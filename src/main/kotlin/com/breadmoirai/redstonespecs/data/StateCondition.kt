package com.breadmoirai.redstonespecs.data

import com.mojang.serialization.Codec
import com.mojang.serialization.MapCodec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.resources.Identifier
import java.util.Optional

sealed class StateCondition {

    data class All(val conditions: List<StateCondition>) : StateCondition()
    data class Any(val conditions: List<StateCondition>) : StateCondition()
    data class Not(val condition: StateCondition) : StateCondition()
    data class BlockState(val properties: Map<String, String>) : StateCondition()
    data class ContainerContents(
        val slot: Int? = null,
        val item: Identifier? = null,
        val minCount: Int = 1,
    ) : StateCondition()

    companion object {
        val CODEC: Codec<StateCondition> = Codec.lazyInitialized {
            val allCodec: MapCodec<All> = RecordCodecBuilder.mapCodec { instance ->
                instance.group(
                    CODEC.listOf().fieldOf("conditions").forGetter(All::conditions)
                ).apply(instance, ::All)
            }
            val anyCodec: MapCodec<Any> = RecordCodecBuilder.mapCodec { instance ->
                instance.group(
                    CODEC.listOf().fieldOf("conditions").forGetter(Any::conditions)
                ).apply(instance, ::Any)
            }
            val notCodec: MapCodec<Not> = RecordCodecBuilder.mapCodec { instance ->
                instance.group(
                    CODEC.fieldOf("condition").forGetter(Not::condition)
                ).apply(instance, ::Not)
            }
            val blockStateCodec: MapCodec<BlockState> = RecordCodecBuilder.mapCodec { instance ->
                instance.group(
                    Codec.unboundedMap(Codec.STRING, Codec.STRING)
                        .fieldOf("properties").forGetter(BlockState::properties)
                ).apply(instance, ::BlockState)
            }
            val containerContentsCodec: MapCodec<ContainerContents> = RecordCodecBuilder.mapCodec { instance ->
                instance.group(
                    Codec.INT.optionalFieldOf("slot")
                        .forGetter { Optional.ofNullable(it.slot) },
                    Identifier.CODEC.optionalFieldOf("item")
                        .forGetter { Optional.ofNullable(it.item) },
                    Codec.INT.optionalFieldOf("min_count", 1)
                        .forGetter(ContainerContents::minCount),
                ).apply(instance) { slot, item, minCount ->
                    ContainerContents(slot.orElse(null), item.orElse(null), minCount)
                }
            }

            val codecMap = mapOf<String, MapCodec<out StateCondition>>(
                "all" to allCodec,
                "any" to anyCodec,
                "not" to notCodec,
                "block_state" to blockStateCodec,
                "container_contents" to containerContentsCodec,
            )

            Codec.STRING.dispatch(
                "type",
                { condition: StateCondition ->
                    when (condition) {
                        is All -> "all"
                        is Any -> "any"
                        is Not -> "not"
                        is BlockState -> "block_state"
                        is ContainerContents -> "container_contents"
                    }
                },
                { type: String ->
                    codecMap[type] ?: throw IllegalArgumentException("Unknown StateCondition type: $type")
                }
            )
        }
    }
}

val DEFAULT_CONDITION: StateCondition = StateCondition.BlockState(mapOf("powered" to "true"))
