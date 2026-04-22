package com.breadmoirai.redstonespecs

import com.breadmoirai.redstonespecs.block.SpecOriginBlockEntity
import com.breadmoirai.redstonespecs.event.SubTickPhaseEvents
import com.breadmoirai.redstonespecs.item.SpecMarkerTool
import com.breadmoirai.redstonespecs.item.UndoStack
import com.breadmoirai.redstonespecs.network.registerNetworking
import com.breadmoirai.redstonespecs.runner.SpecRunnerCoordinator
import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.event.player.AttackBlockCallback
import net.minecraft.world.InteractionResult
import org.slf4j.LoggerFactory

private val LOGGER = LoggerFactory.getLogger("Redstone Specs")

class Redstonespecs : ModInitializer {

    override fun onInitialize() {
        LOGGER.debug("[Redstonespecs#onInitialize] initializing mod")
        ModRegistries.register()
        registerNetworking()
        registerAttackCallback()
        SubTickPhaseEvents.PHASE.register { level, phase ->
            SpecRunnerCoordinator.onPhase(level, phase)
        }
        LOGGER.debug("[Redstonespecs#onInitialize] initialization complete")
    }

    private fun registerAttackCallback() {
        AttackBlockCallback.EVENT.register { player, world, hand, pos, _ ->
            val item = player.getItemInHand(hand).item
            if (item !is SpecMarkerTool) return@register InteractionResult.PASS
            if (world.isClientSide) return@register InteractionResult.SUCCESS

            val be = SpecOriginBlockEntity.findFor(world, pos) ?: return@register InteractionResult.PASS
            val relPos = pos.subtract(be.blockPos)
            val removed = be.removeEntry(be.activeSpecCaseIndex, relPos)
            if (removed != null) {
                LOGGER.debug("[Redstonespecs#attackCallback] removed entry at {} from case {}", relPos, be.activeSpecCaseIndex)
                UndoStack.push(player.uuid, UndoStack.UndoRecord(be.blockPos, be.activeSpecCaseIndex, removed))
            }

            InteractionResult.SUCCESS
        }
    }
}
