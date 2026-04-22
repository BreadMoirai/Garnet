package com.breadmoirai.redstonespecs.test

import com.breadmoirai.redstonespecs.block.SpecOriginBlockEntity
import com.breadmoirai.redstonespecs.client.screen.SpecBoundsScreen
import com.breadmoirai.redstonespecs.client.screen.SpecEditorScreen
import com.breadmoirai.redstonespecs.client.screen.SpecOverviewScreen
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
        }
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

        // Toggle to Corners mode and screenshot
        ctx.clickButton("Offset / Size")
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

        // INIT entry is pre-populated (lever.powered=false captured by marker item).
        // Add tick-0 entry: powered=true at START_OF_TICK.
        ctx.clickButton("+ Add Entry")
        ctx.fillEditBoxByWidth(30, "0")             // Tick EditBox (width=30)
        // Phase CycleButton defaults to END_OF_TICK; one click cycles to START_OF_TICK.
        ctx.clickButton("END_OF_TICK")
        ctx.fillEditBoxByWidth(220, "powered=true")  // Props EditBox (width=220)
        ctx.clickButton("Confirm")
        ctx.clickButton("Save")
        ctx.waitTick()

        // ── Place OutputSpec on lamp via marker item ─────────────────────────
        ctx.runCommand("clear @a")
        ctx.runCommand("give @a redstonespecs:output_spec_marker 1")
        ctx.waitTick()
        ctx.rightClickBlock(lampPos)
        ctx.waitForScreen(SpecEditorScreen::class.java)

        // INIT entry pre-populated (lamp.lit=false). Add tick-0 END_OF_TICK check.
        ctx.clickButton("+ Add Entry")
        ctx.fillEditBoxByWidth(30, "0")             // Tick=0
        // Phase defaults to END_OF_TICK — no click needed
        ctx.fillEditBoxByWidth(220, "lit=true")     // Props
        ctx.clickButton("Confirm")
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
        assert(result.results.isNotEmpty()) { "Expected at least one SpecCaseResult" }
        val checks = result.results.flatMap { it.checks }
        assert(checks.isNotEmpty()) { "Expected at least one check in results" }
        val failed = checks.filter { !it.pass }
        assert(failed.isEmpty()) {
            "Failed checks: ${failed.joinToString { "${it.label}: expected=${it.expected} actual=${it.actual}" }}"
        }
    }
}
