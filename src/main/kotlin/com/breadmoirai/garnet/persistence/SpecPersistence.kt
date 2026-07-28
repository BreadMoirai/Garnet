package com.breadmoirai.garnet.persistence

import com.breadmoirai.garnet.dsl.GarnetSpec
import com.breadmoirai.garnet.runner.StateRecording
import org.slf4j.LoggerFactory
import java.nio.file.Path
import kotlin.io.path.*

private val LOGGER = LoggerFactory.getLogger("Garnet")

private const val EXT = ".spec.kts"

object SpecPersistence {

    /**
     * Writes raw `.spec.kts` source text directly to disk.
     * Used by the recording finalize path via [com.breadmoirai.garnet.runner.RecordingDslEmitter].
     */
    fun writeSpecKts(saveDir: Path, id: String, source: String) {
        saveDir.createDirectories()
        val file = saveDir.resolve("$id$EXT")
        file.writeText(source)
        LOGGER.debug("[SpecPersistence#writeSpecKts] wrote '{}' to {}", id, file)
    }

    fun loadRecording(saveDir: Path, id: String): StateRecording? =
        RecordingSidecar.load(saveDir, id)

    fun load(saveDir: Path, id: String): GarnetSpec? {
        val file = saveDir.resolve("$id$EXT")
        if (!file.exists()) return null
        return runCatching { KtsSpecLoader.loadFileAsGarnetSpec(file) }
            .onFailure { e -> LOGGER.warn("[SpecPersistence#load] failed to load '{}': {}", id, e.message) }
            .getOrNull()
    }

    fun listIds(saveDir: Path): List<String> {
        if (!saveDir.exists()) return emptyList()
        return saveDir.listDirectoryEntries("*$EXT").map {
            it.fileName.toString().removeSuffix(EXT)
        }
    }

}
