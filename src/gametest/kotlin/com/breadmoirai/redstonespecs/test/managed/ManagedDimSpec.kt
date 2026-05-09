package com.breadmoirai.redstonespecs.test.managed

import com.breadmoirai.redstonespecs.managed.ManagedDimLifecycle
import com.breadmoirai.redstonespecs.runner.RecordingDslEmitter
import com.breadmoirai.redstonespecs.managed.ManagedDimRegistry
import com.breadmoirai.redstonespecs.managed.ManagedNewSpec
import com.breadmoirai.redstonespecs.managed.ManagedRoot
import com.breadmoirai.redstonespecs.managed.ManagedSession
import com.breadmoirai.redstonespecs.managed.ManagedWorld
import com.breadmoirai.redstonespecs.testing.RedstoneTestSpec
import com.breadmoirai.redstonespecs.testing.server.onServer
import com.mojang.authlib.GameProfile
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.netty.channel.embedded.EmbeddedChannel
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
 * Server-side gametests for the managed-worlds load/save/new-spec flow. Since T27 the canvas
 * is `server.overworld()` (no custom datapack dimension), so these tests run unmodified under
 * Fabric's `GameTestServer` harness.
 *
 * Each test creates a fresh temp root + a synthetic mock `ServerPlayer` so the lifecycle's
 * teleport/session-tracking code paths execute end-to-end without sharing player state across
 * tests.
 */
class ManagedDimSpec : RedstoneTestSpec({

    test("load places cells in the managed dim and registers a session") {
        val tmp = Files.createTempDirectory("managed-load")
        try {
            val folder = tmp.resolve("set-a").also { it.createDirectories() }
            folder.resolve("a.spec.kts").writeText(RecordingDslEmitter.emitStub("a"))
            folder.resolve("b.spec.kts").writeText(RecordingDslEmitter.emitStub("b"))

            val report = onServer {
                ManagedDimRegistry.of(this).managedLevel()
                    .shouldNotBeNull() // managed dim must be registered (data-pack JSON)
                val player = makeMockServerPlayer(this)
                val reports = ManagedDimLifecycle.placeAll(this, ManagedRoot(tmp))
                reports.single { it.subpath == "set-a" }
            }

            report.loaded.shouldContainAll(listOf("a", "b"))

            onServer {
                val world = ManagedWorld.get(this).shouldNotBeNull()
                val perFolder = world.perFolder["set-a"].shouldNotBeNull()
                perFolder.size shouldBe 2
                val cellA = perFolder.getValue("a").cell
                val cellB = perFolder.getValue("b").cell
                (cellA.origin != cellB.origin) shouldBe true
                ManagedWorld.clear(this)
            }
        } finally {
            deleteRecursively(tmp)
        }
    }

    test("saveNow writes only specs whose cell volume changed") {
        val tmp = Files.createTempDirectory("managed-save")
        try {
            val folder = tmp.resolve("set-b").also { it.createDirectories() }
            folder.resolve("a.spec.kts").writeText(RecordingDslEmitter.emitStub("a"))
            folder.resolve("b.spec.kts").writeText(RecordingDslEmitter.emitStub("b"))

            val results = onServer {
                makeMockServerPlayer(this)
                val root = ManagedRoot(tmp)
                ManagedDimLifecycle.placeAll(this, root)

                // The gametest world persists across runs, so the cell volume may contain
                // leftover blocks from a prior test run. Clear both cells to air and
                // re-place the folder so the snapshot baseline is deterministic.
                val world = ManagedWorld.get(this).shouldNotBeNull()
                val absA = world.absoluteCellOrigin(this, "set-b", "a").shouldNotBeNull()
                val absB = world.absoluteCellOrigin(this, "set-b", "b").shouldNotBeNull()
                val level = ManagedDimRegistry.of(this).managedLevel().shouldNotBeNull()
                val air = Blocks.AIR.defaultBlockState()
                for (pos in net.minecraft.core.BlockPos.betweenClosed(absA, absA.offset(2, 2, 2))) {
                    level.setBlock(pos, air, 2)
                }
                for (pos in net.minecraft.core.BlockPos.betweenClosed(absB, absB.offset(1, 1, 1))) {
                    level.setBlock(pos, air, 2)
                }
                ManagedDimLifecycle.placeFolder(this, root, "set-b")

                // Now mutate spec a's cell volume by setting a block inside its bounds (offset
                // by (1,1,1) so we're not on the placement floor).
                level.setBlock(absA.offset(1, 1, 1), Blocks.GOLD_BLOCK.defaultBlockState(), 2)

                val r = ManagedDimLifecycle.saveFolder(this, "set-b")
                ManagedWorld.clear(this)
                r
            }

            val byId = results.associateBy { it.specId }
            byId.getValue("a").saved shouldBe true
            byId.getValue("b").saved shouldBe false
        } finally {
            deleteRecursively(tmp)
        }
    }

    test("ManagedNewSpec.create writes a stub spec.kts to the leaf folder") {
        val tmp = Files.createTempDirectory("managed-new")
        try {
            val folder = tmp.resolve("empty-set").also { it.createDirectories() }

            onServer {
                makeMockServerPlayer(this)
                ManagedDimLifecycle.placeAll(this, ManagedRoot(tmp))
                ManagedWorld.clear(this)
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
