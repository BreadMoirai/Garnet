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

object ModConfig {
    private val configFile get() = FabricLoader.getInstance().configDir.resolve("redstonespecs.json").toFile()

    var autoSaveOnExit: Boolean = false

    fun load() {
        if (!configFile.exists()) return
        runCatching {
            val json = JsonParser.parseReader(configFile.reader()) as? JsonObject ?: return
            autoSaveOnExit = json.get("autoSaveOnExit")?.asBoolean ?: false
        }
    }

    fun save() {
        val json = JsonObject()
        json.addProperty("autoSaveOnExit", autoSaveOnExit)
        configFile.writeText(json.toString())
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
                                Component.literal("Automatically save changes when closing SpecEditorScreen without pressing Save")
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
