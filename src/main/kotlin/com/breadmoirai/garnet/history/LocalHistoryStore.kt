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
     * Appends [tag] as a new revision, optionally pruning. Returns the written [Revision], or null
     * when history is disabled or the write failed.
     *
     * [prune] defaults to `true` — today's behavior for every pre-existing caller: the write is
     * immediately followed by an age/count-capped prune that **permanently deletes** the blobs it
     * drops. Pass `prune = false` when the caller cannot yet promise this revision is worth keeping
     * — e.g. `StructureCommit` writes speculatively, before attempting a possibly-failing `.nbt`
     * rewrite, and rolls the write back via [discardRevision] if that rewrite then fails. If every
     * write pruned unconditionally, a stuck structure retried on a backoff would still permanently
     * delete one genuine OLDER revision per failed attempt once the count is at the cap — the
     * revision that failed doesn't survive either way, but a real one that had nothing to do with
     * this attempt would be destroyed for no reason. Callers that pass `prune = false` are
     * responsible for calling [prune] themselves once the write is confirmed worth keeping.
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
        prune: Boolean = true,
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
            val toWrite = if (prune) prune(dir, merged, nowMillis) else merged
            try {
                writeIndex(structureFile, HistoryIndex(normalizePath(structureFile, onWindows()), toWrite))
            } catch (e: IOException) {
                // The blob landed on disk but the index write failed, so revisions() would never
                // see it — an invisible orphan. Delete it so disk state matches what revisions()
                // reports, and fail honestly rather than returning a Revision nobody can find.
                runCatching { dir.resolve(name).deleteIfExists() }
                LOGGER.error(
                    "[LocalHistoryStore] write index for '{}' after writing revision '{}': {}",
                    structureFile, name, e.message,
                )
                return null
            }
            revision
        } catch (e: IOException) {
            LOGGER.error("[LocalHistoryStore] write revision for '{}': {}", structureFile, e.message)
            null
        }
    }

    /**
     * Applies the age cutoff then the count cap to [structureFile]'s history right now,
     * **permanently deleting** the blobs it drops. Call this once a [writeRevision]`(prune = false)`
     * write is confirmed worth keeping (`StructureCommit` calls it only after the `.nbt` rewrite it
     * gates has actually succeeded) — deferring the prune out of `writeRevision` itself is what
     * keeps a failed, rolled-back attempt ([discardRevision]) from destroying old history it never
     * should have touched, at any revision count including at/above the cap.
     */
    fun prune(structureFile: Path, nowMillis: Long = System.currentTimeMillis()) {
        val dir = dirFor(structureFile)
        val index = readIndex(structureFile)
        if (index.revisions.isEmpty()) return
        val pruned = prune(dir, index.revisions, nowMillis)
        if (pruned.size == index.revisions.size) return
        runCatching {
            writeIndex(structureFile, HistoryIndex(normalizePath(structureFile, onWindows()), pruned))
        }.onFailure { e ->
            LOGGER.error("[LocalHistoryStore] prune index rewrite for '{}': {}", structureFile, e.message)
        }
    }

    /**
     * Removes [revision] from [structureFile]'s index and deletes its blob.
     *
     * A seam for callers that must speculatively write a revision (typically via
     * `writeRevision(prune = false)`, so this rollback is a complete no-op against the rest of the
     * structure's history — nothing else was touched or pruned by that write) before attempting a
     * dependent, possibly-failing operation (`StructureCommit`'s atomic `.nbt` rewrite: the revision
     * has to be durable *before* the rewrite per the "never overwrite unless the prior state is
     * recoverable" invariant, but if the rewrite itself then fails, that revision must not linger —
     * a structure that can never actually commit would otherwise accumulate one indistinguishable
     * revision per retry). Best-effort: if the blob delete succeeds but the index rewrite then fails,
     * this logs and leaves the index as-is — the index would then reference a blob that no longer
     * exists, which [readTag] already handles gracefully (returns null for a missing blob) rather
     * than throwing, so that failure mode degrades to "one revision unrecoverable," not corruption.
     * The caller is already deep in its own failure handling at this point and has nothing further
     * to undo.
     */
    fun discardRevision(structureFile: Path, revision: Revision) {
        val dir = dirFor(structureFile)
        runCatching { dir.resolve(revision.file).deleteIfExists() }
        val index = readIndex(structureFile)
        val remaining = index.revisions.filterNot { it.file == revision.file }
        if (remaining.size == index.revisions.size) return
        runCatching {
            writeIndex(structureFile, HistoryIndex(normalizePath(structureFile, onWindows()), remaining))
        }.onFailure { e ->
            LOGGER.error("[LocalHistoryStore] discardRevision index rewrite for '{}': {}", structureFile, e.message)
        }
    }

    /**
     * Move a structure's history to the key for [to] — called after a rename, since the absolute
     * path (and therefore the hash) changes. Merging rather than replacing keeps any history the
     * destination path already accumulated under a previous structure of the same name.
     *
     * [move] is a seam for tests to simulate a failing move without needing OS-specific tricks
     * (locked files, read-only dirs) that don't behave uniformly across platforms; production
     * callers never pass it and get a real [kotlin.io.path.moveTo].
     *
     * If any revision fails to move, its blob is left behind in `fromDir` on purpose: `fromDir` is
     * NOT deleted, and its index is rewritten to name exactly what's still physically there, so the
     * history stays recoverable instead of being silently destroyed by the unconditional cleanup
     * that used to run regardless of per-revision failures.
     */
    fun moveHistory(from: Path, to: Path, move: (source: Path, target: Path) -> Unit = { s, t -> s.moveTo(t) }) {
        val fromDir = dirFor(from)
        if (!fromDir.exists()) return
        val fromIndex = readIndex(from)
        val toDir = dirFor(to)
        toDir.createDirectories()
        val toIndex = readIndex(to)

        val moved = ArrayList<Revision>()
        val remaining = ArrayList<Revision>()
        for (revision in fromIndex.revisions) {
            val source = fromDir.resolve(revision.file)
            if (!source.exists()) continue
            // A destination collision is only possible when both sides wrote in the same
            // millisecond; re-sequence rather than clobber.
            var seq = 0
            var name = "${revision.timestampMillis}-$seq.nbt"
            while (toDir.resolve(name).exists()) { seq++; name = "${revision.timestampMillis}-$seq.nbt" }
            try {
                move(source, toDir.resolve(name))
                moved += revision.copy(file = name)
            } catch (e: IOException) {
                LOGGER.error("[LocalHistoryStore] move revision '{}' -> '{}': {}", source, toDir.resolve(name), e.message)
                remaining += revision
            }
        }

        val merged = (toIndex.revisions + moved).sortedBy { it.timestampMillis }
        try {
            writeIndex(to, HistoryIndex(normalizePath(to, onWindows()), merged))
        } catch (e: IOException) {
            // The destination index couldn't be written even though some blobs may have already
            // physically moved into toDir. We can't claim they're indexed there, and fromDir must
            // not be wiped — it may hold the only surviving copy of revisions that failed to move,
            // and even the ones that DID move are now unindexed at the destination. Log loudly and
            // stop; both directories are left as-is rather than risking silent data loss.
            LOGGER.error(
                "[LocalHistoryStore] write destination index for '{}' after moving {} revision(s): {}",
                to, moved.size, e.message,
            )
            return
        }

        if (remaining.isNotEmpty()) {
            // Partial move: rewrite the source index to name exactly what's still there, and keep
            // fromDir — do not delete history that failed to relocate.
            try {
                writeIndex(from, HistoryIndex(normalizePath(from, onWindows()), remaining.sortedBy { it.timestampMillis }))
            } catch (e: IOException) {
                LOGGER.error("[LocalHistoryStore] write source index for '{}' after partial move: {}", from, e.message)
            }
            LOGGER.error(
                "[LocalHistoryStore] moveHistory '{}' -> '{}' was partial: {} revision(s) moved, {} left at source",
                from, to, moved.size, remaining.size,
            )
            return
        }

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

    /**
     * Writes `index.json`. Throws [IOException] on failure rather than swallowing it — callers
     * must decide how to react (undo a just-written blob, abort a move, etc.); this function has
     * no way to know what "recover" means for each caller, so it must not silently report success.
     */
    private fun writeIndex(structureFile: Path, index: HistoryIndex) {
        val dir = dirFor(structureFile)
        dir.createDirectories()
        dir.resolve(INDEX_FILE).writeText(GSON.toJson(index))
    }
}
