package com.breadmoirai.garnet.client

import com.breadmoirai.garnet.client.config.ModConfig
import com.breadmoirai.garnet.client.project.ProjectClientNetworking
import com.breadmoirai.garnet.client.network.registerClientNetworking
import com.breadmoirai.garnet.client.render.registerBoundsRenderer
import com.breadmoirai.garnet.client.render.registerHudOverlay
import com.breadmoirai.garnet.client.viewport.registerCursorFocusToggle
import com.breadmoirai.garnet.client.viewport.registerDockKeybinds
import com.breadmoirai.garnet.client.viewport.registerViewportToggle
import net.fabricmc.api.ClientModInitializer
import org.slf4j.LoggerFactory

private val LOGGER = LoggerFactory.getLogger("Garnet")

class GarnetClient : ClientModInitializer {

    override fun onInitializeClient() {
        LOGGER.debug("[GarnetClient#onInitializeClient] initializing client")
        ModConfig.load()
        registerBoundsRenderer()
        registerClientNetworking()
        ProjectClientNetworking.register()
        registerHudOverlay()
        registerViewportToggle()
        registerCursorFocusToggle()
        registerDockKeybinds()
        // Seed the Project Explorer into the LEFT dock (region stays hidden until Shift+1 reveals it).
        com.breadmoirai.garnet.client.ui.compose.dock.DockState.leftPanels
            .add(com.breadmoirai.garnet.client.ide.explorerPanel())
        LOGGER.debug("[GarnetClient#onInitializeClient] client initialization complete")
    }
}
