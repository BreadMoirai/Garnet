package com.breadmoirai.redstonespecs.block

import com.breadmoirai.redstonespecs.ModRegistries
import com.breadmoirai.redstonespecs.data.RedstoneSpec
import com.breadmoirai.redstonespecs.data.SpecCase
import com.breadmoirai.redstonespecs.data.SpecEntry
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
import java.util.concurrent.ConcurrentHashMap

class SpecOriginBlockEntity(pos: BlockPos, state: BlockState) :
    BlockEntity(ModRegistries.SPEC_ORIGIN_BLOCK_ENTITY_TYPE, pos, state) {

    var spec: RedstoneSpec? = null
        private set

    var activeSpecCaseIndex: Int = 0
        private set

    var lastTestResult: TestResult? = null
        private set

    fun setSpec(newSpec: RedstoneSpec) {
        spec = newSpec
        if (activeSpecCaseIndex >= newSpec.specCases.size) activeSpecCaseIndex = 0
        setChangedAndSync()
    }

    fun setActiveSpecCase(index: Int) {
        val s = spec ?: return
        require(index in s.specCases.indices) { "Index $index out of bounds for ${s.specCases.size} spec cases" }
        activeSpecCaseIndex = index
        setChangedAndSync()
    }

    fun setLastTestResult(result: TestResult) {
        lastTestResult = result
        setChangedAndSync()
    }

    fun addOrUpdateEntry(specCaseIndex: Int, entry: SpecEntry) {
        val s = spec ?: return
        val updatedCases = s.specCases.toMutableList()
        updatedCases[specCaseIndex] = updatedCases[specCaseIndex].withEntryAddedOrUpdated(entry)
        spec = s.copy(specCases = updatedCases)
        setChangedAndSync()
    }

    fun addSpecCase(name: String) {
        val s = spec ?: return
        val newCase = SpecCase(name, 20, emptyList(), emptyList(), emptyList(), emptyList())
        spec = s.copy(specCases = s.specCases + newCase)
        setChangedAndSync()
    }

    fun addOrUpdateSpecCase(specCase: SpecCase) {
        val s = spec ?: return
        val existing = s.specCases.indexOfFirst { it.name == specCase.name }
        val updated = if (existing >= 0) {
            s.specCases.toMutableList().also { it[existing] = specCase }
        } else {
            s.specCases + specCase
        }
        spec = s.copy(specCases = updated)
        if (activeSpecCaseIndex >= updated.size) activeSpecCaseIndex = updated.size - 1
        setChangedAndSync()
    }

    fun removeSpecCase(index: Int) {
        val s = spec ?: return
        if (index !in s.specCases.indices) return
        val updated = s.specCases.toMutableList().also { it.removeAt(index) }
        spec = s.copy(specCases = updated)
        if (activeSpecCaseIndex >= updated.size) activeSpecCaseIndex = (updated.size - 1).coerceAtLeast(0)
        setChangedAndSync()
    }

    fun renameSpecCase(index: Int, name: String) {
        val s = spec ?: return
        if (index !in s.specCases.indices) return
        val updated = s.specCases.toMutableList()
        updated[index] = updated[index].copy(name = name)
        spec = s.copy(specCases = updated)
        setChangedAndSync()
    }

    fun setSpecName(name: String) {
        spec = spec?.copy(name = name) ?: return
        setChangedAndSync()
    }

    fun setOneShot(oneShot: Boolean) {
        spec = spec?.copy(oneShot = oneShot) ?: return
        setChangedAndSync()
    }

    fun removeEntry(specCaseIndex: Int, pos: BlockPos): SpecEntry? {
        val s = spec ?: return null
        val specCase = s.specCases.getOrNull(specCaseIndex) ?: return null
        val removed = specCase.entryAt(pos) ?: return null
        val updatedCases = s.specCases.toMutableList()
        updatedCases[specCaseIndex] = specCase.withEntryRemoved(pos)
        spec = s.copy(specCases = updatedCases)
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
        private val registry = ConcurrentHashMap<Level, ConcurrentHashMap<BlockPos, SpecOriginBlockEntity>>()

        private fun register(be: SpecOriginBlockEntity) {
            val level = be.level ?: return
            registry.getOrPut(level, ::ConcurrentHashMap)[be.blockPos] = be
        }

        fun findFor(level: Level, worldPos: BlockPos): SpecOriginBlockEntity? =
            registry[level]?.values?.find { be ->
                val s = be.spec ?: return@find false
                val b = s.bounds
                val o = be.blockPos
                worldPos.x in (o.x + b.minX())..(o.x + b.maxX()) &&
                worldPos.y in (o.y + b.minY())..(o.y + b.maxY()) &&
                worldPos.z in (o.z + b.minZ())..(o.z + b.maxZ())
            }

        fun allFor(level: Level): Collection<SpecOriginBlockEntity> =
            registry[level]?.values ?: emptyList()
    }

    override fun saveAdditional(output: ValueOutput) {
        super.saveAdditional(output)
        spec?.let { output.store("spec", RedstoneSpec.CODEC, it) }
        output.putInt("active_spec_case", activeSpecCaseIndex)
        lastTestResult?.let { output.store("last_test_result", TestResult.CODEC, it) }
    }

    override fun loadAdditional(input: ValueInput) {
        super.loadAdditional(input)
        spec = input.read("spec", RedstoneSpec.CODEC).orElse(null)
        activeSpecCaseIndex = input.getIntOr("active_spec_case", 0)
        lastTestResult = input.read("last_test_result", TestResult.CODEC).orElse(null)
    }

    override fun getUpdateTag(registries: HolderLookup.Provider): CompoundTag =
        saveWithoutMetadata(registries)

    override fun getUpdatePacket(): Packet<ClientGamePacketListener> =
        ClientboundBlockEntityDataPacket.create(this)
}
