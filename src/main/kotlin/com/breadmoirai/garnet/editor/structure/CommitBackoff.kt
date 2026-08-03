package com.breadmoirai.garnet.editor.structure

import net.minecraft.server.MinecraftServer
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

private val LOGGER = LoggerFactory.getLogger("Garnet")

/**
 * Per-server failure-backoff and last-committed-disk-fingerprint bookkeeping for
 * [StructureCommit]. Split out of that object (Task 3) so the orchestration logic in
 * `StructureCommit.kt` isn't tangled with this attached-state plumbing.
 */
internal object CommitBackoff {

    /**
     * Minimum ticks between retrying a structure whose last commit attempt failed to write its
     * `.nbt` or its history revision. ~5s at 20 tps — far above the previous every-tick retry, low
     * enough that a transient lock (antivirus scan, a momentarily-open file handle) still recovers
     * quickly. Gates the automatic [StructureCommit.tick] pass only; an explicit
     * [StructureCommit.commit] call always attempts.
     */
    const val FAILURE_BACKOFF_TICKS = 100L

    private val retryAfter = java.util.WeakHashMap<MinecraftServer, ConcurrentHashMap<String, Long>>()

    @Synchronized
    fun backoffMap(server: MinecraftServer): ConcurrentHashMap<String, Long> =
        retryAfter.getOrPut(server) { ConcurrentHashMap() }

    /**
     * What the `.nbt` on disk looked like immediately after this process last committed it, and
     * which revision that content matches. Used to skip the out-of-band-edit check (see
     * [StructureCommit.commit]): if the file is byte-identical in [size] and [modifiedMillis] to
     * what we wrote, and the newest revision is still the same one, then disk *is* that revision
     * and re-reading and normalizing the revision blob to prove it is pure waste.
     *
     * [path] is part of the identity, not just bookkeeping: subpaths are relative to the project
     * root, and the root is swappable, so the same subpath can mean a completely different file
     * after an "Open Folder…". Including the absolute path means a stale entry can never match
     * the wrong file, which is why no cache invalidation is needed on a root swap, a rename, or
     * between tests reusing a subpath under different temp directories.
     */
    data class DiskFingerprint(
        val path: String,
        val revisionFile: String,
        val size: Long,
        val modifiedMillis: Long,
    )

    private val lastCommitted =
        java.util.WeakHashMap<MinecraftServer, ConcurrentHashMap<String, DiskFingerprint>>()

    @Synchronized
    fun fingerprintMap(server: MinecraftServer): ConcurrentHashMap<String, DiskFingerprint> =
        lastCommitted.getOrPut(server) { ConcurrentHashMap() }

    /** Current on-disk identity of [file], or null if it cannot be stat'd. */
    fun fingerprint(file: Path, revisionFile: String): DiskFingerprint? = runCatching {
        val attrs = java.nio.file.Files.readAttributes(
            file, java.nio.file.attribute.BasicFileAttributes::class.java,
        )
        DiskFingerprint(
            file.toAbsolutePath().toString(), revisionFile, attrs.size(), attrs.lastModifiedTime().toMillis(),
        )
    }.getOrNull()

    /**
     * Drop [subpath]'s failure-backoff entry. Called internally on a no-op or successful commit;
     * also public as a test/administrative seam for forgetting a failure without a real commit
     * (e.g. a test that deliberately leaves a structure dirty must also forget any backoff it
     * triggered, or a later, unrelated test sharing the same server would inherit it).
     */
    fun clearBackoff(server: MinecraftServer, subpath: String) {
        backoffMap(server).remove(subpath)
    }

    /**
     * Records a failed commit attempt so [StructureCommit.tick] backs off retrying [subpath] for
     * [FAILURE_BACKOFF_TICKS], and logs once per backoff window rather than once per attempt (an
     * explicit [StructureCommit.commit] call bypasses [StructureCommit.tick]'s skip and can retry
     * sooner; this keeps repeated explicit retries from spamming the log too).
     */
    fun onCommitFailure(server: MinecraftServer, subpath: String, now: Long, message: String) {
        val previous = backoffMap(server).put(subpath, now + FAILURE_BACKOFF_TICKS)
        if (previous == null || previous <= now) {
            LOGGER.error("[StructureCommit] {}", message)
        }
    }

    /**
     * Drop this server's failure-backoff and last-committed-fingerprint bookkeeping. Pair with
     * [StructureAutoSave.dispose].
     */
    @Synchronized
    fun dispose(server: MinecraftServer) {
        retryAfter.remove(server)
        lastCommitted.remove(server)
    }
}
