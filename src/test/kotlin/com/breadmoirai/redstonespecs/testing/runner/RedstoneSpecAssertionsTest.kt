package com.breadmoirai.redstonespecs.testing.runner

import com.breadmoirai.redstonespecs.data.EntryKind
import com.breadmoirai.redstonespecs.dsl.Phase
import com.breadmoirai.redstonespecs.data.RedstoneSpec
import com.breadmoirai.redstonespecs.dsl.SimTime
import com.breadmoirai.redstonespecs.data.SpecEntry
import com.breadmoirai.redstonespecs.data.StateCondition
import com.breadmoirai.redstonespecs.runner.BlockStateChange
import com.breadmoirai.redstonespecs.runner.PropertyDiff
import com.breadmoirai.redstonespecs.runner.StateRecording
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.string.shouldContain
import net.minecraft.SharedConstants
import net.minecraft.core.BlockPos
import net.minecraft.core.Vec3i
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.Identifier
import net.minecraft.server.Bootstrap
import net.minecraft.world.level.block.LeverBlock
import java.util.UUID

class RedstoneSpecAssertionsTest : FunSpec({

    beforeSpec {
        SharedConstants.tryDetectVersion()
        Bootstrap.bootStrap()
    }

    test("assertOutputsMatch passes when no outputs declared and recording empty") {
        val spec = RedstoneSpec(
            id = "empty", bounds = Vec3i(1, 1, 1), lifespan = 1,
            structure = null, entries = emptyList(),
        )
        val recording = emptyStateRecording(spec)
        shouldNotThrowAny { assertOutputsMatch(spec, recording) }
    }

    test("assertOutputsMatch throws AssertionError mentioning the entry's tick on mismatch") {
        val (spec, recording) = buildMismatchedSpecAndRecording()
        val ex = shouldThrow<AssertionError> { assertOutputsMatch(spec, recording) }
        ex.message shouldContain "tick 1"
    }
})

private fun emptyStateRecording(spec: RedstoneSpec): StateRecording = StateRecording(
    specId = UUID.randomUUID(),
    timestamp = 0L,
    initialSnapshot = emptyMap(),
    changes = emptyList(),
)

private fun buildMismatchedSpecAndRecording(): Pair<RedstoneSpec, StateRecording> {
    val pos = BlockPos(0, 0, 0)
    val lever = BuiltInRegistries.BLOCK.getValue(Identifier.parse("minecraft:lever"))
    val unpowered = lever.defaultBlockState().setValue(LeverBlock.POWERED, false)

    // Spec expects powered=true at tick 1 END_OF_TICK
    val spec = RedstoneSpec(
        id = "mismatch-test",
        bounds = Vec3i(1, 1, 1),
        lifespan = 2,
        structure = null,
        entries = listOf(
            SpecEntry(
                pos = pos,
                label = "output",
                color = 0,
                kind = EntryKind.OUTPUT,
                time = SimTime(1, Phase.END_OF_TICK),
                condition = StateCondition.BoolProperty("powered", true),
            )
        ),
    )

    // Recording: initial snapshot has unpowered lever, no changes → at tick 1 END_OF_TICK state is still unpowered
    val recording = StateRecording(
        specId = UUID.randomUUID(),
        timestamp = 0L,
        initialSnapshot = mapOf(pos to unpowered),
        changes = emptyList(),
    )

    return spec to recording
}
