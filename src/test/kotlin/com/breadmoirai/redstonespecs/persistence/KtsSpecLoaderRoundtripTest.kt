package com.breadmoirai.redstonespecs.persistence

import com.breadmoirai.redstonespecs.persistence.KtsSpecLoader
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import net.minecraft.core.Vec3i

class KtsSpecLoaderRoundtripTest : FunSpec({
    test("new-dsl source roundtrips id, bounds, lifespan via loadRedstoneSpec") {
        // Source produced by RecordingDslEmitter (new-DSL path): a top-level
        // `redstoneSpec(...) { }` expression whose value is the script's return value.
        val source = """
            import com.breadmoirai.redstonespecs.dsl.*
            import net.minecraft.core.Vec3i

            redstoneSpec(id = "roundtrip-1", bounds = Vec3i(4, 3, 2), lifespan = 8) {}
        """.trimIndent()
        val loaded = KtsSpecLoader.loadRedstoneSpec(source, name = "roundtrip-1.spec.kts")
        loaded.id shouldBe "roundtrip-1"
        loaded.lifespan shouldBe 8
        loaded.bounds shouldBe Vec3i(4, 3, 2)
    }
})
