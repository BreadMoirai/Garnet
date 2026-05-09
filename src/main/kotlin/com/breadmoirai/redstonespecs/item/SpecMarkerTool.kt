package com.breadmoirai.redstonespecs.item

import com.breadmoirai.redstonespecs.block.RedstoneSpecRunnerBlock
import com.breadmoirai.redstonespecs.block.SpecBlockEntity
import com.breadmoirai.redstonespecs.runner.EntryMarker
import net.minecraft.core.BlockPos
import net.minecraft.world.InteractionResult
import net.minecraft.world.item.Item
import net.minecraft.world.item.context.UseOnContext
import net.minecraft.world.level.Level
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

    abstract fun createMarker(relPos: BlockPos, be: SpecBlockEntity): EntryMarker

    override fun useOn(context: UseOnContext): InteractionResult {
        val level: Level = context.level
        val hitPos: BlockPos = context.clickedPos
        context.player ?: return InteractionResult.PASS

        val be = SpecBlockEntity.findFor(level, hitPos) ?: return InteractionResult.PASS

        if (be.blockState.block is RedstoneSpecRunnerBlock) {
            return InteractionResult.PASS
        }

        if (!level.isClientSide) {
            val relPos = hitPos.subtract(be.blockPos)
            val existing = be.specMarkers.any { it.pos == relPos }
            if (!existing) {
                LOGGER.debug("[SpecMarkerTool#useOn] placing {} marker at {}", javaClass.simpleName, relPos)
                be.addOrUpdateMarker(createMarker(relPos, be))
            } else {
                LOGGER.debug("[SpecMarkerTool#useOn] marker already exists at {}", relPos)
            }
        }

        return InteractionResult.SUCCESS
    }
}

class InputSpecMarkerItem(properties: Properties = Properties()) : SpecMarkerTool(properties) {
    override fun createMarker(relPos: BlockPos, be: SpecBlockEntity): EntryMarker =
        EntryMarker(
            pos = relPos,
            label = nextLabel("input", be.specMarkers.map { it.label }.toSet()),
            color = 0xFF4488FF.toInt(),
            kind = EntryMarker.Kind.INPUT,
        )
}

class OutputSpecMarkerItem(properties: Properties = Properties()) : SpecMarkerTool(properties) {
    override fun createMarker(relPos: BlockPos, be: SpecBlockEntity): EntryMarker =
        EntryMarker(
            pos = relPos,
            label = nextLabel("output", be.specMarkers.map { it.label }.toSet()),
            color = 0xFFFF8800.toInt(),
            kind = EntryMarker.Kind.OUTPUT,
        )
}
