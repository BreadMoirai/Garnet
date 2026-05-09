package com.breadmoirai.redstonespecs.data

import com.breadmoirai.redstonespecs.dsl.SimTime
import com.breadmoirai.redstonespecs.runner.StateRecording
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import net.minecraft.SharedConstants
import net.minecraft.nbt.NbtOps
import net.minecraft.server.Bootstrap
import java.util.UUID

class TestResultCodecTest : FunSpec({

    beforeSpec {
        SharedConstants.tryDetectVersion()
        Bootstrap.bootStrap()
    }

    fun <T> roundtrip(value: T, codec: com.mojang.serialization.Codec<T>): T {
        val encoded = codec.encodeStart(NbtOps.INSTANCE, value).getOrThrow()
        return codec.parse(NbtOps.INSTANCE, encoded).getOrThrow()
    }

    test("TestResult without recording roundtrips") {
        val result = TestResult(
            specId = "my_spec",
            timestamp = 1234567890L,
            checks = listOf(
                TickCheck(SimTime.START, "label", "ok", "ok", true),
            ),
        )
        roundtrip(result, TestResult.CODEC) shouldBe result
    }

    test("TestResult with null recording roundtrips") {
        val result = TestResult(
            specId = "spec_null_rec",
            timestamp = 9999L,
            checks = emptyList(),
            recording = null,
        )
        roundtrip(result, TestResult.CODEC) shouldBe result
    }

    test("TestResult with empty recording roundtrips") {
        val recording = StateRecording(
            specId = UUID.fromString("00000000-0000-0000-0000-000000000001"),
            timestamp = 100L,
            initialSnapshot = emptyMap(),
            changes = emptyList(),
        )
        val result = TestResult(
            specId = "spec_with_rec",
            timestamp = 42L,
            checks = listOf(
                TickCheck(SimTime.START, "check1", "expected", "actual", false),
            ),
            recording = recording,
        )
        val rt = roundtrip(result, TestResult.CODEC)
        rt shouldBe result
        rt.recording shouldBe recording
    }
})
