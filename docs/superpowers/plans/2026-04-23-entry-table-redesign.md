# Entry Table Redesign — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the two-screen entry editing flow (SpecEditorScreen → EntryEditorScreen) with an inline scrollable flat-row table; add `StateCondition.IntRange`; auto-name new spec marker entries from the block name.

**Architecture:** Working state in `SpecEditorScreen` is a `MutableList<FlatRow>` — one row per leaf condition — flattened from `List<Pair<SimTime, StateCondition>>` on load and reconstituted on save. A new `DropdownButton<T>` handles PROPERTY and PHASE selection inline. `EntryEditorScreen` is deleted entirely.

**Tech Stack:** Kotlin, Minecraft Fabric 26.1, MC GUI (`AbstractButton`, `LinearLayout`, `ScrollableLayout`, `CycleButton`, `EditBox`), YACL 3.x (`LowProfileButtonWidget`), Mojang Serialization Codecs, JUnit 5 + fabric-loader-junit.

---

## File Map

| Status | File | Responsibility |
|---|---|---|
| Modify | `src/main/kotlin/com/breadmoirai/redstonespecs/data/StateCondition.kt` | Add `IntRange` variant + codec entry |
| Modify | `src/test/kotlin/com/breadmoirai/redstonespecs/data/StateConditionTest.kt` | Add `IntRange` roundtrip test |
| Modify | `src/main/kotlin/com/breadmoirai/redstonespecs/item/SpecMarkerTool.kt` | Add `nextLabel()` top-level fn + `defaultLabel()` helper + update all 4 marker items |
| Modify | `src/test/kotlin/com/breadmoirai/redstonespecs/data/SpecMarkerToolTest.kt` | Add naming logic tests |
| Modify | `src/client/kotlin/com/breadmoirai/redstonespecs/client/screen/IntEditBox.kt` | Add `onHoverEnd` callback |
| Create | `src/client/kotlin/com/breadmoirai/redstonespecs/client/screen/DropdownButton.kt` | Reusable dropdown widget |
| Create | `src/client/kotlin/com/breadmoirai/redstonespecs/client/screen/FlatRow.kt` | `RowProp`, `FlatRow`, `flattenEntries`, `flattenCondition`, `reconstitute` |
| Create | `src/test/kotlin/com/breadmoirai/redstonespecs/data/FlatRowTest.kt` | Unit tests for flatten/reconstitute logic |
| Rewrite | `src/client/kotlin/com/breadmoirai/redstonespecs/client/screen/SpecEditorScreen.kt` | Inline entry table; `flattenConditionToMap` deleted (captureState no longer needs it) |
| Delete | `src/client/kotlin/com/breadmoirai/redstonespecs/client/screen/EntryEditorScreen.kt` | Replaced by inline table |

---

### Task 1: `StateCondition.IntRange` — data model + codec

**Files:**
- Modify: `src/main/kotlin/com/breadmoirai/redstonespecs/data/StateCondition.kt`
- Modify: `src/test/kotlin/com/breadmoirai/redstonespecs/data/StateConditionTest.kt`

- [ ] **Step 1: Write the failing test**

Add inside `StateConditionTest` class body after the existing `@Test` methods:

```kotlin
@Test
fun `IntRange roundtrip`() {
    val cond = StateCondition.IntRange("power", 1, 15)
    assertEquals(cond, roundtrip(cond, StateCondition.CODEC))
}
```

- [ ] **Step 2: Run to verify it fails**

```
cmd.exe /c "./gradlew.bat test --tests \"com.breadmoirai.redstonespecs.data.StateConditionTest.IntRange roundtrip\""
```

Expected: FAIL — `StateCondition.IntRange` does not exist.

- [ ] **Step 3: Add `IntRange` to the sealed class**

In `StateCondition.kt`, add after `data class ContainerContents(...)`:

```kotlin
data class IntRange(val name: String, val min: Int, val max: Int) : StateCondition()
```

- [ ] **Step 4: Add `IntRange` to the codec**

Inside the `Codec.lazyInitialized { ... }` block in `StateCondition.CODEC`:

Add this codec definition after `containerContentsCodec`:

```kotlin
val intRangeCodec: MapCodec<IntRange> = RecordCodecBuilder.mapCodec { instance ->
    instance.group(
        Codec.STRING.fieldOf("name").forGetter(IntRange::name),
        Codec.INT.fieldOf("min").forGetter(IntRange::min),
        Codec.INT.fieldOf("max").forGetter(IntRange::max),
    ).apply(instance, ::IntRange)
}
```

Add to `codecMap`:

```kotlin
"int_range" to intRangeCodec,
```

Add to the `when` dispatch block (after `is ContainerContents -> "container_contents"`):

```kotlin
is IntRange -> "int_range"
```

- [ ] **Step 5: Run test to verify it passes**

```
cmd.exe /c "./gradlew.bat test --tests \"com.breadmoirai.redstonespecs.data.StateConditionTest.IntRange roundtrip\""
```

Expected: PASS

- [ ] **Step 6: Commit**

```
git add src/main/kotlin/com/breadmoirai/redstonespecs/data/StateCondition.kt src/test/kotlin/com/breadmoirai/redstonespecs/data/StateConditionTest.kt
git commit -m "feat: add StateCondition.IntRange with codec"
```

---

### Task 2: Spec marker default naming

**Files:**
- Modify: `src/main/kotlin/com/breadmoirai/redstonespecs/item/SpecMarkerTool.kt`
- Modify: `src/test/kotlin/com/breadmoirai/redstonespecs/data/SpecMarkerToolTest.kt`

- [ ] **Step 1: Write failing tests**

Replace the full content of `SpecMarkerToolTest.kt`:

```kotlin
package com.breadmoirai.redstonespecs.data

import com.breadmoirai.redstonespecs.item.nextLabel
import net.minecraft.core.BlockPos
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SpecMarkerToolTest {

    @Test
    fun `InputSpec created with INIT entry from captured props`() {
        val pos = BlockPos(1, 0, 0)
        val initEntries = listOf(SimTime.INIT to StateCondition.BoolProperty("powered", false))
        val entry = InputSpec(pos, "", 0x4488FF, initEntries)
        assertEquals(pos, entry.pos)
        assertEquals(1, entry.entries.size)
        assertEquals(SimTime.INIT, entry.entries.first().first)
    }

    @Test
    fun `OutputSpec created with INIT entry from captured props`() {
        val pos = BlockPos(2, 0, 0)
        val initEntries = listOf(SimTime.INIT to StateCondition.BoolProperty("lit", false))
        val entry = OutputSpec(pos, "", 0x44FF88, initEntries)
        assertEquals(pos, entry.pos)
        assertEquals(SimTime.INIT, entry.entries.first().first)
    }

    @Test
    fun `nextLabel returns a when no existing labels`() {
        assertEquals("lever_a", nextLabel("lever", emptySet()))
    }

    @Test
    fun `nextLabel skips taken suffixes`() {
        assertEquals("lever_c", nextLabel("lever", setOf("lever_a", "lever_b")))
    }

    @Test
    fun `nextLabel ignores labels from other blocks`() {
        assertEquals("lever_a", nextLabel("lever", setOf("button_a", "stone_button_a")))
    }

    @Test
    fun `nextLabel wraps to double letter after z`() {
        val existing = ('a'..'z').map { "lever_$it" }.toSet()
        assertEquals("lever_aa", nextLabel("lever", existing))
    }
}
```

- [ ] **Step 2: Run to verify it fails**

```
cmd.exe /c "./gradlew.bat test --tests \"com.breadmoirai.redstonespecs.data.SpecMarkerToolTest\""
```

Expected: FAIL — `nextLabel` does not exist in `com.breadmoirai.redstonespecs.item`.

- [ ] **Step 3: Add `nextLabel` and `defaultLabel` to `SpecMarkerTool.kt`**

Add this top-level function before the `LOGGER` line at the top of `SpecMarkerTool.kt`:

```kotlin
internal fun nextLabel(blockName: String, existing: Set<String>): String {
    var suffix = 'a'
    while ("${blockName}_${suffix}" in existing) suffix++
    return "${blockName}_${suffix}"
}
```

Add this import (if not already present):

```kotlin
import net.minecraft.core.registries.BuiltInRegistries
```

Add this protected method inside the `SpecMarkerTool` abstract class body:

```kotlin
protected fun defaultLabel(initState: BlockState, spec: RedstoneSpec): String {
    val blockName = BuiltInRegistries.BLOCK.getKey(initState.block).path
    return nextLabel(blockName, spec.allEntries.map { it.label }.toSet())
}
```

- [ ] **Step 4: Update all four marker items to use `defaultLabel`**

Replace the hardcoded `""` label in each `createEntry`:

`InputSpecMarkerItem`:
```kotlin
override fun createEntry(relPos: BlockPos, initProps: Map<String, String>, initState: BlockState, spec: RedstoneSpec): SpecEntry =
    InputSpec(relPos, defaultLabel(initState, spec), 0x4488FF, listOf(SimTime.INIT to propsToCondition(initProps, initState)))
```

`OutputSpecMarkerItem`:
```kotlin
override fun createEntry(relPos: BlockPos, initProps: Map<String, String>, initState: BlockState, spec: RedstoneSpec): SpecEntry {
    val time = if (spec.mode == SpecMode.SIMPLE) SimTime(spec.lifespan, Phase.END_OF_TICK) else SimTime.INIT
    return OutputSpec(relPos, defaultLabel(initState, spec), 0x44FF88, listOf(time to propsToCondition(initProps, initState)))
}
```

`BreakpointSpecMarkerItem`:
```kotlin
override fun createEntry(relPos: BlockPos, initProps: Map<String, String>, initState: BlockState, spec: RedstoneSpec): SpecEntry =
    BreakpointSpec(relPos, defaultLabel(initState, spec), 0xFF4444)
```

`AutoSpecMarkerItem`:
```kotlin
override fun createEntry(relPos: BlockPos, initProps: Map<String, String>, initState: BlockState, spec: RedstoneSpec): SpecEntry =
    AutoSpec(relPos, defaultLabel(initState, spec), 0xFFAA00)
```

- [ ] **Step 5: Run tests to verify they pass**

```
cmd.exe /c "./gradlew.bat test --tests \"com.breadmoirai.redstonespecs.data.SpecMarkerToolTest\""
```

Expected: all 6 tests PASS.

- [ ] **Step 6: Commit**

```
git add src/main/kotlin/com/breadmoirai/redstonespecs/item/SpecMarkerTool.kt src/test/kotlin/com/breadmoirai/redstonespecs/data/SpecMarkerToolTest.kt
git commit -m "feat: auto-name spec marker entries from block name with letter suffix"
```

---

### Task 3: `IntEditBox` hover-end callback

**Files:**
- Modify: `src/client/kotlin/com/breadmoirai/redstonespecs/client/screen/IntEditBox.kt`

- [ ] **Step 1: Add `onHoverEnd` parameter and hover tracking**

Replace the full `IntEditBox.kt` content:

```kotlin
package com.breadmoirai.redstonespecs.client.screen

import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphics
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
    private val onHoverEnd: () -> Unit = {},
) : EditBox(font, width, height, Component.empty()) {

    private var wasHovered = false

    init {
        setValue(formatIntValue(initial, min))
        setResponder { text ->
            val parsed = parseIntValue(text, min, max)
            onChange(parsed)
        }
    }

    fun getIntValue(): Int = parseIntValue(getValue(), min, max)

    fun setIntValue(n: Int) {
        val clamped = n.coerceIn(min, max)
        setValue(formatIntValue(clamped, min))
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, horizontalAmount: Double, verticalAmount: Double): Boolean {
        if (!isMouseOver(mouseX, mouseY)) return false
        setIntValue(getIntValue() + verticalAmount.sign.toInt())
        return true
    }

    override fun renderWidget(guiGraphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.renderWidget(guiGraphics, mouseX, mouseY, partialTick)
        val hovered = isMouseOver(mouseX.toDouble(), mouseY.toDouble())
        if (wasHovered && !hovered) onHoverEnd()
        wasHovered = hovered
    }
}
```

- [ ] **Step 2: Verify existing tests still pass**

```
cmd.exe /c "./gradlew.bat test --tests \"com.breadmoirai.redstonespecs.data.IntEditBoxLogicTest\""
```

Expected: PASS — `parseIntValue` and `formatIntValue` are unchanged.

- [ ] **Step 3: Compile-check**

```
cmd.exe /c "./gradlew.bat :26.1:compileKotlin"
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```
git add src/client/kotlin/com/breadmoirai/redstonespecs/client/screen/IntEditBox.kt
git commit -m "feat: add onHoverEnd callback to IntEditBox"
```

---

### Task 4: `DropdownButton<T>` widget

**Files:**
- Create: `src/client/kotlin/com/breadmoirai/redstonespecs/client/screen/DropdownButton.kt`

- [ ] **Step 1: Create `DropdownButton.kt`**

```kotlin
package com.breadmoirai.redstonespecs.client.screen

import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.AbstractButton
import net.minecraft.client.gui.narration.NarrationElementOutput
import net.minecraft.network.chat.Component

class DropdownButton<T>(
    x: Int,
    y: Int,
    width: Int,
    height: Int,
    private val font: Font,
    val options: List<T>,
    private val toComponent: (T) -> Component,
    initial: T,
    private val onChange: (T) -> Unit,
) : AbstractButton(x, y, width, height, Component.empty()) {

    var selected: T = initial
        private set

    private var isOpen = false
    private val itemHeight = 14

    override fun getMessage(): Component = toComponent(selected)

    override fun onPress() {
        isOpen = !isOpen
    }

    fun close() {
        isOpen = false
    }

    override fun renderWidget(guiGraphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.renderWidget(guiGraphics, mouseX, mouseY, partialTick)
        if (!isOpen) return
        guiGraphics.pose().pushPose()
        guiGraphics.pose().translate(0f, 0f, 400f)
        val listY = y + height
        for ((i, option) in options.withIndex()) {
            val iy = listY + i * itemHeight
            val hovered = mouseX in x until x + width && mouseY in iy until iy + itemHeight
            guiGraphics.fill(x, iy, x + width, iy + itemHeight,
                if (hovered) 0xCC555555.toInt() else 0xCC333333.toInt())
            guiGraphics.drawString(font, toComponent(option), x + 4, iy + 3, 0xFFFFFF, false)
        }
        guiGraphics.pose().popPose()
    }

    override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        if (isOpen) {
            val listY = y + height
            val mx = mouseX.toInt()
            val my = mouseY.toInt()
            for ((i, option) in options.withIndex()) {
                val iy = listY + i * itemHeight
                if (mx in x until x + width && my in iy until iy + itemHeight) {
                    selected = option
                    onChange(option)
                    isOpen = false
                    return true
                }
            }
            isOpen = false
            return true
        }
        return super.mouseClicked(mouseX, mouseY, button)
    }

    override fun updateWidgetNarration(output: NarrationElementOutput) {
        defaultButtonNarrationText(output)
    }
}
```

- [ ] **Step 2: Compile-check**

```
cmd.exe /c "./gradlew.bat :26.1:compileKotlin"
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```
git add src/client/kotlin/com/breadmoirai/redstonespecs/client/screen/DropdownButton.kt
git commit -m "feat: add reusable DropdownButton widget"
```

---

### Task 5: `RowProp` / `FlatRow` model + flatten/reconstitute

**Files:**
- Create: `src/client/kotlin/com/breadmoirai/redstonespecs/client/screen/FlatRow.kt`
- Create: `src/test/kotlin/com/breadmoirai/redstonespecs/data/FlatRowTest.kt`

- [ ] **Step 1: Write failing tests**

Create `src/test/kotlin/com/breadmoirai/redstonespecs/data/FlatRowTest.kt`:

```kotlin
package com.breadmoirai.redstonespecs.data

import com.breadmoirai.redstonespecs.client.screen.FlatRow
import com.breadmoirai.redstonespecs.client.screen.RowProp
import com.breadmoirai.redstonespecs.client.screen.flattenCondition
import com.breadmoirai.redstonespecs.client.screen.flattenEntries
import com.breadmoirai.redstonespecs.client.screen.reconstitute
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FlatRowTest {

    @Test
    fun `flattenCondition BoolProperty`() {
        val result = flattenCondition(StateCondition.BoolProperty("powered", true), null)
        assertEquals(listOf(RowProp.Bool("powered", true)), result)
    }

    @Test
    fun `flattenCondition IntProperty uses default bounds 0 to 15`() {
        val result = flattenCondition(StateCondition.IntProperty("power", 7), null)
        assertEquals(listOf(RowProp.ExactInt("power", 7, 0, 15)), result)
    }

    @Test
    fun `flattenCondition IntRange uses default bounds 0 to 15`() {
        val result = flattenCondition(StateCondition.IntRange("power", 1, 15), null)
        assertEquals(listOf(RowProp.RangeInt("power", 1, 15, 0, 15)), result)
    }

    @Test
    fun `flattenCondition EnumProperty with null blockState uses single-element options`() {
        val result = flattenCondition(StateCondition.EnumProperty("facing", "north"), null)
        assertEquals(listOf(RowProp.Enum("facing", "north", listOf("north"))), result)
    }

    @Test
    fun `flattenCondition All expands to multiple RowProps`() {
        val cond = StateCondition.All(listOf(
            StateCondition.BoolProperty("powered", true),
            StateCondition.BoolProperty("lit", false),
        ))
        val result = flattenCondition(cond, null)
        assertEquals(listOf(
            RowProp.Bool("powered", true),
            RowProp.Bool("lit", false),
        ), result)
    }

    @Test
    fun `flattenCondition Any returns empty list (passthrough)`() {
        val result = flattenCondition(
            StateCondition.Any(listOf(StateCondition.BoolProperty("powered", true))), null)
        assertEquals(emptyList<RowProp>(), result)
    }

    @Test
    fun `flattenCondition Not returns empty list (passthrough)`() {
        val result = flattenCondition(
            StateCondition.Not(StateCondition.BoolProperty("powered", false)), null)
        assertEquals(emptyList<RowProp>(), result)
    }

    @Test
    fun `flattenEntries separates editable rows from passthrough`() {
        val entries = listOf(
            SimTime.INIT to StateCondition.BoolProperty("powered", true),
            SimTime(0, Phase.END_OF_TICK) to StateCondition.Not(StateCondition.BoolProperty("lit", false)),
        )
        val (rows, passthrough) = flattenEntries(entries, null)
        assertEquals(1, rows.size)
        assertEquals(1, passthrough.size)
        assertEquals(SimTime.INIT, rows[0].simTime)
    }

    @Test
    fun `reconstitute single row stored unwrapped`() {
        val rows = listOf(FlatRow(SimTime.INIT, RowProp.Bool("powered", true)))
        val result = reconstitute(rows, emptyList())
        assertEquals(1, result.size)
        assertEquals(SimTime.INIT to StateCondition.BoolProperty("powered", true), result[0])
    }

    @Test
    fun `reconstitute same SimTime rows wrapped in All`() {
        val t = SimTime(0, Phase.END_OF_TICK)
        val rows = listOf(
            FlatRow(t, RowProp.Bool("powered", true)),
            FlatRow(t, RowProp.Bool("lit", false)),
        )
        val result = reconstitute(rows, emptyList())
        assertEquals(1, result.size)
        assertTrue(result[0].second is StateCondition.All)
        assertEquals(2, (result[0].second as StateCondition.All).conditions.size)
    }

    @Test
    fun `reconstitute passthrough appended after reconstituted rows`() {
        val pt = SimTime(5, Phase.END_OF_TICK) to StateCondition.Not(StateCondition.BoolProperty("powered", false))
        val rows = listOf(FlatRow(SimTime.INIT, RowProp.Bool("powered", true)))
        val result = reconstitute(rows, listOf(pt))
        assertEquals(2, result.size)
        assertEquals(pt, result[1])
    }
}
```

- [ ] **Step 2: Run to verify it fails**

```
cmd.exe /c "./gradlew.bat test --tests \"com.breadmoirai.redstonespecs.data.FlatRowTest\""
```

Expected: FAIL — `RowProp`, `FlatRow`, etc. do not exist yet.

- [ ] **Step 3: Create `FlatRow.kt`**

Create `src/client/kotlin/com/breadmoirai/redstonespecs/client/screen/FlatRow.kt`:

```kotlin
package com.breadmoirai.redstonespecs.client.screen

import com.breadmoirai.redstonespecs.data.SimTime
import com.breadmoirai.redstonespecs.data.StateCondition
import net.minecraft.resources.Identifier
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.IntegerProperty
import net.minecraft.world.level.block.state.properties.Property

sealed class RowProp {
    abstract val name: String
    abstract fun toCondition(): StateCondition

    data class Block(val blockId: Identifier) : RowProp() {
        override val name = "block"
        override fun toCondition() = StateCondition.BlockType(blockId)
    }

    data class Bool(override val name: String, var value: Boolean) : RowProp() {
        override fun toCondition() = StateCondition.BoolProperty(name, value)
    }

    data class ExactInt(override val name: String, var value: Int, val min: Int, val max: Int) : RowProp() {
        override fun toCondition() = StateCondition.IntProperty(name, value)
    }

    data class RangeInt(override val name: String, var lo: Int, var hi: Int, val absMin: Int, val absMax: Int) : RowProp() {
        override fun toCondition() = StateCondition.IntRange(name, lo, hi)
    }

    data class Enum(override val name: String, var value: String, val options: List<String>) : RowProp() {
        override fun toCondition() = StateCondition.EnumProperty(name, value)
    }
}

data class FlatRow(var simTime: SimTime, var prop: RowProp)

fun flattenEntries(
    entries: List<Pair<SimTime, StateCondition>>,
    blockState: BlockState?,
): Pair<MutableList<FlatRow>, MutableList<Pair<SimTime, StateCondition>>> {
    val rows = mutableListOf<FlatRow>()
    val passthrough = mutableListOf<Pair<SimTime, StateCondition>>()
    for ((simTime, condition) in entries) {
        val leafProps = flattenCondition(condition, blockState)
        if (leafProps.isEmpty()) {
            passthrough.add(simTime to condition)
        } else {
            leafProps.forEach { rows.add(FlatRow(simTime, it)) }
        }
    }
    return rows to passthrough
}

fun flattenCondition(condition: StateCondition, blockState: BlockState?): List<RowProp> = when (condition) {
    is StateCondition.All -> condition.conditions.flatMap { flattenCondition(it, blockState) }
    is StateCondition.BlockType -> listOf(RowProp.Block(condition.blockId))
    is StateCondition.BoolProperty -> listOf(RowProp.Bool(condition.name, condition.value))
    is StateCondition.IntProperty -> {
        val prop = blockState?.block?.stateDefinition?.getProperty(condition.name) as? IntegerProperty
        val lo = prop?.possibleValues?.min() ?: 0
        val hi = prop?.possibleValues?.max() ?: 15
        listOf(RowProp.ExactInt(condition.name, condition.value, lo, hi))
    }
    is StateCondition.IntRange -> {
        val prop = blockState?.block?.stateDefinition?.getProperty(condition.name) as? IntegerProperty
        val lo = prop?.possibleValues?.min() ?: 0
        val hi = prop?.possibleValues?.max() ?: 15
        listOf(RowProp.RangeInt(condition.name, condition.min, condition.max, lo, hi))
    }
    is StateCondition.EnumProperty -> {
        @Suppress("UNCHECKED_CAST")
        val cast = blockState?.block?.stateDefinition?.getProperty(condition.name) as? Property<Comparable<Any>>
        val options = cast?.possibleValues?.map { cast.getName(it) } ?: listOf(condition.value)
        listOf(RowProp.Enum(condition.name, condition.value, options))
    }
    else -> emptyList()
}

fun reconstitute(
    rows: List<FlatRow>,
    passthrough: List<Pair<SimTime, StateCondition>>,
): List<Pair<SimTime, StateCondition>> {
    val grouped = linkedMapOf<SimTime, MutableList<StateCondition>>()
    for (row in rows) grouped.getOrPut(row.simTime) { mutableListOf() }.add(row.prop.toCondition())
    val result = grouped.map { (simTime, conditions) ->
        simTime to if (conditions.size == 1) conditions[0] else StateCondition.All(conditions)
    }.toMutableList()
    result.addAll(passthrough)
    return result
}
```

- [ ] **Step 4: Run tests to verify they pass**

```
cmd.exe /c "./gradlew.bat test --tests \"com.breadmoirai.redstonespecs.data.FlatRowTest\""
```

Expected: all 11 tests PASS.

- [ ] **Step 5: Commit**

```
git add src/client/kotlin/com/breadmoirai/redstonespecs/client/screen/FlatRow.kt src/test/kotlin/com/breadmoirai/redstonespecs/data/FlatRowTest.kt
git commit -m "feat: add FlatRow model with flatten/reconstitute logic"
```

---

### Task 6: `SpecEditorScreen` inline entry table

**Files:**
- Rewrite: `src/client/kotlin/com/breadmoirai/redstonespecs/client/screen/SpecEditorScreen.kt`

The entries section (previously text preview rows + Edit/Remove + "+ Add Entry" button) is replaced with a scrollable flat-row table. `openEntryEditor` and its call sites are removed. `flattenConditionToMap` is moved here from `EntryEditorScreen.kt`.

- [ ] **Step 1: Replace `SpecEditorScreen.kt` with the full rewrite**

```kotlin
package com.breadmoirai.redstonespecs.client.screen

import com.breadmoirai.redstonespecs.block.RedstoneSpecBlockEntity
import com.breadmoirai.redstonespecs.data.AutoSpec
import com.breadmoirai.redstonespecs.data.BreakpointSpec
import com.breadmoirai.redstonespecs.data.InputSpec
import com.breadmoirai.redstonespecs.data.OutputSpec
import com.breadmoirai.redstonespecs.data.Phase
import com.breadmoirai.redstonespecs.data.SimTime
import com.breadmoirai.redstonespecs.data.SpecEntry
import com.breadmoirai.redstonespecs.data.SpecMode
import com.breadmoirai.redstonespecs.data.StateCondition
import com.breadmoirai.redstonespecs.network.RemoveSpecEntryC2SPayload
import com.breadmoirai.redstonespecs.network.SaveSpecEntryC2SPayload
import com.breadmoirai.redstonespecs.runner.captureBlockStateProps
import dev.isxander.yacl3.gui.LowProfileButtonWidget
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.minecraft.client.gui.components.CycleButton
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.components.ScrollableLayout
import net.minecraft.client.gui.components.StringWidget
import net.minecraft.client.gui.layouts.FrameLayout
import net.minecraft.client.gui.layouts.LayoutElement
import net.minecraft.client.gui.layouts.LinearLayout
import net.minecraft.client.gui.layouts.SpacerElement
import net.minecraft.client.gui.screens.Screen
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.CommonComponents
import net.minecraft.network.chat.Component
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.BooleanProperty
import net.minecraft.world.level.block.state.properties.IntegerProperty
import net.minecraft.world.level.block.state.properties.Property

class SpecEditorScreen(
    private val originPos: BlockPos,
    private val entryRelPos: BlockPos,
) : Screen(Component.translatable("screen.redstonespecs.spec_editor")) {

    private var launched = false
    private var workingLabel: String = ""
    private var workingColor: Int = 0xFFFFFF
    private var workingRows: MutableList<FlatRow>? = null
    private var workingPassthrough: MutableList<Pair<SimTime, StateCondition>>? = null
    private var originalEntry: SpecEntry? = null
    private var specMode: SpecMode = SpecMode.SIMPLE

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
        originalEntry = entry
        workingLabel = entry.label
        workingColor = entry.color
        specMode = be.spec?.mode ?: SpecMode.SIMPLE

        val entries: List<Pair<SimTime, StateCondition>>? = when (entry) {
            is InputSpec -> entry.entries
            is OutputSpec -> entry.entries
            else -> null
        }
        if (entries != null) {
            val worldPos = originPos.offset(entryRelPos)
            val blockState = minecraft?.level?.getBlockState(worldPos)
            val (rows, passthrough) = flattenEntries(entries, blockState)
            workingRows = rows
            workingPassthrough = passthrough
        }

        launched = true
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

        val content = LinearLayout.vertical().spacing(4)

        content.addChild(StringWidget(Component.literal("$typeLabel @ $entryRelPos"), font))
        content.addChild(SpacerElement(0, 4))

        val labelRow = LinearLayout.horizontal().spacing(4)
        labelRow.addChild(StringWidget(50, 20, Component.literal("Label:"), font))
        val labelBox = EditBox(font, 180, 20, Component.empty())
        labelBox.value = workingLabel
        labelBox.setResponder { workingLabel = it }
        labelRow.addChild(labelBox)
        content.addChild(labelRow)

        val colorRow = LinearLayout.horizontal().spacing(4)
        colorRow.addChild(StringWidget(50, 20, Component.literal("Color:"), font))
        val colorBox = EditBox(font, 80, 20, Component.empty())
        colorBox.value = "%06X".format(workingColor)
        colorBox.setMaxLength(6)
        val swatch = ColorSwatchWidget(0, 0, workingColor)
        colorBox.setResponder { hex ->
            val parsed = hex.toLongOrNull(16)
            if (parsed != null && hex.length <= 6) {
                workingColor = parsed.toInt() and 0xFFFFFF
                swatch.setColor(workingColor)
            }
        }
        colorRow.addChild(colorBox)
        colorRow.addChild(swatch)
        content.addChild(colorRow)

        content.addChild(SpacerElement(0, 4))

        val rows = workingRows
        if (rows != null) {
            content.addChild(StringWidget(Component.literal("Entries:"), font))

            val worldPos = originPos.offset(entryRelPos)
            val blockState = minecraft?.level?.getBlockState(worldPos)
            val availableProps = buildAvailableProps(blockState)
            val advancedPhases = Phase.entries.filter { it != Phase.USER_INTERACTION }

            val tableContent = LinearLayout.vertical().spacing(1)
            rows.forEachIndexed { i, row ->
                tableContent.addChild(buildTableRow(i, row, blockState, availableProps, advancedPhases))
            }

            tableContent.addChild(
                LowProfileButtonWidget(0, 0, 400, 16, Component.literal("+ Add Row")) {
                    val lastTime = rows.lastOrNull()?.simTime
                    val newTick = when {
                        lastTime == null -> -1
                        lastTime == SimTime.INIT -> 0
                        else -> lastTime.tick + 1
                    }
                    val newPhase = lastTime?.phase?.takeIf { it != Phase.USER_INTERACTION } ?: Phase.END_OF_TICK
                    val newTime = if (newTick < 0) SimTime.INIT else SimTime(newTick, newPhase)
                    val firstProp = buildFirstRowProp(blockState)
                    if (firstProp != null) rows.add(FlatRow(newTime, firstProp))
                    rebuildWidgets()
                }
            )

            content.addChild(ScrollableLayout(minecraft, tableContent, 140))

            content.addChild(
                LowProfileButtonWidget(0, 0, 120, 20, Component.literal("Capture State")) {
                    captureState(rows)
                }
            )

            content.addChild(SpacerElement(0, 4))
        }

        content.addChild(
            LowProfileButtonWidget(0, 0, 120, 20, Component.literal("Remove Spec")) {
                ClientPlayNetworking.send(RemoveSpecEntryC2SPayload(originPos, entryRelPos))
                minecraft?.setScreen(null)
            }
        )

        content.addChild(SpacerElement(0, 4))

        val bottomRow = LinearLayout.horizontal().spacing(4)
        bottomRow.addChild(LowProfileButtonWidget(0, 0, 80, 20, Component.literal("Save")) { saveAndClose() })
        bottomRow.addChild(LowProfileButtonWidget(0, 0, 80, 20, CommonComponents.GUI_CANCEL) { onClose() })
        content.addChild(bottomRow)

        content.arrangeElements()
        FrameLayout.centerInRectangle(content, 0, 0, width, height)
        content.visitWidgets { addRenderableWidget(it) }
    }

    private fun buildTableRow(
        index: Int,
        row: FlatRow,
        blockState: BlockState?,
        availableProps: List<String>,
        advancedPhases: List<Phase>,
    ): LinearLayout {
        val rowLayout = LinearLayout.horizontal().spacing(2)

        if (specMode == SpecMode.TICK_AWARE || specMode == SpecMode.UPDATE_AWARE) {
            val tickVal = if (row.simTime == SimTime.INIT) -1 else row.simTime.tick
            val tickBox = IntEditBox(
                font, 60, 16, -1, Int.MAX_VALUE, tickVal,
                onChange = { v ->
                    row.simTime = if (v < 0) SimTime.INIT
                        else SimTime(v, row.simTime.phase.takeIf { it != Phase.USER_INTERACTION } ?: Phase.END_OF_TICK)
                },
                onHoverEnd = { sortAndRebuild() },
            )
            rowLayout.addChild(tickBox)
        }

        if (specMode == SpecMode.UPDATE_AWARE) {
            val currentPhase = row.simTime.phase.takeIf { it != Phase.USER_INTERACTION } ?: Phase.END_OF_TICK
            val phaseDropdown = DropdownButton(
                0, 0, 110, 16, font,
                advancedPhases,
                { phase -> Component.literal(phase.name) },
                currentPhase,
            ) { phase ->
                if (row.simTime != SimTime.INIT) {
                    row.simTime = SimTime(row.simTime.tick, phase)
                }
                sortAndRebuild()
            }
            phaseDropdown.active = row.simTime != SimTime.INIT
            rowLayout.addChild(phaseDropdown)
        }

        val propDropdown = DropdownButton(
            0, 0, 100, 16, font,
            availableProps,
            { Component.literal(it) },
            row.prop.name,
        ) { propName ->
            val newProp = buildRowPropForName(propName, blockState)
            if (newProp != null) row.prop = newProp
            rebuildWidgets()
        }
        rowLayout.addChild(propDropdown)

        rowLayout.addChild(buildValueWidget(index, row))

        rowLayout.addChild(
            LowProfileButtonWidget(0, 0, 20, 16, Component.literal("×")) {
                workingRows!!.removeAt(index)
                rebuildWidgets()
            }
        )

        return rowLayout
    }

    private fun buildValueWidget(index: Int, row: FlatRow): LayoutElement = when (val prop = row.prop) {
        is RowProp.Block -> StringWidget(110, 16, Component.literal(prop.blockId.path), font)

        is RowProp.Bool -> CycleButton.builder<Boolean>(
            { v -> Component.literal(v.toString()) },
            prop.value,
        ).withValues(false, true)
            .displayOnlyValue()
            .create(0, 0, 110, 16, Component.empty()) { _, v -> prop.value = v }

        is RowProp.ExactInt -> {
            val valRow = LinearLayout.horizontal().spacing(1)
            valRow.addChild(IntEditBox(font, 80, 16, prop.min, prop.max, prop.value) { v -> prop.value = v })
            valRow.addChild(LowProfileButtonWidget(0, 0, 28, 16, Component.literal("~")) {
                row.prop = RowProp.RangeInt(prop.name, prop.value, prop.max, prop.min, prop.max)
                rebuildWidgets()
            })
            valRow
        }

        is RowProp.RangeInt -> {
            val valRow = LinearLayout.horizontal().spacing(1)
            valRow.addChild(IntEditBox(font, 37, 16, prop.absMin, prop.absMax, prop.lo) { v -> prop.lo = v })
            valRow.addChild(StringWidget(6, 16, Component.literal("-"), font))
            valRow.addChild(IntEditBox(font, 37, 16, prop.absMin, prop.absMax, prop.hi) { v -> prop.hi = v })
            valRow.addChild(LowProfileButtonWidget(0, 0, 20, 16, Component.literal("=")) {
                row.prop = RowProp.ExactInt(prop.name, prop.lo, prop.absMin, prop.absMax)
                rebuildWidgets()
            })
            valRow
        }

        is RowProp.Enum -> CycleButton.builder<String>(
            { v -> Component.literal(v) },
            prop.value,
        ).withValues(*prop.options.toTypedArray())
            .displayOnlyValue()
            .create(0, 0, 110, 16, Component.empty()) { _, v -> prop.value = v }
    }

    private fun sortAndRebuild() {
        workingRows?.sortWith(compareBy { it.simTime })
        rebuildWidgets()
    }

    private fun buildAvailableProps(blockState: BlockState?): List<String> {
        val names = mutableListOf("block")
        blockState?.block?.stateDefinition?.properties?.mapTo(names) { it.name }
        return names
    }

    private fun buildFirstRowProp(blockState: BlockState?): RowProp? {
        if (blockState == null) return null
        val firstProp = blockState.block.stateDefinition.properties.firstOrNull()
            ?: return RowProp.Block(BuiltInRegistries.BLOCK.getKey(blockState.block))
        return buildRowPropForName(firstProp.name, blockState)
    }

    private fun buildRowPropForName(propName: String, blockState: BlockState?): RowProp? {
        if (propName == "block") {
            val blockId = blockState?.let { BuiltInRegistries.BLOCK.getKey(it.block) } ?: return null
            return RowProp.Block(blockId)
        }
        val prop = blockState?.block?.stateDefinition?.getProperty(propName) ?: return null
        return when (prop) {
            is BooleanProperty -> RowProp.Bool(propName, blockState.getValue(prop))
            is IntegerProperty -> {
                val min = prop.possibleValues.min()
                val max = prop.possibleValues.max()
                RowProp.ExactInt(propName, blockState.getValue(prop), min, max)
            }
            else -> {
                @Suppress("UNCHECKED_CAST")
                val cast = prop as Property<Comparable<Any>>
                RowProp.Enum(propName, cast.getName(blockState.getValue(prop)), prop.possibleValues.map { cast.getName(it) })
            }
        }
    }

    private fun captureState(rows: MutableList<FlatRow>) {
        val level = minecraft?.level ?: return
        val worldPos = originPos.offset(entryRelPos)
        val blockState = level.getBlockState(worldPos)
        val currentProps = captureBlockStateProps(blockState)

        if (rows.isEmpty()) {
            val firstProp = buildFirstRowProp(blockState) ?: return
            rows.add(FlatRow(SimTime.INIT, firstProp))
            rebuildWidgets()
            return
        }

        val lastTime = rows.maxByOrNull { it.simTime }?.simTime ?: return
        val lastKnown = mutableMapOf<String, String>()
        rows.filter { it.simTime == lastTime }.forEach { r ->
            when (val p = r.prop) {
                is RowProp.Bool -> lastKnown[p.name] = p.value.toString()
                is RowProp.ExactInt -> lastKnown[p.name] = p.value.toString()
                is RowProp.Enum -> lastKnown[p.name] = p.value
                else -> {}
            }
        }

        val diff = currentProps.filter { (k, v) -> lastKnown[k] != v }
        if (diff.isEmpty()) return

        val newTick = if (lastTime == SimTime.INIT) 0 else lastTime.tick + 1
        val newTime = SimTime(newTick, Phase.END_OF_TICK)
        diff.keys.forEach { propName ->
            val newProp = buildRowPropForName(propName, blockState) ?: return@forEach
            rows.add(FlatRow(newTime, newProp))
        }
        rebuildWidgets()
    }

    private fun saveAndClose() {
        val entry = originalEntry ?: return
        val entries = workingRows?.let { reconstitute(it, workingPassthrough ?: emptyList()) }
        val updated: SpecEntry = when (entry) {
            is InputSpec -> entry.copy(label = workingLabel, color = workingColor, entries = entries ?: entry.entries)
            is OutputSpec -> entry.copy(label = workingLabel, color = workingColor, entries = entries ?: entry.entries)
            is BreakpointSpec -> entry.copy(label = workingLabel, color = workingColor)
            is AutoSpec -> entry.copy(label = workingLabel, color = workingColor)
        }
        ClientPlayNetworking.send(SaveSpecEntryC2SPayload(originPos, updated))
        onClose()
    }
}
```

- [ ] **Step 2: Compile-check**

```
cmd.exe /c "./gradlew.bat :26.1:compileKotlin"
```

Expected: BUILD SUCCESSFUL. If errors reference anything from `EntryEditorScreen` (`PropState`, `buildPropStates`, `previewEntry`, etc.), those are coming from the old screen file which will be deleted in Task 7. Verify none of the new `SpecEditorScreen` code references those symbols.

- [ ] **Step 3: Commit**

```
git add src/client/kotlin/com/breadmoirai/redstonespecs/client/screen/SpecEditorScreen.kt
git commit -m "feat: replace entry list with inline flat-row table in SpecEditorScreen"
```

---

### Task 7: Delete `EntryEditorScreen` + final verification

**Files:**
- Delete: `src/client/kotlin/com/breadmoirai/redstonespecs/client/screen/EntryEditorScreen.kt`

- [ ] **Step 1: Delete the file**

Delete `src/client/kotlin/com/breadmoirai/redstonespecs/client/screen/EntryEditorScreen.kt`.

- [ ] **Step 2: Compile-check**

```
cmd.exe /c "./gradlew.bat :26.1:compileKotlin"
```

Expected: BUILD SUCCESSFUL. If errors appear, check for any remaining references to `EntryEditorScreen`, `PropState`, `buildPropStates`, `prePopulate`, `previewEntry`, `previewCondition`, or `flattenConditionToMap` from the old import path and remove them.

- [ ] **Step 3: Run all tests**

```
cmd.exe /c "./gradlew.bat test"
```

Expected: all tests PASS.

- [ ] **Step 4: Commit**

```
git add -u
git commit -m "feat: delete EntryEditorScreen; inline entry table complete"
```
