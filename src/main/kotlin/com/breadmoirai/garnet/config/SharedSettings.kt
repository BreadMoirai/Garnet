package com.breadmoirai.garnet.config

object SharedSettings {
    var projectCellSize: net.minecraft.core.Vec3i = net.minecraft.core.Vec3i(32, 32, 32)
    var projectCellGap: Int = 4
    var projectRowMax: Int = 8
    var projectGridYBase: Int = 64
    var projectRootPath: String = ""

    /** Side length, in chunks, of a standalone structure's build region (full world height). */
    var structureRegionChunks: Int = 9

    // === Auto-save ===

    /** When false, structures commit only via SaveStructureC2S and the world-save/stop backstops. */
    var autoSaveEnabled: Boolean = true

    /** Ticks of quiet after the last edit before a dirty structure commits. 20 ticks = 1s. */
    var autoSaveDebounceTicks: Int = 20

    /**
     * Ticks a structure may stay continuously dirty before committing regardless of the debounce,
     * so an uninterrupted build session still checkpoints. 600 ticks = 30s.
     */
    var autoSaveMaxDirtyTicks: Int = 600

    // === Local history ===

    /** When false, commits still happen but no revisions are recorded. */
    var localHistoryEnabled: Boolean = true

    /** Revisions older than this many days are pruned on write. Matches JetBrains' default. */
    var localHistoryDays: Int = 5

    /** Hard cap on revisions kept per structure, applied after the age cutoff. */
    var localHistoryMaxRevisions: Int = 100

    /** Blank means `<gameDir>/.garnet/local-history`. */
    var localHistoryDir: String = ""

    // === Default platform for new structures ===

    /**
     * Block a newly created structure's platform is made of. An unknown or malformed id logs a
     * warning and falls back to `minecraft:smooth_stone` rather than blocking the create.
     */
    var newStructurePlatformBlock: String = "minecraft:smooth_stone"

    /** Platform extent along X. Zero or negative creates the empty structure instead. */
    var newStructurePlatformWidth: Int = 3

    /** Platform extent along Z. Zero or negative creates the empty structure instead. */
    var newStructurePlatformDepth: Int = 3
}
