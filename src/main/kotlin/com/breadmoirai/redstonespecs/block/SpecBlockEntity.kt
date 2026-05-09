package com.breadmoirai.redstonespecs.block

import com.breadmoirai.redstonespecs.ModRegistries
import com.breadmoirai.redstonespecs.config.SharedSettings
import com.breadmoirai.redstonespecs.data.EntryKind
import com.breadmoirai.redstonespecs.data.RedstoneSpec
import com.breadmoirai.redstonespecs.data.RedstoneSpecEmitter
import com.breadmoirai.redstonespecs.data.RedstoneSpecEmitter.Companion.emitter
import com.breadmoirai.redstonespecs.data.SpecEntry
import com.breadmoirai.redstonespecs.data.TestResult
import com.breadmoirai.redstonespecs.data.allEntries
import com.breadmoirai.redstonespecs.data.inputs
import com.breadmoirai.redstonespecs.data.outputs
import com.breadmoirai.redstonespecs.data.serial.KtsSpecEmitter
import com.breadmoirai.redstonespecs.data.serial.SpecJsonCodec
import com.breadmoirai.redstonespecs.persistence.SpecPersistence
import com.breadmoirai.redstonespecs.runner.EntryMarker
import com.breadmoirai.redstonespecs.runner.RecordingDslEmitter
import com.breadmoirai.redstonespecs.runner.StateRecorder
import com.breadmoirai.redstonespecs.runner.runRedstoneSpec
import com.breadmoirai.redstonespecs.testing.core.McDispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.io.path.writeText
import net.minecraft.core.BlockPos
import net.minecraft.core.HolderLookup
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientGamePacketListener
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.storage.LevelResource
import net.minecraft.world.level.storage.ValueInput
import net.minecraft.world.level.storage.ValueOutput
import org.slf4j.LoggerFactory
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class SpecBlockEntity(pos: BlockPos, state: BlockState) :
    BlockEntity(ModRegistries.SPEC_BLOCK_ENTITY_TYPE, pos, state) {

    private var specEmitter: RedstoneSpecEmitter? = null
    private val coroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var collectorJob: Job? = null

    val spec: RedstoneSpec? get() = specEmitter?.value

    var lastTestResult: TestResult? = null
        private set

    private var stateRecorder: StateRecorder? = null
    val isRecording: Boolean get() = stateRecorder != null

    /** Set by ManagedDimLifecycle when placing this BE in a managed cell. Null otherwise.
     *  Not persisted to NBT — managed dims rebuild from disk every session. */
    @JvmField var managedSourcePath: java.nio.file.Path? = null

    fun startRecording(): Boolean {
        val s = spec ?: run { LOGGER.debug("[startRecording] no spec at {}", blockPos); return false }
        if (s.id.isBlank()) { LOGGER.debug("[startRecording] blank id at {}", blockPos); return false }
        if (s.inputs.isEmpty()) { LOGGER.debug("[startRecording] no inputs at {}", blockPos); return false }
        if (s.outputs.isEmpty()) { LOGGER.debug("[startRecording] no outputs at {}", blockPos); return false }
        val b = s.bounds
        if (b.x < 1 || b.y < 1 || b.z < 1) {
            LOGGER.debug("[startRecording] empty bounds {} at {}", b, blockPos); return false
        }
        val lv = level as? ServerLevel ?: run { LOGGER.debug("[startRecording] not ServerLevel at {}", blockPos); return false }
        if (stateRecorder != null) { LOGGER.debug("[startRecording] already recording at {}", blockPos); return false }
        LOGGER.info("[startRecording] starting at {} (id={}, bounds={})", blockPos, s.id, b)
        val recorder = StateRecorder.forSpec(UUID.randomUUID(), blockPos, b)
        recorder.start(lv, blockPos, b)
        StateRecorder.activate(recorder)
        stateRecorder = recorder
        setChangedAndSync()
        return true
    }

    fun stopRecordingAndFinalize(): Boolean {
        val rec = stateRecorder ?: return false
        StateRecorder.deactivate(rec)
        val recording = rec.toRecording()
        stateRecorder = null
        val s = spec
        if (s != null) {
            val markers = s.allEntries
                .map { e ->
                    EntryMarker(
                        pos = e.pos,
                        label = e.label,
                        color = e.color,
                        kind = when (e.kind) {
                            EntryKind.INPUT  -> EntryMarker.Kind.INPUT
                            EntryKind.OUTPUT -> EntryMarker.Kind.OUTPUT
                        },
                    )
                }
                .distinctBy { it.pos to it.kind }
            if (markers.isNotEmpty()) {
                val source = RecordingDslEmitter.emit(
                    id = s.id,
                    bounds = s.bounds,
                    lifespan = 20,
                    structure = s.structure,
                    strict = false,
                    markers = markers,
                    recording = recording,
                )
                val serverLevel = level as? ServerLevel
                if (serverLevel != null) {
                    val src = managedSourcePath
                    coroutineScope.launch(Dispatchers.IO) {
                        if (src != null) {
                            src.writeText(source)
                            LOGGER.debug("[finalize] managed: wrote recording result to {}", src)
                        } else {
                            val saveDir = serverLevel.server
                                .getWorldPath(LevelResource.ROOT)
                                .resolve(SharedSettings.specSaveDir)
                            SpecPersistence.writeSpecKts(saveDir, s.id, source)
                        }
                    }
                }
            }
        }
        setChangedAndSync()
        return true
    }

    fun setSpec(newSpec: RedstoneSpec) {
        LOGGER.debug("[SpecBlockEntity#setSpec] setting spec '{}' at {}", newSpec.id, blockPos)
        val e = specEmitter
        if (e == null) {
            specEmitter = newSpec.emitter()
            tryStartCollecting()
            triggerSave(newSpec)
        } else {
            e.updateFrom(newSpec)
        }
        setChangedAndSync()
    }

    fun setSpecId(id: String) {
        val e = specEmitter ?: return
        e.id = id
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
        LOGGER.debug("[SpecBlockEntity#addOrUpdateEntry] pos={} kind={}", entry.pos, entry.kind)
        val e = specEmitter ?: return
        e.updateFrom(e.value.withEntryAddedOrUpdated(entry))
        setChangedAndSync()
    }

    fun removeEntry(pos: BlockPos): SpecEntry? {
        LOGGER.debug("[SpecBlockEntity#removeEntry] pos={}", pos)
        val e = specEmitter ?: return null
        val s = e.value
        val removed = s.entries.firstOrNull { it.pos == pos } ?: return null
        e.updateFrom(s.withEntriesRemoved(pos))
        setChangedAndSync()
        return removed
    }

    /**
     * Wipes everything on the spec EXCEPT id, bounds, and per-(pos,kind) marker headers.
     * Marker entries collapse to one placeholder per (pos, kind). Used by Editor → Recorder Discard.
     */
    fun discardForRerecord() {
        val s = spec ?: return
        // Keep one marker entry per (pos, kind) — first occurrence wins for label/color.
        val seen = HashSet<Pair<BlockPos, EntryKind>>()
        val markers = s.allEntries.filter { e ->
            val key = e.pos to e.kind
            seen.add(key)
        }
        val cleared = RedstoneSpec.new(s.id).copy(
            bounds = s.bounds,
            entries = markers,
        )
        setSpec(cleared)
        lastTestResult = null
        setChangedAndSync()
    }

    /**
     * Launches [runRedstoneSpec] on the server thread using the BE's own coroutine scope.
     *
     * Returns false if a run is already in flight for this BE (debounce).
     * On completion, updates [lastTestResult] and broadcasts the result.
     */
    fun startRun(dslSpec: com.breadmoirai.redstonespecs.dsl.RedstoneSpec, serverLevel: ServerLevel): Boolean {
        if (!inFlightRuns.add(blockPos)) {
            LOGGER.debug("[SpecBlockEntity#startRun] '{}' already running, ignoring", dslSpec.id)
            return false
        }
        coroutineScope.launch(McDispatchers.Server) {
            try {
                LOGGER.info("[SpecBlockEntity#startRun] launching '{}' at {}", dslSpec.id, blockPos)
                val recording = runRedstoneSpec(serverLevel, blockPos, dslSpec)
                LOGGER.info("[SpecBlockEntity#startRun] '{}' PASSED", dslSpec.id)
            } catch (e: AssertionError) {
                LOGGER.warn("[SpecBlockEntity#startRun] '{}' FAILED: {}", dslSpec.id, e.message)
            } catch (t: Throwable) {
                LOGGER.error("[SpecBlockEntity#startRun] '{}' crashed unexpectedly", dslSpec.id, t)
            } finally {
                inFlightRuns.remove(blockPos)
            }
        }
        return true
    }

    fun transformTo(targetBlock: Block) {
        val lv = level ?: return
        if (lv.isClientSide) return
        val carriedSpec = spec
        val newState = targetBlock.defaultBlockState()
        lv.setBlock(blockPos, newState, 3)
        if (carriedSpec != null) {
            val newBe = lv.getBlockEntity(blockPos) as? SpecBlockEntity ?: return
            newBe.setSpec(carriedSpec)
        }
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
            e.drop(1).collect { spec ->
                val serverLevel = lv as? ServerLevel ?: return@collect
                val src = managedSourcePath
                if (src != null) {
                    withContext(Dispatchers.IO) {
                        src.writeText(KtsSpecEmitter.emit(spec))
                    }
                    LOGGER.debug("[finalize] managed: wrote back to {}", src)
                } else {
                    val saveDir = serverLevel.server
                        .getWorldPath(LevelResource.ROOT)
                        .resolve(SharedSettings.specSaveDir)
                    withContext(Dispatchers.IO) {
                        SpecPersistence.save(saveDir, spec)
                    }
                }
            }
        }
    }

    private fun triggerSave(spec: RedstoneSpec) {
        val serverLevel = level as? ServerLevel ?: return
        val src = managedSourcePath
        if (src != null) {
            coroutineScope.launch(Dispatchers.IO) {
                src.writeText(KtsSpecEmitter.emit(spec))
                LOGGER.debug("[finalize] managed: wrote back to {}", src)
            }
        } else {
            val saveDir = serverLevel.server
                .getWorldPath(LevelResource.ROOT)
                .resolve(SharedSettings.specSaveDir)
            coroutineScope.launch(Dispatchers.IO) {
                SpecPersistence.save(saveDir, spec)
            }
        }
    }

    private fun setChangedAndSync() {
        setChanged()
        level?.sendBlockUpdated(blockPos, blockState, blockState, 3)
    }

    companion object {
        private val LOGGER = LoggerFactory.getLogger("Redstone Specs")
        private val registry = ConcurrentHashMap<Level, ConcurrentHashMap<BlockPos, SpecBlockEntity>>()
        private val inFlightRuns = java.util.concurrent.ConcurrentHashMap.newKeySet<BlockPos>()

        private fun register(be: SpecBlockEntity) {
            val level = be.level ?: return
            registry.getOrPut(level, ::ConcurrentHashMap)[be.blockPos] = be
        }

        fun findFor(level: Level, worldPos: BlockPos): SpecBlockEntity? =
            registry[level]?.values?.find { be ->
                val s = be.spec ?: return@find false
                val b = s.bounds
                val o = be.blockPos
                (worldPos.x - o.x) in 0 until b.x &&
                (worldPos.y - o.y) in 0 until b.y &&
                (worldPos.z - o.z) in 0 until b.z
            }

        fun allFor(level: Level): Collection<SpecBlockEntity> =
            registry[level]?.values ?: emptyList()
    }

    override fun saveAdditional(output: ValueOutput) {
        LOGGER.debug("[SpecBlockEntity#saveAdditional] saving at {}", blockPos)
        super.saveAdditional(output)
        spec?.let { output.store("spec", SpecJsonCodec.SPEC, it) }
        lastTestResult?.let { output.store("last_test_result", TestResult.CODEC, it) }
    }

    override fun loadAdditional(input: ValueInput) {
        super.loadAdditional(input)
        val loaded = input.read("spec", SpecJsonCodec.SPEC).orElse(null)
        lastTestResult = input.read("last_test_result", TestResult.CODEC).orElse(null)
        LOGGER.debug("[SpecBlockEntity#loadAdditional] loaded at {} spec='{}'", blockPos, loaded?.id)
        if (loaded == null) return
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
    bounds = spec.bounds
    lifespan = spec.lifespan
    structure = spec.structure
    entries = spec.entries
}
