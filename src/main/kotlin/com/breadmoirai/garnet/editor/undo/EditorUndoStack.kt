package com.breadmoirai.garnet.editor.undo

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-player undo/redo history over Explorer file operations.
 *
 * Shape and lifetime mirror `EditorSession`: a `ConcurrentHashMap` keyed by player UUID, in memory
 * only, cleared by the same `ServerPlayConnectionEvents.DISCONNECT` registration. Nothing is
 * persisted — a stack restored after a restart would be almost entirely stale, and the content a
 * delete needs to be reversible lives in `LocalHistoryStore`, which does survive.
 *
 * Per-player stacks sit over shared server state, so an entry can go stale while it waits. That is
 * handled by precondition checks at replay time in `EditorUndoOps`, not here — this class is a pure
 * data structure and does no validation.
 */
object EditorUndoStack {

    /** Deepest history kept per player. Older entries are evicted from the bottom. */
    const val MAX_DEPTH = 50

    private class PlayerUndo {
        val undo = ArrayDeque<EditorUndoCommand>()
        val redo = ArrayDeque<EditorUndoCommand>()
    }

    private val byPlayer = ConcurrentHashMap<UUID, PlayerUndo>()

    private fun of(playerId: UUID): PlayerUndo = byPlayer.computeIfAbsent(playerId) { PlayerUndo() }

    /**
     * Record a newly performed operation. Clears the redo deque: once a new action lands, every
     * redo entry describes a branch that no longer follows from the current tree.
     */
    fun push(playerId: UUID, command: EditorUndoCommand) {
        val state = of(playerId)
        synchronized(state) {
            state.undo.addLast(command)
            while (state.undo.size > MAX_DEPTH) state.undo.removeFirst()
            state.redo.clear()
        }
    }

    /** Record an undone operation as redoable. Unlike [push], leaves the redo deque intact. */
    fun pushRedo(playerId: UUID, command: EditorUndoCommand) {
        val state = of(playerId)
        synchronized(state) {
            state.redo.addLast(command)
            while (state.redo.size > MAX_DEPTH) state.redo.removeFirst()
        }
    }

    fun peekUndo(playerId: UUID): EditorUndoCommand? =
        byPlayer[playerId]?.let { synchronized(it) { it.undo.lastOrNull() } }

    fun peekRedo(playerId: UUID): EditorUndoCommand? =
        byPlayer[playerId]?.let { synchronized(it) { it.redo.lastOrNull() } }

    fun popUndo(playerId: UUID): EditorUndoCommand? =
        byPlayer[playerId]?.let { synchronized(it) { it.undo.removeLastOrNull() } }

    fun popRedo(playerId: UUID): EditorUndoCommand? =
        byPlayer[playerId]?.let { synchronized(it) { it.redo.removeLastOrNull() } }

    fun undoDepth(playerId: UUID): Int =
        byPlayer[playerId]?.let { synchronized(it) { it.undo.size } } ?: 0

    fun clear(playerId: UUID) { byPlayer.remove(playerId) }
}
