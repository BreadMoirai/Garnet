package com.breadmoirai.redstonespecs.data

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder

@JvmInline
value class StateSpec(val entries: List<Pair<SimTime, Map<String, String>>>) {

    init {
        require(entries.any { it.first == SimTime.INIT }) {
            "StateSpec must contain a SimTime.INIT entry defining the initial state"
        }
    }

    companion object {
        private val ENTRY_CODEC: Codec<Pair<SimTime, Map<String, String>>> =
            RecordCodecBuilder.create { instance ->
                instance.group(
                    SimTime.CODEC.fieldOf("time").forGetter { it.first },
                    Codec.unboundedMap(Codec.STRING, Codec.STRING).fieldOf("properties").forGetter { it.second },
                ).apply(instance) { time, props -> time to props }
            }

        val CODEC: Codec<StateSpec> = ENTRY_CODEC.listOf().xmap(::StateSpec, StateSpec::entries)
    }
}
