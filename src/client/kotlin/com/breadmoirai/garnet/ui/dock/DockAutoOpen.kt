package com.breadmoirai.garnet.ui.dock

import com.breadmoirai.garnet.config.DockLayoutStore
import com.breadmoirai.garnet.editor.network.ListEditorTreeC2S
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking

/**
 * Seam for the "is the peer a Garnet server" probe that gates join-time auto-open.
 *
 * Extracted so unit tests can drive both branches without a server, exactly as
 * `ExplorerSessionGate` does for the singleplayer check.
 *
 * The default asks whether the editor-tree payload can be sent, which is Fabric's standard way of
 * asking whether the other side registered a receiver — i.e. whether it runs Garnet. The payload
 * type is an implementation detail of this probe; the dock shell has no other notion of the Explorer.
 */
object DockAutoOpenGate {
    var isGarnetServer: () -> Boolean = { ClientPlayNetworking.canSend(ListEditorTreeC2S.TYPE) }

    fun resetForTest() {
        isGarnetServer = { ClientPlayNetworking.canSend(ListEditorTreeC2S.TYPE) }
    }
}

/**
 * Applies the remembered open-panel map on world join. Returns `true` when the dock actually
 * opened, so the caller can skip [com.breadmoirai.garnet.ui.viewport.syncDockViewport] and the
 * framebuffer resize when nothing changed.
 *
 * Kept free of `Minecraft` for the same reason [DockState.closeAll] is: the decision is plain state
 * plus one JSON read, and that is worth being able to unit-test without a render context. The two
 * Minecraft-side follow-ups belong to the event handler in `viewport/DockKeybinds.kt`.
 *
 * Focus is deliberately NOT taken. The dock appears, the game keeps keyboard/pointer input and the
 * cursor stays grabbed; Alt+1 remains the only way to hand focus to the panel.
 */
fun applyDockAutoOpen(): Boolean {
    // Ask the gate before the store: on a vanilla server the answer is "no" regardless of what was
    // remembered, and there is no reason to touch the filesystem to find that out.
    if (!DockAutoOpenGate.isGarnetServer()) return false
    val stored = DockLayoutStore.load()
    if (stored.isEmpty()) return false
    // Nothing to do when every stored panel is already the open one — the caller skips the
    // framebuffer churn on that answer.
    if (stored.all { (region, id) -> DockState.openPanelId(region) == id }) return false
    DockState.applyOpenMap(stored)
    return DockState.anyActive()
}
