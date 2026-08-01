package com.breadmoirai.garnet.editor.world

import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel

/**
 * The bridge from a successful world block change to auto-save bookkeeping.
 *
 * Called from `ServerLevelSetBlockMixin` on every successful server-side `setBlock` — the hottest
 * write path in the game — so the common "not a structure edit" case must reject as cheaply as
 * possible. The two checks below are ordered so nothing that costs a lock or a map lookup runs
 * until a call has survived both: [EditorDimRegistry.isInStructureLaneZ] is a couple of integer
 * comparisons with no server/registry involved at all, and `level !== server.overworld()` is a
 * plain reference compare — neither touches [EditorDimRegistry.of], which is `@Synchronized`.
 * Only a call that passes both pays for the registry lookup and the (still allocation-cheap)
 * region scan inside [EditorDimRegistry.structureSubpathAt]. Deliberately `@JvmStatic`-friendly
 * (an `object` with a plain function) so the Java mixin can call it without Kotlin-specific
 * plumbing.
 */
object StructureEditWatcher {

    @JvmStatic
    fun onBlockChanged(level: ServerLevel, pos: BlockPos) {
        if (!EditorDimRegistry.isInStructureLaneZ(pos)) return
        val server = level.server
        // Structure regions live in the project level only; edits anywhere else are irrelevant.
        // Compared directly against server.overworld() (what EditorDimRegistry.projectLevel()
        // would return) so this rejection doesn't need an EditorDimRegistry.of(server) lock.
        if (level !== server.overworld()) return
        val registry = EditorDimRegistry.of(server)
        val subpath = registry.structureSubpathAt(pos) ?: return
        StructureAutoSave.of(server).onEdit(subpath, pos, level.gameTime)
    }
}
