package com.breadmoirai.garnet.editor.network

import net.minecraft.resources.Identifier

/**
 * Mints the `garnet:project_<p>` [Identifier] every editor payload type is registered under,
 * shared by all four per-sub-feature packet files (`explorer`, `structure`, `history`, `undo`).
 *
 * **The strings this produces are the wire protocol.** Changing the namespace, the `project_`
 * prefix, or any argument passed to it renames a packet type, which desyncs a client from a
 * server that has not been updated in lockstep. Treat every produced id as frozen.
 */
fun payloadId(p: String): Identifier = Identifier.fromNamespaceAndPath("garnet", "project_$p")
