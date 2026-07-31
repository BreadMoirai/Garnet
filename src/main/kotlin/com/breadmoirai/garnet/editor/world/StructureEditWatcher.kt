package com.breadmoirai.garnet.editor.world

import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel

/**
 * The bridge from a successful world block change to auto-save bookkeeping.
 *
 * Called from `ServerLevelSetBlockMixin` on every successful server-side `setBlock`, so it must stay
 * cheap: two map lookups and a handful of comparisons on the common "not in any structure region"
 * path. Deliberately `@JvmStatic`-friendly (an `object` with a plain function) so the Java mixin can
 * call it without Kotlin-specific plumbing.
 */
object StructureEditWatcher {

    @JvmStatic
    fun onBlockChanged(level: ServerLevel, pos: BlockPos) {
        val server = level.server
        val registry = EditorDimRegistry.of(server)
        // Structure regions live in the project level only; edits anywhere else are irrelevant.
        if (level !== registry.projectLevel()) return
        val subpath = registry.structureSubpathAt(pos) ?: return
        StructureAutoSave.of(server).onEdit(subpath, pos, level.gameTime)
    }
}
