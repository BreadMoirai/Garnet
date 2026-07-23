package com.breadmoirai.redstonespecs.config

object SharedSettings {
    var specSaveDir: String = "redstonespecs"

    var projectCellSize: net.minecraft.core.Vec3i = net.minecraft.core.Vec3i(32, 32, 32)
    var projectCellGap: Int = 4
    var projectRowMax: Int = 8
    var projectGridYBase: Int = 64
    var projectRootPath: String = ""
}
