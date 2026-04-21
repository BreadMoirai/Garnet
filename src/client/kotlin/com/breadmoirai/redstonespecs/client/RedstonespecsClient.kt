package com.breadmoirai.redstonespecs.client

import com.breadmoirai.redstonespecs.client.network.registerClientNetworking
import com.breadmoirai.redstonespecs.client.render.registerBoundsRenderer
import net.fabricmc.api.ClientModInitializer

class RedstonespecsClient : ClientModInitializer {

    override fun onInitializeClient() {
        registerBoundsRenderer()
        registerClientNetworking()
    }
}
