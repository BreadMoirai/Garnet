package com.breadmoirai.redstonespecs.test

import com.breadmoirai.redstonespecs.client.managed.ManagedRootListScreen
import com.breadmoirai.redstonespecs.client.managed.ManagedScreen
import com.breadmoirai.redstonespecs.client.screen.RedstoneIconButton
import com.breadmoirai.redstonespecs.managed.ManagedCommand
import com.breadmoirai.redstonespecs.managed.ManagedRoot
import com.breadmoirai.redstonespecs.managed.ManagedRootsConfig
import com.breadmoirai.redstonespecs.managed.ManagedServerContext
import com.breadmoirai.redstonespecs.managed.ManagedSession
import com.breadmoirai.redstonespecs.network.managed.ManagedLeafEntry
import com.breadmoirai.redstonespecs.network.managed.ManagedTreeSnapshotS2C
import com.breadmoirai.redstonespecs.runner.RecordingDslEmitter
import com.breadmoirai.redstonespecs.testing.ClientSpec
import com.breadmoirai.redstonespecs.testing.server.onServer
import com.mojang.brigadier.CommandDispatcher
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.gui.components.AbstractButton
import net.minecraft.client.gui.components.AbstractContainerWidget
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.components.events.ContainerEventHandler
import net.minecraft.client.gui.components.events.GuiEventListener
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.TitleScreen
import net.minecraft.client.input.MouseButtonInfo
import net.minecraft.commands.CommandSourceStack
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.writeText

/**
 * Client coverage for the managed-worlds entry points:
 *  - UC-MAN-01.a: TitleScreen mixin injects a `RedstoneIconButton`; pressing it opens
 *    `ManagedRootListScreen`.
 *  - UC-MAN-01.b: typing a path into the EditBox and clicking "Add" persists the path to
 *    `<configDir>/redstonespecs/managed-roots.json` and surfaces an "Open: <path>" row
 *    (the per-root navigation glue that UC-MAN-02.a would dispatch through).
 *  - UC-MAN-XX (command): `/redstonespecs managed` with a pinned [ManagedServerContext]
 *    sends `ManagedTreeSnapshotS2C`, and the client receiver opens [ManagedScreen].
 *
 * UC-MAN-02 (booting the managed singleplayer save) is intentionally NOT exercised here:
 * `ManagedIntegratedBoot.boot` calls `WorldOpenFlows.createFreshLevel`, which tears down
 * the integrated server the `ClientTestSentinel` is running and starts a new save. That
 * conflicts with the sentinel's world-lifetime contract. The per-root "Open: <path>"
 * widget assertion in `Add button` covers the navigation glue immediately before the boot.
 */
class ManagedEntryFlowSpec : ClientSpec({

    test("UC-MAN-01.a: TitleScreen RedstoneIconButton opens ManagedRootListScreen") {
        runOnClient { mc -> mc.setScreen(TitleScreen(false)) }
        waitForClientScreen(TitleScreen::class.java)

        val button = onClient { mc ->
            mc.screen!!.children().filterIsInstance<RedstoneIconButton>().firstOrNull()
        }
        button shouldNotBe null

        runOnClient { _ -> button!!.onPress(MouseButtonInfo(0, 0)) }
        waitForClientScreen(ManagedRootListScreen::class.java)

        // Bounce back to a clean state for subsequent tests.
        runOnClient { mc -> mc.setScreen(null) }
    }

    test("UC-MAN-01.b: Add button persists path to config and renders 'Open: <path>' row") {
        val configPath: Path = FabricLoader.getInstance().configDir.resolve("redstonespecs/managed-roots.json")
        val backup: List<String>? = if (configPath.exists()) ManagedRootsConfig.load(configPath) else null
        configPath.deleteIfExists()

        val entered = "/tmp/uc-man-01b-${System.nanoTime()}"

        try {
            runOnClient { mc -> mc.setScreen(ManagedRootListScreen(TitleScreen(false))) }
            waitForClientScreen(ManagedRootListScreen::class.java)

            // Type into the path EditBox. The screen has exactly one EditBox (the add-row input).
            runOnClient { mc ->
                val box = mc.screen!!.children().filterIsInstance<EditBox>().single()
                box.value = entered
            }

            // Press the "Add" button.
            runOnClient { mc ->
                val add = mc.screen!!.children()
                    .filterIsInstance<AbstractButton>()
                    .single { it.message.string == "Add" }
                add.onPress(MouseButtonInfo(0, 0))
            }
            waitClientTicks(2)

            // On-disk side effect: ManagedRootsConfig.save wrote the JSON file with our entry.
            configPath.exists() shouldBe true
            ManagedRootsConfig.load(configPath) shouldContain entered

            // Screen-state side effect: rebuildWidgets re-read the persisted list — the
            // path is now in the screen's `roots`, which is what the per-row "Open: <path>"
            // button reads from in init(). (We don't scrape widget messages because the
            // row lives inside a ScrollableLayout, whose internal widgets aren't surfaced
            // through `screen.children()`.)
            @Suppress("UNCHECKED_CAST")
            val rootsField = ManagedRootListScreen::class.java.getDeclaredField("roots")
                .apply { isAccessible = true }
            val rootsOnScreen = onClient { mc -> rootsField.get(mc.screen) as List<String> }
            rootsOnScreen shouldContain entered

            runOnClient { mc -> mc.setScreen(null) }
        } finally {
            // Restore: leave the test runner's config dir as we found it so subsequent
            // sentinel runs (and the next test in this run) start clean.
            configPath.deleteIfExists()
            if (backup != null) {
                configPath.parent?.createDirectories()
                ManagedRootsConfig.save(configPath, backup)
            }
        }
    }

    test("/redstonespecs managed opens ManagedScreen client-side with the tree leaves") {
        val tmp = Files.createTempDirectory("uc-man-cmd-")
        val leaf = tmp.resolve("alpha").also { it.createDirectories() }
        leaf.resolve("a.spec.kts").writeText(RecordingDslEmitter.emitStub("a"))

        try {
            onServer {
                val player = this.overworld().players().firstOrNull() ?: error("no overworld player")
                ManagedServerContext.set(this, ManagedServerContext(ManagedRoot(tmp.toAbsolutePath())))
                val dispatcher = CommandDispatcher<CommandSourceStack>()
                ManagedCommand.register(dispatcher)
                dispatcher.execute("redstonespecs managed", player.createCommandSourceStack())
            }

            waitForClientScreen(ManagedScreen::class.java)

            val leafSubpaths = onClient { mc ->
                // ManagedScreen captures the snapshot internally; read its private lastSnapshot
                // so we don't depend on the rendered widget layout. (Cross-sourceset reflection
                // because the field is `private` and we don't want a test-only accessor on the
                // production screen.)
                val f = ManagedScreen::class.java.getDeclaredField("lastSnapshot").apply { isAccessible = true }
                val snap = f.get(mc.screen) as com.breadmoirai.redstonespecs.network.managed.ManagedTreeSnapshotS2C
                snap.leaves.map { it.subpath }
            }
            leafSubpaths shouldBe listOf("alpha")

            runOnClient { mc -> mc.setScreen(null) }
        } finally {
            onServer { ManagedServerContext.clear(this) }
            tmp.toFile().deleteRecursively()
        }
    }

    test("UC-MAN-05.b: ManagedScreen shows \"Loading…\" placeholder before snapshot, clears after") {
        runOnClient { mc -> mc.setScreen(ManagedScreen()) }
        waitForClientScreen(ManagedScreen::class.java)

        // The placeholder lives inside a ScrollableLayout, whose children aren't surfaced
        // through Screen.children() directly. Recurse into AbstractContainerWidget so we
        // can see widgets nested in the scroll area.
        val hasLoadingBefore = onClient { mc -> collectWidgetMessages(mc.screen!!).any { it == "Loading…" } }
        hasLoadingBefore shouldBe true

        runOnClient { mc ->
            (mc.screen as ManagedScreen).onTreeSnapshot(
                ManagedTreeSnapshotS2C(
                    leaves = listOf(ManagedLeafEntry("alpha", 1)),
                    intermediates = emptyList(),
                    currentSubpath = null,
                )
            )
        }
        waitClientTicks(1)

        val state = onClient { mc ->
            val msgs = collectWidgetMessages(mc.screen!!)
            val hasLoading = msgs.any { it == "Loading…" }
            val hasAlpha = msgs.any { it.contains("alpha") }
            hasLoading to hasAlpha
        }
        state.first shouldBe false
        state.second shouldBe true

        runOnClient { mc -> mc.setScreen(null) }
    }

    test("UC-MAN-06.a (text survives snapshot): typed spec name in EditBox survives an incoming ManagedTreeSnapshotS2C") {
        val initial = ManagedTreeSnapshotS2C(
            leaves = listOf(ManagedLeafEntry("alpha", 1)),
            intermediates = emptyList(),
            currentSubpath = null,
        )
        runOnClient { mc -> mc.setScreen(ManagedScreen(initial)) }
        waitForClientScreen(ManagedScreen::class.java)

        runOnClient { mc ->
            val box = mc.screen!!.children().filterIsInstance<EditBox>().single()
            box.value = "newspec"
        }

        runOnClient { mc ->
            (mc.screen as ManagedScreen).onTreeSnapshot(
                ManagedTreeSnapshotS2C(
                    leaves = listOf(
                        ManagedLeafEntry("alpha", 1),
                        ManagedLeafEntry("beta", 2),
                    ),
                    intermediates = emptyList(),
                    currentSubpath = null,
                )
            )
        }
        waitClientTicks(1)

        val preservedValue = onClient { mc ->
            mc.screen!!.children().filterIsInstance<EditBox>().single().value
        }
        preservedValue shouldBe "newspec"

        runOnClient { mc -> mc.setScreen(null) }
    }

    test("UC-MAN-06.a (creates file): clicking \"New Spec\" after typing creates the .spec.kts on disk") {
        val tmp = Files.createTempDirectory("uc-man-06a-")
        val leaf = tmp.resolve("alpha").also { it.createDirectories() }
        leaf.resolve("a.spec.kts").writeText(RecordingDslEmitter.emitStub("a"))

        try {
            onServer {
                val player = this.overworld().players().firstOrNull() ?: error("no overworld player")
                ManagedServerContext.set(this, ManagedServerContext(ManagedRoot(tmp.toAbsolutePath())))
                // Pin the active subpath directly server-side; the screen→server LoadManagedFolder
                // flow is already covered by ManagedTeleportSpec, and exercising it here would
                // require teleporting (which conflicts with the sentinel's world-lifetime contract).
                ManagedSession.setActive(player.uuid, "alpha")
                val dispatcher = CommandDispatcher<CommandSourceStack>()
                ManagedCommand.register(dispatcher)
                dispatcher.execute("redstonespecs managed", player.createCommandSourceStack())
            }

            waitForClientScreen(ManagedScreen::class.java)

            runOnClient { mc ->
                val box = mc.screen!!.children().filterIsInstance<EditBox>().single()
                box.value = "foo"
            }

            runOnClient { mc ->
                val btn = mc.screen!!.children()
                    .filterIsInstance<AbstractButton>()
                    .single { it.message.string == "New Spec" }
                btn.onPress(MouseButtonInfo(0, 0))
            }
            waitClientTicks(20)

            tmp.resolve("alpha/foo.spec.kts").exists() shouldBe true

            runOnClient { mc -> mc.setScreen(null) }
        } finally {
            onServer { ManagedServerContext.clear(this) }
            tmp.toFile().deleteRecursively()
        }
    }
})

/**
 * Recursively walks the screen's listener tree, collecting `.message.string` from every
 * `AbstractWidget`. Needed because widgets parented to a `ScrollableLayout` are not in
 * `Screen.children()` directly — they live behind the layout's inner `AbstractContainerWidget`.
 */
private fun collectWidgetMessages(screen: Screen): List<String> {
    val out = mutableListOf<String>()
    fun visit(node: GuiEventListener) {
        if (node is AbstractWidget) out += node.message.string
        if (node is AbstractContainerWidget) {
            node.children().forEach { visit(it) }
        } else if (node is ContainerEventHandler) {
            node.children().forEach { visit(it) }
        }
    }
    screen.children().forEach { visit(it) }
    return out
}
