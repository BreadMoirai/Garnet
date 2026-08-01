package com.breadmoirai.garnet

import com.breadmoirai.garnet.config.SharedSettings
import com.breadmoirai.garnet.mc.SubTickPhaseEvents
import com.breadmoirai.garnet.editor.network.EditorNetworking
import com.breadmoirai.garnet.editor.command.EditorCommand
import com.breadmoirai.garnet.editor.world.EditorDimLifecycle
import com.breadmoirai.garnet.editor.data.EditorRoot
import com.breadmoirai.garnet.editor.world.EditorServerContext
import com.breadmoirai.garnet.editor.world.StructureAutoSave
import com.breadmoirai.garnet.editor.world.StructureCommit
import com.breadmoirai.garnet.history.LocalHistoryStore
import com.breadmoirai.garnet.mc.McLifecycle
import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import com.breadmoirai.garnet.editor.data.EditorSession
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import org.slf4j.LoggerFactory
import java.nio.file.Path

private val LOGGER = LoggerFactory.getLogger("Garnet")

class Garnet : ModInitializer {

    override fun onInitialize() {
        LOGGER.debug("[Garnet#onInitialize] initializing mod")
        EditorNetworking.register()
        McLifecycle.register()
        SubTickPhaseEvents.PHASE.register { level, phase ->
            com.breadmoirai.garnet.playback.recorder.StateRecorder.onPhaseForActiveRecorders(level, phase)
        }
        ServerLifecycleEvents.SERVER_STARTING.register { server ->
            val cfg = SharedSettings.projectRootPath
            if (cfg.isNotBlank() && EditorServerContext.get(server) == null) {
                val rootPath = Path.of(cfg).toAbsolutePath()
                val root = EditorRoot(rootPath)
                EditorServerContext.set(server, EditorServerContext(root))
            }
        }
        ServerLifecycleEvents.SERVER_STARTED.register { server ->
            // Dedicated-server / pre-pinned path: if a managed context was pinned (by config or
            // by EditorIntegratedBoot's own SERVER_STARTING listener), materialize specs once
            // the server is ready. Integrated boot has its own one-shot listener, but it is a
            // no-op here because that listener also pins the same root and runs placeAll the
            // same way; this listener is the dedicated-server entry point.
            val ctx = EditorServerContext.get(server) ?: return@register
            EditorDimLifecycle.placeAll(server, ctx.root)
        }
        ServerLifecycleEvents.SERVER_STOPPED.register { server ->
            StructureCommit.commitAll(server, LocalHistoryStore.REASON_AUTOSAVE)
            StructureAutoSave.dispose(server)
            StructureCommit.dispose(server)
            EditorDimLifecycle.releaseServerState(server)
        }
        ServerTickEvents.END_SERVER_TICK.register { server ->
            StructureCommit.tick(server)
        }
        ServerLifecycleEvents.BEFORE_SAVE.register { server, _, _ ->
            StructureCommit.commitAll(server, LocalHistoryStore.REASON_AUTOSAVE)
            // Transitional backstop (fix round 1 / Finding 4): StructureCommit only ever commits
            // subpaths the setBlock-mixin watcher marked dirty. The mixin only injects into the
            // 3-arg Level.setBlock, so a 4-arg setBlock or a direct LevelChunk.setBlockState write
            // is invisible to it -- never marked dirty, and silently excluded from the next commit's
            // tight scan box. flushDirtyStructures is watcher-independent (it re-captures each
            // placed structure's whole region and compares by content), so it still catches those.
            // Remove this call in Task 7, once the .nbt.unsaved sidecar itself is retired.
            EditorNetworking.flushDirtyStructures(server)
        }
        CommandRegistrationCallback.EVENT.register { dispatcher, _, _ ->
            EditorCommand.register(dispatcher)
        }
        ServerPlayConnectionEvents.DISCONNECT.register { handler, _ ->
            EditorSession.clear(handler.player.uuid)
        }
        LOGGER.debug("[Garnet#onInitialize] initialization complete")
    }
}
