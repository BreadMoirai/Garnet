---
title: Invoking Gradle from WSL2 on Windows
tags: [gradle, wsl, windows, wrapper]
summary: Run `cmd.exe /c "gradlew.bat ..."` instead of `./gradlew` because the project lives on a Windows drive under WSL2.
---

# Invoking Gradle from WSL2 on Windows

This repository lives on a Windows filesystem (`H:\Repo\RedstoneSpecs`) but is
typically edited from a WSL2 shell. **Always invoke Gradle through the Windows
batch wrapper, not the POSIX wrapper.**

## The command

```sh
cmd.exe /c "gradlew.bat <tasks...>"
```

Or, when the working directory may differ:

```sh
cmd.exe /c "cd /d H:\\Repo\\RedstoneSpecs && gradlew.bat <tasks...>"
```

## Why not `./gradlew`?

`./gradlew` is the POSIX shell wrapper. Running it from WSL2 against a project
on `/mnt/h/...` works in some configurations but is fragile here:

- **Toolchain paths.** The project's JDK, Loom caches, and Stonecutter caches
  resolve to Windows paths (e.g. `H:\Repo\...\.gradle\loom-cache\...`). When
  Gradle runs under WSL it produces Linux-style paths, which then don't match
  cached entries created by Windows-side tooling (IDE, prior builds), causing
  redundant resolves and occasional path-comparison failures.
- **File locking.** Loom and Fabric Loom remap caches use Windows file locking
  semantics. Mixing WSL and Windows daemons against the same `.gradle/`
  directory has produced stuck remap tasks.
- **Daemon reuse.** The Gradle daemon is keyed by JVM and working directory.
  Two daemons (one per OS) on the same project waste memory and can hold
  conflicting locks.

Invoking via `cmd.exe /c` runs the build inside the Windows shell with Windows
paths end-to-end, matching how the IDE runs it.

## Common pitfall

Subagent prompts and runbooks sometimes paste `./gradlew :26.2:compileKotlin`
copied from generic Stonecutter docs. Translate every such command to the
`cmd.exe /c "gradlew.bat ..."` form before running.

## See also

- [Stonecutter task paths](stonecutter-task-paths.md) — the `:26.2:` prefix to use
- [Local verification command set](local-verification-commands.md) — which tasks
  to actually run
