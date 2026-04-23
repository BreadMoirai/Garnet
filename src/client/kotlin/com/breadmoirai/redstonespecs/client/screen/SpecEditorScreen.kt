package com.breadmoirai.redstonespecs.client.screen

import com.breadmoirai.redstonespecs.block.RedstoneSpecBlockEntity
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
import com.breadmoirai.redstonespecs.runner.propsToCondition
import dev.isxander.yacl3.api.ButtonOption
import dev.isxander.yacl3.api.ConfigCategory
import dev.isxander.yacl3.api.Option
import dev.isxander.yacl3.api.OptionGroup
import dev.isxander.yacl3.api.YetAnotherConfigLib
import dev.isxander.yacl3.api.controller.ColorControllerBuilder
import dev.isxander.yacl3.api.controller.StringControllerBuilder
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.Screen
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import java.awt.Color

// ── Mutable state bridge — persists across YACL screen rebuilds ───────────

class SpecEditorState(
    val originPos: BlockPos,
    val entryRelPos: BlockPos,
    val originalEntry: SpecEntry,
) {
    var workingLabel: String = originalEntry.label
    var workingColor: Int = originalEntry.color
    val workingEntries: MutableList<Pair<SimTime, StateCondition>>? = when (originalEntry) {
        is InputSpec -> originalEntry.entries.toMutableList()
        is OutputSpec -> originalEntry.entries.toMutableList()
        else -> null
    }
}

// ── Public loading screen — opened by ClientNetworkHandler ────────────────

class SpecEditorScreen(
    private val originPos: BlockPos,
    private val entryRelPos: BlockPos,
) : Screen(Component.translatable("screen.redstonespecs.spec_editor")) {

    private var launched = false

    override fun init() {
        super.init()
        tryLaunch()
    }

    override fun tick() {
        super.tick()
        tryLaunch()
    }

    private fun tryLaunch() {
        if (launched) return
        val be = minecraft?.level?.getBlockEntity(originPos) as? RedstoneSpecBlockEntity ?: return
        val entry = be.spec?.entryAt(entryRelPos) ?: return
        launched = true
        val state = SpecEditorState(originPos, entryRelPos, entry)
        minecraft?.setScreen(buildSpecEditorYacl(state))
    }

    override fun isPauseScreen() = false
    override fun isInGameUi() = true
}

// ── Lazy loader — rebuilds YACL spec editor from updated state ───────────

internal class SpecEditorLazy(val state: SpecEditorState) : Screen(Component.empty()) {
    override fun init() {
        super.init()
        minecraft?.setScreen(buildSpecEditorYacl(state))
    }
    override fun isPauseScreen() = false
    override fun isInGameUi() = true
}

// ── YACL screen builder ───────────────────────────────────────────────────

fun buildSpecEditorYacl(state: SpecEditorState): Screen {
    val entry = state.originalEntry
    val typeLabel = when (entry) {
        is InputSpec -> "Input"
        is OutputSpec -> "Output"
        is BreakpointSpec -> "Breakpoint"
        is AutoSpec -> "AutoSpec"
    }
    val mc = Minecraft.getInstance()

    return YetAnotherConfigLib.createBuilder()
        .title(Component.literal("$typeLabel @ ${state.entryRelPos}"))
        .category(
            ConfigCategory.createBuilder()
                .name(Component.literal("Settings"))
                .option(
                    Option.createBuilder<String>()
                        .name(Component.literal("Label"))
                        .binding("", { state.workingLabel }, { state.workingLabel = it })
                        .controller(StringControllerBuilder::create)
                        .build()
                )
                .option(
                    Option.createBuilder<Color>()
                        .name(Component.literal("Color"))
                        .binding(Color(entry.color), { Color(state.workingColor) }, { state.workingColor = it.rgb and 0xFFFFFF })
                        .controller(ColorControllerBuilder::create)
                        .build()
                )
                .option(
                    ButtonOption.createBuilder()
                        .name(Component.literal("Remove Spec"))
                        .action { _, _ ->
                            ClientPlayNetworking.send(
                                RemoveSpecEntryC2SPayload(state.originPos, state.entryRelPos)
                            )
                            mc.setScreen(null)
                        }
                        .build()
                )
                .build()
        )
        .apply {
            val entries = state.workingEntries
            if (entries != null) {
                category(
                    ConfigCategory.createBuilder()
                        .name(Component.literal("Entries"))
                        .option(
                            ButtonOption.createBuilder()
                                .name(Component.literal("+ Add Entry"))
                                .action { _, _ ->
                                    mc.setScreen(
                                        buildEntryEditorYacl(
                                            originPos = state.originPos,
                                            entryRelPos = state.entryRelPos,
                                            initial = null,
                                            onConfirm = { simTime, condition -> entries.add(simTime to condition) },
                                            parent = SpecEditorLazy(state),
                                        )
                                    )
                                }
                                .build()
                        )
                        .option(buildCaptureStateButton(state, entries, mc))
                        .apply {
                            entries.forEachIndexed { i, (simTime, condition) ->
                                group(
                                    OptionGroup.createBuilder()
                                        .name(Component.literal(previewEntry(simTime, condition)))
                                        .option(
                                            ButtonOption.createBuilder()
                                                .name(Component.literal("✎ Edit"))
                                                .action { _, _ ->
                                                    mc.setScreen(
                                                        buildEntryEditorYacl(
                                                            originPos = state.originPos,
                                                            entryRelPos = state.entryRelPos,
                                                            initial = simTime to condition,
                                                            onConfirm = { st, cond -> entries[i] = st to cond },
                                                            parent = SpecEditorLazy(state),
                                                        )
                                                    )
                                                }
                                                .build()
                                        )
                                        .option(
                                            ButtonOption.createBuilder()
                                                .name(Component.literal("✕ Remove"))
                                                .action { _, _ ->
                                                    entries.removeAt(i)
                                                    mc.setScreen(buildSpecEditorYacl(state))
                                                }
                                                .build()
                                        )
                                        .build()
                                )
                            }
                        }
                        .build()
                )
            }
        }
        .save {
            val updated = buildUpdatedEntry(state)
            ClientPlayNetworking.send(SaveSpecEntryC2SPayload(state.originPos, updated))
        }
        .build()
        .generateScreen(null)
}

private fun buildCaptureStateButton(
    state: SpecEditorState,
    entries: MutableList<Pair<SimTime, StateCondition>>,
    mc: Minecraft,
): ButtonOption = ButtonOption.createBuilder()
    .name(Component.literal("Capture State"))
    .action { _, _ ->
        val level = mc.level ?: return@action
        val worldPos = state.originPos.offset(state.entryRelPos)
        val blockState = level.getBlockState(worldPos)
        val currentProps = captureBlockStateProps(blockState)

        if (entries.isEmpty()) {
            entries.add(0, SimTime.INIT to propsToCondition(currentProps, blockState))
            mc.setScreen(buildSpecEditorYacl(state))
            return@action
        }

        val lastEntry = entries.maxByOrNull { it.first } ?: return@action
        val lastKnown = flattenConditionToMap(lastEntry.second)
        val diff = currentProps.filter { (k, v) -> lastKnown[k] != v }
        if (diff.isEmpty()) return@action

        val newTick = if (lastEntry.first == SimTime.INIT) 0 else lastEntry.first.tick + 1
        entries.add(SimTime(newTick, Phase.END_OF_TICK) to propsToCondition(diff, blockState))
        mc.setScreen(buildSpecEditorYacl(state))
    }
    .build()

private fun buildUpdatedEntry(state: SpecEditorState): SpecEntry {
    val label = state.workingLabel
    val color = state.workingColor
    val entries = state.workingEntries?.toList()
    return when (val e = state.originalEntry) {
        is InputSpec -> e.copy(label = label, color = color, entries = entries ?: e.entries)
        is OutputSpec -> e.copy(label = label, color = color, entries = entries ?: e.entries)
        is BreakpointSpec -> e.copy(label = label, color = color)
        is AutoSpec -> e.copy(label = label, color = color)
    }
}

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
