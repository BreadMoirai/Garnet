package com.breadmoirai.redstonespecs.testing.core

import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext

/**
 * Holds the active `TestSingleplayerContext` (the integrated server's world) for
 * client-source-set Kotest specs to consume.
 *
 * Set by `ClientTestSentinel.runTest` after `SpecTestContext.createWorld`; cleared
 * when the surrounding `use { }` block exits.
 */
@Suppress("UnstableApiUsage")
object WorldHolder {
    @Volatile private var _world: TestSingleplayerContext? = null

    val world: TestSingleplayerContext
        get() = _world ?: error("WorldHolder accessed outside an active client gametest")

    fun install(world: TestSingleplayerContext) {
        _world = world
    }

    fun clear() {
        _world = null
    }
}
