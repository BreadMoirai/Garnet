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
            ctx.server().execute { sendTree(ctx.server(), ctx.player()) }
        }

        ServerPlayNetworking.registerGlobalReceiver(LoadManagedFolderC2S.TYPE) { payload, ctx ->
            val player = ctx.player()
            ctx.server().execute {
                val root = rootFor(ctx.server()) ?: run {
                    ServerPlayNetworking.send(player, ManagedErrorS2C("managed-root not configured"))
                    return@execute
                }
                if (root.resolveSubpath(payload.subpath) == null) {
                    ServerPlayNetworking.send(player, ManagedErrorS2C("subpath not found or escapes root: ${payload.subpath}"))
                    return@execute
                }
                if (ManagedSession.get(player.uuid) != null) {
                    ManagedDimLifecycle.unload(ctx.server(), player.uuid, save = true)
                }
                val report = try {
                    ManagedDimLifecycle.load(ctx.server(), root, payload.subpath, player)
                } catch (e: Exception) {
                    LOGGER.error("[managed/load] {}: {}", payload.subpath, e.message, e)
                    ServerPlayNetworking.send(player, ManagedErrorS2C("load failed: ${e.message}"))
                    return@execute
                }
                ServerPlayNetworking.send(player, ManagedFolderLoadedS2C(
                    subpath = report.subpath,
                    loadedSpecIds = report.loaded,
                    parseErrors = report.parseErrors.map { "${it.filename}: ${it.message}" },
                    layoutErrors = report.errors.map { "${it.specId} (${it.filename}): ${it.reason}" },
                ))
            }
        }

        ServerPlayNetworking.registerGlobalReceiver(UnloadManagedFolderC2S.TYPE) { _, ctx ->
            val player = ctx.player()
            ctx.server().execute {
                val results = ManagedDimLifecycle.unload(ctx.server(), player.uuid, save = true)
                ServerPlayNetworking.send(player, ManagedSaveReportS2C(results.map(::formatSaveResult)))
            }
        }

        ServerPlayNetworking.registerGlobalReceiver(SaveNowC2S.TYPE) { _, ctx ->
            val player = ctx.player()
            ctx.server().execute {
                val results = ManagedDimLifecycle.saveNow(ctx.server(), player.uuid)
                ServerPlayNetworking.send(player, ManagedSaveReportS2C(results.map(::formatSaveResult)))
            }
        }

        ServerPlayNetworking.registerGlobalReceiver(NewManagedSpecC2S.TYPE) { payload, ctx ->
            val player = ctx.player()
            ctx.server().execute {
                val session = ManagedSession.get(player.uuid) ?: run {
                    ServerPlayNetworking.send(player, ManagedErrorS2C("no folder loaded"))
                    return@execute
                }
                val root = rootFor(ctx.server()) ?: return@execute
                try {
                    ManagedNewSpec.create(session.folderAbsolute, payload.name)
                } catch (e: Exception) {
                    ServerPlayNetworking.send(player, ManagedErrorS2C("new-spec failed: ${e.message}"))
                    return@execute
                }
                ManagedDimLifecycle.unload(ctx.server(), player.uuid, save = true)
                val report = ManagedDimLifecycle.load(ctx.server(), root, session.subpath, player)
                ServerPlayNetworking.send(player, ManagedFolderLoadedS2C(
                    subpath = report.subpath,
                    loadedSpecIds = report.loaded,
                    parseErrors = report.parseErrors.map { "${it.filename}: ${it.message}" },
                    layoutErrors = report.errors.map { "${it.specId} (${it.filename}): ${it.reason}" },
                ))
            }
        }
    }

    private fun sendTree(server: MinecraftServer, player: ServerPlayer) {
        val root = rootFor(server) ?: run {
            ServerPlayNetworking.send(player, ManagedErrorS2C("managed-root not configured"))
            return
        }
        val tree = ManagedFolderTree.scan(root)
        val current = ManagedSession.get(player.uuid)?.subpath
        ServerPlayNetworking.send(player, ManagedTreeSnapshotS2C(
            leaves = tree.leaves.map { ManagedLeafEntry(it.subpath, it.specFiles.size) },
            intermediates = tree.intermediates.toList(),
            currentSubpath = current,
        ))
    }

    private fun formatSaveResult(r: CellSaveResult): String =
        "${r.specId}|saved=${r.saved}${r.error?.let { "|err=$it" } ?: ""}"
}
