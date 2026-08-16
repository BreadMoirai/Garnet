package com.breadmoirai.garnet.client.ui.dock

import com.breadmoirai.garnet.ui.compose.ComposeOverlay
import com.breadmoirai.garnet.ui.dock.DockRegion
import com.breadmoirai.garnet.ui.dock.DockState
import com.breadmoirai.garnet.ui.dock.Panel
import com.breadmoirai.garnet.ui.dock.stripeIconClicked
import com.breadmoirai.garnet.ui.viewport.DockVisibilityCommit
import com.breadmoirai.garnet.ui.viewport.ViewportState
import com.breadmoirai.garnet.ui.viewport.commitDockVisibilityChange
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import org.jetbrains.jewel.ui.icons.AllIconsKeys

/**
 * REGRESSION for the final review's Critical 1: a stripe click changed dock visibility and stopped
 * there, running none of the follow-ups every keybind path runs. `WindowMixin` caches the shrunk
 * framebuffer size and only recomputes it from `garnet$updateScaledFramebuffer(true)` or a real OS
 * resize, while `MinecraftPresentMixin` recomputes its blit rect fresh every present — so closing the
 * Explorer from the stripe left a 312px-narrower texture stretched across a 32px-narrower rect, and
 * the world stayed visibly distorted until an unrelated resize. Nothing caught it because no test
 * routed a click through the stripe at all.
 *
 * The click's behaviour is pinned here rather than by mounting `DockStripe`: that composable needs a
 * render context, which is why `stripeIconClicked` exists as a plain function one line beneath the
 * `detectTapGestures` lambda that calls it. The composable wiring itself — that the gesture really
 * reaches this function with the production callback attached — is covered by `JewelExplorerSpec`'s
 * stripe-click step in `src/clientTest`, which drives a real pointer event into the stripe column.
 */
class DockVisibilityCommitTest : FunSpec({

    val persisted = mutableListOf<Map<DockRegion, String>>()
    var focusDrops = 0
    var framebufferApplies = 0

    beforeTest {
        persisted.clear(); focusDrops = 0; framebufferApplies = 0
        DockVisibilityCommit.persistLayout = { persisted += it }
        DockVisibilityCommit.dropFocus = { focusDrops++; DockState.focusedRegion = null }
        DockVisibilityCommit.applyFramebuffer = { framebufferApplies++ }
        DockState.reset()
        DockState.panels += Panel(
            "garnet.explorer", "Explorer", DockRegion.LEFT, AllIconsKeys.General.Information,
        ) {}
        DockState.panels += Panel(
            "garnet.localHistory", "History", DockRegion.LEFT, AllIconsKeys.Vcs.History,
        ) {}
    }

    afterTest {
        DockVisibilityCommit.resetForTest()
        DockState.reset()
        ViewportState.active = false
        ComposeOverlay.enabled = false
    }

    test("a stripe click that closes the focused region runs the whole follow-up sequence") {
        DockState.showPanel("garnet.explorer")
        DockState.focusedRegion = DockRegion.LEFT
        commitDockVisibilityChange()
        persisted.clear(); focusDrops = 0; framebufferApplies = 0

        // The click on the lit Explorer icon: this is exactly what DockStripe's detectTapGestures runs.
        stripeIconClicked("garnet.explorer", ::commitDockVisibilityChange)

        DockState.isVisible(DockRegion.LEFT).shouldBeFalse()
        // 1. the new (empty) layout was persisted...
        persisted shouldBe listOf(emptyMap())
        // 2. ...focus was dropped, because the region it pointed at is gone. Without this the cursor
        // stays released and input keeps routing into an empty scene.
        focusDrops shouldBe 1
        DockState.focusedRegion shouldBe null
        // 3. ...the viewport/overlay flags followed the now-inactive dock...
        ViewportState.active.shouldBeFalse()
        ComposeOverlay.enabled.shouldBeFalse()
        // 4. ...and the cached framebuffer override was recomputed. This is the one that was missing.
        framebufferApplies shouldBe 1
    }

    test("a stripe click that swaps panels keeps focus and still re-applies the framebuffer") {
        DockState.showPanel("garnet.explorer")
        DockState.focusedRegion = DockRegion.LEFT
        commitDockVisibilityChange()
        persisted.clear(); focusDrops = 0; framebufferApplies = 0

        stripeIconClicked("garnet.localHistory", ::commitDockVisibilityChange)

        DockState.openPanelId(DockRegion.LEFT) shouldBe "garnet.localHistory"
        persisted shouldBe listOf(mapOf(DockRegion.LEFT to "garnet.localHistory"))
        // LEFT is still open, so focus has somewhere to live: the guard is "the focused region
        // closed", not "visibility changed".
        focusDrops shouldBe 0
        DockState.focusedRegion shouldBe DockRegion.LEFT
        ViewportState.active.shouldBeTrue()
        framebufferApplies shouldBe 1
    }

    test("persist = false skips the layout write but still syncs the viewport") {
        DockState.showPanel("garnet.explorer")

        commitDockVisibilityChange(persist = false)

        // The disconnect/auto-open paths: a programmatic visibility change must not overwrite the
        // record of what the player last chose.
        persisted shouldBe emptyList()
        ViewportState.active.shouldBeTrue()
        framebufferApplies shouldBe 1
    }

    test("focus is left alone when the focused region is still open") {
        DockState.showPanel("garnet.explorer")
        DockState.focusedRegion = DockRegion.LEFT

        commitDockVisibilityChange()

        focusDrops shouldBe 0
        DockState.focusedRegion shouldBe DockRegion.LEFT
    }
})
