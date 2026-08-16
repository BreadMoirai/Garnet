package com.breadmoirai.garnet.test

import com.breadmoirai.garnet.ui.dock.STRIPE_WIDTH
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

    /**
     * Samples differing from a reference pixel by more than [tolerance] (Manhattan, 0..765).
     *
     * The reference defaults to the panel fill at ([BG_X], [BG_Y]). [refX]/[refY] override it for
     * regions whose "nothing painted here" colour is something else — the tool-window stripe, whose
     * own fill is `STRIPE_BG` and nowhere near the panel's.
     */
    fun regionDiffCount(
        png: Path,
        xs: IntProgression,
        ys: IntProgression,
        tolerance: Int = 20,
        refX: Int = BG_X,
        refY: Int = BG_Y,
    ): Int {
        val img = ImageIO.read(awaitDecodable(png).toFile())
        fun rgb(x: Int, y: Int): Triple<Int, Int, Int> {
            val p = img.getRGB(x, y)
            return Triple((p shr 16) and 0xFF, (p shr 8) and 0xFF, p and 0xFF)
        }
        val (bgR, bgG, bgB) = rgb(refX, refY)
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
     * anchored at `ExplorerUiSpec`'s first right-click point rather than the kebab menu's
     * toolbar-relative position — the two menus anchor differently (fixed-offset-at-click vs.
     * horizontal-under-a-button) so [menuRegionDiffCount]'s region is the wrong place to look here.
     *
     * Measured directly from `explorer_context_menu_{closed,open}.png`: a pixel-level diff between the
     * two captures (not just vs. background) bounded the changed area to roughly x[16,137] y[32,115]
     * *when the LEFT panel body still started at x=0*. The popup card itself is *not* what reaches the
     * left edge of that window — `FixedOffsetPositionProvider` places the card's top-left at the click
     * point with the clamp a no-op this far from any window edge, so the "New"/"Rename" card's left
     * edge sits at the click x. The extra width to the left comes from a second, unrelated repaint:
     * right-clicking also calls `ExplorerTreeState.select(path)`, and Jewel's tree row selection
     * highlight is a full-width bar starting at the panel body's left edge, not just under the card.
     * The footprint recorded here is the union of that highlight sliver and the card, not the card
     * alone.
     *
     * The whole footprint — panel edge, click point, and card alike — moved right by [STRIPE_WIDTH]
     * when the tool-window stripe took over `x ∈ [0, 32)`, so the original x window `16..136` is now
     * `48..168`; `ExplorerUiSpec`'s right-click moved by the same 32px. Sampled on a 21x21 grid → 441
     * samples: closed reads ~40/441 (the tree row underneath), open ~314/441. Use
     * [CONTEXT_MENU_OPEN_MIN]/[CONTEXT_MENU_CLOSED_MAX] rather than the raw counts so cosmetic drift
     * does not break every call site.
     */
    fun contextMenuRegionDiffCount(png: Path): Int =
        regionDiffCount(png, (STRIPE_WIDTH + 16)..(STRIPE_WIDTH + 136) step 6, 32..112 step 4)

    /** Above this, the context-menu card is definitely painted over the region (measured 314/441 open). */
    const val CONTEXT_MENU_OPEN_MIN = 250

    /** Below this, no context-menu card is painted (measured 40/441 closed — the tree row underneath). */
    const val CONTEXT_MENU_CLOSED_MAX = 100

    /**
     * How many samples inside the stripe icon box at [index] differ from the stripe's own fill.
     *
     * `DockStripe` lays its icons out as a `Column` of 28dp boxes each with 4dp of top padding, at
     * `Density(1f)`, so box `i` occupies `y ∈ [4 + 32i, 32 + 32i)` and `x ∈ [2, 30)` (28 centred in
     * [STRIPE_WIDTH]). Sampled on a 14x14 grid → 196 samples.
     *
     * The reference is a stripe pixel well below every icon, **not** the panel fill: the stripe paints
     * its own `STRIPE_BG` (0xFF1E1F22), which differs from the panel body's fill by far more than any
     * tolerance, so the inherited reference would score every sample as "painted" and measure nothing.
     *
     * Two things move this number: the icon artwork itself (a few dozen glyph pixels), and the
     * `ICON_SELECTED_BG` (0xFF2B2D30) highlight behind the open panel's icon, which covers the whole
     * box and leads the fill by 41 Manhattan — comfortably over the default tolerance of 20. So the
     * open panel's box always outscores its siblings by a wide margin, and comparing boxes to each
     * other is how a test tells *which* icon is lit without pinning a raw pixel count.
     */
    fun stripeIconDiffCount(png: Path, index: Int): Int = regionDiffCount(
        png,
        3..29 step 2,
        (5 + 32 * index)..(31 + 32 * index) step 2,
        refX = STRIPE_WIDTH / 2,
        refY = STRIPE_BG_SAMPLE_Y,
    )

    /**
     * A y inside the stripe but below every icon box (three panels reach y=96), used as the
     * "nothing painted here" reference for [stripeIconDiffCount]. Safe at any composite height the
     * specs use (854x480).
     */
    private const val STRIPE_BG_SAMPLE_Y = 300

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
     * "redstone" row — `FixedOffsetPositionProvider` puts the card's top-left at the click point, so
     * these are click-point offsets: measured at a click of (90, 68) the card spanned x[90,207]
     * y[74,162], with row centres at +19 ("New Folder"), +43 ("New Structure") and +76 ("Rename"),
     * from `context_menu_hover_new_folder.png`. That click is now at x = [STRIPE_WIDTH] + 90 (the
     * panel body starts behind the stripe), so the x window moves right by the same 32px while the y
     * windows, which are unaffected by a horizontal shift, do not. Each grid is 26x8 → 208 samples; a
     * hovered row reads well over half of them blue, an idle row reads 0.
     */
    val CONTEXT_MENU_ROW_XS: IntProgression = (STRIPE_WIDTH + 96)..(STRIPE_WIDTH + 196) step 4
    val CONTEXT_MENU_NEW_FOLDER_YS: IntProgression = 80..94 step 2
    val CONTEXT_MENU_RENAME_YS: IntProgression = 138..152 step 2

    /** Above this, the sampled menu row is carrying the hover highlight. */
    const val MENU_ROW_HOVERED_MIN = 100
}
