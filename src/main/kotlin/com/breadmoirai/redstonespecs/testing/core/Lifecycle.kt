package com.breadmoirai.redstonespecs.testing.core

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents

/**
 * Idempotent registration of Fabric event subscriptions for the testing module.
 *
 * Must be invoked from a mod-init context (the testing module does not register itself
 * automatically — it has no fabric.mod.json entrypoint). Each test source set's
 * sentinel arranges for this to be called exactly once per server lifecycle.
 */
object RedstoneTestLifecycle {
    @Volatile private var registered = false

    fun register() {
        if (registered) return
        registered = true

        ServerLifecycleEvents.SERVER_STARTED.register { server ->
            McDispatchers.install(server)
        }
        ServerLifecycleEvents.SERVER_STOPPED.register { _ ->
            McDispatchers.uninstall()
        }
        ServerTickEvents.START_SERVER_TICK.register { server ->
            emitServerTickStart(server)
        }
        ServerTickEvents.END_SERVER_TICK.register { server ->
            emitServerTickEnd(server)
        }
    }

    /**
     * Variant for GameTest sentinels: registers tick events and installs the dispatcher
     * directly from the provided server. Use this when [register] is called after
     * SERVER_STARTED has already fired (which is always the case inside a @GameTest method).
     *
     * Calling this after [register] is a no-op for the event registrations (idempotent),
     * but [McDispatchers.install] is always called to ensure the dispatcher is available.
     */
    fun registerWithServer(server: net.minecraft.server.MinecraftServer) {
        register()
        McDispatchers.install(server)
    }
}
