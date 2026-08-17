package com.breadmoirai.garnet.client.ui.dock

import com.breadmoirai.garnet.dock.shell.DockRegion
import com.breadmoirai.garnet.dock.shell.DockState
import com.breadmoirai.garnet.dock.shell.Panel
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.jetbrains.jewel.ui.icons.AllIconsKeys

/**
 * `DockState.mountEpoch` exists so a panel body cannot outlive its mount — see that field for the
 * ghost-popup failure mode. The epoch must bump whenever a region's OPEN PANEL changes, not only
 * when the region closes: switching Explorer -> Local History reuses the region's body slot, and a
 * `Popup` opened in the first panel was added to the scene rather than to the keyed subtree, so it
 * survives the swap and paints over the second panel.
 */
class DockMountEpochTest : FunSpec({

    afterTest { DockState.reset() }

    fun seed() {
        DockState.reset()
        DockState.panels += Panel("a", "A", DockRegion.LEFT, AllIconsKeys.General.Information) {}
        DockState.panels += Panel("b", "B", DockRegion.LEFT, AllIconsKeys.General.Information) {}
    }

    test("switching panels within a region bumps that region's epoch") {
        seed()
        DockState.togglePanel("a")
        val before = DockState.mountEpoch(DockRegion.LEFT)
        DockState.togglePanel("b")
        (DockState.mountEpoch(DockRegion.LEFT) > before) shouldBe true
    }

    test("closing a region bumps its epoch") {
        seed()
        DockState.togglePanel("a")
        val before = DockState.mountEpoch(DockRegion.LEFT)
        DockState.togglePanel("a")
        (DockState.mountEpoch(DockRegion.LEFT) > before) shouldBe true
    }

    test("re-showing the panel that is already open bumps nothing") {
        seed()
        DockState.showPanel("a")
        val before = DockState.mountEpoch(DockRegion.LEFT)
        DockState.showPanel("a")
        DockState.mountEpoch(DockRegion.LEFT) shouldBe before
    }

    test("a change in one region leaves another region's epoch alone") {
        seed()
        DockState.panels += Panel("c", "C", DockRegion.BOTTOM, AllIconsKeys.General.Information) {}
        DockState.togglePanel("c")
        val bottom = DockState.mountEpoch(DockRegion.BOTTOM)
        DockState.togglePanel("a")
        DockState.mountEpoch(DockRegion.BOTTOM) shouldBe bottom
    }
})
