# State Recording System Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Record all block state changes within a spec's bounds during spec runs and AutoSpec sessions, with sub-tick phase and intra-tick ordering, replacing the ad-hoc capture/check logic in `AutoSpecRecorder` and `SpecRunner`.

**Architecture:** A `LevelSetBlockMixin` intercepts every `Level.setBlock` call; when a `StateRecorder` is active and the position falls within the spec bounds, it appends a `BlockStateChange` (bounds-local pos, SimTime, property diffs) to the recorder. `SpecRunnerCoordinator` owns the single active `StateRecorder` and wires its lifecycle into spec runs and AutoSpec sessions. All consumers query via `StateRecordingView`, which reconstructs `BlockState` at any point by replaying diffs from the initial snapshot.

**Tech Stack:** Kotlin + Java (Mixin), Minecraft Fabric, Mojang Serialization Codecs, `NbtIo` for persistence, JUnit 5 + `fabric-loader-junit` for tests.

---

## File Map

| Status | File | Purpose |
|--------|------|---------|
| Create | `src/main/kotlin/com/breadmoirai/redstonespecs/runner/StateRecording.kt` | `PropertyDiff`, `BlockStateChange`, `StateRecording` data classes + NBT codec helpers |
| Create | `src/main/kotlin/com/breadmoirai/redstonespecs/runner/StateRecordingView.kt` | Query API over a recording or live recorder |
| Create | `src/main/kotlin/com/breadmoirai/redstonespecs/runner/StateRecorder.kt` | Active recorder: bounds check, coordinate conversion, change accumulation |
| Create | `src/main/kotlin/com/breadmoirai/redstonespecs/runner/StateRecordingStorage.kt` | NBT file persistence |
| Create | `src/main/java/com/breadmoirai/redstonespecs/mixin/LevelSetBlockMixin.java` | Intercepts `Level.setBlock` |
| Create | `src/test/kotlin/com/breadmoirai/redstonespecs/runner/StateRecordingViewTest.kt` | Unit tests for view query logic |
| Create | `src/test/kotlin/com/breadmoirai/redstonespecs/runner/StateRecordingStorageTest.kt` | NBT roundtrip test |
| Modify | `src/main/resources/redstonespecs.mixins.json` | Register `LevelSetBlockMixin` |
| Modify | `src/main/kotlin/com/breadmoirai/redstonespecs/runner/ConditionEvaluator.kt` | Add recording-based `evaluateCondition` overload |
| Modify | `src/main/kotlin/com/breadmoirai/redstonespecs/runner/SpecRunnerCoordinator.kt` | Wire recorder lifecycle |
| Modify | `src/main/kotlin/com/breadmoirai/redstonespecs/runner/AutoSpecRecorder.kt` | Replace manual diff tracking with view queries |
| Modify | `src/main/kotlin/com/breadmoirai/redstonespecs/runner/SpecRunner.kt` | Use view for output checks |

---

## Task 1: Data Classes — `PropertyDiff`, `BlockStateChange`, `StateRecording`

**Files:**
- Create: `src/main/kotlin/com/breadmoirai/redstonespecs/runner/StateRecording.kt`

These are pure data classes. No Minecraft registry access required for construction, but NBT codec roundtrip tests need Bootstrap.

- [ ] **Step 1: Create `StateRecording.kt` with data classes**

```kotlin
package com.breadmoirai.redstonespecs.runner

import net.minecraft.core.BlockPos
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.Tag
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.level.block.state.BlockState
import com.breadmoirai.redstonespecs.data.Phase
import com.breadmoirai.redstonespecs.data.SimTime
import java.util.UUID

data class PropertyDiff(val name: String, val to: String)

data class BlockStateChange(
    val pos: BlockPos,               // bounds-local: (0,0,0) = bounds min corner
    val simTime: SimTime,
    val toBlock: ResourceLocation?,  // null if block type unchanged
    val diffs: List<PropertyDiff>,
)

data class StateRecording(
    val specId: UUID,
    val timestamp: Long,
    val initialSnapshot: Map<BlockPos, BlockState>, // keyed by bounds-local pos
    val changes: List<BlockStateChange>,            // ordered by simTime
)

// NBT helpers — kept here so codec logic is co-located with the data

fun StateRecording.toNbt(): CompoundTag {
    val tag = CompoundTag()
    tag.putString("specId", specId.toString())
    tag.putLong("timestamp", timestamp)

    val snapshotList = ListTag()
    for ((pos, state) in initialSnapshot) {
        val entry = CompoundTag()
        entry.putIntArray("pos", intArrayOf(pos.x, pos.y, pos.z))
        entry.putString("state", blockStateToString(state))
        snapshotList.add(entry)
    }
    tag.put("initialSnapshot", snapshotList)

    val changesList = ListTag()
    for (change in changes) {
        val c = CompoundTag()
        c.putIntArray("pos", intArrayOf(change.pos.x, change.pos.y, change.pos.z))
        c.putInt("tick", change.simTime.tick)
        c.putString("phase", change.simTime.phase.name)
        c.putInt("order", change.simTime.order)
        change.toBlock?.let { c.putString("toBlock", it.toString()) }
        val diffsList = ListTag()
        for (diff in change.diffs) {
            val d = CompoundTag()
            d.putString("name", diff.name)
            d.putString("to", diff.to)
            diffsList.add(d)
        }
        c.put("diffs", diffsList)
        changesList.add(c)
    }
    tag.put("changes", changesList)
    return tag
}

fun stateRecordingFromNbt(tag: CompoundTag): StateRecording {
    val specId = UUID.fromString(tag.getString("specId"))
    val timestamp = tag.getLong("timestamp")

    val snapshotList = tag.getList("initialSnapshot", Tag.TAG_COMPOUND.toInt())
    val initialSnapshot = buildMap {
        for (i in 0 until snapshotList.size) {
            val entry = snapshotList.getCompound(i)
            val arr = entry.getIntArray("pos")
            val pos = BlockPos(arr[0], arr[1], arr[2])
            put(pos, blockStateFromString(entry.getString("state")))
        }
    }

    val changesList = tag.getList("changes", Tag.TAG_COMPOUND.toInt())
    val changes = buildList {
        for (i in 0 until changesList.size) {
            val c = changesList.getCompound(i)
            val arr = c.getIntArray("pos")
            val pos = BlockPos(arr[0], arr[1], arr[2])
            val simTime = SimTime(
                c.getInt("tick"),
                Phase.valueOf(c.getString("phase")),
                c.getInt("order"),
            )
            val toBlock = if (c.contains("toBlock")) ResourceLocation.parse(c.getString("toBlock")) else null
            val diffsList = c.getList("diffs", Tag.TAG_COMPOUND.toInt())
            val diffs = buildList {
                for (j in 0 until diffsList.size) {
                    val d = diffsList.getCompound(j)
                    add(PropertyDiff(d.getString("name"), d.getString("to")))
                }
            }
            add(BlockStateChange(pos, simTime, toBlock, diffs))
        }
    }
    return StateRecording(specId, timestamp, initialSnapshot, changes)
}
```

- [ ] **Step 2: Add `blockStateToString` / `blockStateFromString` helpers at the bottom of the file**

These helpers serialize a `BlockState` to its string form (`minecraft:lever[face=wall,facing=east,powered=false]`) and back. This matches the NBT schema in the spec.

```kotlin
private fun blockStateToString(state: BlockState): String {
    val block = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.block).toString()
    if (state.block.stateDefinition.properties.isEmpty()) return block
    val props = captureBlockStateProps(state).entries.joinToString(",") { "${it.key}=${it.value}" }
    return "$block[$props]"
}

private fun blockStateFromString(str: String): BlockState {
    val bracketIdx = str.indexOf('[')
    val blockId = if (bracketIdx == -1) str else str.substring(0, bracketIdx)
    val block = net.minecraft.core.registries.BuiltInRegistries.BLOCK
        .getValue(ResourceLocation.parse(blockId))
    if (bracketIdx == -1) return block.defaultBlockState()
    val propsStr = str.substring(bracketIdx + 1, str.length - 1)
    var state = block.defaultBlockState()
    for (part in propsStr.split(",")) {
        val (name, value) = part.split("=")
        val property = state.block.stateDefinition.getProperty(name) ?: continue
        @Suppress("UNCHECKED_CAST")
        state = applyPropertyFromString(state, property as net.minecraft.world.level.block.state.properties.Property<Comparable<Any>>, value)
    }
    return state
}

private fun <T : Comparable<T>> applyPropertyFromString(
    state: BlockState,
    property: net.minecraft.world.level.block.state.properties.Property<T>,
    value: String,
): BlockState = property.getValue(value).map { state.setValue(property, it) }.orElse(state)
```

- [ ] **Step 3: Run tests to confirm compilation**

```bash
./gradlew compileKotlin
```

Expected: BUILD SUCCESSFUL with no errors.

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/com/breadmoirai/redstonespecs/runner/StateRecording.kt
git commit -m "feat: add StateRecording data classes and NBT codec helpers"
```

---

## Task 2: `StateRecordingView` — Query API

**Files:**
- Create: `src/main/kotlin/com/breadmoirai/redstonespecs/runner/StateRecordingView.kt`
- Create: `src/test/kotlin/com/breadmoirai/redstonespecs/runner/StateRecordingViewTest.kt`

The view reconstructs `BlockState` at any `(pos, simTime)` by replaying diffs from the initial snapshot forward. It can wrap either a finalized `StateRecording` or the live `changes` list from a `StateRecorder` (via factory methods). The `changes` list must be ordered by `simTime` ascending — guaranteed by the recorder's sequential append.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.breadmoirai.redstonespecs.runner

import com.breadmoirai.redstonespecs.data.Phase
import com.breadmoirai.redstonespecs.data.SimTime
import net.minecraft.SharedConstants
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.Bootstrap
import net.minecraft.world.level.block.LeverBlock
import net.minecraft.world.level.block.state.properties.AttachFace
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StateRecordingViewTest {

    @BeforeAll
    fun bootstrap() {
        SharedConstants.tryDetectVersion()
        Bootstrap.bootStrap()
    }

    private fun leverBlock() = BuiltInRegistries.BLOCK.getValue(ResourceLocation.parse("minecraft:lever"))
    private fun leverState(powered: Boolean) =
        leverBlock().defaultBlockState().setValue(LeverBlock.POWERED, powered)

    @Test
    fun `stateAt returns initial snapshot when no changes`() {
        val pos = BlockPos(0, 0, 0)
        val initial = leverState(false)
        val view = StateRecordingView(mapOf(pos to initial), emptyList())
        assertEquals(initial, view.stateAt(pos, SimTime(0, Phase.END_OF_TICK)))
    }

    @Test
    fun `stateAt applies diff at exact simTime`() {
        val pos = BlockPos(0, 0, 0)
        val t = SimTime(0, Phase.SCHEDULED_TICKS, 0)
        val change = BlockStateChange(pos, t, null, listOf(PropertyDiff("powered", "true")))
        val view = StateRecordingView(mapOf(pos to leverState(false)), listOf(change))
        assertEquals(leverState(true), view.stateAt(pos, t))
    }

    @Test
    fun `stateAt does not apply future change`() {
        val pos = BlockPos(0, 0, 0)
        val t = SimTime(0, Phase.SCHEDULED_TICKS, 0)
        val change = BlockStateChange(pos, t, null, listOf(PropertyDiff("powered", "true")))
        val view = StateRecordingView(mapOf(pos to leverState(false)), listOf(change))
        val before = SimTime(0, Phase.BLOCK_EVENTS, 0)
        assertEquals(leverState(false), view.stateAt(pos, before))
    }

    @Test
    fun `changesAt filters by position`() {
        val p1 = BlockPos(0, 0, 0)
        val p2 = BlockPos(1, 0, 0)
        val t = SimTime(0, Phase.SCHEDULED_TICKS, 0)
        val c1 = BlockStateChange(p1, t, null, listOf(PropertyDiff("powered", "true")))
        val c2 = BlockStateChange(p2, t, null, listOf(PropertyDiff("powered", "true")))
        val view = StateRecordingView(
            mapOf(p1 to leverState(false), p2 to leverState(false)),
            listOf(c1, c2),
        )
        assertEquals(listOf(c1), view.changesAt(p1))
        assertEquals(listOf(c2), view.changesAt(p2))
    }

    @Test
    fun `changesInPhase filters by tick and phase`() {
        val pos = BlockPos(0, 0, 0)
        val t1 = SimTime(0, Phase.SCHEDULED_TICKS, 0)
        val t2 = SimTime(0, Phase.END_OF_TICK, 0)
        val c1 = BlockStateChange(pos, t1, null, listOf(PropertyDiff("powered", "true")))
        val c2 = BlockStateChange(pos, t2, null, listOf(PropertyDiff("powered", "false")))
        val view = StateRecordingView(mapOf(pos to leverState(false)), listOf(c1, c2))
        assertEquals(listOf(c1), view.changesInPhase(0, Phase.SCHEDULED_TICKS))
        assertEquals(listOf(c2), view.changesInPhase(0, Phase.END_OF_TICK))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
./gradlew test --tests "com.breadmoirai.redstonespecs.runner.StateRecordingViewTest"
```

Expected: FAIL — `StateRecordingView` not found.

- [ ] **Step 3: Create `StateRecordingView.kt`**

```kotlin
package com.breadmoirai.redstonespecs.runner

import com.breadmoirai.redstonespecs.data.Phase
import com.breadmoirai.redstonespecs.data.SimTime
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.Property

class StateRecordingView(
    val initialSnapshot: Map<BlockPos, BlockState>,
    private val changes: List<BlockStateChange>,
) {
    fun stateAt(pos: BlockPos, simTime: SimTime): BlockState {
        var state = initialSnapshot[pos] ?: error("Position $pos not in recording bounds")
        for (change in changes) {
            if (change.pos != pos) continue
            if (change.simTime > simTime) break
            if (change.toBlock != null) {
                state = BuiltInRegistries.BLOCK.getValue(change.toBlock).defaultBlockState()
            }
            for (diff in change.diffs) {
                val property = state.block.stateDefinition.getProperty(diff.name) ?: continue
                @Suppress("UNCHECKED_CAST")
                state = applyPropertyFromString(state, property as Property<Comparable<Any>>, diff.to)
            }
        }
        return state
    }

    fun changesAt(pos: BlockPos): List<BlockStateChange> =
        changes.filter { it.pos == pos }

    fun changesInPhase(tick: Int, phase: Phase): List<BlockStateChange> =
        changes.filter { it.simTime.tick == tick && it.simTime.phase == phase }

    fun changesAt(pos: BlockPos, tick: Int, phase: Phase): List<BlockStateChange> =
        changes.filter { it.pos == pos && it.simTime.tick == tick && it.simTime.phase == phase }

    companion object {
        fun of(recording: StateRecording) =
            StateRecordingView(recording.initialSnapshot, recording.changes)
        // Live view backed by a recorder's mutable list — safe to read during an active run
        fun of(recorder: StateRecorder) =
            StateRecordingView(recorder.initialSnapshot, recorder.changes)
    }
}

private fun <T : Comparable<T>> applyPropertyFromString(
    state: BlockState,
    property: Property<T>,
    value: String,
): BlockState = property.getValue(value).map { state.setValue(property, it) }.orElse(state)
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
./gradlew test --tests "com.breadmoirai.redstonespecs.runner.StateRecordingViewTest"
```

Expected: PASS — all 5 tests green.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/breadmoirai/redstonespecs/runner/StateRecordingView.kt \
        src/test/kotlin/com/breadmoirai/redstonespecs/runner/StateRecordingViewTest.kt
git commit -m "feat: add StateRecordingView with query API and unit tests"
```

---

## Task 3: `StateRecorder` — Active Recorder

**Files:**
- Create: `src/main/kotlin/com/breadmoirai/redstonespecs/runner/StateRecorder.kt`

The recorder owns the global active instance (via `@JvmStatic` companion), the tick counter, current phase, the initial snapshot, and the mutable change list. The `LevelSetBlockMixin` calls `StateRecorder.getActive()` from Java.

- [ ] **Step 1: Create `StateRecorder.kt`**

```kotlin
package com.breadmoirai.redstonespecs.runner

import com.breadmoirai.redstonespecs.data.Phase
import com.breadmoirai.redstonespecs.data.SimTime
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.levelgen.structure.BoundingBox
import java.util.UUID

class StateRecorder(
    val specId: UUID,
    private val boundsWorldMin: BlockPos,
    private val boundsWorldMax: BlockPos,
) {
    private var currentTick: Int = -1
    private var tickOrder: Int = 0
    var currentPhase: Phase = Phase.START_OF_TICK
        private set

    lateinit var initialSnapshot: Map<BlockPos, BlockState>
        private set

    val changes: MutableList<BlockStateChange> = mutableListOf()

    fun start(level: ServerLevel, originPos: BlockPos, bounds: BoundingBox) {
        initialSnapshot = buildMap {
            for (x in bounds.minX()..bounds.maxX()) {
                for (y in bounds.minY()..bounds.maxY()) {
                    for (z in bounds.minZ()..bounds.maxZ()) {
                        val worldPos = BlockPos(originPos.x + x, originPos.y + y, originPos.z + z)
                        val localPos = worldToLocal(worldPos)
                        put(localPos, level.getBlockState(worldPos))
                    }
                }
            }
        }
    }

    fun onTickStart() {
        currentTick++
        tickOrder = 0
    }

    fun onPhaseStart(phase: Phase) {
        currentPhase = phase
    }

    fun isInBounds(worldPos: BlockPos): Boolean =
        worldPos.x in boundsWorldMin.x..boundsWorldMax.x &&
        worldPos.y in boundsWorldMin.y..boundsWorldMax.y &&
        worldPos.z in boundsWorldMin.z..boundsWorldMax.z

    fun worldToLocal(worldPos: BlockPos): BlockPos = BlockPos(
        worldPos.x - boundsWorldMin.x,
        worldPos.y - boundsWorldMin.y,
        worldPos.z - boundsWorldMin.z,
    )

    fun record(worldPos: BlockPos, from: BlockState, to: BlockState) {
        val localPos = worldToLocal(worldPos)
        val toBlock: ResourceLocation? = if (from.block != to.block)
            BuiltInRegistries.BLOCK.getKey(to.block) else null
        val fromProps = captureBlockStateProps(from)
        val toProps = captureBlockStateProps(to)
        val diffs = toProps.mapNotNull { (name, value) ->
            if (fromProps[name] != value) PropertyDiff(name, value) else null
        }
        if (diffs.isEmpty() && toBlock == null) return
        val simTime = SimTime(currentTick.coerceAtLeast(0), currentPhase, tickOrder++)
        changes += BlockStateChange(localPos, simTime, toBlock, diffs)
    }

    fun toRecording(): StateRecording =
        StateRecording(specId, System.currentTimeMillis(), initialSnapshot, changes.toList())

    companion object {
        @JvmStatic
        var active: StateRecorder? = null
            private set

        @JvmStatic
        fun activate(recorder: StateRecorder) {
            active = recorder
        }

        @JvmStatic
        fun deactivate() {
            active = null
        }

        fun forSpec(specId: UUID, originPos: BlockPos, bounds: BoundingBox): StateRecorder {
            val minX = originPos.x + bounds.minX()
            val minY = originPos.y + bounds.minY()
            val minZ = originPos.z + bounds.minZ()
            val maxX = originPos.x + bounds.maxX()
            val maxY = originPos.y + bounds.maxY()
            val maxZ = originPos.z + bounds.maxZ()
            return StateRecorder(specId, BlockPos(minX, minY, minZ), BlockPos(maxX, maxY, maxZ))
        }
    }
}
```

- [ ] **Step 2: Run compile check**

```bash
./gradlew compileKotlin
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/com/breadmoirai/redstonespecs/runner/StateRecorder.kt
git commit -m "feat: add StateRecorder with bounds-local coordinate tracking"
```

---

## Task 4: `StateRecordingStorage` — NBT Persistence

**Files:**
- Create: `src/main/kotlin/com/breadmoirai/redstonespecs/runner/StateRecordingStorage.kt`
- Create: `src/test/kotlin/com/breadmoirai/redstonespecs/runner/StateRecordingStorageTest.kt`

Files written to `<world>/data/redstonespecs/<specUUID>.dat` via `NbtIo`. Overwrites any prior recording for the same spec.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.breadmoirai.redstonespecs.runner

import com.breadmoirai.redstonespecs.data.Phase
import com.breadmoirai.redstonespecs.data.SimTime
import net.minecraft.SharedConstants
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.nbt.NbtIo
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.Bootstrap
import net.minecraft.world.level.block.LeverBlock
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.UUID

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StateRecordingStorageTest {

    @BeforeAll
    fun bootstrap() {
        SharedConstants.tryDetectVersion()
        Bootstrap.bootStrap()
    }

    @Test
    fun `recording NBT roundtrip`(@TempDir dir: Path) {
        val lever = BuiltInRegistries.BLOCK.getValue(ResourceLocation.parse("minecraft:lever"))
        val unpowered = lever.defaultBlockState().setValue(LeverBlock.POWERED, false)
        val powered = lever.defaultBlockState().setValue(LeverBlock.POWERED, true)
        val pos = BlockPos(0, 0, 0)
        val specId = UUID.randomUUID()
        val recording = StateRecording(
            specId = specId,
            timestamp = 12345L,
            initialSnapshot = mapOf(pos to unpowered),
            changes = listOf(
                BlockStateChange(
                    pos = pos,
                    simTime = SimTime(0, Phase.SCHEDULED_TICKS, 0),
                    toBlock = null,
                    diffs = listOf(PropertyDiff("powered", "true")),
                )
            ),
        )
        val file = dir.resolve("$specId.dat").toFile()
        NbtIo.write(recording.toNbt(), file.toPath())
        val loaded = stateRecordingFromNbt(NbtIo.read(file.toPath()))
        assertEquals(recording.specId, loaded.specId)
        assertEquals(recording.timestamp, loaded.timestamp)
        assertEquals(recording.changes, loaded.changes)
        assertEquals(powered, StateRecordingView.of(loaded).stateAt(pos, SimTime(0, Phase.END_OF_TICK)))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
./gradlew test --tests "com.breadmoirai.redstonespecs.runner.StateRecordingStorageTest"
```

Expected: FAIL — `StateRecordingStorage` not found.

- [ ] **Step 3: Create `StateRecordingStorage.kt`**

```kotlin
package com.breadmoirai.redstonespecs.runner

import net.minecraft.nbt.NbtIo
import net.minecraft.server.level.ServerLevel
import java.util.UUID

object StateRecordingStorage {
    fun save(level: ServerLevel, recording: StateRecording) {
        val file = fileFor(level, recording.specId)
        file.parentFile.mkdirs()
        NbtIo.write(recording.toNbt(), file.toPath())
    }

    fun load(level: ServerLevel, specId: UUID): StateRecording? {
        val file = fileFor(level, specId)
        if (!file.exists()) return null
        return stateRecordingFromNbt(NbtIo.read(file.toPath()))
    }

    fun delete(level: ServerLevel, specId: UUID) {
        fileFor(level, specId).delete()
    }

    private fun fileFor(level: ServerLevel, specId: UUID) =
        level.server.storageSource.levelPath
            .resolve("data/redstonespecs/$specId.dat")
            .toFile()
}
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
./gradlew test --tests "com.breadmoirai.redstonespecs.runner.StateRecordingStorageTest"
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/breadmoirai/redstonespecs/runner/StateRecordingStorage.kt \
        src/test/kotlin/com/breadmoirai/redstonespecs/runner/StateRecordingStorageTest.kt
git commit -m "feat: add StateRecordingStorage with NBT persistence and roundtrip test"
```

---

## Task 5: `LevelSetBlockMixin` — Intercept Block State Changes

**Files:**
- Create: `src/main/java/com/breadmoirai/redstonespecs/mixin/LevelSetBlockMixin.java`
- Modify: `src/main/resources/redstonespecs.mixins.json`

Intercepts `Level.setBlock(BlockPos, BlockState, int)`. Uses a `ThreadLocal<Deque<BlockState>>` stack to capture the before-state at HEAD and compare at RETURN — the stack handles recursive `setBlock` calls (neighbor updates triggering further updates). The bounds check comes first to skip inactive recorders quickly.

The 3-arg `setBlock` delegates to the 4-arg internally; we hook only the 3-arg to avoid double-counting.

- [ ] **Step 1: Create `LevelSetBlockMixin.java`**

```java
package com.breadmoirai.redstonespecs.mixin;

import com.breadmoirai.redstonespecs.runner.StateRecorder;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayDeque;
import java.util.Deque;

@Mixin(Level.class)
abstract class LevelSetBlockMixin {

    // Stack to handle recursive setBlock calls (neighbor updates triggering further updates).
    private static final ThreadLocal<Deque<BlockState>> BEFORE_STATE_STACK =
        ThreadLocal.withInitial(ArrayDeque::new);

    @Inject(
        method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Z",
        at = @At("HEAD")
    )
    private void redstonespecs$captureBeforeState(
        BlockPos pos, BlockState state, int flags,
        CallbackInfoReturnable<Boolean> cir
    ) {
        StateRecorder recorder = StateRecorder.getActive();
        if (recorder == null || !recorder.isInBounds(pos)) {
            BEFORE_STATE_STACK.get().push(null); // null sentinel — skip at RETURN
            return;
        }
        BEFORE_STATE_STACK.get().push(((Level) (Object) this).getBlockState(pos));
    }

    @Inject(
        method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Z",
        at = @At("RETURN")
    )
    private void redstonespecs$recordChange(
        BlockPos pos, BlockState newState, int flags,
        CallbackInfoReturnable<Boolean> cir
    ) {
        Deque<BlockState> stack = BEFORE_STATE_STACK.get();
        BlockState from = stack.isEmpty() ? null : stack.pop();
        if (from == null) return; // out of bounds or no recorder — sentinel consumed
        if (!cir.getReturnValue()) return; // block did not actually change
        StateRecorder recorder = StateRecorder.getActive();
        if (recorder == null) return; // recorder deactivated between HEAD and RETURN (edge case)
        recorder.record(pos, from, newState);
    }
}
```

- [ ] **Step 2: Register the mixin in `redstonespecs.mixins.json`**

```json
{
  "required": true,
  "minVersion": "0.8",
  "package": "com.breadmoirai.redstonespecs.mixin",
  "compatibilityLevel": "JAVA_25",
  "mixins": [
    "LevelSetBlockMixin",
    "ServerLevelPhaseMixin"
  ],
  "injectors": {
    "defaultRequire": 1
  },
  "overwrites": {
    "requireAnnotations": true
  }
}
```

- [ ] **Step 3: Compile to confirm no errors**

```bash
./gradlew compileJava compileKotlin
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/breadmoirai/redstonespecs/mixin/LevelSetBlockMixin.java \
        src/main/resources/redstonespecs.mixins.json
git commit -m "feat: add LevelSetBlockMixin to intercept Level.setBlock for state recording"
```

---

## Task 6: Wire `StateRecorder` into `SpecRunnerCoordinator`

**Files:**
- Modify: `src/main/kotlin/com/breadmoirai/redstonespecs/runner/SpecRunnerCoordinator.kt`

The coordinator creates and activates a `StateRecorder` on `startRun` and `monitorAutoSpecs` (when an AutoSpec activates), drives `onTickStart`/`onPhaseStart` on every phase event, and finalizes/persists the recording on `finishRun` and `AutoSpecRecorder.commit`.

- [ ] **Step 1: Add recorder fields and activation to `startRun`**

Add a field `private val stateRecorders = HashMap<SpecOriginBlockEntity, StateRecorder>()` alongside the other maps, then modify `startRun`:

```kotlin
fun startRun(be: SpecOriginBlockEntity, runAll: Boolean) {
    if (runners.containsKey(be)) return
    val spec = be.spec ?: return
    val level = be.level as? ServerLevel ?: return

    val caseIndices = if (runAll) spec.specCases.indices.toList()
                      else listOf(be.activeSpecCaseIndex)

    LOGGER.debug("[SpecRunnerCoordinator#startRun] starting '{}' runAll={} cases={}", spec.name, runAll, caseIndices)
    snapshots[be] = SpecSnapshot.capture(level, be.blockPos, spec.bounds)
    results[be] = mutableListOf()
    queues[be] = ArrayDeque(caseIndices)

    val recorder = StateRecorder.forSpec(spec.id, be.blockPos, spec.bounds)
    recorder.start(level, be.blockPos, spec.bounds)
    stateRecorders[be] = recorder
    StateRecorder.activate(recorder)

    startNextCase(be)
}
```

- [ ] **Step 2: Drive the recorder in `onPhase`**

Replace `onPhase`:

```kotlin
fun onPhase(level: ServerLevel, phase: Phase) {
    val recorder = StateRecorder.active
    if (recorder != null) {
        if (phase == Phase.START_OF_TICK) recorder.onTickStart()
        recorder.onPhaseStart(phase)
    }
    tickRunners(level, phase)
    monitorAutoSpecs(level, phase)
}
```

- [ ] **Step 3: Finalize and persist recording in `finishRun`**

Add at the top of `finishRun`, before the existing logic:

```kotlin
private fun finishRun(be: SpecOriginBlockEntity) {
    val recorder = stateRecorders.remove(be)
    StateRecorder.deactivate()
    val level = be.level as? ServerLevel ?: return
    if (recorder != null) {
        val recording = recorder.toRecording()
        StateRecordingStorage.save(level, recording)
    }

    val spec = be.spec ?: return
    // ... rest of existing finishRun unchanged ...
}
```

- [ ] **Step 4: Wire AutoSpec recorder activation in `monitorAutoSpecs`**

Replace the `existing == null && isActive` branch:

```kotlin
existing == null && isActive -> {
    LOGGER.debug("[SpecRunnerCoordinator#monitorAutoSpecs] autoSpec '{}' activated at {}", autoSpec.label, autoSpec.pos)
    val recorder = AutoSpecRecorder(autoSpec, be.blockPos, level, specCase)

    // Activate a StateRecorder for this AutoSpec session
    val spec = be.spec ?: return
    val stateRecorder = StateRecorder.forSpec(spec.id, be.blockPos, spec.bounds)
    stateRecorder.start(level, be.blockPos, spec.bounds)
    stateRecorders[be] = stateRecorder
    StateRecorder.activate(stateRecorder)

    recorder.start()
    autoSpecRecorders[key] = recorder
}
```

Replace the `existing != null && !isActive` branch:

```kotlin
existing != null && !isActive -> {
    autoSpecRecorders.remove(key)

    // Finalize and hand recording to AutoSpecRecorder.commit
    val stateRecorder = stateRecorders.remove(be)
    StateRecorder.deactivate()
    val view = stateRecorder?.let { StateRecordingView.of(it) }

    val boundsWorldMin = be.spec?.bounds?.let { bounds ->
        BlockPos(be.blockPos.x + bounds.minX(), be.blockPos.y + bounds.minY(), be.blockPos.z + bounds.minZ())
    }

    val newCase = if (view != null && boundsWorldMin != null)
        existing.commit(view, boundsWorldMin)
    else
        existing.commit(null, null)

    if (stateRecorder != null) {
        StateRecordingStorage.save(level, stateRecorder.toRecording())
    }

    LOGGER.debug("[SpecRunnerCoordinator#monitorAutoSpecs] autoSpec '{}' committed as case '{}'", autoSpec.label, newCase.name)
    be.addOrUpdateSpecCase(newCase)
    PlayerLookup.level(level).forEach { player ->
        ServerPlayNetworking.send(player, AutoSpecRecordedS2CPayload(be.blockPos, newCase.name))
    }
}
```

Also add `stateRecorders.remove(be)` in `resetSpec` to clean up on manual reset:

```kotlin
fun resetSpec(be: SpecOriginBlockEntity) {
    LOGGER.debug("[SpecRunnerCoordinator#resetSpec] resetting spec at {}", be.blockPos)
    runners.remove(be)
    queues.remove(be)
    val snapshot = snapshots.remove(be)
    results.remove(be)
    if (stateRecorders.remove(be) != null) StateRecorder.deactivate()
    val level = be.level as? ServerLevel ?: return
    snapshot?.restore(level)
}
```

- [ ] **Step 5: Build to confirm no compile errors**

```bash
./gradlew compileKotlin
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/breadmoirai/redstonespecs/runner/SpecRunnerCoordinator.kt
git commit -m "feat: wire StateRecorder lifecycle into SpecRunnerCoordinator"
```

---

## Task 7: Refactor `AutoSpecRecorder` — Use `StateRecordingView`

**Files:**
- Modify: `src/main/kotlin/com/breadmoirai/redstonespecs/runner/AutoSpecRecorder.kt`

Remove all manual snapshot/diff tracking. `commit` now accepts the finalized `StateRecordingView` and `boundsWorldMin` from the coordinator, and queries it to build `SpecCase` entries.

- [ ] **Step 1: Replace `AutoSpecRecorder.kt` with the recording-based version**

```kotlin
package com.breadmoirai.redstonespecs.runner

import com.breadmoirai.redstonespecs.config.DevLevel
import com.breadmoirai.redstonespecs.config.SharedSettings
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

    fun start() {
        LOGGER.debug("[AutoSpecRecorder#start] recording '{}' monitoring {} positions",
            autoSpec.label, (specCase.inputs + specCase.outputs).size)
        ticksElapsed = -1
    }

    fun onPhase(phase: Phase) {
        if (phase == Phase.START_OF_TICK) ticksElapsed++
    }

    /** Called by SpecRunnerCoordinator with the finalized view and the world position of the bounds min corner. */
    fun commit(view: StateRecordingView?, boundsWorldMin: BlockPos?): SpecCase {
        val standardMode = SharedSettings.devLevel == DevLevel.STANDARD

        fun buildEntries(worldPos: BlockPos, isInput: Boolean): List<Pair<SimTime, StateCondition>> {
            if (view == null || boundsWorldMin == null) return emptyList()
            val localPos = BlockPos(
                worldPos.x - boundsWorldMin.x,
                worldPos.y - boundsWorldMin.y,
                worldPos.z - boundsWorldMin.z,
            )
            val initBlockState = view.initialSnapshot[localPos] ?: level.getBlockState(worldPos)
            val initProps = captureBlockStateProps(initBlockState)
            val result = mutableListOf<Pair<SimTime, StateCondition>>(
                SimTime.INIT to propsToCondition(initProps, initBlockState)
            )
            for (change in view.changesAt(localPos)) {
                val stateAtChange = view.stateAt(localPos, change.simTime)
                val effectiveTime = if (standardMode && !isInput)
                    change.simTime.copy(phase = Phase.END_OF_TICK) else change.simTime
                val diffMap = change.diffs.associate { it.name to it.to }
                result += effectiveTime to propsToCondition(diffMap, stateAtChange)
            }
            return result
        }

        val caseName = autoSpec.label.ifEmpty { "auto_${ticksElapsed + 1}t_${System.currentTimeMillis() % 10000}" }
        LOGGER.debug("[AutoSpecRecorder#commit] committing '{}' duration={}t", caseName, ticksElapsed + 1)
        return SpecCase(
            name = caseName,
            lifespan = (ticksElapsed + 1).coerceAtLeast(1),
            inputs = specCase.inputs.map { it.copy(entries = buildEntries(worldPos(it.pos), isInput = true)) },
            outputs = specCase.outputs.map { it.copy(entries = buildEntries(worldPos(it.pos), isInput = false)) },
            breakpoints = specCase.breakpoints,
            autoSpecs = specCase.autoSpecs,
        )
    }

    private fun worldPos(relPos: BlockPos) =
        BlockPos(originPos.x + relPos.x, originPos.y + relPos.y, originPos.z + relPos.z)
}
```

- [ ] **Step 2: Build**

```bash
./gradlew compileKotlin
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/com/breadmoirai/redstonespecs/runner/AutoSpecRecorder.kt
git commit -m "refactor: AutoSpecRecorder uses StateRecordingView instead of manual diff tracking"
```

---

## Task 8: Refactor `SpecRunner` + `ConditionEvaluator` — Use View for Output Checks

**Files:**
- Modify: `src/main/kotlin/com/breadmoirai/redstonespecs/runner/SpecRunner.kt`
- Modify: `src/main/kotlin/com/breadmoirai/redstonespecs/runner/ConditionEvaluator.kt`

`SpecRunner.checkOutputsAt` queries `StateRecordingView.stateAt` instead of `level.getBlockState`. The view is a live view (backed by the recorder's mutable list) constructed once and passed to the runner at construction. `ConditionEvaluator` gains a recording-based overload.

- [ ] **Step 1: Add recording-based `evaluateCondition` to `ConditionEvaluator.kt`**

Append to the bottom of `src/main/kotlin/com/breadmoirai/redstonespecs/runner/ConditionEvaluator.kt`:

```kotlin
fun evaluateCondition(
    condition: StateCondition,
    view: StateRecordingView,
    localPos: BlockPos,
    simTime: SimTime,
): Boolean {
    val state = view.stateAt(localPos, simTime)
    return evaluateConditionOnState(condition, state, null, localPos)
}
```

Note: `evaluateConditionOnState` currently takes a `Level` for `ContainerContents` — pass `null` as a safe no-op. Update the private function signature to accept `Level?`:

```kotlin
private fun evaluateConditionOnState(
    condition: StateCondition,
    state: BlockState,
    level: Level?,
    worldPos: BlockPos,
): Boolean = when (condition) {
    // ... all existing branches unchanged, except ContainerContents:
    is StateCondition.ContainerContents -> false  // no-op for recording context
}
```

Update the existing public `evaluateCondition(condition, level, worldPos)` to call the updated private function (passes `level` as `Level?` — Kotlin accepts `Level` where `Level?` is expected):

```kotlin
fun evaluateCondition(condition: StateCondition, level: Level, worldPos: BlockPos): Boolean {
    val state = level.getBlockState(worldPos)
    return evaluateConditionOnState(condition, state, level, worldPos)
}
```

- [ ] **Step 2: Add `view` and `boundsWorldMin` to `SpecRunner` constructor, update `checkOutputsAt`**

Change the constructor signature and add a `worldToLocal` helper:

```kotlin
class SpecRunner(
    val spec: RedstoneSpec,
    val specCase: SpecCase,
    val originPos: BlockPos,
    val level: ServerLevel,
    private val snapshot: SpecSnapshot,
    private val view: StateRecordingView,
    private val boundsWorldMin: BlockPos,
) {
    // ... existing fields unchanged ...
```

Replace `checkOutputsAt`:

```kotlin
private fun checkOutputsAt(simTime: SimTime) {
    val userInteractionTime = if (simTime.phase == Phase.END_OF_TICK)
        simTime.copy(phase = Phase.USER_INTERACTION) else null
    for (output in specCase.outputs) {
        val (_, condition) = output.entries.find {
            it.first == simTime || (userInteractionTime != null && it.first == userInteractionTime)
        } ?: continue
        val worldPos = worldPos(output.pos)
        val localPos = worldToLocal(worldPos)
        val state = view.stateAt(localPos, simTime)
        val label = output.label.ifEmpty { output.pos.toString() }
        collectChecks(condition, state, worldPos, simTime, label)
    }
}

private fun worldToLocal(worldPos: BlockPos) = BlockPos(
    worldPos.x - boundsWorldMin.x,
    worldPos.y - boundsWorldMin.y,
    worldPos.z - boundsWorldMin.z,
)
```

- [ ] **Step 3: Update `SpecRunnerCoordinator.startNextCase` to pass view and boundsWorldMin to SpecRunner**

In `startNextCase`, replace the runner construction:

```kotlin
val spec = be.spec ?: run { finishRun(be); return }
val specCase = spec.specCases.getOrNull(caseIndex) ?: run { startNextCase(be); return }
val level = be.level as? ServerLevel ?: return
val snapshot = snapshots[be] ?: return
val stateRecorder = stateRecorders[be]
val boundsWorldMin = BlockPos(
    be.blockPos.x + spec.bounds.minX(),
    be.blockPos.y + spec.bounds.minY(),
    be.blockPos.z + spec.bounds.minZ(),
)
val view = stateRecorder?.let { StateRecordingView.of(it) }
    ?: StateRecordingView(emptyMap(), emptyList())

LOGGER.debug("[SpecRunnerCoordinator#startNextCase] starting case '{}' (index={}) remaining={}", specCase.name, caseIndex, queue.size)
snapshot.restore(level)
val runner = SpecRunner(spec, specCase, be.blockPos, level, snapshot, view, boundsWorldMin)
runner.start()
runners[be] = runner
```

- [ ] **Step 4: Build**

```bash
./gradlew compileKotlin compileJava
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Run all unit tests**

```bash
./gradlew test
```

Expected: All existing tests pass plus the new view and storage tests.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/breadmoirai/redstonespecs/runner/SpecRunner.kt \
        src/main/kotlin/com/breadmoirai/redstonespecs/runner/ConditionEvaluator.kt \
        src/main/kotlin/com/breadmoirai/redstonespecs/runner/SpecRunnerCoordinator.kt
git commit -m "refactor: SpecRunner checks outputs via StateRecordingView instead of live world state"
```

---

## Self-Review Checklist

After all tasks are complete, run:

```bash
./gradlew test
./gradlew build
```

Both must succeed before declaring the work done.
