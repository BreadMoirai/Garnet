# Explorer Duplicate, Delete, and Move Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add Duplicate, Delete, and Move operations to the Project Explorer, server and client, with test coverage matching the existing rename operation's depth.

**Architecture:** Three new C2S packets dispatch to `EditorFileOpsHandlers`. The commit-dirty-state-first loop currently inlined in `handleRename` is extracted to `EditorHandlerSupport.commitDirtyUnder` and reused by all three. Move and rename collapse onto a shared `relocate` core, since a rename is already a move that happens to keep the same parent. On the client, three context-menu items feed one panel-scoped dialog host rendering two contents (delete confirmation, move target picker).

**Tech Stack:** Kotlin, Fabric (Minecraft 26.2, single Stonecutter version), Jewel/Compose Desktop for the dock UI, Kotest for all three test source sets.

**Spec:** `docs/superpowers/specs/2026-08-11-explorer-duplicate-delete-move-design.md`

## Global Constraints

- **Gradle must be invoked through the Windows batch wrapper**, never `./gradlew`: `cmd.exe /c "cd /d H:\\Repo\\Garnet && gradlew.bat <tasks>"`. See `docs/tooling/wsl2-gradle-invocation.md`.
- **The Stonecutter version prefix is `:26.2:`** on every Gradle task. It is the only version in `versions/`.
- **`--tests` filtering does not select Kotest specs.** Run the task unfiltered and read `versions/26.2/build/test-results/test/TEST-<FQCN>.xml`. See `docs/tooling/local-verification-commands.md`.
- **Compile verification spans five source sets**: `:26.2:clientClasses :26.2:classes :26.2:gametestClasses :26.2:clientTestClasses :26.2:testClasses`. `compileKotlin` alone covers only `main`.
- **Every client-supplied subpath must go through `EditorRoot.resolveSubpath` and reject `null`.** That is the path-traversal boundary; there is no other guard.
- **The project root is the empty subpath `""`** (`ExplorerTreeState.ROOT_PATH`). All three operations refuse it.
- **Handlers run on the server thread** via `ctx.server().execute`, as does `StructureCommit.tick`. Ordering within a handler is atomic against ticks.
- **Do not add a `Co-Authored-By` trailer** to any commit.

---

### Task 1: `EditorNames.duplicateName`

Pure name derivation, no IO. Lands first because Task 3 consumes it.

**Files:**
- Modify: `src/main/kotlin/com/breadmoirai/garnet/editor/data/EditorNames.kt`
- Test: `src/test/kotlin/com/breadmoirai/garnet/editor/data/EditorNamesTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces: `EditorNames.duplicateName(sourceName: String, siblings: Collection<String>, isFolder: Boolean): String`

- [ ] **Step 1: Write the failing tests**

Append to `EditorNamesTest.kt`, inside the existing `FunSpec({ ... })` block:

```kotlin
    test("duplicateName appends ' copy' before the extension") {
        EditorNames.duplicateName("house.nbt", listOf("house.nbt"), isFolder = false) shouldBe "house copy.nbt"
    }

    test("duplicateName counts up from 2 once ' copy' is taken") {
        EditorNames.duplicateName(
            "house.nbt", listOf("house.nbt", "house copy.nbt"), isFolder = false,
        ) shouldBe "house copy 2.nbt"
        EditorNames.duplicateName(
            "house.nbt", listOf("house.nbt", "house copy.nbt", "house copy 2.nbt"), isFolder = false,
        ) shouldBe "house copy 3.nbt"
    }

    test("duplicateName matches siblings case-insensitively, like validate") {
        // A copy that only differs from an existing sibling by case would be rejected by validate()
        // a moment later on the very filesystems (NTFS, APFS) this project runs on.
        EditorNames.duplicateName(
            "house.nbt", listOf("house.nbt", "HOUSE COPY.NBT"), isFolder = false,
        ) shouldBe "house copy 2.nbt"
    }

    test("duplicateName treats a folder's dots as part of its name, not an extension") {
        EditorNames.duplicateName("redstone", listOf("redstone"), isFolder = true) shouldBe "redstone copy"
        EditorNames.duplicateName("my.stuff", listOf("my.stuff"), isFolder = true) shouldBe "my.stuff copy"
    }

    test("duplicateName treats a leading dot as part of the name, not an extension") {
        EditorNames.duplicateName(".gitignore", listOf(".gitignore"), isFolder = false) shouldBe ".gitignore copy"
    }

    test("duplicateName produces a name validate accepts") {
        val name = EditorNames.duplicateName("house.nbt", listOf("house.nbt"), isFolder = false)
        EditorNames.validate(name, listOf("house.nbt")) shouldBe null
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

```sh
cmd.exe /c "cd /d H:\\Repo\\Garnet && gradlew.bat :26.2:testClasses"
```

Expected: FAIL — compilation error, `Unresolved reference: duplicateName`.

- [ ] **Step 3: Implement `duplicateName`**

Add to `EditorNames`, below `resolveFinalName`:

```kotlin
    /**
     * The name a duplicate of [sourceName] should take among [siblings]: `house.nbt` →
     * `house copy.nbt` → `house copy 2.nbt`, counting up until the name is free.
     *
     * The suffix goes BEFORE the last dot so a duplicated structure is still a `.nbt` the Explorer
     * will place — "house.nbt copy" would be an inert file the tree renders as an unknown type.
     *
     * [isFolder] exists because a folder has no extension to preserve, and folder names may
     * legitimately contain dots ("my.stuff"): splitting those on the last dot would produce
     * "my copy.stuff". The caller knows which it has — the server from `isDirectory()`, the client
     * from the node type — so this never has to guess from the name's shape.
     *
     * Collision matching is case-insensitive, matching [validate]: a candidate differing from an
     * existing sibling only by case would be rejected by [validate] a moment later on the
     * case-insensitive filesystems this project runs on (NTFS, APFS).
     */
    fun duplicateName(sourceName: String, siblings: Collection<String>, isFolder: Boolean): String {
        // dot > 0, not >= 0: a leading dot (".gitignore") names the file, it does not introduce an
        // extension, so such a name must keep its dot in the stem.
        val dot = if (isFolder) -1 else sourceName.lastIndexOf('.')
        val stem = if (dot > 0) sourceName.substring(0, dot) else sourceName
        val extension = if (dot > 0) sourceName.substring(dot) else ""

        val taken = siblings.mapTo(HashSet()) { it.lowercase() }
        var candidate = "$stem copy$extension"
        var counter = 2
        while (candidate.lowercase() in taken) {
            candidate = "$stem copy $counter$extension"
            counter++
        }
        return candidate
    }
```

- [ ] **Step 4: Run the tests to verify they pass**

```sh
cmd.exe /c "cd /d H:\\Repo\\Garnet && gradlew.bat :26.2:test"
```

Expected: PASS. Confirm in `versions/26.2/build/test-results/test/TEST-com.breadmoirai.garnet.editor.data.EditorNamesTest.xml` — `failures="0" errors="0"`, and the six new test names present.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/breadmoirai/garnet/editor/data/EditorNames.kt src/test/kotlin/com/breadmoirai/garnet/editor/data/EditorNamesTest.kt
git commit -m "feat(editor): derive duplicate names in EditorNames"
```

---

### Task 2: Extract `commitDirtyUnder`

Pure refactor. `handleRename`'s fifteen existing gametests are the safety net — no new tests, and no behavior may change. Error strings are preserved exactly, because `handleRename`'s failure test asserts an `EditorErrorS2C` is sent.

**Files:**
- Modify: `src/main/kotlin/com/breadmoirai/garnet/editor/network/EditorHandlerSupport.kt`
- Modify: `src/main/kotlin/com/breadmoirai/garnet/editor/network/EditorFileOpsHandlers.kt:72-105`

**Interfaces:**
- Consumes: `StructureAutoSave.of(server).dirtySubpaths(): Set<String>`, `StructureCommit.commit(server, subpath, reason): CommitOutcome`, `LocalHistoryStore.REASON_AUTOSAVE`.
- Produces: `EditorHandlerSupport.commitDirtyUnder(server: MinecraftServer, subpath: String): String?` — null when quiesced, else the reason (without any operation-name prefix; callers add their own).

- [ ] **Step 1: Add `commitDirtyUnder` to `EditorHandlerSupport`**

Add these imports to `EditorHandlerSupport.kt`:

```kotlin
import com.breadmoirai.garnet.editor.structure.CommitOutcome
import com.breadmoirai.garnet.editor.structure.StructureAutoSave
import com.breadmoirai.garnet.editor.structure.StructureCommit
import com.breadmoirai.garnet.history.LocalHistoryStore
```

Add the function inside `object EditorHandlerSupport`:

```kotlin
    /**
     * Commit every dirty structure at or under [subpath] before an operation relocates or destroys
     * it. Null when everything is quiesced; otherwise the reason the caller should abort, with no
     * operation-name prefix — callers prepend their own ("rename failed: ...").
     *
     * Dirty state is keyed by subpath, so an operation that changes or invalidates a path without
     * quiescing first strands every pending edit beneath it under a key nothing resolves again:
     * `dirtySubpaths()` never empties (permanently defeating `tick()`'s idle fast path), and
     * `commitAll` on BEFORE_SAVE / SERVER_STOPPING cannot resolve the old subpath either, so the
     * edits never reach the `.nbt` at all.
     *
     * A `NotApplicable` outcome whose dirty flag SURVIVED is treated as a failure alongside
     * `Failed`. It means the subpath was dirty but its root or file was momentarily unresolvable, so
     * `commit` correctly left the flag set (see `StructureCommit.commit`'s KDoc, case 2) — and
     * proceeding would strand that entry exactly like an outright failure. A `NotApplicable` that
     * DID clear the flag is fine and falls through.
     */
    fun commitDirtyUnder(server: MinecraftServer, subpath: String): String? {
        val autoSave = StructureAutoSave.of(server)
        val dirtyUnder = autoSave.dirtySubpaths().filter {
            it == subpath || it.startsWith("$subpath/")
        }
        for (dirtySubpath in dirtyUnder) {
            val outcome = StructureCommit.commit(server, dirtySubpath, LocalHistoryStore.REASON_AUTOSAVE)
            if (outcome is CommitOutcome.Failed) {
                return "could not save pending edits for '$dirtySubpath': ${outcome.reason}"
            }
            if (outcome is CommitOutcome.NotApplicable && autoSave.dirtySubpaths().contains(dirtySubpath)) {
                return "pending edits for '$dirtySubpath' are not resolvable right now"
            }
        }
        return null
    }
```

- [ ] **Step 2: Replace the inlined loop in `handleRename`**

In `EditorFileOpsHandlers.handleRename`, delete the entire block from the comment beginning `// Commit every dirty structure under the renamed path BEFORE the move` through the closing brace of the `for (dirtySubpath in dirtyUnderRename)` loop (currently lines 72–105), and replace it with:

```kotlin
        // Quiesce before the move: see EditorHandlerSupport.commitDirtyUnder for why a relocation
        // that skips this strands every pending edit beneath the renamed path.
        commitDirtyUnder(server, payload.subpath)?.let {
            fail(player, "rename failed: $it"); return
        }
```

Add `import com.breadmoirai.garnet.editor.network.EditorHandlerSupport.commitDirtyUnder` alongside the existing `EditorHandlerSupport.*` imports at the top of the file. Then remove the now-unused imports: `CommitOutcome`, `StructureAutoSave`, `StructureCommit`, and `LocalHistoryStore` — but **only if nothing else in the file still references them.** Grep before deleting:

```sh
grep -n "CommitOutcome\|StructureAutoSave\|StructureCommit\|LocalHistoryStore" src/main/kotlin/com/breadmoirai/garnet/editor/network/EditorFileOpsHandlers.kt
```

- [ ] **Step 3: Compile all source sets**

```sh
cmd.exe /c "cd /d H:\\Repo\\Garnet && gradlew.bat :26.2:clientClasses :26.2:classes :26.2:gametestClasses :26.2:clientTestClasses :26.2:testClasses"
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Run the gametests to prove rename is unchanged**

```sh
cmd.exe /c "cd /d H:\\Repo\\Garnet && gradlew.bat :26.2:runGameTest"
```

Expected: PASS, with all fifteen existing `EditorFileOpsNetworkSpec` rename tests green — in particular "a failed commit during rename aborts the rename entirely and reports an error", which is the one that exercises the extracted loop's failure path.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/breadmoirai/garnet/editor/network/EditorHandlerSupport.kt src/main/kotlin/com/breadmoirai/garnet/editor/network/EditorFileOpsHandlers.kt
git commit -m "refactor(editor): extract commitDirtyUnder from handleRename"
```

---

### Task 3: Duplicate — packet, handler, gametests

**Files:**
- Modify: `src/main/kotlin/com/breadmoirai/garnet/editor/network/EditorPackets.kt`
- Modify: `src/main/kotlin/com/breadmoirai/garnet/editor/network/EditorNetworkRegistry.kt`
- Modify: `src/main/kotlin/com/breadmoirai/garnet/editor/network/EditorFileOpsHandlers.kt`
- Test: `src/gametest/kotlin/com/breadmoirai/garnet/test/editor/EditorFileOpsNetworkSpec.kt`

**Interfaces:**
- Consumes: `EditorNames.duplicateName` (Task 1), `EditorHandlerSupport.commitDirtyUnder` (Task 2), `EditorHandlerSupport.siblingNames(folder: Path): List<String>`, `EditorRootResolver.rootFor(server)?.resolveSubpath(subpath): Path?`.
- Produces: `DuplicatePathC2S(subpath: String)`, `EditorFileOpsHandlers.handleDuplicate(server, player, payload)`.

- [ ] **Step 1: Write the failing gametests**

Add to `EditorFileOpsNetworkSpec.kt` inside the `GarnetTestSpec({ ... })` block. Add these imports at the top of the file:

```kotlin
import com.breadmoirai.garnet.editor.network.DuplicatePathC2S
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes
```

```kotlin
    test("handleDuplicate copies a structure to a ' copy' sibling") {
        withServer { server, player, root ->
            EditorNewStructure.create(root, "clock")

            EditorFileOpsHandlers.handleDuplicate(server, player, DuplicatePathC2S("clock.nbt"))

            root.resolve("clock.nbt").exists().shouldBeTrue()
            root.resolve("clock copy.nbt").exists().shouldBeTrue()
            root.resolve("clock copy.nbt").readBytes() shouldBe root.resolve("clock.nbt").readBytes()
        }
    }

    test("handleDuplicate counts up when the copy name is taken") {
        withServer { server, player, root ->
            EditorNewStructure.create(root, "clock")
            EditorFileOpsHandlers.handleDuplicate(server, player, DuplicatePathC2S("clock.nbt"))
            EditorFileOpsHandlers.handleDuplicate(server, player, DuplicatePathC2S("clock.nbt"))

            root.resolve("clock copy.nbt").exists().shouldBeTrue()
            root.resolve("clock copy 2.nbt").exists().shouldBeTrue()
        }
    }

    test("handleDuplicate copies a folder subtree") {
        withServer { server, player, root ->
            root.resolve("redstone/clocks").createDirectories()
            EditorNewStructure.create(root.resolve("redstone/clocks"), "tick")

            EditorFileOpsHandlers.handleDuplicate(server, player, DuplicatePathC2S("redstone"))

            root.resolve("redstone copy").isDirectory().shouldBeTrue()
            root.resolve("redstone copy/clocks/tick.nbt").exists().shouldBeTrue()
            root.resolve("redstone/clocks/tick.nbt").exists().shouldBeTrue()   // source untouched
        }
    }

    test("handleDuplicate refuses the project root") {
        withServer { server, player, root ->
            EditorFileOpsHandlers.handleDuplicate(server, player, DuplicatePathC2S(""))
            drainPayloads(player).filterIsInstance<EditorErrorS2C>() shouldHaveSize 1
        }
    }

    test("handleDuplicate rejects a subpath that escapes the root") {
        withServer { server, player, root ->
            // The escape target must genuinely EXIST — resolveSubpath checks exists() before
            // containment, so a non-existent target would pass this test even with containment
            // deleted outright. Same reasoning as the handleCreateFolder escape test above.
            val evil = root.resolveSibling("evil").also { it.createDirectories() }
            evil.resolve("secret.nbt").writeBytes(ByteArray(4))

            EditorFileOpsHandlers.handleDuplicate(server, player, DuplicatePathC2S("../evil/secret.nbt"))

            evil.resolve("secret copy.nbt").exists().shouldBeFalse()
            root.resolve("secret copy.nbt").exists().shouldBeFalse()
        }
    }

    test("duplicating a dirty placed structure captures its in-world edits, not stale disk content") {
        // The whole reason handleDuplicate quiesces first. Without the commit, the copy is made from
        // the .nbt as it sat before the edit, and the player gets a silently stale duplicate.
        withServer { server, player, root ->
            EditorNewStructure.create(root, "clock")
            EditorStructureHandlers.handlePlaceStructure(server, player, PlaceStructureC2S("clock.nbt"))
            drainPayloads(player)

            val registry = EditorDimRegistry.of(server)
            val origin = registry.structureRegionOriginOf("clock.nbt").shouldNotBeNull()
            val level = registry.projectLevel()
            level.setBlock(origin, Blocks.STONE.defaultBlockState(), 3)
            StructureEditWatcher.onBlockChanged(level, origin)
            StructureAutoSave.of(server).isDirty("clock.nbt").shouldBeTrue()

            EditorFileOpsHandlers.handleDuplicate(server, player, DuplicatePathC2S("clock.nbt"))

            // The commit ran, so the source is clean and both files now hold the edited structure.
            StructureAutoSave.of(server).isDirty("clock.nbt").shouldBeFalse()
            root.resolve("clock copy.nbt").readBytes() shouldBe root.resolve("clock.nbt").readBytes()
        }
    }

    test("handleDuplicate neither places the copy nor assigns it a region") {
        withServer { server, player, root ->
            EditorNewStructure.create(root, "clock")
            EditorStructureHandlers.handlePlaceStructure(server, player, PlaceStructureC2S("clock.nbt"))

            EditorFileOpsHandlers.handleDuplicate(server, player, DuplicatePathC2S("clock.nbt"))

            val registry = EditorDimRegistry.of(server)
            registry.placedBoxOf("clock copy.nbt").shouldBeNull()
            registry.structureRegionOriginOf("clock copy.nbt").shouldBeNull()
            registry.placedBoxOf("clock.nbt").shouldNotBeNull()   // the source is still placed
        }
    }

    test("a failed commit aborts the duplicate rather than copying stale content") {
        // The counterpart to the delete case, and the opposite call: a duplicate made from a .nbt
        // that does not match the world is a silently wrong answer the player has no way to spot,
        // so duplicate ABORTS where delete proceeds.
        withServer { server, player, root ->
            EditorNewStructure.create(root, "clock")
            EditorStructureHandlers.handlePlaceStructure(server, player, PlaceStructureC2S("clock.nbt"))
            drainPayloads(player)

            val registry = EditorDimRegistry.of(server)
            val origin = registry.structureRegionOriginOf("clock.nbt").shouldNotBeNull()
            val level = registry.projectLevel()
            level.setBlock(origin, Blocks.STONE.defaultBlockState(), 3)
            StructureEditWatcher.onBlockChanged(level, origin)

            val file = root.resolve("clock.nbt")
            val historyDir = LocalHistoryStore.dirFor(file)
            historyDir.resolve("index.json").deleteIfExists()
            historyDir.resolve("index.json").createDirectory()

            try {
                EditorFileOpsHandlers.handleDuplicate(server, player, DuplicatePathC2S("clock.nbt"))

                root.resolve("clock copy.nbt").exists().shouldBeFalse()
                drainPayloads(player).filterIsInstance<EditorErrorS2C>() shouldHaveSize 1
            } finally {
                StructureAutoSave.of(server).clear("clock.nbt")
                StructureCommit.clearBackoff(server, "clock.nbt")
                historyDir.resolve("index.json").toFile().delete()
            }
        }
    }

    test("a duplicate starts with no local history and leaves the source's alone") {
        withServer { server, player, root ->
            EditorNewStructure.create(root, "clock")
            // Placing seeds a REASON_PLACED baseline revision on the source.
            EditorStructureHandlers.handlePlaceStructure(server, player, PlaceStructureC2S("clock.nbt"))
            LocalHistoryStore.revisions(root.resolve("clock.nbt")).shouldNotBeEmpty()

            EditorFileOpsHandlers.handleDuplicate(server, player, DuplicatePathC2S("clock.nbt"))

            LocalHistoryStore.revisions(root.resolve("clock copy.nbt")).shouldBeEmpty()
            LocalHistoryStore.revisions(root.resolve("clock.nbt")).shouldNotBeEmpty()
        }
    }
```

Add these Kotest matcher imports:

```kotlin
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
```

- [ ] **Step 2: Run to verify they fail**

```sh
cmd.exe /c "cd /d H:\\Repo\\Garnet && gradlew.bat :26.2:gametestClasses"
```

Expected: FAIL — `Unresolved reference: DuplicatePathC2S`, `Unresolved reference: handleDuplicate`.

- [ ] **Step 3: Add the packet**

In `EditorPackets.kt`, after `RenamePathC2S`:

```kotlin
/**
 * Duplicate the file or folder at [subpath] beside itself. Carries no name: only the server sees the
 * real filesystem, so it derives the deduplicated name itself rather than trusting a client whose
 * tree snapshot may be stale.
 */
data class DuplicatePathC2S(val subpath: String) : CustomPacketPayload {
    companion object {
        val TYPE = CustomPacketPayload.Type<DuplicatePathC2S>(id("duplicate_path"))
        val STREAM_CODEC: StreamCodec<ByteBuf, DuplicatePathC2S> = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, DuplicatePathC2S::subpath,
            ::DuplicatePathC2S,
        )
    }
    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE
}
```

- [ ] **Step 4: Register it**

In `EditorNetworkRegistry.register()`, alongside the other serverbound registrations:

```kotlin
        PayloadTypeRegistry.serverboundPlay().register(DuplicatePathC2S.TYPE, DuplicatePathC2S.STREAM_CODEC)
```

and alongside the other receivers:

```kotlin
        ServerPlayNetworking.registerGlobalReceiver(DuplicatePathC2S.TYPE) { payload, ctx ->
            ctx.server().execute { EditorFileOpsHandlers.handleDuplicate(ctx.server(), ctx.player(), payload) }
        }
```

- [ ] **Step 5: Implement the handler**

In `EditorFileOpsHandlers`, after `handleRename`. Add imports `kotlin.io.path.copyTo`, `kotlin.io.path.isDirectory`, and `com.breadmoirai.garnet.editor.data.EditorNames`:

```kotlin
    /**
     * Copy the node at [payload].subpath beside itself under a deduplicated name.
     *
     * Much simpler than [handleRename] because nothing in the world is keyed to a path that did not
     * exist a moment ago: no registry entry to rekey, no placed box to tear down, no session to
     * repoint. The copy is NOT placed and starts with no local history — `LocalHistoryStore` keys
     * revisions by absolute path, so it inherits nothing, and cloning the source's revisions would
     * claim an edit history the copy never had.
     */
    fun handleDuplicate(server: MinecraftServer, player: ServerPlayer, payload: DuplicatePathC2S) {
        if (payload.subpath.isEmpty()) {
            fail(player, "cannot duplicate the project root"); return
        }
        val root = EditorRootResolver.rootFor(server) ?: run {
            fail(player, "project-root not configured"); return
        }
        val source = root.resolveSubpath(payload.subpath) ?: run {
            fail(player, "path not found or escapes root: ${payload.subpath}"); return
        }

        // Quiesce BEFORE reading the bytes, so the copy reflects the structure as it stands in the
        // world rather than the .nbt as it sat before the current edit. A failed commit aborts:
        // producing a silently stale duplicate is worse than producing none.
        commitDirtyUnder(server, payload.subpath)?.let {
            fail(player, "duplicate failed: $it"); return
        }

        val parent = source.parent
        val newName = EditorNames.duplicateName(
            sourceName = source.name,
            siblings = siblingNames(parent),
            isFolder = source.isDirectory(),
        )
        val target = parent.resolve(newName)

        try {
            if (source.isDirectory()) source.toFile().copyRecursively(target.toFile(), overwrite = false)
            else source.copyTo(target)
        } catch (e: Exception) {
            LOGGER.error("[project/duplicate] {} -> {}: {}", payload.subpath, newName, e.message, e)
            fail(player, "duplicate failed: ${e.message}"); return
        }

        sendTree(server, player)
    }
```

- [ ] **Step 6: Run the gametests**

```sh
cmd.exe /c "cd /d H:\\Repo\\Garnet && gradlew.bat :26.2:runGameTest"
```

Expected: PASS, including all nine new duplicate tests and the fifteen pre-existing ones.

- [ ] **Step 7: Commit**

```bash
git add src/main/kotlin/com/breadmoirai/garnet/editor/network/ src/gametest/kotlin/com/breadmoirai/garnet/test/editor/EditorFileOpsNetworkSpec.kt
git commit -m "feat(editor): duplicate a structure or folder from the Explorer"
```

---

### Task 4: `EditorSession.clearSessionUnder`

Delete needs to drop an active session pointing at a path that no longer exists. `repointSession` rewrites onto a new path; delete has no new path.

**Files:**
- Modify: `src/main/kotlin/com/breadmoirai/garnet/editor/data/EditorSession.kt`
- Test: `src/test/kotlin/com/breadmoirai/garnet/editor/data/EditorSessionTest.kt`

**Interfaces:**
- Consumes: `EditorSession.setActive(playerId: UUID, subpath: String?)`, `EditorSession.get(playerId): EditorSession?`.
- Produces: `EditorSession.clearSessionUnder(playerId: UUID, deletedSubpath: String)`.

Note: this takes a `UUID`, not a `ServerPlayer` like `repointSession` does — the existing unit test file has no `ServerPlayer` available, and the handler has `player.uuid` on hand either way.

- [ ] **Step 1: Write the failing tests**

Append inside `EditorSessionTest.kt`'s existing spec block:

```kotlin
    test("clearSessionUnder clears an active subpath equal to the deleted path") {
        val id = UUID.randomUUID()
        EditorSession.setActive(id, "redstone")
        EditorSession.clearSessionUnder(id, "redstone")
        EditorSession.get(id)!!.activeSubpath shouldBe null
    }

    test("clearSessionUnder clears an active subpath nested under the deleted path") {
        val id = UUID.randomUUID()
        EditorSession.setActive(id, "redstone/clocks")
        EditorSession.clearSessionUnder(id, "redstone")
        EditorSession.get(id)!!.activeSubpath shouldBe null
    }

    test("clearSessionUnder leaves an unrelated active subpath alone") {
        val id = UUID.randomUUID()
        EditorSession.setActive(id, "adders")
        EditorSession.clearSessionUnder(id, "redstone")
        EditorSession.get(id)!!.activeSubpath shouldBe "adders"
    }

    test("clearSessionUnder matches on a full path segment, not a string prefix") {
        // Deleting "redstone" must not clear a session in the SIBLING folder "redstoneworks".
        val id = UUID.randomUUID()
        EditorSession.setActive(id, "redstoneworks/clocks")
        EditorSession.clearSessionUnder(id, "redstone")
        EditorSession.get(id)!!.activeSubpath shouldBe "redstoneworks/clocks"
    }
```

Ensure `import java.util.UUID` and `io.kotest.matchers.shouldBe` are present in the file.

- [ ] **Step 2: Run to verify they fail**

```sh
cmd.exe /c "cd /d H:\\Repo\\Garnet && gradlew.bat :26.2:testClasses"
```

Expected: FAIL — `Unresolved reference: clearSessionUnder`.

- [ ] **Step 3: Implement it**

In `EditorSession`'s companion object, next to `repointSession`:

```kotlin
        /**
         * Drop an active folder that no longer exists: an activeSubpath equal to [deletedSubpath],
         * or nested under it, becomes null. The delete counterpart to [repointSession], which has a
         * new path to rewrite onto where a delete has none.
         *
         * The boundary is a full path segment, matching [repointSession] and
         * `EditorDimRegistry.rekeyForRename`: deleting "redstone" must clear a session in
         * "redstone/clocks" but never one in the sibling "redstoneworks".
         */
        fun clearSessionUnder(playerId: UUID, deletedSubpath: String) {
            val active = get(playerId)?.activeSubpath ?: return
            if (active == deletedSubpath || active.startsWith("$deletedSubpath/")) {
                setActive(playerId, null)
            }
        }
```

- [ ] **Step 4: Run to verify they pass**

```sh
cmd.exe /c "cd /d H:\\Repo\\Garnet && gradlew.bat :26.2:test"
```

Expected: PASS. Confirm `failures="0" errors="0"` in `versions/26.2/build/test-results/test/TEST-com.breadmoirai.garnet.editor.data.EditorSessionTest.xml`.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/breadmoirai/garnet/editor/data/EditorSession.kt src/test/kotlin/com/breadmoirai/garnet/editor/data/EditorSessionTest.kt
git commit -m "feat(editor): clear an active session under a deleted path"
```

---

### Task 5: Delete — packet, handler, gametests

**Files:**
- Modify: `src/main/kotlin/com/breadmoirai/garnet/editor/network/EditorPackets.kt`
- Modify: `src/main/kotlin/com/breadmoirai/garnet/editor/network/EditorNetworkRegistry.kt`
- Modify: `src/main/kotlin/com/breadmoirai/garnet/editor/network/EditorFileOpsHandlers.kt`
- Test: `src/gametest/kotlin/com/breadmoirai/garnet/test/editor/EditorFileOpsNetworkSpec.kt`

**Interfaces:**
- Consumes: `EditorHandlerSupport.commitDirtyUnder` (Task 2), `EditorSession.clearSessionUnder` (Task 4), `EditorDimRegistry.structureSubpaths(): Set<String>`, `EditorDimRegistry.unplaceStructure(subpath): PlacedBox?`, `PlacedBox(origin: BlockPos, size: Vec3i)`, `StructurePersistence.clearBounds(level, originPos, bounds)`, `StructureAutoSave.of(server).clear(subpath)`, `StructureCommit.clearBackoff(server, subpath)`.
- Produces: `DeletePathC2S(subpath: String)`, `EditorFileOpsHandlers.handleDelete(server, player, payload)`.

- [ ] **Step 1: Write the failing gametests**

Add to `EditorFileOpsNetworkSpec.kt`. Add import `com.breadmoirai.garnet.editor.network.DeletePathC2S`.

```kotlin
    test("handleDelete removes a structure file") {
        withServer { server, player, root ->
            EditorNewStructure.create(root, "clock")
            EditorFileOpsHandlers.handleDelete(server, player, DeletePathC2S("clock.nbt"))
            root.resolve("clock.nbt").exists().shouldBeFalse()
        }
    }

    test("handleDelete removes a folder subtree") {
        withServer { server, player, root ->
            root.resolve("redstone/clocks").createDirectories()
            EditorNewStructure.create(root.resolve("redstone/clocks"), "tick")

            EditorFileOpsHandlers.handleDelete(server, player, DeletePathC2S("redstone"))

            root.resolve("redstone").exists().shouldBeFalse()
        }
    }

    test("handleDelete refuses the project root") {
        withServer { server, player, root ->
            EditorNewStructure.create(root, "clock")
            EditorFileOpsHandlers.handleDelete(server, player, DeletePathC2S(""))
            root.resolve("clock.nbt").exists().shouldBeTrue()
            drainPayloads(player).filterIsInstance<EditorErrorS2C>() shouldHaveSize 1
        }
    }

    test("handleDelete rejects a subpath that escapes the root") {
        withServer { server, player, root ->
            val evil = root.resolveSibling("evil").also { it.createDirectories() }
            evil.resolve("secret.nbt").writeBytes(ByteArray(4))

            EditorFileOpsHandlers.handleDelete(server, player, DeletePathC2S("../evil/secret.nbt"))

            evil.resolve("secret.nbt").exists().shouldBeTrue()
        }
    }

    test("deleting a placed structure unplaces it and drops its region assignment") {
        withServer { server, player, root ->
            EditorNewStructure.create(root, "clock")
            EditorStructureHandlers.handlePlaceStructure(server, player, PlaceStructureC2S("clock.nbt"))
            val registry = EditorDimRegistry.of(server)
            registry.placedBoxOf("clock.nbt").shouldNotBeNull()

            EditorFileOpsHandlers.handleDelete(server, player, DeletePathC2S("clock.nbt"))

            registry.placedBoxOf("clock.nbt").shouldBeNull()
            registry.structureRegionOriginOf("clock.nbt").shouldBeNull()
        }
    }

    test("deleting a dirty structure clears its dirty state so tick cannot recreate the file") {
        // The core hazard: a subpath left in StructureAutoSave after its file is gone makes
        // StructureCommit.tick retry on EVERY tick forever -- either failing repeatedly or writing
        // the .nbt back out, resurrecting the file the player just deleted.
        withServer { server, player, root ->
            EditorNewStructure.create(root, "clock")
            EditorStructureHandlers.handlePlaceStructure(server, player, PlaceStructureC2S("clock.nbt"))
            drainPayloads(player)

            val registry = EditorDimRegistry.of(server)
            val origin = registry.structureRegionOriginOf("clock.nbt").shouldNotBeNull()
            val level = registry.projectLevel()
            level.setBlock(origin, Blocks.STONE.defaultBlockState(), 3)
            StructureEditWatcher.onBlockChanged(level, origin)
            StructureAutoSave.of(server).isDirty("clock.nbt").shouldBeTrue()

            EditorFileOpsHandlers.handleDelete(server, player, DeletePathC2S("clock.nbt"))

            StructureAutoSave.of(server).isDirty("clock.nbt").shouldBeFalse()
            StructureAutoSave.of(server).dirtySubpaths().contains("clock.nbt").shouldBeFalse()

            // commitAll is what BEFORE_SAVE / SERVER_STOPPING run; it must not resurrect the file.
            StructureCommit.commitAll(server)
            root.resolve("clock.nbt").exists().shouldBeFalse()
        }
    }

    test("deleting a folder clears dirty state for a structure nested inside it") {
        withServer { server, player, root ->
            root.resolve("redstone").createDirectories()
            EditorNewStructure.create(root.resolve("redstone"), "clock")
            EditorStructureHandlers.handlePlaceStructure(server, player, PlaceStructureC2S("redstone/clock.nbt"))
            drainPayloads(player)

            val registry = EditorDimRegistry.of(server)
            val origin = registry.structureRegionOriginOf("redstone/clock.nbt").shouldNotBeNull()
            val level = registry.projectLevel()
            level.setBlock(origin, Blocks.STONE.defaultBlockState(), 3)
            StructureEditWatcher.onBlockChanged(level, origin)

            EditorFileOpsHandlers.handleDelete(server, player, DeletePathC2S("redstone"))

            StructureAutoSave.of(server).isDirty("redstone/clock.nbt").shouldBeFalse()
            registry.placedBoxOf("redstone/clock.nbt").shouldBeNull()
        }
    }

    test("deleting a structure keeps its local history") {
        // History is the recovery route for a delete, so it deliberately outlives the file.
        withServer { server, player, root ->
            EditorNewStructure.create(root, "clock")
            val file = root.resolve("clock.nbt")
            EditorStructureHandlers.handlePlaceStructure(server, player, PlaceStructureC2S("clock.nbt"))
            LocalHistoryStore.revisions(file).shouldNotBeEmpty()

            EditorFileOpsHandlers.handleDelete(server, player, DeletePathC2S("clock.nbt"))

            file.exists().shouldBeFalse()
            LocalHistoryStore.revisions(file).shouldNotBeEmpty()
        }
    }

    test("a delete proceeds even when the pre-delete commit fails") {
        // Delete deliberately DIVERGES from rename here. Quiescing banks a final recovery revision,
        // but blocking the delete when that fails would make a structure with a broken history dir
        // undeletable from the editor -- for a node the player is explicitly destroying.
        withServer { server, player, root ->
            EditorNewStructure.create(root, "clock")
            EditorStructureHandlers.handlePlaceStructure(server, player, PlaceStructureC2S("clock.nbt"))
            drainPayloads(player)

            val registry = EditorDimRegistry.of(server)
            val origin = registry.structureRegionOriginOf("clock.nbt").shouldNotBeNull()
            val level = registry.projectLevel()
            level.setBlock(origin, Blocks.STONE.defaultBlockState(), 3)
            StructureEditWatcher.onBlockChanged(level, origin)

            // Booby-trap the history write exactly as the rename-abort test does: occupy
            // index.json's path with a directory so the commit fails deterministically.
            val file = root.resolve("clock.nbt")
            val historyDir = LocalHistoryStore.dirFor(file)
            historyDir.resolve("index.json").deleteIfExists()
            historyDir.resolve("index.json").createDirectory()

            try {
                EditorFileOpsHandlers.handleDelete(server, player, DeletePathC2S("clock.nbt"))

                file.exists().shouldBeFalse()                                   // deleted anyway
                StructureAutoSave.of(server).isDirty("clock.nbt").shouldBeFalse()
                registry.placedBoxOf("clock.nbt").shouldBeNull()
            } finally {
                StructureAutoSave.of(server).clear("clock.nbt")
                StructureCommit.clearBackoff(server, "clock.nbt")
                historyDir.resolve("index.json").toFile().delete()
            }
        }
    }

    test("a failed delete leaves the world and registry intact") {
        // Mirrors "a failed rename leaves a placed structure's registry state and blocks intact",
        // and shares its platform assumption: RandomAccessFile on Windows does not request
        // share-delete, so the unlink genuinely fails while the handle is held.
        withServer { server, player, root ->
            EditorNewStructure.create(root, "clock")
            EditorStructureHandlers.handlePlaceStructure(server, player, PlaceStructureC2S("clock.nbt"))
            val registry = EditorDimRegistry.of(server)
            registry.placedBoxOf("clock.nbt").shouldNotBeNull()

            val lock = java.io.RandomAccessFile(root.resolve("clock.nbt").toFile(), "rw")
            try {
                EditorFileOpsHandlers.handleDelete(server, player, DeletePathC2S("clock.nbt"))
            } finally {
                lock.close()
            }

            root.resolve("clock.nbt").exists().shouldBeTrue()
            registry.placedBoxOf("clock.nbt").shouldNotBeNull()
            registry.structureRegionOriginOf("clock.nbt").shouldNotBeNull()
        }
    }

    test("deleting an ancestor folder clears the active session") {
        withServer { server, player, root ->
            root.resolve("redstone/clocks").createDirectories()
            EditorSession.setActive(player.uuid, "redstone/clocks")

            EditorFileOpsHandlers.handleDelete(server, player, DeletePathC2S("redstone"))

            EditorSession.get(player.uuid)!!.activeSubpath shouldBe null
        }
    }
```

Verify `StructureCommit.commitAll(server)` matches the real signature before relying on it:

```sh
grep -n "fun commitAll" src/main/kotlin/com/breadmoirai/garnet/editor/structure/StructureCommit.kt
```

If it takes different parameters, adapt the call in the "tick cannot recreate the file" test.

- [ ] **Step 2: Run to verify they fail**

```sh
cmd.exe /c "cd /d H:\\Repo\\Garnet && gradlew.bat :26.2:gametestClasses"
```

Expected: FAIL — `Unresolved reference: DeletePathC2S`, `Unresolved reference: handleDelete`.

- [ ] **Step 3: Add the packet**

In `EditorPackets.kt`, after `DuplicatePathC2S`:

```kotlin
/** Delete the file, or the whole folder subtree, at [subpath]. */
data class DeletePathC2S(val subpath: String) : CustomPacketPayload {
    companion object {
        val TYPE = CustomPacketPayload.Type<DeletePathC2S>(id("delete_path"))
        val STREAM_CODEC: StreamCodec<ByteBuf, DeletePathC2S> = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, DeletePathC2S::subpath,
            ::DeletePathC2S,
        )
    }
    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE
}
```

- [ ] **Step 4: Register it**

```kotlin
        PayloadTypeRegistry.serverboundPlay().register(DeletePathC2S.TYPE, DeletePathC2S.STREAM_CODEC)
```

```kotlin
        ServerPlayNetworking.registerGlobalReceiver(DeletePathC2S.TYPE) { payload, ctx ->
            ctx.server().execute { EditorFileOpsHandlers.handleDelete(ctx.server(), ctx.player(), payload) }
        }
```

- [ ] **Step 5: Implement the handler**

In `EditorFileOpsHandlers`, after `handleDuplicate`. Requires imports `com.breadmoirai.garnet.editor.data.EditorSession`, `com.breadmoirai.garnet.editor.structure.StructureAutoSave`, `com.breadmoirai.garnet.editor.structure.StructureCommit`, `com.breadmoirai.garnet.structure.StructurePersistence`, `kotlin.io.path.deleteExisting`:

```kotlin
    /**
     * Delete the node at [payload].subpath, recursively for a folder.
     *
     * Order is fallible-IO-first, teardown-second, matching [handleRename]: if the unlink fails,
     * nothing has been unplaced and the world is untouched, so the error the player sees is the
     * whole story.
     *
     * The real hazard here is NOT the unlink — it is the leftover dirty entry. A subpath still in
     * `StructureAutoSave` after its file is gone makes `StructureCommit.tick` retry on every tick
     * forever, either failing repeatedly or writing the `.nbt` back out and resurrecting the file
     * the player just deleted. Clearing the subtree's dirty state is the correctness step, not
     * cleanup. This handler and `tick` both run on the server thread, so the window between the
     * unlink and that clear cannot be observed.
     *
     * `LocalHistoryStore` revisions are deliberately RETAINED — they are the recovery route for a
     * delete. The consequence: a file later created at the same path inherits them.
     */
    fun handleDelete(server: MinecraftServer, player: ServerPlayer, payload: DeletePathC2S) {
        if (payload.subpath.isEmpty()) {
            fail(player, "cannot delete the project root"); return
        }
        val root = EditorRootResolver.rootFor(server) ?: run {
            fail(player, "project-root not configured"); return
        }
        val target = root.resolveSubpath(payload.subpath) ?: run {
            fail(player, "path not found or escapes root: ${payload.subpath}"); return
        }

        // Best-effort quiesce: this banks a final recovery revision into the history that outlives
        // the file. Unlike every other caller, a FAILURE here does not abort — blocking a delete
        // because history could not be banked would make a structure with a broken history dir
        // undeletable from the editor, for a node the player is explicitly destroying. Logged, not
        // reported: the delete below succeeds, so an error packet would contradict what happened.
        commitDirtyUnder(server, payload.subpath)?.let {
            LOGGER.warn("[project/delete] proceeding despite failed pre-delete commit for {}: {}", payload.subpath, it)
        }

        try {
            if (target.isDirectory()) {
                // copyRecursively's counterpart returns false rather than throwing on a partial
                // failure, so an unchecked call would silently report success for a subtree that is
                // still half on disk.
                if (!target.toFile().deleteRecursively()) {
                    error("could not delete every entry under ${payload.subpath}")
                }
            } else {
                target.deleteExisting()
            }
        } catch (e: Exception) {
            LOGGER.error("[project/delete] {}: {}", payload.subpath, e.message, e)
            fail(player, "delete failed: ${e.message}"); return
        }

        // structureSubpaths(), not placedStructureSubpaths(): a place that errored partway leaves a
        // region assignment with no placed box, and that entry must go too.
        val registry = EditorDimRegistry.of(server)
        val doomed = registry.structureSubpaths().filter {
            it == payload.subpath || it.startsWith("${payload.subpath}/")
        }
        for (subpath in doomed) {
            // unplaceStructure BEFORE clearBounds, never after: clearBounds writes AIR through the
            // 3-arg level.setBlock, which the setBlock mixin hooks unconditionally, so a
            // still-registered subpath would have those very writes re-marked dirty by the mixin.
            val box = registry.unplaceStructure(subpath)
            if (box != null) {
                StructurePersistence.clearBounds(registry.projectLevel(), box.origin, box.size)
            }
        }

        val autoSave = StructureAutoSave.of(server)
        for (subpath in autoSave.dirtySubpaths().filter {
            it == payload.subpath || it.startsWith("${payload.subpath}/")
        }) {
            autoSave.clear(subpath)
            // The backoff map is keyed by subpath too; leaving an entry behind leaks one per delete
            // for the life of the server.
            StructureCommit.clearBackoff(server, subpath)
        }

        EditorSession.clearSessionUnder(player.uuid, payload.subpath)

        sendTree(server, player)
    }
```

- [ ] **Step 6: Run the gametests**

```sh
cmd.exe /c "cd /d H:\\Repo\\Garnet && gradlew.bat :26.2:runGameTest"
```

Expected: PASS, including all eleven new delete tests.

- [ ] **Step 7: Commit**

```bash
git add src/main/kotlin/com/breadmoirai/garnet/editor/network/ src/gametest/kotlin/com/breadmoirai/garnet/test/editor/EditorFileOpsNetworkSpec.kt
git commit -m "feat(editor): delete a structure or folder from the Explorer"
```

---

### Task 6: Move — shared `relocate`, packet, handler, gametests

**Files:**
- Modify: `src/main/kotlin/com/breadmoirai/garnet/editor/network/EditorFileOpsHandlers.kt`
- Modify: `src/main/kotlin/com/breadmoirai/garnet/editor/network/EditorPackets.kt`
- Modify: `src/main/kotlin/com/breadmoirai/garnet/editor/network/EditorNetworkRegistry.kt`
- Test: `src/gametest/kotlin/com/breadmoirai/garnet/test/editor/EditorFileOpsNetworkSpec.kt`

**Interfaces:**
- Consumes: everything `handleRename` already uses — `EditorDimRegistry.rekeyForRename`, `EditorSession.repointSession`, `LocalHistoryStore.moveDescendantHistories`, `EditorStructureHandlers.placeStructureFrom`.
- Produces: `MovePathC2S(subpath: String, destFolderSubpath: String)`, `EditorFileOpsHandlers.handleMove(server, player, payload)`, and a private `relocate(...)` shared with `handleRename`.

- [ ] **Step 1: Write the failing gametests**

Add to `EditorFileOpsNetworkSpec.kt`. Add import `com.breadmoirai.garnet.editor.network.MovePathC2S`.

```kotlin
    test("handleMove relocates a structure into another folder") {
        withServer { server, player, root ->
            root.resolve("redstone").createDirectories()
            EditorNewStructure.create(root, "clock")

            EditorFileOpsHandlers.handleMove(server, player, MovePathC2S("clock.nbt", "redstone"))

            root.resolve("redstone/clock.nbt").exists().shouldBeTrue()
            root.resolve("clock.nbt").exists().shouldBeFalse()
        }
    }

    test("handleMove relocates a folder into another folder") {
        withServer { server, player, root ->
            root.resolve("redstone/clocks").createDirectories()
            root.resolve("archive").createDirectories()
            EditorNewStructure.create(root.resolve("redstone/clocks"), "tick")

            EditorFileOpsHandlers.handleMove(server, player, MovePathC2S("redstone/clocks", "archive"))

            root.resolve("archive/clocks/tick.nbt").exists().shouldBeTrue()
            root.resolve("redstone/clocks").exists().shouldBeFalse()
        }
    }

    test("handleMove to the project root uses an empty destination") {
        withServer { server, player, root ->
            root.resolve("redstone").createDirectories()
            EditorNewStructure.create(root.resolve("redstone"), "clock")

            EditorFileOpsHandlers.handleMove(server, player, MovePathC2S("redstone/clock.nbt", ""))

            root.resolve("clock.nbt").exists().shouldBeTrue()
            root.resolve("redstone/clock.nbt").exists().shouldBeFalse()
        }
    }

    test("handleMove rejects a destination inside the moved folder's own subtree") {
        // The one invariant rename never had to express: moveTo would either throw or nest the
        // folder inside itself, depending on the filesystem.
        withServer { server, player, root ->
            root.resolve("redstone/clocks").createDirectories()

            EditorFileOpsHandlers.handleMove(server, player, MovePathC2S("redstone", "redstone/clocks"))

            root.resolve("redstone/clocks").isDirectory().shouldBeTrue()
            root.resolve("redstone/clocks/redstone").exists().shouldBeFalse()
            drainPayloads(player).filterIsInstance<EditorErrorS2C>() shouldHaveSize 1
        }
    }

    test("handleMove rejects a folder into itself") {
        withServer { server, player, root ->
            root.resolve("redstone").createDirectories()
            EditorFileOpsHandlers.handleMove(server, player, MovePathC2S("redstone", "redstone"))
            root.resolve("redstone").isDirectory().shouldBeTrue()
            drainPayloads(player).filterIsInstance<EditorErrorS2C>() shouldHaveSize 1
        }
    }

    test("handleMove matches the subtree on a full path segment, not a string prefix") {
        // Moving "redstone" INTO the sibling "redstoneworks" is legal and must not be caught by the
        // descendant check.
        withServer { server, player, root ->
            root.resolve("redstone").createDirectories()
            root.resolve("redstoneworks").createDirectories()

            EditorFileOpsHandlers.handleMove(server, player, MovePathC2S("redstone", "redstoneworks"))

            root.resolve("redstoneworks/redstone").isDirectory().shouldBeTrue()
        }
    }

    test("handleMove rejects a name that already exists in the destination") {
        withServer { server, player, root ->
            root.resolve("redstone").createDirectories()
            EditorNewStructure.create(root, "clock")
            EditorNewStructure.create(root.resolve("redstone"), "clock")

            EditorFileOpsHandlers.handleMove(server, player, MovePathC2S("clock.nbt", "redstone"))

            root.resolve("clock.nbt").exists().shouldBeTrue()   // source untouched
            drainPayloads(player).filterIsInstance<EditorErrorS2C>() shouldHaveSize 1
        }
    }

    test("handleMove rejects a file as the destination") {
        withServer { server, player, root ->
            EditorNewStructure.create(root, "clock")
            EditorNewStructure.create(root, "ring")

            EditorFileOpsHandlers.handleMove(server, player, MovePathC2S("clock.nbt", "ring.nbt"))

            root.resolve("clock.nbt").exists().shouldBeTrue()
            drainPayloads(player).filterIsInstance<EditorErrorS2C>() shouldHaveSize 1
        }
    }

    test("handleMove treats the current parent as a no-op") {
        withServer { server, player, root ->
            root.resolve("redstone").createDirectories()
            EditorNewStructure.create(root.resolve("redstone"), "clock")

            EditorFileOpsHandlers.handleMove(server, player, MovePathC2S("redstone/clock.nbt", "redstone"))

            root.resolve("redstone/clock.nbt").exists().shouldBeTrue()
        }
    }

    test("handleMove rejects a destination that escapes the root") {
        withServer { server, player, root ->
            val evil = root.resolveSibling("evil").also { it.createDirectories() }
            EditorNewStructure.create(root, "clock")

            EditorFileOpsHandlers.handleMove(server, player, MovePathC2S("clock.nbt", "../evil"))

            evil.resolve("clock.nbt").exists().shouldBeFalse()
            root.resolve("clock.nbt").exists().shouldBeTrue()
        }
    }

    test("moving a placed structure unloads it and reloads it under the new subpath") {
        withServer { server, player, root ->
            root.resolve("redstone").createDirectories()
            EditorNewStructure.create(root, "clock")
            EditorStructureHandlers.handlePlaceStructure(server, player, PlaceStructureC2S("clock.nbt"))
            val registry = EditorDimRegistry.of(server)
            registry.placedBoxOf("clock.nbt").shouldNotBeNull()

            EditorFileOpsHandlers.handleMove(server, player, MovePathC2S("clock.nbt", "redstone"))

            registry.placedBoxOf("clock.nbt").shouldBeNull()
            registry.placedBoxOf("redstone/clock.nbt").shouldNotBeNull()
            registry.structureRegionOriginOf("clock.nbt").shouldBeNull()
        }
    }

    test("moving a folder rekeys every structure placed beneath it and carries their history") {
        withServer { server, player, root ->
            root.resolve("redstone").createDirectories()
            root.resolve("archive").createDirectories()
            EditorNewStructure.create(root.resolve("redstone"), "clock")
            EditorStructureHandlers.handlePlaceStructure(server, player, PlaceStructureC2S("redstone/clock.nbt"))
            LocalHistoryStore.revisions(root.resolve("redstone/clock.nbt")).shouldNotBeEmpty()

            EditorFileOpsHandlers.handleMove(server, player, MovePathC2S("redstone", "archive"))

            val registry = EditorDimRegistry.of(server)
            registry.placedBoxOf("archive/redstone/clock.nbt").shouldNotBeNull()
            registry.placedBoxOf("redstone/clock.nbt").shouldBeNull()
            LocalHistoryStore.revisions(root.resolve("archive/redstone/clock.nbt")).shouldNotBeEmpty()
        }
    }

    test("moving a dirty structure commits first, so no edits are lost") {
        withServer { server, player, root ->
            root.resolve("redstone").createDirectories()
            EditorNewStructure.create(root, "clock")
            EditorStructureHandlers.handlePlaceStructure(server, player, PlaceStructureC2S("clock.nbt"))
            drainPayloads(player)

            val registry = EditorDimRegistry.of(server)
            val origin = registry.structureRegionOriginOf("clock.nbt").shouldNotBeNull()
            val level = registry.projectLevel()
            level.setBlock(origin, Blocks.STONE.defaultBlockState(), 3)
            StructureEditWatcher.onBlockChanged(level, origin)
            StructureAutoSave.of(server).isDirty("clock.nbt").shouldBeTrue()

            EditorFileOpsHandlers.handleMove(server, player, MovePathC2S("clock.nbt", "redstone"))

            StructureAutoSave.of(server).isDirty("clock.nbt").shouldBeFalse()
            root.resolve("redstone/clock.nbt").exists().shouldBeTrue()
        }
    }

    test("a failed commit during move aborts the move entirely and reports an error") {
        // Same contract as rename's abort, reached through the shared relocate: a move that
        // proceeded would invalidate the old subpath and strand those edits permanently.
        withServer { server, player, root ->
            root.resolve("redstone").createDirectories()
            EditorNewStructure.create(root, "clock")
            EditorStructureHandlers.handlePlaceStructure(server, player, PlaceStructureC2S("clock.nbt"))
            drainPayloads(player)

            val registry = EditorDimRegistry.of(server)
            val origin = registry.structureRegionOriginOf("clock.nbt").shouldNotBeNull()
            val level = registry.projectLevel()
            level.setBlock(origin, Blocks.STONE.defaultBlockState(), 3)
            StructureEditWatcher.onBlockChanged(level, origin)

            val file = root.resolve("clock.nbt")
            val historyDir = LocalHistoryStore.dirFor(file)
            historyDir.resolve("index.json").deleteIfExists()
            historyDir.resolve("index.json").createDirectory()

            try {
                EditorFileOpsHandlers.handleMove(server, player, MovePathC2S("clock.nbt", "redstone"))

                root.resolve("clock.nbt").exists().shouldBeTrue()
                root.resolve("redstone/clock.nbt").exists().shouldBeFalse()
                StructureAutoSave.of(server).isDirty("clock.nbt").shouldBeTrue()
                registry.placedBoxOf("clock.nbt").shouldNotBeNull()
                drainPayloads(player).filterIsInstance<EditorErrorS2C>() shouldHaveSize 1
            } finally {
                StructureAutoSave.of(server).clear("clock.nbt")
                StructureCommit.clearBackoff(server, "clock.nbt")
                historyDir.resolve("index.json").toFile().delete()
            }
        }
    }

    test("moving an ancestor folder repoints the active session") {
        withServer { server, player, root ->
            root.resolve("redstone/clocks").createDirectories()
            root.resolve("archive").createDirectories()
            EditorSession.setActive(player.uuid, "redstone/clocks")

            EditorFileOpsHandlers.handleMove(server, player, MovePathC2S("redstone", "archive"))

            EditorSession.get(player.uuid)!!.activeSubpath shouldBe "archive/redstone/clocks"
        }
    }
```

- [ ] **Step 2: Run to verify they fail**

```sh
cmd.exe /c "cd /d H:\\Repo\\Garnet && gradlew.bat :26.2:gametestClasses"
```

Expected: FAIL — `Unresolved reference: MovePathC2S`, `Unresolved reference: handleMove`.

- [ ] **Step 3: Extract `relocate` from `handleRename`**

Restructure `handleRename` so everything from the quiesce call onward moves into a private `relocate`. `handleRename` keeps only its own resolution and validation, then delegates. The replacement for the whole of `handleRename` plus the new `relocate`:

```kotlin
    fun handleRename(server: MinecraftServer, player: ServerPlayer, payload: RenamePathC2S) {
        val root = EditorRootResolver.rootFor(server) ?: run {
            fail(player, "project-root not configured"); return
        }
        val source = root.resolveSubpath(payload.subpath) ?: run {
            fail(player, "path not found or escapes root: ${payload.subpath}"); return
        }
        if (payload.subpath.isEmpty()) {
            fail(player, "cannot rename the project root"); return
        }
        val newName = payload.newName.trim()
        val parent = source.parent
        // Exclude the node itself so re-committing an unchanged name is a no-op, not a collision.
        val siblings = siblingNames(parent).filterNot { it == source.name }
        EditorNames.validate(newName, siblings)?.let {
            fail(player, it); return
        }

        val parentSubpath = payload.subpath.substringBeforeLast('/', "")
        val newSubpath = if (parentSubpath.isEmpty()) newName else "$parentSubpath/$newName"

        relocate(server, player, payload.subpath, source, parent.resolve(newName), newSubpath, "rename")
    }

    /**
     * Move the node at [oldSubpath] from [source] to [target], where [target]'s subpath is
     * [newSubpath]. Shared by [handleRename] and [handleMove]: a rename IS a move that happens to
     * keep the same parent, and every step below is already expressed in
     * `oldSubpath → newSubpath` terms. [operation] names the caller for error messages ("rename
     * failed: ...").
     *
     * Each caller does its own resolution and validation first; by the time this runs, the move is
     * known to be legal and only the ordering hazards remain.
     */
    private fun relocate(
        server: MinecraftServer,
        player: ServerPlayer,
        oldSubpath: String,
        source: Path,
        target: Path,
        newSubpath: String,
        operation: String,
    ) {
        // A placed structure is keyed by subpath in EditorDimRegistry, so relocating under it would
        // strand both the placed box and the region assignment if we don't unload/reload it. But the
        // move (an IO op — can fail on a lock, permission problem, etc.) must succeed FIRST: tearing
        // down the placed-structure state (clearing its blocks, dropping its registry keys) before
        // the move is confirmed would leave the structure's blocks erased and its registry entry gone
        // while the player is told the operation failed and the (untouched, still-old-named) file
        // sits there unrecoverably out of sync with the world. Only touch that state once the move
        // is confirmed.
        val registry = EditorDimRegistry.of(server)
        val wasPlaced = registry.placedBoxOf(oldSubpath)

        // Quiesce before the move: see EditorHandlerSupport.commitDirtyUnder for why a relocation
        // that skips this strands every pending edit beneath the moved path.
        commitDirtyUnder(server, oldSubpath)?.let {
            fail(player, "$operation failed: $it"); return
        }

        try {
            source.moveTo(target)
        } catch (e: Exception) {
            LOGGER.error("[project/{}] {} -> {}: {}", operation, oldSubpath, newSubpath, e.message, e)
            fail(player, "$operation failed: ${e.message}"); return
        }

        // History is keyed by each file's own absolute path, so every moved .nbt (the node itself, or
        // every descendant .nbt when a whole FOLDER moved) must carry its history across, or it
        // silently loses every revision it has accumulated. This cannot itself fail the operation
        // from the player's perspective — the file(s) already moved — so a problem here is logged,
        // not reported as a failure: sharing one `try` with the move above would have let a
        // hypothetical history-move exception report "failed" after the file had already relocated.
        try {
            LocalHistoryStore.moveDescendantHistories(source, target)
        } catch (e: Exception) {
            LOGGER.error("[project/{}] history move for {} -> {}: {}", operation, oldSubpath, newSubpath, e.message, e)
        }

        if (wasPlaced != null) {
            // Drop the registry entry BEFORE clearing blocks, not after. clearBounds writes AIR
            // through the 3-arg level.setBlock, which the setBlock mixin hooks unconditionally for
            // any successful server write; if the OLD subpath were still registered at that instant,
            // EditorDimRegistry.structureSubpathAt would still attribute those positions to it, and
            // the watcher would re-mark the OLD subpath dirty immediately after the commit above
            // just cleared it. Since wasPlaced.origin/size were already captured into a local before
            // any registry mutation, clearBounds needs nothing further from the registry, so
            // dropping the entry first is safe.
            registry.unplaceStructure(oldSubpath)
            StructurePersistence.clearBounds(registry.projectLevel(), wasPlaced.origin, wasPlaced.size)
            EditorStructureHandlers.placeStructureFrom(server, player, newSubpath, target, "moved to $newSubpath")
        }

        // Rekey every OTHER registry entry nested under the moved path (e.g. structures placed inside
        // a moved folder). The node's own entry, if any, was already handled above by the wasPlaced
        // block, so by this point rekeyForRename's exact-match branch is a no-op for it — only
        // descendants still keyed under the old subpath remain to be moved.
        registry.rekeyForRename(oldSubpath, newSubpath)

        EditorSession.repointSession(player, oldSubpath, newSubpath)

        sendTree(server, player)
    }
```

Note two deliberate consolidations from the original: the two consecutive `if (wasPlaced != null)` blocks become one, and the status message becomes `"moved to $newSubpath"` for both callers. Confirm no test asserts on the old `"renamed to ..."` string:

```sh
grep -rn "renamed to" src/gametest src/clientTest src/test
```

If any test does assert on it, thread the message through as another `relocate` parameter rather than changing the test.

Add imports if not already present: `java.nio.file.Path`, `com.breadmoirai.garnet.editor.data.EditorNames`, `com.breadmoirai.garnet.structure.StructurePersistence`, `com.breadmoirai.garnet.editor.data.EditorSession`.

- [ ] **Step 4: Verify rename still passes before adding move**

```sh
cmd.exe /c "cd /d H:\\Repo\\Garnet && gradlew.bat :26.2:runGameTest"
```

Expected: all pre-existing rename tests still PASS (the move tests still fail to compile only if Step 1's tests are already in the file — if so, comment them out for this step, or accept the compile failure and run this verification after Step 6). Prefer: run this check with the new move tests present but `handleMove` stubbed to `fail(player, "not implemented")` so the file compiles and rename's regression signal is readable on its own.

- [ ] **Step 5: Add the packet and register it**

In `EditorPackets.kt`:

```kotlin
/**
 * Move the file or folder at [subpath] into [destFolderSubpath] (`""` = the project root), keeping
 * its name.
 */
data class MovePathC2S(val subpath: String, val destFolderSubpath: String) : CustomPacketPayload {
    companion object {
        val TYPE = CustomPacketPayload.Type<MovePathC2S>(id("move_path"))
        val STREAM_CODEC: StreamCodec<ByteBuf, MovePathC2S> = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, MovePathC2S::subpath,
            ByteBufCodecs.STRING_UTF8, MovePathC2S::destFolderSubpath,
            ::MovePathC2S,
        )
    }
    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE
}
```

In `EditorNetworkRegistry.register()`:

```kotlin
        PayloadTypeRegistry.serverboundPlay().register(MovePathC2S.TYPE, MovePathC2S.STREAM_CODEC)
```

```kotlin
        ServerPlayNetworking.registerGlobalReceiver(MovePathC2S.TYPE) { payload, ctx ->
            ctx.server().execute { EditorFileOpsHandlers.handleMove(ctx.server(), ctx.player(), payload) }
        }
```

- [ ] **Step 6: Implement `handleMove`**

```kotlin
    /**
     * Move the node at [payload].subpath into [payload].destFolderSubpath, keeping its name.
     *
     * Shares [relocate] with [handleRename] — a rename is a move that keeps its parent — so only the
     * validation differs. Three of the four checks below have no rename counterpart, because a
     * rename cannot change which folder a node lives in.
     */
    fun handleMove(server: MinecraftServer, player: ServerPlayer, payload: MovePathC2S) {
        if (payload.subpath.isEmpty()) {
            fail(player, "cannot move the project root"); return
        }
        val root = EditorRootResolver.rootFor(server) ?: run {
            fail(player, "project-root not configured"); return
        }
        val source = root.resolveSubpath(payload.subpath) ?: run {
            fail(player, "path not found or escapes root: ${payload.subpath}"); return
        }
        // resolveParentFolder handles root resolution, containment, and the not-a-folder case, and
        // reports each itself.
        val destFolder = resolveParentFolder(server, player, payload.destFolderSubpath) ?: return

        // Moving a folder into its own subtree would either throw or nest the folder inside itself,
        // depending on the filesystem. The boundary is a full path SEGMENT, matching
        // EditorDimRegistry.rekeyForRename and EditorSession.repointSession: moving "redstone" into
        // the sibling "redstoneworks" is perfectly legal and a plain startsWith would wrongly
        // reject it.
        if (payload.destFolderSubpath == payload.subpath ||
            payload.destFolderSubpath.startsWith("${payload.subpath}/")
        ) {
            fail(player, "cannot move '${payload.subpath}' into itself"); return
        }

        val name = source.name
        val currentParentSubpath = payload.subpath.substringBeforeLast('/', "")
        if (currentParentSubpath == payload.destFolderSubpath) {
            // Already there. Not an error — resend the tree so a client acting on a stale snapshot
            // still converges — but nothing to move.
            sendTree(server, player); return
        }

        EditorNames.validate(name, siblingNames(destFolder))?.let {
            fail(player, "move failed: $it"); return
        }

        val newSubpath = if (payload.destFolderSubpath.isEmpty()) name
        else "${payload.destFolderSubpath}/$name"

        relocate(server, player, payload.subpath, source, destFolder.resolve(name), newSubpath, "move")
    }
```

- [ ] **Step 7: Run the gametests**

```sh
cmd.exe /c "cd /d H:\\Repo\\Garnet && gradlew.bat :26.2:runGameTest"
```

Expected: PASS — all fifteen new move tests, all delete and duplicate tests, and all fifteen original rename tests.

- [ ] **Step 8: Commit**

```bash
git add src/main/kotlin/com/breadmoirai/garnet/editor/network/ src/gametest/kotlin/com/breadmoirai/garnet/test/editor/EditorFileOpsNetworkSpec.kt
git commit -m "feat(editor): move a structure or folder between Explorer folders"
```

---

### Task 7: Client actions

Pure payload logic with unit tests. Separate from Task 8's UI so the wire contract is settled before any Compose work.

**Files:**
- Modify: `src/client/kotlin/com/breadmoirai/garnet/editor/ui/ExplorerActions.kt`
- Test: `src/test/kotlin/com/breadmoirai/garnet/client/editor/ui/ExplorerActionsTest.kt`

**Interfaces:**
- Consumes: `DuplicatePathC2S`, `DeletePathC2S`, `MovePathC2S` (Tasks 3, 5, 6); `ExplorerActions.sender`; the existing private `siblingsOf(parentPath): List<String>`.
- Produces: `commitDuplicate(path: String): String?`, `commitDelete(path: String): String?`, `commitMove(path: String, destFolder: String): String?` — each null on success, else the reason nothing was sent.

- [ ] **Step 1: Write the failing tests**

Append inside `ExplorerActionsTest.kt`'s spec block, and add imports for the three new payload types:

```kotlin
    test("duplicating sends DuplicatePathC2S for the clicked node") {
        val sent = captureSends()
        ExplorerActions.commitDuplicate("redstone/clock.nbt") shouldBe null
        sent shouldBe listOf(DuplicatePathC2S("redstone/clock.nbt"))
    }

    test("deleting sends DeletePathC2S for the clicked node") {
        val sent = captureSends()
        ExplorerActions.commitDelete("redstone") shouldBe null
        sent shouldBe listOf(DeletePathC2S("redstone"))
    }

    test("the project root cannot be duplicated, deleted, or moved") {
        val sent = captureSends()
        ExplorerActions.commitDuplicate("").shouldNotBeNull()
        ExplorerActions.commitDelete("").shouldNotBeNull()
        ExplorerActions.commitMove("", "redstone").shouldNotBeNull()
        sent.shouldBeEmpty()
    }

    test("moving sends MovePathC2S with the destination folder") {
        val sent = captureSends()
        ExplorerActions.commitMove("clock.nbt", "redstone") shouldBe null
        sent shouldBe listOf(MovePathC2S("clock.nbt", "redstone"))
    }

    test("moving to the project root sends an empty destination") {
        val sent = captureSends()
        ExplorerActions.commitMove("redstone/clock.nbt", "") shouldBe null
        sent shouldBe listOf(MovePathC2S("redstone/clock.nbt", ""))
    }

    test("a folder cannot be moved into itself or its own subtree") {
        val sent = captureSends()
        ExplorerActions.commitMove("redstone", "redstone").shouldNotBeNull()
        ExplorerActions.commitMove("redstone", "redstone/clocks").shouldNotBeNull()
        sent.shouldBeEmpty()
    }

    test("moving into a same-prefixed sibling folder is allowed") {
        // "redstoneworks" is a sibling of "redstone", not a descendant — a plain startsWith check
        // would wrongly reject this.
        val sent = captureSends()
        ExplorerActions.commitMove("redstone", "redstoneworks") shouldBe null
        sent shouldBe listOf(MovePathC2S("redstone", "redstoneworks"))
    }

    test("moving into the folder a node already lives in sends nothing") {
        val sent = captureSends()
        ExplorerActions.commitMove("redstone/clock.nbt", "redstone").shouldNotBeNull()
        sent.shouldBeEmpty()
    }
```

- [ ] **Step 2: Run to verify they fail**

```sh
cmd.exe /c "cd /d H:\\Repo\\Garnet && gradlew.bat :26.2:testClasses"
```

Expected: FAIL — `Unresolved reference: commitDuplicate`.

- [ ] **Step 3: Implement the actions**

Add to `ExplorerActions`, with imports for the three payload types and `com.breadmoirai.garnet.editor.data.EditorNames` (already imported):

```kotlin
    /** Null on success (packet sent), else the reason nothing was sent. */
    fun commitDuplicate(path: String): String? {
        if (path.isEmpty()) return "cannot duplicate the project root"
        // The name is derived server-side, so there is nothing to pre-validate here.
        sender(DuplicatePathC2S(path))
        return null
    }

    /** Null on success (packet sent), else the reason nothing was sent. */
    fun commitDelete(path: String): String? {
        if (path.isEmpty()) return "cannot delete the project root"
        sender(DeletePathC2S(path))
        return null
    }

    /**
     * Null on success (packet sent), else the reason nothing was sent.
     *
     * Mirrors `handleMove`'s validation so the dialog can refuse a destination in place instead of
     * closing and surfacing an EditorErrorS2C a round-trip later. The server re-runs all of it
     * against the real filesystem and stays authoritative.
     */
    fun commitMove(path: String, destFolder: String): String? {
        if (path.isEmpty()) return "cannot move the project root"
        // Full path SEGMENT, not a string prefix: moving "redstone" into the sibling
        // "redstoneworks" is legal and must not be caught here.
        if (destFolder == path || destFolder.startsWith("$path/")) {
            return "cannot move '$path' into itself"
        }
        val name = path.substringAfterLast('/')
        if (path.substringBeforeLast('/', "") == destFolder) {
            return "'$name' is already in that folder"
        }
        EditorNames.validate(name, siblingsOf(destFolder))?.let { return it }
        sender(MovePathC2S(path, destFolder))
        return null
    }
```

- [ ] **Step 4: Run to verify they pass**

```sh
cmd.exe /c "cd /d H:\\Repo\\Garnet && gradlew.bat :26.2:test"
```

Expected: PASS. Confirm `failures="0" errors="0"` in `versions/26.2/build/test-results/test/TEST-com.breadmoirai.garnet.client.editor.ui.ExplorerActionsTest.xml`.

- [ ] **Step 5: Commit**

```bash
git add src/client/kotlin/com/breadmoirai/garnet/editor/ui/ExplorerActions.kt src/test/kotlin/com/breadmoirai/garnet/client/editor/ui/ExplorerActionsTest.kt
git commit -m "feat(explorer): client actions for duplicate, delete, and move"
```

---

### Task 8: Context menu items and the dialog host

**Files:**
- Create: `src/client/kotlin/com/breadmoirai/garnet/editor/ui/ExplorerDialogs.kt`
- Modify: `src/client/kotlin/com/breadmoirai/garnet/editor/ui/ExplorerContextMenu.kt`
- Modify: `src/client/kotlin/com/breadmoirai/garnet/editor/ui/ProjectExplorerPanel.kt:151-161`
- Test: `src/clientTest/kotlin/com/breadmoirai/garnet/test/ExplorerUiSpec.kt`

**Interfaces:**
- Consumes: `ExplorerActions.commitDuplicate/commitDelete/commitMove` (Task 7); `ProjectTreeState.snapshot`; `FolderNode.walk(): Sequence<Pair<String, FileTreeNode>>`; `FolderNode.resolve(path): FileTreeNode?`; `ExplorerTreeState.ROOT_PATH` (`""`).
- Produces: `ExplorerDialogState` (panel-scoped, with `openDelete(path)`, `openMove(path)`, `close()`, and a `pending` property), and `@Composable ExplorerDialogs(state, onConfirmDelete, onConfirmMove)`.

- [ ] **Step 1: Write the failing clientTest**

Add to `ExplorerUiSpec.kt`. The file's existing `mountForContextMenu()` helper builds a root → "redstone" → "clock.nbt" tree and the `rightClick(x, y)` / `click(x, y)` helpers drive it; reuse them and follow the coordinate conventions of the existing "right-click a nested folder, create through the inline field" test when picking row positions.

`CLOCK_X` / `CLOCK_Y` below are the coordinates of the `clock.nbt` row — copy the exact values the existing "right-click a nested folder, create through the inline field" test uses to hit that row.

```kotlin
    val CLOCK_X = 40.0   // copy from the existing nested-folder test
    val CLOCK_Y = 60.0   // copy from the existing nested-folder test

    test("delete asks for confirmation first, and cancelling sends nothing") {
        val sent = captureSends()
        mountForContextMenu()
        try {
            // Right-click "clock.nbt", choose Delete: the confirmation must appear and, until it is
            // confirmed, nothing may reach the wire.
            rightClickThenDelete(CLOCK_X, CLOCK_Y)
            sent.shouldBeEmpty()

            clickDialogCancel(CLOCK_X, CLOCK_Y)
            sent.shouldBeEmpty()
        } finally {
            unmountFromContextMenu()
        }
    }

    test("confirming the delete dialog sends DeletePathC2S for the clicked node") {
        val sent = captureSends()
        mountForContextMenu()
        try {
            rightClickThenDelete(CLOCK_X, CLOCK_Y)
            clickDialogConfirm(CLOCK_X, CLOCK_Y)
            sent shouldBe listOf(DeletePathC2S("redstone/clock.nbt"))
        } finally {
            unmountFromContextMenu()
        }
    }

    test("duplicate sends immediately, with no dialog") {
        val sent = captureSends()
        mountForContextMenu()
        try {
            rightClickThenDuplicate(CLOCK_X, CLOCK_Y)
            sent shouldBe listOf(DuplicatePathC2S("redstone/clock.nbt"))
        } finally {
            unmountFromContextMenu()
        }
    }

    test("the move dialog sends MovePathC2S for the chosen destination") {
        val sent = captureSends()
        mountForContextMenu()
        try {
            rightClickThenMove(CLOCK_X, CLOCK_Y)
            sent.shouldBeEmpty()                          // the dialog is open; nothing sent yet

            clickMoveDestinationRoot(CLOCK_X, CLOCK_Y)    // choose the project root
            sent shouldBe listOf(MovePathC2S("redstone/clock.nbt", ""))
        } finally {
            unmountFromContextMenu()
        }
    }
```

Add these helpers next to the existing `rightClickThenNewFolder`. They build on the same fact its KDoc records: `FixedOffsetPositionProvider` puts the popup card's top-left at the click point, so every row sits at a fixed offset from it.

```kotlin
    /**
     * Vertical pitch between adjacent PopupMenu rows, and the extra height a `separator()` adds.
     *
     * CALIBRATE BOTH before relying on them: run the "duplicate sends immediately" test once with
     * `capture("calib_menu_rows.png")` inserted after the `rightClick`, open the PNG, and measure.
     * The existing +20/+12 first-row offset is already known-good and is the anchor for the rest.
     */
    val ROW_PITCH = 24.0
    val SEPARATOR_H = 9.0

    /** Click the menu row at [index], counting only selectable rows, with [separatorsAbove] above it. */
    fun clickMenuRow(x: Double, y: Double, index: Int, separatorsAbove: Int) {
        click(x + 20.0, y + 12.0 + index * ROW_PITCH + separatorsAbove * SEPARATOR_H)
    }

    // Menu order: New Folder(0), New Structure(1), --, Rename(2), Duplicate(3), Move to…(4), --, Delete(5).
    fun rightClickThenDuplicate(x: Double, y: Double) {
        rightClick(x, y); clickMenuRow(x, y, index = 3, separatorsAbove = 1)
    }

    fun rightClickThenMove(x: Double, y: Double) {
        rightClick(x, y); clickMenuRow(x, y, index = 4, separatorsAbove = 1)
    }

    fun rightClickThenDelete(x: Double, y: Double) {
        rightClick(x, y); clickMenuRow(x, y, index = 5, separatorsAbove = 2)
    }

    /**
     * Both dialogs open at the SAME anchor the context menu used, so their rows are at the same
     * offsets from the original right-click point.
     *
     * Delete dialog rows: Delete(0), Cancel(1).
     * Move dialog rows: one per destination in tree order, then Cancel. With the mount's
     * root → "redstone" → "clock.nbt" tree and "redstone/clock.nbt" as the moved node, "redstone"
     * is the current parent and is excluded, leaving the project root alone at index 0.
     */
    fun clickDialogConfirm(x: Double, y: Double) = clickMenuRow(x, y, index = 0, separatorsAbove = 0)
    fun clickDialogCancel(x: Double, y: Double) = clickMenuRow(x, y, index = 1, separatorsAbove = 0)
    fun clickMoveDestinationRoot(x: Double, y: Double) = clickMenuRow(x, y, index = 0, separatorsAbove = 0)
```

Each new test passes the same `(x, y)` it right-clicked with — reuse the coordinates the existing "right-click a nested folder" test uses to hit the `clock.nbt` row, so all four tests target the same known-good row. If a row click misses, capture a screenshot at that point and re-measure `ROW_PITCH`/`SEPARATOR_H` rather than nudging coordinates by trial.

- [ ] **Step 2: Run to verify it fails**

```sh
cmd.exe /c "cd /d H:\\Repo\\Garnet && gradlew.bat :26.2:clientTestClasses"
```

Expected: FAIL — unresolved helper references.

- [ ] **Step 3: Create `ExplorerDialogs.kt`**

```kotlin
package com.breadmoirai.garnet.editor.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.breadmoirai.garnet.editor.data.FileNode
import com.breadmoirai.garnet.editor.data.FolderNode
import com.breadmoirai.garnet.editor.data.resolve
import com.breadmoirai.garnet.editor.data.walk

/**
 * Which confirm/pick dialog the Explorer has open, if any.
 *
 * Panel-scoped by construction (`remember`-ed in the panel, never a top-level object), for the same
 * reason as [ExplorerMenuState]: the dock composes into a long-lived singleton scene, so dialog
 * state held globally would survive a panel re-mount and repaint over the next one. See
 * DockState.mountEpoch and docs/ui/jewel-widget-layer.md.
 */
class ExplorerDialogState {
    sealed interface Pending {
        val path: String
        data class Delete(override val path: String) : Pending
        data class Move(override val path: String) : Pending
    }

    var pending: Pending? by mutableStateOf(null)
        private set

    fun openDelete(path: String) { pending = Pending.Delete(path) }
    fun openMove(path: String) { pending = Pending.Move(path) }
    fun close() { pending = null }
}

/**
 * The Explorer's delete-confirmation and move-target dialogs.
 *
 * A single popup layer at a time, opened only AFTER the context menu has closed — which is what
 * keeps this clear of the nested-popup defect documented on [ExplorerContextMenu]: Jewel opens a
 * flyout as a second focusable layer, and the dock's CanvasLayersComposeScene stops routing pointer
 * events to every layer below the focused one. One layer, one set of live pointer targets.
 */
@Composable
fun ExplorerDialogs(
    state: ExplorerDialogState,
    onConfirmDelete: (path: String) -> Unit,
    onConfirmMove: (path: String, destFolder: String) -> Unit,
) {
    when (val pending = state.pending) {
        null -> Unit
        is ExplorerDialogState.Pending.Delete -> DeleteConfirmDialog(
            path = pending.path,
            onCancel = { state.close() },
            onConfirm = { state.close(); onConfirmDelete(pending.path) },
        )
        is ExplorerDialogState.Pending.Move -> MoveTargetDialog(
            path = pending.path,
            onCancel = { state.close() },
            onPick = { dest -> state.close(); onConfirmMove(pending.path, dest) },
        )
    }
}

/**
 * How many `.nbt` structures sit anywhere beneath [path], for the delete prompt. Null when [path] is
 * a file or does not resolve — those are named without a count, since the number the player cares
 * about is "how many structures am I about to lose", and for a single file that number is obvious.
 *
 * Counts structures rather than all nodes on purpose: intermediate folders are not what anyone is
 * afraid of losing.
 */
internal fun structureCountUnder(path: String): Int? {
    val root = ProjectTreeState.snapshot?.root ?: return null
    val node = root.resolve(path) as? FolderNode ?: return null
    return node.walk().count { (_, child) -> child is FileNode && child.extension == "nbt" }
}

/**
 * Every folder in the project that [movedPath] could legally move into, as `subpath to label` in
 * tree order. The project root is always first, as `"" to "<root name>"`.
 *
 * Excludes the moved node's own subtree (moving a folder inside itself) and the folder it already
 * lives in — the same two rules `ExplorerActions.commitMove` enforces, applied here so an illegal
 * destination is never offered rather than being offered and then refused.
 */
internal fun moveDestinationsFor(movedPath: String): List<Pair<String, String>> {
    val root = ProjectTreeState.snapshot?.root ?: return emptyList()
    val currentParent = movedPath.substringBeforeLast('/', "")
    return root.walk()
        .filter { (_, node) -> node is FolderNode }
        .filterNot { (subpath, _) -> subpath == movedPath || subpath.startsWith("$movedPath/") }
        .filterNot { (subpath, _) -> subpath == currentParent }
        .map { (subpath, node) -> subpath to (if (subpath.isEmpty()) node.name else subpath) }
        .toList()
}
```

Both dialogs reuse `FixedOffsetPositionProvider`, which currently lives in `ExplorerContextMenu.kt` as `private`. Widen it to `internal` there:

```kotlin
internal class FixedOffsetPositionProvider(private val offset: IntOffset) : PopupPositionProvider {
```

The dialogs open at the same anchor the menu used, so `ExplorerDialogState` carries it. Amend the state class written above:

```kotlin
    var pending: Pending? by mutableStateOf(null)
        private set
    var anchor: IntOffset by mutableStateOf(IntOffset.Zero)
        private set

    fun openDelete(path: String, at: IntOffset) { anchor = at; pending = Pending.Delete(path) }
    fun openMove(path: String, at: IntOffset) { anchor = at; pending = Pending.Move(path) }
    fun close() { pending = null }
```

and pass `state.anchor` into both dialogs from `ExplorerDialogs`. Then add the two composables to the same file:

```kotlin
/**
 * "Delete 'clock.nbt'?" — with a structure count when the target is a folder, because that is the
 * number the player is actually weighing. Confirm is the first row so the common case is the
 * shortest travel from the menu item that opened it.
 */
@Composable
private fun DeleteConfirmDialog(
    path: String,
    anchor: IntOffset,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    val name = path.substringAfterLast('/')
    val count = structureCountUnder(path)
    val prompt = when {
        count == null -> "Delete '$name'?"
        count == 0 -> "Delete the empty folder '$name'?"
        count == 1 -> "Delete '$name' and the 1 structure inside it?"
        else -> "Delete '$name' and the $count structures inside it?"
    }
    PopupMenu(
        onDismissRequest = { onCancel(); true },
        popupPositionProvider = FixedOffsetPositionProvider(anchor),
    ) {
        selectableItem(selected = false, onClick = { onConfirm() }) { Text(prompt) }
        selectableItem(selected = false, onClick = { onCancel() }) { Text("Cancel") }
    }
}

/**
 * "Move to…" — one row per legal destination folder, in tree order, then Cancel.
 *
 * Illegal destinations are never offered rather than offered-then-refused: [moveDestinationsFor]
 * applies the same two exclusions `ExplorerActions.commitMove` enforces.
 */
@Composable
private fun MoveTargetDialog(
    path: String,
    anchor: IntOffset,
    onCancel: () -> Unit,
    onPick: (destFolder: String) -> Unit,
) {
    val destinations = moveDestinationsFor(path)
    PopupMenu(
        onDismissRequest = { onCancel(); true },
        popupPositionProvider = FixedOffsetPositionProvider(anchor),
    ) {
        for ((subpath, label) in destinations) {
            selectableItem(selected = false, onClick = { onPick(subpath) }) { Text(label) }
        }
        selectableItem(selected = false, onClick = { onCancel() }) { Text("Cancel") }
    }
}
```

`ExplorerDialogs`'s `when` branches gain the anchor argument:

```kotlin
        is ExplorerDialogState.Pending.Delete -> DeleteConfirmDialog(
            path = pending.path,
            anchor = state.anchor,
            onCancel = { state.close() },
            onConfirm = { state.close(); onConfirmDelete(pending.path) },
        )
        is ExplorerDialogState.Pending.Move -> MoveTargetDialog(
            path = pending.path,
            anchor = state.anchor,
            onCancel = { state.close() },
            onPick = { dest -> state.close(); onConfirmMove(pending.path, dest) },
        )
```

Add `androidx.compose.ui.unit.IntOffset`, `org.jetbrains.jewel.ui.component.PopupMenu`, `org.jetbrains.jewel.ui.component.Text`, and `androidx.compose.runtime.Composable` to the file's imports. Do not introduce a new widget library, and do not nest a popup inside another — one layer at a time is what keeps these clear of the Jewel defect.

- [ ] **Step 4: Add the menu items**

In `ExplorerContextMenu.kt`, extend the signature and add three rows after the existing `Rename` row. Update the KDoc's first line to name the new actions:

```kotlin
fun ExplorerContextMenu(
    state: ExplorerMenuState,
    onNew: (parentPath: String, kind: NewNodeKind) -> Unit,
    onRename: (path: String) -> Unit,
    onDuplicate: (path: String) -> Unit,
    onDelete: (path: String) -> Unit,
    onMove: (path: String) -> Unit,
) {
```

```kotlin
        selectableItem(
            selected = false,
            enabled = target != ExplorerTreeState.ROOT_PATH,
            onClick = { state.close(); onDuplicate(target) },
        ) {
            Text("Duplicate")
        }
        selectableItem(
            selected = false,
            enabled = target != ExplorerTreeState.ROOT_PATH,
            onClick = { state.close(); onMove(target) },
        ) {
            Text("Move to…")
        }
        separator()
        selectableItem(
            selected = false,
            enabled = target != ExplorerTreeState.ROOT_PATH,
            onClick = { state.close(); onDelete(target) },
        ) {
            Text("Delete")
        }
```

`state.close()` runs before each callback, so the menu layer is gone before any dialog opens.

- [ ] **Step 5: Wire the panel**

In `ProjectExplorerPanel.kt`, add alongside `val menu = remember { ExplorerMenuState() }`:

```kotlin
            val dialogs = remember { ExplorerDialogState() }
```

Extend the `ExplorerContextMenu(...)` call with the three new callbacks:

```kotlin
                    onDuplicate = { path -> editError = ExplorerActions.commitDuplicate(path) },
                    // menu.anchor survives close() (only `target` is nulled), so the dialog opens
                    // exactly where the menu was.
                    onDelete = { path -> dialogs.openDelete(path, menu.anchor) },
                    onMove = { path -> dialogs.openMove(path, menu.anchor) },
```

and add the dialog host immediately after that call, inside the same `else` branch:

```kotlin
                ExplorerDialogs(
                    state = dialogs,
                    onConfirmDelete = { path -> editError = ExplorerActions.commitDelete(path) },
                    onConfirmMove = { path, dest -> editError = ExplorerActions.commitMove(path, dest) },
                )
```

Routing failures into `editError` reuses the existing status line at the bottom of the panel (`val message = editError ?: ProjectTreeState.status`), so a refused action reports why without new UI.

- [ ] **Step 6: Compile every source set**

```sh
cmd.exe /c "cd /d H:\\Repo\\Garnet && gradlew.bat :26.2:clientClasses :26.2:classes :26.2:gametestClasses :26.2:clientTestClasses :26.2:testClasses"
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Run the client tests**

```sh
cmd.exe /c "cd /d H:\\Repo\\Garnet && gradlew.bat :26.2:runClientTest"
```

Expected: PASS, including the four new tests and every pre-existing `ExplorerUiSpec` test.

- [ ] **Step 8: Commit**

```bash
git add src/client/kotlin/com/breadmoirai/garnet/editor/ui/ src/clientTest/kotlin/com/breadmoirai/garnet/test/ExplorerUiSpec.kt
git commit -m "feat(explorer): context-menu duplicate, delete, and move with confirm dialogs"
```

---

### Task 9: Documentation

`CLAUDE.md` makes this mandatory after any source change, not optional polish.

**Files:**
- Modify: `docs/use-cases/structure-lifecycle.md`
- Modify: `docs/persistence/local-history.md`
- Modify: `docs/architecture/redstone-project.md` (only if it enumerates the Explorer's file operations)

- [ ] **Step 1: Extend UC-MAN-10**

In `docs/use-cases/structure-lifecycle.md`, add three sub-entries after UC-MAN-10.f, matching the existing entries' density (each names the packet, the handler, and the non-obvious invariant):

- **UC-MAN-10.g** `DuplicatePathC2S(subpath)` → `handleDuplicate`: quiesces via `EditorHandlerSupport.commitDirtyUnder` so the copy reflects in-world edits rather than a stale `.nbt`, derives a deduplicated sibling name through `EditorNames.duplicateName`, copies the file or subtree, and resends the tree. The copy is not placed, gets no registry entry, and starts with no local history.
- **UC-MAN-10.h** `DeletePathC2S(subpath)` → `handleDelete`: unlinks first (fallible IO before destructive teardown), then `unplaceStructure` before `clearBounds` for every structure in the subtree, then clears `StructureAutoSave` state and `StructureCommit` backoff — the correctness step, since a dirty entry outliving its file makes `tick` resurrect it — then clears an active session under the path. History is retained; the pre-delete commit is best-effort and a failure does not block the delete.
- **UC-MAN-10.i** `MovePathC2S(subpath, destFolderSubpath)` → `handleMove`: shares `relocate` with `handleRename`, adding four checks a rename cannot need — destination is a folder, is not the source's own subtree (full path segment, so a same-prefixed sibling is legal), is not the current parent (a no-op), and has no name collision.

Also update the paragraph above UC-MAN-10 that currently reads "**Save/New have no client UI trigger**" — the Explorer's context menu now carries six actions, so correct any claim that implies otherwise.

- [ ] **Step 2: Document the retained-history consequence**

In `docs/persistence/local-history.md`, add a short section: deleting a structure from the Explorer leaves its revisions in place, because history is the recovery route for a delete. Since revisions are keyed by absolute path, a file later created at the same path inherits them — a deliberate undelete affordance, and the reason a delete is not "unrecoverable" despite there being no trash.

- [ ] **Step 3: Audit for stale references**

```sh
grep -rn "handleRename" docs/
grep -rn "New Folder\|New Structure\|Rename" docs/ | grep -v superpowers
```

Fix any doc that describes `handleRename` as owning the commit loop (it now delegates to `commitDirtyUnder`), or that enumerates the context menu as three items. Leave `docs/superpowers/` alone — those are commit-time snapshots.

- [ ] **Step 4: Verify cross-references resolve**

Confirm every `INDEX.md` entry you touched still matches its article's summary, and that no link points at a heading you renamed.

- [ ] **Step 5: Commit**

```bash
git add docs/
git commit -m "docs: cover Explorer duplicate, delete, and move"
```

---

## Final Verification

- [ ] **Full compile across all five source sets**

```sh
cmd.exe /c "cd /d H:\\Repo\\Garnet && gradlew.bat :26.2:clientClasses :26.2:classes :26.2:gametestClasses :26.2:clientTestClasses :26.2:testClasses"
```

- [ ] **All three test suites**

```sh
cmd.exe /c "cd /d H:\\Repo\\Garnet && gradlew.bat :26.2:test"
cmd.exe /c "cd /d H:\\Repo\\Garnet && gradlew.bat :26.2:runGameTest"
cmd.exe /c "cd /d H:\\Repo\\Garnet && gradlew.bat :26.2:runClientTest"
```

Read `versions/26.2/build/test-results/test/TEST-*.xml` for the unit suites rather than trusting a green task summary — and do not claim the work passes without having read that output.
