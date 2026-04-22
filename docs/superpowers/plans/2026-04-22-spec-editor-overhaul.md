# SpecEditorScreen Overhaul Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Overhaul `SpecEditorScreen` with a typed `StateCondition` data model, YACL config, color picker widget, scrollable entry table, and a `StateEntryEditorScreen` sub-screen backed by `BlockStateFormBuilder`.

**Architecture:** The data model (`StateCondition`, `SpecEntry`) is refactored first so that all downstream code (SpecRunner, AutoSpecRecorder, SpecMarkerTool) compiles before touching UI. New client-only files (`ModConfig`, `ColorPickerWidget`, `BlockStateFormBuilder`, `StateEntryEditorScreen`) are built in dependency order, then `SpecEditorScreen` is rewritten last to wire them together.

**Tech Stack:** Kotlin, Minecraft Fabric (26.1), YACL 3.9.2, Mod Menu 18.0.0-alpha.8, Mojang Codec / StreamCodec

---

## File Map

| Path | Status | Purpose |
|---|---|---|
| `src/main/kotlin/.../data/StateCondition.kt` | Modify | Add typed leaves, remove BlockState |
| `src/main/kotlin/.../data/SpecEntry.kt` | Modify | Replace stateSpec with entries |
| `src/main/kotlin/.../data/StateSpec.kt` | **Delete** | Replaced by List<Pair<SimTime, StateCondition>> |
| `src/main/kotlin/.../runner/ConditionEvaluator.kt` | Modify | Handle typed leaves + new propsToCondition util |
| `src/main/kotlin/.../runner/SpecRunner.kt` | Modify | applyInputs/checkOutputs use typed conditions |
| `src/main/kotlin/.../runner/AutoSpecRecorder.kt` | Modify | Build typed entries instead of StateSpec |
| `src/main/kotlin/.../item/SpecMarkerTool.kt` | Modify | Create typed INIT entry |
| `src/main/resources/fabric.mod.json` | Modify | Add modmenu entrypoint |
| `src/test/kotlin/.../data/StateConditionTest.kt` | Modify | Replace BlockState tests with typed leaf tests |
| `src/test/kotlin/.../data/StateSpecTest.kt` | **Delete** | StateSpec no longer exists |
| `src/test/kotlin/.../data/SpecEntryTest.kt` | Modify | Use new entries field |
| `src/client/kotlin/.../client/config/ModConfig.kt` | **Create** | YACL config singleton |
| `src/client/kotlin/.../client/config/ModMenuIntegration.kt` | **Create** | Mod Menu entrypoint |
| `src/client/kotlin/.../client/widget/ColorPickerWidget.kt` | **Create** | 4×4 dye color grid + hex input |
| `src/client/kotlin/.../client/widget/BlockStateFormBuilder.kt` | **Create** | Typed PropertyRow factory |
| `src/client/kotlin/.../client/screen/StateEntryEditorScreen.kt` | **Create** | Sub-screen for editing (SimTime, StateCondition) |
| `src/client/kotlin/.../client/screen/SpecEditorScreen.kt` | Modify | Table, color picker, dirty guard, Capture State |

All paths under `src/main/kotlin/` are in package `com.breadmoirai.redstonespecs`.
All paths under `src/client/kotlin/` are in package `com.breadmoirai.redstonespecs.client`.

---

## Task 1: Refactor `StateCondition`

**Files:**
- Modify: `src/main/kotlin/com/breadmoirai/redstonespecs/data/StateCondition.kt`
- Modify: `src/test/kotlin/com/breadmoirai/redstonespecs/data/StateConditionTest.kt`

- [ ] **Step 1: Replace `StateCondition.kt` with the new typed model**

```kotlin
package com.breadmoirai.redstonespecs.data

import com.mojang.serialization.Codec
import com.mojang.serialization.MapCodec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.resources.Identifier
import java.util.Optional

sealed class StateCondition {

    data class BlockType(val blockId: Identifier) : StateCondition()
    data class BoolProperty(val name: String, val value: Boolean) : StateCondition()
    data class IntProperty(val name: String, val value: Int) : StateCondition()
    data class EnumProperty(val name: String, val value: String) : StateCondition()

    data class All(val conditions: List<StateCondition>) : StateCondition()
    data class Any(val conditions: List<StateCondition>) : StateCondition()
    data class Not(val condition: StateCondition) : StateCondition()
    data class ContainerContents(
        val slot: Int? = null,
        val item: Identifier? = null,
        val minCount: Int = 1,
    ) : StateCondition()

    companion object {
        val CODEC: Codec<StateCondition> = Codec.lazyInitialized {
            val blockTypeCodec: MapCodec<BlockType> = RecordCodecBuilder.mapCodec { instance ->
                instance.group(
                    Identifier.CODEC.fieldOf("block").forGetter(BlockType::blockId),
                ).apply(instance, ::BlockType)
            }
            val boolPropertyCodec: MapCodec<BoolProperty> = RecordCodecBuilder.mapCodec { instance ->
                instance.group(
                    Codec.STRING.fieldOf("name").forGetter(BoolProperty::name),
                    Codec.BOOL.fieldOf("value").forGetter(BoolProperty::value),
                ).apply(instance, ::BoolProperty)
            }
            val intPropertyCodec: MapCodec<IntProperty> = RecordCodecBuilder.mapCodec { instance ->
                instance.group(
                    Codec.STRING.fieldOf("name").forGetter(IntProperty::name),
                    Codec.INT.fieldOf("value").forGetter(IntProperty::value),
                ).apply(instance, ::IntProperty)
            }
            val enumPropertyCodec: MapCodec<EnumProperty> = RecordCodecBuilder.mapCodec { instance ->
                instance.group(
                    Codec.STRING.fieldOf("name").forGetter(EnumProperty::name),
                    Codec.STRING.fieldOf("value").forGetter(EnumProperty::value),
                ).apply(instance, ::EnumProperty)
            }
            val allCodec: MapCodec<All> = RecordCodecBuilder.mapCodec { instance ->
                instance.group(
                    CODEC.listOf().fieldOf("conditions").forGetter(All::conditions)
                ).apply(instance, ::All)
            }
            val anyCodec: MapCodec<Any> = RecordCodecBuilder.mapCodec { instance ->
                instance.group(
                    CODEC.listOf().fieldOf("conditions").forGetter(Any::conditions)
                ).apply(instance, ::Any)
            }
            val notCodec: MapCodec<Not> = RecordCodecBuilder.mapCodec { instance ->
                instance.group(
                    CODEC.fieldOf("condition").forGetter(Not::condition)
                ).apply(instance, ::Not)
            }
            val containerContentsCodec: MapCodec<ContainerContents> = RecordCodecBuilder.mapCodec { instance ->
                instance.group(
                    Codec.INT.optionalFieldOf("slot").forGetter { Optional.ofNullable(it.slot) },
                    Identifier.CODEC.optionalFieldOf("item").forGetter { Optional.ofNullable(it.item) },
                    Codec.INT.optionalFieldOf("min_count", 1).forGetter(ContainerContents::minCount),
                ).apply(instance) { slot, item, minCount ->
                    ContainerContents(slot.orElse(null), item.orElse(null), minCount)
                }
            }

            val codecMap = mapOf<String, MapCodec<out StateCondition>>(
                "block_type" to blockTypeCodec,
                "bool_property" to boolPropertyCodec,
                "int_property" to intPropertyCodec,
                "enum_property" to enumPropertyCodec,
                "all" to allCodec,
                "any" to anyCodec,
                "not" to notCodec,
                "container_contents" to containerContentsCodec,
            )

            Codec.STRING.dispatch(
                "type",
                { condition: StateCondition ->
                    when (condition) {
                        is BlockType -> "block_type"
                        is BoolProperty -> "bool_property"
                        is IntProperty -> "int_property"
                        is EnumProperty -> "enum_property"
                        is All -> "all"
                        is Any -> "any"
                        is Not -> "not"
                        is ContainerContents -> "container_contents"
                    }
                },
                { type: String ->
                    codecMap[type] ?: throw IllegalArgumentException("Unknown StateCondition type: $type")
                }
            )
        }
    }
}

val DEFAULT_CONDITION: StateCondition = StateCondition.BoolProperty("powered", true)
```

- [ ] **Step 2: Replace `StateConditionTest.kt` with typed-leaf tests**

```kotlin
package com.breadmoirai.redstonespecs.data

import net.minecraft.SharedConstants
import net.minecraft.nbt.NbtOps
import net.minecraft.resources.Identifier
import net.minecraft.server.Bootstrap
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StateConditionTest {

    @BeforeAll
    fun bootstrap() {
        SharedConstants.tryDetectVersion()
        Bootstrap.bootStrap()
    }

    private fun <T> roundtrip(value: T, codec: com.mojang.serialization.Codec<T>): T {
        val encoded = codec.encodeStart(NbtOps.INSTANCE, value).getOrThrow()
        return codec.parse(NbtOps.INSTANCE, encoded).getOrThrow()
    }

    @Test
    fun `BlockType roundtrip`() {
        val cond = StateCondition.BlockType(Identifier.fromNamespaceAndPath("minecraft", "redstone_lamp"))
        assertEquals(cond, roundtrip(cond, StateCondition.CODEC))
    }

    @Test
    fun `BoolProperty roundtrip true`() {
        val cond = StateCondition.BoolProperty("powered", true)
        assertEquals(cond, roundtrip(cond, StateCondition.CODEC))
    }

    @Test
    fun `BoolProperty roundtrip false`() {
        val cond = StateCondition.BoolProperty("lit", false)
        assertEquals(cond, roundtrip(cond, StateCondition.CODEC))
    }

    @Test
    fun `IntProperty roundtrip`() {
        val cond = StateCondition.IntProperty("power", 7)
        assertEquals(cond, roundtrip(cond, StateCondition.CODEC))
    }

    @Test
    fun `EnumProperty roundtrip`() {
        val cond = StateCondition.EnumProperty("facing", "north")
        assertEquals(cond, roundtrip(cond, StateCondition.CODEC))
    }

    @Test
    fun `ContainerContents no optionals roundtrip`() {
        val cond = StateCondition.ContainerContents()
        assertEquals(cond, roundtrip(cond, StateCondition.CODEC))
    }

    @Test
    fun `ContainerContents with slot and item roundtrip`() {
        val cond = StateCondition.ContainerContents(
            slot = 3,
            item = Identifier.fromNamespaceAndPath("minecraft", "diamond"),
            minCount = 5,
        )
        assertEquals(cond, roundtrip(cond, StateCondition.CODEC))
    }

    @Test
    fun `All roundtrip`() {
        val cond = StateCondition.All(listOf(
            StateCondition.BoolProperty("powered", true),
            StateCondition.IntProperty("power", 4),
        ))
        assertEquals(cond, roundtrip(cond, StateCondition.CODEC))
    }

    @Test
    fun `Any roundtrip`() {
        val cond = StateCondition.Any(listOf(
            StateCondition.BoolProperty("lit", true),
            StateCondition.BoolProperty("powered", true),
        ))
        assertEquals(cond, roundtrip(cond, StateCondition.CODEC))
    }

    @Test
    fun `Not roundtrip`() {
        val cond = StateCondition.Not(StateCondition.BoolProperty("powered", true))
        assertEquals(cond, roundtrip(cond, StateCondition.CODEC))
    }

    @Test
    fun `nested recursive condition roundtrip`() {
        val cond = StateCondition.All(listOf(
            StateCondition.Not(
                StateCondition.Any(listOf(
                    StateCondition.BoolProperty("powered", false),
                    StateCondition.ContainerContents(slot = 0),
                ))
            ),
            StateCondition.EnumProperty("facing", "south"),
        ))
        assertEquals(cond, roundtrip(cond, StateCondition.CODEC))
    }

    @Test
    fun `DEFAULT_CONDITION is BoolProperty powered=true`() {
        assertEquals(StateCondition.BoolProperty("powered", true), DEFAULT_CONDITION)
    }
}
```

- [ ] **Step 3: Run tests — expect failures in StateSpecTest, SpecEntryTest (will fix next task)**

```bash
cd /mnt/h/Repo/RedstoneSpecs && ./gradlew test 2>&1 | tail -40
```

Expected: `StateConditionTest` passes. `StateSpecTest` and `SpecEntryTest` fail with compile errors (StateSpec / stateSpec still used there — fixed in Task 2).

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/com/breadmoirai/redstonespecs/data/StateCondition.kt \
        src/test/kotlin/com/breadmoirai/redstonespecs/data/StateConditionTest.kt
git commit -m "refactor: replace StateCondition.BlockState with typed leaf variants"
```

---

## Task 2: Refactor `SpecEntry`, delete `StateSpec`, update `ConditionEvaluator`, `SpecRunner`, `AutoSpecRecorder`, `SpecMarkerTool`

**Files:**
- Modify: `src/main/kotlin/com/breadmoirai/redstonespecs/data/SpecEntry.kt`
- Delete: `src/main/kotlin/com/breadmoirai/redstonespecs/data/StateSpec.kt`
- Modify: `src/main/kotlin/com/breadmoirai/redstonespecs/runner/ConditionEvaluator.kt`
- Modify: `src/main/kotlin/com/breadmoirai/redstonespecs/runner/SpecRunner.kt`
- Modify: `src/main/kotlin/com/breadmoirai/redstonespecs/runner/AutoSpecRecorder.kt`
- Modify: `src/main/kotlin/com/breadmoirai/redstonespecs/item/SpecMarkerTool.kt`
- Delete: `src/test/kotlin/com/breadmoirai/redstonespecs/data/StateSpecTest.kt`
- Modify: `src/test/kotlin/com/breadmoirai/redstonespecs/data/SpecEntryTest.kt`

- [ ] **Step 1: Delete `StateSpec.kt` and `StateSpecTest.kt`**

```bash
rm src/main/kotlin/com/breadmoirai/redstonespecs/data/StateSpec.kt
rm src/test/kotlin/com/breadmoirai/redstonespecs/data/StateSpecTest.kt
```

- [ ] **Step 2: Rewrite `SpecEntry.kt`**

```kotlin
package com.breadmoirai.redstonespecs.data

import com.mojang.serialization.Codec
import com.mojang.serialization.MapCodec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.core.BlockPos

private val ENTRY_CODEC: Codec<Pair<SimTime, StateCondition>> =
    RecordCodecBuilder.create { instance ->
        instance.group(
            SimTime.CODEC.fieldOf("time").forGetter { it.first },
            StateCondition.CODEC.fieldOf("condition").forGetter { it.second },
        ).apply(instance) { time, cond -> time to cond }
    }

val ENTRIES_CODEC: Codec<List<Pair<SimTime, StateCondition>>> = ENTRY_CODEC.listOf()

sealed class SpecEntry {
    abstract val pos: BlockPos
    abstract val label: String
    abstract val color: Int

    companion object {
        val CODEC: Codec<SpecEntry> = Codec.STRING.dispatch(
            "type",
            { entry: SpecEntry ->
                when (entry) {
                    is InputSpec -> "input"
                    is OutputSpec -> "output"
                    is BreakpointSpec -> "breakpoint"
                    is AutoSpec -> "auto"
                }
            },
            { type: String ->
                when (type) {
                    "input" -> InputSpec.MAP_CODEC
                    "output" -> OutputSpec.MAP_CODEC
                    "breakpoint" -> BreakpointSpec.MAP_CODEC
                    "auto" -> AutoSpec.MAP_CODEC
                    else -> throw IllegalArgumentException("Unknown SpecEntry type: $type")
                }
            }
        )
    }
}

data class InputSpec(
    override val pos: BlockPos,
    override val label: String,
    override val color: Int,
    val entries: List<Pair<SimTime, StateCondition>>,
) : SpecEntry() {
    companion object {
        val MAP_CODEC: MapCodec<InputSpec> = RecordCodecBuilder.mapCodec { instance ->
            instance.group(
                BlockPos.CODEC.fieldOf("pos").forGetter(InputSpec::pos),
                Codec.STRING.fieldOf("label").forGetter(InputSpec::label),
                Codec.INT.fieldOf("color").forGetter(InputSpec::color),
                ENTRIES_CODEC.fieldOf("entries").forGetter(InputSpec::entries),
            ).apply(instance, ::InputSpec)
        }
    }
}

data class OutputSpec(
    override val pos: BlockPos,
    override val label: String,
    override val color: Int,
    val entries: List<Pair<SimTime, StateCondition>>,
) : SpecEntry() {
    companion object {
        val MAP_CODEC: MapCodec<OutputSpec> = RecordCodecBuilder.mapCodec { instance ->
            instance.group(
                BlockPos.CODEC.fieldOf("pos").forGetter(OutputSpec::pos),
                Codec.STRING.fieldOf("label").forGetter(OutputSpec::label),
                Codec.INT.fieldOf("color").forGetter(OutputSpec::color),
                ENTRIES_CODEC.fieldOf("entries").forGetter(OutputSpec::entries),
            ).apply(instance, ::OutputSpec)
        }
    }
}

data class BreakpointSpec(
    override val pos: BlockPos,
    override val label: String,
    override val color: Int,
    val condition: StateCondition = DEFAULT_CONDITION,
    val enabled: Boolean = true,
) : SpecEntry() {
    companion object {
        val MAP_CODEC: MapCodec<BreakpointSpec> = RecordCodecBuilder.mapCodec { instance ->
            instance.group(
                BlockPos.CODEC.fieldOf("pos").forGetter(BreakpointSpec::pos),
                Codec.STRING.fieldOf("label").forGetter(BreakpointSpec::label),
                Codec.INT.fieldOf("color").forGetter(BreakpointSpec::color),
                StateCondition.CODEC.optionalFieldOf("condition", DEFAULT_CONDITION)
                    .forGetter(BreakpointSpec::condition),
                Codec.BOOL.optionalFieldOf("enabled", true).forGetter(BreakpointSpec::enabled),
            ).apply(instance, ::BreakpointSpec)
        }
    }
}

data class AutoSpec(
    override val pos: BlockPos,
    override val label: String,
    override val color: Int,
    val condition: StateCondition = DEFAULT_CONDITION,
) : SpecEntry() {
    companion object {
        val MAP_CODEC: MapCodec<AutoSpec> = RecordCodecBuilder.mapCodec { instance ->
            instance.group(
                BlockPos.CODEC.fieldOf("pos").forGetter(AutoSpec::pos),
                Codec.STRING.fieldOf("label").forGetter(AutoSpec::label),
                Codec.INT.fieldOf("color").forGetter(AutoSpec::color),
                StateCondition.CODEC.optionalFieldOf("condition", DEFAULT_CONDITION)
                    .forGetter(AutoSpec::condition),
            ).apply(instance, ::AutoSpec)
        }
    }
}
```

- [ ] **Step 3: Rewrite `ConditionEvaluator.kt`**

Add `propsToCondition` utility (used by `AutoSpecRecorder` and `SpecMarkerTool`). Add typed leaf evaluation cases.

```kotlin
package com.breadmoirai.redstonespecs.runner

import com.breadmoirai.redstonespecs.data.StateCondition
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.BooleanProperty
import net.minecraft.world.level.block.state.properties.IntegerProperty
import net.minecraft.world.level.block.state.properties.Property

fun evaluateCondition(condition: StateCondition, level: Level, worldPos: BlockPos): Boolean {
    val state = level.getBlockState(worldPos)
    return evaluateConditionOnState(condition, state, level, worldPos)
}

private fun evaluateConditionOnState(condition: StateCondition, state: BlockState, level: Level, worldPos: BlockPos): Boolean = when (condition) {
    is StateCondition.All -> condition.conditions.all { evaluateConditionOnState(it, state, level, worldPos) }
    is StateCondition.Any -> condition.conditions.any { evaluateConditionOnState(it, state, level, worldPos) }
    is StateCondition.Not -> !evaluateConditionOnState(condition.condition, state, level, worldPos)
    is StateCondition.BlockType -> {
        val actualId = BuiltInRegistries.BLOCK.getKey(state.block)?.location() ?: return false
        actualId == condition.blockId
    }
    is StateCondition.BoolProperty -> {
        val prop = state.block.stateDefinition.getProperty(condition.name) as? BooleanProperty ?: return false
        state.getValue(prop) == condition.value
    }
    is StateCondition.IntProperty -> {
        val prop = state.block.stateDefinition.getProperty(condition.name) as? IntegerProperty ?: return false
        state.getValue(prop) == condition.value
    }
    is StateCondition.EnumProperty -> blockStatePropertyStr(state, condition.name) == condition.value
    is StateCondition.ContainerContents -> false
}

fun blockStatePropertyStr(state: BlockState, propName: String): String? {
    val property = state.block.stateDefinition.getProperty(propName) ?: return null
    if (!state.hasProperty(property)) return null
    @Suppress("UNCHECKED_CAST")
    return readPropertyStr(state, property as Property<Comparable<Any>>)
}

fun <T : Comparable<T>> readPropertyStr(state: BlockState, property: Property<T>): String =
    property.getName(state.getValue(property))

fun captureBlockStateProps(state: BlockState): Map<String, String> =
    state.block.stateDefinition.properties.associate { prop ->
        @Suppress("UNCHECKED_CAST")
        prop.name to readPropertyStr(state, prop as Property<Comparable<Any>>)
    }

/** Converts a Map<String,String> property diff (from captureBlockStateProps) to a typed StateCondition. */
fun propsToCondition(props: Map<String, String>, state: BlockState): StateCondition {
    val conditions = props.map { (name, value) ->
        when (state.block.stateDefinition.getProperty(name)) {
            is BooleanProperty -> StateCondition.BoolProperty(name, value.toBoolean())
            is IntegerProperty -> StateCondition.IntProperty(name, value.toInt())
            else -> StateCondition.EnumProperty(name, value)
        }
    }
    return when (conditions.size) {
        0 -> StateCondition.All(emptyList())
        1 -> conditions[0]
        else -> StateCondition.All(conditions)
    }
}
```

- [ ] **Step 4: Rewrite `SpecRunner.kt`**

Replace `stateSpec.entries` references with typed `entries` access. Replace `setBlockStateProperties(Map<String,String>)` with `applyCondition`. Replace `checkOutputsAt` map iteration with typed `collectChecks`.

```kotlin
package com.breadmoirai.redstonespecs.runner

import com.breadmoirai.redstonespecs.data.InputSpec
import com.breadmoirai.redstonespecs.data.Phase
import com.breadmoirai.redstonespecs.data.RedstoneSpec
import com.breadmoirai.redstonespecs.data.SimTime
import com.breadmoirai.redstonespecs.data.SpecCase
import com.breadmoirai.redstonespecs.data.SpecCaseResult
import com.breadmoirai.redstonespecs.data.StateCondition
import com.breadmoirai.redstonespecs.data.TickCheck
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.BooleanProperty
import net.minecraft.world.level.block.state.properties.IntegerProperty
import net.minecraft.world.level.block.state.properties.Property
import org.slf4j.LoggerFactory

private val LOGGER = LoggerFactory.getLogger("Redstone Specs")

data class BreakpointHit(
    val simTime: SimTime,
    val specName: String,
    val caseName: String,
    val breakpointLabel: String,
)

class SpecRunner(
    val spec: RedstoneSpec,
    val specCase: SpecCase,
    val originPos: BlockPos,
    val level: ServerLevel,
    private val snapshot: SpecSnapshot,
) {
    private var ticksElapsed = -1
    private val checks = mutableListOf<TickCheck>()

    var frozenAt: SimTime? = null
        private set
    var pendingBreakpointHit: BreakpointHit? = null
        private set

    fun start() {
        LOGGER.debug("[SpecRunner#start] starting case '{}' of spec '{}'", specCase.name, spec.name)
        applyInputsAt(SimTime.INIT)
    }

    fun resume() {
        LOGGER.debug("[SpecRunner#resume] resuming case '{}' frozen at {}", specCase.name, frozenAt)
        frozenAt = null
    }

    fun clearPendingBreakpointHit() { pendingBreakpointHit = null }

    fun resetCircuit() { snapshot.restore(level) }

    fun onPhase(phase: Phase): SpecCaseResult? {
        if (frozenAt != null) return null
        if (phase == Phase.START_OF_TICK) ticksElapsed++
        if (ticksElapsed < 0) return null
        if (ticksElapsed >= specCase.lifespan) {
            LOGGER.debug("[SpecRunner#onPhase] case '{}' finished after {} ticks", specCase.name, ticksElapsed)
            return SpecCaseResult(specCase.name, checks.toList())
        }
        val simTime = SimTime(ticksElapsed, phase)
        applyInputsAt(simTime)
        checkOutputsAt(simTime)
        checkBreakpointsAt(simTime)
        return null
    }

    private fun applyInputsAt(simTime: SimTime) {
        for (input in specCase.inputs) {
            val (_, condition) = input.entries.find { it.first == simTime } ?: continue
            val pos = worldPos(input.pos)
            LOGGER.debug("[SpecRunner#applyInputsAt] {} applying condition to {}", simTime, pos)
            applyCondition(condition, pos)
        }
    }

    private fun applyCondition(condition: StateCondition, pos: BlockPos) {
        var state = level.getBlockState(pos)
        val mods = mutableListOf<Pair<String, String>>()
        flattenToProperties(condition, mods)
        if (mods.isEmpty()) return
        for ((name, value) in mods) {
            val property = state.block.stateDefinition.getProperty(name) ?: continue
            @Suppress("UNCHECKED_CAST")
            state = applyProperty(state, property as Property<Comparable<Any>>, value)
        }
        level.setBlock(pos, state, 3)
    }

    private fun flattenToProperties(condition: StateCondition, out: MutableList<Pair<String, String>>) {
        when (condition) {
            is StateCondition.All -> condition.conditions.forEach { flattenToProperties(it, out) }
            is StateCondition.BoolProperty -> out += condition.name to condition.value.toString()
            is StateCondition.IntProperty -> out += condition.name to condition.value.toString()
            is StateCondition.EnumProperty -> out += condition.name to condition.value
            else -> { /* BlockType, Any, Not, ContainerContents: not applicable for input driving */ }
        }
    }

    private fun checkOutputsAt(simTime: SimTime) {
        for (output in specCase.outputs) {
            val (_, condition) = output.entries.find { it.first == simTime } ?: continue
            val pos = worldPos(output.pos)
            val state = level.getBlockState(pos)
            val label = output.label.ifEmpty { output.pos.toString() }
            collectChecks(condition, state, pos, simTime, label)
        }
    }

    private fun collectChecks(condition: StateCondition, state: BlockState, pos: BlockPos, simTime: SimTime, label: String) {
        when (condition) {
            is StateCondition.All -> condition.conditions.forEach { collectChecks(it, state, pos, simTime, label) }
            is StateCondition.BoolProperty -> {
                val prop = state.block.stateDefinition.getProperty(condition.name) as? BooleanProperty
                val actual = prop?.let { state.getValue(it).toString() } ?: "missing"
                val expected = condition.value.toString()
                val pass = actual == expected
                LOGGER.debug("[SpecRunner#collectChecks] {} '{}.{}' expected={} actual={} pass={}", simTime, label, condition.name, expected, actual, pass)
                checks += TickCheck(simTime, "$label.${condition.name}", expected, actual, pass)
            }
            is StateCondition.IntProperty -> {
                val prop = state.block.stateDefinition.getProperty(condition.name) as? IntegerProperty
                val actual = prop?.let { state.getValue(it).toString() } ?: "missing"
                val expected = condition.value.toString()
                val pass = actual == expected
                checks += TickCheck(simTime, "$label.${condition.name}", expected, actual, pass)
            }
            is StateCondition.EnumProperty -> {
                val actual = blockStatePropertyStr(state, condition.name) ?: "missing"
                val pass = actual == condition.value
                checks += TickCheck(simTime, "$label.${condition.name}", condition.value, actual, pass)
            }
            is StateCondition.BlockType -> {
                val actualId = BuiltInRegistries.BLOCK.getKey(state.block)?.location()?.toString() ?: "missing"
                val expected = condition.blockId.toString()
                val pass = actualId == expected
                checks += TickCheck(simTime, "$label.block", expected, actualId, pass)
            }
            else -> { /* Any, Not, ContainerContents: not supported for output checks */ }
        }
    }

    private fun checkBreakpointsAt(simTime: SimTime) {
        for (bp in specCase.breakpoints) {
            if (!bp.enabled) continue
            if (evaluateCondition(bp.condition, level, worldPos(bp.pos))) {
                LOGGER.debug("[SpecRunner#checkBreakpointsAt] breakpoint '{}' hit at {}", bp.label, simTime)
                frozenAt = simTime
                pendingBreakpointHit = BreakpointHit(simTime, spec.name, specCase.name, bp.label.ifEmpty { bp.pos.toString() })
                return
            }
        }
    }

    private fun <T : Comparable<T>> applyProperty(state: BlockState, property: Property<T>, valueStr: String): BlockState =
        property.getValue(valueStr).map { state.setValue(property, it) }.orElse(state)

    fun evaluateCondition(condition: StateCondition, worldPos: BlockPos): Boolean =
        com.breadmoirai.redstonespecs.runner.evaluateCondition(condition, level, worldPos)

    private fun worldPos(relPos: BlockPos) =
        BlockPos(originPos.x + relPos.x, originPos.y + relPos.y, originPos.z + relPos.z)
}
```

- [ ] **Step 5: Rewrite `AutoSpecRecorder.kt`**

Replace `buildStateSpec` (returns `StateSpec`) with `buildEntries` (returns `List<Pair<SimTime, StateCondition>>`).

```kotlin
package com.breadmoirai.redstonespecs.runner

import com.breadmoirai.redstonespecs.data.AutoSpec
import com.breadmoirai.redstonespecs.data.Phase
import com.breadmoirai.redstonespecs.data.SimTime
import com.breadmoirai.redstonespecs.data.SpecCase
import com.breadmoirai.redstonespecs.data.StateCondition
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import org.slf4j.LoggerFactory

private val LOGGER = LoggerFactory.getLogger("Redstone Specs")

class AutoSpecRecorder(
    private val autoSpec: AutoSpec,
    private val originPos: BlockPos,
    private val level: ServerLevel,
    private val specCase: SpecCase,
) {
    private var ticksElapsed = -1
    private val initStates = HashMap<BlockPos, Map<String, String>>()
    private val recordedChanges = mutableListOf<Pair<SimTime, Map<BlockPos, Map<String, String>>>>()
    private var lastStates: Map<BlockPos, Map<String, String>> = emptyMap()

    private val monitoredPositions: List<BlockPos> by lazy {
        (specCase.inputs + specCase.outputs).map { worldPos(it.pos) }
    }

    fun start() {
        LOGGER.debug("[AutoSpecRecorder#start] recording '{}' monitoring {} positions", autoSpec.label, monitoredPositions.size)
        ticksElapsed = -1
        val states = monitoredPositions.associateWith { pos -> captureBlockStateProps(level.getBlockState(pos)) }
        initStates.clear()
        initStates.putAll(states)
        lastStates = states
    }

    fun onPhase(phase: Phase) {
        if (phase == Phase.START_OF_TICK) ticksElapsed++
        val simTime = SimTime(ticksElapsed.coerceAtLeast(0), phase)
        val currentStates = monitoredPositions.associateWith { pos -> captureBlockStateProps(level.getBlockState(pos)) }
        val changes = currentStates.mapNotNull { (pos, current) ->
            val last = lastStates[pos] ?: emptyMap()
            val diff = current.filter { (k, v) -> last[k] != v }
            if (diff.isNotEmpty()) pos to diff else null
        }.toMap()
        if (changes.isNotEmpty()) {
            LOGGER.debug("[AutoSpecRecorder#onPhase] {} detected changes at {} positions", simTime, changes.size)
            recordedChanges += simTime to changes
        }
        lastStates = currentStates
    }

    fun commit(): SpecCase {
        fun buildEntries(worldPos: BlockPos): List<Pair<SimTime, StateCondition>> {
            val blockState = level.getBlockState(worldPos)
            val initProps = initStates[worldPos] ?: emptyMap()
            val result = mutableListOf<Pair<SimTime, StateCondition>>(
                SimTime.INIT to propsToCondition(initProps, blockState)
            )
            for ((simTime, changes) in recordedChanges) {
                changes[worldPos]?.let { diff ->
                    result += simTime to propsToCondition(diff, blockState)
                }
            }
            return result
        }

        val caseName = autoSpec.label.ifEmpty { "auto_${ticksElapsed + 1}t_${System.currentTimeMillis() % 10000}" }
        LOGGER.debug("[AutoSpecRecorder#commit] committing '{}' duration={}t changes={}", caseName, ticksElapsed + 1, recordedChanges.size)
        return SpecCase(
            name = caseName,
            lifespan = (ticksElapsed + 1).coerceAtLeast(1),
            inputs = specCase.inputs.map { it.copy(entries = buildEntries(worldPos(it.pos))) },
            outputs = specCase.outputs.map { it.copy(entries = buildEntries(worldPos(it.pos))) },
            breakpoints = specCase.breakpoints,
            autoSpecs = specCase.autoSpecs,
        )
    }

    private fun worldPos(relPos: BlockPos) =
        BlockPos(originPos.x + relPos.x, originPos.y + relPos.y, originPos.z + relPos.z)
}
```

- [ ] **Step 6: Update `SpecMarkerTool.kt` to build typed INIT entries**

Open `src/main/kotlin/com/breadmoirai/redstonespecs/item/SpecMarkerTool.kt`. Find where it calls `StateSpec(listOf(SimTime.INIT to initProps))` or creates an `InputSpec`/`OutputSpec` with a stateSpec. Replace with:

```kotlin
// Replace any occurrence of:
//   stateSpec = StateSpec(listOf(SimTime.INIT to initProps))
// with:
    entries = listOf(SimTime.INIT to propsToCondition(initProps, level.getBlockState(hitPos)))
```

Import `com.breadmoirai.redstonespecs.runner.propsToCondition` at the top of the file.

- [ ] **Step 7: Rewrite `SpecEntryTest.kt`**

```kotlin
package com.breadmoirai.redstonespecs.data

import net.minecraft.SharedConstants
import net.minecraft.core.BlockPos
import net.minecraft.nbt.NbtOps
import net.minecraft.server.Bootstrap
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SpecEntryTest {

    @BeforeAll
    fun bootstrap() {
        SharedConstants.tryDetectVersion()
        Bootstrap.bootStrap()
    }

    private val initEntries = listOf(SimTime.INIT to StateCondition.BoolProperty("powered", false))
    private val pos = BlockPos(1, 2, 3)
    private val color = 0xFF0000

    private fun <T> roundtrip(value: T, codec: com.mojang.serialization.Codec<T>): T {
        val encoded = codec.encodeStart(NbtOps.INSTANCE, value).getOrThrow()
        return codec.parse(NbtOps.INSTANCE, encoded).getOrThrow()
    }

    @Test
    fun `InputSpec roundtrip via SpecEntry codec`() {
        val entry: SpecEntry = InputSpec(pos, "A", color, initEntries)
        assertEquals(entry, roundtrip(entry, SpecEntry.CODEC))
    }

    @Test
    fun `OutputSpec roundtrip via SpecEntry codec`() {
        val entry: SpecEntry = OutputSpec(pos, "B", color, initEntries)
        assertEquals(entry, roundtrip(entry, SpecEntry.CODEC))
    }

    @Test
    fun `InputSpec with multiple entries roundtrip`() {
        val entries = listOf(
            SimTime.INIT to StateCondition.BoolProperty("powered", false),
            SimTime(0, Phase.END_OF_TICK) to StateCondition.All(listOf(
                StateCondition.BoolProperty("powered", true),
                StateCondition.IntProperty("power", 15),
            )),
        )
        val entry: SpecEntry = InputSpec(pos, "multi", color, entries)
        assertEquals(entry, roundtrip(entry, SpecEntry.CODEC))
    }

    @Test
    fun `BreakpointSpec roundtrip with defaults`() {
        val entry: SpecEntry = BreakpointSpec(pos, "BP", color)
        assertEquals(entry, roundtrip(entry, SpecEntry.CODEC))
    }

    @Test
    fun `BreakpointSpec roundtrip with typed condition and disabled`() {
        val entry: SpecEntry = BreakpointSpec(
            pos, "BP", color,
            condition = StateCondition.BoolProperty("lit", true),
            enabled = false,
        )
        assertEquals(entry, roundtrip(entry, SpecEntry.CODEC))
    }

    @Test
    fun `AutoSpec roundtrip with defaults`() {
        val entry: SpecEntry = AutoSpec(pos, "Auto", color)
        assertEquals(entry, roundtrip(entry, SpecEntry.CODEC))
    }

    @Test
    fun `AutoSpec roundtrip with custom condition`() {
        val entry: SpecEntry = AutoSpec(
            pos, "Auto", color,
            condition = StateCondition.ContainerContents(slot = 0, minCount = 3),
        )
        assertEquals(entry, roundtrip(entry, SpecEntry.CODEC))
    }

    @Test
    fun `negative relative positions roundtrip`() {
        val entry: SpecEntry = InputSpec(BlockPos(-5, -1, 10), "neg", color, initEntries)
        assertEquals(entry, roundtrip(entry, SpecEntry.CODEC))
    }

    @Test
    fun `InputSpec MAP_CODEC roundtrip directly`() {
        val entry = InputSpec(pos, "direct", color, initEntries)
        val encoded = InputSpec.MAP_CODEC.codec().encodeStart(NbtOps.INSTANCE, entry).getOrThrow()
        val decoded = InputSpec.MAP_CODEC.codec().parse(NbtOps.INSTANCE, encoded).getOrThrow()
        assertEquals(entry, decoded)
    }
}
```

- [ ] **Step 8: Run all tests**

```bash
cd /mnt/h/Repo/RedstoneSpecs && ./gradlew test 2>&1 | tail -40
```

Expected: All tests pass (StateConditionTest, SpecEntryTest, SpecCaseTest, RedstoneSpecTest, SpecMarkerToolTest, etc.)

- [ ] **Step 9: Commit**

```bash
git add -A
git commit -m "refactor: replace StateSpec with List<Pair<SimTime,StateCondition>> throughout"
```

---

## Task 3: `ModConfig` + `ModMenuIntegration`

**Files:**
- Create: `src/client/kotlin/com/breadmoirai/redstonespecs/client/config/ModConfig.kt`
- Create: `src/client/kotlin/com/breadmoirai/redstonespecs/client/config/ModMenuIntegration.kt`
- Modify: `src/main/resources/fabric.mod.json`

- [ ] **Step 1: Create `ModConfig.kt`**

```kotlin
package com.breadmoirai.redstonespecs.client.config

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.isxander.yacl3.api.ConfigCategory
import dev.isxander.yacl3.api.Option
import dev.isxander.yacl3.api.YetAnotherConfigLib
import dev.isxander.yacl3.gui.controllers.TickBoxController
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component

object ModConfig {
    private val configFile get() = FabricLoader.getInstance().configDir.resolve("redstonespecs.json").toFile()

    var autoSaveOnExit: Boolean = false

    fun load() {
        if (!configFile.exists()) return
        runCatching {
            val json = JsonParser.parseReader(configFile.reader()) as? JsonObject ?: return
            autoSaveOnExit = json.get("autoSaveOnExit")?.asBoolean ?: false
        }
    }

    fun save() {
        val json = JsonObject()
        json.addProperty("autoSaveOnExit", autoSaveOnExit)
        configFile.writeText(json.toString())
    }

    fun createScreen(parent: Screen): Screen = YetAnotherConfigLib.createBuilder()
        .title(Component.literal("RedstoneSpecs Config"))
        .category(ConfigCategory.createBuilder()
            .name(Component.literal("General"))
            .option(Option.createBuilder<Boolean>()
                .name(Component.literal("Auto-save on exit"))
                .description(dev.isxander.yacl3.api.OptionDescription.of(
                    Component.literal("Automatically save changes when closing SpecEditorScreen without pressing Save")
                ))
                .binding(false, { autoSaveOnExit }, { autoSaveOnExit = it })
                .controller(::TickBoxController)
                .build())
            .build())
        .save(::save)
        .build()
        .generateScreen(parent)
}
```

- [ ] **Step 2: Create `ModMenuIntegration.kt`**

```kotlin
package com.breadmoirai.redstonespecs.client.config

import com.terraformersmc.modmenu.api.ConfigScreenFactory
import com.terraformersmc.modmenu.api.ModMenuApi
import net.fabricmc.api.EnvType
import net.fabricmc.api.Environment

@Environment(EnvType.CLIENT)
class ModMenuIntegration : ModMenuApi {
    override fun getModConfigScreenFactory(): ConfigScreenFactory<*> =
        ConfigScreenFactory { parent -> ModConfig.createScreen(parent) }
}
```

- [ ] **Step 3: Add `modmenu` entrypoint to `fabric.mod.json`**

Open `src/main/resources/fabric.mod.json`. Add the `modmenu` entrypoint:

```json
{
  "entrypoints": {
    "main": ["com.breadmoirai.redstonespecs.Redstonespecs"],
    "client": ["com.breadmoirai.redstonespecs.client.RedstonespecsClient"],
    "modmenu": ["com.breadmoirai.redstonespecs.client.config.ModMenuIntegration"]
  }
}
```

- [ ] **Step 4: Call `ModConfig.load()` from `RedstonespecsClient.onInitializeClient()`**

Add to `RedstonespecsClient.kt`:

```kotlin
override fun onInitializeClient() {
    LOGGER.debug("[RedstonespecsClient#onInitializeClient] initializing client")
    ModConfig.load()
    registerBoundsRenderer()
    registerClientNetworking()
    registerHudOverlay()
    LOGGER.debug("[RedstonespecsClient#onInitializeClient] client initialization complete")
}
```

Also add the import: `import com.breadmoirai.redstonespecs.client.config.ModConfig`

- [ ] **Step 5: Build to verify compilation**

```bash
cd /mnt/h/Repo/RedstoneSpecs && ./gradlew compileKotlin compileClientKotlin 2>&1 | tail -20
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add src/client/kotlin/com/breadmoirai/redstonespecs/client/config/ \
        src/main/resources/fabric.mod.json \
        src/client/kotlin/com/breadmoirai/redstonespecs/client/RedstonespecsClient.kt
git commit -m "feat: add ModConfig with YACL screen and ModMenu integration"
```

---

## Task 4: `ColorPickerWidget`

**Files:**
- Create: `src/client/kotlin/com/breadmoirai/redstonespecs/client/widget/ColorPickerWidget.kt`

- [ ] **Step 1: Create `ColorPickerWidget.kt`**

```kotlin
package com.breadmoirai.redstonespecs.client.widget

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.narration.NarrationElementOutput
import net.minecraft.network.chat.Component

class ColorPickerWidget(
    x: Int, y: Int, width: Int, height: Int,
    initialColor: Int,
) : AbstractWidget(x, y, width, height, Component.empty()) {

    var color: Int = initialColor & 0xFFFFFF
        private set

    private var dropdownOpen = false
    private var hexBox: EditBox? = null

    private val dropW = 4 * SWATCH_SIZE + 2 * PAD
    private val dropH = 4 * SWATCH_SIZE + PAD + HEX_ROW_H + 2 * PAD

    companion object {
        private const val SWATCH_SIZE = 20
        private const val PAD = 4
        private const val HEX_ROW_H = 14

        val DYE_COLORS: List<Pair<String, Int>> = listOf(
            "White"       to 0xF9FFFE,
            "Orange"      to 0xF9801D,
            "Magenta"     to 0xC74EBD,
            "Light Blue"  to 0x3AB3DA,
            "Yellow"      to 0xFED83D,
            "Lime"        to 0x80C71F,
            "Pink"        to 0xF38BAA,
            "Gray"        to 0x474F52,
            "Light Gray"  to 0x9D9D97,
            "Cyan"        to 0x169C9C,
            "Purple"      to 0x8932B8,
            "Blue"        to 0x3C44AA,
            "Brown"       to 0x835432,
            "Green"       to 0x5E7C16,
            "Red"         to 0xB02E26,
            "Black"       to 0x1D1D21,
        )

        fun nearestDyeName(color: Int): String {
            val r = (color shr 16) and 0xFF
            val g = (color shr 8) and 0xFF
            val b = color and 0xFF
            return DYE_COLORS.minByOrNull { (_, c) ->
                val dr = r - ((c shr 16) and 0xFF)
                val dg = g - ((c shr 8) and 0xFF)
                val db = b - (c and 0xFF)
                dr * dr + dg * dg + db * db
            }?.first ?: "Custom"
        }
    }

    fun openDropdown(screen: net.minecraft.client.gui.screens.Screen) {
        dropdownOpen = true
        val font = Minecraft.getInstance().font
        hexBox = EditBox(font, x, y + height + dropH - HEX_ROW_H - PAD, dropW - SWATCH_SIZE - PAD, HEX_ROW_H, Component.empty()).also {
            it.value = String.format("%06X", color)
            it.setMaxLength(6)
            screen.addWidget(it)
        }
    }

    fun closeDropdown() {
        dropdownOpen = false
        hexBox = null
    }

    fun isDropdownOpen() = dropdownOpen

    /** Returns the dropdown panel bounding box [x, y, x+w, y+h] relative to screen. */
    fun dropdownBounds(): IntArray = intArrayOf(x, y + height, x + dropW, y + height + dropH)

    override fun renderWidget(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        // Button background
        graphics.fill(x, y, x + width, y + height, 0xFF333333.toInt())
        graphics.fill(x + 1, y + 1, x + 1 + 12, y + height - 1, color or 0xFF000000.toInt())
        val name = nearestDyeName(color)
        val hex = String.format("#%06X", color)
        graphics.drawString(Minecraft.getInstance().font, "$name  $hex", x + 16, y + (height - 8) / 2, 0xFFFFFFFF.toInt())

        if (dropdownOpen) renderDropdown(graphics, mouseX, mouseY)
    }

    private fun renderDropdown(graphics: GuiGraphics, mouseX: Int, mouseY: Int) {
        val dx = x
        val dy = y + height
        graphics.fill(dx, dy, dx + dropW, dy + dropH, 0xFF222222.toInt())

        // 4×4 swatch grid
        DYE_COLORS.forEachIndexed { i, (name, c) ->
            val col = i % 4
            val row = i / 4
            val sx = dx + PAD + col * SWATCH_SIZE
            val sy = dy + PAD + row * SWATCH_SIZE
            graphics.fill(sx, sy, sx + SWATCH_SIZE - 1, sy + SWATCH_SIZE - 1, c or 0xFF000000.toInt())
            if (mouseX in sx until sx + SWATCH_SIZE - 1 && mouseY in sy until sy + SWATCH_SIZE - 1) {
                graphics.fill(sx, sy, sx + SWATCH_SIZE - 1, sy + SWATCH_SIZE - 1, 0x44FFFFFF)
            }
        }

        // Hex preview swatch
        val hexY = dy + PAD + 4 * SWATCH_SIZE
        val hexInput = hexBox?.value ?: String.format("%06X", color)
        val previewColor = hexInput.toLongOrNull(16)?.toInt() ?: color
        graphics.fill(dx + dropW - SWATCH_SIZE, hexY, dx + dropW, hexY + HEX_ROW_H, previewColor or 0xFF000000.toInt())
    }

    override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        if (!dropdownOpen) {
            if (isHovered) {
                dropdownOpen = true
                return true
            }
            return false
        }

        // Click on swatch grid
        val dx = x; val dy = y + height
        DYE_COLORS.forEachIndexed { i, (_, c) ->
            val col = i % 4; val row = i / 4
            val sx = dx + PAD + col * SWATCH_SIZE
            val sy = dy + PAD + row * SWATCH_SIZE
            if (mouseX >= sx && mouseX < sx + SWATCH_SIZE && mouseY >= sy && mouseY < sy + SWATCH_SIZE) {
                color = c
                hexBox?.value = String.format("%06X", c)
                dropdownOpen = false
                hexBox = null
                return true
            }
        }

        // Outside dropdown: close
        val bounds = dropdownBounds()
        if (mouseX < bounds[0] || mouseX > bounds[2] || mouseY < bounds[1] || mouseY > bounds[3]) {
            applyHexInput()
            dropdownOpen = false
            hexBox = null
        }
        return super.mouseClicked(mouseX, mouseY, button)
    }

    private fun applyHexInput() {
        val hex = hexBox?.value ?: return
        val parsed = hex.toLongOrNull(16) ?: return
        color = parsed.toInt() and 0xFFFFFF
    }

    override fun updateWidgetNarration(output: NarrationElementOutput) {}
}
```

- [ ] **Step 2: Build to verify compilation**

```bash
cd /mnt/h/Repo/RedstoneSpecs && ./gradlew compileClientKotlin 2>&1 | tail -20
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add src/client/kotlin/com/breadmoirai/redstonespecs/client/widget/ColorPickerWidget.kt
git commit -m "feat: add ColorPickerWidget with 4x4 dye grid and hex input"
```

---

## Task 5: `BlockStateFormBuilder`

**Files:**
- Create: `src/client/kotlin/com/breadmoirai/redstonespecs/client/widget/BlockStateFormBuilder.kt`

- [ ] **Step 1: Create `BlockStateFormBuilder.kt`**

```kotlin
package com.breadmoirai.redstonespecs.client.widget

import com.breadmoirai.redstonespecs.data.StateCondition
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.CycleButton
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.screens.Screen
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.BooleanProperty
import net.minecraft.world.level.block.state.properties.IntegerProperty

private const val ROW_H = 18
private const val CHECK_W = 18
private const val LABEL_W = 80
private const val VALUE_X_OFFSET = CHECK_W + LABEL_W + 4

sealed class PropertyRow {
    abstract val name: String
    abstract var included: Boolean
    abstract fun currentCondition(): StateCondition
    /** Adds interactive widgets to the screen. Call from Screen.init(). */
    abstract fun addWidgetsTo(screen: Screen, font: Font, rowX: Int, rowY: Int)

    class BlockTypeRow(override val name: String = "block", val blockId: Identifier) : PropertyRow() {
        override var included: Boolean = false
        override fun currentCondition() = StateCondition.BlockType(blockId)
        override fun addWidgetsTo(screen: Screen, font: Font, rowX: Int, rowY: Int) {
            screen.addRenderableWidget(
                Button.builder(Component.literal(if (included) "✓" else " ")) {
                    included = !included
                    (screen as? net.minecraft.client.gui.screens.Screen)?.rebuildWidgets()
                }.bounds(rowX, rowY, CHECK_W, ROW_H).build()
            )
        }
    }

    class BoolRow(override val name: String, var value: Boolean) : PropertyRow() {
        override var included: Boolean = false
        override fun currentCondition() = StateCondition.BoolProperty(name, value)
        override fun addWidgetsTo(screen: Screen, font: Font, rowX: Int, rowY: Int) {
            screen.addRenderableWidget(
                Button.builder(Component.literal(if (included) "✓" else " ")) {
                    included = !included
                }.bounds(rowX, rowY, CHECK_W, ROW_H).build()
            )
            screen.addRenderableWidget(
                CycleButton.builder<Boolean>(
                    { v -> Component.literal(v.toString()) },
                    value,
                ).withValues(false, true)
                    .create(rowX + VALUE_X_OFFSET, rowY, 60, ROW_H, Component.empty()) { _, v -> value = v }
            )
        }
    }

    class IntRow(override val name: String, var value: Int, val min: Int, val max: Int) : PropertyRow() {
        override var included: Boolean = false
        override fun currentCondition() = StateCondition.IntProperty(name, value)
        private var editBox: EditBox? = null

        override fun addWidgetsTo(screen: Screen, font: Font, rowX: Int, rowY: Int) {
            screen.addRenderableWidget(
                Button.builder(Component.literal(if (included) "✓" else " ")) {
                    included = !included
                }.bounds(rowX, rowY, CHECK_W, ROW_H).build()
            )
            val vx = rowX + VALUE_X_OFFSET
            screen.addRenderableWidget(
                Button.builder(Component.literal("−")) {
                    value = (value - 1).coerceAtLeast(min)
                    editBox?.value = value.toString()
                }.bounds(vx, rowY, 14, ROW_H).build()
            )
            editBox = EditBox(font, vx + 16, rowY, 30, ROW_H, Component.empty()).also {
                it.value = value.toString()
                it.setMaxLength(4)
                screen.addRenderableWidget(it)
            }
            screen.addRenderableWidget(
                Button.builder(Component.literal("+")) {
                    value = (value + 1).coerceAtMost(max)
                    editBox?.value = value.toString()
                }.bounds(vx + 48, rowY, 14, ROW_H).build()
            )
        }

        fun syncFromEditBox() {
            value = editBox?.value?.toIntOrNull()?.coerceIn(min, max) ?: value
        }
    }

    class EnumRow(override val name: String, var value: String, val options: List<String>) : PropertyRow() {
        override var included: Boolean = false
        override fun currentCondition() = StateCondition.EnumProperty(name, value)

        override fun addWidgetsTo(screen: Screen, font: Font, rowX: Int, rowY: Int) {
            screen.addRenderableWidget(
                Button.builder(Component.literal(if (included) "✓" else " ")) {
                    included = !included
                }.bounds(rowX, rowY, CHECK_W, ROW_H).build()
            )
            screen.addRenderableWidget(
                CycleButton.builder<String>(
                    { v -> Component.literal(v) },
                    value,
                ).withValues(*options.toTypedArray())
                    .create(rowX + VALUE_X_OFFSET, rowY, 80, ROW_H, Component.empty()) { _, v -> value = v }
            )
        }
    }
}

object BlockStateFormBuilder {

    fun buildRows(state: BlockState, existingCondition: StateCondition?): List<PropertyRow> {
        val blockId = BuiltInRegistries.BLOCK.getKey(state.block)?.location()
            ?: Identifier.fromNamespaceAndPath("minecraft", "air")

        val rows = mutableListOf<PropertyRow>()

        // Always first: the block type row
        rows += PropertyRow.BlockTypeRow(blockId = blockId)

        // One row per block state property
        for (prop in state.block.stateDefinition.properties) {
            val currentValueStr = prop.getName(
                @Suppress("UNCHECKED_CAST")
                state.getValue(prop as net.minecraft.world.level.block.state.properties.Property<Comparable<Any>>)
            )
            rows += when (prop) {
                is BooleanProperty -> PropertyRow.BoolRow(prop.name, currentValueStr.toBoolean())
                is IntegerProperty -> PropertyRow.IntRow(
                    prop.name,
                    currentValueStr.toInt(),
                    prop.min,
                    prop.max,
                )
                else -> PropertyRow.EnumRow(
                    prop.name,
                    currentValueStr,
                    prop.possibleValues.map { v ->
                        @Suppress("UNCHECKED_CAST")
                        (prop as net.minecraft.world.level.block.state.properties.Property<Comparable<Any>>).getName(v as Comparable<Any>)
                    },
                )
            }
        }

        if (existingCondition != null) prePopulate(rows, existingCondition)
        return rows
    }

    private fun prePopulate(rows: List<PropertyRow>, condition: StateCondition) {
        when (condition) {
            is StateCondition.All -> condition.conditions.forEach { prePopulate(rows, it) }
            is StateCondition.BlockType -> rows.filterIsInstance<PropertyRow.BlockTypeRow>()
                .firstOrNull()?.apply { included = true }
            is StateCondition.BoolProperty -> rows.filterIsInstance<PropertyRow.BoolRow>()
                .find { it.name == condition.name }?.apply { included = true; value = condition.value }
            is StateCondition.IntProperty -> rows.filterIsInstance<PropertyRow.IntRow>()
                .find { it.name == condition.name }?.apply { included = true; value = condition.value }
            is StateCondition.EnumProperty -> rows.filterIsInstance<PropertyRow.EnumRow>()
                .find { it.name == condition.name }?.apply { included = true; value = condition.value }
            else -> { /* Any/Not/ContainerContents: leave rows unchecked */ }
        }
    }
}
```

- [ ] **Step 2: Build to verify compilation**

```bash
cd /mnt/h/Repo/RedstoneSpecs && ./gradlew compileClientKotlin 2>&1 | tail -20
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add src/client/kotlin/com/breadmoirai/redstonespecs/client/widget/BlockStateFormBuilder.kt
git commit -m "feat: add BlockStateFormBuilder with typed PropertyRow hierarchy"
```

---

## Task 6: `StateEntryEditorScreen`

**Files:**
- Create: `src/client/kotlin/com/breadmoirai/redstonespecs/client/screen/StateEntryEditorScreen.kt`

- [ ] **Step 1: Create `StateEntryEditorScreen.kt`**

```kotlin
package com.breadmoirai.redstonespecs.client.screen

import com.breadmoirai.redstonespecs.client.widget.BlockStateFormBuilder
import com.breadmoirai.redstonespecs.client.widget.PropertyRow
import com.breadmoirai.redstonespecs.data.Phase
import com.breadmoirai.redstonespecs.data.SimTime
import com.breadmoirai.redstonespecs.data.StateCondition
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.CycleButton
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.screens.Screen
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.CommonComponents
import net.minecraft.network.chat.Component

class StateEntryEditorScreen(
    private val originPos: BlockPos,
    private val entryRelPos: BlockPos,
    private val initial: Pair<SimTime, StateCondition>?,
    private val onConfirm: (SimTime, StateCondition) -> Unit,
) : Screen(Component.literal("Edit Entry")) {

    private val panelW = 300
    private val panelH = 240
    private val panelX get() = (width - panelW) / 2
    private val panelY get() = (height - panelH) / 2

    // Persists across rebuildWidgets()
    private var rows: List<PropertyRow>? = null
    private var currentTick: Int = initial?.first?.tick ?: 0
    private var currentPhase: Phase = initial?.first?.phase ?: Phase.END_OF_TICK
    private var hasComplexCondition: Boolean = false

    private var tickEditBox: EditBox? = null
    private var phaseButton: CycleButton<Phase>? = null

    companion object {
        private const val ROW_H = 18
        private const val ROWS_TOP_OFFSET = 70
    }

    override fun init() {
        super.init()
        val x = panelX; val y = panelY

        // Build rows once, persist state across init() calls
        if (rows == null) {
            val worldPos = BlockPos(
                originPos.x + entryRelPos.x,
                originPos.y + entryRelPos.y,
                originPos.z + entryRelPos.z,
            )
            val state = minecraft?.level?.getBlockState(worldPos) ?: return
            rows = BlockStateFormBuilder.buildRows(state, initial?.second)

            // Detect complex (non-leaf, non-All) conditions that can't be edited here
            hasComplexCondition = initial?.second?.let { cond ->
                cond is StateCondition.Any || cond is StateCondition.Not
            } ?: false
        }

        // Tick stepper
        tickEditBox = EditBox(font, x + 46, y + 30, 36, 16, Component.empty()).also {
            it.value = if (currentTick < 0) "" else currentTick.toString()
            it.setMaxLength(5)
            addRenderableWidget(it)
        }
        addRenderableWidget(
            Button.builder(Component.literal("−")) {
                currentTick = (currentTick - 1).coerceAtLeast(-1)
                tickEditBox?.value = if (currentTick < 0) "" else currentTick.toString()
            }.bounds(x + 28, y + 30, 16, 16).build()
        )
        addRenderableWidget(
            Button.builder(Component.literal("+")) {
                currentTick = (currentTick + 1).coerceAtLeast(0)
                tickEditBox?.value = currentTick.toString()
            }.bounds(x + 84, y + 30, 16, 16).build()
        )

        // Phase cycle button
        phaseButton = CycleButton.builder<Phase>(
            { phase -> Component.literal(phase.name) },
            currentPhase,
        ).withValues(*Phase.entries.toTypedArray())
            .create(x + 120, y + 30, 160, 16, Component.literal("Phase")) { _, v -> currentPhase = v }
            .also { addRenderableWidget(it) }

        // Property rows
        val currentRows = rows ?: return
        currentRows.forEachIndexed { i, row ->
            row.addWidgetsTo(this, font, x + 8, y + ROWS_TOP_OFFSET + i * ROW_H)
        }

        // Confirm button — enabled only when ≥1 row included
        addRenderableWidget(
            Button.builder(Component.literal("Confirm")) { confirm() }
                .bounds(x + panelW / 2 - 64, y + panelH - 22, 60, 18).build()
        )
        addRenderableWidget(
            Button.builder(CommonComponents.GUI_CANCEL) { onClose() }
                .bounds(x + panelW / 2 + 4, y + panelH - 22, 60, 18).build()
        )
    }

    private fun confirm() {
        // Sync int row edit boxes before reading
        rows?.filterIsInstance<PropertyRow.IntRow>()?.forEach { it.syncFromEditBox() }

        val tickText = tickEditBox?.value?.trim() ?: ""
        val tick = if (tickText.isEmpty()) -1 else tickText.toIntOrNull() ?: return
        currentTick = tick
        currentPhase = phaseButton?.getValue() ?: Phase.END_OF_TICK
        val simTime = if (tick < 0) SimTime.INIT else SimTime(tick, currentPhase)

        val included = rows?.filter { it.included } ?: return
        if (included.isEmpty()) return

        val condition = when (included.size) {
            1 -> included[0].currentCondition()
            else -> StateCondition.All(included.map { it.currentCondition() })
        }

        onConfirm(simTime, condition)
        onClose()
    }

    override fun extractRenderState(
        extractor: GuiGraphicsExtractor,
        mouseX: Int,
        mouseY: Int,
        partialTick: Float,
    ) {
        super.extractRenderState(extractor, mouseX, mouseY, partialTick)
        val x = panelX; val y = panelY

        extractor.centeredText(font, title, x + panelW / 2, y + 6, 0xFFFFFFFF.toInt())
        extractor.text(font, Component.literal("Tick:"), x + 8, y + 34, 0xFFAAAAAA.toInt())

        if (hasComplexCondition) {
            extractor.text(
                font,
                Component.literal("⚠ Complex condition (Any/Not) — properties shown unchecked"),
                x + 8, y + ROWS_TOP_OFFSET - 12, 0xFFFF8800.toInt(),
            )
        }

        // Row labels
        rows?.forEachIndexed { i, row ->
            val rowY = y + ROWS_TOP_OFFSET + i * ROW_H
            extractor.text(font, Component.literal(row.name), x + 26, rowY + 5, 0xFFCCCCCC.toInt())
        }
    }

    override fun isPauseScreen() = false
    override fun isInGameUi() = true
}
```

- [ ] **Step 2: Build to verify compilation**

```bash
cd /mnt/h/Repo/RedstoneSpecs && ./gradlew compileClientKotlin 2>&1 | tail -20
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add src/client/kotlin/com/breadmoirai/redstonespecs/client/screen/StateEntryEditorScreen.kt
git commit -m "feat: add StateEntryEditorScreen with tick/phase/condition editing"
```

---

## Task 7: Overhaul `SpecEditorScreen`

**Files:**
- Modify: `src/client/kotlin/com/breadmoirai/redstonespecs/client/screen/SpecEditorScreen.kt`

- [ ] **Step 1: Rewrite `SpecEditorScreen.kt`**

```kotlin
package com.breadmoirai.redstonespecs.client.screen

import com.breadmoirai.redstonespecs.block.SpecOriginBlockEntity
import com.breadmoirai.redstonespecs.client.config.ModConfig
import com.breadmoirai.redstonespecs.client.widget.ColorPickerWidget
import com.breadmoirai.redstonespecs.data.AutoSpec
import com.breadmoirai.redstonespecs.data.BreakpointSpec
import com.breadmoirai.redstonespecs.data.InputSpec
import com.breadmoirai.redstonespecs.data.OutputSpec
import com.breadmoirai.redstonespecs.data.Phase
import com.breadmoirai.redstonespecs.data.SimTime
import com.breadmoirai.redstonespecs.data.SpecEntry
import com.breadmoirai.redstonespecs.data.StateCondition
import com.breadmoirai.redstonespecs.network.RemoveSpecEntryC2SPayload
import com.breadmoirai.redstonespecs.network.SaveSpecEntryC2SPayload
import com.breadmoirai.redstonespecs.runner.captureBlockStateProps
import com.breadmoirai.redstonespecs.runner.propsToCondition
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.screens.ConfirmScreen
import net.minecraft.client.gui.screens.Screen
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.CommonComponents
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.common.custom.CustomPacketPayload

class SpecEditorScreen(
    private val originPos: BlockPos,
    private val entryRelPos: BlockPos,
) : Screen(Component.translatable("screen.redstonespecs.spec_editor")) {

    private val panelW = 320
    private val panelH = 230
    private val panelX get() = (width - panelW) / 2
    private val panelY get() = (height - panelH) / 2

    private var labelEditBox: EditBox? = null
    private var colorPicker: ColorPickerWidget? = null

    // Persists across rebuildWidgets(); null until entry available from server BE
    private var workingEntries: MutableList<Pair<SimTime, StateCondition>>? = null
    private var scrollOffset = 0

    companion object {
        private const val MAX_VISIBLE_ROWS = 5
        private const val ROW_H = 14
        private const val TABLE_TOP = 80
        private const val TABLE_COL_TICK = 8
        private const val TABLE_COL_PHASE = 50
        private const val TABLE_COL_STATE = 140
        private const val TABLE_COL_EDIT = -44
        private const val TABLE_COL_REMOVE = -22
    }

    override fun init() {
        super.init()
        val x = panelX; val y = panelY
        val entry = getEntry()

        if (workingEntries == null) {
            workingEntries = when (entry) {
                is InputSpec -> entry.entries.toMutableList()
                is OutputSpec -> entry.entries.toMutableList()
                else -> null
            }
        }

        labelEditBox = EditBox(font, x + 52, y + 26, 200, 16, Component.literal("Label")).also {
            it.value = entry?.label ?: ""
            addRenderableWidget(it)
        }

        colorPicker = ColorPickerWidget(x + 52, y + 46, 180, 16, entry?.color ?: 0xFFFFFF).also {
            addRenderableWidget(it)
        }

        val entries = workingEntries
        if (entries != null) {
            val visible = entries.drop(scrollOffset).take(MAX_VISIBLE_ROWS)
            visible.forEachIndexed { i, (simTime, condition) ->
                val absIdx = scrollOffset + i
                val rowY = y + TABLE_TOP + i * ROW_H

                // ✎ edit button
                addRenderableWidget(
                    Button.builder(Component.literal("✎")) {
                        openEntryEditor(absIdx)
                    }.bounds(x + panelW + TABLE_COL_EDIT, rowY, 18, 12).build()
                )
                // ✕ remove button
                addRenderableWidget(
                    Button.builder(Component.literal("✕")) {
                        entries.removeAt(absIdx)
                        if (scrollOffset > 0 && scrollOffset >= entries.size) scrollOffset--
                        rebuildWidgets()
                    }.bounds(x + panelW + TABLE_COL_REMOVE, rowY, 18, 12).build()
                )
            }

            // Scroll buttons if needed
            if (entries.size > MAX_VISIBLE_ROWS) {
                addRenderableWidget(
                    Button.builder(Component.literal("▲")) {
                        if (scrollOffset > 0) { scrollOffset--; rebuildWidgets() }
                    }.bounds(x + panelW - 20, y + TABLE_TOP - 14, 14, 12).build()
                )
                addRenderableWidget(
                    Button.builder(Component.literal("▼")) {
                        if (scrollOffset + MAX_VISIBLE_ROWS < entries.size) { scrollOffset++; rebuildWidgets() }
                    }.bounds(x + panelW - 20, y + TABLE_TOP + MAX_VISIBLE_ROWS * ROW_H, 14, 12).build()
                )
            }

            // + Add Entry
            addRenderableWidget(
                Button.builder(Component.literal("+ Add Entry")) {
                    openEntryEditor(null)
                }.bounds(x + 8, y + 168, 80, 14).build()
            )

            // Capture State
            addRenderableWidget(
                Button.builder(Component.literal("Capture State")) { captureState() }
                    .bounds(x + 96, y + 168, 90, 14).build()
            )
        }

        // Bottom buttons
        addRenderableWidget(
            Button.builder(Component.literal("Save")) { save() }
                .bounds(x + 8, y + panelH - 22, 60, 18).build()
        )
        addRenderableWidget(
            Button.builder(Component.literal("Remove")) { remove() }
                .bounds(x + 74, y + panelH - 22, 60, 18).build()
        )
        addRenderableWidget(
            Button.builder(CommonComponents.GUI_CANCEL) { onClose() }
                .bounds(x + panelW - 66, y + panelH - 22, 60, 18).build()
        )
    }

    private fun openEntryEditor(editIndex: Int?) {
        val entries = workingEntries
        val initial = if (editIndex != null) entries?.getOrNull(editIndex) else null
        minecraft?.setScreen(
            StateEntryEditorScreen(
                originPos = originPos,
                entryRelPos = entryRelPos,
                initial = initial,
                onConfirm = { simTime, condition ->
                    if (editIndex != null && entries != null) {
                        entries[editIndex] = simTime to condition
                    } else {
                        workingEntries?.add(simTime to condition)
                    }
                    minecraft?.setScreen(this)
                    rebuildWidgets()
                },
            )
        )
    }

    private fun captureState() {
        val mc = minecraft ?: return
        val level = mc.level ?: return
        val worldPos = BlockPos(
            originPos.x + entryRelPos.x,
            originPos.y + entryRelPos.y,
            originPos.z + entryRelPos.z,
        )
        val blockState = level.getBlockState(worldPos)
        val currentProps = captureBlockStateProps(blockState)
        val entries = workingEntries ?: return

        if (entries.isEmpty()) {
            // No entries: create full INIT entry
            entries.add(0, SimTime.INIT to propsToCondition(currentProps, blockState))
            rebuildWidgets()
            return
        }

        // Find last entry by SimTime order
        val lastEntry = entries.maxByOrNull { it.first }!!
        val lastKnown = flattenConditionToMap(lastEntry.second)

        // Diff: only properties that changed
        val diff = currentProps.filter { (k, v) -> lastKnown[k] != v }
        if (diff.isEmpty()) return

        val newTick = if (lastEntry.first == SimTime.INIT) 0 else lastEntry.first.tick + 1
        entries.add(SimTime(newTick, Phase.END_OF_TICK) to propsToCondition(diff, blockState))
        rebuildWidgets()
    }

    /** Flattens All(leaves) or single typed leaf to a Map<String,String> for diffing. */
    private fun flattenConditionToMap(condition: StateCondition): Map<String, String> {
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

    private fun isDirty(): Boolean {
        val entry = getEntry() ?: return false
        val currentLabel = labelEditBox?.value ?: ""
        val currentColor = colorPicker?.color ?: 0xFFFFFF
        if (currentLabel != entry.label || currentColor != entry.color) return true
        return when (entry) {
            is InputSpec -> workingEntries != entry.entries
            is OutputSpec -> workingEntries != entry.entries
            else -> false
        }
    }

    override fun onClose() {
        if (!isDirty()) {
            doClose()
            return
        }
        if (ModConfig.autoSaveOnExit) {
            save()
            return
        }
        minecraft?.setScreen(
            ConfirmScreen(
                { confirmed ->
                    if (confirmed) save() else doClose()
                },
                Component.literal("Unsaved Changes"),
                Component.literal("You have unsaved changes. Save before closing?"),
                Component.literal("Save"),
                Component.literal("Discard"),
            )
        )
    }

    private fun doClose() {
        workingEntries = null
        scrollOffset = 0
        super.onClose()
    }

    override fun tick() {
        super.tick()
        if (workingEntries == null) {
            when (val entry = getEntry()) {
                is InputSpec -> { workingEntries = entry.entries.toMutableList(); rebuildWidgets() }
                is OutputSpec -> { workingEntries = entry.entries.toMutableList(); rebuildWidgets() }
                else -> {}
            }
        }
    }

    private fun save() {
        val entry = getEntry() ?: run { doClose(); return }
        val specCaseIndex = getBe()?.activeSpecCaseIndex ?: 0
        val label = labelEditBox?.value ?: ""
        val color = colorPicker?.color ?: 0xFFFFFF

        val updated: SpecEntry = when (entry) {
            is InputSpec -> entry.copy(label = label, color = color,
                entries = workingEntries?.toList() ?: entry.entries)
            is OutputSpec -> entry.copy(label = label, color = color,
                entries = workingEntries?.toList() ?: entry.entries)
            is BreakpointSpec -> entry.copy(label = label, color = color)
            is AutoSpec -> entry.copy(label = label, color = color)
        }
        sendPacket(SaveSpecEntryC2SPayload(originPos, specCaseIndex, updated))
        doClose()
    }

    private fun remove() {
        val specCaseIndex = getBe()?.activeSpecCaseIndex ?: 0
        sendPacket(RemoveSpecEntryC2SPayload(originPos, specCaseIndex, entryRelPos))
        doClose()
    }

    override fun extractRenderState(
        extractor: GuiGraphicsExtractor,
        mouseX: Int,
        mouseY: Int,
        partialTick: Float,
    ) {
        val x = panelX; val y = panelY
        val entry = getEntry()
        super.extractRenderState(extractor, mouseX, mouseY, partialTick)

        val typeLabel = when (entry) {
            is InputSpec -> "Input"
            is OutputSpec -> "Output"
            is BreakpointSpec -> "Breakpoint"
            is AutoSpec -> "AutoSpec"
            null -> "Entry"
        }
        extractor.centeredText(font, Component.literal("$typeLabel @ $entryRelPos"), x + panelW / 2, y + 6, 0xFFFFFFFF.toInt())
        extractor.text(font, Component.literal("Label:"), x + 8, y + 29, 0xFFAAAAAA.toInt())
        extractor.text(font, Component.literal("Color:"), x + 8, y + 49, 0xFFAAAAAA.toInt())

        val entries = workingEntries
        if (entries != null) {
            extractor.text(font, Component.literal("State entries: ${entries.size}"), x + 8, y + 68, 0xFF888888.toInt())

            // Table column headers
            extractor.text(font, Component.literal("TICK"), x + TABLE_COL_TICK, y + TABLE_TOP - 10, 0xFF666666.toInt())
            extractor.text(font, Component.literal("PHASE"), x + TABLE_COL_PHASE, y + TABLE_TOP - 10, 0xFF666666.toInt())
            extractor.text(font, Component.literal("STATE"), x + TABLE_COL_STATE, y + TABLE_TOP - 10, 0xFF666666.toInt())

            val visible = entries.drop(scrollOffset).take(MAX_VISIBLE_ROWS)
            visible.forEachIndexed { i, (simTime, condition) ->
                val rowY = y + TABLE_TOP + i * ROW_H + 1
                val tickLabel = if (simTime == SimTime.INIT) "INIT" else "t${simTime.tick}"
                val phaseLabel = simTime.phase.name.take(9)
                val statePreview = previewCondition(condition).let {
                    if (it.length > 28) it.take(27) + "…" else it
                }
                extractor.text(font, Component.literal(tickLabel), x + TABLE_COL_TICK, rowY, 0xFFAAAAAA.toInt())
                extractor.text(font, Component.literal(phaseLabel), x + TABLE_COL_PHASE, rowY, 0xFF888888.toInt())
                extractor.text(font, Component.literal(statePreview), x + TABLE_COL_STATE, rowY, 0xFF888888.toInt())
            }

            if (entries.size > MAX_VISIBLE_ROWS) {
                val scrollInfo = "${scrollOffset + 1}–${(scrollOffset + MAX_VISIBLE_ROWS).coerceAtMost(entries.size)}/${entries.size}"
                extractor.text(font, Component.literal(scrollInfo), x + panelW - 60, y + TABLE_TOP - 10, 0xFF555555.toInt())
            }
        } else {
            when (entry) {
                is BreakpointSpec -> extractor.text(
                    font, Component.literal("Enabled: ${entry.enabled}"),
                    x + 8, y + 70, if (entry.enabled) 0xFF44FF88.toInt() else 0xFFFF4444.toInt(),
                )
                is AutoSpec -> extractor.text(
                    font, Component.literal("Trigger: ${entry.condition::class.simpleName}"),
                    x + 8, y + 70, 0xFFFFAA00.toInt(),
                )
                null -> extractor.centeredText(
                    font, Component.literal("Entry not found"), x + panelW / 2, y + 70, 0xFFFF4444.toInt(),
                )
                else -> {}
            }
        }
    }

    private fun previewCondition(condition: StateCondition): String = when (condition) {
        is StateCondition.BoolProperty -> "${condition.name}=${condition.value}"
        is StateCondition.IntProperty -> "${condition.name}=${condition.value}"
        is StateCondition.EnumProperty -> "${condition.name}=${condition.value}"
        is StateCondition.BlockType -> "block=${condition.blockId.path}"
        is StateCondition.All -> condition.conditions.joinToString(",") { previewCondition(it) }
        is StateCondition.Any -> condition.conditions.joinToString("|") { previewCondition(it) }
        is StateCondition.Not -> "!${previewCondition(condition.condition)}"
        is StateCondition.ContainerContents -> "container(...)"
    }

    override fun isPauseScreen() = false
    override fun isInGameUi() = true

    private fun getBe() = minecraft?.level?.getBlockEntity(originPos) as? SpecOriginBlockEntity
    private fun getEntry(): SpecEntry? {
        val be = getBe() ?: return null
        val spec = be.spec ?: return null
        return spec.specCases.getOrNull(be.activeSpecCaseIndex)?.entryAt(entryRelPos)
    }
    private fun sendPacket(p: CustomPacketPayload) = ClientPlayNetworking.send(p)
}
```

- [ ] **Step 2: Build to verify compilation**

```bash
cd /mnt/h/Repo/RedstoneSpecs && ./gradlew compileClientKotlin 2>&1 | tail -30
```

Expected: BUILD SUCCESSFUL. Fix any import or compilation errors before continuing.

- [ ] **Step 3: Run all tests**

```bash
cd /mnt/h/Repo/RedstoneSpecs && ./gradlew test 2>&1 | tail -40
```

Expected: All tests pass.

- [ ] **Step 4: Commit**

```bash
git add src/client/kotlin/com/breadmoirai/redstonespecs/client/screen/SpecEditorScreen.kt
git commit -m "feat: overhaul SpecEditorScreen with table, color picker, dirty guard, Capture State"
```

---

## Self-Review Checklist

- [x] **Spec § Data Model:** `BlockType`, `BoolProperty`, `IntProperty`, `EnumProperty` in Task 1. `StateSpec` deleted, `entries: List<Pair<SimTime, StateCondition>>` in Task 2.
- [x] **Spec § ConditionEvaluator:** Typed leaf cases added, `propsToCondition` utility added in Task 2 Step 3.
- [x] **Spec § AutoSpecRecorder:** `buildEntries` replaces `buildStateSpec` in Task 2 Step 5.
- [x] **Spec § SpecMarkerTool:** Updated to create typed INIT entry in Task 2 Step 6.
- [x] **Spec § ModConfig/ModMenuIntegration:** Task 3. `modmenu` entrypoint registered. Config loaded on client init.
- [x] **Spec § ColorPickerWidget:** 4×4 dye grid + hex input in Task 4.
- [x] **Spec § BlockStateFormBuilder:** `BlockTypeRow`, `BoolRow`, `IntRow`, `EnumRow` + pre-populate in Task 5.
- [x] **Spec § StateEntryEditorScreen:** Tick stepper, Phase CycleButton, PropertyRow widgets, Confirm builds single or All condition in Task 6.
- [x] **Spec § SpecEditorScreen entry table:** TICK|PHASE|STATE columns, ✎/✕ per row, scroll offset in Task 7.
- [x] **Spec § Unsaved changes guard:** `isDirty()`, `onClose()` → ConfirmScreen or auto-save in Task 7.
- [x] **Spec § Capture State button:** Diff logic with `flattenConditionToMap`, INIT fallback in Task 7.
- [x] **Spec § + Add Entry:** Opens `StateEntryEditorScreen` with null initial in Task 7.
- [x] **Type consistency:** `workingEntries: MutableList<Pair<SimTime, StateCondition>>` used consistently. `PropertyRow.IntRow.syncFromEditBox()` called in `StateEntryEditorScreen.confirm()`.
