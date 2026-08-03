---
title: Explorer session state
tags: [storage, config, explorer, client, persistence]
summary: The Explorer's expansion and selection persist to config/garnet-explorer.json, keyed by project root, restored one-shot when the first tree snapshot lands after a join.
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

## Arm at JOIN, apply at snapshot

The restore is a two-step, one-shot handshake between `ExplorerLifecycle` and
`ExplorerTreeState`, not a single "load and apply" call:

1. **Arm** — `ExplorerLifecycle`'s `ClientPlayConnectionEvents.JOIN` handler calls
   `ExplorerTreeState.armRestore(ExplorerStateStore.load())` before sending `ListEditorTreeC2S`
   (guarded by `ClientPlayNetworking.canSend`, so joining a vanilla server without the mod doesn't
   throw). This just stashes the loaded `ExplorerSession?` in `pendingRestore`.
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

`ExplorerLifecycle`'s private `saveExplorerSession()` — the function both save trigger points call
— returns early when `ProjectTreeState.snapshot == null`. Without a snapshot, `ExplorerTreeState`'s
expansion/selection are empty because the player never actually saw a tree (joined a vanilla
server, or disconnected before the snapshot arrived); persisting that emptiness would silently
blank out a perfectly good record from a prior session.

## Save trigger points

- **`ClientPlayConnectionEvents.DISCONNECT`** — saves first, then resets `ProjectTreeState` and
  `ExplorerTreeState`. The order matters: reading tree state after the reset would persist empty
  sets instead of the session that just ended. This covers quit-to-title, a multiplayer
  disconnect, and a kick.
- **`ClientLifecycleEvents.CLIENT_STOPPING`** — an idempotent second save, because DISCONNECT does
  not reliably fire when the player closes the game window from inside a world (the common way a
  session actually ends).

See [ui/dock-dialogs.md](../ui/dock-dialogs.md) for the root picker that produces the `root` this
state is keyed against, and
[architecture/redstone-project.md](../architecture/redstone-project.md) for where
`ExplorerTreeState`/`ProjectTreeState` sit in the Explorer's overall client-side split.
