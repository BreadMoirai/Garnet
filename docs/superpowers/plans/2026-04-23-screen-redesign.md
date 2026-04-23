# Screen Redesign — Layout Primitives Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove the `DevLevel` config option, then rewrite all game screens to use MC layout primitives (`LinearLayout`, `FrameLayout`, `ScrollableLayout`) with every widget position resolved by a layout — no manual pixel coordinates.

**Architecture:** Each screen builds its widget tree entirely inside `init()` using MC's layout classes; `layout.arrangeElements()` resolves positions, then `layout.visitWidgets { addRenderableWidget(it) }` registers them with the screen. Mutable screen state is held as fields on the screen object; any structural change calls `rebuildWidgets()` (which re-invokes `init()`).

**Tech Stack:** Kotlin, Minecraft Fabric 26.1, MC layout API (`net.minecraft.client.gui.layouts.*`, `net.minecraft.client.gui.components.*`), YACL 3.9.2 (`dev.isxander.yacl3.gui.LowProfileButtonWidget`)

---

## File map

| File | Status | Responsibility |
|------|--------|----------------|
| `src/main/kotlin/.../config/DevLevel.kt` | **Delete** | DevLevel enum + SharedSettings.devLevel |
| `src/client/kotlin/.../client/config/ModConfig.kt` | **Modify** | Remove devLevel field, option, load/save |
| `src/client/kotlin/.../client/screen/IntEditBox.kt` | **Create** | Reusable integer EditBox with scroll + step buttons |
| `src/client/kotlin/.../client/screen/ColorSwatchWidget.kt` | **Create** | 16×16 color preview widget |
| `src/client/kotlin/.../client/screen/SpecBoundsScreen.kt` | **Rewrite** | Bounds editor using IntEditBox + GridLayout |
| `src/client/kotlin/.../client/screen/SpecOverviewScreen.kt` | **Rewrite** | Overview panel using LinearLayout + ScrollableLayout |
| `src/client/kotlin/.../client/screen/SpecEditorScreen.kt` | **Rewrite** | Entry editor panel; remove SpecEditorState/SpecEditorLazy/buildSpecEditorYacl |
| `src/client/kotlin/.../client/screen/EntryEditorScreen.kt` | **Rewrite** | Condition editor panel; remove buildEntryEditorYacl; add specMode param |
| `src/test/kotlin/.../data/IntEditBoxLogicTest.kt` | **Create** | Unit tests for IntEditBox value-parsing logic |

All paths under `src/client/kotlin/com/breadmoirai/redstonespecs/`.

---

### Task 1: Remove DevLevel

**Files:**
- Delete: `src/main/kotlin/com/breadmoirai/redstonespecs/config/DevLevel.kt`
- Modify: `src/client/kotlin/com/breadmoirai/redstonespecs/client/config/ModConfig.kt`
- Modify: `src/client/kotlin/com/breadmoirai/redstonespecs/client/screen/EntryEditorScreen.kt`

- [ ] **Step 1: Delete DevLevel.kt**

Delete the file `src/main/kotlin/com/breadmoirai/redstonespecs/config/DevLevel.kt` entirely.
This removes both the `DevLevel` enum and `SharedSettings.devLevel`.

- [ ] **Step 2: Update SharedSettings**

`DevLevel.kt` contains `SharedSettings` as well. The remaining content after deletion should be a new minimal `SharedSettings.kt` in the same package:

```kotlin
package com.breadmoirai.redstonespecs.config

object SharedSettings {
    var specSaveDir: String = "redstonespecs"
}
```

Create this as `src/main/kotlin/com/breadmoirai/redstonespecs/config/SharedSettings.kt`.

- [ ] **Step 3: Update ModConfig.kt**

Replace the file content. Remove the `devLevel` field, its `load`/`save` entries, the `CyclingListControllerBuilder` import, and the entire "Redstone Developer Level" option block:

```kotlin
package com.breadmoirai.redstonespecs.client.config

import com.breadmoirai.redstonespecs.config.SharedSettings
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.isxander.yacl3.api.ConfigCategory
import dev.isxander.yacl3.api.Option
import dev.isxander.yacl3.api.OptionDescription
import dev.isxander.yacl3.api.YetAnotherConfigLib
import dev.isxander.yacl3.api.controller.StringControllerBuilder
import dev.isxander.yacl3.api.controller.TickBoxControllerBuilder
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import org.slf4j.LoggerFactory

private val LOGGER = LoggerFactory.getLogger("Redstone Specs")

object ModConfig {
    private val configFile = FabricLoader.getInstance().configDir.resolve("redstonespecs.json").toFile()

    var autoSaveOnExit: Boolean = false
    var specSaveDir: String = "redstonespecs"

    fun load() {
        if (!configFile.exists()) return
        runCatching {
            configFile.reader().use { reader ->
                val json = JsonParser.parseReader(reader) as? JsonObject ?: return@use
                autoSaveOnExit = json.get("autoSaveOnExit")?.asBoolean ?: false
                specSaveDir = json.get("specSaveDir")?.asString ?: "redstonespecs"
            }
        }.onFailure { e ->
            LOGGER.warn("Failed to load ModConfig from {}", configFile.absolutePath, e)
        }
        SharedSettings.specSaveDir = specSaveDir
    }

    fun save() {
        configFile.parentFile?.mkdirs()
        val json = JsonObject()
        json.addProperty("autoSaveOnExit", autoSaveOnExit)
        json.addProperty("specSaveDir", specSaveDir)
        runCatching {
            configFile.writeText(json.toString())
        }.onFailure { e ->
            LOGGER.error("Failed to save ModConfig to {}", configFile.absolutePath, e)
        }
        SharedSettings.specSaveDir = specSaveDir
    }

    fun createScreen(parent: Screen): Screen = YetAnotherConfigLib.createBuilder()
        .title(Component.literal("RedstoneSpecs Config"))
        .category(
            ConfigCategory.createBuilder()
                .name(Component.literal("General"))
                .option(
                    Option.createBuilder<Boolean>()
                        .name(Component.literal("Auto-save on exit"))
                        .description(
                            OptionDescription.of(
                                Component.literal("Automatically save changes when closing the spec editor without pressing Save")
                            )
                        )
                        .binding(false, { autoSaveOnExit }, { autoSaveOnExit = it })
                        .controller(TickBoxControllerBuilder::create)
                        .build()
                )
                .option(
                    Option.createBuilder<String>()
                        .name(Component.literal("Spec Save Directory"))
                        .description(
                            OptionDescription.of(
                                Component.literal("Folder (relative to world folder) where .json and .nbt spec files are saved.")
                            )
                        )
                        .binding("redstonespecs", { specSaveDir }, { specSaveDir = it })
                        .controller(StringControllerBuilder::create)
                        .build()
                )
                .build()
        )
        .save(::save)
        .build()
        .generateScreen(parent)
}
```

- [ ] **Step 4: Remove standardMode from EntryEditorScreen**

In `EntryEditorScreen.kt`, delete the line:
```kotlin
val standardMode = ModConfig.devLevel == DevLevel.STANDARD
```
and delete the `import` for `ModConfig` and `DevLevel` if no longer used.
Also delete the `.apply { if (!standardMode) option(...) }` block that conditionally adds the Phase option — leave Phase always included for now (it will be gated by SpecMode in Task 7).

- [ ] **Step 5: Compile**

```bash
cmd.exe /c "./gradlew.bat :versions:26.1:compileKotlin"
```

Expected: BUILD SUCCESSFUL with no errors.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "feat: remove DevLevel config setting"
```

---

### Task 2: IntEditBox

**Files:**
- Create: `src/client/kotlin/com/breadmoirai/redstonespecs/client/screen/IntEditBox.kt`
- Create: `src/test/kotlin/com/breadmoirai/redstonespecs/data/IntEditBoxLogicTest.kt`

The widget itself requires a Minecraft rendering context so it cannot be unit-tested directly. Extract the two pure functions — `parseIntValue` and `formatIntValue` — as package-level functions so they can be tested in isolation.

- [ ] **Step 1: Write the failing tests**

Create `src/test/kotlin/com/breadmoirai/redstonespecs/data/IntEditBoxLogicTest.kt`:

```kotlin
package com.breadmoirai.redstonespecs.data

import com.breadmoirai.redstonespecs.client.screen.formatIntValue
import com.breadmoirai.redstonespecs.client.screen.parseIntValue
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class IntEditBoxLogicTest {

    @Test fun `parse normal integer`() {
        assertEquals(5, parseIntValue("5", min = 1, max = 10))
    }

    @Test fun `parse clamps to min`() {
        assertEquals(1, parseIntValue("0", min = 1, max = 10))
    }

    @Test fun `parse clamps to max`() {
        assertEquals(10, parseIntValue("99", min = 1, max = 10))
    }

    @Test fun `parse blank returns min`() {
        assertEquals(1, parseIntValue("", min = 1, max = 10))
    }

    @Test fun `parse non-numeric returns min`() {
        assertEquals(1, parseIntValue("abc", min = 1, max = 10))
    }

    @Test fun `parse INIT string when min is -1 returns -1`() {
        assertEquals(-1, parseIntValue("INIT", min = -1, max = 100))
    }

    @Test fun `parse INIT string when min is not -1 returns min`() {
        assertEquals(1, parseIntValue("INIT", min = 1, max = 10))
    }

    @Test fun `format negative one as INIT when min is -1`() {
        assertEquals("INIT", formatIntValue(-1, min = -1))
    }

    @Test fun `format negative one as string when min is not -1`() {
        assertEquals("-1", formatIntValue(-1, min = 0))
    }

    @Test fun `format normal value`() {
        assertEquals("42", formatIntValue(42, min = 0))
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
cmd.exe /c "./gradlew.bat :versions:26.1:test --tests '*.IntEditBoxLogicTest'"
```

Expected: FAILED — `parseIntValue` and `formatIntValue` not found.

- [ ] **Step 3: Create IntEditBox.kt with the pure functions and the widget**

Create `src/client/kotlin/com/breadmoirai/redstonespecs/client/screen/IntEditBox.kt`:

```kotlin
package com.breadmoirai.redstonespecs.client.screen

import net.minecraft.client.gui.Font
import net.minecraft.client.gui.components.EditBox
import net.minecraft.network.chat.Component
import kotlin.math.sign

fun parseIntValue(text: String, min: Int, max: Int): Int {
    if (text == "INIT" && min == -1) return -1
    return text.toIntOrNull()?.coerceIn(min, max) ?: min
}

fun formatIntValue(value: Int, min: Int): String =
    if (value == -1 && min == -1) "INIT" else value.toString()

class IntEditBox(
    font: Font,
    width: Int,
    height: Int,
    private val min: Int,
    private val max: Int,
    initial: Int,
    private val onChange: (Int) -> Unit,
) : EditBox(font, width, height, Component.empty()) {

    init {
        value = formatIntValue(initial, min)
        setResponder { text ->
            val parsed = parseIntValue(text, min, max)
            onChange(parsed)
        }
    }

    fun getValue(): Int = parseIntValue(value, min, max)

    fun setValue(n: Int) {
        val clamped = n.coerceIn(min, max)
        value = formatIntValue(clamped, min)
        onChange(clamped)
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, horizontalAmount: Double, verticalAmount: Double): Boolean {
        if (!isMouseOver(mouseX, mouseY)) return false
        setValue(getValue() + verticalAmount.sign.toInt())
        return true
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
cmd.exe /c "./gradlew.bat :versions:26.1:test --tests '*.IntEditBoxLogicTest'"
```

Expected: BUILD SUCCESSFUL, all 10 tests pass.

- [ ] **Step 5: Compile check**

```bash
cmd.exe /c "./gradlew.bat :versions:26.1:compileKotlin"
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add src/client/kotlin/com/breadmoirai/redstonespecs/client/screen/IntEditBox.kt \
        src/test/kotlin/com/breadmoirai/redstonespecs/data/IntEditBoxLogicTest.kt
git commit -m "feat: add IntEditBox with scroll support and unit tests"
```

---

### Task 3: ColorSwatchWidget

**Files:**
- Create: `src/client/kotlin/com/breadmoirai/redstonespecs/client/screen/ColorSwatchWidget.kt`

- [ ] **Step 1: Create ColorSwatchWidget.kt**

```kotlin
package com.breadmoirai.redstonespecs.client.screen

import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.gui.narration.NarrationElementOutput
import net.minecraft.network.chat.Component

class ColorSwatchWidget(x: Int, y: Int, private var rgb: Int) :
    AbstractWidget(x, y, 16, 16, Component.empty()) {

    fun setColor(newRgb: Int) {
        rgb = newRgb
    }

    override fun renderWidget(guiGraphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        guiGraphics.fill(x, y, x + width, y + height, (0xFF000000.toInt() or (rgb and 0xFFFFFF)))
    }

    override fun updateWidgetNarration(output: NarrationElementOutput) {}

    override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int) = false
}
```

- [ ] **Step 2: Compile check**

```bash
cmd.exe /c "./gradlew.bat :versions:26.1:compileKotlin"
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add src/client/kotlin/com/breadmoirai/redstonespecs/client/screen/ColorSwatchWidget.kt
git commit -m "feat: add ColorSwatchWidget"
```

---

### Task 4: Rewrite SpecBoundsScreen

**Files:**
- Rewrite: `src/client/kotlin/com/breadmoirai/redstonespecs/client/screen/SpecBoundsScreen.kt`

The screen has two coordinate rows (row1 = Offset/Min, row2 = Size/Max) of three `IntEditBox` fields each. When `DisplayMode` toggles, current values are converted before `rebuildWidgets()`.

- [ ] **Step 1: Rewrite SpecBoundsScreen.kt**

```kotlin
package com.breadmoirai.redstonespecs.client.screen

import com.breadmoirai.redstonespecs.block.RedstoneSpecBlockEntity
import com.breadmoirai.redstonespecs.network.ResizeBoundsC2SPayload
import dev.isxander.yacl3.gui.LowProfileButtonWidget
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.CycleButton
import net.minecraft.client.gui.layouts.FrameLayout
import net.minecraft.client.gui.layouts.LinearLayout
import net.minecraft.client.gui.layouts.SpacerElement
import net.minecraft.client.gui.screens.Screen
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.CommonComponents
import net.minecraft.network.chat.Component
import net.minecraft.world.level.levelgen.structure.BoundingBox

class SpecBoundsScreen(private val originPos: BlockPos) :
    Screen(Component.translatable("screen.redstonespecs.spec_bounds")) {

    private enum class DisplayMode { OFFSET_SIZE, MIN_MAX }

    private var displayMode = DisplayMode.OFFSET_SIZE

    // stored values in whichever displayMode is current
    private var v1x = 0; private var v1y = 0; private var v1z = 0
    private var v2x = 0; private var v2y = 0; private var v2z = 0
    private var valuesLoaded = false

    // live IntEditBox references (set during init)
    private var box1x: IntEditBox? = null; private var box1y: IntEditBox? = null; private var box1z: IntEditBox? = null
    private var box2x: IntEditBox? = null; private var box2y: IntEditBox? = null; private var box2z: IntEditBox? = null

    override fun isPauseScreen() = false
    override fun isInGameUi() = true

    override fun init() {
        super.init()

        if (!valuesLoaded) {
            val bounds = getBounds() ?: BoundingBox(-4, -1, -4, 4, 3, 4)
            loadFromBounds(bounds)
            valuesLoaded = true
        }

        val (row1Label, row2Label) = if (displayMode == DisplayMode.OFFSET_SIZE)
            "Offset" to "Size" else "Min" to "Max"

        val content = LinearLayout.vertical().spacing(6)

        content.addChild(CycleButton.builder<DisplayMode>(
            { mode -> Component.literal(if (mode == DisplayMode.OFFSET_SIZE) "Offset / Size" else "Min / Max") },
            displayMode
        ).withValues(*DisplayMode.entries.toTypedArray())
            .create(0, 0, 140, 20, Component.empty()) { _, value -> switchMode(value) })

        content.addChild(SpacerElement.height(2))

        // Row 1
        val row1 = LinearLayout.horizontal().spacing(4)
        row1.addChild(net.minecraft.client.gui.components.StringWidget(
            Component.literal(row1Label), font).setMaxWidth(40))
        box1x = IntEditBox(font, 50, 20, Int.MIN_VALUE, Int.MAX_VALUE, v1x) { v1x = it }.also { row1.addChild(it) }
        row1.addChild(buttonMinus { box1x!!.setValue(box1x!!.getValue() - 1) })
        row1.addChild(buttonPlus  { box1x!!.setValue(box1x!!.getValue() + 1) })
        row1.addChild(SpacerElement.width(8))
        box1y = IntEditBox(font, 50, 20, Int.MIN_VALUE, Int.MAX_VALUE, v1y) { v1y = it }.also { row1.addChild(it) }
        row1.addChild(buttonMinus { box1y!!.setValue(box1y!!.getValue() - 1) })
        row1.addChild(buttonPlus  { box1y!!.setValue(box1y!!.getValue() + 1) })
        row1.addChild(SpacerElement.width(8))
        box1z = IntEditBox(font, 50, 20, Int.MIN_VALUE, Int.MAX_VALUE, v1z) { v1z = it }.also { row1.addChild(it) }
        row1.addChild(buttonMinus { box1z!!.setValue(box1z!!.getValue() - 1) })
        row1.addChild(buttonPlus  { box1z!!.setValue(box1z!!.getValue() + 1) })
        content.addChild(row1)

        // Row 2
        val row2 = LinearLayout.horizontal().spacing(4)
        row2.addChild(net.minecraft.client.gui.components.StringWidget(
            Component.literal(row2Label), font).setMaxWidth(40))
        box2x = IntEditBox(font, 50, 20, Int.MIN_VALUE, Int.MAX_VALUE, v2x) { v2x = it }.also { row2.addChild(it) }
        row2.addChild(buttonMinus { box2x!!.setValue(box2x!!.getValue() - 1) })
        row2.addChild(buttonPlus  { box2x!!.setValue(box2x!!.getValue() + 1) })
        row2.addChild(SpacerElement.width(8))
        box2y = IntEditBox(font, 50, 20, Int.MIN_VALUE, Int.MAX_VALUE, v2y) { v2y = it }.also { row2.addChild(it) }
        row2.addChild(buttonMinus { box2y!!.setValue(box2y!!.getValue() - 1) })
        row2.addChild(buttonPlus  { box2y!!.setValue(box2y!!.getValue() + 1) })
        row2.addChild(SpacerElement.width(8))
        box2z = IntEditBox(font, 50, 20, Int.MIN_VALUE, Int.MAX_VALUE, v2z) { v2z = it }.also { row2.addChild(it) }
        row2.addChild(buttonMinus { box2z!!.setValue(box2z!!.getValue() - 1) })
        row2.addChild(buttonPlus  { box2z!!.setValue(box2z!!.getValue() + 1) })
        content.addChild(row2)

        content.addChild(SpacerElement.height(4))

        val buttons = LinearLayout.horizontal().spacing(8)
        buttons.addChild(LowProfileButtonWidget(0, 0, 80, 20, Component.literal("Save")) { save() })
        buttons.addChild(LowProfileButtonWidget(0, 0, 80, 20, CommonComponents.GUI_CANCEL) { onClose() })
        content.addChild(buttons)

        FrameLayout.centerInRectangle(content, 0, 0, width, height)
        content.arrangeElements()
        content.visitWidgets { addRenderableWidget(it) }
    }

    private fun buttonMinus(onClick: () -> Unit) =
        net.minecraft.client.gui.components.Button.builder(Component.literal("-")) { onClick() }.size(20, 20).build()

    private fun buttonPlus(onClick: () -> Unit) =
        net.minecraft.client.gui.components.Button.builder(Component.literal("+")) { onClick() }.size(20, 20).build()

    private fun loadFromBounds(b: BoundingBox) {
        if (displayMode == DisplayMode.OFFSET_SIZE) {
            v1x = b.minX(); v1y = b.minY(); v1z = b.minZ()
            v2x = b.maxX() - b.minX() + 1; v2y = b.maxY() - b.minY() + 1; v2z = b.maxZ() - b.minZ() + 1
        } else {
            v1x = b.minX(); v1y = b.minY(); v1z = b.minZ()
            v2x = b.maxX(); v2y = b.maxY(); v2z = b.maxZ()
        }
    }

    private fun switchMode(newMode: DisplayMode) {
        if (newMode == displayMode) return
        // convert current stored values to new mode
        val minX: Int; val minY: Int; val minZ: Int
        val maxX: Int; val maxY: Int; val maxZ: Int
        if (displayMode == DisplayMode.OFFSET_SIZE) {
            val sx = v2x.coerceAtLeast(1); val sy = v2y.coerceAtLeast(1); val sz = v2z.coerceAtLeast(1)
            minX = v1x; minY = v1y; minZ = v1z
            maxX = v1x + sx - 1; maxY = v1y + sy - 1; maxZ = v1z + sz - 1
        } else {
            minX = minOf(v1x, v2x); minY = minOf(v1y, v2y); minZ = minOf(v1z, v2z)
            maxX = maxOf(v1x, v2x); maxY = maxOf(v1y, v2y); maxZ = maxOf(v1z, v2z)
        }
        displayMode = newMode
        if (newMode == DisplayMode.OFFSET_SIZE) {
            v1x = minX; v1y = minY; v1z = minZ
            v2x = maxX - minX + 1; v2y = maxY - minY + 1; v2z = maxZ - minZ + 1
        } else {
            v1x = minX; v1y = minY; v1z = minZ
            v2x = maxX; v2y = maxY; v2z = maxZ
        }
        rebuildWidgets()
    }

    private fun save() {
        val newBounds = if (displayMode == DisplayMode.OFFSET_SIZE) {
            BoundingBox(
                v1x, v1y, v1z,
                v1x + v2x.coerceAtLeast(1) - 1,
                v1y + v2y.coerceAtLeast(1) - 1,
                v1z + v2z.coerceAtLeast(1) - 1,
            )
        } else {
            BoundingBox(
                minOf(v1x, v2x), minOf(v1y, v2y), minOf(v1z, v2z),
                maxOf(v1x, v2x), maxOf(v1y, v2y), maxOf(v1z, v2z),
            )
        }
        ClientPlayNetworking.send(ResizeBoundsC2SPayload(originPos, newBounds))
        onClose()
    }

    private fun getBounds() =
        (minecraft?.level?.getBlockEntity(originPos) as? RedstoneSpecBlockEntity)?.spec?.bounds
}
```

- [ ] **Step 2: Compile check**

```bash
cmd.exe /c "./gradlew.bat :versions:26.1:compileKotlin"
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add src/client/kotlin/com/breadmoirai/redstonespecs/client/screen/SpecBoundsScreen.kt
git commit -m "feat: rewrite SpecBoundsScreen with layout primitives"
```

---

### Task 5: Rewrite SpecOverviewScreen

**Files:**
- Rewrite: `src/client/kotlin/com/breadmoirai/redstonespecs/client/screen/SpecOverviewScreen.kt`

State: `idEditMode` and `structureEditMode` control whether a `StringWidget` or `EditBox` appears in those rows. A `ScrollableLayout` wraps the entry list. The semi-transparent panel fill is drawn in `renderBackground()`.

- [ ] **Step 1: Rewrite SpecOverviewScreen.kt**

```kotlin
package com.breadmoirai.redstonespecs.client.screen

import com.breadmoirai.redstonespecs.block.RedstoneSpecBlockEntity
import com.breadmoirai.redstonespecs.data.*
import com.breadmoirai.redstonespecs.network.*
import dev.isxander.yacl3.gui.LowProfileButtonWidget
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.CycleButton
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.components.StringWidget
import net.minecraft.client.gui.layouts.FrameLayout
import net.minecraft.client.gui.layouts.LinearLayout
import net.minecraft.client.gui.layouts.SpacerElement
import net.minecraft.client.gui.screens.Screen
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.common.custom.CustomPacketPayload

class SpecOverviewScreen(
    val originPos: BlockPos,
    val availableStructures: List<String>,
) : Screen(Component.translatable("screen.redstonespecs.spec_overview")) {

    private var idEditMode = false
    private var structureEditMode = false
    private var idEditBox: EditBox? = null
    private var structureEditBox: EditBox? = null
    private var lifespanBox: IntEditBox? = null

    override fun isPauseScreen() = false
    override fun isInGameUi() = true

    override fun init() {
        super.init()
        val spec = getSpec()

        val content = LinearLayout.vertical().spacing(4)

        content.addChild(StringWidget(Component.translatable("screen.redstonespecs.spec_overview"), font))
        content.addChild(SpacerElement.height(2))

        // ID row
        val idRow = LinearLayout.horizontal().spacing(4)
        idRow.addChild(StringWidget(Component.literal("ID:"), font))
        if (idEditMode) {
            idEditBox = EditBox(font, 160, 20, Component.empty()).also {
                it.value = spec?.id ?: ""
                idRow.addChild(it)
            }
            idRow.addChild(LowProfileButtonWidget(0, 0, 20, 20, Component.literal("✔")) {
                val newId = idEditBox?.value?.trim()?.takeIf { v -> v.isNotBlank() } ?: return@LowProfileButtonWidget
                sendPacket(SetSpecIdC2SPayload(originPos, newId))
                idEditMode = false; rebuildWidgets()
            })
        } else {
            idRow.addChild(StringWidget(Component.literal(spec?.id ?: "(none)"), font))
            idRow.addChild(LowProfileButtonWidget(0, 0, 20, 20, Component.literal("✎")) {
                idEditMode = true; rebuildWidgets()
            })
        }
        content.addChild(idRow)

        // Mode row
        val modeRow = LinearLayout.horizontal().spacing(8)
        modeRow.addChild(StringWidget(Component.literal("Mode:"), font))
        modeRow.addChild(CycleButton.builder<SpecMode>(
            { mode -> Component.literal(mode.name.lowercase().replaceFirstChar { it.uppercase() }) },
            spec?.mode ?: SpecMode.SIMPLE
        ).withValues(*SpecMode.entries.toTypedArray())
            .create(0, 0, 120, 20, Component.empty()) { _, mode ->
                sendPacket(SetSpecModeC2SPayload(originPos, mode))
            })
        content.addChild(modeRow)

        // Lifespan row
        val lifeRow = LinearLayout.horizontal().spacing(4)
        lifeRow.addChild(StringWidget(Component.literal("Life:"), font))
        val currentLifespan = spec?.lifespan ?: 20
        lifespanBox = IntEditBox(font, 60, 20, 1, Int.MAX_VALUE, currentLifespan) { l ->
            sendPacket(SetLifespanC2SPayload(originPos, l))
        }.also { lifeRow.addChild(it) }
        lifeRow.addChild(buttonOf("-") { lifespanBox!!.setValue(lifespanBox!!.getValue() - 1) })
        lifeRow.addChild(buttonOf("+") { lifespanBox!!.setValue(lifespanBox!!.getValue() + 1) })
        content.addChild(lifeRow)

        // Structure row
        val structRow = LinearLayout.horizontal().spacing(4)
        structRow.addChild(StringWidget(Component.literal("Struct:"), font))
        if (structureEditMode) {
            structureEditBox = EditBox(font, 160, 20, Component.empty()).also {
                it.value = spec?.structure ?: (spec?.id ?: "")
                structRow.addChild(it)
            }
            structRow.addChild(LowProfileButtonWidget(0, 0, 20, 20, Component.literal("✔")) {
                val s = structureEditBox?.value?.trim()
                sendPacket(SetStructureC2SPayload(originPos, s?.ifBlank { null }))
                structureEditMode = false; rebuildWidgets()
            })
        } else {
            structRow.addChild(StringWidget(Component.literal(spec?.structure ?: "(none)"), font))
            structRow.addChild(LowProfileButtonWidget(0, 0, 20, 20, Component.literal("✎")) {
                structureEditMode = true; rebuildWidgets()
            })
        }
        content.addChild(structRow)

        content.addChild(SpacerElement.height(2))

        // Entry list
        val entries = spec?.allEntries ?: emptyList()
        val listContent = LinearLayout.vertical().spacing(2)
        entries.forEach { entry ->
            val tag = when (entry) {
                is InputSpec -> "IN"
                is OutputSpec -> "OUT"
                is BreakpointSpec -> "BP"
                is AutoSpec -> "AUTO"
            }
            listContent.addChild(LowProfileButtonWidget(0, 0, 280, 20,
                Component.literal("$tag  ${entry.label.ifEmpty { "(unlabeled)" }}  (${entry.pos.x},${entry.pos.y},${entry.pos.z})")
            ) {
                minecraft?.setScreen(SpecEditorScreen(originPos, entry.pos))
            })
        }
        val scrollable = net.minecraft.client.gui.components.ScrollableLayout(minecraft!!, listContent, 100)
        content.addChild(scrollable)

        content.addChild(SpacerElement.height(2))

        // Result label
        val be = getBe()
        val result = be?.lastTestResult
        if (result != null) {
            val text = "${result.passCount}/${result.checks.size} checks ${if (result.pass) "passed" else "failed"}"
            val label = StringWidget(Component.literal(text), font)
            content.addChild(label)
        }

        // Action buttons
        val actions = LinearLayout.horizontal().spacing(4)
        actions.addChild(LowProfileButtonWidget(0, 0, 60, 20,
            Component.translatable("screen.redstonespecs.spec_overview.run")) {
            sendPacket(RunSpecC2SPayload(originPos))
        })
        actions.addChild(LowProfileButtonWidget(0, 0, 60, 20, Component.literal("Load")) {
            val id = spec?.id ?: return@LowProfileButtonWidget
            sendPacket(LoadSpecC2SPayload(originPos, id))
        })
        actions.addChild(LowProfileButtonWidget(0, 0, 60, 20, Component.literal("Save")) {
            sendPacket(SaveSpecC2SPayload(originPos))
        })
        actions.addChild(LowProfileButtonWidget(0, 0, 60, 20, Component.literal("Done")) { onClose() })
        content.addChild(actions)

        FrameLayout.centerInRectangle(content, 0, 0, width, height)
        content.arrangeElements()
        content.visitWidgets { addRenderableWidget(it) }
    }

    private fun buttonOf(label: String, onClick: () -> Unit) =
        net.minecraft.client.gui.components.Button.builder(Component.literal(label)) { onClick() }.size(20, 20).build()

    override fun renderBackground(guiGraphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.renderBackground(guiGraphics, mouseX, mouseY, partialTick)
        // panel fill behind content — rough bounds, adjust to taste
        guiGraphics.fill(width / 2 - 180, height / 2 - 140, width / 2 + 180, height / 2 + 140, 0xC0101010.toInt())
    }

    override fun onClose() {
        idEditMode = false; structureEditMode = false
        super.onClose()
    }

    private fun getBe() = minecraft?.level?.getBlockEntity(originPos) as? RedstoneSpecBlockEntity
    private fun getSpec() = getBe()?.spec
    private fun sendPacket(payload: CustomPacketPayload) = ClientPlayNetworking.send(payload)
}
```

- [ ] **Step 2: Compile check**

```bash
cmd.exe /c "./gradlew.bat :versions:26.1:compileKotlin"
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add src/client/kotlin/com/breadmoirai/redstonespecs/client/screen/SpecOverviewScreen.kt
git commit -m "feat: rewrite SpecOverviewScreen with layout primitives"
```

---

### Task 6: Rewrite SpecEditorScreen

**Files:**
- Rewrite: `src/client/kotlin/com/breadmoirai/redstonespecs/client/screen/SpecEditorScreen.kt`

Removes `SpecEditorState`, `SpecEditorLazy`, `buildSpecEditorYacl`, `buildCaptureStateButton`, `buildUpdatedEntry`. State (`workingLabel`, `workingColor`, `workingEntries`) is held on the screen directly. The color EditBox drives the `ColorSwatchWidget`. When opening `EntryEditorScreen`, passes `this` as the return target via callback.

- [ ] **Step 1: Rewrite SpecEditorScreen.kt**

```kotlin
package com.breadmoirai.redstonespecs.client.screen

import com.breadmoirai.redstonespecs.block.RedstoneSpecBlockEntity
import com.breadmoirai.redstonespecs.data.*
import com.breadmoirai.redstonespecs.network.RemoveSpecEntryC2SPayload
import com.breadmoirai.redstonespecs.network.SaveSpecEntryC2SPayload
import com.breadmoirai.redstonespecs.runner.captureBlockStateProps
import com.breadmoirai.redstonespecs.runner.propsToCondition
import dev.isxander.yacl3.gui.LowProfileButtonWidget
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.components.ScrollableLayout
import net.minecraft.client.gui.components.StringWidget
import net.minecraft.client.gui.layouts.FrameLayout
import net.minecraft.client.gui.layouts.LinearLayout
import net.minecraft.client.gui.layouts.SpacerElement
import net.minecraft.client.gui.screens.Screen
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component

class SpecEditorScreen(
    private val originPos: BlockPos,
    private val entryRelPos: BlockPos,
) : Screen(Component.translatable("screen.redstonespecs.spec_editor")) {

    private var launched = false
    private var workingLabel = ""
    private var workingColor = 0xFFFFFF
    private var workingEntries: MutableList<Pair<SimTime, StateCondition>>? = null
    private var originalEntry: SpecEntry? = null
    private var specMode: SpecMode = SpecMode.SIMPLE

    private var colorEditBox: EditBox? = null
    private var colorSwatch: ColorSwatchWidget? = null

    override fun isPauseScreen() = false
    override fun isInGameUi() = true

    override fun init() {
        super.init()
        if (!launched) return
        buildLayout()
    }

    override fun tick() {
        super.tick()
        if (launched) return
        val be = minecraft?.level?.getBlockEntity(originPos) as? RedstoneSpecBlockEntity ?: return
        val entry = be.spec?.entryAt(entryRelPos) ?: return
        launched = true
        originalEntry = entry
        workingLabel = entry.label
        workingColor = entry.color
        workingEntries = when (entry) {
            is InputSpec -> entry.entries.toMutableList()
            is OutputSpec -> entry.entries.toMutableList()
            else -> null
        }
        specMode = be.spec?.mode ?: SpecMode.SIMPLE
        rebuildWidgets()
    }

    private fun buildLayout() {
        val entry = originalEntry ?: return
        val typeLabel = when (entry) {
            is InputSpec -> "Input"
            is OutputSpec -> "Output"
            is BreakpointSpec -> "Breakpoint"
            is AutoSpec -> "AutoSpec"
        }

        val content = LinearLayout.vertical().spacing(6)

        content.addChild(StringWidget(
            Component.literal("$typeLabel @ $entryRelPos"), font))

        // Label row
        val labelRow = LinearLayout.horizontal().spacing(6)
        labelRow.addChild(StringWidget(Component.literal("Label:"), font))
        val labelBox = EditBox(font, 160, 20, Component.empty()).also {
            it.value = workingLabel
            it.setResponder { v -> workingLabel = v }
        }
        labelRow.addChild(labelBox)
        content.addChild(labelRow)

        // Color row
        val colorRow = LinearLayout.horizontal().spacing(6)
        colorRow.addChild(StringWidget(Component.literal("Color:"), font))
        colorEditBox = EditBox(font, 80, 20, Component.empty()).also {
            it.value = "%06X".format(workingColor)
            it.setResponder { hex ->
                val rgb = hex.toLongOrNull(16)?.toInt()?.and(0xFFFFFF) ?: return@setResponder
                workingColor = rgb
                colorSwatch?.setColor(rgb)
            }
            colorRow.addChild(it)
        }
        colorSwatch = ColorSwatchWidget(0, 0, workingColor).also { colorRow.addChild(it) }
        content.addChild(colorRow)

        // Entries section (Input / Output only)
        val entries = workingEntries
        if (entries != null) {
            content.addChild(StringWidget(Component.literal("Entries:"), font))

            val listLayout = LinearLayout.vertical().spacing(2)
            entries.forEachIndexed { i, (simTime, condition) ->
                val row = LinearLayout.horizontal().spacing(4)
                row.addChild(StringWidget(Component.literal(previewEntry(simTime, condition)), font)
                    .setMaxWidth(200))
                row.addChild(LowProfileButtonWidget(0, 0, 50, 20, Component.literal("Edit")) {
                    openEntryEditor(i, simTime, condition)
                })
                row.addChild(LowProfileButtonWidget(0, 0, 60, 20, Component.literal("Remove")) {
                    entries.removeAt(i)
                    rebuildWidgets()
                })
                listLayout.addChild(row)
            }
            content.addChild(ScrollableLayout(minecraft!!, listLayout, 120))

            content.addChild(LowProfileButtonWidget(0, 0, 100, 20, Component.literal("Add Entry")) {
                openEntryEditor(null, null, null)
            })
            content.addChild(LowProfileButtonWidget(0, 0, 120, 20, Component.literal("Capture State")) {
                captureState()
            })
        }

        content.addChild(SpacerElement.height(4))
        content.addChild(LowProfileButtonWidget(0, 0, 110, 20, Component.literal("Remove Spec")) {
            ClientPlayNetworking.send(RemoveSpecEntryC2SPayload(originPos, entryRelPos))
            minecraft?.setScreen(null)
        })

        val bottomButtons = LinearLayout.horizontal().spacing(8)
        bottomButtons.addChild(LowProfileButtonWidget(0, 0, 80, 20, Component.literal("Save")) { save() })
        bottomButtons.addChild(LowProfileButtonWidget(0, 0, 80, 20, Component.literal("Cancel")) { onClose() })
        content.addChild(bottomButtons)

        FrameLayout.centerInRectangle(content, 0, 0, width, height)
        content.arrangeElements()
        content.visitWidgets { addRenderableWidget(it) }
    }

    private fun openEntryEditor(index: Int?, simTime: SimTime?, condition: StateCondition?) {
        val thisScreen = this
        minecraft?.setScreen(EntryEditorScreen(
            originPos = originPos,
            entryRelPos = entryRelPos,
            specMode = specMode,
            initial = if (simTime != null && condition != null) simTime to condition else null,
            onConfirm = { st, cond ->
                if (index == null) workingEntries?.add(st to cond)
                else workingEntries?.set(index, st to cond)
                minecraft?.setScreen(thisScreen)
            }
        ))
    }

    private fun captureState() {
        val level = minecraft?.level ?: return
        val worldPos = originPos.offset(entryRelPos)
        val blockState = level.getBlockState(worldPos)
        val currentProps = captureBlockStateProps(blockState)
        val entries = workingEntries ?: return

        if (entries.isEmpty()) {
            entries.add(0, SimTime.INIT to propsToCondition(currentProps, blockState))
            rebuildWidgets(); return
        }
        val lastEntry = entries.maxByOrNull { it.first } ?: return
        val lastKnown = flattenConditionToMap(lastEntry.second)
        val diff = currentProps.filter { (k, v) -> lastKnown[k] != v }
        if (diff.isEmpty()) return
        val newTick = if (lastEntry.first == SimTime.INIT) 0 else lastEntry.first.tick + 1
        entries.add(SimTime(newTick, Phase.END_OF_TICK) to propsToCondition(diff, blockState))
        rebuildWidgets()
    }

    private fun save() {
        val entry = originalEntry ?: return
        val updated = when (entry) {
            is InputSpec -> entry.copy(label = workingLabel, color = workingColor,
                entries = workingEntries?.toList() ?: entry.entries)
            is OutputSpec -> entry.copy(label = workingLabel, color = workingColor,
                entries = workingEntries?.toList() ?: entry.entries)
            is BreakpointSpec -> entry.copy(label = workingLabel, color = workingColor)
            is AutoSpec -> entry.copy(label = workingLabel, color = workingColor)
        }
        ClientPlayNetworking.send(SaveSpecEntryC2SPayload(originPos, updated))
        onClose()
    }
}
```

- [ ] **Step 2: Compile check**

```bash
cmd.exe /c "./gradlew.bat :versions:26.1:compileKotlin"
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add src/client/kotlin/com/breadmoirai/redstonespecs/client/screen/SpecEditorScreen.kt
git commit -m "feat: rewrite SpecEditorScreen with layout primitives"
```

---

### Task 7: Rewrite EntryEditorScreen

**Files:**
- Rewrite: `src/client/kotlin/com/breadmoirai/redstonespecs/client/screen/EntryEditorScreen.kt`

Removes `buildEntryEditorYacl` and the YACL imports. Receives `specMode: SpecMode` to gate Tick/Phase rows. `PropState` sealed class and all helpers below it (`buildPropStates`, `prePopulate`) are kept unchanged. `previewEntry`, `previewCondition`, `flattenConditionToMap` move here from `SpecEditorScreen.kt` (they were previously in the same file; ensure `SpecEditorScreen.kt` references them correctly — they will be package-level in `EntryEditorScreen.kt`).

- [ ] **Step 1: Rewrite EntryEditorScreen.kt**

```kotlin
package com.breadmoirai.redstonespecs.client.screen

import com.breadmoirai.redstonespecs.data.Phase
import com.breadmoirai.redstonespecs.data.SimTime
import com.breadmoirai.redstonespecs.data.SpecMode
import com.breadmoirai.redstonespecs.data.StateCondition
import dev.isxander.yacl3.gui.LowProfileButtonWidget
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.components.CycleButton
import net.minecraft.client.gui.components.ScrollableLayout
import net.minecraft.client.gui.components.StringWidget
import net.minecraft.client.gui.layouts.FrameLayout
import net.minecraft.client.gui.layouts.LinearLayout
import net.minecraft.client.gui.layouts.SpacerElement
import net.minecraft.client.gui.screens.Screen
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.BooleanProperty
import net.minecraft.world.level.block.state.properties.IntegerProperty
import net.minecraft.world.level.block.state.properties.Property

// ── PropState — mirrors block property state without widget code ──────────

private sealed class PropState {
    abstract val name: String
    abstract var included: Boolean
    abstract fun toCondition(): StateCondition?

    class Block(val blockId: Identifier, override var included: Boolean = false) : PropState() {
        override val name = "block"
        override fun toCondition() = if (included) StateCondition.BlockType(blockId) else null
    }

    class Bool(override val name: String, override var included: Boolean, var value: Boolean) : PropState() {
        override fun toCondition() = if (included) StateCondition.BoolProperty(name, value) else null
    }

    class Int(override val name: String, override var included: Boolean, var value: kotlin.Int,
              val min: kotlin.Int, val max: kotlin.Int) : PropState() {
        override fun toCondition() = if (included) StateCondition.IntProperty(name, value) else null
    }

    class Enum(override val name: String, override var included: Boolean, var value: String,
               val options: List<String>) : PropState() {
        override fun toCondition() = if (included) StateCondition.EnumProperty(name, value) else null
    }
}

// ── Entry editor screen ───────────────────────────────────────────────────

class EntryEditorScreen(
    private val originPos: BlockPos,
    private val entryRelPos: BlockPos,
    private val specMode: SpecMode,
    private val initial: Pair<SimTime, StateCondition>?,
    private val onConfirm: (SimTime, StateCondition) -> Unit,
) : Screen(Component.literal(if (initial == null) "Add Entry" else "Edit Entry")) {

    private val mc = Minecraft.getInstance()
    private val worldPos = originPos.offset(entryRelPos)
    private val blockState: BlockState = mc.level?.getBlockState(worldPos)
        ?: error("Level not available when opening EntryEditorScreen")
    private val propStates = buildPropStates(blockState, initial?.second)

    private var tickBox: IntEditBox? = null
    private var phaseButton: CycleButton<Phase>? = null

    override fun isPauseScreen() = false
    override fun isInGameUi() = true

    override fun init() {
        super.init()

        val advancedPhases = Phase.entries.filter { it != Phase.USER_INTERACTION }
        val initialTick = initial?.first?.tick ?: -1
        val initialPhase = initial?.first?.phase ?: Phase.END_OF_TICK

        val content = LinearLayout.vertical().spacing(6)

        content.addChild(StringWidget(title, font))
        content.addChild(SpacerElement.height(2))

        // Tick row — TICK_AWARE or UPDATE_AWARE
        if (specMode == SpecMode.TICK_AWARE || specMode == SpecMode.UPDATE_AWARE) {
            val tickRow = LinearLayout.horizontal().spacing(4)
            tickRow.addChild(StringWidget(Component.literal("Tick:"), font))
            tickBox = IntEditBox(font, 70, 20, -1, Int.MAX_VALUE, initialTick) {}.also {
                tickRow.addChild(it)
            }
            tickRow.addChild(net.minecraft.client.gui.components.Button.builder(Component.literal("-")) {
                tickBox!!.setValue(tickBox!!.getValue() - 1)
            }.size(20, 20).build())
            tickRow.addChild(net.minecraft.client.gui.components.Button.builder(Component.literal("+")) {
                tickBox!!.setValue(tickBox!!.getValue() + 1)
            }.size(20, 20).build())
            content.addChild(tickRow)
        }

        // Phase row — UPDATE_AWARE only
        if (specMode == SpecMode.UPDATE_AWARE) {
            val phaseRow = LinearLayout.horizontal().spacing(8)
            phaseRow.addChild(StringWidget(Component.literal("Phase:"), font))
            phaseButton = CycleButton.builder<Phase>(
                { p -> Component.literal(p.name.lowercase().replaceFirstChar { it.uppercase() }) },
                initialPhase
            ).withValues(advancedPhases)
                .create(0, 0, 140, 20, Component.empty()) { _, _ -> /* stored in button */ }
            phaseRow.addChild(phaseButton!!)
            content.addChild(phaseRow)
        }

        // Conditions
        content.addChild(StringWidget(Component.literal("Conditions:"), font))
        val condLayout = LinearLayout.vertical().spacing(2)
        propStates.forEach { ps -> condLayout.addChild(buildPropRow(ps)) }
        content.addChild(ScrollableLayout(minecraft!!, condLayout, 150))

        content.addChild(SpacerElement.height(4))

        val buttons = LinearLayout.horizontal().spacing(8)
        buttons.addChild(LowProfileButtonWidget(0, 0, 80, 20, Component.literal("Confirm")) { confirm() })
        buttons.addChild(LowProfileButtonWidget(0, 0, 80, 20, Component.literal("Cancel")) { onClose() })
        content.addChild(buttons)

        FrameLayout.centerInRectangle(content, 0, 0, width, height)
        content.arrangeElements()
        content.visitWidgets { addRenderableWidget(it) }
    }

    private fun buildPropRow(ps: PropState): LinearLayout {
        val row = LinearLayout.horizontal().spacing(6)
        row.addChild(StringWidget(Component.literal(ps.name), font).setMaxWidth(100))
        val skipLabel = "—"
        val allValues: List<String> = when (ps) {
            is PropState.Block -> listOf(skipLabel, "✓")
            is PropState.Bool  -> listOf(skipLabel, "false", "true")
            is PropState.Int   -> listOf(skipLabel) + (ps.min..ps.max).map { it.toString() }
            is PropState.Enum  -> listOf(skipLabel) + ps.options
        }
        val currentValue: String = when {
            !ps.included -> skipLabel
            ps is PropState.Block -> "✓"
            ps is PropState.Bool  -> ps.value.toString()
            ps is PropState.Int   -> ps.value.toString()
            ps is PropState.Enum  -> ps.value
            else -> skipLabel
        }
        row.addChild(CycleButton.builder<String>(
            { v -> Component.literal(v) },
            currentValue
        ).withValues(allValues)
            .create(0, 0, 120, 20, Component.empty()) { _, v ->
                when {
                    v == skipLabel -> ps.included = false
                    ps is PropState.Block -> ps.included = true
                    ps is PropState.Bool  -> { ps.included = true; ps.value = v.toBooleanStrict() }
                    ps is PropState.Int   -> { ps.included = true; ps.value = v.toInt() }
                    ps is PropState.Enum  -> { ps.included = true; ps.value = v }
                }
            })
        return row
    }

    private fun confirm() {
        val rawTick = tickBox?.getValue() ?: -1
        val phase = phaseButton?.value ?: Phase.END_OF_TICK
        val simTime = if (rawTick < 0) SimTime.INIT else SimTime(rawTick, phase)
        val conditions = propStates.mapNotNull { it.toCondition() }
        if (conditions.isEmpty()) return
        val condition = if (conditions.size == 1) conditions[0] else StateCondition.All(conditions)
        onConfirm(simTime, condition)
        onClose()
    }
}

// ── State construction helpers (unchanged logic) ──────────────────────────

private fun buildPropStates(state: BlockState, condition: StateCondition?): List<PropState> {
    val blockId = BuiltInRegistries.BLOCK.getKey(state.block)
    val props = mutableListOf<PropState>(PropState.Block(blockId))
    for (prop in state.block.stateDefinition.properties) {
        props += when (prop) {
            is BooleanProperty -> PropState.Bool(prop.name, false, state.getValue(prop))
            is IntegerProperty -> {
                val min = prop.possibleValues.minOrNull() ?: 0
                val max = prop.possibleValues.maxOrNull() ?: 15
                PropState.Int(prop.name, false, state.getValue(prop), min, max)
            }
            else -> {
                @Suppress("UNCHECKED_CAST")
                val cast = prop as Property<Comparable<Any>>
                PropState.Enum(
                    name = prop.name,
                    included = false,
                    value = cast.getName(state.getValue(prop)),
                    options = prop.possibleValues.map { cast.getName(it) },
                )
            }
        }
    }
    if (condition != null) prePopulate(props, condition)
    return props
}

private fun prePopulate(props: List<PropState>, condition: StateCondition) {
    when (condition) {
        is StateCondition.All -> condition.conditions.forEach { prePopulate(props, it) }
        is StateCondition.BlockType ->
            props.filterIsInstance<PropState.Block>().firstOrNull()?.included = true
        is StateCondition.BoolProperty ->
            props.filterIsInstance<PropState.Bool>().firstOrNull { it.name == condition.name }
                ?.also { it.included = true; it.value = condition.value }
        is StateCondition.IntProperty ->
            props.filterIsInstance<PropState.Int>().firstOrNull { it.name == condition.name }
                ?.also { it.included = true; it.value = condition.value }
        is StateCondition.EnumProperty ->
            props.filterIsInstance<PropState.Enum>().firstOrNull { it.name == condition.name }
                ?.also { it.included = true; it.value = condition.value }
        else -> {}
    }
}

// ── Preview helpers used by SpecEditorScreen ─────────────────────────────

fun previewEntry(simTime: SimTime, condition: StateCondition): String {
    val timeStr = if (simTime == SimTime.INIT) "INIT" else "t${simTime.tick} ${simTime.phase.name.take(5)}"
    val condStr = previewCondition(condition).let { if (it.length > 24) it.take(23) + "…" else it }
    return "$timeStr: $condStr"
}

fun previewCondition(condition: StateCondition): String = when (condition) {
    is StateCondition.BoolProperty -> "${condition.name}=${condition.value}"
    is StateCondition.IntProperty -> "${condition.name}=${condition.value}"
    is StateCondition.EnumProperty -> "${condition.name}=${condition.value}"
    is StateCondition.BlockType -> "block=${condition.blockId.path}"
    is StateCondition.All -> condition.conditions.joinToString(",") { previewCondition(it) }
    is StateCondition.Any -> condition.conditions.joinToString("|") { previewCondition(it) }
    is StateCondition.Not -> "!${previewCondition(condition.condition)}"
    is StateCondition.ContainerContents -> "container(...)"
}

fun flattenConditionToMap(condition: StateCondition): Map<String, String> {
    val out = mutableMapOf<String, String>()
    fun walk(c: StateCondition) {
        when (c) {
            is StateCondition.All -> c.conditions.forEach(::walk)
            is StateCondition.BoolProperty -> out[c.name] = c.value.toString()
            is StateCondition.IntProperty -> out[c.name] = c.value.toString()
            is StateCondition.EnumProperty -> out[c.name] = c.value
            else -> {}
        }
    }
    walk(condition)
    return out
}
```

- [ ] **Step 2: Compile check**

```bash
cmd.exe /c "./gradlew.bat :versions:26.1:compileKotlin"
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Run all unit tests**

```bash
cmd.exe /c "./gradlew.bat :versions:26.1:test"
```

Expected: BUILD SUCCESSFUL, all tests pass.

- [ ] **Step 4: Commit**

```bash
git add src/client/kotlin/com/breadmoirai/redstonespecs/client/screen/EntryEditorScreen.kt
git commit -m "feat: rewrite EntryEditorScreen with layout primitives and SpecMode gating"
```

---

## Testing checklist (manual, run game)

After all tasks compile, run the game with:

```bash
cmd.exe /c "./gradlew.bat :versions:26.1:runClient"
```

Verify each screen:

- [ ] **ModConfig**: Open via ModMenu → Settings. Only "Auto-save on exit" and "Spec Save Directory" options visible. No "Redstone Developer Level".
- [ ] **SpecBoundsScreen**: Place a redstone spec block, use the bounds interaction. Verify 6 IntEditBox fields, scroll adjusts values, − and + buttons work, mode toggle converts values without resetting them, Save sends correct bounding box.
- [ ] **SpecOverviewScreen**: Right-click spec block. Verify ID/Structure inline edit (pencil button → EditBox → checkmark), Mode CycleButton cycles through SIMPLE/TICK_AWARE/UPDATE_AWARE, Life IntEditBox adjusts lifespan, entry list scrolls, Run/Load/Save/Done buttons work.
- [ ] **SpecEditorScreen (Input or Output marker)**: Click entry row in overview. Verify label EditBox, hex color EditBox updates swatch, Entries list shows with Edit/Remove buttons, Add Entry / Capture State / Remove Spec / Save / Cancel work.
- [ ] **SpecEditorScreen (Breakpoint or Auto marker)**: Verify no Entries section shown.
- [ ] **EntryEditorScreen (SIMPLE mode spec)**: Click Add Entry. Verify no Tick row, no Phase row.
- [ ] **EntryEditorScreen (TICK_AWARE mode spec)**: Click Add Entry. Verify Tick row shown, no Phase row.
- [ ] **EntryEditorScreen (UPDATE_AWARE mode spec)**: Click Add Entry. Verify both Tick and Phase rows shown.
- [ ] **EntryEditorScreen all modes**: Verify Conditions scrollable list, each property row has a CycleButton cycling through — and all values. Confirm fires callback and returns to SpecEditorScreen. Cancel discards.
