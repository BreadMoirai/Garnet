package com.breadmoirai.garnet.test

import com.breadmoirai.garnet.editor.explorer.ui.ExplorerTreeState
import com.breadmoirai.garnet.editor.ui.LocalHistoryState
import com.breadmoirai.garnet.editor.ui.OpenStructureState
import com.breadmoirai.garnet.editor.explorer.ui.ExplorerTreeSnapshot
import com.breadmoirai.garnet.editor.ui.StructureInfoState
import com.breadmoirai.garnet.editor.explorer.ui.explorerPanel
import com.breadmoirai.garnet.editor.ui.localHistoryPanel
import com.breadmoirai.garnet.editor.ui.structureInfoPanel
import com.breadmoirai.garnet.ui.compose.ComposeOverlay
import com.breadmoirai.garnet.ui.compose.ComposeSurface
import com.breadmoirai.garnet.ui.dock.DockRegion
import com.breadmoirai.garnet.ui.dock.DockState
import com.breadmoirai.garnet.ui.dock.STRIPE_WIDTH
import com.breadmoirai.garnet.ui.input.DockInputRouter
import com.breadmoirai.garnet.ui.viewport.ViewportState
import com.breadmoirai.garnet.ui.viewport.WindowViewportExt
import com.breadmoirai.garnet.editor.network.EditorTreeSnapshotS2C
import com.breadmoirai.garnet.editor.network.StructureAutoSavedS2C
import com.breadmoirai.garnet.editor.explorer.data.FileNode
import com.breadmoirai.garnet.editor.explorer.data.FolderNode
import com.breadmoirai.garnet.harness.ClientSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.shouldBe
import org.lwjgl.glfw.GLFW
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.math.abs

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
 * closed/reopened -- and both stay, because DockState.reset() (used by unmount) and the closeRegion()
 * epoch bump (used by close/reopen) are different code paths that could each reintroduce the leak
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
    //
    // PanelPixelProbe.menuRegionDiffCount's x window (10..112) was measured before the tool-window
    // stripe existed, when the LEFT panel body started at x=0. The stripe is now always painted
    // whenever any panel is open (DockState.anyActive()), which every test in this file triggers, so
    // the panel body actually starts at x=STRIPE_WIDTH -- shift the window by the same amount rather
    // than reuse the helper's stale one.
    fun dropdownRegionDiffCount(png: Path): Int =
        PanelPixelProbe.regionDiffCount(png, (STRIPE_WIDTH + 10)..(STRIPE_WIDTH + 112) step 6, 36..68 step 4)

    // Same stripe-offset reasoning as dropdownRegionDiffCount, applied to headerRegionDiffCount's
    // x window (originally 4..108).
    fun headerRegionDiffCount(png: Path): Int =
        PanelPixelProbe.regionDiffCount(png, (STRIPE_WIDTH + 4)..(STRIPE_WIDTH + 108) step 4, 36..48 step 4)

    fun openKebabMenu() {
        runOnClient {
            // Click the kebab (overflow) button in ExplorerToolbar's first row. The old root-name
            // Dropdown anchor is gone (task 3); the toolbar's leftmost control is the kebab, which
            // sits at roughly (14, 12) relative to the panel body -- offset by STRIPE_WIDTH in scene
            // coordinates now that the tool-window stripe occupies x=0..31.
            DockInputRouter.onGlfwMove((STRIPE_WIDTH + 14).toDouble(), 12.0)
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
            DockState.reset(); ExplorerTreeSnapshot.reset(); ExplorerTreeState.reset()
            ExplorerTreeSnapshot.onSnapshot(EditorTreeSnapshotS2C(root = tree, currentSubpath = "adders/full-adder"))
            ExplorerTreeState.toggleExpanded("adders")
            DockState.panels += explorerPanel()
            DockState.showPanel("garnet.explorer")
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
        val header = headerRegionDiffCount(treeShot)
        val menu = dropdownRegionDiffCount(treeShot)
        println("[jewel] fresh-mount guard: header=$header menu=$menu")
        header shouldBeGreaterThan 8
        menu shouldBeLessThan PanelPixelProbe.MENU_CLOSED_MAX

        runOnClient { DockInputRouter.focus(DockRegion.LEFT) }
        waitClientTicks(2)

        // 3. a routed pointer click selects a tree row. Rows start right below the toolbar; the root
        // itself is row 0 ("myproject", y~44), pushing every child down a row and indenting it a
        // level deeper -- click the first CHILD row ("adders", y~68, x~70 relative to the panel body,
        // chosen to land inside a solid glyph stroke rather than an inter-letter gap; offset by
        // STRIPE_WIDTH in scene coordinates for the tool-window stripe). If this coordinate drifts onto
        // a neighbouring row the click still fires ExplorerTreeState.select(path) for THAT row, so
        // assert the exact expected path rather than just "non-null".
        runOnClient {
            DockInputRouter.onGlfwMove((STRIPE_WIDTH + 70).toDouble(), 68.0)
            DockInputRouter.onGlfwPress(0)
        }
        waitClientTicks(3)
        runOnClient { DockInputRouter.onGlfwRelease(0) }
        waitClientTicks(4)
        capture("jewel_explorer_click.png")
        println("[jewel] click: selectedPath=${ExplorerTreeState.selectedPath}")
        ExplorerTreeState.selectedPath shouldBe "adders"

        // A click on a folder row does double duty: it selects AND toggles expansion
        // (ProjectExplorerPanel.onElementClick, `is FolderNode -> ExplorerTreeState.toggleExpanded(path)`,
        // deliberate per that file's own doc). "adders" was expanded by mountExplorer(), so this same
        // click that just selected it also collapsed it -- real, load-bearing coverage of that
        // click-to-toggle contract, not incidental.
        ExplorerTreeState.expandedPaths shouldNotContain "adders"

        // Re-expand it before testing arrow-key descent, which is the actual point of step 4 below:
        // DOWN should move into an expanded child, not just to the next visible row.
        runOnClient { ExplorerTreeState.toggleExpanded("adders") }
        waitClientTicks(4)

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
        // back. Exercises the closeRegion() epoch bump specifically, which DockState.reset() (used
        // by step 7) would otherwise mask -- a different code path that could reintroduce the leak on
        // its own. Focus is re-requested again: mountExplorer() reset DockState above.
        runOnClient { DockInputRouter.focus(DockRegion.LEFT) }
        waitClientTicks(2)
        openKebabMenu()
        dropdownRegionDiffCount(capture("jewel_explorer_hide_open.png")) shouldBeGreaterThan PanelPixelProbe.MENU_OPEN_MIN

        runOnClient { mc ->
            DockInputRouter.clearFocus()
            DockState.closeRegion(DockRegion.LEFT)
            ComposeOverlay.enabled = false; ViewportState.active = false
            (mc.window as Any as WindowViewportExt).`garnet$updateScaledFramebuffer`(true)
        }
        waitClientTicks(6)
        runOnClient { mc ->
            DockState.showPanel("garnet.explorer")
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

    test("clicking a stripe icon swaps the lit icon and what the LEFT region paints") {
        closeClientScreen(); waitClientTicks(2)
        runOnClient { OpenStructureState.reset(); LocalHistoryState.reset() }
        mountExplorer()
        // A second LEFT panel: this is also what puts a second icon in the stripe, so the stripe is
        // part of what is being captured here. Focus is needed because the click below goes through
        // DockInputRouter, which only forwards to the scene while a region is captured.
        runOnClient {
            DockState.panels += localHistoryPanel()
            DockState.showPanel("garnet.explorer")
            DockInputRouter.focus(DockRegion.LEFT)
        }
        waitClientTicks(12)
        val explorerShot = capture("stripe_panel_explorer.png")
        ComposeSurface.disabled.shouldBeFalse()

        // The stripe column itself, x in [0, 32) -- the one part of the dock no test sampled before
        // the final review. Box 0 is the Explorer, box 1 is Local History; the open one carries
        // ICON_SELECTED_BG across its whole 28x28 box, the other shows only its icon artwork.
        val explorerIcon0 = PanelPixelProbe.stripeIconDiffCount(explorerShot, 0)
        val explorerIcon1 = PanelPixelProbe.stripeIconDiffCount(explorerShot, 1)
        println("[jewel] stripe icons (Explorer open): box0=$explorerIcon0/196 box1=$explorerIcon1/196")
        explorerIcon0 shouldBeGreaterThan explorerIcon1
        // The idle box still has to paint something: this is what proves the IconKey rasterized to
        // real artwork rather than the stripe being an empty 32px gutter with a highlight in it.
        explorerIcon1 shouldBeGreaterThan 0

        // A REAL pointer click into the stripe, not DockState.togglePanel(): the whole point of the
        // final review's Critical 1 was that no test ever routed an event through DockStripe's
        // gesture detector, so the missing commitDockVisibilityChange() follow-up went unnoticed.
        // Box 1 spans y in [36, 64) at Density(1f) (4dp top padding + 28dp box, twice), centre y=50;
        // x=16 is the middle of the STRIPE_WIDTH=32 column.
        runOnClient {
            DockInputRouter.onGlfwMove(16.0, 50.0)
            DockInputRouter.onGlfwPress(0)
        }
        waitClientTicks(3)
        runOnClient { DockInputRouter.onGlfwRelease(0) }
        waitClientTicks(12)
        val historyShot = capture("stripe_panel_history.png")
        onClient { DockState.openPanelId(DockRegion.LEFT) } shouldBe "garnet.localHistory"

        // ...and the highlight followed the click to the other icon.
        val historyIcon0 = PanelPixelProbe.stripeIconDiffCount(historyShot, 0)
        val historyIcon1 = PanelPixelProbe.stripeIconDiffCount(historyShot, 1)
        println("[jewel] stripe icons (History open): box0=$historyIcon0/196 box1=$historyIcon1/196")
        historyIcon1 shouldBeGreaterThan historyIcon0
        historyIcon0 shouldBeGreaterThan 0

        // Asserted by pixel diff, not by state flags: showPanel reads back cleanly the instant it is
        // called, while a stale composition can still be the thing on screen -- exactly the
        // ghost-panel failure DockState.mountEpoch exists to prevent. The Explorer's tree fills this
        // region with glyphs; Local History with no structure open paints a single line, so the two
        // frames must differ across most of the body.
        val changed = bodyDiffCount(explorerShot, historyShot)
        println("[jewel] stripe panel probe: changed=$changed/${BODY_SAMPLES}")
        changed shouldBeGreaterThan 50
        ComposeSurface.disabled.shouldBeFalse()

        unmount()
        ComposeSurface.disabled.shouldBeFalse()
    }

    test("the Structure Info panel paints its fields, and its empty state when nothing is open") {
        closeClientScreen(); waitClientTicks(2)
        runOnClient { StructureInfoState.reset() }
        mountExplorer()
        runOnClient {
            DockState.panels += structureInfoPanel()
            DockState.showPanel("garnet.structureInfo")
        }
        waitClientTicks(12)
        val emptyShot = capture("structure_info_empty.png")
        ComposeSurface.disabled.shouldBeFalse()

        // Drive it through the real receiver rather than by writing fields: that is the path the
        // panel actually sees, and it is what pins the packet-to-field mapping end to end.
        runOnClient {
            StructureInfoState.onAutoSaved(
                StructureAutoSavedS2C("redstone/clock.nbt", 5, 3, 7, 42, savedAtMillis = 1_700_000_000_000L),
            )
        }
        waitClientTicks(12)
        val filledShot = capture("structure_info_filled.png")
        onClient { StructureInfoState.blockCount } shouldBe 42

        // Empty state is one line; the filled state is a name plus three rows, so the two frames must
        // differ well below the first line of text.
        val changed = bodyDiffCount(emptyShot, filledShot)
        println("[jewel] structure info probe: changed=$changed/${BODY_SAMPLES}")
        changed shouldBeGreaterThan 10
        ComposeSurface.disabled.shouldBeFalse()

        unmount()
        ComposeSurface.disabled.shouldBeFalse()
    }

})

private val BODY_XS = (STRIPE_WIDTH + 4)..(STRIPE_WIDTH + 300) step 6
private val BODY_YS = 30..200 step 6
private val BODY_SAMPLES = BODY_XS.count() * BODY_YS.count()

/**
 * Samples differing between two captures over the LEFT panel body.
 *
 * Deliberately a capture-to-capture diff rather than PanelPixelProbe's diff-from-background count:
 * "differs from the panel fill" is a property both tabs have (each paints text over the same fill),
 * so only comparing the two frames to each other can show that the painted content CHANGED.
 */
private fun bodyDiffCount(a: Path, b: Path, tolerance: Int = 20): Int {
    val ia = ImageIO.read(PanelPixelProbe.awaitDecodable(a).toFile())
    val ib = ImageIO.read(PanelPixelProbe.awaitDecodable(b).toFile())
    return BODY_YS.sumOf { y ->
        BODY_XS.count { x ->
            val pa = ia.getRGB(x, y)
            val pb = ib.getRGB(x, y)
            abs(((pa shr 16) and 0xFF) - ((pb shr 16) and 0xFF)) +
                abs(((pa shr 8) and 0xFF) - ((pb shr 8) and 0xFF)) +
                abs((pa and 0xFF) - (pb and 0xFF)) > tolerance
        }
    }
}
