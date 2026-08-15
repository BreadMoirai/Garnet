# Dock Stripe and Structure Info Panel Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the dock's per-region visibility and tab strip with a JetBrains-style icon stripe driving per-panel visibility, and split structure metadata out of the Explorer's status line into its own LEFT panel.

**Architecture:** `DockState`'s four per-region panel lists collapse into one flat `panels` registry where each `Panel` declares its own region and stripe icon; region visibility becomes derived from an `openPanel: region -> panel id` map mutated by a single `togglePanel(id)`. A 32px `DockStripe` composable, drawn last and visible only while `anyActive()`, renders one icon per LEFT panel and is the primary control. `ProjectTreeState.status` is deleted and its five network receivers repoint at a new `StructureInfoState` holding structured fields, rendered by a third LEFT panel.

**Tech Stack:** Kotlin, Compose Multiplatform 1.11.0 (desktop), JetBrains Jewel `0.39.1-262.9437.29`, Fabric for Minecraft 26.2, Stonecutter, Kotest.

**Spec:** `docs/superpowers/specs/2026-08-15-dock-stripe-and-structure-info-design.md`

## Global Constraints

- **Invoke Gradle through the Windows batch wrapper, never `./gradlew`.** All commands take the form
  `cmd.exe /c "cd /d H:\\Repo\\Garnet && gradlew.bat <tasks>"`. See `docs/tooling/wsl2-gradle-invocation.md`.
- **Stonecutter task paths are `:26.2:<task>`**, not `:versions:26.2:<task>`.
- **Compile verification must cover all five source sets:**
  `:26.2:clientClasses :26.2:classes :26.2:gametestClasses :26.2:clientTestClasses :26.2:testClasses`.
  `compileKotlin` alone only covers `main`.
- **Gradle's `--tests` filter does not select Kotest specs.** Run `:26.2:test` unfiltered and read
  `versions/26.2/build/test-results/test/TEST-<fully.qualified.ClassName>.xml`.
- **No protocol changes.** No file under `src/main/kotlin/.../network/` is edited by this plan.
  `StructureAutoSavedS2C` already carries `savedAtMillis`; `StructureResultS2C` already carries `sizeX/Y/Z`.
- **No glyphs in panel text.** Jewel's default family is Inter, which has no emoji coverage; a glyph
  falls through to whatever Skia finds on the host and renders as tofu. Use ASCII `x`, never `U+00D7`.
- **No panel-local state in top-level objects.** Anything panel-local is `remember`-ed inside the
  composable, or it survives a re-mount and paints over the next one (`DockState.mountEpoch`).
- **Commit messages carry no `Co-Authored-By` or "Generated with" trailer.**
- Work happens on branch `feat/dock-stripe-structure-info`, already created.

## File Structure

**Created:**

| File | Responsibility |
|---|---|
| `src/client/kotlin/.../ui/dock/DockStripe.kt` | The icon stripe composable and its `STRIPE_WIDTH` constant |
| `src/client/kotlin/.../editor/ui/StructureInfoState.kt` | Structured structure metadata + the moved transient status string |
| `src/client/kotlin/.../editor/ui/StructureInfoPanel.kt` | The Structure Info panel body and its `Panel` factory |
| `src/client/kotlin/.../editor/ui/TimeFormat.kt` | `formatClock(millis)`, shared by Local History and Structure Info |
| `src/test/kotlin/.../client/ui/dock/DockPanelVisibilityTest.kt` | `togglePanel` semantics |
| `src/test/kotlin/.../client/ui/dock/DockMountEpochTest.kt` | Epoch bumps on panel switch, not just close |
| `src/test/kotlin/.../client/ui/dock/DockStripeGeometryTest.kt` | Stripe's contribution to insets and hit test |
| `src/test/kotlin/.../client/ui/dock/DockLayoutStoreTest.kt` | Store round trip, legacy migration, bad input |
| `src/test/kotlin/.../client/editor/ui/StructureInfoStateTest.kt` | Receiver-to-field mapping |
| `docs/ui/dock-stripe.md`, `docs/ui/structure-info-panel.md` | New articles |

**Deleted:**

| File | Why |
|---|---|
| `src/client/kotlin/.../ui/dock/DockTabStrip.kt` | One panel open per region leaves it nothing to render |
| `src/test/kotlin/.../client/ui/dock/DockTabStateTest.kt` | Asserts on the deleted `activeTab`/`setActiveTab` |
| `src/test/kotlin/.../client/editor/ui/StructureExplorerStatusTest.kt` | Asserts on the deleted `ProjectTreeState.status` |

**Modified:** `Panel.kt`, `DockState.kt`, `GarnetDock.kt`, `DockInsets.kt`, `DockHitTest.kt`,
`DockAutoOpen.kt`, `DockLayoutStore.kt`, `viewport/DockKeybinds.kt`, `GarnetClient.kt`,
`ProjectTreeState.kt`, `ProjectExplorerPanel.kt`, `LocalHistoryPanel.kt`,
`EditorClientNetworking.kt`, `ExplorerLifecycle.kt`, `DockHitTestTest.kt`, `DockInsetsTest.kt`,
`DockLifecycleTest.kt`, `DockAutoOpenTest.kt`, `JewelExplorerSpec.kt`, and the docs listed in Task 8.

---

### Task 1: `Panel` declares its region and stripe icon

Purely additive — every call site is updated in the same task, so the tree compiles at the end of it
with no behaviour change.

**Files:**
- Modify: `src/client/kotlin/com/breadmoirai/garnet/ui/dock/Panel.kt`
- Modify: `src/client/kotlin/com/breadmoirai/garnet/editor/ui/ProjectExplorerPanel.kt:66`
- Modify: `src/client/kotlin/com/breadmoirai/garnet/editor/ui/LocalHistoryPanel.kt:36`
- Modify: `src/test/kotlin/com/breadmoirai/garnet/client/ui/dock/DockHitTestTest.kt:91`
- Modify: `src/test/kotlin/com/breadmoirai/garnet/client/ui/dock/DockLifecycleTest.kt:17-18`
- Modify: `src/test/kotlin/com/breadmoirai/garnet/client/ui/dock/DockTabStateTest.kt:15-16`

**Interfaces:**
- Produces: `Panel(id: String, title: String, region: DockRegion, icon: IconKey, content: @Composable (Panel) -> Unit)`
  with properties of the same names. `IconKey` is `org.jetbrains.jewel.ui.icon.IconKey`.

- [ ] **Step 1: Rewrite `Panel.kt`**

```kotlin
package com.breadmoirai.garnet.ui.dock

import androidx.compose.runtime.Composable
import org.jetbrains.jewel.ui.icon.IconKey

/**
 * One panel in a [DockRegion]. Retained across frames; its [content] pulls live state each
 * recomposition. Named `Panel` (never `Component`) to avoid colliding with MC's text Component.
 *
 * A panel carries its own [region] and [icon] rather than being filed into a per-region list,
 * because both are properties of the panel itself: the region is where it belongs, and the icon is
 * how `DockStripe` offers it. `DockState.panelsFor` derives the per-region lists from these, so
 * there is exactly one definition of "which panels does this region have".
 *
 * [title] survives the tab strip's deletion: it is the panel's human name for accessibility and for
 * test assertions, and the stripe uses it as the icon's content description.
 */
class Panel(
    val id: String,
    val title: String,
    val region: DockRegion,
    val icon: IconKey,
    val content: @Composable (Panel) -> Unit,
)
```

- [ ] **Step 2: Update the two real panel factories**

In `ProjectExplorerPanel.kt`, replace line 66 and add the two imports
(`com.breadmoirai.garnet.ui.dock.DockRegion`, `org.jetbrains.jewel.ui.icons.AllIconsKeys` is
already imported):

```kotlin
fun explorerPanel(): Panel = Panel(
    "garnet.explorer", "Explorer", DockRegion.LEFT, AllIconsKeys.Toolwindows.ToolWindowProject,
) { ProjectExplorer() }
```

In `LocalHistoryPanel.kt`, replace line 36 and add imports for `DockRegion` and
`org.jetbrains.jewel.ui.icons.AllIconsKeys`:

```kotlin
fun localHistoryPanel(): Panel = Panel(
    "garnet.localHistory", "Local History", DockRegion.LEFT, AllIconsKeys.Vcs.History,
) { LocalHistory() }
```

- [ ] **Step 3: Update the three test panel constructions**

`DockHitTestTest.kt:91`:

```kotlin
        DockState.centerPanels.add(
            Panel("garnet.test.center", "CenterProbe", DockRegion.CENTER, AllIconsKeys.General.Information) {},
        )
```

`DockLifecycleTest.kt:17-18`:

```kotlin
        DockState.leftPanels.add(
            Panel("test.left", "Left", DockRegion.LEFT, AllIconsKeys.General.Information) {},
        )
        DockState.centerPanels.add(
            Panel("test.center", "Center", DockRegion.CENTER, AllIconsKeys.General.Information) {},
        )
```

`DockTabStateTest.kt:15-16`:

```kotlin
        DockState.leftPanels += Panel("a", "Explorer", DockRegion.LEFT, AllIconsKeys.General.Information) { }
        DockState.leftPanels += Panel("b", "Local History", DockRegion.LEFT, AllIconsKeys.General.Information) { }
```

Add `import org.jetbrains.jewel.ui.icons.AllIconsKeys` to each of the three test files.

- [ ] **Step 4: Verify the icon keys resolve at compile time**

Run: `cmd.exe /c "cd /d H:\\Repo\\Garnet && gradlew.bat :26.2:clientClasses :26.2:testClasses"`
Expected: BUILD SUCCESSFUL.

If `AllIconsKeys.Toolwindows.ToolWindowProject` or `AllIconsKeys.Vcs.History` does not exist in this
Jewel version, the compile fails with "unresolved reference". Substitute from the same catalog —
`AllIconsKeys.Nodes.Folder` and `AllIconsKeys.Actions.Rollback` are known-good in this codebase
(the Explorer already draws `Nodes.Folder`). Whether the key *renders* is checked in Task 4 Step 6;
a key can compile and still draw nothing, because `jewel-ui` ships the catalog but the SVGs come
from the separate `com.jetbrains.intellij.platform:icons` artifact
(`docs/ui/jewel-widget-layer.md`).

- [ ] **Step 5: Run the full compile across all five source sets**

Run: `cmd.exe /c "cd /d H:\\Repo\\Garnet && gradlew.bat :26.2:clientClasses :26.2:classes :26.2:gametestClasses :26.2:clientTestClasses :26.2:testClasses"`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add src/client/kotlin/com/breadmoirai/garnet/ui/dock/Panel.kt \
        src/client/kotlin/com/breadmoirai/garnet/editor/ui/ProjectExplorerPanel.kt \
        src/client/kotlin/com/breadmoirai/garnet/editor/ui/LocalHistoryPanel.kt \
        src/test/kotlin/com/breadmoirai/garnet/client/ui/dock/
git commit -m "refactor(dock): give Panel its own region and stripe icon"
```

---

### Task 2: `DockLayoutStore` stores which panel is open per region

Done before the `DockState` rewrite so that rewrite has a store to call.

**Files:**
- Modify: `src/client/kotlin/com/breadmoirai/garnet/config/DockLayoutStore.kt`
- Create: `src/test/kotlin/com/breadmoirai/garnet/client/ui/dock/DockLayoutStoreTest.kt`

**Interfaces:**
- Consumes: `DockRegion` (Task 1, unchanged enum).
- Produces:
  - `DockLayoutStore.load(): Map<DockRegion, String>`
  - `DockLayoutStore.save(open: Map<DockRegion, String>)`
  - `DockLayoutStore.DEFAULT_OPEN: Map<DockRegion, String>` = `mapOf(DockRegion.LEFT to "garnet.explorer")`
  - `configFileForTest(File)` / `resetConfigFileForTest()` unchanged.

- [ ] **Step 1: Write the failing test**

Create `src/test/kotlin/com/breadmoirai/garnet/client/ui/dock/DockLayoutStoreTest.kt`:

```kotlin
package com.breadmoirai.garnet.client.ui.dock

import com.breadmoirai.garnet.config.DockLayoutStore
import com.breadmoirai.garnet.ui.dock.DockRegion
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.io.File
import kotlin.io.path.createTempDirectory

/**
 * The `config/garnet-dock.json` round trip. Every failure path falls back to [DEFAULT_OPEN] rather
 * than propagating: restoring the layout is a convenience, and "open the Explorer" is the wanted
 * behaviour for a fresh install, so it is also the right thing to fall back to when the record
 * cannot be trusted.
 */
class DockLayoutStoreTest : FunSpec({

    fun withStore(name: String, seed: String?, body: (File) -> Unit) {
        val dir = createTempDirectory(name)
        val file = dir.resolve("garnet-dock.json").toFile()
        DockLayoutStore.configFileForTest(file)
        try {
            if (seed != null) file.writeText(seed)
            body(file)
        } finally {
            DockLayoutStore.resetConfigFileForTest()
            dir.toFile().deleteRecursively()
        }
    }

    test("an absent file falls back to the Explorer open in LEFT") {
        withStore("store-absent", seed = null) {
            DockLayoutStore.load() shouldBe DockLayoutStore.DEFAULT_OPEN
        }
    }

    test("a saved map round-trips") {
        withStore("store-roundtrip", seed = null) {
            DockLayoutStore.save(mapOf(DockRegion.LEFT to "garnet.localHistory"))
            DockLayoutStore.load() shouldBe mapOf(DockRegion.LEFT to "garnet.localHistory")
        }
    }

    test("an explicitly empty map round-trips as everything closed") {
        withStore("store-empty", seed = null) {
            DockLayoutStore.save(emptyMap())
            DockLayoutStore.load() shouldBe emptyMap()
        }
    }

    test("a legacy leftVisible:true record migrates to the Explorer open") {
        withStore("store-legacy-true", seed = """{"leftVisible":true}""") {
            DockLayoutStore.load() shouldBe mapOf(DockRegion.LEFT to "garnet.explorer")
        }
    }

    test("a legacy leftVisible:false record migrates to everything closed") {
        withStore("store-legacy-false", seed = """{"leftVisible":false}""") {
            DockLayoutStore.load() shouldBe emptyMap()
        }
    }

    test("an unknown region key is dropped rather than failing the whole read") {
        withStore("store-bad-region", seed = """{"open":{"SIDEWAYS":"garnet.explorer","LEFT":"garnet.explorer"}}""") {
            DockLayoutStore.load() shouldBe mapOf(DockRegion.LEFT to "garnet.explorer")
        }
    }

    test("a non-string panel id is dropped") {
        withStore("store-bad-id", seed = """{"open":{"LEFT":7}}""") {
            DockLayoutStore.load() shouldBe emptyMap()
        }
    }

    test("malformed JSON falls back to the default") {
        withStore("store-malformed", seed = "{not json") {
            DockLayoutStore.load() shouldBe DockLayoutStore.DEFAULT_OPEN
        }
    }

    test("a file with neither key falls back to the default") {
        withStore("store-no-keys", seed = """{"somethingElse":1}""") {
            DockLayoutStore.load() shouldBe DockLayoutStore.DEFAULT_OPEN
        }
    }
})
```

Note the deliberate asymmetry the tests pin: an **absent or untrustworthy** record falls back to the
default, but a **present and well-formed** record saying "nothing open" (`{"open":{}}` or legacy
`leftVisible:false`) is honoured as an explicit user choice.

- [ ] **Step 2: Run it to verify it fails**

Run: `cmd.exe /c "cd /d H:\\Repo\\Garnet && gradlew.bat :26.2:testClasses"`
Expected: FAIL — "unresolved reference: DEFAULT_OPEN", and `save`/`load` type mismatches.

- [ ] **Step 3: Rewrite `DockLayoutStore.kt`**

Replace the whole file body below the imports (add `import com.breadmoirai.garnet.ui.dock.DockRegion`
and `import com.google.gson.JsonPrimitive`):

```kotlin
/**
 * The `config/garnet-dock.json` round-trip for which dock panel is open in each region.
 *
 * Deliberately NOT part of [ModConfig]: that object's contract is a pure [SharedSettings] round-trip,
 * and `SharedSettings` is read by the dedicated server — dock layout is client UI state a server must
 * never see. Deliberately NOT part of [ExplorerStateStore] either: that record is keyed by project
 * root and written only in singleplayer, and dock layout is neither. Reusing that file would silently
 * inherit both restrictions and break auto-open on a remote Garnet server.
 *
 * Panel ids, not a boolean. The record used to be `{"leftVisible": true}`, from when visibility was
 * per-region; [load] still reads that shape and migrates it, and the file is rewritten in the new
 * shape on the next [save]. Splitter sizes are still process-lifetime state that
 * `DockState.closeAll()` preserves, and nothing needs them to survive a restart yet.
 */
object DockLayoutStore {
    /** What a fresh install — or an untrustworthy record — gets: the Explorer open in LEFT. */
    val DEFAULT_OPEN: Map<DockRegion, String> = mapOf(DockRegion.LEFT to "garnet.explorer")

    private const val EXPLORER_ID = "garnet.explorer"

    private val defaultFile: File
        get() = FabricLoader.getInstance().configDir.resolve("garnet-dock.json").toFile()

    private var overrideFile: File? = null
    private val configFile: File get() = overrideFile ?: defaultFile

    /** Test seam: redirect reads/writes at [file] instead of the real config directory. */
    fun configFileForTest(file: File) { overrideFile = file }
    fun resetConfigFileForTest() { overrideFile = null }

    /**
     * The remembered open panel per region.
     *
     * Every *untrustworthy* path — absent file, unreadable file, malformed JSON, no recognised key —
     * yields [DEFAULT_OPEN]. A *well-formed* record saying nothing is open is honoured as the
     * explicit user choice it is, which is why `{"open":{}}` returns empty rather than the default.
     *
     * Entries are dropped individually rather than failing the whole read: an unknown region name or
     * a non-string panel id costs that one entry, so a panel removed in a future version cannot wedge
     * the file.
     */
    fun load(): Map<DockRegion, String> {
        val file = configFile
        if (!file.exists()) return DEFAULT_OPEN
        return runCatching {
            file.reader().use { reader ->
                val json = JsonParser.parseReader(reader) as? JsonObject ?: return@use DEFAULT_OPEN
                json.getAsJsonObject("open")?.let { open ->
                    return@use buildMap {
                        for ((name, value) in open.entrySet()) {
                            val region = DockRegion.entries.firstOrNull { it.name == name } ?: continue
                            val id = (value as? JsonPrimitive)?.takeIf { it.isString }?.asString ?: continue
                            put(region, id)
                        }
                    }
                }
                // Legacy shape, written before visibility became per-panel.
                val legacy = json.get("leftVisible")
                if (legacy != null && legacy.isJsonPrimitive && legacy.asJsonPrimitive.isBoolean) {
                    return@use if (legacy.asBoolean) mapOf(DockRegion.LEFT to EXPLORER_ID) else emptyMap()
                }
                DEFAULT_OPEN
            }
        }.onFailure { e ->
            LOGGER.warn("Failed to load dock layout from {}", file.absolutePath, e)
        }.getOrDefault(DEFAULT_OPEN)
    }

    /** Overwrite the stored record with [open], replacing any legacy `leftVisible` key. */
    fun save(open: Map<DockRegion, String>) {
        val file = configFile
        file.parentFile?.mkdirs()
        val entries = JsonObject()
        open.forEach { (region, id) -> entries.addProperty(region.name, id) }
        val json = JsonObject()
        json.add("open", entries)
        runCatching {
            file.writeText(json.toString())
        }.onFailure { e ->
            LOGGER.error("Failed to save dock layout to {}", file.absolutePath, e)
        }
    }
}
```

`getAsJsonObject` returns `null` when the key is absent **and** throws `ClassCastException` when the
key holds a non-object; the enclosing `runCatching` turns the latter into the default, which is the
wanted behaviour for a corrupt record.

- [ ] **Step 4: Fix the two existing callers so the tree compiles**

`DockAutoOpen.kt:41` currently reads `if (!DockLayoutStore.load()) return false`. Change to a
temporary shim, replaced properly in Task 3:

```kotlin
    if (DockLayoutStore.load()[DockRegion.LEFT] == null) return false
```

`viewport/DockKeybinds.kt` has two `DockLayoutStore.save(...)` calls (a boolean each). Change both to
a temporary shim, replaced properly in Task 3:

```kotlin
                    DockLayoutStore.save(
                        if (DockState.isVisible(DockRegion.LEFT)) mapOf(DockRegion.LEFT to "garnet.explorer")
                        else emptyMap(),
                    )
```

for the Shift branch, and the unconditional `mapOf(DockRegion.LEFT to "garnet.explorer")` for the Alt
branch.

`DockAutoOpenTest.kt`'s `withDock` helper calls `DockLayoutStore.save(stored)` with a `Boolean`.
Change its seeding line to:

```kotlin
            if (stored != null) {
                DockLayoutStore.save(if (stored) mapOf(DockRegion.LEFT to "garnet.explorer") else emptyMap())
            }
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `cmd.exe /c "cd /d H:\\Repo\\Garnet && gradlew.bat :26.2:test"`
Then read `versions/26.2/build/test-results/test/TEST-com.breadmoirai.garnet.client.ui.dock.DockLayoutStoreTest.xml`
Expected: 9 tests, 0 failures. Also confirm `TEST-...DockAutoOpenTest.xml` still shows 0 failures.

- [ ] **Step 6: Commit**

```bash
git add src/client/kotlin/com/breadmoirai/garnet/config/DockLayoutStore.kt \
        src/client/kotlin/com/breadmoirai/garnet/ui/dock/DockAutoOpen.kt \
        src/client/kotlin/com/breadmoirai/garnet/ui/viewport/DockKeybinds.kt \
        src/test/kotlin/com/breadmoirai/garnet/client/ui/dock/DockLayoutStoreTest.kt \
        src/test/kotlin/com/breadmoirai/garnet/client/ui/dock/DockAutoOpenTest.kt
git commit -m "feat(dock): store which panel is open per region, migrating leftVisible"
```

---

### Task 3: Per-panel visibility replaces per-region visibility

The atomic one. Deleting `setVisible` breaks every consumer at once, so state, layout, hit test,
insets, keybinds, registration, and the affected tests all move together.

**Files:**
- Modify: `src/client/kotlin/com/breadmoirai/garnet/ui/dock/DockState.kt`
- Modify: `src/client/kotlin/com/breadmoirai/garnet/ui/dock/GarnetDock.kt`
- Modify: `src/client/kotlin/com/breadmoirai/garnet/ui/dock/DockInsets.kt`
- Modify: `src/client/kotlin/com/breadmoirai/garnet/ui/dock/DockHitTest.kt`
- Modify: `src/client/kotlin/com/breadmoirai/garnet/ui/dock/DockAutoOpen.kt`
- Modify: `src/client/kotlin/com/breadmoirai/garnet/ui/viewport/DockKeybinds.kt`
- Modify: `src/client/kotlin/com/breadmoirai/garnet/GarnetClient.kt`
- Delete: `src/client/kotlin/com/breadmoirai/garnet/ui/dock/DockTabStrip.kt`
- Delete: `src/test/kotlin/com/breadmoirai/garnet/client/ui/dock/DockTabStateTest.kt`
- Create: `src/test/kotlin/com/breadmoirai/garnet/client/ui/dock/DockPanelVisibilityTest.kt`
- Create: `src/test/kotlin/com/breadmoirai/garnet/client/ui/dock/DockMountEpochTest.kt`
- Modify: `src/test/kotlin/com/breadmoirai/garnet/client/ui/dock/DockHitTestTest.kt`
- Modify: `src/test/kotlin/com/breadmoirai/garnet/client/ui/dock/DockInsetsTest.kt`
- Modify: `src/test/kotlin/com/breadmoirai/garnet/client/ui/dock/DockLifecycleTest.kt`
- Modify: `src/test/kotlin/com/breadmoirai/garnet/client/ui/dock/DockAutoOpenTest.kt`
- Modify: `src/clientTest/kotlin/com/breadmoirai/garnet/test/JewelExplorerSpec.kt` (compile only; the
  behavioural rewrite is Task 7)

**Interfaces:**
- Consumes: `Panel(id, title, region, icon, content)` (Task 1);
  `DockLayoutStore.load(): Map<DockRegion, String>`, `save(Map<DockRegion, String>)`,
  `DEFAULT_OPEN` (Task 2).
- Produces:
  - `DockState.panels: SnapshotStateList<Panel>`
  - `DockState.panelsFor(region: DockRegion): List<Panel>`
  - `DockState.openPanelId(region: DockRegion): String?`
  - `DockState.isVisible(region: DockRegion): Boolean`
  - `DockState.togglePanel(id: String)`
  - `DockState.showPanel(id: String)`
  - `DockState.closeRegion(region: DockRegion)`
  - `DockState.openMap(): Map<DockRegion, String>`
  - `DockState.applyOpenMap(open: Map<DockRegion, String>)`
  - unchanged: `mountEpoch`, `focusedRegion`, `anyActive`, `setSize`, `reset`, `closeAll`,
    `leftWidth`/`rightWidth`/`bottomHeight`, `DEFAULT_*`, `MIN_EDGE`, `MAX_EDGE`
  - **removed:** `leftPanels`, `rightPanels`, `bottomPanels`, `centerPanels`, `leftVisible`,
    `rightVisible`, `bottomVisible`, `leftActiveTab`, `rightActiveTab`, `bottomActiveTab`,
    `centerActiveTab`, `activeTab`, `setActiveTab`, `setVisible`, `toggleVisible`

- [ ] **Step 1: Write the failing tests**

Create `src/test/kotlin/com/breadmoirai/garnet/client/ui/dock/DockPanelVisibilityTest.kt`:

```kotlin
package com.breadmoirai.garnet.client.ui.dock

import com.breadmoirai.garnet.ui.dock.DockRegion
import com.breadmoirai.garnet.ui.dock.DockState
import com.breadmoirai.garnet.ui.dock.Panel
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.jetbrains.jewel.ui.icons.AllIconsKeys

/**
 * The dock's interaction model: one panel open per region, driven by [DockState.togglePanel].
 * Pure snapshot state, no render context — the same click routed through a real scene is
 * `JewelExplorerSpec` in `src/clientTest`.
 */
class DockPanelVisibilityTest : FunSpec({

    afterTest { DockState.reset() }

    fun seed() {
        DockState.reset()
        DockState.panels += Panel("a", "A", DockRegion.LEFT, AllIconsKeys.General.Information) {}
        DockState.panels += Panel("b", "B", DockRegion.LEFT, AllIconsKeys.General.Information) {}
        DockState.panels += Panel("c", "C", DockRegion.BOTTOM, AllIconsKeys.General.Information) {}
    }

    test("panelsFor filters the flat registry by region, in registration order") {
        seed()
        DockState.panelsFor(DockRegion.LEFT).map { it.id } shouldBe listOf("a", "b")
        DockState.panelsFor(DockRegion.BOTTOM).map { it.id } shouldBe listOf("c")
        DockState.panelsFor(DockRegion.RIGHT).map { it.id } shouldBe emptyList()
    }

    test("a region starts closed and opens when one of its panels is toggled") {
        seed()
        DockState.isVisible(DockRegion.LEFT) shouldBe false
        DockState.togglePanel("a")
        DockState.isVisible(DockRegion.LEFT) shouldBe true
        DockState.openPanelId(DockRegion.LEFT) shouldBe "a"
    }

    test("toggling a sibling switches the open panel without closing the region") {
        seed()
        DockState.togglePanel("a")
        DockState.togglePanel("b")
        DockState.openPanelId(DockRegion.LEFT) shouldBe "b"
        DockState.isVisible(DockRegion.LEFT) shouldBe true
    }

    test("toggling the already-open panel closes its region") {
        seed()
        DockState.togglePanel("a")
        DockState.togglePanel("a")
        DockState.isVisible(DockRegion.LEFT) shouldBe false
        DockState.openPanelId(DockRegion.LEFT) shouldBe null
    }

    test("regions are independent") {
        seed()
        DockState.togglePanel("a")
        DockState.togglePanel("c")
        DockState.openPanelId(DockRegion.LEFT) shouldBe "a"
        DockState.openPanelId(DockRegion.BOTTOM) shouldBe "c"
        DockState.togglePanel("c")
        DockState.openPanelId(DockRegion.LEFT) shouldBe "a"
        DockState.isVisible(DockRegion.BOTTOM) shouldBe false
    }

    test("an unknown id is ignored rather than throwing") {
        seed()
        DockState.togglePanel("nope")
        DockState.anyActive() shouldBe false
    }

    test("showPanel opens without the close-on-repeat behaviour") {
        seed()
        DockState.showPanel("a")
        DockState.showPanel("a")
        DockState.openPanelId(DockRegion.LEFT) shouldBe "a"
    }

    test("openMap round-trips through applyOpenMap, dropping unknown ids") {
        seed()
        DockState.togglePanel("a")
        DockState.togglePanel("c")
        val saved = DockState.openMap()
        saved shouldBe mapOf(DockRegion.LEFT to "a", DockRegion.BOTTOM to "c")

        DockState.closeRegion(DockRegion.LEFT)
        DockState.closeRegion(DockRegion.BOTTOM)
        DockState.applyOpenMap(saved + (DockRegion.RIGHT to "ghost"))
        DockState.openMap() shouldBe saved
    }

    test("applyOpenMap ignores an entry whose panel belongs to a different region") {
        seed()
        DockState.applyOpenMap(mapOf(DockRegion.BOTTOM to "a"))
        DockState.isVisible(DockRegion.BOTTOM) shouldBe false
    }
})
```

Create `src/test/kotlin/com/breadmoirai/garnet/client/ui/dock/DockMountEpochTest.kt`:

```kotlin
package com.breadmoirai.garnet.client.ui.dock

import com.breadmoirai.garnet.ui.dock.DockRegion
import com.breadmoirai.garnet.ui.dock.DockState
import com.breadmoirai.garnet.ui.dock.Panel
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.jetbrains.jewel.ui.icons.AllIconsKeys

/**
 * `DockState.mountEpoch` exists so a panel body cannot outlive its mount — see that field for the
 * ghost-popup failure mode. The epoch must bump whenever a region's OPEN PANEL changes, not only
 * when the region closes: switching Explorer -> Local History reuses the region's body slot, and a
 * `Popup` opened in the first panel was added to the scene rather than to the keyed subtree, so it
 * survives the swap and paints over the second panel.
 */
class DockMountEpochTest : FunSpec({

    afterTest { DockState.reset() }

    fun seed() {
        DockState.reset()
        DockState.panels += Panel("a", "A", DockRegion.LEFT, AllIconsKeys.General.Information) {}
        DockState.panels += Panel("b", "B", DockRegion.LEFT, AllIconsKeys.General.Information) {}
    }

    test("switching panels within a region bumps that region's epoch") {
        seed()
        DockState.togglePanel("a")
        val before = DockState.mountEpoch(DockRegion.LEFT)
        DockState.togglePanel("b")
        (DockState.mountEpoch(DockRegion.LEFT) > before) shouldBe true
    }

    test("closing a region bumps its epoch") {
        seed()
        DockState.togglePanel("a")
        val before = DockState.mountEpoch(DockRegion.LEFT)
        DockState.togglePanel("a")
        (DockState.mountEpoch(DockRegion.LEFT) > before) shouldBe true
    }

    test("re-showing the panel that is already open bumps nothing") {
        seed()
        DockState.showPanel("a")
        val before = DockState.mountEpoch(DockRegion.LEFT)
        DockState.showPanel("a")
        DockState.mountEpoch(DockRegion.LEFT) shouldBe before
    }

    test("a change in one region leaves another region's epoch alone") {
        seed()
        DockState.panels += Panel("c", "C", DockRegion.BOTTOM, AllIconsKeys.General.Information) {}
        DockState.togglePanel("c")
        val bottom = DockState.mountEpoch(DockRegion.BOTTOM)
        DockState.togglePanel("a")
        DockState.mountEpoch(DockRegion.BOTTOM) shouldBe bottom
    }
})
```

- [ ] **Step 2: Run to verify they fail**

Run: `cmd.exe /c "cd /d H:\\Repo\\Garnet && gradlew.bat :26.2:testClasses"`
Expected: FAIL — "unresolved reference: panels", "unresolved reference: togglePanel".

- [ ] **Step 3: Rewrite `DockState.kt`**

Replace everything from `var leftVisible` (line 41) through the end of `toggleVisible` (line 155),
keeping the class KDoc, the `DEFAULT_*`/`MIN_EDGE`/`MAX_EDGE` constants, the width/height fields,
`focusedRegion`, and the `mountEpochs` block with its KDoc. Change the `mountEpochs` KDoc's
"bumped whenever a region is hidden or [reset]" to "bumped whenever a region's open panel changes,
or on [reset]".

Imports change: drop `mutableStateListOf`/`SnapshotStateList` only if unused — they are still needed
for `panels` — and add `androidx.compose.runtime.mutableStateMapOf`.

```kotlin
    /**
     * Every registered panel, in registration order. Flat rather than four per-region lists: a panel
     * carries its own region (see [Panel]), so [panelsFor] derives the per-region view and there is
     * one definition of "which panels does this region have" — the one `DockStripe` renders.
     */
    val panels: SnapshotStateList<Panel> = mutableStateListOf()

    /**
     * Which panel is open in each region, by id. Absence means the region is closed, so this is the
     * single source of visibility — there is no separate `leftVisible` to disagree with it.
     */
    private val openPanel = mutableStateMapOf<DockRegion, String>()

    fun panelsFor(region: DockRegion): List<Panel> = panels.filter { it.region == region }

    fun panelById(id: String): Panel? = panels.firstOrNull { it.id == id }

    fun openPanelId(region: DockRegion): String? = openPanel[region]

    /** The open panel's body, or null when the region is closed or its id no longer resolves. */
    fun openPanelOf(region: DockRegion): Panel? = openPanel[region]?.let { panelById(it) }

    fun isVisible(region: DockRegion): Boolean = openPanelOf(region) != null

    /**
     * Show [id]'s panel, evicting whatever its region had open. A no-op when it is already the open
     * one, so repeated calls (auto-open on join, Alt+1 held down) cost no mount-epoch churn.
     * Unknown ids are ignored: a panel can be removed between versions while its id survives in
     * `garnet-dock.json`.
     */
    fun showPanel(id: String) {
        val panel = panelById(id) ?: return
        if (openPanel[panel.region] == id) return
        openPanel[panel.region] = id
        bumpMountEpoch(panel.region)
    }

    /** Close [region], ending its open panel's mount. Idempotent. */
    fun closeRegion(region: DockRegion) {
        if (openPanel.remove(region) != null) bumpMountEpoch(region)
    }

    /**
     * The stripe's click, and the keybinds': show [id], or close its region when [id] is already the
     * open panel there. This is the whole interaction model — "click the lit icon to close" is what
     * makes a stripe a stripe rather than a row of radio buttons.
     */
    fun togglePanel(id: String) {
        val panel = panelById(id) ?: return
        if (openPanel[panel.region] == id) closeRegion(panel.region) else showPanel(id)
    }

    /** The persistable layout: which panel is open where. */
    fun openMap(): Map<DockRegion, String> = openPanel.toMap()

    /**
     * Restore [open], ignoring entries whose id is unknown or whose panel belongs to a different
     * region than the entry claims. Both are what a stale `garnet-dock.json` looks like after a
     * panel is removed or moved, and neither should wedge the dock.
     */
    fun applyOpenMap(open: Map<DockRegion, String>) {
        open.forEach { (region, id) ->
            if (panelById(id)?.region == region) showPanel(id)
        }
    }
```

Then update the three remaining members:

```kotlin
    /** Set an edge region's reserved size (width for L/R, height for B), clamped. */
    fun setSize(region: DockRegion, size: Int) {
        val clamped = size.coerceIn(MIN_EDGE, MAX_EDGE)
        when (region) {
            DockRegion.LEFT -> leftWidth = clamped
            DockRegion.RIGHT -> rightWidth = clamped
            DockRegion.BOTTOM -> bottomHeight = clamped
            DockRegion.CENTER -> {}
        }
    }

    /**
     * True when the dock has something to show: any region open, or a region focused. Drives whether
     * the viewport shrink + Compose overlay should render (see `syncDockViewport` in
     * `DockViewportSync.kt`) and whether `DockStripe` is drawn at all — read-only, makes no state
     * changes itself.
     */
    fun anyActive(): Boolean = openPanel.isNotEmpty() || focusedRegion != null

    /** Test/reset hook: clears panels, closes every region, restores default sizes and focus. */
    fun reset() {
        openPanel.clear()
        leftWidth = DEFAULT_LEFT; rightWidth = DEFAULT_RIGHT; bottomHeight = DEFAULT_BOTTOM
        panels.clear()
        focusedRegion = null
        DockRegion.entries.forEach { bumpMountEpoch(it) }
    }

    /**
     * Ends the dock's **world session**: closes every region, drops the CENTER documents, and drops
     * input focus. Called when the client disconnects (see `registerDockWorldLifecycle` in
     * `viewport/DockKeybinds.kt`).
     *
     * Deliberately narrower than [reset]. The panel registry and splitter sizes are user *layout*,
     * not world state — and the Explorer is only ever registered at `onInitializeClient`, so a full
     * [reset] here would leave LEFT permanently empty for the rest of the process. CENTER panels
     * *are* removed from the registry: they are per-world documents that mean nothing without the
     * session that opened them.
     *
     * [focusedRegion] is cleared directly instead of via `DockInputRouter.clearFocus()`: that helper
     * re-grabs the mouse when no [net.minecraft.client.gui.screens.Screen] is open, and at disconnect
     * time the title screen is not reliably installed yet, so it would capture the cursor on the
     * title screen. `DockInputRouter.captured` reads through to this field, so clearing it is enough
     * to stop the input mixins. Keeping this method free of `Minecraft` calls also keeps it testable.
     *
     * Idempotent: calling it on an already-closed dock changes nothing.
     */
    fun closeAll() {
        DockRegion.entries.forEach { closeRegion(it) }
        if (panels.any { it.region == DockRegion.CENTER }) {
            panels.removeAll { it.region == DockRegion.CENTER }
            bumpMountEpoch(DockRegion.CENTER)
        }
        focusedRegion = null
    }
```

- [ ] **Step 4: Rewrite `GarnetDock.kt`'s region rendering**

`DockTabStrip` is gone, so a region is just its open panel's body. Delete `DockTabStrip.kt` and
replace `RegionColumn`:

```kotlin
/** A region = the open panel's body, filling the region. */
@Composable
private fun RegionColumn(region: DockRegion, modifier: Modifier) {
    val panel = DockState.openPanelOf(region) ?: return
    Column(modifier.background(PANEL_BG)) {
        // key(): a panel body must not be able to outlive its mount. Panel content is invoked at a
        // fixed slot, and a re-mounted panel from the same factory has the same composable source
        // key, so without this Compose reuses the group and every `remember` inside survives — most
        // visibly a Jewel Dropdown's open menu and its Popup layer, which then paints over the next
        // mount. See DockState.mountEpoch for the full mechanism. Panel id is in the key too so
        // swapping which panel occupies a region is likewise a fresh mount.
        Box(Modifier.fillMaxSize()) {
            key(DockState.mountEpoch(region), panel.id) { panel.content(panel) }
        }
    }
}
```

and change the CENTER guard at line 53 from `DockState.centerPanels.isNotEmpty()` to
`DockState.isVisible(DockRegion.CENTER)`. The stripe's own offsets are added in Task 4 — for now
`GarnetDock` keeps LEFT at `x = 0`.

- [ ] **Step 5: Update `DockInsets.kt` and `DockHitTest.kt` to the derived visibility**

Both already call `isVisible(region)`, which still exists with the same signature — so the only edit
is `DockHitTest.kt`'s CENTER line, which reads `centerPanels` directly:

```kotlin
    if (isVisible(DockRegion.CENTER)) return DockRegion.CENTER
```

and its KDoc bullet 3, which should now read: "CENTER owns whatever is left — but **only when it
actually has an open panel**. A closed CENTER is transparent by omission and *is* the world, which is
the whole point of the `null` case."

- [ ] **Step 6: Update `DockAutoOpen.kt`**

Replace the body of `applyDockAutoOpen`, and update its KDoc's first paragraph to say it applies the
remembered open-panel map rather than "the remembered LEFT visibility":

```kotlin
fun applyDockAutoOpen(): Boolean {
    // Ask the gate before the store: on a vanilla server the answer is "no" regardless of what was
    // remembered, and there is no reason to touch the filesystem to find that out.
    if (!DockAutoOpenGate.isGarnetServer()) return false
    val stored = DockLayoutStore.load()
    if (stored.isEmpty()) return false
    // Nothing to do when every stored panel is already the open one — the caller skips the
    // framebuffer churn on that answer.
    if (stored.all { (region, id) -> DockState.openPanelId(region) == id }) return false
    DockState.applyOpenMap(stored)
    return DockState.anyActive()
}
```

- [ ] **Step 7: Update `viewport/DockKeybinds.kt`**

Replace the `shift ->` and `alt ->` branch bodies, keeping every surrounding comment:

```kotlin
                shift -> {
                    DockState.togglePanel(EXPLORER_PANEL_ID)
                    if (!DockState.isVisible(DockRegion.LEFT) && DockState.focusedRegion == DockRegion.LEFT) {
                        DockInputRouter.clearFocus()
                    }
                    DockLayoutStore.save(DockState.openMap())
                    syncDockViewport()
                    (mc.window as Any as WindowViewportExt).`garnet$updateScaledFramebuffer`(true)
                }
                alt -> {
                    if (DockState.focusedRegion == DockRegion.LEFT) {
                        // Focus-only change: visibility is untouched, so nothing to persist.
                        DockInputRouter.clearFocus()
                    } else {
                        // Focus needs something to focus: open the Explorer when LEFT is empty, but
                        // leave an already-open Local History alone — Alt+1 is "give the dock the
                        // keyboard", not "switch panels".
                        if (!DockState.isVisible(DockRegion.LEFT)) DockState.showPanel(EXPLORER_PANEL_ID)
                        DockLayoutStore.save(DockState.openMap())
                        DockInputRouter.focus(DockRegion.LEFT)
                    }
                    syncDockViewport()
                    (mc.window as Any as WindowViewportExt).`garnet$updateScaledFramebuffer`(true)
                }
```

Add near the top of the file, beside `GLFW_KEY_1`:

```kotlin
private const val EXPLORER_PANEL_ID = "garnet.explorer"
```

Update the `registerDockKeybinds` KDoc: "Alt+1 focuses the Explorer … Shift+1 toggles the LEFT
region's visibility" becomes "Shift+1 toggles the **Explorer panel** — opening it, or closing LEFT
when the Explorer is already what LEFT shows, or switching LEFT to the Explorer when it is showing
something else."

- [ ] **Step 8: Update `GarnetClient.kt` registration**

```kotlin
        // Seed the LEFT dock panels. The region starts closed; joining a Garnet world opens the
        // remembered one (see applyDockAutoOpen), the stripe icons switch between them, and Shift+1
        // toggles the Explorer by hand.
        DockState.panels += explorerPanel()
        DockState.panels += localHistoryPanel()
```

with proper top-level imports for `DockState`, `explorerPanel`, and `localHistoryPanel`, replacing
the fully-qualified references currently on lines 27-31.

- [ ] **Step 9: Migrate the four existing dock tests**

In `DockHitTestTest.kt`, `DockInsetsTest.kt`, and `DockLifecycleTest.kt`, replace each
`DockState.setVisible(DockRegion.X, true)` with a seeded panel plus a toggle. Add this helper to
each file's spec body:

```kotlin
    fun open(region: DockRegion) {
        val id = "probe.${region.name}"
        if (DockState.panelById(id) == null) {
            DockState.panels += Panel(id, region.name, region, AllIconsKeys.General.Information) {}
        }
        DockState.showPanel(id)
    }
```

so `DockState.setVisible(DockRegion.LEFT, true)` becomes `open(DockRegion.LEFT)`. In
`DockHitTestTest`'s CENTER test, the `centerPanels.add(...)` line becomes `open(DockRegion.CENTER)`.
In `DockLifecycleTest`, `seedOpenDock` uses `open(...)` for all four regions, and the two assertions
on removed members change:

- `DockState.centerPanels.isEmpty() shouldBe true` → `DockState.isVisible(DockRegion.CENTER) shouldBe false`
- `DockState.centerActiveTab shouldBe 0` → delete the line
- `DockState.leftPanels.size shouldBe 1` → `DockState.panelsFor(DockRegion.LEFT).size shouldBe 1`
- `DockState.leftPanels[0].id shouldBe "test.left"` → `DockState.panelsFor(DockRegion.LEFT)[0].id shouldBe "probe.LEFT"`

In `DockAutoOpenTest.kt`, `withDock` must register the Explorer panel so `applyOpenMap` can resolve
`"garnet.explorer"`. After `DockState.reset()` add:

```kotlin
        DockState.panels += Panel(
            "garnet.explorer", "Explorer", DockRegion.LEFT, AllIconsKeys.General.Information,
        ) {}
```

and in the `"an already-visible dock reports no change so the caller skips the framebuffer churn"`
test, replace `DockState.setVisible(DockRegion.LEFT, true)` with:

```kotlin
            DockState.showPanel("garnet.explorer")
```

Every `DockState.isVisible(DockRegion.LEFT) shouldBe …` assertion in that file is unchanged —
`isVisible` kept its signature.

Delete `DockTabStateTest.kt` — `DockPanelVisibilityTest` replaces it.

- [ ] **Step 10: Make `JewelExplorerSpec.kt` compile**

Mechanical only; the behavioural rewrite is Task 7. Replace `DockState.leftPanels.add(x)` with
`DockState.panels += x`, `DockState.setVisible(DockRegion.LEFT, true)` with
`DockState.showPanel("garnet.explorer")`, `DockState.setActiveTab(DockRegion.LEFT, 0)` with
`DockState.showPanel("garnet.explorer")`, `DockState.setActiveTab(DockRegion.LEFT, 1)` with
`DockState.showPanel("garnet.localHistory")`, and
`onClient { DockState.activeTab(DockRegion.LEFT) } shouldBe 1` with
`onClient { DockState.openPanelId(DockRegion.LEFT) } shouldBe "garnet.localHistory"`. Grep for any
other hit: `grep -rn "leftPanels\|setVisible\|setActiveTab\|activeTab\|centerPanels" src/clientTest`.

- [ ] **Step 11: Run the full compile and test suite**

Run: `cmd.exe /c "cd /d H:\\Repo\\Garnet && gradlew.bat :26.2:clientClasses :26.2:classes :26.2:gametestClasses :26.2:clientTestClasses :26.2:testClasses"`
Expected: BUILD SUCCESSFUL. Grep for stragglers first:
`grep -rn "leftPanels\|rightPanels\|bottomPanels\|centerPanels\|setVisible\|toggleVisible\|setActiveTab\|activeTab\|leftVisible" src/` should return only matches inside `docs/` (none in `src/`).

Run: `cmd.exe /c "cd /d H:\\Repo\\Garnet && gradlew.bat :26.2:test"`
Then read the XML reports for `DockPanelVisibilityTest`, `DockMountEpochTest`, `DockHitTestTest`,
`DockInsetsTest`, `DockLifecycleTest`, `DockAutoOpenTest`, `DockLayoutStoreTest`, `DockViewportSyncTest`.
Expected: 0 failures in each.

- [ ] **Step 12: Commit**

```bash
git add -A src/client src/test src/clientTest
git rm --cached -r --ignore-unmatch src/client/kotlin/com/breadmoirai/garnet/ui/dock/DockTabStrip.kt
git commit -m "feat(dock): make visibility per-panel and retire the tab strip"
```

---

### Task 4: The stripe

**Files:**
- Create: `src/client/kotlin/com/breadmoirai/garnet/ui/dock/DockStripe.kt`
- Modify: `src/client/kotlin/com/breadmoirai/garnet/ui/dock/GarnetDock.kt`
- Modify: `src/client/kotlin/com/breadmoirai/garnet/ui/dock/DockInsets.kt`
- Modify: `src/client/kotlin/com/breadmoirai/garnet/ui/dock/DockHitTest.kt`
- Create: `src/test/kotlin/com/breadmoirai/garnet/client/ui/dock/DockStripeGeometryTest.kt`
- Modify: `src/test/kotlin/com/breadmoirai/garnet/client/ui/dock/DockInsetsTest.kt`
- Modify: `src/test/kotlin/com/breadmoirai/garnet/client/ui/dock/DockHitTestTest.kt`

**Interfaces:**
- Consumes: `DockState.panelsFor`, `openPanelId`, `togglePanel`, `anyActive` (Task 3).
- Produces: `const val STRIPE_WIDTH = 32` in `ui/dock/DockStripe.kt`;
  `@Composable fun DockStripe(region: DockRegion, modifier: Modifier)`.

- [ ] **Step 1: Write the failing geometry test**

Create `src/test/kotlin/com/breadmoirai/garnet/client/ui/dock/DockStripeGeometryTest.kt`:

```kotlin
package com.breadmoirai.garnet.client.ui.dock

import com.breadmoirai.garnet.ui.dock.DockInsets
import com.breadmoirai.garnet.ui.dock.DockRegion
import com.breadmoirai.garnet.ui.dock.DockState
import com.breadmoirai.garnet.ui.dock.Panel
import com.breadmoirai.garnet.ui.dock.STRIPE_WIDTH
import com.breadmoirai.garnet.ui.dock.insets
import com.breadmoirai.garnet.ui.dock.regionAt
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.jetbrains.jewel.ui.icons.AllIconsKeys

/**
 * The stripe's contribution to the two pure-geometry surfaces: [insets] (how much the world shrinks)
 * and [regionAt] (who owns a pixel). The stripe is drawn LAST in `GarnetDock` and tested FIRST here;
 * those two facts are one decision and must move together.
 */
class DockStripeGeometryTest : FunSpec({

    val w = 1920
    val h = 1080

    afterTest { DockState.reset() }

    fun seed() {
        DockState.reset()
        DockState.panels += Panel("l", "L", DockRegion.LEFT, AllIconsKeys.General.Information) {}
        DockState.panels += Panel("b", "B", DockRegion.BOTTOM, AllIconsKeys.General.Information) {}
    }

    test("a closed dock reserves nothing at all — no stripe, no world shrink") {
        seed()
        DockState.insets() shouldBe DockInsets(0, 0, 0, 0)
        DockState.regionAt(0, 0, w, h) shouldBe null
    }

    test("an open LEFT reserves the stripe plus the panel width") {
        seed()
        DockState.togglePanel("l")
        DockState.setSize(DockRegion.LEFT, 280)
        DockState.insets() shouldBe DockInsets(STRIPE_WIDTH + 280, 0, 0, 0)
    }

    test("the stripe is reserved even when only BOTTOM is open") {
        seed()
        DockState.togglePanel("b")
        DockState.setSize(DockRegion.BOTTOM, 160)
        DockState.insets() shouldBe DockInsets(STRIPE_WIDTH, 0, 160, 0)
    }

    test("focus alone keeps the stripe reserved") {
        seed()
        DockState.focusedRegion = DockRegion.LEFT
        DockState.insets() shouldBe DockInsets(STRIPE_WIDTH, 0, 0, 0)
    }

    test("the stripe column belongs to LEFT, and the LEFT panel starts after it") {
        seed()
        DockState.togglePanel("l")
        DockState.setSize(DockRegion.LEFT, 280)

        DockState.regionAt(0, h / 2, w, h) shouldBe DockRegion.LEFT
        DockState.regionAt(STRIPE_WIDTH - 1, h / 2, w, h) shouldBe DockRegion.LEFT
        DockState.regionAt(STRIPE_WIDTH, h / 2, w, h) shouldBe DockRegion.LEFT
        // Stripe + panel, and the first pixel past both is world.
        DockState.regionAt(STRIPE_WIDTH + 279, h / 2, w, h) shouldBe DockRegion.LEFT
        DockState.regionAt(STRIPE_WIDTH + 280, h / 2, w, h) shouldBe null
    }

    test("the stripe wins its column inside the BOTTOM band, which it is drawn over") {
        seed()
        DockState.togglePanel("l")
        DockState.togglePanel("b")
        DockState.setSize(DockRegion.BOTTOM, 160)

        // GarnetDock draws the stripe LAST, full height, so it beats BOTTOM's full-width band here.
        DockState.regionAt(4, h - 1, w, h) shouldBe DockRegion.LEFT
        // Just past the stripe, the band still owns the bottom-left corner.
        DockState.regionAt(STRIPE_WIDTH + 4, h - 1, w, h) shouldBe DockRegion.BOTTOM
    }

    test("with only BOTTOM open the stripe still owns its column and still reads as LEFT") {
        seed()
        DockState.togglePanel("b")
        DockState.regionAt(4, h / 2, w, h) shouldBe DockRegion.LEFT
        DockState.regionAt(STRIPE_WIDTH + 4, h / 2, w, h) shouldBe null
    }
})
```

- [ ] **Step 2: Run to verify it fails**

Run: `cmd.exe /c "cd /d H:\\Repo\\Garnet && gradlew.bat :26.2:testClasses"`
Expected: FAIL — "unresolved reference: STRIPE_WIDTH".

- [ ] **Step 3: Create `DockStripe.kt`**

```kotlin
package com.breadmoirai.garnet.ui.dock

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import org.jetbrains.jewel.intui.standalone.theme.IntUiTheme
import org.jetbrains.jewel.ui.component.Icon

/** Reserved width of the tool-window stripe, in real framebuffer px (the scene runs at Density(1f)). */
const val STRIPE_WIDTH = 32

private val STRIPE_BG = Color(0xFF1E1F22)
private val ICON_SELECTED_BG = Color(0xFF2B2D30)

/**
 * The JetBrains-style tool-window stripe: one icon per panel registered to [region], top-aligned,
 * with the open one highlighted. Clicking an icon shows that panel; clicking the lit icon closes the
 * region. That close-on-relick is what makes this a stripe rather than a row of radio buttons.
 *
 * Rendered by [GarnetDock] only while [DockState.anyActive], so a closed dock costs the world zero
 * pixels. The consequence — closing the last panel makes the stripe vanish, leaving Shift+1 as the
 * only way back — is accepted: `applyDockAutoOpen` opens a panel on every Garnet world join, so a
 * fully closed dock is a deliberate act, and the keybind that closed it also reopens it.
 *
 * Hand-rolled over Box/pointerInput rather than built from Jewel's IconButton, for the same reason
 * the retired DockTabStrip was: this sits underneath the scene's layer routing, which is the subtlest
 * thing in this package, and a focusable Jewel component here would pull focus-and-popup behaviour
 * into it.
 */
@Composable
fun DockStripe(region: DockRegion, modifier: Modifier) {
    val panels = DockState.panelsFor(region)
    if (panels.isEmpty()) return
    val open = DockState.openPanelId(region)
    IntUiTheme(isDark = true) {
        Column(modifier.background(STRIPE_BG), horizontalAlignment = Alignment.CenterHorizontally) {
            panels.forEach { panel ->
                Box(
                    Modifier
                        .padding(top = 4.dp)
                        .size(28.dp)
                        .background(if (panel.id == open) ICON_SELECTED_BG else Color.Transparent)
                        .pointerInput(panel.id) {
                            detectTapGestures { DockState.togglePanel(panel.id) }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(panel.icon, contentDescription = panel.title)
                }
            }
        }
    }
}
```

- [ ] **Step 4: Add the stripe to `DockInsets.kt`**

```kotlin
/**
 * Current reserved insets derived purely from [DockState]. A closed region reserves nothing.
 * CENTER reserves nothing here (an occupying CENTER panel occludes the world at composite time,
 * it does not shrink it).
 *
 * The stripe's width is gated on [DockState.anyActive] rather than on LEFT being open: the stripe
 * is visible whenever *any* region is open, which is what lets a user with only BOTTOM open still
 * reach the LEFT icons.
 */
fun DockState.insets(): DockInsets = DockInsets(
    left = (if (anyActive()) STRIPE_WIDTH else 0) + (if (isVisible(DockRegion.LEFT)) leftWidth else 0),
    right = if (isVisible(DockRegion.RIGHT)) rightWidth else 0,
    bottom = if (isVisible(DockRegion.BOTTOM)) bottomHeight else 0,
    top = 0,
)
```

- [ ] **Step 5: Add the stripe to `DockHitTest.kt` and `GarnetDock.kt`**

In `regionAt`, insert as the **first** region test after the bounds check, and shift LEFT's strip:

```kotlin
    val stripe = if (anyActive()) STRIPE_WIDTH else 0
    val left = if (isVisible(DockRegion.LEFT)) leftWidth else 0
    val right = if (isVisible(DockRegion.RIGHT)) rightWidth else 0
    val bottom = if (isVisible(DockRegion.BOTTOM)) bottomHeight else 0

    // The stripe is drawn LAST in GarnetDock — full height, over everything — so it is tested FIRST
    // here, before BOTTOM's full-width band. Attributed to LEFT so clicking an icon also focuses the
    // panel it opens.
    if (stripe > 0 && x < stripe) return DockRegion.LEFT
    if (bottom > 0 && y >= realH - bottom) return DockRegion.BOTTOM
    if (left > 0 && x < stripe + left) return DockRegion.LEFT
    if (right > 0 && x >= realW - right) return DockRegion.RIGHT
    if (isVisible(DockRegion.CENTER)) return DockRegion.CENTER
    return null
```

Update the KDoc's numbered z-order list to lead with "0. The stripe owns its full-height column,
drawn last and therefore tested first."

In `GarnetDock`, add `stripe` to the local sizes, offset LEFT and CENTER by it, and draw the stripe
last:

```kotlin
        val stripe = if (DockState.anyActive()) STRIPE_WIDTH else 0
        val left = if (DockState.isVisible(DockRegion.LEFT)) DockState.leftWidth else 0
```

then in the LEFT branch use `Modifier.offset(stripe.dp, 0.dp)` for `RegionColumn` and
`Modifier.offset((stripe + left - SPLITTER).dp, 0.dp)` for the splitter; in the CENTER branch use
`Modifier.offset((stripe + left).dp, 0.dp).width((realW - stripe - left - right).dp)`; and add, as
the **last** child of the outer `Box`:

```kotlin
        if (stripe > 0) {
            DockStripe(DockRegion.LEFT, Modifier.offset(0.dp, 0.dp).width(stripe.dp).height(realH.dp))
        }
```

- [ ] **Step 6: Verify the icons actually render**

Compile-passing icon keys can still draw nothing — `jewel-ui` ships the `AllIconsKeys` catalog but
the SVGs come from `com.jetbrains.intellij.platform:icons` (`docs/ui/jewel-widget-layer.md`).

Run: `cmd.exe /c "cd /d H:\\Repo\\Garnet && gradlew.bat :26.2:runClientTest"` (the clientTest run
config), join the editor world, and confirm the stripe shows two distinct, non-blank icons. If either
is blank, substitute a key already proven to render in this codebase — `AllIconsKeys.Nodes.Folder`,
`AllIconsKeys.FileTypes.Archive`, `AllIconsKeys.Actions.Refresh`, `AllIconsKeys.Actions.Undo` — and
record the substitution in `docs/ui/dock-stripe.md` in Task 8.

- [ ] **Step 7: Update the two existing geometry tests for the new stripe term**

`DockInsetsTest` and `DockHitTestTest` both assert insets and coordinates that now shift by
`STRIPE_WIDTH` whenever the dock is active. Update every expectation:

- `"a visible left region reserves its width"` → `DockInsets(STRIPE_WIDTH + 260, 0, 0, 0)`
- `"insets drive the content rect"` → `rect.frameX shouldBe STRIPE_WIDTH + 260`,
  `rect.frameWidth shouldBe 1000 - STRIPE_WIDTH - 260`
- `"hidden regions reserve no space"` is unchanged — a closed dock is not active, so no stripe.
- In `DockHitTestTest`, every open-region case gains `STRIPE_WIDTH` on its x coordinates; the
  `"with nothing visible"` and `"a hidden region claims nothing"` cases are unchanged.

Import `STRIPE_WIDTH` in both files rather than hardcoding 32, so a future width change does not
silently invalidate them.

- [ ] **Step 8: Run the tests**

Run: `cmd.exe /c "cd /d H:\\Repo\\Garnet && gradlew.bat :26.2:test"`
Read the XML for `DockStripeGeometryTest`, `DockInsetsTest`, `DockHitTestTest`, `DockViewportSyncTest`.
Expected: 0 failures in each.

- [ ] **Step 9: Commit**

```bash
git add src/client/kotlin/com/breadmoirai/garnet/ui/dock/ \
        src/test/kotlin/com/breadmoirai/garnet/client/ui/dock/
git commit -m "feat(dock): add the tool-window stripe"
```

---

### Task 5: `StructureInfoState` takes over from `ProjectTreeState.status`

**Files:**
- Create: `src/client/kotlin/com/breadmoirai/garnet/editor/ui/StructureInfoState.kt`
- Modify: `src/client/kotlin/com/breadmoirai/garnet/editor/ui/ProjectTreeState.kt`
- Modify: `src/client/kotlin/com/breadmoirai/garnet/editor/network/EditorClientNetworking.kt:29-44`
- Modify: `src/client/kotlin/com/breadmoirai/garnet/editor/ui/ExplorerLifecycle.kt:69`
- Create: `src/test/kotlin/com/breadmoirai/garnet/client/editor/ui/StructureInfoStateTest.kt`
- Delete: `src/test/kotlin/com/breadmoirai/garnet/client/editor/ui/StructureExplorerStatusTest.kt`

**Interfaces:**
- Produces: `object StructureInfoState` with `var subpath: String?`, `var sizeX/sizeY/sizeZ: Int`,
  `var blockCount: Int`, `var lastSavedMillis: Long`, `var status: String`, and functions
  `onStructureResult(StructureResultS2C)`, `onAutoSaved(StructureAutoSavedS2C)`,
  `onFolderLoaded(EditorFolderLoadedS2C)`, `onSaveReport(EditorSaveReportS2C)`,
  `onError(EditorErrorS2C)`, `reset()`. Sentinels: `blockCount = -1` means unknown,
  `lastSavedMillis = 0L` means never saved this session.
- Produces: `ProjectTreeState` reduced to `snapshot` + `onSnapshot` + `reset`.

- [ ] **Step 1: Write the failing test**

Create `src/test/kotlin/com/breadmoirai/garnet/client/editor/ui/StructureInfoStateTest.kt`:

```kotlin
package com.breadmoirai.garnet.client.editor.ui

import com.breadmoirai.garnet.editor.network.EditorErrorS2C
import com.breadmoirai.garnet.editor.network.StructureAutoSavedS2C
import com.breadmoirai.garnet.editor.network.StructureResultS2C
import com.breadmoirai.garnet.editor.ui.StructureInfoState
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Which packet lands which field. This replaced `StructureExplorerStatusTest`, which asserted on the
 * pre-baked `ProjectTreeState.status` string — the panel needs the numbers, not a sentence.
 */
class StructureInfoStateTest : FunSpec({

    afterTest { StructureInfoState.reset() }

    test("a place result fills the subpath, the sizes and the status") {
        StructureInfoState.reset()
        StructureInfoState.onStructureResult(
            StructureResultS2C("a/box.nbt", 2, 1, 3, message = "placed a/box.nbt"),
        )
        StructureInfoState.subpath shouldBe "a/box.nbt"
        StructureInfoState.sizeX shouldBe 2
        StructureInfoState.sizeY shouldBe 1
        StructureInfoState.sizeZ shouldBe 3
        StructureInfoState.status shouldBe "placed a/box.nbt"
    }

    test("a place result leaves the block count and save time unknown") {
        StructureInfoState.reset()
        StructureInfoState.onStructureResult(
            StructureResultS2C("a/box.nbt", 2, 1, 3, message = "placed a/box.nbt"),
        )
        StructureInfoState.blockCount shouldBe -1
        StructureInfoState.lastSavedMillis shouldBe 0L
    }

    test("an auto-save fills every field from the payload, save time included") {
        StructureInfoState.reset()
        StructureInfoState.onAutoSaved(
            StructureAutoSavedS2C("redstone/clock.nbt", 5, 3, 7, 42, savedAtMillis = 1_700_000_000_000L),
        )
        StructureInfoState.subpath shouldBe "redstone/clock.nbt"
        StructureInfoState.sizeX shouldBe 5
        StructureInfoState.sizeY shouldBe 3
        StructureInfoState.sizeZ shouldBe 7
        StructureInfoState.blockCount shouldBe 42
        StructureInfoState.lastSavedMillis shouldBe 1_700_000_000_000L
    }

    test("placing a different structure clears the previous one's block count and save time") {
        StructureInfoState.reset()
        StructureInfoState.onAutoSaved(
            StructureAutoSavedS2C("redstone/clock.nbt", 5, 3, 7, 42, savedAtMillis = 1_700_000_000_000L),
        )
        StructureInfoState.onStructureResult(
            StructureResultS2C("a/box.nbt", 2, 1, 3, message = "placed a/box.nbt"),
        )
        // Carrying 42 blocks and the clock's save time under box.nbt's name would be a lie.
        StructureInfoState.blockCount shouldBe -1
        StructureInfoState.lastSavedMillis shouldBe 0L
        StructureInfoState.sizeX shouldBe 2
    }

    test("an error writes only the status, leaving the structure facts intact") {
        StructureInfoState.reset()
        StructureInfoState.onAutoSaved(
            StructureAutoSavedS2C("redstone/clock.nbt", 5, 3, 7, 42, savedAtMillis = 1_700_000_000_000L),
        )
        StructureInfoState.onError(EditorErrorS2C("bad name"))
        StructureInfoState.status shouldBe "error: bad name"
        StructureInfoState.subpath shouldBe "redstone/clock.nbt"
        StructureInfoState.blockCount shouldBe 42
    }

    test("reset returns every field to its no-structure sentinel") {
        StructureInfoState.reset()
        StructureInfoState.onAutoSaved(
            StructureAutoSavedS2C("redstone/clock.nbt", 5, 3, 7, 42, savedAtMillis = 1L),
        )
        StructureInfoState.reset()
        StructureInfoState.subpath shouldBe null
        StructureInfoState.blockCount shouldBe -1
        StructureInfoState.lastSavedMillis shouldBe 0L
        StructureInfoState.status shouldBe ""
    }
})
```

Check `EditorErrorS2C`'s constructor before running — if it takes more than a single `reason`
string, adjust that one call. Verify with
`grep -n "class EditorErrorS2C" -A 4 src/main/kotlin/com/breadmoirai/garnet/editor/network/EditorPackets.kt`.

- [ ] **Step 2: Run to verify it fails**

Run: `cmd.exe /c "cd /d H:\\Repo\\Garnet && gradlew.bat :26.2:testClasses"`
Expected: FAIL — "unresolved reference: StructureInfoState".

- [ ] **Step 3: Create `StructureInfoState.kt`**

```kotlin
package com.breadmoirai.garnet.editor.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.breadmoirai.garnet.editor.network.EditorErrorS2C
import com.breadmoirai.garnet.editor.network.EditorFolderLoadedS2C
import com.breadmoirai.garnet.editor.network.EditorSaveReportS2C
import com.breadmoirai.garnet.editor.network.StructureAutoSavedS2C
import com.breadmoirai.garnet.editor.network.StructureResultS2C

/**
 * Client-side, Compose-observable facts about the open structure, plus the editor's transient status
 * line. Read by [structureInfoPanel]; written by the network receivers in `EditorClientNetworking`.
 *
 * Fields, not a pre-baked sentence. This replaced `ProjectTreeState.status`, a single string that
 * five receivers wrote to — three with transient feedback and two with structure facts — so the
 * size and block count of the open structure were destroyed by the next unrelated error.
 *
 * The two sentinels exist because the two payloads carry different amounts. `StructureResultS2C`
 * has the sizes but no block count, and a freshly placed structure has not been auto-saved yet; the
 * panel omits those rows entirely rather than rendering `-1` or an epoch date.
 */
object StructureInfoState {
    /** Null when no structure is open. */
    var subpath by mutableStateOf<String?>(null)
        private set
    var sizeX by mutableIntStateOf(0)
        private set
    var sizeY by mutableIntStateOf(0)
        private set
    var sizeZ by mutableIntStateOf(0)
        private set

    /** -1 when not known yet — placed, but no auto-save report has arrived for it. */
    var blockCount by mutableIntStateOf(-1)
        private set

    /** 0 when never saved this session. The server's own write time, from the payload. */
    var lastSavedMillis by mutableLongStateOf(0L)
        private set

    /** The transient line: errors, load reports, save reports, and place messages. */
    var status by mutableStateOf("")
        private set

    fun onStructureResult(r: StructureResultS2C) {
        subpath = r.subpath
        sizeX = r.sizeX; sizeY = r.sizeY; sizeZ = r.sizeZ
        // A place carries no block count, and nothing has auto-saved this structure yet. Keeping the
        // PREVIOUS structure's numbers under the new one's name would be a lie, so both reset.
        blockCount = -1
        lastSavedMillis = 0L
        status = r.message
    }

    fun onAutoSaved(p: StructureAutoSavedS2C) {
        subpath = p.subpath
        sizeX = p.sizeX; sizeY = p.sizeY; sizeZ = p.sizeZ
        blockCount = p.blockCount
        lastSavedMillis = p.savedAtMillis
    }

    fun onFolderLoaded(p: EditorFolderLoadedS2C) {
        val errs = p.parseErrors.size + p.layoutErrors.size
        status = if (errs == 0) "loaded ${p.subpath} (${p.loadedSpecIds.size} specs)"
                 else "loaded ${p.subpath} with $errs error(s)"
    }

    fun onSaveReport(r: EditorSaveReportS2C) { status = "saved ${r.perSpec.size} spec(s)" }

    fun onError(e: EditorErrorS2C) { status = "error: ${e.reason}" }

    /** Test/reset hook; also called on disconnect — a placed structure does not survive a world. */
    fun reset() {
        subpath = null
        sizeX = 0; sizeY = 0; sizeZ = 0
        blockCount = -1
        lastSavedMillis = 0L
        status = ""
    }
}
```

- [ ] **Step 4: Reduce `ProjectTreeState.kt` to the snapshot**

```kotlin
package com.breadmoirai.garnet.editor.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.breadmoirai.garnet.editor.network.EditorTreeSnapshotS2C

/**
 * Client-side, Compose-observable state for the Project Explorer: the server's tree snapshot. The
 * networking layer mutates it from the client thread; [ProjectExplorerPanel] reads it during
 * composition and recomposes on change.
 *
 * The status line used to live here too. It moved wholesale to [StructureInfoState], which holds the
 * open structure's facts as fields rather than as a formatted sentence.
 *
 * Tree *interaction* state (expansion, selection) deliberately lives in [ExplorerTreeState], owned by
 * Jewel's TreeState, so there is exactly one copy of it.
 */
object ProjectTreeState {
    var snapshot by mutableStateOf<EditorTreeSnapshotS2C?>(null)
        private set

    fun onSnapshot(s: EditorTreeSnapshotS2C) { snapshot = s }

    /** Test/reset hook: clears the snapshot back to its initial value. */
    fun reset() { snapshot = null }
}
```

- [ ] **Step 5: Repoint the receivers and the disconnect reset**

In `EditorClientNetworking.kt`, change lines 29, 32, 35, 39 and 44 so that `onFolderLoaded`,
`onSaveReport`, `onError`, `onStructureResult` and `onAutoSaved` are called on `StructureInfoState`
instead of `ProjectTreeState`. Line 40's `OpenStructureState.onStructureResult(payload)` stays as-is
— the two objects serve different consumers and both want that packet. Add the import for
`StructureInfoState`; keep `ProjectTreeState`'s (line 21's `onSnapshot` still uses it).

In `ExplorerLifecycle.kt`, add `StructureInfoState.reset()` immediately after line 69's
`ProjectTreeState.reset()`, alongside the existing `OpenStructureState.reset()` and
`LocalHistoryState.reset()`.

- [ ] **Step 6: Temporarily keep `ProjectExplorerPanel` compiling**

Line 174-175 reads the deleted `ProjectTreeState.status`. Task 6 rewrites this properly; for now,
change it to read the local edit error only:

```kotlin
            val message = editError
            if (!message.isNullOrEmpty()) Text(message, Modifier.padding(start = 4.dp, end = 4.dp, top = 4.dp))
```

- [ ] **Step 7: Delete the superseded test and run**

```bash
git rm src/test/kotlin/com/breadmoirai/garnet/client/editor/ui/StructureExplorerStatusTest.kt
```

Run: `cmd.exe /c "cd /d H:\\Repo\\Garnet && gradlew.bat :26.2:clientClasses :26.2:classes :26.2:gametestClasses :26.2:clientTestClasses :26.2:testClasses"`
Expected: BUILD SUCCESSFUL. Confirm nothing still references the deleted field:
`grep -rn "ProjectTreeState.status" src/` returns nothing.

Run: `cmd.exe /c "cd /d H:\\Repo\\Garnet && gradlew.bat :26.2:test"`
Read `TEST-com.breadmoirai.garnet.client.editor.ui.StructureInfoStateTest.xml`.
Expected: 6 tests, 0 failures.

- [ ] **Step 8: Commit**

```bash
git add -A src/client src/test
git commit -m "feat(editor): split structure facts out of the Explorer status line"
```

---

### Task 6: The Structure Info panel, and `editError` inline

**Files:**
- Create: `src/client/kotlin/com/breadmoirai/garnet/editor/ui/TimeFormat.kt`
- Create: `src/client/kotlin/com/breadmoirai/garnet/editor/ui/StructureInfoPanel.kt`
- Modify: `src/client/kotlin/com/breadmoirai/garnet/editor/ui/LocalHistoryPanel.kt:145-146`
- Modify: `src/client/kotlin/com/breadmoirai/garnet/editor/ui/ProjectExplorerPanel.kt`
- Modify: `src/client/kotlin/com/breadmoirai/garnet/GarnetClient.kt`

**Interfaces:**
- Consumes: `StructureInfoState` (Task 5); `Panel(id, title, region, icon, content)` (Task 1);
  `DockState.panels` (Task 3).
- Produces: `fun structureInfoPanel(): Panel` with id `garnet.structureInfo`;
  `fun formatClock(millis: Long): String` in `editor/ui/TimeFormat.kt`.

- [ ] **Step 1: Extract the shared time format**

Create `src/client/kotlin/com/breadmoirai/garnet/editor/ui/TimeFormat.kt`:

```kotlin
package com.breadmoirai.garnet.editor.ui

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Wall-clock formatting shared by the Local History and Structure Info panels, so a revision's
 * timestamp and the open structure's last-saved time read identically.
 *
 * [Locale.ROOT] rather than the default locale: this is a fixed 24-hour pattern, and a host locale
 * that formats it differently would make the two panels disagree with each other.
 */
private val CLOCK_FORMAT = SimpleDateFormat("HH:mm:ss", Locale.ROOT)

fun formatClock(millis: Long): String = CLOCK_FORMAT.format(Date(millis))
```

In `LocalHistoryPanel.kt`, delete lines 145-146 (`TIME_FORMAT` and `formatTime`) and replace every
`formatTime(` call with `formatClock(`. Remove the now-unused `SimpleDateFormat`, `Date` and `Locale`
imports.

- [ ] **Step 2: Create `StructureInfoPanel.kt`**

```kotlin
package com.breadmoirai.garnet.editor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.breadmoirai.garnet.ui.dock.DockRegion
import com.breadmoirai.garnet.ui.dock.Panel
import org.jetbrains.jewel.intui.standalone.theme.IntUiTheme
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.icons.AllIconsKeys

/** Matches the Explorer and Local History panel backgrounds. */
private val PANEL_BG = Color(0xFF1E1F22)

/** The Structure Info panel for the LEFT stripe. */
fun structureInfoPanel(): Panel = Panel(
    "garnet.structureInfo", "Structure Info", DockRegion.LEFT, AllIconsKeys.General.Information,
) { StructureInfo() }

/**
 * The open structure's facts, plus the editor's transient status line.
 *
 * No state of its own: everything comes from [StructureInfoState], fed by network receivers.
 * Anything panel-local would have to be `remember`-ed *here* rather than parked in a top-level
 * object — the dock composes into a long-lived singleton scene, so global panel state survives a
 * re-mount and paints over the next one (see `DockState.mountEpoch`).
 *
 * No glyphs anywhere, and `x` between the dimensions rather than U+00D7: Jewel's default family is
 * Inter, which has no emoji coverage, so anything outside its coverage falls through to whatever
 * Skia finds on the host and renders as tofu. Same rule as the Local History panel.
 *
 * Unknown fields are omitted rather than rendered as their sentinel. A structure that has been
 * placed but not yet auto-saved genuinely has no block count and no save time; showing `-1` or a
 * 1970 date would be worse than showing nothing.
 */
@Composable
private fun StructureInfo() {
    IntUiTheme(isDark = true) {
        Column(Modifier.fillMaxSize().background(PANEL_BG).padding(4.dp)) {
            val subpath = StructureInfoState.subpath
            if (subpath == null) {
                Text("no structure open")
            } else {
                Text(subpath)
                Spacer(Modifier.height(6.dp))
                InfoRow("Size", "${StructureInfoState.sizeX} x ${StructureInfoState.sizeY} x ${StructureInfoState.sizeZ}")
                if (StructureInfoState.blockCount >= 0) {
                    InfoRow("Blocks", StructureInfoState.blockCount.toString())
                }
                if (StructureInfoState.lastSavedMillis > 0L) {
                    InfoRow("Saved", formatClock(StructureInfoState.lastSavedMillis))
                }
            }
            val status = StructureInfoState.status
            if (status.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(status)
            }
        }
    }
}

/** One `label   value` line. The fixed label column keeps the values aligned down the panel. */
@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
        Text(label, Modifier.width(56.dp))
        Text(value)
    }
}
```

- [ ] **Step 3: Move `editError` inline in `ProjectExplorerPanel.kt`**

Delete the temporary bottom line added in Task 5 Step 6 (lines 174-175) entirely. Then, in
`InlineNameField`, render the error under the field. Change the `RowScope.InlineNameField` signature
to `ColumnScope` is *not* needed — instead wrap the field and its message in a `Column` inside the
existing `Row`. Replace the `GarnetTextField(...)` call site so the field and message stack:

```kotlin
    Column(Modifier.weight(1f)) {
        GarnetTextField(
            state = state,
            outline = if (error != null) Outline.Error else Outline.None,
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focusRequester)
                // Keep the existing .onFocusChanged (current lines 340-355) and .onPreviewKeyEvent
                // (356-363) modifiers verbatim, including their full comments — the everFocused gate
                // is load-bearing and its comment explains why. Only `.weight(1f)` is removed from
                // this chain, replaced by `.fillMaxWidth()` above; the weight moves to the Column.
        )
        // The message lives at the field, not in a panel-wide status line, because the panel that
        // now owns status (Structure Info) may be closed — a rename that failed would otherwise
        // leave the user with a red border and no reason for it anywhere on screen.
        if (error != null) Text(error, Modifier.padding(start = 2.dp, top = 1.dp))
    }
```

The `Modifier.weight(1f)` moves from the text field to the enclosing `Column` (it is the `RowScope`
weight); the field itself becomes `fillMaxWidth()`. Add imports for
`androidx.compose.foundation.layout.Column` (already present) and keep `RowScope` on the function
receiver.

Update the `InlineNameField` KDoc: add a line stating the error renders beneath the field, and why.

Also remove the now-stale note in the `ProjectExplorer` KDoc block at lines 97-102 that says the
`remember(snap.root, edit)` scope "also reads `ProjectTreeState.status`, which changes on every S2C
packet" — that read is gone. Keep the rest of that comment; the `buildTreeFrom` cost argument still
holds.

- [ ] **Step 4: Register the panel**

In `GarnetClient.kt`, add after the Local History registration:

```kotlin
        DockState.panels += structureInfoPanel()
```

with the matching import.

- [ ] **Step 5: Compile and run the suite**

Run: `cmd.exe /c "cd /d H:\\Repo\\Garnet && gradlew.bat :26.2:clientClasses :26.2:classes :26.2:gametestClasses :26.2:clientTestClasses :26.2:testClasses"`
Expected: BUILD SUCCESSFUL.

Run: `cmd.exe /c "cd /d H:\\Repo\\Garnet && gradlew.bat :26.2:test"`
Expected: 0 failures across every `TEST-*.xml`.

- [ ] **Step 6: Verify in the running client**

Run: `cmd.exe /c "cd /d H:\\Repo\\Garnet && gradlew.bat :26.2:runClientTest"`, join the editor world,
and confirm: three icons in the stripe; clicking each swaps the panel; clicking the lit one closes
LEFT and the stripe disappears; Shift+1 brings it back; placing a `.nbt` fills Structure Info's
subpath and Size but shows no Blocks/Saved row until the first auto-save; a rename to an invalid name
shows its message under the field with the Explorer's tree still visible.

- [ ] **Step 7: Commit**

```bash
git add -A src/client
git commit -m "feat(editor): add the Structure Info panel"
```

---

### Task 7: Rewrite the scene test for the stripe

**Files:**
- Modify: `src/clientTest/kotlin/com/breadmoirai/garnet/test/JewelExplorerSpec.kt`

**Interfaces:**
- Consumes: `DockState.panels`, `showPanel`, `togglePanel`, `openPanelId` (Task 3);
  `localHistoryPanel()`, `structureInfoPanel()` (Tasks 1, 6).

- [ ] **Step 1: Replace the tab test with a stripe test**

Rewrite the `"switching to the Local History tab actually swaps what the LEFT region paints"` test
(currently at `JewelExplorerSpec.kt:251`), renaming it and dropping its DockTabStrip comment:

```kotlin
    test("switching panels from the stripe actually swaps what the LEFT region paints") {
        closeClientScreen(); waitClientTicks(2)
        runOnClient { OpenStructureState.reset(); LocalHistoryState.reset() }
        mountExplorer()
        // A second LEFT panel: this is also what puts a second icon in the stripe, so the stripe is
        // part of what is being captured here.
        runOnClient {
            DockState.panels += localHistoryPanel()
            DockState.showPanel("garnet.explorer")
        }
        waitClientTicks(12)
        val explorerShot = capture("stripe_panel_explorer.png")
        ComposeSurface.disabled.shouldBeFalse()

        runOnClient { DockState.togglePanel("garnet.localHistory") }
        waitClientTicks(12)
        val historyShot = capture("stripe_panel_history.png")
        onClient { DockState.openPanelId(DockRegion.LEFT) } shouldBe "garnet.localHistory"

        // Asserted by pixel diff, not by state flags: showPanel reads back cleanly the instant it is
        // called, while a stale composition can still be the thing on screen -- exactly the
        // ghost-panel failure DockState.mountEpoch exists to prevent. The Explorer's tree fills this
        // region with glyphs; Local History with no structure open paints a single line, so the two
        // frames must differ across most of the body.
        val changed = bodyDiffCount(explorerShot, historyShot)
        println("[jewel] stripe panel probe: changed=$changed/${BODY_SAMPLES}")
        changed shouldBeGreaterThan 50
        ComposeSurface.disabled.shouldBeFalse()

        unmount()
        ComposeSurface.disabled.shouldBeFalse()
    }
```

- [ ] **Step 2: Shift the body sample window past the stripe**

`BODY_XS = 4..300 step 6` samples from x=4, which is now inside the stripe rather than the panel
body — the stripe does not change between the two captures, so those samples would dilute the diff.
Change it to start past the stripe:

```kotlin
private val BODY_XS = (STRIPE_WIDTH + 4)..(STRIPE_WIDTH + 300) step 6
```

and import `com.breadmoirai.garnet.ui.dock.STRIPE_WIDTH`. Check whether the `BODY_SAMPLES` count and
the `dropdownRegionDiffCount` window (used by the earlier tests in this file) also assume x from 0;
if they do, shift them by `STRIPE_WIDTH` the same way and note it in the commit message.

- [ ] **Step 3: Add a Structure Info render test**

Append to the same spec, after the stripe test:

```kotlin
    test("the Structure Info panel paints its fields, and its empty state when nothing is open") {
        closeClientScreen(); waitClientTicks(2)
        runOnClient { StructureInfoState.reset() }
        mountExplorer()
        runOnClient {
            DockState.panels += structureInfoPanel()
            DockState.showPanel("garnet.structureInfo")
        }
        waitClientTicks(12)
        val emptyShot = capture("structure_info_empty.png")
        ComposeSurface.disabled.shouldBeFalse()

        // Drive it through the real receiver rather than by writing fields: that is the path the
        // panel actually sees, and it is what pins the packet-to-field mapping end to end.
        runOnClient {
            StructureInfoState.onAutoSaved(
                StructureAutoSavedS2C("redstone/clock.nbt", 5, 3, 7, 42, savedAtMillis = 1_700_000_000_000L),
            )
        }
        waitClientTicks(12)
        val filledShot = capture("structure_info_filled.png")
        onClient { StructureInfoState.blockCount } shouldBe 42

        // Empty state is one line; the filled state is a name plus three rows, so the two frames must
        // differ well below the first line of text.
        val changed = bodyDiffCount(emptyShot, filledShot)
        println("[jewel] structure info probe: changed=$changed/${BODY_SAMPLES}")
        changed shouldBeGreaterThan 10
        ComposeSurface.disabled.shouldBeFalse()

        unmount()
        ComposeSurface.disabled.shouldBeFalse()
    }
```

Add imports for `com.breadmoirai.garnet.editor.ui.StructureInfoState`,
`com.breadmoirai.garnet.editor.ui.structureInfoPanel`, and
`com.breadmoirai.garnet.editor.network.StructureAutoSavedS2C`.

The `changed shouldBeGreaterThan 10` threshold is deliberately lower than the stripe test's 50: this
panel paints a handful of short lines rather than filling the region with tree glyphs, so far fewer
sample points move. If the assertion fails on a real, visibly-correct change, widen `BODY_YS` before
lowering the threshold.

- [ ] **Step 4: Run the client test**

Run: `cmd.exe /c "cd /d H:\\Repo\\Garnet && gradlew.bat :26.2:runClientTest"`
Expected: `JewelExplorerSpec` passes, including the earlier dropdown and hide/reshow probes.

If the earlier probes now fail on the sample-window shift, adjust their windows rather than the
thresholds — a threshold change would hide a real regression.

- [ ] **Step 5: Commit**

```bash
git add src/clientTest
git commit -m "test(dock): drive the panel swap from the stripe"
```

---

### Task 8: Documentation

Mandatory per `CLAUDE.md` — the docs audit is part of the task, not a follow-up.

**Files:**
- Create: `docs/ui/dock-stripe.md`, `docs/ui/structure-info-panel.md`
- Modify: `docs/ui/INDEX.md`, `docs/ui/dock-framework.md`, `docs/ui/dock-input-routing.md`,
  `docs/ui/local-history-panel.md`, `docs/ui/explorer-toolbar-and-context-menu.md`

- [ ] **Step 1: Write `docs/ui/dock-stripe.md`**

Frontmatter, then sections covering: the per-panel visibility model that replaced per-region
visibility (`openPanel` map, `togglePanel`, why `panelsFor` derives from the flat registry); why the
stripe is gated on `anyActive()` and the vanishing-stripe dead end that creates; why the stripe is
drawn last and hit-tested first, and that those are one decision; its contribution to
`DockInsets.left` and `regionAt`; why mount epochs now bump on a panel *switch* and the ghost-popup
bug that fixes; and which icon keys were verified to render (record any substitution made in Task 4
Step 6).

```markdown
---
title: The dock stripe — per-panel visibility
tags: [compose, dock, stripe, panels, layout, input, jewel]
summary: The JetBrains-style icon stripe that replaced the dock's tab strip, the per-panel visibility model behind it, why it is gated on anyActive() and vanishes with the dock, and why it is drawn last and hit-tested first.
---
```

- [ ] **Step 2: Write `docs/ui/structure-info-panel.md`**

Sections: what the panel shows; the `ProjectTreeState.status` split and why a single string could not
serve both purposes; why a `StructureResultS2C` resets `blockCount` and `lastSavedMillis` while
keeping the sizes; the two sentinels and why unknown rows are omitted rather than rendered; that
`savedAtMillis` is the server's own write time and no protocol change was needed; the no-glyph rule;
and why `editError` stayed in the Explorer, rendered at the field.

```markdown
---
title: The Structure Info panel
tags: [screens, widgets, dock, structure, status, explorer]
summary: The LEFT panel showing the open structure's subpath, size, block count and last-saved time — why it holds fields rather than a formatted status string, why placing a structure resets the block count and save time, and why the Explorer's inline edit error did not move with the rest of the status line.
---
```

- [ ] **Step 3: Update the four existing articles**

- `dock-framework.md` — the tab-strip passages and the region-visibility model are now wrong.
  Rewrite the region description as "each region renders its open panel's body"; point at
  `dock-stripe.md` for how a panel is opened. Update the mount-epoch section's trigger from "region
  hidden" to "region's open panel changed". Update the `applyDockAutoOpen()`/`garnet-dock.json`
  sentence: the record is now an open-panel map, not a boolean.
- `dock-input-routing.md` — `regionAt` gains the stripe as its first test; Shift+1 now toggles the
  Explorer *panel* and switches to it when LEFT shows something else; the `garnet-dock.json`
  persistence shape changed.
- `local-history-panel.md` — "tabbed beside the Project Explorer" is no longer true; it is a stripe
  icon in the same region.
- `explorer-toolbar-and-context-menu.md` — the panel-wide status line it references is gone;
  `editError` renders at the name field.

- [ ] **Step 4: Register both new articles in `docs/ui/INDEX.md`**

Add a `- [Title](file.md) — summary _[tags]_` line for each, in the existing style, and update the
`dock-framework.md` and `local-history-panel.md` summary lines in that same file — both currently
describe the tab strip.

- [ ] **Step 5: Verify no dangling references**

```bash
grep -rn "DockTabStrip\|leftVisible\|setActiveTab\|activeTab\|ProjectTreeState.status\|leftPanels\|centerPanels" docs/ui docs/architecture docs/use-cases
```

Expected: no hits outside `docs/superpowers/` (specs and plans are commit-time snapshots and are
left alone). Fix any that appear.

Then confirm every `INDEX.md` link resolves:

```bash
for f in $(grep -o '](\([a-z0-9-]*\.md\))' docs/ui/INDEX.md | tr -d '](' | tr -d ')'); do
  test -f "docs/ui/$f" || echo "MISSING: $f"
done
```

Expected: no output.

- [ ] **Step 6: Commit**

```bash
git add docs/ui
git commit -m "docs(ui): cover the dock stripe and the Structure Info panel"
```

---

## Final verification

- [ ] Full compile across all five source sets:
      `cmd.exe /c "cd /d H:\\Repo\\Garnet && gradlew.bat :26.2:clientClasses :26.2:classes :26.2:gametestClasses :26.2:clientTestClasses :26.2:testClasses"`
- [ ] Full unit suite: `cmd.exe /c "cd /d H:\\Repo\\Garnet && gradlew.bat :26.2:test"`, then confirm
      every `versions/26.2/build/test-results/test/TEST-*.xml` reports `failures="0" errors="0"`.
- [ ] Client scene suite: `cmd.exe /c "cd /d H:\\Repo\\Garnet && gradlew.bat :26.2:runClientTest"`.
- [ ] Gametest suite unaffected — no server code was touched, but run it to prove that:
      `cmd.exe /c "cd /d H:\\Repo\\Garnet && gradlew.bat :26.2:runGametest"` (confirm the exact task
      name with `gradlew.bat :26.2:tasks --all | findstr -i gametest` if it does not resolve).
- [ ] `grep -rn "DockTabStrip\|leftVisible\|setActiveTab\|ProjectTreeState.status" src/ docs/ui`
      returns nothing.
