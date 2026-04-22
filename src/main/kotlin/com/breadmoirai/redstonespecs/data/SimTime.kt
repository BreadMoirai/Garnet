package com.breadmoirai.redstonespecs.data

import com.mojang.serialization.Codec
import com.mojang.serialization.DataResult
import com.mojang.serialization.codecs.RecordCodecBuilder
import io.netty.buffer.ByteBuf
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec

enum class Phase {
    START_OF_TICK, BLOCK_EVENTS, TILE_ENTITY_TICK, SCHEDULED_TICKS, RANDOM_TICKS, END_OF_TICK;

    companion object {
        val CODEC: Codec<Phase> = Codec.STRING.comapFlatMap(
            { str ->
                entries.find { it.name == str }
                    ?.let { DataResult.success(it) }
                    ?: DataResult.error { "Unknown phase: $str" }
            },
            Phase::name
        )
        val STREAM_CODEC: StreamCodec<ByteBuf, Phase> =
            ByteBufCodecs.VAR_INT.map({ entries[it] }, Phase::ordinal)
    }
}

data class SimTime(
    val tick: Int,
    val phase: Phase,
    val order: Int = 0,
) : Comparable<SimTime> {

    override fun compareTo(other: SimTime): Int =
        compareValuesBy(this, other, SimTime::tick, { it.phase.ordinal }, SimTime::order)

    companion object {
        val INIT = SimTime(-1, Phase.START_OF_TICK, 0)

        val CODEC: Codec<SimTime> = RecordCodecBuilder.create { instance ->
            instance.group(
                Codec.INT.fieldOf("tick").forGetter(SimTime::tick),
                Phase.CODEC.fieldOf("phase").forGetter(SimTime::phase),
                Codec.INT.optionalFieldOf("order", 0).forGetter(SimTime::order),
            ).apply(instance, ::SimTime)
        }

        val STREAM_CODEC: StreamCodec<ByteBuf, SimTime> = StreamCodec.composite(
            ByteBufCodecs.INT, SimTime::tick,
            Phase.STREAM_CODEC, SimTime::phase,
            ByteBufCodecs.INT, SimTime::order,
            ::SimTime,
        )
    }
}
