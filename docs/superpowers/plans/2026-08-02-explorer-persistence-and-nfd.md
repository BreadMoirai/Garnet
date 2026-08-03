# Explorer Session Persistence + NFD Folder Picker Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The Project Explorer reloads its tree and restores its expansion/selection when the player rejoins, and the Open Folder dialog uses LWJGL NativeFileDialog instead of tinyfd.

**Architecture:** A new client-only `ExplorerStateStore` round-trips `config/garnet-explorer.json`. `ExplorerLifecycle` gains a JOIN handler that requests the tree and arms a one-shot restore on `ExplorerTreeState`; the restore is applied at the snapshot dispatch site once a tree actually exists. `NfdFolderPicker` replaces `TinyfdFolderPicker`, with a platform-aware `runner` because macOS cannot use a worker thread.

**Tech Stack:** Kotlin, Fabric (fabric-networking-api-v1, fabric-lifecycle-events-v1), Gson, Jewel `TreeState`, LWJGL 3.4.1 `lwjgl-nfd`, Kotest via the `clientTest` source set.

**Spec:** `docs/superpowers/specs/2026-08-02-explorer-persistence-and-nfd-design.md`

## Global Constraints

- **Gradle task paths are `:26.2:<task>`**, not `:versions:26.2:<task>`. Always invoke Gradle as
  `cmd.exe /c "gradlew.bat <task>"` — no `./` prefix (cmd.exe cannot parse it).
- **Compile check (all five source sets):**
  `cmd.exe /c "gradlew.bat :26.2:classes :26.2:clientClasses :26.2:gametestClasses :26.2:clientTestClasses :26.2:testClasses"`
- **Client tests run only via `:26.2:runClientTest`**, in the foreground, with `timeout 600000`.
  Never pipe the output through `grep` (it buffers to an empty file) — redirect to a log file.
  Gradle test runs hang *after* printing the summary; read the log, then kill.
- **The clientTest XML reports are always empty.** Read the Kotest summary from the run log.
- **Every new Kotest spec must be registered** in the `specs = listOf(...)` block of
  `src/clientTest/kotlin/com/breadmoirai/garnet/test/ClientTestSentinel.kt`. Autoscan is off — an
  unregistered spec silently does not run.
- **LWJGL version is pinned to `3.4.1`** — MC 26.2's own LWJGL. `lwjgl-nfd` must match exactly or
  LWJGL's version check fails at load.
- **Commit directly to `main`.** No feature branches, no worktrees. No `Co-Authored-By` trailer.

---

### Task 1: `ExplorerStateStore` — the `garnet-explorer.json` round-trip

**Files:**
- Create: `src/client/kotlin/com/breadmoirai/garnet/config/ExplorerStateStore.kt`
- Test: `src/clientTest/kotlin/com/breadmoirai/garnet/test/ExplorerStateStoreSpec.kt`
- Modify: `src/clientTest/kotlin/com/breadmoirai/garnet/test/ClientTestSentinel.kt` (register the spec)

**Interfaces:**
- Consumes: nothing.
- Produces:
  - `data class ExplorerSession(val root: String, val expanded: Set<String>, val selected: String?)`
  - `ExplorerStateStore.load(): ExplorerSession?`
  - `ExplorerStateStore.save(root: String, expanded: Set<String>, selected: String?)`
  - `ExplorerStateStore.configFileForTest(file: java.io.File)` / `resetConfigFileForTest()`
  - Both in package `com.breadmoirai.garnet.config`.

- [ ] **Step 1: Write the failing test**

Create `src/clientTest/kotlin/com/breadmoirai/garnet/test/ExplorerStateStoreSpec.kt`:

```kotlin
package com.breadmoirai.garnet.test

import com.breadmoirai.garnet.config.ExplorerStateStore
import com.breadmoirai.garnet.harness.ClientSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlin.io.path.createTempDirectory

class ExplorerStateStoreSpec : ClientSpec({

    test("a session round-trips through garnet-explorer.json") {
        val dir = createTempDirectory("garnet-explorer")
        ExplorerStateStore.configFileForTest(dir.resolve("garnet-explorer.json").toFile())
        try {
            ExplorerStateStore.save("/tmp/proj", setOf("", "adders", "adders/full-adder"), "adders/full-adder")

            val loaded = ExplorerStateStore.load()!!
            loaded.root shouldBe "/tmp/proj"
            loaded.expanded shouldContainExactly setOf("", "adders", "adders/full-adder")
            loaded.selected shouldBe "adders/full-adder"
        } finally {
            ExplorerStateStore.resetConfigFileForTest()
            dir.toFile().deleteRecursively()
        }
    }

    test("a null selection round-trips as null rather than an empty string") {
        val dir = createTempDirectory("garnet-explorer-noselect")
        ExplorerStateStore.configFileForTest(dir.resolve("garnet-explorer.json").toFile())
        try {
            ExplorerStateStore.save("/tmp/proj", setOf(""), null)
            ExplorerStateStore.load()!!.selected.shouldBeNull()
        } finally {
            ExplorerStateStore.resetConfigFileForTest()
            dir.toFile().deleteRecursively()
        }
    }

    test("a missing file loads as null") {
        val dir = createTempDirectory("garnet-explorer-missing")
        ExplorerStateStore.configFileForTest(dir.resolve("absent.json").toFile())
        try {
            ExplorerStateStore.load().shouldBeNull()
        } finally {
            ExplorerStateStore.resetConfigFileForTest()
            dir.toFile().deleteRecursively()
        }
    }

    test("a malformed file loads as null instead of throwing") {
        val dir = createTempDirectory("garnet-explorer-malformed")
        val file = dir.resolve("garnet-explorer.json").toFile()
        file.writeText("{ this is not json")
        ExplorerStateStore.configFileForTest(file)
        try {
            ExplorerStateStore.load().shouldBeNull()
        } finally {
            ExplorerStateStore.resetConfigFileForTest()
            dir.toFile().deleteRecursively()
        }
    }

    test("a record with no root loads as null") {
        val dir = createTempDirectory("garnet-explorer-norder")
        val file = dir.resolve("garnet-explorer.json").toFile()
        file.writeText("""{"expanded":["adders"]}""")
        ExplorerStateStore.configFileForTest(file)
        try {
            ExplorerStateStore.load().shouldBeNull()
        } finally {
            ExplorerStateStore.resetConfigFileForTest()
            dir.toFile().deleteRecursively()
        }
    }

    test("saving with a blank root writes nothing") {
        val dir = createTempDirectory("garnet-explorer-blank")
        val file = dir.resolve("garnet-explorer.json").toFile()
        ExplorerStateStore.configFileForTest(file)
        try {
            ExplorerStateStore.save("", setOf("adders"), null)
            file.exists() shouldBe false
        } finally {
            ExplorerStateStore.resetConfigFileForTest()
            dir.toFile().deleteRecursively()
        }
    }
})
```

- [ ] **Step 2: Register the spec in the sentinel**

In `src/clientTest/kotlin/com/breadmoirai/garnet/test/ClientTestSentinel.kt`, add to the
`specs = listOf(...)` block, immediately after `ExplorerTreeStateSpec::class,`:

```kotlin
                        ExplorerStateStoreSpec::class,
```

- [ ] **Step 3: Run the compile to verify it fails**

Run: `cmd.exe /c "gradlew.bat :26.2:clientTestClasses"`
Expected: FAIL — `Unresolved reference: ExplorerStateStore`.

- [ ] **Step 4: Write the implementation**

Create `src/client/kotlin/com/breadmoirai/garnet/config/ExplorerStateStore.kt`:

```kotlin
package com.breadmoirai.garnet.config

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import net.fabricmc.loader.api.FabricLoader
import org.slf4j.LoggerFactory
import java.io.File

private val LOGGER = LoggerFactory.getLogger("Garnet")

/**
 * One session's Explorer tree state: which project [root] it was captured against, the `/`-joined
 * paths of the folders that were open, and the selected node's path (null when nothing was
 * selected). The project root itself is the empty-string path, so `expanded` normally contains `""`.
 */
data class ExplorerSession(
    val root: String,
    val expanded: Set<String>,
    val selected: String?,
)

/**
 * The `config/garnet-explorer.json` round-trip for the Explorer's per-session tree state.
 *
 * Deliberately NOT part of [ModConfig]: that object's contract is a pure [SharedSettings] round-trip
 * with no shadow state, and expansion/selection is client UI state a server must never see —
 * `SharedSettings` is read by the dedicated server. A separate file keeps that boundary intact.
 *
 * Exactly one record is stored, keyed by the project root it was captured against. Root swaps are
 * rare, so a per-root map would grow forever for no benefit; a record whose [ExplorerSession.root]
 * does not match the active root is simply discarded by the consumer.
 */
object ExplorerStateStore {
    private val defaultFile: File
        get() = FabricLoader.getInstance().configDir.resolve("garnet-explorer.json").toFile()

    private var overrideFile: File? = null
    private val configFile: File get() = overrideFile ?: defaultFile

    /** Test seam: redirect reads/writes at [file] instead of the real config directory. */
    fun configFileForTest(file: File) { overrideFile = file }
    fun resetConfigFileForTest() { overrideFile = null }

    /**
     * The persisted session, or null when there is none to restore — absent file, malformed JSON,
     * or a record with no `root`. A restore is a convenience, so every failure degrades to "open
     * the tree fresh" rather than propagating.
     */
    fun load(): ExplorerSession? {
        val file = configFile
        if (!file.exists()) return null
        return runCatching {
            file.reader().use { reader ->
                val json = JsonParser.parseReader(reader) as? JsonObject ?: return@use null
                val root = json.get("root")?.asString ?: return@use null
                if (root.isBlank()) return@use null
                val expanded = json.getAsJsonArray("expanded")
                    ?.map { it.asString }
                    ?.toSet()
                    ?: emptySet()
                val selected = json.get("selected")?.takeIf { !it.isJsonNull }?.asString
                ExplorerSession(root, expanded, selected)
            }
        }.onFailure { e ->
            LOGGER.warn("Failed to load Explorer session from {}", file.absolutePath, e)
        }.getOrNull()
    }

    /**
     * Overwrite the stored record. A blank [root] writes nothing: without a root there is no key to
     * match on later, so the record could only ever be discarded on load.
     */
    fun save(root: String, expanded: Set<String>, selected: String?) {
        if (root.isBlank()) return
        val file = configFile
        file.parentFile?.mkdirs()
        val json = JsonObject()
        json.addProperty("root", root)
        val arr = JsonArray()
        expanded.forEach { arr.add(it) }
        json.add("expanded", arr)
        if (selected != null) json.addProperty("selected", selected)
        runCatching {
            file.writeText(json.toString())
        }.onFailure { e ->
            LOGGER.error("Failed to save Explorer session to {}", file.absolutePath, e)
        }
    }
}
```

- [ ] **Step 5: Run the compile to verify it passes**

Run: `cmd.exe /c "gradlew.bat :26.2:clientClasses :26.2:clientTestClasses"`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add src/client/kotlin/com/breadmoirai/garnet/config/ExplorerStateStore.kt \
        src/clientTest/kotlin/com/breadmoirai/garnet/test/ExplorerStateStoreSpec.kt \
        src/clientTest/kotlin/com/breadmoirai/garnet/test/ClientTestSentinel.kt
git commit -m "feat(explorer): add ExplorerStateStore for per-session tree state"
```

---

### Task 2: One-shot restore on `ExplorerTreeState`

**Files:**
- Modify: `src/client/kotlin/com/breadmoirai/garnet/editor/ui/ExplorerTreeState.kt`
- Test: `src/clientTest/kotlin/com/breadmoirai/garnet/test/ExplorerTreeStateSpec.kt` (append tests)

**Interfaces:**
- Consumes: `ExplorerSession` from Task 1.
- Produces:
  - `ExplorerTreeState.armRestore(session: ExplorerSession?)`
  - `ExplorerTreeState.applyPendingRestore(root: FolderNode)`
  - `ExplorerTreeState.reset()` additionally clears the armed restore.

- [ ] **Step 1: Write the failing tests**

Append these tests inside the existing `ExplorerTreeStateSpec({ ... })` body, after the
`"expansion toggles Jewel's openNodes, keyed by path"` test. The `tree` val declared at the top of
the spec is reused.

Add these imports to the file's import block:

```kotlin
import com.breadmoirai.garnet.config.ExplorerSession
import com.breadmoirai.garnet.config.SharedSettings
```

Tests:

```kotlin
    test("an armed restore reopens the persisted folders when the snapshot lands") {
        val prior = SharedSettings.projectRootPath
        try {
            SharedSettings.projectRootPath = "/tmp/proj"
            runOnClient {
                ExplorerTreeState.reset()
                ExplorerTreeState.armRestore(
                    ExplorerSession("/tmp/proj", setOf("", "adders"), "adders/full-adder"),
                )
                ExplorerTreeState.applyPendingRestore(tree)
            }
            ExplorerTreeState.expandedPaths shouldContainExactly setOf("", "adders")
            ExplorerTreeState.selectedPath shouldBe "adders/full-adder"
        } finally {
            SharedSettings.projectRootPath = prior
        }
    }

    test("a restore captured against a different root is discarded") {
        val prior = SharedSettings.projectRootPath
        try {
            SharedSettings.projectRootPath = "/tmp/other"
            runOnClient {
                ExplorerTreeState.reset()
                ExplorerTreeState.armRestore(ExplorerSession("/tmp/proj", setOf("adders"), "adders"))
                ExplorerTreeState.applyPendingRestore(tree)
            }
            ExplorerTreeState.expandedPaths.shouldBeEmpty()
            ExplorerTreeState.selectedPath.shouldBeNull()
        } finally {
            SharedSettings.projectRootPath = prior
        }
    }

    test("paths that no longer exist are dropped from the restore") {
        val prior = SharedSettings.projectRootPath
        try {
            SharedSettings.projectRootPath = "/tmp/proj"
            runOnClient {
                ExplorerTreeState.reset()
                ExplorerTreeState.armRestore(
                    ExplorerSession("/tmp/proj", setOf("adders", "deleted-folder"), "gone.nbt"),
                )
                ExplorerTreeState.applyPendingRestore(tree)
            }
            ExplorerTreeState.expandedPaths shouldContainExactly setOf("adders")
            ExplorerTreeState.selectedPath.shouldBeNull()
        } finally {
            SharedSettings.projectRootPath = prior
        }
    }

    test("a file path is never restored as an expanded node") {
        val prior = SharedSettings.projectRootPath
        try {
            SharedSettings.projectRootPath = "/tmp/proj"
            runOnClient {
                ExplorerTreeState.reset()
                ExplorerTreeState.armRestore(ExplorerSession("/tmp/proj", setOf("dirty.nbt"), null))
                ExplorerTreeState.applyPendingRestore(tree)
            }
            ExplorerTreeState.expandedPaths.shouldBeEmpty()
        } finally {
            SharedSettings.projectRootPath = prior
        }
    }

    test("the restore is one-shot: a second snapshot does not clobber live expansion") {
        val prior = SharedSettings.projectRootPath
        try {
            SharedSettings.projectRootPath = "/tmp/proj"
            runOnClient {
                ExplorerTreeState.reset()
                ExplorerTreeState.armRestore(ExplorerSession("/tmp/proj", setOf("adders"), null))
                ExplorerTreeState.applyPendingRestore(tree)
                ExplorerTreeState.collapseAll()
                ExplorerTreeState.applyPendingRestore(tree)
            }
            ExplorerTreeState.expandedPaths.shouldBeEmpty()
        } finally {
            SharedSettings.projectRootPath = prior
        }
    }

    test("reset disarms a pending restore") {
        val prior = SharedSettings.projectRootPath
        try {
            SharedSettings.projectRootPath = "/tmp/proj"
            runOnClient {
                ExplorerTreeState.armRestore(ExplorerSession("/tmp/proj", setOf("adders"), null))
                ExplorerTreeState.reset()
                ExplorerTreeState.applyPendingRestore(tree)
            }
            ExplorerTreeState.expandedPaths.shouldBeEmpty()
        } finally {
            SharedSettings.projectRootPath = prior
        }
    }
```

Add these matcher imports if not already present in the file (`shouldBeEmpty` and
`shouldContainExactly` already are):

```kotlin
import io.kotest.matchers.nulls.shouldBeNull
```

- [ ] **Step 2: Run the compile to verify it fails**

Run: `cmd.exe /c "gradlew.bat :26.2:clientTestClasses"`
Expected: FAIL — `Unresolved reference: armRestore`.

- [ ] **Step 3: Write the implementation**

In `src/client/kotlin/com/breadmoirai/garnet/editor/ui/ExplorerTreeState.kt`, add these imports:

```kotlin
import com.breadmoirai.garnet.config.ExplorerSession
import com.breadmoirai.garnet.config.SharedSettings
import com.breadmoirai.garnet.editor.data.resolve
```

Add this field and these two functions to the `ExplorerTreeState` object, directly above the
existing `ROOT_PATH` constant:

```kotlin
    private var pendingRestore: ExplorerSession? = null

    /**
     * Arm a one-shot restore, applied by [applyPendingRestore] when the next tree snapshot lands.
     *
     * The restore cannot be applied at arm time: expanding the id `"adders/full-adder"` is
     * meaningless before a tree containing that id exists. A null [session] simply disarms.
     */
    fun armRestore(session: ExplorerSession?) {
        pendingRestore = session
    }

    /**
     * Apply an armed restore against the snapshot's [root], then disarm.
     *
     * One-shot on purpose: a later manual Refresh, or a snapshot pushed after a file operation,
     * must not clobber the expansion the player has changed since rejoining.
     *
     * No-op when nothing is armed, when the client has no configured root, or when the record was
     * captured against a different root — the latter is the correct outcome both after an Open
     * Folder swap and on a multiplayer server whose root differs from this client's config.
     *
     * Paths absent from [root] are dropped. Writing a stale id into `openNodes` would be inert
     * rather than harmful, but filtering stops the persisted set accumulating garbage across
     * sessions. Only folders can be expanded, so a persisted file path is dropped too.
     */
    fun applyPendingRestore(root: FolderNode) {
        val session = pendingRestore ?: return
        pendingRestore = null
        val active = SharedSettings.projectRootPath
        if (active.isBlank() || active != session.root) return
        treeState.openNodes = session.expanded.filter { root.resolve(it) is FolderNode }.toSet()
        treeState.selectedKeys = session.selected
            ?.takeIf { root.resolve(it) != null }
            ?.let { setOf(it) }
            ?: emptySet()
    }
```

Then update `reset()` to disarm:

```kotlin
    /** Test/reset hook: drops selection, expansion and any armed restore. */
    fun reset() {
        treeState = newTreeState()
        // A disconnect between JOIN and the first snapshot must not leak an armed restore into the
        // next session, where it would be applied against a different project's tree.
        pendingRestore = null
    }
```

- [ ] **Step 4: Run the compile to verify it passes**

Run: `cmd.exe /c "gradlew.bat :26.2:clientClasses :26.2:clientTestClasses"`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add src/client/kotlin/com/breadmoirai/garnet/editor/ui/ExplorerTreeState.kt \
        src/clientTest/kotlin/com/breadmoirai/garnet/test/ExplorerTreeStateSpec.kt
git commit -m "feat(explorer): restore persisted expansion and selection when a snapshot lands"
```

---

### Task 3: Wire the lifecycle — auto-load on join, save on exit

**Files:**
- Modify: `src/client/kotlin/com/breadmoirai/garnet/editor/ui/ExplorerLifecycle.kt`
- Modify: `src/client/kotlin/com/breadmoirai/garnet/editor/network/EditorClientNetworking.kt:14-16`

**Interfaces:**
- Consumes: `ExplorerStateStore.load`/`save` (Task 1), `ExplorerTreeState.armRestore`/
  `applyPendingRestore` (Task 2).
- Produces: nothing new for later tasks.

This task has no new unit test. Its whole content is Fabric event registration and a send guard —
there is no seam to drive without a live connection, and the behavior it wires (store round-trip,
restore filtering) is already covered by Tasks 1 and 2. It is verified by the full `runClientTest`
suite staying green plus the manual check in Step 5.

- [ ] **Step 1: Rewrite `ExplorerLifecycle.kt`**

Replace the whole file with:

```kotlin
package com.breadmoirai.garnet.editor.ui

import com.breadmoirai.garnet.config.ExplorerStateStore
import com.breadmoirai.garnet.config.SharedSettings
import com.breadmoirai.garnet.editor.network.ListEditorTreeC2S
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking

/**
 * Explorer session lifecycle: request the tree and restore last session's expansion on join,
 * persist it and reset on the way out.
 *
 * Every networking-event body runs inside `mc.execute { ... }` because `fabric-networking-api-v1`
 * fires these events from two sites in `ClientConnectionMixin` — the main thread, or a **Netty
 * event-loop thread**, whichever wins the CAS.
 */
fun registerExplorerLifecycle() {
    ClientPlayConnectionEvents.JOIN.register { _, _, mc ->
        mc.execute {
            // Arm before sending: the snapshot reply is what consumes the restore, and on a
            // singleplayer join the reply can land in the very next tick.
            ExplorerTreeState.armRestore(ExplorerStateStore.load())
            // A vanilla server without the mod has no receiver registered for this payload, and
            // sending it anyway throws. canSend is the standard Fabric guard.
            if (ClientPlayNetworking.canSend(ListEditorTreeC2S.TYPE)) {
                ClientPlayNetworking.send(ListEditorTreeC2S.INSTANCE)
            }
        }
    }

    ClientPlayConnectionEvents.DISCONNECT.register { _, mc ->
        mc.execute {
            // Save BEFORE the resets below: reading afterwards would persist empty sets.
            saveExplorerSession()
            // Per-world Explorer state: the tree snapshot and its expansion/selection are stale once
            // the session that produced them ends. Reset here, not in DockState.closeAll(), which
            // stays free of IDE-state and Minecraft dependencies.
            ProjectTreeState.reset()
            ExplorerTreeState.reset()
        }
    }

    // DISCONNECT covers quit-to-title, a multiplayer disconnect and a kick. It does not reliably
    // cover closing the game window from inside a world, which is the common way a player ends a
    // session — hence this second, idempotent save.
    ClientLifecycleEvents.CLIENT_STOPPING.register { _ ->
        saveExplorerSession()
    }
}

/**
 * Persist the live tree state against the configured root.
 *
 * Skipped when no snapshot was ever loaded this session: the tree state is empty because the player
 * never saw a tree, and writing it would overwrite a good record with nothing. That is exactly the
 * case when the player joins a vanilla server, or quits before the snapshot arrives.
 */
private fun saveExplorerSession() {
    val root = SharedSettings.projectRootPath
    if (root.isBlank()) return
    if (ProjectTreeState.snapshot == null) return
    ExplorerStateStore.save(root, ExplorerTreeState.expandedPaths, ExplorerTreeState.selectedPath)
}
```

- [ ] **Step 2: Consume the restore at the snapshot dispatch site**

In `src/client/kotlin/com/breadmoirai/garnet/editor/network/EditorClientNetworking.kt`, add the
import:

```kotlin
import com.breadmoirai.garnet.editor.ui.ExplorerTreeState
```

and replace the `EditorTreeSnapshotS2C` receiver (currently lines 14-16) with:

```kotlin
        ClientPlayNetworking.registerGlobalReceiver(EditorTreeSnapshotS2C.TYPE) { payload, ctx ->
            ctx.client().execute {
                ProjectTreeState.onSnapshot(payload)
                // Driven from here rather than from inside onSnapshot so ProjectTreeState (tree
                // data) and ExplorerTreeState (tree interaction state) stay passive siblings that
                // do not reach into each other — the separation both their docstrings assert.
                ExplorerTreeState.applyPendingRestore(payload.root)
            }
        }
```

- [ ] **Step 3: Run the compile**

Run: `cmd.exe /c "gradlew.bat :26.2:classes :26.2:clientClasses :26.2:gametestClasses :26.2:clientTestClasses :26.2:testClasses"`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Run the client test suite**

Run (foreground, `timeout 600000`, redirect to a log — do NOT pipe through `grep`):

```bash
cmd.exe /c "gradlew.bat :26.2:runClientTest" > /tmp/claude-1000/-mnt-h-Repo-RedstoneSpecs/clienttest.log 2>&1
```

Then read the log for the Kotest summary line. Expected: 0 failed. The run hangs after printing the
summary — poll the log, then kill the process.

- [ ] **Step 5: Manual verification**

Launch the client, use **Open Folder…** from the Explorer kebab menu to pick a project, expand a
couple of folders, select a file, then quit to title and rejoin. The tree should populate without
touching Refresh, with the same folders expanded and the same file selected. Confirm
`config/garnet-explorer.json` exists and contains those paths.

- [ ] **Step 6: Commit**

```bash
git add src/client/kotlin/com/breadmoirai/garnet/editor/ui/ExplorerLifecycle.kt \
        src/client/kotlin/com/breadmoirai/garnet/editor/network/EditorClientNetworking.kt
git commit -m "feat(explorer): load the tree on join and persist session state on exit"
```

---

### Task 4: Replace tinyfd with LWJGL NativeFileDialog

**Files:**
- Modify: `build.gradle.kts` (dependencies block, after the existing `kotlinx-coroutines-core` line)
- Modify: `src/client/kotlin/com/breadmoirai/garnet/editor/ui/FolderPicker.kt`
- Modify: `src/client/kotlin/com/breadmoirai/garnet/editor/ui/RootPickerController.kt:22-23,60-61`

**Interfaces:**
- Consumes: the existing `FolderPicker` fun-interface (`pick(title: String, default: String?): String?`).
- Produces: `object NfdFolderPicker : FolderPicker` in `com.breadmoirai.garnet.editor.ui`.
  `TinyfdFolderPicker` is **deleted** — no later task may reference it.

No new unit test: the LWJGL binding cannot be exercised headlessly (the call blocks on a real modal),
and every seam around it is already covered by `RootPickerSpec`, which must keep passing unchanged.
Verification is the compile, the existing suite, and the manual dialog check in Step 6.

- [ ] **Step 1: Add the dependency**

In `build.gradle.kts`, inside the `dependencies { }` block, immediately after the
`implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.1")` line, add:

```kotlin
    // LWJGL NativeFileDialog backs the Explorer's Open Folder dialog (editor/ui/NfdFolderPicker.kt).
    // MC 26.2 ships LWJGL 3.4.1 with lwjgl-tinyfd but NOT lwjgl-nfd, so the module and its natives
    // are added here. The version MUST stay pinned to MC's own LWJGL: lwjgl-core comes from MC, and
    // a module built against a different version trips LWJGL's load-time version check.
    val lwjglVersion = "3.4.1"
    "clientImplementation"("org.lwjgl:lwjgl-nfd:$lwjglVersion")
    include("org.lwjgl:lwjgl-nfd:$lwjglVersion")
    for (nativesClassifier in listOf(
        "natives-windows", "natives-windows-arm64",
        "natives-linux", "natives-linux-arm64",
        "natives-macos", "natives-macos-arm64",
    )) {
        "clientRuntimeOnly"("org.lwjgl:lwjgl-nfd:$lwjglVersion:$nativesClassifier")
        include("org.lwjgl:lwjgl-nfd:$lwjglVersion:$nativesClassifier")
    }
```

Note: this is the project's first use of Loom's `include` (jar-in-jar). If `include` is unresolved
in this Loom version, drop the three `include(...)` calls and keep only the `clientImplementation` /
`clientRuntimeOnly` declarations — the dev runtime works either way, and packaging is a separate,
already-open question for skiko/Compose/Jewel too. Report which path you took.

- [ ] **Step 2: Verify the dependency resolves**

Run: `cmd.exe /c "gradlew.bat :26.2:clientClasses"`
Expected: BUILD SUCCESSFUL, with Gradle downloading `lwjgl-nfd-3.4.1*.jar`.

- [ ] **Step 3: Replace the picker implementation**

Replace the whole of `src/client/kotlin/com/breadmoirai/garnet/editor/ui/FolderPicker.kt` with:

```kotlin
package com.breadmoirai.garnet.editor.ui

import org.lwjgl.system.MemoryStack
import org.lwjgl.util.nfd.NativeFileDialog
import org.lwjgl.util.nfd.NativeFileDialog.NFD_OKAY

/** Selects a folder from the OS. [pick] blocks — see `RootPickerController.runner` for where. */
fun interface FolderPicker {
    fun pick(title: String, default: String?): String?
}

/**
 * Default impl, backed by LWJGL's NativeFileDialog: the real `IFileDialog` / `NSOpenPanel` / GTK
 * folder chooser rather than tinyfd's legacy Win32 folder browser. Returns null on cancel or error.
 *
 * `NFD_Init`, `NFD_PickFolder` and `NFD_Quit` all run inside this one call, on one thread, on
 * purpose: on Windows NFD's init performs the COM `CoInitializeEx` on the **calling** thread, so
 * splitting the calls across threads leaves the dialog thread without an initialised apartment and
 * the dialog fails to open.
 *
 * [title] is ignored: NFD's pick-folder API exposes only `defaultPath` and `parentWindow` — there
 * is no title field, and the OS supplies its own. The parameter stays for [FolderPicker]'s shape.
 */
object NfdFolderPicker : FolderPicker {
    override fun pick(title: String, default: String?): String? {
        if (NativeFileDialog.NFD_Init() != NFD_OKAY) return null
        try {
            MemoryStack.stackPush().use { stack ->
                val out = stack.mallocPointer(1)
                if (NativeFileDialog.NFD_PickFolder(out, default) != NFD_OKAY) return null
                val path = out.getStringUTF8(0)
                // NFD allocates the result natively; the binding does not free it for us.
                NativeFileDialog.NFD_FreePath(out.get(0))
                return path
            }
        } finally {
            NativeFileDialog.NFD_Quit()
        }
    }
}
```

- [ ] **Step 4: Make `RootPickerController`'s picker and runner platform-aware**

In `src/client/kotlin/com/breadmoirai/garnet/editor/ui/RootPickerController.kt`, add the import:

```kotlin
import org.lwjgl.system.Platform
```

Change the `picker` and `runner` declarations (currently lines 22-23) to:

```kotlin
    var picker: FolderPicker = NfdFolderPicker
    var runner: (Runnable) -> Unit = defaultRunner()
```

Change the matching lines in `resetForTest()` (currently lines 60-61) to:

```kotlin
        picker = NfdFolderPicker
        runner = defaultRunner()
```

Add this private function at file scope, below the `RootPickerController` object:

```kotlin
/**
 * Where the blocking dialog runs.
 *
 * Everywhere but macOS: a worker thread, because the dialog blocks until dismissed and the caller is
 * the render thread.
 *
 * On macOS: **inline on the calling (render) thread**. NFD drives `NSOpenPanel`, which must be
 * called from the AppKit main thread — and under `-XstartOnFirstThread` that thread *is* Minecraft's
 * render thread, so the worker-thread rule and the AppKit rule cannot both hold. The game visibly
 * freezes behind the modal until it is dismissed; that is the accepted trade over an AppKit crash.
 */
private fun defaultRunner(): (Runnable) -> Unit =
    if (Platform.get() == Platform.MACOSX) Runnable::run
    else { r -> Thread(r, "garnet-folder-picker").start() }
```

Also update the `openFolder` KDoc's mention of the seams if it names tinyfd — it currently says
"[picker] (native dialog)", which stays accurate.

- [ ] **Step 5: Compile and run the suite**

Run: `cmd.exe /c "gradlew.bat :26.2:classes :26.2:clientClasses :26.2:gametestClasses :26.2:clientTestClasses :26.2:testClasses"`
Expected: BUILD SUCCESSFUL, and `grep -rn "Tinyfd\|TinyFileDialogs" src/` returns nothing.

Then run the client tests as in Task 3 Step 4. `RootPickerSpec` must still pass — it overrides
`picker` and `runner`, so it never touches the native.

- [ ] **Step 6: Manual verification**

Launch the client and open the Explorer kebab → **Open Folder…**. On Windows this must now be the
modern folder dialog (breadcrumb bar, sidebar, type-to-filter), not the old tree-only browser.
Confirm picking a folder still re-roots the tree, and that cancelling changes nothing.

- [ ] **Step 7: Commit**

```bash
git add build.gradle.kts \
        src/client/kotlin/com/breadmoirai/garnet/editor/ui/FolderPicker.kt \
        src/client/kotlin/com/breadmoirai/garnet/editor/ui/RootPickerController.kt
git commit -m "feat(explorer): replace tinyfd folder picker with LWJGL NativeFileDialog"
```

---

### Task 5: Documentation sync

**Files:**
- Modify: `docs/ui/dock-dialogs.md` (the "Native OS dialogs block" section)
- Modify: `docs/architecture/redstone-project.md` (the Explorer paragraph)
- Modify: `docs/use-cases/redstone-project.md` (near UC-MAN-09)
- Create: `docs/persistence/explorer-session-state.md`
- Modify: `docs/persistence/INDEX.md`

- [ ] **Step 1: Update `docs/ui/dock-dialogs.md`**

Its "Native OS dialogs block — run them off the render thread" section names
`org.lwjgl.util.tinyfd.TinyFileDialogs` as the picker and states the worker-thread rule without
exception. Rewrite that section to cover: NFD as the picker and why (tinyfd's legacy Win32 browser);
that `lwjgl-nfd` is added by this mod because MC ships tinyfd but not nfd, pinned to MC's LWJGL
3.4.1; the same-thread `NFD_Init`/`NFD_PickFolder`/`NFD_Quit` rule and the COM reason for it; and the
macOS exception where the dialog runs inline on the render thread. Keep `RootPickerController` as the
named reference for the seam layout. Update the frontmatter `tags` (`tinyfd` → `nfd`) and `summary`.

- [ ] **Step 2: Write `docs/persistence/explorer-session-state.md`**

Frontmatter, then the article:

```markdown
---
title: Explorer session state
tags: [storage, config, explorer, client, persistence]
summary: The Explorer's expansion and selection persist to config/garnet-explorer.json, keyed by project root, restored one-shot when the first tree snapshot lands after a join.
---
```

Cover, in prose: why this is a separate file from `garnet.json` (ModConfig's "pure SharedSettings
round-trip, no shadow state" invariant, and that `SharedSettings` is read by dedicated servers while
expansion is client UI state); the single-record-keyed-by-root design and why not a map; why the
restore is armed at JOIN but applied at the snapshot dispatch site; why it is one-shot; why
`saveExplorerSession` skips when `ProjectTreeState.snapshot == null`; and the two save trigger
points. Register it in `docs/persistence/INDEX.md` as
`- [Explorer session state](explorer-session-state.md) — <summary>` with its tags, matching the
format of the entries already there.

- [ ] **Step 3: Update `docs/architecture/redstone-project.md`**

The paragraph describing `editor/ui/ExplorerTreeState` and `ProjectTreeState` currently says the
tree only reloads on an explicit user click. That is now false — correct it to describe the JOIN
auto-load and link to `../persistence/explorer-session-state.md`.

- [ ] **Step 4: Update `docs/use-cases/redstone-project.md`**

UC-MAN-09 describes re-rooting from the native folder picker; update any mention of tinyfd. Add a
sibling use case for rejoining a world and finding the tree already loaded with last session's
folders expanded, following the numbering and format of the surrounding entries.

- [ ] **Step 5: Verify no dangling references**

Run: `grep -rn "tinyfd\|TinyFileDialogs\|Tinyfd" docs/ --include="*.md" | grep -v superpowers/`
Expected: no hits that describe tinyfd as the *current* picker. Historical notes in a "retired"
section are fine if clearly marked as such.

Run: `grep -rn "explorer-session-state" docs/persistence/INDEX.md`
Expected: one hit.

- [ ] **Step 6: Commit**

```bash
git add docs/
git commit -m "docs: Explorer session persistence and the NFD picker swap"
```

---

## Self-Review

**Spec coverage:**

| Spec section | Task |
|---|---|
| `ExplorerStateStore` + JSON shape + test seams | 1 |
| Keying by root, single record, blank-root skip | 1 (store), 2 (mismatch discard) |
| Auto-load on join + `canSend` guard | 3 |
| Restore timing, one-shot, `pendingRestore` on `ExplorerTreeState` | 2 (mechanism), 3 (arm + consume sites) |
| Stale paths dropped, unresolvable selection cleared | 2 |
| `reset()` clears `pendingRestore` | 2 |
| Save at DISCONNECT (before reset) + CLIENT_STOPPING | 3 |
| NFD dependency, natives, jar-in-jar | 4 |
| Same-thread Init/Pick/Quit | 4 |
| macOS inline on render thread | 4 |
| `title` unused, parameter retained | 4 |
| Testing: store spec, restore spec, RootPickerSpec unchanged | 1, 2, 4 |
| Docs to update (4 files listed) | 5 |

No gaps.

**Type consistency:** `ExplorerSession(root, expanded, selected)` is defined in Task 1 and consumed
with those exact property names in Tasks 2 and 3. `armRestore(ExplorerSession?)` /
`applyPendingRestore(FolderNode)` are defined in Task 2 and called with matching types in Task 3.
`ExplorerStateStore.save(root, expanded, selected)` matches its Task 3 call site.
`NfdFolderPicker` is defined in Task 4 Step 3 and referenced in Task 4 Step 4 only.
`FolderNode.resolve(String): FileTreeNode?` and `FolderNode` come from
`com.breadmoirai.garnet.editor.data` (`FileTree.kt`), already on the client classpath.

**Verified against the real artifacts:** `NFD_PickFolder(PointerBuffer, CharSequence): Int`,
`NFD_Init(): Int`, `NFD_Quit()`, `NFD_FreePath(long)` and `NFD_OKAY` all confirmed by `javap` on
`lwjgl-nfd-3.4.1.jar`; all six natives classifiers confirmed present on Maven Central.
