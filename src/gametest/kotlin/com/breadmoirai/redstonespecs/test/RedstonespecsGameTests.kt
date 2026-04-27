package com.breadmoirai.redstonespecs.test

import com.breadmoirai.redstonespecs.ModRegistries
import com.breadmoirai.redstonespecs.block.SpecBlockEntity
import com.breadmoirai.redstonespecs.data.InputSpec
import com.breadmoirai.redstonespecs.data.OutputSpec
import com.breadmoirai.redstonespecs.data.Phase
import com.breadmoirai.redstonespecs.data.SimTime
import com.breadmoirai.redstonespecs.runner.SpecRunnerCoordinator
import com.breadmoirai.redstonespecs.runner.propsToCondition
import net.fabricmc.fabric.api.gametest.v1.GameTest
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.LeverBlock
import net.minecraft.world.level.block.RedstoneWallTorchBlock
import net.minecraft.world.level.block.state.properties.AttachFace

/**
 * Server-side counterpart to [RedstonespecsClientTests.recorderToEditorToRunnerFlow].
 *
 * Drives the full record → finalize → editor → runner → run pipeline through direct
 * BE / coordinator calls (no client, no UI screens). Each scenario describes a small
 * world layout, which blocks are inputs/outputs, and how to mutate the world during
 * recording. The shared [runRecorderScenario] handles the rest.
 */
class RedstonespecsGameTests {

    /**
     * Trivial direct-attachment circuit: a floor-lever sits on top of a redstone
     * lamp. Toggling the lever directly powers the lamp via its attachment block.
     */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 200)
    fun recorderFlowLeverLampDirect(helper: GameTestHelper) {
        val lampPos = BlockPos(2, 0, 1)
        val leverPos = BlockPos(2, 1, 1)
        runRecorderScenario(helper, RecorderScenario(
            recorderRelPos = BlockPos(0, 0, 0),
            placeBlocks = { h ->
                h.setBlock(lampPos, Blocks.REDSTONE_LAMP.defaultBlockState())
                h.setBlock(leverPos, Blocks.LEVER.defaultBlockState()
                    .setValue(LeverBlock.FACE, AttachFace.FLOOR))
            },
            inputs = listOf(leverPos),
            outputs = listOf(lampPos),
            drive = { h -> h.useBlock(leverPos) },
        ))
    }

    /**
     * Hard-power chain: lever → smooth_stone → redstone wall torch. Toggling the
     * lever hard-powers the stone, which deactivates the torch on the adjacent face.
     * Exercises full lever neighbor-update propagation through a non-wire block.
     */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 200)
    fun recorderFlowLeverStoneTorch(helper: GameTestHelper) {
        val stonePos = BlockPos(2, 0, 1)
        val leverPos = BlockPos(2, 1, 1)
        val torchPos = BlockPos(3, 0, 1)
        runRecorderScenario(helper, RecorderScenario(
            recorderRelPos = BlockPos(0, 0, 0),
            placeBlocks = { h ->
                h.setBlock(stonePos, Blocks.SMOOTH_STONE.defaultBlockState())
                h.setBlock(leverPos, Blocks.LEVER.defaultBlockState()
                    .setValue(LeverBlock.FACE, AttachFace.FLOOR))
                // Wall torch on the east face of the stone — torch's FACING is
                // the direction it points (away from its attachment block).
                h.setBlock(torchPos, Blocks.REDSTONE_WALL_TORCH.defaultBlockState()
                    .setValue(RedstoneWallTorchBlock.FACING, Direction.EAST))
            },
            inputs = listOf(leverPos),
            outputs = listOf(torchPos),
            drive = { h -> h.useBlock(leverPos) },
        ))
    }

    // ── Shared scenario runner ────────────────────────────────────────────────

    /**
     * @param recorderRelPos position of the recorder block in helper-local coords.
     * @param placeBlocks places the input/output blocks (helper-local coords).
     * @param inputs world positions (helper-local) of input marker blocks.
     * @param outputs world positions (helper-local) of output marker blocks.
     * @param drive mutates the world during recording (e.g. toggles a lever).
     * @param recordingTicks number of ticks to wait between [drive] and stop.
     */
    private data class RecorderScenario(
        val recorderRelPos: BlockPos,
        val placeBlocks: (GameTestHelper) -> Unit,
        val inputs: List<BlockPos>,
        val outputs: List<BlockPos>,
        val drive: (GameTestHelper) -> Unit,
        val recordingTicks: Int = 6,
    )

    private fun runRecorderScenario(helper: GameTestHelper, scenario: RecorderScenario) {
        val level = helper.level
        val recorderAbs = helper.absolutePos(scenario.recorderRelPos)

        helper.startSequence()
            .thenExecute {
                level.setBlock(recorderAbs, ModRegistries.REDSTONE_SPEC_RECORDER_BLOCK.defaultBlockState(), 3)
                scenario.placeBlocks(helper)
                val be = beAt(level, recorderAbs)
                applyMarkers(level, helper, be, scenario)
                check(be.startRecording()) { "startRecording returned false (gating not satisfied)" }
            }
            .thenIdle(2)
            .thenExecute { scenario.drive(helper) }
            .thenIdle(scenario.recordingTicks)
            .thenExecute {
                val be = beAt(level, recorderAbs)
                check(be.stopRecordingAndFinalize()) { "stopRecordingAndFinalize returned false" }
                be.transformTo(ModRegistries.REDSTONE_SPEC_EDITOR_BLOCK)
            }
            .thenIdle(1)
            .thenExecute {
                val be = beAt(level, recorderAbs)
                be.transformTo(ModRegistries.REDSTONE_SPEC_RUNNER_BLOCK)
            }
            .thenIdle(1)
            .thenExecute {
                val be = beAt(level, recorderAbs)
                SpecRunnerCoordinator.startRun(be)
            }
            .thenWaitUntil {
                val be = beAt(level, recorderAbs)
                if (be.lastTestResult == null) throw helper.assertionException("lastTestResult not yet set")
            }
            .thenExecute {
                val be = beAt(level, recorderAbs)
                val result = be.lastTestResult!!
                if (result.checks.isEmpty()) helper.fail("expected at least one check in result")
                val failed = result.checks.filter { !it.pass }
                if (failed.isNotEmpty()) {
                    helper.fail("failed checks: " + failed.joinToString {
                        "${it.label}@${it.simTime}: expected=${it.expected} actual=${it.actual}"
                    })
                }
            }
            .thenSucceed()
    }

    private fun beAt(level: net.minecraft.server.level.ServerLevel, pos: BlockPos): SpecBlockEntity =
        level.getBlockEntity(pos) as? SpecBlockEntity
            ?: error("SpecBlockEntity not found at $pos")

    private fun applyMarkers(
        level: net.minecraft.server.level.ServerLevel,
        helper: GameTestHelper,
        be: SpecBlockEntity,
        scenario: RecorderScenario,
    ) {
        val spec = be.spec ?: error("recorder has no default spec at ${be.blockPos}")
        scenario.inputs.forEachIndexed { i, relPos ->
            val worldPos = helper.absolutePos(relPos)
            val state = level.getBlockState(worldPos)
            val specRelPos = worldPos.subtract(be.blockPos)
            val name = BuiltInRegistries.BLOCK.getKey(state.block).path
            be.addOrUpdateEntry(InputSpec(
                specRelPos, "in_${name}_$i", 0x4488FF,
                listOf(SimTime.INIT to propsToCondition(emptyMap(), state)),
            ))
        }
        scenario.outputs.forEachIndexed { i, relPos ->
            val worldPos = helper.absolutePos(relPos)
            val state = level.getBlockState(worldPos)
            val specRelPos = worldPos.subtract(be.blockPos)
            val name = BuiltInRegistries.BLOCK.getKey(state.block).path
            // Time and condition are placeholders — RecordingFinalizer rederives them.
            be.addOrUpdateEntry(OutputSpec(
                specRelPos, "out_${name}_$i", 0xFF8800,
                listOf(SimTime(spec.lifespan, Phase.END_OF_TICK) to propsToCondition(emptyMap(), state)),
            ))
        }
    }
}
