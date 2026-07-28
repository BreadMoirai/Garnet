package com.breadmoirai.garnet.test

import com.breadmoirai.garnet.testing.GarnetTestSpec
import com.breadmoirai.garnet.testing.core.McDispatchers
import com.breadmoirai.garnet.testing.server.awaitTickEnd
import com.breadmoirai.garnet.testing.server.awaitTicks
import io.kotest.matchers.shouldBe

class SmokeSpec : GarnetTestSpec({

    test("awaitTicks advances the server tick counter") {
        // Align to an END_SERVER_TICK boundary first: the test body otherwise runs at an
        // unspecified point inside tickChildren, and `tickCount++` happens at the start of
        // tickServer (MinecraftServer:1003), so reading tickCount before vs after that
        // increment racy-ily produces delta=2 or delta=3 from the same awaitTicks(3) call.
        awaitTickEnd()
        val before = McDispatchers.currentServer.tickCount
        awaitTicks(3)
        val after = McDispatchers.currentServer.tickCount
        (after - before) shouldBe 3
    }

    test("test body runs on the server thread") {
        McDispatchers.currentServer.isSameThread shouldBe true
    }
})
