package com.breadmoirai.redstonespecs.persistence

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
import net.minecraft.world.level.levelgen.structure.BoundingBox
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.file.Path
import kotlin.io.path.*

private val LOGGER = LoggerFactory.getLogger("Redstone Specs")

object StructurePersistence {

    fun save(saveDir: Path, id: String, level: ServerLevel, originPos: BlockPos, bounds: BoundingBox) {
        saveDir.createDirectories()
        val template = StructureTemplate()
        val worldMin = worldMin(originPos, bounds)
        val size = size(bounds)
        template.fillFromWorld(level, worldMin, size, false, emptyList())
        val nbt = template.save(CompoundTag())
        val file = saveDir.resolve("$id.nbt")
        try {
            NbtIo.writeCompressed(nbt, file)
        } catch (e: IOException) {
            LOGGER.error("[StructurePersistence#save] failed to write '{}': {}", id, e.message)
        }
        LOGGER.debug("[StructurePersistence#save] saved structure '{}' to {}", id, file)
    }

    fun load(saveDir: Path, id: String, level: ServerLevel, originPos: BlockPos, bounds: BoundingBox) {
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
            val worldMin = worldMin(originPos, bounds)
            template.placeInWorld(level, worldMin, worldMin, StructurePlaceSettings(), level.random, 2)
            LOGGER.debug("[StructurePersistence#load] placed structure '{}' at {}", id, worldMin)
        } catch (e: IOException) {
            LOGGER.error("[StructurePersistence#load] failed to read '{}': {}", id, e.message)
        }
    }

    fun hasChanges(saveDir: Path, id: String, level: ServerLevel, originPos: BlockPos, bounds: BoundingBox): Boolean {
        val file = saveDir.resolve("$id.nbt")
        if (!file.exists()) return true
        return try {
            val savedNbt = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap())
            val live = StructureTemplate()
            live.fillFromWorld(level, worldMin(originPos, bounds), size(bounds), false, emptyList())
            val liveNbt = live.save(CompoundTag())
            savedNbt != liveNbt
        } catch (e: IOException) {
            LOGGER.error("[StructurePersistence#hasChanges] failed to read '{}': {}", id, e.message)
            true
        }
    }

    fun hasNonAirBlocks(level: ServerLevel, originPos: BlockPos, bounds: BoundingBox): Boolean {
        val min = worldMin(originPos, bounds)
        val max = BlockPos(
            originPos.x + bounds.maxX(),
            originPos.y + bounds.maxY(),
            originPos.z + bounds.maxZ(),
        )
        for (x in min.x..max.x)
            for (y in min.y..max.y)
                for (z in min.z..max.z)
                    if (!level.getBlockState(BlockPos(x, y, z)).`is`(Blocks.AIR)) return true
        return false
    }

    fun clearBounds(level: ServerLevel, originPos: BlockPos, bounds: BoundingBox) {
        val min = worldMin(originPos, bounds)
        val max = BlockPos(
            originPos.x + bounds.maxX(),
            originPos.y + bounds.maxY(),
            originPos.z + bounds.maxZ(),
        )
        for (x in min.x..max.x)
            for (y in min.y..max.y)
                for (z in min.z..max.z)
                    level.setBlock(BlockPos(x, y, z), Blocks.AIR.defaultBlockState(), 2)
    }

    fun listIds(saveDir: Path): List<String> {
        if (!saveDir.exists()) return emptyList()
        return saveDir.listDirectoryEntries("*.nbt").map { it.nameWithoutExtension }
    }

    private fun worldMin(originPos: BlockPos, bounds: BoundingBox) = BlockPos(
        originPos.x + bounds.minX(),
        originPos.y + bounds.minY(),
        originPos.z + bounds.minZ(),
    )

    private fun size(bounds: BoundingBox) = Vec3i(
        bounds.maxX() - bounds.minX() + 1,
        bounds.maxY() - bounds.minY() + 1,
        bounds.maxZ() - bounds.minZ() + 1,
    )
}
