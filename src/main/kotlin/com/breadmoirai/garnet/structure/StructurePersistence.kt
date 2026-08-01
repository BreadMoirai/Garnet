package com.breadmoirai.garnet.structure

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
     * This is the auto-save path's capture: scanning `union(placedBox, dirtyBox)` reads a few
     * thousand positions where scanning the full region reads ~8M, which is the difference between
     * a viable per-edit debounce and an unusable one. A zero-size [scan] is empty by definition.
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
        template.fillFromWorld(level, tightOrigin, size, false, emptyList())
        return CapturedStructure(template.save(CompoundTag()), PlacedBox(tightOrigin, size), blockCount)
    }

    /**
     * Auto-fit across a whole structure region. There is no "explicit-save path" any more — the
     * old sidecar model that used this for a full-region flush on save was replaced by the
     * `.nbt`-per-commit model, and every commit (`StructureCommit.commit`) goes through
     * [captureAutoFitIn] with the tiny `union(placedBox, dirtyBox)` volume instead (F10). This
     * remains test-only: [StructureRegionPersistenceSpec] and [StructureAutoSaveSpec] exercise it
     * directly to assert the tight-fit scan itself. **Do not call this from any commit path** — a
     * full ~144-wide-region scan (~8M block reads) on every debounce is exactly the cost
     * [captureAutoFitIn] exists to avoid.
     */
    fun captureAutoFit(
        level: ServerLevel, regionOrigin: BlockPos,
        regionSizeXZ: Int, regionMinY: Int, regionMaxY: Int,
    ): Pair<CompoundTag, PlacedBox?> {
        val scan = PlacedBox(
            BlockPos(regionOrigin.x, regionMinY, regionOrigin.z),
            Vec3i(regionSizeXZ, regionMaxY - regionMinY + 1, regionSizeXZ),
        )
        val captured = captureAutoFitIn(level, scan)
        return captured.tag to captured.box
    }

    /**
     * Scans the full region volume ([regionSizeXZ] wide, `regionMinY..regionMaxY` tall) for
     * non-air, computes the tight box, and writes exactly that box into [file] as a compressed
     * structure. Returns the captured [PlacedBox] (absolute origin + size), or null when the
     * region is empty (an empty structure is still written).
     *
     * Test-only (F10): built on [captureAutoFit]'s full-region scan, which no commit path uses —
     * see that function's KDoc. [StructureRegionPersistenceSpec] uses this to assert the
     * region-wide auto-fit-and-write behavior directly.
     */
    fun saveAutoFitToFile(
        file: Path, level: ServerLevel, regionOrigin: BlockPos,
        regionSizeXZ: Int, regionMinY: Int, regionMaxY: Int,
    ): PlacedBox? {
        val (tag, box) = captureAutoFit(level, regionOrigin, regionSizeXZ, regionMinY, regionMaxY)
        try { writeStructureAtomic(tag, file) }
        catch (e: IOException) { LOGGER.error("[StructurePersistence#saveAutoFit] write '{}': {}", file, e.message) }
        LOGGER.debug("[StructurePersistence#saveAutoFit] captured {} at {} -> {}", box?.size, box?.origin, file)
        return box
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
        } catch (e: Exception) {
            LOGGER.error("[StructurePersistence#placeCentered] read '{}': {}", file, e.message)
            null
        }
    }
}
