package com.breadmoirai.redstonespecs.test

import com.breadmoirai.redstonespecs.ModRegistries
import com.breadmoirai.redstonespecs.block.RedstoneSpecRecorderBlock
import com.breadmoirai.redstonespecs.block.SpecBlockEntity
import com.breadmoirai.redstonespecs.client.screen.RecorderScreen
import com.breadmoirai.redstonespecs.network.SetRecorderConfigC2S
import com.breadmoirai.redstonespecs.testing.ClientSpec
import com.breadmoirai.redstonespecs.testing.core.FabricTestThreadPump
import com.breadmoirai.redstonespecs.testing.server.onServer
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import net.minecraft.client.gui.components.EditBox
import net.minecraft.core.BlockPos
import net.minecraft.core.Vec3i

/**
 * Client-side coverage for [RecorderScreen] population and keystroke wiring.
 *
 * Covers UC-REC-01.b/c (server build of OpenRecorderScreenS2C → screen opens with
 * EditBoxes pre-populated from BE fields) and UC-REC-01.d / UC-REC-03.a (every keystroke
 * fires SetRecorderConfigC2S carrying the current field values).
 *
 * Test bodies run on the Kotest worker thread under ClientSpec's dispatcher.
 * Private EditBox fields on RecorderScreen are read via [editBoxValue] reflection helper —
 * adding test-only accessors to RecorderScreen would pollute production for a single seam.
 * See docs/gametest/client-test-threading.md for the threading model.
 */
class RecorderScreenSpec : ClientSpec({
    // tests added in subsequent tasks
})

private fun RecorderScreen.editBoxValue(fieldName: String): String {
    val f = RecorderScreen::class.java.getDeclaredField(fieldName).apply { isAccessible = true }
    val box = f.get(this) as? EditBox ?: error("EditBox field '$fieldName' was null")
    return box.value
}

private fun RecorderScreen.setEditBoxValue(fieldName: String, value: String) {
    val f = RecorderScreen::class.java.getDeclaredField(fieldName).apply { isAccessible = true }
    val box = f.get(this) as? EditBox ?: error("EditBox field '$fieldName' was null")
    box.value = value
}
