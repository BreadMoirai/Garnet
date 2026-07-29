package com.breadmoirai.garnet.test

import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.math.abs

/**
 * Pixel probes over the captured Compose composite.
 *
 * Why these exist: the Explorer's specs can only assert state they themselves set, which is
 * tautological — a panel that renders nothing at all, or one covered by a leaked popup, leaves every
 * such assertion green. These probes sample a fixed grid inside a screen region and count how many
 * samples differ from a known plain-panel-background pixel, which turns "did this actually paint?"
 * into a machine-checkable number.
 *
 * All coordinates are in the 854x480 composite. The reference background sample sits at (250, 300):
 * inside the LEFT panel at every width the specs use (260/300/320), below every widget, so it is
 * always the flat panel fill.
 */
object PanelPixelProbe {

    private const val BG_X = 250
    private const val BG_Y = 300

    /**
     * Block until [png] is a *complete, decodable* PNG, then return it.
     *
     * The capture path is asynchronous: `MinecraftPresentMixin` hands the composite to
     * `Screenshot.takeScreenshot`, which downloads the GPU buffer and writes the file on its own
     * schedule. A test that polls only for `Files.exists` can therefore win the race against the
     * writer and hand `ImageIO.read` a truncated file, which it reports by returning **null** — a
     * confusing `NullPointerException` in the probe rather than a capture-timing failure. Poll for
     * decodability instead.
     */
    fun awaitDecodable(png: Path, timeoutMs: Long = 10_000): Path {
        val deadline = System.currentTimeMillis() + timeoutMs
        var lastError: Throwable? = null
        while (System.currentTimeMillis() < deadline) {
            try {
                if (java.nio.file.Files.size(png) > 0 && ImageIO.read(png.toFile()) != null) return png
            } catch (t: Throwable) {
                lastError = t
            }
            Thread.sleep(50)
        }
        error("capture $png never became a decodable PNG within ${timeoutMs}ms (last error: $lastError)")
    }

    /** Samples differing from the panel background by more than [tolerance] (Manhattan, 0..765). */
    fun regionDiffCount(png: Path, xs: IntProgression, ys: IntProgression, tolerance: Int = 20): Int {
        val img = ImageIO.read(awaitDecodable(png).toFile())
        fun rgb(x: Int, y: Int): Triple<Int, Int, Int> {
            val p = img.getRGB(x, y)
            return Triple((p shr 16) and 0xFF, (p shr 8) and 0xFF, p and 0xFF)
        }
        val (bgR, bgG, bgB) = rgb(BG_X, BG_Y)
        return ys.sumOf { y ->
            xs.count { x ->
                val (r, g, b) = rgb(x, y)
                abs(r - bgR) + abs(g - bgG) + abs(b - bgB) > tolerance
            }
        }
    }

    /**
     * The region the kebab menu's `PopupMenu` card covers when open (measured from
     * `jewel_explorer_dropdown.png` after task 3 replaced the root-name Dropdown with the kebab
     * overflow menu), sampled on a fixed 18x9 grid → 162 samples.
     *
     * Discriminating: an open menu fills it almost entirely (measured 162/162), while the same panel
     * with the menu closed scores ~31/162 (the toolbar row and the first tree rows also live here).
     * Use [MENU_OPEN_MIN] / [MENU_CLOSED_MAX] rather than raw numbers so cosmetic drift does not
     * break every call site.
     */
    fun menuRegionDiffCount(png: Path): Int = regionDiffCount(png, 10..112 step 6, 36..68 step 4)

    /** Above this, a menu card is definitely painted over the region. */
    const val MENU_OPEN_MIN = 140

    /** Below this, no menu card is painted (only the normal toolbar / tree rows show through). */
    const val MENU_CLOSED_MAX = 60

    /**
     * The first tree row (folder icon + name, e.g. "adders" or "set"). Task 3 dropped the root-name
     * Dropdown that used to anchor this check — the toolbar's icon buttons are individually only a
     * few sparse pixels wide (a 3-dot kebab, thin chevrons), too thin to sample reliably on a coarse
     * grid, whereas the first row's label text is a dense, reliable glyph block. Non-zero only if the
     * panel actually composed and painted — the check that makes "the Explorer rendered" assertable
     * instead of merely assuming it from the state the test just set. 27x4 grid → 108 samples.
     */
    fun headerRegionDiffCount(png: Path): Int = regionDiffCount(png, 4..108 step 4, 36..48 step 4)

    /**
     * The region the right-click `New`/`Rename` context menu's `PopupMenu` card covers when open,
     * anchored at ExplorerContextMenuSpec's click point (60, 40) rather than the kebab menu's
     * toolbar-relative position — the two menus anchor differently (fixed-offset-at-click vs.
     * horizontal-under-a-button) so [menuRegionDiffCount]'s region is the wrong place to look here.
     *
     * Measured directly from `explorer_context_menu_{closed,open}.png`: a pixel-level diff between the
     * two captures (not just vs. background) bounded the card's actual footprint to roughly
     * x[16,137] y[32,115], which is *not* centered on the click point — the popup's content (a "New"
     * row with a submenu chevron, a separator, and "Rename") extends left and above it. Sampled on a
     * 21x21 grid → 441 samples over that footprint: closed reads 40/441 (the tree row underneath),
     * open reads 314/441. Use [CONTEXT_MENU_OPEN_MIN]/[CONTEXT_MENU_CLOSED_MAX] rather than the raw
     * counts so cosmetic drift does not break every call site.
     */
    fun contextMenuRegionDiffCount(png: Path): Int = regionDiffCount(png, 16..136 step 6, 32..112 step 4)

    /** Above this, the context-menu card is definitely painted over the region (measured 314/441 open). */
    const val CONTEXT_MENU_OPEN_MIN = 250

    /** Below this, no context-menu card is painted (measured 40/441 closed — the tree row underneath). */
    const val CONTEXT_MENU_CLOSED_MAX = 100
}
