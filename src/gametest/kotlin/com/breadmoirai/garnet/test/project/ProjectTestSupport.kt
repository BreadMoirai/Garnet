package com.breadmoirai.garnet.test.project

import com.breadmoirai.garnet.runner.RecordingDslEmitter
import net.minecraft.core.BlockPos
import net.minecraft.core.Vec3i
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.block.Blocks
import java.nio.file.Path
import kotlin.io.path.writeText

fun writeStub(folder: Path, name: String) {
    folder.resolve("$name.spec.kts").writeText(RecordingDslEmitter.emitStub(name))
}

/** Sets every block in `[origin, origin+size)` to AIR. */
fun clearCellVolume(level: ServerLevel, origin: BlockPos, size: Vec3i) {
    val air = Blocks.AIR.defaultBlockState()
    val end = origin.offset(size.x - 1, size.y - 1, size.z - 1)
    for (pos in BlockPos.betweenClosed(origin, end)) {
        level.setBlock(pos, air, 2)
    }
}
