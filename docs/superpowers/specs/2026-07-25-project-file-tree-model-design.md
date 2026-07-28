# Project file-tree data model — design

**Date:** 2026-07-25
**Status:** approved, ready for implementation plan
**Scope:** pure server-side data model only (no network payload, no Compose panel)

## Problem

The current representation of a project's folder contents is two flat lists built by
`ProjectFolderTree.scan` — `leaves` (folders that directly contain `.spec.kts` files, each
with its spec filenames) and `intermediates` (folders that have subdirectories). This model:

- Loses the **hierarchy** — there are no parent/child links, so consumers can't render a real
  nested tree.
- Drops **individual filenames** by the time they reach the client (the network payload ships
  only a per-leaf `specCount`).
- Only sees `.spec.kts` files — nothing else in the folder is represented.

We want a genuine recursive file-tree model: folders and files as distinct node types, mirroring
the filesystem, that can back a real expand/collapse explorer and per-file actions later.

## Non-goals

- **Not** touching `ProjectFolderTree`, `ProjectTreeSnapshotS2C`/network payloads, or
  `ProjectExplorerPanel`/`ProjectTreeState`. The old flat model stays live; migrating consumers
  onto the new model is a **later** task.
- **Not** modeling spec-internal structure (inputs/outputs/etc.). Files are opaque leaves.
- **Not** deciding the extension→icon mapping or a compound `.spec.kts` extension rule. Icons
  and any compound-extension rule are a later concern; the model exposes a simple extension only.

## Model

New file: `src/main/kotlin/com/breadmoirai/garnet/project/FileTree.kt` — pure Kotlin data
plus a scanner. No Minecraft dependencies, so it is unit-testable off-thread in `src/test`.

```kotlin
sealed interface FileTreeNode {
    val name: String   // this node's own path segment, e.g. "foo.spec.kts" or "sub"
}

data class FolderNode(
    override val name: String,
    val children: List<FileTreeNode>,
) : FileTreeNode

data class FileNode(
    override val name: String,
    val extension: String,   // kotlin.io.path.Path.extension: last-dot rule, "" if none
) : FileTreeNode
```

- **A "project" is just the root `FolderNode`.** There is no separate `Project`/`ProjectTree`
  wrapper type. Any `FolderNode` can be handed to a consumer as "the root."
- **No baked root-relative path.** Nodes store only their own `name`. This makes **re-rooting a
  cheap in-memory operation**: to treat a subfolder as the new root, just pass that `FolderNode`
  to consumers — nothing needs recomputing and no path goes stale. Paths are computed on demand
  relative to whichever node is the chosen root (see Path helpers).
- **`extension`** is the plain `kotlin.io.path.Path.extension` value (substring after the last
  dot, `""` when there is no dot), lowercased. The icon mapping / compound-extension rule is
  deferred; consumers can re-derive from `name` when that rule lands.

## Scanner

```kotlin
fun scanFolder(path: Path): FolderNode
```

- Recursive **filesystem mirror**: includes **all** files (any extension) and **all** folders,
  **including empty ones**.
- Root node's `name` = the folder's own name (`path.name`).
- Each folder's `children` are sorted **folders-first, then files, then alphabetical by name,
  case-insensitive** — the conventional file-explorer order.
- A non-existent path or a non-directory path yields an empty root: `FolderNode(path.name, emptyList())`.

Implementation uses `kotlin.io.path` extensions throughout rather than raw `java.nio.file`
idioms — `path.name`, `path.extension`, `path.isDirectory()`, `path.isRegularFile()`,
`path.listDirectoryEntries()`, and `kotlin.io.path.div` (`base / "sub"`) for any path joins.
This matches the direction the codebase already leans (`ProjectFolderTree` uses `it.isDirectory()`,
`it.name`).

## Path helpers

Paths are **computed, not stored**. Both helpers treat the receiver `FolderNode` as the root, so
they automatically produce paths relative to whatever node you re-rooted to.

```kotlin
/** Resolve a '/'-joined path (root = "") to a node under this root, or null if none. */
fun FolderNode.resolve(path: String): FileTreeNode?

/** Every node paired with its '/'-path relative to this root, depth-first. */
fun FolderNode.walk(): Sequence<Pair<String, FileTreeNode>>
```

- Path format: `/`-joined segments, no leading slash; the root itself maps to `""`. (Matches the
  existing string-subpath convention used by `LoadProjectFolderC2S`, `ProjectRoot.resolveSubpath`,
  and the client `expanded` set — so a future migration slots in cleanly.)
- **The two helpers cover both directions and compose cleanly:** `walk` gives node→path (a
  consumer holds the path for every node it visits during traversal), and `resolve` gives the
  reverse, path→node, for random access — e.g. re-fetching the node for a selection stored as a
  path string. This deliberately avoids a node→path lookup that would need reference identity
  (same-named files in different folders are *structurally* equal as `data class`es).
- `resolve` splits on `/`, walks child folders segment by segment matching on `name`, returns the
  matched node — or the receiver itself for `""` — and null if any segment is missing. It does not
  depend on holding a specific node instance, matching the codebase's string-subpath idiom.

## Testing

New file: `src/test/kotlin/com/breadmoirai/garnet/project/FileTreeTest.kt`, mirroring the
existing `ProjectFolderTreeTest` style (JUnit + temp dir). Cases:

- **Structure:** a temp tree (nested folders, files, an empty folder) scans to the expected
  `FolderNode`/`FileNode` shape.
- **Ordering:** children come out folders-first then files, alphabetical case-insensitive.
- **Empty folders included:** an empty subdirectory appears as a childless `FolderNode`.
- **All extensions:** non-`.spec.kts` files (e.g. `.nbt`, `.md`, a no-extension file) appear;
  `extension` is the last-dot value, lowercased, `""` for the extensionless file.
- **`walk` paths:** every emitted path is `/`-joined relative to the root; root maps to `""`.
- **`resolve` round-trip:** for every `(path, node)` from `walk`, `resolve(path)` returns that
  same node; `resolve("")` returns the receiver; `resolve` of a missing path returns null.
- **Re-rooting:** calling `walk`/`resolve` on a *subfolder* node yields/consumes paths relative to
  that subfolder, not the original scan root.
- **Missing/non-directory path:** yields an empty root `FolderNode`.

## Docs

Add `FileTree` to the "Pure data" component list in
`docs/architecture/redstone-project.md`, noting it is the new recursive tree model and is **not
yet wired** to the network payload or the Explorer (which still use `ProjectFolderTree`).
