package com.breadmoirai.redstonespecs.persistence

import com.breadmoirai.redstonespecs.data.EntryKind
import com.breadmoirai.redstonespecs.data.RedstoneSpec
import com.breadmoirai.redstonespecs.data.serial.KtsSpecEmitter
import com.breadmoirai.redstonespecs.data.serial.KtsSpecLoader
import com.breadmoirai.redstonespecs.network.SpecFileInfo
import org.slf4j.LoggerFactory
import java.nio.file.Path
import kotlin.io.path.*

private val LOGGER = LoggerFactory.getLogger("Redstone Specs")

private const val EXT = ".spec.kts"

object SpecPersistence {

    fun save(saveDir: Path, spec: RedstoneSpec) {
        saveDir.createDirectories()
        val file = saveDir.resolve("${spec.id}$EXT")
        file.writeText(KtsSpecEmitter.emit(spec))
        LOGGER.debug("[SpecPersistence#save] saved spec '{}' to {}", spec.id, file)
    }

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

    fun listSpecsInfo(saveDir: Path): List<SpecFileInfo> {
        return listIds(saveDir).mapNotNull { id ->
            val spec = load(saveDir, id) ?: return@mapNotNull null
            SpecFileInfo(
                id = spec.id,
                lifespan = spec.lifespan,
                inputCount = spec.entries.count { it.kind == EntryKind.INPUT },
                outputCount = spec.entries.count { it.kind == EntryKind.OUTPUT },
                structure = spec.structure,
            )
        }
    }
}
