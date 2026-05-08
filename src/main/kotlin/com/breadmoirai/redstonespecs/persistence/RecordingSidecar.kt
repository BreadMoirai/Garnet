package com.breadmoirai.redstonespecs.persistence

import com.breadmoirai.redstonespecs.runner.StateRecording
import com.breadmoirai.redstonespecs.runner.stateRecordingFromNbt
import com.breadmoirai.redstonespecs.runner.toNbt
import net.minecraft.nbt.NbtAccounter
import net.minecraft.nbt.NbtIo
import org.slf4j.LoggerFactory
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists

private val LOGGER = LoggerFactory.getLogger("Redstone Specs")
private const val EXT = ".recording.nbt"

/**
 * Stores the authorship-time [StateRecording] for a spec next to its `.spec.kts` file.
 * Used by the in-game editor for visualization and (optionally) by the diagnostic
 * timeline scrubber. Not consulted on the spec-execution path.
 */
object RecordingSidecar {
    fun save(saveDir: Path, specId: String, recording: StateRecording) {
        saveDir.createDirectories()
        val file = saveDir.resolve("$specId$EXT")
        NbtIo.writeCompressed(recording.toNbt(), file)
        LOGGER.debug("[RecordingSidecar#save] saved recording '{}' to {}", specId, file)
    }

    fun load(saveDir: Path, specId: String): StateRecording? {
        val file = saveDir.resolve("$specId$EXT")
        if (!file.exists()) return null
        val tag = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap())
        return stateRecordingFromNbt(tag)
    }
}
