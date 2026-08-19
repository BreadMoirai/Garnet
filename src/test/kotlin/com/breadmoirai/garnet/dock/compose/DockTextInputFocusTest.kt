package com.breadmoirai.garnet.dock.compose

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue

/**
 * The counter that tells the input router "someone is typing" — the gate that stops the `G`
 * dock-focus keybind from ejecting the player to the game mid-word (a plain letter is *not*
 * consumed by a Compose text field: typed text arrives on the separate `charTyped` path, so without
 * this the router would see an unclaimed `G` and drop focus).
 *
 * A count rather than a boolean: focus moves between two fields as *focus gained on the new one*
 * and *focus lost on the old one*, in an order Compose does not promise, so a boolean can be left
 * `false` while a field is still focused.
 */
class DockTextInputFocusTest : FunSpec({

    beforeTest { DockTextInputFocus.reset() }
    afterTest { DockTextInputFocus.reset() }

    test("no text field focused by default") {
        DockTextInputFocus.anyFocused.shouldBeFalse()
    }

    test("a focused field is reported until it releases") {
        DockTextInputFocus.acquire()
        DockTextInputFocus.anyFocused.shouldBeTrue()

        DockTextInputFocus.release()
        DockTextInputFocus.anyFocused.shouldBeFalse()
    }

    test("focus moving between two fields never reads as unfocused") {
        DockTextInputFocus.acquire() // field A focused
        DockTextInputFocus.acquire() // field B gained focus before A lost it
        DockTextInputFocus.release() // A lost focus

        DockTextInputFocus.anyFocused.shouldBeTrue()
    }

    test("an unbalanced release cannot drive the count negative") {
        // A panel torn down while a field held focus can release more than it acquired. A negative
        // count would make the *next* real focus invisible, silently re-arming the bug this guards.
        DockTextInputFocus.release()
        DockTextInputFocus.release()

        DockTextInputFocus.acquire()
        DockTextInputFocus.anyFocused.shouldBeTrue()
    }
})
