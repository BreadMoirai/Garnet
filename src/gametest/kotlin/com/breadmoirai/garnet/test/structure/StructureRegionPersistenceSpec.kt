package com.breadmoirai.garnet.test.structure

import com.breadmoirai.garnet.structure.PlacedBox
import com.breadmoirai.garnet.structure.StructurePersistence
import com.breadmoirai.garnet.editor.world.EditorDimRegistry
import com.breadmoirai.garnet.harness.GarnetTestSpec
import com.breadmoirai.garnet.mc.onServer
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import net.minecraft.core.BlockPos
import net.minecraft.core.Vec3i
import net.minecraft.world.level.block.Blocks
import java.nio.file.Files

class StructureRegionPersistenceSpec : GarnetTestSpec({

    test("auto-fit capture writes the tight non-air box to a file; place re-centers it") {
        onServer {
            val level = overworld()
            val file = Files.createTempFile("struct-roundtrip", ".nbt")
            // A small 32-wide region far out in the structure lane keeps the scan cheap. The
            // region-wide captureAutoFit/saveAutoFitToFile pair this test used to call was deleted
            // (it had no production callers and its ~8M-read scan was a footgun); the capture side
            // is now captureAutoFitIn over an explicit scan box, exactly as StructureCommit does it.
            val region = BlockPos(200_000, 64, EditorDimRegistry.STRUCTURE_LANE_Z)
            val sizeXZ = 32
            val minY = level.minY
            val maxY = level.maxY
            val regionBox = PlacedBox(BlockPos(region.x, minY, region.z), Vec3i(sizeXZ, maxY - minY + 1, sizeXZ))

            // Clear then build a known 2x1x3 gold box at a known offset inside the region.
            StructurePersistence.clearBounds(level, regionBox.origin, regionBox.size)
            val buildOrigin = region.offset(10, 0, 12)  // y == 64
            level.setBlock(buildOrigin.offset(0, 0, 0), Blocks.GOLD_BLOCK.defaultBlockState(), 2)
            level.setBlock(buildOrigin.offset(1, 0, 0), Blocks.GOLD_BLOCK.defaultBlockState(), 2)
            level.setBlock(buildOrigin.offset(0, 0, 2), Blocks.GOLD_BLOCK.defaultBlockState(), 2)

            val captured = StructurePersistence.captureAutoFitIn(level, regionBox)
            captured.box.shouldNotBeNull().size shouldBe Vec3i(2, 1, 3)
            StructurePersistence.writeStructureAtomic(captured.tag, file)

            // Clear the region, then place the file back; it should be centered in X/Z, floored at 64.
            StructurePersistence.clearBounds(level, regionBox.origin, regionBox.size)
            val placed = StructurePersistence.placeStructureCentered(file, level, region, sizeXZ, minY, maxY, 64).shouldNotBeNull()
            placed.size shouldBe Vec3i(2, 1, 3)
            placed.origin.x shouldBe (region.x + (sizeXZ - 2) / 2)
            placed.origin.z shouldBe (region.z + (sizeXZ - 3) / 2)
            placed.origin.y shouldBe 64
            // The centered gold block is actually in the world.
            level.getBlockState(placed.origin).`is`(Blocks.GOLD_BLOCK) shouldBe true

            StructurePersistence.clearBounds(level, regionBox.origin, regionBox.size)
            Files.deleteIfExists(file)
        }
    }

    test("captureAutoFitIn fits tightly inside the scanned box and counts non-air blocks") {
        onServer {
            val level = overworld()
            val origin = BlockPos(300_000, 64, EditorDimRegistry.STRUCTURE_LANE_Z)
            val scan = PlacedBox(origin, Vec3i(8, 4, 8))
            StructurePersistence.clearBounds(level, scan.origin, scan.size)

            level.setBlock(origin.offset(2, 0, 3), Blocks.GOLD_BLOCK.defaultBlockState(), 2)
            level.setBlock(origin.offset(5, 1, 3), Blocks.IRON_BLOCK.defaultBlockState(), 2)

            val captured = StructurePersistence.captureAutoFitIn(level, scan)

            captured.blockCount shouldBe 2
            val box = captured.box.shouldNotBeNull()
            box.origin shouldBe origin.offset(2, 0, 3)
            box.size shouldBe Vec3i(4, 2, 1)

            StructurePersistence.clearBounds(level, scan.origin, scan.size)
        }
    }

    test("captureAutoFitIn on an empty box returns a null box, zero blocks, and a valid tag") {
        onServer {
            val level = overworld()
            val origin = BlockPos(310_000, 64, EditorDimRegistry.STRUCTURE_LANE_Z)
            val scan = PlacedBox(origin, Vec3i(4, 4, 4))
            StructurePersistence.clearBounds(level, scan.origin, scan.size)

            val captured = StructurePersistence.captureAutoFitIn(level, scan)

            captured.box shouldBe null
            captured.blockCount shouldBe 0
            // Still a loadable empty structure, not a malformed tag.
            captured.tag.getListOrEmpty("blocks").size shouldBe 0
        }
    }
})
