# Feature-based package layout

**Date:** 2026-07-31
**Status:** Design approved, plan pending

## Problem

`com.breadmoirai.garnet` is packaged by technical layer — `dsl/`, `runner/`, `persistence/`,
`network/`, `block/`, `item/` on the server and `client/{ide,render,screen,state,ui,viewport}`
on the client. Touching one user-facing capability means editing six packages, and no package
tells you what capability it serves.

Three problems compound it:

1. **`testing/` is misnamed.** It holds the Kotest-in-Minecraft harness, but `Garnet.onInitialize`
   calls `GarnetTestLifecycle.register()` — the *product* registers those tick pumps. Half the
   package is live server infrastructure wearing a test name; the other half is genuinely
   dev-only and has no business shipping.
2. **The pre-dock workflow is still in the tree.** The recorder/runner screens were hard-cut for
   the Compose dock, but the blocks, block entity, marker tools, and their entire wire protocol
   remain — driving a UI that no longer exists. They dominate the package graph while serving
   no user-reachable flow.
3. **The client tree is a fourth layer axis.** `client/ide` and `main/project` are two halves of
   one capability, split across sourcesets *and* across unrelated package roots.

## Goals

- Package by capability: `playback`, `testing`, `editor`, each with `data` / `ui` / `network`
  style subpackages.
- Shared infrastructure lives in named base packages, not in whichever feature happened to
  need it first.
- Kotest ships in no jar the player loads.
- Strict, acyclic dependency direction that a reader can state from memory.
- Remove the pre-dock in-world surface so the editor can become the sole driver.

## Non-goals

- No new features. The recorder and runner panels are out of scope; this makes room for them.
- No Stonecutter/multi-version work. Single MC 26.2 slice throughout.
- No rework of the record/run engine itself. It survives untouched, minus its callers.

## Post-state

After this work the mod can open a redstone-project workspace, browse and edit the file tree,
create specs, folders, and structures, and place structures into the world. It **cannot record
or run a spec from inside the game** — the engine is intact and fully test-covered, but its only
entry point (the runner/recorder blocks) is gone until the dock panels are built.

This is deliberate and is the direct continuation of cutting the screens. It is the single
biggest consequence of this spec.

---

## Target layout

### Base packages

```
com.breadmoirai.garnet/
  Garnet.kt                 mod entrypoint
  GarnetClient.kt           client entrypoint
  ModRegistries.kt          registry handles (much reduced — see Trim)

  spec/                     the spec DSL — shared vocabulary of testing and playback
  mc/                       MC coroutine + tick plumbing
  structure/                NBT structure templates + region geometry
  config/                   shared + client config
  ui/                       the Compose dock shell every panel mounts into
  mixin/                    unchanged — the mixin JSONs pin this package
```

`spec/` is the leaf: it imports nothing from the project. `mc/` is the rename that unblocks
everything — it is `testing/core` + `testing/server` with the "Test" removed from the names,
because it was never test-only.

### Feature packages

```
  playback/         capture, emit, store, replay world state
    recorder/       StateRecorder, RecordingDslEmitter
    data/           StateRecording, StateRecordingStorage, StateRecordingView, RecordingSidecar

  testing/          run a spec and verify it
    runner/         runGarnetSpec (engine), SpecSnapshot, PlayerInteractionDispatch
    data/           SpecPersistence, KtsSpecLoader, SpecScript, SpecDirectoryScan, LoadedSpec

  editor/           workspace, project model, explorer
    data/           pure model — no server side effects, unit-testable
    ops/            filesystem-mutating create operations (new spec / new structure)
    world/          dimension/grid substrate — server side effects
    structure/      live structure-commit pipeline — dirty-track → debounce → commit → history
    command/        EditorCommand
    network/        payloads + registration (main) and client sender (client)
    ui/             the Project Explorer
```

`playback/` and `testing/` have no `ui/` and no `network/` after the trim — the panels and their
wire protocol do not exist yet. Those subpackages are created when the panels are, not
speculatively.

`editor/` splits `data` from `world` because a flat `editor.data` would hold 20 files, which is
the problem this restructure exists to fix. The split is along a real seam: `data` is pure and
already has unit tests; `world` mutates dimensions and is gametest-covered.

> **Amendment (2026-08-02):** the two-way `data`/`world` split above was later widened to three.
> `editor/world/` had grown to hold two unrelated subsystems: the dimension/grid substrate
> (`EditorWorld`, `EditorDimRegistry`, `EditorDimLifecycle`, `EditorServerContext`,
> `EditorTeleport`, `GridLayout`, `EditorCellSaver`, `EditorRootResolver`) and the live
> structure-commit pipeline (`StructureAutoSave`, `StructureEditWatcher`, `StructureCommit`). The
> commit pipeline moved to its own `editor/structure/` package: `data` stays pure, `world` stays
> the dimension/grid substrate, and `structure` is the dirty-track → debounce → commit → history
> pipeline. This is a deliberate, approved deviation from this spec's original two-way split — see
> `docs/superpowers/plans/2026-08-02-refactor-package-cohesion.md` for the rationale. `editor/structure`
> is distinct from the pre-existing top-level `structure/` package (pure NBT/region geometry, no
> server state) documented above.

> **Amendment (2026-08-02):** `editor/data/` held two files that violated this package's own
> "pure model — no server side effects" description: `EditorNewSpec` and `EditorNewStructure`
> both create files on disk. They moved to a new `editor/ops/` package. `EditorNames` and
> `EditorSaveNaming` were checked against the same criterion and confirmed to do no filesystem
> IO (string/hash logic only), so they stayed in `data/`. See
> `docs/superpowers/plans/2026-08-02-refactor-package-cohesion.md` (Task 5) for the rationale.

### Dependency direction

```
spec/ mc/ structure/ config/ ui/     ← base, no feature imports
       ↑
   playback/                          ← imports spec/, structure/
       ↑
   testing/                           ← imports playback/, spec/
       ↑
   editor/                            ← imports testing/, playback/, structure/, ui/
```

Strictly one direction. `testing.runner.runGarnetSpec` drives a `playback.recorder.StateRecorder`
and returns a `playback.data.StateRecording`, so testing sits *above* playback — this is why
the DSL cannot live under `testing/`.

Features never import another feature's `ui`. Cross-feature reach goes through `data`.

### Seam fixes

An import audit of `src/main` + `src/client` found five violations of the direction above. Two
are resolved by the trim, since the offending files are deleted (`SpecBlockEntity` reaching into
both features; `SpecMarkerTool` type-checking `GarnetRunnerBlock`). Three remain:

| Violation | Resolution | Phase |
| --- | --- | --- |
| `StructurePersistence` → `project.PlacedBox`, `autoFit`, `anchorY`, `centeredStart` | these are structure-region geometry, not editor concerns. `StructureRegionMath.kt` → `structure/`; `PlacedBox` extracted out of `ProjectDimRegistry.kt` into `structure/` | 4 |
| `ui.DockKeybinds` → `editor` Explorer state | the file does two jobs. Keybind registration stays in `ui/viewport/DockKeybinds.kt`; `registerDockWorldLifecycle`'s Explorer-state reset moves to `editor/ui/ExplorerLifecycle.kt` | 6 |
| `spec.ConditionEvaluator` → `playback.StateRecordingView` | **code change.** `SpecRun` already declares `StateRecordingViewLike` for this purpose; `StateRecordingView` does not implement it. Make it implement the interface and widen `ConditionEvaluator`'s parameter to the interface type | 4 |

The `ConditionEvaluator` fix is the only non-mechanical edit in phases 3–6, and it is the one
that makes `spec/` a true leaf.

### Client sourceset

The `client` segment is dropped from package names. `editor.ui` lives in `src/client`,
`editor.data` in `src/main`, same package root.

**Known trap:** Kotlin `internal` is per-sourceset, not per-package. Two files in
`com.breadmoirai.garnet.editor` in different sourcesets cannot see each other's `internal`
members despite sharing a package. Anything `src/client` needs from `src/main` must be
`public`. This already bites in this repo; the new layout makes the same-package case look
like it should work when it does not.

---

## Kotest leaves `main`

New sourceset `src/testSupport/kotlin`, package `com.breadmoirai.garnet.harness`:

```
  harness/
    GarnetTestSpec, GarnetTestSpecContext, ClientSpec
    RecordingHolder, runGarnetSpec (the recording-capturing wrapper)
    launcher/   launchKotest, ResultCollector, DiagnosticRecorderListener
    client/     FabricTestThreadPump, WorldHolder, ClientContextHolder
```

It must be its own sourceset rather than folded into `gametest` or `clientTest`: `launchKotest`,
`GarnetTestSpec`, `FabricTestThreadPump`, and `WorldHolder` are all used by **both**, and those
are sibling sourcesets that see only `main` + `client`.

Build wiring required:

- `sourceSets.create("testSupport")` with `compileClasspath`/`runtimeClasspath` including
  `main` + `client` output and their compile classpaths.
- `gametest`, `clientTest`, and `test` gain `testSupport` output on both classpaths.
- The `gametest` and `clientTest` Loom run configs gain `sourceSet(testSupportSourceSet)` so
  the classes are on the game's runtime classpath.
- `testSupport` is excluded from the Compose compiler plugin (it contains no `@Composable`),
  matching how `main`/`test`/`gametest` are already filtered.
- `testSupport` must **not** be a `remapJar` input — it never ships.

What stays in `main` as `garnet.mc`: `McDispatchers`, `ServerThreadDispatcher`,
`serverTickStart`/`serverTickEnd`, `McLifecycle` (was `GarnetTestLifecycle`), `awaitTickEnd`,
`awaitTicks`, `onServer`, `SubTickPhaseEvents`. None of it references Kotest.

---

## Trim

All of this happens in phase 1, before anything moves.

### The in-world entry surface

The blocks, the block entity, the marker tools, and their whole wire protocol go.

| Deleted | Notes |
| --- | --- |
| `block/GarnetRecorderBlock.kt`, `GarnetRunnerBlock.kt`, `SpecBlockEntity.kt` | the entire `block/` package |
| `item/SpecMarkerTool.kt`, `UndoStack.kt` | the entire `item/` package, incl. `InputSpecMarkerItem` / `OutputSpecMarkerItem` |
| `network/Packets.kt`, `network/NetworkRegistry.kt` | every payload is addressed to a block entity by `originPos`; with no BE, none has a referent |
| `client/network/ClientNetworkHandler.kt` | receives only the deleted payloads |
| `client/render/GarnetBoundsRenderer.kt`, `HudOverlayRenderer.kt` | render BE bounds and marker HUD |
| `client/SpecBoundsInteraction.kt` | zero references already |
| `client/state/ClientRunnerState.kt` | zero references already |
| `ModRegistries` block, block-item, BE-type, and marker-item registrations | leaves `ModRegistries` nearly empty; delete the file if nothing survives |
| `Garnet.onInitialize`: `registerNetworking()`, `registerAttackCallback()`, `registerUseBlockCallback()` | the marker-tool interaction callbacks |
| `gametest/.../recorder/MarkerToolSpec.kt`, `RecordingLifecycleSpec.kt` | drive the deleted blocks/tools |
| `gametest/.../network/RecorderRunnerNetworkRegistrySpec.kt` | asserts on the deleted payloads |
| `clientTest/.../ClientNetworkSpec.kt`, `ClientNetworkTestSupport.kt` | same |
| Sentinel registrations for all of the above | `GametestSentinel` and `ClientTestSentinel` explicit spec lists |

**Assets.** `blockstates/{garnet_recorder,garnet_runner}.json`, the `models/block/`,
`models/item/`, and `items/` entries for both blocks and all four markers,
`textures/block/garnet_recorder.png`, `textures/item/{input,output,auto,breakpoint}_spec_marker.png`,
and the matching `block.garnet.*` / `item.garnet.*` lang keys.

The audit also found assets that are *already* orphaned and should go in the same pass:
`garnet_editor` blockstate/model/item/texture (no such block class exists),
`auto_spec_marker` and `breakpoint_spec_marker` (registered nowhere), and every
`screen.garnet.*` lang key (the screens were cut).

### What survives, and why

- **`StateRecorder` and the whole recording pipeline.** The recorder is driven by a static
  `StateRecorder.activeRecorders()` registry, not by block entities. `Garnet.onInitialize`'s
  `SubTickPhaseEvents.PHASE` handler and `ServerLevelSetBlockMixin` both dispatch through that
  registry and are unaffected. `runGarnetSpec` activates its own recorder.
- **`runGarnetSpec`, the DSL, the emitter, persistence.** These have no product caller after
  the trim. That is intentional — they are the engine the panels will drive, and they carry
  substantial unit and gametest coverage. They are **not** to be treated as dead code by the
  same standard applied to the deletions above.
- **`RecordingDslEmitter`'s `markers` parameter.** Nothing produces `EntryMarker`s once the
  marker tool is gone. The parameter and the emit path stay; supplying markers becomes the
  editor's job.

### Independent dead code

| Target | Reason |
| --- | --- |
| `KtsSpecLoader.loadSpec` / `loadFile` / `findFirstSpecClass` | legacy Kotest script form, zero callers; removing them drops the `io.kotest` import from `main` |
| `SpecScript` `defaultImports` for `GarnetTestSpec`, `runGarnetSpec`, `awaitTicks`, `awaitTickEnd`, `spawnStructure` | pre-imports for a script form nothing emits |
| `testing/server/Structures.kt` (`StructureGrid`, `StructureHandle`, slot acquire/release) | zero callers |

---

## File mapping

### `src/main/kotlin` → base

| From | To |
| --- | --- |
| `dsl/GarnetSpec.kt`, `SpecRun.kt`, `InputScope.kt`, `OutputScope.kt`, `ConditionScope.kt`, `StateCondition.kt`, `ConditionEvaluator.kt`, `SpecTime.kt` | `spec/` |
| `testing/core/Dispatchers.kt` | `mc/Dispatchers.kt` |
| `testing/core/Ticks.kt` | `mc/Ticks.kt` |
| `testing/core/Lifecycle.kt` (`GarnetTestLifecycle` → `McLifecycle`) | `mc/McLifecycle.kt` |
| `testing/server/Suspending.kt` | `mc/Suspending.kt` |
| `event/SubTickPhaseEvents.kt` | `mc/SubTickPhaseEvents.kt` |
| `persistence/StructurePersistence.kt`, `StructureDiff.kt` | `structure/` |
| `project/StructureRegionMath.kt` (`autoFit`, `centeredStart`, `anchorY`) | `structure/` — see seam fixes |
| `PlacedBox` (extracted from `project/ProjectDimRegistry.kt`) | `structure/PlacedBox.kt` |
| `config/SharedSettings.kt` | `config/` (unchanged) |

### `src/main/kotlin` → `playback/`

| From | To |
| --- | --- |
| `runner/StateRecorder.kt` | `playback/recorder/` |
| `runner/RecordingDslEmitter.kt` | `playback/recorder/` |
| `runner/StateRecording.kt`, `StateRecordingStorage.kt`, `StateRecordingView.kt` | `playback/data/` |
| `persistence/RecordingSidecar.kt` | `playback/data/` |

### `src/main/kotlin` → `testing/`

| From | To |
| --- | --- |
| `runner/runGarnetSpec.kt`, `SpecSnapshot.kt`, `PlayerInteractionDispatch.kt` | `testing/runner/` |
| `persistence/SpecPersistence.kt`, `KtsSpecLoader.kt`, `SpecScript.kt`, `SpecDirectoryScan.kt` | `testing/data/` |
| `project/LoadedSpec.kt` | `testing/data/` |

### `src/main/kotlin` → `editor/` (with `Project` → `Editor` rename)

| From | To |
| --- | --- |
| `project/ProjectRoot.kt` | `editor/data/EditorRoot.kt` |
| `project/ProjectSession.kt` | `editor/data/EditorSession.kt` |
| `project/ProjectCell.kt` | `editor/data/EditorCell.kt` |
| `project/ProjectNames.kt` | `editor/data/EditorNames.kt` |
| `project/ProjectSaveNaming.kt` | `editor/data/EditorSaveNaming.kt` |
| `project/ProjectNewSpec.kt` | `editor/ops/EditorNewSpec.kt` (moved from `editor/data/` 2026-08-02, see amendment below) |
| `project/ProjectNewStructure.kt` | `editor/ops/EditorNewStructure.kt` (moved from `editor/data/` 2026-08-02, see amendment below) |
| `project/ProjectFolderTree.kt` | `editor/data/EditorFolderTree.kt` |
| `project/FileTree.kt` | `editor/data/FileTree.kt` |
| `project/ProjectDimRegistry.kt` | `editor/world/EditorDimRegistry.kt` |
| `project/ProjectDimLifecycle.kt` | `editor/world/EditorDimLifecycle.kt` |
| `project/ProjectWorld.kt` | `editor/world/EditorWorld.kt` |
| `project/ProjectCellSaver.kt` | `editor/world/EditorCellSaver.kt` |
| `project/ProjectTeleport.kt` | `editor/world/EditorTeleport.kt` |
| `project/ProjectServerContext.kt` | `editor/world/EditorServerContext.kt` |
| `project/GridLayout.kt` | `editor/world/GridLayout.kt` |
| `project/ProjectCommand.kt` | `editor/command/EditorCommand.kt` |
| `network/project/ProjectPackets.kt` | `editor/network/EditorPackets.kt` |
| `network/project/ProjectNetworkRegistry.kt` | `editor/network/EditorNetworking.kt` |

Payload **class** names follow the prefix rename (`ProjectTreeSnapshotS2C` →
`EditorTreeSnapshotS2C`). Payload **`Identifier` strings stay unchanged** — client and server
ship in one jar, so there is no compatibility need, and leaving them alone keeps the diff
honest about what changed.

Two names keep `Project` deliberately: the `/garnet project …` command literal (user-facing)
and `docs/architecture/redstone-project.md`'s "redstone project" concept (the domain term for
a folder of specs). The *classes* are `Editor*`; the *concept* is still a project.

With `NetworkRegistry.kt` deleted, `editor/network/EditorNetworking.kt` is the **only** payload
registration left. `Garnet.onInitialize` calls it directly.

### `src/client/kotlin`

| From | To |
| --- | --- |
| `client/ui/compose/*` | `ui/compose/` |
| `client/ui/compose/dock/*` | `ui/dock/` |
| `client/ui/compose/input/*` | `ui/input/` |
| `client/viewport/*` | `ui/viewport/` |
| `client/viewport/DockKeybinds.kt` — `registerDockWorldLifecycle`'s Explorer reset only | `editor/ui/ExplorerLifecycle.kt` — see seam fixes |
| `client/screen/GarnetIconButton.kt` | `ui/widget/` |
| `client/config/ModConfig.kt`, `ModMenuIntegration.kt` | `config/` |
| `client/ide/*` (9 files) | `editor/ui/` |
| `client/project/ProjectClientNetworking.kt` | `editor/network/EditorClientNetworking.kt` |
| `client/project/ProjectIntegratedBoot.kt` | `editor/world/EditorIntegratedBoot.kt` |
| `client/GarnetClient.kt` | stays at root next to `Garnet.kt` |

`src/client/java` is unchanged — the mixin JSON pins `com.breadmoirai.garnet.mixin.client`, and
`WindowViewportExt` belongs with it.

### Test sourcesets

`src/test`, `src/gametest`, and `src/clientTest` mirror the feature layout:
`test/project/*` → `test/editor/*`, `test/persistence/*` splits between `test/testing/` and
`test/structure/`, `test/runner/*` splits between `test/playback/` and `test/testing/`.
`test/recorder/` and `test/network/` are deleted with the code they cover.

`GametestSentinel`'s explicit spec list and `ClientTestSentinel`'s must be updated in the same
commit as any spec move or deletion — autoscan is disabled, so an unregistered spec silently
does not run.

---

## Sequencing

Each phase is one commit on `main`, gated on the full build:
`clientClasses classes gametestClasses clientTestClasses testClasses`, plus `test`,
`runGameTest`, and `runClientTest`.

| Phase | Content |
| --- | --- |
| 1 | Trim: the in-world entry surface, its assets and tests, and the independent dead code. No moves, no renames. |
| 2 | `testSupport` sourceset + build wiring; move the Kotest-coupled classes out of `main`. |
| 3 | `testing/core` + `testing/server` + `event/` → `garnet.mc`; `GarnetTestLifecycle` → `McLifecycle`. |
| 4 | Base packages: `spec/`, `structure/`, `ui/`, `config/`. Includes the `StateRecordingViewLike` and `StructureRegionMath`/`PlacedBox` seam fixes. Update `RecordingDslEmitter`'s emitted import line and `SpecScript.defaultImports` to `garnet.spec.*`. |
| 5 | `playback/`, then `testing/`. |
| 6 | `editor/`, including the `Project` → `Editor` rename and the `DockKeybinds` split. |
| 7 | Docs sync. |

Phase 1 is by far the largest diff and the only one with behavior change. Phases 3–6 run in
dependency order so each lands on an already-moved base.

Phase 4 is the only one with a user-visible artifact change: every `.spec.kts` file's first line
becomes `import com.breadmoirai.garnet.spec.*`. No `.spec.kts` files are committed to the repo,
and `RecordingDslEmitter` regenerates the line, so existing files are only affected in a
developer's local world save.

## Verification

Per phase:

1. Compile all sourcesets (six after phase 2).
2. `:26.2:test` unfiltered, then read the XML report — the `--tests` filter does not work with
   Kotest and reports a false "no tests found".
3. `runGameTest` and `runClientTest` in the foreground with a 600 s timeout. `clientTest` XML
   reports are always empty; read the log for the sentinel's `LauncherResult.summary()`.
4. `grep -rn "<OldName>" docs/ src/` returns zero hits referring to the old role.

Phase 1 additionally needs a manual check: launch the client, open a redstone project, and
confirm the Explorer still lists, creates, renames, and places — that is the entire remaining
product surface, and it must be unaffected.

Phases 3 and 5–6 are mechanical: package/import rewrites with no logic edits. Any behavior diff
in a mechanical phase is a bug in the move. Phase 4 carries two seam fixes and phase 2 carries
build wiring, so those need real review.

## Docs impact

`docs/architecture/module-map.md` is rewritten wholesale — it is a package-by-package tour and
every entry changes, including several that now describe deleted code (it already cites a
`block/SpecBlockKind.kt` that does not exist).

Phase 1 invalidates whole articles rather than lines: anything documenting the recorder/runner
blocks, the marker tool, or the C2S/S2C payload contract. `docs/persistence/network-payload-contract.md`
loses its subject entirely — the `originPos` trust anchor it describes goes with the BE.
`docs/architecture/recording-pipeline.md` needs its entry point rewritten to say the engine has
none. `docs/use-cases/` entries covering record-from-block flows are no longer reachable
journeys and should be marked as such rather than silently left.

Phases 3–6 are name changes: `docs/gametest/` articles name `GarnetTestSpec`, `launchKotest`,
`FabricTestThreadPump`; `docs/architecture/redstone-project.md` names the `Project*` classes.

A full `grep -rn` sweep for every deleted and renamed symbol is part of phase 7, not deferred.

## Open risks

- **Phase 1 leaves the mod without a record/run entry point.** See Post-state. Everything else
  in this spec is reversible refactoring; this is not.
- **Phase 2 is the riskiest structural phase.** A new sourceset that must be on the game's
  runtime classpath for two different Loom run configs, excluded from Compose, and excluded
  from the shipped jar. If it resists, the fallback is to keep the harness in `main` behind a
  `garnet.harness` package and accept that Kotest ships — the layout benefit survives, only
  the jar-size/purity benefit is lost.
- **Gametest coverage drops sharply in phase 1.** `MarkerToolSpec`, `RecordingLifecycleSpec`,
  `RecorderRunnerNetworkRegistrySpec`, and `ClientNetworkSpec` are deleted, and they are the
  only end-to-end exercise of the record→emit→run path through a real world. What remains is
  `RunGarnetSpecSmokeTest` plus unit tests. Rebuilding that coverage against the editor-driven
  path is a follow-up, not part of this spec.
- **Same-package-across-sourcesets `internal`.** Expect compile errors in phases 5–6 where
  `src/client` code reaches `src/main` code that is now nominally in the same package. The fix
  is promoting to `public`, not restructuring further.
