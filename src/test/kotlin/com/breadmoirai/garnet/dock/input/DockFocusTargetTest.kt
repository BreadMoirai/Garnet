package com.breadmoirai.garnet.dock.input

import com.breadmoirai.garnet.dock.shell.DockRegion
import com.breadmoirai.garnet.dock.shell.DockState
import com.breadmoirai.garnet.dock.shell.Panel
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.jetbrains.jewel.ui.icons.AllIconsKeys

/**
 * Which region the dock-focus keybind (`G`) hands the keyboard and cursor to.
 *
 * Kept a pure function over [DockState] — no `Minecraft`, no GLFW — so the choice is pinned here
 * rather than only inside a client gametest that has to boot a real client to observe it. The
 * keybind itself (cursor release, toggle-back) is covered by `DockFocusKeybindSpec` in
 * `src/clientTest`.
 */
class DockFocusTargetTest : FunSpec({

    fun panel(id: String, region: DockRegion) =
        Panel(id, id, region, AllIconsKeys.General.Information) {}

    beforeTest {
        DockState.reset()
        DockState.panels += panel("garnet.explorer", DockRegion.LEFT)
        DockState.panels += panel("garnet.right", DockRegion.RIGHT)
        DockState.panels += panel("garnet.bottom", DockRegion.BOTTOM)
    }

    afterTest { DockState.reset() }

    test("with nothing open the target is LEFT — the stripe's own column") {
        // Focusing an empty LEFT is the "free the cursor and show the stripe" state: `anyActive()`
        // becomes true because a region is focused, the stripe renders, and `regionAt` attributes
        // the stripe column to LEFT, so a click on an icon lands on the focused region.
        DockState.focusTarget() shouldBe DockRegion.LEFT
    }

    test("the only visible region wins even when it is not LEFT") {
        DockState.showPanel("garnet.bottom")

        DockState.focusTarget() shouldBe DockRegion.BOTTOM
    }

    test("the last focused region wins over the first visible one") {
        DockState.showPanel("garnet.explorer")
        DockState.showPanel("garnet.bottom")
        DockState.focusedRegion = DockRegion.BOTTOM
        DockState.focusedRegion = null

        // Not LEFT, even though LEFT is open and comes first in region order: G means "put me back
        // where I was".
        DockState.focusTarget() shouldBe DockRegion.BOTTOM
    }

    test("a last focused region that has since closed falls back to the first visible one") {
        DockState.showPanel("garnet.explorer")
        DockState.showPanel("garnet.bottom")
        DockState.focusedRegion = DockRegion.BOTTOM
        DockState.focusedRegion = null
        DockState.closeRegion(DockRegion.BOTTOM)

        DockState.focusTarget() shouldBe DockRegion.LEFT
    }

    test("reset clears the remembered region") {
        DockState.showPanel("garnet.bottom")
        DockState.focusedRegion = DockRegion.BOTTOM
        DockState.focusedRegion = null
        DockState.reset()
        DockState.panels += panel("garnet.explorer", DockRegion.LEFT)
        DockState.panels += panel("garnet.bottom", DockRegion.BOTTOM)
        DockState.showPanel("garnet.explorer")
        DockState.showPanel("garnet.bottom")

        // Without this the memory would leak across a client-gametest spec boundary (and across a
        // world session), silently changing where a later G press lands.
        DockState.focusTarget() shouldBe DockRegion.LEFT
    }
})
