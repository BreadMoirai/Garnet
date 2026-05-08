package com.breadmoirai.redstonespecs.data

import com.breadmoirai.redstonespecs.client.screen.formatIntValue
import com.breadmoirai.redstonespecs.client.screen.parseIntValue
import dev.kensa.ActionUnderTest
import dev.kensa.RenderedValue
import dev.kensa.StateExtractor
import dev.kensa.junit.KensaTest
import dev.kensa.kotest.WithKotest
import io.kotest.matchers.Matcher
import io.kotest.matchers.MatcherResult
import org.junit.jupiter.api.Test

/**
 * Tests for [parseIntValue] and [formatIntValue].
 * Lambdas live in private helpers so Kensa's Kotlin source parser never enters InLambda state
 * while walking a @Test method body.
 */
class IntEditBoxLogicTest : KensaTest, WithKotest {

    @RenderedValue
    private var intResult: Int = 0

    @RenderedValue
    private var strResult: String = ""

    // ── @Test methods: no lambda literals ──────────────────────────────────

    @Test
    fun parseNormalInteger() {
        whenever(parseIntAction("5", 1, 10))
        then(capturedInt(), equalInt(5))
    }

    @Test
    fun parseClampsToMin() {
        whenever(parseIntAction("0", 1, 10))
        then(capturedInt(), equalInt(1))
    }

    @Test
    fun parseClampsToMax() {
        whenever(parseIntAction("99", 1, 10))
        then(capturedInt(), equalInt(10))
    }

    @Test
    fun parseBlankReturnsMin() {
        whenever(parseIntAction("", 1, 10))
        then(capturedInt(), equalInt(1))
    }

    @Test
    fun parseNonNumericReturnsMin() {
        whenever(parseIntAction("abc", 1, 10))
        then(capturedInt(), equalInt(1))
    }

    @Test
    fun parseStartStringWhenMinIsMinusOneReturnsMinusOne() {
        whenever(parseIntAction("START", -1, 100))
        then(capturedInt(), equalInt(-1))
    }

    @Test
    fun parseStartStringWhenMinIsNotMinusOneReturnsMin() {
        whenever(parseIntAction("START", 1, 10))
        then(capturedInt(), equalInt(1))
    }

    @Test
    fun formatNegativeOneAsStartWhenMinIsMinusOne() {
        whenever(formatIntAction(-1, -1))
        then(capturedStr(), equalStr("START"))
    }

    @Test
    fun formatNegativeOneAsStringWhenMinIsNotMinusOne() {
        whenever(formatIntAction(-1, 0))
        then(capturedStr(), equalStr("-1"))
    }

    @Test
    fun formatNormalValue() {
        whenever(formatIntAction(42, 0))
        then(capturedStr(), equalStr("42"))
    }

    // ── Helpers: lambdas live here, not in @Test bodies ────────────────────

    private fun parseIntAction(text: String, min: Int, max: Int) =
        ActionUnderTest { _, _ -> intResult = parseIntValue(text, min, max) }

    private fun formatIntAction(value: Int, min: Int) =
        ActionUnderTest { _, _ -> strResult = formatIntValue(value, min) }

    private fun capturedInt() = StateExtractor<Int> { intResult }

    private fun capturedStr() = StateExtractor<String> { strResult }

    private fun equalInt(expected: Int): Matcher<Int> = Matcher { actual ->
        MatcherResult(
            actual == expected,
            { "expected $expected but was $actual" },
            { "expected not $expected but was $actual" },
        )
    }

    private fun equalStr(expected: String): Matcher<String> = Matcher { actual ->
        MatcherResult(
            actual == expected,
            { "expected \"$expected\" but was \"$actual\"" },
            { "expected not \"$expected\" but was \"$actual\"" },
        )
    }
}
