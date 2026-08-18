package com.breadmoirai.garnet.editor.explorer.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.breadmoirai.garnet.core.config.ModConfig
import com.breadmoirai.garnet.core.config.SharedSettings
import com.breadmoirai.garnet.editor.explorer.network.SetEditorRootC2S
import java.nio.file.Path
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.minecraft.client.Minecraft
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import org.lwjgl.system.Platform

/**
 * Compose-observable state + action controller for the Explorer's root-picker header.
 * Sibling of [ExplorerTreeSnapshot]; packet handlers never touch this UI state.
 *
 * External effects are seams (swapped in tests): [picker] (native dialog), [runner]
 * (background thread — the dialog blocks), [executor] (marshal back to the client thread),
 * [sender] (network), [persist] (disk).
 */
object RootPickerController {
    var picker: FolderPicker = NfdFolderPicker
    var runner: (Runnable) -> Unit = defaultRunner()
    var executor: (Runnable) -> Unit = { Minecraft.getInstance().execute(it) }
    var sender: (CustomPacketPayload) -> Unit = { ClientPlayNetworking.send(it) }
    var persist: (String) -> Unit = { path -> ModConfig.projectRootPath = path; ModConfig.save() }

    var picking by mutableStateOf(false)
        private set

    /** Open the native folder picker; on a non-null result, persist it and send [SetEditorRootC2S]. */
    fun openFolder() {
        if (picking) return
        picking = true
        // Seed the dialog at the current project root so Open Folder starts where the player is
        // working. Blank (no root configured yet) passes null, which lets NFD pick its own
        // default. Read here, before dispatching to runner: the runner may be a worker thread, and
        // the read belongs on the calling (client) thread.
        val start = SharedSettings.projectRootPath.takeIf { it.isNotBlank() }
        try {
            runner {
                try {
                    val path = picker.pick("Open Project Folder", start)
                    if (path != null) {
                        // Normalize to absolute so the persisted + sent value matches the
                        // canonical form the server stores (handleSetRoot's toAbsolutePath()).
                        val abs = Path.of(path).toAbsolutePath().toString()
                        persist(abs)
                        executor {
                            // The old root's expansion/selection (and any pending restore armed
                            // for it) are meaningless against the new tree — a path like "src"
                            // that happens to exist in both projects would otherwise restore as
                            // expansion the player never made here. reset() also clears
                            // pendingRestore, which is correct: a restore armed for the old root
                            // must not later apply against the new one's snapshot.
                            ExplorerTreeState.reset()
                            sender(SetEditorRootC2S(abs))
                        }
                    }
                } finally {
                    executor { picking = false }
                }
            }
        } catch (e: Throwable) {
            // runner failed to even start the work (e.g. thread creation threw); release the
            // guard so a later click can retry rather than latching picking=true forever.
            picking = false
            throw e
        }
    }

    /** Restore default seams + flags between tests. */
    fun resetForTest() {
        picker = NfdFolderPicker
        runner = defaultRunner()
        executor = { Minecraft.getInstance().execute(it) }
        sender = { ClientPlayNetworking.send(it) }
        persist = { path -> ModConfig.projectRootPath = path; ModConfig.save() }
        picking = false
    }
}

/**
 * Where the blocking dialog runs.
 *
 * Everywhere but macOS: a worker thread, because the dialog blocks until dismissed and the caller is
 * the render thread.
 *
 * On macOS: **inline on the calling (render) thread**. NFD drives `NSOpenPanel`, which must be
 * called from the AppKit main thread — and under `-XstartOnFirstThread` that thread *is* Minecraft's
 * render thread, so the worker-thread rule and the AppKit rule cannot both hold. The game visibly
 * freezes behind the modal until it is dismissed; that is the accepted trade over an AppKit crash.
 */
private fun defaultRunner(): (Runnable) -> Unit =
    if (Platform.get() == Platform.MACOSX) Runnable::run
    else { r -> Thread(r, "garnet-folder-picker").start() }
