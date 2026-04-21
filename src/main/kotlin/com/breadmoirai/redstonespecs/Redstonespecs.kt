package com.breadmoirai.redstonespecs

import com.breadmoirai.redstonespecs.block.SpecOriginBlockEntity
import com.breadmoirai.redstonespecs.data.Phase
import com.breadmoirai.redstonespecs.item.SpecMarkerTool
import com.breadmoirai.redstonespecs.item.UndoStack
import com.breadmoirai.redstonespecs.network.registerNetworking
import com.breadmoirai.redstonespecs.runner.SpecRunnerCoordinator
import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.fabricmc.fabric.api.event.player.AttackBlockCallback
import net.minecraft.world.InteractionResult

class Redstonespecs : ModInitializer {

    override fun onInitialize() {
        ModRegistries.register()
        registerNetworking()
        registerAttackCallback()
        registerPhaseHooks()
    }

    private fun registerPhaseHooks() {
        ServerTickEvents.START_LEVEL_TICK.register { level ->
            SpecRunnerCoordinator.onPhase(level, level.server.tickCount, Phase.START_OF_TICK)
        }
        ServerTickEvents.END_LEVEL_TICK.register { level ->
            SpecRunnerCoordinator.onPhase(level, level.server.tickCount, Phase.END_OF_TICK)
        }
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
                UndoStack.push(player.uuid, UndoStack.UndoRecord(be.blockPos, be.activeSpecCaseIndex, removed))
            }

            InteractionResult.SUCCESS
        }
    }
}
