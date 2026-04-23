package com.breadmoirai.redstonespecs.runner

import com.breadmoirai.redstonespecs.block.RedstoneSpecBlockEntity
import com.breadmoirai.redstonespecs.data.Phase
import com.breadmoirai.redstonespecs.data.TickCheck
import com.breadmoirai.redstonespecs.data.TestResult
import com.breadmoirai.redstonespecs.network.BreakpointHitS2CPayload
import com.breadmoirai.redstonespecs.network.TestResultS2CPayload
import net.fabricmc.fabric.api.networking.v1.PlayerLookup
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import org.slf4j.LoggerFactory
import java.util.UUID

object SpecRunnerCoordinator {
    private val LOGGER = LoggerFactory.getLogger("Redstone Specs")

    private val runners = HashMap<RedstoneSpecBlockEntity, SpecRunner>()
    private val snapshots = HashMap<RedstoneSpecBlockEntity, SpecSnapshot>()
    private val stateRecorders = HashMap<RedstoneSpecBlockEntity, StateRecorder>()

    fun startRun(be: RedstoneSpecBlockEntity) {
        if (runners.containsKey(be)) return
        val spec = be.spec ?: return
        val level = be.level as? ServerLevel ?: return

        LOGGER.debug("[SpecRunnerCoordinator#startRun] starting '{}'", spec.id)
        snapshots[be] = SpecSnapshot.capture(level, be.blockPos, spec.bounds)
        val recorderId = UUID.randomUUID()
        val recorder = StateRecorder.forSpec(recorderId, be.blockPos, spec.bounds)
        recorder.start(level, be.blockPos, spec.bounds)
        stateRecorders[be] = recorder
        StateRecorder.activate(recorder)

        val boundsWorldMin = BlockPos(
            be.blockPos.x + spec.bounds.minX(),
            be.blockPos.y + spec.bounds.minY(),
            be.blockPos.z + spec.bounds.minZ(),
        )
        val view = StateRecordingView.of(recorder)
        val snapshot = snapshots[be]!!
        snapshot.restore(level)
        val runner = SpecRunner(spec, be.blockPos, level, snapshot, view, boundsWorldMin)
        runner.start()
        runners[be] = runner
    }

    fun resetSpec(be: RedstoneSpecBlockEntity) {
        LOGGER.debug("[SpecRunnerCoordinator#resetSpec] resetting spec at {}", be.blockPos)
        if (stateRecorders.remove(be) != null) StateRecorder.deactivate()
        runners.remove(be)
        val snapshot = snapshots.remove(be)
        val level = be.level as? ServerLevel ?: return
        snapshot?.restore(level)
    }

    fun resumeSpec(be: RedstoneSpecBlockEntity) {
        LOGGER.debug("[SpecRunnerCoordinator#resumeSpec] resuming spec at {}", be.blockPos)
        runners[be]?.resume()
    }

    fun onPhase(level: ServerLevel, phase: Phase) {
        val recorder = StateRecorder.active
        if (recorder != null) {
            if (phase == Phase.START_OF_TICK) recorder.onTickStart()
            recorder.onPhaseStart(phase)
        }
        tickRunners(level, phase)
    }

    private fun tickRunners(level: ServerLevel, phase: Phase) {
        val completed = mutableListOf<Pair<RedstoneSpecBlockEntity, List<TickCheck>>>()

        for ((be, runner) in runners) {
            if (be.level !== level) continue
            val result = runner.onPhase(phase)

            val bpHit = runner.pendingBreakpointHit
            if (bpHit != null) {
                runner.clearPendingBreakpointHit()
                PlayerLookup.level(level).forEach { player ->
                    ServerPlayNetworking.send(player, BreakpointHitS2CPayload(
                        be.blockPos, bpHit.simTime, bpHit.specId, bpHit.breakpointLabel,
                    ))
                }
            }

            if (result != null) {
                completed += be to result
            }
        }

        for ((be, checks) in completed) {
            runners.remove(be)
            finishRun(be, checks)
        }
    }

    private fun finishRun(be: RedstoneSpecBlockEntity, checks: List<TickCheck>) {
        val recorder = stateRecorders.remove(be)
        if (recorder != null) StateRecorder.deactivate()
        val level = be.level as? ServerLevel ?: return
        if (recorder != null) StateRecordingStorage.save(level, recorder.toRecording())
        val spec = be.spec ?: return
        val snapshot = snapshots.remove(be)
        snapshot?.restore(level)

        LOGGER.debug("[SpecRunnerCoordinator#finishRun] spec '{}' done: {}/{} checks passed",
            spec.id, checks.count { it.pass }, checks.size)
        val testResult = TestResult(spec.id, System.currentTimeMillis(), checks)
        be.setLastTestResult(testResult)
        PlayerLookup.level(level).forEach { player ->
            ServerPlayNetworking.send(player, TestResultS2CPayload(be.blockPos, testResult))
        }
    }
}
