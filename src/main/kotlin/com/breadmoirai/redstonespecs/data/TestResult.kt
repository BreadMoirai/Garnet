package com.breadmoirai.redstonespecs.data

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.core.UUIDUtil
import java.util.UUID

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

data class SpecCaseResult(
    val specCaseName: String,
    val checks: List<TickCheck>,
) {
    companion object {
        val CODEC: Codec<SpecCaseResult> = RecordCodecBuilder.create { instance ->
            instance.group(
                Codec.STRING.fieldOf("spec_case_name").forGetter(SpecCaseResult::specCaseName),
                TickCheck.CODEC.listOf().optionalFieldOf("checks", emptyList())
                    .forGetter(SpecCaseResult::checks),
            ).apply(instance, ::SpecCaseResult)
        }
    }
}

data class TestResult(
    val specId: UUID,
    val timestamp: Long,
    val results: List<SpecCaseResult>,
) {
    companion object {
        val CODEC: Codec<TestResult> = RecordCodecBuilder.create { instance ->
            instance.group(
                UUIDUtil.CODEC.fieldOf("spec_id").forGetter(TestResult::specId),
                Codec.LONG.fieldOf("timestamp").forGetter(TestResult::timestamp),
                SpecCaseResult.CODEC.listOf().optionalFieldOf("results", emptyList())
                    .forGetter(TestResult::results),
            ).apply(instance, ::TestResult)
        }
    }
}
