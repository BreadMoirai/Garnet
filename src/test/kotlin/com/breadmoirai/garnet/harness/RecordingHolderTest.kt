package com.breadmoirai.garnet.harness

import com.breadmoirai.garnet.runner.StateRecording
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.util.UUID

class RecordingHolderTest : FunSpec({
    test("holder set in outer scope is visible in nested suspend functions") {
        runBlocking {
            withContext(RecordingHolder()) {
                val outer = kotlin.coroutines.coroutineContext[RecordingHolder]
                outer.shouldNotBeNull()
                outer.recording = makeEmptyRecording()
                nestedRead() shouldBe outer.recording
            }
        }
    }

    test("holder is null when not installed") {
        runBlocking {
            kotlin.coroutines.coroutineContext[RecordingHolder] shouldBe null
        }
    }
})

private suspend fun nestedRead(): StateRecording? =
    kotlin.coroutines.coroutineContext[RecordingHolder]?.recording

private fun makeEmptyRecording(): StateRecording =
    StateRecording(
        specId = UUID.randomUUID(),
        timestamp = 0L,
        initialSnapshot = emptyMap(),
        changes = emptyList(),
    )
