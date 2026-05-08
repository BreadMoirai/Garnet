package com.breadmoirai.redstonespecs.test

import com.breadmoirai.redstonespecs.data.dsl.redstoneSpec
import com.breadmoirai.redstonespecs.runner.EngineDrivenRun
import com.breadmoirai.redstonespecs.testing.RedstoneTestSpec
import com.breadmoirai.redstonespecs.testing.core.McDispatchers
import io.kotest.matchers.collections.shouldNotBeEmpty
import net.minecraft.core.BlockPos

class RunnerBlockEngineE2ETest : RedstoneTestSpec({
    test("EngineDrivenRun completes a trivial spec end-to-end") {
        val spec = redstoneSpec("e2e-trivial") {
            bounds(1, 1, 1)
            lifespan = 2
        }
        val server = McDispatchers.currentServer
        val level = server.overworld()

        val result = EngineDrivenRun.run(spec, BlockPos(0, 64, 0), level)
        result.checks.shouldNotBeEmpty()
    }
})
