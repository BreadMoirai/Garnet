# Workflow Redesign v1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove `SpecCase`, add `SpecMode` + string `id` + `lifespan` + `structure` to `RedstoneSpec`, add JSON/NBT persistence, overhaul `SpecOverviewScreen` with entry list and load/save buttons.

**Architecture:** Data model is rewritten first (SpecMode enum, RedstoneSpec fields), then server-side consumers (BlockEntity, Runner, NetworkRegistry), then persistence layer (JSON + NBT), then UI. Each task ends in a compilable state. The SpecCase abstraction is deleted entirely; each spec block is one test execution.

**Tech Stack:** Kotlin, Fabric MC, Mojang Serialization (Codec), Mojang DataFixerUpper, YACL3, JUnit 5, Gson (already present via Fabric), Minecraft StructureTemplate for NBT structure I/O.

**Spec:** `docs/superpowers/specs/2026-04-22-workflow-redesign-v1-design.md`

---

### Task 1: `SpecMode` enum

**Files:**
- Create: `src/main/kotlin/com/breadmoirai/redstonespecs/data/SpecMode.kt`

- [ ] **Step 1: Write the file**

```kotlin
package com.breadmoirai.redstonespecs.data

import com.mojang.serialization.Codec
import com.mojang.serialization.DataResult

enum class SpecMode {
    SIMPLE, TICK_AWARE, UPDATE_AWARE;

    companion object {
        val CODEC: Codec<SpecMode> = Codec.STRING.comapFlatMap(
            { str ->
                entries.find { it.name == str }
                    ?.let { DataResult.success(it) }
                    ?: DataResult.error { "Unknown SpecMode: $str" }
            },
            SpecMode::name,
        )
    }
}
```

- [ ] **Step 2: Verify it compiles**

```bash
./gradlew compileKotlin
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/com/breadmoirai/redstonespecs/data/SpecMode.kt
git commit -m "feat: add SpecMode enum with CODEC"
```

---

### Task 2: Rewrite `RedstoneSpec` data class

This task rewrites the central data class and immediately updates `RedstoneSpecBlockEntity` to keep the build compilable. The old `SpecCase` is no longer referenced from `RedstoneSpec`.

**Files:**
- Modify: `src/main/kotlin/com/breadmoirai/redstonespecs/data/RedstoneSpec.kt`
- Modify: `src/main/kotlin/com/breadmoirai/redstonespecs/block/RedstoneSpecBlockEntity.kt`
- Modify: `src/main/kotlin/com/breadmoirai/redstonespecs/data/SpecEntry.kt` (drop OutputSpec INIT constraint)

- [ ] **Step 1: Rewrite `RedstoneSpec.kt`**

```kotlin
package com.breadmoirai.redstonespecs.data

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.core.BlockPos
import net.minecraft.world.level.levelgen.structure.BoundingBox
import java.util.Optional

data class RedstoneSpec(
    val id: String,
    val mode: SpecMode,
    val bounds: BoundingBox,
    val lifespan: Int,
    val structure: String?,
    val inputs: List<InputSpec>,
    val outputs: List<OutputSpec>,
    val breakpoints: List<BreakpointSpec>,
    val autoSpecs: List<AutoSpec>,
) {
    val allEntries: List<SpecEntry> get() = inputs + outputs + breakpoints + autoSpecs

    fun entryAt(pos: BlockPos): SpecEntry? = allEntries.find { it.pos == pos }

    fun withEntryAddedOrUpdated(entry: SpecEntry): RedstoneSpec = when (entry) {
        is InputSpec -> copy(inputs = inputs.filter { it.pos != entry.pos } + entry)
        is OutputSpec -> copy(outputs = outputs.filter { it.pos != entry.pos } + entry)
        is BreakpointSpec -> copy(breakpoints = breakpoints.filter { it.pos != entry.pos } + entry)
        is AutoSpec -> copy(autoSpecs = autoSpecs.filter { it.pos != entry.pos } + entry)
    }

    fun withEntryRemoved(pos: BlockPos): RedstoneSpec = copy(
        inputs = inputs.filter { it.pos != pos },
        outputs = outputs.filter { it.pos != pos },
        breakpoints = breakpoints.filter { it.pos != pos },
        autoSpecs = autoSpecs.filter { it.pos != pos },
    )

    companion object {
        val DEFAULT_BOUNDS = BoundingBox(1, 0, 1, 5, 4, 5)

        fun new(id: String) = RedstoneSpec(
            id, SpecMode.SIMPLE, DEFAULT_BOUNDS, 20, null,
            emptyList(), emptyList(), emptyList(), emptyList(),
        )

        val CODEC: Codec<RedstoneSpec> = RecordCodecBuilder.create { instance ->
            instance.group(
                Codec.STRING.fieldOf("id").forGetter(RedstoneSpec::id),
                SpecMode.CODEC.optionalFieldOf("mode", SpecMode.SIMPLE).forGetter(RedstoneSpec::mode),
                BoundingBox.CODEC.fieldOf("bounds").forGetter(RedstoneSpec::bounds),
                Codec.INT.optionalFieldOf("lifespan", 20).forGetter(RedstoneSpec::lifespan),
                Codec.STRING.optionalFieldOf("structure").forGetter { Optional.ofNullable(it.structure) },
                InputSpec.MAP_CODEC.codec().listOf().optionalFieldOf("inputs", emptyList())
                    .forGetter(RedstoneSpec::inputs),
                OutputSpec.MAP_CODEC.codec().listOf().optionalFieldOf("outputs", emptyList())
                    .forGetter(RedstoneSpec::outputs),
                BreakpointSpec.MAP_CODEC.codec().listOf().optionalFieldOf("breakpoints", emptyList())
                    .forGetter(RedstoneSpec::breakpoints),
                AutoSpec.MAP_CODEC.codec().listOf().optionalFieldOf("auto_specs", emptyList())
                    .forGetter(RedstoneSpec::autoSpecs),
            ).apply(instance) { id, mode, bounds, lifespan, structure, inputs, outputs, breakpoints, autoSpecs ->
                RedstoneSpec(id, mode, bounds, lifespan, structure.orElse(null),
                    inputs, outputs, breakpoints, autoSpecs)
            }
        }
    }
}
```

- [ ] **Step 2: Drop `OutputSpec` INIT constraint in `SpecEntry.kt`**

In `SpecEntry.kt`, delete the `init { require(...) }` block from `OutputSpec` only. `InputSpec` keeps its `init` block.

Find and delete these lines in `OutputSpec`:
```kotlin
    init {
        require(entries.count { it.first == SimTime.INIT } == 1) {
            "OutputSpec entries must contain exactly one INIT entry, got: ${entries.map { it.first }}"
        }
    }
```

- [ ] **Step 3: Rewrite `RedstoneSpecBlockEntity.kt`**

Replace the entire file:

```kotlin
package com.breadmoirai.redstonespecs.block

import com.breadmoirai.redstonespecs.ModRegistries
import com.breadmoirai.redstonespecs.data.RedstoneSpec
import com.breadmoirai.redstonespecs.data.SpecEntry
import com.breadmoirai.redstonespecs.data.SpecMode
import com.breadmoirai.redstonespecs.data.TestResult
import net.minecraft.core.BlockPos
import net.minecraft.core.HolderLookup
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientGamePacketListener
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.storage.ValueInput
import net.minecraft.world.level.storage.ValueOutput
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

class RedstoneSpecBlockEntity(pos: BlockPos, state: BlockState) :
    BlockEntity(ModRegistries.REDSTONE_SPEC_BLOCK_ENTITY_TYPE, pos, state) {

    var spec: RedstoneSpec? = null
        private set

    var lastTestResult: TestResult? = null
        private set

    fun setSpec(newSpec: RedstoneSpec) {
        LOGGER.debug("[RedstoneSpecBlockEntity#setSpec] setting spec '{}' at {}", newSpec.id, blockPos)
        spec = newSpec
        setChangedAndSync()
    }

    fun setSpecId(id: String) {
        spec = spec?.copy(id = id) ?: return
        setChangedAndSync()
    }

    fun setMode(mode: SpecMode) {
        spec = spec?.copy(mode = mode) ?: return
        setChangedAndSync()
    }

    fun setLifespan(lifespan: Int) {
        spec = spec?.copy(lifespan = lifespan) ?: return
        setChangedAndSync()
    }

    fun setStructure(structure: String?) {
        spec = spec?.copy(structure = structure) ?: return
        setChangedAndSync()
    }

    fun setLastTestResult(result: TestResult) {
        lastTestResult = result
        setChangedAndSync()
    }

    fun addOrUpdateEntry(entry: SpecEntry) {
        LOGGER.debug("[RedstoneSpecBlockEntity#addOrUpdateEntry] pos={} type={}", entry.pos, entry.javaClass.simpleName)
        spec = spec?.withEntryAddedOrUpdated(entry) ?: return
        setChangedAndSync()
    }

    fun removeEntry(pos: BlockPos): SpecEntry? {
        LOGGER.debug("[RedstoneSpecBlockEntity#removeEntry] pos={}", pos)
        val s = spec ?: return null
        val removed = s.entryAt(pos) ?: return null
        spec = s.withEntryRemoved(pos)
        setChangedAndSync()
        return removed
    }

    override fun setLevel(level: Level) {
        super.setLevel(level)
        register(this)
    }

    override fun setRemoved() {
        super.setRemoved()
        level?.let { registry[it]?.remove(blockPos) }
    }

    private fun setChangedAndSync() {
        setChanged()
        level?.sendBlockUpdated(blockPos, blockState, blockState, 3)
    }

    companion object {
        private val LOGGER = LoggerFactory.getLogger("Redstone Specs")
        private val registry = ConcurrentHashMap<Level, ConcurrentHashMap<BlockPos, RedstoneSpecBlockEntity>>()

        private fun register(be: RedstoneSpecBlockEntity) {
            val level = be.level ?: return
            registry.getOrPut(level, ::ConcurrentHashMap)[be.blockPos] = be
        }

        fun findFor(level: Level, worldPos: BlockPos): RedstoneSpecBlockEntity? =
            registry[level]?.values?.find { be ->
                val s = be.spec ?: return@find false
                val b = s.bounds
                val o = be.blockPos
                worldPos.x in (o.x + b.minX())..(o.x + b.maxX()) &&
                worldPos.y in (o.y + b.minY())..(o.y + b.maxY()) &&
                worldPos.z in (o.z + b.minZ())..(o.z + b.maxZ())
            }

        fun allFor(level: Level): Collection<RedstoneSpecBlockEntity> =
            registry[level]?.values ?: emptyList()
    }

    override fun saveAdditional(output: ValueOutput) {
        LOGGER.debug("[RedstoneSpecBlockEntity#saveAdditional] saving at {}", blockPos)
        super.saveAdditional(output)
        spec?.let { output.store("spec", RedstoneSpec.CODEC, it) }
        lastTestResult?.let { output.store("last_test_result", TestResult.CODEC, it) }
    }

    override fun loadAdditional(input: ValueInput) {
        super.loadAdditional(input)
        spec = input.read("spec", RedstoneSpec.CODEC).orElse(null)
        lastTestResult = input.read("last_test_result", TestResult.CODEC).orElse(null)
        LOGGER.debug("[RedstoneSpecBlockEntity#loadAdditional] loaded at {} spec='{}'", blockPos, spec?.id)
    }

    override fun getUpdateTag(registries: HolderLookup.Provider): CompoundTag =
        saveWithoutMetadata(registries)

    override fun getUpdatePacket(): Packet<ClientGamePacketListener> =
        ClientboundBlockEntityDataPacket.create(this)
}
```

- [ ] **Step 4: Fix remaining compile errors from SpecCase removal**

Files that reference `spec.specCases`, `spec.name`, `be.activeSpecCaseIndex`, etc. now fail. Fix each:

**`src/main/kotlin/com/breadmoirai/redstonespecs/block/RedstoneSpecBlock.kt`** — any reference to `specCases` or `activeSpecCaseIndex`: replace with the new `RedstoneSpec` fields (check what it renders/uses and adapt).

**`src/main/kotlin/com/breadmoirai/redstonespecs/item/SpecMarkerTool.kt`** — replace the body of `useOn` on the server branch:

```kotlin
if (!level.isClientSide) {
    val spec = be.spec ?: return InteractionResult.PASS
    val relPos = hitPos.subtract(be.blockPos)
    val hitState = level.getBlockState(hitPos)
    val initProps = captureBlockStateProps(hitState)

    if (spec.entryAt(relPos) == null) {
        LOGGER.debug("[SpecMarkerTool#useOn] placing {} entry at {}", javaClass.simpleName, relPos)
        be.addOrUpdateEntry(createEntry(relPos, initProps, hitState))
    } else {
        LOGGER.debug("[SpecMarkerTool#useOn] opening editor for existing entry at {}", relPos)
    }

    ServerPlayNetworking.send(player as ServerPlayer, OpenEditorS2CPayload(be.blockPos, relPos))
}
```

Also update `OutputSpecMarkerItem.createEntry` — in Simple Mode the initial output entry should use `SimTime(spec.lifespan, Phase.END_OF_TICK)`. Change `createEntry` to take a `spec: RedstoneSpec` parameter:

```kotlin
abstract fun createEntry(relPos: BlockPos, initProps: Map<String, String>, initState: BlockState, spec: RedstoneSpec): SpecEntry
```

Update `useOn` to pass `spec` to `createEntry(relPos, initProps, hitState, spec)`.

Update each subclass:
```kotlin
class InputSpecMarkerItem : SpecMarkerTool() {
    override fun createEntry(relPos: BlockPos, initProps: Map<String, String>, initState: BlockState, spec: RedstoneSpec): SpecEntry =
        InputSpec(relPos, "", 0x4488FF, listOf(SimTime.INIT to propsToCondition(initProps, initState)))
}

class OutputSpecMarkerItem : SpecMarkerTool() {
    override fun createEntry(relPos: BlockPos, initProps: Map<String, String>, initState: BlockState, spec: RedstoneSpec): SpecEntry {
        val time = if (spec.mode == SpecMode.SIMPLE) SimTime(spec.lifespan, Phase.END_OF_TICK) else SimTime.INIT
        return OutputSpec(relPos, "", 0x44FF88, listOf(time to propsToCondition(initProps, initState)))
    }
}

class BreakpointSpecMarkerItem : SpecMarkerTool() {
    override fun createEntry(relPos: BlockPos, initProps: Map<String, String>, initState: BlockState, spec: RedstoneSpec): SpecEntry =
        BreakpointSpec(relPos, "", 0xFF4444)
}

class AutoSpecMarkerItem : SpecMarkerTool() {
    override fun createEntry(relPos: BlockPos, initProps: Map<String, String>, initState: BlockState, spec: RedstoneSpec): SpecEntry =
        AutoSpec(relPos, "", 0xFFAA00)
}
```

**`src/main/kotlin/com/breadmoirai/redstonespecs/item/UndoStack.kt`** — remove `specCaseIndex` from `UndoRecord`:

```kotlin
object UndoStack {
    private const val MAX_DEPTH = 20

    data class UndoRecord(val originPos: BlockPos, val entry: SpecEntry)

    private val stacks = HashMap<UUID, ArrayDeque<UndoRecord>>()

    fun push(playerId: UUID, record: UndoRecord) {
        val stack = stacks.getOrPut(playerId, ::ArrayDeque)
        stack.addLast(record)
        if (stack.size > MAX_DEPTH) stack.removeFirst()
    }

    fun pop(playerId: UUID): UndoRecord? = stacks[playerId]?.removeLastOrNull()

    fun clear(playerId: UUID) { stacks.remove(playerId) }
}
```

**`src/client/kotlin/.../render/HudOverlayRenderer.kt`** and **`RedstoneSpecBoundsRenderer.kt`** — look for any use of `spec.specCases`, `spec.name`, `be.activeSpecCaseIndex`. Replace `spec.name` with `spec.id`. Replace `spec.specCases[be.activeSpecCaseIndex]` usages with direct `spec` fields (inputs, outputs, lifespan, etc.).

**`src/main/kotlin/.../Redstonespecs.kt`** — check for `spec.name` references; replace with `spec.id`.

- [ ] **Step 5: Compile**

```bash
./gradlew compileKotlin compileTestKotlin
```

Fix any remaining errors. Expected: BUILD SUCCESSFUL (some test files may fail — that's ok, tests are updated in Task 12).

- [ ] **Step 6: Commit**

```bash
git add -p
git commit -m "refactor: replace SpecCase with flat RedstoneSpec; add SpecMode, lifespan, structure fields"
```

---

### Task 3: Rewrite `TestResult` — remove `SpecCaseResult`, change `specId` to `String`

**Files:**
- Modify: `src/main/kotlin/com/breadmoirai/redstonespecs/data/TestResult.kt`

- [ ] **Step 1: Rewrite `TestResult.kt`**

```kotlin
package com.breadmoirai.redstonespecs.data

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder

data class TickCheck(
    val simTime: SimTime,
    val label: String,
    val expected: String,
    val actual: String,
    val pass: Boolean,
) {
    companion object {
        val CODEC: Codec<TickCheck> = RecordCodecBuilder.create { instance ->
            instance.group(
                SimTime.CODEC.fieldOf("sim_time").forGetter(TickCheck::simTime),
                Codec.STRING.fieldOf("label").forGetter(TickCheck::label),
                Codec.STRING.fieldOf("expected").forGetter(TickCheck::expected),
                Codec.STRING.fieldOf("actual").forGetter(TickCheck::actual),
                Codec.BOOL.fieldOf("pass").forGetter(TickCheck::pass),
            ).apply(instance, ::TickCheck)
        }
    }
}

data class TestResult(
    val specId: String,
    val timestamp: Long,
    val checks: List<TickCheck>,
) {
    val pass: Boolean get() = checks.all { it.pass }
    val passCount: Int get() = checks.count { it.pass }

    companion object {
        val CODEC: Codec<TestResult> = RecordCodecBuilder.create { instance ->
            instance.group(
                Codec.STRING.fieldOf("spec_id").forGetter(TestResult::specId),
                Codec.LONG.fieldOf("timestamp").forGetter(TestResult::timestamp),
                TickCheck.CODEC.listOf().optionalFieldOf("checks", emptyList())
                    .forGetter(TestResult::checks),
            ).apply(instance, ::TestResult)
        }
    }
}
```

- [ ] **Step 2: Fix compile errors caused by `SpecCaseResult` removal**

`SpecRunnerCoordinator` references `SpecCaseResult` and `results: MutableList<SpecCaseResult>`. Temporarily stub it:

In `SpecRunnerCoordinator.kt`, change:
```kotlin
private val results = HashMap<RedstoneSpecBlockEntity, MutableList<SpecCaseResult>>()
```
to:
```kotlin
private val results = HashMap<RedstoneSpecBlockEntity, MutableList<TickCheck>>()
```

And in `finishRun`, replace `SpecCaseResult` references with `TickCheck` list and update `TestResult` construction:
```kotlin
val resultList = results.remove(be) ?: mutableListOf()
val testResult = TestResult(spec.id, System.currentTimeMillis(), resultList)
```

`SpecRunner.onPhase()` currently returns `SpecCaseResult?`. Change the return type to `List<TickCheck>?`. Update the return statement:
```kotlin
if (ticksElapsed >= spec.lifespan) {
    return checks.toList()
}
```

Remove the `BreakpointHit.caseName` field and update usages. In `SpecRunner.kt`:
```kotlin
data class BreakpointHit(
    val simTime: SimTime,
    val specId: String,
    val breakpointLabel: String,
)
```

- [ ] **Step 3: Compile**

```bash
./gradlew compileKotlin
```

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/com/breadmoirai/redstonespecs/data/TestResult.kt
git add src/main/kotlin/com/breadmoirai/redstonespecs/runner/
git commit -m "refactor: simplify TestResult to flat check list; remove SpecCaseResult"
```

---

### Task 4: Rewrite packets and network registry

**Files:**
- Modify: `src/main/kotlin/com/breadmoirai/redstonespecs/network/Packets.kt`
- Modify: `src/main/kotlin/com/breadmoirai/redstonespecs/network/NetworkRegistry.kt`

- [ ] **Step 1: Rewrite `Packets.kt`**

Remove: `CycleSpecCaseC2SPayload`, `AddSpecCaseC2SPayload`, `RemoveSpecCaseC2SPayload`, `SelectSpecCaseC2SPayload`, `RenameSpecCaseC2SPayload`, `RenameSpecC2SPayload`, `AutoSpecRecordedS2CPayload`.

Remove `runAll` from `RunSpecC2SPayload`:
```kotlin
data class RunSpecC2SPayload(val originPos: BlockPos) : CustomPacketPayload {
    companion object {
        val TYPE = CustomPacketPayload.Type<RunSpecC2SPayload>(
            Identifier.fromNamespaceAndPath("redstonespecs", "run_spec")
        )
        val STREAM_CODEC: StreamCodec<ByteBuf, RunSpecC2SPayload> = StreamCodec.composite(
            BlockPos.STREAM_CODEC, RunSpecC2SPayload::originPos,
            ::RunSpecC2SPayload,
        )
    }
    override fun type() = TYPE
}
```

Remove `specCaseIndex` from `SaveSpecEntryC2SPayload`:
```kotlin
data class SaveSpecEntryC2SPayload(
    val originPos: BlockPos,
    val entry: SpecEntry,
) : CustomPacketPayload {
    companion object {
        val TYPE = CustomPacketPayload.Type<SaveSpecEntryC2SPayload>(
            Identifier.fromNamespaceAndPath("redstonespecs", "save_spec_entry")
        )
        val STREAM_CODEC: StreamCodec<ByteBuf, SaveSpecEntryC2SPayload> = StreamCodec.composite(
            BlockPos.STREAM_CODEC, SaveSpecEntryC2SPayload::originPos,
            ByteBufCodecs.fromCodec(SpecEntry.CODEC), SaveSpecEntryC2SPayload::entry,
            ::SaveSpecEntryC2SPayload,
        )
    }
    override fun type() = TYPE
}
```

Remove `specCaseIndex` from `RemoveSpecEntryC2SPayload`:
```kotlin
data class RemoveSpecEntryC2SPayload(
    val originPos: BlockPos,
    val entryRelPos: BlockPos,
) : CustomPacketPayload {
    companion object {
        val TYPE = CustomPacketPayload.Type<RemoveSpecEntryC2SPayload>(
            Identifier.fromNamespaceAndPath("redstonespecs", "remove_spec_entry")
        )
        val STREAM_CODEC: StreamCodec<ByteBuf, RemoveSpecEntryC2SPayload> = StreamCodec.composite(
            BlockPos.STREAM_CODEC, RemoveSpecEntryC2SPayload::originPos,
            BlockPos.STREAM_CODEC, RemoveSpecEntryC2SPayload::entryRelPos,
            ::RemoveSpecEntryC2SPayload,
        )
    }
    override fun type() = TYPE
}
```

Remove `caseName` from `BreakpointHitS2CPayload`:
```kotlin
data class BreakpointHitS2CPayload(
    val originPos: BlockPos,
    val simTime: SimTime,
    val specId: String,
    val breakpointLabel: String,
) : CustomPacketPayload {
    companion object {
        val TYPE = CustomPacketPayload.Type<BreakpointHitS2CPayload>(
            Identifier.fromNamespaceAndPath("redstonespecs", "breakpoint_hit")
        )
        val STREAM_CODEC: StreamCodec<ByteBuf, BreakpointHitS2CPayload> = StreamCodec.composite(
            BlockPos.STREAM_CODEC, BreakpointHitS2CPayload::originPos,
            SimTime.STREAM_CODEC, BreakpointHitS2CPayload::simTime,
            ByteBufCodecs.STRING_UTF8, BreakpointHitS2CPayload::specId,
            ByteBufCodecs.STRING_UTF8, BreakpointHitS2CPayload::breakpointLabel,
            ::BreakpointHitS2CPayload,
        )
    }
    override fun type() = TYPE
}
```

Update `OpenOverviewS2CPayload` to include available structure ids:
```kotlin
data class OpenOverviewS2CPayload(
    val originPos: BlockPos,
    val availableStructures: List<String>,
) : CustomPacketPayload {
    companion object {
        val TYPE = CustomPacketPayload.Type<OpenOverviewS2CPayload>(
            Identifier.fromNamespaceAndPath("redstonespecs", "open_overview")
        )
        val STREAM_CODEC: StreamCodec<ByteBuf, OpenOverviewS2CPayload> = StreamCodec.composite(
            BlockPos.STREAM_CODEC, OpenOverviewS2CPayload::originPos,
            ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()), OpenOverviewS2CPayload::availableStructures,
            ::OpenOverviewS2CPayload,
        )
    }
    override fun type() = TYPE
}
```

Add new C2S packets:
```kotlin
data class SetSpecIdC2SPayload(val originPos: BlockPos, val id: String) : CustomPacketPayload {
    companion object {
        val TYPE = CustomPacketPayload.Type<SetSpecIdC2SPayload>(
            Identifier.fromNamespaceAndPath("redstonespecs", "set_spec_id")
        )
        val STREAM_CODEC: StreamCodec<ByteBuf, SetSpecIdC2SPayload> = StreamCodec.composite(
            BlockPos.STREAM_CODEC, SetSpecIdC2SPayload::originPos,
            ByteBufCodecs.STRING_UTF8, SetSpecIdC2SPayload::id,
            ::SetSpecIdC2SPayload,
        )
    }
    override fun type() = TYPE
}

data class SetSpecModeC2SPayload(val originPos: BlockPos, val mode: SpecMode) : CustomPacketPayload {
    companion object {
        val TYPE = CustomPacketPayload.Type<SetSpecModeC2SPayload>(
            Identifier.fromNamespaceAndPath("redstonespecs", "set_spec_mode")
        )
        val STREAM_CODEC: StreamCodec<ByteBuf, SetSpecModeC2SPayload> = StreamCodec.composite(
            BlockPos.STREAM_CODEC, SetSpecModeC2SPayload::originPos,
            ByteBufCodecs.VAR_INT.map({ SpecMode.entries[it] }, SpecMode::ordinal), SetSpecModeC2SPayload::mode,
            ::SetSpecModeC2SPayload,
        )
    }
    override fun type() = TYPE
}

data class SetLifespanC2SPayload(val originPos: BlockPos, val lifespan: Int) : CustomPacketPayload {
    companion object {
        val TYPE = CustomPacketPayload.Type<SetLifespanC2SPayload>(
            Identifier.fromNamespaceAndPath("redstonespecs", "set_lifespan")
        )
        val STREAM_CODEC: StreamCodec<ByteBuf, SetLifespanC2SPayload> = StreamCodec.composite(
            BlockPos.STREAM_CODEC, SetLifespanC2SPayload::originPos,
            ByteBufCodecs.VAR_INT, SetLifespanC2SPayload::lifespan,
            ::SetLifespanC2SPayload,
        )
    }
    override fun type() = TYPE
}

data class SetStructureC2SPayload(val originPos: BlockPos, val structure: String?) : CustomPacketPayload {
    companion object {
        val TYPE = CustomPacketPayload.Type<SetStructureC2SPayload>(
            Identifier.fromNamespaceAndPath("redstonespecs", "set_structure")
        )
        val STREAM_CODEC: StreamCodec<ByteBuf, SetStructureC2SPayload> = StreamCodec.composite(
            BlockPos.STREAM_CODEC, SetStructureC2SPayload::originPos,
            ByteBufCodecs.optional(ByteBufCodecs.STRING_UTF8)
                .map({ it.orElse(null) }, { java.util.Optional.ofNullable(it) }),
            SetStructureC2SPayload::structure,
            ::SetStructureC2SPayload,
        )
    }
    override fun type() = TYPE
}

// Client requests server to save the spec to disk
data class SaveSpecC2SPayload(val originPos: BlockPos) : CustomPacketPayload {
    companion object {
        val TYPE = CustomPacketPayload.Type<SaveSpecC2SPayload>(
            Identifier.fromNamespaceAndPath("redstonespecs", "save_spec")
        )
        val STREAM_CODEC: StreamCodec<ByteBuf, SaveSpecC2SPayload> = StreamCodec.composite(
            BlockPos.STREAM_CODEC, SaveSpecC2SPayload::originPos,
            ::SaveSpecC2SPayload,
        )
    }
    override fun type() = TYPE
}

// Client requests server to load spec JSON (and optionally structure) from disk
data class LoadSpecC2SPayload(val originPos: BlockPos, val specId: String) : CustomPacketPayload {
    companion object {
        val TYPE = CustomPacketPayload.Type<LoadSpecC2SPayload>(
            Identifier.fromNamespaceAndPath("redstonespecs", "load_spec")
        )
        val STREAM_CODEC: StreamCodec<ByteBuf, LoadSpecC2SPayload> = StreamCodec.composite(
            BlockPos.STREAM_CODEC, LoadSpecC2SPayload::originPos,
            ByteBufCodecs.STRING_UTF8, LoadSpecC2SPayload::specId,
            ::LoadSpecC2SPayload,
        )
    }
    override fun type() = TYPE
}

// Server asks client: structure differs from saved — "save" or "fork"?
// promptKind: "SAVE_OR_FORK" (existing structure changed) or "CREATE_OR_FORK" (structure=null, file exists)
data class StructurePromptS2CPayload(
    val originPos: BlockPos,
    val currentStructureId: String,
    val promptKind: String,
) : CustomPacketPayload {
    companion object {
        val TYPE = CustomPacketPayload.Type<StructurePromptS2CPayload>(
            Identifier.fromNamespaceAndPath("redstonespecs", "structure_prompt")
        )
        val STREAM_CODEC: StreamCodec<ByteBuf, StructurePromptS2CPayload> = StreamCodec.composite(
            BlockPos.STREAM_CODEC, StructurePromptS2CPayload::originPos,
            ByteBufCodecs.STRING_UTF8, StructurePromptS2CPayload::currentStructureId,
            ByteBufCodecs.STRING_UTF8, StructurePromptS2CPayload::promptKind,
            ::StructurePromptS2CPayload,
        )
    }
    override fun type() = TYPE
}

// Client response to StructurePromptS2CPayload
// decision: "SAVE", "FORK", or "CANCEL". newId is only set when decision == "FORK".
data class StructureDecisionC2SPayload(
    val originPos: BlockPos,
    val decision: String,
    val newId: String,
) : CustomPacketPayload {
    companion object {
        val TYPE = CustomPacketPayload.Type<StructureDecisionC2SPayload>(
            Identifier.fromNamespaceAndPath("redstonespecs", "structure_decision")
        )
        val STREAM_CODEC: StreamCodec<ByteBuf, StructureDecisionC2SPayload> = StreamCodec.composite(
            BlockPos.STREAM_CODEC, StructureDecisionC2SPayload::originPos,
            ByteBufCodecs.STRING_UTF8, StructureDecisionC2SPayload::decision,
            ByteBufCodecs.STRING_UTF8, StructureDecisionC2SPayload::newId,
            ::StructureDecisionC2SPayload,
        )
    }
    override fun type() = TYPE
}

// Server asks: non-air blocks in bounds — overwrite?
data class OverwritePromptS2CPayload(val originPos: BlockPos, val specId: String) : CustomPacketPayload {
    companion object {
        val TYPE = CustomPacketPayload.Type<OverwritePromptS2CPayload>(
            Identifier.fromNamespaceAndPath("redstonespecs", "overwrite_prompt")
        )
        val STREAM_CODEC: StreamCodec<ByteBuf, OverwritePromptS2CPayload> = StreamCodec.composite(
            BlockPos.STREAM_CODEC, OverwritePromptS2CPayload::originPos,
            ByteBufCodecs.STRING_UTF8, OverwritePromptS2CPayload::specId,
            ::OverwritePromptS2CPayload,
        )
    }
    override fun type() = TYPE
}

// Client response to OverwritePromptS2CPayload
data class OverwriteDecisionC2SPayload(val originPos: BlockPos, val overwrite: Boolean) : CustomPacketPayload {
    companion object {
        val TYPE = CustomPacketPayload.Type<OverwriteDecisionC2SPayload>(
            Identifier.fromNamespaceAndPath("redstonespecs", "overwrite_decision")
        )
        val STREAM_CODEC: StreamCodec<ByteBuf, OverwriteDecisionC2SPayload> = StreamCodec.composite(
            BlockPos.STREAM_CODEC, OverwriteDecisionC2SPayload::originPos,
            ByteBufCodecs.BOOL, OverwriteDecisionC2SPayload::overwrite,
            ::OverwriteDecisionC2SPayload,
        )
    }
    override fun type() = TYPE
}
```

- [ ] **Step 2: Update `NetworkRegistry.kt`**

Remove registrations for all deleted packets. Add registrations for new packets. Update handler logic:

```kotlin
fun registerNetworking() {
    // S2C
    PayloadTypeRegistry.clientboundPlay().register(OpenOverviewS2CPayload.TYPE, OpenOverviewS2CPayload.STREAM_CODEC)
    PayloadTypeRegistry.clientboundPlay().register(OpenEditorS2CPayload.TYPE, OpenEditorS2CPayload.STREAM_CODEC)
    PayloadTypeRegistry.clientboundPlay().register(TestResultS2CPayload.TYPE, TestResultS2CPayload.STREAM_CODEC)
    PayloadTypeRegistry.clientboundPlay().register(BreakpointHitS2CPayload.TYPE, BreakpointHitS2CPayload.STREAM_CODEC)
    PayloadTypeRegistry.clientboundPlay().register(StructurePromptS2CPayload.TYPE, StructurePromptS2CPayload.STREAM_CODEC)
    PayloadTypeRegistry.clientboundPlay().register(OverwritePromptS2CPayload.TYPE, OverwritePromptS2CPayload.STREAM_CODEC)

    // C2S
    PayloadTypeRegistry.serverboundPlay().register(UndoC2SPayload.TYPE, UndoC2SPayload.STREAM_CODEC)
    PayloadTypeRegistry.serverboundPlay().register(RunSpecC2SPayload.TYPE, RunSpecC2SPayload.STREAM_CODEC)
    PayloadTypeRegistry.serverboundPlay().register(ResetSpecC2SPayload.TYPE, ResetSpecC2SPayload.STREAM_CODEC)
    PayloadTypeRegistry.serverboundPlay().register(ResumeSpecC2SPayload.TYPE, ResumeSpecC2SPayload.STREAM_CODEC)
    PayloadTypeRegistry.serverboundPlay().register(SaveSpecEntryC2SPayload.TYPE, SaveSpecEntryC2SPayload.STREAM_CODEC)
    PayloadTypeRegistry.serverboundPlay().register(RemoveSpecEntryC2SPayload.TYPE, RemoveSpecEntryC2SPayload.STREAM_CODEC)
    PayloadTypeRegistry.serverboundPlay().register(ResizeBoundsC2SPayload.TYPE, ResizeBoundsC2SPayload.STREAM_CODEC)
    PayloadTypeRegistry.serverboundPlay().register(NudgeSpecBoundsC2SPayload.TYPE, NudgeSpecBoundsC2SPayload.STREAM_CODEC)
    PayloadTypeRegistry.serverboundPlay().register(SetSpecIdC2SPayload.TYPE, SetSpecIdC2SPayload.STREAM_CODEC)
    PayloadTypeRegistry.serverboundPlay().register(SetSpecModeC2SPayload.TYPE, SetSpecModeC2SPayload.STREAM_CODEC)
    PayloadTypeRegistry.serverboundPlay().register(SetLifespanC2SPayload.TYPE, SetLifespanC2SPayload.STREAM_CODEC)
    PayloadTypeRegistry.serverboundPlay().register(SetStructureC2SPayload.TYPE, SetStructureC2SPayload.STREAM_CODEC)
    PayloadTypeRegistry.serverboundPlay().register(SaveSpecC2SPayload.TYPE, SaveSpecC2SPayload.STREAM_CODEC)
    PayloadTypeRegistry.serverboundPlay().register(LoadSpecC2SPayload.TYPE, LoadSpecC2SPayload.STREAM_CODEC)
    PayloadTypeRegistry.serverboundPlay().register(StructureDecisionC2SPayload.TYPE, StructureDecisionC2SPayload.STREAM_CODEC)
    PayloadTypeRegistry.serverboundPlay().register(OverwriteDecisionC2SPayload.TYPE, OverwriteDecisionC2SPayload.STREAM_CODEC)

    // Handlers
    ServerPlayNetworking.registerGlobalReceiver(UndoC2SPayload.TYPE) { _, context ->
        val player = context.player()
        context.server().execute {
            val record = UndoStack.pop(player.uuid) ?: return@execute
            val be = player.level().getBlockEntity(record.originPos) as? RedstoneSpecBlockEntity ?: return@execute
            be.addOrUpdateEntry(record.entry)
        }
    }

    ServerPlayNetworking.registerGlobalReceiver(RunSpecC2SPayload.TYPE) { payload, context ->
        context.server().execute {
            val be = context.player().level().getBlockEntity(payload.originPos) as? RedstoneSpecBlockEntity ?: return@execute
            SpecRunnerCoordinator.startRun(be)
        }
    }

    ServerPlayNetworking.registerGlobalReceiver(ResetSpecC2SPayload.TYPE) { payload, context ->
        context.server().execute {
            val be = context.player().level().getBlockEntity(payload.originPos) as? RedstoneSpecBlockEntity ?: return@execute
            SpecRunnerCoordinator.resetSpec(be)
        }
    }

    ServerPlayNetworking.registerGlobalReceiver(ResumeSpecC2SPayload.TYPE) { payload, context ->
        context.server().execute {
            val be = context.player().level().getBlockEntity(payload.originPos) as? RedstoneSpecBlockEntity ?: return@execute
            SpecRunnerCoordinator.resumeSpec(be)
        }
    }

    ServerPlayNetworking.registerGlobalReceiver(SaveSpecEntryC2SPayload.TYPE) { payload, context ->
        context.server().execute {
            val be = context.player().level().getBlockEntity(payload.originPos) as? RedstoneSpecBlockEntity ?: return@execute
            be.addOrUpdateEntry(payload.entry)
        }
    }

    ServerPlayNetworking.registerGlobalReceiver(RemoveSpecEntryC2SPayload.TYPE) { payload, context ->
        val player = context.player()
        context.server().execute {
            val be = player.level().getBlockEntity(payload.originPos) as? RedstoneSpecBlockEntity ?: return@execute
            val removed = be.removeEntry(payload.entryRelPos) ?: return@execute
            UndoStack.push(player.uuid, UndoStack.UndoRecord(payload.originPos, removed))
        }
    }

    ServerPlayNetworking.registerGlobalReceiver(SetSpecIdC2SPayload.TYPE) { payload, context ->
        context.server().execute {
            val be = context.player().level().getBlockEntity(payload.originPos) as? RedstoneSpecBlockEntity ?: return@execute
            if (payload.id.isNotBlank()) be.setSpecId(payload.id)
        }
    }

    ServerPlayNetworking.registerGlobalReceiver(SetSpecModeC2SPayload.TYPE) { payload, context ->
        context.server().execute {
            val be = context.player().level().getBlockEntity(payload.originPos) as? RedstoneSpecBlockEntity ?: return@execute
            be.setMode(payload.mode)
        }
    }

    ServerPlayNetworking.registerGlobalReceiver(SetLifespanC2SPayload.TYPE) { payload, context ->
        context.server().execute {
            val be = context.player().level().getBlockEntity(payload.originPos) as? RedstoneSpecBlockEntity ?: return@execute
            if (payload.lifespan >= 1) be.setLifespan(payload.lifespan)
        }
    }

    ServerPlayNetworking.registerGlobalReceiver(SetStructureC2SPayload.TYPE) { payload, context ->
        context.server().execute {
            val be = context.player().level().getBlockEntity(payload.originPos) as? RedstoneSpecBlockEntity ?: return@execute
            be.setStructure(payload.structure)
        }
    }

    ServerPlayNetworking.registerGlobalReceiver(ResizeBoundsC2SPayload.TYPE) { payload, context ->
        context.server().execute {
            val be = context.player().level().getBlockEntity(payload.originPos) as? RedstoneSpecBlockEntity ?: return@execute
            val spec = be.spec ?: return@execute
            be.setSpec(spec.copy(bounds = payload.bounds))
        }
    }

    ServerPlayNetworking.registerGlobalReceiver(NudgeSpecBoundsC2SPayload.TYPE) { payload, context ->
        context.server().execute {
            val be = context.player().level().getBlockEntity(payload.originPos) as? RedstoneSpecBlockEntity ?: return@execute
            val spec = be.spec ?: return@execute
            be.setSpec(spec.copy(bounds = nudgeBounds(spec.bounds, payload.axis, payload.isMax, payload.delta)))
        }
    }

    // SaveSpec and LoadSpec handlers are added in Task 7 after persistence layer exists.
    // StructureDecision and OverwriteDecision handlers are also added in Task 7.
}
```

- [ ] **Step 3: Fix remaining compile errors**

`SpecEditorScreen.kt` references `state.specCaseIndex` — remove it. Update `SpecEditorState`:
```kotlin
class SpecEditorState(
    val originPos: BlockPos,
    val entryRelPos: BlockPos,
    val originalEntry: SpecEntry,
) {
    var workingLabel: String = originalEntry.label
    var workingColor: Int = originalEntry.color
    val workingEntries: MutableList<Pair<SimTime, StateCondition>>? = when (originalEntry) {
        is InputSpec -> originalEntry.entries.toMutableList()
        is OutputSpec -> originalEntry.entries.toMutableList()
        else -> null
    }
}
```

Update `SpecEditorScreen.tryLaunch()`:
```kotlin
private fun tryLaunch() {
    if (launched) return
    val be = minecraft?.level?.getBlockEntity(originPos) as? RedstoneSpecBlockEntity ?: return
    val entry = be.spec?.entryAt(entryRelPos) ?: return
    launched = true
    val state = SpecEditorState(originPos, entryRelPos, entry)
    minecraft?.setScreen(buildSpecEditorYacl(state))
}
```

In `buildSpecEditorYacl`, update the Remove button and Save action:
```kotlin
// Remove button:
ClientPlayNetworking.send(RemoveSpecEntryC2SPayload(state.originPos, state.entryRelPos))

// Save action:
ClientPlayNetworking.send(SaveSpecEntryC2SPayload(state.originPos, updated))
```

`ClientNetworkHandler.kt` references `BreakpointHitS2CPayload.caseName` — update:
```kotlin
Component.literal("§6Breakpoint hit: §f${payload.breakpointLabel} §7in §f${payload.specId} §7at ${payload.simTime.tick}t ${payload.simTime.phase.name}")
```

Also remove the `AutoSpecRecordedS2CPayload` handler from `ClientNetworkHandler`.

- [ ] **Step 4: Compile**

```bash
./gradlew compileKotlin
```

- [ ] **Step 5: Commit**

```bash
git add -p
git commit -m "refactor: remove SpecCase packets; add SetSpecId/Mode/Lifespan/Structure, Save/Load, prompt packets"
```

---

### Task 5: Rewrite `SpecRunner` and `SpecRunnerCoordinator`

**Files:**
- Modify: `src/main/kotlin/com/breadmoirai/redstonespecs/runner/SpecRunner.kt`
- Modify: `src/main/kotlin/com/breadmoirai/redstonespecs/runner/SpecRunnerCoordinator.kt`

- [ ] **Step 1: Rewrite `SpecRunner.kt`**

Replace `specCase: SpecCase` with direct `spec: RedstoneSpec`. Return `List<TickCheck>?` from `onPhase`. Remove `monitorAutoSpecs`-related fields.

```kotlin
package com.breadmoirai.redstonespecs.runner

import com.breadmoirai.redstonespecs.data.Phase
import com.breadmoirai.redstonespecs.data.RedstoneSpec
import com.breadmoirai.redstonespecs.data.SimTime
import com.breadmoirai.redstonespecs.data.TickCheck
import com.breadmoirai.redstonespecs.data.StateCondition
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
    val specId: String,
    val breakpointLabel: String,
)

class SpecRunner(
    val spec: RedstoneSpec,
    val originPos: BlockPos,
    val level: ServerLevel,
    private val snapshot: SpecSnapshot,
    private val view: StateRecordingView,
    private val boundsWorldMin: BlockPos,
) {
    private var ticksElapsed = -1
    private val checks = mutableListOf<TickCheck>()

    var frozenAt: SimTime? = null
        private set
    var pendingBreakpointHit: BreakpointHit? = null
        private set

    fun start() {
        LOGGER.debug("[SpecRunner#start] starting spec '{}'", spec.id)
        applyInputsAt(SimTime.INIT)
    }

    fun resume() {
        LOGGER.debug("[SpecRunner#resume] resuming spec '{}' frozen at {}", spec.id, frozenAt)
        frozenAt = null
    }

    fun clearPendingBreakpointHit() { pendingBreakpointHit = null }

    fun resetCircuit() { snapshot.restore(level) }

    fun onPhase(phase: Phase): List<TickCheck>? {
        if (frozenAt != null) return null
        if (phase == Phase.START_OF_TICK) ticksElapsed++
        if (ticksElapsed < 0) return null
        if (ticksElapsed >= spec.lifespan) {
            LOGGER.debug("[SpecRunner#onPhase] spec '{}' finished after {} ticks", spec.id, ticksElapsed)
            return checks.toList()
        }
        val simTime = SimTime(ticksElapsed, phase)
        applyInputsAt(simTime)
        checkOutputsAt(simTime)
        checkBreakpointsAt(simTime)
        return null
    }

    private fun applyInputsAt(simTime: SimTime) {
        val userInteractionTime = if (simTime.phase == Phase.START_OF_TICK)
            simTime.copy(phase = Phase.USER_INTERACTION) else null
        for (input in spec.inputs) {
            val (_, condition) = input.entries.find {
                it.first == simTime || (userInteractionTime != null && it.first == userInteractionTime)
            } ?: continue
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
            is StateCondition.BlockType -> LOGGER.warn("[SpecRunner] BlockType condition cannot be applied as input, ignoring")
            else -> LOGGER.warn("[SpecRunner] Unsupported condition type '{}' in flattenToProperties, ignoring", condition::class.simpleName)
        }
    }

    private fun checkOutputsAt(simTime: SimTime) {
        val userInteractionTime = if (simTime.phase == Phase.END_OF_TICK)
            simTime.copy(phase = Phase.USER_INTERACTION) else null
        for (output in spec.outputs) {
            val (_, condition) = output.entries.find {
                it.first == simTime || (userInteractionTime != null && it.first == userInteractionTime)
            } ?: continue
            val wPos = worldPos(output.pos)
            val localPos = worldToLocal(wPos)
            val state = view.stateAt(localPos, simTime)
            val label = output.label.ifEmpty { output.pos.toString() }
            collectChecks(condition, state, wPos, simTime, label)
        }
    }

    private fun collectChecks(condition: StateCondition, state: BlockState, pos: BlockPos, simTime: SimTime, label: String) {
        when (condition) {
            is StateCondition.All -> condition.conditions.forEach { collectChecks(it, state, pos, simTime, label) }
            is StateCondition.BoolProperty -> {
                val prop = state.block.stateDefinition.getProperty(condition.name) as? BooleanProperty
                val actual = prop?.let { state.getValue(it).toString() } ?: "missing"
                val expected = condition.value.toString()
                checks += TickCheck(simTime, "$label.${condition.name}", expected, actual, actual == expected)
            }
            is StateCondition.IntProperty -> {
                val prop = state.block.stateDefinition.getProperty(condition.name) as? IntegerProperty
                val actual = prop?.let { state.getValue(it).toString() } ?: "missing"
                val expected = condition.value.toString()
                checks += TickCheck(simTime, "$label.${condition.name}", expected, actual, actual == expected)
            }
            is StateCondition.EnumProperty -> {
                val actual = blockStatePropertyStr(state, condition.name) ?: "missing"
                checks += TickCheck(simTime, "$label.${condition.name}", condition.value, actual, actual == condition.value)
            }
            is StateCondition.BlockType -> {
                val actualId = BuiltInRegistries.BLOCK.getKey(state.block)?.toString() ?: "missing"
                val expected = condition.blockId.toString()
                checks += TickCheck(simTime, "$label.block", expected, actualId, actualId == expected)
            }
            else -> LOGGER.warn("[SpecRunner] Unsupported condition type '{}' in output check, skipping", condition::class.simpleName)
        }
    }

    private fun checkBreakpointsAt(simTime: SimTime) {
        for (bp in spec.breakpoints) {
            if (!bp.enabled) continue
            if (evaluateCondition(bp.condition, level, worldPos(bp.pos))) {
                LOGGER.debug("[SpecRunner#checkBreakpointsAt] breakpoint '{}' hit at {}", bp.label, simTime)
                frozenAt = simTime
                pendingBreakpointHit = BreakpointHit(simTime, spec.id, bp.label.ifEmpty { bp.pos.toString() })
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

    private fun worldToLocal(worldPos: BlockPos) = BlockPos(
        worldPos.x - boundsWorldMin.x,
        worldPos.y - boundsWorldMin.y,
        worldPos.z - boundsWorldMin.z,
    )
}
```

- [ ] **Step 2: Rewrite `SpecRunnerCoordinator.kt`**

Remove `queues`, `results` (as SpecCaseResult), `monitorAutoSpecs`. Simplify to single run per spec. Wire `TickCheck` accumulation into `results`:

```kotlin
package com.breadmoirai.redstonespecs.runner

import com.breadmoirai.redstonespecs.block.RedstoneSpecBlockEntity
import com.breadmoirai.redstonespecs.data.Phase
import com.breadmoirai.redstonespecs.data.TickCheck
import com.breadmoirai.redstonespecs.data.TestResult
import com.breadmoirai.redstonespecs.network.BreakpointHitS2CPayload
import com.breadmoirai.redstonespecs.network.TestResultS2CPayload
import net.fabricmc.fabric.api.networking.v1.PlayerLookup
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import org.slf4j.LoggerFactory

object SpecRunnerCoordinator {
    private val LOGGER = LoggerFactory.getLogger("Redstone Specs")

    private val runners = HashMap<RedstoneSpecBlockEntity, SpecRunner>()
    private val snapshots = HashMap<RedstoneSpecBlockEntity, SpecSnapshot>()
    private val stateRecorders = HashMap<RedstoneSpecBlockEntity, StateRecorder>()

    fun startRun(be: RedstoneSpecBlockEntity) {
        if (runners.containsKey(be)) return
        val spec = be.spec ?: return
        val level = be.level as? ServerLevel ?: return

        LOGGER.debug("[SpecRunnerCoordinator#startRun] starting '{}'", spec.id)
        snapshots[be] = SpecSnapshot.capture(level, be.blockPos, spec.bounds)
        val recorder = StateRecorder.forSpec(spec.id, be.blockPos, spec.bounds)
        recorder.start(level, be.blockPos, spec.bounds)
        stateRecorders[be] = recorder
        StateRecorder.activate(recorder)

        val boundsWorldMin = BlockPos(
            be.blockPos.x + spec.bounds.minX(),
            be.blockPos.y + spec.bounds.minY(),
            be.blockPos.z + spec.bounds.minZ(),
        )
        val view = StateRecordingView.of(recorder)
        val snapshot = snapshots[be]!!
        snapshot.restore(level)
        val runner = SpecRunner(spec, be.blockPos, level, snapshot, view, boundsWorldMin)
        runner.start()
        runners[be] = runner
    }

    fun resetSpec(be: RedstoneSpecBlockEntity) {
        LOGGER.debug("[SpecRunnerCoordinator#resetSpec] resetting spec at {}", be.blockPos)
        if (stateRecorders.remove(be) != null) StateRecorder.deactivate()
        runners.remove(be)
        val snapshot = snapshots.remove(be)
        val level = be.level as? ServerLevel ?: return
        snapshot?.restore(level)
    }

    fun resumeSpec(be: RedstoneSpecBlockEntity) {
        LOGGER.debug("[SpecRunnerCoordinator#resumeSpec] resuming spec at {}", be.blockPos)
        runners[be]?.resume()
    }

    fun onPhase(level: ServerLevel, phase: Phase) {
        val recorder = StateRecorder.active
        if (recorder != null) {
            if (phase == Phase.START_OF_TICK) recorder.onTickStart()
            recorder.onPhaseStart(phase)
        }
        tickRunners(level, phase)
    }

    private fun tickRunners(level: ServerLevel, phase: Phase) {
        val completed = mutableListOf<Pair<RedstoneSpecBlockEntity, List<TickCheck>>>()

        for ((be, runner) in runners) {
            if (be.level !== level) continue
            val result = runner.onPhase(phase)

            val bpHit = runner.pendingBreakpointHit
            if (bpHit != null) {
                runner.clearPendingBreakpointHit()
                PlayerLookup.level(level).forEach { player ->
                    ServerPlayNetworking.send(player, BreakpointHitS2CPayload(
                        be.blockPos, bpHit.simTime, bpHit.specId, bpHit.breakpointLabel,
                    ))
                }
            }

            if (result != null) {
                completed += be to result
            }
        }

        for ((be, checks) in completed) {
            runners.remove(be)
            finishRun(be, checks)
        }
    }

    private fun finishRun(be: RedstoneSpecBlockEntity, checks: List<TickCheck>) {
        val recorder = stateRecorders.remove(be)
        if (recorder != null) StateRecorder.deactivate()
        val level = be.level as? ServerLevel ?: return
        if (recorder != null) StateRecordingStorage.save(level, recorder.toRecording())
        val spec = be.spec ?: return
        val snapshot = snapshots.remove(be)
        snapshot?.restore(level)

        LOGGER.debug("[SpecRunnerCoordinator#finishRun] spec '{}' done: {}/{} checks passed",
            spec.id, checks.count { it.pass }, checks.size)
        val testResult = TestResult(spec.id, System.currentTimeMillis(), checks)
        be.setLastTestResult(testResult)
        PlayerLookup.level(level).forEach { player ->
            ServerPlayNetworking.send(player, TestResultS2CPayload(be.blockPos, testResult))
        }
    }
}
```

- [ ] **Step 3: Compile**

```bash
./gradlew compileKotlin
```

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/com/breadmoirai/redstonespecs/runner/
git commit -m "refactor: SpecRunner works directly on RedstoneSpec; SpecRunnerCoordinator removes case queue"
```

---

### Task 6: Persistence layer — JSON and NBT

**Files:**
- Create: `src/main/kotlin/com/breadmoirai/redstonespecs/persistence/SpecPersistence.kt`
- Create: `src/main/kotlin/com/breadmoirai/redstonespecs/persistence/StructurePersistence.kt`
- Create: `src/test/kotlin/com/breadmoirai/redstonespecs/persistence/SpecPersistenceTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.breadmoirai.redstonespecs.persistence

import com.breadmoirai.redstonespecs.data.RedstoneSpec
import com.breadmoirai.redstonespecs.data.SpecMode
import net.minecraft.SharedConstants
import net.minecraft.server.Bootstrap
import net.minecraft.world.level.levelgen.structure.BoundingBox
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SpecPersistenceTest {

    @BeforeAll
    fun bootstrap() {
        SharedConstants.tryDetectVersion()
        Bootstrap.bootStrap()
    }

    @Test
    fun `save and load roundtrip`(@TempDir dir: Path) {
        val spec = RedstoneSpec.new("lever_lamp").copy(
            mode = SpecMode.TICK_AWARE,
            lifespan = 8,
            structure = "lever_lamp",
            bounds = BoundingBox(1, 0, 1, 5, 4, 5),
        )
        SpecPersistence.save(dir, spec)
        val loaded = SpecPersistence.load(dir, "lever_lamp")
        assertEquals(spec, loaded)
    }

    @Test
    fun `load returns null for unknown id`(@TempDir dir: Path) {
        assertNull(SpecPersistence.load(dir, "nonexistent"))
    }

    @Test
    fun `listIds returns saved ids`(@TempDir dir: Path) {
        SpecPersistence.save(dir, RedstoneSpec.new("alpha"))
        SpecPersistence.save(dir, RedstoneSpec.new("beta"))
        val ids = SpecPersistence.listIds(dir)
        assertTrue(ids.containsAll(listOf("alpha", "beta")))
    }
}
```

- [ ] **Step 2: Run test — verify it fails**

```bash
./gradlew test --tests "com.breadmoirai.redstonespecs.persistence.SpecPersistenceTest"
```
Expected: FAIL — `SpecPersistence` does not exist yet.

- [ ] **Step 3: Create `SpecPersistence.kt`**

```kotlin
package com.breadmoirai.redstonespecs.persistence

import com.breadmoirai.redstonespecs.data.RedstoneSpec
import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import com.mojang.serialization.JsonOps
import org.slf4j.LoggerFactory
import java.nio.file.Path
import kotlin.io.path.*

private val LOGGER = LoggerFactory.getLogger("Redstone Specs")
private val GSON = GsonBuilder().setPrettyPrinting().create()

object SpecPersistence {

    fun save(saveDir: Path, spec: RedstoneSpec) {
        saveDir.createDirectories()
        val jsonElement = RedstoneSpec.CODEC.encodeStart(JsonOps.INSTANCE, spec).getOrThrow()
        val file = saveDir.resolve("${spec.id}.json")
        file.writeText(GSON.toJson(jsonElement))
        LOGGER.debug("[SpecPersistence#save] saved spec '{}' to {}", spec.id, file)
    }

    fun load(saveDir: Path, id: String): RedstoneSpec? {
        val file = saveDir.resolve("$id.json")
        if (!file.exists()) return null
        return runCatching {
            val json = JsonParser.parseReader(file.reader())
            RedstoneSpec.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow()
        }.onFailure { e ->
            LOGGER.warn("[SpecPersistence#load] failed to load spec '{}': {}", id, e.message)
        }.getOrNull()
    }

    fun listIds(saveDir: Path): List<String> {
        if (!saveDir.exists()) return emptyList()
        return saveDir.listDirectoryEntries("*.json").map { it.nameWithoutExtension }
    }
}
```

- [ ] **Step 4: Run test — verify it passes**

```bash
./gradlew test --tests "com.breadmoirai.redstonespecs.persistence.SpecPersistenceTest"
```
Expected: PASS

- [ ] **Step 5: Create `StructurePersistence.kt`**

```kotlin
package com.breadmoirai.redstonespecs.persistence

import net.minecraft.core.BlockPos
import net.minecraft.core.Vec3i
import net.minecraft.nbt.NbtIo
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.levelgen.structure.BoundingBox
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate
import org.slf4j.LoggerFactory
import java.nio.file.Path
import kotlin.io.path.*

private val LOGGER = LoggerFactory.getLogger("Redstone Specs")

object StructurePersistence {

    fun save(saveDir: Path, id: String, level: ServerLevel, originPos: BlockPos, bounds: BoundingBox) {
        saveDir.createDirectories()
        val template = StructureTemplate()
        val worldMin = worldMin(originPos, bounds)
        val size = size(bounds)
        template.fillFromWorld(level, worldMin, size, false, null)
        val nbt = template.save(level.registryAccess())
        val file = saveDir.resolve("$id.nbt")
        NbtIo.writeCompressed(nbt, file.toFile())
        LOGGER.debug("[StructurePersistence#save] saved structure '{}' to {}", id, file)
    }

    fun load(saveDir: Path, id: String, level: ServerLevel, originPos: BlockPos, bounds: BoundingBox) {
        val file = saveDir.resolve("$id.nbt")
        if (!file.exists()) {
            LOGGER.warn("[StructurePersistence#load] structure file '{}' not found", file)
            return
        }
        val nbt = NbtIo.readCompressed(file.toFile())
        val template = StructureTemplate()
        template.load(level.registryAccess(), nbt)
        val worldMin = worldMin(originPos, bounds)
        template.placeInWorld(level, worldMin, worldMin, StructurePlaceSettings(), level.random, 2)
        LOGGER.debug("[StructurePersistence#load] placed structure '{}' at {}", id, worldMin)
    }

    fun hasChanges(saveDir: Path, id: String, level: ServerLevel, originPos: BlockPos, bounds: BoundingBox): Boolean {
        val file = saveDir.resolve("$id.nbt")
        if (!file.exists()) return true
        val savedNbt = NbtIo.readCompressed(file.toFile())
        val live = StructureTemplate()
        live.fillFromWorld(level, worldMin(originPos, bounds), size(bounds), false, null)
        val liveNbt = live.save(level.registryAccess())
        return savedNbt != liveNbt
    }

    fun hasNonAirBlocks(level: ServerLevel, originPos: BlockPos, bounds: BoundingBox): Boolean {
        val min = worldMin(originPos, bounds)
        val max = BlockPos(
            originPos.x + bounds.maxX(),
            originPos.y + bounds.maxY(),
            originPos.z + bounds.maxZ(),
        )
        for (x in min.x..max.x)
            for (y in min.y..max.y)
                for (z in min.z..max.z)
                    if (!level.getBlockState(BlockPos(x, y, z)).`is`(Blocks.AIR)) return true
        return false
    }

    fun clearBounds(level: ServerLevel, originPos: BlockPos, bounds: BoundingBox) {
        val min = worldMin(originPos, bounds)
        val max = BlockPos(
            originPos.x + bounds.maxX(),
            originPos.y + bounds.maxY(),
            originPos.z + bounds.maxZ(),
        )
        for (x in min.x..max.x)
            for (y in min.y..max.y)
                for (z in min.z..max.z)
                    level.setBlock(BlockPos(x, y, z), Blocks.AIR.defaultBlockState(), 2)
    }

    fun listIds(saveDir: Path): List<String> {
        if (!saveDir.exists()) return emptyList()
        return saveDir.listDirectoryEntries("*.nbt").map { it.nameWithoutExtension }
    }

    private fun worldMin(originPos: BlockPos, bounds: BoundingBox) = BlockPos(
        originPos.x + bounds.minX(),
        originPos.y + bounds.minY(),
        originPos.z + bounds.minZ(),
    )

    private fun size(bounds: BoundingBox) = Vec3i(
        bounds.maxX() - bounds.minX() + 1,
        bounds.maxY() - bounds.minY() + 1,
        bounds.maxZ() - bounds.minZ() + 1,
    )
}
```

- [ ] **Step 6: Compile**

```bash
./gradlew compileKotlin
```

- [ ] **Step 7: Commit**

```bash
git add src/main/kotlin/com/breadmoirai/redstonespecs/persistence/
git add src/test/kotlin/com/breadmoirai/redstonespecs/persistence/
git commit -m "feat: add SpecPersistence (JSON) and StructurePersistence (NBT) with tests"
```

---

### Task 7: Wire persistence into `NetworkRegistry` save/load handlers

**Files:**
- Modify: `src/main/kotlin/com/breadmoirai/redstonespecs/network/NetworkRegistry.kt`
- Modify: `src/main/kotlin/com/breadmoirai/redstonespecs/config/DevLevel.kt` (add `specSaveDir` to `SharedSettings`)

- [ ] **Step 1: Add `specSaveDir` to `SharedSettings`**

In `DevLevel.kt`, add:
```kotlin
object SharedSettings {
    var devLevel: DevLevel = DevLevel.STANDARD
    var specSaveDir: String = "redstonespecs"
}
```

- [ ] **Step 2: Add save/load handlers to `NetworkRegistry.kt`**

Add a helper to get the save directory from the server:
```kotlin
private fun saveDir(server: net.minecraft.server.MinecraftServer): java.nio.file.Path =
    server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
        .resolve(SharedSettings.specSaveDir)
```

Add the `SaveSpecC2SPayload` handler at the end of `registerNetworking()`:
```kotlin
ServerPlayNetworking.registerGlobalReceiver(SaveSpecC2SPayload.TYPE) { payload, context ->
    val player = context.player()
    context.server().execute {
        val be = player.level().getBlockEntity(payload.originPos) as? RedstoneSpecBlockEntity ?: return@execute
        val spec = be.spec ?: return@execute
        val dir = saveDir(context.server())

        // TODO v1: Simple Mode collapse warning (warn user that per-tick entries will be dropped)
        // is deferred — for now the JSON is saved as-is regardless of mode.
        // Save the JSON
        SpecPersistence.save(dir, spec)
        LOGGER.debug("[NetworkRegistry#saveSpec] saved spec '{}' JSON", spec.id)

        // Structure handling
        val structureId = spec.structure
        if (structureId != null) {
            val level = be.level as? ServerLevel ?: return@execute
            if (StructurePersistence.hasChanges(dir, structureId, level, be.blockPos, spec.bounds)) {
                ServerPlayNetworking.send(player, StructurePromptS2CPayload(
                    payload.originPos, structureId, "SAVE_OR_FORK"
                ))
            }
            // else: no changes, nothing more to do
        } else {
            val level = be.level as? ServerLevel ?: return@execute
            val defaultId = spec.id
            if (dir.resolve("$defaultId.nbt").exists()) {
                ServerPlayNetworking.send(player, StructurePromptS2CPayload(
                    payload.originPos, defaultId, "CREATE_OR_FORK"
                ))
            } else {
                // Auto-save with spec id
                StructurePersistence.save(dir, defaultId, level, be.blockPos, spec.bounds)
                be.setStructure(defaultId)
                LOGGER.debug("[NetworkRegistry#saveSpec] auto-saved structure as '{}'", defaultId)
            }
        }
    }
}

ServerPlayNetworking.registerGlobalReceiver(StructureDecisionC2SPayload.TYPE) { payload, context ->
    context.server().execute {
        val be = context.player().level().getBlockEntity(payload.originPos) as? RedstoneSpecBlockEntity ?: return@execute
        val spec = be.spec ?: return@execute
        val level = be.level as? ServerLevel ?: return@execute
        val dir = saveDir(context.server())
        when (payload.decision) {
            "SAVE" -> {
                val id = payload.newId.ifBlank { spec.structure ?: spec.id }
                StructurePersistence.save(dir, id, level, be.blockPos, spec.bounds)
                if (spec.structure != id) be.setStructure(id)
                LOGGER.debug("[NetworkRegistry#structureDecision] saved structure as '{}'", id)
            }
            "FORK" -> {
                val newId = payload.newId
                if (newId.isBlank()) return@execute
                StructurePersistence.save(dir, newId, level, be.blockPos, spec.bounds)
                be.setStructure(newId)
                LOGGER.debug("[NetworkRegistry#structureDecision] forked structure to '{}'", newId)
            }
            "CANCEL" -> LOGGER.debug("[NetworkRegistry#structureDecision] user cancelled structure save")
        }
    }
}

ServerPlayNetworking.registerGlobalReceiver(LoadSpecC2SPayload.TYPE) { payload, context ->
    val player = context.player()
    context.server().execute {
        val be = player.level().getBlockEntity(payload.originPos) as? RedstoneSpecBlockEntity ?: return@execute
        val dir = saveDir(context.server())
        val spec = SpecPersistence.load(dir, payload.specId)
        if (spec == null) {
            LOGGER.warn("[NetworkRegistry#loadSpec] spec '{}' not found on disk", payload.specId)
            return@execute
        }
        be.setSpec(spec)
        LOGGER.debug("[NetworkRegistry#loadSpec] loaded spec '{}' from disk", payload.specId)

        val structureId = spec.structure ?: return@execute
        val level = be.level as? ServerLevel ?: return@execute
        if (StructurePersistence.hasNonAirBlocks(level, be.blockPos, spec.bounds)) {
            ServerPlayNetworking.send(player, OverwritePromptS2CPayload(payload.originPos, structureId))
        } else {
            StructurePersistence.load(dir, structureId, level, be.blockPos, spec.bounds)
            LOGGER.debug("[NetworkRegistry#loadSpec] placed structure '{}'", structureId)
        }
    }
}

ServerPlayNetworking.registerGlobalReceiver(OverwriteDecisionC2SPayload.TYPE) { payload, context ->
    context.server().execute {
        val be = context.player().level().getBlockEntity(payload.originPos) as? RedstoneSpecBlockEntity ?: return@execute
        val spec = be.spec ?: return@execute
        val structureId = spec.structure ?: return@execute
        val level = be.level as? ServerLevel ?: return@execute
        val dir = saveDir(context.server())
        if (payload.overwrite) {
            StructurePersistence.clearBounds(level, be.blockPos, spec.bounds)
            StructurePersistence.load(dir, structureId, level, be.blockPos, spec.bounds)
            LOGGER.debug("[NetworkRegistry#overwriteDecision] cleared and placed structure '{}'", structureId)
        } else {
            LOGGER.debug("[NetworkRegistry#overwriteDecision] user skipped structure load")
        }
    }
}
```

Add the necessary imports: `SpecPersistence`, `StructurePersistence`, `SharedSettings`, `StructurePromptS2CPayload`, `OverwritePromptS2CPayload`, `OverwriteDecisionC2SPayload`, `StructureDecisionC2SPayload`, `SaveSpecC2SPayload`, `LoadSpecC2SPayload`.

- [ ] **Step 3: Compile**

```bash
./gradlew compileKotlin
```

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/com/breadmoirai/redstonespecs/
git commit -m "feat: wire spec save/load and structure decision handlers into NetworkRegistry"
```

---

### Task 8: `ModConfig` — add spec save directory

**Files:**
- Modify: `src/client/kotlin/com/breadmoirai/redstonespecs/client/config/ModConfig.kt`

- [ ] **Step 1: Add `specSaveDir` field**

Add to the `ModConfig` object:
```kotlin
var specSaveDir: String = "redstonespecs"
```

In `load()`, add:
```kotlin
specSaveDir = json.get("specSaveDir")?.asString ?: "redstonespecs"
```

In `save()`, add:
```kotlin
json.addProperty("specSaveDir", specSaveDir)
```

After setting `SharedSettings.devLevel`, also sync save dir:
```kotlin
SharedSettings.specSaveDir = specSaveDir
```

In `createScreen()`, add an option to the General category:
```kotlin
.option(
    Option.createBuilder<String>()
        .name(Component.literal("Spec Save Directory"))
        .description(OptionDescription.of(
            Component.literal("Folder (relative to world folder) where .json and .nbt spec files are saved.")
        ))
        .binding("redstonespecs", { specSaveDir }, { specSaveDir = it })
        .controller(StringControllerBuilder::create)
        .build()
)
```

- [ ] **Step 2: Compile**

```bash
./gradlew compileKotlin
```

- [ ] **Step 3: Commit**

```bash
git add src/client/kotlin/com/breadmoirai/redstonespecs/client/config/ModConfig.kt
git add src/main/kotlin/com/breadmoirai/redstonespecs/config/DevLevel.kt
git commit -m "feat: add specSaveDir config option; sync to SharedSettings"
```

---

### Task 9: Overhaul `SpecOverviewScreen`

**Files:**
- Modify: `src/client/kotlin/com/breadmoirai/redstonespecs/client/screen/SpecOverviewScreen.kt`
- Modify: `src/client/kotlin/com/breadmoirai/redstonespecs/client/network/ClientNetworkHandler.kt`

- [ ] **Step 1: Rewrite `ClientNetworkHandler.kt`** to handle new prompts

```kotlin
package com.breadmoirai.redstonespecs.client.network

import com.breadmoirai.redstonespecs.client.screen.SpecOverviewScreen
import com.breadmoirai.redstonespecs.client.screen.SpecEditorScreen
import com.breadmoirai.redstonespecs.network.*
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.ConfirmScreen
import net.minecraft.network.chat.Component
import org.slf4j.LoggerFactory

private val LOGGER = LoggerFactory.getLogger("Redstone Specs")

fun registerClientNetworking() {
    ClientPlayNetworking.registerGlobalReceiver(OpenOverviewS2CPayload.TYPE) { payload, context ->
        val mc = context.client()
        mc.execute {
            LOGGER.debug("[ClientNetworkHandler#openOverview] originPos={}", payload.originPos)
            mc.setScreen(SpecOverviewScreen(payload.originPos, payload.availableStructures))
        }
    }

    ClientPlayNetworking.registerGlobalReceiver(OpenEditorS2CPayload.TYPE) { payload, context ->
        val mc = context.client()
        mc.execute {
            LOGGER.debug("[ClientNetworkHandler#openEditor] originPos={} entryRelPos={}", payload.originPos, payload.entryRelPos)
            mc.setScreen(SpecEditorScreen(payload.originPos, payload.entryRelPos))
        }
    }

    ClientPlayNetworking.registerGlobalReceiver(TestResultS2CPayload.TYPE) { payload, context ->
        val mc = context.client()
        mc.execute {
            val r = payload.result
            val color = if (r.pass) "§a" else "§c"
            LOGGER.debug("[ClientNetworkHandler#testResult] originPos={} {}/{} passed", payload.originPos, r.passCount, r.checks.size)
            mc.player?.sendSystemMessage(
                Component.literal("${color}Spec '${r.specId}': ${r.passCount}/${r.checks.size} checks passed")
            )
            val current = mc.screen
            if (current is SpecOverviewScreen && current.originPos == payload.originPos) {
                mc.setScreen(SpecOverviewScreen(payload.originPos, current.availableStructures))
            }
        }
    }

    ClientPlayNetworking.registerGlobalReceiver(BreakpointHitS2CPayload.TYPE) { payload, context ->
        val mc = context.client()
        mc.execute {
            LOGGER.debug("[ClientNetworkHandler#breakpointHit] '{}' in '{}' at {}t {}",
                payload.breakpointLabel, payload.specId, payload.simTime.tick, payload.simTime.phase.name)
            mc.player?.sendSystemMessage(
                Component.literal("§6Breakpoint: §f${payload.breakpointLabel} §7in §f${payload.specId} §7at ${payload.simTime.tick}t ${payload.simTime.phase.name}")
            )
        }
    }

    ClientPlayNetworking.registerGlobalReceiver(StructurePromptS2CPayload.TYPE) { payload, context ->
        val mc = context.client()
        mc.execute {
            LOGGER.debug("[ClientNetworkHandler#structurePrompt] kind={} id={}", payload.promptKind, payload.currentStructureId)
            val title = when (payload.promptKind) {
                "SAVE_OR_FORK" -> "Structure '${payload.currentStructureId}' has changed"
                else -> "Structure file '${payload.currentStructureId}' already exists"
            }
            mc.setScreen(ConfirmScreen(
                { save ->
                    mc.setScreen(null)
                    val decision = if (save) "SAVE" else "CANCEL"
                    ClientPlayNetworking.send(StructureDecisionC2SPayload(
                        payload.originPos, decision, payload.currentStructureId
                    ))
                },
                Component.literal(title),
                Component.literal(if (payload.promptKind == "SAVE_OR_FORK") "Overwrite '${payload.currentStructureId}'?" else "Overwrite existing file?"),
                "Overwrite", "Cancel"
            ))
            // Note: Fork flow requires a text-input screen — implemented as "Cancel + manual rename" for v1
        }
    }

    ClientPlayNetworking.registerGlobalReceiver(OverwritePromptS2CPayload.TYPE) { payload, context ->
        val mc = context.client()
        mc.execute {
            LOGGER.debug("[ClientNetworkHandler#overwritePrompt] specId={}", payload.specId)
            mc.setScreen(ConfirmScreen(
                { overwrite ->
                    mc.setScreen(null)
                    ClientPlayNetworking.send(OverwriteDecisionC2SPayload(payload.originPos, overwrite))
                },
                Component.literal("Blocks found inside bounds"),
                Component.literal("Overwrite existing blocks with structure '${payload.specId}'?"),
                "Overwrite", "Skip Structure"
            ))
        }
    }
}
```

- [ ] **Step 2: Rewrite `SpecOverviewScreen.kt`**

```kotlin
package com.breadmoirai.redstonespecs.client.screen

import com.breadmoirai.redstonespecs.block.RedstoneSpecBlockEntity
import com.breadmoirai.redstonespecs.data.*
import com.breadmoirai.redstonespecs.network.*
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.screens.Screen
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.CommonComponents
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.common.custom.CustomPacketPayload

class SpecOverviewScreen(
    val originPos: BlockPos,
    val availableStructures: List<String>,
) : Screen(Component.translatable("screen.redstonespecs.spec_overview")) {

    private val panelW = 340
    private val panelH = 260
    private val panelX get() = (width - panelW) / 2
    private val panelY get() = (height - panelH) / 2

    private var idEditMode = false
    private var idEditBox: EditBox? = null
    private var structureEditMode = false
    private var structureEditBox: EditBox? = null
    private var entryScrollOffset = 0

    override fun isInGameUi() = true
    override fun isPauseScreen() = false

    override fun init() {
        super.init()
        val x = panelX; val y = panelY
        val spec = getSpec()

        // ID row
        if (idEditMode) {
            idEditBox = EditBox(font, x + 32, y + 14, panelW - 60, 14, Component.empty()).also {
                it.value = spec?.id ?: ""
                addRenderableWidget(it)
            }
            addRenderableWidget(Button.builder(Component.literal("✔")) {
                val newId = idEditBox?.value?.trim()?.takeIf { it.isNotBlank() } ?: return@builder
                sendPacket(SetSpecIdC2SPayload(originPos, newId))
                idEditMode = false; rebuildWidgets()
            }.bounds(x + panelW - 26, y + 14, 18, 14).build())
        } else {
            addRenderableWidget(Button.builder(Component.literal("✎")) {
                idEditMode = true; rebuildWidgets()
            }.bounds(x + panelW - 26, y + 14, 18, 14).build())
        }

        // Mode cycle button
        val modeLabel = when (spec?.mode) {
            SpecMode.SIMPLE -> "Simple"
            SpecMode.TICK_AWARE -> "Tick-Aware"
            SpecMode.UPDATE_AWARE -> "Update-Aware"
            null -> "—"
        }
        addRenderableWidget(Button.builder(Component.literal("◀ $modeLabel ▶")) {
            val current = spec?.mode ?: SpecMode.SIMPLE
            val next = SpecMode.entries[(current.ordinal + 1) % SpecMode.entries.size]
            sendPacket(SetSpecModeC2SPayload(originPos, next))
            rebuildWidgets()
        }.bounds(x + 60, y + 32, 120, 14).build())

        // Lifespan stepper
        addRenderableWidget(Button.builder(Component.literal("−")) {
            val l = (spec?.lifespan ?: 20) - 1
            if (l >= 1) sendPacket(SetLifespanC2SPayload(originPos, l))
            rebuildWidgets()
        }.bounds(x + 60, y + 50, 14, 14).build())
        addRenderableWidget(Button.builder(Component.literal("+")) {
            sendPacket(SetLifespanC2SPayload(originPos, (spec?.lifespan ?: 20) + 1))
            rebuildWidgets()
        }.bounds(x + 100, y + 50, 14, 14).build())

        // Structure field
        if (structureEditMode) {
            structureEditBox = EditBox(font, x + 60, y + 68, panelW - 88, 14, Component.empty()).also {
                it.value = spec?.structure ?: (spec?.id ?: "")
                addRenderableWidget(it)
            }
            addRenderableWidget(Button.builder(Component.literal("✔")) {
                val s = structureEditBox?.value?.trim()
                sendPacket(SetStructureC2SPayload(originPos, s?.ifBlank { null }))
                structureEditMode = false; rebuildWidgets()
            }.bounds(x + panelW - 26, y + 68, 18, 14).build())
        } else {
            addRenderableWidget(Button.builder(Component.literal("✎")) {
                structureEditMode = true; rebuildWidgets()
            }.bounds(x + panelW - 26, y + 68, 18, 14).build())
        }

        // Entry list rows (scrollable, 5 visible, 16px each)
        val entries = spec?.allEntries ?: emptyList()
        val visibleCount = 5
        val listY = y + 90
        val visibleEntries = entries.drop(entryScrollOffset).take(visibleCount)
        visibleEntries.forEachIndexed { i, entry ->
            val rowY = listY + i * 16
            val tag = when (entry) {
                is InputSpec -> "IN"
                is OutputSpec -> "OUT"
                is BreakpointSpec -> "BP"
                is AutoSpec -> "AUTO"
            }
            addRenderableWidget(Button.builder(
                Component.literal("▶ $tag  ${entry.label.ifEmpty { "—" }}  (${entry.pos.x},${entry.pos.y},${entry.pos.z})")
            ) {
                minecraft?.setScreen(SpecEditorScreen(originPos, entry.pos))
            }.bounds(x + 8, rowY, panelW - 16, 14).build())
        }

        // Scroll buttons
        if (entryScrollOffset > 0) {
            addRenderableWidget(Button.builder(Component.literal("▲")) {
                entryScrollOffset--; rebuildWidgets()
            }.bounds(x + panelW - 20, listY, 14, 14).build())
        }
        if (entries.size > entryScrollOffset + visibleCount) {
            addRenderableWidget(Button.builder(Component.literal("▼")) {
                entryScrollOffset++; rebuildWidgets()
            }.bounds(x + panelW - 20, listY + (visibleCount - 1) * 16, 14, 14).build())
        }

        // Bottom buttons: Run, Load, Save, Done
        addRenderableWidget(Button.builder(
            Component.translatable("screen.redstonespecs.spec_overview.run")
        ) { sendPacket(RunSpecC2SPayload(originPos)) }
            .bounds(x + 8, y + panelH - 24, 60, 20).build())

        addRenderableWidget(Button.builder(Component.literal("Load")) {
            val id = spec?.id ?: return@builder
            sendPacket(LoadSpecC2SPayload(originPos, id))
        }.bounds(x + 72, y + panelH - 24, 60, 20).build())

        addRenderableWidget(Button.builder(Component.literal("Save")) {
            sendPacket(SaveSpecC2SPayload(originPos))
        }.bounds(x + 136, y + panelH - 24, 60, 20).build())

        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE) { onClose() }
            .bounds(x + panelW - 66, y + panelH - 24, 58, 20).build())
    }

    override fun extractRenderState(
        extractor: GuiGraphicsExtractor,
        mouseX: Int, mouseY: Int, partialTick: Float,
    ) {
        val x = panelX; val y = panelY
        val spec = getSpec()
        super.extractRenderState(extractor, mouseX, mouseY, partialTick)

        extractor.centeredText(font, title, x + panelW / 2, y + 4, 0xFFFFFFFF.toInt())

        // ID label
        extractor.text(font, Component.literal("ID:"), x + 8, y + 16, 0xFF888888.toInt())
        if (!idEditMode) extractor.text(font, Component.literal(spec?.id ?: ""), x + 32, y + 16, 0xFFFFFFFF.toInt())

        // Mode label
        extractor.text(font, Component.literal("Mode:"), x + 8, y + 34, 0xFF888888.toInt())

        // Lifespan label
        extractor.text(font, Component.literal("Life:"), x + 8, y + 52, 0xFF888888.toInt())
        extractor.text(font, Component.literal("${spec?.lifespan ?: 0}"), x + 78, y + 52, 0xFFFFFFFF.toInt())
        extractor.text(font, Component.literal("ticks"), x + 116, y + 52, 0xFF888888.toInt())

        // Structure label
        extractor.text(font, Component.literal("Struct:"), x + 8, y + 70, 0xFF888888.toInt())
        if (!structureEditMode) {
            extractor.text(font, Component.literal(spec?.structure ?: "(none)"), x + 60, y + 70, 0xFFCCCCCC.toInt())
        }

        // Entry list border
        extractor.fill(x + 6, y + 88, x + panelW - 6, y + 88 + 6 * 16, 0x33FFFFFF.toInt())

        // Last result
        val result = getBe()?.lastTestResult
        if (result != null) {
            val text = if (result.pass) "✓ ${result.passCount}/${result.checks.size} checks passed"
                       else "✗ ${result.checks.size - result.passCount}/${result.checks.size} checks failed"
            val color = if (result.pass) 0xFF44FF88.toInt() else 0xFFFF4444.toInt()
            extractor.text(font, Component.literal(text), x + 8, y + panelH - 46, color)
        }
    }

    override fun onClose() { idEditMode = false; structureEditMode = false; super.onClose() }

    private fun getBe() = minecraft?.level?.getBlockEntity(originPos) as? RedstoneSpecBlockEntity
    private fun getSpec() = getBe()?.spec
    private fun sendPacket(payload: CustomPacketPayload) = ClientPlayNetworking.send(payload)
}
```

- [ ] **Step 3: Update `Redstonespecs.kt` / `RedstoneSpecBlock.kt`** — wherever `OpenOverviewS2CPayload` is constructed, pass the list of available structures. Add an import of `StructurePersistence` and `SharedSettings`.

Find the place where `ServerPlayNetworking.send(player, OpenOverviewS2CPayload(be.blockPos))` is called (likely in `RedstoneSpecBlock.use()`). Replace with:

```kotlin
val dir = server.getWorldPath(LevelResource.ROOT).resolve(SharedSettings.specSaveDir)
val structures = StructurePersistence.listIds(dir)
ServerPlayNetworking.send(player, OpenOverviewS2CPayload(be.blockPos, structures))
```

- [ ] **Step 4: Create spec on first right-click if none exists**

In `RedstoneSpecBlock.use()`, if `be.spec == null`, create a default spec from the player's username:

```kotlin
if (be.spec == null) {
    val defaultId = player.gameProfile.name.lowercase().replace(" ", "_") + "_spec"
    be.setSpec(RedstoneSpec.new(defaultId))
}
```

- [ ] **Step 5: Compile**

```bash
./gradlew compileKotlin
```

- [ ] **Step 6: Commit**

```bash
git add src/client/kotlin/com/breadmoirai/redstonespecs/client/
git add src/main/kotlin/com/breadmoirai/redstonespecs/block/
git commit -m "feat: overhaul SpecOverviewScreen with entry list, mode/lifespan/structure controls, load/save"
```

---

### Task 10: Delete dead code and update tests

**Files to delete:**
- `src/main/kotlin/com/breadmoirai/redstonespecs/data/SpecCase.kt`
- `src/main/kotlin/com/breadmoirai/redstonespecs/runner/AutoSpecRecorder.kt`
- `src/client/kotlin/com/breadmoirai/redstonespecs/client/screen/SpecCasesScreen.kt`
- `src/test/kotlin/com/breadmoirai/redstonespecs/data/SpecCaseTest.kt`

**Files to update:**
- `src/test/kotlin/com/breadmoirai/redstonespecs/data/RedstoneSpecTest.kt`
- `src/test/kotlin/com/breadmoirai/redstonespecs/data/SpecEntryTest.kt` (minor: OutputSpec no longer requires INIT)

- [ ] **Step 1: Delete dead files**

```bash
git rm src/main/kotlin/com/breadmoirai/redstonespecs/data/SpecCase.kt
git rm src/main/kotlin/com/breadmoirai/redstonespecs/runner/AutoSpecRecorder.kt
git rm src/client/kotlin/com/breadmoirai/redstonespecs/client/screen/SpecCasesScreen.kt
git rm src/test/kotlin/com/breadmoirai/redstonespecs/data/SpecCaseTest.kt
```

- [ ] **Step 1b: Fix game test — update `RedstonespecsClientTests.kt`**

Open `src/gametest/kotlin/com/breadmoirai/redstonespecs/test/RedstonespecsClientTests.kt`. Remove any references to `SpecCaseResult`, `specCases`, `activeSpecCaseIndex`, or `caseName`. Replace result assertions to use `TestResult.checks` and `TestResult.pass` directly.

- [ ] **Step 2: Rewrite `RedstoneSpecTest.kt`**

```kotlin
package com.breadmoirai.redstonespecs.data

import net.minecraft.SharedConstants
import net.minecraft.core.BlockPos
import net.minecraft.nbt.NbtOps
import net.minecraft.server.Bootstrap
import net.minecraft.world.level.levelgen.structure.BoundingBox
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RedstoneSpecTest {

    @BeforeAll
    fun bootstrap() {
        SharedConstants.tryDetectVersion()
        Bootstrap.bootStrap()
    }

    private val initEntries = listOf(SimTime.INIT to StateCondition.BoolProperty("powered", false))

    private fun roundtrip(value: RedstoneSpec): RedstoneSpec {
        val encoded = RedstoneSpec.CODEC.encodeStart(NbtOps.INSTANCE, value).getOrThrow()
        return RedstoneSpec.CODEC.parse(NbtOps.INSTANCE, encoded).getOrThrow()
    }

    @Test
    fun `empty RedstoneSpec roundtrip`() {
        val spec = RedstoneSpec.new("test-spec")
        assertEquals(spec, roundtrip(spec))
    }

    @Test
    fun `mode roundtrip - all modes`() {
        for (mode in SpecMode.entries) {
            val spec = RedstoneSpec.new("spec").copy(mode = mode)
            assertEquals(mode, roundtrip(spec).mode)
        }
    }

    @Test
    fun `id preserved across roundtrip`() {
        val spec = RedstoneSpec.new("my-circuit")
        assertEquals("my-circuit", roundtrip(spec).id)
    }

    @Test
    fun `lifespan preserved`() {
        val spec = RedstoneSpec.new("spec").copy(lifespan = 42)
        assertEquals(42, roundtrip(spec).lifespan)
    }

    @Test
    fun `structure nullable roundtrip`() {
        val withStructure = RedstoneSpec.new("spec").copy(structure = "shared_counter")
        assertEquals("shared_counter", roundtrip(withStructure).structure)
        val noStructure = RedstoneSpec.new("spec")
        assertNull(roundtrip(noStructure).structure)
    }

    @Test
    fun `bounds preserved`() {
        val bounds = BoundingBox(-3, 60, -3, 12, 65, 12)
        val spec = RedstoneSpec.new("spec").copy(bounds = bounds)
        assertEquals(bounds, roundtrip(spec).bounds)
    }

    @Test
    fun `spec with inputs and outputs roundtrip`() {
        val endEntries = listOf(SimTime(8, Phase.END_OF_TICK) to StateCondition.BoolProperty("lit", true))
        val spec = RedstoneSpec.new("lever-lamp").copy(
            lifespan = 8,
            inputs = listOf(InputSpec(BlockPos(1, 0, 0), "lever", 0x4488FF, initEntries)),
            outputs = listOf(OutputSpec(BlockPos(3, 0, 0), "lamp", 0x44FF88, endEntries)),
        )
        assertEquals(spec, roundtrip(spec))
    }

    @Test
    fun `TestResult codec roundtrip`() {
        val result = TestResult(
            specId = "my-spec",
            timestamp = 1000L,
            checks = listOf(
                TickCheck(SimTime(0, Phase.END_OF_TICK), "lamp.lit", "true", "true", pass = true),
                TickCheck(SimTime(1, Phase.END_OF_TICK), "lamp.lit", "true", "false", pass = false),
            ),
        )
        val encoded = TestResult.CODEC.encodeStart(NbtOps.INSTANCE, result).getOrThrow()
        val decoded = TestResult.CODEC.parse(NbtOps.INSTANCE, encoded).getOrThrow()
        assertEquals(result, decoded)
    }
}
```

- [ ] **Step 3: Run all tests**

```bash
./gradlew test
```
Expected: PASS (the SpecCaseTest is deleted; remaining tests should pass).

- [ ] **Step 4: Commit**

```bash
git add -p
git commit -m "chore: delete SpecCase, AutoSpecRecorder, SpecCasesScreen; update tests for new data model"
```

---

### Task 11: Final compile + smoke check

- [ ] **Step 1: Full build**

```bash
./gradlew build
```
Expected: BUILD SUCCESSFUL. Fix any remaining errors.

- [ ] **Step 2: Commit any final fixes**

```bash
git add -p
git commit -m "fix: address final compile issues after workflow v1 redesign"
```
