package com.breadmoirai.redstonespecs.data

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.core.BlockPos
import net.minecraft.world.level.levelgen.structure.BoundingBox
import java.util.Optional

data class RedstoneSpec(
    val id: String,
    val mode: SpecMode,
    val bounds: BoundingBox,
    val lifespan: Int,
    val structure: String?,
    val inputs: List<InputSpec>,
    val outputs: List<OutputSpec>,
    val breakpoints: List<BreakpointSpec>,
    val autoSpecs: List<AutoSpec>,
) {
    val allEntries: List<SpecEntry> get() = inputs + outputs + breakpoints + autoSpecs

    fun entryAt(pos: BlockPos): SpecEntry? = allEntries.find { it.pos == pos }

    fun withEntryAddedOrUpdated(entry: SpecEntry): RedstoneSpec = when (entry) {
        is InputSpec -> copy(inputs = inputs.filter { it.pos != entry.pos } + entry)
        is OutputSpec -> copy(outputs = outputs.filter { it.pos != entry.pos } + entry)
        is BreakpointSpec -> copy(breakpoints = breakpoints.filter { it.pos != entry.pos } + entry)
        is AutoSpec -> copy(autoSpecs = autoSpecs.filter { it.pos != entry.pos } + entry)
    }

    fun withEntryRemoved(pos: BlockPos): RedstoneSpec = copy(
        inputs = inputs.filter { it.pos != pos },
        outputs = outputs.filter { it.pos != pos },
        breakpoints = breakpoints.filter { it.pos != pos },
        autoSpecs = autoSpecs.filter { it.pos != pos },
    )

    companion object {
        val DEFAULT_BOUNDS = BoundingBox(1, 0, 1, 5, 4, 5)

        fun new(id: String) = RedstoneSpec(
            id, SpecMode.SIMPLE, DEFAULT_BOUNDS, 20, null,
            emptyList(), emptyList(), emptyList(), emptyList(),
        )

        val CODEC: Codec<RedstoneSpec> = RecordCodecBuilder.create { instance ->
            instance.group(
                Codec.STRING.fieldOf("id").forGetter(RedstoneSpec::id),
                SpecMode.CODEC.optionalFieldOf("mode", SpecMode.SIMPLE).forGetter(RedstoneSpec::mode),
                BoundingBox.CODEC.fieldOf("bounds").forGetter(RedstoneSpec::bounds),
                Codec.INT.optionalFieldOf("lifespan", 20).forGetter(RedstoneSpec::lifespan),
                Codec.STRING.optionalFieldOf("structure").forGetter { Optional.ofNullable(it.structure) },
                InputSpec.MAP_CODEC.codec().listOf().optionalFieldOf("inputs", emptyList())
                    .forGetter(RedstoneSpec::inputs),
                OutputSpec.MAP_CODEC.codec().listOf().optionalFieldOf("outputs", emptyList())
                    .forGetter(RedstoneSpec::outputs),
                BreakpointSpec.MAP_CODEC.codec().listOf().optionalFieldOf("breakpoints", emptyList())
                    .forGetter(RedstoneSpec::breakpoints),
                AutoSpec.MAP_CODEC.codec().listOf().optionalFieldOf("auto_specs", emptyList())
                    .forGetter(RedstoneSpec::autoSpecs),
            ).apply(instance) { id, mode, bounds, lifespan, structure, inputs, outputs, breakpoints, autoSpecs ->
                RedstoneSpec(id, mode, bounds, lifespan, structure.orElse(null),
                    inputs, outputs, breakpoints, autoSpecs)
            }
        }
    }
}
