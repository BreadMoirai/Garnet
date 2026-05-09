package com.breadmoirai.redstonespecs.managed

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import java.util.UUID

class ManagedSessionTest : FunSpec({

    test("setActive upserts and overwrites prior session for same UUID") {
        val u = UUID.randomUUID()
        try {
            ManagedSession.setActive(u, "folder/a")
            ManagedSession.get(u)?.activeSubpath shouldBe "folder/a"
            ManagedSession.setActive(u, "folder/b")
            ManagedSession.get(u)?.activeSubpath shouldBe "folder/b"
        } finally {
            ManagedSession.clear(u)
        }
    }

    test("setActive(null) records a session with null subpath") {
        val u = UUID.randomUUID()
        try {
            ManagedSession.setActive(u, null)
            val s = ManagedSession.get(u)
            s.shouldBe(ManagedSession(u, null))
        } finally {
            ManagedSession.clear(u)
        }
    }

    test("get returns null after clear") {
        val u = UUID.randomUUID()
        ManagedSession.setActive(u, "x")
        ManagedSession.clear(u)
        ManagedSession.get(u).shouldBeNull()
    }

    test("set stores a constructed session") {
        val u = UUID.randomUUID()
        try {
            ManagedSession.set(ManagedSession(u, "x"))
            ManagedSession.get(u)?.activeSubpath shouldBe "x"
        } finally {
            ManagedSession.clear(u)
        }
    }

    test("all() reflects every active session") {
        val u1 = UUID.randomUUID()
        val u2 = UUID.randomUUID()
        try {
            ManagedSession.setActive(u1, "a")
            ManagedSession.setActive(u2, "b")
            val ids = ManagedSession.all().map { it.playerId }.toSet()
            ids shouldContain u1
            ids shouldContain u2
        } finally {
            ManagedSession.clear(u1)
            ManagedSession.clear(u2)
        }
        ManagedSession.all().map { it.playerId }.toSet().also {
            it shouldNotContain u1
            it shouldNotContain u2
        }
    }

    test("UUIDs are isolated") {
        val u1 = UUID.randomUUID()
        val u2 = UUID.randomUUID()
        try {
            ManagedSession.setActive(u1, "a")
            ManagedSession.get(u2).shouldBeNull()
            ManagedSession.get(u1)?.activeSubpath shouldBe "a"
        } finally {
            ManagedSession.clear(u1)
            ManagedSession.clear(u2)
        }
    }
})
