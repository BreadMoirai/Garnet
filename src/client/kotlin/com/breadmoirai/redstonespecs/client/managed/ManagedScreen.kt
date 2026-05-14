package com.breadmoirai.redstonespecs.client.managed

import com.breadmoirai.redstonespecs.network.managed.LoadManagedFolderC2S
import com.breadmoirai.redstonespecs.network.managed.ManagedErrorS2C
import com.breadmoirai.redstonespecs.network.managed.ManagedFolderLoadedS2C
import com.breadmoirai.redstonespecs.network.managed.ManagedSaveReportS2C
import com.breadmoirai.redstonespecs.network.managed.ManagedTreeSnapshotS2C
import com.breadmoirai.redstonespecs.network.managed.NewManagedSpecC2S
import com.breadmoirai.redstonespecs.network.managed.SaveNowC2S
import com.breadmoirai.redstonespecs.network.managed.UnloadManagedFolderC2S
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
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

/**
 * In-game folder browser for the managed-specs feature. Opens via /redstonespecs managed
 * (T22) or via the world-list "Managed Specs..." flow (T20). Both entry paths push a
 * `ManagedTreeSnapshotS2C` from the server before the screen is constructed, so this
 * screen does not request the tree itself; it is updated by S2C handlers in
 * `ManagedClientNetworking` (T19) which call `onTreeSnapshot`/`onSaveReport`/`onError`.
 */
class ManagedScreen(private var lastSnapshot: ManagedTreeSnapshotS2C? = null) :
    Screen(Component.literal("Managed Specs")) {

    private var newSpecName: String = ""
    private var status: String = ""

    override fun isPauseScreen() = false
    override fun isInGameUi() = true

    override fun init() {
        super.init()

        val outer = LinearLayout.vertical().spacing(4)

        outer.addChild(StringWidget(Component.literal("Managed Specs"), font))
        val current = lastSnapshot?.currentSubpath?.let { "Loaded: $it" } ?: "No folder loaded"
        outer.addChild(StringWidget(Component.literal(current), font))
        outer.addChild(SpacerElement(0, 4))

        // Folder list
        val listContent = LinearLayout.vertical().spacing(2)
        val snap = lastSnapshot
        val leaves = snap?.leaves.orEmpty()
        if (snap == null) {
            listContent.addChild(StringWidget(300, 18, Component.literal("Loading…"), font))
        } else if (leaves.isEmpty()) {
            listContent.addChild(StringWidget(300, 18, Component.literal("(no folders)"), font))
        } else {
            leaves.forEach { leaf ->
                listContent.addChild(
                    Button.builder(Component.literal("📁 ${leaf.subpath} (${leaf.specCount})")) {
                        ClientPlayNetworking.send(LoadManagedFolderC2S(leaf.subpath))
                        status = "Loading ${leaf.subpath}…"
                    }.pos(0, 0).width(300).build()
                )
            }
        }
        val listHeight = (height - 160).coerceAtLeast(60)
        outer.addChild(ScrollableLayout(minecraft, listContent, listHeight))

        outer.addChild(SpacerElement(0, 4))

        // New spec row
        val newRow = LinearLayout.horizontal().spacing(4)
        val nameBox = EditBox(font, 200, 20, Component.literal("name"))
        nameBox.setMaxLength(64)
        // Restore typed text from the prior rebuild *before* installing the responder.
        // EditBox.setValue fires the responder synchronously in MC 26.1; doing this in the
        // other order would clobber `newSpecName` with whatever the previous EditBox had.
        nameBox.value = newSpecName
        nameBox.setResponder { newSpecName = it }
        newRow.addChild(nameBox)
        newRow.addChild(
            Button.builder(Component.literal("New Spec")) {
                if (newSpecName.isNotBlank()) {
                    ClientPlayNetworking.send(NewManagedSpecC2S(newSpecName))
                    status = "Creating ${newSpecName}…"
                }
            }.pos(0, 0).width(96).build()
        )
        outer.addChild(newRow)

        // Save / Unload row
        val actionRow = LinearLayout.horizontal().spacing(4)
        actionRow.addChild(
            Button.builder(Component.literal("Save Now")) {
                ClientPlayNetworking.send(SaveNowC2S())
                status = "Saving…"
            }.pos(0, 0).width(148).build()
        )
        actionRow.addChild(
            Button.builder(Component.literal("Unload")) {
                ClientPlayNetworking.send(UnloadManagedFolderC2S())
                status = "Unloading…"
            }.pos(0, 0).width(148).build()
        )
        outer.addChild(actionRow)

        // Status line
        outer.addChild(StringWidget(300, 12, Component.literal(status), font))

        outer.addChild(SpacerElement(0, 4))

        // Back
        outer.addChild(
            Button.builder(CommonComponents.GUI_BACK) { onClose() }
                .pos(0, 0).width(150).build()
        )

        outer.arrangeElements()
        FrameLayout.centerInRectangle(outer, 10, 10, width - 10, height - 10)
        outer.visitWidgets { addRenderableWidget(it) }
    }

    fun onTreeSnapshot(snapshot: ManagedTreeSnapshotS2C) {
        this.lastSnapshot = snapshot
        rebuildWidgets()
    }

    fun onFolderLoaded(loaded: ManagedFolderLoadedS2C) {
        status = "Loaded ${loaded.subpath}: ${loaded.loadedSpecIds.size} specs" +
                (if (loaded.parseErrors.isNotEmpty()) " (${loaded.parseErrors.size} parse errors)" else "") +
                (if (loaded.layoutErrors.isNotEmpty()) " (${loaded.layoutErrors.size} layout errors)" else "")
        onClose()
    }

    fun onSaveReport(report: ManagedSaveReportS2C) {
        val saved = report.perSpec.count { it.contains("saved=true") }
        status = "Saved $saved spec(s)"
        rebuildWidgets()
    }

    fun onError(err: ManagedErrorS2C) {
        status = "Error: ${err.reason}"
        rebuildWidgets()
    }
}
