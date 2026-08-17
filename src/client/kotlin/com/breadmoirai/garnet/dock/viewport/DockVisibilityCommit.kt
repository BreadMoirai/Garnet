package com.breadmoirai.garnet.dock.viewport

import com.breadmoirai.garnet.dock.data.DockLayoutStore
import com.breadmoirai.garnet.dock.shell.DockRegion
import com.breadmoirai.garnet.dock.shell.DockState
import com.breadmoirai.garnet.dock.input.DockInputRouter
import net.minecraft.client.Minecraft

/**
 * The one definition of "dock visibility just changed — now make the rest of the client agree".
 *
 * ## Why this has to be a single function
 *
 * The world texture and the window's scaled framebuffer are **cached**, not recomputed per frame.
 * `WindowMixin` stores the shrunk size in `garnet$overrideFramebufferWidth/Height` and only ever
 * recomputes it from `garnet$updateScaledFramebuffer(true)` or a real OS window resize, while
 * `MinecraftPresentMixin` recomputes the destination rect from `DockInsets`
 * *fresh on every present*. Change visibility without this follow-up and the two disagree: a
 * `realW - 312`-wide game texture gets blitted into a `realW - 32`-wide rect, and the world stays
 * visibly stretched until something else resizes the framebuffer. That was the stripe-click bug —
 * `DockStripe` mutated `DockState` and stopped there while every keybind path ran four more steps.
 *
 * The steps, in order (the order matters):
 * 1. **Persist** the new open-panel map, when [persist] is asked for. Skipped for changes the user
 *    did not choose — a disconnect-time `closeAll()`, a join-time auto-open, a focus-only Alt+1 —
 *    because those would overwrite "what the player last chose" with a programmatic state.
 * 2. **Drop focus if the focused region just closed.** A closed region has no scene to route input
 *    into, so leaving [DockState.focusedRegion] pointing at it keeps the cursor released and keeps
 *    feeding clicks and keys to nothing.
 * 3. [syncDockViewport] — derive `ViewportState.active` / `ComposeOverlay.enabled`. Must run *after*
 *    step 2, since `focusedRegion` is one of the two inputs to `DockState.anyActive()`.
 * 4. **Apply the framebuffer**, which is what actually re-caches the override in `WindowMixin`.
 *
 * ## Test seams
 *
 * [persistLayout] and [applyFramebuffer] are settable so the sequence can be pinned from a plain-JVM
 * test with no render context and no config directory (`DockVisibilityCommitTest` in `src/test`).
 * [dropFocus] is a seam for the same reason: `DockInputRouter.clearFocus()` reaches
 * `Minecraft.getInstance()` to re-grab the mouse.
 */
object DockVisibilityCommit {

    /** Writes the open-panel map to `config/garnet-dock.json`. */
    var persistLayout: (Map<DockRegion, String>) -> Unit = { DockLayoutStore.save(it) }

    /** Releases dock input focus back to the game (re-grabbing the cursor when no Screen is open). */
    var dropFocus: () -> Unit = { DockInputRouter.clearFocus() }

    /**
     * Re-caches `WindowMixin`'s framebuffer override from the current insets.
     *
     * Reads `Minecraft.getInstance()` rather than taking a `Window` parameter so that the stripe's
     * click path — which is a Compose gesture deep inside `ui/dock`, a package deliberately kept free
     * of `Minecraft` imports so `DockState`/`DockStripe` stay unit-testable — does not have to thread
     * one down. Every caller already runs on the client thread (a tick handler, `mc.execute`, or the
     * render thread's scene dispatch), so the instance is live.
     */
    var applyFramebuffer: () -> Unit = {
        (Minecraft.getInstance().window as Any as WindowViewportExt).`garnet$updateScaledFramebuffer`(true)
    }

    fun resetForTest() {
        persistLayout = { DockLayoutStore.save(it) }
        dropFocus = { DockInputRouter.clearFocus() }
        applyFramebuffer = {
            (Minecraft.getInstance().window as Any as WindowViewportExt).`garnet$updateScaledFramebuffer`(true)
        }
    }
}

/**
 * Run the follow-up sequence documented on [DockVisibilityCommit] after any change to which dock
 * panels are open. Call it from *every* such site — the keybinds, the stripe's click (routed in
 * through a callback from `GarnetDock`), and `ExplorerActions.openLocalHistory`.
 *
 * Pass `persist = false` when the change was not the user choosing a layout: see step 1 of
 * [DockVisibilityCommit]'s doc.
 */
fun commitDockVisibilityChange(persist: Boolean = true) {
    if (persist) DockVisibilityCommit.persistLayout(DockState.openMap())
    val focused = DockState.focusedRegion
    if (focused != null && !DockState.isVisible(focused)) DockVisibilityCommit.dropFocus()
    syncDockViewport()
    DockVisibilityCommit.applyFramebuffer()
}
