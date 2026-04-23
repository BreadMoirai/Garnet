package com.breadmoirai.redstonespecs.runner

import net.minecraft.core.BlockPos
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.resources.Identifier
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.level.block.state.properties.Property
import com.breadmoirai.redstonespecs.data.Phase
import com.breadmoirai.redstonespecs.data.SimTime
import java.util.UUID

data class PropertyDiff(val name: String, val to: String)

data class BlockStateChange(
    val pos: BlockPos,               // bounds-local: (0,0,0) = bounds min corner, extends +x/+y/+z
    val simTime: SimTime,
    val toBlock: Identifier?,        // null if block type unchanged
    val diffs: List<PropertyDiff>,
)

data class StateRecording(
    val specId: UUID,
    val timestamp: Long,
    val initialSnapshot: Map<BlockPos, BlockState>, // keyed by bounds-local pos
    val changes: List<BlockStateChange>,            // ordered by simTime
)

// NBT helpers — kept here so codec logic is co-located with the data

fun StateRecording.toNbt(): CompoundTag {
    val tag = CompoundTag()
    tag.putString("specId", specId.toString())
    tag.putLong("timestamp", timestamp)

    val snapshotList = ListTag()
    for ((pos, state) in initialSnapshot) {
        val entry = CompoundTag()
        entry.putIntArray("pos", intArrayOf(pos.x, pos.y, pos.z))
        entry.putString("state", blockStateToString(state))
        snapshotList.add(entry)
    }
    tag.put("initialSnapshot", snapshotList)

    val changesList = ListTag()
    for (change in changes) {
        val c = CompoundTag()
        c.putIntArray("pos", intArrayOf(change.pos.x, change.pos.y, change.pos.z))
        c.putInt("tick", change.simTime.tick)
        c.putString("phase", change.simTime.phase.name)
        c.putInt("order", change.simTime.order)
        change.toBlock?.let { c.putString("toBlock", it.toString()) }
        val diffsList = ListTag()
        for (diff in change.diffs) {
            val d = CompoundTag()
            d.putString("name", diff.name)
            d.putString("to", diff.to)
            diffsList.add(d)
        }
        c.put("diffs", diffsList)
        changesList.add(c)
    }
    tag.put("changes", changesList)
    return tag
}

fun stateRecordingFromNbt(tag: CompoundTag): StateRecording {
    val specId = UUID.fromString(tag.getStringOr("specId", ""))
    val timestamp = tag.getLongOr("timestamp", 0L)

    val snapshotList = tag.getListOrEmpty("initialSnapshot")
    val initialSnapshot = buildMap {
        for (i in 0 until snapshotList.size) {
            val entry = snapshotList.getCompoundOrEmpty(i)
            val arr = entry.getIntArray("pos").orElse(intArrayOf(0, 0, 0))
            val pos = BlockPos(arr[0], arr[1], arr[2])
            put(pos, blockStateFromString(entry.getStringOr("state", "minecraft:air")))
        }
    }

    val changesList = tag.getListOrEmpty("changes")
    val changes = buildList {
        for (i in 0 until changesList.size) {
            val c = changesList.getCompoundOrEmpty(i)
            val arr = c.getIntArray("pos").orElse(intArrayOf(0, 0, 0))
            val pos = BlockPos(arr[0], arr[1], arr[2])
            val simTime = SimTime(
                c.getIntOr("tick", 0),
                Phase.valueOf(c.getStringOr("phase", Phase.START_OF_TICK.name)),
                c.getIntOr("order", 0),
            )
            val toBlock = if (c.contains("toBlock")) Identifier.parse(c.getStringOr("toBlock", "")) else null
            val diffsList = c.getListOrEmpty("diffs")
            val diffs = buildList {
                for (j in 0 until diffsList.size) {
                    val d = diffsList.getCompoundOrEmpty(j)
                    add(PropertyDiff(d.getStringOr("name", ""), d.getStringOr("to", "")))
                }
            }
            add(BlockStateChange(pos, simTime, toBlock, diffs))
        }
    }
    return StateRecording(specId, timestamp, initialSnapshot, changes)
}

private fun blockStateToString(state: BlockState): String {
    val block = BuiltInRegistries.BLOCK.getKey(state.block)?.toString()
        ?: return "minecraft:air"
    if (state.block.stateDefinition.properties.isEmpty()) return block
    val props = captureBlockStateProps(state).entries.joinToString(",") { "${it.key}=${it.value}" }
    return "$block[$props]"
}

private fun blockStateFromString(str: String): BlockState {
    val bracketIdx = str.indexOf('[')
    val blockId = if (bracketIdx == -1) str else str.substring(0, bracketIdx)
    val block = BuiltInRegistries.BLOCK
        .getValue(Identifier.parse(blockId))
    if (bracketIdx == -1) return block.defaultBlockState()
    val propsStr = str.substring(bracketIdx + 1, str.length - 1)
    var state = block.defaultBlockState()
    for (part in propsStr.split(",")) {
        val (name, value) = part.split("=", limit = 2)
        val property = state.block.stateDefinition.getProperty(name) ?: continue
        @Suppress("UNCHECKED_CAST")
        state = applyPropertyFromString(state, property as Property<Comparable<Any>>, value)
    }
    return state
}
