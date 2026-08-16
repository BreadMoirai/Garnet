---
title: Module map
tags: [modules, layout, dependencies, entry-points]
summary: Tour of the source tree — which package owns what, the dependency direction, and where to start reading for a given concern.
---

# Module map

A guided tour of `com.breadmoirai.garnet` across all six source sets
(`main`, `client`, `gametest`, `clientTest`, `test`, `testSupport`). Use this as the entry
point when you don't yet know which file to open.

**There is currently no in-world way to record or run a spec.** The blocks, block entity, and
wire protocol that used to be the product surface (`GarnetRecorderBlock`, `GarnetRunnerBlock`,
`SpecBlockEntity`, `SpecMarkerTool`, `network/Packets.kt`) were deleted. The engine underneath —
`spec/`, `playback/`, `testing/` below — is intact and fully test-covered, but the only product
callers today are the Explorer (`editor/`) and the Kotest harness (`testSupport/`). A future
Compose dock panel is the intended replacement caller for `playback`/`testing`; see
[recording-pipeline.md](recording-pipeline.md).

## Top level (`src/main/kotlin/com/breadmoirai/garnet/`)

- `Garnet.kt` — `ModInitializer`. Registers `EditorNetworkRegistry`, `AsyncEventHandler`, the
  `SubTickPhaseEvents.PHASE` listener that still drives any active `StateRecorder` (no *product*
  surface creates one today — the live caller is `testing/runner/runGarnetSpec.kt`, on the
  test-execution path), the project-root `SERVER_STARTING`/
  `SERVER_STARTED`/`SERVER_STOPPED`/`BEFORE_SAVE` hooks, `/garnet editor`, and the per-player
  session cleanup on disconnect.

## `spec/` — the spec DSL (the spec *is* the lambda)

A true leaf: imports nothing else in the project. `GarnetSpec` holds a
`SpecRun.() -> Unit` lambda — the user's declared inputs and assertions — not a flat entry list.
Construction is via `garnetSpec(id) { … }`.

- `GarnetSpec.kt` — id, `Vec3i` bounds, lifespan, optional structure id, strict flag, and the
  `block: SpecRun.() -> Unit` lambda.
- `SpecRun.kt` — execution context for one `block` invocation. `input(x,y,z) { … }` /
  `output(x,y,z) { … }` register tick-keyed callbacks into sorted maps; `SpecFailure` records
  assertion failures.
- `InputScope.kt` / `OutputScope.kt` — DSL receiver types; schedule actions / assertions against
  `SimTime` keys.
- `ConditionScope.kt` — condition leaf builders (`powered`, `prop`, `range`, `block`,
  `containerHas`) and combinators (`all` / `any` / `not`).
- `StateCondition.kt` — recursive predicate AST; `ConditionEvaluator.kt` evaluates it against a
  live `BlockState`.
- `SpecTime.kt` — `SimTime(tick, phase, order)` with `START` / `END` sentinels; `Phase` enum.
  Order within a phase is load-bearing for sequencing multiple actions.
- `PlayerInteractionDispatch.kt` — `tryApplyAsPlayerInteraction`: buttons go through
  `ButtonBlock.press` so scheduled-tick paths fire correctly; other blocks fall through to
  `setBlock`. See [runner/player-interaction-dispatch.md](../runner/player-interaction-dispatch.md).

## `core/async/` + `core/events/` — MC coroutine + tick plumbing

- `core/async/AsyncEventHandler.kt` — idempotent registration of the Fabric lifecycle/tick event
  subscriptions: installs `AsyncDispatchers` on `SERVER_STARTED`/uninstalls on `SERVER_STOPPED`,
  and forwards `START_SERVER_TICK`/`END_SERVER_TICK` into `ServerTickFlows`. `registerWithServer`
  is the gametest-sentinel variant for contexts where `SERVER_STARTED` has already fired.
- `core/async/AsyncDispatchers.kt` (`AsyncDispatchers`) — coroutine dispatcher bound to the server
  thread (`ServerThreadDispatcher`), installed/uninstalled by `AsyncEventHandler`.
- `core/async/ServerThreadDispatcher.kt` — the `CoroutineDispatcher` itself: posts continuations to
  `MinecraftServer.execute`, short-circuiting when already on the server thread.
- `core/async/ServerTickFlows.kt` — `SharedFlow`s of `START_SERVER_TICK`/`END_SERVER_TICK`, with
  the same-tick contract that keeps a resumed `awaitTicks`/`awaitTickEnd` continuation's
  server-thread work draining before MC moves on.
- `core/async/Suspending.kt` — `awaitTicks`/`awaitTickEnd`/`awaitTickWhere`/`onServer` primitives
  used by both the test harness and `runGarnetSpec`'s tick loop.
- `core/events/SubTickPhaseEvents.kt` — the `Phase` emitter (level tick → recorder `onPhase`);
  `PHASE` is registered once in `Garnet.onInitialize`.

## `structure/` — structure NBT + region math

- `StructurePersistence.kt` — compressed-NBT structure template save/load (`save`/`load`,
  origin-fixed 1:1), the standalone-file path (`captureAutoFitIn`/`placeStructureCentered`,
  auto-fit + re-center), and the crash-safe `writeStructureAtomic`. `captureAutoFitIn` is the
  bounded-volume capture `StructureCommit` uses for auto-save, and the only capture there is — the
  region-wide `captureAutoFit`/`saveAutoFitToFile` pair was deleted once nothing in production
  called it. `placeStructureCentered(file, ...)` is a thin read-then-delegate wrapper over
  `placeStructureTagCentered(nbt, ...)`, which holds the actual centering/placement logic; the
  split exists so a `CompoundTag` read straight out of a Local History blob can be placed without
  spooling it to a temp file first.
- `StructureDiff.kt` — palette-order-insensitive NBT comparison (`structuresDiffer`) used to
  decide whether a commit's captured content actually changed vs. the committed `.nbt`.
- `StructureRegionMath.kt` — `centeredStart`/`anchorY`/`autoFit`: pure geometry for centering and
  tight-boxing a structure inside an assigned region.
- `PlacedBox.kt` — `(origin, size)` record for a structure's last-placed footprint.

## `editor/structure/` — the live structure-commit pipeline

Distinct from the top-level `structure/` package above (pure NBT/region geometry, no server
state): `editor/structure/` is dirty-track → debounce → commit → history, all server-state-backed.

- `StructureAutoSave.kt` — per-server dirty-state tracking (fed by the setBlock-mixin watcher):
  which subpaths are dirty, their touched-box, and debounce due-time.
- `StructureEditWatcher.kt` — records an in-world edit against the placed structure it lands in.
- `CommitOutcome.kt` — the `CommitOutcome` sealed interface (`Committed`/`NoChange`/
  `NotApplicable`/`Failed`) returned by [StructureCommit.commit].
- `CommitBackoff.kt` — per-server failure-backoff and last-committed-disk-fingerprint bookkeeping
  used internally by `StructureCommit`; `clearBackoff` is also exposed as
  `StructureCommit.clearBackoff` for tests.
- `StructureCommit.kt` — orchestration only: turns a structure's dirty state into a committed
  `.nbt` plus a `LocalHistoryStore` revision; see
  [redstone-project.md](redstone-project.md#standalone-structure-files).

## `history/` — local history for standalone structures

- `LocalHistoryStore.kt` — writes/prunes/lists per-structure revisions under
  `<instance>/.garnet/local-history`, keyed by the structure file's own absolute path. See
  `docs/persistence/local-history.md`.

## `config/`

- `config/SharedSettings.kt` (main) — config read by both client and server: project root path,
  grid/cell sizing, structure-region sizing, auto-save tuning, and local-history retention.
- `config/ModConfig.kt` (client) — client-side persisted config (`garnet.json`): round-trips every
  `SharedSettings` field (project root path, grid/cell sizing, structure-region sizing, auto-save,
  local history) on `load()`/`save()`; absent keys leave the in-memory value untouched. There is no
  Mod Menu screen — the Explorer's root-picker header (`editor/ui/RootPickerController.kt`) is the
  only editor for the project-root value, so a duplicate YACL screen was removed as dead weight.

## `playback/` — the record → emit pipeline (engine intact, no in-game caller)

- `recorder/StateRecorder.kt` — captures per-phase block-state snapshots while a recorder is
  active. Wired to `SubTickPhaseEvents` from `Garnet.onInitialize`. No *product* code constructs
  one; the live caller is `testing/runner/runGarnetSpec.kt` (`StateRecorder.forSpec`, `.start`,
  `.activate`/`.deactivate`), on the test-execution path — every gametest/clientTest run drives it.
- `recorder/RecordingDslEmitter.kt` — derives `.spec.kts` source text from a `StateRecording`.
  Pure function: walks the recording, diffs adjacent snapshots, emits `input(…) { … }` /
  `output(…) { … }` blocks. Also emits the empty-spec stub used by `EditorNewSpec`.
- `data/StateRecording.kt` / `StateRecordingStorage.kt` / `StateRecordingView.kt` — the recorded
  data in flight and its persisted/queryable forms.
- `data/RecordingSidecar.kt` — saves/loads the authorship-time `StateRecording` as
  `<id>.recording.nbt`, consulted only by the (not-yet-built) editor timeline, not on the
  execution path.

## `testing/` — load a spec from disk and replay it

- `runner/runGarnetSpec.kt` — top-level suspend fun; snapshots the region, restores it, invokes
  `spec.block` once to populate callbacks, drives the tick loop, fires assertions inline, and
  throws `AssertionError` on failure. Called today only from the `testSupport` harness
  (`GarnetTestSpec`) and gametest/unit specs — there is no product (UI) caller yet.
- `runner/SpecSnapshot.kt` — captures/restores a region so a run starts from and returns to a
  known state.
- `data/SpecPersistence.kt` — `.spec.kts` save/load (`writeSpecKts`/`load`/`loadRecording`/
  `listIds`) via `RecordingDslEmitter`/`KtsSpecLoader`. JSON is **not** used on disk.
- `data/KtsSpecLoader.kt` — evaluates a `.spec.kts` source via `BasicJvmScriptingHost` and
  unwraps the result as `GarnetSpec`. Pins the script's `baseClassLoader` to `GarnetSpec`'s
  loader so the cast works under Fabric's mod ("knot") classloader. See
  [persistence/kts-script-host.md](../persistence/kts-script-host.md).
- `data/SpecScript.kt` — `@KotlinScript` type + `ScriptCompilationConfiguration` (pre-imports
  the DSL package).
- `data/SpecDirectoryScan.kt` — lists `.spec.kts` files in a directory, sorted.

## `editor/` — the redstone-project workspace (the live product surface)

The Explorer and the void-workspace grid are the only reachable in-game feature today. See
[redstone-project.md](redstone-project.md) for the full design; summary of package layout:

- `data/` — read-only data: `EditorRoot` (path-traversal-safe `resolveSubpath`, plus read-only
  `exists`/`isDirectory` checks), `EditorSession` (per-player active-folder pointer), `EditorCell`,
  `FileTree`/`EditorFolderTree` (tree scan models — both walk the filesystem to build their view,
  but only ever read it), `EditorNames` (name validation), `EditorSaveNaming`, `LoadedSpec`.
- `ops/` — filesystem-mutating create operations: `EditorNewSpec` (stub `.spec.kts` writer),
  `EditorNewStructure` (empty `.nbt` writer). Split out of `data/` on a mutating-vs-read-only
  seam: `data/` reads the filesystem (directory scans, existence checks) but never writes to it;
  `ops/` is where writes live.
- `world/` — the dimension/grid substrate: `EditorWorld` (per-server loaded-folder map),
  `EditorDimRegistry` (region assignment in `server.overworld()`), `EditorDimLifecycle`
  (place/save the grid), `EditorCellSaver` (dirty-diff a cell and rewrite its structure NBT),
  `EditorTeleport`, `EditorServerContext`, `EditorRootResolver` (the active managed root:
  loaded world's, else pinned server context's, else configured path), `GridLayout` (pure
  row-major slot math).
- `structure/` — the live structure-commit pipeline (dirty-track → debounce → commit → history):
  `StructureAutoSave`, `StructureEditWatcher`, `StructureCommit`. See the `editor/structure/`
  section above.
- `history/` — `StructureRestoreOps.kt` (moving a placed structure back to a banked revision, through
  `StructureCommit` so it stays the sole `.nbt` writer) and `HistoryWatchers.kt` (one watched subpath
  per player, plus the push fan-out). See [ui/local-history-panel.md](../ui/local-history-panel.md).
- `command/EditorCommand.kt` — `/garnet editor`.
- `network/EditorPackets.kt` + `EditorNetworkRegistry.kt` + `EditorTreeHandlers.kt` +
  `EditorStructureHandlers.kt` + `EditorFileOpsHandlers.kt` + `EditorHandlerSupport.kt` (main) —
  the wire protocol and its server handlers, split by concern (registration; tree/root ops;
  structure ops; file ops; shared helpers). See
  [persistence/network-payload-contract.md](../persistence/network-payload-contract.md)
  for the authority model — a *different* one from the deleted block-entity trust anchor.
- `network/EditorClientNetworking.kt` (client) — S2C receivers feeding `ProjectTreeState` (tree
  snapshot only), `StructureInfoState` (structure facts and the transient status line), and (for
  the Local History panel) `OpenStructureState` and `LocalHistoryState`.
- `ui/` (client) — `ProjectExplorerPanel.kt`, `ExplorerToolbar.kt`, `ExplorerContextMenu.kt`,
  `ExplorerEdit.kt`, `ExplorerLifecycle.kt`, `ExplorerTreeState.kt`, `ProjectTreeState.kt`,
  `FolderPicker.kt`, `RootPickerController.kt`, `LocalHistoryPanel.kt`, `OpenStructureState.kt`,
  `LocalHistoryState.kt`, `StructureInfoState.kt`, `StructureInfoPanel.kt` — the three Compose
  LEFT-dock panels (Explorer, Local History, Structure Info) and their state; see
  [ui/local-history-panel.md](../ui/local-history-panel.md) and
  [ui/structure-info-panel.md](../ui/structure-info-panel.md). Note
  these classes keep the `Project*`/legacy names deliberately (the `/garnet editor` command
  literal and "redstone project" domain term are unchanged); they are not `Editor*`.
- `world/EditorIntegratedBoot.kt` (client) — `bootWorkspace()`, the "Redstone Projects…" title-screen
  entry point.

## `ui/` — the Compose dock shell (client only)

- `compose/` — `ComposeOverlay.kt` (render/enable gate), `ComposeSceneHost.kt` (generic
  `ImageComposeScene` wrapper), `ComposeSurface.kt` (lifecycle + blit rendering), `ComposeInput.kt`
  (pointer/scroll/key entry points), `GlStateStash.kt` (GL state save/restore around Skia draws).
- `dock/` — `GarnetDock.kt` (the root composable), `DockState.kt`, `DockRegion.kt`,
  `DockInsets.kt`, `Panel.kt`, `DockStripe.kt` (the LEFT icon stripe, rendered only while any region
  is open).
- `input/` — `DockInputRouter.kt`, `GlfwKeyMap.kt`.
- `viewport/` — `ViewportState.kt`, `ViewportToggle.kt`, `DockKeybinds.kt`, `DockViewportSync.kt`
  (`syncDockViewport`, split out so it has no live-client class-init dependency and can run under a
  plain-JVM test), `CursorFocusToggle.kt`, `CompositeTarget.kt`, `BlitUvPipeline.kt` (blend pipeline).
- `widget/GarnetIconButton.kt` — the one surviving legacy-style (`GuiGraphicsExtractor`-based)
  widget, the title-screen "Redstone Projects…" button.

See [ui/INDEX.md](../ui/INDEX.md) for the detailed dock articles.

## `mixin/` (main, Java) and `mixin/client/` (client, Java)

Server-side: `ConnectionAccessor`, `ServerCommonPacketListenerImplAccessor`,
`ServerLevelPhaseMixin`, `ServerLevelSetBlockMixin`. Client-side:
`ClientCommonPacketListenerImplAccessor`, `KeyboardHandlerMixin`, `MinecraftPresentMixin`,
`MouseHandlerMixin`, `MouseHandlerViewportMixin`, `TitleScreenMixin`, `WindowMixin` — the
GLFW-input and viewport-shrink/composite mixins backing `ui/`. See
[architecture/shrink-viewport-compose-model.md](shrink-viewport-compose-model.md) and
[ui/dock-input-routing.md](../ui/dock-input-routing.md).

## Test source sets

- `src/test/kotlin/...` — pure JUnit/Kotest (DSL, kts loader/emitter, persistence, structure
  math, editor data). Mirrors the main-package layout under `com.breadmoirai.garnet`. Since
  2026-08-03 it also carries `client`'s compile classpath and holds pure-JVM client-code specs
  under `com/breadmoirai/garnet/client/` (mirroring the client package tree instead). See
  [gametest/unit-vs-gametest-split.md](../gametest/unit-vs-gametest-split.md).
- `src/gametest/kotlin/.../test/` — server-side Kotest specs driven by a `@GameTest` sentinel
  (`GametestSentinel`, `runGameTest`), under `test/editor/` and `test/structure/`.
- `src/clientTest/kotlin/.../test/` — client-side Kotest specs driven by `runClientTest`
  (`ClientTestSentinel`) — dock, viewport, and Explorer coverage.
- `src/testSupport/kotlin/.../harness/` — the Kotest bridge itself (not the mod's tests):
  `GarnetTestSpec`, `GarnetTestSpecContext`, `ClientSpec`, `RecordingHolder`, `RunGarnetSpec.kt`,
  `client/` (`ClientContextHolder`, `FabricTestThreadPump`, `WorldHolder`),
  `launcher/` (`KotestLauncher`, `ResultCollector`, `DiagnosticRecorderListener`). This sourceset
  does not ship in the mod jar; Kotest itself is only a dependency here, not on `main`. See
  [gametest/kotest-bridge.md](../gametest/kotest-bridge.md).

## Dependency direction

```
spec/  core/async/  core/events/  structure/  config/  ui/   →   playback/   →   testing/   →   editor/
```

`spec/`, `core/async/`, `core/events/`, `structure/`, `config/`, and `ui/` are the base packages —
none of them depends on any of the others in this list, and none depends up the chain. `playback/`
consumes only `spec/`. `testing/` consumes `playback/` (for `RecordingDslEmitter`) plus
`spec/`/`core/async/`.
`editor/` is the top of the stack: it consumes `structure/`, `config/`, `testing/`'s
persistence pieces, `spec/`, `playback/`, and `ui/` (its client half's panels drive the `ui/`
dock shell). Nothing outside `editor/` depends on `editor/`.

## Where to start reading

- *"How does a spec get from the world onto disk?"* — there is currently no in-game trigger.
  Read `playback/recorder/StateRecorder.kt` → `playback/recorder/RecordingDslEmitter.kt` →
  `testing/data/SpecPersistence.kt` for the intact pipeline, and see
  [recording-pipeline.md](recording-pipeline.md) for why it has no caller today.
- *"How is a spec replayed and verified?"* → `testing/runner/runGarnetSpec.kt`; the spec's
  `block` lambda fires inputs and asserts outputs inline. Called from
  `testSupport/harness/GarnetTestSpec.kt` and gametest/unit specs today. See
  [runner/engine-driven-verification.md](../runner/engine-driven-verification.md).
- *"How does the Explorer work?"* → `editor/network/EditorTreeHandlers.kt` /
  `EditorStructureHandlers.kt` / `EditorFileOpsHandlers.kt` (server handlers) and
  `editor/ui/ProjectExplorerPanel.kt` (client panel); see [redstone-project.md](redstone-project.md).
- *"Why is the GUI structured this way?"* → the legacy `RecorderScreen`/`RunnerScreen`/
  `ProjectScreen` were hard-cut in favor of a full-window Compose dock; start at
  [ui/dock-framework.md](../ui/dock-framework.md) and [ui/dock-input-routing.md](../ui/dock-input-routing.md).
