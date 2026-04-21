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
    fun `BlockState roundtrip`() {
        val cond = StateCondition.BlockState(mapOf("powered" to "true", "facing" to "north"))
        assertEquals(cond, roundtrip(cond, StateCondition.CODEC))
    }

    @Test
    fun `BlockState empty properties roundtrip`() {
        val cond = StateCondition.BlockState(emptyMap())
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
    fun `Not roundtrip`() {
        val cond = StateCondition.Not(StateCondition.BlockState(mapOf("powered" to "true")))
        assertEquals(cond, roundtrip(cond, StateCondition.CODEC))
    }

    @Test
    fun `All roundtrip`() {
        val cond = StateCondition.All(listOf(
            StateCondition.BlockState(mapOf("powered" to "true")),
            StateCondition.ContainerContents(minCount = 2),
        ))
        assertEquals(cond, roundtrip(cond, StateCondition.CODEC))
    }

    @Test
    fun `Any roundtrip`() {
        val cond = StateCondition.Any(listOf(
            StateCondition.BlockState(mapOf("lit" to "true")),
            StateCondition.BlockState(mapOf("powered" to "true")),
        ))
        assertEquals(cond, roundtrip(cond, StateCondition.CODEC))
    }

    @Test
    fun `nested recursive condition roundtrip`() {
        val cond = StateCondition.All(listOf(
            StateCondition.Not(
                StateCondition.Any(listOf(
                    StateCondition.BlockState(mapOf("powered" to "false")),
                    StateCondition.ContainerContents(slot = 0),
                ))
            ),
            StateCondition.BlockState(mapOf("facing" to "south")),
        ))
        assertEquals(cond, roundtrip(cond, StateCondition.CODEC))
    }

    @Test
    fun `DEFAULT_CONDITION is powered=true BlockState`() {
        assertEquals(StateCondition.BlockState(mapOf("powered" to "true")), DEFAULT_CONDITION)
    }
}
