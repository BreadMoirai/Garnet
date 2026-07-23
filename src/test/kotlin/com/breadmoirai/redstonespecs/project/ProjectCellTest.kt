package com.breadmoirai.redstonespecs.project

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import net.minecraft.core.BlockPos
import net.minecraft.core.Vec3i

class ProjectCellTest : FunSpec({

    test("data class equality by component") {
        val a = ProjectCell("id", BlockPos(1, 2, 3), Vec3i(5, 5, 5), "id.spec.kts")
        val b = ProjectCell("id", BlockPos(1, 2, 3), Vec3i(5, 5, 5), "id.spec.kts")
        a shouldBe b
    }

    test("copy preserves untouched fields") {
        val a = ProjectCell("id", BlockPos(1, 2, 3), Vec3i(5, 5, 5), "id.spec.kts")
        val b = a.copy(origin = BlockPos(10, 2, 3))
        b.specId shouldBe "id"
        b.cellSize shouldBe Vec3i(5, 5, 5)
        b.sourceFile shouldBe "id.spec.kts"
        b.origin shouldBe BlockPos(10, 2, 3)
    }
})
