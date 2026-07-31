# Structure Auto-Save with Local History — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Standalone `.nbt` structures auto-save directly to disk on a debounce after each edit, backed by a JetBrains-style local-history store, replacing the `.nbt.unsaved` sidecar model.

**Architecture:** A mixin hook feeds every successful server `setBlock` to `StructureEditWatcher`, which attributes the position to a placed structure and grows a per-structure dirty box in `StructureAutoSave`. An end-of-server-tick pass commits structures whose edits have gone quiet (or which have been dirty too long): it captures only `union(placedBox, dirtyBox)` rather than the whole 144×384×144 region, writes a revision into `<instance>/.garnet/local-history/<stem>-<hash8>/`, rewrites the `.nbt`, and broadcasts `StructureAutoSavedS2C`.

**Tech Stack:** Kotlin, Fabric (MC 26.2), Stonecutter single-version slice, SpongePowered Mixin, Gson, Kotest via the in-game gametest/clientTest harnesses.

**Spec:** `docs/superpowers/specs/2026-07-31-structure-autosave-local-history-design.md`

## Global Constraints

- **Gradle must be invoked as `cmd.exe /c "gradlew.bat ..."`** — no `./` prefix, which `cmd.exe` cannot parse.
- **Stonecutter task paths are `:26.2:<task>`**, not `:versions:26.2:<task>`.
- **Full compile check** (5 source sets): `cmd.exe /c "gradlew.bat :26.2:classes :26.2:clientClasses :26.2:gametestClasses :26.2:clientTestClasses :26.2:testClasses"`
- **Gametests:** `cmd.exe /c "gradlew.bat :26.2:runGameTest"`. Run in the **foreground** with `timeout: 600000`; a backgrounded run is lost. Kotest's `--tests` filter does not work here — run the whole suite and read `build/reports/garnet/gametest`.
- **Client tests:** `cmd.exe /c "gradlew.bat :26.2:runClientTest"`, same foreground rule. The clientTest XML report is always empty; read the launcher summary in the console output.
- **Every new Kotest spec must be registered** in the explicit `specs = listOf(...)` in `GametestSentinel` / `ClientTestSentinel`. Autoscan is off — an unregistered spec silently does not run.
- **Do not rely on `level.setBlock` triggering the mixin inside gametests** — it is flaky under the harness. Drive `StructureEditWatcher.onBlockChanged` directly in tests.
- **`internal` in Kotlin is per-source-set**, so gametest cannot see `internal` declarations from `main`. Anything a test touches must be `public`.
- **Commit messages carry no `Co-Authored-By` trailer** and no Claude attribution.
- **Work directly on `main`.** No feature branches or worktrees.
- **After any source change, audit `docs/`** per `CLAUDE.md` before calling a task done. Task 8 covers the bulk of it; earlier tasks that add a public API note it there.

---

## File Structure

**Created:**

| File | Responsibility |
|---|---|
| `src/main/kotlin/com/breadmoirai/garnet/history/Revision.kt` | The `Revision` / `HistoryIndex` data model and its Gson (de)serialization |
| `src/main/kotlin/com/breadmoirai/garnet/history/LocalHistoryStore.kt` | Path keying, revision writes, index round-trip, pruning, directory moves |
| `src/main/kotlin/com/breadmoirai/garnet/editor/world/StructureAutoSave.kt` | Per-server dirty state: dirty box, first/last edit tick, due-for-commit predicate |
| `src/main/kotlin/com/breadmoirai/garnet/editor/world/StructureEditWatcher.kt` | Maps a changed block position to the owning placed structure and records the edit |
| `src/main/kotlin/com/breadmoirai/garnet/editor/world/StructureCommit.kt` | The commit itself: capture, diff, revision, write, broadcast; plus the tick pass |
| `src/gametest/kotlin/com/breadmoirai/garnet/test/history/LocalHistoryStoreSpec.kt` | Filesystem-level history tests (no world needed) |
| `src/gametest/kotlin/com/breadmoirai/garnet/test/editor/StructureAutoSaveSpec.kt` | Dirty tracking, debounce, cap, commit, force-commit, disabled-flag |
| `src/clientTest/kotlin/com/breadmoirai/garnet/test/ModConfigSpec.kt` | `garnet.json` round-trip for every setting |
| `docs/persistence/local-history.md` | The store's on-disk contract and keying rationale |

**Modified:**

| File | Change |
|---|---|
| `src/main/kotlin/com/breadmoirai/garnet/config/SharedSettings.kt` | Seven new tunables |
| `src/client/kotlin/com/breadmoirai/garnet/config/ModConfig.kt` | Round-trip every setting; `projectRootPath` delegates to `SharedSettings` |
| `src/main/kotlin/com/breadmoirai/garnet/structure/StructurePersistence.kt` | Add `captureAutoFitIn`; later remove the sidecar functions |
| `src/main/kotlin/com/breadmoirai/garnet/editor/world/EditorDimRegistry.kt` | Add `structureSubpathAt(pos)` |
| `src/main/java/com/breadmoirai/garnet/mixin/ServerLevelSetBlockMixin.java` | Notify `StructureEditWatcher` before the recorder-only early return |
| `src/main/kotlin/com/breadmoirai/garnet/Garnet.kt` | Register `END_SERVER_TICK`; swap `BEFORE_SAVE` to `StructureCommit.commitAll` |
| `src/main/kotlin/com/breadmoirai/garnet/editor/network/EditorPackets.kt` | Add `StructureAutoSavedS2C`; drop `hasUnsaved` and `DiscardStructureC2S` |
| `src/main/kotlin/com/breadmoirai/garnet/editor/network/EditorNetworking.kt` | Make `rootFor` public; rewire place/save/rename; delete discard + flush |
| `src/main/kotlin/com/breadmoirai/garnet/editor/data/FileTree.kt` | Drop `FileNode.hasUnsaved` |
| `src/client/kotlin/com/breadmoirai/garnet/editor/network/EditorClientNetworking.kt` | Receive `StructureAutoSavedS2C` |
| `src/client/kotlin/com/breadmoirai/garnet/editor/ui/ProjectTreeState.kt` | `onAutoSaved` handler |
| `src/client/kotlin/com/breadmoirai/garnet/editor/ui/ProjectExplorerPanel.kt` | `●` marker driven only by `currentSubpath` |
| `src/gametest/kotlin/com/breadmoirai/garnet/test/GametestSentinel.kt` | Register two new specs |
| `src/clientTest/kotlin/com/breadmoirai/garnet/test/ClientTestSentinel.kt` | Register `ModConfigSpec` |
| `src/gametest/.../EditorStructureNetworkSpec.kt`, `EditorFileOpsNetworkSpec.kt` | Rewrite the sidecar assertions |
| `docs/**` | Sidecar references, new article registration |

`StructureSidecarPersistenceSpec` is **left alone** despite its name — it covers the spec-cell fixed-`bounds` `save`/`load`/`hasChanges` path, not the `.nbt.unsaved` buffer.

---

### Task 1: Configuration surface

**Files:**
- Modify: `src/main/kotlin/com/breadmoirai/garnet/config/SharedSettings.kt`
- Modify: `src/client/kotlin/com/breadmoirai/garnet/config/ModConfig.kt`
- Test: `src/clientTest/kotlin/com/breadmoirai/garnet/test/ModConfigSpec.kt` (create)
- Modify: `src/clientTest/kotlin/com/breadmoirai/garnet/test/ClientTestSentinel.kt`

**Interfaces:**
- Produces: `SharedSettings.autoSaveEnabled: Boolean`, `autoSaveDebounceTicks: Int`, `autoSaveMaxDirtyTicks: Int`, `localHistoryEnabled: Boolean`, `localHistoryDays: Int`, `localHistoryMaxRevisions: Int`, `localHistoryDir: String`. `ModConfig.load()`, `ModConfig.save()`, `ModConfig.configFileForTest(file: File)`.

- [ ] **Step 1: Write the failing test**

Create `src/clientTest/kotlin/com/breadmoirai/garnet/test/ModConfigSpec.kt`:

```kotlin
package com.breadmoirai.garnet.test

import com.breadmoirai.garnet.config.ModConfig
import com.breadmoirai.garnet.config.SharedSettings
import com.breadmoirai.garnet.harness.ClientSpec
import io.kotest.matchers.shouldBe
import kotlin.io.path.createTempDirectory

class ModConfigSpec : ClientSpec({

    test("every setting round-trips through garnet.json") {
        val dir = createTempDirectory("garnet-config")
        val file = dir.resolve("garnet.json").toFile()
        ModConfig.configFileForTest(file)
        try {
            SharedSettings.projectRootPath = "/tmp/proj"
            SharedSettings.autoSaveEnabled = false
            SharedSettings.autoSaveDebounceTicks = 7
            SharedSettings.autoSaveMaxDirtyTicks = 77
            SharedSettings.localHistoryEnabled = false
            SharedSettings.localHistoryDays = 3
            SharedSettings.localHistoryMaxRevisions = 9
            SharedSettings.localHistoryDir = "/tmp/hist"
            SharedSettings.structureRegionChunks = 2
            ModConfig.save()

            // Clobber every field, then reload: each must come back from disk.
            SharedSettings.projectRootPath = ""
            SharedSettings.autoSaveEnabled = true
            SharedSettings.autoSaveDebounceTicks = 20
            SharedSettings.autoSaveMaxDirtyTicks = 600
            SharedSettings.localHistoryEnabled = true
            SharedSettings.localHistoryDays = 5
            SharedSettings.localHistoryMaxRevisions = 100
            SharedSettings.localHistoryDir = ""
            SharedSettings.structureRegionChunks = 9
            ModConfig.load()

            SharedSettings.projectRootPath shouldBe "/tmp/proj"
            SharedSettings.autoSaveEnabled shouldBe false
            SharedSettings.autoSaveDebounceTicks shouldBe 7
            SharedSettings.autoSaveMaxDirtyTicks shouldBe 77
            SharedSettings.localHistoryEnabled shouldBe false
            SharedSettings.localHistoryDays shouldBe 3
            SharedSettings.localHistoryMaxRevisions shouldBe 9
            SharedSettings.localHistoryDir shouldBe "/tmp/hist"
            SharedSettings.structureRegionChunks shouldBe 2
        } finally {
            ModConfig.resetConfigFileForTest()
            dir.toFile().deleteRecursively()
        }
    }

    test("a missing config file leaves defaults untouched") {
        val dir = createTempDirectory("garnet-config-missing")
        ModConfig.configFileForTest(dir.resolve("absent.json").toFile())
        try {
            SharedSettings.autoSaveDebounceTicks = 20
            ModConfig.load()
            SharedSettings.autoSaveDebounceTicks shouldBe 20
        } finally {
            ModConfig.resetConfigFileForTest()
            dir.toFile().deleteRecursively()
        }
    }

    test("a config file missing a key keeps that setting's current value") {
        val dir = createTempDirectory("garnet-config-partial")
        val file = dir.resolve("garnet.json").toFile()
        file.writeText("""{"projectRootPath":"/only/this"}""")
        ModConfig.configFileForTest(file)
        try {
            SharedSettings.localHistoryDays = 42
            ModConfig.load()
            SharedSettings.projectRootPath shouldBe "/only/this"
            SharedSettings.localHistoryDays shouldBe 42
        } finally {
            ModConfig.resetConfigFileForTest()
            dir.toFile().deleteRecursively()
        }
    }
})
```

Register it in `ClientTestSentinel`'s `specs = listOf(...)` by adding `ModConfigSpec::class`. The spec classes live in the same `com.breadmoirai.garnet.test` package, so no import is needed there. `com.breadmoirai.garnet.harness.ClientSpec` is the confirmed base for non-world client specs — `ExplorerTreeStateSpec.kt` is a working example to copy structure from.

- [ ] **Step 2: Run the test to verify it fails**

Run: `cmd.exe /c "gradlew.bat :26.2:runClientTest"` (foreground, `timeout: 600000`)
Expected: compile failure — `configFileForTest` and the new `SharedSettings` fields are unresolved.

- [ ] **Step 3: Add the settings**

Replace the body of `src/main/kotlin/com/breadmoirai/garnet/config/SharedSettings.kt`:

```kotlin
package com.breadmoirai.garnet.config

object SharedSettings {
    var projectCellSize: net.minecraft.core.Vec3i = net.minecraft.core.Vec3i(32, 32, 32)
    var projectCellGap: Int = 4
    var projectRowMax: Int = 8
    var projectGridYBase: Int = 64
    var projectRootPath: String = ""

    /** Side length, in chunks, of a standalone structure's build region (full world height). */
    var structureRegionChunks: Int = 9

    // === Auto-save ===

    /** When false, structures commit only via SaveStructureC2S and the world-save/stop backstops. */
    var autoSaveEnabled: Boolean = true

    /** Ticks of quiet after the last edit before a dirty structure commits. 20 ticks = 1s. */
    var autoSaveDebounceTicks: Int = 20

    /**
     * Ticks a structure may stay continuously dirty before committing regardless of the debounce,
     * so an uninterrupted build session still checkpoints. 600 ticks = 30s.
     */
    var autoSaveMaxDirtyTicks: Int = 600

    // === Local history ===

    /** When false, commits still happen but no revisions are recorded. */
    var localHistoryEnabled: Boolean = true

    /** Revisions older than this many days are pruned on write. Matches JetBrains' default. */
    var localHistoryDays: Int = 5

    /** Hard cap on revisions kept per structure, applied after the age cutoff. */
    var localHistoryMaxRevisions: Int = 100

    /** Blank means `<gameDir>/.garnet/local-history`. */
    var localHistoryDir: String = ""
}
```

- [ ] **Step 4: Rewrite ModConfig to round-trip everything**

Replace `src/client/kotlin/com/breadmoirai/garnet/config/ModConfig.kt`:

```kotlin
package com.breadmoirai.garnet.config

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import net.fabricmc.loader.api.FabricLoader
import org.slf4j.LoggerFactory
import java.io.File

private val LOGGER = LoggerFactory.getLogger("Garnet")

/**
 * The `config/garnet.json` round-trip for every [SharedSettings] field.
 *
 * [SharedSettings] is the single copy of each value — this object holds no shadow state, so a
 * caller that mutates a setting directly and then calls [save] persists exactly what it set.
 * [projectRootPath] survives only as a delegating property because [RootPickerController] writes
 * through it.
 *
 * Known limitation: this lives in the client source set, so a dedicated server never loads it and
 * runs the compiled defaults instead.
 */
object ModConfig {
    private val defaultFile: File
        get() = FabricLoader.getInstance().configDir.resolve("garnet.json").toFile()

    private var overrideFile: File? = null
    private val configFile: File get() = overrideFile ?: defaultFile

    /** Test seam: redirect reads/writes at [file] instead of the real config directory. */
    fun configFileForTest(file: File) { overrideFile = file }
    fun resetConfigFileForTest() { overrideFile = null }

    var projectRootPath: String
        get() = SharedSettings.projectRootPath
        set(value) { SharedSettings.projectRootPath = value }

    fun load() {
        val file = configFile
        if (!file.exists()) return
        runCatching {
            file.reader().use { reader ->
                val json = JsonParser.parseReader(reader) as? JsonObject ?: return@use
                // Absent keys leave the in-memory value alone, so a hand-edited partial config
                // never silently resets unrelated settings to their compiled defaults.
                json.get("projectRootPath")?.let { SharedSettings.projectRootPath = it.asString }
                json.get("structureRegionChunks")?.let { SharedSettings.structureRegionChunks = it.asInt }
                json.get("projectCellGap")?.let { SharedSettings.projectCellGap = it.asInt }
                json.get("projectRowMax")?.let { SharedSettings.projectRowMax = it.asInt }
                json.get("projectGridYBase")?.let { SharedSettings.projectGridYBase = it.asInt }
                json.get("autoSaveEnabled")?.let { SharedSettings.autoSaveEnabled = it.asBoolean }
                json.get("autoSaveDebounceTicks")?.let { SharedSettings.autoSaveDebounceTicks = it.asInt }
                json.get("autoSaveMaxDirtyTicks")?.let { SharedSettings.autoSaveMaxDirtyTicks = it.asInt }
                json.get("localHistoryEnabled")?.let { SharedSettings.localHistoryEnabled = it.asBoolean }
                json.get("localHistoryDays")?.let { SharedSettings.localHistoryDays = it.asInt }
                json.get("localHistoryMaxRevisions")?.let { SharedSettings.localHistoryMaxRevisions = it.asInt }
                json.get("localHistoryDir")?.let { SharedSettings.localHistoryDir = it.asString }
                json.getAsJsonObject("projectCellSize")?.let { size ->
                    SharedSettings.projectCellSize = net.minecraft.core.Vec3i(
                        size.get("x").asInt, size.get("y").asInt, size.get("z").asInt,
                    )
                }
            }
        }.onFailure { e ->
            LOGGER.warn("Failed to load ModConfig from {}", file.absolutePath, e)
        }
    }

    fun save() {
        val file = configFile
        file.parentFile?.mkdirs()
        val json = JsonObject()
        json.addProperty("projectRootPath", SharedSettings.projectRootPath)
        json.addProperty("structureRegionChunks", SharedSettings.structureRegionChunks)
        json.addProperty("projectCellGap", SharedSettings.projectCellGap)
        json.addProperty("projectRowMax", SharedSettings.projectRowMax)
        json.addProperty("projectGridYBase", SharedSettings.projectGridYBase)
        json.addProperty("autoSaveEnabled", SharedSettings.autoSaveEnabled)
        json.addProperty("autoSaveDebounceTicks", SharedSettings.autoSaveDebounceTicks)
        json.addProperty("autoSaveMaxDirtyTicks", SharedSettings.autoSaveMaxDirtyTicks)
        json.addProperty("localHistoryEnabled", SharedSettings.localHistoryEnabled)
        json.addProperty("localHistoryDays", SharedSettings.localHistoryDays)
        json.addProperty("localHistoryMaxRevisions", SharedSettings.localHistoryMaxRevisions)
        json.addProperty("localHistoryDir", SharedSettings.localHistoryDir)
        val size = JsonObject()
        size.addProperty("x", SharedSettings.projectCellSize.x)
        size.addProperty("y", SharedSettings.projectCellSize.y)
        size.addProperty("z", SharedSettings.projectCellSize.z)
        json.add("projectCellSize", size)
        runCatching {
            file.writeText(json.toString())
        }.onFailure { e ->
            LOGGER.error("Failed to save ModConfig to {}", file.absolutePath, e)
        }
    }
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `cmd.exe /c "gradlew.bat :26.2:runClientTest"` (foreground, `timeout: 600000`)
Expected: PASS. Read the launcher summary in the console — the clientTest XML report is always empty.

- [ ] **Step 6: Verify nothing else broke**

Run: `cmd.exe /c "gradlew.bat :26.2:classes :26.2:clientClasses :26.2:gametestClasses :26.2:clientTestClasses :26.2:testClasses"`
Expected: BUILD SUCCESSFUL. `RootPickerController` still compiles against the delegating `ModConfig.projectRootPath`.

- [ ] **Step 7: Commit**

```bash
git add src/main/kotlin/com/breadmoirai/garnet/config/SharedSettings.kt \
        src/client/kotlin/com/breadmoirai/garnet/config/ModConfig.kt \
        src/clientTest/kotlin/com/breadmoirai/garnet/test/ModConfigSpec.kt \
        src/clientTest/kotlin/com/breadmoirai/garnet/test/ClientTestSentinel.kt
git commit -m "feat(config): persist every setting through garnet.json"
```

---

### Task 2: The local-history store

**Files:**
- Create: `src/main/kotlin/com/breadmoirai/garnet/history/Revision.kt`
- Create: `src/main/kotlin/com/breadmoirai/garnet/history/LocalHistoryStore.kt`
- Test: `src/gametest/kotlin/com/breadmoirai/garnet/test/history/LocalHistoryStoreSpec.kt` (create)
- Modify: `src/gametest/kotlin/com/breadmoirai/garnet/test/GametestSentinel.kt`

**Interfaces:**
- Consumes: `SharedSettings.localHistoryDir`, `localHistoryDays`, `localHistoryMaxRevisions`, `localHistoryEnabled` (Task 1).
- Produces:
  - `data class Revision(val file: String, val timestampMillis: Long, val sizeX: Int, val sizeY: Int, val sizeZ: Int, val blockCount: Int, val reason: String)`
  - `data class HistoryIndex(val absolutePath: String, val revisions: List<Revision>)`
  - `LocalHistoryStore.historyRoot(): Path`
  - `LocalHistoryStore.keyOf(structureFile: Path): String`
  - `LocalHistoryStore.dirFor(structureFile: Path): Path`
  - `LocalHistoryStore.normalizePath(structureFile: Path, windows: Boolean): String`
  - `LocalHistoryStore.writeRevision(structureFile: Path, tag: CompoundTag, sizeX: Int, sizeY: Int, sizeZ: Int, blockCount: Int, reason: String, nowMillis: Long = System.currentTimeMillis()): Revision?`
  - `LocalHistoryStore.revisions(structureFile: Path): List<Revision>`
  - `LocalHistoryStore.readTag(structureFile: Path, revision: Revision): CompoundTag?`
  - `LocalHistoryStore.moveHistory(from: Path, to: Path)`
  - `LocalHistoryStore.REASON_PLACED`, `REASON_AUTOSAVE`, `REASON_MANUAL`

- [ ] **Step 1: Write the failing test**

Create `src/gametest/kotlin/com/breadmoirai/garnet/test/history/LocalHistoryStoreSpec.kt`:

```kotlin
package com.breadmoirai.garnet.test.history

import com.breadmoirai.garnet.config.SharedSettings
import com.breadmoirai.garnet.harness.GarnetTestSpec
import com.breadmoirai.garnet.history.LocalHistoryStore
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import net.minecraft.nbt.CompoundTag
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.writeText

/**
 * Filesystem-level coverage for the local-history store. Needs no world: every call is pure IO,
 * so these run without an `onServer` block.
 */
class LocalHistoryStoreSpec : GarnetTestSpec({

    /** Point the store at a scratch directory and restore the previous settings afterwards. */
    fun withStore(block: (java.nio.file.Path) -> Unit) {
        val dir = createTempDirectory("garnet-history")
        val prevDir = SharedSettings.localHistoryDir
        val prevDays = SharedSettings.localHistoryDays
        val prevMax = SharedSettings.localHistoryMaxRevisions
        val prevEnabled = SharedSettings.localHistoryEnabled
        SharedSettings.localHistoryDir = dir.toAbsolutePath().toString()
        SharedSettings.localHistoryEnabled = true
        try {
            block(dir)
        } finally {
            SharedSettings.localHistoryDir = prevDir
            SharedSettings.localHistoryDays = prevDays
            SharedSettings.localHistoryMaxRevisions = prevMax
            SharedSettings.localHistoryEnabled = prevEnabled
            dir.toFile().deleteRecursively()
        }
    }

    /** A minimal distinguishable tag — the store never interprets it, only stores it. */
    fun tagWith(marker: String): CompoundTag {
        val tag = CompoundTag()
        tag.putString("garnetTestMarker", marker)
        return tag
    }

    test("a revision is written, indexed, and reads back byte-identical") {
        withStore { _ ->
            val proj = createTempDirectory("proj")
            val file = proj.resolve("clock.nbt")
            file.writeText("placeholder")

            val rev = LocalHistoryStore.writeRevision(
                file, tagWith("first"), 3, 2, 1, 5, LocalHistoryStore.REASON_PLACED, nowMillis = 1_000L,
            ).shouldNotBeNull()

            rev.timestampMillis shouldBe 1_000L
            rev.sizeX shouldBe 3
            rev.sizeY shouldBe 2
            rev.sizeZ shouldBe 1
            rev.blockCount shouldBe 5
            rev.reason shouldBe LocalHistoryStore.REASON_PLACED

            LocalHistoryStore.revisions(file) shouldHaveSize 1
            LocalHistoryStore.readTag(file, rev)!!.getStringOr("garnetTestMarker", "") shouldBe "first"

            proj.toFile().deleteRecursively()
        }
    }

    test("two revisions in the same millisecond get distinct sequence numbers") {
        withStore { _ ->
            val proj = createTempDirectory("proj-seq")
            val file = proj.resolve("clock.nbt")

            val a = LocalHistoryStore.writeRevision(
                file, tagWith("a"), 1, 1, 1, 1, LocalHistoryStore.REASON_AUTOSAVE, nowMillis = 5_000L,
            ).shouldNotBeNull()
            val b = LocalHistoryStore.writeRevision(
                file, tagWith("b"), 1, 1, 1, 1, LocalHistoryStore.REASON_AUTOSAVE, nowMillis = 5_000L,
            ).shouldNotBeNull()

            a.file shouldNotBe b.file
            LocalHistoryStore.revisions(file) shouldHaveSize 2
            LocalHistoryStore.readTag(file, b)!!.getStringOr("garnetTestMarker", "") shouldBe "b"

            proj.toFile().deleteRecursively()
        }
    }

    test("revisions come back in chronological order regardless of write order") {
        withStore { _ ->
            val proj = createTempDirectory("proj-order")
            val file = proj.resolve("clock.nbt")
            LocalHistoryStore.writeRevision(file, tagWith("late"), 1, 1, 1, 1, LocalHistoryStore.REASON_AUTOSAVE, nowMillis = 9_000L)
            LocalHistoryStore.writeRevision(file, tagWith("early"), 1, 1, 1, 1, LocalHistoryStore.REASON_AUTOSAVE, nowMillis = 1_000L)
            LocalHistoryStore.revisions(file).map { it.timestampMillis } shouldBe listOf(1_000L, 9_000L)
            proj.toFile().deleteRecursively()
        }
    }

    test("revisions older than localHistoryDays are pruned on the next write") {
        withStore { _ ->
            SharedSettings.localHistoryDays = 5
            SharedSettings.localHistoryMaxRevisions = 100
            val proj = createTempDirectory("proj-age")
            val file = proj.resolve("clock.nbt")
            val now = 100L * 24 * 60 * 60 * 1000  // day 100
            val sixDaysAgo = now - 6L * 24 * 60 * 60 * 1000
            val oneDayAgo = now - 1L * 24 * 60 * 60 * 1000

            LocalHistoryStore.writeRevision(file, tagWith("old"), 1, 1, 1, 1, LocalHistoryStore.REASON_AUTOSAVE, nowMillis = sixDaysAgo)
            LocalHistoryStore.writeRevision(file, tagWith("recent"), 1, 1, 1, 1, LocalHistoryStore.REASON_AUTOSAVE, nowMillis = oneDayAgo)
            // The write below is what triggers pruning; the 6-day-old entry falls outside the window.
            LocalHistoryStore.writeRevision(file, tagWith("now"), 1, 1, 1, 1, LocalHistoryStore.REASON_AUTOSAVE, nowMillis = now)

            val kept = LocalHistoryStore.revisions(file)
            kept shouldHaveSize 2
            kept.map { it.timestampMillis } shouldBe listOf(oneDayAgo, now)
            // The pruned revision's blob is gone from disk too, not merely dropped from the index.
            LocalHistoryStore.dirFor(file).listDirectoryEntries("*.nbt") shouldHaveSize 2

            proj.toFile().deleteRecursively()
        }
    }

    test("revisions beyond localHistoryMaxRevisions are pruned oldest-first") {
        withStore { _ ->
            SharedSettings.localHistoryDays = 3650
            SharedSettings.localHistoryMaxRevisions = 3
            val proj = createTempDirectory("proj-count")
            val file = proj.resolve("clock.nbt")
            repeat(5) { i ->
                LocalHistoryStore.writeRevision(
                    file, tagWith("r$i"), 1, 1, 1, 1, LocalHistoryStore.REASON_AUTOSAVE,
                    nowMillis = 1_000L + i,
                )
            }
            val kept = LocalHistoryStore.revisions(file)
            kept shouldHaveSize 3
            kept.map { it.timestampMillis } shouldBe listOf(1_002L, 1_003L, 1_004L)
            proj.toFile().deleteRecursively()
        }
    }

    test("localHistoryEnabled=false writes nothing") {
        withStore { _ ->
            SharedSettings.localHistoryEnabled = false
            val proj = createTempDirectory("proj-off")
            val file = proj.resolve("clock.nbt")
            LocalHistoryStore.writeRevision(file, tagWith("x"), 1, 1, 1, 1, LocalHistoryStore.REASON_AUTOSAVE) shouldBe null
            LocalHistoryStore.revisions(file) shouldHaveSize 0
            proj.toFile().deleteRecursively()
        }
    }

    test("the same file reached through two project roots resolves to one history") {
        withStore { _ ->
            // The key is the .nbt's own absolute path, so opening the parent as root or the folder
            // itself as root must not fork the history.
            val proj = createTempDirectory("proj-roots")
            val nested = proj.resolve("redstone").createDirectories()
            val file = nested.resolve("clock.nbt")
            val sameFileOtherWay = proj.resolve("redstone").resolve(".").resolve("clock.nbt")

            LocalHistoryStore.keyOf(sameFileOtherWay) shouldBe LocalHistoryStore.keyOf(file)

            LocalHistoryStore.writeRevision(file, tagWith("a"), 1, 1, 1, 1, LocalHistoryStore.REASON_AUTOSAVE, nowMillis = 1L)
            LocalHistoryStore.writeRevision(sameFileOtherWay, tagWith("b"), 1, 1, 1, 1, LocalHistoryStore.REASON_AUTOSAVE, nowMillis = 2L)
            LocalHistoryStore.revisions(file) shouldHaveSize 2

            proj.toFile().deleteRecursively()
        }
    }

    test("path normalization lowercases on Windows only") {
        val upper = java.nio.file.Path.of("C:/Repo/Clock.nbt")
        LocalHistoryStore.normalizePath(upper, windows = true) shouldBe
            LocalHistoryStore.normalizePath(java.nio.file.Path.of("c:/repo/clock.nbt"), windows = true)
        LocalHistoryStore.normalizePath(upper, windows = false) shouldNotBe
            LocalHistoryStore.normalizePath(java.nio.file.Path.of("c:/repo/clock.nbt"), windows = false)
    }

    test("moveHistory relocates a structure's revisions onto its new path") {
        withStore { _ ->
            val proj = createTempDirectory("proj-move")
            val from = proj.resolve("clock.nbt")
            val to = proj.resolve("ring.nbt")
            LocalHistoryStore.writeRevision(from, tagWith("a"), 1, 1, 1, 1, LocalHistoryStore.REASON_AUTOSAVE, nowMillis = 1L)

            LocalHistoryStore.moveHistory(from, to)

            LocalHistoryStore.revisions(from) shouldHaveSize 0
            LocalHistoryStore.revisions(to) shouldHaveSize 1
            LocalHistoryStore.dirFor(from).exists() shouldBe false

            proj.toFile().deleteRecursively()
        }
    }

    test("moveHistory onto a path that already has history merges chronologically") {
        withStore { _ ->
            val proj = createTempDirectory("proj-move-merge")
            val from = proj.resolve("clock.nbt")
            val to = proj.resolve("ring.nbt")
            LocalHistoryStore.writeRevision(from, tagWith("from"), 1, 1, 1, 1, LocalHistoryStore.REASON_AUTOSAVE, nowMillis = 1L)
            LocalHistoryStore.writeRevision(to, tagWith("to"), 1, 1, 1, 1, LocalHistoryStore.REASON_AUTOSAVE, nowMillis = 2L)

            LocalHistoryStore.moveHistory(from, to)

            LocalHistoryStore.revisions(to).map { it.timestampMillis } shouldBe listOf(1L, 2L)
            proj.toFile().deleteRecursively()
        }
    }
})
```

Register the spec by adding `LocalHistoryStoreSpec::class` to the `specs = listOf(...)` in `GametestSentinel`, with the import `com.breadmoirai.garnet.test.history.LocalHistoryStoreSpec`.

- [ ] **Step 2: Run the test to verify it fails**

Run: `cmd.exe /c "gradlew.bat :26.2:runGameTest"` (foreground, `timeout: 600000`)
Expected: compile failure — `com.breadmoirai.garnet.history` does not exist.

- [ ] **Step 3: Write the data model**

Create `src/main/kotlin/com/breadmoirai/garnet/history/Revision.kt`:

```kotlin
package com.breadmoirai.garnet.history

/**
 * One recorded state of a structure. [file] is the blob's filename inside the structure's history
 * directory (`<epochMillis>-<seq>.nbt`); everything else is metadata a browser can show without
 * reading the blob.
 */
data class Revision(
    val file: String,
    val timestampMillis: Long,
    val sizeX: Int,
    val sizeY: Int,
    val sizeZ: Int,
    val blockCount: Int,
    val reason: String,
)

/**
 * A structure's `index.json`. [absolutePath] records the path the directory was keyed from — for
 * hand-debugging an opaque hash directory, and to notice a hash collision rather than silently
 * interleaving two structures' revisions.
 */
data class HistoryIndex(
    val absolutePath: String,
    val revisions: List<Revision>,
)
```

- [ ] **Step 4: Write the store**

Create `src/main/kotlin/com/breadmoirai/garnet/history/LocalHistoryStore.kt`:

```kotlin
package com.breadmoirai.garnet.history

import com.breadmoirai.garnet.config.SharedSettings
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.NbtAccounter
import net.minecraft.nbt.NbtIo
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.writeText

private val LOGGER = LoggerFactory.getLogger("Garnet")
private val GSON: Gson = GsonBuilder().setPrettyPrinting().create()

private const val INDEX_FILE = "index.json"
private const val MILLIS_PER_DAY = 24L * 60 * 60 * 1000

/**
 * JetBrains-style local history for standalone `.nbt` structures.
 *
 * ```
 * <instance>/.garnet/local-history/<stem>-<hash8>/<epochMillis>-<seq>.nbt
 * <instance>/.garnet/local-history/<stem>-<hash8>/index.json
 * ```
 *
 * **Keying is by the structure file's own absolute path, never by the project root.** The editor's
 * root is swappable ("Open Folder…"), so keying by root would fork one file's history the moment a
 * user opened its parent directory instead of the directory itself. The `<stem>` prefix exists only
 * so the directory is browsable by hand; the hash is what identifies it.
 *
 * History deliberately outlives the structure it describes — deleting a `.nbt` leaves its revisions
 * in place, since recovering a deleted structure is exactly what this store is for.
 */
object LocalHistoryStore {

    const val REASON_PLACED = "placed"
    const val REASON_AUTOSAVE = "autosave"
    const val REASON_MANUAL = "manual"

    /** `<instance>/.garnet/local-history`, or [SharedSettings.localHistoryDir] when set. */
    fun historyRoot(): Path {
        val configured = SharedSettings.localHistoryDir
        if (configured.isNotBlank()) return Path.of(configured)
        return FabricLoader.getInstance().gameDir.resolve(".garnet").resolve("local-history")
    }

    /**
     * The canonical string form of [structureFile] used as hash input. Windows paths are lowercased
     * because its filesystem is case-insensitive: the same file reached as `Clock.nbt` and
     * `clock.nbt` must land in one history, not two.
     */
    fun normalizePath(structureFile: Path, windows: Boolean): String {
        val absolute = structureFile.toAbsolutePath().normalize().toString()
        return if (windows) absolute.lowercase() else absolute
    }

    private fun onWindows(): Boolean =
        System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)

    /** `<stem>-<hash8>` — the history directory name for [structureFile]. */
    fun keyOf(structureFile: Path): String {
        val normalized = normalizePath(structureFile, onWindows())
        val digest = MessageDigest.getInstance("SHA-256").digest(normalized.toByteArray())
        val hash8 = digest.take(4).joinToString("") { "%02x".format(it) }
        val stem = structureFile.normalize().name.substringBeforeLast('.').ifEmpty { "structure" }
        return "${sanitize(stem)}-$hash8"
    }

    /** Keep the browsable prefix filesystem-safe; the hash carries the actual identity. */
    private fun sanitize(stem: String): String =
        stem.map { if (it.isLetterOrDigit() || it == '-' || it == '_') it else '_' }.joinToString("")

    fun dirFor(structureFile: Path): Path = historyRoot().resolve(keyOf(structureFile))

    /** Chronological (oldest first). Empty when the structure has no history. */
    fun revisions(structureFile: Path): List<Revision> = readIndex(structureFile).revisions

    /** The stored blob for [revision], or null if it is missing or unreadable. */
    fun readTag(structureFile: Path, revision: Revision): CompoundTag? {
        val blob = dirFor(structureFile).resolve(revision.file)
        if (!blob.exists()) return null
        return try {
            NbtIo.readCompressed(blob, NbtAccounter.unlimitedHeap())
        } catch (e: IOException) {
            LOGGER.error("[LocalHistoryStore] read revision '{}': {}", blob, e.message)
            null
        }
    }

    /**
     * Appends [tag] as a new revision and prunes. Returns the written [Revision], or null when
     * history is disabled or the write failed.
     */
    fun writeRevision(
        structureFile: Path,
        tag: CompoundTag,
        sizeX: Int,
        sizeY: Int,
        sizeZ: Int,
        blockCount: Int,
        reason: String,
        nowMillis: Long = System.currentTimeMillis(),
    ): Revision? {
        if (!SharedSettings.localHistoryEnabled) return null
        val dir = dirFor(structureFile)
        val index = readIndex(structureFile)

        // Same-millisecond writes would otherwise collide on filename; bump the sequence until free.
        var seq = 0
        var name = "$nowMillis-$seq.nbt"
        while (dir.resolve(name).exists() || index.revisions.any { it.file == name }) {
            seq++
            name = "$nowMillis-$seq.nbt"
        }

        val revision = Revision(name, nowMillis, sizeX, sizeY, sizeZ, blockCount, reason)
        return try {
            dir.createDirectories()
            NbtIo.writeCompressed(tag, dir.resolve(name))
            val merged = (index.revisions + revision).sortedBy { it.timestampMillis }
            writeIndex(structureFile, HistoryIndex(normalizePath(structureFile, onWindows()), prune(dir, merged, nowMillis)))
            revision
        } catch (e: IOException) {
            LOGGER.error("[LocalHistoryStore] write revision for '{}': {}", structureFile, e.message)
            null
        }
    }

    /**
     * Move a structure's history to the key for [to] — called after a rename, since the absolute
     * path (and therefore the hash) changes. Merging rather than replacing keeps any history the
     * destination path already accumulated under a previous structure of the same name.
     */
    fun moveHistory(from: Path, to: Path) {
        val fromDir = dirFor(from)
        if (!fromDir.exists()) return
        val fromIndex = readIndex(from)
        val toDir = dirFor(to)
        toDir.createDirectories()
        val toIndex = readIndex(to)

        val moved = ArrayList<Revision>()
        for (revision in fromIndex.revisions) {
            val source = fromDir.resolve(revision.file)
            if (!source.exists()) continue
            // A destination collision is only possible when both sides wrote in the same
            // millisecond; re-sequence rather than clobber.
            var seq = 0
            var name = "${revision.timestampMillis}-$seq.nbt"
            while (toDir.resolve(name).exists()) { seq++; name = "${revision.timestampMillis}-$seq.nbt" }
            try {
                kotlin.io.path.moveTo(source, toDir.resolve(name))
                moved += revision.copy(file = name)
            } catch (e: IOException) {
                LOGGER.error("[LocalHistoryStore] move revision '{}': {}", source, e.message)
            }
        }
        val merged = (toIndex.revisions + moved).sortedBy { it.timestampMillis }
        writeIndex(to, HistoryIndex(normalizePath(to, onWindows()), merged))
        fromDir.resolve(INDEX_FILE).deleteIfExists()
        runCatching { fromDir.toFile().deleteRecursively() }
    }

    /**
     * Applies the age cutoff then the count cap to [revisions], deleting the blobs it drops.
     * Returns what survives, chronological.
     */
    private fun prune(dir: Path, revisions: List<Revision>, nowMillis: Long): List<Revision> {
        val cutoff = nowMillis - SharedSettings.localHistoryDays.toLong() * MILLIS_PER_DAY
        val byAge = revisions.filter { it.timestampMillis >= cutoff }
        val capped = byAge.takeLast(SharedSettings.localHistoryMaxRevisions.coerceAtLeast(1))
        val keptFiles = capped.mapTo(HashSet()) { it.file }
        for (dropped in revisions) {
            if (dropped.file in keptFiles) continue
            runCatching { dir.resolve(dropped.file).deleteIfExists() }
        }
        return capped
    }

    private fun readIndex(structureFile: Path): HistoryIndex {
        val file = dirFor(structureFile).resolve(INDEX_FILE)
        val empty = HistoryIndex(normalizePath(structureFile, onWindows()), emptyList())
        if (!file.exists()) return empty
        return runCatching { GSON.fromJson(file.readText(), HistoryIndex::class.java) ?: empty }
            .getOrElse { e ->
                LOGGER.error("[LocalHistoryStore] read index '{}': {}", file, e.message)
                empty
            }
    }

    private fun writeIndex(structureFile: Path, index: HistoryIndex) {
        val dir = dirFor(structureFile)
        runCatching {
            dir.createDirectories()
            dir.resolve(INDEX_FILE).writeText(GSON.toJson(index))
        }.onFailure { e ->
            LOGGER.error("[LocalHistoryStore] write index for '{}': {}", structureFile, e.message)
        }
    }
}
```

If `kotlin.io.path.moveTo(source, target)` does not resolve as a top-level call, use the extension form `source.moveTo(target)` with the `kotlin.io.path.moveTo` import — match whichever form `EditorNetworking.handleRename` already uses.

- [ ] **Step 5: Run the test to verify it passes**

Run: `cmd.exe /c "gradlew.bat :26.2:runGameTest"` (foreground, `timeout: 600000`)
Expected: all ten `LocalHistoryStoreSpec` tests pass. Read `build/reports/garnet/gametest`.

If `getStringOr` is not the right accessor for this MC version, check how `StructureDiff.kt` reads tags (it uses `getIntOr`, `getCompoundOrEmpty`) and match that idiom.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/breadmoirai/garnet/history/ \
        src/gametest/kotlin/com/breadmoirai/garnet/test/history/ \
        src/gametest/kotlin/com/breadmoirai/garnet/test/GametestSentinel.kt
git commit -m "feat(history): add the local-history store"
```

---

### Task 3: Bounded structure capture

**Files:**
- Modify: `src/main/kotlin/com/breadmoirai/garnet/structure/StructurePersistence.kt`
- Test: `src/gametest/kotlin/com/breadmoirai/garnet/test/structure/StructureRegionPersistenceSpec.kt` (append)

**Interfaces:**
- Produces:
  - `data class CapturedStructure(val tag: CompoundTag, val box: PlacedBox?, val blockCount: Int)` in `com.breadmoirai.garnet.structure`
  - `StructurePersistence.captureAutoFitIn(level: ServerLevel, scan: PlacedBox): CapturedStructure`
- Note: the existing `captureAutoFit(level, regionOrigin, regionSizeXZ, regionMinY, regionMaxY): Pair<CompoundTag, PlacedBox?>` keeps its signature and delegates, so `flushUnsavedSidecar` and `saveAutoFitToFile` keep compiling until Task 7 removes the former.

- [ ] **Step 1: Write the failing test**

Append these two tests inside the existing `StructureRegionPersistenceSpec` body (open the file first to match its `withServer`/`onServer` idiom and its choice of far-out coordinates):

```kotlin
    test("captureAutoFitIn fits tightly inside the scanned box and counts non-air blocks") {
        onServer {
            val level = overworld()
            val origin = BlockPos(300_000, 64, EditorDimRegistry.STRUCTURE_LANE_Z)
            val scan = PlacedBox(origin, Vec3i(8, 4, 8))
            StructurePersistence.clearBounds(level, scan.origin, scan.size)

            level.setBlock(origin.offset(2, 0, 3), Blocks.GOLD_BLOCK.defaultBlockState(), 2)
            level.setBlock(origin.offset(5, 1, 3), Blocks.IRON_BLOCK.defaultBlockState(), 2)

            val captured = StructurePersistence.captureAutoFitIn(level, scan)

            captured.blockCount shouldBe 2
            val box = captured.box.shouldNotBeNull()
            box.origin shouldBe origin.offset(2, 0, 3)
            box.size shouldBe Vec3i(4, 2, 1)

            StructurePersistence.clearBounds(level, scan.origin, scan.size)
        }
    }

    test("captureAutoFitIn on an empty box returns a null box, zero blocks, and a valid tag") {
        onServer {
            val level = overworld()
            val origin = BlockPos(310_000, 64, EditorDimRegistry.STRUCTURE_LANE_Z)
            val scan = PlacedBox(origin, Vec3i(4, 4, 4))
            StructurePersistence.clearBounds(level, scan.origin, scan.size)

            val captured = StructurePersistence.captureAutoFitIn(level, scan)

            captured.box shouldBe null
            captured.blockCount shouldBe 0
            // Still a loadable empty structure, not a malformed tag.
            captured.tag.getListOrEmpty("blocks").size shouldBe 0
        }
    }
```

Add whatever imports the file lacks: `com.breadmoirai.garnet.structure.PlacedBox`, `com.breadmoirai.garnet.editor.world.EditorDimRegistry`, `io.kotest.matchers.nulls.shouldNotBeNull`, `net.minecraft.core.Vec3i`, `net.minecraft.world.level.block.Blocks`.

- [ ] **Step 2: Run the test to verify it fails**

Run: `cmd.exe /c "gradlew.bat :26.2:runGameTest"` (foreground, `timeout: 600000`)
Expected: compile failure — `captureAutoFitIn` unresolved.

- [ ] **Step 3: Implement the bounded capture**

In `src/main/kotlin/com/breadmoirai/garnet/structure/StructurePersistence.kt`, add above `object StructurePersistence`:

```kotlin
/**
 * The result of scanning a volume: the saved [StructureTemplate] tag, the tight [box] enclosing all
 * non-air (null when the volume was empty), and the non-air [blockCount].
 *
 * [blockCount] is counted during the scan rather than derived from the tag: `fillFromWorld` records
 * every cell in its bounds, air included, so `tag.blocks.size` is the box volume, not the build size.
 */
data class CapturedStructure(
    val tag: CompoundTag,
    val box: PlacedBox?,
    val blockCount: Int,
)
```

Then replace `captureAutoFit`'s body and add `captureAutoFitIn` inside the object:

```kotlin
    /**
     * Auto-fit within an arbitrary absolute [scan] volume, rather than a whole structure region.
     *
     * This is the auto-save path's capture: scanning `union(placedBox, dirtyBox)` reads a few
     * thousand positions where scanning the full region reads ~8M, which is the difference between
     * a viable per-edit debounce and an unusable one. A zero-size [scan] is empty by definition.
     */
    fun captureAutoFitIn(level: ServerLevel, scan: PlacedBox): CapturedStructure {
        val template = StructureTemplate()
        if (scan.size.x <= 0 || scan.size.y <= 0 || scan.size.z <= 0) {
            return CapturedStructure(template.save(CompoundTag()), null, 0)
        }
        var blockCount = 0
        val fit = autoFit(scan.size.x, scan.size.y, scan.size.z) { lx, ly, lz ->
            val nonAir = !level.getBlockState(
                BlockPos(scan.origin.x + lx, scan.origin.y + ly, scan.origin.z + lz),
            ).`is`(Blocks.AIR)
            if (nonAir) blockCount++
            nonAir
        }
        if (fit == null) return CapturedStructure(template.save(CompoundTag()), null, 0)
        val tightOrigin = BlockPos(
            scan.origin.x + fit.minX, scan.origin.y + fit.minY, scan.origin.z + fit.minZ,
        )
        val size = Vec3i(fit.sizeX, fit.sizeY, fit.sizeZ)
        template.fillFromWorld(level, tightOrigin, size, false, emptyList())
        return CapturedStructure(template.save(CompoundTag()), PlacedBox(tightOrigin, size), blockCount)
    }

    /**
     * Auto-fit across a whole structure region. Kept for the explicit-save path; the auto-save path
     * uses [captureAutoFitIn] with a far smaller volume.
     */
    fun captureAutoFit(
        level: ServerLevel, regionOrigin: BlockPos,
        regionSizeXZ: Int, regionMinY: Int, regionMaxY: Int,
    ): Pair<CompoundTag, PlacedBox?> {
        val scan = PlacedBox(
            BlockPos(regionOrigin.x, regionMinY, regionOrigin.z),
            Vec3i(regionSizeXZ, regionMaxY - regionMinY + 1, regionSizeXZ),
        )
        val captured = captureAutoFitIn(level, scan)
        return captured.tag to captured.box
    }
```

`autoFit` is called once per position, so the `blockCount++` side effect inside the predicate counts each cell exactly once — do not also count inside `fillFromWorld`.

- [ ] **Step 4: Run the test to verify it passes**

Run: `cmd.exe /c "gradlew.bat :26.2:runGameTest"` (foreground, `timeout: 600000`)
Expected: the two new tests pass, and every pre-existing structure/editor test still passes — `captureAutoFit`'s behavior is unchanged.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/breadmoirai/garnet/structure/StructurePersistence.kt \
        src/gametest/kotlin/com/breadmoirai/garnet/test/structure/StructureRegionPersistenceSpec.kt
git commit -m "feat(structure): add bounded captureAutoFitIn"
```

---

### Task 4: Dirty tracking

**Files:**
- Create: `src/main/kotlin/com/breadmoirai/garnet/editor/world/StructureAutoSave.kt`
- Create: `src/main/kotlin/com/breadmoirai/garnet/editor/world/StructureEditWatcher.kt`
- Modify: `src/main/kotlin/com/breadmoirai/garnet/editor/world/EditorDimRegistry.kt`
- Modify: `src/main/java/com/breadmoirai/garnet/mixin/ServerLevelSetBlockMixin.java`
- Test: `src/gametest/kotlin/com/breadmoirai/garnet/test/editor/StructureAutoSaveSpec.kt` (create)
- Modify: `src/gametest/kotlin/com/breadmoirai/garnet/test/GametestSentinel.kt`

**Interfaces:**
- Consumes: `SharedSettings.autoSaveDebounceTicks`, `autoSaveMaxDirtyTicks` (Task 1); `PlacedBox` (existing).
- Produces:
  - `EditorDimRegistry.structureSubpathAt(pos: BlockPos): String?`
  - `StructureAutoSave.of(server: MinecraftServer): StructureAutoSave`, `StructureAutoSave.dispose(server)`
  - `StructureAutoSave.onEdit(subpath: String, pos: BlockPos, tick: Long)`
  - `StructureAutoSave.dirtyBox(subpath: String): PlacedBox?`
  - `StructureAutoSave.isDirty(subpath: String): Boolean`
  - `StructureAutoSave.dueForCommit(subpath: String, tick: Long): Boolean`
  - `StructureAutoSave.dirtySubpaths(): Set<String>`
  - `StructureAutoSave.clear(subpath: String)`
  - `StructureEditWatcher.onBlockChanged(level: ServerLevel, pos: BlockPos)`

- [ ] **Step 1: Write the failing test**

Create `src/gametest/kotlin/com/breadmoirai/garnet/test/editor/StructureAutoSaveSpec.kt`:

```kotlin
package com.breadmoirai.garnet.test.editor

import com.breadmoirai.garnet.config.SharedSettings
import com.breadmoirai.garnet.editor.world.EditorDimRegistry
import com.breadmoirai.garnet.editor.world.StructureAutoSave
import com.breadmoirai.garnet.editor.world.StructureEditWatcher
import com.breadmoirai.garnet.harness.GarnetTestSpec
import com.breadmoirai.garnet.mc.onServer
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import net.minecraft.core.BlockPos
import net.minecraft.core.Vec3i

/**
 * Dirty-state bookkeeping only — the commit itself is covered by the network-level tests once
 * [com.breadmoirai.garnet.editor.world.StructureCommit] exists.
 *
 * These drive [StructureEditWatcher.onBlockChanged] directly rather than calling `level.setBlock`:
 * the setBlock mixin is unreliable under the gametest harness, and what needs testing here is the
 * bookkeeping, not the mixin plumbing.
 */
class StructureAutoSaveSpec : GarnetTestSpec({

    test("an edit marks the structure dirty and the dirty box grows to enclose every edit") {
        onServer {
            val autoSave = StructureAutoSave.of(this)
            autoSave.clear("dirtybox.nbt")

            autoSave.isDirty("dirtybox.nbt") shouldBe false

            autoSave.onEdit("dirtybox.nbt", BlockPos(10, 64, 10), tick = 100L)
            autoSave.isDirty("dirtybox.nbt") shouldBe true
            autoSave.dirtyBox("dirtybox.nbt").shouldNotBeNull().size shouldBe Vec3i(1, 1, 1)

            autoSave.onEdit("dirtybox.nbt", BlockPos(12, 66, 10), tick = 101L)
            val box = autoSave.dirtyBox("dirtybox.nbt").shouldNotBeNull()
            box.origin shouldBe BlockPos(10, 64, 10)
            box.size shouldBe Vec3i(3, 3, 1)

            // An edit at lower coordinates must move the origin, not just grow the size.
            autoSave.onEdit("dirtybox.nbt", BlockPos(9, 63, 8), tick = 102L)
            val grown = autoSave.dirtyBox("dirtybox.nbt").shouldNotBeNull()
            grown.origin shouldBe BlockPos(9, 63, 8)
            grown.size shouldBe Vec3i(4, 4, 3)

            autoSave.clear("dirtybox.nbt")
        }
    }

    test("dueForCommit fires only after the debounce has elapsed") {
        onServer {
            val prev = SharedSettings.autoSaveDebounceTicks
            val prevCap = SharedSettings.autoSaveMaxDirtyTicks
            SharedSettings.autoSaveDebounceTicks = 20
            SharedSettings.autoSaveMaxDirtyTicks = 100_000
            try {
                val autoSave = StructureAutoSave.of(this)
                autoSave.clear("debounce.nbt")
                autoSave.onEdit("debounce.nbt", BlockPos(0, 64, 0), tick = 1_000L)

                autoSave.dueForCommit("debounce.nbt", tick = 1_010L) shouldBe false
                autoSave.dueForCommit("debounce.nbt", tick = 1_020L) shouldBe true

                // A fresh edit restarts the quiet period.
                autoSave.onEdit("debounce.nbt", BlockPos(0, 64, 1), tick = 1_015L)
                autoSave.dueForCommit("debounce.nbt", tick = 1_020L) shouldBe false
                autoSave.dueForCommit("debounce.nbt", tick = 1_035L) shouldBe true

                autoSave.clear("debounce.nbt")
            } finally {
                SharedSettings.autoSaveDebounceTicks = prev
                SharedSettings.autoSaveMaxDirtyTicks = prevCap
            }
        }
    }

    test("the max-dirty cap fires during continuous editing even though the debounce never elapses") {
        onServer {
            val prev = SharedSettings.autoSaveDebounceTicks
            val prevCap = SharedSettings.autoSaveMaxDirtyTicks
            SharedSettings.autoSaveDebounceTicks = 20
            SharedSettings.autoSaveMaxDirtyTicks = 50
            try {
                val autoSave = StructureAutoSave.of(this)
                autoSave.clear("cap.nbt")
                // An edit every 5 ticks: the debounce alone would never elapse.
                var tick = 2_000L
                repeat(11) {
                    autoSave.onEdit("cap.nbt", BlockPos(0, 64, 0), tick = tick)
                    tick += 5
                }
                // 50 ticks after the FIRST edit, the cap is due despite the last edit being recent.
                autoSave.dueForCommit("cap.nbt", tick = 2_050L) shouldBe true
                autoSave.clear("cap.nbt")
            } finally {
                SharedSettings.autoSaveDebounceTicks = prev
                SharedSettings.autoSaveMaxDirtyTicks = prevCap
            }
        }
    }

    test("clear forgets the structure entirely") {
        onServer {
            val autoSave = StructureAutoSave.of(this)
            autoSave.onEdit("clear.nbt", BlockPos(0, 64, 0), tick = 1L)
            autoSave.dirtySubpaths().contains("clear.nbt") shouldBe true
            autoSave.clear("clear.nbt")
            autoSave.isDirty("clear.nbt") shouldBe false
            autoSave.dirtyBox("clear.nbt") shouldBe null
            autoSave.dueForCommit("clear.nbt", tick = 100_000L) shouldBe false
            autoSave.dirtySubpaths().contains("clear.nbt") shouldBe false
        }
    }

    test("structureSubpathAt attributes a position to the placed structure whose region holds it") {
        onServer {
            val registry = EditorDimRegistry.of(this)
            val origin = registry.getOrAssignStructureRegion("attributed.nbt")
            val width = SharedSettings.structureRegionChunks * 16

            registry.structureSubpathAt(origin) shouldBe "attributed.nbt"
            registry.structureSubpathAt(origin.offset(width - 1, 100, width - 1)) shouldBe "attributed.nbt"
            // Just outside the region's X extent — no structure owns it.
            registry.structureSubpathAt(origin.offset(width, 0, 0)) shouldBe null
            // Far away in Z, outside the structure lane entirely.
            registry.structureSubpathAt(BlockPos(origin.x, 64, 0)) shouldBe null
        }
    }

    test("the edit watcher records an edit inside a placed region and ignores one outside") {
        onServer {
            val registry = EditorDimRegistry.of(this)
            val origin = registry.getOrAssignStructureRegion("watched.nbt")
            val autoSave = StructureAutoSave.of(this)
            autoSave.clear("watched.nbt")

            StructureEditWatcher.onBlockChanged(overworld(), origin.offset(3, 5, 3))
            autoSave.isDirty("watched.nbt") shouldBe true

            autoSave.clear("watched.nbt")
            StructureEditWatcher.onBlockChanged(overworld(), BlockPos(origin.x, 64, 0))
            autoSave.isDirty("watched.nbt") shouldBe false
        }
    }
})
```

Register `StructureAutoSaveSpec::class` in `GametestSentinel`'s `specs = listOf(...)`, import `com.breadmoirai.garnet.test.editor.StructureAutoSaveSpec`.

- [ ] **Step 2: Run the test to verify it fails**

Run: `cmd.exe /c "gradlew.bat :26.2:runGameTest"` (foreground, `timeout: 600000`)
Expected: compile failure — `StructureAutoSave`, `StructureEditWatcher`, `structureSubpathAt` unresolved.

- [ ] **Step 3: Add region attribution to the registry**

In `EditorDimRegistry`, add after `structureRegionOriginOf`:

```kotlin
    /**
     * The structure whose assigned region contains [pos], or null. Regions span the full world
     * height, so only X/Z are tested. Linear in the number of placed structures — a handful in
     * practice, and the overwhelmingly common answer is "none", reached in a couple of comparisons.
     */
    fun structureSubpathAt(pos: BlockPos): String? {
        val width = SharedSettings.structureRegionChunks * 16
        for ((subpath, origin) in structureBySubpath) {
            if (pos.x < origin.x || pos.x >= origin.x + width) continue
            if (pos.z < origin.z || pos.z >= origin.z + width) continue
            return subpath
        }
        return null
    }
```

- [ ] **Step 4: Write the dirty-state holder**

Create `src/main/kotlin/com/breadmoirai/garnet/editor/world/StructureAutoSave.kt`:

```kotlin
package com.breadmoirai.garnet.editor.world

import com.breadmoirai.garnet.config.SharedSettings
import com.breadmoirai.garnet.structure.PlacedBox
import net.minecraft.core.BlockPos
import net.minecraft.core.Vec3i
import net.minecraft.server.MinecraftServer
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-server dirty bookkeeping for placed standalone structures: which ones have unsaved edits,
 * where those edits landed, and when they are due to be committed.
 *
 * Holds no world state and performs no IO — [StructureCommit] does the capturing and writing. The
 * split keeps this side cheap enough to touch from every successful `setBlock`.
 */
class StructureAutoSave {

    private data class Dirty(
        val min: BlockPos,
        val max: BlockPos,
        val firstEditTick: Long,
        val lastEditTick: Long,
    )

    private val dirty = ConcurrentHashMap<String, Dirty>()

    /** Record an edit at [pos] in [subpath]'s region, growing its dirty box. */
    fun onEdit(subpath: String, pos: BlockPos, tick: Long) {
        dirty.compute(subpath) { _, existing ->
            if (existing == null) {
                Dirty(pos, pos, firstEditTick = tick, lastEditTick = tick)
            } else {
                Dirty(
                    min = BlockPos(
                        minOf(existing.min.x, pos.x),
                        minOf(existing.min.y, pos.y),
                        minOf(existing.min.z, pos.z),
                    ),
                    max = BlockPos(
                        maxOf(existing.max.x, pos.x),
                        maxOf(existing.max.y, pos.y),
                        maxOf(existing.max.z, pos.z),
                    ),
                    firstEditTick = existing.firstEditTick,
                    lastEditTick = tick,
                )
            }
        }
    }

    fun isDirty(subpath: String): Boolean = dirty.containsKey(subpath)

    /** The inclusive box enclosing every edit since the last commit, as origin + size. */
    fun dirtyBox(subpath: String): PlacedBox? {
        val d = dirty[subpath] ?: return null
        return PlacedBox(
            d.min,
            Vec3i(d.max.x - d.min.x + 1, d.max.y - d.min.y + 1, d.max.z - d.min.z + 1),
        )
    }

    /**
     * True once the edits have gone quiet for [SharedSettings.autoSaveDebounceTicks], or the
     * structure has been continuously dirty for [SharedSettings.autoSaveMaxDirtyTicks] — the cap
     * that makes a long uninterrupted build session still checkpoint.
     */
    fun dueForCommit(subpath: String, tick: Long): Boolean {
        val d = dirty[subpath] ?: return false
        if (tick - d.lastEditTick >= SharedSettings.autoSaveDebounceTicks) return true
        return tick - d.firstEditTick >= SharedSettings.autoSaveMaxDirtyTicks
    }

    /** Snapshot of the currently dirty subpaths, safe to iterate while committing clears entries. */
    fun dirtySubpaths(): Set<String> = dirty.keys.toSet()

    fun clear(subpath: String) { dirty.remove(subpath) }

    companion object {
        private val perServer = java.util.WeakHashMap<MinecraftServer, StructureAutoSave>()

        @Synchronized fun of(server: MinecraftServer): StructureAutoSave =
            perServer.getOrPut(server) { StructureAutoSave() }

        @Synchronized fun dispose(server: MinecraftServer) { perServer.remove(server) }
    }
}
```

- [ ] **Step 5: Write the edit watcher**

Create `src/main/kotlin/com/breadmoirai/garnet/editor/world/StructureEditWatcher.kt`:

```kotlin
package com.breadmoirai.garnet.editor.world

import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel

/**
 * The bridge from a successful world block change to auto-save bookkeeping.
 *
 * Called from `ServerLevelSetBlockMixin` on every successful server-side `setBlock`, so it must stay
 * cheap: two map lookups and a handful of comparisons on the common "not in any structure region"
 * path. Deliberately `@JvmStatic`-friendly (an `object` with a plain function) so the Java mixin can
 * call it without Kotlin-specific plumbing.
 */
object StructureEditWatcher {

    @JvmStatic
    fun onBlockChanged(level: ServerLevel, pos: BlockPos) {
        val server = level.server
        val registry = EditorDimRegistry.of(server)
        // Structure regions live in the project level only; edits anywhere else are irrelevant.
        if (level !== registry.projectLevel()) return
        val subpath = registry.structureSubpathAt(pos) ?: return
        StructureAutoSave.of(server).onEdit(subpath, pos, level.gameTime)
    }
}
```

- [ ] **Step 6: Hook the mixin**

In `src/main/java/com/breadmoirai/garnet/mixin/ServerLevelSetBlockMixin.java`, inside `garnet$recordChange`, insert the watcher notification **before** the sentinel early-return, and add the import:

```java
import com.breadmoirai.garnet.editor.world.StructureEditWatcher;
```

```java
    private void garnet$recordChange(
        BlockPos pos, BlockState newState, int flags,
        CallbackInfoReturnable<Boolean> cir
    ) {
        Deque<Object> stack = BEFORE_STATE_STACK.get();
        Object fromObj = stack.isEmpty() ? SKIP_SENTINEL : stack.pop();

        // Auto-save cares about every successful server-side change, not only positions some
        // StateRecorder is watching -- so this must run BEFORE the sentinel return below, which
        // fires for the common "no recorder interested" case. The watcher needs no before-state,
        // only the position and the fact that the write actually landed.
        if (cir.getReturnValue() && ((Object) this) instanceof ServerLevel) {
            StructureEditWatcher.onBlockChanged((ServerLevel) (Object) this, pos);
        }

        if (fromObj == SKIP_SENTINEL) return; // client level, out of bounds, or no recorder
        if (!cir.getReturnValue()) return; // block did not actually change
        BlockState before = (BlockState) fromObj;
        for (StateRecorder recorder : StateRecorder.activeRecorders()) {
            if (recorder.isInBounds(pos)) {
                recorder.record(pos, before, newState);
            }
        }
    }
```

The `stack.pop()` must stay first: it has to happen on every path or the HEAD/RETURN pairing drifts on the next recursive call.

- [ ] **Step 7: Run the test to verify it passes**

Run: `cmd.exe /c "gradlew.bat :26.2:runGameTest"` (foreground, `timeout: 600000`)
Expected: all six `StructureAutoSaveSpec` tests pass, and no pre-existing test regresses (the mixin change must not disturb `StateRecorder` behavior — watch for recorder/playback specs in the report).

- [ ] **Step 8: Commit**

```bash
git add src/main/kotlin/com/breadmoirai/garnet/editor/world/StructureAutoSave.kt \
        src/main/kotlin/com/breadmoirai/garnet/editor/world/StructureEditWatcher.kt \
        src/main/kotlin/com/breadmoirai/garnet/editor/world/EditorDimRegistry.kt \
        src/main/java/com/breadmoirai/garnet/mixin/ServerLevelSetBlockMixin.java \
        src/gametest/kotlin/com/breadmoirai/garnet/test/editor/StructureAutoSaveSpec.kt \
        src/gametest/kotlin/com/breadmoirai/garnet/test/GametestSentinel.kt
git commit -m "feat(editor): track structure edits into a dirty box"
```

---

### Task 5: The auto-saved packet

**Files:**
- Modify: `src/main/kotlin/com/breadmoirai/garnet/editor/network/EditorPackets.kt`
- Modify: `src/main/kotlin/com/breadmoirai/garnet/editor/network/EditorNetworking.kt`
- Modify: `src/client/kotlin/com/breadmoirai/garnet/editor/network/EditorClientNetworking.kt`
- Modify: `src/client/kotlin/com/breadmoirai/garnet/editor/ui/ProjectTreeState.kt`
- Test: `src/clientTest/kotlin/com/breadmoirai/garnet/test/StructureExplorerSpec.kt` (append)

**Interfaces:**
- Produces:
  - `StructureAutoSavedS2C(subpath: String, sizeX: Int, sizeY: Int, sizeZ: Int, blockCount: Int, savedAtMillis: Long)` with `TYPE` and `STREAM_CODEC`
  - `ProjectTreeState.onAutoSaved(p: StructureAutoSavedS2C)`
- Note: this is the packet **spec 3's info panel consumes directly**. Do not narrow its fields to what the status line needs.

- [ ] **Step 1: Write the failing test**

Open `src/clientTest/kotlin/com/breadmoirai/garnet/test/StructureExplorerSpec.kt` and match its existing style, then append:

```kotlin
    test("an auto-save result lands in the Explorer status line") {
        ProjectTreeState.reset()
        ProjectTreeState.onAutoSaved(
            StructureAutoSavedS2C("redstone/clock.nbt", 5, 3, 7, 42, savedAtMillis = 1_700_000_000_000L),
        )
        ProjectTreeState.status shouldBe "auto-saved redstone/clock.nbt (5×3×7, 42 blocks)"
    }
```

Add the import `com.breadmoirai.garnet.editor.network.StructureAutoSavedS2C`.

- [ ] **Step 2: Run the test to verify it fails**

Run: `cmd.exe /c "gradlew.bat :26.2:runClientTest"` (foreground, `timeout: 600000`)
Expected: compile failure — `StructureAutoSavedS2C` and `onAutoSaved` unresolved.

- [ ] **Step 3: Add the packet**

In `EditorPackets.kt`, under the `// === Structure S2C ===` heading:

```kotlin
/**
 * Sent on every committed auto-save. Broadcast rather than addressed: structure regions are
 * server-global, so any player looking at one wants the update.
 *
 * Carries more than the status line needs — [blockCount] and [savedAtMillis] exist for the
 * structure info panel, which consumes this same packet.
 */
data class StructureAutoSavedS2C(
    val subpath: String,
    val sizeX: Int, val sizeY: Int, val sizeZ: Int,
    val blockCount: Int,
    val savedAtMillis: Long,
) : CustomPacketPayload {
    companion object {
        val TYPE = CustomPacketPayload.Type<StructureAutoSavedS2C>(id("structure_autosaved"))
        val STREAM_CODEC: StreamCodec<ByteBuf, StructureAutoSavedS2C> = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, StructureAutoSavedS2C::subpath,
            ByteBufCodecs.VAR_INT, StructureAutoSavedS2C::sizeX,
            ByteBufCodecs.VAR_INT, StructureAutoSavedS2C::sizeY,
            ByteBufCodecs.VAR_INT, StructureAutoSavedS2C::sizeZ,
            ByteBufCodecs.VAR_INT, StructureAutoSavedS2C::blockCount,
            ByteBufCodecs.VAR_LONG, StructureAutoSavedS2C::savedAtMillis,
            ::StructureAutoSavedS2C,
        )
    }
    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE
}
```

The 6-pair `StreamCodec.composite` overload is known to exist here — `StructureResultS2C` in this same file already uses one. `ByteBufCodecs.VAR_LONG` is not yet used anywhere in this codebase; if it does not resolve, fall back to `ByteBufCodecs.LONG`.

In `EditorNetworking.register()`, alongside the other clientbound registrations:

```kotlin
        PayloadTypeRegistry.clientboundPlay().register(StructureAutoSavedS2C.TYPE, StructureAutoSavedS2C.STREAM_CODEC)
```

- [ ] **Step 4: Wire the client side**

In `ProjectTreeState.kt`, add the import and the handler:

```kotlin
    fun onAutoSaved(p: StructureAutoSavedS2C) {
        status = "auto-saved ${p.subpath} (${p.sizeX}×${p.sizeY}×${p.sizeZ}, ${p.blockCount} blocks)"
    }
```

In `EditorClientNetworking.register()`:

```kotlin
        ClientPlayNetworking.registerGlobalReceiver(StructureAutoSavedS2C.TYPE) { payload, ctx ->
            ctx.client().execute { ProjectTreeState.onAutoSaved(payload) }
        }
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `cmd.exe /c "gradlew.bat :26.2:runClientTest"` (foreground, `timeout: 600000`)
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/breadmoirai/garnet/editor/network/EditorPackets.kt \
        src/main/kotlin/com/breadmoirai/garnet/editor/network/EditorNetworking.kt \
        src/client/kotlin/com/breadmoirai/garnet/editor/network/EditorClientNetworking.kt \
        src/client/kotlin/com/breadmoirai/garnet/editor/ui/ProjectTreeState.kt \
        src/clientTest/kotlin/com/breadmoirai/garnet/test/StructureExplorerSpec.kt
git commit -m "feat(editor): add StructureAutoSavedS2C"
```

---

### Task 6: The commit path

**Files:**
- Create: `src/main/kotlin/com/breadmoirai/garnet/editor/world/StructureCommit.kt`
- Modify: `src/main/kotlin/com/breadmoirai/garnet/editor/network/EditorNetworking.kt` (make `rootFor` public)
- Modify: `src/main/kotlin/com/breadmoirai/garnet/Garnet.kt`
- Test: `src/gametest/kotlin/com/breadmoirai/garnet/test/editor/StructureAutoSaveSpec.kt` (append)

**Interfaces:**
- Consumes: `LocalHistoryStore.writeRevision` (Task 2); `StructurePersistence.captureAutoFitIn` / `CapturedStructure` (Task 3); `StructureAutoSave` (Task 4); `StructureAutoSavedS2C` (Task 5).
- Produces:
  - `StructureCommit.commit(server: MinecraftServer, subpath: String, reason: String): StructureAutoSavedS2C?`
  - `StructureCommit.tick(server: MinecraftServer)`
  - `StructureCommit.commitAll(server: MinecraftServer, reason: String)`
  - `EditorNetworking.rootFor(server: MinecraftServer): EditorRoot?` becomes public

- [ ] **Step 1: Write the failing test**

Append to `StructureAutoSaveSpec`:

```kotlin
    test("a commit writes the .nbt, records a revision, and clears the dirty state") {
        withTempRoot("autosave-commit") { tmp ->
            val prevChunks = SharedSettings.structureRegionChunks
            val prevHistDir = SharedSettings.localHistoryDir
            SharedSettings.structureRegionChunks = 1
            val histDir = kotlin.io.path.createTempDirectory("autosave-hist")
            SharedSettings.localHistoryDir = histDir.toAbsolutePath().toString()
            EditorNewStructure.create(tmp, "widget")
            try {
                onServer {
                    EditorServerContext.set(this, EditorServerContext(EditorRoot(tmp)))
                    val player = makeMockServerPlayer(this)
                    drainPayloads(player)

                    EditorNetworking.handlePlaceStructure(this, player, PlaceStructureC2S("widget.nbt"))
                    drainPayloads(player)

                    // Placing seeds the pre-edit baseline, so rollback is possible from the start.
                    val file = tmp.resolve("widget.nbt")
                    LocalHistoryStore.revisions(file) shouldHaveSize 1
                    LocalHistoryStore.revisions(file).single().reason shouldBe LocalHistoryStore.REASON_PLACED

                    val registry = EditorDimRegistry.of(this)
                    val region = registry.structureRegionOriginOf("widget.nbt")!!
                    val lvl = overworld()
                    val width = SharedSettings.structureRegionChunks * 16
                    // The gametest world's terrain isn't part of the structure until cleared.
                    StructurePersistence.clearBounds(
                        lvl, BlockPos(region.x, lvl.minY, region.z),
                        Vec3i(width, lvl.maxY - lvl.minY + 1, width),
                    )

                    val edited = region.offset(5, 0, 5)
                    lvl.setBlock(edited, Blocks.GOLD_BLOCK.defaultBlockState(), 2)
                    // Drive the watcher directly: the setBlock mixin is flaky under this harness.
                    StructureEditWatcher.onBlockChanged(lvl, edited)
                    StructureAutoSave.of(this).isDirty("widget.nbt") shouldBe true

                    val result = StructureCommit.commit(this, "widget.nbt", LocalHistoryStore.REASON_AUTOSAVE)
                        .shouldNotBeNull()
                    result.subpath shouldBe "widget.nbt"
                    result.sizeX shouldBe 1
                    result.blockCount shouldBe 1

                    StructureAutoSave.of(this).isDirty("widget.nbt") shouldBe false
                    LocalHistoryStore.revisions(file) shouldHaveSize 2
                    LocalHistoryStore.revisions(file).last().reason shouldBe LocalHistoryStore.REASON_AUTOSAVE
                }
            } finally {
                SharedSettings.structureRegionChunks = prevChunks
                SharedSettings.localHistoryDir = prevHistDir
                histDir.toFile().deleteRecursively()
            }
        }
    }

    test("committing an unchanged structure writes nothing and records no revision") {
        withTempRoot("autosave-noop") { tmp ->
            val prevChunks = SharedSettings.structureRegionChunks
            val prevHistDir = SharedSettings.localHistoryDir
            SharedSettings.structureRegionChunks = 1
            val histDir = kotlin.io.path.createTempDirectory("autosave-noop-hist")
            SharedSettings.localHistoryDir = histDir.toAbsolutePath().toString()
            EditorNewStructure.create(tmp, "still")
            try {
                onServer {
                    EditorServerContext.set(this, EditorServerContext(EditorRoot(tmp)))
                    val player = makeMockServerPlayer(this)
                    EditorNetworking.handlePlaceStructure(this, player, PlaceStructureC2S("still.nbt"))
                    drainPayloads(player)

                    val file = tmp.resolve("still.nbt")
                    val before = file.readBytes().toList()
                    val revisionsBefore = LocalHistoryStore.revisions(file).size

                    // No edits at all -> the capture matches the committed file exactly.
                    StructureCommit.commit(this, "still.nbt", LocalHistoryStore.REASON_AUTOSAVE) shouldBe null

                    file.readBytes().toList() shouldBe before
                    LocalHistoryStore.revisions(file) shouldHaveSize revisionsBefore
                }
            } finally {
                SharedSettings.structureRegionChunks = prevChunks
                SharedSettings.localHistoryDir = prevHistDir
                histDir.toFile().deleteRecursively()
            }
        }
    }

    test("tick commits a due structure and skips one that is not due") {
        withTempRoot("autosave-tick") { tmp ->
            val prevChunks = SharedSettings.structureRegionChunks
            val prevHistDir = SharedSettings.localHistoryDir
            val prevDebounce = SharedSettings.autoSaveDebounceTicks
            val prevCap = SharedSettings.autoSaveMaxDirtyTicks
            SharedSettings.structureRegionChunks = 1
            SharedSettings.autoSaveDebounceTicks = 1_000_000  // never elapses during this test
            SharedSettings.autoSaveMaxDirtyTicks = 1_000_000
            val histDir = kotlin.io.path.createTempDirectory("autosave-tick-hist")
            SharedSettings.localHistoryDir = histDir.toAbsolutePath().toString()
            EditorNewStructure.create(tmp, "ticker")
            try {
                onServer {
                    EditorServerContext.set(this, EditorServerContext(EditorRoot(tmp)))
                    val player = makeMockServerPlayer(this)
                    EditorNetworking.handlePlaceStructure(this, player, PlaceStructureC2S("ticker.nbt"))
                    drainPayloads(player)

                    val registry = EditorDimRegistry.of(this)
                    val region = registry.structureRegionOriginOf("ticker.nbt")!!
                    val lvl = overworld()
                    val width = SharedSettings.structureRegionChunks * 16
                    StructurePersistence.clearBounds(
                        lvl, BlockPos(region.x, lvl.minY, region.z),
                        Vec3i(width, lvl.maxY - lvl.minY + 1, width),
                    )
                    val edited = region.offset(4, 0, 4)
                    lvl.setBlock(edited, Blocks.IRON_BLOCK.defaultBlockState(), 2)
                    StructureEditWatcher.onBlockChanged(lvl, edited)

                    // Debounce is effectively infinite -> tick must NOT commit.
                    StructureCommit.tick(this)
                    StructureAutoSave.of(this).isDirty("ticker.nbt") shouldBe true

                    // Make it due, then tick again.
                    SharedSettings.autoSaveDebounceTicks = 0
                    StructureCommit.tick(this)
                    StructureAutoSave.of(this).isDirty("ticker.nbt") shouldBe false
                }
            } finally {
                SharedSettings.structureRegionChunks = prevChunks
                SharedSettings.localHistoryDir = prevHistDir
                SharedSettings.autoSaveDebounceTicks = prevDebounce
                SharedSettings.autoSaveMaxDirtyTicks = prevCap
                histDir.toFile().deleteRecursively()
            }
        }
    }

    test("autoSaveEnabled=false stops the tick pass but not an explicit commit") {
        withTempRoot("autosave-off") { tmp ->
            val prevChunks = SharedSettings.structureRegionChunks
            val prevHistDir = SharedSettings.localHistoryDir
            val prevEnabled = SharedSettings.autoSaveEnabled
            val prevDebounce = SharedSettings.autoSaveDebounceTicks
            SharedSettings.structureRegionChunks = 1
            SharedSettings.autoSaveDebounceTicks = 0
            SharedSettings.autoSaveEnabled = false
            val histDir = kotlin.io.path.createTempDirectory("autosave-off-hist")
            SharedSettings.localHistoryDir = histDir.toAbsolutePath().toString()
            EditorNewStructure.create(tmp, "manual")
            try {
                onServer {
                    EditorServerContext.set(this, EditorServerContext(EditorRoot(tmp)))
                    val player = makeMockServerPlayer(this)
                    EditorNetworking.handlePlaceStructure(this, player, PlaceStructureC2S("manual.nbt"))
                    drainPayloads(player)

                    val registry = EditorDimRegistry.of(this)
                    val region = registry.structureRegionOriginOf("manual.nbt")!!
                    val lvl = overworld()
                    val width = SharedSettings.structureRegionChunks * 16
                    StructurePersistence.clearBounds(
                        lvl, BlockPos(region.x, lvl.minY, region.z),
                        Vec3i(width, lvl.maxY - lvl.minY + 1, width),
                    )
                    val edited = region.offset(2, 0, 2)
                    lvl.setBlock(edited, Blocks.GOLD_BLOCK.defaultBlockState(), 2)
                    StructureEditWatcher.onBlockChanged(lvl, edited)

                    StructureCommit.tick(this)
                    StructureAutoSave.of(this).isDirty("manual.nbt") shouldBe true

                    StructureCommit.commit(this, "manual.nbt", LocalHistoryStore.REASON_MANUAL).shouldNotBeNull()
                    StructureAutoSave.of(this).isDirty("manual.nbt") shouldBe false
                }
            } finally {
                SharedSettings.structureRegionChunks = prevChunks
                SharedSettings.localHistoryDir = prevHistDir
                SharedSettings.autoSaveEnabled = prevEnabled
                SharedSettings.autoSaveDebounceTicks = prevDebounce
                histDir.toFile().deleteRecursively()
            }
        }
    }
```

Add the imports these need: `com.breadmoirai.garnet.editor.data.EditorNewStructure`, `com.breadmoirai.garnet.editor.data.EditorRoot`, `com.breadmoirai.garnet.editor.world.EditorServerContext`, `com.breadmoirai.garnet.editor.world.StructureCommit`, `com.breadmoirai.garnet.editor.network.EditorNetworking`, `com.breadmoirai.garnet.editor.network.PlaceStructureC2S`, `com.breadmoirai.garnet.history.LocalHistoryStore`, `com.breadmoirai.garnet.structure.StructurePersistence`, `com.breadmoirai.garnet.test.drainPayloads`, `com.breadmoirai.garnet.test.makeMockServerPlayer`, `com.breadmoirai.garnet.test.withTempRoot`, `io.kotest.matchers.collections.shouldHaveSize`, `net.minecraft.world.level.block.Blocks`, `kotlin.io.path.readBytes`.

The `placed`-revision assertion in the first test depends on Step 4's change to `handlePlaceStructure`.

- [ ] **Step 2: Run the test to verify it fails**

Run: `cmd.exe /c "gradlew.bat :26.2:runGameTest"` (foreground, `timeout: 600000`)
Expected: compile failure — `StructureCommit` unresolved.

- [ ] **Step 3: Write the commit path**

First, in `EditorNetworking.kt`, change `private fun rootFor(` to `fun rootFor(` and give it a doc comment:

```kotlin
    /**
     * The active managed root: the loaded world's, else a pinned server context's, else the
     * configured path. Public because [StructureCommit] resolves subpaths through the same rule.
     */
    fun rootFor(server: MinecraftServer): EditorRoot? {
```

Create `src/main/kotlin/com/breadmoirai/garnet/editor/world/StructureCommit.kt`:

```kotlin
package com.breadmoirai.garnet.editor.world

import com.breadmoirai.garnet.config.SharedSettings
import com.breadmoirai.garnet.editor.network.EditorNetworking
import com.breadmoirai.garnet.editor.network.StructureAutoSavedS2C
import com.breadmoirai.garnet.history.LocalHistoryStore
import com.breadmoirai.garnet.structure.PlacedBox
import com.breadmoirai.garnet.structure.StructurePersistence
import com.breadmoirai.garnet.structure.structuresDiffer
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.core.BlockPos
import net.minecraft.core.Vec3i
import net.minecraft.nbt.NbtAccounter
import net.minecraft.nbt.NbtIo
import net.minecraft.server.MinecraftServer
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.file.Path
import kotlin.io.path.exists

private val LOGGER = LoggerFactory.getLogger("Garnet")

/**
 * Turns a structure's dirty state into a committed `.nbt` plus a history revision.
 *
 * This replaces the old `.nbt.unsaved` sidecar flush: there is no dirty buffer any more, so a
 * commit writes the real file every time and the local-history store is what makes an edit
 * reversible.
 */
object StructureCommit {

    /**
     * Capture, diff, and write [subpath] if its content actually changed. Returns the packet
     * describing what was written, or null when nothing needed writing (or the structure is not
     * placed / not resolvable). Always clears the dirty state — a structure that captured identical
     * to disk is clean by definition.
     */
    fun commit(server: MinecraftServer, subpath: String, reason: String): StructureAutoSavedS2C? {
        val autoSave = StructureAutoSave.of(server)
        val root = EditorNetworking.rootFor(server) ?: return null
        val file = root.resolveSubpath(subpath) ?: return null
        val registry = EditorDimRegistry.of(server)
        val placed = registry.placedBoxOf(subpath) ?: return null

        val scan = union(placed, autoSave.dirtyBox(subpath)) ?: run {
            autoSave.clear(subpath)
            return null
        }
        val captured = StructurePersistence.captureAutoFitIn(registry.projectLevel(), scan)

        val committed = readTag(file)
        if (committed != null && !structuresDiffer(committed, captured.tag)) {
            autoSave.clear(subpath)
            return null
        }

        val size = captured.box?.size ?: Vec3i(0, 0, 0)
        LocalHistoryStore.writeRevision(
            file, captured.tag, size.x, size.y, size.z, captured.blockCount, reason,
        )
        try {
            file.parent?.let { java.nio.file.Files.createDirectories(it) }
            NbtIo.writeCompressed(captured.tag, file)
        } catch (e: IOException) {
            LOGGER.error("[StructureCommit] write '{}': {}", file, e.message)
            return null
        }
        captured.box?.let { registry.setPlacedBox(subpath, it) }
        autoSave.clear(subpath)

        return StructureAutoSavedS2C(
            subpath, size.x, size.y, size.z, captured.blockCount, System.currentTimeMillis(),
        )
    }

    /** Commit every dirty structure that has come due, and tell the clients. */
    fun tick(server: MinecraftServer) {
        if (!SharedSettings.autoSaveEnabled) return
        val autoSave = StructureAutoSave.of(server)
        if (autoSave.dirtySubpaths().isEmpty()) return
        val now = server.overworld().gameTime
        for (subpath in autoSave.dirtySubpaths()) {
            if (!autoSave.dueForCommit(subpath, now)) continue
            commit(server, subpath, LocalHistoryStore.REASON_AUTOSAVE)?.let { broadcast(server, it) }
        }
    }

    /**
     * Backstop flush: commit every dirty structure regardless of timing. Used on world-save, server
     * stop, and before operations that would strand dirty state (rename, unplace).
     */
    fun commitAll(server: MinecraftServer, reason: String) {
        val autoSave = StructureAutoSave.of(server)
        for (subpath in autoSave.dirtySubpaths()) {
            commit(server, subpath, reason)?.let { broadcast(server, it) }
        }
    }

    fun broadcast(server: MinecraftServer, payload: StructureAutoSavedS2C) {
        for (player in server.playerList.players) {
            ServerPlayNetworking.send(player, payload)
        }
    }

    /**
     * The volume to scan: the structure's own extent plus wherever the player touched. Zero-size
     * boxes contribute nothing — an emptied structure has a size-0 placed box, and unioning that
     * with a real edit box would otherwise drag the origin to a meaningless corner.
     */
    private fun union(placed: PlacedBox, dirty: PlacedBox?): PlacedBox? {
        val boxes = listOfNotNull(placed, dirty).filter { it.size.x > 0 && it.size.y > 0 && it.size.z > 0 }
        if (boxes.isEmpty()) return null
        var minX = Int.MAX_VALUE; var minY = Int.MAX_VALUE; var minZ = Int.MAX_VALUE
        var maxX = Int.MIN_VALUE; var maxY = Int.MIN_VALUE; var maxZ = Int.MIN_VALUE
        for (box in boxes) {
            minX = minOf(minX, box.origin.x); maxX = maxOf(maxX, box.origin.x + box.size.x - 1)
            minY = minOf(minY, box.origin.y); maxY = maxOf(maxY, box.origin.y + box.size.y - 1)
            minZ = minOf(minZ, box.origin.z); maxZ = maxOf(maxZ, box.origin.z + box.size.z - 1)
        }
        return PlacedBox(
            BlockPos(minX, minY, minZ),
            Vec3i(maxX - minX + 1, maxY - minY + 1, maxZ - minZ + 1),
        )
    }

    private fun readTag(file: Path) =
        if (!file.exists()) null
        else runCatching { NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap()) }.getOrNull()
}
```

- [ ] **Step 4: Seed the baseline revision on place**

In `EditorNetworking.handlePlaceStructure`, after the `payload.subpath.endsWith(".nbt")` guard and before `placeStructureFrom`, record the pre-edit baseline:

```kotlin
        // Seed the pre-edit baseline so a rollback target exists from the moment the structure is
        // opened, not only after the first auto-save.
        if (file.exists()) {
            val tag = runCatching { NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap()) }.getOrNull()
            if (tag != null && LocalHistoryStore.revisions(file).isEmpty()) {
                val template = StructureTemplate()
                template.load(server.registryAccess().lookupOrThrow(Registries.BLOCK), tag)
                val size = template.size
                LocalHistoryStore.writeRevision(
                    file, tag, size.x, size.y, size.z,
                    blockCount = 0, reason = LocalHistoryStore.REASON_PLACED,
                )
            }
        }
```

`blockCount = 0` on the baseline is deliberate: the count is only known by scanning the world, and the structure has not been placed yet at this point. A revision browser should show the size and treat a zero count on a `placed` revision as "not measured".

Add imports to `EditorNetworking.kt`: `com.breadmoirai.garnet.history.LocalHistoryStore`, `net.minecraft.core.registries.Registries`, `net.minecraft.nbt.NbtAccounter`, `net.minecraft.nbt.NbtIo`, `net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate`, `kotlin.io.path.exists` (already imported).

- [ ] **Step 5: Wire the tick and the world-save backstop**

In `src/main/kotlin/com/breadmoirai/garnet/Garnet.kt`, replace the `BEFORE_SAVE` listener body and add a tick listener:

```kotlin
        ServerTickEvents.END_SERVER_TICK.register { server ->
            StructureCommit.tick(server)
        }
        ServerLifecycleEvents.BEFORE_SAVE.register { server, _, _ ->
            StructureCommit.commitAll(server, LocalHistoryStore.REASON_AUTOSAVE)
        }
```

and extend the existing `SERVER_STOPPED` listener:

```kotlin
        ServerLifecycleEvents.SERVER_STOPPED.register { server ->
            StructureCommit.commitAll(server, LocalHistoryStore.REASON_AUTOSAVE)
            StructureAutoSave.dispose(server)
            EditorDimLifecycle.releaseServerState(server)
        }
```

Add imports: `net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents`, `com.breadmoirai.garnet.editor.world.StructureCommit`, `com.breadmoirai.garnet.editor.world.StructureAutoSave`, `com.breadmoirai.garnet.history.LocalHistoryStore`.

Note that `SERVER_STOPPED` fires after the final save, so `commitAll` there is the belt-and-braces path for a dirty structure edited between the last save and shutdown.

- [ ] **Step 6: Run the test to verify it passes**

Run: `cmd.exe /c "gradlew.bat :26.2:runGameTest"` (foreground, `timeout: 600000`)
Expected: the four new commit tests pass. Existing sidecar tests still pass — nothing has been removed yet.

- [ ] **Step 7: Commit**

```bash
git add src/main/kotlin/com/breadmoirai/garnet/editor/world/StructureCommit.kt \
        src/main/kotlin/com/breadmoirai/garnet/editor/network/EditorNetworking.kt \
        src/main/kotlin/com/breadmoirai/garnet/Garnet.kt \
        src/gametest/kotlin/com/breadmoirai/garnet/test/editor/StructureAutoSaveSpec.kt
git commit -m "feat(editor): commit structures on a debounce with history revisions"
```

---

### Task 7: Remove the sidecar model

**Files:**
- Modify: `src/main/kotlin/com/breadmoirai/garnet/structure/StructurePersistence.kt`
- Modify: `src/main/kotlin/com/breadmoirai/garnet/editor/data/FileTree.kt`
- Modify: `src/main/kotlin/com/breadmoirai/garnet/editor/network/EditorPackets.kt`
- Modify: `src/main/kotlin/com/breadmoirai/garnet/editor/network/EditorNetworking.kt`
- Modify: `src/client/kotlin/com/breadmoirai/garnet/editor/ui/ProjectExplorerPanel.kt`
- Modify: `src/gametest/kotlin/com/breadmoirai/garnet/test/editor/EditorStructureNetworkSpec.kt`
- Modify: `src/gametest/kotlin/com/breadmoirai/garnet/test/editor/EditorFileOpsNetworkSpec.kt`

**Interfaces:**
- Removes: `StructurePersistence.unsavedSidecarOf`, `StructurePersistence.flushUnsavedSidecar`, `FileNode.hasUnsaved`, `StructureResultS2C.hasUnsaved`, `DiscardStructureC2S`, `EditorNetworking.handleDiscardStructure`, `EditorNetworking.flushDirtyStructures`.
- Changes: `StructureResultS2C(subpath, sizeX, sizeY, sizeZ, message)`; `FileNode(name, extension)`; `handleSaveStructure` delegates to `StructureCommit.commit(..., REASON_MANUAL)`.

- [ ] **Step 1: Update the tests first**

In `EditorStructureNetworkSpec.kt`, delete the whole `"dirty sidecar lifecycle: flush writes/deletes, place loads unsaved, save+discard clear"` test and replace it with:

```kotlin
    test("editing a placed structure and force-saving commits straight to the .nbt") {
        withTempRoot("struct-commit") { tmp ->
            val prevChunks = SharedSettings.structureRegionChunks
            val prevHistDir = SharedSettings.localHistoryDir
            SharedSettings.structureRegionChunks = 1
            val histDir = kotlin.io.path.createTempDirectory("struct-commit-hist")
            SharedSettings.localHistoryDir = histDir.toAbsolutePath().toString()
            EditorNewStructure.create(tmp, "widget")
            val committed = tmp.resolve("widget.nbt")
            try {
                onServer {
                    EditorServerContext.set(this, EditorServerContext(EditorRoot(tmp)))
                    val player = makeMockServerPlayer(this)
                    drainPayloads(player)

                    EditorNetworking.handlePlaceStructure(this, player, PlaceStructureC2S("widget.nbt"))
                    drainPayloads(player)

                    val region = EditorDimRegistry.of(this).structureRegionOriginOf("widget.nbt")!!
                    val lvl = overworld()
                    val width = SharedSettings.structureRegionChunks * 16
                    StructurePersistence.clearBounds(
                        lvl, BlockPos(region.x, lvl.minY, region.z),
                        Vec3i(width, lvl.maxY - lvl.minY + 1, width),
                    )
                    val before = committed.readBytes().toList()

                    val edited = region.offset(5, 0, 5)
                    lvl.setBlock(edited, Blocks.GOLD_BLOCK.defaultBlockState(), 2)
                    StructureEditWatcher.onBlockChanged(lvl, edited)

                    EditorNetworking.handleSaveStructure(this, player, SaveStructureC2S("widget.nbt"))

                    // The committed file itself changed — there is no dirty buffer any more.
                    committed.readBytes().toList() shouldNotBe before
                    val saved = drainPayloads(player).filterIsInstance<StructureAutoSavedS2C>().last()
                    saved.subpath shouldBe "widget.nbt"
                    saved.sizeX shouldBe 1
                    // The pre-edit state is recoverable from history rather than from a sidecar.
                    LocalHistoryStore.revisions(committed).size shouldBe 2
                }
            } finally {
                SharedSettings.structureRegionChunks = prevChunks
                SharedSettings.localHistoryDir = prevHistDir
                histDir.toFile().deleteRecursively()
            }
        }
    }
```

Remove the now-unused `DiscardStructureC2S` import and add `StructureAutoSavedS2C`, `StructureEditWatcher`, `LocalHistoryStore`, and `io.kotest.matchers.shouldNotBe`.

In `EditorFileOpsNetworkSpec.kt`, replace the `"renaming a placed AND dirty structure re-places from its sidecar, not the stale saved file"` test with:

```kotlin
    test("renaming a placed AND dirty structure commits first, so no edits are lost") {
        // REGRESSION (reframed): under the old sidecar model a rename could repaint the world from
        // the stale saved file and lose unsaved edits. With auto-save there is no dirty buffer, so
        // the invariant is now that handleRename commits BEFORE moving the file — otherwise the
        // dirty box would be stranded against the old subpath and the edits would be dropped.
        withServer { server, player, root ->
            EditorNewStructure.create(root, "clock")
            EditorNetworking.handlePlaceStructure(server, player, PlaceStructureC2S("clock.nbt"))
            drainPayloads(player)

            val registry = EditorDimRegistry.of(server)
            val origin = registry.structureRegionOriginOf("clock.nbt").shouldNotBeNull()
            val level = registry.projectLevel()
            level.setBlock(origin, Blocks.STONE.defaultBlockState(), 3)
            StructureEditWatcher.onBlockChanged(level, origin)
            StructureAutoSave.of(server).isDirty("clock.nbt").shouldBeTrue()

            EditorNetworking.handleRename(server, player, RenamePathC2S("clock.nbt", "ring.nbt"))

            // The edit was committed under the OLD name before the move, then carried across.
            StructureAutoSave.of(server).isDirty("clock.nbt").shouldBeFalse()
            val result = drainPayloads(player).filterIsInstance<StructureResultS2C>().last()
            result.subpath shouldBe "ring.nbt"
            LocalHistoryStore.revisions(root.resolve("ring.nbt")).size shouldBe 2
        }
    }
```

Adjust its imports the same way (`StructureEditWatcher`, `StructureAutoSave`, `LocalHistoryStore`; drop `StructurePersistence.unsavedSidecarOf` usages). Also delete the `StructurePersistence.unsavedSidecarOf(...)` assertion lines anywhere else in that file and any `hasUnsaved` reference. Grep to be sure:

```bash
grep -rn "unsavedSidecarOf\|hasUnsaved\|flushDirtyStructures\|DiscardStructureC2S" src/
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `cmd.exe /c "gradlew.bat :26.2:runGameTest"` (foreground, `timeout: 600000`)
Expected: compile failure — `StructureAutoSavedS2C` is not yet what `handleSaveStructure` sends, and the removed symbols are still referenced by main.

- [ ] **Step 3: Strip the sidecar from persistence and the data model**

In `StructurePersistence.kt` delete `unsavedSidecarOf` and `flushUnsavedSidecar` entirely.

In `FileTree.kt`, change `FileNode` and `scanFolder`:

```kotlin
/** A file. [extension] is the lowercased last-dot extension, "" when the name has no dot. */
data class FileNode(
    override val name: String,
    val extension: String,
) : FileTreeNode
```

```kotlin
fun scanFolder(path: Path): FolderNode {
    if (!path.isDirectory()) return FolderNode(path.name, emptyList())
    val children = path.listDirectoryEntries()
        .map { entry ->
            if (entry.isDirectory()) scanFolder(entry)
            else FileNode(entry.name, entry.extension.lowercase())
        }
        .sortedWith(CHILD_ORDER)
    return FolderNode(path.name, children)
}
```

- [ ] **Step 4: Strip the sidecar from the wire and the handlers**

In `EditorPackets.kt`:

- In `FILE_TREE_STREAM_CODEC`, drop the boolean: decode becomes `TAG_FILE -> FileNode(name, ByteBufCodecs.STRING_UTF8.decode(buf))`, and the `is FileNode ->` encode branch loses its `buf.writeBoolean(value.hasUnsaved)` line.
- Delete the `hasUnsaved` property and codec field from `StructureResultS2C`, leaving `(subpath, sizeX, sizeY, sizeZ, message)`.
- Delete `DiscardStructureC2S` entirely.

In `EditorNetworking.kt`:

- Delete the `DiscardStructureC2S` payload-type registration and its `registerGlobalReceiver`.
- Delete `handleDiscardStructure` and `flushDirtyStructures`.
- `placeStructureFrom` loses its `hasUnsaved` parameter; update its `StructureResultS2C` construction and both call sites.
- `handlePlaceStructure` loses the sidecar branch:

```kotlin
        placeStructureFrom(server, player, payload.subpath, file, "placed ${payload.subpath}")
```

- `handleSaveStructure` becomes a force-commit:

```kotlin
    fun handleSaveStructure(server: MinecraftServer, player: ServerPlayer, payload: SaveStructureC2S) {
        val root = rootFor(server) ?: run {
            ServerPlayNetworking.send(player, EditorErrorS2C("project-root not configured")); return
        }
        if (root.resolveSubpath(payload.subpath) == null) {
            ServerPlayNetworking.send(player, EditorErrorS2C("subpath not found or escapes root: ${payload.subpath}")); return
        }
        if (!payload.subpath.endsWith(".nbt")) {
            ServerPlayNetworking.send(player, EditorErrorS2C("not a structure file: ${payload.subpath}")); return
        }
        if (EditorDimRegistry.of(server).placedBoxOf(payload.subpath) == null) {
            ServerPlayNetworking.send(player, EditorErrorS2C("place the structure before saving: ${payload.subpath}"))
            return
        }
        val result = StructureCommit.commit(server, payload.subpath, LocalHistoryStore.REASON_MANUAL)
        if (result == null) {
            // Nothing to write: the region already matches the committed file.
            ServerPlayNetworking.send(player, StructureResultS2C(
                payload.subpath, 0, 0, 0, "no changes to save: ${payload.subpath}",
            ))
        } else {
            StructureCommit.broadcast(server, result)
        }
    }
```

- In `handleRename`, commit before the move and move the history alongside the file. Immediately before the `val target = parent.resolve(newName)` line:

```kotlin
        // Commit before the move: the dirty box is keyed by subpath, so moving first would strand
        // the edits under a name nothing will ever commit again.
        if (wasPlaced != null) StructureCommit.commit(server, payload.subpath, LocalHistoryStore.REASON_AUTOSAVE)
```

and inside the `try` block, replacing the sidecar move:

```kotlin
            source.moveTo(target)
            // History is keyed by the file's absolute path, so a rename must carry it across or the
            // structure silently loses every revision it has accumulated.
            LocalHistoryStore.moveHistory(source, target)
```

Note `source` has already been moved at that point, so `moveHistory(source, target)` keys off the old path string — which is exactly what is wanted, since `keyOf` is a pure path computation and does not require the file to exist.

- In the `wasPlaced != null` re-place block, drop the sidecar preference:

```kotlin
        if (wasPlaced != null) {
            placeStructureFrom(server, player, newSubpath, target, "renamed to $newSubpath")
        }
```

Add `StructureCommit` and `LocalHistoryStore` imports; remove now-unused ones (`deleteIfExists` may become unused).

- [ ] **Step 5: Strip the dirty marker from the UI**

In `ProjectExplorerPanel.kt`'s `TreeRow`:

```kotlin
        val marker = if (path == currentSubpath) "● " else ""
        Text("  $marker${node.name}")
```

Delete the `val dirty = ...` and `val current = ...` lines. Check `ExplorerTreeStateSpec` / `JewelExplorerSpec` / `StructureExplorerSpec` in `clientTest` for `FileNode(...)` constructions passing three arguments and drop the third.

- [ ] **Step 6: Verify everything compiles and passes**

```bash
grep -rn "unsavedSidecarOf\|hasUnsaved\|flushDirtyStructures\|DiscardStructureC2S" src/
```
Expected: no results.

Run: `cmd.exe /c "gradlew.bat :26.2:classes :26.2:clientClasses :26.2:gametestClasses :26.2:clientTestClasses :26.2:testClasses"`
Expected: BUILD SUCCESSFUL.

Run: `cmd.exe /c "gradlew.bat :26.2:runGameTest"` (foreground, `timeout: 600000`)
Expected: all pass.

Run: `cmd.exe /c "gradlew.bat :26.2:runClientTest"` (foreground, `timeout: 600000`)
Expected: all pass.

- [ ] **Step 7: Commit**

Stage explicit paths only — the working tree may hold unrelated in-progress edits that must not be swept into this commit.

```bash
git add src/main/kotlin/com/breadmoirai/garnet/structure/StructurePersistence.kt \
        src/main/kotlin/com/breadmoirai/garnet/editor/data/FileTree.kt \
        src/main/kotlin/com/breadmoirai/garnet/editor/network/EditorPackets.kt \
        src/main/kotlin/com/breadmoirai/garnet/editor/network/EditorNetworking.kt \
        src/client/kotlin/com/breadmoirai/garnet/editor/ui/ProjectExplorerPanel.kt \
        src/gametest/kotlin/com/breadmoirai/garnet/test/editor/EditorStructureNetworkSpec.kt \
        src/gametest/kotlin/com/breadmoirai/garnet/test/editor/EditorFileOpsNetworkSpec.kt
# Plus any clientTest spec whose FileNode(...) constructions Step 5 had to amend.
git status --short  # confirm nothing unrelated is staged
git commit -m "refactor(editor): remove the .nbt.unsaved sidecar model"
```

---

### Task 8: Documentation

**Files:**
- Create: `docs/persistence/local-history.md`
- Modify: `docs/persistence/INDEX.md`, `docs/architecture/redstone-project.md`, `docs/architecture/module-map.md`, `docs/persistence/spec-on-disk-format.md`, `docs/ui/explorer-toolbar-and-context-menu.md`, `docs/use-cases/persistence.md`, `docs/use-cases/redstone-project.md`, `docs/use-cases/structure-lifecycle.md`, and any `INDEX.md` whose summary text changes

- [ ] **Step 1: Find every stale reference**

```bash
grep -rn "unsaved\|sidecar\|Discard\|flushDirtyStructures" docs/ --include=*.md | grep -v "^docs/superpowers/"
```

Read each hit. `docs/superpowers/` is excluded on purpose — those are commit-time snapshots and must be left as-is.

- [ ] **Step 2: Write the new article**

Create `docs/persistence/local-history.md` with the required frontmatter:

```markdown
---
title: Local history for standalone structures
tags: [storage, history, autosave, structures, persistence]
summary: How auto-saved .nbt structures record revisions under <instance>/.garnet/local-history, why the key is the file's absolute path, and how pruning works.
---
```

The body must cover, in prose rather than a code dump:

- The directory layout and the `<epochMillis>-<seq>.nbt` + `index.json` contract.
- **Why the key is the structure file's own absolute path** rather than the project root plus subpath: the editor's root is swappable via "Open Folder…", so root-keying would fork one file's history the moment a user opened its parent directory instead. This is the single most non-obvious decision in the subsystem.
- Why Windows paths are lowercased before hashing.
- The pruning policy (age then count) and which settings control it.
- That history deliberately outlives a deleted structure.
- That `blockCount` is `0` on a `placed` baseline revision because the count is only knowable by scanning the world, which has not happened yet at place time.
- A pointer to `StructureCommit` as the only writer.

Register it in `docs/persistence/INDEX.md` as
`- [Local history for standalone structures](local-history.md) — <the summary line above>` plus its tags, matching the file's existing entry format.

- [ ] **Step 3: Update the stale references**

For each hit from Step 1, either delete the obsolete section or rewrite it to describe the auto-save model, and never leave a dangling reference. Specifically:

- `docs/architecture/redstone-project.md` — the standalone-structure section describes the sidecar lifecycle and the `Save`/`Discard` packets. Rewrite it around the debounce commit, and link to `local-history.md`.
- `docs/ui/explorer-toolbar-and-context-menu.md` — remove the claim that `Save`/`Discard` are wired-but-unexposed; `Discard` no longer exists and `Save` is a force-commit.
- `docs/persistence/spec-on-disk-format.md` — drop the `.nbt.unsaved` entry from the on-disk inventory, add the local-history directory.
- `docs/use-cases/structure-lifecycle.md`, `docs/use-cases/persistence.md`, `docs/use-cases/redstone-project.md` — the dirty/save/discard journeys become edit → auto-commit → history, and the coverage audit should point at `StructureAutoSaveSpec` and `LocalHistoryStoreSpec`.
- `docs/architecture/module-map.md` — add the `history/` package.

Also update any `file:line` citation that Tasks 1–7 invalidated, or convert it to a description that survives refactors.

- [ ] **Step 4: Verify cross-references resolve**

```bash
grep -rn "unsaved\|sidecar\|flushDirtyStructures" docs/ --include=*.md | grep -v "^docs/superpowers/"
```
Expected: only hits that legitimately describe the *spec-cell* `save`/`load` path (which `StructureSidecarPersistenceSpec` covers), or historical notes explicitly framed as such. Every `[link](path.md)` added must resolve to a real file, and every new `INDEX.md` entry's summary must match the article's frontmatter `summary`.

- [ ] **Step 5: Commit**

```bash
git add docs/
git commit -m "docs: describe structure auto-save and local history"
```

---

## Self-Review Notes

**Spec coverage:** §1 sidecar removal → Task 7. §2 dirty box → Tasks 3 and 4. §3 commit timing → Tasks 4 (predicate) and 6 (commit, tick, backstops). §4 history store → Task 2, with the `placed` baseline in Task 6 Step 4 and the rename move in Task 7 Step 4. §5 config → Task 1. §6 client feedback → Task 5. §7 testing → distributed across every task, with sentinel registration called out in Tasks 1, 2, and 4. §8 documentation → Task 8.

**Deliberate ordering:** the sidecar is removed *last*, after auto-save works, so no task leaves the tree in a state where a structure's edits can be lost. Task 3 keeps `captureAutoFit`'s signature intact for exactly this reason.

**Known imprecision to resolve during implementation:** the exact MC 26.2 accessor name for reading an NBT string (`getStringOr`, used in `LocalHistoryStoreSpec`) was not verified against the decompiled sources — `StructureDiff.kt` uses the sibling `getIntOr` / `getCompoundOrEmpty` idiom, so match that file if it differs. Fix it in Task 2 rather than carrying the guess forward.

`StreamCodec.composite`'s 6-pair overload and the `ClientSpec` base class were both verified against existing code and are not open questions.
