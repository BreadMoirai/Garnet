---
title: Explorer session state
tags: [storage, config, explorer, client, persistence, dock]
summary: The Explorer's expansion and selection persist to config/garnet-explorer.json, keyed by project root, restored one-shot when the first tree snapshot lands after a join; also covers the sibling config/garnet-dock.json store for remembered dock LEFT visibility.
---

# Explorer session state

`com.breadmoirai.garnet.config.ExplorerStateStore` round-trips `config/garnet-explorer.json`: a
single `{root, expanded[], selected}` record capturing which folders were open and which node was
selected in the Compose Project Explorer, so a player who quits mid-session gets the tree back the
way they left it rather than fully collapsed.

## Why not part of `ModConfig`

`ModConfig` (`garnet.json`) is deliberately a pure [`SharedSettings`](../architecture/module-map.md)
round-trip with no shadow state — every field it persists is also a field the dedicated server
reads via `SharedSettings`. Expansion and selection are neither: they are purely client-side UI
state, meaningless to a server and never read by one. Folding them into `ModConfig` would break
that "one object, one contract" invariant for a value with no server-side counterpart, so
`ExplorerStateStore` is a separate file with its own load/save.

## One record keyed by root, not a map

Only one `ExplorerSession` is ever stored, keyed by the `root` it was captured against. A record
whose `root` doesn't match the client's currently-configured root is discarded on load rather than
merged or migrated. This is deliberate: root swaps (via [Open Folder](../ui/dock-dialogs.md)) are
rare, so a per-root map that accumulates one entry per project ever opened would grow forever for a
benefit almost nobody uses — reopening an old, unrelated project and expecting its expansion state
to still be there. A single record keeps the format trivial and the failure mode obvious (wrong
root in, nothing out) instead of quietly bloating a JSON file across sessions. `save()` also
no-ops on a blank root: without a root there is no key to match against on a future load, so
writing would only ever produce a record that gets discarded later.

Every load failure — missing file, malformed JSON, a record with no `root` — degrades to `null`
rather than throwing. Restoring last session's tree state is a convenience; the fallback is simply
opening the tree fresh, the same as a first run.

## Singleplayer-only: the key has no cross-session meaning otherwise

Both save and restore are gated on `ExplorerSessionGate.isSingleplayer()`
(`ExplorerLifecycle.kt`), a seam object whose default is
`Minecraft.getInstance().hasSingleplayerServer()`. The record is keyed by
`SharedSettings.projectRootPath`, which is local client config — nothing on the client ever
overwrites it from a remote server's root (`EditorTreeSnapshotS2C` carries only a `FolderNode`
*name*, never an absolute path). Without the gate, the comparison on a remote connection is always
"this client's local key" against "this client's local key" — it always matches, regardless of
which server is actually on the other end. That would let a multiplayer disconnect persist a
remote server's tree state under the *local* project's key, corrupting the singleplayer record the
next local join restores from.

`hasSingleplayerServer()` is true exactly when the integrated server runs in this JVM, which is the
one case `SharedSettings` genuinely describes a session sharing state with the server. On any other
connection — a dedicated server, a friend's Garnet server, a vanilla server — nothing is armed on
JOIN and `saveExplorerSession` returns early on DISCONNECT/CLIENT_STOPPING, so
`config/garnet-explorer.json` is neither read nor written for that session.

The gate is a swappable `var` (`ExplorerSessionGate.isSingleplayer`, with `resetForTest()` to
restore the default) precisely so `ExplorerLifecycleTest` can drive both branches without a live
client or a network connection — it is the one thing standing between a remote session and
overwriting the local project's saved record, so it is not left untested. The arm step is its own
top-level function, `armRestoreIfSingleplayer()`, so a test can call it directly without going
through the JOIN event. `saveExplorerSession()` is public for the same reason.

## Arm at JOIN, apply at snapshot

The restore is a two-step, one-shot handshake between `ExplorerLifecycle` and
`ExplorerTreeState`, not a single "load and apply" call:

1. **Arm** — `ExplorerLifecycle`'s `ClientPlayConnectionEvents.JOIN` handler calls
   `armRestoreIfSingleplayer()`, which gates on `ExplorerSessionGate.isSingleplayer()` above and
   then calls `ExplorerTreeState.armRestore(ExplorerStateStore.load())`, before sending
   `ListEditorTreeC2S` (itself guarded by `ClientPlayNetworking.canSend`, so joining a vanilla
   server without the mod doesn't throw, and deliberately **not** gated by singleplayer — a remote
   Garnet server must still populate the tree, only the local persisted record is protected). This
   just stashes the loaded `ExplorerSession?` in `pendingRestore`.
2. **Apply** — `EditorClientNetworking`'s snapshot receiver calls
   `ExplorerTreeState.applyPendingRestore(payload.root)` immediately after feeding the same
   payload to `ProjectTreeState.onSnapshot`, then clears `pendingRestore`.

The two steps can't collapse into one because a path like `"adders/full-adder"` can't be expanded
before a tree containing that id exists — `applyPendingRestore` needs the just-arrived
`FolderNode` to resolve each persisted path and drop any that no longer exist (a folder renamed or
deleted since last session). Only folders enter `openNodes`; a persisted path that now resolves to
a file, or resolves to nothing, is filtered out. The persisted `selected` path is cleared the same
way if it no longer resolves.

The restore is one-shot by design: applying it clears `pendingRestore`, so a later manual Refresh
or a snapshot pushed by an unrelated file operation (new spec, rename, structure placement) does
not clobber expansion the player has already changed since rejoining. `ExplorerTreeState.reset()`
(called on disconnect) also clears `pendingRestore`, so a disconnect that lands between JOIN and
the first snapshot can't leak an armed restore into the next session, where it would apply against
the wrong project's tree.

## Save skips when no snapshot was ever seen

`ExplorerLifecycle`'s public `saveExplorerSession()` — the function both save trigger points call
— returns early when `ProjectTreeState.snapshot == null`. Without a snapshot, `ExplorerTreeState`'s
expansion/selection are empty because the player never actually saw a tree (joined a vanilla
server, or disconnected before the snapshot arrived); persisting that emptiness would silently
blank out a perfectly good record from a prior session.

## Save trigger points

- **`ClientPlayConnectionEvents.DISCONNECT`** — saves first, then resets `ProjectTreeState`,
  `ExplorerTreeState`, `UndoState`, `OpenStructureState`, and `LocalHistoryState`. The order
  matters: reading tree state after the reset would persist empty sets instead of the session that
  just ended. This covers quit-to-title, a multiplayer disconnect, and a kick. The last two are
  reset here too: a structure placed in the editor world, and its revision list, do not survive a
  world.
- **`ClientLifecycleEvents.CLIENT_STOPPING`** — an idempotent second save, because DISCONNECT does
  not reliably fire when the player closes the game window from inside a world (the common way a
  session actually ends).

## Root swap also resets the live tree state

`RootPickerController.openFolder`'s success path calls `ExplorerTreeState.reset()` (on the client
thread, alongside sending `SetEditorRootC2S`) before the new root's snapshot ever arrives. Without
this, `openNodes`/`selectedKeys` would still hold the **old** root's paths after a swap, and a
later disconnect would persist them under the **new** root's key — any path that happens to exist
in both projects (`src`, `adders`) would then restore as expansion the player never made there.
`reset()` also clears `pendingRestore`, which is correct here too: a restore armed for the old root
must not later apply against the new root's snapshot.

See [ui/dock-dialogs.md](../ui/dock-dialogs.md) for the root picker that produces the `root` this
state is keyed against, and
[architecture/redstone-project.md](../architecture/redstone-project.md) for where
`ExplorerTreeState`/`ProjectTreeState` sit in the Explorer's overall client-side split.

## Sibling store: `config/garnet-dock.json`

`com.breadmoirai.garnet.config.DockLayoutStore` round-trips a second, unrelated file:
`config/garnet-dock.json`, a `{ "open": { "LEFT": "garnet.explorer" } }` record mapping each
`DockRegion` to the id of the panel open there (absent region = closed). This replaced an earlier
`{ "leftVisible": true }` shape; `DockLayoutStore.load()` still reads that legacy shape and migrates
it in memory (`true` → `{"LEFT": "garnet.explorer"}`, `false` → `{}`), and the file is rewritten in
the new shape on the next `save()`. It is read on `ClientPlayConnectionEvents.JOIN` by
`applyDockAutoOpen()` (`ui/dock/DockAutoOpen.kt`) to auto-open the Explorer on a Garnet-capable
world — see [ui/dock-framework.md#left-auto-opens-on-joining-a-garnet-capable-world](../ui/dock-framework.md#left-auto-opens-on-joining-a-garnet-capable-world).

It is a separate file from `garnet-explorer.json` above, not a field added to that record, because
the two stores have opposite scoping. `garnet-explorer.json` is keyed by project root and, per
the singleplayer-only section above, is written only when `ExplorerSessionGate.isSingleplayer()`
holds. Dock visibility is neither: it is not root-keyed, and it must also restore on a remote
Garnet server, so folding it into the root-keyed, singleplayer-gated file would silently inherit
both restrictions.

It is also written at a different point in the session than `garnet-explorer.json`. Rather than
reading `DockState` in the `DISCONNECT` handler, `DockLayoutStore.save(...)` is called directly in
`registerDockKeybinds()` on the Shift+1 / Alt+1 keypresses that change which panel LEFT shows. A
disconnect-time read would race handler ordering: `DockState.closeAll()` (also run on
`DISCONNECT`, see [ui/dock-framework.md#world-session-lifecycle](../ui/dock-framework.md#world-session-lifecycle))
closes every region's open panel on that same event, so a save at that point could easily persist the
programmatic close instead of the player's last real choice.
