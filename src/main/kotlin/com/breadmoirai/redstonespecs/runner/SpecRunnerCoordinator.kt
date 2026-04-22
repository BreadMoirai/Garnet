package com.breadmoirai.redstonespecs.runner

import com.breadmoirai.redstonespecs.block.SpecOriginBlockEntity
import com.breadmoirai.redstonespecs.data.Phase
import com.breadmoirai.redstonespecs.data.SpecCaseResult
import com.breadmoirai.redstonespecs.data.TestResult
import com.breadmoirai.redstonespecs.network.AutoSpecRecordedS2CPayload
import com.breadmoirai.redstonespecs.network.BreakpointHitS2CPayload
import com.breadmoirai.redstonespecs.network.TestResultS2CPayload
import net.fabricmc.fabric.api.networking.v1.PlayerLookup
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import org.slf4j.LoggerFactory

object SpecRunnerCoordinator {
    private val LOGGER = LoggerFactory.getLogger("Redstone Specs")

    private val runners = HashMap<SpecOriginBlockEntity, SpecRunner>()
    private val queues = HashMap<SpecOriginBlockEntity, ArrayDeque<Int>>()
    private val snapshots = HashMap<SpecOriginBlockEntity, SpecSnapshot>()
    private val results = HashMap<SpecOriginBlockEntity, MutableList<SpecCaseResult>>()

    // AutoSpec monitoring: (be, caseIndex, autoSpec.relPos) → active recorder
    private val autoSpecRecorders =
        HashMap<Triple<SpecOriginBlockEntity, Int, BlockPos>, AutoSpecRecorder>()

    fun startRun(be: SpecOriginBlockEntity, runAll: Boolean) {
        if (runners.containsKey(be)) return
        val spec = be.spec ?: return
        val level = be.level as? ServerLevel ?: return

        val caseIndices = if (runAll) {
            spec.specCases.indices.toList()
        } else {
            listOf(be.activeSpecCaseIndex)
        }

        LOGGER.debug("[SpecRunnerCoordinator#startRun] starting '{}' runAll={} cases={}", spec.name, runAll, caseIndices)
        snapshots[be] = SpecSnapshot.capture(level, be.blockPos, spec.bounds)
        results[be] = mutableListOf()
        queues[be] = ArrayDeque(caseIndices)
        startNextCase(be)
    }

    fun resetSpec(be: SpecOriginBlockEntity) {
        LOGGER.debug("[SpecRunnerCoordinator#resetSpec] resetting spec at {}", be.blockPos)
        runners.remove(be)
        queues.remove(be)
        val snapshot = snapshots.remove(be)
        results.remove(be)
        val level = be.level as? ServerLevel ?: return
        snapshot?.restore(level)
    }

    fun resumeSpec(be: SpecOriginBlockEntity) {
        LOGGER.debug("[SpecRunnerCoordinator#resumeSpec] resuming spec at {}", be.blockPos)
        runners[be]?.resume()
    }

    fun onPhase(level: ServerLevel, phase: Phase) {
        tickRunners(level, phase)
        monitorAutoSpecs(level, phase)
    }

    private fun tickRunners(level: ServerLevel, phase: Phase) {
        val completed = mutableListOf<SpecOriginBlockEntity>()

        for ((be, runner) in runners) {
            if (be.level !== level) continue

            val result = runner.onPhase(phase)

            val bpHit = runner.pendingBreakpointHit
            if (bpHit != null) {
                runner.clearPendingBreakpointHit()
                PlayerLookup.level(level).forEach { player ->
                    ServerPlayNetworking.send(
                        player,
                        BreakpointHitS2CPayload(
                            be.blockPos, bpHit.simTime,
                            bpHit.specName, bpHit.caseName, bpHit.breakpointLabel,
                        ),
                    )
                }
            }

            if (result != null) {
                results[be]?.add(result)
                completed += be
            }
        }

        for (be in completed) {
            runners.remove(be)
            startNextCase(be)
        }
    }

    private fun startNextCase(be: SpecOriginBlockEntity) {
        val queue = queues[be] ?: return
        if (queue.isEmpty()) {
            finishRun(be)
            return
        }
        val caseIndex = queue.removeFirst()
        val spec = be.spec ?: run { finishRun(be); return }
        val specCase = spec.specCases.getOrNull(caseIndex) ?: run { startNextCase(be); return }
        val level = be.level as? ServerLevel ?: return
        val snapshot = snapshots[be] ?: return

        LOGGER.debug("[SpecRunnerCoordinator#startNextCase] starting case '{}' (index={}) remaining={}", specCase.name, caseIndex, queue.size)
        snapshot.restore(level)
        val runner = SpecRunner(spec, specCase, be.blockPos, level, snapshot)
        runner.start()
        runners[be] = runner
    }

    private fun finishRun(be: SpecOriginBlockEntity) {
        val spec = be.spec ?: return
        val resultList = results.remove(be) ?: mutableListOf()
        val snapshot = snapshots.remove(be)
        queues.remove(be)
        val level = be.level as? ServerLevel ?: return
        snapshot?.restore(level)

        val passCount = resultList.count { r -> r.checks.all { it.pass } }
        LOGGER.debug("[SpecRunnerCoordinator#finishRun] spec '{}' done: {}/{} cases passed", spec.name, passCount, resultList.size)
        val testResult = TestResult(spec.id, System.currentTimeMillis(), resultList)
        be.setLastTestResult(testResult)
        PlayerLookup.level(level).forEach { player ->
            ServerPlayNetworking.send(player, TestResultS2CPayload(be.blockPos, testResult))
        }
    }

    private fun monitorAutoSpecs(level: ServerLevel, phase: Phase) {
        for (be in SpecOriginBlockEntity.allFor(level)) {
            if (runners.containsKey(be)) continue
            val spec = be.spec ?: continue

            for ((caseIndex, specCase) in spec.specCases.withIndex()) {
                for (autoSpec in specCase.autoSpecs) {
                    val worldPos = relToWorld(be.blockPos, autoSpec.pos)
                    val key = Triple(be, caseIndex, autoSpec.pos)
                    val existing = autoSpecRecorders[key]
                    val isActive = evaluateCondition(autoSpec.condition, level, worldPos)

                    when {
                        existing == null && isActive -> {
                            LOGGER.debug("[SpecRunnerCoordinator#monitorAutoSpecs] autoSpec '{}' activated at {}", autoSpec.label, autoSpec.pos)
                            val recorder = AutoSpecRecorder(autoSpec, be.blockPos, level, specCase)
                            recorder.start()
                            autoSpecRecorders[key] = recorder
                        }
                        existing != null && isActive -> existing.onPhase(phase)
                        existing != null && !isActive -> {
                            autoSpecRecorders.remove(key)
                            val newCase = existing.commit()
                            LOGGER.debug("[SpecRunnerCoordinator#monitorAutoSpecs] autoSpec '{}' committed as case '{}'", autoSpec.label, newCase.name)
                            be.addOrUpdateSpecCase(newCase)
                            PlayerLookup.level(level).forEach { player ->
                                ServerPlayNetworking.send(
                                    player,
                                    AutoSpecRecordedS2CPayload(be.blockPos, newCase.name),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    private fun relToWorld(origin: BlockPos, rel: BlockPos) =
        BlockPos(origin.x + rel.x, origin.y + rel.y, origin.z + rel.z)
}
