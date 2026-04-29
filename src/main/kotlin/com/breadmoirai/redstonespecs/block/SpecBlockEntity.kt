package com.breadmoirai.redstonespecs.block

import com.breadmoirai.redstonespecs.ModRegistries
import com.breadmoirai.redstonespecs.config.SharedSettings
import com.breadmoirai.redstonespecs.data.InputSpec
import com.breadmoirai.redstonespecs.data.OutputSpec
import com.breadmoirai.redstonespecs.data.RedstoneSpec
import com.breadmoirai.redstonespecs.data.RedstoneSpecEmitter
import com.breadmoirai.redstonespecs.data.RedstoneSpecEmitter.Companion.emitter
import com.breadmoirai.redstonespecs.data.SimTime
import com.breadmoirai.redstonespecs.data.SpecEntry
import com.breadmoirai.redstonespecs.data.SpecMode
import com.breadmoirai.redstonespecs.data.TestResult
import com.breadmoirai.redstonespecs.persistence.SpecPersistence
import com.breadmoirai.redstonespecs.runner.RecordingFinalizer
import com.breadmoirai.redstonespecs.runner.StateRecorder
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

    fun startRecording(): Boolean {
        val s = spec ?: run { LOGGER.debug("[startRecording] no spec at {}", blockPos); return false }
        if (s.id.isBlank()) { LOGGER.debug("[startRecording] blank id at {}", blockPos); return false }
        if (s.inputs.isEmpty()) { LOGGER.debug("[startRecording] no inputs at {}", blockPos); return false }
        if (s.outputs.isEmpty()) { LOGGER.debug("[startRecording] no outputs at {}", blockPos); return false }
        val b = s.bounds
        if (b.minX() >= b.maxX() || b.minY() >= b.maxY() || b.minZ() >= b.maxZ()) {
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
            val finalized = RecordingFinalizer.finalize(s, recording)
            if (finalized != null) {
                setSpec(finalized)
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
        LOGGER.debug("[SpecBlockEntity#addOrUpdateEntry] pos={} type={}", entry.pos, entry.javaClass.simpleName)
        val e = specEmitter ?: return
        e.updateFrom(e.value.withEntryAddedOrUpdated(entry))
        setChangedAndSync()
    }

    fun removeEntry(pos: BlockPos): SpecEntry? {
        LOGGER.debug("[SpecBlockEntity#removeEntry] pos={}", pos)
        val e = specEmitter ?: return null
        val s = e.value
        val removed = s.entryAt(pos) ?: return null
        e.updateFrom(s.withEntryRemoved(pos))
        setChangedAndSync()
        return removed
    }

    /**
     * Wipes everything on the spec EXCEPT id, bounds, and input/output marker positions.
     * Input entries are reduced to just their START condition (required by InputSpec invariant).
     * Output entries are cleared to empty. Used by Editor → Recorder Discard.
     */
    fun discardForRerecord() {
        val s = spec ?: return
        val cleared = RedstoneSpec.new(s.id).copy(
            bounds = s.bounds,
            inputs = s.inputs.map { InputSpec(it.pos, it.label, it.color, it.entries.filter { e -> e.first == SimTime.START }) },
            outputs = s.outputs.map { OutputSpec(it.pos, it.label, it.color, emptyList()) },
        )
        setSpec(cleared)
        lastTestResult = null
        setChangedAndSync()
    }

    fun transformTo(targetBlock: Block) {
        val lv = level ?: return
        if (lv.isClientSide) return
        // setBlock replaces the BlockEntity when the block type changes, so explicitly
        // carry spec state across the transition.
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
        private val registry = ConcurrentHashMap<Level, ConcurrentHashMap<BlockPos, SpecBlockEntity>>()

        private fun register(be: SpecBlockEntity) {
            val level = be.level ?: return
            registry.getOrPut(level, ::ConcurrentHashMap)[be.blockPos] = be
        }

        fun findFor(level: Level, worldPos: BlockPos): SpecBlockEntity? =
            registry[level]?.values?.find { be ->
                val s = be.spec ?: return@find false
                val b = s.bounds
                val o = be.blockPos
                worldPos.x in (o.x + b.minX())..(o.x + b.maxX()) &&
                worldPos.y in (o.y + b.minY())..(o.y + b.maxY()) &&
                worldPos.z in (o.z + b.minZ())..(o.z + b.maxZ())
            }

        fun allFor(level: Level): Collection<SpecBlockEntity> =
            registry[level]?.values ?: emptyList()
    }

    override fun saveAdditional(output: ValueOutput) {
        LOGGER.debug("[SpecBlockEntity#saveAdditional] saving at {}", blockPos)
        super.saveAdditional(output)
        spec?.let { output.store("spec", RedstoneSpec.CODEC, it) }
        lastTestResult?.let { output.store("last_test_result", TestResult.CODEC, it) }
    }

    override fun loadAdditional(input: ValueInput) {
        super.loadAdditional(input)
        val loaded = input.read("spec", RedstoneSpec.CODEC).orElse(null)
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
    mode = spec.mode
    bounds = spec.bounds
    lifespan = spec.lifespan
    structure = spec.structure
    inputs = spec.inputs
    outputs = spec.outputs
    breakpoints = spec.breakpoints
    autoSpecs = spec.autoSpecs
}
