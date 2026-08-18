package com.breadmoirai.garnet.playback.recorder

import com.breadmoirai.garnet.playback.data.StateRecording
import com.breadmoirai.garnet.playback.data.StateRecordingView
import com.breadmoirai.garnet.core.spec.Phase
import com.breadmoirai.garnet.core.spec.SimTime
import com.breadmoirai.garnet.core.spec.StateCondition
import net.minecraft.core.BlockPos
import net.minecraft.core.Vec3i

data class EntryMarker(
    val pos: BlockPos,
    val label: String,
    val color: Int,
    val kind: Kind,
) {
    enum class Kind { INPUT, OUTPUT }
}

/**
 * Walks a [StateRecording] and emits `.spec.kts` source text using the new
 * imperative DSL. This replaces the old two-step
 * `RecordingFinalizer` (recording → `data.GarnetSpec`) → `KtsSpecEmitter` (`data.GarnetSpec` → text).
 *
 * Inputs use direct setters (`setPowered`/`setLit`; falls back to `setProp`).
 * Outputs use condition predicates (`powered()`/`lit()`; falls back to `prop()`).
 */
object RecordingDslEmitter {

    fun emit(
        id: String,
        bounds: Vec3i,
        lifespan: Int,
        structure: String?,
        strict: Boolean,
        markers: List<EntryMarker>,
        recording: StateRecording,
    ): String {
        val ioPositions = markers.map { it.pos }.toSet()
        val (firstTick, lastTick) = ioActivitySpan(recording, ioPositions)
            ?: return buildEmptySpec(id, bounds, lifespan, structure, strict)

        val view = StateRecordingView.of(recording)

        val sb = StringBuilder()
        sb.appendLine("import com.breadmoirai.garnet.core.spec.*")
        sb.appendLine("import net.minecraft.core.Vec3i")
        sb.appendLine()

        // Header
        sb.append("garnetSpec(")
        sb.append("id = ${quoted(id)}, ")
        sb.append("bounds = Vec3i(${bounds.x}, ${bounds.y}, ${bounds.z}), ")
        sb.append("lifespan = $lifespan")
        if (structure != null) sb.append(", structure = ${quoted(structure)}")
        if (strict) sb.append(", strict = true")
        sb.appendLine(") {")

        // Sort markers by (kind ordinal, then pos) for determinism
        val sortedMarkers = markers
            .distinctBy { it.pos to it.kind }
            .sortedWith(compareBy({ it.kind.ordinal }, { it.pos.x }, { it.pos.y }, { it.pos.z }))

        for (marker in sortedMarkers) {
            when (marker.kind) {
                EntryMarker.Kind.INPUT -> emitInput(sb, marker, recording, view, firstTick, lastTick)
                EntryMarker.Kind.OUTPUT -> emitOutput(sb, marker, recording, view, firstTick, lastTick)
            }
        }

        sb.append("}")
        return sb.toString()
    }

    // ---- Input emission ----

    private fun emitInput(
        sb: StringBuilder,
        marker: EntryMarker,
        recording: StateRecording,
        view: StateRecordingView,
        firstTick: Int,
        lastTick: Int,
    ) {
        sb.append("    input(${marker.pos.x}, ${marker.pos.y}, ${marker.pos.z}")
        if (marker.label.isNotEmpty()) sb.append(", label = ${quoted(marker.label)}")
        if (marker.color != -1) sb.append(", color = ${formatColor(marker.color)}")
        sb.appendLine(") {")

        // atStart — initial state at firstTick END_OF_TICK
        val initState = view.stateAt(marker.pos, SimTime(firstTick, Phase.END_OF_TICK, Int.MAX_VALUE))
        sb.appendLine("        atStart {")
        for (diff in deriveInputSetters(initState)) {
            sb.appendLine("            $diff")
        }
        sb.appendLine("        }")

        // at(t) for each change tick after firstTick
        val laterTicks = changedTicks(recording, marker.pos).filter { it in (firstTick + 1)..lastTick }
        for (t in laterTicks) {
            val state = view.stateAt(marker.pos, SimTime(t, Phase.END_OF_TICK, Int.MAX_VALUE))
            sb.appendLine("        at(${t - firstTick}) {")
            for (setter in deriveInputSetters(state)) {
                sb.appendLine("            $setter")
            }
            sb.appendLine("        }")
        }

        sb.appendLine("    }")
    }

    /**
     * Derives the setter call(s) for an input from the full block state at the target tick.
     * Specialises `setPowered` / `setLit` for well-known bool properties; falls back to
     * `setProp("name", "value")` for everything else.
     */
    private fun deriveInputSetters(state: net.minecraft.world.level.block.state.BlockState): List<String> {
        val props = state.block.stateDefinition.properties
        return props.map { prop ->
            @Suppress("UNCHECKED_CAST")
            val valueStr = (prop as net.minecraft.world.level.block.state.properties.Property<Comparable<Any>>)
                .getName(state.getValue(prop))
            when (prop.name) {
                "powered" -> "setPowered($valueStr)"
                "lit"     -> "setLit($valueStr)"
                else      -> "setProp(${quoted(prop.name)}, ${quoted(valueStr)})"
            }
        }
    }

    // ---- Output emission ----

    private fun emitOutput(
        sb: StringBuilder,
        marker: EntryMarker,
        recording: StateRecording,
        view: StateRecordingView,
        firstTick: Int,
        lastTick: Int,
    ) {
        sb.append("    output(${marker.pos.x}, ${marker.pos.y}, ${marker.pos.z}")
        if (marker.label.isNotEmpty()) sb.append(", label = ${quoted(marker.label)}")
        if (marker.color != -1) sb.append(", color = ${formatColor(marker.color)}")
        sb.appendLine(") {")

        val ticks = changedTicks(recording, marker.pos).filter { it in firstTick..lastTick }
        for (t in ticks) {
            val state = view.stateAt(marker.pos, SimTime(t, Phase.END_OF_TICK, Int.MAX_VALUE))
            val condition = deriveOutputCondition(state)
            val relTick = t - firstTick
            if (relTick == 0) {
                sb.appendLine("        atStart {")
            } else {
                sb.appendLine("        at($relTick) {")
            }
            for (line in conditionLines(condition)) {
                sb.appendLine("            $line")
            }
            sb.appendLine("        }")
        }

        sb.appendLine("    }")
    }

    /**
     * Derives a [StateCondition] predicate from the full block state at the output tick.
     * Same logic as RecordingFinalizer's `propsToCondition` path.
     */
    private fun deriveOutputCondition(state: net.minecraft.world.level.block.state.BlockState): StateCondition {
        val props = state.block.stateDefinition.properties
        val conditions = props.map { prop ->
            @Suppress("UNCHECKED_CAST")
            val p = prop as net.minecraft.world.level.block.state.properties.Property<Comparable<Any>>
            val valueStr = p.getName(state.getValue(p))
            when (prop) {
                is net.minecraft.world.level.block.state.properties.BooleanProperty ->
                    StateCondition.BoolProperty(prop.name, valueStr.toBoolean())
                is net.minecraft.world.level.block.state.properties.IntegerProperty ->
                    StateCondition.IntProperty(prop.name, valueStr.toInt())
                else ->
                    StateCondition.EnumProperty(prop.name, valueStr)
            }
        }
        return when (conditions.size) {
            0 -> StateCondition.BoolProperty("powered", true) // fallback for blocks without properties
            1 -> conditions.single()
            else -> StateCondition.All(conditions)
        }
    }

    /**
     * Converts a [StateCondition] to DSL output lines using the condition predicate API.
     * Specialises `powered()` / `lit()` for well-known bool properties; falls back to
     * `prop("name", value)` for unknown bools, or `prop("name", "value")` for enums.
     */
    private fun conditionLines(cond: StateCondition): List<String> = when (cond) {
        is StateCondition.BoolProperty -> when (cond.name) {
            "powered" -> listOf("powered(${cond.value})")
            "lit"     -> listOf("lit(${cond.value})")
            else      -> listOf("prop(${quoted(cond.name)}, ${cond.value})")
        }
        is StateCondition.IntProperty  -> listOf("intProp(${quoted(cond.name)}, ${cond.value})")
        is StateCondition.EnumProperty -> listOf("prop(${quoted(cond.name)}, ${quoted(cond.value)})")
        is StateCondition.IntRange     -> listOf("range(${quoted(cond.name)}, ${cond.min}..${cond.max})")
        is StateCondition.BlockType    -> listOf("block(${quoted(cond.blockId.toString())})")
        is StateCondition.All          -> cond.conditions.flatMap { conditionLines(it) }
        is StateCondition.Any          -> {
            val inner = cond.conditions.flatMap { conditionLines(it) }
            listOf("any {") + inner.map { "    $it" } + listOf("}")
        }
        is StateCondition.Not          -> {
            val inner = conditionLines(cond.condition)
            listOf("not {") + inner.map { "    $it" } + listOf("}")
        }
        is StateCondition.ContainerContents -> {
            val args = buildList {
                cond.item?.let { add("item = \"$it\"") }
                cond.slot?.let { add("slot = $it") }
                if (cond.minCount != 1) add("min = ${cond.minCount}")
            }
            listOf("containerHas(${args.joinToString(", ")})")
        }
    }

    // ---- Helpers ----

    /** Returns inclusive [first, last] tick indices where any I/O block changed state, or null. */
    private fun ioActivitySpan(rec: StateRecording, io: Set<BlockPos>): Pair<Int, Int>? {
        var first = Int.MAX_VALUE
        var last = Int.MIN_VALUE
        for (change in rec.changes) {
            if (change.pos !in io) continue
            val t = change.simTime.tick
            if (t < first) first = t
            if (t > last) last = t
        }
        return if (first == Int.MAX_VALUE) null else first to last
    }

    private fun changedTicks(rec: StateRecording, pos: BlockPos): List<Int> =
        rec.changes.asSequence()
            .filter { it.pos == pos }
            .map { it.simTime.tick }
            .distinct()
            .sorted()
            .toList()

    private fun buildEmptySpec(
        id: String,
        bounds: Vec3i,
        lifespan: Int,
        structure: String?,
        strict: Boolean,
    ): String {
        val sb = StringBuilder()
        sb.appendLine("import com.breadmoirai.garnet.core.spec.*")
        sb.appendLine("import net.minecraft.core.Vec3i")
        sb.appendLine()
        sb.append("garnetSpec(")
        sb.append("id = ${quoted(id)}, ")
        sb.append("bounds = Vec3i(${bounds.x}, ${bounds.y}, ${bounds.z}), ")
        sb.append("lifespan = $lifespan")
        if (structure != null) sb.append(", structure = ${quoted(structure)}")
        if (strict) sb.append(", strict = true")
        sb.append(") {}")
        return sb.toString()
    }

    /**
     * Emits a minimal stub `.spec.kts` source for a new spec with the given [id].
     * Used by the editor's new-spec flow to initialize a file
     * before the first recording.
     */
    fun emitStub(id: String): String {
        val sb = StringBuilder()
        sb.appendLine("import com.breadmoirai.garnet.core.spec.*")
        sb.appendLine("import net.minecraft.core.Vec3i")
        sb.appendLine()
        sb.append("garnetSpec(id = ${quoted(id)}, bounds = Vec3i(5, 5, 5), lifespan = 20) {}")
        return sb.toString()
    }

    private fun quoted(s: String): String = "\"${s.replace("\\", "\\\\").replace("\"", "\\\"")}\""

    private fun formatColor(c: Int): String = when (c) {
        -1 -> "-1"
        else -> "0x%08X.toInt()".format(c)
    }
}
