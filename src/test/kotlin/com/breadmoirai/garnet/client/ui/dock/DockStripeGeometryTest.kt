package com.breadmoirai.garnet.client.ui.dock

import com.breadmoirai.garnet.ui.dock.DockInsets
import com.breadmoirai.garnet.ui.dock.DockRegion
import com.breadmoirai.garnet.ui.dock.DockState
import com.breadmoirai.garnet.ui.dock.Panel
import com.breadmoirai.garnet.ui.dock.STRIPE_WIDTH
import com.breadmoirai.garnet.ui.dock.insets
import com.breadmoirai.garnet.ui.dock.regionAt
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.jetbrains.jewel.ui.icons.AllIconsKeys

/**
 * The stripe's contribution to the two pure-geometry surfaces: [insets] (how much the world shrinks)
 * and [regionAt] (who owns a pixel). The stripe is drawn LAST in `GarnetDock` and tested FIRST here;
 * those two facts are one decision and must move together.
 */
class DockStripeGeometryTest : FunSpec({

    val w = 1920
    val h = 1080

    afterTest { DockState.reset() }

    fun seed() {
        DockState.reset()
        DockState.panels += Panel("l", "L", DockRegion.LEFT, AllIconsKeys.General.Information) {}
        DockState.panels += Panel("b", "B", DockRegion.BOTTOM, AllIconsKeys.General.Information) {}
    }

    test("a closed dock reserves nothing at all — no stripe, no world shrink") {
        seed()
        DockState.insets() shouldBe DockInsets(0, 0, 0, 0)
        DockState.regionAt(0, 0, w, h) shouldBe null
    }

    test("an open LEFT reserves the stripe plus the panel width") {
        seed()
        DockState.togglePanel("l")
        DockState.setSize(DockRegion.LEFT, 280)
        DockState.insets() shouldBe DockInsets(STRIPE_WIDTH + 280, 0, 0, 0)
    }

    test("the stripe is reserved even when only BOTTOM is open") {
        seed()
        DockState.togglePanel("b")
        DockState.setSize(DockRegion.BOTTOM, 160)
        DockState.insets() shouldBe DockInsets(STRIPE_WIDTH, 0, 160, 0)
    }

    test("focus alone keeps the stripe reserved") {
        seed()
        DockState.focusedRegion = DockRegion.LEFT
        DockState.insets() shouldBe DockInsets(STRIPE_WIDTH, 0, 0, 0)
    }

    test("the stripe column belongs to LEFT, and the LEFT panel starts after it") {
        seed()
        DockState.togglePanel("l")
        DockState.setSize(DockRegion.LEFT, 280)

        DockState.regionAt(0, h / 2, w, h) shouldBe DockRegion.LEFT
        DockState.regionAt(STRIPE_WIDTH - 1, h / 2, w, h) shouldBe DockRegion.LEFT
        DockState.regionAt(STRIPE_WIDTH, h / 2, w, h) shouldBe DockRegion.LEFT
        // Stripe + panel, and the first pixel past both is world.
        DockState.regionAt(STRIPE_WIDTH + 279, h / 2, w, h) shouldBe DockRegion.LEFT
        DockState.regionAt(STRIPE_WIDTH + 280, h / 2, w, h) shouldBe null
    }

    test("the stripe wins its column inside the BOTTOM band, which it is drawn over") {
        seed()
        DockState.togglePanel("l")
        DockState.togglePanel("b")
        DockState.setSize(DockRegion.BOTTOM, 160)

        // GarnetDock draws the stripe LAST, full height, so it beats BOTTOM's full-width band here.
        DockState.regionAt(4, h - 1, w, h) shouldBe DockRegion.LEFT
        // Just past the stripe, the band still owns the bottom-left corner.
        DockState.regionAt(STRIPE_WIDTH + 4, h - 1, w, h) shouldBe DockRegion.BOTTOM
    }

    test("with only BOTTOM open the stripe still owns its column and still reads as LEFT") {
        seed()
        DockState.togglePanel("b")
        DockState.regionAt(4, h / 2, w, h) shouldBe DockRegion.LEFT
        DockState.regionAt(STRIPE_WIDTH + 4, h / 2, w, h) shouldBe null
    }
})
