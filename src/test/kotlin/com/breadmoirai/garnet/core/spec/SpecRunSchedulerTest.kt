package com.breadmoirai.garnet.core.spec

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import net.minecraft.core.Vec3i

class SpecRunSchedulerTest : FunSpec({

    test("garnetSpec carries args; lambda is not executed at construction") {
        var ran = false
        val spec = garnetSpec(
            id = "t",
            bounds = Vec3i(3, 3, 3),
            lifespan = 5,
        ) { ran = true }

        spec.id shouldBe "t"
        spec.bounds shouldBe Vec3i(3, 3, 3)
        spec.lifespan shouldBe 5
        spec.strict shouldBe false
        ran shouldBe false  // lambda is deferred — must NOT have run yet
    }

    test("input scope schedules at START_OF_TICK; output at END_OF_TICK") {
        // Build a spec whose block registers one input and one output.
        val spec = garnetSpec(id = "scheduler-test", lifespan = 5) {
            input(0, 0, 0) { at(2) { setPowered(true) } }
            output(1, 0, 0) { at(3) { powered() } }
        }

        // Construct a SpecRun without a real ServerLevel using the test harness.
        // The callbacks are registered but never invoked — only the TreeMap keys
        // are inspected, so the level is never touched.
        val run = specRunForTest()
        spec.block(run)

        // Input must be keyed at (tick=2, START_OF_TICK)
        run.inputActions.size shouldBe 1
        val inputKey = run.inputActions.keys.first()
        inputKey.tick shouldBe 2
        inputKey.phase shouldBe Phase.START_OF_TICK

        // Assertion must be keyed at (tick=3, END_OF_TICK)
        run.assertions.size shouldBe 1
        val assertKey = run.assertions.keys.first()
        assertKey.tick shouldBe 3
        assertKey.phase shouldBe Phase.END_OF_TICK
    }

    test("strict flag round-trips through the value class") {
        val strict = garnetSpec(id = "s", strict = true) { }
        strict.strict shouldBe true

        val lenient = garnetSpec(id = "l") { }
        lenient.strict shouldBe false
    }
})
