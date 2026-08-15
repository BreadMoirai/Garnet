package com.breadmoirai.garnet.ui.dock

import androidx.compose.runtime.Composable
import org.jetbrains.jewel.ui.icon.IconKey

/**
 * One panel in a [DockRegion]. Retained across frames; its [content] pulls live state each
 * recomposition. Named `Panel` (never `Component`) to avoid colliding with MC's text Component.
 *
 * A panel carries its own [region] and [icon] rather than being filed into a per-region list,
 * because both are properties of the panel itself: the region is where it belongs, and the icon is
 * how `DockStripe` offers it. `DockState.panelsFor` derives the per-region lists from these, so
 * there is exactly one definition of "which panels does this region have".
 *
 * [title] survives the tab strip's deletion: it is the panel's human name for accessibility and for
 * test assertions, and the stripe uses it as the icon's content description.
 */
class Panel(
    val id: String,
    val title: String,
    val region: DockRegion,
    val icon: IconKey,
    val content: @Composable (Panel) -> Unit,
)
