# Explorer Toolbar & Context Menu Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the Project Explorer's debug-bar controls and the dock's hand-rolled tab strip with a themed IDE toolbar and a right-click `New ▸ (Folder | Structure)` / `Rename` context menu that edits names inline in the tree, backed by real packets and server handlers.

**Architecture:** Three layers, built bottom-up. Pointer buttons are first threaded into the Compose scene (nothing else works without secondary clicks). Then the dock chrome and Explorer toolbar are rewritten. Then a `PopupMenu`-based context menu drives an `ExplorerEdit` state that injects a synthetic placeholder node into the Jewel tree, and commits through three project packets whose server handlers do the filesystem work and re-send the tree.

**Tech Stack:** Kotlin, Fabric (Minecraft 26.2), Compose Multiplatform 1.11.0 desktop, JetBrains Jewel 0.39.1-262.9437.29, Kotest (unit / gametest / clientTest source sets), Stonecutter multi-version build.

**Spec:** `docs/superpowers/specs/2026-07-28-explorer-toolbar-context-menu-design.md`

## Global Constraints

- **Gradle is invoked through Windows cmd**: `cmd.exe /c "gradlew.bat <tasks>"`. Never use a `./` prefix — cmd.exe cannot parse it.
- **Stonecutter task paths are `:26.2:<task>`**, not `:versions:26.2:<task>`.
- **Full compile check spans five source sets**: `:26.2:clientClasses :26.2:classes :26.2:gametestClasses :26.2:clientTestClasses :26.2:testClasses`. Compiling only `compileKotlin` misses breakage.
- **`runClientTest` / `runGameTest` must run in the foreground** with `timeout: 600000`. Background Gradle runs are lost. clientTest XML reports are always empty — read the console output.
- **Gradle `--tests` filtering does not work with Kotest.** Run the whole task and read `versions/26.2/build/test-results/test/*.xml`.
- **New Kotest specs must be registered by hand.** Autoscan is off: gametest specs go in `src/gametest/kotlin/com/breadmoirai/garnet/test/GametestSentinel.kt`, clientTest specs in `src/clientTest/kotlin/com/breadmoirai/garnet/test/ClientTestSentinel.kt`. An unregistered spec silently does not run.
- **Kotlin `internal` is per-source-set.** `gametest`/`clientTest` cannot see `internal` declarations from `main`. Anything a test touches across that boundary must be `public`.
- **Do not add `Co-Authored-By: Claude` or any Claude attribution to commit messages.**
- **Work directly on `main`.** No feature branches or worktrees.
- **Icon key names are verified and exact**: `AllIconsKeys.Actions.More` (kebab / `moreVertical.svg`), `AllIconsKeys.Actions.Collapseall` (lowercase `a`), `AllIconsKeys.Actions.Refresh`, `AllIconsKeys.Nodes.Folder`, `AllIconsKeys.FileTypes.Archive`, `AllIconsKeys.FileTypes.Text`. A wrong key renders a magenta placeholder square rather than throwing.
- **ARGB text colors**: use `-1` for white, never `0xFFFFFF` (alpha 0 renders invisible).

## File Structure

**Created**
- `src/client/kotlin/com/breadmoirai/garnet/client/ide/ExplorerToolbar.kt` — the toolbar row and its kebab menu.
- `src/client/kotlin/com/breadmoirai/garnet/client/ide/ExplorerEdit.kt` — inline-edit state model + the synthetic-node id helper.
- `src/client/kotlin/com/breadmoirai/garnet/client/ide/ExplorerContextMenu.kt` — right-click menu state + `PopupMenu` composable.
- `src/client/kotlin/com/breadmoirai/garnet/client/ide/ExplorerActions.kt` — the network seam (swappable sender) the menu commits through.
- `src/main/kotlin/com/breadmoirai/garnet/project/ProjectNames.kt` — shared name validation.
- `src/test/kotlin/com/breadmoirai/garnet/project/ProjectNamesTest.kt`
- `src/gametest/kotlin/com/breadmoirai/garnet/test/project/ProjectFileOpsNetworkSpec.kt` — create-folder / create-structure / rename handlers.
- `src/clientTest/kotlin/com/breadmoirai/garnet/test/ExplorerContextMenuSpec.kt`

**Modified**
- `src/client/kotlin/com/breadmoirai/garnet/client/ui/compose/ComposeSceneHost.kt` — pointer button parameter.
- `src/client/kotlin/com/breadmoirai/garnet/client/ui/compose/ComposeSurface.kt` — pointer button parameter.
- `src/client/kotlin/com/breadmoirai/garnet/client/ui/compose/input/DockInputRouter.kt` — GLFW→`PointerButton` mapping.
- `src/client/kotlin/com/breadmoirai/garnet/client/ui/compose/dock/GarnetDock.kt` — tab strip removal.
- `src/client/kotlin/com/breadmoirai/garnet/client/ide/ProjectExplorerPanel.kt` — toolbar, context menu host, inline rows.
- `src/client/kotlin/com/breadmoirai/garnet/client/ide/ExplorerTreeState.kt` — `collapseAll()`, root node, synthetic node.
- `src/main/kotlin/com/breadmoirai/garnet/network/project/ProjectPackets.kt` — two new payloads, one reshaped.
- `src/main/kotlin/com/breadmoirai/garnet/network/project/ProjectNetworkRegistry.kt` — three handlers.
- `src/main/kotlin/com/breadmoirai/garnet/project/ProjectDimRegistry.kt` — `unplaceStructure`.
- `src/test/kotlin/com/breadmoirai/garnet/network/StructurePacketsTest.kt`
- `src/gametest/kotlin/com/breadmoirai/garnet/test/project/ProjectStructureNetworkSpec.kt`
- `src/gametest/kotlin/com/breadmoirai/garnet/test/GametestSentinel.kt`
- `src/clientTest/kotlin/com/breadmoirai/garnet/test/ClientTestSentinel.kt`
- `src/clientTest/kotlin/com/breadmoirai/garnet/test/{JewelExplorerSpec,ProjectExplorerSpec,StructureExplorerSpec,ExplorerTreeStateSpec,DockRenderSpec}.kt`

**Phase ordering note.** The spec sequenced packets in phase 3. This plan moves the *packet payload classes* (Task 5) to the front of phase 2, leaving the *server handlers* in phase 3. The client menu needs concrete payload types to send; a separate throwaway seam interface would be deleted one task later. Payload classes are inert data in `src/main` and cost nothing to land early.

---

## Phase 1 — Chrome

### Task 1: Pointer buttons reach the Compose scene

**Files:**
- Modify: `src/client/kotlin/com/breadmoirai/garnet/client/ui/compose/ComposeSceneHost.kt:33-36`
- Modify: `src/client/kotlin/com/breadmoirai/garnet/client/ui/compose/ComposeSurface.kt:269-272`
- Modify: `src/client/kotlin/com/breadmoirai/garnet/client/ui/compose/input/DockInputRouter.kt:52-58`
- Test: `src/clientTest/kotlin/com/breadmoirai/garnet/test/DockInputSpec.kt`

**Interfaces:**
- Consumes: nothing (first task).
- Produces:
  - `ComposeSceneHost.pointerPress(pos: Offset, button: PointerButton? = null)`
  - `ComposeSceneHost.pointerRelease(pos: Offset, button: PointerButton? = null)`
  - `ComposeSurface.sendPointerPress(pos: Offset, button: PointerButton? = null)`
  - `ComposeSurface.sendPointerRelease(pos: Offset, button: PointerButton? = null)`
  - `DockInputRouter.onGlfwPress(button: Int)` / `onGlfwRelease(button: Int)` — signatures unchanged; behaviour now forwards the button.
  - `com.breadmoirai.garnet.client.ui.compose.input.glfwMouseButtonToPointerButton(button: Int): PointerButton?`

- [ ] **Step 1: Write the failing test**

Add to `src/clientTest/kotlin/com/breadmoirai/garnet/test/DockInputSpec.kt`, inside the existing `ClientSpec({ ... })` body:

```kotlin
test("GLFW mouse buttons map to Compose pointer buttons") {
    glfwMouseButtonToPointerButton(GLFW.GLFW_MOUSE_BUTTON_LEFT) shouldBe PointerButton.Primary
    glfwMouseButtonToPointerButton(GLFW.GLFW_MOUSE_BUTTON_RIGHT) shouldBe PointerButton.Secondary
    glfwMouseButtonToPointerButton(GLFW.GLFW_MOUSE_BUTTON_MIDDLE) shouldBe PointerButton.Tertiary
    glfwMouseButtonToPointerButton(7) shouldBe null
}

test("a secondary press reaches the scene as Secondary") {
    val seen = mutableListOf<PointerButton?>()
    val panel = Panel("garnet.test.buttonprobe", "ButtonProbe") {
        Box(
            Modifier.fillMaxSize().pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val e = awaitPointerEvent()
                        if (e.type == PointerEventType.Press) seen += e.button
                    }
                }
            },
        )
    }
    runOnClient {
        DockState.leftPanels.add(panel)
        DockState.setVisible(DockRegion.LEFT, true)
        DockInputRouter.focus(DockRegion.LEFT)
    }
    waitClientTicks(4)
    runOnClient {
        DockInputRouter.onGlfwMove(60.0, 200.0)
        DockInputRouter.onGlfwPress(GLFW.GLFW_MOUSE_BUTTON_RIGHT)
    }
    waitClientTicks(4)
    runOnClient { DockInputRouter.onGlfwRelease(GLFW.GLFW_MOUSE_BUTTON_RIGHT) }
    waitClientTicks(2)

    seen shouldBe listOf(PointerButton.Secondary)
    ComposeSurface.disabled.shouldBeFalse()

    runOnClient {
        DockInputRouter.clearFocus()
        DockState.leftPanels.remove(panel)
    }
}
```

Add the imports this needs to the top of the file: `androidx.compose.foundation.layout.Box`, `androidx.compose.foundation.layout.fillMaxSize`, `androidx.compose.ui.Modifier`, `androidx.compose.ui.input.pointer.PointerButton`, `androidx.compose.ui.input.pointer.PointerEventType`, `androidx.compose.ui.input.pointer.pointerInput`, `com.breadmoirai.garnet.client.ui.compose.ComposeSurface`, `com.breadmoirai.garnet.client.ui.compose.dock.Panel`, `com.breadmoirai.garnet.client.ui.compose.input.glfwMouseButtonToPointerButton`, `io.kotest.matchers.booleans.shouldBeFalse`, `io.kotest.matchers.shouldBe`, `org.lwjgl.glfw.GLFW`.

- [ ] **Step 2: Run the test to verify it fails**

Run: `cmd.exe /c "gradlew.bat :26.2:clientTestClasses"`
Expected: FAIL — `Unresolved reference: glfwMouseButtonToPointerButton`.

- [ ] **Step 3: Add the button mapping**

Append to `src/client/kotlin/com/breadmoirai/garnet/client/ui/compose/input/DockInputRouter.kt`, at file scope below the `object DockInputRouter` block:

```kotlin
/**
 * GLFW mouse-button index → Compose [PointerButton]. Returns null for buttons Compose has no
 * concept of (GLFW exposes 8), so callers drop them rather than mislabelling them as Primary.
 */
fun glfwMouseButtonToPointerButton(button: Int): PointerButton? = when (button) {
    GLFW.GLFW_MOUSE_BUTTON_LEFT -> PointerButton.Primary
    GLFW.GLFW_MOUSE_BUTTON_RIGHT -> PointerButton.Secondary
    GLFW.GLFW_MOUSE_BUTTON_MIDDLE -> PointerButton.Tertiary
    else -> null
}
```

Add `import androidx.compose.ui.input.pointer.PointerButton` to that file.

- [ ] **Step 4: Thread the button through the scene host**

In `ComposeSceneHost.kt`, replace lines 34-35:

```kotlin
    fun pointerPress(pos: Offset, button: PointerButton? = null) =
        scene.sendPointerEvent(PointerEventType.Press, pos, button = button)

    fun pointerRelease(pos: Offset, button: PointerButton? = null) =
        scene.sendPointerEvent(PointerEventType.Release, pos, button = button)
```

Add `import androidx.compose.ui.input.pointer.PointerButton`.

- [ ] **Step 5: Thread the button through ComposeSurface**

In `ComposeSurface.kt`, replace lines 270-271:

```kotlin
    fun sendPointerPress(pos: Offset, button: PointerButton? = null) =
        guardedInput { host?.pointerPress(pos, button) }

    fun sendPointerRelease(pos: Offset, button: PointerButton? = null) =
        guardedInput { host?.pointerRelease(pos, button) }
```

Add `import androidx.compose.ui.input.pointer.PointerButton`.

- [ ] **Step 6: Forward the real button from the router**

In `DockInputRouter.kt`, replace `onGlfwPress`/`onGlfwRelease` (lines 52-58):

```kotlin
    fun onGlfwPress(button: Int) {
        if (!captured) return
        val composeButton = glfwMouseButtonToPointerButton(button) ?: return
        ComposeSurface.sendPointerPress(Offset(lastX.toFloat(), lastY.toFloat()), composeButton)
    }

    fun onGlfwRelease(button: Int) {
        if (!captured) return
        val composeButton = glfwMouseButtonToPointerButton(button) ?: return
        ComposeSurface.sendPointerRelease(Offset(lastX.toFloat(), lastY.toFloat()), composeButton)
    }
```

- [ ] **Step 7: Compile all five source sets**

Run: `cmd.exe /c "gradlew.bat :26.2:clientClasses :26.2:classes :26.2:gametestClasses :26.2:clientTestClasses :26.2:testClasses"`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 8: Run the client tests**

Run (foreground, `timeout: 600000`): `cmd.exe /c "gradlew.bat :26.2:runClientTest"`
Expected: console output shows `DockInputSpec` passing both new tests, and no previously-passing spec regressing. The XML report is empty by design — read the console.

- [ ] **Step 9: Commit**

```bash
git add src/client/kotlin/com/breadmoirai/garnet/client/ui/compose src/clientTest/kotlin/com/breadmoirai/garnet/test/DockInputSpec.kt
git commit -m "feat(dock): carry GLFW mouse buttons into the Compose scene

onGlfwPress/Release discarded their button index and sendPointerEvent was
called with none, so Compose saw every dock click as Primary. Right-click
now arrives as PointerButton.Secondary."
```

---

### Task 2: Remove the dock tab strip

**Files:**
- Modify: `src/client/kotlin/com/breadmoirai/garnet/client/ui/compose/dock/GarnetDock.kt:25-31,70-102,134-137`
- Test: `src/clientTest/kotlin/com/breadmoirai/garnet/test/DockRenderSpec.kt`

**Interfaces:**
- Consumes: nothing from Task 1.
- Produces: `RegionColumn` renders only the active panel body. `DockState.leftActiveTab`/`rightActiveTab`/`bottomActiveTab`/`centerActiveTab`, `DockState.panelsFor`, `DockState.mountEpoch`, and `Panel.title` all keep their current signatures and remain in use.

- [ ] **Step 1: Check what the render spec currently asserts**

Run: `grep -n "TAB\|tab\|18\|title" src/clientTest/kotlin/com/breadmoirai/garnet/test/DockRenderSpec.kt`

If any assertion samples pixels inside the top 18px of a region (the old `TAB_H`) or asserts a tab-coloured pixel, it must be updated in Step 5. If nothing matches, no spec change is needed — note that and move on.

- [ ] **Step 2: Delete the tab strip from RegionColumn**

In `GarnetDock.kt`, replace the whole `RegionColumn` function (lines 70-102) with:

```kotlin
/** A region = the active panel's body, filling the region. */
@Composable
private fun RegionColumn(region: DockRegion, modifier: Modifier) {
    val panels = DockState.panelsFor(region)
    if (panels.isEmpty()) return
    val active = activeTabFor(region).coerceIn(0, panels.lastIndex)
    Column(modifier.background(PANEL_BG)) {
        // key(): a panel body must not be able to outlive its mount. Panel content is invoked at a
        // fixed slot, and a re-mounted panel from the same factory has the same composable source
        // key, so without this Compose reuses the group and every `remember` inside survives — most
        // visibly a Jewel PopupMenu's open menu and its Popup layer, which then paints over the next
        // mount. See DockState.mountEpoch for the full mechanism. Panel id is in the key too so
        // swapping which panel occupies a tab index is likewise a fresh mount.
        Box(Modifier.fillMaxSize()) {
            key(DockState.mountEpoch(region), panels[active].id) { panels[active].content(panels[active]) }
        }
    }
}
```

- [ ] **Step 3: Delete the now-dead declarations**

In `GarnetDock.kt`:
- Delete the constants `TAB_H`, `TAB_BG`, `TAB_BG_INACTIVE`, `TEXT` (lines 26, 28-30). Keep `SPLITTER`, `PANEL_BG`, `SPLITTER_COLOR`.
- Delete the `detectTapOrDown` extension function at the bottom of the file (lines 134-137).
- Delete the `setActiveTab` private function — with the strip gone nothing calls it. Keep `activeTabFor`; `RegionColumn` still reads it.
- Delete the now-unused imports: `androidx.compose.foundation.gestures.detectTapGestures`, `androidx.compose.foundation.layout.Row`, `androidx.compose.foundation.layout.height`, `androidx.compose.foundation.layout.padding`, `androidx.compose.foundation.text.BasicText`, `androidx.compose.ui.input.pointer.pointerInput`, `androidx.compose.ui.text.TextStyle`, `androidx.compose.ui.unit.TextUnit`.

Keep `androidx.compose.foundation.gestures.detectDragGestures` and `androidx.compose.ui.input.pointer.pointerInput` **only if** the splitters still use them — `Splitter` calls `pointerInput` and `detectDragGestures`, so both stay. Remove only `detectTapGestures`.

- [ ] **Step 4: Compile**

Run: `cmd.exe /c "gradlew.bat :26.2:clientClasses :26.2:clientTestClasses"`
Expected: BUILD SUCCESSFUL. If it fails with "unused import" style errors, that is not a Kotlin error — a real failure here means something still references a deleted symbol; fix that reference.

- [ ] **Step 5: Update DockRenderSpec if Step 1 found tab assertions**

For any assertion that sampled a pixel in the top 18px expecting a tab colour, change it to assert the panel background is present there instead. Example shape (adapt to the spec's actual probe helper):

```kotlin
// The tab strip is gone: the top of a region is now panel body, not tab chrome.
regionPixelAt(DockRegion.LEFT, x = 20, y = 6) shouldNotBe TAB_BLUE
```

If Step 1 found nothing, skip this step.

- [ ] **Step 6: Run the client tests**

Run (foreground, `timeout: 600000`): `cmd.exe /c "gradlew.bat :26.2:runClientTest"`
Expected: all specs pass. `DockRenderSpec`, `DockLifecycleSpec`, and `JewelExplorerSpec` are the likely breakers — every panel's content now starts 18px higher, so any hardcoded click coordinate in a spec must shift up by 18. Fix those coordinates as they surface.

- [ ] **Step 7: Commit**

```bash
git add src/client/kotlin/com/breadmoirai/garnet/client/ui/compose/dock/GarnetDock.kt src/clientTest/kotlin/com/breadmoirai/garnet/test/
git commit -m "feat(dock): drop the hand-rolled tab strip

The strip was Box+BasicText with hardcoded colours outside IntUiTheme, and
with one panel registered it earned nothing. Regions now render the active
panel body directly; the multi-panel model and the mountEpoch guard stay."
```

---

### Task 3: The Explorer toolbar

**Files:**
- Create: `src/client/kotlin/com/breadmoirai/garnet/client/ide/ExplorerToolbar.kt`
- Modify: `src/client/kotlin/com/breadmoirai/garnet/client/ide/ProjectExplorerPanel.kt`
- Modify: `src/client/kotlin/com/breadmoirai/garnet/client/ide/ExplorerTreeState.kt`
- Test: `src/clientTest/kotlin/com/breadmoirai/garnet/test/ExplorerTreeStateSpec.kt`, `.../StructureExplorerSpec.kt`, `.../JewelExplorerSpec.kt`, `.../ProjectExplorerSpec.kt`

**Interfaces:**
- Consumes: `RootPickerController.openFolder()` (existing), `ExplorerTreeState.treeState` (existing).
- Produces:
  - `ExplorerTreeState.collapseAll()` — clears every open node.
  - `@Composable fun ExplorerToolbar()` in package `com.breadmoirai.garnet.client.ide`.

- [ ] **Step 1: Write the failing test**

Add to `src/clientTest/kotlin/com/breadmoirai/garnet/test/ExplorerTreeStateSpec.kt`:

```kotlin
test("collapseAll clears every open node") {
    ExplorerTreeState.reset()
    ExplorerTreeState.treeState.openNodes = setOf("adders", "adders/full-adder", "clocks")
    ExplorerTreeState.expandedPaths shouldBe setOf("adders", "adders/full-adder", "clocks")

    ExplorerTreeState.collapseAll()

    ExplorerTreeState.expandedPaths.shouldBeEmpty()
}
```

Add imports `io.kotest.matchers.collections.shouldBeEmpty` and `io.kotest.matchers.shouldBe` if absent.

- [ ] **Step 2: Run to verify it fails**

Run: `cmd.exe /c "gradlew.bat :26.2:clientTestClasses"`
Expected: FAIL — `Unresolved reference: collapseAll`.

- [ ] **Step 3: Add collapseAll**

In `ExplorerTreeState.kt`, below `toggleExpanded` (line 49):

```kotlin
    /** Collapse every expanded node. Selection is left alone — IntelliJ's Collapse All does the same. */
    fun collapseAll() {
        treeState.openNodes = emptySet()
    }
```

- [ ] **Step 4: Write the toolbar**

Create `src/client/kotlin/com/breadmoirai/garnet/client/ide/ExplorerToolbar.kt`:

```kotlin
package com.breadmoirai.garnet.client.ide

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.weight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.breadmoirai.garnet.network.project.ListProjectTreeC2S
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.IconButton
import org.jetbrains.jewel.ui.component.PopupMenu
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.icons.AllIconsKeys

/**
 * The Explorer's tool-window toolbar: a kebab overflow menu on the left, tree actions on the right.
 *
 * Replaces the old root-name `Dropdown` + `+ New`/`Save`/`Discard` rows. The root name is no longer
 * shown here — the tree's own root node carries it (see [ExplorerTreeState.buildTreeFrom]).
 */
@Composable
fun ExplorerToolbar() {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        KebabMenu()
        Spacer(Modifier.weight(1f))
        IconButton(onClick = { ClientPlayNetworking.send(ListProjectTreeC2S.INSTANCE) }) {
            Icon(AllIconsKeys.Actions.Refresh, contentDescription = "Refresh")
        }
        IconButton(onClick = { ExplorerTreeState.collapseAll() }) {
            // Actions.Collapseall — Jewel's generated name lowercases the second "a". The key
            // resolves to expui/general/collapseAll.svg in the IntelliJ icons artifact.
            Icon(AllIconsKeys.Actions.Collapseall, contentDescription = "Collapse All")
        }
    }
}

@Composable
private fun KebabMenu() {
    var open by remember { mutableStateOf(false) }
    IconButton(onClick = { open = !open }) {
        // Actions.More is the VERTICAL three-dot kebab (expui/general/moreVertical.svg).
        // Actions.MoreHorizontal is the horizontal variant — not this.
        Icon(AllIconsKeys.Actions.More, contentDescription = "Menu")
    }
    if (open) {
        PopupMenu(
            onDismissRequest = { open = false; true },
            horizontalAlignment = Alignment.Start,
        ) {
            selectableItem(selected = false, onClick = { open = false; RootPickerController.openFolder() }) {
                Text("Open Folder…")
            }
        }
    }
}
```

- [ ] **Step 5: Wire it into the panel and delete the old rows**

In `ProjectExplorerPanel.kt`:
- Replace the `Header()` and `StructureActions()` calls (lines 51-52) with a single `ExplorerToolbar()`.
- Delete the entire `Header()` function (lines 114-138) and the entire `StructureActions()` function (lines 140-182).
- Delete the now-unused imports: `androidx.compose.foundation.layout.Spacer`, `androidx.compose.foundation.layout.width`, `androidx.compose.foundation.text.input.clearText`, `androidx.compose.foundation.text.input.rememberTextFieldState`, `androidx.compose.ui.Alignment` (only if `TreeRow` no longer needs it — it does, keep it), `com.breadmoirai.garnet.network.project.DiscardStructureC2S`, `com.breadmoirai.garnet.network.project.ListProjectTreeC2S`, `com.breadmoirai.garnet.network.project.NewStructureC2S`, `com.breadmoirai.garnet.network.project.SaveStructureC2S`, `org.jetbrains.jewel.ui.component.Dropdown`, `org.jetbrains.jewel.ui.component.DefaultSlimButton`, `org.jetbrains.jewel.ui.component.OutlinedSlimButton`, `org.jetbrains.jewel.ui.component.IconButton`, `org.jetbrains.jewel.ui.component.TextField`, `org.jetbrains.jewel.ui.component.separator`.

Keep `Row`, `Alignment`, `Icon`, `AllIconsKeys`, `Text`, `LazyTree` — `TreeRow` still uses them.

- [ ] **Step 6: Compile**

Run: `cmd.exe /c "gradlew.bat :26.2:clientClasses :26.2:clientTestClasses"`
Expected: BUILD SUCCESSFUL.

If `PopupMenu`'s `onDismissRequest` does not accept a `() -> Boolean`, check the descriptor: the two-overload set is `PopupMenu(onDismissRequest: (InputMode) -> Boolean, horizontalAlignment: Alignment.Horizontal, ...)` and `PopupMenu(onDismissRequest: (InputMode) -> Boolean, popupPositionProvider: PopupPositionProvider, ...)`. If the lambda takes an `InputMode` parameter, write `onDismissRequest = { _ -> open = false; true }`.

- [ ] **Step 7: Fix the specs that assert on the removed controls**

`StructureExplorerSpec` exercises the `+ New` / `Save` / `Discard` buttons by clicking their coordinates, and `JewelExplorerSpec.openRootMenu()` clicks the root `Dropdown` at `(40, 34)`. Both are now testing controls that no longer exist.

- In `StructureExplorerSpec`: delete the tests that click `+ New` / `Save` / `Discard`. The underlying packets keep their gametest coverage in `ProjectStructureNetworkSpec`, so this is not a loss of behavioural coverage — only of UI coverage for buttons that are gone. Keep any test that asserts on tree rendering or dirty markers.
- In `JewelExplorerSpec`: `openRootMenu()` and the dropdown-region pixel probes now target the kebab menu instead. Update `openRootMenu()` to click the kebab's position — with the tab strip gone the toolbar is the first row of the panel, so the kebab sits near `(14, 12)`. Rename the helper to `openKebabMenu()`. Keep the `PanelPixelProbe.menuRegionDiffCount` assertions: they still discriminate "a menu card painted" from "no menu", which is exactly what the kebab needs to prove.
- In `ProjectExplorerSpec`: update any assertion that a capture is menu-free to use the same new coordinates.

- [ ] **Step 8: Run the client tests**

Run (foreground, `timeout: 600000`): `cmd.exe /c "gradlew.bat :26.2:runClientTest"`
Expected: all specs pass, `ComposeSurface.disabled` stays false throughout (a wrong icon key would render magenta, not throw — check the `JewelExplorerSpec` screenshots under `versions/26.2/run/screenshots/` and confirm three toolbar icons are drawn, not magenta squares).

- [ ] **Step 9: Commit**

```bash
git add src/client/kotlin/com/breadmoirai/garnet/client/ide src/clientTest/kotlin/com/breadmoirai/garnet/test
git commit -m "feat(explorer): replace the action rows with a real toolbar

Kebab overflow (Open Folder), Refresh, and Collapse All, in one row. Drops
the root-name Dropdown and the +New/Save/Discard controls; the structure
save and discard packets keep their gametest coverage."
```

---

### Task 4: The tree gains its root node

**Files:**
- Modify: `src/client/kotlin/com/breadmoirai/garnet/client/ide/ExplorerTreeState.kt:62-64`
- Test: `src/clientTest/kotlin/com/breadmoirai/garnet/test/ExplorerTreeStateSpec.kt`

**Interfaces:**
- Consumes: `ExplorerTreeState.collapseAll()` from Task 3.
- Produces: `ExplorerTreeState.buildTreeFrom(root: FolderNode): Tree<FileTreeNode>` — signature unchanged, but the returned tree now has exactly one top-level element whose id is `""` and whose data is the root folder. `ExplorerTreeState.ROOT_PATH: String = ""`.

- [ ] **Step 1: Write the failing test**

Add to `src/clientTest/kotlin/com/breadmoirai/garnet/test/ExplorerTreeStateSpec.kt`:

```kotlin
test("buildTreeFrom emits the project root as the single top-level node") {
    val root = FolderNode("myproject", listOf(
        FolderNode("adders", listOf(FileNode("full.spec.kts", "kts"))),
        FileNode("clock.nbt", "nbt"),
    ))

    val tree = ExplorerTreeState.buildTreeFrom(root)

    tree.roots.size shouldBe 1
    val rootElement = tree.roots.single()
    ExplorerTreeState.pathOf(rootElement) shouldBe ExplorerTreeState.ROOT_PATH
    rootElement.data shouldBe root

    // Tree.Element.Node.children is lazy — open() materializes it.
    val node = rootElement as Tree.Element.Node<com.breadmoirai.garnet.project.FileTreeNode>
    node.open()
    node.children!!.map { ExplorerTreeState.pathOf(it) } shouldBe listOf("adders", "clock.nbt")
}
```

Imports needed: `com.breadmoirai.garnet.project.FileNode`, `com.breadmoirai.garnet.project.FolderNode`, `org.jetbrains.jewel.foundation.lazy.tree.Tree`.

- [ ] **Step 2: Run to verify it fails**

Run: `cmd.exe /c "gradlew.bat :26.2:clientTestClasses"`
Expected: FAIL — `Unresolved reference: ROOT_PATH`. (After adding `ROOT_PATH` it would then fail on `tree.roots.size shouldBe 1`, since today the roots are the root's *children*.)

- [ ] **Step 3: Emit the root node**

In `ExplorerTreeState.kt`, add the constant inside the object and replace `buildTreeFrom`:

```kotlin
    /** The tree id of the project root itself. `FolderNode.resolve("")` and
     *  `ProjectRoot.resolveSubpath("")` both already mean "the root", so this needs no translation. */
    const val ROOT_PATH: String = ""
```

```kotlin
    /**
     * Convert a snapshot root into a Jewel [Tree]. The root folder is emitted as the single
     * top-level node under id [ROOT_PATH] (`""`), with its children nested beneath — so the root's
     * name is visible and the root is itself a right-click target for "create at project root".
     */
    fun buildTreeFrom(root: FolderNode): Tree<FileTreeNode> = buildTree {
        addNode(root, ROOT_PATH) {
            root.children.forEach { child -> addFileTreeNode(child, child.name) }
        }
    }
```

- [ ] **Step 4: Expand the root by default**

In `ProjectExplorerPanel.kt`, inside `ProjectExplorer()` immediately after the `val tree = remember(snap.root) { ... }` line, add:

```kotlin
                // The root node carries the project name and is the "create at root" target, so it
                // is useless collapsed. Keyed on the root so a genuinely new project re-opens it,
                // while a user who collapses it during a session keeps it collapsed.
                LaunchedEffect(snap.root) {
                    ExplorerTreeState.treeState.openNodes += ExplorerTreeState.ROOT_PATH
                }
```

Add `import androidx.compose.runtime.LaunchedEffect`.

- [ ] **Step 5: Run the test to verify it passes**

Run: `cmd.exe /c "gradlew.bat :26.2:clientClasses :26.2:clientTestClasses"` then (foreground, `timeout: 600000`) `cmd.exe /c "gradlew.bat :26.2:runClientTest"`
Expected: the new test passes.

Other specs will need coordinate fixes: every tree row is now one row lower and one indent level deeper. `JewelExplorerSpec` and `ProjectExplorerSpec` click tree rows by coordinate — shift each click down by one row height and adjust any expectation that the first visible row is a child of the root.

- [ ] **Step 6: Commit**

```bash
git add src/client/kotlin/com/breadmoirai/garnet/client/ide src/clientTest/kotlin/com/breadmoirai/garnet/test
git commit -m "feat(explorer): show the project root as the tree's root node

Restores the root name lost with the Dropdown, and gives 'New > Folder' a
right-click target meaning 'at the project root'. Id is \"\", which both
FolderNode.resolve and ProjectRoot.resolveSubpath already read as the root."
```

---

## Phase 2 — Context menu and inline editing

### Task 5: Packets for folder creation, targeted structure creation, and rename

**Files:**
- Modify: `src/main/kotlin/com/breadmoirai/garnet/network/project/ProjectPackets.kt`
- Modify: `src/main/kotlin/com/breadmoirai/garnet/network/project/ProjectNetworkRegistry.kt:41,73`
- Test: `src/test/kotlin/com/breadmoirai/garnet/network/StructurePacketsTest.kt`

**Interfaces:**
- Consumes: nothing from phase 1.
- Produces:
  - `NewStructureC2S(parentSubpath: String, name: String)` — **reshaped**, was `NewStructureC2S(name)`.
  - `CreateFolderC2S(parentSubpath: String, name: String)`
  - `RenamePathC2S(subpath: String, newName: String)`
  - Each with `TYPE`, `STREAM_CODEC`, and `type()` following the file's existing pattern.

- [ ] **Step 1: Write the failing codec tests**

In `src/test/kotlin/com/breadmoirai/garnet/network/StructurePacketsTest.kt`, replace the existing `NewStructureC2S` round-trip test and add two more:

```kotlin
    test("NewStructureC2S codec round-trips") {
        val buf = Unpooled.buffer()
        val orig = NewStructureC2S("redstone/clocks", "gadget")
        NewStructureC2S.STREAM_CODEC.encode(buf, orig)
        NewStructureC2S.STREAM_CODEC.decode(buf) shouldBe orig
    }

    test("CreateFolderC2S codec round-trips") {
        val buf = Unpooled.buffer()
        val orig = CreateFolderC2S("redstone", "clocks")
        CreateFolderC2S.STREAM_CODEC.encode(buf, orig)
        CreateFolderC2S.STREAM_CODEC.decode(buf) shouldBe orig
    }

    test("CreateFolderC2S round-trips an empty parent (the project root)") {
        val buf = Unpooled.buffer()
        val orig = CreateFolderC2S("", "toplevel")
        CreateFolderC2S.STREAM_CODEC.encode(buf, orig)
        CreateFolderC2S.STREAM_CODEC.decode(buf) shouldBe orig
    }

    test("RenamePathC2S codec round-trips") {
        val buf = Unpooled.buffer()
        val orig = RenamePathC2S("redstone/clock.nbt", "ring-clock.nbt")
        RenamePathC2S.STREAM_CODEC.encode(buf, orig)
        RenamePathC2S.STREAM_CODEC.decode(buf) shouldBe orig
    }
```

Add imports for `CreateFolderC2S` and `RenamePathC2S`.

- [ ] **Step 2: Run to verify it fails**

Run: `cmd.exe /c "gradlew.bat :26.2:testClasses"`
Expected: FAIL — `Unresolved reference: CreateFolderC2S`, and `NewStructureC2S` argument count mismatch.

- [ ] **Step 3: Reshape NewStructureC2S and add the two payloads**

In `ProjectPackets.kt`, replace the `NewStructureC2S` declaration with:

```kotlin
/** Create an empty `<name>.nbt` inside [parentSubpath] (`""` = the project root). */
data class NewStructureC2S(val parentSubpath: String, val name: String) : CustomPacketPayload {
    companion object {
        val TYPE = CustomPacketPayload.Type<NewStructureC2S>(id("new_structure"))
        val STREAM_CODEC: StreamCodec<ByteBuf, NewStructureC2S> = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, NewStructureC2S::parentSubpath,
            ByteBufCodecs.STRING_UTF8, NewStructureC2S::name,
            ::NewStructureC2S,
        )
    }
    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE
}

/** Create a folder named [name] inside [parentSubpath] (`""` = the project root). */
data class CreateFolderC2S(val parentSubpath: String, val name: String) : CustomPacketPayload {
    companion object {
        val TYPE = CustomPacketPayload.Type<CreateFolderC2S>(id("create_folder"))
        val STREAM_CODEC: StreamCodec<ByteBuf, CreateFolderC2S> = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, CreateFolderC2S::parentSubpath,
            ByteBufCodecs.STRING_UTF8, CreateFolderC2S::name,
            ::CreateFolderC2S,
        )
    }
    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE
}

/** Rename the file or folder at [subpath] to [newName] (a bare name, not a path). */
data class RenamePathC2S(val subpath: String, val newName: String) : CustomPacketPayload {
    companion object {
        val TYPE = CustomPacketPayload.Type<RenamePathC2S>(id("rename_path"))
        val STREAM_CODEC: StreamCodec<ByteBuf, RenamePathC2S> = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, RenamePathC2S::subpath,
            ByteBufCodecs.STRING_UTF8, RenamePathC2S::newName,
            ::RenamePathC2S,
        )
    }
    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE
}
```

- [ ] **Step 4: Register the two new payloads**

In `ProjectNetworkRegistry.kt`, beside the existing `NewStructureC2S` registration on line 41:

```kotlin
        PayloadTypeRegistry.serverboundPlay().register(CreateFolderC2S.TYPE, CreateFolderC2S.STREAM_CODEC)
        PayloadTypeRegistry.serverboundPlay().register(RenamePathC2S.TYPE, RenamePathC2S.STREAM_CODEC)
```

Do **not** add receivers yet — the handlers land in Tasks 9 and 10. Add the imports for both types.

- [ ] **Step 5: Fix the one existing caller**

`ProjectExplorerPanel.kt`'s `NewStructureC2S(name)` call site was deleted in Task 3, so the only remaining caller is `src/gametest/.../ProjectStructureNetworkSpec.kt:135`. Change it to `NewStructureC2S("", "fresh")` for now — Task 9 rewrites this spec properly. This keeps the gametest source set compiling.

- [ ] **Step 6: Run the tests to verify they pass**

Run: `cmd.exe /c "gradlew.bat :26.2:test"`
Then read `versions/26.2/build/test-results/test/*.xml` — do not trust a `--tests` filter, it reports false "no tests found" with Kotest.
Expected: `StructurePacketsTest` passes all four round-trips.

- [ ] **Step 7: Compile every source set**

Run: `cmd.exe /c "gradlew.bat :26.2:clientClasses :26.2:classes :26.2:gametestClasses :26.2:clientTestClasses :26.2:testClasses"`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 8: Commit**

```bash
git add src/main/kotlin/com/breadmoirai/garnet/network/project src/test/kotlin/com/breadmoirai/garnet/network/StructurePacketsTest.kt src/gametest
git commit -m "feat(net): add CreateFolderC2S/RenamePathC2S, target NewStructureC2S at a folder

NewStructureC2S gains a parentSubpath so creation can target the folder the
user right-clicked rather than the session's active folder. Handlers land
in a follow-up; this commit is payloads plus registration only."
```

---

### Task 6: Shared name validation

**Files:**
- Create: `src/main/kotlin/com/breadmoirai/garnet/project/ProjectNames.kt`
- Create: `src/test/kotlin/com/breadmoirai/garnet/project/ProjectNamesTest.kt`

**Interfaces:**
- Consumes: `FolderNode`, `FileTreeNode` from `com.breadmoirai.garnet.project`.
- Produces:
  - `enum class NewNodeKind { FOLDER, STRUCTURE }`
  - `ProjectNames.resolveFinalName(typed: String, kind: NewNodeKind): String`
  - `ProjectNames.validate(finalName: String, siblings: Collection<String>): String?` — returns null when valid, else a human-readable reason.

- [ ] **Step 1: Write the failing test**

Create `src/test/kotlin/com/breadmoirai/garnet/project/ProjectNamesTest.kt`:

```kotlin
package com.breadmoirai.garnet.project

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

class ProjectNamesTest : FunSpec({

    test("a plain name against no siblings is valid") {
        ProjectNames.validate("clocks", emptyList()) shouldBe null
    }

    test("blank and whitespace-only names are rejected") {
        ProjectNames.validate("", emptyList()).shouldNotBeNull()
        ProjectNames.validate("   ", emptyList()).shouldNotBeNull()
    }

    test("path separators are rejected") {
        ProjectNames.validate("a/b", emptyList()).shouldNotBeNull()
        ProjectNames.validate("a\\b", emptyList()).shouldNotBeNull()
    }

    test("dot and dot-dot are rejected") {
        ProjectNames.validate(".", emptyList()).shouldNotBeNull()
        ProjectNames.validate("..", emptyList()).shouldNotBeNull()
    }

    test("a name matching an existing sibling is rejected, case-insensitively") {
        ProjectNames.validate("clocks", listOf("adders", "clocks")).shouldNotBeNull()
        ProjectNames.validate("CLOCKS", listOf("clocks")).shouldNotBeNull()
    }

    test("resolveFinalName appends .nbt for structures and leaves folders alone") {
        ProjectNames.resolveFinalName("gadget", NewNodeKind.STRUCTURE) shouldBe "gadget.nbt"
        ProjectNames.resolveFinalName("gadget.nbt", NewNodeKind.STRUCTURE) shouldBe "gadget.nbt"
        ProjectNames.resolveFinalName("gadget.NBT", NewNodeKind.STRUCTURE) shouldBe "gadget.NBT"
        ProjectNames.resolveFinalName("clocks", NewNodeKind.FOLDER) shouldBe "clocks"
    }

    test("resolveFinalName trims surrounding whitespace") {
        ProjectNames.resolveFinalName("  clocks  ", NewNodeKind.FOLDER) shouldBe "clocks"
    }

    test("the sibling check runs against the resolved name, not the typed one") {
        // "gadget" resolves to "gadget.nbt", which collides.
        val final = ProjectNames.resolveFinalName("gadget", NewNodeKind.STRUCTURE)
        ProjectNames.validate(final, listOf("gadget.nbt")).shouldNotBeNull()
    }
})
```

- [ ] **Step 2: Run to verify it fails**

Run: `cmd.exe /c "gradlew.bat :26.2:testClasses"`
Expected: FAIL — `Unresolved reference: ProjectNames`.

- [ ] **Step 3: Implement**

Create `src/main/kotlin/com/breadmoirai/garnet/project/ProjectNames.kt`:

```kotlin
package com.breadmoirai.garnet.project

/** What an Explorer "New" action creates. */
enum class NewNodeKind { FOLDER, STRUCTURE }

/**
 * Name rules for Explorer create/rename, shared by the client's pre-commit check and the server's
 * re-check.
 *
 * One implementation on purpose: the client validates against its tree snapshot so an invalid name
 * never leaves the field, but that snapshot can be stale, so the server re-runs the identical rule
 * against the real filesystem. Two copies of this logic would drift into a UI that accepts what the
 * server rejects.
 */
object ProjectNames {

    /** The typed text turned into the actual on-disk name: trimmed, with `.nbt` added for structures. */
    fun resolveFinalName(typed: String, kind: NewNodeKind): String {
        val trimmed = typed.trim()
        if (kind != NewNodeKind.STRUCTURE) return trimmed
        if (trimmed.isEmpty()) return trimmed
        return if (trimmed.substringAfterLast('.', "").equals("nbt", ignoreCase = true)) trimmed
        else "$trimmed.nbt"
    }

    /**
     * Null when [finalName] is a usable name among [siblings], else the reason it is not.
     * [siblings] are the names already present in the destination folder.
     */
    fun validate(finalName: String, siblings: Collection<String>): String? {
        if (finalName.isBlank()) return "name must not be blank"
        if (finalName.contains('/') || finalName.contains('\\')) return "name must not contain a path separator"
        if (finalName == "." || finalName == "..") return "'$finalName' is not a valid name"
        if (siblings.any { it.equals(finalName, ignoreCase = true) }) return "'$finalName' already exists"
        return null
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `cmd.exe /c "gradlew.bat :26.2:test"`
Then read `versions/26.2/build/test-results/test/TEST-com.breadmoirai.garnet.project.ProjectNamesTest.xml`.
Expected: all seven tests pass, zero failures.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/breadmoirai/garnet/project/ProjectNames.kt src/test/kotlin/com/breadmoirai/garnet/project/ProjectNamesTest.kt
git commit -m "feat(project): shared name validation for Explorer create and rename

One rule set for the client pre-check and the server re-check, so the UI
cannot accept a name the server will reject."
```

---

### Task 7: Inline-edit state and the synthetic tree node

**Files:**
- Create: `src/client/kotlin/com/breadmoirai/garnet/client/ide/ExplorerEdit.kt`
- Modify: `src/client/kotlin/com/breadmoirai/garnet/client/ide/ExplorerTreeState.kt`
- Modify: `src/client/kotlin/com/breadmoirai/garnet/client/ide/ProjectExplorerPanel.kt`
- Test: `src/clientTest/kotlin/com/breadmoirai/garnet/test/ExplorerTreeStateSpec.kt`

**Interfaces:**
- Consumes: `NewNodeKind` and `ProjectNames` (Task 6); `ExplorerTreeState.ROOT_PATH` and `buildTreeFrom` (Task 4).
- Produces:
  - `sealed interface ExplorerEdit`, `ExplorerEdit.Creating(parentPath: String, kind: NewNodeKind)`, `ExplorerEdit.Renaming(path: String, original: String)`
  - `ExplorerEdit.pendingIdFor(parentPath: String): String`
  - `ExplorerEdit.isPendingId(id: String): Boolean`
  - `ExplorerTreeState.buildTreeFrom(root: FolderNode, edit: ExplorerEdit?): Tree<FileTreeNode>` — the one-arg overload is replaced by this two-arg form with `edit` defaulted to null.

- [ ] **Step 1: Write the failing test**

Add to `src/clientTest/kotlin/com/breadmoirai/garnet/test/ExplorerTreeStateSpec.kt`:

```kotlin
test("a pending create injects a placeholder row into the target folder") {
    val root = FolderNode("myproject", listOf(
        FolderNode("redstone", listOf(FileNode("clock.nbt", "nbt"))),
    ))
    val edit = ExplorerEdit.Creating("redstone", NewNodeKind.FOLDER)

    val tree = ExplorerTreeState.buildTreeFrom(root, edit)

    val rootNode = tree.roots.single() as Tree.Element.Node<FileTreeNode>
    rootNode.open()
    val redstone = rootNode.children!!.single() as Tree.Element.Node<FileTreeNode>
    redstone.open()
    ExplorerTreeState.pathOf(redstone.children!!.last()) shouldBe ExplorerEdit.pendingIdFor("redstone")
}

test("a pending create at the root injects the placeholder at top level") {
    val root = FolderNode("myproject", listOf(FileNode("clock.nbt", "nbt")))
    val edit = ExplorerEdit.Creating(ExplorerTreeState.ROOT_PATH, NewNodeKind.STRUCTURE)

    val tree = ExplorerTreeState.buildTreeFrom(root, edit)

    val rootNode = tree.roots.single() as Tree.Element.Node<FileTreeNode>
    rootNode.open()
    ExplorerTreeState.pathOf(rootNode.children!!.last()) shouldBe
        ExplorerEdit.pendingIdFor(ExplorerTreeState.ROOT_PATH)
}

test("the pending id can never collide with a real path") {
    ExplorerEdit.isPendingId(ExplorerEdit.pendingIdFor("redstone")).shouldBeTrue()
    ExplorerEdit.isPendingId("redstone/clock.nbt").shouldBeFalse()
    ExplorerEdit.isPendingId(ExplorerTreeState.ROOT_PATH).shouldBeFalse()
}

test("no pending create leaves the tree untouched") {
    val root = FolderNode("myproject", listOf(FileNode("clock.nbt", "nbt")))
    val rootNode = ExplorerTreeState.buildTreeFrom(root, null).roots.single()
        as Tree.Element.Node<FileTreeNode>
    rootNode.open()
    rootNode.children!!.map { ExplorerTreeState.pathOf(it) } shouldBe listOf("clock.nbt")
}
```

Imports needed: `com.breadmoirai.garnet.client.ide.ExplorerEdit`, `com.breadmoirai.garnet.project.NewNodeKind`, `com.breadmoirai.garnet.project.FileTreeNode`, `io.kotest.matchers.booleans.shouldBeTrue`, `io.kotest.matchers.booleans.shouldBeFalse`.

- [ ] **Step 2: Run to verify it fails**

Run: `cmd.exe /c "gradlew.bat :26.2:clientTestClasses"`
Expected: FAIL — `Unresolved reference: ExplorerEdit`.

- [ ] **Step 3: Create the edit state**

Create `src/client/kotlin/com/breadmoirai/garnet/client/ide/ExplorerEdit.kt`:

```kotlin
package com.breadmoirai.garnet.client.ide

import com.breadmoirai.garnet.project.NewNodeKind

/**
 * The Explorer's in-tree text-field state. Exactly one edit can be active at a time.
 *
 * Both variants render as a `TextField` in place of a tree row's label; [Creating] additionally
 * needs a row to exist at all, which is what [pendingIdFor] is for — see
 * [ExplorerTreeState.buildTreeFrom].
 */
sealed interface ExplorerEdit {
    /** Typing the name of a new [kind] to be created inside the folder at [parentPath]. */
    data class Creating(val parentPath: String, val kind: NewNodeKind) : ExplorerEdit

    /** Typing a replacement name for the node at [path], whose current name is [original]. */
    data class Renaming(val path: String, val original: String) : ExplorerEdit

    companion object {
        /**
         * Tree id of the placeholder row for a pending create inside [parentPath].
         *
         * NUL is illegal in a filename on every filesystem this mod supports, so this id can never
         * collide with a real `/`-joined path — which matters because Jewel keys selection and
         * expansion off these ids, and a collision would let the placeholder hijack a real node's
         * state.
         */
        fun pendingIdFor(parentPath: String): String = "$parentPath/\u0000new"

        fun isPendingId(id: String): Boolean = id.endsWith("/\u0000new")
    }
}
```

- [ ] **Step 4: Inject the placeholder in buildTreeFrom**

In `ExplorerTreeState.kt`, replace `buildTreeFrom` and the private `addFileTreeNode` extension:

```kotlin
    /**
     * Convert a snapshot root into a Jewel [Tree]. The root folder is emitted as the single
     * top-level node under id [ROOT_PATH] (`""`), with its children nested beneath.
     *
     * When [edit] is a pending [ExplorerEdit.Creating], a placeholder child is appended to the
     * target folder so the name field renders at the depth and position the new item will occupy.
     * The placeholder's data is a throwaway [FileNode]; only its id is meaningful, and `TreeRow`
     * switches on that id to draw a field instead of a label.
     */
    fun buildTreeFrom(root: FolderNode, edit: ExplorerEdit? = null): Tree<FileTreeNode> {
        val pendingParent = (edit as? ExplorerEdit.Creating)?.parentPath
        return buildTree {
            addNode(root, ROOT_PATH) {
                root.children.forEach { child -> addFileTreeNode(child, child.name, pendingParent) }
                if (pendingParent == ROOT_PATH) addPendingLeaf(ROOT_PATH)
            }
        }
    }
```

```kotlin
/** The placeholder row a pending create renders into. */
private fun TreeGeneratorScope<FileTreeNode>.addPendingLeaf(parentPath: String) {
    addLeaf(FileNode(PENDING_NODE_NAME, ""), ExplorerEdit.pendingIdFor(parentPath))
}

/** Name carried by the placeholder's throwaway FileNode; never displayed (TreeRow draws a field). */
private const val PENDING_NODE_NAME = ""

/**
 * Recursive builder. Both `TreeBuilder` and `ChildrenGeneratorScope` implement [TreeGeneratorScope],
 * so one extension covers every depth. The `id` is the node's path, which is what makes Jewel's
 * selection/expansion sets path-keyed. [pendingParent], when it matches a folder's path, appends
 * that folder's pending-create placeholder after its real children.
 */
private fun TreeGeneratorScope<FileTreeNode>.addFileTreeNode(
    node: FileTreeNode,
    path: String,
    pendingParent: String?,
) {
    when (node) {
        is FolderNode -> addNode(node, path) {
            node.children.forEach { child -> addFileTreeNode(child, "$path/${child.name}", pendingParent) }
            if (pendingParent == path) addPendingLeaf(path)
        }
        is FileNode -> addLeaf(node, path)
    }
}
```

- [ ] **Step 5: Hold the edit state in the panel and widen the remember key**

In `ProjectExplorerPanel.kt`, inside `ProjectExplorer()`:

Add above the `val snap = ...` line:

```kotlin
        var edit by remember { mutableStateOf<ExplorerEdit?>(null) }
```

Replace the `val tree = remember(snap.root) { ExplorerTreeState.buildTreeFrom(snap.root) }` line with:

```kotlin
                // remember(snap.root, edit): buildTreeFrom walks the WHOLE project tree recursively
                // and allocates a fresh Tree, which LazyTree then has to re-flatten. This scope also
                // reads ProjectTreeState.status, which changes on every S2C packet, so an
                // un-remembered call rebuilds the entire tree on each packet. Keyed on the root so a
                // genuinely new snapshot still rebuilds, and on the edit so a pending create's
                // placeholder row appears and disappears.
                val tree = remember(snap.root, edit) { ExplorerTreeState.buildTreeFrom(snap.root, edit) }
```

Add imports `androidx.compose.runtime.getValue`, `androidx.compose.runtime.mutableStateOf`, `androidx.compose.runtime.setValue`.

- [ ] **Step 6: Render the field in TreeRow**

In `ProjectExplorerPanel.kt`, change `TreeRow`'s signature and body:

```kotlin
@Composable
private fun TreeRow(
    node: com.breadmoirai.garnet.project.FileTreeNode,
    path: String,
    currentSubpath: String?,
    edit: ExplorerEdit?,
    onCommit: (String) -> Unit,
    onCancel: () -> Unit,
) {
    val creatingHere = edit is ExplorerEdit.Creating && ExplorerEdit.isPendingId(path)
    val renamingHere = edit is ExplorerEdit.Renaming && edit.path == path
    if (creatingHere || renamingHere) {
        val kindIcon = when {
            edit is ExplorerEdit.Creating && edit.kind == NewNodeKind.FOLDER -> AllIconsKeys.Nodes.Folder
            edit is ExplorerEdit.Creating -> AllIconsKeys.FileTypes.Archive
            node is FolderNode -> AllIconsKeys.Nodes.Folder
            else -> AllIconsKeys.FileTypes.Text
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(kindIcon, contentDescription = null)
            InlineNameField(
                initial = (edit as? ExplorerEdit.Renaming)?.original.orEmpty(),
                onCommit = onCommit,
                onCancel = onCancel,
            )
        }
        return
    }
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
```

Add the field composable at the bottom of the file:

```kotlin
/**
 * The in-tree name field. Enter commits, Escape cancels, and losing focus cancels — an abandoned
 * field must never linger as a phantom row after the user clicks elsewhere in the tree.
 *
 * EditBox-style note does not apply here: this is a Jewel TextField over a Compose TextFieldState,
 * and `setTextAndPlaceCursorAtEnd` does not fire a responder, so seeding [initial] is safe.
 */
@Composable
private fun InlineNameField(initial: String, onCommit: (String) -> Unit, onCancel: () -> Unit) {
    val state = rememberTextFieldState()
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        state.setTextAndPlaceCursorAtEnd(initial)
        focusRequester.requestFocus()
    }
    TextField(
        state = state,
        modifier = Modifier
            .weight(1f)
            .focusRequester(focusRequester)
            .onFocusChanged { if (!it.isFocused) onCancel() }
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.Enter, Key.NumPadEnter -> { onCommit(state.text.toString()); true }
                    Key.Escape -> { onCancel(); true }
                    else -> false
                }
            },
    )
}
```

Imports needed in `ProjectExplorerPanel.kt`: `androidx.compose.foundation.layout.weight`, `androidx.compose.foundation.text.input.rememberTextFieldState`, `androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd`, `androidx.compose.runtime.LaunchedEffect`, `androidx.compose.ui.focus.FocusRequester`, `androidx.compose.ui.focus.focusRequester`, `androidx.compose.ui.focus.onFocusChanged`, `androidx.compose.ui.input.key.Key`, `androidx.compose.ui.input.key.KeyEventType`, `androidx.compose.ui.input.key.key`, `androidx.compose.ui.input.key.onPreviewKeyEvent`, `androidx.compose.ui.input.key.type`, `org.jetbrains.jewel.ui.component.TextField`, `com.breadmoirai.garnet.project.NewNodeKind`.

- [ ] **Step 7: Pass the edit state into the row**

Update the `LazyTree` call in `ProjectExplorer()` so its item lambda forwards the new parameters. Commit is wired to real packets in Task 8; for now cancel-only:

```kotlin
                ) { element ->
                    TreeRow(
                        element.data,
                        ExplorerTreeState.pathOf(element),
                        snap.currentSubpath,
                        edit,
                        onCommit = { edit = null },
                        onCancel = { edit = null },
                    )
                }
```

- [ ] **Step 8: Run the tests**

Run: `cmd.exe /c "gradlew.bat :26.2:clientClasses :26.2:clientTestClasses"` then (foreground, `timeout: 600000`) `cmd.exe /c "gradlew.bat :26.2:runClientTest"`
Expected: the four new `ExplorerTreeStateSpec` tests pass; no other spec regresses.

- [ ] **Step 9: Commit**

```bash
git add src/client/kotlin/com/breadmoirai/garnet/client/ide src/clientTest/kotlin/com/breadmoirai/garnet/test/ExplorerTreeStateSpec.kt
git commit -m "feat(explorer): inline name field rendered in the tree

A pending create injects a NUL-keyed placeholder row into the target folder
so the field appears where the item will; rename swaps a real row's label.
Enter commits, Escape and focus loss cancel."
```

---

### Task 8: The right-click context menu

**Files:**
- Create: `src/client/kotlin/com/breadmoirai/garnet/client/ide/ExplorerContextMenu.kt`
- Create: `src/client/kotlin/com/breadmoirai/garnet/client/ide/ExplorerActions.kt`
- Modify: `src/client/kotlin/com/breadmoirai/garnet/client/ide/ProjectExplorerPanel.kt`
- Create: `src/clientTest/kotlin/com/breadmoirai/garnet/test/ExplorerContextMenuSpec.kt`
- Modify: `src/clientTest/kotlin/com/breadmoirai/garnet/test/ClientTestSentinel.kt`

**Interfaces:**
- Consumes: `ExplorerEdit` (Task 7), `ProjectNames`/`NewNodeKind` (Task 6), `CreateFolderC2S`/`NewStructureC2S`/`RenamePathC2S` (Task 5), secondary pointer events (Task 1).
- Produces:
  - `ExplorerActions.sender: (CustomPacketPayload) -> Unit` — swappable network seam, defaults to `ClientPlayNetworking.send`.
  - `ExplorerActions.resetForTest()`
  - `ExplorerActions.commitCreate(parentPath: String, kind: NewNodeKind, typed: String): String?`
  - `ExplorerActions.commitRename(path: String, typed: String): String?`
  - Both return null on success (packet sent) or a validation reason (nothing sent).
  - `class ExplorerMenuState` with `target: String?`, `anchor: IntOffset`, `open(path, offset)`, `close()`.
  - `@Composable fun ExplorerContextMenu(state: ExplorerMenuState, onNew: (String, NewNodeKind) -> Unit, onRename: (String) -> Unit)`

- [ ] **Step 1: Write the failing test**

Create `src/clientTest/kotlin/com/breadmoirai/garnet/test/ExplorerContextMenuSpec.kt`:

```kotlin
package com.breadmoirai.garnet.test

import com.breadmoirai.garnet.client.ide.ExplorerActions
import com.breadmoirai.garnet.network.project.CreateFolderC2S
import com.breadmoirai.garnet.network.project.NewStructureC2S
import com.breadmoirai.garnet.network.project.RenamePathC2S
import com.breadmoirai.garnet.project.NewNodeKind
import com.breadmoirai.garnet.testing.ClientSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import net.minecraft.network.protocol.common.custom.CustomPacketPayload

class ExplorerContextMenuSpec : ClientSpec({

    fun captureSends(): MutableList<CustomPacketPayload> {
        val sent = mutableListOf<CustomPacketPayload>()
        ExplorerActions.sender = { sent += it }
        return sent
    }

    afterTest { ExplorerActions.resetForTest() }

    test("creating a folder sends CreateFolderC2S with the target parent") {
        val sent = captureSends()
        ExplorerActions.commitCreate("redstone", NewNodeKind.FOLDER, "clocks") shouldBe null
        sent shouldBe listOf(CreateFolderC2S("redstone", "clocks"))
    }

    test("creating a structure appends .nbt and targets the parent") {
        val sent = captureSends()
        ExplorerActions.commitCreate("", NewNodeKind.STRUCTURE, "gadget") shouldBe null
        sent shouldBe listOf(NewStructureC2S("", "gadget.nbt"))
    }

    test("an invalid name sends nothing and reports why") {
        val sent = captureSends()
        ExplorerActions.commitCreate("redstone", NewNodeKind.FOLDER, "  ").shouldNotBeNull()
        ExplorerActions.commitCreate("redstone", NewNodeKind.FOLDER, "a/b").shouldNotBeNull()
        sent.shouldBeEmpty()
    }

    test("renaming sends RenamePathC2S with a bare new name") {
        val sent = captureSends()
        ExplorerActions.commitRename("redstone/clock.nbt", "ring-clock.nbt") shouldBe null
        sent shouldBe listOf(RenamePathC2S("redstone/clock.nbt", "ring-clock.nbt"))
    }

    test("renaming to a path is rejected") {
        val sent = captureSends()
        ExplorerActions.commitRename("redstone/clock.nbt", "a/b.nbt").shouldNotBeNull()
        sent.shouldBeEmpty()
    }
})
```

Register it in `ClientTestSentinel.kt` beside `JewelExplorerSpec::class`:

```kotlin
                        ExplorerContextMenuSpec::class,
```

- [ ] **Step 2: Run to verify it fails**

Run: `cmd.exe /c "gradlew.bat :26.2:clientTestClasses"`
Expected: FAIL — `Unresolved reference: ExplorerActions`.

- [ ] **Step 3: Write the actions seam**

Create `src/client/kotlin/com/breadmoirai/garnet/client/ide/ExplorerActions.kt`:

```kotlin
package com.breadmoirai.garnet.client.ide

import com.breadmoirai.garnet.network.project.CreateFolderC2S
import com.breadmoirai.garnet.network.project.NewStructureC2S
import com.breadmoirai.garnet.network.project.RenamePathC2S
import com.breadmoirai.garnet.project.FolderNode
import com.breadmoirai.garnet.project.NewNodeKind
import com.breadmoirai.garnet.project.ProjectNames
import com.breadmoirai.garnet.project.resolve
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.minecraft.network.protocol.common.custom.CustomPacketPayload

/**
 * Validate-then-send for the Explorer's create/rename actions. Sibling of [RootPickerController]:
 * [sender] is a seam so clientTests can assert on payloads without a live connection.
 *
 * Validation runs here as well as on the server. The client's snapshot can be stale, so the server
 * is authoritative — but pre-checking is what lets the inline field stay open and show an error
 * instead of closing and surfacing a ProjectErrorS2C a round-trip later.
 */
object ExplorerActions {

    var sender: (CustomPacketPayload) -> Unit = { ClientPlayNetworking.send(it) }

    fun resetForTest() {
        sender = { ClientPlayNetworking.send(it) }
    }

    /** Null on success (packet sent), else the reason nothing was sent. */
    fun commitCreate(parentPath: String, kind: NewNodeKind, typed: String): String? {
        val finalName = ProjectNames.resolveFinalName(typed, kind)
        ProjectNames.validate(finalName, siblingsOf(parentPath))?.let { return it }
        sender(
            when (kind) {
                NewNodeKind.FOLDER -> CreateFolderC2S(parentPath, finalName)
                NewNodeKind.STRUCTURE -> NewStructureC2S(parentPath, finalName)
            },
        )
        return null
    }

    /** Null on success (packet sent), else the reason nothing was sent. */
    fun commitRename(path: String, typed: String): String? {
        val finalName = typed.trim()
        // Exclude the node being renamed from its own sibling set, so re-committing an unchanged
        // name is a harmless no-op rather than a bogus "already exists".
        val currentName = path.substringAfterLast('/')
        val siblings = siblingsOf(path.substringBeforeLast('/', "")).filterNot { it == currentName }
        ProjectNames.validate(finalName, siblings)?.let { return it }
        sender(RenamePathC2S(path, finalName))
        return null
    }

    private fun siblingsOf(parentPath: String): List<String> {
        val root = ProjectTreeState.snapshot?.root ?: return emptyList()
        val node = root.resolve(parentPath) as? FolderNode ?: return emptyList()
        return node.children.map { it.name }
    }
}
```

Note on `commitRename`'s parent derivation: `substringBeforeLast('/', "")` yields `""` for a top-level node like `"clock.nbt"`, which is exactly `ROOT_PATH`. That is correct, not a fallback.

- [ ] **Step 4: Run the seam tests**

Run: `cmd.exe /c "gradlew.bat :26.2:clientClasses :26.2:clientTestClasses"` then (foreground, `timeout: 600000`) `cmd.exe /c "gradlew.bat :26.2:runClientTest"`
Expected: all five `ExplorerContextMenuSpec` tests pass.

- [ ] **Step 5: Write the menu**

Create `src/client/kotlin/com/breadmoirai/garnet/client/ide/ExplorerContextMenu.kt`:

```kotlin
package com.breadmoirai.garnet.client.ide

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.PopupPositionProvider
import com.breadmoirai.garnet.project.NewNodeKind
import org.jetbrains.jewel.ui.component.PopupMenu
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.separator

/**
 * Which node was right-clicked and where the menu should appear.
 *
 * Panel-scoped by construction (`remember`-ed in the panel, never a top-level object): a popup layer
 * belongs to the composition that opened it, and the dock composes into a long-lived singleton
 * scene, so a menu held in global state would survive a panel re-mount and repaint over the next
 * one. See DockState.mountEpoch and docs/ui/jewel-widget-layer.md.
 */
class ExplorerMenuState {
    var target: String? by mutableStateOf(null)
        private set
    var anchor: IntOffset by mutableStateOf(IntOffset.Zero)
        private set

    fun open(path: String, offset: IntOffset) {
        target = path
        anchor = offset
    }

    fun close() {
        target = null
    }
}

/**
 * The `New ▸ (Folder | Structure)` / `Rename` menu, anchored at the click point.
 *
 * `New` targets the clicked folder, or a clicked file's parent folder — the IDE convention. `Rename`
 * targets the clicked node itself and is disabled on the project root, which has no parent to be
 * renamed within.
 */
@Composable
fun ExplorerContextMenu(
    state: ExplorerMenuState,
    onNew: (parentPath: String, kind: NewNodeKind) -> Unit,
    onRename: (path: String) -> Unit,
) {
    val target = state.target ?: return
    val parent = newTargetFolderFor(target)
    PopupMenu(
        onDismissRequest = { state.close(); true },
        popupPositionProvider = FixedOffsetPositionProvider(state.anchor),
    ) {
        submenu(submenu = {
            selectableItem(selected = false, onClick = { state.close(); onNew(parent, NewNodeKind.FOLDER) }) {
                Text("Folder")
            }
            selectableItem(selected = false, onClick = { state.close(); onNew(parent, NewNodeKind.STRUCTURE) }) {
                Text("Structure")
            }
        }) {
            Text("New")
        }
        separator()
        selectableItem(
            selected = false,
            enabled = target != ExplorerTreeState.ROOT_PATH,
            onClick = { state.close(); onRename(target) },
        ) {
            Text("Rename")
        }
    }
}

/**
 * The folder a `New` action on [target] should create into: [target] itself when it is a folder,
 * else its parent. Reads the live snapshot rather than guessing from the path shape, since a folder
 * name may legitimately contain a dot.
 */
private fun newTargetFolderFor(target: String): String {
    val root = ProjectTreeState.snapshot?.root ?: return ExplorerTreeState.ROOT_PATH
    val node = root.resolve(target)
    return if (node is com.breadmoirai.garnet.project.FolderNode) target
    else target.substringBeforeLast('/', ExplorerTreeState.ROOT_PATH)
}

/** Places the popup's top-left at a fixed window offset — the recorded right-click point. */
private class FixedOffsetPositionProvider(private val offset: IntOffset) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        // Clamp so a click near the right/bottom edge does not push the menu off-canvas; the dock
        // scene has no desktop window to overflow into.
        val x = offset.x.coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0))
        val y = offset.y.coerceIn(0, (windowSize.height - popupContentSize.height).coerceAtLeast(0))
        return IntOffset(x, y)
    }
}
```

Add `import com.breadmoirai.garnet.project.resolve` to that file.

- [ ] **Step 6: Detect the right-click and host the menu**

In `ProjectExplorerPanel.kt`, inside `ProjectExplorer()` add beside the `edit` state:

```kotlin
        val menu = remember { ExplorerMenuState() }
```

Wrap the tree area so the menu renders over it — add, immediately after the `LazyTree(...)` call and inside the same `Column`:

```kotlin
                ExplorerContextMenu(
                    state = menu,
                    onNew = { parent, kind ->
                        // The field is inside the folder, so the folder has to be open to see it.
                        ExplorerTreeState.treeState.openNodes += parent
                        edit = ExplorerEdit.Creating(parent, kind)
                    },
                    onRename = { path ->
                        edit = ExplorerEdit.Renaming(path, path.substringAfterLast('/'))
                    },
                )
```

Add the right-click detector to `TreeRow`'s outer `Row` in the non-editing branch:

```kotlin
    Row(
        Modifier.pointerInput(path) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent()
                    if (event.type == PointerEventType.Press && event.button == PointerButton.Secondary) {
                        val position = event.changes.first().position
                        event.changes.forEach { it.consume() }
                        onSecondaryClick(path, position)
                    }
                }
            }
        },
        verticalAlignment = Alignment.CenterVertically,
    ) {
```

Add `onSecondaryClick: (String, Offset) -> Unit` as a `TreeRow` parameter, and pass it from the `LazyTree` item lambda:

```kotlin
                        onSecondaryClick = { path, local ->
                            ExplorerTreeState.select(path)
                            // Row-local → window coords: the scene is full-window at Density(1f),
                            // so a row's window position is its layout position. Using the raw local
                            // offset would anchor every menu near the panel's left edge.
                            menu.open(path, IntOffset(local.x.toInt(), local.y.toInt()))
                        },
```

Then convert row-local to window coordinates by adding `.onGloballyPositioned { rowOrigin = it.positionInWindow() }` to the row `Modifier` and offsetting: hold `var rowOrigin by remember { mutableStateOf(Offset.Zero) }` inside `TreeRow` and pass `rowOrigin + local` to `onSecondaryClick`. Without this the menu anchors relative to the row, not the window.

Imports needed: `androidx.compose.ui.geometry.Offset`, `androidx.compose.ui.input.pointer.PointerButton`, `androidx.compose.ui.input.pointer.PointerEventType`, `androidx.compose.ui.input.pointer.pointerInput`, `androidx.compose.ui.layout.onGloballyPositioned`, `androidx.compose.ui.layout.positionInWindow`, `androidx.compose.ui.unit.IntOffset`.

- [ ] **Step 7: Wire commit to the actions**

Replace the placeholder `onCommit = { edit = null }` from Task 7 with real commits, and hold the error for display:

```kotlin
        var editError by remember { mutableStateOf<String?>(null) }
```

```kotlin
                        onCommit = { typed ->
                            val current = edit
                            val failure = when (current) {
                                is ExplorerEdit.Creating ->
                                    ExplorerActions.commitCreate(current.parentPath, current.kind, typed)
                                is ExplorerEdit.Renaming ->
                                    ExplorerActions.commitRename(current.path, typed)
                                null -> null
                            }
                            editError = failure
                            // Keep the field open on failure so the user can fix the name in place.
                            if (failure == null) edit = null
                        },
                        onCancel = { edit = null; editError = null },
```

Surface `editError` by passing it into `TreeRow` → `InlineNameField` and setting `outline = if (error != null) Outline.Error else Outline.None` on the `TextField`. Add `import org.jetbrains.jewel.ui.Outline`.

Also render the reason next to the status line so the failure is readable, not just red:

```kotlin
            val message = editError ?: ProjectTreeState.status
            if (message.isNotEmpty()) Text(message, Modifier.padding(top = 4.dp))
```

replacing the existing `val status = ...` / `if (status.isNotEmpty())` pair.

- [ ] **Step 8: Add the interaction test**

Append to `ExplorerContextMenuSpec`:

```kotlin
    test("a right-click on a tree row opens the menu") {
        val sent = captureSends()
        runOnClient {
            ProjectTreeState.apply(ProjectTreeSnapshotS2C(
                FolderNode("myproject", listOf(FolderNode("redstone", listOf(FileNode("clock.nbt", "nbt"))))),
                null,
            ))
            DockState.leftPanels.clear()
            DockState.leftPanels.add(explorerPanel())
            DockState.setVisible(DockRegion.LEFT, true)
            DockInputRouter.focus(DockRegion.LEFT)
        }
        waitClientTicks(6)

        // Row 1 of the tree body, below the toolbar: the project root node.
        runOnClient {
            DockInputRouter.onGlfwMove(60.0, 40.0)
            DockInputRouter.onGlfwPress(GLFW.GLFW_MOUSE_BUTTON_RIGHT)
        }
        waitClientTicks(2)
        runOnClient { DockInputRouter.onGlfwRelease(GLFW.GLFW_MOUSE_BUTTON_RIGHT) }
        waitClientTicks(8)

        ComposeSurface.disabled.shouldBeFalse()
        sent.shouldBeEmpty()  // opening a menu sends nothing

        runOnClient { DockInputRouter.clearFocus(); DockState.leftPanels.clear() }
    }
```

Add the imports this needs, mirroring `JewelExplorerSpec`'s import block. If `ProjectTreeState`'s snapshot-apply entry point is named differently, use whatever `JewelExplorerSpec` already calls to seed a tree — copy that call verbatim rather than inventing one.

- [ ] **Step 9: Run everything**

Run: `cmd.exe /c "gradlew.bat :26.2:clientClasses :26.2:classes :26.2:gametestClasses :26.2:clientTestClasses :26.2:testClasses"`
Then (foreground, `timeout: 600000`): `cmd.exe /c "gradlew.bat :26.2:runClientTest"`
Expected: every spec passes and `ComposeSurface.disabled` stays false.

- [ ] **Step 10: Commit**

```bash
git add src/client/kotlin/com/breadmoirai/garnet/client/ide src/clientTest/kotlin/com/breadmoirai/garnet/test
git commit -m "feat(explorer): right-click New (Folder|Structure) and Rename

Jewel PopupMenu anchored at the click point via a fixed-offset position
provider, driving the inline field. Commits go through ExplorerActions,
which validates against the snapshot before sending."
```

---

## Phase 3 — Server handlers

### Task 9: Create-folder and folder-targeted structure creation

**Files:**
- Modify: `src/main/kotlin/com/breadmoirai/garnet/network/project/ProjectNetworkRegistry.kt:73,265-284`
- Create: `src/gametest/kotlin/com/breadmoirai/garnet/test/project/ProjectFileOpsNetworkSpec.kt`
- Modify: `src/gametest/kotlin/com/breadmoirai/garnet/test/project/ProjectStructureNetworkSpec.kt:135`
- Modify: `src/gametest/kotlin/com/breadmoirai/garnet/test/GametestSentinel.kt`

**Interfaces:**
- Consumes: `CreateFolderC2S`, `NewStructureC2S` (Task 5); `ProjectNames` (Task 6).
- Produces:
  - `ProjectNetworkRegistry.handleNewStructure(server, player, payload: NewStructureC2S)` — same name, now resolves `payload.parentSubpath`.
  - `ProjectNetworkRegistry.handleCreateFolder(server, player, payload: CreateFolderC2S)`

- [ ] **Step 1: Write the failing test**

Create `src/gametest/kotlin/com/breadmoirai/garnet/test/project/ProjectFileOpsNetworkSpec.kt`. Model its setup (server fixture, temp project root, fake player) on the existing `ProjectStructureNetworkSpec` — read that file first and copy its harness verbatim rather than inventing one. Test bodies run on the server thread via `withContext(McDispatchers.Server)` as `RedstoneTestSpec` requires.

```kotlin
    test("handleCreateFolder creates a folder at the project root") {
        withServer { server, player, root ->
            ProjectNetworkRegistry.handleCreateFolder(server, player, CreateFolderC2S("", "toplevel"))
            root.resolve("toplevel").isDirectory().shouldBeTrue()
        }
    }

    test("handleCreateFolder creates a nested folder") {
        withServer { server, player, root ->
            root.resolve("redstone").createDirectories()
            ProjectNetworkRegistry.handleCreateFolder(server, player, CreateFolderC2S("redstone", "clocks"))
            root.resolve("redstone/clocks").isDirectory().shouldBeTrue()
        }
    }

    test("handleCreateFolder rejects a parent that escapes the root") {
        withServer { server, player, root ->
            ProjectNetworkRegistry.handleCreateFolder(server, player, CreateFolderC2S("../evil", "x"))
            root.resolveSibling("evil").exists().shouldBeFalse()
        }
    }

    test("handleCreateFolder rejects a name containing a separator") {
        withServer { server, player, root ->
            ProjectNetworkRegistry.handleCreateFolder(server, player, CreateFolderC2S("", "a/b"))
            root.resolve("a").exists().shouldBeFalse()
        }
    }

    test("handleNewStructure creates in the named folder, not the session's active folder") {
        withServer { server, player, root ->
            root.resolve("redstone").createDirectories()
            root.resolve("other").createDirectories()
            ProjectSession.setActive(player.uuid, "other")

            ProjectNetworkRegistry.handleNewStructure(server, player, NewStructureC2S("redstone", "gadget.nbt"))

            root.resolve("redstone/gadget.nbt").exists().shouldBeTrue()
            root.resolve("other/gadget.nbt").exists().shouldBeFalse()
        }
    }

    test("handleNewStructure creates at the project root for an empty parent") {
        withServer { server, player, root ->
            ProjectNetworkRegistry.handleNewStructure(server, player, NewStructureC2S("", "gadget.nbt"))
            root.resolve("gadget.nbt").exists().shouldBeTrue()
        }
    }
```

Register the spec in `GametestSentinel.kt` beside `ProjectStructureNetworkSpec::class`:

```kotlin
                            ProjectFileOpsNetworkSpec::class,
```
plus the matching import.

- [ ] **Step 2: Run to verify it fails**

Run: `cmd.exe /c "gradlew.bat :26.2:gametestClasses"`
Expected: FAIL — `Unresolved reference: handleCreateFolder`.

- [ ] **Step 3: Rewrite handleNewStructure to target a folder**

In `ProjectNetworkRegistry.kt`, replace `handleNewStructure` (lines 265-284):

```kotlin
    fun handleNewStructure(server: MinecraftServer, player: ServerPlayer, payload: NewStructureC2S) {
        val folder = resolveParentFolder(server, player, payload.parentSubpath) ?: return
        val finalName = ProjectNames.resolveFinalName(payload.name, NewNodeKind.STRUCTURE)
        ProjectNames.validate(finalName, siblingNames(folder))?.let {
            ServerPlayNetworking.send(player, ProjectErrorS2C(it)); return
        }
        try {
            // ProjectNewStructure.create appends ".nbt" itself, so hand it the bare stem.
            ProjectNewStructure.create(folder, finalName.removeSuffix(".nbt"))
        } catch (e: Exception) {
            LOGGER.error("[project/new-structure] create {}/{}: {}", payload.parentSubpath, finalName, e.message, e)
            ServerPlayNetworking.send(player, ProjectErrorS2C("new-structure failed: ${e.message}")); return
        }
        sendTree(server, player)
    }

    fun handleCreateFolder(server: MinecraftServer, player: ServerPlayer, payload: CreateFolderC2S) {
        val parent = resolveParentFolder(server, player, payload.parentSubpath) ?: return
        val name = payload.name.trim()
        ProjectNames.validate(name, siblingNames(parent))?.let {
            ServerPlayNetworking.send(player, ProjectErrorS2C(it)); return
        }
        try {
            parent.resolve(name).createDirectory()
        } catch (e: Exception) {
            LOGGER.error("[project/create-folder] {}/{}: {}", payload.parentSubpath, name, e.message, e)
            ServerPlayNetworking.send(player, ProjectErrorS2C("create-folder failed: ${e.message}")); return
        }
        sendTree(server, player)
    }

    /**
     * The absolute path of [parentSubpath] under the configured root, or null after sending the
     * player an error. `""` means the root itself, which `resolveSubpath` already handles; anything
     * absolute or escaping the root comes back null from that call and is refused here.
     */
    private fun resolveParentFolder(
        server: MinecraftServer,
        player: ServerPlayer,
        parentSubpath: String,
    ): Path? {
        val root = rootFor(server) ?: run {
            ServerPlayNetworking.send(player, ProjectErrorS2C("project-root not configured")); return null
        }
        val folder = root.resolveSubpath(parentSubpath) ?: run {
            ServerPlayNetworking.send(
                player,
                ProjectErrorS2C("folder not found or escapes root: $parentSubpath"),
            ); return null
        }
        if (!folder.isDirectory()) {
            ServerPlayNetworking.send(player, ProjectErrorS2C("not a folder: $parentSubpath")); return null
        }
        return folder
    }

    /** Names already present in [folder], for the duplicate check. */
    private fun siblingNames(folder: Path): List<String> =
        folder.listDirectoryEntries().map { it.name }
```

Add imports: `com.breadmoirai.garnet.project.NewNodeKind`, `com.breadmoirai.garnet.project.ProjectNames`, `kotlin.io.path.createDirectory`, `kotlin.io.path.isDirectory`, `kotlin.io.path.listDirectoryEntries`, `kotlin.io.path.name`.

Note the behaviour change this locks in: `handleNewStructure` no longer reads `ProjectSession.activeSubpath` at all. The `ProjectWorld.folderAbsoluteByPath` lookup it used goes away with it — folder resolution is now uniformly through `ProjectRoot.resolveSubpath`.

- [ ] **Step 4: Register the receiver**

In `ProjectNetworkRegistry.kt`, beside the existing `NewStructureC2S` receiver at line 73:

```kotlin
        ServerPlayNetworking.registerGlobalReceiver(CreateFolderC2S.TYPE) { payload, ctx ->
            ctx.server().execute { handleCreateFolder(ctx.server(), ctx.player(), payload) }
        }
```

Match the exact shape of the neighbouring receivers — copy one and change the type rather than writing it from memory.

- [ ] **Step 5: Fix the old ProjectStructureNetworkSpec call**

`ProjectStructureNetworkSpec.kt:135` was patched to `NewStructureC2S("", "fresh")` in Task 5. Now that creation targets a folder, make the assertion match: the file should land at the project root as `fresh.nbt`. Update the assertion accordingly.

- [ ] **Step 6: Run the game tests**

Run (foreground, `timeout: 600000`): `cmd.exe /c "gradlew.bat :26.2:runGameTest"`
Expected: `ProjectFileOpsNetworkSpec` passes all six tests; `ProjectStructureNetworkSpec` still passes.

If the world persists between runs and a stale file trips a test, clear the temp root in the fixture's setup — `runGameTest` reuses its world, which is a known source of stale state.

- [ ] **Step 7: Commit**

```bash
git add src/main/kotlin/com/breadmoirai/garnet/network/project/ProjectNetworkRegistry.kt src/gametest
git commit -m "feat(project): create folders, and create structures in a named folder

handleNewStructure now resolves the payload's parentSubpath instead of the
session's active folder, so the Explorer creates where the user clicked.
handleCreateFolder is new. Both re-validate names through ProjectNames."
```

---

### Task 10: Rename, including unload/reload of a placed structure

**Files:**
- Modify: `src/main/kotlin/com/breadmoirai/garnet/project/ProjectDimRegistry.kt`
- Modify: `src/main/kotlin/com/breadmoirai/garnet/network/project/ProjectNetworkRegistry.kt`
- Modify: `src/gametest/kotlin/com/breadmoirai/garnet/test/project/ProjectFileOpsNetworkSpec.kt`

**Interfaces:**
- Consumes: `RenamePathC2S` (Task 5); `ProjectNames` (Task 6); `resolveParentFolder`/`siblingNames` (Task 9).
- Produces:
  - `ProjectDimRegistry.unplaceStructure(subpath: String): PlacedBox?`
  - `ProjectNetworkRegistry.handleRename(server, player, payload: RenamePathC2S)`

- [ ] **Step 1: Write the failing test**

Append to `ProjectFileOpsNetworkSpec.kt`:

```kotlin
    test("handleRename renames a folder") {
        withServer { server, player, root ->
            root.resolve("redstone").createDirectories()
            ProjectNetworkRegistry.handleRename(server, player, RenamePathC2S("redstone", "logic"))
            root.resolve("logic").isDirectory().shouldBeTrue()
            root.resolve("redstone").exists().shouldBeFalse()
        }
    }

    test("handleRename moves a structure's unsaved sidecar with it") {
        withServer { server, player, root ->
            val nbt = root.resolve("clock.nbt")
            ProjectNewStructure.create(root, "clock")
            StructurePersistence.unsavedSidecarOf(nbt).writeBytes(byteArrayOf(1, 2, 3))

            ProjectNetworkRegistry.handleRename(server, player, RenamePathC2S("clock.nbt", "ring.nbt"))

            root.resolve("ring.nbt").exists().shouldBeTrue()
            StructurePersistence.unsavedSidecarOf(root.resolve("ring.nbt")).exists().shouldBeTrue()
            StructurePersistence.unsavedSidecarOf(nbt).exists().shouldBeFalse()
        }
    }

    test("handleRename rejects a new name that already exists") {
        withServer { server, player, root ->
            root.resolve("a").createDirectories()
            root.resolve("b").createDirectories()
            ProjectNetworkRegistry.handleRename(server, player, RenamePathC2S("a", "b"))
            root.resolve("a").isDirectory().shouldBeTrue()   // untouched
        }
    }

    test("handleRename rejects a new name containing a separator") {
        withServer { server, player, root ->
            root.resolve("a").createDirectories()
            ProjectNetworkRegistry.handleRename(server, player, RenamePathC2S("a", "x/y"))
            root.resolve("a").isDirectory().shouldBeTrue()
        }
    }

    test("renaming a placed structure unloads it and reloads it under the new name") {
        withServer { server, player, root ->
            ProjectNewStructure.create(root, "clock")
            ProjectNetworkRegistry.handlePlaceStructure(server, player, PlaceStructureC2S("clock.nbt"))
            val registry = ProjectDimRegistry.of(server)
            registry.placedBoxOf("clock.nbt").shouldNotBeNull()

            ProjectNetworkRegistry.handleRename(server, player, RenamePathC2S("clock.nbt", "ring.nbt"))

            registry.placedBoxOf("clock.nbt").shouldBeNull()
            registry.placedBoxOf("ring.nbt").shouldNotBeNull()
            registry.structureRegionOriginOf("clock.nbt").shouldBeNull()
        }
    }

    test("renaming an ancestor folder repoints the active session") {
        withServer { server, player, root ->
            root.resolve("redstone/clocks").createDirectories()
            ProjectSession.setActive(player.uuid, "redstone/clocks")

            ProjectNetworkRegistry.handleRename(server, player, RenamePathC2S("redstone", "logic"))

            ProjectSession.get(player.uuid)!!.activeSubpath shouldBe "logic/clocks"
        }
    }
```

- [ ] **Step 2: Run to verify it fails**

Run: `cmd.exe /c "gradlew.bat :26.2:gametestClasses"`
Expected: FAIL — `Unresolved reference: handleRename`.

- [ ] **Step 3: Add unplaceStructure**

In `ProjectDimRegistry.kt`, below `placedStructureSubpaths()` (line 89):

```kotlin
    /**
     * Forget everything about a placed structure: its footprint record and its region assignment.
     * Returns the removed [PlacedBox] so the caller can clear those blocks, or null if it was not
     * placed.
     *
     * The freed region index is **not** recycled — [nextStructureIndex] is monotonic, so a structure
     * re-placed after this lands in a fresh region. That matches how every other assignment in this
     * registry behaves and keeps region identity from being reused while blocks may still linger.
     */
    fun unplaceStructure(subpath: String): PlacedBox? {
        structureBySubpath.remove(subpath)
        return placedBoxes.remove(subpath)
    }
```

- [ ] **Step 4: Implement handleRename**

In `ProjectNetworkRegistry.kt`, beside `handleCreateFolder`:

```kotlin
    fun handleRename(server: MinecraftServer, player: ServerPlayer, payload: RenamePathC2S) {
        val root = rootFor(server) ?: run {
            ServerPlayNetworking.send(player, ProjectErrorS2C("project-root not configured")); return
        }
        val source = root.resolveSubpath(payload.subpath) ?: run {
            ServerPlayNetworking.send(
                player,
                ProjectErrorS2C("path not found or escapes root: ${payload.subpath}"),
            ); return
        }
        if (payload.subpath.isEmpty()) {
            ServerPlayNetworking.send(player, ProjectErrorS2C("cannot rename the project root")); return
        }
        val newName = payload.newName.trim()
        val parent = source.parent
        // Exclude the node itself so re-committing an unchanged name is a no-op, not a collision.
        val siblings = siblingNames(parent).filterNot { it == source.name }
        ProjectNames.validate(newName, siblings)?.let {
            ServerPlayNetworking.send(player, ProjectErrorS2C(it)); return
        }

        val parentSubpath = payload.subpath.substringBeforeLast('/', "")
        val newSubpath = if (parentSubpath.isEmpty()) newName else "$parentSubpath/$newName"

        // A placed structure is keyed by subpath in ProjectDimRegistry, so renaming under it would
        // strand both the placed box and the region assignment. Unload first, re-place after.
        val registry = ProjectDimRegistry.of(server)
        val wasPlaced = registry.placedBoxOf(payload.subpath)
        if (wasPlaced != null) {
            StructurePersistence.clearBounds(registry.projectLevel(), wasPlaced.origin, wasPlaced.size)
            registry.unplaceStructure(payload.subpath)
        }

        val target = parent.resolve(newName)
        try {
            source.moveTo(target)
            // The dirty buffer lives beside the .nbt as "<name>.nbt.unsaved"; leaving it behind
            // would silently detach a structure's unsaved edits from the structure.
            val sidecar = StructurePersistence.unsavedSidecarOf(source)
            if (sidecar.exists()) sidecar.moveTo(StructurePersistence.unsavedSidecarOf(target))
        } catch (e: Exception) {
            LOGGER.error("[project/rename] {} -> {}: {}", payload.subpath, newSubpath, e.message, e)
            ServerPlayNetworking.send(player, ProjectErrorS2C("rename failed: ${e.message}")); return
        }

        repointSession(player, payload.subpath, newSubpath)

        if (wasPlaced != null) {
            placeStructureFrom(server, player, newSubpath, target, hasUnsaved = false, message = "renamed to $newSubpath")
        }
        sendTree(server, player)
    }

    /**
     * Keep a loaded project reachable after one of its ancestors is renamed: an activeSubpath equal
     * to [oldSubpath], or nested under it, is rewritten onto [newSubpath].
     */
    private fun repointSession(player: ServerPlayer, oldSubpath: String, newSubpath: String) {
        val active = ProjectSession.get(player.uuid)?.activeSubpath ?: return
        when {
            active == oldSubpath -> ProjectSession.setActive(player.uuid, newSubpath)
            active.startsWith("$oldSubpath/") ->
                ProjectSession.setActive(player.uuid, newSubpath + active.removePrefix(oldSubpath))
        }
    }
```

Add imports: `com.breadmoirai.garnet.persistence.StructurePersistence` (if not already present), `com.breadmoirai.garnet.project.ProjectDimRegistry` (likewise), `kotlin.io.path.exists`, `kotlin.io.path.moveTo`, `kotlin.io.path.name`.

Note on `hasUnsaved = false`: the re-place reads the freshly moved `.nbt`, and the sidecar moved with it, so the flag reported to the client reflects a clean load of the renamed file. If the sidecar existed, the next world-save flush re-derives it.

- [ ] **Step 5: Register the receiver**

Beside the `CreateFolderC2S` receiver from Task 9:

```kotlin
        ServerPlayNetworking.registerGlobalReceiver(RenamePathC2S.TYPE) { payload, ctx ->
            ctx.server().execute { handleRename(ctx.server(), ctx.player(), payload) }
        }
```

- [ ] **Step 6: Run the game tests**

Run (foreground, `timeout: 600000`): `cmd.exe /c "gradlew.bat :26.2:runGameTest"`
Expected: all twelve `ProjectFileOpsNetworkSpec` tests pass.

The placed-structure test is the one most likely to be flaky: `runGameTest` reuses its world, so a structure placed by an earlier run can leave blocks behind. If it fails, clear the structure lane in the fixture's setup before placing, and read block state directly rather than relying on a `setBlock` mixin firing.

- [ ] **Step 7: Compile every source set**

Run: `cmd.exe /c "gradlew.bat :26.2:clientClasses :26.2:classes :26.2:gametestClasses :26.2:clientTestClasses :26.2:testClasses"`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 8: Commit**

```bash
git add src/main/kotlin/com/breadmoirai/garnet src/gametest
git commit -m "feat(project): rename files and folders

Moves the .nbt.unsaved sidecar with its structure, repoints an active
session whose folder was renamed, and unloads/reloads a currently-placed
structure so ProjectDimRegistry never holds a stale subpath key."
```

---

### Task 11: End-to-end verification and documentation

**Files:**
- Modify: `docs/ui/dock-framework.md`
- Modify: `docs/ui/dock-input-routing.md`
- Modify: `docs/ui/jewel-widget-layer.md`
- Create: `docs/ui/explorer-toolbar-and-context-menu.md`
- Modify: `docs/ui/INDEX.md`
- Modify: `docs/persistence/INDEX.md` and the article covering project packets

**Interfaces:**
- Consumes: everything.
- Produces: no code.

- [ ] **Step 1: Run the whole suite**

Run in order, each foreground with `timeout: 600000`:

```
cmd.exe /c "gradlew.bat :26.2:clientClasses :26.2:classes :26.2:gametestClasses :26.2:clientTestClasses :26.2:testClasses"
cmd.exe /c "gradlew.bat :26.2:test"
cmd.exe /c "gradlew.bat :26.2:runGameTest"
cmd.exe /c "gradlew.bat :26.2:runClientTest"
```

Read `versions/26.2/build/test-results/test/*.xml` for the unit run. Read console output for the two game runs.
Expected: zero failures across all four. Do not proceed to docs with a red suite — fix first.

- [ ] **Step 2: Inspect the screenshots**

Open the PNGs `JewelExplorerSpec` wrote under `versions/26.2/run/screenshots/`. Confirm by eye:
- Three toolbar icons render as artwork, not magenta squares (magenta = the icons artifact is missing or a key name is wrong; it degrades silently rather than throwing).
- No tab strip is drawn above the panel.
- The tree shows the project root as its top row.

- [ ] **Step 3: Update the dock articles**

- `docs/ui/dock-framework.md`: replace the tab-strip description with "regions render the active panel body directly"; keep the `mountEpoch` section, which is unchanged and now also guards the context menu. Fix any `GarnetDock.kt:NN` line citations that moved.
- `docs/ui/dock-input-routing.md`: add that GLFW mouse buttons are now mapped through `glfwMouseButtonToPointerButton` and delivered as `PointerButton`, and that buttons beyond the first three are dropped rather than coerced to Primary.
- `docs/ui/jewel-widget-layer.md`: in "Popups render in-scene", note `PopupMenu`'s two overloads (horizontal-alignment vs `PopupPositionProvider`) and that `MenuScope.submenu` gives native nesting. In "Tree state is Jewel's", record that the root node's id is `""` and that a pending create injects a NUL-keyed placeholder id. Replace the "`+ Structure` name field" paragraph, which described a control that no longer exists, with the inline-field model.

- [ ] **Step 4: Write the new article**

Create `docs/ui/explorer-toolbar-and-context-menu.md` with the required frontmatter:

```markdown
---
title: Explorer toolbar and context menu
tags: [explorer, toolbar, context-menu, inline-edit, jewel, packets]
summary: The Project Explorer's kebab/Refresh/Collapse-All toolbar, its right-click New/Rename menu, and the inline name field that commits through ExplorerActions.
---
```

Cover what the code does not show on its own: why validation is duplicated client and server (stale snapshots), why the placeholder id uses NUL, why `ExplorerMenuState` must be panel-scoped rather than a top-level object, and why renaming a placed structure unloads and reloads it. Do not restate the code.

- [ ] **Step 5: Register the article and audit cross-references**

- Add to `docs/ui/INDEX.md`: `- [Explorer toolbar and context menu](explorer-toolbar-and-context-menu.md) — <the summary line>` with its tags.
- Update the `docs/persistence/` article covering project packets with `CreateFolderC2S`, `RenamePathC2S`, and `NewStructureC2S`'s new shape; update `docs/persistence/INDEX.md` if its summary line no longer matches.
- Run `grep -rn "StructureActions\|RootMenu\|tab strip\|TAB_BG\|NewStructureC2S(" docs/` and fix every hit that describes the old behaviour. Leave `docs/superpowers/specs/` and `docs/superpowers/plans/` alone — those are commit-time snapshots, not living docs.
- Confirm every `[link](path.md)` you touched resolves to a real file.

- [ ] **Step 6: Commit**

```bash
git add docs/
git commit -m "docs(ui): Explorer toolbar, context menu, and inline editing

Adds the new article, records the pointer-button routing and PopupMenu
usage, and retires the tab-strip and +New/Save/Discard descriptions."
```

---

## Self-Review Notes

Checked against the spec:

- **§1 pointer buttons** → Task 1. **§2 tab strip** → Task 2. **§3 toolbar** → Task 3. **§4 root node** → Task 4. **§5 context menu** → Task 8. **§6 inline editing** → Tasks 6 (validation) and 7 (state, synthetic node, field). **§7 packets/handlers** → Tasks 5, 9, 10. **Accepted regressions** → Task 3 Step 7 deletes the Save/Discard UI tests and says why; `selectedHasUnsaved()` is left untouched by every task, as the spec requires. **Testing section** → Tasks 1, 3, 4, 6, 7, 8 (client + unit), 9, 10 (gametest). **Docs section** → Task 11.
- **Name consistency verified across tasks:** `ROOT_PATH`, `collapseAll()`, `buildTreeFrom(root, edit)`, `pendingIdFor`/`isPendingId`, `ExplorerEdit.Creating`/`Renaming`, `NewNodeKind.FOLDER`/`STRUCTURE`, `ProjectNames.resolveFinalName`/`validate`, `ExplorerActions.commitCreate`/`commitRename`/`sender`/`resetForTest`, `ExplorerMenuState.open`/`close`/`target`/`anchor`, `unplaceStructure`, `handleCreateFolder`/`handleRename`/`handleNewStructure`, `resolveParentFolder`/`siblingNames`/`repointSession`, `glfwMouseButtonToPointerButton`.
- **Known API risk, flagged where it lands:** Jewel's `PopupMenu` `onDismissRequest` is `(InputMode) -> Boolean`; Task 3 Step 6 tells the implementer what to write if the zero-arg lambda does not compile. `Tree.Element.Node.children` is lazy — Task 4 and Task 7 tests call `open()` before reading it, per the documented gotcha.
