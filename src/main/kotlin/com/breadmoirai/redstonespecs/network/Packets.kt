package com.breadmoirai.redstonespecs.network

import com.breadmoirai.redstonespecs.block.SpecBlockKind
import com.breadmoirai.redstonespecs.data.SpecEntry
import com.breadmoirai.redstonespecs.data.TestResult
import com.breadmoirai.redstonespecs.data.serial.SpecJsonCodec
import io.netty.buffer.ByteBuf
import net.minecraft.core.BlockPos
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.Identifier

// === S2C ===

data class OpenOverviewS2CPayload(
    val originPos: BlockPos,
    val kind: SpecBlockKind,
) : CustomPacketPayload {
    companion object {
        val TYPE = CustomPacketPayload.Type<OpenOverviewS2CPayload>(
            Identifier.fromNamespaceAndPath("redstonespecs", "open_overview")
        )
        val STREAM_CODEC: StreamCodec<ByteBuf, OpenOverviewS2CPayload> = StreamCodec.composite(
            BlockPos.STREAM_CODEC, OpenOverviewS2CPayload::originPos,
            SpecBlockKind.STREAM_CODEC, OpenOverviewS2CPayload::kind,
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
            ByteBufCodecs.fromCodec(SpecJsonCodec.ENTRY), SaveSpecEntryC2SPayload::entry,
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
    val sizeX: Int,
    val sizeY: Int,
    val sizeZ: Int,
) : CustomPacketPayload {
    companion object {
        val TYPE = CustomPacketPayload.Type<ResizeBoundsC2SPayload>(
            Identifier.fromNamespaceAndPath("redstonespecs", "resize_bounds")
        )
        val STREAM_CODEC: StreamCodec<ByteBuf, ResizeBoundsC2SPayload> = StreamCodec.composite(
            BlockPos.STREAM_CODEC, ResizeBoundsC2SPayload::originPos,
            ByteBufCodecs.VAR_INT, ResizeBoundsC2SPayload::sizeX,
            ByteBufCodecs.VAR_INT, ResizeBoundsC2SPayload::sizeY,
            ByteBufCodecs.VAR_INT, ResizeBoundsC2SPayload::sizeZ,
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
    val lifespan: Int,
    val inputCount: Int,
    val outputCount: Int,
    val structure: String?,
) {
    companion object {
        val STREAM_CODEC: StreamCodec<ByteBuf, SpecFileInfo> = object : StreamCodec<ByteBuf, SpecFileInfo> {
            override fun decode(buf: ByteBuf): SpecFileInfo {
                val id = ByteBufCodecs.STRING_UTF8.decode(buf)
                val lifespan = ByteBufCodecs.VAR_INT.decode(buf)
                val inputCount = ByteBufCodecs.VAR_INT.decode(buf)
                val outputCount = ByteBufCodecs.VAR_INT.decode(buf)
                val hasStructure = buf.readBoolean()
                val structure = if (hasStructure) ByteBufCodecs.STRING_UTF8.decode(buf) else null
                return SpecFileInfo(id, lifespan, inputCount, outputCount, structure)
            }
            override fun encode(buf: ByteBuf, value: SpecFileInfo) {
                ByteBufCodecs.STRING_UTF8.encode(buf, value.id)
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

// === v1.3: Runner spec picker ===

data class OpenRunnerPickerS2CPayload(
    val originPos: BlockPos,
    val files: List<SpecFileInfo>,
) : CustomPacketPayload {
    companion object {
        val TYPE = CustomPacketPayload.Type<OpenRunnerPickerS2CPayload>(
            Identifier.fromNamespaceAndPath("redstonespecs", "open_runner_picker")
        )
        val STREAM_CODEC: StreamCodec<ByteBuf, OpenRunnerPickerS2CPayload> = StreamCodec.composite(
            BlockPos.STREAM_CODEC, OpenRunnerPickerS2CPayload::originPos,
            SpecFileInfo.STREAM_CODEC.apply(ByteBufCodecs.list()), OpenRunnerPickerS2CPayload::files,
            ::OpenRunnerPickerS2CPayload,
        )
    }
    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE
}

data class RunnerLoadSpecC2SPayload(val originPos: BlockPos, val specId: String) : CustomPacketPayload {
    companion object {
        val TYPE = CustomPacketPayload.Type<RunnerLoadSpecC2SPayload>(
            Identifier.fromNamespaceAndPath("redstonespecs", "runner_load_spec")
        )
        val STREAM_CODEC: StreamCodec<ByteBuf, RunnerLoadSpecC2SPayload> = StreamCodec.composite(
            BlockPos.STREAM_CODEC, RunnerLoadSpecC2SPayload::originPos,
            ByteBufCodecs.STRING_UTF8, RunnerLoadSpecC2SPayload::specId,
            ::RunnerLoadSpecC2SPayload,
        )
    }
    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE
}

// === v1.5: Timeline screen ===

data class OpenTimelineS2CPayload(val runnerPos: BlockPos) : CustomPacketPayload {
    companion object {
        val TYPE = CustomPacketPayload.Type<OpenTimelineS2CPayload>(
            Identifier.fromNamespaceAndPath("redstonespecs", "open_timeline")
        )
        val STREAM_CODEC: StreamCodec<ByteBuf, OpenTimelineS2CPayload> = StreamCodec.composite(
            BlockPos.STREAM_CODEC, OpenTimelineS2CPayload::runnerPos,
            ::OpenTimelineS2CPayload,
        )
    }
    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE
}

// === v1.4: Recorder screen ===

data class OpenRecorderS2CPayload(
    val originPos: BlockPos,
    val isRecording: Boolean,
) : CustomPacketPayload {
    companion object {
        val TYPE = CustomPacketPayload.Type<OpenRecorderS2CPayload>(
            Identifier.fromNamespaceAndPath("redstonespecs", "open_recorder")
        )
        val STREAM_CODEC: StreamCodec<ByteBuf, OpenRecorderS2CPayload> = StreamCodec.composite(
            BlockPos.STREAM_CODEC, OpenRecorderS2CPayload::originPos,
            ByteBufCodecs.BOOL, OpenRecorderS2CPayload::isRecording,
            ::OpenRecorderS2CPayload,
        )
    }
    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE
}

data class StartRecordingC2SPayload(val originPos: BlockPos) : CustomPacketPayload {
    companion object {
        val TYPE = CustomPacketPayload.Type<StartRecordingC2SPayload>(
            Identifier.fromNamespaceAndPath("redstonespecs", "start_recording")
        )
        val STREAM_CODEC: StreamCodec<ByteBuf, StartRecordingC2SPayload> = StreamCodec.composite(
            BlockPos.STREAM_CODEC, StartRecordingC2SPayload::originPos,
            ::StartRecordingC2SPayload,
        )
    }
    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE
}

data class StopRecordingC2SPayload(val originPos: BlockPos) : CustomPacketPayload {
    companion object {
        val TYPE = CustomPacketPayload.Type<StopRecordingC2SPayload>(
            Identifier.fromNamespaceAndPath("redstonespecs", "stop_recording")
        )
        val STREAM_CODEC: StreamCodec<ByteBuf, StopRecordingC2SPayload> = StreamCodec.composite(
            BlockPos.STREAM_CODEC, StopRecordingC2SPayload::originPos,
            ::StopRecordingC2SPayload,
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
