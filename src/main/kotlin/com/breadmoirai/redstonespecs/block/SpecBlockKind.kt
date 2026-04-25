package com.breadmoirai.redstonespecs.block

import io.netty.buffer.ByteBuf
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec

enum class SpecBlockKind { RUNNER, EDITOR, RECORDER;
    companion object {
        val STREAM_CODEC: StreamCodec<ByteBuf, SpecBlockKind> =
            ByteBufCodecs.VAR_INT.map({ entries[it] }, SpecBlockKind::ordinal)
    }
}
