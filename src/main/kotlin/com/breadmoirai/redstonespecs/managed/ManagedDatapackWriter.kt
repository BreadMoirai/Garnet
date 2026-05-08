package com.breadmoirai.redstonespecs.managed

import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

private val LOGGER = LoggerFactory.getLogger("Redstone Specs")

/**
 * Writes a runtime datapack of `LevelStem` JSONs — one per leaf folder under a `ManagedRoot`.
 * The datapack is rooted at `<saveDir>/datapacks/redstonespecs-managed/`. MC's vanilla
 * data-pack loader picks up the contained JSONs at server bootstrap and registers the
 * corresponding `ServerLevel`s.
 *
 * Call BEFORE `MinecraftServer` constructs its levels — i.e. before the integrated server boots
 * (singleplayer) or before the dedicated server's `runServer()` (typically in mod init given the
 * config is already loaded by then).
 *
 * Caveat: by the time `SERVER_STARTING` fires, level construction is already underway, so a
 * datapack written from inside that listener does NOT get its dims registered until the *next*
 * server start. Folders created mid-session likewise need a restart to materialize as dedicated
 * dims; until then the fallback single-dim + region path handles them.
 */
object ManagedDatapackWriter {

    /** Standard pack.mcmeta describing format compatibility. */
    private const val PACK_MCMETA = """{
  "pack": {
    "pack_format": 48,
    "description": "redstonespecs managed-worlds runtime datapack"
  }
}
"""

    /** Same flat-void content as the static `managed.json`; uses the managed_void DimensionType. */
    private const val LEVEL_STEM_JSON = """{
  "type": "redstonespecs:managed_void",
  "generator": {
    "type": "minecraft:flat",
    "settings": {
      "biome": "minecraft:the_void",
      "lakes": false,
      "features": false,
      "layers": [],
      "structure_overrides": []
    }
  }
}
"""

    /**
     * Writes one `data/redstonespecs/dimension/<sanitized>.json` per leaf folder under [root].
     * Returns the list of sanitized paths actually written (collisions deduped).
     *
     * @param saveDir absolute path to the world save directory (or the dedicated-server root).
     */
    fun writeForRoot(root: ManagedRoot, saveDir: Path): List<String> {
        val packDir = saveDir.resolve("datapacks/redstonespecs-managed")
        val dimDir = packDir.resolve("data/redstonespecs/dimension")
        dimDir.createDirectories()
        packDir.resolve("pack.mcmeta").writeText(PACK_MCMETA)

        val tree = ManagedFolderTree.scan(root)
        val written = mutableListOf<String>()
        val seenSanitized = mutableSetOf<String>()

        for (leaf in tree.leaves) {
            val sanitized = DimIdSanitizer.toPath(leaf.subpath)  // e.g. "managed/foo/bar"
            if (!seenSanitized.add(sanitized)) {
                LOGGER.warn(
                    "[ManagedDatapackWriter] sanitization collision on '{}' — skipping (already written)",
                    leaf.subpath,
                )
                continue
            }
            // Dim id `redstonespecs:managed/foo/bar` ⇔ file `dimension/managed/foo/bar.json`.
            val stemFile = dimDir.resolve("$sanitized.json")
            stemFile.parent?.createDirectories()
            stemFile.writeText(LEVEL_STEM_JSON)
            written.add(sanitized)
        }
        LOGGER.info(
            "[ManagedDatapackWriter] wrote {} per-folder dim JSON(s) to {}",
            written.size,
            dimDir,
        )
        return written
    }

    /** Removes any previously-written runtime datapack. Useful when switching roots. */
    fun clear(saveDir: Path) {
        val packDir = saveDir.resolve("datapacks/redstonespecs-managed")
        if (Files.exists(packDir)) {
            Files.walk(packDir).use { stream ->
                stream.sorted(Comparator.reverseOrder()).forEach(Files::delete)
            }
        }
    }
}
