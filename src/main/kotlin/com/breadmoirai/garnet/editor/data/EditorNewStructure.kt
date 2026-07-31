package com.breadmoirai.garnet.editor.data

import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.NbtIo
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate
import org.slf4j.LoggerFactory
import java.nio.file.Path
import kotlin.io.path.exists

private val LOGGER = LoggerFactory.getLogger("Garnet")

object EditorNewStructure {
    /**
     * Creates an empty `<name>.nbt` structure in [folder]. Returns the path; throws if [name] is
     * blank/illegal or the file already exists. Caller should re-scan the tree afterwards so the
     * new file appears in the Explorer.
     */
    fun create(folder: Path, name: String): Path {
        require(name.isNotBlank()) { "structure name must not be blank" }
        require(name.matches(Regex("[a-zA-Z0-9_\\-]+"))) {
            "structure name must match [a-zA-Z0-9_-]+, got: '$name'"
        }
        val file = folder.resolve("$name.nbt")
        require(!file.exists()) { "structure file already exists: $file" }
        val nbt = StructureTemplate().save(CompoundTag())
        NbtIo.writeCompressed(nbt, file)
        LOGGER.info("[EditorNewStructure] created empty structure '{}'", file)
        return file
    }
}
