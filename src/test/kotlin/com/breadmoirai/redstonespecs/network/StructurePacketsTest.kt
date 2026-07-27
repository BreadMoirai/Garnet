package com.breadmoirai.redstonespecs.network

import com.breadmoirai.redstonespecs.network.project.NewStructureC2S
import com.breadmoirai.redstonespecs.network.project.PlaceStructureC2S
import com.breadmoirai.redstonespecs.network.project.SaveStructureC2S
import com.breadmoirai.redstonespecs.network.project.StructureResultS2C
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.netty.buffer.Unpooled

class StructurePacketsTest : FunSpec({
    test("PlaceStructureC2S codec round-trips") {
        val buf = Unpooled.buffer()
        val orig = PlaceStructureC2S("a/b/c.nbt")
        PlaceStructureC2S.STREAM_CODEC.encode(buf, orig)
        PlaceStructureC2S.STREAM_CODEC.decode(buf) shouldBe orig
    }
    test("SaveStructureC2S codec round-trips") {
        val buf = Unpooled.buffer()
        val orig = SaveStructureC2S("x.nbt")
        SaveStructureC2S.STREAM_CODEC.encode(buf, orig)
        SaveStructureC2S.STREAM_CODEC.decode(buf) shouldBe orig
    }
    test("NewStructureC2S codec round-trips") {
        val buf = Unpooled.buffer()
        val orig = NewStructureC2S("gadget")
        NewStructureC2S.STREAM_CODEC.encode(buf, orig)
        NewStructureC2S.STREAM_CODEC.decode(buf) shouldBe orig
    }
    test("StructureResultS2C codec round-trips") {
        val buf = Unpooled.buffer()
        val orig = StructureResultS2C("a/b.nbt", 2, 1, 3, "placed a/b.nbt")
        StructureResultS2C.STREAM_CODEC.encode(buf, orig)
        StructureResultS2C.STREAM_CODEC.decode(buf) shouldBe orig
    }
})
