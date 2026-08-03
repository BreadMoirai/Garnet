package com.breadmoirai.garnet.test

import com.breadmoirai.garnet.spec.garnetSpec
import com.breadmoirai.garnet.harness.ClientSpec
import com.breadmoirai.garnet.core.async.AsyncDispatchers
import com.breadmoirai.garnet.harness.runGarnetSpec
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import net.minecraft.core.BlockPos
import net.minecraft.core.Vec3i

class RunGarnetSpecSmokeTest : ClientSpec({
    test("runGarnetSpec completes for a trivial empty spec") {
        val spec = garnetSpec(
            id = "smoke-empty",
            bounds = Vec3i(1, 1, 1),
            lifespan = 1,
            structure = null,
            strict = false,
        ) {}
        val server = AsyncDispatchers.currentServer
        val recording = runGarnetSpec(spec, BlockPos(0, 64, 0), server.overworld())
        recording.changes.size shouldBeGreaterThanOrEqual 0
    }
})
