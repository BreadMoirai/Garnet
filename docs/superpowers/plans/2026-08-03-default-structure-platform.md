# Default Platform for New Structures — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** New `.nbt` structures are created containing a configurable platform — by default a 3×3 layer of `minecraft:smooth_stone` — instead of being empty.

**Architecture:** Three new `SharedSettings` fields round-tripped through `ModConfig`'s `garnet.json`; a new pure function `DefaultPlatform.platformTag` that builds the structure NBT by hand (no `Level` needed); `EditorNewStructure.create` writes that tag instead of an empty `StructureTemplate`. Placement is untouched — the existing `anchorY`/`projectGridYBase` logic already floors a 1-tall structure at y=64.

**Tech Stack:** Kotlin, Minecraft 26.2 (Fabric), Stonecutter multi-version build, Kotest (unit + gametest).

**Spec:** `docs/superpowers/specs/2026-08-03-default-structure-platform-design.md`

## Global Constraints

- **MC version slice is 26.2 only.** Every Gradle task path is `:26.2:<task>`, NOT `:versions:26.2:<task>`.
- **Always invoke Gradle as `cmd.exe /c "gradlew.bat ..."`** from the repo root. No `./` prefix — cmd.exe cannot parse it. The build runs on Windows through WSL interop.
- **Compile check (all five source sets):**
  `cmd.exe /c "gradlew.bat :26.2:classes :26.2:clientClasses :26.2:testClasses :26.2:gametestClasses :26.2:clientTestClasses"`
- **Unit tests:** `cmd.exe /c "gradlew.bat :26.2:test"` — run **unfiltered**. Gradle's `--tests` filter does not work with Kotest and reports a false "No tests found". Read results from `versions/26.2/build/test-results/test/TEST-<fqcn>.xml`.
- **Gametests:** `cmd.exe /c "gradlew.bat :26.2:runGameTest"`. This run **hangs after printing its summary** — redirect to a log file, poll the log, then kill the process. Never pipe through `grep` (it buffers into an empty file).
- **Source sets and `internal`:** Kotlin `internal` is per-source-set. `src/gametest` cannot see `internal` declarations in `src/main`. Everything this plan adds to `src/main` must be public.
- **`SharedSettings` is global mutable state.** Any test that mutates it MUST restore the previous value in a `finally` block. This is an established convention in `ModConfigTest` (`SettingsSnapshot`) and the gametest specs.
- **Commit style:** commit directly to `main` (this project's workflow — no feature branches). Do NOT add a `Co-Authored-By: Claude` trailer or any Claude attribution.

## File Structure

| File | Status | Responsibility |
|---|---|---|
| `src/main/kotlin/com/breadmoirai/garnet/config/SharedSettings.kt` | Modify | Three new platform fields |
| `src/client/kotlin/com/breadmoirai/garnet/config/ModConfig.kt` | Modify | `garnet.json` round-trip for the new fields |
| `src/main/kotlin/com/breadmoirai/garnet/editor/ops/DefaultPlatform.kt` | **Create** | Pure NBT builder for the platform template |
| `src/main/kotlin/com/breadmoirai/garnet/editor/ops/EditorNewStructure.kt` | Modify | Write the platform tag instead of an empty template |
| `src/test/kotlin/com/breadmoirai/garnet/client/config/ModConfigTest.kt` | Modify | Round-trip coverage for the new keys |
| `src/test/kotlin/com/breadmoirai/garnet/editor/ops/DefaultPlatformTest.kt` | **Create** | Unit coverage for the NBT builder |
| `src/test/kotlin/com/breadmoirai/garnet/editor/ops/EditorNewStructureTest.kt` | Modify | The created file carries the platform |
| `src/gametest/kotlin/com/breadmoirai/garnet/test/editor/EditorStructureNetworkSpec.kt` | Modify | End-to-end: created → placed → smooth_stone at y=64 |
| `docs/use-cases/structure-lifecycle.md` | Modify | UC-MAN-10.d no longer writes an "empty" `.nbt` |
| `docs/architecture/redstone-project.md` | Modify | Same "empty `<name>.nbt`" claim at line ~188 |
| `docs/architecture/module-map.md` | Modify | `config/` section lists what `SharedSettings`/`ModConfig` hold |

---

### Task 1: Config settings and their `garnet.json` round-trip

**Files:**
- Modify: `src/main/kotlin/com/breadmoirai/garnet/config/SharedSettings.kt`
- Modify: `src/client/kotlin/com/breadmoirai/garnet/config/ModConfig.kt`
- Test: `src/test/kotlin/com/breadmoirai/garnet/client/config/ModConfigTest.kt`

**Interfaces:**
- Consumes: nothing (first task).
- Produces: `SharedSettings.newStructurePlatformBlock: String`, `SharedSettings.newStructurePlatformWidth: Int`, `SharedSettings.newStructurePlatformDepth: Int`. Tasks 2 and 3 read these.

**Context:** `SharedSettings` is a plain `object` of `var`s in the `main` source set. `ModConfig` (client source set) is its only persistence layer: `load()` reads `config/garnet.json` and assigns each present key onto `SharedSettings`; absent keys are deliberately left alone so a hand-edited partial config never resets unrelated settings. `save()` writes every field. Both must be updated together.

- [ ] **Step 1: Add the failing assertions to `ModConfigTest`**

In `src/test/kotlin/com/breadmoirai/garnet/client/config/ModConfigTest.kt`, extend the private `SettingsSnapshot` data class with the three new fields (constructor defaults + `restore()` body):

```kotlin
private data class SettingsSnapshot(
    val projectRootPath: String = SharedSettings.projectRootPath,
    val autoSaveEnabled: Boolean = SharedSettings.autoSaveEnabled,
    val autoSaveDebounceTicks: Int = SharedSettings.autoSaveDebounceTicks,
    val autoSaveMaxDirtyTicks: Int = SharedSettings.autoSaveMaxDirtyTicks,
    val localHistoryEnabled: Boolean = SharedSettings.localHistoryEnabled,
    val localHistoryDays: Int = SharedSettings.localHistoryDays,
    val localHistoryMaxRevisions: Int = SharedSettings.localHistoryMaxRevisions,
    val localHistoryDir: String = SharedSettings.localHistoryDir,
    val structureRegionChunks: Int = SharedSettings.structureRegionChunks,
    val newStructurePlatformBlock: String = SharedSettings.newStructurePlatformBlock,
    val newStructurePlatformWidth: Int = SharedSettings.newStructurePlatformWidth,
    val newStructurePlatformDepth: Int = SharedSettings.newStructurePlatformDepth,
) {
    fun restore() {
        SharedSettings.projectRootPath = projectRootPath
        SharedSettings.autoSaveEnabled = autoSaveEnabled
        SharedSettings.autoSaveDebounceTicks = autoSaveDebounceTicks
        SharedSettings.autoSaveMaxDirtyTicks = autoSaveMaxDirtyTicks
        SharedSettings.localHistoryEnabled = localHistoryEnabled
        SharedSettings.localHistoryDays = localHistoryDays
        SharedSettings.localHistoryMaxRevisions = localHistoryMaxRevisions
        SharedSettings.localHistoryDir = localHistoryDir
        SharedSettings.structureRegionChunks = structureRegionChunks
        SharedSettings.newStructurePlatformBlock = newStructurePlatformBlock
        SharedSettings.newStructurePlatformWidth = newStructurePlatformWidth
        SharedSettings.newStructurePlatformDepth = newStructurePlatformDepth
    }
}
```

In the existing test `"every setting round-trips through garnet.json"`, add to the first mutation block (before `ModConfig.save()`):

```kotlin
            SharedSettings.newStructurePlatformBlock = "minecraft:gold_block"
            SharedSettings.newStructurePlatformWidth = 5
            SharedSettings.newStructurePlatformDepth = 7
```

to the clobber block (after `save()`, before `ModConfig.load()`):

```kotlin
            SharedSettings.newStructurePlatformBlock = "minecraft:smooth_stone"
            SharedSettings.newStructurePlatformWidth = 3
            SharedSettings.newStructurePlatformDepth = 3
```

and to the assertion block:

```kotlin
            SharedSettings.newStructurePlatformBlock shouldBe "minecraft:gold_block"
            SharedSettings.newStructurePlatformWidth shouldBe 5
            SharedSettings.newStructurePlatformDepth shouldBe 7
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `cmd.exe /c "gradlew.bat :26.2:test"`
Expected: **compilation failure** — `Unresolved reference: newStructurePlatformBlock`. That is the failing state for this step; the fields do not exist yet.

- [ ] **Step 3: Add the fields to `SharedSettings`**

Append to `src/main/kotlin/com/breadmoirai/garnet/config/SharedSettings.kt`, inside the object, after the local-history block:

```kotlin
    // === Default platform for new structures ===

    /**
     * Block a newly created structure's platform is made of. An unknown or malformed id logs a
     * warning and falls back to `minecraft:smooth_stone` rather than blocking the create.
     */
    var newStructurePlatformBlock: String = "minecraft:smooth_stone"

    /** Platform extent along X. Zero or negative creates the empty structure instead. */
    var newStructurePlatformWidth: Int = 3

    /** Platform extent along Z. Zero or negative creates the empty structure instead. */
    var newStructurePlatformDepth: Int = 3
```

- [ ] **Step 4: Add the `garnet.json` round-trip to `ModConfig`**

In `load()`, alongside the other `json.get(...)` lines (before the `projectCellSize` block):

```kotlin
                json.get("newStructurePlatformBlock")?.let { SharedSettings.newStructurePlatformBlock = it.asString }
                json.get("newStructurePlatformWidth")?.let { SharedSettings.newStructurePlatformWidth = it.asInt }
                json.get("newStructurePlatformDepth")?.let { SharedSettings.newStructurePlatformDepth = it.asInt }
```

In `save()`, alongside the other `json.addProperty(...)` lines (before the `projectCellSize` block):

```kotlin
        json.addProperty("newStructurePlatformBlock", SharedSettings.newStructurePlatformBlock)
        json.addProperty("newStructurePlatformWidth", SharedSettings.newStructurePlatformWidth)
        json.addProperty("newStructurePlatformDepth", SharedSettings.newStructurePlatformDepth)
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `cmd.exe /c "gradlew.bat :26.2:test"`
Expected: PASS. Confirm in `versions/26.2/build/test-results/test/TEST-com.breadmoirai.garnet.client.config.ModConfigTest.xml` that `failures="0" errors="0"` and the test count is unchanged (3 tests).

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/breadmoirai/garnet/config/SharedSettings.kt \
        src/client/kotlin/com/breadmoirai/garnet/config/ModConfig.kt \
        src/test/kotlin/com/breadmoirai/garnet/client/config/ModConfigTest.kt
git commit -m "feat(config): add default-platform settings for new structures"
```

---

### Task 2: `DefaultPlatform.platformTag` — the NBT builder

**Files:**
- Create: `src/main/kotlin/com/breadmoirai/garnet/editor/ops/DefaultPlatform.kt`
- Test: `src/test/kotlin/com/breadmoirai/garnet/editor/ops/DefaultPlatformTest.kt`

**Interfaces:**
- Consumes: nothing from Task 1 at compile time (the settings are read by Task 3's caller, not here — this function takes its inputs as parameters so it stays pure and testable).
- Produces:
  ```kotlin
  object DefaultPlatform {
      const val FALLBACK_BLOCK_ID: String = "minecraft:smooth_stone"
      fun platformTag(width: Int, depth: Int, blockId: String): CompoundTag?
  }
  ```
  Task 3 calls `platformTag`.

**Context — why hand-built NBT:** `StructureTemplate` exposes no public block-adding API. The only way to populate one is `fillFromWorld`, which needs a live `ServerLevel`. `EditorNewStructure.create` is a pure filesystem operation that runs before anything is placed in a world, so the tag must be assembled directly.

**Context — the exact format** (verified against the decompiled MC 26.2 `StructureTemplate.save`/`load`):

- `size` — a `ListTag` of three `IntTag`s: `[x, y, z]`
- `palette` — a `ListTag` of `CompoundTag`s produced by `NbtUtils.writeBlockState(state)`
- `blocks` — a `ListTag` of `CompoundTag`s, each with `pos` (int `ListTag` of 3) and `state` (int index into `palette`)
- `entities` — an empty `ListTag`

`StructureTemplate.load` reads exactly these keys and applies no datafixer, so no `DataVersion` field is needed. Use `NbtUtils.writeBlockState` rather than writing `{ Name: ... }` by hand — it is the exact inverse of the `NbtUtils.readBlockState` that `loadPalette` calls, and it emits block properties correctly for any configured block.

**Context — block-id resolution:** In MC 26.2 the resource-location class is `net.minecraft.resources.Identifier` (not `ResourceLocation`). `BuiltInRegistries.BLOCK` is a **defaulted** registry, so `getValue(id)` silently returns `AIR` for an unknown id — use `getOptional(id)` instead, which `DefaultedMappedRegistry` overrides specifically to return `Optional.empty()` for unknown keys. `Identifier.tryParse` returns `null` for a malformed string rather than throwing.

- [ ] **Step 1: Write the failing test**

Create `src/test/kotlin/com/breadmoirai/garnet/editor/ops/DefaultPlatformTest.kt`:

```kotlin
package com.breadmoirai.garnet.editor.ops

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import net.minecraft.SharedConstants
import net.minecraft.core.BlockPos
import net.minecraft.core.Vec3i
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.server.Bootstrap
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate

/**
 * `StructureTemplate.load` needs a `HolderGetter<Block>`; the vanilla built-in block registry
 * doubles as one once `Bootstrap.bootStrap()` has run, so these tests need no world.
 */
class DefaultPlatformTest : FunSpec({

    beforeSpec {
        SharedConstants.tryDetectVersion()
        Bootstrap.bootStrap()
    }

    fun loadTemplate(tag: net.minecraft.nbt.CompoundTag): StructureTemplate {
        val template = StructureTemplate()
        template.load(BuiltInRegistries.BLOCK, tag)
        return template
    }

    test("a 3x3 tag round-trips through StructureTemplate.load as a one-block-thick slab") {
        val tag = DefaultPlatform.platformTag(3, 3, "minecraft:smooth_stone").shouldNotBeNull()
        val template = loadTemplate(tag)
        template.size shouldBe Vec3i(3, 1, 3)

        // getPalettes()[0] is the single palette this builder writes; its block list must cover
        // every cell of the slab exactly once, all at y = 0, all smooth stone.
        val blocks = template.palettes[0].blocks()
        blocks.size shouldBe 9
        blocks.map { it.pos }.toSet() shouldBe
            (0..2).flatMap { x -> (0..2).map { z -> BlockPos(x, 0, z) } }.toSet()
        blocks.all { it.state == Blocks.SMOOTH_STONE.defaultBlockState() } shouldBe true
    }

    test("non-square dimensions produce the right cell count and size") {
        val tag = DefaultPlatform.platformTag(5, 2, "minecraft:gold_block").shouldNotBeNull()
        val template = loadTemplate(tag)
        template.size shouldBe Vec3i(5, 1, 2)
        template.palettes[0].blocks().size shouldBe 10
    }

    test("an unknown or malformed block id falls back to smooth stone instead of throwing") {
        val unknown = DefaultPlatform.platformTag(1, 1, "minecraft:not_a_real_block").shouldNotBeNull()
        loadTemplate(unknown).palettes[0].blocks()[0].state shouldBe
            Blocks.SMOOTH_STONE.defaultBlockState()

        val malformed = DefaultPlatform.platformTag(1, 1, "NOT AN ID").shouldNotBeNull()
        loadTemplate(malformed).palettes[0].blocks()[0].state shouldBe
            Blocks.SMOOTH_STONE.defaultBlockState()
    }

    test("a non-positive width or depth disables the platform") {
        DefaultPlatform.platformTag(0, 3, "minecraft:smooth_stone").shouldBeNull()
        DefaultPlatform.platformTag(3, 0, "minecraft:smooth_stone").shouldBeNull()
        DefaultPlatform.platformTag(-1, -1, "minecraft:smooth_stone").shouldBeNull()
    }
})
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `cmd.exe /c "gradlew.bat :26.2:test"`
Expected: **compilation failure** — `Unresolved reference: DefaultPlatform`.

- [ ] **Step 3: Write the implementation**

Create `src/main/kotlin/com/breadmoirai/garnet/editor/ops/DefaultPlatform.kt`:

```kotlin
package com.breadmoirai.garnet.editor.ops

import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.IntTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.NbtUtils
import net.minecraft.resources.Identifier
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import org.slf4j.LoggerFactory

private val LOGGER = LoggerFactory.getLogger("Garnet")

/**
 * Builds the structure NBT for the platform a newly created `.nbt` is seeded with.
 *
 * The tag is assembled by hand rather than through [net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate]:
 * the only way to populate a template is `fillFromWorld`, which needs a live `ServerLevel`, and
 * this runs during a pure filesystem create — before anything exists in a world. The key names and
 * shapes below mirror `StructureTemplate.save`/`load` exactly. No `DataVersion` is written because
 * the read path ([com.breadmoirai.garnet.structure.StructurePersistence.placeStructureCentered])
 * calls `template.load` directly, with no datafixer step.
 */
object DefaultPlatform {

    /** Used when the configured block id is unknown or malformed. */
    const val FALLBACK_BLOCK_ID: String = "minecraft:smooth_stone"

    /**
     * A [width] × 1 × [depth] slab of [blockId] at local y = 0, or `null` when either dimension is
     * non-positive (the platform is then disabled and the caller writes an empty structure).
     */
    fun platformTag(width: Int, depth: Int, blockId: String): CompoundTag? {
        if (width <= 0 || depth <= 0) return null
        val state = resolveBlockState(blockId)

        val blocks = ListTag()
        for (x in 0 until width) {
            for (z in 0 until depth) {
                val block = CompoundTag()
                block.put("pos", intList(x, 0, z))
                block.putInt("state", 0)  // the palette below has exactly one entry
                blocks.add(block)
            }
        }

        val palette = ListTag()
        // The exact inverse of the NbtUtils.readBlockState that StructureTemplate.loadPalette
        // calls -- it writes Properties too, so a configured block that has block states
        // (minecraft:oak_slab, say) round-trips in its default form instead of failing to parse.
        palette.add(NbtUtils.writeBlockState(state))

        val tag = CompoundTag()
        tag.put("size", intList(width, 1, depth))
        tag.put("palette", palette)
        tag.put("blocks", blocks)
        tag.put("entities", ListTag())
        return tag
    }

    private fun resolveBlockState(blockId: String): BlockState {
        // BuiltInRegistries.BLOCK is a DEFAULTED registry: getValue returns AIR for an unknown id
        // rather than null, which would silently produce an invisible platform. getOptional is
        // overridden by DefaultedMappedRegistry to bypass the default, so it reports the miss.
        val id = Identifier.tryParse(blockId)
        val block = id?.let { BuiltInRegistries.BLOCK.getOptional(it).orElse(null) }
        if (block != null) return block.defaultBlockState()
        LOGGER.warn(
            "[DefaultPlatform] unknown platform block '{}', falling back to {}",
            blockId, FALLBACK_BLOCK_ID,
        )
        return Blocks.SMOOTH_STONE.defaultBlockState()
    }

    private fun intList(vararg values: Int): ListTag {
        val list = ListTag()
        values.forEach { list.add(IntTag.valueOf(it)) }
        return list
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `cmd.exe /c "gradlew.bat :26.2:test"`
Expected: PASS. Check `versions/26.2/build/test-results/test/TEST-com.breadmoirai.garnet.editor.ops.DefaultPlatformTest.xml` — 4 tests, `failures="0" errors="0"`.

If a member name in the test does not resolve (`template.palettes`, `Palette.blocks()`), read the decompiled source rather than guessing:
`.gradle/loom-cache/minecraftMaven/net/minecraft/minecraft-common-*/26.2/minecraft-common-*-26.2-sources.jar`, class `net/minecraft/world/level/levelgen/structure/templatesystem/StructureTemplate.java`. Adjust the **test** to the real accessor; the tag shape in the implementation is already verified against `save`/`load` in that file.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/breadmoirai/garnet/editor/ops/DefaultPlatform.kt \
        src/test/kotlin/com/breadmoirai/garnet/editor/ops/DefaultPlatformTest.kt
git commit -m "feat(editor): add DefaultPlatform NBT builder for seeded structures"
```

---

### Task 3: Seed new structures with the platform

**Files:**
- Modify: `src/main/kotlin/com/breadmoirai/garnet/editor/ops/EditorNewStructure.kt`
- Test: `src/test/kotlin/com/breadmoirai/garnet/editor/ops/EditorNewStructureTest.kt`

**Interfaces:**
- Consumes: `SharedSettings.newStructurePlatform{Block,Width,Depth}` (Task 1), `DefaultPlatform.platformTag(width, depth, blockId)` (Task 2).
- Produces: no signature change. `EditorNewStructure.create(folder: Path, name: String): Path` keeps its shape, so its single production caller (`EditorStructureHandlers.handleNewStructure`) and ~25 gametest fixture call sites need no edits.

**Context:** Settings are read *inside* `create` rather than threaded through the call, matching how `StructureCommit` and `StructureAutoSave` read `SharedSettings` directly. This is what keeps the signature stable.

- [ ] **Step 1: Write the failing test**

Add two tests to `src/test/kotlin/com/breadmoirai/garnet/editor/ops/EditorNewStructureTest.kt`. Add these imports at the top of the file:

```kotlin
import com.breadmoirai.garnet.config.SharedSettings
import net.minecraft.core.Vec3i
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate
```

and the tests inside the `FunSpec({ ... })` body:

```kotlin
    test("a created structure carries the configured default platform") {
        val dir = Files.createTempDirectory("new-structure-platform")
        val prevBlock = SharedSettings.newStructurePlatformBlock
        val prevWidth = SharedSettings.newStructurePlatformWidth
        val prevDepth = SharedSettings.newStructurePlatformDepth
        try {
            SharedSettings.newStructurePlatformBlock = "minecraft:smooth_stone"
            SharedSettings.newStructurePlatformWidth = 3
            SharedSettings.newStructurePlatformDepth = 3

            val file = EditorNewStructure.create(dir, "platformed")
            val template = StructureTemplate()
            template.load(BuiltInRegistries.BLOCK, NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap()))

            template.size shouldBe Vec3i(3, 1, 3)
            val blocks = template.palettes[0].blocks()
            blocks.size shouldBe 9
            blocks.all { it.state == Blocks.SMOOTH_STONE.defaultBlockState() } shouldBe true
        } finally {
            SharedSettings.newStructurePlatformBlock = prevBlock
            SharedSettings.newStructurePlatformWidth = prevWidth
            SharedSettings.newStructurePlatformDepth = prevDepth
            dir.toFile().deleteRecursively()
        }
    }

    test("a zero-width platform setting still creates an empty structure") {
        val dir = Files.createTempDirectory("new-structure-noplatform")
        val prevWidth = SharedSettings.newStructurePlatformWidth
        try {
            SharedSettings.newStructurePlatformWidth = 0

            val file = EditorNewStructure.create(dir, "bare")
            val template = StructureTemplate()
            template.load(BuiltInRegistries.BLOCK, NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap()))

            template.size shouldBe Vec3i(0, 0, 0)
        } finally {
            SharedSettings.newStructurePlatformWidth = prevWidth
            dir.toFile().deleteRecursively()
        }
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `cmd.exe /c "gradlew.bat :26.2:test"`
Expected: the new `"a created structure carries the configured default platform"` FAILS — `create` still writes an empty template, so `template.size` is `Vec3i(0, 0, 0)`, not `Vec3i(3, 1, 3)`. (`"a zero-width platform setting still creates an empty structure"` passes already; that is expected — it is the regression guard for the disabled path.)

- [ ] **Step 3: Write the implementation**

In `src/main/kotlin/com/breadmoirai/garnet/editor/ops/EditorNewStructure.kt`, add the import:

```kotlin
import com.breadmoirai.garnet.config.SharedSettings
```

and replace the tag-building line:

```kotlin
        val nbt = StructureTemplate().save(CompoundTag())
```

with:

```kotlin
        // Seeded with the configured default platform so a freshly created structure places with a
        // build plane instead of nothing. Settings are read here rather than passed in, matching
        // StructureCommit/StructureAutoSave, so create's signature stays stable for its callers.
        val nbt = DefaultPlatform.platformTag(
            SharedSettings.newStructurePlatformWidth,
            SharedSettings.newStructurePlatformDepth,
            SharedSettings.newStructurePlatformBlock,
        ) ?: StructureTemplate().save(CompoundTag())
```

Update the object's KDoc on `create`: it says "Creates an empty `<name>.nbt` structure in [folder]" — change "empty" to "Creates a `<name>.nbt` structure in [folder], seeded with the configured default platform (see [DefaultPlatform])".

- [ ] **Step 4: Run the tests to verify they pass**

Run: `cmd.exe /c "gradlew.bat :26.2:test"`
Expected: PASS. Check `versions/26.2/build/test-results/test/TEST-com.breadmoirai.garnet.editor.ops.EditorNewStructureTest.xml` — 4 tests, `failures="0" errors="0"`.

- [ ] **Step 5: Verify every source set still compiles**

Run: `cmd.exe /c "gradlew.bat :26.2:classes :26.2:clientClasses :26.2:testClasses :26.2:gametestClasses :26.2:clientTestClasses"`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/breadmoirai/garnet/editor/ops/EditorNewStructure.kt \
        src/test/kotlin/com/breadmoirai/garnet/editor/ops/EditorNewStructureTest.kt
git commit -m "feat(editor): seed new structures with the default platform"
```

---

### Task 4: End-to-end gametest coverage and fallout

**Files:**
- Modify: `src/gametest/kotlin/com/breadmoirai/garnet/test/editor/EditorStructureNetworkSpec.kt`

**Interfaces:**
- Consumes: the behavior from Task 3 (`EditorNewStructure.create` writes a platform).
- Produces: nothing consumed downstream.

**Context — the fallout risk this task exists to catch:** `EditorNewStructure.create` is used as a *fixture* in roughly 25 gametest call sites (`StructureAutoSaveSpec`, `EditorFileOpsNetworkSpec`, `EditorNetworkRegistrySpec`, `EditorStructureNetworkSpec`). Every one of them previously got a zero-size structure; they now get a 3×3×1 one. Specs that place a structure and then assert on captured sizes or file bytes may shift. Several already call `StructurePersistence.clearBounds` over the whole region before editing, which wipes the platform out of the *world* (the `.nbt` still holds it) — those should be unaffected — but this must be confirmed by running, not by reading.

**Do NOT globally neuter the platform in test setup.** Where a spec genuinely depends on an empty starting structure, set `SharedSettings.newStructurePlatformWidth = 0` in *that spec's* setup with a `finally` restore, and add a one-line comment saying why.

**Note:** no new gametest spec class is added, so `GametestSentinel`'s explicit spec list needs no edit. (Autoscan is off — a *new* spec class would have to be registered there or it silently would not run.)

- [ ] **Step 1: Write the failing assertion**

In `src/gametest/kotlin/com/breadmoirai/garnet/test/editor/EditorStructureNetworkSpec.kt`, extend the existing test `"new structure creates the file and re-sends the tree"`. After the existing `drainPayloads(player).filterIsInstance<EditorTreeSnapshotS2C>() shouldHaveSize 1` line and before `EditorSession.clear(player.uuid)`, add:

```kotlin
                // The created structure is seeded with the default platform, so placing it puts a
                // 3x3 smooth-stone build plane at projectGridYBase (64) -- not nothing.
                EditorStructureHandlers.handlePlaceStructure(this, player, PlaceStructureC2S("fresh.nbt"))
                drainPayloads(player)
                val placed = EditorDimRegistry.of(this).placedBoxOf("fresh.nbt")!!
                placed.size shouldBe Vec3i(3, 1, 3)
                placed.origin.y shouldBe SharedSettings.projectGridYBase
                val lvl = overworld()
                for (dx in 0 until 3) {
                    for (dz in 0 until 3) {
                        lvl.getBlockState(placed.origin.offset(dx, 0, dz)).block shouldBe Blocks.SMOOTH_STONE
                    }
                }
```

Add whatever imports this needs that the file lacks — check the existing import block first; `EditorDimRegistry`, `SharedSettings`, `PlaceStructureC2S`, and `Vec3i` are already imported in this file, `net.minecraft.world.level.block.Blocks` may be. If `PlacedBox`'s accessors are named differently from `origin`/`size`, read `src/main/kotlin/com/breadmoirai/garnet/editor/world/EditorDimRegistry.kt` and use the real names.

- [ ] **Step 2: Run the gametests**

Run (the run hangs after printing its summary, so log-and-kill):

```bash
cmd.exe /c "gradlew.bat :26.2:runGameTest" > /tmp/gametest.log 2>&1 &
```

Then poll `/tmp/gametest.log` for the summary line, and kill the process once it appears. Do NOT pipe through `grep` — it buffers into an empty file. Read the log with the Read tool. The XML reports for `runGameTest` are unreliable; the log is the source of truth.

Expected on the first run: the new assertions PASS (Task 3 is already implemented) — this step is really about surfacing **fallout in other specs**.

- [ ] **Step 3: Fix any fallout**

For each failing spec in the log, determine whether the failure is (a) a genuine regression in production code, or (b) a fixture that assumed an empty structure.

- (a) → fix the production code and re-run.
- (b) → in that spec's setup, disable the platform for the affected test with a restore in `finally`:

```kotlin
            val prevWidth = SharedSettings.newStructurePlatformWidth
            // This spec asserts on capture sizes from a known-empty starting structure.
            SharedSettings.newStructurePlatformWidth = 0
            try {
                // ... existing test body ...
            } finally {
                SharedSettings.newStructurePlatformWidth = prevWidth
            }
```

Re-run step 2 after each round of fixes until the log reports zero failures.

- [ ] **Step 4: Run the unit suite once more**

Run: `cmd.exe /c "gradlew.bat :26.2:test"`
Expected: PASS, no regressions. Confirm `failures="0" errors="0"` across `versions/26.2/build/test-results/test/*.xml`.

- [ ] **Step 5: Commit**

```bash
git add src/gametest/kotlin/com/breadmoirai/garnet/test/editor/
git commit -m "test(gametest): cover the default platform on a newly created structure"
```

---

### Task 5: Documentation

**Files:**
- Modify: `docs/use-cases/structure-lifecycle.md`
- Modify: `docs/architecture/redstone-project.md`
- Modify: `docs/architecture/module-map.md`

**Interfaces:** none — documentation only.

**Context:** `CLAUDE.md` makes a docs audit mandatory after any source change. Three places currently assert that new structures are empty or enumerate what `SharedSettings`/`ModConfig` hold. `docs/superpowers/specs/` and `docs/superpowers/plans/` are commit-time snapshots — leave them alone.

- [ ] **Step 1: Update UC-MAN-10.d**

In `docs/use-cases/structure-lifecycle.md`, UC-MAN-10.d reads "... then calls `EditorNewStructure.create(folder, name)` — which writes an empty `<name>.nbt` into that folder — and re-sends the project tree."

Replace "which writes an empty `<name>.nbt` into that folder" with:

> which writes a `<name>.nbt` into that folder seeded with the default platform (a `SharedSettings.newStructurePlatformWidth` × 1 × `newStructurePlatformDepth` slab of `newStructurePlatformBlock`, default 3×3 `minecraft:smooth_stone`, built by `EditorNewStructure`'s `DefaultPlatform.platformTag`; a non-positive width or depth disables it and restores the empty-structure behavior). Because the slab is one block tall, `anchorY` floors it at `projectGridYBase` — a freshly created structure places with its build plane at y = 64.

Then update the UC-MAN-10.d row of the **coverage matrix** at the bottom of the same file: append to its description "…, seeding the new `.nbt` with the configured default platform", and append to its test list:

```
`DefaultPlatformTest."a 3x3 tag round-trips through StructureTemplate.load as a one-block-thick slab"`, `DefaultPlatformTest."non-square dimensions produce the right cell count and size"`, `DefaultPlatformTest."an unknown or malformed block id falls back to smooth stone instead of throwing"`, `DefaultPlatformTest."a non-positive width or depth disables the platform"`, `EditorNewStructureTest."a created structure carries the configured default platform"`, `EditorNewStructureTest."a zero-width platform setting still creates an empty structure"`
```

- [ ] **Step 2: Update the architecture overview**

In `docs/architecture/redstone-project.md` (~line 188), the sentence reads:

> "New Structure" (`EditorNewStructure.create`) writes an empty `<name>.nbt` into the folder named by `NewStructureC2S.parentSubpath` (`""` = the project root).

Replace with:

> "New Structure" (`EditorNewStructure.create`) writes a `<name>.nbt` into the folder named by `NewStructureC2S.parentSubpath` (`""` = the project root), seeded with the default platform — a one-block-thick slab of `SharedSettings.newStructurePlatformBlock` (default 3×3 `minecraft:smooth_stone`), built as raw structure NBT by `DefaultPlatform.platformTag`. Setting `newStructurePlatformWidth` or `newStructurePlatformDepth` to zero writes the empty structure instead.

- [ ] **Step 3: Update the module map**

In `docs/architecture/module-map.md`, the `config/` section lists what each file holds. Extend both bullets:

- `config/SharedSettings.kt` — append "and the default platform new structures are seeded with" to its list of concerns.
- `config/ModConfig.kt` — append "default structure platform" to its parenthesised key list.

Also add a bullet to the `editor/ops/` section (create the entry in place if that section already lists sibling files such as `EditorNewStructure.kt`):

```markdown
- `editor/ops/DefaultPlatform.kt` (main) — builds the raw structure NBT for the platform a newly
  created `.nbt` is seeded with. Hand-assembled rather than via `StructureTemplate`, whose only
  populate path (`fillFromWorld`) needs a live `ServerLevel` that the filesystem-only create has no
  access to. An unknown configured block id warns and falls back to `minecraft:smooth_stone`.
```

- [ ] **Step 4: Verify cross-references resolve**

Run: `grep -rn "writes an empty" docs/use-cases docs/architecture docs/persistence`
Expected: no hit that refers to `EditorNewStructure.create`.

Run: `grep -rn "DefaultPlatform" docs/architecture docs/use-cases`
Expected: at least the module-map and structure-lifecycle mentions added above.

- [ ] **Step 5: Commit**

```bash
git add docs/
git commit -m "docs: record the default platform seeded into new structures"
```

---

## Definition of Done

- `:26.2:test` is green, including `DefaultPlatformTest` (4 tests) and the two new `EditorNewStructureTest` tests.
- `:26.2:runGameTest` reports zero failures, including the extended `EditorStructureNetworkSpec` assertion.
- All five source sets compile.
- Creating a structure in-game and clicking it in the Explorer places a 3×3 smooth-stone platform with its surface at y = 64.
- Setting `newStructurePlatformWidth` to `0` in `config/garnet.json` restores the pre-change empty-structure behavior.
- The three docs files no longer claim new structures are empty.
