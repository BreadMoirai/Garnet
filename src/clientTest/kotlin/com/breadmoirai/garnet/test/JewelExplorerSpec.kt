package com.breadmoirai.garnet.test

import com.breadmoirai.garnet.editor.ui.ExplorerTreeState
import com.breadmoirai.garnet.editor.ui.ProjectTreeState
import com.breadmoirai.garnet.editor.ui.explorerPanel
import com.breadmoirai.garnet.ui.compose.ComposeOverlay
import com.breadmoirai.garnet.ui.compose.ComposeSurface
import com.breadmoirai.garnet.ui.dock.DockRegion
import com.breadmoirai.garnet.ui.dock.DockState
import com.breadmoirai.garnet.ui.input.DockInputRouter
import com.breadmoirai.garnet.ui.viewport.ViewportState
import com.breadmoirai.garnet.ui.viewport.WindowViewportExt
import com.breadmoirai.garnet.editor.network.EditorTreeSnapshotS2C
import com.breadmoirai.garnet.editor.data.FileNode
import com.breadmoirai.garnet.editor.data.FolderNode
import com.breadmoirai.garnet.harness.ClientSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.shouldBe
import org.lwjgl.glfw.GLFW
import java.nio.file.Files
import java.nio.file.Path

/**
 * Verification gate for the Jewel migration, consolidated into one continuous mount. The assertions
 * cover what can be checked in code; the screenshots cover what cannot -- specifically that chevrons
 * and file icons actually PAINT rather than rendering as magenta missing-resource placeholders, which
 * is the failure mode when the IntelliJ icon artwork artifact is absent from the classpath.
 *
 * Hard gate throughout: ComposeSurface.disabled must stay false. Any font/icon resource-load failure
 * during composition throws inside host.render(), which renderFrame turns into a permanent disable.
 *
 * Step 2 folds in the former ProjectExplorerSpec's leaked-popup guard at this spec's own fresh mount
 * (that spec is gone; its only remaining job was proving THIS panel's captures start out menu-free,
 * so it belongs here rather than in a standalone file). Steps 7 and 8 are two distinct teardown paths
 * for the same kebab-menu-survives-a-remount regression -- panel unmount/remount vs. LEFT region
 * hidden/shown -- and both stay, because DockState.reset() (used by unmount) and the setVisible(false)
 * epoch bump (used by hide/show) are different code paths that could each reintroduce the leak
 * independently.
 */
class JewelExplorerSpec : ClientSpec({

    fun capture(name: String): Path {
        val p = Path.of("screenshots", name).toAbsolutePath()
        Files.deleteIfExists(p)
        runOnClient { ViewportState.compositeCaptureRequest = p }
        val deadline = System.currentTimeMillis() + 6000
        while (!Files.exists(p) && System.currentTimeMillis() < deadline) Thread.sleep(50)
        Files.exists(p).shouldBeTrue()
        return p
    }

    // Counts, out of a fixed sample grid over the dropdown-menu region, how many sampled pixels
    // differ from a plain-panel background sample. A menu card painted over that region fills it
    // almost entirely (measured 162/162 open vs 31/162 with the panel mounted but no menu), so this
    // discriminates "menu rendered" from "menu absent" rather than just "capture file exists".
    fun dropdownRegionDiffCount(png: Path): Int = PanelPixelProbe.menuRegionDiffCount(png)

    fun openKebabMenu() {
        runOnClient {
            // Click the kebab (overflow) button in ExplorerToolbar's first row. The old root-name
            // Dropdown anchor is gone (task 3); the toolbar's leftmost control is the kebab, which
            // sits at roughly (14, 12) with the tab strip removed and the toolbar as the panel's
            // first row.
            DockInputRouter.onGlfwMove(14.0, 12.0)
            DockInputRouter.onGlfwPress(0)
        }
        waitClientTicks(2)
        runOnClient { DockInputRouter.onGlfwRelease(0) }
        waitClientTicks(8)
    }

    val tree = FolderNode("myproject", listOf(
        FolderNode("adders", listOf(
            FolderNode("full-adder", listOf(FileNode("full.spec.kts", "kts"))),
        )),
        FolderNode("clocks", listOf(
            FolderNode("ring", listOf(FileNode("ring.spec.kts", "kts"))),
        )),
        FileNode("box.nbt", "nbt"),
        FileNode("README.md", "md"),
    ))

    fun mountExplorer(width: Int = 320) {
        runOnClient { mc ->
            DockState.reset(); ProjectTreeState.reset(); ExplorerTreeState.reset()
            ProjectTreeState.onSnapshot(EditorTreeSnapshotS2C(root = tree, currentSubpath = "adders/full-adder"))
            ExplorerTreeState.toggleExpanded("adders")
            DockState.leftPanels.add(explorerPanel())
            DockState.setVisible(DockRegion.LEFT, true)
            DockState.setSize(DockRegion.LEFT, width)
            ViewportState.active = true
            ComposeOverlay.enabled = true
            (mc.window as Any as WindowViewportExt).`garnet$updateScaledFramebuffer`(true)
        }
        waitClientTicks(16)
    }

    fun unmount() {
        runOnClient { mc ->
            ComposeOverlay.enabled = false; ViewportState.active = false
            DockInputRouter.clearFocus(); DockState.reset()
            (mc.window as Any as WindowViewportExt).`garnet$updateScaledFramebuffer`(true)
        }
        waitClientTicks(6)
    }

    test("the Explorer tree renders, selects, navigates, and its kebab menu never outlives a remount") {
        closeClientScreen(); waitClientTicks(2)
        mountExplorer()

        // 1. LazyTree renders with painted chevrons and file icons. Read this back: "adders" must
        // show an EXPANDED chevron and "clocks" a collapsed one, both painted (not magenta squares),
        // plus folder/file icons and Inter-font labels.
        val treeShot = capture("jewel_explorer_tree.png")
        println("[jewel] tree: disabled=${ComposeSurface.disabled} reason=${ComposeSurface.disabledReason}")
        ComposeSurface.disabled.shouldBeFalse()

        // 2. fold in the former ProjectExplorerSpec's leaked-popup guard, at this same fresh mount --
        // this is where that regression first showed itself. `header` proves the panel actually
        // painted; `menu` proves no dropdown leaked in from another spec's mount.
        val header = PanelPixelProbe.headerRegionDiffCount(treeShot)
        val menu = PanelPixelProbe.menuRegionDiffCount(treeShot)
        println("[jewel] fresh-mount guard: header=$header menu=$menu")
        header shouldBeGreaterThan 8
        menu shouldBeLessThan PanelPixelProbe.MENU_CLOSED_MAX

        runOnClient { DockInputRouter.focus(DockRegion.LEFT) }
        waitClientTicks(2)

        // 3. a routed pointer click selects a tree row. Rows start right below the toolbar; the root
        // itself is row 0 ("myproject", y~44), pushing every child down a row and indenting it a
        // level deeper -- click the first CHILD row ("adders", y~68, x~70, chosen to land inside a
        // solid glyph stroke rather than an inter-letter gap). If this coordinate drifts onto a
        // neighbouring row the click still fires ExplorerTreeState.select(path) for THAT row, so
        // assert the exact expected path rather than just "non-null".
        runOnClient {
            DockInputRouter.onGlfwMove(70.0, 68.0)
            DockInputRouter.onGlfwPress(0)
        }
        waitClientTicks(3)
        runOnClient { DockInputRouter.onGlfwRelease(0) }
        waitClientTicks(4)
        capture("jewel_explorer_click.png")
        println("[jewel] click: selectedPath=${ExplorerTreeState.selectedPath}")
        ExplorerTreeState.selectedPath shouldBe "adders"

        // 4. arrow keys navigate the tree end-to-end through the new key path. The whole point of
        // Task 3: this was impossible before, because keys never reached the scene. Assert the exact
        // rows, not just "changed" -- "changed" alone would pass even if DOWN moved selection to the
        // wrong neighbour.
        val before = ExplorerTreeState.selectedPath
        runOnClient { DockInputRouter.onGlfwKey(GLFW.GLFW_KEY_DOWN, GLFW.GLFW_PRESS, 0) }
        waitClientTicks(2)
        runOnClient { DockInputRouter.onGlfwKey(GLFW.GLFW_KEY_DOWN, GLFW.GLFW_RELEASE, 0) }
        waitClientTicks(4)
        val after = ExplorerTreeState.selectedPath
        capture("jewel_explorer_keynav.png")
        println("[jewel] keynav: before=$before after=$after")
        before shouldBe "adders"
        after shouldBe "adders/full-adder"

        // 5. the kebab menu opens in-scene over the Blaze3D FBO. Baseline BEFORE opening: same mount,
        // same region, menu absent -- what makes the probe below a real gate rather than a "file
        // exists" check. The capture is the FBO RenderTarget, not a screen grab, so an OS window
        // physically cannot appear in it -- if the menu is visible here, it genuinely rendered in-scene.
        val closedShot = capture("jewel_explorer_dropdown_closed.png")
        val closedCount = dropdownRegionDiffCount(closedShot)
        println("[jewel] dropdown probe (closed): diffCount=$closedCount/162")
        openKebabMenu()
        val openShot = capture("jewel_explorer_dropdown.png")
        val openCount = dropdownRegionDiffCount(openShot)
        println("[jewel] dropdown probe (open): diffCount=$openCount/162")
        closedCount shouldBeLessThan PanelPixelProbe.MENU_CLOSED_MAX
        openCount shouldBeGreaterThan PanelPixelProbe.MENU_OPEN_MIN
        ComposeSurface.disabled.shouldBeFalse()

        // 6. ESC closes the open kebab before it drops dock focus. ESC must still report consumed to
        // the mixin in BOTH branches (vanilla's pause menu must never open over the dock) -- that half
        // of the contract is asserted here and in DockInputSpec.
        onClient { DockInputRouter.onGlfwKey(GLFW.GLFW_KEY_ESCAPE, GLFW.GLFW_PRESS, 0) } shouldBe true
        waitClientTicks(8)
        val afterEsc = dropdownRegionDiffCount(capture("jewel_explorer_esc_closed.png"))
        val focusAfterEsc = onClient { DockState.focusedRegion }
        println("[jewel] esc probe: diffCount=$afterEsc/162 focus=$focusAfterEsc")
        afterEsc shouldBeLessThan PanelPixelProbe.MENU_CLOSED_MAX
        // A second ESC (nothing left to consume it) must still drop dock focus.
        onClient { DockInputRouter.onGlfwKey(GLFW.GLFW_KEY_ESCAPE, GLFW.GLFW_PRESS, 0) } shouldBe true
        onClient { DockState.focusedRegion } shouldBe null

        // 7. reopen the kebab, then unmount and remount the panel -- assert it did not survive.
        // REGRESSION (final-review Critical 1). The dock's ComposeSceneHost is a long-lived singleton
        // and rendering STOPS the moment the dock is hidden, so before the DockState.mountEpoch fix the
        // panel's composition was never disposed: a remounted Explorer reused the same slot, kept the
        // kebab menu's remembered open flag, and repainted the menu over the fresh panel. This MUST be
        // a pixel assertion -- every state flag is reset by unmount/remount and looks perfectly clean
        // while the menu is still painting; only the pixels remember. Focus is re-requested explicitly
        // rather than assumed to survive the ESC steps above, which just dropped it.
        runOnClient { DockInputRouter.focus(DockRegion.LEFT) }
        waitClientTicks(2)
        openKebabMenu()
        val leakOpenCount = dropdownRegionDiffCount(capture("jewel_explorer_leak_open.png"))
        println("[jewel] leak probe (open, before unmount): diffCount=$leakOpenCount/162")
        // Guard the guard: if the menu never opened, the post-remount assertion below would pass
        // vacuously and this step would prove nothing.
        leakOpenCount shouldBeGreaterThan PanelPixelProbe.MENU_OPEN_MIN

        unmount()
        mountExplorer()
        val leakRemountCount = dropdownRegionDiffCount(capture("jewel_explorer_leak_remount.png"))
        println("[jewel] leak probe (after remount): diffCount=$leakRemountCount/162")
        leakRemountCount shouldBeLessThan PanelPixelProbe.MENU_CLOSED_MAX
        ComposeSurface.disabled.shouldBeFalse()

        // 8. reopen the kebab, then hide and re-show the LEFT region -- assert it did not survive.
        // The in-game path for the same defect: the keybind hides the region (it does NOT reset
        // DockState), syncDockViewport stops the overlay, and showing it again must not bring the menu
        // back. Exercises the setVisible(false) epoch bump specifically, which DockState.reset() (used
        // by step 7) would otherwise mask -- a different code path that could reintroduce the leak on
        // its own. Focus is re-requested again: mountExplorer() reset DockState above.
        runOnClient { DockInputRouter.focus(DockRegion.LEFT) }
        waitClientTicks(2)
        openKebabMenu()
        dropdownRegionDiffCount(capture("jewel_explorer_hide_open.png")) shouldBeGreaterThan PanelPixelProbe.MENU_OPEN_MIN

        runOnClient { mc ->
            DockInputRouter.clearFocus()
            DockState.setVisible(DockRegion.LEFT, false)
            ComposeOverlay.enabled = false; ViewportState.active = false
            (mc.window as Any as WindowViewportExt).`garnet$updateScaledFramebuffer`(true)
        }
        waitClientTicks(6)
        runOnClient { mc ->
            DockState.setVisible(DockRegion.LEFT, true)
            ViewportState.active = true; ComposeOverlay.enabled = true
            (mc.window as Any as WindowViewportExt).`garnet$updateScaledFramebuffer`(true)
        }
        waitClientTicks(16)

        val hideReshowCount = dropdownRegionDiffCount(capture("jewel_explorer_hide_reshow.png"))
        println("[jewel] hide/reshow probe: diffCount=$hideReshowCount/162")
        hideReshowCount shouldBeLessThan PanelPixelProbe.MENU_CLOSED_MAX

        unmount()
        ComposeSurface.disabled.shouldBeFalse()
    }

})
