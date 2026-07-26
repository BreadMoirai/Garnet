package com.breadmoirai.redstonespecs.project

import com.breadmoirai.redstonespecs.config.SharedSettings
import com.breadmoirai.redstonespecs.network.project.ProjectTreeSnapshotS2C
import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.context.CommandContext
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.network.chat.Component
import java.nio.file.Path

object ProjectCommand {
    fun register(dispatcher: CommandDispatcher<CommandSourceStack>) {
        dispatcher.register(
            Commands.literal("redstonespecs")
                .then(Commands.literal("project").executes(::open))
        )
    }

    private fun open(ctx: CommandContext<CommandSourceStack>): Int {
        val src = ctx.source
        val player = src.playerOrException
        val server = src.server
        val ctxPin = ProjectServerContext.get(server)
        val rootCfg = SharedSettings.projectRootPath
        val root = ctxPin?.root
            ?: if (rootCfg.isNotBlank()) ProjectRoot(Path.of(rootCfg).toAbsolutePath()) else null

        if (root == null) {
            src.sendSystemMessage(Component.literal("§cRedstone Project root not configured. Use the world-list 'Redstone Projects…' button (singleplayer) or set 'projectRootPath' in config (dedicated server)."))
            return 0
        }

        val current = ProjectSession.get(player.uuid)?.activeSubpath
        ServerPlayNetworking.send(player, ProjectTreeSnapshotS2C(
            root = scanFolder(root.path),
            currentSubpath = current,
        ))
        // Client receiver renders the project tree via the Compose dock Explorer panel.
        return Command.SINGLE_SUCCESS
    }
}
