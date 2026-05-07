package com.breadmoirai.redstonespecs.data.dsl

import com.breadmoirai.redstonespecs.data.EntryKind
import com.breadmoirai.redstonespecs.data.RedstoneSpec
import com.breadmoirai.redstonespecs.data.SpecEntry
import net.minecraft.core.BlockPos
import net.minecraft.core.Vec3i

@SpecDslMarker
class RedstoneSpecBuilder internal constructor(private val id: String) {
    private var bounds: Vec3i = RedstoneSpec.DEFAULT_BOUNDS
    var lifespan: Int = 20
    var structure: String? = null
    private val entries = mutableListOf<SpecEntry>()

    fun bounds(x: Int, y: Int, z: Int) { bounds = Vec3i(x, y, z) }
    fun bounds(size: Vec3i) { bounds = size }

    fun input(x: Int, y: Int, z: Int, label: String = "", color: Int = -1, block: EntryScope.() -> Unit) {
        entries += EntryScope(BlockPos(x, y, z), label, color, EntryKind.INPUT).apply(block).build()
    }

    fun output(x: Int, y: Int, z: Int, label: String = "", color: Int = -1, block: EntryScope.() -> Unit) {
        entries += EntryScope(BlockPos(x, y, z), label, color, EntryKind.OUTPUT).apply(block).build()
    }

    internal fun build(): RedstoneSpec = RedstoneSpec(
        id = id, bounds = bounds, lifespan = lifespan, structure = structure, entries = entries.toList(),
    )
}

fun redstoneSpec(id: String, block: RedstoneSpecBuilder.() -> Unit): RedstoneSpec =
    RedstoneSpecBuilder(id).apply(block).build()
