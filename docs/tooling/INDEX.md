# Tooling

Build and developer tooling. Multi-version build setup, Stonecutter task paths, gradle wrappers,
fabric/loom configuration, dependency choices, and the local docs-search index.

**Tags:** gradle, stonecutter, fabric, loom, deps, wsl, tooling, docs

> Named `tooling/`, not `build/`: qmd (the docs search index) hardcodes an exclude for any
> directory named `build`, so articles here would be permanently unsearchable under that name.

## Articles

- [Recovering lost Kotlin source from build output](recovering-source-from-build-output.md) — When uncommitted source is lost, `versions/<mcver>/build/classes/kotlin/**` still holds the last compile; decompile it with the Vineflower jar already in the Windows Gradle cache, verify with the tests written against the lost version, and expect KDoc not to survive. Tags: gradle, tooling, recovery, decompiler.
- [Invoking Gradle from WSL2 on Windows](wsl2-gradle-invocation.md) — Use `cmd.exe /c "gradlew.bat ..."`; why `./gradlew` from WSL is fragile against a Windows-resident project, and how the choice decides which Skiko native resolves. Tags: gradle, wsl, windows, wrapper.
- [Stonecutter Task Paths and the Active Version](stonecutter-task-paths.md) — Tasks are `:26.2:<task>` (not `:versions:26.2:...`); how the active version is declared and switched. Tags: stonecutter, gradle, multi-version.
- [Local Compile Verification Across All Source Sets](local-verification-commands.md) — Why `compileKotlin` is insufficient; run `clientClasses classes gametestClasses clientTestClasses testClasses` to cover main/client/gametest/clientTest/test; and why `:26.2:test --tests` doesn't select Kotest specs, so run `:26.2:test` unfiltered and read the per-class JUnit XML report instead. Tags: gradle, source-sets, verification, loom, kotest.
- [Retired — split-wiring](gametest-sourceset-split-wiring.md) — Retired; merged into main. Tags: retired.
- [Retired — testBridge source set wiring](test-bridge-source-set.md) — Retired; testBridge dissolved into main in Plan A (2026-05-07). Tags: retired.
- [Docs semantic search (qmd)](docs-search.md) — How `docs/` is indexed by qmd, why the category is `tooling/` and not `build/`, how the reindex hooks fire, and how to rebuild the index from scratch. Tags: tooling, docs, search, hooks, claude, qmd.
- [Scoping the Compose runtime to client-only via pluginClasspath stripping](compose-runtime-scoping.md) — Compose compiler plugin applies project-wide and its VersionChecker needs the runtime on every compilation unless the subplugin is stripped from non-client KotlinCompile tasks' pluginClasspath; that strip works, keeping runtime-desktop clientImplementation-scoped. Tags: gradle, compose, stonecutter, dependencies, scoping.
- [Package Move Mechanics](package-move-mechanics.md) — Gotchas for moving files between packages across all six source sets without silently breaking discovery or content. Tags: gradle, kotlin, refactor, package-move, sed, verification, imports.
