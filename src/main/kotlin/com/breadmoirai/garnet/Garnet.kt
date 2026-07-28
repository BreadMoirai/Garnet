package com.breadmoirai.garnet

import com.breadmoirai.garnet.block.SpecBlockEntity
import com.breadmoirai.garnet.config.SharedSettings
import com.breadmoirai.garnet.event.SubTickPhaseEvents
import com.breadmoirai.garnet.item.SpecMarkerTool
import com.breadmoirai.garnet.item.UndoStack
import com.breadmoirai.garnet.project.ProjectCommand
import com.breadmoirai.garnet.project.ProjectDimLifecycle
import com.breadmoirai.garnet.project.ProjectRoot
import com.breadmoirai.garnet.project.ProjectServerContext
import com.breadmoirai.garnet.network.registerNetworking
import com.breadmoirai.garnet.testing.core.GarnetTestLifecycle
import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import com.breadmoirai.garnet.project.ProjectSession
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.event.player.AttackBlockCallback
import net.fabricmc.fabric.api.event.player.UseBlockCallback
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.minecraft.world.InteractionResult
import net.minecraft.world.item.context.UseOnContext
import org.slf4j.LoggerFactory
import java.nio.file.Path

private val LOGGER = LoggerFactory.getLogger("Garnet")

class Garnet : ModInitializer {

    override fun onInitialize() {
        LOGGER.debug("[Garnet#onInitialize] initializing mod")
        ModRegistries.register()
        registerNetworking()
        GarnetTestLifecycle.register()
        registerAttackCallback()
        registerUseBlockCallback()
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
            com.breadmoirai.garnet.network.project.ProjectNetworkRegistry.flushDirtyStructures(server)
        }
        CommandRegistrationCallback.EVENT.register { dispatcher, _, _ ->
            ProjectCommand.register(dispatcher)
        }
        ServerPlayConnectionEvents.DISCONNECT.register { handler, _ ->
            ProjectSession.clear(handler.player.uuid)
        }
        LOGGER.debug("[Garnet#onInitialize] initialization complete")
    }

    private fun registerUseBlockCallback() {
        UseBlockCallback.EVENT.register { player, world, hand, hitResult ->
            val stack = player.getItemInHand(hand)
            if (stack.item !is SpecMarkerTool) return@register InteractionResult.PASS
            // Prevent block interactions (e.g. lever/button toggle) when holding a marker item.
            // Manually dispatch item interaction since returning non-PASS skips ServerPlayerGameMode.useItemOn.
            if (!world.isClientSide) {
                stack.useOn(UseOnContext(world, player, hand, stack, hitResult))
            }
            InteractionResult.SUCCESS
        }
    }

    private fun registerAttackCallback() {
        AttackBlockCallback.EVENT.register { player, world, hand, pos, _ ->
            val item = player.getItemInHand(hand).item
            if (item !is SpecMarkerTool) return@register InteractionResult.PASS
            if (world.isClientSide) return@register InteractionResult.SUCCESS

            val be = SpecBlockEntity.findFor(world, pos) ?: return@register InteractionResult.PASS
            val relPos = pos.subtract(be.blockPos)
            val removed = be.removeMarker(relPos)
            if (removed != null) {
                LOGGER.debug("[Garnet#attackCallback] removed marker at {}", relPos)
                UndoStack.push(player.uuid, UndoStack.UndoRecord(be.blockPos, removed))
            }

            InteractionResult.SUCCESS
        }
    }
}
