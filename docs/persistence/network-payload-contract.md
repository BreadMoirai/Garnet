---
title: Editor C2S/S2C payload contract
tags: [networking, payloads, sync, authority, editor]
summary: The authority model behind editor/network/EditorPackets.kt — every client subpath is resolved through EditorRoot.resolveSubpath's path-traversal guard, per-player intent is tracked by EditorSession, and the server is still the only writer.
---

# Editor C2S/S2C payload contract

**This article used to document the recorder/runner block wire protocol** (`network/Packets.kt`,
`NetworkRegistry.kt`): every payload carried an `originPos` `BlockPos`, and the server resolved a
`SpecBlockEntity` at that position as the trust anchor. That protocol, the blocks, and the BE were
all deleted — there is no in-game recorder/runner surface anymore (see
[architecture/module-map.md](../architecture/module-map.md)).

The Explorer/redstone-project wire protocol (`editor/network/EditorPackets.kt`,
`editor/network/EditorNetworkRegistry.kt`, `EditorTreeHandlers.kt`, `EditorStructureHandlers.kt`,
`EditorFileOpsHandlers.kt`) is a genuine replacement worth documenting on its own terms
— it is a **different** authority model, not a renamed version of the old one. There is no
block-entity handle at all: the client addresses everything by **path** and by **player identity**.

## Invariant 1: server is the only writer

Unchanged in spirit from the old protocol. The client never writes to disk directly — every
mutating action (`CreateFolderC2S`, `RenamePathC2S`, `DuplicatePathC2S`, `MovePathC2S`,
`DeletePathC2S`, `NewStructureC2S`, `NewEditorSpecC2S`, `SaveStructureC2S`, `SaveNowC2S`, and the
two replay requests `UndoC2S`/`RedoC2S`) is a request the server validates and
performs, replying with either the new state (`EditorTreeSnapshotS2C`, `StructureResultS2C`,
`EditorFolderLoadedS2C`, `EditorSaveReportS2C`, `UndoStateS2C`) or `EditorErrorS2C(reason)`. `SaveStructureC2S` is
a force-commit through `StructureCommit`, the same engine that drives auto-save — there is no
`DiscardStructureC2S`; a placed structure auto-saves continuously, so there is nothing to discard
back to (recovery goes through `LocalHistoryStore` instead, see `docs/persistence/local-history.md`).

`StructureAutoSavedS2C(subpath, sizeX, sizeY, sizeZ, blockCount, savedAtMillis)` is the one
clientbound payload here that is **not** a reply to a specific request: `StructureCommit` broadcasts
it to every player on every successful commit (debounced auto-save or a forced `SaveStructureC2S`),
since a structure region is server-global and any player looking at it wants the update. The client
handler (`StructureInfoState.onAutoSaved`) renders every field —
`subpath`/`sizeX`/`sizeY`/`sizeZ`/`blockCount`/`savedAtMillis` — into the
[Structure Info panel](../ui/structure-info-panel.md).

## Invariant 2: every client-supplied path goes through `EditorRoot.resolveSubpath`

There is no `originPos` lookup anymore — the trust anchor is **path containment**, not a
block-entity cast. `EditorRoot.resolveSubpath(subpath)` (in `editor/explorer/data/EditorRoot.kt`):

```kotlin
fun resolveSubpath(subpath: String): Path? {
    val isAbs = try { Path.of(subpath).isAbsolute } catch (e: InvalidPathException) { return null }
    if (isAbs) return null
    val candidate = path.resolve(subpath).normalize()
    if (!candidate.exists()) return null
    val real = candidate.toRealPath()
    val rootReal = path.toRealPath()
    return if (real.startsWith(rootReal)) real else null
}
```

Every handler across `EditorTreeHandlers`/`EditorStructureHandlers`/`EditorFileOpsHandlers` that
takes a client-supplied subpath (`LoadEditorFolderC2S`,
`PlaceStructureC2S`, `SaveStructureC2S`, `RenamePathC2S`, `DuplicatePathC2S`, `DeletePathC2S`,
`MovePathC2S` — both its `subpath` and its `destFolderSubpath` —
`NewStructureC2S.parentSubpath`, `CreateFolderC2S.parentSubpath`) calls this before touching the
filesystem, and replies with `EditorErrorS2C("... not found or escapes root: ...")` on a `null`.
Three things this rejects: an absolute path, a `..`-relative escape, and a symlink that resolves
outside the root (the `toRealPath()` comparison happens *after* resolving symlinks, so a symlink
planted inside the root that points outside it is still caught). This is exercised directly by
`EditorRootTest` and end-to-end by `EditorFileOpsNetworkSpec`/`EditorStructureNetworkSpec` (a
recent gap-fill closed a rename/rekey correctness hole and a `.NBT`-vs-`.nbt` double-extension
bug in this same area — see the file-tree explorer commits).

**Undo/redo carries no client-supplied path at all.** `UndoC2S`/`RedoC2S` are empty singletons: the
client asks to undo, and the *server* decides what that means by replaying the top of that player's
own `EditorUndoStack`. There is nothing for `resolveSubpath` to check at request time — the
containment boundary is instead enforced at **replay** time, against the subpaths the server itself
recorded when it performed the original operation. `EditorUndoOps` puts each of those through
`EditorRoot.resolveSubpath` before touching the filesystem — or, for a destination that must *not*
exist yet (a restore target, a move-back target), resolves its parent that way and appends the final
segment — and refuses the undo when a path no longer resolves. A successful undo replies with
`EditorTreeSnapshotS2C` + `UndoStateS2C`; a refusal replies with `EditorErrorS2C` and leaves the
stack untouched. See [editor-undo-stack.md](editor-undo-stack.md).

**Consequence of dropping the BE anchor:** there is no "stale reference is a silent no-op" case
anymore, because there is no block that can be broken/replaced out from under a pending request.
A request either resolves against the current directory tree or gets an explicit `EditorErrorS2C`
— never a silent drop.

## Invariant 3: per-player intent lives in `EditorSession`, not on a block

`EditorSession` (`editor/explorer/data/EditorSession.kt`) is a `ConcurrentHashMap<UUID, EditorSession>`
tracking each player's `activeSubpath` — the folder actions like "New Spec" target. This replaces
the old model where the *block itself* (looked up by `originPos`) implicitly scoped every action.
Consequences:

- **No block-kind re-validation exists because there is no block kind.** The old protocol had to
  re-check `level.getBlockState(originPos).block is GarnetXBlock` because one BE type was shared
  across three block roles. The editor protocol has no shared-mutable-object hazard of that shape.
- **Session is in-memory only.** A server restart drops every player's `activeSubpath`; the next
  snapshot they receive carries `currentSubpath = null` until they select a folder again (see
  [use-cases/command.md](../use-cases/command.md) UC-CMD-04).
- **Cleared on disconnect.** `Garnet.onInitialize` registers `ServerPlayConnectionEvents.DISCONNECT`
  → `EditorSession.clear(player.uuid)`.

## Root resolution has a priority chain, not a single lookup

`EditorRootResolver.rootFor(server)` (`editor/world/EditorRootResolver.kt`): `EditorWorld.get(server)?.root` → `EditorServerContext.get(server)?.root`
→ `SharedSettings.projectRootPath` (if non-blank). This is a fallback chain, not authority
delegation — whichever resolves first is used for the entire request.

## Stream codec idioms used here

- Fixed records: `StreamCodec.composite(...)` — most payloads (`LoadEditorFolderC2S`,
  `RenamePathC2S`, `StructureResultS2C`, …).
- **Singleton payloads must send `INSTANCE`, not a fresh construction.** `ListEditorTreeC2S`,
  `UnloadEditorFolderC2S`, `SaveNowC2S`, `UndoC2S`, `RedoC2S` use `StreamCodec.unit(INSTANCE)`, which captures one
  specific object by identity and throws `IllegalStateException("Can't encode A, expected B")` if
  a caller sends a newly-constructed instance instead of the registered `INSTANCE`/singleton.
- Recursive tree codec: `FILE_TREE_STREAM_CODEC` (in `EditorPackets.kt`) hand-encodes a
  `FileTreeNode` tree with a per-node tag byte (`TAG_FOLDER` / `TAG_FILE`), recursing for folder
  children.
- Optional string: a leading `Boolean` flag before the string, used by
  `EditorTreeSnapshotS2C.currentSubpath` and by both fields of
  `UndoStateS2C(undoLabel, redoLabel)` — a null label there means "that button is disabled".

All payload types are registered in `EditorNetworkRegistry.register()`. If you add a payload, register
it in the right direction (`PayloadTypeRegistry.serverboundPlay()` vs `.clientboundPlay()`) —
Fabric silently drops unregistered types.
