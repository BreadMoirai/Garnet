package com.breadmoirai.garnet

import com.breadmoirai.garnet.config.SharedSettings
import com.breadmoirai.garnet.event.SubTickPhaseEvents
import com.breadmoirai.garnet.network.project.ProjectNetworkRegistry
import com.breadmoirai.garnet.project.ProjectCommand
import com.breadmoirai.garnet.project.ProjectDimLifecycle
import com.breadmoirai.garnet.project.ProjectRoot
import com.breadmoirai.garnet.project.ProjectServerContext
import com.breadmoirai.garnet.testing.core.GarnetTestLifecycle
import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import com.breadmoirai.garnet.project.ProjectSession
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import org.slf4j.LoggerFactory
import java.nio.file.Path

private val LOGGER = LoggerFactory.getLogger("Garnet")

class Garnet : ModInitializer {

    override fun onInitialize() {
        LOGGER.debug("[Garnet#onInitialize] initializing mod")
        ProjectNetworkRegistry.register()
        GarnetTestLifecycle.register()
        SubTickPhaseEvents.PHASE.register { level, phase ->
            com.breadmoirai.garnet.runner.StateRecorder.onPhaseForActiveRecorders(level, phase)
        }
        ServerLifecycleEvents.SERVER_STARTING.register { server ->
            val cfg = SharedSettings.projectRootPath
            if (cfg.isNotBlank() && ProjectServerContext.get(server) == null) {
                val rootPath = Path.of(cfg).toAbsolutePath()
                val root = ProjectRoot(rootPath)
                ProjectServerContext.set(server, ProjectServerContext(root))
            }
        }
        ServerLifecycleEvents.SERVER_STARTED.register { server ->
            // Dedicated-server / pre-pinned path: if a managed context was pinned (by config or
            // by ProjectIntegratedBoot's own SERVER_STARTING listener), materialize specs once
            // the server is ready. Integrated boot has its own one-shot listener, but it is a
            // no-op here because that listener also pins the same root and runs placeAll the
            // same way; this listener is the dedicated-server entry point.
            val ctx = ProjectServerContext.get(server) ?: return@register
            ProjectDimLifecycle.placeAll(server, ctx.root)
        }
        ServerLifecycleEvents.SERVER_STOPPED.register { server ->
            ProjectDimLifecycle.releaseServerState(server)
        }
        ServerLifecycleEvents.BEFORE_SAVE.register { server, _, _ ->
            ProjectNetworkRegistry.flushDirtyStructures(server)
        }
        CommandRegistrationCallback.EVENT.register { dispatcher, _, _ ->
            ProjectCommand.register(dispatcher)
        }
        ServerPlayConnectionEvents.DISCONNECT.register { handler, _ ->
            ProjectSession.clear(handler.player.uuid)
        }
        LOGGER.debug("[Garnet#onInitialize] initialization complete")
    }
}
