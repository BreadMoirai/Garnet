package com.breadmoirai.garnet

import com.breadmoirai.garnet.core.config.ModConfig
import com.breadmoirai.garnet.editor.network.EditorClientNetworking
import com.breadmoirai.garnet.editor.ui.explorerPanel
import com.breadmoirai.garnet.editor.ui.localHistoryPanel
import com.breadmoirai.garnet.editor.ui.registerExplorerLifecycle
import com.breadmoirai.garnet.editor.ui.structureInfoPanel
import com.breadmoirai.garnet.ui.dock.DockState
import com.breadmoirai.garnet.ui.viewport.registerCursorFocusToggle
import com.breadmoirai.garnet.ui.viewport.registerDockKeybinds
import com.breadmoirai.garnet.ui.viewport.registerDockWorldLifecycle
import com.breadmoirai.garnet.ui.viewport.registerViewportToggle
import net.fabricmc.api.ClientModInitializer
import org.slf4j.LoggerFactory

private val LOGGER = LoggerFactory.getLogger("Garnet")

class GarnetClient : ClientModInitializer {

    override fun onInitializeClient() {
        LOGGER.debug("[GarnetClient#onInitializeClient] initializing client")
        ModConfig.load()
        EditorClientNetworking.register()
        registerViewportToggle()
        registerCursorFocusToggle()
        registerDockKeybinds()
        registerDockWorldLifecycle()
        registerExplorerLifecycle()
        // Seed the LEFT dock panels. The region starts closed; joining a Garnet world opens the
        // remembered one (see applyDockAutoOpen), the stripe icons switch between them, and Shift+1
        // toggles the Explorer by hand.
        DockState.panels += explorerPanel()
        DockState.panels += localHistoryPanel()
        DockState.panels += structureInfoPanel()
        LOGGER.debug("[GarnetClient#onInitializeClient] client initialization complete")
    }
}
