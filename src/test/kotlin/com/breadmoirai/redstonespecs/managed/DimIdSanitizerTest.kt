package com.breadmoirai.redstonespecs.managed

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class DimIdSanitizerTest : FunSpec({

    test("preserves nested folder subpath") {
        DimIdSanitizer.toPath("pistons/doors") shouldBe "managed/pistons/doors"
    }

    test("lowercases uppercase letters") {
        DimIdSanitizer.toPath("ABC") shouldBe "managed/abc"
    }

    test("replaces disallowed characters with underscore") {
        DimIdSanitizer.toPath("2x2 with space") shouldBe "managed/2x2_with_space"
        DimIdSanitizer.toPath("a@b") shouldBe "managed/a_b"
    }

    test("preserves slash, dot, dash, underscore, digit") {
        DimIdSanitizer.toPath("a-b_c.d/0") shouldBe "managed/a-b_c.d/0"
    }

    test("empty input maps to managed root") {
        DimIdSanitizer.toPath("") shouldBe "managed"
    }
})
