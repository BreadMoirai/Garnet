package com.breadmoirai.redstonespecs.data

import net.minecraft.SharedConstants
import net.minecraft.nbt.NbtOps
import net.minecraft.resources.Identifier
import net.minecraft.server.Bootstrap
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StateConditionTest {

    @BeforeAll
    fun bootstrap() {
        SharedConstants.tryDetectVersion()
        Bootstrap.bootStrap()
    }

    private fun <T> roundtrip(value: T, codec: com.mojang.serialization.Codec<T>): T {
        val encoded = codec.encodeStart(NbtOps.INSTANCE, value).getOrThrow()
        return codec.parse(NbtOps.INSTANCE, encoded).getOrThrow()
    }

    @Test
    fun `BlockType roundtrip`() {
        val cond = StateCondition.BlockType(Identifier.fromNamespaceAndPath("minecraft", "redstone_lamp"))
        assertEquals(cond, roundtrip(cond, StateCondition.CODEC))
    }

    @Test
    fun `BoolProperty roundtrip true`() {
        val cond = StateCondition.BoolProperty("powered", true)
        assertEquals(cond, roundtrip(cond, StateCondition.CODEC))
    }

    @Test
    fun `BoolProperty roundtrip false`() {
        val cond = StateCondition.BoolProperty("lit", false)
        assertEquals(cond, roundtrip(cond, StateCondition.CODEC))
    }

    @Test
    fun `IntProperty roundtrip`() {
        val cond = StateCondition.IntProperty("power", 7)
        assertEquals(cond, roundtrip(cond, StateCondition.CODEC))
    }

    @Test
    fun `EnumProperty roundtrip`() {
        val cond = StateCondition.EnumProperty("facing", "north")
        assertEquals(cond, roundtrip(cond, StateCondition.CODEC))
    }

    @Test
    fun `ContainerContents no optionals roundtrip`() {
        val cond = StateCondition.ContainerContents()
        assertEquals(cond, roundtrip(cond, StateCondition.CODEC))
    }

    @Test
    fun `ContainerContents with slot and item roundtrip`() {
        val cond = StateCondition.ContainerContents(
            slot = 3,
            item = Identifier.fromNamespaceAndPath("minecraft", "diamond"),
            minCount = 5,
        )
        assertEquals(cond, roundtrip(cond, StateCondition.CODEC))
    }

    @Test
    fun `All roundtrip`() {
        val cond = StateCondition.All(listOf(
            StateCondition.BoolProperty("powered", true),
            StateCondition.IntProperty("power", 4),
        ))
        assertEquals(cond, roundtrip(cond, StateCondition.CODEC))
    }

    @Test
    fun `Any roundtrip`() {
        val cond = StateCondition.Any(listOf(
            StateCondition.BoolProperty("lit", true),
            StateCondition.BoolProperty("powered", true),
        ))
        assertEquals(cond, roundtrip(cond, StateCondition.CODEC))
    }

    @Test
    fun `Not roundtrip`() {
        val cond = StateCondition.Not(StateCondition.BoolProperty("powered", true))
        assertEquals(cond, roundtrip(cond, StateCondition.CODEC))
    }

    @Test
    fun `nested recursive condition roundtrip`() {
        val cond = StateCondition.All(listOf(
            StateCondition.Not(
                StateCondition.Any(listOf(
                    StateCondition.BoolProperty("powered", false),
                    StateCondition.ContainerContents(slot = 0),
                ))
            ),
            StateCondition.EnumProperty("facing", "south"),
        ))
        assertEquals(cond, roundtrip(cond, StateCondition.CODEC))
    }

    @Test
    fun `DEFAULT_CONDITION is BoolProperty powered=true`() {
        assertEquals(StateCondition.BoolProperty("powered", true), DEFAULT_CONDITION)
    }
}
