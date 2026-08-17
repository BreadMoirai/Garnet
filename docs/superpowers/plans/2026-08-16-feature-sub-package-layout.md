# Feature Sub-Package Layout Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Re-package `com.breadmoirai.garnet` so every file's path states its feature, sub-feature, and layer, across all six source sets.

**Architecture:** A pure move. `git mv` the files, rewrite the fully-qualified names everywhere with `sed`, add the imports the compiler asks for, and prove nothing changed by compiling all five compile targets and running the existing suite. One sub-feature per commit, each independently green. Two files are renamed for naming consistency; no other names change, no logic is edited, nothing is deleted.

**Tech Stack:** Kotlin 2.3.20, Fabric Loom + Stonecutter (`:26.2:` task prefix), JUnit 5 + Kotest, MC 26.2. Gradle runs through `cmd.exe` from WSL.

**Spec:** `docs/superpowers/specs/2026-08-16-feature-sub-package-layout-design.md`

## Global Constraints

- **No behavior change.** This is a move plus two renames. Do not edit logic, do not delete code, do not "improve" anything you pass. If a file looks wrong, leave it and report it.
- **Six source sets, five compile targets.** `main`, `client`, `gametest`, `clientTest`, `test`, `testSupport`. Verification is `:26.2:clientClasses :26.2:classes :26.2:gametestClasses :26.2:clientTestClasses :26.2:testClasses` — `compileKotlin` alone covers only `main`.
- **Gradle from WSL runs through cmd.exe:** `cmd.exe /c "cd /d H:\Repo\garnet && gradlew.bat <tasks>"`. See `docs/tooling/wsl2-gradle-invocation.md`.
- **`:26.2:test` runs unfiltered.** Gradle's `--tests` filter does not select Kotest specs. Read the per-class JUnit XML under `versions/26.2/build/test-results/test/`.
- **`docs/superpowers/**` is never edited.** Specs and plans are commit-time historical snapshots. Every `sed` over `docs/` must exclude that subtree. This plan file and its spec are the only exceptions, and only if you are correcting them deliberately.
- **These package roots do not move:** `com.breadmoirai.garnet.Garnet`, `com.breadmoirai.garnet.GarnetClient` (pinned by `fabric.mod.json` entrypoints), `com.breadmoirai.garnet.mixin`, `com.breadmoirai.garnet.mixin.client` (pinned by the `package` field of `src/main/resources/garnet.mixins.json` and `src/client/resources/garnet.client.mixins.json`).
- **`testSupport/harness/` does not move.** It is the Kotest bridge, not tests.
- **Saved `.spec.kts` files break, by decision.** Task 1 changes the DSL package that every emitted spec file imports. No compat shim. Do not add one.

---

## The move recipe

Every task is the same three moves. Read this once; the tasks reference it as "the recipe".

**1. Move the files.**

```bash
mkdir -p <destination-dir>
git mv <old-path> <new-path>
```

**2. Rewrite the fully-qualified name everywhere.** Anchor with `\b` so `…garnet.spec` does not also match `…garnet.specFoo`. Include `docs/` but exclude the superpowers subtree:

```bash
rewrite() {  # rewrite OLD_FQN NEW_FQN
  grep -rl "$1" src docs --exclude-dir=superpowers \
    | xargs -r sed -i "s/${1//./\\.}\\b/$2/g"
}
```

This single pass fixes the `package` declaration at the top of each moved file, every `import` of it, every fully-qualified reference in code, and every doc citation — they are all the same string.

**Package-line rewrites must target the package line, not line 1.** Kotlin files may open with
`@file:OptIn(...)`, a license header, or a comment — Task 2 lost a `@file:OptIn` to a blind
`1s/.*/…/` and had to restore it by hand. Every package rewrite in this plan therefore uses
`sed -i '0,/^package /{s|^package .*|package <new>|}'`, which replaces the first line that actually
begins with `package` wherever it sits. Verify afterwards with `grep -Hn '^package' <files>`: every
file must report exactly one hit, naming the new package.

**3. Add the imports the compiler asks for.** `sed` cannot do this part. When files that used to share a package land in different packages, references between them that needed no import now need one. Compile, read the `unresolved reference` errors, add exactly those imports, repeat until clean. Do not resolve an error by moving a file somewhere the plan didn't put it.

**Verification, run at the end of every task:**

```bash
cmd.exe /c "cd /d H:\Repo\garnet && gradlew.bat :26.2:clientClasses :26.2:classes :26.2:gametestClasses :26.2:clientTestClasses :26.2:testClasses"
cmd.exe /c "cd /d H:\Repo\garnet && gradlew.bat :26.2:test"
```

Expected: `BUILD SUCCESSFUL` for both. If `:26.2:test` reports failures, open `versions/26.2/build/test-results/test/TEST-*.xml` for the failing class — do not trust the console summary alone for Kotest specs.

**A stale-directory check to run before each commit** — `git mv` leaves empty directories behind on some filesystems, and an empty package directory is invisible to Gradle but confusing to readers:

```bash
find src -type d -empty -delete
```

---

## Task 1: `core/` — spec, config, and the DSL package break

Moves the spec DSL under `core/`, moves both config files under `core/config/`, and — the one non-mechanical edit in the whole plan — changes the DSL package name that `RecordingDslEmitter` writes into every `.spec.kts` file. `core/async/` and `core/events/` are already in place and are not touched.

**Files:**
- Move: `src/main/kotlin/com/breadmoirai/garnet/spec/` (9 files: `GarnetSpec.kt`, `SpecRun.kt`, `InputScope.kt`, `OutputScope.kt`, `ConditionScope.kt`, `StateCondition.kt`, `ConditionEvaluator.kt`, `SpecTime.kt`, `PlayerInteractionDispatch.kt`) → `src/main/kotlin/com/breadmoirai/garnet/core/spec/`
- Move: `src/main/kotlin/com/breadmoirai/garnet/config/SharedSettings.kt` → `src/main/kotlin/com/breadmoirai/garnet/core/config/SharedSettings.kt`
- Move: `src/client/kotlin/com/breadmoirai/garnet/config/ModConfig.kt` → `src/client/kotlin/com/breadmoirai/garnet/core/config/ModConfig.kt`
- Move: `src/test/kotlin/com/breadmoirai/garnet/spec/` (3 files) → `src/test/kotlin/com/breadmoirai/garnet/core/spec/`
- Move: `src/test/kotlin/com/breadmoirai/garnet/client/config/ModConfigTest.kt` → `src/test/kotlin/com/breadmoirai/garnet/core/config/ModConfigTest.kt`
- Modify (string literals): `src/main/kotlin/com/breadmoirai/garnet/playback/recorder/RecordingDslEmitter.kt` (3 sites), `src/main/kotlin/com/breadmoirai/garnet/testing/data/SpecScript.kt` (1 site)

`DockLayoutStore.kt` and `ExplorerStateStore.kt` stay in `client/config/` for now — they leave in Tasks 8 and 2. `src/test/.../client/config/ExplorerStateStoreTest.kt` likewise.

**Interfaces:**
- Consumes: nothing.
- Produces: `com.breadmoirai.garnet.core.spec.*` (the DSL — `GarnetSpec`, `SpecRun`, `SimTime`, `Phase`, `StateCondition`, `garnetSpec`), `com.breadmoirai.garnet.core.config.SharedSettings`, `com.breadmoirai.garnet.core.config.ModConfig`. Every later task imports the first of these.

- [ ] **Step 1: Move the spec DSL and rewrite its FQN**

```bash
cd /mnt/h/Repo/Garnet
mkdir -p src/main/kotlin/com/breadmoirai/garnet/core/spec
git mv src/main/kotlin/com/breadmoirai/garnet/spec/*.kt src/main/kotlin/com/breadmoirai/garnet/core/spec/
mkdir -p src/test/kotlin/com/breadmoirai/garnet/core/spec
git mv src/test/kotlin/com/breadmoirai/garnet/spec/*.kt src/test/kotlin/com/breadmoirai/garnet/core/spec/
grep -rl 'com\.breadmoirai\.garnet\.spec\b' src docs --exclude-dir=superpowers \
  | xargs -r sed -i 's/com\.breadmoirai\.garnet\.spec\b/com.breadmoirai.garnet.core.spec/g'
find src -type d -empty -delete
```

- [ ] **Step 2: Confirm the emitted-script import moved with it**

The same `sed` rewrote four string literals, which is intended and is the deliberate break. Verify all four now read `core.spec`:

```bash
grep -rn 'import com.breadmoirai.garnet.core.spec' \
  src/main/kotlin/com/breadmoirai/garnet/playback/recorder/RecordingDslEmitter.kt \
  src/main/kotlin/com/breadmoirai/garnet/testing/data/SpecScript.kt
grep -rn 'garnet\.spec\.\*' src | grep -v core.spec
```

Expected: the first command prints 3 lines from `RecordingDslEmitter.kt` (the emitted file header, written at three call sites) and 1 from `SpecScript.kt` (the `defaultImports` entry). The second prints **nothing** — no stale literal survives. The four affected test files (`RecordingDslEmitterTest`, `KtsSpecLoaderTest`, `KtsSpecLoaderRoundtripTest`, `SpecPersistenceTest`) assert against these strings and were rewritten by the same pass.

- [ ] **Step 3: Move both config files**

```bash
mkdir -p src/main/kotlin/com/breadmoirai/garnet/core/config
git mv src/main/kotlin/com/breadmoirai/garnet/config/SharedSettings.kt \
       src/main/kotlin/com/breadmoirai/garnet/core/config/SharedSettings.kt
mkdir -p src/client/kotlin/com/breadmoirai/garnet/core/config
git mv src/client/kotlin/com/breadmoirai/garnet/config/ModConfig.kt \
       src/client/kotlin/com/breadmoirai/garnet/core/config/ModConfig.kt
mkdir -p src/test/kotlin/com/breadmoirai/garnet/core/config
git mv src/test/kotlin/com/breadmoirai/garnet/client/config/ModConfigTest.kt \
       src/test/kotlin/com/breadmoirai/garnet/core/config/ModConfigTest.kt
grep -rl 'com\.breadmoirai\.garnet\.config\.SharedSettings\b' src docs --exclude-dir=superpowers \
  | xargs -r sed -i 's/com\.breadmoirai\.garnet\.config\.SharedSettings\b/com.breadmoirai.garnet.core.config.SharedSettings/g'
grep -rl 'com\.breadmoirai\.garnet\.config\.ModConfig\b' src docs --exclude-dir=superpowers \
  | xargs -r sed -i 's/com\.breadmoirai\.garnet\.config\.ModConfig\b/com.breadmoirai.garnet.core.config.ModConfig/g'
```

Note the per-class rewrite here rather than a whole-package one: `com.breadmoirai.garnet.config` still exists after this step, holding `DockLayoutStore` and `ExplorerStateStore`. The moved files' own `package` lines are also `com.breadmoirai.garnet.config` and are **not** matched by a per-class pattern — fix those two by hand:

```bash
sed -i '0,/^package /{s|^package .*|package com.breadmoirai.garnet.core.config|}' \
  src/main/kotlin/com/breadmoirai/garnet/core/config/SharedSettings.kt \
  src/client/kotlin/com/breadmoirai/garnet/core/config/ModConfig.kt
sed -i '0,/^package /{s|^package .*|package com.breadmoirai.garnet.core.config|}' \
  src/test/kotlin/com/breadmoirai/garnet/core/config/ModConfigTest.kt
```

Then confirm each of the three has exactly one package declaration and it names the new package:

```bash
grep -Hn '^package' src/main/kotlin/com/breadmoirai/garnet/core/config/SharedSettings.kt \
        src/client/kotlin/com/breadmoirai/garnet/core/config/ModConfig.kt \
        src/test/kotlin/com/breadmoirai/garnet/core/config/ModConfigTest.kt
```

- [ ] **Step 4: Compile and fix imports**

`DockLayoutStore` and `ExplorerStateStore` referenced `SharedSettings`/`ModConfig` with no import (same package). They now need one. Run the compile target, add exactly the imports named, repeat:

```bash
cmd.exe /c "cd /d H:\Repo\garnet && gradlew.bat :26.2:clientClasses :26.2:classes :26.2:gametestClasses :26.2:clientTestClasses :26.2:testClasses"
```

Expected: eventually `BUILD SUCCESSFUL`.

- [ ] **Step 5: Run the suite**

```bash
cmd.exe /c "cd /d H:\Repo\garnet && gradlew.bat :26.2:test"
```

Expected: `BUILD SUCCESSFUL`. The kts loader/emitter/persistence tests are the ones that would catch a half-applied package rename — confirm `KtsSpecLoaderRoundtripTest` and `SpecPersistenceTest` are green in `versions/26.2/build/test-results/test/`.

- [ ] **Step 6: Update the doc citations this task invalidated**

The `sed` already rewrote FQN mentions. What it did not rewrite is prose referring to the packages by their short path. Find and fix:

```bash
grep -rn '`spec/`\|`config/`\|garnet/spec\|garnet/config' docs --exclude-dir=superpowers
```

Update each hit to `core/spec/` or `core/config/`. Leave `docs/architecture/module-map.md` alone — Task 10 rewrites it wholesale.

- [ ] **Step 7: Commit**

```bash
find src -type d -empty -delete
git add -A
git commit -m "refactor(core): move spec DSL and config under core/

Breaks previously saved .spec.kts files: the emitted import is now
com.breadmoirai.garnet.core.spec.*. Deliberate, no compat shim."
```

---

## Task 2: `editor/explorer/`

Carves the Explorer out of `editor/data`, `editor/ops`, `editor/network`, and `editor/ui`. This is the largest task and the one where Step "add the imports" does the most work — `editor/ui/`'s 22 files currently reference each other with no imports at all, and this task splits them across four packages.

**Files:**
- Move → `src/main/kotlin/com/breadmoirai/garnet/editor/explorer/data/`: `editor/data/EditorRoot.kt`, `EditorSession.kt`, `EditorNames.kt`, `EditorSaveNaming.kt`, `EditorCell.kt`, `FileTree.kt`, `EditorFolderTree.kt`, `LoadedSpec.kt`
- Move → `src/main/kotlin/com/breadmoirai/garnet/editor/explorer/ops/`: `editor/ops/EditorNewSpec.kt`, `EditorNewStructure.kt`, `DefaultPlatform.kt`
- Move → `src/main/kotlin/com/breadmoirai/garnet/editor/explorer/network/`: `editor/network/EditorTreeHandlers.kt`, `EditorFileOpsHandlers.kt`
- Move → `src/client/kotlin/com/breadmoirai/garnet/editor/explorer/ui/`: `editor/ui/ExplorerActions.kt`, `ExplorerContextMenu.kt`, `ExplorerDialogs.kt`, `ExplorerEdit.kt`, `ExplorerKeyActions.kt`, `ExplorerLifecycle.kt`, `ExplorerToolbar.kt`, `ExplorerTreeState.kt`, `FolderPicker.kt`, `RootPickerController.kt`, `TimeFormat.kt`, plus the two renames below
- Move + rename: `editor/ui/ProjectExplorerPanel.kt` → `editor/explorer/ui/ExplorerPanel.kt`; `editor/ui/ProjectTreeState.kt` → `editor/explorer/ui/ExplorerTreeSnapshot.kt`
- Move: `src/client/kotlin/com/breadmoirai/garnet/config/ExplorerStateStore.kt` → `src/client/kotlin/com/breadmoirai/garnet/editor/explorer/ui/ExplorerStateStore.kt`
- Test moves are deferred to Task 9 — **except** that this task's `sed` rewrites their imports in place, which is expected and keeps them compiling from their old locations.

**Interfaces:**
- Consumes: `com.breadmoirai.garnet.core.config.SharedSettings` (Task 1).
- Produces: `com.breadmoirai.garnet.editor.explorer.data.{EditorRoot, EditorSession, EditorNames, EditorSaveNaming, EditorCell, FileTree, FileTreeNode, EditorFolderTree, LoadedSpec}`; `…explorer.ops.{EditorNewSpec, EditorNewStructure, DefaultPlatform}`; `…explorer.network.{EditorTreeHandlers, EditorFileOpsHandlers, DeleteOutcome}`; `…explorer.ui.{ExplorerTreeSnapshot, ExplorerTreeState, ExplorerStateStore, RootPickerController, explorerPanel}`. Tasks 3–7 import from `explorer.data`; Task 5 imports `EditorFileOpsHandlers`.

- [ ] **Step 1: Move the main-source files**

```bash
cd /mnt/h/Repo/Garnet
mkdir -p src/main/kotlin/com/breadmoirai/garnet/editor/explorer/{data,ops,network}
for f in EditorRoot EditorSession EditorNames EditorSaveNaming EditorCell FileTree EditorFolderTree LoadedSpec; do
  git mv src/main/kotlin/com/breadmoirai/garnet/editor/data/$f.kt \
         src/main/kotlin/com/breadmoirai/garnet/editor/explorer/data/$f.kt
done
for f in EditorNewSpec EditorNewStructure DefaultPlatform; do
  git mv src/main/kotlin/com/breadmoirai/garnet/editor/ops/$f.kt \
         src/main/kotlin/com/breadmoirai/garnet/editor/explorer/ops/$f.kt
done
for f in EditorTreeHandlers EditorFileOpsHandlers; do
  git mv src/main/kotlin/com/breadmoirai/garnet/editor/network/$f.kt \
         src/main/kotlin/com/breadmoirai/garnet/editor/explorer/network/$f.kt
done
```

- [ ] **Step 2: Rewrite the data and ops packages wholesale**

`editor/data` and `editor/ops` are emptied completely by this task, so a whole-package rewrite is safe and catches the `package` lines too:

```bash
grep -rl 'com\.breadmoirai\.garnet\.editor\.data\b' src docs --exclude-dir=superpowers \
  | xargs -r sed -i 's/com\.breadmoirai\.garnet\.editor\.data\b/com.breadmoirai.garnet.editor.explorer.data/g'
grep -rl 'com\.breadmoirai\.garnet\.editor\.ops\b' src docs --exclude-dir=superpowers \
  | xargs -r sed -i 's/com\.breadmoirai\.garnet\.editor\.ops\b/com.breadmoirai.garnet.editor.explorer.ops/g'
```

- [ ] **Step 3: Rewrite the two moved network files per-class**

`editor/network` survives this task (it keeps the registry, the support object, the packets, and the structure handlers), so rewrite by class, then fix the two `package` lines by hand:

```bash
for c in EditorTreeHandlers EditorFileOpsHandlers DeleteOutcome; do
  grep -rl "com\.breadmoirai\.garnet\.editor\.network\.$c\b" src docs --exclude-dir=superpowers \
    | xargs -r sed -i "s/com\.breadmoirai\.garnet\.editor\.network\.$c\b/com.breadmoirai.garnet.editor.explorer.network.$c/g"
done
sed -i '0,/^package /{s|^package .*|package com.breadmoirai.garnet.editor.explorer.network|}' \
  src/main/kotlin/com/breadmoirai/garnet/editor/explorer/network/EditorTreeHandlers.kt \
  src/main/kotlin/com/breadmoirai/garnet/editor/explorer/network/EditorFileOpsHandlers.kt
grep -Hn '^package' src/main/kotlin/com/breadmoirai/garnet/editor/explorer/network/*.kt
```

`DeleteOutcome` is declared in `EditorFileOpsHandlers.kt` and referenced from `editor/undo/EditorUndoOps.kt`; it moves with its file, which is why it is in the loop.

- [ ] **Step 4: Move the client UI files, with the two renames**

```bash
mkdir -p src/client/kotlin/com/breadmoirai/garnet/editor/explorer/ui
for f in ExplorerActions ExplorerContextMenu ExplorerDialogs ExplorerEdit ExplorerKeyActions \
         ExplorerLifecycle ExplorerToolbar ExplorerTreeState FolderPicker RootPickerController TimeFormat; do
  git mv src/client/kotlin/com/breadmoirai/garnet/editor/ui/$f.kt \
         src/client/kotlin/com/breadmoirai/garnet/editor/explorer/ui/$f.kt
done
git mv src/client/kotlin/com/breadmoirai/garnet/editor/ui/ProjectExplorerPanel.kt \
       src/client/kotlin/com/breadmoirai/garnet/editor/explorer/ui/ExplorerPanel.kt
git mv src/client/kotlin/com/breadmoirai/garnet/editor/ui/ProjectTreeState.kt \
       src/client/kotlin/com/breadmoirai/garnet/editor/explorer/ui/ExplorerTreeSnapshot.kt
git mv src/client/kotlin/com/breadmoirai/garnet/config/ExplorerStateStore.kt \
       src/client/kotlin/com/breadmoirai/garnet/editor/explorer/ui/ExplorerStateStore.kt
```

- [ ] **Step 5: Apply the `ProjectTreeState` → `ExplorerTreeSnapshot` rename**

`ProjectTreeState` is an `object` with call sites across ten source files. `ProjectExplorerPanel` is a **filename only** — the file declares top-level `fun explorerPanel()`, there is no `ProjectExplorerPanel` symbol — so the file rename in Step 4 completes it and no symbol rewrite is needed.

```bash
grep -rl '\bProjectTreeState\b' src docs --exclude-dir=superpowers \
  | xargs -r sed -i 's/\bProjectTreeState\b/ExplorerTreeSnapshot/g'
grep -rn '\bProjectTreeState\b' src docs --exclude-dir=superpowers
```

Expected: the second command prints nothing.

- [ ] **Step 6: Fix the moved UI files' package lines and their importers**

`editor/ui` does **not** empty out in this task (Local History, Structure Info, and `UndoState` are still there until Tasks 3–5), so the package line fix is per-file:

```bash
sed -i '0,/^package /{s|^package .*|package com.breadmoirai.garnet.editor.explorer.ui|}' \
  src/client/kotlin/com/breadmoirai/garnet/editor/explorer/ui/*.kt
grep -Hn '^package' src/client/kotlin/com/breadmoirai/garnet/editor/explorer/ui/*.kt
```

`ExplorerStateStore.kt` had `package com.breadmoirai.garnet.config` — the same line-1 rewrite covers it. Then rewrite its importers, and the importers of everything else that moved out of `editor/ui`:

```bash
for c in ExplorerActions ExplorerContextMenu ExplorerDialogs ExplorerEdit ExplorerKeyActions \
         ExplorerLifecycle ExplorerToolbar ExplorerTreeState FolderPicker RootPickerController \
         TimeFormat ExplorerTreeSnapshot; do
  grep -rl "com\.breadmoirai\.garnet\.editor\.ui\.$c\b" src docs --exclude-dir=superpowers \
    | xargs -r sed -i "s/com\.breadmoirai\.garnet\.editor\.ui\.$c\b/com.breadmoirai.garnet.editor.explorer.ui.$c/g"
done
grep -rl 'com\.breadmoirai\.garnet\.config\.ExplorerStateStore\b' src docs --exclude-dir=superpowers \
  | xargs -r sed -i 's/com\.breadmoirai\.garnet\.config\.ExplorerStateStore\b/com.breadmoirai.garnet.editor.explorer.ui.ExplorerStateStore/g'
```

- [ ] **Step 7: Compile and add the imports the compiler names**

This is the long step. `LocalHistoryPanel`, `StructureInfoState`, `OpenStructureState`, `UndoState`, and `EditorClientNetworking` all reference `ExplorerTreeSnapshot`, `ExplorerTreeState`, or `RootPickerController` and are still in `editor/ui`/`editor/network` — they now need imports. Likewise `EditorStructureHandlers` and the surviving `editor/network` files reference `EditorRoot` and friends.

```bash
cmd.exe /c "cd /d H:\Repo\garnet && gradlew.bat :26.2:clientClasses :26.2:classes :26.2:gametestClasses :26.2:clientTestClasses :26.2:testClasses"
```

Add exactly the imports each `unresolved reference` names, re-run, repeat until `BUILD SUCCESSFUL`. Do not relocate a file to silence an error.

- [ ] **Step 8: Run the suite**

```bash
cmd.exe /c "cd /d H:\Repo\garnet && gradlew.bat :26.2:test"
```

Expected: `BUILD SUCCESSFUL`. `ExplorerActionsTest`, `ExplorerLifecycleTest`, `ExplorerTreeStateTest`, `RootPickerControllerTest`, and the `editor/data` tests are the coverage for this task; confirm each in `versions/26.2/build/test-results/test/`.

- [ ] **Step 9: Update doc prose**

```bash
grep -rn 'ProjectExplorerPanel\|ProjectTreeState\|editor/data/\|editor/ops/' docs --exclude-dir=superpowers
```

Rewrite each hit to `ExplorerPanel.kt` / `ExplorerTreeSnapshot` / `editor/explorer/data/` / `editor/explorer/ops/`. Skip `module-map.md` (Task 10).

- [ ] **Step 10: Commit**

```bash
find src -type d -empty -delete
git add -A
git commit -m "refactor(editor): carve out editor/explorer sub-feature"
```

---

## Task 3: `editor/structure/`

Absorbs the top-level `structure/` package (pure NBT + region geometry) into the editor sub-feature that is its only consumer, and splits the existing `editor/structure/` files across `data/` and `ops/` on the read-vs-write seam.

**Files:**
- Move → `src/main/kotlin/com/breadmoirai/garnet/editor/structure/data/`: `structure/PlacedBox.kt`, `structure/StructureRegionMath.kt`, `structure/StructureDiff.kt`, `editor/structure/CommitOutcome.kt`
- Move → `src/main/kotlin/com/breadmoirai/garnet/editor/structure/ops/`: `structure/StructurePersistence.kt`, `editor/structure/StructureCommit.kt`, `StructureAutoSave.kt`, `StructureEditWatcher.kt`, `CommitBackoff.kt`
- Move → `src/main/kotlin/com/breadmoirai/garnet/editor/structure/network/`: `editor/network/EditorStructureHandlers.kt`
- Move → `src/client/kotlin/com/breadmoirai/garnet/editor/structure/ui/`: `editor/ui/StructureInfoPanel.kt`, `StructureInfoState.kt`, `OpenStructureState.kt`

`StructurePersistence` is in `ops/` because it writes `.nbt` files (`save`, `writeStructureAtomic`); `StructureDiff` and `StructureRegionMath` are pure functions and belong in `data/`.

**Interfaces:**
- Consumes: `editor.explorer.data.EditorRoot` (Task 2), `core.config.SharedSettings` (Task 1).
- Produces: `com.breadmoirai.garnet.editor.structure.data.{PlacedBox, StructureRegionMath, StructureDiff, CommitOutcome}`; `…structure.ops.{StructurePersistence, StructureCommit, StructureAutoSave, StructureEditWatcher, CommitBackoff}`; `…structure.network.EditorStructureHandlers`; `…structure.ui.{StructureInfoState, OpenStructureState, structureInfoPanel}`. Task 4's `StructureRestoreOps` imports `StructureCommit` and `StructurePersistence`.

- [ ] **Step 1: Move the files**

```bash
cd /mnt/h/Repo/Garnet
mkdir -p src/main/kotlin/com/breadmoirai/garnet/editor/structure/{data,ops,network}
mkdir -p src/client/kotlin/com/breadmoirai/garnet/editor/structure/ui
for f in PlacedBox StructureRegionMath StructureDiff; do
  git mv src/main/kotlin/com/breadmoirai/garnet/structure/$f.kt \
         src/main/kotlin/com/breadmoirai/garnet/editor/structure/data/$f.kt
done
git mv src/main/kotlin/com/breadmoirai/garnet/structure/StructurePersistence.kt \
       src/main/kotlin/com/breadmoirai/garnet/editor/structure/ops/StructurePersistence.kt
git mv src/main/kotlin/com/breadmoirai/garnet/editor/structure/CommitOutcome.kt \
       src/main/kotlin/com/breadmoirai/garnet/editor/structure/data/CommitOutcome.kt
for f in StructureCommit StructureAutoSave StructureEditWatcher CommitBackoff; do
  git mv src/main/kotlin/com/breadmoirai/garnet/editor/structure/$f.kt \
         src/main/kotlin/com/breadmoirai/garnet/editor/structure/ops/$f.kt
done
git mv src/main/kotlin/com/breadmoirai/garnet/editor/network/EditorStructureHandlers.kt \
       src/main/kotlin/com/breadmoirai/garnet/editor/structure/network/EditorStructureHandlers.kt
for f in StructureInfoPanel StructureInfoState OpenStructureState; do
  git mv src/client/kotlin/com/breadmoirai/garnet/editor/ui/$f.kt \
         src/client/kotlin/com/breadmoirai/garnet/editor/structure/ui/$f.kt
done
```

- [ ] **Step 2: Rewrite the FQNs**

Top-level `structure/` empties completely, so it gets a whole-package rewrite — but it splits two ways, so do it per class:

```bash
for c in PlacedBox StructureRegionMath StructureDiff; do
  grep -rl "com\.breadmoirai\.garnet\.structure\.$c\b" src docs --exclude-dir=superpowers \
    | xargs -r sed -i "s/com\.breadmoirai\.garnet\.structure\.$c\b/com.breadmoirai.garnet.editor.structure.data.$c/g"
done
grep -rl 'com\.breadmoirai\.garnet\.structure\.StructurePersistence\b' src docs --exclude-dir=superpowers \
  | xargs -r sed -i 's/com\.breadmoirai\.garnet\.structure\.StructurePersistence\b/com.breadmoirai.garnet.editor.structure.ops.StructurePersistence/g'
grep -rl 'com\.breadmoirai\.garnet\.editor\.structure\.CommitOutcome\b' src docs --exclude-dir=superpowers \
  | xargs -r sed -i 's/com\.breadmoirai\.garnet\.editor\.structure\.CommitOutcome\b/com.breadmoirai.garnet.editor.structure.data.CommitOutcome/g'
for c in StructureCommit StructureAutoSave StructureEditWatcher CommitBackoff; do
  grep -rl "com\.breadmoirai\.garnet\.editor\.structure\.$c\b" src docs --exclude-dir=superpowers \
    | xargs -r sed -i "s/com\.breadmoirai\.garnet\.editor\.structure\.$c\b/com.breadmoirai.garnet.editor.structure.ops.$c/g"
done
grep -rl 'com\.breadmoirai\.garnet\.editor\.network\.EditorStructureHandlers\b' src docs --exclude-dir=superpowers \
  | xargs -r sed -i 's/com\.breadmoirai\.garnet\.editor\.network\.EditorStructureHandlers\b/com.breadmoirai.garnet.editor.structure.network.EditorStructureHandlers/g'
for c in StructureInfoPanel StructureInfoState OpenStructureState; do
  grep -rl "com\.breadmoirai\.garnet\.editor\.ui\.$c\b" src docs --exclude-dir=superpowers \
    | xargs -r sed -i "s/com\.breadmoirai\.garnet\.editor\.ui\.$c\b/com.breadmoirai.garnet.editor.structure.ui.$c/g"
done
```

Then set every moved file's package line:

```bash
sed -i '0,/^package /{s|^package .*|package com.breadmoirai.garnet.editor.structure.data|}'    src/main/kotlin/com/breadmoirai/garnet/editor/structure/data/*.kt
sed -i '0,/^package /{s|^package .*|package com.breadmoirai.garnet.editor.structure.ops|}'     src/main/kotlin/com/breadmoirai/garnet/editor/structure/ops/*.kt
sed -i '0,/^package /{s|^package .*|package com.breadmoirai.garnet.editor.structure.network|}' src/main/kotlin/com/breadmoirai/garnet/editor/structure/network/*.kt
sed -i '0,/^package /{s|^package .*|package com.breadmoirai.garnet.editor.structure.ui|}'      src/client/kotlin/com/breadmoirai/garnet/editor/structure/ui/*.kt
grep -Hn '^package' src/main/kotlin/com/breadmoirai/garnet/editor/structure/*/*.kt \
        src/client/kotlin/com/breadmoirai/garnet/editor/structure/ui/*.kt
```

- [ ] **Step 3: Compile and add imports**

The `data`/`ops` split inside what was one package is where breakage concentrates: `StructureCommit` (ops) uses `StructureDiff` and `CommitOutcome` (data), `StructureAutoSave` (ops) uses `PlacedBox` (data). Each now needs an import.

```bash
cmd.exe /c "cd /d H:\Repo\garnet && gradlew.bat :26.2:clientClasses :26.2:classes :26.2:gametestClasses :26.2:clientTestClasses :26.2:testClasses"
```

Expected: `BUILD SUCCESSFUL` after adding them.

- [ ] **Step 4: Run the suite**

```bash
cmd.exe /c "cd /d H:\Repo\garnet && gradlew.bat :26.2:test"
```

Expected: `BUILD SUCCESSFUL`. `StructureDiffTest`, `StructureRegionMathTest`, and `StructureInfoStateTest` are this task's coverage.

- [ ] **Step 5: Update doc prose and commit**

```bash
grep -rn '`structure/`\|garnet/structure\|editor/structure/Structure' docs --exclude-dir=superpowers
```

Fix each hit (`structure/` → `editor/structure/data/` or `.../ops/` as appropriate); skip `module-map.md`.

```bash
find src -type d -empty -delete
git add -A
git commit -m "refactor(editor): fold top-level structure/ into editor/structure sub-feature"
```

---

## Task 4: `editor/history/`

Absorbs the top-level `history/` package and completes the sub-feature that `editor/history/` half-started.

**Files:**
- Move → `src/main/kotlin/com/breadmoirai/garnet/editor/history/data/`: `history/Revision.kt`, `history/LocalHistoryStore.kt`
- Move → `src/main/kotlin/com/breadmoirai/garnet/editor/history/ops/`: `editor/history/StructureRestoreOps.kt`
- Move → `src/main/kotlin/com/breadmoirai/garnet/editor/history/network/`: `editor/history/HistoryWatchers.kt`
- Move → `src/client/kotlin/com/breadmoirai/garnet/editor/history/ui/`: `editor/ui/LocalHistoryPanel.kt`, `LocalHistoryState.kt`

`RestoreOutcome` is declared in `StructureRestoreOps.kt` and referenced from `editor/undo/EditorUndoOps.kt`; it moves with its file.

**Interfaces:**
- Consumes: `editor.structure.ops.{StructureCommit, StructurePersistence}` (Task 3), `editor.explorer.data.EditorRoot` (Task 2), `core.config.SharedSettings` (Task 1).
- Produces: `com.breadmoirai.garnet.editor.history.data.{Revision, LocalHistoryStore}`; `…history.ops.{StructureRestoreOps, RestoreOutcome}`; `…history.network.HistoryWatchers`; `…history.ui.{LocalHistoryState, localHistoryPanel}`. Task 5's `EditorUndoCommand` imports `Revision`; `EditorUndoOps` imports `StructureRestoreOps` and `RestoreOutcome`.

- [ ] **Step 1: Move the files**

```bash
cd /mnt/h/Repo/Garnet
mkdir -p src/main/kotlin/com/breadmoirai/garnet/editor/history/{data,ops,network}
mkdir -p src/client/kotlin/com/breadmoirai/garnet/editor/history/ui
for f in Revision LocalHistoryStore; do
  git mv src/main/kotlin/com/breadmoirai/garnet/history/$f.kt \
         src/main/kotlin/com/breadmoirai/garnet/editor/history/data/$f.kt
done
git mv src/main/kotlin/com/breadmoirai/garnet/editor/history/StructureRestoreOps.kt \
       src/main/kotlin/com/breadmoirai/garnet/editor/history/ops/StructureRestoreOps.kt
git mv src/main/kotlin/com/breadmoirai/garnet/editor/history/HistoryWatchers.kt \
       src/main/kotlin/com/breadmoirai/garnet/editor/history/network/HistoryWatchers.kt
for f in LocalHistoryPanel LocalHistoryState; do
  git mv src/client/kotlin/com/breadmoirai/garnet/editor/ui/$f.kt \
         src/client/kotlin/com/breadmoirai/garnet/editor/history/ui/$f.kt
done
```

- [ ] **Step 2: Rewrite the FQNs**

Top-level `history/` empties completely and lands in one destination, so a whole-package rewrite works there:

```bash
grep -rl 'com\.breadmoirai\.garnet\.history\b' src docs --exclude-dir=superpowers \
  | xargs -r sed -i 's/com\.breadmoirai\.garnet\.history\b/com.breadmoirai.garnet.editor.history.data/g'
for c in StructureRestoreOps RestoreOutcome; do
  grep -rl "com\.breadmoirai\.garnet\.editor\.history\.$c\b" src docs --exclude-dir=superpowers \
    | xargs -r sed -i "s/com\.breadmoirai\.garnet\.editor\.history\.$c\b/com.breadmoirai.garnet.editor.history.ops.$c/g"
done
grep -rl 'com\.breadmoirai\.garnet\.editor\.history\.HistoryWatchers\b' src docs --exclude-dir=superpowers \
  | xargs -r sed -i 's/com\.breadmoirai\.garnet\.editor\.history\.HistoryWatchers\b/com.breadmoirai.garnet.editor.history.network.HistoryWatchers/g'
for c in LocalHistoryPanel LocalHistoryState; do
  grep -rl "com\.breadmoirai\.garnet\.editor\.ui\.$c\b" src docs --exclude-dir=superpowers \
    | xargs -r sed -i "s/com\.breadmoirai\.garnet\.editor\.ui\.$c\b/com.breadmoirai.garnet.editor.history.ui.$c/g"
done
sed -i '0,/^package /{s|^package .*|package com.breadmoirai.garnet.editor.history.data|}'    src/main/kotlin/com/breadmoirai/garnet/editor/history/data/*.kt
sed -i '0,/^package /{s|^package .*|package com.breadmoirai.garnet.editor.history.ops|}'     src/main/kotlin/com/breadmoirai/garnet/editor/history/ops/*.kt
sed -i '0,/^package /{s|^package .*|package com.breadmoirai.garnet.editor.history.network|}' src/main/kotlin/com/breadmoirai/garnet/editor/history/network/*.kt
sed -i '0,/^package /{s|^package .*|package com.breadmoirai.garnet.editor.history.ui|}'      src/client/kotlin/com/breadmoirai/garnet/editor/history/ui/*.kt
grep -Hn '^package' src/main/kotlin/com/breadmoirai/garnet/editor/history/*/*.kt \
        src/client/kotlin/com/breadmoirai/garnet/editor/history/ui/*.kt
```

Watch the first rewrite: the whole-package `history` → `editor.history.data` pass also hits any pre-existing `com.breadmoirai.garnet.editor.history` string, turning it into `…editor.editor.history.data`. Check for that before moving on:

```bash
grep -rn 'editor\.editor\|history\.data\.StructureRestoreOps\|history\.data\.HistoryWatchers' src docs --exclude-dir=superpowers
```

Expected: nothing. If anything prints, fix those lines by hand — the per-class rewrites in the same step were meant to catch them.

- [ ] **Step 3: Compile and add imports**

`StructureCommit` (structure/ops) imports `LocalHistoryStore`, and `StructureRestoreOps` (history/ops) imports `StructureCommit` — the intentional two-node, one-direction-each relationship from the spec. Both are cross-package now and need explicit imports.

```bash
cmd.exe /c "cd /d H:\Repo\garnet && gradlew.bat :26.2:clientClasses :26.2:classes :26.2:gametestClasses :26.2:clientTestClasses :26.2:testClasses"
```

- [ ] **Step 4: Run the suite**

```bash
cmd.exe /c "cd /d H:\Repo\garnet && gradlew.bat :26.2:test"
```

Expected: `BUILD SUCCESSFUL`; `LocalHistoryStateTest` is the unit coverage, `LocalHistoryStoreSpec` the gametest coverage (the latter only runs in a game run — see Task 10).

- [ ] **Step 5: Update doc prose and commit**

```bash
grep -rn '`history/`\|garnet/history' docs --exclude-dir=superpowers
find src -type d -empty -delete
git add -A
git commit -m "refactor(editor): fold top-level history/ into editor/history sub-feature"
```

---

## Task 5: `editor/undo/`

The smallest sub-feature, and the one carrying the spec's recorded layering exception.

**Files:**
- Move → `src/main/kotlin/com/breadmoirai/garnet/editor/undo/data/`: `editor/undo/EditorUndoStack.kt`, `EditorUndoCommand.kt`
- Move → `src/main/kotlin/com/breadmoirai/garnet/editor/undo/ops/`: `editor/undo/EditorUndoOps.kt`
- Move → `src/client/kotlin/com/breadmoirai/garnet/editor/undo/ui/`: `editor/ui/UndoState.kt`

`editor/undo/network/` is created in Task 7, when the undo payloads split out of `EditorPackets`.

**Interfaces:**
- Consumes: `editor.history.data.Revision` and `editor.history.ops.{StructureRestoreOps, RestoreOutcome}` (Task 4), `editor.explorer.network.{EditorFileOpsHandlers, DeleteOutcome}` (Task 2), `editor.workspace.world.{EditorDimLifecycle, EditorRootResolver}` (Task 6 — still at `editor.world.*` when this task runs, and rewritten by Task 6).
- Produces: `com.breadmoirai.garnet.editor.undo.data.{EditorUndoStack, EditorUndoCommand, RelocateKind, CreatedFileKind}`; `…undo.ops.EditorUndoOps`; `…undo.ui.UndoState`.

- [ ] **Step 1: Move and rewrite**

```bash
cd /mnt/h/Repo/Garnet
mkdir -p src/main/kotlin/com/breadmoirai/garnet/editor/undo/{data,ops}
mkdir -p src/client/kotlin/com/breadmoirai/garnet/editor/undo/ui
for f in EditorUndoStack EditorUndoCommand; do
  git mv src/main/kotlin/com/breadmoirai/garnet/editor/undo/$f.kt \
         src/main/kotlin/com/breadmoirai/garnet/editor/undo/data/$f.kt
done
git mv src/main/kotlin/com/breadmoirai/garnet/editor/undo/EditorUndoOps.kt \
       src/main/kotlin/com/breadmoirai/garnet/editor/undo/ops/EditorUndoOps.kt
git mv src/client/kotlin/com/breadmoirai/garnet/editor/ui/UndoState.kt \
       src/client/kotlin/com/breadmoirai/garnet/editor/undo/ui/UndoState.kt
for c in EditorUndoStack EditorUndoCommand RelocateKind CreatedFileKind; do
  grep -rl "com\.breadmoirai\.garnet\.editor\.undo\.$c\b" src docs --exclude-dir=superpowers \
    | xargs -r sed -i "s/com\.breadmoirai\.garnet\.editor\.undo\.$c\b/com.breadmoirai.garnet.editor.undo.data.$c/g"
done
grep -rl 'com\.breadmoirai\.garnet\.editor\.undo\.EditorUndoOps\b' src docs --exclude-dir=superpowers \
  | xargs -r sed -i 's/com\.breadmoirai\.garnet\.editor\.undo\.EditorUndoOps\b/com.breadmoirai.garnet.editor.undo.ops.EditorUndoOps/g'
grep -rl 'com\.breadmoirai\.garnet\.editor\.ui\.UndoState\b' src docs --exclude-dir=superpowers \
  | xargs -r sed -i 's/com\.breadmoirai\.garnet\.editor\.ui\.UndoState\b/com.breadmoirai.garnet.editor.undo.ui.UndoState/g'
sed -i '0,/^package /{s|^package .*|package com.breadmoirai.garnet.editor.undo.data|}' src/main/kotlin/com/breadmoirai/garnet/editor/undo/data/*.kt
sed -i '0,/^package /{s|^package .*|package com.breadmoirai.garnet.editor.undo.ops|}'  src/main/kotlin/com/breadmoirai/garnet/editor/undo/ops/*.kt
sed -i '0,/^package /{s|^package .*|package com.breadmoirai.garnet.editor.undo.ui|}'   src/client/kotlin/com/breadmoirai/garnet/editor/undo/ui/*.kt
grep -Hn '^package' src/main/kotlin/com/breadmoirai/garnet/editor/undo/*/*.kt \
        src/client/kotlin/com/breadmoirai/garnet/editor/undo/ui/*.kt
```

- [ ] **Step 2: Add a comment recording the layering exception**

`EditorUndoOps` is the only `ops → network` import in the codebase, and the spec says it must be visible rather than hidden. Add this KDoc paragraph to the `EditorUndoOps` declaration (append to the existing KDoc; do not replace it):

```kotlin
 *
 * **Layering exception.** This file imports `explorer/network` ([EditorFileOpsHandlers]) and the
 * `editor/network` spine ([EditorHandlerSupport]), against the usual `ops` → `data` direction,
 * because undoing a file operation replays it through the very handlers the client would have
 * invoked. This is the codebase's only `ops` → `network` edge and is recorded in
 * `docs/superpowers/specs/2026-08-16-feature-sub-package-layout-design.md`. A second one means
 * the rule is wrong and should be revisited, not extended.
```

- [ ] **Step 3: Compile, test, commit**

```bash
cmd.exe /c "cd /d H:\Repo\garnet && gradlew.bat :26.2:clientClasses :26.2:classes :26.2:gametestClasses :26.2:clientTestClasses :26.2:testClasses"
cmd.exe /c "cd /d H:\Repo\garnet && gradlew.bat :26.2:test"
find src -type d -empty -delete
git add -A
git commit -m "refactor(editor): split editor/undo into data, ops, and ui layers"
```

Expected: both `BUILD SUCCESSFUL`; `EditorUndoStackTest` green.

---

## Task 6: `editor/workspace/`

Renames `editor/world/` to `editor/workspace/world/` and gathers the command and the client boot entry point under the same sub-feature. `editor/ui/` is emptied by this task and must not exist afterward.

**Files:**
- Move → `src/main/kotlin/com/breadmoirai/garnet/editor/workspace/world/`: all 8 files of `editor/world/` (`EditorWorld.kt`, `EditorDimRegistry.kt`, `EditorDimLifecycle.kt`, `EditorCellSaver.kt`, `EditorTeleport.kt`, `EditorServerContext.kt`, `EditorRootResolver.kt`, `GridLayout.kt`)
- Move → `src/main/kotlin/com/breadmoirai/garnet/editor/workspace/command/`: `editor/command/EditorCommand.kt`
- Move → `src/client/kotlin/com/breadmoirai/garnet/editor/workspace/ui/`: `editor/world/EditorIntegratedBoot.kt` (client), `ui/widget/GarnetIconButton.kt`

**Interfaces:**
- Consumes: `editor.explorer.data.*` (Task 2), `editor.structure.ops.*` (Task 3), `core.spec.*` and `core.config.SharedSettings` (Task 1), `testing.data.SpecPersistence`.
- Produces: `com.breadmoirai.garnet.editor.workspace.world.{EditorWorld, EditorDimRegistry, EditorDimLifecycle, EditorCellSaver, EditorTeleport, EditorServerContext, EditorRootResolver, GridLayout}`; `…workspace.command.EditorCommand`; `…workspace.ui.{bootWorkspace, GarnetIconButton}`.

- [ ] **Step 1: Move and rewrite**

```bash
cd /mnt/h/Repo/Garnet
mkdir -p src/main/kotlin/com/breadmoirai/garnet/editor/workspace/{world,command}
mkdir -p src/client/kotlin/com/breadmoirai/garnet/editor/workspace/ui
git mv src/main/kotlin/com/breadmoirai/garnet/editor/world/*.kt \
       src/main/kotlin/com/breadmoirai/garnet/editor/workspace/world/
git mv src/main/kotlin/com/breadmoirai/garnet/editor/command/EditorCommand.kt \
       src/main/kotlin/com/breadmoirai/garnet/editor/workspace/command/EditorCommand.kt
git mv src/client/kotlin/com/breadmoirai/garnet/editor/world/EditorIntegratedBoot.kt \
       src/client/kotlin/com/breadmoirai/garnet/editor/workspace/ui/EditorIntegratedBoot.kt
git mv src/client/kotlin/com/breadmoirai/garnet/ui/widget/GarnetIconButton.kt \
       src/client/kotlin/com/breadmoirai/garnet/editor/workspace/ui/GarnetIconButton.kt
```

`editor/world` splits two ways (main → `workspace/world`, the client boot file → `workspace/ui`), so rewrite the world package wholesale first, then correct the boot file:

```bash
grep -rl 'com\.breadmoirai\.garnet\.editor\.world\b' src docs --exclude-dir=superpowers \
  | xargs -r sed -i 's/com\.breadmoirai\.garnet\.editor\.world\b/com.breadmoirai.garnet.editor.workspace.world/g'
grep -rl 'com\.breadmoirai\.garnet\.editor\.command\b' src docs --exclude-dir=superpowers \
  | xargs -r sed -i 's/com\.breadmoirai\.garnet\.editor\.command\b/com.breadmoirai.garnet.editor.workspace.command/g'
grep -rl 'com\.breadmoirai\.garnet\.ui\.widget\b' src docs --exclude-dir=superpowers \
  | xargs -r sed -i 's/com\.breadmoirai\.garnet\.ui\.widget\b/com.breadmoirai.garnet.editor.workspace.ui/g'
sed -i '0,/^package /{s|^package .*|package com.breadmoirai.garnet.editor.workspace.ui|}' \
  src/client/kotlin/com/breadmoirai/garnet/editor/workspace/ui/EditorIntegratedBoot.kt
```

`bootWorkspace` is a top-level function whose callers (`TitleScreenMixin` via `GarnetClient`, and `GarnetIconButton`) import it by FQN; the world-package rewrite pointed them at `workspace.world`, which is now wrong. Fix:

```bash
grep -rl 'com\.breadmoirai\.garnet\.editor\.workspace\.world\.bootWorkspace\b' src docs --exclude-dir=superpowers \
  | xargs -r sed -i 's/com\.breadmoirai\.garnet\.editor\.workspace\.world\.bootWorkspace\b/com.breadmoirai.garnet.editor.workspace.ui.bootWorkspace/g'
```

- [ ] **Step 2: Confirm `editor/ui/` and `editor/world/` are gone**

```bash
ls src/client/kotlin/com/breadmoirai/garnet/editor/ui src/main/kotlin/com/breadmoirai/garnet/editor/world 2>&1
```

Expected: "No such file or directory" for both. If either still has files, they were missed by Tasks 2–5 — place them per the spec's target layout before continuing.

- [ ] **Step 3: Compile, test, commit**

```bash
cmd.exe /c "cd /d H:\Repo\garnet && gradlew.bat :26.2:clientClasses :26.2:classes :26.2:gametestClasses :26.2:clientTestClasses :26.2:testClasses"
cmd.exe /c "cd /d H:\Repo\garnet && gradlew.bat :26.2:test"
grep -rn 'editor/world/\|editor/command/\|ui/widget/' docs --exclude-dir=superpowers
find src -type d -empty -delete
git add -A
git commit -m "refactor(editor): gather the workspace substrate under editor/workspace"
```

Expected: both builds successful; `GridLayoutTest`, `EditorDimRegistryTest`, `EditorLifecycleReleaseTest` green. Fix any doc paths the `grep` prints (skip `module-map.md`).

---

## Task 7: The `editor/network/` spine and the `EditorPackets` split

Splits the 477-line `EditorPackets.kt` four ways along the sub-feature seam, leaving the genuinely cross-cutting spine behind.

**Files:**
- Modify → split: `src/main/kotlin/com/breadmoirai/garnet/editor/network/EditorPackets.kt`
- Create: `src/main/kotlin/com/breadmoirai/garnet/editor/explorer/network/ExplorerPackets.kt` — `ListEditorTreeC2S`, `LoadEditorFolderC2S`, `UnloadEditorFolderC2S`, `SetEditorRootC2S`, `CreateFolderC2S`, `NewEditorSpecC2S`, `NewStructureC2S`, `RenamePathC2S`, `MovePathC2S`, `DuplicatePathC2S`, `DeletePathC2S`, `EditorTreeSnapshotS2C`, `EditorFolderLoadedS2C`, `EditorErrorS2C`
- Create: `src/main/kotlin/com/breadmoirai/garnet/editor/structure/network/StructurePackets.kt` — `SaveStructureC2S`, `PlaceStructureC2S`, `SaveNowC2S`, `StructureResultS2C`, `StructureAutoSavedS2C`, `EditorSaveReportS2C`
- Create: `src/main/kotlin/com/breadmoirai/garnet/editor/history/network/HistoryPackets.kt` — `WatchStructureHistoryC2S`, `RestoreRevisionC2S`, `StructureHistoryS2C`, `RevisionEntry`
- Create: `src/main/kotlin/com/breadmoirai/garnet/editor/undo/network/UndoPackets.kt` — `UndoC2S`, `RedoC2S`, `UndoStateS2C`
- Keep in `src/main/kotlin/com/breadmoirai/garnet/editor/network/`: `EditorNetworkRegistry.kt`, `EditorHandlerSupport.kt`, and (client) `EditorClientNetworking.kt`
- Delete when emptied: `EditorPackets.kt`

**Interfaces:**
- Consumes: every sub-feature package from Tasks 2–6.
- Produces: the payload classes above at their new FQNs. `EditorNetworkRegistry` and `EditorClientNetworking` import all four payload files.

- [ ] **Step 1: Read the file before cutting it**

```bash
cat src/main/kotlin/com/breadmoirai/garnet/editor/network/EditorPackets.kt
```

Note where the shared helpers live — codec helpers, `CustomPayload.Id` construction, any shared serialization extension. **Shared helpers stay in `editor/network/`**, in `EditorHandlerSupport.kt` or a new `PacketCodecs.kt` beside it, and get imported by the four new files. Do not duplicate a helper into all four.

- [ ] **Step 2: Create the four payload files**

Move each payload declaration verbatim — the class body, its KDoc, its `CODEC`/`ID` members — into the file listed above. Each new file starts with its destination package and imports whatever the moved declarations referenced:

```kotlin
package com.breadmoirai.garnet.editor.explorer.network

// imports as the compiler requires
```

Do not renumber, rename, or restructure a payload. The wire format must be byte-identical: same class names, same field order, same codec order, same payload IDs.

- [ ] **Step 3: Delete the emptied file and rewrite its FQN references**

```bash
cd /mnt/h/Repo/Garnet
git rm src/main/kotlin/com/breadmoirai/garnet/editor/network/EditorPackets.kt
for c in ListEditorTreeC2S LoadEditorFolderC2S UnloadEditorFolderC2S SetEditorRootC2S CreateFolderC2S \
         NewEditorSpecC2S NewStructureC2S RenamePathC2S MovePathC2S DuplicatePathC2S DeletePathC2S \
         EditorTreeSnapshotS2C EditorFolderLoadedS2C EditorErrorS2C; do
  grep -rl "com\.breadmoirai\.garnet\.editor\.network\.$c\b" src docs --exclude-dir=superpowers \
    | xargs -r sed -i "s/com\.breadmoirai\.garnet\.editor\.network\.$c\b/com.breadmoirai.garnet.editor.explorer.network.$c/g"
done
for c in SaveStructureC2S PlaceStructureC2S SaveNowC2S StructureResultS2C StructureAutoSavedS2C EditorSaveReportS2C; do
  grep -rl "com\.breadmoirai\.garnet\.editor\.network\.$c\b" src docs --exclude-dir=superpowers \
    | xargs -r sed -i "s/com\.breadmoirai\.garnet\.editor\.network\.$c\b/com.breadmoirai.garnet.editor.structure.network.$c/g"
done
for c in WatchStructureHistoryC2S RestoreRevisionC2S StructureHistoryS2C RevisionEntry; do
  grep -rl "com\.breadmoirai\.garnet\.editor\.network\.$c\b" src docs --exclude-dir=superpowers \
    | xargs -r sed -i "s/com\.breadmoirai\.garnet\.editor\.network\.$c\b/com.breadmoirai.garnet.editor.history.network.$c/g"
done
for c in UndoC2S RedoC2S UndoStateS2C; do
  grep -rl "com\.breadmoirai\.garnet\.editor\.network\.$c\b" src docs --exclude-dir=superpowers \
    | xargs -r sed -i "s/com\.breadmoirai\.garnet\.editor\.network\.$c\b/com.breadmoirai.garnet.editor.undo.network.$c/g"
done
```

- [ ] **Step 4: Compile and add imports**

`EditorNetworkRegistry`, `EditorHandlerSupport`, `EditorClientNetworking`, and all four handler files referenced these payloads with no import (same package). Every one now needs explicit imports — expect many.

```bash
cmd.exe /c "cd /d H:\Repo\garnet && gradlew.bat :26.2:clientClasses :26.2:classes :26.2:gametestClasses :26.2:clientTestClasses :26.2:testClasses"
```

- [ ] **Step 5: Prove the wire format is unchanged**

```bash
cmd.exe /c "cd /d H:\Repo\garnet && gradlew.bat :26.2:test"
```

Expected: `BUILD SUCCESSFUL`, with `EditorStructurePacketsTest`, `EditorUndoPacketsTest`, and `FileTreeCodecTest` green — these are codec round-trip tests and are the direct evidence that the split did not perturb serialization. Read their XML reports explicitly; do not accept the console summary alone.

- [ ] **Step 6: Commit**

```bash
find src -type d -empty -delete
git add -A
git commit -m "refactor(editor): split EditorPackets across the four sub-features"
```

---

## Task 8: `dock/`

Renames the top-level `ui/` package to `dock/`, renames its `dock/` layer to `shell/` (so the path is not `dock/dock/`), and absorbs `DockLayoutStore`.

**Files:**
- Move → `src/client/kotlin/com/breadmoirai/garnet/dock/shell/`: all 8 files of `ui/dock/`
- Move → `src/client/kotlin/com/breadmoirai/garnet/dock/compose/`: all 7 files of `ui/compose/`
- Move → `src/client/kotlin/com/breadmoirai/garnet/dock/input/`: `ui/input/DockInputRouter.kt`, `GlfwKeyMap.kt`
- Move → `src/client/kotlin/com/breadmoirai/garnet/dock/viewport/`: all 8 files of `ui/viewport/` plus `src/client/java/com/breadmoirai/garnet/client/viewport/WindowViewportExt.java`
- Move → `src/client/kotlin/com/breadmoirai/garnet/dock/data/`: `config/DockLayoutStore.kt`
- `ui/widget/GarnetIconButton.kt` already left in Task 6; `client/config/` empties here.

**Interfaces:**
- Consumes: `core.config.SharedSettings` (Task 1).
- Produces: `com.breadmoirai.garnet.dock.shell.{GarnetDock, DockState, DockRegion, DockInsets, Panel, DockStripe, DockAutoOpen, DockHitTest}`; `…dock.compose.*`; `…dock.input.*`; `…dock.viewport.*`; `…dock.data.DockLayoutStore`.

- [ ] **Step 1: Move and rewrite**

```bash
cd /mnt/h/Repo/Garnet
mkdir -p src/client/kotlin/com/breadmoirai/garnet/dock/{shell,compose,input,viewport,data}
git mv src/client/kotlin/com/breadmoirai/garnet/ui/dock/*.kt     src/client/kotlin/com/breadmoirai/garnet/dock/shell/
git mv src/client/kotlin/com/breadmoirai/garnet/ui/compose/*.kt  src/client/kotlin/com/breadmoirai/garnet/dock/compose/
git mv src/client/kotlin/com/breadmoirai/garnet/ui/input/*.kt    src/client/kotlin/com/breadmoirai/garnet/dock/input/
git mv src/client/kotlin/com/breadmoirai/garnet/ui/viewport/*.kt src/client/kotlin/com/breadmoirai/garnet/dock/viewport/
mkdir -p src/client/java/com/breadmoirai/garnet/dock/viewport
git mv src/client/java/com/breadmoirai/garnet/client/viewport/WindowViewportExt.java \
       src/client/java/com/breadmoirai/garnet/dock/viewport/WindowViewportExt.java
git mv src/client/kotlin/com/breadmoirai/garnet/config/DockLayoutStore.kt \
       src/client/kotlin/com/breadmoirai/garnet/dock/data/DockLayoutStore.kt

grep -rl 'com\.breadmoirai\.garnet\.ui\.dock\b' src docs --exclude-dir=superpowers \
  | xargs -r sed -i 's/com\.breadmoirai\.garnet\.ui\.dock\b/com.breadmoirai.garnet.dock.shell/g'
for p in compose input viewport; do
  grep -rl "com\.breadmoirai\.garnet\.ui\.$p\b" src docs --exclude-dir=superpowers \
    | xargs -r sed -i "s/com\.breadmoirai\.garnet\.ui\.$p\b/com.breadmoirai.garnet.dock.$p/g"
done
grep -rl 'com\.breadmoirai\.garnet\.client\.viewport\b' src docs --exclude-dir=superpowers \
  | xargs -r sed -i 's/com\.breadmoirai\.garnet\.client\.viewport\b/com.breadmoirai.garnet.dock.viewport/g'
grep -rl 'com\.breadmoirai\.garnet\.config\.DockLayoutStore\b' src docs --exclude-dir=superpowers \
  | xargs -r sed -i 's/com\.breadmoirai\.garnet\.config\.DockLayoutStore\b/com.breadmoirai.garnet.dock.data.DockLayoutStore/g'
sed -i '0,/^package /{s|^package .*|package com.breadmoirai.garnet.dock.data|}' src/client/kotlin/com/breadmoirai/garnet/dock/data/DockLayoutStore.kt
```

- [ ] **Step 2: Check the Java file and the mixins**

`WindowViewportExt.java` is Java — its `package` line must match its new directory, and the client mixins reference it:

```bash
head -3 src/client/java/com/breadmoirai/garnet/dock/viewport/WindowViewportExt.java
grep -rn 'viewport\|WindowViewportExt' src/client/resources/garnet.client.mixins.json
```

Expected: the package line reads `package com.breadmoirai.garnet.dock.viewport;`. The mixins JSON lists only classes under `com.breadmoirai.garnet.mixin.client` — which did not move — so it should need no edit. If it names anything else, fix it now.

- [ ] **Step 3: Confirm `ui/` and `client/config/` are gone**

```bash
ls src/client/kotlin/com/breadmoirai/garnet/ui src/client/kotlin/com/breadmoirai/garnet/config 2>&1
```

Expected: "No such file or directory" for both.

- [ ] **Step 4: Compile, test, commit**

```bash
cmd.exe /c "cd /d H:\Repo\garnet && gradlew.bat :26.2:clientClasses :26.2:classes :26.2:gametestClasses :26.2:clientTestClasses :26.2:testClasses"
cmd.exe /c "cd /d H:\Repo\garnet && gradlew.bat :26.2:test"
grep -rn '`ui/`\|ui/dock/\|ui/compose/\|ui/viewport/\|ui/input/\|ui/widget/' docs --exclude-dir=superpowers
find src -type d -empty -delete
git add -A
git commit -m "refactor(dock): rename ui/ to dock/ and absorb DockLayoutStore"
```

Expected: both builds successful; the ten `client/ui/dock` unit tests green. Fix the doc paths the `grep` prints (skip `module-map.md`).

---

## Task 9: Repackage the test source sets

Every test moves into the package of the code it covers. The `test.` and `client.` infix segments disappear.

**Files:** all of `src/test/kotlin/com/breadmoirai/garnet/**`, `src/gametest/kotlin/com/breadmoirai/garnet/test/**`, `src/clientTest/kotlin/com/breadmoirai/garnet/test/**`. `src/testSupport/**` does **not** move.

**Interfaces:**
- Consumes: every production package from Tasks 1–8.
- Produces: nothing other tasks depend on.

- [ ] **Step 1: Move `src/test` — drop the `client.` infix**

The earlier tasks already rewrote these files' imports; only their own package and path change now.

```bash
cd /mnt/h/Repo/Garnet
T=src/test/kotlin/com/breadmoirai/garnet
mkdir -p $T/{core/spec,core/config,editor/explorer/{data,ops,ui},editor/structure/{data,ui},editor/history/ui,editor/undo/data,editor/workspace/world,editor/explorer/network,editor/structure/network,editor/undo/network,dock/{shell,compose,input,data}}
# client UI tests
git mv $T/client/editor/ui/ExplorerActionsTest.kt        $T/editor/explorer/ui/
git mv $T/client/editor/ui/ExplorerClickTest.kt          $T/editor/explorer/ui/
git mv $T/client/editor/ui/ExplorerLifecycleTest.kt      $T/editor/explorer/ui/
git mv $T/client/editor/ui/ExplorerRestoreRenderTest.kt  $T/editor/explorer/ui/
git mv $T/client/editor/ui/ExplorerRowClickSceneTest.kt  $T/editor/explorer/ui/
git mv $T/client/editor/ui/ExplorerTreeStateTest.kt      $T/editor/explorer/ui/
git mv $T/client/editor/ui/InlineNameFieldKeyRoutingTest.kt $T/editor/explorer/ui/
git mv $T/client/editor/ui/RootPickerControllerTest.kt   $T/editor/explorer/ui/
git mv $T/client/editor/ui/LocalHistoryStateTest.kt      $T/editor/history/ui/
git mv $T/client/editor/ui/StructureInfoStateTest.kt     $T/editor/structure/ui/
git mv $T/client/config/ExplorerStateStoreTest.kt        $T/editor/explorer/ui/
# dock tests
git mv $T/client/ui/compose/*.kt $T/dock/compose/
git mv $T/client/ui/input/*.kt   $T/dock/input/
git mv $T/client/ui/dock/DockLayoutStoreTest.kt $T/dock/data/
git mv $T/client/ui/dock/*.kt    $T/dock/shell/
# main-side tests
git mv $T/editor/data/*.kt    $T/editor/explorer/data/
git mv $T/editor/ops/*.kt     $T/editor/explorer/ops/
git mv $T/editor/undo/*.kt    $T/editor/undo/data/
git mv $T/editor/world/*.kt   $T/editor/workspace/world/
git mv $T/structure/StructureDiffTest.kt       $T/editor/structure/data/
git mv $T/structure/StructureRegionMathTest.kt $T/editor/structure/data/
git mv $T/editor/network/EditorStructurePacketsTest.kt $T/editor/structure/network/
git mv $T/editor/network/EditorUndoPacketsTest.kt      $T/editor/undo/network/
git mv $T/editor/network/FileTreeCodecTest.kt          $T/editor/explorer/network/
git mv $T/mc/SuspendingTest.kt $T/core/async/ 2>/dev/null || { mkdir -p $T/core/async && git mv $T/mc/SuspendingTest.kt $T/core/async/; }
```

`src/test/.../harness/`, `playback/`, and `testing/` already sit in the package of the code they cover and do not move.

- [ ] **Step 2: Set each moved test's package line from its path**

Rather than 30 hand-written `sed` calls, derive the package from the directory:

```bash
# git mv stages renames as R, not A — list every staged path under src/test and
# filter to the ones that exist on disk now (the rename destinations).
for f in $(git diff --cached --name-only -- 'src/test/*'); do
  [ -f "$f" ] || continue
  pkg=$(dirname "$f" | sed 's|src/test/kotlin/||; s|/|.|g')
  sed -i "0,/^package /{s|^package .*|package $pkg|}" "$f"
done
grep -c '^package' $(git diff --cached --name-only -- 'src/test/*' | while read f; do [ -f "$f" ] && echo "$f"; done)
```

Every file must report exactly `1`. A `0` means the file had no package declaration to replace; a `2` means one was duplicated. Both mean the rewrite went wrong — inspect `git diff` and fix by hand.

Then confirm the packages match the new paths before compiling.

- [ ] **Step 3: Compile the test source sets**

```bash
cmd.exe /c "cd /d H:\Repo\garnet && gradlew.bat :26.2:testClasses"
cmd.exe /c "cd /d H:\Repo\garnet && gradlew.bat :26.2:test"
```

Expected: both successful, with the **same test count** as before the move. Compare against the previous run's `versions/26.2/build/test-results/test/` — a test that silently stopped being discovered is the failure mode this step is guarding against.

- [ ] **Step 4: Commit, then move gametest**

```bash
git add -A && git commit -m "refactor(test): mirror the feature tree in src/test"

G=src/gametest/kotlin/com/breadmoirai/garnet
mkdir -p $G/editor/{explorer/{network,ops},structure/{network,ops},history/ops,workspace/world}
git mv $G/test/editor/EditorCommandSpec.kt        $G/editor/workspace/command/ 2>/dev/null || \
  { mkdir -p $G/editor/workspace/command && git mv $G/test/editor/EditorCommandSpec.kt $G/editor/workspace/command/; }
git mv $G/test/editor/EditorCellSaverSpec.kt      $G/editor/workspace/world/
git mv $G/test/editor/EditorDimSpec.kt            $G/editor/workspace/world/
git mv $G/test/editor/EditorTeleportSpec.kt       $G/editor/workspace/world/
git mv $G/test/editor/EditorFileOpsNetworkSpec.kt $G/editor/explorer/network/
git mv $G/test/editor/EditorNetworkRegistrySpec.kt $G/editor/explorer/network/
git mv $G/test/editor/EditorStructureNetworkSpec.kt $G/editor/structure/network/
git mv $G/test/editor/EditorUndoNetworkSpec.kt    $G/editor/undo/network/ 2>/dev/null || \
  { mkdir -p $G/editor/undo/network && git mv $G/test/editor/EditorUndoNetworkSpec.kt $G/editor/undo/network/; }
git mv $G/test/editor/StructureAutoSaveSpec.kt    $G/editor/structure/ops/
git mv $G/test/editor/StructureRestoreSpec.kt     $G/editor/history/ops/
git mv $G/test/structure/*.kt                     $G/editor/structure/ops/
git mv $G/test/history/LocalHistoryStoreSpec.kt   $G/editor/history/ops/ 2>/dev/null || \
  { mkdir -p $G/editor/history/data && git mv $G/test/history/LocalHistoryStoreSpec.kt $G/editor/history/data/; }
```

`GametestSentinel.kt`, `SmokeSpec.kt`, `NetworkTestSupport.kt`, and `EditorTestSupport.kt` serve more than one feature: leave `GametestSentinel`, `SmokeSpec`, and `NetworkTestSupport` at `com.breadmoirai.garnet.test`, and move `EditorTestSupport.kt` to `$G/editor/` (it is editor-wide, not sub-feature-specific).

Then set package lines the same way as Step 2 (substituting `src/gametest/kotlin/`), and verify:

```bash
cmd.exe /c "cd /d H:\Repo\garnet && gradlew.bat :26.2:gametestClasses"
git add -A && git commit -m "refactor(gametest): mirror the feature tree in src/gametest"
```

- [ ] **Step 5: Move clientTest**

```bash
C=src/clientTest/kotlin/com/breadmoirai/garnet
mkdir -p $C/editor/explorer/ui $C/dock/{shell,viewport}
git mv $C/test/ExplorerUiSpec.kt       $C/editor/explorer/ui/
git mv $C/test/JewelExplorerSpec.kt    $C/editor/explorer/ui/
git mv $C/test/DockInputSpec.kt        $C/dock/shell/
git mv $C/test/DockRenderSpec.kt       $C/dock/shell/
git mv $C/test/PanelPixelProbe.kt      $C/dock/shell/
git mv $C/test/ViewportSpec.kt         $C/dock/viewport/
git mv $C/test/CursorFocusToggleSpec.kt $C/dock/viewport/
```

`ClientTestSentinel.kt`, `ClientTestSupport.kt`, `SpecTestContext.kt`, and `RunGarnetSpecSmokeTest.kt` serve every client spec — leave them at `com.breadmoirai.garnet.test`. Set package lines as before, then:

```bash
cmd.exe /c "cd /d H:\Repo\garnet && gradlew.bat :26.2:clientTestClasses"
find src -type d -empty -delete
git add -A && git commit -m "refactor(clientTest): mirror the feature tree in src/clientTest"
```

- [ ] **Step 6: Full verification**

```bash
cmd.exe /c "cd /d H:\Repo\garnet && gradlew.bat :26.2:clientClasses :26.2:classes :26.2:gametestClasses :26.2:clientTestClasses :26.2:testClasses"
cmd.exe /c "cd /d H:\Repo\garnet && gradlew.bat :26.2:test"
```

Expected: both `BUILD SUCCESSFUL`, same test count as the pre-refactor baseline.

---

## Task 10: Game runs, docs, and the final sweep

Compilation does not prove the gametest and clientTest sentinels still get discovered — they are found by `@GameTest` annotation scanning at runtime. This task closes that gap and finishes the documentation.

**Files:**
- Rewrite: `docs/architecture/module-map.md`
- Modify: any `docs/**` file (excluding `docs/superpowers/**`) still citing an old path
- Modify: `docs/architecture/INDEX.md`, `docs/ui/INDEX.md`, and any other `INDEX.md` whose summary text names a moved path

- [ ] **Step 1: Run the gametest and clientTest game runs**

These need an actual game launch, not a compile. Use the project's existing run configurations:

```bash
cmd.exe /c "cd /d H:\Repo\garnet && gradlew.bat :26.2:runGametest"
cmd.exe /c "cd /d H:\Repo\garnet && gradlew.bat :26.2:runClientTest"
```

If those task names do not exist, list them with `gradlew.bat :26.2:tasks --all | findstr /i test` and use the run tasks Loom registered. Expected: both complete with every spec passing. A sentinel that stopped being discovered shows up as a **drop in spec count**, not as a failure — compare against the counts in `docs/gametest/` or a pre-refactor run.

- [ ] **Step 2: Rewrite the module map**

`docs/architecture/module-map.md` is a package-by-package tour; nearly every heading changed. Rewrite it against the new tree, preserving its frontmatter (`title`, `tags`, `summary`) and its existing structure: top-level section per package, bullet per file, then "Dependency direction" and "Where to start reading".

Update the dependency-direction section to the spec's graph:

```
core/  →  playback/  →  testing/  →  editor/
                              dock/  →  editor/
```

and add the `(sub-feature, layer)` rule for `editor/` plus the recorded `EditorUndoOps` exception.

- [ ] **Step 3: Sweep for stale citations**

```bash
grep -rn 'com\.breadmoirai\.garnet\.\(spec\|structure\|history\|config\|ui\)\.' docs --exclude-dir=superpowers
grep -rn 'editor/ui/\|editor/data/\|editor/ops/\|editor/world/\|editor/command/\|ui/dock/\|ui/compose/\|ui/viewport/\|ui/input/\|ui/widget/' docs --exclude-dir=superpowers
grep -rn 'ProjectExplorerPanel\|ProjectTreeState' docs --exclude-dir=superpowers
```

Expected: all three print nothing. Fix anything that prints.

- [ ] **Step 4: Verify INDEX cross-references still resolve**

CLAUDE.md requires that every `INDEX.md` entry point at a real article with matching summary text. The articles did not move, but their summaries cite paths that did:

```bash
grep -rn 'garnet/\|editor/\|ui/' docs/*/INDEX.md
```

Update any summary naming an old path.

- [ ] **Step 5: Final full verification and commit**

```bash
cmd.exe /c "cd /d H:\Repo\garnet && gradlew.bat :26.2:clientClasses :26.2:classes :26.2:gametestClasses :26.2:clientTestClasses :26.2:testClasses"
cmd.exe /c "cd /d H:\Repo\garnet && gradlew.bat :26.2:test"
git status --short
find src -type d -empty
```

Expected: both builds successful, `git status` clean apart from the doc edits, and no empty directories under `src/`.

```bash
git add -A
git commit -m "docs: rewrite the module map for the feature sub-package layout"
```

---

## Definition of done

- [ ] All five compile targets green.
- [ ] `:26.2:test` green with the same test count as the pre-refactor baseline.
- [ ] The gametest and clientTest game runs complete with the same spec count as before.
- [ ] No file remains under `com.breadmoirai.garnet.{spec, structure, history, config, ui}` or `editor.{data, ops, world, command, ui}`.
- [ ] `docs/architecture/module-map.md` describes the tree that exists.
- [ ] The three `grep` sweeps in Task 10 Step 3 print nothing.
- [ ] `docs/superpowers/**` is untouched except for this plan file.
