package com.breadmoirai.garnet.test

import com.breadmoirai.garnet.client.ide.ProjectTreeState
import com.breadmoirai.garnet.client.ide.explorerPanel
import com.breadmoirai.garnet.client.ui.compose.ComposeOverlay
import com.breadmoirai.garnet.client.ui.compose.dock.DockRegion
import com.breadmoirai.garnet.client.ui.compose.dock.DockState
import com.breadmoirai.garnet.client.viewport.ViewportState
import com.breadmoirai.garnet.client.viewport.WindowViewportExt
import com.breadmoirai.garnet.network.project.ProjectTreeSnapshotS2C
import com.breadmoirai.garnet.project.FileNode
import com.breadmoirai.garnet.project.FolderNode
import com.breadmoirai.garnet.project.walk
import com.breadmoirai.garnet.testing.ClientSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContainAll
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

    test("Explorer renders a recursive project tree snapshot") {
        closeClientScreen(); waitClientTicks(2)
        // root/
        //   adders/full-adder/full.spec.kts   (spec-folder)
        //   clocks/ring/ring.spec.kts          (spec-folder)
        val tree = FolderNode("root", listOf(
            FolderNode("adders", listOf(
                FolderNode("full-adder", listOf(FileNode("full.spec.kts", "kts"))),
            )),
            FolderNode("clocks", listOf(
                FolderNode("ring", listOf(FileNode("ring.spec.kts", "kts"))),
            )),
        ))
        runOnClient { mc ->
            DockState.reset()
            ProjectTreeState.reset()
            ProjectTreeState.onSnapshot(ProjectTreeSnapshotS2C(root = tree, currentSubpath = "adders/full-adder"))
            // Expand so the nested folders and files are visible in the screenshot.
            ProjectTreeState.toggleExpanded("adders")
            ProjectTreeState.toggleExpanded("adders/full-adder")
            DockState.leftPanels.add(explorerPanel())
            DockState.setVisible(DockRegion.LEFT, true)
            DockState.setSize(DockRegion.LEFT, 300)
            ViewportState.active = true; ComposeOverlay.enabled = true
            (mc.window as Any as WindowViewportExt).`garnet$updateScaledFramebuffer`(true)
        }
        waitClientTicks(12)
        val paths = ProjectTreeState.snapshot!!.root.walk().map { it.first }.toList()
        paths shouldContainAll listOf(
            "adders", "clocks",
            "adders/full-adder", "adders/full-adder/full.spec.kts",
            "clocks/ring/ring.spec.kts",
        )
        ProjectTreeState.snapshot!!.currentSubpath shouldBe "adders/full-adder"
        capture("explorer_tree.png")   // controller verifies: adders/ expanded to full-adder → full.spec.kts; clocks/ collapsed

        runOnClient { mc ->
            ComposeOverlay.enabled = false; ViewportState.active = false; DockState.reset()
            (mc.window as Any as WindowViewportExt).`garnet$updateScaledFramebuffer`(true)
        }
        waitClientTicks(6)
    }

    test("Explorer header renders the root option button and opens the dropdown") {
        closeClientScreen(); waitClientTicks(2)
        val tree = FolderNode("myroot", listOf(
            FolderNode("set", listOf(FileNode("a.spec.kts", "kts"))),
        ))
        runOnClient { mc ->
            DockState.reset(); ProjectTreeState.reset()
            com.breadmoirai.garnet.client.ide.RootPickerController.resetForTest()
            ProjectTreeState.onSnapshot(ProjectTreeSnapshotS2C(root = tree, currentSubpath = null))
            com.breadmoirai.garnet.client.ide.RootPickerController.toggleMenu()
            DockState.leftPanels.add(explorerPanel())
            DockState.setVisible(DockRegion.LEFT, true); DockState.setSize(DockRegion.LEFT, 300)
            ViewportState.active = true; ComposeOverlay.enabled = true
            (mc.window as Any as WindowViewportExt).`garnet$updateScaledFramebuffer`(true)
        }
        waitClientTicks(12)
        com.breadmoirai.garnet.client.ide.RootPickerController.menuOpen shouldBe true
        capture("explorer_root_menu.png")
        runOnClient { mc ->
            com.breadmoirai.garnet.client.ide.RootPickerController.resetForTest()
            ComposeOverlay.enabled = false; ViewportState.active = false; DockState.reset()
            (mc.window as Any as WindowViewportExt).`garnet$updateScaledFramebuffer`(true)
        }
        waitClientTicks(6)
    }
})
