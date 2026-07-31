package com.breadmoirai.garnet.editor.data

import com.breadmoirai.garnet.playback.recorder.RecordingDslEmitter
import org.slf4j.LoggerFactory
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.writeText

private val LOGGER = LoggerFactory.getLogger("Garnet")

object EditorNewSpec {
    /**
     * Creates a stub `<name>.spec.kts` in `folder`. Returns the path; throws if a file with
     * that name already exists or `name` is blank/illegal. Caller should reload the folder
     * afterwards so the new cell appears.
     */
    fun create(folder: Path, name: String): Path {
        require(name.isNotBlank()) { "spec name must not be blank" }
        require(name.matches(Regex("[a-zA-Z0-9_\\-]+"))) {
            "spec name must match [a-zA-Z0-9_-]+, got: '$name'"
        }
        val file = folder.resolve("$name.spec.kts")
        require(!file.exists()) { "spec file already exists: $file" }
        // Emit a minimal stub: empty spec with default bounds
        val stub = RecordingDslEmitter.emitStub(name)
        file.writeText(stub)
        LOGGER.info("[EditorNewSpec] created stub '{}'", file)
        return file
    }
}
