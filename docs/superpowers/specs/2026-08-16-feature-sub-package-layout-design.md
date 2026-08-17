# Feature sub-package layout

**Date:** 2026-08-16
**Status:** Design approved, plan pending
**Supersedes the layout of:** `2026-07-31-feature-package-layout-design.md` (and its two
2026-08-02 amendments), which remain the historical record of how the tree reached its
current shape.

## Problem

The 2026-07-31 restructure made the tree feature-first **one level deep**: `editor/`,
`playback/`, `testing/`, `ui/`, over base packages `spec/`, `core/`, `structure/`, `history/`,
`config/`. That was the right move and it held for six weeks. It has now run out of room.

Three symptoms:

1. **`editor/`'s layer packages mix unrelated capabilities.** `editor/ui/` holds three
   independent Compose panels — Explorer, Local History, Structure Info — plus a folder picker
   and a root picker, in 19 flat files. `editor/network/` holds the handler sets for all of them
   plus a 477-line `EditorPackets.kt` carrying every payload in the mod. Touching the Local
   History panel means reading files that belong to the Explorer.
2. **The base tier accreted single-consumer packages.** Top-level `structure/` and `history/`
   were declared "base", but the import graph says every consumer of either is inside `editor/`
   or its tests. They are editor internals wearing a shared-tier name.
3. **`ui/` is a layer name at the feature tier.** With every other top-level package naming a
   capability, one named for a layer is the odd node — and it is really one capability: the dock.

## Goals

- Package by capability *recursively*: a feature owns its sub-features, and a sub-feature owns
  its layers.
- The shared tier is explicit and earns its place: nothing lives there without 2+ feature
  consumers.
- Test source sets sit in the same package as the code they cover; the source set alone says
  what kind of test it is.
- A strict, acyclic dependency direction, statable from memory, checkable per file from its path.

## Non-goals

- No behavior change. This is a pure move plus mechanical renames. Nothing is deleted, no logic
  is edited, no feature is added.
- No Stonecutter/multi-version work. Single MC 26.2 slice throughout.
- No `build.gradle.kts` source-set changes. The six source sets and their roots are unchanged.

## Accepted consequence: saved `.spec.kts` files break

`spec/` moves to `core/spec/`, and the DSL's package name appears as a literal
`import com.breadmoirai.garnet.spec.*` in **every `.spec.kts` file ever emitted** —
`RecordingDslEmitter` writes it at three call sites and `SpecScript` pre-imports the same string
into the script compilation config.

Moving the package invalidates every spec file already on disk. **No compatibility shim will be
provided**: the emitter and the pre-import are updated to the new package, old files fail to
load, and that is accepted. This was raised at design time and decided deliberately — the mod
has no released users with spec libraries to protect, and a permanent compat path is worse than
a clean break now.

There are no `.spec.kts` fixtures checked into the repo. Four test files carry the literal
string and are updated with the emitter:
`playback/recorder/RecordingDslEmitterTest`, `testing/data/KtsSpecLoaderTest`,
`testing/data/KtsSpecLoaderRoundtripTest`, `testing/data/SpecPersistenceTest`.

---

## Target layout

```
com.breadmoirai.garnet/
  Garnet.kt  GarnetClient.kt

  core/                     shared tier — 2+ feature consumers required
    spec/                   GarnetSpec, SpecRun, InputScope, OutputScope, ConditionScope,
                            StateCondition, ConditionEvaluator, SpecTime,
                            PlayerInteractionDispatch
    async/                  AsyncDispatchers, AsyncEventHandler, ServerThreadDispatcher,
                            ServerTickFlows, Suspending
    events/                 SubTickPhaseEvents
    config/                 SharedSettings (main), ModConfig (client)

  editor/
    explorer/
      data/                 EditorRoot, EditorSession, EditorNames, EditorSaveNaming,
                            EditorCell, FileTree, EditorFolderTree, LoadedSpec
      ops/                  EditorNewSpec, EditorNewStructure, DefaultPlatform
      network/              EditorTreeHandlers, EditorFileOpsHandlers,
                            + tree/file-op payloads split out of EditorPackets
      ui/                   ExplorerPanel, ExplorerToolbar, ExplorerContextMenu,
                            ExplorerDialogs, ExplorerEdit, ExplorerKeyActions,
                            ExplorerActions, ExplorerLifecycle, ExplorerTreeState,
                            ExplorerTreeSnapshot, FolderPicker, RootPickerController,
                            ExplorerStateStore, TimeFormat
    structure/
      data/                 PlacedBox, StructureRegionMath, StructureDiff, CommitOutcome
      ops/                  StructurePersistence, StructureCommit, StructureAutoSave,
                            StructureEditWatcher, CommitBackoff
      network/              EditorStructureHandlers + structure payloads
      ui/                   StructureInfoPanel, StructureInfoState, OpenStructureState
    history/
      data/                 Revision, LocalHistoryStore
      ops/                  StructureRestoreOps
      network/              HistoryWatchers + history payloads
      ui/                   LocalHistoryPanel, LocalHistoryState
    undo/
      data/                 EditorUndoStack, EditorUndoCommand
      ops/                  EditorUndoOps
      network/              undo payloads
      ui/                   UndoState
    workspace/
      world/                EditorWorld, EditorDimRegistry, EditorDimLifecycle,
                            EditorCellSaver, EditorTeleport, EditorServerContext,
                            EditorRootResolver, GridLayout
      command/              EditorCommand
      ui/                   EditorIntegratedBoot, GarnetIconButton
    network/                shared spine: EditorNetworkRegistry, EditorHandlerSupport,
                            EditorClientNetworking

  playback/  { data/, recorder/ }        contents unchanged
  testing/   { data/, runner/ }          contents unchanged

  dock/                     was ui/
    shell/                  GarnetDock, DockState, DockRegion, DockInsets, Panel,
                            DockStripe, DockAutoOpen, DockHitTest
    compose/                ComposeOverlay, ComposeSceneHost, ComposeSurface, ComposeInput,
                            GlStateStash, FocusInteractionBridge, GarnetTextField
    input/                  DockInputRouter, GlfwKeyMap
    viewport/               ViewportState, ViewportToggle, DockKeybinds, DockViewportSync,
                            DockVisibilityCommit, CursorFocusToggle, CompositeTarget,
                            BlitUvPipeline, WindowViewportExt
    data/                   DockLayoutStore

  mixin/  mixin.client/     unchanged — the mixin JSONs pin these packages
```

### Rules the layout encodes

- **Strict layering.** A sub-feature grows every layer it needs, even at one file. A one-file
  `history/ops/` is correct: predictability of `<feature>/<sub-feature>/<layer>` is the point,
  and near-empty packages are the price.
- **The `data` / `ops` seam is read-vs-write**, carried forward from the 2026-08-02 amendment:
  `data/` may read the filesystem (scans, existence checks) but never writes; `ops/` is where
  writes live.
- **Shared tier admission.** A package under `core/` must have 2+ feature consumers. Top-level
  `structure/` and `history/` fail that test today — every consumer is inside `editor/` — so they
  dissolve into `editor/structure/` and `editor/history/`.
- **`client/config/` dissolves** the same way, each of its three files having exactly one
  consumer: `ModConfig` → `core/config/`, `DockLayoutStore` → `dock/data/`,
  `ExplorerStateStore` → `editor/explorer/ui/`.
- **`EditorPackets.kt` splits four ways** along the sub-feature seam. `EditorNetworkRegistry`,
  `EditorHandlerSupport`, and `EditorClientNetworking` stay in `editor/network/` because each
  genuinely fans out across all four sub-features.

### Renames

Names keep their prefixes. Renaming is for **consistency only**, not for shortening: a
sub-feature uses one noun throughout. Only `editor/explorer/ui/` violates that today, mixing
`Project*` and `Explorer*` for the same feature:

| Current | New | Why |
|---|---|---|
| `ProjectExplorerPanel` | `ExplorerPanel` | one noun per sub-feature |
| `ProjectTreeState` | `ExplorerTreeSnapshot` | holds the server's tree snapshot; a straight `Project`→`Explorer` swap collides with the existing `ExplorerTreeState`, which holds expansion state |

Every other file keeps its exact name, `Editor*` prefixes included. Test classes follow their
subject (`ProjectExplorerPanel` tests → `ExplorerPanel*`).

## Dependency direction

```
core/                       (leaf — imports nothing else in the project)
  ↓
playback/  →  testing/
  ↓             ↓
dock/    →   editor/
```

`core/spec` remains the true leaf inside `core/`. `playback/` consumes only `core/`. `testing/`
consumes `playback/` and `core/`. `editor/` is the top of the stack and consumes everything;
nothing outside `editor/` depends on `editor/`.

Inside `editor/`, the rule is stated at **(sub-feature, layer) granularity**, and that graph must
be a DAG:

- `<sub>/data` is a leaf within its sub-feature, and any other sub-feature may import it.
- `<sub>/ops` may import any `data`, and the `ops` of a sub-feature earlier in the order
  **explorer → structure → history → undo → workspace**.
- `<sub>/network` and `<sub>/ui` are tops. Nothing imports them except `editor/network/`'s
  spine and the two entrypoints — with one recorded exception, below.

**The one exception:** `undo/ops` (`EditorUndoOps`) imports `explorer/network`
(`EditorFileOpsHandlers`) and the `editor/network/` spine (`EditorHandlerSupport`), because
undoing a file operation replays it through the very handlers the client would have invoked.
That is a genuine `ops → network` edge against the grain. It is written down here rather than
hidden, and it is the only one; if a second appears, the rule is wrong and should be revisited
rather than extended.

This is what makes the structure/history relationship legal rather than cyclic:
`structure/ops` (`StructureCommit`) imports `history/data` (`LocalHistoryStore`), while
`history/ops` (`StructureRestoreOps`) imports `structure/ops` (`StructureCommit`). Two distinct
nodes, one direction each.

## Test source sets

Every test sits in the **same package as the code it covers**. The `test.` and `client.` infix
segments in use today are dropped — the source set already says what kind of test it is.

| Source set | Before | After |
|---|---|---|
| `src/test` | `…/client/editor/ui/ExplorerTreeStateTest` | `…/editor/explorer/ui/ExplorerTreeStateTest` |
| `src/gametest` | `…/test/editor/EditorTreeHandlersSpec` | `…/editor/explorer/network/EditorTreeHandlersSpec` |
| `src/clientTest` | `…/test/ExplorerUiSpec` | `…/editor/explorer/ui/ExplorerUiSpec` |
| `src/testSupport` | `…/harness/…` | unchanged — the Kotest bridge, not tests |

Sentinel and support classes move with the tests they serve: `GametestSentinel`,
`ClientTestSentinel`, `ClientTestSupport`, `SpecTestContext`, `NetworkTestSupport`,
`EditorTestSupport`, `PanelPixelProbe`. Where a support class serves more than one feature it
stays at `com.breadmoirai.garnet.test` rather than being duplicated.

## Execution and verification

The work runs **one sub-feature per commit**, each independently green:

1. `core/` (spec + async + events + config, including the emitter/pre-import string change)
2. `editor/explorer/`
3. `editor/structure/` (absorbs top-level `structure/`)
4. `editor/history/` (absorbs top-level `history/`)
5. `editor/undo/`
6. `editor/workspace/`
7. `editor/network/` spine + the `EditorPackets` four-way split
8. `dock/` (was `ui/`, absorbs `DockLayoutStore`)
9. test source sets — repackaged to mirror, infixes dropped

Per-commit verification, from WSL against the Windows-resident project
(see `docs/tooling/wsl2-gradle-invocation.md`):

```sh
cmd.exe /c "cd /d H:\Repo\garnet && gradlew.bat :26.2:clientClasses :26.2:classes \
  :26.2:gametestClasses :26.2:clientTestClasses :26.2:testClasses"
cmd.exe /c "cd /d H:\Repo\garnet && gradlew.bat :26.2:test"
```

`:26.2:test` runs **unfiltered** — Gradle's `--tests` filter does not select Kotest specs; read
the per-class JUnit XML report instead (`docs/tooling/local-verification-commands.md`).

Non-Kotlin surfaces to keep in sync, checked every commit:

- `fabric.mod.json` entrypoints — `Garnet` / `GarnetClient` stay at the package root, unchanged.
- `garnet.mixins.json` / `garnet.client.mixins.json` `package` fields — unchanged; `mixin/` does
  not move.
- `garnet.classtweaker`.
- `garnet.json` and dock-layout config keys — unaffected; they are value keys, not class names.

Gametest and clientTest sentinels are discovered by annotation, so those two source sets need a
live game run, not just compilation, before the restructure is called done.

## Documentation

Doc updates are part of this work, not a follow-up (CLAUDE.md makes the audit mandatory):

- `docs/architecture/module-map.md` is rewritten against the new tree — it is a
  package-by-package tour and nearly every heading changes.
- Every `docs/**` citation of a moved path or a renamed class is updated; `grep -rn` for the old
  paths must come back clean.
- `docs/superpowers/specs/` and `plans/` are historical snapshots and stay untouched — including
  the 2026-07-31 design this one supersedes.
