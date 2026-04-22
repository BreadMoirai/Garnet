# Design: SpecEditorScreen State List + Test Infrastructure

Date: 2026-04-21

## Overview

Two closely related goals:

1. **SpecEditorScreen inline state list** — add/edit/remove `StateSpec` entries (tick-time
   state changes) directly in the editor panel, so users can configure InputSpec drive
   sequences and OutputSpec check points without needing AutoSpec recording.

2. **Test infrastructure + tests** — a server-side `@GameTest` verifying the `SpecRunner`
   logic and a `@ClientGameTest` walking the full user flow: marker items → editor
   (including the new state list) → run → assert result.

These goals are coupled: the ClientGameTest needs the state list UI to exist in order to
configure tick-time entries and produce a run with real pass/fail checks.

---

## 1. SpecEditorScreen Redesign

### Current State

The editor panel (300×200) shows:
- Label + Color EditBox fields
- "State entries: N" + 3-line preview (for Input/Output)
- "Capture Init State" button
- Save / Remove / Cancel buttons

There is no way to add new entries at specific SimTimes or remove individual entries.

### New Layout

Panel height grows to **260** to accommodate the state list. Layout (top to bottom):

```
y+ 6   Title: "<Type> @ relPos"
y+26   Label: [______________]
y+46   Color: [______]
y+68   "State entries:" header + count
y+80   ┌─────────────────────────────────┐  ← scrollable list (max 4 rows visible, 14px each)
       │ INIT         powered=false  [✕] │
       │ t0 SCHED     powered=true   [✕] │
       │                                 │
       └─────────────────────────────────┘
y+140  [+ Add Entry]
y+164  ── add-entry form (shown only when adding) ──
       Tick: [__]  Phase: [__________]
       Props: [key=value, key=value ...]
       [Confirm]  [Cancel Add]
y+220  [Capture Init]   (Input/Output only)
y+240  [Save]  [Remove]  [Cancel]
```

The add-entry form is **hidden by default**; clicking "+ Add Entry" reveals it inline
and hides itself. "Confirm" appends the entry, "Cancel Add" hides the form again.

### State List Rendering

Each row:
- Left column: SimTime label — `"INIT"` for `SimTime.INIT`, else `"t<tick> <phase>"` where
  phase is abbreviated (e.g., `SCHED` for `SCHEDULED_TICKS`, `START` for `START_OF_TICK`, etc.)
- Middle column: props summary — `key=value` pairs joined with `,` (truncated if too long)
- Right: small `[✕]` remove button (12×12)

Rows are rendered via `extractRenderState` (read-only overlay). The `[✕]` button widgets
are added in `init()` for each existing entry.

### Add-Entry Form Fields

| Field  | Widget       | Validation |
|--------|-------------|------------|
| Tick   | `EditBox` (width 30) | int ≥ 0 or blank for INIT |
| Phase  | `EditBox` (width 90) | must match a `Phase` name (case-insensitive) |
| Props  | `EditBox` (width 220) | `key=value` pairs separated by `,` |

The screen holds a mutable `workingStateSpec: StateSpec` (initialised from the current
entry's `stateSpec` in `init()`). All edits mutate this local copy; nothing is sent
to the server until "Save" is clicked.

On "Confirm":
- Parse tick (blank → INIT), phase (blank or "INIT" → `START_OF_TICK`), props map
- Append `(SimTime(tick, phase), propsMap)` to `workingStateSpec`
- Call `rebuildWidgets()` (MC Screen helper: `clearWidgets()` + `init()`) so the state
  list re-renders with the new row
- Hide the add form (add-form flag reset before `rebuildWidgets()`)

### Save / Cancel Behaviour

"Save" sends `SaveSpecEntryC2SPayload` with the fully updated entry (including all
modified `StateSpec` entries). No new packets are needed — the existing payload carries
the whole `SpecEntry`.

"Remove" sends `RemoveSpecEntryC2SPayload` (unchanged).

### For BreakpointSpec and AutoSpec

These don't have a `StateSpec`, so the state list section is hidden for them. Layout
reverts to the existing compact form.

---

## 2. Missing Item Registrations

`InputSpecMarkerItem`, `OutputSpecMarkerItem`, `BreakpointSpecMarkerItem`, and
`AutoSpecMarkerItem` have assets (models, textures) but are not registered in
`ModRegistries`. These must be added before the ClientGameTest can give them to the
player.

Item IDs (matching existing asset paths):
- `redstonespecs:input_spec_marker`
- `redstonespecs:output_spec_marker`
- `redstonespecs:breakpoint_spec_marker`
- `redstonespecs:auto_spec_marker`

---

## 3. Test Infrastructure

### build.gradle.kts additions

```kotlin
val hasClientGameTestApi = property("minecraft_version").toString() >= "1.21.4"  // always true for 26.1

sourceSets {
    named("test") {
        compileClasspath += sourceSets.main.get().compileClasspath
            + sourceSets.client.get().compileClasspath
        runtimeClasspath += sourceSets.main.get().runtimeClasspath
            + sourceSets.client.get().runtimeClasspath
    }
}

loom {
    // existing splitEnvironmentSourceSets + mods block unchanged
    runs {
        register("TestClient") {
            client()
            name("Test Client")
            source(sourceSets.test.get())
            vmArgs("-Dfabric.client.gametest", "-Dfabric.client.gametest.disableNetworkSynchronizer")
        }
    }
    createRemapConfigurations(sourceSets.test.get())
}

dependencies {
    // existing deps unchanged
    testImplementation(sourceSets.main.get().output)
    testImplementation(sourceSets.client.get().output)
}
```

### src/test/resources/fabric.mod.json

```json
{
  "schemaVersion": 1,
  "id": "redstonespecs-test",
  "version": "1.0.0",
  "name": "RedstoneSpecs Testmod",
  "environment": "client",
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

### Structure file

`src/test/resources/data/redstonespecs/gametest/structures/lever_lamp.snbt`

A 5×4×5 flat area with a stone floor at y=0, all air above. The test places all
functional blocks programmatically via `GameTestHelper.setBlock()`.

---

## 4. GameTest — LeverLampSpecTest

**File:** `src/test/kotlin/com/breadmoirai/redstonespecs/test/LeverLampSpecTest.kt`

**Purpose:** Verify that `SpecRunner` correctly drives a lever input and checks a redstone
lamp output for a 1-tick spec.

### Circuit

Placed programmatically at these positions relative to the structure origin:

```
y=1: SpecOrigin @ (0,1,0)   Lever @ (1,1,0)   Lamp @ (2,1,0)
y=0: stone       stone       stone
```

The lever is placed directly adjacent to the lamp. `SpecRunner.applyInputsAt` calls
`level.setBlock(leverPos, state.with(powered, true), flags=3)` which triggers a neighbor
notification; the adjacent lamp receives power and becomes lit synchronously.

### Spec Configuration (direct BE mutation, no UI)

```kotlin
InputSpec(
    pos    = BlockPos(1, 0, 0),   // relPos from SpecOrigin
    label  = "lever",
    color  = 0x4488FF,
    stateSpec = StateSpec(listOf(
        SimTime.INIT               to mapOf("powered" to "false"),
        SimTime(0, Phase.START_OF_TICK) to mapOf("powered" to "true"),
    ))
)
OutputSpec(
    pos    = BlockPos(2, 0, 0),
    label  = "lamp",
    color  = 0x44FF88,
    stateSpec = StateSpec(listOf(
        SimTime.INIT               to mapOf("lit" to "false"),
        SimTime(0, Phase.END_OF_TICK) to mapOf("lit" to "true"),
    ))
)
SpecCase(name = "lever on → lamp lit", lifespan = 2, inputs, outputs, ...)
```

### Test Flow

```kotlin
class LeverLampSpecTest : FabricGameTest {
    @GameTest(templateNamespace = "redstonespecs", templateId = "lever_lamp")
    fun leverOnLampLit(helper: GameTestHelper) {
        // 1. Place blocks
        helper.setBlock(BlockPos(0,1,0), ModRegistries.SPEC_ORIGIN_BLOCK)
        helper.setBlock(BlockPos(1,1,0), Blocks.LEVER.defaultBlockState())
        helper.setBlock(BlockPos(2,1,0), Blocks.REDSTONE_LAMP.defaultBlockState())

        // 2. Set spec on BE
        val be = helper.getBlockEntity(BlockPos(0,1,0)) as SpecOriginBlockEntity
        be.setSpec( /* RedstoneSpec with InputSpec + OutputSpec as above */ )

        // 3. Start run
        SpecRunnerCoordinator.startRun(be, false)

        // 4. Wait and assert
        helper.runAfterDelay(40) {
            val result = be.lastTestResult
                ?: throw GameTestAssertException("No test result received after 40 ticks")
            val checks = result.results.flatMap { it.checks }
            if (checks.isEmpty()) throw GameTestAssertException("No checks recorded")
            val failed = checks.filter { !it.pass }
            if (failed.isNotEmpty()) throw GameTestAssertException(
                "Checks failed: ${failed.joinToString { "${it.label}: expected=${it.expected} actual=${it.actual}" }}"
            )
            helper.succeed()
        }
    }
}
```

---

## 5. ClientGameTest — RedstonespecsClientTests

**File:** `src/test/kotlin/com/breadmoirai/redstonespecs/test/RedstonespecsClientTests.kt`

**Purpose:** Verify the full user flow from an empty `SpecOrigin` through UI interaction
to a spec run with real pass/fail checks.

### Helper class: `SpecTestContext`

Wraps `ClientGameTestContext` + `TestSingleplayerContext` with helpers specific to this
mod. Mirrors OCC's `TestSuite` pattern.

```kotlin
class SpecTestContext(
    val context: ClientGameTestContext,
    val world: TestSingleplayerContext,
) {
    fun rightClickBlock(pos: BlockPos, direction: Direction = Direction.WEST) { ... }
    fun clickButton(labelText: String) { ... }  // finds Button in current screen by label text
    fun fillEditBox(placeholder: String, value: String) { ... }
    fun waitForScreen(clazz: Class<out Screen>) = context.waitForScreen(clazz)
    fun waitTick() = context.waitTick()
    fun waitTicks(n: Int) = context.waitTicks(n)
    fun closeScreen() { context.getInput().pressKey(GLFW.GLFW_KEY_ESCAPE); context.waitFor { mc -> mc.screen == null } }
    fun runCommand(cmd: String) = world.getServer().runCommand(cmd)
    fun giveItem(itemId: String) = runCommand("give @a $itemId 1")
    // lastTestResult is synced to the client via ClientboundBlockEntityDataPacket
    fun getClientBe(pos: BlockPos): SpecOriginBlockEntity? =
        context.computeOnClient { mc -> mc.level?.getBlockEntity(pos) as? SpecOriginBlockEntity }
}
```

### Test World Setup

```kotlin
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
```

### Test: `leverLampFullFlow`

```
1.  Place floor (stone 5×5 at y=63), SpecOrigin at (0,64,0), lever at (1,64,0), lamp at (2,64,0)
     → via /setblock commands

2.  Teleport player near origin: /tp @a 0 64 -3

3.  Right-click SpecOrigin
     → server creates "New Spec" + sends OpenOverviewS2CPayload
     → waitForScreen(SpecOverviewScreen::class.java)

4.  Close screen (Escape)

5.  /give @a redstonespecs:input_spec_marker 1
    Right-click lever
     → SpecMarkerTool.useOn adds InputSpec, sends OpenEditorS2CPayload
     → waitForScreen(SpecEditorScreen::class.java)

6.  Click "Capture Init State"  (captures lever.powered=false as INIT entry)
    Click "+ Add Entry", fill Tick=0, Phase=start_of_tick, Props=powered=true, Confirm
    Click "Save"
     → SaveSpecEntryC2SPayload sent with updated InputSpec

7.  /give @a redstonespecs:output_spec_marker 1
    Right-click lamp
     → waitForScreen(SpecEditorScreen::class.java)

8.  Click "Capture Init State"  (captures lamp.lit=false as INIT entry)
    Click "+ Add Entry", fill Tick=0, Phase=end_of_tick, Props=lit=true, Confirm
    Click "Save"

9.  Right-click SpecOrigin
     → waitForScreen(SpecOverviewScreen::class.java)

10. Click "▶ Run"

11. context.waitFor({ mc ->
        (mc.level?.getBlockEntity(originPos) as? SpecOriginBlockEntity)?.lastTestResult != null
    }, 100)

12. Assert (computed on client from synced BE):
     - be.lastTestResult!!.results.size == 1
     - checks.isNotEmpty()
     - checks.all { it.pass }
```

### Button clicking

Buttons in our screens are `Button` widgets added via `addRenderableWidget`. The helper
clicks them by iterating `screen.children()` to find a `Button` whose message text
matches, then calling `screen.mouseClicked(cx, cy, 0)` at the button's center.

---

## 6. Out of Scope (This Plan)

- Editing existing state entries (only add + remove in this design)
- Phase selector as a dropdown widget (plain EditBox accepting phase name)
- Multi-property inline table (comma-separated `key=value` string in one EditBox)
