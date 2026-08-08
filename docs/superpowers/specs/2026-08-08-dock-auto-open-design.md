# Auto-open the dock on joining a Garnet world

**Date:** 2026-08-08
**Status:** Design approved, pending implementation

## Problem

The LEFT dock region (the Project Explorer) starts hidden and stays hidden until the player presses
Shift+1 or Alt+1. Every session in a Garnet project world therefore begins with the same manual
keypress. The dock should be up when the player arrives.

Two constraints came out of the design discussion:

- **Not focused.** Auto-open reveals the region; the game keeps keyboard/pointer input. The cursor
  stays grabbed and WASD still moves. Alt+1 remains the only way to hand focus to the panel.
- **Remembered.** A player who deliberately hides the dock should not have it forced back open on the
  next join.

## Scope

LEFT only. RIGHT and BOTTOM have no panels registered today, and CENTER is per-world documents that
`closeAll()` deliberately drops. Splitter sizes are out of scope — they are already process-lifetime
state that `closeAll()` preserves, and nothing asked for them to survive a restart.

## Design

### 1. Gate: Garnet-capable servers

Auto-open fires only when the peer speaks Garnet, probed as
`ClientPlayNetworking.canSend(ListEditorTreeC2S.TYPE)` — the same guard `registerExplorerLifecycle`
already uses before requesting the tree.

This deliberately includes remote Garnet servers, not just singleplayer. The narrower
`ExplorerSessionGate.isSingleplayer()` gate exists to protect the *persisted per-root record* from a
foreign tree; dock visibility is neither root-keyed nor server-derived, so that reasoning does not
transfer. On a vanilla server the dock stays hidden — an empty Explorer that shrinks the viewport is
strictly worse than no dock.

The probe gets its own seam so both branches are unit-testable without a server:

```kotlin
object DockAutoOpenGate {
    var isGarnetServer: () -> Boolean = { ClientPlayNetworking.canSend(ListEditorTreeC2S.TYPE) }
    fun resetForTest() { isGarnetServer = { ClientPlayNetworking.canSend(ListEditorTreeC2S.TYPE) } }
}
```

This puts one editor-payload import into the dock shell. `registerDockWorldLifecycle`'s class doc
currently asserts the shell "has no notion of the Explorer feature"; amend it to say the shell knows
only *is the peer a Garnet server*, asked through this seam, and that the payload type is an
implementation detail of the default probe.

### 2. Storage: a new `config/garnet-dock.json`

```kotlin
object DockLayoutStore {
    fun load(): Boolean   // stored leftVisible, or true when absent/malformed
    fun save(leftVisible: Boolean)
}
```

Payload: `{ "leftVisible": true }`.

**Not `ModConfig`/`SharedSettings`.** `ExplorerStateStore`'s docstring already establishes the
boundary: `ModConfig` is a pure `SharedSettings` round-trip, and `SharedSettings` is read by the
dedicated server. Dock visibility is client UI state a server must never see.

**Not folded into `garnet-explorer.json`.** That record is keyed by project root and written only in
singleplayer. Dock visibility is neither, and reusing the file would silently inherit both
restrictions — breaking the remote-server half of the gate in §1.

Missing file, unreadable file, or malformed JSON all yield `true`. A restore is a convenience; every
failure degrades to "open the dock", which is the requested default for a fresh install.

### 3. Write on user action, not at disconnect

Persist inside `registerDockKeybinds`, immediately after a visibility change:

```kotlin
DockLayoutStore.save(DockState.isVisible(DockRegion.LEFT))
```

Two call sites: the Shift+1 branch (which toggles), and the show-and-focus half of the Alt+1 branch.
Alt+1's other half only calls `DockInputRouter.clearFocus()` and leaves visibility alone, so it does
not write.

These are the *only* places a user changes LEFT visibility — verified by grep:
`setVisible`/`toggleVisible` callers outside tests are these two sites plus `closeAll()` itself.

The rejected alternative was reading `DockState` in a DISCONNECT handler. `DockState.closeAll()`
sets `leftVisible = false` on that same event, so such a save races Fabric's handler registration
order (`registerDockWorldLifecycle` happens to be registered before `registerExplorerLifecycle` in
`GarnetClient`, but nothing enforces it) and would persist the programmatic close rather than the
player's choice. Writing on the keypress also removes any need for a `CLIENT_STOPPING` backstop —
the Explorer needs one only because it reads live state at exit, and the window-close-from-in-world
path never fires DISCONNECT.

Consequence, and it is the intended semantics: the persisted value means *what the player last
chose*. Auto-open itself and `closeAll()` never write.

### 4. Read on JOIN

A new `ClientPlayConnectionEvents.JOIN` handler in `registerDockWorldLifecycle`, alongside the
existing DISCONNECT one, its body inside `mc.execute { }` for the documented reason the DISCONNECT
handler already uses — `fabric-networking-api-v1` fires these from either the main thread or a Netty
event-loop thread, and `garnet$updateScaledFramebuffer` reaches `resizeGui()`.

Split so the decision stays free of `Minecraft`, mirroring how `closeAll()` is kept testable:

```kotlin
/** Applies the remembered LEFT visibility. Returns true when the dock actually opened. */
fun applyDockAutoOpen(): Boolean {
    if (!DockAutoOpenGate.isGarnetServer()) return false
    if (!DockLayoutStore.load()) return false
    if (DockState.isVisible(DockRegion.LEFT)) return false
    DockState.setVisible(DockRegion.LEFT, true)
    return true
}
```

The event handler calls it and, only on `true`, follows with `syncDockViewport()` and
`garnet$updateScaledFramebuffer(true)` — the same pair both keybind branches already run.

`DockInputRouter.focus(...)` is **not** called. `focusedRegion` stays null, so the input mixins keep
routing to the game and the cursor stays grabbed.

## Error handling

Both stores degrade rather than propagate, matching `ExplorerStateStore`: load failures log at `warn`
and return the default; save failures log at `error` and are dropped. A failed persist costs the
player one keypress next session.

## Testing

Unit (`src/test`, plain `StringSpec` — no render context needed):

- `DockLayoutStoreTest`: round-trip true/false; absent file → true; malformed JSON → true;
  unparseable value → true. Uses the `configFileForTest` seam pattern from `ExplorerStateStoreTest`.
- `DockLifecycleTest` additions, driving `DockAutoOpenGate.isGarnetServer` and a temp store file:
  - vanilla server (`isGarnetServer` false) → LEFT stays hidden, `applyDockAutoOpen()` returns false
  - Garnet server + stored `false` → LEFT stays hidden
  - Garnet server + stored `true` → LEFT visible, `focusedRegion` still null
  - Garnet server + no record → LEFT visible (default-open)
  - already-visible LEFT → returns false, so the caller skips the framebuffer churn

## Docs to update

- `docs/ui/dock-framework.md` — the "OFF-by-default / hidden until Shift+1 reveals it" description
  is no longer the whole truth; describe auto-open and its gate.
- `docs/ui/dock-input-routing.md` — note that auto-open is visibility-only and does not focus.
- `docs/persistence/explorer-session-state.md` — cross-reference the new `garnet-dock.json` and why
  it is a separate file from `garnet-explorer.json`.
- `docs/ui/INDEX.md` / `docs/persistence/INDEX.md` — refresh the touched summaries.
- `GarnetClient.kt`'s seeding comment ("region stays hidden until Shift+1 reveals it").
