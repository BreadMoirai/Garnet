package com.breadmoirai.redstonespecs.project

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import java.util.UUID

class ProjectSessionTest : FunSpec({

    test("setActive upserts and overwrites prior session for same UUID") {
        val u = UUID.randomUUID()
        try {
            ProjectSession.setActive(u, "folder/a")
            ProjectSession.get(u)?.activeSubpath shouldBe "folder/a"
            ProjectSession.setActive(u, "folder/b")
            ProjectSession.get(u)?.activeSubpath shouldBe "folder/b"
        } finally {
            ProjectSession.clear(u)
        }
    }

    test("setActive(null) records a session with null subpath") {
        val u = UUID.randomUUID()
        try {
            ProjectSession.setActive(u, null)
            val s = ProjectSession.get(u)
            s.shouldBe(ProjectSession(u, null))
        } finally {
            ProjectSession.clear(u)
        }
    }

    test("get returns null after clear") {
        val u = UUID.randomUUID()
        ProjectSession.setActive(u, "x")
        ProjectSession.clear(u)
        ProjectSession.get(u).shouldBeNull()
    }

    test("set stores a constructed session") {
        val u = UUID.randomUUID()
        try {
            ProjectSession.set(ProjectSession(u, "x"))
            ProjectSession.get(u)?.activeSubpath shouldBe "x"
        } finally {
            ProjectSession.clear(u)
        }
    }

    test("all() reflects every active session") {
        val u1 = UUID.randomUUID()
        val u2 = UUID.randomUUID()
        try {
            ProjectSession.setActive(u1, "a")
            ProjectSession.setActive(u2, "b")
            val ids = ProjectSession.all().map { it.playerId }.toSet()
            ids shouldContain u1
            ids shouldContain u2
        } finally {
            ProjectSession.clear(u1)
            ProjectSession.clear(u2)
        }
        ProjectSession.all().map { it.playerId }.toSet().also {
            it shouldNotContain u1
            it shouldNotContain u2
        }
    }

    test("UUIDs are isolated") {
        val u1 = UUID.randomUUID()
        val u2 = UUID.randomUUID()
        try {
            ProjectSession.setActive(u1, "a")
            ProjectSession.get(u2).shouldBeNull()
            ProjectSession.get(u1)?.activeSubpath shouldBe "a"
        } finally {
            ProjectSession.clear(u1)
            ProjectSession.clear(u2)
        }
    }
})
