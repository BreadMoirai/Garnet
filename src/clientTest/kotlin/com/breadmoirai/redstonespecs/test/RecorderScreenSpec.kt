package com.breadmoirai.redstonespecs.test

import com.breadmoirai.redstonespecs.ModRegistries
import com.breadmoirai.redstonespecs.block.RedstoneSpecRecorderBlock
import com.breadmoirai.redstonespecs.block.SpecBlockEntity
import com.breadmoirai.redstonespecs.client.screen.RecorderScreen
import com.breadmoirai.redstonespecs.network.SetRecorderConfigC2S
import com.breadmoirai.redstonespecs.testing.ClientSpec
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

    test("UC-REC-01.b/c: OpenRecorderScreenS2C opens RecorderScreen with EditBoxes pre-populated from BE") {
        val pos = BlockPos(220, 64, 100)
        val expectedSpecId = "uc-rec-01b-spec"
        val expectedStructure = "uc-rec-01b-struct"

        onServer {
            val level = this.overworld()
            val player = level.players().firstOrNull() ?: error("no overworld player")
            level.setBlock(pos, ModRegistries.REDSTONE_SPEC_RECORDER_BLOCK.defaultBlockState(), 2)
            val be = level.getBlockEntity(pos) as SpecBlockEntity
            be.setSpecId(expectedSpecId)
            be.setStructure(expectedStructure)
            be.setSpecBounds(Vec3i(3, 3, 3))
            RedstoneSpecRecorderBlock.openScreenFor(player, be)
        }

        waitForClientScreen(RecorderScreen::class.java)
        val (specIdVal, outPathVal, structIdVal) = onClient { mc ->
            val s = mc.screen as RecorderScreen
            Triple(s.editBoxValue("specIdBox"), s.editBoxValue("outPathBox"), s.editBoxValue("structureIdBox"))
        }
        specIdVal shouldBe expectedSpecId
        // outPath is sourced from be.specId in RedstoneSpecRecorderBlock.openScreenFor — stable contract
        outPathVal shouldBe expectedSpecId
        structIdVal shouldBe expectedStructure

        closeClientScreen()
    }

    test("UC-REC-01.d / UC-REC-03.a: setting an EditBox value fires SetRecorderConfigC2S with current field values") {
        val pos = BlockPos(240, 64, 100)
        val initialSpecId = "uc01d-init"
        val initialStructure = "uc01d-struct-init"

        onServer {
            val level = this.overworld()
            val player = level.players().firstOrNull() ?: error("no overworld player")
            level.setBlock(pos, ModRegistries.REDSTONE_SPEC_RECORDER_BLOCK.defaultBlockState(), 2)
            val be = level.getBlockEntity(pos) as SpecBlockEntity
            be.setSpecId(initialSpecId)
            be.setStructure(initialStructure)
            be.setSpecBounds(Vec3i(3, 3, 3))
            RedstoneSpecRecorderBlock.openScreenFor(player, be)
        }

        waitForClientScreen(RecorderScreen::class.java)
        drainClientPayloads()

        // Mutate specIdBox on the render thread — setValue fires the responder synchronously;
        // do NOT call onChange manually. Use runOnClient to reach the render thread safely.
        runOnClient { mc ->
            val s = mc.screen as RecorderScreen
            s.setEditBoxValue("specIdBox", "edited-id")
        }

        // Allow the payload to flush via the client networking pipeline.
        waitClientTicks(2)

        val first = drainClientPayloads().filterIsInstance<SetRecorderConfigC2S>()
        first shouldHaveSize 1
        first[0].originPos shouldBe pos
        first[0].specId shouldBe "edited-id"
        first[0].structureId shouldBe initialStructure

        // Now mutate the structure field; the new payload carries the latest specId AND the new structureId.
        runOnClient { mc ->
            val s = mc.screen as RecorderScreen
            s.setEditBoxValue("structureIdBox", "edited-struct")
        }
        waitClientTicks(2)

        val second = drainClientPayloads().filterIsInstance<SetRecorderConfigC2S>()
        second shouldHaveSize 1
        second[0].specId shouldBe "edited-id"
        second[0].structureId shouldBe "edited-struct"

        closeClientScreen()
    }
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
