package com.breadmoirai.redstonespecs.client.screen

import com.breadmoirai.redstonespecs.network.RunnerLoadSpecC2SPayload
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

class RunnerSpecPickerScreen(
    private val originPos: BlockPos,
    private val files: List<SpecFileInfo>,
) : Screen(Component.literal("Select a Spec")) {

    override fun isPauseScreen() = false
    override fun isInGameUi() = true

    override fun init() {
        super.init()

        val outer = LinearLayout.vertical().spacing(8)
        outer.addChild(StringWidget(Component.literal("Select a Spec"), font))
        outer.addChild(SpacerElement(0, 4))

        val listContent = LinearLayout.vertical().spacing(2)
        if (files.isEmpty()) {
            listContent.addChild(StringWidget(300, 18, Component.literal("(no saved specs)"), font))
        } else {
            files.forEach { info ->
                val label = "${info.id}  (${info.lifespan}t, ${info.inputCount}→${info.outputCount})"
                listContent.addChild(Button.builder(Component.literal(label)) {
                    ClientPlayNetworking.send(RunnerLoadSpecC2SPayload(originPos, info.id))
                    onClose()
                }.pos(0, 0).width(300).build())
            }
        }
        val listHeight = (height - 80).coerceAtLeast(60)
        outer.addChild(ScrollableLayout(minecraft, listContent, listHeight))

        outer.addChild(SpacerElement(0, 4))
        outer.addChild(Button.builder(CommonComponents.GUI_CANCEL) {
            onClose()
        }.pos(0, 0).width(100).build())

        outer.arrangeElements()
        FrameLayout.centerInRectangle(outer, 10, 10, width - 10, height - 10)
        outer.visitWidgets { addRenderableWidget(it) }
    }
}
