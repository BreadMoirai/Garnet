package com.breadmoirai.redstonespecs.client.screen

import com.breadmoirai.redstonespecs.block.SpecOriginBlockEntity
import com.breadmoirai.redstonespecs.data.AutoSpec
import com.breadmoirai.redstonespecs.data.BreakpointSpec
import com.breadmoirai.redstonespecs.data.InputSpec
import com.breadmoirai.redstonespecs.data.OutputSpec
import com.breadmoirai.redstonespecs.data.SimTime
import com.breadmoirai.redstonespecs.data.SpecEntry
import com.breadmoirai.redstonespecs.data.StateSpec
import com.breadmoirai.redstonespecs.network.RemoveSpecEntryC2SPayload
import com.breadmoirai.redstonespecs.network.SaveSpecEntryC2SPayload
import com.breadmoirai.redstonespecs.runner.captureBlockStateProps
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.screens.Screen
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.CommonComponents
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.common.custom.CustomPacketPayload

class SpecEditorScreen(
    private val originPos: BlockPos,
    private val entryRelPos: BlockPos,
) : Screen(Component.translatable("screen.redstonespecs.spec_editor")) {

    private val panelW = 300
    private val panelH = 200
    private val panelX get() = (width - panelW) / 2
    private val panelY get() = (height - panelH) / 2

    private var labelEditBox: EditBox? = null
    private var colorEditBox: EditBox? = null

    override fun init() {
        super.init()
        val x = panelX
        val y = panelY
        val entry = getEntry()

        labelEditBox = EditBox(font, x + 52, y + 26, 200, 16, Component.literal("Label")).also { box ->
            box.value = entry?.label ?: ""
            addRenderableWidget(box)
        }

        colorEditBox = EditBox(font, x + 52, y + 46, 80, 16, Component.literal("Color")).also { box ->
            box.value = entry?.color?.let { String.format("%06X", it and 0xFFFFFF) } ?: "FFFFFF"
            addRenderableWidget(box)
        }

        // Capture current block state as INIT (inputs/outputs only)
        if (entry is InputSpec || entry is OutputSpec) {
            addRenderableWidget(
                Button.builder(Component.literal("Capture Init State")) { captureInitState() }
                    .bounds(x + 10, y + 82, 130, 18).build()
            )
        }

        // Save
        addRenderableWidget(
            Button.builder(Component.literal("Save")) { save() }
                .bounds(x + 10, y + panelH - 26, 60, 20).build()
        )
        // Remove
        addRenderableWidget(
            Button.builder(Component.literal("Remove")) { remove() }
                .bounds(x + 76, y + panelH - 26, 60, 20).build()
        )
        // Cancel
        addRenderableWidget(
            Button.builder(CommonComponents.GUI_CANCEL) { onClose() }
                .bounds(x + panelW - 66, y + panelH - 26, 60, 20).build()
        )
    }

    override fun extractRenderState(
        extractor: GuiGraphicsExtractor,
        mouseX: Int,
        mouseY: Int,
        partialTick: Float,
    ) {
        extractBackground(extractor, mouseX, mouseY, partialTick)
        super.extractRenderState(extractor, mouseX, mouseY, partialTick)

        val x = panelX
        val y = panelY
        val entry = getEntry()

        extractor.fill(x, y, x + panelW, y + panelH, 0xCC000000.toInt())

        val typeLabel = when (entry) {
            is InputSpec -> "Input"
            is OutputSpec -> "Output"
            is BreakpointSpec -> "Breakpoint"
            is AutoSpec -> "AutoSpec"
            null -> "Entry"
        }
        extractor.centeredText(font, Component.literal("$typeLabel @ $entryRelPos"), x + panelW / 2, y + 6, 0xFFFFFF)
        extractor.text(font, Component.literal("Label:"), x + 8, y + 29, 0xAAAAAA)
        extractor.text(font, Component.literal("Color:"), x + 8, y + 49, 0xAAAAAA)

        when (entry) {
            is InputSpec, is OutputSpec -> {
                val stateSpec = if (entry is InputSpec) entry.stateSpec else (entry as OutputSpec).stateSpec
                extractor.text(
                    font,
                    Component.literal("State entries: ${stateSpec.entries.size}"),
                    x + 8, y + 70, 0x888888,
                )
                stateSpec.entries.take(3).forEachIndexed { i, (simTime, props) ->
                    val label = if (simTime == SimTime.INIT) "INIT" else "${simTime.tick}t ${simTime.phase.name}"
                    extractor.text(
                        font,
                        Component.literal("  $label → ${props.entries.joinToString { "${it.key}=${it.value}" }}"),
                        x + 8, y + 82 + i * 12, 0x666666,
                    )
                }
            }
            is BreakpointSpec -> {
                val color = if (entry.enabled) 0x44FF88 else 0xFF4444
                extractor.text(
                    font,
                    Component.literal("Enabled: ${entry.enabled}  (condition: ${entry.condition::class.simpleName})"),
                    x + 8, y + 70, color,
                )
            }
            is AutoSpec -> {
                extractor.text(
                    font,
                    Component.literal("Trigger: ${entry.condition::class.simpleName}"),
                    x + 8, y + 70, 0xFFAA00,
                )
            }
            null -> extractor.centeredText(font, Component.literal("Entry not found"), x + panelW / 2, y + 70, 0xFF4444)
        }
    }

    private fun captureInitState() {
        val mc = minecraft ?: return
        val level = mc.level ?: return
        val worldPos = BlockPos(originPos.x + entryRelPos.x, originPos.y + entryRelPos.y, originPos.z + entryRelPos.z)
        val props = captureBlockStateProps(level.getBlockState(worldPos))
        val entry = getEntry() ?: return
        val specCaseIndex = getBe()?.activeSpecCaseIndex ?: 0
        val updatedStateSpec = StateSpec(listOf(SimTime.INIT to props))
        val updatedEntry: SpecEntry = when (entry) {
            is InputSpec -> entry.copy(stateSpec = updatedStateSpec)
            is OutputSpec -> entry.copy(stateSpec = updatedStateSpec)
            else -> return
        }
        sendPacket(SaveSpecEntryC2SPayload(originPos, specCaseIndex, updatedEntry))
    }

    private fun save() {
        val entry = getEntry() ?: run { onClose(); return }
        val specCaseIndex = getBe()?.activeSpecCaseIndex ?: 0
        val labelValue = labelEditBox?.value ?: ""
        val colorValue = colorEditBox?.value ?: "FFFFFF"
        val color = colorValue.toLongOrNull(16)?.toInt() ?: 0xFFFFFF
        val updatedEntry: SpecEntry = when (entry) {
            is InputSpec -> entry.copy(label = labelValue, color = color)
            is OutputSpec -> entry.copy(label = labelValue, color = color)
            is BreakpointSpec -> entry.copy(label = labelValue, color = color)
            is AutoSpec -> entry.copy(label = labelValue, color = color)
        }
        sendPacket(SaveSpecEntryC2SPayload(originPos, specCaseIndex, updatedEntry))
        onClose()
    }

    private fun remove() {
        val specCaseIndex = getBe()?.activeSpecCaseIndex ?: 0
        sendPacket(RemoveSpecEntryC2SPayload(originPos, specCaseIndex, entryRelPos))
        onClose()
    }

    override fun isPauseScreen(): Boolean = false

    private fun getBe() = minecraft?.level?.getBlockEntity(originPos) as? SpecOriginBlockEntity
    private fun getEntry(): SpecEntry? {
        val be = getBe() ?: return null
        val spec = be.spec ?: return null
        return spec.specCases.getOrNull(be.activeSpecCaseIndex)?.entryAt(entryRelPos)
    }

    private fun sendPacket(payload: CustomPacketPayload) = ClientPlayNetworking.send(payload)
}
