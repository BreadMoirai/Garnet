package com.breadmoirai.redstonespecs.client.screen

import com.breadmoirai.redstonespecs.block.SpecOriginBlockEntity
import com.breadmoirai.redstonespecs.data.AutoSpec
import com.breadmoirai.redstonespecs.data.BreakpointSpec
import com.breadmoirai.redstonespecs.data.InputSpec
import com.breadmoirai.redstonespecs.data.OutputSpec
import com.breadmoirai.redstonespecs.data.Phase
import com.breadmoirai.redstonespecs.data.SimTime
import com.breadmoirai.redstonespecs.data.SpecEntry
import com.breadmoirai.redstonespecs.data.StateSpec
import com.breadmoirai.redstonespecs.network.RemoveSpecEntryC2SPayload
import com.breadmoirai.redstonespecs.network.SaveSpecEntryC2SPayload
import com.breadmoirai.redstonespecs.runner.captureBlockStateProps
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.CycleButton
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
    private val panelH = 260
    private val panelX get() = (width - panelW) / 2
    private val panelY get() = (height - panelH) / 2

    private var labelEditBox: EditBox? = null
    private var colorEditBox: EditBox? = null

    // Persists across rebuildWidgets(); null until entry is available from server BE.
    private var workingEntries: MutableList<Pair<SimTime, Map<String, String>>>? = null
    private var showAddForm = false

    // Add-form field refs (populated in init() when showAddForm=true)
    private var addTickEditBox: EditBox? = null
    private var addPhaseButton: CycleButton<Phase>? = null
    private var addPropsEditBox: EditBox? = null

    override fun init() {
        super.init()
        val x = panelX
        val y = panelY
        val entry = getEntry()

        if (workingEntries == null) {
            workingEntries = when (entry) {
                is InputSpec -> entry.stateSpec.entries.toMutableList()
                is OutputSpec -> entry.stateSpec.entries.toMutableList()
                else -> null
            }
        }

        labelEditBox = EditBox(font, x + 52, y + 26, 200, 16, Component.literal("Label")).also {
            it.value = entry?.label ?: ""
            addRenderableWidget(it)
        }
        colorEditBox = EditBox(font, x + 52, y + 46, 80, 16, Component.literal("Color")).also {
            it.value = entry?.color?.let { c -> String.format("%06X", c and 0xFFFFFF) } ?: "FFFFFF"
            addRenderableWidget(it)
        }

        val entries = workingEntries
        if (entries != null) {
            // [✕] remove button per visible row (max 4)
            entries.take(4).forEachIndexed { i, _ ->
                val idx = i
                addRenderableWidget(
                    Button.builder(Component.literal("✕")) {
                        entries.removeAt(idx)
                        rebuildWidgets()
                    }.bounds(x + panelW - 22, y + 80 + i * 14, 14, 12).build()
                )
            }

            // "+ Add Entry" button
            addRenderableWidget(
                Button.builder(Component.literal("+ Add Entry")) {
                    showAddForm = true
                    rebuildWidgets()
                }.bounds(x + 10, y + 140, 80, 14).build()
            )

            if (showAddForm) {
                // Tick EditBox — width=30 (unique, used by ClientGameTest)
                addTickEditBox = EditBox(font, x + 46, y + 164, 30, 12, Component.literal("tick")).also {
                    addRenderableWidget(it)
                }
                // Phase CycleButton — default END_OF_TICK; width=110
                addPhaseButton = CycleButton.builder<Phase>(
                    { phase -> Component.literal(phase.name) },
                    Phase.END_OF_TICK,
                )
                    .withValues(*Phase.entries.toTypedArray())
                    .create(x + 82, y + 164, 110, 12, Component.literal("Phase"))
                    .also { addRenderableWidget(it) }
                // Props EditBox — width=220 (unique, used by ClientGameTest)
                addPropsEditBox = EditBox(font, x + 10, y + 180, 220, 12, Component.literal("key=val,key=val")).also {
                    addRenderableWidget(it)
                }
                addRenderableWidget(
                    Button.builder(Component.literal("Confirm")) { confirmAddEntry() }
                        .bounds(x + 10, y + 196, 58, 14).build()
                )
                addRenderableWidget(
                    Button.builder(Component.literal("Cancel Add")) {
                        showAddForm = false
                        rebuildWidgets()
                    }.bounds(x + 74, y + 196, 68, 14).build()
                )
            }

            addRenderableWidget(
                Button.builder(Component.literal("Capture Init State")) { captureInitState() }
                    .bounds(x + 10, y + 220, 130, 14).build()
            )
        }

        addRenderableWidget(
            Button.builder(Component.literal("Save")) { save() }
                .bounds(x + 10, y + panelH - 22, 60, 18).build()
        )
        addRenderableWidget(
            Button.builder(Component.literal("Remove")) { remove() }
                .bounds(x + 76, y + panelH - 22, 60, 18).build()
        )
        addRenderableWidget(
            Button.builder(CommonComponents.GUI_CANCEL) { onClose() }
                .bounds(x + panelW - 66, y + panelH - 22, 60, 18).build()
        )
    }

    // Handles server→client BE sync race: if workingEntries is still null but entry
    // is now available (packet arrived after screen opened), initialize and rebuild.
    override fun tick() {
        super.tick()
        if (workingEntries == null) {
            when (val entry = getEntry()) {
                is InputSpec -> {
                    workingEntries = entry.stateSpec.entries.toMutableList()
                    rebuildWidgets()
                }
                is OutputSpec -> {
                    workingEntries = entry.stateSpec.entries.toMutableList()
                    rebuildWidgets()
                }
                else -> {}
            }
        }
    }

    private fun confirmAddEntry() {
        val entries = workingEntries ?: return
        val tickText = addTickEditBox?.value?.trim() ?: ""
        val phase = addPhaseButton?.getValue() ?: Phase.END_OF_TICK
        val propsText = addPropsEditBox?.value?.trim() ?: ""

        val simTime = if (tickText.isEmpty()) SimTime.INIT
        else {
            val tick = tickText.toIntOrNull() ?: return
            SimTime(tick, phase)
        }

        val props = if (propsText.isEmpty()) emptyMap()
        else propsText.split(",").mapNotNull { token ->
            val kv = token.trim().split("=", limit = 2)
            if (kv.size == 2) kv[0].trim() to kv[1].trim() else null
        }.toMap()

        entries.add(simTime to props)
        showAddForm = false
        rebuildWidgets()
    }

    override fun extractRenderState(
        extractor: GuiGraphicsExtractor,
        mouseX: Int,
        mouseY: Int,
        partialTick: Float,
    ) {
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

        val entries = workingEntries
        if (entries != null) {
            extractor.text(
                font,
                Component.literal("State entries: ${entries.size}"),
                x + 8, y + 68, 0x888888,
            )
            entries.take(4).forEachIndexed { i, (simTime, props) ->
                val rowY = y + 82 + i * 14
                val timeLabel = if (simTime == SimTime.INIT) "INIT"
                    else "t${simTime.tick} ${simTime.phase.name.take(5)}"
                val propStr = props.entries.joinToString(",") { "${it.key}=${it.value}" }.let {
                    if (it.length > 28) it.take(27) + "…" else it
                }
                extractor.text(font, Component.literal(timeLabel), x + 10, rowY, 0xAAAAAA)
                extractor.text(font, Component.literal(propStr), x + 68, rowY, 0x888888)
            }
            if (showAddForm) {
                extractor.text(font, Component.literal("Tick:"), x + 8, y + 166, 0xAAAAAA)
            }
        } else {
            when (entry) {
                is BreakpointSpec -> {
                    val color = if (entry.enabled) 0x44FF88 else 0xFF4444
                    extractor.text(
                        font,
                        Component.literal("Enabled: ${entry.enabled}  (${entry.condition::class.simpleName})"),
                        x + 8, y + 70, color,
                    )
                }
                is AutoSpec -> extractor.text(
                    font,
                    Component.literal("Trigger: ${entry.condition::class.simpleName}"),
                    x + 8, y + 70, 0xFFAA00,
                )
                null -> extractor.centeredText(
                    font, Component.literal("Entry not found"), x + panelW / 2, y + 70, 0xFF4444,
                )
                else -> {}
            }
        }
    }

    private fun captureInitState() {
        val mc = minecraft ?: return
        val level = mc.level ?: return
        val worldPos = BlockPos(
            originPos.x + entryRelPos.x,
            originPos.y + entryRelPos.y,
            originPos.z + entryRelPos.z,
        )
        val props = captureBlockStateProps(level.getBlockState(worldPos))
        val entries = workingEntries ?: return
        val idx = entries.indexOfFirst { it.first == SimTime.INIT }
        if (idx >= 0) entries[idx] = SimTime.INIT to props
        else entries.add(0, SimTime.INIT to props)
        rebuildWidgets()
    }

    private fun save() {
        val entry = getEntry() ?: run { onClose(); return }
        val specCaseIndex = getBe()?.activeSpecCaseIndex ?: 0
        val label = labelEditBox?.value ?: ""
        val color = (colorEditBox?.value ?: "FFFFFF").toLongOrNull(16)?.toInt() ?: 0xFFFFFF

        val stateSpec = workingEntries?.let { entries ->
            if (entries.any { it.first == SimTime.INIT }) StateSpec(entries.toList())
            else null
        }

        val updated: SpecEntry = when (entry) {
            is InputSpec -> entry.copy(label = label, color = color,
                stateSpec = stateSpec ?: entry.stateSpec)
            is OutputSpec -> entry.copy(label = label, color = color,
                stateSpec = stateSpec ?: entry.stateSpec)
            is BreakpointSpec -> entry.copy(label = label, color = color)
            is AutoSpec -> entry.copy(label = label, color = color)
        }
        sendPacket(SaveSpecEntryC2SPayload(originPos, specCaseIndex, updated))
        onClose()
    }

    private fun remove() {
        val specCaseIndex = getBe()?.activeSpecCaseIndex ?: 0
        sendPacket(RemoveSpecEntryC2SPayload(originPos, specCaseIndex, entryRelPos))
        onClose()
    }

    override fun onClose() {
        workingEntries = null
        showAddForm = false
        super.onClose()
    }

    override fun isPauseScreen(): Boolean = false

    private fun getBe() = minecraft?.level?.getBlockEntity(originPos) as? SpecOriginBlockEntity
    private fun getEntry(): SpecEntry? {
        val be = getBe() ?: return null
        val spec = be.spec ?: return null
        return spec.specCases.getOrNull(be.activeSpecCaseIndex)?.entryAt(entryRelPos)
    }
    private fun sendPacket(p: CustomPacketPayload) = ClientPlayNetworking.send(p)
}
