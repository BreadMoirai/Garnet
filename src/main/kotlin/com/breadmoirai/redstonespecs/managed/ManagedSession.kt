package com.breadmoirai.redstonespecs.managed

import com.breadmoirai.redstonespecs.data.RedstoneSpec
import net.minecraft.core.BlockPos
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate
import java.nio.file.Path
import java.util.UUID

/**
 * Per-spec state inside a loaded folder. `cell.origin` is *relative to (0,0,0)* — the absolute
 * world position is `cell.origin + regionOrigin` (regionOrigin is owned by ManagedDimRegistry).
 * `loadedSnapshot` is the blocks in the cell volume *immediately after placement*, used as the
 * baseline for dirty-diff at save time.
 */
data class LoadedSpec(
    val cell: ManagedCell,
    val spec: RedstoneSpec,
    val sourceFile: Path,
    val loadedSnapshot: StructureTemplate,
)

/**
 * Per-player loaded-folder state. Single-player has at most one entry; dedicated server has one
 * per connected player who's opened a folder. Ephemeral — not persisted to disk.
 */
class ManagedSession(
    val playerId: UUID,
    val root: ManagedRoot,
    val subpath: String,
    val folderAbsolute: Path,
    val regionOrigin: BlockPos,           // assigned by ManagedDimRegistry
    val loaded: MutableMap<String, LoadedSpec>, // by spec id
) {
    /** Absolute world origin for a given spec's cell. */
    fun absoluteCellOrigin(specId: String): BlockPos? =
        loaded[specId]?.cell?.origin?.let { rel ->
            BlockPos(regionOrigin.x + rel.x, regionOrigin.y + rel.y, regionOrigin.z + rel.z)
        }

    fun cellByOrigin(): Map<BlockPos, String> =
        loaded.values.associate { it.cell.origin to it.cell.specId }

    companion object {
        private val sessions = java.util.concurrent.ConcurrentHashMap<UUID, ManagedSession>()

        fun get(playerId: UUID): ManagedSession? = sessions[playerId]
        fun set(session: ManagedSession) { sessions[session.playerId] = session }
        fun clear(playerId: UUID) { sessions.remove(playerId) }
        fun all(): Collection<ManagedSession> = sessions.values
    }
}
