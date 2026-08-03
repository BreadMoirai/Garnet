package com.breadmoirai.garnet.editor.network

import com.breadmoirai.garnet.config.SharedSettings
import com.breadmoirai.garnet.editor.data.*
import com.breadmoirai.garnet.editor.network.EditorHandlerSupport.fail
import com.breadmoirai.garnet.editor.network.EditorHandlerSupport.resolveParentFolder
import com.breadmoirai.garnet.editor.network.EditorHandlerSupport.sendTree
import com.breadmoirai.garnet.editor.network.EditorHandlerSupport.siblingNames
import com.breadmoirai.garnet.editor.structure.StructureCommit
import com.breadmoirai.garnet.editor.world.*
import com.breadmoirai.garnet.history.LocalHistoryStore
import com.breadmoirai.garnet.structure.StructurePersistence
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.core.registries.Registries
import net.minecraft.nbt.NbtAccounter
import net.minecraft.nbt.NbtIo
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.Relative
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate
import org.slf4j.LoggerFactory
import java.nio.file.Path
import kotlin.io.path.exists

private val LOGGER = LoggerFactory.getLogger("Garnet")

object EditorStructureHandlers {

    fun placeStructureFrom(
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
            fail(player, "failed to load structure: $subpath"); return
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
        val root = EditorRootResolver.rootFor(server) ?: run {
            fail(player, "project-root not configured"); return
        }
        val file = root.resolveSubpath(payload.subpath) ?: run {
            fail(player, "subpath not found or escapes root: ${payload.subpath}"); return
        }
        if (!payload.subpath.endsWith(".nbt")) {
            fail(player, "not a structure file: ${payload.subpath}"); return
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
        val root = EditorRootResolver.rootFor(server) ?: run {
            fail(player, "project-root not configured"); return
        }
        if (root.resolveSubpath(payload.subpath) == null) {
            fail(player, "subpath not found or escapes root: ${payload.subpath}"); return
        }
        if (!payload.subpath.endsWith(".nbt")) {
            fail(player, "not a structure file: ${payload.subpath}"); return
        }
        if (EditorDimRegistry.of(server).placedBoxOf(payload.subpath) == null) {
            fail(player, "place the structure before saving: ${payload.subpath}")
            return
        }
        when (val outcome = StructureCommit.commit(server, payload.subpath, LocalHistoryStore.REASON_MANUAL)) {
            is StructureCommit.CommitOutcome.Committed -> {
                // This is a REPLY to the SaveStructureC2S `player` just sent — they provably have
                // the mod (they just used one of its channels), so send to them directly and
                // unconditionally, the same way every other S2C in this file replies. The `canSend`
                // guard on StructureCommit.broadcast exists for the genuinely UNSOLICITED fan-out
                // (StructureCommit.tick's debounce, commitAll's backstop) where the recipient never
                // asked for anything and isn't provably running the mod at all (F6) — it does not
                // apply to a reply. Still broadcast to every OTHER player (guarded) so their
                // Explorer status lines pick up the change too.
                ServerPlayNetworking.send(player, outcome.payload)
                StructureCommit.broadcast(server, outcome.payload, exclude = player)
            }
            is StructureCommit.CommitOutcome.NoChange -> {
                // Nothing to write: the region already matches the committed file.
                ServerPlayNetworking.send(player, StructureResultS2C(
                    payload.subpath, 0, 0, 0, "no changes to save: ${payload.subpath}",
                ))
            }
            is StructureCommit.CommitOutcome.NotApplicable -> {
                // Shouldn't happen — placedBoxOf(subpath) was already confirmed non-null above —
                // but report it honestly rather than silently claiming success.
                fail(player, "place the structure before saving: ${payload.subpath}")
            }
            is StructureCommit.CommitOutcome.Failed -> {
                // The edits exist only in the world; a bare "no changes to save" here would tell the
                // player their work is safe when it is not (Task 7 fix round 1 / Finding 4).
                fail(player, "save failed: ${outcome.reason}")
            }
        }
    }

    fun handleNewStructure(server: MinecraftServer, player: ServerPlayer, payload: NewStructureC2S) {
        val folder = resolveParentFolder(server, player, payload.parentSubpath) ?: return
        val finalName = EditorNames.resolveFinalName(payload.name, NewNodeKind.STRUCTURE)
        EditorNames.validate(finalName, siblingNames(folder))?.let {
            fail(player, it); return
        }
        try {
            // EditorNewStructure.create appends ".nbt" itself, so hand it the bare stem.
            // resolveFinalName normalizes the extension to lowercase ".nbt", so this case-sensitive
            // removeSuffix is safe -- see resolveFinalName's doc for why normalizing there, rather than
            // stripping case-insensitively here, is the fix.
            EditorNewStructure.create(folder, finalName.removeSuffix(".nbt"))
        } catch (e: Exception) {
            LOGGER.error("[project/new-structure] create {}/{}: {}", payload.parentSubpath, finalName, e.message, e)
            fail(player, "new-structure failed: ${e.message}"); return
        }
        sendTree(server, player)
    }
}
