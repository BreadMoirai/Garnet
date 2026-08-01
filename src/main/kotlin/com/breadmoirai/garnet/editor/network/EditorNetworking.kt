package com.breadmoirai.garnet.editor.network

import com.breadmoirai.garnet.config.SharedSettings
import com.breadmoirai.garnet.history.LocalHistoryStore
import com.breadmoirai.garnet.structure.StructurePersistence
import com.breadmoirai.garnet.editor.data.*
import com.breadmoirai.garnet.editor.world.*
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.core.Vec3i
import net.minecraft.core.registries.Registries
import net.minecraft.nbt.NbtAccounter
import net.minecraft.nbt.NbtIo
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.Relative
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate
import org.slf4j.LoggerFactory
import java.nio.file.Path
import kotlin.io.path.createDirectory
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.moveTo
import kotlin.io.path.name

private val LOGGER = LoggerFactory.getLogger("Garnet")

object EditorNetworking {

    /**
     * The active managed root: the loaded world's, else a pinned server context's, else the
     * configured path. Public because [com.breadmoirai.garnet.editor.world.StructureCommit] resolves
     * subpaths through the same rule.
     */
    fun rootFor(server: MinecraftServer): EditorRoot? {
        val world = EditorWorld.get(server)
        if (world != null) return world.root
        val ctx = EditorServerContext.get(server)
        if (ctx != null) return ctx.root
        val cfg = SharedSettings.projectRootPath
        return if (cfg.isNotBlank()) EditorRoot(Path.of(cfg).toAbsolutePath()) else null
    }

    fun register() {
        // Payload-type registrations
        PayloadTypeRegistry.serverboundPlay().register(ListEditorTreeC2S.TYPE, ListEditorTreeC2S.STREAM_CODEC)
        PayloadTypeRegistry.serverboundPlay().register(LoadEditorFolderC2S.TYPE, LoadEditorFolderC2S.STREAM_CODEC)
        PayloadTypeRegistry.serverboundPlay().register(UnloadEditorFolderC2S.TYPE, UnloadEditorFolderC2S.STREAM_CODEC)
        PayloadTypeRegistry.serverboundPlay().register(SaveNowC2S.TYPE, SaveNowC2S.STREAM_CODEC)
        PayloadTypeRegistry.serverboundPlay().register(NewEditorSpecC2S.TYPE, NewEditorSpecC2S.STREAM_CODEC)
        PayloadTypeRegistry.serverboundPlay().register(SetEditorRootC2S.TYPE, SetEditorRootC2S.STREAM_CODEC)
        PayloadTypeRegistry.serverboundPlay().register(PlaceStructureC2S.TYPE, PlaceStructureC2S.STREAM_CODEC)
        PayloadTypeRegistry.serverboundPlay().register(SaveStructureC2S.TYPE, SaveStructureC2S.STREAM_CODEC)
        PayloadTypeRegistry.serverboundPlay().register(NewStructureC2S.TYPE, NewStructureC2S.STREAM_CODEC)
        PayloadTypeRegistry.serverboundPlay().register(CreateFolderC2S.TYPE, CreateFolderC2S.STREAM_CODEC)
        PayloadTypeRegistry.serverboundPlay().register(RenamePathC2S.TYPE, RenamePathC2S.STREAM_CODEC)
        PayloadTypeRegistry.clientboundPlay().register(EditorTreeSnapshotS2C.TYPE, EditorTreeSnapshotS2C.STREAM_CODEC)
        PayloadTypeRegistry.clientboundPlay().register(EditorFolderLoadedS2C.TYPE, EditorFolderLoadedS2C.STREAM_CODEC)
        PayloadTypeRegistry.clientboundPlay().register(EditorSaveReportS2C.TYPE, EditorSaveReportS2C.STREAM_CODEC)
        PayloadTypeRegistry.clientboundPlay().register(EditorErrorS2C.TYPE, EditorErrorS2C.STREAM_CODEC)
        PayloadTypeRegistry.clientboundPlay().register(StructureResultS2C.TYPE, StructureResultS2C.STREAM_CODEC)
        PayloadTypeRegistry.clientboundPlay().register(StructureAutoSavedS2C.TYPE, StructureAutoSavedS2C.STREAM_CODEC)

        ServerPlayNetworking.registerGlobalReceiver(ListEditorTreeC2S.TYPE) { _, ctx ->
            ctx.server().execute { handleListTree(ctx.server(), ctx.player()) }
        }
        ServerPlayNetworking.registerGlobalReceiver(LoadEditorFolderC2S.TYPE) { payload, ctx ->
            ctx.server().execute { handleLoadFolder(ctx.server(), ctx.player(), payload) }
        }
        ServerPlayNetworking.registerGlobalReceiver(UnloadEditorFolderC2S.TYPE) { _, ctx ->
            ctx.server().execute { handleUnload(ctx.server(), ctx.player()) }
        }
        ServerPlayNetworking.registerGlobalReceiver(SaveNowC2S.TYPE) { _, ctx ->
            ctx.server().execute { handleSaveNow(ctx.server(), ctx.player()) }
        }
        ServerPlayNetworking.registerGlobalReceiver(NewEditorSpecC2S.TYPE) { payload, ctx ->
            ctx.server().execute { handleNewSpec(ctx.server(), ctx.player(), payload) }
        }
        ServerPlayNetworking.registerGlobalReceiver(SetEditorRootC2S.TYPE) { payload, ctx ->
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
        ServerPlayNetworking.registerGlobalReceiver(CreateFolderC2S.TYPE) { payload, ctx ->
            ctx.server().execute { handleCreateFolder(ctx.server(), ctx.player(), payload) }
        }
        ServerPlayNetworking.registerGlobalReceiver(RenamePathC2S.TYPE) { payload, ctx ->
            ctx.server().execute { handleRename(ctx.server(), ctx.player(), payload) }
        }
    }

    fun handleListTree(server: MinecraftServer, player: ServerPlayer) {
        sendTree(server, player)
    }

    fun handleLoadFolder(server: MinecraftServer, player: ServerPlayer, payload: LoadEditorFolderC2S) {
        val root = rootFor(server) ?: run {
            ServerPlayNetworking.send(player, EditorErrorS2C("project-root not configured")); return
        }
        if (root.resolveSubpath(payload.subpath) == null) {
            ServerPlayNetworking.send(player, EditorErrorS2C("subpath not found or escapes root: ${payload.subpath}")); return
        }
        val ok = EditorTeleport.toFolder(server, player, payload.subpath)
        if (!ok) {
            ServerPlayNetworking.send(player, EditorErrorS2C("folder not placed: ${payload.subpath}")); return
        }
        val world = EditorWorld.get(server)
        val loadedIds = world?.perFolder?.get(payload.subpath)?.keys?.toList().orEmpty()
        ServerPlayNetworking.send(player, EditorFolderLoadedS2C(
            subpath = payload.subpath,
            loadedSpecIds = loadedIds,
            parseErrors = emptyList(),
            layoutErrors = emptyList(),
        ))
    }

    fun handleUnload(server: MinecraftServer, player: ServerPlayer) {
        EditorSession.clear(player.uuid)
        ServerPlayNetworking.send(player, EditorSaveReportS2C(emptyList()))
    }

    fun handleSaveNow(server: MinecraftServer, player: ServerPlayer) {
        val results = EditorDimLifecycle.saveAll(server)
        ServerPlayNetworking.send(player, EditorSaveReportS2C(results.map(::formatSaveResult)))
    }

    fun handleNewSpec(server: MinecraftServer, player: ServerPlayer, payload: NewEditorSpecC2S) {
        val activeSubpath = EditorSession.get(player.uuid)?.activeSubpath ?: run {
            ServerPlayNetworking.send(player, EditorErrorS2C("no folder selected")); return
        }
        val root = rootFor(server) ?: run {
            ServerPlayNetworking.send(player, EditorErrorS2C("project-root not configured")); return
        }
        val world = EditorWorld.get(server)
        val folderAbsolute = world?.folderAbsoluteByPath?.get(activeSubpath)
            ?: root.resolveSubpath(activeSubpath)
            ?: run {
                ServerPlayNetworking.send(player, EditorErrorS2C("active folder not resolvable: $activeSubpath")); return
            }
        try {
            EditorNewSpec.create(folderAbsolute, payload.name)
        } catch (e: Exception) {
            LOGGER.error("[project/new-spec] create {}/{}: {}", activeSubpath, payload.name, e.message, e)
            ServerPlayNetworking.send(player, EditorErrorS2C("new-spec failed: ${e.message}")); return
        }
        val report = try {
            EditorDimLifecycle.placeFolder(server, root, activeSubpath)
        } catch (e: Exception) {
            LOGGER.error("[project/new-spec] re-place {}: {}", activeSubpath, e.message, e)
            ServerPlayNetworking.send(player, EditorErrorS2C("re-place failed: ${e.message}")); return
        }
        ServerPlayNetworking.send(player, EditorFolderLoadedS2C(
            subpath = report.subpath,
            loadedSpecIds = report.loaded,
            parseErrors = report.parseErrors.map { "${it.filename}: ${it.message}" },
            layoutErrors = report.errors.map { "${it.specId} (${it.filename}): ${it.reason}" },
        ))
    }

    fun handleSetRoot(server: MinecraftServer, player: ServerPlayer, payload: SetEditorRootC2S) {
        val abs = try {
            Path.of(payload.path).toAbsolutePath()
        } catch (e: java.nio.file.InvalidPathException) {
            ServerPlayNetworking.send(player, EditorErrorS2C("invalid path: ${payload.path}")); return
        }
        if (!abs.isDirectory()) {
            ServerPlayNetworking.send(player, EditorErrorS2C("not a folder: $abs")); return
        }
        val root = EditorRoot(abs)
        SharedSettings.projectRootPath = abs.toString()
        EditorServerContext.set(server, EditorServerContext(root))
        EditorDimLifecycle.placeAll(server, root)
        sendTree(server, player)
    }

    private fun placeStructureFrom(
        server: MinecraftServer, player: ServerPlayer, subpath: String,
        source: Path, message: String,
    ) {
        val registry = EditorDimRegistry.of(server)
        val level = registry.projectLevel()
        val origin = registry.getOrAssignStructureRegion(subpath)
        val width = SharedSettings.structureRegionChunks * 16
        // Cheap re-clear: only the previously-placed footprint, not the whole region.
        registry.placedBoxOf(subpath)?.let { StructurePersistence.clearBounds(level, it.origin, it.size) }
        val placed = StructurePersistence.placeStructureCentered(
            source, level, origin, width, level.minY, level.maxY, SharedSettings.projectGridYBase,
        ) ?: run {
            ServerPlayNetworking.send(player, EditorErrorS2C("failed to load structure: $subpath")); return
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
            subpath, placed.size.x, placed.size.y, placed.size.z, message,
        ))
    }

    fun handlePlaceStructure(server: MinecraftServer, player: ServerPlayer, payload: PlaceStructureC2S) {
        val root = rootFor(server) ?: run {
            ServerPlayNetworking.send(player, EditorErrorS2C("project-root not configured")); return
        }
        val file = root.resolveSubpath(payload.subpath) ?: run {
            ServerPlayNetworking.send(player, EditorErrorS2C("subpath not found or escapes root: ${payload.subpath}")); return
        }
        if (!payload.subpath.endsWith(".nbt")) {
            ServerPlayNetworking.send(player, EditorErrorS2C("not a structure file: ${payload.subpath}")); return
        }
        // Seed the pre-edit baseline so a rollback target exists from the moment the structure is
        // opened, not only after the first auto-save.
        if (file.exists()) {
            val tag = runCatching { NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap()) }.getOrNull()
            if (tag != null && LocalHistoryStore.revisions(file).isEmpty()) {
                val template = StructureTemplate()
                template.load(server.registryAccess().lookupOrThrow(Registries.BLOCK), tag)
                val size = template.size
                LocalHistoryStore.writeRevision(
                    file, tag, size.x, size.y, size.z,
                    blockCount = 0, reason = LocalHistoryStore.REASON_PLACED,
                )
            }
        }
        placeStructureFrom(server, player, payload.subpath, file, "placed ${payload.subpath}")
    }

    fun handleSaveStructure(server: MinecraftServer, player: ServerPlayer, payload: SaveStructureC2S) {
        val root = rootFor(server) ?: run {
            ServerPlayNetworking.send(player, EditorErrorS2C("project-root not configured")); return
        }
        if (root.resolveSubpath(payload.subpath) == null) {
            ServerPlayNetworking.send(player, EditorErrorS2C("subpath not found or escapes root: ${payload.subpath}")); return
        }
        if (!payload.subpath.endsWith(".nbt")) {
            ServerPlayNetworking.send(player, EditorErrorS2C("not a structure file: ${payload.subpath}")); return
        }
        if (EditorDimRegistry.of(server).placedBoxOf(payload.subpath) == null) {
            ServerPlayNetworking.send(player, EditorErrorS2C("place the structure before saving: ${payload.subpath}"))
            return
        }
        val result = StructureCommit.commit(server, payload.subpath, LocalHistoryStore.REASON_MANUAL)
        if (result == null) {
            // Nothing to write: the region already matches the committed file.
            ServerPlayNetworking.send(player, StructureResultS2C(
                payload.subpath, 0, 0, 0, "no changes to save: ${payload.subpath}",
            ))
        } else {
            StructureCommit.broadcast(server, result)
        }
    }

    fun handleNewStructure(server: MinecraftServer, player: ServerPlayer, payload: NewStructureC2S) {
        val folder = resolveParentFolder(server, player, payload.parentSubpath) ?: return
        val finalName = EditorNames.resolveFinalName(payload.name, NewNodeKind.STRUCTURE)
        EditorNames.validate(finalName, siblingNames(folder))?.let {
            ServerPlayNetworking.send(player, EditorErrorS2C(it)); return
        }
        try {
            // EditorNewStructure.create appends ".nbt" itself, so hand it the bare stem.
            // resolveFinalName normalizes the extension to lowercase ".nbt", so this case-sensitive
            // removeSuffix is safe -- see resolveFinalName's doc for why normalizing there, rather than
            // stripping case-insensitively here, is the fix.
            EditorNewStructure.create(folder, finalName.removeSuffix(".nbt"))
        } catch (e: Exception) {
            LOGGER.error("[project/new-structure] create {}/{}: {}", payload.parentSubpath, finalName, e.message, e)
            ServerPlayNetworking.send(player, EditorErrorS2C("new-structure failed: ${e.message}")); return
        }
        sendTree(server, player)
    }

    fun handleCreateFolder(server: MinecraftServer, player: ServerPlayer, payload: CreateFolderC2S) {
        val parent = resolveParentFolder(server, player, payload.parentSubpath) ?: return
        val name = payload.name.trim()
        EditorNames.validate(name, siblingNames(parent))?.let {
            ServerPlayNetworking.send(player, EditorErrorS2C(it)); return
        }
        try {
            parent.resolve(name).createDirectory()
        } catch (e: Exception) {
            LOGGER.error("[project/create-folder] {}/{}: {}", payload.parentSubpath, name, e.message, e)
            ServerPlayNetworking.send(player, EditorErrorS2C("create-folder failed: ${e.message}")); return
        }
        sendTree(server, player)
    }

    fun handleRename(server: MinecraftServer, player: ServerPlayer, payload: RenamePathC2S) {
        val root = rootFor(server) ?: run {
            ServerPlayNetworking.send(player, EditorErrorS2C("project-root not configured")); return
        }
        val source = root.resolveSubpath(payload.subpath) ?: run {
            ServerPlayNetworking.send(
                player,
                EditorErrorS2C("path not found or escapes root: ${payload.subpath}"),
            ); return
        }
        if (payload.subpath.isEmpty()) {
            ServerPlayNetworking.send(player, EditorErrorS2C("cannot rename the project root")); return
        }
        val newName = payload.newName.trim()
        val parent = source.parent
        // Exclude the node itself so re-committing an unchanged name is a no-op, not a collision.
        val siblings = siblingNames(parent).filterNot { it == source.name }
        EditorNames.validate(newName, siblings)?.let {
            ServerPlayNetworking.send(player, EditorErrorS2C(it)); return
        }

        val parentSubpath = payload.subpath.substringBeforeLast('/', "")
        val newSubpath = if (parentSubpath.isEmpty()) newName else "$parentSubpath/$newName"

        // A placed structure is keyed by subpath in EditorDimRegistry, so renaming under it would
        // strand both the placed box and the region assignment if we don't unload/reload it. But the
        // move (an IO op — can fail on a lock, permission problem, etc.) must succeed FIRST: tearing
        // down the placed-structure state (clearing its blocks, dropping its registry keys) before
        // the move is confirmed would leave the structure's blocks erased and its registry entry gone
        // while the player is told the rename failed and the (untouched, still-old-named) file sits
        // there unrecoverably out of sync with the world. Only touch that state once the move is
        // confirmed.
        val registry = EditorDimRegistry.of(server)
        val wasPlaced = registry.placedBoxOf(payload.subpath)

        // Commit before the move: the dirty box is keyed by subpath, so moving first would strand
        // the edits under a name nothing will ever commit again.
        if (wasPlaced != null) StructureCommit.commit(server, payload.subpath, LocalHistoryStore.REASON_AUTOSAVE)

        val target = parent.resolve(newName)
        try {
            source.moveTo(target)
            // History is keyed by the file's absolute path, so a rename must carry it across or the
            // structure silently loses every revision it has accumulated.
            LocalHistoryStore.moveHistory(source, target)
        } catch (e: Exception) {
            LOGGER.error("[project/rename] {} -> {}: {}", payload.subpath, newSubpath, e.message, e)
            ServerPlayNetworking.send(player, EditorErrorS2C("rename failed: ${e.message}")); return
        }

        if (wasPlaced != null) {
            StructurePersistence.clearBounds(registry.projectLevel(), wasPlaced.origin, wasPlaced.size)
            registry.unplaceStructure(payload.subpath)
        }

        if (wasPlaced != null) {
            placeStructureFrom(server, player, newSubpath, target, "renamed to $newSubpath")
        }

        // Rekey every OTHER registry entry nested under the renamed path (e.g. structures placed
        // inside a renamed folder). The renamed node's own entry, if any, was already handled above by
        // the wasPlaced block, so by this point rekeyForRename's exact-match branch is a no-op for it —
        // only descendants still keyed under the old subpath remain to be moved.
        registry.rekeyForRename(payload.subpath, newSubpath)

        repointSession(player, payload.subpath, newSubpath)

        sendTree(server, player)
    }

    /**
     * Keep a loaded project reachable after one of its ancestors is renamed: an activeSubpath equal
     * to [oldSubpath], or nested under it, is rewritten onto [newSubpath].
     */
    private fun repointSession(player: ServerPlayer, oldSubpath: String, newSubpath: String) {
        val active = EditorSession.get(player.uuid)?.activeSubpath ?: return
        when {
            active == oldSubpath -> EditorSession.setActive(player.uuid, newSubpath)
            active.startsWith("$oldSubpath/") ->
                EditorSession.setActive(player.uuid, newSubpath + active.removePrefix(oldSubpath))
        }
    }

    /**
     * The absolute path of [parentSubpath] under the configured root, or null after sending the
     * player an error. `""` means the root itself, which `resolveSubpath` already handles; anything
     * absolute or escaping the root comes back null from that call and is refused here.
     */
    private fun resolveParentFolder(
        server: MinecraftServer,
        player: ServerPlayer,
        parentSubpath: String,
    ): Path? {
        val root = rootFor(server) ?: run {
            ServerPlayNetworking.send(player, EditorErrorS2C("project-root not configured")); return null
        }
        val folder = root.resolveSubpath(parentSubpath) ?: run {
            ServerPlayNetworking.send(
                player,
                EditorErrorS2C("folder not found or escapes root: $parentSubpath"),
            ); return null
        }
        if (!folder.isDirectory()) {
            ServerPlayNetworking.send(player, EditorErrorS2C("not a folder: $parentSubpath")); return null
        }
        return folder
    }

    /** Names already present in [folder], for the duplicate check. */
    private fun siblingNames(folder: Path): List<String> =
        folder.listDirectoryEntries().map { it.name }

    private fun sendTree(server: MinecraftServer, player: ServerPlayer) {
        val root = rootFor(server) ?: run {
            ServerPlayNetworking.send(player, EditorErrorS2C("project-root not configured"))
            return
        }
        val current = EditorSession.get(player.uuid)?.activeSubpath
        ServerPlayNetworking.send(player, EditorTreeSnapshotS2C(
            root = scanFolder(root.path),
            currentSubpath = current,
        ))
    }

    private fun formatSaveResult(r: CellSaveResult): String =
        "${r.specId}|saved=${r.saved}${r.error?.let { "|err=$it" } ?: ""}"
}
