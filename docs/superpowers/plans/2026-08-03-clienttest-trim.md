# clientTest Trim Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Cut `src/clientTest/` from 82 test cases across 19 files to 7 single-story specs, relocating 55 cases to `src/test/` as JVM unit tests, so `runClientTest` stops paying client-boot cost for pure-JVM assertions.

**Architecture:** The blocker is a classpath wall — `src/test` cannot see `client` output, so client-side code is untestable outside a booted client. Task 1 removes that wall (adds the client source set to the test classpath, stops stripping the Compose compiler plugin from `compileTestKotlin`) and proves it on the riskiest spec. Tasks 2–5 relocate specs that never needed a client. Tasks 6–9 consolidate what genuinely does need one down to one user story per file. Task 10 updates docs and runs full verification.

**Tech Stack:** Kotlin, Kotest 5.9.1, Gradle + Stonecutter (MC 26.2 slice), Fabric client-gametest API, Compose Multiplatform 1.11.0 + Jewel 0.39.1.

## Global Constraints

- **Gradle invocation:** always `cmd.exe /c "gradlew.bat <task>"` from the repo root. Never `./gradlew`; `cmd.exe` cannot parse the `./` prefix.
- **Stonecutter task paths:** `:26.2:test`, `:26.2:runClientTest`, `:26.2:runGameTest`. Never `:versions:26.2:`.
- **Kotest + `--tests` filters do not work.** `--tests` reports a false "No tests found". Run the whole task and read the XML report under `versions/26.2/build/test-results/test/`.
- **Gradle test runs hang after printing the summary.** Never pipe through `grep` (it buffers to an empty file). Redirect to a log file, poll the log, then kill the process.
- **`runClientTest` / `runGameTest` are the controller's job, not a subagent's.** Cold builds blow the 600s cap and orphaned runs wedge the daemon. An implementer subagent compiles and stops; the controller runs the in-game suites.
- **Full compile verification is five source sets:** `clientClasses classes gametestClasses clientTestClasses testClasses`. `compileKotlin` alone is not sufficient.
- **New specs in `src/clientTest/` must be registered in `ClientTestSentinel`.** Autoscan is off; an unregistered spec silently does not run. Specs in `src/test/` need no registration — the `test` task's JUnit platform discovers them.
- **No `Co-Authored-By` or "Generated with Claude Code" trailers in commits.**
- **Work directly on `main`.** No feature branches or worktrees.

---

### Task 1: Open the test classpath, and prove Jewel loads headless

This is the spike. Everything downstream depends on `TreeState` constructing on a plain JVM without pulling a Skiko native. `ExplorerTreeStateSpec` is the riskiest mover (17 cases, Jewel `TreeState` + `SelectableLazyListState` + compose snapshot state), so it goes first and alone. If it fails, the fallback in the spec applies and the remaining tasks proceed unchanged.

**Files:**
- Modify: `build.gradle.kts:134-137` (test source set classpath)
- Modify: `build.gradle.kts:285` (Compose plugin strip list)
- Create: `src/test/kotlin/com/breadmoirai/garnet/client/editor/ui/ExplorerTreeStateTest.kt`
- Delete: `src/clientTest/kotlin/com/breadmoirai/garnet/test/ExplorerTreeStateSpec.kt`
- Modify: `src/clientTest/kotlin/com/breadmoirai/garnet/test/ClientTestSentinel.kt:74` (drop `ExplorerTreeStateSpec::class`)

**Interfaces:**
- Consumes: nothing.
- Produces: a `src/test` source set that can compile against `com.breadmoirai.garnet.editor.ui.*`, `com.breadmoirai.garnet.ui.dock.*`, `com.breadmoirai.garnet.config.*`, Compose runtime/foundation, and Jewel foundation — including `@Composable` lambdas. Every later task relies on this.

- [ ] **Step 1: Widen the test source set's classpath**

Replace `build.gradle.kts:134-137`:

```kotlin
    sourceSets.named("test") {
        compileClasspath += testSupportSourceSet.output
        runtimeClasspath += testSupportSourceSet.output
    }
```

with:

```kotlin
    // `test` sees `client` for the same reason `clientTest` does: a large share of this mod's
    // testable logic (Explorer tree state, dock geometry, config round-trips, payload builders)
    // lives in the client source set but needs no live client to exercise. Without this, those
    // tests could only run inside a booted MC client — see
    // docs/gametest/unit-vs-gametest-split.md for the decision rule this enables.
    sourceSets.named("test") {
        compileClasspath += sourceSets["client"].output +
            sourceSets["client"].compileClasspath +
            testSupportSourceSet.output
        runtimeClasspath += sourceSets["client"].output +
            sourceSets["client"].runtimeClasspath +
            testSupportSourceSet.output
    }
```

- [ ] **Step 2: Stop stripping the Compose plugin from `compileTestKotlin`**

At `build.gradle.kts:285`, change:

```kotlin
listOf("compileKotlin", "compileTestKotlin", "compileGametestKotlin", "compileTestSupportKotlin").forEach { name ->
```

to:

```kotlin
// `compileTestKotlin` is deliberately absent: `test` now carries `client`'s compile classpath
// (and therefore `runtime-desktop`), so the Compose plugin's VersionChecker passes there, and
// the plugin is REQUIRED — `Panel.content` is `@Composable (Panel) -> Unit`, so any test that
// constructs a Panel needs the plugin to compile its lambda.
listOf("compileKotlin", "compileGametestKotlin", "compileTestSupportKotlin").forEach { name ->
```

Also update the comment block directly above it (`build.gradle.kts:280-284`), which currently reads "Strip it from the others (main, test, gametest)" — change that parenthetical to "(main, gametest, testSupport)".

- [ ] **Step 3: Move the spec, unwrapping the client hops**

Create `src/test/kotlin/com/breadmoirai/garnet/client/editor/ui/ExplorerTreeStateTest.kt` as a copy of `src/clientTest/kotlin/com/breadmoirai/garnet/test/ExplorerTreeStateSpec.kt` with exactly four mechanical edits, and no change to any assertion:

1. `package com.breadmoirai.garnet.test` → `package com.breadmoirai.garnet.client.editor.ui`
2. Drop `import com.breadmoirai.garnet.harness.ClientSpec`, add `import io.kotest.core.spec.style.FunSpec`
3. `class ExplorerTreeStateSpec : ClientSpec({` → `class ExplorerTreeStateTest : FunSpec({`
4. Unwrap all 10 `runOnClient { ... }` calls to their bodies. There is no client thread to hop to — `ExplorerTreeState` is Compose snapshot state, which is plain JVM state outside composition.

The unwrap for edit 4 looks like this — before:

```kotlin
    test("selection is stored in Jewel's TreeState, keyed by path") {
        runOnClient { ExplorerTreeState.reset(); ExplorerTreeState.select("adders/full-adder") }
        ExplorerTreeState.treeState.selectedKeys shouldContainExactly setOf("adders/full-adder")
        ExplorerTreeState.selectedPath shouldBe "adders/full-adder"
    }
```

after:

```kotlin
    test("selection is stored in Jewel's TreeState, keyed by path") {
        ExplorerTreeState.reset()
        ExplorerTreeState.select("adders/full-adder")
        ExplorerTreeState.treeState.selectedKeys shouldContainExactly setOf("adders/full-adder")
        ExplorerTreeState.selectedPath shouldBe "adders/full-adder"
    }
```

Where a `runOnClient { }` contains a single statement, inline it. Where it contains several separated by `;`, put each on its own line. Do not reorder statements — several tests depend on `reset()` preceding the mutation.

Then delete `src/clientTest/kotlin/com/breadmoirai/garnet/test/ExplorerTreeStateSpec.kt` and remove the `ExplorerTreeStateSpec::class,` line from `ClientTestSentinel.kt`.

- [ ] **Step 4: Compile all five source sets**

```bash
cmd.exe /c "gradlew.bat :26.2:clientClasses :26.2:classes :26.2:gametestClasses :26.2:clientTestClasses :26.2:testClasses" > /tmp/claude-1000/-mnt-h-Repo-RedstoneSpecs/47cfb0a0-bd70-4b2a-b1d1-a918e56e777a/scratchpad/compile1.log 2>&1
```

Expected: BUILD SUCCESSFUL. A `VersionChecker` failure here means Step 1 did not put `runtime-desktop` on the test compile classpath — re-check that `sourceSets["client"].compileClasspath` (not just `.output`) was added.

- [ ] **Step 5: Run the unit suite — this is the spike gate**

```bash
cmd.exe /c "gradlew.bat :26.2:test" > /tmp/claude-1000/-mnt-h-Repo-RedstoneSpecs/47cfb0a0-bd70-4b2a-b1d1-a918e56e777a/scratchpad/test1.log 2>&1
```

Read results from `versions/26.2/build/test-results/test/TEST-com.breadmoirai.garnet.client.editor.ui.ExplorerTreeStateTest.xml`, not from stdout.

Expected: 17 tests, 0 failures.

**If it fails with `UnsatisfiedLinkError`, `NoClassDefFoundError: org/jetbrains/skiko/...`, or a headless/AWT error:** the spike has answered its question. Revert only Step 3 (restore `ExplorerTreeStateSpec` to `clientTest` and its sentinel entry), keep Steps 1–2, note the failure in the commit message, and continue to Task 2 — the other 38 relocations do not touch Jewel. `ExplorerTreeStateSpec` then becomes an 18th consolidation target rather than a mover, and Task 9 collapses it to one story in place.

- [ ] **Step 6: Commit**

```bash
git add build.gradle.kts src/test/kotlin/com/breadmoirai/garnet/client src/clientTest/kotlin/com/breadmoirai/garnet/test/ClientTestSentinel.kt
git add -u src/clientTest
git commit -m "test: open src/test to client classes, move ExplorerTreeStateSpec off the client boot"
```

---

### Task 2: Relocate the three specs that need no client state at all

`DockInsetsSpec` and `DockLifecycleSpec` are already plain `StringSpec` — their own doc comments say they touch no render context. `GlfwKeyMapSpec` is a lookup table over `androidx.compose.ui.input.key.Key` constants. All three ride the client boot purely because of where the file sits. `DockLifecycleSpec` is the spec that proves Task 1 Step 2 was necessary: it constructs `Panel(...) { }` with a `@Composable` lambda.

**Files:**
- Create: `src/test/kotlin/com/breadmoirai/garnet/client/ui/dock/DockInsetsTest.kt`
- Create: `src/test/kotlin/com/breadmoirai/garnet/client/ui/dock/DockLifecycleTest.kt`
- Create: `src/test/kotlin/com/breadmoirai/garnet/client/ui/input/GlfwKeyMapTest.kt`
- Delete: `src/clientTest/kotlin/com/breadmoirai/garnet/test/DockInsetsSpec.kt`
- Delete: `src/clientTest/kotlin/com/breadmoirai/garnet/test/DockLifecycleSpec.kt`
- Delete: `src/clientTest/kotlin/com/breadmoirai/garnet/test/GlfwKeyMapSpec.kt`
- Modify: `src/clientTest/kotlin/com/breadmoirai/garnet/test/ClientTestSentinel.kt` (drop all three entries)

**Interfaces:**
- Consumes: the widened test classpath from Task 1.
- Produces: nothing later tasks depend on.

- [ ] **Step 1: Move both files**

Copy each to its new path with two edits only — the `package` line becomes `package com.breadmoirai.garnet.client.ui.dock`, and the class name gains a `Test` suffix in place of `Spec` (`DockInsetsSpec` → `DockInsetsTest`, `DockLifecycleSpec` → `DockLifecycleTest`). Both are already `StringSpec`; the base class, imports and every assertion stay byte-identical. There are no `runOnClient` calls in either file.

While moving `DockInsetsTest`, tidy the two fully-qualified `com.breadmoirai.garnet.ui.dock.DockInsets(...)` references into an import, since the file now lives in that same package name and the qualification reads as noise:

```kotlin
import com.breadmoirai.garnet.ui.dock.DockInsets
```

and then `DockState.insets() shouldBe DockInsets(0, 0, 0, 0)` / `DockInsets(260, 0, 0, 0)`.

Update the class doc comments, which currently say "Runs in the clientTest source set (which can see `client` classes) but touches no render context, so it does not extend ClientSpec." Replace with: "Pure geometry of the dock: region sizes → reserved insets → the shrunk content rect. Runs in `src/test` — no client, no render context." Apply the equivalent correction to `DockLifecycleTest`'s comment, which makes the same "like DockInsetsSpec" claim.

- [ ] **Step 2: Move `GlfwKeyMapSpec`**

To `src/test/kotlin/com/breadmoirai/garnet/client/ui/input/GlfwKeyMapTest.kt`, package `com.breadmoirai.garnet.client.ui.input`, class renamed `GlfwKeyMapTest`. Drop `import com.breadmoirai.garnet.harness.ClientSpec`, add `import io.kotest.core.spec.style.FunSpec`, and change the declaration to `class GlfwKeyMapTest : FunSpec({`. All four test bodies stay byte-identical — they are `glfwKeyToComposeKey(...) shouldBe Key.X` assertions plus `GlfwMods` bit decoding, with no `runOnClient` and no client state. The `androidx.compose.ui.input.key.Key` import resolves because Task 1 put `client`'s compile classpath on `test`.

- [ ] **Step 3: Deregister and delete**

Remove `DockInsetsSpec::class,`, `DockLifecycleSpec::class,` and `GlfwKeyMapSpec::class,` from `ClientTestSentinel.kt`'s `specs` list, and delete all three original files.

- [ ] **Step 4: Compile and run the unit suite**

```bash
cmd.exe /c "gradlew.bat :26.2:testClasses :26.2:clientTestClasses" > /tmp/claude-1000/-mnt-h-Repo-RedstoneSpecs/47cfb0a0-bd70-4b2a-b1d1-a918e56e777a/scratchpad/compile2.log 2>&1
cmd.exe /c "gradlew.bat :26.2:test" > /tmp/claude-1000/-mnt-h-Repo-RedstoneSpecs/47cfb0a0-bd70-4b2a-b1d1-a918e56e777a/scratchpad/test2.log 2>&1
```

Expected: `TEST-com.breadmoirai.garnet.client.ui.dock.DockInsetsTest.xml` 4 tests / 0 failures; `DockLifecycleTest.xml` 4 tests / 0 failures; `TEST-com.breadmoirai.garnet.client.ui.input.GlfwKeyMapTest.xml` 4 tests / 0 failures.

- [ ] **Step 5: Commit**

```bash
git add -A src/test/kotlin/com/breadmoirai/garnet/client src/clientTest
git commit -m "test: move DockInsets, DockLifecycle and GlfwKeyMap specs to src/test"
```

---

### Task 3: Relocate the config and picker specs

Four specs whose seams are already fully injected: they stub every collaborator and touch nothing live. `RootPickerSpec` stubs `picker`, `runner`, `executor`, `sender` and `persist`; `ExplorerStateStoreSpec` and `ModConfigSpec` redirect their config file to a temp dir; `StructureExplorerSpec` mutates `ProjectTreeState` snapshot state.

**Files:**
- Create: `src/test/kotlin/com/breadmoirai/garnet/client/config/ExplorerStateStoreTest.kt`
- Create: `src/test/kotlin/com/breadmoirai/garnet/client/config/ModConfigTest.kt`
- Create: `src/test/kotlin/com/breadmoirai/garnet/client/editor/ui/RootPickerControllerTest.kt`
- Create: `src/test/kotlin/com/breadmoirai/garnet/client/editor/ui/StructureExplorerStatusTest.kt`
- Delete: the four corresponding `src/clientTest/.../*Spec.kt`
- Modify: `ClientTestSentinel.kt` (drop four entries)

**Interfaces:**
- Consumes: the widened test classpath from Task 1.
- Produces: nothing later tasks depend on.

- [ ] **Step 1: Move `ExplorerStateStoreSpec` and `ModConfigSpec`**

Both go to `package com.breadmoirai.garnet.client.config`. Edits: package line, `ClientSpec` → `FunSpec` (add `import io.kotest.core.spec.style.FunSpec`, drop the `ClientSpec` import), class rename `Spec` → `Test`. Neither uses `runOnClient`; every body stays byte-identical, including the `configFileForTest` / `resetConfigFileForTest` try-finally discipline.

- [ ] **Step 2: Move `RootPickerSpec`**

To `src/test/kotlin/com/breadmoirai/garnet/client/editor/ui/RootPickerControllerTest.kt`, package `com.breadmoirai.garnet.client.editor.ui`. Same three edits, class renamed to `RootPickerControllerTest`. Keep the `afterTest { RootPickerController.resetForTest() }` hook — `FunSpec` supports it identically. Keep the comment explaining why the test asserts against `Path.of(picked).toAbsolutePath()` rather than a literal: path resolution is Windows-flavored here and that comment is load-bearing.

- [ ] **Step 3: Move `StructureExplorerSpec`**

To `src/test/kotlin/com/breadmoirai/garnet/client/editor/ui/StructureExplorerStatusTest.kt`. Same edits, plus unwrap its two `runOnClient { }` blocks. Result:

```kotlin
package com.breadmoirai.garnet.client.editor.ui

import com.breadmoirai.garnet.editor.ui.ExplorerTreeState
import com.breadmoirai.garnet.editor.ui.ProjectTreeState
import com.breadmoirai.garnet.editor.network.StructureResultS2C
import com.breadmoirai.garnet.editor.network.StructureAutoSavedS2C
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class StructureExplorerStatusTest : FunSpec({
    test("onStructureResult surfaces the message as Explorer status") {
        ProjectTreeState.reset()
        ExplorerTreeState.reset()
        ProjectTreeState.onStructureResult(
            StructureResultS2C("a/box.nbt", 2, 1, 3, message = "placed a/box.nbt"),
        )
        ProjectTreeState.status shouldBe "placed a/box.nbt"
    }

    test("an auto-save result lands in the Explorer status line") {
        ProjectTreeState.reset()
        ProjectTreeState.onAutoSaved(
            StructureAutoSavedS2C("redstone/clock.nbt", 5, 3, 7, 42, savedAtMillis = 1_700_000_000_000L),
        )
        ProjectTreeState.status shouldBe "auto-saved redstone/clock.nbt (5×3×7, 42 blocks)"
    }
})
```

- [ ] **Step 4: Deregister, delete, compile, run**

Remove `ExplorerStateStoreSpec::class,`, `ModConfigSpec::class,`, `RootPickerSpec::class,` and `StructureExplorerSpec::class,` from `ClientTestSentinel.kt`; delete the four originals.

```bash
cmd.exe /c "gradlew.bat :26.2:testClasses :26.2:clientTestClasses" > /tmp/claude-1000/-mnt-h-Repo-RedstoneSpecs/47cfb0a0-bd70-4b2a-b1d1-a918e56e777a/scratchpad/compile3.log 2>&1
cmd.exe /c "gradlew.bat :26.2:test" > /tmp/claude-1000/-mnt-h-Repo-RedstoneSpecs/47cfb0a0-bd70-4b2a-b1d1-a918e56e777a/scratchpad/test3.log 2>&1
```

Expected across the four new XML reports: 6 + 3 + 4 + 2 = 15 tests, 0 failures.

- [ ] **Step 5: Commit**

```bash
git add -A src/test/kotlin/com/breadmoirai/garnet/client src/clientTest
git commit -m "test: move Explorer state-store, config, root-picker and status specs to src/test"
```

---

### Task 4: Relocate `ExplorerLifecycleSpec`

Held back from Task 3 because it is the one mover that depends on Task 1's spike outcome: it drives `ExplorerTreeState` (Jewel) as well as the config-file seam. If Task 1's gate failed, skip this task entirely and add `ExplorerLifecycleSpec` to Task 9's consolidation list instead.

**Files:**
- Create: `src/test/kotlin/com/breadmoirai/garnet/client/editor/ui/ExplorerLifecycleTest.kt`
- Delete: `src/clientTest/kotlin/com/breadmoirai/garnet/test/ExplorerLifecycleSpec.kt`
- Modify: `ClientTestSentinel.kt`

**Interfaces:**
- Consumes: Task 1's widened classpath and its confirmation that `ExplorerTreeState` constructs headless.
- Produces: nothing later tasks depend on.

- [ ] **Step 1: Move and unwrap**

Package `com.breadmoirai.garnet.client.editor.ui`, `ClientSpec` → `FunSpec`, class `ExplorerLifecycleSpec` → `ExplorerLifecycleTest`, and unwrap each `runOnClient { ... }`. All four tests keep their `ExplorerSessionGate.isSingleplayer = { true/false }` arrangement and their try-finally teardown (`resetConfigFileForTest`, `ExplorerSessionGate.resetForTest`, `SharedSettings.projectRootPath` restore, `ProjectTreeState.reset`, `ExplorerTreeState.reset`, temp-dir delete) unchanged — that teardown is why these tests do not leak into each other.

Keep the class doc comment, correcting only its cross-references: it currently says "Modeled on `ExplorerStateStoreSpec` … and `ExplorerTreeStateSpec`", which are now `ExplorerStateStoreTest` and `ExplorerTreeStateTest`.

- [ ] **Step 2: Deregister, delete, compile, run**

Remove `ExplorerLifecycleSpec::class,` from `ClientTestSentinel.kt`; delete the original.

```bash
cmd.exe /c "gradlew.bat :26.2:testClasses :26.2:clientTestClasses" > /tmp/claude-1000/-mnt-h-Repo-RedstoneSpecs/47cfb0a0-bd70-4b2a-b1d1-a918e56e777a/scratchpad/compile4.log 2>&1
cmd.exe /c "gradlew.bat :26.2:test" > /tmp/claude-1000/-mnt-h-Repo-RedstoneSpecs/47cfb0a0-bd70-4b2a-b1d1-a918e56e777a/scratchpad/test4.log 2>&1
```

Expected: `ExplorerLifecycleTest.xml` shows 4 tests / 0 failures.

- [ ] **Step 3: Commit**

```bash
git add -A src/test/kotlin/com/breadmoirai/garnet/client src/clientTest
git commit -m "test: move ExplorerLifecycleSpec to src/test"
```

---

### Task 5: Extract the payload-only cases out of the two UI specs

`ExplorerContextMenuSpec`'s first five tests and `DockInputSpec`'s two pure tests assert on functions that take arguments and return values. They sit inside client-booting spec files only because their neighbors need a client.

**Files:**
- Create: `src/test/kotlin/com/breadmoirai/garnet/client/editor/ui/ExplorerActionsTest.kt`
- Create: `src/test/kotlin/com/breadmoirai/garnet/client/ui/dock/DockViewportSyncTest.kt`
- Modify: `src/clientTest/kotlin/com/breadmoirai/garnet/test/ExplorerContextMenuSpec.kt` (delete lines 35–71: the `captureSends` helper, the `afterTest` hook and the five payload tests)
- Modify: `src/clientTest/kotlin/com/breadmoirai/garnet/test/DockInputSpec.kt` (delete the `GLFW mouse buttons map…` and `syncDockViewport derives…` tests)

**Interfaces:**
- Consumes: the widened test classpath from Task 1.
- Produces: nothing later tasks depend on. Tasks 8 and 9 edit the same two spec files, so those tasks must run after this one.

- [ ] **Step 1: Write `ExplorerActionsTest`**

```kotlin
package com.breadmoirai.garnet.client.editor.ui

import com.breadmoirai.garnet.editor.ui.ExplorerActions
import com.breadmoirai.garnet.editor.network.CreateFolderC2S
import com.breadmoirai.garnet.editor.network.NewStructureC2S
import com.breadmoirai.garnet.editor.network.RenamePathC2S
import com.breadmoirai.garnet.editor.data.NewNodeKind
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import net.minecraft.network.protocol.common.custom.CustomPacketPayload

/**
 * What the Explorer's create/rename actions put on the wire. Pure: `ExplorerActions.sender` is a
 * settable seam, so nothing here needs a client. The in-scene flow that *reaches* these functions
 * (right-click → New Folder → type → Enter) is `ExplorerUiSpec` in `src/clientTest`.
 */
class ExplorerActionsTest : FunSpec({

    fun captureSends(): MutableList<CustomPacketPayload> {
        val sent = mutableListOf<CustomPacketPayload>()
        ExplorerActions.sender = { sent += it }
        return sent
    }

    afterTest { ExplorerActions.resetForTest() }

    test("creating a folder sends CreateFolderC2S with the target parent") {
        val sent = captureSends()
        ExplorerActions.commitCreate("redstone", NewNodeKind.FOLDER, "clocks") shouldBe null
        sent shouldBe listOf(CreateFolderC2S("redstone", "clocks"))
    }

    test("creating a structure appends .nbt and targets the parent") {
        val sent = captureSends()
        ExplorerActions.commitCreate("", NewNodeKind.STRUCTURE, "gadget") shouldBe null
        sent shouldBe listOf(NewStructureC2S("", "gadget.nbt"))
    }

    test("an invalid name sends nothing and reports why") {
        val sent = captureSends()
        ExplorerActions.commitCreate("redstone", NewNodeKind.FOLDER, "  ").shouldNotBeNull()
        ExplorerActions.commitCreate("redstone", NewNodeKind.FOLDER, "a/b").shouldNotBeNull()
        sent.shouldBeEmpty()
    }

    test("renaming sends RenamePathC2S with a bare new name") {
        val sent = captureSends()
        ExplorerActions.commitRename("redstone/clock.nbt", "ring-clock.nbt") shouldBe null
        sent shouldBe listOf(RenamePathC2S("redstone/clock.nbt", "ring-clock.nbt"))
    }

    test("renaming to a path is rejected") {
        val sent = captureSends()
        ExplorerActions.commitRename("redstone/clock.nbt", "a/b.nbt").shouldNotBeNull()
        sent.shouldBeEmpty()
    }
})
```

- [ ] **Step 2: Write `DockViewportSyncTest`**

`syncDockViewport` reads `DockState` and writes `ViewportState.active` / `ComposeOverlay.enabled` — no GLFW, no window, as its own test name asserts. The `runOnClient` wrappers in the original exist only because the surrounding spec was a `ClientSpec`.

```kotlin
package com.breadmoirai.garnet.client.ui.dock

import com.breadmoirai.garnet.ui.compose.ComposeOverlay
import com.breadmoirai.garnet.ui.dock.DockRegion
import com.breadmoirai.garnet.ui.dock.DockState
import com.breadmoirai.garnet.ui.input.DockInputRouter
import com.breadmoirai.garnet.ui.input.glfwMouseButtonToPointerButton
import com.breadmoirai.garnet.ui.input.syncDockViewport
import com.breadmoirai.garnet.ui.viewport.ViewportState
import androidx.compose.ui.input.pointer.PointerButton
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import org.lwjgl.glfw.GLFW

/**
 * The two pieces of dock input handling that are pure lookups over state: the GLFW→Compose button
 * table, and the rule deciding when the dock takes over the viewport. Routing an actual event into
 * a live ComposeScene is `DockInputSpec` in `src/clientTest`.
 */
class DockViewportSyncTest : FunSpec({

    afterTest {
        DockInputRouter.clearFocus()
        DockState.reset()
        ViewportState.active = false
        ComposeOverlay.enabled = false
    }

    test("GLFW mouse buttons map to Compose pointer buttons") {
        glfwMouseButtonToPointerButton(GLFW.GLFW_MOUSE_BUTTON_LEFT) shouldBe PointerButton.Primary
        glfwMouseButtonToPointerButton(GLFW.GLFW_MOUSE_BUTTON_RIGHT) shouldBe PointerButton.Secondary
        glfwMouseButtonToPointerButton(GLFW.GLFW_MOUSE_BUTTON_MIDDLE) shouldBe PointerButton.Tertiary
        glfwMouseButtonToPointerButton(7) shouldBe null
    }

    test("syncDockViewport derives active/enabled from DockState, no GLFW involved") {
        DockState.reset()
        ViewportState.active = false
        ComposeOverlay.enabled = false

        // Nothing visible/focused: vanilla stays vanilla.
        syncDockViewport()
        ViewportState.active.shouldBeFalse()
        ComposeOverlay.enabled.shouldBeFalse()

        // LEFT becomes visible: both flags flip on.
        DockState.setVisible(DockRegion.LEFT, true)
        syncDockViewport()
        ViewportState.active.shouldBeTrue()
        ComposeOverlay.enabled.shouldBeTrue()

        // LEFT hidden again: both flags revert to vanilla.
        DockState.setVisible(DockRegion.LEFT, false)
        syncDockViewport()
        ViewportState.active.shouldBeFalse()
        ComposeOverlay.enabled.shouldBeFalse()

        // Focus alone (no visible region) also counts as "something to show".
        DockState.reset()
        DockInputRouter.focus(DockRegion.LEFT)
        syncDockViewport()
        ViewportState.active.shouldBeTrue()
        ComposeOverlay.enabled.shouldBeTrue()
    }
})
```

Verify the import paths for `glfwMouseButtonToPointerButton` and `syncDockViewport` against the originals in `DockInputSpec.kt` before compiling — copy whatever that file imports rather than trusting the paths above.

- [ ] **Step 3: Delete the migrated cases from their old homes**

From `ExplorerContextMenuSpec.kt`, delete the `captureSends` helper, the `afterTest { ExplorerActions.resetForTest() }` hook and the five tests spanning lines 35–71. Leave the second `capture(name: String)` helper (the screenshot one) and everything from `"a right-click on a tree row opens the menu…"` onward. Prune the imports that only those five tests used: `CreateFolderC2S`, `NewStructureC2S`, `RenamePathC2S`, `NewNodeKind`, `CustomPacketPayload`, `shouldBeEmpty`, `shouldNotBeNull` — but only if no surviving test still references them; the compiler will flag unused imports as warnings, not errors, so check each by grep before removing.

From `DockInputSpec.kt`, delete the `"GLFW mouse buttons map to Compose pointer buttons"` and `"syncDockViewport derives active/enabled from DockState, no GLFW involved"` tests, and prune imports the same way.

- [ ] **Step 4: Compile and run**

```bash
cmd.exe /c "gradlew.bat :26.2:testClasses :26.2:clientTestClasses" > /tmp/claude-1000/-mnt-h-Repo-RedstoneSpecs/47cfb0a0-bd70-4b2a-b1d1-a918e56e777a/scratchpad/compile5.log 2>&1
cmd.exe /c "gradlew.bat :26.2:test" > /tmp/claude-1000/-mnt-h-Repo-RedstoneSpecs/47cfb0a0-bd70-4b2a-b1d1-a918e56e777a/scratchpad/test5.log 2>&1
```

Expected: `ExplorerActionsTest.xml` 5 tests / 0 failures, `DockViewportSyncTest.xml` 2 tests / 0 failures, and `clientTestClasses` still compiles.

- [ ] **Step 5: Commit**

```bash
git add -A src/test/kotlin/com/breadmoirai/garnet/client src/clientTest
git commit -m "test: extract payload-only cases from ExplorerContextMenu and DockInput specs"
```

---

### Task 6: Controller checkpoint — first `runClientTest` since the trim began

Not an implementation task. Five tasks have removed 55 cases from a suite that has not been run since. Run it once now, before consolidation starts, so that any breakage is attributable to relocation rather than to the rewrites in Tasks 7–9.

**Files:** none.

**Interfaces:**
- Consumes: Tasks 1–5.
- Produces: a known-green baseline for the remaining 27 cases.

- [ ] **Step 1: Run the client suite (controller only, foreground, 600s cap)**

```bash
cmd.exe /c "gradlew.bat :26.2:runClientTest" > /tmp/claude-1000/-mnt-h-Repo-RedstoneSpecs/47cfb0a0-bd70-4b2a-b1d1-a918e56e777a/scratchpad/clienttest-baseline.log 2>&1
```

The run hangs after printing its summary — poll the log for the Kotest summary line, then kill the process. The clientTest XML reports are always empty; the summary in the log is the result.

Expected: the 10 still-registered specs pass — `RunGarnetSpecSmokeTest`, `ViewportCompositeSpec`, `ViewportPickingSpec`, `ViewportCursorMappingSpec`, `CursorFocusToggleSpec`, `DockRenderSpec`, `DockInputSpec`, `ProjectExplorerSpec`, `JewelExplorerSpec`, `ExplorerContextMenuSpec`. Record the wall-clock time — it is the number this whole exercise is judged against.

- [ ] **Step 2: If anything fails, fix it before continuing**

A failure here is almost certainly cross-spec state leakage: several relocated specs used to run inside the same client and reset shared singletons (`DockState`, `ProjectTreeState`, `ExplorerTreeState`, `ExplorerActions`, `RootPickerController`) on their way through. A spec that silently depended on a neighbor's reset will now start dirty. Fix by adding the missing reset to the failing spec's own arrangement, not by restoring the moved spec.

- [ ] **Step 3: Commit any fixes**

```bash
git commit -am "test: reset state a relocated neighbor used to clear"
```

---

### Task 7: Consolidate the three viewport specs into one story

`ViewportCompositeSpec`, `ViewportPickingSpec` and `ViewportCursorMappingSpec` each perform the same expensive setup — close the screen, enable the shrink, drive `garnet$updateScaledFramebuffer`, wait ~10 ticks, then tear it all down. Three setups become one.

**Files:**
- Create: `src/clientTest/kotlin/com/breadmoirai/garnet/test/ViewportSpec.kt`
- Delete: `ViewportCompositeSpec.kt`, `ViewportPickingSpec.kt`, `ViewportCursorMappingSpec.kt`
- Modify: `ClientTestSentinel.kt`

**Interfaces:**
- Consumes: nothing from prior tasks beyond a green baseline.
- Produces: `ViewportSpec`, registered in the sentinel.

- [ ] **Step 1: Write the merged story**

One test named `"the shrunk viewport composites, picks, and maps the cursor correctly"`, structured as four phases in a single arrange/act/assert sweep:

1. **Baseline (effect off).** `closeClientScreen()`, `waitClientTicks(2)`, assert `ViewportState.active` is false, and — from `ViewportPickingSpec` — capture the block `Minecraft.hitResult` picks with the shrink off. Copy that spec's existing hit-result reading code verbatim; it is the half of the picking assertion that must be taken before the shrink.
2. **Enable.** Set a visible LEFT region of size 300 (from `ViewportCursorMappingSpec`'s arrangement — the cursor assertion needs a non-zero `frameX`, and the composite proof needs a reserved edge, so one arrangement serves both), set `ViewportState.active = true`, drive `garnet$updateScaledFramebuffer(true)`, `waitClientTicks(10)`.
3. **Assert, in this order** — cheapest first, so a failure surfaces before the slow capture:
   - cursor mapping: `ViewportCursorMappingSpec`'s body verbatim from `val xposField = ...` through the two `shouldBe` assertions, minus its own arrange/teardown blocks.
   - picking: assert the shrink-on `hitResult` block matches the baseline one captured in phase 1.
   - composite proof: `ViewportCompositeSpec`'s `compositeCaptureRequest` poll and `Files.exists(...).shouldBeTrue()`.
4. **Teardown.** `ViewportState.active = false`, `DockState.reset()`, drive the recompute, `waitClientTicks(4)`, assert `ViewportState.active` is false.

Drop the three reference screenshots `ViewportCompositeSpec` takes via `takeClientScreenshot` (`viewport_shrink_off`, `viewport_shrink_composited`, `viewport_shrink_restored`). Its own comments say the main-target captures "can NOT show our composite" and exist for eyeball reference; only `viewport_shrink_composite_proof.png` carries an assertion. Keep that one.

Carry over the class doc comments from all three specs, merged — in particular `ViewportCursorMappingSpec`'s long explanation of the `getScaledXPos` offset bug, which is the reason that assertion exists and is not reconstructible from the code.

- [ ] **Step 2: Register, delete, compile**

Replace `ViewportCompositeSpec::class,`, `ViewportPickingSpec::class,` and `ViewportCursorMappingSpec::class,` in `ClientTestSentinel.kt` with a single `ViewportSpec::class,`. Delete the three originals.

```bash
cmd.exe /c "gradlew.bat :26.2:clientTestClasses" > /tmp/claude-1000/-mnt-h-Repo-RedstoneSpecs/47cfb0a0-bd70-4b2a-b1d1-a918e56e777a/scratchpad/compile7.log 2>&1
```

- [ ] **Step 3: Controller runs the client suite**

```bash
cmd.exe /c "gradlew.bat :26.2:runClientTest" > /tmp/claude-1000/-mnt-h-Repo-RedstoneSpecs/47cfb0a0-bd70-4b2a-b1d1-a918e56e777a/scratchpad/clienttest7.log 2>&1
```

Expected: `ViewportSpec` passes, and `versions/26.2/run/screenshots/viewport_shrink_composite_proof.png` exists and shows the world in a centered sub-rect with a solid left edge. Inspect it — this spec's value is visual and the assertion only proves a file appeared.

- [ ] **Step 4: Commit**

```bash
git add -A src/clientTest
git commit -m "test: merge the three viewport specs into one ViewportSpec story"
```

---

### Task 8: Consolidate `DockInputSpec` and delete `ProjectExplorerSpec`

After Task 5, `DockInputSpec` holds six client-requiring cases, each mounting a probe panel and tearing it down. They become one story that mounts once. Separately, `ProjectExplorerSpec` is deleted: its two cases are near-duplicates that both assert only "the header painted and no menu leaked", at the cost of two screenshot captures with 6-second polling deadlines.

**Files:**
- Modify: `src/clientTest/kotlin/com/breadmoirai/garnet/test/DockInputSpec.kt`
- Delete: `src/clientTest/kotlin/com/breadmoirai/garnet/test/ProjectExplorerSpec.kt`
- Modify: `ClientTestSentinel.kt`

**Interfaces:**
- Consumes: Task 5 (which already removed two cases from `DockInputSpec`).
- Produces: `JewelExplorerSpec` inherits `ProjectExplorerSpec`'s leaked-popup guard — Task 9 depends on this task having removed it here.

- [ ] **Step 1: Merge `DockInputSpec` into one story**

One test, `"pointer, key and char events route into a focused dock panel and back out"`. Mount a single probe `Panel` carrying both a `pointerInput` collector and a focusable text field (the existing specs already build both — combine them into one composable rather than mounting twice), then in sequence:

1. a secondary press reaches the scene as `PointerButton.Secondary`
2. a routed primary click reaches the focused LEFT panel
3. a non-ESC key press reaches the focused widget
4. a typed char lands in the text field via `onGlfwChar`
5. ESC drops dock focus, and `onGlfwKey` reports only ESC-press as consumed
6. an uncaptured ESC drops nothing

Take each assertion verbatim from the corresponding existing test — the probe wiring differs between them only in which collector the panel carries.

Keep the trailing `runOnClient { DockInputRouter.clearFocus(); DockState.reset() }` + `waitClientTicks(2)` teardown; other specs in the suite depend on the dock being clean.

- [ ] **Step 2: Delete `ProjectExplorerSpec`**

Delete the file and remove `ProjectExplorerSpec::class,` from `ClientTestSentinel.kt`. Before deleting, note the two assertions its cases share, since Task 9 re-homes them:

```kotlin
val header = PanelPixelProbe.headerRegionDiffCount(shot)
val menu = PanelPixelProbe.menuRegionDiffCount(shot)
header shouldBeGreaterThan 8
menu shouldBeLessThan PanelPixelProbe.MENU_CLOSED_MAX
```

`PanelPixelProbe.kt` stays — Task 9 uses it.

- [ ] **Step 3: Compile, then controller runs the suite**

```bash
cmd.exe /c "gradlew.bat :26.2:clientTestClasses" > /tmp/claude-1000/-mnt-h-Repo-RedstoneSpecs/47cfb0a0-bd70-4b2a-b1d1-a918e56e777a/scratchpad/compile8.log 2>&1
cmd.exe /c "gradlew.bat :26.2:runClientTest" > /tmp/claude-1000/-mnt-h-Repo-RedstoneSpecs/47cfb0a0-bd70-4b2a-b1d1-a918e56e777a/scratchpad/clienttest8.log 2>&1
```

Expected: `DockInputSpec` passes as a single test; no `ProjectExplorerSpec` in the summary.

- [ ] **Step 4: Commit**

```bash
git add -A src/clientTest
git commit -m "test: collapse DockInputSpec to one routing story, drop ProjectExplorerSpec"
```

---

### Task 9: Consolidate the two Explorer UI specs

`JewelExplorerSpec` (7 cases) and the post-Task-5 remainder of `ExplorerContextMenuSpec` (6 cases) each mount the Explorer panel repeatedly. Each becomes one story with a single mount.

**Files:**
- Modify: `src/clientTest/kotlin/com/breadmoirai/garnet/test/JewelExplorerSpec.kt`
- Create: `src/clientTest/kotlin/com/breadmoirai/garnet/test/ExplorerUiSpec.kt`
- Delete: `src/clientTest/kotlin/com/breadmoirai/garnet/test/ExplorerContextMenuSpec.kt`
- Modify: `ClientTestSentinel.kt`

**Interfaces:**
- Consumes: Task 5 (payload cases already gone from `ExplorerContextMenuSpec`), Task 8 (`ProjectExplorerSpec` gone, its assertions to be re-homed here).
- Produces: the final `clientTest` roster.

- [ ] **Step 1: Collapse `JewelExplorerSpec` to one story**

One test, `"the Explorer tree renders, selects, navigates, and its kebab menu never outlives a remount"`. Sequence, each step's assertions taken verbatim from the corresponding existing case:

1. mount the panel with a nested tree snapshot, render, assert the LazyTree painted with chevrons and file icons
2. **fold in `ProjectExplorerSpec`'s guard here** — take the capture, assert `headerRegionDiffCount(shot) shouldBeGreaterThan 8` and `menuRegionDiffCount(shot) shouldBeLessThan PanelPixelProbe.MENU_CLOSED_MAX`. This is where the leaked-popup regression first showed itself, and this mount is the fresh-mount scenario that exposed it.
3. a routed pointer click selects a tree row
4. arrow keys navigate the tree end-to-end through the key path
5. the kebab menu opens in-scene over the Blaze3D FBO
6. ESC closes the open kebab before it drops dock focus
7. reopen the kebab, then unmount and remount the panel — assert it did not survive
8. reopen the kebab, then hide and re-show the LEFT region — assert it did not survive

Steps 7 and 8 are the two distinct teardown paths (`panel unmount` vs `region hidden`) and both must stay; they are the same assertion against different lifecycle events, and the original spec kept them separate for that reason.

- [ ] **Step 2: Write `ExplorerUiSpec` from what remains of `ExplorerContextMenuSpec`**

One test, `"right-click a nested folder, create through the inline field, and the payload reaches the sender"`. Sequence:

1. right-click a tree row — the menu opens, painted at the click point
2. hover moves between menu rows, and the row under the cursor is the one that acts
3. `New > Folder` on a nested folder auto-expands it and targets it, not the root
4. type an invalid name — the inline field stays open for correction and nothing is sent
5. correct it, press Enter — `CreateFolderC2S` with the nested parent reaches `ExplorerActions.sender`
6. reopen the field, press Escape — nothing is sent and no stale text lingers

This ordering merges the six surviving cases into one continuous user journey without dropping an assertion: the invalid-name case becomes a step *within* the create flow rather than a separate mount, which is what makes the merge worth doing.

Rename the file and class to `ExplorerUiSpec`; keep the `capture(name: String)` screenshot helper and the `afterTest { ExplorerActions.resetForTest() }` hook.

- [ ] **Step 3: Register, delete, compile**

In `ClientTestSentinel.kt`, replace `ExplorerContextMenuSpec::class,` with `ExplorerUiSpec::class,`. `JewelExplorerSpec::class,` stays as-is.

```bash
cmd.exe /c "gradlew.bat :26.2:clientTestClasses" > /tmp/claude-1000/-mnt-h-Repo-RedstoneSpecs/47cfb0a0-bd70-4b2a-b1d1-a918e56e777a/scratchpad/compile9.log 2>&1
```

- [ ] **Step 4: Controller runs the suite**

```bash
cmd.exe /c "gradlew.bat :26.2:runClientTest" > /tmp/claude-1000/-mnt-h-Repo-RedstoneSpecs/47cfb0a0-bd70-4b2a-b1d1-a918e56e777a/scratchpad/clienttest9.log 2>&1
```

Expected: the sentinel's list is now exactly `RunGarnetSpecSmokeTest`, `ViewportSpec`, `DockRenderSpec`, `DockInputSpec`, `CursorFocusToggleSpec`, `JewelExplorerSpec`, `ExplorerUiSpec` — 7 specs, 7 tests. Compare wall clock against the Task 6 baseline.

- [ ] **Step 5: Commit**

```bash
git add -A src/clientTest
git commit -m "test: collapse the Explorer UI specs into one story each"
```

---

### Task 10: Documentation and full verification

Per CLAUDE.md, the docs audit is part of the task, not a follow-up.

**Files:**
- Modify: `docs/gametest/unit-vs-gametest-split.md`
- Modify: `docs/gametest/INDEX.md`
- Modify: `docs/gametest/client-test-threading.md`
- Modify: `docs/architecture/shrink-viewport-compose-model.md`, `docs/persistence/explorer-session-state.md`, `docs/ui/jewel-widget-layer.md`, `docs/gametest/screenshots-for-debug-and-regression.md`, `docs/use-cases/redstone-project.md`, `docs/use-cases/structure-lifecycle.md`

**Interfaces:**
- Consumes: Tasks 1–9.
- Produces: docs that match the code.

- [ ] **Step 1: Rewrite the decision rule**

In `docs/gametest/unit-vs-gametest-split.md`, replace the "Decision rule" section's third sentence — currently "If it requires a real client — screens, widgets, keybinds, payload round-trips driven from the client — it belongs in `src/clientTest/`" — with the sharper rule:

> If it needs a live `Minecraft` instance, a GL context, or GLFW — pixel probes, `Minecraft.hitResult`, `MouseHandler` state, routing events into a running `ComposeScene` — it belongs in `src/clientTest/`. Everything else belongs in `src/test/`, **including Compose snapshot state and `@Composable` code**: since 2026-08-03 the `test` source set carries `client`'s compile classpath and runs the Compose compiler plugin.

Update the `src/test/` bullet in the source-set list and the "Where the contracts actually live" lists to name the relocated tests (`ExplorerTreeStateTest`, `ExplorerStateStoreTest`, `ModConfigTest`, `RootPickerControllerTest`, `StructureExplorerStatusTest`, `ExplorerLifecycleTest`, `DockInsetsTest`, `DockLifecycleTest`, `ExplorerActionsTest`, `DockViewportSyncTest`) and the seven surviving clientTest specs. Add a short section recording *why* the wall came down and what it cost, so the next person does not re-erect it:

> **Why `src/test` can see `client`.** It could not until 2026-08-03, which stranded ~55 pure-JVM assertions inside a full client boot — Gson round-trips, a GLFW→Compose lookup table, and a picker spec that stubbed every seam it had. The fix was two lines of `build.gradle.kts`: the client source set on the test classpath, and `compileTestKotlin` removed from the Compose-plugin strip list. The strip remains for `main`, `gametest` and `testSupport`, which is what keeps Compose off the server jar.

- [ ] **Step 2: Update the remaining docs**

For each of the seven other files, grep for the old spec names and update. Most are one-line mentions.

```bash
grep -rn "ExplorerTreeStateSpec\|ExplorerStateStoreSpec\|DockInsetsSpec\|DockLifecycleSpec\|ModConfigSpec\|GlfwKeyMapSpec\|RootPickerSpec\|StructureExplorerSpec\|ExplorerLifecycleSpec\|ProjectExplorerSpec\|ViewportCompositeSpec\|ViewportPickingSpec\|ViewportCursorMappingSpec\|ExplorerContextMenuSpec" docs/ | grep -v superpowers
```

Expected after the edits: zero hits outside `docs/superpowers/`. Where a doc cites a deleted spec as *evidence* for a claim (for example `docs/architecture/shrink-viewport-compose-model.md` pointing at `ViewportCursorMappingSpec` for the cursor-offset fix), repoint it at `ViewportSpec` rather than deleting the sentence — the claim is still true and still tested.

In `docs/gametest/client-test-threading.md`, add a line noting that `ClientSpec` and `runOnClient` are now reserved for specs that genuinely drive the client, and that a test reaching for them should first ask whether it belongs in `src/test`.

In `docs/gametest/INDEX.md`, update any summary line whose article description references the moved specs.

- [ ] **Step 3: Full five-source-set compile**

```bash
cmd.exe /c "gradlew.bat :26.2:clientClasses :26.2:classes :26.2:gametestClasses :26.2:clientTestClasses :26.2:testClasses" > /tmp/claude-1000/-mnt-h-Repo-RedstoneSpecs/47cfb0a0-bd70-4b2a-b1d1-a918e56e777a/scratchpad/compile-final.log 2>&1
```

- [ ] **Step 4: All three suites green**

```bash
cmd.exe /c "gradlew.bat :26.2:test" > /tmp/claude-1000/-mnt-h-Repo-RedstoneSpecs/47cfb0a0-bd70-4b2a-b1d1-a918e56e777a/scratchpad/test-final.log 2>&1
cmd.exe /c "gradlew.bat :26.2:runGameTest" > /tmp/claude-1000/-mnt-h-Repo-RedstoneSpecs/47cfb0a0-bd70-4b2a-b1d1-a918e56e777a/scratchpad/gametest-final.log 2>&1
cmd.exe /c "gradlew.bat :26.2:runClientTest" > /tmp/claude-1000/-mnt-h-Repo-RedstoneSpecs/47cfb0a0-bd70-4b2a-b1d1-a918e56e777a/scratchpad/clienttest-final.log 2>&1
```

`runGameTest` is not incidental: Task 1 edited shared source-set wiring and the Compose-plugin strip list, both of which the gametest compilation depends on. It must still be green.

Expected: `test` shows its prior count plus 55 relocated cases, 0 failures. `runGameTest` unchanged. `runClientTest` shows 7 specs / 7 tests and a wall clock materially below the Task 6 baseline — report the before and after numbers.

- [ ] **Step 5: Commit**

```bash
git add -A docs
git commit -m "docs: record the new unit/clientTest split and repoint moved spec citations"
```

---

## Self-Review

**Spec coverage.** Every section of the design maps to a task: the build change → Task 1; the destination layout → Tasks 1–5; the seven survivors → Tasks 7–9 (`DockRenderSpec` and `CursorFocusToggleSpec` are already single-story and need no work, which is why no task touches them); `ProjectExplorerSpec`'s deletion and the re-homing of its guard → Tasks 8 and 9; risk/verification → Task 1's gate and Task 10; documentation → Task 10.

**Case arithmetic.** Relocated: 17 (Task 1) + 12 (Task 2) + 15 (Task 3) + 4 (Task 4) + 7 (Task 5) = **55** cases into `src/test`, matching the spec's headline figure. The first pass of this plan had no task for `GlfwKeyMapSpec` and came to 51; that gap was found by this review and folded into Task 2. Deleted as duplicate: 2 (`ProjectExplorerSpec`), plus 3 of `ViewportCompositeSpec`'s unasserted reference screenshots (not test cases). Consolidated in place: the remaining 25 client-requiring cases → 7 tests. 55 + 2 + 25 = 82. Reconciles with the spec's inventory.

**Type consistency.** `PanelPixelProbe.headerRegionDiffCount` / `menuRegionDiffCount` / `MENU_CLOSED_MAX` are used identically in Tasks 8 and 9. `ExplorerActions.commitCreate` / `commitRename` / `sender` / `resetForTest` match their current signatures. `ExplorerSessionGate.isSingleplayer` / `resetForTest` and `ExplorerStateStore.configFileForTest` / `resetConfigFileForTest` match. `glfwMouseButtonToPointerButton` and `syncDockViewport` import paths are flagged for verification against the original file in Task 5 Step 2 rather than asserted.

**Ordering constraints.** Task 5 must precede Tasks 8 and 9 (all three edit `DockInputSpec.kt` and `ExplorerContextMenuSpec.kt`). Task 8 must precede Task 9 (Task 9 re-homes assertions Task 8 removes). Task 4 is conditional on Task 1's gate.
