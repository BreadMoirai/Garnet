package com.breadmoirai.garnet.editor.workspace.world

import com.breadmoirai.garnet.core.config.SharedSettings
import com.breadmoirai.garnet.editor.explorer.data.EditorFolderTree
import com.breadmoirai.garnet.editor.explorer.data.EditorRoot
import com.breadmoirai.garnet.testing.data.KtsSpecLoader
import com.breadmoirai.garnet.editor.explorer.data.LoadedSpec
import com.breadmoirai.garnet.core.spec.GarnetSpec
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.Registries
import net.minecraft.nbt.NbtAccounter
import net.minecraft.nbt.NbtIo
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate
import org.slf4j.LoggerFactory
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

private val LOGGER = LoggerFactory.getLogger("Garnet")

data class ParseError(val filename: String, val message: String)
data class LoadFolderReport(
    val subpath: String,
    val loaded: List<String>,
    val errors: List<LayoutError>,
    val parseErrors: List<ParseError>,
)

object EditorDimLifecycle {

    /**
     * Walks the tree under `root` and places every leaf folder. Region origins are assigned in
     * subpath-sorted order so they're stable across server restarts (the registry's counter is
     * in-memory ephemeral). Lazily creates the [EditorWorld] for `server` if not already set.
     */
    fun placeAll(server: MinecraftServer, root: EditorRoot): List<LoadFolderReport> {
        val world = EditorWorld.get(server)?.takeIf { it.root == root }
            ?: EditorWorld(root).also { EditorWorld.set(server, it) }
        val tree = EditorFolderTree.scan(root)
        val reports = mutableListOf<LoadFolderReport>()
        for (leaf in tree.leaves.sortedBy { it.subpath }) {
            reports.add(placeFolderInto(server, world, leaf.subpath))
        }
        return reports
    }

    /**
     * Place a single folder. Used by [placeAll]. May also be called directly to re-place a folder
     * after a `EditorNewSpec.create` to materialize the new cell.
     */
    fun placeFolder(server: MinecraftServer, root: EditorRoot, subpath: String): LoadFolderReport {
        val world = EditorWorld.get(server)?.takeIf { it.root == root }
            ?: EditorWorld(root).also { EditorWorld.set(server, it) }
        return placeFolderInto(server, world, subpath)
    }

    private fun placeFolderInto(
        server: MinecraftServer,
        world: EditorWorld,
        subpath: String,
    ): LoadFolderReport {
        val root = world.root
        val folder = root.resolveSubpath(subpath)
            ?: error("subpath outside root: $subpath")
        require(folder.isDirectory()) { "not a directory: $folder" }

        // 1. Parse all .spec.kts directly under the folder.
        val files = folder.listDirectoryEntries("*.spec.kts").sortedBy { it.name }
        val parsed = mutableListOf<Pair<String, GarnetSpec>>()
        val parseErrors = mutableListOf<ParseError>()
        for (f in files) {
            try {
                parsed.add(f.name to KtsSpecLoader.loadFileAsGarnetSpec(f))
            } catch (e: Exception) {
                parseErrors.add(ParseError(f.name, e.message ?: e::class.simpleName ?: "unknown"))
            }
        }

        // 2. Compute layout. GridLayout returns BlockPos with X/Z = slot offset and Y = yBase.
        val cellSize = SharedSettings.projectCellSize
        val cellGap = SharedSettings.projectCellGap
        val rowMax = SharedSettings.projectRowMax
        val yBase = SharedSettings.projectGridYBase
        val layout = GridLayout.compute(
            inputs = parsed.map { (name, s) -> LayoutInput(name, s) },
            cellSize, cellGap, rowMax, yBase,
        )

        // 3. Canvas is the overworld; folders map to regions via counter assignment.
        val registry = EditorDimRegistry.of(server)
        val level = registry.projectLevel()
        val regionOrigin = registry.getOrAssignRegion(subpath)

        // 4. Place each cell.
        val loadedSpecs = mutableMapOf<String, LoadedSpec>()
        for ((name, spec) in parsed) {
            val cell = layout.cells[spec.id] ?: continue  // excluded by oversize
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

        // 5. Register with the world (replace any prior entry for this subpath).
        world.folderAbsoluteByPath[subpath] = folder
        val perFolderMap = java.util.concurrent.ConcurrentHashMap<String, LoadedSpec>(loadedSpecs)
        world.perFolder[subpath] = perFolderMap

        LOGGER.info(
            "[EditorDimLifecycle#placeFolder] placed folder '{}' ({} specs, {} layout errors, {} parse errors)",
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
        spec: GarnetSpec,
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

        // Anchor blocks were removed with the pre-dock in-world surface; a cell is now
        // just its structure. Binding a source path to a block entity went with them.

        // Snapshot the cell volume after placement.
        val snapshot = StructureTemplate()
        snapshot.fillFromWorld(level, absOrigin, spec.bounds, false, emptyList())
        return snapshot
    }

    /**
     * Release all server-scoped managed state on server stop. The three holders are
     * `WeakHashMap`-keyed by server so they would eventually be GC'd once the server is
     * dereferenced, but this makes the release prompt and deterministic (UC-MAN-08.d).
     * Per-player `EditorSession` entries are not touched here — they are released on each
     * player's DISCONNECT (UC-MAN-08.c), which fires for every player as the server closes.
     */
    fun releaseServerState(server: MinecraftServer) {
        EditorDimRegistry.dispose(server)
        EditorWorld.clear(server)
        EditorServerContext.clear(server)
    }

    /** Save dirty cells across ALL loaded folders in the world. */
    fun saveAll(server: MinecraftServer): List<CellSaveResult> {
        val world = EditorWorld.get(server) ?: return emptyList()
        val results = mutableListOf<CellSaveResult>()
        for (subpath in world.perFolder.keys.toList()) {
            results.addAll(saveFolder(server, subpath))
        }
        return results
    }

    /** Save dirty cells in a single folder. */
    fun saveFolder(server: MinecraftServer, subpath: String): List<CellSaveResult> {
        val world = EditorWorld.get(server) ?: return emptyList()
        val perFolder = world.perFolder[subpath] ?: return emptyList()
        val folderAbsolute = world.folderAbsoluteByPath[subpath] ?: return emptyList()
        val level = EditorDimRegistry.of(server).projectLevel()
        val results = mutableListOf<CellSaveResult>()
        val refreshed = mutableMapOf<String, LoadedSpec>()
        for ((id, loaded) in perFolder) {
            val abs = world.absoluteCellOrigin(server, subpath, id) ?: continue
            val r = EditorCellSaver.captureAndSaveIfDirty(level, loaded, abs, folderAbsolute)
            results.add(r)
            if (r.saved) {
                val newSnap = StructureTemplate()
                newSnap.fillFromWorld(level, abs, loaded.spec.bounds, false, emptyList())
                refreshed[id] = loaded.copy(loadedSnapshot = newSnap)
            }
        }
        if (refreshed.isNotEmpty()) {
            perFolder.putAll(refreshed)
        }
        return results
    }
}
