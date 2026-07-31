# Feature-Based Package Layout Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Repackage `com.breadmoirai.garnet` by capability (`playback`, `testing`, `editor`) over shared base packages, delete the orphaned pre-dock in-world surface, and move Kotest out of the shipped jar.

**Architecture:** Seven sequential phases, one commit each, every phase gated on a full green build. Phase 1 is a deletion with real behavior change; phase 2 is build wiring; phases 3–6 are mechanical package moves in dependency order (base → playback → testing → editor); phase 7 is docs.

**Tech Stack:** Kotlin 2.x, Fabric for Minecraft 26.2, Stonecutter (single 26.2 slice), Gradle + Loom, Kotest (dev only), Compose Multiplatform + Jewel (client UI).

**Spec:** `docs/superpowers/specs/2026-07-31-feature-package-layout-design.md`

## Global Constraints

- **Gradle invocation:** always `cmd.exe /c "gradlew.bat <tasks>"` from the repo root. The `./` prefix fails — cmd.exe cannot parse it.
- **Task path is `:26.2:`**, not `:versions:26.2:`.
- **Full compile gate (all sourcesets):** `cmd.exe /c "gradlew.bat :26.2:clientClasses :26.2:classes :26.2:gametestClasses :26.2:clientTestClasses :26.2:testClasses"`. Compiling only `compileKotlin` is not sufficient.
- **Unit tests:** run `:26.2:test` **unfiltered**. Gradle's `--tests` filter does not work with Kotest and reports a false "No tests found". Read `build/test-results/test/*.xml` for results.
- **Gametests:** run `:26.2:runGameTest` and `:26.2:runClientTest` in the **foreground** with `timeout: 600000`. Background runs are lost. `clientTest` XML reports are always empty — read the log for the sentinel's `LauncherResult.summary()` line.
- **New Kotest specs must be registered** in `GametestSentinel.runAll`'s or `ClientTestSentinel`'s explicit `specs = listOf(...)`. Autoscan is disabled; an unregistered spec silently does not run. The same applies in reverse: deleting a spec requires removing its entry and its import.
- **Commits go directly to `main`.** No feature branches or worktrees.
- **No `Co-Authored-By` or "Generated with Claude Code" trailers** on any commit.
- **Package moves are mechanical.** In phases 3–6, change only the `package` line and `import` lines. Any logic edit in those phases is out of scope unless this plan names it explicitly.
- **Kotlin `internal` is per-sourceset, not per-package.** After the `client` segment is dropped, `src/client` files share package names with `src/main` files but still cannot see their `internal` members. Fix by promoting to `public`.

---

## Task 1: Delete the in-world entry surface

Removes the recorder/runner blocks, `SpecBlockEntity`, the marker tools, the entire `originPos` wire protocol, the bounds/HUD renderers, and their assets and tests.

**This is the only task with behavior change.** After it, the mod can open a redstone-project workspace and use the Explorer, but has no in-game way to record or run a spec.

**Files:**
- Delete: `src/main/kotlin/com/breadmoirai/garnet/block/` (3 files), `item/` (2 files), `network/Packets.kt`, `network/NetworkRegistry.kt`, `ModRegistries.kt`
- Delete: `src/client/kotlin/com/breadmoirai/garnet/client/network/ClientNetworkHandler.kt`, `client/render/GarnetBoundsRenderer.kt`, `client/render/HudOverlayRenderer.kt`, `client/SpecBoundsInteraction.kt`, `client/state/ClientRunnerState.kt`
- Delete: `src/gametest/.../test/recorder/` (2 files), `src/gametest/.../test/network/RecorderRunnerNetworkRegistrySpec.kt`
- Delete: `src/clientTest/.../test/ClientNetworkSpec.kt`, `ClientNetworkTestSupport.kt`
- Modify: `src/main/kotlin/com/breadmoirai/garnet/Garnet.kt`
- Modify: `src/main/kotlin/com/breadmoirai/garnet/project/ProjectDimLifecycle.kt:150-166`
- Modify: `src/client/kotlin/com/breadmoirai/garnet/client/GarnetClient.kt`
- Modify: `src/gametest/.../test/NetworkTestSupport.kt`, `GametestSentinel.kt`
- Modify: `src/clientTest/.../test/SpecTestContext.kt`, `ClientTestSentinel.kt`
- Delete: assets under `src/main/resources/assets/garnet/`
- Modify: `src/main/resources/assets/garnet/lang/en_us.json`

**Interfaces:**
- Consumes: nothing (first task).
- Produces: `Garnet.onInitialize` calls `ProjectNetworkRegistry.register()` directly. Everything downstream depends on `ProjectNetworkRegistry.register()` still being called exactly once at init.

### The one real risk

`NetworkRegistry.kt:227` — the last line of `registerNetworking()` — is:

```kotlin
com.breadmoirai.garnet.network.project.ProjectNetworkRegistry.register()
```

Deleting `NetworkRegistry.kt` without replacing that call silently unregisters **all editor networking**. The Explorer would go dead with no compile error. `ProjectNetworkRegistrySpec` is the regression gate; it must pass at the end of this task.

- [ ] **Step 1: Confirm the regression gate exists and passes before you change anything**

```bash
cd /mnt/h/Repo/RedstoneSpecs
grep -n "PayloadTypeRegistry\|shouldNotThrow\|registered" src/gametest/kotlin/com/breadmoirai/garnet/test/project/ProjectNetworkRegistrySpec.kt | head -20
```

Expected: the spec asserts project payload types are registered. If it does not, stop and add that assertion first — it is the only thing standing between this task and a silently dead Explorer.

- [ ] **Step 2: Delete the test specs that drive the doomed code**

```bash
cd /mnt/h/Repo/RedstoneSpecs
git rm -r src/gametest/kotlin/com/breadmoirai/garnet/test/recorder/
git rm src/gametest/kotlin/com/breadmoirai/garnet/test/network/RecorderRunnerNetworkRegistrySpec.kt
git rm src/clientTest/kotlin/com/breadmoirai/garnet/test/ClientNetworkSpec.kt
git rm src/clientTest/kotlin/com/breadmoirai/garnet/test/ClientNetworkTestSupport.kt
```

`src/gametest/.../test/network/` is now empty — remove the directory if git left it.

- [ ] **Step 3: Deregister the deleted specs from both sentinels**

In `src/gametest/kotlin/com/breadmoirai/garnet/test/GametestSentinel.kt`, remove these three lines from the `specs = listOf(...)` block:

```kotlin
                            RecorderRunnerNetworkRegistrySpec::class,
                            RecordingLifecycleSpec::class,
                            MarkerToolSpec::class,
```

and these three imports:

```kotlin
import com.breadmoirai.garnet.test.network.RecorderRunnerNetworkRegistrySpec
import com.breadmoirai.garnet.test.recorder.MarkerToolSpec
import com.breadmoirai.garnet.test.recorder.RecordingLifecycleSpec
```

In `src/clientTest/kotlin/com/breadmoirai/garnet/test/ClientTestSentinel.kt`, remove this line from its `specs = listOf(...)` block:

```kotlin
                        ClientNetworkSpec::class,
```

- [ ] **Step 4: Delete the client-side code**

```bash
cd /mnt/h/Repo/RedstoneSpecs
git rm src/client/kotlin/com/breadmoirai/garnet/client/network/ClientNetworkHandler.kt
git rm src/client/kotlin/com/breadmoirai/garnet/client/render/GarnetBoundsRenderer.kt
git rm src/client/kotlin/com/breadmoirai/garnet/client/render/HudOverlayRenderer.kt
git rm src/client/kotlin/com/breadmoirai/garnet/client/SpecBoundsInteraction.kt
git rm src/client/kotlin/com/breadmoirai/garnet/client/state/ClientRunnerState.kt
```

- [ ] **Step 5: Delete the server-side code**

```bash
cd /mnt/h/Repo/RedstoneSpecs
git rm -r src/main/kotlin/com/breadmoirai/garnet/block/
git rm -r src/main/kotlin/com/breadmoirai/garnet/item/
git rm src/main/kotlin/com/breadmoirai/garnet/network/Packets.kt
git rm src/main/kotlin/com/breadmoirai/garnet/network/NetworkRegistry.kt
git rm src/main/kotlin/com/breadmoirai/garnet/ModRegistries.kt
```

`ModRegistries.kt` goes entirely — every one of its members is a block, block-item, BE type, or marker item.

- [ ] **Step 6: Rewrite `GarnetClient.onInitializeClient`**

Remove the three deleted registrations and their imports. The result:

```kotlin
package com.breadmoirai.garnet.client

import com.breadmoirai.garnet.client.config.ModConfig
import com.breadmoirai.garnet.client.project.ProjectClientNetworking
import com.breadmoirai.garnet.client.viewport.registerCursorFocusToggle
import com.breadmoirai.garnet.client.viewport.registerDockKeybinds
import com.breadmoirai.garnet.client.viewport.registerDockWorldLifecycle
import com.breadmoirai.garnet.client.viewport.registerViewportToggle
import net.fabricmc.api.ClientModInitializer
import org.slf4j.LoggerFactory

private val LOGGER = LoggerFactory.getLogger("Garnet")

class GarnetClient : ClientModInitializer {

    override fun onInitializeClient() {
        LOGGER.debug("[GarnetClient#onInitializeClient] initializing client")
        ModConfig.load()
        ProjectClientNetworking.register()
        registerViewportToggle()
        registerCursorFocusToggle()
        registerDockKeybinds()
        registerDockWorldLifecycle()
        // Seed the Project Explorer into the LEFT dock (region stays hidden until Shift+1 reveals it).
        com.breadmoirai.garnet.client.ui.compose.dock.DockState.leftPanels
            .add(com.breadmoirai.garnet.client.ide.explorerPanel())
        LOGGER.debug("[GarnetClient#onInitializeClient] client initialization complete")
    }
}
```

Removed: `registerBoundsRenderer()`, `registerClientNetworking()`, `registerHudOverlay()` and their three imports.

- [ ] **Step 7: Rewrite `Garnet.onInitialize`**

Delete the `registerAttackCallback()` and `registerUseBlockCallback()` private functions entirely, remove their call sites, remove `ModRegistries.register()`, and **replace `registerNetworking()` with a direct call to the project registry**. Remove the now-unused imports (`SpecBlockEntity`, `SpecMarkerTool`, `UndoStack`, `registerNetworking`, `AttackBlockCallback`, `UseBlockCallback`, `InteractionResult`, `UseOnContext`).

The resulting `onInitialize` head:

```kotlin
    override fun onInitialize() {
        LOGGER.debug("[Garnet#onInitialize] initializing mod")
        ProjectNetworkRegistry.register()
        GarnetTestLifecycle.register()
        SubTickPhaseEvents.PHASE.register { level, phase ->
            com.breadmoirai.garnet.runner.StateRecorder.onPhaseForActiveRecorders(level, phase)
        }
```

with `import com.breadmoirai.garnet.network.project.ProjectNetworkRegistry` added. Everything from `ServerLifecycleEvents.SERVER_STARTING` down is unchanged — including the `BEFORE_SAVE` handler, which can now use the short name.

The `SubTickPhaseEvents.PHASE` handler **stays**. `StateRecorder` dispatches through its own static `activeRecorders()` registry, not through block entities, so the recording pipeline survives this task intact.

- [ ] **Step 8: Drop the anchor-block placement from `ProjectDimLifecycle.placeCell`**

Replace lines 150–161 (the anchor block + BE binding) so the function goes straight from the structure placement to the snapshot:

```kotlin
        // Anchor blocks were removed with the pre-dock in-world surface; a cell is now
        // just its structure. Binding a source path to a block entity went with them.
```

Delete the imports `com.breadmoirai.garnet.ModRegistries` and `com.breadmoirai.garnet.block.SpecBlockEntity` from the file.

Note for the reviewer: `projectSourcePath` was write-only in surviving code — its only reader was the deleted `RecordingLifecycleSpec`. Save-back goes through `ProjectCellSaver` and the cell registry, not through the BE, so this does not affect saving.

- [ ] **Step 9: Trim the test support files**

In `src/gametest/kotlin/com/breadmoirai/garnet/test/NetworkTestSupport.kt`, delete the functions `placeRecorderBE` and `placeRunnerBE` (used only by the three deleted specs) and the imports `com.breadmoirai.garnet.ModRegistries` and `com.breadmoirai.garnet.block.SpecBlockEntity`. Keep `makeMockServerPlayer`, `deleteRecursively`, and `drainPayloads` — the surviving `Project*` specs use them.

In `src/clientTest/kotlin/com/breadmoirai/garnet/test/SpecTestContext.kt`, delete the `getClientBe` function (lines ~150–152, no callers) and the `com.breadmoirai.garnet.block.SpecBlockEntity` import.

- [ ] **Step 10: Compile — expect this to be the moment things surface**

```bash
cd /mnt/h/Repo/RedstoneSpecs && cmd.exe /c "gradlew.bat :26.2:clientClasses :26.2:classes :26.2:gametestClasses :26.2:clientTestClasses :26.2:testClasses"
```

Expected: BUILD SUCCESSFUL. If anything still references a deleted symbol, the compiler names it — fix by deletion, not by reintroducing the symbol. Do not proceed until this is green.

- [ ] **Step 11: Delete the assets for the removed blocks and items**

```bash
cd /mnt/h/Repo/RedstoneSpecs/src/main/resources/assets/garnet
git rm blockstates/garnet_recorder.json blockstates/garnet_runner.json
git rm models/block/garnet_recorder.json models/block/garnet_runner.json
git rm models/item/garnet_recorder.json models/item/garnet_runner.json
git rm models/item/input_spec_marker.json models/item/output_spec_marker.json
git rm items/garnet_recorder.json items/garnet_runner.json
git rm items/input_spec_marker.json items/output_spec_marker.json
git rm textures/block/garnet_recorder.png
git rm textures/item/input_spec_marker.png textures/item/output_spec_marker.png
```

- [ ] **Step 12: Delete the assets that were already orphaned**

The audit found these have no code behind them and predate this task: a `garnet_editor` block with no class, and two markers registered nowhere.

```bash
cd /mnt/h/Repo/RedstoneSpecs/src/main/resources/assets/garnet
git rm blockstates/garnet_editor.json models/block/garnet_editor.json
git rm models/item/garnet_editor.json items/garnet_editor.json
git rm textures/block/garnet_editor.png
git rm models/item/auto_spec_marker.json models/item/breakpoint_spec_marker.json
git rm items/auto_spec_marker.json items/breakpoint_spec_marker.json
git rm textures/item/auto_spec_marker.png textures/item/breakpoint_spec_marker.png
```

Leave `textures/block/garnet.png` — verify with `grep -rn "garnet.png" src/main/resources/` before deleting anything else in that directory.

- [ ] **Step 13: Strip the dead lang keys**

In `src/main/resources/assets/garnet/lang/en_us.json`, delete every `block.garnet.*` and `item.garnet.*` key (all ten name blocks and markers that no longer exist) and every `screen.garnet.*` key (the screens were cut before this work started). Keep any `key.garnet.*` keybind entries — `DockKeybinds` still registers `key.garnet.dock_explorer_focus`.

Verify the file is still valid JSON:

```bash
python3 -m json.tool src/main/resources/assets/garnet/lang/en_us.json > /dev/null && echo "valid"
```

- [ ] **Step 14: Run the full test suite**

```bash
cd /mnt/h/Repo/RedstoneSpecs && cmd.exe /c "gradlew.bat :26.2:test"
```

Then read `build/test-results/test/*.xml` — do not trust the console summary alone.

- [ ] **Step 15: Run the gametests (foreground, 600 s timeout)**

```bash
cd /mnt/h/Repo/RedstoneSpecs && cmd.exe /c "gradlew.bat :26.2:runGameTest"
```

Expected: `ProjectNetworkRegistrySpec` passes. **This is the gate for step 7** — if project payloads are no longer registered, this is where it shows.

- [ ] **Step 16: Run the client tests (foreground, 600 s timeout)**

```bash
cd /mnt/h/Repo/RedstoneSpecs && cmd.exe /c "gradlew.bat :26.2:runClientTest"
```

Read the log for the sentinel's summary line; the XML report is always empty.

- [ ] **Step 17: Commit**

```bash
cd /mnt/h/Repo/RedstoneSpecs
git add -A
git commit -m "refactor: delete the pre-dock in-world entry surface

The recorder and runner blocks, SpecBlockEntity, the marker tools, and the
whole originPos wire protocol drove screens that were cut for the Compose
dock. They have had no user-reachable flow since. Removes them along with
the bounds/HUD renderers, block and item registrations, assets, and the
four specs that covered them.

Garnet.onInitialize now calls ProjectNetworkRegistry.register() directly;
it was previously reached through the deleted registerNetworking().

The record/run engine is untouched. StateRecorder dispatches through its
static activeRecorders() registry rather than block entities, so the phase
handler and ServerLevelSetBlockMixin still work. What is gone is the only
in-game way to reach it, until the dock panels land."
```

---

## Task 2: Independent dead code

Three items with zero callers that are unrelated to the in-world surface. Separate from Task 1 so a reviewer can reject one without the other.

**Files:**
- Modify: `src/main/kotlin/com/breadmoirai/garnet/persistence/KtsSpecLoader.kt`, `SpecScript.kt`
- Delete: `src/main/kotlin/com/breadmoirai/garnet/testing/server/Structures.kt`

**Interfaces:**
- Consumes: Task 1's tree.
- Produces: `main` no longer imports `io.kotest` anywhere. Task 4 depends on this — it is what lets Kotest leave the shipped sourcesets.

- [ ] **Step 1: Prove the legacy loader path is unreferenced**

```bash
cd /mnt/h/Repo/RedstoneSpecs
grep -rn "loadSpec\|loadFile\b\|findFirstSpecClass" src/ --include='*.kt' | grep -v "persistence/KtsSpecLoader.kt"
```

Expected: no output. If there is output, stop — the premise is wrong and this task needs rethinking.

- [ ] **Step 2: Delete the legacy Kotest script path from `KtsSpecLoader`**

Remove the functions `loadSpec`, `loadFile`, and `findFirstSpecClass`, plus the imports `io.kotest.core.spec.Spec` and `kotlin.reflect.KClass`. Keep `loadGarnetSpec`, `loadFileAsGarnetSpec`, and the private `evalOrThrow`.

Also delete the now-stale `import kotlin.script.experimental.api.ResultValue` only if `loadGarnetSpec` no longer needs it — it does need it, so keep it.

- [ ] **Step 3: Trim `SpecScript.defaultImports`**

Remove these entries and the comment above them — they pre-import a script form nothing emits:

```kotlin
        // Testing surface: lets .spec.kts name GarnetTestSpec, runGarnetSpec,
        // and kotest matchers without explicit imports.
        "com.breadmoirai.garnet.testing.GarnetTestSpec",
        "com.breadmoirai.garnet.testing.runner.runGarnetSpec",
        "com.breadmoirai.garnet.testing.server.awaitTicks",
        "com.breadmoirai.garnet.testing.server.awaitTickEnd",
        "com.breadmoirai.garnet.testing.server.spawnStructure",
```

Keep `"com.breadmoirai.garnet.dsl.*"` — that is the live one.

- [ ] **Step 4: Delete `Structures.kt`**

```bash
cd /mnt/h/Repo/RedstoneSpecs
grep -rn "StructureGrid\|StructureHandle\|acquireSlot\|releaseSlot\|spawnStructure\|signalAt" src/ --include='*.kt' | grep -v "testing/server/Structures.kt"
```

Expected: no output. Then:

```bash
git rm src/main/kotlin/com/breadmoirai/garnet/testing/server/Structures.kt
```

- [ ] **Step 5: Verify `main` is Kotest-free**

```bash
cd /mnt/h/Repo/RedstoneSpecs
grep -rn "io.kotest" src/main/kotlin/ | grep -v "/testing/"
```

Expected: no output. `src/main/kotlin/com/breadmoirai/garnet/testing/` still has Kotest — Task 4 moves it out.

- [ ] **Step 6: Compile and test**

```bash
cd /mnt/h/Repo/RedstoneSpecs && cmd.exe /c "gradlew.bat :26.2:clientClasses :26.2:classes :26.2:gametestClasses :26.2:clientTestClasses :26.2:testClasses"
cmd.exe /c "gradlew.bat :26.2:test"
```

Expected: BUILD SUCCESSFUL, tests green.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "refactor: drop the legacy kts Spec-class loader and unused structure helpers

KtsSpecLoader.loadSpec/loadFile walked a script instance's nested classes
looking for a GarnetTestSpec subclass. Nothing has emitted that form since
RecordingDslEmitter switched to garnetSpec(...) values, and nothing called
them. Removing them takes the last io.kotest import out of non-testing main
code. SpecScript's matching defaultImports and the unreferenced
testing/server/Structures.kt go with them."
```

---

## Task 3: `testSupport` sourceset

Creates the sixth sourceset and moves the Kotest-coupled classes into it, so Kotest ships in no jar the player loads.

**This is the riskiest structural task.** If the Loom/Compose/remap wiring resists, the documented fallback is to leave the harness in `main` under a `garnet.harness` package and accept that Kotest ships — the package-layout benefit survives, only jar purity is lost. Do not spend more than one session fighting it.

**Files:**
- Modify: `build.gradle.kts` (sourceset creation ~line 29, dependency wiring ~line 76, classpath wiring ~line 97, Compose plugin filter ~line 229)
- Create: `src/testSupport/kotlin/com/breadmoirai/garnet/harness/` (11 files moved)
- Delete: `src/main/kotlin/com/breadmoirai/garnet/testing/{ClientSpec,GarnetTestSpec,GarnetTestSpecContext}.kt`, `testing/launcher/`, `testing/runner/`, `testing/core/{ClientContextHolder,FabricTestThreadPump,WorldHolder}.kt`
- Modify: every file in `src/gametest`, `src/clientTest`, `src/test` that imports them

**Interfaces:**
- Consumes: Task 2's Kotest-free `main` (outside `testing/`).
- Produces: package `com.breadmoirai.garnet.harness` containing `GarnetTestSpec`, `GarnetTestSpecContext`, `ClientSpec`, `RecordingHolder`, `runGarnetSpec(spec, originPos, level)`, `harness.launcher.launchKotest(sourceSet, reportsDir, specs)`, `harness.launcher.LauncherResult`, `harness.client.FabricTestThreadPump`, `harness.client.WorldHolder`, `harness.client.ClientContextHolder`.

**What stays in `main`:** `testing/core/Dispatchers.kt`, `Ticks.kt`, `Lifecycle.kt`, and `testing/server/Suspending.kt`. None reference Kotest, and `Garnet.onInitialize` calls `GarnetTestLifecycle.register()`. Task 5 renames them.

- [ ] **Step 1: Create the sourceset and wire its classpaths**

In `build.gradle.kts`, next to the existing `val clientTestSourceSet = sourceSets.create("clientTest")` (~line 29):

```kotlin
val testSupportSourceSet = sourceSets.create("testSupport")
```

In the `afterEvaluate` block that wires classpaths (~line 97), add:

```kotlin
    testSupportSourceSet.apply {
        compileClasspath += sourceSets["main"].output +
            sourceSets["client"].output +
            sourceSets["client"].compileClasspath
        runtimeClasspath += sourceSets["main"].output +
            sourceSets["client"].output +
            sourceSets["client"].runtimeClasspath
    }
    sourceSets.named("gametest") {
        compileClasspath += testSupportSourceSet.output
        runtimeClasspath += testSupportSourceSet.output
    }
    clientTestSourceSet.apply {
        compileClasspath += testSupportSourceSet.output
        runtimeClasspath += testSupportSourceSet.output
    }
    sourceSets.named("test") {
        compileClasspath += testSupportSourceSet.output
        runtimeClasspath += testSupportSourceSet.output
    }
```

Order matters: `testSupportSourceSet.apply` must run before the three that consume its output.

- [ ] **Step 2: Move the Kotest dependency off `main` and onto `testSupport`**

This is the step that actually stops Kotest shipping. `build.gradle.kts:165-166` currently declares it as a plain `implementation`, which puts it on `main`'s compile and runtime classpath:

```kotlin
    implementation("io.kotest:kotest-runner-junit5:5.9.1")
    implementation("io.kotest:kotest-assertions-core:5.9.1")
```

Replace those two lines with per-sourceset declarations. `testSupport` needs it to compile the harness; the three test sourcesets need it to write specs; `main` and `client` must not have it at all:

```kotlin
    "testSupportImplementation"("io.kotest:kotest-runner-junit5:5.9.1")
    "testSupportImplementation"("io.kotest:kotest-assertions-core:5.9.1")
    testImplementation("io.kotest:kotest-runner-junit5:5.9.1")
    testImplementation("io.kotest:kotest-assertions-core:5.9.1")
    "gametestImplementation"("io.kotest:kotest-runner-junit5:5.9.1")
    "gametestImplementation"("io.kotest:kotest-assertions-core:5.9.1")
    "clientTestImplementation"("io.kotest:kotest-runner-junit5:5.9.1")
    "clientTestImplementation"("io.kotest:kotest-assertions-core:5.9.1")
```

Moving these will break `main`'s compile if Task 2 was skipped — `KtsSpecLoader` imported `io.kotest.core.spec.Spec`. That dependency ordering is deliberate.

In the `configurations` block alongside the existing `gametestImplementation` / `clientTestImplementation` wiring (~line 76), add `testSupportImplementation`, `testSupportCompileOnly`, and `testSupportRuntimeOnly`, mirroring how `clientTest` is given client APIs. `testSupport` also needs the Fabric client-gametest API for `FabricTestThreadPump` and `ClientContextHolder`:

```kotlin
    "testSupportImplementation"(fabricApi.module("fabric-client-gametest-api-v1", project.property("fabric_version") as String))
```

- [ ] **Step 3: Put `testSupport` on the game's runtime classpath for both run configs**

In the `loom { runs { ... } }` block, the `gametest` and `clientTest` runs each list their sourcesets. Add `sourceSet(testSupportSourceSet)` to both, next to their existing `sourceSet(...)` calls. Without this the classes compile but are absent at runtime, and the sentinels fail with `NoClassDefFoundError`.

- [ ] **Step 4: Exclude `testSupport` from the Compose compiler plugin**

Near line 229 there is a filter stripping the Compose subplugin from compilations without `@Composable` code (`main`, `test`, `gametest`). Add `testSupport` to that list — it contains no Compose code.

- [ ] **Step 5: Confirm `testSupport` is not a jar input**

```bash
cd /mnt/h/Repo/RedstoneSpecs
grep -n "remapJar\|from(sourceSets" build.gradle.kts
```

The `remapJar`/`jar` inputs should name only `main` and `client`. If `testSupport` was picked up by a wildcard, exclude it explicitly.

- [ ] **Step 6: Verify the empty sourceset builds before moving any code**

```bash
cd /mnt/h/Repo/RedstoneSpecs
mkdir -p src/testSupport/kotlin/com/breadmoirai/garnet/harness
cmd.exe /c "gradlew.bat :26.2:testSupportClasses"
```

Expected: BUILD SUCCESSFUL. Getting the wiring green while the sourceset is empty separates build problems from move problems.

- [ ] **Step 7: Move the eleven files**

```bash
cd /mnt/h/Repo/RedstoneSpecs
S=src/main/kotlin/com/breadmoirai/garnet/testing
D=src/testSupport/kotlin/com/breadmoirai/garnet/harness
mkdir -p $D/launcher $D/client
git mv $S/GarnetTestSpec.kt $S/GarnetTestSpecContext.kt $S/ClientSpec.kt $D/
git mv $S/runner/RecordingHolder.kt $S/runner/RunGarnetSpec.kt $D/
git mv $S/launcher/KotestLauncher.kt $S/launcher/ResultCollector.kt $S/launcher/DiagnosticRecorderListener.kt $D/launcher/
git mv $S/core/FabricTestThreadPump.kt $S/core/WorldHolder.kt $S/core/ClientContextHolder.kt $D/client/
rmdir $S/runner $S/launcher 2>/dev/null || true
```

- [ ] **Step 8: Rewrite the package declarations in the moved files**

```bash
cd /mnt/h/Repo/RedstoneSpecs/src/testSupport/kotlin/com/breadmoirai/garnet/harness
sed -i 's/^package com\.breadmoirai\.garnet\.testing\.launcher$/package com.breadmoirai.garnet.harness.launcher/' launcher/*.kt
sed -i 's/^package com\.breadmoirai\.garnet\.testing\.core$/package com.breadmoirai.garnet.harness.client/' client/*.kt
sed -i 's/^package com\.breadmoirai\.garnet\.testing\.runner$/package com.breadmoirai.garnet.harness/' *.kt
sed -i 's/^package com\.breadmoirai\.garnet\.testing$/package com.breadmoirai.garnet.harness/' *.kt
```

- [ ] **Step 9: Rewrite every import of the moved symbols, repo-wide**

```bash
cd /mnt/h/Repo/RedstoneSpecs
FILES=$(grep -rl "com\.breadmoirai\.garnet\.testing\." src --include='*.kt')
for f in $FILES; do
  sed -i \
    -e 's/com\.breadmoirai\.garnet\.testing\.launcher\./com.breadmoirai.garnet.harness.launcher./g' \
    -e 's/com\.breadmoirai\.garnet\.testing\.runner\./com.breadmoirai.garnet.harness./g' \
    -e 's/com\.breadmoirai\.garnet\.testing\.core\.FabricTestThreadPump/com.breadmoirai.garnet.harness.client.FabricTestThreadPump/g' \
    -e 's/com\.breadmoirai\.garnet\.testing\.core\.WorldHolder/com.breadmoirai.garnet.harness.client.WorldHolder/g' \
    -e 's/com\.breadmoirai\.garnet\.testing\.core\.ClientContextHolder/com.breadmoirai.garnet.harness.client.ClientContextHolder/g' \
    -e 's/com\.breadmoirai\.garnet\.testing\.GarnetTestSpecContext/com.breadmoirai.garnet.harness.GarnetTestSpecContext/g' \
    -e 's/com\.breadmoirai\.garnet\.testing\.GarnetTestSpec/com.breadmoirai.garnet.harness.GarnetTestSpec/g' \
    -e 's/com\.breadmoirai\.garnet\.testing\.ClientSpec/com.breadmoirai.garnet.harness.ClientSpec/g' \
    "$f"
done
```

The `GarnetTestSpecContext` rule must come before `GarnetTestSpec` — the shorter name is a prefix of the longer one and would corrupt it.

Left deliberately untouched: `com.breadmoirai.garnet.testing.core.McDispatchers`, `...core.GarnetTestLifecycle`, `...core.serverTick*`, and `...server.*`. Those stay in `main` and Task 5 renames them.

- [ ] **Step 10: Move the two unit tests that cover harness classes**

```bash
cd /mnt/h/Repo/RedstoneSpecs
mkdir -p src/test/kotlin/com/breadmoirai/garnet/harness/launcher
git mv src/test/kotlin/com/breadmoirai/garnet/testing/launcher/DiagnosticRecorderListenerTest.kt \
       src/test/kotlin/com/breadmoirai/garnet/harness/launcher/
git mv src/test/kotlin/com/breadmoirai/garnet/testing/runner/RecordingHolderTest.kt \
       src/test/kotlin/com/breadmoirai/garnet/harness/
```

Update their `package` lines to `com.breadmoirai.garnet.harness.launcher` and `com.breadmoirai.garnet.harness`.

`src/test/.../testing/server/SuspendingTest.kt` stays where it is — `Suspending.kt` is not moving in this task.

- [ ] **Step 11: Compile everything**

```bash
cd /mnt/h/Repo/RedstoneSpecs && cmd.exe /c "gradlew.bat :26.2:clientClasses :26.2:classes :26.2:testSupportClasses :26.2:gametestClasses :26.2:clientTestClasses :26.2:testClasses"
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 12: Confirm Kotest is out of the shipped sourcesets**

```bash
cd /mnt/h/Repo/RedstoneSpecs
grep -rn "io.kotest" src/main/ src/client/
```

Expected: no output. This is the point of the task.

- [ ] **Step 13: Run all three test suites**

```bash
cd /mnt/h/Repo/RedstoneSpecs
cmd.exe /c "gradlew.bat :26.2:test"
cmd.exe /c "gradlew.bat :26.2:runGameTest"
cmd.exe /c "gradlew.bat :26.2:runClientTest"
```

`runGameTest` and `runClientTest` foreground, 600 s timeout. A `NoClassDefFoundError` for a harness class means step 3 did not take.

- [ ] **Step 14: Commit**

```bash
git add -A
git commit -m "build: move the Kotest harness into a testSupport sourceset

GarnetTestSpec, ClientSpec, the Kotest launcher, and the client-test thread
plumbing were in src/main, so Kotest shipped in the mod jar. They move to a
new testSupport sourceset under com.breadmoirai.garnet.harness, wired onto
the compile and runtime classpaths of gametest, clientTest, and test, and
onto both Loom run configs.

It needs its own sourceset rather than folding into one of the test source
sets because gametest and clientTest are siblings and both use it.

The MC coroutine and tick plumbing in testing/core and testing/server stays
in main — Garnet.onInitialize registers it, and none of it touches Kotest."
```

---

## Task 4: `garnet.mc`

Renames the misnamed `testing/core` + `testing/server` + `event/` into a package that says what they are: Minecraft coroutine and tick plumbing, registered by the product.

**Files:**
- Move: `testing/core/{Dispatchers,Ticks,Lifecycle}.kt`, `testing/server/Suspending.kt`, `event/SubTickPhaseEvents.kt` → `mc/`
- Modify: every importer across all six sourcesets
- Move: `src/test/.../testing/server/SuspendingTest.kt` → `src/test/.../mc/SuspendingTest.kt`

**Interfaces:**
- Consumes: Task 3's tree.
- Produces: `com.breadmoirai.garnet.mc` containing `McDispatchers`, `ServerThreadDispatcher`, `serverTickStart`, `serverTickEnd`, `McLifecycle` (renamed from `GarnetTestLifecycle`, same `register()` and `registerWithServer(server)` methods), `awaitTickEnd()`, `awaitTicks(n)`, `awaitTickWhere(predicate)`, `onServer(block)`, `SubTickPhaseEvents`.

- [ ] **Step 1: Move the files**

```bash
cd /mnt/h/Repo/RedstoneSpecs
D=src/main/kotlin/com/breadmoirai/garnet/mc
mkdir -p $D
S=src/main/kotlin/com/breadmoirai/garnet/testing
git mv $S/core/Dispatchers.kt $S/core/Ticks.kt $D/
git mv $S/core/Lifecycle.kt $D/McLifecycle.kt
git mv $S/server/Suspending.kt $D/
git mv src/main/kotlin/com/breadmoirai/garnet/event/SubTickPhaseEvents.kt $D/
rmdir $S/core $S/server $S src/main/kotlin/com/breadmoirai/garnet/event 2>/dev/null || true
```

`src/main/.../testing/` should now be empty and gone. If `rmdir` refuses, list what is left and account for it before continuing.

- [ ] **Step 2: Rewrite the package lines and the class rename**

```bash
cd /mnt/h/Repo/RedstoneSpecs/src/main/kotlin/com/breadmoirai/garnet/mc
sed -i 's/^package com\.breadmoirai\.garnet\.\(testing\.core\|testing\.server\|event\)$/package com.breadmoirai.garnet.mc/' *.kt
sed -i 's/\bGarnetTestLifecycle\b/McLifecycle/g' *.kt
```

- [ ] **Step 3: Rewrite imports repo-wide**

```bash
cd /mnt/h/Repo/RedstoneSpecs
FILES=$(grep -rl "garnet\.testing\.\(core\|server\)\.\|garnet\.event\.\|GarnetTestLifecycle" src --include='*.kt')
for f in $FILES; do
  sed -i \
    -e 's/com\.breadmoirai\.garnet\.testing\.core\./com.breadmoirai.garnet.mc./g' \
    -e 's/com\.breadmoirai\.garnet\.testing\.server\./com.breadmoirai.garnet.mc./g' \
    -e 's/com\.breadmoirai\.garnet\.event\./com.breadmoirai.garnet.mc./g' \
    -e 's/\bGarnetTestLifecycle\b/McLifecycle/g' \
    "$f"
done
```

- [ ] **Step 4: Catch the fully-qualified reference in `Garnet.kt`**

`Garnet.kt` refers to `com.breadmoirai.garnet.runner.StateRecorder` inline rather than by import — that one is untouched here. But confirm no stragglers:

```bash
cd /mnt/h/Repo/RedstoneSpecs
grep -rn "garnet\.testing\|garnet\.event\|GarnetTestLifecycle" src --include='*.kt'
```

Expected: no output.

- [ ] **Step 5: Move the unit test**

```bash
cd /mnt/h/Repo/RedstoneSpecs
mkdir -p src/test/kotlin/com/breadmoirai/garnet/mc
git mv src/test/kotlin/com/breadmoirai/garnet/testing/server/SuspendingTest.kt src/test/kotlin/com/breadmoirai/garnet/mc/
sed -i 's/^package com\.breadmoirai\.garnet\.testing\.server$/package com.breadmoirai.garnet.mc/' src/test/kotlin/com/breadmoirai/garnet/mc/SuspendingTest.kt
rmdir src/test/kotlin/com/breadmoirai/garnet/testing/server src/test/kotlin/com/breadmoirai/garnet/testing 2>/dev/null || true
```

- [ ] **Step 6: Compile, test, commit**

```bash
cd /mnt/h/Repo/RedstoneSpecs
cmd.exe /c "gradlew.bat :26.2:clientClasses :26.2:classes :26.2:testSupportClasses :26.2:gametestClasses :26.2:clientTestClasses :26.2:testClasses"
cmd.exe /c "gradlew.bat :26.2:test"
cmd.exe /c "gradlew.bat :26.2:runGameTest"
cmd.exe /c "gradlew.bat :26.2:runClientTest"
git add -A
git commit -m "refactor: rename testing/core and testing/server to garnet.mc

None of it is test-only. Garnet.onInitialize calls GarnetTestLifecycle
.register() to install the server dispatcher and the tick-event pumps that
runGarnetSpec's awaitTickEnd depends on. The name has been telling readers
this is dev-only infrastructure that could be stripped.

GarnetTestLifecycle becomes McLifecycle. SubTickPhaseEvents joins it, since
the phase pump belongs with the tick pump."
```

---

## Task 5: Base packages and the two seam fixes

Creates `spec/`, `structure/`, `ui/`, `config/` and fixes the two layering violations that survive the trim.

**Files:**
- Move: `dsl/` → `spec/`; `persistence/Structure*.kt` + `project/StructureRegionMath.kt` → `structure/`; `client/ui/compose/**`, `client/viewport/`, `client/screen/` → `ui/`; `client/config/` → `config/`
- Modify: `runner/StateRecordingView.kt` (implement the interface), `dsl/ConditionEvaluator.kt` (widen the parameter)
- Modify: `project/ProjectDimRegistry.kt` (extract `PlacedBox`)
- Create: `structure/PlacedBox.kt`
- Modify: `runner/RecordingDslEmitter.kt` (three emitted import lines), `persistence/SpecScript.kt` (defaultImports)

**Interfaces:**
- Consumes: Task 4's `garnet.mc`.
- Produces: `com.breadmoirai.garnet.spec` (the whole DSL — `GarnetSpec`, `garnetSpec()`, `SpecRun`, `InputScope`, `OutputScope`, `ConditionScope`, `StateCondition`, `SimTime`, `Phase`, `StateRecordingViewLike`); `com.breadmoirai.garnet.structure` (`StructurePersistence`, `StructureDiff`, `PlacedBox(origin: BlockPos, size: Vec3i)`, `autoFit`, `centeredStart`, `anchorY`); `com.breadmoirai.garnet.ui.{compose,dock,input,viewport,widget}`; `com.breadmoirai.garnet.config`.

### Seam fix A: `StateRecordingView` implements `StateRecordingViewLike`

`spec.ConditionEvaluator` currently takes a concrete `StateRecordingView` from what is about to become `playback`, which would make the leaf package depend on a feature. `SpecRun.kt:99` already declares the interface for exactly this purpose.

- [ ] **Step 1: Read the interface and the class**

```bash
cd /mnt/h/Repo/RedstoneSpecs
sed -n '95,115p' src/main/kotlin/com/breadmoirai/garnet/dsl/SpecRun.kt
sed -n '1,40p' src/main/kotlin/com/breadmoirai/garnet/runner/StateRecordingView.kt
```

Confirm `StateRecordingView` already has methods matching every `StateRecordingViewLike` member. If a signature differs, adapt `StateRecordingView` to the interface — do not change the interface, `SpecRun` depends on its shape.

- [ ] **Step 2: Declare the implementation**

In `src/main/kotlin/com/breadmoirai/garnet/runner/StateRecordingView.kt`, change the class header to implement the interface and mark the matching members `override`:

```kotlin
class StateRecordingView(
    // ...existing constructor params unchanged...
) : com.breadmoirai.garnet.dsl.StateRecordingViewLike {
```

- [ ] **Step 3: Widen `ConditionEvaluator`**

In `src/main/kotlin/com/breadmoirai/garnet/dsl/ConditionEvaluator.kt`, delete `import com.breadmoirai.garnet.runner.StateRecordingView` and change the parameter at line 19 from `view: StateRecordingView` to `view: StateRecordingViewLike`.

- [ ] **Step 4: Verify the leaf is clean**

```bash
cd /mnt/h/Repo/RedstoneSpecs
grep -rn "^import com\.breadmoirai\.garnet" src/main/kotlin/com/breadmoirai/garnet/dsl/
```

Expected: no output. `dsl/` must import nothing from the project.

- [ ] **Step 5: Compile and run the unit tests**

```bash
cd /mnt/h/Repo/RedstoneSpecs
cmd.exe /c "gradlew.bat :26.2:classes"
cmd.exe /c "gradlew.bat :26.2:test"
```

Expected: green. `StateConditionTest` and `StateRecordingViewTest` cover this path.

### Seam fix B: `PlacedBox` and the region math move to `structure`

`StructurePersistence` imports `project.PlacedBox`, `autoFit`, `anchorY`, and `centeredStart` — geometry, not editor concerns.

- [ ] **Step 6: Extract `PlacedBox`**

Delete this line from `src/main/kotlin/com/breadmoirai/garnet/project/ProjectDimRegistry.kt:15`:

```kotlin
data class PlacedBox(val origin: BlockPos, val size: Vec3i)
```

Create `src/main/kotlin/com/breadmoirai/garnet/structure/PlacedBox.kt`:

```kotlin
package com.breadmoirai.garnet.structure

import net.minecraft.core.BlockPos
import net.minecraft.core.Vec3i

/** Absolute origin plus size of a structure placed into the world. */
data class PlacedBox(val origin: BlockPos, val size: Vec3i)
```

Add `import com.breadmoirai.garnet.structure.PlacedBox` to `ProjectDimRegistry.kt`, and drop `BlockPos`/`Vec3i` imports there only if nothing else in the file uses them.

- [ ] **Step 7: Move the rest into `structure/`**

```bash
cd /mnt/h/Repo/RedstoneSpecs
D=src/main/kotlin/com/breadmoirai/garnet/structure
git mv src/main/kotlin/com/breadmoirai/garnet/persistence/StructurePersistence.kt $D/
git mv src/main/kotlin/com/breadmoirai/garnet/persistence/StructureDiff.kt $D/
git mv src/main/kotlin/com/breadmoirai/garnet/project/StructureRegionMath.kt $D/
sed -i 's/^package com\.breadmoirai\.garnet\.\(persistence\|project\)$/package com.breadmoirai.garnet.structure/' $D/*.kt
```

Then delete the four now-intra-package imports from `structure/StructurePersistence.kt`:

```kotlin
import com.breadmoirai.garnet.project.PlacedBox
import com.breadmoirai.garnet.project.anchorY
import com.breadmoirai.garnet.project.autoFit
import com.breadmoirai.garnet.project.centeredStart
```

- [ ] **Step 8: Rewrite importers of the moved structure symbols**

```bash
cd /mnt/h/Repo/RedstoneSpecs
FILES=$(grep -rl "StructurePersistence\|StructureDiff\|PlacedBox\|autoFit\|centeredStart\|anchorY\|StructureRegionMath" src --include='*.kt')
for f in $FILES; do
  sed -i \
    -e 's/com\.breadmoirai\.garnet\.persistence\.StructurePersistence/com.breadmoirai.garnet.structure.StructurePersistence/g' \
    -e 's/com\.breadmoirai\.garnet\.persistence\.StructureDiff/com.breadmoirai.garnet.structure.StructureDiff/g' \
    -e 's/com\.breadmoirai\.garnet\.project\.PlacedBox/com.breadmoirai.garnet.structure.PlacedBox/g' \
    -e 's/com\.breadmoirai\.garnet\.project\.autoFit/com.breadmoirai.garnet.structure.autoFit/g' \
    -e 's/com\.breadmoirai\.garnet\.project\.centeredStart/com.breadmoirai.garnet.structure.centeredStart/g' \
    -e 's/com\.breadmoirai\.garnet\.project\.anchorY/com.breadmoirai.garnet.structure.anchorY/g' \
    "$f"
done
```

- [ ] **Step 9: Move the matching unit tests**

```bash
cd /mnt/h/Repo/RedstoneSpecs
mkdir -p src/test/kotlin/com/breadmoirai/garnet/structure
git mv src/test/kotlin/com/breadmoirai/garnet/persistence/StructureDiffTest.kt src/test/kotlin/com/breadmoirai/garnet/structure/
git mv src/test/kotlin/com/breadmoirai/garnet/project/StructureRegionMathTest.kt src/test/kotlin/com/breadmoirai/garnet/structure/
sed -i 's/^package com\.breadmoirai\.garnet\.\(persistence\|project\)$/package com.breadmoirai.garnet.structure/' src/test/kotlin/com/breadmoirai/garnet/structure/*.kt
```

Also move the gametest structure specs:

```bash
mkdir -p src/gametest/kotlin/com/breadmoirai/garnet/test/structure
git mv src/gametest/kotlin/com/breadmoirai/garnet/test/persistence/StructureRegionPersistenceSpec.kt \
       src/gametest/kotlin/com/breadmoirai/garnet/test/persistence/StructureSidecarPersistenceSpec.kt \
       src/gametest/kotlin/com/breadmoirai/garnet/test/structure/
sed -i 's/^package com\.breadmoirai\.garnet\.test\.persistence$/package com.breadmoirai.garnet.test.structure/' \
       src/gametest/kotlin/com/breadmoirai/garnet/test/structure/*.kt
```

Update the two imports in `GametestSentinel.kt` from `test.persistence.` to `test.structure.`.

### `dsl/` → `spec/`

- [ ] **Step 10: Move and rewrite**

```bash
cd /mnt/h/Repo/RedstoneSpecs
git mv src/main/kotlin/com/breadmoirai/garnet/dsl src/main/kotlin/com/breadmoirai/garnet/spec
sed -i 's/^package com\.breadmoirai\.garnet\.dsl$/package com.breadmoirai.garnet.spec/' src/main/kotlin/com/breadmoirai/garnet/spec/*.kt
FILES=$(grep -rl "com\.breadmoirai\.garnet\.dsl" src --include='*.kt')
for f in $FILES; do sed -i 's/com\.breadmoirai\.garnet\.dsl/com.breadmoirai.garnet.spec/g' "$f"; done
```

That last loop also rewrites the string literals inside `RecordingDslEmitter` and `SpecScript`, which is what we want — but verify:

- [ ] **Step 11: Confirm the emitted import line changed**

```bash
cd /mnt/h/Repo/RedstoneSpecs
grep -n "garnet.spec\|garnet.dsl" src/main/kotlin/com/breadmoirai/garnet/runner/RecordingDslEmitter.kt src/main/kotlin/com/breadmoirai/garnet/persistence/SpecScript.kt
```

Expected: three `sb.appendLine("import com.breadmoirai.garnet.spec.*")` in the emitter, one `"com.breadmoirai.garnet.spec.*"` in `SpecScript.defaultImports`, and no `garnet.dsl` anywhere. Every `.spec.kts` file emitted from now on carries the new import; no such files are committed to this repo.

- [ ] **Step 12: Move the DSL unit tests**

```bash
cd /mnt/h/Repo/RedstoneSpecs
mkdir -p src/test/kotlin/com/breadmoirai/garnet/spec
git mv src/test/kotlin/com/breadmoirai/garnet/dsl/SpecRunSchedulerTest.kt src/test/kotlin/com/breadmoirai/garnet/spec/
git mv src/test/kotlin/com/breadmoirai/garnet/data/SimTimeTest.kt src/test/kotlin/com/breadmoirai/garnet/data/StateConditionTest.kt src/test/kotlin/com/breadmoirai/garnet/spec/
sed -i 's/^package com\.breadmoirai\.garnet\.\(dsl\|data\)$/package com.breadmoirai.garnet.spec/' src/test/kotlin/com/breadmoirai/garnet/spec/*.kt
rmdir src/test/kotlin/com/breadmoirai/garnet/dsl src/test/kotlin/com/breadmoirai/garnet/data 2>/dev/null || true
```

### Client base packages

- [ ] **Step 13: Move the dock shell and config**

```bash
cd /mnt/h/Repo/RedstoneSpecs
C=src/client/kotlin/com/breadmoirai/garnet
mkdir -p $C/ui/compose $C/ui/dock $C/ui/input $C/ui/viewport $C/ui/widget $C/config
git mv $C/client/ui/compose/dock/*.kt $C/ui/dock/
git mv $C/client/ui/compose/input/*.kt $C/ui/input/
git mv $C/client/ui/compose/*.kt $C/ui/compose/
git mv $C/client/viewport/*.kt $C/ui/viewport/
git mv $C/client/screen/GarnetIconButton.kt $C/ui/widget/
git mv $C/client/config/*.kt $C/config/
sed -i 's/^package com\.breadmoirai\.garnet\.client\.ui\.compose\.dock$/package com.breadmoirai.garnet.ui.dock/'   $C/ui/dock/*.kt
sed -i 's/^package com\.breadmoirai\.garnet\.client\.ui\.compose\.input$/package com.breadmoirai.garnet.ui.input/' $C/ui/input/*.kt
sed -i 's/^package com\.breadmoirai\.garnet\.client\.ui\.compose$/package com.breadmoirai.garnet.ui.compose/'      $C/ui/compose/*.kt
sed -i 's/^package com\.breadmoirai\.garnet\.client\.viewport$/package com.breadmoirai.garnet.ui.viewport/'        $C/ui/viewport/*.kt
sed -i 's/^package com\.breadmoirai\.garnet\.client\.screen$/package com.breadmoirai.garnet.ui.widget/'            $C/ui/widget/*.kt
sed -i 's/^package com\.breadmoirai\.garnet\.client\.config$/package com.breadmoirai.garnet.config/'               $C/config/*.kt
```

- [ ] **Step 14: Rewrite importers, longest prefix first**

```bash
cd /mnt/h/Repo/RedstoneSpecs
FILES=$(grep -rl "garnet\.client\.\(ui\|viewport\|screen\|config\)" src --include='*.kt' --include='*.java')
for f in $FILES; do
  sed -i \
    -e 's/com\.breadmoirai\.garnet\.client\.ui\.compose\.dock/com.breadmoirai.garnet.ui.dock/g' \
    -e 's/com\.breadmoirai\.garnet\.client\.ui\.compose\.input/com.breadmoirai.garnet.ui.input/g' \
    -e 's/com\.breadmoirai\.garnet\.client\.ui\.compose/com.breadmoirai.garnet.ui.compose/g' \
    -e 's/com\.breadmoirai\.garnet\.client\.viewport/com.breadmoirai.garnet.ui.viewport/g' \
    -e 's/com\.breadmoirai\.garnet\.client\.screen/com.breadmoirai.garnet.ui.widget/g' \
    -e 's/com\.breadmoirai\.garnet\.client\.config/com.breadmoirai.garnet.config/g' \
    "$f"
done
```

The `.dock` and `.input` rules must precede the bare `.ui.compose` rule, or the prefix match rewrites them wrong.

`TitleScreenMixin.java` imports `GarnetIconButton` — the `--include='*.java'` above covers it. `fabric.mod.json`'s `modmenu` entrypoint names `com.breadmoirai.garnet.client.config.ModMenuIntegration`:

- [ ] **Step 15: Update the ModMenu entrypoint**

In `src/main/resources/fabric.mod.json`, change:

```json
    "modmenu": [
      "com.breadmoirai.garnet.config.ModMenuIntegration"
    ]
```

- [ ] **Step 16: Fix `internal` visibility fallout**

```bash
cd /mnt/h/Repo/RedstoneSpecs && cmd.exe /c "gradlew.bat :26.2:clientClasses :26.2:classes"
```

Expect errors of the form "Cannot access 'X': it is internal in ...". `config/` now spans `src/main` (`SharedSettings`) and `src/client` (`ModConfig`, `ModMenuIntegration`) — same package, different sourcesets, so `internal` does not cross. Promote the named members to `public`. Do not restructure to avoid this.

- [ ] **Step 17: Full build, all suites, commit**

```bash
cd /mnt/h/Repo/RedstoneSpecs
cmd.exe /c "gradlew.bat :26.2:clientClasses :26.2:classes :26.2:testSupportClasses :26.2:gametestClasses :26.2:clientTestClasses :26.2:testClasses"
cmd.exe /c "gradlew.bat :26.2:test"
cmd.exe /c "gradlew.bat :26.2:runGameTest"
cmd.exe /c "gradlew.bat :26.2:runClientTest"
git add -A
git commit -m "refactor: extract spec/, structure/, ui/, and config/ base packages

dsl/ becomes spec/ — it is the vocabulary both playback and testing speak,
so it cannot live inside either. Structure templates and the region math
they depend on join in structure/; PlacedBox moves out of ProjectDimRegistry
to break StructurePersistence's reach into project code. The Compose dock
shell and the client config leave the client.* root, since the sourceset
already says client.

Two seam fixes: StateRecordingView now implements the StateRecordingViewLike
interface SpecRun already declared, so ConditionEvaluator takes the interface
and spec/ imports nothing from the project.

Emitted .spec.kts files now open with import com.breadmoirai.garnet.spec.*."
```

---

## Task 6: `playback/` and `testing/`

Splits `runner/` and `persistence/` into the two feature packages.

**Files:**
- Move: `runner/{StateRecorder,RecordingDslEmitter}.kt` → `playback/recorder/`; `runner/{StateRecording,StateRecordingStorage,StateRecordingView}.kt` + `persistence/RecordingSidecar.kt` → `playback/data/`
- Move: `runner/{runGarnetSpec,SpecSnapshot}.kt` → `testing/runner/`; `persistence/{SpecPersistence,KtsSpecLoader,SpecScript,SpecDirectoryScan}.kt` + `project/LoadedSpec.kt` → `editor/data/`

**Interfaces:**
- Consumes: Task 5's `spec/` and `structure/`.
- Produces: `com.breadmoirai.garnet.playback.recorder.{StateRecorder, EntryMarker, RecordingDslEmitter}`, `com.breadmoirai.garnet.playback.data.{StateRecording, StateRecordingStorage, StateRecordingView, RecordingSidecar}`, `com.breadmoirai.garnet.testing.runner.{runGarnetSpec, SpecSnapshot}`, `com.breadmoirai.garnet.testing.data.{SpecPersistence, KtsSpecLoader, SpecScript, SpecDirectoryScan}`.

- [ ] **Step 1: Move into `playback/`**

```bash
cd /mnt/h/Repo/RedstoneSpecs
G=src/main/kotlin/com/breadmoirai/garnet
mkdir -p $G/playback/recorder $G/playback/data
git mv $G/runner/StateRecorder.kt $G/runner/RecordingDslEmitter.kt $G/playback/recorder/
git mv $G/runner/StateRecording.kt $G/runner/StateRecordingStorage.kt $G/runner/StateRecordingView.kt $G/playback/data/
git mv $G/persistence/RecordingSidecar.kt $G/playback/data/
sed -i 's/^package com\.breadmoirai\.garnet\.runner$/package com.breadmoirai.garnet.playback.recorder/' $G/playback/recorder/*.kt
sed -i 's/^package com\.breadmoirai\.garnet\.\(runner\|persistence\)$/package com.breadmoirai.garnet.playback.data/' $G/playback/data/*.kt
```

- [ ] **Step 2: Move into `testing/`**

```bash
cd /mnt/h/Repo/RedstoneSpecs
G=src/main/kotlin/com/breadmoirai/garnet
mkdir -p $G/testing/runner $G/testing/data
git mv $G/runner/runGarnetSpec.kt $G/runner/SpecSnapshot.kt $G/testing/runner/
git mv $G/persistence/SpecPersistence.kt $G/persistence/KtsSpecLoader.kt $G/persistence/SpecScript.kt $G/persistence/SpecDirectoryScan.kt $G/testing/data/
git mv $G/project/LoadedSpec.kt $G/editor/data/
sed -i 's/^package com\.breadmoirai\.garnet\.runner$/package com.breadmoirai.garnet.testing.runner/' $G/testing/runner/*.kt
sed -i 's/^package com\.breadmoirai\.garnet\.\(persistence\|project\)$/package com.breadmoirai.garnet.testing.data/' $G/testing/data/*.kt
rmdir $G/runner $G/persistence 2>/dev/null || true
```

`$G/persistence` and `$G/runner` should now be empty. If not, list the leftovers and place them before continuing — every file was accounted for in the spec's mapping tables.

- [ ] **Step 3: Rewrite importers by symbol, not by package**

The old `runner` and `persistence` packages split two ways, so a package-level `sed` would be wrong. Map each symbol:

```bash
cd /mnt/h/Repo/RedstoneSpecs
FILES=$(grep -rl "com\.breadmoirai\.garnet\.\(runner\|persistence\)\." src --include='*.kt')
for f in $FILES; do
  sed -i \
    -e 's/com\.breadmoirai\.garnet\.runner\.StateRecordingStorage/com.breadmoirai.garnet.playback.data.StateRecordingStorage/g' \
    -e 's/com\.breadmoirai\.garnet\.runner\.StateRecordingView/com.breadmoirai.garnet.playback.data.StateRecordingView/g' \
    -e 's/com\.breadmoirai\.garnet\.runner\.StateRecording/com.breadmoirai.garnet.playback.data.StateRecording/g' \
    -e 's/com\.breadmoirai\.garnet\.runner\.stateRecordingFromNbt/com.breadmoirai.garnet.playback.data.stateRecordingFromNbt/g' \
    -e 's/com\.breadmoirai\.garnet\.runner\.toNbt/com.breadmoirai.garnet.playback.data.toNbt/g' \
    -e 's/com\.breadmoirai\.garnet\.runner\.StateRecorder/com.breadmoirai.garnet.playback.recorder.StateRecorder/g' \
    -e 's/com\.breadmoirai\.garnet\.runner\.RecordingDslEmitter/com.breadmoirai.garnet.playback.recorder.RecordingDslEmitter/g' \
    -e 's/com\.breadmoirai\.garnet\.runner\.EntryMarker/com.breadmoirai.garnet.playback.recorder.EntryMarker/g' \
    -e 's/com\.breadmoirai\.garnet\.runner\.runGarnetSpec/com.breadmoirai.garnet.testing.runner.runGarnetSpec/g' \
    -e 's/com\.breadmoirai\.garnet\.runner\.SpecSnapshot/com.breadmoirai.garnet.testing.runner.SpecSnapshot/g' \
    -e 's/com\.breadmoirai\.garnet\.persistence\.RecordingSidecar/com.breadmoirai.garnet.playback.data.RecordingSidecar/g' \
    -e 's/com\.breadmoirai\.garnet\.persistence\.SpecPersistence/com.breadmoirai.garnet.testing.data.SpecPersistence/g' \
    -e 's/com\.breadmoirai\.garnet\.persistence\.KtsSpecLoader/com.breadmoirai.garnet.testing.data.KtsSpecLoader/g' \
    -e 's/com\.breadmoirai\.garnet\.persistence\.SpecScript/com.breadmoirai.garnet.testing.data.SpecScript/g' \
    -e 's/com\.breadmoirai\.garnet\.persistence\.SpecDirectoryScan/com.breadmoirai.garnet.testing.data.SpecDirectoryScan/g' \
    -e 's/com\.breadmoirai\.garnet\.project\.LoadedSpec/com.breadmoirai.garnet.editor.data.LoadedSpec/g' \
    "$f"
done
```

`StateRecordingStorage` and `StateRecordingView` must precede `StateRecording` — it is a prefix of both.

- [ ] **Step 4: Fix the fully-qualified reference in `Garnet.kt`**

`Garnet.kt` names `com.breadmoirai.garnet.runner.StateRecorder` inline inside the `SubTickPhaseEvents.PHASE` lambda. The step-3 loop rewrites it, but confirm:

```bash
cd /mnt/h/Repo/RedstoneSpecs
grep -rn "garnet\.runner\.\|garnet\.persistence\." src --include='*.kt'
```

Expected: no output.

- [ ] **Step 5: Move the unit tests**

```bash
cd /mnt/h/Repo/RedstoneSpecs
T=src/test/kotlin/com/breadmoirai/garnet
mkdir -p $T/playback/data $T/playback/recorder $T/testing/data
git mv $T/runner/RecordingDslEmitterTest.kt $T/playback/recorder/
git mv $T/runner/StateRecordingStorageTest.kt $T/runner/StateRecordingViewTest.kt $T/playback/data/
git mv $T/persistence/RecordingSidecarTest.kt $T/playback/data/
git mv $T/persistence/KtsSpecLoaderTest.kt $T/persistence/KtsSpecLoaderRoundtripTest.kt $T/persistence/SpecPersistenceTest.kt $T/persistence/SpecDirectoryScanTest.kt $T/testing/data/
git mv $T/project/LoadedSpecTest.kt $T/editor/data/
sed -i 's/^package com\.breadmoirai\.garnet\.runner$/package com.breadmoirai.garnet.playback.recorder/' $T/playback/recorder/*.kt
sed -i 's/^package com\.breadmoirai\.garnet\.\(runner\|persistence\)$/package com.breadmoirai.garnet.playback.data/' $T/playback/data/*.kt
sed -i 's/^package com\.breadmoirai\.garnet\.\(persistence\|project\)$/package com.breadmoirai.garnet.testing.data/' $T/testing/data/*.kt
rmdir $T/runner $T/persistence 2>/dev/null || true
```

- [ ] **Step 6: Full build, all suites, commit**

```bash
cd /mnt/h/Repo/RedstoneSpecs
cmd.exe /c "gradlew.bat :26.2:clientClasses :26.2:classes :26.2:testSupportClasses :26.2:gametestClasses :26.2:clientTestClasses :26.2:testClasses"
cmd.exe /c "gradlew.bat :26.2:test"
cmd.exe /c "gradlew.bat :26.2:runGameTest"
cmd.exe /c "gradlew.bat :26.2:runClientTest"
git add -A
git commit -m "refactor: split runner/ and persistence/ into playback/ and testing/

The old runner package held two different things: the capture side
(StateRecorder, the recordings, the emitter) and the verification side
(runGarnetSpec, snapshots, interaction dispatch). persistence/ was split the
same way between recording sidecars and spec files. Each half now sits with
the feature it serves.

Direction is one-way: testing.runner drives playback.recorder and returns a
playback.data.StateRecording. Nothing in playback reaches back."
```

---

## Task 7: `editor/`

Moves the project model, dimension substrate, command, networking, and Explorer into one feature, renaming the `Project` class prefix to `Editor`.

**Files:**
- Move: `project/` → `editor/data/` and `editor/world/`; `project/ProjectCommand.kt` → `editor/command/`; `network/project/` → `editor/network/`; `client/ide/` → `editor/ui/`; `client/project/` → `editor/network/` and `editor/world/`
- Modify: `client/viewport/DockKeybinds.kt` → split
- Modify: every importer

**Interfaces:**
- Consumes: Tasks 5 and 6.
- Produces: `com.breadmoirai.garnet.editor.data.{EditorRoot, EditorSession, EditorCell, EditorNames, EditorSaveNaming, EditorNewSpec, EditorNewStructure, EditorFolderTree, FileTree}`, `com.breadmoirai.garnet.editor.world.{EditorDimRegistry, EditorDimLifecycle, EditorWorld, EditorCellSaver, EditorTeleport, EditorServerContext, GridLayout, EditorIntegratedBoot}`, `com.breadmoirai.garnet.editor.command.EditorCommand`, `com.breadmoirai.garnet.editor.network.{EditorPackets, EditorNetworking, EditorClientNetworking}`, `com.breadmoirai.garnet.editor.ui.*`.

- [ ] **Step 1: Move the server-side files into `data/`, `world/`, and `command/`**

```bash
cd /mnt/h/Repo/RedstoneSpecs
G=src/main/kotlin/com/breadmoirai/garnet
mkdir -p $G/editor/data $G/editor/world $G/editor/command $G/editor/network
git mv $G/project/ProjectRoot.kt          $G/editor/data/EditorRoot.kt
git mv $G/project/ProjectSession.kt       $G/editor/data/EditorSession.kt
git mv $G/project/ProjectCell.kt          $G/editor/data/EditorCell.kt
git mv $G/project/ProjectNames.kt         $G/editor/data/EditorNames.kt
git mv $G/project/ProjectSaveNaming.kt    $G/editor/data/EditorSaveNaming.kt
git mv $G/project/ProjectNewSpec.kt       $G/editor/data/EditorNewSpec.kt
git mv $G/project/ProjectNewStructure.kt  $G/editor/data/EditorNewStructure.kt
git mv $G/project/ProjectFolderTree.kt    $G/editor/data/EditorFolderTree.kt
git mv $G/project/FileTree.kt             $G/editor/data/
git mv $G/project/ProjectDimRegistry.kt   $G/editor/world/EditorDimRegistry.kt
git mv $G/project/ProjectDimLifecycle.kt  $G/editor/world/EditorDimLifecycle.kt
git mv $G/project/ProjectWorld.kt         $G/editor/world/EditorWorld.kt
git mv $G/project/ProjectCellSaver.kt     $G/editor/world/EditorCellSaver.kt
git mv $G/project/ProjectTeleport.kt      $G/editor/world/EditorTeleport.kt
git mv $G/project/ProjectServerContext.kt $G/editor/world/EditorServerContext.kt
git mv $G/project/GridLayout.kt           $G/editor/world/
git mv $G/project/ProjectCommand.kt       $G/editor/command/EditorCommand.kt
git mv $G/network/project/ProjectPackets.kt         $G/editor/network/EditorPackets.kt
git mv $G/network/project/ProjectNetworkRegistry.kt $G/editor/network/EditorNetworking.kt
rmdir $G/project $G/network/project $G/network 2>/dev/null || true
```

- [ ] **Step 2: Rewrite the package lines**

```bash
cd /mnt/h/Repo/RedstoneSpecs
G=src/main/kotlin/com/breadmoirai/garnet
sed -i 's/^package com\.breadmoirai\.garnet\.project$/package com.breadmoirai.garnet.editor.data/'    $G/editor/data/*.kt
sed -i 's/^package com\.breadmoirai\.garnet\.project$/package com.breadmoirai.garnet.editor.world/'   $G/editor/world/*.kt
sed -i 's/^package com\.breadmoirai\.garnet\.project$/package com.breadmoirai.garnet.editor.command/' $G/editor/command/*.kt
sed -i 's/^package com\.breadmoirai\.garnet\.network\.project$/package com.breadmoirai.garnet.editor.network/' $G/editor/network/*.kt
```

- [ ] **Step 3: Move the client-side files**

```bash
cd /mnt/h/Repo/RedstoneSpecs
C=src/client/kotlin/com/breadmoirai/garnet
mkdir -p $C/editor/ui $C/editor/network $C/editor/world
git mv $C/client/ide/*.kt $C/editor/ui/
git mv $C/client/project/ProjectClientNetworking.kt $C/editor/network/EditorClientNetworking.kt
git mv $C/client/project/ProjectIntegratedBoot.kt   $C/editor/world/EditorIntegratedBoot.kt
sed -i 's/^package com\.breadmoirai\.garnet\.client\.ide$/package com.breadmoirai.garnet.editor.ui/'          $C/editor/ui/*.kt
sed -i 's/^package com\.breadmoirai\.garnet\.client\.project$/package com.breadmoirai.garnet.editor.network/' $C/editor/network/*.kt
sed -i 's/^package com\.breadmoirai\.garnet\.client\.project$/package com.breadmoirai.garnet.editor.world/'   $C/editor/world/*.kt
rmdir $C/client/ide $C/client/project $C/client/network $C/client/render $C/client/state $C/client/ui/compose $C/client/ui $C/client/screen 2>/dev/null || true
```

`$C/client/` should retain only `GarnetClient.kt`. Move it up and drop the directory:

```bash
git mv $C/client/GarnetClient.kt $C/
sed -i 's/^package com\.breadmoirai\.garnet\.client$/package com.breadmoirai.garnet/' $C/GarnetClient.kt
rmdir $C/client 2>/dev/null || true
```

Then update `fabric.mod.json`'s client entrypoint to `com.breadmoirai.garnet.GarnetClient`.

- [ ] **Step 4: Rename the classes**

```bash
cd /mnt/h/Repo/RedstoneSpecs
FILES=$(grep -rl "Project" src --include='*.kt' --include='*.java')
for f in $FILES; do
  sed -i \
    -e 's/\bProjectNetworkRegistry\b/EditorNetworking/g' \
    -e 's/\bProjectClientNetworking\b/EditorClientNetworking/g' \
    -e 's/\bProjectIntegratedBoot\b/EditorIntegratedBoot/g' \
    -e 's/\bProjectServerContext\b/EditorServerContext/g' \
    -e 's/\bProjectDimLifecycle\b/EditorDimLifecycle/g' \
    -e 's/\bProjectDimRegistry\b/EditorDimRegistry/g' \
    -e 's/\bProjectNewStructure\b/EditorNewStructure/g' \
    -e 's/\bProjectSaveNaming\b/EditorSaveNaming/g' \
    -e 's/\bProjectFolderTree\b/EditorFolderTree/g' \
    -e 's/\bProjectCellSaver\b/EditorCellSaver/g' \
    -e 's/\bProjectTeleport\b/EditorTeleport/g' \
    -e 's/\bProjectSession\b/EditorSession/g' \
    -e 's/\bProjectCommand\b/EditorCommand/g' \
    -e 's/\bProjectNewSpec\b/EditorNewSpec/g' \
    -e 's/\bProjectPackets\b/EditorPackets/g' \
    -e 's/\bProjectNames\b/EditorNames/g' \
    -e 's/\bProjectWorld\b/EditorWorld/g' \
    -e 's/\bProjectRoot\b/EditorRoot/g' \
    -e 's/\bProjectCell\b/EditorCell/g' \
    "$f"
done
```

`ProjectCellSaver` must precede `ProjectCell`, and `ProjectNetworkRegistry` must precede any shorter `Project*` prefix. The list above is already ordered longest-first within each family — preserve that order.

**Do not** rewrite `ProjectTreeSnapshotS2C`-style payload names with a blanket rule; handle the payload classes explicitly in the next step so the `Identifier` string literals are not touched.

- [ ] **Step 5: Rename the payload classes without touching the wire IDs**

In `editor/network/EditorPackets.kt`, rename each payload class from `Project*` to `Editor*` (`ProjectTreeSnapshotS2C` → `EditorTreeSnapshotS2C`, `ProjectFolderLoadedS2C` → `EditorFolderLoadedS2C`, `ProjectSaveReportS2C` → `EditorSaveReportS2C`, `ProjectErrorS2C` → `EditorErrorS2C`, `ListProjectTreeC2S` → `ListEditorTreeC2S`, `LoadProjectFolderC2S` → `LoadEditorFolderC2S`, `SetProjectRootC2S` → `SetEditorRootC2S`, `UnloadProjectFolderC2S` → `UnloadEditorFolderC2S`, `NewProjectSpecC2S` → `NewEditorSpecC2S`).

Leave every `Identifier.fromNamespaceAndPath(...)` string literal exactly as it is. Verify:

```bash
cd /mnt/h/Repo/RedstoneSpecs
git diff src/main/kotlin/com/breadmoirai/garnet/editor/network/EditorPackets.kt | grep '^[-+].*fromNamespaceAndPath'
```

Expected: no output — no `Identifier` line changed.

- [ ] **Step 6: Rewrite the remaining package-qualified imports**

```bash
cd /mnt/h/Repo/RedstoneSpecs
FILES=$(grep -rl "garnet\.project\.\|garnet\.network\.project\.\|garnet\.client\.\(ide\|project\)\." src --include='*.kt' --include='*.java')
for f in $FILES; do
  sed -i \
    -e 's/com\.breadmoirai\.garnet\.network\.project\./com.breadmoirai.garnet.editor.network./g' \
    -e 's/com\.breadmoirai\.garnet\.client\.ide\./com.breadmoirai.garnet.editor.ui./g' \
    -e 's/com\.breadmoirai\.garnet\.client\.project\./com.breadmoirai.garnet.editor.network./g' \
    "$f"
done
```

Then fix the leftover `garnet.project.` imports by hand — they split between `editor.data` and `editor.world`, so a blanket rule would be wrong. Use the mapping in step 1 as the key. The compiler will name every one of them.

- [ ] **Step 7: Split `DockKeybinds`**

`ui/viewport/DockKeybinds.kt` registers dock keybinds and, in `registerDockWorldLifecycle`, resets Explorer tree state on world unload — the base UI package reaching into a feature.

Move `registerDockWorldLifecycle` and its two Explorer imports into a new file `src/client/kotlin/com/breadmoirai/garnet/editor/ui/ExplorerLifecycle.kt`:

```kotlin
package com.breadmoirai.garnet.editor.ui

// imports for the Fabric client world-unload event, as in the original DockKeybinds.kt

/** Resets Explorer tree state when the client leaves a world. */
fun registerExplorerLifecycle() {
    // body moved verbatim from DockKeybinds.registerDockWorldLifecycle
}
```

Delete `registerDockWorldLifecycle` and the `ExplorerTreeState` / `ProjectTreeState` imports from `DockKeybinds.kt`. In `GarnetClient.onInitializeClient`, replace `registerDockWorldLifecycle()` with `registerExplorerLifecycle()` and update the import.

If `registerDockWorldLifecycle` also does viewport work unrelated to the Explorer, leave that part behind in `DockKeybinds.kt` under its original name and call both from `GarnetClient`.

- [ ] **Step 8: Verify the layering holds**

```bash
cd /mnt/h/Repo/RedstoneSpecs
echo "-- base must not import features:"
grep -rn "garnet\.\(playback\|testing\|editor\)\." src/main/kotlin/com/breadmoirai/garnet/{spec,mc,structure,config} src/client/kotlin/com/breadmoirai/garnet/{ui,config} 2>/dev/null
echo "-- playback must not import testing or editor:"
grep -rn "garnet\.\(testing\|editor\)\." src/main/kotlin/com/breadmoirai/garnet/playback
echo "-- testing must not import editor:"
grep -rn "garnet\.editor\." src/main/kotlin/com/breadmoirai/garnet/testing
```

Expected: no output from any of the three. If there is, the offending import is a violation the spec did not anticipate — report it rather than working around it.

- [ ] **Step 9: Move the tests**

```bash
cd /mnt/h/Repo/RedstoneSpecs
T=src/test/kotlin/com/breadmoirai/garnet
mkdir -p $T/editor/data $T/editor/world
git mv $T/project/FileTreeTest.kt $T/project/ProjectFolderTreeTest.kt $T/project/ProjectNamesTest.kt \
       $T/project/ProjectNewSpecTest.kt $T/project/ProjectNewStructureTest.kt $T/project/ProjectRootTest.kt \
       $T/project/ProjectSaveNamingTest.kt $T/project/ProjectSessionTest.kt $T/project/ProjectCellTest.kt \
       $T/editor/data/
git mv $T/project/GridLayoutTest.kt $T/project/ProjectDimRegistryTest.kt $T/project/ProjectLifecycleReleaseTest.kt \
       $T/editor/world/
mkdir -p $T/editor/network
git mv $T/network/project/FileTreeCodecTest.kt $T/editor/network/
git mv $T/network/StructurePacketsTest.kt $T/editor/network/
sed -i 's/^package com\.breadmoirai\.garnet\.project$/package com.breadmoirai.garnet.editor.data/'  $T/editor/data/*.kt
sed -i 's/^package com\.breadmoirai\.garnet\.project$/package com.breadmoirai.garnet.editor.world/' $T/editor/world/*.kt
sed -i 's/^package com\.breadmoirai\.garnet\.network\(\.project\)\?$/package com.breadmoirai.garnet.editor.network/' $T/editor/network/*.kt
rmdir $T/project $T/network/project $T/network 2>/dev/null || true
```

Rename the test **files** to match their renamed subjects (`ProjectRootTest.kt` → `EditorRootTest.kt`, and so on) — the class names inside were already rewritten by step 4.

`StructurePacketsTest.kt` sits in `test/network/` but its subjects are all `network.project.*` payloads — `PlaceStructureC2S`, `SaveStructureC2S`, `NewStructureC2S`, `CreateFolderC2S`, `RenamePathC2S`, `DiscardStructureC2S`, `StructureResultS2C`. Those are editor payloads and survive Task 1, so it moves to `editor/network/` despite the name. Consider renaming it `EditorStructurePacketsTest.kt`.

Then the gametest specs:

```bash
mkdir -p src/gametest/kotlin/com/breadmoirai/garnet/test/editor
git mv src/gametest/kotlin/com/breadmoirai/garnet/test/project/*.kt src/gametest/kotlin/com/breadmoirai/garnet/test/editor/
sed -i 's/^package com\.breadmoirai\.garnet\.test\.project$/package com.breadmoirai.garnet.test.editor/' \
       src/gametest/kotlin/com/breadmoirai/garnet/test/editor/*.kt
rmdir src/gametest/kotlin/com/breadmoirai/garnet/test/project 2>/dev/null || true
```

Update `GametestSentinel`'s imports from `test.project.` to `test.editor.` — and its spec-list entries, whose class names step 4 already renamed (`ProjectDimSpec` → `EditorDimSpec`, etc.). Rename those spec files to match.

- [ ] **Step 10: Full build, all suites**

```bash
cd /mnt/h/Repo/RedstoneSpecs
cmd.exe /c "gradlew.bat :26.2:clientClasses :26.2:classes :26.2:testSupportClasses :26.2:gametestClasses :26.2:clientTestClasses :26.2:testClasses"
cmd.exe /c "gradlew.bat :26.2:test"
cmd.exe /c "gradlew.bat :26.2:runGameTest"
cmd.exe /c "gradlew.bat :26.2:runClientTest"
```

Expect `internal` visibility errors where `src/client` `editor.ui` code reaches `src/main` `editor.data` code — same package, different sourceset. Promote to `public`.

- [ ] **Step 11: Manual smoke check**

Launch the client, open a redstone project, and confirm the Explorer lists files, creates a folder, renames a file, and places a structure. After Task 1 this is the entire remaining product surface; the automated suites do not cover the Compose rendering path end to end.

- [ ] **Step 12: Commit**

```bash
git add -A
git commit -m "refactor: move the project workspace and explorer into editor/

The workspace model, the dimension and grid substrate, the command, the
packet family, and the Project Explorer were spread across project/,
network/project/, client/ide/, and client/project/. They are one capability
and now live in one package, split into data/ (pure, unit-tested) and
world/ (server side effects, gametest-covered).

Project* classes become Editor*. Payload Identifier strings are unchanged —
client and server ship in one jar, so there was nothing to stay compatible
with, and leaving them alone keeps the diff honest.

DockKeybinds no longer resets Explorer state; that moved to
editor/ui/ExplorerLifecycle.kt so the shared dock shell stops reaching into
a feature."
```

---

## Task 8: Documentation sync

**Files:**
- Rewrite: `docs/architecture/module-map.md`
- Modify: `docs/architecture/recording-pipeline.md`, `redstone-project.md`, `docs/gametest/*.md`, `docs/use-cases/*.md`, `docs/persistence/*.md`, `docs/ui/*.md`
- Modify: every `INDEX.md` whose entries changed

**Interfaces:**
- Consumes: the finished tree from Tasks 1–7.

- [ ] **Step 1: Rewrite `module-map.md`**

It is a package-by-package tour and every entry changed. Rewrite it against the actual tree, not against this plan. It currently also cites a `block/SpecBlockKind.kt` that does not exist — that drift predates this work.

- [ ] **Step 2: Handle articles whose subject was deleted**

`docs/persistence/network-payload-contract.md` documents the `originPos` → block-entity trust anchor, which went with `SpecBlockEntity`. Delete the article and remove its `INDEX.md` entry, or rewrite it around the editor payloads if the validation pattern still applies there — read `EditorNetworking` and decide.

`docs/architecture/recording-pipeline.md` needs its entry point rewritten: the pipeline is intact but has no in-game trigger. Say so explicitly rather than leaving a description of a flow a reader cannot reach.

- [ ] **Step 3: Mark unreachable use cases**

In `docs/use-cases/`, any journey that starts by placing a recorder or runner block is no longer reachable. Mark those entries rather than deleting them — they are the specification for the panels that will replace the blocks.

- [ ] **Step 4: Sweep for every renamed and deleted symbol**

```bash
cd /mnt/h/Repo/RedstoneSpecs
for s in SpecBlockEntity GarnetRecorderBlock GarnetRunnerBlock SpecMarkerTool ModRegistries \
         GarnetTestLifecycle garnet.dsl garnet.runner garnet.persistence garnet.project \
         ProjectRoot ProjectSession ProjectCell ProjectCommand ProjectNetworkRegistry \
         RecorderCommandC2S RunnerCommandC2S OpenRunnerScreenS2C RunnerStatusS2C; do
  echo "== $s"; grep -rn "$s" docs/ --include='*.md' | grep -v "docs/superpowers/"
done
```

Every hit is either a stale reference to fix or a deliberate historical mention. `docs/superpowers/` is excluded — those are commit-time snapshots and stay as written.

- [ ] **Step 5: Verify cross-references resolve**

Check that every `INDEX.md` entry points at a file that exists and that its summary still matches the article. Articles are indexed for `qmd` automatically on save; there is no manual reindex step.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "docs: sync architecture and use-case docs with the new package layout

Rewrites module-map.md against the new tree, retires the network payload
contract article along with the block entity it described, and marks the
record-from-block use cases as unreachable pending the dock panels."
```

---

## Self-review notes

**Spec coverage.** Every section of the design spec maps to a task: trim → Tasks 1–2; Kotest extraction → Task 3; `garnet.mc` → Task 4; base packages + both seam fixes → Task 5; `playback`/`testing` → Task 6; `editor` + the `DockKeybinds` split → Task 7; docs → Task 8. The spec's eight phases became eight tasks, with phase 1 split into Tasks 1 and 2 so the behavior-changing deletion can be reviewed separately from the unrelated dead code.

**Ordering hazards deliberately called out.** Four `sed` orderings would silently corrupt code if reversed: `GarnetTestSpecContext` before `GarnetTestSpec` (Task 3), `.ui.compose.dock`/`.input` before `.ui.compose` (Task 5), `StateRecordingStorage`/`View` before `StateRecording` (Task 6), and `ProjectCellSaver` before `ProjectCell` (Task 7).

**Known gap.** Task 7 step 6 leaves the `garnet.project.` → `editor.data`/`editor.world` import split to hand-resolution, because the source package fans out to two destinations. The compiler names every one, and step 1's move list is the key.

**Two errors caught during self-review, now fixed in place.** Kotest is declared as a plain `implementation` at `build.gradle.kts:165-166`, so moving only the harness *source* in Task 3 would have left Kotest on `main`'s classpath and still in the jar — Task 3 step 2 now moves the dependency too, and depends on Task 2 having removed `KtsSpecLoader`'s `io.kotest` import first. And `StructurePacketsTest.kt` lives in `test/network/` but covers `network.project.*` editor payloads, so it belongs in `editor/network/`, not `structure/`.
