package com.breadmoirai.redstonespecs.test

import com.breadmoirai.redstonespecs.ModRegistries
import com.breadmoirai.redstonespecs.block.RedstoneSpecEditorBlock
import com.breadmoirai.redstonespecs.block.RedstoneSpecRunnerBlock
import com.breadmoirai.redstonespecs.block.SpecBlockEntity
import com.breadmoirai.redstonespecs.client.screen.RecorderSetupScreen
import com.breadmoirai.redstonespecs.client.screen.SpecOverviewScreen
import com.breadmoirai.redstonespecs.data.InputSpec
import com.breadmoirai.redstonespecs.data.OutputSpec
import com.breadmoirai.redstonespecs.data.RedstoneSpec
import com.breadmoirai.redstonespecs.data.Phase
import com.breadmoirai.redstonespecs.data.SimTime
import com.breadmoirai.redstonespecs.data.SpecMode
import com.breadmoirai.redstonespecs.data.StateCondition
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext
import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.Blocks
import org.apache.commons.lang3.function.FailableConsumer

@Suppress("UnstableApiUsage")
class RedstonespecsClientTests : FabricClientGameTest {

    override fun runTest(context: ClientGameTestContext) {
        SpecTestContext.createWorld(context).use { world ->
            val ctx = SpecTestContext(context, world)
            // One pass per SpecMode, on a fresh patch of world each time so the
            // earlier runner block doesn't interfere with the next iteration.
            recorderToEditorToRunnerFlow(ctx, SpecMode.SIMPLE,       originX = 30)
            recorderToEditorToRunnerFlow(ctx, SpecMode.TICK_AWARE,   originX = 50)
            recorderToEditorToRunnerFlow(ctx, SpecMode.UPDATE_AWARE, originX = 70)
            editorTransformsToRunnerOnSave(ctx)
            discardClearsEverythingExceptIdBoundsAndMarkers(ctx)
            markerToolRejectsRunnerBlock(ctx)
        }
    }

    // ── Test: Editor → Runner via transformTo ────────────────────────────────
    private fun editorTransformsToRunnerOnSave(ctx: SpecTestContext) {
        val bePos = BlockPos(10, 64, 10)

        // Place editor block and set a spec on the server thread.
        var specSurvived = false
        var blockIsRunner = false
        ctx.world.getServer().runOnServer(object : FailableConsumer<net.minecraft.server.MinecraftServer, RuntimeException> {
            override fun accept(server: net.minecraft.server.MinecraftServer) {
                val level = server.overworld()
                level.setBlock(bePos, ModRegistries.REDSTONE_SPEC_EDITOR_BLOCK.defaultBlockState(), 3)
                val be = level.getBlockEntity(bePos) as? SpecBlockEntity
                    ?: error("SpecBlockEntity not found at $bePos after placing editor block")
                be.setSpec(RedstoneSpec.new("transform_test"))
                be.transformTo(ModRegistries.REDSTONE_SPEC_RUNNER_BLOCK)

                // Read back immediately on the same server tick.
                blockIsRunner = level.getBlockState(bePos).block is RedstoneSpecRunnerBlock
                val newBe = level.getBlockEntity(bePos) as? SpecBlockEntity
                specSurvived = newBe?.spec?.id == "transform_test"
            }
        })
        ctx.waitTick()

        check(blockIsRunner) { "editorTransformsToRunnerOnSave: block should be Runner after transformTo, but was not" }
        check(specSurvived) { "editorTransformsToRunnerOnSave: spec id should survive transform to Runner" }
    }

    // ── Test: discardForRerecord preserves only id/bounds/marker positions ───
    private fun discardClearsEverythingExceptIdBoundsAndMarkers(ctx: SpecTestContext) {
        val bePos = BlockPos(20, 64, 10)
        val inputRelPos = BlockPos(1, 0, 0)
        val outputRelPos = BlockPos(2, 0, 0)
        val customLifespan = 99
        val initCondition = StateCondition.BoolProperty("powered", false)

        var idOk = false
        var boundsOk = false
        var inputPosOk = false
        var inputEntriesCollapsedToStart = false
        var outputEntriesCleared = false
        var lifespanReset = false

        val originalBounds = RedstoneSpec.DEFAULT_BOUNDS

        ctx.world.getServer().runOnServer(object : FailableConsumer<net.minecraft.server.MinecraftServer, RuntimeException> {
            override fun accept(server: net.minecraft.server.MinecraftServer) {
                val level = server.overworld()
                level.setBlock(bePos, ModRegistries.REDSTONE_SPEC_EDITOR_BLOCK.defaultBlockState(), 3)
                val be = level.getBlockEntity(bePos) as? SpecBlockEntity
                    ?: error("SpecBlockEntity not found at $bePos")

                // Build a spec with a custom lifespan, an InputSpec with a non-START entry appended,
                // and an OutputSpec with a non-START entry. InputSpec must always contain exactly one
                // START entry (invariant), so we include the required START plus an extra entry.
                val extraTime = SimTime(0, Phase.END_OF_TICK)
                val original = RedstoneSpec.new("keep_me").copy(
                    bounds = originalBounds,
                    lifespan = customLifespan,
                    inputs = listOf(
                        InputSpec(
                            inputRelPos, "in_a", 0,
                            listOf(
                                SimTime.START to initCondition,
                                extraTime to StateCondition.BoolProperty("powered", true),
                            ),
                        ),
                    ),
                    outputs = listOf(
                        OutputSpec(
                            outputRelPos, "out_a", 0,
                            listOf(extraTime to StateCondition.BoolProperty("lit", true)),
                        ),
                    ),
                )
                be.setSpec(original)
                be.discardForRerecord()
                be.transformTo(ModRegistries.REDSTONE_SPEC_RECORDER_BLOCK)

                val newBe = level.getBlockEntity(bePos) as? SpecBlockEntity
                    ?: error("SpecBlockEntity not found at $bePos after transform")
                val s = newBe.spec ?: error("spec is null after discardForRerecord")

                idOk = s.id == "keep_me"
                boundsOk = s.bounds == originalBounds
                inputPosOk = s.inputs.size == 1 && s.inputs[0].pos == inputRelPos
                // discardForRerecord keeps only the START entry on each InputSpec.
                inputEntriesCollapsedToStart = s.inputs[0].entries.size == 1 &&
                    s.inputs[0].entries[0].first == SimTime.START
                outputEntriesCleared = s.outputs.size == 1 && s.outputs[0].entries.isEmpty()
                lifespanReset = s.lifespan != customLifespan
            }
        })
        ctx.waitTick()

        check(idOk) { "discardClearsEverythingExceptIdBoundsAndMarkers: id should be preserved" }
        check(boundsOk) { "discardClearsEverythingExceptIdBoundsAndMarkers: bounds should be preserved" }
        check(inputPosOk) { "discardClearsEverythingExceptIdBoundsAndMarkers: input marker position should be preserved" }
        check(inputEntriesCollapsedToStart) { "discardClearsEverythingExceptIdBoundsAndMarkers: input entries should collapse to START only" }
        check(outputEntriesCleared) { "discardClearsEverythingExceptIdBoundsAndMarkers: output entries should be cleared" }
        check(lifespanReset) { "discardClearsEverythingExceptIdBoundsAndMarkers: lifespan should be reset to default" }
    }

    // ── Test: marker tool rejects Runner block ────────────────────────────────
    private fun markerToolRejectsRunnerBlock(ctx: SpecTestContext) {
        // DEFAULT_BOUNDS = BoundingBox(1, 0, 1, 5, 4, 5) — offsets relative to block pos.
        // Place runner block at (10, 64, 20). A block at (12, 64, 22) is well inside the bounds
        // (offset (2, 0, 2) is in [1..5, 0..4, 1..5]).
        val runnerPos = BlockPos(10, 64, 20)
        val targetPos = BlockPos(12, 64, 22)   // relative offset (2, 0, 2) — inside DEFAULT_BOUNDS

        // Place stone floor and target block on the server.
        ctx.world.getServer().runOnServer(object : FailableConsumer<net.minecraft.server.MinecraftServer, RuntimeException> {
            override fun accept(server: net.minecraft.server.MinecraftServer) {
                val level = server.overworld()
                level.setBlock(runnerPos.below(), Blocks.STONE.defaultBlockState(), 3)
                level.setBlock(runnerPos, ModRegistries.REDSTONE_SPEC_RUNNER_BLOCK.defaultBlockState(), 3)
                level.setBlock(targetPos.below(), Blocks.STONE.defaultBlockState(), 3)
                level.setBlock(targetPos, Blocks.LEVER.defaultBlockState(), 3)
                val be = level.getBlockEntity(runnerPos) as? SpecBlockEntity
                    ?: error("SpecBlockEntity not found at $runnerPos")
                be.setSpec(RedstoneSpec.new("runner_test"))
            }
        })
        ctx.waitTick()

        // Give player the input_spec_marker and teleport them near the target.
        ctx.runCommand("clear @a")
        ctx.runCommand("give @a redstonespecs:input_spec_marker 1")
        ctx.runCommand("tp @a ${targetPos.x} ${targetPos.y} ${targetPos.z - 3}")
        ctx.waitTicks(3)

        // Right-click the target block (marker in hand). SpecMarkerTool.useOn checks for Runner
        // block via SpecBlockEntity.findFor — if the owning BE is a Runner, it returns PASS.
        ctx.rightClickBlock(targetPos)
        ctx.waitTick()

        // Verify no input entry was added to the Runner spec.
        var inputsEmpty = false
        ctx.world.getServer().runOnServer(object : FailableConsumer<net.minecraft.server.MinecraftServer, RuntimeException> {
            override fun accept(server: net.minecraft.server.MinecraftServer) {
                val level = server.overworld()
                val be = level.getBlockEntity(runnerPos) as? SpecBlockEntity
                    ?: error("SpecBlockEntity not found at $runnerPos")
                inputsEmpty = be.spec?.inputs.isNullOrEmpty()
            }
        })
        ctx.waitTick()

        check(inputsEmpty) { "markerToolRejectsRunnerBlock: Runner block must reject marker placement — inputs should remain empty" }
    }

    // ── Test: Recorder → Editor → Runner via UI (Record/Stop, Save, Run) ─────
    /**
     * Drives the full recorder→editor→runner UI flow for a lever-on-lamp circuit
     * at the given [SpecMode]. [originX] is the x-coordinate of the recorder block,
     * letting the caller place each mode's run on a fresh patch of world.
     */
    private fun recorderToEditorToRunnerFlow(ctx: SpecTestContext, mode: SpecMode, originX: Int) {
        val recorderPos = BlockPos(originX, 64, 0)
        val recLampPos  = BlockPos(originX + 2, 64, 1)   // relative (2, 0, 1) — in DEFAULT_BOUNDS
        val recLeverPos = BlockPos(originX + 2, 65, 1)   // relative (2, 1, 1) — on top of lamp

        // ── World setup ──────────────────────────────────────────────────────
        // Stone floor under the recorder, lamp, and the player's standing spot so the
        // player doesn't fall (falling causes the recorder to silently auto-stop between
        // re-opens of RecorderSetupScreen).
        ctx.runCommand("fill ${recorderPos.x - 1} ${recorderPos.y - 1} ${recorderPos.z - 4} ${recorderPos.x + 3} ${recorderPos.y - 1} ${recorderPos.z + 2} minecraft:stone")
        ctx.runCommand("setblock ${recorderPos.x} ${recorderPos.y} ${recorderPos.z} redstonespecs:redstone_spec_recorder")
        ctx.runCommand("setblock ${recLampPos.x} ${recLampPos.y} ${recLampPos.z} minecraft:redstone_lamp[lit=false]")
        ctx.runCommand("setblock ${recLeverPos.x} ${recLeverPos.y} ${recLeverPos.z} minecraft:lever[face=floor,facing=north,powered=false]")
        // Stand the player a few blocks south of the recorder so right-click hits land cleanly.
        ctx.runCommand("tp @a ${recorderPos.x} ${recorderPos.y} ${recorderPos.z - 3}")
        ctx.waitTicks(5)

        // ── Set the recorder's spec mode (server-side, before markers/recording) ─
        ctx.world.getServer().runOnServer(object : FailableConsumer<net.minecraft.server.MinecraftServer, RuntimeException> {
            override fun accept(server: net.minecraft.server.MinecraftServer) {
                val be = server.overworld().getBlockEntity(recorderPos) as? SpecBlockEntity
                    ?: error("SpecBlockEntity not found at $recorderPos")
                be.setMode(mode)
            }
        })
        ctx.waitTick()

        // ── Apply input marker on the lever (no UI in recorder mode) ─────────
        ctx.runCommand("clear @a")
        ctx.runCommand("give @a redstonespecs:input_spec_marker 1")
        ctx.waitTick()
        ctx.rightClickBlock(recLeverPos)
        ctx.waitTick()

        // ── Apply output marker on the lamp (no UI in recorder mode) ─────────
        ctx.runCommand("clear @a")
        ctx.runCommand("give @a redstonespecs:output_spec_marker 1")
        ctx.waitTick()
        ctx.rightClickBlock(recLampPos)
        ctx.waitTick()

        // ── Open recorder UI and click Record ────────────────────────────────
        ctx.runCommand("clear @a")
        ctx.waitTick()
        ctx.rightClickBlock(recorderPos)
        ctx.waitForScreen(RecorderSetupScreen::class.java)
        ctx.clickButton("Record")
        // Record button calls onClose(); wait for screen to clear.
        ctx.context.waitFor({ mc -> mc.screen == null }, 100)

        // ── Drive state changes during recording ─────────────────────────────
        // Toggle the lever via a real right-click so MC's lever logic fires the
        // proper neighbor-update chain. /setblock would only swap the powered
        // property and would not propagate redstone through e.g. a smooth-stone
        // hard-power chain.
        ctx.waitTicks(2)
        ctx.rightClickBlock(recLeverPos)
        ctx.waitTicks(4)

        // ── Open recorder UI again and click Stop ────────────────────────────
        ctx.rightClickBlock(recorderPos)
        ctx.waitForScreen(RecorderSetupScreen::class.java)
        ctx.clickButton("Stop")
        ctx.context.waitFor({ mc -> mc.screen == null }, 100)

        // Server transforms recorder → editor on stop+finalize success.
        ctx.context.waitFor({ mc ->
            mc.level?.getBlockState(recorderPos)?.block is RedstoneSpecEditorBlock
        }, 100)

        // ── Strict assertion on the derived output entry ─────────────────────
        // The lever and lamp transition on the same tick (direct attachment),
        // so all three modes derive the single output entry at
        // SimTime(0, END_OF_TICK) with condition lit=true.
        val finalizedSpec = ctx.getClientBe(recorderPos)?.spec
            ?: throw AssertionError("[$mode] spec is null after editor transition")
        val output = finalizedSpec.outputs.singleOrNull()
            ?: throw AssertionError("[$mode] expected exactly one output marker, got ${finalizedSpec.outputs.size}")
        check(output.entries.size == 1) {
            "[$mode] expected 1 output entry, got ${output.entries.size}: ${output.entries.map { it.first }}"
        }
        val (entryTime, entryCondition) = output.entries[0]
        check(entryTime == SimTime(0, Phase.END_OF_TICK)) {
            "[$mode] output entry time: expected SimTime(0, END_OF_TICK), got $entryTime"
        }
        check(conditionMatchesProperty(entryCondition, "lit", "true")) {
            "[$mode] output entry condition: expected lit=true, got $entryCondition"
        }

        // ── Open editor's overview screen and click Save → transforms to Runner ──
        ctx.rightClickBlock(recorderPos)
        ctx.waitForScreen(SpecOverviewScreen::class.java)
        ctx.clickButton("Save")
        ctx.context.waitFor({ mc -> mc.screen == null }, 100)
        ctx.context.waitFor({ mc ->
            mc.level?.getBlockState(recorderPos)?.block is RedstoneSpecRunnerBlock
        }, 100)

        // ── Open runner's overview and click Run ─────────────────────────────
        ctx.rightClickBlock(recorderPos)
        ctx.waitForScreen(SpecOverviewScreen::class.java)
        ctx.clickButton("Run")

        // ── Wait for test result and assert ──────────────────────────────────
        ctx.context.waitFor({ mc ->
            (mc.level?.getBlockEntity(recorderPos) as? SpecBlockEntity)
                ?.lastTestResult != null
        }, 100)

        val be = ctx.getClientBe(recorderPos)
            ?: throw AssertionError("[$mode] SpecBlockEntity not found at $recorderPos")
        val result = be.lastTestResult
            ?: throw AssertionError("[$mode] lastTestResult is null after waitFor succeeded")
        val checks = result.checks
        check(checks.isNotEmpty()) { "[$mode] recorderToEditorToRunnerFlow: expected at least one check in results" }
        val failed = checks.filter { !it.pass }
        check(failed.isEmpty()) {
            "[$mode] recorderToEditorToRunnerFlow: failed checks: ${failed.joinToString { "${it.label}: expected=${it.expected} actual=${it.actual}" }}"
        }
    }

    /** Shared with [RedstonespecsGameTests.conditionMatchesProperty] — kept duplicated to
     *  avoid pulling in cross-test deps. True iff [condition] (or any leaf of an
     *  [StateCondition.All] wrapper) carries the named property with the given string value. */
    private fun conditionMatchesProperty(condition: StateCondition, name: String, value: String): Boolean = when (condition) {
        is StateCondition.BoolProperty -> condition.name == name && condition.value.toString() == value
        is StateCondition.IntProperty -> condition.name == name && condition.value.toString() == value
        is StateCondition.EnumProperty -> condition.name == name && condition.value == value
        is StateCondition.All -> condition.conditions.any { conditionMatchesProperty(it, name, value) }
        else -> false
    }
}
