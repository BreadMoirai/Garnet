package com.breadmoirai.garnet.editor.workspace.world

import com.breadmoirai.garnet.core.spec.GarnetSpec
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import net.minecraft.core.BlockPos
import net.minecraft.core.Vec3i

class GridLayoutTest : FunSpec({

    val defaultSize = Vec3i(5, 5, 5)
    fun spec(id: String, bounds: Vec3i = defaultSize) = GarnetSpec(
        id = id,
        bounds = bounds,
        lifespan = 20,
        structure = null,
        strict = false,
        block = {},
    )

    test("single spec lands at (0, yBase, 0)") {
        val result = GridLayout.compute(
            inputs = listOf(LayoutInput("only.spec.kts", spec("only"))),
            cellSize = Vec3i(32, 32, 32),
            cellGap = 4,
            rowMax = 3,
            yBase = 7,
        )
        result.errors shouldBe emptyList()
        result.cells.size shouldBe 1
        val cell = result.cells.getValue("only")
        cell.origin shouldBe BlockPos(0, 7, 0)
        cell.cellSize shouldBe Vec3i(32, 32, 32)
        cell.sourceFile shouldBe "only.spec.kts"
        result.byOrigin.getValue(BlockPos(0, 7, 0)) shouldBe "only"
    }

    test("row wraps at rowMax") {
        val y = 64
        val inputs = listOf(
            LayoutInput("a.spec.kts", spec("a")),
            LayoutInput("b.spec.kts", spec("b")),
            LayoutInput("c.spec.kts", spec("c")),
            LayoutInput("d.spec.kts", spec("d")),
        )
        val result = GridLayout.compute(
            inputs = inputs,
            cellSize = Vec3i(32, 32, 32),
            cellGap = 4,
            rowMax = 3,
            yBase = y,
        )
        result.errors shouldBe emptyList()
        result.cells.getValue("a").origin shouldBe BlockPos(0, y, 0)
        result.cells.getValue("b").origin shouldBe BlockPos(36, y, 0)
        result.cells.getValue("c").origin shouldBe BlockPos(72, y, 0)
        result.cells.getValue("d").origin shouldBe BlockPos(0, y, 36)
    }

    test("sort is filename-case-insensitive") {
        val result = GridLayout.compute(
            inputs = listOf(
                LayoutInput("Beta.spec.kts", spec("beta")),
                LayoutInput("alpha.spec.kts", spec("alpha")),
            ),
            cellSize = Vec3i(32, 32, 32),
            cellGap = 4,
            rowMax = 3,
            yBase = 0,
        )
        result.errors shouldBe emptyList()
        result.cells.getValue("alpha").origin shouldBe BlockPos(0, 0, 0)
        result.cells.getValue("beta").origin shouldBe BlockPos(36, 0, 0)
    }

    test("oversized spec excluded with error") {
        val cellSize = Vec3i(8, 32, 32)
        val result = GridLayout.compute(
            inputs = listOf(
                LayoutInput("big.spec.kts", spec("big", bounds = Vec3i(10, 5, 5))),
                LayoutInput("ok.spec.kts", spec("ok")),
            ),
            cellSize = cellSize,
            cellGap = 4,
            rowMax = 3,
            yBase = 0,
        )
        result.errors shouldHaveSize 1
        val err = result.errors[0]
        err.specId shouldBe "big"
        err.filename shouldBe "big.spec.kts"
        err.reason shouldContain "10"
        result.cells.containsKey("big") shouldBe false
        result.cells.getValue("ok").origin shouldBe BlockPos(0, 0, 0)
    }

    test("filename tie broken by spec id") {
        val result = GridLayout.compute(
            inputs = listOf(
                LayoutInput("same.spec.kts", spec("zzz")),
                LayoutInput("same.spec.kts", spec("aaa")),
            ),
            cellSize = Vec3i(32, 32, 32),
            cellGap = 4,
            rowMax = 3,
            yBase = 0,
        )
        result.errors shouldBe emptyList()
        result.cells.getValue("aaa").origin shouldBe BlockPos(0, 0, 0)
        result.cells.getValue("zzz").origin shouldBe BlockPos(36, 0, 0)
    }
})
