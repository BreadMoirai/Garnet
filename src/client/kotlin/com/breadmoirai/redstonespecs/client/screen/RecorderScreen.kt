package com.breadmoirai.redstonespecs.client.screen

import com.breadmoirai.redstonespecs.network.RecorderCmd
import com.breadmoirai.redstonespecs.network.RecorderCommandC2S
import com.breadmoirai.redstonespecs.network.SetRecorderConfigC2S
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.components.StringWidget
import net.minecraft.client.gui.layouts.FrameLayout
import net.minecraft.client.gui.layouts.LinearLayout
import net.minecraft.client.gui.layouts.SpacerElement
import net.minecraft.client.gui.screens.Screen
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component

class RecorderScreen(
    val originPos: BlockPos,
    private val initialSpecId: String,
    private val initialOutPath: String,
    private val initialStructureId: String,
    private val initialState: String,
) : Screen(Component.literal("Spec Recorder")) {

    private var specIdBox: EditBox? = null
    private var outPathBox: EditBox? = null
    private var structureIdBox: EditBox? = null

    override fun isPauseScreen() = false
    override fun isInGameUi() = true

    override fun init() {
        super.init()

        val content = LinearLayout.vertical().spacing(6)

        // Title
        content.addChild(StringWidget(Component.literal("Spec Recorder"), font))
        content.addChild(SpacerElement(0, 2))

        // State label
        content.addChild(StringWidget(Component.literal("State: $initialState"), font))
        content.addChild(SpacerElement(0, 4))

        // Spec ID
        val idRow = LinearLayout.horizontal().spacing(4)
        idRow.addChild(StringWidget(80, 20, Component.literal("Spec ID:"), font))
        val idBox = EditBox(font, 200, 20, Component.literal("Spec ID"))
        idBox.value = initialSpecId
        idBox.setResponder { sendSetConfig() }
        idRow.addChild(idBox)
        content.addChild(idRow)
        specIdBox = idBox

        // Output path
        val outRow = LinearLayout.horizontal().spacing(4)
        outRow.addChild(StringWidget(80, 20, Component.literal("Out Path:"), font))
        val outBox = EditBox(font, 200, 20, Component.literal("Output Path"))
        outBox.value = initialOutPath
        outBox.setResponder { sendSetConfig() }
        outRow.addChild(outBox)
        content.addChild(outRow)
        outPathBox = outBox

        // Structure ID
        val structRow = LinearLayout.horizontal().spacing(4)
        structRow.addChild(StringWidget(80, 20, Component.literal("Structure:"), font))
        val structBox = EditBox(font, 200, 20, Component.literal("Structure ID"))
        structBox.value = initialStructureId
        structBox.setResponder { sendSetConfig() }
        structRow.addChild(structBox)
        content.addChild(structRow)
        structureIdBox = structBox

        content.addChild(SpacerElement(0, 8))

        // Action buttons
        val btnRow = LinearLayout.horizontal().spacing(6)
        btnRow.addChild(
            Button.builder(Component.literal("Start")) {
                ClientPlayNetworking.send(RecorderCommandC2S(originPos, RecorderCmd.START))
            }.bounds(0, 0, 80, 20).build()
        )
        btnRow.addChild(
            Button.builder(Component.literal("Stop & Emit")) {
                ClientPlayNetworking.send(RecorderCommandC2S(originPos, RecorderCmd.STOP))
            }.bounds(0, 0, 80, 20).build()
        )
        btnRow.addChild(
            Button.builder(Component.literal("Discard")) {
                ClientPlayNetworking.send(RecorderCommandC2S(originPos, RecorderCmd.DISCARD))
            }.bounds(0, 0, 80, 20).build()
        )
        content.addChild(btnRow)

        content.addChild(SpacerElement(0, 4))

        // Done
        content.addChild(
            Button.builder(Component.literal("Done")) {
                onClose()
            }.bounds(0, 0, 200, 20).build()
        )

        content.arrangeElements()
        FrameLayout.centerInRectangle(content, 10, 10, width - 10, height - 10)
        content.visitWidgets { addRenderableWidget(it) }
    }

    private fun sendSetConfig() {
        val specId = specIdBox?.value ?: return
        val outPath = outPathBox?.value ?: return
        val structureId = structureIdBox?.value ?: return
        ClientPlayNetworking.send(SetRecorderConfigC2S(originPos, specId, outPath, structureId))
    }
}
