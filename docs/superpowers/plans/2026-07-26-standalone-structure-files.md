# Standalone Structure Files in the Explorer — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make `.nbt` structure files first-class place/capture entities in the project Explorer — click to place into an auto-assigned region, "Save Structure" to auto-fit-capture the selected file, "New Structure" to create an empty one — independent of specs.

**Architecture:** A pure Kotlin math unit (`StructureRegionMath`) computes the tight auto-fit box and the placement anchor. `StructurePersistence` gains level-driven `saveAutoFitToFile`/`placeStructureCentered`. `ProjectDimRegistry` hands out a disjoint per-structure region lane and tracks the last-placed box for cheap re-clears. Three C2S packets + one S2C packet drive three server handlers in `ProjectNetworkRegistry`. The client wires `.nbt` clicks and two header actions into those packets.

**Tech Stack:** Kotlin 2.x, Fabric, Minecraft **26.1.2** (Mojang mappings; `LevelHeightAccessor.getMinY()/getMaxY()`), `StructureTemplate`/`NbtIo`, Kotest (`FunSpec` autoscanned in `src/test`; `RedstoneTestSpec`/`ClientSpec` registered in `GametestSentinel` for game/client tests).

## Global Constraints

- **Build/test runner:** always `cmd.exe /c "gradlew.bat <task>"` — no `./` prefix.
- **Stonecutter task path:** active version is `:26.1:` (e.g. `:26.1:test`, `:26.1:testClasses`).
- **Kotest + Gradle `--tests` does not work** (false "No tests found"). Run the unfiltered task and read `build/test-results/.../*.xml` or the console summary.
- **Full compile sanity (all 5 sourcesets):** `cmd.exe /c "gradlew.bat :26.1:clientClasses :26.1:classes :26.1:gametestClasses :26.1:clientTestClasses :26.1:testClasses"`.
- **New gametest/clientTest specs MUST be registered** in `src/gametest/.../GametestSentinel.kt` (`specs = listOf(...)`) — autoscan is off for those sourcesets. `src/test` unit specs ARE autoscanned (no registration).
- **`internal` is per-sourceset** — anything a test sourceset touches must be `public`.
- **Kotlin `kotlin.io.path` idioms** (`path.name`, `path.exists()`, `base / "sub"`), not raw `java.nio.file`.
- **Git:** commit per task. Do **not** add a `Co-Authored-By: Claude` trailer.
- **Do not run the full client (`runClient`) in tests** — use the client-test harness.

---

### Task 1: Pure region math (`StructureRegionMath.kt`)

**Files:**
- Create: `src/main/kotlin/com/breadmoirai/redstonespecs/project/StructureRegionMath.kt`
- Test: `src/test/kotlin/com/breadmoirai/redstonespecs/project/StructureRegionMathTest.kt`

**Interfaces:**
- Consumes: nothing (leaf, no Minecraft deps).
- Produces:
  - `data class FitBox(val minX: Int, val minY: Int, val minZ: Int, val sizeX: Int, val sizeY: Int, val sizeZ: Int)`
  - `fun autoFit(dimX: Int, dimY: Int, dimZ: Int, isNonAir: (Int, Int, Int) -> Boolean): FitBox?`
  - `fun centeredStart(regionStart: Int, regionWidth: Int, size: Int): Int`
  - `const val TALL_THRESHOLD = 256`
  - `fun anchorY(structHeight: Int, yBase: Int, regionMinY: Int, regionHeight: Int): Int`

- [ ] **Step 1: Write the failing tests**

Create `src/test/kotlin/com/breadmoirai/redstonespecs/project/StructureRegionMathTest.kt`:

```kotlin
package com.breadmoirai.redstonespecs.project

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

class StructureRegionMathTest : FunSpec({

    test("autoFit returns null when the volume has no non-air") {
        autoFit(4, 4, 4) { _, _, _ -> false }.shouldBeNull()
    }

    test("autoFit tightly boxes a single non-air cell") {
        val box = autoFit(8, 8, 8) { x, y, z -> x == 2 && y == 3 && z == 4 }
        box shouldBe FitBox(2, 3, 4, 1, 1, 1)
    }

    test("autoFit tightly boxes scattered non-air cells") {
        // cells at (1,1,1) and (5,2,6) -> min (1,1,1), max (5,2,6) -> size (5,2,6)
        val hits = setOf(Triple(1, 1, 1), Triple(5, 2, 6))
        val box = autoFit(8, 8, 8) { x, y, z -> Triple(x, y, z) in hits }
        box shouldBe FitBox(1, 1, 1, 5, 2, 6)
    }

    test("centeredStart centers a box in a region (floor-divides odd slack)") {
        centeredStart(100, 16, 4) shouldBe 106   // 100 + (16-4)/2
        centeredStart(0, 15, 4) shouldBe 5        // 0 + (15-4)/2 = 5
    }

    test("anchorY floors short structures at yBase") {
        anchorY(structHeight = 10, yBase = 64, regionMinY = -64, regionHeight = 384) shouldBe 64
        anchorY(structHeight = 255, yBase = 64, regionMinY = -64, regionHeight = 384) shouldBe 64
    }

    test("anchorY vertically centers structures at or above the tall threshold") {
        // regionMinY -64, regionHeight 384, structHeight 256 -> -64 + (384-256)/2 = 0
        anchorY(structHeight = 256, yBase = 64, regionMinY = -64, regionHeight = 384) shouldBe 0
    }
})
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `cmd.exe /c "gradlew.bat :26.1:test"`
Expected: FAIL — unresolved `autoFit` / `FitBox` / `centeredStart` / `anchorY`.

- [ ] **Step 3: Write the minimal implementation**

Create `src/main/kotlin/com/breadmoirai/redstonespecs/project/StructureRegionMath.kt`:

```kotlin
package com.breadmoirai.redstonespecs.project

/** A tight axis-aligned box in region-local coordinates. */
data class FitBox(
    val minX: Int, val minY: Int, val minZ: Int,
    val sizeX: Int, val sizeY: Int, val sizeZ: Int,
)

/** Structures this tall (blocks) can't be floored at sea level without hitting the ceiling. */
const val TALL_THRESHOLD = 256

/**
 * Scans a [dimX]x[dimY]x[dimZ] volume in local coords and returns the tight box enclosing every
 * cell for which [isNonAir] is true, or null if none are.
 */
fun autoFit(dimX: Int, dimY: Int, dimZ: Int, isNonAir: (Int, Int, Int) -> Boolean): FitBox? {
    var minX = Int.MAX_VALUE; var minY = Int.MAX_VALUE; var minZ = Int.MAX_VALUE
    var maxX = Int.MIN_VALUE; var maxY = Int.MIN_VALUE; var maxZ = Int.MIN_VALUE
    var any = false
    for (x in 0 until dimX) for (y in 0 until dimY) for (z in 0 until dimZ) {
        if (isNonAir(x, y, z)) {
            any = true
            if (x < minX) minX = x; if (x > maxX) maxX = x
            if (y < minY) minY = y; if (y > maxY) maxY = y
            if (z < minZ) minZ = z; if (z > maxZ) maxZ = z
        }
    }
    if (!any) return null
    return FitBox(minX, minY, minZ, maxX - minX + 1, maxY - minY + 1, maxZ - minZ + 1)
}

/** Start coord so a [size]-wide box is centered in a [regionWidth]-wide region beginning at [regionStart]. */
fun centeredStart(regionStart: Int, regionWidth: Int, size: Int): Int = regionStart + (regionWidth - size) / 2

/**
 * Y origin for placing a structure of height [structHeight]. Floored at [yBase] (sea level) for
 * normal builds; vertically centered in `[regionMinY, regionMinY + regionHeight)` once the
 * structure reaches [TALL_THRESHOLD], where flooring at sea level would clip the build ceiling.
 */
fun anchorY(structHeight: Int, yBase: Int, regionMinY: Int, regionHeight: Int): Int =
    if (structHeight >= TALL_THRESHOLD) regionMinY + (regionHeight - structHeight) / 2 else yBase
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `cmd.exe /c "gradlew.bat :26.1:test"`
Expected: PASS (all `StructureRegionMathTest` cases).

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/breadmoirai/redstonespecs/project/StructureRegionMath.kt \
        src/test/kotlin/com/breadmoirai/redstonespecs/project/StructureRegionMathTest.kt
git commit -m "feat(project): pure auto-fit box + placement-anchor math for structures"
```

---

### Task 2: Create empty structure file (`ProjectNewStructure`)

**Files:**
- Create: `src/main/kotlin/com/breadmoirai/redstonespecs/project/ProjectNewStructure.kt`
- Test: `src/test/kotlin/com/breadmoirai/redstonespecs/project/ProjectNewStructureTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces: `object ProjectNewStructure { fun create(folder: java.nio.file.Path, name: String): java.nio.file.Path }`

Mirrors `ProjectNewSpec.create` (same name validation regex), but writes an empty `StructureTemplate` NBT as `<name>.nbt`.

- [ ] **Step 1: Write the failing test**

Create `src/test/kotlin/com/breadmoirai/redstonespecs/project/ProjectNewStructureTest.kt`:

```kotlin
package com.breadmoirai.redstonespecs.project

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import net.minecraft.nbt.NbtAccounter
import net.minecraft.nbt.NbtIo
import java.nio.file.Files
import kotlin.io.path.exists
import kotlin.io.path.name

class ProjectNewStructureTest : FunSpec({

    test("create writes a readable <name>.nbt with a size tag") {
        val dir = Files.createTempDirectory("new-structure")
        val file = ProjectNewStructure.create(dir, "gadget")
        file.name shouldBe "gadget.nbt"
        file.exists().shouldBeTrue()
        // Re-read: a valid compressed structure NBT carries an int-list "size".
        val tag = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap())
        tag.contains("size").shouldBeTrue()
    }

    test("create rejects a blank or illegal name and an existing file") {
        val dir = Files.createTempDirectory("new-structure-bad")
        shouldThrow<IllegalArgumentException> { ProjectNewStructure.create(dir, "") }
        shouldThrow<IllegalArgumentException> { ProjectNewStructure.create(dir, "has space") }
        ProjectNewStructure.create(dir, "dup")
        shouldThrow<IllegalArgumentException> { ProjectNewStructure.create(dir, "dup") }
    }
})
```

- [ ] **Step 2: Run to verify it fails**

Run: `cmd.exe /c "gradlew.bat :26.1:test"`
Expected: FAIL — unresolved `ProjectNewStructure`.

- [ ] **Step 3: Write the minimal implementation**

Create `src/main/kotlin/com/breadmoirai/redstonespecs/project/ProjectNewStructure.kt`:

```kotlin
package com.breadmoirai.redstonespecs.project

import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.NbtIo
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate
import org.slf4j.LoggerFactory
import java.nio.file.Path
import kotlin.io.path.exists

private val LOGGER = LoggerFactory.getLogger("Redstone Specs")

object ProjectNewStructure {
    /**
     * Creates an empty `<name>.nbt` structure in [folder]. Returns the path; throws if [name] is
     * blank/illegal or the file already exists. Caller should re-scan the tree afterwards so the
     * new file appears in the Explorer.
     */
    fun create(folder: Path, name: String): Path {
        require(name.isNotBlank()) { "structure name must not be blank" }
        require(name.matches(Regex("[a-zA-Z0-9_\\-]+"))) {
            "structure name must match [a-zA-Z0-9_-]+, got: '$name'"
        }
        val file = folder.resolve("$name.nbt")
        require(!file.exists()) { "structure file already exists: $file" }
        val nbt = StructureTemplate().save(CompoundTag())
        NbtIo.writeCompressed(nbt, file)
        LOGGER.info("[ProjectNewStructure] created empty structure '{}'", file)
        return file
    }
}
```

> If `StructureTemplate().save(...)` throws because Minecraft bootstrap has not run in this
> sourceset, add `net.minecraft.SharedConstants.tryDetectVersion()` + `net.minecraft.server.Bootstrap.bootStrap()`
> in a `beforeSpec { }` block in the test. Do NOT add bootstrap to production code.

- [ ] **Step 4: Run to verify it passes**

Run: `cmd.exe /c "gradlew.bat :26.1:test"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/breadmoirai/redstonespecs/project/ProjectNewStructure.kt \
        src/test/kotlin/com/breadmoirai/redstonespecs/project/ProjectNewStructureTest.kt
git commit -m "feat(project): ProjectNewStructure creates an empty .nbt file"
```

---

### Task 3: Config + structure region assignment + placed-box tracking

**Files:**
- Modify: `src/main/kotlin/com/breadmoirai/redstonespecs/config/SharedSettings.kt`
- Modify: `src/main/kotlin/com/breadmoirai/redstonespecs/project/ProjectDimRegistry.kt`
- Test: `src/test/kotlin/com/breadmoirai/redstonespecs/project/ProjectDimRegistryTest.kt` (extend)

**Interfaces:**
- Consumes: `SharedSettings.projectGridYBase`, `ProjectDimRegistry.REGION_PAD`.
- Produces:
  - `SharedSettings.structureRegionChunks: Int` (default 9)
  - `data class PlacedBox(val origin: net.minecraft.core.BlockPos, val size: net.minecraft.core.Vec3i)` (top-level, package `project`)
  - `ProjectDimRegistry.getOrAssignStructureRegion(subpath: String): BlockPos`
  - `ProjectDimRegistry.structureRegionOriginOf(subpath: String): BlockPos?`
  - `ProjectDimRegistry.placedBoxOf(subpath: String): PlacedBox?`
  - `ProjectDimRegistry.setPlacedBox(subpath: String, box: PlacedBox)`
  - `ProjectDimRegistry.STRUCTURE_LANE_Z: Int` (companion const)

- [ ] **Step 1: Write the failing tests (extend the existing spec)**

Add these tests inside the existing `ProjectDimRegistryTest` body (they use its `newRegistry()` helper — reuse it; if it's private, the tests live in the same file so it is in scope):

```kotlin
    test("getOrAssignStructureRegion is idempotent and distinct per subpath") {
        val r = newRegistry()
        val a1 = r.getOrAssignStructureRegion("things/box.nbt")
        val a2 = r.getOrAssignStructureRegion("things/box.nbt")
        a1 shouldBe a2
        val b = r.getOrAssignStructureRegion("things/other.nbt")
        (a1 == b) shouldBe false
    }

    test("structure regions sit in a lane disjoint from spec-folder regions") {
        val r = newRegistry()
        val spec = r.getOrAssignRegion("set/a")           // spec lane: z == 0
        val struct = r.getOrAssignStructureRegion("s.nbt") // structure lane: z == STRUCTURE_LANE_Z
        spec.z shouldBe 0
        struct.z shouldBe ProjectDimRegistry.STRUCTURE_LANE_Z
    }

    test("placed-box round-trips per subpath") {
        val r = newRegistry()
        r.placedBoxOf("s.nbt").shouldBeNull()
        val box = PlacedBox(net.minecraft.core.BlockPos(1, 2, 3), net.minecraft.core.Vec3i(4, 5, 6))
        r.setPlacedBox("s.nbt", box)
        r.placedBoxOf("s.nbt") shouldBe box
    }
```

- [ ] **Step 2: Run to verify they fail**

Run: `cmd.exe /c "gradlew.bat :26.1:test"`
Expected: FAIL — unresolved `getOrAssignStructureRegion` / `PlacedBox` / `STRUCTURE_LANE_Z` / `structureRegionChunks`.

- [ ] **Step 3: Implement**

In `SharedSettings.kt`, add the field:

```kotlin
    /** Side length, in chunks, of a standalone structure's build region (full world height). */
    var structureRegionChunks: Int = 9
```

In `ProjectDimRegistry.kt`:

Add a top-level data class (below the imports, above the class):

```kotlin
/** A structure's placed footprint in the world: absolute [origin] and [size]. */
data class PlacedBox(val origin: BlockPos, val size: Vec3i)
```

Add these imports if missing: `import net.minecraft.core.Vec3i`.

Add fields inside the class (next to `bySubpath`):

```kotlin
    private val structureBySubpath = ConcurrentHashMap<String, BlockPos>()
    private val nextStructureIndex = AtomicInteger(0)
    private val placedBoxes = ConcurrentHashMap<String, PlacedBox>()
```

Add methods inside the class:

```kotlin
    /**
     * Region origin (x,z corner; y == grid base) for a standalone structure [subpath], assigned
     * on first use. Structures occupy their own +X lane at z == [STRUCTURE_LANE_Z], disjoint from
     * spec-folder regions, so the two never collide.
     */
    fun getOrAssignStructureRegion(subpath: String): BlockPos {
        structureBySubpath[subpath]?.let { return it }
        val idx = nextStructureIndex.getAndIncrement()
        val width = SharedSettings.structureRegionChunks * 16
        val origin = BlockPos(idx * (width + REGION_PAD), SharedSettings.projectGridYBase, STRUCTURE_LANE_Z)
        structureBySubpath[subpath] = origin
        LOGGER.info("[ProjectDimRegistry] assigned structure region #{} at {} to '{}'", idx, origin, subpath)
        return origin
    }

    fun structureRegionOriginOf(subpath: String): BlockPos? = structureBySubpath[subpath]

    fun placedBoxOf(subpath: String): PlacedBox? = placedBoxes[subpath]

    fun setPlacedBox(subpath: String, box: PlacedBox) { placedBoxes[subpath] = box }
```

Add the constant inside `companion object`:

```kotlin
        /** Z coordinate of the standalone-structure region lane (far from the spec lane at z=0). */
        const val STRUCTURE_LANE_Z = 4096
```

- [ ] **Step 4: Run to verify they pass**

Run: `cmd.exe /c "gradlew.bat :26.1:test"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/breadmoirai/redstonespecs/config/SharedSettings.kt \
        src/main/kotlin/com/breadmoirai/redstonespecs/project/ProjectDimRegistry.kt \
        src/test/kotlin/com/breadmoirai/redstonespecs/project/ProjectDimRegistryTest.kt
git commit -m "feat(project): structure region lane + placed-box tracking + region-size config"
```

---

### Task 4: Level-driven capture + placement (`StructurePersistence` extend)

**Files:**
- Modify: `src/main/kotlin/com/breadmoirai/redstonespecs/persistence/StructurePersistence.kt`
- Create (gametest): `src/gametest/kotlin/com/breadmoirai/redstonespecs/test/persistence/StructureRegionPersistenceSpec.kt`
- Modify (register): `src/gametest/kotlin/com/breadmoirai/redstonespecs/test/GametestSentinel.kt`

**Interfaces:**
- Consumes (Task 1): `autoFit`, `FitBox`, `centeredStart`, `anchorY`; (Task 3) `PlacedBox`.
- Produces (added to `object StructurePersistence`):
  - `fun saveAutoFitToFile(file: Path, level: ServerLevel, regionOrigin: BlockPos, regionSizeXZ: Int, regionMinY: Int, regionMaxY: Int): PlacedBox?`
  - `fun placeStructureCentered(file: Path, level: ServerLevel, regionOrigin: BlockPos, regionSizeXZ: Int, regionMinY: Int, regionMaxY: Int, yBase: Int): PlacedBox?`

- [ ] **Step 1: Write the failing gametest**

Create `src/gametest/kotlin/com/breadmoirai/redstonespecs/test/persistence/StructureRegionPersistenceSpec.kt`:

```kotlin
package com.breadmoirai.redstonespecs.test.persistence

import com.breadmoirai.redstonespecs.persistence.StructurePersistence
import com.breadmoirai.redstonespecs.project.ProjectDimRegistry
import com.breadmoirai.redstonespecs.testing.RedstoneTestSpec
import com.breadmoirai.redstonespecs.testing.server.onServer
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import net.minecraft.core.BlockPos
import net.minecraft.core.Vec3i
import net.minecraft.world.level.block.Blocks
import java.nio.file.Files

class StructureRegionPersistenceSpec : RedstoneTestSpec({

    test("auto-fit save captures the tight non-air box; place re-centers it") {
        onServer {
            val level = overworld()
            val file = Files.createTempFile("struct-roundtrip", ".nbt")
            // Use a small 32-wide region far out in the structure lane to keep the scan cheap.
            val region = BlockPos(200_000, 64, ProjectDimRegistry.STRUCTURE_LANE_Z)
            val sizeXZ = 32
            val minY = level.minY
            val maxY = level.maxY

            // Clear then build a known 2x1x3 gold box at a known offset inside the region.
            StructurePersistence.clearBounds(level, BlockPos(region.x, minY, region.z), Vec3i(sizeXZ, maxY - minY + 1, sizeXZ))
            val buildOrigin = region.offset(10, 0, 12)  // y == 64
            level.setBlock(buildOrigin.offset(0, 0, 0), Blocks.GOLD_BLOCK.defaultBlockState(), 2)
            level.setBlock(buildOrigin.offset(1, 0, 0), Blocks.GOLD_BLOCK.defaultBlockState(), 2)
            level.setBlock(buildOrigin.offset(0, 0, 2), Blocks.GOLD_BLOCK.defaultBlockState(), 2)

            val captured = StructurePersistence.saveAutoFitToFile(file, level, region, sizeXZ, minY, maxY).shouldNotBeNull()
            captured.size shouldBe Vec3i(2, 1, 3)

            // Clear the region, then place the file back; it should be centered in X/Z, floored at 64.
            StructurePersistence.clearBounds(level, BlockPos(region.x, minY, region.z), Vec3i(sizeXZ, maxY - minY + 1, sizeXZ))
            val placed = StructurePersistence.placeStructureCentered(file, level, region, sizeXZ, minY, maxY, 64).shouldNotBeNull()
            placed.size shouldBe Vec3i(2, 1, 3)
            placed.origin.x shouldBe (region.x + (sizeXZ - 2) / 2)
            placed.origin.z shouldBe (region.z + (sizeXZ - 3) / 2)
            placed.origin.y shouldBe 64
            // The centered gold block is actually in the world.
            level.getBlockState(placed.origin).`is`(Blocks.GOLD_BLOCK) shouldBe true

            Files.deleteIfExists(file)
        }
    }

    test("auto-fit save of an empty region writes a file and returns null") {
        onServer {
            val level = overworld()
            val file = Files.createTempFile("struct-empty", ".nbt")
            val region = BlockPos(210_000, 64, ProjectDimRegistry.STRUCTURE_LANE_Z)
            val sizeXZ = 16
            StructurePersistence.clearBounds(level, BlockPos(region.x, level.minY, region.z), Vec3i(sizeXZ, level.maxY - level.minY + 1, sizeXZ))
            val result = StructurePersistence.saveAutoFitToFile(file, level, region, sizeXZ, level.minY, level.maxY)
            (result == null) shouldBe true
            Files.exists(file) shouldBe true
            Files.deleteIfExists(file)
        }
    }
})
```

Register it in `GametestSentinel.kt`: add the import
`import com.breadmoirai.redstonespecs.test.persistence.StructureRegionPersistenceSpec`
and add `StructureRegionPersistenceSpec::class,` to the `specs = listOf(...)`.

- [ ] **Step 2: Run to verify it fails**

Run: `cmd.exe /c "gradlew.bat :26.1:runGametest"`
Expected: FAIL — unresolved `saveAutoFitToFile` / `placeStructureCentered`.

> If `runGametest` is not the task name in this repo, use the gametest launch task the other
> `*Spec` gametests run under (see how `ProjectTeleportSpec` is invoked); the sentinel's
> `@GameTest` entrypoint drives all registered specs.

- [ ] **Step 3: Implement (add to `StructurePersistence`)**

Add imports at the top of `StructurePersistence.kt`:

```kotlin
import com.breadmoirai.redstonespecs.project.PlacedBox
import com.breadmoirai.redstonespecs.project.anchorY
import com.breadmoirai.redstonespecs.project.autoFit
import com.breadmoirai.redstonespecs.project.centeredStart
```

Add these two methods inside `object StructurePersistence`:

```kotlin
    /**
     * Scans the full region volume ([regionSizeXZ] wide, `regionMinY..regionMaxY` tall) for
     * non-air, computes the tight box, and writes exactly that box into [file] as a compressed
     * structure. Returns the captured [PlacedBox] (absolute origin + size), or null when the
     * region is empty (an empty structure is still written).
     */
    fun saveAutoFitToFile(
        file: Path, level: ServerLevel, regionOrigin: BlockPos,
        regionSizeXZ: Int, regionMinY: Int, regionMaxY: Int,
    ): PlacedBox? {
        val dimY = regionMaxY - regionMinY + 1
        val fit = autoFit(regionSizeXZ, dimY, regionSizeXZ) { lx, ly, lz ->
            !level.getBlockState(BlockPos(regionOrigin.x + lx, regionMinY + ly, regionOrigin.z + lz)).`is`(Blocks.AIR)
        }
        file.parent?.createDirectories()
        val template = StructureTemplate()
        if (fit == null) {
            try { NbtIo.writeCompressed(template.save(CompoundTag()), file) }
            catch (e: IOException) { LOGGER.error("[StructurePersistence#saveAutoFit] write empty '{}': {}", file, e.message) }
            return null
        }
        val tightOrigin = BlockPos(regionOrigin.x + fit.minX, regionMinY + fit.minY, regionOrigin.z + fit.minZ)
        val size = Vec3i(fit.sizeX, fit.sizeY, fit.sizeZ)
        template.fillFromWorld(level, tightOrigin, size, false, emptyList())
        try { NbtIo.writeCompressed(template.save(CompoundTag()), file) }
        catch (e: IOException) { LOGGER.error("[StructurePersistence#saveAutoFit] write '{}': {}", file, e.message) }
        LOGGER.debug("[StructurePersistence#saveAutoFit] captured {} at {} -> {}", size, tightOrigin, file)
        return PlacedBox(tightOrigin, size)
    }

    /**
     * Loads [file] and places it centered (X/Z) in the region, floored at [yBase] unless the
     * structure is tall enough to require vertical centering (see [anchorY]). Returns the placed
     * [PlacedBox], or null when [file] does not exist / fails to read.
     */
    fun placeStructureCentered(
        file: Path, level: ServerLevel, regionOrigin: BlockPos,
        regionSizeXZ: Int, regionMinY: Int, regionMaxY: Int, yBase: Int,
    ): PlacedBox? {
        if (!file.exists()) {
            LOGGER.warn("[StructurePersistence#placeCentered] file '{}' not found", file)
            return null
        }
        return try {
            val nbt = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap())
            val blockGetter: HolderGetter<Block> = level.registryAccess().lookupOrThrow(Registries.BLOCK)
            val template = StructureTemplate()
            template.load(blockGetter, nbt)
            val size = template.size  // Vec3i
            val regionHeight = regionMaxY - regionMinY + 1
            val origin = BlockPos(
                centeredStart(regionOrigin.x, regionSizeXZ, size.x),
                anchorY(size.y, yBase, regionMinY, regionHeight),
                centeredStart(regionOrigin.z, regionSizeXZ, size.z),
            )
            template.placeInWorld(level, origin, origin, StructurePlaceSettings(), level.random, 2)
            LOGGER.debug("[StructurePersistence#placeCentered] placed {} ({}) at {}", file, size, origin)
            PlacedBox(origin, size)
        } catch (e: IOException) {
            LOGGER.error("[StructurePersistence#placeCentered] read '{}': {}", file, e.message)
            null
        }
    }
```

- [ ] **Step 4: Run to verify it passes**

Run: `cmd.exe /c "gradlew.bat :26.1:runGametest"`
Expected: PASS — both `StructureRegionPersistenceSpec` cases.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/breadmoirai/redstonespecs/persistence/StructurePersistence.kt \
        src/gametest/kotlin/com/breadmoirai/redstonespecs/test/persistence/StructureRegionPersistenceSpec.kt \
        src/gametest/kotlin/com/breadmoirai/redstonespecs/test/GametestSentinel.kt
git commit -m "feat(persistence): auto-fit capture + centered placement for structures"
```

---

### Task 5: Packets (`network/project`)

**Files:**
- Modify: `src/main/kotlin/com/breadmoirai/redstonespecs/network/project/ProjectPackets.kt`
- Modify: `src/main/kotlin/com/breadmoirai/redstonespecs/network/project/ProjectNetworkRegistry.kt` (register types only)
- Test: `src/test/kotlin/com/breadmoirai/redstonespecs/network/StructurePacketsTest.kt`

**Interfaces:**
- Produces:
  - `data class PlaceStructureC2S(val subpath: String)` — id `project_place_structure`
  - `data class SaveStructureC2S(val subpath: String)` — id `project_save_structure`
  - `data class NewStructureC2S(val name: String)` — id `project_new_structure`
  - `data class StructureResultS2C(val subpath: String, val sizeX: Int, val sizeY: Int, val sizeZ: Int, val message: String)` — id `project_structure_result` (size 0,0,0 = placement-only / empty capture)

- [ ] **Step 1: Write the failing codec round-trip test**

Create `src/test/kotlin/com/breadmoirai/redstonespecs/network/StructurePacketsTest.kt`:

```kotlin
package com.breadmoirai.redstonespecs.network

import com.breadmoirai.redstonespecs.network.project.NewStructureC2S
import com.breadmoirai.redstonespecs.network.project.PlaceStructureC2S
import com.breadmoirai.redstonespecs.network.project.SaveStructureC2S
import com.breadmoirai.redstonespecs.network.project.StructureResultS2C
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.netty.buffer.Unpooled

class StructurePacketsTest : FunSpec({
    test("PlaceStructureC2S codec round-trips") {
        val buf = Unpooled.buffer()
        val orig = PlaceStructureC2S("a/b/c.nbt")
        PlaceStructureC2S.STREAM_CODEC.encode(buf, orig)
        PlaceStructureC2S.STREAM_CODEC.decode(buf) shouldBe orig
    }
    test("SaveStructureC2S codec round-trips") {
        val buf = Unpooled.buffer()
        val orig = SaveStructureC2S("x.nbt")
        SaveStructureC2S.STREAM_CODEC.encode(buf, orig)
        SaveStructureC2S.STREAM_CODEC.decode(buf) shouldBe orig
    }
    test("NewStructureC2S codec round-trips") {
        val buf = Unpooled.buffer()
        val orig = NewStructureC2S("gadget")
        NewStructureC2S.STREAM_CODEC.encode(buf, orig)
        NewStructureC2S.STREAM_CODEC.decode(buf) shouldBe orig
    }
    test("StructureResultS2C codec round-trips") {
        val buf = Unpooled.buffer()
        val orig = StructureResultS2C("a/b.nbt", 2, 1, 3, "placed a/b.nbt")
        StructureResultS2C.STREAM_CODEC.encode(buf, orig)
        StructureResultS2C.STREAM_CODEC.decode(buf) shouldBe orig
    }
})
```

- [ ] **Step 2: Run to verify it fails**

Run: `cmd.exe /c "gradlew.bat :26.1:test"`
Expected: FAIL — unresolved payload classes.

- [ ] **Step 3: Add the payloads**

Append to `ProjectPackets.kt` (the `id(...)` helper and imports already exist there):

```kotlin
// === Structure C2S ===

data class PlaceStructureC2S(val subpath: String) : CustomPacketPayload {
    companion object {
        val TYPE = CustomPacketPayload.Type<PlaceStructureC2S>(id("place_structure"))
        val STREAM_CODEC: StreamCodec<ByteBuf, PlaceStructureC2S> = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, PlaceStructureC2S::subpath,
            ::PlaceStructureC2S,
        )
    }
    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE
}

data class SaveStructureC2S(val subpath: String) : CustomPacketPayload {
    companion object {
        val TYPE = CustomPacketPayload.Type<SaveStructureC2S>(id("save_structure"))
        val STREAM_CODEC: StreamCodec<ByteBuf, SaveStructureC2S> = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, SaveStructureC2S::subpath,
            ::SaveStructureC2S,
        )
    }
    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE
}

data class NewStructureC2S(val name: String) : CustomPacketPayload {
    companion object {
        val TYPE = CustomPacketPayload.Type<NewStructureC2S>(id("new_structure"))
        val STREAM_CODEC: StreamCodec<ByteBuf, NewStructureC2S> = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, NewStructureC2S::name,
            ::NewStructureC2S,
        )
    }
    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE
}

// === Structure S2C ===

data class StructureResultS2C(
    val subpath: String,
    val sizeX: Int, val sizeY: Int, val sizeZ: Int,
    val message: String,
) : CustomPacketPayload {
    companion object {
        val TYPE = CustomPacketPayload.Type<StructureResultS2C>(id("structure_result"))
        val STREAM_CODEC: StreamCodec<ByteBuf, StructureResultS2C> = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, StructureResultS2C::subpath,
            ByteBufCodecs.VAR_INT, StructureResultS2C::sizeX,
            ByteBufCodecs.VAR_INT, StructureResultS2C::sizeY,
            ByteBufCodecs.VAR_INT, StructureResultS2C::sizeZ,
            ByteBufCodecs.STRING_UTF8, StructureResultS2C::message,
            ::StructureResultS2C,
        )
    }
    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE
}
```

In `ProjectNetworkRegistry.register()`, register the four types alongside the existing ones:

```kotlin
        PayloadTypeRegistry.serverboundPlay().register(PlaceStructureC2S.TYPE, PlaceStructureC2S.STREAM_CODEC)
        PayloadTypeRegistry.serverboundPlay().register(SaveStructureC2S.TYPE, SaveStructureC2S.STREAM_CODEC)
        PayloadTypeRegistry.serverboundPlay().register(NewStructureC2S.TYPE, NewStructureC2S.STREAM_CODEC)
        PayloadTypeRegistry.clientboundPlay().register(StructureResultS2C.TYPE, StructureResultS2C.STREAM_CODEC)
```

- [ ] **Step 4: Run to verify it passes**

Run: `cmd.exe /c "gradlew.bat :26.1:test"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/breadmoirai/redstonespecs/network/project/ProjectPackets.kt \
        src/main/kotlin/com/breadmoirai/redstonespecs/network/project/ProjectNetworkRegistry.kt \
        src/test/kotlin/com/breadmoirai/redstonespecs/network/StructurePacketsTest.kt
git commit -m "feat(net): structure place/save/new C2S + result S2C packets"
```

---

### Task 6: Server handlers (`ProjectNetworkRegistry`)

**Files:**
- Modify: `src/main/kotlin/com/breadmoirai/redstonespecs/network/project/ProjectNetworkRegistry.kt`
- Test: `src/gametest/kotlin/com/breadmoirai/redstonespecs/test/project/ProjectStructureNetworkSpec.kt` (new)
- Modify (register): `src/gametest/kotlin/com/breadmoirai/redstonespecs/test/GametestSentinel.kt`

**Interfaces:**
- Consumes: Task 3 (`getOrAssignStructureRegion`, `placedBoxOf`, `setPlacedBox`, `PlacedBox`), Task 4 (`saveAutoFitToFile`, `placeStructureCentered`, `StructurePersistence.clearBounds`), Task 2 (`ProjectNewStructure.create`), Task 5 packets.
- Produces: `handlePlaceStructure`, `handleSaveStructure`, `handleNewStructure` (public, for gametest) + wired receivers.

- [ ] **Step 1: Write the failing gametest**

Create `src/gametest/kotlin/com/breadmoirai/redstonespecs/test/project/ProjectStructureNetworkSpec.kt`:

```kotlin
package com.breadmoirai.redstonespecs.test.project

import com.breadmoirai.redstonespecs.config.SharedSettings
import com.breadmoirai.redstonespecs.network.project.NewStructureC2S
import com.breadmoirai.redstonespecs.network.project.PlaceStructureC2S
import com.breadmoirai.redstonespecs.network.project.SaveStructureC2S
import com.breadmoirai.redstonespecs.network.project.StructureResultS2C
import com.breadmoirai.redstonespecs.network.project.ProjectErrorS2C
import com.breadmoirai.redstonespecs.network.project.ProjectNetworkRegistry
import com.breadmoirai.redstonespecs.network.project.ProjectTreeSnapshotS2C
import com.breadmoirai.redstonespecs.project.ProjectNewStructure
import com.breadmoirai.redstonespecs.project.ProjectRoot
import com.breadmoirai.redstonespecs.project.ProjectServerContext
import com.breadmoirai.redstonespecs.project.ProjectSession
import com.breadmoirai.redstonespecs.test.drainPayloads
import com.breadmoirai.redstonespecs.test.makeMockServerPlayer
import com.breadmoirai.redstonespecs.test.withTempRoot
import com.breadmoirai.redstonespecs.testing.RedstoneTestSpec
import com.breadmoirai.redstonespecs.testing.server.onServer
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import net.minecraft.world.level.block.Blocks
import kotlin.io.path.exists

class ProjectStructureNetworkSpec : RedstoneTestSpec({

    test("place then save round-trips a standalone structure via handlers") {
        withTempRoot("struct-net") { tmp ->
            // Keep the scanned region tiny so the full-height scan is fast in-test.
            val prevChunks = SharedSettings.structureRegionChunks
            SharedSettings.structureRegionChunks = 1
            ProjectNewStructure.create(tmp, "gadget")  // seed an empty gadget.nbt at root
            onServer {
                ProjectServerContext.set(this, ProjectServerContext(ProjectRoot(tmp)))
                val player = makeMockServerPlayer(this)
                drainPayloads(player)

                // Place: empty structure -> size 0,0,0, region assigned, player teleported.
                ProjectNetworkRegistry.handlePlaceStructure(this, player, PlaceStructureC2S("gadget.nbt"))
                val placed = drainPayloads(player).filterIsInstance<StructureResultS2C>().single()
                placed.subpath shouldBe "gadget.nbt"

                // Build a block in the assigned region, then save: captures a 1x1x1 box.
                val region = com.breadmoirai.redstonespecs.project.ProjectDimRegistry.of(this).structureRegionOriginOf("gadget.nbt")!!
                overworld().setBlock(region.offset(5, 0, 5), Blocks.GOLD_BLOCK.defaultBlockState(), 2)
                ProjectNetworkRegistry.handleSaveStructure(this, player, SaveStructureC2S("gadget.nbt"))
                val saved = drainPayloads(player).filterIsInstance<StructureResultS2C>().single()
                saved.sizeX shouldBe 1; saved.sizeY shouldBe 1; saved.sizeZ shouldBe 1

                ProjectSession.clear(player.uuid)
            }
            SharedSettings.structureRegionChunks = prevChunks
        }
    }

    test("place rejects a non-.nbt subpath") {
        withTempRoot("struct-net-bad") { tmp ->
            (tmp.resolve("notes.txt")).toFile().writeText("hi")
            onServer {
                ProjectServerContext.set(this, ProjectServerContext(ProjectRoot(tmp)))
                val player = makeMockServerPlayer(this)
                drainPayloads(player)
                ProjectNetworkRegistry.handlePlaceStructure(this, player, PlaceStructureC2S("notes.txt"))
                drainPayloads(player).filterIsInstance<ProjectErrorS2C>() shouldHaveSize 1
                ProjectSession.clear(player.uuid)
            }
        }
    }

    test("new structure creates the file and re-sends the tree") {
        withTempRoot("struct-net-new") { tmp ->
            onServer {
                ProjectServerContext.set(this, ProjectServerContext(ProjectRoot(tmp)))
                val player = makeMockServerPlayer(this)
                ProjectSession.setActive(player.uuid, "")  // active folder = root
                drainPayloads(player)
                ProjectNetworkRegistry.handleNewStructure(this, player, NewStructureC2S("fresh"))
                tmp.resolve("fresh.nbt").exists() shouldBe true
                drainPayloads(player).filterIsInstance<ProjectTreeSnapshotS2C>() shouldHaveSize 1
                ProjectSession.clear(player.uuid)
            }
        }
    }
})
```

> `ProjectSession.setActive(uuid, "")` sets the active subpath to root; if the empty string is
> rejected by `resolveSubpath`, load a subfolder instead and target that (mirror how
> `ProjectNetworkRegistrySpec`'s `handleNewSpec` test sets up its active folder).

Register in `GametestSentinel.kt` (import + `ProjectStructureNetworkSpec::class,` in the list).

- [ ] **Step 2: Run to verify it fails**

Run: `cmd.exe /c "gradlew.bat :26.1:runGametest"`
Expected: FAIL — unresolved `handlePlaceStructure` / `handleSaveStructure` / `handleNewStructure`.

- [ ] **Step 3: Implement the handlers + receivers**

Add imports to `ProjectNetworkRegistry.kt`:

```kotlin
import com.breadmoirai.redstonespecs.persistence.StructurePersistence
import net.minecraft.core.BlockPos
import net.minecraft.core.Vec3i
import net.minecraft.world.entity.Relative
import kotlin.io.path.exists
```

In `register()`, add the three receivers (after the existing ones):

```kotlin
        ServerPlayNetworking.registerGlobalReceiver(PlaceStructureC2S.TYPE) { payload, ctx ->
            ctx.server().execute { handlePlaceStructure(ctx.server(), ctx.player(), payload) }
        }
        ServerPlayNetworking.registerGlobalReceiver(SaveStructureC2S.TYPE) { payload, ctx ->
            ctx.server().execute { handleSaveStructure(ctx.server(), ctx.player(), payload) }
        }
        ServerPlayNetworking.registerGlobalReceiver(NewStructureC2S.TYPE) { payload, ctx ->
            ctx.server().execute { handleNewStructure(ctx.server(), ctx.player(), payload) }
        }
```

Add the handlers (public methods on `object ProjectNetworkRegistry`):

```kotlin
    fun handlePlaceStructure(server: MinecraftServer, player: ServerPlayer, payload: PlaceStructureC2S) {
        val root = rootFor(server) ?: run {
            ServerPlayNetworking.send(player, ProjectErrorS2C("project-root not configured")); return
        }
        val file = root.resolveSubpath(payload.subpath) ?: run {
            ServerPlayNetworking.send(player, ProjectErrorS2C("subpath not found or escapes root: ${payload.subpath}")); return
        }
        if (!payload.subpath.endsWith(".nbt")) {
            ServerPlayNetworking.send(player, ProjectErrorS2C("not a structure file: ${payload.subpath}")); return
        }
        if (!file.exists()) {
            ServerPlayNetworking.send(player, ProjectErrorS2C("structure file not found: ${payload.subpath}")); return
        }
        val registry = ProjectDimRegistry.of(server)
        val level = registry.projectLevel()
        val origin = registry.getOrAssignStructureRegion(payload.subpath)
        val width = SharedSettings.structureRegionChunks * 16
        // Cheap re-clear: only the previously-placed footprint, not the whole region.
        registry.placedBoxOf(payload.subpath)?.let { StructurePersistence.clearBounds(level, it.origin, it.size) }
        val placed = StructurePersistence.placeStructureCentered(
            file, level, origin, width, level.minY, level.maxY, SharedSettings.projectGridYBase,
        ) ?: run {
            ServerPlayNetworking.send(player, ProjectErrorS2C("failed to load structure: ${payload.subpath}")); return
        }
        registry.setPlacedBox(payload.subpath, placed)
        player.teleportTo(
            level,
            (origin.x + width / 2) + 0.5, (SharedSettings.projectGridYBase + 2).toDouble(), (origin.z + width / 2) + 0.5,
            emptySet<Relative>(), player.yRot, player.xRot, true,
        )
        ServerPlayNetworking.send(player, StructureResultS2C(
            payload.subpath, placed.size.x, placed.size.y, placed.size.z, "placed ${payload.subpath}",
        ))
    }

    fun handleSaveStructure(server: MinecraftServer, player: ServerPlayer, payload: SaveStructureC2S) {
        val root = rootFor(server) ?: run {
            ServerPlayNetworking.send(player, ProjectErrorS2C("project-root not configured")); return
        }
        val file = root.resolveSubpath(payload.subpath) ?: run {
            ServerPlayNetworking.send(player, ProjectErrorS2C("subpath not found or escapes root: ${payload.subpath}")); return
        }
        if (!payload.subpath.endsWith(".nbt")) {
            ServerPlayNetworking.send(player, ProjectErrorS2C("not a structure file: ${payload.subpath}")); return
        }
        val registry = ProjectDimRegistry.of(server)
        val level = registry.projectLevel()
        val origin = registry.getOrAssignStructureRegion(payload.subpath)
        val width = SharedSettings.structureRegionChunks * 16
        val box = StructurePersistence.saveAutoFitToFile(file, level, origin, width, level.minY, level.maxY)
        val size = box?.size ?: Vec3i(0, 0, 0)
        if (box != null) registry.setPlacedBox(payload.subpath, box)
        val msg = if (box == null) "saved ${payload.subpath} (empty)"
                  else "saved ${payload.subpath} (${size.x}×${size.y}×${size.z})"
        ServerPlayNetworking.send(player, StructureResultS2C(payload.subpath, size.x, size.y, size.z, msg))
    }

    fun handleNewStructure(server: MinecraftServer, player: ServerPlayer, payload: NewStructureC2S) {
        val activeSubpath = ProjectSession.get(player.uuid)?.activeSubpath ?: run {
            ServerPlayNetworking.send(player, ProjectErrorS2C("no folder selected")); return
        }
        val root = rootFor(server) ?: run {
            ServerPlayNetworking.send(player, ProjectErrorS2C("project-root not configured")); return
        }
        val world = ProjectWorld.get(server)
        val folderAbsolute = world?.folderAbsoluteByPath?.get(activeSubpath)
            ?: root.resolveSubpath(activeSubpath)
            ?: run {
                ServerPlayNetworking.send(player, ProjectErrorS2C("active folder not resolvable: $activeSubpath")); return
            }
        try {
            ProjectNewStructure.create(folderAbsolute, payload.name)
        } catch (e: Exception) {
            LOGGER.error("[project/new-structure] create {}/{}: {}", activeSubpath, payload.name, e.message, e)
            ServerPlayNetworking.send(player, ProjectErrorS2C("new-structure failed: ${e.message}")); return
        }
        sendTree(server, player)
    }
```

- [ ] **Step 4: Run to verify it passes**

Run: `cmd.exe /c "gradlew.bat :26.1:runGametest"`
Expected: PASS — all `ProjectStructureNetworkSpec` cases.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/breadmoirai/redstonespecs/network/project/ProjectNetworkRegistry.kt \
        src/gametest/kotlin/com/breadmoirai/redstonespecs/test/project/ProjectStructureNetworkSpec.kt \
        src/gametest/kotlin/com/breadmoirai/redstonespecs/test/GametestSentinel.kt
git commit -m "feat(net): place/save/new structure server handlers"
```

---

### Task 7: Client wiring (Explorer actions + result status)

**Files:**
- Modify: `src/client/kotlin/com/breadmoirai/redstonespecs/client/project/ProjectClientNetworking.kt`
- Modify: `src/client/kotlin/com/breadmoirai/redstonespecs/client/ide/ProjectTreeState.kt`
- Modify: `src/client/kotlin/com/breadmoirai/redstonespecs/client/ide/ProjectExplorerPanel.kt`
- Test: `src/clientTest/kotlin/com/breadmoirai/redstonespecs/test/StructureExplorerSpec.kt` (new)
- Modify (register): `src/gametest/kotlin/com/breadmoirai/redstonespecs/test/GametestSentinel.kt` (clientTest specs are registered the same way — add to the appropriate list; if clientTest uses a separate sentinel, register there)

**Interfaces:**
- Consumes: Task 5 packets, `ProjectTreeState`.
- Produces: `ProjectTreeState.onStructureResult(r: StructureResultS2C)`; Explorer `.nbt`-click sends `PlaceStructureC2S`; header "New Structure" / "Save Structure" actions.

- [ ] **Step 1: Write the failing clientTest**

Create `src/clientTest/kotlin/com/breadmoirai/redstonespecs/test/StructureExplorerSpec.kt`:

```kotlin
package com.breadmoirai.redstonespecs.test

import com.breadmoirai.redstonespecs.client.ide.ProjectTreeState
import com.breadmoirai.redstonespecs.network.project.StructureResultS2C
import com.breadmoirai.redstonespecs.testing.ClientSpec
import io.kotest.matchers.shouldBe

class StructureExplorerSpec : ClientSpec({
    test("onStructureResult surfaces the message as Explorer status") {
        runOnClient {
            ProjectTreeState.reset()
            ProjectTreeState.onStructureResult(StructureResultS2C("a/box.nbt", 2, 1, 3, "placed a/box.nbt"))
        }
        ProjectTreeState.status shouldBe "placed a/box.nbt"
    }
})
```

Register `StructureExplorerSpec::class` in the sentinel list that drives clientTest specs (same pattern as `ProjectExplorerSpec`).

- [ ] **Step 2: Run to verify it fails**

Run: `cmd.exe /c "gradlew.bat :26.1:runClientGametest"` (the clientTest launch task — match how `ProjectExplorerSpec` is run)
Expected: FAIL — unresolved `onStructureResult`.

- [ ] **Step 3: Implement**

In `ProjectTreeState.kt`, add the import and handler:

```kotlin
import com.breadmoirai.redstonespecs.network.project.StructureResultS2C
```
```kotlin
    fun onStructureResult(r: StructureResultS2C) { status = r.message }
```

In `ProjectClientNetworking.kt`, add the import and receiver (inside `register()`):

```kotlin
import com.breadmoirai.redstonespecs.network.project.StructureResultS2C
```
```kotlin
        ClientPlayNetworking.registerGlobalReceiver(StructureResultS2C.TYPE) { payload, ctx ->
            ctx.client().execute { ProjectTreeState.onStructureResult(payload) }
        }
```

In `ProjectExplorerPanel.kt`:

Add imports:

```kotlin
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.breadmoirai.redstonespecs.network.project.NewStructureC2S
import com.breadmoirai.redstonespecs.network.project.PlaceStructureC2S
import com.breadmoirai.redstonespecs.network.project.SaveStructureC2S
```

Make `.nbt` file rows place-on-click (and keep selection). Replace the `is FileNode ->` branch body in `TreeNode` with:

```kotlin
        is FileNode -> {
            val isSelected = path == ProjectTreeState.selectedPath
            val isStructure = node.extension == "nbt"
            val onClick: () -> Unit = {
                ProjectTreeState.select(path)
                if (isStructure) ClientPlayNetworking.send(PlaceStructureC2S(path))
            }
            val base = Modifier.fillMaxWidth().clickable(onClick = onClick)
            val rowMod = if (isSelected) base.background(SELECTED_BG) else base
            Row(rowMod.padding(vertical = 2.dp)) {
                Spacer(Modifier.width(indent))
                val label = if (isStructure) "▶ ${node.name}" else node.name
                BasicText(label, style = TextStyle(color = if (isSelected) TEXT else TEXT_DIM))
            }
        }
```

Add a "New/Save Structure" action row. Add this composable and call it from `ProjectExplorer()`
right after `Header()`:

```kotlin
@Composable
private fun StructureActions() {
    var newName by remember { mutableStateOf("") }
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Box(Modifier.width(90.dp).background(Color(0x22000000)).padding(horizontal = 4.dp, vertical = 2.dp)) {
            BasicTextField(
                value = newName, onValueChange = { newName = it },
                textStyle = TextStyle(color = TEXT), singleLine = true,
            )
        }
        Box(Modifier.clickable {
            if (newName.isNotBlank()) { ClientPlayNetworking.send(NewStructureC2S(newName)); newName = "" }
        }.padding(horizontal = 6.dp)) { BasicText("+ Structure", style = TextStyle(color = TEXT_DIM)) }
        Spacer(Modifier.weight(1f))
        Box(Modifier.clickable {
            val sel = ProjectTreeState.selectedPath
            if (sel != null && sel.endsWith(".nbt")) ClientPlayNetworking.send(SaveStructureC2S(sel))
        }.padding(horizontal = 6.dp)) { BasicText("Save Structure", style = TextStyle(color = TEXT_DIM)) }
    }
}
```

In `ProjectExplorer()`, add `StructureActions()` on the line after `Header()`.

- [ ] **Step 4: Run to verify it passes**

Run the clientTest launch task (`StructureExplorerSpec`).
Expected: PASS. Also run the compile sanity across all sourcesets:
`cmd.exe /c "gradlew.bat :26.1:clientClasses :26.1:classes :26.1:gametestClasses :26.1:clientTestClasses :26.1:testClasses"`

- [ ] **Step 5: Commit**

```bash
git add src/client/kotlin/com/breadmoirai/redstonespecs/client/project/ProjectClientNetworking.kt \
        src/client/kotlin/com/breadmoirai/redstonespecs/client/ide/ProjectTreeState.kt \
        src/client/kotlin/com/breadmoirai/redstonespecs/client/ide/ProjectExplorerPanel.kt \
        src/clientTest/kotlin/com/breadmoirai/redstonespecs/test/StructureExplorerSpec.kt \
        src/gametest/kotlin/com/breadmoirai/redstonespecs/test/GametestSentinel.kt
git commit -m "feat(ui): Explorer place/save/new actions for structure files"
```

---

### Task 8: Documentation sync

**Files:**
- Modify: `docs/architecture/redstone-project.md`
- Modify: `docs/persistence/INDEX.md` and `docs/persistence/spec-on-disk-format.md`
- Modify: `docs/ui/INDEX.md`
- Modify: `docs/use-cases/INDEX.md` (add a one-line UC entry if the folder's convention has UC IDs)

This task has no automated test; its "test" is the doc-sync checklist in `CLAUDE.md`.

- [ ] **Step 1: Document the feature in `architecture/redstone-project.md`**

Add a new section after "Components" describing standalone structures:

```markdown
## Standalone structure files

`.nbt` files are also first-class in the Explorer, independent of specs. Clicking a `.nbt`
places it (`StructurePersistence.placeStructureCentered`) centered in an auto-assigned region
(`ProjectDimRegistry.getOrAssignStructureRegion`, a disjoint +X lane at
`z = STRUCTURE_LANE_Z = 4096`), floored at `projectGridYBase` (64) — or vertically centered when
the structure's height ≥ `TALL_THRESHOLD` (256). "Save Structure" auto-fits the tight non-air box
in the region (`StructurePersistence.saveAutoFitToFile` → `project.autoFit`) and rewrites the file.
"New Structure" (`ProjectNewStructure.create`) writes an empty `<name>.nbt` into the active folder.

- **Region size:** `SharedSettings.structureRegionChunks` (default 9 → 144×144 blocks), full
  world height.
- **Cheap re-clear:** the registry tracks the last-placed `PlacedBox` per structure subpath;
  re-placing clears only that footprint, not the whole region.
- **Packets:** `PlaceStructureC2S` / `SaveStructureC2S` / `NewStructureC2S` → `StructureResultS2C`,
  handled by `ProjectNetworkRegistry.handlePlaceStructure/handleSaveStructure/handleNewStructure`.
```

- [ ] **Step 2: Cross-reference from persistence docs**

In `docs/persistence/spec-on-disk-format.md`, add a short note that `.nbt` structures can now be
placed/captured/created directly from the Explorer (not only as spec sidecars), linking to
`architecture/redstone-project.md#standalone-structure-files`. Update the matching
`docs/persistence/INDEX.md` summary line for that article.

- [ ] **Step 3: Note the Explorer actions in `docs/ui/INDEX.md`**

Extend the `dock-framework.md` / Explorer INDEX entry summary to mention `.nbt` click-to-place and
the New/Save Structure header actions. (If a dedicated Explorer article exists, add the section
there instead and keep the INDEX line one-liner.)

- [ ] **Step 4: Verify cross-references resolve**

Run: `grep -rn "standalone-structure-files\|structureRegionChunks\|getOrAssignStructureRegion" docs/`
Expected: every hit resolves to a real section; no dangling links.

- [ ] **Step 5: Commit**

```bash
git add docs/
git commit -m "docs: standalone structure files in the Explorer"
```

---

## Self-Review

**Spec coverage:**
- Place → Task 4 (`placeStructureCentered`) + Task 6 (`handlePlaceStructure`) + Task 7 (`.nbt` click). ✓
- Save/auto-fit → Task 1 (`autoFit`) + Task 4 (`saveAutoFitToFile`) + Task 6 (`handleSaveStructure`) + Task 7 (Save action). ✓
- New empty structure → Task 2 (`ProjectNewStructure`) + Task 6 (`handleNewStructure`) + Task 7 (New action). ✓
- Region model (9×9 default, full height, centered, disjoint lane, no overlap) → Task 3 + Task 1 (`centeredStart`/`anchorY`). ✓
- Y anchor (floor 64, center when ≥256) → Task 1 (`anchorY`), used in Task 4. ✓
- Cheap re-clear via last-placed box → Task 3 (`PlacedBox`) + Task 6 (clearBounds of prior box). ✓
- Packets + `StructureResultS2C` status → Task 5 + Task 7. ✓
- Docs sync → Task 8. ✓

**Type consistency:** `PlacedBox(origin: BlockPos, size: Vec3i)` defined in Task 3 and returned by
Task 4's `saveAutoFitToFile`/`placeStructureCentered`, consumed by Task 6 — consistent.
`StructureResultS2C(subpath, sizeX, sizeY, sizeZ, message)` identical across Tasks 5/6/7.
`autoFit`/`centeredStart`/`anchorY`/`FitBox` from Task 1 used only in Task 4. `structureRegionChunks`
from Task 3 used in Tasks 4/6.

**Placeholder scan:** No TBD/TODO. Two defensive notes (MC bootstrap in Task 2's test; gametest task
name) are conditional guidance, not gaps — primary code/commands are concrete.

**Open verification points for the executor** (fix inline if the symbol differs on 26.1.2):
`ServerLevel.minY`/`maxY` (LevelHeightAccessor `getMinY()`/`getMaxY()`); the exact gametest/clientTest
Gradle launch task names (match the tasks the existing `*Spec`s already run under).
