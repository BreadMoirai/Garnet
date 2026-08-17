package com.breadmoirai.garnet.editor.network

import com.breadmoirai.garnet.editor.undo.network.RedoC2S
import com.breadmoirai.garnet.editor.undo.network.UndoC2S
import com.breadmoirai.garnet.editor.undo.network.UndoStateS2C
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.netty.buffer.Unpooled

class EditorUndoPacketsTest : FunSpec({

    test("UndoStateS2C round-trips both labels") {
        val buf = Unpooled.buffer()
        val orig = UndoStateS2C("delete 'redstone/clock.nbt'", "rename to 'logic'")
        UndoStateS2C.STREAM_CODEC.encode(buf, orig)
        UndoStateS2C.STREAM_CODEC.decode(buf) shouldBe orig
    }

    test("UndoStateS2C round-trips nulls (both buttons disabled)") {
        val buf = Unpooled.buffer()
        val orig = UndoStateS2C(null, null)
        UndoStateS2C.STREAM_CODEC.encode(buf, orig)
        UndoStateS2C.STREAM_CODEC.decode(buf) shouldBe orig
    }

    test("UndoStateS2C round-trips a null redo label only") {
        val buf = Unpooled.buffer()
        val orig = UndoStateS2C("create folder 'x'", null)
        UndoStateS2C.STREAM_CODEC.encode(buf, orig)
        UndoStateS2C.STREAM_CODEC.decode(buf) shouldBe orig
    }

    test("UndoC2S encodes its INSTANCE") {
        val buf = Unpooled.buffer()
        UndoC2S.STREAM_CODEC.encode(buf, UndoC2S.INSTANCE)
        UndoC2S.STREAM_CODEC.decode(buf) shouldBe UndoC2S.INSTANCE
    }

    test("RedoC2S encodes its INSTANCE") {
        val buf = Unpooled.buffer()
        RedoC2S.STREAM_CODEC.encode(buf, RedoC2S.INSTANCE)
        RedoC2S.STREAM_CODEC.decode(buf) shouldBe RedoC2S.INSTANCE
    }
})
