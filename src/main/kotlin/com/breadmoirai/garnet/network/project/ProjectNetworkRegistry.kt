package com.breadmoirai.garnet.network.project

import com.breadmoirai.garnet.config.SharedSettings
import com.breadmoirai.garnet.persistence.StructurePersistence
import com.breadmoirai.garnet.project.*
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.core.Vec3i
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.Relative
import org.slf4j.LoggerFactory
import java.nio.file.Path
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.isDirectory

private val LOGGER = LoggerFactory.getLogger("Garnet")

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
        PayloadTypeRegistry.serverboundPlay().register(PlaceStructureC2S.TYPE, PlaceStructureC2S.STREAM_CODEC)
        PayloadTypeRegistry.serverboundPlay().register(SaveStructureC2S.TYPE, SaveStructureC2S.STREAM_CODEC)
        PayloadTypeRegistry.serverboundPlay().register(NewStructureC2S.TYPE, NewStructureC2S.STREAM_CODEC)
        PayloadTypeRegistry.serverboundPlay().register(DiscardStructureC2S.TYPE, DiscardStructureC2S.STREAM_CODEC)
        PayloadTypeRegistry.clientboundPlay().register(ProjectTreeSnapshotS2C.TYPE, ProjectTreeSnapshotS2C.STREAM_CODEC)
        PayloadTypeRegistry.clientboundPlay().register(ProjectFolderLoadedS2C.TYPE, ProjectFolderLoadedS2C.STREAM_CODEC)
        PayloadTypeRegistry.clientboundPlay().register(ProjectSaveReportS2C.TYPE, ProjectSaveReportS2C.STREAM_CODEC)
        PayloadTypeRegistry.clientboundPlay().register(ProjectErrorS2C.TYPE, ProjectErrorS2C.STREAM_CODEC)
        PayloadTypeRegistry.clientboundPlay().register(StructureResultS2C.TYPE, StructureResultS2C.STREAM_CODEC)

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
        ServerPlayNetworking.registerGlobalReceiver(PlaceStructureC2S.TYPE) { payload, ctx ->
            ctx.server().execute { handlePlaceStructure(ctx.server(), ctx.player(), payload) }
        }
        ServerPlayNetworking.registerGlobalReceiver(SaveStructureC2S.TYPE) { payload, ctx ->
            ctx.server().execute { handleSaveStructure(ctx.server(), ctx.player(), payload) }
        }
        ServerPlayNetworking.registerGlobalReceiver(NewStructureC2S.TYPE) { payload, ctx ->
            ctx.server().execute { handleNewStructure(ctx.server(), ctx.player(), payload) }
        }
        ServerPlayNetworking.registerGlobalReceiver(DiscardStructureC2S.TYPE) { payload, ctx ->
            ctx.server().execute { handleDiscardStructure(ctx.server(), ctx.player(), payload) }
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

    private fun placeStructureFrom(
        server: MinecraftServer, player: ServerPlayer, subpath: String,
        source: Path, hasUnsaved: Boolean, message: String,
    ) {
        val registry = ProjectDimRegistry.of(server)
        val level = registry.projectLevel()
        val origin = registry.getOrAssignStructureRegion(subpath)
        val width = SharedSettings.structureRegionChunks * 16
        // Cheap re-clear: only the previously-placed footprint, not the whole region.
        registry.placedBoxOf(subpath)?.let { StructurePersistence.clearBounds(level, it.origin, it.size) }
        val placed = StructurePersistence.placeStructureCentered(
            source, level, origin, width, level.minY, level.maxY, SharedSettings.projectGridYBase,
        ) ?: run {
            ServerPlayNetworking.send(player, ProjectErrorS2C("failed to load structure: $subpath")); return
        }
        registry.setPlacedBox(subpath, placed)
        // Land the player just above the top of the placed structure (never inside it),
        // regardless of size. For an empty structure (size 0) this is the region floor + 2.
        val tpY = placed.origin.y + placed.size.y + 2
        player.teleportTo(
            level,
            (origin.x + width / 2) + 0.5, tpY.toDouble(), (origin.z + width / 2) + 0.5,
            emptySet<Relative>(), player.yRot, player.xRot, true,
        )
        ServerPlayNetworking.send(player, StructureResultS2C(
            subpath, placed.size.x, placed.size.y, placed.size.z, hasUnsaved, message,
        ))
    }

    fun handlePlaceStructure(server: MinecraftServer, player: ServerPlayer, payload: PlaceStructureC2S) {
        val root = rootFor(server) ?: run {
            ServerPlayNetworking.send(player, ProjectErrorS2C("project-root not configured")); return
        }
        val file = root.resolveSubpath(payload.subpath) ?: run {
            ServerPlayNetworking.send(player, ProjectErrorS2C("subpath not found or escapes root: ${payload.subpath}")); return
        }
        if (!payload.subpath.endsWith(".nbt")) {
            ServerPlayNetworking.send(player, ProjectErrorS2C("not a structure file: ${payload.subpath}")); return
        }
        val sidecar = StructurePersistence.unsavedSidecarOf(file)
        val hasUnsaved = sidecar.exists()
        val source = if (hasUnsaved) sidecar else file
        val message = if (hasUnsaved) "placed ${payload.subpath} — unsaved changes" else "placed ${payload.subpath}"
        placeStructureFrom(server, player, payload.subpath, source, hasUnsaved, message)
    }

    fun handleSaveStructure(server: MinecraftServer, player: ServerPlayer, payload: SaveStructureC2S) {
        val root = rootFor(server) ?: run {
            ServerPlayNetworking.send(player, ProjectErrorS2C("project-root not configured")); return
        }
        val file = root.resolveSubpath(payload.subpath) ?: run {
            ServerPlayNetworking.send(player, ProjectErrorS2C("subpath not found or escapes root: ${payload.subpath}")); return
        }
        if (!payload.subpath.endsWith(".nbt")) {
            ServerPlayNetworking.send(player, ProjectErrorS2C("not a structure file: ${payload.subpath}")); return
        }
        val registry = ProjectDimRegistry.of(server)
        if (registry.placedBoxOf(payload.subpath) == null) {
            ServerPlayNetworking.send(player, ProjectErrorS2C("place the structure before saving: ${payload.subpath}"))
            return
        }
        val level = registry.projectLevel()
        val origin = registry.getOrAssignStructureRegion(payload.subpath)
        val width = SharedSettings.structureRegionChunks * 16
        val box = StructurePersistence.saveAutoFitToFile(file, level, origin, width, level.minY, level.maxY)
        val size = box?.size ?: Vec3i(0, 0, 0)
        if (box != null) registry.setPlacedBox(payload.subpath, box)
        StructurePersistence.unsavedSidecarOf(file).deleteIfExists()
        val msg = if (box == null) "saved ${payload.subpath} (empty)"
                  else "saved ${payload.subpath} (${size.x}×${size.y}×${size.z})"
        ServerPlayNetworking.send(player, StructureResultS2C(payload.subpath, size.x, size.y, size.z, false, msg))
    }

    fun handleDiscardStructure(server: MinecraftServer, player: ServerPlayer, payload: DiscardStructureC2S) {
        val root = rootFor(server) ?: run {
            ServerPlayNetworking.send(player, ProjectErrorS2C("project-root not configured")); return
        }
        val file = root.resolveSubpath(payload.subpath) ?: run {
            ServerPlayNetworking.send(player, ProjectErrorS2C("subpath not found or escapes root: ${payload.subpath}")); return
        }
        if (!payload.subpath.endsWith(".nbt")) {
            ServerPlayNetworking.send(player, ProjectErrorS2C("not a structure file: ${payload.subpath}")); return
        }
        StructurePersistence.unsavedSidecarOf(file).deleteIfExists()
        placeStructureFrom(server, player, payload.subpath, file, false, "discarded ${payload.subpath}")
    }

    /** Capture each placed structure's region on world-save, writing/deleting its `.nbt.unsaved`. */
    fun flushDirtyStructures(server: MinecraftServer) {
        val root = rootFor(server) ?: return
        val registry = ProjectDimRegistry.of(server)
        val level = registry.projectLevel()
        val width = SharedSettings.structureRegionChunks * 16
        for (subpath in registry.placedStructureSubpaths()) {
            val file = root.resolveSubpath(subpath) ?: continue
            val origin = registry.structureRegionOriginOf(subpath) ?: continue
            StructurePersistence.flushUnsavedSidecar(file, level, origin, width, level.minY, level.maxY)
        }
    }

    fun handleNewStructure(server: MinecraftServer, player: ServerPlayer, payload: NewStructureC2S) {
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
            ProjectNewStructure.create(folderAbsolute, payload.name)
        } catch (e: Exception) {
            LOGGER.error("[project/new-structure] create {}/{}: {}", activeSubpath, payload.name, e.message, e)
            ServerPlayNetworking.send(player, ProjectErrorS2C("new-structure failed: ${e.message}")); return
        }
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
