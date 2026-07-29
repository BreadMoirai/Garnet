package com.breadmoirai.garnet.project

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

class ProjectNamesTest : FunSpec({

    test("a plain name against no siblings is valid") {
        ProjectNames.validate("clocks", emptyList()) shouldBe null
    }

    test("blank and whitespace-only names are rejected") {
        ProjectNames.validate("", emptyList()).shouldNotBeNull()
        ProjectNames.validate("   ", emptyList()).shouldNotBeNull()
    }

    test("path separators are rejected") {
        ProjectNames.validate("a/b", emptyList()).shouldNotBeNull()
        ProjectNames.validate("a\\b", emptyList()).shouldNotBeNull()
    }

    test("dot and dot-dot are rejected") {
        ProjectNames.validate(".", emptyList()).shouldNotBeNull()
        ProjectNames.validate("..", emptyList()).shouldNotBeNull()
    }

    test("a name matching an existing sibling is rejected, case-insensitively") {
        ProjectNames.validate("clocks", listOf("adders", "clocks")).shouldNotBeNull()
        ProjectNames.validate("CLOCKS", listOf("clocks")).shouldNotBeNull()
    }

    test("resolveFinalName appends .nbt for structures and leaves folders alone") {
        ProjectNames.resolveFinalName("gadget", NewNodeKind.STRUCTURE) shouldBe "gadget.nbt"
        ProjectNames.resolveFinalName("gadget.nbt", NewNodeKind.STRUCTURE) shouldBe "gadget.nbt"
        ProjectNames.resolveFinalName("gadget.NBT", NewNodeKind.STRUCTURE) shouldBe "gadget.NBT"
        ProjectNames.resolveFinalName("clocks", NewNodeKind.FOLDER) shouldBe "clocks"
    }

    test("resolveFinalName trims surrounding whitespace") {
        ProjectNames.resolveFinalName("  clocks  ", NewNodeKind.FOLDER) shouldBe "clocks"
    }

    test("the sibling check runs against the resolved name, not the typed one") {
        // "gadget" resolves to "gadget.nbt", which collides.
        val final = ProjectNames.resolveFinalName("gadget", NewNodeKind.STRUCTURE)
        ProjectNames.validate(final, listOf("gadget.nbt")).shouldNotBeNull()
    }
})
