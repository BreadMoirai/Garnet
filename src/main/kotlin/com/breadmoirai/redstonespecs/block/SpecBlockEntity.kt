package com.breadmoirai.redstonespecs.block

import com.breadmoirai.redstonespecs.ModRegistries
import com.breadmoirai.redstonespecs.config.SharedSettings
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
import kotlinx.coroutines.launch
import net.minecraft.core.BlockPos
import net.minecraft.core.HolderLookup
import net.minecraft.core.Vec3i
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
import kotlin.io.path.writeText

class SpecBlockEntity(pos: BlockPos, state: BlockState) :
    BlockEntity(ModRegistries.SPEC_BLOCK_ENTITY_TYPE, pos, state) {

    private val coroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // Spec config: plain strings + bounds, no data.RedstoneSpec dependency.
    var specId: String = "spec"
        private set
    var specBounds: Vec3i = Vec3i(5, 5, 5)
        private set
    var specStructure: String? = null
        private set
    var specMarkers: List<EntryMarker> = emptyList()
        private set

    /** Convenience: true if id is non-blank and bounds are positive. */
    val isConfigured: Boolean
        get() = specId.isNotBlank() && specBounds.x >= 1 && specBounds.y >= 1 && specBounds.z >= 1

    private var stateRecorder: StateRecorder? = null
    val isRecording: Boolean get() = stateRecorder != null

    /** Set by ProjectDimLifecycle when placing this BE in a managed cell. Null otherwise.
     *  Not persisted to NBT — managed dims rebuild from disk every session. */
    @JvmField var managedSourcePath: java.nio.file.Path? = null

    // ── Spec config setters ───────────────────────────────────────────────────

    fun setSpecId(id: String) {
        specId = id
        setChangedAndSync()
    }

    fun setSpecBounds(bounds: Vec3i) {
        specBounds = bounds
        setChangedAndSync()
    }

    fun setStructure(structure: String?) {
        specStructure = structure
        setChangedAndSync()
    }

    fun setSpecMarkers(markers: List<EntryMarker>) {
        specMarkers = markers
        setChangedAndSync()
    }

    fun addOrUpdateMarker(marker: EntryMarker) {
        val others = specMarkers.filter { !(it.pos == marker.pos && it.kind == marker.kind) }
        specMarkers = others + marker
        setChangedAndSync()
    }

    fun removeMarker(pos: BlockPos): EntryMarker? {
        val removed = specMarkers.firstOrNull { it.pos == pos } ?: return null
        specMarkers = specMarkers.filter { it.pos != pos }
        setChangedAndSync()
        return removed
    }

    // ── Recording ─────────────────────────────────────────────────────────────

    fun startRecording(): Boolean {
        if (specId.isBlank()) { LOGGER.debug("[startRecording] blank id at {}", blockPos); return false }
        val b = specBounds
        if (b.x < 1 || b.y < 1 || b.z < 1) {
            LOGGER.debug("[startRecording] empty bounds {} at {}", b, blockPos); return false
        }
        val lv = level as? ServerLevel ?: run { LOGGER.debug("[startRecording] not ServerLevel at {}", blockPos); return false }
        if (stateRecorder != null) { LOGGER.debug("[startRecording] already recording at {}", blockPos); return false }
        LOGGER.info("[startRecording] starting at {} (id={}, bounds={})", blockPos, specId, b)
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

        val markers = specMarkers
            .distinctBy { it.pos to it.kind }
        if (markers.isNotEmpty()) {
            val source = RecordingDslEmitter.emit(
                id = specId,
                bounds = specBounds,
                lifespan = 20,
                structure = specStructure,
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
                        LOGGER.debug("[finalize] project: wrote recording result to {}", src)
                    } else {
                        val saveDir = serverLevel.server
                            .getWorldPath(LevelResource.ROOT)
                            .resolve(SharedSettings.specSaveDir)
                        SpecPersistence.writeSpecKts(saveDir, specId, source)
                    }
                }
            }
        }
        setChangedAndSync()
        return true
    }

    /**
     * Resets the spec markers to placeholders (one per pos/kind) for re-recording.
     */
    fun discardForRerecord() {
        val seen = HashSet<Pair<BlockPos, EntryMarker.Kind>>()
        specMarkers = specMarkers.filter { e ->
            val key = e.pos to e.kind
            seen.add(key)
        }
        setChangedAndSync()
    }

    /**
     * Atomically marks this BE as having a run in flight. Returns false if a run is
     * already in flight for this BE. Public so gametest specs can force the
     * "already running" branch of [startRun] without actually launching the engine.
     */
    fun tryClaimRun(): Boolean = inFlightRuns.add(blockPos)

    /**
     * Releases the in-flight claim. Test seam for gametest cleanup — production
     * releases happen in the launched coroutine's finally block.
     */
    fun releaseRunClaim(): Boolean = inFlightRuns.remove(blockPos)

    /**
     * Launches [runRedstoneSpec] on the server thread using the BE's own coroutine scope.
     * Returns false if a run is already in flight for this BE (debounce).
     */
    fun startRun(dslSpec: com.breadmoirai.redstonespecs.dsl.RedstoneSpec, serverLevel: ServerLevel): Boolean {
        if (!tryClaimRun()) {
            LOGGER.debug("[SpecBlockEntity#startRun] '{}' already running, ignoring", dslSpec.id)
            return false
        }
        coroutineScope.launch(McDispatchers.Server) {
            try {
                LOGGER.info("[SpecBlockEntity#startRun] launching '{}' at {}", dslSpec.id, blockPos)
                runRedstoneSpec(serverLevel, blockPos, dslSpec)
                LOGGER.info("[SpecBlockEntity#startRun] '{}' PASSED", dslSpec.id)
            } catch (e: AssertionError) {
                LOGGER.warn("[SpecBlockEntity#startRun] '{}' FAILED: {}", dslSpec.id, e.message)
            } catch (t: Throwable) {
                LOGGER.error("[SpecBlockEntity#startRun] '{}' crashed unexpectedly", dslSpec.id, t)
            } finally {
                releaseRunClaim()
            }
        }
        return true
    }

    fun transformTo(targetBlock: Block) {
        val lv = level ?: return
        if (lv.isClientSide) return
        val newState = targetBlock.defaultBlockState()
        lv.setBlock(blockPos, newState, 3)
        val newBe = lv.getBlockEntity(blockPos) as? SpecBlockEntity ?: return
        newBe.specId = specId
        newBe.specBounds = specBounds
        newBe.specStructure = specStructure
        newBe.specMarkers = specMarkers
        newBe.setChangedAndSync()
    }

    override fun setLevel(level: Level) {
        super.setLevel(level)
        register(this)
    }

    override fun setRemoved() {
        super.setRemoved()
        coroutineScope.cancel()
        level?.let { registry[it]?.remove(blockPos) }
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
                val b = be.specBounds
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
        output.store("spec_id", com.mojang.serialization.Codec.STRING, specId)
        output.store("spec_bounds_x", com.mojang.serialization.Codec.INT, specBounds.x)
        output.store("spec_bounds_y", com.mojang.serialization.Codec.INT, specBounds.y)
        output.store("spec_bounds_z", com.mojang.serialization.Codec.INT, specBounds.z)
        specStructure?.let { output.store("spec_structure", com.mojang.serialization.Codec.STRING, it) }
    }

    override fun loadAdditional(input: ValueInput) {
        super.loadAdditional(input)
        specId = input.read("spec_id", com.mojang.serialization.Codec.STRING).orElse("spec")
        val bx = input.read("spec_bounds_x", com.mojang.serialization.Codec.INT).orElse(5)
        val by = input.read("spec_bounds_y", com.mojang.serialization.Codec.INT).orElse(5)
        val bz = input.read("spec_bounds_z", com.mojang.serialization.Codec.INT).orElse(5)
        specBounds = Vec3i(bx, by, bz)
        specStructure = input.read("spec_structure", com.mojang.serialization.Codec.STRING).orElse(null)
        LOGGER.debug("[SpecBlockEntity#loadAdditional] loaded at {} specId='{}'", blockPos, specId)
    }

    override fun getUpdateTag(registries: HolderLookup.Provider): CompoundTag =
        saveWithoutMetadata(registries)

    override fun getUpdatePacket(): Packet<ClientGamePacketListener> =
        ClientboundBlockEntityDataPacket.create(this)
}
