---
title: Local history for standalone structures
tags: [storage, history, autosave, structures, persistence]
summary: How auto-saved .nbt structures record revisions under <instance>/.garnet/local-history, why the key is the file's absolute path, and how pruning works.
---

# Local history for standalone structures

`com.breadmoirai.garnet.history.LocalHistoryStore` is a JetBrains-style local history for
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
(`sizeX`/`sizeY`/`sizeZ`), `blockCount`, and a `reason` tag (`"placed"`, `"autosave"`, `"manual"`,
or `"external"` — content found on disk that didn't match the newest banked revision, i.e. edited
outside the editor between sessions; see "Out-of-band edits are banked too" below). `index.json`
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

Because that revision is written speculatively — before the outcome of the rewrite it's guarding is
known — it must not be pruned yet. If the `.nbt` write then fails, `LocalHistoryStore.discardRevision`
rolls the speculative write back out of the index and deletes its blob, as if it had never happened.
If pruning had already run unconditionally at write time, a structure stuck retrying a failing
commit would permanently delete one genuine *older* revision per failed attempt whenever the
revision count was already at the cap — the failed attempt's own revision doesn't survive either
way, but a real, unrelated older revision would be destroyed for no reason. Deferring `prune` to
after a confirmed-successful rewrite avoids that.

## History outlives the structure

Deleting a `.nbt` file does not touch its history directory. This is intentional: recovering a
structure a user deleted (accidentally or not) is exactly what local history exists for, and
disappearing history the moment the file disappears would defeat that. Renaming or moving a
structure, by contrast, does move its history — `LocalHistoryStore.moveHistory` re-keys every
revision under the destination path's hash, merging into whatever history the destination already
had rather than overwriting it, and if any individual revision fails to move, that revision (and
only that one) is left behind at the source rather than the whole move being treated as
all-or-nothing.

## `blockCount` is `0` on `placed` and `external` revisions

When a structure is placed into the world, `EditorNetworking` writes an initial revision tagged
`REASON_PLACED` with `blockCount = 0`. This isn't a bug: block count is only knowable by scanning
the placed volume, and at the moment of placement nothing has been scanned yet — the structure is
being written *into* the world from its `.nbt`, not captured *out of* it. `StructureCommit` writes
`blockCount = 0` for the same reason on a `REASON_EXTERNAL` revision (see "Out-of-band edits are
banked too" above): that content comes from reading the on-disk `.nbt` tag directly, not from
scanning a placed volume, so no block count is available either. Only `REASON_AUTOSAVE` and
`REASON_MANUAL` revisions — also written by `StructureCommit` — carry a real `blockCount`, because
those are written from a `CapturedStructure` produced by scanning the world.

## The only writer

`com.breadmoirai.garnet.editor.world.StructureCommit` is the only caller that writes autosave,
manual, or external revisions; `EditorNetworking` writes the one `placed` baseline revision at
place time. No other code path calls `LocalHistoryStore.writeRevision` — there is no separate
"manual snapshot" feature. See `docs/architecture/redstone-project.md#standalone-structure-files`
for how `StructureCommit` decides *when* to commit (debounce ticks, max-dirty cap, and the
`BEFORE_SAVE`/`SERVER_STOPPING` backstops).

## Test coverage

`LocalHistoryStoreSpec` (`src/gametest/kotlin/com/breadmoirai/garnet/test/history/`) exercises
the store directly against a temp directory, filesystem-level and without a running world: writing
and reading a revision byte-for-byte, same-millisecond sequence numbers, chronological ordering
regardless of write order, age- and count-based pruning, and `localHistoryEnabled = false` writing
nothing. `StructureAutoSaveSpec` and `EditorStructureNetworkSpec` cover how `StructureCommit`
drives this store end-to-end (see the `UC-MAN-10` coverage matrix in
[use-cases/structure-lifecycle.md](../use-cases/structure-lifecycle.md#coverage-matrix)).
