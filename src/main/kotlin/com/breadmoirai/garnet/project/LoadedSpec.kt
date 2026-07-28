package com.breadmoirai.garnet.project

import com.breadmoirai.garnet.dsl.GarnetSpec
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate
import java.nio.file.Path

/**
 * Per-spec state inside a loaded folder. `cell.origin` is *relative to (0,0,0)* — the absolute
 * world position is `cell.origin + regionOrigin` (regionOrigin is owned by ProjectDimRegistry).
 * `loadedSnapshot` is the blocks in the cell volume *immediately after placement*, used as the
 * baseline for dirty-diff at save time.
 */
data class LoadedSpec(
    val cell: ProjectCell,
    val spec: GarnetSpec,
    val sourceFile: Path,
    val loadedSnapshot: StructureTemplate,
)
