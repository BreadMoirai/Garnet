package com.breadmoirai.garnet.editor.structure.ops

import com.breadmoirai.garnet.editor.structure.data.PlacedBox
import com.breadmoirai.garnet.editor.structure.data.anchorY
import com.breadmoirai.garnet.editor.structure.data.autoFit
import com.breadmoirai.garnet.editor.structure.data.centeredStart
import net.minecraft.core.BlockPos
import net.minecraft.core.HolderGetter
import net.minecraft.core.Vec3i
import net.minecraft.core.registries.Registries
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.NbtAccounter
import net.minecraft.nbt.NbtIo
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.*

private val LOGGER = LoggerFactory.getLogger("Garnet")

/**
 * The result of scanning a volume: the saved [StructureTemplate] tag, the tight [box] enclosing all
 * non-air (null when the volume was empty), and the non-air [blockCount].
 *
 * [blockCount] is counted during the scan rather than derived from the tag: `fillFromWorld` records
 * every cell in its bounds, air included, so `tag.blocks.size` is the box volume, not the build size.
 */
data class CapturedStructure(
    val tag: CompoundTag,
    val box: PlacedBox?,
    val blockCount: Int,
)

object StructurePersistence {

    fun save(saveDir: Path, id: String, level: ServerLevel, originPos: BlockPos, bounds: Vec3i) {
        saveDir.createDirectories()
        val template = StructureTemplate()
        template.fillFromWorld(level, originPos, bounds, false, emptyList())
        val nbt = template.save(CompoundTag())
        val file = saveDir.resolve("$id.nbt")
        try {
            NbtIo.writeCompressed(nbt, file)
        } catch (e: IOException) {
            LOGGER.error("[StructurePersistence#save] failed to write '{}': {}", id, e.message)
        }
        LOGGER.debug("[StructurePersistence#save] saved structure '{}' to {}", id, file)
    }

    fun load(saveDir: Path, id: String, level: ServerLevel, originPos: BlockPos, bounds: Vec3i) {
        val file = saveDir.resolve("$id.nbt")
        if (!file.exists()) {
            LOGGER.warn("[StructurePersistence#load] structure file '{}' not found", file)
            return
        }
        try {
            val nbt = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap())
            val blockGetter: HolderGetter<Block> = level.registryAccess().lookupOrThrow(Registries.BLOCK)
            val template = StructureTemplate()
            template.load(blockGetter, nbt)
            template.placeInWorld(level, originPos, originPos, StructurePlaceSettings(), level.random, 2)
            LOGGER.debug("[StructurePersistence#load] placed structure '{}' at {}", id, originPos)
        } catch (e: IOException) {
            LOGGER.error("[StructurePersistence#load] failed to read '{}': {}", id, e.message)
        }
    }

    fun hasChanges(saveDir: Path, id: String, level: ServerLevel, originPos: BlockPos, bounds: Vec3i): Boolean {
        val file = saveDir.resolve("$id.nbt")
        if (!file.exists()) return true
        return try {
            val savedNbt = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap())
            val live = StructureTemplate()
            live.fillFromWorld(level, originPos, bounds, false, emptyList())
            val liveNbt = live.save(CompoundTag())
            savedNbt != liveNbt
        } catch (e: IOException) {
            LOGGER.error("[StructurePersistence#hasChanges] failed to read '{}': {}", id, e.message)
            true
        }
    }

    fun hasNonAirBlocks(level: ServerLevel, originPos: BlockPos, bounds: Vec3i): Boolean {
        for (x in 0 until bounds.x)
            for (y in 0 until bounds.y)
                for (z in 0 until bounds.z) {
                    val p = BlockPos(originPos.x + x, originPos.y + y, originPos.z + z)
                    if (!level.getBlockState(p).`is`(Blocks.AIR)) return true
                }
        return false
    }

    fun clearBounds(level: ServerLevel, originPos: BlockPos, bounds: Vec3i) {
        for (x in 0 until bounds.x)
            for (y in 0 until bounds.y)
                for (z in 0 until bounds.z) {
                    val p = BlockPos(originPos.x + x, originPos.y + y, originPos.z + z)
                    level.setBlock(p, Blocks.AIR.defaultBlockState(), 2)
                }
    }

    fun listIds(saveDir: Path): List<String> {
        if (!saveDir.exists()) return emptyList()
        return saveDir.listDirectoryEntries("*.nbt").map { it.nameWithoutExtension }
    }

    /**
     * Writes [tag] to [file] crash-safely: to a same-directory temp file first, then an atomic (or
     * best-effort) move over the target. A plain `NbtIo.writeCompressed(tag, file)` truncates
     * in place, so a crash or power loss mid-write leaves a corrupt structure; [file] here either
     * keeps its old content or gets the fully-written new content, never a partial write.
     *
     * Throws [IOException] on failure (temp-write or move) — callers decide how to react. The temp
     * file is always cleaned up, success or failure, so a failed attempt doesn't leave litter next
     * to the structure.
     */
    fun writeStructureAtomic(tag: CompoundTag, file: Path) {
        file.parent?.let { Files.createDirectories(it) }
        val tmp = file.resolveSibling(".${file.fileName}.tmp-${System.nanoTime()}")
        try {
            NbtIo.writeCompressed(tag, tmp)
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (e: AtomicMoveNotSupportedException) {
                // Some filesystems (certain network mounts, cross-device moves) reject ATOMIC_MOVE;
                // a plain replace is still far safer than truncating the live file in place.
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    /**
     * Auto-fit within an arbitrary absolute [scan] volume, rather than a whole structure region.
     *
     * This is the auto-save path's capture — and, since the region-wide `captureAutoFit` /
     * `saveAutoFitToFile` pair was deleted, the only capture there is. Scanning
     * `union(placedBox, dirtyBox)` reads a few thousand positions where scanning a whole structure
     * region (144 wide by full world height) reads ~8M, which is the difference between a viable
     * per-edit debounce and an unusable one. Nothing should reintroduce a region-wide scan on a
     * commit path. A zero-size [scan] is empty by definition.
     *
     * **Entities are captured** (`fillFromWorld(..., withEntities = true)`), because
     * [placeStructureCentered] places them: a default [StructurePlaceSettings] has
     * `ignoreEntities = false`, so placing a structure spawns its item frames, armour stands and
     * paintings into the world. Capturing without them made the round-trip lossy in one direction
     * only — the first unattended auto-save after a structure was placed silently deleted every
     * entity it contained. `fillFromWorld` filters out `Player`, so the person doing the editing is
     * never captured into their own structure.
     *
     * Two consequences worth knowing:
     * - The auto-fit box is computed from **blocks** only, and `fillFromWorld` collects entities
     *   from exactly that box. An entity floating outside the tight non-air box (past the last
     *   block, or in an otherwise empty structure) is still dropped. Growing the box to enclose
     *   entities would change what "the structure's extent" means for placement and re-centering,
     *   so it deliberately does not.
     * - [structuresDiffer] compares blocks only, so an entity that merely moved does not by itself
     *   count as a change — nor could it trigger a commit anyway, since the dirty-tracking watcher
     *   only ever sees `setBlock`. What this does guarantee is the reverse, and it is the important
     *   direction: capturing entities cannot make an otherwise-unchanged structure look different,
     *   so the no-op fast path still holds and a quiet structure still writes nothing.
     */
    fun captureAutoFitIn(level: ServerLevel, scan: PlacedBox): CapturedStructure {
        val template = StructureTemplate()
        if (scan.size.x <= 0 || scan.size.y <= 0 || scan.size.z <= 0) {
            return CapturedStructure(template.save(CompoundTag()), null, 0)
        }
        var blockCount = 0
        val fit = autoFit(scan.size.x, scan.size.y, scan.size.z) { lx, ly, lz ->
            val nonAir = !level.getBlockState(
                BlockPos(scan.origin.x + lx, scan.origin.y + ly, scan.origin.z + lz),
            ).`is`(Blocks.AIR)
            if (nonAir) blockCount++
            nonAir
        }
        if (fit == null) return CapturedStructure(template.save(CompoundTag()), null, 0)
        val tightOrigin = BlockPos(
            scan.origin.x + fit.minX, scan.origin.y + fit.minY, scan.origin.z + fit.minZ,
        )
        val size = Vec3i(fit.sizeX, fit.sizeY, fit.sizeZ)
        template.fillFromWorld(level, tightOrigin, size, true, emptyList())
        return CapturedStructure(template.save(CompoundTag()), PlacedBox(tightOrigin, size), blockCount)
    }

    /**
     * Place [file]'s structure centered in the region. Thin wrapper over
     * [placeStructureTagCentered]: the only thing it adds is reading the tag off disk.
     */
    fun placeStructureCentered(
        file: Path, level: ServerLevel, regionOrigin: BlockPos,
        regionSizeXZ: Int, regionMinY: Int, regionMaxY: Int, yBase: Int,
    ): PlacedBox? {
        if (!file.exists()) {
            LOGGER.warn("[StructurePersistence#placeCentered] file '{}' not found", file)
            return null
        }
        val nbt = try {
            NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap())
        } catch (e: Exception) {
            LOGGER.error("[StructurePersistence#placeCentered] read '{}': {}", file, e.message)
            return null
        }
        return placeStructureTagCentered(nbt, level, regionOrigin, regionSizeXZ, regionMinY, regionMaxY, yBase)
    }

    /**
     * Place an already-read structure [nbt] centered (X/Z) in the region, floored at [yBase] unless
     * the structure is tall enough to require vertical centering (see [anchorY]). Returns the placed
     * [PlacedBox], or null when the tag fails to load.
     *
     * Split out of [placeStructureCentered] for the Local History restore, which holds a tag read
     * from a history blob and has no file to point at. Spooling that tag to a temp file just to read
     * it back would add an IO round trip and a failure mode for nothing.
     */
    fun placeStructureTagCentered(
        nbt: CompoundTag, level: ServerLevel, regionOrigin: BlockPos,
        regionSizeXZ: Int, regionMinY: Int, regionMaxY: Int, yBase: Int,
    ): PlacedBox? {
        return try {
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
            LOGGER.debug("[StructurePersistence#placeTagCentered] placed ({}) at {}", size, origin)
            PlacedBox(origin, size)
        } catch (e: Exception) {
            LOGGER.error("[StructurePersistence#placeTagCentered] load: {}", e.message)
            null
        }
    }
}
