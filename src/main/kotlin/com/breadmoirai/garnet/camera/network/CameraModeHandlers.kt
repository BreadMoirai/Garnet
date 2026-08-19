package com.breadmoirai.garnet.camera.network

import com.breadmoirai.garnet.editor.network.EditorHandlerSupport
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.server.permissions.PermissionSet
import net.minecraft.world.level.GameType

object CameraModeHandlers {

    /**
     * Flip the requesting player into spectator, or back out of it.
     *
     * **Goes through the vanilla `/gamemode` command rather than calling `setGameMode` directly.**
     * The command is the path every other gamemode change in the game takes, so routing through it
     * means camera mode inherits the whole of it for free and stays consistent with it forever:
     * `GameModeCommand`'s own argument handling, the `sendGameModeFeedback` message, and anything a
     * server owner has hooked onto command execution. A direct `setGameMode` bypasses all of that
     * and would silently drift the moment vanilla adds a step.
     *
     * **Restore deliberately reads vanilla's own previous-gamemode field** rather than remembering
     * one here. That field is what `/gamemode` and back already uses, it survives a relog, and
     * leaning on it means there is no per-player map of ours to leak, to get out of sync with a
     * gamemode changed by an operator mid-orbit, or to strand a player in spectator if the client
     * never sends the matching leave.
     */
    fun handleCameraMode(server: MinecraftServer, player: ServerPlayer, payload: CameraModeC2S) {
        val target = if (payload.enter) {
            if (player.gameMode() == GameType.SPECTATOR) return
            GameType.SPECTATOR
        } else {
            if (player.gameMode() != GameType.SPECTATOR) return
            player.gameMode.previousGameModeForPlayer ?: server.defaultGameType
        }

        // ELEVATED ON PURPOSE. `/gamemode` requires Permissions.COMMANDS_GAMEMASTER, which an
        // ordinary player does not have, so running as the player's own source stack would refuse
        // every request. Camera mode is a first-class feature of this mod rather than an operator
        // tool, so the mod grants it — the elevation is scoped to this one command string, which is
        // built here from a GameType and never from anything the client sent.
        val source = player.createCommandSourceStack()
            .withPermission(PermissionSet.ALL_PERMISSIONS)
            .withSuppressedOutput()
        server.commands.performPrefixedCommand(source, "gamemode ${target.getName()}")

        // performPrefixedCommand returns void, so success is confirmed by reading the state back
        // rather than by trusting the call.
        if (player.gameMode() != target) {
            val verb = if (payload.enter) "enter" else "leave"
            EditorHandlerSupport.fail(player, "could not $verb camera mode")
        }
    }
}
