package com.breadmoirai.garnet.persistence

import com.breadmoirai.garnet.dsl.Phase
import com.breadmoirai.garnet.dsl.SimTime
import com.breadmoirai.garnet.runner.BlockStateChange
import com.breadmoirai.garnet.runner.PropertyDiff
import com.breadmoirai.garnet.runner.StateRecording
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import net.minecraft.SharedConstants
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.Identifier
import net.minecraft.server.Bootstrap
import net.minecraft.world.level.block.LeverBlock
import java.nio.file.Files
import java.util.UUID

class RecordingSidecarTest : FunSpec({

    beforeSpec {
        SharedConstants.tryDetectVersion()
        Bootstrap.bootStrap()
    }

    test("save then load yields an equivalent recording") {
        val tmp = Files.createTempDirectory("recording-sidecar")
        val recording = makeMinimalRecording()
        RecordingSidecar.save(tmp, "spec-1", recording)
        val loaded = RecordingSidecar.load(tmp, "spec-1")
        loaded shouldNotBe null
        loaded!!.changes.size shouldBe recording.changes.size
    }

    test("load returns null when sidecar absent") {
        val tmp = Files.createTempDirectory("recording-sidecar-empty")
        RecordingSidecar.load(tmp, "missing") shouldBe null
    }

    test("save then load round-trips specId and timestamp") {
        val tmp = Files.createTempDirectory("recording-sidecar-rt")
        val recording = makeMinimalRecording()
        RecordingSidecar.save(tmp, "spec-rt", recording)
        val loaded = RecordingSidecar.load(tmp, "spec-rt")
        loaded shouldNotBe null
        loaded!!.specId shouldBe recording.specId
        loaded.timestamp shouldBe recording.timestamp
    }
})

private fun makeMinimalRecording(): StateRecording {
    val lever = BuiltInRegistries.BLOCK.getValue(Identifier.parse("minecraft:lever"))
    val unpowered = lever.defaultBlockState().setValue(LeverBlock.POWERED, false)
    val pos = BlockPos(0, 0, 0)
    return StateRecording(
        specId = UUID.randomUUID(),
        timestamp = 12345L,
        initialSnapshot = mapOf(pos to unpowered),
        changes = listOf(
            BlockStateChange(
                pos = pos,
                simTime = SimTime(0, Phase.SCHEDULED_TICKS, 0),
                toBlock = null,
                diffs = listOf(PropertyDiff("powered", "true")),
            )
        ),
    )
}
