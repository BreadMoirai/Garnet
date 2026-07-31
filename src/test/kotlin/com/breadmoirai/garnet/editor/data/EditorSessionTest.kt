package com.breadmoirai.garnet.editor.data

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
})
