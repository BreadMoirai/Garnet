package com.breadmoirai.garnet.ui.dock

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val TAB_ACTIVE_BG = Color(0xFF2B2D30)
private val TAB_ACTIVE_FG = Color(0xFFDFE1E5)
private val TAB_IDLE_FG = Color(0xFF8B8F96)

/**
 * The region's tab row. Rendered only when a region holds more than one panel — a single-panel
 * region shows its body full-bleed, as it did before this strip existed.
 *
 * Deliberately hand-rolled rather than a Jewel tab component: this is the same layer that had a
 * hand-rolled strip before, and a Jewel tab row would pull focus-and-popup behaviour into a scene
 * whose layer routing is already the subtlest thing in this package.
 */
@Composable
fun DockTabStrip(
    region: DockRegion,
    panels: List<Panel>,
    active: Int,
    onSelect: (Int) -> Unit,
) {
    if (panels.size < 2) return
    Row(Modifier.padding(horizontal = 2.dp, vertical = 2.dp)) {
        panels.forEachIndexed { index, panel ->
            val isActive = index == active
            BasicText(
                text = panel.title,
                style = TextStyle(
                    color = if (isActive) TAB_ACTIVE_FG else TAB_IDLE_FG,
                    fontSize = 11.sp,
                ),
                modifier = Modifier
                    .background(if (isActive) TAB_ACTIVE_BG else Color.Transparent)
                    .padding(horizontal = 6.dp, vertical = 3.dp)
                    .pointerInput(index) { detectTapGestures { onSelect(index) } },
            )
        }
    }
}
