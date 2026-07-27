package com.breadmoirai.redstonespecs.network.project

import com.breadmoirai.redstonespecs.config.SharedSettings
import com.breadmoirai.redstonespecs.project.*
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import org.slf4j.LoggerFactory
import java.nio.file.Path
import kotlin.io.path.isDirectory

private val LOGGER = LoggerFactory.getLogger("Redstone Specs")

object ProjectNetworkRegistry {

    private fun rootFor(server: MinecraftServer): ProjectRoot? {
        val world = ProjectWorld.get(server)
        if (world != null) return world.root
        val ctx = ProjectServerContext.get(server)
        if (ctx != null) return ctx.root
        val cfg = SharedSettings.projectRootPath
        return if (cfg.isNotBlank()) ProjectRoot(Path.of(cfg).toAbsolutePath()) else null
    }

    fun register() {
        // Payload-type registrations
        PayloadTypeRegistry.serverboundPlay().register(ListProjectTreeC2S.TYPE, ListProjectTreeC2S.STREAM_CODEC)
        PayloadTypeRegistry.serverboundPlay().register(LoadProjectFolderC2S.TYPE, LoadProjectFolderC2S.STREAM_CODEC)
        PayloadTypeRegistry.serverboundPlay().register(UnloadProjectFolderC2S.TYPE, UnloadProjectFolderC2S.STREAM_CODEC)
        PayloadTypeRegistry.serverboundPlay().register(SaveNowC2S.TYPE, SaveNowC2S.STREAM_CODEC)
        PayloadTypeRegistry.serverboundPlay().register(NewProjectSpecC2S.TYPE, NewProjectSpecC2S.STREAM_CODEC)
        PayloadTypeRegistry.serverboundPlay().register(SetProjectRootC2S.TYPE, SetProjectRootC2S.STREAM_CODEC)
        PayloadTypeRegistry.clientboundPlay().register(ProjectTreeSnapshotS2C.TYPE, ProjectTreeSnapshotS2C.STREAM_CODEC)
        PayloadTypeRegistry.clientboundPlay().register(ProjectFolderLoadedS2C.TYPE, ProjectFolderLoadedS2C.STREAM_CODEC)
        PayloadTypeRegistry.clientboundPlay().register(ProjectSaveReportS2C.TYPE, ProjectSaveReportS2C.STREAM_CODEC)
        PayloadTypeRegistry.clientboundPlay().register(ProjectErrorS2C.TYPE, ProjectErrorS2C.STREAM_CODEC)

        ServerPlayNetworking.registerGlobalReceiver(ListProjectTreeC2S.TYPE) { _, ctx ->
            ctx.server().execute { handleListTree(ctx.server(), ctx.player()) }
        }
        ServerPlayNetworking.registerGlobalReceiver(LoadProjectFolderC2S.TYPE) { payload, ctx ->
            ctx.server().execute { handleLoadFolder(ctx.server(), ctx.player(), payload) }
        }
        ServerPlayNetworking.registerGlobalReceiver(UnloadProjectFolderC2S.TYPE) { _, ctx ->
            ctx.server().execute { handleUnload(ctx.server(), ctx.player()) }
        }
        ServerPlayNetworking.registerGlobalReceiver(SaveNowC2S.TYPE) { _, ctx ->
            ctx.server().execute { handleSaveNow(ctx.server(), ctx.player()) }
        }
        ServerPlayNetworking.registerGlobalReceiver(NewProjectSpecC2S.TYPE) { payload, ctx ->
            ctx.server().execute { handleNewSpec(ctx.server(), ctx.player(), payload) }
        }
        ServerPlayNetworking.registerGlobalReceiver(SetProjectRootC2S.TYPE) { payload, ctx ->
            ctx.server().execute { handleSetRoot(ctx.server(), ctx.player(), payload) }
        }
    }

    fun handleListTree(server: MinecraftServer, player: ServerPlayer) {
        sendTree(server, player)
    }

    fun handleLoadFolder(server: MinecraftServer, player: ServerPlayer, payload: LoadProjectFolderC2S) {
        val root = rootFor(server) ?: run {
            ServerPlayNetworking.send(player, ProjectErrorS2C("project-root not configured")); return
        }
        if (root.resolveSubpath(payload.subpath) == null) {
            ServerPlayNetworking.send(player, ProjectErrorS2C("subpath not found or escapes root: ${payload.subpath}")); return
        }
        val ok = ProjectTeleport.toFolder(server, player, payload.subpath)
        if (!ok) {
            ServerPlayNetworking.send(player, ProjectErrorS2C("folder not placed: ${payload.subpath}")); return
        }
        val world = ProjectWorld.get(server)
        val loadedIds = world?.perFolder?.get(payload.subpath)?.keys?.toList().orEmpty()
        ServerPlayNetworking.send(player, ProjectFolderLoadedS2C(
            subpath = payload.subpath,
            loadedSpecIds = loadedIds,
            parseErrors = emptyList(),
            layoutErrors = emptyList(),
        ))
    }

    fun handleUnload(server: MinecraftServer, player: ServerPlayer) {
        ProjectSession.clear(player.uuid)
        ServerPlayNetworking.send(player, ProjectSaveReportS2C(emptyList()))
    }

    fun handleSaveNow(server: MinecraftServer, player: ServerPlayer) {
        val results = ProjectDimLifecycle.saveAll(server)
        ServerPlayNetworking.send(player, ProjectSaveReportS2C(results.map(::formatSaveResult)))
    }

    fun handleNewSpec(server: MinecraftServer, player: ServerPlayer, payload: NewProjectSpecC2S) {
        val activeSubpath = ProjectSession.get(player.uuid)?.activeSubpath ?: run {
            ServerPlayNetworking.send(player, ProjectErrorS2C("no folder selected")); return
        }
        val root = rootFor(server) ?: run {
            ServerPlayNetworking.send(player, ProjectErrorS2C("project-root not configured")); return
        }
        val world = ProjectWorld.get(server)
        val folderAbsolute = world?.folderAbsoluteByPath?.get(activeSubpath)
            ?: root.resolveSubpath(activeSubpath)
            ?: run {
                ServerPlayNetworking.send(player, ProjectErrorS2C("active folder not resolvable: $activeSubpath")); return
            }
        try {
            ProjectNewSpec.create(folderAbsolute, payload.name)
        } catch (e: Exception) {
            LOGGER.error("[project/new-spec] create {}/{}: {}", activeSubpath, payload.name, e.message, e)
            ServerPlayNetworking.send(player, ProjectErrorS2C("new-spec failed: ${e.message}")); return
        }
        val report = try {
            ProjectDimLifecycle.placeFolder(server, root, activeSubpath)
        } catch (e: Exception) {
            LOGGER.error("[project/new-spec] re-place {}: {}", activeSubpath, e.message, e)
            ServerPlayNetworking.send(player, ProjectErrorS2C("re-place failed: ${e.message}")); return
        }
        ServerPlayNetworking.send(player, ProjectFolderLoadedS2C(
            subpath = report.subpath,
            loadedSpecIds = report.loaded,
            parseErrors = report.parseErrors.map { "${it.filename}: ${it.message}" },
            layoutErrors = report.errors.map { "${it.specId} (${it.filename}): ${it.reason}" },
        ))
    }

    fun handleSetRoot(server: MinecraftServer, player: ServerPlayer, payload: SetProjectRootC2S) {
        val abs = try {
            Path.of(payload.path).toAbsolutePath()
        } catch (e: java.nio.file.InvalidPathException) {
            ServerPlayNetworking.send(player, ProjectErrorS2C("invalid path: ${payload.path}")); return
        }
        if (!abs.isDirectory()) {
            ServerPlayNetworking.send(player, ProjectErrorS2C("not a folder: $abs")); return
        }
        val root = ProjectRoot(abs)
        SharedSettings.projectRootPath = abs.toString()
        ProjectServerContext.set(server, ProjectServerContext(root))
        ProjectDimLifecycle.placeAll(server, root)
        sendTree(server, player)
    }

    private fun sendTree(server: MinecraftServer, player: ServerPlayer) {
        val root = rootFor(server) ?: run {
            ServerPlayNetworking.send(player, ProjectErrorS2C("project-root not configured"))
            return
        }
        val current = ProjectSession.get(player.uuid)?.activeSubpath
        ServerPlayNetworking.send(player, ProjectTreeSnapshotS2C(
            root = scanFolder(root.path),
            currentSubpath = current,
        ))
    }

    private fun formatSaveResult(r: CellSaveResult): String =
        "${r.specId}|saved=${r.saved}${r.error?.let { "|err=$it" } ?: ""}"
}
