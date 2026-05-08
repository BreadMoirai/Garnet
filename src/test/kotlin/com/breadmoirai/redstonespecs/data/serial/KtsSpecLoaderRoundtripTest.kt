package com.breadmoirai.redstonespecs.data.serial

import com.breadmoirai.redstonespecs.data.dsl.redstoneSpec
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class KtsSpecLoaderRoundtripTest : FunSpec({
    test("emit then loadRedstoneSpec yields equivalent RedstoneSpec") {
        val original = redstoneSpec("roundtrip-1") {
            bounds(4, 3, 2)
            lifespan = 8
        }
        val source = KtsSpecEmitter.emit(original)
        val loaded = KtsSpecLoader.loadRedstoneSpec(source, name = "roundtrip-1.spec.kts")
        loaded.id shouldBe "roundtrip-1"
        loaded.lifespan shouldBe 8
        loaded.bounds shouldBe original.bounds
    }
})
