---
title: Local history for standalone structures
tags: [storage, history, autosave, structures, persistence]
summary: How auto-saved .nbt structures record revisions under <instance>/.garnet/local-history, why the key is the file's absolute path, how pruning works, how a delete banks a pre-delete revision of every file type, why a deleted structure keeps its history (so a file recreated at the same path inherits it), and how a Local History restore banks itself through StructureCommit like any other edit.
---

# Local history for standalone structures

`com.breadmoirai.garnet.editor.history.data.LocalHistoryStore` is a JetBrains-style local history for
standalone `.nbt` structures: every time `StructureCommit` is about to rewrite a structure's
`.nbt` file, it first writes a **revision** — a snapshot of the NEWLY CAPTURED content that is
about to become the live `.nbt`, not the content being replaced — so an edit can be rolled back
even though there is no "Discard" action and no in-memory undo buffer. See
[architecture/redstone-project.md#standalone-structure-files](../architecture/redstone-project.md#standalone-structure-files)
for how commits are triggered, and [spec-on-disk-format.md](spec-on-disk-format.md) for where the
history directory sits relative to the rest of a world's on-disk layout.

**Rollback implication (read this before trusting `revisions.last()`):** the model is a `placed`
baseline plus one POST-commit revision per commit — each revision is what the `.nbt` became, not
what it was before. So to undo the most recent edit, restore `revisions[size - 2]` (the revision
*before* the latest one), not `revisions.last()` — the last revision already matches what's
currently on disk.

**There is a UI for this now.** The [Local History panel](../ui/local-history-panel.md), which shares
the LEFT dock region with the Project Explorer (its own icon in the tool-window stripe) and is reached
from a `.nbt` node's right-click → *Local History*, lists
these revisions and restores one into the world and the `.nbt` — undoably. Rollback no longer means
copying a blob out of `<instance>/.garnet/local-history/…` with an external NBT tool. The panel
renders the newest revision but makes it inert, and the server refuses it independently, for exactly
the reason above.

## Layout

```
<instance>/.garnet/local-history/<stem>-<hash8>/<epochMillis>-<seq>.nbt
<instance>/.garnet/local-history/<stem>-<hash8>/index.json
```

`<instance>` is `FabricLoader.getInstance().gameDir` by default, or `SharedSettings.localHistoryDir`
when that setting is non-blank. Each structure gets its own directory named `<stem>-<hash8>`: the
`<stem>` is the structure's filename without extension, kept only so the directory is browsable by
hand, and `<hash8>` is the first 4 bytes of a SHA-256 digest of the structure's normalized absolute
path — that hash, not the stem, is what actually identifies the directory. Two structures that
happen to share a filename in different folders get different directories because their hashes
differ.

Inside a structure's directory, each revision is one compressed-NBT blob named
`<epochMillis>-<seq>.nbt` (the `-<seq>` suffix only appears when two writes land in the same
millisecond) plus one shared `index.json` listing every revision's filename, timestamp, size
(`sizeX`/`sizeY`/`sizeZ`), `blockCount`, and a `reason` tag — one of:

| `reason` | Written by | Meaning |
|---|---|---|
| `"placed"` | `EditorStructureHandlers` | The baseline banked the first time a structure is placed |
| `"autosave"` | `StructureCommit` | A debounced commit's newly captured content |
| `"manual"` | `StructureCommit` | A forced `SaveStructureC2S` commit's newly captured content |
| `"external"` | `StructureCommit` | Content found on disk that didn't match the newest banked revision — edited outside the editor between sessions; see "Out-of-band edits are banked too" below |
| `"pre-delete"` | `EditorFileOpsHandlers.deleteSubtree` | A snapshot taken immediately before a delete unlinks the file, so the delete is undoable; see "A delete adds to history" below |
| `"restore"` | `StructureCommit`, driven by `StructureRestoreOps` | The content a Local History restore put back — banked like any other commit, because a restore *is* a commit of re-placed world content; see "The writers" below |

`index.json` is rewritten crash-safely — to a same-directory temp file, then an atomic move over
the target, the same pattern `StructurePersistence.writeStructureAtomic` uses for the `.nbt` itself.
A plain truncate-in-place write would let a crash, power loss, or full disk leave a half-written
index, and `readIndex` can only degrade that to "no history" — silently hiding every revision whose
blob is still sitting right there on disk. `index.json`
also records the absolute path it was keyed from, so a hand-inspection of
an opaque hash directory can identify which structure it belongs to and a hash collision would be
noticeable rather than silently interleaving two structures' revisions. `LocalHistoryStore` is the
sole reader/writer of this layout; nothing else touches these files directly.

## Why the key is the file's own path, not the project root

The single most non-obvious decision in this subsystem: history is keyed off the structure file's
**own absolute path**, never off `<project root> + subpath`. The editor's root is swappable —
"Open Folder…" repoints it to any directory at any time — so keying by root-relative subpath would
fork one structure's history the instant a user opened its *parent* directory as the new root
instead of the directory they'd been using. The file's absolute path doesn't move just because the
editor's notion of "root" does, so keying off it keeps one structure's history contiguous across
root changes. The cost is that moving or renaming the `.nbt` file itself changes the key (the hash
is a function of the path), which is why renames must explicitly move history — see below.

## Windows path lowercasing

Before hashing, `LocalHistoryStore.normalizePath` lowercases the absolute path when running on
Windows (detected via `os.name`), because Windows filesystems are case-insensitive: `Clock.nbt` and
`clock.nbt` name the same file on disk and must resolve to the same history directory, not two
independent ones. On other platforms the path is hashed as-is, since case is significant there.

## Pruning

Pruning applies two policies, age first then count, both driven by `SharedSettings`:

- Revisions older than `localHistoryDays` (default 5) are dropped.
- Of what survives the age cutoff, only the newest `localHistoryMaxRevisions` (default 100) are
  kept.

Dropped revisions have their blobs deleted from disk — pruning is destructive, not a soft
tombstone. Those deletions happen **after** the rewritten `index.json` has landed, never before:
an index that still names a blob which is already gone degrades that revision to unreadable,
whereas a blob nothing references is merely wasted disk the next prune cleans up. Of the two ways
to be inconsistent mid-prune, only one loses data, so the ordering is chosen to fail into the
other. `writeRevision(..., prune = true)` (the default) prunes immediately after a successful
write. `StructureCommit` instead passes `prune = false` and calls the standalone `prune(file)`
entry point itself, but only *after* the `.nbt` rewrite that revision was guarding has actually
succeeded — see "Revision-before-rewrite ordering" below for why that separation matters. History
recording as a whole is gated by `localHistoryEnabled` (default true); when disabled,
`writeRevision` is a no-op that returns `null` and no blobs or index entries are written.

## Revision-before-rewrite ordering, and why `prune` is deferred

`StructureCommit` always writes a revision capturing the NEWLY CAPTURED world state before it
overwrites the `.nbt` file with that same content. This guarantees the new content is durably
recorded before it becomes the live file, so a `.nbt` write that fails partway through can't lose
it — the pre-edit content itself lives in the *previous* revision (or the `placed` baseline),
already banked by an earlier commit.

## Out-of-band edits are banked too

If a structure's `.nbt` was changed *outside* the editor between commits — an external NBT tool, a
`git checkout`, a restore-from-backup — the revision-before-rewrite ordering above alone would lose
that content with no recovery point: the next commit would capture the world's content, overwrite
the `.nbt`, and the out-of-band content would never have been banked anywhere. `StructureCommit`
guards against this: before writing the new revision, it reads what's currently on disk and, if it
doesn't match the newest existing revision's content (`structuresDiffer`), banks it first as a
`REASON_EXTERNAL` revision. This only fires when there's a genuine mismatch — the common case
(disk already matches the newest revision, because the previous commit wrote both) is a no-op.

**The common case doesn't pay for the check.** Proving disk matches the newest revision means
gzip-decompressing that revision's blob and normalizing both tags — on a ~1s debounce over a large
structure, real main-thread work on every single commit. So `StructureCommit` keeps a per-subpath
fingerprint of what its last successful commit left behind: the `.nbt`'s size and mtime, plus which
revision that content matches. If the file is still exactly as we left it and the newest revision is
unchanged, disk *is* that revision and the comparison is skipped entirely; the steady state does no
extra IO at all. Any genuine out-of-band write changes size or mtime and falls through to the full
comparison, as does a file this process has not committed yet (no fingerprint). The fingerprint
includes the file's **absolute path**, not just the subpath — subpaths are root-relative and the
root is swappable, so this is what stops a stale entry from ever matching a different file after an
"Open Folder…". The only way past the fast path is an external write within the same filesystem
timestamp tick that also preserves the exact byte length, whose consequence is one un-banked
external edit — the same outcome as for a file with no fingerprint yet.

Because that revision is written speculatively — before the outcome of the rewrite it's guarding is
known — it must not be pruned yet. If the `.nbt` write then fails, `LocalHistoryStore.discardRevision`
rolls the speculative write back out of the index and deletes its blob, as if it had never happened.
If pruning had already run unconditionally at write time, a structure stuck retrying a failing
commit would permanently delete one genuine *older* revision per failed attempt whenever the
revision count was already at the cap — the failed attempt's own revision doesn't survive either
way, but a real, unrelated older revision would be destroyed for no reason. Deferring `prune` to
after a confirmed-successful rewrite avoids that.

## Raw revisions: banking a file that is not a structure

`writeRawRevision(file, bytes, reason)` banks a file whose content is not a structure at all — a
`.spec.kts`, or a `.nbt` that fails to parse as NBT. `readRawBytes(file, revision)` reads one back.
Both exist for the delete path (see below), which must be able to restore *every* file type, not
just structures.

**A raw revision is a normal revision whose blob is a wrapper `CompoundTag`** carrying two keys: a
`garnetRaw` boolean marker and a `garnetBytes` byte array holding the file's bytes verbatim. It goes
through `writeRevision` like any other, so `index.json`, `prune`, `moveHistory` and
`moveDescendantHistories` all keep working unchanged for both kinds — that is the whole reason for
wrapping rather than forking the blob format.

**The zero-size caveat.** A raw revision records `sizeX`/`sizeY`/`sizeZ` and `blockCount` as `0`,
because there is no structure to describe. A consumer must read a zero-size revision as **"not a
structure snapshot"**, never as "an empty structure". Worse, **size alone cannot tell the two apart**:
a gzipped `.nbt` that parses as NBT but is not a structure template is banked through the *typed*
path with `StructureTemplate` sizes of `0` as well. **Only the marker distinguishes them** — which
is what `readRawBytes` returning `null` means, and why `restoreSubtree` uses that null (not the
size) to choose between `writeBytes` and `NbtIo.writeCompressed`. Writing a wrapper tag over a real
`.nbt`, or NBT-writing a wrapper as if it were a template, would corrupt the restored file.

## A delete adds to history

Deleting a file does not touch its history directory — and, since the Explorer's undo feature, a
delete also **writes to it**: `EditorFileOpsHandlers.deleteSubtree` walks the doomed subtree and
banks *every* file in it, unconditionally, as a `REASON_PRE_DELETE` revision, before anything is
unlinked. This is what makes an Explorer delete undoable; see
[editor-undo-stack.md](editor-undo-stack.md).

Three consequences worth knowing:

- **Every file type is banked, not just `.nbt`.** A `.spec.kts` was never in this store at all
  before; it is banked via `writeRawRevision`. A `.nbt` that parses goes through the typed
  `writeRevision` path so its revision carries real size metadata; one that does not parse falls
  back to raw bytes rather than being lost — the goal here is restorability, not a well-formed
  structure record.
- **Banking is unconditional, not "only if it looks stale".** An equality check would be a guess
  about content, and unconditional banking is what closes the two real gaps: `.spec.kts` files were
  never banked, and a freshly duplicated `.nbt` has no history by design.
- **Banking happens before the unlink**, which is the only ordering that works: once the bytes are
  gone there is nothing left to read. If any file cannot be banked, the delete still proceeds, but
  it is reported as not undoable — a partially restorable entry is worse than none.

Retaining the history is intentional for the same reason: recovering a structure a user deleted
(accidentally or not) is exactly what local history exists for, and disappearing history the moment
the file disappears would defeat that. Renaming or moving a
structure, by contrast, does move its history — `LocalHistoryStore.moveHistory` re-keys every
revision under the destination path's hash, merging into whatever history the destination already
had rather than overwriting it, and if any individual revision fails to move, that revision (and
only that one) is left behind at the source rather than the whole move being treated as
all-or-nothing.

The Explorer's `Delete` action (`EditorFileOpsHandlers.handleDelete`, UC-MAN-10.j) leans on this
entirely: **history is the only recovery route for a delete** — there is no trash folder, and the
Explorer's Undo restores from exactly these revisions. That is also why a delete quiesces before
unlinking, committing whatever was still only in the world so the pre-delete bank captures it. That
quiesce lives inside `deleteSubtree` rather than in `handleDelete`, so it holds on the undo/redo
paths that reuse the primitive too — a redo of a delete reaches it with no handler above it, and
skipping it there would bank stale bytes and then clear the dirty state that was the only record of
the difference. That commit is best-effort, though: if it fails, the delete proceeds anyway rather
than leaving a structure with a broken history directory undeletable.

**Consequence: a file later created at the same path inherits the deleted file's revisions.** Keys
are a hash of the absolute path, and nothing ties a revision to the file's identity beyond that. A
new `redstone/clock.nbt` created where an old one was deleted opens with the old one's history. That
is a usable undelete affordance — recreate the name, roll back — but it is surprising if you expect
a new file to start clean, and it is the reason a "delete" here is not the same as "unrecoverable".

## `blockCount` is `0` on every revision except `autosave`, `manual`, and `restore`

When a structure is placed into the world, `EditorStructureHandlers` writes an initial revision tagged
`REASON_PLACED` with `blockCount = 0`. This isn't a bug: block count is only knowable by scanning
the placed volume, and at the moment of placement nothing has been scanned yet — the structure is
being written *into* the world from its `.nbt`, not captured *out of* it. `StructureCommit` writes
`blockCount = 0` for the same reason on a `REASON_EXTERNAL` revision (see "Out-of-band edits are
banked too" above): that content comes from reading the on-disk `.nbt` tag directly, not from
scanning a placed volume, so no block count is available either. `REASON_PRE_DELETE` revisions are
`blockCount = 0` too, for both of their paths: the raw one has no structure at all, and even the
typed one is built by `StructureTemplate.load`ing the on-disk tag rather than scanning the world.
Only `REASON_AUTOSAVE`, `REASON_MANUAL` and `REASON_RESTORE` revisions — all three written by
`StructureCommit` — carry a real `blockCount`, because those are written from a `CapturedStructure`
produced by scanning the world. `restore` belongs on that list precisely *because* the restore path
re-places the revision's tag into the world and then commits it like any other edit, rather than
writing the `.nbt` directly: it goes through the same scan, so it gets the same real count.

Note the asymmetry with `sizeX`/`sizeY`/`sizeZ`: a *typed* `pre-delete` revision does carry real
sizes (they come from the loaded template), while a *raw* one is zero on all four fields. See
"Raw revisions" above for why size cannot be used to tell those two apart.

## The writers

Three, and only three:

- `com.breadmoirai.garnet.editor.structure.ops.StructureCommit` writes every `autosave`, `manual`,
  `external`, and `restore` revision. There is no separate "manual snapshot" feature — a `manual`
  revision is a forced commit, not a distinct action. A `restore` revision is likewise not a fourth
  writer: `editor/history/StructureRestoreOps` re-places a chosen revision's tag into the world and
  then calls `StructureCommit.commit(..., REASON_RESTORE)`, so **`StructureCommit` remains the sole
  writer of any `.nbt`** and the restore lands in history as a first-class entry. See
  [ui/local-history-panel.md](../ui/local-history-panel.md) for that sequence's ordering rules.
- `EditorStructureHandlers` writes the one `placed` baseline revision at place time.
- `EditorFileOpsHandlers.deleteSubtree` writes a `pre-delete` revision for every file in a subtree
  being deleted — through `writeRevision` for a parseable `.nbt`, and through `writeRawRevision`
  (which itself calls `writeRevision` with a wrapper tag) for everything else. This is the only
  writer that is not structure-specific, and the only one reached from the undo feature.

Nothing else calls `LocalHistoryStore.writeRevision`. See
`docs/architecture/redstone-project.md#standalone-structure-files`
for how `StructureCommit` decides *when* to commit (debounce ticks, max-dirty cap, and the
`BEFORE_SAVE`/`SERVER_STOPPING` backstops).

## Test coverage

`LocalHistoryStoreSpec` (`src/gametest/kotlin/com/breadmoirai/garnet/editor/history/ops/`) exercises
the store directly against a temp directory, filesystem-level and without a running world: writing
and reading a revision byte-for-byte, same-millisecond sequence numbers, chronological ordering
regardless of write order, age- and count-based pruning, and `localHistoryEnabled = false` writing
nothing. Raw revisions have their own cases there: a byte round-trip, `readRawBytes` returning
`null` for a typed revision, raw and typed revisions coexisting in one index, and the zero
size/`blockCount` a raw revision records. `StructureRestoreSpec` covers the read side end-to-end:
restoring a revision into the world and the `.nbt`, the `restore` revision it banks with a non-zero
`blockCount`, the absence of a spurious `external` revision, and the refusals (unknown timestamp,
newest revision, raw blob). `EditorUndoNetworkSpec` covers the `pre-delete` banking
end-to-end (a `.spec.kts`, a freshly duplicated `.nbt` with no history, and the unbankable and
history-disabled paths). `StructureAutoSaveSpec` and `EditorStructureNetworkSpec` cover how `StructureCommit`
drives this store end-to-end (see the `UC-MAN-10` coverage matrix in
[use-cases/structure-lifecycle.md](../use-cases/structure-lifecycle.md#coverage-matrix)).
