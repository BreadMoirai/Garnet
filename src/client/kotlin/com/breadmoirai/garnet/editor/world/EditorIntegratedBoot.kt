package com.breadmoirai.garnet.editor.world

import com.breadmoirai.garnet.editor.explorer.data.EditorRoot
import com.breadmoirai.garnet.editor.world.EditorServerContext
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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

private val LOGGER = LoggerFactory.getLogger("Garnet")

object EditorIntegratedBoot {
    /**
     * Project root to pin onto the next integrated server that starts. No caller currently sets
     * it — the main-menu button boots the root-agnostic [bootWorkspace] — so the SERVER_STARTING
     * listener below reads it and stays a no-op while it remains null. The pinning path (set
     * pendingRoot, then open a per-root save) is retained for programmatic use.
     *
     * On SERVER_STARTING a pinned root sets [EditorServerContext]; on SERVER_STARTED the common
     * listener in `garnet` calls `EditorDimLifecycle.placeAll` for whatever context was
     * pinned. The listener is registered exactly once (Fabric's `Event<T>` has no `unregister`).
     */
    private val pendingRoot = AtomicReference<EditorRoot?>()
    private val initialized = AtomicBoolean(false)

    /** Fixed workspace save the main-menu button boots into; project folders are loaded/unloaded in-world. */
    private const val WORKSPACE_SAVE = "garnet-workspace"

    /** Boots (opens or creates) the shared flat-void workspace world, without pinning a project root. */
    fun bootWorkspace() {
        ensureListenersRegistered()
        openOrCreateWorld(WORKSPACE_SAVE)
    }

    private fun ensureListenersRegistered() {
        if (!initialized.compareAndSet(false, true)) return
        ServerLifecycleEvents.SERVER_STARTING.register(ServerLifecycleEvents.ServerStarting { server ->
            val root = pendingRoot.getAndSet(null) ?: return@ServerStarting
            EditorServerContext.set(server, EditorServerContext(root))
            LOGGER.info("[EditorIntegratedBoot] pinned root '{}' on SERVER_STARTING", root.path)
        })
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
        val onCancel = Runnable { mc.gui.setScreen(null) }

        val exists = try {
            mc.levelSource.levelExists(saveName)
        } catch (e: Exception) {
            LOGGER.warn("[EditorIntegratedBoot] levelExists check failed for '{}': {}", saveName, e.message, e)
            false
        }

        if (exists) {
            LOGGER.info("[EditorIntegratedBoot] opening existing save '{}'", saveName)
            flows.openWorld(saveName, onCancel)
            return
        }

        LOGGER.info("[EditorIntegratedBoot] creating fresh flat-void save '{}'", saveName)
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
            // MC 26.2 removed WorldPresets.createFlatWorldDimensions; build the FLAT preset's
            // dimensions straight from the registry (same shape createNormalWorldDimensions uses),
            // giving us the nether/end stems before we override the overworld with the void source.
            val baseDimensions = provider.lookupOrThrow(Registries.WORLD_PRESET)
                .getOrThrow(WorldPresets.FLAT).value().createWorldDimensions()
            val voidPreset = provider.lookupOrThrow(Registries.FLAT_LEVEL_GENERATOR_PRESET)
                .getOrThrow(FlatLevelGeneratorPresets.THE_VOID)
                .value()
            baseDimensions.replaceOverworldGenerator(provider, FlatLevelSource(voidPreset.settings()))
        }

        // `createFreshLevel`'s parentScreen is shown if datapack loading throws; pass current
        // screen if any, else a fresh empty TitleScreen-equivalent (null is not allowed).
        val parentScreen = mc.gui.screen() ?: net.minecraft.client.gui.screens.TitleScreen()
        flows.createFreshLevel(saveName, levelSettings, worldOptions, dimensionsProvider, parentScreen)
    }
}
