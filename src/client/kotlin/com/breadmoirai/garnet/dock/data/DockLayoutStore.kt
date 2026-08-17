package com.breadmoirai.garnet.dock.data

import com.breadmoirai.garnet.dock.shell.DockRegion
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import net.fabricmc.loader.api.FabricLoader
import org.slf4j.LoggerFactory
import java.io.File

private val LOGGER = LoggerFactory.getLogger("Garnet")

/**
 * The `config/garnet-dock.json` round-trip for which dock panel is open in each region.
 *
 * Deliberately NOT part of [ModConfig]: that object's contract is a pure [SharedSettings] round-trip,
 * and `SharedSettings` is read by the dedicated server — dock layout is client UI state a server must
 * never see. Deliberately NOT part of [ExplorerStateStore] either: that record is keyed by project
 * root and written only in singleplayer, and dock layout is neither. Reusing that file would silently
 * inherit both restrictions and break auto-open on a remote Garnet server.
 *
 * Panel ids, not a boolean. The record used to be `{"leftVisible": true}`, from when visibility was
 * per-region; [load] still reads that shape and migrates it, and the file is rewritten in the new
 * shape on the next [save]. Splitter sizes are still process-lifetime state that
 * `DockState.closeAll()` preserves, and nothing needs them to survive a restart yet.
 */
object DockLayoutStore {
    /** What a fresh install — or an untrustworthy record — gets: the Explorer open in LEFT. */
    val DEFAULT_OPEN: Map<DockRegion, String> = mapOf(DockRegion.LEFT to "garnet.explorer")

    private const val EXPLORER_ID = "garnet.explorer"

    private val defaultFile: File
        get() = FabricLoader.getInstance().configDir.resolve("garnet-dock.json").toFile()

    private var overrideFile: File? = null
    private val configFile: File get() = overrideFile ?: defaultFile

    /** Test seam: redirect reads/writes at [file] instead of the real config directory. */
    fun configFileForTest(file: File) { overrideFile = file }
    fun resetConfigFileForTest() { overrideFile = null }

    /**
     * The remembered open panel per region.
     *
     * Every *untrustworthy* path — absent file, unreadable file, malformed JSON, no recognised key —
     * yields [DEFAULT_OPEN]. A *well-formed* record saying nothing is open is honoured as the
     * explicit user choice it is, which is why `{"open":{}}` returns empty rather than the default.
     *
     * Entries are dropped individually rather than failing the whole read: an unknown region name or
     * a non-string panel id costs that one entry, so a panel removed in a future version cannot wedge
     * the file.
     */
    fun load(): Map<DockRegion, String> {
        val file = configFile
        if (!file.exists()) return DEFAULT_OPEN
        return runCatching {
            file.reader().use { reader ->
                val json = JsonParser.parseReader(reader) as? JsonObject ?: return@use DEFAULT_OPEN
                json.getAsJsonObject("open")?.let { open ->
                    return@use buildMap {
                        for ((name, value) in open.entrySet()) {
                            val region = DockRegion.entries.firstOrNull { it.name == name } ?: continue
                            val id = (value as? JsonPrimitive)?.takeIf { it.isString }?.asString ?: continue
                            put(region, id)
                        }
                    }
                }
                // Legacy shape, written before visibility became per-panel.
                val legacy = json.get("leftVisible")
                if (legacy != null && legacy.isJsonPrimitive && legacy.asJsonPrimitive.isBoolean) {
                    return@use if (legacy.asBoolean) mapOf(DockRegion.LEFT to EXPLORER_ID) else emptyMap()
                }
                DEFAULT_OPEN
            }
        }.onFailure { e ->
            LOGGER.warn("Failed to load dock layout from {}", file.absolutePath, e)
        }.getOrDefault(DEFAULT_OPEN)
    }

    /** Overwrite the stored record with [open], replacing any legacy `leftVisible` key. */
    fun save(open: Map<DockRegion, String>) {
        val file = configFile
        file.parentFile?.mkdirs()
        val entries = JsonObject()
        open.forEach { (region, id) -> entries.addProperty(region.name, id) }
        val json = JsonObject()
        json.add("open", entries)
        runCatching {
            file.writeText(json.toString())
        }.onFailure { e ->
            LOGGER.error("Failed to save dock layout to {}", file.absolutePath, e)
        }
    }
}
