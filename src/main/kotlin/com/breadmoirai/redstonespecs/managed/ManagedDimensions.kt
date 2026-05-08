package com.breadmoirai.redstonespecs.managed

import net.minecraft.core.registries.Registries
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.world.level.Level
import net.minecraft.world.level.dimension.DimensionType

/**
 * Static keys to the managed dim. The DimensionType and the LevelStem are both registered
 * via data-pack JSON (under `data/redstonespecs/dimension_type/` and `data/redstonespecs/dimension/`)
 * so MC creates the ServerLevel at server bootstrap. Look up with `server.getLevel(MANAGED_LEVEL_KEY)`.
 */
object ManagedDimensions {
    val DIMENSION_TYPE_KEY: ResourceKey<DimensionType> = ResourceKey.create(
        Registries.DIMENSION_TYPE,
        Identifier.fromNamespaceAndPath("redstonespecs", "managed_void"),
    )
    val MANAGED_LEVEL_KEY: ResourceKey<Level> = ResourceKey.create(
        Registries.DIMENSION,
        Identifier.fromNamespaceAndPath("redstonespecs", "managed"),
    )

    /**
     * Per-folder dim level key. `sanitizedPath` should be the output of
     * `DimIdSanitizer.toPath(subpath)` (e.g. `managed/foo/bar`). Coexists with
     * [MANAGED_LEVEL_KEY] which is the fallback single-dim reference.
     */
    fun levelKey(sanitizedPath: String): ResourceKey<Level> = ResourceKey.create(
        Registries.DIMENSION,
        Identifier.fromNamespaceAndPath("redstonespecs", sanitizedPath),
    )
}
