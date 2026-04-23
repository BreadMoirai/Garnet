package com.breadmoirai.redstonespecs.item

import com.breadmoirai.redstonespecs.data.SpecEntry
import net.minecraft.core.BlockPos
import java.util.UUID

object UndoStack {
    private const val MAX_DEPTH = 20

    data class UndoRecord(val originPos: BlockPos, val entry: SpecEntry)

    private val stacks = HashMap<UUID, ArrayDeque<UndoRecord>>()

    fun push(playerId: UUID, record: UndoRecord) {
        val stack = stacks.getOrPut(playerId, ::ArrayDeque)
        stack.addLast(record)
        if (stack.size > MAX_DEPTH) stack.removeFirst()
    }

    fun pop(playerId: UUID): UndoRecord? = stacks[playerId]?.removeLastOrNull()

    fun clear(playerId: UUID) { stacks.remove(playerId) }
}
