package com.breadmoirai.redstonespecs.project

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-player UI focus. The player's currently "active" folder for save / new-spec actions.
 * The actual loaded-folder state lives in [ProjectWorld].
 */
data class ProjectSession(
    val playerId: UUID,
    val activeSubpath: String?,   // null means "no folder selected"
) {
    companion object {
        private val sessions = ConcurrentHashMap<UUID, ProjectSession>()

        fun get(playerId: UUID): ProjectSession? = sessions[playerId]
        fun set(session: ProjectSession) { sessions[session.playerId] = session }
        fun setActive(playerId: UUID, subpath: String?) {
            sessions[playerId] = ProjectSession(playerId, subpath)
        }
        fun clear(playerId: UUID) { sessions.remove(playerId) }
        fun all(): Collection<ProjectSession> = sessions.values
    }
}
