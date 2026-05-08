package com.breadmoirai.redstonespecs.data.serial

import com.breadmoirai.redstonespecs.data.RedstoneSpec
import com.breadmoirai.redstonespecs.data.dsl.redstoneSpec
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

class KtsSpecLoaderRoundtripTest : FunSpec({
    test("emit then load yields equivalent RedstoneSpec") {
        val original = redstoneSpec("roundtrip-1") {
            bounds(4, 3, 2)
            lifespan = 8
        }
        val source = KtsSpecEmitter.emit(original)
        val loaded = KtsSpecLoader.loadString(source, name = "roundtrip-1.spec.kts")
        loaded.shouldBeInstanceOf<RedstoneSpec>()
        loaded.id shouldBe "roundtrip-1"
        loaded.lifespan shouldBe 8
        loaded.bounds shouldBe original.bounds
    }
})
