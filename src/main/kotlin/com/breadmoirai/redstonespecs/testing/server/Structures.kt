package com.breadmoirai.redstonespecs.testing.server

import com.breadmoirai.redstonespecs.testing.core.McDispatchers
import kotlinx.coroutines.withContext
import net.minecraft.core.BlockPos
import net.minecraft.resources.Identifier
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.Rotation
import net.minecraft.world.level.levelgen.structure.BoundingBox
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings
import java.util.concurrent.ConcurrentHashMap

private const val GRID_Y = 64
private const val SLOT_SIZE = 32
private const val MAX_SLOTS = 16

/** Per-test handle to a spawned structure copy. */
class StructureHandle(
    val origin: BlockPos,
    val bounds: BoundingBox,
    private val server: MinecraftServer,
    private val grid: StructureGrid,
    private val slotIndex: Int,
) {
    fun absolute(relative: BlockPos): BlockPos =
        origin.offset(relative.x, relative.y, relative.z)

    fun signalAt(relative: BlockPos): Int {
        val pos = absolute(relative)
        return server.overworld().getBestNeighborSignal(pos)
    }

    suspend fun teardown() {
        withContext(McDispatchers.Server) {
            val level = server.overworld()
            BlockPos.betweenClosed(
                bounds.minX(), bounds.minY(), bounds.minZ(),
                bounds.maxX(), bounds.maxY(), bounds.maxZ(),
            ).forEach { p ->
                level.setBlock(p, Blocks.AIR.defaultBlockState(), 2)
            }
            grid.releaseSlot(slotIndex)
        }
    }
}

/** Per-server allocator for structure spawn regions. */
class StructureGrid(private val server: MinecraftServer) {
    private val freeSlots: ArrayDeque<Int> = ArrayDeque((0 until MAX_SLOTS).toList())

    @Synchronized
    private fun acquireSlot(): Int {
        check(freeSlots.isNotEmpty()) { "StructureGrid exhausted: no free slots (max $MAX_SLOTS)" }
        return freeSlots.removeFirst()
    }

    @Synchronized
    internal fun releaseSlot(index: Int) {
        if (index !in 0 until MAX_SLOTS) return
        if (freeSlots.contains(index)) return
        freeSlots.addFirst(index)
    }

    /** Must be called on the server thread. */
    fun spawn(id: Identifier): StructureHandle {
        require(server.isSameThread) { "StructureGrid.spawn must be called on the server thread" }
        val level: ServerLevel = server.overworld()
        val template = server.getStructureManager().get(id).orElseThrow {
            IllegalArgumentException("Structure not found: $id")
        }
        val slot = acquireSlot()
        return try {
            val origin = BlockPos(slot * SLOT_SIZE, GRID_Y, 0)
            val settings = StructurePlaceSettings().setRotation(Rotation.NONE)
            template.placeInWorld(level, origin, origin, settings, level.getRandom(), 2)
            val size = template.getSize()
            val bounds = BoundingBox(
                origin.x, origin.y, origin.z,
                origin.x + size.x - 1, origin.y + size.y - 1, origin.z + size.z - 1,
            )
            StructureHandle(origin, bounds, server, this, slot)
        } catch (t: Throwable) {
            releaseSlot(slot)
            throw t
        }
    }

    companion object {
        private val grids = ConcurrentHashMap<MinecraftServer, StructureGrid>()
        fun forServer(server: MinecraftServer): StructureGrid =
            grids.computeIfAbsent(server) { StructureGrid(it) }
    }
}

/** Spawns the structure named [id] into a fresh grid slot. */
suspend fun spawnStructure(id: Identifier): StructureHandle =
    onServer { StructureGrid.forServer(this).spawn(id) }
