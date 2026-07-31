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
`editor/network/EditorNetworking.kt`) is a genuine replacement worth documenting on its own terms
— it is a **different** authority model, not a renamed version of the old one. There is no
block-entity handle at all: the client addresses everything by **path** and by **player identity**.

## Invariant 1: server is the only writer

Unchanged in spirit from the old protocol. The client never writes to disk directly — every
mutating action (`CreateFolderC2S`, `RenamePathC2S`, `NewStructureC2S`, `NewEditorSpecC2S`,
`SaveStructureC2S`, `DiscardStructureC2S`, `SaveNowC2S`) is a request the server validates and
performs, replying with either the new state (`EditorTreeSnapshotS2C`, `StructureResultS2C`,
`EditorFolderLoadedS2C`, `EditorSaveReportS2C`) or `EditorErrorS2C(reason)`.

## Invariant 2: every client-supplied path goes through `EditorRoot.resolveSubpath`

There is no `originPos` lookup anymore — the trust anchor is **path containment**, not a
block-entity cast. `EditorRoot.resolveSubpath(subpath)` (in `editor/data/EditorRoot.kt`):

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

Every handler in `EditorNetworking` that takes a client-supplied subpath (`LoadEditorFolderC2S`,
`PlaceStructureC2S`, `SaveStructureC2S`, `DiscardStructureC2S`, `RenamePathC2S`,
`NewStructureC2S.parentSubpath`, `CreateFolderC2S.parentSubpath`) calls this before touching the
filesystem, and replies with `EditorErrorS2C("... not found or escapes root: ...")` on a `null`.
Three things this rejects: an absolute path, a `..`-relative escape, and a symlink that resolves
outside the root (the `toRealPath()` comparison happens *after* resolving symlinks, so a symlink
planted inside the root that points outside it is still caught). This is exercised directly by
`EditorRootTest` and end-to-end by `EditorFileOpsNetworkSpec`/`EditorStructureNetworkSpec` (a
recent gap-fill closed a rename/rekey correctness hole and a `.NBT`-vs-`.nbt` double-extension
bug in this same area — see the file-tree explorer commits).

**Consequence of dropping the BE anchor:** there is no "stale reference is a silent no-op" case
anymore, because there is no block that can be broken/replaced out from under a pending request.
A request either resolves against the current directory tree or gets an explicit `EditorErrorS2C`
— never a silent drop.

## Invariant 3: per-player intent lives in `EditorSession`, not on a block

`EditorSession` (`editor/data/EditorSession.kt`) is a `ConcurrentHashMap<UUID, EditorSession>`
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

`EditorNetworking.rootFor(server)`: `EditorWorld.get(server)?.root` → `EditorServerContext.get(server)?.root`
→ `SharedSettings.projectRootPath` (if non-blank). This is a fallback chain, not authority
delegation — whichever resolves first is used for the entire request.

## Stream codec idioms used here

- Fixed records: `StreamCodec.composite(...)` — most payloads (`LoadEditorFolderC2S`,
  `RenamePathC2S`, `StructureResultS2C`, …).
- **Singleton payloads must send `INSTANCE`, not a fresh construction.** `ListEditorTreeC2S`,
  `UnloadEditorFolderC2S`, `SaveNowC2S` use `StreamCodec.unit(INSTANCE)`, which captures one
  specific object by identity and throws `IllegalStateException("Can't encode A, expected B")` if
  a caller sends a newly-constructed instance instead of the registered `INSTANCE`/singleton.
- Recursive tree codec: `FILE_TREE_STREAM_CODEC` (in `EditorPackets.kt`) hand-encodes a
  `FileTreeNode` tree with a per-node tag byte (`TAG_FOLDER` / `TAG_FILE`), recursing for folder
  children.
- Optional string: a leading `Boolean` flag before the string, used by `EditorTreeSnapshotS2C.currentSubpath`.

All payload types are registered in `EditorNetworking.register()`. If you add a payload, register
it in the right direction (`PayloadTypeRegistry.serverboundPlay()` vs `.clientboundPlay()`) —
Fabric silently drops unregistered types.
