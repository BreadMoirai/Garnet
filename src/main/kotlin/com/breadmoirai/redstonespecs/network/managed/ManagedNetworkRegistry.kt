package com.breadmoirai.redstonespecs.network.managed

import com.breadmoirai.redstonespecs.config.SharedSettings
import com.breadmoirai.redstonespecs.managed.*
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import org.slf4j.LoggerFactory
import java.nio.file.Path

private val LOGGER = LoggerFactory.getLogger("Redstone Specs")

object ManagedNetworkRegistry {

    private fun rootFor(server: MinecraftServer): ManagedRoot? {
        val world = ManagedWorld.get(server)
        if (world != null) return world.root
        val ctx = ManagedServerContext.get(server)
        if (ctx != null) return ctx.root
        val cfg = SharedSettings.managedRootPath
        return if (cfg.isNotBlank()) ManagedRoot(Path.of(cfg).toAbsolutePath()) else null
    }

    fun register() {
        // Payload-type registrations
        PayloadTypeRegistry.serverboundPlay().register(ListManagedTreeC2S.TYPE, ListManagedTreeC2S.STREAM_CODEC)
        PayloadTypeRegistry.serverboundPlay().register(LoadManagedFolderC2S.TYPE, LoadManagedFolderC2S.STREAM_CODEC)
        PayloadTypeRegistry.serverboundPlay().register(UnloadManagedFolderC2S.TYPE, UnloadManagedFolderC2S.STREAM_CODEC)
        PayloadTypeRegistry.serverboundPlay().register(SaveNowC2S.TYPE, SaveNowC2S.STREAM_CODEC)
        PayloadTypeRegistry.serverboundPlay().register(NewManagedSpecC2S.TYPE, NewManagedSpecC2S.STREAM_CODEC)
        PayloadTypeRegistry.clientboundPlay().register(ManagedTreeSnapshotS2C.TYPE, ManagedTreeSnapshotS2C.STREAM_CODEC)
        PayloadTypeRegistry.clientboundPlay().register(ManagedFolderLoadedS2C.TYPE, ManagedFolderLoadedS2C.STREAM_CODEC)
        PayloadTypeRegistry.clientboundPlay().register(ManagedSaveReportS2C.TYPE, ManagedSaveReportS2C.STREAM_CODEC)
        PayloadTypeRegistry.clientboundPlay().register(ManagedErrorS2C.TYPE, ManagedErrorS2C.STREAM_CODEC)

        ServerPlayNetworking.registerGlobalReceiver(ListManagedTreeC2S.TYPE) { _, ctx ->
            ctx.server().execute { handleListTree(ctx.server(), ctx.player()) }
        }
        ServerPlayNetworking.registerGlobalReceiver(LoadManagedFolderC2S.TYPE) { payload, ctx ->
            ctx.server().execute { handleLoadFolder(ctx.server(), ctx.player(), payload) }
        }
        ServerPlayNetworking.registerGlobalReceiver(UnloadManagedFolderC2S.TYPE) { _, ctx ->
            ctx.server().execute { handleUnload(ctx.server(), ctx.player()) }
        }
        ServerPlayNetworking.registerGlobalReceiver(SaveNowC2S.TYPE) { _, ctx ->
            ctx.server().execute { handleSaveNow(ctx.server(), ctx.player()) }
        }
        ServerPlayNetworking.registerGlobalReceiver(NewManagedSpecC2S.TYPE) { payload, ctx ->
            ctx.server().execute { handleNewSpec(ctx.server(), ctx.player(), payload) }
        }
    }

    internal fun handleListTree(server: MinecraftServer, player: ServerPlayer) {
        sendTree(server, player)
    }

    internal fun handleLoadFolder(server: MinecraftServer, player: ServerPlayer, payload: LoadManagedFolderC2S) {
        val root = rootFor(server) ?: run {
            ServerPlayNetworking.send(player, ManagedErrorS2C("managed-root not configured")); return
        }
        if (root.resolveSubpath(payload.subpath) == null) {
            ServerPlayNetworking.send(player, ManagedErrorS2C("subpath not found or escapes root: ${payload.subpath}")); return
        }
        val ok = ManagedTeleport.toFolder(server, player, payload.subpath)
        if (!ok) {
            ServerPlayNetworking.send(player, ManagedErrorS2C("folder not placed: ${payload.subpath}")); return
        }
        val world = ManagedWorld.get(server)
        val loadedIds = world?.perFolder?.get(payload.subpath)?.keys?.toList().orEmpty()
        ServerPlayNetworking.send(player, ManagedFolderLoadedS2C(
            subpath = payload.subpath,
            loadedSpecIds = loadedIds,
            parseErrors = emptyList(),
            layoutErrors = emptyList(),
        ))
    }

    internal fun handleUnload(server: MinecraftServer, player: ServerPlayer) {
        ManagedSession.clear(player.uuid)
        ServerPlayNetworking.send(player, ManagedSaveReportS2C(emptyList()))
    }

    internal fun handleSaveNow(server: MinecraftServer, player: ServerPlayer) {
        val results = ManagedDimLifecycle.saveAll(server)
        ServerPlayNetworking.send(player, ManagedSaveReportS2C(results.map(::formatSaveResult)))
    }

    internal fun handleNewSpec(server: MinecraftServer, player: ServerPlayer, payload: NewManagedSpecC2S) {
        val activeSubpath = ManagedSession.get(player.uuid)?.activeSubpath ?: run {
            ServerPlayNetworking.send(player, ManagedErrorS2C("no folder selected")); return
        }
        val root = rootFor(server) ?: run {
            ServerPlayNetworking.send(player, ManagedErrorS2C("managed-root not configured")); return
        }
        val world = ManagedWorld.get(server)
        val folderAbsolute = world?.folderAbsoluteByPath?.get(activeSubpath)
            ?: root.resolveSubpath(activeSubpath)
            ?: run {
                ServerPlayNetworking.send(player, ManagedErrorS2C("active folder not resolvable: $activeSubpath")); return
            }
        try {
            ManagedNewSpec.create(folderAbsolute, payload.name)
        } catch (e: Exception) {
            LOGGER.error("[managed/new-spec] create {}/{}: {}", activeSubpath, payload.name, e.message, e)
            ServerPlayNetworking.send(player, ManagedErrorS2C("new-spec failed: ${e.message}")); return
        }
        val report = try {
            ManagedDimLifecycle.placeFolder(server, root, activeSubpath)
        } catch (e: Exception) {
            LOGGER.error("[managed/new-spec] re-place {}: {}", activeSubpath, e.message, e)
            ServerPlayNetworking.send(player, ManagedErrorS2C("re-place failed: ${e.message}")); return
        }
        ServerPlayNetworking.send(player, ManagedFolderLoadedS2C(
            subpath = report.subpath,
            loadedSpecIds = report.loaded,
            parseErrors = report.parseErrors.map { "${it.filename}: ${it.message}" },
            layoutErrors = report.errors.map { "${it.specId} (${it.filename}): ${it.reason}" },
        ))
    }

    private fun sendTree(server: MinecraftServer, player: ServerPlayer) {
        val root = rootFor(server) ?: run {
            ServerPlayNetworking.send(player, ManagedErrorS2C("managed-root not configured"))
            return
        }
        val tree = ManagedFolderTree.scan(root)
        val current = ManagedSession.get(player.uuid)?.activeSubpath
        ServerPlayNetworking.send(player, ManagedTreeSnapshotS2C(
            leaves = tree.leaves.map { ManagedLeafEntry(it.subpath, it.specFiles.size) },
            intermediates = tree.intermediates.toList(),
            currentSubpath = current,
        ))
    }

    private fun formatSaveResult(r: CellSaveResult): String =
        "${r.specId}|saved=${r.saved}${r.error?.let { "|err=$it" } ?: ""}"
}
