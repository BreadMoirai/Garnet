package com.breadmoirai.redstonespecs.client.screen

import com.breadmoirai.redstonespecs.network.RunnerCmd
import com.breadmoirai.redstonespecs.network.RunnerCommandC2S
import com.breadmoirai.redstonespecs.network.RunnerMetaSnapshot
import com.breadmoirai.redstonespecs.network.RunnerState
import com.breadmoirai.redstonespecs.network.SetRunnerConfigC2S
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.CycleButton
import net.minecraft.client.gui.components.StringWidget
import net.minecraft.client.gui.layouts.FrameLayout
import net.minecraft.client.gui.layouts.LinearLayout
import net.minecraft.client.gui.layouts.SpacerElement
import net.minecraft.client.gui.screens.Screen
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component

class RunnerScreen(
    val originPos: BlockPos,
    private val initialSpecPath: String,
    private val specList: List<String>,
    initialMeta: RunnerMetaSnapshot?,
) : Screen(Component.literal("Runner: $originPos")) {

    // Mutable state updated by incoming packets
    var meta: RunnerMetaSnapshot? = initialMeta
        private set
    var statusText: String = ""
        private set
    var statusState: RunnerState = RunnerState.IDLE
        private set

    // The currently selected spec path (may differ from initialSpecPath after cycling).
    private var currentSpecPath: String = initialSpecPath

    override fun isPauseScreen() = false
    override fun isInGameUi() = true

    override fun init() {
        super.init()

        val content = LinearLayout.vertical().spacing(6)

        // Title
        content.addChild(StringWidget(Component.literal("Runner: $originPos"), font))
        content.addChild(SpacerElement(0, 4))

        // Spec picker row
        val specRow = LinearLayout.horizontal().spacing(4)
        specRow.addChild(StringWidget(40, 20, Component.literal("Spec:"), font))

        val displayList: List<String> = if (specList.isEmpty()) listOf("(no specs)") else specList
        val defaultPath = if (specList.contains(currentSpecPath)) currentSpecPath else displayList[0]

        val picker = CycleButton.builder<String>(
            { path: String -> Component.literal(path) },
            { defaultPath }
        )
            .withValues(displayList)
            .create(0, 0, 200, 20, Component.literal("Spec")) { _, value: String ->
                if (specList.isNotEmpty() && value != currentSpecPath) {
                    currentSpecPath = value
                    ClientPlayNetworking.send(SetRunnerConfigC2S(originPos, value))
                }
            }
        picker.active = specList.isNotEmpty()
        specRow.addChild(picker)
        content.addChild(specRow)

        content.addChild(SpacerElement(0, 4))

        // Meta panel — rendered as StringWidgets that rebuild on init()
        val m = meta
        if (m == null) {
            content.addChild(StringWidget(Component.literal("(no spec loaded)"), font))
            content.addChild(SpacerElement(0, 48))
        } else {
            content.addChild(StringWidget(Component.literal("id:        ${m.id}"), font))
            content.addChild(StringWidget(Component.literal("bounds:    ${m.boundsX}×${m.boundsY}×${m.boundsZ}"), font))
            content.addChild(StringWidget(Component.literal("lifespan:  ${m.lifespan}"), font))
            content.addChild(StringWidget(Component.literal("structure: ${m.structure ?: "(none)"}"), font))
            content.addChild(SpacerElement(0, 4))
        }

        content.addChild(SpacerElement(0, 4))

        // Action buttons
        val btnRow = LinearLayout.horizontal().spacing(6)
        btnRow.addChild(
            Button.builder(Component.literal("Place structure")) {
                ClientPlayNetworking.send(RunnerCommandC2S(originPos, RunnerCmd.PLACE_STRUCTURE))
            }.bounds(0, 0, 100, 20).build()
        )
        btnRow.addChild(
            Button.builder(Component.literal("Run")) {
                ClientPlayNetworking.send(RunnerCommandC2S(originPos, RunnerCmd.RUN))
            }.bounds(0, 0, 60, 20).build()
        )
        btnRow.addChild(
            Button.builder(Component.literal("Restore snapshot")) {
                ClientPlayNetworking.send(RunnerCommandC2S(originPos, RunnerCmd.RESTORE))
            }.bounds(0, 0, 110, 20).build()
        )
        content.addChild(btnRow)

        content.addChild(SpacerElement(0, 6))

        // Status line
        val statusLabel = if (statusText.isBlank()) "(idle)" else "Status: $statusText"
        content.addChild(StringWidget(Component.literal(statusLabel), font))

        content.arrangeElements()
        FrameLayout.centerInRectangle(content, 10, 10, width - 10, height - 10)
        content.visitWidgets { addRenderableWidget(it) }
    }

    /** Called by the client network handler when a RunnerStatusS2C arrives for this pos. */
    fun pushStatus(state: RunnerState, summary: String) {
        statusState = state
        statusText = summary
        // Rebuild the screen so the status label updates.
        rebuildWidgets()
    }

    /** Called by the client network handler when a new OpenRunnerScreenS2C arrives (after SetRunnerConfig). */
    fun updateMeta(newMeta: RunnerMetaSnapshot?) {
        meta = newMeta
        rebuildWidgets()
    }

    companion object {
        /** Singleton reference so the packet handler can push updates without reflection. */
        var active: RunnerScreen? = null
    }

    override fun onClose() {
        if (active === this) active = null
        super.onClose()
    }
}
