package com.breadmoirai.redstonespecs.client

import com.breadmoirai.redstonespecs.client.network.registerClientNetworking
import net.fabricmc.api.ClientModInitializer

class RedstonespecsClient : ClientModInitializer {

    override fun onInitializeClient() {
        registerClientNetworking()
    }
}
