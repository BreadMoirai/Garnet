package com.breadmoirai.garnet.editor.explorer.ui

import com.breadmoirai.garnet.core.config.SharedSettings
import com.breadmoirai.garnet.editor.network.ListEditorTreeC2S
import com.breadmoirai.garnet.editor.history.ui.LocalHistoryState
import com.breadmoirai.garnet.editor.structure.ui.OpenStructureState
import com.breadmoirai.garnet.editor.structure.ui.StructureInfoState
import com.breadmoirai.garnet.editor.undo.ui.UndoState
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.minecraft.client.Minecraft

/**
 * Seam for the singleplayer check that gates Explorer session persistence.
 *
 * Extracted so clientTests can drive both branches: the gate is what stops a remote-server session
 * from overwriting the local project's record, and it is not something to leave untested.
 */
object ExplorerSessionGate {
    var isSingleplayer: () -> Boolean = { Minecraft.getInstance().hasSingleplayerServer() }

    fun resetForTest() {
        isSingleplayer = { Minecraft.getInstance().hasSingleplayerServer() }
    }
}

/**
 * Arm last session's restore — singleplayer only.
 *
 * `SharedSettings.projectRootPath` is client-local and is never updated from a remote server, so on
 * a remote session the saved record's root would always compare equal to it and a foreign tree's
 * expansion would be restored (and later saved) under the local project's key.
 */
fun armRestoreIfSingleplayer() {
    if (!ExplorerSessionGate.isSingleplayer()) return
    ExplorerTreeState.armRestore(ExplorerStateStore.load())
}

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
            // singleplayer join the reply can land in the very next tick. See
            // ExplorerSessionGate/armRestoreIfSingleplayer for why this is gated.
            armRestoreIfSingleplayer()
            // A vanilla server without the mod has no receiver registered for this payload, and
            // sending it anyway throws. canSend is the standard Fabric guard. Deliberately
            // UNgated by singleplayer: a remote Garnet server must still populate the tree — only
            // the local persisted record is protected.
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
            ExplorerTreeSnapshot.reset()
            StructureInfoState.reset()
            ExplorerTreeState.reset()
            UndoState.reset()
            OpenStructureState.reset()
            LocalHistoryState.reset()
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
 * Skipped outside singleplayer: see the matching guard on the JOIN handler above for why
 * `SharedSettings.projectRootPath` cannot be trusted as this session's key on a remote server.
 * Without this guard, disconnecting from someone else's Garnet server would write *their* tree's
 * expansion/selection into the local record keyed by *this* client's own configured root,
 * corrupting the singleplayer record the next local join restores from.
 *
 * Also skipped when no snapshot was ever loaded this session: the tree state is empty because the
 * player never saw a tree, and writing it would overwrite a good record with nothing. That is
 * exactly the case when the player joins a vanilla server, or quits before the snapshot arrives.
 */
fun saveExplorerSession() {
    if (!ExplorerSessionGate.isSingleplayer()) return
    val root = SharedSettings.projectRootPath
    if (root.isBlank()) return
    if (ExplorerTreeSnapshot.snapshot == null) return
    ExplorerStateStore.save(root, ExplorerTreeState.expandedPaths, ExplorerTreeState.selectedPath)
}
