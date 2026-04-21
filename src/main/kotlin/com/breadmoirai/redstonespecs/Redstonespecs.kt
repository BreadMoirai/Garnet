package com.breadmoirai.redstonespecs

import net.fabricmc.api.ModInitializer

class Redstonespecs : ModInitializer {

    override fun onInitialize() {
        ModRegistries.register()
    }
}
