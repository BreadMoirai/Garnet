package com.breadmoirai.garnet.playback.data

import net.minecraft.nbt.NbtIo
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.storage.LevelResource
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.file.Path
import java.util.UUID

private val LOGGER = LoggerFactory.getLogger("Garnet")

object StateRecordingStorage {
    fun save(level: ServerLevel, recording: StateRecording) {
        val file = fileFor(level.server.getWorldPath(LevelResource.DATA), recording.specId)
        file.parentFile.mkdirs()
        try {
            NbtIo.write(recording.toNbt(), file.toPath())
        } catch (e: IOException) {
            LOGGER.error("[StateRecordingStorage] Failed to save recording for spec {}", recording.specId, e)
        }
    }

    fun load(level: ServerLevel, specId: UUID): StateRecording? {
        val file = fileFor(level.server.getWorldPath(LevelResource.DATA), specId)
        if (!file.exists()) return null
        return try {
            stateRecordingFromNbt(NbtIo.read(file.toPath()) ?: return null)
        } catch (e: IOException) {
            LOGGER.error("[StateRecordingStorage] Failed to load recording for spec {}", specId, e)
            null
        }
    }

    fun delete(level: ServerLevel, specId: UUID) {
        val file = fileFor(level.server.getWorldPath(LevelResource.DATA), specId)
        if (file.exists() && !file.delete()) {
            LOGGER.warn("[StateRecordingStorage] Failed to delete recording for spec {}", specId)
        }
    }

    internal fun fileFor(worldDataPath: Path, specId: UUID) =
        worldDataPath.resolve("garnet/$specId.dat").toFile()
}
