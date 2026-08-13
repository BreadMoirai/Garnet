package com.breadmoirai.garnet.test

import com.breadmoirai.garnet.config.SharedSettings
import com.breadmoirai.garnet.core.async.onServer
import com.breadmoirai.garnet.editor.data.EditorRoot
import com.breadmoirai.garnet.editor.data.EditorSession
import com.breadmoirai.garnet.editor.structure.StructureAutoSave
import com.breadmoirai.garnet.editor.structure.StructureCommit
import com.breadmoirai.garnet.editor.world.EditorServerContext
import com.breadmoirai.garnet.editor.world.EditorWorld
import com.breadmoirai.garnet.mixin.ConnectionAccessor
import com.breadmoirai.garnet.mixin.ServerCommonPacketListenerImplAccessor
import com.mojang.authlib.GameProfile
import io.netty.channel.embedded.EmbeddedChannel
import net.minecraft.network.Connection
import net.minecraft.network.protocol.PacketFlow
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.server.network.CommonListenerCookie
import net.minecraft.world.level.GameType
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists

/**
 * Builds a "mock" [ServerPlayer] in the server's overworld, mirroring vanilla
 * `GameTestHelper#makeMockServerPlayerInLevel`. Tied to a unique UUID so concurrent tests
 * don't collide on shared server-scoped state. The connection uses an [EmbeddedChannel]
 * so packet sends are no-ops on the wire but remain inspectable via [drainPayloads].
 *
 * Must be called on the server thread.
 */
fun makeMockServerPlayer(server: MinecraftServer): ServerPlayer {
    require(server.isSameThread) { "makeMockServerPlayer must be called on the server thread" }
    val profile = GameProfile(UUID.randomUUID(), "test-${UUID.randomUUID().toString().take(6)}")
    val cookie = CommonListenerCookie.createInitial(profile, false)
    val player = object : ServerPlayer(server, server.overworld(), cookie.gameProfile(), cookie.clientInformation()) {
        override fun gameMode(): GameType = GameType.CREATIVE
    }
    val connection = Connection(PacketFlow.SERVERBOUND)
    EmbeddedChannel(connection)
    server.playerList.placeNewPlayer(connection, player, cookie)
    return player
}

fun deleteRecursively(path: Path) {
    if (!path.exists()) return
    Files.walk(path).use { stream ->
        stream.sorted(Comparator.reverseOrder()).forEach { p -> runCatching { Files.delete(p) } }
    }
}

inline fun withTempRoot(prefix: String, block: (Path) -> Unit) {
    val tmp = Files.createTempDirectory(prefix)
    try {
        block(tmp)
    } finally {
        deleteRecursively(tmp)
    }
}

/**
 * Model of `EditorStructureNetworkSpec`'s harness: temp project root + a mock server player,
 * wired through `EditorServerContext` so the handlers resolve the temp root.
 *
 * Also redirects [SharedSettings.localHistoryDir] to a per-call temp directory for every test in
 * this file (Task 7 fix round 2, minor): several of these tests place and/or commit structures,
 * which writes real `LocalHistoryStore` revisions, and without this every one of them would litter
 * the real `<gameDir>/.garnet/local-history` instead of a disposable temp dir. No test's assertions
 * depend on the exact path (keys hash from each test's own unique temp root), so this is purely
 * about not leaving blobs behind on the machine actually running the suite.
 *
 * [prefix] names both temp directories, so each calling spec gets its own recognisable pair.
 */
suspend fun withEditorServer(
    prefix: String,
    block: suspend (server: MinecraftServer, player: ServerPlayer, root: Path) -> Unit,
) {
    val prevHistDir = SharedSettings.localHistoryDir
    val histDir = createTempDirectory("$prefix-hist")
    SharedSettings.localHistoryDir = histDir.toAbsolutePath().toString()
    try {
        withTempRoot(prefix) { tmp ->
            onServer {
                EditorServerContext.set(this, EditorServerContext(EditorRoot(tmp)))
                val player = makeMockServerPlayer(this)
                drainPayloads(player)
                try {
                    block(this, player, tmp)
                } finally {
                    // StructureAutoSave/StructureCommit backoff are keyed per-MinecraftServer and
                    // survive across tests (the gametest harness reuses one server), but every test
                    // gets a fresh withTempRoot. A dirty/backoff entry a test leaves behind (e.g. an
                    // assertion failing before that test's own cleanup runs) would otherwise be
                    // resolved against a LATER test's root, where the subpath no longer exists --
                    // observed as handleRename's commit-before-move loop aborting on a stale,
                    // unresolvable subpath. Clear all of it unconditionally so no subpath ever
                    // leaks from one test's root into another's.
                    for (subpath in StructureAutoSave.of(this).dirtySubpaths()) {
                        StructureAutoSave.of(this).clear(subpath)
                        StructureCommit.clearBackoff(this, subpath)
                    }
                    EditorSession.clear(player.uuid)
                    // Drop the EditorWorld this test may have created, for the same
                    // one-server-many-roots reason (fix round 1). Anything that reaches
                    // `EditorDimLifecycle.placeFolder`/`placeAll` — `handleNewSpec`, `handleSetRoot`,
                    // a relocate that re-places — installs a server-scoped `EditorWorld` pinned to
                    // THIS test's temp root, and nothing ever removes it. That matters because
                    // `EditorRootResolver.rootFor` consults the world FIRST and only then the
                    // `EditorServerContext` each test sets: a leaked world silently overrides the
                    // next test's root with a directory `withTempRoot` has already deleted, so every
                    // later `resolveSubpath` returns null. Symptoms are remote from the cause —
                    // handlers bailing out through their "not found" branches without logging, and
                    // `StructureCommit` reporting NotApplicable with no revision written, in specs
                    // that run much later in the suite. The specs that drive the lifecycle directly
                    // (EditorDimSpec, EditorCellSaverSpec, EditorNetworkRegistrySpec, ...) each call
                    // `EditorWorld.clear` by hand for this reason; doing it here covers every spec
                    // built on this harness, including the ones that create a world only indirectly.
                    EditorWorld.clear(this)
                }
            }
        }
    } finally {
        SharedSettings.localHistoryDir = prevHistDir
        histDir.toFile().deleteRecursively()
    }
}

/**
 * Reads all [CustomPacketPayload]s sent to [player] since the last call.
 *
 * Walks the player's [EmbeddedChannel] outbound queue (set up by [makeMockServerPlayer])
 * and unwraps each [ClientboundCustomPayloadPacket]. The chain
 * `player.connection -> ServerCommonPacketListenerImpl.connection -> Connection.channel`
 * crosses two non-public fields; access is granted via accessor mixins
 * ([ServerCommonPacketListenerImplAccessor], [ConnectionAccessor]).
 */
fun drainPayloads(player: ServerPlayer): List<CustomPacketPayload> {
    val listener = player.connection
    val conn = (listener as ServerCommonPacketListenerImplAccessor).`garnet$getConnection`()
    val ch = (conn as ConnectionAccessor).`garnet$getChannel`() as? EmbeddedChannel
        ?: return emptyList()
    val out = mutableListOf<CustomPacketPayload>()
    while (true) {
        val msg = ch.readOutbound<Any>() ?: break
        if (msg is ClientboundCustomPayloadPacket) out.add(msg.payload())
    }
    return out
}
