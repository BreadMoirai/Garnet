package com.breadmoirai.garnet.editor.world

import com.breadmoirai.garnet.config.SharedSettings
import com.breadmoirai.garnet.editor.data.EditorRoot
import net.minecraft.server.MinecraftServer
import java.nio.file.Path

object EditorRootResolver {

    /**
     * The active managed root: the loaded world's, else a pinned server context's, else the
     * configured path.
     */
    fun rootFor(server: MinecraftServer): EditorRoot? {
        val world = EditorWorld.get(server)
        if (world != null) return world.root
        val ctx = EditorServerContext.get(server)
        if (ctx != null) return ctx.root
        val cfg = SharedSettings.projectRootPath
        return if (cfg.isNotBlank()) EditorRoot(Path.of(cfg).toAbsolutePath()) else null
    }
}
