package com.breadmoirai.garnet.client.ui.compose.dock

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp

private const val SPLITTER = 4
private const val TAB_H = 18
private val PANEL_BG = Color(0xF01B2433)   // ~94% opaque slate; center stays transparent
private val TAB_BG = Color(0xFF2D6DA3)
private val TAB_BG_INACTIVE = Color(0xFF243044)
private val TEXT = Color(0xFFFFFFFF)
private val SPLITTER_COLOR = Color(0xFF10161F)

/**
 * Full-window dock. Draws the visible LEFT/RIGHT/BOTTOM regions (with tab strips and draggable
 * splitters) and any CENTER panel; everything else is transparent so the composited world shows
 * through. Sizes come from [DockState] in real pixels (the scene runs at Density(1f)).
 */
@Composable
fun GarnetDock(realW: Int, realH: Int) {
    Box(Modifier.fillMaxSize()) {
        val left = if (DockState.isVisible(DockRegion.LEFT)) DockState.leftWidth else 0
        val right = if (DockState.isVisible(DockRegion.RIGHT)) DockState.rightWidth else 0
        val bottom = if (DockState.isVisible(DockRegion.BOTTOM)) DockState.bottomHeight else 0

        if (DockState.isVisible(DockRegion.LEFT)) {
            RegionColumn(DockRegion.LEFT, Modifier.offset(0.dp, 0.dp).width(left.dp).height((realH - bottom).dp))
            SplitterX(Modifier.offset((left - SPLITTER).dp, 0.dp).width(SPLITTER.dp).height((realH - bottom).dp)) { dx ->
                DockState.setSize(DockRegion.LEFT, DockState.leftWidth + dx)
            }
        }
        if (DockState.isVisible(DockRegion.RIGHT)) {
            RegionColumn(DockRegion.RIGHT, Modifier.offset((realW - right).dp, 0.dp).width(right.dp).height((realH - bottom).dp))
            SplitterX(Modifier.offset((realW - right).dp, 0.dp).width(SPLITTER.dp).height((realH - bottom).dp)) { dx ->
                DockState.setSize(DockRegion.RIGHT, DockState.rightWidth - dx)
            }
        }
        if (DockState.isVisible(DockRegion.BOTTOM)) {
            RegionColumn(DockRegion.BOTTOM, Modifier.offset(0.dp, (realH - bottom).dp).width(realW.dp).height(bottom.dp))
            Splitter(Modifier.offset(0.dp, (realH - bottom).dp).width(realW.dp).height(SPLITTER.dp)) { _, dy ->
                DockState.setSize(DockRegion.BOTTOM, DockState.bottomHeight - dy)
            }
        }
        // CENTER: only render a panel if one exists (else transparent → world shows).
        if (DockState.centerPanels.isNotEmpty()) {
            RegionColumn(DockRegion.CENTER, Modifier.offset(left.dp, 0.dp).width((realW - left - right).dp).height((realH - bottom).dp))
        }
    }
}

/** A region = a tab strip over its panels + the active panel's body. */
@Composable
private fun RegionColumn(region: DockRegion, modifier: Modifier) {
    val panels = DockState.panelsFor(region)
    if (panels.isEmpty()) return
    val active = activeTabFor(region).coerceIn(0, panels.lastIndex)
    Column(modifier.background(PANEL_BG)) {
        Row(Modifier.fillMaxWidth().height(TAB_H.dp)) {
            panels.forEachIndexed { i, p ->
                Box(
                    Modifier
                        .height(TAB_H.dp)
                        .background(if (i == active) TAB_BG else TAB_BG_INACTIVE)
                        .pointerInput(region, i) {
                            detectTapOrDown { setActiveTab(region, i) }
                        }
                        .padding(horizontal = 6.dp),
                ) {
                    BasicText(p.title, style = TextStyle(color = TEXT, fontSize = TextUnit.Unspecified))
                }
            }
        }
        Box(Modifier.fillMaxSize()) { panels[active].content(panels[active]) }
    }
}

@Composable
private fun Splitter(modifier: Modifier, onDrag: (dx: Int, dy: Int) -> Unit) =
    Box(modifier.background(SPLITTER_COLOR).pointerInput(Unit) {
        detectDragGestures { change, drag ->
            change.consume()
            onDrag(drag.x.toInt(), drag.y.toInt())
        }
    })

// Horizontal-only splitter convenience.
@Composable
private fun SplitterX(modifier: Modifier, onDragX: (dx: Int) -> Unit) =
    Splitter(modifier) { dx, _ -> onDragX(dx) }

private fun activeTabFor(region: DockRegion) = when (region) {
    DockRegion.LEFT -> DockState.leftActiveTab
    DockRegion.RIGHT -> DockState.rightActiveTab
    DockRegion.BOTTOM -> DockState.bottomActiveTab
    DockRegion.CENTER -> DockState.centerActiveTab
}

private fun setActiveTab(region: DockRegion, i: Int) {
    when (region) {
        DockRegion.LEFT -> DockState.leftActiveTab = i
        DockRegion.RIGHT -> DockState.rightActiveTab = i
        DockRegion.BOTTOM -> DockState.bottomActiveTab = i
        DockRegion.CENTER -> DockState.centerActiveTab = i
    }
}

// Minimal tap detector (foundation `clickable` also works; kept explicit for Density(1f) hit-testing).
private suspend fun androidx.compose.ui.input.pointer.PointerInputScope.detectTapOrDown(onTap: () -> Unit) {
    detectTapGestures(onTap = { onTap() })
}
