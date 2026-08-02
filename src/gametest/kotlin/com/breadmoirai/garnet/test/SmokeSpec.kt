package com.breadmoirai.garnet.test

import com.breadmoirai.garnet.harness.GarnetTestSpec
import com.breadmoirai.garnet.core.async.AsyncDispatchers
import com.breadmoirai.garnet.core.async.awaitTickEnd
import com.breadmoirai.garnet.core.async.awaitTicks
import io.kotest.matchers.shouldBe

class SmokeSpec : GarnetTestSpec({

    test("awaitTicks advances the server tick counter") {
        // Align to an END_SERVER_TICK boundary first: the test body otherwise runs at an
        // unspecified point inside tickChildren, and `tickCount++` happens at the start of
        // tickServer (MinecraftServer:1003), so reading tickCount before vs after that
        // increment racy-ily produces delta=2 or delta=3 from the same awaitTicks(3) call.
        awaitTickEnd()
        val before = AsyncDispatchers.currentServer.tickCount
        awaitTicks(3)
        val after = AsyncDispatchers.currentServer.tickCount
        (after - before) shouldBe 3
    }

    test("test body runs on the server thread") {
        AsyncDispatchers.currentServer.isSameThread shouldBe true
    }
})
