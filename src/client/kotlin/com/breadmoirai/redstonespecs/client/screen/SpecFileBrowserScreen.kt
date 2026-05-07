package com.breadmoirai.redstonespecs.client.screen

import com.breadmoirai.redstonespecs.network.LoadFromFileC2SPayload
import com.breadmoirai.redstonespecs.network.SpecFileInfo
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.ScrollableLayout
import net.minecraft.client.gui.components.StringWidget
import net.minecraft.client.gui.layouts.FrameLayout
import net.minecraft.client.gui.layouts.LinearLayout
import net.minecraft.client.gui.layouts.SpacerElement
import net.minecraft.client.gui.screens.Screen
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.CommonComponents
import net.minecraft.network.chat.Component

class SpecFileBrowserScreen(
    private val originPos: BlockPos,
    private val files: List<SpecFileInfo>,
) : Screen(Component.literal("Load Spec")) {

    private var selected: SpecFileInfo? = null

    override fun isPauseScreen() = false
    override fun isInGameUi() = true

    override fun init() {
        super.init()

        val root = LinearLayout.horizontal().spacing(8)

        // Left panel: file list
        val listContent = LinearLayout.vertical().spacing(2)
        if (files.isEmpty()) {
            listContent.addChild(StringWidget(160, 18, Component.literal("(no saved specs)"), font))
        } else {
            files.forEach { info ->
                val isSelected = info.id == selected?.id
                val label = Component.literal(if (isSelected) "► ${info.id}" else "  ${info.id}")
                listContent.addChild(Button.builder(label) {
                    selected = info
                    rebuildWidgets()
                }.pos(0, 0).width(160).build())
            }
        }
        val listHeight = (height - 60).coerceAtLeast(60)
        root.addChild(ScrollableLayout(minecraft, listContent, listHeight))

        // Right panel: preview
        val preview = LinearLayout.vertical().spacing(4)
        val sel = selected
        if (sel != null) {
            preview.addChild(StringWidget(180, 12, Component.literal("ID: ${sel.id}"), font))
            preview.addChild(StringWidget(180, 12, Component.literal("Lifespan: ${sel.lifespan}"), font))
            preview.addChild(StringWidget(180, 12, Component.literal("Inputs: ${sel.inputCount}"), font))
            preview.addChild(StringWidget(180, 12, Component.literal("Outputs: ${sel.outputCount}"), font))
            preview.addChild(StringWidget(180, 12, Component.literal("Structure: ${sel.structure ?: sel.id}"), font))
        } else {
            preview.addChild(StringWidget(180, 12, Component.literal("Select a spec to preview"), font))
        }
        root.addChild(preview)

        val outer = LinearLayout.vertical().spacing(8)
        outer.addChild(StringWidget(Component.literal("Load Spec"), font))
        outer.addChild(SpacerElement(0, 4))
        outer.addChild(root)
        outer.addChild(SpacerElement(0, 4))

        val bottomRow = LinearLayout.horizontal().spacing(4)
        bottomRow.addChild(Button.builder(Component.literal("Load")) {
            val id = selected?.id ?: return@builder
            ClientPlayNetworking.send(LoadFromFileC2SPayload(originPos, id))
            onClose()
        }.pos(0, 0).width(60).build().also { it.active = selected != null })
        bottomRow.addChild(Button.builder(CommonComponents.GUI_CANCEL) {
            onClose()
        }.pos(0, 0).width(60).build())
        outer.addChild(bottomRow)

        outer.arrangeElements()
        FrameLayout.centerInRectangle(outer, 10, 10, width - 10, height - 10)
        outer.visitWidgets { addRenderableWidget(it) }
    }
}
