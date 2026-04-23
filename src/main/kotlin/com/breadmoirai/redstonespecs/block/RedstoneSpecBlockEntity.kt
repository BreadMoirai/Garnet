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
