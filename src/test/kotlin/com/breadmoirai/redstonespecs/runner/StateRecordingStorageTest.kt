package com.breadmoirai.redstonespecs.runner

import com.breadmoirai.redstonespecs.data.Phase
import com.breadmoirai.redstonespecs.data.SimTime
import net.minecraft.SharedConstants
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.nbt.NbtIo
import net.minecraft.resources.Identifier
import net.minecraft.server.Bootstrap
import net.minecraft.world.level.block.LeverBlock
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.UUID

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StateRecordingStorageTest {

    @BeforeAll
    fun bootstrap() {
        SharedConstants.tryDetectVersion()
        Bootstrap.bootStrap()
    }

    @Test
    fun `recording NBT roundtrip`(@TempDir dir: Path) {
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
        assertEquals(recording.specId, loaded.specId)
        assertEquals(recording.timestamp, loaded.timestamp)
        assertEquals(recording.changes, loaded.changes)
        assertEquals(powered, StateRecordingView.of(loaded).stateAt(pos, SimTime(0, Phase.END_OF_TICK)))
        assertEquals(recording.initialSnapshot.keys, loaded.initialSnapshot.keys)
        recording.initialSnapshot.forEach { (pos, state) ->
            assertEquals(state.toString(), loaded.initialSnapshot[pos]?.toString(),
                "initialSnapshot mismatch at $pos")
        }
    }
}
