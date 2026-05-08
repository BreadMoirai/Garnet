package com.breadmoirai.redstonespecs

import com.breadmoirai.redstonespecs.block.SpecBlockEntity
import com.breadmoirai.redstonespecs.config.SharedSettings
import com.breadmoirai.redstonespecs.event.SubTickPhaseEvents
import com.breadmoirai.redstonespecs.item.SpecMarkerTool
import com.breadmoirai.redstonespecs.item.UndoStack
import com.breadmoirai.redstonespecs.managed.ManagedCommand
import com.breadmoirai.redstonespecs.managed.ManagedDatapackWriter
import com.breadmoirai.redstonespecs.managed.ManagedRoot
import com.breadmoirai.redstonespecs.managed.ManagedServerContext
import net.minecraft.world.level.storage.LevelResource
import com.breadmoirai.redstonespecs.network.registerNetworking
import com.breadmoirai.redstonespecs.runner.SpecRunnerCoordinator
import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.event.player.AttackBlockCallback
import net.fabricmc.fabric.api.event.player.UseBlockCallback
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
                // Write per-folder dim datapack. Caveat: dedicated server has already begun
                // level construction by SERVER_STARTING, so the dims become live on the *next*
                // restart. Until then the fallback single-dim handles cells.
                try {
                    val saveDir = server.getWorldPath(LevelResource.ROOT)
                    ManagedDatapackWriter.writeForRoot(root, saveDir)
                } catch (e: Exception) {
                    LOGGER.error("[Redstonespecs] managed datapack write failed: {}", e.message, e)
                }
            }
        }
        CommandRegistrationCallback.EVENT.register { dispatcher, _, _ ->
            ManagedCommand.register(dispatcher)
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
