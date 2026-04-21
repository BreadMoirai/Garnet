package com.breadmoirai.redstonespecs.data

import com.mojang.serialization.Codec
import com.mojang.serialization.MapCodec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.core.BlockPos

sealed class SpecEntry {
    abstract val pos: BlockPos
    abstract val label: String
    abstract val color: Int

    companion object {
        val CODEC: Codec<SpecEntry> = Codec.STRING.dispatch(
            "type",
            { entry: SpecEntry ->
                when (entry) {
                    is InputSpec -> "input"
                    is OutputSpec -> "output"
                    is BreakpointSpec -> "breakpoint"
                    is AutoSpec -> "auto"
                }
            },
            { type: String ->
                when (type) {
                    "input" -> InputSpec.MAP_CODEC
                    "output" -> OutputSpec.MAP_CODEC
                    "breakpoint" -> BreakpointSpec.MAP_CODEC
                    "auto" -> AutoSpec.MAP_CODEC
                    else -> throw IllegalArgumentException("Unknown SpecEntry type: $type")
                }
            }
        )
    }
}

data class InputSpec(
    override val pos: BlockPos,
    override val label: String,
    override val color: Int,
    val stateSpec: StateSpec,
) : SpecEntry() {
    companion object {
        val MAP_CODEC: MapCodec<InputSpec> = RecordCodecBuilder.mapCodec { instance ->
            instance.group(
                BlockPos.CODEC.fieldOf("pos").forGetter(InputSpec::pos),
                Codec.STRING.fieldOf("label").forGetter(InputSpec::label),
                Codec.INT.fieldOf("color").forGetter(InputSpec::color),
                StateSpec.CODEC.fieldOf("state_spec").forGetter(InputSpec::stateSpec),
            ).apply(instance, ::InputSpec)
        }
    }
}

data class OutputSpec(
    override val pos: BlockPos,
    override val label: String,
    override val color: Int,
    val stateSpec: StateSpec,
) : SpecEntry() {
    companion object {
        val MAP_CODEC: MapCodec<OutputSpec> = RecordCodecBuilder.mapCodec { instance ->
            instance.group(
                BlockPos.CODEC.fieldOf("pos").forGetter(OutputSpec::pos),
                Codec.STRING.fieldOf("label").forGetter(OutputSpec::label),
                Codec.INT.fieldOf("color").forGetter(OutputSpec::color),
                StateSpec.CODEC.fieldOf("state_spec").forGetter(OutputSpec::stateSpec),
            ).apply(instance, ::OutputSpec)
        }
    }
}

data class BreakpointSpec(
    override val pos: BlockPos,
    override val label: String,
    override val color: Int,
    val condition: StateCondition = DEFAULT_CONDITION,
    val enabled: Boolean = true,
) : SpecEntry() {
    companion object {
        val MAP_CODEC: MapCodec<BreakpointSpec> = RecordCodecBuilder.mapCodec { instance ->
            instance.group(
                BlockPos.CODEC.fieldOf("pos").forGetter(BreakpointSpec::pos),
                Codec.STRING.fieldOf("label").forGetter(BreakpointSpec::label),
                Codec.INT.fieldOf("color").forGetter(BreakpointSpec::color),
                StateCondition.CODEC.optionalFieldOf("condition", DEFAULT_CONDITION)
                    .forGetter(BreakpointSpec::condition),
                Codec.BOOL.optionalFieldOf("enabled", true).forGetter(BreakpointSpec::enabled),
            ).apply(instance, ::BreakpointSpec)
        }
    }
}

data class AutoSpec(
    override val pos: BlockPos,
    override val label: String,
    override val color: Int,
    val condition: StateCondition = DEFAULT_CONDITION,
) : SpecEntry() {
    companion object {
        val MAP_CODEC: MapCodec<AutoSpec> = RecordCodecBuilder.mapCodec { instance ->
            instance.group(
                BlockPos.CODEC.fieldOf("pos").forGetter(AutoSpec::pos),
                Codec.STRING.fieldOf("label").forGetter(AutoSpec::label),
                Codec.INT.fieldOf("color").forGetter(AutoSpec::color),
                StateCondition.CODEC.optionalFieldOf("condition", DEFAULT_CONDITION)
                    .forGetter(AutoSpec::condition),
            ).apply(instance, ::AutoSpec)
        }
    }
}
