package com.breadmoirai.garnet

import com.breadmoirai.garnet.block.GarnetRecorderBlock
import com.breadmoirai.garnet.block.GarnetRunnerBlock
import com.breadmoirai.garnet.block.SpecBlockEntity
import com.breadmoirai.garnet.item.InputSpecMarkerItem
import com.breadmoirai.garnet.item.OutputSpecMarkerItem
import net.fabricmc.fabric.api.`object`.builder.v1.block.entity.FabricBlockEntityTypeBuilder
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
import org.slf4j.LoggerFactory

object ModRegistries {
    private val LOGGER = LoggerFactory.getLogger("Garnet")

    private val sharedProps: BlockBehaviour.Properties
        get() = BlockBehaviour.Properties.of().strength(2f).noOcclusion()

    val GARNET_RUNNER_BLOCK: GarnetRunnerBlock = registerBlock(
        "garnet_runner", ::GarnetRunnerBlock, sharedProps
    )
    val GARNET_RECORDER_BLOCK: GarnetRecorderBlock = registerBlock(
        "garnet_recorder", ::GarnetRecorderBlock, sharedProps
    )

    val SPEC_BLOCK_ENTITY_TYPE: BlockEntityType<SpecBlockEntity> = Registry.register(
        BuiltInRegistries.BLOCK_ENTITY_TYPE,
        id("spec_block_entity"),
        FabricBlockEntityTypeBuilder.create(
            ::SpecBlockEntity,
            GARNET_RUNNER_BLOCK,
            GARNET_RECORDER_BLOCK,
        ).build(),
    )

    val GARNET_RUNNER_ITEM: BlockItem = registerBlockItem("garnet_runner", GARNET_RUNNER_BLOCK)
    val GARNET_RECORDER_ITEM: BlockItem = registerBlockItem("garnet_recorder", GARNET_RECORDER_BLOCK)

    val INPUT_SPEC_MARKER: InputSpecMarkerItem = registerItem("input_spec_marker", ::InputSpecMarkerItem)
    val OUTPUT_SPEC_MARKER: OutputSpecMarkerItem = registerItem("output_spec_marker", ::OutputSpecMarkerItem)

    fun register() {
        LOGGER.debug("[ModRegistries#register] registering blocks, block entities, and items")
    }

    private fun id(path: String): Identifier = Identifier.fromNamespaceAndPath("garnet", path)

    private fun <T : Block> registerBlock(
        id: String, factory: (BlockBehaviour.Properties) -> T, properties: BlockBehaviour.Properties
    ): T {
        val identifier = id(id)
        val key = ResourceKey.create(Registries.BLOCK, identifier)
        properties.setId(key)
        return Registry.register(BuiltInRegistries.BLOCK, identifier, factory(properties))
    }

    private fun registerBlockItem(id: String, block: Block): BlockItem {
        val identifier = id(id)
        return Registry.register(
            BuiltInRegistries.ITEM, identifier,
            BlockItem(block, Item.Properties().setId(ResourceKey.create(Registries.ITEM, identifier)))
        )
    }

    private fun <T : Item> registerItem(id: String, factory: (Item.Properties) -> T): T {
        val identifier = id(id)
        val props = Item.Properties().setId(ResourceKey.create(Registries.ITEM, identifier))
        return Registry.register(BuiltInRegistries.ITEM, identifier, factory(props))
    }
}
