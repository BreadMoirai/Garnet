package com.breadmoirai.garnet.dock.shell

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.jetbrains.jewel.ui.icons.AllIconsKeys

/**
 * The dock's interaction model: one panel open per region, driven by [DockState.togglePanel].
 * Pure snapshot state, no render context — the same click routed through a real scene is
 * `JewelExplorerSpec` in `src/clientTest`.
 */
class DockPanelVisibilityTest : FunSpec({

    afterTest { DockState.reset() }

    fun seed() {
        DockState.reset()
        DockState.panels += Panel("a", "A", DockRegion.LEFT, AllIconsKeys.General.Information) {}
        DockState.panels += Panel("b", "B", DockRegion.LEFT, AllIconsKeys.General.Information) {}
        DockState.panels += Panel("c", "C", DockRegion.BOTTOM, AllIconsKeys.General.Information) {}
    }

    test("panelsFor filters the flat registry by region, in registration order") {
        seed()
        DockState.panelsFor(DockRegion.LEFT).map { it.id } shouldBe listOf("a", "b")
        DockState.panelsFor(DockRegion.BOTTOM).map { it.id } shouldBe listOf("c")
        DockState.panelsFor(DockRegion.RIGHT).map { it.id } shouldBe emptyList()
    }

    test("a region starts closed and opens when one of its panels is toggled") {
        seed()
        DockState.isVisible(DockRegion.LEFT) shouldBe false
        DockState.togglePanel("a")
        DockState.isVisible(DockRegion.LEFT) shouldBe true
        DockState.openPanelId(DockRegion.LEFT) shouldBe "a"
    }

    test("toggling a sibling switches the open panel without closing the region") {
        seed()
        DockState.togglePanel("a")
        DockState.togglePanel("b")
        DockState.openPanelId(DockRegion.LEFT) shouldBe "b"
        DockState.isVisible(DockRegion.LEFT) shouldBe true
    }

    test("toggling the already-open panel closes its region") {
        seed()
        DockState.togglePanel("a")
        DockState.togglePanel("a")
        DockState.isVisible(DockRegion.LEFT) shouldBe false
        DockState.openPanelId(DockRegion.LEFT) shouldBe null
    }

    test("regions are independent") {
        seed()
        DockState.togglePanel("a")
        DockState.togglePanel("c")
        DockState.openPanelId(DockRegion.LEFT) shouldBe "a"
        DockState.openPanelId(DockRegion.BOTTOM) shouldBe "c"
        DockState.togglePanel("c")
        DockState.openPanelId(DockRegion.LEFT) shouldBe "a"
        DockState.isVisible(DockRegion.BOTTOM) shouldBe false
    }

    test("an unknown id is ignored rather than throwing") {
        seed()
        DockState.togglePanel("nope")
        DockState.anyActive() shouldBe false
    }

    test("showPanel opens without the close-on-repeat behaviour") {
        seed()
        DockState.showPanel("a")
        DockState.showPanel("a")
        DockState.openPanelId(DockRegion.LEFT) shouldBe "a"
    }

    test("openMap round-trips through applyOpenMap, dropping unknown ids") {
        seed()
        DockState.togglePanel("a")
        DockState.togglePanel("c")
        val saved = DockState.openMap()
        saved shouldBe mapOf(DockRegion.LEFT to "a", DockRegion.BOTTOM to "c")

        DockState.closeRegion(DockRegion.LEFT)
        DockState.closeRegion(DockRegion.BOTTOM)
        DockState.applyOpenMap(saved + (DockRegion.RIGHT to "ghost"))
        DockState.openMap() shouldBe saved
    }

    test("applyOpenMap ignores an entry whose panel belongs to a different region") {
        seed()
        DockState.applyOpenMap(mapOf(DockRegion.BOTTOM to "a"))
        DockState.isVisible(DockRegion.BOTTOM) shouldBe false
    }
})
