package com.breadmoirai.garnet.ui.dock

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import org.jetbrains.jewel.intui.standalone.theme.IntUiTheme
import org.jetbrains.jewel.ui.component.Icon

/** Reserved width of the tool-window stripe, in real framebuffer px (the scene runs at Density(1f)). */
const val STRIPE_WIDTH = 32

private val STRIPE_BG = Color(0xFF1E1F22)
private val ICON_SELECTED_BG = Color(0xFF2B2D30)

/**
 * The JetBrains-style tool-window stripe: one icon per panel registered to [region], top-aligned,
 * with the open one highlighted. Clicking an icon shows that panel; clicking the lit icon closes the
 * region. That close-on-relick is what makes this a stripe rather than a row of radio buttons.
 *
 * Rendered by [GarnetDock] only while [DockState.anyActive], so a closed dock costs the world zero
 * pixels. The consequence — closing the last panel makes the stripe vanish, leaving Shift+1 as the
 * only way back — is accepted: `applyDockAutoOpen` opens a panel on every Garnet world join, so a
 * fully closed dock is a deliberate act, and the keybind that closed it also reopens it.
 *
 * Hand-rolled over Box/pointerInput rather than built from Jewel's IconButton, for the same reason
 * the retired DockTabStrip was: this sits underneath the scene's layer routing, which is the subtlest
 * thing in this package, and a focusable Jewel component here would pull focus-and-popup behaviour
 * into it.
 */
@Composable
fun DockStripe(region: DockRegion, modifier: Modifier) {
    val panels = DockState.panelsFor(region)
    if (panels.isEmpty()) return
    val open = DockState.openPanelId(region)
    IntUiTheme(isDark = true) {
        Column(modifier.background(STRIPE_BG), horizontalAlignment = Alignment.CenterHorizontally) {
            panels.forEach { panel ->
                Box(
                    Modifier
                        .padding(top = 4.dp)
                        .size(28.dp)
                        .background(if (panel.id == open) ICON_SELECTED_BG else Color.Transparent)
                        .pointerInput(panel.id) {
                            detectTapGestures { DockState.togglePanel(panel.id) }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(panel.icon, contentDescription = panel.title)
                }
            }
        }
    }
}
