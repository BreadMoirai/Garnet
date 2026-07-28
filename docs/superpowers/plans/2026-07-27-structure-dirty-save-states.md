# Structure Dirty Save States Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give standalone `.nbt` structures an IDE-style unsaved-changes buffer: edits are auto-captured to an adjacent `<name>.nbt.unsaved` sidecar whenever Minecraft saves the world, so re-opening resumes from the edits and the user can discard back to the committed file.

**Architecture:** On `ServerLifecycleEvents.BEFORE_SAVE`, capture each placed structure's region (auto-fit), diff it against the committed `.nbt`, and write/delete the `<name>.nbt.unsaved` sidecar accordingly. Place prefers the sidecar when present; Save writes the committed file and deletes the sidecar; a new Discard operation deletes the sidecar and re-places from the committed file. The Explorer shows a dirty indicator driven purely by sidecar existence on disk.

**Tech Stack:** Kotlin, Fabric (MC 26.1), Minecraft `StructureTemplate`/NBT, Compose-for-MC client UI, Kotest (unit `src/test`, gametest `src/gametest`, client `src/clientTest`).

## Global Constraints

- Build verification runs 5 sourcesets: `cmd.exe /c "gradlew.bat :26.1:clientClasses :26.1:classes :26.1:gametestClasses :26.1:clientTestClasses :26.1:testClasses"` (invoke gradle only as `cmd.exe /c "gradlew.bat ..."`, never `./gradlew`).
- Unit tests: `cmd.exe /c "gradlew.bat :26.1:test"` — run **unfiltered** (Kotest `--tests` filter reports false "No tests found"); read the XML report under `build/test-results/`.
- New unit-test files under `src/test` are autoscanned. New **gametest** and **clientTest** specs must be registered in `GametestSentinel`/`ClientTestSentinel` — this plan avoids that by extending the already-registered `ProjectStructureNetworkSpec` and `StructureExplorerSpec`.
- Sidecar suffix is exactly `.nbt.unsaved` (must NOT end in `.nbt`, so it is never mistaken for a placeable structure).
- ARGB text colors: white is `-1`, not `0xFFFFFF` (alpha 0 renders invisible). Not directly used here but relevant if adding colored client text.
- Direct commits to `main`; no feature branches unless asked.

---

## File Structure

**New files:**
- `src/main/kotlin/com/breadmoirai/garnet/persistence/StructureDiff.kt` — pure blocks-only structure-tag diff.
- `src/test/kotlin/com/breadmoirai/garnet/persistence/StructureDiffTest.kt` — unit tests for the diff.

**Modified files:**
- `src/main/kotlin/com/breadmoirai/garnet/project/FileTree.kt` — `FileNode.hasUnsaved`; `scanFolder` hides `.nbt.unsaved` and flags dirty `.nbt` nodes.
- `src/main/kotlin/com/breadmoirai/garnet/network/project/ProjectPackets.kt` — `FileNode` codec carries `hasUnsaved`; `StructureResultS2C` gains `hasUnsaved`; new `DiscardStructureC2S`.
- `src/main/kotlin/com/breadmoirai/garnet/persistence/StructurePersistence.kt` — `unsavedSidecarOf`, `captureAutoFit`, `flushUnsavedSidecar`; `saveAutoFitToFile` refactored onto `captureAutoFit`.
- `src/main/kotlin/com/breadmoirai/garnet/project/ProjectDimRegistry.kt` — `placedStructureSubpaths()`.
- `src/main/kotlin/com/breadmoirai/garnet/network/project/ProjectNetworkRegistry.kt` — sidecar-aware place, save deletes sidecar, `handleDiscardStructure`, `flushDirtyStructures`, `placeStructureFrom` helper, packet registration.
- `src/main/kotlin/com/breadmoirai/garnet/garnet.kt` — register `BEFORE_SAVE` flush.
- `src/client/kotlin/com/breadmoirai/garnet/client/ide/ProjectTreeState.kt` — `selectedHasUnsaved()`.
- `src/client/kotlin/com/breadmoirai/garnet/client/ide/ProjectExplorerPanel.kt` — Discard action + dirty dot.
- Tests: `src/test/.../FileTreeTest.kt`, `src/test/.../network/StructurePacketsTest.kt`, `src/test/.../network/project/FileTreeCodecTest.kt`, `src/gametest/.../project/ProjectStructureNetworkSpec.kt`, `src/clientTest/.../StructureExplorerSpec.kt`.

---

## Task 1: Pure structure diff helper

**Files:**
- Create: `src/main/kotlin/com/breadmoirai/garnet/persistence/StructureDiff.kt`
- Test: `src/test/kotlin/com/breadmoirai/garnet/persistence/StructureDiffTest.kt`

**Interfaces:**
- Produces: `fun structuresDiffer(a: CompoundTag, b: CompoundTag): Boolean` in package `com.breadmoirai.garnet.persistence` — true when the two `StructureTemplate.save()` tags differ in size or in their set of `(pos, blockStateTag, blockEntityNbt?)` block cells. Ignores `DataVersion`, palette ordering, and entities.

Background: `StructureTemplate.save(tag)` writes `size` (int ListTag of 3), `palette` (ListTag of block-state CompoundTags), and `blocks` (ListTag of `{pos:[x,y,z] ints, state:int palette index, nbt?:CompoundTag}`). NBT tags implement structural `equals`, so comparing resolved palette CompoundTags and pos triples in a `Set` is order-independent.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.breadmoirai.garnet.persistence

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag

/** Builds a StructureTemplate-shaped tag from (x,y,z,paletteIndex) blocks + a palette of state names. */
private fun structureTag(
    size: Triple<Int, Int, Int>,
    palette: List<String>,
    blocks: List<IntArray>, // each = [x, y, z, stateIndex]
): CompoundTag {
    val tag = CompoundTag()
    val sizeTag = ListTag()
    sizeTag.add(net.minecraft.nbt.IntTag.valueOf(size.first))
    sizeTag.add(net.minecraft.nbt.IntTag.valueOf(size.second))
    sizeTag.add(net.minecraft.nbt.IntTag.valueOf(size.third))
    tag.put("size", sizeTag)
    val paletteTag = ListTag()
    palette.forEach { name -> paletteTag.add(CompoundTag().apply { putString("Name", name) }) }
    tag.put("palette", paletteTag)
    val blocksTag = ListTag()
    blocks.forEach { b ->
        val bt = CompoundTag()
        val pos = ListTag()
        pos.add(net.minecraft.nbt.IntTag.valueOf(b[0]))
        pos.add(net.minecraft.nbt.IntTag.valueOf(b[1]))
        pos.add(net.minecraft.nbt.IntTag.valueOf(b[2]))
        bt.put("pos", pos)
        bt.putInt("state", b[3])
        blocksTag.add(bt)
    }
    tag.put("blocks", blocksTag)
    return tag
}

class StructureDiffTest : FunSpec({
    test("identical structures are not different") {
        val a = structureTag(Triple(1, 1, 1), listOf("minecraft:stone"), listOf(intArrayOf(0, 0, 0, 0)))
        val b = structureTag(Triple(1, 1, 1), listOf("minecraft:stone"), listOf(intArrayOf(0, 0, 0, 0)))
        structuresDiffer(a, b) shouldBe false
    }
    test("palette reordering with remapped indices is not different") {
        val a = structureTag(Triple(1, 1, 2), listOf("minecraft:stone", "minecraft:gold_block"),
            listOf(intArrayOf(0, 0, 0, 0), intArrayOf(0, 0, 1, 1)))
        // Same blocks, palette order swapped, state indices remapped accordingly.
        val b = structureTag(Triple(1, 1, 2), listOf("minecraft:gold_block", "minecraft:stone"),
            listOf(intArrayOf(0, 0, 0, 1), intArrayOf(0, 0, 1, 0)))
        structuresDiffer(a, b) shouldBe false
    }
    test("a changed block state is different") {
        val a = structureTag(Triple(1, 1, 1), listOf("minecraft:stone"), listOf(intArrayOf(0, 0, 0, 0)))
        val b = structureTag(Triple(1, 1, 1), listOf("minecraft:gold_block"), listOf(intArrayOf(0, 0, 0, 0)))
        structuresDiffer(a, b) shouldBe true
    }
    test("a changed size is different") {
        val a = structureTag(Triple(1, 1, 1), listOf("minecraft:stone"), listOf(intArrayOf(0, 0, 0, 0)))
        val b = structureTag(Triple(2, 1, 1), listOf("minecraft:stone"), listOf(intArrayOf(0, 0, 0, 0)))
        structuresDiffer(a, b) shouldBe true
    }
})
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cmd.exe /c "gradlew.bat :26.1:test"`
Expected: FAIL — `structuresDiffer` unresolved (compile error).

- [ ] **Step 3: Write the implementation**

```kotlin
package com.breadmoirai.garnet.persistence

import net.minecraft.nbt.CompoundTag

/**
 * True when two `StructureTemplate.save()` tags describe different block content: different size,
 * or a different set of (relative pos, block-state tag, block-entity nbt) cells. Palette ordering
 * and `DataVersion` are ignored (normalized away); entities are not compared. Pure — no level.
 */
fun structuresDiffer(a: CompoundTag, b: CompoundTag): Boolean = normalize(a) != normalize(b)

private data class Cell(val pos: Triple<Int, Int, Int>, val state: CompoundTag, val nbt: CompoundTag?)

private fun normalize(tag: CompoundTag): Pair<Triple<Int, Int, Int>, Set<Cell>> {
    val sizeTag = tag.getListOrEmpty("size")
    val size = Triple(sizeTag.getIntOr(0, 0), sizeTag.getIntOr(1, 0), sizeTag.getIntOr(2, 0))
    val palette = tag.getListOrEmpty("palette")
    val blocks = tag.getListOrEmpty("blocks")
    val cells = HashSet<Cell>()
    for (i in 0 until blocks.size) { // ListTag extends AbstractList → use the .size property, not size()
        val bt = blocks.getCompoundOrEmpty(i)
        val posTag = bt.getListOrEmpty("pos")
        val pos = Triple(posTag.getIntOr(0, 0), posTag.getIntOr(1, 0), posTag.getIntOr(2, 0))
        val state = palette.getCompoundOrEmpty(bt.getIntOr("state", 0))
        val nbt = bt.getCompound("nbt").orElse(null)
        cells.add(Cell(pos, state, nbt))
    }
    return size to cells
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cmd.exe /c "gradlew.bat :26.1:test"`
Expected: PASS (4 `StructureDiffTest` tests green in the XML report).

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/breadmoirai/garnet/persistence/StructureDiff.kt \
        src/test/kotlin/com/breadmoirai/garnet/persistence/StructureDiffTest.kt
git commit -m "feat(persistence): pure blocks-only structure diff helper"
```

---

## Task 2: FileNode dirty flag + scanFolder sidecar handling

**Files:**
- Modify: `src/main/kotlin/com/breadmoirai/garnet/project/FileTree.kt`
- Modify: `src/main/kotlin/com/breadmoirai/garnet/network/project/ProjectPackets.kt` (FILE_TREE codec only)
- Test: `src/test/kotlin/com/breadmoirai/garnet/project/FileTreeTest.kt`
- Test: `src/test/kotlin/com/breadmoirai/garnet/network/project/FileTreeCodecTest.kt`

**Interfaces:**
- Consumes: nothing from prior tasks.
- Produces: `data class FileNode(name, extension, hasUnsaved: Boolean = false)`; `scanFolder` omits any `*.nbt.unsaved` file from the tree and sets `hasUnsaved = true` on the `<name>.nbt` node when a sibling `<name>.nbt.unsaved` exists. `FILE_TREE_STREAM_CODEC` serializes `hasUnsaved` (boolean after extension).

- [ ] **Step 1: Write the failing test (FileTreeTest)**

Add these tests inside the existing `FileTreeTest` spec body (they create files on a temp dir — follow the file's existing temp-dir pattern; if it uses `@TempDir`/`createTempDirectory`, mirror it). Use `kotlin.io.path.*` helpers.

```kotlin
    test("scanFolder hides .nbt.unsaved and flags the sibling .nbt as dirty") {
        val dir = kotlin.io.path.createTempDirectory("scan-dirty")
        dir.resolve("gadget.nbt").createFile()
        dir.resolve("gadget.nbt.unsaved").createFile()
        dir.resolve("clean.nbt").createFile()
        val root = scanFolder(dir)
        val files = root.children.filterIsInstance<FileNode>()
        files.map { it.name } shouldBe listOf("clean.nbt", "gadget.nbt") // sidecar hidden, sorted
        files.first { it.name == "gadget.nbt" }.hasUnsaved shouldBe true
        files.first { it.name == "clean.nbt" }.hasUnsaved shouldBe false
    }
```

Ensure imports at the top of the test file include `com.breadmoirai.garnet.project.FileNode` (if not already), `io.kotest.matchers.shouldBe`, and `kotlin.io.path.createFile` / `createTempDirectory`.

- [ ] **Step 2: Run test to verify it fails**

Run: `cmd.exe /c "gradlew.bat :26.1:test"`
Expected: FAIL — `hasUnsaved` unresolved on `FileNode`, and/or sidecar not hidden.

- [ ] **Step 3: Update `FileNode` and `scanFolder`**

In `FileTree.kt`, extend the data class:

```kotlin
/** A file. [extension] is the lowercased last-dot extension, "" when the name has no dot.
 *  [hasUnsaved] is true for an `<name>.nbt` that has an adjacent `<name>.nbt.unsaved` dirty buffer. */
data class FileNode(
    override val name: String,
    val extension: String,
    val hasUnsaved: Boolean = false,
) : FileTreeNode
```

Replace the `scanFolder` body's file mapping so it (a) skips sidecars and (b) flags dirty `.nbt` nodes:

```kotlin
fun scanFolder(path: Path): FolderNode {
    if (!path.isDirectory()) return FolderNode(path.name, emptyList())
    val entries = path.listDirectoryEntries()
    val names = entries.map { it.name }.toHashSet()
    val children = entries
        .filterNot { !it.isDirectory() && it.name.endsWith(".nbt.unsaved") }
        .map { entry ->
            if (entry.isDirectory()) scanFolder(entry)
            else FileNode(
                entry.name,
                entry.extension.lowercase(),
                hasUnsaved = entry.name.endsWith(".nbt") && "${entry.name}.unsaved" in names,
            )
        }
        .sortedWith(CHILD_ORDER)
    return FolderNode(path.name, children)
}
```

- [ ] **Step 4: Update the FILE_TREE codec to carry `hasUnsaved`**

In `ProjectPackets.kt`, update the `TAG_FILE` decode branch and the `is FileNode` encode branch:

```kotlin
            TAG_FILE -> FileNode(name, ByteBufCodecs.STRING_UTF8.decode(buf), buf.readBoolean())
```

```kotlin
            is FileNode -> {
                buf.writeByte(TAG_FILE.toInt())
                ByteBufCodecs.STRING_UTF8.encode(buf, value.name)
                ByteBufCodecs.STRING_UTF8.encode(buf, value.extension)
                buf.writeBoolean(value.hasUnsaved)
            }
```

- [ ] **Step 5: Add a codec round-trip assertion for the dirty flag (FileTreeCodecTest)**

Add a test in `FileTreeCodecTest` that a dirty `FileNode` survives the round-trip:

```kotlin
    test("FileNode hasUnsaved survives round-trip") {
        val node: FileTreeNode = FileNode("gadget.nbt", "nbt", hasUnsaved = true)
        val buf = io.netty.buffer.Unpooled.buffer()
        FILE_TREE_STREAM_CODEC.encode(buf, node)
        (FILE_TREE_STREAM_CODEC.decode(buf) as FileNode).hasUnsaved shouldBe true
    }
```

Ensure `com.breadmoirai.garnet.network.project.FILE_TREE_STREAM_CODEC` and `com.breadmoirai.garnet.project.FileNode` are imported in that test file (the existing tests already reference the tree types; add what's missing).

- [ ] **Step 6: Run tests to verify they pass**

Run: `cmd.exe /c "gradlew.bat :26.1:test"`
Expected: PASS — new `FileTreeTest` and `FileTreeCodecTest` cases green; existing `FileTreeCodecTest` round-trips still pass (default `hasUnsaved=false`).

- [ ] **Step 7: Build the client + main sourcesets (FileNode is shared)**

Run: `cmd.exe /c "gradlew.bat :26.1:classes :26.1:clientClasses"`
Expected: BUILD SUCCESSFUL (no other `FileNode(` call sites break — client/tests use the defaulted param).

- [ ] **Step 8: Commit**

```bash
git add src/main/kotlin/com/breadmoirai/garnet/project/FileTree.kt \
        src/main/kotlin/com/breadmoirai/garnet/network/project/ProjectPackets.kt \
        src/test/kotlin/com/breadmoirai/garnet/project/FileTreeTest.kt \
        src/test/kotlin/com/breadmoirai/garnet/network/project/FileTreeCodecTest.kt
git commit -m "feat(project): FileNode dirty flag; scanFolder hides .nbt.unsaved and flags dirty structures"
```

---

## Task 3: Packet changes — StructureResultS2C.hasUnsaved + DiscardStructureC2S

**Files:**
- Modify: `src/main/kotlin/com/breadmoirai/garnet/network/project/ProjectPackets.kt`
- Modify: `src/main/kotlin/com/breadmoirai/garnet/network/project/ProjectNetworkRegistry.kt` (two `StructureResultS2C(` call sites, to compile)
- Test: `src/test/kotlin/com/breadmoirai/garnet/network/StructurePacketsTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces:
  - `StructureResultS2C(subpath: String, sizeX: Int, sizeY: Int, sizeZ: Int, hasUnsaved: Boolean, message: String)` — new `hasUnsaved` field between the sizes and `message`.
  - `DiscardStructureC2S(subpath: String)` with `TYPE`/`STREAM_CODEC` (id `project_discard_structure`).

- [ ] **Step 1: Write the failing test**

Replace the existing `StructureResultS2C codec round-trips` test body and add a Discard test:

```kotlin
    test("StructureResultS2C codec round-trips") {
        val buf = Unpooled.buffer()
        val orig = StructureResultS2C("a/b.nbt", 2, 1, 3, hasUnsaved = true, message = "placed a/b.nbt — unsaved changes")
        StructureResultS2C.STREAM_CODEC.encode(buf, orig)
        StructureResultS2C.STREAM_CODEC.decode(buf) shouldBe orig
    }
    test("DiscardStructureC2S codec round-trips") {
        val buf = Unpooled.buffer()
        val orig = DiscardStructureC2S("a/b.nbt")
        DiscardStructureC2S.STREAM_CODEC.encode(buf, orig)
        DiscardStructureC2S.STREAM_CODEC.decode(buf) shouldBe orig
    }
```

Add `import com.breadmoirai.garnet.network.project.DiscardStructureC2S` to the test file.

- [ ] **Step 2: Run test to verify it fails**

Run: `cmd.exe /c "gradlew.bat :26.1:test"`
Expected: FAIL — `DiscardStructureC2S` unresolved and `StructureResultS2C` constructor arity mismatch.

- [ ] **Step 3: Extend `StructureResultS2C` and add `DiscardStructureC2S`**

In `ProjectPackets.kt`, update `StructureResultS2C`:

```kotlin
data class StructureResultS2C(
    val subpath: String,
    val sizeX: Int, val sizeY: Int, val sizeZ: Int,
    val hasUnsaved: Boolean,
    val message: String,
) : CustomPacketPayload {
    companion object {
        val TYPE = CustomPacketPayload.Type<StructureResultS2C>(id("structure_result"))
        val STREAM_CODEC: StreamCodec<ByteBuf, StructureResultS2C> = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, StructureResultS2C::subpath,
            ByteBufCodecs.VAR_INT, StructureResultS2C::sizeX,
            ByteBufCodecs.VAR_INT, StructureResultS2C::sizeY,
            ByteBufCodecs.VAR_INT, StructureResultS2C::sizeZ,
            ByteBufCodecs.BOOL, StructureResultS2C::hasUnsaved,
            ByteBufCodecs.STRING_UTF8, StructureResultS2C::message,
            ::StructureResultS2C,
        )
    }
    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE
}
```

Add a `DiscardStructureC2S` alongside the other Structure C2S packets (after `NewStructureC2S`):

```kotlin
data class DiscardStructureC2S(val subpath: String) : CustomPacketPayload {
    companion object {
        val TYPE = CustomPacketPayload.Type<DiscardStructureC2S>(id("discard_structure"))
        val STREAM_CODEC: StreamCodec<ByteBuf, DiscardStructureC2S> = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, DiscardStructureC2S::subpath,
            ::DiscardStructureC2S,
        )
    }
    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE
}
```

- [ ] **Step 4: Fix the two server call sites so main compiles**

In `ProjectNetworkRegistry.kt`, add `hasUnsaved` to both existing `StructureResultS2C(...)` constructions (temporary literals; Task 5 gives them their real values):

`handlePlaceStructure` (~line 189):
```kotlin
        ServerPlayNetworking.send(player, StructureResultS2C(
            payload.subpath, placed.size.x, placed.size.y, placed.size.z, false, "placed ${payload.subpath}",
        ))
```

`handleSaveStructure` (~line 217):
```kotlin
        ServerPlayNetworking.send(player, StructureResultS2C(payload.subpath, size.x, size.y, size.z, false, msg))
```

- [ ] **Step 5: Run unit tests + main compile**

Run: `cmd.exe /c "gradlew.bat :26.1:test :26.1:classes"`
Expected: PASS — packet round-trip tests green; main compiles.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/breadmoirai/garnet/network/project/ProjectPackets.kt \
        src/main/kotlin/com/breadmoirai/garnet/network/project/ProjectNetworkRegistry.kt \
        src/test/kotlin/com/breadmoirai/garnet/network/StructurePacketsTest.kt
git commit -m "feat(net): StructureResultS2C.hasUnsaved + DiscardStructureC2S packet"
```

---

## Task 4: StructurePersistence — sidecar path, capture-to-tag, flush

**Files:**
- Modify: `src/main/kotlin/com/breadmoirai/garnet/persistence/StructurePersistence.kt`
- Test: `src/test/kotlin/com/breadmoirai/garnet/persistence/StructureDiffTest.kt` (add a path-helper unit test here to avoid a new file; or create `StructureSidecarTest.kt`)

**Interfaces:**
- Consumes: `structuresDiffer` (Task 1).
- Produces (all in `object StructurePersistence`):
  - `fun unsavedSidecarOf(file: Path): Path` — `<name>.nbt` → `<name>.nbt.unsaved` in the same directory.
  - `fun captureAutoFit(level, regionOrigin, regionSizeXZ, regionMinY, regionMaxY): Pair<CompoundTag, PlacedBox?>` — auto-fit scan → saved `StructureTemplate` tag + tight box (box null when region empty; tag is a valid empty structure then).
  - `fun flushUnsavedSidecar(file, level, regionOrigin, regionSizeXZ, regionMinY, regionMaxY): Boolean` — capture, diff vs committed `file`, write sidecar when dirty / delete it when clean; returns whether the structure is now dirty.
  - `saveAutoFitToFile` unchanged in signature/behavior (now delegates to `captureAutoFit`).

- [ ] **Step 1: Write the failing test**

Add to `StructureDiffTest.kt` (pure, no level needed):

```kotlin
    test("unsavedSidecarOf appends .unsaved adjacent to the file") {
        val f = java.nio.file.Path.of("/proj", "sub", "gadget.nbt")
        val sc = com.breadmoirai.garnet.persistence.StructurePersistence.unsavedSidecarOf(f)
        sc.fileName.toString() shouldBe "gadget.nbt.unsaved"
        sc.parent shouldBe f.parent
        sc.fileName.toString().endsWith(".nbt") shouldBe false
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cmd.exe /c "gradlew.bat :26.1:test"`
Expected: FAIL — `unsavedSidecarOf` unresolved.

- [ ] **Step 3: Refactor `saveAutoFitToFile` onto a new `captureAutoFit`, add `unsavedSidecarOf` + `flushUnsavedSidecar`**

In `StructurePersistence.kt` add (import `kotlin.io.path.deleteIfExists`, `kotlin.io.path.exists`, `kotlin.io.path.resolveSibling` are covered by the existing `kotlin.io.path.*` import):

```kotlin
    /** `<name>.nbt` → adjacent `<name>.nbt.unsaved` dirty buffer (same directory). */
    fun unsavedSidecarOf(file: Path): Path = file.resolveSibling("${file.fileName}.unsaved")

    /**
     * Auto-fit scans the region for non-air, returning the saved [StructureTemplate] tag plus the
     * tight [PlacedBox] (null when the region is empty; the tag is still a valid empty structure).
     * Does not write anything.
     */
    fun captureAutoFit(
        level: ServerLevel, regionOrigin: BlockPos,
        regionSizeXZ: Int, regionMinY: Int, regionMaxY: Int,
    ): Pair<CompoundTag, PlacedBox?> {
        val dimY = regionMaxY - regionMinY + 1
        val fit = autoFit(regionSizeXZ, dimY, regionSizeXZ) { lx, ly, lz ->
            !level.getBlockState(BlockPos(regionOrigin.x + lx, regionMinY + ly, regionOrigin.z + lz)).`is`(Blocks.AIR)
        }
        val template = StructureTemplate()
        if (fit == null) return template.save(CompoundTag()) to null
        val tightOrigin = BlockPos(regionOrigin.x + fit.minX, regionMinY + fit.minY, regionOrigin.z + fit.minZ)
        val size = Vec3i(fit.sizeX, fit.sizeY, fit.sizeZ)
        template.fillFromWorld(level, tightOrigin, size, false, emptyList())
        return template.save(CompoundTag()) to PlacedBox(tightOrigin, size)
    }

    /**
     * Captures the region and compares to the committed [file]; writes `<file>.unsaved` when they
     * differ (or the committed file is missing), deletes the sidecar when they match. Returns true
     * when the structure is now dirty (sidecar present).
     */
    fun flushUnsavedSidecar(
        file: Path, level: ServerLevel, regionOrigin: BlockPos,
        regionSizeXZ: Int, regionMinY: Int, regionMaxY: Int,
    ): Boolean {
        val (capturedTag, _) = captureAutoFit(level, regionOrigin, regionSizeXZ, regionMinY, regionMaxY)
        val committedTag = if (file.exists()) {
            try { NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap()) }
            catch (e: IOException) { LOGGER.error("[StructurePersistence#flush] read '{}': {}", file, e.message); null }
        } else null
        val sidecar = unsavedSidecarOf(file)
        val dirty = committedTag == null || structuresDiffer(committedTag, capturedTag)
        if (dirty) {
            sidecar.parent?.createDirectories()
            try { NbtIo.writeCompressed(capturedTag, sidecar) }
            catch (e: IOException) { LOGGER.error("[StructurePersistence#flush] write '{}': {}", sidecar, e.message) }
        } else {
            sidecar.deleteIfExists()
        }
        return dirty
    }
```

Replace the body of `saveAutoFitToFile` to delegate:

```kotlin
    fun saveAutoFitToFile(
        file: Path, level: ServerLevel, regionOrigin: BlockPos,
        regionSizeXZ: Int, regionMinY: Int, regionMaxY: Int,
    ): PlacedBox? {
        val (tag, box) = captureAutoFit(level, regionOrigin, regionSizeXZ, regionMinY, regionMaxY)
        file.parent?.createDirectories()
        try { NbtIo.writeCompressed(tag, file) }
        catch (e: IOException) { LOGGER.error("[StructurePersistence#saveAutoFit] write '{}': {}", file, e.message) }
        LOGGER.debug("[StructurePersistence#saveAutoFit] captured {} -> {}", box?.size, file)
        return box
    }
```

Add `import com.breadmoirai.garnet.persistence.structuresDiffer` is unnecessary (same package). Confirm the file already imports `Blocks`, `autoFit`, `PlacedBox`, `StructureTemplate`, `CompoundTag`, `NbtIo`, `NbtAccounter`, `Vec3i` (it does).

- [ ] **Step 4: Run unit tests + main compile**

Run: `cmd.exe /c "gradlew.bat :26.1:test :26.1:classes"`
Expected: PASS — `unsavedSidecarOf` test green; main compiles; existing `saveAutoFitToFile` behavior unchanged.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/breadmoirai/garnet/persistence/StructurePersistence.kt \
        src/test/kotlin/com/breadmoirai/garnet/persistence/StructureDiffTest.kt
git commit -m "feat(persistence): sidecar path, capture-to-tag, and dirty-flush helpers"
```

---

## Task 5: Wire the server flow — place/save/discard + BEFORE_SAVE flush

**Files:**
- Modify: `src/main/kotlin/com/breadmoirai/garnet/project/ProjectDimRegistry.kt`
- Modify: `src/main/kotlin/com/breadmoirai/garnet/network/project/ProjectNetworkRegistry.kt`
- Modify: `src/main/kotlin/com/breadmoirai/garnet/garnet.kt`
- Test: `src/gametest/kotlin/com/breadmoirai/garnet/test/project/ProjectStructureNetworkSpec.kt`

**Interfaces:**
- Consumes: `unsavedSidecarOf`, `flushUnsavedSidecar` (Task 4); `DiscardStructureC2S`, `StructureResultS2C.hasUnsaved` (Task 3).
- Produces:
  - `ProjectDimRegistry.placedStructureSubpaths(): Set<String>` — subpaths with a recorded placed box.
  - `ProjectNetworkRegistry.handleDiscardStructure(server, player, DiscardStructureC2S)`.
  - `ProjectNetworkRegistry.flushDirtyStructures(server: MinecraftServer)` — flush the sidecar for every placed structure.
  - `DiscardStructureC2S` registered (payload type + receiver).
  - Place now loads the sidecar when present and reports `hasUnsaved`; Save deletes the sidecar.

- [ ] **Step 1: Write the failing gametest**

Add to `ProjectStructureNetworkSpec` (after the existing test). It mirrors the existing test's region-clear setup.

```kotlin
    test("dirty sidecar lifecycle: flush writes/deletes, place loads unsaved, save+discard clear") {
        withTempRoot("struct-dirty") { tmp ->
            val prevChunks = SharedSettings.structureRegionChunks
            SharedSettings.structureRegionChunks = 1
            ProjectNewStructure.create(tmp, "widget") // empty widget.nbt at root
            val committed = tmp.resolve("widget.nbt")
            val sidecar = com.breadmoirai.garnet.persistence.StructurePersistence.unsavedSidecarOf(committed)
            onServer {
                ProjectServerContext.set(this, ProjectServerContext(ProjectRoot(tmp)))
                val player = makeMockServerPlayer(this)
                drainPayloads(player)

                ProjectNetworkRegistry.handlePlaceStructure(this, player, PlaceStructureC2S("widget.nbt"))
                val placed = drainPayloads(player).filterIsInstance<StructureResultS2C>().single()
                placed.hasUnsaved shouldBe false

                val region = ProjectDimRegistry.of(this).structureRegionOriginOf("widget.nbt")!!
                val width = SharedSettings.structureRegionChunks * 16
                val lvl = overworld()
                StructurePersistence.clearBounds(
                    lvl, BlockPos(region.x, lvl.minY, region.z),
                    Vec3i(width, lvl.maxY - lvl.minY + 1, width),
                )
                // Edit the region, then flush (simulates a world-save): sidecar appears, committed untouched.
                lvl.setBlock(region.offset(5, 0, 5), Blocks.GOLD_BLOCK.defaultBlockState(), 2)
                ProjectNetworkRegistry.flushDirtyStructures(this)
                sidecar.exists() shouldBe true
                // Committed is still the empty structure (place produced size 0 earlier).
                placed.sizeX shouldBe 0

                // Re-place: loads the unsaved sidecar (1x1x1 gold), reports hasUnsaved.
                ProjectNetworkRegistry.handlePlaceStructure(this, player, PlaceStructureC2S("widget.nbt"))
                val replaced = drainPayloads(player).filterIsInstance<StructureResultS2C>().single()
                replaced.hasUnsaved shouldBe true
                replaced.sizeX shouldBe 1

                // Explicit Save: writes committed, deletes sidecar, reports clean.
                ProjectNetworkRegistry.handleSaveStructure(this, player, SaveStructureC2S("widget.nbt"))
                val saved = drainPayloads(player).filterIsInstance<StructureResultS2C>().single()
                saved.hasUnsaved shouldBe false
                sidecar.exists() shouldBe false

                // Edit again + flush -> sidecar reappears; then Discard removes it and re-places committed.
                lvl.setBlock(region.offset(6, 0, 6), Blocks.IRON_BLOCK.defaultBlockState(), 2)
                ProjectNetworkRegistry.flushDirtyStructures(this)
                sidecar.exists() shouldBe true
                ProjectNetworkRegistry.handleDiscardStructure(this, player, DiscardStructureC2S("widget.nbt"))
                val discarded = drainPayloads(player).filterIsInstance<StructureResultS2C>().single()
                discarded.hasUnsaved shouldBe false
                sidecar.exists() shouldBe false
            }
            SharedSettings.structureRegionChunks = prevChunks
        }
    }
```

Add imports to the spec: `com.breadmoirai.garnet.network.project.DiscardStructureC2S`, `kotlin.io.path.exists` (already present per the existing test's imports — verify).

- [ ] **Step 2: Run the gametest to verify it fails**

Run: `cmd.exe /c "gradlew.bat :26.1:gametestClasses"`
Expected: FAIL — `handleDiscardStructure`, `flushDirtyStructures`, and `DiscardStructureC2S` unresolved (compile failure is a valid red).

- [ ] **Step 3: Expose placed subpaths on the registry**

In `ProjectDimRegistry.kt`, add next to `placedBoxOf`/`setPlacedBox`:

```kotlin
    /** Subpaths with a recorded placed box this session (the set to flush on world-save). */
    fun placedStructureSubpaths(): Set<String> = placedBoxes.keys.toSet()
```

- [ ] **Step 4: Refactor place onto a shared helper; add discard + flush; delete sidecar on save**

In `ProjectNetworkRegistry.kt`:

Add the private helper (near the other structure handlers):

```kotlin
    private fun placeStructureFrom(
        server: MinecraftServer, player: ServerPlayer, subpath: String,
        source: Path, hasUnsaved: Boolean, message: String,
    ) {
        val registry = ProjectDimRegistry.of(server)
        val level = registry.projectLevel()
        val origin = registry.getOrAssignStructureRegion(subpath)
        val width = SharedSettings.structureRegionChunks * 16
        registry.placedBoxOf(subpath)?.let { StructurePersistence.clearBounds(level, it.origin, it.size) }
        val placed = StructurePersistence.placeStructureCentered(
            source, level, origin, width, level.minY, level.maxY, SharedSettings.projectGridYBase,
        ) ?: run {
            ServerPlayNetworking.send(player, ProjectErrorS2C("failed to load structure: $subpath")); return
        }
        registry.setPlacedBox(subpath, placed)
        val tpY = placed.origin.y + placed.size.y + 2
        player.teleportTo(
            level,
            (origin.x + width / 2) + 0.5, tpY.toDouble(), (origin.z + width / 2) + 0.5,
            emptySet<Relative>(), player.yRot, player.xRot, true,
        )
        ServerPlayNetworking.send(player, StructureResultS2C(
            subpath, placed.size.x, placed.size.y, placed.size.z, hasUnsaved, message,
        ))
    }
```

Replace the body of `handlePlaceStructure` from the `val registry = ...` line through the final `ServerPlayNetworking.send(... StructureResultS2C ...)` with:

```kotlin
        val sidecar = StructurePersistence.unsavedSidecarOf(file)
        val hasUnsaved = sidecar.exists()
        val source = if (hasUnsaved) sidecar else file
        val message = if (hasUnsaved) "placed ${payload.subpath} — unsaved changes" else "placed ${payload.subpath}"
        placeStructureFrom(server, player, payload.subpath, source, hasUnsaved, message)
```

(Keep the leading `root`/`file`/`.nbt` validation block of `handlePlaceStructure` intact. Add `import kotlin.io.path.exists` if not already present.)

In `handleSaveStructure`, after `saveAutoFitToFile` writes and before/at the reply, delete the sidecar and report clean. Replace the reply tail:

```kotlin
        val box = StructurePersistence.saveAutoFitToFile(file, level, origin, width, level.minY, level.maxY)
        val size = box?.size ?: Vec3i(0, 0, 0)
        if (box != null) registry.setPlacedBox(payload.subpath, box)
        StructurePersistence.unsavedSidecarOf(file).deleteIfExists()
        val msg = if (box == null) "saved ${payload.subpath} (empty)"
                  else "saved ${payload.subpath} (${size.x}×${size.y}×${size.z})"
        ServerPlayNetworking.send(player, StructureResultS2C(payload.subpath, size.x, size.y, size.z, false, msg))
```

(Add `import kotlin.io.path.deleteIfExists`.)

Add the discard handler (after `handleSaveStructure`):

```kotlin
    fun handleDiscardStructure(server: MinecraftServer, player: ServerPlayer, payload: DiscardStructureC2S) {
        val root = rootFor(server) ?: run {
            ServerPlayNetworking.send(player, ProjectErrorS2C("project-root not configured")); return
        }
        val file = root.resolveSubpath(payload.subpath) ?: run {
            ServerPlayNetworking.send(player, ProjectErrorS2C("subpath not found or escapes root: ${payload.subpath}")); return
        }
        if (!payload.subpath.endsWith(".nbt")) {
            ServerPlayNetworking.send(player, ProjectErrorS2C("not a structure file: ${payload.subpath}")); return
        }
        StructurePersistence.unsavedSidecarOf(file).deleteIfExists()
        placeStructureFrom(server, player, payload.subpath, file, false, "discarded ${payload.subpath}")
    }
```

Add the flush entry point (near `handleSaveNow`):

```kotlin
    /** Capture each placed structure's region on world-save, writing/deleting its `.nbt.unsaved`. */
    fun flushDirtyStructures(server: MinecraftServer) {
        val root = rootFor(server) ?: return
        val registry = ProjectDimRegistry.of(server)
        val level = registry.projectLevel()
        val width = SharedSettings.structureRegionChunks * 16
        for (subpath in registry.placedStructureSubpaths()) {
            val file = root.resolveSubpath(subpath) ?: continue
            val origin = registry.structureRegionOriginOf(subpath) ?: continue
            StructurePersistence.flushUnsavedSidecar(file, level, origin, width, level.minY, level.maxY)
        }
    }
```

- [ ] **Step 5: Register the Discard packet + receiver**

In `register()`, add the payload-type registration alongside the other structure C2S:

```kotlin
        PayloadTypeRegistry.serverboundPlay().register(DiscardStructureC2S.TYPE, DiscardStructureC2S.STREAM_CODEC)
```

and the receiver alongside the other structure receivers:

```kotlin
        ServerPlayNetworking.registerGlobalReceiver(DiscardStructureC2S.TYPE) { payload, ctx ->
            ctx.server().execute { handleDiscardStructure(ctx.server(), ctx.player(), payload) }
        }
```

- [ ] **Step 6: Register the BEFORE_SAVE flush**

In `garnet.kt`, add inside `onInitialize` (next to the other `ServerLifecycleEvents.*` registrations):

```kotlin
        ServerLifecycleEvents.BEFORE_SAVE.register { server, _, _ ->
            com.breadmoirai.garnet.network.project.ProjectNetworkRegistry.flushDirtyStructures(server)
        }
```

- [ ] **Step 7: Compile main + gametest, then run the gametest**

Run: `cmd.exe /c "gradlew.bat :26.1:classes :26.1:gametestClasses"`
Expected: BUILD SUCCESSFUL.

Run: `cmd.exe /c "gradlew.bat :26.1:runGameTest"`
Expected: PASS — `src/gametest/` specs run via `runGameTest` (NOT `:26.1:test`). Check the Kotest report under `build/reports/garnet/gametest/` (XML under `build/test-results/gametest/`) for `ProjectStructureNetworkSpec`'s new "dirty sidecar lifecycle" test green. Never use `--tests`.

- [ ] **Step 8: Commit**

```bash
git add src/main/kotlin/com/breadmoirai/garnet/project/ProjectDimRegistry.kt \
        src/main/kotlin/com/breadmoirai/garnet/network/project/ProjectNetworkRegistry.kt \
        src/main/kotlin/com/breadmoirai/garnet/garnet.kt \
        src/gametest/kotlin/com/breadmoirai/garnet/test/project/ProjectStructureNetworkSpec.kt
git commit -m "feat(net): sidecar-aware place/save, Discard handler, BEFORE_SAVE dirty flush"
```

---

## Task 6: Client — dirty status, Discard action, dirty dot

**Files:**
- Modify: `src/client/kotlin/com/breadmoirai/garnet/client/ide/ProjectTreeState.kt`
- Modify: `src/client/kotlin/com/breadmoirai/garnet/client/ide/ProjectExplorerPanel.kt`
- Test: `src/clientTest/kotlin/com/breadmoirai/garnet/test/StructureExplorerSpec.kt`

**Interfaces:**
- Consumes: `StructureResultS2C.hasUnsaved`, `DiscardStructureC2S` (Task 3); `FileNode.hasUnsaved` (Task 2).
- Produces: `ProjectTreeState.selectedHasUnsaved(): Boolean` — whether the currently selected path is a dirty `.nbt` in the current snapshot.

- [ ] **Step 1: Write the failing client test**

Extend `StructureExplorerSpec`. Update the existing status test's `StructureResultS2C` call for the new arity, and add dirty-state assertions:

```kotlin
package com.breadmoirai.garnet.test

import com.breadmoirai.garnet.client.ide.ProjectTreeState
import com.breadmoirai.garnet.network.project.ProjectTreeSnapshotS2C
import com.breadmoirai.garnet.network.project.StructureResultS2C
import com.breadmoirai.garnet.project.FileNode
import com.breadmoirai.garnet.project.FolderNode
import com.breadmoirai.garnet.testing.ClientSpec
import io.kotest.matchers.shouldBe

class StructureExplorerSpec : ClientSpec({
    test("onStructureResult surfaces the message as Explorer status") {
        runOnClient {
            ProjectTreeState.reset()
            ProjectTreeState.onStructureResult(
                StructureResultS2C("a/box.nbt", 2, 1, 3, hasUnsaved = false, message = "placed a/box.nbt"),
            )
        }
        ProjectTreeState.status shouldBe "placed a/box.nbt"
    }

    test("selectedHasUnsaved reflects the dirty flag on the selected .nbt node") {
        runOnClient {
            ProjectTreeState.reset()
            val root = FolderNode("root", listOf(
                FileNode("dirty.nbt", "nbt", hasUnsaved = true),
                FileNode("clean.nbt", "nbt", hasUnsaved = false),
            ))
            ProjectTreeState.onSnapshot(ProjectTreeSnapshotS2C(root, currentSubpath = null))
            ProjectTreeState.select("dirty.nbt")
        }
        ProjectTreeState.selectedHasUnsaved() shouldBe true
        runOnClient { ProjectTreeState.select("clean.nbt") }
        ProjectTreeState.selectedHasUnsaved() shouldBe false
    }
})
```

- [ ] **Step 2: Run the client test to verify it fails**

Run: `cmd.exe /c "gradlew.bat :26.1:clientTestClasses"`
Expected: FAIL — `selectedHasUnsaved` unresolved (compile failure).

- [ ] **Step 3: Add `selectedHasUnsaved()` to `ProjectTreeState`**

In `ProjectTreeState.kt` add (uses `FolderNode.resolve` from the project package):

```kotlin
    /** True when [selectedPath] resolves to a `.nbt` file flagged dirty in the current snapshot. */
    fun selectedHasUnsaved(): Boolean {
        val path = selectedPath ?: return false
        val node = snapshot?.root?.let { com.breadmoirai.garnet.project.resolve(it, path) }
        return node is com.breadmoirai.garnet.project.FileNode && node.hasUnsaved
    }
```

Note: `resolve` is an extension `FolderNode.resolve(path)`. Call it as `it.resolve(path)`:

```kotlin
    fun selectedHasUnsaved(): Boolean {
        val path = selectedPath ?: return false
        val node = snapshot?.root?.resolve(path)
        return node is com.breadmoirai.garnet.project.FileNode && node.hasUnsaved
    }
```

Add imports: `com.breadmoirai.garnet.project.FileNode` and `com.breadmoirai.garnet.project.resolve`.

- [ ] **Step 4: Run the client test to verify it passes**

Run: `cmd.exe /c "gradlew.bat :26.1:runClientTest"` — `src/clientTest/` specs run via `runClientTest` (NOT `:26.1:test`). Confirm `StructureExplorerSpec` green in the Kotest report under `build/reports/garnet/clientTest/` (XML under `build/test-results/clientTest/`).
Expected: PASS.

- [ ] **Step 5: Add the Discard action and dirty dot to the Explorer UI**

In `ProjectExplorerPanel.kt`:

Import the discard packet at the top:

```kotlin
import com.breadmoirai.garnet.network.project.DiscardStructureC2S
```

In `StructureActions`, add a Discard control after the "Save Structure" box (inside the same `Row`). It sends `DiscardStructureC2S` for the selected `.nbt` and dims when the selection isn't dirty:

```kotlin
        val selDirty = ProjectTreeState.selectedHasUnsaved()
        Box(Modifier.clickable {
            val sel = ProjectTreeState.selectedPath
            if (sel != null && sel.endsWith(".nbt")) ClientPlayNetworking.send(DiscardStructureC2S(sel))
        }.padding(horizontal = 6.dp)) {
            BasicText("Discard", style = TextStyle(color = if (selDirty) TEXT_DIM else TEXT_DISABLED))
        }
```

In the `TreeNode` `is FileNode` branch, prefix a dirty dot on structure nodes that have unsaved changes. Replace the `label` line:

```kotlin
                val dirtyMark = if (node.hasUnsaved) "● " else ""
                val label = if (isStructure) "$dirtyMark▶ ${node.name}" else node.name
```

- [ ] **Step 6: Build the client sourceset**

Run: `cmd.exe /c "gradlew.bat :26.1:clientClasses"`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Full 5-sourceset verification + all three test tiers**

Run: `cmd.exe /c "gradlew.bat :26.1:clientClasses :26.1:classes :26.1:gametestClasses :26.1:clientTestClasses :26.1:testClasses"`
Expected: BUILD SUCCESSFUL.

Run each test tier via its own task and confirm green in `build/reports/garnet/<sourceSet>/`:
- `cmd.exe /c "gradlew.bat :26.1:test"` — unit (`src/test`)
- `cmd.exe /c "gradlew.bat :26.1:runGameTest"` — gametest (`src/gametest`)
- `cmd.exe /c "gradlew.bat :26.1:runClientTest"` — client (`src/clientTest`)
Expected: PASS on all three.

- [ ] **Step 8: Commit**

```bash
git add src/client/kotlin/com/breadmoirai/garnet/client/ide/ProjectTreeState.kt \
        src/client/kotlin/com/breadmoirai/garnet/client/ide/ProjectExplorerPanel.kt \
        src/clientTest/kotlin/com/breadmoirai/garnet/test/StructureExplorerSpec.kt
git commit -m "feat(ui): Explorer dirty dot + Discard action for structures"
```

---

## Task 7: Documentation sync

**Files:**
- Modify: `docs/persistence/spec-on-disk-format.md`
- Modify: `docs/architecture/redstone-project.md` (the standalone-structure section)
- Modify: `docs/persistence/INDEX.md` and/or `docs/architecture/INDEX.md` if a summary line changes

Per CLAUDE.md, source changes require a docs audit. This feature adds a new on-disk artifact (`.nbt.unsaved`) and new behavior (world-save capture, Discard).

- [ ] **Step 1: Document the sidecar in the on-disk format article**

In `docs/persistence/spec-on-disk-format.md`, under "Companion files", add an entry describing `<id>.nbt.unsaved`:

```markdown
- **`<name>.nbt.unsaved`** — dirty-buffer sidecar written adjacent to a standalone `<name>.nbt`
  whenever Minecraft saves the world (`ServerLifecycleEvents.BEFORE_SAVE`) and the placed
  region's auto-fit capture differs from the committed `.nbt`. Placing a structure loads this
  sidecar when present (resuming unsaved edits); **Save Structure** writes the committed `.nbt`
  and deletes the sidecar; **Discard** deletes it and re-places the committed version. The
  Explorer hides `*.nbt.unsaved` files and shows a dirty dot on the owning `.nbt`.
```

- [ ] **Step 2: Update the standalone-structure architecture section**

In `docs/architecture/redstone-project.md`, in the standalone-structure-files section, add a short "Unsaved changes (dirty buffer)" subsection summarizing: capture-on-`BEFORE_SAVE`, adjacent `.nbt.unsaved`, blocks-only diff (`structuresDiffer`), `flushDirtyStructures` iterating `ProjectDimRegistry.placedStructureSubpaths()`, and the place/save/discard lifecycle. Cross-link to `persistence/spec-on-disk-format.md`.

- [ ] **Step 3: Reconcile INDEX summaries**

Ensure the `docs/persistence/INDEX.md` line for `spec-on-disk-format.md` still reads accurately (it already mentions standalone `.nbt`; extend it to mention the dirty buffer if it makes the summary clearer). Confirm no other doc references the old 5-arg `StructureResultS2C` or the old `FileNode(name, extension)` shape.

Run: `grep -rn "StructureResultS2C\|FileNode(" docs/` and update any stale citation.

- [ ] **Step 4: Commit**

```bash
git add docs/
git commit -m "docs: dirty save states for standalone structures"
```

---

## Self-Review Notes

- **Spec coverage:** dirty sidecar adjacent to file (Task 4/5), `BEFORE_SAVE` trigger (Task 5), place-loads-sidecar + `hasUnsaved` (Task 5), Save deletes sidecar (Task 5), Discard (Tasks 3/5/6), blocks-only diff (Task 1), Explorer dirty dot + tree hiding (Tasks 2/6), all test tiers (unit T1/T2/T3/T4, gametest T5, client T6), docs (Task 7). All spec sections map to a task.
- **Type consistency:** `structuresDiffer(CompoundTag, CompoundTag): Boolean`, `unsavedSidecarOf(Path): Path`, `captureAutoFit(...): Pair<CompoundTag, PlacedBox?>`, `flushUnsavedSidecar(...): Boolean`, `placedStructureSubpaths(): Set<String>`, `flushDirtyStructures(MinecraftServer)`, `handleDiscardStructure(...)`, `selectedHasUnsaved(): Boolean`, `FileNode(name, extension, hasUnsaved=false)`, `StructureResultS2C(subpath, x, y, z, hasUnsaved, message)` — used identically across producing and consuming tasks.
- **Known limitations (from spec):** entities are not diffed (blocks-only); orphaned overworld blocks after reload are pre-existing; no spec-folder-structure buffers.
