package com.breadmoirai.garnet.camera.network

import com.breadmoirai.garnet.editor.network.EditorHandlerSupport
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.server.permissions.PermissionSet
import net.minecraft.world.level.GameType
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object CameraModeHandlers {

    /**
     * The players this handler has *itself* put into spectator for camera mode, by UUID.
     *
     * **This is authority state, not a copy of vanilla's previous-gamemode field — do not delete it
     * as a violation of the "store no gamemode state of our own" pillar.** The two are different
     * things. Restore still reads `previousGameModeForPlayer` and nothing here ever records a
     * `GameType`; this set records only the boolean fact *"the mod granted camera mode to this
     * player"*, which vanilla has no field for and cannot answer.
     *
     * Without it the leave path is a privilege escalation. Enter no-ops when the player is already
     * spectating (nothing to do), so an operator who puts a player into spectator for moderation
     * leaves no trace to distinguish that from camera mode. The player could then send
     * `CameraModeC2S(enter = true)` — a server-side no-op — followed by `CameraModeC2S(enter =
     * false)`, and the leave path would elevate itself to `ALL_PERMISSIONS` and hand them back
     * `previousGameModeForPlayer`, quite possibly `CREATIVE`. Gating leave on a grant this handler
     * wrote itself means a spectator state the mod did not cause can never be undone through this
     * payload.
     *
     * In memory only, cleared on disconnect by [handleDisconnect], so it can neither leak across
     * sessions nor be resurrected by a relog.
     */
    private val grantedCameraMode: MutableSet<UUID> = ConcurrentHashMap.newKeySet()

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
     *
     * **Leaving is gated on [grantedCameraMode]** — see that field for why the gate is not optional.
     */
    fun handleCameraMode(server: MinecraftServer, player: ServerPlayer, payload: CameraModeC2S) {
        val target = if (payload.enter) {
            if (player.gameMode() == GameType.SPECTATOR) return
            GameType.SPECTATOR
        } else {
            // Only a spectator state THIS handler created may be undone here.
            if (player.uuid !in grantedCameraMode) return
            if (player.gameMode() != GameType.SPECTATOR) {
                // Something else (an operator, a plugin) moved them out from under camera mode.
                // There is nothing to restore, and the grant is stale — drop it.
                grantedCameraMode.remove(player.uuid)
                return
            }
            player.gameMode.previousGameModeForPlayer ?: server.defaultGameType
        }

        applyGameMode(server, player, target)

        // performPrefixedCommand returns void, so success is confirmed by reading the state back
        // rather than by trusting the call.
        if (player.gameMode() != target) {
            val verb = if (payload.enter) "enter" else "leave"
            EditorHandlerSupport.fail(player, "could not $verb camera mode")
            return
        }
        // The grant is written only once the transition has actually landed, and cleared only once
        // the restore has: a refused leave keeps the grant so [handleDisconnect] still has the
        // authority to finish the job.
        if (payload.enter) grantedCameraMode.add(player.uuid) else grantedCameraMode.remove(player.uuid)
    }

    /**
     * Restore a player who logs out still holding a camera-mode grant, and drop the grant either way.
     *
     * Registered from `Garnet.onInitialize`'s `ServerPlayConnectionEvents.DISCONNECT` block, beside
     * the other per-player server state cleared there. The client's own `DISCONNECT` hook cannot
     * cover this case: a client that crashes, is killed, or loses its connection mid-orbit never
     * runs anything, and vanilla persists spectator across a relog — so without this the player is
     * stranded in spectator until an operator notices. Fabric fires this event from
     * `Connection.handleDisconnection`, *before* `ServerGamePacketListenerImpl.onDisconnect` runs
     * `PlayerList.remove`, so the gamemode written here is still the one saved to disk.
     *
     * The grant check is what makes this safe to run on every disconnect: a player spectating for
     * any reason the mod did not cause holds no grant and is left exactly as they are.
     */
    fun handleDisconnect(server: MinecraftServer, player: ServerPlayer) {
        if (!grantedCameraMode.remove(player.uuid)) return
        if (player.gameMode() != GameType.SPECTATOR) return
        applyGameMode(server, player, player.gameMode.previousGameModeForPlayer ?: server.defaultGameType)
    }

    /**
     * Run `/gamemode <target>` for [player] on an elevated source.
     *
     * ELEVATED ON PURPOSE. `/gamemode` requires Permissions.COMMANDS_GAMEMASTER, which an ordinary
     * player does not have, so running as the player's own source stack would refuse every request.
     * Camera mode is a first-class feature of this mod rather than an operator tool, so the mod
     * grants it — the elevation is scoped to this one command string, which is built here from a
     * [GameType] and never from anything the client sent.
     */
    private fun applyGameMode(server: MinecraftServer, player: ServerPlayer, target: GameType) {
        val source = player.createCommandSourceStack()
            .withPermission(PermissionSet.ALL_PERMISSIONS)
            .withSuppressedOutput()
        server.commands.performPrefixedCommand(source, "gamemode ${target.getName()}")
    }
}
