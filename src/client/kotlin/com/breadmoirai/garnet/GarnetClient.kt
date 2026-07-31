package com.breadmoirai.garnet

import com.breadmoirai.garnet.config.ModConfig
import com.breadmoirai.garnet.editor.network.EditorClientNetworking
import com.breadmoirai.garnet.editor.ui.registerExplorerLifecycle
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
        // Seed the Project Explorer into the LEFT dock (region stays hidden until Shift+1 reveals it).
        com.breadmoirai.garnet.ui.dock.DockState.leftPanels
            .add(com.breadmoirai.garnet.editor.ui.explorerPanel())
        LOGGER.debug("[GarnetClient#onInitializeClient] client initialization complete")
    }
}
