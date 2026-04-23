package com.breadmoirai.redstonespecs.data

import com.mojang.serialization.Codec
import com.mojang.serialization.DataResult

enum class SpecMode {
    SIMPLE, TICK_AWARE, UPDATE_AWARE;

    companion object {
        val CODEC: Codec<SpecMode> = Codec.STRING.comapFlatMap(
            { str ->
                entries.find { it.name == str }
                    ?.let { DataResult.success(it) }
                    ?: DataResult.error { "Unknown SpecMode: $str" }
            },
            SpecMode::name,
        )
    }
}
