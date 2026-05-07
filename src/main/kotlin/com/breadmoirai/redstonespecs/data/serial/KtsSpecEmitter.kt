package com.breadmoirai.redstonespecs.data.serial

import com.breadmoirai.redstonespecs.data.EntryKind
import com.breadmoirai.redstonespecs.data.Phase
import com.breadmoirai.redstonespecs.data.RedstoneSpec
import com.breadmoirai.redstonespecs.data.SimTime
import com.breadmoirai.redstonespecs.data.SpecEntry
import com.breadmoirai.redstonespecs.data.StateCondition
import com.squareup.kotlinpoet.CodeBlock

object KtsSpecEmitter {

    fun emit(spec: RedstoneSpec): String {
        val out = CodeBlock.builder()
        out.beginControlFlow("redstoneSpec(%S)", spec.id)
        out.addStatement("bounds(%L, %L, %L)", spec.bounds.x, spec.bounds.y, spec.bounds.z)
        out.addStatement("lifespan = %L", spec.lifespan)
        spec.structure?.let { out.addStatement("structure = %S", it) }

        val grouped = spec.entries.groupBy { Triple(it.pos, it.kind, EntryHeader(it.label, it.color)) }
        for ((key, entries) in grouped) {
            val (pos, kind, header) = key
            val fn = if (kind == EntryKind.INPUT) "input" else "output"
            out.beginControlFlow(
                "$fn(%L, %L, %L, label = %S, color = %L)",
                pos.x, pos.y, pos.z, header.label, formatColor(header.color),
            )
            for (entry in entries.sortedBy { it.time }) {
                emitTimeBlock(out, entry.time, kind, entry.condition)
            }
            out.endControlFlow()
        }
        out.endControlFlow()
        return out.build().toString()
    }

    private data class EntryHeader(val label: String, val color: Int)

    private fun formatColor(c: Int): String = when (c) {
        -1 -> "-1"
        else -> "0x%08X.toInt()".format(c)
    }

    private fun emitTimeBlock(out: CodeBlock.Builder, time: SimTime, kind: EntryKind, cond: StateCondition) {
        val isStart = time == SimTime.START
        val defaultPhase = if (kind == EntryKind.INPUT) Phase.START_OF_TICK else Phase.END_OF_TICK
        when {
            isStart -> out.beginControlFlow("atStart")
            time.phase == defaultPhase && time.order == 0 ->
                out.beginControlFlow("at(tick = %L)", time.tick)
            else -> out.beginControlFlow(
                "at(tick = %L, phase = %T.%L, order = %L)",
                time.tick, Phase::class, time.phase.name, time.order,
            )
        }
        emitCondition(out, cond)
        out.endControlFlow()
    }

    private fun emitCondition(out: CodeBlock.Builder, c: StateCondition) {
        when (c) {
            is StateCondition.BoolProperty -> when (c.name) {
                "powered" -> out.addStatement("powered(%L)", c.value)
                "lit"     -> out.addStatement("lit(%L)", c.value)
                else      -> out.addStatement("prop(%S, %L)", c.name, c.value)
            }
            is StateCondition.IntProperty  -> out.addStatement("intProp(%S, %L)", c.name, c.value)
            is StateCondition.EnumProperty -> out.addStatement("prop(%S, %S)", c.name, c.value)
            is StateCondition.IntRange     -> out.addStatement("range(%S, %L..%L)", c.name, c.min, c.max)
            is StateCondition.BlockType    -> out.addStatement("block(%S)", c.blockId.toString())
            is StateCondition.ContainerContents -> {
                val args = buildList {
                    c.item?.let { add("""item = "$it"""") }
                    c.slot?.let { add("slot = $it") }
                    if (c.minCount != 1) add("min = ${c.minCount}")
                }
                out.addStatement("containerHas(${args.joinToString(", ")})")
            }
            is StateCondition.All -> {
                out.beginControlFlow("all")
                c.conditions.forEach { emitCondition(out, it) }
                out.endControlFlow()
            }
            is StateCondition.Any -> {
                out.beginControlFlow("any")
                c.conditions.forEach { emitCondition(out, it) }
                out.endControlFlow()
            }
            is StateCondition.Not -> {
                out.beginControlFlow("not")
                emitCondition(out, c.condition)
                out.endControlFlow()
            }
        }
    }
}
