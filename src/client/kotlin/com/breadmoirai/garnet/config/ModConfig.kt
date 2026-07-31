package com.breadmoirai.garnet.config

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import net.fabricmc.loader.api.FabricLoader
import org.slf4j.LoggerFactory
import java.io.File

private val LOGGER = LoggerFactory.getLogger("Garnet")

/**
 * The `config/garnet.json` round-trip for every [SharedSettings] field.
 *
 * [SharedSettings] is the single copy of each value — this object holds no shadow state, so a
 * caller that mutates a setting directly and then calls [save] persists exactly what it set.
 * [projectRootPath] survives only as a delegating property because `RootPickerController` writes
 * through it.
 *
 * Known limitation: this lives in the client source set, so a dedicated server never loads it and
 * runs the compiled defaults instead.
 */
object ModConfig {
    private val defaultFile: File
        get() = FabricLoader.getInstance().configDir.resolve("garnet.json").toFile()

    private var overrideFile: File? = null
    private val configFile: File get() = overrideFile ?: defaultFile

    /** Test seam: redirect reads/writes at [file] instead of the real config directory. */
    fun configFileForTest(file: File) { overrideFile = file }
    fun resetConfigFileForTest() { overrideFile = null }

    var projectRootPath: String
        get() = SharedSettings.projectRootPath
        set(value) { SharedSettings.projectRootPath = value }

    fun load() {
        val file = configFile
        if (!file.exists()) return
        runCatching {
            file.reader().use { reader ->
                val json = JsonParser.parseReader(reader) as? JsonObject ?: return@use
                // Absent keys leave the in-memory value alone, so a hand-edited partial config
                // never silently resets unrelated settings to their compiled defaults.
                json.get("projectRootPath")?.let { SharedSettings.projectRootPath = it.asString }
                json.get("structureRegionChunks")?.let { SharedSettings.structureRegionChunks = it.asInt }
                json.get("projectCellGap")?.let { SharedSettings.projectCellGap = it.asInt }
                json.get("projectRowMax")?.let { SharedSettings.projectRowMax = it.asInt }
                json.get("projectGridYBase")?.let { SharedSettings.projectGridYBase = it.asInt }
                json.get("autoSaveEnabled")?.let { SharedSettings.autoSaveEnabled = it.asBoolean }
                json.get("autoSaveDebounceTicks")?.let { SharedSettings.autoSaveDebounceTicks = it.asInt }
                json.get("autoSaveMaxDirtyTicks")?.let { SharedSettings.autoSaveMaxDirtyTicks = it.asInt }
                json.get("localHistoryEnabled")?.let { SharedSettings.localHistoryEnabled = it.asBoolean }
                json.get("localHistoryDays")?.let { SharedSettings.localHistoryDays = it.asInt }
                json.get("localHistoryMaxRevisions")?.let { SharedSettings.localHistoryMaxRevisions = it.asInt }
                json.get("localHistoryDir")?.let { SharedSettings.localHistoryDir = it.asString }
                json.getAsJsonObject("projectCellSize")?.let { size ->
                    SharedSettings.projectCellSize = net.minecraft.core.Vec3i(
                        size.get("x").asInt, size.get("y").asInt, size.get("z").asInt,
                    )
                }
            }
        }.onFailure { e ->
            LOGGER.warn("Failed to load ModConfig from {}", file.absolutePath, e)
        }
    }

    fun save() {
        val file = configFile
        file.parentFile?.mkdirs()
        val json = JsonObject()
        json.addProperty("projectRootPath", SharedSettings.projectRootPath)
        json.addProperty("structureRegionChunks", SharedSettings.structureRegionChunks)
        json.addProperty("projectCellGap", SharedSettings.projectCellGap)
        json.addProperty("projectRowMax", SharedSettings.projectRowMax)
        json.addProperty("projectGridYBase", SharedSettings.projectGridYBase)
        json.addProperty("autoSaveEnabled", SharedSettings.autoSaveEnabled)
        json.addProperty("autoSaveDebounceTicks", SharedSettings.autoSaveDebounceTicks)
        json.addProperty("autoSaveMaxDirtyTicks", SharedSettings.autoSaveMaxDirtyTicks)
        json.addProperty("localHistoryEnabled", SharedSettings.localHistoryEnabled)
        json.addProperty("localHistoryDays", SharedSettings.localHistoryDays)
        json.addProperty("localHistoryMaxRevisions", SharedSettings.localHistoryMaxRevisions)
        json.addProperty("localHistoryDir", SharedSettings.localHistoryDir)
        val size = JsonObject()
        size.addProperty("x", SharedSettings.projectCellSize.x)
        size.addProperty("y", SharedSettings.projectCellSize.y)
        size.addProperty("z", SharedSettings.projectCellSize.z)
        json.add("projectCellSize", size)
        runCatching {
            file.writeText(json.toString())
        }.onFailure { e ->
            LOGGER.error("Failed to save ModConfig to {}", file.absolutePath, e)
        }
    }
}
