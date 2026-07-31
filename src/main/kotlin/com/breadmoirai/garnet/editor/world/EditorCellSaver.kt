package com.breadmoirai.garnet.editor.world

import com.breadmoirai.garnet.editor.data.LoadedSpec
import net.minecraft.core.BlockPos
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.NbtIo
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate
import org.slf4j.LoggerFactory
import java.nio.file.Path
import kotlin.io.path.writeText

private val LOGGER = LoggerFactory.getLogger("Garnet")

data class CellSaveResult(val specId: String, val saved: Boolean, val error: String? = null)

object EditorCellSaver {
    /**
     * Captures the current cell volume at `absoluteCellOrigin`, diffs vs `loaded.loadedSnapshot`,
     * and rewrites the source `.spec.kts` + structure NBT iff dirty.
     */
    fun captureAndSaveIfDirty(
        level: ServerLevel,
        loaded: LoadedSpec,
        absoluteCellOrigin: BlockPos,
        folderAbsolute: Path,
    ): CellSaveResult {
        val live = StructureTemplate()
        live.fillFromWorld(level, absoluteCellOrigin, loaded.spec.bounds, false, emptyList())
        val liveNbt = live.save(CompoundTag())
        val savedNbt = loaded.loadedSnapshot.save(CompoundTag())
        if (liveNbt == savedNbt) {
            return CellSaveResult(loaded.spec.id, saved = false)
        }

        return runCatching {
            // TODO(Phase 5+): re-emit source file via new dsl path; old KtsSpecEmitter
            // only supports data.GarnetSpec, which is being removed. Source file is now
            // the canonical record — written by RecordingDslEmitter at finalize time.
            val structureId = loaded.spec.structure ?: loaded.spec.id
            val structureFile = folderAbsolute.resolve("$structureId.nbt")
            NbtIo.writeCompressed(liveNbt, structureFile)
            LOGGER.info("[EditorCellSaver] saved '{}' -> {} + {}",
                loaded.spec.id, loaded.sourceFile, structureFile)
            CellSaveResult(loaded.spec.id, saved = true)
        }.getOrElse { e ->
            LOGGER.error("[EditorCellSaver] failed to save '{}': {}", loaded.spec.id, e.message, e)
            CellSaveResult(loaded.spec.id, saved = false, error = e.message)
        }
    }
}
