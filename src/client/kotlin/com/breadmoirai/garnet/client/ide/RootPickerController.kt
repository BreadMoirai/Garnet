package com.breadmoirai.garnet.client.ide

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.breadmoirai.garnet.config.ModConfig
import com.breadmoirai.garnet.network.project.SetProjectRootC2S
import java.nio.file.Path
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.minecraft.client.Minecraft
import net.minecraft.network.protocol.common.custom.CustomPacketPayload

/**
 * Compose-observable state + action controller for the Explorer's root-picker header.
 * Sibling of [ProjectTreeState]; packet handlers never touch this UI state.
 *
 * External effects are seams (swapped in tests): [picker] (native dialog), [runner]
 * (background thread — the dialog blocks), [executor] (marshal back to the client thread),
 * [sender] (network), [persist] (disk).
 */
object RootPickerController {
    var picker: FolderPicker = TinyfdFolderPicker
    var runner: (Runnable) -> Unit = { Thread(it, "garnet-folder-picker").start() }
    var executor: (Runnable) -> Unit = { Minecraft.getInstance().execute(it) }
    var sender: (CustomPacketPayload) -> Unit = { ClientPlayNetworking.send(it) }
    var persist: (String) -> Unit = { path -> ModConfig.projectRootPath = path; ModConfig.save() }

    var picking by mutableStateOf(false)
        private set

    /** Open the native folder picker; on a non-null result, persist it and send [SetProjectRootC2S]. */
    fun openFolder() {
        if (picking) return
        picking = true
        try {
            runner {
                try {
                    val path = picker.pick("Open Project Folder", null)
                    if (path != null) {
                        // Normalize to absolute so the persisted + sent value matches the
                        // canonical form the server stores (handleSetRoot's toAbsolutePath()).
                        val abs = Path.of(path).toAbsolutePath().toString()
                        persist(abs)
                        executor { sender(SetProjectRootC2S(abs)) }
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
        picker = TinyfdFolderPicker
        runner = { Thread(it, "garnet-folder-picker").start() }
        executor = { Minecraft.getInstance().execute(it) }
        sender = { ClientPlayNetworking.send(it) }
        persist = { path -> ModConfig.projectRootPath = path; ModConfig.save() }
        picking = false
    }
}
