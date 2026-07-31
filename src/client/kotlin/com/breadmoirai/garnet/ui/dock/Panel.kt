package com.breadmoirai.garnet.ui.dock

import androidx.compose.runtime.Composable

/**
 * One titled tab in a [DockRegion]. Retained across frames; its [content] pulls live state each
 * recomposition. Named `Panel` (never `Component`) to avoid colliding with MC's text Component.
 */
class Panel(
    val id: String,
    val title: String,
    val content: @Composable (Panel) -> Unit,
)
