package com.breadmoirai.garnet.editor.explorer.ui

import org.lwjgl.system.MemoryStack
import org.lwjgl.util.nfd.NativeFileDialog
import org.lwjgl.util.nfd.NativeFileDialog.NFD_OKAY

/** Selects a folder from the OS. [pick] blocks — see `RootPickerController.runner` for where. */
fun interface FolderPicker {
    fun pick(title: String, default: String?): String?
}

/**
 * Default impl, backed by LWJGL's NativeFileDialog: the real `IFileDialog` / `NSOpenPanel` / GTK
 * folder chooser rather than tinyfd's legacy Win32 folder browser. Returns null on cancel or error.
 *
 * `NFD_Init`, `NFD_PickFolder` and `NFD_Quit` all run inside this one call, on one thread, on
 * purpose: on Windows NFD's init performs the COM `CoInitializeEx` on the **calling** thread, so
 * splitting the calls across threads leaves the dialog thread without an initialised apartment and
 * the dialog fails to open.
 *
 * [title] is ignored: NFD's pick-folder API exposes only `defaultPath` and `parentWindow` — there
 * is no title field, and the OS supplies its own. The parameter stays for [FolderPicker]'s shape.
 */
object NfdFolderPicker : FolderPicker {
    override fun pick(title: String, default: String?): String? {
        // This early return skips the try/finally below, so NFD_Quit() never runs when init
        // fails — that is intentional, not a missed cleanup: NFD_Quit() pairs with a *successful*
        // NFD_Init(), and calling it after a failed init is undefined per NFD's own contract. Do
        // not "fix" this into an unconditional finally.
        if (NativeFileDialog.NFD_Init() != NFD_OKAY) return null
        try {
            MemoryStack.stackPush().use { stack ->
                val out = stack.mallocPointer(1)
                if (NativeFileDialog.NFD_PickFolder(out, default) != NFD_OKAY) return null
                val path = out.getStringUTF8(0)
                // NFD allocates the result natively; the binding does not free it for us.
                NativeFileDialog.NFD_FreePath(out.get(0))
                return path
            }
        } finally {
            NativeFileDialog.NFD_Quit()
        }
    }
}
