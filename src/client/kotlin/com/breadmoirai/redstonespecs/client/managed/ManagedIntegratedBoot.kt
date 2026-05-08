package com.breadmoirai.redstonespecs.client.managed

import com.breadmoirai.redstonespecs.managed.ManagedDatapackWriter
import com.breadmoirai.redstonespecs.managed.ManagedRoot
import com.breadmoirai.redstonespecs.managed.ManagedServerContext
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.minecraft.server.MinecraftServer
import net.minecraft.world.level.storage.LevelResource
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean

private val LOGGER = LoggerFactory.getLogger("Redstone Specs")

object ManagedIntegratedBoot {
    /**
     * Pins the next-started server to use `rootPath` as its managed root. The user is
     * expected to open any singleplayer world after this; once they do, the integrated
     * server starts, the listener fires once, the context is set, and `/redstonespecs managed`
     * works as expected.
     *
     * Fabric's `Event<T>` has no `unregister` (verified against fabric-api-base Event.java),
     * so we guard the listener with an `AtomicBoolean` to ensure it only takes effect once.
     * Stale listeners from prior `boot` calls become inert after the next server start.
     */
    fun boot(rootPath: Path) {
        require(rootPath.isAbsolute) { "rootPath must be absolute: $rootPath" }
        val root = ManagedRoot(rootPath)
        val pendingContext = ManagedServerContext(root)
        val fired = AtomicBoolean(false)

        ServerLifecycleEvents.SERVER_STARTING.register(ServerLifecycleEvents.ServerStarting { server: MinecraftServer ->
            if (!fired.compareAndSet(false, true)) return@ServerStarting
            ManagedServerContext.set(server, pendingContext)
            LOGGER.info("[ManagedIntegratedBoot] pinned root '{}' to integrated server", rootPath)
            // Write per-folder dim datapack into the active save. Caveat: levels are already
            // being constructed by the time this fires, so the per-folder dims will not be live
            // until the *next* server start of this same save. The fallback single-dim handles
            // the current session.
            try {
                val saveDir = server.getWorldPath(LevelResource.ROOT)
                ManagedDatapackWriter.writeForRoot(root, saveDir)
                LOGGER.info("[ManagedIntegratedBoot] wrote per-folder dim datapack into {}", saveDir)
            } catch (e: Exception) {
                LOGGER.error("[ManagedIntegratedBoot] datapack write failed: {}", e.message, e)
            }
        })
        LOGGER.info("[ManagedIntegratedBoot] '{}' pinned for next server start", rootPath)
    }
}
