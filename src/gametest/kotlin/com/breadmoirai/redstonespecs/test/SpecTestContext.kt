package com.breadmoirai.redstonespecs.test

import com.breadmoirai.redstonespecs.block.SpecOriginBlockEntity
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.components.EditBox
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.InteractionHand
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.Vec3
import org.apache.commons.lang3.function.FailableConsumer
import org.lwjgl.glfw.GLFW
import java.nio.file.Path

@Suppress("UnstableApiUsage")
class SpecTestContext(
    val context: ClientGameTestContext,
    val world: TestSingleplayerContext,
) {

    fun runCommand(cmd: String) = world.getServer().runCommand(cmd)
    fun waitTick() = context.waitTick()
    fun waitTicks(n: Int) = context.waitTicks(n)

    fun waitForScreen(clazz: Class<out net.minecraft.client.gui.screens.Screen>) =
        context.waitForScreen(clazz)

    fun closeScreen() {
        context.getInput().pressKey(GLFW.GLFW_KEY_ESCAPE)
        context.waitFor { mc -> mc.screen == null }
    }

    fun rightClickBlock(pos: BlockPos, direction: Direction = Direction.SOUTH) {
        world.getServer().runOnServer(object : FailableConsumer<net.minecraft.server.MinecraftServer, RuntimeException> {
            override fun accept(server: net.minecraft.server.MinecraftServer) {
                val level = server.overworld()
                val player = level.players().firstOrNull() ?: return
                val hitResult = BlockHitResult(Vec3.atCenterOf(pos), direction, pos, false)
                // Select the first non-empty hotbar slot so getItemInHand returns the actual item.
                val inv = player.inventory
                val nonEmpty = (0 until 9).firstOrNull { !inv.getItem(it).isEmpty }
                if (nonEmpty != null) inv.selected = nonEmpty
                val stack = player.getItemInHand(InteractionHand.MAIN_HAND)
                player.gameMode.useItemOn(player, level, stack, InteractionHand.MAIN_HAND, hitResult)
            }
        })
        context.waitTicks(2)
    }

    /** Clicks a button by its displayed text (works with both literal and translatable components). */
    fun clickButton(labelText: String) = context.clickScreenButton(labelText)

    /** Finds an EditBox in the current screen by its pixel width and sets its value directly. */
    fun fillEditBoxByWidth(widthPx: Int, value: String) {
        onClient { mc ->
            val screen = mc.screen
                ?: throw AssertionError("fillEditBoxByWidth($widthPx): no screen open")
            val box = screen.children()
                .filterIsInstance<EditBox>()
                .find { it.width == widthPx }
                ?: throw AssertionError(
                    "EditBox with width=$widthPx not found in ${screen::class.simpleName}. " +
                        "Available widths: ${screen.children().filterIsInstance<EditBox>().map { it.width }}"
                )
            box.value = value
        }
        context.waitTick()
    }

    /** Takes a screenshot and saves it to the test screenshots directory. */
    fun screenshot(name: String): Path = context.takeScreenshot(name)

    /** Reads the synced client-side BE (lastTestResult is synced via getUpdatePacket). */
    fun getClientBe(pos: BlockPos): SpecOriginBlockEntity? =
        fromClient { mc ->
            mc.level?.getBlockEntity(pos) as? SpecOriginBlockEntity
        }

    // Wrappers to avoid Kotlin's inability to infer FailableConsumer's exception type parameter.
    fun onClient(block: (Minecraft) -> Unit) {
        context.runOnClient(object : FailableConsumer<Minecraft, RuntimeException> {
            override fun accept(mc: Minecraft) = block(mc)
        })
    }

    fun <T> fromClient(block: (Minecraft) -> T): T {
        var result: T? = null
        onClient { mc -> result = block(mc) }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    companion object {
        fun createWorld(context: ClientGameTestContext): TestSingleplayerContext {
            val world = context.worldBuilder().setUseConsistentSettings(true).create()
            world.getClientLevel().waitForChunksDownload()
            world.getServer().runCommand("time set day")
            world.getServer().runCommand("gamemode creative @a")
            world.getServer().runCommand("effect give @a minecraft:saturation 1000000 255 true")
            context.waitTick()
            return world
        }
    }
}
