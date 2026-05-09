package com.breadmoirai.redstonespecs.test.managed

import com.breadmoirai.redstonespecs.mixin.ConnectionAccessor
import com.breadmoirai.redstonespecs.mixin.ServerCommonPacketListenerImplAccessor
import com.breadmoirai.redstonespecs.runner.RecordingDslEmitter
import com.mojang.authlib.GameProfile
import io.netty.channel.embedded.EmbeddedChannel
import net.minecraft.core.BlockPos
import net.minecraft.core.Vec3i
import net.minecraft.network.Connection
import net.minecraft.network.protocol.PacketFlow
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.server.network.CommonListenerCookie
import net.minecraft.world.level.GameType
import net.minecraft.world.level.block.Blocks
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.io.path.exists
import kotlin.io.path.writeText

/**
 * Builds a "mock" [ServerPlayer] in the server's overworld, mirroring vanilla
 * `GameTestHelper#makeMockServerPlayerInLevel`. Tied to a unique UUID so concurrent tests
 * don't collide on `ManagedSession`. The connection uses an [EmbeddedChannel] so packet
 * sends are no-ops.
 *
 * Must be called on the server thread.
 */
fun makeMockServerPlayer(server: MinecraftServer): ServerPlayer {
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

fun writeStub(folder: Path, name: String) {
    folder.resolve("$name.spec.kts").writeText(RecordingDslEmitter.emitStub(name))
}

/** Sets every block in `[origin, origin+size)` to AIR. */
fun clearCellVolume(level: ServerLevel, origin: BlockPos, size: Vec3i) {
    val air = Blocks.AIR.defaultBlockState()
    val end = origin.offset(size.x - 1, size.y - 1, size.z - 1)
    for (pos in BlockPos.betweenClosed(origin, end)) {
        level.setBlock(pos, air, 2)
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
    val conn = (listener as ServerCommonPacketListenerImplAccessor).`redstonespecs$getConnection`()
    val ch = (conn as ConnectionAccessor).`redstonespecs$getChannel`() as? EmbeddedChannel
        ?: return emptyList()
    val out = mutableListOf<CustomPacketPayload>()
    while (true) {
        val msg = ch.readOutbound<Any>() ?: break
        if (msg is ClientboundCustomPayloadPacket) out.add(msg.payload())
    }
    return out
}
