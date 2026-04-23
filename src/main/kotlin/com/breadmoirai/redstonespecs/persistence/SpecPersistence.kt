package com.breadmoirai.redstonespecs.persistence

import com.breadmoirai.redstonespecs.data.RedstoneSpec
import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import com.mojang.serialization.JsonOps
import org.slf4j.LoggerFactory
import java.nio.file.Path
import kotlin.io.path.*

private val LOGGER = LoggerFactory.getLogger("Redstone Specs")
private val GSON = GsonBuilder().setPrettyPrinting().create()

object SpecPersistence {

    fun save(saveDir: Path, spec: RedstoneSpec) {
        saveDir.createDirectories()
        val jsonElement = RedstoneSpec.CODEC.encodeStart(JsonOps.INSTANCE, spec).getOrThrow()
        val file = saveDir.resolve("${spec.id}.json")
        file.writeText(GSON.toJson(jsonElement))
        LOGGER.debug("[SpecPersistence#save] saved spec '{}' to {}", spec.id, file)
    }

    fun load(saveDir: Path, id: String): RedstoneSpec? {
        val file = saveDir.resolve("$id.json")
        if (!file.exists()) return null
        return runCatching {
            val json = JsonParser.parseReader(file.reader())
            RedstoneSpec.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow()
        }.onFailure { e ->
            LOGGER.warn("[SpecPersistence#load] failed to load spec '{}': {}", id, e.message)
        }.getOrNull()
    }

    fun listIds(saveDir: Path): List<String> {
        if (!saveDir.exists()) return emptyList()
        return saveDir.listDirectoryEntries("*.json").map { it.nameWithoutExtension }
    }
}
