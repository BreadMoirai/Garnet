package com.breadmoirai.redstonespecs.client.ide

import org.lwjgl.util.tinyfd.TinyFileDialogs

/** Selects a folder from the OS. [pick] blocks — never call it on the render thread. */
fun interface FolderPicker {
    fun pick(title: String, default: String?): String?
}

/** Default impl backed by LWJGL tinyfd (bundled with MC). Returns null on cancel. */
object TinyfdFolderPicker : FolderPicker {
    override fun pick(title: String, default: String?): String? =
        TinyFileDialogs.tinyfd_selectFolderDialog(title, default ?: "")
}
