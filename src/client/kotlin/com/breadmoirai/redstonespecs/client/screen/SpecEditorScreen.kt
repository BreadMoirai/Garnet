package com.breadmoirai.redstonespecs.client.screen

import com.breadmoirai.redstonespecs.block.SpecOriginBlockEntity
import com.breadmoirai.redstonespecs.data.AutoSpec
import com.breadmoirai.redstonespecs.data.BreakpointSpec
import com.breadmoirai.redstonespecs.data.InputSpec
import com.breadmoirai.redstonespecs.data.OutputSpec
import com.breadmoirai.redstonespecs.data.Phase
import com.breadmoirai.redstonespecs.data.SimTime
import com.breadmoirai.redstonespecs.data.SpecEntry
import com.breadmoirai.redstonespecs.data.StateCondition
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
    private var workingEntries: MutableList<Pair<SimTime, StateCondition>>? = null
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
                is InputSpec -> entry.entries.toMutableList()
                is OutputSpec -> entry.entries.toMutableList()
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
                    workingEntries = entry.entries.toMutableList()
                    rebuildWidgets()
                }
                is OutputSpec -> {
                    workingEntries = entry.entries.toMutableList()
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

        // Parse key=val pairs into EnumProperty conditions (string-based, works for editor input)
        val conditions = if (propsText.isEmpty()) emptyList()
        else propsText.split(",").mapNotNull { token ->
            val kv = token.trim().split("=", limit = 2)
            if (kv.size == 2) StateCondition.EnumProperty(kv[0].trim(), kv[1].trim()) else null
        }

        val condition: StateCondition = when (conditions.size) {
            0 -> StateCondition.All(emptyList())
            1 -> conditions[0]
            else -> StateCondition.All(conditions)
        }

        entries.add(simTime to condition)
        showAddForm = false
        rebuildWidgets()
    }

    override fun extractRenderState(
        extractor: GuiGraphicsExtractor,
        mouseX: Int,
        mouseY: Int,
        partialTick: Float,
    ) {
        val x = panelX
        val y = panelY
        val entry = getEntry()

        super.extractRenderState(extractor, mouseX, mouseY, partialTick)

        val typeLabel = when (entry) {
            is InputSpec -> "Input"
            is OutputSpec -> "Output"
            is BreakpointSpec -> "Breakpoint"
            is AutoSpec -> "AutoSpec"
            null -> "Entry"
        }
        extractor.centeredText(font, Component.literal("$typeLabel @ $entryRelPos"), x + panelW / 2, y + 6, 0xFFFFFFFF.toInt())
        extractor.text(font, Component.literal("Label:"), x + 8, y + 29, 0xFFAAAAAA.toInt())
        extractor.text(font, Component.literal("Color:"), x + 8, y + 49, 0xFFAAAAAA.toInt())

        val entries = workingEntries
        if (entries != null) {
            extractor.text(
                font,
                Component.literal("State entries: ${entries.size}"),
                x + 8, y + 68, 0xFF888888.toInt(),
            )
            entries.take(4).forEachIndexed { i, (simTime, condition) ->
                val rowY = y + 82 + i * 14
                val timeLabel = if (simTime == SimTime.INIT) "INIT"
                    else "t${simTime.tick} ${simTime.phase.name.take(5)}"
                val condStr = conditionToDisplayString(condition).let {
                    if (it.length > 28) it.take(27) + "…" else it
                }
                extractor.text(font, Component.literal(timeLabel), x + 10, rowY, 0xFFAAAAAA.toInt())
                extractor.text(font, Component.literal(condStr), x + 68, rowY, 0xFF888888.toInt())
            }
            if (showAddForm) {
                extractor.text(font, Component.literal("Tick:"), x + 8, y + 166, 0xFFAAAAAA.toInt())
            }
        } else {
            when (entry) {
                is BreakpointSpec -> {
                    val color = if (entry.enabled) 0xFF44FF88.toInt() else 0xFFFF4444.toInt()
                    extractor.text(
                        font,
                        Component.literal("Enabled: ${entry.enabled}  (${entry.condition::class.simpleName})"),
                        x + 8, y + 70, color,
                    )
                }
                is AutoSpec -> extractor.text(
                    font,
                    Component.literal("Trigger: ${entry.condition::class.simpleName}"),
                    x + 8, y + 70, 0xFFFFAA00.toInt(),
                )
                null -> extractor.centeredText(
                    font, Component.literal("Entry not found"), x + panelW / 2, y + 70, 0xFFFF4444.toInt(),
                )
                else -> {}
            }
        }
    }

    private fun conditionToDisplayString(condition: StateCondition): String = when (condition) {
        is StateCondition.All -> condition.conditions.joinToString(",") { conditionToDisplayString(it) }
        is StateCondition.BoolProperty -> "${condition.name}=${condition.value}"
        is StateCondition.IntProperty -> "${condition.name}=${condition.value}"
        is StateCondition.EnumProperty -> "${condition.name}=${condition.value}"
        is StateCondition.BlockType -> "block=${condition.blockId}"
        is StateCondition.Any -> "any(${condition.conditions.size})"
        is StateCondition.Not -> "not(${conditionToDisplayString(condition.condition)})"
        is StateCondition.ContainerContents -> "container[${condition.slot}]"
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
        // Build an All condition from captured string props using EnumProperty (display-only approach)
        val conditions = props.map { (k, v) -> StateCondition.EnumProperty(k, v) }
        val condition: StateCondition = when (conditions.size) {
            0 -> StateCondition.All(emptyList())
            1 -> conditions[0]
            else -> StateCondition.All(conditions)
        }
        val idx = entries.indexOfFirst { it.first == SimTime.INIT }
        if (idx >= 0) entries[idx] = SimTime.INIT to condition
        else entries.add(0, SimTime.INIT to condition)
        rebuildWidgets()
    }

    private fun save() {
        val entry = getEntry() ?: run { onClose(); return }
        val specCaseIndex = getBe()?.activeSpecCaseIndex ?: 0
        val label = labelEditBox?.value ?: ""
        val color = (colorEditBox?.value ?: "FFFFFF").toLongOrNull(16)?.toInt() ?: 0xFFFFFF

        val newEntries = workingEntries?.toList()

        val updated: SpecEntry = when (entry) {
            is InputSpec -> entry.copy(label = label, color = color,
                entries = newEntries ?: entry.entries)
            is OutputSpec -> entry.copy(label = label, color = color,
                entries = newEntries ?: entry.entries)
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
    override fun isInGameUi(): Boolean = true

    private fun getBe() = minecraft?.level?.getBlockEntity(originPos) as? SpecOriginBlockEntity
    private fun getEntry(): SpecEntry? {
        val be = getBe() ?: return null
        val spec = be.spec ?: return null
        return spec.specCases.getOrNull(be.activeSpecCaseIndex)?.entryAt(entryRelPos)
    }
    private fun sendPacket(p: CustomPacketPayload) = ClientPlayNetworking.send(p)
}
