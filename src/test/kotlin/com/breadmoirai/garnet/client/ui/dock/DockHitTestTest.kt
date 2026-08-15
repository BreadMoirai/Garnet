package com.breadmoirai.garnet.client.ui.dock

import com.breadmoirai.garnet.ui.dock.DockRegion
import com.breadmoirai.garnet.ui.dock.DockState
import com.breadmoirai.garnet.ui.dock.Panel
import com.breadmoirai.garnet.ui.dock.STRIPE_WIDTH
import com.breadmoirai.garnet.ui.dock.regionAt
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.jetbrains.jewel.ui.icons.AllIconsKeys

/**
 * The dock's pointer hit test: which region (if any) owns a window coordinate. `null` means the bare
 * world viewport, which is what `DockInputRouter` keys click-to-return-to-game off.
 *
 * Pure arithmetic over [DockState], so it needs no client — the routing of a real click through this
 * decision is `DockInputSpec` in `src/clientTest`. Geometry here must stay in lockstep with
 * `GarnetDock`'s layout; every case below names the `GarnetDock` rule it pins.
 */
class DockHitTestTest : FunSpec({

    val w = 1920
    val h = 1080

    afterTest { DockState.reset() }

    fun open(region: DockRegion) {
        val id = "probe.${region.name}"
        if (DockState.panelById(id) == null) {
            DockState.panels += Panel(id, region.name, region, AllIconsKeys.General.Information) {}
        }
        DockState.showPanel(id)
    }

    test("with nothing visible the whole window is the world") {
        DockState.reset()
        DockState.regionAt(0, 0, w, h) shouldBe null
        DockState.regionAt(w / 2, h / 2, w, h) shouldBe null
        DockState.regionAt(w - 1, h - 1, w, h) shouldBe null
    }

    test("a visible LEFT region claims its strip and nothing beyond it") {
        DockState.reset()
        open(DockRegion.LEFT)
        DockState.setSize(DockRegion.LEFT, 280)

        DockState.regionAt(0, 0, w, h) shouldBe DockRegion.LEFT
        DockState.regionAt(STRIPE_WIDTH + 279, h / 2, w, h) shouldBe DockRegion.LEFT
        // The first pixel past the strip is world, not LEFT.
        DockState.regionAt(STRIPE_WIDTH + 280, h / 2, w, h) shouldBe null
    }

    test("a visible RIGHT region claims the strip measured from the right window edge") {
        DockState.reset()
        open(DockRegion.RIGHT)
        DockState.setSize(DockRegion.RIGHT, 220)

        DockState.regionAt(w - 1, h / 2, w, h) shouldBe DockRegion.RIGHT
        DockState.regionAt(w - 220, h / 2, w, h) shouldBe DockRegion.RIGHT
        DockState.regionAt(w - 221, h / 2, w, h) shouldBe null
    }

    test("a visible BOTTOM region claims the full-width band and wins the corner overlap") {
        DockState.reset()
        open(DockRegion.BOTTOM)
        DockState.setSize(DockRegion.BOTTOM, 160)
        open(DockRegion.LEFT)
        DockState.setSize(DockRegion.LEFT, 280)
        open(DockRegion.RIGHT)
        DockState.setSize(DockRegion.RIGHT, 220)

        DockState.regionAt(w / 2, h - 1, w, h) shouldBe DockRegion.BOTTOM
        DockState.regionAt(w / 2, h - 160, w, h) shouldBe DockRegion.BOTTOM
        DockState.regionAt(w / 2, h - 161, w, h) shouldBe null
        // GarnetDock draws BOTTOM full-width over the LEFT/RIGHT columns (which stop at realH-bottom),
        // so both bottom corners belong to BOTTOM -- except the stripe's own column, which is drawn
        // over BOTTOM too and wins first.
        DockState.regionAt(STRIPE_WIDTH + 10, h - 1, w, h) shouldBe DockRegion.BOTTOM
        DockState.regionAt(w - 10, h - 1, w, h) shouldBe DockRegion.BOTTOM
        // Above the band the columns still own their strips.
        DockState.regionAt(STRIPE_WIDTH + 10, h - 161, w, h) shouldBe DockRegion.LEFT
        DockState.regionAt(w - 10, h - 161, w, h) shouldBe DockRegion.RIGHT
    }

    test("a hidden region claims nothing regardless of its stored size") {
        DockState.reset()
        DockState.setSize(DockRegion.LEFT, 400)
        DockState.setSize(DockRegion.BOTTOM, 300)
        // Never made visible: sizes are remembered but reserve no space.
        DockState.regionAt(10, 10, w, h) shouldBe null
        DockState.regionAt(10, h - 1, w, h) shouldBe null
    }

    test("CENTER owns the middle only while it holds a panel") {
        DockState.reset()
        open(DockRegion.LEFT)
        DockState.setSize(DockRegion.LEFT, 280)

        // Empty CENTER is transparent by omission: the middle IS the world.
        DockState.regionAt(w / 2, h / 2, w, h) shouldBe null

        open(DockRegion.CENTER)
        DockState.regionAt(w / 2, h / 2, w, h) shouldBe DockRegion.CENTER
        // An occupying CENTER does not steal the edge strips.
        DockState.regionAt(10, h / 2, w, h) shouldBe DockRegion.LEFT
    }

    test("coordinates outside the window belong to no region") {
        DockState.reset()
        open(DockRegion.LEFT)
        DockState.regionAt(-1, 10, w, h) shouldBe null
        DockState.regionAt(10, -1, w, h) shouldBe null
        DockState.regionAt(w, 10, w, h) shouldBe null
        DockState.regionAt(10, h, w, h) shouldBe null
    }
})
