package com.breadmoirai.redstonespecs.client

import com.breadmoirai.redstonespecs.network.NudgeSpecBoundsC2SPayload
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.minecraft.core.BlockPos

data class HoveredFace(val originPos: BlockPos, val axis: Int, val isMax: Boolean)

var currentHoveredFace: HoveredFace? = null

data class FaceHit(val axis: Int, val isMax: Boolean, val t: Double)

fun findHoveredFace(
    ox: Double, oy: Double, oz: Double,
    dx: Double, dy: Double, dz: Double,
    minX: Double, minY: Double, minZ: Double,
    maxX: Double, maxY: Double, maxZ: Double,
): FaceHit? {
    val invDx = if (dx != 0.0) 1.0 / dx else if (ox < minX || ox > maxX) return null else Double.POSITIVE_INFINITY
    val invDy = if (dy != 0.0) 1.0 / dy else if (oy < minY || oy > maxY) return null else Double.POSITIVE_INFINITY
    val invDz = if (dz != 0.0) 1.0 / dz else if (oz < minZ || oz > maxZ) return null else Double.POSITIVE_INFINITY

    var txMin = (minX - ox) * invDx; var txMax = (maxX - ox) * invDx
    val xFromMax = txMin > txMax
    if (xFromMax) { val t = txMin; txMin = txMax; txMax = t }

    var tyMin = (minY - oy) * invDy; var tyMax = (maxY - oy) * invDy
    val yFromMax = tyMin > tyMax
    if (yFromMax) { val t = tyMin; tyMin = tyMax; tyMax = t }

    var tzMin = (minZ - oz) * invDz; var tzMax = (maxZ - oz) * invDz
    val zFromMax = tzMin > tzMax
    if (zFromMax) { val t = tzMin; tzMin = tzMax; tzMax = t }

    val tEnter = maxOf(txMin, tyMin, tzMin)
    val tExit = minOf(txMax, tyMax, tzMax)

    if (tExit < 0.0 || tEnter > tExit) return null

    return if (tEnter > 0.0) {
        when {
            tEnter == txMin -> FaceHit(0, xFromMax, tEnter)
            tEnter == tyMin -> FaceHit(1, yFromMax, tEnter)
            else            -> FaceHit(2, zFromMax, tEnter)
        }
    } else {
        when {
            tExit == txMax -> FaceHit(0, !xFromMax, tExit)
            tExit == tyMax -> FaceHit(1, !yFromMax, tExit)
            else           -> FaceHit(2, !zFromMax, tExit)
        }
    }
}

fun handleCtrlScroll(yOffset: Double) {
    val face = currentHoveredFace ?: return
    val delta = if (yOffset > 0) 1 else -1
    ClientPlayNetworking.send(NudgeSpecBoundsC2SPayload(face.originPos, face.axis, face.isMax, delta))
}
