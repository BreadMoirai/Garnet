# Spec Editor State List + Tests Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add an inline state-entry list to `SpecEditorScreen` (with add/remove, `CycleButton<Phase>` dropdown defaulting to `END_OF_TICK`), auto-capture block state in marker items, and wire up a server-side `@GameTest` and a full-flow `@ClientGameTest`.

**Architecture:** The editor screen holds a mutable `workingEntries` list that survives `rebuildWidgets()` calls; a `showAddForm` flag gates the inline add-entry form. The GameTest builds the circuit programmatically and calls `SpecRunnerCoordinator` directly. The ClientGameTest follows OCC's `TestSuite` / `SpecTestContext` helper pattern using `ClientGameTestContext` + `TestSingleplayerContext`.

**Tech Stack:** Kotlin, Fabric API 0.146.1+26.1, `net.fabricmc.fabric.api.client.gametest.v1`, `net.minecraft.gametest.framework`, `CycleButton<Phase>`, `MouseButtonEvent`/`MouseButtonInfo` (MC 26.1 click API).

---

## File Map

| Action   | Path |
|----------|------|
| Modify   | `build.gradle.kts` |
| Create   | `src/test/resources/fabric.mod.json` |
| Create   | `src/test/resources/data/redstonespecs/structures/lever_lamp.snbt` |
| Modify   | `src/main/kotlin/com/breadmoirai/redstonespecs/item/SpecMarkerTool.kt` |
| Modify   | `src/client/kotlin/com/breadmoirai/redstonespecs/client/screen/SpecEditorScreen.kt` |
| Create   | `src/test/kotlin/com/breadmoirai/redstonespecs/test/LeverLampSpecTest.kt` |
| Create   | `src/test/kotlin/com/breadmoirai/redstonespecs/test/SpecTestContext.kt` |
| Create   | `src/test/kotlin/com/breadmoirai/redstonespecs/test/RedstonespecsClientTests.kt` |

---

## Task 1: Build infrastructure

**Files:**
- Modify: `build.gradle.kts`
- Create: `src/test/resources/fabric.mod.json`
- Create: `src/test/resources/data/redstonespecs/structures/lever_lamp.snbt`

- [ ] **Step 1: Add test sourceset, loom run, and dependencies to `build.gradle.kts`**

Replace the existing `loom { ... }` block and add sourceSets + dependencies as follows. Keep all existing content inside `loom { }` and `dependencies { }` — only add the new lines shown.

```kotlin
// After the existing `val requiredJava = ...` block, add:
val hasClientGameTestApi = property("minecraft_version").toString() >= "1.21.4"

// In the sourceSets block (add after existing content or create new):
sourceSets {
    named("test") {
        if (hasClientGameTestApi) {
            compileClasspath += sourceSets.main.get().compileClasspath +
                sourceSets.client.get().compileClasspath
            runtimeClasspath += sourceSets.main.get().runtimeClasspath +
                sourceSets.client.get().runtimeClasspath
        }
    }
}
```

Inside the existing `loom { }` block, add a `runs { }` sub-block and `createRemapConfigurations` call:

```kotlin
loom {
    splitEnvironmentSourceSets()

    mods {
        register("redstonespecs") {
            sourceSet("main")
            sourceSet("client")
        }
    }

    // ADD:
    if (hasClientGameTestApi) {
        runs {
            register("TestClient") {
                client()
                name("Test Client")
                source(sourceSets.test.get())
                vmArgs(
                    "-Dfabric.client.gametest",
                    "-Dfabric.client.gametest.disableNetworkSynchronizer",
                )
            }
        }
    }
    createRemapConfigurations(sourceSets.test.get())
}
```

Inside the existing `dependencies { }` block, append:

```kotlin
    if (hasClientGameTestApi) {
        testImplementation(sourceSets.main.get().output)
        testImplementation(sourceSets.client.get().output)
    }
```

- [ ] **Step 2: Verify compilation**

```bash
./gradlew compileTestKotlin
```

Expected: `BUILD SUCCESSFUL`. If you see "unresolved reference" errors for `sourceSets.client`, confirm `splitEnvironmentSourceSets()` is called before `createRemapConfigurations`.

- [ ] **Step 3: Create testmod `fabric.mod.json`**

Create `src/test/resources/fabric.mod.json`:

```json
{
  "schemaVersion": 1,
  "id": "redstonespecs-test",
  "version": "1.0.0",
  "name": "RedstoneSpecs Testmod",
  "environment": "*",
  "entrypoints": {
    "fabric-gametest": [
      "com.breadmoirai.redstonespecs.test.LeverLampSpecTest"
    ],
    "fabric-client-gametest": [
      "com.breadmoirai.redstonespecs.test.RedstonespecsClientTests"
    ]
  },
  "depends": {
    "redstonespecs": "*"
  }
}
```

- [ ] **Step 4: Create empty structure file for GameTest**

Create `src/test/resources/data/redstonespecs/structures/lever_lamp.snbt`:

```snbt
{DataVersion: 0, size: [5, 4, 5], palette: [{Name: "minecraft:air"}], blocks: [], entities: []}
```

This defines a 5×4×5 empty region. All blocks (floor, SpecOrigin, lever, lamp) are placed programmatically in the test.

- [ ] **Step 5: Commit**

```bash
git add build.gradle.kts src/test/resources/
git commit -m "feat: add test infrastructure — runTestClient, testmod fabric.mod.json, empty structure"
```

---

## Task 2: Auto-capture INIT state in marker items

**Files:**
- Modify: `src/main/kotlin/com/breadmoirai/redstonespecs/item/SpecMarkerTool.kt`
- Test via: `src/test/kotlin/com/breadmoirai/redstonespecs/data/SpecMarkerToolTest.kt` (unit test, runs with `./gradlew test`)

- [ ] **Step 1: Write a failing unit test**

Create `src/test/kotlin/com/breadmoirai/redstonespecs/data/SpecMarkerToolTest.kt`:

```kotlin
package com.breadmoirai.redstonespecs.data

import com.breadmoirai.redstonespecs.item.InputSpecMarkerItem
import com.breadmoirai.redstonespecs.item.OutputSpecMarkerItem
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.core.BlockPos
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class SpecMarkerToolTest {

    @Test
    fun `InputSpecMarkerItem creates entry with captured init props`() {
        val marker = InputSpecMarkerItem()
        val pos = BlockPos(1, 0, 0)
        val initProps = mapOf("powered" to "false", "facing" to "north")
        val entry = marker.createEntry(pos, initProps)
        assertIs<InputSpec>(entry)
        assertEquals(pos, entry.pos)
        assertEquals(SimTime.INIT to initProps, entry.stateSpec.entries.first())
    }

    @Test
    fun `OutputSpecMarkerItem creates entry with captured init props`() {
        val marker = OutputSpecMarkerItem()
        val pos = BlockPos(2, 0, 0)
        val initProps = mapOf("lit" to "false")
        val entry = marker.createEntry(pos, initProps)
        assertIs<OutputSpec>(entry)
        assertEquals(pos, entry.pos)
        assertEquals(SimTime.INIT to initProps, entry.stateSpec.entries.first())
    }
}
```

- [ ] **Step 2: Run test — expect compile failure**

```bash
./gradlew test --tests "com.breadmoirai.redstonespecs.data.SpecMarkerToolTest"
```

Expected: compilation error — `createEntry` has wrong signature. Confirm the error before proceeding.

- [ ] **Step 3: Implement auto-capture in `SpecMarkerTool.kt`**

Replace the entire file:

```kotlin
package com.breadmoirai.redstonespecs.item

import com.breadmoirai.redstonespecs.block.SpecOriginBlockEntity
import com.breadmoirai.redstonespecs.data.AutoSpec
import com.breadmoirai.redstonespecs.data.BreakpointSpec
import com.breadmoirai.redstonespecs.data.InputSpec
import com.breadmoirai.redstonespecs.data.OutputSpec
import com.breadmoirai.redstonespecs.data.SimTime
import com.breadmoirai.redstonespecs.data.SpecEntry
import com.breadmoirai.redstonespecs.data.StateSpec
import com.breadmoirai.redstonespecs.network.OpenEditorS2CPayload
import com.breadmoirai.redstonespecs.runner.captureBlockStateProps
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionResult
import net.minecraft.world.item.Item
import net.minecraft.world.item.context.UseOnContext
import net.minecraft.world.level.Level

abstract class SpecMarkerTool(properties: Properties = Properties()) : Item(properties) {

    abstract fun createEntry(relPos: BlockPos, initProps: Map<String, String>): SpecEntry

    override fun useOn(context: UseOnContext): InteractionResult {
        val level: Level = context.level
        val hitPos: BlockPos = context.clickedPos
        val player = context.player ?: return InteractionResult.PASS

        val be = SpecOriginBlockEntity.findFor(level, hitPos) ?: return InteractionResult.PASS

        if (!level.isClientSide) {
            val spec = be.spec ?: return InteractionResult.PASS
            if (be.activeSpecCaseIndex >= spec.specCases.size) return InteractionResult.PASS

            val relPos = hitPos.subtract(be.blockPos)
            val specCase = spec.specCases[be.activeSpecCaseIndex]
            val initProps = captureBlockStateProps(level.getBlockState(hitPos))

            if (specCase.entryAt(relPos) == null) {
                be.addOrUpdateEntry(be.activeSpecCaseIndex, createEntry(relPos, initProps))
            }

            ServerPlayNetworking.send(player as ServerPlayer, OpenEditorS2CPayload(be.blockPos, relPos))
        }

        return InteractionResult.SUCCESS
    }
}

class InputSpecMarkerItem(properties: Properties = Properties()) : SpecMarkerTool(properties) {
    override fun createEntry(relPos: BlockPos, initProps: Map<String, String>): SpecEntry =
        InputSpec(relPos, "", 0x4488FF, StateSpec(listOf(SimTime.INIT to initProps)))
}

class OutputSpecMarkerItem(properties: Properties = Properties()) : SpecMarkerTool(properties) {
    override fun createEntry(relPos: BlockPos, initProps: Map<String, String>): SpecEntry =
        OutputSpec(relPos, "", 0x44FF88, StateSpec(listOf(SimTime.INIT to initProps)))
}

class BreakpointSpecMarkerItem(properties: Properties = Properties()) : SpecMarkerTool(properties) {
    override fun createEntry(relPos: BlockPos, initProps: Map<String, String>): SpecEntry =
        BreakpointSpec(relPos, "", 0xFF4444)
}

class AutoSpecMarkerItem(properties: Properties = Properties()) : SpecMarkerTool(properties) {
    override fun createEntry(relPos: BlockPos, initProps: Map<String, String>): SpecEntry =
        AutoSpec(relPos, "", 0xFFAA00)
}
```

- [ ] **Step 4: Run test — expect PASS**

```bash
./gradlew test --tests "com.breadmoirai.redstonespecs.data.SpecMarkerToolTest"
```

Expected: `BUILD SUCCESSFUL`, both tests green.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/breadmoirai/redstonespecs/item/SpecMarkerTool.kt \
        src/test/kotlin/com/breadmoirai/redstonespecs/data/SpecMarkerToolTest.kt
git commit -m "feat: auto-capture INIT block state when placing spec marker"
```

---

## Task 3: Write the failing GameTest

**Files:**
- Create: `src/test/kotlin/com/breadmoirai/redstonespecs/test/LeverLampSpecTest.kt`

The GameTest verifies that `SpecRunner` drives a lever input (`powered=false→true` at tick 0 `START_OF_TICK`) and detects a redstone lamp turning on (`lit=true` at tick 0 `END_OF_TICK`). Setting the lever's block state with `UPDATE_NEIGHBORS` flag synchronously triggers the lamp's `neighborChanged` which immediately sets `lit=true` (lamps turn on without scheduling). By `END_OF_TICK` the lamp is already lit.

- [ ] **Step 1: Create the test class**

Create `src/test/kotlin/com/breadmoirai/redstonespecs/test/LeverLampSpecTest.kt`:

```kotlin
package com.breadmoirai.redstonespecs.test

import com.breadmoirai.redstonespecs.ModRegistries
import com.breadmoirai.redstonespecs.block.SpecOriginBlockEntity
import com.breadmoirai.redstonespecs.data.InputSpec
import com.breadmoirai.redstonespecs.data.OutputSpec
import com.breadmoirai.redstonespecs.data.Phase
import com.breadmoirai.redstonespecs.data.RedstoneSpec
import com.breadmoirai.redstonespecs.data.SimTime
import com.breadmoirai.redstonespecs.data.SpecCase
import com.breadmoirai.redstonespecs.data.StateSpec
import com.breadmoirai.redstonespecs.runner.SpecRunnerCoordinator
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.LeverBlock
import net.minecraft.world.level.block.state.properties.AttachFace
import java.util.UUID

@Suppress("UnstableApiUsage")
class LeverLampSpecTest {

    @GameTest(templateNamespace = "redstonespecs", templateId = "lever_lamp")
    fun leverOnLampLit(helper: GameTestHelper) {
        // Stone floor (lever needs support below)
        for (x in 0..3) helper.setBlock(BlockPos(x, 0, 0), Blocks.STONE.defaultBlockState())

        // SpecOrigin at (0,1,0), Lever at (1,1,0), Lamp at (2,1,0)
        helper.setBlock(BlockPos(0, 1, 0), ModRegistries.SPEC_ORIGIN_BLOCK.defaultBlockState())
        helper.setBlock(
            BlockPos(1, 1, 0),
            Blocks.LEVER.defaultBlockState()
                .setValue(LeverBlock.FACE, AttachFace.FLOOR)
                .setValue(LeverBlock.POWERED, false),
        )
        helper.setBlock(BlockPos(2, 1, 0), Blocks.REDSTONE_LAMP.defaultBlockState())

        val be = helper.getBlockEntity(BlockPos(0, 1, 0)) as SpecOriginBlockEntity

        val input = InputSpec(
            pos = BlockPos(1, 0, 0),
            label = "lever",
            color = 0x4488FF,
            stateSpec = StateSpec(
                listOf(
                    SimTime.INIT to mapOf("powered" to "false"),
                    SimTime(0, Phase.START_OF_TICK) to mapOf("powered" to "true"),
                )
            ),
        )
        val output = OutputSpec(
            pos = BlockPos(2, 0, 0),
            label = "lamp",
            color = 0x44FF88,
            stateSpec = StateSpec(
                listOf(
                    SimTime.INIT to mapOf("lit" to "false"),
                    SimTime(0, Phase.END_OF_TICK) to mapOf("lit" to "true"),
                )
            ),
        )
        val specCase = SpecCase(
            name = "lever on → lamp lit",
            lifespan = 2,
            inputs = listOf(input),
            outputs = listOf(output),
            breakpoints = emptyList(),
            autoSpecs = emptyList(),
        )
        be.setSpec(
            RedstoneSpec(
                id = UUID.randomUUID(),
                name = "Lever Lamp Test",
                bounds = net.minecraft.world.level.levelgen.structure.BoundingBox(-1, -1, -1, 3, 2, 1),
                oneShot = false,
                specCases = listOf(specCase),
            )
        )

        SpecRunnerCoordinator.startRun(be, false)

        helper.runAfterDelay(40) {
            val result = be.lastTestResult
                ?: throw net.minecraft.gametest.framework.GameTestAssertException(
                    "No test result after 40 ticks"
                )
            val checks = result.results.flatMap { it.checks }
            helper.assertTrue(checks.isNotEmpty(), "Expected at least one check")
            val failed = checks.filter { !it.pass }
            helper.assertTrue(
                failed.isEmpty(),
                "Failed checks: ${failed.joinToString { "${it.label}: expected=${it.expected} actual=${it.actual}" }}"
            )
            helper.succeed()
        }
    }
}
```

- [ ] **Step 2: Run — expect infrastructure pass or known failure**

```bash
./gradlew runTestClient 2>&1 | tail -30
```

Expected: test is discovered and runs. If it fails with "No test result after 40 ticks", check that `SubTickPhaseEvents` is firing (the mod initializer must have run). If the structure file is not found, check `src/test/resources/data/redstonespecs/structures/lever_lamp.snbt` exists.

`GameTestHelper.assertTrue` may not exist in all Fabric versions — if you see a compile error, replace with:
```kotlin
if (!condition) throw net.minecraft.gametest.framework.GameTestAssertException(message)
```

- [ ] **Step 3: Commit**

```bash
git add src/test/kotlin/com/breadmoirai/redstonespecs/test/LeverLampSpecTest.kt
git commit -m "test: add LeverLampSpecTest GameTest for SpecRunner lever→lamp circuit"
```

---

## Task 4: Write failing ClientGameTest

**Files:**
- Create: `src/test/kotlin/com/breadmoirai/redstonespecs/test/SpecTestContext.kt`
- Create: `src/test/kotlin/com/breadmoirai/redstonespecs/test/RedstonespecsClientTests.kt`

Write the full test now. It will fail at the `clickButton("+ Add Entry")` step because the editor screen doesn't have that button yet. That's the expected failure state.

- [ ] **Step 1: Create `SpecTestContext.kt`**

Create `src/test/kotlin/com/breadmoirai/redstonespecs/test/SpecTestContext.kt`:

```kotlin
package com.breadmoirai.redstonespecs.test

import com.breadmoirai.redstonespecs.block.SpecOriginBlockEntity
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.client.input.MouseButtonInfo
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.InteractionHand
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.Vec3
import org.lwjgl.glfw.GLFW

@Suppress("UnstableApiUsage")
class SpecTestContext(
    val context: ClientGameTestContext,
    val world: TestSingleplayerContext,
) {

    fun runCommand(cmd: String) = world.getServer().runCommand(cmd)
    fun giveItem(itemId: String) = runCommand("give @a $itemId 1")
    fun waitTick() = context.waitTick()
    fun waitTicks(n: Int) = context.waitTicks(n)

    fun <T : Screen> waitForScreen(clazz: Class<T>): T = context.waitForScreen(clazz)

    fun closeScreen() {
        context.getInput().pressKey(GLFW.GLFW_KEY_ESCAPE)
        context.waitFor { mc -> mc.screen == null }
    }

    fun rightClickBlock(pos: BlockPos, direction: Direction = Direction.SOUTH) {
        context.runOnClient { mc ->
            val hitResult = BlockHitResult(Vec3.atCenterOf(pos), direction, pos, false)
            mc.gameMode!!.useItemOn(mc.player!!, InteractionHand.MAIN_HAND, hitResult)
        }
        context.waitTick()
    }

    fun clickButton(labelText: String) {
        context.runOnClient { mc ->
            val screen = mc.screen
                ?: throw AssertionError("clickButton('$labelText'): no screen open")
            val button = screen.children()
                .filterIsInstance<Button>()
                .find { it.message.string == labelText }
                ?: throw AssertionError(
                    "Button '$labelText' not found in ${screen::class.simpleName}. " +
                        "Available: ${screen.children().filterIsInstance<Button>().map { it.message.string }}"
                )
            val cx = (button.x + button.width / 2).toDouble()
            val cy = (button.y + button.height / 2).toDouble()
            screen.mouseClicked(MouseButtonEvent(cx, cy, MouseButtonInfo(0, 0)), false)
        }
        context.waitTick()
    }

    /** Finds an EditBox by its pixel width and sets its value. */
    fun fillEditBoxByWidth(widthPx: Int, value: String) {
        context.runOnClient { mc ->
            val screen = mc.screen
                ?: throw AssertionError("fillEditBoxByWidth($widthPx): no screen open")
            val box = screen.children()
                .filterIsInstance<EditBox>()
                .find { it.width == widthPx }
                ?: throw AssertionError(
                    "EditBox with width=$widthPx not found in ${screen::class.simpleName}. " +
                        "Available widths: ${screen.children().filterIsInstance<EditBox>().map { it.width }}"
                )
            box.value = value
        }
        context.waitTick()
    }

    /** Reads the synced client-side BE (lastTestResult is synced via ClientboundBlockEntityDataPacket). */
    fun getClientBe(pos: BlockPos): SpecOriginBlockEntity? =
        context.computeOnClient { mc ->
            mc.level?.getBlockEntity(pos) as? SpecOriginBlockEntity
        }

    companion object {
        fun createWorld(context: ClientGameTestContext): TestSingleplayerContext {
            val world = context.worldBuilder().setUseConsistentSettings(true).create()
            world.getClientLevel().waitForChunksDownload()
            world.getServer().runCommand("time set day")
            world.getServer().runCommand("effect give @a minecraft:saturation 1000000 255 true")
            context.waitTick()
            return world
        }
    }
}
```

- [ ] **Step 2: Create `RedstonespecsClientTests.kt`**

Create `src/test/kotlin/com/breadmoirai/redstonespecs/test/RedstonespecsClientTests.kt`:

```kotlin
package com.breadmoirai.redstonespecs.test

import com.breadmoirai.redstonespecs.client.screen.SpecEditorScreen
import com.breadmoirai.redstonespecs.client.screen.SpecOverviewScreen
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext
import net.minecraft.core.BlockPos

@Suppress("UnstableApiUsage")
class RedstonespecsClientTests : FabricClientGameTest {

    // World-space positions for the test circuit.
    private val originPos = BlockPos(0, 64, 0)
    private val leverPos  = BlockPos(1, 64, 0)
    private val lampPos   = BlockPos(2, 64, 0)

    override fun runTest(context: ClientGameTestContext) {
        SpecTestContext(context, SpecTestContext.createWorld(context)).use {
            leverLampFullFlow(it)
        }
    }

    private fun leverLampFullFlow(ctx: SpecTestContext) {
        // ── World setup ────────────────────────────────────────────────────
        // Stone floor so the lever has support
        for (x in 0..3) ctx.runCommand("setblock ${x} 63 0 minecraft:stone")
        ctx.runCommand("setblock 0 64 0 redstonespecs:spec_origin")
        ctx.runCommand("setblock 1 64 0 minecraft:lever[face=floor,facing=north,powered=false]")
        ctx.runCommand("setblock 2 64 0 minecraft:redstone_lamp[lit=false]")
        ctx.runCommand("tp @a 0 64 -3")
        ctx.waitTicks(5)

        // ── Open overview (right-click SpecOrigin, auto-creates spec) ──────
        ctx.rightClickBlock(originPos)
        ctx.waitForScreen(SpecOverviewScreen::class.java)
        ctx.closeScreen()

        // ── Place InputSpec on lever via marker item ───────────────────────
        ctx.giveItem("redstonespecs:input_spec_marker")
        ctx.context.runOnClient { mc -> mc.player!!.inventory.selected = 0 }
        ctx.waitTick()
        ctx.rightClickBlock(leverPos)
        ctx.waitForScreen(SpecEditorScreen::class.java)
        // INIT entry is pre-populated (lever.powered=false captured by marker item)
        // Add tick-0 entry: powered=true at START_OF_TICK
        ctx.clickButton("+ Add Entry")
        ctx.fillEditBoxByWidth(30, "0")           // Tick EditBox (width=30)
        // Phase CycleButton defaults to END_OF_TICK; click to cycle to START_OF_TICK
        // START_OF_TICK is Phase.ordinal=0; END_OF_TICK is ordinal=5.
        // Cycling from END_OF_TICK: next click goes to START_OF_TICK (wraps around).
        ctx.clickButton("END_OF_TICK")            // CycleButton label shows current value
        // After one click it shows START_OF_TICK
        ctx.fillEditBoxByWidth(220, "powered=true") // Props EditBox (width=220)
        ctx.clickButton("Confirm")
        ctx.clickButton("Save")
        ctx.waitTick()

        // ── Place OutputSpec on lamp via marker item ───────────────────────
        ctx.context.runOnClient { mc -> mc.player!!.inventory.clear() }
        ctx.waitTick()
        ctx.giveItem("redstonespecs:output_spec_marker")
        ctx.context.runOnClient { mc -> mc.player!!.inventory.selected = 0 }
        ctx.waitTick()
        ctx.rightClickBlock(lampPos)
        ctx.waitForScreen(SpecEditorScreen::class.java)
        // INIT entry pre-populated (lamp.lit=false). Add tick-0 END_OF_TICK check.
        ctx.clickButton("+ Add Entry")
        ctx.fillEditBoxByWidth(30, "0")            // Tick=0
        // Phase already defaults to END_OF_TICK — no click needed
        ctx.fillEditBoxByWidth(220, "lit=true")    // Props
        ctx.clickButton("Confirm")
        ctx.clickButton("Save")
        ctx.waitTick()

        // ── Open overview and run spec ────────────────────────────────────
        ctx.context.runOnClient { mc -> mc.player!!.inventory.clear() }
        ctx.waitTick()
        ctx.rightClickBlock(originPos)
        ctx.waitForScreen(SpecOverviewScreen::class.java)
        ctx.clickButton("▶ Run")

        // ── Wait for result (synced to client BE via getUpdatePacket) ─────
        ctx.context.waitFor({ mc ->
            (mc.level?.getBlockEntity(originPos) as? com.breadmoirai.redstonespecs.block.SpecOriginBlockEntity)
                ?.lastTestResult != null
        }, 100)

        // ── Assert all checks passed ───────────────────────────────────────
        val be = ctx.getClientBe(originPos)
            ?: throw AssertionError("SpecOriginBlockEntity not found at $originPos")
        val result = be.lastTestResult
            ?: throw AssertionError("lastTestResult is null after waitFor succeeded")
        assert(result.results.isNotEmpty()) { "Expected at least one SpecCaseResult" }
        val checks = result.results.flatMap { it.checks }
        assert(checks.isNotEmpty()) { "Expected at least one check in results" }
        val failed = checks.filter { !it.pass }
        assert(failed.isEmpty()) {
            "Failed checks: ${failed.joinToString { "${it.label}: expected=${it.expected} actual=${it.actual}" }}"
        }
    }

    /** Auto-closes the test world. */
    private fun SpecTestContext.use(block: SpecTestContext.() -> Unit) {
        world.use { block() }
    }
}
```

- [ ] **Step 3: Run — expect failure at `clickButton("+ Add Entry")`**

```bash
./gradlew runTestClient 2>&1 | grep -E "(FAIL|ERROR|AssertionError|Button)" | head -20
```

Expected output contains something like:
```
AssertionError: Button '+ Add Entry' not found in SpecEditorScreen
```

This confirms the test is wired up correctly and will pass once the editor has the state list.

- [ ] **Step 4: Commit**

```bash
git add src/test/kotlin/com/breadmoirai/redstonespecs/test/
git commit -m "test: add ClientGameTest leverLampFullFlow (currently fails — editor state list pending)"
```

---

## Task 5: Implement SpecEditorScreen redesign

**Files:**
- Modify: `src/client/kotlin/com/breadmoirai/redstonespecs/client/screen/SpecEditorScreen.kt`

The key design decisions:
- `workingEntries: MutableList<Pair<SimTime, Map<String, String>>>?` — null until initialized; survives `rebuildWidgets()`.
- `showAddForm: Boolean` — gate for the add-entry form widgets.
- `tick()` override handles the server→client sync race: if `workingEntries` is null but the entry is now available (BE packet arrived), initialize it and rebuild.
- Panel height 260 (expanded from 200).
- Phase uses `CycleButton<Phase>` defaulting to `Phase.END_OF_TICK`.
- EditBox widths: Label=200, Color=80, Tick=30, Props=220 — all distinct (required by `fillEditBoxByWidth` in tests).

- [ ] **Step 1: Replace `SpecEditorScreen.kt` in full**

```kotlin
package com.breadmoirai.redstonespecs.client.screen

import com.breadmoirai.redstonespecs.block.SpecOriginBlockEntity
import com.breadmoirai.redstonespecs.data.AutoSpec
import com.breadmoirai.redstonespecs.data.BreakpointSpec
import com.breadmoirai.redstonespecs.data.InputSpec
import com.breadmoirai.redstonespecs.data.OutputSpec
import com.breadmoirai.redstonespecs.data.Phase
import com.breadmoirai.redstonespecs.data.SimTime
import com.breadmoirai.redstonespecs.data.SpecEntry
import com.breadmoirai.redstonespecs.data.StateSpec
import com.breadmoirai.redstonespecs.network.RemoveSpecEntryC2SPayload
import com.breadmoirai.redstonespecs.network.SaveSpecEntryC2SPayload
import com.breadmoirai.redstonespecs.runner.captureBlockStateProps
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.CycleButton
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.screens.Screen
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.CommonComponents
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.common.custom.CustomPacketPayload

class SpecEditorScreen(
    private val originPos: BlockPos,
    private val entryRelPos: BlockPos,
) : Screen(Component.translatable("screen.redstonespecs.spec_editor")) {

    private val panelW = 300
    private val panelH = 260
    private val panelX get() = (width - panelW) / 2
    private val panelY get() = (height - panelH) / 2

    private var labelEditBox: EditBox? = null
    private var colorEditBox: EditBox? = null

    // Persists across rebuildWidgets(); null until entry is available from server BE.
    private var workingEntries: MutableList<Pair<SimTime, Map<String, String>>>? = null
    private var showAddForm = false

    // Add-form field refs (populated in init() when showAddForm=true)
    private var addTickEditBox: EditBox? = null
    private var addPhaseButton: CycleButton<Phase>? = null
    private var addPropsEditBox: EditBox? = null

    override fun init() {
        super.init()
        val x = panelX
        val y = panelY
        val entry = getEntry()

        if (workingEntries == null) {
            workingEntries = when (entry) {
                is InputSpec -> entry.stateSpec.entries.toMutableList()
                is OutputSpec -> entry.stateSpec.entries.toMutableList()
                else -> null
            }
        }

        labelEditBox = EditBox(font, x + 52, y + 26, 200, 16, Component.literal("Label")).also {
            it.value = entry?.label ?: ""
            addRenderableWidget(it)
        }
        colorEditBox = EditBox(font, x + 52, y + 46, 80, 16, Component.literal("Color")).also {
            it.value = entry?.color?.let { c -> String.format("%06X", c and 0xFFFFFF) } ?: "FFFFFF"
            addRenderableWidget(it)
        }

        val entries = workingEntries
        if (entries != null) {
            // [✕] remove button per visible row (max 4)
            entries.take(4).forEachIndexed { i, _ ->
                addRenderableWidget(
                    Button.builder(Component.literal("✕")) {
                        entries.removeAt(i)
                        rebuildWidgets()
                    }.bounds(x + panelW - 22, y + 80 + i * 14, 14, 12).build()
                )
            }

            // "+ Add Entry" button
            addRenderableWidget(
                Button.builder(Component.literal("+ Add Entry")) {
                    showAddForm = true
                    rebuildWidgets()
                }.bounds(x + 10, y + 140, 80, 14).build()
            )

            if (showAddForm) {
                // Tick EditBox — width=30 (unique, used by test)
                addTickEditBox = EditBox(font, x + 46, y + 164, 30, 12, Component.literal("tick")).also {
                    addRenderableWidget(it)
                }
                // Phase CycleButton — default END_OF_TICK
                addPhaseButton = CycleButton.builder<Phase> { phase -> Component.literal(phase.name) }
                    .withValues(*Phase.entries.toTypedArray())
                    .withInitialValue(Phase.END_OF_TICK)
                    .create(x + 82, y + 164, 110, 12, Component.literal("Phase")) { _, _ -> }
                    .also { addRenderableWidget(it) }
                // Props EditBox — width=220 (unique, used by test)
                addPropsEditBox = EditBox(font, x + 10, y + 180, 220, 12, Component.literal("key=val,key=val")).also {
                    addRenderableWidget(it)
                }
                addRenderableWidget(
                    Button.builder(Component.literal("Confirm")) { confirmAddEntry() }
                        .bounds(x + 10, y + 196, 58, 14).build()
                )
                addRenderableWidget(
                    Button.builder(Component.literal("Cancel Add")) {
                        showAddForm = false
                        rebuildWidgets()
                    }.bounds(x + 74, y + 196, 68, 14).build()
                )
            }

            addRenderableWidget(
                Button.builder(Component.literal("Capture Init State")) { captureInitState() }
                    .bounds(x + 10, y + 220, 130, 14).build()
            )
        }

        addRenderableWidget(
            Button.builder(Component.literal("Save")) { save() }
                .bounds(x + 10, y + panelH - 22, 60, 18).build()
        )
        addRenderableWidget(
            Button.builder(Component.literal("Remove")) { remove() }
                .bounds(x + 76, y + panelH - 22, 60, 18).build()
        )
        addRenderableWidget(
            Button.builder(CommonComponents.GUI_CANCEL) { onClose() }
                .bounds(x + panelW - 66, y + panelH - 22, 60, 18).build()
        )
    }

    // Handles server→client BE sync race: if workingEntries is still null but entry
    // is now available (packet arrived after screen opened), initialize and rebuild.
    override fun tick() {
        super.tick()
        if (workingEntries == null) {
            when (val entry = getEntry()) {
                is InputSpec -> {
                    workingEntries = entry.stateSpec.entries.toMutableList()
                    rebuildWidgets()
                }
                is OutputSpec -> {
                    workingEntries = entry.stateSpec.entries.toMutableList()
                    rebuildWidgets()
                }
                else -> {}
            }
        }
    }

    private fun confirmAddEntry() {
        val entries = workingEntries ?: return
        val tickText = addTickEditBox?.value?.trim() ?: ""
        val phase = addPhaseButton?.value ?: Phase.END_OF_TICK
        val propsText = addPropsEditBox?.value?.trim() ?: ""

        val simTime = if (tickText.isEmpty()) SimTime.INIT
        else {
            val tick = tickText.toIntOrNull() ?: return
            SimTime(tick, phase)
        }

        val props = if (propsText.isEmpty()) emptyMap()
        else propsText.split(",").mapNotNull { token ->
            val kv = token.trim().split("=", limit = 2)
            if (kv.size == 2) kv[0].trim() to kv[1].trim() else null
        }.toMap()

        entries.add(simTime to props)
        showAddForm = false
        rebuildWidgets()
    }

    override fun extractRenderState(
        extractor: GuiGraphicsExtractor,
        mouseX: Int,
        mouseY: Int,
        partialTick: Float,
    ) {
        extractBackground(extractor, mouseX, mouseY, partialTick)
        super.extractRenderState(extractor, mouseX, mouseY, partialTick)

        val x = panelX
        val y = panelY
        val entry = getEntry()

        extractor.fill(x, y, x + panelW, y + panelH, 0xCC000000.toInt())

        val typeLabel = when (entry) {
            is InputSpec -> "Input"
            is OutputSpec -> "Output"
            is BreakpointSpec -> "Breakpoint"
            is AutoSpec -> "AutoSpec"
            null -> "Entry"
        }
        extractor.centeredText(font, Component.literal("$typeLabel @ $entryRelPos"), x + panelW / 2, y + 6, 0xFFFFFF)
        extractor.text(font, Component.literal("Label:"), x + 8, y + 29, 0xAAAAAA)
        extractor.text(font, Component.literal("Color:"), x + 8, y + 49, 0xAAAAAA)

        val entries = workingEntries
        if (entries != null) {
            extractor.text(
                font,
                Component.literal("State entries: ${entries.size}"),
                x + 8, y + 68, 0x888888,
            )
            entries.take(4).forEachIndexed { i, (simTime, props) ->
                val rowY = y + 82 + i * 14
                val timeLabel = if (simTime == SimTime.INIT) "INIT"
                    else "t${simTime.tick} ${simTime.phase.name.take(5)}"
                val propStr = props.entries.joinToString(",") { "${it.key}=${it.value}" }.let {
                    if (it.length > 28) it.take(27) + "…" else it
                }
                extractor.text(font, Component.literal(timeLabel), x + 10, rowY, 0xAAAAAA)
                extractor.text(font, Component.literal(propStr), x + 68, rowY, 0x888888)
            }
            if (showAddForm) {
                extractor.text(font, Component.literal("Tick:"), x + 8, y + 166, 0xAAAAAA)
            }
        } else {
            when (entry) {
                is BreakpointSpec -> {
                    val color = if (entry.enabled) 0x44FF88 else 0xFF4444
                    extractor.text(
                        font,
                        Component.literal("Enabled: ${entry.enabled}  (${entry.condition::class.simpleName})"),
                        x + 8, y + 70, color,
                    )
                }
                is AutoSpec -> extractor.text(
                    font,
                    Component.literal("Trigger: ${entry.condition::class.simpleName}"),
                    x + 8, y + 70, 0xFFAA00,
                )
                null -> extractor.centeredText(
                    font, Component.literal("Entry not found"), x + panelW / 2, y + 70, 0xFF4444,
                )
                else -> {}
            }
        }
    }

    private fun captureInitState() {
        val mc = minecraft ?: return
        val level = mc.level ?: return
        val worldPos = BlockPos(
            originPos.x + entryRelPos.x,
            originPos.y + entryRelPos.y,
            originPos.z + entryRelPos.z,
        )
        val props = captureBlockStateProps(level.getBlockState(worldPos))
        val entries = workingEntries ?: return
        val idx = entries.indexOfFirst { it.first == SimTime.INIT }
        if (idx >= 0) entries[idx] = SimTime.INIT to props
        else entries.add(0, SimTime.INIT to props)
        rebuildWidgets()
    }

    private fun save() {
        val entry = getEntry() ?: run { onClose(); return }
        val specCaseIndex = getBe()?.activeSpecCaseIndex ?: 0
        val label = labelEditBox?.value ?: ""
        val color = (colorEditBox?.value ?: "FFFFFF").toLongOrNull(16)?.toInt() ?: 0xFFFFFF

        val stateSpec = workingEntries?.let { entries ->
            if (entries.any { it.first == SimTime.INIT }) StateSpec(entries.toList())
            else null
        }

        val updated: SpecEntry = when (entry) {
            is InputSpec -> entry.copy(label = label, color = color,
                stateSpec = stateSpec ?: entry.stateSpec)
            is OutputSpec -> entry.copy(label = label, color = color,
                stateSpec = stateSpec ?: entry.stateSpec)
            is BreakpointSpec -> entry.copy(label = label, color = color)
            is AutoSpec -> entry.copy(label = label, color = color)
        }
        sendPacket(SaveSpecEntryC2SPayload(originPos, specCaseIndex, updated))
        onClose()
    }

    private fun remove() {
        val specCaseIndex = getBe()?.activeSpecCaseIndex ?: 0
        sendPacket(RemoveSpecEntryC2SPayload(originPos, specCaseIndex, entryRelPos))
        onClose()
    }

    override fun onClose() {
        workingEntries = null
        showAddForm = false
        super.onClose()
    }

    override fun isPauseScreen(): Boolean = false

    private fun getBe() = minecraft?.level?.getBlockEntity(originPos) as? SpecOriginBlockEntity
    private fun getEntry(): SpecEntry? {
        val be = getBe() ?: return null
        val spec = be.spec ?: return null
        return spec.specCases.getOrNull(be.activeSpecCaseIndex)?.entryAt(entryRelPos)
    }
    private fun sendPacket(p: CustomPacketPayload) = ClientPlayNetworking.send(p)
}
```

- [ ] **Step 2: Verify the module compiles**

```bash
./gradlew compileClientKotlin compileTestKotlin
```

Expected: `BUILD SUCCESSFUL`. Fix any import errors (e.g. `CycleButton` needs the right import).

- [ ] **Step 3: Commit**

```bash
git add src/client/kotlin/com/breadmoirai/redstonespecs/client/screen/SpecEditorScreen.kt
git commit -m "feat: SpecEditorScreen inline state list — add/remove entries, CycleButton<Phase>"
```

---

## Task 6: Run all tests

- [ ] **Step 1: Run `./gradlew runTestClient`**

```bash
./gradlew runTestClient 2>&1 | tail -40
```

Expected: `BUILD SUCCESSFUL`. Both tests should pass:
- `leverOnLampLit` (GameTest) — SpecRunner drives lever, lamp turns on, result has 1 passing check
- `leverLampFullFlow` (ClientGameTest) — full UI flow, spec runs and all checks pass

- [ ] **Step 2: If `leverLampFullFlow` fails at `clickButton("END_OF_TICK")`**

The CycleButton's message text is the Phase name (`END_OF_TICK`). Verify the button is rendering with that label by adding a debug print or checking the screen's button list. If the button message text differs, update `clickButton("END_OF_TICK")` to match the actual text shown.

- [ ] **Step 3: If `leverOnLampLit` fails with "No test result after 40 ticks"**

Check the log for SubTickPhaseEvent firing:
```bash
grep -E "(SubTickPhase|onPhase|SpecRunner)" versions/26.1/run/logs/latest.log | head -20
```

If the event is not firing, verify `Redstonespecs.onInitialize()` ran (check for the mod's startup logs). The integrated server in `runTestClient` must load the main mod's initializer.

- [ ] **Step 4: If the lamp is not lit (output check fails)**

The `START_OF_TICK` lever-to-lamp propagation happens synchronously, but if a tick scheduling issue arises, shift the output check to `Phase.SCHEDULED_TICKS`:
```kotlin
SimTime(0, Phase.SCHEDULED_TICKS) to mapOf("lit" to "true"),
```
And repeat for the ClientGameTest editor interaction (the phase cycle order is `START_OF_TICK=0, BLOCK_EVENTS=1, TILE_ENTITY_TICK=2, SCHEDULED_TICKS=3, RANDOM_TICKS=4, END_OF_TICK=5`; cycling from END_OF_TICK forward wraps to START_OF_TICK on the next click).

- [ ] **Step 5: Commit green tests**

```bash
git add -A
git commit -m "test: all tests passing — lever→lamp GameTest and full-flow ClientGameTest"
```
