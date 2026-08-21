---
title: Module map
tags: [modules, layout, dependencies, entry-points]
summary: Tour of the source tree — which package owns what, the dependency direction, and where to start reading for a given concern.
---

# Module map

A guided tour of `com.breadmoirai.garnet` across all six source sets
(`main`, `client`, `gametest`, `clientTest`, `test`, `testSupport`). Use this as the entry
point when you don't yet know which file to open.

The tree is **feature-first, recursively**: a top-level package names a capability, a feature
owns its sub-features, and a sub-feature owns its layers — `<feature>/<sub-feature>/<layer>`.
See [the 2026-08-16 design](../superpowers/specs/2026-08-16-feature-sub-package-layout-design.md)
for the reasoning; this article describes the tree as it actually stands.

**There is still no in-world way to record or run a spec.** The blocks, block entity, and wire
protocol that used to be the product surface (`GarnetRecorderBlock`, `GarnetRunnerBlock`,
`SpecBlockEntity`, `SpecMarkerTool`, `network/Packets.kt`) were deleted, and nothing has replaced
them — verified against the current tree: nothing outside `testing/` and the test source sets
references `StateRecorder` or `runGarnetSpec`. The nuance worth carrying: `playback/` and
`testing/` are not *entirely* callerless. `editor/explorer/ops/EditorNewSpec` calls
`RecordingDslEmitter` for its empty-spec stub, and `editor/workspace/world/EditorCellSaver` and
`EditorDimLifecycle` call `testing/data/KtsSpecLoader` to read a `.spec.kts` off disk. What has no
product caller is the **execution** path — `StateRecorder` and `runGarnetSpec` — whose only live
callers are `testSupport/` and the gametest/clientTest specs. A future Compose dock panel is the
intended replacement caller; see [recording-pipeline.md](recording-pipeline.md).

## Top level

- `Garnet.kt` (main) — `ModInitializer`. Registers `EditorNetworkRegistry`, `AsyncEventHandler`,
  the `SubTickPhaseEvents.PHASE` listener that drives any active `StateRecorder`, the project-root
  `SERVER_STARTING`/`SERVER_STARTED`/`SERVER_STOPPED`/`BEFORE_SAVE` hooks, `/garnet editor`, and
  the per-player session/undo-stack cleanup on disconnect.
- `GarnetClient.kt` (client) — `ClientModInitializer`. Boots the dock, its input routing and
  keybinds, and the client-side S2C receivers.

Both are pinned by `fabric.mod.json` entrypoints and stay at the package root.

## `core/` — the shared tier

Admission rule: a package lives here only if 2+ features consume it. `core/` imports nothing else
in the project.

### `core/spec/` — the spec DSL (the spec *is* the lambda)

The true leaf. `GarnetSpec` holds a `SpecRun.() -> Unit` lambda — the user's declared inputs and
assertions — not a flat entry list. Construction is via `garnetSpec(id) { … }`.

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

The package name is a literal in every emitted `.spec.kts` file (`import
com.breadmoirai.garnet.core.spec.*`), written by `RecordingDslEmitter` and pre-imported by
`SpecScript`. Moving this package again invalidates every spec file on disk.

### `core/async/` + `core/events/` — MC coroutine + tick plumbing

- `async/AsyncEventHandler.kt` — idempotent registration of the Fabric lifecycle/tick event
  subscriptions: installs `AsyncDispatchers` on `SERVER_STARTED`/uninstalls on `SERVER_STOPPED`,
  and forwards `START_SERVER_TICK`/`END_SERVER_TICK` into `ServerTickFlows`. `registerWithServer`
  is the gametest-sentinel variant for contexts where `SERVER_STARTED` has already fired.
- `async/AsyncDispatchers.kt` — coroutine dispatcher bound to the server thread
  (`ServerThreadDispatcher`), installed/uninstalled by `AsyncEventHandler`.
- `async/ServerThreadDispatcher.kt` — the `CoroutineDispatcher` itself: posts continuations to
  `MinecraftServer.execute`, short-circuiting when already on the server thread.
- `async/ServerTickFlows.kt` — `SharedFlow`s of `START_SERVER_TICK`/`END_SERVER_TICK`, with the
  same-tick contract that keeps a resumed `awaitTicks`/`awaitTickEnd` continuation's server-thread
  work draining before MC moves on.
- `async/Suspending.kt` — `awaitTicks`/`awaitTickEnd`/`awaitTickWhere`/`onServer` primitives used
  by both the test harness and `runGarnetSpec`'s tick loop.
- `events/SubTickPhaseEvents.kt` — the `Phase` emitter (level tick → recorder `onPhase`); `PHASE`
  is registered once in `Garnet.onInitialize`.

### `core/config/`

- `SharedSettings.kt` (main) — config read by both client and server: project root path,
  grid/cell sizing, structure-region sizing, auto-save tuning, and local-history retention.
- `ModConfig.kt` (client) — client-side persisted config (`garnet.json`): round-trips every
  `SharedSettings` field on `load()`/`save()`; absent keys leave the in-memory value untouched.
  There is no Mod Menu screen — the Explorer's root-picker header
  (`editor/explorer/ui/RootPickerController.kt`) is the only editor for the project-root value, so
  a duplicate YACL screen was removed as dead weight.

Two other files that used to sit in a client `config/` package dissolved to their single
consumers: `DockLayoutStore` → `dock/data/`, `ExplorerStateStore` → `editor/explorer/ui/`.

## `playback/` — the record → emit pipeline

- `recorder/StateRecorder.kt` — captures per-phase block-state snapshots while a recorder is
  active. Wired to `SubTickPhaseEvents` from `Garnet.onInitialize`. No *product* code constructs
  one; the live caller is `testing/runner/runGarnetSpec.kt`, on the test-execution path — every
  gametest/clientTest run drives it.
- `recorder/RecordingDslEmitter.kt` — derives `.spec.kts` source text from a `StateRecording`.
  Pure function: walks the recording, diffs adjacent snapshots, emits `input(…) { … }` /
  `output(…) { … }` blocks. Also emits the empty-spec stub used by `EditorNewSpec` — this one
  function *does* have a product caller.
- `data/StateRecording.kt` / `StateRecordingStorage.kt` / `StateRecordingView.kt` — the recorded
  data in flight and its persisted/queryable forms.
- `data/RecordingSidecar.kt` — saves/loads the authorship-time `StateRecording` as
  `<id>.recording.nbt`, consulted only by the (not-yet-built) editor timeline, not on the
  execution path.

## `testing/` — load a spec from disk and replay it

- `runner/runGarnetSpec.kt` — top-level suspend fun; snapshots the region, restores it, invokes
  `spec.block` once to populate callbacks, drives the tick loop, fires assertions inline, and
  throws `AssertionError` on failure. Called today only from the `testSupport` harness
  (`GarnetTestSpec`) and gametest/clientTest specs — there is no product caller.
- `runner/SpecSnapshot.kt` — captures/restores a region so a run starts from and returns to a
  known state.
- `data/SpecPersistence.kt` — `.spec.kts` save/load (`writeSpecKts`/`load`/`loadRecording`/
  `listIds`) via `RecordingDslEmitter`/`KtsSpecLoader`. JSON is **not** used on disk.
- `data/KtsSpecLoader.kt` — evaluates a `.spec.kts` source via `BasicJvmScriptingHost` and unwraps
  the result as `GarnetSpec`. Pins the script's `baseClassLoader` to `GarnetSpec`'s loader so the
  cast works under Fabric's mod ("knot") classloader. Called from `editor/workspace/world/` when a
  cell is loaded or saved. See [persistence/kts-script-host.md](../persistence/kts-script-host.md).
- `data/SpecScript.kt` — `@KotlinScript` type + `ScriptCompilationConfiguration` (pre-imports the
  DSL package, `com.breadmoirai.garnet.core.spec.*`).
- `data/SpecDirectoryScan.kt` — lists `.spec.kts` files in a directory, sorted.

## `editor/` — the redstone-project workspace (the live product surface)

The Explorer and the void-workspace grid are the only reachable in-game feature today. See
[redstone-project.md](redstone-project.md) for the full design.

`editor/` is split into five sub-features — **explorer, structure, history, undo, workspace** —
plus a shared `network/` spine. Each sub-feature grows every layer it needs, even at one file: a
one-file `history/ops/` is correct, because predictability of `<sub-feature>/<layer>` is the point.
The `data`/`ops` seam is **read-vs-write**: `data/` may read the filesystem (scans, existence
checks) but never writes; `ops/` is where writes live.

### `editor/explorer/` — the project tree and file operations

- `data/EditorRoot.kt` — the project root, with the path-traversal-safe `resolveSubpath` plus
  read-only `exists`/`isDirectory` checks.
- `data/EditorSession.kt` — per-player active-folder pointer, a `ConcurrentHashMap` keyed by
  player UUID, cleared on disconnect.
- `data/EditorCell.kt` — one grid cell's identity.
- `data/FileTree.kt` / `data/EditorFolderTree.kt` — tree scan models; both walk the filesystem to
  build their view, but only ever read it.
- `data/EditorNames.kt` — name validation. `data/EditorSaveNaming.kt` — save-file naming rules.
- `data/LoadedSpec.kt` — a spec paired with the cell it occupies.
- `ops/EditorNewSpec.kt` — writes a stub `.spec.kts` (via `RecordingDslEmitter`).
  `ops/EditorNewStructure.kt` — writes an empty `.nbt`.
- `ops/DefaultPlatform.kt` — the platform seam the create ops resolve paths through.
- `network/ExplorerPackets.kt` — the tree and file-op payloads: `EditorTreeSnapshotS2C`,
  `ListEditorTreeC2S`, `LoadEditorFolderC2S`, `SetEditorRootC2S`, `UnloadEditorFolderC2S`,
  `NewEditorSpecC2S`, `EditorFolderLoadedS2C`, `EditorErrorS2C`, `NewStructureC2S`,
  `CreateFolderC2S`, `RenamePathC2S`, `DuplicatePathC2S`, `DeletePathC2S`, `MovePathC2S`, plus the
  hand-rolled recursive `FILE_TREE_STREAM_CODEC`.
- `network/EditorTreeHandlers.kt` — server handlers for tree/root operations.
- `network/EditorFileOpsHandlers.kt` — server handlers for create/rename/duplicate/delete/move,
  and the `relocate`/`deleteSubtree`/`restoreSubtree` primitives that undo replays through.
- `ui/ExplorerPanel.kt` (client) — the Compose LEFT-dock panel, built on Jewel's `LazyTree`.
- `ui/ExplorerToolbar.kt`, `ui/ExplorerContextMenu.kt`, `ui/ExplorerDialogs.kt` (which
  confirm/pick dialog is open and where — panel-scoped by construction, never a top-level object,
  so it cannot survive a panel re-mount), `ui/ExplorerEdit.kt` (inline name field).
- `ui/ExplorerActions.kt` — validate-then-send for create/rename, with a `sender` seam so
  clientTests can assert on payloads without a live connection.
- `ui/ExplorerKeyActions.kt` — wraps the tree's `KeyActions` so that while an in-tree name field is
  open the tree consumes no keys at all (Jewel installs its bindings with `onPreviewKeyEvent` on
  the tree *container*, which otherwise wins every key the text field needs).
- `ui/ExplorerLifecycle.kt` — panel mount/unmount and the state resets that hang off it.
- `ui/ExplorerTreeState.kt` — expansion/selection state. `ui/ExplorerTreeSnapshot.kt` — the
  server's tree snapshot as received. (Two distinct things; the names are deliberately not
  interchangeable.)
- `ui/ExplorerStateStore.kt` — the `config/garnet-explorer.json` round-trip for the per-session
  expanded/selected paths, keyed by the root they were captured against.
- `ui/FolderPicker.kt` — the native folder dialog (`NfdFolderPicker`).
  `ui/RootPickerController.kt` — the root-picker header.
- `ui/TimeFormat.kt` — wall-clock formatting shared by the Local History and Structure Info
  panels, on `Locale.ROOT` so the two panels cannot disagree.

### `editor/structure/` — the live structure-commit pipeline

Dirty-track → debounce → commit → history, all server-state-backed. `data/` here is the pure
NBT/region geometry that used to be a top-level `structure/` package.

- `data/StructureRegionMath.kt` — `centeredStart`/`anchorY`/`autoFit`: pure geometry for centering
  and tight-boxing a structure inside an assigned region.
- `data/PlacedBox.kt` — `(origin, size)` record for a structure's last-placed footprint.
- `data/StructureDiff.kt` — palette-order-insensitive NBT comparison (`structuresDiffer`), used to
  decide whether a commit's captured content actually changed vs. the committed `.nbt`.
- `data/CommitOutcome.kt` — the sealed interface (`Committed`/`NoChange`/`NotApplicable`/`Failed`)
  returned by `StructureCommit.commit`.
- `data/CommittedStructure.kt` — the domain record `Committed` carries (subpath, size, block count,
  timestamp). Deliberately *not* the `StructureAutoSavedS2C` wire payload: `data` is a leaf layer,
  so the wire mapping lives at the network boundary as
  `StructurePackets.CommittedStructure.toAutoSavedPayload()`. The two types carry identical fields
  today; the payload's field order is its stream codec and is frozen by the protocol, this one is
  free to change.
- `ops/StructurePersistence.kt` — compressed-NBT template save/load (`save`/`load`, origin-fixed
  1:1), the standalone-file path (`captureAutoFitIn`/`placeStructureCentered`), and the crash-safe
  `writeStructureAtomic`. `placeStructureCentered(file, …)` is a thin read-then-delegate wrapper
  over `placeStructureTagCentered(nbt, …)`, so a `CompoundTag` read straight out of a Local History
  blob can be placed without spooling it to a temp file first.
- `ops/StructureAutoSave.kt` — per-server dirty-state tracking: which subpaths are dirty, their
  touched-box, and debounce due-time.
- `ops/StructureEditWatcher.kt` — records an in-world edit against the placed structure it lands in
  (fed by `ServerLevelSetBlockMixin`).
- `ops/CommitBackoff.kt` — per-server failure-backoff and last-committed-disk-fingerprint
  bookkeeping used internally by `StructureCommit`.
- `ops/StructureCommit.kt` — orchestration only: turns a structure's dirty state into a committed
  `.nbt` plus a `LocalHistoryStore` revision. The sole `.nbt` writer. Sends nothing — `tick` and
  `commitAll` return what they committed and `network/StructureSync` announces it. See
  [redstone-project.md](redstone-project.md#standalone-structure-files).
- `network/StructurePackets.kt` — `SaveNowC2S`, `EditorSaveReportS2C`, `PlaceStructureC2S`,
  `SaveStructureC2S`, `StructureAutoSavedS2C`, `StructureResultS2C`, plus
  `CommittedStructure.toAutoSavedPayload()`, the one place that knows a committed structure has a
  wire form.
- `network/EditorStructureHandlers.kt` — their server handlers.
- `network/StructureSync.kt` — the client-facing half of the commit pipeline: `broadcast` (the
  unsolicited `canSend`-guarded fan-out) plus `tick`/`commitAll`, thin wrappers that run the
  `ops` commit and announce what it produced. **Production calls these, not the `StructureCommit`
  forms** — `Garnet.kt`'s `END_SERVER_TICK`, `BEFORE_SAVE` and `SERVER_STOPPING` all go through
  here. Calling `StructureCommit.tick` directly commits correctly but tells nobody.
- `ui/StructureInfoPanel.kt` + `ui/StructureInfoState.kt` (client) — the Structure Info dock panel
  and its state; `ui/OpenStructureState.kt` — which structure the panels are pointed at. See
  [ui/structure-info-panel.md](../ui/structure-info-panel.md).

### `editor/history/` — local history for standalone structures

- `data/LocalHistoryStore.kt` — writes/prunes/lists per-structure revisions under
  `<instance>/.garnet/local-history`, keyed by the structure file's own absolute path. See
  [persistence/local-history.md](../persistence/local-history.md).
- `data/Revision.kt` — one banked revision's metadata.
- `ops/StructureRestoreOps.kt` — moves a placed structure back to a banked revision, through
  `StructureCommit` so that stays the sole `.nbt` writer.
- `network/HistoryPackets.kt` — `RevisionEntry`, `WatchStructureHistoryC2S`, `StructureHistoryS2C`,
  `RestoreRevisionC2S`.
- `network/HistoryWatchers.kt` — one watched subpath per player, plus the push fan-out.
- `ui/LocalHistoryPanel.kt` + `ui/LocalHistoryState.kt` (client) — the Local History dock panel.
  See [ui/local-history-panel.md](../ui/local-history-panel.md).

### `editor/undo/` — per-player undo/redo over file operations

- `data/EditorUndoStack.kt` — per-player undo/redo history. Mirrors `EditorSession`'s shape: a
  `ConcurrentHashMap` keyed by player UUID, in memory only, cleared by the same disconnect
  registration. Nothing is persisted — a stack restored after a restart would be almost entirely
  stale, and the content a delete needs to be reversible lives in `LocalHistoryStore`, which does
  survive.
- `data/EditorUndoCommand.kt` — the command ADT (`Relocate`, `CreateFile`, deleted-subtree nodes
  and their banked blobs), with `relPath` resolved against the *current* root on restore.
- `ops/EditorUndoOps.kt` — replays a command's inverse (undo) or the command itself (redo).
- `network/UndoPackets.kt` — `UndoC2S`, `RedoC2S`, `UndoStateS2C` (per-player, never broadcast).
- `ui/UndoState.kt` (client) — mirror of the server's availability; the client never derives it
  itself because it has no stack. A null label disables that toolbar button.

### `editor/workspace/` — the dimension/grid substrate

- `world/EditorWorld.kt` — per-server loaded-folder map.
- `world/EditorDimRegistry.kt` — region assignment in `server.overworld()`.
- `world/EditorDimLifecycle.kt` — place/save the grid.
- `world/EditorCellSaver.kt` — dirty-diff a cell and rewrite its structure NBT.
- `world/EditorTeleport.kt` — moving a player to a cell (including the look-at-structure rotation).
- `world/EditorServerContext.kt` — the pinned server context.
- `world/EditorRootResolver.kt` — the active managed root: loaded world's, else pinned server
  context's, else configured path.
- `world/GridLayout.kt` — pure row-major slot math.
- `command/EditorCommand.kt` — `/garnet editor`.
- `ui/EditorIntegratedBoot.kt` (client) — `bootWorkspace()`, the "Redstone Projects…" title-screen
  entry point. `ui/GarnetIconButton.kt` — the one surviving legacy-style
  (`GuiGraphicsExtractor`-based) widget, used for that title-screen button.

### `editor/network/` — the shared spine

The three files that genuinely fan out across all four payload-carrying sub-features:

- `EditorNetworkRegistry.kt` (main) — registers every payload type and server receiver.
- `EditorHandlerSupport.kt` (main) — shared handler helpers (`fail`, `sendTree`, `sendUndoState`,
  `commitDirtyUnder`).
- `PayloadIds.kt` (main) — the `payloadId(p)` helper minting `garnet:project_<p>` identifiers, shared by
  all four per-sub-feature payload files.
- `EditorClientNetworking.kt` (client) — S2C receivers feeding `ExplorerTreeSnapshot`,
  `StructureInfoState`, `OpenStructureState`, `LocalHistoryState`, and `UndoState`.

See [persistence/network-payload-contract.md](../persistence/network-payload-contract.md) for the
authority model.

## `dock/` — the Compose dock shell (client only)

Formerly the top-level `ui/` package; renamed because it is one capability, not a layer.

- `shell/` — `GarnetDock.kt` (the root composable), `DockState.kt`, `DockRegion.kt`,
  `DockInsets.kt`, `Panel.kt`, `DockStripe.kt` (the LEFT icon stripe, rendered only while any
  region is open), `DockAutoOpen.kt` (the "is the peer a Garnet server" probe gating join-time
  auto-open, behind a test seam), `DockHitTest.kt` (which region owns a window pixel, or `null` for
  the bare world viewport — the pointer-side mirror of `insets()`).
- `compose/` — `ComposeOverlay.kt` (render/enable gate), `ComposeSceneHost.kt` (generic
  `ImageComposeScene` wrapper), `ComposeSurface.kt` (lifecycle + blit rendering), `ComposeInput.kt`
  (pointer/scroll/key entry points), `GlStateStash.kt` (GL state save/restore around Skia draws),
  `FocusInteractionBridge.kt`, `GarnetTextField.kt` (which also reports its focus into
  `DockTextInputFocus.kt`, the "someone is typing" gate the `G` keybind consults).
- `input/` — `DockInputRouter.kt`, `GlfwKeyMap.kt`, `DockFocusKeybind.kt` (the `G` dock-focus
  keybind and its `isDockFocusKey` half for the captured path), `DockFocusTarget.kt` (`focusTarget()`,
  which region `G` focuses — pure, unit-tested).
- `viewport/` — `ViewportState.kt`, `ViewportToggle.kt`, `DockKeybinds.kt`, `DockViewportSync.kt`
  (`syncDockViewport`, split out so it has no live-client class-init dependency and can run under a
  plain-JVM test), `DockVisibilityCommit.kt`, `CompositeTarget.kt`,
  `BlitUvPipeline.kt` (blend pipeline), and `WindowViewportExt.java`.
- `data/DockLayoutStore.kt` — the `garnet-dock.json` round-trip for remembered region visibility.

See [ui/INDEX.md](../ui/INDEX.md) for the detailed dock articles.

## `camera/` — the orbit camera (main + client)

The client owns this camera outright and just moves the player's own entity through spectator; the
server's only involvement is the one payload below. See
[ui/orbit-camera.md](../ui/orbit-camera.md) for the authority split and why.

- `data/OrbitCamera.kt` (main) — the pure, immutable pivot/yaw/pitch/distance state and its
  `orbit`/`pan`/`dolly` transforms, the shared `rightVector`/`upVector` basis, the `cursorRay`
  projection inverse used to pick a pivot under the free cursor, `pivotFor`'s snap from that raycast
  hit to the centre of the block cell, the `orbitAround` focus seed, and the derived `eyePosition`. `pan`/`dolly` translate the camera at flat world rates; `distance` is
  the orbit radius only. No Minecraft client dependency; unit-tested directly under `src/test`.
- `network/CameraPackets.kt` + `network/CameraModeHandlers.kt` (main) — `CameraModeC2S`, the one
  payload this feature has, and its server handler. Registered through
  `editor/network/EditorNetworkRegistry.kt` alongside the editor payloads even though it lives
  outside `editor/*/network/` — see
  [persistence/network-payload-contract.md](../persistence/network-payload-contract.md).
- `input/OrbitCameraController.kt` (client) — `applyFrame` writes the pose onto the `LocalPlayer`
  every rendered frame (hooked by `mixin/client/MinecraftFrameMixin.java`), `applyTick` keeps the
  tick-scoped bookkeeping; owns lazy entry, the cursor aim, the spectator-arm timeout, and exit.
- `input/CursorPick.kt` (client) — stateless: the free-cursor ray (content rect, rendered FOV,
  crosshair fallback) and the `Block.OUTLINE` / `Fluid.NONE` clip. Shared by the pivot pick and the
  hovered-block highlight so the two name the same block for the same pixel.
- `input/WorldHover.kt` (client) — the live cursor position and the hit result the hovered-block
  highlight installs, cast from the *render* camera. Read by `mixin/client/MinecraftPickMixin.java`,
  whose aimed hit only draws because `mixin/client/GameRendererOutlineMixin.java` defeats vanilla's
  spectator outline gate; see [ui/cursor-block-targeting.md](../ui/cursor-block-targeting.md).

See [ui/dock-input-routing.md](../ui/dock-input-routing.md) for how `dock/input/DockInputRouter.kt`
feeds world-viewport gestures into the controller.

## `mixin/` (main, Java) and `mixin/client/` (client, Java)

These packages did **not** move — the mixin JSONs pin them by name.

Server-side: `ConnectionAccessor`, `ServerCommonPacketListenerImplAccessor`,
`ServerLevelPhaseMixin`, `ServerLevelSetBlockMixin`. Client-side:
`ClientCommonPacketListenerImplAccessor`, `GameRendererOutlineMixin`, `KeyboardHandlerMixin`,
`MinecraftFrameMixin`, `MinecraftPickMixin`, `MinecraftPresentMixin`, `MouseHandlerMixin`,
`MouseHandlerViewportMixin`, `TitleScreenMixin`, `WindowMixin` — the GLFW-input and
viewport-shrink/composite mixins backing `dock/`, plus `MinecraftFrameMixin`'s per-frame
orbit-camera commit (see [ui/orbit-camera.md](../ui/orbit-camera.md)) and the two halves of the
free-cursor block highlight — `MinecraftPickMixin` aims the pick, `GameRendererOutlineMixin` stops
vanilla's spectator rule from suppressing the draw (see
[ui/cursor-block-targeting.md](../ui/cursor-block-targeting.md)). See
[architecture/shrink-viewport-compose-model.md](shrink-viewport-compose-model.md) and
[ui/dock-input-routing.md](../ui/dock-input-routing.md).

## Test source sets

Every test sits in the **same package as the code it covers**; the source set alone says what kind
of test it is. The old `test.` / `client.` infix segments are gone.

- `src/test/kotlin/…` — pure JUnit/Kotest, mirroring both the main and client package trees
  (`core/spec/`, `dock/shell/`, `editor/explorer/ui/`, …). Carries `client`'s compile classpath so
  pure-JVM client-code specs live here. See
  [gametest/unit-vs-gametest-split.md](../gametest/unit-vs-gametest-split.md).
- `src/gametest/kotlin/…` — server-side Kotest specs mirroring the feature tree
  (`editor/explorer/network/`, `editor/structure/ops/`, `editor/workspace/world/`, …), driven by a
  single `@GameTest` sentinel (`test/GametestSentinel.kt`, task `runGameTest`). Support that serves
  more than one feature stays at `com.breadmoirai.garnet.test` (`NetworkTestSupport`, `SmokeSpec`);
  `editor/EditorTestSupport.kt` serves all of `editor/`.
- `src/clientTest/kotlin/…` — client-side Kotest specs under `dock/shell/`, `dock/viewport/`,
  `dock/input/`, `editor/explorer/ui/` and `camera/`, driven by `test/ClientTestSentinel.kt`
  (task `runClientTest`), with
  `test/ClientTestSupport.kt`, `test/SpecTestContext.kt` and `dock/shell/PanelPixelProbe.kt`.
- `src/testSupport/kotlin/…/harness/` — the Kotest bridge itself, not tests, so it keeps its
  `harness/` name: `GarnetTestSpec`, `GarnetTestSpecContext`, `ClientSpec`, `RecordingHolder`,
  `RunGarnetSpec.kt`, `client/` (`ClientContextHolder`, `FabricTestThreadPump`, `WorldHolder`),
  `launcher/` (`KotestLauncher`, `ResultCollector`, `DiagnosticRecorderListener`). This source set
  does not ship in the mod jar; Kotest is a dependency only here, not on `main`. See
  [gametest/kotest-bridge.md](../gametest/kotest-bridge.md).

Both sentinels pass their spec list to the Kotest engine as **explicit `KClass` references**, not
by classpath scanning — so a spec that stopped being discovered would be a compile error, and the
runtime discovery risk is confined to the sentinel classes themselves being found by `@GameTest` /
`FabricClientGameTest` annotation scanning.

## Dependency direction

```
core/  →  playback/  →  testing/  →  editor/
                            dock/  →  editor/
```

`core/` is the leaf — it imports nothing else in the project, and `core/spec/` is the leaf inside
`core/`. `playback/` consumes only `core/`. `testing/` consumes `playback/` and `core/`. `dock/`
consumes `core/` and nothing else of the mod's own. `editor/` is the top of the stack and consumes
everything; nothing outside `editor/` depends on `editor/`.

Inside `editor/`, the rule is stated at **(sub-feature, layer)** granularity, and the graph is
meant to be a DAG. It is stated in **three tiers**, `data` at the bottom and `network`/`ui` at the
top:

- **`<sub>/data` — the leaf tier.** May import other `data` nodes and nothing else in `editor/`.
  Every node may import any `data`.
- **`<sub>/ops` — the middle tier.** May import any `data`, `workspace/world` (the shared world
  substrate, see below), and the `ops` of its own sub-feature or of one earlier in the order
  **explorer → structure → history → undo → workspace**. **`ops` may not import `network` or
  `ui`** — this is the one direction the rule genuinely forbids.
- **`<sub>/network` and `<sub>/ui` — the top tier.** May import anything below them, the
  `editor/network/` spine, and *each other*: `ui → network` (a panel sending its own sub-feature's
  payloads, or another's), `ui → ui` (cross-panel state sharing), and `network → network`
  (handler-to-handler reuse) are all permitted. Nothing below this tier may import into it.
- **The `editor/network/` spine and the two entrypoints** (`workspace/command/`, `Garnet` /
  `GarnetClient`) are licensed to import anything.

`workspace/world` is deliberately *not* one of the three tiers. It is a shared substrate —
`EditorDimRegistry`, `EditorRootResolver` — that every sub-feature's `ops` reads, and treating it
as `workspace`'s `ops` (and therefore last in the sub-feature order) was a vocabulary gap in the
rule rather than a real constraint.

> **The tree satisfies this rule with 11 known exceptions**, all of them `ops → network`, all of
> them recorded by name below. See [the full count](#the-count-what-the-rule-costs-today).

### Why the top tier is permissive

An earlier statement of this rule made `network` and `ui` *tops that nothing may import*, including
each other. That was never how the client worked. A panel sends its own sub-feature's C2S payloads
and mirrors its S2C ones directly, with no indirection layer between them and none ever intended —
that shape alone accounted for 27 of the 60 "violations" the previous audit reported. `ui → ui`
(9 edges) is `ExplorerLifecycle` resetting every panel's state on mount and `LocalHistoryPanel` /
`StructureInfoPanel` sharing `explorer/ui`'s `formatClock` so their timestamps agree — the reason
`TimeFormat.kt` exists at all. `network → network` (6) is handler-to-handler reuse, e.g. a file-op
that relocates a `.nbt` calling `EditorStructureHandlers.placeStructureFrom` rather than
duplicating its ordering rules.

None of those is debt. A rule that reports the intended design as a violation is a broken rule, not
a broken codebase, so the rule was restated to describe the tree that exists. What it still forbids
— `data` or `ops` reaching upward into the wire and the screen — is the constraint that was
actually worth having, and it is the one the remaining exceptions are measured against.

That is what makes the structure/history relationship legal rather than cyclic: `structure/ops`
(`StructureCommit`) imports `history/data` (`LocalHistoryStore`), while `history/ops`
(`StructureRestoreOps`) imports `structure/ops` (`StructureCommit`) — two distinct nodes, one
direction each.

### The recorded exception

`undo/ops/EditorUndoOps.kt` imports `explorer/network` (`EditorFileOpsHandlers`, `DeleteOutcome`,
`EditorFolderLoadedS2C`) and the `editor/network/` spine (`EditorHandlerSupport`), because undoing
a file operation replays it through the very handlers the client would have invoked — `relocate`,
`deleteSubtree`, `restoreSubtree` — rather than through hand-rolled file IO. Those functions carry
the ordering rules that make the operation reversible, so duplicating them would be the worse
choice. This is a genuine `ops → network` edge against the grain, written down here rather than
hidden.

### The count: what the rule costs today

Every import in `src/main/kotlin` and `src/client/kotlin` whose source and target are both
`editor/<sub-feature>/<layer>` nodes, classified against the rule above. Method, to re-run it: for
each file take its `(sub-feature, layer)` from its path; for each `import
com.breadmoirai.garnet.editor.…` line take the target's `(sub-feature, layer)` from the import
path; drop same-node edges; apply the four bullets, with the `editor/network/` spine and the
entrypoints (`workspace/command/`, `Garnet`, `GarnetClient`) licensed as sources.

```
editor-internal cross-node import edges : 226
  permitted by the rule                 : 218
  VIOLATIONS                            :   8
```

**All 8 are `ops → network`** — the one direction the rule forbids — and every one is named here:

| Source | Target | Imports | Why |
|---|---|---:|---|
| `undo/ops/EditorUndoOps.kt` | `editor/network` spine (`EditorHandlerSupport`) | 4 | The recorded exception below. |
| `undo/ops/EditorUndoOps.kt` | `explorer/network` (`EditorFileOpsHandlers`, `DeleteOutcome`, `EditorFolderLoadedS2C`) | 3 | The recorded exception below. |
| `history/ops/StructureRestoreOps.kt` | `editor/network` spine (`EditorHandlerSupport.commitDirtyUnder`) | 1 | Same reason as the undo case: restoring a revision must flush dirty structures through the shared helper that carries the commit ordering, not a copy of it. |

This used to be 11. `StructureCommit` accounted for three of them, because `broadcast` — a
networking function — lived in `ops`: it imported `StructureAutoSavedS2C`, `toAutoSavedPayload` and
`history/network`'s `HistoryWatchers`, and `tick`/`commitAll` called it. That is now fixed rather
than excused. `broadcast` moved to `network/StructureSync`, and `tick`/`commitAll` return their
committed structures for the network layer to announce, so `StructureCommit` imports no network
package at all.

The remaining 8 are the recorded exception and its sibling, and are deliberate: both replay a file
operation through the very handlers the client would have invoked, and duplicating those handlers'
ordering rules would be the worse trade.

#### History: what the previous audit reported

An earlier version of this section reported **60 violations of 228 edges**. That number was
measured against the stricter rule ("nothing imports `network`/`ui`"), and 49 of the 60 were shapes
that rule got wrong rather than code that was wrong: 27 `ui → network`, 9 `ui → ui`, 6
`network → network`, 7 `ops → workspace/world`. Restating the rule removed all of those from the
count without moving a line of code. One more — `data → network`, `CommitOutcome.Committed`
carrying the `StructureAutoSavedS2C` payload it would be broadcast as — was a genuine layering
inversion and was **fixed**: `Committed` now carries `CommittedStructure`, with the wire mapping at
the network boundary.

Worth keeping from that audit, because it stays true: **none of those 60 was introduced by the
sub-package restructure.** Checked against the branch point `2f823a3` by indexing where every
symbol was declared in the old flat tree, **46 were already cross-package imports** before the move
and **14 were intra-package references** the old flat layout made invisible — every `ui → ui` edge,
and 5 of the 6 `network → network` ones, because all panels then lived in one flat `editor.ui`
package and all handlers in one flat `editor.network` one. **Zero were new couplings.** The
restructure did not add coupling; it made pre-existing coupling legible for the first time, and
this section is what that legibility bought.

## Where to start reading

- *"How does a spec get from the world onto disk?"* — there is currently no in-game trigger.
  Read `playback/recorder/StateRecorder.kt` → `playback/recorder/RecordingDslEmitter.kt` →
  `testing/data/SpecPersistence.kt` for the intact pipeline, and see
  [recording-pipeline.md](recording-pipeline.md) for why it has no caller today.
- *"How is a spec replayed and verified?"* → `testing/runner/runGarnetSpec.kt`; the spec's `block`
  lambda fires inputs and asserts outputs inline. Called from
  `testSupport/harness/GarnetTestSpec.kt` and gametest/clientTest specs today. See
  [runner/engine-driven-verification.md](../runner/engine-driven-verification.md).
- *"How does the Explorer work?"* → `editor/explorer/network/EditorTreeHandlers.kt` and
  `EditorFileOpsHandlers.kt` (server handlers), `editor/explorer/network/ExplorerPackets.kt` (the
  wire types), and `editor/explorer/ui/ExplorerPanel.kt` (the client panel); see
  [redstone-project.md](redstone-project.md).
- *"Where did `EditorPackets.kt` go?"* → it split four ways along the sub-feature seam:
  `editor/explorer/network/ExplorerPackets.kt`, `editor/structure/network/StructurePackets.kt`,
  `editor/history/network/HistoryPackets.kt`, `editor/undo/network/UndoPackets.kt`, with the shared
  `payloadId()` helper in `editor/network/PayloadIds.kt`.
- *"Why is the GUI structured this way?"* → the legacy `RecorderScreen`/`RunnerScreen`/
  `ProjectScreen` were hard-cut in favor of a full-window Compose dock; start at
  [ui/dock-framework.md](../ui/dock-framework.md) and
  [ui/dock-input-routing.md](../ui/dock-input-routing.md).
