package com.breadmoirai.redstonespecs.test

import com.breadmoirai.redstonespecs.client.ide.ProjectTreeState
import com.breadmoirai.redstonespecs.client.ide.explorerPanel
import com.breadmoirai.redstonespecs.client.ui.compose.ComposeOverlay
import com.breadmoirai.redstonespecs.client.ui.compose.dock.DockRegion
import com.breadmoirai.redstonespecs.client.ui.compose.dock.DockState
import com.breadmoirai.redstonespecs.client.viewport.ViewportState
import com.breadmoirai.redstonespecs.client.viewport.WindowViewportExt
import com.breadmoirai.redstonespecs.network.project.ProjectLeafEntry
import com.breadmoirai.redstonespecs.network.project.ProjectTreeSnapshotS2C
import com.breadmoirai.redstonespecs.testing.ClientSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.Path

class ProjectExplorerSpec : ClientSpec({
    fun capture(name: String): Path {
        val p = Path.of("screenshots", name).toAbsolutePath()
        Files.deleteIfExists(p)
        runOnClient { ViewportState.compositeCaptureRequest = p }
        val deadline = System.currentTimeMillis() + 6000
        while (!Files.exists(p) && System.currentTimeMillis() < deadline) Thread.sleep(50)
        Files.exists(p).shouldBeTrue(); return p
    }

    test("Explorer renders a project tree snapshot") {
        closeClientScreen(); waitClientTicks(2)
        val leaves = listOf(ProjectLeafEntry("adders/full-adder", 3), ProjectLeafEntry("clocks/ring", 1))
        val intermediates = listOf("adders", "clocks")
        val currentSubpath = "adders/full-adder"
        runOnClient { mc ->
            DockState.reset()
            ProjectTreeState.reset()
            ProjectTreeState.onSnapshot(ProjectTreeSnapshotS2C(
                leaves = leaves,
                intermediates = intermediates,
                currentSubpath = currentSubpath,
            ))
            DockState.leftPanels.add(explorerPanel())
            DockState.setVisible(DockRegion.LEFT, true)
            DockState.setSize(DockRegion.LEFT, 300)
            ViewportState.active = true; ComposeOverlay.enabled = true
            (mc.window as Any as WindowViewportExt).`redstonespecs$updateScaledFramebuffer`(true)
        }
        waitClientTicks(12)
        ProjectTreeState.snapshot?.leaves?.map { it.subpath } shouldBe leaves.map { it.subpath }
        ProjectTreeState.snapshot?.intermediates shouldBe intermediates
        ProjectTreeState.snapshot?.currentSubpath shouldBe currentSubpath
        capture("explorer_tree.png")   // controller verifies: tree rows for adders/, clocks/, leaves

        runOnClient { mc ->
            ComposeOverlay.enabled = false; ViewportState.active = false; DockState.reset()
            (mc.window as Any as WindowViewportExt).`redstonespecs$updateScaledFramebuffer`(true)
        }
        waitClientTicks(6)
    }
})
