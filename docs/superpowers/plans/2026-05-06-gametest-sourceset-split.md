# Gametest Sourceset Split Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Split today's combined `src/gametest/` sourceset into a server-side `gametest` sourceset and a new client-side `clientTest` sourceset, each with its own test mod-id, `fabric.mod.json`, and run task.

**Architecture:** Keep `fabricApi.configureTests` for the server side (loom continues to manage the `gametest` sourceset and `runGameTest` task; only the mod-id is renamed and `enableClientGameTests` flips to `false`). The new `clientTest` sourceset is wired by hand: a manual sourceset declaration, a hand-written `runClientTest` `JavaExec` task whose config mirrors loom's existing `runClientGameTest`, and its own `fabric.mod.json`. `SpecTestContext` and `garnetClientTests` move into `src/clientTest/`; `garnetGameTests` and gametest structure data stay in `src/gametest/`.

**Tech Stack:** Kotlin 2.x, Gradle Kotlin DSL, Fabric Loom, fabric-api `configureTests`, JUnit-style Fabric `@GameTest` (server) and `FabricClientGameTest` (client).

**Conventions:**
- Active MC version is `26.1`. All gradle invocations use `cmd.exe /c "./gradlew.bat :26.1:<task>"` (project is on WSL2; see `MEMORY.md`).
- Each task ends with a commit.
- The build must stay green at the end of every task — tasks are ordered so you can stop after any one of them.

---

## File Structure

| File | State | Responsibility |
|---|---|---|
| `build.gradle.kts` | modify | `configureTests` flags + new `clientTest` sourceset, mod registration, configurations, and `runClientTest` task |
| `src/gametest/resources/fabric.mod.json` | modify | Mod-id renames to `garnet-gametest`; only `fabric-gametest` entrypoint remains |
| `src/clientTest/resources/fabric.mod.json` | create | New manifest with mod-id `garnet-clienttest`; only `fabric-client-gametest` entrypoint |
| `src/gametest/kotlin/com/breadmoirai/garnet/test/garnetClientTests.kt` | move → `src/clientTest/kotlin/...` | Client gametest flow |
| `src/gametest/kotlin/com/breadmoirai/garnet/test/SpecTestContext.kt` | move → `src/clientTest/kotlin/...` | Helper used only by `garnetClientTests` (verified by grep — no server-side reference) |
| `src/gametest/kotlin/com/breadmoirai/garnet/test/garnetGameTests.kt` | unchanged | Server `@GameTest` flow |
| `src/gametest/resources/data/garnet/structures/lever_lamp.snbt` | unchanged | Used by server `@GameTest` structures |
| `docs/gametest/unit-vs-gametest-split.md` | modify | Decision rule grows from 2-way to 3-way; update file paths |
| `docs/architecture/module-map.md` | modify | "Client and tests" subsection lists both gametest sourcesets |

---

## Task 1: Add empty `clientTest` sourceset and mod registration

Goal: introduce the new sourceset wiring with no source files yet, so the build stays green and we can confirm loom accepts the configuration.

**Files:**
- Modify: `build.gradle.kts:36-44` (`fabricApi.configureTests`) and append new sourceset/mod blocks below `loom { … }`

- [ ] **Step 1: Read current `build.gradle.kts`**

Run:
```bash
sed -n '25,60p' build.gradle.kts
```
Expected: see the existing `loom { splitEnvironmentSourceSets() … }` and `fabricApi.configureTests { … enableClientGameTests = true … }` blocks.

- [ ] **Step 2: Add the `clientTest` sourceset and a loom mod entry for it**

Add this block to `build.gradle.kts` immediately after the closing `}` of the existing `loom { … }` block (around line 34) — i.e. before `fabricApi {`:

```kotlin
sourceSets {
    create("clientTest") {
        compileClasspath += sourceSets["main"].output + sourceSets["client"].output
        runtimeClasspath += sourceSets["main"].output + sourceSets["client"].output
    }
}

loom {
    mods.register("garnet-clienttest") {
        sourceSet("clientTest")
    }
}

configurations {
    named("clientTestImplementation") {
        extendsFrom(configurations["clientImplementation"])
    }
}
```

Do NOT yet change `configureTests` flags or the `garnet-test` mod-id — keep the existing combined gametest behaviour working until Task 3.

- [ ] **Step 3: Verify the build still configures**

Run:
```bash
cmd.exe /c "./gradlew.bat :26.1:help"
```
Expected: BUILD SUCCESSFUL. No "could not register" / "duplicate sourceset" errors.

- [ ] **Step 4: Verify the new sourceset compiles (empty)**

Run:
```bash
cmd.exe /c "./gradlew.bat :26.1:clientTestClasses"
```
Expected: BUILD SUCCESSFUL. Task UP-TO-DATE or NO-SOURCE because the directory is empty — both are fine.

- [ ] **Step 5: Verify pre-existing gametest still compiles unchanged**

Run:
```bash
cmd.exe /c "./gradlew.bat :26.1:gametestClasses"
```
Expected: BUILD SUCCESSFUL — same outcome as before this task.

- [ ] **Step 6: Commit**

```bash
git add build.gradle.kts
git commit -m "build: add empty clientTest sourceset and loom mod registration"
```

---

## Task 2: Inspect loom's `runClientGameTest` to capture its configuration

Goal: capture the exact properties of loom's auto-generated `runClientGameTest` task before we disable it in Task 3, so the manual `runClientTest` we add in Task 4 can mirror it accurately.

This is research only — no code changes, no commit.

**Files:** none modified.

- [ ] **Step 1: List the task properties of `runClientGameTest`**

Run:
```bash
cmd.exe /c "./gradlew.bat :26.1:help --task runClientGameTest"
```
Record the printed `Type`, `Description`, `Group`, and any "Options" / system properties section in your scratch notes.

- [ ] **Step 2: Dump the resolved task config**

Add a temporary inspection block to `build.gradle.kts` (do NOT commit it). Place it at the end of the file:

```kotlin
gradle.projectsEvaluated {
    val rcgt = tasks.findByName("runClientGameTest") as? JavaExec
    if (rcgt != null) {
        println("=== runClientGameTest dump ===")
        println("mainClass:    " + rcgt.mainClass.orNull)
        println("classpath:    " + rcgt.classpath.files.joinToString("\n              "))
        println("workingDir:   " + rcgt.workingDir)
        println("args:         " + rcgt.args)
        println("jvmArgs:      " + rcgt.jvmArgs)
        println("systemProps:  " + rcgt.systemProperties)
        println("environment:  " + rcgt.environment.filterKeys { it.startsWith("FABRIC") || it.startsWith("LOOM") })
        println("=== end dump ===")
    } else {
        println("runClientGameTest not found")
    }
}
```

Run:
```bash
cmd.exe /c "./gradlew.bat :26.1:help"
```
Expected: BUILD SUCCESSFUL with the `=== runClientGameTest dump ===` block printed in the output.

- [ ] **Step 3: Save the dump**

Copy the entire `=== runClientGameTest dump ===` block from the gradle output into a scratch file at `/tmp/run-client-game-test-dump.txt` for reference in Task 4.

- [ ] **Step 4: Remove the temporary inspection block**

Delete the `gradle.projectsEvaluated { … }` block you added in Step 2. Re-run:
```bash
cmd.exe /c "./gradlew.bat :26.1:help"
```
Expected: BUILD SUCCESSFUL with NO `=== runClientGameTest dump ===` line.

- [ ] **Step 5: Verify nothing was committed**

Run:
```bash
git status build.gradle.kts
```
Expected: `nothing to commit, working tree clean` for `build.gradle.kts`. (No commit at the end of this task — research only.)

---

## Task 3: Move client test sources into `clientTest` and split `fabric.mod.json`

Goal: physically move `garnetClientTests` and `SpecTestContext` into `src/clientTest/`, give the client side its own manifest with a new mod-id, narrow the existing manifest to server-only entrypoints, and rename the server test mod-id. After this task, both `gametestClasses` and `clientTestClasses` compile, and `runGameTest` still passes.

**Files:**
- Move: `src/gametest/kotlin/com/breadmoirai/garnet/test/garnetClientTests.kt` → `src/clientTest/kotlin/com/breadmoirai/garnet/test/garnetClientTests.kt`
- Move: `src/gametest/kotlin/com/breadmoirai/garnet/test/SpecTestContext.kt` → `src/clientTest/kotlin/com/breadmoirai/garnet/test/SpecTestContext.kt`
- Modify: `src/gametest/resources/fabric.mod.json`
- Create: `src/clientTest/resources/fabric.mod.json`
- Modify: `build.gradle.kts:36-44` (`fabricApi.configureTests`)

- [ ] **Step 1: Confirm `SpecTestContext` is unused by the server gametest**

Run:
```bash
grep -n "SpecTestContext" src/gametest/kotlin/com/breadmoirai/garnet/test/garnetGameTests.kt
```
Expected: no matches. (If there are matches, stop and re-evaluate the placement decision; the spec lists fallback options 2a/2b/2c.)

- [ ] **Step 2: Create the client-test directory structure and move the two files**

Run:
```bash
mkdir -p src/clientTest/kotlin/com/breadmoirai/garnet/test src/clientTest/resources
git mv src/gametest/kotlin/com/breadmoirai/garnet/test/garnetClientTests.kt src/clientTest/kotlin/com/breadmoirai/garnet/test/garnetClientTests.kt
git mv src/gametest/kotlin/com/breadmoirai/garnet/test/SpecTestContext.kt src/clientTest/kotlin/com/breadmoirai/garnet/test/SpecTestContext.kt
```

Verify:
```bash
ls src/gametest/kotlin/com/breadmoirai/garnet/test/ src/clientTest/kotlin/com/breadmoirai/garnet/test/
```
Expected: `garnetGameTests.kt` only in the gametest dir; both `garnetClientTests.kt` and `SpecTestContext.kt` in the clientTest dir. The Kotlin package declarations stay `com.breadmoirai.garnet.test` — no source edits required since the package is unchanged.

- [ ] **Step 3: Create the new client-test manifest**

Create `src/clientTest/resources/fabric.mod.json` with this exact content:

```json
{
  "schemaVersion": 1,
  "id": "garnet-clienttest",
  "version": "1.0.0",
  "name": "garnet Client Testmod",
  "environment": "client",
  "entrypoints": {
    "fabric-client-gametest": [
      "com.breadmoirai.garnet.test.garnetClientTests"
    ]
  },
  "depends": {
    "garnet": "*"
  }
}
```

- [ ] **Step 4: Narrow the existing gametest manifest to server-only**

Replace the contents of `src/gametest/resources/fabric.mod.json` with:

```json
{
  "schemaVersion": 1,
  "id": "garnet-gametest",
  "version": "1.0.0",
  "name": "garnet Server Testmod",
  "environment": "*",
  "entrypoints": {
    "fabric-gametest": [
      "com.breadmoirai.garnet.test.garnetGameTests"
    ]
  },
  "depends": {
    "garnet": "*"
  }
}
```

Note: the `id` field changes from `garnet-test` to `garnet-gametest`, and the `fabric-client-gametest` entrypoint is removed.

- [ ] **Step 5: Update `configureTests` to match: rename mod-id and disable client gametests**

In `build.gradle.kts`, change the `fabricApi.configureTests { … }` block (around lines 36-44) so the result is exactly:

```kotlin
fabricApi {
    configureTests {
        createSourceSet = true
        modId = "garnet-gametest"
        enableGameTests = true
        enableClientGameTests = false
        eula = true
    }
}
```

- [ ] **Step 6: Compile both gametest sourcesets**

Run:
```bash
cmd.exe /c "./gradlew.bat :26.1:gametestClasses :26.1:clientTestClasses"
```
Expected: BUILD SUCCESSFUL. If `clientTestClasses` fails because `SpecTestContext` references something it can no longer see (e.g. a server-only API), stop and reassess — note in your scratch which dependency is missing.

- [ ] **Step 7: Verify the server gametest still passes**

Run:
```bash
cmd.exe /c "./gradlew.bat :26.1:runGameTest"
```
Expected: BUILD SUCCESSFUL with the same set of `@GameTest` methods passing as before this plan started.

- [ ] **Step 8: Confirm no stale `garnet-test` references remain**

Run:
```bash
grep -rn "garnet-test" --include="*.kt" --include="*.kts" --include="*.json"
```
Expected: no matches. (If any match, replace it with the appropriate new mod-id and re-run Step 7.)

- [ ] **Step 9: Commit**

```bash
git add src/gametest/kotlin src/clientTest src/gametest/resources/fabric.mod.json build.gradle.kts
git commit -m "build: move client gametests into dedicated clientTest sourceset

- Move garnetClientTests and SpecTestContext to src/clientTest
- Add src/clientTest/resources/fabric.mod.json (mod-id garnet-clienttest)
- Rename server gametest mod-id from garnet-test to garnet-gametest
- Disable enableClientGameTests in configureTests; client side is now manual"
```

---

## Task 4: Add a `runClientTest` task that mirrors loom's old `runClientGameTest`

Goal: register a `JavaExec` task on `clientTest` that reproduces what loom did for `runClientGameTest` (mainClass, classpath, system properties, fabric run-config injection), so the relocated `garnetClientTests` can actually execute.

**Files:**
- Modify: `build.gradle.kts` (append a `tasks.register<JavaExec>("runClientTest") { … }` block)

- [ ] **Step 1: Re-open the dump captured in Task 2**

Open `/tmp/run-client-game-test-dump.txt`. Identify, at minimum:
- `mainClass` (typically `net.fabricmc.devlaunchinjector.Main` or a fabric loader main; record the exact value)
- The system properties starting with `fabric.` and `loom.` (e.g. `fabric.dli.config`, `fabric.dli.env=client`, `fabric.client.gametest`)
- The full classpath (it will reference the gametest sourceset's runtime classpath plus the dev-launch classpath)

These values are what `runClientTest` must reproduce, **except** every reference to the `gametest` sourceset is replaced with `clientTest`.

- [ ] **Step 2: Add the `runClientTest` task**

Append this to `build.gradle.kts` inside the `tasks { … }` block (alongside the existing `named<JavaExec>("runGameTest") { … }` block, around line 131):

```kotlin
register<JavaExec>("runClientTest") {
    group = "fabric"
    description = "Runs FabricClientGameTest flows from the clientTest sourceset."

    val clientTestSrc = sourceSets["clientTest"]
    classpath = clientTestSrc.runtimeClasspath
    mainClass.set(/* mainClass from the dump in Task 2 */)

    // Reproduce every system property from the dump whose key begins with
    // "fabric." or "loom.". Replace any path that pointed at the gametest
    // sourceset with the equivalent clientTest path.
    systemProperty("fabric.dli.env", "client")
    systemProperty("fabric.client.gametest", "true")
    // …add the remaining systemProperty(...) calls captured in the dump…

    jvmArgs(
        "-Dlog4j2.logger.garnet.name=Garnet",
        "-Dlog4j2.logger.garnet.level=DEBUG",
    )

    workingDir = project.file("run")
    dependsOn("clientTestClasses")
}
```

The two commented placeholders (`/* mainClass from the dump in Task 2 */` and `…add the remaining systemProperty(...) calls…`) are filled in from `/tmp/run-client-game-test-dump.txt` — do not leave placeholders in the committed file.

- [ ] **Step 3: Verify the task is registered**

Run:
```bash
cmd.exe /c "./gradlew.bat :26.1:help --task runClientTest"
```
Expected: BUILD SUCCESSFUL with the task description printed.

- [ ] **Step 4: Run it end-to-end**

Run:
```bash
cmd.exe /c "./gradlew.bat :26.1:runClientTest"
```
Expected: BUILD SUCCESSFUL. The MC client launches, executes `garnetClientTests`, and exits with the same outcome it had when running under loom's `runClientGameTest` before the split.

If the client launches but tests fail because the `garnet-clienttest` mod doesn't appear loaded, double-check that:
- `src/clientTest/resources/fabric.mod.json` is on the runtime classpath (Task 1's `runtimeClasspath += …` plus loom's `mods.register("garnet-clienttest") { sourceSet("clientTest") }`).
- The dump's classpath entries for the `gametest` sourceset were translated to `clientTest`.

If the client fails to launch with a missing system property, compare the running configuration to the Task 2 dump and add the missing property.

- [ ] **Step 5: Re-verify the server side is unaffected**

Run:
```bash
cmd.exe /c "./gradlew.bat :26.1:runGameTest"
```
Expected: BUILD SUCCESSFUL with the same outcomes as Task 3 Step 7.

- [ ] **Step 6: Commit**

```bash
git add build.gradle.kts
git commit -m "build: add runClientTest task mirroring loom's runClientGameTest"
```

---

## Task 5: End-to-end verification of all three layers

Goal: confirm the four canonical commands from the design doc all succeed together, with the same test outcomes as before the split.

**Files:** none modified.

- [ ] **Step 1: Compile every sourceset**

Run:
```bash
cmd.exe /c "./gradlew.bat :26.1:clientClasses :26.1:classes :26.1:gametestClasses :26.1:clientTestClasses :26.1:testClasses"
```
Expected: BUILD SUCCESSFUL. All five compile tasks succeed.

- [ ] **Step 2: Run JUnit unit tests**

Run:
```bash
cmd.exe /c "./gradlew.bat :26.1:test"
```
Expected: BUILD SUCCESSFUL. Same passing test count as before the split (this layer was untouched).

- [ ] **Step 3: Run server gametests**

Run:
```bash
cmd.exe /c "./gradlew.bat :26.1:runGameTest"
```
Expected: BUILD SUCCESSFUL. Same passing `@GameTest` methods as before the split.

- [ ] **Step 4: Run client gametests**

Run:
```bash
cmd.exe /c "./gradlew.bat :26.1:runClientTest"
```
Expected: BUILD SUCCESSFUL. Same `garnetClientTests` outcomes as the pre-split `runClientGameTest`.

- [ ] **Step 5: Confirm `runClientGameTest` is no longer registered**

Run:
```bash
cmd.exe /c "./gradlew.bat :26.1:tasks --group fabric"
```
Expected: `runGameTest` and `runClientTest` are listed; `runClientGameTest` is NOT listed (since `enableClientGameTests = false` removed it). If it still appears, recheck Task 3 Step 5.

No commit — verification only.

---

## Task 6: Update documentation

Goal: bring `docs/gametest/unit-vs-gametest-split.md` and `docs/architecture/module-map.md` in line with the new three-sourceset reality.

**Files:**
- Modify: `docs/gametest/unit-vs-gametest-split.md`
- Modify: `docs/architecture/module-map.md`

- [ ] **Step 1: Update `unit-vs-gametest-split.md`**

In `docs/gametest/unit-vs-gametest-split.md`, change the "The repo uses two separate source sets" preamble to describe three sourcesets:

Replace the existing list (lines 9-15 of the original file) with:

```
The repo uses three separate source sets:

- `src/test/` — JUnit 5 unit tests, run on the JVM with no MC client or
  server. Bootstrap MC via `SharedConstants.tryDetectVersion()` +
  `Bootstrap.bootStrap()` when registries are needed
  (`RecordingFinalizerTest` is the canonical example).
- `src/gametest/` — Fabric `@GameTest` server-side flows that run inside
  a dedicated MC server instance (`runGameTest`).
- `src/clientTest/` — `FabricClientGameTest` flows that run inside a
  full MC client (`runClientTest`).
```

Update the "Decision rule" section so it distinguishes server-gametest from client-gametest:

```
If the logic is **pure** — given inputs in, asserts outputs, no MC
ticks, no levels, no scheduled-tick semantics — it belongs in
`src/test/`. If correctness depends on MC's tick loop, neighbor
updates, scheduled ticks, or BE persistence on the server, it belongs
in `src/gametest/`. If it requires a real client — screens, widgets,
keybinds, payload round-trips driven from the client — it belongs in
`src/clientTest/`.
```

Update the "Where the contracts actually live" section so the line for `garnetClientTests` reads:

```
- `garnetClientTests` (in `src/clientTest/`) — full client UI
  flow (recorder screen → marker tool → editor screen → runner block).
  Drives screens, payloads, keybinds. Runs via `runClientTest`.
```

Update the "Practical guidance" bullet for client work:

```
- New screen, widget, payload, or marker-tool flow? Add to
  `garnetClientTests` in `src/clientTest/` (uses
  `SpecTestContext`, which lives alongside it).
```

- [ ] **Step 2: Update `module-map.md`'s "Client and tests" subsection**

In `docs/architecture/module-map.md`, find the "Client and tests" subsection (around lines 64-68 of the original) and replace its bullets with:

```
- `src/client/kotlin/...` — every screen widget and the client-side payload sender. See [ui/INDEX.md](../ui/INDEX.md).
- `src/test/kotlin/...` — pure JUnit (currently `RecordingFinalizerTest` and friends). See [gametest/unit-vs-gametest-split.md](../gametest/unit-vs-gametest-split.md).
- `src/gametest/kotlin/...` — server-side `@GameTest` flows (`runGameTest`). See [gametest/INDEX.md](../gametest/INDEX.md).
- `src/clientTest/kotlin/...` — client-side `FabricClientGameTest` flows (`runClientTest`). See [gametest/INDEX.md](../gametest/INDEX.md).
```

- [ ] **Step 3: Verify `docs/gametest/INDEX.md` summaries still match**

Run:
```bash
cat docs/gametest/INDEX.md
```
Read the entries; if any one-line summary still implies a single combined gametest sourceset, update its summary to reflect the split. If everything reads correctly, no change needed.

- [ ] **Step 4: Commit**

```bash
git add docs/
git commit -m "docs: update gametest-split and module-map for clientTest sourceset"
```

---

## Self-review

- **Spec coverage** — every section of the design doc maps to a task: sourceset layout (Tasks 1, 3), build config (Tasks 1, 3, 4), resource/entrypoint split (Task 3), file migration (Task 3), `SpecTestContext` placement (Task 3 Step 1 confirms option 1; documented in File Structure table), docs updates (Task 6), verification (Task 5), risks (`runClientTest` wiring → Tasks 2 + 4; mod-id rename audit → Task 3 Step 8; `SpecTestContext` coupling → Task 3 Step 1 with explicit fallback note).
- **Placeholder scan** — the only intentional placeholders are inside the inline code block of Task 4 Step 2 (`/* mainClass from the dump in Task 2 */` and `…add the remaining systemProperty(...) calls captured in the dump…`); the step text explicitly tells the implementer to fill them in from the captured dump and not commit placeholders.
- **Type/path consistency** — sourceset names (`test`, `gametest`, `clientTest`), mod-ids (`garnet-gametest`, `garnet-clienttest`), and run task names (`runGameTest`, `runClientTest`) are spelled the same way throughout. The `clientTestImplementation` configuration is registered in Task 1 and consumed implicitly via `sourceSets["clientTest"].runtimeClasspath` in Task 4.
