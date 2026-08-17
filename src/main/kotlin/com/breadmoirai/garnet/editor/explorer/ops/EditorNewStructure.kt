package com.breadmoirai.garnet.editor.explorer.ops

import com.breadmoirai.garnet.core.config.SharedSettings
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.NbtIo
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate
import org.slf4j.LoggerFactory
import java.nio.file.Path
import kotlin.io.path.exists

private val LOGGER = LoggerFactory.getLogger("Garnet")

object EditorNewStructure {
    /**
     * Creates a `<name>.nbt` structure in [folder], seeded with the configured default platform
     * (see [DefaultPlatform]). Returns the path; throws if [name] is blank/illegal or the file
     * already exists. Caller should re-scan the tree afterwards so the new file appears in the
     * Explorer.
     */
    fun create(folder: Path, name: String): Path {
        require(name.isNotBlank()) { "structure name must not be blank" }
        // Spaces are allowed: EditorNames.validate -- the rule the Explorer's create/rename field
        // actually enforces -- only rejects path separators, "." and "..", so a stricter charset
        // here would reject names the UI had already accepted.
        require(name.matches(Regex("[a-zA-Z0-9_ \\-]+"))) {
            "structure name must match [a-zA-Z0-9_ -]+, got: '$name'"
        }
        val file = folder.resolve("$name.nbt")
        require(!file.exists()) { "structure file already exists: $file" }
        // Seeded with the configured default platform so a freshly created structure places with a
        // build plane instead of nothing. Settings are read here rather than passed in, matching
        // StructureCommit/StructureAutoSave, so create's signature stays stable for its callers.
        val nbt = DefaultPlatform.platformTag(
            SharedSettings.newStructurePlatformWidth,
            SharedSettings.newStructurePlatformDepth,
            SharedSettings.newStructurePlatformBlock,
        ) ?: StructureTemplate().save(CompoundTag())
        NbtIo.writeCompressed(nbt, file)
        LOGGER.info("[EditorNewStructure] created empty structure '{}'", file)
        return file
    }
}
