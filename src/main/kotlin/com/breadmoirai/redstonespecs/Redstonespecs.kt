package com.breadmoirai.redstonespecs

import com.breadmoirai.redstonespecs.block.SpecBlockEntity
import com.breadmoirai.redstonespecs.config.SharedSettings
import com.breadmoirai.redstonespecs.event.SubTickPhaseEvents
import com.breadmoirai.redstonespecs.item.SpecMarkerTool
import com.breadmoirai.redstonespecs.item.UndoStack
import com.breadmoirai.redstonespecs.managed.ManagedCommand
import com.breadmoirai.redstonespecs.managed.ManagedDimLifecycle
import com.breadmoirai.redstonespecs.managed.ManagedRoot
import com.breadmoirai.redstonespecs.managed.ManagedServerContext
import com.breadmoirai.redstonespecs.network.registerNetworking
import com.breadmoirai.redstonespecs.runner.SpecRunnerCoordinator
import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import com.breadmoirai.redstonespecs.managed.ManagedSession
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.event.player.AttackBlockCallback
import net.fabricmc.fabric.api.event.player.UseBlockCallback
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.minecraft.world.InteractionResult
import net.minecraft.world.item.context.UseOnContext
import org.slf4j.LoggerFactory
import java.nio.file.Path

private val LOGGER = LoggerFactory.getLogger("Redstone Specs")

class Redstonespecs : ModInitializer {

    override fun onInitialize() {
        LOGGER.debug("[Redstonespecs#onInitialize] initializing mod")
        ModRegistries.register()
        registerNetworking()
        registerAttackCallback()
        registerUseBlockCallback()
        SubTickPhaseEvents.PHASE.register { level, phase ->
            SpecRunnerCoordinator.onPhase(level, phase)
        }
        ServerLifecycleEvents.SERVER_STARTING.register { server ->
            val cfg = SharedSettings.managedRootPath
            if (cfg.isNotBlank() && ManagedServerContext.get(server) == null) {
                val rootPath = Path.of(cfg).toAbsolutePath()
                val root = ManagedRoot(rootPath)
                ManagedServerContext.set(server, ManagedServerContext(root))
            }
        }
        ServerLifecycleEvents.SERVER_STARTED.register { server ->
            // Dedicated-server / pre-pinned path: if a managed context was pinned (by config or
            // by ManagedIntegratedBoot's own SERVER_STARTING listener), materialize specs once
            // the server is ready. Integrated boot has its own one-shot listener, but it is a
            // no-op here because that listener also pins the same root and runs placeAll the
            // same way; this listener is the dedicated-server entry point.
            val ctx = ManagedServerContext.get(server) ?: return@register
            ManagedDimLifecycle.placeAll(server, ctx.root)
        }
        CommandRegistrationCallback.EVENT.register { dispatcher, _, _ ->
            ManagedCommand.register(dispatcher)
        }
        ServerPlayConnectionEvents.DISCONNECT.register { handler, _ ->
            ManagedSession.clear(handler.player.uuid)
        }
        LOGGER.debug("[Redstonespecs#onInitialize] initialization complete")
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
            val removed = be.removeEntry(relPos)
            if (removed != null) {
                LOGGER.debug("[Redstonespecs#attackCallback] removed entry at {}", relPos)
                UndoStack.push(player.uuid, UndoStack.UndoRecord(be.blockPos, removed))
            }

            InteractionResult.SUCCESS
        }
    }
}
