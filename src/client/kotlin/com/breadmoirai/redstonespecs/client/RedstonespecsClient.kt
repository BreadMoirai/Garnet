package com.breadmoirai.redstonespecs.client

import com.breadmoirai.redstonespecs.client.network.registerClientNetworking
import com.breadmoirai.redstonespecs.client.render.registerBoundsRenderer
import com.breadmoirai.redstonespecs.client.render.registerHudOverlay
import net.fabricmc.api.ClientModInitializer
import org.slf4j.LoggerFactory

private val LOGGER = LoggerFactory.getLogger("Redstone Specs")

class RedstonespecsClient : ClientModInitializer {

    override fun onInitializeClient() {
        LOGGER.debug("[RedstonespecsClient#onInitializeClient] initializing client")
        registerBoundsRenderer()
        registerClientNetworking()
        registerHudOverlay()
        LOGGER.debug("[RedstonespecsClient#onInitializeClient] client initialization complete")
    }
}
