package com.breadmoirai.redstonespecs.dsl

import com.mojang.serialization.Codec
import com.mojang.serialization.MapCodec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.resources.Identifier
import java.util.Optional

sealed class StateCondition {

    data class BlockType(val blockId: Identifier) : StateCondition()
    data class BoolProperty(val name: String, val value: Boolean) : StateCondition()
    data class IntProperty(val name: String, val value: Int) : StateCondition()
    data class EnumProperty(val name: String, val value: String) : StateCondition()

    data class All(val conditions: List<StateCondition>) : StateCondition()
    data class Any(val conditions: List<StateCondition>) : StateCondition()
    data class Not(val condition: StateCondition) : StateCondition()
    data class ContainerContents(
        val slot: Int? = null,
        val item: Identifier? = null,
        val minCount: Int = 1,
    ) : StateCondition()
    data class IntRange(val name: String, val min: Int, val max: Int) : StateCondition()

    companion object {
        val CODEC: Codec<StateCondition> = Codec.lazyInitialized {
            val blockTypeCodec: MapCodec<BlockType> = RecordCodecBuilder.mapCodec { instance ->
                instance.group(
                    Identifier.CODEC.fieldOf("block").forGetter(BlockType::blockId),
                ).apply(instance, ::BlockType)
            }
            val boolPropertyCodec: MapCodec<BoolProperty> = RecordCodecBuilder.mapCodec { instance ->
                instance.group(
                    Codec.STRING.fieldOf("name").forGetter(BoolProperty::name),
                    Codec.BOOL.fieldOf("value").forGetter(BoolProperty::value),
                ).apply(instance, ::BoolProperty)
            }
            val intPropertyCodec: MapCodec<IntProperty> = RecordCodecBuilder.mapCodec { instance ->
                instance.group(
                    Codec.STRING.fieldOf("name").forGetter(IntProperty::name),
                    Codec.INT.fieldOf("value").forGetter(IntProperty::value),
                ).apply(instance, ::IntProperty)
            }
            val enumPropertyCodec: MapCodec<EnumProperty> = RecordCodecBuilder.mapCodec { instance ->
                instance.group(
                    Codec.STRING.fieldOf("name").forGetter(EnumProperty::name),
                    Codec.STRING.fieldOf("value").forGetter(EnumProperty::value),
                ).apply(instance, ::EnumProperty)
            }
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
            val containerContentsCodec: MapCodec<ContainerContents> = RecordCodecBuilder.mapCodec { instance ->
                instance.group(
                    Codec.INT.optionalFieldOf("slot").forGetter { Optional.ofNullable(it.slot) },
                    Identifier.CODEC.optionalFieldOf("item").forGetter { Optional.ofNullable(it.item) },
                    Codec.INT.optionalFieldOf("min_count", 1).forGetter(ContainerContents::minCount),
                ).apply(instance) { slot, item, minCount ->
                    ContainerContents(slot.orElse(null), item.orElse(null), minCount)
                }
            }
            val intRangeCodec: MapCodec<IntRange> = RecordCodecBuilder.mapCodec { instance ->
                instance.group(
                    Codec.STRING.fieldOf("name").forGetter(IntRange::name),
                    Codec.INT.fieldOf("min").forGetter(IntRange::min),
                    Codec.INT.fieldOf("max").forGetter(IntRange::max),
                ).apply(instance, ::IntRange)
            }

            val codecMap = mapOf<String, MapCodec<out StateCondition>>(
                "block_type" to blockTypeCodec,
                "bool_property" to boolPropertyCodec,
                "int_property" to intPropertyCodec,
                "enum_property" to enumPropertyCodec,
                "all" to allCodec,
                "any" to anyCodec,
                "not" to notCodec,
                "container_contents" to containerContentsCodec,
                "int_range" to intRangeCodec,
            )

            Codec.STRING.dispatch(
                "type",
                { condition: StateCondition ->
                    when (condition) {
                        is BlockType -> "block_type"
                        is BoolProperty -> "bool_property"
                        is IntProperty -> "int_property"
                        is EnumProperty -> "enum_property"
                        is All -> "all"
                        is Any -> "any"
                        is Not -> "not"
                        is ContainerContents -> "container_contents"
                        is IntRange -> "int_range"
                    }
                },
                { type: String ->
                    codecMap[type] ?: throw IllegalArgumentException("Unknown StateCondition type: $type")
                }
            )
        }
    }
}

val DEFAULT_CONDITION: StateCondition = StateCondition.BoolProperty("powered", true)
