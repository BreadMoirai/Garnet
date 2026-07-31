package com.breadmoirai.garnet.test

import com.breadmoirai.garnet.client.viewport.ViewportState
import com.breadmoirai.garnet.client.viewport.WindowViewportExt
import com.breadmoirai.garnet.harness.ClientSpec
import com.breadmoirai.garnet.mc.onServer
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import net.minecraft.core.BlockPos
import net.minecraft.world.entity.Relative
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.Vec3

/**
 * Spike Task 4 / exit criterion (b): does `Minecraft.hitResult` block-picking remain correct
 * through the shrunk viewport?
 *
 * Places a generous wall of a distinctive block in front of the player (yaw=0/pitch=0, due
 * south, high in the sky above the player's spawn column — same chunk already loaded, well
 * clear of generated terrain and any structures other specs placed), then compares
 * `Minecraft.hitResult` with [ViewportState.active] off (regression baseline) and on (a strict,
 * non-full-window shrunk content rect).
 *
 * The wall is wide/tall on purpose: this test does not depend on pixel-perfect rotation after
 * a server-side teleport (client-side rotation can carry a few degrees of residual drift from
 * earlier specs' input simulation), only on whether toggling the viewport shrink changes
 * *which* block gets picked. The real assertion is `hitOff.blockPos == hitOn.blockPos` — the
 * exact same block is chosen in both states — which is the property exit criterion (b) cares
 * about.
 *
 * MC's crosshair pick (`GameRenderer#pick` / `Minecraft#hitResult`) always raycasts from the
 * camera center using the entity's look direction — it is never a function of raw mouse pixel
 * position or window/framebuffer size, only of the player's yaw/xRot. `WindowMixin` only lies
 * about reported window/framebuffer dimensions (read by GUI-scale math and `MouseHandler`'s
 * raw-to-scaled coordinate conversion for cursor-driven UI); it does not touch camera rotation
 * or the world raycast. So this test is expected to pass in both states without any remap
 * mixin — see the Task 4 report for the empirical result.
 */
class ViewportPickingSpec : ClientSpec({

    test("(b) Minecraft.hitResult picks the same block with viewport shrink off and on") {
        closeClientScreen()
        waitClientTicks(2)

        // Freeze look input for the duration of this test: MouseHandler may still have a queued
        // accumulated-movement delta from earlier specs' input simulation (a screen close that
        // re-grabs the mouse without an "ignore first move" reset — precisely the hazard Task 4
        // Step 2's focus keybind guards against), which otherwise keeps nudging the player's
        // rotation every tick and makes a fixed-rotation aim non-reproducible. releaseMouse()
        // stops MouseHandler from turning the player at all, so the pinned rotation below holds.
        runOnClient { mc -> mc.mouseHandler.releaseMouse() }
        waitClientTicks(1)

        val target: Vec3 = onServer {
            val level = overworld()
            val player = level.players().first()
            val basePos = player.blockPosition()

            // Move well above spawn terrain (same x/z chunk column, so no new chunk load is
            // needed) and build a 5-wide x 4-tall x 2-thick wall of a distinctive block a few
            // blocks straight ahead (due south at yaw=0/pitch=0), spanning the player's eye
            // height. Generous on purpose — see class doc.
            val skyY = basePos.y + 80
            for (dx in -3..3) {
                for (dy in -1..4) {
                    for (dz in 2..5) {
                        level.setBlock(
                            BlockPos(basePos.x + dx, skyY + dy, basePos.z + dz),
                            Blocks.DIAMOND_BLOCK.defaultBlockState(), 3,
                        )
                    }
                }
            }

            val tx = basePos.x + 0.5
            val ty = skyY.toDouble()
            val tz = basePos.z + 0.5
            player.teleportTo(level, tx, ty, tz, emptySet<Relative>(), 0.0f, 0.0f, true)
            // Creative mode alone does not disable gravity (only the separate "flying" ability
            // does, which we haven't toggled) — without this the player free-falls out from
            // under the wall during the ticks we wait for network sync, causing the ray to
            // undershoot vertically. Not a picking bug; just test-setup physics.
            player.setNoGravity(true)
            Vec3(tx, ty, tz)
        }

        // Let the client sync the teleport and the new blocks, and settle a raycast.
        waitClientTicks(6)

        // Re-teleport (position + rotation + velocity, authoritatively) right before each
        // measurement. The two reads are ~10 ticks and a framebuffer resize apart; without a
        // fresh teleport the client player drifts a block in Y between them (residual velocity /
        // interpolation despite setNoGravity), so the two fixed-yaw/pitch raycasts graze
        // different block rows — a test-isolation artifact, not a picking change. teleportTo
        // encodes the exact position AND yaw=xRot=0 in one authoritative call, so both reads
        // measure from an identical camera state.
        suspend fun teleportAndSettle() {
            onServer {
                val level = overworld()
                val p = level.players().first()
                p.teleportTo(level, target.x, target.y, target.z, emptySet<Relative>(), 0.0f, 0.0f, true)
                p.deltaMovement = Vec3.ZERO
            }
            waitClientTicks(3)
        }

        // -- Baseline: viewport shrink OFF (regression check: vanilla picking unaffected) --
        ViewportState.active.shouldBeFalse()
        teleportAndSettle()
        val hitOff = onClient { mc -> mc.hitResult as? BlockHitResult }
        hitOff.shouldNotBeNull()
        val hitOffBlock = onClient { mc -> mc.level?.getBlockState(hitOff!!.blockPos)?.block }
        hitOffBlock shouldBe Blocks.DIAMOND_BLOCK

        // -- viewport shrink ON, strict sub-rect (not full-window) --
        runOnClient { mc ->
            ViewportState.active = true
            val windowExt = mc.window as Any as WindowViewportExt
            windowExt.`garnet$updateScaledFramebuffer`(true)
        }
        waitClientTicks(10)
        ViewportState.active.shouldBeTrue()
        teleportAndSettle()

        val hitOn = onClient { mc -> mc.hitResult as? BlockHitResult }
        hitOn.shouldNotBeNull()
        val hitOnBlock = onClient { mc -> mc.level?.getBlockState(hitOn!!.blockPos)?.block }
        hitOnBlock shouldBe Blocks.DIAMOND_BLOCK

        // The crux of exit criterion (b): toggling the viewport shrink must not change which
        // block the crosshair picks.
        hitOn!!.blockPos shouldBe hitOff!!.blockPos

        // Restore: toggle back off so later specs run under vanilla conditions.
        runOnClient { mc ->
            ViewportState.active = false
            val windowExt = mc.window as Any as WindowViewportExt
            windowExt.`garnet$updateScaledFramebuffer`(true)
        }
        waitClientTicks(4)
        ViewportState.active.shouldBeFalse()

        // Re-grab the mouse so later specs see normal input handling. setIgnoreFirstMove()
        // (confirmed present on this MC version's MouseHandler via javap) discards the first
        // post-grab delta, avoiding a spurious camera jump from cursor repositioning — the same
        // mechanism Task 4 Step 2 wires up to the focus keybind.
        runOnClient { mc ->
            mc.mouseHandler.setIgnoreFirstMove()
            mc.mouseHandler.grabMouse()
        }
    }
})
