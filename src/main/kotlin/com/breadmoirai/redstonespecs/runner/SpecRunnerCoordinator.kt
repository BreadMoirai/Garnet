package com.breadmoirai.redstonespecs.runner

import com.breadmoirai.redstonespecs.block.SpecBlockEntity
import com.breadmoirai.redstonespecs.data.Phase
import com.breadmoirai.redstonespecs.data.TestResult
import com.breadmoirai.redstonespecs.data.TickCheck
import com.breadmoirai.redstonespecs.network.TestResultS2CPayload
import net.fabricmc.fabric.api.networking.v1.PlayerLookup
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.server.level.ServerLevel
import org.slf4j.LoggerFactory
import java.util.UUID

object SpecRunnerCoordinator {
    private val LOGGER = LoggerFactory.getLogger("Redstone Specs")

    private val runners = HashMap<SpecBlockEntity, SpecRunner>()
    private val snapshots = HashMap<SpecBlockEntity, SpecSnapshot>()
    private val stateRecorders = HashMap<SpecBlockEntity, StateRecorder>()

    fun startRun(be: SpecBlockEntity) {
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

        val snapshot = snapshots[be]!!
        snapshot.restore(level)
        val runner = SpecRunner(spec, be.blockPos, level, snapshot)
        runner.start()
        runners[be] = runner
    }

    fun resetSpec(be: SpecBlockEntity) {
        LOGGER.debug("[SpecRunnerCoordinator#resetSpec] resetting spec at {}", be.blockPos)
        stateRecorders.remove(be)?.let { StateRecorder.deactivate(it) }
        runners.remove(be)
        val snapshot = snapshots.remove(be)
        val level = be.level as? ServerLevel ?: return
        snapshot?.restore(level)
    }

    fun onPhase(level: ServerLevel, phase: Phase) {
        for (recorder in StateRecorder.activeRecorders()) {
            if (phase == Phase.START_OF_TICK) recorder.onTickStart()
            recorder.onPhaseStart(phase)
        }
        tickRunners(level, phase)
    }

    private fun tickRunners(level: ServerLevel, phase: Phase) {
        val completed = mutableListOf<SpecBlockEntity>()
        for ((be, runner) in runners) {
            if (be.level !== level) continue
            if (runner.onPhase(phase)) completed += be
        }
        for (be in completed) {
            runners.remove(be)
            finishRun(be)
        }
    }

    private fun finishRun(be: SpecBlockEntity) {
        val recorder = stateRecorders.remove(be)
        if (recorder != null) StateRecorder.deactivate(recorder)
        val level = be.level as? ServerLevel ?: return
        val recording = recorder?.toRecording()
        if (recording != null) StateRecordingStorage.save(level, recording)
        val spec = be.spec ?: return
        val snapshot = snapshots.remove(be)
        snapshot?.restore(level)

        val checks: List<TickCheck> = if (recording != null) {
            OutputVerifier.verify(spec, recording).checks
        } else {
            emptyList()
        }

        LOGGER.debug("[SpecRunnerCoordinator#finishRun] spec '{}' done: {}/{} checks passed",
            spec.id, checks.count { it.pass }, checks.size)
        val testResult = TestResult(spec.id, System.currentTimeMillis(), checks)
        be.setLastTestResult(testResult)
        PlayerLookup.level(level).forEach { player ->
            ServerPlayNetworking.send(player, TestResultS2CPayload(be.blockPos, testResult))
        }
    }
}
