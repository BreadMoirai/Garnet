package com.breadmoirai.garnet.test

import com.breadmoirai.garnet.editor.ui.ExplorerTreeState
import com.breadmoirai.garnet.editor.ui.ProjectTreeState
import com.breadmoirai.garnet.editor.ui.RootPickerController
import com.breadmoirai.garnet.editor.ui.explorerPanel
import com.breadmoirai.garnet.ui.compose.ComposeOverlay
import com.breadmoirai.garnet.ui.dock.DockRegion
import com.breadmoirai.garnet.ui.dock.DockState
import com.breadmoirai.garnet.ui.viewport.ViewportState
import com.breadmoirai.garnet.ui.viewport.WindowViewportExt
import com.breadmoirai.garnet.editor.network.EditorTreeSnapshotS2C
import com.breadmoirai.garnet.editor.data.FileNode
import com.breadmoirai.garnet.editor.data.FolderNode
import com.breadmoirai.garnet.editor.data.walk
import com.breadmoirai.garnet.harness.ClientSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeLessThan
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
            ExplorerTreeState.reset()
            ProjectTreeState.onSnapshot(EditorTreeSnapshotS2C(root = tree, currentSubpath = "adders/full-adder"))
            // Expand so the nested folders and files are visible in the screenshot.
            ExplorerTreeState.toggleExpanded("adders")
            ExplorerTreeState.toggleExpanded("adders/full-adder")
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
        // The snapshot assertions above only prove the test's own fixture round-tripped; they cannot
        // fail for any rendering reason. These probe the capture instead. (controller also verifies
        // by eye: adders/ expanded to full-adder → full.spec.kts; clocks/ collapsed)
        val shot = capture("explorer_tree.png")
        val header = PanelPixelProbe.headerRegionDiffCount(shot)
        val menu = PanelPixelProbe.menuRegionDiffCount(shot)
        println("[explorer] tree capture: header=$header/108 menu=$menu/162")
        // The panel actually painted: the first tree row's folder icon + label are on-screen.
        header shouldBeGreaterThan 8
        // ...and NO dropdown menu is open. This spec never clicks the dropdown, so a menu card here
        // means a popup leaked in from another spec's mount (final-review Critical 1).
        menu shouldBeLessThan PanelPixelProbe.MENU_CLOSED_MAX

        runOnClient { mc ->
            ComposeOverlay.enabled = false; ViewportState.active = false; DockState.reset()
            (mc.window as Any as WindowViewportExt).`garnet$updateScaledFramebuffer`(true)
        }
        waitClientTicks(6)
    }

    // Named for the root-name Dropdown this used to cover (task 3 removed it — the root name is no
    // longer rendered anywhere in the panel, only tracked in ProjectTreeState). Kept as a distinct
    // fresh-mount scenario: this is where the leaked-popup regression first showed itself.
    test("Explorer header renders the root name") {
        closeClientScreen(); waitClientTicks(2)
        val tree = FolderNode("myroot", listOf(
            FolderNode("set", listOf(FileNode("a.spec.kts", "kts"))),
        ))
        runOnClient { mc ->
            DockState.reset(); ProjectTreeState.reset(); ExplorerTreeState.reset()
            RootPickerController.resetForTest()
            ProjectTreeState.onSnapshot(EditorTreeSnapshotS2C(root = tree, currentSubpath = null))
            DockState.leftPanels.add(explorerPanel())
            DockState.setVisible(DockRegion.LEFT, true); DockState.setSize(DockRegion.LEFT, 300)
            ViewportState.active = true; ComposeOverlay.enabled = true
            (mc.window as Any as WindowViewportExt).`garnet$updateScaledFramebuffer`(true)
        }
        waitClientTicks(12)
        ProjectTreeState.snapshot!!.root.name shouldBe "myroot"
        val shot = capture("explorer_root_header.png")
        val header = PanelPixelProbe.headerRegionDiffCount(shot)
        val menu = PanelPixelProbe.menuRegionDiffCount(shot)
        println("[explorer] header capture: header=$header/108 menu=$menu/162")
        // Same pairing as above: the header genuinely painted, and it painted CLOSED. This capture is
        // where the leaked-popup regression showed itself first.
        header shouldBeGreaterThan 8
        menu shouldBeLessThan PanelPixelProbe.MENU_CLOSED_MAX
        runOnClient { mc ->
            RootPickerController.resetForTest()
            ComposeOverlay.enabled = false; ViewportState.active = false; DockState.reset()
            (mc.window as Any as WindowViewportExt).`garnet$updateScaledFramebuffer`(true)
        }
        waitClientTicks(6)
    }
})
