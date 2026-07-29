package com.breadmoirai.garnet.test

import com.breadmoirai.garnet.client.ide.ExplorerTreeState
import com.breadmoirai.garnet.client.ide.ProjectTreeState
import com.breadmoirai.garnet.client.ide.explorerPanel
import com.breadmoirai.garnet.client.ui.compose.ComposeOverlay
import com.breadmoirai.garnet.client.ui.compose.ComposeSurface
import com.breadmoirai.garnet.client.ui.compose.dock.DockRegion
import com.breadmoirai.garnet.client.ui.compose.dock.DockState
import com.breadmoirai.garnet.client.ui.compose.input.DockInputRouter
import com.breadmoirai.garnet.client.viewport.ViewportState
import com.breadmoirai.garnet.client.viewport.WindowViewportExt
import com.breadmoirai.garnet.network.project.ProjectTreeSnapshotS2C
import com.breadmoirai.garnet.project.FileNode
import com.breadmoirai.garnet.project.FolderNode
import com.breadmoirai.garnet.testing.ClientSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.shouldBe
import org.lwjgl.glfw.GLFW
import java.nio.file.Files
import java.nio.file.Path

/**
 * Verification gate for the Jewel migration. The assertions cover what can be checked in code; the
 * screenshots cover what cannot — specifically that chevrons and file icons actually PAINT rather
 * than rendering as magenta missing-resource placeholders, which is the failure mode when the
 * IntelliJ icon artwork artifact is absent from the classpath.
 *
 * Hard gate throughout: ComposeSurface.disabled must stay false. Any font/icon resource-load failure
 * during composition throws inside host.render(), which renderFrame turns into a permanent disable.
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
    // almost entirely (measured 170/170 open vs 62/170 with the panel mounted but no menu), so this
    // discriminates "menu rendered" from "menu absent" rather than just "capture file exists".
    // Lives in PanelPixelProbe because ProjectExplorerSpec needs the same probe to prove its own
    // captures are menu-FREE.
    fun dropdownRegionDiffCount(png: Path): Int = PanelPixelProbe.menuRegionDiffCount(png)

    fun openRootMenu() {
        runOnClient {
            // Click the header dropdown anchor. Was y=34 (below the now-removed 18px tab strip);
            // with the strip gone the whole panel body shifted up by TAB_H, so the anchor is at y=16.
            DockInputRouter.onGlfwMove(40.0, 16.0)
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
        FileNode("box.nbt", "nbt", hasUnsaved = true),
        FileNode("README.md", "md"),
    ))

    fun mountExplorer(width: Int = 320) {
        runOnClient { mc ->
            DockState.reset(); ProjectTreeState.reset(); ExplorerTreeState.reset()
            ProjectTreeState.onSnapshot(ProjectTreeSnapshotS2C(root = tree, currentSubpath = "adders/full-adder"))
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

    test("LazyTree renders with painted chevrons and file icons") {
        closeClientScreen(); waitClientTicks(2)
        mountExplorer()
        // Read this back: "adders" must show an EXPANDED chevron and "clocks" a collapsed one, both
        // painted (not magenta squares), plus folder/file icons and Inter-font labels.
        capture("jewel_explorer_tree.png")
        println("[jewel] tree: disabled=${ComposeSurface.disabled} reason=${ComposeSurface.disabledReason}")
        unmount()
        ComposeSurface.disabled.shouldBeFalse()
    }

    test("a routed pointer click selects a tree row") {
        closeClientScreen(); waitClientTicks(2)
        mountExplorer()
        runOnClient { DockInputRouter.focus(DockRegion.LEFT) }
        waitClientTicks(2)
        // Rows start below the header and actions row (no tab strip anymore). Click the first tree
        // row ("adders", measured at y≈75 in jewel_explorer_tree.png). If this coordinate drifts
        // onto a neighbouring row ("clocks", "box.nbt", ...) the click still fires
        // ExplorerTreeState.select(path) for THAT row, so selectedPath is still non-null — a null
        // check alone would go green on a mis-click. Assert the exact expected path instead.
        runOnClient {
            // Was y=93 (measured with the 18px tab strip present); the strip's removal shifted the
            // panel body up by TAB_H, so the first tree row ("adders") now sits at y=75.
            DockInputRouter.onGlfwMove(80.0, 75.0)
            DockInputRouter.onGlfwPress(0)
        }
        waitClientTicks(3)
        runOnClient { DockInputRouter.onGlfwRelease(0) }
        waitClientTicks(4)
        capture("jewel_explorer_click.png")
        println("[jewel] click: selectedPath=${ExplorerTreeState.selectedPath}")
        ExplorerTreeState.selectedPath shouldBe "adders"
        unmount()
        ComposeSurface.disabled.shouldBeFalse()
    }

    test("arrow keys navigate the tree end-to-end through the new key path") {
        closeClientScreen(); waitClientTicks(2)
        mountExplorer()
        runOnClient { DockInputRouter.focus(DockRegion.LEFT) }
        waitClientTicks(2)
        // Seed a selection by clicking (see the mis-click note in the previous test — same
        // coordinate, same neighbouring-row risk), so the tree has focus and a starting row.
        runOnClient {
            // Was y=93 (measured with the 18px tab strip present); the strip's removal shifted the
            // panel body up by TAB_H, so the first tree row ("adders") now sits at y=75.
            DockInputRouter.onGlfwMove(80.0, 75.0)
            DockInputRouter.onGlfwPress(0)
        }
        waitClientTicks(2)
        runOnClient { DockInputRouter.onGlfwRelease(0) }
        waitClientTicks(4)
        val before = ExplorerTreeState.selectedPath

        runOnClient { DockInputRouter.onGlfwKey(GLFW.GLFW_KEY_DOWN, GLFW.GLFW_PRESS, 0) }
        waitClientTicks(2)
        runOnClient { DockInputRouter.onGlfwKey(GLFW.GLFW_KEY_DOWN, GLFW.GLFW_RELEASE, 0) }
        waitClientTicks(4)
        val after = ExplorerTreeState.selectedPath

        capture("jewel_explorer_keynav.png")
        println("[jewel] keynav: before=$before after=$after")
        // The whole point of Task 3: this was impossible before, because keys never reached the scene.
        // Assert the exact rows, not just "changed" — "changed" alone would pass even if DOWN moved
        // selection to the wrong neighbour.
        before shouldBe "adders"
        after shouldBe "adders/full-adder"
        unmount()
        ComposeSurface.disabled.shouldBeFalse()
    }

    test("the root Dropdown opens in-scene over the Blaze3D FBO") {
        closeClientScreen(); waitClientTicks(2)
        mountExplorer()
        runOnClient { DockInputRouter.focus(DockRegion.LEFT) }
        waitClientTicks(2)
        // Baseline BEFORE opening the menu: same mount, same region, menu absent. This is what makes
        // the probe below a real gate rather than a "file exists" check — if it can't tell this capture
        // apart from the open one, it isn't testing anything.
        val closedShot = capture("jewel_explorer_dropdown_closed.png")
        val closedCount = dropdownRegionDiffCount(closedShot)
        println("[jewel] dropdown probe (closed): diffCount=$closedCount/170")

        // Click the header dropdown anchor, just below the panel tab strip.
        openRootMenu()
        // Read this back: "Open Folder" and "Attach Folder (soon)" must appear INSIDE the capture.
        // The capture is the FBO RenderTarget, not a screen grab, so an OS window physically cannot
        // appear in it -- if the menu is visible here, it genuinely rendered in-scene.
        val openShot = capture("jewel_explorer_dropdown.png")
        val openCount = dropdownRegionDiffCount(openShot)
        println("[jewel] dropdown probe (open): diffCount=$openCount/170")
        println("[jewel] dropdown: disabled=${ComposeSurface.disabled}")

        // Pixel-probe gate: the capture existing and the surface staying alive prove nothing about
        // whether the menu actually painted (a regression back to the retired RootMenu-overlay
        // failure mode would still leave both of those true). Require the open capture to be
        // (near-)fully covered by non-background pixels in the menu region, and the closed baseline
        // to be well below that — measured 170/170 open vs 62/170 closed on a known-good build.
        closedCount shouldBeLessThan PanelPixelProbe.MENU_CLOSED_MAX
        openCount shouldBeGreaterThan PanelPixelProbe.MENU_OPEN_MIN
        unmount()
        ComposeSurface.disabled.shouldBeFalse()
    }

    test("an open Dropdown cannot survive the panel being unmounted and remounted") {
        // REGRESSION (final-review Critical 1). The dock's ComposeSceneHost is a long-lived singleton
        // and rendering STOPS the moment the dock is hidden, so before the DockState.mountEpoch fix
        // the panel's composition was never disposed: a remounted Explorer reused the same slot, kept
        // the Jewel Dropdown's remembered open flag, and repainted the menu over the fresh panel.
        // In-game that is a ghost menu after hide/show with no way to dismiss it.
        //
        // This MUST be a pixel assertion. Every state flag (DockState, ProjectTreeState,
        // ExplorerTreeState) is reset by unmount/remount and looks perfectly clean while the menu is
        // still painting — the whole defect is that only the pixels remember.
        closeClientScreen(); waitClientTicks(2)
        mountExplorer()
        runOnClient { DockInputRouter.focus(DockRegion.LEFT) }
        waitClientTicks(2)
        openRootMenu()
        // Guard the guard: if the menu never opened, the post-remount assertion below would pass
        // vacuously and this test would prove nothing.
        val openCount = dropdownRegionDiffCount(capture("jewel_explorer_leak_open.png"))
        println("[jewel] leak probe (open, before unmount): diffCount=$openCount/170")
        openCount shouldBeGreaterThan PanelPixelProbe.MENU_OPEN_MIN

        unmount()
        mountExplorer()
        val afterCount = dropdownRegionDiffCount(capture("jewel_explorer_leak_remount.png"))
        println("[jewel] leak probe (after remount): diffCount=$afterCount/170")
        afterCount shouldBeLessThan PanelPixelProbe.MENU_CLOSED_MAX

        unmount()
        ComposeSurface.disabled.shouldBeFalse()
    }

    test("an open Dropdown cannot survive the LEFT region being hidden and shown again") {
        // The in-game path for the same defect: the keybind hides the region (it does NOT reset
        // DockState), syncDockViewport stops the overlay, and showing it again must not bring the
        // menu back. Exercises the setVisible(false) epoch bump specifically, which DockState.reset()
        // would otherwise mask.
        closeClientScreen(); waitClientTicks(2)
        mountExplorer()
        runOnClient { DockInputRouter.focus(DockRegion.LEFT) }
        waitClientTicks(2)
        openRootMenu()
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

        val afterCount = dropdownRegionDiffCount(capture("jewel_explorer_hide_reshow.png"))
        println("[jewel] hide/reshow probe: diffCount=$afterCount/170")
        afterCount shouldBeLessThan PanelPixelProbe.MENU_CLOSED_MAX

        unmount()
        ComposeSurface.disabled.shouldBeFalse()
    }

    test("ESC closes an open Dropdown before it drops dock focus") {
        // Finding 6: ESC used to drop dock focus unconditionally, so it could never reach an open
        // menu — which is what made the leaked menu undismissable. ESC must still report consumed to
        // the mixin in BOTH branches (vanilla's pause menu must never open over the dock); that half
        // of the contract is asserted here and in DockInputSpec.
        closeClientScreen(); waitClientTicks(2)
        mountExplorer()
        runOnClient { DockInputRouter.focus(DockRegion.LEFT) }
        waitClientTicks(2)
        openRootMenu()
        dropdownRegionDiffCount(capture("jewel_explorer_esc_open.png")) shouldBeGreaterThan PanelPixelProbe.MENU_OPEN_MIN

        onClient { DockInputRouter.onGlfwKey(GLFW.GLFW_KEY_ESCAPE, GLFW.GLFW_PRESS, 0) } shouldBe true
        waitClientTicks(8)
        val afterEsc = dropdownRegionDiffCount(capture("jewel_explorer_esc_closed.png"))
        val focusAfterEsc = onClient { DockState.focusedRegion }
        println("[jewel] esc probe: diffCount=$afterEsc/170 focus=$focusAfterEsc")
        afterEsc shouldBeLessThan PanelPixelProbe.MENU_CLOSED_MAX

        // A second ESC (nothing left to consume it) must still drop dock focus.
        onClient { DockInputRouter.onGlfwKey(GLFW.GLFW_KEY_ESCAPE, GLFW.GLFW_PRESS, 0) } shouldBe true
        onClient { DockState.focusedRegion } shouldBe null

        unmount()
        ComposeSurface.disabled.shouldBeFalse()
    }

    test("the action row renders intact at the shipped DEFAULT_LEFT width") {
        // Finding 9: the 300px layout fix was only ever exercised at 300/320px, but DEFAULT_LEFT is
        // the width users actually get from the keybind, so that is what has to be covered.
        //
        // Comparing against a wide reference capture rather than probing a bounding box: at 260 (the
        // old default) all four controls WERE on-canvas and the Discard box drew its full border —
        // Jewel squeezed the button's inner width instead, truncating the label to "Discar". Only a
        // pixel comparison against a width that definitely fits catches that.
        closeClientScreen(); waitClientTicks(2)
        mountExplorer(width = 320)
        val reference = capture("jewel_explorer_row_ref320.png")
        unmount()
        mountExplorer(width = DockState.DEFAULT_LEFT)
        val shot = capture("jewel_explorer_default_width.png")
        val mismatch = PanelPixelProbe.actionRowMismatch(reference, shot)
        println("[jewel] default-width(${DockState.DEFAULT_LEFT}) action-row mismatch=$mismatch px")
        mismatch shouldBe 0
        unmount()
        ComposeSurface.disabled.shouldBeFalse()
    }
})
