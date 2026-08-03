# Trimming `src/clientTest/` to seven user stories

**Date:** 2026-08-03
**Status:** approved, ready for planning

## Problem

`runClientTest` takes too long. The cost is dominated by per-test work — scene
mount/unmount cycles, `waitTick` loops, screenshot captures — not by the fixed client
boot. The suite holds 82 test cases across 19 files, and most of them do not need a
running Minecraft client at all. They sit in `src/clientTest/` because of a classpath
wall, not because of what they test.

## Goal

Every spec that survives in `src/clientTest/` covers exactly one user story. Everything
else either moves to `src/test/` as a JVM unit test or is discarded as duplicate
coverage.

Scope is `src/clientTest/` only. `src/gametest/` is left alone.

## Current inventory

82 cases across 19 files, classified by what each case actually needs:

| Category | Files | Cases |
|---|---|---|
| Already plain `StringSpec`, no render context | `DockInsetsSpec`, `DockLifecycleSpec` | 8 |
| Seam-injected; client never touched | `RootPickerSpec`, `ExplorerStateStoreSpec`, `ModConfigSpec`, `GlfwKeyMapSpec`, `StructureExplorerSpec`, `ExplorerLifecycleSpec` | 23 |
| Compose/Jewel state, no rendering | `ExplorerTreeStateSpec` | 17 |
| Payload-only prefixes inside UI specs | `ExplorerContextMenuSpec` (first 5), `DockInputSpec` (button map, `syncDockViewport`) | 7 |
| Genuinely needs a live client | the rest | 27 |

`DockInsetsSpec` and `DockLifecycleSpec` are the clearest cases: their own doc comments
already say they touch no render context, yet they pay a full client boot.
`RootPickerSpec` stubs every seam it has (`picker`, `runner`, `sender`, `persist`) and
touches nothing live.

**55 of 82 cases leave the client suite. 27 stay, consolidating to 7 stories.**

## The build change that unblocks it

`build.gradle.kts` currently gives `src/test` only `main` + `testSupport` output, so
anything under `com.breadmoirai.garnet.editor.ui` or `config.ExplorerStateStore` cannot
compile there. Two edits:

1. Add the client source set to the test classpath, mirroring what `clientTest` already
   does:

   ```kotlin
   sourceSets.named("test") {
       compileClasspath += sourceSets["client"].output +
           sourceSets["client"].compileClasspath + testSupportSourceSet.output
       runtimeClasspath += sourceSets["client"].output +
           sourceSets["client"].runtimeClasspath + testSupportSourceSet.output
   }
   ```

2. Drop `"compileTestKotlin"` from the Compose-compiler-plugin strip list. This is
   required, not optional: `Panel.content` is `@Composable (Panel) -> Unit`
   (`src/client/kotlin/com/breadmoirai/garnet/ui/dock/Panel.kt:12`), and
   `DockLifecycleSpec` constructs `Panel` instances with a trailing lambda. Without the
   plugin that lambda will not compile. The plugin's `VersionChecker` passes because edit
   1 puts `runtime-desktop` on the test compile classpath.

`main`, `gametest` and `testSupport` keep the strip, so Compose stays off the server
jar's classpath — the property that comment protects is preserved.

### Resulting decision rule

The rule in `docs/gametest/unit-vs-gametest-split.md` becomes:

> Needs a live `Minecraft` instance, a GL context, or GLFW → `src/clientTest/`.
> Everything else, including Compose state and `@Composable` code → `src/test/`.

This is sharper than today's "requires a real client", which has been read loosely enough
to pull pure Gson round-trips into a client boot.

## Destination layout

Moved specs land under `src/test/kotlin/com/breadmoirai/garnet/client/`, mirroring the
client package tree (`client/config/`, `client/editor/ui/`, `client/ui/dock/`). They keep
their names and bodies close to verbatim. The mechanical edits are:

- `ClientSpec` → `StringSpec` / `FunSpec`
- `runOnClient { ... }` unwrapped — there is no client thread to hop to, and the snapshot
  state these specs mutate is plain JVM state.

## The seven survivors

| File | The one story |
|---|---|
| `ViewportSpec` | Shrink the viewport, composite the world into the sub-rect, verify a click picks the same block it picked unshrunk and the cursor maps through the content-rect offset |
| `DockRenderSpec` | Open Left + Bottom, render, probe that both regions painted and the world sits centered between them |
| `DockInputSpec` | A pointer click, an arrow key, a typed char, and an ESC all route into a focused Compose widget and back out |
| `JewelExplorerSpec` | Render the tree, click a row to select, arrow-navigate, open the kebab, ESC it closed — then unmount/remount and confirm no popup survives |
| `ExplorerUiSpec` | Right-click a nested folder → New Folder → type → Enter → the payload reaches `ExplorerActions.sender` |
| `CursorFocusSpec` | The keybind toggles `MouseHandler` grab state |
| `RunGarnetSpecSmokeTest` | Harness smoke — unchanged |

`ViewportCompositeSpec`, `ViewportPickingSpec` and `ViewportCursorMappingSpec` merge into
`ViewportSpec`. `DockInsetsSpec` and `DockLifecycleSpec` leave for `src/test`.
`ExplorerContextMenuSpec` becomes `ExplorerUiSpec` after its payload-only prefix moves out.

`ProjectExplorerSpec` is deleted outright. Its two cases are near-duplicates — both assert
"the header painted, and no menu leaked" — and its screenshot capture (a 6-second polling
deadline plus 12 ticks, twice over) is among the most expensive things in the suite. The
leaked-popup regression it guards is preserved as an assertion inside `JewelExplorerSpec`'s
kebab story.

Consolidation is deliberately aggressive: one story per file. The accepted cost is that a
red test names a file rather than a behavior.

## Risk and verification

**The one real unknown** is whether Jewel's `TreeState` / `SelectableLazyListState`
construct on a headless JVM without pulling a Skiko native. `LazyListState` is
`compose.foundation`, which suggests they will, but this is a build-classpath fact that
has to be observed rather than reasoned to.

Task 1 of the plan is therefore a spike: apply the build change, move
`ExplorerTreeStateSpec` alone, run `:26.2:test`. If a native loads, the fallback is that
the 17 tree-state cases stay in `clientTest` as one consolidated story while the other 38
still move. The trim delivers value either way.

Verification for the whole change:

1. Five-sourceset compile: `clientClasses classes gametestClasses clientTestClasses testClasses`
2. `:26.2:test` green (Kotest results read from the XML report — `--tests` filters do not
   work with Kotest)
3. One `runClientTest` confirming the 7 stories pass
4. `:26.2:runGameTest` unchanged and still green, to prove the build edit did not disturb
   the gametest classpath

## Documentation

Part of this work, not a follow-up:

- `docs/gametest/unit-vs-gametest-split.md` — rewrite the decision rule and the
  "where the contracts actually live" lists to match the new split
- `docs/gametest/INDEX.md` — update summaries that reference the moved specs
- `docs/gametest/client-test-threading.md` — note that `ClientSpec` / `runOnClient` are
  now reserved for specs that genuinely drive the client
- Any doc citing a moved spec by path
