# Jewel Widget Layer Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the dock Project Explorer's hand-rolled Compose tree, menu, and text field with JetBrains Jewel widgets, and wire the keyboard path that makes them usable.

**Architecture:** Move the Compose/skiko pin down to the triple Jewel resolves against (Compose 1.11.0 stable + skiko 0.144.6), add Jewel plus the IntelliJ icon artwork artifact, then rebuild `ProjectExplorerPanel` on Jewel's `LazyTree`/`Dropdown`/`TextField` under one `IntUiTheme`. Jewel's `TreeState` becomes the single source of truth for expand/selection, keyed by `/`-joined path strings. `DockInputRouter` gains GLFW→Compose key and character forwarding, which both enables tree navigation and fixes a text field that could never receive input.

**Tech Stack:** Kotlin 2.3.20, Compose Multiplatform 1.11.0 (desktop), skiko 0.144.6, JetBrains Jewel 0.39.1-262.9437.29, Fabric/Loom/Stonecutter (MC 26.2), Kotest (clientTest source set), Mixin.

## Global Constraints

- **Gradle is invoked via cmd.exe with no `./` prefix:** `cmd.exe /c "gradlew.bat ..."`. Stonecutter task paths are prefixed `:26.2:`.
- **Full compile gate (all five source sets):** `cmd.exe /c "gradlew.bat :26.2:clientClasses :26.2:classes :26.2:gametestClasses :26.2:clientTestClasses :26.2:testClasses"`
- **Client test run:** `cmd.exe /c "gradlew.bat :26.2:runClientTest"`. Pass = `All N tests passed` in the log and no `disabling Compose surface` kill line.
- **Gradle `--tests` filtering does not work with Kotest here.** Scope by editing `ClientTestSentinel`'s `specs = listOf(...)`; read results from the log or `build/reports/garnet/clientTest`.
- **Every new Kotest client spec MUST be registered** in `ClientTestSentinel`'s explicit `specs` list. Autoscan is off — unregistered specs silently do not run.
- **All Compose/Jewel/icon dependencies go on `clientImplementation`,** never plain `implementation`. Compose must stay out of the server jar; the compiler-plugin strip on `main`/`test`/`gametest` depends on this.
- **The dock input path stays OFF by default.** Everything remains behind `DockInputRouter.captured` (`DockState.focusedRegion != null`); uncaptured input must stay byte-for-byte vanilla.
- **Commit directly to `main`.** No feature branch. No `Co-Authored-By` or "Generated with Claude Code" trailer — commits are attributed to the user only. Do not push.
- **Screenshots** land in `versions/26.2/run/screenshots/`; capture via `runOnClient { ViewportState.compositeCaptureRequest = <absolute Path> }` then poll for the file.
- **Do not merge or cherry-pick the `spike/jewel` branch.** It carries a throwaway spec and a temporarily-scoped sentinel.

---

## File Structure

**Created:**
- `src/client/kotlin/com/breadmoirai/garnet/client/ide/ExplorerTreeState.kt` — owns the single hoisted Jewel `TreeState`, converts `FolderNode` → `Tree<FileTreeNode>`, and exposes path-keyed selection/expansion accessors.
- `src/client/kotlin/com/breadmoirai/garnet/client/ui/compose/input/GlfwKeyMap.kt` — GLFW key code → Compose `Key` table, and the GLFW-mods → modifier-flags decode.
- `src/clientTest/kotlin/com/breadmoirai/garnet/test/ExplorerTreeStateSpec.kt` — headless tests for the tree-state model (no rendering).
- `src/clientTest/kotlin/com/breadmoirai/garnet/test/JewelExplorerSpec.kt` — visual/interaction verification (chevrons paint, click selects, arrow-key navigates, dropdown opens in-scene).
- `docs/ui/jewel-widget-layer.md` — the durable Jewel reference on `main`.

**Modified:**
- `settings.gradle.kts:2-9` — add the JetBrains repository.
- `build.gradle.kts:187,203-205` — re-pin skiko + Compose, add Jewel + icons.
- `src/client/kotlin/com/breadmoirai/garnet/client/ui/compose/ComposeSurface.kt:109` — stale skiko version in the log string.
- `src/client/kotlin/com/breadmoirai/garnet/client/ui/compose/input/DockInputRouter.kt:58-68` — key forwarding + new `onGlfwChar`.
- `src/client/java/com/breadmoirai/garnet/mixin/client/KeyboardHandlerMixin.java` — pass modifiers; add `charTyped` injection.
- `src/client/kotlin/com/breadmoirai/garnet/client/ide/ProjectTreeState.kt` — strip `expanded`, `selectedPath`, `select`, `toggleExpanded`, `selectedHasUnsaved`.
- `src/client/kotlin/com/breadmoirai/garnet/client/ide/ProjectExplorerPanel.kt` — full Jewel conversion; delete `TreeNode` and `RootMenu`.
- `src/client/kotlin/com/breadmoirai/garnet/client/ide/RootPickerController.kt` — delete `menuOpen`/`toggleMenu`/`closeMenu`.
- `src/clientTest/.../ClientTestSentinel.kt`, `ProjectExplorerSpec.kt`, `StructureExplorerSpec.kt`, `RootPickerSpec.kt` — follow the state-model change.
- Docs per Task 7.

---

### Task 1: Re-pin the Compose/skiko triple and add Jewel + icons

Nothing else changes in this task. The point is to prove the dependency move is inert before any UI churn sits on top of it.

**Files:**
- Modify: `settings.gradle.kts:2-9`
- Modify: `build.gradle.kts:187,203-205`
- Modify: `src/client/kotlin/com/breadmoirai/garnet/client/ui/compose/ComposeSurface.kt:109`

**Interfaces:**
- Consumes: nothing.
- Produces: the `org.jetbrains.jewel.*` and `org.jetbrains.jewel.ui.icons.AllIconsKeys` packages on the `client` and `clientTest` compile classpaths.

**Background — why these exact versions.** The Compose pin is load-bearing on skiko ABI: Compose's transitive skiko must exactly match the bundled `skiko-awt-runtime-windows-x64` native, so the three move together. Jewel `0.39.1-262.9437.29` depends on Compose `1.11.0`, whose transitive skiko is `0.144.6`. This moves the project *down* one minor, from a beta to stable.

**Background — why a separate icons artifact.** `jewel-ui` ships the full `AllIconsKeys` catalog but **zero SVGs**; `jewel-int-ui-standalone` ships only 45 (checkbox/radio/theme). Its transitive `com.jetbrains.intellij.platform:icons-api`/`-api-rendering`/`-impl` are rendering machinery and also contain **zero SVGs**. The artwork lives only in `com.jetbrains.intellij.platform:icons`, which is **not on Maven Central** (404 at every version) — hence the extra repository.

**Background — the deliberate version skew.** Jewel wants icon build `262.9437.29`, but the JetBrains repo publishes only the `262.8665.x` line (latest `262.8665.369`); `icons:262.9437.29` returns 404 on both releases and snapshots. Use `262.8665.369`. Icon resource paths are stable within the 262 branch, and the required paths were verified present in that jar: `expui/general/chevronRight.svg`, `expui/general/chevronDown.svg`, `expui/nodes/folder.svg`, `expui/general/refresh.svg`. Task 6's screenshot is the check that catches a missing icon.

- [ ] **Step 1: Add the JetBrains repository**

In `settings.gradle.kts`, inside the existing `repositories { }` block (currently lines 2-9), add after the `maven.terraformersmc.com` line:

```kotlin
        // IntelliJ platform icon ARTWORK (com.jetbrains.intellij.platform:icons). Jewel's jewel-ui
        // ships the AllIconsKeys catalog but no SVGs, and the artwork artifact is not published to
        // Maven Central — only here. Jewel's own transitive icons-api/-impl DO come from Central.
        maven("https://www.jetbrains.com/intellij-repository/releases") { name = "IntelliJ Repository" }
```

- [ ] **Step 2: Re-pin skiko**

In `build.gradle.kts`, replace line 187 and update the comment block above it. Change:

```kotlin
    "clientImplementation"("org.jetbrains.skiko:skiko-awt-runtime-windows-x64:0.150.1")
```

to:

```kotlin
    // Re-pinned 0.150.1 -> 0.144.6: this must EXACTLY match the transitive skiko of the Compose line
    // below, which moved to 1.11.0 to match Jewel. A mismatch risks a skiko version-guard failure or
    // a native ABI break. See docs/ui/jewel-widget-layer.md.
    "clientImplementation"("org.jetbrains.skiko:skiko-awt-runtime-windows-x64:0.144.6")
```

Also update the stale sentence in the comment block immediately above it — replace `Skiko 0.150.1 is the desktop-GL build Compose Multiplatform 1.12.x targets.` with `Skiko 0.144.6 is the desktop-GL build Compose Multiplatform 1.11.x targets.`

- [ ] **Step 3: Re-pin Compose and add Jewel + icons**

Replace lines 203-205:

```kotlin
    "clientImplementation"("org.jetbrains.compose.runtime:runtime-desktop:1.12.0-beta02")
    "clientImplementation"("org.jetbrains.compose.ui:ui-desktop:1.12.0-beta02")
    "clientImplementation"("org.jetbrains.compose.foundation:foundation-desktop:1.12.0-beta02")
```

with:

```kotlin
    "clientImplementation"("org.jetbrains.compose.runtime:runtime-desktop:1.11.0")
    "clientImplementation"("org.jetbrains.compose.ui:ui-desktop:1.11.0")
    "clientImplementation"("org.jetbrains.compose.foundation:foundation-desktop:1.11.0")

    // JetBrains Jewel — IntelliJ-look Compose widgets; the dock's widget layer. Transitively pulls
    // jewel-ui + jewel-foundation + compose foundation-desktop:1.11.0 + skiko 0.144.6 (matching the
    // pins above), and bundles the Int UI theme + Inter fonts.
    "clientImplementation"("org.jetbrains.jewel:jewel-int-ui-standalone:0.39.1-262.9437.29")

    // IntelliJ component icon ARTWORK. jewel-ui ships the AllIconsKeys catalog but zero SVGs, and
    // IconKey resolves icons as classloader resource paths (there is no Painter/ImageVector seam),
    // so without this the tree chevrons render as magenta missing-resource placeholders.
    // Version skew is deliberate: Jewel is built against icon build 262.9437.29, but only the
    // 262.8665.x line is published; paths are stable within the 262 branch.
    "clientImplementation"("com.jetbrains.intellij.platform:icons:262.8665.369")
```

Also update the stale Compose rationale in the comment block above — replace `We pin 1.12.0-beta02 because its transitive skiko-awt is 0.150.1` with `We pin 1.11.0 (stable) because Jewel 0.39.1-262.9437.29 is built against it, and its transitive skiko-awt is 0.144.6`.

- [ ] **Step 4: Fix the stale skiko version in the log string**

In `ComposeSurface.kt` line 109, change:

```kotlin
            logger.info("[compose-spike] Skiko native loaded (skiko 0.150.1, desktop-GL)")
```

to:

```kotlin
            logger.info("[compose-spike] Skiko native loaded (skiko 0.144.6, desktop-GL)")
```

- [ ] **Step 5: Verify the dependencies resolve and Jewel is on the classpath**

Run: `cmd.exe /c "gradlew.bat :26.2:dependencies --configuration clientRuntimeClasspath"`

Expected: `org.jetbrains.jewel:jewel-int-ui-standalone:0.39.1-262.9437.29`, `com.jetbrains.intellij.platform:icons:262.8665.369`, `org.jetbrains.compose.*:1.11.0`, and `org.jetbrains.skiko:skiko-awt-runtime-windows-x64:0.144.6` all appear. **No `FAILED` lines.**

If `icons:262.8665.369` fails to resolve, list available versions with:
`curl -sL https://www.jetbrains.com/intellij-repository/releases/com/jetbrains/intellij/platform/icons/maven-metadata.xml | grep -oE '<version>26[0-9]\.[^<]*' | tail -20`
and pick the newest `262.*`.

- [ ] **Step 6: Verify all five source sets compile**

Run: `cmd.exe /c "gradlew.bat :26.2:clientClasses :26.2:classes :26.2:gametestClasses :26.2:clientTestClasses :26.2:testClasses"`

Expected: `BUILD SUCCESSFUL`. The spike verified zero API drift across 1.11↔1.12 for `ComposeSceneHost` (`ImageComposeScene`, `Density`, `sendPointerEvent`, `sendKeyEvent`) and `ComposeSurface` (`DirectContext.makeGL`, `BackendRenderTarget`, `Surface.makeFromBackendRenderTarget`), so no source changes should be needed here. **If something does fail to compile, fix it in this task** — that is exactly the risk this task exists to isolate.

- [ ] **Step 7: Verify the existing client tests still pass on the new pin**

Run: `cmd.exe /c "gradlew.bat :26.2:runClientTest"`

Expected: `All N tests passed`, and **no** `disabling Compose surface` line in the log. `DockRenderSpec` and `ProjectExplorerSpec` must still pass — the dock renders identically on the new triple. Confirm the log shows `Skiko native loaded (skiko 0.144.6, desktop-GL)`.

- [ ] **Step 8: Commit**

```bash
git add settings.gradle.kts build.gradle.kts src/client/kotlin/com/breadmoirai/garnet/client/ui/compose/ComposeSurface.kt
git commit -m "build(compose): re-pin to Compose 1.11.0 + skiko 0.144.6, add Jewel and IntelliJ icons

Moves the ABI-load-bearing triple together so JetBrains Jewel resolves:
compose 1.12.0-beta02 -> 1.11.0 (stable), skiko 0.150.1 -> 0.144.6.

Adds jewel-int-ui-standalone plus the IntelliJ icon artwork artifact --
jewel-ui ships the AllIconsKeys catalog but zero SVGs, and IconKey
resolves icons as classloader resource paths, so without the artwork
component icons render as magenta placeholders. The artwork is not on
Maven Central, hence the JetBrains repository.

No behavior change; dock renders identically on the new triple."
```

---

### Task 2: GLFW → Compose key mapping table

Pure, headless, no MC dependency — so it is testable on its own before any wiring.

**Files:**
- Create: `src/client/kotlin/com/breadmoirai/garnet/client/ui/compose/input/GlfwKeyMap.kt`
- Create/Test: `src/clientTest/kotlin/com/breadmoirai/garnet/test/GlfwKeyMapSpec.kt`
- Modify: `src/clientTest/kotlin/com/breadmoirai/garnet/test/ClientTestSentinel.kt:70-83`

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces:
  - `fun glfwKeyToComposeKey(glfwKey: Int): Key?` — `null` for unmapped keys.
  - `object GlfwMods { fun shift(mods: Int): Boolean; fun ctrl(mods: Int): Boolean; fun alt(mods: Int): Boolean; fun meta(mods: Int): Boolean }`

- [ ] **Step 1: Write the failing test**

Create `src/clientTest/kotlin/com/breadmoirai/garnet/test/GlfwKeyMapSpec.kt`:

```kotlin
package com.breadmoirai.garnet.test

import androidx.compose.ui.input.key.Key
import com.breadmoirai.garnet.client.ui.compose.input.GlfwMods
import com.breadmoirai.garnet.client.ui.compose.input.glfwKeyToComposeKey
import com.breadmoirai.garnet.testing.ClientSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.lwjgl.glfw.GLFW

class GlfwKeyMapSpec : ClientSpec({

    test("maps the navigation keys the tree needs") {
        glfwKeyToComposeKey(GLFW.GLFW_KEY_DOWN) shouldBe Key.DirectionDown
        glfwKeyToComposeKey(GLFW.GLFW_KEY_UP) shouldBe Key.DirectionUp
        glfwKeyToComposeKey(GLFW.GLFW_KEY_LEFT) shouldBe Key.DirectionLeft
        glfwKeyToComposeKey(GLFW.GLFW_KEY_RIGHT) shouldBe Key.DirectionRight
        glfwKeyToComposeKey(GLFW.GLFW_KEY_ENTER) shouldBe Key.Enter
    }

    test("maps the editing keys the text field needs") {
        glfwKeyToComposeKey(GLFW.GLFW_KEY_BACKSPACE) shouldBe Key.Backspace
        glfwKeyToComposeKey(GLFW.GLFW_KEY_DELETE) shouldBe Key.Delete
        glfwKeyToComposeKey(GLFW.GLFW_KEY_HOME) shouldBe Key.MoveHome
        glfwKeyToComposeKey(GLFW.GLFW_KEY_END) shouldBe Key.MoveEnd
        glfwKeyToComposeKey(GLFW.GLFW_KEY_A) shouldBe Key.A
    }

    test("returns null for an unmapped key rather than a wrong one") {
        glfwKeyToComposeKey(GLFW.GLFW_KEY_F24).shouldBeNull()
        glfwKeyToComposeKey(-1).shouldBeNull()
    }

    test("decodes GLFW modifier bits") {
        GlfwMods.shift(GLFW.GLFW_MOD_SHIFT).shouldBeTrue()
        GlfwMods.ctrl(GLFW.GLFW_MOD_CONTROL).shouldBeTrue()
        GlfwMods.alt(GLFW.GLFW_MOD_ALT).shouldBeTrue()
        GlfwMods.meta(GLFW.GLFW_MOD_SUPER).shouldBeTrue()
        GlfwMods.shift(GLFW.GLFW_MOD_CONTROL).shouldBeFalse()
        GlfwMods.ctrl(GLFW.GLFW_MOD_SHIFT or GLFW.GLFW_MOD_CONTROL).shouldBeTrue()
    }
})
```

- [ ] **Step 2: Register the spec in the sentinel**

In `ClientTestSentinel.kt`, add `GlfwKeyMapSpec::class,` to the `specs = listOf(...)` block (after `DockInputSpec::class,`). Autoscan is off — without this the spec silently does not run.

- [ ] **Step 3: Run the test to verify it fails**

Run: `cmd.exe /c "gradlew.bat :26.2:clientTestClasses"`
Expected: FAIL — `Unresolved reference: glfwKeyToComposeKey`.

- [ ] **Step 4: Write the implementation**

Create `src/client/kotlin/com/breadmoirai/garnet/client/ui/compose/input/GlfwKeyMap.kt`:

```kotlin
package com.breadmoirai.garnet.client.ui.compose.input

import androidx.compose.ui.input.key.Key
import org.lwjgl.glfw.GLFW

/**
 * GLFW key code -> Compose [Key]. MC hands us raw GLFW codes (via `KeyEvent#key()`); Compose's
 * desktop key pipeline wants its own [Key] constants, so the dock needs this table to deliver
 * keystrokes into the scene at all.
 *
 * Deliberately partial: it covers navigation, editing, modifiers, letters and digits — what the
 * Explorer's tree and text field actually consume. Unmapped keys return `null` and are dropped
 * rather than guessed at, because a wrong [Key] would fire the wrong action in a focused widget.
 * Printable text does NOT come through here — it arrives as a codepoint via the GLFW char callback
 * (see [DockInputRouter.onGlfwChar]); this table exists so control keys still work.
 */
fun glfwKeyToComposeKey(glfwKey: Int): Key? = KEY_TABLE[glfwKey]

/** Decodes the GLFW modifier bitfield handed to the key callback. */
object GlfwMods {
    fun shift(mods: Int) = (mods and GLFW.GLFW_MOD_SHIFT) != 0
    fun ctrl(mods: Int) = (mods and GLFW.GLFW_MOD_CONTROL) != 0
    fun alt(mods: Int) = (mods and GLFW.GLFW_MOD_ALT) != 0
    fun meta(mods: Int) = (mods and GLFW.GLFW_MOD_SUPER) != 0
}

private val KEY_TABLE: Map<Int, Key> = buildMap {
    // Navigation
    put(GLFW.GLFW_KEY_UP, Key.DirectionUp)
    put(GLFW.GLFW_KEY_DOWN, Key.DirectionDown)
    put(GLFW.GLFW_KEY_LEFT, Key.DirectionLeft)
    put(GLFW.GLFW_KEY_RIGHT, Key.DirectionRight)
    put(GLFW.GLFW_KEY_HOME, Key.MoveHome)
    put(GLFW.GLFW_KEY_END, Key.MoveEnd)
    put(GLFW.GLFW_KEY_PAGE_UP, Key.PageUp)
    put(GLFW.GLFW_KEY_PAGE_DOWN, Key.PageDown)

    // Editing / activation
    put(GLFW.GLFW_KEY_ENTER, Key.Enter)
    put(GLFW.GLFW_KEY_KP_ENTER, Key.NumPadEnter)
    put(GLFW.GLFW_KEY_TAB, Key.Tab)
    put(GLFW.GLFW_KEY_BACKSPACE, Key.Backspace)
    put(GLFW.GLFW_KEY_DELETE, Key.Delete)
    put(GLFW.GLFW_KEY_INSERT, Key.Insert)
    put(GLFW.GLFW_KEY_SPACE, Key.Spacebar)
    put(GLFW.GLFW_KEY_ESCAPE, Key.Escape)

    // Modifiers (delivered as their own key events, and widgets track them)
    put(GLFW.GLFW_KEY_LEFT_SHIFT, Key.ShiftLeft)
    put(GLFW.GLFW_KEY_RIGHT_SHIFT, Key.ShiftRight)
    put(GLFW.GLFW_KEY_LEFT_CONTROL, Key.CtrlLeft)
    put(GLFW.GLFW_KEY_RIGHT_CONTROL, Key.CtrlRight)
    put(GLFW.GLFW_KEY_LEFT_ALT, Key.AltLeft)
    put(GLFW.GLFW_KEY_RIGHT_ALT, Key.AltRight)
    put(GLFW.GLFW_KEY_LEFT_SUPER, Key.MetaLeft)
    put(GLFW.GLFW_KEY_RIGHT_SUPER, Key.MetaRight)

    // Letters: GLFW_KEY_A..GLFW_KEY_Z are contiguous and ASCII-aligned, as are Compose's Key.A..Key.Z.
    val letters = listOf(
        Key.A, Key.B, Key.C, Key.D, Key.E, Key.F, Key.G, Key.H, Key.I, Key.J, Key.K, Key.L, Key.M,
        Key.N, Key.O, Key.P, Key.Q, Key.R, Key.S, Key.T, Key.U, Key.V, Key.W, Key.X, Key.Y, Key.Z,
    )
    letters.forEachIndexed { i, key -> put(GLFW.GLFW_KEY_A + i, key) }

    // Digits, likewise contiguous.
    val digits = listOf(
        Key.Zero, Key.One, Key.Two, Key.Three, Key.Four,
        Key.Five, Key.Six, Key.Seven, Key.Eight, Key.Nine,
    )
    digits.forEachIndexed { i, key -> put(GLFW.GLFW_KEY_0 + i, key) }
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `cmd.exe /c "gradlew.bat :26.2:runClientTest"`
Expected: `GlfwKeyMapSpec` — 4 tests passing, in `All N tests passed`.

If a `Key.*` constant does not resolve, check the real name in the Compose jar:
`javap -cp <compose-ui-desktop-1.11.0.jar> androidx.compose.ui.input.key.Key\$Companion | grep -i <name>`

- [ ] **Step 6: Commit**

```bash
git add src/client/kotlin/com/breadmoirai/garnet/client/ui/compose/input/GlfwKeyMap.kt src/clientTest/kotlin/com/breadmoirai/garnet/test/GlfwKeyMapSpec.kt src/clientTest/kotlin/com/breadmoirai/garnet/test/ClientTestSentinel.kt
git commit -m "feat(dock): add GLFW -> Compose key mapping table

Navigation, editing, modifier, letter and digit keys. Unmapped keys
return null and are dropped rather than guessed at, since a wrong Key
would fire the wrong action in a focused widget."
```

---

### Task 3: Forward keys and characters into the Compose scene

Closes the gap documented in `dock-input-routing.md` as "key→Compose delivery is deferred".

**Files:**
- Modify: `src/client/kotlin/com/breadmoirai/garnet/client/ui/compose/input/DockInputRouter.kt:58-68`
- Modify: `src/client/java/com/breadmoirai/garnet/mixin/client/KeyboardHandlerMixin.java`
- Modify: `src/clientTest/kotlin/com/breadmoirai/garnet/test/DockInputSpec.kt`

**Interfaces:**
- Consumes: `glfwKeyToComposeKey(Int): Key?`, `GlfwMods` (Task 2); existing `ComposeSurface.sendKey(KeyEvent)`.
- Produces:
  - `DockInputRouter.onGlfwKey(key: Int, action: Int, mods: Int = 0): Boolean` — **note the new third parameter, defaulted so existing call sites keep compiling.**
  - `DockInputRouter.onGlfwChar(codePoint: Int)`

**Background — constructing a Compose `KeyEvent`.** Compose 1.11 desktop exposes a synthetic factory (verified in `androidx.compose.ui.input.key.KeyEvent_desktopKt`):

```
KeyEvent(key: Key, type: KeyEventType, codePoint: Int, isCtrlPressed, isMetaPressed, isAltPressed, isShiftPressed, nativeEvent)
```

so there is **no need to fabricate a `java.awt.event.KeyEvent`**. If that factory turns out not to be importable from Kotlin (it may carry an opt-in annotation), the fallback is `KeyEvent_desktopKt.toComposeEvent(awtEvent)` with a synthesized AWT event — but try the factory first, and add `@OptIn(ExperimentalComposeUiApi::class)` if the compiler asks for it.

**Background — the ESC contract must not change.** `onGlfwKey` currently returns `true` **only** for a plain ESC press, and the mixin cancels vanilla handling on that basis. Keep that branch first and unchanged; forwarding is additive. `DockInputSpec` already asserts this contract.

- [ ] **Step 1: Write the failing test**

Append these two tests inside the existing `DockInputSpec` body in `src/clientTest/kotlin/com/breadmoirai/garnet/test/DockInputSpec.kt`. Add the imports it needs at the top of the file: `androidx.compose.ui.input.key.Key`, `androidx.compose.ui.input.key.KeyEventType`, `androidx.compose.ui.input.key.key`, `androidx.compose.ui.input.key.type`, `androidx.compose.foundation.focusable`, `androidx.compose.ui.focus.FocusRequester`, `androidx.compose.ui.focus.focusRequester`, `androidx.compose.ui.input.key.onKeyEvent`, `java.util.concurrent.atomic.AtomicReference`.

```kotlin
    test("a non-ESC key press reaches a focused Compose widget in the scene") {
        closeClientScreen(); waitClientTicks(2)
        val seen = AtomicReference<String?>(null)
        val focusRequester = FocusRequester()

        runOnClient { mc ->
            DockState.reset()
            DockState.leftPanels.add(Panel("test.keys", "Keys") {
                Box(
                    Modifier.fillMaxSize()
                        .focusRequester(focusRequester)
                        .focusable()
                        .onKeyEvent { e ->
                            if (e.type == KeyEventType.KeyDown) seen.set(e.key.toString())
                            true
                        },
                )
                LaunchedEffect(Unit) { focusRequester.requestFocus() }
            })
            DockState.setVisible(DockRegion.LEFT, true)
            DockState.setSize(DockRegion.LEFT, 300)
            ViewportState.active = true
            ComposeOverlay.enabled = true
            (mc.window as Any as WindowViewportExt).`garnet$updateScaledFramebuffer`(true)
        }
        waitClientTicks(8)

        runOnClient { DockInputRouter.focus(DockRegion.LEFT) }
        waitClientTicks(2)
        runOnClient { DockInputRouter.onGlfwKey(GLFW.GLFW_KEY_DOWN, GLFW.GLFW_PRESS, 0) }
        waitClientTicks(4)

        seen.get() shouldBe Key.DirectionDown.toString()

        runOnClient { mc ->
            ComposeOverlay.enabled = false; ViewportState.active = false
            DockInputRouter.clearFocus(); DockState.reset()
            (mc.window as Any as WindowViewportExt).`garnet$updateScaledFramebuffer`(true)
        }
        waitClientTicks(6)
    }

    test("onGlfwKey still reports only ESC-press as consumed, and drops nothing when uncaptured") {
        runOnClient { DockInputRouter.focus(DockRegion.LEFT) }
        // Forwarding is additive: a non-ESC key is delivered to the scene but NOT reported consumed,
        // so the mixin's existing cancel-everything-while-captured behavior is unchanged.
        runOnClient { DockInputRouter.onGlfwKey(GLFW.GLFW_KEY_DOWN, GLFW.GLFW_PRESS, 0) } shouldBe false
        DockState.focusedRegion shouldBe DockRegion.LEFT
        runOnClient { DockInputRouter.onGlfwKey(GLFW.GLFW_KEY_ESCAPE, GLFW.GLFW_PRESS, 0) } shouldBe true
        DockState.focusedRegion shouldBe null
        // Uncaptured: never consumed, so vanilla ESC still opens the pause menu.
        runOnClient { DockInputRouter.onGlfwKey(GLFW.GLFW_KEY_ESCAPE, GLFW.GLFW_PRESS, 0) } shouldBe false
    }
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cmd.exe /c "gradlew.bat :26.2:runClientTest"`
Expected: FAIL — the first test's `seen.get()` is `null` (keys never reach the scene today).

- [ ] **Step 3: Implement forwarding in the router**

In `DockInputRouter.kt`, replace the whole `onGlfwKey` function (lines 58-68) with:

```kotlin
    /**
     * Key policy for the dock's key mixin, called for every key while [captured].
     *
     * ESC keeps its original contract: a plain key-**press** of ESC drops focus and returns `true`
     * ("consumed") so the mixin cancels vanilla handling and the pause menu never opens on top of a
     * dropped dock focus. Every other key returns `false` — the mixin cancels it anyway so keystrokes
     * cannot leak into the game, but "not consumed" keeps that behavior exactly as it was.
     *
     * Additively, non-ESC keys are now *delivered* into the Compose scene, which is what makes tree
     * navigation and text fields work at all. Unmapped keys ([glfwKeyToComposeKey] returns null) are
     * dropped rather than guessed at.
     */
    fun onGlfwKey(key: Int, action: Int, mods: Int = 0): Boolean {
        if (!captured) return false
        if (key == GLFW.GLFW_KEY_ESCAPE && action == GLFW.GLFW_PRESS) {
            clearFocus()
            return true
        }
        val composeKey = glfwKeyToComposeKey(key) ?: return false
        val type = when (action) {
            GLFW.GLFW_PRESS, GLFW.GLFW_REPEAT -> KeyEventType.KeyDown
            GLFW.GLFW_RELEASE -> KeyEventType.KeyUp
            else -> return false
        }
        ComposeSurface.sendKey(
            KeyEvent(
                key = composeKey,
                type = type,
                codePoint = 0,
                isCtrlPressed = GlfwMods.ctrl(mods),
                isMetaPressed = GlfwMods.meta(mods),
                isAltPressed = GlfwMods.alt(mods),
                isShiftPressed = GlfwMods.shift(mods),
            ),
        )
        return false
    }

    /**
     * Printable text from the GLFW character callback. Control keys arrive via [onGlfwKey]; actual
     * typed characters only exist here, so a Compose text field needs both paths wired to be usable.
     */
    fun onGlfwChar(codePoint: Int) {
        if (!captured) return
        ComposeSurface.sendKey(
            KeyEvent(key = Key.Unknown, type = KeyEventType.KeyDown, codePoint = codePoint),
        )
    }
```

Add these imports at the top of the file:

```kotlin
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
```

- [ ] **Step 4: Forward modifiers and characters from the mixin**

In `KeyboardHandlerMixin.java`, change the existing forwarding call to pass modifiers, and add the character injection. Replace the body of `garnet$keyPress` and append a second injection:

```java
    @Inject(method = "keyPress(JILnet/minecraft/client/input/KeyEvent;)V", at = @At("HEAD"), cancellable = true)
    private void garnet$keyPress(long window, int action, KeyEvent event, CallbackInfo ci) {
        if (!DockInputRouter.INSTANCE.getCaptured()) return;
        // drops focus on ESC-press; otherwise delivers the key into the Compose scene
        DockInputRouter.INSTANCE.onGlfwKey(event.key(), action, event.modifiers());
        ci.cancel();
    }

    /**
     * Printable characters. Without this, a focused Compose text field can never receive text —
     * the key callback above carries key codes, not typed characters.
     *
     * <p>MC 26.2 target: {@code charTyped(long, net.minecraft.client.input.CharacterEvent)}, where
     * {@code CharacterEvent} is a record wrapping a single {@code int codepoint}.
     */
    @Inject(method = "charTyped(JLnet/minecraft/client/input/CharacterEvent;)V", at = @At("HEAD"), cancellable = true)
    private void garnet$charTyped(long window, CharacterEvent event, CallbackInfo ci) {
        if (!DockInputRouter.INSTANCE.getCaptured()) return;
        DockInputRouter.INSTANCE.onGlfwChar(event.codepoint());
        ci.cancel();
    }
```

Add the import `net.minecraft.client.input.CharacterEvent;` alongside the existing `net.minecraft.client.input.KeyEvent;`.

A wrong `@Inject` descriptor fails silently (mixin does not apply) or crashes at class-load, so if `charTyped` does not apply, re-check the descriptor against the decompiled source:
`jar xf .gradle/loom-cache/minecraftMaven/net/minecraft/minecraft-clientOnly-*/26.2/*-sources.jar net/minecraft/client/KeyboardHandler.java`

- [ ] **Step 5: Run the tests to verify they pass**

Run: `cmd.exe /c "gradlew.bat :26.2:runClientTest"`
Expected: `All N tests passed`, including both new `DockInputSpec` cases and the three pre-existing ones (ESC policy, pointer click, `syncDockViewport`).

- [ ] **Step 6: Commit**

```bash
git add src/client/kotlin/com/breadmoirai/garnet/client/ui/compose/input/DockInputRouter.kt src/client/java/com/breadmoirai/garnet/mixin/client/KeyboardHandlerMixin.java src/clientTest/kotlin/com/breadmoirai/garnet/test/DockInputSpec.kt
git commit -m "feat(dock): forward GLFW keys and characters into the Compose scene

Keys never reached the embedded scene before -- the router only ever
intercepted ESC -- so no Compose widget could take keyboard input, and
the Explorer's text field was unusable by construction.

Adds key delivery (with modifiers) plus a charTyped injection for
printable text. The ESC contract is unchanged: still the only key
reported consumed, so vanilla cancel behavior is untouched."
```

---

### Task 4: Jewel-authoritative Explorer tree state

Swaps the state model with **no UI change yet**, so the model is proven before the panel is rewritten on top of it.

**Files:**
- Create: `src/client/kotlin/com/breadmoirai/garnet/client/ide/ExplorerTreeState.kt`
- Modify: `src/client/kotlin/com/breadmoirai/garnet/client/ide/ProjectTreeState.kt`
- Modify: `src/client/kotlin/com/breadmoirai/garnet/client/ide/ProjectExplorerPanel.kt` (mechanical call-site updates only)
- Create/Test: `src/clientTest/kotlin/com/breadmoirai/garnet/test/ExplorerTreeStateSpec.kt`
- Modify: `src/clientTest/.../ClientTestSentinel.kt`, `ProjectExplorerSpec.kt`, `StructureExplorerSpec.kt`

**Interfaces:**
- Consumes: `ProjectTreeState.snapshot` (unchanged); `FolderNode`/`FileNode`/`FileTreeNode`/`resolve` from `com.breadmoirai.garnet.project`.
- Produces:
  - `ExplorerTreeState.treeState: TreeState`
  - `ExplorerTreeState.selectedPath: String?`
  - `ExplorerTreeState.select(path: String)`
  - `ExplorerTreeState.expandedPaths: Set<String>`
  - `ExplorerTreeState.toggleExpanded(path: String)`
  - `ExplorerTreeState.selectedHasUnsaved(): Boolean`
  - `ExplorerTreeState.buildTreeFrom(root: FolderNode): Tree<FileTreeNode>`
  - `ExplorerTreeState.pathOf(element: Tree.Element<FileTreeNode>): String`
  - `ExplorerTreeState.reset()`

**Background — why path strings are the keys.** `TreeGeneratorScope.addNode(data, id) { }` and `addLeaf(data, id)` take an explicit `id: Any`, and `TreeState` stores `openNodes: Set<Any>` / `selectedKeys: Set<Any>`. Using the `/`-joined path as the id makes Jewel's own sets *be* the path sets — no mirrored state and no translation layer. `TreeState` is publicly constructible as `TreeState(SelectableLazyListState(LazyListState()))`, so it can be hoisted out of composition where networking code and tests can reach it.

- [ ] **Step 1: Write the failing test**

Create `src/clientTest/kotlin/com/breadmoirai/garnet/test/ExplorerTreeStateSpec.kt`:

```kotlin
package com.breadmoirai.garnet.test

import com.breadmoirai.garnet.client.ide.ExplorerTreeState
import com.breadmoirai.garnet.client.ide.ProjectTreeState
import com.breadmoirai.garnet.network.project.ProjectTreeSnapshotS2C
import com.breadmoirai.garnet.project.FileNode
import com.breadmoirai.garnet.project.FolderNode
import com.breadmoirai.garnet.testing.ClientSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe

class ExplorerTreeStateSpec : ClientSpec({

    val tree = FolderNode("root", listOf(
        FolderNode("adders", listOf(
            FolderNode("full-adder", listOf(FileNode("full.spec.kts", "kts"))),
        )),
        FileNode("dirty.nbt", "nbt", hasUnsaved = true),
        FileNode("clean.nbt", "nbt", hasUnsaved = false),
    ))

    test("selection is stored in Jewel's TreeState, keyed by path") {
        runOnClient { ExplorerTreeState.reset(); ExplorerTreeState.select("adders/full-adder") }
        ExplorerTreeState.treeState.selectedKeys shouldContainExactly setOf("adders/full-adder")
        ExplorerTreeState.selectedPath shouldBe "adders/full-adder"
    }

    test("expansion toggles Jewel's openNodes, keyed by path") {
        runOnClient { ExplorerTreeState.reset(); ExplorerTreeState.toggleExpanded("adders") }
        ExplorerTreeState.treeState.openNodes shouldContain "adders"
        ExplorerTreeState.expandedPaths shouldContain "adders"
        runOnClient { ExplorerTreeState.toggleExpanded("adders") }
        ExplorerTreeState.treeState.openNodes shouldNotContain "adders"
    }

    test("selectedHasUnsaved is derived from the snapshot, not stored") {
        runOnClient {
            ProjectTreeState.reset(); ExplorerTreeState.reset()
            ProjectTreeState.onSnapshot(ProjectTreeSnapshotS2C(tree, currentSubpath = null))
            ExplorerTreeState.select("dirty.nbt")
        }
        ExplorerTreeState.selectedHasUnsaved() shouldBe true
        runOnClient { ExplorerTreeState.select("clean.nbt") }
        ExplorerTreeState.selectedHasUnsaved() shouldBe false
        runOnClient { ExplorerTreeState.select("adders") }
        ExplorerTreeState.selectedHasUnsaved() shouldBe false
    }

    test("buildTreeFrom mirrors the snapshot with path ids, folders keeping their children") {
        val built = ExplorerTreeState.buildTreeFrom(tree)
        val ids = built.roots.map { it.id }
        ids shouldContainExactly listOf("adders", "dirty.nbt", "clean.nbt")
    }

    test("reset clears selection and expansion") {
        runOnClient {
            ExplorerTreeState.reset()
            ExplorerTreeState.select("dirty.nbt")
            ExplorerTreeState.toggleExpanded("adders")
            ExplorerTreeState.reset()
        }
        ExplorerTreeState.selectedPath shouldBe null
        ExplorerTreeState.expandedPaths shouldBe emptySet()
    }
})
```

- [ ] **Step 2: Register the spec in the sentinel**

In `ClientTestSentinel.kt`, add `ExplorerTreeStateSpec::class,` to the `specs = listOf(...)` block.

- [ ] **Step 3: Run the test to verify it fails**

Run: `cmd.exe /c "gradlew.bat :26.2:clientTestClasses"`
Expected: FAIL — `Unresolved reference: ExplorerTreeState`.

- [ ] **Step 4: Write the implementation**

Create `src/client/kotlin/com/breadmoirai/garnet/client/ide/ExplorerTreeState.kt`:

```kotlin
package com.breadmoirai.garnet.client.ide

import androidx.compose.foundation.lazy.LazyListState
import com.breadmoirai.garnet.project.FileNode
import com.breadmoirai.garnet.project.FileTreeNode
import com.breadmoirai.garnet.project.FolderNode
import com.breadmoirai.garnet.project.resolve
import org.jetbrains.jewel.foundation.lazy.SelectableLazyListState
import org.jetbrains.jewel.foundation.lazy.tree.Tree
import org.jetbrains.jewel.foundation.lazy.tree.TreeGeneratorScope
import org.jetbrains.jewel.foundation.lazy.tree.TreeState
import org.jetbrains.jewel.foundation.lazy.tree.buildTree

/**
 * Expand/selection state for the Explorer tree, owned by Jewel.
 *
 * Jewel's [TreeState] is the single source of truth — there is deliberately no mirrored `expanded`
 * set or `selectedPath` field, because two copies of tree state drift. The trick that makes this
 * work is that `addNode`/`addLeaf` take an explicit `id`, so we use each node's `/`-joined path and
 * Jewel's own `openNodes`/`selectedKeys` sets simply *are* sets of path strings.
 *
 * [TreeState] is hoisted here rather than created with `rememberTreeState()` inside composition, so
 * packet handlers, panel actions, and clientTests can read and drive it from outside a composable.
 */
object ExplorerTreeState {

    var treeState: TreeState = newTreeState()
        private set

    private fun newTreeState() = TreeState(SelectableLazyListState(LazyListState()))

    /** The selected node's path, or null. Single-selection: the tree is configured Single-mode. */
    val selectedPath: String?
        get() = treeState.selectedKeys.firstOrNull() as? String

    fun select(path: String) {
        treeState.selectedKeys = setOf(path)
    }

    val expandedPaths: Set<String>
        get() = treeState.openNodes.filterIsInstance<String>().toSet()

    fun toggleExpanded(path: String) = treeState.toggleNode(path)

    /** True when [selectedPath] resolves to a `.nbt` file flagged dirty in the current snapshot. */
    fun selectedHasUnsaved(): Boolean {
        val path = selectedPath ?: return false
        val node = ProjectTreeState.snapshot?.root?.resolve(path)
        return node is FileNode && node.hasUnsaved
    }

    /**
     * Convert a snapshot root into a Jewel [Tree]. The root folder itself is not emitted — its
     * children become the top-level rows, matching the previous hand-rolled tree.
     */
    fun buildTreeFrom(root: FolderNode): Tree<FileTreeNode> = buildTree {
        root.children.forEach { child -> addFileTreeNode(child, child.name) }
    }

    /** The `/`-joined path a tree element was built with. */
    fun pathOf(element: Tree.Element<FileTreeNode>): String = element.id as String

    /** Test/reset hook: drops selection and expansion by replacing the state wholesale. */
    fun reset() {
        treeState = newTreeState()
    }
}

/**
 * Recursive builder. Both `TreeBuilder` and `ChildrenGeneratorScope` implement [TreeGeneratorScope],
 * so one extension covers every depth. The `id` is the node's path, which is what makes Jewel's
 * selection/expansion sets path-keyed.
 */
private fun TreeGeneratorScope<FileTreeNode>.addFileTreeNode(node: FileTreeNode, path: String) {
    when (node) {
        is FolderNode -> addNode(node, path) {
            node.children.forEach { child -> addFileTreeNode(child, "$path/${child.name}") }
        }
        is FileNode -> addLeaf(node, path)
    }
}
```

- [ ] **Step 5: Strip the duplicated state from ProjectTreeState**

In `ProjectTreeState.kt`, delete the `expanded` property, the `selectedPath` property, `select()`, `toggleExpanded()`, and `selectedHasUnsaved()`; remove the now-unused `FileNode` and `resolve` imports. Update the KDoc and `reset()`:

```kotlin
/**
 * Client-side, Compose-observable state for the Project Explorer: the server's tree snapshot and the
 * status line. The networking layer mutates it from the client thread; [ProjectExplorerPanel] reads
 * it during composition and recomposes on change.
 *
 * Tree *interaction* state (expansion, selection) deliberately lives in [ExplorerTreeState], owned by
 * Jewel's TreeState, so there is exactly one copy of it.
 */
object ProjectTreeState {
    var snapshot by mutableStateOf<ProjectTreeSnapshotS2C?>(null)
        private set
    var status by mutableStateOf("")
        private set

    fun onSnapshot(s: ProjectTreeSnapshotS2C) { snapshot = s }

    fun onFolderLoaded(p: ProjectFolderLoadedS2C) {
        val errs = p.parseErrors.size + p.layoutErrors.size
        status = if (errs == 0) "loaded ${p.subpath} (${p.loadedSpecIds.size} specs)"
                 else "loaded ${p.subpath} with $errs error(s)"
    }

    fun onSaveReport(r: ProjectSaveReportS2C) { status = "saved ${r.perSpec.size} spec(s)" }
    fun onError(e: ProjectErrorS2C) { status = "error: ${e.reason}" }
    fun onStructureResult(r: StructureResultS2C) { status = r.message }

    /** Test/reset hook: clears the snapshot and status back to initial values. */
    fun reset() {
        snapshot = null
        status = ""
    }
}
```

- [ ] **Step 6: Update the call sites in ProjectExplorerPanel**

Mechanical only — the Jewel rewrite is Task 5. In `ProjectExplorerPanel.kt`:
- `ProjectTreeState.expanded` → `ExplorerTreeState.expandedPaths`
- `ProjectTreeState.toggleExpanded(...)` → `ExplorerTreeState.toggleExpanded(...)`
- `ProjectTreeState.selectedPath` → `ExplorerTreeState.selectedPath`
- `ProjectTreeState.select(...)` → `ExplorerTreeState.select(...)`
- `ProjectTreeState.selectedHasUnsaved()` → `ExplorerTreeState.selectedHasUnsaved()`

- [ ] **Step 7: Update the existing specs to the new owner**

In `StructureExplorerSpec.kt`: replace `ProjectTreeState.select(...)` with `ExplorerTreeState.select(...)` and `ProjectTreeState.selectedHasUnsaved()` with `ExplorerTreeState.selectedHasUnsaved()`; add `ExplorerTreeState.reset()` next to each `ProjectTreeState.reset()`; add the import.

In `ProjectExplorerSpec.kt`: replace both `ProjectTreeState.toggleExpanded(...)` calls with `ExplorerTreeState.toggleExpanded(...)`; add `ExplorerTreeState.reset()` next to `ProjectTreeState.reset()`; add the import.

- [ ] **Step 8: Run the tests to verify they pass**

Run: `cmd.exe /c "gradlew.bat :26.2:runClientTest"`
Expected: `All N tests passed`, including the five new `ExplorerTreeStateSpec` cases. `ProjectExplorerSpec` and `StructureExplorerSpec` still pass — the UI is unchanged in this task.

- [ ] **Step 9: Commit**

```bash
git add src/client/kotlin/com/breadmoirai/garnet/client/ide/ExplorerTreeState.kt src/client/kotlin/com/breadmoirai/garnet/client/ide/ProjectTreeState.kt src/client/kotlin/com/breadmoirai/garnet/client/ide/ProjectExplorerPanel.kt src/clientTest/kotlin/com/breadmoirai/garnet/test/
git commit -m "refactor(explorer): make Jewel's TreeState the single source of tree state

Expansion and selection move out of ProjectTreeState into a hoisted
Jewel TreeState, keyed by the same /-joined paths the tree is built
with -- so openNodes/selectedKeys ARE the path sets and nothing is
mirrored. ProjectTreeState keeps only the snapshot and status line.

No UI change; the panel rewrite follows."
```

---

### Task 5: Rebuild the Explorer panel on Jewel

**Files:**
- Modify: `src/client/kotlin/com/breadmoirai/garnet/client/ide/ProjectExplorerPanel.kt` (full rewrite)
- Modify: `src/client/kotlin/com/breadmoirai/garnet/client/ide/RootPickerController.kt`
- Modify: `src/clientTest/kotlin/com/breadmoirai/garnet/test/ProjectExplorerSpec.kt`, `RootPickerSpec.kt`

**Interfaces:**
- Consumes: everything `ExplorerTreeState` produces (Task 4); `RootPickerController.openFolder()`.
- Produces: `explorerPanel(): Panel` — unchanged signature, so `DockState.leftPanels.add(explorerPanel())` call sites are untouched.

**Background — behavior that must survive.** Clicking a `.nbt` file selects it and sends `PlaceStructureC2S(path)`. Clicking a folder containing any `*.spec.kts` sends `LoadProjectFolderC2S(path)`; other folders just expand/collapse. The node at `snapshot.currentSubpath` shows a `●` marker. A `.nbt` with `hasUnsaved` shows a `●` marker. Discard is dimmed unless the selection is dirty. Save/Discard only act on a `.nbt` selection.

**Background — `RootMenu` can go.** It existed only because of the rule "the embedded `ImageComposeScene` can't host a Compose `Popup`". The spike disproved that on Compose 1.11: `ImageComposeScene` is internally a `CanvasLayersComposeScene`, so popup layers draw into the same canvas rather than a separate OS window, and Jewel's `Dropdown` (built on `PopupContainer` → `androidx.compose.ui.window.Popup`) renders in-scene. Task 6's screenshot re-confirms this on `main` — **if that screenshot shows no menu, stop and restore `RootMenu` rather than shipping a broken picker.**

- [ ] **Step 1: Rewrite the panel**

Replace the entire contents of `ProjectExplorerPanel.kt`:

```kotlin
package com.breadmoirai.garnet.client.ide

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.breadmoirai.garnet.client.ui.compose.dock.Panel
import com.breadmoirai.garnet.network.project.DiscardStructureC2S
import com.breadmoirai.garnet.network.project.ListProjectTreeC2S
import com.breadmoirai.garnet.network.project.LoadProjectFolderC2S
import com.breadmoirai.garnet.network.project.NewStructureC2S
import com.breadmoirai.garnet.network.project.PlaceStructureC2S
import com.breadmoirai.garnet.network.project.SaveStructureC2S
import com.breadmoirai.garnet.project.FileNode
import com.breadmoirai.garnet.project.FolderNode
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import org.jetbrains.jewel.foundation.lazy.SelectionMode
import org.jetbrains.jewel.intui.standalone.theme.IntUiTheme
import org.jetbrains.jewel.ui.component.Dropdown
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.IconButton
import org.jetbrains.jewel.ui.component.LazyTree
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextField
import org.jetbrains.jewel.ui.component.separator
import org.jetbrains.jewel.ui.icons.AllIconsKeys

/** Panel background, matching the IntelliJ dark tool-window colour. */
private val PANEL_BG = Color(0xFF1E1F22)

/** The Explorer tab for DockState.leftPanels. */
fun explorerPanel(): Panel = Panel("garnet.explorer", "Explorer") { ProjectExplorer() }

@Composable
private fun ProjectExplorer() {
    IntUiTheme(isDark = true) {
        Column(Modifier.fillMaxSize().background(PANEL_BG).padding(4.dp)) {
            Header()
            StructureActions()
            val snap = ProjectTreeState.snapshot
            if (snap == null) {
                Text("(no project loaded — Refresh)", Modifier.padding(vertical = 2.dp))
            } else {
                LazyTree(
                    tree = ExplorerTreeState.buildTreeFrom(snap.root),
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    treeState = ExplorerTreeState.treeState,
                    selectionMode = SelectionMode.Single,
                    onElementClick = { element -> onElementClick(element.data, ExplorerTreeState.pathOf(element)) },
                ) { element ->
                    TreeRow(element.data, ExplorerTreeState.pathOf(element), snap.currentSubpath)
                }
            }
            val status = ProjectTreeState.status
            if (status.isNotEmpty()) Text(status, Modifier.padding(top = 4.dp))
        }
    }
}

/**
 * Click behavior, preserved from the hand-rolled tree: a `.nbt` places its structure, a folder that
 * directly contains any `*.spec.kts` loads that folder as a project, and every other folder just
 * expands/collapses (which LazyTree already does on its own for nodes).
 */
private fun onElementClick(node: com.breadmoirai.garnet.project.FileTreeNode, path: String) {
    ExplorerTreeState.select(path)
    when (node) {
        is FileNode -> if (node.extension == "nbt") ClientPlayNetworking.send(PlaceStructureC2S(path))
        is FolderNode ->
            if (node.children.any { it is FileNode && it.name.endsWith(".spec.kts") }) {
                ClientPlayNetworking.send(LoadProjectFolderC2S(path))
            }
    }
}

@Composable
private fun TreeRow(node: com.breadmoirai.garnet.project.FileTreeNode, path: String, currentSubpath: String?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        when (node) {
            is FolderNode -> Icon(AllIconsKeys.Nodes.Folder, contentDescription = null)
            is FileNode ->
                if (node.extension == "nbt") Icon(AllIconsKeys.FileTypes.Archive, contentDescription = null)
                else Icon(AllIconsKeys.FileTypes.Text, contentDescription = null)
        }
        val dirty = node is FileNode && node.hasUnsaved
        val current = path == currentSubpath
        val marker = if (dirty || current) "● " else ""
        Text("  $marker${node.name}")
    }
}

@Composable
private fun Header() {
    val rootName = ProjectTreeState.snapshot?.root?.name ?: "(no root)"
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        // Real Jewel Dropdown. This replaces the hand-rolled RootMenu overlay, which only existed
        // because Compose Popups were believed unable to render in the embedded scene.
        Dropdown(
            menuContent = {
                selectableItem(selected = false, onClick = { RootPickerController.openFolder() }) {
                    Text("Open Folder")
                }
                separator()
                selectableItem(selected = false, enabled = false, onClick = {}) {
                    Text("Attach Folder  (soon)")
                }
            },
        ) {
            Text(rootName)
        }
        Spacer(Modifier.weight(1f))
        IconButton(onClick = { ClientPlayNetworking.send(ListProjectTreeC2S.INSTANCE) }) {
            Icon(AllIconsKeys.Actions.Refresh, contentDescription = "Refresh")
        }
    }
}

@Composable
private fun StructureActions() {
    var newName by remember { mutableStateOf("") }
    val selected = ExplorerTreeState.selectedPath
    val isStructure = selected != null && selected.endsWith(".nbt")
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        TextField(
            value = newName,
            onValueChange = { newName = it },
            modifier = Modifier.width(110.dp),
        )
        DefaultButton(
            onClick = {
                if (newName.isNotBlank()) {
                    ClientPlayNetworking.send(NewStructureC2S(newName))
                    newName = ""
                }
            },
            modifier = Modifier.padding(horizontal = 4.dp),
        ) { Text("+ Structure") }
        Spacer(Modifier.weight(1f))
        OutlinedButton(
            onClick = { if (isStructure) ClientPlayNetworking.send(SaveStructureC2S(selected!!)) },
            enabled = isStructure,
            modifier = Modifier.padding(horizontal = 2.dp),
        ) { Text("Save") }
        OutlinedButton(
            onClick = { if (isStructure) ClientPlayNetworking.send(DiscardStructureC2S(selected!!)) },
            enabled = isStructure && ExplorerTreeState.selectedHasUnsaved(),
            modifier = Modifier.padding(horizontal = 2.dp),
        ) { Text("Discard") }
    }
}
```

**If an `AllIconsKeys` path does not resolve** (e.g. `FileTypes.Archive`), list the real names with:
`javap -cp <jewel-ui.jar> org.jetbrains.jewel.ui.icons.AllIconsKeys\$FileTypes | grep IconKey`

**If the `TextField(value, onValueChange, ...)` overload does not resolve,** Jewel 0.39 also exposes a `TextFieldState`-based overload; switch to `rememberTextFieldState()` and read `.text.toString()`.

- [ ] **Step 2: Delete the retired menu state**

In `RootPickerController.kt`, delete the `menuOpen` property, `toggleMenu()`, and `closeMenu()`; remove `closeMenu()` from the first line of `openFolder()` (the `Dropdown` closes itself on item click); and drop `menuOpen = false` from `resetForTest()`. Leave `picking`, `openFolder()`, and all five injectable seams untouched.

- [ ] **Step 3: Update the specs that referenced the retired menu**

In `RootPickerSpec.kt`: delete the `RootPickerController.toggleMenu()` line and the `RootPickerController.menuOpen shouldBe false` assertion from the first test. Everything else stands.

In `ProjectExplorerSpec.kt`: rewrite the second test ("Explorer header renders the root option button and opens the dropdown") to drop the `toggleMenu()`/`menuOpen` calls — the dropdown is now opened by clicking it, which `JewelExplorerSpec` covers in Task 6. Keep the test as a render check:

```kotlin
    test("Explorer header renders the root name") {
        closeClientScreen(); waitClientTicks(2)
        val tree = FolderNode("myroot", listOf(
            FolderNode("set", listOf(FileNode("a.spec.kts", "kts"))),
        ))
        runOnClient { mc ->
            DockState.reset(); ProjectTreeState.reset(); ExplorerTreeState.reset()
            RootPickerController.resetForTest()
            ProjectTreeState.onSnapshot(ProjectTreeSnapshotS2C(root = tree, currentSubpath = null))
            DockState.leftPanels.add(explorerPanel())
            DockState.setVisible(DockRegion.LEFT, true); DockState.setSize(DockRegion.LEFT, 300)
            ViewportState.active = true; ComposeOverlay.enabled = true
            (mc.window as Any as WindowViewportExt).`garnet$updateScaledFramebuffer`(true)
        }
        waitClientTicks(12)
        ProjectTreeState.snapshot!!.root.name shouldBe "myroot"
        capture("explorer_root_header.png")
        runOnClient { mc ->
            RootPickerController.resetForTest()
            ComposeOverlay.enabled = false; ViewportState.active = false; DockState.reset()
            (mc.window as Any as WindowViewportExt).`garnet$updateScaledFramebuffer`(true)
        }
        waitClientTicks(6)
    }
```

Add the `ExplorerTreeState` and `RootPickerController` imports at the top rather than using the fully-qualified names the old test used.

- [ ] **Step 4: Verify it compiles**

Run: `cmd.exe /c "gradlew.bat :26.2:clientClasses :26.2:clientTestClasses"`
Expected: `BUILD SUCCESSFUL`. Resolve any `AllIconsKeys`/Jewel signature mismatches using the `javap` recipes above.

- [ ] **Step 5: Verify the existing tests still pass**

Run: `cmd.exe /c "gradlew.bat :26.2:runClientTest"`
Expected: `All N tests passed`, no `disabling Compose surface` line. A resource-load failure during composition throws inside `host.render()` and permanently disables the surface, so a green run is itself evidence that Jewel's fonts and icons loaded under Fabric's Knot classloader.

Open `versions/26.2/run/screenshots/explorer_tree.png` and confirm the tree now renders in Jewel's Inter font with folder icons.

- [ ] **Step 6: Commit**

```bash
git add src/client/kotlin/com/breadmoirai/garnet/client/ide/ src/clientTest/kotlin/com/breadmoirai/garnet/test/
git commit -m "feat(explorer): rebuild the Explorer panel on Jewel widgets

LazyTree replaces the hand-rolled recursive TreeNode composable, a real
Jewel Dropdown replaces the hand-rolled RootMenu overlay, and the header
and structure actions move to Jewel TextField/Button/IconButton under one
IntUiTheme.

RootMenu existed only because Compose Popups were believed unable to
render in the embedded ImageComposeScene; that scene is internally a
CanvasLayersComposeScene, so popup layers draw in-canvas.

Behavior preserved: .nbt click places, spec-folder click loads, current
subpath and unsaved markers, Save/Discard enablement."
```

---

### Task 6: Visual and interaction verification

The gate for the whole migration. Everything here is read back from real screenshots.

**Files:**
- Create: `src/clientTest/kotlin/com/breadmoirai/garnet/test/JewelExplorerSpec.kt`
- Modify: `src/clientTest/kotlin/com/breadmoirai/garnet/test/ClientTestSentinel.kt`

**Interfaces:**
- Consumes: `explorerPanel()`, `ExplorerTreeState`, `ProjectTreeState`, `DockInputRouter.onGlfwKey(key, action, mods)`.
- Produces: nothing consumed by later tasks.

- [ ] **Step 1: Write the spec**

Create `src/clientTest/kotlin/com/breadmoirai/garnet/test/JewelExplorerSpec.kt`:

```kotlin
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
import io.kotest.matchers.nulls.shouldNotBeNull
import org.lwjgl.glfw.GLFW
import java.nio.file.Files
import java.nio.file.Path

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
        // Rows start below the tab strip, header and actions row. Click the first tree row.
        runOnClient {
            DockInputRouter.onGlfwMove(80.0, 78.0)
            DockInputRouter.onGlfwPress(0)
        }
        waitClientTicks(3)
        runOnClient { DockInputRouter.onGlfwRelease(0) }
        waitClientTicks(4)
        capture("jewel_explorer_click.png")
        println("[jewel] click: selectedPath=${ExplorerTreeState.selectedPath}")
        ExplorerTreeState.selectedPath.shouldNotBeNull()
        unmount()
        ComposeSurface.disabled.shouldBeFalse()
    }

    test("arrow keys navigate the tree end-to-end through the new key path") {
        closeClientScreen(); waitClientTicks(2)
        mountExplorer()
        runOnClient { DockInputRouter.focus(DockRegion.LEFT) }
        waitClientTicks(2)
        // Seed a selection by clicking, so the tree has focus and a starting row.
        runOnClient {
            DockInputRouter.onGlfwMove(80.0, 78.0)
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
        (after != before).shouldBeTrue()
        unmount()
        ComposeSurface.disabled.shouldBeFalse()
    }

    test("the root Dropdown opens in-scene over the Blaze3D FBO") {
        closeClientScreen(); waitClientTicks(2)
        mountExplorer()
        runOnClient { DockInputRouter.focus(DockRegion.LEFT) }
        waitClientTicks(2)
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
        capture("jewel_explorer_dropdown.png")
        println("[jewel] dropdown: disabled=${ComposeSurface.disabled}")
        unmount()
        ComposeSurface.disabled.shouldBeFalse()
    }
})
```

- [ ] **Step 2: Register the spec in the sentinel**

In `ClientTestSentinel.kt`, add `JewelExplorerSpec::class,` to the `specs = listOf(...)` block. Autoscan is off — without this it silently does not run.

- [ ] **Step 3: Run the spec**

Run: `cmd.exe /c "gradlew.bat :26.2:runClientTest"`
Expected: `All N tests passed`, no `disabling Compose surface` line.

If the click coordinates miss their targets, read `jewel_explorer_tree.png` to measure the real row offsets and adjust — panel layout depends on the header/actions row heights Jewel produces.

- [ ] **Step 4: Read the screenshots back and confirm**

Open each PNG in `versions/26.2/run/screenshots/` and verify:

| File | Must show |
|---|---|
| `jewel_explorer_tree.png` | Tree rows in Inter font; `adders` expanded, `clocks` collapsed; **chevrons and file icons painted, no magenta squares** |
| `jewel_explorer_click.png` | The clicked row carrying Jewel's selection highlight |
| `jewel_explorer_keynav.png` | The highlight moved one row down from the click |
| `jewel_explorer_dropdown.png` | The menu (`Open Folder`, `Attach Folder (soon)`) drawn **inside** the composite |

**Magenta placeholders mean the icon artwork artifact is missing or the version is wrong** — go back to Task 1 Step 5 and confirm `com.jetbrains.intellij.platform:icons` resolved. **An absent dropdown means popups are not rendering in-scene** — stop, and restore `RootMenu` rather than shipping a broken root picker.

- [ ] **Step 5: Commit**

```bash
git add src/clientTest/kotlin/com/breadmoirai/garnet/test/JewelExplorerSpec.kt src/clientTest/kotlin/com/breadmoirai/garnet/test/ClientTestSentinel.kt
git commit -m "test(explorer): verify the Jewel Explorer renders, selects, navigates and pops up

Screenshot-backed gate for the migration: chevrons and file icons paint
(the magenta-placeholder failure mode when the icon artwork artifact is
missing), pointer click selects, arrow keys navigate through the newly
wired key path, and the root Dropdown opens in-scene."
```

---

### Task 7: Documentation sync

Mandatory per `CLAUDE.md` after source changes. Newly written articles are indexed automatically on save — do **not** run `qmd embed` by hand.

**Files:**
- Create: `docs/ui/jewel-widget-layer.md`
- Modify: `docs/ui/dock-dialogs.md`, `docs/ui/dock-input-routing.md`, `docs/ui/dock-framework.md`, `docs/ui/INDEX.md`, `docs/tooling/compose-runtime-scoping.md`, `docs/ui/compose-in-mc-feasibility.md`, `docs/architecture/redstone-project.md`, `docs/use-cases/redstone-project.md`, `docs/use-cases/structure-lifecycle.md`, `docs/use-cases/command.md`

**Note on `qmd`:** it is installed but not on `PATH`, and a stale Windows `mise` shim shadows `node`. To use it: `export PATH="/home/local/.local/lib/node-v24.18.0-linux-x64/bin:$PATH"` then `qmd query "<q>" -c garnet-docs -n 8 --no-rerank --format md`.

- [ ] **Step 1: Write the new Jewel reference article**

Create `docs/ui/jewel-widget-layer.md` with frontmatter:

```
---
title: Jewel — the dock's widget layer
tags: [compose, jewel, dock, icons, popup, skiko, versions]
summary: The dock's IntelliJ-look widget layer. Covers the load-bearing Jewel/Compose/skiko version triple, why component icons need a separate artwork artifact, and the Jewel-authoritative tree-state model.
---
```

Cover, in this order:
1. **The version triple and why it moves together** — Jewel `0.39.1-262.9437.29` → Compose `1.11.0` → skiko `0.144.6`; Compose's transitive skiko must exactly match the bundled native or the native load / version guard fails.
2. **Icons need two artifacts** — `jewel-ui` ships the `AllIconsKeys` catalog with **zero SVGs**; `jewel-int-ui-standalone` ships only 45 (checkbox/radio/theme); the transitive `icons-api`/`-impl` are rendering machinery with zero SVGs. Artwork comes from `com.jetbrains.intellij.platform:icons`, which is **not on Maven Central** — hence the JetBrains repo. Note the deliberate version skew (Jewel wants `262.9437.29`; only `262.8665.x` is published). Failure mode: magenta placeholders.
3. **`IconKey` has no `ImageVector` seam** — it is `{ iconClass, path(Boolean) }`, resolved as a classloader resource. Record that Compose Material Icons are *not* an option (discontinued at 1.7.3, no 1.11 build), so nobody re-proposes it.
4. **Tree state is Jewel's** — `addNode`/`addLeaf` take explicit ids, so path strings are the keys and `openNodes`/`selectedKeys` are the path sets; `TreeState` is hoisted in `ExplorerTreeState` so non-composable callers can drive it.
5. **Popups render in-scene** — `ImageComposeScene` is internally `CanvasLayersComposeScene`; cross-link `dock-dialogs.md`.

- [ ] **Step 2: Rewrite dock-dialogs.md**

Its central premise is now false. Replace the `## No Compose Popup / DropdownMenu` section with a section stating that popups **do** render in-scene (because `ImageComposeScene` is a `CanvasLayersComposeScene`, so popup layers draw into the same canvas), that `ProjectExplorerPanel.RootMenu` was retired in favour of a Jewel `Dropdown`, and that new menus should use Jewel components rather than hand-rolled z-layered overlays. Keep the "Native OS dialogs block" section — still valid. Update the frontmatter `title` and `summary` to match, and cross-link `jewel-widget-layer.md`.

- [ ] **Step 3: Update dock-input-routing.md**

In "What is and isn't forwarded", replace the `**Key→Compose translation is deferred.**` bullet with the current behavior: keys are mapped through `GlfwKeyMap` and delivered via `ComposeSurface.sendKey`; `onGlfwChar` forwards printable characters from the `charTyped` mixin injection; unmapped keys are dropped rather than guessed at. State that the ESC contract is unchanged — still the only key reported consumed. Add `charTyped(JLnet/minecraft/client/input/CharacterEvent;)V` to the `KeyboardHandlerMixin` target list, and note `keyPress` now also passes `event.modifiers()`. In "Test coverage", add the two new `DockInputSpec` cases and `GlfwKeyMapSpec`. Update the frontmatter `summary`.

- [ ] **Step 4: Update dock-framework.md**

The section citing `client/ide/ProjectExplorerPanel.kt` + `ProjectTreeState.kt` as the template must describe the Jewel pattern: content wrapped in `IntUiTheme`, `LazyTree` fed by `ExplorerTreeState.buildTreeFrom`, tree state in Jewel's `TreeState`, and `ProjectTreeState` reduced to snapshot + status. Link `jewel-widget-layer.md`.

- [ ] **Step 5: Update the remaining articles**

- `docs/tooling/compose-runtime-scoping.md` — Compose is now **1.11.0 stable** (down from 1.12.0-beta02) with skiko 0.144.6; note that Jewel and the icons artifact are also `clientImplementation` and why the pin moves as a triple.
- `docs/ui/compose-in-mc-feasibility.md` — update version references (Compose 1.12.0-beta02 → 1.11.0, skiko 0.150.1 → 0.144.6).
- `docs/architecture/redstone-project.md` — the Client bullet describing `ProjectExplorerPanel` "recursively renders `snapshot.root.children` … with per-folder expand/collapse" and `ProjectTreeState` now describes the Jewel `LazyTree` and `ExplorerTreeState`.
- `docs/use-cases/redstone-project.md`, `docs/use-cases/structure-lifecycle.md`, `docs/use-cases/command.md` — update any `TreeNode`, `RootMenu`, `ProjectTreeState.select`/`.selectedPath`/`.expanded` citation to its new owner.

- [ ] **Step 6: Register in the index**

In `docs/ui/INDEX.md`, add:
`- [Jewel — the dock's widget layer](jewel-widget-layer.md) — The dock's IntelliJ-look widget layer: the load-bearing Jewel/Compose/skiko triple, why icons need a separate artwork artifact, and the Jewel-authoritative tree-state model.` plus its tags. Update the `dock-dialogs.md` entry's summary to match its rewrite.

- [ ] **Step 7: Verify no dangling references**

Run:

```bash
grep -rn "TreeNode\|RootMenu\|menuOpen\|toggleMenu" docs/ --include=*.md | grep -v "docs/superpowers/"
grep -rn "1\.12\.0-beta02\|0\.150\.1" docs/ --include=*.md | grep -v "docs/superpowers/"
```

Expected: **zero** hits describing these as current. `docs/superpowers/` specs and plans are commit-time snapshots and are deliberately excluded — leave them as-is.

Also confirm every `[link](path.md)` added in this task resolves to a real file.

- [ ] **Step 8: Commit**

```bash
git add docs/
git commit -m "docs: sync for the Jewel widget layer

Rewrites dock-dialogs.md -- its 'the embedded ImageComposeScene cannot
host a Compose Popup' premise is false on Compose 1.11, and RootMenu is
retired. Updates dock-input-routing.md for the now-wired keyboard path,
dock-framework.md for the Jewel panel template, and the version
references in compose-runtime-scoping.md and compose-in-mc-feasibility.md.

Adds docs/ui/jewel-widget-layer.md as the durable Jewel reference,
including why component icons need a separate artwork artifact and why
Compose Material Icons are not an option."
```

---

## Self-Review

**Spec coverage:**

| Spec section | Task |
|---|---|
| D1 version triple | Task 1 |
| D2 icons artifact + repo | Task 1 |
| D3 Jewel-authoritative TreeState | Task 4 |
| D4 whole-panel conversion | Task 5 |
| D5 keyboard forwarding | Tasks 2, 3 |
| D6 retire RootMenu | Task 5 (deletion), Task 6 (in-scene re-confirmation) |
| Verification (4 checks + `disabled` gate) | Task 6 |
| Documentation impact (10 files) | Task 7 |
| Increments 1–4 | Tasks 1 / 2+3 / 4+5 / 7 |

No gaps. The spec's four-item verification list maps to Task 6's four tests.

**Type consistency:** `ExplorerTreeState` members are declared once in Task 4's Interfaces block and used identically in Tasks 5 and 6. `onGlfwKey(key, action, mods)` gains its third parameter in Task 3 with a default, and Task 6 passes all three. `glfwKeyToComposeKey`/`GlfwMods` are defined in Task 2 and consumed in Task 3.

**Deviation from the spec, deliberate:** the spec named icons `262.8665.369` without noting that Jewel's own build number (`262.9437.29`) is unavailable for the artwork artifact. Task 1 records the skew and its rationale explicitly.
