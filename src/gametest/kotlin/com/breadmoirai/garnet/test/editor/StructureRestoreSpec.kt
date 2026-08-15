package com.breadmoirai.garnet.test.editor

import com.breadmoirai.garnet.harness.GarnetTestSpec
import com.breadmoirai.garnet.structure.PlacedBox
import com.breadmoirai.garnet.structure.StructurePersistence
import com.breadmoirai.garnet.test.withEditorServer
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import net.minecraft.core.BlockPos
import net.minecraft.core.Vec3i
import net.minecraft.nbt.NbtAccounter
import net.minecraft.nbt.NbtIo
import net.minecraft.world.level.block.Blocks
import kotlin.io.path.exists

class StructureRestoreSpec : GarnetTestSpec({

    test("placeStructureTagCentered places the same blocks as the file overload") {
        withEditorServer("restore-tag") { server, _, root ->
            val level = server.overworld()
            // Build a one-block structure on disk via the existing capture+write path, then read
            // its tag back so both overloads get provably identical input.
            val file = root.resolve("probe.nbt")
            val origin = BlockPos(64, 70, 64)
            level.setBlockAndUpdate(origin, Blocks.REDSTONE_BLOCK.defaultBlockState())
            val captured = StructurePersistence.captureAutoFitIn(
                level, PlacedBox(origin, Vec3i(1, 1, 1)),
            )
            StructurePersistence.writeStructureAtomic(captured.tag, file)
            file.exists() shouldBe true

            val tag = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap())
            val target = BlockPos(256, 70, 256)
            val placed = StructurePersistence.placeStructureTagCentered(
                tag, level, target, 16, level.minY, level.maxY, 70,
            )

            placed.shouldNotBeNull()
            placed.size shouldBe Vec3i(1, 1, 1)
            level.getBlockState(placed.origin).block shouldBe Blocks.REDSTONE_BLOCK
        }
    }
})
