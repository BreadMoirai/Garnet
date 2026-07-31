package com.breadmoirai.garnet.config

object SharedSettings {
    var projectCellSize: net.minecraft.core.Vec3i = net.minecraft.core.Vec3i(32, 32, 32)
    var projectCellGap: Int = 4
    var projectRowMax: Int = 8
    var projectGridYBase: Int = 64
    var projectRootPath: String = ""

    /** Side length, in chunks, of a standalone structure's build region (full world height). */
    var structureRegionChunks: Int = 9
}
