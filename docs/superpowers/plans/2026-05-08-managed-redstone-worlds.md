# Managed Redstone Worlds — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A void-dimension-per-folder workspace that lays out a folder of `.spec.kts` files in a deterministic grid for in-world authoring and running, persisting only per-spec bounding-region changes back to disk.

**Architecture:** New `managed/` package (data + server lifecycle), new `network/managed/` payloads, new `client/managed/` GUI, and a `SelectWorldScreen` mixin that injects an entry-point button. Reuses the existing `KtsSpecLoader`, `KtsSpecEmitter`, `StructurePersistence`, recorder/runner blocks, and `SpecBlockEntity` unchanged. Server-authoritative; clients only propose (matches `Packets.kt` pattern).

**Tech Stack:** Kotlin, Fabric Loader API (Stonecutter MC 26.1+), Mojang's `LevelStem` / `DimensionType` registries, Fabric `Dimensions` API, JUnit 5 (unit), Fabric `@GameTest` (server gametest), `FabricClientGameTest` (client gametest).

**Project conventions (already established — implementer must obey):**

- Build verification: `cmd.exe /c "./gradlew.bat :26.1:clientClasses :26.1:classes :26.1:gametestClasses :26.1:clientTestClasses :26.1:testClasses"` (5 sourcesets).
- Stonecutter task path: `:26.1:<task>` (NOT `:versions:26.1:<task>`).
- Mixins on inherited methods must target the declaring class with an `instanceof` guard inside the inject body.
- Use `-1` (0xFFFFFFFF) for white text — `0xFFFFFF` has alpha=0 and renders invisible in MC 26.1.
- Render-state extraction uses `extractWidgetRenderState` / `GuiGraphicsExtractor` (NOT `renderWidget` / `GuiGraphics`).
- All file paths in this plan use forward slashes; the project lives at `/mnt/h/Repo/RedstoneSpecs/`.

---

## Phase 1 — Pure data layer

These tasks are TDD-friendly and don't touch MC. They go in `src/main/kotlin/.../managed/` with unit tests in `src/test/kotlin/.../managed/`.

### Task 1: `ManagedRoot` value type + path-traversal guard

**Files:**
- Create: `src/main/kotlin/com/breadmoirai/redstonespecs/managed/ManagedRoot.kt`
- Test: `src/test/kotlin/com/breadmoirai/redstonespecs/managed/ManagedRootTest.kt`

- [ ] **Step 1: Write failing tests**

```kotlin
// ManagedRootTest.kt
package com.breadmoirai.redstonespecs.managed

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createFile
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertNotNull

class ManagedRootTest {
    @Test fun `resolveSubpath returns child when inside root`(@TempDir tmp: Path) {
        val root = ManagedRoot(tmp)
        tmp.resolve("a/b").createDirectories()
        val resolved = root.resolveSubpath("a/b")
        assertEquals(tmp.resolve("a/b").toRealPath(), resolved?.toRealPath())
    }

    @Test fun `resolveSubpath rejects empty subpath as root itself`(@TempDir tmp: Path) {
        val root = ManagedRoot(tmp)
        // Empty subpath = the root itself; allowed (used for tree listing).
        assertEquals(tmp.toRealPath(), root.resolveSubpath("")?.toRealPath())
    }

    @Test fun `resolveSubpath rejects parent traversal`(@TempDir tmp: Path) {
        val root = ManagedRoot(tmp.resolve("inner").also { it.createDirectories() })
        tmp.resolve("escape.txt").createFile()
        assertNull(root.resolveSubpath("../escape.txt"))
    }

    @Test fun `resolveSubpath rejects absolute subpath`(@TempDir tmp: Path) {
        val root = ManagedRoot(tmp)
        assertNull(root.resolveSubpath("/etc/passwd"))
    }

    @Test fun `resolveSubpath returns null for non-existent`(@TempDir tmp: Path) {
        val root = ManagedRoot(tmp)
        assertNull(root.resolveSubpath("nope"))
    }
}
```

- [ ] **Step 2: Verify tests fail**

Run: `cmd.exe /c "./gradlew.bat :26.1:test --tests com.breadmoirai.redstonespecs.managed.ManagedRootTest"`
Expected: compile error (`ManagedRoot` not found).

- [ ] **Step 3: Implement**

```kotlin
// ManagedRoot.kt
package com.breadmoirai.redstonespecs.managed

import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isDirectory

/**
 * A managed-specs root: an absolute folder containing nested folders of `.spec.kts` files.
 * Path traversal is rejected at this boundary — every server-side action that takes a
 * client-supplied subpath MUST go through `resolveSubpath` and reject `null`.
 */
data class ManagedRoot(val path: Path) {
    init {
        require(path.isAbsolute) { "ManagedRoot path must be absolute: $path" }
    }

    /**
     * Returns the absolute, real path of `subpath` if and only if it stays under `path`.
     * Empty subpath is allowed and returns the root itself. Absolute or escaping subpaths return null.
     */
    fun resolveSubpath(subpath: String): Path? {
        if (Path.of(subpath).isAbsolute) return null
        val candidate = path.resolve(subpath).normalize()
        if (!candidate.exists()) return null
        val real = candidate.toRealPath()
        val rootReal = path.toRealPath()
        return if (real.startsWith(rootReal)) real else null
    }

    fun isDirectory(subpath: String): Boolean {
        val resolved = resolveSubpath(subpath) ?: return false
        return resolved.isDirectory()
    }
}
```

- [ ] **Step 4: Verify tests pass**

Run: `cmd.exe /c "./gradlew.bat :26.1:test --tests com.breadmoirai.redstonespecs.managed.ManagedRootTest"`
Expected: 5 tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/breadmoirai/redstonespecs/managed/ManagedRoot.kt \
        src/test/kotlin/com/breadmoirai/redstonespecs/managed/ManagedRootTest.kt
git commit -m "managed: ManagedRoot value type with traversal guard"
```

---

### Task 2: `ManagedFolderTree` filesystem scanner

**Files:**
- Create: `src/main/kotlin/com/breadmoirai/redstonespecs/managed/ManagedFolderTree.kt`
- Test: `src/test/kotlin/com/breadmoirai/redstonespecs/managed/ManagedFolderTreeTest.kt`

- [ ] **Step 1: Write failing tests**

```kotlin
// ManagedFolderTreeTest.kt
package com.breadmoirai.redstonespecs.managed

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class ManagedFolderTreeTest {
    @Test fun `leaf folder contains spec files`(@TempDir tmp: Path) {
        tmp.resolve("doors").createDirectories()
        tmp.resolve("doors/piston.spec.kts").writeText("// ignored")
        val tree = ManagedFolderTree.scan(ManagedRoot(tmp))
        assertEquals(1, tree.leaves.size)
        assertEquals("doors", tree.leaves[0].subpath)
        assertEquals(listOf("piston.spec.kts"), tree.leaves[0].specFiles)
    }

    @Test fun `intermediate folder is recorded but has no specs`(@TempDir tmp: Path) {
        tmp.resolve("a/b").createDirectories()
        tmp.resolve("a/b/x.spec.kts").writeText("")
        val tree = ManagedFolderTree.scan(ManagedRoot(tmp))
        assertTrue(tree.intermediates.contains("a"))
        assertEquals(1, tree.leaves.size)
        assertEquals("a/b", tree.leaves[0].subpath)
    }

    @Test fun `folder with both subfolders and specs is a leaf and an intermediate`(@TempDir tmp: Path) {
        tmp.resolve("mixed/sub").createDirectories()
        tmp.resolve("mixed/top.spec.kts").writeText("")
        tmp.resolve("mixed/sub/inner.spec.kts").writeText("")
        val tree = ManagedFolderTree.scan(ManagedRoot(tmp))
        assertEquals(2, tree.leaves.size)
        val mixed = tree.leaves.first { it.subpath == "mixed" }
        assertEquals(listOf("top.spec.kts"), mixed.specFiles)
        assertTrue(tree.intermediates.contains("mixed"))
    }

    @Test fun `non-spec files ignored`(@TempDir tmp: Path) {
        tmp.resolve("a").createDirectories()
        tmp.resolve("a/readme.md").writeText("")
        tmp.resolve("a/x.spec.kts").writeText("")
        val tree = ManagedFolderTree.scan(ManagedRoot(tmp))
        assertEquals(listOf("x.spec.kts"), tree.leaves[0].specFiles)
    }

    @Test fun `empty root yields empty tree`(@TempDir tmp: Path) {
        val tree = ManagedFolderTree.scan(ManagedRoot(tmp))
        assertTrue(tree.leaves.isEmpty())
        assertFalse(tree.intermediates.contains(""))  // root itself isn't recorded
    }
}
```

- [ ] **Step 2: Verify failure**

Run: `cmd.exe /c "./gradlew.bat :26.1:test --tests com.breadmoirai.redstonespecs.managed.ManagedFolderTreeTest"`
Expected: compile error.

- [ ] **Step 3: Implement**

```kotlin
// ManagedFolderTree.kt
package com.breadmoirai.redstonespecs.managed

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.streams.toList

/** A folder containing one or more `.spec.kts` files directly. */
data class ManagedLeaf(val subpath: String, val specFiles: List<String>)

/**
 * Snapshot of a managed root's folder structure. `leaves` are folders that directly contain
 * spec files (one dim per leaf). `intermediates` are pure-navigation folders (no direct specs)
 * — the GUI uses these to render the navigation tree.
 */
data class ManagedFolderTree(
    val leaves: List<ManagedLeaf>,
    val intermediates: Set<String>,
) {
    companion object {
        private const val SPEC_EXT = ".spec.kts"

        fun scan(root: ManagedRoot): ManagedFolderTree {
            if (!Files.isDirectory(root.path)) return ManagedFolderTree(emptyList(), emptySet())

            val leaves = mutableListOf<ManagedLeaf>()
            val intermediates = sortedSetOf<String>()

            Files.walk(root.path).use { stream ->
                stream.filter { it.isDirectory() && it != root.path }.toList().sorted().forEach { dir ->
                    val rel = root.path.relativize(dir).toString().replace('\\', '/')
                    val specs = Files.list(dir).use { s ->
                        s.filter { it.isRegularFile() && it.name.endsWith(SPEC_EXT) }
                            .map { it.name }
                            .toList()
                            .sorted()
                    }
                    val hasSubdirs = Files.list(dir).use { s -> s.anyMatch { it.isDirectory() } }
                    if (specs.isNotEmpty()) leaves.add(ManagedLeaf(rel, specs))
                    if (hasSubdirs) intermediates.add(rel)
                }
            }

            return ManagedFolderTree(leaves.sortedBy { it.subpath }, intermediates)
        }
    }
}
```

- [ ] **Step 4: Verify tests pass**

Run: `cmd.exe /c "./gradlew.bat :26.1:test --tests com.breadmoirai.redstonespecs.managed.ManagedFolderTreeTest"`
Expected: 5 pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/breadmoirai/redstonespecs/managed/ManagedFolderTree.kt \
        src/test/kotlin/com/breadmoirai/redstonespecs/managed/ManagedFolderTreeTest.kt
git commit -m "managed: ManagedFolderTree filesystem scanner"
```

---

### Task 3: `GridLayout` pure layout function

**Files:**
- Create: `src/main/kotlin/com/breadmoirai/redstonespecs/managed/ManagedCell.kt`
- Create: `src/main/kotlin/com/breadmoirai/redstonespecs/managed/GridLayout.kt`
- Test: `src/test/kotlin/com/breadmoirai/redstonespecs/managed/GridLayoutTest.kt`

- [ ] **Step 1: Write failing tests**

```kotlin
// GridLayoutTest.kt
package com.breadmoirai.redstonespecs.managed

import com.breadmoirai.redstonespecs.data.RedstoneSpec
import net.minecraft.core.BlockPos
import net.minecraft.core.Vec3i
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GridLayoutTest {
    private val cellSize = Vec3i(32, 32, 32)
    private val cellGap = 4
    private val rowMax = 3
    private val yBase = 64

    private fun spec(id: String, size: Vec3i = Vec3i(5, 5, 5)) =
        RedstoneSpec(id = id, bounds = size, lifespan = 20, structure = null, entries = emptyList())

    private fun fileNamed(name: String, spec: RedstoneSpec) = LayoutInput(filename = name, spec = spec)

    @Test fun `single spec lands at origin`() {
        val out = GridLayout.compute(
            inputs = listOf(fileNamed("a.spec.kts", spec("a"))),
            cellSize = cellSize, cellGap = cellGap, rowMax = rowMax, yBase = yBase,
        )
        assertEquals(BlockPos(0, yBase, 0), out.cells["a"]!!.origin)
        assertTrue(out.errors.isEmpty())
    }

    @Test fun `row wraps at rowMax`() {
        val inputs = listOf("a", "b", "c", "d").map { fileNamed("$it.spec.kts", spec(it)) }
        val out = GridLayout.compute(inputs, cellSize, cellGap, rowMax, yBase)
        assertEquals(BlockPos(0, yBase, 0), out.cells["a"]!!.origin)
        assertEquals(BlockPos(36, yBase, 0), out.cells["b"]!!.origin)        // 32 + 4
        assertEquals(BlockPos(72, yBase, 0), out.cells["c"]!!.origin)        // 2*(32+4)
        assertEquals(BlockPos(0, yBase, 36), out.cells["d"]!!.origin)        // wrap to z=36
    }

    @Test fun `sort is by filename case-insensitive`() {
        val inputs = listOf(
            fileNamed("Beta.spec.kts", spec("beta")),
            fileNamed("alpha.spec.kts", spec("alpha")),
        )
        val out = GridLayout.compute(inputs, cellSize, cellGap, rowMax, yBase)
        // alpha < Beta when case-insensitive
        assertEquals(BlockPos(0, yBase, 0), out.cells["alpha"]!!.origin)
        assertEquals(BlockPos(36, yBase, 0), out.cells["beta"]!!.origin)
    }

    @Test fun `oversized spec excluded with error`() {
        val out = GridLayout.compute(
            listOf(fileNamed("big.spec.kts", spec("big", Vec3i(64, 5, 5)))),
            cellSize, cellGap, rowMax, yBase,
        )
        assertTrue(out.cells.isEmpty())
        assertEquals(1, out.errors.size)
        val e = out.errors[0]
        assertEquals("big", e.specId)
        assertTrue(e.reason.contains("64"))
    }

    @Test fun `tiebreak by spec id when filenames equal-after-case-fold`() {
        // Same filename impossible on disk, but the sort's tie-breaker is documented; verify by id.
        val inputs = listOf(
            fileNamed("x.spec.kts", spec("zzz")),
            fileNamed("x.spec.kts", spec("aaa")),
        )
        val out = GridLayout.compute(inputs, cellSize, cellGap, rowMax, yBase)
        assertEquals(BlockPos(0, yBase, 0), out.cells["aaa"]!!.origin)
        assertEquals(BlockPos(36, yBase, 0), out.cells["zzz"]!!.origin)
    }
}
```

- [ ] **Step 2: Verify failure**

Run: `cmd.exe /c "./gradlew.bat :26.1:test --tests com.breadmoirai.redstonespecs.managed.GridLayoutTest"`
Expected: compile error.

- [ ] **Step 3: Implement `ManagedCell.kt`**

```kotlin
// ManagedCell.kt
package com.breadmoirai.redstonespecs.managed

import net.minecraft.core.BlockPos
import net.minecraft.core.Vec3i

data class ManagedCell(
    val specId: String,
    val origin: BlockPos,
    val cellSize: Vec3i,
    val sourceFile: String,  // filename relative to the leaf folder, e.g. "piston.spec.kts"
)
```

- [ ] **Step 4: Implement `GridLayout.kt`**

```kotlin
// GridLayout.kt
package com.breadmoirai.redstonespecs.managed

import com.breadmoirai.redstonespecs.data.RedstoneSpec
import net.minecraft.core.BlockPos
import net.minecraft.core.Vec3i

data class LayoutInput(val filename: String, val spec: RedstoneSpec)
data class LayoutError(val specId: String, val filename: String, val reason: String)
data class LayoutResult(
    val cells: Map<String, ManagedCell>,           // by spec id
    val byOrigin: Map<BlockPos, String>,           // origin -> spec id
    val errors: List<LayoutError>,
)

object GridLayout {
    fun compute(
        inputs: List<LayoutInput>,
        cellSize: Vec3i,
        cellGap: Int,
        rowMax: Int,
        yBase: Int,
    ): LayoutResult {
        require(rowMax >= 1) { "rowMax must be >= 1" }

        val sorted = inputs.sortedWith(
            compareBy(String.CASE_INSENSITIVE_ORDER) { it.filename }
                .thenBy { it.spec.id }
        )

        val cells = LinkedHashMap<String, ManagedCell>()
        val byOrigin = LinkedHashMap<BlockPos, String>()
        val errors = mutableListOf<LayoutError>()

        var slotIndex = 0
        for (input in sorted) {
            val s = input.spec
            if (s.bounds.x > cellSize.x || s.bounds.y > cellSize.y || s.bounds.z > cellSize.z) {
                errors.add(LayoutError(s.id, input.filename,
                    "spec bounds ${s.bounds} exceeds cellSize $cellSize on at least one axis " +
                    "(${s.bounds.x}/${cellSize.x}, ${s.bounds.y}/${cellSize.y}, ${s.bounds.z}/${cellSize.z})"))
                continue
            }
            val sx = slotIndex % rowMax
            val sz = slotIndex / rowMax
            val origin = BlockPos(
                sx * (cellSize.x + cellGap),
                yBase,
                sz * (cellSize.z + cellGap),
            )
            cells[s.id] = ManagedCell(s.id, origin, cellSize, input.filename)
            byOrigin[origin] = s.id
            slotIndex++
        }

        return LayoutResult(cells, byOrigin, errors)
    }
}
```

- [ ] **Step 5: Verify tests pass**

Run: `cmd.exe /c "./gradlew.bat :26.1:test --tests com.breadmoirai.redstonespecs.managed.GridLayoutTest"`
Expected: 5 pass.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/breadmoirai/redstonespecs/managed/ManagedCell.kt \
        src/main/kotlin/com/breadmoirai/redstonespecs/managed/GridLayout.kt \
        src/test/kotlin/com/breadmoirai/redstonespecs/managed/GridLayoutTest.kt
git commit -m "managed: GridLayout pure layout function"
```

---

### Task 4: Subpath sanitization → dim id

**Files:**
- Create: `src/main/kotlin/com/breadmoirai/redstonespecs/managed/DimIdSanitizer.kt`
- Test: `src/test/kotlin/com/breadmoirai/redstonespecs/managed/DimIdSanitizerTest.kt`

- [ ] **Step 1: Write failing tests**

```kotlin
// DimIdSanitizerTest.kt
package com.breadmoirai.redstonespecs.managed

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class DimIdSanitizerTest {
    @Test fun `simple subpath passes through`() {
        assertEquals("managed/pistons/doors", DimIdSanitizer.toPath("pistons/doors"))
    }
    @Test fun `lowercases letters`() {
        assertEquals("managed/abc", DimIdSanitizer.toPath("ABC"))
    }
    @Test fun `replaces unsupported chars with underscore`() {
        assertEquals("managed/2x2_with_space", DimIdSanitizer.toPath("2x2 with space"))
        assertEquals("managed/a_b", DimIdSanitizer.toPath("a@b"))
    }
    @Test fun `preserves slash dot dash underscore digit`() {
        assertEquals("managed/a-b_c.d/0", DimIdSanitizer.toPath("a-b_c.d/0"))
    }
    @Test fun `empty subpath produces base`() {
        assertEquals("managed", DimIdSanitizer.toPath(""))
    }
}
```

- [ ] **Step 2: Verify failure**

Run: `cmd.exe /c "./gradlew.bat :26.1:test --tests com.breadmoirai.redstonespecs.managed.DimIdSanitizerTest"`
Expected: compile error.

- [ ] **Step 3: Implement**

```kotlin
// DimIdSanitizer.kt
package com.breadmoirai.redstonespecs.managed

object DimIdSanitizer {
    private val ALLOWED = Regex("[a-z0-9_/.\\-]")

    /**
     * Sanitizes a folder subpath (relative to the managed root) into the path component of a
     * Minecraft `ResourceLocation`. The base prefix is `managed`; full id is `redstonespecs:<this>`.
     * Rules: lowercase; chars outside `[a-z0-9_/.-]` become `_`. Empty → `managed`.
     */
    fun toPath(subpath: String): String {
        if (subpath.isEmpty()) return "managed"
        val sanitized = subpath.lowercase().map { c ->
            if (ALLOWED.matches(c.toString())) c else '_'
        }.joinToString("")
        return "managed/$sanitized"
    }
}
```

- [ ] **Step 4: Verify tests pass**

Run: `cmd.exe /c "./gradlew.bat :26.1:test --tests com.breadmoirai.redstonespecs.managed.DimIdSanitizerTest"`
Expected: 5 pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/breadmoirai/redstonespecs/managed/DimIdSanitizer.kt \
        src/test/kotlin/com/breadmoirai/redstonespecs/managed/DimIdSanitizerTest.kt
git commit -m "managed: subpath sanitization for dim ids"
```

---

## Phase 2 — Server: dim type, registry, lifecycle

### Task 5: Managed void chunk generator + dimension type registration

**Files:**
- Create: `src/main/kotlin/com/breadmoirai/redstonespecs/managed/ManagedDimensions.kt`
- Modify: `src/main/kotlin/com/breadmoirai/redstonespecs/Redstonespecs.kt` (call `ManagedDimensions.bootstrap()` in mod init)

- [ ] **Step 1: Implement `ManagedDimensions.kt`**

```kotlin
// ManagedDimensions.kt
package com.breadmoirai.redstonespecs.managed

import com.mojang.serialization.MapCodec
import net.minecraft.core.Holder
import net.minecraft.core.registries.Registries
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.world.level.Level
import net.minecraft.world.level.biome.Biome
import net.minecraft.world.level.biome.BiomeManager
import net.minecraft.world.level.biome.Biomes
import net.minecraft.world.level.biome.FixedBiomeSource
import net.minecraft.world.level.chunk.ChunkGenerator
import net.minecraft.world.level.dimension.DimensionType
import net.minecraft.world.level.dimension.LevelStem
import net.minecraft.world.level.dimension.end.EndDimension
import net.minecraft.server.MinecraftServer
import net.minecraft.world.level.levelgen.flat.FlatLevelGeneratorPreset
import net.minecraft.world.level.levelgen.flat.FlatLevelSource
import net.minecraft.world.level.levelgen.flat.FlatLevelGeneratorSettings

/**
 * Static registration for the managed-void dimension *type* (one per JVM). Per-folder
 * `LevelStem`s reuse this type and are registered dynamically by `ManagedDimRegistry`.
 *
 * Implementation note: the chunk generator is a vanilla `FlatLevelSource` with all empty
 * layers (a void preset). This avoids needing to register a custom generator codec.
 */
object ManagedDimensions {
    val DIMENSION_TYPE_KEY: ResourceKey<DimensionType> = ResourceKey.create(
        Registries.DIMENSION_TYPE,
        Identifier.fromNamespaceAndPath("redstonespecs", "managed_void"),
    )

    fun levelKey(sanitizedPath: String): ResourceKey<Level> = ResourceKey.create(
        Registries.DIMENSION,
        Identifier.fromNamespaceAndPath("redstonespecs", sanitizedPath),
    )

    /** Builds an empty-flat ChunkGenerator using the server's biome registry. */
    fun voidGenerator(server: MinecraftServer): ChunkGenerator {
        val biomes = server.registryAccess().lookupOrThrow(Registries.BIOME)
        val plainsBiome: Holder<Biome> = biomes.getOrThrow(Biomes.THE_VOID)
        val structureSets = server.registryAccess().lookupOrThrow(Registries.STRUCTURE_SET)
        val placedFeatures = server.registryAccess().lookupOrThrow(Registries.PLACED_FEATURE)
        val settings = FlatLevelGeneratorSettings(
            /* structureOverrides = */ java.util.Optional.empty(),
            /* biome = */ plainsBiome,
            /* structureSets = */ java.util.Collections.emptyList(),
        )
        // No layers added → pure void.
        return FlatLevelSource(settings)
    }
}
```

> **Implementer notes for this task:**
>
> 1. The exact constructor signatures of `FlatLevelGeneratorSettings`, `FlatLevelSource`, and `Biomes.THE_VOID` may vary per MC version. Consult `docs/minecraft/INDEX.md` and the decompiled MC sources (paths in `feedback_mc_sources` memory). The shape above matches MC 26.1; if the build fails, inspect `net.minecraft.world.level.levelgen.flat.FlatLevelGeneratorSettings` directly.
> 2. The dimension *type* is **not** registered here at runtime. Instead, register it via a vanilla data-pack JSON resource at `src/main/resources/data/redstonespecs/dimension_type/managed_void.json` so MC bootstrap picks it up from the registry. JSON contents:
>
>    ```json
>    {
>      "ultrawarm": false,
>      "natural": false,
>      "coordinate_scale": 1.0,
>      "has_skylight": true,
>      "has_ceiling": false,
>      "ambient_light": 0.0,
>      "fixed_time": 6000,
>      "monster_spawn_light_level": 0,
>      "monster_spawn_block_light_limit": 0,
>      "piglin_safe": false,
>      "bed_works": false,
>      "respawn_anchor_works": false,
>      "has_raids": false,
>      "logical_height": 256,
>      "min_y": 0,
>      "height": 256,
>      "infiniburn": "#minecraft:infiniburn_overworld",
>      "effects": "minecraft:overworld"
>    }
>    ```
>
>    `fixed_time: 6000` keeps it permanently noon (no day/night noise). The single-resource file is enough for MC to register the type at server start.

- [ ] **Step 2: Create the dimension-type JSON**

Create `src/main/resources/data/redstonespecs/dimension_type/managed_void.json` with the JSON above.

- [ ] **Step 3: Wire in `Redstonespecs.kt`**

Read the current `onInitialize` body and call `ManagedDimensions` only if it has any side-effecting bootstrap (currently it doesn't). For now, no edit to `Redstonespecs.kt` is needed at this task; `ManagedDimRegistry` (Task 6) will pull the codec on demand.

- [ ] **Step 4: Build verification**

Run: `cmd.exe /c "./gradlew.bat :26.1:classes"`
Expected: BUILD SUCCESSFUL. (Unit tests don't cover this — it requires an MC bootstrap context. Behavior is verified in Task 23's gametest.)

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/breadmoirai/redstonespecs/managed/ManagedDimensions.kt \
        src/main/resources/data/redstonespecs/dimension_type/managed_void.json
git commit -m "managed: void DimensionType registration + flat-void generator helper"
```

---

### Task 6: `ManagedDimRegistry` — dynamic level registration

**Files:**
- Create: `src/main/kotlin/com/breadmoirai/redstonespecs/managed/ManagedDimRegistry.kt`

- [ ] **Step 1: Implement**

```kotlin
// ManagedDimRegistry.kt
package com.breadmoirai.redstonespecs.managed

import net.fabricmc.fabric.api.dimension.v1.FabricDimensions
import net.minecraft.core.BlockPos
import net.minecraft.core.RegistryAccess
import net.minecraft.resources.ResourceKey
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.Level
import net.minecraft.world.level.dimension.LevelStem
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

private val LOGGER = LoggerFactory.getLogger("Redstone Specs")

/**
 * One per `MinecraftServer`. Owns:
 *   1. The set of dynamically-registered managed `ServerLevel`s, keyed by **original subpath**
 *      (canonical) so two paths that sanitize to the same id can be detected.
 *   2. Per-dim cell maps: `Map<BlockPos, spec-id>` for cell-membership lookup.
 *
 * NOTE on dynamic registration: Fabric exposes `FabricDimensions.add(...)` for runtime stems.
 * In MC 26.1 the supported path is to register a `LevelStem` and have Fabric materialize the
 * `ServerLevel`. If `FabricDimensions.add` isn't available in the configured Fabric API
 * version, the implementer must add it as a dependency in `build.gradle.kts` (see
 * `docs/build/INDEX.md`).
 */
class ManagedDimRegistry(private val server: MinecraftServer) {
    private data class Entry(
        val subpath: String,
        val sanitized: String,
        val key: ResourceKey<Level>,
        val cellsByOrigin: MutableMap<BlockPos, String> = mutableMapOf(),
    )

    private val bySubpath = ConcurrentHashMap<String, Entry>()
    private val bySanitized = ConcurrentHashMap<String, String>()  // sanitized -> subpath

    /** Returns existing or null if not loaded. */
    fun get(subpath: String): ResourceKey<Level>? = bySubpath[subpath]?.key

    /** True iff some other subpath already occupies the sanitized id. */
    fun collision(subpath: String): String? {
        val sanitized = DimIdSanitizer.toPath(subpath)
        val occupant = bySanitized[sanitized]
        return if (occupant != null && occupant != subpath) occupant else null
    }

    /**
     * Register or fetch the level for a subpath. Returns the level once it exists.
     * @throws IllegalStateException on sanitization collision.
     */
    fun getOrCreateLevel(subpath: String): ServerLevel {
        collision(subpath)?.let { other ->
            throw IllegalStateException("dim id collision: '$subpath' sanitizes to same id as '$other'")
        }
        val existing = bySubpath[subpath]
        if (existing != null) {
            return server.getLevel(existing.key)
                ?: throw IllegalStateException("registered key ${existing.key} has no live ServerLevel")
        }
        val sanitized = DimIdSanitizer.toPath(subpath)
        val key = ManagedDimensions.levelKey(sanitized)

        val dimTypeHolder = server.registryAccess()
            .lookupOrThrow(net.minecraft.core.registries.Registries.DIMENSION_TYPE)
            .getOrThrow(ManagedDimensions.DIMENSION_TYPE_KEY)
        val stem = LevelStem(dimTypeHolder, ManagedDimensions.voidGenerator(server))

        val level: ServerLevel = FabricDimensions.add(key, stem)
            ?: throw IllegalStateException("FabricDimensions.add returned null for $key")

        bySubpath[subpath] = Entry(subpath, sanitized, key)
        bySanitized[sanitized] = subpath
        LOGGER.info("[ManagedDimRegistry] registered '{}' -> {}", subpath, key)
        return level
    }

    fun setCellsForLevel(subpath: String, byOrigin: Map<BlockPos, String>) {
        val e = bySubpath[subpath] ?: return
        e.cellsByOrigin.clear()
        e.cellsByOrigin.putAll(byOrigin)
    }

    fun specIdAt(level: ServerLevel, blockPos: BlockPos): String? {
        val subpath = bySanitized.entries.firstOrNull { (_, sub) ->
            bySubpath[sub]?.key == level.dimension()
        }?.value ?: return null
        val cells = bySubpath[subpath]?.cellsByOrigin ?: return null
        // Match by cell origin: the spec owning a given pos is the one whose origin is the
        // greatest BlockPos componentwise <= pos and within cellSize. For now we only support
        // exact-origin lookup; callers translate cell-relative actions through cell origin.
        return cells[blockPos]
    }

    fun subpathForLevel(level: ServerLevel): String? =
        bySubpath.values.firstOrNull { it.key == level.dimension() }?.subpath

    fun forEach(block: (subpath: String, level: ServerLevel) -> Unit) {
        for (e in bySubpath.values) {
            val lvl = server.getLevel(e.key) ?: continue
            block(e.subpath, lvl)
        }
    }

    companion object {
        // One per server. Stored as a field on a server-attachment singleton.
        private val perServer = java.util.WeakHashMap<MinecraftServer, ManagedDimRegistry>()

        @Synchronized
        fun of(server: MinecraftServer): ManagedDimRegistry =
            perServer.getOrPut(server) { ManagedDimRegistry(server) }

        @Synchronized
        fun dispose(server: MinecraftServer) {
            perServer.remove(server)
        }
    }
}
```

- [ ] **Step 2: Build verification**

Run: `cmd.exe /c "./gradlew.bat :26.1:classes"`
Expected: BUILD SUCCESSFUL. If `FabricDimensions` doesn't resolve, add `fabric-dimensions-v1` to `build.gradle.kts` mod-dependencies (see existing fabric-api modules in the build file for the pattern).

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/com/breadmoirai/redstonespecs/managed/ManagedDimRegistry.kt
git commit -m "managed: ManagedDimRegistry for dynamic level registration"
```

---

### Task 7: `ManagedSession` — loaded-folder state

**Files:**
- Create: `src/main/kotlin/com/breadmoirai/redstonespecs/managed/ManagedSession.kt`

A `ManagedSession` is the in-memory state for "the currently loaded folder for player X." It owns the layout, the loaded-snapshot of each cell (for dirty diff), and the source-file paths.

- [ ] **Step 1: Implement**

```kotlin
// ManagedSession.kt
package com.breadmoirai.redstonespecs.managed

import com.breadmoirai.redstonespecs.data.RedstoneSpec
import net.minecraft.core.BlockPos
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate
import java.nio.file.Path
import java.util.UUID

/**
 * Per-player view of "what folder is loaded right now." Single-player has at most one entry.
 * Dedicated server: one per connected player who's opened the managed screen.
 *
 * The loaded snapshot is the in-memory `StructureTemplate` taken right after placing the spec's
 * structure in the cell — used for dirty-diff at save time.
 */
data class LoadedSpec(
    val cell: ManagedCell,
    val spec: RedstoneSpec,
    val sourceFile: Path,           // absolute path to the .spec.kts on disk
    val loadedSnapshot: StructureTemplate,
)

class ManagedSession(
    val playerId: UUID,
    val root: ManagedRoot,
    val subpath: String,
    val folderAbsolute: Path,
    val loaded: MutableMap<String, LoadedSpec>, // keyed by spec id
) {
    fun cellByOrigin(): Map<BlockPos, String> =
        loaded.values.associate { it.cell.origin to it.cell.specId }

    companion object {
        private val sessions = java.util.concurrent.ConcurrentHashMap<UUID, ManagedSession>()

        fun get(playerId: UUID): ManagedSession? = sessions[playerId]
        fun set(session: ManagedSession) { sessions[session.playerId] = session }
        fun clear(playerId: UUID) { sessions.remove(playerId) }
        fun all(): Collection<ManagedSession> = sessions.values
    }
}
```

- [ ] **Step 2: Build verification**

Run: `cmd.exe /c "./gradlew.bat :26.1:classes"`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/com/breadmoirai/redstonespecs/managed/ManagedSession.kt
git commit -m "managed: ManagedSession state (loaded folder per player)"
```

---

### Task 8: `ManagedCellSaver` — capture & write back

**Files:**
- Create: `src/main/kotlin/com/breadmoirai/redstonespecs/managed/ManagedCellSaver.kt`

- [ ] **Step 1: Implement**

```kotlin
// ManagedCellSaver.kt
package com.breadmoirai.redstonespecs.managed

import com.breadmoirai.redstonespecs.data.RedstoneSpec
import com.breadmoirai.redstonespecs.data.serial.KtsSpecEmitter
import com.breadmoirai.redstonespecs.persistence.StructurePersistence
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.NbtIo
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText

private val LOGGER = LoggerFactory.getLogger("Redstone Specs")

data class CellSaveResult(val specId: String, val saved: Boolean, val error: String? = null)

object ManagedCellSaver {
    /**
     * Captures the current cell volume, diffs vs `loaded.loadedSnapshot`, and rewrites
     * `.spec.kts` + structure NBT iff dirty. Returns whether the file was rewritten.
     */
    fun captureAndSaveIfDirty(
        level: ServerLevel,
        loaded: LoadedSpec,
        folderAbsolute: Path,
    ): CellSaveResult {
        val live = StructureTemplate()
        live.fillFromWorld(level, loaded.cell.origin, loaded.spec.bounds, false, emptyList())
        val liveNbt = live.save(CompoundTag())
        val savedNbt = loaded.loadedSnapshot.save(CompoundTag())
        if (liveNbt == savedNbt) {
            return CellSaveResult(loaded.spec.specIdSafe(), saved = false)
        }

        return runCatching {
            // .spec.kts: rewrite to source path; preserve `structure` field.
            val newSpec: RedstoneSpec = loaded.spec  // structure-id reference and entries unchanged here
            loaded.sourceFile.writeText(KtsSpecEmitter.emit(newSpec))
            // structure NBT: write next to .spec.kts using `<structureId>.nbt`.
            val structureId = newSpec.structure ?: newSpec.id
            val structureFile = folderAbsolute.resolve("$structureId.nbt")
            NbtIo.writeCompressed(liveNbt, structureFile)
            LOGGER.info("[ManagedCellSaver] saved '{}' -> {} + {}",
                newSpec.id, loaded.sourceFile, structureFile)
            CellSaveResult(newSpec.id, saved = true)
        }.getOrElse { e ->
            LOGGER.error("[ManagedCellSaver] failed to save '{}': {}", loaded.spec.id, e.message)
            CellSaveResult(loaded.spec.id, saved = false, error = e.message)
        }
    }

    private fun RedstoneSpec.specIdSafe(): String = id
}
```

> **Implementer note:** `StructurePersistence` already has `hasChanges`, but it diffs against the on-disk file. Here we diff against the in-memory snapshot taken at load — more accurate (catches the case where the user modified, hit save, modified again to revert; we still don't rewrite the file unchanged).

- [ ] **Step 2: Build verification**

Run: `cmd.exe /c "./gradlew.bat :26.1:classes"`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/com/breadmoirai/redstonespecs/managed/ManagedCellSaver.kt
git commit -m "managed: ManagedCellSaver writes per-spec changes back to disk"
```

---

### Task 9: `ManagedDimLifecycle.load` — open a folder

**Files:**
- Create: `src/main/kotlin/com/breadmoirai/redstonespecs/managed/ManagedDimLifecycle.kt`

- [ ] **Step 1: Implement (load flow)**

```kotlin
// ManagedDimLifecycle.kt
package com.breadmoirai.redstonespecs.managed

import com.breadmoirai.redstonespecs.ModRegistries
import com.breadmoirai.redstonespecs.block.SpecBlockEntity
import com.breadmoirai.redstonespecs.config.SharedSettings
import com.breadmoirai.redstonespecs.data.RedstoneSpec
import com.breadmoirai.redstonespecs.data.serial.KtsSpecLoader
import net.minecraft.core.BlockPos
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.NbtAccounter
import net.minecraft.nbt.NbtIo
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

private val LOGGER = LoggerFactory.getLogger("Redstone Specs")

data class LoadFolderReport(
    val subpath: String,
    val loaded: List<String>,                  // spec ids successfully placed
    val errors: List<LayoutError>,
    val parseErrors: List<ParseError>,
)
data class ParseError(val filename: String, val message: String)

object ManagedDimLifecycle {

    fun load(
        server: MinecraftServer,
        root: ManagedRoot,
        subpath: String,
        player: ServerPlayer,
    ): LoadFolderReport {
        val folder = root.resolveSubpath(subpath)
            ?: error("subpath outside root: $subpath")
        require(folder.isDirectory()) { "not a directory: $folder" }

        // 1. Parse all .spec.kts under the folder (one level).
        val files = folder.listDirectoryEntries("*.spec.kts").sortedBy { it.name }
        val parsed = mutableListOf<Pair<String, RedstoneSpec>>()  // (filename, spec)
        val parseErrors = mutableListOf<ParseError>()
        for (f in files) {
            try {
                parsed.add(f.name to KtsSpecLoader.loadFileAsRedstoneSpec(f))
            } catch (e: Exception) {
                parseErrors.add(ParseError(f.name, e.message ?: e::class.simpleName ?: "unknown"))
            }
        }

        // 2. Compute layout.
        val cellSize = SharedSettings.managedCellSize
        val cellGap = SharedSettings.managedCellGap
        val rowMax = SharedSettings.managedRowMax
        val yBase = SharedSettings.managedGridYBase
        val layout = GridLayout.compute(
            inputs = parsed.map { (name, s) -> LayoutInput(name, s) },
            cellSize, cellGap, rowMax, yBase,
        )

        // 3. Register / get the level.
        val registry = ManagedDimRegistry.of(server)
        val level = registry.getOrCreateLevel(subpath)

        // 4. Place each cell.
        val loadedSpecs = mutableMapOf<String, LoadedSpec>()
        for ((name, spec) in parsed) {
            val cell = layout.cells[spec.id] ?: continue  // excluded by oversize
            placeCell(level, folder, name, spec, cell)?.let { snapshot ->
                loadedSpecs[spec.id] = LoadedSpec(
                    cell = cell,
                    spec = spec,
                    sourceFile = folder.resolve(name),
                    loadedSnapshot = snapshot,
                )
            }
        }

        // 5. Update registry cell map for auto-bind lookups.
        registry.setCellsForLevel(subpath, layout.byOrigin)

        // 6. Save session and teleport.
        val session = ManagedSession(
            playerId = player.uuid,
            root = root, subpath = subpath, folderAbsolute = folder,
            loaded = loadedSpecs,
        )
        ManagedSession.set(session)

        val spawn = BlockPos(0, yBase + 2, 0)
        player.teleportTo(level, spawn.x + 0.5, spawn.y.toDouble(), spawn.z + 0.5, player.yRot, player.xRot)

        return LoadFolderReport(
            subpath = subpath,
            loaded = loadedSpecs.keys.toList(),
            errors = layout.errors,
            parseErrors = parseErrors,
        )
    }

    private fun placeCell(
        level: ServerLevel,
        folder: Path,
        filename: String,
        spec: RedstoneSpec,
        cell: ManagedCell,
    ): StructureTemplate? {
        val structureFile = folder.resolve("${spec.structure ?: spec.id}.nbt")
        val placedTemplate = if (structureFile.exists()) {
            // Place existing structure at cell origin.
            val nbt = NbtIo.readCompressed(structureFile, NbtAccounter.unlimitedHeap())
            val blockGetter = level.registryAccess()
                .lookupOrThrow(net.minecraft.core.registries.Registries.BLOCK)
            val tpl = StructureTemplate()
            tpl.load(blockGetter, nbt)
            tpl.placeInWorld(level, cell.origin, cell.origin, StructurePlaceSettings(), level.random, 2)
            tpl
        } else {
            StructureTemplate().also { it.fillFromWorld(level, cell.origin, spec.bounds, false, emptyList()) }
        }

        // Place runner block (or recorder for new spec) at cell origin's +X edge.
        val auxPos = cell.origin.offset(spec.bounds.x, 0, 0)
        val anchorBlock = if (structureFile.exists()) {
            ModRegistries.REDSTONE_SPEC_RUNNER_BLOCK
        } else {
            ModRegistries.REDSTONE_SPEC_RECORDER_BLOCK
        }
        level.setBlock(auxPos, anchorBlock.defaultBlockState(), 2)
        val be = level.getBlockEntity(auxPos) as? SpecBlockEntity
        be?.setSpec(spec)

        // Snapshot the cell volume *after* placement (so dirty-diff has a stable baseline that
        // includes the runner block too).
        val snapshot = StructureTemplate()
        snapshot.fillFromWorld(level, cell.origin, spec.bounds, false, emptyList())
        return snapshot
    }
}
```

> **Implementer notes:**
> - `KtsSpecLoader.loadFileAsRedstoneSpec(Path)` is the existing API (see `SpecPersistence.load`).
> - The runner block is placed at `cell.origin + (bounds.x, 0, 0)` — this lands one block past the +X face of the structure, *outside* the spec's bounds AABB. This means it's also outside the save scan, so changing it doesn't dirty the spec.
> - The "snapshot includes runner" choice differs from the spec doc but is simpler and the runner block isn't inside `bounds` so there's no actual difference in semantics; `bounds` defines what's saved.
> - Re-read the design doc Section 6 if confused.

- [ ] **Step 2: Build verification**

Run: `cmd.exe /c "./gradlew.bat :26.1:classes"`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/com/breadmoirai/redstonespecs/managed/ManagedDimLifecycle.kt
git commit -m "managed: ManagedDimLifecycle.load opens a folder into its dim"
```

---

### Task 10: `ManagedDimLifecycle.unload` and `saveNow`

**Files:**
- Modify: `src/main/kotlin/com/breadmoirai/redstonespecs/managed/ManagedDimLifecycle.kt`

- [ ] **Step 1: Add unload + saveNow**

Append to the same file:

```kotlin
fun saveNow(server: MinecraftServer, playerId: java.util.UUID): List<CellSaveResult> {
    val session = ManagedSession.get(playerId) ?: return emptyList()
    val level = server.getLevel(ManagedDimensions.levelKey(DimIdSanitizer.toPath(session.subpath)))
        ?: return emptyList()
    val results = mutableListOf<CellSaveResult>()
    val refreshed = mutableMapOf<String, LoadedSpec>()
    for ((id, loaded) in session.loaded) {
        val r = ManagedCellSaver.captureAndSaveIfDirty(level, loaded, session.folderAbsolute)
        results.add(r)
        if (r.saved) {
            // Refresh in-memory snapshot to the just-saved state so subsequent dirty-diffs are accurate.
            val newSnap = StructureTemplate()
            newSnap.fillFromWorld(level, loaded.cell.origin, loaded.spec.bounds, false, emptyList())
            refreshed[id] = loaded.copy(loadedSnapshot = newSnap)
        }
    }
    if (refreshed.isNotEmpty()) {
        session.loaded.putAll(refreshed)
    }
    return results
}

fun unload(server: MinecraftServer, playerId: java.util.UUID, save: Boolean = true): List<CellSaveResult> {
    val results = if (save) saveNow(server, playerId) else emptyList()
    ManagedSession.clear(playerId)
    return results
}
```

- [ ] **Step 2: Build verification**

Run: `cmd.exe /c "./gradlew.bat :26.1:classes"`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/com/breadmoirai/redstonespecs/managed/ManagedDimLifecycle.kt
git commit -m "managed: saveNow + unload flows"
```

---

### Task 11: New-spec creator

**Files:**
- Create: `src/main/kotlin/com/breadmoirai/redstonespecs/managed/ManagedNewSpec.kt`

- [ ] **Step 1: Implement**

```kotlin
// ManagedNewSpec.kt
package com.breadmoirai.redstonespecs.managed

import com.breadmoirai.redstonespecs.data.RedstoneSpec
import com.breadmoirai.redstonespecs.data.serial.KtsSpecEmitter
import org.slf4j.LoggerFactory
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.writeText

private val LOGGER = LoggerFactory.getLogger("Redstone Specs")

object ManagedNewSpec {
    /**
     * Creates a stub `<name>.spec.kts` in `folder`. Returns the path; throws if a file with
     * that name already exists or `name` is blank/illegal. Caller should reload the folder
     * afterwards so the new cell appears.
     */
    fun create(folder: Path, name: String): Path {
        require(name.isNotBlank()) { "spec name must not be blank" }
        require(name.matches(Regex("[a-zA-Z0-9_\\-]+"))) {
            "spec name must match [a-zA-Z0-9_-]+, got: '$name'"
        }
        val file = folder.resolve("$name.spec.kts")
        require(!file.exists()) { "spec file already exists: $file" }
        val stub = RedstoneSpec.new(name)
        file.writeText(KtsSpecEmitter.emit(stub))
        LOGGER.info("[ManagedNewSpec] created stub '{}'", file)
        return file
    }
}
```

- [ ] **Step 2: Build verification**

Run: `cmd.exe /c "./gradlew.bat :26.1:classes"`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/com/breadmoirai/redstonespecs/managed/ManagedNewSpec.kt
git commit -m "managed: ManagedNewSpec stub creator"
```

---

## Phase 3 — Config additions

### Task 12: Extend `SharedSettings` with grid + root config

**Files:**
- Modify: `src/main/kotlin/com/breadmoirai/redstonespecs/config/SharedSettings.kt`

- [ ] **Step 1: Edit**

Replace the file with:

```kotlin
package com.breadmoirai.redstonespecs.config

import net.minecraft.core.Vec3i

object SharedSettings {
    // Existing
    var specSaveDir: String = "redstonespecs"

    // Managed-worlds: grid layout
    var managedCellSize: Vec3i = Vec3i(32, 32, 32)
    var managedCellGap: Int = 4
    var managedRowMax: Int = 8
    var managedGridYBase: Int = 64

    // Dedicated-server-only: absolute root path. Empty string = managed disabled.
    // Singleplayer ignores this (root is selected from the world-list screen).
    var managedRootPath: String = ""
}
```

- [ ] **Step 2: Build verification**

Run: `cmd.exe /c "./gradlew.bat :26.1:classes"`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/com/breadmoirai/redstonespecs/config/SharedSettings.kt
git commit -m "config: managed-worlds grid + root settings"
```

---

### Task 13: Client-side managed-roots registry file

**Files:**
- Create: `src/client/kotlin/com/breadmoirai/redstonespecs/client/managed/ManagedRootsConfig.kt`
- Test: `src/test/kotlin/com/breadmoirai/redstonespecs/client/managed/ManagedRootsConfigTest.kt`

- [ ] **Step 1: Write failing test**

```kotlin
package com.breadmoirai.redstonespecs.client.managed

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals

class ManagedRootsConfigTest {
    @Test fun `add then load roundtrips`(@TempDir tmp: Path) {
        val cfg = tmp.resolve("managed-roots.json")
        ManagedRootsConfig.save(cfg, listOf("/a/b", "/c/d"))
        assertEquals(listOf("/a/b", "/c/d"), ManagedRootsConfig.load(cfg))
    }

    @Test fun `load returns empty when missing`(@TempDir tmp: Path) {
        assertEquals(emptyList(), ManagedRootsConfig.load(tmp.resolve("missing.json")))
    }
}
```

- [ ] **Step 2: Verify failure**

Run: `cmd.exe /c "./gradlew.bat :26.1:test --tests com.breadmoirai.redstonespecs.client.managed.ManagedRootsConfigTest"`
Expected: compile error.

- [ ] **Step 3: Implement**

```kotlin
// ManagedRootsConfig.kt
package com.breadmoirai.redstonespecs.client.managed

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createParentDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

object ManagedRootsConfig {
    private val GSON = Gson()
    private val LIST_TYPE = object : TypeToken<List<String>>() {}.type

    fun load(path: Path): List<String> {
        if (!path.exists()) return emptyList()
        return GSON.fromJson(path.readText(), LIST_TYPE) ?: emptyList()
    }

    fun save(path: Path, roots: List<String>) {
        path.createParentDirectories()
        path.writeText(GSON.toJson(roots))
    }
}
```

- [ ] **Step 4: Verify tests pass**

Run: `cmd.exe /c "./gradlew.bat :26.1:test --tests com.breadmoirai.redstonespecs.client.managed.ManagedRootsConfigTest"`
Expected: 2 pass.

- [ ] **Step 5: Commit**

```bash
git add src/client/kotlin/com/breadmoirai/redstonespecs/client/managed/ManagedRootsConfig.kt \
        src/test/kotlin/com/breadmoirai/redstonespecs/client/managed/ManagedRootsConfigTest.kt
git commit -m "client/managed: persistent managed-roots list"
```

---

## Phase 4 — Network protocol

### Task 14: Define managed payloads

**Files:**
- Create: `src/main/kotlin/com/breadmoirai/redstonespecs/network/managed/ManagedPackets.kt`

- [ ] **Step 1: Implement**

Write the payloads following the exact pattern of `network/Packets.kt` (data class with `STREAM_CODEC` companion). Implement each below:

```kotlin
// ManagedPackets.kt
package com.breadmoirai.redstonespecs.network.managed

import io.netty.buffer.ByteBuf
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.Identifier

private fun id(p: String) = Identifier.fromNamespaceAndPath("redstonespecs", "managed_$p")

// === Tree listing ===

data class ManagedLeafEntry(val subpath: String, val specCount: Int) {
    companion object {
        val STREAM_CODEC: StreamCodec<ByteBuf, ManagedLeafEntry> = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, ManagedLeafEntry::subpath,
            ByteBufCodecs.VAR_INT, ManagedLeafEntry::specCount,
            ::ManagedLeafEntry,
        )
    }
}

data class ManagedTreeSnapshotS2C(
    val leaves: List<ManagedLeafEntry>,
    val intermediates: List<String>,
    val currentSubpath: String?, // null if no folder loaded
) : CustomPacketPayload {
    companion object {
        val TYPE = CustomPacketPayload.Type<ManagedTreeSnapshotS2C>(id("tree_snapshot"))
        val STREAM_CODEC: StreamCodec<ByteBuf, ManagedTreeSnapshotS2C> = StreamCodec.composite(
            ManagedLeafEntry.STREAM_CODEC.apply(ByteBufCodecs.list()), ManagedTreeSnapshotS2C::leaves,
            ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()), ManagedTreeSnapshotS2C::intermediates,
            ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.optional()), { it.currentSubpath?.let(java.util.Optional::of) ?: java.util.Optional.empty() },
            { leaves, ints, current -> ManagedTreeSnapshotS2C(leaves, ints, current.orElse(null)) },
        )
    }
    override fun type() = TYPE
}

// === C2S actions ===

class ListManagedTreeC2S : CustomPacketPayload {
    companion object {
        val TYPE = CustomPacketPayload.Type<ListManagedTreeC2S>(id("list_tree"))
        val STREAM_CODEC: StreamCodec<ByteBuf, ListManagedTreeC2S> = StreamCodec.unit(ListManagedTreeC2S())
    }
    override fun type() = TYPE
}

data class LoadManagedFolderC2S(val subpath: String) : CustomPacketPayload {
    companion object {
        val TYPE = CustomPacketPayload.Type<LoadManagedFolderC2S>(id("load_folder"))
        val STREAM_CODEC: StreamCodec<ByteBuf, LoadManagedFolderC2S> = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, LoadManagedFolderC2S::subpath,
            ::LoadManagedFolderC2S,
        )
    }
    override fun type() = TYPE
}

class UnloadManagedFolderC2S : CustomPacketPayload {
    companion object {
        val TYPE = CustomPacketPayload.Type<UnloadManagedFolderC2S>(id("unload"))
        val STREAM_CODEC: StreamCodec<ByteBuf, UnloadManagedFolderC2S> = StreamCodec.unit(UnloadManagedFolderC2S())
    }
    override fun type() = TYPE
}

class SaveNowC2S : CustomPacketPayload {
    companion object {
        val TYPE = CustomPacketPayload.Type<SaveNowC2S>(id("save_now"))
        val STREAM_CODEC: StreamCodec<ByteBuf, SaveNowC2S> = StreamCodec.unit(SaveNowC2S())
    }
    override fun type() = TYPE
}

data class NewManagedSpecC2S(val name: String) : CustomPacketPayload {
    companion object {
        val TYPE = CustomPacketPayload.Type<NewManagedSpecC2S>(id("new_spec"))
        val STREAM_CODEC: StreamCodec<ByteBuf, NewManagedSpecC2S> = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, NewManagedSpecC2S::name,
            ::NewManagedSpecC2S,
        )
    }
    override fun type() = TYPE
}

// === S2C results ===

data class ManagedFolderLoadedS2C(
    val subpath: String,
    val loadedSpecIds: List<String>,
    val parseErrors: List<String>,
    val layoutErrors: List<String>,
) : CustomPacketPayload {
    companion object {
        val TYPE = CustomPacketPayload.Type<ManagedFolderLoadedS2C>(id("folder_loaded"))
        val STREAM_CODEC: StreamCodec<ByteBuf, ManagedFolderLoadedS2C> = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, ManagedFolderLoadedS2C::subpath,
            ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()), ManagedFolderLoadedS2C::loadedSpecIds,
            ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()), ManagedFolderLoadedS2C::parseErrors,
            ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()), ManagedFolderLoadedS2C::layoutErrors,
            ::ManagedFolderLoadedS2C,
        )
    }
    override fun type() = TYPE
}

data class ManagedSaveReportS2C(
    val perSpec: List<String>,  // "specId|saved=true|err=..." — formatted for simple GUI display
) : CustomPacketPayload {
    companion object {
        val TYPE = CustomPacketPayload.Type<ManagedSaveReportS2C>(id("save_report"))
        val STREAM_CODEC: StreamCodec<ByteBuf, ManagedSaveReportS2C> = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()), ManagedSaveReportS2C::perSpec,
            ::ManagedSaveReportS2C,
        )
    }
    override fun type() = TYPE
}

data class ManagedErrorS2C(val reason: String) : CustomPacketPayload {
    companion object {
        val TYPE = CustomPacketPayload.Type<ManagedErrorS2C>(id("error"))
        val STREAM_CODEC: StreamCodec<ByteBuf, ManagedErrorS2C> = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, ManagedErrorS2C::reason,
            ::ManagedErrorS2C,
        )
    }
    override fun type() = TYPE
}
```

- [ ] **Step 2: Build verification**

Run: `cmd.exe /c "./gradlew.bat :26.1:classes"`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/com/breadmoirai/redstonespecs/network/managed/ManagedPackets.kt
git commit -m "network/managed: payloads for tree, load, save, new-spec, errors"
```

---

### Task 15: Server handlers + payload registration

**Files:**
- Create: `src/main/kotlin/com/breadmoirai/redstonespecs/network/managed/ManagedNetworkRegistry.kt`
- Modify: `src/main/kotlin/com/breadmoirai/redstonespecs/network/NetworkRegistry.kt` (call into managed registration)

- [ ] **Step 1: Implement `ManagedNetworkRegistry.kt`**

```kotlin
package com.breadmoirai.redstonespecs.network.managed

import com.breadmoirai.redstonespecs.config.SharedSettings
import com.breadmoirai.redstonespecs.managed.*
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import org.slf4j.LoggerFactory
import java.nio.file.Path

private val LOGGER = LoggerFactory.getLogger("Redstone Specs")

object ManagedNetworkRegistry {

    /**
     * Resolves the managed root for a player: dedicated server uses `SharedSettings.managedRootPath`;
     * integrated server (singleplayer) reads it from a server-attached `ManagedServerContext`
     * pinned at boot (see Task 21).
     */
    private fun rootFor(server: MinecraftServer): ManagedRoot? {
        val ctx = ManagedServerContext.get(server)
        if (ctx != null) return ctx.root
        val cfg = SharedSettings.managedRootPath
        return if (cfg.isNotBlank()) ManagedRoot(Path.of(cfg).toAbsolutePath()) else null
    }

    fun register() {
        // Payload registrations
        PayloadTypeRegistry.serverboundPlay().register(ListManagedTreeC2S.TYPE, ListManagedTreeC2S.STREAM_CODEC)
        PayloadTypeRegistry.serverboundPlay().register(LoadManagedFolderC2S.TYPE, LoadManagedFolderC2S.STREAM_CODEC)
        PayloadTypeRegistry.serverboundPlay().register(UnloadManagedFolderC2S.TYPE, UnloadManagedFolderC2S.STREAM_CODEC)
        PayloadTypeRegistry.serverboundPlay().register(SaveNowC2S.TYPE, SaveNowC2S.STREAM_CODEC)
        PayloadTypeRegistry.serverboundPlay().register(NewManagedSpecC2S.TYPE, NewManagedSpecC2S.STREAM_CODEC)
        PayloadTypeRegistry.clientboundPlay().register(ManagedTreeSnapshotS2C.TYPE, ManagedTreeSnapshotS2C.STREAM_CODEC)
        PayloadTypeRegistry.clientboundPlay().register(ManagedFolderLoadedS2C.TYPE, ManagedFolderLoadedS2C.STREAM_CODEC)
        PayloadTypeRegistry.clientboundPlay().register(ManagedSaveReportS2C.TYPE, ManagedSaveReportS2C.STREAM_CODEC)
        PayloadTypeRegistry.clientboundPlay().register(ManagedErrorS2C.TYPE, ManagedErrorS2C.STREAM_CODEC)

        ServerPlayNetworking.registerGlobalReceiver(ListManagedTreeC2S.TYPE) { _, ctx ->
            ctx.server().execute { sendTree(ctx.server(), ctx.player()) }
        }

        ServerPlayNetworking.registerGlobalReceiver(LoadManagedFolderC2S.TYPE) { payload, ctx ->
            val player = ctx.player()
            ctx.server().execute {
                val root = rootFor(ctx.server()) ?: run {
                    ServerPlayNetworking.send(player, ManagedErrorS2C("managed-root not configured"))
                    return@execute
                }
                if (root.resolveSubpath(payload.subpath) == null) {
                    ServerPlayNetworking.send(player, ManagedErrorS2C("subpath not found or escapes root: ${payload.subpath}"))
                    return@execute
                }
                // Unload current session if any (saving) before loading next.
                if (ManagedSession.get(player.uuid) != null) {
                    ManagedDimLifecycle.unload(ctx.server(), player.uuid, save = true)
                }
                val report = try {
                    ManagedDimLifecycle.load(ctx.server(), root, payload.subpath, player)
                } catch (e: Exception) {
                    LOGGER.error("[managed/load] {}: {}", payload.subpath, e.message, e)
                    ServerPlayNetworking.send(player, ManagedErrorS2C("load failed: ${e.message}"))
                    return@execute
                }
                ServerPlayNetworking.send(player, ManagedFolderLoadedS2C(
                    subpath = report.subpath,
                    loadedSpecIds = report.loaded,
                    parseErrors = report.parseErrors.map { "${it.filename}: ${it.message}" },
                    layoutErrors = report.errors.map { "${it.specId} (${it.filename}): ${it.reason}" },
                ))
            }
        }

        ServerPlayNetworking.registerGlobalReceiver(UnloadManagedFolderC2S.TYPE) { _, ctx ->
            val player = ctx.player()
            ctx.server().execute {
                val results = ManagedDimLifecycle.unload(ctx.server(), player.uuid, save = true)
                ServerPlayNetworking.send(player, ManagedSaveReportS2C(results.map(::formatSaveResult)))
            }
        }

        ServerPlayNetworking.registerGlobalReceiver(SaveNowC2S.TYPE) { _, ctx ->
            val player = ctx.player()
            ctx.server().execute {
                val results = ManagedDimLifecycle.saveNow(ctx.server(), player.uuid)
                ServerPlayNetworking.send(player, ManagedSaveReportS2C(results.map(::formatSaveResult)))
            }
        }

        ServerPlayNetworking.registerGlobalReceiver(NewManagedSpecC2S.TYPE) { payload, ctx ->
            val player = ctx.player()
            ctx.server().execute {
                val session = ManagedSession.get(player.uuid) ?: run {
                    ServerPlayNetworking.send(player, ManagedErrorS2C("no folder loaded"))
                    return@execute
                }
                val root = rootFor(ctx.server()) ?: return@execute
                try {
                    ManagedNewSpec.create(session.folderAbsolute, payload.name)
                } catch (e: Exception) {
                    ServerPlayNetworking.send(player, ManagedErrorS2C("new-spec failed: ${e.message}"))
                    return@execute
                }
                // Reload to materialize the new cell.
                ManagedDimLifecycle.unload(ctx.server(), player.uuid, save = true)
                val report = ManagedDimLifecycle.load(ctx.server(), root, session.subpath, player)
                ServerPlayNetworking.send(player, ManagedFolderLoadedS2C(
                    subpath = report.subpath,
                    loadedSpecIds = report.loaded,
                    parseErrors = report.parseErrors.map { "${it.filename}: ${it.message}" },
                    layoutErrors = report.errors.map { "${it.specId} (${it.filename}): ${it.reason}" },
                ))
            }
        }
    }

    private fun sendTree(server: MinecraftServer, player: ServerPlayer) {
        val root = rootFor(server) ?: run {
            ServerPlayNetworking.send(player, ManagedErrorS2C("managed-root not configured"))
            return
        }
        val tree = ManagedFolderTree.scan(root)
        val current = ManagedSession.get(player.uuid)?.subpath
        ServerPlayNetworking.send(player, ManagedTreeSnapshotS2C(
            leaves = tree.leaves.map { ManagedLeafEntry(it.subpath, it.specFiles.size) },
            intermediates = tree.intermediates.toList(),
            currentSubpath = current,
        ))
    }

    private fun formatSaveResult(r: CellSaveResult): String =
        "${r.specId}|saved=${r.saved}${r.error?.let { "|err=$it" } ?: ""}"
}
```

- [ ] **Step 2: Wire from `NetworkRegistry.registerNetworking`**

Append at the end of `NetworkRegistry.kt`'s `registerNetworking()` function:

```kotlin
    com.breadmoirai.redstonespecs.network.managed.ManagedNetworkRegistry.register()
```

- [ ] **Step 3: Create `ManagedServerContext`** — a server attachment for the active managed root (referenced above)

Create `src/main/kotlin/com/breadmoirai/redstonespecs/managed/ManagedServerContext.kt`:

```kotlin
package com.breadmoirai.redstonespecs.managed

import net.minecraft.server.MinecraftServer

/**
 * Per-server pin for the managed root. On dedicated servers, set from `SharedSettings`
 * at server start. On integrated servers booted via the world-list "Managed Specs..." flow,
 * set from the chosen root before the server begins ticking.
 */
class ManagedServerContext(val root: ManagedRoot) {
    companion object {
        private val perServer = java.util.WeakHashMap<MinecraftServer, ManagedServerContext>()
        @Synchronized fun set(server: MinecraftServer, ctx: ManagedServerContext) { perServer[server] = ctx }
        @Synchronized fun get(server: MinecraftServer): ManagedServerContext? = perServer[server]
        @Synchronized fun clear(server: MinecraftServer) { perServer.remove(server) }
    }
}
```

- [ ] **Step 4: Build verification**

Run: `cmd.exe /c "./gradlew.bat :26.1:classes"`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/breadmoirai/redstonespecs/managed/ManagedServerContext.kt \
        src/main/kotlin/com/breadmoirai/redstonespecs/network/managed/ManagedNetworkRegistry.kt \
        src/main/kotlin/com/breadmoirai/redstonespecs/network/NetworkRegistry.kt
git commit -m "network/managed: server handlers + ManagedServerContext"
```

---

## Phase 5 — BE auto-bind in managed dim

### Task 16: Wire `SpecBlockEntity` to consult registry on load

**Files:**
- Modify: `src/main/kotlin/com/breadmoirai/redstonespecs/block/SpecBlockEntity.kt`

The current BE binds its spec from on-disk `.spec.kts` via the explicit network packets. In a managed dim, the BE should additionally accept the spec injected by `ManagedDimLifecycle.placeCell` (already done via `be.setSpec(spec)`) and remember its source path. The minimal change is to add a single optional field `managedSourcePath: Path?` to the BE that the lifecycle sets after `setSpec`. This lets `RecordingFinalizer` write back to the source file.

- [ ] **Step 1: Read the existing BE**

Run: `cat src/main/kotlin/com/breadmoirai/redstonespecs/block/SpecBlockEntity.kt | head -80`

Identify where `setSpec` is defined.

- [ ] **Step 2: Add the field**

Add after the existing fields in `SpecBlockEntity`:

```kotlin
    /** Set by ManagedDimLifecycle when placing this BE in a managed cell. Null otherwise. */
    @JvmField var managedSourcePath: java.nio.file.Path? = null
```

This field is *not* persisted to NBT — managed dims are reconstructed from disk on every load, so the binding only needs to live for the session.

- [ ] **Step 3: Update `ManagedDimLifecycle.placeCell` to set it**

In `ManagedDimLifecycle.placeCell`, after `be?.setSpec(spec)`:

```kotlin
        be?.managedSourcePath = folder.resolve(filename)
```

- [ ] **Step 4: Build verification**

Run: `cmd.exe /c "./gradlew.bat :26.1:classes"`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/breadmoirai/redstonespecs/block/SpecBlockEntity.kt \
        src/main/kotlin/com/breadmoirai/redstonespecs/managed/ManagedDimLifecycle.kt
git commit -m "managed: thread source-file path through SpecBlockEntity"
```

---

### Task 17: Make recorder finalize write back to managed source

**Files:**
- Modify: `src/main/kotlin/com/breadmoirai/redstonespecs/runner/RecordingFinalizer.kt` *(or whichever file currently calls `SpecPersistence.save` after a recording ends)*
- Read first: `src/main/kotlin/com/breadmoirai/redstonespecs/block/SpecBlockEntity.kt:stopRecordingAndFinalize`

The existing finalize path computes a fresh `RedstoneSpec` and saves it via `SpecPersistence.save(saveDir, spec, recording)` to the world's central `redstonespecs/` directory. In a managed dim we want to write back to `be.managedSourcePath` instead.

- [ ] **Step 1: Locate the call site**

Run: `grep -n "SpecPersistence.save" src/main/kotlin/com/breadmoirai/redstonespecs/`

Read the call sites.

- [ ] **Step 2: Add a managed-aware fork**

In whatever method finalizes (likely `SpecBlockEntity.stopRecordingAndFinalize` or a helper near it), wrap the existing save call:

```kotlin
        val src = managedSourcePath
        if (src != null) {
            // Managed dim: write back to source file directly. Structure NBT is written by
            // ManagedCellSaver on save-now / unload, not here.
            src.writeText(com.breadmoirai.redstonespecs.data.serial.KtsSpecEmitter.emit(spec))
            LOGGER.debug("[finalize] managed: wrote back to {}", src)
        } else {
            com.breadmoirai.redstonespecs.persistence.SpecPersistence.save(saveDir, spec, recording)
        }
```

(The exact form depends on what variables are in scope. The decision is binary: managed source path set → write to it; else fall through to existing behavior.)

- [ ] **Step 3: Build verification**

Run: `cmd.exe /c "./gradlew.bat :26.1:classes"`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/com/breadmoirai/redstonespecs/...  # whichever file
git commit -m "runner: finalize writes back to managed source path when set"
```

---

## Phase 6 — Client GUI

### Task 18: `ManagedScreen` — folder browser

**Files:**
- Create: `src/client/kotlin/com/breadmoirai/redstonespecs/client/managed/ManagedScreen.kt`

Read existing screens for the patterns (`SpecEditorScreen`, file-browser screen) before writing.

- [ ] **Step 1: Read references**

Run: `find src/client -name '*.kt' | head -20`

Identify the existing screen class hierarchy and the `Screen` API in use. Then implement following that pattern.

- [ ] **Step 2: Implement**

```kotlin
// ManagedScreen.kt
package com.breadmoirai.redstonespecs.client.managed

import com.breadmoirai.redstonespecs.network.managed.*
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component

class ManagedScreen(private var lastSnapshot: ManagedTreeSnapshotS2C? = null) :
    Screen(Component.literal("Managed Specs")) {

    private var newSpecName: String = ""
    private var status: String = "Loading…"

    override fun init() {
        super.init()
        // Refresh tree from server.
        ClientPlayNetworking.send(ListManagedTreeC2S())

        val centerX = width / 2
        var y = 40

        // Tree list — for v1 just render leaf paths as buttons stacked vertically.
        lastSnapshot?.leaves?.forEachIndexed { idx, leaf ->
            addRenderableWidget(
                Button.builder(Component.literal("📁 ${leaf.subpath} (${leaf.specCount})")) {
                    ClientPlayNetworking.send(LoadManagedFolderC2S(leaf.subpath))
                    status = "Loading ${leaf.subpath}…"
                }.bounds(centerX - 150, y, 300, 20).build()
            )
            y += 22
        }
        if (lastSnapshot?.leaves?.isEmpty() == true) {
            // Render an info row; no widget needed.
        }

        y += 20

        // New-spec input + button (only meaningful when a folder is loaded).
        val nameBox = EditBox(font, centerX - 150, y, 200, 20, Component.literal("name"))
        nameBox.setMaxLength(64)
        nameBox.setResponder { newSpecName = it }
        addRenderableWidget(nameBox)
        addRenderableWidget(
            Button.builder(Component.literal("New Spec")) {
                if (newSpecName.isNotBlank()) {
                    ClientPlayNetworking.send(NewManagedSpecC2S(newSpecName))
                    status = "Creating ${newSpecName}…"
                }
            }.bounds(centerX + 60, y, 90, 20).build()
        )
        y += 30

        addRenderableWidget(
            Button.builder(Component.literal("Save Now")) {
                ClientPlayNetworking.send(SaveNowC2S())
                status = "Saving…"
            }.bounds(centerX - 150, y, 145, 20).build()
        )
        addRenderableWidget(
            Button.builder(Component.literal("Unload")) {
                ClientPlayNetworking.send(UnloadManagedFolderC2S())
                status = "Unloading…"
            }.bounds(centerX + 5, y, 145, 20).build()
        )
        y += 30

        addRenderableWidget(
            Button.builder(CommonComponents.GUI_BACK) { onClose() }
                .bounds(centerX - 75, height - 30, 150, 20).build()
        )
    }

    override fun render(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.render(graphics, mouseX, mouseY, partialTick)
        graphics.drawCenteredString(font, title, width / 2, 16, -1)
        val current = lastSnapshot?.currentSubpath?.let { "Loaded: $it" } ?: "No folder loaded"
        graphics.drawCenteredString(font, Component.literal(current), width / 2, 28, -1)
        graphics.drawCenteredString(font, Component.literal(status), width / 2, height - 50, -1)
    }

    fun onTreeSnapshot(snapshot: ManagedTreeSnapshotS2C) {
        this.lastSnapshot = snapshot
        rebuildWidgets()
    }

    fun onFolderLoaded(loaded: ManagedFolderLoadedS2C) {
        status = "Loaded ${loaded.subpath}: ${loaded.loadedSpecIds.size} specs" +
                (if (loaded.parseErrors.isNotEmpty()) " (${loaded.parseErrors.size} parse errors)" else "") +
                (if (loaded.layoutErrors.isNotEmpty()) " (${loaded.layoutErrors.size} layout errors)" else "")
        // Player has been teleported server-side; close the screen so they see the world.
        onClose()
    }

    fun onSaveReport(report: ManagedSaveReportS2C) {
        val saved = report.perSpec.count { it.contains("saved=true") }
        status = "Saved $saved spec(s)"
    }

    fun onError(err: ManagedErrorS2C) {
        status = "Error: ${err.reason}"
    }
}
```

- [ ] **Step 3: Build verification**

Run: `cmd.exe /c "./gradlew.bat :26.1:clientClasses"`
Expected: BUILD SUCCESSFUL. (Adjust imports — e.g. `CommonComponents` location, `drawCenteredString` signature — if compile fails; the existing client code in the repo uses the right idioms for this MC version.)

- [ ] **Step 4: Commit**

```bash
git add src/client/kotlin/com/breadmoirai/redstonespecs/client/managed/ManagedScreen.kt
git commit -m "client/managed: ManagedScreen folder browser"
```

---

### Task 19: Client-side payload receivers

**Files:**
- Create: `src/client/kotlin/com/breadmoirai/redstonespecs/client/managed/ManagedClientNetworking.kt`
- Modify: `src/client/kotlin/com/breadmoirai/redstonespecs/client/RedstonespecsClient.kt` (call `ManagedClientNetworking.register()` from `onInitializeClient`)

- [ ] **Step 1: Implement**

```kotlin
package com.breadmoirai.redstonespecs.client.managed

import com.breadmoirai.redstonespecs.network.managed.*
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.minecraft.client.Minecraft

object ManagedClientNetworking {
    fun register() {
        ClientPlayNetworking.registerGlobalReceiver(ManagedTreeSnapshotS2C.TYPE) { payload, ctx ->
            ctx.client().execute {
                (Minecraft.getInstance().screen as? ManagedScreen)?.onTreeSnapshot(payload)
            }
        }
        ClientPlayNetworking.registerGlobalReceiver(ManagedFolderLoadedS2C.TYPE) { payload, ctx ->
            ctx.client().execute {
                (Minecraft.getInstance().screen as? ManagedScreen)?.onFolderLoaded(payload)
            }
        }
        ClientPlayNetworking.registerGlobalReceiver(ManagedSaveReportS2C.TYPE) { payload, ctx ->
            ctx.client().execute {
                (Minecraft.getInstance().screen as? ManagedScreen)?.onSaveReport(payload)
            }
        }
        ClientPlayNetworking.registerGlobalReceiver(ManagedErrorS2C.TYPE) { payload, ctx ->
            ctx.client().execute {
                (Minecraft.getInstance().screen as? ManagedScreen)?.onError(payload)
            }
        }
    }
}
```

- [ ] **Step 2: Wire**

In `RedstonespecsClient.onInitializeClient()`, append:

```kotlin
        com.breadmoirai.redstonespecs.client.managed.ManagedClientNetworking.register()
```

- [ ] **Step 3: Build verification**

Run: `cmd.exe /c "./gradlew.bat :26.1:clientClasses"`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add src/client/kotlin/com/breadmoirai/redstonespecs/client/managed/ManagedClientNetworking.kt \
        src/client/kotlin/com/breadmoirai/redstonespecs/client/RedstonespecsClient.kt
git commit -m "client/managed: payload receivers + wiring"
```

---

### Task 20: `ManagedRootListScreen` and SelectWorldScreen mixin

**Files:**
- Create: `src/client/kotlin/com/breadmoirai/redstonespecs/client/managed/ManagedRootListScreen.kt`
- Create: `src/client/kotlin/com/breadmoirai/redstonespecs/client/mixin/SelectWorldScreenMixin.kt`
- Modify: `src/client/resources/redstonespecs.client.mixins.json` (add the mixin)

- [ ] **Step 1: Read the project's existing client mixin file**

Run: `cat src/client/resources/redstonespecs.client.mixins.json`

Confirm the mixin file location and structure. (If it doesn't exist yet, find the existing mixins json under `src/main/resources` and study its format.)

- [ ] **Step 2: Implement `ManagedRootListScreen.kt`**

```kotlin
package com.breadmoirai.redstonespecs.client.managed

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import java.nio.file.Path

class ManagedRootListScreen(private val parent: Screen) :
    Screen(Component.literal("Managed Spec Roots")) {

    private val configPath: Path =
        net.fabricmc.loader.api.FabricLoader.getInstance().configDir
            .resolve("redstonespecs/managed-roots.json")
    private var roots: MutableList<String> = ManagedRootsConfig.load(configPath).toMutableList()
    private var newRootInput: String = ""

    override fun init() {
        super.init()
        val cx = width / 2
        var y = 40

        roots.forEachIndexed { idx, r ->
            addRenderableWidget(
                Button.builder(Component.literal("Open: $r")) {
                    openRoot(r)
                }.bounds(cx - 200, y, 320, 20).build()
            )
            addRenderableWidget(
                Button.builder(Component.literal("X")) {
                    roots.removeAt(idx)
                    ManagedRootsConfig.save(configPath, roots)
                    rebuildWidgets()
                }.bounds(cx + 130, y, 20, 20).build()
            )
            y += 22
        }

        y += 10
        val box = EditBox(font, cx - 200, y, 280, 20, Component.literal("path"))
        box.setMaxLength(512)
        box.setResponder { newRootInput = it }
        addRenderableWidget(box)
        addRenderableWidget(
            Button.builder(Component.literal("Add")) {
                if (newRootInput.isNotBlank()) {
                    roots.add(newRootInput)
                    ManagedRootsConfig.save(configPath, roots)
                    rebuildWidgets()
                }
            }.bounds(cx + 90, y, 60, 20).build()
        )

        addRenderableWidget(
            Button.builder(net.minecraft.network.chat.CommonComponents.GUI_BACK) { onClose() }
                .bounds(cx - 75, height - 30, 150, 20).build()
        )
    }

    override fun onClose() {
        Minecraft.getInstance().screen = parent
    }

    override fun render(g: GuiGraphics, mx: Int, my: Int, pt: Float) {
        super.render(g, mx, my, pt)
        g.drawCenteredString(font, title, width / 2, 16, -1)
    }

    private fun openRoot(rootPath: String) {
        // Boot an integrated server pinned to this root.
        ManagedIntegratedBoot.boot(Path.of(rootPath))
    }
}
```

- [ ] **Step 3: Implement the mixin**

```kotlin
// SelectWorldScreenMixin.kt
package com.breadmoirai.redstonespecs.client.mixin

import com.breadmoirai.redstonespecs.client.managed.ManagedRootListScreen
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.worldselection.SelectWorldScreen
import net.minecraft.network.chat.Component
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo

@Mixin(SelectWorldScreen::class)
abstract class SelectWorldScreenMixin : Screen(Component.empty()) {

    @Inject(method = ["init"], at = [At("TAIL")])
    private fun redstonespecs_addManagedButton(ci: CallbackInfo) {
        val self = (this as Any) as Screen
        val w = self.width
        val h = self.height
        addRenderableWidget(
            Button.builder(Component.literal("Managed Specs…")) {
                Minecraft.getInstance().setScreen(ManagedRootListScreen(self))
            }.bounds(w / 2 - 75, h - 52, 150, 20).build()
        )
    }
}
```

> **Implementer notes (re: project memory):**
> - `SelectWorldScreen.init` is declared on the class itself (not inherited), so targeting it directly is fine. If you instead inject on an inherited method, you must target the declaring class with an `instanceof` guard inside the inject body.
> - Position the button somewhere that doesn't overlap vanilla buttons. The `h - 52` Y above is a guess — adjust if it overlaps.

- [ ] **Step 4: Register the mixin**

Add `"client.mixin.SelectWorldScreenMixin"` to the `mixins` array in the client mixin JSON. (Or add a new `redstonespecs.client.mixins.json` if it doesn't exist; reference an existing mixin JSON in the repo for the package layout.)

- [ ] **Step 5: Build verification**

Run: `cmd.exe /c "./gradlew.bat :26.1:clientClasses"`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add src/client/kotlin/com/breadmoirai/redstonespecs/client/managed/ManagedRootListScreen.kt \
        src/client/kotlin/com/breadmoirai/redstonespecs/client/mixin/SelectWorldScreenMixin.kt \
        src/client/resources/redstonespecs.client.mixins.json
git commit -m "client/managed: world-list button + ManagedRootListScreen"
```

---

### Task 21: `ManagedIntegratedBoot` — boot an integrated server pinned to a root

**Files:**
- Create: `src/client/kotlin/com/breadmoirai/redstonespecs/client/managed/ManagedIntegratedBoot.kt`

This is the trickiest client-side piece. The cleanest path is:

1. Pre-create or reuse a stub vanilla world directory under `<.minecraft>/saves/redstonespecs-managed-session/<rootHash>/`.
2. Use `Minecraft.createWorldOpenFlows().createFreshLevel(...)` (or whatever the MC 26.1 entry point is — see the decompiled sources for `CreateWorldScreen` to find the call) to boot the integrated server with that save directory.
3. Before the server starts ticking, call `ManagedServerContext.set(server, ManagedServerContext(ManagedRoot(rootPath)))` via a `ServerLifecycleEvents.SERVER_STARTING` callback.
4. After the player has joined, automatically open `ManagedScreen` on the client via `Minecraft.getInstance().setScreen(ManagedScreen())`.

Implementer note: this involves MC's world-creation flow which is involved. A simpler, equally valid v1 approach: have the user create a normal singleplayer world themselves, and inside it open `ManagedScreen` via a command (`/redstonespecs managed`). The world-list button then navigates to a screen that explains "Open any world and run /redstonespecs managed" or directly creates a world via the standard flow first. **Decide based on how complex the createFreshLevel path turns out to be when read.**

- [ ] **Step 1: Read MC's `CreateWorldScreen.createNewWorld` (or equivalent)**

Run: `find ~/.gradle -path '*loom-cache*' -name 'CreateWorldScreen.java' 2>/dev/null | head -1`

Open the file. Identify the public entry that creates and starts a fresh integrated server given a level directory and settings.

- [ ] **Step 2: Implement (skeleton)**

```kotlin
// ManagedIntegratedBoot.kt
package com.breadmoirai.redstonespecs.client.managed

import com.breadmoirai.redstonespecs.managed.ManagedRoot
import com.breadmoirai.redstonespecs.managed.ManagedServerContext
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.minecraft.client.Minecraft
import org.slf4j.LoggerFactory
import java.nio.file.Path

private val LOGGER = LoggerFactory.getLogger("Redstone Specs")

object ManagedIntegratedBoot {
    /**
     * Boot the integrated server pinned to `rootPath`. Pre-installs a one-shot
     * `SERVER_STARTING` listener that pins the `ManagedServerContext` and unregisters itself.
     */
    fun boot(rootPath: Path) {
        require(rootPath.isAbsolute) { "rootPath must be absolute: $rootPath" }
        val root = ManagedRoot(rootPath)
        val pendingContext = ManagedServerContext(root)

        val listener = object : ServerLifecycleEvents.ServerStarting {
            override fun onServerStarting(server: net.minecraft.server.MinecraftServer) {
                ManagedServerContext.set(server, pendingContext)
                LOGGER.info("[ManagedIntegratedBoot] pinned root '{}' to integrated server", rootPath)
                // Self-unregister:
                ServerLifecycleEvents.SERVER_STARTING.unregister(this)
            }
        }
        ServerLifecycleEvents.SERVER_STARTING.register(listener)

        // TODO(implementer): create or reuse a stub level directory and call MC's
        // createFreshLevel with default settings. Reference: net.minecraft.client.gui.screens
        // .worldselection.CreateWorldScreen.createNewWorld in the decompiled sources.
        // If this proves too involved, fall back to opening the vanilla "Create New World"
        // screen with a name field pre-filled, and do the context pin inside the same listener
        // (it'll fire once the user clicks "Create").
        Minecraft.getInstance().player?.let {
            // If we're already in a world (unlikely), open ManagedScreen directly.
            Minecraft.getInstance().setScreen(ManagedScreen())
        }
    }
}
```

> **Implementer note:** The `TODO(implementer)` above is intentional — Section 14 of the spec lists this as an open question (cheapest way to skip world generation cost on a throwaway integrated server). Resolve it during this task by reading `CreateWorldScreen` and either:
>   1. Calling its create-fresh-level path with a flat preset and the configured root-derived save name, OR
>   2. Documenting in the GUI "you must enter any singleplayer world first" and using the in-world `/redstonespecs managed` command flow.
>
> Either is acceptable for v1. Pick whichever is shorter to implement and update this task's commit message accordingly.

- [ ] **Step 3: Build verification**

Run: `cmd.exe /c "./gradlew.bat :26.1:clientClasses"`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add src/client/kotlin/com/breadmoirai/redstonespecs/client/managed/ManagedIntegratedBoot.kt
git commit -m "client/managed: integrated-server boot pinning ManagedServerContext"
```

---

### Task 22: `/redstonespecs managed` command

**Files:**
- Create: `src/main/kotlin/com/breadmoirai/redstonespecs/managed/ManagedCommand.kt`
- Modify: `src/main/kotlin/com/breadmoirai/redstonespecs/Redstonespecs.kt` (register on `CommandRegistrationCallback`)

A command path is the safest in-world entry point; the GUI button approach can fall back to "join any world, run /redstonespecs managed".

- [ ] **Step 1: Implement**

```kotlin
// ManagedCommand.kt
package com.breadmoirai.redstonespecs.managed

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.Command
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.network.chat.Component

object ManagedCommand {
    fun register(dispatcher: CommandDispatcher<CommandSourceStack>) {
        dispatcher.register(
            Commands.literal("redstonespecs")
                .then(Commands.literal("managed").executes(::open))
        )
    }

    private fun open(ctx: com.mojang.brigadier.context.CommandContext<CommandSourceStack>): Int {
        val src = ctx.source
        val player = src.playerOrException
        // Server-side trigger: send a tree snapshot, which the client UI handler will pop ManagedScreen for.
        // Simpler: client opens the screen by typing the command — but that's client-only. We do server-side here:
        // tell the client to open via a dedicated S2C if needed. For v1, the player runs this in singleplayer
        // and we open the screen client-side via the existing tree snapshot flow:
        net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(
            player,
            // We need a "open managed screen" S2C. Reusing TreeSnapshot works only if a screen is already open.
            // Add a tiny S2C: OpenManagedScreenS2C below in a follow-up if needed. For now, send tree:
            com.breadmoirai.redstonespecs.network.managed.ManagedTreeSnapshotS2C(emptyList(), emptyList(), null)
        )
        src.sendSystemMessage(Component.literal("Open the managed screen via the world-select button (or the in-game keybind)."))
        return Command.SINGLE_SUCCESS
    }
}
```

> **Implementer note:** This task is pragmatic. If you find a cleaner pattern for client-only command actions (Fabric provides `ClientCommandRegistrationCallback`), use it instead. The bare-minimum requirement is: the player can run something to make `ManagedScreen` appear on screen.

- [ ] **Step 2: Wire registration**

In `Redstonespecs.kt`'s `onInitialize`, add:

```kotlin
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STARTING.register { server ->
            // dedicated server: pin from config
            val cfg = com.breadmoirai.redstonespecs.config.SharedSettings.managedRootPath
            if (cfg.isNotBlank()) {
                com.breadmoirai.redstonespecs.managed.ManagedServerContext.set(
                    server,
                    com.breadmoirai.redstonespecs.managed.ManagedServerContext(
                        com.breadmoirai.redstonespecs.managed.ManagedRoot(java.nio.file.Path.of(cfg).toAbsolutePath())
                    ),
                )
            }
        }
        net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback.EVENT.register { dispatcher, _, _ ->
            com.breadmoirai.redstonespecs.managed.ManagedCommand.register(dispatcher)
        }
```

- [ ] **Step 3: Build verification**

Run: `cmd.exe /c "./gradlew.bat :26.1:classes"`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/com/breadmoirai/redstonespecs/managed/ManagedCommand.kt \
        src/main/kotlin/com/breadmoirai/redstonespecs/Redstonespecs.kt
git commit -m "managed: /redstonespecs managed command + dedicated-server context pin"
```

---

## Phase 7 — Tests

### Task 23: Gametest — load + save-back

**Files:**
- Create: `src/gametest/kotlin/com/breadmoirai/redstonespecs/gametest/managed/ManagedDimGameTest.kt`

- [ ] **Step 1: Read existing gametest harness**

Run: `ls src/gametest/kotlin/com/breadmoirai/redstonespecs/`

Open one existing `@GameTest` class to see how the harness is invoked, what `TestContext` looks like, and how `runGameTest` is wired.

- [ ] **Step 2: Implement**

```kotlin
package com.breadmoirai.redstonespecs.gametest.managed

import com.breadmoirai.redstonespecs.managed.*
import com.breadmoirai.redstonespecs.data.RedstoneSpec
import com.breadmoirai.redstonespecs.data.serial.KtsSpecEmitter
import net.fabricmc.fabric.api.gametest.v1.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.core.Vec3i
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

class ManagedDimGameTest {

    @GameTest(template = "redstonespecs:empty")  // a 16x16 empty platform; create if missing
    fun loadFolderPlacesCells(helper: GameTestHelper) {
        val server = helper.level.server
        val root = Files.createTempDirectory("managed-test")
        val folder = root.resolve("set-a").also { it.createDirectories() }
        val a = RedstoneSpec(id = "a", bounds = Vec3i(3, 3, 3), lifespan = 5, structure = null, entries = emptyList())
        val b = RedstoneSpec(id = "b", bounds = Vec3i(2, 2, 2), lifespan = 5, structure = null, entries = emptyList())
        folder.resolve("a.spec.kts").writeText(KtsSpecEmitter.emit(a))
        folder.resolve("b.spec.kts").writeText(KtsSpecEmitter.emit(b))

        val player = helper.makeMockPlayer(net.minecraft.world.level.GameType.CREATIVE)
        val report = ManagedDimLifecycle.load(server, ManagedRoot(root), "set-a", player as net.minecraft.server.level.ServerPlayer)
        helper.assertTrue(report.loaded.containsAll(listOf("a", "b")), "both specs loaded")

        val session = ManagedSession.get(player.uuid)!!
        val cellA = session.loaded["a"]!!.cell
        val cellB = session.loaded["b"]!!.cell
        helper.assertTrue(cellA.origin != cellB.origin, "cells have distinct origins")

        helper.succeed()
    }

    @GameTest(template = "redstonespecs:empty")
    fun saveBackOnlyWritesDirtySpecs(helper: GameTestHelper) {
        val server = helper.level.server
        val root = Files.createTempDirectory("managed-test")
        val folder = root.resolve("set-b").also { it.createDirectories() }
        val a = RedstoneSpec(id = "a", bounds = Vec3i(3, 3, 3), lifespan = 5, structure = null, entries = emptyList())
        val b = RedstoneSpec(id = "b", bounds = Vec3i(2, 2, 2), lifespan = 5, structure = null, entries = emptyList())
        val aFile = folder.resolve("a.spec.kts").also { it.writeText(KtsSpecEmitter.emit(a)) }
        val bFile = folder.resolve("b.spec.kts").also { it.writeText(KtsSpecEmitter.emit(b)) }
        val aBefore = Files.getLastModifiedTime(aFile)
        val bBefore = Files.getLastModifiedTime(bFile)

        val player = helper.makeMockPlayer(net.minecraft.world.level.GameType.CREATIVE) as net.minecraft.server.level.ServerPlayer
        ManagedDimLifecycle.load(server, ManagedRoot(root), "set-b", player)

        // Modify one block inside spec A's bounds.
        val session = ManagedSession.get(player.uuid)!!
        val level = server.getLevel(player.level().dimension())!!
        val cellA = session.loaded["a"]!!.cell
        level.setBlock(cellA.origin, net.minecraft.world.level.block.Blocks.STONE.defaultBlockState(), 2)

        val results = ManagedDimLifecycle.saveNow(server, player.uuid)
        val aResult = results.first { it.specId == "a" }
        val bResult = results.first { it.specId == "b" }
        helper.assertTrue(aResult.saved, "spec a was rewritten")
        helper.assertFalse(bResult.saved, "spec b was NOT rewritten")
        helper.assertTrue(Files.getLastModifiedTime(aFile) != aBefore, "a.spec.kts mtime changed")
        helper.assertTrue(Files.getLastModifiedTime(bFile) == bBefore, "b.spec.kts mtime unchanged")

        helper.succeed()
    }

    @GameTest(template = "redstonespecs:empty")
    fun newSpecCreatesStubFile(helper: GameTestHelper) {
        val server = helper.level.server
        val root = Files.createTempDirectory("managed-test")
        val folder = root.resolve("empty").also { it.createDirectories() }
        val player = helper.makeMockPlayer(net.minecraft.world.level.GameType.CREATIVE) as net.minecraft.server.level.ServerPlayer
        ManagedDimLifecycle.load(server, ManagedRoot(root), "empty", player)
        ManagedNewSpec.create(folder, "fresh")
        helper.assertTrue(Files.exists(folder.resolve("fresh.spec.kts")), "stub file created")
        helper.succeed()
    }
}
```

> **Implementer notes:**
> - The `redstonespecs:empty` structure template must exist under `src/gametest/resources/data/redstonespecs/structure/empty.snbt`. If the project already has a similar empty template, reuse it. Otherwise create a 16×16 empty platform (existing gametests should have an example).
> - `helper.makeMockPlayer` may not exist verbatim on this MC version; check `GameTestHelper`'s API and use whatever the existing gametests use to obtain a `ServerPlayer`.
> - Method signatures (`assertTrue`/`assertFalse`) come from the existing harness — match them.

- [ ] **Step 3: Run gametests**

Run: `cmd.exe /c "./gradlew.bat :26.1:runGameTest"`
Expected: 3 new tests pass alongside the existing suite.

- [ ] **Step 4: Commit**

```bash
git add src/gametest/kotlin/com/breadmoirai/redstonespecs/gametest/managed/ \
        src/gametest/resources/data/redstonespecs/structure/empty.snbt
git commit -m "managed: gametests for load, save-back diff, new-spec stub"
```

---

### Task 24: Build verification — full

- [ ] **Step 1: Run the full verification command**

Run: `cmd.exe /c "./gradlew.bat :26.1:clientClasses :26.1:classes :26.1:gametestClasses :26.1:clientTestClasses :26.1:testClasses"`

Expected: BUILD SUCCESSFUL across all 5 sourcesets.

- [ ] **Step 2: Run all unit tests**

Run: `cmd.exe /c "./gradlew.bat :26.1:test"`

Expected: all tests (existing + new) pass.

- [ ] **Step 3: Run gametests**

Run: `cmd.exe /c "./gradlew.bat :26.1:runGameTest"`

Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit (if any fixups were needed)**

If you had to make small fixups during verification, commit them:

```bash
git commit -m "managed: build/test fixups"
```

Otherwise skip.

---

## Phase 8 — Documentation

### Task 25: Add architecture article + INDEX entries

**Files:**
- Create: `docs/architecture/managed-redstone-worlds.md`
- Modify: `docs/architecture/INDEX.md`

- [ ] **Step 1: Write the article**

```markdown
---
title: Managed redstone worlds
tags: [managed-worlds, dimensions, grid, persistence]
summary: A void-dim-per-folder workspace; specs laid out in a fixed grid, only per-spec bounds saved back to disk.
---

# Managed redstone worlds

A managed redstone world is a void dimension whose contents are deterministically laid out
from a folder of `.spec.kts` files. One leaf folder = one void dim. Specs sit in a fixed-size
grid; on unload, only blocks inside each spec's bounds are written back to its `.spec.kts`
and structure NBT.

See `docs/superpowers/specs/2026-05-08-managed-redstone-worlds-design.md` for the full design.

## Key invariants

- **Cell origin = grid slot.** Slot index is `filename-sorted index`; row wraps at `rowMax`.
  No persisted slot — recomputed each load. Renaming a spec shuffles slots.
- **Save scope = spec bounds.** Anything outside the AABB `cellOrigin..cellOrigin+bounds` is
  discarded on unload. Decoration in the cell margin does not persist.
- **Server-authoritative.** The same model as `network/Packets.kt`: clients propose, server
  validates against `ManagedRoot.resolveSubpath` (path traversal guard) and acts.
- **Dim id = `redstonespecs:managed/<sanitized-subpath>`.** Sanitization replaces non-allowed
  chars with `_`. Two distinct subpaths sanitizing to the same id is a load-time error.
- **`SpecBlockEntity.managedSourcePath`** is the binding from a runner/recorder block in a
  managed cell back to the source `.spec.kts`. Not persisted to NBT — managed dims rebuild
  from disk every session.

## Components

- `managed/ManagedRoot`, `ManagedFolderTree`, `GridLayout`, `DimIdSanitizer` — pure data.
- `managed/ManagedDimensions` — registered dim type (data-pack JSON) + per-folder level keys.
- `managed/ManagedDimRegistry` — dynamic level registration via `FabricDimensions.add`.
- `managed/ManagedDimLifecycle` — load/saveNow/unload orchestration.
- `managed/ManagedCellSaver` — captures cell, diffs vs in-memory snapshot, writes back.
- `managed/ManagedSession`, `ManagedServerContext`, `ManagedNewSpec`, `ManagedCommand`.
- `network/managed/ManagedPackets` + `ManagedNetworkRegistry` — wire protocol.
- `client/managed/ManagedScreen`, `ManagedRootListScreen`, `SelectWorldScreenMixin`,
  `ManagedIntegratedBoot`, `ManagedClientNetworking`, `ManagedRootsConfig`.

## Where to start reading

- *"How does loading a folder work?"* → `ManagedDimLifecycle.load`.
- *"What gets saved?"* → `ManagedCellSaver.captureAndSaveIfDirty`.
- *"How does the GUI show the folder tree?"* → `ManagedScreen` + `ManagedTreeSnapshotS2C`.
```

- [ ] **Step 2: Update INDEX.md**

Add to `docs/architecture/INDEX.md`:

```markdown
- [Managed redstone worlds](managed-redstone-worlds.md) — Void-dim-per-folder workspace; deterministic grid layout; per-spec bounds save-back. Tags: managed-worlds, dimensions, grid, persistence.
```

- [ ] **Step 3: Commit**

```bash
git add docs/architecture/managed-redstone-worlds.md docs/architecture/INDEX.md
git commit -m "docs: managed redstone worlds architecture article"
```

---

## Final verification

- [ ] **Step 1: Full build + tests**

Run: `cmd.exe /c "./gradlew.bat :26.1:clientClasses :26.1:classes :26.1:gametestClasses :26.1:clientTestClasses :26.1:testClasses :26.1:test :26.1:runGameTest"`

Expected: BUILD SUCCESSFUL.

- [ ] **Step 2: Smoke test in-game**

Per the project's CLAUDE.md ("UI testing required for UI changes"): launch the client (`cmd.exe /c "./gradlew.bat :26.1:runClient"`), click "Managed Specs…" on the world list, add a temp folder root, open it, verify cells appear, run a recorder, save now, verify the file changed.

- [ ] **Step 3: Memory + plan housekeeping**

If anything in the project memory (e.g. `feedback_build_command.md`) needs an update because of a new sourceset, edit it. Otherwise nothing to do.

---

## Self-review notes (writer's check)

- **Spec coverage:** all 14 spec sections map to tasks (Sections 3/4 → Tasks 1–4; 5 → 5–11; 6 → 8, 17; 7 → 3, 12; 8 → 9; 9 → 14, 15; 10 → 18–22; 11 → 14–22 error paths; 12 → 23, 24; 13 → file-layout-driven across all tasks; 14 → addressed inline as implementer notes).
- **Placeholders:** one explicit `TODO(implementer)` remains in Task 21 (`ManagedIntegratedBoot.boot`). It is bracketed with a clear decision tree (option A: `createFreshLevel`; option B: command-flow fallback) and the implementer is told to pick one and remove the TODO. This is the open question called out in the design doc.
- **Type consistency:** `ManagedCell`, `LayoutResult`, `LoadedSpec`, `ManagedSession`, `CellSaveResult`, `LoadFolderReport`, payload type names match across tasks. `SharedSettings` field names (`managedCellSize`, `managedCellGap`, `managedRowMax`, `managedGridYBase`, `managedRootPath`) are consistent.
- **Frequent commits:** every task ends in a commit; substantial tasks have a single focused commit.
