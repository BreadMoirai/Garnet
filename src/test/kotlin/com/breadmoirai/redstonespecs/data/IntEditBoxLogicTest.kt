package com.breadmoirai.redstonespecs.data

import com.breadmoirai.redstonespecs.client.screen.formatIntValue
import com.breadmoirai.redstonespecs.client.screen.parseIntValue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class IntEditBoxLogicTest : FunSpec({

    test("parse normal integer") {
        parseIntValue("5", min = 1, max = 10) shouldBe 5
    }

    test("parse clamps to min") {
        parseIntValue("0", min = 1, max = 10) shouldBe 1
    }

    test("parse clamps to max") {
        parseIntValue("99", min = 1, max = 10) shouldBe 10
    }

    test("parse blank returns min") {
        parseIntValue("", min = 1, max = 10) shouldBe 1
    }

    test("parse non-numeric returns min") {
        parseIntValue("abc", min = 1, max = 10) shouldBe 1
    }

    test("parse START string when min is -1 returns -1") {
        parseIntValue("START", min = -1, max = 100) shouldBe -1
    }

    test("parse START string when min is not -1 returns min") {
        parseIntValue("START", min = 1, max = 10) shouldBe 1
    }

    test("format negative one as START when min is -1") {
        formatIntValue(-1, min = -1) shouldBe "START"
    }

    test("format negative one as string when min is not -1") {
        formatIntValue(-1, min = 0) shouldBe "-1"
    }

    test("format normal value") {
        formatIntValue(42, min = 0) shouldBe "42"
    }
})
