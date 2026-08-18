package com.breadmoirai.garnet.editor.explorer.network

import com.breadmoirai.garnet.core.config.SharedSettings
import com.breadmoirai.garnet.editor.clearCellVolume
import com.breadmoirai.garnet.editor.writeStub
import com.breadmoirai.garnet.editor.workspace.world.EditorDimLifecycle
import com.breadmoirai.garnet.editor.workspace.world.EditorDimRegistry
import com.breadmoirai.garnet.editor.explorer.data.EditorRoot
import com.breadmoirai.garnet.editor.workspace.world.EditorRootResolver
import com.breadmoirai.garnet.editor.workspace.world.EditorServerContext
import com.breadmoirai.garnet.editor.explorer.data.EditorSession
import com.breadmoirai.garnet.editor.workspace.world.EditorWorld
import com.breadmoirai.garnet.editor.explorer.data.walk
import com.breadmoirai.garnet.editor.network.EditorNetworkRegistry
import com.breadmoirai.garnet.editor.structure.network.EditorStructureHandlers
import com.breadmoirai.garnet.editor.structure.network.EditorSaveReportS2C
import com.breadmoirai.garnet.editor.structure.network.PlaceStructureC2S
import com.breadmoirai.garnet.editor.history.network.RestoreRevisionC2S
import com.breadmoirai.garnet.editor.history.network.RevisionEntry
import com.breadmoirai.garnet.editor.history.network.StructureHistoryS2C
import com.breadmoirai.garnet.editor.history.network.WatchStructureHistoryC2S
import com.breadmoirai.garnet.editor.explorer.ops.EditorNewStructure
import com.breadmoirai.garnet.editor.structure.ops.StructureAutoSave
import com.breadmoirai.garnet.editor.structure.ops.StructureCommit
import com.breadmoirai.garnet.editor.structure.ops.StructureEditWatcher
import com.breadmoirai.garnet.editor.history.data.LocalHistoryStore
import com.breadmoirai.garnet.editor.structure.data.PlacedBox
import net.minecraft.core.Vec3i
import com.breadmoirai.garnet.test.drainPayloads
import com.breadmoirai.garnet.test.makeMockServerPlayer
import com.breadmoirai.garnet.test.withTempRoot
import com.breadmoirai.garnet.harness.GarnetTestSpec
import com.breadmoirai.garnet.core.async.onServer
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.server.MinecraftServer
import net.minecraft.world.level.block.Blocks
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText

class EditorNetworkRegistrySpec : GarnetTestSpec({

    // Every other test in this spec calls the handler objects' handleX(...) directly, which
    // never exercises whether register() was actually invoked at mod init. This test probes the
    // global Fabric networking registries instead of the handler functions, so it fails if
    // EditorNetworkRegistry.register() is ever dropped from Garnet.onInitialize.
    test("EditorNetworkRegistry.register() was called at init: receiver and payload types are already claimed") {
        // C2S: registerGlobalReceiver uses Map.putIfAbsent under the hood and returns false
        // when a receiver for this type is already registered — proving init already claimed it.
        // Because it's a no-op when already-present, this does not disturb the real handler.
        val alreadyRegistered = ServerPlayNetworking.registerGlobalReceiver(LoadEditorFolderC2S.TYPE) { _, _ -> }
        alreadyRegistered shouldBe false

        // S2C: PayloadTypeRegistry.register() throws IllegalArgumentException if the type id is
        // already present in its packetTypes map — proving init already registered the codec.
        shouldThrow<IllegalArgumentException> {
            PayloadTypeRegistry.clientboundPlay().register(EditorErrorS2C.TYPE, EditorErrorS2C.STREAM_CODEC)
        }
    }

    test("handleLoadFolder rejects path traversal with EditorErrorS2C") {
        withTempRoot("project-net-traversal") { tmp ->
            val folder = tmp.resolve("set").also { it.createDirectories() }
            writeStub(folder, "a")
            onServer {
                val player = makeMockServerPlayer(this)
                EditorServerContext.set(this, EditorServerContext(EditorRoot(tmp)))
                EditorDimLifecycle.placeAll(this, EditorRoot(tmp))
                drainPayloads(player) // discard any pre-existing payloads

                EditorTreeHandlers.handleLoadFolder(this, player, LoadEditorFolderC2S("../escape"))

                val payloads = drainPayloads(player)
                val err = payloads.filterIsInstance<EditorErrorS2C>().single()
                err.reason.shouldContain("escapes root")

                EditorSession.clear(player.uuid)
                EditorWorld.clear(this)
                EditorServerContext.clear(this)
            }
        }
    }

    test("handleLoadFolder happy path sends EditorFolderLoadedS2C and sets session") {
        withTempRoot("project-net-load-ok") { tmp ->
            val folder = tmp.resolve("set").also { it.createDirectories() }
            writeStub(folder, "a")
            writeStub(folder, "b")
            onServer {
                val player = makeMockServerPlayer(this)
                EditorServerContext.set(this, EditorServerContext(EditorRoot(tmp)))
                EditorDimLifecycle.placeAll(this, EditorRoot(tmp))
                drainPayloads(player)

                EditorTreeHandlers.handleLoadFolder(this, player, LoadEditorFolderC2S("set"))

                val loaded = drainPayloads(player).filterIsInstance<EditorFolderLoadedS2C>().single()
                loaded.subpath shouldBe "set"
                loaded.loadedSpecIds.toSet() shouldBe setOf("a", "b")
                EditorSession.get(player.uuid)?.activeSubpath shouldBe "set"

                EditorSession.clear(player.uuid)
                EditorWorld.clear(this)
                EditorServerContext.clear(this)
            }
        }
    }

    test("handleSaveNow returns formatted EditorSaveReportS2C") {
        withTempRoot("project-net-save") { tmp ->
            val folder = tmp.resolve("set").also { it.createDirectories() }
            writeStub(folder, "a")
            onServer {
                val player = makeMockServerPlayer(this)
                EditorServerContext.set(this, EditorServerContext(EditorRoot(tmp)))
                val root = EditorRoot(tmp)
                EditorDimLifecycle.placeAll(this, root)
                val world = EditorWorld.get(this).shouldNotBeNull()
                val abs = world.absoluteCellOrigin(this, "set", "a").shouldNotBeNull()
                val level = EditorDimRegistry.of(this).projectLevel()
                val bounds = world.perFolder["set"]!!["a"]!!.spec.bounds
                clearCellVolume(level, abs, bounds)
                EditorDimLifecycle.placeFolder(this, root, "set")
                level.setBlock(abs.offset(1, 1, 1), Blocks.GOLD_BLOCK.defaultBlockState(), 2)
                drainPayloads(player)

                EditorTreeHandlers.handleSaveNow(this, player)

                val report = drainPayloads(player).filterIsInstance<EditorSaveReportS2C>().single()
                report.perSpec.any { it.startsWith("a|saved=true") } shouldBe true

                EditorWorld.clear(this)
                EditorServerContext.clear(this)
            }
        }
    }

    test("handleNewSpec without active session returns 'no folder selected'") {
        withTempRoot("project-net-new-no-session") { tmp ->
            tmp.resolve("set").createDirectories()
            onServer {
                val player = makeMockServerPlayer(this)
                EditorServerContext.set(this, EditorServerContext(EditorRoot(tmp)))
                EditorSession.clear(player.uuid)
                drainPayloads(player)

                EditorTreeHandlers.handleNewSpec(this, player, NewEditorSpecC2S("fresh"))

                val err = drainPayloads(player).filterIsInstance<EditorErrorS2C>().single()
                err.reason shouldBe "no folder selected"

                EditorServerContext.clear(this)
            }
        }
    }

    test("handleNewSpec with active session creates file and sends EditorFolderLoadedS2C") {
        withTempRoot("project-net-new-ok") { tmp ->
            val folder = tmp.resolve("set").also { it.createDirectories() }
            onServer {
                val player = makeMockServerPlayer(this)
                EditorServerContext.set(this, EditorServerContext(EditorRoot(tmp)))
                EditorDimLifecycle.placeAll(this, EditorRoot(tmp))
                EditorSession.setActive(player.uuid, "set")
                drainPayloads(player)

                EditorTreeHandlers.handleNewSpec(this, player, NewEditorSpecC2S("fresh"))

                folder.resolve("fresh.spec.kts").exists() shouldBe true
                val loaded = drainPayloads(player).filterIsInstance<EditorFolderLoadedS2C>().single()
                loaded.loadedSpecIds shouldContain "fresh"

                EditorSession.clear(player.uuid)
                EditorWorld.clear(this)
                EditorServerContext.clear(this)
            }
        }
    }

    test("handleUnload clears session and sends empty save report") {
        withTempRoot("project-net-unload") { tmp ->
            tmp.resolve("set").createDirectories()
            onServer {
                val player = makeMockServerPlayer(this)
                EditorSession.setActive(player.uuid, "set")
                drainPayloads(player)

                EditorTreeHandlers.handleUnload(this, player)

                EditorSession.get(player.uuid).let { it == null } shouldBe true
                val report = drainPayloads(player).filterIsInstance<EditorSaveReportS2C>().single()
                report.perSpec shouldBe emptyList()
            }
        }
    }

    test("ungraceful disconnect clears the player's managed session") {
        onServer {
            val player = makeMockServerPlayer(this)
            EditorSession.setActive(player.uuid, "set")
            EditorSession.get(player.uuid)?.activeSubpath shouldBe "set"

            // No Unload click: the player just drops. Fire the real server-side disconnect
            // event and assert the mod's DISCONNECT listener released the session slot so it
            // doesn't linger into a reconnect.
            ServerPlayConnectionEvents.DISCONNECT.invoker().onPlayDisconnect(player.connection, this)

            EditorSession.get(player.uuid).shouldBeNull()
        }
    }

    test("handleListTree sends a recursive snapshot matching scanFolder") {
        withTempRoot("project-net-list") { tmp ->
            val subA = tmp.resolve("set-a").also { it.createDirectories() }
            writeStub(subA, "x")
            val subB = tmp.resolve("set-b").also { it.createDirectories() }
            writeStub(subB, "y")
            writeStub(subB, "z")
            onServer {
                val player = makeMockServerPlayer(this)
                EditorServerContext.set(this, EditorServerContext(EditorRoot(tmp)))
                drainPayloads(player)

                EditorTreeHandlers.handleListTree(this, player)

                val snap = drainPayloads(player).filterIsInstance<EditorTreeSnapshotS2C>().single()
                val paths = snap.root.walk().map { it.first }.toList()
                paths shouldContainAll listOf("set-a", "set-b", "set-a/x.spec.kts", "set-b/y.spec.kts", "set-b/z.spec.kts")

                EditorServerContext.clear(this)
            }
        }
    }

    test("handleSetRoot switches root, persists it, and sends a snapshot of the new folder") {
        withTempRoot("project-net-setroot") { tmp ->
            val newRoot = tmp.resolve("workspace").also { it.createDirectories() }
            val folder = newRoot.resolve("set").also { it.createDirectories() }
            writeStub(folder, "a")
            val originalRootPath = SharedSettings.projectRootPath
            onServer {
                val player = makeMockServerPlayer(this)
                drainPayloads(player)

                EditorTreeHandlers.handleSetRoot(this, player, SetEditorRootC2S(newRoot.toString()))

                SharedSettings.projectRootPath shouldBe newRoot.toAbsolutePath().toString()
                val snap = drainPayloads(player).filterIsInstance<EditorTreeSnapshotS2C>().single()
                snap.root.name shouldBe "workspace"
                snap.root.walk().map { it.first }.toList() shouldContain "set/a.spec.kts"

                SharedSettings.projectRootPath = originalRootPath
                EditorWorld.clear(this)
                EditorServerContext.clear(this)
            }
        }
    }

    test("handleSetRoot rejects a non-directory path with EditorErrorS2C") {
        withTempRoot("project-net-setroot-bad") { tmp ->
            val notAFolder = tmp.resolve("notafolder.txt").also { it.writeText("x") }
            val originalRootPath = SharedSettings.projectRootPath
            onServer {
                val player = makeMockServerPlayer(this)
                drainPayloads(player)

                EditorTreeHandlers.handleSetRoot(this, player, SetEditorRootC2S(notAFolder.toString()))

                val err = drainPayloads(player).filterIsInstance<EditorErrorS2C>().single()
                err.reason shouldContain "not a folder"
                SharedSettings.projectRootPath shouldBe originalRootPath
            }
        }
    }

    // --- Final review, Finding B1: root swap must not cross-contaminate structures --------------

    test("handleSetRoot commits the OLD root's dirty structure and never touches the NEW root's same-named file") {
        withTempRoot("project-net-setroot-crosscontam") { tmp ->
            val rootA = tmp.resolve("rootA").also { it.createDirectories() }
            val rootB = tmp.resolve("rootB").also { it.createDirectories() }
            val prevChunks = SharedSettings.structureRegionChunks
            val prevHistDir = SharedSettings.localHistoryDir
            SharedSettings.structureRegionChunks = 1
            val histDir = kotlin.io.path.createTempDirectory("project-net-setroot-crosscontam-hist")
            SharedSettings.localHistoryDir = histDir.toAbsolutePath().toString()

            // A's clock.nbt: a real, placeable structure -- this is the one that gets edited and
            // must receive the commit.
            EditorNewStructure.create(rootA, "clock")
            val fileA = rootA.resolve("clock.nbt")

            // B's clock.nbt: a DIFFERENT file that happens to share the exact same name. It is never
            // opened or placed by this test -- if handleSetRoot's reset is missing, a leftover
            // placedBox/region assignment for "clock.nbt" would let the next commit capture A's
            // leftover world blocks and silently overwrite THIS file with them.
            val fileB = rootB.resolve("clock.nbt")
            fileB.writeBytes(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9))
            val bBefore = fileB.readBytes()

            var server: MinecraftServer? = null
            try {
                onServer {
                    server = this
                    EditorServerContext.set(this, EditorServerContext(EditorRoot(rootA)))
                    val player = makeMockServerPlayer(this)
                    EditorStructureHandlers.handlePlaceStructure(this, player, PlaceStructureC2S("clock.nbt"))
                    drainPayloads(player)

                    val fileABeforeEdit = fileA.readBytes()

                    val registry = EditorDimRegistry.of(this)
                    val region = registry.structureRegionOriginOf("clock.nbt")!!
                    val lvl = overworld()
                    val edited = region.offset(1, 0, 1)
                    lvl.setBlock(edited, Blocks.GOLD_BLOCK.defaultBlockState(), 2)
                    // Drive the watcher directly: the setBlock mixin is flaky under this harness.
                    StructureEditWatcher.onBlockChanged(lvl, edited)
                    StructureAutoSave.of(this).isDirty("clock.nbt") shouldBe true

                    // The swap itself must flush A's dirty structure BEFORE touching any state, and
                    // reset all per-structure state so nothing carries over onto B.
                    EditorTreeHandlers.handleSetRoot(this, player, SetEditorRootC2S(rootB.toString()))
                    drainPayloads(player)

                    // A received the edit: its file changed from the pre-edit baseline.
                    fileA.readBytes() shouldNotBe fileABeforeEdit
                    // B was never touched -- not one byte.
                    fileB.readBytes() shouldBe bBefore

                    // The reset actually happened: no leftover placed/dirty/region state survives
                    // under the old root's key.
                    StructureAutoSave.of(this).isDirty("clock.nbt") shouldBe false
                    EditorDimRegistry.of(this).placedBoxOf("clock.nbt") shouldBe null

                    // A tick pass after the swap must not resurrect the cross-contamination either.
                    StructureCommit.tick(this, now = overworld().gameTime + 1000)
                    fileB.readBytes() shouldBe bBefore

                    SharedSettings.projectRootPath = ""
                    EditorWorld.clear(this)
                    EditorServerContext.clear(this)
                }
            } finally {
                server?.let {
                    StructureAutoSave.of(it).clear("clock.nbt")
                    StructureCommit.clearBackoff(it, "clock.nbt")
                }
                SharedSettings.structureRegionChunks = prevChunks
                SharedSettings.localHistoryDir = prevHistDir
                histDir.toFile().deleteRecursively()
            }
        }
    }

    test("a failed commit during a root swap aborts the swap and reports an error") {
        // FOLLOW-UP (whole-branch review, item 2): handleSetRoot used to ignore commitAll's result,
        // then unconditionally clear dirty state and unplace every structure. A structure whose
        // commit FAILED had its edits -- which exist only as world blocks -- discarded silently.
        // The swap must be refused instead, exactly as handleRename refuses its file move.
        withTempRoot("project-net-setroot-commitfail") { tmp ->
            val rootA = tmp.resolve("rootA").also { it.createDirectories() }
            val rootB = tmp.resolve("rootB").also { it.createDirectories() }
            val prevChunks = SharedSettings.structureRegionChunks
            val prevHistDir = SharedSettings.localHistoryDir
            SharedSettings.structureRegionChunks = 1
            val histDir = kotlin.io.path.createTempDirectory("project-net-setroot-commitfail-hist")
            SharedSettings.localHistoryDir = histDir.toAbsolutePath().toString()

            EditorNewStructure.create(rootA, "clock")
            val fileA = rootA.resolve("clock.nbt")

            var server: MinecraftServer? = null
            var historyDir: java.nio.file.Path? = null
            try {
                onServer {
                    server = this
                    EditorServerContext.set(this, EditorServerContext(EditorRoot(rootA)))
                    val player = makeMockServerPlayer(this)
                    EditorStructureHandlers.handlePlaceStructure(this, player, PlaceStructureC2S("clock.nbt"))
                    drainPayloads(player)

                    val registry = EditorDimRegistry.of(this)
                    val region = registry.structureRegionOriginOf("clock.nbt").shouldNotBeNull()
                    val lvl = overworld()
                    val edited = region.offset(1, 0, 1)
                    lvl.setBlock(edited, Blocks.GOLD_BLOCK.defaultBlockState(), 2)
                    StructureEditWatcher.onBlockChanged(lvl, edited)
                    StructureAutoSave.of(this).isDirty("clock.nbt") shouldBe true

                    // Force the commit's history write to fail deterministically and portably by
                    // occupying index.json's path with a directory -- the same trick
                    // EditorFileOpsNetworkSpec's rename-abort test uses.
                    historyDir = LocalHistoryStore.dirFor(fileA)
                    historyDir!!.resolve("index.json").toFile().delete()
                    historyDir!!.resolve("index.json").createDirectories()

                    EditorTreeHandlers.handleSetRoot(this, player, SetEditorRootC2S(rootB.toString()))

                    // Refused, with an error the player actually sees.
                    drainPayloads(player).filterIsInstance<EditorErrorS2C>() shouldHaveSize 1
                    // The root did NOT swap.
                    EditorRootResolver.rootFor(this)!!.path shouldBe rootA
                    // The edits are still recoverable: the structure is still placed and still
                    // dirty, so a later commit (once the failure clears) can still write them.
                    StructureAutoSave.of(this).isDirty("clock.nbt") shouldBe true
                    EditorDimRegistry.of(this).placedBoxOf("clock.nbt").shouldNotBeNull()
                    // The world blocks -- the only copy of those edits -- were not cleared.
                    lvl.getBlockState(edited).`is`(Blocks.GOLD_BLOCK) shouldBe true
                }
            } finally {
                // This test deliberately leaves "clock.nbt" dirty and its history dir booby-trapped.
                server?.let {
                    StructureAutoSave.of(it).clear("clock.nbt")
                    StructureCommit.clearBackoff(it, "clock.nbt")
                    EditorWorld.clear(it)
                    EditorServerContext.clear(it)
                }
                historyDir?.resolve("index.json")?.toFile()?.delete()
                SharedSettings.projectRootPath = ""
                SharedSettings.structureRegionChunks = prevChunks
                SharedSettings.localHistoryDir = prevHistDir
                histDir.toFile().deleteRecursively()
            }
        }
    }

    test("a root swap still proceeds when a dirty structure is merely unresolvable, not write-failed") {
        // The refuse-on-failure guard above must NOT extend to an unresolvable root/file. Nothing
        // could be written for such a structure however long we wait, and "Open Folder" is the very
        // action that repairs an unresolvable root -- refusing would leave a player holding a stale
        // placed-and-dirty structure with no way out. (Found by this suite: three pre-existing
        // root-swap tests started failing when the guard covered both cases.)
        withTempRoot("project-net-setroot-unresolvable") { tmp ->
            val rootB = tmp.resolve("rootB").also { it.createDirectories() }
            val prevChunks = SharedSettings.structureRegionChunks
            SharedSettings.structureRegionChunks = 1

            var server: MinecraftServer? = null
            try {
                onServer {
                    server = this
                    val player = makeMockServerPlayer(this)
                    drainPayloads(player)

                    // A structure that is placed and dirty, but whose root cannot be resolved at
                    // all -- no EditorServerContext is set. commit() reports NotApplicable and
                    // deliberately KEEPS the dirty flag, which is what used to block the swap.
                    val registry = EditorDimRegistry.of(this)
                    val origin = registry.getOrAssignStructureRegion("ghost.nbt")
                    registry.setPlacedBox("ghost.nbt", PlacedBox(origin, Vec3i(1, 1, 1)))
                    EditorServerContext.clear(this)
                    EditorWorld.clear(this)
                    StructureAutoSave.of(this).onEdit("ghost.nbt", origin, overworld().gameTime)
                    StructureAutoSave.of(this).isDirty("ghost.nbt") shouldBe true

                    EditorTreeHandlers.handleSetRoot(this, player, SetEditorRootC2S(rootB.toString()))

                    // The swap went through: the root actually changed, and no error was sent.
                    SharedSettings.projectRootPath shouldBe rootB.toAbsolutePath().toString()
                    drainPayloads(player).filterIsInstance<EditorErrorS2C>() shouldHaveSize 0

                    SharedSettings.projectRootPath = ""
                    EditorWorld.clear(this)
                    EditorServerContext.clear(this)
                }
            } finally {
                server?.let {
                    StructureAutoSave.of(it).clear("ghost.nbt")
                    StructureCommit.clearBackoff(it, "ghost.nbt")
                }
                SharedSettings.structureRegionChunks = prevChunks
            }
        }
    }

    test("a root swap clears the old root's blocks and drops region assignments that never got a placed box") {
        // FOLLOW-UP (whole-branch review, item 3): regions are never recycled, so blocks left in the
        // project level after a swap are unreachable for the rest of the session. And the old reset
        // loop iterated placedStructureSubpaths(), which misses a subpath that got a region
        // assignment but never a placed box -- that assignment used to survive the swap.
        withTempRoot("project-net-setroot-leak") { tmp ->
            val rootA = tmp.resolve("rootA").also { it.createDirectories() }
            val rootB = tmp.resolve("rootB").also { it.createDirectories() }
            val prevChunks = SharedSettings.structureRegionChunks
            val prevHistDir = SharedSettings.localHistoryDir
            SharedSettings.structureRegionChunks = 1
            val histDir = kotlin.io.path.createTempDirectory("project-net-setroot-leak-hist")
            SharedSettings.localHistoryDir = histDir.toAbsolutePath().toString()

            EditorNewStructure.create(rootA, "clock")

            try {
                onServer {
                    EditorServerContext.set(this, EditorServerContext(EditorRoot(rootA)))
                    val player = makeMockServerPlayer(this)
                    EditorStructureHandlers.handlePlaceStructure(this, player, PlaceStructureC2S("clock.nbt"))
                    drainPayloads(player)

                    val registry = EditorDimRegistry.of(this)
                    val lvl = overworld()
                    val region = registry.structureRegionOriginOf("clock.nbt").shouldNotBeNull()

                    // A block inside the structure's placed footprint, committed so placedBox grows
                    // to enclose it -- this is what must be gone from the world after the swap.
                    val edited = region.offset(1, 0, 1)
                    lvl.setBlock(edited, Blocks.GOLD_BLOCK.defaultBlockState(), 2)
                    StructureEditWatcher.onBlockChanged(lvl, edited)
                    StructureCommit.commit(this, "clock.nbt", LocalHistoryStore.REASON_AUTOSAVE)
                    lvl.getBlockState(edited).`is`(Blocks.GOLD_BLOCK) shouldBe true

                    // A subpath that got a region assignment but never a placed box -- exactly the
                    // state handlePlaceStructure leaves behind when it errors after assigning.
                    registry.getOrAssignStructureRegion("orphan.nbt")
                    registry.structureRegionOriginOf("orphan.nbt").shouldNotBeNull()
                    registry.placedBoxOf("orphan.nbt").shouldBeNull()

                    EditorTreeHandlers.handleSetRoot(this, player, SetEditorRootC2S(rootB.toString()))
                    drainPayloads(player)

                    // The old root's blocks are gone from the project level, not orphaned in a
                    // region nothing will ever address again.
                    lvl.getBlockState(edited).`is`(Blocks.AIR) shouldBe true
                    // Both kinds of registry state are gone -- including the placed-box-less one.
                    registry.placedBoxOf("clock.nbt").shouldBeNull()
                    registry.structureRegionOriginOf("clock.nbt").shouldBeNull()
                    registry.structureRegionOriginOf("orphan.nbt").shouldBeNull()

                    SharedSettings.projectRootPath = ""
                    EditorWorld.clear(this)
                    EditorServerContext.clear(this)
                }
            } finally {
                SharedSettings.structureRegionChunks = prevChunks
                SharedSettings.localHistoryDir = prevHistDir
                histDir.toFile().deleteRecursively()
            }
        }
    }

    // Same probe as the init test above: PayloadTypeRegistry.register() throws
    // IllegalArgumentException when the type id is already present, so a throw proves
    // EditorNetworkRegistry.register() already claimed it at mod init. Calling
    // EditorNetworkRegistry.register() outright instead would re-register EVERY payload and blow up
    // on the first one it reaches, which says nothing about these three.
    test("the local-history payloads are registered at init") {
        shouldThrow<IllegalArgumentException> {
            PayloadTypeRegistry.serverboundPlay()
                .register(WatchStructureHistoryC2S.TYPE, WatchStructureHistoryC2S.STREAM_CODEC)
        }
        shouldThrow<IllegalArgumentException> {
            PayloadTypeRegistry.serverboundPlay()
                .register(RestoreRevisionC2S.TYPE, RestoreRevisionC2S.STREAM_CODEC)
        }
        shouldThrow<IllegalArgumentException> {
            PayloadTypeRegistry.clientboundPlay()
                .register(StructureHistoryS2C.TYPE, StructureHistoryS2C.STREAM_CODEC)
        }
    }

    test("a StructureHistoryS2C round-trips its revision list") {
        val payload = StructureHistoryS2C("clock.nbt", listOf(
            RevisionEntry(1_000L, 1, 2, 3, 4, "autosave"),
            RevisionEntry(2_000L, 5, 6, 7, 8, "restore"),
        ))
        val buf = io.netty.buffer.Unpooled.buffer()
        StructureHistoryS2C.STREAM_CODEC.encode(buf, payload)

        StructureHistoryS2C.STREAM_CODEC.decode(buf) shouldBe payload
    }
})
