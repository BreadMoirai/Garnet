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
2. **Dead code hides the boundaries.** The recorder/runner screens were hard-cut for the Compose
   dock and left orphaned payloads, no-op receivers, and a legacy Kotest script-loading path
   with zero callers. It is impossible to see which packages actually depend on which.
3. **The client tree is a fourth layer axis.** `client/ide` and `main/project` are two halves of
   one capability, split across sourcesets *and* across unrelated package roots.

## Goals

- Package by capability: `testing`, `playback`, `editor`, each with `data` / `ui` / `network`
  style subpackages.
- Shared infrastructure lives in named base packages, not in whichever feature happened to
  need it first.
- Kotest ships in no jar the player loads.
- Strict, acyclic dependency direction that a reader can state from memory.

## Non-goals

- No behavior change beyond what the dead-code trim removes.
- No new features. The recorder and runner panels are out of scope; this makes room for them.
- No Stonecutter/multi-version work. Single MC 26.2 slice throughout.

---

## Target layout

### Base packages

```
com.breadmoirai.garnet/
  Garnet.kt                 mod entrypoint
  ModRegistries.kt          registry handles

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
    ui/             GarnetBoundsRenderer, HudOverlayRenderer, SpecBoundsInteraction

  testing/          run a spec and verify it
    runner/         runGarnetSpec (engine), SpecSnapshot, PlayerInteractionDispatch
    data/           SpecPersistence, KtsSpecLoader, SpecScript, SpecDirectoryScan, LoadedSpec
    ui/             (empty — the runner panel lands here)

  anchor/           the in-world surface: blocks, block entity, marker tool
    block/          GarnetRecorderBlock, GarnetRunnerBlock, SpecBlockEntity
    item/           SpecMarkerTool, UndoStack
    network/        recorder + runner payloads + registration
    ui/             (client) ClientNetworkHandler — the surviving OverwritePrompt receiver

  editor/           workspace, project model, explorer
    data/           pure model — no server side effects, unit-testable
    world/          dimension/grid substrate — server side effects
    command/        EditorCommand
    network/        payloads + registration (main) and client sender (client)
    ui/             the Project Explorer
```

`editor/` splits `data` from `world` because a flat `editor.data` would hold 20 files, which is
the problem this restructure exists to fix. The split is along a real seam: `data` is pure and
already has unit tests; `world` mutates dimensions and is gametest-covered.

`anchor/` is the fourth package, and it exists because of a measured fact rather than a
preference: `SpecBlockEntity` calls `SpecPersistence`, `runGarnetSpec`, `StateRecorder`, *and*
`RecordingDslEmitter`. It is not a base class — it is the orchestrator that drives both
features, and both blocks share it. Any base package holding it makes the base depend on
everything. Lifting the whole in-world surface above both features resolves it with no logic
change. Splitting `SpecBlockEntity` into a recorder BE and a runner BE is the architecturally
correct fix, but it is a behavior-affecting refactor with BE-type and NBT migration; it needs
its own spec and is explicitly out of scope here.

### Dependency direction

```
spec/ mc/ structure/ config/ ui/     ← base, no feature imports
       ↑
   playback/                          ← imports spec/, structure/
       ↑
   testing/                           ← imports playback/, spec/
       ↑
   anchor/                            ← imports testing/, playback/
       ↑
   editor/                            ← imports anchor/, testing/, playback/, structure/
```

Strictly one direction. `testing.runner.runGarnetSpec` drives a `playback.recorder.StateRecorder`
and returns a `playback.data.StateRecording`, so testing sits *above* playback — this is why
the DSL cannot live under `testing/`.

Features never import another feature's `ui`. Cross-feature reach goes through `data`.

### Seam fixes

The layering above does not hold today. An import audit of `src/main` + `src/client` found five
violations that the current layer-based packaging conceals. Four are resolved by placement; one
needs a small code change. These are prerequisites, not incidental cleanup — without them the
new packages have cycles on day one.

| Violation | Resolution | Phase |
| --- | --- | --- |
| `SpecBlockEntity` → `SpecPersistence`, `runGarnetSpec`, `StateRecorder`, `RecordingDslEmitter` | `anchor/` sits above both features | 6 |
| `SpecMarkerTool` → `GarnetRunnerBlock` (one `is` check) | both land in `anchor/` | 6 |
| `StructurePersistence` → `project.PlacedBox`, `autoFit`, `anchorY`, `centeredStart` | these are structure-region geometry, not editor concerns. `StructureRegionMath.kt` → `structure/`; `PlacedBox` extracted out of `ProjectDimRegistry.kt` into `structure/` | 4 |
| `ui.DockKeybinds` → `editor` Explorer state | the file does two jobs. Keybind registration stays in `ui/viewport/DockKeybinds.kt`; `registerDockWorldLifecycle`'s Explorer-state reset moves to `editor/ui/ExplorerLifecycle.kt` | 7 |
| `dsl.ConditionEvaluator` → `runner.StateRecordingView` | **code change.** `SpecRun` already declares `StateRecordingViewLike` for this purpose; `StateRecordingView` does not implement it. Make it implement the interface and widen `ConditionEvaluator`'s parameter to the interface type | 4 |

The `ConditionEvaluator` fix is the only non-mechanical edit in phases 3–8, and it is the one
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

Deletions, in phase 1, before anything moves.

| Target | Reason |
| --- | --- |
| `client/state/ClientRunnerState.kt` | zero references, whole file |
| `KtsSpecLoader.loadSpec` / `loadFile` / `findFirstSpecClass` | legacy Kotest script form, zero callers; removing them drops the `io.kotest` import from `main` |
| `SpecScript` `defaultImports` for `GarnetTestSpec`, `runGarnetSpec`, `awaitTicks`, `awaitTickEnd`, `spawnStructure` | pre-imports for a script form nothing emits |
| `testing/server/Structures.kt` (`StructureGrid`, `StructureHandle`, slot acquire/release) | zero callers |
| `RunSpecC2SPayload`, `ResetSpecC2SPayload`, `SetStructureC2SPayload`, `SetRunnerConfigC2S` | registered and handled server-side, no sender anywhere |
| `OpenRecorderScreenS2C`, `OpenRunnerScreenS2C`, `RunnerStatusS2C` | client handlers are no-op log lines reading "UI removed" |
| `RunnerMetaSnapshot` | exists only to populate `OpenRunnerScreenS2C` |
| The three matching receivers in `ClientNetworkHandler` | dead with their payloads; leaves one receiver (`OverwritePrompt`) |
| Gametest assertions covering the removed payloads | in `RecorderRunnerNetworkRegistrySpec` |

Survivors in the spec-block wire protocol: `OverwritePromptS2CPayload`,
`OverwriteDecisionC2SPayload`, `SetRecorderConfigC2S`, `RecorderCommandC2S`, `RunnerCommandC2S`.
These are commands with live gametest coverage, not screen plumbing.

**Behavior change:** `GarnetRunnerBlock.use` and `GarnetRecorderBlock.use` lose their
open-screen sends. Right-clicking either block becomes a no-op until the panels are built.
This is intended — it removes the pretence of a UI that was cut.

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

### `src/main/kotlin` → `testing/`

| From | To |
| --- | --- |
| `runner/runGarnetSpec.kt` | `testing/runner/` |
| `runner/SpecSnapshot.kt` | `testing/runner/` |
| `runner/PlayerInteractionDispatch.kt` | `testing/runner/` |
| `persistence/SpecPersistence.kt`, `KtsSpecLoader.kt`, `SpecScript.kt`, `SpecDirectoryScan.kt` | `testing/data/` |
| `project/LoadedSpec.kt` | `testing/data/` |

### `src/main/kotlin` → `playback/`

| From | To |
| --- | --- |
| `runner/StateRecorder.kt` | `playback/recorder/` |
| `runner/RecordingDslEmitter.kt` | `playback/recorder/` |
| `runner/StateRecording.kt`, `StateRecordingStorage.kt`, `StateRecordingView.kt` | `playback/data/` |
| `persistence/RecordingSidecar.kt` | `playback/data/` |

### `src/main/kotlin` → `anchor/`

| From | To |
| --- | --- |
| `block/GarnetRecorderBlock.kt`, `GarnetRunnerBlock.kt`, `SpecBlockEntity.kt` | `anchor/block/` |
| `item/SpecMarkerTool.kt`, `UndoStack.kt` | `anchor/item/` |
| `network/Packets.kt` (surviving payloads) | `anchor/network/AnchorPackets.kt` |
| `network/NetworkRegistry.kt` (surviving handlers) | `anchor/network/AnchorNetworking.kt` |

The trim removes every payload that would have split `Packets.kt` across two features. What
survives — `OverwritePromptS2CPayload`, `OverwriteDecisionC2SPayload`, `SetRecorderConfigC2S`,
`RecorderCommandC2S`, `RunnerCommandC2S` — is uniformly addressed to a block entity by
`originPos`, so it belongs with the blocks. `NetworkRegistry` therefore splits **two** ways
(`anchor` + `editor`), not three.

### `src/main/kotlin` → `editor/` (with `Project` → `Editor` rename)

| From | To |
| --- | --- |
| `project/ProjectRoot.kt` | `editor/data/EditorRoot.kt` |
| `project/ProjectSession.kt` | `editor/data/EditorSession.kt` |
| `project/ProjectCell.kt` | `editor/data/EditorCell.kt` |
| `project/ProjectNames.kt` | `editor/data/EditorNames.kt` |
| `project/ProjectSaveNaming.kt` | `editor/data/EditorSaveNaming.kt` |
| `project/ProjectNewSpec.kt` | `editor/data/EditorNewSpec.kt` |
| `project/ProjectNewStructure.kt` | `editor/data/EditorNewStructure.kt` |
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
| `client/render/GarnetBoundsRenderer.kt`, `HudOverlayRenderer.kt` | `playback/ui/` |
| `client/SpecBoundsInteraction.kt` | `playback/ui/` |
| `client/network/ClientNetworkHandler.kt` | `anchor/ui/AnchorClientNetworking.kt` |
| `client/ide/*` (9 files) | `editor/ui/` |
| `client/project/ProjectClientNetworking.kt` | `editor/network/EditorClientNetworking.kt` |
| `client/project/ProjectIntegratedBoot.kt` | `editor/world/EditorIntegratedBoot.kt` |
| `client/GarnetClient.kt` | stays at root next to `Garnet.kt` |

`src/client/java` (mixins, `WindowViewportExt`) is unchanged — the mixin JSON pins
`com.breadmoirai.garnet.mixin.client`.

After the trim, `ClientNetworkHandler` holds one receiver (`OverwritePrompt`), which is
addressed to a block entity; hence its move into `anchor/`.

### Test sourcesets

`src/test`, `src/gametest`, and `src/clientTest` mirror the feature layout:
`test/project/*` → `test/editor/*`, `test/recorder/*` → `test/anchor/` (they exercise the
marker tool and recording lifecycle through the blocks), `test/network/*` → `test/anchor/`,
`test/persistence/*` splits between `test/testing/` and `test/structure/`.

`GametestSentinel`'s explicit spec list and `ClientTestSentinel`'s must be updated in the same
commit as any spec move — autoscan is disabled, so an unregistered spec silently does not run.

---

## Sequencing

Each phase is one commit on `main`, gated on the full build:
`clientClasses classes gametestClasses clientTestClasses testClasses`, plus `test`,
`runGameTest`, and `runClientTest`.

| Phase | Content |
| --- | --- |
| 1 | Trim. No moves, no renames. |
| 2 | `testSupport` sourceset + build wiring; move the Kotest-coupled classes out of `main`. |
| 3 | `testing/core` + `testing/server` + `event/` → `garnet.mc`; `GarnetTestLifecycle` → `McLifecycle`. |
| 4 | Base packages: `spec/`, `structure/`, `ui/`, `config/`. Includes the `StateRecordingViewLike` and `StructureRegionMath`/`PlacedBox` seam fixes. Update `RecordingDslEmitter`'s emitted import line and `SpecScript.defaultImports` to `garnet.spec.*`. |
| 5 | `playback/`. |
| 6 | `testing/`, then `anchor/` — the two are one commit only if `anchor/` cannot compile without `testing/` in place. |
| 7 | `editor/`, including the `Project` → `Editor` rename and the `DockKeybinds` split. |
| 8 | Docs sync. |

Phases 5–7 run in dependency order so each lands on an already-moved base.

Phase 4 is the only one with a user-visible artifact change: every `.spec.kts` file's first line
becomes `import com.breadmoirai.garnet.spec.*`. No `.spec.kts` files are committed to the repo,
and `RecordingDslEmitter` regenerates the line, so existing files are only affected in a
developer's local world save.

## Verification

Per phase:

1. Compile all five (soon six) sourcesets.
2. `:26.2:test` unfiltered, then read the XML report — the `--tests` filter does not work with
   Kotest and reports a false "no tests found".
3. `runGameTest` and `runClientTest` in the foreground with a 600 s timeout. `clientTest` XML
   reports are always empty; read the log for the sentinel's `LauncherResult.summary()`.
4. `grep -rn "<OldName>" docs/ src/` returns zero hits referring to the old role.

Phases 3 and 5–7 are mechanical: package/import rewrites with no logic edits. Any behavior diff
in a mechanical phase is a bug in the move, not an intended change. Phase 4 carries the two
seam fixes and phase 2 carries the build wiring, so those two need real review.

## Docs impact

`docs/architecture/module-map.md` is rewritten wholesale — it is a package-by-package tour and
every entry changes. `docs/architecture/recording-pipeline.md`, `redstone-project.md`, the
`docs/gametest/` articles (they name `GarnetTestSpec`, `launchKotest`, `FabricTestThreadPump`),
and `docs/use-cases/gametest-harness.md` all cite moved names. `docs/persistence/` articles cite
`Packets.kt` payloads that phase 1 deletes.

Full `grep -rn` sweep for every renamed symbol is part of phase 8, not deferred.

## Open risks

- **Phase 2 is the riskiest phase.** A new sourceset that must be on the game's
  runtime classpath for two different Loom run configs, excluded from Compose, and excluded
  from the shipped jar. If it resists, the fallback is to keep the harness in `main` behind a
  `garnet.harness` package and accept that Kotest ships — the layout benefit survives, only
  the jar-size/purity benefit is lost.
- **Same-package-across-sourcesets `internal`.** Expect compile errors in phases 5–7 where
  `src/client` code reaches `src/main` code that is now nominally in the same package. The fix
  is promoting to `public`, not restructuring further.
- **Payload split.** `NetworkRegistry.kt` currently registers everything in one
  `registerNetworking()` called from `Garnet.onInitialize`. Splitting it into `anchor` and
  `editor` means two registration calls; registration order must stay stable or payload IDs
  shift.
- **`anchor/` is a compromise, not the destination.** It exists to contain `SpecBlockEntity`'s
  reach without a behavior-affecting refactor. Splitting the BE per feature and dissolving
  `anchor/` into `testing/block` and `playback/block` remains the better end state and should
  get its own spec once the panels exist.
