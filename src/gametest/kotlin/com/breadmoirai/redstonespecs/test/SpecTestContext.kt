package com.breadmoirai.redstonespecs.test

import com.breadmoirai.redstonespecs.block.RedstoneSpecBlockEntity
import dev.isxander.yacl3.api.ButtonOption
import dev.isxander.yacl3.api.Option
import dev.isxander.yacl3.gui.YACLScreen
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.components.EditBox
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.InteractionHand
import net.minecraft.world.item.context.UseOnContext
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
                val stack = (0 until 9).map { player.inventory.getItem(it) }.firstOrNull { !it.isEmpty }
                    ?: player.getItemInHand(InteractionHand.MAIN_HAND)
                if (!stack.isEmpty) {
                    // Item interaction: call useOn directly to bypass ServerPlayerGameMode block
                    // interaction phase, which would toggle levers and similar blocks first.
                    stack.useOn(UseOnContext(level, player, InteractionHand.MAIN_HAND, stack, hitResult))
                } else {
                    // No item: trigger the block's own use action (e.g. opening SpecOverviewScreen).
                    level.getBlockState(pos).useWithoutItem(level, player, hitResult)
                }
            }
        })
        context.waitTicks(2)
    }

    /** Clicks a button by its displayed text (works with both literal and translatable components). */
    fun clickButton(labelText: String) = context.clickScreenButton(labelText)

    /**
     * Waits until a button with [labelText] is present in the current screen.
     * Useful when the screen rebuilds asynchronously after data arrives from the server.
     */
    fun waitForButton(labelText: String, timeoutTicks: Int = 100) {
        context.waitFor({ mc ->
            val screen = mc.screen ?: return@waitFor false
            screen.children()
                .filterIsInstance<net.minecraft.client.gui.components.AbstractButton>()
                .any { it.message.string == labelText }
        }, timeoutTicks)
    }

    /**
     * Clicks the Nth button (0-indexed) that has the given label text in the current screen.
     * Useful when multiple buttons share the same label (e.g. multiple " " checkboxes).
     */
    fun clickNthButton(labelText: String, index: Int) {
        onClient { mc ->
            val screen = mc.screen
                ?: throw AssertionError("clickNthButton($labelText, $index): no screen open")
            val matching = screen.children()
                .filterIsInstance<net.minecraft.client.gui.components.AbstractButton>()
                .filter { it.message.string == labelText }
            if (index >= matching.size) throw AssertionError(
                "clickNthButton($labelText, $index): only ${matching.size} button(s) with label '$labelText' found"
            )
            val input = net.minecraft.client.input.MouseButtonInfo(0, 0)
            matching[index].onPress(input)
        }
        context.waitTick()
    }

    /**
     * Clicks the Nth CycleButton (0-indexed) whose displayed value string equals [valueText].
     * CycleButtons with Component.empty() label render as ": <value>"; this helper extracts
     * just the value portion for matching.
     *
     * The displayed message of a CycleButton is either:
     *   - Just the value (displayState=VALUE), e.g. "false"
     *   - "Label: Value" (displayState=NAME_AND_VALUE), e.g. ": false" when label is empty
     */
    fun clickNthCycleButtonByValue(valueText: String, index: Int) {
        onClient { mc ->
            val screen = mc.screen
                ?: throw AssertionError("clickNthCycleButtonByValue($valueText, $index): no screen open")
            val matching = screen.children()
                .filterIsInstance<net.minecraft.client.gui.components.CycleButton<*>>()
                .filter { btn ->
                    val msg = btn.message.string
                    msg == valueText || msg.endsWith(": $valueText")
                }
            if (index >= matching.size) throw AssertionError(
                "clickNthCycleButtonByValue($valueText, $index): only ${matching.size} CycleButton(s) " +
                    "with value '$valueText' found. Messages: ${
                        screen.children()
                            .filterIsInstance<net.minecraft.client.gui.components.CycleButton<*>>()
                            .map { it.message.string }
                    }"
            )
            val input = net.minecraft.client.input.MouseButtonInfo(0, 0)
            matching[index].onPress(input)
        }
        context.waitTick()
    }

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
    fun getClientBe(pos: BlockPos): RedstoneSpecBlockEntity? =
        fromClient { mc ->
            mc.level?.getBlockEntity(pos) as? RedstoneSpecBlockEntity
        }

    /**
     * Clicks a ButtonOption by name inside a YACLScreen's option list.
     * Unlike clickButton(), this reaches into YACL's option tree (not the rendered widget tree).
     */
    fun clickYaclButton(labelText: String) {
        onClient { mc ->
            val screen = mc.screen as? YACLScreen
                ?: throw AssertionError("clickYaclButton($labelText): current screen is not a YACLScreen (got ${mc.screen?.javaClass?.simpleName})")
            for (category in screen.config.categories()) {
                for (group in category.groups()) {
                    for (option in group.options()) {
                        if (option is ButtonOption && option.name().string == labelText) {
                            option.action().accept(screen, option)
                            return@onClient
                        }
                    }
                }
            }
            val allButtons = screen.config.categories()
                .flatMap { it.groups() }
                .flatMap { it.options() }
                .filterIsInstance<ButtonOption>()
                .map { it.name().string }
            throw AssertionError("clickYaclButton($labelText): not found. Available buttons: $allButtons")
        }
        context.waitTick()
    }

    /**
     * Sets a YACL option value by searching all categories and groups.
     * [groupName] can be null to search the root (ungrouped) options only,
     * or set to a specific group name to search within that group.
     * Uses [Option.requestSet] which triggers the binding setter immediately.
     */
    fun <T : Any> setYaclOption(optionName: String, value: T, groupName: String? = null) {
        onClient { mc ->
            val screen = mc.screen as? YACLScreen
                ?: throw AssertionError("setYaclOption($optionName): not a YACLScreen")
            for (category in screen.config.categories()) {
                for (group in category.groups()) {
                    val groupMatch = groupName == null || group.name().string == groupName
                    if (!groupMatch) continue
                    for (option in group.options()) {
                        if (option.name().string == optionName) {
                            @Suppress("UNCHECKED_CAST")
                            (option as Option<T>).requestSet(value)
                            return@onClient
                        }
                    }
                }
            }
            throw AssertionError("setYaclOption($optionName, groupName=$groupName): option not found")
        }
        context.waitTick()
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
