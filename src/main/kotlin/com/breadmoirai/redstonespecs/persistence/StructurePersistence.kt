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
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.file.Path
import kotlin.io.path.*

private val LOGGER = LoggerFactory.getLogger("Redstone Specs")

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
}
