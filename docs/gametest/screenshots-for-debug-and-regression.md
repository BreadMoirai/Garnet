---
title: Screenshots for client-test debugging and visual regression
tags: [testing, client-gametest, screenshots, debugging, regression]
summary: How to capture client screenshots from a Kotest spec, and the optional Fabric comparison API for visual regression.
---

# Screenshots for debugging and visual regression

Fabric's client-gametest API supports two screenshot use cases. We currently use the first; the second is documented here for future application.

## Two use cases

1. **Diagnostic capture.** Save the live client state to a PNG to inspect after the run. Useful for "I expected screen X but the test saw Y" debugging, or as evidence a UI element rendered as intended. We use this today in UC-NET-04.a (proof the `ConfirmScreen` opens with the right labels — see `versions/26.1/run/screenshots/0000_uc-net-04a-confirm-screen.png`).
2. **Visual regression.** Compare a fresh capture to a committed baseline. Mismatch (beyond a fuzzy threshold) fails the test. Useful for catching unintended UI changes when refactoring screens or upgrading MC versions. Not yet wired into any test, but the API is ready when we want it.

## Capturing for diagnostics (today)

`ClientNetworkTestSupport.kt` provides:

```kotlin
fun takeClientScreenshot(name: String): java.nio.file.Path
```

Call it from any `ClientSpec` test body. Internally hops to the Fabric test thread via `FabricTestThreadPump`, where `ctx.takeScreenshot(name)` is legal. Returns the file path under `versions/26.1/run/screenshots/`. Files are numbered `NNNN_<name>.png` in capture order.

Use during UI work:

```kotlin
takeClientScreenshot("editor-after-add-marker")
takeClientScreenshot("editor-after-discard")
```

Use during debugging — drop a screenshot before each assertion that operates on `mc.screen` to see exactly what state the client was in when the test ran. Especially valuable when a `waitForClientScreen` times out: take a screenshot just before to see what screen is actually up.

The screenshots directory is gitignored — these are run-time artifacts, not committed assets.

## Visual regression (when we want it)

Fabric's `ClientGameTestContext.takeScreenshot(TestScreenshotOptions)` accepts options that compare the capture against a committed template image. The full API:

```kotlin
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotComparisonOptions

// Inside a test, on the Fabric test thread (use the pump):
FabricTestThreadPump.runOnTestThread { ctx ->
    ctx.takeScreenshot(
        TestScreenshotComparisonOptions.of("confirm-screen-overwrite-prompt")
    )
}
```

### Where templates live

`src/clientTest/resources/templates/<name>.png`. Fabric resolves the path via the `fabric.client.gametest.testModResourcesPath` system property (Loom sets this for us).

Template images must be **fully opaque** — Fabric throws if any pixel is transparent.

### First-run baseline generation

If the template file does NOT exist when the test runs:

- The captured screenshot is saved to `src/clientTest/resources/templates/<name>.png`.
- The test **fails** with a message indicating a new baseline was written.
- Commit the generated file, re-run; the test now compares and passes.

This is the standard baseline-update workflow: delete the template, re-run, commit.

### Comparison algorithm

Three algorithms via `TestScreenshotComparisonAlgorithm`:

- **`defaultAlgorithm()`** — fuzzy comparison, 0.5% threshold. Recommended for normal use; tolerates the small per-frame and per-GPU pixel variation that's normal in a real render pipeline.
- **`meanSquaredDifference(threshold)`** — fuzzy with a custom threshold. Use when 0.5% is too loose or too tight.
- **`exact()`** — byte-equal match. Use only when you genuinely need exact pixels (rare; will flake on minor render variation).

### On failure

The test fails and a diff image is saved alongside the original capture in `versions/26.1/run/screenshots/`. Open both to see what changed.

### Save-and-compare in one call

`TestScreenshotComparisonOptions#save()` writes the captured frame to disk **and** compares against the template. Useful for keeping a record of every comparison frame, not just the failing ones.

## When to reach for which

| Situation | Use |
|---|---|
| Investigating a flaky UI test | `takeClientScreenshot` before/after the suspect step. Inspect after the run. |
| Verifying a screen opens correctly (one-off) | `takeClientScreenshot` as a single proof artifact. |
| Pinning a screen's appearance against regression | `TestScreenshotComparisonOptions.of(name)` with a committed template. |
| Refactoring a screen and want to confirm no visual change | Same as above; run before and after, no template change → no visual delta. |

## Caveats

- **GPU / driver variation.** Even with `defaultAlgorithm()`'s 0.5% tolerance, baselines from CI machines may differ slightly from a developer's local capture. If we adopt regression broadly, baselines should be generated and committed from a known-good environment (typically CI).
- **Font rendering.** Locale and font fallbacks can shift text by sub-pixels. Stick with `Component.literal(...)` over `Component.translatable(...)` in production text that's tested by screenshot.
- **Frame timing.** A regression test should run after the screen has fully laid out. Use `waitForClientScreen(class)` first; consider an additional `waitClientTicks(1-2)` if a screen has post-init animations.
- **`takeScreenshot` is a Fabric-test-thread call.** From a `ClientSpec` Kotest test body, use `FabricTestThreadPump.runOnTestThread { ctx -> ctx.takeScreenshot(...) }`. Our existing `takeClientScreenshot` helper wraps this.

## See also

- `docs/gametest/client-test-threading.md` — pump and `onClient`/`runOnClient` mechanics.
- `src/clientTest/.../ClientNetworkTestSupport.kt` — `takeClientScreenshot` implementation.
- Fabric API source: `net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotComparisonOptions`.
