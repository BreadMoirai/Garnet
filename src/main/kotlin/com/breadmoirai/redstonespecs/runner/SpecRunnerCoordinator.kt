package com.breadmoirai.redstonespecs.runner

import com.breadmoirai.redstonespecs.block.SpecBlockEntity
import com.breadmoirai.redstonespecs.data.Phase
import com.breadmoirai.redstonespecs.data.SimTime
import com.breadmoirai.redstonespecs.data.TestResult
import com.breadmoirai.redstonespecs.data.TickCheck
import com.breadmoirai.redstonespecs.network.TestResultS2CPayload
import net.fabricmc.fabric.api.networking.v1.PlayerLookup
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.server.level.ServerLevel
import org.slf4j.LoggerFactory

object SpecRunnerCoordinator {
    private val LOGGER = LoggerFactory.getLogger("Redstone Specs")
    private val standaloneRunners = mutableListOf<SpecRunner>()

    fun registerStandalone(runner: SpecRunner) { standaloneRunners += runner }
    fun unregisterStandalone(runner: SpecRunner) { standaloneRunners -= runner }

    fun startRun(be: SpecBlockEntity) {
        val spec = be.spec ?: return
        val level = be.level as? ServerLevel ?: return
        LOGGER.debug("[SpecRunnerCoordinator#startRun] launching '{}' via Kotest engine", spec.id)

        Thread({
            val testResult = try {
                EngineDrivenRun.run(spec, be.blockPos, level)
            } catch (t: Throwable) {
                LOGGER.warn("[SpecRunnerCoordinator] engine crashed for '{}'", spec.id, t)
                TestResult(spec.id, System.currentTimeMillis(), listOf(
                    TickCheck(SimTime.START, "engine-error",
                        expected = "ok", actual = t.message ?: "(no message)", pass = false),
                ))
            }
            level.server.execute {
                be.setLastTestResult(testResult)
                PlayerLookup.level(level).forEach { player ->
                    ServerPlayNetworking.send(player, TestResultS2CPayload(be.blockPos, testResult))
                }
            }
        }, "redstonespecs-engine-launch-${spec.id}").start()
    }

    fun resetSpec(be: SpecBlockEntity) {
        // No BE-tracked state to clear. If SpecBlockEntity has a way to reset
        // lastTestResult, do that here; otherwise no-op.
        LOGGER.debug("[SpecRunnerCoordinator#resetSpec] no-op (engine-driven path)")
    }

    fun onPhase(level: ServerLevel, phase: Phase) {
        for (recorder in StateRecorder.activeRecorders()) {
            if (phase == Phase.START_OF_TICK) recorder.onTickStart()
            recorder.onPhaseStart(phase)
        }
        val completed = mutableListOf<SpecRunner>()
        for (runner in standaloneRunners) {
            if (runner.level !== level) continue
            if (runner.onPhase(phase)) completed += runner
        }
        standaloneRunners.removeAll(completed)
    }
}
