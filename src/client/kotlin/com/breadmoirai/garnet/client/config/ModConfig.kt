package com.breadmoirai.garnet.client.config

import com.breadmoirai.garnet.config.SharedSettings
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.isxander.yacl3.api.ConfigCategory
import dev.isxander.yacl3.api.Option
import dev.isxander.yacl3.api.OptionDescription
import dev.isxander.yacl3.api.YetAnotherConfigLib
import dev.isxander.yacl3.api.controller.StringControllerBuilder
import dev.isxander.yacl3.api.controller.TickBoxControllerBuilder
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import org.slf4j.LoggerFactory

private val LOGGER = LoggerFactory.getLogger("Garnet")

object ModConfig {
    private val configFile = FabricLoader.getInstance().configDir.resolve("garnet.json").toFile()

    var autoSaveOnExit: Boolean = false
    var specSaveDir: String = "garnet"
    var projectRootPath: String = ""

    fun load() {
        if (!configFile.exists()) return
        runCatching {
            configFile.reader().use { reader ->
                val json = JsonParser.parseReader(reader) as? JsonObject ?: return@use
                autoSaveOnExit = json.get("autoSaveOnExit")?.asBoolean ?: false
                specSaveDir = json.get("specSaveDir")?.asString ?: "garnet"
                projectRootPath = json.get("projectRootPath")?.asString ?: ""
            }
        }.onFailure { e ->
            LOGGER.warn("Failed to load ModConfig from {}", configFile.absolutePath, e)
        }
        SharedSettings.specSaveDir = specSaveDir
        SharedSettings.projectRootPath = projectRootPath
    }

    fun save() {
        configFile.parentFile?.mkdirs()
        val json = JsonObject()
        json.addProperty("autoSaveOnExit", autoSaveOnExit)
        json.addProperty("specSaveDir", specSaveDir)
        json.addProperty("projectRootPath", projectRootPath)
        runCatching {
            configFile.writeText(json.toString())
        }.onFailure { e ->
            LOGGER.error("Failed to save ModConfig to {}", configFile.absolutePath, e)
        }
        SharedSettings.specSaveDir = specSaveDir
        SharedSettings.projectRootPath = projectRootPath
    }

    fun createScreen(parent: Screen): Screen = YetAnotherConfigLib.createBuilder()
        .title(Component.literal("garnet Config"))
        .category(
            ConfigCategory.createBuilder()
                .name(Component.literal("General"))
                .option(
                    Option.createBuilder<Boolean>()
                        .name(Component.literal("Auto-save on exit"))
                        .description(
                            OptionDescription.of(
                                Component.literal("Automatically save changes when closing the spec editor without pressing Save")
                            )
                        )
                        .binding(false, { autoSaveOnExit }, { autoSaveOnExit = it })
                        .controller(TickBoxControllerBuilder::create)
                        .build()
                )
                .option(
                    Option.createBuilder<String>()
                        .name(Component.literal("Spec Save Directory"))
                        .description(
                            OptionDescription.of(
                                Component.literal("Folder (relative to world folder) where .json and .nbt spec files are saved.")
                            )
                        )
                        .binding("garnet", { specSaveDir }, { specSaveDir = it })
                        .controller(StringControllerBuilder::create)
                        .build()
                )
                .build()
        )
        .save(::save)
        .build()
        .generateScreen(parent)
}
