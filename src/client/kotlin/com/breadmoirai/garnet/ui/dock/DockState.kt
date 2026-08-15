package com.breadmoirai.garnet.ui.dock

import androidx.compose.runtime.MutableIntState
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
 * Fields are Compose **snapshot state** so [GarnetDock] recomposes when they change, yet plain
 * reads (`.value` via the getters below) are cheap and thread-safe for [ViewportState]/`WindowMixin`
 * to consult when computing the framebuffer shrink. The geometry is authoritative *plain arithmetic*
 * updated eagerly by input handlers — never a side effect of rendering — so the shrink never waits
 * on a compose pass. See docs/superpowers/specs/2026-07-24-compose-panel-framework-design.md §2.
 */
object DockState {

    /**
     * Default reserved sizes (px) when a region is first shown.
     *
     * [DEFAULT_LEFT] is 280, not 260. That value was originally sized to fit the Explorer's old
     * action row (name field, `+ New`, `Save`, `Discard`) without clipping. That row is gone; the
     * current toolbar (kebab menu + refresh + collapse-all, all compact icon buttons -- see
     * `ExplorerToolbar.kt`) has no content anywhere near this wide. The value is kept at 280 anyway,
     * purely to avoid changing a user-visible default width with no user-facing reason to: nothing
     * about today's toolbar requires it, and [MIN_EDGE] already lets a user drag narrower if they want.
     */
    const val DEFAULT_LEFT = 280
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

    /**
     * Per-region "mount epoch": bumped whenever a region is hidden or [reset], and used by
     * [GarnetDock] as the `key()` of that region's panel body so the whole subtree is torn down and
     * rebuilt from scratch on the next mount.
     *
     * ## Why this exists (do not remove)
     * Panel content is invoked at a fixed slot position, and a re-mounted panel built by the same
     * factory produces a composable lambda with the *same* source key. Compose therefore reuses the
     * existing group and every `remember` inside the panel survives — including a Jewel `Dropdown`'s
     * internal open flag and the `Popup` layer it added to the scene. Worse, the dock stops rendering
     * the instant it is hidden ([com.breadmoirai.garnet.ui.viewport.syncDockViewport] drives
     * `ComposeOverlay.enabled` off `anyActive()`), so no recomposition ever runs *while* the panel is
     * absent and the removal that would have disposed that popup never happens. Net effect: open the
     * root menu, hide the dock, show it again, and a ghost menu paints over the fresh panel.
     *
     * Keying on the epoch makes "hidden then shown again" a genuinely new composition, so popup
     * layers and per-panel widget state cannot outlive the mount that created them. It is per-region
     * rather than global so hiding LEFT does not throw away RIGHT/BOTTOM panel state.
     */
    private val mountEpochs: Map<DockRegion, MutableIntState> =
        DockRegion.entries.associateWith { mutableIntStateOf(0) }

    fun mountEpoch(region: DockRegion): Int = mountEpochs.getValue(region).intValue

    private fun bumpMountEpoch(region: DockRegion) {
        mountEpochs.getValue(region).intValue++
    }

    fun panelsFor(region: DockRegion): SnapshotStateList<Panel> = when (region) {
        DockRegion.LEFT -> leftPanels
        DockRegion.RIGHT -> rightPanels
        DockRegion.BOTTOM -> bottomPanels
        DockRegion.CENTER -> centerPanels
    }

    fun activeTab(region: DockRegion): Int = when (region) {
        DockRegion.LEFT -> leftActiveTab
        DockRegion.RIGHT -> rightActiveTab
        DockRegion.BOTTOM -> bottomActiveTab
        DockRegion.CENTER -> centerActiveTab
    }

    /**
     * Select which panel a region shows. Clamped to the region's real indices, so a stale index from
     * a caller that has not seen a panel list change can never point past the end — [GarnetDock]
     * would otherwise index out of bounds mid-composition.
     */
    fun setActiveTab(region: DockRegion, index: Int) {
        val clamped = index.coerceIn(0, (panelsFor(region).size - 1).coerceAtLeast(0))
        when (region) {
            DockRegion.LEFT -> leftActiveTab = clamped
            DockRegion.RIGHT -> rightActiveTab = clamped
            DockRegion.BOTTOM -> bottomActiveTab = clamped
            DockRegion.CENTER -> centerActiveTab = clamped
        }
    }

    fun isVisible(region: DockRegion): Boolean = when (region) {
        DockRegion.LEFT -> leftVisible
        DockRegion.RIGHT -> rightVisible
        DockRegion.BOTTOM -> bottomVisible
        DockRegion.CENTER -> centerPanels.isNotEmpty()
    }

    fun setVisible(region: DockRegion, visible: Boolean) {
        // Hiding a region ends its panels' mount: bump the epoch so the next show composes fresh
        // (see [mountEpochs] for the ghost-popup failure mode this prevents).
        if (!visible && isVisible(region)) bumpMountEpoch(region)
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

    /**
     * True when the dock has something to show: any edge region visible, a center panel present,
     * or a region focused. Drives whether the viewport shrink + Compose overlay should render
     * (see `syncDockViewport` in `DockViewportSync.kt`) — read-only, makes no state changes itself.
     */
    fun anyActive(): Boolean =
        leftVisible || rightVisible || bottomVisible || centerPanels.isNotEmpty() || focusedRegion != null

    /** Test/reset hook: clears panels, hides all edges, restores default sizes and focus. */
    fun reset() {
        leftVisible = false; rightVisible = false; bottomVisible = false
        leftWidth = DEFAULT_LEFT; rightWidth = DEFAULT_RIGHT; bottomHeight = DEFAULT_BOTTOM
        leftPanels.clear(); rightPanels.clear(); bottomPanels.clear(); centerPanels.clear()
        leftActiveTab = 0; rightActiveTab = 0; bottomActiveTab = 0; centerActiveTab = 0
        focusedRegion = null
        // Every region's panels are gone: force a fresh composition for whatever mounts next, so no
        // popup/widget state from the previous mount can bleed through (see [mountEpochs]).
        DockRegion.entries.forEach { bumpMountEpoch(it) }
    }

    /**
     * Ends the dock's **world session**: hides every edge region, clears the CENTER documents, and
     * drops input focus. Called when the client disconnects (see `registerDockWorldLifecycle` in
     * `viewport/DockKeybinds.kt`).
     *
     * Deliberately narrower than [reset]. The panel lists and splitter sizes are user *layout*, not
     * world state — and the Project Explorer is only ever added at `onInitializeClient`, so a full
     * [reset] here would leave LEFT permanently empty for the rest of the process. CENTER *is*
     * cleared: its panels are per-world documents that mean nothing without the session that opened
     * them. [setVisible] already bumps a hidden region's mount epoch; CENTER never goes through it,
     * so its epoch is bumped here (see [mountEpochs] for the ghost-popup failure mode).
     *
     * [focusedRegion] is cleared directly instead of via `DockInputRouter.clearFocus()`: that helper
     * re-grabs the mouse when no [net.minecraft.client.gui.screens.Screen] is open, and at disconnect
     * time the title screen is not reliably installed yet, so it would capture the cursor on the
     * title screen. `DockInputRouter.captured` reads through to this field, so clearing it is enough
     * to stop the input mixins. Keeping this method free of `Minecraft` calls also keeps it testable.
     *
     * Idempotent: calling it on an already-closed dock changes nothing.
     */
    fun closeAll() {
        setVisible(DockRegion.LEFT, false)
        setVisible(DockRegion.RIGHT, false)
        setVisible(DockRegion.BOTTOM, false)
        if (centerPanels.isNotEmpty()) {
            centerPanels.clear()
            bumpMountEpoch(DockRegion.CENTER)
        }
        // Only centerActiveTab is zeroed: CENTER's panels were just cleared above, so index 0 is the
        // only sane value. left/right/bottomActiveTab are left alone on purpose — those regions' panel
        // lists survive closeAll() (see the class doc above), so their active-tab index still points at
        // a real panel and resetting it would just discard the user's edge-panel tab choice for nothing.
        centerActiveTab = 0
        focusedRegion = null
    }
}
