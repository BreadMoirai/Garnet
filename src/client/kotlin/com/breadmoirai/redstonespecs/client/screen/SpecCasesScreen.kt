package com.breadmoirai.redstonespecs.client.screen

import net.minecraft.client.gui.screens.Screen
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component

// TODO Task 3/5: SpecCasesScreen is no longer relevant after SpecCase removal.
// Stubbed to allow compilation. Will be replaced or removed in a future task.
class SpecCasesScreen(private val originPos: BlockPos) :
    Screen(Component.translatable("screen.redstonespecs.spec_cases")) {

    override fun isInGameUi() = true
    override fun isPauseScreen() = false
}
