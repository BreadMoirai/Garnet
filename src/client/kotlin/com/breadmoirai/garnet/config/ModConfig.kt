package com.breadmoirai.garnet.config

import com.breadmoirai.garnet.config.SharedSettings
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import net.fabricmc.loader.api.FabricLoader
import org.slf4j.LoggerFactory

private val LOGGER = LoggerFactory.getLogger("Garnet")

object ModConfig {
    private val configFile = FabricLoader.getInstance().configDir.resolve("garnet.json").toFile()

    var projectRootPath: String = ""

    fun load() {
        if (!configFile.exists()) return
        runCatching {
            configFile.reader().use { reader ->
                val json = JsonParser.parseReader(reader) as? JsonObject ?: return@use
                projectRootPath = json.get("projectRootPath")?.asString ?: ""
            }
        }.onFailure { e ->
            LOGGER.warn("Failed to load ModConfig from {}", configFile.absolutePath, e)
        }
        SharedSettings.projectRootPath = projectRootPath
    }

    fun save() {
        configFile.parentFile?.mkdirs()
        val json = JsonObject()
        json.addProperty("projectRootPath", projectRootPath)
        runCatching {
            configFile.writeText(json.toString())
        }.onFailure { e ->
            LOGGER.error("Failed to save ModConfig to {}", configFile.absolutePath, e)
        }
        SharedSettings.projectRootPath = projectRootPath
    }
}
