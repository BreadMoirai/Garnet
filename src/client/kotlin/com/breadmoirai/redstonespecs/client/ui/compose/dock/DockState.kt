package com.breadmoirai.redstonespecs.client.ui.compose.dock

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList

/**
 * Single source of truth for the dock layout: which edge regions are visible, how big they are
 * (splitter positions), which tab is active, and which region has input focus.
 *
 * Fields are Compose **snapshot state** so [RedstoneDock] recomposes when they change, yet plain
 * reads (`.value` via the getters below) are cheap and thread-safe for [ViewportState]/`WindowMixin`
 * to consult when computing the framebuffer shrink. The geometry is authoritative *plain arithmetic*
 * updated eagerly by input handlers — never a side effect of rendering — so the shrink never waits
 * on a compose pass. See docs/superpowers/specs/2026-07-24-compose-panel-framework-design.md §2.
 */
object DockState {

    /** Default reserved sizes (px) when a region is first shown. */
    const val DEFAULT_LEFT = 260
    const val DEFAULT_RIGHT = 220
    const val DEFAULT_BOTTOM = 160

    /** Splitter clamps. */
    const val MIN_EDGE = 120
    const val MAX_EDGE = 640

    var leftVisible by mutableStateOf(false)
        private set
    var rightVisible by mutableStateOf(false)
        private set
    var bottomVisible by mutableStateOf(false)
        private set

    var leftWidth by mutableIntStateOf(DEFAULT_LEFT)
        private set
    var rightWidth by mutableIntStateOf(DEFAULT_RIGHT)
        private set
    var bottomHeight by mutableIntStateOf(DEFAULT_BOTTOM)
        private set

    val leftPanels: SnapshotStateList<Panel> = mutableStateListOf()
    val rightPanels: SnapshotStateList<Panel> = mutableStateListOf()
    val bottomPanels: SnapshotStateList<Panel> = mutableStateListOf()
    val centerPanels: SnapshotStateList<Panel> = mutableStateListOf()

    var leftActiveTab by mutableIntStateOf(0)
    var rightActiveTab by mutableIntStateOf(0)
    var bottomActiveTab by mutableIntStateOf(0)
    var centerActiveTab by mutableIntStateOf(0)

    /** Which region currently owns keyboard/pointer focus, or null when the game does. */
    var focusedRegion by mutableStateOf<DockRegion?>(null)

    fun panelsFor(region: DockRegion): SnapshotStateList<Panel> = when (region) {
        DockRegion.LEFT -> leftPanels
        DockRegion.RIGHT -> rightPanels
        DockRegion.BOTTOM -> bottomPanels
        DockRegion.CENTER -> centerPanels
    }

    fun isVisible(region: DockRegion): Boolean = when (region) {
        DockRegion.LEFT -> leftVisible
        DockRegion.RIGHT -> rightVisible
        DockRegion.BOTTOM -> bottomVisible
        DockRegion.CENTER -> centerPanels.isNotEmpty()
    }

    fun setVisible(region: DockRegion, visible: Boolean) {
        when (region) {
            DockRegion.LEFT -> leftVisible = visible
            DockRegion.RIGHT -> rightVisible = visible
            DockRegion.BOTTOM -> bottomVisible = visible
            DockRegion.CENTER -> {}
        }
    }

    /** Set an edge region's reserved size (width for L/R, height for B), clamped. */
    fun setSize(region: DockRegion, size: Int) {
        val clamped = size.coerceIn(MIN_EDGE, MAX_EDGE)
        when (region) {
            DockRegion.LEFT -> leftWidth = clamped
            DockRegion.RIGHT -> rightWidth = clamped
            DockRegion.BOTTOM -> bottomHeight = clamped
            DockRegion.CENTER -> {}
        }
    }

    fun toggleVisible(region: DockRegion) = setVisible(region, !isVisible(region))

    /** Test/reset hook: clears panels, hides all edges, restores default sizes and focus. */
    fun reset() {
        leftVisible = false; rightVisible = false; bottomVisible = false
        leftWidth = DEFAULT_LEFT; rightWidth = DEFAULT_RIGHT; bottomHeight = DEFAULT_BOTTOM
        leftPanels.clear(); rightPanels.clear(); bottomPanels.clear(); centerPanels.clear()
        leftActiveTab = 0; rightActiveTab = 0; bottomActiveTab = 0; centerActiveTab = 0
        focusedRegion = null
    }
}
