package com.breadmoirai.garnet.editor.explorer.data

import net.minecraft.server.level.ServerPlayer
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-player UI focus. The player's currently "active" folder for save / new-spec actions.
 * The actual loaded-folder state lives in `EditorWorld`.
 */
data class EditorSession(
    val playerId: UUID,
    val activeSubpath: String?,   // null means "no folder selected"
) {
    companion object {
        private val sessions = ConcurrentHashMap<UUID, EditorSession>()

        fun get(playerId: UUID): EditorSession? = sessions[playerId]
        fun set(session: EditorSession) { sessions[session.playerId] = session }
        fun setActive(playerId: UUID, subpath: String?) {
            sessions[playerId] = EditorSession(playerId, subpath)
        }
        fun clear(playerId: UUID) { sessions.remove(playerId) }
        fun all(): Collection<EditorSession> = sessions.values

        /**
         * Keep a loaded project reachable after one of its ancestors is renamed: an activeSubpath
         * equal to [oldSubpath], or nested under it, is rewritten onto [newSubpath].
         */
        /**
         * Drop an active folder that no longer exists: an activeSubpath equal to [deletedSubpath],
         * or nested under it, becomes null. The delete counterpart to [repointSession], which has a
         * new path to rewrite onto where a delete has none.
         *
         * The boundary is a full path segment, matching [repointSession] and
         * `EditorDimRegistry.rekeyForRename`: deleting "redstone" must clear a session in
         * "redstone/clocks" but never one in the sibling "redstoneworks".
         */
        fun clearSessionUnder(playerId: UUID, deletedSubpath: String) {
            val active = get(playerId)?.activeSubpath ?: return
            if (active == deletedSubpath || active.startsWith("$deletedSubpath/")) {
                setActive(playerId, null)
            }
        }

        fun repointSession(player: ServerPlayer, oldSubpath: String, newSubpath: String) {
            val active = get(player.uuid)?.activeSubpath ?: return
            when {
                active == oldSubpath -> setActive(player.uuid, newSubpath)
                active.startsWith("$oldSubpath/") ->
                    setActive(player.uuid, newSubpath + active.removePrefix(oldSubpath))
            }
        }
    }
}
