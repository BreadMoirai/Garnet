package com.breadmoirai.garnet.camera.network

import com.breadmoirai.garnet.editor.network.payloadId
import io.netty.buffer.ByteBuf
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload

/**
 * Enter or leave camera mode. The *only* thing the orbit camera asks the server for: the client
 * owns the camera outright and moves its own player, which the server accepts because
 * `ServerGamePacketListenerImpl.handleMovePlayer` exempts spectators from both its distance check
 * and its collision check. Sending orbit deltas instead would put a round trip inside every drag
 * frame.
 */
data class CameraModeC2S(val enter: Boolean) : CustomPacketPayload {
    companion object {
        val TYPE = CustomPacketPayload.Type<CameraModeC2S>(payloadId("camera_mode"))
        val STREAM_CODEC: StreamCodec<ByteBuf, CameraModeC2S> = StreamCodec.composite(
            ByteBufCodecs.BOOL, CameraModeC2S::enter,
            ::CameraModeC2S,
        )
    }
    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE
}
