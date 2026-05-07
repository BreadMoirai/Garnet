package com.breadmoirai.redstonespecs.data.serial

import com.breadmoirai.redstonespecs.data.EntryKind
import com.breadmoirai.redstonespecs.data.Phase
import com.breadmoirai.redstonespecs.data.RedstoneSpec
import com.breadmoirai.redstonespecs.data.SimTime
import com.breadmoirai.redstonespecs.data.SpecEntry
import com.breadmoirai.redstonespecs.data.StateCondition
import com.mojang.serialization.JsonOps
import net.minecraft.core.BlockPos
import net.minecraft.core.Vec3i
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SpecJsonCodecTest {
    @Test
    fun `RedstoneSpec round-trips through SPEC codec`() {
        val spec = RedstoneSpec(
            id = "test",
            bounds = Vec3i(5, 4, 5),
            lifespan = 40,
            structure = "redstonespecs:test",
            entries = listOf(
                SpecEntry(
                    pos = BlockPos(2, 0, 2),
                    label = "lever",
                    color = 0xFFFF4444.toInt(),
                    kind = EntryKind.INPUT,
                    time = SimTime(0, Phase.START_OF_TICK),
                    condition = StateCondition.BoolProperty("powered", true),
                ),
                SpecEntry(
                    pos = BlockPos(4, 0, 4),
                    label = "lamp",
                    color = -1,
                    kind = EntryKind.OUTPUT,
                    time = SimTime(11, Phase.END_OF_TICK),
                    condition = StateCondition.BoolProperty("lit", true),
                ),
            ),
        )

        val json = SpecJsonCodec.SPEC.encodeStart(JsonOps.INSTANCE, spec).getOrThrow()
        val decoded = SpecJsonCodec.SPEC.parse(JsonOps.INSTANCE, json).getOrThrow()

        assertEquals(spec, decoded)
    }
}
