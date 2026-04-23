package com.breadmoirai.redstonespecs.runner

import net.minecraft.nbt.NbtIo
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.storage.LevelResource
import java.util.UUID

object StateRecordingStorage {
    fun save(level: ServerLevel, recording: StateRecording) {
        val file = fileFor(level, recording.specId)
        file.parentFile.mkdirs()
        NbtIo.write(recording.toNbt(), file.toPath())
    }

    fun load(level: ServerLevel, specId: UUID): StateRecording? {
        val file = fileFor(level, specId)
        if (!file.exists()) return null
        return stateRecordingFromNbt(NbtIo.read(file.toPath()) ?: return null)
    }

    fun delete(level: ServerLevel, specId: UUID) {
        fileFor(level, specId).delete()
    }

    private fun fileFor(level: ServerLevel, specId: UUID) =
        level.server.getWorldPath(LevelResource.DATA)
            .resolve("redstonespecs/$specId.dat")
            .toFile()
}
