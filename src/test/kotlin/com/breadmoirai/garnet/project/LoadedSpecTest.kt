package com.breadmoirai.garnet.project

import com.breadmoirai.garnet.spec.GarnetSpec
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import net.minecraft.core.BlockPos
import net.minecraft.core.Vec3i
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate
import java.nio.file.Path

class LoadedSpecTest : FunSpec({

    test("copy with refreshed snapshot preserves other fields") {
        val cell = ProjectCell("id", BlockPos(0, 0, 0), Vec3i(5, 5, 5), "id.spec.kts")
        val spec = GarnetSpec(id = "id", bounds = Vec3i(5, 5, 5), lifespan = 20, structure = null, strict = false, block = {})
        val source = Path.of("/tmp/id.spec.kts")
        val snap1 = StructureTemplate()
        val snap2 = StructureTemplate()
        val a = LoadedSpec(cell, spec, source, snap1)
        val b = a.copy(loadedSnapshot = snap2)
        b.cell shouldBe cell
        b.spec shouldBe spec
        b.sourceFile shouldBe source
        (b.loadedSnapshot === snap2) shouldBe true
    }
})
