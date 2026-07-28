package com.breadmoirai.garnet.data

import com.breadmoirai.garnet.dsl.DEFAULT_CONDITION
import com.breadmoirai.garnet.dsl.StateCondition
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import net.minecraft.SharedConstants
import net.minecraft.nbt.NbtOps
import net.minecraft.resources.Identifier
import net.minecraft.server.Bootstrap

class StateConditionTest : FunSpec({

    beforeSpec {
        SharedConstants.tryDetectVersion()
        Bootstrap.bootStrap()
    }

    fun <T> roundtrip(value: T, codec: com.mojang.serialization.Codec<T>): T {
        val encoded = codec.encodeStart(NbtOps.INSTANCE, value).getOrThrow()
        return codec.parse(NbtOps.INSTANCE, encoded).getOrThrow()
    }

    test("BlockType roundtrip") {
        val cond = StateCondition.BlockType(Identifier.fromNamespaceAndPath("minecraft", "redstone_lamp"))
        roundtrip(cond, StateCondition.CODEC) shouldBe cond
    }

    test("BoolProperty roundtrip true") {
        val cond = StateCondition.BoolProperty("powered", true)
        roundtrip(cond, StateCondition.CODEC) shouldBe cond
    }

    test("BoolProperty roundtrip false") {
        val cond = StateCondition.BoolProperty("lit", false)
        roundtrip(cond, StateCondition.CODEC) shouldBe cond
    }

    test("IntProperty roundtrip") {
        val cond = StateCondition.IntProperty("power", 7)
        roundtrip(cond, StateCondition.CODEC) shouldBe cond
    }

    test("EnumProperty roundtrip") {
        val cond = StateCondition.EnumProperty("facing", "north")
        roundtrip(cond, StateCondition.CODEC) shouldBe cond
    }

    test("ContainerContents no optionals roundtrip") {
        val cond = StateCondition.ContainerContents()
        roundtrip(cond, StateCondition.CODEC) shouldBe cond
    }

    test("ContainerContents with slot and item roundtrip") {
        val cond = StateCondition.ContainerContents(
            slot = 3,
            item = Identifier.fromNamespaceAndPath("minecraft", "diamond"),
            minCount = 5,
        )
        roundtrip(cond, StateCondition.CODEC) shouldBe cond
    }

    test("All roundtrip") {
        val cond = StateCondition.All(listOf(
            StateCondition.BoolProperty("powered", true),
            StateCondition.IntProperty("power", 4),
        ))
        roundtrip(cond, StateCondition.CODEC) shouldBe cond
    }

    test("Any roundtrip") {
        val cond = StateCondition.Any(listOf(
            StateCondition.BoolProperty("lit", true),
            StateCondition.BoolProperty("powered", true),
        ))
        roundtrip(cond, StateCondition.CODEC) shouldBe cond
    }

    test("Not roundtrip") {
        val cond = StateCondition.Not(StateCondition.BoolProperty("powered", true))
        roundtrip(cond, StateCondition.CODEC) shouldBe cond
    }

    test("nested recursive condition roundtrip") {
        val cond = StateCondition.All(listOf(
            StateCondition.Not(
                StateCondition.Any(listOf(
                    StateCondition.BoolProperty("powered", false),
                    StateCondition.ContainerContents(slot = 0),
                ))
            ),
            StateCondition.EnumProperty("facing", "south"),
        ))
        roundtrip(cond, StateCondition.CODEC) shouldBe cond
    }

    test("DEFAULT_CONDITION is BoolProperty powered=true") {
        DEFAULT_CONDITION shouldBe StateCondition.BoolProperty("powered", true)
    }

    test("IntRange roundtrip") {
        val cond = StateCondition.IntRange("power", 1, 15)
        roundtrip(cond, StateCondition.CODEC) shouldBe cond
    }
})
