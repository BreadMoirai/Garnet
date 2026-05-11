package com.breadmoirai.redstonespecs.test

import com.breadmoirai.redstonespecs.dsl.redstoneSpec
import com.breadmoirai.redstonespecs.testing.ClientSpec
import com.breadmoirai.redstonespecs.testing.core.McDispatchers
import com.breadmoirai.redstonespecs.testing.runner.runRedstoneSpec
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import net.minecraft.core.BlockPos
import net.minecraft.core.Vec3i

class RunRedstoneSpecSmokeTest : ClientSpec({
    test("runRedstoneSpec completes for a trivial empty spec") {
        val spec = redstoneSpec(
            id = "smoke-empty",
            bounds = Vec3i(1, 1, 1),
            lifespan = 1,
            structure = null,
            strict = false,
        ) {}
        val server = McDispatchers.currentServer
        val recording = runRedstoneSpec(spec, BlockPos(0, 64, 0), server.overworld())
        recording.changes.size shouldBeGreaterThanOrEqual 0
    }
})
