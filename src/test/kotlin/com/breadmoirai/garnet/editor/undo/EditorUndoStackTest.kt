package com.breadmoirai.garnet.editor.undo

import com.breadmoirai.garnet.editor.undo.data.EditorUndoCommand
import com.breadmoirai.garnet.editor.undo.data.EditorUndoStack
import com.breadmoirai.garnet.editor.undo.data.RelocateKind
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import java.util.UUID

private fun cmd(name: String) = EditorUndoCommand.CreateFolder(name)

class EditorUndoStackTest : FunSpec({

    test("peekUndo returns the most recently pushed command") {
        val id = UUID.randomUUID()
        EditorUndoStack.clear(id)
        EditorUndoStack.push(id, cmd("a"))
        EditorUndoStack.push(id, cmd("b"))
        EditorUndoStack.peekUndo(id) shouldBe cmd("b")
    }

    test("popUndo removes the command it returns") {
        val id = UUID.randomUUID()
        EditorUndoStack.clear(id)
        EditorUndoStack.push(id, cmd("a"))
        EditorUndoStack.popUndo(id) shouldBe cmd("a")
        EditorUndoStack.peekUndo(id).shouldBeNull()
    }

    test("popRedo removes the command it returns") {
        val id = UUID.randomUUID()
        EditorUndoStack.clear(id)
        EditorUndoStack.pushRedo(id, cmd("a"))
        EditorUndoStack.popRedo(id) shouldBe cmd("a")
        EditorUndoStack.peekRedo(id).shouldBeNull()
    }

    test("push clears the redo deque") {
        val id = UUID.randomUUID()
        EditorUndoStack.clear(id)
        EditorUndoStack.pushRedo(id, cmd("redoable"))
        EditorUndoStack.push(id, cmd("new"))
        // A new action invalidates the redo branch — otherwise redo would replay an operation
        // against a tree that has since diverged from the one it was recorded on.
        EditorUndoStack.peekRedo(id).shouldBeNull()
    }

    test("pushRedo does not clear the redo deque") {
        val id = UUID.randomUUID()
        EditorUndoStack.clear(id)
        EditorUndoStack.pushRedo(id, cmd("first"))
        EditorUndoStack.pushRedo(id, cmd("second"))
        EditorUndoStack.peekRedo(id) shouldBe cmd("second")
    }

    test("the undo deque is capped, dropping the oldest entry") {
        val id = UUID.randomUUID()
        EditorUndoStack.clear(id)
        repeat(EditorUndoStack.MAX_DEPTH + 1) { EditorUndoStack.push(id, cmd("c$it")) }
        // Newest survives, oldest was evicted, depth is exactly the cap.
        EditorUndoStack.peekUndo(id) shouldBe cmd("c${EditorUndoStack.MAX_DEPTH}")
        EditorUndoStack.undoDepth(id) shouldBe EditorUndoStack.MAX_DEPTH
        val all = generateSequence { EditorUndoStack.popUndo(id) }.toList()
        all.last() shouldBe cmd("c1")
    }

    test("stacks are isolated per player") {
        val a = UUID.randomUUID()
        val b = UUID.randomUUID()
        EditorUndoStack.clear(a); EditorUndoStack.clear(b)
        EditorUndoStack.push(a, cmd("mine"))
        EditorUndoStack.peekUndo(b).shouldBeNull()
    }

    test("clear empties both deques") {
        val id = UUID.randomUUID()
        EditorUndoStack.push(id, cmd("a"))
        EditorUndoStack.pushRedo(id, cmd("b"))
        EditorUndoStack.clear(id)
        EditorUndoStack.peekUndo(id).shouldBeNull()
        EditorUndoStack.peekRedo(id).shouldBeNull()
    }

    test("Relocate labels distinguish rename from move") {
        EditorUndoCommand.Relocate("a", "b", RelocateKind.RENAME).label shouldBe "rename to 'b'"
        EditorUndoCommand.Relocate("a", "x/a", RelocateKind.MOVE).label shouldBe "move to 'x/a'"
    }
})
