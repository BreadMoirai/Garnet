package com.breadmoirai.garnet.test.editor

import com.breadmoirai.garnet.editor.history.RestoreOutcome
import com.breadmoirai.garnet.editor.history.StructureRestoreOps
import com.breadmoirai.garnet.editor.network.EditorStructureHandlers
import com.breadmoirai.garnet.editor.network.PlaceStructureC2S
import com.breadmoirai.garnet.editor.ops.EditorNewStructure
import com.breadmoirai.garnet.editor.structure.StructureAutoSave
import com.breadmoirai.garnet.editor.structure.StructureCommit
import com.breadmoirai.garnet.editor.world.EditorDimRegistry
import com.breadmoirai.garnet.harness.GarnetTestSpec
import com.breadmoirai.garnet.history.LocalHistoryStore
import com.breadmoirai.garnet.history.Revision
import com.breadmoirai.garnet.structure.PlacedBox
import com.breadmoirai.garnet.structure.StructurePersistence
import com.breadmoirai.garnet.structure.structuresDiffer
import com.breadmoirai.garnet.test.withEditorServer
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import net.minecraft.core.BlockPos
import net.minecraft.core.Vec3i
import net.minecraft.nbt.NbtAccounter
import net.minecraft.nbt.NbtIo
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import java.nio.file.Path
import kotlin.io.path.createDirectory
import kotlin.io.path.deleteExisting
import kotlin.io.path.exists
import kotlin.io.path.writeText

class StructureRestoreSpec : GarnetTestSpec({

    /** Set one block at the placed structure's origin and mark the subpath dirty. */
    fun editPlacedStructure(server: MinecraftServer, subpath: String, block: Block) {
        val box = EditorDimRegistry.of(server).placedBoxOf(subpath)!!
        server.overworld().setBlockAndUpdate(box.origin, block.defaultBlockState())
        // StructureAutoSave has no `markDirty(subpath, box)`; the watcher marks per-position via
        // `onEdit(subpath, pos, tick)`, which grows the dirty box itself.
        StructureAutoSave.of(server).onEdit(subpath, box.origin, server.overworld().gameTime)
    }

    /**
     * Place a fresh structure (seeded 3x1x3 platform), stamp redstone on its origin and commit,
     * returning its subpath and the revision that commit banked.
     */
    fun placeAndEdit(
        server: MinecraftServer, player: ServerPlayer, root: Path,
    ): Pair<String, Revision> {
        val subpath = "probe.nbt"
        EditorNewStructure.create(root, "probe")
        EditorStructureHandlers.handlePlaceStructure(server, player, PlaceStructureC2S(subpath))
        editPlacedStructure(server, subpath, Blocks.REDSTONE_BLOCK)
        StructureCommit.commit(server, subpath, LocalHistoryStore.REASON_MANUAL)
        return subpath to LocalHistoryStore.revisions(root.resolve(subpath)).last()
    }

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

    test("restoring an older revision puts it in the world and on disk") {
        withEditorServer("restore-basic") { server, player, root ->
            val (subpath, first) = placeAndEdit(server, player, root)
            // A second edit, so `first` is genuinely older than the newest revision.
            editPlacedStructure(server, subpath, Blocks.GOLD_BLOCK)
            StructureCommit.commit(server, subpath, LocalHistoryStore.REASON_MANUAL)

            val outcome = StructureRestoreOps.restore(server, subpath, first.timestampMillis)

            outcome.shouldBeInstanceOf<RestoreOutcome.Restored>()
            // World matches the restored revision.
            val box = EditorDimRegistry.of(server).placedBoxOf(subpath).shouldNotBeNull()
            server.overworld().getBlockState(box.origin).block shouldBe Blocks.REDSTONE_BLOCK
            // Disk matches it too.
            val file = root.resolve(subpath)
            val onDisk = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap())
            val restoredTag = LocalHistoryStore.readTag(file, first).shouldNotBeNull()
            structuresDiffer(onDisk, restoredTag).shouldBeFalse()
        }
    }

    test("a restore banks a restore revision carrying a real block count") {
        withEditorServer("restore-banks") { server, player, root ->
            val (subpath, first) = placeAndEdit(server, player, root)
            editPlacedStructure(server, subpath, Blocks.GOLD_BLOCK)
            StructureCommit.commit(server, subpath, LocalHistoryStore.REASON_MANUAL)

            StructureRestoreOps.restore(server, subpath, first.timestampMillis)

            val newest = LocalHistoryStore.revisions(root.resolve(subpath)).last()
            newest.reason shouldBe LocalHistoryStore.REASON_RESTORE
            // Unlike `placed`/`external`/`pre-delete`, a restore is committed by scanning the world,
            // so it carries a real count.
            newest.blockCount shouldBeGreaterThan 0
        }
    }

    test("a restore banks no spurious external revision") {
        withEditorServer("restore-no-external") { server, player, root ->
            val (subpath, first) = placeAndEdit(server, player, root)
            editPlacedStructure(server, subpath, Blocks.GOLD_BLOCK)
            StructureCommit.commit(server, subpath, LocalHistoryStore.REASON_MANUAL)

            StructureRestoreOps.restore(server, subpath, first.timestampMillis)

            LocalHistoryStore.revisions(root.resolve(subpath))
                .none { it.reason == LocalHistoryStore.REASON_EXTERNAL }
                .shouldBeTrue()
        }
    }

    test("restoring a smaller revision leaves no blocks from the larger footprint") {
        withEditorServer("restore-shrink") { server, player, root ->
            val (subpath, small) = placeAndEdit(server, player, root)  // 3x1x3 platform
            // Grow it: add a block 3 away on X, just past the platform's 3-wide footprint.
            val box = EditorDimRegistry.of(server).placedBoxOf(subpath).shouldNotBeNull()
            val far = box.origin.offset(3, 0, 0)
            server.overworld().setBlockAndUpdate(far, Blocks.GOLD_BLOCK.defaultBlockState())
            StructureAutoSave.of(server).onEdit(subpath, far, server.overworld().gameTime)
            StructureCommit.commit(server, subpath, LocalHistoryStore.REASON_MANUAL)
            server.overworld().getBlockState(far).block shouldBe Blocks.GOLD_BLOCK

            StructureRestoreOps.restore(server, subpath, small.timestampMillis)

            // The old footprint must be cleared, or the next commit would capture the stray block
            // straight back into the "restored" structure.
            server.overworld().getBlockState(far).block shouldBe Blocks.AIR
        }
    }

    test("restoring refuses when the structure is not placed") {
        withEditorServer("restore-unplaced") { server, player, root ->
            val (subpath, first) = placeAndEdit(server, player, root)
            EditorDimRegistry.of(server).unplaceStructure(subpath)

            val outcome = StructureRestoreOps.restore(server, subpath, first.timestampMillis)

            outcome.shouldBeInstanceOf<RestoreOutcome.Refused>()
            outcome.reason shouldContain "place the structure"
        }
    }

    test("restoring refuses an unknown timestamp") {
        withEditorServer("restore-unknown") { server, player, root ->
            val (subpath, _) = placeAndEdit(server, player, root)

            val outcome = StructureRestoreOps.restore(server, subpath, 1L)

            outcome.shouldBeInstanceOf<RestoreOutcome.Refused>()
            outcome.reason shouldContain "no such revision"
        }
    }

    test("restoring refuses the newest revision") {
        withEditorServer("restore-newest") { server, player, root ->
            val (subpath, _) = placeAndEdit(server, player, root)
            val newest = LocalHistoryStore.revisions(root.resolve(subpath)).last()

            val outcome = StructureRestoreOps.restore(server, subpath, newest.timestampMillis)

            outcome.shouldBeInstanceOf<RestoreOutcome.Refused>()
            outcome.reason shouldContain "already the current"
        }
    }

    test("restoring refuses a raw (non-structure) revision") {
        withEditorServer("restore-raw") { server, player, root ->
            val (subpath, _) = placeAndEdit(server, player, root)
            val file = root.resolve(subpath)
            // A raw revision is what the delete path banks for a non-structure file. Size cannot
            // distinguish it from a typed revision — only the garnetRaw marker can.
            val raw = LocalHistoryStore.writeRawRevision(
                file, "not nbt".toByteArray(), LocalHistoryStore.REASON_PRE_DELETE,
            ).shouldNotBeNull()
            // Bank one more typed revision so the raw one is not the newest (which refuses anyway).
            editPlacedStructure(server, subpath, Blocks.GOLD_BLOCK)
            StructureCommit.commit(server, subpath, LocalHistoryStore.REASON_MANUAL)

            val outcome = StructureRestoreOps.restore(server, subpath, raw.timestampMillis)

            outcome.shouldBeInstanceOf<RestoreOutcome.Refused>()
            outcome.reason shouldContain "not a structure snapshot"
        }
    }

    test("a failed quiesce aborts the restore without touching the world") {
        withEditorServer("restore-quiesce-fail") { server, player, root ->
            val (subpath, first) = placeAndEdit(server, player, root)
            editPlacedStructure(server, subpath, Blocks.GOLD_BLOCK)
            StructureCommit.commit(server, subpath, LocalHistoryStore.REASON_MANUAL)
            // Dirty it again, then make the .nbt unwritable so commitDirtyUnder cannot land it.
            //
            // The brief proposed DELETING the file here, but `EditorRoot.resolveSubpath` returns
            // null for a path that does not exist, so a deleted file refuses at the resolve step
            // BEFORE the quiesce ever runs — covered separately below. Replacing the file with a
            // non-empty directory keeps it resolvable (it exists) while making
            // `writeStructureAtomic`'s move fail with an IOException, so `StructureCommit.commit`
            // returns `Failed`, `commitDirtyUnder` reports it, and this is a genuine exercise of
            // the abort path.
            editPlacedStructure(server, subpath, Blocks.DIAMOND_BLOCK)
            val file = root.resolve(subpath)
            file.deleteExisting()
            file.createDirectory()
            file.resolve("blocker.txt").writeText("keeps the directory non-empty")

            val outcome = StructureRestoreOps.restore(server, subpath, first.timestampMillis)

            outcome.shouldBeInstanceOf<RestoreOutcome.Refused>()
            outcome.reason shouldContain "pending edits"
            // The world still holds the un-quiesced edit — nothing was cleared or re-placed.
            val box = EditorDimRegistry.of(server).placedBoxOf(subpath).shouldNotBeNull()
            server.overworld().getBlockState(box.origin).block shouldBe Blocks.DIAMOND_BLOCK
        }
    }

    test("restoring refuses when the structure file is gone, leaving the world alone") {
        withEditorServer("restore-file-gone") { server, player, root ->
            val (subpath, first) = placeAndEdit(server, player, root)
            editPlacedStructure(server, subpath, Blocks.DIAMOND_BLOCK)
            root.resolve(subpath).deleteExisting()

            val outcome = StructureRestoreOps.restore(server, subpath, first.timestampMillis)

            // `resolveSubpath` requires the file to exist, so this refuses before the quiesce.
            outcome.shouldBeInstanceOf<RestoreOutcome.Refused>()
            outcome.reason shouldContain "subpath not found"
            val box = EditorDimRegistry.of(server).placedBoxOf(subpath).shouldNotBeNull()
            server.overworld().getBlockState(box.origin).block shouldBe Blocks.DIAMOND_BLOCK
        }
    }
})
