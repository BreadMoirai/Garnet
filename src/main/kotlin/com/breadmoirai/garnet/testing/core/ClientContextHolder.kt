package com.breadmoirai.garnet.testing.core

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext

/**
 * Holds the active `ClientGameTestContext` for client-source-set Kotest specs to consume.
 *
 * Set by `ClientTestSentinel.runTest` before launching Kotest; cleared after.
 */
@Suppress("UnstableApiUsage")
object ClientContextHolder {
    @Volatile private var _context: ClientGameTestContext? = null

    val context: ClientGameTestContext
        get() = _context ?: error("ClientContextHolder accessed outside an active client gametest")

    fun install(ctx: ClientGameTestContext) {
        _context = ctx
    }

    fun clear() {
        _context = null
    }
}
