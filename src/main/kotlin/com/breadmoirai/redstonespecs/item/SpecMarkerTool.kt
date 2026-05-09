package com.breadmoirai.redstonespecs.item

import com.breadmoirai.redstonespecs.block.RedstoneSpecRecorderBlock
import com.breadmoirai.redstonespecs.block.RedstoneSpecRunnerBlock
import com.breadmoirai.redstonespecs.block.SpecBlockEntity
import com.breadmoirai.redstonespecs.data.EntryKind
import com.breadmoirai.redstonespecs.data.RedstoneSpec
import com.breadmoirai.redstonespecs.dsl.SimTime
import com.breadmoirai.redstonespecs.data.allEntries
import com.breadmoirai.redstonespecs.data.SpecEntry
import com.breadmoirai.redstonespecs.network.OpenEditorS2CPayload
import com.breadmoirai.redstonespecs.dsl.captureBlockStateProps
import com.breadmoirai.redstonespecs.dsl.propsToCondition
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

        if (be.blockState.block is RedstoneSpecRunnerBlock) {
            return InteractionResult.PASS
        }

        if (!level.isClientSide) {
            val spec = be.spec ?: return InteractionResult.PASS
            val relPos = hitPos.subtract(be.blockPos)
            val hitState = level.getBlockState(hitPos)
            val isRecorder = be.blockState.block is RedstoneSpecRecorderBlock
            val initProps = if (isRecorder) emptyMap() else captureBlockStateProps(hitState)

            val existing = spec.entries.any { it.pos == relPos }
            if (!existing) {
                LOGGER.debug("[SpecMarkerTool#useOn] placing {} entry at {}", javaClass.simpleName, relPos)
                be.addOrUpdateEntry(createEntry(relPos, initProps, hitState, spec))
            } else {
                LOGGER.debug("[SpecMarkerTool#useOn] opening editor for existing entry at {}", relPos)
            }

            if (!isRecorder) {
                ServerPlayNetworking.send(player as ServerPlayer, OpenEditorS2CPayload(be.blockPos, relPos))
            }
        }

        return InteractionResult.SUCCESS
    }
}

class InputSpecMarkerItem(properties: Properties = Properties()) : SpecMarkerTool(properties) {
    override fun createEntry(relPos: BlockPos, initProps: Map<String, String>, initState: BlockState, spec: RedstoneSpec): SpecEntry =
        SpecEntry(
            pos = relPos,
            label = defaultLabel(initState, spec),
            color = 0xFF4488FF.toInt(),
            kind = EntryKind.INPUT,
            time = SimTime.START,
            condition = propsToCondition(initProps, initState),
        )
}

class OutputSpecMarkerItem(properties: Properties = Properties()) : SpecMarkerTool(properties) {
    override fun createEntry(relPos: BlockPos, initProps: Map<String, String>, initState: BlockState, spec: RedstoneSpec): SpecEntry =
        SpecEntry(
            pos = relPos,
            label = defaultLabel(initState, spec),
            color = 0xFFFF8800.toInt(),
            kind = EntryKind.OUTPUT,
            time = SimTime.START,
            condition = propsToCondition(initProps, initState),
        )
}
