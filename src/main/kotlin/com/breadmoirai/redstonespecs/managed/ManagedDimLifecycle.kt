package com.breadmoirai.redstonespecs.managed

import com.breadmoirai.redstonespecs.ModRegistries
import com.breadmoirai.redstonespecs.block.SpecBlockEntity
import com.breadmoirai.redstonespecs.config.SharedSettings
import com.breadmoirai.redstonespecs.data.serial.KtsSpecLoader
import com.breadmoirai.redstonespecs.dsl.RedstoneSpec
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

private val LOGGER = LoggerFactory.getLogger("Redstone Specs")

data class ParseError(val filename: String, val message: String)
data class LoadFolderReport(
    val subpath: String,
    val loaded: List<String>,
    val errors: List<LayoutError>,
    val parseErrors: List<ParseError>,
)

object ManagedDimLifecycle {

    /**
     * Walks the tree under `root` and places every leaf folder. Region origins are assigned in
     * subpath-sorted order so they're stable across server restarts (the registry's counter is
     * in-memory ephemeral). Lazily creates the [ManagedWorld] for `server` if not already set.
     */
    fun placeAll(server: MinecraftServer, root: ManagedRoot): List<LoadFolderReport> {
        val world = ManagedWorld.get(server)?.takeIf { it.root == root }
            ?: ManagedWorld(root).also { ManagedWorld.set(server, it) }
        val tree = ManagedFolderTree.scan(root)
        val reports = mutableListOf<LoadFolderReport>()
        for (leaf in tree.leaves.sortedBy { it.subpath }) {
            reports.add(placeFolderInto(server, world, leaf.subpath))
        }
        return reports
    }

    /**
     * Place a single folder. Used by [placeAll]. May also be called directly to re-place a folder
     * after a `ManagedNewSpec.create` to materialize the new cell.
     */
    fun placeFolder(server: MinecraftServer, root: ManagedRoot, subpath: String): LoadFolderReport {
        val world = ManagedWorld.get(server)?.takeIf { it.root == root }
            ?: ManagedWorld(root).also { ManagedWorld.set(server, it) }
        return placeFolderInto(server, world, subpath)
    }

    private fun placeFolderInto(
        server: MinecraftServer,
        world: ManagedWorld,
        subpath: String,
    ): LoadFolderReport {
        val root = world.root
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

        // 3. Canvas is the overworld; folders map to regions via counter assignment.
        val registry = ManagedDimRegistry.of(server)
        val level = registry.managedLevel()
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
            "[ManagedDimLifecycle#placeFolder] placed folder '{}' ({} specs, {} layout errors, {} parse errors)",
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
        // TODO(Task 16+): bind spec to block entity via new dsl.RedstoneSpec path
        be?.managedSourcePath = folder.resolve(filename)

        // Snapshot the cell volume after placement.
        val snapshot = StructureTemplate()
        snapshot.fillFromWorld(level, absOrigin, spec.bounds, false, emptyList())
        return snapshot
    }

    /** Save dirty cells across ALL loaded folders in the world. */
    fun saveAll(server: MinecraftServer): List<CellSaveResult> {
        val world = ManagedWorld.get(server) ?: return emptyList()
        val results = mutableListOf<CellSaveResult>()
        for (subpath in world.perFolder.keys.toList()) {
            results.addAll(saveFolder(server, subpath))
        }
        return results
    }

    /** Save dirty cells in a single folder. */
    fun saveFolder(server: MinecraftServer, subpath: String): List<CellSaveResult> {
        val world = ManagedWorld.get(server) ?: return emptyList()
        val perFolder = world.perFolder[subpath] ?: return emptyList()
        val folderAbsolute = world.folderAbsoluteByPath[subpath] ?: return emptyList()
        val level = ManagedDimRegistry.of(server).managedLevel()
        val results = mutableListOf<CellSaveResult>()
        val refreshed = mutableMapOf<String, LoadedSpec>()
        for ((id, loaded) in perFolder) {
            val abs = world.absoluteCellOrigin(server, subpath, id) ?: continue
            val r = ManagedCellSaver.captureAndSaveIfDirty(level, loaded, abs, folderAbsolute)
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
