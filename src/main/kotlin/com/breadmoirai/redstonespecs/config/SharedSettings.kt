package com.breadmoirai.redstonespecs.config

object SharedSettings {
    var specSaveDir: String = "redstonespecs"

    var managedCellSize: net.minecraft.core.Vec3i = net.minecraft.core.Vec3i(32, 32, 32)
    var managedCellGap: Int = 4
    var managedRowMax: Int = 8
    var managedGridYBase: Int = 64
    var managedRootPath: String = ""
}
