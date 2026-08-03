# Refactor: package + file cohesion

Follow-on to `bc86318` (`refactor(events): Move mc package into core.async and core.events`), which
split a grab-bag package by concern and fanned one multi-concern file out into single-purpose
files. This plan applies the same two moves to the five remaining places that have the same shape.

Authoritative layout reference: `docs/superpowers/specs/2026-07-31-feature-package-layout-design.md`.
Task 2 deliberately amends that doc; every other task conforms to it as written.

## Global Constraints

- **Behavior-preserving.** These are moves, renames, and extractions. No logic changes, no
  signature changes beyond what a move forces, no "while I'm here" fixes. If an implementer
  believes it found a real bug, it reports it as a concern and leaves the code alone.
- **Comments move verbatim.** The files being split carry long explanatory blocks (the B1/B2 and
  "Finding N" rationale in `StructureCommit`/`EditorNetworking`). These are load-bearing
  institutional memory. Move them with the code they explain; do not summarize, reword, or drop
  them. Update only the cross-references inside them that a move invalidates.
- **Branch:** work directly on `main`, one commit per task. This is the established project
  workflow — no feature branches, no worktrees.
- **Build verification (implementer runs this, all five sourcesets):**
  `cmd.exe /c "gradlew.bat :26.2:classes :26.2:clientClasses :26.2:gametestClasses :26.2:clientTestClasses :26.2:testClasses"`
  Note the `:26.2:` task path — not `:versions:26.2:`. Invoke through `cmd.exe /c` with no `./`
  prefix.
- **Implementers do not run test suites.** Cold Gradle builds exceed the subagent timeout and
  orphaned runs wedge. An implementer compiles all five sourcesets, then stops. The controller
  runs `:26.2:runGameTest` / `:26.2:runClientTest` / `:26.2:test` between tasks.
- **Kotlin `internal` is per-sourceset, not per-package.** Anything `src/client` consumes from
  `src/main` must be `public`, even when the packages match.
- **Doc audit is part of every task**, per `CLAUDE.md`: grep `docs/` for every moved type name and
  update hits, keep `INDEX.md` entries and `file:line` citations resolving. `docs/superpowers/`
  is exempt (commit-time snapshots) *except* where a task says otherwise.
- Do not add tests for pure moves. Existing gametest/clientTest coverage is the regression net;
  if it compiles against the new names and passes, the move is verified.

---

## Task 1: Split `editor/network/EditorNetworking.kt`

`src/main/kotlin/com/breadmoirai/garnet/editor/network/EditorNetworking.kt` is 594 lines doing four
unrelated jobs. Split it, and fix the layering inversion it causes.

### 1a. Move `rootFor` out of `network`

`EditorNetworking.rootFor` (currently `EditorNetworking.kt:38-45`) is consumed by
`editor/world/StructureCommit.kt:222` — the world layer importing the network layer. Move it to a
new `src/main/kotlin/com/breadmoirai/garnet/editor/world/EditorRootResolver.kt`:

```kotlin
object EditorRootResolver {
    fun rootFor(server: MinecraftServer): EditorRoot? { ... }
}
```

`world/`, not `data/`: the design doc defines `editor/data` as "pure model — no server side
effects", and this reads `EditorWorld` / `EditorServerContext` off a live server. `world` is the
correct home and it still removes the inversion (`world → world`).

Update all callers, including `EditorNetworkRegistrySpec.kt:414` which asserts on
`EditorNetworking.rootFor(...)`, and the KDoc reference in `EditorDimRegistry.kt:156`. Drop the
"Public because StructureCommit resolves subpaths through the same rule" sentence from the KDoc —
after the move it is no longer explaining anything unusual.

### 1b. Extract registration

New `EditorNetworkRegistry.kt` holding `register()` verbatim (the 16 `PayloadTypeRegistry` calls
and the 11 `ServerPlayNetworking.registerGlobalReceiver` calls). `Garnet.kt:30` calls
`EditorNetworking.register()` — update it. `EditorNetworkRegistrySpec.kt:57-58` names
`EditorNetworking.register()` in a comment and a test name; update both.

### 1c. Split the handlers three ways

Mirror the existing gametest partition exactly:

| New file | Handlers |
| --- | --- |
| `EditorTreeHandlers.kt` | `handleListTree`, `handleLoadFolder`, `handleUnload`, `handleSaveNow`, `handleNewSpec`, `handleSetRoot` |
| `EditorStructureHandlers.kt` | `handlePlaceStructure`, `handleSaveStructure`, `handleNewStructure` |
| `EditorFileOpsHandlers.kt` | `handleCreateFolder`, `handleRename` |

Keep them as `object`s with the handler names unchanged — the gametest suite calls every one of
these by name (`EditorFileOpsNetworkSpec`, `EditorStructureNetworkSpec`,
`EditorNetworkRegistrySpec`, `StructureAutoSaveSpec`) and only the receiver object should change at
the call sites.

`EditorNetworking.kt` itself is deleted once empty.

### 1d. Rehome the helpers

- `placeStructureFrom` (`:245-272`) — used by `handlePlaceStructure` and `handleRename`. It places,
  teleports the player, and sends `StructureResultS2C`. Put it in `EditorStructureHandlers.kt` as a
  public member so `EditorFileOpsHandlers.handleRename` can call it.
- `moveDescendantHistories` (`:524-536`) — a `LocalHistoryStore` concern, not a networking one.
  Move it onto `history/LocalHistoryStore.kt` as a public function (`moveHistoryTree(from, to)` or
  keep the name). Its KDoc explains why it walks `newRoot` rather than `oldRoot` — move that with
  it.
- `repointSession` (`:542-549`) — pure `EditorSession` bookkeeping. Move it onto
  `editor/data/EditorSession.kt`.
- `resolveParentFolder` (`:556-574`) and `siblingNames` (`:577-578`) — used by
  `handleNewStructure` (structure) and `handleCreateFolder`/`handleRename` (file ops). They are
  shared, so put them in a new `EditorHandlerSupport.kt` in the same package alongside the error
  helper below.
- `sendTree` (`:580-590`) and `formatSaveResult` (`:592-593`) — `sendTree` is used by tree,
  structure, and file-ops handlers; put it in `EditorHandlerSupport.kt`. `formatSaveResult` is used
  only by `handleSaveNow`; it goes with `EditorTreeHandlers.kt`.

### 1e. Collapse the error-reply boilerplate

`EditorErrorS2C(...)` is constructed 36 times, almost always as
`ServerPlayNetworking.send(player, EditorErrorS2C(msg)); return`. Add to `EditorHandlerSupport.kt`:

```kotlin
fun fail(player: ServerPlayer, reason: String) {
    ServerPlayNetworking.send(player, EditorErrorS2C(reason))
}
```

and use it at every such site. Keep the messages byte-identical — several gametests assert on the
exact reason string. Do not try to make `fail` also return/throw; the explicit `; return` stays.

### Verification

All five sourcesets compile. No file under `src/main/kotlin/.../editor/world/` imports
`com.breadmoirai.garnet.editor.network` any more — confirm with grep.

---

## Task 2: Promote the structure-commit pipeline to `editor/structure/`

`editor/world/` holds two unrelated subsystems. Move the commit pipeline into a new
`src/main/kotlin/com/breadmoirai/garnet/editor/structure/`:

| File | Lines |
| --- | --- |
| `StructureCommit.kt` | 475 |
| `StructureAutoSave.kt` | 87 |
| `StructureEditWatcher.kt` | 34 |

Everything else stays in `editor/world/` (`EditorWorld`, `EditorDimRegistry`, `EditorDimLifecycle`,
`EditorServerContext`, `EditorTeleport`, `GridLayout`, `EditorCellSaver`, and the new
`EditorRootResolver` from Task 1).

This is a package move only — no file contents change beyond the `package` line and imports.

**Naming:** `editor.structure` is distinct from the existing top-level `structure` package
(`PlacedBox`, `StructureDiff`, `StructurePersistence`, `StructureRegionMath` — pure NBT and region
geometry, no server state). `editor.structure` is the *live editing pipeline*. Both `structure`
packages will now be imported into the same files; make sure imports are unambiguous and add a
one-line KDoc header to each new file stating which layer it belongs to.

Callers to update: `Garnet.kt`, `ServerLevelSetBlockMixin.java`, `EditorDimRegistry.kt`,
`LocalHistoryStore.kt`, the Task 1 handler files, and the gametest specs
(`StructureAutoSaveSpec`, `EditorStructureNetworkSpec`, `EditorFileOpsNetworkSpec`,
`EditorNetworkRegistrySpec`, `StructureRegionPersistenceSpec`, `GametestSentinel`).

### Doc updates (this task is the exception to the `superpowers/` exemption)

1. Amend `docs/superpowers/specs/2026-07-31-feature-package-layout-design.md` — the "Feature
   packages" block's `editor/` listing gains a `structure/` line, and the prose under it
   ("`editor/` splits `data` from `world` because...") is extended to explain the three-way split:
   `data` is pure, `world` is the dimension/grid substrate, `structure` is the dirty-track →
   debounce → commit → history pipeline. Add a dated note that this amends the original spec.
2. Update `docs/architecture/module-map.md` and any other live doc under `docs/` that names these
   three files or `editor.world` as their home.

---

## Task 3: Fan out `editor/structure/StructureCommit.kt`

After Task 2 the file is at `editor/structure/StructureCommit.kt`, 475 lines holding four things.
Split, in the same package:

| New file | Contents |
| --- | --- |
| `CommitOutcome.kt` | the `CommitOutcome` sealed interface and its four variants (`:133-149`), with their KDoc |
| `CommitBackoff.kt` | the per-server attached state: `backoffMap`, `fingerprintMap`, `DiskFingerprint`, `fingerprint()`, `clearBackoff`, `onCommitFailure` (`:63-131`) |
| `StructureCommit.kt` | orchestration only: `commit`, `tick`, `commitAll`, `broadcast`, `dispose`, `UncommittedStructure`, and the NBT helpers `union`/`readTag`/`sizeOf` |

`CommitOutcome` is referenced as `StructureCommit.CommitOutcome.Committed` etc. at call sites in
the Task 1 handler files and in the gametest specs. Promoting it to a top-level `CommitOutcome`
changes those references — update every one. Prefer the top-level form over keeping a nested alias;
a nested type that lives in another file is worse than either option.

If `readTag`/`sizeOf`/`union` turn out to be used by `StructureAutoSave` too, put them in a fourth
`StructureNbtSupport.kt` rather than making `StructureCommit` a utility holder.

---

## Task 4: Split `client/ui/compose/ComposeSurface.kt`

384 lines mixing four concerns. Extract two, in the same package:

| New file | Contents |
| --- | --- |
| `GlStateStash.kt` | `saveGlState`, `restoreGlState`, `setEnabled`, `saveAndResetUnpack`, `restoreUnpack` (`:309-368`) — pure OpenGL bookkeeping with no Compose or Skia dependency |
| `ComposeInput.kt` | `sendPointerMove`, `sendPointerPress`, `sendPointerRelease`, `sendScroll`, `sendKey`, and the `guardedInput` wrapper (`:270-307`) |

`ComposeSurface.kt` keeps lifecycle (`markSceneStale`, `kill`, `ensureNativeLoaded`,
`releaseSurfaceOnly`, `releaseGpu`) and rendering (`ensureDirectContext`, `ensureSurface`,
`renderFrame`, `ensureHost`).

The input functions read `ComposeSurface.host`, which is private. Widen exactly what `ComposeInput`
needs (an internal accessor is fine — both files are in `src/client`) rather than making the whole
host field public.

`ComposeSurface` is referenced from `docs/ui/jewel-widget-layer.md`,
`docs/ui/compose-blended-overlay.md`, `docs/ui/compose-in-mc-feasibility.md`, and
`docs/architecture/shrink-viewport-compose-model.md`. Audit all four; several cite `file:line`.

Regression net: `DockInputSpec`, `JewelExplorerSpec`, `ViewportPickingSpec`, `PanelPixelProbe` in
`src/clientTest`. The pixel-probe specs are the real check that rendering still works.

---

## Task 5: Separate mutations from model in `editor/data/`

Smallest-value task; do it last and drop it if Tasks 1–4 have already made the package legible.

`editor/data/` (10 files) mixes a pure read model with filesystem-mutating operations. The design
doc's own words for this package are "pure model — no server side effects, unit-testable", which
`EditorNewSpec` and `EditorNewStructure` violate — both create files on disk.

Move to a new `src/main/kotlin/com/breadmoirai/garnet/editor/ops/`:

- `EditorNewSpec.kt` (30 lines) — creates a spec file
- `EditorNewStructure.kt` (33 lines) — creates a `.nbt`

Stays in `editor/data/`: `EditorRoot`, `FileTree`, `EditorFolderTree`, `EditorCell`, `LoadedSpec`
(model), `EditorNames`, `EditorSaveNaming` (pure naming/validation logic, no IO), `EditorSession`
(in-memory per-player state, no IO).

Update `docs/superpowers/specs/2026-07-31-feature-package-layout-design.md`'s file-mapping table
rows for the two moved files, in the same amendment style as Task 2. Update the "Feature packages"
block to list `ops/`.

If the implementer finds `EditorNames`/`EditorSaveNaming` do touch the filesystem, report it as a
concern — do not expand the move unilaterally.
