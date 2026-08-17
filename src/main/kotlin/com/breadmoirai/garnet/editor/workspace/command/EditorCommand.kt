package com.breadmoirai.garnet.editor.workspace.command

import com.breadmoirai.garnet.core.config.SharedSettings
import com.breadmoirai.garnet.editor.explorer.data.EditorRoot
import com.breadmoirai.garnet.editor.explorer.data.EditorSession
import com.breadmoirai.garnet.editor.explorer.data.scanFolder
import com.breadmoirai.garnet.editor.network.EditorTreeSnapshotS2C
import com.breadmoirai.garnet.editor.workspace.world.EditorServerContext
import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.context.CommandContext
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.network.chat.Component
import java.nio.file.Path

object EditorCommand {
    fun register(dispatcher: CommandDispatcher<CommandSourceStack>) {
        dispatcher.register(
            Commands.literal("garnet")
                .then(Commands.literal("editor").executes(::open))
        )
    }

    private fun open(ctx: CommandContext<CommandSourceStack>): Int {
        val src = ctx.source
        val player = src.playerOrException
        val server = src.server
        val ctxPin = EditorServerContext.get(server)
        val rootCfg = SharedSettings.projectRootPath
        val root = ctxPin?.root
            ?: if (rootCfg.isNotBlank()) EditorRoot(Path.of(rootCfg).toAbsolutePath()) else null

        if (root == null) {
            src.sendSystemMessage(Component.literal("§cProject root not configured. Use the world-list 'Redstone Projects…' button (singleplayer) or set 'projectRootPath' in config (dedicated server)."))
            return 0
        }

        val current = EditorSession.get(player.uuid)?.activeSubpath
        ServerPlayNetworking.send(player, EditorTreeSnapshotS2C(
            root = scanFolder(root.path),
            currentSubpath = current,
        ))
        // Client receiver renders the project tree via the Compose dock Explorer panel.
        return Command.SINGLE_SUCCESS
    }
}
