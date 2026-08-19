package com.breadmoirai.garnet

import com.breadmoirai.garnet.core.config.ModConfig
import com.breadmoirai.garnet.editor.explorer.ui.explorerPanel
import com.breadmoirai.garnet.editor.explorer.ui.registerExplorerLifecycle
import com.breadmoirai.garnet.editor.network.EditorClientNetworking
import com.breadmoirai.garnet.editor.history.ui.localHistoryPanel
import com.breadmoirai.garnet.editor.structure.ui.structureInfoPanel
import com.breadmoirai.garnet.dock.input.registerDockFocusKeybind
import com.breadmoirai.garnet.dock.shell.DockState
import com.breadmoirai.garnet.dock.viewport.registerDockKeybinds
import com.breadmoirai.garnet.dock.viewport.registerDockWorldLifecycle
import com.breadmoirai.garnet.dock.viewport.registerViewportToggle
import net.fabricmc.api.ClientModInitializer
import org.slf4j.LoggerFactory

private val LOGGER = LoggerFactory.getLogger("Garnet")

class GarnetClient : ClientModInitializer {

    override fun onInitializeClient() {
        LOGGER.debug("[GarnetClient#onInitializeClient] initializing client")
        ModConfig.load()
        EditorClientNetworking.register()
        registerViewportToggle()
        registerDockFocusKeybind()
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
