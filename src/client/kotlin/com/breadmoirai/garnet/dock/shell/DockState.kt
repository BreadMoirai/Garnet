package com.breadmoirai.garnet.dock.shell

import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList

/**
 * Single source of truth for the dock layout: which panel is open in each region (and therefore
 * which regions are visible), how big they are (splitter positions), and which region has input
 * focus.
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

    var leftWidth by mutableIntStateOf(DEFAULT_LEFT)
        private set
    var rightWidth by mutableIntStateOf(DEFAULT_RIGHT)
        private set
    var bottomHeight by mutableIntStateOf(DEFAULT_BOTTOM)
        private set

    /**
     * Every registered panel, in registration order. Flat rather than four per-region lists: a panel
     * carries its own region (see [Panel]), so [panelsFor] derives the per-region view and there is
     * one definition of "which panels does this region have" — the one `DockStripe` renders.
     */
    val panels: SnapshotStateList<Panel> = mutableStateListOf()

    /**
     * Which panel is open in each region, by id. Absence means the region is closed, so this is the
     * single source of visibility — there is no separate `leftVisible` to disagree with it.
     */
    private val openPanel = mutableStateMapOf<DockRegion, String>()

    /** Which region currently owns keyboard/pointer focus, or null when the game does. */
    var focusedRegion by mutableStateOf<DockRegion?>(null)

    /**
     * Per-region "mount epoch": bumped whenever a region's open panel changes, or on [reset], and
     * used by [GarnetDock] as the `key()` of that region's panel body so the whole subtree is torn
     * down and rebuilt from scratch on the next mount.
     *
     * ## Why this exists (do not remove)
     * Panel content is invoked at a fixed slot position, and a re-mounted panel built by the same
     * factory produces a composable lambda with the *same* source key. Compose therefore reuses the
     * existing group and every `remember` inside the panel survives — including a Jewel `Dropdown`'s
     * internal open flag and the `Popup` layer it added to the scene. Worse, the dock stops rendering
     * the instant it is hidden ([com.breadmoirai.garnet.dock.viewport.syncDockViewport] drives
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

    fun panelsFor(region: DockRegion): List<Panel> = panels.filter { it.region == region }

    fun panelById(id: String): Panel? = panels.firstOrNull { it.id == id }

    fun openPanelId(region: DockRegion): String? = openPanel[region]

    /** The open panel's body, or null when the region is closed or its id no longer resolves. */
    fun openPanelOf(region: DockRegion): Panel? = openPanel[region]?.let { panelById(it) }

    fun isVisible(region: DockRegion): Boolean = openPanelOf(region) != null

    /**
     * Show [id]'s panel, evicting whatever its region had open. A no-op when it is already the open
     * one, so repeated calls (auto-open on join, Alt+1 held down) cost no mount-epoch churn.
     * Unknown ids are ignored: a panel can be removed between versions while its id survives in
     * `garnet-dock.json`.
     */
    fun showPanel(id: String) {
        val panel = panelById(id) ?: return
        if (openPanel[panel.region] == id) return
        openPanel[panel.region] = id
        bumpMountEpoch(panel.region)
    }

    /** Close [region], ending its open panel's mount. Idempotent. */
    fun closeRegion(region: DockRegion) {
        if (openPanel.remove(region) != null) bumpMountEpoch(region)
    }

    /**
     * The stripe's click, and the keybinds': show [id], or close its region when [id] is already the
     * open panel there. This is the whole interaction model — "click the lit icon to close" is what
     * makes a stripe a stripe rather than a row of radio buttons.
     */
    fun togglePanel(id: String) {
        val panel = panelById(id) ?: return
        if (openPanel[panel.region] == id) closeRegion(panel.region) else showPanel(id)
    }

    /** The persistable layout: which panel is open where. */
    fun openMap(): Map<DockRegion, String> = openPanel.toMap()

    /**
     * Restore [open], ignoring entries whose id is unknown or whose panel belongs to a different
     * region than the entry claims. Both are what a stale `garnet-dock.json` looks like after a
     * panel is removed or moved, and neither should wedge the dock.
     */
    fun applyOpenMap(open: Map<DockRegion, String>) {
        open.forEach { (region, id) ->
            if (panelById(id)?.region == region) showPanel(id)
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

    /**
     * True when the dock has something to show: any region open, or a region focused. Drives whether
     * the viewport shrink + Compose overlay should render (see `syncDockViewport` in
     * `DockViewportSync.kt`) and whether `DockStripe` is drawn at all — read-only, makes no state
     * changes itself.
     */
    fun anyActive(): Boolean = openPanel.isNotEmpty() || focusedRegion != null

    /** Test/reset hook: clears panels, closes every region, restores default sizes and focus. */
    fun reset() {
        openPanel.clear()
        leftWidth = DEFAULT_LEFT; rightWidth = DEFAULT_RIGHT; bottomHeight = DEFAULT_BOTTOM
        panels.clear()
        focusedRegion = null
        // Every region's panels are gone: force a fresh composition for whatever mounts next, so no
        // popup/widget state from the previous mount can bleed through (see [mountEpochs]).
        DockRegion.entries.forEach { bumpMountEpoch(it) }
    }

    /**
     * Ends the dock's **world session**: closes every region, drops the CENTER documents, and drops
     * input focus. Called when the client disconnects (see `registerDockWorldLifecycle` in
     * `viewport/DockKeybinds.kt`).
     *
     * Deliberately narrower than [reset]. The panel registry and splitter sizes are user *layout*,
     * not world state — and the Explorer is only ever registered at `onInitializeClient`, so a full
     * [reset] here would leave LEFT permanently empty for the rest of the process. CENTER panels
     * *are* removed from the registry: they are per-world documents that mean nothing without the
     * session that opened them.
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
        DockRegion.entries.forEach { closeRegion(it) }
        if (panels.any { it.region == DockRegion.CENTER }) {
            panels.removeAll { it.region == DockRegion.CENTER }
            bumpMountEpoch(DockRegion.CENTER)
        }
        focusedRegion = null
    }
}
