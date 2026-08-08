package com.breadmoirai.garnet.config

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import net.fabricmc.loader.api.FabricLoader
import org.slf4j.LoggerFactory
import java.io.File

private val LOGGER = LoggerFactory.getLogger("Garnet")

/**
 * The `config/garnet-dock.json` round-trip for the dock's remembered LEFT-region visibility.
 *
 * Deliberately NOT part of [ModConfig]: that object's contract is a pure [SharedSettings] round-trip,
 * and `SharedSettings` is read by the dedicated server — dock visibility is client UI state a server
 * must never see. Deliberately NOT part of [ExplorerStateStore] either: that record is keyed by
 * project root and written only in singleplayer, and dock visibility is neither. Reusing that file
 * would silently inherit both restrictions and break auto-open on a remote Garnet server.
 *
 * A single boolean, not the full layout. Splitter sizes are process-lifetime state that
 * `DockState.closeAll()` already preserves, and nothing needs them to survive a restart yet.
 */
object DockLayoutStore {
    private val defaultFile: File
        get() = FabricLoader.getInstance().configDir.resolve("garnet-dock.json").toFile()

    private var overrideFile: File? = null
    private val configFile: File get() = overrideFile ?: defaultFile

    /** Test seam: redirect reads/writes at [file] instead of the real config directory. */
    fun configFileForTest(file: File) { overrideFile = file }
    fun resetConfigFileForTest() { overrideFile = null }

    /**
     * The remembered LEFT visibility, defaulting to `true`.
     *
     * Every failure path — absent file, unreadable file, malformed JSON, missing or non-boolean key
     * — yields the default rather than propagating. Restoring the layout is a convenience, and
     * "open the dock" is the wanted behaviour for a fresh install, so it is also the right thing to
     * fall back to when the record cannot be trusted.
     */
    fun load(): Boolean {
        val file = configFile
        if (!file.exists()) return true
        return runCatching {
            file.reader().use { reader ->
                val json = JsonParser.parseReader(reader) as? JsonObject ?: return@use true
                val value = json.get("leftVisible") ?: return@use true
                if (!value.isJsonPrimitive || !value.asJsonPrimitive.isBoolean) return@use true
                value.asBoolean
            }
        }.onFailure { e ->
            LOGGER.warn("Failed to load dock layout from {}", file.absolutePath, e)
        }.getOrDefault(true)
    }

    /** Overwrite the stored record with [leftVisible]. */
    fun save(leftVisible: Boolean) {
        val file = configFile
        file.parentFile?.mkdirs()
        val json = JsonObject()
        json.addProperty("leftVisible", leftVisible)
        runCatching {
            file.writeText(json.toString())
        }.onFailure { e ->
            LOGGER.error("Failed to save dock layout to {}", file.absolutePath, e)
        }
    }
}
