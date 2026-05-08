package com.breadmoirai.redstonespecs.managed

import com.breadmoirai.redstonespecs.ModRegistries
import com.breadmoirai.redstonespecs.block.SpecBlockEntity
import com.breadmoirai.redstonespecs.config.SharedSettings
import com.breadmoirai.redstonespecs.data.RedstoneSpec
import com.breadmoirai.redstonespecs.data.serial.KtsSpecLoader
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.Registries
import net.minecraft.nbt.NbtAccounter
import net.minecraft.nbt.NbtIo
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.Relative
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate
import org.slf4j.LoggerFactory
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

private val LOGGER = LoggerFactory.getLogger("Redstone Specs")

data class ParseError(val filename: String, val message: String)
data class LoadFolderReport(
    val subpath: String,
    val loaded: List<String>,
    val errors: List<LayoutError>,
    val parseErrors: List<ParseError>,
)

object ManagedDimLifecycle {

    fun load(
        server: MinecraftServer,
        root: ManagedRoot,
        subpath: String,
        player: ServerPlayer,
    ): LoadFolderReport {
        val folder = root.resolveSubpath(subpath)
            ?: error("subpath outside root: $subpath")
        require(folder.isDirectory()) { "not a directory: $folder" }

        // 1. Parse all .spec.kts directly under the folder.
        val files = folder.listDirectoryEntries("*.spec.kts").sortedBy { it.name }
        val parsed = mutableListOf<Pair<String, RedstoneSpec>>()
        val parseErrors = mutableListOf<ParseError>()
        for (f in files) {
            try {
                parsed.add(f.name to KtsSpecLoader.loadFileAsRedstoneSpec(f))
            } catch (e: Exception) {
                parseErrors.add(ParseError(f.name, e.message ?: e::class.simpleName ?: "unknown"))
            }
        }

        // 2. Compute layout. GridLayout returns BlockPos with X/Z = slot offset and Y = yBase.
        val cellSize = SharedSettings.managedCellSize
        val cellGap = SharedSettings.managedCellGap
        val rowMax = SharedSettings.managedRowMax
        val yBase = SharedSettings.managedGridYBase
        val layout = GridLayout.compute(
            inputs = parsed.map { (name, s) -> LayoutInput(name, s) },
            cellSize, cellGap, rowMax, yBase,
        )

        // 3. Prefer per-folder dim (runtime datapack); fall back to single-dim + region.
        val registry = ManagedDimRegistry.of(server)
        val perFolder = registry.perFolderLevel(subpath)
        val level: ServerLevel
        val regionOrigin: BlockPos
        if (perFolder != null) {
            level = perFolder
            // Dedicated dim — no offset needed; placeCell math collapses to absolute coords.
            regionOrigin = BlockPos(0, SharedSettings.managedGridYBase, 0)
        } else {
            level = registry.managedLevel()
                ?: error("managed dim is not registered (data/redstonespecs/dimension/managed.json missing?)")
            regionOrigin = registry.getOrAssignRegion(subpath)
        }

        // 4. Place each cell.
        val loadedSpecs = mutableMapOf<String, LoadedSpec>()
        for ((name, spec) in parsed) {
            val cell = layout.cells[spec.id] ?: continue  // excluded by oversize
            // GridLayout's cell.origin.y already equals yBase, so use it directly for absolute Y;
            // X/Z are slot-offsets relative to (0,0), so add regionOrigin.{x,z}.
            val absOrigin = BlockPos(
                regionOrigin.x + cell.origin.x,
                cell.origin.y,
                regionOrigin.z + cell.origin.z,
            )
            val snapshot = placeCell(level, folder, name, spec, absOrigin)
            loadedSpecs[spec.id] = LoadedSpec(
                cell = cell,
                spec = spec,
                sourceFile = folder.resolve(name),
                loadedSnapshot = snapshot,
            )
        }

        // 5. Update registry's cell map for this folder (origins are region-relative for lookup).
        registry.setCellsForFolder(subpath, layout.byOrigin)

        // 6. Save the session and teleport.
        val session = ManagedSession(
            playerId = player.uuid,
            root = root,
            subpath = subpath,
            folderAbsolute = folder,
            regionOrigin = regionOrigin,
            loaded = loadedSpecs,
        )
        ManagedSession.set(session)

        val spawn = BlockPos(regionOrigin.x, yBase + 2, regionOrigin.z)
        player.teleportTo(
            level,
            spawn.x + 0.5,
            spawn.y.toDouble(),
            spawn.z + 0.5,
            emptySet<Relative>(),
            player.yRot,
            player.xRot,
            true,
        )

        LOGGER.info(
            "[ManagedDimLifecycle#load] loaded folder '{}' ({} specs, {} layout errors, {} parse errors)",
            subpath, loadedSpecs.size, layout.errors.size, parseErrors.size,
        )

        return LoadFolderReport(
            subpath = subpath,
            loaded = loadedSpecs.keys.toList(),
            errors = layout.errors,
            parseErrors = parseErrors,
        )
    }

    private fun placeCell(
        level: ServerLevel,
        folder: Path,
        filename: String,
        spec: RedstoneSpec,
        absOrigin: BlockPos,
    ): StructureTemplate {
        val structureFile = folder.resolve("${spec.structure ?: spec.id}.nbt")
        val structureExists = structureFile.exists()
        if (structureExists) {
            val nbt = NbtIo.readCompressed(structureFile, NbtAccounter.unlimitedHeap())
            val blockGetter = level.registryAccess().lookupOrThrow(Registries.BLOCK)
            val tpl = StructureTemplate()
            tpl.load(blockGetter, nbt)
            tpl.placeInWorld(level, absOrigin, absOrigin, StructurePlaceSettings(), level.random, 2)
        }

        // Anchor block: runner if structure exists, recorder if new spec.
        val anchorPos = absOrigin.offset(spec.bounds.x, 0, 0)
        val anchorBlock = if (structureExists) {
            ModRegistries.REDSTONE_SPEC_RUNNER_BLOCK
        } else {
            ModRegistries.REDSTONE_SPEC_RECORDER_BLOCK
        }
        level.setBlock(anchorPos, anchorBlock.defaultBlockState(), 2)
        val be = level.getBlockEntity(anchorPos) as? SpecBlockEntity
        be?.setSpec(spec)
        be?.managedSourcePath = folder.resolve(filename)

        // Snapshot the cell volume after placement.
        val snapshot = StructureTemplate()
        snapshot.fillFromWorld(level, absOrigin, spec.bounds, false, emptyList())
        return snapshot
    }

    fun saveNow(server: MinecraftServer, playerId: java.util.UUID): List<CellSaveResult> {
        val session = ManagedSession.get(playerId) ?: return emptyList()
        val registry = ManagedDimRegistry.of(server)
        val level = registry.perFolderLevel(session.subpath)
            ?: registry.managedLevel()
            ?: return emptyList()
        val results = mutableListOf<CellSaveResult>()
        val refreshed = mutableMapOf<String, LoadedSpec>()
        for ((id, loaded) in session.loaded) {
            val abs = session.absoluteCellOrigin(id) ?: continue
            val r = ManagedCellSaver.captureAndSaveIfDirty(level, loaded, abs, session.folderAbsolute)
            results.add(r)
            if (r.saved) {
                // Refresh in-memory snapshot to the just-saved state.
                val newSnap = StructureTemplate()
                newSnap.fillFromWorld(level, abs, loaded.spec.bounds, false, emptyList())
                refreshed[id] = loaded.copy(loadedSnapshot = newSnap)
            }
        }
        if (refreshed.isNotEmpty()) {
            session.loaded.putAll(refreshed)
        }
        return results
    }

    fun unload(server: MinecraftServer, playerId: java.util.UUID, save: Boolean = true): List<CellSaveResult> {
        val results = if (save) saveNow(server, playerId) else emptyList()
        ManagedSession.clear(playerId)
        return results
    }
}
