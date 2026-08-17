package com.breadmoirai.garnet.editor.explorer.data

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import java.util.UUID

class EditorSessionTest : FunSpec({

    test("setActive upserts and overwrites prior session for same UUID") {
        val u = UUID.randomUUID()
        try {
            EditorSession.setActive(u, "folder/a")
            EditorSession.get(u)?.activeSubpath shouldBe "folder/a"
            EditorSession.setActive(u, "folder/b")
            EditorSession.get(u)?.activeSubpath shouldBe "folder/b"
        } finally {
            EditorSession.clear(u)
        }
    }

    test("setActive(null) records a session with null subpath") {
        val u = UUID.randomUUID()
        try {
            EditorSession.setActive(u, null)
            val s = EditorSession.get(u)
            s.shouldBe(EditorSession(u, null))
        } finally {
            EditorSession.clear(u)
        }
    }

    test("get returns null after clear") {
        val u = UUID.randomUUID()
        EditorSession.setActive(u, "x")
        EditorSession.clear(u)
        EditorSession.get(u).shouldBeNull()
    }

    test("set stores a constructed session") {
        val u = UUID.randomUUID()
        try {
            EditorSession.set(EditorSession(u, "x"))
            EditorSession.get(u)?.activeSubpath shouldBe "x"
        } finally {
            EditorSession.clear(u)
        }
    }

    test("all() reflects every active session") {
        val u1 = UUID.randomUUID()
        val u2 = UUID.randomUUID()
        try {
            EditorSession.setActive(u1, "a")
            EditorSession.setActive(u2, "b")
            val ids = EditorSession.all().map { it.playerId }.toSet()
            ids shouldContain u1
            ids shouldContain u2
        } finally {
            EditorSession.clear(u1)
            EditorSession.clear(u2)
        }
        EditorSession.all().map { it.playerId }.toSet().also {
            it shouldNotContain u1
            it shouldNotContain u2
        }
    }

    test("UUIDs are isolated") {
        val u1 = UUID.randomUUID()
        val u2 = UUID.randomUUID()
        try {
            EditorSession.setActive(u1, "a")
            EditorSession.get(u2).shouldBeNull()
            EditorSession.get(u1)?.activeSubpath shouldBe "a"
        } finally {
            EditorSession.clear(u1)
            EditorSession.clear(u2)
        }
    }

    test("clearSessionUnder clears an active subpath equal to the deleted path") {
        val u = UUID.randomUUID()
        try {
            EditorSession.setActive(u, "redstone")
            EditorSession.clearSessionUnder(u, "redstone")
            EditorSession.get(u)?.activeSubpath.shouldBeNull()
        } finally {
            EditorSession.clear(u)
        }
    }

    test("clearSessionUnder clears an active subpath nested under the deleted path") {
        val u = UUID.randomUUID()
        try {
            EditorSession.setActive(u, "redstone/clocks")
            EditorSession.clearSessionUnder(u, "redstone")
            EditorSession.get(u)?.activeSubpath.shouldBeNull()
        } finally {
            EditorSession.clear(u)
        }
    }

    test("clearSessionUnder leaves an unrelated active subpath alone") {
        val u = UUID.randomUUID()
        try {
            EditorSession.setActive(u, "adders")
            EditorSession.clearSessionUnder(u, "redstone")
            EditorSession.get(u)?.activeSubpath shouldBe "adders"
        } finally {
            EditorSession.clear(u)
        }
    }

    test("clearSessionUnder matches on a full path segment, not a string prefix") {
        // Deleting "redstone" must not clear a session in the SIBLING folder "redstoneworks".
        val u = UUID.randomUUID()
        try {
            EditorSession.setActive(u, "redstoneworks/clocks")
            EditorSession.clearSessionUnder(u, "redstone")
            EditorSession.get(u)?.activeSubpath shouldBe "redstoneworks/clocks"
        } finally {
            EditorSession.clear(u)
        }
    }
})
