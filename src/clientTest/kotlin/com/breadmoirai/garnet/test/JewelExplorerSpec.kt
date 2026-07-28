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
import javax.imageio.ImageIO
import kotlin.math.abs

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

    // Counts, out of a fixed sample grid over the dropdown-menu region (x 12..160, y 55..118 in the
    // 854x480 composite, measured from jewel_explorer_dropdown.png), how many sampled pixels differ
    // from a plain-panel background sample at (250, 300). A menu card painted over that region fills
    // it almost entirely (measured 170/170 open vs 62/170 with the panel mounted but no menu), so this
    // discriminates "menu rendered" from "menu absent" rather than just "capture file exists".
    fun dropdownRegionDiffCount(png: Path): Int {
        val img = ImageIO.read(png.toFile())
        fun rgb(x: Int, y: Int): Triple<Int, Int, Int> {
            val p = img.getRGB(x, y)
            return Triple((p shr 16) and 0xFF, (p shr 8) and 0xFF, p and 0xFF)
        }
        val (bgR, bgG, bgB) = rgb(250, 300)
        return (58..112 step 6).sumOf { y ->
            (16..150 step 8).count { x ->
                val (r, g, b) = rgb(x, y)
                abs(r - bgR) + abs(g - bgG) + abs(b - bgB) > 20
            }
        }
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

    fun mountExplorer() {
        runOnClient { mc ->
            DockState.reset(); ProjectTreeState.reset(); ExplorerTreeState.reset()
            ProjectTreeState.onSnapshot(ProjectTreeSnapshotS2C(root = tree, currentSubpath = "adders/full-adder"))
            ExplorerTreeState.toggleExpanded("adders")
            DockState.leftPanels.add(explorerPanel())
            DockState.setVisible(DockRegion.LEFT, true)
            DockState.setSize(DockRegion.LEFT, 320)
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
        // Rows start below the tab strip, header and actions row. Click the first tree row
        // ("adders", measured at y≈93 in jewel_explorer_tree.png — the brief's y=78 guess landed
        // in the gap between the actions row and the first tree row and missed). If this coordinate
        // drifts onto a neighbouring row ("clocks", "box.nbt", ...) the click still fires
        // ExplorerTreeState.select(path) for THAT row, so selectedPath is still non-null — a null
        // check alone would go green on a mis-click. Assert the exact expected path instead.
        runOnClient {
            DockInputRouter.onGlfwMove(80.0, 93.0)
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
            DockInputRouter.onGlfwMove(80.0, 93.0)
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
        runOnClient {
            DockInputRouter.onGlfwMove(40.0, 34.0)
            DockInputRouter.onGlfwPress(0)
        }
        waitClientTicks(2)
        runOnClient { DockInputRouter.onGlfwRelease(0) }
        waitClientTicks(8)
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
        closedCount shouldBeLessThan 120
        openCount shouldBeGreaterThan 140
        unmount()
        ComposeSurface.disabled.shouldBeFalse()
    }
})
