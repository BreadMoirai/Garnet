---
title: Local Compile Verification Across All Source Sets
tags: [gradle, source-sets, verification, loom]
summary: `compileKotlin` only covers `main`; verify with `clientClasses classes gametestClasses clientTestClasses testClasses` to catch errors across all five source sets.
---

# Local Compile Verification Across All Source Sets

`:26.1:compileKotlin` is **not** sufficient to verify the project compiles. It
only covers the `main` source set. This project has five source sets, and each
has independent compile tasks that can fail independently.

## The full command

```sh
cmd.exe /c "cd /d H:\\Repo\\RedstoneSpecs && gradlew.bat :26.1:clientClasses :26.1:classes :26.1:gametestClasses :26.1:clientTestClasses :26.1:testClasses"
```

Use `*Classes` aggregate tasks rather than `compile*Kotlin` so that Java
sources, resource processing, and KSP-generated sources all run.

## Why four source sets?

`build.gradle.kts` enables Loom's split-environment layout:

```kotlin
loom {
    splitEnvironmentSourceSets()
    mods {
        register("redstonespecs") {
            sourceSet("main")
            sourceSet("client")
        }
    }
}
```

Fabric API's `configureTests` creates the server-side gametest source set;
`clientTest` is wired manually (see [gametest-sourceset-split-wiring.md](gametest-sourceset-split-wiring.md)):

```kotlin
fabricApi {
    configureTests {
        createSourceSet = true
        modId = "redstonespecs-gametest"
        enableGameTests = true
        enableClientGameTests = false   // client side is wired manually
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
cmd.exe /c "gradlew.bat :26.1:test"            # JUnit, no MC runtime
cmd.exe /c "gradlew.bat :26.1:runGameTest"     # server-side @GameTest harness
cmd.exe /c "gradlew.bat :26.1:runClientTest"   # FabricClientGameTest harness
```

The `runGameTest` and `runClientTest` JavaExec tasks are configured with
log4j JVM args in `build.gradle.kts` to surface the `redstonespecs` logger at
DEBUG. `runClientTest` is registered manually (loom no longer auto-creates
`runClientGameTest` since `enableClientGameTests = false`); see
[gametest-sourceset-split-wiring.md](gametest-sourceset-split-wiring.md).

## See also

- [WSL2 invocation](wsl2-gradle-invocation.md)
- [Stonecutter task paths](stonecutter-task-paths.md)
