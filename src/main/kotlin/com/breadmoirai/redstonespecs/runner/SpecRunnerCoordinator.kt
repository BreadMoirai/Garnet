package com.breadmoirai.redstonespecs.runner

import com.breadmoirai.redstonespecs.data.Phase
import net.minecraft.server.level.ServerLevel

object SpecRunnerCoordinator {

    /**
     * Called at each sub-tick phase boundary for the given level.
     * Phase 6 will attach active SpecRunner instances here.
     */
    fun onPhase(level: ServerLevel, tick: Int, phase: Phase) {
        // Phase 6: iterate active runners and dispatch
    }
}
