# Explorer session persistence + NativeFileDialog picker — design

**Date:** 2026-08-02
**Status:** approved, ready for planning

## Problem

Three related gaps in the Project Explorer's session handling:

1. **The Explorer is blank after a client restart.** `ListEditorTreeC2S` is sent from exactly one
   place — the Refresh button in `ExplorerToolbar.kt`. Nothing requests the tree on join, so the
   panel stays empty until the player clicks Refresh.
2. **Expansion and selection are lost.** `ExplorerLifecycle`'s DISCONNECT handler resets
   `ProjectTreeState` and `ExplorerTreeState`, and nothing writes that state to disk.
3. **The folder picker uses tinyfd.** `TinyfdFolderPicker` calls
   `TinyFileDialogs.tinyfd_selectFolderDialog`, which on Windows is the dated legacy Win32 folder
   browser. LWJGL's NativeFileDialog gives the real `IFileDialog` / `NSOpenPanel` / GTK dialogs.

**Not in scope.** The project root itself already persists correctly:
`RootPickerController.persist` writes `projectRootPath` to `config/garnet.json`,
`GarnetClient.onInitializeClient` calls `ModConfig.load()`, and `Garnet.onInitialize`'s
`SERVER_STARTING` listener pins an `EditorServerContext` from `SharedSettings.projectRootPath`. The
reported "root isn't saved" symptom is entirely gap 1 — a blank panel, not a forgotten root. The
separate known limitation that `ModConfig` lives in the client source set and so never loads on a
dedicated server is left as-is.

## Where the Explorer's state lives

A new client-only `config/garnet-explorer.json`, owned by a new `ExplorerStateStore` object in the
client source set.

`ModConfig`'s docstring asserts an invariant: it is a pure `SharedSettings` round-trip that holds no
shadow state, so a caller mutating a setting directly and calling `save()` persists exactly what it
set. Expansion and selection are client UI state the server must never see. Routing them through
`SharedSettings` would break that invariant *and* leak UI state into the config a dedicated server
reads. A separate store keeps the boundary clean and stays independently testable.

Rejected alternatives:

- **Extend `ModConfig` with an `explorer` object.** One file for the user, but `ModConfig` grows a
  second concern and the "no shadow state" invariant dies.
- **Keep the state in memory across DISCONNECT only.** Survives quit-to-title, not a client
  restart — which is the actual requirement.

## Components

### `ExplorerStateStore` (client source set)

Round-trips `config/garnet-explorer.json`:

```json
{ "root": "/abs/project/path", "expanded": ["", "sub", "sub/deep"], "selected": "sub/deep/thing" }
```

API: `load(): Snapshot?`, `save(root: String, expanded: Set<String>, selected: String?)`, plus
`configFileForTest(file)` / `resetConfigFileForTest()` seams mirroring `ModConfig`'s shape. A
malformed or absent file loads as `null` rather than throwing, and a load failure is logged at WARN.

`ExplorerTreeState` makes the payload nearly free: Jewel's `openNodes` and `selectedKeys` **are**
sets of `/`-joined path strings, exposed as `expandedPaths` and `selectedPath`. Nothing new is
mirrored — the store reads those on save and writes through `treeState` on restore. The root node's
own id is `ExplorerTreeState.ROOT_PATH` (`""`), which round-trips as an empty string in the
`expanded` array.

### Keying: by root, single record

`EditorTreeSnapshotS2C` carries `root: FolderNode` — a folder *name*, not an absolute path — so the
key comes from `SharedSettings.projectRootPath` instead.

- One record, not a map. Root swaps are rare; an unbounded per-root map would grow forever for no
  benefit.
- If the persisted `root` does not match the active one, the record is discarded and the tree opens
  fresh. That is the correct behavior after an Open Folder swap, and it degrades safely on a
  multiplayer server whose root differs from the client's config — the keys simply don't match and
  no restore happens.
- A blank `SharedSettings.projectRootPath` skips persistence entirely, on both save and load.

### Auto-load on join

`ExplorerLifecycle` gains a `ClientPlayConnectionEvents.JOIN` handler alongside the existing
DISCONNECT one. The body runs inside `mc.execute { }` for the same reason DISCONNECT does —
`fabric-networking-api-v1` can fire these events from a Netty event-loop thread.

The handler guards on `ClientPlayNetworking.canSend(ListEditorTreeC2S.TYPE)` before sending;
joining a vanilla server without the mod installed must not throw. It then sends
`ListEditorTreeC2S.INSTANCE` and arms the pending restore (below).

`ExplorerToolbar`'s Refresh button is unchanged — this only adds a second send site.

### Restore timing

Restore cannot happen at JOIN: expanding path `"sub/deep"` is meaningless before a tree containing
that id exists. It fires when the snapshot lands.

A one-shot `pendingRestore` field lives on `ExplorerTreeState` — the object that already owns
expansion and selection. `ExplorerLifecycle`'s JOIN handler arms it by calling
`ExplorerTreeState.armRestore(ExplorerStateStore.load())`, and `EditorClientNetworking`'s snapshot
handler consumes it via `ExplorerTreeState.applyPendingRestore(payload.root)` immediately after the
existing `ProjectTreeState.onSnapshot(payload)` call, inside the same `ctx.client().execute { }`.
`applyPendingRestore` is a no-op when nothing is armed. Driving it from
that call site rather than from inside `onSnapshot` keeps `ProjectTreeState` and `ExplorerTreeState`
passive siblings, preserving the separation both docstrings assert (tree *data* in one, tree
*interaction* state in the other).

The restore is one-shot so that a later manual Refresh — or a snapshot pushed after a file
operation — does not clobber the expansion the player has since changed.

Paths in the persisted record that no longer exist in the snapshot are dropped silently. Writing a
stale id into `openNodes` would be inert rather than harmful, but filtering keeps the persisted set
from accumulating garbage across sessions. A `selected` path that no longer resolves leaves
selection empty.

`ExplorerTreeState.reset()` also clears `pendingRestore`, so a disconnect that happens between JOIN
and the first snapshot cannot leak an armed restore into the next session.

### Save timing

Two trigger points:

1. `ExplorerLifecycle`'s DISCONNECT handler, **before** the existing `ProjectTreeState.reset()` /
   `ExplorerTreeState.reset()` calls — reading after the reset would persist empty sets.
2. `ClientLifecycleEvents.CLIENT_STOPPING`, covering alt-F4 from in-world where the disconnect path
   may not complete.

Deliberately *not* on every expand toggle: that would rewrite the config file on every click.

### NFD picker

`NfdFolderPicker` replaces `TinyfdFolderPicker` as `RootPickerController.picker`'s default.

Build: `org.lwjgl:lwjgl-nfd:3.4.1` — version-matched to MC 26.2's LWJGL, which ships `lwjgl-tinyfd`
but **not** `lwjgl-nfd` — plus every natives classifier (windows/linux/macos × x64/arm64),
`include(...)`'d for jar-in-jar. Cost is roughly 1–2 MB of natives in the mod jar.

Three constraints, each documented at the call site:

- **`NFD_Init`, `NFD_PickFolder`, and `NFD_Quit` must run on the same thread.** On Windows, NFD's
  init performs the COM `CoInitializeEx` on the calling thread, so splitting the calls across
  threads breaks the dialog. All three live in one block.
- **macOS runs the dialog inline on the render thread.** NFD on macOS drives `NSOpenPanel`, which
  requires the AppKit main thread — and under `-XstartOnFirstThread` that thread *is* the render
  thread, so the existing "always use a worker thread" rule cannot hold there.
  `RootPickerController.runner`'s default becomes platform-aware: `Platform.get() == MACOSX` uses
  `Runnable::run`, every other platform keeps the worker thread. The game visibly freezes behind the
  modal on macOS; that is the accepted trade. `runner` remains an injectable seam, so `RootPickerSpec`
  is unaffected.
- **`FolderPicker.pick`'s `title` parameter becomes unused.** NFD's pick-folder API exposes only
  `defaultPath` and `parentWindow` — there is no title field. The parameter stays for the seam's
  shape, with a comment explaining why NFD ignores it, rather than reshaping the interface and its
  tests for one dead argument.

The exact LWJGL binding signature for `NFD_PickFolder` is verified against the resolved 3.4.1 jar at
implementation time; it has changed shape across LWJGL versions.

## Testing

- `ExplorerStateStoreSpec` (clientTest), modeled on `ModConfigSpec`: round-trip, absent file,
  malformed JSON, root mismatch discards the record, blank root skips persistence.
- Restore logic driven against a synthetic `FolderNode` tree — asserts that surviving paths land in
  `treeState.openNodes`, that missing paths are dropped, and that the restore is one-shot. No live
  connection required.
- `RootPickerSpec` continues to cover the picker flow through the existing `picker` / `runner` /
  `executor` / `sender` / `persist` seams.
- The NFD native itself is not unit-testable; it is verified by running the client and opening the
  dialog.

## Docs to update

- `docs/ui/dock-dialogs.md` — the "Native OS dialogs block" section names tinyfd and
  `RootPickerController` as the reference; update for NFD, the same-thread init rule, and the macOS
  render-thread exception.
- `docs/architecture/redstone-project.md` — the Explorer paragraph describing `ExplorerTreeState` /
  `ProjectTreeState` gains the session-restore flow.
- `docs/use-cases/redstone-project.md` — UC-MAN-09 describes the native folder picker; add or extend
  a use case for tree restore on rejoin.
- A new `docs/persistence/` article for `ExplorerStateStore`, registered in that folder's
  `INDEX.md`.
