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
     * two captures (not just vs. background) bounded the changed area to roughly x[16,137] y[32,115].
     * The popup card itself is *not* what reaches x=16 — `FixedOffsetPositionProvider` places the
     * card's top-left at the click point (60, 40) with the clamp a no-op this far from any window
     * edge, so the "New"/"Rename" card's left edge sits at x≈60, matching the click. The extra width
     * to the left comes from a second, unrelated repaint: right-clicking also calls
     * `ExplorerTreeState.select(path)`, and Jewel's tree row selection highlight is a full-width bar
     * starting at the panel's left edge (x=0), not just under the card. The footprint recorded here is
     * the union of that highlight sliver and the card, not the card alone. Sampled on a 21x21 grid →
     * 441 samples over that footprint: closed reads 40/441 (the tree row underneath), open reads
     * 314/441. Use [CONTEXT_MENU_OPEN_MIN]/[CONTEXT_MENU_CLOSED_MAX] rather than the raw counts so
     * cosmetic drift does not break every call site.
     */
    fun contextMenuRegionDiffCount(png: Path): Int = regionDiffCount(png, 16..136 step 6, 32..112 step 4)

    /** Above this, the context-menu card is definitely painted over the region (measured 314/441 open). */
    const val CONTEXT_MENU_OPEN_MIN = 250

    /** Below this, no context-menu card is painted (measured 40/441 closed — the tree row underneath). */
    const val CONTEXT_MENU_CLOSED_MAX = 100

    /**
     * Samples whose blue channel leads red by more than [margin] — i.e. IntUi's *selection* blue.
     *
     * Deliberately not a background-difference count like [regionDiffCount]: inside an open menu card
     * every pixel already differs from the panel fill, so "differs from background" cannot tell a
     * hovered row from an idle one. The menu card, its border, and its label glyphs are all neutral
     * greys (r≈g≈b), so a blue-lead test isolates exactly the hover/selection bar and nothing else.
     */
    fun selectionPixelCount(png: Path, xs: IntProgression, ys: IntProgression, margin: Int = 30): Int {
        val img = ImageIO.read(awaitDecodable(png).toFile())
        return ys.sumOf { y ->
            xs.count { x ->
                val p = img.getRGB(x, y)
                val r = (p shr 16) and 0xFF
                val b = p and 0xFF
                b - r > margin
            }
        }
    }

    /**
     * The three rows of the right-click context-menu card as anchored by a right-click on the
     * "redstone" row at (90, 68) — `FixedOffsetPositionProvider` puts the card's top-left at the click
     * point, so these are click-point offsets: the card spans x[90,207] y[74,162], with row centres at
     * +19 ("New Folder"), +43 ("New Structure") and +76 ("Rename"). Measured from
     * `context_menu_hover_new_folder.png`. Each grid is 26x8 → 208 samples; a hovered row reads well
     * over half of them blue, an idle row reads 0.
     */
    val CONTEXT_MENU_ROW_XS: IntProgression = 96..196 step 4
    val CONTEXT_MENU_NEW_FOLDER_YS: IntProgression = 80..94 step 2
    val CONTEXT_MENU_RENAME_YS: IntProgression = 138..152 step 2

    /** Above this, the sampled menu row is carrying the hover highlight. */
    const val MENU_ROW_HOVERED_MIN = 100
}
