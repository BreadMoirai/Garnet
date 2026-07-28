---
title: Locating and extracting decompiled MC source jars
tags: [mc-api, tooling, reference]
summary: Decompiled vanilla sources live in .gradle/loom-cache; extract them from WSL via cmd.exe's jar tool against a Windows-pathable temp directory.
---

# Locating and extracting decompiled MC source jars

Loom decompiles vanilla Minecraft into source jars under the project's
`.gradle/loom-cache`. They are the source of truth when memory or docs disagree
with current behavior.

## Where the jars live

Under `.gradle/loom-cache/minecraftMaven/net/minecraft/` there are four
artifact roots (one pair per stonecutter version slice):

- `minecraft-common-<hash>/<mcver>/minecraft-common-<hash>-<mcver>-sources.jar`
  — server/common classes: blocks, redstone, gametest framework, level, etc.
- `minecraft-clientOnly-<hash>/<mcver>/minecraft-clientOnly-<hash>-<mcver>-sources.jar`
  — client-only classes: renderers, GUI, screens. Notably does **not** contain
  block classes like `ComparatorBlock` or `RedStoneWireBlock` — those are in
  `minecraft-common`.

For 26.2 the common sources jar is at:
`/mnt/h/Repo/RedstoneSpecs/.gradle/loom-cache/minecraftMaven/net/minecraft/minecraft-common-a89b853bf9/26.2/minecraft-common-a89b853bf9-26.2-sources.jar`
(the `<hash>` differs between the compiled and `-sources` jars, and changes when the MC version
or mappings change — glob `*26.2*sources.jar` under `loom-cache` rather than hard-coding it).

## Extracting from WSL

The repo lives on a Windows drive accessed from WSL. `unzip` is not installed
in the default WSL image, but Windows ships a `jar` tool that can extract zip
archives. The catch: `jar` invoked through `cmd.exe` cannot read paths under
`/tmp/` (UNC path issue from `cmd.exe`'s point of view).

Use `/mnt/c/temp/` as the staging directory:

```bash
mkdir -p /mnt/c/temp/mcsrc
cp /mnt/h/Repo/RedstoneSpecs/.gradle/loom-cache/.../minecraft-common-...-sources.jar \
   /mnt/c/temp/mcsrc.jar
cd /mnt/c/temp/mcsrc
cmd.exe /c "jar xf C:\\temp\\mcsrc.jar net/minecraft/world/level/block/DiodeBlock.java"
```

Pass specific entries to `jar xf` to avoid extracting tens of thousands of
files. Use forward slashes inside the jar entry path; backslashes only in the
Windows path to the jar itself.

## Key class index for redstone work

| Class | Path inside jar |
|---|---|
| `DiodeBlock` (base for repeater/comparator) | `net/minecraft/world/level/block/DiodeBlock.java` |
| `ComparatorBlock` | `net/minecraft/world/level/block/ComparatorBlock.java` |
| `ButtonBlock` (incl. `press`) | `net/minecraft/world/level/block/ButtonBlock.java` |
| `RedStoneWireBlock` (note capital S) | `net/minecraft/world/level/block/RedStoneWireBlock.java` |
| `DefaultRedstoneWireEvaluator` | `net/minecraft/world/level/redstone/DefaultRedstoneWireEvaluator.java` |
| `GameTestHelper` | `net/minecraft/gametest/framework/GameTestHelper.java` |
| `MinecraftServer` (tick order) | `net/minecraft/server/MinecraftServer.java` |
| `Level` (declares `setBlock`) | `net/minecraft/world/level/Level.java` |

## Notes verified against current source

- `Level.setBlock(BlockPos, BlockState, int)` is the 3-arg overload that
  delegates to a 4-arg variant with `updateLimit=512`. The recorder mixin
  hooks the 3-arg HEAD/RETURN; nested `setBlock` calls return inner-first, so
  recorded `tickOrder=0` is the inner-most change.
- `ButtonBlock.press(state, level, pos, player?)` sets `POWERED=true`, fires
  neighbor updates, **and** calls `level.scheduleTick(pos, this, ticksToStayPressed)`.
  Replaying a press via raw `setBlock` skips the schedule and breaks
  downstream timing — use `press` for player-input replay.
