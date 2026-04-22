package com.breadmoirai.redstonespecs.item

import com.breadmoirai.redstonespecs.block.SpecOriginBlockEntity
import com.breadmoirai.redstonespecs.data.AutoSpec
import com.breadmoirai.redstonespecs.data.BreakpointSpec
import com.breadmoirai.redstonespecs.data.InputSpec
import com.breadmoirai.redstonespecs.data.OutputSpec
import com.breadmoirai.redstonespecs.data.SimTime
import com.breadmoirai.redstonespecs.data.SpecEntry
import com.breadmoirai.redstonespecs.network.OpenEditorS2CPayload
import com.breadmoirai.redstonespecs.runner.captureBlockStateProps
import com.breadmoirai.redstonespecs.runner.propsToCondition
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionResult
import net.minecraft.world.item.Item
import net.minecraft.world.item.context.UseOnContext
import net.minecraft.world.level.Level
import org.slf4j.LoggerFactory

private val LOGGER = LoggerFactory.getLogger("Redstone Specs")

abstract class SpecMarkerTool(properties: Properties = Properties()) : Item(properties) {

    abstract fun createEntry(relPos: BlockPos, initProps: Map<String, String>, initState: net.minecraft.world.level.block.state.BlockState): SpecEntry

    override fun useOn(context: UseOnContext): InteractionResult {
        val level: Level = context.level
        val hitPos: BlockPos = context.clickedPos
        val player = context.player ?: return InteractionResult.PASS

        val be = SpecOriginBlockEntity.findFor(level, hitPos) ?: return InteractionResult.PASS

        if (!level.isClientSide) {
            val spec = be.spec ?: return InteractionResult.PASS
            if (be.activeSpecCaseIndex >= spec.specCases.size) return InteractionResult.PASS

            val relPos = hitPos.subtract(be.blockPos)
            val specCase = spec.specCases[be.activeSpecCaseIndex]
            val hitState = level.getBlockState(hitPos)
            val initProps = captureBlockStateProps(hitState)

            if (specCase.entryAt(relPos) == null) {
                LOGGER.debug("[SpecMarkerTool#useOn] placing {} entry at {} for case {}", javaClass.simpleName, relPos, be.activeSpecCaseIndex)
                be.addOrUpdateEntry(be.activeSpecCaseIndex, createEntry(relPos, initProps, hitState))
            } else {
                LOGGER.debug("[SpecMarkerTool#useOn] opening editor for existing entry at {} case {}", relPos, be.activeSpecCaseIndex)
            }

            ServerPlayNetworking.send(player as ServerPlayer, OpenEditorS2CPayload(be.blockPos, relPos))
        }

        return InteractionResult.SUCCESS
    }
}

class InputSpecMarkerItem(properties: Properties = Properties()) : SpecMarkerTool(properties) {
    override fun createEntry(relPos: BlockPos, initProps: Map<String, String>, initState: net.minecraft.world.level.block.state.BlockState): SpecEntry =
        InputSpec(relPos, "", 0x4488FF, listOf(SimTime.INIT to propsToCondition(initProps, initState)))
}

class OutputSpecMarkerItem(properties: Properties = Properties()) : SpecMarkerTool(properties) {
    override fun createEntry(relPos: BlockPos, initProps: Map<String, String>, initState: net.minecraft.world.level.block.state.BlockState): SpecEntry =
        OutputSpec(relPos, "", 0x44FF88, listOf(SimTime.INIT to propsToCondition(initProps, initState)))
}

class BreakpointSpecMarkerItem(properties: Properties = Properties()) : SpecMarkerTool(properties) {
    override fun createEntry(relPos: BlockPos, initProps: Map<String, String>, initState: net.minecraft.world.level.block.state.BlockState): SpecEntry =
        BreakpointSpec(relPos, "", 0xFF4444)
}

class AutoSpecMarkerItem(properties: Properties = Properties()) : SpecMarkerTool(properties) {
    override fun createEntry(relPos: BlockPos, initProps: Map<String, String>, initState: net.minecraft.world.level.block.state.BlockState): SpecEntry =
        AutoSpec(relPos, "", 0xFFAA00)
}
