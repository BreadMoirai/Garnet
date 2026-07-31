package com.breadmoirai.garnet.test.persistence

import com.breadmoirai.garnet.persistence.StructurePersistence
import com.breadmoirai.garnet.project.ProjectDimRegistry
import com.breadmoirai.garnet.harness.GarnetTestSpec
import com.breadmoirai.garnet.testing.server.onServer
import io.kotest.matchers.shouldBe
import net.minecraft.core.BlockPos
import net.minecraft.core.Vec3i
import net.minecraft.world.level.block.Blocks
import java.nio.file.Files
import kotlin.io.path.createTempDirectory

/**
 * UC-PER-06 / UC-PER-07: the exact-origin `StructurePersistence.save`/`load`/`hasChanges` path used
 * by the spec-cell sidecar (recording finalize + runner restore). This is the fixed-`bounds`,
 * fixed-`origin` path — distinct from the standalone auto-fit path exercised by
 * [StructureRegionPersistenceSpec]. Regions live far out in the structure lane with tiny bounds so
 * the `fillFromWorld` scan stays cheap.
 */
class StructureSidecarPersistenceSpec : GarnetTestSpec({

    test("UC-PER-06: save captures the region and load restores it byte-for-byte at the origin") {
        onServer {
            val level = overworld()
            val dir = createTempDirectory("sidecar-roundtrip")
            val bounds = Vec3i(3, 1, 3)
            val origin = BlockPos(220_000, 64, ProjectDimRegistry.STRUCTURE_LANE_Z)

            StructurePersistence.clearBounds(level, origin, bounds)
            level.setBlock(origin.offset(0, 0, 0), Blocks.GOLD_BLOCK.defaultBlockState(), 2)
            level.setBlock(origin.offset(2, 0, 2), Blocks.IRON_BLOCK.defaultBlockState(), 2)

            // UC-PER-06.a: save writes <id>.nbt.
            StructurePersistence.save(dir, "gizmo", level, origin, bounds)
            Files.exists(dir.resolve("gizmo.nbt")) shouldBe true

            // Wipe the region, then load: UC-PER-06.b places the blocks back at the same origin.
            StructurePersistence.clearBounds(level, origin, bounds)
            level.getBlockState(origin).`is`(Blocks.GOLD_BLOCK) shouldBe false
            StructurePersistence.load(dir, "gizmo", level, origin, bounds)
            level.getBlockState(origin.offset(0, 0, 0)).`is`(Blocks.GOLD_BLOCK) shouldBe true
            level.getBlockState(origin.offset(2, 0, 2)).`is`(Blocks.IRON_BLOCK) shouldBe true

            dir.toFile().deleteRecursively()
        }
    }

    test("UC-PER-06.c: hasChanges is false right after save and true after a block edit") {
        onServer {
            val level = overworld()
            val dir = createTempDirectory("sidecar-haschanges")
            val bounds = Vec3i(2, 1, 2)
            val origin = BlockPos(230_000, 64, ProjectDimRegistry.STRUCTURE_LANE_Z)

            StructurePersistence.clearBounds(level, origin, bounds)
            level.setBlock(origin, Blocks.GOLD_BLOCK.defaultBlockState(), 2)
            StructurePersistence.save(dir, "diff", level, origin, bounds)

            // Live region == saved bytes -> not dirty.
            StructurePersistence.hasChanges(dir, "diff", level, origin, bounds) shouldBe false

            // Mutate one block inside bounds -> byte diff -> dirty.
            level.setBlock(origin.offset(1, 0, 1), Blocks.IRON_BLOCK.defaultBlockState(), 2)
            StructurePersistence.hasChanges(dir, "diff", level, origin, bounds) shouldBe true

            dir.toFile().deleteRecursively()
        }
    }

    test("UC-PER-07.a: load of a missing .nbt is a no-op that leaves the region untouched") {
        onServer {
            val level = overworld()
            val dir = createTempDirectory("sidecar-missing")
            val bounds = Vec3i(2, 1, 2)
            val origin = BlockPos(240_000, 64, ProjectDimRegistry.STRUCTURE_LANE_Z)

            StructurePersistence.clearBounds(level, origin, bounds)
            level.setBlock(origin, Blocks.GOLD_BLOCK.defaultBlockState(), 2)
            // No <id>.nbt exists in dir: load must place nothing and must not throw.
            StructurePersistence.load(dir, "absent", level, origin, bounds)
            level.getBlockState(origin).`is`(Blocks.GOLD_BLOCK) shouldBe true

            dir.toFile().deleteRecursively()
        }
    }

    test("UC-PER-07.b: load of a corrupt .nbt is caught and does not throw") {
        onServer {
            val level = overworld()
            val dir = createTempDirectory("sidecar-corrupt")
            val bounds = Vec3i(2, 1, 2)
            val origin = BlockPos(250_000, 64, ProjectDimRegistry.STRUCTURE_LANE_Z)
            Files.write(dir.resolve("corrupt.nbt"), byteArrayOf(1, 2, 3, 4, 5))
            // A bad gzip header surfaces as IOException, which load swallows: no exception escapes.
            StructurePersistence.load(dir, "corrupt", level, origin, bounds)
            dir.toFile().deleteRecursively()
        }
    }

    test("UC-PER-07.c: hasChanges treats an absent .nbt as changed") {
        onServer {
            val level = overworld()
            val dir = createTempDirectory("sidecar-haschanges-absent")
            val bounds = Vec3i(2, 1, 2)
            val origin = BlockPos(260_000, 64, ProjectDimRegistry.STRUCTURE_LANE_Z)
            StructurePersistence.hasChanges(dir, "absent", level, origin, bounds) shouldBe true
            dir.toFile().deleteRecursively()
        }
    }
})
