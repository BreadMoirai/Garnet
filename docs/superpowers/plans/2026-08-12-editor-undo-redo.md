# Explorer Undo/Redo Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give each player an in-memory undo/redo stack over the seven mutating Project Explorer file operations, driven by two toolbar buttons.

**Architecture:** Each mutating handler, on its success path, pushes a server-authored `EditorUndoCommand` recording what actually happened (not what the client asked for) onto a per-player `EditorUndoStack`. Undo runs the command's inverse through the same primitives the handlers use — `relocate`, `deleteSubtree`, `restoreSubtree` — so the ordering hazards documented in `EditorFileOpsHandlers` are honoured rather than reimplemented. Delete gains a banking phase that writes a `pre-delete` revision of every file in the doomed subtree into `LocalHistoryStore`, which is what makes a delete reversible at all.

**Tech Stack:** Kotlin, Fabric (Minecraft 26.2, single Stonecutter version), Jewel/Compose Desktop for the dock UI, Kotest across `test`, `gametest`, and `clientTest`.

**Spec:** `docs/superpowers/specs/2026-08-12-editor-undo-redo-design.md`

## Global Constraints

- **Gradle must be invoked through the Windows batch wrapper**, never `./gradlew`: `cmd.exe /c "cd /d H:\\Repo\\Garnet && gradlew.bat <tasks>"`. See `docs/tooling/wsl2-gradle-invocation.md`.
- **The Stonecutter version prefix is `:26.2:`** on every Gradle task. It is the only version in `versions/`.
- **`--tests` filtering does not select Kotest specs.** Run the task unfiltered and read `versions/26.2/build/test-results/test/TEST-<FQCN>.xml`. See `docs/tooling/local-verification-commands.md`.
- **Compile verification spans five source sets**: `:26.2:clientClasses :26.2:classes :26.2:gametestClasses :26.2:clientTestClasses :26.2:testClasses`. `compileKotlin` alone covers only `main`.
- **`EditorCommand` is already taken** by `editor/command/EditorCommand.kt` (the brigadier command object). The undo type is `EditorUndoCommand`. Do not shadow the existing name.
- **`Revision` is a top-level class** in `com.breadmoirai.garnet.history`, not nested inside `LocalHistoryStore`.
- **`CompoundTag` getters are Optional-based on 26.2**: `getByteArray(String): Optional<byte[]>` (use `.orElse(null)`), `getBooleanOr(String, Boolean): Boolean`, `putByteArray(String, byte[])`, `putBoolean(String, Boolean)`. Verified against `minecraft-common-deobf-26.2.jar`.
- **Singleton payloads must send `INSTANCE`.** `StreamCodec.unit` captures one object by identity and throws `IllegalStateException("Can't encode A, expected B")` for any other instance.
- **Every client-supplied subpath goes through `EditorRoot.resolveSubpath`.** Undo itself carries no client-supplied path — that is the point — but any path it hands to a primitive must still resolve.
- **Handlers run on the server thread** via `ctx.server().execute`, as does `StructureCommit.tick`. Ordering within a handler is atomic against ticks.
- **Do not add a `Co-Authored-By` trailer** to any commit.

## File Structure

**Created:**
- `src/main/kotlin/com/breadmoirai/garnet/editor/undo/EditorUndoCommand.kt` — the sealed command type and its supporting records. Pure data, no IO, no Minecraft types.
- `src/main/kotlin/com/breadmoirai/garnet/editor/undo/EditorUndoStack.kt` — per-player deques. Pure data structure.
- `src/main/kotlin/com/breadmoirai/garnet/editor/undo/EditorUndoOps.kt` — the executor: precondition checks and inverse dispatch.
- `src/client/kotlin/com/breadmoirai/garnet/editor/ui/UndoState.kt` — client-side label holder.
- `src/test/kotlin/com/breadmoirai/garnet/editor/undo/EditorUndoStackTest.kt`
- `src/test/kotlin/com/breadmoirai/garnet/editor/network/EditorUndoPacketsTest.kt`
- `src/gametest/kotlin/com/breadmoirai/garnet/test/editor/EditorUndoNetworkSpec.kt`
- `docs/persistence/editor-undo-stack.md`

**Modified:**
- `src/main/kotlin/com/breadmoirai/garnet/history/LocalHistoryStore.kt` — raw-byte revisions.
- `src/main/kotlin/com/breadmoirai/garnet/editor/network/EditorFileOpsHandlers.kt` — `deleteSubtree` / `restoreSubtree` primitives, `relocate` becomes internal, command recording.
- `src/main/kotlin/com/breadmoirai/garnet/editor/network/EditorStructureHandlers.kt`, `EditorTreeHandlers.kt` — command recording for the two create-a-file operations.
- `src/main/kotlin/com/breadmoirai/garnet/editor/network/EditorHandlerSupport.kt` — `sendUndoState`.
- `src/main/kotlin/com/breadmoirai/garnet/editor/network/EditorPackets.kt`, `EditorNetworkRegistry.kt` — three payloads.
- `src/main/kotlin/com/breadmoirai/garnet/Garnet.kt` — clear the stack on disconnect.
- `src/client/kotlin/com/breadmoirai/garnet/editor/network/EditorClientNetworking.kt`, `editor/ui/ExplorerToolbar.kt`, `editor/ui/ExplorerLifecycle.kt`.
- `src/gametest/kotlin/com/breadmoirai/garnet/test/history/LocalHistoryStoreSpec.kt`
- `src/clientTest/kotlin/com/breadmoirai/garnet/test/ExplorerUiSpec.kt`
- `docs/persistence/INDEX.md`, `docs/persistence/local-history.md`, `docs/persistence/network-payload-contract.md`

---

### Task 1: Raw-byte revisions in `LocalHistoryStore`

`LocalHistoryStore` writes `CompoundTag` blobs through `NbtIo.writeCompressed`. A `.spec.kts` file is not NBT. Rather than forking the blob format — which would mean a second `index.json` shape, a second prune path, and a second `moveHistory` — arbitrary bytes are wrapped in a `CompoundTag` carrying a marker field. Everything downstream keeps working untouched.

**Files:**
- Modify: `src/main/kotlin/com/breadmoirai/garnet/history/LocalHistoryStore.kt`
- Test: `src/gametest/kotlin/com/breadmoirai/garnet/test/history/LocalHistoryStoreSpec.kt`

**Interfaces:**
- Consumes: existing `LocalHistoryStore.writeRevision`, `readTag`, and the top-level `Revision`.
- Produces:
  - `LocalHistoryStore.REASON_PRE_DELETE: String` (`"pre-delete"`)
  - `LocalHistoryStore.writeRawRevision(file: Path, bytes: ByteArray, reason: String, nowMillis: Long = System.currentTimeMillis(), prune: Boolean = true): Revision?`
  - `LocalHistoryStore.readRawBytes(file: Path, revision: Revision): ByteArray?` — null when the revision is a typed structure revision rather than a raw one.

This is a gametest, not a unit test: `NbtIo` needs the Minecraft bootstrap.

- [ ] **Step 1: Write the failing tests**

Append inside the existing `FunSpec`-style block in `LocalHistoryStoreSpec.kt`. It already has a temp-dir helper; follow whatever the surrounding tests use to obtain `file` — read the top of the file first and match it exactly rather than inventing a new helper.

```kotlin
    test("writeRawRevision round-trips arbitrary bytes") {
        withHistoryDir { dir ->
            val file = dir.resolve("clock.spec.kts")
            val bytes = "spec { name = \"clock\" }".toByteArray()
            val rev = LocalHistoryStore.writeRawRevision(file, bytes, LocalHistoryStore.REASON_PRE_DELETE)
            rev.shouldNotBeNull()
            rev.reason shouldBe "pre-delete"
            LocalHistoryStore.readRawBytes(file, rev) shouldBe bytes
        }
    }

    test("readRawBytes returns null for a typed structure revision") {
        withHistoryDir { dir ->
            val file = dir.resolve("gadget.nbt")
            val tag = CompoundTag().also { it.putString("garnetTestMarker", "typed") }
            val rev = LocalHistoryStore.writeRevision(file, tag, 1, 2, 3, blockCount = 4, reason = LocalHistoryStore.REASON_MANUAL)
            rev.shouldNotBeNull()
            // A typed revision must not be mistaken for a raw one — restore picks its write path
            // off exactly this distinction, and a wrong answer writes a wrapper tag over a .nbt.
            LocalHistoryStore.readRawBytes(file, rev).shouldBeNull()
        }
    }

    test("raw and typed revisions coexist in one index") {
        withHistoryDir { dir ->
            val file = dir.resolve("mixed.nbt")
            val tag = CompoundTag().also { it.putString("garnetTestMarker", "typed") }
            LocalHistoryStore.writeRevision(file, tag, 1, 1, 1, blockCount = 1, reason = LocalHistoryStore.REASON_MANUAL)
            LocalHistoryStore.writeRawRevision(file, byteArrayOf(1, 2, 3), LocalHistoryStore.REASON_PRE_DELETE)
            LocalHistoryStore.revisions(file) shouldHaveSize 2
        }
    }

    test("a raw revision records zero size and zero block count") {
        withHistoryDir { dir ->
            val file = dir.resolve("notes.txt")
            val rev = LocalHistoryStore.writeRawRevision(file, byteArrayOf(7), LocalHistoryStore.REASON_PRE_DELETE)
            rev.shouldNotBeNull()
            rev.sizeX shouldBe 0
            rev.sizeY shouldBe 0
            rev.sizeZ shouldBe 0
            rev.blockCount shouldBe 0
        }
    }
```

If `LocalHistoryStoreSpec.kt` has no `withHistoryDir` helper, add one modelled on how the file's existing tests redirect `SharedSettings.localHistoryDir` to a temp directory and restore it afterwards — do not point tests at the real `<gameDir>/.garnet/local-history`.

- [ ] **Step 2: Run the tests to verify they fail**

```bash
cmd.exe /c "cd /d H:\\Repo\\Garnet && gradlew.bat :26.2:gametestClasses"
```

Expected: FAIL to compile — `writeRawRevision`, `readRawBytes`, and `REASON_PRE_DELETE` are unresolved references.

- [ ] **Step 3: Implement**

In `LocalHistoryStore.kt`, add the reason constant beside the existing ones:

```kotlin
    /** A snapshot banked immediately before a delete unlinks the file, so the delete is undoable.
     *  Unlike every other reason, this one is written for ANY file type — `.spec.kts` included —
     *  via [writeRawRevision]. See docs/persistence/editor-undo-stack.md. */
    const val REASON_PRE_DELETE = "pre-delete"
```

And, after `readTag`, the raw pair:

```kotlin
    // A raw revision is a normal revision whose blob is a wrapper CompoundTag holding the file's
    // bytes verbatim. Wrapping rather than forking the blob format is what keeps index.json,
    // prune, moveHistory and moveDescendantHistories working unchanged for both kinds.
    private const val RAW_MARKER_KEY = "garnetRaw"
    private const val RAW_BYTES_KEY = "garnetBytes"

    /**
     * Banks [bytes] as a revision of [file] for a file that is not a structure. Size and block-count
     * metadata are zero, because there is no structure to describe — a consumer must read a
     * zero-size revision as "not a structure snapshot", never as "an empty structure".
     */
    fun writeRawRevision(
        file: Path,
        bytes: ByteArray,
        reason: String,
        nowMillis: Long = System.currentTimeMillis(),
        prune: Boolean = true,
    ): Revision? {
        val tag = CompoundTag()
        tag.putBoolean(RAW_MARKER_KEY, true)
        tag.putByteArray(RAW_BYTES_KEY, bytes)
        return writeRevision(
            file, tag, sizeX = 0, sizeY = 0, sizeZ = 0, blockCount = 0,
            reason = reason, nowMillis = nowMillis, prune = prune,
        )
    }

    /**
     * The bytes banked by [writeRawRevision], or null when [revision] is a typed structure revision
     * (or unreadable). Callers restoring a file use the null to choose the `NbtIo` write path
     * instead — mixing them up would write a wrapper tag over a real `.nbt`.
     */
    fun readRawBytes(file: Path, revision: Revision): ByteArray? {
        val tag = readTag(file, revision) ?: return null
        if (!tag.getBooleanOr(RAW_MARKER_KEY, false)) return null
        return tag.getByteArray(RAW_BYTES_KEY).orElse(null)
    }
```

- [ ] **Step 4: Run the tests to verify they pass**

```bash
cmd.exe /c "cd /d H:\\Repo\\Garnet && gradlew.bat :26.2:runGameTest"
```

Expected: PASS. Read `versions/26.2/build/test-results/` for the `LocalHistoryStoreSpec` results — `--tests` filtering does not work on Kotest specs.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/breadmoirai/garnet/history/LocalHistoryStore.kt src/gametest/kotlin/com/breadmoirai/garnet/test/history/LocalHistoryStoreSpec.kt
git commit -m "feat(history): bank arbitrary file bytes as raw revisions"
```

---

### Task 2: `EditorUndoCommand` and `EditorUndoStack`

Pure data and a pure data structure — no Minecraft types, no IO, so both are unit-testable. Lands before anything that records or replays commands.

**Files:**
- Create: `src/main/kotlin/com/breadmoirai/garnet/editor/undo/EditorUndoCommand.kt`
- Create: `src/main/kotlin/com/breadmoirai/garnet/editor/undo/EditorUndoStack.kt`
- Test: `src/test/kotlin/com/breadmoirai/garnet/editor/undo/EditorUndoStackTest.kt`

**Interfaces:**
- Consumes: `com.breadmoirai.garnet.history.Revision` from Task 1's file (unchanged by Task 1).
- Produces: the full `EditorUndoCommand` hierarchy and every `EditorUndoStack` function listed in Step 3. Later tasks construct these types by name.

- [ ] **Step 1: Write the failing test**

Create `src/test/kotlin/com/breadmoirai/garnet/editor/undo/EditorUndoStackTest.kt`:

```kotlin
package com.breadmoirai.garnet.editor.undo

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
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
cmd.exe /c "cd /d H:\\Repo\\Garnet && gradlew.bat :26.2:testClasses"
```

Expected: FAIL to compile — unresolved reference `EditorUndoStack`.

- [ ] **Step 3: Implement both files**

`src/main/kotlin/com/breadmoirai/garnet/editor/undo/EditorUndoCommand.kt`:

```kotlin
package com.breadmoirai.garnet.editor.undo

import com.breadmoirai.garnet.history.Revision
import java.nio.file.Path

/** Which flavour of relocation produced a [EditorUndoCommand.Relocate] — messages only. */
enum class RelocateKind { RENAME, MOVE }

/** Which create-a-file handler produced a [EditorUndoCommand.CreateFile]. */
enum class CreatedFileKind { STRUCTURE, SPEC }

/** One node of a deleted subtree, relative to the deleted root. */
data class ManifestEntry(val relPath: String, val isFolder: Boolean)

/**
 * One banked file of a deleted subtree.
 *
 * [relPath] is relative to the deleted root and is what a restore writes back to, resolved against
 * the CURRENT project root. [absolutePath] is the file's path as it stood at delete time and is the
 * `LocalHistoryStore` key — that store hashes the absolute path, and a deleted file's history
 * directory deliberately outlives the file. The two are separate fields because the project root
 * can be repointed ("Open Folder…") between the delete and the undo.
 */
data class BankedFile(val relPath: String, val absolutePath: Path, val revision: Revision)

/**
 * A record of a file operation the server actually performed, carrying everything its inverse needs.
 *
 * Deliberately NOT the C2S packet that triggered it: `DuplicatePathC2S` does not say what name the
 * server derived, and `DeletePathC2S` carries neither the subtree shape nor its contents. See
 * docs/persistence/editor-undo-stack.md.
 *
 * Named `EditorUndoCommand`, not `EditorCommand` — the latter is the brigadier command object in
 * `editor/command/`.
 */
sealed interface EditorUndoCommand {

    /** Human-readable, rendered verbatim in the toolbar tooltip as "Undo <label>". */
    val label: String

    /**
     * The three create-shaped commands each carry a nullable [banked].
     *
     * Undoing a create means deleting what was created — and the content of that node cannot be
     * reconstructed from the command, because it came from a create handler rather than from
     * anything recorded here. So the undo's own `deleteSubtree` banks it, and the resulting
     * [Delete] is stapled onto the command as it moves to the redo deque. Redo is then a restore.
     * Null means "not undone yet" (fresh command) or "the removal could not be banked", in which
     * case redo is unavailable and says so.
     */
    data class CreateFolder(
        val subpath: String,
        val banked: Delete? = null,
    ) : EditorUndoCommand {
        override val label get() = "create folder '$subpath'"
    }

    data class CreateFile(
        val subpath: String,
        val kind: CreatedFileKind,
        val banked: Delete? = null,
    ) : EditorUndoCommand {
        override val label get() = "create '$subpath'"
    }

    /** [createdSubpath] is the server-derived copy name, which is the whole reason this exists. */
    data class Duplicate(
        val createdSubpath: String,
        val banked: Delete? = null,
    ) : EditorUndoCommand {
        override val label get() = "duplicate '$createdSubpath'"
    }

    data class Relocate(
        val oldSubpath: String,
        val newSubpath: String,
        val kind: RelocateKind,
    ) : EditorUndoCommand {
        override val label get() = when (kind) {
            RelocateKind.RENAME -> "rename to '$newSubpath'"
            RelocateKind.MOVE -> "move to '$newSubpath'"
        }
    }

    data class Delete(
        val rootSubpath: String,
        val manifest: List<ManifestEntry>,
        val banked: List<BankedFile>,
    ) : EditorUndoCommand {
        override val label get() = "delete '$rootSubpath'"
    }
}
```

`src/main/kotlin/com/breadmoirai/garnet/editor/undo/EditorUndoStack.kt`:

```kotlin
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
```

- [ ] **Step 4: Run the test to verify it passes**

```bash
cmd.exe /c "cd /d H:\\Repo\\Garnet && gradlew.bat :26.2:test"
```

Expected: PASS. Read `versions/26.2/build/test-results/test/TEST-com.breadmoirai.garnet.editor.undo.EditorUndoStackTest.xml`.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/breadmoirai/garnet/editor/undo/ src/test/kotlin/com/breadmoirai/garnet/editor/undo/
git commit -m "feat(editor): per-player undo command stack"
```

---

### Task 3: Undo payloads, disconnect clearing, and `sendUndoState`

Three payloads and the helper every recording site will call. Receivers are wired in Task 7, once there is something for them to invoke.

**Files:**
- Modify: `src/main/kotlin/com/breadmoirai/garnet/editor/network/EditorPackets.kt`
- Modify: `src/main/kotlin/com/breadmoirai/garnet/editor/network/EditorNetworkRegistry.kt`
- Modify: `src/main/kotlin/com/breadmoirai/garnet/editor/network/EditorHandlerSupport.kt`
- Modify: `src/main/kotlin/com/breadmoirai/garnet/Garnet.kt`
- Test: `src/test/kotlin/com/breadmoirai/garnet/editor/network/EditorUndoPacketsTest.kt`

**Interfaces:**
- Consumes: `EditorUndoStack.peekUndo` / `peekRedo` and `EditorUndoCommand.label` from Task 2.
- Produces: `UndoC2S.INSTANCE`, `RedoC2S.INSTANCE`, `UndoStateS2C(undoLabel: String?, redoLabel: String?)`, and `EditorHandlerSupport.sendUndoState(player: ServerPlayer)`.

- [ ] **Step 1: Write the failing test**

Create `src/test/kotlin/com/breadmoirai/garnet/editor/network/EditorUndoPacketsTest.kt`:

```kotlin
package com.breadmoirai.garnet.editor.network

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.netty.buffer.Unpooled

class EditorUndoPacketsTest : FunSpec({

    test("UndoStateS2C round-trips both labels") {
        val buf = Unpooled.buffer()
        val orig = UndoStateS2C("delete 'redstone/clock.nbt'", "rename to 'logic'")
        UndoStateS2C.STREAM_CODEC.encode(buf, orig)
        UndoStateS2C.STREAM_CODEC.decode(buf) shouldBe orig
    }

    test("UndoStateS2C round-trips nulls (both buttons disabled)") {
        val buf = Unpooled.buffer()
        val orig = UndoStateS2C(null, null)
        UndoStateS2C.STREAM_CODEC.encode(buf, orig)
        UndoStateS2C.STREAM_CODEC.decode(buf) shouldBe orig
    }

    test("UndoStateS2C round-trips a null redo label only") {
        val buf = Unpooled.buffer()
        val orig = UndoStateS2C("create folder 'x'", null)
        UndoStateS2C.STREAM_CODEC.encode(buf, orig)
        UndoStateS2C.STREAM_CODEC.decode(buf) shouldBe orig
    }

    test("UndoC2S encodes its INSTANCE") {
        val buf = Unpooled.buffer()
        UndoC2S.STREAM_CODEC.encode(buf, UndoC2S.INSTANCE)
        UndoC2S.STREAM_CODEC.decode(buf) shouldBe UndoC2S.INSTANCE
    }

    test("RedoC2S encodes its INSTANCE") {
        val buf = Unpooled.buffer()
        RedoC2S.STREAM_CODEC.encode(buf, RedoC2S.INSTANCE)
        RedoC2S.STREAM_CODEC.decode(buf) shouldBe RedoC2S.INSTANCE
    }
})
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
cmd.exe /c "cd /d H:\\Repo\\Garnet && gradlew.bat :26.2:testClasses"
```

Expected: FAIL to compile — unresolved references `UndoStateS2C`, `UndoC2S`, `RedoC2S`.

- [ ] **Step 3: Add the payloads**

Append to `EditorPackets.kt`, after the existing file-ops payloads:

```kotlin
// === Undo/redo ===

class UndoC2S private constructor() : CustomPacketPayload {
    companion object {
        // Must be sent as INSTANCE — StreamCodec.unit captures this object by identity.
        val INSTANCE = UndoC2S()
        val TYPE = CustomPacketPayload.Type<UndoC2S>(id("undo"))
        val STREAM_CODEC: StreamCodec<ByteBuf, UndoC2S> = StreamCodec.unit(INSTANCE)
    }
    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE
}

class RedoC2S private constructor() : CustomPacketPayload {
    companion object {
        val INSTANCE = RedoC2S()
        val TYPE = CustomPacketPayload.Type<RedoC2S>(id("redo"))
        val STREAM_CODEC: StreamCodec<ByteBuf, RedoC2S> = StreamCodec.unit(INSTANCE)
    }
    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE
}

/**
 * The acting player's undo/redo availability. A null label means that button is disabled.
 *
 * Per-player state, so unlike [StructureAutoSavedS2C] this is never broadcast — another player's
 * stack is none of this client's business.
 *
 * Optional strings use the leading-boolean-flag idiom, matching
 * [EditorTreeSnapshotS2C.currentSubpath].
 */
data class UndoStateS2C(val undoLabel: String?, val redoLabel: String?) : CustomPacketPayload {
    companion object {
        val TYPE = CustomPacketPayload.Type<UndoStateS2C>(id("undo_state"))
        val STREAM_CODEC: StreamCodec<ByteBuf, UndoStateS2C> = object : StreamCodec<ByteBuf, UndoStateS2C> {
            override fun decode(buf: ByteBuf): UndoStateS2C {
                val undo = if (buf.readBoolean()) ByteBufCodecs.STRING_UTF8.decode(buf) else null
                val redo = if (buf.readBoolean()) ByteBufCodecs.STRING_UTF8.decode(buf) else null
                return UndoStateS2C(undo, redo)
            }
            override fun encode(buf: ByteBuf, value: UndoStateS2C) {
                buf.writeBoolean(value.undoLabel != null)
                if (value.undoLabel != null) ByteBufCodecs.STRING_UTF8.encode(buf, value.undoLabel)
                buf.writeBoolean(value.redoLabel != null)
                if (value.redoLabel != null) ByteBufCodecs.STRING_UTF8.encode(buf, value.redoLabel)
            }
        }
    }
    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE
}
```

- [ ] **Step 4: Register the payload types**

In `EditorNetworkRegistry.register()`, beside the existing registrations (receivers come in Task 7):

```kotlin
        PayloadTypeRegistry.serverboundPlay().register(UndoC2S.TYPE, UndoC2S.STREAM_CODEC)
        PayloadTypeRegistry.serverboundPlay().register(RedoC2S.TYPE, RedoC2S.STREAM_CODEC)
        PayloadTypeRegistry.clientboundPlay().register(UndoStateS2C.TYPE, UndoStateS2C.STREAM_CODEC)
```

- [ ] **Step 5: Add `sendUndoState` and clear the stack on disconnect**

In `EditorHandlerSupport`, beside `sendTree`:

```kotlin
    /**
     * Push the player's current undo/redo availability. Called after every mutating operation and
     * after every undo/redo — the toolbar renders availability rather than guessing at it.
     */
    fun sendUndoState(player: ServerPlayer) {
        ServerPlayNetworking.send(player, UndoStateS2C(
            undoLabel = EditorUndoStack.peekUndo(player.uuid)?.label,
            redoLabel = EditorUndoStack.peekRedo(player.uuid)?.label,
        ))
    }
```

Add `import com.breadmoirai.garnet.editor.undo.EditorUndoStack` to that file.

In `Garnet.kt`, extend the existing DISCONNECT registration — the stack has exactly `EditorSession`'s lifetime:

```kotlin
        ServerPlayConnectionEvents.DISCONNECT.register { handler, _ ->
            EditorSession.clear(handler.player.uuid)
            EditorUndoStack.clear(handler.player.uuid)
        }
```

Add `import com.breadmoirai.garnet.editor.undo.EditorUndoStack` there too.

- [ ] **Step 6: Run the test to verify it passes**

```bash
cmd.exe /c "cd /d H:\\Repo\\Garnet && gradlew.bat :26.2:test"
```

Expected: PASS. Read `versions/26.2/build/test-results/test/TEST-com.breadmoirai.garnet.editor.network.EditorUndoPacketsTest.xml`.

- [ ] **Step 7: Commit**

```bash
git add src/main/kotlin/com/breadmoirai/garnet/editor/network/EditorPackets.kt src/main/kotlin/com/breadmoirai/garnet/editor/network/EditorNetworkRegistry.kt src/main/kotlin/com/breadmoirai/garnet/editor/network/EditorHandlerSupport.kt src/main/kotlin/com/breadmoirai/garnet/Garnet.kt src/test/kotlin/com/breadmoirai/garnet/editor/network/EditorUndoPacketsTest.kt
git commit -m "feat(editor): undo/redo payloads and per-player state push"
```

---

### Task 4: `deleteSubtree` — extract the delete, add the banking phase

`handleDelete`'s body becomes a reusable primitive, and gains the phase that makes a delete reversible. This is the task the whole feature rests on.

**Files:**
- Modify: `src/main/kotlin/com/breadmoirai/garnet/editor/network/EditorFileOpsHandlers.kt`
- Test: `src/gametest/kotlin/com/breadmoirai/garnet/test/editor/EditorUndoNetworkSpec.kt` (create)

**Interfaces:**
- Consumes: `LocalHistoryStore.writeRawRevision` / `REASON_PRE_DELETE` (Task 1), `EditorUndoCommand.Delete` / `ManifestEntry` / `BankedFile` (Task 2), `EditorHandlerSupport.sendUndoState` (Task 3).
- Produces:
  - `sealed interface DeleteOutcome` with `Failed(reason: String)`, `Deleted(command: EditorUndoCommand.Delete)`, `DeletedUnbankable(reason: String)`
  - `EditorFileOpsHandlers.deleteSubtree(server: MinecraftServer, player: ServerPlayer, subpath: String, target: Path): DeleteOutcome`

- [ ] **Step 1: Write the failing tests**

First, **extract** `EditorFileOpsNetworkSpec.kt`'s private `withServer` helper into the shared gametest support file that already holds `withTempRoot`, `makeMockServerPlayer`, and `drainPayloads` (`com.breadmoirai.garnet.test`), giving it a `prefix: String` parameter in place of the hardcoded `"fileops-net"` temp-dir names:

```kotlin
suspend fun withEditorServer(
    prefix: String,
    block: suspend (server: MinecraftServer, player: ServerPlayer, root: Path) -> Unit,
)
```

Keep its body — the `SharedSettings.localHistoryDir` redirect and the `finally` block clearing `StructureAutoSave` dirty state and `StructureCommit` backoff — byte-for-byte, including its comments. That helper exists because the gametest harness reuses one `MinecraftServer` across tests, so a dirty subpath leaked by one test resolves against a later test's root; the comments explaining that must travel with it.

Then have `EditorFileOpsNetworkSpec` call `withEditorServer("fileops-net") { … }` (its own tests are the regression guard for the extraction) and create `src/gametest/kotlin/com/breadmoirai/garnet/test/editor/EditorUndoNetworkSpec.kt` calling `withEditorServer("undo-net") { … }`. Tests below are written against a local `withServer` alias — define one private to the new spec if you prefer, or call `withEditorServer` directly.

```kotlin
package com.breadmoirai.garnet.test.editor

// … imports mirroring EditorFileOpsNetworkSpec, plus:
// com.breadmoirai.garnet.editor.network.DeletePathC2S
// com.breadmoirai.garnet.editor.network.EditorFileOpsHandlers
// com.breadmoirai.garnet.editor.undo.EditorUndoCommand
// com.breadmoirai.garnet.editor.undo.EditorUndoStack
// com.breadmoirai.garnet.history.LocalHistoryStore

class EditorUndoNetworkSpec : GarnetTestSpec({

    test("delete banks a pre-delete revision for a .spec.kts") {
        withServer { server, player, root ->
            EditorUndoStack.clear(player.uuid)
            val spec = root.resolve("clock.spec.kts")
            spec.writeBytes("spec { }".toByteArray())

            EditorFileOpsHandlers.handleDelete(server, player, DeletePathC2S("clock.spec.kts"))

            spec.exists().shouldBeFalse()
            // History is keyed by absolute path and outlives the file, which is what makes the
            // undo possible at all.
            LocalHistoryStore.revisions(spec).shouldNotBeEmpty()
            LocalHistoryStore.revisions(spec).last().reason shouldBe LocalHistoryStore.REASON_PRE_DELETE
        }
    }

    test("delete banks a freshly duplicated .nbt that had no history") {
        withServer { server, player, root ->
            EditorUndoStack.clear(player.uuid)
            val copy = root.resolve("gadget copy.nbt")
            copy.writeBytes(byteArrayOf(0x1f.toByte(), 0x8b.toByte()))
            // handleDuplicate deliberately gives a copy no history, so without banking there would
            // be nothing to restore from here.
            LocalHistoryStore.revisions(copy).shouldBeEmpty()

            EditorFileOpsHandlers.handleDelete(server, player, DeletePathC2S("gadget copy.nbt"))

            LocalHistoryStore.revisions(copy).shouldNotBeEmpty()
        }
    }

    test("delete pushes a Delete command carrying the whole subtree manifest") {
        withServer { server, player, root ->
            EditorUndoStack.clear(player.uuid)
            root.resolve("redstone/clocks").createDirectories()
            root.resolve("redstone/clocks/a.spec.kts").writeBytes("a".toByteArray())
            root.resolve("redstone/b.spec.kts").writeBytes("b".toByteArray())

            EditorFileOpsHandlers.handleDelete(server, player, DeletePathC2S("redstone"))

            val command = EditorUndoStack.peekUndo(player.uuid)
            command.shouldNotBeNull()
            command.shouldBeInstanceOf<EditorUndoCommand.Delete>()
            command.rootSubpath shouldBe "redstone"
            // Both folders (redstone itself and clocks) and both files.
            command.manifest.filter { it.isFolder }.map { it.relPath } shouldContainExactlyInAnyOrder listOf("", "clocks")
            command.banked.map { it.relPath } shouldContainExactlyInAnyOrder listOf("clocks/a.spec.kts", "b.spec.kts")
        }
    }

    test("a failed delete pushes nothing") {
        withServer { server, player, _ ->
            EditorUndoStack.clear(player.uuid)
            EditorFileOpsHandlers.handleDelete(server, player, DeletePathC2S("does-not-exist"))
            EditorUndoStack.peekUndo(player.uuid).shouldBeNull()
        }
    }
})
```

Use `io.kotest.matchers.types.shouldBeInstanceOf` and `io.kotest.matchers.collections.shouldContainExactlyInAnyOrder`.

- [ ] **Step 2: Run to verify it fails**

```bash
cmd.exe /c "cd /d H:\\Repo\\Garnet && gradlew.bat :26.2:gametestClasses"
```

Expected: FAIL to compile — unresolved `EditorUndoStack` usage is fine (Task 2 landed it), but the manifest assertions fail at runtime because nothing is pushed yet. If it compiles, run `:26.2:runGameTest` and expect the three assertion failures.

- [ ] **Step 3: Add the banking walk and `DeleteOutcome`**

In `EditorFileOpsHandlers.kt`, above `handleDelete`:

```kotlin
/** The result of [EditorFileOpsHandlers.deleteSubtree]. */
sealed interface DeleteOutcome {
    /** Nothing was deleted; [reason] is already phrased for the player. */
    data class Failed(val reason: String) : DeleteOutcome
    /** Deleted, and fully restorable — push [command]. */
    data class Deleted(val command: EditorUndoCommand.Delete) : DeleteOutcome
    /**
     * Deleted, but at least one file could not be banked, so the operation is NOT undoable and the
     * caller must push nothing. A partially restorable entry is worse than none: undo would report
     * success while silently losing files.
     */
    data class DeletedUnbankable(val reason: String) : DeleteOutcome
}
```

Then replace the body of `handleDelete` after its resolution/validation block with a call to the new primitive, and add the primitive itself:

```kotlin
    /**
     * Walk [target]'s subtree, bank every file into `LocalHistoryStore`, then unlink and tear down.
     *
     * Order is bank → unlink → teardown. Banking BEFORE the unlink is the only ordering that works:
     * once the bytes are gone there is nothing left to read. The pre-existing best-effort
     * `commitDirtyUnder` still runs before this (in the caller), so a dirty structure's pending
     * world edits are already in the file by the time it is read here.
     *
     * Every file is banked unconditionally rather than only those whose newest revision looks
     * stale: an equality check would be a guess about content, and unconditional banking is what
     * closes the two real gaps — `.spec.kts` files were never in the store at all, and a freshly
     * duplicated `.nbt` has no history by design.
     */
    internal fun deleteSubtree(
        server: MinecraftServer,
        player: ServerPlayer,
        subpath: String,
        target: Path,
    ): DeleteOutcome {
        val manifest = mutableListOf<ManifestEntry>()
        val banked = mutableListOf<BankedFile>()
        var bankFailure: String? = null

        if (target.isDirectory()) {
            manifest.add(ManifestEntry("", isFolder = true))
            target.walk(PathWalkOption.INCLUDE_DIRECTORIES).forEach { entry ->
                if (entry == target) return@forEach
                val rel = target.relativize(entry).joinToString("/")
                if (entry.isDirectory()) {
                    manifest.add(ManifestEntry(rel, isFolder = true))
                } else {
                    manifest.add(ManifestEntry(rel, isFolder = false))
                    when (val revision = bankFile(server, entry)) {
                        null -> bankFailure = bankFailure ?: "could not bank '$rel'"
                        else -> banked.add(BankedFile(rel, entry.toAbsolutePath(), revision))
                    }
                }
            }
        } else {
            manifest.add(ManifestEntry("", isFolder = false))
            when (val revision = bankFile(server, target)) {
                null -> bankFailure = "could not bank '$subpath'"
                else -> banked.add(BankedFile("", target.toAbsolutePath(), revision))
            }
        }

        try {
            if (target.isDirectory()) {
                // copyRecursively's counterpart returns false rather than throwing on a partial
                // failure, so an unchecked call would silently report success for a subtree that is
                // still half on disk.
                if (!target.toFile().deleteRecursively()) {
                    error("could not delete every entry under $subpath")
                }
            } else {
                target.deleteExisting()
            }
        } catch (e: Exception) {
            LOGGER.error("[project/delete] {}: {}", subpath, e.message, e)
            return DeleteOutcome.Failed("delete failed: ${e.message}")
        }

        // structureSubpaths(), not placedStructureSubpaths(): a place that errored partway leaves a
        // region assignment with no placed box, and that entry must go too.
        val registry = EditorDimRegistry.of(server)
        val doomed = registry.structureSubpaths().filter { it == subpath || it.startsWith("$subpath/") }
        for (doomedSubpath in doomed) {
            // unplaceStructure BEFORE clearBounds, never after: clearBounds writes AIR through the
            // 3-arg level.setBlock, which the setBlock mixin hooks unconditionally, so a
            // still-registered subpath would have those very writes re-marked dirty by the mixin.
            val box = registry.unplaceStructure(doomedSubpath)
            if (box != null) {
                StructurePersistence.clearBounds(registry.projectLevel(), box.origin, box.size)
            }
        }

        val autoSave = StructureAutoSave.of(server)
        for (dirty in autoSave.dirtySubpaths().filter { it == subpath || it.startsWith("$subpath/") }) {
            autoSave.clear(dirty)
            // The backoff map is keyed by subpath too; leaving an entry behind leaks one per delete
            // for the life of the server.
            StructureCommit.clearBackoff(server, dirty)
        }

        EditorSession.clearSessionUnder(player.uuid, subpath)

        bankFailure?.let { return DeleteOutcome.DeletedUnbankable(it) }
        return DeleteOutcome.Deleted(EditorUndoCommand.Delete(subpath, manifest, banked))
    }

    /**
     * Bank one file's current bytes, returning the revision or null on any failure.
     *
     * A `.nbt` goes through the typed path so its revision carries real size metadata (matching
     * what `handlePlaceStructure` banks); anything else goes through `writeRawRevision`. A `.nbt`
     * that fails to parse as NBT falls back to raw bytes rather than being lost — the goal is
     * restorability, not a well-formed structure record.
     */
    private fun bankFile(server: MinecraftServer, file: Path): Revision? {
        val bytes = try { file.readBytes() } catch (e: Exception) {
            LOGGER.error("[project/delete] read for banking '{}': {}", file, e.message, e)
            return null
        }
        if (file.name.endsWith(".nbt", ignoreCase = true)) {
            val tag = runCatching { NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap()) }.getOrNull()
            if (tag != null) {
                val template = StructureTemplate()
                template.load(server.registryAccess().lookupOrThrow(Registries.BLOCK), tag)
                val size = template.size
                return LocalHistoryStore.writeRevision(
                    file, tag, size.x, size.y, size.z,
                    blockCount = 0, reason = LocalHistoryStore.REASON_PRE_DELETE,
                )
            }
        }
        return LocalHistoryStore.writeRawRevision(file, bytes, LocalHistoryStore.REASON_PRE_DELETE)
    }
```

New imports for this file: `com.breadmoirai.garnet.editor.undo.BankedFile`, `.EditorUndoCommand`, `.ManifestEntry`, `com.breadmoirai.garnet.history.Revision`, `net.minecraft.core.registries.Registries`, `net.minecraft.nbt.NbtAccounter`, `net.minecraft.nbt.NbtIo`, `net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate`, `kotlin.io.path.PathWalkOption`, `kotlin.io.path.readBytes`, `kotlin.io.path.walk`.

`Path.walk` is `@ExperimentalPathApi` — annotate `deleteSubtree` with `@OptIn(kotlin.io.path.ExperimentalPathApi::class)`. If the project already opts in globally in `build.gradle.kts`, skip the annotation.

- [ ] **Step 4: Rewrite `handleDelete` to use it**

Keep `handleDelete`'s existing resolution, root-guard, and best-effort `commitDirtyUnder` block exactly as they are. Replace everything after them with:

```kotlin
        when (val outcome = deleteSubtree(server, player, payload.subpath, target)) {
            is DeleteOutcome.Failed -> { fail(player, outcome.reason); return }
            is DeleteOutcome.Deleted -> EditorUndoStack.push(player.uuid, outcome.command)
            is DeleteOutcome.DeletedUnbankable -> {
                // Deleted, but not undoable. Say so rather than leaving an Undo button that would
                // silently restore an incomplete subtree.
                LOGGER.warn("[project/delete] {} is not undoable: {}", payload.subpath, outcome.reason)
                fail(player, "deleted '${payload.subpath}', but it cannot be undone: ${outcome.reason}")
            }
        }

        sendTree(server, player)
        sendUndoState(player)
```

Add `import com.breadmoirai.garnet.editor.network.EditorHandlerSupport.sendUndoState` and `import com.breadmoirai.garnet.editor.undo.EditorUndoStack`.

- [ ] **Step 5: Run the tests to verify they pass**

```bash
cmd.exe /c "cd /d H:\\Repo\\Garnet && gradlew.bat :26.2:runGameTest"
```

Expected: PASS for `EditorUndoNetworkSpec`, and **`EditorFileOpsNetworkSpec` still passes unchanged** — its existing delete tests are the regression guard for this extraction. Read both result XMLs.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/breadmoirai/garnet/editor/network/EditorFileOpsHandlers.kt src/gametest/kotlin/com/breadmoirai/garnet/test/
git commit -m "feat(editor): bank every deleted file so a delete can be undone"
```

---

### Task 5: `restoreSubtree`

The inverse of Task 4.

**Files:**
- Modify: `src/main/kotlin/com/breadmoirai/garnet/editor/network/EditorFileOpsHandlers.kt`
- Test: `src/gametest/kotlin/com/breadmoirai/garnet/test/editor/EditorUndoNetworkSpec.kt`

**Interfaces:**
- Consumes: `EditorUndoCommand.Delete` (Task 2), `LocalHistoryStore.readRawBytes` / `readTag` (Task 1), `deleteSubtree` (Task 4).
- Produces:
  - `data class RestoreReport(val restored: Int, val total: Int, val failures: List<String>)`
  - `EditorFileOpsHandlers.restoreSubtree(server: MinecraftServer, player: ServerPlayer, command: EditorUndoCommand.Delete): RestoreReport`

- [ ] **Step 1: Write the failing tests**

Append to `EditorUndoNetworkSpec.kt`:

```kotlin
    test("restoreSubtree brings back folders, a .spec.kts and a .nbt") {
        withServer { server, player, root ->
            EditorUndoStack.clear(player.uuid)
            root.resolve("redstone/clocks").createDirectories()
            root.resolve("redstone/clocks/a.spec.kts").writeBytes("contents-a".toByteArray())
            val nbtBytes = root.resolve("redstone/g.nbt").let { p ->
                p.writeBytes(byteArrayOf(1, 2, 3, 4)); p.readBytes()
            }

            EditorFileOpsHandlers.handleDelete(server, player, DeletePathC2S("redstone"))
            root.resolve("redstone").exists().shouldBeFalse()

            val command = EditorUndoStack.peekUndo(player.uuid) as EditorUndoCommand.Delete
            val report = EditorFileOpsHandlers.restoreSubtree(server, player, command)

            report.failures.shouldBeEmpty()
            report.restored shouldBe report.total
            root.resolve("redstone/clocks").isDirectory().shouldBeTrue()
            root.resolve("redstone/clocks/a.spec.kts").readBytes() shouldBe "contents-a".toByteArray()
            root.resolve("redstone/g.nbt").readBytes() shouldBe nbtBytes
        }
    }

    test("restoreSubtree recreates an empty folder") {
        withServer { server, player, root ->
            EditorUndoStack.clear(player.uuid)
            root.resolve("empty/inner").createDirectories()

            EditorFileOpsHandlers.handleDelete(server, player, DeletePathC2S("empty"))
            val command = EditorUndoStack.peekUndo(player.uuid) as EditorUndoCommand.Delete
            EditorFileOpsHandlers.restoreSubtree(server, player, command)

            // A folder with no files has nothing banked — the manifest is the only record it
            // existed, which is why the manifest carries folders separately from banked files.
            root.resolve("empty/inner").isDirectory().shouldBeTrue()
        }
    }

    test("restoreSubtree reports a partial restore when a blob is gone") {
        withServer { server, player, root ->
            EditorUndoStack.clear(player.uuid)
            root.resolve("part").createDirectories()
            root.resolve("part/a.spec.kts").writeBytes("a".toByteArray())
            root.resolve("part/b.spec.kts").writeBytes("b".toByteArray())

            EditorFileOpsHandlers.handleDelete(server, player, DeletePathC2S("part"))
            val command = EditorUndoStack.peekUndo(player.uuid) as EditorUndoCommand.Delete

            // Destroy one banked blob behind the store's back, simulating a prune or a wiped
            // history directory between the delete and the undo.
            val victim = command.banked.first()
            LocalHistoryStore.dirFor(victim.absolutePath).resolve(victim.revision.file).deleteExisting()

            val report = EditorFileOpsHandlers.restoreSubtree(server, player, command)

            report.restored shouldBe 1
            report.total shouldBe 2
            report.failures shouldHaveSize 1
        }
    }
```

- [ ] **Step 2: Run to verify it fails**

```bash
cmd.exe /c "cd /d H:\\Repo\\Garnet && gradlew.bat :26.2:gametestClasses"
```

Expected: FAIL to compile — unresolved reference `restoreSubtree`.

- [ ] **Step 3: Implement**

Add to `EditorFileOpsHandlers.kt`:

```kotlin
/** Outcome of [EditorFileOpsHandlers.restoreSubtree]. [failures] are already phrased for a player. */
data class RestoreReport(val restored: Int, val total: Int, val failures: List<String>)
```

```kotlin
    /**
     * Recreate a subtree deleted by [deleteSubtree], from its manifest and banked revisions.
     *
     * Folders come from the manifest, files from `LocalHistoryStore`. Both are needed: a folder with
     * no files banks nothing, so the manifest is the only evidence it ever existed.
     *
     * Paths are resolved against the CURRENT project root via `BankedFile.relPath`, not against
     * `BankedFile.absolutePath` — the root is swappable, so the path a file had at delete time is
     * not necessarily where it belongs now. `absolutePath` is used only as the history key, which
     * is exactly what it is.
     *
     * There is no rollback. A partial restore is reported honestly rather than undone: the
     * alternative is deleting files the player just got back.
     *
     * Restored structures come back UNPLACED — this touches no `EditorDimRegistry` state. Placing an
     * arbitrary restored subtree would need a region assignment per `.nbt`, and navigating to a
     * structure costs the same either way.
     */
    internal fun restoreSubtree(
        server: MinecraftServer,
        player: ServerPlayer,
        command: EditorUndoCommand.Delete,
    ): RestoreReport {
        val root = EditorRootResolver.rootFor(server)
            ?: return RestoreReport(0, command.banked.size, listOf("project-root not configured"))
        // resolveSubpath refuses a path that does not exist, and the whole point here is that it
        // does not — so resolve the PARENT (which does exist) and rebuild beneath it.
        val parentSubpath = command.rootSubpath.substringBeforeLast('/', "")
        val parent = root.resolveSubpath(parentSubpath)
            ?: return RestoreReport(0, command.banked.size, listOf("parent folder is gone: $parentSubpath"))
        val base = parent.resolve(command.rootSubpath.substringAfterLast('/'))

        val failures = mutableListOf<String>()

        // Shortest relPath first, so a parent directory always exists before its children.
        for (entry in command.manifest.filter { it.isFolder }.sortedBy { it.relPath.length }) {
            val dir = if (entry.relPath.isEmpty()) base else base.resolve(entry.relPath)
            try {
                dir.createDirectories()
            } catch (e: Exception) {
                LOGGER.error("[project/undo] recreate folder '{}': {}", dir, e.message, e)
                failures.add("could not recreate folder '${entry.relPath}'")
            }
        }

        var restored = 0
        for (file in command.banked) {
            val destination = if (file.relPath.isEmpty()) base else base.resolve(file.relPath)
            val raw = LocalHistoryStore.readRawBytes(file.absolutePath, file.revision)
            try {
                destination.parent?.createDirectories()
                if (raw != null) {
                    destination.writeBytes(raw)
                } else {
                    // Not a raw revision, so it is a typed structure revision — write it back as
                    // compressed NBT. Writing the wrapper tag here instead would corrupt the .nbt.
                    val tag = LocalHistoryStore.readTag(file.absolutePath, file.revision)
                        ?: error("revision blob is missing")
                    NbtIo.writeCompressed(tag, destination)
                }
                restored++
            } catch (e: Exception) {
                LOGGER.error("[project/undo] restore '{}': {}", destination, e.message, e)
                failures.add("could not restore '${file.relPath.ifEmpty { command.rootSubpath }}'")
            }
        }

        return RestoreReport(restored, command.banked.size, failures)
    }
```

Add imports `kotlin.io.path.writeBytes` and `com.breadmoirai.garnet.editor.world.EditorRootResolver` if not already present.

- [ ] **Step 4: Run the tests to verify they pass**

```bash
cmd.exe /c "cd /d H:\\Repo\\Garnet && gradlew.bat :26.2:runGameTest"
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/breadmoirai/garnet/editor/network/EditorFileOpsHandlers.kt src/gametest/kotlin/com/breadmoirai/garnet/test/
git commit -m "feat(editor): restore a deleted subtree from its manifest and revisions"
```

---

### Task 6: Record commands on the remaining five operations

Delete already records (Task 4). This wires the other six payloads onto five command types.

**Files:**
- Modify: `src/main/kotlin/com/breadmoirai/garnet/editor/network/EditorFileOpsHandlers.kt`
- Modify: `src/main/kotlin/com/breadmoirai/garnet/editor/network/EditorStructureHandlers.kt`
- Modify: `src/main/kotlin/com/breadmoirai/garnet/editor/network/EditorTreeHandlers.kt`
- Test: `src/gametest/kotlin/com/breadmoirai/garnet/test/editor/EditorUndoNetworkSpec.kt`

**Interfaces:**
- Consumes: everything from Tasks 2–3.
- Produces: `relocate` changes from `private` to `internal` and gains a `kind: RelocateKind` parameter. Its full signature becomes:
  `internal fun relocate(server: MinecraftServer, player: ServerPlayer, oldSubpath: String, source: Path, target: Path, newSubpath: String, operation: String, placedMessage: String, kind: RelocateKind, record: Boolean = true)`

  `record = false` is used by undo in Task 7, which must move the node back without recording that move as a fresh action.

- [ ] **Step 1: Write the failing tests**

Append to `EditorUndoNetworkSpec.kt`:

```kotlin
    test("handleCreateFolder records a CreateFolder command") {
        withServer { server, player, _ ->
            EditorUndoStack.clear(player.uuid)
            EditorFileOpsHandlers.handleCreateFolder(server, player, CreateFolderC2S("", "toplevel"))
            EditorUndoStack.peekUndo(player.uuid) shouldBe EditorUndoCommand.CreateFolder("toplevel")
        }
    }

    test("handleNewStructure records a CreateFile command with the resolved name") {
        withServer { server, player, root ->
            EditorUndoStack.clear(player.uuid)
            root.resolve("redstone").createDirectories()
            EditorStructureHandlers.handleNewStructure(server, player, NewStructureC2S("redstone", "gadget"))
            // The handler appends ".nbt" itself, so the command must record the RESOLVED name —
            // recording the requested one would make undo look for a file that does not exist.
            EditorUndoStack.peekUndo(player.uuid) shouldBe
                EditorUndoCommand.CreateFile("redstone/gadget.nbt", CreatedFileKind.STRUCTURE)
        }
    }

    test("handleDuplicate records the server-derived copy name") {
        withServer { server, player, root ->
            EditorUndoStack.clear(player.uuid)
            root.resolve("gadget.nbt").writeBytes(byteArrayOf(1))
            EditorFileOpsHandlers.handleDuplicate(server, player, DuplicatePathC2S("gadget.nbt"))

            val command = EditorUndoStack.peekUndo(player.uuid)
            command.shouldBeInstanceOf<EditorUndoCommand.Duplicate>()
            // Whatever EditorNames.duplicateName chose, the command must name the file that now
            // exists — this is the case the store-the-packet approach could not express.
            root.resolve(command.createdSubpath).exists().shouldBeTrue()
            command.createdSubpath shouldNotBe "gadget.nbt"
        }
    }

    test("handleRename records a RENAME relocate") {
        withServer { server, player, root ->
            EditorUndoStack.clear(player.uuid)
            root.resolve("redstone").createDirectories()
            EditorFileOpsHandlers.handleRename(server, player, RenamePathC2S("redstone", "logic"))
            EditorUndoStack.peekUndo(player.uuid) shouldBe
                EditorUndoCommand.Relocate("redstone", "logic", RelocateKind.RENAME)
        }
    }

    test("handleMove records a MOVE relocate") {
        withServer { server, player, root ->
            EditorUndoStack.clear(player.uuid)
            root.resolve("a").createDirectories()
            root.resolve("dest").createDirectories()
            EditorFileOpsHandlers.handleMove(server, player, MovePathC2S("a", "dest"))
            EditorUndoStack.peekUndo(player.uuid) shouldBe
                EditorUndoCommand.Relocate("a", "dest/a", RelocateKind.MOVE)
        }
    }

    test("a rejected rename records nothing") {
        withServer { server, player, root ->
            EditorUndoStack.clear(player.uuid)
            root.resolve("a").createDirectories()
            root.resolve("b").createDirectories()
            EditorFileOpsHandlers.handleRename(server, player, RenamePathC2S("a", "b"))
            EditorUndoStack.peekUndo(player.uuid).shouldBeNull()
        }
    }
```

Import `io.kotest.matchers.shouldNotBe` and the payload/enum types used.

- [ ] **Step 2: Run to verify it fails**

```bash
cmd.exe /c "cd /d H:\\Repo\\Garnet && gradlew.bat :26.2:runGameTest"
```

Expected: FAIL — every new assertion sees a null top-of-stack.

- [ ] **Step 3: Record in `EditorFileOpsHandlers`**

In `handleCreateFolder`, replace the trailing `sendTree(server, player)` with:

```kotlin
        EditorUndoStack.push(player.uuid, EditorUndoCommand.CreateFolder(
            if (payload.parentSubpath.isEmpty()) name else "${payload.parentSubpath}/$name",
        ))
        sendTree(server, player)
        sendUndoState(player)
```

In `handleDuplicate`, replace the trailing `sendTree(server, player)` with:

```kotlin
        val createdSubpath = if (payload.subpath.contains('/')) {
            "${payload.subpath.substringBeforeLast('/')}/$newName"
        } else newName
        EditorUndoStack.push(player.uuid, EditorUndoCommand.Duplicate(createdSubpath))
        sendTree(server, player)
        sendUndoState(player)
```

Change `relocate`'s signature to the one in **Interfaces** above, and replace its trailing `sendTree(server, player)` with:

```kotlin
        if (record) {
            EditorUndoStack.push(player.uuid, EditorUndoCommand.Relocate(oldSubpath, newSubpath, kind))
        }
        sendTree(server, player)
        sendUndoState(player)
```

Pass `kind = RelocateKind.RENAME` from `handleRename` and `kind = RelocateKind.MOVE` from `handleMove`.

`handleMove`'s early "already there" branch (`currentParentSubpath == payload.destFolderSubpath`) must keep returning after `sendTree` **without** pushing — nothing moved, so there is nothing to undo.

- [ ] **Step 4: Record in the other two handler objects**

`EditorStructureHandlers.handleNewStructure` — replace its trailing `sendTree(server, player)`:

```kotlin
        EditorUndoStack.push(player.uuid, EditorUndoCommand.CreateFile(
            if (payload.parentSubpath.isEmpty()) finalName else "${payload.parentSubpath}/$finalName",
            CreatedFileKind.STRUCTURE,
        ))
        sendTree(server, player)
        sendUndoState(player)
```

`EditorTreeHandlers.handleNewSpec` — after the `EditorDimLifecycle.placeFolder` call succeeds and before the `EditorFolderLoadedS2C` send. `EditorNewSpec.create` derives the on-disk filename, so read that function and record the name it actually produced, not `payload.name`:

```kotlin
        EditorUndoStack.push(player.uuid, EditorUndoCommand.CreateFile(
            if (activeSubpath.isEmpty()) createdFileName else "$activeSubpath/$createdFileName",
            CreatedFileKind.SPEC,
        ))
        sendUndoState(player)
```

If `EditorNewSpec.create` does not return the created name, change it to return the created `Path` and derive `createdFileName` from `it.name`. Recording a guessed filename here is the same bug class as recording `DuplicatePathC2S`'s source name.

Add the matching imports to all three files.

- [ ] **Step 5: Run the tests to verify they pass**

```bash
cmd.exe /c "cd /d H:\\Repo\\Garnet && gradlew.bat :26.2:runGameTest"
```

Expected: PASS for `EditorUndoNetworkSpec`, and `EditorFileOpsNetworkSpec` / `EditorStructureNetworkSpec` still pass.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/breadmoirai/garnet/editor/
git commit -m "feat(editor): record every mutating file operation on the undo stack"
```

---

### Task 7: `EditorUndoOps` — preconditions, inverses, and the receivers

**Files:**
- Create: `src/main/kotlin/com/breadmoirai/garnet/editor/undo/EditorUndoOps.kt`
- Modify: `src/main/kotlin/com/breadmoirai/garnet/editor/network/EditorNetworkRegistry.kt`
- Test: `src/gametest/kotlin/com/breadmoirai/garnet/test/editor/EditorUndoNetworkSpec.kt`

**Interfaces:**
- Consumes: `deleteSubtree`, `restoreSubtree`, `relocate` (Tasks 4–6); `EditorUndoStack` (Task 2); `sendUndoState` (Task 3).
- Produces: `EditorUndoOps.undo(server: MinecraftServer, player: ServerPlayer)` and `EditorUndoOps.redo(server: MinecraftServer, player: ServerPlayer)`.

- [ ] **Step 1: Write the failing tests**

Append to `EditorUndoNetworkSpec.kt`:

```kotlin
    test("undo of a create folder removes it; redo puts it back") {
        withServer { server, player, root ->
            EditorUndoStack.clear(player.uuid)
            EditorFileOpsHandlers.handleCreateFolder(server, player, CreateFolderC2S("", "toplevel"))

            EditorUndoOps.undo(server, player)
            root.resolve("toplevel").exists().shouldBeFalse()

            EditorUndoOps.redo(server, player)
            root.resolve("toplevel").isDirectory().shouldBeTrue()
        }
    }

    test("undo of a rename moves it back") {
        withServer { server, player, root ->
            EditorUndoStack.clear(player.uuid)
            root.resolve("redstone").createDirectories()
            EditorFileOpsHandlers.handleRename(server, player, RenamePathC2S("redstone", "logic"))

            EditorUndoOps.undo(server, player)

            root.resolve("redstone").isDirectory().shouldBeTrue()
            root.resolve("logic").exists().shouldBeFalse()
        }
    }

    test("undo of a move restores the original parent") {
        withServer { server, player, root ->
            EditorUndoStack.clear(player.uuid)
            root.resolve("a").createDirectories()
            root.resolve("dest").createDirectories()
            EditorFileOpsHandlers.handleMove(server, player, MovePathC2S("a", "dest"))

            EditorUndoOps.undo(server, player)

            root.resolve("a").isDirectory().shouldBeTrue()
            root.resolve("dest/a").exists().shouldBeFalse()
        }
    }

    test("undo of a duplicate removes the copy, not the original") {
        withServer { server, player, root ->
            EditorUndoStack.clear(player.uuid)
            root.resolve("gadget.nbt").writeBytes(byteArrayOf(1))
            EditorFileOpsHandlers.handleDuplicate(server, player, DuplicatePathC2S("gadget.nbt"))
            val copy = (EditorUndoStack.peekUndo(player.uuid) as EditorUndoCommand.Duplicate).createdSubpath

            EditorUndoOps.undo(server, player)

            root.resolve(copy).exists().shouldBeFalse()
            root.resolve("gadget.nbt").exists().shouldBeTrue()
        }
    }

    test("undo of a delete restores the whole subtree") {
        withServer { server, player, root ->
            EditorUndoStack.clear(player.uuid)
            root.resolve("redstone/clocks").createDirectories()
            root.resolve("redstone/clocks/a.spec.kts").writeBytes("a".toByteArray())
            EditorFileOpsHandlers.handleDelete(server, player, DeletePathC2S("redstone"))

            EditorUndoOps.undo(server, player)

            root.resolve("redstone/clocks/a.spec.kts").readBytes() shouldBe "a".toByteArray()
        }
    }

    test("undo refuses and keeps the entry when the node moved underneath it") {
        withServer { server, player, root ->
            EditorUndoStack.clear(player.uuid)
            root.resolve("redstone").createDirectories()
            EditorFileOpsHandlers.handleRename(server, player, RenamePathC2S("redstone", "logic"))
            drainPayloads(player)

            // Someone else moves it out from under the pending undo.
            root.resolve("logic").moveTo(root.resolve("elsewhere"))

            EditorUndoOps.undo(server, player)

            drainPayloads(player).filterIsInstance<EditorErrorS2C>().shouldNotBeEmpty()
            // The entry SURVIVES: the player can retry once the conflict is resolved, rather than
            // silently skipping to an older action they did not ask to undo.
            EditorUndoStack.peekUndo(player.uuid) shouldBe
                EditorUndoCommand.Relocate("redstone", "logic", RelocateKind.RENAME)
        }
    }

    test("undo refuses when the delete's path is occupied again") {
        withServer { server, player, root ->
            EditorUndoStack.clear(player.uuid)
            root.resolve("gone.spec.kts").writeBytes("x".toByteArray())
            EditorFileOpsHandlers.handleDelete(server, player, DeletePathC2S("gone.spec.kts"))
            root.resolve("gone.spec.kts").writeBytes("something else".toByteArray())
            drainPayloads(player)

            EditorUndoOps.undo(server, player)

            drainPayloads(player).filterIsInstance<EditorErrorS2C>().shouldNotBeEmpty()
            // Never clobber content that arrived after the delete.
            root.resolve("gone.spec.kts").readBytes() shouldBe "something else".toByteArray()
        }
    }

    test("undo with an empty stack reports an error and does nothing") {
        withServer { server, player, _ ->
            EditorUndoStack.clear(player.uuid)
            drainPayloads(player)
            EditorUndoOps.undo(server, player)
            drainPayloads(player).filterIsInstance<EditorErrorS2C>().shouldNotBeEmpty()
        }
    }

    test("a rename of a placed structure survives undo still placed") {
        withServer { server, player, root ->
            EditorUndoStack.clear(player.uuid)
            EditorStructureHandlers.handleNewStructure(server, player, NewStructureC2S("", "gadget"))
            EditorStructureHandlers.handlePlaceStructure(server, player, PlaceStructureC2S("gadget.nbt"))
            EditorFileOpsHandlers.handleRename(server, player, RenamePathC2S("gadget.nbt", "widget.nbt"))
            EditorDimRegistry.of(server).placedBoxOf("widget.nbt").shouldNotBeNull()

            EditorUndoOps.undo(server, player)

            // relocate re-places and re-keys; undo goes through the same function, so this is the
            // guard that undo did not take a shortcut around it.
            EditorDimRegistry.of(server).placedBoxOf("gadget.nbt").shouldNotBeNull()
            EditorDimRegistry.of(server).placedBoxOf("widget.nbt").shouldBeNull()
        }
    }
```

Import `kotlin.io.path.moveTo` and `com.breadmoirai.garnet.editor.undo.EditorUndoOps`.

- [ ] **Step 2: Run to verify it fails**

```bash
cmd.exe /c "cd /d H:\\Repo\\Garnet && gradlew.bat :26.2:gametestClasses"
```

Expected: FAIL to compile — unresolved reference `EditorUndoOps`.

- [ ] **Step 3: Implement `EditorUndoOps`**

```kotlin
package com.breadmoirai.garnet.editor.undo

import com.breadmoirai.garnet.editor.network.DeleteOutcome
import com.breadmoirai.garnet.editor.network.EditorFileOpsHandlers
import com.breadmoirai.garnet.editor.network.EditorHandlerSupport.fail
import com.breadmoirai.garnet.editor.network.EditorHandlerSupport.sendTree
import com.breadmoirai.garnet.editor.network.EditorHandlerSupport.sendUndoState
import com.breadmoirai.garnet.editor.world.EditorDimLifecycle
import com.breadmoirai.garnet.editor.world.EditorRootResolver
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import org.slf4j.LoggerFactory
import kotlin.io.path.exists

private val LOGGER = LoggerFactory.getLogger("Garnet")

/**
 * Replays a command's inverse (undo) or the command itself (redo).
 *
 * Every inverse goes through `EditorFileOpsHandlers`' primitives — `relocate`, `deleteSubtree`,
 * `restoreSubtree` — never through hand-rolled file IO. Those functions carry the ordering rules
 * this feature must not relearn: quiesce before relocating, unplace before `clearBounds`, rekey the
 * registry, repoint the session, carry history across.
 *
 * A stale entry is REFUSED and left on the stack. Per-player stacks sit over shared server state,
 * so an entry can be invalidated by anyone; refusing lets the player retry after resolving the
 * conflict, where discarding would silently skip to an older action.
 */
object EditorUndoOps {

    /** What an inverse produced: a failure reason, or the command to seat on the redo deque. */
    private sealed interface Inverted {
        data class Refused(val reason: String) : Inverted
        /** [redoable] is usually the original command; creates return a copy carrying their bank. */
        data class Applied(val redoable: EditorUndoCommand) : Inverted
    }

    fun undo(server: MinecraftServer, player: ServerPlayer) {
        val command = EditorUndoStack.peekUndo(player.uuid) ?: run {
            fail(player, "nothing to undo"); return
        }
        when (val result = applyInverse(server, player, command)) {
            is Inverted.Refused -> {
                // The entry stays put — see this object's KDoc.
                fail(player, "can't undo ${command.label} — ${result.reason}")
                return
            }
            is Inverted.Applied -> {
                EditorUndoStack.popUndo(player.uuid)
                EditorUndoStack.pushRedo(player.uuid, result.redoable)
            }
        }
        sendTree(server, player)
        sendUndoState(player)
    }

    fun redo(server: MinecraftServer, player: ServerPlayer) {
        val command = EditorUndoStack.peekRedo(player.uuid) ?: run {
            fail(player, "nothing to redo"); return
        }
        val problem = reapply(server, player, command)
        if (problem != null) {
            fail(player, "can't redo ${command.label} — $problem")
            return
        }
        EditorUndoStack.popRedo(player.uuid)
        // push() would clear the redo deque, discarding every entry above this one. This is a
        // replay, not a new action, so the redo branch must survive.
        EditorUndoStack.pushUndoWithoutClearingRedo(player.uuid, command)
        sendTree(server, player)
        sendUndoState(player)
    }

    private fun applyInverse(
        server: MinecraftServer,
        player: ServerPlayer,
        command: EditorUndoCommand,
    ): Inverted = when (command) {
        is EditorUndoCommand.CreateFolder ->
            when (val removed = removeCreated(server, player, command.subpath)) {
                is Removed.Refused -> Inverted.Refused(removed.reason)
                is Removed.Gone -> Inverted.Applied(command.copy(banked = removed.banked))
            }

        is EditorUndoCommand.CreateFile ->
            when (val removed = removeCreated(server, player, command.subpath)) {
                is Removed.Refused -> Inverted.Refused(removed.reason)
                is Removed.Gone -> {
                    // A spec's creation re-placed its parent folder in the world, so removing the
                    // spec must re-place it again — otherwise the folder keeps showing a spec whose
                    // file no longer exists. Structures are not placed by their create handler and
                    // need nothing here.
                    if (command.kind == CreatedFileKind.SPEC) replaceFolderOf(server, command.subpath)
                    Inverted.Applied(command.copy(banked = removed.banked))
                }
            }

        is EditorUndoCommand.Duplicate ->
            when (val removed = removeCreated(server, player, command.createdSubpath)) {
                is Removed.Refused -> Inverted.Refused(removed.reason)
                is Removed.Gone -> Inverted.Applied(command.copy(banked = removed.banked))
            }

        is EditorUndoCommand.Relocate ->
            moveBack(server, player, from = command.newSubpath, to = command.oldSubpath, command = command)

        is EditorUndoCommand.Delete -> restore(server, player, command)
    }

    /** Null on success, else the reason phrased as a clause following "can't redo X — ". */
    private fun reapply(
        server: MinecraftServer,
        player: ServerPlayer,
        command: EditorUndoCommand,
    ): String? = when (command) {
        // A create cannot be replayed as a create — the content came from a create handler, not
        // from anything this command recorded. It is replayed as a RESTORE of the bank the undo
        // took on its way out, which is exactly what `banked` is for.
        is EditorUndoCommand.CreateFolder -> restoreBanked(server, player, command.banked)
        is EditorUndoCommand.CreateFile -> restoreBanked(server, player, command.banked).also { problem ->
            // On SUCCESS only (problem == null): the spec file is back, so the folder must be
            // re-placed to show it again. `?.also` here would be exactly backwards — it fires on
            // failure.
            if (problem == null && command.kind == CreatedFileKind.SPEC) {
                replaceFolderOf(server, command.subpath)
            }
        }
        is EditorUndoCommand.Duplicate -> restoreBanked(server, player, command.banked)

        is EditorUndoCommand.Relocate ->
            when (val result = moveBack(server, player, from = command.oldSubpath, to = command.newSubpath, command = command)) {
                is Inverted.Refused -> result.reason
                is Inverted.Applied -> null
            }

        is EditorUndoCommand.Delete -> {
            val root = EditorRootResolver.rootFor(server)
            val target = root?.resolveSubpath(command.rootSubpath)
            if (target == null) "'${command.rootSubpath}' is already gone"
            else when (val outcome = EditorFileOpsHandlers.deleteSubtree(server, player, command.rootSubpath, target)) {
                is DeleteOutcome.Failed -> outcome.reason
                else -> null
            }
        }
    }

    private fun restoreBanked(
        server: MinecraftServer,
        player: ServerPlayer,
        banked: EditorUndoCommand.Delete?,
    ): String? {
        if (banked == null) return "the removal could not be banked, so it cannot be redone"
        return when (val result = restore(server, player, banked)) {
            is Inverted.Refused -> result.reason
            is Inverted.Applied -> null
        }
    }

    /** Outcome of removing a node an undone create had produced. */
    private sealed interface Removed {
        data class Refused(val reason: String) : Removed
        /** [banked] is null when the removal could not be banked — redo will be unavailable. */
        data class Gone(val banked: EditorUndoCommand.Delete?) : Removed
    }

    private fun removeCreated(server: MinecraftServer, player: ServerPlayer, subpath: String): Removed {
        val root = EditorRootResolver.rootFor(server) ?: return Removed.Refused("project-root not configured")
        val target = root.resolveSubpath(subpath) ?: return Removed.Refused("'$subpath' is no longer there")
        return when (val outcome = EditorFileOpsHandlers.deleteSubtree(server, player, subpath, target)) {
            is DeleteOutcome.Failed -> Removed.Refused(outcome.reason)
            is DeleteOutcome.Deleted -> Removed.Gone(outcome.command)
            // Removed, but unbankable. The undo itself succeeded — the player asked for the created
            // node to go away and it did — so this is not a refusal. Only redo is lost.
            is DeleteOutcome.DeletedUnbankable -> Removed.Gone(null)
        }
    }

    /**
     * Re-place the folder containing [subpath], so the world matches the files again after a spec
     * appears or disappears. Failures are logged by `placeFolder` itself and must not fail the
     * undo: the file operation already happened.
     */
    private fun replaceFolderOf(server: MinecraftServer, subpath: String) {
        val root = EditorRootResolver.rootFor(server) ?: return
        val folderSubpath = subpath.substringBeforeLast('/', "")
        runCatching { EditorDimLifecycle.placeFolder(server, root, folderSubpath) }
            .onFailure { LOGGER.error("[project/undo] re-place '{}': {}", folderSubpath, it.message, it) }
    }

    private fun moveBack(
        server: MinecraftServer,
        player: ServerPlayer,
        from: String,
        to: String,
        command: EditorUndoCommand.Relocate,
    ): Inverted {
        val root = EditorRootResolver.rootFor(server)
            ?: return Inverted.Refused("project-root not configured")
        val source = root.resolveSubpath(from)
            ?: return Inverted.Refused("'$from' moved or was deleted since")
        val parentSubpath = to.substringBeforeLast('/', "")
        val parent = root.resolveSubpath(parentSubpath)
            ?: return Inverted.Refused("'$parentSubpath' no longer exists")
        val target = parent.resolve(to.substringAfterLast('/'))
        if (target.exists()) return Inverted.Refused("'$to' is occupied again")

        EditorFileOpsHandlers.relocate(
            server, player,
            oldSubpath = from,
            source = source,
            target = target,
            newSubpath = to,
            operation = if (command.kind == RelocateKind.RENAME) "undo rename" else "undo move",
            placedMessage = "restored to $to",
            kind = command.kind,
            // The stack is managed by undo()/redo() above; recording here would push a duplicate
            // entry for a move the player did not perform.
            record = false,
        )
        return Inverted.Applied(command)
    }

    private fun restore(
        server: MinecraftServer,
        player: ServerPlayer,
        command: EditorUndoCommand.Delete,
    ): Inverted {
        val root = EditorRootResolver.rootFor(server)
            ?: return Inverted.Refused("project-root not configured")
        // The precondition: the path must still be free. Restoring over content that arrived after
        // the delete would destroy work nobody asked to lose.
        if (root.resolveSubpath(command.rootSubpath) != null) {
            return Inverted.Refused("'${command.rootSubpath}' exists again")
        }
        val report = EditorFileOpsHandlers.restoreSubtree(server, player, command)
        if (report.failures.isNotEmpty()) {
            // Partial restores are NOT rolled back — deleting what was just recovered would be
            // worse. Report honestly and let the entry be consumed, since the tree has changed.
            fail(player, "restored ${report.restored} of ${report.total} files: ${report.failures.joinToString("; ")}")
        }
        return Inverted.Applied(command)
    }
}
```

Add to `EditorUndoStack` the function `redo` needs:

```kotlin
    /**
     * Re-seat a redone command on the undo deque WITHOUT clearing redo. [push] clears it, which is
     * right for a new action and wrong for a replay — clearing here would discard every redo entry
     * above the one just consumed.
     */
    fun pushUndoWithoutClearingRedo(playerId: UUID, command: EditorUndoCommand) {
        val state = of(playerId)
        synchronized(state) {
            state.undo.addLast(command)
            while (state.undo.size > MAX_DEPTH) state.undo.removeFirst()
        }
    }
```

- [ ] **Step 4: Wire the receivers**

In `EditorNetworkRegistry.register()`:

```kotlin
        ServerPlayNetworking.registerGlobalReceiver(UndoC2S.TYPE) { _, ctx ->
            ctx.server().execute { EditorUndoOps.undo(ctx.server(), ctx.player()) }
        }
        ServerPlayNetworking.registerGlobalReceiver(RedoC2S.TYPE) { _, ctx ->
            ctx.server().execute { EditorUndoOps.redo(ctx.server(), ctx.player()) }
        }
```

- [ ] **Step 5: Run the tests to verify they pass**

```bash
cmd.exe /c "cd /d H:\\Repo\\Garnet && gradlew.bat :26.2:runGameTest"
```

Expected: PASS for every test in `EditorUndoNetworkSpec`, and the pre-existing `EditorFileOpsNetworkSpec` / `EditorStructureNetworkSpec` still pass.

Two things to check specifically in the output, because they are the subtle ones:

- **Redo of a create round-trips.** It works by restoring the bank the undo took on its way out (`Removed.Gone(banked)` → `command.copy(banked = …)` → `restoreBanked`), not by re-running the create handler. If the folder comes back empty when it should have contents, the bank was not stapled onto the redo entry.
- **Undoing a spec creation re-places its folder.** `replaceFolderOf` is what keeps the world from showing a spec whose file is gone.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/breadmoirai/garnet/editor/ src/gametest/kotlin/com/breadmoirai/garnet/test/editor/EditorUndoNetworkSpec.kt
git commit -m "feat(editor): undo and redo Explorer file operations"
```

---

### Task 8: Client toolbar buttons

**Files:**
- Create: `src/client/kotlin/com/breadmoirai/garnet/editor/ui/UndoState.kt`
- Modify: `src/client/kotlin/com/breadmoirai/garnet/editor/network/EditorClientNetworking.kt`
- Modify: `src/client/kotlin/com/breadmoirai/garnet/editor/ui/ExplorerToolbar.kt`
- Modify: `src/client/kotlin/com/breadmoirai/garnet/editor/ui/ExplorerLifecycle.kt`
- Test: `src/clientTest/kotlin/com/breadmoirai/garnet/test/ExplorerUiSpec.kt`

**Interfaces:**
- Consumes: `UndoStateS2C`, `UndoC2S.INSTANCE`, `RedoC2S.INSTANCE` (Task 3).
- Produces: `UndoState.undoLabel: String?`, `UndoState.redoLabel: String?`, `UndoState.onUndoState(payload: UndoStateS2C)`, `UndoState.reset()`.

- [ ] **Step 1: Write the failing test**

Append to `ExplorerUiSpec.kt`, matching whatever spec style that file already uses:

```kotlin
    test("UndoState mirrors the server's labels") {
        UndoState.reset()
        UndoState.onUndoState(UndoStateS2C("delete 'a.nbt'", null))
        UndoState.undoLabel shouldBe "delete 'a.nbt'"
        UndoState.redoLabel.shouldBeNull()
    }

    test("UndoState.reset clears both labels") {
        UndoState.onUndoState(UndoStateS2C("x", "y"))
        UndoState.reset()
        // Cleared on disconnect so a rejoin never shows an enabled button backed by a server
        // stack that was dropped with the connection.
        UndoState.undoLabel.shouldBeNull()
        UndoState.redoLabel.shouldBeNull()
    }
```

- [ ] **Step 2: Run to verify it fails**

```bash
cmd.exe /c "cd /d H:\\Repo\\Garnet && gradlew.bat :26.2:clientTestClasses"
```

Expected: FAIL to compile — unresolved reference `UndoState`.

- [ ] **Step 3: Implement `UndoState`**

`src/client/kotlin/com/breadmoirai/garnet/editor/ui/UndoState.kt`:

```kotlin
package com.breadmoirai.garnet.editor.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.breadmoirai.garnet.editor.network.UndoStateS2C

/**
 * Client mirror of the server's per-player undo/redo availability.
 *
 * The client never derives this itself — it has no stack. A null label means the corresponding
 * toolbar button is disabled.
 */
object UndoState {
    var undoLabel by mutableStateOf<String?>(null)
        private set
    var redoLabel by mutableStateOf<String?>(null)
        private set

    fun onUndoState(payload: UndoStateS2C) {
        undoLabel = payload.undoLabel
        redoLabel = payload.redoLabel
    }

    fun reset() {
        undoLabel = null
        redoLabel = null
    }
}
```

- [ ] **Step 4: Wire the receiver and the lifecycle reset**

In `EditorClientNetworking.register()`:

```kotlin
        ClientPlayNetworking.registerGlobalReceiver(UndoStateS2C.TYPE) { payload, ctx ->
            ctx.client().execute { UndoState.onUndoState(payload) }
        }
```

In `ExplorerLifecycle.registerExplorerLifecycle()`'s DISCONNECT handler, beside the existing resets:

```kotlin
            UndoState.reset()
```

- [ ] **Step 5: Add the toolbar buttons**

In `ExplorerToolbar.kt`, before the existing Refresh button:

```kotlin
        IconButton(
            onClick = { ClientPlayNetworking.send(UndoC2S.INSTANCE) },
            enabled = UndoState.undoLabel != null,
        ) {
            Icon(
                AllIconsKeys.Actions.Undo,
                contentDescription = UndoState.undoLabel?.let { "Undo $it" } ?: "Undo",
            )
        }
        IconButton(
            onClick = { ClientPlayNetworking.send(RedoC2S.INSTANCE) },
            enabled = UndoState.redoLabel != null,
        ) {
            Icon(
                AllIconsKeys.Actions.Redo,
                contentDescription = UndoState.redoLabel?.let { "Redo $it" } ?: "Redo",
            )
        }
```

Send `INSTANCE`, never `UndoC2S()` — the constructor is private for exactly this reason.

If `IconButton` has no `enabled` parameter in this Jewel version, gate the `onClick` body on the label being non-null instead and leave the icon rendered; do not silently drop the disabled state without noting it in the commit message.

- [ ] **Step 6: Verify compilation across all five source sets and run the client test**

```bash
cmd.exe /c "cd /d H:\\Repo\\Garnet && gradlew.bat :26.2:clientClasses :26.2:classes :26.2:gametestClasses :26.2:clientTestClasses :26.2:testClasses"
cmd.exe /c "cd /d H:\\Repo\\Garnet && gradlew.bat :26.2:runClientTest"
```

Expected: compiles clean; `ExplorerUiSpec` passes.

- [ ] **Step 7: Commit**

```bash
git add src/client/kotlin/com/breadmoirai/garnet/editor/ src/clientTest/kotlin/com/breadmoirai/garnet/test/ExplorerUiSpec.kt
git commit -m "feat(explorer): undo and redo toolbar buttons"
```

---

### Task 9: Documentation

Mandatory per `CLAUDE.md` — a source change is not complete until the docs are audited.

**Files:**
- Create: `docs/persistence/editor-undo-stack.md`
- Modify: `docs/persistence/INDEX.md`, `docs/persistence/local-history.md`, `docs/persistence/network-payload-contract.md`

- [ ] **Step 1: Write the new article**

`docs/persistence/editor-undo-stack.md`, with the standard frontmatter:

```markdown
---
title: Explorer undo/redo command stack
tags: [editor, undo, history, networking, persistence]
summary: Why the undo stack stores server-authored EditorUndoCommand records rather than the C2S packets, how a delete is made reversible by banking every file, and why a stale entry is refused rather than discarded.
---
```

Cover, in this order: why storing packets fails (the `Duplicate` name and the `Delete` manifest); the five command types and their inverses; that undo reuses `relocate` / `deleteSubtree` / `restoreSubtree` rather than reimplementing their ordering rules; the per-player, in-memory, `EditorSession`-shaped lifetime; the refuse-and-keep staleness rule and why discarding is worse; and that a restored structure comes back unplaced.

- [ ] **Step 2: Register it and update the three affected articles**

Add to `docs/persistence/INDEX.md`:

```markdown
- [Explorer undo/redo command stack](editor-undo-stack.md) — Why the undo stack stores server-authored `EditorUndoCommand` records rather than the C2S packets, how a delete is made reversible by banking every file, and why a stale entry is refused rather than discarded. Tags: editor, undo, history, networking, persistence
```

In `docs/persistence/local-history.md`: document `REASON_PRE_DELETE`, `writeRawRevision` / `readRawBytes` and the wrapper-tag format, and the zero-size caveat (a raw revision reports `sizeX/Y/Z` and `blockCount` as 0 — read that as "not a structure snapshot", never "an empty structure"). The article currently says a delete *retains* history; strengthen it to say a delete now *adds* to history, for every file type.

In `docs/persistence/network-payload-contract.md`: add `UndoC2S` / `RedoC2S` to the `StreamCodec.unit` singleton list, add `UndoStateS2C` to the optional-string idiom list, and note under Invariant 2 that undo carries **no** client-supplied path at all — the client sends a bare request and the server decides what it means, so the resolve-subpath boundary is enforced on the recorded paths at replay time.

- [ ] **Step 3: Verify every cross-reference resolves**

```bash
grep -rn "editor-undo-stack" docs/
grep -rn "REASON_PRE_DELETE\|writeRawRevision" docs/
```

Expected: the INDEX entry and the article resolve; no dangling links.

- [ ] **Step 4: Commit**

```bash
git add docs/persistence/
git commit -m "docs: cover the Explorer undo/redo command stack"
```

---

## Final Verification

- [ ] **All five source sets compile**

```bash
cmd.exe /c "cd /d H:\\Repo\\Garnet && gradlew.bat :26.2:clientClasses :26.2:classes :26.2:gametestClasses :26.2:clientTestClasses :26.2:testClasses"
```

- [ ] **Unit, gametest, and clientTest suites pass**

```bash
cmd.exe /c "cd /d H:\\Repo\\Garnet && gradlew.bat :26.2:test"
cmd.exe /c "cd /d H:\\Repo\\Garnet && gradlew.bat :26.2:runGameTest"
cmd.exe /c "cd /d H:\\Repo\\Garnet && gradlew.bat :26.2:runClientTest"
```

Read the XMLs under `versions/26.2/build/test-results/`. Confirm specifically that the pre-existing `EditorFileOpsNetworkSpec`, `EditorStructureNetworkSpec`, and `LocalHistoryStoreSpec` still pass — Tasks 1, 4, and 6 all modify code those specs cover.
