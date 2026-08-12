package com.breadmoirai.garnet.editor.data

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

class EditorNamesTest : FunSpec({

    test("a plain name against no siblings is valid") {
        EditorNames.validate("clocks", emptyList()) shouldBe null
    }

    test("blank and whitespace-only names are rejected") {
        EditorNames.validate("", emptyList()).shouldNotBeNull()
        EditorNames.validate("   ", emptyList()).shouldNotBeNull()
    }

    test("path separators are rejected") {
        EditorNames.validate("a/b", emptyList()).shouldNotBeNull()
        EditorNames.validate("a\\b", emptyList()).shouldNotBeNull()
    }

    test("dot and dot-dot are rejected") {
        EditorNames.validate(".", emptyList()).shouldNotBeNull()
        EditorNames.validate("..", emptyList()).shouldNotBeNull()
    }

    test("a name matching an existing sibling is rejected, case-insensitively") {
        EditorNames.validate("clocks", listOf("adders", "clocks")).shouldNotBeNull()
        EditorNames.validate("CLOCKS", listOf("clocks")).shouldNotBeNull()
    }

    test("resolveFinalName appends .nbt for structures and leaves folders alone") {
        EditorNames.resolveFinalName("gadget", NewNodeKind.STRUCTURE) shouldBe "gadget.nbt"
        EditorNames.resolveFinalName("gadget.nbt", NewNodeKind.STRUCTURE) shouldBe "gadget.nbt"
        EditorNames.resolveFinalName("clocks", NewNodeKind.FOLDER) shouldBe "clocks"
    }

    test("resolveFinalName normalizes an existing .nbt extension to lowercase") {
        // Regression: a case-insensitively-ACCEPTED but un-normalized extension left
        // handleNewStructure's case-sensitive removeSuffix(".nbt") a no-op for "gadget.NBT", so
        // create() appended its own ".nbt" on top and the file landed on disk as "gadget.NBT.nbt".
        EditorNames.resolveFinalName("gadget.NBT", NewNodeKind.STRUCTURE) shouldBe "gadget.nbt"
        EditorNames.resolveFinalName("gadget.Nbt", NewNodeKind.STRUCTURE) shouldBe "gadget.nbt"
    }

    test("resolveFinalName trims surrounding whitespace") {
        EditorNames.resolveFinalName("  clocks  ", NewNodeKind.FOLDER) shouldBe "clocks"
    }

    test("the sibling check runs against the resolved name, not the typed one") {
        // "gadget" resolves to "gadget.nbt", which collides.
        val final = EditorNames.resolveFinalName("gadget", NewNodeKind.STRUCTURE)
        EditorNames.validate(final, listOf("gadget.nbt")).shouldNotBeNull()
    }

    test("duplicateName appends ' copy' before the extension") {
        EditorNames.duplicateName("house.nbt", listOf("house.nbt"), isFolder = false) shouldBe "house copy.nbt"
    }

    test("duplicateName counts up from 2 once ' copy' is taken") {
        EditorNames.duplicateName(
            "house.nbt", listOf("house.nbt", "house copy.nbt"), isFolder = false,
        ) shouldBe "house copy 2.nbt"
        EditorNames.duplicateName(
            "house.nbt", listOf("house.nbt", "house copy.nbt", "house copy 2.nbt"), isFolder = false,
        ) shouldBe "house copy 3.nbt"
    }

    test("duplicateName matches siblings case-insensitively, like validate") {
        // A copy that only differs from an existing sibling by case would be rejected by validate()
        // a moment later on the very filesystems (NTFS, APFS) this project runs on.
        EditorNames.duplicateName(
            "house.nbt", listOf("house.nbt", "HOUSE COPY.NBT"), isFolder = false,
        ) shouldBe "house copy 2.nbt"
    }

    test("duplicateName treats a folder's dots as part of its name, not an extension") {
        EditorNames.duplicateName("redstone", listOf("redstone"), isFolder = true) shouldBe "redstone copy"
        EditorNames.duplicateName("my.stuff", listOf("my.stuff"), isFolder = true) shouldBe "my.stuff copy"
    }

    test("duplicateName treats a leading dot as part of the name, not an extension") {
        EditorNames.duplicateName(".gitignore", listOf(".gitignore"), isFolder = false) shouldBe ".gitignore copy"
    }

    test("duplicateName produces a name validate accepts") {
        val name = EditorNames.duplicateName("house.nbt", listOf("house.nbt"), isFolder = false)
        EditorNames.validate(name, listOf("house.nbt")) shouldBe null
    }
})
