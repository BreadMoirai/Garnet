---
title: Gametest sourceset split
tags: [build, gradle, loom, gametest, testing]
summary: Split the combined `src/gametest/` sourceset into a server-side `gametest` and a new client-side `clientTest`, with separate test mod-ids, run tasks, and resource manifests.
---

# Gametest sourceset split — design

## Problem

`src/gametest/` currently holds two distinct kinds of tests:

- Server-side `@GameTest` flows (`RedstonespecsGameTests`).
- Full-client `FabricClientGameTest` flows (`RedstonespecsClientTests`), plus shared helpers (`SpecTestContext`).

They share one fabric-loom-managed sourceset, one test mod-id (`redstonespecs-test`), and one `fabric.mod.json`. This conflates two layers that have different runtimes (server vs client), different entrypoints, and different failure modes. It also blocks per-feature test specs that want to tag behaviors `[server-gametest]` vs `[client-gametest]` against actual sourcesets, not conventions.

## Goal

Three sourcesets, each with one purpose:

| Sourceset | Path | Run task | What lives here |
|---|---|---|---|
| `test` | `src/test/kotlin/…` | `test` | Pure JUnit 5 (unchanged) |
| `gametest` | `src/gametest/kotlin/…` | `runGameTest` | Server-side `@GameTest` only |
| `clientTest` | `src/clientTest/kotlin/…` | `runClientTest` | `FabricClientGameTest` flows |

The `test` sourceset is unchanged. The existing `gametest` sourceset keeps its name and run task but loses its client tests. `clientTest` is new.

## Non-goals

- **Per-feature test specs** (`docs/testing/specs/`) — separate work, brainstormed next.
- **New tests** — this is a relocation, not a coverage expansion.
- **Refactoring existing tests** beyond what relocation requires.
- **Kensa (kensa.dev) integration** — orthogonal; only relevant to the JUnit layer, deferred to the test-spec brainstorm.

## Approach

Keep `fabricApi.configureTests` for the server side (loom continues to manage `gametest` sourceset and `runGameTest` task) and manually wire `clientTest` as a sibling sourceset with a hand-written `runClientTest` task that mirrors what loom does for its built-in `runClientGameTest`. Lowest-risk on the working server side; the manual scope is bounded to the new client side.

Two alternatives were considered and rejected:

- *Drop `configureTests` entirely, wire both manually* — discards working loom automation; doubles maintenance.
- *Two `configureTests` calls* — `configureTests` is single-call by design; not supported.

## Build configuration

`build.gradle.kts` changes (sketch — exact `runClientTest` wiring is discovered from loom's `runClientGameTest` at implementation time):

```kotlin
fabricApi {
    configureTests {
        createSourceSet = true
        modId = "redstonespecs-gametest"   // was "redstonespecs-test"
        enableGameTests = true
        enableClientGameTests = false      // was true; client tests move out
        eula = true
    }
}

sourceSets {
    create("clientTest") {
        compileClasspath += sourceSets["main"].output + sourceSets["client"].output
        runtimeClasspath += sourceSets["main"].output + sourceSets["client"].output
    }
}

loom {
    mods.register("redstonespecs-clienttest") {
        sourceSet("clientTest")
    }
}

configurations {
    named("clientTestImplementation") {
        extendsFrom(configurations["clientImplementation"])
    }
}

dependencies {
    "clientTestImplementation"("net.fabricmc.fabric-api:fabric-api:${project.property("fabric_version")}")
    // plus the fabric-client-gametest API package loom normally pulls in for client gametests
}

tasks.register<JavaExec>("runClientTest") {
    group = "fabric"
    jvmArgs(
        "-Dlog4j2.logger.redstonespecs.name=Redstone Specs",
        "-Dlog4j2.logger.redstonespecs.level=DEBUG",
    )
    // mainClass / args / run-config injection mirror loom's runClientGameTest;
    // see implementation plan for the exact wiring.
}
```

The existing `runGameTest` task block keeps its current jvmArgs unchanged.

## Resource and entrypoint split

Today's single `src/gametest/resources/fabric.mod.json` (mod-id `redstonespecs-test`) is split:

- `src/gametest/resources/fabric.mod.json` — mod-id `redstonespecs-gametest`. Server `@GameTest` registration entrypoints only.
- `src/clientTest/resources/fabric.mod.json` — mod-id `redstonespecs-clienttest`. `FabricClientGameTest` entrypoints only.

Each manifest declares only the entrypoints relevant to its runtime. This keeps server-side gametest runs from loading client-only entrypoints and vice versa.

## File migration

Mechanical:

- `src/gametest/kotlin/com/breadmoirai/redstonespecs/test/RedstonespecsClientTests.kt` → `src/clientTest/kotlin/com/breadmoirai/redstonespecs/test/RedstonespecsClientTests.kt`.
- `src/gametest/kotlin/com/breadmoirai/redstonespecs/test/RedstonespecsGameTests.kt` — stays.

### `SpecTestContext` placement (decision deferred to implementation)

`SpecTestContext` is currently shared. Its post-split home depends on actual coupling, audited at implementation time:

1. **Only `clientTest` references it after the move** → live in `src/clientTest/`.
2. **`gametest` still references it** → either (a) duplicate (small enough), (b) keep in `clientTest` and have `gametest` declare an `implementation`/`compileOnly` dep on `clientTest`, or (c) introduce a small `gametestShared` sourceset both depend on.

The implementation plan picks one of (1)/(2a)/(2b)/(2c) based on what the audit shows. Default preference if (2): try (a) first (duplicate) for simplicity; promote to (c) if duplication exceeds a few helpers.

## Documentation updates

- `docs/gametest/unit-vs-gametest-split.md` — extend the decision rule from 2-way (unit / gametest) to 3-way (unit / server-gametest / client-gametest); update file path references; refresh the "Practical guidance" section.
- `docs/architecture/module-map.md` — update the "Client and tests" subsection to list both gametest sourcesets.
- `docs/gametest/INDEX.md` — refresh affected entries; add a new entry only if a dedicated article is warranted (likely just an update to `unit-vs-gametest-split.md` is enough).
- `CLAUDE.md` — no change (it indexes folders, not paths).

## Verification

Run on the active version `:26.1`:

```
cmd.exe /c "./gradlew.bat :26.1:clientClasses :26.1:classes :26.1:gametestClasses :26.1:clientTestClasses :26.1:testClasses"
cmd.exe /c "./gradlew.bat :26.1:test"
cmd.exe /c "./gradlew.bat :26.1:runGameTest"
cmd.exe /c "./gradlew.bat :26.1:runClientTest"
```

Pass criteria:

- All four commands exit 0.
- The exact same set of tests that ran under the old combined `gametest` sourceset still run, just relocated; no test is dropped or skipped.
- No regressions in `runGameTest` (server tests still pass with the same outcomes as before the split).
- `runClientTest` runs the relocated `RedstonespecsClientTests` with the same outcomes as it had under the old `runClientGameTest`.

## Risks

- **`runClientTest` wiring drift** — manually-registered task may diverge from what loom does in `runClientGameTest`. Mitigation: at implementation time, inspect loom's task config (`tasks.named("runClientGameTest").get()` properties: mainClass, args, classpath, system properties, run-config injection) and mirror them rather than guessing. Document any non-trivial mirrored properties as code comments only where the *why* isn't obvious.
- **Test mod-id rename breaking existing fabric.mod.json references** — the rename from `redstonespecs-test` to `redstonespecs-gametest` is a contract change visible in any place that names the test mod-id (e.g. resource lookups, integration with test discovery). Audit grep for `redstonespecs-test` before merging.
- **`SpecTestContext` coupling worse than expected** — if many helpers turn out to be shared, option (c) (`gametestShared` sourceset) may be required, expanding scope. Acceptable: still smaller than refactoring tests themselves.
