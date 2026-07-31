package com.breadmoirai.garnet.history

import com.breadmoirai.garnet.config.SharedSettings
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.NbtAccounter
import net.minecraft.nbt.NbtIo
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.moveTo
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.writeText

private val LOGGER = LoggerFactory.getLogger("Garnet")
private val GSON: Gson = GsonBuilder().setPrettyPrinting().create()

private const val INDEX_FILE = "index.json"
private const val MILLIS_PER_DAY = 24L * 60 * 60 * 1000

/**
 * JetBrains-style local history for standalone `.nbt` structures.
 *
 * ```
 * <instance>/.garnet/local-history/<stem>-<hash8>/<epochMillis>-<seq>.nbt
 * <instance>/.garnet/local-history/<stem>-<hash8>/index.json
 * ```
 *
 * **Keying is by the structure file's own absolute path, never by the project root.** The editor's
 * root is swappable ("Open Folder…"), so keying by root would fork one file's history the moment a
 * user opened its parent directory instead of the directory itself. The `<stem>` prefix exists only
 * so the directory is browsable by hand; the hash is what identifies it.
 *
 * History deliberately outlives the structure it describes — deleting a `.nbt` leaves its revisions
 * in place, since recovering a deleted structure is exactly what this store is for.
 */
object LocalHistoryStore {

    const val REASON_PLACED = "placed"
    const val REASON_AUTOSAVE = "autosave"
    const val REASON_MANUAL = "manual"

    /** `<instance>/.garnet/local-history`, or [SharedSettings.localHistoryDir] when set. */
    fun historyRoot(): Path {
        val configured = SharedSettings.localHistoryDir
        if (configured.isNotBlank()) return Path.of(configured)
        return FabricLoader.getInstance().gameDir.resolve(".garnet").resolve("local-history")
    }

    /**
     * The canonical string form of [structureFile] used as hash input. Windows paths are lowercased
     * because its filesystem is case-insensitive: the same file reached as `Clock.nbt` and
     * `clock.nbt` must land in one history, not two.
     */
    fun normalizePath(structureFile: Path, windows: Boolean): String {
        val absolute = structureFile.toAbsolutePath().normalize().toString()
        return if (windows) absolute.lowercase() else absolute
    }

    private fun onWindows(): Boolean =
        System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)

    /** `<stem>-<hash8>` — the history directory name for [structureFile]. */
    fun keyOf(structureFile: Path): String {
        val normalized = normalizePath(structureFile, onWindows())
        val digest = MessageDigest.getInstance("SHA-256").digest(normalized.toByteArray())
        val hash8 = digest.take(4).joinToString("") { "%02x".format(it) }
        val stem = structureFile.normalize().name.substringBeforeLast('.').ifEmpty { "structure" }
        return "${sanitize(stem)}-$hash8"
    }

    /** Keep the browsable prefix filesystem-safe; the hash carries the actual identity. */
    private fun sanitize(stem: String): String =
        stem.map { if (it.isLetterOrDigit() || it == '-' || it == '_') it else '_' }.joinToString("")

    fun dirFor(structureFile: Path): Path = historyRoot().resolve(keyOf(structureFile))

    /** Chronological (oldest first). Empty when the structure has no history. */
    fun revisions(structureFile: Path): List<Revision> = readIndex(structureFile).revisions

    /** The stored blob for [revision], or null if it is missing or unreadable. */
    fun readTag(structureFile: Path, revision: Revision): CompoundTag? {
        val blob = dirFor(structureFile).resolve(revision.file)
        if (!blob.exists()) return null
        return try {
            NbtIo.readCompressed(blob, NbtAccounter.unlimitedHeap())
        } catch (e: IOException) {
            LOGGER.error("[LocalHistoryStore] read revision '{}': {}", blob, e.message)
            null
        }
    }

    /**
     * Appends [tag] as a new revision and prunes. Returns the written [Revision], or null when
     * history is disabled or the write failed.
     */
    fun writeRevision(
        structureFile: Path,
        tag: CompoundTag,
        sizeX: Int,
        sizeY: Int,
        sizeZ: Int,
        blockCount: Int,
        reason: String,
        nowMillis: Long = System.currentTimeMillis(),
    ): Revision? {
        if (!SharedSettings.localHistoryEnabled) return null
        val dir = dirFor(structureFile)
        val index = readIndex(structureFile)

        // Same-millisecond writes would otherwise collide on filename; bump the sequence until free.
        var seq = 0
        var name = "$nowMillis-$seq.nbt"
        while (dir.resolve(name).exists() || index.revisions.any { it.file == name }) {
            seq++
            name = "$nowMillis-$seq.nbt"
        }

        val revision = Revision(name, nowMillis, sizeX, sizeY, sizeZ, blockCount, reason)
        return try {
            dir.createDirectories()
            NbtIo.writeCompressed(tag, dir.resolve(name))
            val merged = (index.revisions + revision).sortedBy { it.timestampMillis }
            writeIndex(structureFile, HistoryIndex(normalizePath(structureFile, onWindows()), prune(dir, merged, nowMillis)))
            revision
        } catch (e: IOException) {
            LOGGER.error("[LocalHistoryStore] write revision for '{}': {}", structureFile, e.message)
            null
        }
    }

    /**
     * Move a structure's history to the key for [to] — called after a rename, since the absolute
     * path (and therefore the hash) changes. Merging rather than replacing keeps any history the
     * destination path already accumulated under a previous structure of the same name.
     */
    fun moveHistory(from: Path, to: Path) {
        val fromDir = dirFor(from)
        if (!fromDir.exists()) return
        val fromIndex = readIndex(from)
        val toDir = dirFor(to)
        toDir.createDirectories()
        val toIndex = readIndex(to)

        val moved = ArrayList<Revision>()
        for (revision in fromIndex.revisions) {
            val source = fromDir.resolve(revision.file)
            if (!source.exists()) continue
            // A destination collision is only possible when both sides wrote in the same
            // millisecond; re-sequence rather than clobber.
            var seq = 0
            var name = "${revision.timestampMillis}-$seq.nbt"
            while (toDir.resolve(name).exists()) { seq++; name = "${revision.timestampMillis}-$seq.nbt" }
            try {
                source.moveTo(toDir.resolve(name))
                moved += revision.copy(file = name)
            } catch (e: IOException) {
                LOGGER.error("[LocalHistoryStore] move revision '{}': {}", source, e.message)
            }
        }
        val merged = (toIndex.revisions + moved).sortedBy { it.timestampMillis }
        writeIndex(to, HistoryIndex(normalizePath(to, onWindows()), merged))
        fromDir.resolve(INDEX_FILE).deleteIfExists()
        runCatching { fromDir.toFile().deleteRecursively() }
    }

    /**
     * Applies the age cutoff then the count cap to [revisions], deleting the blobs it drops.
     * Returns what survives, chronological.
     */
    private fun prune(dir: Path, revisions: List<Revision>, nowMillis: Long): List<Revision> {
        val cutoff = nowMillis - SharedSettings.localHistoryDays.toLong() * MILLIS_PER_DAY
        val byAge = revisions.filter { it.timestampMillis >= cutoff }
        val capped = byAge.takeLast(SharedSettings.localHistoryMaxRevisions.coerceAtLeast(1))
        val keptFiles = capped.mapTo(HashSet()) { it.file }
        for (dropped in revisions) {
            if (dropped.file in keptFiles) continue
            runCatching { dir.resolve(dropped.file).deleteIfExists() }
        }
        return capped
    }

    private fun readIndex(structureFile: Path): HistoryIndex {
        val file = dirFor(structureFile).resolve(INDEX_FILE)
        val empty = HistoryIndex(normalizePath(structureFile, onWindows()), emptyList())
        if (!file.exists()) return empty
        return runCatching { GSON.fromJson(file.readText(), HistoryIndex::class.java) ?: empty }
            .getOrElse { e ->
                LOGGER.error("[LocalHistoryStore] read index '{}': {}", file, e.message)
                empty
            }
    }

    private fun writeIndex(structureFile: Path, index: HistoryIndex) {
        val dir = dirFor(structureFile)
        runCatching {
            dir.createDirectories()
            dir.resolve(INDEX_FILE).writeText(GSON.toJson(index))
        }.onFailure { e ->
            LOGGER.error("[LocalHistoryStore] write index for '{}': {}", structureFile, e.message)
        }
    }
}
