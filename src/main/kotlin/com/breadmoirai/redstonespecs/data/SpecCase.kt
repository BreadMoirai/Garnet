package com.breadmoirai.redstonespecs.data

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder

data class SpecCase(
    val name: String,
    val lifespan: Int,
    val inputs: List<InputSpec>,
    val outputs: List<OutputSpec>,
    val breakpoints: List<BreakpointSpec>,
    val autoSpecs: List<AutoSpec>,
) {
    val allEntries: List<SpecEntry> get() = inputs + outputs + breakpoints + autoSpecs

    fun entryAt(pos: net.minecraft.core.BlockPos): SpecEntry? = allEntries.find { it.pos == pos }

    fun withEntryAddedOrUpdated(entry: SpecEntry): SpecCase = when (entry) {
        is InputSpec -> copy(inputs = inputs.filter { it.pos != entry.pos } + entry)
        is OutputSpec -> copy(outputs = outputs.filter { it.pos != entry.pos } + entry)
        is BreakpointSpec -> copy(breakpoints = breakpoints.filter { it.pos != entry.pos } + entry)
        is AutoSpec -> copy(autoSpecs = autoSpecs.filter { it.pos != entry.pos } + entry)
    }

    fun withEntryRemoved(pos: net.minecraft.core.BlockPos): SpecCase = copy(
        inputs = inputs.filter { it.pos != pos },
        outputs = outputs.filter { it.pos != pos },
        breakpoints = breakpoints.filter { it.pos != pos },
        autoSpecs = autoSpecs.filter { it.pos != pos },
    )
    companion object {
        val CODEC: Codec<SpecCase> = RecordCodecBuilder.create { instance ->
            instance.group(
                Codec.STRING.fieldOf("name").forGetter(SpecCase::name),
                Codec.INT.fieldOf("lifespan").forGetter(SpecCase::lifespan),
                InputSpec.MAP_CODEC.codec().listOf()
                    .optionalFieldOf("inputs", emptyList()).forGetter(SpecCase::inputs),
                OutputSpec.MAP_CODEC.codec().listOf()
                    .optionalFieldOf("outputs", emptyList()).forGetter(SpecCase::outputs),
                BreakpointSpec.MAP_CODEC.codec().listOf()
                    .optionalFieldOf("breakpoints", emptyList()).forGetter(SpecCase::breakpoints),
                AutoSpec.MAP_CODEC.codec().listOf()
                    .optionalFieldOf("auto_specs", emptyList()).forGetter(SpecCase::autoSpecs),
            ).apply(instance, ::SpecCase)
        }
    }
}
