package com.breadmoirai.garnet.test

import com.breadmoirai.garnet.ui.dock.DockRegion
import com.breadmoirai.garnet.ui.dock.DockState
import com.breadmoirai.garnet.ui.dock.insets
import com.breadmoirai.garnet.ui.viewport.ViewportState
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/**
 * Pure geometry of the dock: region sizes -> reserved insets -> the shrunk content rect.
 * Runs in the clientTest source set (which can see `client` classes) but touches no render
 * context, so it does not extend ClientSpec.
 */
class DockInsetsSpec : StringSpec({

    "hidden regions reserve no space" {
        DockState.reset()
        DockState.insets() shouldBe com.breadmoirai.garnet.ui.dock.DockInsets(0, 0, 0, 0)
    }

    "a visible left region reserves its width" {
        DockState.reset()
        DockState.setVisible(DockRegion.LEFT, true)
        DockState.setSize(DockRegion.LEFT, 260)
        DockState.insets() shouldBe com.breadmoirai.garnet.ui.dock.DockInsets(260, 0, 0, 0)
    }

    "insets drive the content rect, clamped to the minimum" {
        DockState.reset()
        DockState.setVisible(DockRegion.LEFT, true); DockState.setSize(DockRegion.LEFT, 260)
        DockState.setVisible(DockRegion.BOTTOM, true); DockState.setSize(DockRegion.BOTTOM, 160)
        val rect = ViewportState.contentRect(1000, 600)
        rect.frameX shouldBe 260
        rect.frameY shouldBe 0
        rect.frameWidth shouldBe 740
        rect.frameHeight shouldBe 440
    }

    "an over-wide reservation clamps content to MIN_CONTENT_SIZE, never negative" {
        DockState.reset()
        DockState.setVisible(DockRegion.LEFT, true); DockState.setSize(DockRegion.LEFT, 900)
        DockState.setVisible(DockRegion.RIGHT, true); DockState.setSize(DockRegion.RIGHT, 900)
        val rect = ViewportState.contentRect(1000, 600)
        (rect.frameWidth >= 64) shouldBe true
    }
})
