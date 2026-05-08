package com.breadmoirai.redstonespecs.client.widget

import net.minecraft.client.gui.components.AbstractSliderButton
import net.minecraft.network.chat.Component

/**
 * Horizontal slider widget for scrubbing through ticks 0..[lifespan-1].
 * Calls [onTickChanged] with the current tick whenever the slider moves.
 */
class TimelineSliderWidget(
    x: Int, y: Int, w: Int, h: Int,
    private val lifespan: Int,
    private val onTickChanged: (Int) -> Unit,
) : AbstractSliderButton(x, y, w, h, Component.literal("Tick 0"), 0.0) {
    var tick: Int = 0
        private set

    init { updateMessage() }

    override fun updateMessage() {
        message = Component.literal("Tick $tick / ${(lifespan - 1).coerceAtLeast(0)}")
    }

    override fun applyValue() {
        if (lifespan <= 1) { tick = 0; onTickChanged(tick); return }
        tick = (value * (lifespan - 1)).toInt().coerceIn(0, lifespan - 1)
        onTickChanged(tick)
    }
}
