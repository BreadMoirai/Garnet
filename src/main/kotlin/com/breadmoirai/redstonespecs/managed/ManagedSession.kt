package com.breadmoirai.redstonespecs.managed

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-player UI focus. The player's currently "active" folder for save / new-spec actions.
 * The actual loaded-folder state lives in [ManagedWorld].
 */
data class ManagedSession(
    val playerId: UUID,
    val activeSubpath: String?,   // null means "no folder selected"
) {
    companion object {
        private val sessions = ConcurrentHashMap<UUID, ManagedSession>()

        fun get(playerId: UUID): ManagedSession? = sessions[playerId]
        fun set(session: ManagedSession) { sessions[session.playerId] = session }
        fun setActive(playerId: UUID, subpath: String?) {
            sessions[playerId] = ManagedSession(playerId, subpath)
        }
        fun clear(playerId: UUID) { sessions.remove(playerId) }
        fun all(): Collection<ManagedSession> = sessions.values
    }
}
