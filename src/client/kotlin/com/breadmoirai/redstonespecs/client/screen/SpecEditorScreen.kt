package com.breadmoirai.redstonespecs.client.screen

import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component

/** Placeholder — full editor implemented in Phase 5. */
class SpecEditorScreen(
    private val originPos: BlockPos,
    private val entryRelPos: BlockPos,
) : Screen(Component.translatable("screen.redstonespecs.spec_editor")) {

    override fun extractRenderState(extractor: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        extractBackground(extractor, mouseX, mouseY, partialTick)
        super.extractRenderState(extractor, mouseX, mouseY, partialTick)
        extractor.centeredText(font, title, width / 2, height / 2 - 12, 0xFFFFFF)
        extractor.centeredText(
            font,
            Component.literal("Origin: $originPos  Entry: $entryRelPos"),
            width / 2, height / 2 + 4, 0xAAAAAA,
        )
        extractor.centeredText(
            font,
            Component.literal("[Esc] to close  —  full editor coming in Phase 5"),
            width / 2, height / 2 + 18, 0x888888,
        )
    }

    override fun isPauseScreen(): Boolean = false
}
