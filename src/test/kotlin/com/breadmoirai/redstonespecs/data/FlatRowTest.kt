package com.breadmoirai.redstonespecs.data

import com.breadmoirai.redstonespecs.client.screen.FlatRow
import com.breadmoirai.redstonespecs.client.screen.RowProp
import com.breadmoirai.redstonespecs.client.screen.flattenCondition
import com.breadmoirai.redstonespecs.client.screen.flattenEntries
import com.breadmoirai.redstonespecs.client.screen.reconstitute
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FlatRowTest {

    @Test
    fun `flattenCondition BoolProperty`() {
        val result = flattenCondition(StateCondition.BoolProperty("powered", true), null)
        assertEquals(listOf(RowProp.Bool("powered", true)), result)
    }

    @Test
    fun `flattenCondition IntProperty uses default bounds 0 to 15`() {
        val result = flattenCondition(StateCondition.IntProperty("power", 7), null)
        assertEquals(listOf(RowProp.ExactInt("power", 7, 0, 15)), result)
    }

    @Test
    fun `flattenCondition IntRange uses default bounds 0 to 15`() {
        val result = flattenCondition(StateCondition.IntRange("power", 1, 15), null)
        assertEquals(listOf(RowProp.RangeInt("power", 1, 15, 0, 15)), result)
    }

    @Test
    fun `flattenCondition EnumProperty with null blockState uses single-element options`() {
        val result = flattenCondition(StateCondition.EnumProperty("facing", "north"), null)
        assertEquals(listOf(RowProp.Enum("facing", "north", listOf("north"))), result)
    }

    @Test
    fun `flattenCondition All expands to multiple RowProps`() {
        val cond = StateCondition.All(listOf(
            StateCondition.BoolProperty("powered", true),
            StateCondition.BoolProperty("lit", false),
        ))
        val result = flattenCondition(cond, null)
        assertEquals(listOf(
            RowProp.Bool("powered", true),
            RowProp.Bool("lit", false),
        ), result)
    }

    @Test
    fun `flattenCondition Any returns empty list (passthrough)`() {
        val result = flattenCondition(
            StateCondition.Any(listOf(StateCondition.BoolProperty("powered", true))), null)
        assertEquals(emptyList<RowProp>(), result)
    }

    @Test
    fun `flattenCondition Not returns empty list (passthrough)`() {
        val result = flattenCondition(
            StateCondition.Not(StateCondition.BoolProperty("powered", false)), null)
        assertEquals(emptyList<RowProp>(), result)
    }

    @Test
    fun `flattenEntries separates editable rows from passthrough`() {
        val entries = listOf(
            SimTime.INIT to StateCondition.BoolProperty("powered", true),
            SimTime(0, Phase.END_OF_TICK) to StateCondition.Not(StateCondition.BoolProperty("lit", false)),
        )
        val (rows, passthrough) = flattenEntries(entries, null)
        assertEquals(1, rows.size)
        assertEquals(1, passthrough.size)
        assertEquals(SimTime.INIT, rows[0].simTime)
    }

    @Test
    fun `reconstitute single row stored unwrapped`() {
        val rows = listOf(FlatRow(SimTime.INIT, RowProp.Bool("powered", true)))
        val result = reconstitute(rows, emptyList())
        assertEquals(1, result.size)
        assertEquals(SimTime.INIT to StateCondition.BoolProperty("powered", true), result[0])
    }

    @Test
    fun `reconstitute same SimTime rows wrapped in All`() {
        val t = SimTime(0, Phase.END_OF_TICK)
        val rows = listOf(
            FlatRow(t, RowProp.Bool("powered", true)),
            FlatRow(t, RowProp.Bool("lit", false)),
        )
        val result = reconstitute(rows, emptyList())
        assertEquals(1, result.size)
        assertTrue(result[0].second is StateCondition.All)
        assertEquals(2, (result[0].second as StateCondition.All).conditions.size)
    }

    @Test
    fun `reconstitute passthrough appended after reconstituted rows`() {
        val pt = SimTime(5, Phase.END_OF_TICK) to StateCondition.Not(StateCondition.BoolProperty("powered", false))
        val rows = listOf(FlatRow(SimTime.INIT, RowProp.Bool("powered", true)))
        val result = reconstitute(rows, listOf(pt))
        assertEquals(2, result.size)
        assertEquals(pt, result[1])
    }
}
