package com.breadmoirai.redstonespecs.network

import io.netty.buffer.ByteBuf
import net.minecraft.core.BlockPos
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.Identifier

// === S2C ===

// Server asks: non-air blocks in bounds — overwrite?
data class OverwritePromptS2CPayload(val originPos: BlockPos, val specId: String) : CustomPacketPayload {
    companion object {
        val TYPE = CustomPacketPayload.Type<OverwritePromptS2CPayload>(
            Identifier.fromNamespaceAndPath("redstonespecs", "overwrite_prompt")
        )
        val STREAM_CODEC: StreamCodec<ByteBuf, OverwritePromptS2CPayload> = StreamCodec.composite(
            BlockPos.STREAM_CODEC, OverwritePromptS2CPayload::originPos,
            ByteBufCodecs.STRING_UTF8, OverwritePromptS2CPayload::specId,
            ::OverwritePromptS2CPayload,
        )
    }
    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE
}

// === C2S ===

data class RunSpecC2SPayload(val originPos: BlockPos) : CustomPacketPayload {
    companion object {
        val TYPE = CustomPacketPayload.Type<RunSpecC2SPayload>(
            Identifier.fromNamespaceAndPath("redstonespecs", "run_spec")
        )
        val STREAM_CODEC: StreamCodec<ByteBuf, RunSpecC2SPayload> = StreamCodec.composite(
            BlockPos.STREAM_CODEC, RunSpecC2SPayload::originPos,
            ::RunSpecC2SPayload,
        )
    }
    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE
}

data class ResetSpecC2SPayload(val originPos: BlockPos) : CustomPacketPayload {
    companion object {
        val TYPE = CustomPacketPayload.Type<ResetSpecC2SPayload>(
            Identifier.fromNamespaceAndPath("redstonespecs", "reset_spec")
        )
        val STREAM_CODEC: StreamCodec<ByteBuf, ResetSpecC2SPayload> = StreamCodec.composite(
            BlockPos.STREAM_CODEC, ResetSpecC2SPayload::originPos,
            ::ResetSpecC2SPayload,
        )
    }
    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE
}

data class SetStructureC2SPayload(val originPos: BlockPos, val structure: String?) : CustomPacketPayload {
    companion object {
        val TYPE = CustomPacketPayload.Type<SetStructureC2SPayload>(
            Identifier.fromNamespaceAndPath("redstonespecs", "set_structure")
        )
        val STREAM_CODEC: StreamCodec<ByteBuf, SetStructureC2SPayload> = object : StreamCodec<ByteBuf, SetStructureC2SPayload> {
            override fun decode(buf: ByteBuf): SetStructureC2SPayload {
                val pos = BlockPos.STREAM_CODEC.decode(buf)
                val hasStructure = buf.readBoolean()
                val structure = if (hasStructure) ByteBufCodecs.STRING_UTF8.decode(buf) else null
                return SetStructureC2SPayload(pos, structure)
            }
            override fun encode(buf: ByteBuf, value: SetStructureC2SPayload) {
                BlockPos.STREAM_CODEC.encode(buf, value.originPos)
                val s = value.structure
                buf.writeBoolean(s != null)
                if (s != null) ByteBufCodecs.STRING_UTF8.encode(buf, s)
            }
        }
    }
    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE
}

// Client response to OverwritePromptS2CPayload
data class OverwriteDecisionC2SPayload(val originPos: BlockPos, val overwrite: Boolean) : CustomPacketPayload {
    companion object {
        val TYPE = CustomPacketPayload.Type<OverwriteDecisionC2SPayload>(
            Identifier.fromNamespaceAndPath("redstonespecs", "overwrite_decision")
        )
        val STREAM_CODEC: StreamCodec<ByteBuf, OverwriteDecisionC2SPayload> = StreamCodec.composite(
            BlockPos.STREAM_CODEC, OverwriteDecisionC2SPayload::originPos,
            ByteBufCodecs.BOOL, OverwriteDecisionC2SPayload::overwrite,
            ::OverwriteDecisionC2SPayload,
        )
    }
    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE
}

// === v2.0: Slim Recorder / Runner packets ===

// --- Recorder C2S ---

data class SetRecorderConfigC2S(
    val originPos: BlockPos,
    val specId: String,
    val outPath: String,
    val structureId: String,
) : CustomPacketPayload {
    companion object {
        val TYPE = CustomPacketPayload.Type<SetRecorderConfigC2S>(
            Identifier.fromNamespaceAndPath("redstonespecs", "set_recorder_config_c2s")
        )
        val STREAM_CODEC: StreamCodec<ByteBuf, SetRecorderConfigC2S> = StreamCodec.composite(
            BlockPos.STREAM_CODEC, SetRecorderConfigC2S::originPos,
            ByteBufCodecs.STRING_UTF8, SetRecorderConfigC2S::specId,
            ByteBufCodecs.STRING_UTF8, SetRecorderConfigC2S::outPath,
            ByteBufCodecs.STRING_UTF8, SetRecorderConfigC2S::structureId,
            ::SetRecorderConfigC2S,
        )
    }
    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE
}

enum class RecorderCmd { START, STOP, DISCARD }

data class RecorderCommandC2S(val originPos: BlockPos, val cmd: RecorderCmd) : CustomPacketPayload {
    companion object {
        val TYPE = CustomPacketPayload.Type<RecorderCommandC2S>(
            Identifier.fromNamespaceAndPath("redstonespecs", "recorder_command_c2s")
        )
        val STREAM_CODEC: StreamCodec<ByteBuf, RecorderCommandC2S> = object : StreamCodec<ByteBuf, RecorderCommandC2S> {
            override fun decode(buf: ByteBuf): RecorderCommandC2S {
                val pos = BlockPos.STREAM_CODEC.decode(buf)
                val cmd = RecorderCmd.entries[ByteBufCodecs.VAR_INT.decode(buf)]
                return RecorderCommandC2S(pos, cmd)
            }
            override fun encode(buf: ByteBuf, value: RecorderCommandC2S) {
                BlockPos.STREAM_CODEC.encode(buf, value.originPos)
                ByteBufCodecs.VAR_INT.encode(buf, value.cmd.ordinal)
            }
        }
    }
    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE
}

// --- Runner C2S ---

data class SetRunnerConfigC2S(val originPos: BlockPos, val specPath: String) : CustomPacketPayload {
    companion object {
        val TYPE = CustomPacketPayload.Type<SetRunnerConfigC2S>(
            Identifier.fromNamespaceAndPath("redstonespecs", "set_runner_config_c2s")
        )
        val STREAM_CODEC: StreamCodec<ByteBuf, SetRunnerConfigC2S> = StreamCodec.composite(
            BlockPos.STREAM_CODEC, SetRunnerConfigC2S::originPos,
            ByteBufCodecs.STRING_UTF8, SetRunnerConfigC2S::specPath,
            ::SetRunnerConfigC2S,
        )
    }
    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE
}

enum class RunnerCmd { PLACE_STRUCTURE, RUN, RESTORE }

data class RunnerCommandC2S(val originPos: BlockPos, val cmd: RunnerCmd) : CustomPacketPayload {
    companion object {
        val TYPE = CustomPacketPayload.Type<RunnerCommandC2S>(
            Identifier.fromNamespaceAndPath("redstonespecs", "runner_command_c2s")
        )
        val STREAM_CODEC: StreamCodec<ByteBuf, RunnerCommandC2S> = object : StreamCodec<ByteBuf, RunnerCommandC2S> {
            override fun decode(buf: ByteBuf): RunnerCommandC2S {
                val pos = BlockPos.STREAM_CODEC.decode(buf)
                val cmd = RunnerCmd.entries[ByteBufCodecs.VAR_INT.decode(buf)]
                return RunnerCommandC2S(pos, cmd)
            }
            override fun encode(buf: ByteBuf, value: RunnerCommandC2S) {
                BlockPos.STREAM_CODEC.encode(buf, value.originPos)
                ByteBufCodecs.VAR_INT.encode(buf, value.cmd.ordinal)
            }
        }
    }
    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE
}

// --- S2C ---

data class OpenRecorderScreenS2C(
    val originPos: BlockPos,
    val specId: String,
    val outPath: String,
    val structureId: String,
    val state: String,
) : CustomPacketPayload {
    companion object {
        val TYPE = CustomPacketPayload.Type<OpenRecorderScreenS2C>(
            Identifier.fromNamespaceAndPath("redstonespecs", "open_recorder_screen_s2c")
        )
        val STREAM_CODEC: StreamCodec<ByteBuf, OpenRecorderScreenS2C> = StreamCodec.composite(
            BlockPos.STREAM_CODEC, OpenRecorderScreenS2C::originPos,
            ByteBufCodecs.STRING_UTF8, OpenRecorderScreenS2C::specId,
            ByteBufCodecs.STRING_UTF8, OpenRecorderScreenS2C::outPath,
            ByteBufCodecs.STRING_UTF8, OpenRecorderScreenS2C::structureId,
            ByteBufCodecs.STRING_UTF8, OpenRecorderScreenS2C::state,
            ::OpenRecorderScreenS2C,
        )
    }
    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE
}

data class RunnerMetaSnapshot(
    val id: String,
    val boundsX: Int,
    val boundsY: Int,
    val boundsZ: Int,
    val lifespan: Int,
    val structure: String?,
) {
    companion object {
        val STREAM_CODEC: StreamCodec<ByteBuf, RunnerMetaSnapshot> = object : StreamCodec<ByteBuf, RunnerMetaSnapshot> {
            override fun decode(buf: ByteBuf): RunnerMetaSnapshot {
                val id = ByteBufCodecs.STRING_UTF8.decode(buf)
                val boundsX = ByteBufCodecs.VAR_INT.decode(buf)
                val boundsY = ByteBufCodecs.VAR_INT.decode(buf)
                val boundsZ = ByteBufCodecs.VAR_INT.decode(buf)
                val lifespan = ByteBufCodecs.VAR_INT.decode(buf)
                val hasStructure = buf.readBoolean()
                val structure = if (hasStructure) ByteBufCodecs.STRING_UTF8.decode(buf) else null
                return RunnerMetaSnapshot(id, boundsX, boundsY, boundsZ, lifespan, structure)
            }
            override fun encode(buf: ByteBuf, value: RunnerMetaSnapshot) {
                ByteBufCodecs.STRING_UTF8.encode(buf, value.id)
                ByteBufCodecs.VAR_INT.encode(buf, value.boundsX)
                ByteBufCodecs.VAR_INT.encode(buf, value.boundsY)
                ByteBufCodecs.VAR_INT.encode(buf, value.boundsZ)
                ByteBufCodecs.VAR_INT.encode(buf, value.lifespan)
                val s = value.structure
                buf.writeBoolean(s != null)
                if (s != null) ByteBufCodecs.STRING_UTF8.encode(buf, s)
            }
        }
    }
}

data class OpenRunnerScreenS2C(
    val originPos: BlockPos,
    val specPath: String,
    val specList: List<String>,
    val meta: RunnerMetaSnapshot?,
) : CustomPacketPayload {
    companion object {
        val TYPE = CustomPacketPayload.Type<OpenRunnerScreenS2C>(
            Identifier.fromNamespaceAndPath("redstonespecs", "open_runner_screen_s2c")
        )
        val STREAM_CODEC: StreamCodec<ByteBuf, OpenRunnerScreenS2C> = object : StreamCodec<ByteBuf, OpenRunnerScreenS2C> {
            private val specListCodec = ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list())
            override fun decode(buf: ByteBuf): OpenRunnerScreenS2C {
                val pos = BlockPos.STREAM_CODEC.decode(buf)
                val specPath = ByteBufCodecs.STRING_UTF8.decode(buf)
                val specList = specListCodec.decode(buf)
                val hasMeta = buf.readBoolean()
                val meta = if (hasMeta) RunnerMetaSnapshot.STREAM_CODEC.decode(buf) else null
                return OpenRunnerScreenS2C(pos, specPath, specList, meta)
            }
            override fun encode(buf: ByteBuf, value: OpenRunnerScreenS2C) {
                BlockPos.STREAM_CODEC.encode(buf, value.originPos)
                ByteBufCodecs.STRING_UTF8.encode(buf, value.specPath)
                specListCodec.encode(buf, value.specList)
                val m = value.meta
                buf.writeBoolean(m != null)
                if (m != null) RunnerMetaSnapshot.STREAM_CODEC.encode(buf, m)
            }
        }
    }
    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE
}

enum class RunnerState { IDLE, RUNNING, PASS, FAIL }

data class RunnerStatusS2C(
    val originPos: BlockPos,
    val state: RunnerState,
    val summary: String,
) : CustomPacketPayload {
    companion object {
        val TYPE = CustomPacketPayload.Type<RunnerStatusS2C>(
            Identifier.fromNamespaceAndPath("redstonespecs", "runner_status_s2c")
        )
        val STREAM_CODEC: StreamCodec<ByteBuf, RunnerStatusS2C> = object : StreamCodec<ByteBuf, RunnerStatusS2C> {
            override fun decode(buf: ByteBuf): RunnerStatusS2C {
                val pos = BlockPos.STREAM_CODEC.decode(buf)
                val state = RunnerState.entries[ByteBufCodecs.VAR_INT.decode(buf)]
                val summary = ByteBufCodecs.STRING_UTF8.decode(buf)
                return RunnerStatusS2C(pos, state, summary)
            }
            override fun encode(buf: ByteBuf, value: RunnerStatusS2C) {
                BlockPos.STREAM_CODEC.encode(buf, value.originPos)
                ByteBufCodecs.VAR_INT.encode(buf, value.state.ordinal)
                ByteBufCodecs.STRING_UTF8.encode(buf, value.summary)
            }
        }
    }
    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE
}
