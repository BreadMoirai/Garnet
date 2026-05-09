package com.breadmoirai.redstonespecs.runner

import com.breadmoirai.redstonespecs.dsl.Phase
import com.breadmoirai.redstonespecs.dsl.SimTime
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import net.minecraft.SharedConstants
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.nbt.NbtIo
import net.minecraft.resources.Identifier
import net.minecraft.server.Bootstrap
import net.minecraft.world.level.block.LeverBlock
import java.util.UUID
import kotlin.io.path.createTempDirectory

class StateRecordingStorageTest : FunSpec({

    beforeSpec {
        SharedConstants.tryDetectVersion()
        Bootstrap.bootStrap()
    }

    test("recording NBT roundtrip") {
        val dir = createTempDirectory("StateRecordingStorageTest")
        val lever = BuiltInRegistries.BLOCK.getValue(Identifier.parse("minecraft:lever"))
        val unpowered = lever.defaultBlockState().setValue(LeverBlock.POWERED, false)
        val powered = lever.defaultBlockState().setValue(LeverBlock.POWERED, true)
        val pos = BlockPos(0, 0, 0)
        val specId = UUID.randomUUID()
        val recording = StateRecording(
            specId = specId,
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
        val file = StateRecordingStorage.fileFor(dir, specId)
        file.parentFile?.mkdirs()
        NbtIo.write(recording.toNbt(), file.toPath())
        val loaded = stateRecordingFromNbt(NbtIo.read(file.toPath()) ?: error("NbtIo.read returned null"))
        loaded.specId shouldBe recording.specId
        loaded.timestamp shouldBe recording.timestamp
        loaded.changes shouldBe recording.changes
        StateRecordingView.of(loaded).stateAt(pos, SimTime(0, Phase.END_OF_TICK)) shouldBe powered
        loaded.initialSnapshot.keys shouldBe recording.initialSnapshot.keys
        recording.initialSnapshot.forEach { (p, state) ->
            loaded.initialSnapshot[p]?.toString() shouldBe state.toString()
        }
    }
})
