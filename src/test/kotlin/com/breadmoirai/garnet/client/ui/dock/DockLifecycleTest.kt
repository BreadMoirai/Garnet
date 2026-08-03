package com.breadmoirai.garnet.client.ui.dock

import com.breadmoirai.garnet.ui.dock.DockRegion
import com.breadmoirai.garnet.ui.dock.DockState
import com.breadmoirai.garnet.ui.dock.Panel
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/**
 * World-session teardown of the dock. `closeAll()` touches only snapshot state and never calls into
 * `Minecraft`, so -- like DockInsetsTest -- this needs no render context and is a plain StringSpec.
 */
class DockLifecycleTest : StringSpec({

    fun seedOpenDock() {
        DockState.reset()
        DockState.leftPanels.add(Panel("test.left", "Left") {})
        DockState.centerPanels.add(Panel("test.center", "Center") {})
        DockState.setVisible(DockRegion.LEFT, true)
        DockState.setVisible(DockRegion.RIGHT, true)
        DockState.setVisible(DockRegion.BOTTOM, true)
        DockState.setSize(DockRegion.LEFT, 320)
        DockState.focusedRegion = DockRegion.LEFT
    }

    "closeAll hides every edge region, clears CENTER, and drops focus" {
        seedOpenDock()
        DockState.closeAll()
        DockState.isVisible(DockRegion.LEFT) shouldBe false
        DockState.isVisible(DockRegion.RIGHT) shouldBe false
        DockState.isVisible(DockRegion.BOTTOM) shouldBe false
        DockState.centerPanels.isEmpty() shouldBe true
        DockState.centerActiveTab shouldBe 0
        DockState.focusedRegion shouldBe null
        DockState.anyActive() shouldBe false
    }

    "closeAll keeps splitter sizes and edge panel registrations" {
        seedOpenDock()
        DockState.closeAll()
        DockState.leftWidth shouldBe 320
        DockState.leftPanels.size shouldBe 1
        DockState.leftPanels[0].id shouldBe "test.left"
    }

    "closeAll bumps the mount epoch of every region it tore down" {
        seedOpenDock()
        val before = DockRegion.entries.associateWith { DockState.mountEpoch(it) }
        DockState.closeAll()
        DockRegion.entries.forEach { region ->
            (DockState.mountEpoch(region) > before.getValue(region)) shouldBe true
        }
    }

    "closeAll is idempotent — a second call changes nothing" {
        seedOpenDock()
        DockState.closeAll()
        val epochs = DockRegion.entries.associateWith { DockState.mountEpoch(it) }
        DockState.closeAll()
        DockRegion.entries.forEach { region ->
            DockState.mountEpoch(region) shouldBe epochs.getValue(region)
        }
        DockState.anyActive() shouldBe false
    }
})
