package com.breadmoirai.redstonespecs.dsl

import net.minecraft.core.Vec3i

/**
 * The DSL-level spec value. Holds metadata in plain fields and the user's
 * [block] lambda; no entry list. Construction is via [redstoneSpec].
 */
class RedstoneSpec(
    val id: String,
    val bounds: Vec3i,
    val lifespan: Int,
    val structure: String?,
    val strict: Boolean,
    val block: SpecRun.() -> Unit,
) {
    init {
        require(bounds.x >= 1 && bounds.y >= 1 && bounds.z >= 1) {
            "bounds must be >= 1 on all axes, got: $bounds"
        }
        require(lifespan >= 1) { "lifespan must be >= 1, got $lifespan" }
    }

    companion object {
        val DEFAULT_BOUNDS: Vec3i = Vec3i(5, 5, 5)
    }
}

fun redstoneSpec(
    id: String,
    bounds: Vec3i = RedstoneSpec.DEFAULT_BOUNDS,
    lifespan: Int = 20,
    structure: String? = null,
    strict: Boolean = false,
    block: SpecRun.() -> Unit,
): RedstoneSpec = RedstoneSpec(id, bounds, lifespan, structure, strict, block)
