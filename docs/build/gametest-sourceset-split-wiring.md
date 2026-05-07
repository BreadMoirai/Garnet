---
title: Gametest sourceset split — manual wiring
tags: [gradle, loom, fabric, gametest, source-sets]
summary: Why `clientTest` is wired by hand instead of via `enableClientGameTests = true`, and the five non-obvious build pieces that need to be present together for `runClientTest` to work after a clean.
---

# Gametest sourceset split — manual wiring

Fabric loom's `fabricApi.configureTests { enableClientGameTests = true }` creates a single combined gametest sourceset that hosts both server `@GameTest` and client `FabricClientGameTest` flows. We split those into two sourcesets — `gametest` (server) and `clientTest` (client) — to keep the layers cleanly testable in isolation. Loom owns the server side as before; the client side is wired by hand.

The full build.gradle.kts changes are visible in commits `ac772ca` and `148d330`. This article explains the **why** behind the five pieces that have to be present together. Removing any one breaks the build in a non-obvious way.

## 1. `enableClientGameTests = false`

Disabling this flag is the single switch that turns off loom's auto-wiring for the client side. Side-effects:

- Loom stops creating the `runClientGameTest` task.
- Loom stops adding `fabric-client-gametest-api-v1` to the gametest sourceset's classpath.
- Loom stops generating per-task argFiles for `runClientGameTest`.

Everything else in this article exists to replace those side-effects on the new `clientTest` sourceset.

## 2. Manual `clientTest` sourceset with full classpath access

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

The pitfall: `sourceSets["client"].output` only adds the *compiled output* of `client` — it does **not** include the dependency JARs that `client` resolves (Minecraft itself, fabric-api, etc.). If you only `+= sourceSets["client"].output`, code that references `Minecraft` or any client-only MC class fails to compile in `clientTest` with a confusing "unresolved reference: Minecraft" error.

`sourceSets["client"].compileClasspath` carries those resolved dependencies, so adding it pulls in the full client view of MC.

## 3. Three configuration extensions, not just `Implementation`

```kotlin
configurations {
    named("clientTestImplementation") { extendsFrom(configurations["clientImplementation"]) }
    named("clientTestCompileOnly")    { extendsFrom(configurations["clientCompileOnly"]) }
    named("clientTestRuntimeOnly")    { extendsFrom(configurations["clientRuntimeOnly"]) }
}
```

Gradle's JavaPlugin auto-creates `<set>Implementation`, `<set>CompileOnly`, and `<set>RuntimeOnly` for each sourceset. We extend all three so a future dependency added to any of `clientImplementation` / `clientCompileOnly` / `clientRuntimeOnly` flows transitively into `clientTest`. Extending only `Implementation` would silently skip compile-only and runtime-only deps, which surfaces as a missing class at random in the future.

## 4. Explicit `fabric-client-gametest-api-v1` dependency

```kotlin
dependencies {
    "clientTestImplementation"(
        fabricApi.module("fabric-client-gametest-api-v1", project.property("fabric_version") as String)
    )
}
```

When `enableClientGameTests = true`, loom adds this module to the combined gametest sourceset's classpath silently. With it disabled, the module is absent unless we add it explicitly. Symptoms of forgetting it: `ClientGameTestContext`, `TestSingleplayerContext`, `waitTick`, `runOnClient` all fail to resolve in `clientTest` sources.

`fabricApi.module(name, version)` is loom's helper that resolves a single fabric-api submodule (fabric-api is a meta-jar; you usually want individual modules).

## 5. Test mod-id split

Two `fabric.mod.json` manifests, one per sourceset's `resources/`:

| Sourceset    | Manifest                              | mod-id                       | entrypoint                  |
|--------------|---------------------------------------|------------------------------|-----------------------------|
| `gametest`   | `src/gametest/resources/fabric.mod.json` | `redstonespecs-gametest`     | `fabric-gametest`           |
| `clientTest` | `src/clientTest/resources/fabric.mod.json` | `redstonespecs-clienttest`   | `fabric-client-gametest`    |

`configureTests { modId = "redstonespecs-gametest" }` must match the server manifest's `id`. The client manifest's `id` is referenced from `loom.mods.register("redstonespecs-clienttest") { sourceSet("clientTest") }`, which is what tells loom to include `clientTest`'s outputs in `launch.cfg`'s `fabric.classPathGroups` for any client run.

A single combined mod-id (the original `redstonespecs-test`) cannot work after the split because it would force both entrypoints to share a runtime, which defeats the isolation goal.

## 6. Manual `runClientTest` task with `dependsOn("generateDLIConfig")`

```kotlin
register<JavaExec>("runClientTest") {
    val clientTestSrc = sourceSets["clientTest"]
    classpath = clientTestSrc.runtimeClasspath
    mainClass.set("net.fabricmc.devlaunchinjector.Main")
    workingDir = project.layout.projectDirectory.asFile

    val launchCfg = project.layout.projectDirectory
        .dir(".gradle/loom-cache").file("launch.cfg").asFile
    val testResources = clientTestSrc.resources.srcDirs.first()

    jvmArgumentProviders.add(CommandLineArgumentProvider {
        listOf(
            "-Dfabric.dli.config=${launchCfg.absolutePath}",
            "-Dfabric.dli.env=client",
            "-Dfabric.client.gametest",
            "-Dfabric.client.gametest.testModResourcesPath=${testResources.absolutePath}",
            "-Dfabric.dli.main=net.fabricmc.loader.impl.launch.knot.KnotClient",
            "--sun-misc-unsafe-memory-access=allow",
            "--enable-native-access=ALL-UNNAMED",
        )
    })

    jvmArgs(
        "-Dlog4j2.logger.redstonespecs.name=Redstone Specs",
        "-Dlog4j2.logger.redstonespecs.level=DEBUG",
    )

    dependsOn("clientTestClasses", "generateDLIConfig")
}
```

The flag set was captured from loom's old `runClientGameTest` task (see `versions/26.1/build/loom-cache/argFiles/runClientGameTest` and the `gradle :tasks --all` dump used during the split). Two non-obvious points:

- **`testModResourcesPath` points at `src/clientTest/resources`.** Loom's old task pointed at `src/gametest/resources` because that was where the combined manifest lived. After the split the client manifest lives in `clientTest`, and this flag is what tells fabric-loader where to find it at runtime. `clientTestSrc.resources.srcDirs.first()` resolves to that path via Gradle's default convention.
- **`dependsOn("generateDLIConfig")` is mandatory.** `launch.cfg` is read at JVM launch from `.gradle/loom-cache/launch.cfg`. It is produced as a side-effect of loom's `generateDLIConfig` task. Without this dependency, `runClientTest` works incidentally — because any prior loom run leaves `launch.cfg` on disk — but `gradlew clean :26.1:runClientTest` fails with a confusing JVM "config file not found" error. Confirmed by `gradlew :26.1:runClientTest --dry-run`: with the dep, the chain is `clientTestClasses → generateLog4jConfig → generateDLIConfig → runClientTest`.

The classpath argFile that loom generates for its own run tasks (e.g. `build/loom-cache/argFiles/runClientGameTest`) is not needed here — Gradle's `JavaExec` builds its own classpath from `task.classpath`, including its own internal argfile if the classpath is too long.

## See also

- [unit-vs-gametest-split.md](../gametest/unit-vs-gametest-split.md) — the decision rule for which test layer a given piece of logic belongs in.
- [local-verification-commands.md](local-verification-commands.md) — the canonical compile and run commands across all five sourcesets.
