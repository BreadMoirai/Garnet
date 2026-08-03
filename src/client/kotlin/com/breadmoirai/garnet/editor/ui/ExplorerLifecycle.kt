package com.breadmoirai.garnet.editor.ui

import com.breadmoirai.garnet.config.ExplorerStateStore
import com.breadmoirai.garnet.config.SharedSettings
import com.breadmoirai.garnet.editor.network.ListEditorTreeC2S
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking

/**
 * Explorer session lifecycle: request the tree and restore last session's expansion on join,
 * persist it and reset on the way out.
 *
 * Every networking-event body runs inside `mc.execute { ... }` because `fabric-networking-api-v1`
 * fires these events from two sites in `ClientConnectionMixin` — the main thread, or a **Netty
 * event-loop thread**, whichever wins the CAS.
 */
fun registerExplorerLifecycle() {
    ClientPlayConnectionEvents.JOIN.register { _, _, mc ->
        mc.execute {
            // Arm before sending: the snapshot reply is what consumes the restore, and on a
            // singleplayer join the reply can land in the very next tick.
            ExplorerTreeState.armRestore(ExplorerStateStore.load())
            // A vanilla server without the mod has no receiver registered for this payload, and
            // sending it anyway throws. canSend is the standard Fabric guard.
            if (ClientPlayNetworking.canSend(ListEditorTreeC2S.TYPE)) {
                ClientPlayNetworking.send(ListEditorTreeC2S.INSTANCE)
            }
        }
    }

    ClientPlayConnectionEvents.DISCONNECT.register { _, mc ->
        mc.execute {
            // Save BEFORE the resets below: reading afterwards would persist empty sets.
            saveExplorerSession()
            // Per-world Explorer state: the tree snapshot and its expansion/selection are stale once
            // the session that produced them ends. Reset here, not in DockState.closeAll(), which
            // stays free of IDE-state and Minecraft dependencies.
            ProjectTreeState.reset()
            ExplorerTreeState.reset()
        }
    }

    // DISCONNECT covers quit-to-title, a multiplayer disconnect and a kick. It does not reliably
    // cover closing the game window from inside a world, which is the common way a player ends a
    // session — hence this second, idempotent save.
    ClientLifecycleEvents.CLIENT_STOPPING.register { _ ->
        saveExplorerSession()
    }
}

/**
 * Persist the live tree state against the configured root.
 *
 * Skipped when no snapshot was ever loaded this session: the tree state is empty because the player
 * never saw a tree, and writing it would overwrite a good record with nothing. That is exactly the
 * case when the player joins a vanilla server, or quits before the snapshot arrives.
 */
private fun saveExplorerSession() {
    val root = SharedSettings.projectRootPath
    if (root.isBlank()) return
    if (ProjectTreeState.snapshot == null) return
    ExplorerStateStore.save(root, ExplorerTreeState.expandedPaths, ExplorerTreeState.selectedPath)
}
