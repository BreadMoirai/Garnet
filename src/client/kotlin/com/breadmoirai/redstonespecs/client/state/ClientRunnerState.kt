package com.breadmoirai.redstonespecs.client.state

import net.minecraft.core.BlockPos
import java.util.concurrent.ConcurrentHashMap

/**
 * Client-side cache of the latest run result summary per runner block.
 * Populated by ClientNetworkHandler; read by the timeline scrubber screen.
 */
object ClientRunnerState {
    private val summaryByRunner = ConcurrentHashMap<BlockPos, String>()

    fun put(runnerPos: BlockPos, summary: String) {
        summaryByRunner[runnerPos] = summary
    }

    fun get(runnerPos: BlockPos): String? = summaryByRunner[runnerPos]

    fun clear(runnerPos: BlockPos) { summaryByRunner.remove(runnerPos) }
}
