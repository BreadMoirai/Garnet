package com.breadmoirai.garnet.editor.ui

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents

/**
 * Resets Explorer tree state when the client leaves a world. `DISCONNECT` covers every exit path
 * that matters: quit-to-title from singleplayer, a multiplayer disconnect, and a server kick.
 *
 * The whole body runs inside `mc.execute { ... }` because `fabric-networking-api-v1` fires this
 * event from two sites in `ClientConnectionMixin` — `handleDisconnection` on the main thread, or
 * `channelInactive` on a **Netty event-loop thread**, whichever wins the CAS.
 */
fun registerExplorerLifecycle() {
    ClientPlayConnectionEvents.DISCONNECT.register { _, mc ->
        mc.execute {
            // Per-world Explorer state: the tree snapshot and its expansion/selection are stale once
            // the session that produced them ends, and nothing else refreshes them on the next join
            // (the tree only reloads on an explicit user click). Reset here, not in
            // DockState.closeAll(), which stays free of IDE-state and Minecraft dependencies.
            ProjectTreeState.reset()
            ExplorerTreeState.reset()
        }
    }
}
