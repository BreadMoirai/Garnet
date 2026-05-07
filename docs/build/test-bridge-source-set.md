---
title: testBridge source set wiring
tags: [gradle, source-sets, testing]
summary: Why testBridge is a source set rather than a Gradle subproject; how gametest and clientTest consume it.
---

# testBridge source set wiring

## Why a source set, not a Gradle subproject

A Gradle subproject was the natural first instinct — testBridge is a distinct body of code depended on by other source sets, which is exactly the subproject use case. It was ruled out because:

- **Stonecutter multi-version complexity.** Stonecutter assumes a flat project layout where all versions are children of the root. Introducing a real subproject would need to participate in Stonecutter's version switching or maintain its own version graph — neither is trivial.
- **Loom entanglement.** The bridge needs access to MC/Fabric API classes on the compile classpath (it imports `MinecraftServer`, `BlockPos`, Fabric event types). A subproject that depends on Loom's deobfuscated jars requires its own Loom configuration, which multiplies the configuration surface.
- **No real subprojects exist yet.** Adding the first subproject also means establishing conventions (settings.gradle, artifact naming, multi-project dependency syntax) that don't yet exist. Source-set wiring reuses patterns already in the build for `clientTest`.

Source sets share the root project's Loom configuration and dependency resolution, so testBridge gets the MC/Fabric classpath for free.

## Source set definitions

`testBridge` and `clientTest` are declared early in `build.gradle.kts` before `loom { }`:

```kotlin
sourceSets {
    create("testBridge") {
        compileClasspath += sourceSets["main"].output +
            sourceSets["client"].output +
            sourceSets["client"].compileClasspath
        runtimeClasspath += sourceSets["main"].output +
            sourceSets["client"].output +
            sourceSets["client"].runtimeClasspath
    }
    create("clientTest") {
        compileClasspath += sourceSets["main"].output +
            sourceSets["client"].output +
            sourceSets["client"].compileClasspath +
            sourceSets["testBridge"].output
        runtimeClasspath += sourceSets["main"].output +
            sourceSets["client"].output +
            sourceSets["client"].runtimeClasspath +
            sourceSets["testBridge"].output
    }
}
```

`clientTest` includes `testBridge`'s output directly in its compile and runtime classpaths so the bridge classes are visible without any extra configuration per-test.

## Configuration extension chains

Gradle generates a `<sourceSet>Implementation` configuration for each source set. Extending these makes transitive dependencies flow automatically:

```kotlin
configurations {
    named("testBridgeImplementation") {
        extendsFrom(configurations["clientImplementation"])
    }
    // ...compileOnly, runtimeOnly similarly

    named("clientTestImplementation") {
        extendsFrom(configurations["clientImplementation"])
        extendsFrom(configurations["testBridgeImplementation"])  // pulls in Kotest, Kensa, coroutines
    }
    // ...compileOnly, runtimeOnly similarly
}
```

`clientTestImplementation extendsFrom testBridgeImplementation` is the key line: it means any dep declared under `testBridgeImplementation` (Kotest runner, Kotest assertions, Kensa, kotlinx-coroutines) is automatically on the `clientTest` compile and runtime classpaths.

## The `afterEvaluate` patch for `gametest`

Loom creates the `gametest` source set lazily during project evaluation (triggered by `fabricApi.configureTests { enableGameTests = true }`). Because it doesn't exist at `sourceSets { }` declaration time, the bridge output and configuration extensions must be wired in `afterEvaluate`:

```kotlin
afterEvaluate {
    sourceSets.findByName("gametest")?.let { gt ->
        gt.compileClasspath += sourceSets["testBridge"].output
        gt.runtimeClasspath += sourceSets["testBridge"].output
    }
    configurations.findByName("gametestImplementation")
        ?.extendsFrom(configurations["testBridgeImplementation"])
    configurations.findByName("gametestCompileOnly")
        ?.extendsFrom(configurations["testBridgeCompileOnly"])
    configurations.findByName("gametestRuntimeOnly")
        ?.extendsFrom(configurations["testBridgeRuntimeOnly"])
}
```

`findByName` guards are used rather than `named(...)` because if `enableGameTests` is ever turned off, these blocks become no-ops instead of erroring.

## Where dependencies are declared

All Kotest, Kensa, and coroutines dependencies are declared on `testBridgeImplementation` in the root `dependencies { }` block:

```kotlin
"testBridgeImplementation"("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.1")
"testBridgeImplementation"("io.kotest:kotest-runner-junit5:5.9.1")
"testBridgeImplementation"("io.kotest:kotest-assertions-core:5.9.1")
"testBridgeImplementation"("dev.kensa:kensa-framework-junit:0.5.10")
"testBridgeImplementation"("dev.kensa:kensa-assertions-kotest:0.5.10")
```

Both `clientTestImplementation` and `gametestImplementation` inherit these via `extendsFrom`. The `test` source set pulls in the bridge output directly:

```kotlin
testImplementation(sourceSets["testBridge"].output)
```

Unit tests in `src/test/` get the bridge classes on their classpath but use the standard `testImplementation` configuration rather than `extendsFrom testBridgeImplementation` — Kotest's JUnit Platform engine is already on `testImplementation` transitively through `kotest-runner-junit5`.
