package com.breadmoirai.redstonespecs.data

import com.breadmoirai.redstonespecs.client.screen.formatIntValue
import com.breadmoirai.redstonespecs.client.screen.parseIntValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class IntEditBoxLogicTest {

    @Test fun `parse normal integer`() {
        assertEquals(5, parseIntValue("5", min = 1, max = 10))
    }

    @Test fun `parse clamps to min`() {
        assertEquals(1, parseIntValue("0", min = 1, max = 10))
    }

    @Test fun `parse clamps to max`() {
        assertEquals(10, parseIntValue("99", min = 1, max = 10))
    }

    @Test fun `parse blank returns min`() {
        assertEquals(1, parseIntValue("", min = 1, max = 10))
    }

    @Test fun `parse non-numeric returns min`() {
        assertEquals(1, parseIntValue("abc", min = 1, max = 10))
    }

    @Test fun `parse START string when min is -1 returns -1`() {
        assertEquals(-1, parseIntValue("START", min = -1, max = 100))
    }

    @Test fun `parse START string when min is not -1 returns min`() {
        assertEquals(1, parseIntValue("START", min = 1, max = 10))
    }

    @Test fun `format negative one as START when min is -1`() {
        assertEquals("START", formatIntValue(-1, min = -1))
    }

    @Test fun `format negative one as string when min is not -1`() {
        assertEquals("-1", formatIntValue(-1, min = 0))
    }

    @Test fun `format normal value`() {
        assertEquals("42", formatIntValue(42, min = 0))
    }
}
