package com.breadmoirai.redstonespecs.data

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.core.UUIDUtil
import net.minecraft.world.level.levelgen.structure.BoundingBox
import java.util.UUID

data class RedstoneSpec(
    val id: UUID,
    val name: String,
    val bounds: BoundingBox,
    val oneShot: Boolean,
    val specCases: List<SpecCase>,
) {
    companion object {
        val CODEC: Codec<RedstoneSpec> = RecordCodecBuilder.create { instance ->
            instance.group(
                UUIDUtil.CODEC.fieldOf("id").forGetter(RedstoneSpec::id),
                Codec.STRING.fieldOf("name").forGetter(RedstoneSpec::name),
                BoundingBox.CODEC.fieldOf("bounds").forGetter(RedstoneSpec::bounds),
                Codec.BOOL.optionalFieldOf("one_shot", false).forGetter(RedstoneSpec::oneShot),
                SpecCase.CODEC.listOf().optionalFieldOf("spec_cases", emptyList())
                    .forGetter(RedstoneSpec::specCases),
            ).apply(instance, ::RedstoneSpec)
        }
    }
}
