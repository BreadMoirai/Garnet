package com.breadmoirai.garnet.mc

import com.breadmoirai.garnet.dsl.Phase
import net.fabricmc.fabric.api.event.Event
import net.fabricmc.fabric.api.event.EventFactory
import net.minecraft.server.level.ServerLevel

fun interface SubTickPhaseEvent {
    fun onPhase(level: ServerLevel, phase: Phase)
}

object SubTickPhaseEvents {
    @JvmField
    val PHASE: Event<SubTickPhaseEvent> =
        EventFactory.createArrayBacked(SubTickPhaseEvent::class.java) { listeners ->
            SubTickPhaseEvent { level, phase ->
                for (listener in listeners) listener.onPhase(level, phase)
            }
        }
}
