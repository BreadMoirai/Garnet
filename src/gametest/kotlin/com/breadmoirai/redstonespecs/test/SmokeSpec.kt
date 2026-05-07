package com.breadmoirai.redstonespecs.test

import com.breadmoirai.redstonespecs.testing.ServerTestSpec
import com.breadmoirai.redstonespecs.testing.core.McDispatchers
import com.breadmoirai.redstonespecs.testing.server.awaitTicks
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe

class SmokeSpec : ServerTestSpec({

    test("awaitTicks advances the server tick counter") {
        val before = McDispatchers.currentServer.tickCount
        awaitTicks(3)
        val after = McDispatchers.currentServer.tickCount
        (after - before) shouldBeGreaterThan 2
    }

    test("test body runs on the server thread") {
        McDispatchers.currentServer.isSameThread shouldBe true
    }
})
