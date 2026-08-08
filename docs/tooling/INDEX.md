# Tooling

Build and developer tooling. Multi-version build setup, Stonecutter task paths, gradle wrappers,
fabric/loom configuration, dependency choices, and the local docs-search index.

**Tags:** gradle, stonecutter, fabric, loom, deps, wsl, tooling, docs

> Named `tooling/`, not `build/`: qmd (the docs search index) hardcodes an exclude for any
> directory named `build`, so articles here would be permanently unsearchable under that name.

## Articles

- [Invoking Gradle from WSL2 on Windows](wsl2-gradle-invocation.md) — Use `cmd.exe /c "gradlew.bat ..."`; why `./gradlew` from WSL is fragile against a Windows-resident project, and how the choice decides which Skiko native resolves. Tags: gradle, wsl, windows, wrapper.
- [Stonecutter Task Paths and the Active Version](stonecutter-task-paths.md) — Tasks are `:26.2:<task>` (not `:versions:26.2:...`); how the active version is declared and switched. Tags: stonecutter, gradle, multi-version.
- [Local Compile Verification Across All Source Sets](local-verification-commands.md) — Why `compileKotlin` is insufficient; run `clientClasses classes gametestClasses clientTestClasses testClasses` to cover main/client/gametest/clientTest/test. Tags: gradle, source-sets, verification, loom.
- [Retired — split-wiring](gametest-sourceset-split-wiring.md) — Retired; merged into main. Tags: retired.
- [Retired — testBridge source set wiring](test-bridge-source-set.md) — Retired; testBridge dissolved into main in Plan A (2026-05-07). Tags: retired.
- [Docs semantic search (qmd)](docs-search.md) — How `docs/` is indexed by qmd, why the category is `tooling/` and not `build/`, how the reindex hooks fire, and how to rebuild the index from scratch. Tags: tooling, docs, search, hooks, claude, qmd.
- [Scoping the Compose runtime to client-only via pluginClasspath stripping](compose-runtime-scoping.md) — Compose compiler plugin applies project-wide and its VersionChecker needs the runtime on every compilation unless the subplugin is stripped from non-client KotlinCompile tasks' pluginClasspath; that strip works, keeping runtime-desktop clientImplementation-scoped. Tags: gradle, compose, stonecutter, dependencies, scoping.
