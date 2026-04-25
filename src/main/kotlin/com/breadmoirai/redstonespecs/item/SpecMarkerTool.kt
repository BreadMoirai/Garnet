package com.breadmoirai.redstonespecs.item

import com.breadmoirai.redstonespecs.block.SpecBlockEntity
import com.breadmoirai.redstonespecs.data.AutoSpec
import com.breadmoirai.redstonespecs.data.BreakpointSpec
import com.breadmoirai.redstonespecs.data.InputSpec
import com.breadmoirai.redstonespecs.data.OutputSpec
import com.breadmoirai.redstonespecs.data.Phase
import com.breadmoirai.redstonespecs.data.RedstoneSpec
import com.breadmoirai.redstonespecs.data.SimTime
import com.breadmoirai.redstonespecs.data.allEntries
import com.breadmoirai.redstonespecs.data.SpecEntry
import com.breadmoirai.redstonespecs.data.SpecMode
import com.breadmoirai.redstonespecs.network.OpenEditorS2CPayload
import com.breadmoirai.redstonespecs.runner.captureBlockStateProps
import com.breadmoirai.redstonespecs.runner.propsToCondition
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionResult
import net.minecraft.world.item.Item
import net.minecraft.world.item.context.UseOnContext
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.state.BlockState
import org.slf4j.LoggerFactory

internal fun nextLabel(blockName: String, existing: Set<String>): String {
    var index = 0
    while (true) {
        val suffix = if (index < 26) {
            ('a' + index).toString()
        } else {
            val first = 'a' + (index / 26) - 1
            val second = 'a' + (index % 26)
            "$first$second"
        }
        val label = "${blockName}_${suffix}"
        if (label !in existing) return label
        index++
    }
}

private val LOGGER = LoggerFactory.getLogger("Redstone Specs")

abstract class SpecMarkerTool(properties: Properties = Properties()) : Item(properties) {

    abstract fun createEntry(relPos: BlockPos, initProps: Map<String, String>, initState: BlockState, spec: RedstoneSpec): SpecEntry

    protected fun defaultLabel(initState: BlockState, spec: RedstoneSpec): String {
        val blockName = BuiltInRegistries.BLOCK.getKey(initState.block).path
        return nextLabel(blockName, spec.allEntries.map { it.label }.toSet())
    }

    override fun useOn(context: UseOnContext): InteractionResult {
        val level: Level = context.level
        val hitPos: BlockPos = context.clickedPos
        val player = context.player ?: return InteractionResult.PASS

        val be = SpecBlockEntity.findFor(level, hitPos) ?: return InteractionResult.PASS

        if (!level.isClientSide) {
            val spec = be.spec ?: return InteractionResult.PASS
            val relPos = hitPos.subtract(be.blockPos)
            val hitState = level.getBlockState(hitPos)
            val initProps = captureBlockStateProps(hitState)

            if (spec.entryAt(relPos) == null) {
                LOGGER.debug("[SpecMarkerTool#useOn] placing {} entry at {}", javaClass.simpleName, relPos)
                be.addOrUpdateEntry(createEntry(relPos, initProps, hitState, spec))
            } else {
                LOGGER.debug("[SpecMarkerTool#useOn] opening editor for existing entry at {}", relPos)
            }

            ServerPlayNetworking.send(player as ServerPlayer, OpenEditorS2CPayload(be.blockPos, relPos))
        }

        return InteractionResult.SUCCESS
    }
}

class InputSpecMarkerItem(properties: Properties = Properties()) : SpecMarkerTool(properties) {
    override fun createEntry(relPos: BlockPos, initProps: Map<String, String>, initState: BlockState, spec: RedstoneSpec): SpecEntry =
        InputSpec(relPos, defaultLabel(initState, spec), 0x4488FF, listOf(SimTime.INIT to propsToCondition(initProps, initState)))
}

class OutputSpecMarkerItem(properties: Properties = Properties()) : SpecMarkerTool(properties) {
    override fun createEntry(relPos: BlockPos, initProps: Map<String, String>, initState: BlockState, spec: RedstoneSpec): SpecEntry {
        val time = if (spec.mode == SpecMode.SIMPLE) SimTime(spec.lifespan, Phase.END_OF_TICK) else SimTime.INIT
        return OutputSpec(relPos, defaultLabel(initState, spec), 0xFF8800, listOf(time to propsToCondition(initProps, initState)))
    }
}

class BreakpointSpecMarkerItem(properties: Properties = Properties()) : SpecMarkerTool(properties) {
    override fun createEntry(relPos: BlockPos, initProps: Map<String, String>, initState: BlockState, spec: RedstoneSpec): SpecEntry =
        BreakpointSpec(relPos, defaultLabel(initState, spec), 0xFF4444)
}

class AutoSpecMarkerItem(properties: Properties = Properties()) : SpecMarkerTool(properties) {
    override fun createEntry(relPos: BlockPos, initProps: Map<String, String>, initState: BlockState, spec: RedstoneSpec): SpecEntry =
        AutoSpec(relPos, defaultLabel(initState, spec), 0xFFAA00)
}
