package com.breadmoirai.redstonespecs.persistence

import com.breadmoirai.redstonespecs.dsl.RedstoneSpec
import com.breadmoirai.redstonespecs.runner.StateRecording
import org.slf4j.LoggerFactory
import java.nio.file.Path
import kotlin.io.path.*

private val LOGGER = LoggerFactory.getLogger("Redstone Specs")

private const val EXT = ".spec.kts"

object SpecPersistence {

    /**
     * Writes raw `.spec.kts` source text directly to disk.
     * Used by the recording finalize path via [com.breadmoirai.redstonespecs.runner.RecordingDslEmitter].
     */
    fun writeSpecKts(saveDir: Path, id: String, source: String) {
        saveDir.createDirectories()
        val file = saveDir.resolve("$id$EXT")
        file.writeText(source)
        LOGGER.debug("[SpecPersistence#writeSpecKts] wrote '{}' to {}", id, file)
    }

    fun loadRecording(saveDir: Path, id: String): StateRecording? =
        RecordingSidecar.load(saveDir, id)

    fun load(saveDir: Path, id: String): RedstoneSpec? {
        val file = saveDir.resolve("$id$EXT")
        if (!file.exists()) return null
        return runCatching { KtsSpecLoader.loadFileAsRedstoneSpec(file) }
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
