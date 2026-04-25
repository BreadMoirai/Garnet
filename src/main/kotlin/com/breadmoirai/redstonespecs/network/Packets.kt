package com.breadmoirai.redstonespecs.network

import com.breadmoirai.redstonespecs.data.SimTime
import com.breadmoirai.redstonespecs.data.SpecEntry
import com.breadmoirai.redstonespecs.data.SpecMode
import com.breadmoirai.redstonespecs.data.TestResult
import io.netty.buffer.ByteBuf
import net.minecraft.core.BlockPos
import net.minecraft.world.level.levelgen.structure.BoundingBox
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.Identifier

// === S2C ===

data class OpenOverviewS2CPayload(
    val originPos: BlockPos,
) : CustomPacketPayload {
    companion object {
        val TYPE = CustomPacketPayload.Type<OpenOverviewS2CPayload>(
            Identifier.fromNamespaceAndPath("redstonespecs", "open_overview")
        )
        val STREAM_CODEC: StreamCodec<ByteBuf, OpenOverviewS2CPayload> = StreamCodec.composite(
            BlockPos.STREAM_CODEC, OpenOverviewS2CPayload::originPos,
            ::OpenOverviewS2CPayload,
        )
    }
    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE
}

data class OpenEditorS2CPayload(
    val originPos: BlockPos,
    val entryRelPos: BlockPos,
) : CustomPacketPayload {
    companion object {
        val TYPE = CustomPacketPayload.Type<OpenEditorS2CPayload>(
            Identifier.fromNamespaceAndPath("redstonespecs", "open_editor")
        )
        val STREAM_CODEC: StreamCodec<ByteBuf, OpenEditorS2CPayload> = StreamCodec.composite(
            BlockPos.STREAM_CODEC, OpenEditorS2CPayload::originPos,
            BlockPos.STREAM_CODEC, OpenEditorS2CPayload::entryRelPos,
            ::OpenEditorS2CPayload,
        )
    }
    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE
}

data class TestResultS2CPayload(
    val originPos: BlockPos,
    val result: TestResult,
) : CustomPacketPayload {
    companion object {
        val TYPE = CustomPacketPayload.Type<TestResultS2CPayload>(
            Identifier.fromNamespaceAndPath("redstonespecs", "test_result")
        )
        val STREAM_CODEC: StreamCodec<ByteBuf, TestResultS2CPayload> = StreamCodec.composite(
            BlockPos.STREAM_CODEC, TestResultS2CPayload::originPos,
            ByteBufCodecs.fromCodec(TestResult.CODEC), TestResultS2CPayload::result,
            ::TestResultS2CPayload,
        )
    }
    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE
}

data class BreakpointHitS2CPayload(
    val originPos: BlockPos,
    val simTime: SimTime,
    val specId: String,
    val breakpointLabel: String,
) : CustomPacketPayload {
    companion object {
        val TYPE = CustomPacketPayload.Type<BreakpointHitS2CPayload>(
            Identifier.fromNamespaceAndPath("redstonespecs", "breakpoint_hit")
        )
        val STREAM_CODEC: StreamCodec<ByteBuf, BreakpointHitS2CPayload> = StreamCodec.composite(
            BlockPos.STREAM_CODEC, BreakpointHitS2CPayload::originPos,
            SimTime.STREAM_CODEC, BreakpointHitS2CPayload::simTime,
            ByteBufCodecs.STRING_UTF8, BreakpointHitS2CPayload::specId,
            ByteBufCodecs.STRING_UTF8, BreakpointHitS2CPayload::breakpointLabel,
            ::BreakpointHitS2CPayload,
        )
    }
    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE
}

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

data class ResumeSpecC2SPayload(val originPos: BlockPos) : CustomPacketPayload {
    companion object {
        val TYPE = CustomPacketPayload.Type<ResumeSpecC2SPayload>(
            Identifier.fromNamespaceAndPath("redstonespecs", "resume_spec")
        )
        val STREAM_CODEC: StreamCodec<ByteBuf, ResumeSpecC2SPayload> = StreamCodec.composite(
            BlockPos.STREAM_CODEC, ResumeSpecC2SPayload::originPos,
            ::ResumeSpecC2SPayload,
        )
    }
    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE
}

data class SaveSpecEntryC2SPayload(
    val originPos: BlockPos,
    val entry: SpecEntry,
) : CustomPacketPayload {
    companion object {
        val TYPE = CustomPacketPayload.Type<SaveSpecEntryC2SPayload>(
            Identifier.fromNamespaceAndPath("redstonespecs", "save_spec_entry")
        )
        val STREAM_CODEC: StreamCodec<ByteBuf, SaveSpecEntryC2SPayload> = StreamCodec.composite(
            BlockPos.STREAM_CODEC, SaveSpecEntryC2SPayload::originPos,
            ByteBufCodecs.fromCodec(SpecEntry.CODEC), SaveSpecEntryC2SPayload::entry,
            ::SaveSpecEntryC2SPayload,
        )
    }
    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE
}

data class RemoveSpecEntryC2SPayload(
    val originPos: BlockPos,
    val entryRelPos: BlockPos,
) : CustomPacketPayload {
    companion object {
        val TYPE = CustomPacketPayload.Type<RemoveSpecEntryC2SPayload>(
            Identifier.fromNamespaceAndPath("redstonespecs", "remove_spec_entry")
        )
        val STREAM_CODEC: StreamCodec<ByteBuf, RemoveSpecEntryC2SPayload> = StreamCodec.composite(
            BlockPos.STREAM_CODEC, RemoveSpecEntryC2SPayload::originPos,
            BlockPos.STREAM_CODEC, RemoveSpecEntryC2SPayload::entryRelPos,
            ::RemoveSpecEntryC2SPayload,
        )
    }
    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE
}

class UndoC2SPayload : CustomPacketPayload {
    companion object {
        val TYPE = CustomPacketPayload.Type<UndoC2SPayload>(
            Identifier.fromNamespaceAndPath("redstonespecs", "undo")
        )
        val STREAM_CODEC: StreamCodec<ByteBuf, UndoC2SPayload> =
            StreamCodec.unit(UndoC2SPayload())
    }
    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE
}

data class ResizeBoundsC2SPayload(
    val originPos: BlockPos,
    val bounds: BoundingBox,
) : CustomPacketPayload {
    companion object {
        val TYPE = CustomPacketPayload.Type<ResizeBoundsC2SPayload>(
            Identifier.fromNamespaceAndPath("redstonespecs", "resize_bounds")
        )
        val STREAM_CODEC: StreamCodec<ByteBuf, ResizeBoundsC2SPayload> = StreamCodec.composite(
            BlockPos.STREAM_CODEC, ResizeBoundsC2SPayload::originPos,
            ByteBufCodecs.fromCodec(BoundingBox.CODEC), ResizeBoundsC2SPayload::bounds,
            ::ResizeBoundsC2SPayload,
        )
    }
    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE
}

data class NudgeSpecBoundsC2SPayload(
    val originPos: BlockPos,
    val axis: Int,
    val isMax: Boolean,
    val delta: Int,
) : CustomPacketPayload {
    companion object {
        val TYPE = CustomPacketPayload.Type<NudgeSpecBoundsC2SPayload>(
            Identifier.fromNamespaceAndPath("redstonespecs", "nudge_spec_bounds")
        )
        val STREAM_CODEC: StreamCodec<ByteBuf, NudgeSpecBoundsC2SPayload> = StreamCodec.composite(
            BlockPos.STREAM_CODEC, NudgeSpecBoundsC2SPayload::originPos,
            ByteBufCodecs.VAR_INT, NudgeSpecBoundsC2SPayload::axis,
            ByteBufCodecs.BOOL, NudgeSpecBoundsC2SPayload::isMax,
            ByteBufCodecs.VAR_INT, NudgeSpecBoundsC2SPayload::delta,
            ::NudgeSpecBoundsC2SPayload,
        )
    }
    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE
}

data class SetSpecIdC2SPayload(val originPos: BlockPos, val id: String) : CustomPacketPayload {
    companion object {
        val TYPE = CustomPacketPayload.Type<SetSpecIdC2SPayload>(
            Identifier.fromNamespaceAndPath("redstonespecs", "set_spec_id")
        )
        val STREAM_CODEC: StreamCodec<ByteBuf, SetSpecIdC2SPayload> = StreamCodec.composite(
            BlockPos.STREAM_CODEC, SetSpecIdC2SPayload::originPos,
            ByteBufCodecs.STRING_UTF8, SetSpecIdC2SPayload::id,
            ::SetSpecIdC2SPayload,
        )
    }
    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE
}

data class SetSpecModeC2SPayload(val originPos: BlockPos, val mode: SpecMode) : CustomPacketPayload {
    companion object {
        val TYPE = CustomPacketPayload.Type<SetSpecModeC2SPayload>(
            Identifier.fromNamespaceAndPath("redstonespecs", "set_spec_mode")
        )
        val STREAM_CODEC: StreamCodec<ByteBuf, SetSpecModeC2SPayload> = StreamCodec.composite(
            BlockPos.STREAM_CODEC, SetSpecModeC2SPayload::originPos,
            ByteBufCodecs.VAR_INT.map({ SpecMode.entries[it] }, SpecMode::ordinal), SetSpecModeC2SPayload::mode,
            ::SetSpecModeC2SPayload,
        )
    }
    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE
}

data class SetLifespanC2SPayload(val originPos: BlockPos, val lifespan: Int) : CustomPacketPayload {
    companion object {
        val TYPE = CustomPacketPayload.Type<SetLifespanC2SPayload>(
            Identifier.fromNamespaceAndPath("redstonespecs", "set_lifespan")
        )
        val STREAM_CODEC: StreamCodec<ByteBuf, SetLifespanC2SPayload> = StreamCodec.composite(
            BlockPos.STREAM_CODEC, SetLifespanC2SPayload::originPos,
            ByteBufCodecs.VAR_INT, SetLifespanC2SPayload::lifespan,
            ::SetLifespanC2SPayload,
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

// === v1.2: File Browser ===

data class SpecFileInfo(
    val id: String,
    val mode: SpecMode,
    val lifespan: Int,
    val inputCount: Int,
    val outputCount: Int,
    val structure: String?,
) {
    companion object {
        val STREAM_CODEC: StreamCodec<ByteBuf, SpecFileInfo> = object : StreamCodec<ByteBuf, SpecFileInfo> {
            override fun decode(buf: ByteBuf): SpecFileInfo {
                val id = ByteBufCodecs.STRING_UTF8.decode(buf)
                val mode = SpecMode.entries[ByteBufCodecs.VAR_INT.decode(buf)]
                val lifespan = ByteBufCodecs.VAR_INT.decode(buf)
                val inputCount = ByteBufCodecs.VAR_INT.decode(buf)
                val outputCount = ByteBufCodecs.VAR_INT.decode(buf)
                val hasStructure = buf.readBoolean()
                val structure = if (hasStructure) ByteBufCodecs.STRING_UTF8.decode(buf) else null
                return SpecFileInfo(id, mode, lifespan, inputCount, outputCount, structure)
            }
            override fun encode(buf: ByteBuf, value: SpecFileInfo) {
                ByteBufCodecs.STRING_UTF8.encode(buf, value.id)
                ByteBufCodecs.VAR_INT.encode(buf, value.mode.ordinal)
                ByteBufCodecs.VAR_INT.encode(buf, value.lifespan)
                ByteBufCodecs.VAR_INT.encode(buf, value.inputCount)
                ByteBufCodecs.VAR_INT.encode(buf, value.outputCount)
                val s = value.structure
                buf.writeBoolean(s != null)
                if (s != null) ByteBufCodecs.STRING_UTF8.encode(buf, s)
            }
        }
    }
}

data class RequestFileBrowserC2SPayload(val originPos: BlockPos) : CustomPacketPayload {
    companion object {
        val TYPE = CustomPacketPayload.Type<RequestFileBrowserC2SPayload>(
            Identifier.fromNamespaceAndPath("redstonespecs", "request_file_browser")
        )
        val STREAM_CODEC: StreamCodec<ByteBuf, RequestFileBrowserC2SPayload> = StreamCodec.composite(
            BlockPos.STREAM_CODEC, RequestFileBrowserC2SPayload::originPos,
            ::RequestFileBrowserC2SPayload,
        )
    }
    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE
}

data class OpenFileBrowserS2CPayload(
    val originPos: BlockPos,
    val files: List<SpecFileInfo>,
) : CustomPacketPayload {
    companion object {
        val TYPE = CustomPacketPayload.Type<OpenFileBrowserS2CPayload>(
            Identifier.fromNamespaceAndPath("redstonespecs", "open_file_browser")
        )
        val STREAM_CODEC: StreamCodec<ByteBuf, OpenFileBrowserS2CPayload> = StreamCodec.composite(
            BlockPos.STREAM_CODEC, OpenFileBrowserS2CPayload::originPos,
            SpecFileInfo.STREAM_CODEC.apply(ByteBufCodecs.list()), OpenFileBrowserS2CPayload::files,
            ::OpenFileBrowserS2CPayload,
        )
    }
    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE
}

data class LoadFromFileC2SPayload(val originPos: BlockPos, val specId: String) : CustomPacketPayload {
    companion object {
        val TYPE = CustomPacketPayload.Type<LoadFromFileC2SPayload>(
            Identifier.fromNamespaceAndPath("redstonespecs", "load_from_file")
        )
        val STREAM_CODEC: StreamCodec<ByteBuf, LoadFromFileC2SPayload> = StreamCodec.composite(
            BlockPos.STREAM_CODEC, LoadFromFileC2SPayload::originPos,
            ByteBufCodecs.STRING_UTF8, LoadFromFileC2SPayload::specId,
            ::LoadFromFileC2SPayload,
        )
    }
    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE
}

data class TransformToRunnerC2SPayload(val originPos: BlockPos) : CustomPacketPayload {
    companion object {
        val TYPE = CustomPacketPayload.Type<TransformToRunnerC2SPayload>(
            Identifier.fromNamespaceAndPath("redstonespecs", "transform_to_runner")
        )
        val STREAM_CODEC: StreamCodec<ByteBuf, TransformToRunnerC2SPayload> = StreamCodec.composite(
            BlockPos.STREAM_CODEC, TransformToRunnerC2SPayload::originPos,
            ::TransformToRunnerC2SPayload,
        )
    }
    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE
}

data class TransformToRecorderC2SPayload(val originPos: BlockPos) : CustomPacketPayload {
    companion object {
        val TYPE = CustomPacketPayload.Type<TransformToRecorderC2SPayload>(
            Identifier.fromNamespaceAndPath("redstonespecs", "transform_to_recorder")
        )
        val STREAM_CODEC: StreamCodec<ByteBuf, TransformToRecorderC2SPayload> = StreamCodec.composite(
            BlockPos.STREAM_CODEC, TransformToRecorderC2SPayload::originPos,
            ::TransformToRecorderC2SPayload,
        )
    }
    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE
}

data class TransformToEditorC2SPayload(val originPos: BlockPos) : CustomPacketPayload {
    companion object {
        val TYPE = CustomPacketPayload.Type<TransformToEditorC2SPayload>(
            Identifier.fromNamespaceAndPath("redstonespecs", "transform_to_editor")
        )
        val STREAM_CODEC: StreamCodec<ByteBuf, TransformToEditorC2SPayload> = StreamCodec.composite(
            BlockPos.STREAM_CODEC, TransformToEditorC2SPayload::originPos,
            ::TransformToEditorC2SPayload,
        )
    }
    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE
}
