---
title: Local Compile Verification Across All Source Sets
tags: [gradle, source-sets, verification, loom]
summary: `compileKotlin` only covers `main`; verify with `clientClasses classes gametestClasses clientTestClasses testClasses` to catch errors across all five source sets.
---

# Local Compile Verification Across All Source Sets

`:26.2:compileKotlin` is **not** sufficient to verify the project compiles. It
only covers the `main` source set. This project has five source sets, and each
has independent compile tasks that can fail independently.

## The full command

```sh
cmd.exe /c "cd /d H:\\Repo\\RedstoneSpecs && gradlew.bat :26.2:clientClasses :26.2:classes :26.2:gametestClasses :26.2:clientTestClasses :26.2:testClasses"
```

Use `*Classes` aggregate tasks rather than `compile*Kotlin` so that Java
sources, resource processing, and KSP-generated sources all run.

## Why five source sets?

`build.gradle.kts` enables Loom's split-environment layout:

```kotlin
loom {
    splitEnvironmentSourceSets()
    mods {
        register("redstonespecs") {
            sourceSet("main")
            sourceSet("client")
        }
        register("redstonespecs-clienttest") {
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
        modId = "redstonespecs-gametest"
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
log4j JVM args set in `build.gradle.kts` to surface the `redstonespecs`
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
        register("redstonespecs-clienttest") {
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

## See also

- [WSL2 invocation](wsl2-gradle-invocation.md)
- [Stonecutter task paths](stonecutter-task-paths.md)
