# Plan A — Promote testing package to main

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move the `testing` package from `src/testBridge/` to `src/main/`, promote Kotest engine + assertions to the shipped `main` configuration, and rename `ServerTestSpec` to `GarnetTestSpec` so the same base class is available to shipped specs and to all dev test source sets.

**Architecture:** No behavior change. The `testBridge` source set goes away; its contents live in `main`. Configurations that previously inherited `testBridgeImplementation` now inherit nothing extra (Kotest is on `main`'s classpath). All existing test specs continue to work with one base-class rename.

**Tech Stack:** Kotlin, Gradle (Loom + Stonecutter), Kotest 5.9.1, Fabric Language Kotlin, kotlin-scripting-jvm-host (already on `main`).

**Spec reference:** `docs/superpowers/specs/2026-05-07-garnet-kotest-bridge-design.md` §"Module shape", §"Convergence with dev-side tests".

**Prerequisite verification:** `KtsSpecLoader.kt:22-24` and `SpecScript.kt:21` already pin the Fabric "knot" classloader for scripting evaluation. Spec Plan #1 (scripting-host spike) is therefore already discharged; Task 1 below verifies it under the runtime conditions Plan D will need.

---

## File structure (after this plan)

**Moved from `src/testBridge/kotlin/com/breadmoirai/garnet/testing/` to `src/main/kotlin/com/breadmoirai/garnet/testing/`:**

- `GarnetTestSpec.kt` (renamed from `ServerTestSpec.kt`) — Kotest `FunSpec` subclass with server-thread dispatcher.
- `core/Dispatchers.kt` — `McDispatchers.Server`.
- `core/Lifecycle.kt` — server lifecycle hooks.
- `core/Ticks.kt` — `awaitTicks`, `awaitTickEnd`, `awaitTickWhere`.
- `core/ClientContextHolder.kt` — client-side context (still needed for `clientTest`).
- `server/Suspending.kt` — `onServer { }`.
- `server/Structures.kt` — `spawnStructure`, `StructureHandle`, `StructureGrid`.
- `launcher/KotestLauncher.kt` — `launchKotest(...)`.
- `launcher/ResultCollector.kt` — Kotest `TestListener` that aggregates pass/fail.

**Modified:**
- `build.gradle.kts` — remove `testBridge` source set + configurations; promote Kotest deps to `implementation`; remove gametest/clientTest extension of testBridge.
- `src/test/kotlin/com/breadmoirai/garnet/testing/server/SuspendingTest.kt` — package-only update.
- `src/clientTest/kotlin/com/breadmoirai/garnet/test/SpecTestContext.kt` — references move from `testBridge` package to `main` package.
- `src/clientTest/kotlin/com/breadmoirai/garnet/test/ClientTestSentinel.kt` — same.
- `src/gametest/kotlin/com/breadmoirai/garnet/test/GametestSentinel.kt` — same.
- `src/gametest/kotlin/com/breadmoirai/garnet/test/SmokeSpec.kt` — extends `GarnetTestSpec` (was `ServerTestSpec`).

**Deleted:**
- `src/testBridge/` source set entirely (after migration).
- `docs/build/gametest-sourceset-split-wiring.md` — needs revision (the testBridge-specific wiring it describes is the thing being removed).

---

## Task 1: Verify scripting host works in current main classpath

**Files:**
- Test: `src/test/kotlin/com/breadmoirai/garnet/data/serial/KtsSpecLoaderRoundtripTest.kt` *(new)*

- [ ] **Step 1: Write the failing test**

```kotlin
package com.breadmoirai.garnet.data.serial

import com.breadmoirai.garnet.data.GarnetSpec
import com.breadmoirai.garnet.data.dsl.garnetSpec
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

class KtsSpecLoaderRoundtripTest : FunSpec({
    test("emit then load yields equivalent GarnetSpec") {
        val original = garnetSpec("roundtrip-1") {
            bounds(4, 3, 2)
            lifespan = 8
        }
        val source = KtsSpecEmitter.emit(original)
        val loaded = KtsSpecLoader.loadString(source, name = "roundtrip-1.spec.kts")
        loaded.shouldBeInstanceOf<GarnetSpec>()
        loaded.id shouldBe "roundtrip-1"
        loaded.lifespan shouldBe 8
        loaded.bounds shouldBe original.bounds
    }
})
```

- [ ] **Step 2: Run test**

Run: `cmd.exe /c "./gradlew.bat :26.1:test --tests com.breadmoirai.garnet.data.serial.KtsSpecLoaderRoundtripTest"`
Expected: PASS. (If it fails, investigate before proceeding — every later plan assumes script eval works.)

- [ ] **Step 3: Commit**

```bash
git add src/test/kotlin/com/breadmoirai/garnet/data/serial/KtsSpecLoaderRoundtripTest.kt
git commit -m "test(serial): add KtsSpecLoader emit↔load roundtrip"
```

---

## Task 2: Move `testing/core` from testBridge to main

**Files:**
- Move: `src/testBridge/kotlin/com/breadmoirai/garnet/testing/core/Dispatchers.kt` → `src/main/kotlin/com/breadmoirai/garnet/testing/core/Dispatchers.kt`
- Move: `src/testBridge/kotlin/com/breadmoirai/garnet/testing/core/Lifecycle.kt` → `src/main/kotlin/com/breadmoirai/garnet/testing/core/Lifecycle.kt`
- Move: `src/testBridge/kotlin/com/breadmoirai/garnet/testing/core/Ticks.kt` → `src/main/kotlin/com/breadmoirai/garnet/testing/core/Ticks.kt`
- Move: `src/testBridge/kotlin/com/breadmoirai/garnet/testing/core/ClientContextHolder.kt` → `src/main/kotlin/com/breadmoirai/garnet/testing/core/ClientContextHolder.kt`

- [ ] **Step 1: Move the four files via git mv**

```bash
mkdir -p src/main/kotlin/com/breadmoirai/garnet/testing/core
git mv src/testBridge/kotlin/com/breadmoirai/garnet/testing/core/Dispatchers.kt        src/main/kotlin/com/breadmoirai/garnet/testing/core/
git mv src/testBridge/kotlin/com/breadmoirai/garnet/testing/core/Lifecycle.kt          src/main/kotlin/com/breadmoirai/garnet/testing/core/
git mv src/testBridge/kotlin/com/breadmoirai/garnet/testing/core/Ticks.kt              src/main/kotlin/com/breadmoirai/garnet/testing/core/
git mv src/testBridge/kotlin/com/breadmoirai/garnet/testing/core/ClientContextHolder.kt src/main/kotlin/com/breadmoirai/garnet/testing/core/
```

- [ ] **Step 2: Verify package declarations are unchanged**

The files already declare `package com.breadmoirai.garnet.testing.core` — no edit needed.

Run: `grep -n "^package" src/main/kotlin/com/breadmoirai/garnet/testing/core/*.kt`
Expected: each prints `package com.breadmoirai.garnet.testing.core`.

- [ ] **Step 3: Don't compile yet** — testBridge still references these via classpath; we'll fix after the rest of the move. Defer commit to Task 6.

---

## Task 3: Move `testing/server` from testBridge to main

**Files:**
- Move: `src/testBridge/kotlin/com/breadmoirai/garnet/testing/server/Suspending.kt` → `src/main/...`
- Move: `src/testBridge/kotlin/com/breadmoirai/garnet/testing/server/Structures.kt` → `src/main/...`

- [ ] **Step 1: Move the files**

```bash
mkdir -p src/main/kotlin/com/breadmoirai/garnet/testing/server
git mv src/testBridge/kotlin/com/breadmoirai/garnet/testing/server/Suspending.kt  src/main/kotlin/com/breadmoirai/garnet/testing/server/
git mv src/testBridge/kotlin/com/breadmoirai/garnet/testing/server/Structures.kt  src/main/kotlin/com/breadmoirai/garnet/testing/server/
```

- [ ] **Step 2: Defer commit to Task 6.**

---

## Task 4: Move `testing/launcher` from testBridge to main

**Files:**
- Move: `src/testBridge/kotlin/com/breadmoirai/garnet/testing/launcher/KotestLauncher.kt` → `src/main/...`
- Move: `src/testBridge/kotlin/com/breadmoirai/garnet/testing/launcher/ResultCollector.kt` → `src/main/...`

- [ ] **Step 1: Move the files**

```bash
mkdir -p src/main/kotlin/com/breadmoirai/garnet/testing/launcher
git mv src/testBridge/kotlin/com/breadmoirai/garnet/testing/launcher/KotestLauncher.kt  src/main/kotlin/com/breadmoirai/garnet/testing/launcher/
git mv src/testBridge/kotlin/com/breadmoirai/garnet/testing/launcher/ResultCollector.kt src/main/kotlin/com/breadmoirai/garnet/testing/launcher/
```

- [ ] **Step 2: Defer commit to Task 6.**

---

## Task 5: Rename `ServerTestSpec` to `GarnetTestSpec` and move to main

**Files:**
- Move + rename: `src/testBridge/kotlin/com/breadmoirai/garnet/testing/ServerTestSpec.kt` → `src/main/kotlin/com/breadmoirai/garnet/testing/GarnetTestSpec.kt`

- [ ] **Step 1: Move and rename**

```bash
git mv src/testBridge/kotlin/com/breadmoirai/garnet/testing/ServerTestSpec.kt  src/main/kotlin/com/breadmoirai/garnet/testing/GarnetTestSpec.kt
```

- [ ] **Step 2: Edit the file — rename the class**

Edit `src/main/kotlin/com/breadmoirai/garnet/testing/GarnetTestSpec.kt`. Replace every occurrence of `ServerTestSpec` with `GarnetTestSpec`. Final file content:

```kotlin
@file:OptIn(io.kotest.common.ExperimentalKotest::class)

package com.breadmoirai.garnet.testing

import com.breadmoirai.garnet.testing.core.McDispatchers
import io.kotest.core.concurrency.CoroutineDispatcherFactory
import io.kotest.core.spec.style.FunSpec
import io.kotest.core.test.TestCase
import kotlinx.coroutines.withContext

/**
 * Base class for specs whose test bodies and lifecycle hooks run on the server thread.
 * Used by both shipped `.spec.kts` files (loaded at runtime) and dev tests in `src/gametest/`,
 * `src/clientTest/`, and `src/test/`.
 */
abstract class GarnetTestSpec(body: GarnetTestSpec.() -> Unit = {}) : FunSpec() {
    init {
        coroutineDispatcherFactory = object : CoroutineDispatcherFactory {
            override suspend fun <T> withDispatcher(testCase: TestCase, block: suspend () -> T): T =
                withContext(McDispatchers.Server) { block() }

            override fun close() = Unit
        }
        body()
    }
}
```

- [ ] **Step 3: Defer commit to Task 6.**

---

## Task 6: Update Gradle to remove testBridge and promote Kotest to main

**Files:**
- Modify: `build.gradle.kts`

- [ ] **Step 1: Edit `build.gradle.kts`**

Remove the `testBridge` source set creation block and the `testBridge` configuration definitions. Promote Kotest to `implementation`. Update `clientTest` and `gametest` to no longer depend on `testBridge`.

**Replace lines 36-55 (`sourceSets { create("testBridge") {...}; create("clientTest") {...} }`) with:**

```kotlin
sourceSets {
    create("clientTest") {
        compileClasspath += sourceSets["main"].output +
            sourceSets["client"].output +
            sourceSets["client"].compileClasspath
        runtimeClasspath += sourceSets["main"].output +
            sourceSets["client"].output +
            sourceSets["client"].runtimeClasspath
    }
}
```

**Replace lines 63-85 (the `configurations { ... }` block) with:**

```kotlin
configurations {
    named("clientTestImplementation") {
        extendsFrom(configurations["clientImplementation"])
    }
    named("clientTestCompileOnly") {
        extendsFrom(configurations["clientCompileOnly"])
    }
    named("clientTestRuntimeOnly") {
        extendsFrom(configurations["clientRuntimeOnly"])
    }
}
```

**Replace lines 98-106 (the `afterEvaluate { ... }` block) with:**

```kotlin
// Loom creates the `gametest` source set during project evaluation; nothing extra to wire
// since gametest now picks up Kotest via main's `implementation` classpath transitively.
```

**Replace lines 153-164 (`testImplementation`/`testBridge*` Kotest declarations) with:**

```kotlin
    // Kotest engine + assertions ship in main: used by .spec.kts at runtime AND by all dev test source sets.
    implementation("io.kotest:kotest-runner-junit5:5.9.1")
    implementation("io.kotest:kotest-assertions-core:5.9.1")

    // kotlinx-coroutines-core (also pulled by fabric-language-kotlin transitively, declared explicitly).
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.1")

    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.1")
    testImplementation("org.mockito:mockito-core:5.14.2")
```

**Remove lines 150-151** (the `clientTestImplementation` and `testBridgeImplementation` references to `fabric-client-gametest-api-v1`) **and replace with**:

```kotlin
    "clientTestImplementation"(fabricApi.module("fabric-client-gametest-api-v1", project.property("fabric_version") as String))
```

- [ ] **Step 2: Delete the empty testBridge source-set directories**

```bash
rm -rf src/testBridge
```

- [ ] **Step 3: Build**

Run: `cmd.exe /c "./gradlew.bat :26.1:clientClasses classes gametestClasses clientTestClasses testClasses"`
Expected: BUILD SUCCESSFUL across all five source sets. If anything in `clientTest`/`gametest`/`test` references `ServerTestSpec` it will fail compilation; proceed to Task 7 to fix consumers.

- [ ] **Step 4: Commit**

```bash
git add build.gradle.kts src/main/kotlin/com/breadmoirai/garnet/testing src/testBridge
git commit -m "refactor(testing): promote testing package and Kotest to main"
```

---

## Task 7: Update consumers — rename ServerTestSpec → GarnetTestSpec

**Files:**
- Modify: every file referencing `ServerTestSpec` (find them with grep below).

- [ ] **Step 1: Find consumers**

Run: `grep -rn "ServerTestSpec" src/ --include="*.kt"`

The expected hits at the time of writing:
- `src/gametest/kotlin/com/breadmoirai/garnet/test/SmokeSpec.kt`
- `src/clientTest/kotlin/com/breadmoirai/garnet/test/SpecTestContext.kt` (may import; verify)
- `docs/gametest/kotest-bridge.md` (docs — handled in Task 8)

- [ ] **Step 2: For each `.kt` consumer, rewrite extends/imports**

For each file printed by the grep above, replace `ServerTestSpec` with `GarnetTestSpec` (both in `import` lines and `class X : ServerTestSpec(...)` declarations).

Example for `SmokeSpec.kt`:

```kotlin
// Before:
import com.breadmoirai.garnet.testing.ServerTestSpec
class SmokeSpec : ServerTestSpec({ ... })

// After:
import com.breadmoirai.garnet.testing.GarnetTestSpec
class SmokeSpec : GarnetTestSpec({ ... })
```

- [ ] **Step 3: Build all source sets**

Run: `cmd.exe /c "./gradlew.bat :26.1:clientClasses classes gametestClasses clientTestClasses testClasses"`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Run unit tests**

Run: `cmd.exe /c "./gradlew.bat :26.1:test"`
Expected: PASS. The `KtsSpecLoaderRoundtripTest` from Task 1 now runs against the same Kotest version that ships in main — confirms unification.

- [ ] **Step 5: Commit**

```bash
git add src/
git commit -m "refactor(testing): rename ServerTestSpec to GarnetTestSpec at all call sites"
```

---

## Task 8: Update docs — kotest-bridge article and INDEX

**Files:**
- Modify: `docs/gametest/kotest-bridge.md`
- Modify: `docs/build/INDEX.md` (if it lists `gametest-sourceset-split-wiring.md`)
- Delete or revise: `docs/build/gametest-sourceset-split-wiring.md`

- [ ] **Step 1: In `docs/gametest/kotest-bridge.md`**

Replace `ServerTestSpec` with `GarnetTestSpec` throughout. Update the "Base class" section's class name and prose. Add a sentence at the top of the article: "The same base class is used by shipped `.spec.kts` files at runtime — see `docs/superpowers/specs/2026-05-07-garnet-kotest-bridge-design.md`."

- [ ] **Step 2: Decide on `gametest-sourceset-split-wiring.md`**

That article documented testBridge wiring that no longer exists. Replace its body with a one-line redirect:

```markdown
---
title: (Retired) Gametest source-set split wiring
tags: [retired]
summary: Documented the testBridge source set, which has been merged into main.
---

The `testBridge` source set was merged into `main` on 2026-05-07; see `docs/superpowers/plans/2026-05-07-plan-a-promote-testing-to-main.md` for the migration. Kotest is now a `main` `implementation` dependency, so all source sets pick it up transitively.
```

- [ ] **Step 3: Update `docs/build/INDEX.md` summary for that file** to read: "Retired — merged into main. Tags: retired."

- [ ] **Step 4: Commit**

```bash
git add docs/
git commit -m "docs: update kotest-bridge for ServerTestSpec→GarnetTestSpec; retire split-wiring article"
```

---

## Task 9: Update memory references

**Files:**
- Modify: `/home/local/.claude/projects/-mnt-h-Repo-garnet/memory/MEMORY.md`
- Modify: the linked memory file `reference_gametest_split_wiring.md` if it exists.

- [ ] **Step 1: Update the memory pointer**

The MEMORY.md entry "Gametest sourceset split wiring doc" points to a now-retired article. Update its summary to note the article is retired post-2026-05-07. Or remove if no longer load-bearing for future sessions.

(This is housekeeping; if the file isn't writable here, skip and let a future session reconcile.)

- [ ] **Step 2: Commit if anything changed**

```bash
git add -A
git commit -m "chore: refresh memory pointer for retired split-wiring doc" --allow-empty
```

---

## Verification checklist

- [ ] `find src/testBridge -type f` returns nothing.
- [ ] `grep -rn "ServerTestSpec" src/ docs/` returns nothing.
- [ ] `cmd.exe /c "./gradlew.bat :26.1:clientClasses classes gametestClasses clientTestClasses testClasses"` succeeds.
- [ ] `cmd.exe /c "./gradlew.bat :26.1:test"` succeeds, including the new `KtsSpecLoaderRoundtripTest`.
- [ ] `GarnetTestSpec` is in `src/main/kotlin/com/breadmoirai/garnet/testing/GarnetTestSpec.kt`.
- [ ] `KotestLauncher`, `ResultCollector`, `Structures`, `Suspending`, `McDispatchers`, `awaitTicks` all live under `src/main/kotlin/com/breadmoirai/garnet/testing/`.

---

## Notes on what is intentionally NOT in this plan

- No new behavior. Nothing at runtime calls `KotestLauncher` from production code yet — that's Plan D.
- `OutputVerifier` is untouched. Plan B retires it.
- `KtsSpecEmitter` still emits the declarative `garnetSpec(...)` form. Plan C wraps that in a `GarnetTestSpec` subclass.
- The shipped jar gets larger (Kotest engine + assertions move from `testBridgeImplementation` to `implementation`). This is intentional and discussed in the spec; no mitigation in this plan.
