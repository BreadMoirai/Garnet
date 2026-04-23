package com.breadmoirai.redstonespecs.client.screen

import com.breadmoirai.redstonespecs.block.RedstoneSpecBlockEntity
import com.breadmoirai.redstonespecs.data.*
import com.breadmoirai.redstonespecs.network.*
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.screens.Screen
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.CommonComponents
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.common.custom.CustomPacketPayload

class SpecOverviewScreen(
    val originPos: BlockPos,
    val availableStructures: List<String>,
) : Screen(Component.translatable("screen.redstonespecs.spec_overview")) {

    private val panelW = 340
    private val panelH = 260
    private val panelX get() = (width - panelW) / 2
    private val panelY get() = (height - panelH) / 2

    private var idEditMode = false
    private var idEditBox: EditBox? = null
    private var structureEditMode = false
    private var structureEditBox: EditBox? = null
    private var entryScrollOffset = 0

    override fun isPauseScreen() = false
    override fun isInGameUi() = true

    override fun init() {
        super.init()
        val x = panelX; val y = panelY
        val spec = getSpec()

        // ID row
        if (idEditMode) {
            idEditBox = EditBox(font, x + 32, y + 14, panelW - 60, 14, Component.empty()).also {
                it.value = spec?.id ?: ""
                addRenderableWidget(it)
            }
            addRenderableWidget(Button.builder(Component.literal("✔")) {
                val newId = idEditBox?.value?.trim()?.takeIf { v -> v.isNotBlank() } ?: return@builder
                sendPacket(SetSpecIdC2SPayload(originPos, newId))
                idEditMode = false; rebuildWidgets()
            }.bounds(x + panelW - 26, y + 14, 18, 14).build())
        } else {
            addRenderableWidget(Button.builder(Component.literal("✎")) {
                idEditMode = true; rebuildWidgets()
            }.bounds(x + panelW - 26, y + 14, 18, 14).build())
        }

        // Mode cycle button
        val modeLabel = when (spec?.mode) {
            SpecMode.SIMPLE -> "Simple"
            SpecMode.TICK_AWARE -> "Tick-Aware"
            SpecMode.UPDATE_AWARE -> "Update-Aware"
            null -> "—"
        }
        addRenderableWidget(Button.builder(Component.literal("◄ $modeLabel ►")) {
            val current = spec?.mode ?: SpecMode.SIMPLE
            val next = SpecMode.entries[(current.ordinal + 1) % SpecMode.entries.size]
            sendPacket(SetSpecModeC2SPayload(originPos, next))
            rebuildWidgets()
        }.bounds(x + 60, y + 32, 120, 14).build())

        // Lifespan stepper
        addRenderableWidget(Button.builder(Component.literal("-")) {
            val l = (spec?.lifespan ?: 20) - 1
            if (l >= 1) sendPacket(SetLifespanC2SPayload(originPos, l))
            rebuildWidgets()
        }.bounds(x + 60, y + 50, 14, 14).build())
        addRenderableWidget(Button.builder(Component.literal("+")) {
            sendPacket(SetLifespanC2SPayload(originPos, (spec?.lifespan ?: 20) + 1))
            rebuildWidgets()
        }.bounds(x + 100, y + 50, 14, 14).build())

        // Structure field
        if (structureEditMode) {
            structureEditBox = EditBox(font, x + 60, y + 68, panelW - 88, 14, Component.empty()).also {
                it.value = spec?.structure ?: (spec?.id ?: "")
                addRenderableWidget(it)
            }
            addRenderableWidget(Button.builder(Component.literal("✔")) {
                val s = structureEditBox?.value?.trim()
                sendPacket(SetStructureC2SPayload(originPos, s?.ifBlank { null }))
                structureEditMode = false; rebuildWidgets()
            }.bounds(x + panelW - 26, y + 68, 18, 14).build())
        } else {
            addRenderableWidget(Button.builder(Component.literal("✎")) {
                structureEditMode = true; rebuildWidgets()
            }.bounds(x + panelW - 26, y + 68, 18, 14).build())
        }

        // Entry list rows (scrollable, 5 visible, 16px each)
        val entries = spec?.allEntries ?: emptyList()
        val visibleCount = 5
        val listY = y + 90
        val visibleEntries = entries.drop(entryScrollOffset).take(visibleCount)
        visibleEntries.forEachIndexed { i, entry ->
            val rowY = listY + i * 16
            val tag = when (entry) {
                is InputSpec -> "IN"
                is OutputSpec -> "OUT"
                is BreakpointSpec -> "BP"
                is AutoSpec -> "AUTO"
            }
            addRenderableWidget(Button.builder(
                Component.literal("► $tag  ${entry.label.ifEmpty { "—" }}  (${entry.pos.x},${entry.pos.y},${entry.pos.z})")
            ) {
                minecraft?.setScreen(SpecEditorScreen(originPos, entry.pos))
            }.bounds(x + 8, rowY, panelW - 16, 14).build())
        }

        // Scroll buttons
        if (entryScrollOffset > 0) {
            addRenderableWidget(Button.builder(Component.literal("▲")) {
                entryScrollOffset--; rebuildWidgets()
            }.bounds(x + panelW - 20, listY, 14, 14).build())
        }
        if (entries.size > entryScrollOffset + visibleCount) {
            addRenderableWidget(Button.builder(Component.literal("▼")) {
                entryScrollOffset++; rebuildWidgets()
            }.bounds(x + panelW - 20, listY + (visibleCount - 1) * 16, 14, 14).build())
        }

        // Bottom buttons: Run, Load, Save, Done
        addRenderableWidget(Button.builder(
            Component.translatable("screen.redstonespecs.spec_overview.run")
        ) { sendPacket(RunSpecC2SPayload(originPos)) }
            .bounds(x + 8, y + panelH - 24, 60, 20).build())

        addRenderableWidget(Button.builder(Component.literal("Load")) {
            val id = spec?.id ?: return@builder
            sendPacket(LoadSpecC2SPayload(originPos, id))
        }.bounds(x + 72, y + panelH - 24, 60, 20).build())

        addRenderableWidget(Button.builder(Component.literal("Save")) {
            sendPacket(SaveSpecC2SPayload(originPos))
        }.bounds(x + 136, y + panelH - 24, 60, 20).build())

        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE) { onClose() }
            .bounds(x + panelW - 66, y + panelH - 24, 58, 20).build())
    }

    override fun extractRenderState(
        extractor: GuiGraphicsExtractor,
        mouseX: Int,
        mouseY: Int,
        partialTick: Float,
    ) {
        super.extractRenderState(extractor, mouseX, mouseY, partialTick)

        val x = panelX; val y = panelY
        val spec = getSpec()

        // Panel background
        extractor.fill(x, y, x + panelW, y + panelH, 0xC0101010.toInt())

        // Title
        extractor.centeredText(font, title, x + panelW / 2, y + 4, 0xFFFFFFFF.toInt())

        // ID label
        extractor.text(font, Component.literal("ID:"), x + 8, y + 16, 0xFF888888.toInt())
        if (!idEditMode) {
            extractor.text(font, Component.literal(spec?.id ?: ""), x + 32, y + 16, 0xFFFFFFFF.toInt())
        }

        // Mode label
        extractor.text(font, Component.literal("Mode:"), x + 8, y + 34, 0xFF888888.toInt())

        // Lifespan
        extractor.text(font, Component.literal("Life:"), x + 8, y + 52, 0xFF888888.toInt())
        extractor.text(font, Component.literal("${spec?.lifespan ?: 0}"), x + 78, y + 52, 0xFFFFFFFF.toInt())
        extractor.text(font, Component.literal("ticks"), x + 116, y + 52, 0xFF888888.toInt())

        // Structure
        extractor.text(font, Component.literal("Struct:"), x + 8, y + 70, 0xFF888888.toInt())
        if (!structureEditMode) {
            extractor.text(font, Component.literal(spec?.structure ?: "(none)"), x + 60, y + 70, 0xFFCCCCCC.toInt())
        }

        // Entry list border
        extractor.fill(x + 6, y + 88, x + panelW - 6, y + 88 + 6 * 16, 0x33FFFFFF.toInt())

        // Last result
        val result = getBe()?.lastTestResult
        if (result != null) {
            val text = if (result.pass) "✓ ${result.passCount}/${result.checks.size} checks passed"
                       else "✗ ${result.checks.size - result.passCount}/${result.checks.size} checks failed"
            val color = if (result.pass) 0xFF44FF88.toInt() else 0xFFFF4444.toInt()
            extractor.text(font, Component.literal(text), x + 8, y + panelH - 46, color)
        }
    }

    override fun onClose() {
        idEditMode = false
        structureEditMode = false
        super.onClose()
    }

    private fun getBe() = minecraft?.level?.getBlockEntity(originPos) as? RedstoneSpecBlockEntity
    private fun getSpec() = getBe()?.spec
    private fun sendPacket(payload: CustomPacketPayload) = ClientPlayNetworking.send(payload)
}
