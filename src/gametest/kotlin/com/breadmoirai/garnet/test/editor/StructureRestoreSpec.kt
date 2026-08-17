package com.breadmoirai.garnet.test.editor

import com.breadmoirai.garnet.editor.history.network.HistoryWatchers
import com.breadmoirai.garnet.editor.history.ops.RestoreOutcome
import com.breadmoirai.garnet.editor.history.ops.StructureRestoreOps
import com.breadmoirai.garnet.editor.network.EditorErrorS2C
import com.breadmoirai.garnet.editor.structure.network.EditorStructureHandlers
import com.breadmoirai.garnet.editor.network.PlaceStructureC2S
import com.breadmoirai.garnet.editor.network.RestoreRevisionC2S
import com.breadmoirai.garnet.editor.network.RevisionEntry
import com.breadmoirai.garnet.editor.network.StructureHistoryS2C
import com.breadmoirai.garnet.editor.network.WatchStructureHistoryC2S
import com.breadmoirai.garnet.editor.explorer.ops.EditorNewStructure
import com.breadmoirai.garnet.editor.structure.data.CommitOutcome
import com.breadmoirai.garnet.editor.structure.ops.StructureAutoSave
import com.breadmoirai.garnet.editor.structure.ops.StructureCommit
import com.breadmoirai.garnet.editor.undo.data.EditorUndoCommand
import com.breadmoirai.garnet.editor.undo.data.EditorUndoStack
import com.breadmoirai.garnet.editor.world.EditorDimRegistry
import com.breadmoirai.garnet.harness.GarnetTestSpec
import com.breadmoirai.garnet.editor.history.data.LocalHistoryStore
import com.breadmoirai.garnet.editor.history.data.Revision
import com.breadmoirai.garnet.editor.structure.data.PlacedBox
import com.breadmoirai.garnet.editor.structure.ops.StructurePersistence
import com.breadmoirai.garnet.editor.structure.data.structuresDiffer
import com.breadmoirai.garnet.test.drainPayloads
import com.breadmoirai.garnet.test.withEditorServer
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import net.fabricmc.fabric.impl.networking.AbstractChanneledNetworkAddon
import net.fabricmc.fabric.impl.networking.server.ServerNetworkingImpl
import net.minecraft.core.BlockPos
import net.minecraft.core.Vec3i
import net.minecraft.nbt.NbtAccounter
import net.minecraft.nbt.NbtIo
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
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
     * Make [type] *sendable* to a mock player, i.e. make `ServerPlayNetworking.canSend` true for it.
     *
     * `HistoryWatchers.pushTo` guards its send with `canSend` because the commit fan-out is
     * unsolicited (an unknown play-phase payload can disconnect a vanilla client on a dedicated
     * server). A real client makes the server's `canSend` true by registering its channels during
     * the CONFIGURATION phase; `makeMockServerPlayer` fabricates a play-phase connection directly
     * and never goes through configuration, so its addon's sendable-channel set is EMPTY and every
     * guarded send is silently dropped. Without this, the push tests below assert on packets that
     * the guard — correctly, by production rules — never emitted.
     *
     * Fabric exposes no public "pretend this client registered X" hook, so this reaches the
     * package-private `AbstractChanneledNetworkAddon.register`, which is exactly what the real
     * `minecraft:register` path calls. Test-only, and deliberately here rather than in the shared
     * harness: it is the one spec that exercises a `canSend`-guarded send.
     */
    fun grantChannel(player: ServerPlayer, type: CustomPacketPayload.Type<*>) {
        val addon = ServerNetworkingImpl.getAddon(player.connection)
        val register = AbstractChanneledNetworkAddon::class.java
            .getDeclaredMethod("register", List::class.java)
        register.isAccessible = true
        register.invoke(addon, listOf(type.id()))
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

    test("watching a structure replies with its revisions oldest first") {
        withEditorServer("watch-reply") { server, player, root ->
            grantChannel(player, StructureHistoryS2C.TYPE)
            val (subpath, first) = placeAndEdit(server, player, root)
            // Grow the 3x1x3 platform along X only, so the newest revision's dimensions are three
            // DISTINCT numbers (5x1x3). Asserting against 3x1x3 would let an X/Z transposition in
            // `pushTo`'s RevisionEntry mapping pass unnoticed.
            val box = EditorDimRegistry.of(server).placedBoxOf(subpath).shouldNotBeNull()
            val far = box.origin.offset(4, 0, 0)
            server.overworld().setBlockAndUpdate(far, Blocks.GOLD_BLOCK.defaultBlockState())
            StructureAutoSave.of(server).onEdit(subpath, far, server.overworld().gameTime)
            StructureCommit.commit(server, subpath, LocalHistoryStore.REASON_MANUAL)
            drainPayloads(player)

            EditorStructureHandlers.handleWatchHistory(server, player, WatchStructureHistoryC2S(subpath))

            val sent = drainPayloads(player).filterIsInstance<StructureHistoryS2C>().last()
            sent.subpath shouldBe subpath
            sent.revisions.size shouldBeGreaterThan 1
            // The wire list is exactly the store's list, in the store's order. The brief asserted
            // `revisions.first().timestampMillis == first.timestampMillis`, which is wrong: opening
            // a structure seeds a REASON_PLACED revision BEFORE the commit `placeAndEdit` returns,
            // so `first` is the SECOND entry, not the oldest.
            sent.revisions.map { it.timestampMillis } shouldBe
                LocalHistoryStore.revisions(root.resolve(subpath)).map { it.timestampMillis }
            sent.revisions.any { it.timestampMillis == first.timestampMillis }.shouldBeTrue()
            // Oldest first, as the store returns them.
            sent.revisions.zipWithNext().all { (a, b) -> a.timestampMillis <= b.timestampMillis }.shouldBeTrue()
            // Every FIELD of an entry, against literals where a literal is knowable: comparing the
            // wire list only to the store list would pass with any two size fields swapped, since
            // both sides would then be read through the same mapping.
            val newest = LocalHistoryStore.revisions(root.resolve(subpath)).last()
            newest.blockCount shouldBeGreaterThan 0
            sent.revisions.last() shouldBe RevisionEntry(
                newest.timestampMillis, 5, 1, 3, newest.blockCount, LocalHistoryStore.REASON_MANUAL,
            )
        }
    }

    test("an unsolicited push is dropped for a client that never registered the channel") {
        // The complement of `grantChannel`: without it, `canSend` is false and the guard in
        // `HistoryWatchers.pushTo` must swallow the fan-out. Deleting that guard turns this test
        // red -- verified by doing exactly that (fix round 1, Important). It is what stands between
        // a vanilla client on a dedicated server and a disconnect for an unknown play-phase payload.
        withEditorServer("watch-unregistered") { server, player, root ->
            val (subpath, _) = placeAndEdit(server, player, root)
            EditorStructureHandlers.handleWatchHistory(server, player, WatchStructureHistoryC2S(subpath))
            // The watch is recorded regardless -- it is the SEND that is guarded, not the bookkeeping.
            HistoryWatchers.watchedBy(player.uuid) shouldBe subpath
            drainPayloads(player)

            editPlacedStructure(server, subpath, Blocks.GOLD_BLOCK)
            StructureCommit.commit(server, subpath, LocalHistoryStore.REASON_MANUAL).let {
                StructureCommit.broadcast(server, (it as CommitOutcome.Committed).payload)
            }

            drainPayloads(player).filterIsInstance<StructureHistoryS2C>().shouldBeEmpty()
        }
    }

    test("an empty subpath stops the watch") {
        withEditorServer("watch-clear") { server, player, root ->
            val (subpath, _) = placeAndEdit(server, player, root)
            EditorStructureHandlers.handleWatchHistory(server, player, WatchStructureHistoryC2S(subpath))
            HistoryWatchers.watchedBy(player.uuid) shouldBe subpath

            EditorStructureHandlers.handleWatchHistory(server, player, WatchStructureHistoryC2S(""))

            HistoryWatchers.watchedBy(player.uuid).shouldBeNull()
        }
    }

    test("a commit pushes a refreshed list to a watcher") {
        withEditorServer("watch-push") { server, player, root ->
            grantChannel(player, StructureHistoryS2C.TYPE)
            val (subpath, _) = placeAndEdit(server, player, root)
            EditorStructureHandlers.handleWatchHistory(server, player, WatchStructureHistoryC2S(subpath))
            val before = drainPayloads(player).filterIsInstance<StructureHistoryS2C>().last().revisions.size

            editPlacedStructure(server, subpath, Blocks.GOLD_BLOCK)
            StructureCommit.commit(server, subpath, LocalHistoryStore.REASON_MANUAL).let {
                // The push rides on `broadcast`, which is what every unsolicited commit path calls.
                StructureCommit.broadcast(server, (it as CommitOutcome.Committed).payload)
            }

            val after = drainPayloads(player).filterIsInstance<StructureHistoryS2C>().last()
            after.revisions.size shouldBe before + 1
        }
    }

    test("restoring through the handler pushes an undo entry and a refreshed list") {
        withEditorServer("restore-handler") { server, player, root ->
            grantChannel(player, StructureHistoryS2C.TYPE)
            EditorUndoStack.clear(player.uuid)
            val (subpath, first) = placeAndEdit(server, player, root)
            editPlacedStructure(server, subpath, Blocks.GOLD_BLOCK)
            StructureCommit.commit(server, subpath, LocalHistoryStore.REASON_MANUAL)
            EditorStructureHandlers.handleWatchHistory(server, player, WatchStructureHistoryC2S(subpath))
            drainPayloads(player)

            EditorStructureHandlers.handleRestoreRevision(
                server, player, RestoreRevisionC2S(subpath, first.timestampMillis),
            )

            EditorUndoStack.peekUndo(player.uuid)
                .shouldBeInstanceOf<EditorUndoCommand.RestoreRevision>()
            drainPayloads(player).filterIsInstance<StructureHistoryS2C>().shouldNotBeEmpty()
        }
    }

    test("a refused restore reports the reason and pushes no undo entry") {
        withEditorServer("restore-refused") { server, player, root ->
            EditorUndoStack.clear(player.uuid)
            val (subpath, _) = placeAndEdit(server, player, root)
            drainPayloads(player)

            EditorStructureHandlers.handleRestoreRevision(
                server, player, RestoreRevisionC2S(subpath, 1L),
            )

            drainPayloads(player).filterIsInstance<EditorErrorS2C>().shouldNotBeEmpty()
            EditorUndoStack.peekUndo(player.uuid).shouldBeNull()
        }
    }
})
