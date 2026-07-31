package com.breadmoirai.garnet.test.editor

import com.breadmoirai.garnet.config.SharedSettings
import com.breadmoirai.garnet.editor.world.EditorDimLifecycle
import com.breadmoirai.garnet.editor.world.EditorDimRegistry
import com.breadmoirai.garnet.editor.data.EditorRoot
import com.breadmoirai.garnet.editor.world.EditorServerContext
import com.breadmoirai.garnet.editor.data.EditorSession
import com.breadmoirai.garnet.editor.world.EditorWorld
import com.breadmoirai.garnet.editor.data.walk
import com.breadmoirai.garnet.editor.network.LoadEditorFolderC2S
import com.breadmoirai.garnet.editor.network.EditorErrorS2C
import com.breadmoirai.garnet.editor.network.EditorFolderLoadedS2C
import com.breadmoirai.garnet.editor.network.EditorNetworking
import com.breadmoirai.garnet.editor.network.EditorSaveReportS2C
import com.breadmoirai.garnet.editor.network.EditorTreeSnapshotS2C
import com.breadmoirai.garnet.editor.network.NewEditorSpecC2S
import com.breadmoirai.garnet.editor.network.SetEditorRootC2S
import com.breadmoirai.garnet.test.drainPayloads
import com.breadmoirai.garnet.test.makeMockServerPlayer
import com.breadmoirai.garnet.test.withTempRoot
import com.breadmoirai.garnet.harness.GarnetTestSpec
import com.breadmoirai.garnet.mc.onServer
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.writeText

class EditorNetworkRegistrySpec : GarnetTestSpec({

    // Every other test in this spec calls EditorNetworking.handleX(...) directly, which
    // never exercises whether register() was actually invoked at mod init. This test probes the
    // global Fabric networking registries instead of the handler functions, so it fails if
    // EditorNetworking.register() is ever dropped from Garnet.onInitialize.
    test("EditorNetworking.register() was called at init: receiver and payload types are already claimed") {
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

                EditorNetworking.handleLoadFolder(this, player, LoadEditorFolderC2S("../escape"))

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

                EditorNetworking.handleLoadFolder(this, player, LoadEditorFolderC2S("set"))

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
                level.setBlock(abs.offset(1, 1, 1), net.minecraft.world.level.block.Blocks.GOLD_BLOCK.defaultBlockState(), 2)
                drainPayloads(player)

                EditorNetworking.handleSaveNow(this, player)

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

                EditorNetworking.handleNewSpec(this, player, NewEditorSpecC2S("fresh"))

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

                EditorNetworking.handleNewSpec(this, player, NewEditorSpecC2S("fresh"))

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

                EditorNetworking.handleUnload(this, player)

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

                EditorNetworking.handleListTree(this, player)

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

                EditorNetworking.handleSetRoot(this, player, SetEditorRootC2S(newRoot.toString()))

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

                EditorNetworking.handleSetRoot(this, player, SetEditorRootC2S(notAFolder.toString()))

                val err = drainPayloads(player).filterIsInstance<EditorErrorS2C>().single()
                err.reason shouldContain "not a folder"
                SharedSettings.projectRootPath shouldBe originalRootPath
            }
        }
    }
})
