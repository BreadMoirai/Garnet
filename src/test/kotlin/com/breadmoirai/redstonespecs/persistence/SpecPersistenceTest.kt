package com.breadmoirai.redstonespecs.persistence

import com.breadmoirai.redstonespecs.data.dsl.redstoneSpec
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists

class SpecPersistenceTest : FunSpec({

    test("save then load round-trips a spec via spec_kts") {
        val tmp = createTempDirectory("SpecPersistenceTest")
        val spec = redstoneSpec("rt") {
            bounds(3, 3, 3)
            lifespan = 10
            input(1, 0, 1, label = "in") { atStart { powered() } }
            output(2, 0, 2, label = "out") { at(tick = 5) { lit() } }
        }
        SpecPersistence.save(tmp, spec)
        tmp.resolve("rt.spec.kts").exists() shouldBe true

        val loaded = SpecPersistence.load(tmp, "rt")
        loaded shouldBe spec
    }
})
