package com.breadmoirai.redstonespecs.client.config

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.isxander.yacl3.api.ConfigCategory
import dev.isxander.yacl3.api.Option
import dev.isxander.yacl3.api.OptionDescription
import dev.isxander.yacl3.api.YetAnotherConfigLib
import dev.isxander.yacl3.api.controller.TickBoxControllerBuilder
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import org.slf4j.LoggerFactory

private val LOGGER = LoggerFactory.getLogger("Redstone Specs")

object ModConfig {
    private val configFile = FabricLoader.getInstance().configDir.resolve("redstonespecs.json").toFile()

    var autoSaveOnExit: Boolean = false

    fun load() {
        if (!configFile.exists()) return
        runCatching {
            configFile.reader().use { reader ->
                val json = JsonParser.parseReader(reader) as? JsonObject ?: return@use
                autoSaveOnExit = json.get("autoSaveOnExit")?.asBoolean ?: false
            }
        }.onFailure { e ->
            LOGGER.warn("Failed to load ModConfig from {}", configFile.absolutePath, e)
        }
    }

    fun save() {
        configFile.parentFile?.mkdirs()
        val json = JsonObject()
        json.addProperty("autoSaveOnExit", autoSaveOnExit)
        runCatching {
            configFile.writeText(json.toString())
        }.onFailure { e ->
            LOGGER.error("Failed to save ModConfig to {}", configFile.absolutePath, e)
        }
    }

    fun createScreen(parent: Screen): Screen = YetAnotherConfigLib.createBuilder()
        .title(Component.literal("RedstoneSpecs Config"))
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
                .build()
        )
        .save(::save)
        .build()
        .generateScreen(parent)
}
