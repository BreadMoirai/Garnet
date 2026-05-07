package com.breadmoirai.redstonespecs.data

import com.livefront.annotation.AutoEmit
import net.minecraft.core.BlockPos
import net.minecraft.core.Vec3i

@AutoEmit
data class RedstoneSpec(
    val id: String,
    val bounds: Vec3i,
    val lifespan: Int,
    val structure: String?,
    val entries: List<SpecEntry>,
) {
    init {
        require(bounds.x >= 1 && bounds.y >= 1 && bounds.z >= 1) {
            "bounds must be >= 1 on all axes, got: $bounds"
        }
        for (e in entries) {
            require(e.pos.x in 0 until bounds.x &&
                    e.pos.y in 0 until bounds.y &&
                    e.pos.z in 0 until bounds.z) {
                "entry pos ${e.pos} (kind=${e.kind}, label='${e.label}') is outside bounds $bounds"
            }
        }
    }

    fun entriesAt(pos: BlockPos): List<SpecEntry> = entries.filter { it.pos == pos }

    fun withEntryAddedOrUpdated(entry: SpecEntry): RedstoneSpec {
        val others = entries.filter {
            !(it.pos == entry.pos && it.kind == entry.kind && it.time == entry.time)
        }
        return copy(entries = others + entry)
    }

    fun withEntriesRemoved(pos: BlockPos): RedstoneSpec =
        copy(entries = entries.filter { it.pos != pos })

    companion object {
        val DEFAULT_BOUNDS: Vec3i = Vec3i(5, 5, 5)

        fun new(id: String) = RedstoneSpec(
            id = id,
            bounds = DEFAULT_BOUNDS,
            lifespan = 20,
            structure = null,
            entries = emptyList(),
        )
    }
}

val RedstoneSpec.inputs: List<SpecEntry>
    get() = entries.filter { it.kind == EntryKind.INPUT }

val RedstoneSpec.outputs: List<SpecEntry>
    get() = entries.filter { it.kind == EntryKind.OUTPUT }

val RedstoneSpec.allEntries: List<SpecEntry> get() = entries
