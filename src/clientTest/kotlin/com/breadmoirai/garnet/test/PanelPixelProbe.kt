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
     * The region the root Dropdown's menu card covers when open (measured from
     * `jewel_explorer_dropdown.png`), sampled on a fixed 20x8 grid → 170 samples.
     *
     * Discriminating: an open menu fills it almost entirely (measured 170/170), while the same panel
     * with the menu closed scores ~62/170 (the actions row and the first tree rows also live here).
     * Use [MENU_OPEN_MIN] / [MENU_CLOSED_MAX] rather than raw numbers so cosmetic drift does not
     * break every call site.
     */
    fun menuRegionDiffCount(png: Path): Int = regionDiffCount(png, 16..150 step 8, 58..112 step 6)

    /** Above this, a menu card is definitely painted over the region. */
    const val MENU_OPEN_MIN = 140

    /** Below this, no menu card is painted (only the normal actions row / tree rows show through). */
    const val MENU_CLOSED_MAX = 120

    /**
     * The header row: the root-name Dropdown anchor (box outline + label). Non-zero only if the
     * panel actually composed and painted — the check that makes "the Explorer rendered" assertable
     * instead of merely assuming it from the state the test just set. 20x5 grid → 100 samples.
     */
    fun headerRegionDiffCount(png: Path): Int = regionDiffCount(png, 8..84 step 4, 28..44 step 4)

    /**
     * Pixels in the Explorer's action row that differ between two captures taken at different panel
     * widths.
     *
     * The row is packed left with fixed-width children and fixed gaps, so at any width that actually
     * fits it, every control lands on identical coordinates and this is **exactly zero**. When the
     * width is too small the row does not simply clip at the panel edge — Jewel squeezes the last
     * button's inner width and its label truncates ("Discard" → "Discar"), which shifts pixels and
     * makes this non-zero. That is the failure a bounding-box check misses, which is why the shipped
     * default width is verified by comparison against a known-good wide capture rather than by a
     * "did something paint here" count.
     */
    fun actionRowMismatch(a: Path, b: Path): Int {
        val imgA = ImageIO.read(awaitDecodable(a).toFile())
        val imgB = ImageIO.read(awaitDecodable(b).toFile())
        return (56..78).sumOf { y ->
            (4..255).count { x -> imgA.getRGB(x, y) != imgB.getRGB(x, y) }
        }
    }
}
