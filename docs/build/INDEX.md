# Build

Multi-version build setup. Stonecutter task paths, gradle wrappers, fabric/loom configuration, and dependency choices.

**Tags:** gradle, stonecutter, fabric, loom, deps, wsl

## Articles

- [Invoking Gradle from WSL2 on Windows](wsl2-gradle-invocation.md) — Use `cmd.exe /c "gradlew.bat ..."`; why `./gradlew` from WSL is fragile against a Windows-resident project. Tags: gradle, wsl, windows, wrapper.
- [Stonecutter Task Paths and the Active Version](stonecutter-task-paths.md) — Tasks are `:26.1:<task>` (not `:versions:26.1:...`); how the active version is declared and switched. Tags: stonecutter, gradle, multi-version.
- [Local Compile Verification Across All Source Sets](local-verification-commands.md) — Why `compileKotlin` is insufficient; run `clientClasses classes gametestClasses clientTestClasses testClasses` to cover main/client/gametest/clientTest/test. Tags: gradle, source-sets, verification, loom.
- [Gametest sourceset split — manual wiring](gametest-sourceset-split-wiring.md) — Why `clientTest` is wired by hand instead of via `enableClientGameTests = true`, and the five non-obvious build pieces that must be present together for `runClientTest` to work after a clean. Tags: gradle, loom, fabric, gametest, source-sets.
- [testBridge source set wiring](test-bridge-source-set.md) — Why it's a source set, not a subproject; how gametest and clientTest pull it in (incl. the afterEvaluate patch for loom-created gametest). Tags: gradle, source-sets, testing.
