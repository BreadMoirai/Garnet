package com.breadmoirai.redstonespecs.test.recorder

import com.breadmoirai.redstonespecs.ModRegistries
import com.breadmoirai.redstonespecs.block.SpecBlockEntity
import com.breadmoirai.redstonespecs.config.SharedSettings
import com.breadmoirai.redstonespecs.item.UndoStack
import com.breadmoirai.redstonespecs.network.RecorderCmd
import com.breadmoirai.redstonespecs.network.RecorderCommandC2S
import com.breadmoirai.redstonespecs.network.handleRecorderCommand
import com.breadmoirai.redstonespecs.dsl.Phase
import com.breadmoirai.redstonespecs.runner.EntryMarker
import com.breadmoirai.redstonespecs.runner.StateRecorder
import com.breadmoirai.redstonespecs.test.makeMockServerPlayer
import com.breadmoirai.redstonespecs.test.placeRecorderBE
import com.breadmoirai.redstonespecs.test.withTempRoot
import com.breadmoirai.redstonespecs.testing.RedstoneTestSpec
import com.breadmoirai.redstonespecs.testing.server.onServer
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.delay
import net.minecraft.core.BlockPos
import net.minecraft.core.Vec3i
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.storage.LevelResource
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

private suspend fun awaitFile(path: Path, timeoutMs: Long = 2000) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        if (Files.exists(path)) return
        delay(20)
    }
    error("file did not appear within ${timeoutMs}ms: $path")
}

/**
 * Server-side coverage for recording-lifecycle UCs. Each test corresponds to one or more
 * rows in `docs/use-cases/recording.md`. Test names embed the UC ID for traceability.
 *
 * Out of scope: client-screen rows (UC-REC-01.c/d, 03.a/b), marker-tool integration
 * rows (UC-REC-02.a/b/d). See the design doc
 * `docs/superpowers/specs/2026-05-12-recording-server-lifecycle-coverage-design.md`.
 */
class RecordingLifecycleSpec : RedstoneTestSpec({

    test("UC-REC-03.c: isConfigured returns false for blank specId") {
        val be = SpecBlockEntity(
            BlockPos.ZERO,
            ModRegistries.REDSTONE_SPEC_RECORDER_BLOCK.defaultBlockState(),
        )
        be.setSpecId("")
        be.isConfigured shouldBe false
    }

    test("UC-REC-03.c: isConfigured returns false when any bound dimension is zero") {
        val be = SpecBlockEntity(
            BlockPos.ZERO,
            ModRegistries.REDSTONE_SPEC_RECORDER_BLOCK.defaultBlockState(),
        )
        be.setSpecId("ok")
        be.setSpecBounds(Vec3i(0, 5, 5))
        be.isConfigured shouldBe false
        be.setSpecBounds(Vec3i(5, 0, 5))
        be.isConfigured shouldBe false
        be.setSpecBounds(Vec3i(5, 5, 0))
        be.isConfigured shouldBe false
        be.setSpecBounds(Vec3i(1, 1, 1))
        be.isConfigured shouldBe true
    }

    test("UC-REC-06.b: startRecording returns false for blank specId") {
        onServer {
            val level = this.overworld()
            val pos = BlockPos(2000, 64, 1000)
            val be = placeRecorderBE(level, pos, specId = "to-be-blanked")
            be.setSpecId("")
            be.startRecording() shouldBe false
            be.isRecording shouldBe false
        }
    }

    test("UC-REC-06.b: startRecording returns false for zero-volume bounds") {
        onServer {
            val level = this.overworld()
            val pos = BlockPos(2010, 64, 1000)
            val be = placeRecorderBE(level, pos, specId = "ok")
            be.setSpecBounds(Vec3i(0, 5, 5))
            be.startRecording() shouldBe false
            be.isRecording shouldBe false
        }
    }

    test("UC-REC-04.a/c: startRecording succeeds and recorder appears in activeRecorders") {
        onServer {
            val level = this.overworld()
            val pos = BlockPos(2020, 64, 1000)
            val be = placeRecorderBE(level, pos, specId = "uc04ac", bounds = Vec3i(3, 3, 3))

            be.startRecording() shouldBe true
            try {
                be.isRecording shouldBe true
                val active = StateRecorder.activeRecorders()
                active.size shouldBe 1
            } finally {
                be.stopRecordingAndFinalize()
            }
        }
    }

    test("UC-REC-04.b: StateRecorder.start captures initial snapshot keyed by origin-relative BlockPos") {
        onServer {
            val level = this.overworld()
            val pos = BlockPos(2030, 64, 1000)
            val be = placeRecorderBE(level, pos, specId = "uc04b", bounds = Vec3i(3, 3, 3))

            // Place a stone block at world (2031, 64, 1000) -> origin-relative (1, 0, 0)
            val markerWorld = BlockPos(2031, 64, 1000)
            level.setBlock(markerWorld, Blocks.STONE.defaultBlockState(), 2)

            be.startRecording() shouldBe true
            try {
                val recorder = be.javaClass.getDeclaredField("stateRecorder")
                    .apply { isAccessible = true }
                    .get(be) as StateRecorder
                val snapshot = recorder.initialSnapshot
                val rel = BlockPos(1, 0, 0)
                val state = snapshot[rel]
                state shouldNotBe null
                state!!.block shouldBe Blocks.STONE
            } finally {
                be.stopRecordingAndFinalize()
            }
        }
    }

    test("UC-REC-05.a: stopRecordingAndFinalize deactivates recorder and clears stateRecorder") {
        onServer {
            val level = this.overworld()
            val pos = BlockPos(2040, 64, 1000)
            val be = placeRecorderBE(level, pos, specId = "uc05a-no-markers")
            // No markers added => no file write, but the deactivation path still runs.
            be.startRecording() shouldBe true
            val activeBefore = StateRecorder.activeRecorders().size

            be.stopRecordingAndFinalize() shouldBe true
            be.isRecording shouldBe false
            StateRecorder.activeRecorders().size shouldBe (activeBefore - 1)
        }
    }

    test("UC-REC-05.e: stopRecordingAndFinalize with markers writes .spec.kts under SharedSettings.specSaveDir") {
        val specId = "uc05e-savedir-${UUID.randomUUID().toString().take(6)}"
        val expectedPath = onServer {
            val level = this.overworld()
            val pos = BlockPos(2050, 64, 1000)
            val be = placeRecorderBE(level, pos, specId = specId)
            // Drop one input marker so the emit path runs.
            be.addOrUpdateMarker(
                EntryMarker(
                    pos = BlockPos(1, 0, 0),
                    label = "input_a",
                    color = 0xFF4488FF.toInt(),
                    kind = EntryMarker.Kind.INPUT,
                )
            )
            be.startRecording() shouldBe true
            be.stopRecordingAndFinalize() shouldBe true
            this.getWorldPath(LevelResource.ROOT)
                .resolve(SharedSettings.specSaveDir)
                .resolve("$specId.spec.kts")
        }
        awaitFile(expectedPath)
        // cleanup so the next gametest run starts clean
        Files.deleteIfExists(expectedPath)
    }

    test("UC-REC-05.e: managedSourcePath redirects write to that path instead of saveDir") {
        withTempRoot("rec-uc05e-managed") { tmp ->
            val target = tmp.resolve("uc05e-managed.spec.kts")
            onServer {
                val level = this.overworld()
                val pos = BlockPos(2060, 64, 1000)
                val be = placeRecorderBE(level, pos, specId = "uc05e-managed")
                be.managedSourcePath = target
                be.addOrUpdateMarker(
                    EntryMarker(
                        pos = BlockPos(1, 0, 0),
                        label = "input_a",
                        color = 0xFF4488FF.toInt(),
                        kind = EntryMarker.Kind.INPUT,
                    )
                )
                be.startRecording() shouldBe true
                be.stopRecordingAndFinalize() shouldBe true
                // UC-REC-05.f: stop returning true and isRecording==false is the observable
                // consequence of setChangedAndSync running on the server thread.
                be.isRecording shouldBe false
            }
            awaitFile(target)
            // Sanity: file is non-empty DSL source
            val text = Files.readString(target)
            text shouldContain "redstoneSpec"
        }
    }

    test("UC-REC-06.c: stopRecordingAndFinalize with no markers writes no file but still clears recorder") {
        val specId = "uc06c-empty-${UUID.randomUUID().toString().take(6)}"
        val expectedPath = onServer {
            val level = this.overworld()
            val pos = BlockPos(2070, 64, 1000)
            val be = placeRecorderBE(level, pos, specId = specId)
            // Intentionally no addOrUpdateMarker call.
            be.startRecording() shouldBe true
            be.stopRecordingAndFinalize() shouldBe true
            be.isRecording shouldBe false
            this.getWorldPath(LevelResource.ROOT)
                .resolve(SharedSettings.specSaveDir)
                .resolve("$specId.spec.kts")
        }
        // Give any (incorrect) async write a chance to land before asserting absence.
        delay(200)
        Files.exists(expectedPath) shouldBe false
    }

    test("UC-REC-06.a: handleRecorderCommand(DISCARD) calls discardForRerecord (does not touch active recorder)") {
        onServer {
            val level = this.overworld()
            val pos = BlockPos(2080, 64, 1000)
            val be = placeRecorderBE(level, pos, specId = "uc06a")
            // Seed two markers with the same (pos, kind) so discard collapses them.
            val marker = EntryMarker(
                pos = BlockPos(1, 0, 0),
                label = "input_a",
                color = 0xFF4488FF.toInt(),
                kind = EntryMarker.Kind.INPUT,
            )
            be.setSpecMarkers(listOf(marker, marker.copy(label = "input_b")))
            be.specMarkers.size shouldBe 2

            val player = makeMockServerPlayer(this)
            handleRecorderCommand(
                this, player,
                RecorderCommandC2S(pos, RecorderCmd.DISCARD),
            )

            // Behavior of discardForRerecord: collapse duplicates by (pos, kind).
            be.specMarkers.size shouldBe 1
        }
    }

    test("UC-REC-06.d: discardForRerecord collapses duplicate (pos,kind) entries to one each") {
        onServer {
            val level = this.overworld()
            val pos = BlockPos(2090, 64, 1000)
            val be = placeRecorderBE(level, pos, specId = "uc06d")
            val rel = BlockPos(1, 0, 0)
            val a = EntryMarker(
                pos = rel, label = "input_a", color = 0,
                kind = EntryMarker.Kind.INPUT,
            )
            val b = a.copy(label = "input_b")
            val cOut = a.copy(
                label = "output_a",
                kind = EntryMarker.Kind.OUTPUT,
            )
            be.setSpecMarkers(listOf(a, b, cOut, cOut.copy(label = "output_b")))
            be.discardForRerecord()
            be.specMarkers.size shouldBe 2
            // Exactly one INPUT and one OUTPUT remain at this (pos).
            val byKind = be.specMarkers.groupBy { it.kind }
            byKind[EntryMarker.Kind.INPUT]?.size shouldBe 1
            byKind[EntryMarker.Kind.OUTPUT]?.size shouldBe 1
        }
    }

    test("UC-REC-02.c: InputSpecMarkerItem.createMarker yields input_a then input_b with color 0xFF4488FF") {
        val be = SpecBlockEntity(
            BlockPos.ZERO,
            ModRegistries.REDSTONE_SPEC_RECORDER_BLOCK.defaultBlockState(),
        )
        val item = ModRegistries.INPUT_SPEC_MARKER
        val first = item.createMarker(BlockPos(1, 0, 0), be)
        first.label shouldBe "input_a"
        first.color shouldBe 0xFF4488FF.toInt()
        first.kind shouldBe EntryMarker.Kind.INPUT

        be.setSpecMarkers(listOf(first))
        val second = item.createMarker(BlockPos(2, 0, 0), be)
        second.label shouldBe "input_b"
    }

    test("UC-REC-02.c: OutputSpecMarkerItem.createMarker uses color 0xFFFF8800") {
        val be = SpecBlockEntity(
            BlockPos.ZERO,
            ModRegistries.REDSTONE_SPEC_RECORDER_BLOCK.defaultBlockState(),
        )
        val marker = ModRegistries.OUTPUT_SPEC_MARKER
            .createMarker(BlockPos(1, 0, 0), be)
        marker.label shouldBe "output_a"
        marker.color shouldBe 0xFFFF8800.toInt()
        marker.kind shouldBe EntryMarker.Kind.OUTPUT
    }

    test("UC-REC-04.d: onPhaseForActiveRecorders advances currentTick on START_OF_TICK and updates currentPhase") {
        onServer {
            val level = this.overworld()
            val pos = BlockPos(2100, 64, 1000)
            val be = placeRecorderBE(level, pos, specId = "uc04d", bounds = Vec3i(3, 3, 3))
            check(be.startRecording()) { "startRecording failed; check isConfigured" }
            try {
                val recorder = be.javaClass.getDeclaredField("stateRecorder")
                    .apply { isAccessible = true }
                    .get(be) as StateRecorder

                // currentTick is private; observe directly via reflection to avoid relying on
                // the setBlock-mixin path (which the gametest harness does not reliably exercise).
                val tickField = StateRecorder::class.java.getDeclaredField("currentTick")
                    .apply { isAccessible = true }
                fun currentTick(): Int = tickField.getInt(recorder)

                val baseTick = currentTick()  // production server ticks may have advanced it past -1

                StateRecorder.onPhaseForActiveRecorders(level, Phase.START_OF_TICK)
                recorder.currentPhase shouldBe Phase.START_OF_TICK
                currentTick() shouldBe (baseTick + 1)

                StateRecorder.onPhaseForActiveRecorders(level, Phase.START_OF_TICK)
                currentTick() shouldBe (baseTick + 2)

                // Non-START_OF_TICK phase: updates currentPhase, leaves tick alone.
                StateRecorder.onPhaseForActiveRecorders(level, Phase.END_OF_TICK)
                recorder.currentPhase shouldBe Phase.END_OF_TICK
                currentTick() shouldBe (baseTick + 2)
            } finally {
                be.stopRecordingAndFinalize()
            }
        }
    }

    test("UC-REC-02.e: UndoStack push then pop returns the marker; cap at 20 per UUID") {
        val uuid = UUID.randomUUID()
        val origin = BlockPos(0, 64, 0)
        val mk = { i: Int ->
            UndoStack.UndoRecord(
                originPos = origin,
                marker = EntryMarker(
                    pos = BlockPos(i, 0, 0),
                    label = "input_$i",
                    color = 0xFF4488FF.toInt(),
                    kind = EntryMarker.Kind.INPUT,
                ),
            )
        }
        UndoStack.clear(uuid)

        val r0 = mk(0)
        UndoStack.push(uuid, r0)
        UndoStack.pop(uuid) shouldBe r0

        // Push 21; bottom must have been evicted -> popping 20 times yields entries 1..20 reversed.
        for (i in 0..20) UndoStack.push(uuid, mk(i))
        val popped = generateSequence { UndoStack.pop(uuid) }.toList()
        popped.size shouldBe 20
        // Newest first: 20, 19, ..., 1
        popped.first().marker.pos.x shouldBe 20
        popped.last().marker.pos.x shouldBe 1
    }

    test("UC-REC-01.a: setPlacedBy derives default specId from placer's profile name when current id is the placeholder") {
        onServer {
            val level = this.overworld()
            val player = makeMockServerPlayer(level.server)
            val pos = BlockPos(2110, 64, 1000)
            // The gametest world persists between runGameTest invocations. Clear the position
            // first so setBlock is a real state change and a *fresh* BE (placeholder specId) is
            // created; otherwise a recorder BE left here by a prior run would make setBlock a
            // no-op and the stale specId (e.g. "<player>_spec") would fail the sanity check.
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), 2)
            level.setBlock(pos, ModRegistries.REDSTONE_SPEC_RECORDER_BLOCK.defaultBlockState(), 2)
            val be = level.getBlockEntity(pos) as SpecBlockEntity
            be.specId shouldBe "spec"  // sanity: BE created with placeholder

            ModRegistries.REDSTONE_SPEC_RECORDER_BLOCK.setPlacedBy(
                level, pos, level.getBlockState(pos), player, player.mainHandItem,
            )

            val expected = player.gameProfile.name.lowercase().replace(" ", "_") + "_spec"
            be.specId shouldBe expected
        }
    }

    test("UC-REC-01.a: setPlacedBy does NOT overwrite a non-placeholder specId") {
        onServer {
            val level = this.overworld()
            val player = makeMockServerPlayer(level.server)
            val pos = BlockPos(2120, 64, 1000)
            level.setBlock(pos, ModRegistries.REDSTONE_SPEC_RECORDER_BLOCK.defaultBlockState(), 2)
            val be = level.getBlockEntity(pos) as SpecBlockEntity
            be.setSpecId("already-set")
            be.specId shouldBe "already-set"

            ModRegistries.REDSTONE_SPEC_RECORDER_BLOCK.setPlacedBy(
                level, pos, level.getBlockState(pos), player, player.mainHandItem,
            )

            be.specId shouldBe "already-set"  // guard preserves explicit id
        }
    }
})
