package com.breadmoirai.garnet.client.ui.dock

import com.breadmoirai.garnet.dock.shell.DockInsets
import com.breadmoirai.garnet.dock.shell.DockRegion
import com.breadmoirai.garnet.dock.shell.DockState
import com.breadmoirai.garnet.dock.shell.Panel
import com.breadmoirai.garnet.dock.shell.STRIPE_WIDTH
import com.breadmoirai.garnet.dock.shell.insets
import com.breadmoirai.garnet.dock.viewport.ViewportState
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.jetbrains.jewel.ui.icons.AllIconsKeys

/**
 * Pure geometry of the dock: region sizes -> reserved insets -> the shrunk content rect.
 * Runs in `src/test` -- no client, no render context.
 */
class DockInsetsTest : StringSpec({

    fun open(region: DockRegion) {
        val id = "probe.${region.name}"
        if (DockState.panelById(id) == null) {
            DockState.panels += Panel(id, region.name, region, AllIconsKeys.General.Information) {}
        }
        DockState.showPanel(id)
    }

    "hidden regions reserve no space" {
        DockState.reset()
        DockState.insets() shouldBe DockInsets(0, 0, 0, 0)
    }

    "a visible left region reserves its width" {
        DockState.reset()
        open(DockRegion.LEFT)
        DockState.setSize(DockRegion.LEFT, 260)
        DockState.insets() shouldBe DockInsets(STRIPE_WIDTH + 260, 0, 0, 0)
    }

    "insets drive the content rect, clamped to the minimum" {
        DockState.reset()
        open(DockRegion.LEFT); DockState.setSize(DockRegion.LEFT, 260)
        open(DockRegion.BOTTOM); DockState.setSize(DockRegion.BOTTOM, 160)
        val rect = ViewportState.contentRect(1000, 600)
        rect.frameX shouldBe STRIPE_WIDTH + 260
        rect.frameY shouldBe 0
        rect.frameWidth shouldBe 1000 - STRIPE_WIDTH - 260
        rect.frameHeight shouldBe 440
    }

    "an over-wide reservation clamps content to MIN_CONTENT_SIZE, never negative" {
        DockState.reset()
        open(DockRegion.LEFT); DockState.setSize(DockRegion.LEFT, 900)
        open(DockRegion.RIGHT); DockState.setSize(DockRegion.RIGHT, 900)
        val rect = ViewportState.contentRect(1000, 600)
        (rect.frameWidth >= 64) shouldBe true
    }
})
