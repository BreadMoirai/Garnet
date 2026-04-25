package com.breadmoirai.redstonespecs.test

import com.breadmoirai.redstonespecs.ModRegistries
import com.breadmoirai.redstonespecs.block.RedstoneSpecRunnerBlock
import com.breadmoirai.redstonespecs.block.SpecBlockEntity
import com.breadmoirai.redstonespecs.client.screen.SpecBoundsScreen
import com.breadmoirai.redstonespecs.client.screen.SpecEditorScreen
import com.breadmoirai.redstonespecs.client.screen.SpecOverviewScreen
import com.breadmoirai.redstonespecs.data.InputSpec
import com.breadmoirai.redstonespecs.data.OutputSpec
import com.breadmoirai.redstonespecs.data.RedstoneSpec
import com.breadmoirai.redstonespecs.data.Phase
import com.breadmoirai.redstonespecs.data.SimTime
import com.breadmoirai.redstonespecs.data.StateCondition
import dev.isxander.yacl3.gui.YACLScreen
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext
import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.Blocks
import org.apache.commons.lang3.function.FailableConsumer

@Suppress("UnstableApiUsage")
class RedstonespecsClientTests : FabricClientGameTest {

    private val originPos = BlockPos(0, 64, 0)
    private val leverPos  = BlockPos(1, 64, 0)
    private val lampPos   = BlockPos(2, 64, 0)

    override fun runTest(context: ClientGameTestContext) {
        SpecTestContext.createWorld(context).use { world ->
            val ctx = SpecTestContext(context, world)
            leverLampFullFlow(ctx)
            boundsScreenFlow(ctx)
            specEditorScreenFlow(ctx)
            editorTransformsToRunnerOnSave(ctx)
            discardClearsEverythingExceptIdBoundsAndMarkers(ctx)
            markerToolRejectsRunnerBlock(ctx)
        }
    }

    private fun specEditorScreenFlow(ctx: SpecTestContext) {
        // Give input marker and right-click lever → thin SpecEditorScreen → YACLScreen
        ctx.runCommand("clear @a")
        ctx.runCommand("give @a redstonespecs:input_spec_marker 1")
        ctx.waitTick()
        ctx.rightClickBlock(leverPos)
        // SpecEditorScreen (loader) transitions to YACLScreen once BE data arrives
        ctx.waitForScreen(YACLScreen::class.java)
        ctx.screenshot("spec-editor-screen")

        // Click "+ Add Entry" ButtonOption → opens entry editor YACLScreen
        ctx.clickYaclButton("+ Add Entry")
        ctx.waitForScreen(YACLScreen::class.java)
        ctx.screenshot("entry-editor-screen")

        // Click Cancel (YACL's built-in cancel button, direct screen widget)
        ctx.clickButton("Cancel")
        ctx.context.waitFor({ mc -> mc.screen == null }, 100)
    }

    private fun boundsScreenFlow(ctx: SpecTestContext) {
        ctx.rightClickBlock(originPos)
        ctx.waitForScreen(SpecOverviewScreen::class.java)
        ctx.screenshot("spec-overview-screen")

        ctx.clickButton("Bounds")
        ctx.waitForScreen(SpecBoundsScreen::class.java)
        ctx.screenshot("spec-bounds-screen-offset-size")

        ctx.clickNthCycleButtonByValue("Offset / Size", 0)
        ctx.waitTick()
        ctx.screenshot("spec-bounds-screen-corners")

        ctx.closeScreen()
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
        var inputEntriesCollapsedToInit = false
        var outputEntriesCleared = false
        var lifespanReset = false

        val originalBounds = RedstoneSpec.DEFAULT_BOUNDS

        ctx.world.getServer().runOnServer(object : FailableConsumer<net.minecraft.server.MinecraftServer, RuntimeException> {
            override fun accept(server: net.minecraft.server.MinecraftServer) {
                val level = server.overworld()
                level.setBlock(bePos, ModRegistries.REDSTONE_SPEC_EDITOR_BLOCK.defaultBlockState(), 3)
                val be = level.getBlockEntity(bePos) as? SpecBlockEntity
                    ?: error("SpecBlockEntity not found at $bePos")

                // Build a spec with a custom lifespan, an InputSpec with a non-INIT entry appended,
                // and an OutputSpec with a non-INIT entry. InputSpec must always contain exactly one
                // INIT entry (invariant), so we include the required INIT plus an extra entry.
                val extraTime = SimTime(0, Phase.END_OF_TICK)
                val original = RedstoneSpec.new("keep_me").copy(
                    bounds = originalBounds,
                    lifespan = customLifespan,
                    inputs = listOf(
                        InputSpec(
                            inputRelPos, "in_a", 0,
                            listOf(
                                SimTime.INIT to initCondition,
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
                // discardForRerecord keeps only the INIT entry on each InputSpec.
                inputEntriesCollapsedToInit = s.inputs[0].entries.size == 1 &&
                    s.inputs[0].entries[0].first == SimTime.INIT
                outputEntriesCleared = s.outputs.size == 1 && s.outputs[0].entries.isEmpty()
                lifespanReset = s.lifespan != customLifespan
            }
        })
        ctx.waitTick()

        check(idOk) { "discardClearsEverythingExceptIdBoundsAndMarkers: id should be preserved" }
        check(boundsOk) { "discardClearsEverythingExceptIdBoundsAndMarkers: bounds should be preserved" }
        check(inputPosOk) { "discardClearsEverythingExceptIdBoundsAndMarkers: input marker position should be preserved" }
        check(inputEntriesCollapsedToInit) { "discardClearsEverythingExceptIdBoundsAndMarkers: input entries should collapse to INIT only" }
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

    private fun leverLampFullFlow(ctx: SpecTestContext) {
        // ── World setup ──────────────────────────────────────────────────────
        for (x in 0..3) ctx.runCommand("setblock $x 63 0 minecraft:stone")
        ctx.runCommand("setblock 0 64 0 redstonespecs:redstone_spec")
        ctx.runCommand("setblock 1 64 0 minecraft:lever[face=floor,facing=north,powered=false]")
        ctx.runCommand("setblock 2 64 0 minecraft:redstone_lamp[lit=false]")
        ctx.runCommand("tp @a 0 64 -3")
        ctx.waitTicks(5)

        // ── Open overview (right-click SpecOrigin auto-creates spec) ─────────
        ctx.rightClickBlock(originPos)
        ctx.waitForScreen(SpecOverviewScreen::class.java)
        ctx.closeScreen()

        // ── Place InputSpec on lever ─────────────────────────────────────────
        ctx.runCommand("clear @a")
        ctx.runCommand("give @a redstonespecs:input_spec_marker 1")
        ctx.waitTick()
        ctx.rightClickBlock(leverPos)
        ctx.waitForScreen(YACLScreen::class.java)

        // Add tick-0 START_OF_TICK entry: powered=true
        ctx.clickYaclButton("+ Add Entry")
        ctx.waitForScreen(YACLScreen::class.java)
        ctx.setYaclOption("Tick", 0)
        ctx.setYaclOption("Phase", "START_OF_TICK")
        ctx.setYaclOption("powered", "true")
        ctx.clickButton("Save Changes")
        ctx.waitForScreen(YACLScreen::class.java)

        // Save the InputSpec
        ctx.clickButton("Save Changes")
        ctx.waitTick()

        // ── Place OutputSpec on lamp ─────────────────────────────────────────
        ctx.runCommand("clear @a")
        ctx.runCommand("give @a redstonespecs:output_spec_marker 1")
        ctx.waitTick()
        ctx.rightClickBlock(lampPos)
        ctx.waitForScreen(YACLScreen::class.java)

        // Add tick-0 END_OF_TICK check: lit=true
        ctx.clickYaclButton("+ Add Entry")
        ctx.waitForScreen(YACLScreen::class.java)
        ctx.setYaclOption("Tick", 0)
        // Phase stays END_OF_TICK (default)
        ctx.setYaclOption("lit", "true")
        ctx.clickButton("Save Changes")
        ctx.waitForScreen(YACLScreen::class.java)

        // Save the OutputSpec
        ctx.clickButton("Save Changes")
        ctx.waitTick()

        // ── Open overview and run spec ────────────────────────────────────────
        ctx.runCommand("clear @a")
        ctx.waitTick()
        ctx.rightClickBlock(originPos)
        ctx.waitForScreen(SpecOverviewScreen::class.java)
        ctx.clickButton("Run")

        // ── Wait for test result ──────────────────────────────────────────────
        ctx.context.waitFor({ mc ->
            (mc.level?.getBlockEntity(originPos) as? SpecBlockEntity)
                ?.lastTestResult != null
        }, 100)

        // ── Assert all checks passed ──────────────────────────────────────────
        val be = ctx.getClientBe(originPos)
            ?: throw AssertionError("SpecBlockEntity not found at $originPos")
        val result = be.lastTestResult
            ?: throw AssertionError("lastTestResult is null after waitFor succeeded")
        val checks = result.checks
        check(checks.isNotEmpty()) { "Expected at least one check in results" }
        val failed = checks.filter { !it.pass }
        check(failed.isEmpty()) {
            "Failed checks: ${failed.joinToString { "${it.label}: expected=${it.expected} actual=${it.actual}" }}"
        }
    }
}
