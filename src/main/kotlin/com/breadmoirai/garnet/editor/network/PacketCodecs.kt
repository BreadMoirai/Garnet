package com.breadmoirai.garnet.editor.network

import net.minecraft.resources.Identifier

/** Shared payload-id helper for every editor packet, across all sub-feature network files. */
fun id(p: String) = Identifier.fromNamespaceAndPath("garnet", "project_$p")
