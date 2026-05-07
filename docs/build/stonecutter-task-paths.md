---
title: Stonecutter Task Paths and the Active Version
tags: [stonecutter, gradle, multi-version]
summary: Tasks are exposed as `:26.1:compileKotlin` (not `:versions:26.1:...`); active version is set in `stonecutter.gradle.kts` via `stonecutter active "26.1"`.
---

# Stonecutter Task Paths and the Active Version

This project uses [Stonecutter](https://stonecutter.kikugie.dev/) to manage
multiple Minecraft versions from a single source tree. Stonecutter exposes each
declared version as a Gradle subproject.

## Task path prefix

Per-version tasks are reached as `:<version>:<task>`:

```sh
cmd.exe /c "gradlew.bat :26.1:compileKotlin"
cmd.exe /c "gradlew.bat :26.1:runGameTest"
cmd.exe /c "gradlew.bat :26.1:build"
```

**Do not** use `:versions:26.1:...`. The `versions/` directory on disk is just
where Stonecutter materializes the per-version build dirs; the Gradle project
path is `:26.1` directly. Generic Stonecutter examples sometimes show the
`versions:` form — it does not match this project's settings.

## Where the active version comes from

`settings.gradle.kts` declares the version set:

```kotlin
stonecutter {
    create(rootProject) {
        versions("26.1")
        vcsVersion = "26.1"
    }
}
```

`stonecutter.gradle.kts` selects the currently active version:

```kotlin
stonecutter active "26.1"
```

The active version is what root-level tasks (e.g. `compileKotlin` without a
prefix) implicitly target, and what the IDE imports.

## Switching versions

Stonecutter generates a `stonecutterSwitchTo<Version>` task per declared
version. For 26.1:

```sh
cmd.exe /c "gradlew.bat stonecutterSwitchTo26.1"
```

This project also wires a finalizer:

```kotlin
afterEvaluate {
    tasks.findByName("stonecutterSwitchTo26.1")?.finalizedBy("restoreUnnamedVars")
}
```

The finalizer restores JDK 22+ unnamed-variable syntax (`_`) that
`transformUnnamedVars` rewrites to `unusedN` for older JDKs. If you add a new
version, mirror that wiring or you'll end up with `unusedN` identifiers
committed to source.

## Adding a version (sketch)

1. Add it to `versions(...)` in `settings.gradle.kts`.
2. Update the JDK selection in `build.gradle.kts` (`requiredJava` switch on
   `sc.current.parsed`).
3. Add a `versions/<new>/gradle.properties` overlay if dependency versions
   differ.
4. If targeting a JDK below 22, ensure `transformUnnamedVars` runs before
   compile and `restoreUnnamedVars` runs when switching back to 26.1.

## See also

- [WSL2 invocation](wsl2-gradle-invocation.md)
- [Local verification commands](local-verification-commands.md)
