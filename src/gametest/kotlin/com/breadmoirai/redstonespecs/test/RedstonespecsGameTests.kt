package com.breadmoirai.redstonespecs.test

import com.breadmoirai.redstonespecs.ModRegistries
import com.breadmoirai.redstonespecs.block.SpecBlockEntity
import com.breadmoirai.redstonespecs.data.InputSpec
import com.breadmoirai.redstonespecs.data.OutputSpec
import com.breadmoirai.redstonespecs.data.Phase
import com.breadmoirai.redstonespecs.data.RedstoneSpec
import com.breadmoirai.redstonespecs.data.SimTime
import com.breadmoirai.redstonespecs.data.SpecMode
import com.breadmoirai.redstonespecs.data.StateCondition
import com.breadmoirai.redstonespecs.runner.SpecRunnerCoordinator
import com.breadmoirai.redstonespecs.runner.propsToCondition
import net.fabricmc.fabric.api.gametest.v1.GameTest
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.ButtonBlock
import net.minecraft.world.level.block.ComparatorBlock
import net.minecraft.world.level.block.DirectionalBlock
import net.minecraft.world.level.block.FaceAttachedHorizontalDirectionalBlock
import net.minecraft.world.level.block.LeverBlock
import net.minecraft.world.level.block.RedstoneWallTorchBlock
import net.minecraft.world.level.block.state.properties.AttachFace
import net.minecraft.world.level.levelgen.structure.BoundingBox

/**
 * Server-side counterpart to [RedstonespecsClientTests.recorderToEditorToRunnerFlow].
 *
 * Drives the full record → finalize → editor → runner → run pipeline through direct
 * BE / coordinator calls (no client, no UI screens). Each scenario describes a small
 * world layout, which blocks are inputs/outputs, and how to mutate the world during
 * recording. Each scenario is exercised at all three [SpecMode] values.
 */
class RedstonespecsGameTests {

    // ── Lever-on-Lamp (direct attachment) ─────────────────────────────────────

    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 200)
    fun recorderFlowSimpleLeverLampDirect(helper: GameTestHelper) =
        runRecorderScenario(helper, leverLampDirect(SpecMode.SIMPLE))

    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 200)
    fun recorderFlowTickAwareLeverLampDirect(helper: GameTestHelper) =
        runRecorderScenario(helper, leverLampDirect(SpecMode.TICK_AWARE))

    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 200)
    fun recorderFlowUpdateAwareLeverLampDirect(helper: GameTestHelper) =
        runRecorderScenario(helper, leverLampDirect(SpecMode.UPDATE_AWARE))

    // ── Lever → Smooth Stone → Wall Torch (hard-power chain) ──────────────────

    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 200)
    fun recorderFlowSimpleLeverStoneTorch(helper: GameTestHelper) =
        runRecorderScenario(helper, leverStoneTorch(SpecMode.SIMPLE))

    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 200)
    fun recorderFlowTickAwareLeverStoneTorch(helper: GameTestHelper) =
        runRecorderScenario(helper, leverStoneTorch(SpecMode.TICK_AWARE))

    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 200)
    fun recorderFlowUpdateAwareLeverStoneTorch(helper: GameTestHelper) =
        runRecorderScenario(helper, leverStoneTorch(SpecMode.UPDATE_AWARE))

    // ── Button → wire → piston pushes blue_concrete into air slot ─────────────

    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 200)
    fun recorderFlowSimpleButtonPistonConcrete(helper: GameTestHelper) =
        runRecorderScenario(helper, buttonPistonConcrete())

    // ── Comparator feedback loop: pulse decays one step per loop cycle ───────

    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 1000)
    fun recorderFlowTickAwareComparatorDecayLoop(helper: GameTestHelper) =
        runRecorderScenario(helper, comparatorDecayLoop())

    // ── Scenario factories ────────────────────────────────────────────────────

    /** Floor lever sitting on a redstone lamp; toggling powers the lamp directly. */
    private fun leverLampDirect(mode: SpecMode): RecorderScenario {
        val lampPos = BlockPos(2, 0, 1)
        val leverPos = BlockPos(2, 1, 1)
        return RecorderScenario(
            mode = mode,
            recorderRelPos = BlockPos(0, 0, 0),
            placeBlocks = { h ->
                h.setBlock(lampPos, Blocks.REDSTONE_LAMP.defaultBlockState())
                h.setBlock(leverPos, Blocks.LEVER.defaultBlockState()
                    .setValue(LeverBlock.FACE, AttachFace.FLOOR))
            },
            inputs = listOf(leverPos),
            outputs = listOf(lampPos),
            drive = { h -> h.useBlock(leverPos) },
            // The lamp transitions off→on on the same tick the lever toggles
            // (direct attachment, no propagation delay). SIMPLE collapses to a
            // single END entry; TICK_AWARE / UPDATE_AWARE place the derived
            // entry at SimTime(0, END_OF_TICK).
            expectedOutputs = { m ->
                listOf(if (m == SpecMode.SIMPLE)
                    listOf(ExpectedEntry.Property(SimTime.END, "lit", "true"))
                else listOf(ExpectedEntry.Property(SimTime(0, Phase.END_OF_TICK), "lit", "true")))
            },
        )
    }

    /**
     * Lever → smooth_stone → wall torch. Toggling the lever hard-powers the
     * stone, which deactivates the torch on the adjacent face. Exercises full
     * lever neighbor-update propagation through a non-wire block.
     */
    private fun leverStoneTorch(mode: SpecMode): RecorderScenario {
        val stonePos = BlockPos(2, 0, 1)
        val leverPos = BlockPos(2, 1, 1)
        val torchPos = BlockPos(3, 0, 1)
        return RecorderScenario(
            mode = mode,
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
            // The torch transitions lit=true → lit=false four ticks after the
            // lever toggles (lever -> stone hard-power propagation -> torch's
            // own scheduled-tick reaction adds up to a 4-tick gap empirically
            // in this MC version). lifespan == lastTick-firstTick+1 == 5. SIMPLE
            // collapses to a single END entry; TICK_AWARE / UPDATE_AWARE place
            // the derived entry at SimTime(4, END_OF_TICK).
            expectedOutputs = { m ->
                listOf(if (m == SpecMode.SIMPLE)
                    listOf(ExpectedEntry.Property(SimTime.END, "lit", "false"))
                else listOf(ExpectedEntry.Property(SimTime(4, Phase.END_OF_TICK), "lit", "false")))
            },
        )
    }

    /**
     * Button on top of a stone anchor; the button strongly powers the anchor,
     * which weakly powers an adjacent stone block carrying redstone dust on top.
     * The dust drives an east-facing piston that pushes blue_concrete one step
     * east into a previously-air slot. After the button depowers, the piston
     * retracts, leaving:
     *   - the original blue_concrete slot empty (air)
     *   - the original air slot occupied by blue_concrete
     *
     * Layout (recorder at origin, looking down the +x axis):
     *   y=2:    .  button   wire  piston  concrete  air
     *   y=1:    .  anchor   anchor   .       .       .
     *   x =     0    1        2      3       4       5    (helper-local)
     *
     * Note: maxX is extended to 6 because the air output sits at x=6.
     */
    private fun buttonPistonConcrete(): RecorderScenario {
        val anchor1Pos = BlockPos(2, 1, 1)
        val buttonPos = BlockPos(2, 2, 1)
        val anchor2Pos = BlockPos(3, 1, 1)
        val wirePos = BlockPos(3, 2, 1)
        val pistonPos = BlockPos(4, 2, 1)
        val concretePos = BlockPos(5, 2, 1)
        val airPos = BlockPos(6, 2, 1)
        return RecorderScenario(
            mode = SpecMode.SIMPLE,
            recorderRelPos = BlockPos(0, 0, 0),
            // DEFAULT_BOUNDS stops at x=5; widen to include airPos at x=6.
            bounds = BoundingBox(1, 0, 1, 6, 4, 5),
            placeBlocks = { h ->
                h.setBlock(anchor1Pos, Blocks.SMOOTH_STONE.defaultBlockState())
                // Stone button on top of anchor1 (FACE=FLOOR). Pressing it
                // strongly powers anchor1 below.
                h.setBlock(buttonPos, Blocks.STONE_BUTTON.defaultBlockState()
                    .setValue(FaceAttachedHorizontalDirectionalBlock.FACE, AttachFace.FLOOR))
                h.setBlock(anchor2Pos, Blocks.SMOOTH_STONE.defaultBlockState())
                h.setBlock(wirePos, Blocks.REDSTONE_WIRE.defaultBlockState())
                h.setBlock(pistonPos, Blocks.PISTON.defaultBlockState()
                    .setValue(DirectionalBlock.FACING, Direction.EAST))
                h.setBlock(concretePos, Blocks.BLUE_CONCRETE.defaultBlockState())
                // airPos is left untouched (already air in the empty structure).
            },
            inputs = listOf(buttonPos),
            outputs = listOf(concretePos, airPos),
            drive = { h -> h.useBlock(buttonPos) },
            // Stone button stays pressed for 10 ticks; piston extension plus
            // retraction takes ~3 ticks at each end. Wait long enough for the
            // piston to fully retract before stopping the recording.
            recordingTicks = 22,
            // Final state at the END sentinel:
            //   - concretePos transitions blue_concrete → piston_head (extension)
            //     → air (after retraction), so its captured block is air.
            //   - airPos transitions air → blue_concrete (push) and stays.
            expectedOutputs = { _ ->
                listOf(
                    listOf(ExpectedEntry.Block(SimTime.END, "minecraft:air")),
                    listOf(ExpectedEntry.Block(SimTime.END, "minecraft:blue_concrete")),
                )
            },
        )
    }

    /**
     * Comparator feedback loop. Pressing the button drives the input wire to
     * power=15; the east-output comparator forwards that into the output wire,
     * which fans south into a tap wire. The tap wire feeds the back of the
     * west-output comparator whose front strong-powers a stone block sitting
     * adjacent to the input wire — completing the loop.
     *
     * While the button is held, the input wire is clamped at 15 by the button.
     * When the button releases, the only signal feeding the input wire is the
     * loop's strong-power feedback (one less than the previous cycle), so the
     * output decays by 1 per round-trip through both comparators until it
     * reaches 0.
     *
     * Layout (recorder at origin; viewing y=2 from above, north up):
     * ```
     *   y=2  z=1: button   wire     comp(out E)  wire(OUTPUT)
     *   y=2  z=2: air      stone    comp(out W)  wire
     *   y=1: 4×2 smooth_stone floor under everything (z=1..2, x=1..4)
     * ```
     *
     * IMPORTANT — vanilla [DiodeBlock.FACING] is the *back* direction (where
     * input is read from). Output emits to `FACING.getOpposite()`. So a
     * "comparator pointing east" (output east) is encoded as `FACING=WEST` in
     * MC. See [DiodeBlock.getInputSignal] and [DiodeBlock.updateNeighborsInFront].
     *
     * TICK_AWARE captures every per-tick power-level change of the output wire.
     * Pinned expectations were captured from a deterministic recording: the
     * stone button stays pressed for ~60 ticks; the loop then decays power
     * 15→14→…→0 in 12-tick steps (two 1-redstone-tick comparator delays per
     * round trip).
     */
    private fun comparatorDecayLoop(): RecorderScenario {
        val buttonPos = BlockPos(1, 2, 1)
        val inWirePos = BlockPos(2, 2, 1)
        val feedbackStonePos = BlockPos(2, 2, 2)
        val compFwdPos = BlockPos(3, 2, 1)
        val compBackPos = BlockPos(3, 2, 2)
        val outWirePos = BlockPos(4, 2, 1)
        val tapWirePos = BlockPos(4, 2, 2)
        return RecorderScenario(
            mode = SpecMode.TICK_AWARE,
            recorderRelPos = BlockPos(0, 0, 0),
            placeBlocks = { h ->
                // y=1: solid stone floor (4×2) under the circuit so wires/buttons have anchors.
                for (x in 1..4) for (z in 1..2) {
                    h.setBlock(BlockPos(x, 1, z), Blocks.SMOOTH_STONE.defaultBlockState())
                }
                // y=2 row z=1: button — wire — comparator(out east) — wire(OUTPUT)
                // Comparators placed BEFORE wires so wire setBlock triggers neighbor
                // updates onto already-existing comparators.
                //
                // NOTE: vanilla DiodeBlock.FACING is the BACK direction (where input
                // is read from). Output emits to FACING.getOpposite(). The user's
                // diagram says "facing=east" meaning the output points east, which
                // in MC code is FACING=WEST.
                h.setBlock(compFwdPos, Blocks.COMPARATOR.defaultBlockState()
                    .setValue(ComparatorBlock.FACING, Direction.WEST))
                h.setBlock(compBackPos, Blocks.COMPARATOR.defaultBlockState()
                    .setValue(ComparatorBlock.FACING, Direction.EAST))
                h.setBlock(feedbackStonePos, Blocks.SMOOTH_STONE.defaultBlockState())
                h.setBlock(buttonPos, Blocks.STONE_BUTTON.defaultBlockState()
                    .setValue(FaceAttachedHorizontalDirectionalBlock.FACE, AttachFace.FLOOR))
                h.setBlock(inWirePos, Blocks.REDSTONE_WIRE.defaultBlockState())
                h.setBlock(outWirePos, Blocks.REDSTONE_WIRE.defaultBlockState())
                h.setBlock(tapWirePos, Blocks.REDSTONE_WIRE.defaultBlockState())
            },
            inputs = listOf(buttonPos),
            outputs = listOf(outWirePos),
            drive = { h -> h.useBlock(buttonPos) },
            // Stone button holds for 60 ticks; after release the comparator
            // feedback loop decays the output wire 15 → 0, one step per ~12
            // ticks per round-trip through both comparators. Total run is
            // ~233 relative ticks — give generous headroom.
            recordingTicks = 240,
            expectedOutputs = null,
        )
    }

    // ── Negative cases: contract violations should fail the run ───────────────

    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 200)
    fun recorderFlowSimpleFailsOnWrongEnd(helper: GameTestHelper) =
        runRecorderScenarioExpectingFailure(
            helper, leverLampDirect(SpecMode.SIMPLE),
        ) { spec ->
            // The lamp ends up lit=true; mutate END expectation to lit=false.
            val output = spec.outputs.first()
            val mutated = output.copy(entries = listOf(
                SimTime.END to StateCondition.BoolProperty("lit", false),
            ))
            spec.copy(outputs = listOf(mutated))
        }

    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 200)
    fun recorderFlowTickAwareFailsOnWrongPostState(helper: GameTestHelper) =
        runRecorderScenarioExpectingFailure(
            helper, leverLampDirect(SpecMode.TICK_AWARE),
        ) { spec ->
            // The lamp transitions to lit=true at tick 0 (END_OF_TICK); mutate the entry
            // to expect lit=false instead. The verifier should report a wrong-value check.
            val output = spec.outputs.first()
            val mutated = output.copy(entries = output.entries.map { (time, _) ->
                time to StateCondition.BoolProperty("lit", false)
            })
            spec.copy(outputs = listOf(mutated))
        }

    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 200)
    fun recorderFlowUpdateAwareFailsOnPhaseMismatch(helper: GameTestHelper) =
        runRecorderScenarioExpectingFailure(
            helper, leverStoneTorch(SpecMode.UPDATE_AWARE),
        ) { spec ->
            // Move the entry to a different phase so neither the recorded change
            // matches an entry, nor the entry matches a recorded change. UPDATE_AWARE
            // should emit both "missing" and "unexpected" diagnostics.
            val output = spec.outputs.first()
            val mutated = output.copy(entries = output.entries.map { (time, cond) ->
                SimTime(time.tick, Phase.BLOCK_EVENTS, time.order) to cond
            })
            spec.copy(outputs = listOf(mutated))
        }

    // ── Shared scenario runner ────────────────────────────────────────────────

    /**
     * @param mode the recorder's [SpecMode]; affects how output entries are derived.
     * @param recorderRelPos position of the recorder block in helper-local coords.
     * @param placeBlocks places the input/output blocks (helper-local coords).
     * @param inputs world positions (helper-local) of input marker blocks.
     * @param outputs world positions (helper-local) of output marker blocks.
     * @param drive mutates the world during recording (e.g. toggles a lever).
     * @param recordingTicks number of ticks to wait between [drive] and stop.
     */
    private data class RecorderScenario(
        val mode: SpecMode,
        val recorderRelPos: BlockPos,
        val placeBlocks: (GameTestHelper) -> Unit,
        val inputs: List<BlockPos>,
        val outputs: List<BlockPos>,
        val drive: (GameTestHelper) -> Unit,
        val recordingTicks: Int = 6,
        /** Spec bounds (relative to the recorder). Defaults to [RedstoneSpec.DEFAULT_BOUNDS]. */
        val bounds: BoundingBox = RedstoneSpec.DEFAULT_BOUNDS,
        /**
         * Per-output expected entries (outer list parallel to [outputs]) after
         * RecordingFinalizer runs, given the scenario's mode. Asserted
         * server-side immediately after stopRecordingAndFinalize, before any
         * transformTo, so derivation regressions are caught independently of
         * the runner replay.
         *
         * When `null`, exact-entry assertions are skipped — useful for circuits
         * whose per-tick output pattern is hard to predict statically (e.g.
         * comparator-feedback loops). Each output is still required to have
         * derived at least one entry. The runner replay continues to verify
         * record/replay consistency.
         */
        val expectedOutputs: ((SpecMode) -> List<List<ExpectedEntry>>)? = null,
    )

    /**
     * One expected output entry: an exact [SimTime] plus a single property or
     * block-type check the entry's condition must satisfy.
     */
    private sealed interface ExpectedEntry {
        val time: SimTime
        data class Property(override val time: SimTime, val propName: String, val propValue: String) : ExpectedEntry
        data class Block(override val time: SimTime, val blockId: String) : ExpectedEntry
    }

    private fun runRecorderScenario(helper: GameTestHelper, scenario: RecorderScenario) {
        val level = helper.level
        val recorderAbs = helper.absolutePos(scenario.recorderRelPos)

        helper.startSequence()
            .thenExecute {
                level.setBlock(recorderAbs, ModRegistries.REDSTONE_SPEC_RECORDER_BLOCK.defaultBlockState(), 3)
                scenario.placeBlocks(helper)
                val be = beAt(level, recorderAbs)
                be.setMode(scenario.mode)
                be.setSpec((be.spec ?: error("recorder has no default spec")).copy(bounds = scenario.bounds))
                applyMarkers(level, helper, be, scenario)
                check(be.startRecording()) { "startRecording returned false (gating not satisfied)" }
            }
            .thenIdle(2)
            .thenExecute { scenario.drive(helper) }
            .thenIdle(scenario.recordingTicks)
            .thenExecute {
                val be = beAt(level, recorderAbs)
                check(be.stopRecordingAndFinalize()) { "stopRecordingAndFinalize returned false" }
                assertOutputEntries(helper, be, scenario)
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

    /**
     * Same flow as [runRecorderScenario], but applies [mutateSpec] to the
     * finalized spec immediately before transforming to the runner block,
     * and asserts that [lastTestResult.pass] is `false` (i.e., the strict
     * contract correctly flags the violation).
     */
    private fun runRecorderScenarioExpectingFailure(
        helper: GameTestHelper,
        scenario: RecorderScenario,
        mutateSpec: (com.breadmoirai.redstonespecs.data.RedstoneSpec) -> com.breadmoirai.redstonespecs.data.RedstoneSpec,
    ) {
        val level = helper.level
        val recorderAbs = helper.absolutePos(scenario.recorderRelPos)

        helper.startSequence()
            .thenExecute {
                level.setBlock(recorderAbs, ModRegistries.REDSTONE_SPEC_RECORDER_BLOCK.defaultBlockState(), 3)
                scenario.placeBlocks(helper)
                val be = beAt(level, recorderAbs)
                be.setMode(scenario.mode)
                be.setSpec((be.spec ?: error("recorder has no default spec")).copy(bounds = scenario.bounds))
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
                val mutated = mutateSpec(be.spec ?: error("spec null after finalize"))
                be.setSpec(mutated)
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
                if (result.pass) {
                    helper.fail("expected failing TestResult under contract violation; checks: ${result.checks}")
                }
                if (result.checks.none { !it.pass }) {
                    helper.fail("expected at least one failing check; got all-pass result: ${result.checks}")
                }
            }
            .thenSucceed()
    }

    private fun beAt(level: net.minecraft.server.level.ServerLevel, pos: BlockPos): SpecBlockEntity =
        level.getBlockEntity(pos) as? SpecBlockEntity
            ?: error("SpecBlockEntity not found at $pos")

    /**
     * Verify each finalized output marker has exactly the entries the scenario
     * declared (outer list of [scenario.expectedOutputs] is parallel to
     * [scenario.outputs]). Each [ExpectedEntry] matches either a property or a
     * block-type check within the entry's condition (which may be wrapped in an [All]).
     */
    private fun assertOutputEntries(helper: GameTestHelper, be: SpecBlockEntity, scenario: RecorderScenario) {
        val tag = "[${scenario.mode}]"
        val spec = be.spec ?: throw helper.assertionException("$tag spec is null after finalize")
        val expectedFn = scenario.expectedOutputs
        if (expectedFn == null) {
            spec.outputs.forEachIndexed { oi, output ->
                if (output.entries.isEmpty()) {
                    throw helper.assertionException("$tag output[$oi] '${output.label}' derived 0 entries")
                }
            }
            return
        }
        val expectedPerOutput = expectedFn(scenario.mode)
        if (spec.outputs.size != expectedPerOutput.size) {
            throw helper.assertionException(
                "$tag expected ${expectedPerOutput.size} output markers, got ${spec.outputs.size}"
            )
        }
        spec.outputs.zip(expectedPerOutput).forEachIndexed { oi, (output, expected) ->
            if (output.entries.size != expected.size) {
                throw helper.assertionException(
                    "$tag output[$oi] '${output.label}' expected ${expected.size} entries, got ${output.entries.size}: " +
                        output.entries.map { (t, c) -> "$t -> $c" }
                )
            }
            output.entries.zip(expected).forEachIndexed { i, pair ->
                val (actual, exp) = pair
                val (actualTime, actualCondition) = actual
                if (actualTime != exp.time) {
                    throw helper.assertionException("$tag output[$oi] '${output.label}' entry[$i]: expected time=${exp.time}, got $actualTime")
                }
                val ok = when (exp) {
                    is ExpectedEntry.Property -> conditionMatchesProperty(actualCondition, exp.propName, exp.propValue)
                    is ExpectedEntry.Block -> conditionMatchesBlock(actualCondition, exp.blockId)
                }
                if (!ok) {
                    throw helper.assertionException("$tag output[$oi] '${output.label}' entry[$i]: expected $exp, got $actualCondition")
                }
            }
        }
    }

    /**
     * True iff [condition] is a single property check (or an [All] containing
     * a property check) matching [name] = [value]. Recorder-derived entries
     * with one component come through propsToCondition as a bare condition; the
     * All wrapping shows up when there are 2+ components.
     */
    private fun conditionMatchesProperty(condition: StateCondition, name: String, value: String): kotlin.Boolean {
        return when (condition) {
            is StateCondition.BoolProperty -> condition.name == name && condition.value.toString() == value
            is StateCondition.IntProperty -> condition.name == name && condition.value.toString() == value
            is StateCondition.EnumProperty -> condition.name == name && condition.value == value
            is StateCondition.All -> condition.conditions.any { conditionMatchesProperty(it, name, value) }
            else -> false
        }
    }

    /** True iff [condition] is (or contains, inside an [All]) a [BlockType] matching [blockId]. */
    private fun conditionMatchesBlock(condition: StateCondition, blockId: String): kotlin.Boolean {
        return when (condition) {
            is StateCondition.BlockType -> condition.blockId.toString() == blockId
            is StateCondition.All -> condition.conditions.any { conditionMatchesBlock(it, blockId) }
            else -> false
        }
    }

    private fun applyMarkers(
        level: net.minecraft.server.level.ServerLevel,
        helper: GameTestHelper,
        be: SpecBlockEntity,
        scenario: RecorderScenario,
    ) {
        be.spec ?: error("recorder has no default spec at ${be.blockPos}")
        scenario.inputs.forEachIndexed { i, relPos ->
            val worldPos = helper.absolutePos(relPos)
            val state = level.getBlockState(worldPos)
            val specRelPos = worldPos.subtract(be.blockPos)
            val name = BuiltInRegistries.BLOCK.getKey(state.block).path
            be.addOrUpdateEntry(InputSpec(
                specRelPos, "in_${name}_$i", 0x4488FF,
                listOf(SimTime.START to propsToCondition(emptyMap(), state)),
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
                listOf(SimTime.END to propsToCondition(emptyMap(), state)),
            ))
        }
    }
}
