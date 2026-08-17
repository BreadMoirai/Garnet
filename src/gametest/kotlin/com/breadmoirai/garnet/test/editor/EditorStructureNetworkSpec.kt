package com.breadmoirai.garnet.test.editor

import com.breadmoirai.garnet.core.config.SharedSettings
import com.breadmoirai.garnet.editor.network.NewStructureC2S
import com.breadmoirai.garnet.editor.network.PlaceStructureC2S
import com.breadmoirai.garnet.editor.network.SaveStructureC2S
import com.breadmoirai.garnet.editor.network.StructureResultS2C
import com.breadmoirai.garnet.editor.network.StructureAutoSavedS2C
import com.breadmoirai.garnet.editor.network.EditorErrorS2C
import com.breadmoirai.garnet.editor.structure.network.EditorStructureHandlers
import com.breadmoirai.garnet.editor.network.EditorTreeSnapshotS2C
import com.breadmoirai.garnet.editor.structure.ops.StructureEditWatcher
import com.breadmoirai.garnet.editor.history.data.LocalHistoryStore
import com.breadmoirai.garnet.editor.structure.ops.StructurePersistence
import com.breadmoirai.garnet.editor.workspace.world.EditorDimRegistry
import com.breadmoirai.garnet.editor.explorer.ops.EditorNewStructure
import com.breadmoirai.garnet.editor.explorer.data.EditorRoot
import com.breadmoirai.garnet.editor.workspace.world.EditorServerContext
import com.breadmoirai.garnet.editor.explorer.data.EditorSession
import com.breadmoirai.garnet.test.drainPayloads
import com.breadmoirai.garnet.test.makeMockServerPlayer
import com.breadmoirai.garnet.test.withTempRoot
import com.breadmoirai.garnet.harness.GarnetTestSpec
import com.breadmoirai.garnet.core.async.onServer
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import net.minecraft.core.BlockPos
import net.minecraft.core.Vec3i
import net.minecraft.world.level.block.Blocks
import java.nio.file.Files
import kotlin.io.path.createDirectory
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.readBytes

class EditorStructureNetworkSpec : GarnetTestSpec({

    test("place then save round-trips a standalone structure via handlers") {
        withTempRoot("struct-net") { tmp ->
            // Keep the scanned region tiny so the full-height scan is fast in-test.
            val prevChunks = SharedSettings.structureRegionChunks
            SharedSettings.structureRegionChunks = 1
            EditorNewStructure.create(tmp, "gadget")  // seed an empty gadget.nbt at root
            onServer {
                EditorServerContext.set(this, EditorServerContext(EditorRoot(tmp)))
                val player = makeMockServerPlayer(this)
                drainPayloads(player)

                // Place: empty structure -> size 0,0,0, region assigned, player teleported.
                EditorStructureHandlers.handlePlaceStructure(this, player, PlaceStructureC2S("gadget.nbt"))
                val placed = drainPayloads(player).filterIsInstance<StructureResultS2C>().single()
                placed.subpath shouldBe "gadget.nbt"

                // Build a block in the assigned region, then save: captures a 1x1x1 box.
                val region = EditorDimRegistry.of(this).structureRegionOriginOf("gadget.nbt")!!
                val width = SharedSettings.structureRegionChunks * 16
                val lvl = overworld()
                // The gametest world's floor terrain isn't part of the region until this test
                // builds it; clear the column so the auto-fit save below doesn't capture it.
                StructurePersistence.clearBounds(
                    lvl,
                    BlockPos(region.x, lvl.minY, region.z),
                    Vec3i(width, lvl.maxY - lvl.minY + 1, width),
                )
                val edited = region.offset(5, 0, 5)
                lvl.setBlock(edited, Blocks.GOLD_BLOCK.defaultBlockState(), 2)
                StructureEditWatcher.onBlockChanged(lvl, edited)
                EditorStructureHandlers.handleSaveStructure(this, player, SaveStructureC2S("gadget.nbt"))
                val saved = drainPayloads(player).filterIsInstance<StructureAutoSavedS2C>().single()
                saved.sizeX shouldBe 1; saved.sizeY shouldBe 1; saved.sizeZ shouldBe 1

                EditorSession.clear(player.uuid)
            }
            SharedSettings.structureRegionChunks = prevChunks
        }
    }

    test("place rejects a non-.nbt subpath") {
        withTempRoot("struct-net-bad") { tmp ->
            (tmp.resolve("notes.txt")).toFile().writeText("hi")
            onServer {
                EditorServerContext.set(this, EditorServerContext(EditorRoot(tmp)))
                val player = makeMockServerPlayer(this)
                drainPayloads(player)
                EditorStructureHandlers.handlePlaceStructure(this, player, PlaceStructureC2S("notes.txt"))
                drainPayloads(player).filterIsInstance<EditorErrorS2C>() shouldHaveSize 1
                EditorSession.clear(player.uuid)
            }
        }
    }

    test("save without placing this session is refused and does not touch the file") {
        withTempRoot("struct-net-unplaced") { tmp ->
            EditorNewStructure.create(tmp, "unplaced")  // seed unplaced.nbt at root, never placed
            val before = java.nio.file.Files.readAllBytes(tmp.resolve("unplaced.nbt"))
            onServer {
                EditorServerContext.set(this, EditorServerContext(EditorRoot(tmp)))
                val player = makeMockServerPlayer(this)
                drainPayloads(player)

                EditorStructureHandlers.handleSaveStructure(this, player, SaveStructureC2S("unplaced.nbt"))
                val payloads = drainPayloads(player)
                payloads.filterIsInstance<EditorErrorS2C>() shouldHaveSize 1
                payloads.filterIsInstance<StructureResultS2C>() shouldHaveSize 0

                val after = Files.readAllBytes(tmp.resolve("unplaced.nbt"))
                after shouldBe before

                EditorDimRegistry.of(this).structureRegionOriginOf("unplaced.nbt").shouldBeNull()

                EditorSession.clear(player.uuid)
            }
        }
    }

    test("placing a corrupt .nbt replies with an error instead of throwing") {
        withTempRoot("struct-net-corrupt") { tmp ->
            java.nio.file.Files.write(tmp.resolve("corrupt.nbt"), byteArrayOf(1, 2, 3, 4, 5))
            onServer {
                EditorServerContext.set(this, EditorServerContext(EditorRoot(tmp)))
                val player = makeMockServerPlayer(this)
                drainPayloads(player)

                EditorStructureHandlers.handlePlaceStructure(this, player, PlaceStructureC2S("corrupt.nbt"))
                val payloads = drainPayloads(player)
                payloads.filterIsInstance<EditorErrorS2C>() shouldHaveSize 1
                payloads.filterIsInstance<StructureResultS2C>() shouldHaveSize 0

                EditorSession.clear(player.uuid)
            }
        }
    }

    test("new structure creates the file and re-sends the tree") {
        withTempRoot("struct-net-new") { tmp ->
            onServer {
                EditorServerContext.set(this, EditorServerContext(EditorRoot(tmp)))
                val player = makeMockServerPlayer(this)
                // The session's active folder is deliberately NOT set: handleNewStructure resolves
                // strictly from payload.parentSubpath now, so "" here must still land at the root.
                drainPayloads(player)
                EditorStructureHandlers.handleNewStructure(this, player, NewStructureC2S("", "fresh"))
                tmp.resolve("fresh.nbt").exists() shouldBe true
                drainPayloads(player).filterIsInstance<EditorTreeSnapshotS2C>() shouldHaveSize 1

                // The created structure is seeded with the default platform, so placing it puts a
                // 3x3 smooth-stone build plane at projectGridYBase (64) -- not nothing.
                EditorStructureHandlers.handlePlaceStructure(this, player, PlaceStructureC2S("fresh.nbt"))
                drainPayloads(player)
                val placed = EditorDimRegistry.of(this).placedBoxOf("fresh.nbt")!!
                placed.size shouldBe Vec3i(3, 1, 3)
                placed.origin.y shouldBe SharedSettings.projectGridYBase
                val lvl = overworld()
                for (dx in 0 until 3) {
                    for (dz in 0 until 3) {
                        lvl.getBlockState(placed.origin.offset(dx, 0, dz)).block shouldBe Blocks.SMOOTH_STONE
                    }
                }

                EditorSession.clear(player.uuid)
            }
        }
    }

    test("editing a placed structure and force-saving commits straight to the .nbt") {
        withTempRoot("struct-commit") { tmp ->
            val prevChunks = SharedSettings.structureRegionChunks
            val prevHistDir = SharedSettings.localHistoryDir
            SharedSettings.structureRegionChunks = 1
            val histDir = kotlin.io.path.createTempDirectory("struct-commit-hist")
            SharedSettings.localHistoryDir = histDir.toAbsolutePath().toString()
            EditorNewStructure.create(tmp, "widget")
            val committed = tmp.resolve("widget.nbt")
            try {
                onServer {
                    EditorServerContext.set(this, EditorServerContext(EditorRoot(tmp)))
                    val player = makeMockServerPlayer(this)
                    drainPayloads(player)

                    EditorStructureHandlers.handlePlaceStructure(this, player, PlaceStructureC2S("widget.nbt"))
                    drainPayloads(player)

                    val region = EditorDimRegistry.of(this).structureRegionOriginOf("widget.nbt")!!
                    val lvl = overworld()
                    val width = SharedSettings.structureRegionChunks * 16
                    StructurePersistence.clearBounds(
                        lvl, BlockPos(region.x, lvl.minY, region.z),
                        Vec3i(width, lvl.maxY - lvl.minY + 1, width),
                    )
                    val before = committed.readBytes().toList()

                    val edited = region.offset(5, 0, 5)
                    lvl.setBlock(edited, Blocks.GOLD_BLOCK.defaultBlockState(), 2)
                    StructureEditWatcher.onBlockChanged(lvl, edited)

                    EditorStructureHandlers.handleSaveStructure(this, player, SaveStructureC2S("widget.nbt"))

                    // The committed file itself changed — there is no dirty buffer any more.
                    committed.readBytes().toList() shouldNotBe before
                    val saved = drainPayloads(player).filterIsInstance<StructureAutoSavedS2C>().last()
                    saved.subpath shouldBe "widget.nbt"
                    saved.sizeX shouldBe 1
                    // The pre-edit state is recoverable from history rather than from a sidecar.
                    LocalHistoryStore.revisions(committed).size shouldBe 2
                }
            } finally {
                SharedSettings.structureRegionChunks = prevChunks
                SharedSettings.localHistoryDir = prevHistDir
                histDir.toFile().deleteRecursively()
            }
        }
    }

    test("a genuinely failed force-save reports an error, not 'no changes to save'") {
        // REGRESSION (Task 7 fix round 1 / Finding 4): handleSaveStructure used to treat
        // commit(...) == null as a no-op unconditionally, but commit also returns null on a genuine
        // history/.nbt write failure. A player pressing Save on a locked/read-only .nbt must be told
        // the save failed -- not "no changes to save", which implies the (never-persisted) edits are
        // safe when they exist only in the world.
        withTempRoot("struct-savefail") { tmp ->
            val prevChunks = SharedSettings.structureRegionChunks
            val prevHistDir = SharedSettings.localHistoryDir
            SharedSettings.structureRegionChunks = 1
            val histDir = kotlin.io.path.createTempDirectory("struct-savefail-hist")
            SharedSettings.localHistoryDir = histDir.toAbsolutePath().toString()
            EditorNewStructure.create(tmp, "locked")
            val file = tmp.resolve("locked.nbt")
            try {
                onServer {
                    EditorServerContext.set(this, EditorServerContext(EditorRoot(tmp)))
                    val player = makeMockServerPlayer(this)
                    drainPayloads(player)

                    EditorStructureHandlers.handlePlaceStructure(this, player, PlaceStructureC2S("locked.nbt"))
                    drainPayloads(player)

                    val region = EditorDimRegistry.of(this).structureRegionOriginOf("locked.nbt")!!
                    val lvl = overworld()
                    val width = SharedSettings.structureRegionChunks * 16
                    StructurePersistence.clearBounds(
                        lvl, BlockPos(region.x, lvl.minY, region.z),
                        Vec3i(width, lvl.maxY - lvl.minY + 1, width),
                    )
                    val edited = region.offset(1, 0, 1)
                    lvl.setBlock(edited, Blocks.GOLD_BLOCK.defaultBlockState(), 2)
                    StructureEditWatcher.onBlockChanged(lvl, edited)

                    // Force the commit's history write to fail deterministically and portably:
                    // occupy index.json's path with a directory (same trick as
                    // StructureAutoSaveSpec's "a commit whose history write fails...").
                    val historyDir = LocalHistoryStore.dirFor(file)
                    historyDir.resolve("index.json").deleteIfExists()
                    historyDir.resolve("index.json").createDirectory()

                    val before = file.readBytes().toList()

                    EditorStructureHandlers.handleSaveStructure(this, player, SaveStructureC2S("locked.nbt"))

                    val payloads = drainPayloads(player)
                    payloads.filterIsInstance<StructureAutoSavedS2C>() shouldHaveSize 0
                    payloads.filterIsInstance<StructureResultS2C>() shouldHaveSize 0
                    val error = payloads.filterIsInstance<EditorErrorS2C>().single()
                    error.reason shouldNotBe "no changes to save: locked.nbt"
                    file.readBytes().toList() shouldBe before
                }
            } finally {
                SharedSettings.structureRegionChunks = prevChunks
                SharedSettings.localHistoryDir = prevHistDir
                histDir.toFile().deleteRecursively()
            }
        }
    }
})
