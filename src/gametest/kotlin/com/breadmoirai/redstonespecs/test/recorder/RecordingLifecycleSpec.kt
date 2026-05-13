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
})
