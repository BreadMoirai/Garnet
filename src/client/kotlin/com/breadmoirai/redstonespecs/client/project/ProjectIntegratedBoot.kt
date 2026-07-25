package com.breadmoirai.redstonespecs.client.project

import com.breadmoirai.redstonespecs.project.ProjectRoot
import com.breadmoirai.redstonespecs.project.ProjectSaveNaming
import com.breadmoirai.redstonespecs.project.ProjectServerContext
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.minecraft.client.Minecraft
import net.minecraft.core.registries.Registries
import net.minecraft.world.Difficulty
import net.minecraft.world.level.GameType
import net.minecraft.world.level.LevelSettings
import net.minecraft.world.level.WorldDataConfiguration
import net.minecraft.world.level.levelgen.FlatLevelSource
import net.minecraft.world.level.levelgen.WorldDimensions
import net.minecraft.world.level.levelgen.WorldOptions
import net.minecraft.world.level.levelgen.flat.FlatLevelGeneratorPresets
import net.minecraft.world.level.levelgen.presets.WorldPresets
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

private val LOGGER = LoggerFactory.getLogger("Redstone Specs")

object ProjectIntegratedBoot {
    /**
     * Boots an integrated server pinned to `rootPath`:
     *  - Save name: `project-<root-tail>-<hash>` (see [ProjectSaveNaming]). If the save exists,
     *    opens it; else creates a fresh flat-void singleplayer world with that name.
     *  - On SERVER_STARTING: pins [ProjectServerContext] from the pending root.
     *  - On SERVER_STARTED: handled by the common listener in `Redstonespecs`, which calls
     *    [ProjectDimLifecycle.placeAll] for whatever context was pinned.
     *
     * The SERVER_STARTING listener is registered exactly once (Fabric's `Event<T>` has no
     * `unregister`); subsequent `boot` calls just swap in a new pending root. The listener is
     * a no-op when no root is pending.
     */
    private val pendingRoot = AtomicReference<ProjectRoot?>()
    private val initialized = AtomicBoolean(false)

    /** Fixed workspace save the main-menu button boots into; project folders are loaded/unloaded in-world. */
    private const val WORKSPACE_SAVE = "redstonespecs-workspace"

    /** Boots (opens or creates) the shared flat-void workspace world, without pinning a project root. */
    fun bootWorkspace() {
        ensureListenersRegistered()
        openOrCreateWorld(WORKSPACE_SAVE)
    }

    private fun ensureListenersRegistered() {
        if (!initialized.compareAndSet(false, true)) return
        ServerLifecycleEvents.SERVER_STARTING.register(ServerLifecycleEvents.ServerStarting { server ->
            val root = pendingRoot.getAndSet(null) ?: return@ServerStarting
            ProjectServerContext.set(server, ProjectServerContext(root))
            LOGGER.info("[ProjectIntegratedBoot] pinned root '{}' on SERVER_STARTING", root.path)
        })
    }

    fun boot(rootPath: Path) {
        require(rootPath.isAbsolute) { "rootPath must be absolute: $rootPath" }
        val root = ProjectRoot(rootPath)
        ensureListenersRegistered()
        pendingRoot.set(root)
        val saveName = ProjectSaveNaming.saveName(rootPath)
        openOrCreateWorld(saveName)
    }

    /**
     * Opens singleplayer save `saveName` if it exists, otherwise creates it as a fresh
     * flat-void world.
     *
     * MC 26.1 entry points (verified against decompiled sources):
     *  - [Minecraft.getLevelSource].levelExists(String) → existence check.
     *  - [Minecraft.createWorldOpenFlows].openWorld(String, Runnable) → opens existing.
     *  - [WorldOpenFlows.createFreshLevel] → creates new from settings + dimensions provider.
     *
     * Flat-void dimensions: we reuse FLAT preset's nether/end stems via
     * [WorldPresets.createFlatWorldDimensions], then replace the overworld stem with one
     * built from the THE_VOID `FlatLevelGeneratorPreset` (looked up in the registry available
     * during world-data load via the `Provider` passed to the dimensions function).
     */
    private fun openOrCreateWorld(saveName: String) {
        val mc = Minecraft.getInstance()
        val flows = mc.createWorldOpenFlows()
        val onCancel = Runnable { mc.setScreen(null) }

        val exists = try {
            mc.levelSource.levelExists(saveName)
        } catch (e: Exception) {
            LOGGER.warn("[ProjectIntegratedBoot] levelExists check failed for '{}': {}", saveName, e.message, e)
            false
        }

        if (exists) {
            LOGGER.info("[ProjectIntegratedBoot] opening existing save '{}'", saveName)
            flows.openWorld(saveName, onCancel)
            return
        }

        LOGGER.info("[ProjectIntegratedBoot] creating fresh flat-void save '{}'", saveName)
        val levelSettings = LevelSettings(
            saveName,
            GameType.CREATIVE,
            LevelSettings.DifficultySettings(Difficulty.PEACEFUL, /*hardcore=*/ false, /*locked=*/ false),
            /*allowCommands=*/ true,
            WorldDataConfiguration.DEFAULT,
        )
        val worldOptions = WorldOptions(WorldOptions.randomSeed(), /*generateStructures=*/ false, /*generateBonusChest=*/ false)

        val dimensionsProvider = java.util.function.Function<net.minecraft.core.HolderLookup.Provider, WorldDimensions> { provider ->
            // Start from the FLAT preset (gives us nether/end stems), then override the
            // overworld stem with a FlatLevelSource built from THE_VOID's preset settings.
            val baseDimensions = WorldPresets.createFlatWorldDimensions(provider)
            val voidPreset = provider.lookupOrThrow(Registries.FLAT_LEVEL_GENERATOR_PRESET)
                .getOrThrow(FlatLevelGeneratorPresets.THE_VOID)
                .value()
            baseDimensions.replaceOverworldGenerator(provider, FlatLevelSource(voidPreset.settings()))
        }

        // `createFreshLevel`'s parentScreen is shown if datapack loading throws; pass current
        // screen if any, else a fresh empty TitleScreen-equivalent (null is not allowed).
        val parentScreen = mc.screen ?: net.minecraft.client.gui.screens.TitleScreen()
        flows.createFreshLevel(saveName, levelSettings, worldOptions, dimensionsProvider, parentScreen)
    }
}
