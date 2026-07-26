# FileTree → Explorer wiring design

**Date:** 2026-07-25
**Status:** Approved

## Problem

The recursive `FileTree` model (`FolderNode`/`FileNode` under sealed `FileTreeNode`,
built by `scanFolder(path)`) exists and is fully unit-tested, but **nothing consumes
it**. The live listing path — server scan → S2C payload → client state → Explorer
panel — still uses the older flat `ProjectFolderTree` (leaves-vs-intermediates), which
ships only a per-folder `specCount` and drops individual filenames.

This is the migration deliberately left out when the model was built: make the Project
Explorer render the **real** folder hierarchy — nested folders **and** individual files
— with working expand/collapse.

Server-authoritative networking is preserved throughout: the client proposes, the
server validates paths (`ProjectRoot.resolveSubpath`) and responds.

## Scope

**In scope:** the tree *listing* path only — `scanFolder` → wire codec → payload →
`ProjectTreeState` → `ProjectExplorerPanel` rendering, plus the tests and docs that
cover it.

**Out of scope (explicit guards):**
- Server-side world/placement machinery (`ProjectDimLifecycle`, `ProjectCellSaver`,
  `GridLayout`, region assignment) is **not** touched.
- No New Spec / Save Now / Unload buttons (separate follow-up; packets/handlers exist).
- No extension→icon mapping (separate deferred task). File nodes show a plain label.
- `FileTree.kt` (the model) is **not** modified. No helper is currently missing.

## Existing pieces (do not rebuild)

- **Model** — `project/FileTree.kt`: `FileTreeNode`/`FolderNode`/`FileNode`,
  `scanFolder(path): FolderNode` (full filesystem mirror, folders-first ordering),
  `FolderNode.walk(): Sequence<Pair<String, FileTreeNode>>` (node → `/`-joined path
  relative to receiver, root emitted as `"" to this`), `FolderNode.resolve(path)`
  (path → node). Nodes store only `name`; paths are computed, so any folder can be root.
- **Client state** — `client/ide/ProjectTreeState.kt`: already has
  `expanded: mutableStateListOf<String>` + `toggleExpanded(subpath)` (dead scaffolding
  built for exactly this) and `reset()` for hermetic tests.
- **Client receivers** — `client/project/ProjectClientNetworking.kt`: feed state via
  `ctx.client().execute { ... }`.
- **Panel** — `client/ide/ProjectExplorerPanel.kt`: Compose, `Column` +
  `verticalScroll` (NOT LazyColumn — scissor/scroll gotcha, see `docs/ui`).

## Design decisions

### 1. Recursive wire codec

A hand-written `StreamCodec<ByteBuf, FileTreeNode>` lives in `ProjectPackets.kt` (the
network package), **not** in `FileTree.kt` — keeping the model free of netty
dependencies and honoring the model-untouched guard. It follows the existing
hand-written `object : StreamCodec { encode/decode }` idiom already in that file.

Per-node **tag byte** discriminates the sealed variants:
- `0` = folder: tag, `name` (`STRING_UTF8`), child count (`VAR_INT`), then each child
  encoded recursively.
- `1` = file: tag, `name` (`STRING_UTF8`), `extension` (`STRING_UTF8`).

`decode` reads the tag, branches, and recurses for folder children. The payload's root
is encoded/decoded as a node and cast to `FolderNode` on decode (a folder tag is
required at the root).

Rationale for a hand-written recursive codec: a sealed tree cannot use
`StreamCodec.composite`/`ByteBufCodecs.list` directly because the element type is
recursive and self-referential; the tag byte + explicit child-count loop is the minimal
correct encoding.

### 2. Replace the payload in place

Grep confirms `ProjectTreeSnapshotS2C` and `ProjectLeafEntry` have **no consumers**
outside the two server senders, the client state/panel, and three test files. So the
payload is replaced (not augmented):

```kotlin
data class ProjectTreeSnapshotS2C(val root: FolderNode, val currentSubpath: String?)
```

- The payload **keeps its name and packet id** `"tree_snapshot"`, so `PayloadTypeRegistry`
  wiring is unchanged.
- `ProjectLeafEntry` is **deleted** — with individual files now visible in the tree, the
  per-folder `specCount` badge is redundant. `leaves`/`intermediates`/`specCount` all go.

### 3. `currentSubpath` stays a sibling field

Nodes are deliberately path-free (any folder can serve as root). `currentSubpath`
therefore remains a top-level field on the payload, sourced from
`ProjectSession.get(player.uuid)?.activeSubpath` exactly as today. Its string value
matches `walk()`'s `/`-joined paths, so the panel can compare it directly against a
node's walk-path to render the `●` current marker.

### 4. Expand/collapse: default-collapsed, persistent

- `expanded` starts empty → **default-collapsed**: only the root's immediate children
  render on a fresh snapshot.
- The `expanded` set survives snapshot swaps (it is separate from `snapshot`), so a
  Refresh preserves what the user had open. Keys are `walk()` `/`-paths.
- The root folder (`""`) is implicitly always open — its children are the top level and
  always visible. A non-root folder's children render iff its path is in `expanded`.

### 5. Folder-row interaction

`load` semantically means "teleport the player to that folder's spec-cells in the
workspace overworld" (`LoadProjectFolderC2S` → `handleLoadFolder` →
`ProjectTeleport.toFolder`). That only makes sense for a folder that directly holds
specs. So (preserving the old leaf-vs-intermediate split, unified into one tree):

- **Triangle** (`▸`/`▾`, shown when the folder has children) → `toggleExpanded(path)`.
- **Folder label click:**
  - if the folder directly contains ≥1 child `FileNode` whose name ends `.spec.kts` (a
    *spec-folder* / old "leaf") → `LoadProjectFolderC2S(path)`.
  - otherwise (a pure container) → also `toggleExpanded(path)`.
- The `●` current marker shows only on the folder whose path == `currentSubpath`.

The client has the full tree, so it computes "spec-folder" itself:
`node.children.any { it is FileNode && it.name.endsWith(".spec.kts") }`.

### 6. File-row interaction: select + highlight

Clicking a `FileNode` sets a new `selectedPath: String?` in `ProjectTreeState` and the
row renders highlighted. **No packet is sent** — spec-open is a later sub-project. This
is cheap, gives visible feedback, and sets up that future work.

## Components changed

### `network/project/ProjectPackets.kt`
- Delete `ProjectLeafEntry`.
- Add the recursive `FileTreeNode` `StreamCodec` (tag-byte).
- Rewrite `ProjectTreeSnapshotS2C` to `(root: FolderNode, currentSubpath: String?)` with
  a codec that reads/writes the root node + the nullable current-subpath (same
  boolean-prefixed nullable-string idiom already used).

### `network/project/ProjectNetworkRegistry.kt`
- `sendTree`: `scanFolder(root.path)` instead of `ProjectFolderTree.scan(root)`; build
  the new payload. `resolveSubpath` validation on inbound `LoadProjectFolderC2S`
  unchanged.

### `project/ProjectCommand.kt`
- The second sender of the identical snapshot: same swap to `scanFolder(root.path)` +
  new payload.

### `project/ProjectFolderTree.kt`
- **Unchanged and retained.** Still used by `ProjectDimLifecycle.placeFolder` for
  placement; only its listing role ends. Not a removal candidate.

### `client/ide/ProjectTreeState.kt`
- `snapshot` holds the new payload type.
- Add `selectedPath: String?` (private set) + `select(path: String)`; `reset()` clears it.
- Reuse existing `expanded` / `toggleExpanded`.

### `client/ide/ProjectExplorerPanel.kt`
- Recursively render `snapshot.root`'s children (root label not shown) as an indented
  tree with expand/collapse triangles, the `●` current marker, folder load / file select
  interactions above. Keep `Column` + `verticalScroll`. Preserve the Refresh row and
  status line.

### `client/project/ProjectClientNetworking.kt`
- Receiver signature follows the payload type change; no behavioral change.

## Data flow

```
scanFolder(root.path): FolderNode          [server]
  → ProjectTreeSnapshotS2C(root, currentSubpath)
  → StreamCodec (recursive tag-byte encode)
  → S2C
  → ClientPlayNetworking receiver → ctx.client().execute
  → ProjectTreeState.onSnapshot                [client state]
  → ProjectExplorerPanel recomposes:           [render]
      render root.children, indent by depth,
      expanded-gated recursion, ● marker,
      folder-label → LoadProjectFolderC2S / toggle,
      file → select
```

Inbound (unchanged authority model): `LoadProjectFolderC2S(path)` → server
`resolveSubpath` guard → teleport + `ProjectFolderLoadedS2C`.

## Testing strategy

- **NEW unit** `src/test/.../FileTreeCodecTest.kt` (Kotest, autoscans) — **TDD anchor,
  written first**: build a nested `FolderNode` covering folders, files, an empty folder,
  and a no-extension file; encode to a `ByteBuf`, decode, assert `decoded shouldBe
  original` (data-class equality). Round-trips the recursive codec in isolation.
- **Update gametest** `src/gametest/.../ProjectNetworkRegistrySpec.kt` and
  `ProjectCommandSpec.kt` — assert against `snap.root` via `walk()`/`resolve()` (e.g.
  expected paths present, a known spec file resolvable) instead of
  `leaves`/`specCount`/`intermediates`. Both already registered in `GametestSentinel`.
- **Update clientTest** `src/clientTest/.../ProjectExplorerSpec.kt` — feed a real
  `FolderNode` tree via `onSnapshot`, assert **content-based** (walk paths present in
  state, not `snapshot != null`), capture the screenshot. Already registered in
  `ClientTestSentinel`.

## Verification (WSL, verbatim)

- Unit: `cmd.exe /c "gradlew.bat :26.1:test"` — run **unfiltered** (Kotest; `--tests`
  gives false "No tests found"). Read pass/fail from the console summary or
  `build/test-results/test/*.xml`.
- 5-sourceset compile: `cmd.exe /c "gradlew.bat :26.1:clientClasses :26.1:classes
  :26.1:gametestClasses :26.1:clientTestClasses :26.1:testClasses"`.
- Client in-MC: `cmd.exe /c "gradlew.bat :26.1:runClientTest"`.

All three must be green before claiming done; report actual output.

## Doc sync (CLAUDE.md mandatory audit)

- `docs/architecture/redstone-project.md`:
  - `FileTree` bullet — drop "**Not yet wired** … which still use the flat
    `ProjectFolderTree`"; state it now backs the payload + Explorer.
  - `ProjectFolderTree` bullet — now placement-only (used by `ProjectDimLifecycle`), no
    longer the listing model.
  - `ProjectExplorerPanel` / `ProjectTreeState` bullets — recursive tree +
    expand/collapse + file select.
  - "How does the GUI show the folder tree?" entry — reflect the recursive render.
- Grep `docs/` for `ProjectLeafEntry`, `specCount`, `intermediates` and fix live
  references (`docs/ui/dock-framework.md`, `docs/use-cases/*`). Historical
  `superpowers/specs` and `superpowers/plans` snapshots are left as-is.

## Rollout

Direct commits to `main` (project workflow), conventional-commit messages
(`feat(project):`, `feat(ui):`, `docs(architecture):`). No `Co-Authored-By` / "Generated
with Claude Code" trailer.
