package com.breadmoirai.redstonespecs.test

import com.breadmoirai.redstonespecs.data.dsl.redstoneSpec
import com.breadmoirai.redstonespecs.runner.EngineDrivenRun
import com.breadmoirai.redstonespecs.testing.RedstoneTestSpec
import com.breadmoirai.redstonespecs.testing.core.McDispatchers
import io.kotest.matchers.nulls.shouldNotBeNull
import net.minecraft.core.BlockPos

class DiagnosticRecordingE2ETest : RedstoneTestSpec({
    test("EngineDrivenRun result carries a non-null StateRecording") {
        val spec = redstoneSpec("e2e-diag") { bounds(1, 1, 1); lifespan = 2 }
        val server = McDispatchers.currentServer
        val result = EngineDrivenRun.run(spec, BlockPos(0, 64, 0), server.overworld())
        result.recording.shouldNotBeNull()
    }
})
