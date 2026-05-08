package com.breadmoirai.redstonespecs.client.managed

import com.breadmoirai.redstonespecs.managed.ManagedRootsConfig
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.components.ScrollableLayout
import net.minecraft.client.gui.components.StringWidget
import net.minecraft.client.gui.layouts.FrameLayout
import net.minecraft.client.gui.layouts.LinearLayout
import net.minecraft.client.gui.layouts.SpacerElement
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.CommonComponents
import net.minecraft.network.chat.Component
import org.slf4j.LoggerFactory
import java.nio.file.Path

/**
 * Client-side screen for managing the persistent list of managed-spec root paths. Opened
 * from the world-selection screen via `SelectWorldScreenMixin` ("Managed Specs..." button).
 *
 * Reads/writes via [ManagedRootsConfig] under the MC config dir. Picking a root will (in
 * T21) boot the integrated server pinned to that root; for now we log + close.
 */
class ManagedRootListScreen(private val parent: Screen) :
    Screen(Component.literal("Managed Spec Roots")) {

    private val configPath: Path =
        FabricLoader.getInstance().configDir.resolve("redstonespecs/managed-roots.json")
    private var roots: MutableList<String> = ManagedRootsConfig.load(configPath).toMutableList()
    private var newRootInput: String = ""

    override fun init() {
        super.init()

        val outer = LinearLayout.vertical().spacing(4)
        outer.addChild(StringWidget(Component.literal("Managed Spec Roots"), font))
        outer.addChild(SpacerElement(0, 4))

        val listContent = LinearLayout.vertical().spacing(2)
        if (roots.isEmpty()) {
            listContent.addChild(StringWidget(340, 18, Component.literal("(no roots configured)"), font))
        } else {
            roots.toList().forEachIndexed { idx, r ->
                val row = LinearLayout.horizontal().spacing(4)
                row.addChild(
                    Button.builder(Component.literal("Open: $r")) {
                        openRoot(r)
                    }.pos(0, 0).width(300).build()
                )
                row.addChild(
                    Button.builder(Component.literal("X")) {
                        roots.removeAt(idx)
                        ManagedRootsConfig.save(configPath, roots)
                        rebuildWidgets()
                    }.pos(0, 0).width(36).build()
                )
                listContent.addChild(row)
            }
        }
        val listHeight = (height - 160).coerceAtLeast(60)
        outer.addChild(ScrollableLayout(minecraft, listContent, listHeight))

        outer.addChild(SpacerElement(0, 4))

        val addRow = LinearLayout.horizontal().spacing(4)
        val pathBox = EditBox(font, 280, 20, Component.literal("path"))
        pathBox.setMaxLength(512)
        pathBox.setResponder { newRootInput = it }
        addRow.addChild(pathBox)
        addRow.addChild(
            Button.builder(Component.literal("Add")) {
                if (newRootInput.isNotBlank()) {
                    roots.add(newRootInput)
                    ManagedRootsConfig.save(configPath, roots)
                    rebuildWidgets()
                }
            }.pos(0, 0).width(56).build()
        )
        outer.addChild(addRow)

        outer.addChild(SpacerElement(0, 4))

        outer.addChild(
            Button.builder(CommonComponents.GUI_BACK) { onClose() }
                .pos(0, 0).width(150).build()
        )

        outer.arrangeElements()
        FrameLayout.centerInRectangle(outer, 10, 10, width - 10, height - 10)
        outer.visitWidgets { addRenderableWidget(it) }
    }

    override fun onClose() {
        Minecraft.getInstance().setScreen(parent)
    }

    private fun openRoot(rootPath: String) {
        // T21 will implement ManagedIntegratedBoot; for now, log + close.
        LoggerFactory.getLogger("Redstone Specs")
            .info("[ManagedRootListScreen] requested open root '{}' (T21 not yet implemented)", rootPath)
        onClose()
    }
}
