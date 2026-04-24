# Persistence Model v1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace manual Save/Load buttons with automatic spec JSON persistence on every change, add a Run button that auto-saves the structure before each test, clean up the UI, and add a file-browser Load flow (v1.2).

**Architecture:** `@AutoEmit` on `RedstoneSpec` generates a `RedstoneSpecEmitter`. The block entity holds this emitter and launches a coroutine that collects the emitter's `StateFlow`, calling `SpecPersistence.save()` on `Dispatchers.IO` for every emission. An `isLoading` flag suppresses saves during `loadAdditional`. All three screens use `this.height` for dynamic vertical layout.

**Tech Stack:** Kotlin, Minecraft Fabric (MC 26.1), `fabric-language-kotlin` (provides `kotlinx.coroutines`), `auto-emit 0.1.0` (KSP), Gson/Mojang Codec

---

## File Map

| File | Change |
|------|--------|
| `src/main/kotlin/.../data/RedstoneSpec.kt` | Add `@AutoEmit` |
| `src/main/kotlin/.../block/RedstoneSpecBlockEntity.kt` | Replace `var spec` with emitter + coroutine auto-save |
| `src/main/kotlin/.../block/RedstoneSpecBlock.kt` | Simplify `useWithoutItem` — drop `can*` permission flags |
| `src/main/kotlin/.../network/Packets.kt` | Remove obsolete packets; simplify `OpenOverviewS2CPayload`; add v1.2 browser packets |
| `src/main/kotlin/.../network/NetworkRegistry.kt` | Remove old handlers; update RunSpec; fix SetSpecId co-update; add v1.2 handler |
| `src/client/kotlin/.../client/network/ClientNetworkHandler.kt` | Remove old S2C handlers; add v1.2 browser handler |
| `src/client/kotlin/.../client/screen/SpecOverviewScreen.kt` | Remove struct row + old buttons; add Run; dynamic height |
| `src/client/kotlin/.../client/screen/SpecEditorScreen.kt` | Dynamic scroll height |
| `src/client/kotlin/.../client/screen/SpecBoundsScreen.kt` | Dynamic vertical centering |
| `src/client/kotlin/.../client/screen/SpecFileBrowserScreen.kt` | Create — v1.2 file browser screen |

---

## Task 1: Add @AutoEmit to RedstoneSpec and verify code generation

**Files:**
- Modify: `src/main/kotlin/com/breadmoirai/redstonespecs/data/RedstoneSpec.kt`

- [ ] **Step 1: Add the @AutoEmit annotation**

Open `src/main/kotlin/com/breadmoirai/redstonespecs/data/RedstoneSpec.kt`. Add the import and annotation:

```kotlin
package com.breadmoirai.redstonespecs.data

import com.livefront.annotation.AutoEmit  // add this import
import com.mojang.serialization.Codec
// ... rest of existing imports unchanged

@AutoEmit  // add this line
data class RedstoneSpec(
    // ... all existing fields unchanged
)
```

- [ ] **Step 2: Compile and inspect the generated emitter**

Run:
```
cmd.exe /c "./gradlew.bat :26.1:compileKotlin"
```
Expected: BUILD SUCCESSFUL

Then find the generated file:
```
find versions/26.1/build -name "RedstoneSpecEmitter.kt" 2>/dev/null
```

Open the generated file and note: the exact property names on the emitter and whether it exposes `stateFlow: StateFlow<RedstoneSpec>` or `asStateFlow()`. The generated class is expected to look like:

```kotlin
class RedstoneSpecEmitter(initial: RedstoneSpec) {
    private val _state = MutableStateFlow(initial)
    val stateFlow: StateFlow<RedstoneSpec> = _state.asStateFlow()
    val value: RedstoneSpec get() = _state.value
    var id: String
        get() = _state.value.id
        set(v) { _state.update { it.copy(id = v) } }
    // ... and so on for each field
}
fun RedstoneSpec.emitter(): RedstoneSpecEmitter = RedstoneSpecEmitter(this)
```

If the property names, `stateFlow` accessor, or `value` property differ from this, update all subsequent tasks accordingly before continuing.

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/com/breadmoirai/redstonespecs/data/RedstoneSpec.kt
git commit -m "feat: annotate RedstoneSpec with @AutoEmit for reactive state emission"
```

---

## Task 2: Migrate RedstoneSpecBlockEntity to emitter + coroutine auto-save

**Files:**
- Modify: `src/main/kotlin/com/breadmoirai/redstonespecs/block/RedstoneSpecBlockEntity.kt`

- [ ] **Step 1: Replace the file contents**

> **Note on the design:** `loadAdditional` creates a fresh emitter (no `isLoading` flag needed — the `drop(1)` on the new emitter's `stateFlow` skips its initial value). `setSpec` for a *new* emitter (first placement) calls `triggerSave` directly since `drop(1)` would suppress that initial value. `setSpec` with an *existing* emitter uses `updateFrom` which triggers the collector naturally — `StateFlow` deduplicates structurally equal values via `equals()`, so only the actually-changed field(s) emit.

Replace the entire file with:

```kotlin
package com.breadmoirai.redstonespecs.block

import com.breadmoirai.redstonespecs.ModRegistries
import com.breadmoirai.redstonespecs.config.SharedSettings
import com.breadmoirai.redstonespecs.data.RedstoneSpec
import com.breadmoirai.redstonespecs.data.RedstoneSpecEmitter
import com.breadmoirai.redstonespecs.data.SpecEntry
import com.breadmoirai.redstonespecs.data.SpecMode
import com.breadmoirai.redstonespecs.data.TestResult
import com.breadmoirai.redstonespecs.persistence.SpecPersistence
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.minecraft.core.BlockPos
import net.minecraft.core.HolderLookup
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientGamePacketListener
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.storage.LevelResource
import net.minecraft.world.level.storage.ValueInput
import net.minecraft.world.level.storage.ValueOutput
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

class RedstoneSpecBlockEntity(pos: BlockPos, state: BlockState) :
    BlockEntity(ModRegistries.REDSTONE_SPEC_BLOCK_ENTITY_TYPE, pos, state) {

    private var specEmitter: RedstoneSpecEmitter? = null
    private val coroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var collectorJob: Job? = null

    val spec: RedstoneSpec? get() = specEmitter?.value

    var lastTestResult: TestResult? = null
        private set

    fun setSpec(newSpec: RedstoneSpec) {
        LOGGER.debug("[RedstoneSpecBlockEntity#setSpec] setting spec '{}' at {}", newSpec.id, blockPos)
        val e = specEmitter
        if (e == null) {
            // First call: emitter not yet created. Create it, start collecting (drop(1) skips
            // the initial value), then manually save since that initial value won't go through
            // the collector.
            specEmitter = newSpec.emitter()
            tryStartCollecting()
            triggerSave(newSpec)
        } else {
            // Emitter exists: updateFrom triggers StateFlow emissions for any changed fields.
            // The collector picks these up and saves. StateFlow deduplicates via equals(), so
            // only genuinely changed fields emit.
            e.updateFrom(newSpec)
        }
        setChangedAndSync()
    }

    fun setSpecId(id: String) {
        val e = specEmitter ?: return
        e.id = id
        setChangedAndSync()
    }

    fun setMode(mode: SpecMode) {
        val e = specEmitter ?: return
        e.mode = mode
        setChangedAndSync()
    }

    fun setLifespan(lifespan: Int) {
        val e = specEmitter ?: return
        e.lifespan = lifespan
        setChangedAndSync()
    }

    fun setStructure(structure: String?) {
        val e = specEmitter ?: return
        e.structure = structure
        setChangedAndSync()
    }

    fun setLastTestResult(result: TestResult) {
        lastTestResult = result
        setChangedAndSync()
    }

    fun addOrUpdateEntry(entry: SpecEntry) {
        LOGGER.debug("[RedstoneSpecBlockEntity#addOrUpdateEntry] pos={} type={}", entry.pos, entry.javaClass.simpleName)
        val e = specEmitter ?: return
        e.updateFrom(e.value.withEntryAddedOrUpdated(entry))
        setChangedAndSync()
    }

    fun removeEntry(pos: BlockPos): SpecEntry? {
        LOGGER.debug("[RedstoneSpecBlockEntity#removeEntry] pos={}", pos)
        val e = specEmitter ?: return null
        val s = e.value
        val removed = s.entryAt(pos) ?: return null
        e.updateFrom(s.withEntryRemoved(pos))
        setChangedAndSync()
        return removed
    }

    override fun setLevel(level: Level) {
        super.setLevel(level)
        register(this)
        tryStartCollecting()
    }

    override fun setRemoved() {
        super.setRemoved()
        coroutineScope.cancel()
        level?.let { registry[it]?.remove(blockPos) }
    }

    private fun tryStartCollecting() {
        val e = specEmitter ?: return
        val lv = level ?: return
        collectorJob?.cancel()
        collectorJob = coroutineScope.launch {
            e.stateFlow.drop(1).collect { spec ->
                val serverLevel = lv as? ServerLevel ?: return@collect
                val saveDir = serverLevel.server
                    .getWorldPath(LevelResource.ROOT)
                    .resolve(SharedSettings.specSaveDir)
                withContext(Dispatchers.IO) {
                    SpecPersistence.save(saveDir, spec)
                }
            }
        }
    }

    private fun triggerSave(spec: RedstoneSpec) {
        val serverLevel = level as? ServerLevel ?: return
        val saveDir = serverLevel.server
            .getWorldPath(LevelResource.ROOT)
            .resolve(SharedSettings.specSaveDir)
        coroutineScope.launch(Dispatchers.IO) {
            SpecPersistence.save(saveDir, spec)
        }
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
        val loaded = input.read("spec", RedstoneSpec.CODEC).orElse(null)
        lastTestResult = input.read("last_test_result", TestResult.CODEC).orElse(null)
        LOGGER.debug("[RedstoneSpecBlockEntity#loadAdditional] loaded at {} spec='{}'", blockPos, loaded?.id)
        if (loaded == null) return
        // Cancel any running collector, create a fresh emitter, restart collector.
        // The fresh emitter's initial state (loaded) is skipped by drop(1) — no spurious
        // disk write on world load or chunk reload.
        collectorJob?.cancel()
        specEmitter = loaded.emitter()
        if (level != null) tryStartCollecting()
    }

    override fun getUpdateTag(registries: HolderLookup.Provider): CompoundTag =
        saveWithoutMetadata(registries)

    override fun getUpdatePacket(): Packet<ClientGamePacketListener> =
        ClientboundBlockEntityDataPacket.create(this)
}

private fun RedstoneSpecEmitter.updateFrom(spec: RedstoneSpec) {
    id = spec.id
    mode = spec.mode
    bounds = spec.bounds
    lifespan = spec.lifespan
    structure = spec.structure
    inputs = spec.inputs
    outputs = spec.outputs
    breakpoints = spec.breakpoints
    autoSpecs = spec.autoSpecs
}
```

- [ ] **Step 2: Compile**

```
cmd.exe /c "./gradlew.bat :26.1:compileKotlin"
```
Expected: BUILD SUCCESSFUL. Fix any import or property-name mismatches against the generated emitter you inspected in Task 1.

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/com/breadmoirai/redstonespecs/block/RedstoneSpecBlockEntity.kt
git commit -m "feat: migrate block entity to @AutoEmit emitter with coroutine auto-save"
```

---

## Task 3: Simplify OpenOverviewS2CPayload and update RedstoneSpecBlock

**Files:**
- Modify: `src/main/kotlin/com/breadmoirai/redstonespecs/network/Packets.kt`
- Modify: `src/main/kotlin/com/breadmoirai/redstonespecs/block/RedstoneSpecBlock.kt`

- [ ] **Step 1: Replace OpenOverviewS2CPayload with a minimal version**

In `Packets.kt`, find the `OpenOverviewS2CPayload` class (lines 17–40) and replace it with:

```kotlin
data class OpenOverviewS2CPayload(
    val originPos: BlockPos,
) : CustomPacketPayload {
    companion object {
        val TYPE = CustomPacketPayload.Type<OpenOverviewS2CPayload>(
            Identifier.fromNamespaceAndPath("redstonespecs", "open_overview")
        )
        val STREAM_CODEC: StreamCodec<ByteBuf, OpenOverviewS2CPayload> = StreamCodec.composite(
            BlockPos.STREAM_CODEC, OpenOverviewS2CPayload::originPos,
            ::OpenOverviewS2CPayload,
        )
    }
    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE
}
```

- [ ] **Step 2: Simplify RedstoneSpecBlock.useWithoutItem**

Replace the entire body of `useWithoutItem` in `RedstoneSpecBlock.kt`:

```kotlin
override fun useWithoutItem(
    state: BlockState,
    level: Level,
    pos: BlockPos,
    player: Player,
    hit: BlockHitResult,
): InteractionResult {
    if (!level.isClientSide) {
        val be = level.getBlockEntity(pos) as? RedstoneSpecBlockEntity ?: return InteractionResult.PASS
        val serverPlayer = player as ServerPlayer
        if (be.spec == null) {
            val defaultId = serverPlayer.gameProfile.name.lowercase().replace(" ", "_") + "_spec"
            be.setSpec(RedstoneSpec.new(defaultId))
        }
        ServerPlayNetworking.send(serverPlayer, OpenOverviewS2CPayload(be.blockPos))
    }
    return InteractionResult.SUCCESS
}
```

Also remove unused imports from `RedstoneSpecBlock.kt` (`SpecPersistence`, `StructurePersistence`, `kotlin.io.path.exists`).

- [ ] **Step 3: Compile**

```
cmd.exe /c "./gradlew.bat :26.1:compileKotlin"
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/com/breadmoirai/redstonespecs/network/Packets.kt
git add src/main/kotlin/com/breadmoirai/redstonespecs/block/RedstoneSpecBlock.kt
git commit -m "feat: simplify OpenOverviewS2CPayload and RedstoneSpecBlock — drop can* flags"
```

---

## Task 4: Remove obsolete packets from Packets.kt

**Files:**
- Modify: `src/main/kotlin/com/breadmoirai/redstonespecs/network/Packets.kt`

- [ ] **Step 1: Delete the following packet classes entirely**

Remove these classes from `Packets.kt` (they are no longer needed):
- `StructurePromptS2CPayload` (lines 97–116)
- `SaveSpecC2SPayload` (lines 322–334)
- `LoadSpecC2SPayload` (lines 336–349)
- `SaveStructureC2SPayload` (lines 351–362)
- `LoadStructureC2SPayload` (lines 364–375)
- `StructureDecisionC2SPayload` (lines 377–396)

Keep `OverwritePromptS2CPayload` and `OverwriteDecisionC2SPayload` — they are still used by the v1.2 load-from-file flow.

- [ ] **Step 2: Compile**

```
cmd.exe /c "./gradlew.bat :26.1:compileKotlin"
```
Expected: compile errors in `NetworkRegistry.kt` and `ClientNetworkHandler.kt` referencing the deleted classes. That is expected — they will be fixed in Task 5.

- [ ] **Step 3: Commit (with broken state noted)**

```bash
git add src/main/kotlin/com/breadmoirai/redstonespecs/network/Packets.kt
git commit -m "chore: remove obsolete save/load spec and structure packets"
```

---

## Task 5: Rewrite NetworkRegistry to remove old handlers and update RunSpec

**Files:**
- Modify: `src/main/kotlin/com/breadmoirai/redstonespecs/network/NetworkRegistry.kt`

- [ ] **Step 1: Replace the entire file contents**

```kotlin
package com.breadmoirai.redstonespecs.network

import com.breadmoirai.redstonespecs.block.RedstoneSpecBlockEntity
import com.breadmoirai.redstonespecs.config.SharedSettings
import com.breadmoirai.redstonespecs.item.UndoStack
import com.breadmoirai.redstonespecs.persistence.StructurePersistence
import com.breadmoirai.redstonespecs.runner.SpecRunnerCoordinator
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.storage.LevelResource
import org.slf4j.LoggerFactory

private val LOGGER = LoggerFactory.getLogger("Redstone Specs")

private fun saveDir(server: net.minecraft.server.MinecraftServer): java.nio.file.Path =
    server.getWorldPath(LevelResource.ROOT)
        .resolve(SharedSettings.specSaveDir)

fun registerNetworking() {
    // S2C registrations
    PayloadTypeRegistry.clientboundPlay().register(OpenOverviewS2CPayload.TYPE, OpenOverviewS2CPayload.STREAM_CODEC)
    PayloadTypeRegistry.clientboundPlay().register(OpenEditorS2CPayload.TYPE, OpenEditorS2CPayload.STREAM_CODEC)
    PayloadTypeRegistry.clientboundPlay().register(TestResultS2CPayload.TYPE, TestResultS2CPayload.STREAM_CODEC)
    PayloadTypeRegistry.clientboundPlay().register(BreakpointHitS2CPayload.TYPE, BreakpointHitS2CPayload.STREAM_CODEC)
    PayloadTypeRegistry.clientboundPlay().register(OverwritePromptS2CPayload.TYPE, OverwritePromptS2CPayload.STREAM_CODEC)

    // C2S registrations
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
    PayloadTypeRegistry.serverboundPlay().register(OverwriteDecisionC2SPayload.TYPE, OverwriteDecisionC2SPayload.STREAM_CODEC)

    // C2S handlers
    ServerPlayNetworking.registerGlobalReceiver(UndoC2SPayload.TYPE) { _, context ->
        val player = context.player()
        context.server().execute {
            val record = UndoStack.pop(player.uuid) ?: return@execute
            LOGGER.debug("[NetworkRegistry#undo] player={} restoring entry", player.name.string)
            val be = player.level().getBlockEntity(record.originPos) as? RedstoneSpecBlockEntity ?: return@execute
            be.addOrUpdateEntry(record.entry)
        }
    }

    ServerPlayNetworking.registerGlobalReceiver(RunSpecC2SPayload.TYPE) { payload, context ->
        context.server().execute {
            LOGGER.debug("[NetworkRegistry#runSpec] originPos={}", payload.originPos)
            val be = context.player().level().getBlockEntity(payload.originPos) as? RedstoneSpecBlockEntity ?: return@execute
            val spec = be.spec ?: return@execute
            val level = be.level as? ServerLevel ?: return@execute
            val dir = saveDir(context.server())
            val structureId = spec.structure ?: spec.id
            StructurePersistence.save(dir, structureId, level, be.blockPos, spec.bounds)
            LOGGER.debug("[NetworkRegistry#runSpec] auto-saved structure '{}' before run", structureId)
            SpecRunnerCoordinator.startRun(be)
        }
    }

    ServerPlayNetworking.registerGlobalReceiver(ResetSpecC2SPayload.TYPE) { payload, context ->
        context.server().execute {
            LOGGER.debug("[NetworkRegistry#resetSpec] originPos={}", payload.originPos)
            val be = context.player().level().getBlockEntity(payload.originPos) as? RedstoneSpecBlockEntity ?: return@execute
            SpecRunnerCoordinator.resetSpec(be)
        }
    }

    ServerPlayNetworking.registerGlobalReceiver(ResumeSpecC2SPayload.TYPE) { payload, context ->
        context.server().execute {
            LOGGER.debug("[NetworkRegistry#resumeSpec] originPos={}", payload.originPos)
            val be = context.player().level().getBlockEntity(payload.originPos) as? RedstoneSpecBlockEntity ?: return@execute
            SpecRunnerCoordinator.resumeSpec(be)
        }
    }

    ServerPlayNetworking.registerGlobalReceiver(SaveSpecEntryC2SPayload.TYPE) { payload, context ->
        context.server().execute {
            LOGGER.debug("[NetworkRegistry#saveSpecEntry] originPos={} pos={}", payload.originPos, payload.entry.pos)
            val be = context.player().level().getBlockEntity(payload.originPos) as? RedstoneSpecBlockEntity ?: return@execute
            be.addOrUpdateEntry(payload.entry)
        }
    }

    ServerPlayNetworking.registerGlobalReceiver(RemoveSpecEntryC2SPayload.TYPE) { payload, context ->
        val player = context.player()
        context.server().execute {
            LOGGER.debug("[NetworkRegistry#removeSpecEntry] originPos={} entryPos={}", payload.originPos, payload.entryRelPos)
            val be = player.level().getBlockEntity(payload.originPos) as? RedstoneSpecBlockEntity ?: return@execute
            val removed = be.removeEntry(payload.entryRelPos) ?: return@execute
            UndoStack.push(player.uuid, UndoStack.UndoRecord(payload.originPos, removed))
        }
    }

    ServerPlayNetworking.registerGlobalReceiver(SetSpecIdC2SPayload.TYPE) { payload, context ->
        context.server().execute {
            LOGGER.debug("[NetworkRegistry#setSpecId] originPos={} id='{}'", payload.originPos, payload.id)
            val be = context.player().level().getBlockEntity(payload.originPos) as? RedstoneSpecBlockEntity ?: return@execute
            if (payload.id.isBlank()) return@execute
            val spec = be.spec ?: return@execute
            val oldId = spec.id
            be.setSpecId(payload.id)
            // Co-update structure if it was auto-named after the old spec id
            if (spec.structure == oldId || spec.structure == null) {
                be.setStructure(payload.id)
            }
        }
    }

    ServerPlayNetworking.registerGlobalReceiver(SetSpecModeC2SPayload.TYPE) { payload, context ->
        context.server().execute {
            LOGGER.debug("[NetworkRegistry#setSpecMode] originPos={} mode={}", payload.originPos, payload.mode)
            val be = context.player().level().getBlockEntity(payload.originPos) as? RedstoneSpecBlockEntity ?: return@execute
            be.setMode(payload.mode)
        }
    }

    ServerPlayNetworking.registerGlobalReceiver(SetLifespanC2SPayload.TYPE) { payload, context ->
        context.server().execute {
            LOGGER.debug("[NetworkRegistry#setLifespan] originPos={} lifespan={}", payload.originPos, payload.lifespan)
            val be = context.player().level().getBlockEntity(payload.originPos) as? RedstoneSpecBlockEntity ?: return@execute
            if (payload.lifespan >= 1) be.setLifespan(payload.lifespan)
        }
    }

    ServerPlayNetworking.registerGlobalReceiver(SetStructureC2SPayload.TYPE) { payload, context ->
        context.server().execute {
            LOGGER.debug("[NetworkRegistry#setStructure] originPos={} structure={}", payload.originPos, payload.structure)
            val be = context.player().level().getBlockEntity(payload.originPos) as? RedstoneSpecBlockEntity ?: return@execute
            be.setStructure(payload.structure)
        }
    }

    ServerPlayNetworking.registerGlobalReceiver(ResizeBoundsC2SPayload.TYPE) { payload, context ->
        context.server().execute {
            LOGGER.debug("[NetworkRegistry#resizeBounds] originPos={} bounds={}", payload.originPos, payload.bounds)
            val be = context.player().level().getBlockEntity(payload.originPos) as? RedstoneSpecBlockEntity ?: return@execute
            val spec = be.spec ?: return@execute
            be.setSpec(spec.copy(bounds = payload.bounds))
        }
    }

    ServerPlayNetworking.registerGlobalReceiver(NudgeSpecBoundsC2SPayload.TYPE) { payload, context ->
        context.server().execute {
            LOGGER.debug("[NetworkRegistry#nudgeSpecBounds] originPos={} axis={} isMax={} delta={}", payload.originPos, payload.axis, payload.isMax, payload.delta)
            val be = context.player().level().getBlockEntity(payload.originPos) as? RedstoneSpecBlockEntity ?: return@execute
            val spec = be.spec ?: return@execute
            be.setSpec(spec.copy(bounds = nudgeBounds(spec.bounds, payload.axis, payload.isMax, payload.delta)))
        }
    }

    ServerPlayNetworking.registerGlobalReceiver(OverwriteDecisionC2SPayload.TYPE) { payload, context ->
        context.server().execute {
            val be = context.player().level().getBlockEntity(payload.originPos) as? RedstoneSpecBlockEntity ?: return@execute
            val spec = be.spec ?: return@execute
            val structureId = spec.structure ?: spec.id
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
}
```

- [ ] **Step 2: Compile**

```
cmd.exe /c "./gradlew.bat :26.1:compileKotlin"
```
Expected: BUILD SUCCESSFUL (the only remaining compile errors should be in `ClientNetworkHandler.kt` referencing `StructurePromptS2CPayload` and `StructureDecisionC2SPayload`).

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/com/breadmoirai/redstonespecs/network/NetworkRegistry.kt
git commit -m "feat: update NetworkRegistry — auto-save structure on run, remove old save/load handlers"
```

---

## Task 6: Update ClientNetworkHandler — remove dead handlers

**Files:**
- Modify: `src/client/kotlin/com/breadmoirai/redstonespecs/client/network/ClientNetworkHandler.kt`

- [ ] **Step 1: Replace the entire file contents**

```kotlin
package com.breadmoirai.redstonespecs.client.network

import com.breadmoirai.redstonespecs.client.screen.SpecEditorScreen
import com.breadmoirai.redstonespecs.client.screen.SpecOverviewScreen
import com.breadmoirai.redstonespecs.network.*
import it.unimi.dsi.fastutil.booleans.BooleanConsumer
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.minecraft.client.gui.screens.ConfirmScreen
import net.minecraft.network.chat.Component
import org.slf4j.LoggerFactory

private val LOGGER = LoggerFactory.getLogger("Redstone Specs")

fun registerClientNetworking() {
    ClientPlayNetworking.registerGlobalReceiver(OpenOverviewS2CPayload.TYPE) { payload, context ->
        val mc = context.client()
        mc.execute {
            LOGGER.debug("[ClientNetworkHandler#openOverview] originPos={}", payload.originPos)
            mc.setScreen(SpecOverviewScreen(payload.originPos))
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
                mc.setScreen(SpecOverviewScreen(payload.originPos))
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

    ClientPlayNetworking.registerGlobalReceiver(OverwritePromptS2CPayload.TYPE) { payload, context ->
        val mc = context.client()
        mc.execute {
            LOGGER.debug("[ClientNetworkHandler#overwritePrompt] specId={}", payload.specId)
            mc.setScreen(ConfirmScreen(
                BooleanConsumer { overwrite ->
                    mc.setScreen(null)
                    ClientPlayNetworking.send(OverwriteDecisionC2SPayload(payload.originPos, overwrite))
                },
                Component.literal("Blocks found inside bounds"),
                Component.literal("Overwrite existing blocks with structure '${payload.specId}'?"),
                Component.literal("Overwrite"),
                Component.literal("Skip Structure"),
            ))
        }
    }
}
```

- [ ] **Step 2: Compile**

```
cmd.exe /c "./gradlew.bat :26.1:compileKotlin"
```
Expected: BUILD SUCCESSFUL. The `SpecOverviewScreen` constructor call `SpecOverviewScreen(payload.originPos)` may fail if the screen still has the old `can*` parameters — that will be fixed in Task 7.

- [ ] **Step 3: Commit**

```bash
git add src/client/kotlin/com/breadmoirai/redstonespecs/client/network/ClientNetworkHandler.kt
git commit -m "feat: remove StructurePrompt client handler, simplify OpenOverview handler"
```

---

## Task 7: Rewrite SpecOverviewScreen — v1 UI

**Files:**
- Modify: `src/client/kotlin/com/breadmoirai/redstonespecs/client/screen/SpecOverviewScreen.kt`

- [ ] **Step 1: Replace the entire file contents**

```kotlin
package com.breadmoirai.redstonespecs.client.screen

import com.breadmoirai.redstonespecs.block.RedstoneSpecBlockEntity
import com.breadmoirai.redstonespecs.data.*
import com.breadmoirai.redstonespecs.network.*
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.CycleButton
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.components.ScrollableLayout
import net.minecraft.client.gui.components.StringWidget
import net.minecraft.client.gui.layouts.FrameLayout
import net.minecraft.client.gui.layouts.LinearLayout
import net.minecraft.client.gui.layouts.SpacerElement
import net.minecraft.client.gui.screens.Screen
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.CommonComponents
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.common.custom.CustomPacketPayload

class SpecOverviewScreen(
    val originPos: BlockPos,
) : Screen(Component.translatable("screen.redstonespecs.spec_overview")) {

    private var idEditMode = false
    private var lifespanBox: IntEditBox? = null

    override fun isPauseScreen() = false
    override fun isInGameUi() = true

    override fun init() {
        super.init()
        val spec = getSpec()

        val content = LinearLayout.vertical().spacing(4)

        // Title
        content.addChild(StringWidget(Component.translatable("screen.redstonespecs.spec_overview"), font))
        content.addChild(SpacerElement(0, 4))

        // ID row
        val idRow = LinearLayout.horizontal().spacing(4)
        idRow.addChild(StringWidget(40, 20, Component.literal("ID:"), font))
        if (idEditMode) {
            val idBox = EditBox(font, 180, 20, Component.empty())
            idBox.value = spec?.id ?: ""
            idRow.addChild(idBox)
            idRow.addChild(Button.builder(Component.literal("✔")) {
                val newId = idBox.value.trim().takeIf { it.isNotBlank() }
                if (newId != null) { sendPacket(SetSpecIdC2SPayload(originPos, newId)); idEditMode = false; rebuildWidgets() }
            }.pos(0, 0).width(20).build())
        } else {
            idRow.addChild(StringWidget(180, 20, Component.literal(spec?.id ?: ""), font))
            idRow.addChild(Button.builder(Component.literal("✎")) {
                idEditMode = true; rebuildWidgets()
            }.pos(0, 0).width(20).build())
        }
        content.addChild(idRow)

        // Mode row
        val modeRow = LinearLayout.horizontal().spacing(4)
        modeRow.addChild(StringWidget(40, 20, Component.literal("Mode:"), font))
        val modeButton = CycleButton.builder<SpecMode>(
            { mode ->
                Component.literal(when (mode) {
                    SpecMode.SIMPLE -> "Simple"
                    SpecMode.TICK_AWARE -> "Tick-Aware"
                    SpecMode.UPDATE_AWARE -> "Update-Aware"
                })
            },
            spec?.mode ?: SpecMode.SIMPLE,
        ).withValues(*SpecMode.entries.toTypedArray())
            .displayOnlyValue()
            .create(0, 0, 180, 20, Component.empty()) { _, value ->
                sendPacket(SetSpecModeC2SPayload(originPos, value))
            }
        modeRow.addChild(modeButton)
        content.addChild(modeRow)

        // Lifespan row
        val lifespanRow = LinearLayout.horizontal().spacing(4)
        lifespanRow.addChild(StringWidget(40, 20, Component.literal("Life:"), font))
        val box = IntEditBox(font, 100, 20, 1, Int.MAX_VALUE, spec?.lifespan ?: 20, onChange = {})
        lifespanBox = box
        val decBtn = Button.builder(Component.literal("−")) {
            box.setIntValue(box.getIntValue() - 1)
            sendPacket(SetLifespanC2SPayload(originPos, box.getIntValue()))
        }.pos(0, 0).width(20).build()
        val incBtn = Button.builder(Component.literal("+")) {
            box.setIntValue(box.getIntValue() + 1)
            sendPacket(SetLifespanC2SPayload(originPos, box.getIntValue()))
        }.pos(0, 0).width(20).build()
        lifespanRow.addChild(box)
        lifespanRow.addChild(decBtn)
        lifespanRow.addChild(incBtn)
        content.addChild(lifespanRow)

        content.addChild(SpacerElement(0, 4))

        // Entry list — dynamic height: consumes all remaining vertical space
        val entries = spec?.allEntries ?: emptyList()
        val entryListContent = LinearLayout.vertical().spacing(2)
        entries.forEach { entry ->
            val tag = when (entry) {
                is InputSpec -> "IN"
                is OutputSpec -> "OUT"
                is BreakpointSpec -> "BP"
                is AutoSpec -> "AUTO"
            }
            val label = Component.literal("► $tag  ${entry.label.ifEmpty { "—" }}  (${entry.pos.x},${entry.pos.y},${entry.pos.z})")
                .withStyle { it.withColor(entry.color) }
            entryListContent.addChild(Button.builder(label) {
                minecraft.setScreen(SpecEditorScreen(originPos, entry.pos))
            }.pos(0, 0).width(240).build())
        }
        if (entries.isEmpty()) {
            entryListContent.addChild(StringWidget(240, 18, Component.literal("(no entries)"), font))
        }

        // Fixed height rows above and below the list:
        // title(9) + spacer(4) + idRow(20) + spacer(4) + modeRow(20) + spacer(4) + lifespanRow(20)
        // + spacer(4) + spacer(4) + result(optional~12) + spacer(2) + actionRow(20) + margins(20)
        val fixedHeight = 9 + 4 + 20 + 4 + 20 + 4 + 20 + 4 + 4 + 14 + 2 + 20 + 20
        val entryScrollHeight = (height - fixedHeight).coerceAtLeast(60)
        val scrollable = ScrollableLayout(minecraft, entryListContent, entryScrollHeight)
        content.addChild(scrollable)

        content.addChild(SpacerElement(0, 4))

        // Last result
        val result = getBe()?.lastTestResult
        if (result != null) {
            val text = if (result.pass)
                "✓ ${result.passCount}/${result.checks.size} checks passed"
            else
                "✗ ${result.checks.size - result.passCount}/${result.checks.size} checks failed"
            content.addChild(StringWidget(Component.literal(text), font))
            content.addChild(SpacerElement(0, 2))
        }

        // Action buttons
        val actionRow = LinearLayout.horizontal().spacing(4)
        actionRow.addChild(Button.builder(Component.literal("Run")) {
            sendPacket(RunSpecC2SPayload(originPos))
        }.pos(0, 0).width(60).build())
        actionRow.addChild(Button.builder(Component.literal("Bounds")) {
            minecraft.setScreen(SpecBoundsScreen(originPos))
        }.pos(0, 0).width(60).build())
        actionRow.addChild(Button.builder(CommonComponents.GUI_DONE) {
            onClose()
        }.pos(0, 0).width(60).build())
        content.addChild(actionRow)

        content.arrangeElements()
        FrameLayout.centerInRectangle(content, 10, 10, width - 10, height - 10)
        content.visitWidgets { addRenderableWidget(it) }
    }

    override fun onClose() {
        idEditMode = false
        super.onClose()
    }

    private fun getBe() = minecraft.level?.getBlockEntity(originPos) as? RedstoneSpecBlockEntity
    private fun getSpec() = getBe()?.spec
    private fun sendPacket(payload: CustomPacketPayload) = ClientPlayNetworking.send(payload)
}
```

- [ ] **Step 2: Compile**

```
cmd.exe /c "./gradlew.bat :26.1:compileKotlin"
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add src/client/kotlin/com/breadmoirai/redstonespecs/client/screen/SpecOverviewScreen.kt
git commit -m "feat: rewrite SpecOverviewScreen — remove struct row, add Run button, dynamic height"
```

---

## Task 8: Dynamic height for SpecEditorScreen

**Files:**
- Modify: `src/client/kotlin/com/breadmoirai/redstonespecs/client/screen/SpecEditorScreen.kt`

- [ ] **Step 1: Update the table scroll height calculation**

In `SpecEditorScreen.kt`, find the line (around line 156):
```kotlin
val tableScrollHeight = (height - 220).coerceIn(60, 200)
```

Replace it with:
```kotlin
// Fixed rows above the table: type header(9) + spacer(4) + spacer(4) + labelRow(20) + spacer(4)
// + colorRow(20) + spacer(4) + "Entries:" label(9) + add-row btn(20) + spacer(4)
// + captureRow(20) + spacer(4) + spacer(4) + bottomRow(20) + margins(20)
val tableScrollHeight = (height - 166).coerceAtLeast(60)
```

- [ ] **Step 2: Compile**

```
cmd.exe /c "./gradlew.bat :26.1:compileKotlin"
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add src/client/kotlin/com/breadmoirai/redstonespecs/client/screen/SpecEditorScreen.kt
git commit -m "feat: SpecEditorScreen uses dynamic height for entry table scroll area"
```

---

## Task 9: Dynamic vertical centering for SpecBoundsScreen

The existing `FrameLayout.centerInRectangle(content, 10, 10, width - 10, height - 10)` already centers within the full screen height — no content-layout change needed. This task just verifies the behaviour is correct.

**Files:**
- Modify: `src/client/kotlin/com/breadmoirai/redstonespecs/client/screen/SpecBoundsScreen.kt`

- [ ] **Step 1: Verify the existing layout already uses `height`**

Open `SpecBoundsScreen.kt` and confirm line 94 reads:
```kotlin
FrameLayout.centerInRectangle(content, 10, 10, width - 10, height - 10)
```
This already uses `this.height` so no change to the layout call is needed. The screen content is a fixed-size block that is centered vertically in the full window height. This satisfies the requirement.

- [ ] **Step 2: Compile and commit (no-op change)**

```
cmd.exe /c "./gradlew.bat :26.1:compileKotlin"
```
Expected: BUILD SUCCESSFUL

```bash
git commit --allow-empty -m "chore: verify SpecBoundsScreen already centers in full window height"
```

---

## Task 10: Add v1.2 packets to Packets.kt

**Files:**
- Modify: `src/main/kotlin/com/breadmoirai/redstonespecs/network/Packets.kt`

- [ ] **Step 1: Add the v1.2 packet classes and SpecFileInfo**

Add the following at the end of `Packets.kt` (after `OverwriteDecisionC2SPayload`):

```kotlin
// === v1.2: File Browser ===

data class SpecFileInfo(
    val id: String,
    val mode: SpecMode,
    val lifespan: Int,
    val inputCount: Int,
    val outputCount: Int,
    val structure: String?,
) {
    companion object {
        val STREAM_CODEC: StreamCodec<ByteBuf, SpecFileInfo> = object : StreamCodec<ByteBuf, SpecFileInfo> {
            override fun decode(buf: ByteBuf): SpecFileInfo {
                val id = ByteBufCodecs.STRING_UTF8.decode(buf)
                val mode = SpecMode.entries[ByteBufCodecs.VAR_INT.decode(buf)]
                val lifespan = ByteBufCodecs.VAR_INT.decode(buf)
                val inputCount = ByteBufCodecs.VAR_INT.decode(buf)
                val outputCount = ByteBufCodecs.VAR_INT.decode(buf)
                val hasStructure = buf.readBoolean()
                val structure = if (hasStructure) ByteBufCodecs.STRING_UTF8.decode(buf) else null
                return SpecFileInfo(id, mode, lifespan, inputCount, outputCount, structure)
            }
            override fun encode(buf: ByteBuf, value: SpecFileInfo) {
                ByteBufCodecs.STRING_UTF8.encode(buf, value.id)
                ByteBufCodecs.VAR_INT.encode(buf, value.mode.ordinal)
                ByteBufCodecs.VAR_INT.encode(buf, value.lifespan)
                ByteBufCodecs.VAR_INT.encode(buf, value.inputCount)
                ByteBufCodecs.VAR_INT.encode(buf, value.outputCount)
                val s = value.structure
                buf.writeBoolean(s != null)
                if (s != null) ByteBufCodecs.STRING_UTF8.encode(buf, s)
            }
        }
    }
}

// Client requests server to open file browser
data class RequestFileBrowserC2SPayload(val originPos: BlockPos) : CustomPacketPayload {
    companion object {
        val TYPE = CustomPacketPayload.Type<RequestFileBrowserC2SPayload>(
            Identifier.fromNamespaceAndPath("redstonespecs", "request_file_browser")
        )
        val STREAM_CODEC: StreamCodec<ByteBuf, RequestFileBrowserC2SPayload> = StreamCodec.composite(
            BlockPos.STREAM_CODEC, RequestFileBrowserC2SPayload::originPos,
            ::RequestFileBrowserC2SPayload,
        )
    }
    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE
}

// Server responds with metadata for all valid spec files
data class OpenFileBrowserS2CPayload(
    val originPos: BlockPos,
    val files: List<SpecFileInfo>,
) : CustomPacketPayload {
    companion object {
        val TYPE = CustomPacketPayload.Type<OpenFileBrowserS2CPayload>(
            Identifier.fromNamespaceAndPath("redstonespecs", "open_file_browser")
        )
        val STREAM_CODEC: StreamCodec<ByteBuf, OpenFileBrowserS2CPayload> = StreamCodec.composite(
            BlockPos.STREAM_CODEC, OpenFileBrowserS2CPayload::originPos,
            SpecFileInfo.STREAM_CODEC.apply(ByteBufCodecs.list()), OpenFileBrowserS2CPayload::files,
            ::OpenFileBrowserS2CPayload,
        )
    }
    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE
}

// Client confirms selection — server loads JSON + structure
data class LoadFromFileC2SPayload(val originPos: BlockPos, val specId: String) : CustomPacketPayload {
    companion object {
        val TYPE = CustomPacketPayload.Type<LoadFromFileC2SPayload>(
            Identifier.fromNamespaceAndPath("redstonespecs", "load_from_file")
        )
        val STREAM_CODEC: StreamCodec<ByteBuf, LoadFromFileC2SPayload> = StreamCodec.composite(
            BlockPos.STREAM_CODEC, LoadFromFileC2SPayload::originPos,
            ByteBufCodecs.STRING_UTF8, LoadFromFileC2SPayload::specId,
            ::LoadFromFileC2SPayload,
        )
    }
    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE
}
```

Also add the missing `SpecMode` import at the top of `Packets.kt` if not present:
```kotlin
import com.breadmoirai.redstonespecs.data.SpecMode
```

- [ ] **Step 2: Compile**

```
cmd.exe /c "./gradlew.bat :26.1:compileKotlin"
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/com/breadmoirai/redstonespecs/network/Packets.kt
git commit -m "feat: add v1.2 file browser packets — SpecFileInfo, RequestFileBrowser, OpenFileBrowser, LoadFromFile"
```

---

## Task 11: Register v1.2 packets and add file browser server handler

**Files:**
- Modify: `src/main/kotlin/com/breadmoirai/redstonespecs/network/NetworkRegistry.kt`

- [ ] **Step 1: Add S2C registration for OpenFileBrowserS2CPayload**

In `registerNetworking()`, add after the existing S2C registrations:
```kotlin
PayloadTypeRegistry.clientboundPlay().register(OpenFileBrowserS2CPayload.TYPE, OpenFileBrowserS2CPayload.STREAM_CODEC)
```

- [ ] **Step 2: Add C2S registrations for the two new C2S packets**

After the existing C2S registrations:
```kotlin
PayloadTypeRegistry.serverboundPlay().register(RequestFileBrowserC2SPayload.TYPE, RequestFileBrowserC2SPayload.STREAM_CODEC)
PayloadTypeRegistry.serverboundPlay().register(LoadFromFileC2SPayload.TYPE, LoadFromFileC2SPayload.STREAM_CODEC)
```

- [ ] **Step 3: Add RequestFileBrowserC2SPayload handler**

Add at the end of `registerNetworking()`:

```kotlin
ServerPlayNetworking.registerGlobalReceiver(RequestFileBrowserC2SPayload.TYPE) { payload, context ->
    val player = context.player()
    context.server().execute {
        LOGGER.debug("[NetworkRegistry#requestFileBrowser] originPos={}", payload.originPos)
        val dir = saveDir(context.server())
        val files = com.breadmoirai.redstonespecs.persistence.SpecPersistence.listIds(dir).mapNotNull { id ->
            val spec = com.breadmoirai.redstonespecs.persistence.SpecPersistence.load(dir, id) ?: return@mapNotNull null
            SpecFileInfo(
                id = spec.id,
                mode = spec.mode,
                lifespan = spec.lifespan,
                inputCount = spec.inputs.size,
                outputCount = spec.outputs.size,
                structure = spec.structure,
            )
        }
        ServerPlayNetworking.send(player, OpenFileBrowserS2CPayload(payload.originPos, files))
    }
}
```

- [ ] **Step 4: Add LoadFromFileC2SPayload handler**

```kotlin
ServerPlayNetworking.registerGlobalReceiver(LoadFromFileC2SPayload.TYPE) { payload, context ->
    val player = context.player()
    context.server().execute {
        LOGGER.debug("[NetworkRegistry#loadFromFile] originPos={} specId='{}'", payload.originPos, payload.specId)
        val be = player.level().getBlockEntity(payload.originPos) as? RedstoneSpecBlockEntity ?: return@execute
        val dir = saveDir(context.server())
        val spec = com.breadmoirai.redstonespecs.persistence.SpecPersistence.load(dir, payload.specId)
        if (spec == null) {
            LOGGER.warn("[NetworkRegistry#loadFromFile] spec '{}' not found on disk", payload.specId)
            return@execute
        }
        be.setSpec(spec)
        LOGGER.debug("[NetworkRegistry#loadFromFile] loaded spec '{}' from disk", payload.specId)

        val structureId = spec.structure ?: spec.id
        val level = be.level as? ServerLevel ?: return@execute
        if (com.breadmoirai.redstonespecs.persistence.StructurePersistence.hasNonAirBlocks(level, be.blockPos, spec.bounds)) {
            ServerPlayNetworking.send(player, OverwritePromptS2CPayload(payload.originPos, structureId))
        } else {
            com.breadmoirai.redstonespecs.persistence.StructurePersistence.load(dir, structureId, level, be.blockPos, spec.bounds)
            LOGGER.debug("[NetworkRegistry#loadFromFile] placed structure '{}'", structureId)
        }
    }
}
```

- [ ] **Step 5: Compile**

```
cmd.exe /c "./gradlew.bat :26.1:compileKotlin"
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/breadmoirai/redstonespecs/network/NetworkRegistry.kt
git commit -m "feat: register v1.2 file browser packets and add server-side handlers"
```

---

## Task 12: Create SpecFileBrowserScreen

**Files:**
- Create: `src/client/kotlin/com/breadmoirai/redstonespecs/client/screen/SpecFileBrowserScreen.kt`

- [ ] **Step 1: Create the file**

```kotlin
package com.breadmoirai.redstonespecs.client.screen

import com.breadmoirai.redstonespecs.network.LoadFromFileC2SPayload
import com.breadmoirai.redstonespecs.network.SpecFileInfo
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.ScrollableLayout
import net.minecraft.client.gui.components.StringWidget
import net.minecraft.client.gui.layouts.FrameLayout
import net.minecraft.client.gui.layouts.LinearLayout
import net.minecraft.client.gui.layouts.SpacerElement
import net.minecraft.client.gui.screens.Screen
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.CommonComponents
import net.minecraft.network.chat.Component

class SpecFileBrowserScreen(
    private val originPos: BlockPos,
    private val files: List<SpecFileInfo>,
) : Screen(Component.literal("Load Spec")) {

    private var selected: SpecFileInfo? = null

    override fun isPauseScreen() = false
    override fun isInGameUi() = true

    override fun init() {
        super.init()

        val root = LinearLayout.horizontal().spacing(8)

        // Left panel: file list
        val listContent = LinearLayout.vertical().spacing(2)
        if (files.isEmpty()) {
            listContent.addChild(StringWidget(160, 18, Component.literal("(no saved specs)"), font))
        } else {
            files.forEach { info ->
                val isSelected = info.id == selected?.id
                val label = Component.literal(if (isSelected) "► ${info.id}" else "  ${info.id}")
                listContent.addChild(Button.builder(label) {
                    selected = info
                    rebuildWidgets()
                }.pos(0, 0).width(160).build())
            }
        }
        val listHeight = (height - 60).coerceAtLeast(60)
        root.addChild(ScrollableLayout(minecraft, listContent, listHeight))

        // Right panel: preview
        val preview = LinearLayout.vertical().spacing(4)
        val sel = selected
        if (sel != null) {
            preview.addChild(StringWidget(180, 12, Component.literal("ID: ${sel.id}"), font))
            preview.addChild(StringWidget(180, 12, Component.literal("Mode: ${sel.mode.name}"), font))
            preview.addChild(StringWidget(180, 12, Component.literal("Lifespan: ${sel.lifespan}"), font))
            preview.addChild(StringWidget(180, 12, Component.literal("Inputs: ${sel.inputCount}"), font))
            preview.addChild(StringWidget(180, 12, Component.literal("Outputs: ${sel.outputCount}"), font))
            preview.addChild(StringWidget(180, 12, Component.literal("Structure: ${sel.structure ?: sel.id}"), font))
        } else {
            preview.addChild(StringWidget(180, 12, Component.literal("Select a spec to preview"), font))
        }
        root.addChild(preview)

        val outer = LinearLayout.vertical().spacing(8)
        outer.addChild(StringWidget(Component.literal("Load Spec"), font))
        outer.addChild(SpacerElement(0, 4))
        outer.addChild(root)
        outer.addChild(SpacerElement(0, 4))

        val bottomRow = LinearLayout.horizontal().spacing(4)
        bottomRow.addChild(Button.builder(Component.literal("Load")) {
            val id = selected?.id ?: return@builder
            ClientPlayNetworking.send(LoadFromFileC2SPayload(originPos, id))
            onClose()
        }.pos(0, 0).width(60).build().also { it.active = selected != null })
        bottomRow.addChild(Button.builder(CommonComponents.GUI_CANCEL) {
            onClose()
        }.pos(0, 0).width(60).build())
        outer.addChild(bottomRow)

        outer.arrangeElements()
        FrameLayout.centerInRectangle(outer, 10, 10, width - 10, height - 10)
        outer.visitWidgets { addRenderableWidget(it) }
    }
}
```

- [ ] **Step 2: Compile**

```
cmd.exe /c "./gradlew.bat :26.1:compileKotlin"
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add src/client/kotlin/com/breadmoirai/redstonespecs/client/screen/SpecFileBrowserScreen.kt
git commit -m "feat: add SpecFileBrowserScreen for v1.2 load-from-file flow"
```

---

## Task 13: Wire OpenFileBrowserS2CPayload in ClientNetworkHandler and add Load button to SpecOverviewScreen

**Files:**
- Modify: `src/client/kotlin/com/breadmoirai/redstonespecs/client/network/ClientNetworkHandler.kt`
- Modify: `src/client/kotlin/com/breadmoirai/redstonespecs/client/screen/SpecOverviewScreen.kt`

- [ ] **Step 1: Add the OpenFileBrowser S2C handler to ClientNetworkHandler**

In `registerClientNetworking()`, add after the `OverwritePromptS2CPayload` handler:

```kotlin
ClientPlayNetworking.registerGlobalReceiver(OpenFileBrowserS2CPayload.TYPE) { payload, context ->
    val mc = context.client()
    mc.execute {
        LOGGER.debug("[ClientNetworkHandler#openFileBrowser] originPos={} fileCount={}", payload.originPos, payload.files.size)
        mc.setScreen(SpecFileBrowserScreen(payload.originPos, payload.files))
    }
}
```

Also add the import for `SpecFileBrowserScreen`:
```kotlin
import com.breadmoirai.redstonespecs.client.screen.SpecFileBrowserScreen
```

- [ ] **Step 2: Add Load button to SpecOverviewScreen**

In `SpecOverviewScreen.kt`, find the action buttons section. Replace the existing action row:
```kotlin
// Action buttons
val actionRow = LinearLayout.horizontal().spacing(4)
actionRow.addChild(Button.builder(Component.literal("Run")) {
    sendPacket(RunSpecC2SPayload(originPos))
}.pos(0, 0).width(60).build())
actionRow.addChild(Button.builder(Component.literal("Bounds")) {
    minecraft.setScreen(SpecBoundsScreen(originPos))
}.pos(0, 0).width(60).build())
actionRow.addChild(Button.builder(CommonComponents.GUI_DONE) {
    onClose()
}.pos(0, 0).width(60).build())
content.addChild(actionRow)
```

Replace with:
```kotlin
// Action buttons
val actionRow = LinearLayout.horizontal().spacing(4)
actionRow.addChild(Button.builder(Component.literal("Load")) {
    sendPacket(RequestFileBrowserC2SPayload(originPos))
}.pos(0, 0).width(60).build())
actionRow.addChild(Button.builder(Component.literal("Run")) {
    sendPacket(RunSpecC2SPayload(originPos))
}.pos(0, 0).width(60).build())
actionRow.addChild(Button.builder(Component.literal("Bounds")) {
    minecraft.setScreen(SpecBoundsScreen(originPos))
}.pos(0, 0).width(60).build())
actionRow.addChild(Button.builder(CommonComponents.GUI_DONE) {
    onClose()
}.pos(0, 0).width(60).build())
content.addChild(actionRow)
```

Also update the fixed height constant in the scroll-area calculation to account for the wider action row (the layout width grows but height is unchanged — no adjustment needed since buttons are horizontal).

- [ ] **Step 3: Compile**

```
cmd.exe /c "./gradlew.bat :26.1:compileKotlin"
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add src/client/kotlin/com/breadmoirai/redstonespecs/client/network/ClientNetworkHandler.kt
git add src/client/kotlin/com/breadmoirai/redstonespecs/client/screen/SpecOverviewScreen.kt
git commit -m "feat: wire file browser S2C handler and add Load button to SpecOverviewScreen"
```

---

## Task 14: Full build and final verification

- [ ] **Step 1: Run full build**

```
cmd.exe /c "./gradlew.bat :26.1:compileKotlin"
```
Expected: BUILD SUCCESSFUL with no warnings about unresolved references or unused imports.

- [ ] **Step 2: Check for any leftover references to removed packets**

```bash
grep -rn "SaveSpecC2SPayload\|LoadSpecC2SPayload\|SaveStructureC2SPayload\|LoadStructureC2SPayload\|StructureDecisionC2SPayload\|StructurePromptS2CPayload" src/
```
Expected: no output (all deleted).

- [ ] **Step 3: Check for leftover `canSaveSpec` / `canLoadSpec` etc. in screens**

```bash
grep -rn "canSaveSpec\|canLoadSpec\|canSaveStructure\|canLoadStructure\|structureEditMode\|availableStructures" src/
```
Expected: no output.

- [ ] **Step 4: Final commit**

```bash
git commit --allow-empty -m "chore: full build passes — persistence model v1 + v1.2 complete"
```
