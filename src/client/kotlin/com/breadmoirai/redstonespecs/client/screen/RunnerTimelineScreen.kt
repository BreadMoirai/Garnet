package com.breadmoirai.redstonespecs.client.screen

import com.breadmoirai.redstonespecs.client.state.ClientRunnerState
import com.breadmoirai.redstonespecs.client.widget.TimelineSliderWidget
import com.breadmoirai.redstonespecs.dsl.Phase
import com.breadmoirai.redstonespecs.dsl.SimTime
import com.breadmoirai.redstonespecs.runner.StateRecording
import com.breadmoirai.redstonespecs.runner.StateRecordingView
import net.minecraft.client.gui.components.ScrollableLayout
import net.minecraft.client.gui.components.StringWidget
import net.minecraft.client.gui.layouts.FrameLayout
import net.minecraft.client.gui.layouts.LinearLayout
import net.minecraft.client.gui.layouts.SpacerElement
import net.minecraft.client.gui.screens.Screen
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component

/**
 * Reads the cached [com.breadmoirai.redstonespecs.data.TestResult] for [runnerPos] and
 * renders a tick-by-tick timeline scrubber over the attached [StateRecording]. If no
 * recording is available, displays a hint and nothing else.
 */
class RunnerTimelineScreen(
    private val runnerPos: BlockPos,
) : Screen(Component.literal("Spec Timeline")) {

    private var recording: StateRecording? = null
    private var view: StateRecordingView? = null
    private var lifespan: Int = 0
    private var displayedTick: Int = 0

    // Holds StringWidget references so we can update them when the tick changes.
    private val snapshotWidgets: MutableList<StringWidget> = mutableListOf()
    private val snapshotPositions: List<BlockPos> by lazy {
        recording?.initialSnapshot?.keys?.take(20)?.toList() ?: emptyList()
    }

    override fun init() {
        super.init()
        recording = ClientRunnerState.get(runnerPos)?.recording
        val rec = recording
        if (rec != null) {
            view = StateRecordingView.of(rec)
            lifespan = ((rec.changes.maxOfOrNull { it.simTime.tick } ?: 0) + 1).coerceAtLeast(1)
        }
        displayedTick = 0
        snapshotWidgets.clear()

        val content = LinearLayout.vertical().spacing(4)
        content.addChild(StringWidget(Component.literal("Spec Timeline"), font))
        content.addChild(SpacerElement(0, 4))

        if (rec == null || view == null) {
            content.addChild(StringWidget(
                Component.literal("(no recording available)"), font
            ))
        } else {
            val lineContent = LinearLayout.vertical().spacing(2)
            for (pos in snapshotPositions) {
                val anchor = SimTime(displayedTick, Phase.END_OF_TICK, Int.MAX_VALUE)
                val s = view!!.stateAt(pos, anchor)
                val widget = StringWidget(300, font.lineHeight + 2, Component.literal("$pos -> $s"), font)
                snapshotWidgets.add(widget)
                lineContent.addChild(widget)
            }
            val scrollHeight = (height - 100).coerceAtLeast(60)
            content.addChild(ScrollableLayout(minecraft, lineContent, scrollHeight))
        }

        content.arrangeElements()
        FrameLayout.centerInRectangle(content, 10, 10, width - 10, height - 70)
        content.visitWidgets { addRenderableWidget(it) }

        // Slider sits at the bottom, outside the scrollable layout
        addRenderableWidget(TimelineSliderWidget(
            x = width / 2 - 100, y = height - 40, w = 200, h = 20,
            lifespan = lifespan,
        ) { tick ->
            displayedTick = tick
            recomputeSnapshotWidgets()
        })
    }

    private fun recomputeSnapshotWidgets() {
        val v = view ?: return
        val anchor = SimTime(displayedTick, Phase.END_OF_TICK, Int.MAX_VALUE)
        snapshotWidgets.forEachIndexed { index, widget ->
            val pos = snapshotPositions[index]
            val s = v.stateAt(pos, anchor)
            widget.message = Component.literal("$pos -> $s")
        }
    }

    override fun isPauseScreen(): Boolean = false
}
