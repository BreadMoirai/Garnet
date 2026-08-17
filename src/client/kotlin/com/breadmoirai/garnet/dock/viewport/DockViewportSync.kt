package com.breadmoirai.garnet.dock.viewport

import com.breadmoirai.garnet.dock.compose.ComposeOverlay
import com.breadmoirai.garnet.dock.shell.DockState

/**
 * Derives [ViewportState.active] and [ComposeOverlay.enabled] from [DockState.anyActive]: the
 * viewport shrink + Compose overlay render exactly when the dock has something to show, and the
 * game is plain vanilla otherwise. This is the seam that makes the dock keybind self-sufficient
 * (no dependency on the separate V/C debug toggles in `ViewportToggle.kt`) — call it after any
 * `DockState` mutation. Does **not** touch the framebuffer; callers with a live `Window` must
 * follow up with `WindowViewportExt.garnet$updateScaledFramebuffer(true)` to apply it.
 *
 * Kept in its own file, separate from `DockKeybinds.kt`: that file's top-level `keyExplorerFocus`
 * registers a real `KeyMapping` at class-init time, which needs a live client. Kotlin compiles a
 * file's top-level declarations into one class with a shared `<clinit>`, so sharing a file with
 * that property would drag a real-client dependency onto this otherwise-pure function and break it
 * under a plain-JVM test (`DockViewportSyncTest` in `src/test`).
 */
fun syncDockViewport() {
    val active = DockState.anyActive()
    ViewportState.active = active
    ComposeOverlay.enabled = active
}
