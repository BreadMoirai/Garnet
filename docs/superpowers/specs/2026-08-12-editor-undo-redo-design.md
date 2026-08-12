# Undo/Redo for Project Explorer File Operations

**Date:** 2026-08-12
**Status:** Design approved, pending implementation

## Problem

The Explorer can now create folders, create specs, create structures, rename, duplicate, delete, and
move — seven mutating operations, none of them reversible. A misclick on Delete destroys a folder
tree; the confirmation dialog is the only guard, and it is a guard against the click, not against
the decision.

Block-level edits inside a structure region already have a recovery route: `LocalHistoryStore` banks
a revision on every `StructureCommit`, so an edit can be rolled back file-by-file. Nothing
equivalent exists at the *file-tree* level, and the file tree is where the destructive operations
live.

The obvious cheap implementation — keep a stack of the C2S packets and derive an inverse packet —
does not hold up:

- `DuplicatePathC2S(subpath)` does not record what was created. `EditorNames.duplicateName` picks
  the name server-side, so the packet cannot tell an inverse whether to delete `foo copy.nbt` or
  `foo copy 2.nbt`.
- `DeletePathC2S(subpath)` carries no subtree manifest and no content, so its inverse has nothing to
  restore from.

Both fixes amount to recording what the server actually did rather than what the client asked for.
That is the design below.

## Scope

Undo/redo for the seven mutating Explorer file operations, per player, in memory, with toolbar
buttons. They collapse to five command types, because rename and move share one inverse and the two
create-a-file operations share another.

**In scope:** `CreateFolderC2S`, `NewStructureC2S`, `NewEditorSpecC2S`, `DuplicatePathC2S`,
`RenamePathC2S`, `MovePathC2S`, `DeletePathC2S`.

**Out of scope, deliberately:**

- **Block edits in the structure region.** Covered by `LocalHistoryStore` revisions; adding them to
  this stack would need world-delta capture off the `setBlock` mixin and a definition of an edit
  "stroke", and it would put two unrelated capture mechanisms on one timeline.
- **`PlaceStructureC2S` / `SaveStructureC2S` / `SaveNowC2S` / `SetEditorRootC2S`.** Not file-tree
  mutations. A save is already recoverable through revisions; a root swap is a session action, not
  an edit.
- **Keyboard bindings.** Toolbar buttons only for this pass. Ctrl+Z would have to negotiate with the
  Jewel tree's `onPreviewKeyEvent` handler and the open `InlineNameField` (see
  `EditAwareKeyActions`), which is a separate problem from the stack itself.
- **Persisting the stack across a disconnect or restart.**

## Design

### 1. `EditorUndoCommand` — what actually happened

A sealed interface in a new `editor/undo/` package. One subtype per operation, each a plain data
record of the *executed* operation, plus a `label` used for the toolbar tooltip.

```kotlin
sealed interface EditorUndoCommand {
    val label: String

    data class CreateFolder(val subpath: String) : EditorUndoCommand
    data class CreateFile(val subpath: String, val kind: FileKind) : EditorUndoCommand
    data class Duplicate(val createdSubpath: String) : EditorUndoCommand
    data class Relocate(val oldSubpath: String, val newSubpath: String, val op: RelocateKind) : EditorUndoCommand
    data class Delete(
        val rootSubpath: String,
        val manifest: List<ManifestEntry>,
        val banked: List<BankedFile>,
    ) : EditorUndoCommand
}

data class ManifestEntry(val relPath: String, val isFolder: Boolean)
data class BankedFile(val absolutePath: Path, val revision: Revision)
```

`Duplicate` stores the server-derived created name, not the source — that is the whole point.
`Relocate` covers both rename and move, mirroring the fact that `EditorFileOpsHandlers.relocate`
already covers both; `op` exists only so error and status messages say "rename" or "move" correctly.

`Revision` is the existing top-level `com.breadmoirai.garnet.history.Revision`, not a nested type.

`BankedFile.absolutePath` is absolute, not root-relative, because that is what `LocalHistoryStore`
actually keys on (`keyOf` hashes the normalized absolute path), and because a deleted file's history
directory deliberately outlives the file.

### 2. `EditorUndoStack` — per-player, in memory

Mirrors `EditorSession`'s shape and lifetime exactly:

```kotlin
object EditorUndoStack {
    private val byPlayer = ConcurrentHashMap<UUID, PlayerUndo>()

    fun push(uuid: UUID, command: EditorUndoCommand)   // clears the redo deque
    fun peekUndo(uuid: UUID): EditorUndoCommand?
    fun peekRedo(uuid: UUID): EditorUndoCommand?
    fun popUndo(uuid: UUID): EditorUndoCommand?
    fun popRedo(uuid: UUID): EditorUndoCommand?
    fun pushRedo(uuid: UUID, command: EditorUndoCommand)
    fun clear(uuid: UUID)
}
```

`PlayerUndo` holds two `ArrayDeque<EditorUndoCommand>`. The undo deque is capped at 50, oldest evicted.
`clear` is called from the same `ServerPlayConnectionEvents.DISCONNECT` registration in
`Garnet.onInitialize` that already clears `EditorSession`.

Per-player stacks over shared server state means an entry can go stale — see §5.

### 3. Extracted primitives in `EditorFileOpsHandlers`

The hard part of this feature is not the stack; it is the ordering hazards documented at length in
`EditorFileOpsHandlers` (quiesce before relocating, fallible IO before destructive teardown,
`unplaceStructure` before `clearBounds`, `rekeyForRename`, `repointSession`, history-follows-file).
An undo module that reimplemented any of them would reintroduce exactly the bugs those comments
record.

So undo calls the same code. Three primitives, with the handlers keeping validation and packet
replies:

- **`relocate`** — already exists as a private function with precisely the right signature. Becomes
  `internal`.
- **`deleteSubtree(server, player, subpath): DeleteOutcome`** — the bank-manifest + unlink + unplace +
  `clearBounds` + `autoSave.clear` + `clearBackoff` + `clearSessionUnder` sequence currently inline
  in `handleDelete`. `DeleteOutcome` is either `Failed(reason)`, `Deleted(command)` carrying the
  manifest and banked-file list ready to push, or `DeletedUnbankable(reason)` — deleted, but banking
  failed, so the caller must push nothing and tell the player (see §4).
- **`restoreSubtree(server, player, command): RestoreReport`** — new. Creates directories in depth
  order, then writes each banked file back.

### 4. Delete becomes a three-phase operation

`handleDelete` today is quiesce → unlink → teardown. It becomes quiesce → **bank** → unlink →
teardown.

The bank phase walks the doomed subtree depth-first, building the manifest, and for every **file**
unconditionally writes a `pre-delete` revision of its current bytes. Unconditionally, rather than
checking whether the newest existing revision already matches the file: the check would be a guess
about content equality, and always banking closes two real gaps at once —

- `.spec.kts` files have never been in `LocalHistoryStore` at all (only `StructureCommit` writes
  revisions, and only for `.nbt`), so a deleted spec was unrecoverable.
- A `.nbt` can have zero revisions. `handleDuplicate` deliberately gives a copy no history, so
  deleting a freshly duplicated structure had nothing to restore from.

The existing best-effort `commitDirtyUnder` stays exactly as it is — logged, never aborting — and
still runs first, so a dirty structure's pending edits are in the file by the time it is banked.

**If banking fails for any file, the delete still proceeds** (matching today's behaviour, and the
player explicitly asked to destroy this node) **but no command is pushed**, and the player is told
the delete is not undoable. A partially restorable undo entry is worse than no entry.

### 5. Undo and redo

Recording is one line on each handler's success path, after `sendTree`. Nothing is pushed on a
failure path, so a refused operation never enters the stack.

`EditorUndoOps.undo(server, player)` peeks the top command, checks its precondition, and refuses
without touching the filesystem if it does not hold:

| Command | Precondition | Inverse |
|---|---|---|
| `CreateFolder`, `CreateFile`, `Duplicate` | node still exists at the recorded subpath | `deleteSubtree` (which banks it, making redo symmetric) |
| `Relocate` | node still at `newSubpath`; `oldSubpath` free | `relocate` back, with a "renamed/moved back to …" status line |
| `Delete` | `rootSubpath` does not exist | `restoreSubtree` |

`CreateFile(kind = SPEC)`'s inverse additionally re-runs `EditorDimLifecycle.placeFolder` for the
parent folder, because `handleNewSpec` re-places the folder on creation; deleting only the file
would leave the world showing a spec that no longer exists.

Redo re-applies the original operation through the same primitives, with the mirrored precondition.
Any push of a new command clears the redo deque.

**A restored structure comes back unplaced.** `restoreSubtree` writes files and sends a tree; it does
not enter `EditorDimRegistry`. Re-placing an arbitrary restored subtree would need region assignment
for every `.nbt` in it, and navigating to a structure costs a player the same number of clicks
whether or not it is placed. Undoing a *rename* of a placed structure does keep it placed, because
that path goes through `relocate`, which already handles the registry.

### 6. Wire protocol

Three new payloads in `EditorPackets.kt`, registered in `EditorNetworkRegistry.register()`:

- `UndoC2S`, `RedoC2S` — no fields, so both use `StreamCodec.unit(INSTANCE)` and **must** be sent as
  the registered `INSTANCE`, never a fresh construction (see
  `docs/persistence/network-payload-contract.md`, "Stream codec idioms").
- `UndoStateS2C(undoLabel: String?, redoLabel: String?)` — each an optional string encoded as a
  leading boolean flag, the idiom `EditorTreeSnapshotS2C.currentSubpath` already uses. A null label
  means that button is disabled.

`UndoStateS2C` is sent to the acting player after every mutating operation and after every
undo/redo. It is per-player state, so unlike `StructureAutoSavedS2C` it is never broadcast.

Undo does not need a new authority story: every path in a command was already validated through
`EditorRoot.resolveSubpath` when the original operation ran, and the inverse re-resolves through the
same handlers' primitives. No client-supplied path is involved in an undo at all — the client sends
a bare "undo" and the server decides what that means.

### 7. `LocalHistoryStore` extension

`writeRevision` is `CompoundTag`-typed (`NbtIo.writeCompressed`). Rather than adding a second blob
format, raw bytes are wrapped in a `CompoundTag`:

```kotlin
fun writeRawRevision(file: Path, bytes: ByteArray, reason: String, ...): Revision?
fun readRawBytes(file: Path, revision: Revision): ByteArray?
const val REASON_PRE_DELETE = "pre-delete"
```

The wrapper tag carries the bytes plus a marker field distinguishing a raw revision from a structure
revision, so `readTag` on a raw revision is detectable rather than silently returning a nonsense
structure. `index.json`, pruning, `moveHistory`, and `moveDescendantHistories` all keep working
untouched, which is the reason for wrapping rather than forking the format.

`sizeX`/`sizeY`/`sizeZ`/`blockCount` are zero for a raw revision. Any future history UI must treat a
zero-size revision as "not a structure snapshot" rather than "an empty structure".

### 8. Client

Two `IconButton`s in `ExplorerToolbar`, beside the existing refresh and collapse-all pair, sending
`UndoC2S.INSTANCE` / `RedoC2S.INSTANCE`. Disabled when the corresponding label is null. Tooltips read
the label directly: `"Undo delete of redstone/clock.nbt"`.

A client-side `UndoState` object holds the two labels, updated from `UndoStateS2C` in
`EditorClientNetworking`. `ExplorerLifecycle` resets it on disconnect, so a rejoin does not show an
enabled button backed by a server stack that was cleared.

## Error handling

Three failure classes, treated differently on purpose:

1. **Stale precondition.** `EditorErrorS2C("can't undo rename of 'foo' — it moved since")`. The entry
   stays on the stack and nothing on disk is touched. This is a normal outcome in a shared session,
   not a bug — leaving the entry means the player can retry after resolving the conflict, rather than
   silently skipping to an older action they did not ask for.
2. **IO failure mid-inverse.** Logged under `[project/undo]`, matching the existing `[project/<op>]`
   convention, and reported via `EditorErrorS2C`. `restoreSubtree` cannot roll back cleanly, so it
   restores what it can and reports honestly: `"restored 4 of 6 files"`.
3. **Missing revision blob** at restore time (pruned, or the history directory was removed). Same
   partial report. The precondition check cannot rule this out without reading every blob, which is
   why the report exists.

## Testing

*Unit — `src/test/kotlin/.../editor/undo/EditorUndoStackTest.kt`*: push clears the redo deque,
cap-50 eviction drops the oldest entry, per-UUID isolation, `clear` empties both deques. Pure data
structure, no server needed.

*Gametest — `src/gametest/kotlin/.../editor/EditorUndoNetworkSpec.kt`*, alongside
`EditorFileOpsNetworkSpec`:

- Round-trip each of the five command types: operation → undo → tree matches the pre-operation state
  → redo → matches the post-operation state.
- Undo of a folder delete restores nested folders *and* both a `.nbt` and a `.spec.kts` — the gap
  that motivated bank-on-delete.
- Undo of a delete of a **freshly duplicated** `.nbt`, which has no prior history at all.
- Stale precondition: rename, mutate underneath, undo → `EditorErrorS2C` and the entry survives.
- Undo of a rename of a *placed* structure keeps it placed and correctly re-keyed in
  `EditorDimRegistry`.

*Extend `LocalHistoryStoreSpec`*: raw-byte revisions round-trip, and coexist with typed `.nbt`
revisions in a single `index.json`.

## Documentation

- New article `docs/persistence/editor-undo-stack.md` — the command model, why storing packets does
  not work, and the per-player/in-memory lifetime. Registered in `docs/persistence/INDEX.md`.
- Update `docs/persistence/network-payload-contract.md` for the three new payloads and the note that
  undo carries no client-supplied path.
- Update `docs/persistence/local-history.md` for raw revisions, `REASON_PRE_DELETE`, and the
  zero-size caveat. The article's current statement that a delete retains history becomes stronger:
  a delete now *adds* to history.
