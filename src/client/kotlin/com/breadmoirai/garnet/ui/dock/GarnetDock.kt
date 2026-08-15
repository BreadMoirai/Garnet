package com.breadmoirai.garnet.ui.dock

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp

private const val SPLITTER = 4
private val PANEL_BG = Color(0xF01B2433)   // ~94% opaque slate; center stays transparent
private val SPLITTER_COLOR = Color(0xFF10161F)

/**
 * Full-window dock. Draws the visible LEFT/RIGHT/BOTTOM regions (with draggable splitters) and
 * any CENTER panel; everything else is transparent so the composited world shows through. Sizes
 * come from [DockState] in real pixels (the scene runs at Density(1f)).
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
        if (DockState.isVisible(DockRegion.CENTER)) {
            RegionColumn(DockRegion.CENTER, Modifier.offset(left.dp, 0.dp).width((realW - left - right).dp).height((realH - bottom).dp))
        }
    }
}

/** A region = the open panel's body, filling the region. */
@Composable
private fun RegionColumn(region: DockRegion, modifier: Modifier) {
    val panel = DockState.openPanelOf(region) ?: return
    Column(modifier.background(PANEL_BG)) {
        // key(): a panel body must not be able to outlive its mount. Panel content is invoked at a
        // fixed slot, and a re-mounted panel from the same factory has the same composable source
        // key, so without this Compose reuses the group and every `remember` inside survives — most
        // visibly a Jewel Dropdown's open menu and its Popup layer, which then paints over the next
        // mount. See DockState.mountEpoch for the full mechanism. Panel id is in the key too so
        // swapping which panel occupies a region is likewise a fresh mount.
        Box(Modifier.fillMaxSize()) {
            key(DockState.mountEpoch(region), panel.id) { panel.content(panel) }
        }
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

