package com.breadmoirai.garnet.editor.data

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-player UI focus. The player's currently "active" folder for save / new-spec actions.
 * The actual loaded-folder state lives in [EditorWorld].
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
    }
}
