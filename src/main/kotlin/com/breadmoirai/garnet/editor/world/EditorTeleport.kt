package com.breadmoirai.garnet.editor.world

import com.breadmoirai.garnet.config.SharedSettings
import com.breadmoirai.garnet.editor.data.EditorSession
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.Relative

object EditorTeleport {
    /**
     * Teleports `player` to the spawn position above `subpath`'s region and marks `subpath`
     * as the player's active focus. Returns false if the region has not been assigned (e.g.
     * placeAll has not run, or the subpath is unknown).
     */
    fun toFolder(server: MinecraftServer, player: ServerPlayer, subpath: String): Boolean {
        val registry = EditorDimRegistry.of(server)
        val region = registry.regionOriginOf(subpath) ?: return false
        val level = registry.projectLevel()
        val yBase = SharedSettings.projectGridYBase
        player.teleportTo(
            level,
            region.x + 0.5, (yBase + 2).toDouble(), region.z + 0.5,
            emptySet<Relative>(), player.yRot, player.xRot, true,
        )
        EditorSession.setActive(player.uuid, subpath)
        return true
    }
}
