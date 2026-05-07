package com.breadmoirai.redstonespecs.data.serial

import com.breadmoirai.redstonespecs.data.EntryKind
import com.breadmoirai.redstonespecs.data.RedstoneSpec
import com.breadmoirai.redstonespecs.data.SimTime
import com.breadmoirai.redstonespecs.data.SpecEntry
import com.breadmoirai.redstonespecs.data.StateCondition
import com.mojang.serialization.Codec
import com.mojang.serialization.DataResult
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.core.BlockPos
import net.minecraft.core.Vec3i
import java.util.Optional

object SpecJsonCodec {

    val ENTRY_KIND: Codec<EntryKind> = Codec.STRING.comapFlatMap(
        { s -> EntryKind.entries.find { it.name.equals(s, ignoreCase = true) }
            ?.let { DataResult.success(it) }
            ?: DataResult.error { "Unknown EntryKind: $s" } },
        { it.name.lowercase() },
    )

    val VEC3I: Codec<Vec3i> = RecordCodecBuilder.create { instance ->
        instance.group(
            Codec.INT.fieldOf("x").forGetter(Vec3i::getX),
            Codec.INT.fieldOf("y").forGetter(Vec3i::getY),
            Codec.INT.fieldOf("z").forGetter(Vec3i::getZ),
        ).apply(instance, ::Vec3i)
    }

    val ENTRY: Codec<SpecEntry> = RecordCodecBuilder.create { instance ->
        instance.group(
            BlockPos.CODEC.fieldOf("pos").forGetter(SpecEntry::pos),
            Codec.STRING.fieldOf("label").forGetter(SpecEntry::label),
            Codec.INT.fieldOf("color").forGetter(SpecEntry::color),
            ENTRY_KIND.fieldOf("kind").forGetter(SpecEntry::kind),
            SimTime.CODEC.fieldOf("time").forGetter(SpecEntry::time),
            StateCondition.CODEC.fieldOf("condition").forGetter(SpecEntry::condition),
        ).apply(instance, ::SpecEntry)
    }

    val SPEC: Codec<RedstoneSpec> = RecordCodecBuilder.create { instance ->
        instance.group(
            Codec.STRING.fieldOf("id").forGetter(RedstoneSpec::id),
            VEC3I.fieldOf("bounds").forGetter(RedstoneSpec::bounds),
            Codec.INT.optionalFieldOf("lifespan", 20).forGetter(RedstoneSpec::lifespan),
            Codec.STRING.optionalFieldOf("structure")
                .forGetter { Optional.ofNullable(it.structure) },
            ENTRY.listOf().optionalFieldOf("entries", emptyList())
                .forGetter(RedstoneSpec::entries),
        ).apply(instance) { id, bounds, lifespan, structure, entries ->
            RedstoneSpec(id, bounds, lifespan, structure.orElse(null), entries)
        }
    }
}
