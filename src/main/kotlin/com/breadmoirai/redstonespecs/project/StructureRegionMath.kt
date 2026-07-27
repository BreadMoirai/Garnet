package com.breadmoirai.redstonespecs.project

/** A tight axis-aligned box in region-local coordinates. */
data class FitBox(
    val minX: Int, val minY: Int, val minZ: Int,
    val sizeX: Int, val sizeY: Int, val sizeZ: Int,
)

/** Structures this tall (blocks) can't be floored at sea level without hitting the ceiling. */
const val TALL_THRESHOLD = 256

/**
 * Scans a [dimX]x[dimY]x[dimZ] volume in local coords and returns the tight box enclosing every
 * cell for which [isNonAir] is true, or null if none are.
 */
fun autoFit(dimX: Int, dimY: Int, dimZ: Int, isNonAir: (Int, Int, Int) -> Boolean): FitBox? {
    var minX = Int.MAX_VALUE; var minY = Int.MAX_VALUE; var minZ = Int.MAX_VALUE
    var maxX = Int.MIN_VALUE; var maxY = Int.MIN_VALUE; var maxZ = Int.MIN_VALUE
    var any = false
    for (x in 0 until dimX) for (y in 0 until dimY) for (z in 0 until dimZ) {
        if (isNonAir(x, y, z)) {
            any = true
            if (x < minX) minX = x; if (x > maxX) maxX = x
            if (y < minY) minY = y; if (y > maxY) maxY = y
            if (z < minZ) minZ = z; if (z > maxZ) maxZ = z
        }
    }
    if (!any) return null
    return FitBox(minX, minY, minZ, maxX - minX + 1, maxY - minY + 1, maxZ - minZ + 1)
}

/** Start coord so a [size]-wide box is centered in a [regionWidth]-wide region beginning at [regionStart]. */
fun centeredStart(regionStart: Int, regionWidth: Int, size: Int): Int = regionStart + (regionWidth - size) / 2

/**
 * Y origin for placing a structure of height [structHeight]. Floored at [yBase] (sea level) for
 * normal builds; vertically centered in `[regionMinY, regionMinY + regionHeight)` once the
 * structure reaches [TALL_THRESHOLD], where flooring at sea level would clip the build ceiling.
 */
fun anchorY(structHeight: Int, yBase: Int, regionMinY: Int, regionHeight: Int): Int =
    if (structHeight >= TALL_THRESHOLD) regionMinY + (regionHeight - structHeight) / 2 else yBase
