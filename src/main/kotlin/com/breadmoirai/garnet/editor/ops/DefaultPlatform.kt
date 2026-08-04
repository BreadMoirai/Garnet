package com.breadmoirai.garnet.editor.ops

import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.IntTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.NbtUtils
import net.minecraft.resources.Identifier
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import org.slf4j.LoggerFactory

private val LOGGER = LoggerFactory.getLogger("Garnet")

/**
 * Builds the structure NBT for the platform a newly created `.nbt` is seeded with.
 *
 * The tag is assembled by hand rather than through [net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate]:
 * the only way to populate a template is `fillFromWorld`, which needs a live `ServerLevel`, and
 * this runs during a pure filesystem create — before anything exists in a world. The key names and
 * shapes below mirror `StructureTemplate.save`/`load` exactly. No `DataVersion` is written because
 * the read path ([com.breadmoirai.garnet.structure.StructurePersistence.placeStructureCentered])
 * calls `template.load` directly, with no datafixer step.
 */
object DefaultPlatform {

    /** Used when the configured block id is unknown or malformed. */
    const val FALLBACK_BLOCK_ID: String = "minecraft:smooth_stone"

    /**
     * A [width] × 1 × [depth] slab of [blockId] at local y = 0, or `null` when either dimension is
     * non-positive (the platform is then disabled and the caller writes an empty structure).
     */
    fun platformTag(width: Int, depth: Int, blockId: String): CompoundTag? {
        if (width <= 0 || depth <= 0) return null
        val state = resolveBlockState(blockId)

        val blocks = ListTag()
        for (x in 0 until width) {
            for (z in 0 until depth) {
                val block = CompoundTag()
                block.put("pos", intList(x, 0, z))
                block.putInt("state", 0)  // the palette below has exactly one entry
                blocks.add(block)
            }
        }

        val palette = ListTag()
        // The exact inverse of the NbtUtils.readBlockState that StructureTemplate.loadPalette
        // calls -- it writes Properties too, so a configured block that has block states
        // (minecraft:oak_slab, say) round-trips in its default form instead of failing to parse.
        palette.add(NbtUtils.writeBlockState(state))

        val tag = CompoundTag()
        tag.put("size", intList(width, 1, depth))
        tag.put("palette", palette)
        tag.put("blocks", blocks)
        tag.put("entities", ListTag())
        return tag
    }

    private fun resolveBlockState(blockId: String): BlockState {
        // BuiltInRegistries.BLOCK is a DEFAULTED registry: getValue returns AIR for an unknown id
        // rather than null, which would silently produce an invisible platform. getOptional is
        // overridden by DefaultedMappedRegistry to bypass the default, so it reports the miss.
        val id = Identifier.tryParse(blockId)
        val block = id?.let { BuiltInRegistries.BLOCK.getOptional(it).orElse(null) }
        if (block != null) return block.defaultBlockState()
        LOGGER.warn(
            "[DefaultPlatform] unknown platform block '{}', falling back to {}",
            blockId, FALLBACK_BLOCK_ID,
        )
        return Blocks.SMOOTH_STONE.defaultBlockState()
    }

    private fun intList(vararg values: Int): ListTag {
        val list = ListTag()
        values.forEach { list.add(IntTag.valueOf(it)) }
        return list
    }
}
