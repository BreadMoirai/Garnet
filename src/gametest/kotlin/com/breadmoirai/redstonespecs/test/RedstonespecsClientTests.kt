package com.breadmoirai.redstonespecs.test

import com.breadmoirai.redstonespecs.block.RedstoneSpecBlockEntity
import com.breadmoirai.redstonespecs.client.screen.SpecBoundsScreen
import com.breadmoirai.redstonespecs.client.screen.SpecEditorScreen
import com.breadmoirai.redstonespecs.client.screen.SpecOverviewScreen
import dev.isxander.yacl3.gui.YACLScreen
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext
import net.minecraft.core.BlockPos

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
            (mc.level?.getBlockEntity(originPos) as? RedstoneSpecBlockEntity)
                ?.lastTestResult != null
        }, 100)

        // ── Assert all checks passed ──────────────────────────────────────────
        val be = ctx.getClientBe(originPos)
            ?: throw AssertionError("RedstoneSpecBlockEntity not found at $originPos")
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
