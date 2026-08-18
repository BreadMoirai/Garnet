package com.breadmoirai.garnet.editor.structure.network

import com.breadmoirai.garnet.editor.explorer.network.CreateFolderC2S
import com.breadmoirai.garnet.editor.explorer.network.NewStructureC2S
import com.breadmoirai.garnet.editor.structure.network.PlaceStructureC2S
import com.breadmoirai.garnet.editor.explorer.network.RenamePathC2S
import com.breadmoirai.garnet.editor.structure.network.SaveStructureC2S
import com.breadmoirai.garnet.editor.structure.network.StructureResultS2C
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.netty.buffer.Unpooled

class EditorStructurePacketsTest : FunSpec({
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
        val orig = NewStructureC2S("redstone/clocks", "gadget")
        NewStructureC2S.STREAM_CODEC.encode(buf, orig)
        NewStructureC2S.STREAM_CODEC.decode(buf) shouldBe orig
    }

    test("CreateFolderC2S codec round-trips") {
        val buf = Unpooled.buffer()
        val orig = CreateFolderC2S("redstone", "clocks")
        CreateFolderC2S.STREAM_CODEC.encode(buf, orig)
        CreateFolderC2S.STREAM_CODEC.decode(buf) shouldBe orig
    }

    test("CreateFolderC2S round-trips an empty parent (the project root)") {
        val buf = Unpooled.buffer()
        val orig = CreateFolderC2S("", "toplevel")
        CreateFolderC2S.STREAM_CODEC.encode(buf, orig)
        CreateFolderC2S.STREAM_CODEC.decode(buf) shouldBe orig
    }

    test("RenamePathC2S codec round-trips") {
        val buf = Unpooled.buffer()
        val orig = RenamePathC2S("redstone/clock.nbt", "ring-clock.nbt")
        RenamePathC2S.STREAM_CODEC.encode(buf, orig)
        RenamePathC2S.STREAM_CODEC.decode(buf) shouldBe orig
    }
    test("StructureResultS2C codec round-trips") {
        val buf = Unpooled.buffer()
        val orig = StructureResultS2C("a/b.nbt", 2, 1, 3, message = "placed a/b.nbt")
        StructureResultS2C.STREAM_CODEC.encode(buf, orig)
        StructureResultS2C.STREAM_CODEC.decode(buf) shouldBe orig
    }
})
