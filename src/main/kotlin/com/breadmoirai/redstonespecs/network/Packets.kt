package com.breadmoirai.redstonespecs.network

import com.breadmoirai.redstonespecs.data.SimTime
import com.breadmoirai.redstonespecs.data.SpecEntry
import com.breadmoirai.redstonespecs.data.TestResult
import io.netty.buffer.ByteBuf
import net.minecraft.core.BlockPos
import net.minecraft.world.level.levelgen.structure.BoundingBox
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.Identifier

// === S2C ===

data class OpenOverviewS2CPayload(val originPos: BlockPos) : CustomPacketPayload {
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
    val specName: String,
    val caseName: String,
    val breakpointLabel: String,
) : CustomPacketPayload {
    companion object {
        val TYPE = CustomPacketPayload.Type<BreakpointHitS2CPayload>(
            Identifier.fromNamespaceAndPath("redstonespecs", "breakpoint_hit")
        )
        val STREAM_CODEC: StreamCodec<ByteBuf, BreakpointHitS2CPayload> = StreamCodec.composite(
            BlockPos.STREAM_CODEC, BreakpointHitS2CPayload::originPos,
            SimTime.STREAM_CODEC, BreakpointHitS2CPayload::simTime,
            ByteBufCodecs.STRING_UTF8, BreakpointHitS2CPayload::specName,
            ByteBufCodecs.STRING_UTF8, BreakpointHitS2CPayload::caseName,
            ByteBufCodecs.STRING_UTF8, BreakpointHitS2CPayload::breakpointLabel,
            ::BreakpointHitS2CPayload,
        )
    }
    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE
}

data class AutoSpecRecordedS2CPayload(
    val originPos: BlockPos,
    val specCaseName: String,
) : CustomPacketPayload {
    companion object {
        val TYPE = CustomPacketPayload.Type<AutoSpecRecordedS2CPayload>(
            Identifier.fromNamespaceAndPath("redstonespecs", "auto_spec_recorded")
        )
        val STREAM_CODEC: StreamCodec<ByteBuf, AutoSpecRecordedS2CPayload> = StreamCodec.composite(
            BlockPos.STREAM_CODEC, AutoSpecRecordedS2CPayload::originPos,
            ByteBufCodecs.STRING_UTF8, AutoSpecRecordedS2CPayload::specCaseName,
            ::AutoSpecRecordedS2CPayload,
        )
    }
    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE
}

// === C2S ===

data class RunSpecC2SPayload(val originPos: BlockPos, val runAll: Boolean) : CustomPacketPayload {
    companion object {
        val TYPE = CustomPacketPayload.Type<RunSpecC2SPayload>(
            Identifier.fromNamespaceAndPath("redstonespecs", "run_spec")
        )
        val STREAM_CODEC: StreamCodec<ByteBuf, RunSpecC2SPayload> = StreamCodec.composite(
            BlockPos.STREAM_CODEC, RunSpecC2SPayload::originPos,
            ByteBufCodecs.BOOL, RunSpecC2SPayload::runAll,
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

data class CycleSpecCaseC2SPayload(val originPos: BlockPos, val forward: Boolean) : CustomPacketPayload {
    companion object {
        val TYPE = CustomPacketPayload.Type<CycleSpecCaseC2SPayload>(
            Identifier.fromNamespaceAndPath("redstonespecs", "cycle_spec_case")
        )
        val STREAM_CODEC: StreamCodec<ByteBuf, CycleSpecCaseC2SPayload> = StreamCodec.composite(
            BlockPos.STREAM_CODEC, CycleSpecCaseC2SPayload::originPos,
            ByteBufCodecs.BOOL, CycleSpecCaseC2SPayload::forward,
            ::CycleSpecCaseC2SPayload,
        )
    }
    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE
}

data class SaveSpecEntryC2SPayload(
    val originPos: BlockPos,
    val specCaseIndex: Int,
    val entry: SpecEntry,
) : CustomPacketPayload {
    companion object {
        val TYPE = CustomPacketPayload.Type<SaveSpecEntryC2SPayload>(
            Identifier.fromNamespaceAndPath("redstonespecs", "save_spec_entry")
        )
        val STREAM_CODEC: StreamCodec<ByteBuf, SaveSpecEntryC2SPayload> = StreamCodec.composite(
            BlockPos.STREAM_CODEC, SaveSpecEntryC2SPayload::originPos,
            ByteBufCodecs.VAR_INT, SaveSpecEntryC2SPayload::specCaseIndex,
            ByteBufCodecs.fromCodec(SpecEntry.CODEC), SaveSpecEntryC2SPayload::entry,
            ::SaveSpecEntryC2SPayload,
        )
    }
    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE
}

data class RemoveSpecEntryC2SPayload(
    val originPos: BlockPos,
    val specCaseIndex: Int,
    val entryRelPos: BlockPos,
) : CustomPacketPayload {
    companion object {
        val TYPE = CustomPacketPayload.Type<RemoveSpecEntryC2SPayload>(
            Identifier.fromNamespaceAndPath("redstonespecs", "remove_spec_entry")
        )
        val STREAM_CODEC: StreamCodec<ByteBuf, RemoveSpecEntryC2SPayload> = StreamCodec.composite(
            BlockPos.STREAM_CODEC, RemoveSpecEntryC2SPayload::originPos,
            ByteBufCodecs.VAR_INT, RemoveSpecEntryC2SPayload::specCaseIndex,
            BlockPos.STREAM_CODEC, RemoveSpecEntryC2SPayload::entryRelPos,
            ::RemoveSpecEntryC2SPayload,
        )
    }
    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE
}

data class AddSpecCaseC2SPayload(
    val originPos: BlockPos,
    val name: String,
) : CustomPacketPayload {
    companion object {
        val TYPE = CustomPacketPayload.Type<AddSpecCaseC2SPayload>(
            Identifier.fromNamespaceAndPath("redstonespecs", "add_spec_case")
        )
        val STREAM_CODEC: StreamCodec<ByteBuf, AddSpecCaseC2SPayload> = StreamCodec.composite(
            BlockPos.STREAM_CODEC, AddSpecCaseC2SPayload::originPos,
            ByteBufCodecs.STRING_UTF8, AddSpecCaseC2SPayload::name,
            ::AddSpecCaseC2SPayload,
        )
    }
    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE
}

data class RemoveSpecCaseC2SPayload(
    val originPos: BlockPos,
    val index: Int,
) : CustomPacketPayload {
    companion object {
        val TYPE = CustomPacketPayload.Type<RemoveSpecCaseC2SPayload>(
            Identifier.fromNamespaceAndPath("redstonespecs", "remove_spec_case")
        )
        val STREAM_CODEC: StreamCodec<ByteBuf, RemoveSpecCaseC2SPayload> = StreamCodec.composite(
            BlockPos.STREAM_CODEC, RemoveSpecCaseC2SPayload::originPos,
            ByteBufCodecs.VAR_INT, RemoveSpecCaseC2SPayload::index,
            ::RemoveSpecCaseC2SPayload,
        )
    }
    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE
}

data class SelectSpecCaseC2SPayload(
    val originPos: BlockPos,
    val index: Int,
) : CustomPacketPayload {
    companion object {
        val TYPE = CustomPacketPayload.Type<SelectSpecCaseC2SPayload>(
            Identifier.fromNamespaceAndPath("redstonespecs", "select_spec_case")
        )
        val STREAM_CODEC: StreamCodec<ByteBuf, SelectSpecCaseC2SPayload> = StreamCodec.composite(
            BlockPos.STREAM_CODEC, SelectSpecCaseC2SPayload::originPos,
            ByteBufCodecs.VAR_INT, SelectSpecCaseC2SPayload::index,
            ::SelectSpecCaseC2SPayload,
        )
    }
    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE
}

data class RenameSpecC2SPayload(
    val originPos: BlockPos,
    val newName: String,
) : CustomPacketPayload {
    companion object {
        val TYPE = CustomPacketPayload.Type<RenameSpecC2SPayload>(
            Identifier.fromNamespaceAndPath("redstonespecs", "rename_spec")
        )
        val STREAM_CODEC: StreamCodec<ByteBuf, RenameSpecC2SPayload> = StreamCodec.composite(
            BlockPos.STREAM_CODEC, RenameSpecC2SPayload::originPos,
            ByteBufCodecs.STRING_UTF8, RenameSpecC2SPayload::newName,
            ::RenameSpecC2SPayload,
        )
    }
    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE
}

data class RenameSpecCaseC2SPayload(
    val originPos: BlockPos,
    val index: Int,
    val newName: String,
) : CustomPacketPayload {
    companion object {
        val TYPE = CustomPacketPayload.Type<RenameSpecCaseC2SPayload>(
            Identifier.fromNamespaceAndPath("redstonespecs", "rename_spec_case")
        )
        val STREAM_CODEC: StreamCodec<ByteBuf, RenameSpecCaseC2SPayload> = StreamCodec.composite(
            BlockPos.STREAM_CODEC, RenameSpecCaseC2SPayload::originPos,
            ByteBufCodecs.VAR_INT, RenameSpecCaseC2SPayload::index,
            ByteBufCodecs.STRING_UTF8, RenameSpecCaseC2SPayload::newName,
            ::RenameSpecCaseC2SPayload,
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
