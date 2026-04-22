package com.breadmoirai.redstonespecs.client.config

import com.breadmoirai.redstonespecs.config.DevLevel
import com.breadmoirai.redstonespecs.config.SharedSettings
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.isxander.yacl3.api.ConfigCategory
import dev.isxander.yacl3.api.Option
import dev.isxander.yacl3.api.OptionDescription
import dev.isxander.yacl3.api.YetAnotherConfigLib
import dev.isxander.yacl3.api.controller.CyclingListControllerBuilder
import dev.isxander.yacl3.api.controller.TickBoxControllerBuilder
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import org.slf4j.LoggerFactory

private val LOGGER = LoggerFactory.getLogger("Redstone Specs")

object ModConfig {
    private val configFile = FabricLoader.getInstance().configDir.resolve("redstonespecs.json").toFile()

    var autoSaveOnExit: Boolean = false
    var devLevel: DevLevel = DevLevel.STANDARD

    fun load() {
        if (!configFile.exists()) return
        runCatching {
            configFile.reader().use { reader ->
                val json = JsonParser.parseReader(reader) as? JsonObject ?: return@use
                autoSaveOnExit = json.get("autoSaveOnExit")?.asBoolean ?: false
                devLevel = json.get("devLevel")?.asString
                    ?.let { runCatching { DevLevel.valueOf(it) }.getOrNull() }
                    ?: DevLevel.STANDARD
            }
        }.onFailure { e ->
            LOGGER.warn("Failed to load ModConfig from {}", configFile.absolutePath, e)
        }
        SharedSettings.devLevel = devLevel
    }

    fun save() {
        configFile.parentFile?.mkdirs()
        val json = JsonObject()
        json.addProperty("autoSaveOnExit", autoSaveOnExit)
        json.addProperty("devLevel", devLevel.name)
        runCatching {
            configFile.writeText(json.toString())
        }.onFailure { e ->
            LOGGER.error("Failed to save ModConfig to {}", configFile.absolutePath, e)
        }
        SharedSettings.devLevel = devLevel
    }

    fun createScreen(parent: Screen): Screen = YetAnotherConfigLib.createBuilder()
        .title(Component.literal("RedstoneSpecs Config"))
        .category(
            ConfigCategory.createBuilder()
                .name(Component.literal("General"))
                .option(
                    Option.createBuilder<String>()
                        .name(Component.literal("Redstone Developer Level"))
                        .description(
                            OptionDescription.of(
                                Component.literal(
                                    "Standard: hides subtick phases and update ordering. " +
                                    "Advanced: shows all timing details."
                                )
                            )
                        )
                        .binding(
                            DevLevel.STANDARD.name,
                            { devLevel.name },
                            { devLevel = DevLevel.valueOf(it) }
                        )
                        .controller { opt ->
                            CyclingListControllerBuilder.create(opt)
                                .values(DevLevel.entries.map { it.name })
                                .formatValue { name ->
                                    Component.literal(name.lowercase().replaceFirstChar { it.uppercase() })
                                }
                        }
                        .build()
                )
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
