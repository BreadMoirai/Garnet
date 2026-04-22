package com.breadmoirai.redstonespecs.test

import com.breadmoirai.redstonespecs.block.SpecOriginBlockEntity
import com.breadmoirai.redstonespecs.client.screen.SpecBoundsScreen
import com.breadmoirai.redstonespecs.client.screen.SpecEditorScreen
import com.breadmoirai.redstonespecs.client.screen.SpecOverviewScreen
import com.breadmoirai.redstonespecs.client.screen.StateEntryEditorScreen
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
        // Give input marker and right-click lever → SpecEditorScreen opens
        ctx.runCommand("clear @a")
        ctx.runCommand("give @a redstonespecs:input_spec_marker 1")
        ctx.waitTick()
        ctx.rightClickBlock(leverPos)
        ctx.waitForScreen(SpecEditorScreen::class.java)
        ctx.waitForButton("+ Add Entry")  // wait for workingEntries to be populated from server sync
        ctx.screenshot("spec-editor-screen")

        // Click + Add Entry → StateEntryEditorScreen opens
        ctx.clickButton("+ Add Entry")
        ctx.waitForScreen(StateEntryEditorScreen::class.java)
        ctx.waitForButton("Confirm")                  // wait for blockState to load and widgets to be built
        ctx.screenshot("state-entry-editor-screen")

        // Click Cancel → StateEntryEditorScreen closes, returns to game (no screen).
        // The SpecEditorScreen is not preserved on cancel.
        ctx.clickButton("Cancel")
        ctx.context.waitFor({ mc -> mc.screen == null }, 100)
    }

    private fun boundsScreenFlow(ctx: SpecTestContext) {
        // Open overview and screenshot it
        ctx.rightClickBlock(originPos)
        ctx.waitForScreen(SpecOverviewScreen::class.java)
        ctx.screenshot("spec-overview-screen")

        // Open bounds sub-screen and screenshot it
        ctx.clickButton("Bounds")
        ctx.waitForScreen(SpecBoundsScreen::class.java)
        ctx.screenshot("spec-bounds-screen-offset-size")

        // Toggle to Corners mode and screenshot.
        // The CycleButton has Component.empty() as label, so match by current value.
        ctx.clickNthCycleButtonByValue("Offset / Size", 0)
        ctx.waitTick()
        ctx.screenshot("spec-bounds-screen-corners")

        ctx.closeScreen()
    }

    private fun leverLampFullFlow(ctx: SpecTestContext) {
        // ── World setup ──────────────────────────────────────────────────────
        for (x in 0..3) ctx.runCommand("setblock $x 63 0 minecraft:stone")
        ctx.runCommand("setblock 0 64 0 redstonespecs:spec_origin")
        ctx.runCommand("setblock 1 64 0 minecraft:lever[face=floor,facing=north,powered=false]")
        ctx.runCommand("setblock 2 64 0 minecraft:redstone_lamp[lit=false]")
        ctx.runCommand("tp @a 0 64 -3")
        ctx.waitTicks(5)

        // ── Open overview (right-click SpecOrigin auto-creates spec) ─────────
        ctx.rightClickBlock(originPos)
        ctx.waitForScreen(SpecOverviewScreen::class.java)
        ctx.closeScreen()

        // ── Place InputSpec on lever via marker item ─────────────────────────
        ctx.runCommand("clear @a")
        ctx.runCommand("give @a redstonespecs:input_spec_marker 1")
        ctx.waitTick()
        ctx.rightClickBlock(leverPos)
        ctx.waitForScreen(SpecEditorScreen::class.java)
        ctx.waitForButton("+ Add Entry")  // wait for workingEntries to be populated from server sync

        // INIT entry is pre-populated (lever.powered=false captured by marker item).
        // Add tick-0 entry: powered=true at START_OF_TICK.
        ctx.clickButton("+ Add Entry")
        ctx.waitForScreen(StateEntryEditorScreen::class.java)
        ctx.waitForButton("Confirm")                  // wait for blockState to load and widgets to be built
        ctx.fillEditBoxByWidth(36, "0")               // Tick=0 (EditBox width=36)
        ctx.clickNthCycleButtonByValue("END_OF_TICK", 0) // Cycle Phase END_OF_TICK → START_OF_TICK
        ctx.clickNthButton(" ", 1)                    // Check "powered" checkbox (row 1, 0-indexed)
        ctx.clickNthCycleButtonByValue("false", 0)    // Cycle powered BoolRow: false→true
        ctx.clickButton("Confirm")
        ctx.waitForScreen(SpecEditorScreen::class.java)
        ctx.clickButton("Save")
        ctx.waitTick()

        // ── Place OutputSpec on lamp via marker item ─────────────────────────
        ctx.runCommand("clear @a")
        ctx.runCommand("give @a redstonespecs:output_spec_marker 1")
        ctx.waitTick()
        ctx.rightClickBlock(lampPos)
        ctx.waitForScreen(SpecEditorScreen::class.java)
        ctx.waitForButton("+ Add Entry")  // wait for workingEntries to be populated from server sync

        // INIT entry pre-populated (lamp.lit=false). Add tick-0 END_OF_TICK check.
        ctx.clickButton("+ Add Entry")
        ctx.waitForScreen(StateEntryEditorScreen::class.java)
        ctx.waitForButton("Confirm")                  // wait for blockState to load and widgets to be built
        ctx.fillEditBoxByWidth(36, "0")               // Tick=0
        // Phase stays END_OF_TICK (default)
        ctx.clickNthButton(" ", 1)                    // Check "lit" checkbox (row 1)
        ctx.clickNthCycleButtonByValue("false", 0)    // Cycle lit BoolRow: false→true
        ctx.clickButton("Confirm")
        ctx.waitForScreen(SpecEditorScreen::class.java)
        ctx.clickButton("Save")
        ctx.waitTick()

        // ── Open overview and run spec ────────────────────────────────────────
        ctx.runCommand("clear @a")
        ctx.waitTick()
        ctx.rightClickBlock(originPos)
        ctx.waitForScreen(SpecOverviewScreen::class.java)
        ctx.clickButton("Run")

        // ── Wait for test result (synced to client BE via getUpdatePacket) ────
        ctx.context.waitFor({ mc ->
            (mc.level?.getBlockEntity(originPos) as? SpecOriginBlockEntity)
                ?.lastTestResult != null
        }, 100)

        // ── Assert all checks passed ──────────────────────────────────────────
        val be = ctx.getClientBe(originPos)
            ?: throw AssertionError("SpecOriginBlockEntity not found at $originPos")
        val result = be.lastTestResult
            ?: throw AssertionError("lastTestResult is null after waitFor succeeded")
        check(result.results.isNotEmpty()) { "Expected at least one SpecCaseResult" }
        val checks = result.results.flatMap { it.checks }
        check(checks.isNotEmpty()) { "Expected at least one check in results" }
        val failed = checks.filter { !it.pass }
        check(failed.isEmpty()) {
            "Failed checks: ${failed.joinToString { "${it.label}: expected=${it.expected} actual=${it.actual}" }}"
        }
    }
}
