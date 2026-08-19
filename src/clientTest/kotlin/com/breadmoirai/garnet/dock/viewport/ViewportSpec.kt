package com.breadmoirai.garnet.dock.viewport

import com.breadmoirai.garnet.core.async.onServer
import com.breadmoirai.garnet.harness.ClientSpec
import com.breadmoirai.garnet.dock.shell.DockRegion
import com.breadmoirai.garnet.dock.shell.DockState
import com.breadmoirai.garnet.dock.shell.Panel
import com.breadmoirai.garnet.test.closeClientScreen
import com.breadmoirai.garnet.test.onClient
import com.breadmoirai.garnet.test.runOnClient
import com.breadmoirai.garnet.test.waitClientTicks
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import net.minecraft.client.MouseHandler
import net.minecraft.core.BlockPos
import net.minecraft.world.entity.Relative
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.Vec3
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path

/**
 * Consolidated viewport-shrink story (spike Tasks 3 & 4): compositing, block-picking, and cursor
 * mapping all under one arrange (shrink enabled via a visible LEFT dock region).
 *
 * **Compositing** — toggling [ViewportState.active] on and driving the [WindowViewportExt]
 * recompute shrinks the game render target; a few frames later
 * [MinecraftPresentMixin][com.breadmoirai.garnet.mixin.client.MinecraftPresentMixin] presents a
 * composite of the live world in a centered sub-rect with solid reserved edges. The normal
 * screenshot path captures the (upstream) main render target, so it only shows the shrunk world
 * full-frame — it can NOT show our composite. The composite proof is captured separately by
 * asking the present mixin to dump the composite target itself to a stable path; that PNG is the
 * actual Task-3 proof and is inspectable under `versions/26.2/run/screenshots/`.
 *
 * **Picking** — does `Minecraft.hitResult` block-picking remain correct through the shrunk
 * viewport? A generous wall of a distinctive block is placed in front of the player (yaw=0/
 * pitch=0, due south, high in the sky above the player's spawn column — same chunk already
 * loaded, well clear of generated terrain and any structures other specs placed), then
 * `Minecraft.hitResult` is compared with the shrink off (regression baseline) and on (a strict,
 * non-full-window shrunk content rect).
 *
 * The wall is wide/tall on purpose: this does not depend on pixel-perfect rotation after a
 * server-side teleport (client-side rotation can carry a few degrees of residual drift from
 * earlier specs' input simulation), only on whether toggling the viewport shrink changes *which*
 * block gets picked. The real assertion is `hitOff.blockPos == hitOn.blockPos` — the exact same
 * block is chosen in both states — which is the property exit criterion (b) cares about.
 *
 * MC's crosshair pick (`GameRenderer#pick` / `Minecraft#hitResult`) always raycasts from the
 * camera center using the entity's look direction — it is never a function of raw mouse pixel
 * position or window/framebuffer size, only of the player's yaw/xRot. `WindowMixin` only lies
 * about reported window/framebuffer dimensions (read by GUI-scale math and `MouseHandler`'s
 * raw-to-scaled coordinate conversion for cursor-driven UI); it does not touch camera rotation or
 * the world raycast. So picking is expected to pass in both states without any remap mixin.
 *
 * **Cursor mapping** — regression for the cursor-mapping bug: with the viewport shrink active (a
 * dock region reserving an edge strip), a vanilla `Screen` (e.g. the pause / Game Menu) renders
 * into the shrunk content sub-rect, which is offset by the reserved insets `(frameX, frameY)`.
 * But `MouseHandler.getScaledXPos/getScaledYPos` map the raw cursor to GUI coords as
 * `raw * guiScaledWidth / screenWidth` — using the shrunk `screenWidth` but **never subtracting
 * the content-rect origin offset**. The result: reported GUI coordinates are shifted by
 * `frameX/scale` (and `frameY/scale`), so a button's hitbox sits at its *un-offset* on-screen
 * position and the user has to hover to the left of where the button is actually drawn to hit it.
 *
 * These are the exact instance-overloads MC uses for absolute screen coordinates
 * (`mouseMoved`/`mouseClicked`/`mouseScrolled`); the static delta overload used for drag/camera-
 * turn is intentionally left alone. The fix subtracts the content offset from the stored raw
 * cursor before scaling.
 *
 * A single visible LEFT region of size 300 serves both the cursor assertion (needs a non-zero
 * `frameX`) and the composite proof (needs a reserved edge) — one arrangement, two readers.
 */
class ViewportSpec : ClientSpec({
    val logger = LoggerFactory.getLogger("Garnet")

    test("the shrunk viewport composites, picks, and maps the cursor correctly") {
        // ---- Phase 1: Baseline (effect off) ----
        closeClientScreen()
        waitClientTicks(2)
        ViewportState.active.shouldBeFalse()

        // Freeze look input for the duration of this test: MouseHandler may still have a queued
        // accumulated-movement delta from earlier specs' input simulation (a screen close that
        // re-grabs the mouse without an "ignore first move" reset), which otherwise keeps nudging
        // the player's rotation every tick and makes a fixed-rotation aim non-reproducible.
        // releaseMouse() stops MouseHandler from turning the player at all, so the pinned
        // rotation below holds.
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

        teleportAndSettle()
        val hitOff = onClient { mc -> mc.hitResult as? BlockHitResult }
        hitOff.shouldNotBeNull()
        val hitOffBlock = onClient { mc -> mc.level?.getBlockState(hitOff.blockPos)?.block }
        hitOffBlock shouldBe Blocks.DIAMOND_BLOCK

        // ---- Phase 2: Enable ----
        // A visible LEFT region reserves a left inset, so the shrunk content rect is pushed right
        // by frameX. (top is always 0 in the current dock model, so frameY stays 0.) This one
        // arrangement backs both the cursor-mapping assertion (needs frameX > 0) and the
        // composite proof (needs a reserved edge).
        runOnClient { mc ->
            DockState.reset()
            DockState.panels += Panel(
                "garnet.test.viewport", "ViewportProbe", DockRegion.LEFT, AllIconsKeys.General.Information,
            ) {}
            DockState.showPanel("garnet.test.viewport")
            DockState.setSize(DockRegion.LEFT, 300)
            ViewportState.active = true
            (mc.window as Any as WindowViewportExt).`garnet$updateScaledFramebuffer`(true)
        }
        waitClientTicks(10)
        ViewportState.active.shouldBeTrue()

        // ---- Phase 3: Assert (cheapest first, so a failure surfaces before the slow capture) ----

        // -- Cursor mapping --
        val xposField = MouseHandler::class.java.getDeclaredField("xpos").apply { isAccessible = true }
        val yposField = MouseHandler::class.java.getDeclaredField("ypos").apply { isAccessible = true }

        runOnClient { mc ->
            val window = mc.window
            val rect = ViewportState.contentRect(ViewportState.realWidth, ViewportState.realHeight)
            val frameX = rect.frameX
            val frameY = rect.frameY

            // Guard: the scenario is only meaningful if the content is actually offset.
            frameX shouldBeGreaterThan 0

            // Place the raw OS cursor at a known point *inside* the content sub-rect.
            val contentX = 137.0
            val contentY = 91.0
            val rawX = frameX + contentX
            val rawY = frameY + contentY
            xposField.setDouble(mc.mouseHandler, rawX)
            yposField.setDouble(mc.mouseHandler, rawY)

            // The GUI coordinate MC hands to the focused screen must be the content-local
            // position scaled — i.e. computed from (raw - frameOffset), not the raw cursor.
            val expectedX = MouseHandler.getScaledXPos(window, rawX - frameX)
            val expectedY = MouseHandler.getScaledYPos(window, rawY - frameY)

            mc.mouseHandler.getScaledXPos(window) shouldBe expectedX
            mc.mouseHandler.getScaledYPos(window) shouldBe expectedY
        }

        // -- Picking: toggling the viewport shrink must not change which block the crosshair
        // picks. --
        teleportAndSettle()
        val hitOn = onClient { mc -> mc.hitResult as? BlockHitResult }
        hitOn.shouldNotBeNull()
        val hitOnBlock = onClient { mc -> mc.level?.getBlockState(hitOn.blockPos)?.block }
        hitOnBlock shouldBe Blocks.DIAMOND_BLOCK
        hitOn.blockPos shouldBe hitOff.blockPos

        // -- Composite proof: the actual Task-3 deliverable. The normal screenshot path captures
        // the (upstream) main render target, so it only shows the shrunk world full-frame — it
        // can NOT show our composite. Capture that separately by asking the present mixin to dump
        // the composite target itself to a stable path. The client's working directory is
        // versions/26.2/run/, so this lands beside the other screenshots in
        // versions/26.2/run/screenshots/. --
        val compositeProof = Path.of("screenshots", "viewport_shrink_composite_proof.png")
            .toAbsolutePath()
        Files.deleteIfExists(compositeProof)
        runOnClient { ViewportState.compositeCaptureRequest = compositeProof }

        // The composite readback is async (GPU download callback); poll for the file.
        val deadline = System.currentTimeMillis() + 5000
        while (!Files.exists(compositeProof) && System.currentTimeMillis() < deadline) {
            Thread.sleep(50)
        }
        Files.exists(compositeProof).shouldBeTrue()
        logger.info("Composite proof (effect on, composite target) written to: {}", compositeProof)

        // ---- Phase 4: Teardown ----
        runOnClient { mc ->
            ViewportState.active = false
            DockState.reset()
            (mc.window as Any as WindowViewportExt).`garnet$updateScaledFramebuffer`(true)
        }
        waitClientTicks(4)
        ViewportState.active.shouldBeFalse()

        // Re-grab the mouse so later specs see normal input handling. setIgnoreFirstMove()
        // discards the first post-grab delta, avoiding a spurious camera jump from cursor
        // repositioning.
        runOnClient { mc ->
            mc.mouseHandler.setIgnoreFirstMove()
            mc.mouseHandler.grabMouse()
        }
    }
})
