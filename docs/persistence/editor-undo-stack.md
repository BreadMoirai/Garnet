---
title: Explorer undo/redo command stack
tags: [editor, undo, history, networking, persistence]
summary: Why the undo stack stores server-authored EditorUndoCommand records rather than the C2S packets, how a delete is made reversible by banking every file, and why a stale entry is refused rather than discarded.
---

# Explorer undo/redo command stack

Undo/redo over the Project Explorer's file operations lives in
`editor/undo/` — `EditorUndoCommand.kt` (what was done), `EditorUndoStack.kt` (per-player deques),
and `EditorUndoOps.kt` (how to invert or replay it). It is **server-authoritative and per-player**:
the client's two toolbar buttons send a bare `UndoC2S`/`RedoC2S` and receive a `UndoStateS2C`
carrying the labels to render; it never names a path and never decides what an undo means. See
[network-payload-contract.md](network-payload-contract.md) for how that fits the wider wire
contract, and [local-history.md](local-history.md) for the revision store a delete's reversibility
depends on.

## Why the stack does not store the C2S packets

The obvious design — keep the request that caused the change and invert it — does not survive
contact with two of the six mutating handlers:

- **`DuplicatePathC2S(subpath)` does not say what was created.** The copy's name is derived
  *server-side* by `EditorNames.duplicateName` against the destination's real siblings:
  `house.nbt` becomes `house copy.nbt`, or `house copy 2.nbt`, or `house copy 7.nbt`, depending on
  what was already there. Undoing a duplicate means deleting the copy, and the packet does not name
  it. Re-deriving the name at undo time would be a guess about what the directory looked like at
  the moment of the copy.
- **`DeletePathC2S(subpath)` carries neither the subtree's shape nor its contents.** Undoing a
  delete means recreating a whole tree of folders and files with their bytes. Nothing in the request
  describes either.

So each handler records what it *actually did*, as an `EditorUndoCommand`. That is also why the
type is a sealed interface of five server-authored records rather than a wrapper around
`CustomPacketPayload`, and why it is named `EditorUndoCommand` and not `EditorCommand` — the latter
is the brigadier command object in `editor/command/`.

## The five commands and their inverses

| Command | Recorded by | Undo | Redo |
|---|---|---|---|
| `CreateFolder(subpath, banked?)` | `handleCreateFolder` | `deleteSubtree` the folder | restore its `banked` delete |
| `CreateFile(subpath, kind, banked?)` | `handleNewStructure` (`STRUCTURE`), `handleNewSpec` (`SPEC`) | `deleteSubtree` the file | restore its `banked` delete |
| `Duplicate(createdSubpath, banked?)` | `handleDuplicate` | `deleteSubtree` the copy | restore its `banked` delete |
| `Relocate(oldSubpath, newSubpath, kind)` | `relocate`, for both `handleRename` (`RENAME`) and `handleMove` (`MOVE`) | `relocate` back | `relocate` forward again |
| `Delete(rootSubpath, manifest, banked)` | `handleDelete` | `restoreSubtree` | `deleteSubtree` again |

`RelocateKind` and `CreatedFileKind` exist for **messages and side effects, not identity**: a rename
and a move are the same filesystem operation, and the kind only decides whether the label reads
"rename to …" or "move to …" and whether an undone create has to re-place its parent folder in the
world. The `label` on each command is rendered verbatim by the client as "Undo &lt;label&gt;".

A `Delete` is the one command that carries real payload: a `manifest` of `ManifestEntry(relPath,
isFolder)` covering **every** node of the deleted subtree, and a `banked` list of `BankedFile`
pointing at `LocalHistoryStore` revisions of every file in it. Both are needed — a folder that
contained no files banks nothing, so the manifest is the only evidence it ever existed.

`BankedFile` carries `relPath` **and** `absolutePath` on purpose. `relPath` is where the restore
writes, resolved against the *current* project root; `absolutePath` is the file's path at delete
time and is only the `LocalHistoryStore` key. The two are separate because the root is swappable
("Open Folder…") between the delete and the undo, and a restore must land under the root in force
now, not the one in force then.

The three create-shaped commands carry a nullable `banked: Delete?` — see
[Redo of a create is a restore](#redo-of-a-create-is-a-restore-not-a-re-create) below.

## Undo reuses the primitives; it does not reimplement them

Every inverse goes through `EditorFileOpsHandlers`' primitives — `relocate`, `deleteSubtree`,
`restoreSubtree` — never through hand-rolled file IO. This is the single most important structural
decision in the feature, because those functions carry ordering rules that took several rounds of
review to get right and that a second implementation would silently get wrong:

- quiesce (`commitDirtyUnder`) **before** a relocate, or pending edits are stranded under a subpath
  nothing will ever commit again;
- move the file **before** any registry teardown, so a failed move is a true no-op;
- `unplaceStructure` **before** `clearBounds`, because `clearBounds` writes AIR through the
  setBlock mixin and a still-registered subpath would have its own erasure re-mark it dirty;
- carry `LocalHistoryStore` revisions across a move via `moveDescendantHistories`;
- repoint or clear `EditorSession` for the affected subtree.

`EditorUndoOps` therefore contains preconditions and bookkeeping only. The one thing it adds is
`record: Boolean` on `relocate`: an undo passes `record = false` so the inverse move does not push a
*second* `Relocate` entry for a move the player never performed — the stack is managed by
`undo()`/`redo()` themselves.

## Lifetime: per player, in memory, `EditorSession`-shaped

`EditorUndoStack` is a `ConcurrentHashMap<UUID, PlayerUndo>` deliberately shaped like
`EditorSession`: in memory only, and cleared from the same `ServerPlayConnectionEvents.DISCONNECT`
registration in `Garnet.onInitialize`. Nothing is persisted, for two reasons — a stack restored
after a restart would be almost entirely stale, and the content a delete needs to be reversible
lives in `LocalHistoryStore`, which *does* survive.

Unlike `EditorSession`, which replaces immutable values wholesale, this class mutates one shared
`PlayerUndo` per player in place, so every accessor synchronizes on that instance before touching
its deques. The invariant: **no deque of a given player is ever read or written outside that
instance's monitor.**

**`EditorUndoStack.clear` does NOT remove the player's map entry** — it empties both deques under
the existing instance's monitor and leaves the mapping in place. That looks like an oversight and
is not. Removing the entry would let a `push`/`pushRedo` that already obtained the (now
soon-to-be-orphaned) `PlayerUndo` reference — and is merely waiting on the monitor `clear` is about
to release — land its mutation on an object the map no longer points at, silently discarding the
write. Leaving the mapping means `of()` and every accessor agree on exactly one instance per player
for the process lifetime, so that interleaving does not exist to reason about. The cost is one
lingering object holding two empty deques per player UUID ever seen.

Depth is capped at `MAX_DEPTH = 50`, evicted from the bottom. `push` clears the redo deque (a new
action invalidates every branch that followed from the old state); `pushUndoWithoutClearingRedo`
exists precisely because a *replay* must not — clearing there would discard every redo entry above
the one just consumed.

## Staleness: refuse and keep, never discard

Per-player stacks sit over **shared** server state. Another player — or the filesystem — can
invalidate an entry while it waits. Every inverse therefore re-checks its preconditions at replay
time (the node is still where it was; the destination is still free; the delete's path is still
free) and, when they fail, **refuses the operation and leaves the entry on the stack**.

Discarding the entry instead would be worse in a specific way: the player presses Undo, is told
nothing about the conflict, and the *next* press undoes an older, unrelated action. Refusing means
the same press keeps working once the conflict is resolved, and the error names the reason.

This is also why undo carries no client-supplied path. The client cannot ask to undo a *particular*
thing; it asks to undo, and the server replays the top of its own stack against paths it recorded
itself. The `EditorRoot.resolveSubpath` containment boundary is still enforced — just at replay
time, on those recorded subpaths.

### Pop only when the inverse actually happened

The invariant every branch of `EditorUndoOps` upholds: **an entry moves between deques only when
the inverse really happened on disk.** A refusal from a precondition here, *or* from a primitive
that failed mid-flight, leaves both deques untouched. A stack that moved an entry for an operation
the filesystem refused would claim an undo that never occurred and destroy the player's only handle
on retrying it.

The mid-flight case is the subtle one. `moveBack`'s preconditions cover staleness, not IO: a lock, a
permission problem, or a destination whose parent stopped being a folder all fail *inside*
`relocate`, after every check has passed. `relocate` returns `Boolean` for exactly this caller —
the two handler call sites ignore it, because a handler has nothing left to do either way.

`relocate` also reports its own failures to the player, which is what `Inverted.Refused`'s
**`alreadyReported`** flag is for: without it, one failed relocate produces two error toasts for one
event. Do not "simplify" the flag away.

Two refusals are decided by counting rather than by an exception:

- A **total** restore failure (`restored == 0 && foldersCreated == 0` with failures) refuses and
  keeps the entry — the filesystem is exactly as it was. `foldersCreated` exists because an
  empty-folder subtree legitimately restores zero *files*, so `restored == 0` alone cannot mean
  "nothing happened".
- A **partial** restore is accepted and reported honestly, never rolled back. Deleting what was
  just recovered would be worse than an incomplete recovery.

## Redo of a create is a restore, not a re-create

Undoing a create means deleting what was created — and a create's content came from a create
handler, not from anything the command recorded, so it cannot be reconstructed on the way back.
The undo's own `deleteSubtree` therefore **banks** the node it is about to remove, and the resulting
`Delete` is stapled onto the command (`banked`) as it moves to the redo deque. Redo is then a
`restoreSubtree` of that bank.

`banked == null` means either "not undone yet" or "the removal could not be banked" — local history
is switched off, or a bank genuinely failed. In that case redo is unavailable and says so, and
`Inverted.Applied(null)` deliberately seats **nothing** on the redo deque: an entry that refuses on
every press would, since refusals never pop, permanently mask every redo beneath it.

A create is still undoable when it cannot be banked. The player asked for the created node to go
away and it did; only redo is lost.

## Restored files come back UNPLACED

`restoreSubtree` touches no `EditorDimRegistry` state. A restored `.nbt` exists on disk and appears
in the tree, but is not placed in the world until the player clicks it — placing an arbitrary
restored subtree would need a region assignment per `.nbt`, and navigating to a structure costs the
same either way.

The one deliberate exception is a **spec**: creating a `.spec.kts` re-places its parent folder, so
undoing (or redoing) that create re-places the folder too, via `EditorDimLifecycle.placeFolder`.
Otherwise the folder in the world would keep showing a spec whose file no longer exists. That
re-place is best-effort and swallowed on failure — the file operation it follows has already
happened, so reporting failure would tell the player nothing changed when the tree in fact did.

`replaceFolderOf` also sends the resulting `EditorFolderLoadedS2C`, exactly as `handleNewSpec` does
from the same report. That packet is not bookkeeping: `sendTree` refreshes the *tree*, not the
client's `loadedSpecIds` for a folder, so an inverse that only re-placed server-side left the client
listing a spec whose file was gone, with no error and no self-correction.

## Test coverage

`EditorUndoNetworkSpec` (`src/gametest/.../editor/`) covers the whole feature end to end: what each
handler records, the pre-delete banking of `.spec.kts` and of a freshly duplicated `.nbt` with no
history, the manifest shape (including the empty-string `relPath` for a single deleted file), the
unbankable and history-disabled delete paths, each command's undo and redo, the refusal cases
(moved underneath, path occupied again, nothing restorable, a relocate that fails mid-flight), and
that a redo does not discard the redo entries above it. Two tests pin the re-bank and the quiesce
specifically: a redo of a delete must bank the file as the player edited it *after* the undo (so the
next undo does not revert that edit), and a redo whose structure is dirty in `StructureAutoSave` must
commit before banking (so an in-world edit survives the round trip). `EditorUndoStackTest` and
`EditorUndoPacketsTest` (`src/test/`) cover the deque semantics and the payload codecs;
`ExplorerUiSpec` (`src/clientTest/`) covers the toolbar buttons' enablement from `UndoStateS2C`.

## A replay re-banks; it never re-seats a stale bank

Redoing a `Delete` calls `deleteSubtree` again, and that call produces its **own** `Delete` command
banking exactly what was on disk at that instant. That fresh command — not the original — is what
`redo()` seats on the undo deque. The original's bank predates the undo, so re-seating it would make
the next undo restore pre-undo bytes and silently revert anything the player changed in between.

This is why `reapply` returns an `Inverted` rather than a nullable refusal: it needs a success
channel that can carry a replacement command. `Inverted.Applied.redoable` therefore means "the
command to seat on the *opposite* deque" in both directions, and `null` there means "this happened
but cannot be replayed back" — an undone create whose removal could not be banked, or a re-delete
that could not be banked (`DeletedUnbankable` / `DeletedHistoryDisabled`). In both cases nothing is
seated, for the same reason: an entry that refuses on every press would, since refusals never pop,
permanently mask every entry beneath it.

## The pre-delete quiesce lives in `deleteSubtree`, not in a caller

`deleteSubtree` runs the best-effort `commitDirtyUnder` itself, before the banking walk. It has three
callers — `handleDelete`, `EditorUndoOps.removeCreated`, and the redo of a `Delete` — and the two
undo-side ones once reached it with no quiesce at all. The failure that made this a correctness rule
rather than tidiness: once the bytes have been read, a dirty structure's pending world edits are
invisible to the bank, and `deleteAndTearDown` then clears that dirty state — so the edits are gone
with no error, and a later undo restores pre-edit bytes.

The quiesce is best-effort *here specifically* (every other `commitDirtyUnder` caller aborts on
failure): blocking a delete because history could not be banked would make a structure with a broken
history directory undeletable from the editor, for a node the player is explicitly destroying.

## Known gaps

Neither of these loses data; both are client-side staleness or redundancy:

- **Refusals do not re-send the tree.** Both `undo` and `redo` return before `sendTree`, yet the
  commonest cause of a refusal is a client tree that disagrees with disk — exactly the case a
  re-send would fix. Conversely, a *successful* `Relocate` undo sends the tree and undo state
  **twice**, because `relocate` already sends both before `undo()` sends them again.
- **`restoreSubtree` is `public` only for the test source set.** It is a primitive on the same
  footing as `deleteSubtree`, which is `internal`, but the `gametest` source set has no Kotlin
  friend-path to `main` (there is no `associateWith` anywhere in the build), so an `internal`
  function is not callable from a spec. A build-hygiene follow-up should add friend-paths and narrow
  `restoreSubtree` back to `internal`.
