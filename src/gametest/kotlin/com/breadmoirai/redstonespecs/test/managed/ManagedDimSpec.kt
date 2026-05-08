package com.breadmoirai.redstonespecs.test.managed

import com.breadmoirai.redstonespecs.data.RedstoneSpec
import com.breadmoirai.redstonespecs.data.serial.KtsSpecEmitter
import com.breadmoirai.redstonespecs.managed.ManagedDimLifecycle
import com.breadmoirai.redstonespecs.managed.ManagedDimRegistry
import com.breadmoirai.redstonespecs.managed.ManagedNewSpec
import com.breadmoirai.redstonespecs.managed.ManagedRoot
import com.breadmoirai.redstonespecs.managed.ManagedSession
import com.breadmoirai.redstonespecs.testing.RedstoneTestSpec
import com.breadmoirai.redstonespecs.testing.server.onServer
import com.mojang.authlib.GameProfile
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.netty.channel.embedded.EmbeddedChannel
import net.minecraft.core.Vec3i
import net.minecraft.network.Connection
import net.minecraft.network.protocol.PacketFlow
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.server.network.CommonListenerCookie
import net.minecraft.world.level.GameType
import net.minecraft.world.level.block.Blocks
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.writeText

/**
 * Skipped: Fabric's GameTestServer (used by `:26.1:runGameTest`) deliberately omits
 * datapack-defined dimensions from its test world. The static `redstonespecs:managed` dim
 * is therefore absent in the gametest harness, and `ManagedDimLifecycle.load` fails with
 * "managed dim is not registered". Unblocking this requires injecting the managed LevelStem
 * into GameTestServer's level-stem registry — a separate harness hook out of scope for v1.
 *
 * To verify managed-worlds at runtime, use `:26.1:runClient` and exercise the
 * world-list "Managed Specs..." flow + `/redstonespecs managed` command manually.
 */
/**
 * Server-side gametests for the managed-dim load/save/new-spec flow. Targets the single-dim
 * fallback path (regions inside `redstonespecs:managed`), which is what `runGameTest` exercises
 * — the per-folder runtime-datapack path only activates after a server restart.
 *
 * Each test creates a fresh temp root + a synthetic mock `ServerPlayer` so the lifecycle's
 * teleport/session-tracking code paths execute end-to-end without sharing player state across
 * tests.
 */
class ManagedDimSpec : RedstoneTestSpec({

    xtest("load places cells in the managed dim and registers a session") {
        val tmp = Files.createTempDirectory("managed-load")
        try {
            val folder = tmp.resolve("set-a").also { it.createDirectories() }
            val a = RedstoneSpec("a", Vec3i(3, 3, 3), 5, null, emptyList())
            val b = RedstoneSpec("b", Vec3i(2, 2, 2), 5, null, emptyList())
            folder.resolve("a.spec.kts").writeText(KtsSpecEmitter.emit(a))
            folder.resolve("b.spec.kts").writeText(KtsSpecEmitter.emit(b))

            val report = onServer {
                ManagedDimRegistry.of(this).managedLevel()
                    .shouldNotBeNull() // managed dim must be registered (data-pack JSON)
                val player = makeMockServerPlayer(this)
                ManagedDimLifecycle.load(this, ManagedRoot(tmp), "set-a", player)
            }

            report.loaded.shouldContainAll(listOf("a", "b"))

            onServer {
                val sessions = ManagedSession.all().filter { it.subpath == "set-a" }
                sessions.size shouldBe 1
                val session = sessions.single()
                session.loaded.size shouldBe 2
                val cellA = session.loaded.getValue("a").cell
                val cellB = session.loaded.getValue("b").cell
                (cellA.origin != cellB.origin) shouldBe true
                ManagedSession.clear(session.playerId)
            }
        } finally {
            deleteRecursively(tmp)
        }
    }

    xtest("saveNow writes only specs whose cell volume changed") {
        val tmp = Files.createTempDirectory("managed-save")
        try {
            val folder = tmp.resolve("set-b").also { it.createDirectories() }
            val a = RedstoneSpec("a", Vec3i(3, 3, 3), 5, null, emptyList())
            val b = RedstoneSpec("b", Vec3i(2, 2, 2), 5, null, emptyList())
            val fileA = folder.resolve("a.spec.kts").also { it.writeText(KtsSpecEmitter.emit(a)) }
            val fileB = folder.resolve("b.spec.kts").also { it.writeText(KtsSpecEmitter.emit(b)) }
            val mtimeA0 = Files.getLastModifiedTime(fileA)
            val mtimeB0 = Files.getLastModifiedTime(fileB)

            val results = onServer {
                val player = makeMockServerPlayer(this)
                ManagedDimLifecycle.load(this, ManagedRoot(tmp), "set-b", player)

                // Mutate spec a's cell volume by setting a block at the absolute cell origin.
                val session = ManagedSession.get(player.uuid).shouldNotBeNull()
                val absA = session.absoluteCellOrigin("a").shouldNotBeNull()
                val level = ManagedDimRegistry.of(this).managedLevel().shouldNotBeNull()
                level.setBlock(absA, Blocks.STONE.defaultBlockState(), 2)

                val r = ManagedDimLifecycle.saveNow(this, player.uuid)
                ManagedSession.clear(player.uuid)
                r
            }

            val byId = results.associateBy { it.specId }
            byId.getValue("a").saved shouldBe true
            byId.getValue("b").saved shouldBe false

            val mtimeA1 = Files.getLastModifiedTime(fileA)
            val mtimeB1 = Files.getLastModifiedTime(fileB)
            (mtimeA1 > mtimeA0) shouldBe true
            (mtimeB1 == mtimeB0) shouldBe true
        } finally {
            deleteRecursively(tmp)
        }
    }

    xtest("ManagedNewSpec.create writes a stub spec.kts to the leaf folder") {
        val tmp = Files.createTempDirectory("managed-new")
        try {
            val folder = tmp.resolve("empty-set").also { it.createDirectories() }

            onServer {
                val player = makeMockServerPlayer(this)
                ManagedDimLifecycle.load(this, ManagedRoot(tmp), "empty-set", player)
                ManagedSession.clear(player.uuid)
            }

            ManagedNewSpec.create(folder, "fresh")
            folder.resolve("fresh.spec.kts").exists() shouldBe true
        } finally {
            deleteRecursively(tmp)
        }
    }
})

/**
 * Builds a "mock" [ServerPlayer] in the server's overworld, mirroring vanilla
 * `GameTestHelper#makeMockServerPlayerInLevel`. Tied to a unique UUID so concurrent tests
 * don't collide on `ManagedSession`. The connection uses an [EmbeddedChannel] so packet
 * sends are no-ops (teleport packets fired during managed-dim load go nowhere safely).
 *
 * Must be called on the server thread.
 */
private fun makeMockServerPlayer(server: MinecraftServer): ServerPlayer {
    require(server.isSameThread) { "makeMockServerPlayer must be called on the server thread" }
    val profile = GameProfile(UUID.randomUUID(), "test-managed-${UUID.randomUUID().toString().take(6)}")
    val cookie = CommonListenerCookie.createInitial(profile, false)
    val player = object : ServerPlayer(server, server.overworld(), cookie.gameProfile(), cookie.clientInformation()) {
        override fun gameMode(): GameType = GameType.CREATIVE
    }
    val connection = Connection(PacketFlow.SERVERBOUND)
    EmbeddedChannel(connection)
    server.playerList.placeNewPlayer(connection, player, cookie)
    return player
}

private fun deleteRecursively(path: Path) {
    if (!path.exists()) return
    Files.walk(path).use { stream ->
        stream.sorted(Comparator.reverseOrder()).forEach { p -> runCatching { Files.delete(p) } }
    }
}
