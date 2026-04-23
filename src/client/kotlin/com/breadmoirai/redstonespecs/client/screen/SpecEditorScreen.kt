package com.breadmoirai.redstonespecs.client.screen

import com.breadmoirai.redstonespecs.block.RedstoneSpecBlockEntity
import com.breadmoirai.redstonespecs.data.AutoSpec
import com.breadmoirai.redstonespecs.data.BreakpointSpec
import com.breadmoirai.redstonespecs.data.InputSpec
import com.breadmoirai.redstonespecs.data.OutputSpec
import com.breadmoirai.redstonespecs.data.Phase
import com.breadmoirai.redstonespecs.data.SimTime
import com.breadmoirai.redstonespecs.data.SpecEntry
import com.breadmoirai.redstonespecs.data.SpecMode
import com.breadmoirai.redstonespecs.data.StateCondition
import com.breadmoirai.redstonespecs.network.RemoveSpecEntryC2SPayload
import com.breadmoirai.redstonespecs.network.SaveSpecEntryC2SPayload
import com.breadmoirai.redstonespecs.runner.captureBlockStateProps
import com.breadmoirai.redstonespecs.runner.propsToCondition
import dev.isxander.yacl3.gui.LowProfileButtonWidget
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.components.ScrollableLayout
import net.minecraft.client.gui.components.StringWidget
import net.minecraft.client.gui.layouts.FrameLayout
import net.minecraft.client.gui.layouts.LinearLayout
import net.minecraft.client.gui.layouts.SpacerElement
import net.minecraft.client.gui.screens.Screen
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.CommonComponents
import net.minecraft.network.chat.Component

class SpecEditorScreen(
    private val originPos: BlockPos,
    private val entryRelPos: BlockPos,
) : Screen(Component.translatable("screen.redstonespecs.spec_editor")) {

    // ── Working state ─────────────────────────────────────────────────────

    private var launched = false
    private var workingLabel: String = ""
    private var workingColor: Int = 0xFFFFFF
    private var workingEntries: MutableList<Pair<SimTime, StateCondition>>? = null
    private var originalEntry: SpecEntry? = null
    private var specMode: SpecMode = SpecMode.SIMPLE

    // ── Screen lifecycle ──────────────────────────────────────────────────

    override fun isPauseScreen() = false
    override fun isInGameUi() = true

    override fun init() {
        super.init()
        if (!launched) return
        buildLayout()
    }

    override fun tick() {
        super.tick()
        if (launched) return
        val be = minecraft?.level?.getBlockEntity(originPos) as? RedstoneSpecBlockEntity ?: return
        val entry = be.spec?.entryAt(entryRelPos) ?: return
        originalEntry = entry
        workingLabel = entry.label
        workingColor = entry.color
        workingEntries = when (entry) {
            is InputSpec -> entry.entries.toMutableList()
            is OutputSpec -> entry.entries.toMutableList()
            else -> null
        }
        specMode = be.spec?.mode ?: SpecMode.SIMPLE
        launched = true
        rebuildWidgets()
    }

    // ── Layout builder ────────────────────────────────────────────────────

    private fun buildLayout() {
        val entry = originalEntry ?: return
        val typeLabel = when (entry) {
            is InputSpec -> "Input"
            is OutputSpec -> "Output"
            is BreakpointSpec -> "Breakpoint"
            is AutoSpec -> "AutoSpec"
        }

        val content = LinearLayout.vertical().spacing(4)

        // Title
        content.addChild(
            StringWidget(Component.literal("$typeLabel @ $entryRelPos"), font)
        )

        content.addChild(SpacerElement(0, 4))

        // Label row
        val labelRow = LinearLayout.horizontal().spacing(4)
        labelRow.addChild(StringWidget(50, 20, Component.literal("Label:"), font))
        val labelBox = EditBox(font, 180, 20, Component.empty())
        labelBox.value = workingLabel
        labelBox.setResponder { workingLabel = it }
        labelRow.addChild(labelBox)
        content.addChild(labelRow)

        // Color row
        val colorRow = LinearLayout.horizontal().spacing(4)
        colorRow.addChild(StringWidget(50, 20, Component.literal("Color:"), font))
        val colorBox = EditBox(font, 80, 20, Component.empty())
        colorBox.value = "%06X".format(workingColor)
        colorBox.setMaxLength(6)
        val swatch = ColorSwatchWidget(0, 0, workingColor)
        colorBox.setResponder { hex ->
            val parsed = hex.toLongOrNull(16)
            if (parsed != null && hex.length <= 6) {
                workingColor = parsed.toInt() and 0xFFFFFF
                swatch.setColor(workingColor)
            }
        }
        colorRow.addChild(colorBox)
        colorRow.addChild(swatch)
        content.addChild(colorRow)

        content.addChild(SpacerElement(0, 4))

        // Entries section (only for InputSpec / OutputSpec)
        val entries = workingEntries
        if (entries != null) {
            content.addChild(StringWidget(Component.literal("Entries:"), font))

            // Per-entry rows inside a scrollable list
            val entryListContent = LinearLayout.vertical().spacing(2)
            entries.forEachIndexed { i, (simTime, condition) ->
                val row = LinearLayout.horizontal().spacing(4)
                row.addChild(
                    StringWidget(180, 18, Component.literal(previewEntry(simTime, condition)), font)
                )
                row.addChild(LowProfileButtonWidget(0, 0, 40, 18, Component.literal("Edit")) {
                    openEntryEditor(i, simTime to condition)
                })
                row.addChild(LowProfileButtonWidget(0, 0, 50, 18, Component.literal("Remove")) {
                    entries.removeAt(i)
                    rebuildWidgets()
                })
                entryListContent.addChild(row)
            }
            if (entries.isEmpty()) {
                entryListContent.addChild(
                    StringWidget(280, 18, Component.literal("(no entries)"), font)
                )
            }

            content.addChild(ScrollableLayout(minecraft, entryListContent, 120))

            // Add Entry button
            content.addChild(
                LowProfileButtonWidget(0, 0, 120, 20, Component.literal("+ Add Entry")) {
                    openEntryEditor(null, null)
                }
            )

            // Capture State button
            content.addChild(
                LowProfileButtonWidget(0, 0, 120, 20, Component.literal("Capture State")) {
                    captureState(entries)
                }
            )

            content.addChild(SpacerElement(0, 4))
        }

        // Remove Spec button
        content.addChild(
            LowProfileButtonWidget(0, 0, 120, 20, Component.literal("Remove Spec")) {
                ClientPlayNetworking.send(RemoveSpecEntryC2SPayload(originPos, entryRelPos))
                minecraft?.setScreen(null)
            }
        )

        content.addChild(SpacerElement(0, 4))

        // Bottom: Save + Cancel
        val bottomRow = LinearLayout.horizontal().spacing(4)
        bottomRow.addChild(
            LowProfileButtonWidget(0, 0, 80, 20, Component.literal("Save")) {
                saveAndClose()
            }
        )
        bottomRow.addChild(
            LowProfileButtonWidget(0, 0, 80, 20, CommonComponents.GUI_CANCEL) {
                onClose()
            }
        )
        content.addChild(bottomRow)

        FrameLayout.centerInRectangle(content, 0, 0, width, height)
        content.arrangeElements()
        content.visitWidgets { addRenderableWidget(it) }
    }

    // ── Actions ───────────────────────────────────────────────────────────

    private fun openEntryEditor(index: Int?, initial: Pair<SimTime, StateCondition>?) {
        val entries = workingEntries ?: return
        val onConfirm: (SimTime, StateCondition) -> Unit = { simTime, condition ->
            if (index == null) {
                entries.add(simTime to condition)
            } else {
                entries[index] = simTime to condition
            }
            minecraft?.setScreen(this)
        }
        minecraft?.setScreen(
            EntryEditorScreen(
                originPos = originPos,
                entryRelPos = entryRelPos,
                specMode = specMode,
                initial = initial,
                onConfirm = onConfirm,
            )
        )
    }

    private fun captureState(entries: MutableList<Pair<SimTime, StateCondition>>) {
        val level = minecraft?.level ?: return
        val worldPos = originPos.offset(entryRelPos)
        val blockState = level.getBlockState(worldPos)
        val currentProps = captureBlockStateProps(blockState)

        if (entries.isEmpty()) {
            entries.add(0, SimTime.INIT to propsToCondition(currentProps, blockState))
            rebuildWidgets()
            return
        }

        val lastEntry = entries.maxByOrNull { it.first } ?: return
        val lastKnown = flattenConditionToMap(lastEntry.second)
        val diff = currentProps.filter { (k, v) -> lastKnown[k] != v }
        if (diff.isEmpty()) return

        val newTick = if (lastEntry.first == SimTime.INIT) 0 else lastEntry.first.tick + 1
        entries.add(SimTime(newTick, Phase.END_OF_TICK) to propsToCondition(diff, blockState))
        rebuildWidgets()
    }

    private fun saveAndClose() {
        val entry = originalEntry ?: return
        val label = workingLabel
        val color = workingColor
        val entries = workingEntries?.toList()
        val updated: SpecEntry = when (entry) {
            is InputSpec -> entry.copy(label = label, color = color, entries = entries ?: entry.entries)
            is OutputSpec -> entry.copy(label = label, color = color, entries = entries ?: entry.entries)
            is BreakpointSpec -> entry.copy(label = label, color = color)
            is AutoSpec -> entry.copy(label = label, color = color)
        }
        ClientPlayNetworking.send(SaveSpecEntryC2SPayload(originPos, updated))
        onClose()
    }
}

// ── Helpers (package-level, reusable by EntryEditorScreen) ────────────────

internal fun previewEntry(simTime: SimTime, condition: StateCondition): String {
    val timeStr = if (simTime == SimTime.INIT) "INIT" else "t${simTime.tick} ${simTime.phase.name.take(5)}"
    val condStr = previewCondition(condition).let { if (it.length > 24) it.take(23) + "…" else it }
    return "$timeStr: $condStr"
}

internal fun previewCondition(condition: StateCondition): String = when (condition) {
    is StateCondition.BoolProperty -> "${condition.name}=${condition.value}"
    is StateCondition.IntProperty -> "${condition.name}=${condition.value}"
    is StateCondition.EnumProperty -> "${condition.name}=${condition.value}"
    is StateCondition.BlockType -> "block=${condition.blockId.path}"
    is StateCondition.All -> condition.conditions.joinToString(",") { previewCondition(it) }
    is StateCondition.Any -> condition.conditions.joinToString("|") { previewCondition(it) }
    is StateCondition.Not -> "!${previewCondition(condition.condition)}"
    is StateCondition.ContainerContents -> "container(...)"
}

internal fun flattenConditionToMap(condition: StateCondition): Map<String, String> {
    val out = mutableMapOf<String, String>()
    fun walk(c: StateCondition) {
        when (c) {
            is StateCondition.All -> c.conditions.forEach(::walk)
            is StateCondition.BoolProperty -> out[c.name] = c.value.toString()
            is StateCondition.IntProperty -> out[c.name] = c.value.toString()
            is StateCondition.EnumProperty -> out[c.name] = c.value
            else -> {}
        }
    }
    walk(condition)
    return out
}
