package com.breadmoirai.redstonespecs.client.state

import com.breadmoirai.redstonespecs.data.TestResult
import net.minecraft.core.BlockPos
import java.util.concurrent.ConcurrentHashMap

/**
 * Client-side cache of the latest [TestResult] (with optional StateRecording attached)
 * per runner block. Populated by ClientNetworkHandler on TestResultS2CPayload receipt;
 * read by the timeline scrubber screen.
 */
object ClientRunnerState {
    private val byRunner = ConcurrentHashMap<BlockPos, TestResult>()

    fun put(runnerPos: BlockPos, result: TestResult) {
        byRunner[runnerPos] = result
    }

    fun get(runnerPos: BlockPos): TestResult? = byRunner[runnerPos]

    fun clear(runnerPos: BlockPos) { byRunner.remove(runnerPos) }
}
