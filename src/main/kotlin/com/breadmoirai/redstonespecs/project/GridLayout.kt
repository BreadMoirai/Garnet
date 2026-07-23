package com.breadmoirai.redstonespecs.project

import com.breadmoirai.redstonespecs.dsl.RedstoneSpec
import net.minecraft.core.BlockPos
import net.minecraft.core.Vec3i

data class LayoutInput(val filename: String, val spec: RedstoneSpec)
data class LayoutError(val specId: String, val filename: String, val reason: String)
data class LayoutResult(
    val cells: Map<String, ProjectCell>,           // by spec id
    val byOrigin: Map<BlockPos, String>,           // origin -> spec id
    val errors: List<LayoutError>,
)

object GridLayout {
    fun compute(
        inputs: List<LayoutInput>,
        cellSize: Vec3i,
        cellGap: Int,
        rowMax: Int,
        yBase: Int,
    ): LayoutResult {
        require(rowMax >= 1) { "rowMax must be >= 1" }

        val sorted = inputs.sortedWith(
            compareBy<LayoutInput, String>(String.CASE_INSENSITIVE_ORDER) { it.filename }
                .thenBy { it.spec.id }
        )

        val cells = LinkedHashMap<String, ProjectCell>()
        val byOrigin = LinkedHashMap<BlockPos, String>()
        val errors = mutableListOf<LayoutError>()

        var slotIndex = 0
        for (input in sorted) {
            val s = input.spec
            if (s.bounds.x > cellSize.x || s.bounds.y > cellSize.y || s.bounds.z > cellSize.z) {
                errors.add(LayoutError(s.id, input.filename,
                    "spec bounds ${s.bounds} exceeds cellSize $cellSize on at least one axis " +
                    "(${s.bounds.x}/${cellSize.x}, ${s.bounds.y}/${cellSize.y}, ${s.bounds.z}/${cellSize.z})"))
                continue
            }
            val sx = slotIndex % rowMax
            val sz = slotIndex / rowMax
            val origin = BlockPos(
                sx * (cellSize.x + cellGap),
                yBase,
                sz * (cellSize.z + cellGap),
            )
            cells[s.id] = ProjectCell(s.id, origin, cellSize, input.filename)
            byOrigin[origin] = s.id
            slotIndex++
        }

        return LayoutResult(cells, byOrigin, errors)
    }
}
