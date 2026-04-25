package com.breadmoirai.redstonespecs

import com.breadmoirai.redstonespecs.block.RedstoneSpecBlock
import com.breadmoirai.redstonespecs.block.SpecBlockEntity
import com.breadmoirai.redstonespecs.item.AutoSpecMarkerItem
import com.breadmoirai.redstonespecs.item.BreakpointSpecMarkerItem
import com.breadmoirai.redstonespecs.item.InputSpecMarkerItem
import com.breadmoirai.redstonespecs.item.OutputSpecMarkerItem
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
import org.slf4j.LoggerFactory

object ModRegistries {
    private val LOGGER = LoggerFactory.getLogger("Redstone Specs")
    val REDSTONE_SPEC_BLOCK: RedstoneSpecBlock = registerBlock(
        "redstone_spec",
        ::RedstoneSpecBlock,
        BlockBehaviour.Properties.of().strength(2f).noOcclusion()
    )
    val REDSTONE_SPEC_BLOCK_ENTITY_TYPE: BlockEntityType<SpecBlockEntity> = registerBlockEntity(
        "redstone_spec",
        ::SpecBlockEntity,
        REDSTONE_SPEC_BLOCK,
    )
    val REDSTONE_SPEC_ITEM: BlockItem = registerBlockItem("redstone_spec", REDSTONE_SPEC_BLOCK)
    val INPUT_SPEC_MARKER: InputSpecMarkerItem = registerItem("input_spec_marker", ::InputSpecMarkerItem)
    val OUTPUT_SPEC_MARKER: OutputSpecMarkerItem = registerItem("output_spec_marker", ::OutputSpecMarkerItem)
    val BREAKPOINT_SPEC_MARKER: BreakpointSpecMarkerItem = registerItem("breakpoint_spec_marker", ::BreakpointSpecMarkerItem)
    val AUTO_SPEC_MARKER: AutoSpecMarkerItem = registerItem("auto_spec_marker", ::AutoSpecMarkerItem)

    fun register() {
        LOGGER.debug("[ModRegistries#register] registering blocks, block entities, and items")
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

    private fun registerBlockItem(id: String, block: RedstoneSpecBlock): BlockItem {
        return Registry.register(
            BuiltInRegistries.ITEM,
            id(id),
            BlockItem(
                block, Item.Properties().setId(ResourceKey.create(Registries.ITEM, id(id)))
            )
        )
    }

    private fun <T : Item> registerItem(id: String, factory: (Item.Properties) -> T): T {
        val identifier = id(id)
        val props = Item.Properties().setId(ResourceKey.create(Registries.ITEM, identifier))
        return Registry.register(BuiltInRegistries.ITEM, identifier, factory(props))
    }
}
