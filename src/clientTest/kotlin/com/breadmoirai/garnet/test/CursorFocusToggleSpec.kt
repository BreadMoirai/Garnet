package com.breadmoirai.garnet.test

import com.breadmoirai.garnet.client.viewport.cursorFocusActive
import com.breadmoirai.garnet.testing.ClientSpec
import com.breadmoirai.garnet.testing.core.FabricTestThreadPump
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import org.lwjgl.glfw.GLFW
import org.slf4j.LoggerFactory

/**
 * Spike Task 4 / exit criterion (d): a keybind that releases and re-grabs the mouse cursor,
 * for future panel-focus support (spike-level only — not wired to a real panel yet).
 *
 * Drives the real keybind (via a simulated key press on the Fabric test thread — the same
 * `ctx.getInput().pressKey` mechanism [SpecTestContext.closeScreen] uses for `ESCAPE`) rather
 * than calling `MouseHandler` directly, so this exercises
 * [com.breadmoirai.garnet.client.viewport.registerCursorFocusToggle]'s actual
 * `END_CLIENT_TICK` consumption path, not just the underlying API.
 *
 * The hard assertions here are on [cursorFocusActive] and
 * [net.minecraft.client.MouseHandler.isMouseGrabbed] — the actual MC-level state that gates
 * look-input processing and that vanilla code (and our own guard against fighting an open
 * screen) reads. A raw `GLFW.glfwGetInputMode` query is logged alongside for extra diagnostic
 * signal, but is **not** hard-asserted: in this client-gametest harness the OS-level cursor
 * visual mode did not reliably flip to `GLFW_CURSOR_DISABLED` even when `MouseHandler` reported
 * `isMouseGrabbed() == true` and `Minecraft.isWindowActive() == true` (see the Task 4 report for
 * the captured evidence) — a harness/window-focus quirk, not a defect in this feature. Whether
 * re-grabbing produces a visible camera jump is *not* something a game test can observe either
 * way; that remains a manual follow-up.
 */
class CursorFocusToggleSpec : ClientSpec({

    test("(d) cursor focus keybind toggles MouseHandler's grabbed state") {
        val logger = LoggerFactory.getLogger("Garnet")
        closeClientScreen()
        waitClientTicks(2)

        // Force a known baseline via the same API path our keybind uses.
        runOnClient { mc -> mc.mouseHandler.grabMouse() }
        waitClientTicks(1)

        cursorFocusActive.shouldBeFalse()
        val handle = onClient { mc -> mc.window.handle() }
        fun logGlfwCursorMode(label: String) {
            val mode = onClient { mc -> GLFW.glfwGetInputMode(handle, GLFW.GLFW_CURSOR) }
            logger.info(
                "CursorFocusToggleSpec [{}]: glfwGetInputMode(GLFW_CURSOR)={} (NORMAL={}, DISABLED={})",
                label, mode, GLFW.GLFW_CURSOR_NORMAL, GLFW.GLFW_CURSOR_DISABLED,
            )
        }
        logGlfwCursorMode("baseline")
        onClient { mc -> mc.mouseHandler.isMouseGrabbed }.shouldBeTrue()

        // Press 1: release the cursor.
        FabricTestThreadPump.runOnTestThread { ctx -> ctx.getInput().pressKey(GLFW.GLFW_KEY_B) }
        waitClientTicks(2)
        cursorFocusActive.shouldBeTrue()
        onClient { mc -> mc.mouseHandler.isMouseGrabbed }.shouldBeFalse()
        logGlfwCursorMode("after release press")

        // Press 2: re-grab the cursor.
        FabricTestThreadPump.runOnTestThread { ctx -> ctx.getInput().pressKey(GLFW.GLFW_KEY_B) }
        waitClientTicks(2)
        cursorFocusActive.shouldBeFalse()
        onClient { mc -> mc.mouseHandler.isMouseGrabbed }.shouldBeTrue()
        logGlfwCursorMode("after re-grab press")
    }
})
