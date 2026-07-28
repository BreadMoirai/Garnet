---
title: Scoping the Compose runtime to client-only via pluginClasspath stripping
tags: [gradle, compose, stonecutter, dependencies, scoping]
summary: kotlin("plugin.compose") applies project-wide, so its VersionChecker fails any KotlinCompile lacking the Compose runtime; stripping the compose subplugin from non-client KotlinCompile tasks' pluginClasspath lets runtime-desktop stay clientImplementation-scoped, keeping it out of the server jar.
---

# Scoping the Compose runtime to client-only via pluginClasspath stripping

`kotlin("plugin.compose")` is applied once, in the root `plugins { }` block, so by
default the Compose compiler subplugin runs on **every** `KotlinCompile` task in the
(single) Gradle module — `main`, `client`, `test`, `gametest`, `clientTest`. Its
`VersionChecker` throws `IncompatibleComposeRuntimeVersionException` ("The Compose
Compiler requires the Compose Runtime to be on the class path, but none could be
found") for any compilation that lacks the Compose *runtime* jar on its classpath,
even ones with no `@Composable` code at all — which meant `runtime-desktop` had to
sit on the base `implementation` configuration (extended by every source set) purely
to satisfy the checker on source sets that never use Compose.

## What was tried

1. **Move `runtime-desktop` to `clientImplementation`, change nothing else.**
   Fails as predicted: `compileKotlin` (main) throws the `VersionChecker` error above.
   `:26.2:clientClasses :26.2:classes :26.2:gametestClasses :26.2:clientTestClasses
   :26.2:testClasses` fails at `:26.2:compileKotlin`.

2. **Strip the Compose compiler subplugin from the non-client `KotlinCompile` tasks'
   `pluginClasspath`.** In `build.gradle.kts`, after the `dependencies { }` block:

   ```kotlin
   listOf("compileKotlin", "compileTestKotlin", "compileGametestKotlin").forEach { name ->
       tasks.findByName(name)?.let { t ->
           (t as org.jetbrains.kotlin.gradle.tasks.KotlinCompile).pluginClasspath.setFrom(
               t.pluginClasspath.filter { !it.name.contains("compose") }
           )
       }
   }
   ```

   **This worked**, including after `:26.2:clean` (ruled out incremental-build/
   up-to-date artifacts as a false positive). With this block in place and
   `runtime-desktop` on `clientImplementation`, all five source sets
   (`main`/`client`/`gametest`/`clientTest`/`test`) compile clean.

This was expected to be brittle/unsupported (per the original task brief, which
anticipated a Gradle-UI-submodule split as the only real fix) — `pluginClasspath` is a
public, mutable `ConfigurableFileCollection` property on `KotlinCompile` in the Kotlin
Gradle plugin version this project pins, and filtering jars whose filename contains
`"compose"` out of it before task execution reliably removes the subplugin from
`compileKotlin`/`compileTestKotlin`/`compileGametestKotlin` without affecting
`compileClientKotlin`/`compileClientTestKotlin` (left untouched, still carry the
Compose subplugin and the runtime).

## Current state

- `runtime-desktop`, `ui-desktop`, `foundation-desktop` are all `clientImplementation`
  — none of the Compose Multiplatform jars ship in the server jar.
- The pluginClasspath-stripping block lives directly below the `dependencies { }`
  block in `build.gradle.kts`, immediately before the `tasks { }` block.
- Compose is pinned to **`1.11.0` (stable)**, with skiko `0.144.6` — down from the
  original feasibility spike's `1.12.0-beta02`/`0.150.1` pre-release pins. The move
  happened because the jewel-widget-layer migration added JetBrains Jewel
  (`jewel-int-ui-standalone:0.39.1-262.9437.29`) as the dock's widget library, and
  Jewel is built against Compose `1.11.0`; the Compose runtime, the skiko native, and
  Jewel all move as one triple, since Jewel forces the Compose version and Compose
  forces the skiko version. See
  [ui/jewel-widget-layer.md](../ui/jewel-widget-layer.md).
- Jewel (`jewel-int-ui-standalone`) and the IntelliJ icon-artwork artifact
  (`com.jetbrains.intellij.platform:icons`, needed because Jewel's own artifacts ship
  the `AllIconsKeys` catalog but not the SVGs — see
  [ui/jewel-widget-layer.md](../ui/jewel-widget-layer.md#icons-need-two-artifacts-and-one-of-them-isnt-on-central))
  are also declared `clientImplementation`, following the same scoping this article
  describes for the base Compose runtime — they compile only under `compileClientKotlin`,
  which still carries the (unstripped) Compose subplugin.

## Caveats / fragility

- This relies on `KotlinCompile.pluginClasspath` being a mutable, settable property
  and on Compose subplugin artifacts being identifiable by `"compose"` appearing in
  the jar filename — both are Kotlin-Gradle-plugin-version-specific implementation
  details, not a supported API contract. A Kotlin/Compose-plugin upgrade could
  silently break this (most likely failure mode: the strip becomes a no-op and the
  `VersionChecker` error from attempt #1 comes back — re-run the Step 2 build from the
  task-0 brief to check).
- If this ever breaks and can't be fixed by adjusting the filter predicate, the
  fallback is what the original brief called "the real fix": move the Compose UI into
  its own Gradle submodule so the compiler plugin applies only there. That is a build
  restructure orthogonal to the panel framework.
