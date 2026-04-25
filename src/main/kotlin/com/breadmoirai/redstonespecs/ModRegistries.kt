package com.breadmoirai.redstonespecs

import com.breadmoirai.redstonespecs.block.RedstoneSpecEditorBlock
import com.breadmoirai.redstonespecs.block.RedstoneSpecRecorderBlock
import com.breadmoirai.redstonespecs.block.RedstoneSpecRunnerBlock
import com.breadmoirai.redstonespecs.block.SpecBlockEntity
import com.breadmoirai.redstonespecs.item.AutoSpecMarkerItem
import com.breadmoirai.redstonespecs.item.BreakpointSpecMarkerItem
import com.breadmoirai.redstonespecs.item.InputSpecMarkerItem
import com.breadmoirai.redstonespecs.item.OutputSpecMarkerItem
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
    private val LOGGER = LoggerFactory.getLogger("Redstone Specs")

    private val sharedProps: BlockBehaviour.Properties
        get() = BlockBehaviour.Properties.of().strength(2f).noOcclusion()

    val REDSTONE_SPEC_RUNNER_BLOCK: RedstoneSpecRunnerBlock = registerBlock(
        "redstone_spec_runner", ::RedstoneSpecRunnerBlock, sharedProps
    )
    val REDSTONE_SPEC_EDITOR_BLOCK: RedstoneSpecEditorBlock = registerBlock(
        "redstone_spec_editor", ::RedstoneSpecEditorBlock, sharedProps
    )
    val REDSTONE_SPEC_RECORDER_BLOCK: RedstoneSpecRecorderBlock = registerBlock(
        "redstone_spec_recorder", ::RedstoneSpecRecorderBlock, sharedProps
    )

    val SPEC_BLOCK_ENTITY_TYPE: BlockEntityType<SpecBlockEntity> = Registry.register(
        BuiltInRegistries.BLOCK_ENTITY_TYPE,
        id("spec_block_entity"),
        FabricBlockEntityTypeBuilder.create(
            ::SpecBlockEntity,
            REDSTONE_SPEC_RUNNER_BLOCK,
            REDSTONE_SPEC_EDITOR_BLOCK,
            REDSTONE_SPEC_RECORDER_BLOCK,
        ).build(),
    )

    val REDSTONE_SPEC_RUNNER_ITEM: BlockItem = registerBlockItem("redstone_spec_runner", REDSTONE_SPEC_RUNNER_BLOCK)
    val REDSTONE_SPEC_EDITOR_ITEM: BlockItem = registerBlockItem("redstone_spec_editor", REDSTONE_SPEC_EDITOR_BLOCK)
    val REDSTONE_SPEC_RECORDER_ITEM: BlockItem = registerBlockItem("redstone_spec_recorder", REDSTONE_SPEC_RECORDER_BLOCK)

    val INPUT_SPEC_MARKER: InputSpecMarkerItem = registerItem("input_spec_marker", ::InputSpecMarkerItem)
    val OUTPUT_SPEC_MARKER: OutputSpecMarkerItem = registerItem("output_spec_marker", ::OutputSpecMarkerItem)
    val BREAKPOINT_SPEC_MARKER: BreakpointSpecMarkerItem = registerItem("breakpoint_spec_marker", ::BreakpointSpecMarkerItem)
    val AUTO_SPEC_MARKER: AutoSpecMarkerItem = registerItem("auto_spec_marker", ::AutoSpecMarkerItem)

    fun register() {
        LOGGER.debug("[ModRegistries#register] registering blocks, block entities, and items")
    }

    private fun id(path: String): Identifier = Identifier.fromNamespaceAndPath("redstonespecs", path)

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
