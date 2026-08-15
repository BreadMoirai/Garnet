package com.breadmoirai.garnet.client.ui.dock

import com.breadmoirai.garnet.ui.dock.DockRegion
import com.breadmoirai.garnet.ui.dock.DockState
import com.breadmoirai.garnet.ui.dock.Panel
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class DockTabStateTest : FunSpec({

    afterTest { DockState.reset() }

    fun seedTwoLeftPanels() {
        DockState.leftPanels += Panel("a", "Explorer") { }
        DockState.leftPanels += Panel("b", "Local History") { }
    }

    test("setActiveTab selects a panel by index") {
        seedTwoLeftPanels()

        DockState.setActiveTab(DockRegion.LEFT, 1)

        DockState.activeTab(DockRegion.LEFT) shouldBe 1
    }

    test("an out-of-range index is clamped rather than accepted") {
        seedTwoLeftPanels()

        DockState.setActiveTab(DockRegion.LEFT, 7)
        DockState.activeTab(DockRegion.LEFT) shouldBe 1

        DockState.setActiveTab(DockRegion.LEFT, -3)
        DockState.activeTab(DockRegion.LEFT) shouldBe 0
    }

    test("selecting a tab in an empty region stays at zero") {
        DockState.setActiveTab(DockRegion.LEFT, 2)

        DockState.activeTab(DockRegion.LEFT) shouldBe 0
    }
})
