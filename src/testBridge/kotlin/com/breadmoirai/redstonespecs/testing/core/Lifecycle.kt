package com.breadmoirai.redstonespecs.testing.core

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents

/**
 * Idempotent registration of the bridge's Fabric event subscriptions.
 *
 * Must be invoked from a mod-init context (the bridge does not register itself
 * automatically — it has no fabric.mod.json entrypoint). Each test source set's
 * sentinel arranges for this to be called exactly once per server lifecycle.
 */
object TestBridgeLifecycle {
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
}
