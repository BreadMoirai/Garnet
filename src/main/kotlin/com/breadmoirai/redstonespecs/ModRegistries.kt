package com.breadmoirai.redstonespecs

import com.breadmoirai.redstonespecs.block.SpecOriginBlock
import com.breadmoirai.redstonespecs.block.SpecOriginBlockEntity
import net.fabricmc.fabric.api.`object`.builder.v1.block.entity.FabricBlockEntityTypeBuilder
import net.minecraft.core.BlockPos
import net.minecraft.core.Registry
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.registries.Registries
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.Item
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockBehaviour
import net.minecraft.world.level.block.state.BlockState

object ModRegistries {
    val SPEC_ORIGIN_BLOCK: SpecOriginBlock = registerBlock(
        "spec_origin",
        ::SpecOriginBlock,
        BlockBehaviour.Properties.of().strength(2f).noOcclusion()
    )
    val SPEC_ORIGIN_BLOCK_ENTITY_TYPE: BlockEntityType<SpecOriginBlockEntity> = registerBlockEntity(
        "spec_origin",
        ::SpecOriginBlockEntity,
        SPEC_ORIGIN_BLOCK,
    )
    val SPEC_ORIGIN_ITEM: BlockItem = registerBlockItem("spec_origin", SPEC_ORIGIN_BLOCK)

    fun register() {
        // Accessing this object triggers the lazy initializers above.
    }

    private fun id(path: String): Identifier = Identifier.fromNamespaceAndPath("redstonespecs", path)

    private fun <T : Block> registerBlock(
        id: String,
        factory: (properties: BlockBehaviour.Properties) -> T,
        properties: BlockBehaviour.Properties
    ): T {
        val identifier = id(id)
        val key = ResourceKey.create(Registries.BLOCK, identifier)
        properties.setId(key)
        return Registry.register(
            BuiltInRegistries.BLOCK,
            identifier,
            factory(properties),
        )
    }

    private fun <T : BlockEntity> registerBlockEntity(
        id: String,
        factory: (BlockPos, BlockState) -> T,
        block: Block,
    ): BlockEntityType<T> {
        return Registry.register(
            BuiltInRegistries.BLOCK_ENTITY_TYPE,
            id(id),
            FabricBlockEntityTypeBuilder.create(factory, block).build(),
        )
    }

    private fun registerBlockItem(id: String, block: SpecOriginBlock): BlockItem {
        return Registry.register(
            BuiltInRegistries.ITEM,
            id(id),
            BlockItem(
                block, Item.Properties().setId(ResourceKey.create(Registries.ITEM, id(id)))
            )
        )
    }
}
