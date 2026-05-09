package com.breadmoirai.redstonespecs.runner

import com.breadmoirai.redstonespecs.dsl.Phase
import com.breadmoirai.redstonespecs.dsl.SimTime
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import net.minecraft.SharedConstants
import net.minecraft.core.BlockPos
import net.minecraft.core.Vec3i
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.Identifier
import net.minecraft.server.Bootstrap
import net.minecraft.world.level.block.ComparatorBlock
import net.minecraft.world.level.block.LeverBlock
import net.minecraft.world.level.block.RedstoneTorchBlock
import java.util.UUID

class RecordingDslEmitterTest : FunSpec({

    beforeSpec {
        SharedConstants.tryDetectVersion()
        Bootstrap.bootStrap()
    }

    fun leverBlock() = BuiltInRegistries.BLOCK.getValue(Identifier.parse("minecraft:lever"))
    fun torchBlock() = BuiltInRegistries.BLOCK.getValue(Identifier.parse("minecraft:redstone_torch"))
    fun comparatorBlock() = BuiltInRegistries.BLOCK.getValue(Identifier.parse("minecraft:comparator"))

    /**
     * Fake recording:
     * - Lever at (0,0,0): powered=false initially, changes to powered=true at tick 2
     * - Redstone torch at (1,0,0): lit=false initially, changes to lit=true at tick 5
     * - Comparator at (2,0,0): powered=false initially, changes to powered=true at tick 3
     *
     * All recorded with absolute ticks 0..5; firstTick=2, lastTick=5.
     * Relative ticks emitted: lever at(2-2)=0→atStart + at(0) in real run;
     * but lever changes at tick 2 which IS firstTick so only atStart is emitted.
     * Comparator at relative tick 3-2=1, torch at relative tick 5-2=3.
     */
    fun buildRecording(): StateRecording {
        val leverPos = BlockPos(0, 0, 0)
        val torchPos = BlockPos(1, 0, 0)
        val comparatorPos = BlockPos(2, 0, 0)

        val leverUnpowered = leverBlock().defaultBlockState().setValue(LeverBlock.POWERED, false)
        val leverPowered = leverBlock().defaultBlockState().setValue(LeverBlock.POWERED, true)
        val torchUnlit = torchBlock().defaultBlockState().setValue(RedstoneTorchBlock.LIT, false)
        val torchLit = torchBlock().defaultBlockState().setValue(RedstoneTorchBlock.LIT, true)
        val comparatorUnpowered = comparatorBlock().defaultBlockState().setValue(ComparatorBlock.POWERED, false)
        val comparatorPowered = comparatorBlock().defaultBlockState().setValue(ComparatorBlock.POWERED, true)

        val changes = listOf(
            // Lever turns on at tick 2 (this is firstTick)
            BlockStateChange(
                pos = leverPos,
                simTime = SimTime(2, Phase.END_OF_TICK, 0),
                toBlock = null,
                diffs = listOf(PropertyDiff("powered", "true")),
            ),
            // Comparator gets powered at tick 3
            BlockStateChange(
                pos = comparatorPos,
                simTime = SimTime(3, Phase.END_OF_TICK, 0),
                toBlock = null,
                diffs = listOf(PropertyDiff("powered", "true")),
            ),
            // Torch lights up at tick 5 (this is lastTick)
            BlockStateChange(
                pos = torchPos,
                simTime = SimTime(5, Phase.END_OF_TICK, 0),
                toBlock = null,
                diffs = listOf(PropertyDiff("lit", "true")),
            ),
        )

        return StateRecording(
            specId = UUID.randomUUID(),
            timestamp = 0L,
            initialSnapshot = mapOf(
                leverPos to leverUnpowered,
                torchPos to torchUnlit,
                comparatorPos to comparatorUnpowered,
            ),
            changes = changes,
        )
    }

    fun buildMarkers(): List<EntryMarker> = listOf(
        EntryMarker(BlockPos(0, 0, 0), "lever", -1, EntryMarker.Kind.INPUT),
        EntryMarker(BlockPos(1, 0, 0), "torch", -1, EntryMarker.Kind.OUTPUT),
        EntryMarker(BlockPos(2, 0, 0), "comparator", -1, EntryMarker.Kind.OUTPUT),
    )

    test("emits redstoneSpec header with correct metadata") {
        val source = RecordingDslEmitter.emit(
            id = "test-spec",
            bounds = Vec3i(3, 3, 3),
            lifespan = 4,
            structure = null,
            strict = false,
            markers = buildMarkers(),
            recording = buildRecording(),
        )

        source shouldContain "redstoneSpec("
        source shouldContain "id = \"test-spec\""
        source shouldContain "bounds = Vec3i(3, 3, 3)"
        source shouldContain "lifespan = 4"
    }

    test("emits imports at top") {
        val source = RecordingDslEmitter.emit(
            id = "test-spec",
            bounds = Vec3i(3, 3, 3),
            lifespan = 4,
            structure = null,
            strict = false,
            markers = buildMarkers(),
            recording = buildRecording(),
        )

        source shouldContain "import com.breadmoirai.redstonespecs.dsl.*"
        source shouldContain "import net.minecraft.core.Vec3i"
    }

    test("emits input block for lever position") {
        val source = RecordingDslEmitter.emit(
            id = "test-spec",
            bounds = Vec3i(3, 3, 3),
            lifespan = 4,
            structure = null,
            strict = false,
            markers = buildMarkers(),
            recording = buildRecording(),
        )

        source shouldContain "input(0, 0, 0"
        source shouldContain "label = \"lever\""
        // Lever turns on at firstTick (tick 2 = relative 0), so emitted in atStart
        source shouldContain "atStart {"
        source shouldContain "setPowered(true)"
    }

    test("emits output block for redstone torch position with lit()") {
        val source = RecordingDslEmitter.emit(
            id = "test-spec",
            bounds = Vec3i(3, 3, 3),
            lifespan = 4,
            structure = null,
            strict = false,
            markers = buildMarkers(),
            recording = buildRecording(),
        )

        source shouldContain "output(1, 0, 0"
        source shouldContain "label = \"torch\""
        // Torch lights at tick 5, relative to firstTick=2: at(3)
        source shouldContain "at(3) {"
        source shouldContain "lit(true)"
    }

    test("emits output block for comparator position with powered()") {
        val source = RecordingDslEmitter.emit(
            id = "test-spec",
            bounds = Vec3i(3, 3, 3),
            lifespan = 4,
            structure = null,
            strict = false,
            markers = buildMarkers(),
            recording = buildRecording(),
        )

        source shouldContain "output(2, 0, 0"
        source shouldContain "label = \"comparator\""
        // Comparator gets powered at tick 3, relative to firstTick=2: at(1)
        source shouldContain "at(1) {"
        source shouldContain "powered(true)"
    }

    test("emits structure parameter when provided") {
        val source = RecordingDslEmitter.emit(
            id = "test-spec",
            bounds = Vec3i(3, 3, 3),
            lifespan = 4,
            structure = "redstonespecs:my_structure",
            strict = false,
            markers = buildMarkers(),
            recording = buildRecording(),
        )

        source shouldContain "structure = \"redstonespecs:my_structure\""
    }

    test("emits strict = true when strict is enabled") {
        val source = RecordingDslEmitter.emit(
            id = "test-spec",
            bounds = Vec3i(3, 3, 3),
            lifespan = 4,
            structure = null,
            strict = true,
            markers = buildMarkers(),
            recording = buildRecording(),
        )

        source shouldContain "strict = true"
    }

    test("does not emit strict when false") {
        val source = RecordingDslEmitter.emit(
            id = "test-spec",
            bounds = Vec3i(3, 3, 3),
            lifespan = 4,
            structure = null,
            strict = false,
            markers = buildMarkers(),
            recording = buildRecording(),
        )

        source shouldNotContain "strict = false"
        source shouldNotContain "strict = true"
    }

    test("returns empty spec body when recording has no I/O activity") {
        val leverPos = BlockPos(0, 0, 0)
        val leverState = leverBlock().defaultBlockState().setValue(LeverBlock.POWERED, false)
        val emptyRecording = StateRecording(
            specId = UUID.randomUUID(),
            timestamp = 0L,
            initialSnapshot = mapOf(leverPos to leverState),
            changes = emptyList(),
        )

        val source = RecordingDslEmitter.emit(
            id = "empty-spec",
            bounds = Vec3i(3, 3, 3),
            lifespan = 4,
            structure = null,
            strict = false,
            markers = listOf(EntryMarker(leverPos, "lever", -1, EntryMarker.Kind.INPUT)),
            recording = emptyRecording,
        )

        source shouldContain "redstoneSpec("
        source shouldContain "id = \"empty-spec\""
        source shouldNotContain "input("
        source shouldNotContain "output("
    }

    test("inputs sorted before outputs in emitted source") {
        val source = RecordingDslEmitter.emit(
            id = "test-spec",
            bounds = Vec3i(3, 3, 3),
            lifespan = 4,
            structure = null,
            strict = false,
            markers = buildMarkers(),
            recording = buildRecording(),
        )

        val inputIdx = source.indexOf("input(")
        val outputIdx = source.indexOf("output(")
        check(inputIdx >= 0) { "input( not found in source" }
        check(outputIdx >= 0) { "output( not found in source" }
        assert(inputIdx < outputIdx) { "Expected input before output in emitted source" }
    }
})
