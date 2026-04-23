package com.breadmoirai.redstonespecs.data

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder

data class TickCheck(
    val simTime: SimTime,
    val label: String,
    val expected: String,
    val actual: String,
    val pass: Boolean,
) {
    companion object {
        val CODEC: Codec<TickCheck> = RecordCodecBuilder.create { instance ->
            instance.group(
                SimTime.CODEC.fieldOf("sim_time").forGetter(TickCheck::simTime),
                Codec.STRING.fieldOf("label").forGetter(TickCheck::label),
                Codec.STRING.fieldOf("expected").forGetter(TickCheck::expected),
                Codec.STRING.fieldOf("actual").forGetter(TickCheck::actual),
                Codec.BOOL.fieldOf("pass").forGetter(TickCheck::pass),
            ).apply(instance, ::TickCheck)
        }
    }
}

data class TestResult(
    val specId: String,
    val timestamp: Long,
    val checks: List<TickCheck>,
) {
    val pass: Boolean get() = checks.all { it.pass }
    val passCount: Int get() = checks.count { it.pass }

    companion object {
        val CODEC: Codec<TestResult> = RecordCodecBuilder.create { instance ->
            instance.group(
                Codec.STRING.fieldOf("spec_id").forGetter(TestResult::specId),
                Codec.LONG.fieldOf("timestamp").forGetter(TestResult::timestamp),
                TickCheck.CODEC.listOf().optionalFieldOf("checks", emptyList())
                    .forGetter(TestResult::checks),
            ).apply(instance, ::TestResult)
        }
    }
}
