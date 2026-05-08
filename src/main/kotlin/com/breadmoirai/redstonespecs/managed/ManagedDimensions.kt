package com.breadmoirai.redstonespecs.managed

import net.minecraft.core.registries.Registries
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.world.level.Level
import net.minecraft.world.level.chunk.ChunkGenerator
import net.minecraft.world.level.dimension.DimensionType
import net.minecraft.server.MinecraftServer

/**
 * Static keys + a helper to build the chunk generator used by every per-folder managed dim.
 *
 * The DimensionType itself is registered via the data-pack JSON at
 *   data/redstonespecs/dimension_type/managed_void.json
 * — MC bootstrap loads it from the registry on server start. We only hold a `ResourceKey`
 * pointer to it here; per-folder `LevelStem`s reuse the same type.
 */
object ManagedDimensions {
    val DIMENSION_TYPE_KEY: ResourceKey<DimensionType> = ResourceKey.create(
        Registries.DIMENSION_TYPE,
        Identifier.fromNamespaceAndPath("redstonespecs", "managed_void"),
    )

    fun levelKey(sanitizedPath: String): ResourceKey<Level> = ResourceKey.create(
        Registries.DIMENSION,
        Identifier.fromNamespaceAndPath("redstonespecs", sanitizedPath),
    )

    /**
     * Builds an empty-flat ChunkGenerator using the server's biome registry. No layers added → pure void.
     *
     * MC 26.1 constructor (verified against decompiled source):
     *   FlatLevelGeneratorSettings(Optional<HolderSet<StructureSet>>, Holder<Biome>, List<Holder<PlacedFeature>>)
     * The third param is the `lakes` list (placed features), not structure settings.
     */
    fun voidGenerator(server: MinecraftServer): ChunkGenerator {
        val biomes = server.registryAccess().lookupOrThrow(Registries.BIOME)
        val voidBiome = biomes.getOrThrow(net.minecraft.world.level.biome.Biomes.THE_VOID)
        val settings = net.minecraft.world.level.levelgen.flat.FlatLevelGeneratorSettings(
            /* structureOverrides = */ java.util.Optional.empty(),
            /* biome = */ voidBiome,
            /* lakes = */ java.util.Collections.emptyList(),
        )
        return net.minecraft.world.level.levelgen.FlatLevelSource(settings)
    }
}
