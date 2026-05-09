package com.breadmoirai.redstonespecs.managed

import com.breadmoirai.redstonespecs.config.SharedSettings
import com.breadmoirai.redstonespecs.network.managed.ManagedLeafEntry
import com.breadmoirai.redstonespecs.network.managed.ManagedTreeSnapshotS2C
import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.context.CommandContext
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.network.chat.Component
import java.nio.file.Path

object ManagedCommand {
    fun register(dispatcher: CommandDispatcher<CommandSourceStack>) {
        dispatcher.register(
            Commands.literal("redstonespecs")
                .then(Commands.literal("managed").executes(::open))
        )
    }

    private fun open(ctx: CommandContext<CommandSourceStack>): Int {
        val src = ctx.source
        val player = src.playerOrException
        val server = src.server
        val ctxPin = ManagedServerContext.get(server)
        val rootCfg = SharedSettings.managedRootPath
        val root = ctxPin?.root
            ?: if (rootCfg.isNotBlank()) ManagedRoot(Path.of(rootCfg).toAbsolutePath()) else null

        if (root == null) {
            src.sendSystemMessage(Component.literal("§cManaged root not configured. Use the world-list 'Managed Specs…' button (singleplayer) or set 'managedRootPath' in config (dedicated server)."))
            return 0
        }

        val tree = ManagedFolderTree.scan(root)
        val current = ManagedSession.get(player.uuid)?.activeSubpath
        ServerPlayNetworking.send(player, ManagedTreeSnapshotS2C(
            leaves = tree.leaves.map { ManagedLeafEntry(it.subpath, it.specFiles.size) },
            intermediates = tree.intermediates.toList(),
            currentSubpath = current,
        ))
        // Client receiver opens ManagedScreen on receipt.
        return Command.SINGLE_SUCCESS
    }
}
