package com.breadmoirai.garnet.dock.shell

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.jetbrains.jewel.ui.icons.AllIconsKeys

/**
 * World-session teardown of the dock. `closeAll()` touches only snapshot state and never calls into
 * `Minecraft`, so -- like DockInsetsTest -- this needs no render context and is a plain StringSpec.
 */
class DockLifecycleTest : StringSpec({

    fun open(region: DockRegion) {
        val id = "probe.${region.name}"
        if (DockState.panelById(id) == null) {
            DockState.panels += Panel(id, region.name, region, AllIconsKeys.General.Information) {}
        }
        DockState.showPanel(id)
    }

    fun seedOpenDock() {
        DockState.reset()
        open(DockRegion.LEFT)
        open(DockRegion.RIGHT)
        open(DockRegion.BOTTOM)
        open(DockRegion.CENTER)
        DockState.setSize(DockRegion.LEFT, 320)
        DockState.focusedRegion = DockRegion.LEFT
    }

    "closeAll hides every edge region, clears CENTER, and drops focus" {
        seedOpenDock()
        DockState.closeAll()
        DockState.isVisible(DockRegion.LEFT) shouldBe false
        DockState.isVisible(DockRegion.RIGHT) shouldBe false
        DockState.isVisible(DockRegion.BOTTOM) shouldBe false
        DockState.isVisible(DockRegion.CENTER) shouldBe false
        // Not just closed: CENTER's panels are per-world documents, so closeAll drops them from the
        // registry entirely. isVisible alone would still pass if they were merely closed.
        DockState.panelsFor(DockRegion.CENTER).isEmpty() shouldBe true
        DockState.focusedRegion shouldBe null
        DockState.anyActive() shouldBe false
    }

    "closeAll keeps splitter sizes and edge panel registrations" {
        seedOpenDock()
        DockState.closeAll()
        DockState.leftWidth shouldBe 320
        DockState.panelsFor(DockRegion.LEFT).size shouldBe 1
        DockState.panelsFor(DockRegion.LEFT)[0].id shouldBe "probe.LEFT"
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
