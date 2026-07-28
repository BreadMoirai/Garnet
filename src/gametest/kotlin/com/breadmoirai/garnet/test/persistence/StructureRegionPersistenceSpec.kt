package com.breadmoirai.garnet.test.persistence

import com.breadmoirai.garnet.persistence.StructurePersistence
import com.breadmoirai.garnet.project.ProjectDimRegistry
import com.breadmoirai.garnet.testing.GarnetTestSpec
import com.breadmoirai.garnet.testing.server.onServer
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import net.minecraft.core.BlockPos
import net.minecraft.core.Vec3i
import net.minecraft.world.level.block.Blocks
import java.nio.file.Files

class StructureRegionPersistenceSpec : GarnetTestSpec({

    test("auto-fit save captures the tight non-air box; place re-centers it") {
        onServer {
            val level = overworld()
            val file = Files.createTempFile("struct-roundtrip", ".nbt")
            // Use a small 32-wide region far out in the structure lane to keep the scan cheap.
            val region = BlockPos(200_000, 64, ProjectDimRegistry.STRUCTURE_LANE_Z)
            val sizeXZ = 32
            val minY = level.minY
            val maxY = level.maxY

            // Clear then build a known 2x1x3 gold box at a known offset inside the region.
            StructurePersistence.clearBounds(level, BlockPos(region.x, minY, region.z), Vec3i(sizeXZ, maxY - minY + 1, sizeXZ))
            val buildOrigin = region.offset(10, 0, 12)  // y == 64
            level.setBlock(buildOrigin.offset(0, 0, 0), Blocks.GOLD_BLOCK.defaultBlockState(), 2)
            level.setBlock(buildOrigin.offset(1, 0, 0), Blocks.GOLD_BLOCK.defaultBlockState(), 2)
            level.setBlock(buildOrigin.offset(0, 0, 2), Blocks.GOLD_BLOCK.defaultBlockState(), 2)

            val captured = StructurePersistence.saveAutoFitToFile(file, level, region, sizeXZ, minY, maxY).shouldNotBeNull()
            captured.size shouldBe Vec3i(2, 1, 3)

            // Clear the region, then place the file back; it should be centered in X/Z, floored at 64.
            StructurePersistence.clearBounds(level, BlockPos(region.x, minY, region.z), Vec3i(sizeXZ, maxY - minY + 1, sizeXZ))
            val placed = StructurePersistence.placeStructureCentered(file, level, region, sizeXZ, minY, maxY, 64).shouldNotBeNull()
            placed.size shouldBe Vec3i(2, 1, 3)
            placed.origin.x shouldBe (region.x + (sizeXZ - 2) / 2)
            placed.origin.z shouldBe (region.z + (sizeXZ - 3) / 2)
            placed.origin.y shouldBe 64
            // The centered gold block is actually in the world.
            level.getBlockState(placed.origin).`is`(Blocks.GOLD_BLOCK) shouldBe true

            Files.deleteIfExists(file)
        }
    }

    test("auto-fit save of an empty region writes a file and returns null") {
        onServer {
            val level = overworld()
            val file = Files.createTempFile("struct-empty", ".nbt")
            val region = BlockPos(210_000, 64, ProjectDimRegistry.STRUCTURE_LANE_Z)
            val sizeXZ = 16
            StructurePersistence.clearBounds(level, BlockPos(region.x, level.minY, region.z), Vec3i(sizeXZ, level.maxY - level.minY + 1, sizeXZ))
            val result = StructurePersistence.saveAutoFitToFile(file, level, region, sizeXZ, level.minY, level.maxY)
            (result == null) shouldBe true
            Files.exists(file) shouldBe true
            Files.deleteIfExists(file)
        }
    }
})
