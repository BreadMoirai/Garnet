package com.breadmoirai.redstonespecs.test.recorder

import com.breadmoirai.redstonespecs.ModRegistries
import com.breadmoirai.redstonespecs.block.SpecBlockEntity
import com.breadmoirai.redstonespecs.config.SharedSettings
import com.breadmoirai.redstonespecs.item.InputSpecMarkerItem
import com.breadmoirai.redstonespecs.item.OutputSpecMarkerItem
import com.breadmoirai.redstonespecs.item.UndoStack
import com.breadmoirai.redstonespecs.network.RecorderCmd
import com.breadmoirai.redstonespecs.network.RecorderCommandC2S
import com.breadmoirai.redstonespecs.network.handleRecorderCommand
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
 * rows (UC-REC-02.a/b/d), and the phase-event row (UC-REC-04.d). See the design doc
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
})
