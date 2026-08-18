package com.breadmoirai.garnet.editor.explorer.ui

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import net.fabricmc.loader.api.FabricLoader
import org.slf4j.LoggerFactory
import java.io.File

private val LOGGER = LoggerFactory.getLogger("Garnet")

/**
 * One session's Explorer tree state: which project [root] it was captured against, the `/`-joined
 * paths of the folders that were open, and the selected node's path (null when nothing was
 * selected). The project root itself is the empty-string path, so `expanded` normally contains `""`.
 */
data class ExplorerSession(
    val root: String,
    val expanded: Set<String>,
    val selected: String?,
)

/**
 * The `config/garnet-explorer.json` round-trip for the Explorer's per-session tree state.
 *
 * Deliberately NOT part of [com.breadmoirai.garnet.core.config.ModConfig]: that object's contract is
 * a pure [com.breadmoirai.garnet.core.config.SharedSettings] round-trip with no shadow state, and
 * expansion/selection is client UI state a server must never see — `SharedSettings` is read by the
 * dedicated server. A separate file keeps that boundary intact.
 *
 * Exactly one record is stored, keyed by the project root it was captured against. Root swaps are
 * rare, so a per-root map would grow forever for no benefit; a record whose [ExplorerSession.root]
 * does not match the active root is simply discarded by the consumer.
 */
object ExplorerStateStore {
    private val defaultFile: File
        get() = FabricLoader.getInstance().configDir.resolve("garnet-explorer.json").toFile()

    private var overrideFile: File? = null
    private val configFile: File get() = overrideFile ?: defaultFile

    /** Test seam: redirect reads/writes at [file] instead of the real config directory. */
    fun configFileForTest(file: File) { overrideFile = file }
    fun resetConfigFileForTest() { overrideFile = null }

    /**
     * The persisted session, or null when there is none to restore — absent file, malformed JSON,
     * or a record with no `root`. A restore is a convenience, so every failure degrades to "open
     * the tree fresh" rather than propagating.
     */
    fun load(): ExplorerSession? {
        val file = configFile
        if (!file.exists()) return null
        return runCatching {
            file.reader().use { reader ->
                val json = JsonParser.parseReader(reader) as? JsonObject ?: return@use null
                val root = json.get("root")?.asString ?: return@use null
                if (root.isBlank()) return@use null
                val expanded = json.getAsJsonArray("expanded")
                    ?.map { it.asString }
                    ?.toSet()
                    ?: emptySet()
                val selected = json.get("selected")?.takeIf { !it.isJsonNull }?.asString
                ExplorerSession(root, expanded, selected)
            }
        }.onFailure { e ->
            LOGGER.warn("Failed to load Explorer session from {}", file.absolutePath, e)
        }.getOrNull()
    }

    /**
     * Overwrite the stored record. A blank [root] writes nothing: without a root there is no key to
     * match on later, so the record could only ever be discarded on load.
     */
    fun save(root: String, expanded: Set<String>, selected: String?) {
        if (root.isBlank()) return
        val file = configFile
        file.parentFile?.mkdirs()
        val json = JsonObject()
        json.addProperty("root", root)
        val arr = JsonArray()
        expanded.forEach { arr.add(it) }
        json.add("expanded", arr)
        if (selected != null) json.addProperty("selected", selected)
        runCatching {
            file.writeText(json.toString())
        }.onFailure { e ->
            LOGGER.error("Failed to save Explorer session to {}", file.absolutePath, e)
        }
    }
}
