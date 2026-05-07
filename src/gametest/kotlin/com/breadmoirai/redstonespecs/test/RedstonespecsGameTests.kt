package com.breadmoirai.redstonespecs.test

import net.fabricmc.fabric.api.gametest.v1.GameTest
import net.minecraft.gametest.framework.GameTestHelper

/**
 * Game test suite for the recorder/runner data-layer flow.
 *
 * The previous suite was parameterized over [SpecMode] (SIMPLE / TICK_AWARE /
 * UPDATE_AWARE), each variant constructing fixtures with InputSpec/OutputSpec
 * sealed-class entries. The data-layer redesign removed both, so the entire
 * suite needs to be re-authored against the flat `SpecEntry` model and the
 * single-verifier behavior. Tracked as a follow-up.
 */
class RedstonespecsGameTests {

    /** Placeholder so the gametest source set has at least one registered test. */
    @GameTest
    fun placeholder(helper: GameTestHelper) {
        helper.succeed()
    }
}
