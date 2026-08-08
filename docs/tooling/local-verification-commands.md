---
title: Local Compile Verification Across All Source Sets
tags: [gradle, source-sets, verification, loom, kotest]
summary: `compileKotlin` only covers `main`; verify with `clientClasses classes gametestClasses clientTestClasses testClasses` to catch errors across all five source sets, and run `:26.2:test` unfiltered because Gradle's `--tests` filter does not select Kotest specs.
---

# Local Compile Verification Across All Source Sets

`:26.2:compileKotlin` is **not** sufficient to verify the project compiles. It
only covers the `main` source set. This project has five source sets, and each
has independent compile tasks that can fail independently.

## The full command

```sh
cmd.exe /c "cd /d H:\\Repo\\garnet && gradlew.bat :26.2:clientClasses :26.2:classes :26.2:gametestClasses :26.2:clientTestClasses :26.2:testClasses"
```

Use `*Classes` aggregate tasks rather than `compile*Kotlin` so that Java
sources, resource processing, and KSP-generated sources all run.

## Why five source sets?

`build.gradle.kts` enables Loom's split-environment layout:

```kotlin
loom {
    splitEnvironmentSourceSets()
    mods {
        register("garnet") {
            sourceSet("main")
            sourceSet("client")
        }
        register("garnet-clienttest") {
            sourceSet("clientTest")
        }
    }
}
```

Fabric API's `configureTests` creates the server-side `gametest` source set;
`clientTest` is created manually and given its own Loom run config (see
[Loom run config for clientTest](#loom-run-config-for-clienttest) below):

```kotlin
fabricApi {
    configureTests {
        createSourceSet = true
        modId = "garnet-gametest"
        enableGameTests = true
        enableClientGameTests = false   // we own the client-test run config
        eula = true
    }
}
```

Resulting source roots (see `src/`):

| Source set   | Roots                                          | Task               |
|--------------|------------------------------------------------|--------------------|
| `main`       | `src/main/kotlin`, `src/main/java`             | `classes`          |
| `client`     | `src/client/kotlin`, `src/client/java`         | `clientClasses`    |
| `gametest`   | `src/gametest/kotlin`                          | `gametestClasses`  |
| `clientTest` | `src/clientTest/kotlin`                        | `clientTestClasses`|
| `test`       | `src/test/kotlin`                              | `testClasses`      |

`main` is server+client common code. `client` is client-only (screens,
widgets, render-state extractors). `gametest` is the server-side `@GameTest`
harness run via `runGameTest`. `clientTest` is the `FabricClientGameTest`
harness run via `runClientTest`. `test` is JUnit unit tests run via the
`test` task with `fabric-loader-junit`. See
[unit-vs-gametest-split.md](../gametest/unit-vs-gametest-split.md) for the
decision rule.

## Failure modes that `compileKotlin` alone misses

- **Client-only API drift.** A renamed `GuiGraphicsExtractor` method only
  breaks `clientClasses`.
- **Gametest helper changes.** Fabric's `GameTestHelper` updates only break
  `gametestClasses`.
- **Client-gametest API changes.** `ClientGameTestContext` /
  `TestSingleplayerContext` updates only break `clientTestClasses`.
- **Unit test fixtures.** A renamed model class only breaks `testClasses`.

## Running tests

Compile tasks check that code builds; they do not run tests. To execute:

```sh
cmd.exe /c "gradlew.bat :26.2:test"            # JUnit, no MC runtime
cmd.exe /c "gradlew.bat :26.2:runGameTest"     # server-side @GameTest harness
cmd.exe /c "gradlew.bat :26.2:runClientTest"   # FabricClientGameTest harness
```

Both `runGameTest` and `runClientTest` are Loom-managed JavaExec tasks with
log4j JVM args set in `build.gradle.kts` to surface the `garnet`
logger at DEBUG. `runGameTest` comes from Fabric API's `configureTests`;
`runClientTest` is registered via `loom.runs.register("clientTest") { ... }`
(see below).

## Loom run config for clientTest

Because `enableClientGameTests = false`, Loom doesn't auto-create a
client-gametest task. We register one ourselves through Loom's `runs` DSL,
which sets up Knot/app classloader separation correctly (a hand-rolled
`JavaExec` puts the mod classpath on the system loader and breaks Mixin
plugin loading):

```kotlin
val clientTestSourceSet = sourceSets.create("clientTest")

loom {
    mods {
        register("garnet-clienttest") {
            sourceSet(clientTestSourceSet)
        }
    }
    runs {
        register("clientTest") {
            client()
            source(clientTestSourceSet)
            property("fabric.client.gametest", "true")
            // log4j + Java 25 native-access flags
        }
    }
}
```

This produces a `runClientTest` Gradle task that launches a real Minecraft
client on the `clientTest` sourceset's classpath, with the
`fabric-client-gametest` entrypoint registered via
`src/clientTest/resources/fabric.mod.json`.

## `--tests` filtering does not select Kotest specs — run the task unfiltered

`gradlew.bat :26.2:test --tests '*SomeSpec*'` reports a false "No tests found for given
include pattern" even when `SomeSpec` exists and contains real, passing tests. This was
reproduced on the pre-existing `DockLifecycleTest`, so it is not specific to any one test
class or to newly added ones — it reproduces across the `test` source set generally, which
runs Kotest specs (`io.kotest:kotest-runner-junit5`) rather than plain JUnit Jupiter classes.

Most likely cause, not verified against Kotest's engine: Gradle's `--tests` filtering has to
pre-select candidate test classes before running them, which relies on the test framework
exposing a conventional class/method shape (JUnit Jupiter's `@Test`-annotated methods, or
JUnit4-style classes). Kotest specs don't have that shape — a spec's individual tests are
registered dynamically by the Kotest JUnit Platform `TestEngine` at discovery time, not
declared as annotated methods Gradle can see up front — so Gradle's pattern matcher finds
nothing to select and reports the suite as empty, even though the same spec runs and passes
as part of the unfiltered task. This matches the same
symptom recorded independently across several `docs/superpowers/plans/*.md` snapshots in this
repo, so it is a known, repeatable interop gap rather than a one-off fluke.

**Workaround:** run the unfiltered task and read the per-class JUnit XML report instead of
filtering:

```sh
cmd.exe /c "cd /d H:\\Repo\\Garnet && gradlew.bat :26.2:test"
```

Then check the specific class's result under
`versions/26.2/build/test-results/test/TEST-<fully.qualified.ClassName>.xml` (e.g.
`TEST-com.breadmoirai.garnet.client.ui.dock.DockLifecycleTest.xml`).

## See also

- [WSL2 invocation](wsl2-gradle-invocation.md)
- [Stonecutter task paths](stonecutter-task-paths.md)
