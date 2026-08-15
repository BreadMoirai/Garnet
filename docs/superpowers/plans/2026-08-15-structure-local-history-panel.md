# Local History Panel Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a Local History panel, tabbed beside the Project Explorer, that lists a placed structure's banked revisions and restores a chosen one into both the world and the `.nbt` — undoably.

**Architecture:** The server owns everything authoritative: it reads `LocalHistoryStore`, performs the restore through `StructureCommit` (which stays the sole writer of any `.nbt`), and pushes a refreshed revision list to whichever players are watching that subpath. The client holds a purely presentational list plus the dock tab strip needed to reach it. One invariant carries the design: the panel only ever shows a structure that is **currently placed**, so restore never needs a not-placed code path.

**Tech Stack:** Kotlin, Fabric (Minecraft 26.2 via Stonecutter), Compose Multiplatform + Jewel for the dock UI, Kotest for tests.

**Spec:** `docs/superpowers/specs/2026-08-15-structure-local-history-panel-design.md`

## Global Constraints

- **Stonecutter version prefix:** every Gradle invocation is versioned. Compile with
  `cmd.exe /c "cd /d H:\\Repo\\garnet && gradlew.bat :26.2:clientClasses :26.2:classes :26.2:gametestClasses :26.2:clientTestClasses :26.2:testClasses"`.
  `compileKotlin` alone only covers `main` and will miss client/test breakage.
- **Test commands:** `cmd.exe /c "gradlew.bat :26.2:test"` (JUnit, no MC runtime),
  `cmd.exe /c "gradlew.bat :26.2:runGameTest"` (server `@GameTest` harness).
- **Gradle's `--tests` filter does not select Kotest specs.** Run the task unfiltered and read the
  per-class JUnit XML under `build/test-results/` for a single spec's result.
- **`StructureCommit` is the only writer of a `.nbt`.** No task in this plan writes a structure file
  directly.
- **Unsolicited S2C sends must be `canSend`-guarded.** A push that is not a reply to a C2S can reach
  a vanilla client and disconnect it. Replies to a C2S the player just sent are sent unguarded.
- **New history reason string:** `"restore"`, as `LocalHistoryStore.REASON_RESTORE`.
- **Panel state is `remember`-ed inside the panel, never a top-level object.** The dock composes into
  a long-lived singleton scene; global panel state survives a re-mount and paints over the next one.
- **No emoji or non-Latin symbols in UI text.** Jewel's Inter has no emoji coverage; status is
  conveyed with theme colors and interactivity.

---

## File Structure

**Create (server, `src/main/kotlin/com/breadmoirai/garnet/editor/history/`):**
- `HistoryWatchers.kt` — which subpath each player has open; the push fan-out.
- `StructureRestoreOps.kt` — the restore sequence, callable without networking.

**Create (client, `src/client/kotlin/com/breadmoirai/garnet/editor/ui/`):**
- `OpenStructureState.kt` — the client's record of the placed structure's subpath.
- `LocalHistoryState.kt` — the panel's revision list + selection.
- `LocalHistoryPanel.kt` — the panel body and its `Panel` factory.

**Create (client, `src/client/kotlin/com/breadmoirai/garnet/ui/dock/`):**
- `DockTabStrip.kt` — the tab strip composable.

**Modify (server):**
- `history/LocalHistoryStore.kt` — add `REASON_RESTORE`.
- `structure/StructurePersistence.kt` — extract a tag-taking `placeStructureTagCentered`.
- `editor/network/EditorPackets.kt` — three payloads + `RevisionEntry`.
- `editor/network/EditorNetworkRegistry.kt` — register them.
- `editor/network/EditorStructureHandlers.kt` — watch + restore handlers.
- `editor/structure/StructureCommit.kt` — push watchers after a commit.
- `editor/undo/EditorUndoCommand.kt` — `RestoreRevision`.
- `editor/undo/EditorUndoOps.kt` — its inverse and reapply branches.

**Modify (client):**
- `ui/dock/DockState.kt` — a writer for the active tab index.
- `ui/dock/GarnetDock.kt` — render the strip.
- `editor/network/EditorClientNetworking.kt` — receive `StructureHistoryS2C`, record placed subpath.
- `editor/ui/ExplorerContextMenu.kt` — the "Local History" item.
- `editor/ui/ProjectExplorerPanel.kt` — wire the menu item.
- `GarnetClient.kt` — seed the second LEFT panel.

**Task order** builds server-inward-out: store constant → placement refactor → restore core → undo →
packets → watchers → client state → panel → dock strip → menu wiring → docs.

---

### Task 1: The `restore` history reason

**Files:**
- Modify: `src/main/kotlin/com/breadmoirai/garnet/history/LocalHistoryStore.kt` (beside the other `REASON_*` constants, ~line 61)
- Test: `src/gametest/kotlin/com/breadmoirai/garnet/test/history/LocalHistoryStoreSpec.kt`

**Interfaces:**
- Consumes: nothing.
- Produces: `LocalHistoryStore.REASON_RESTORE: String` (value `"restore"`).

- [ ] **Step 1: Write the failing test**

Append inside the existing `LocalHistoryStoreSpec({ ... })` block. Match the file's existing helper
usage — open it first and reuse whatever temp-dir/`writeRevision` helper the neighbouring tests use
rather than inventing one.

```kotlin
    test("a restore revision round-trips its reason") {
        withHistoryDir { dir ->
            val file = dir.resolve("clock.nbt")
            val tag = CompoundTag()
            tag.putString("marker", "restored")

            LocalHistoryStore.writeRevision(
                file, tag, 1, 2, 3, blockCount = 7, reason = LocalHistoryStore.REASON_RESTORE,
            ).shouldNotBeNull()

            val revisions = LocalHistoryStore.revisions(file)
            revisions shouldHaveSize 1
            revisions.last().reason shouldBe "restore"
            revisions.last().blockCount shouldBe 7
            LocalHistoryStore.readTag(file, revisions.last())!!.getString("marker") shouldBe "restored"
        }
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cmd.exe /c "gradlew.bat :26.2:runGameTest"`
Expected: FAIL — `REASON_RESTORE` is an unresolved reference (compile error).

- [ ] **Step 3: Write minimal implementation**

In `LocalHistoryStore`, directly after `REASON_PRE_DELETE`:

```kotlin
    /** The content re-placed by a Local History restore, banked by the commit that follows it.
     *  Written through the normal [StructureCommit] path, so unlike [REASON_PLACED] it carries a
     *  real block count. See docs/persistence/local-history.md. */
    const val REASON_RESTORE = "restore"
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cmd.exe /c "gradlew.bat :26.2:runGameTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/breadmoirai/garnet/history/LocalHistoryStore.kt \
        src/gametest/kotlin/com/breadmoirai/garnet/test/history/LocalHistoryStoreSpec.kt
git commit -m "feat(history): add the restore revision reason"
```

---

### Task 2: Place a structure from a tag, not only from a file

`StructurePersistence.placeStructureCentered` reads its `Path` internally. The restore path holds a
`CompoundTag` read out of a history blob and has no file to point at. Extract the core; keep the
`Path` overload as a wrapper so every existing caller is untouched.

**Files:**
- Modify: `src/main/kotlin/com/breadmoirai/garnet/structure/StructurePersistence.kt:195-222`
- Test: `src/gametest/kotlin/com/breadmoirai/garnet/test/editor/StructureRestoreSpec.kt` (new file)

**Interfaces:**
- Consumes: `LocalHistoryStore.REASON_RESTORE` (Task 1) — not used here, but the new spec file lands in this task.
- Produces:
  ```kotlin
  fun placeStructureTagCentered(
      nbt: CompoundTag, level: ServerLevel, regionOrigin: BlockPos,
      regionSizeXZ: Int, regionMinY: Int, regionMaxY: Int, yBase: Int,
  ): PlacedBox?
  ```
  The existing `placeStructureCentered(file, ...)` keeps its exact current signature and return type.

- [ ] **Step 1: Write the failing test**

Create `src/gametest/kotlin/com/breadmoirai/garnet/test/editor/StructureRestoreSpec.kt`. Model the
imports and harness call on `EditorUndoNetworkSpec` — same package conventions, `GarnetTestSpec` base,
`withEditorServer`.

```kotlin
package com.breadmoirai.garnet.test.editor

import com.breadmoirai.garnet.harness.GarnetTestSpec
import com.breadmoirai.garnet.structure.StructurePersistence
import com.breadmoirai.garnet.test.withEditorServer
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import net.minecraft.core.BlockPos
import net.minecraft.nbt.NbtAccounter
import net.minecraft.nbt.NbtIo
import net.minecraft.world.level.block.Blocks
import kotlin.io.path.exists

class StructureRestoreSpec : GarnetTestSpec({

    test("placeStructureTagCentered places the same blocks as the file overload") {
        withEditorServer("restore-tag") { server, _, root ->
            val level = server.overworld()
            // Build a one-block structure on disk via the existing capture+write path, then read
            // its tag back so both overloads get provably identical input.
            val file = root.resolve("probe.nbt")
            val origin = BlockPos(64, 70, 64)
            level.setBlockAndUpdate(origin, Blocks.REDSTONE_BLOCK.defaultBlockState())
            val captured = StructurePersistence.captureAutoFitIn(
                level, com.breadmoirai.garnet.editor.world.PlacedBox(origin, net.minecraft.core.Vec3i(1, 1, 1)),
            )
            StructurePersistence.writeStructureAtomic(captured.tag, file)
            file.exists() shouldBe true

            val tag = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap())
            val target = BlockPos(256, 70, 256)
            val placed = StructurePersistence.placeStructureTagCentered(
                tag, level, target, 16, level.minY, level.maxY, 70,
            )

            placed.shouldNotBeNull()
            placed.size shouldBe net.minecraft.core.Vec3i(1, 1, 1)
            level.getBlockState(placed.origin).block shouldBe Blocks.REDSTONE_BLOCK
        }
    }
})
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cmd.exe /c "gradlew.bat :26.2:runGameTest"`
Expected: FAIL — `placeStructureTagCentered` is an unresolved reference.

- [ ] **Step 3: Write minimal implementation**

Replace the body of `placeStructureCentered` with a read-then-delegate wrapper, and move the existing
logic into the new function. The `try/catch` and its logging move with the body; the file-not-found
check and the read stay in the wrapper, because only the wrapper has a file.

```kotlin
    /**
     * Place [file]'s structure centered in the region. Thin wrapper over
     * [placeStructureTagCentered]: the only thing it adds is reading the tag off disk.
     */
    fun placeStructureCentered(
        file: Path, level: ServerLevel, regionOrigin: BlockPos,
        regionSizeXZ: Int, regionMinY: Int, regionMaxY: Int, yBase: Int,
    ): PlacedBox? {
        if (!file.exists()) {
            LOGGER.warn("[StructurePersistence#placeCentered] file '{}' not found", file)
            return null
        }
        val nbt = try {
            NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap())
        } catch (e: Exception) {
            LOGGER.error("[StructurePersistence#placeCentered] read '{}': {}", file, e.message)
            return null
        }
        return placeStructureTagCentered(nbt, level, regionOrigin, regionSizeXZ, regionMinY, regionMaxY, yBase)
    }

    /**
     * Place an already-read structure [nbt] centered in the region.
     *
     * Split out of [placeStructureCentered] for the Local History restore, which holds a tag read
     * from a history blob and has no file to point at. Spooling that tag to a temp file just to read
     * it back would add an IO round trip and a failure mode for nothing.
     */
    fun placeStructureTagCentered(
        nbt: CompoundTag, level: ServerLevel, regionOrigin: BlockPos,
        regionSizeXZ: Int, regionMinY: Int, regionMaxY: Int, yBase: Int,
    ): PlacedBox? {
        return try {
            val blockGetter: HolderGetter<Block> = level.registryAccess().lookupOrThrow(Registries.BLOCK)
            val template = StructureTemplate()
            template.load(blockGetter, nbt)
            val size = template.size
            val regionHeight = regionMaxY - regionMinY + 1
            val origin = BlockPos(
                centeredStart(regionOrigin.x, regionSizeXZ, size.x),
                anchorY(size.y, yBase, regionMinY, regionHeight),
                centeredStart(regionOrigin.z, regionSizeXZ, size.z),
            )
            template.placeInWorld(level, origin, origin, StructurePlaceSettings(), level.random, 2)
            LOGGER.debug("[StructurePersistence#placeTagCentered] placed ({}) at {}", size, origin)
            PlacedBox(origin, size)
        } catch (e: Exception) {
            LOGGER.error("[StructurePersistence#placeTagCentered] load: {}", e.message)
            null
        }
    }
```

Add `import net.minecraft.nbt.CompoundTag` if the file does not already have it.

- [ ] **Step 4: Run test to verify it passes**

Run: `cmd.exe /c "gradlew.bat :26.2:runGameTest"`
Expected: PASS. Existing structure specs (`StructureAutoSaveSpec`, `EditorStructureNetworkSpec`) must
also still pass — the wrapper's behaviour is unchanged.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/breadmoirai/garnet/structure/StructurePersistence.kt \
        src/gametest/kotlin/com/breadmoirai/garnet/test/editor/StructureRestoreSpec.kt
git commit -m "refactor(structure): split placeStructureCentered into a tag-taking core"
```

---

### Task 3: The restore sequence

**Files:**
- Create: `src/main/kotlin/com/breadmoirai/garnet/editor/history/StructureRestoreOps.kt`
- Test: `src/gametest/kotlin/com/breadmoirai/garnet/test/editor/StructureRestoreSpec.kt` (extend)

**Interfaces:**
- Consumes: `LocalHistoryStore.REASON_RESTORE` (Task 1), `StructurePersistence.placeStructureTagCentered` (Task 2), and the existing `EditorHandlerSupport.commitDirtyUnder(server, subpath): String?`, `EditorDimRegistry.placedBoxOf/setPlacedBox`, `StructureCommit.commit(server, subpath, reason): CommitOutcome`.
- Produces:
  ```kotlin
  sealed interface RestoreOutcome {
      data class Restored(val subpath: String, val fromTimestampMillis: Long, val toTimestampMillis: Long) : RestoreOutcome
      data class Refused(val reason: String) : RestoreOutcome
  }
  object StructureRestoreOps {
      fun restore(server: MinecraftServer, subpath: String, timestampMillis: Long): RestoreOutcome
  }
  ```
  Note `restore` takes **no `ServerPlayer`** — it reports through its return value so undo/redo and
  the packet handler can each phrase their own message. `fromTimestampMillis` is the timestamp of the
  revision that was newest *before* the restore, which is what an undo aims back at.

- [ ] **Step 1: Write the failing tests**

Append to `StructureRestoreSpec.kt`. These are the behavioural core of the feature; write all of them
before implementing.

```kotlin
    test("restoring an older revision puts it in the world and on disk") {
        withEditorServer("restore-basic") { server, player, root ->
            val (subpath, first) = placeAndEdit(server, player, root)   // helper below
            // A second edit, so `first` is genuinely older than the newest revision.
            editPlacedStructure(server, subpath, Blocks.GOLD_BLOCK)
            StructureCommit.commit(server, subpath, LocalHistoryStore.REASON_MANUAL)

            val outcome = StructureRestoreOps.restore(server, subpath, first.timestampMillis)

            outcome.shouldBeInstanceOf<RestoreOutcome.Restored>()
            // World matches the restored revision.
            val box = EditorDimRegistry.of(server).placedBoxOf(subpath).shouldNotBeNull()
            server.overworld().getBlockState(box.origin).block shouldBe Blocks.REDSTONE_BLOCK
            // Disk matches it too.
            val file = root.resolve(subpath)
            val onDisk = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap())
            val restoredTag = LocalHistoryStore.readTag(file, first).shouldNotBeNull()
            StructurePersistence.structuresDiffer(onDisk, restoredTag).shouldBeFalse()
        }
    }

    test("a restore banks a restore revision carrying a real block count") {
        withEditorServer("restore-banks") { server, player, root ->
            val (subpath, first) = placeAndEdit(server, player, root)
            editPlacedStructure(server, subpath, Blocks.GOLD_BLOCK)
            StructureCommit.commit(server, subpath, LocalHistoryStore.REASON_MANUAL)

            StructureRestoreOps.restore(server, subpath, first.timestampMillis)

            val newest = LocalHistoryStore.revisions(root.resolve(subpath)).last()
            newest.reason shouldBe LocalHistoryStore.REASON_RESTORE
            // Unlike `placed`/`external`/`pre-delete`, a restore is committed by scanning the world,
            // so it carries a real count.
            newest.blockCount shouldBeGreaterThan 0
        }
    }

    test("a restore banks no spurious external revision") {
        withEditorServer("restore-no-external") { server, player, root ->
            val (subpath, first) = placeAndEdit(server, player, root)
            editPlacedStructure(server, subpath, Blocks.GOLD_BLOCK)
            StructureCommit.commit(server, subpath, LocalHistoryStore.REASON_MANUAL)

            StructureRestoreOps.restore(server, subpath, first.timestampMillis)

            LocalHistoryStore.revisions(root.resolve(subpath))
                .none { it.reason == LocalHistoryStore.REASON_EXTERNAL }
                .shouldBeTrue()
        }
    }

    test("restoring a smaller revision leaves no blocks from the larger footprint") {
        withEditorServer("restore-shrink") { server, player, root ->
            val (subpath, small) = placeAndEdit(server, player, root)   // 1x1x1 redstone
            // Grow it: add a block 3 away, so the committed structure is 4 wide.
            val box = EditorDimRegistry.of(server).placedBoxOf(subpath).shouldNotBeNull()
            val far = box.origin.offset(3, 0, 0)
            server.overworld().setBlockAndUpdate(far, Blocks.GOLD_BLOCK.defaultBlockState())
            StructureAutoSave.of(server).markDirty(subpath, PlacedBox(far, Vec3i(1, 1, 1)))
            StructureCommit.commit(server, subpath, LocalHistoryStore.REASON_MANUAL)
            server.overworld().getBlockState(far).block shouldBe Blocks.GOLD_BLOCK

            StructureRestoreOps.restore(server, subpath, small.timestampMillis)

            // The old footprint must be cleared, or the next commit would capture the stray block
            // straight back into the "restored" structure.
            server.overworld().getBlockState(far).block shouldBe Blocks.AIR
        }
    }

    test("restoring refuses when the structure is not placed") {
        withEditorServer("restore-unplaced") { server, player, root ->
            val (subpath, first) = placeAndEdit(server, player, root)
            EditorDimRegistry.of(server).unplaceStructure(subpath)

            val outcome = StructureRestoreOps.restore(server, subpath, first.timestampMillis)

            outcome.shouldBeInstanceOf<RestoreOutcome.Refused>()
            outcome.reason shouldContain "place the structure"
        }
    }

    test("restoring refuses an unknown timestamp") {
        withEditorServer("restore-unknown") { server, player, root ->
            val (subpath, _) = placeAndEdit(server, player, root)

            val outcome = StructureRestoreOps.restore(server, subpath, 1L)

            outcome.shouldBeInstanceOf<RestoreOutcome.Refused>()
            outcome.reason shouldContain "no such revision"
        }
    }

    test("restoring refuses the newest revision") {
        withEditorServer("restore-newest") { server, player, root ->
            val (subpath, _) = placeAndEdit(server, player, root)
            val newest = LocalHistoryStore.revisions(root.resolve(subpath)).last()

            val outcome = StructureRestoreOps.restore(server, subpath, newest.timestampMillis)

            outcome.shouldBeInstanceOf<RestoreOutcome.Refused>()
            outcome.reason shouldContain "already the current"
        }
    }

    test("restoring refuses a raw (non-structure) revision") {
        withEditorServer("restore-raw") { server, player, root ->
            val (subpath, _) = placeAndEdit(server, player, root)
            val file = root.resolve(subpath)
            // A raw revision is what the delete path banks for a non-structure file. Size cannot
            // distinguish it from a typed revision — only the garnetRaw marker can.
            val raw = LocalHistoryStore.writeRawRevision(
                file, "not nbt".toByteArray(), LocalHistoryStore.REASON_PRE_DELETE,
            ).shouldNotBeNull()
            // Bank one more typed revision so the raw one is not the newest (which refuses anyway).
            editPlacedStructure(server, subpath, Blocks.GOLD_BLOCK)
            StructureCommit.commit(server, subpath, LocalHistoryStore.REASON_MANUAL)

            val outcome = StructureRestoreOps.restore(server, subpath, raw.timestampMillis)

            outcome.shouldBeInstanceOf<RestoreOutcome.Refused>()
            outcome.reason shouldContain "not a structure snapshot"
        }
    }
```

Add these two helpers at the top of the spec's lambda body:

```kotlin
    /**
     * Place a fresh 1x1x1 redstone structure and commit it, returning its subpath and the revision
     * that commit banked.
     */
    suspend fun placeAndEdit(
        server: MinecraftServer, player: ServerPlayer, root: Path,
    ): Pair<String, Revision> {
        val subpath = "probe.nbt"
        EditorNewStructure.create(root, "probe")
        EditorStructureHandlers.handlePlaceStructure(server, player, PlaceStructureC2S(subpath))
        editPlacedStructure(server, subpath, Blocks.REDSTONE_BLOCK)
        StructureCommit.commit(server, subpath, LocalHistoryStore.REASON_MANUAL)
        return subpath to LocalHistoryStore.revisions(root.resolve(subpath)).last()
    }

    /** Set one block at the placed structure's origin and mark the subpath dirty. */
    fun editPlacedStructure(server: MinecraftServer, subpath: String, block: Block) {
        val box = EditorDimRegistry.of(server).placedBoxOf(subpath)!!
        server.overworld().setBlockAndUpdate(box.origin, block.defaultBlockState())
        StructureAutoSave.of(server).markDirty(subpath, PlacedBox(box.origin, Vec3i(1, 1, 1)))
    }
```

Check `StructureAutoSave`'s actual dirty-marking method name before running — if it is not
`markDirty(subpath, box)`, use whatever `StructureEditWatcher` calls and adjust every use above.
Likewise confirm `StructurePersistence.structuresDiffer` is accessible from the gametest source set;
if it is private, compare `NbtIo` tags with `==` on the normalized tags instead.

- [ ] **Step 2: Run tests to verify they fail**

Run: `cmd.exe /c "gradlew.bat :26.2:runGameTest"`
Expected: FAIL — `StructureRestoreOps` and `RestoreOutcome` are unresolved references.

- [ ] **Step 3: Write the implementation**

Create `src/main/kotlin/com/breadmoirai/garnet/editor/history/StructureRestoreOps.kt`:

```kotlin
package com.breadmoirai.garnet.editor.history

import com.breadmoirai.garnet.config.SharedSettings
import com.breadmoirai.garnet.editor.network.EditorHandlerSupport.commitDirtyUnder
import com.breadmoirai.garnet.editor.structure.CommitOutcome
import com.breadmoirai.garnet.editor.structure.StructureCommit
import com.breadmoirai.garnet.editor.world.EditorDimRegistry
import com.breadmoirai.garnet.editor.world.EditorRootResolver
import com.breadmoirai.garnet.history.LocalHistoryStore
import com.breadmoirai.garnet.structure.StructurePersistence
import net.minecraft.server.MinecraftServer
import org.slf4j.LoggerFactory

private val LOGGER = LoggerFactory.getLogger("Garnet")

/** What a restore attempt produced. Callers phrase their own player-facing message. */
sealed interface RestoreOutcome {
    /**
     * [fromTimestampMillis] is the revision that was newest BEFORE the restore — what an undo of
     * this restore aims back at. [toTimestampMillis] is the revision that was restored.
     */
    data class Restored(
        val subpath: String,
        val fromTimestampMillis: Long,
        val toTimestampMillis: Long,
    ) : RestoreOutcome

    data class Refused(val reason: String) : RestoreOutcome
}

/**
 * Moves a placed structure's world copy and `.nbt` back to a banked revision.
 *
 * **Only ever operates on a structure that is currently placed.** That invariant is what keeps this
 * a single code path: there is always a footprint to clear and a [StructureCommit] to write through,
 * so nothing here writes a `.nbt` directly.
 *
 * Player-independent by design: it returns a [RestoreOutcome] instead of sending packets, so the
 * undo/redo replay and the packet handler can each phrase their own message, and so it is testable
 * without a network round trip. See docs/superpowers/specs/2026-08-15-structure-local-history-panel-design.md.
 */
object StructureRestoreOps {

    fun restore(server: MinecraftServer, subpath: String, timestampMillis: Long): RestoreOutcome {
        if (!subpath.endsWith(".nbt")) return RestoreOutcome.Refused("not a structure file: $subpath")
        val root = EditorRootResolver.rootFor(server)
            ?: return RestoreOutcome.Refused("project-root not configured")
        val file = root.resolveSubpath(subpath)
            ?: return RestoreOutcome.Refused("subpath not found or escapes root: $subpath")

        val registry = EditorDimRegistry.of(server)
        // The invariant. Not a fallback to writing the file: an unplaced restore has no world copy
        // to reconcile, and allowing it would fork every step below.
        val placed = registry.placedBoxOf(subpath)
            ?: return RestoreOutcome.Refused("place the structure before restoring: $subpath")

        val revisions = LocalHistoryStore.revisions(file)
        val target = revisions.firstOrNull { it.timestampMillis == timestampMillis }
            ?: return RestoreOutcome.Refused("no such revision for $subpath")
        val newest = revisions.last()
        if (target.timestampMillis == newest.timestampMillis) {
            // Revisions are POST-commit: the newest one IS what is on disk, so restoring it is a
            // no-op. The panel renders that row inert; this is the server-side half of the rule.
            return RestoreOutcome.Refused("that revision is already the current content")
        }
        // A raw revision is a `pre-delete` bank of something that is not a structure. Detect it by
        // the garnetRaw marker, NEVER by size: a real .nbt that parses but is not a template records
        // zero sizes too, so size cannot tell the two apart.
        if (LocalHistoryStore.readRawBytes(file, target) != null) {
            return RestoreOutcome.Refused("not a structure snapshot: ${target.reason} revision")
        }
        val tag = LocalHistoryStore.readTag(file, target)
            ?: return RestoreOutcome.Refused("revision blob is missing or unreadable")

        // Quiesce, and ABORT if it fails. Deliberately unlike deleteSubtree's best-effort quiesce:
        // a delete is a request to destroy that content anyway, but here a failed quiesce followed
        // by the re-place below would silently eat live edits nobody asked to lose.
        commitDirtyUnder(server, subpath)?.let {
            return RestoreOutcome.Refused("could not save pending edits before restoring: $it")
        }

        // Re-read AFTER the quiesce: that commit banked a revision, and it is the one an undo of
        // this restore has to aim back at.
        val fromTimestamp = LocalHistoryStore.revisions(file).last().timestampMillis

        val level = registry.projectLevel()
        val origin = registry.getOrAssignStructureRegion(subpath)
        val width = SharedSettings.structureRegionChunks * 16
        // Clear the OLD footprint before placing. A restored structure may be smaller than what it
        // replaces, and the new box is the only thing bounding the commit's scan below — clearing by
        // the new box would strand the old footprint's blocks where the next commit captures them
        // straight back in.
        StructurePersistence.clearBounds(level, placed.origin, placed.size)
        val newBox = StructurePersistence.placeStructureTagCentered(
            tag, level, origin, width, level.minY, level.maxY, SharedSettings.projectGridYBase,
        ) ?: return RestoreOutcome.Refused("could not place revision content for $subpath")
        registry.setPlacedBox(subpath, newBox)
        // No teleport, unlike placeStructureFrom: the player is already standing in this region and
        // being flung to the new roof height mid-restore is disorienting.

        return when (val outcome = StructureCommit.commit(server, subpath, LocalHistoryStore.REASON_RESTORE)) {
            is CommitOutcome.Committed, is CommitOutcome.NoChange -> {
                val toTimestamp = target.timestampMillis
                LOGGER.debug("[restore] {} -> {}", subpath, toTimestamp)
                RestoreOutcome.Restored(subpath, fromTimestamp, toTimestamp)
            }
            is CommitOutcome.Failed ->
                // The world holds the restored content; disk does not. Retrying is the recovery.
                RestoreOutcome.Refused("restore placed but could not be saved: ${outcome.reason}")
            is CommitOutcome.NotApplicable ->
                RestoreOutcome.Refused("restore placed but could not be saved: $subpath is not committable")
        }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cmd.exe /c "gradlew.bat :26.2:runGameTest"`
Expected: PASS, all eight new cases.

- [ ] **Step 5: Add the failed-quiesce case**

This one needs the quiesce to fail, which means an unresolvable subpath while dirty. Append:

```kotlin
    test("a failed quiesce aborts the restore without touching the world") {
        withEditorServer("restore-quiesce-fail") { server, player, root ->
            val (subpath, first) = placeAndEdit(server, player, root)
            editPlacedStructure(server, subpath, Blocks.GOLD_BLOCK)
            StructureCommit.commit(server, subpath, LocalHistoryStore.REASON_MANUAL)
            // Dirty it again, then make the file unresolvable so commitDirtyUnder cannot land it.
            editPlacedStructure(server, subpath, Blocks.DIAMOND_BLOCK)
            root.resolve(subpath).deleteExisting()

            val outcome = StructureRestoreOps.restore(server, subpath, first.timestampMillis)

            outcome.shouldBeInstanceOf<RestoreOutcome.Refused>()
            outcome.reason shouldContain "pending edits"
            // The world still holds the un-quiesced edit — nothing was cleared or re-placed.
            val box = EditorDimRegistry.of(server).placedBoxOf(subpath).shouldNotBeNull()
            server.overworld().getBlockState(box.origin).block shouldBe Blocks.DIAMOND_BLOCK
        }
    }
```

Run: `cmd.exe /c "gradlew.bat :26.2:runGameTest"`
Expected: PASS. If `commitDirtyUnder` returns null for a deleted file (because `commit` cleared the
flag rather than keeping it), assert on whatever refusal actually occurs and note it in the test — do
not weaken the production abort to make a test pass.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/breadmoirai/garnet/editor/history/StructureRestoreOps.kt \
        src/gametest/kotlin/com/breadmoirai/garnet/test/editor/StructureRestoreSpec.kt
git commit -m "feat(history): restore a structure revision into the world and the .nbt"
```

---

### Task 4: Undo and redo of a restore

**Files:**
- Modify: `src/main/kotlin/com/breadmoirai/garnet/editor/undo/EditorUndoCommand.kt`
- Modify: `src/main/kotlin/com/breadmoirai/garnet/editor/undo/EditorUndoOps.kt` (`applyInverse` and `reapply` `when` branches)
- Test: `src/gametest/kotlin/com/breadmoirai/garnet/test/editor/EditorUndoNetworkSpec.kt`

**Interfaces:**
- Consumes: `StructureRestoreOps.restore(server, subpath, timestampMillis): RestoreOutcome` (Task 3).
- Produces: `EditorUndoCommand.RestoreRevision(subpath: String, fromTimestampMillis: Long, toTimestampMillis: Long)`.

- [ ] **Step 1: Write the failing tests**

Append to `EditorUndoNetworkSpec.kt`. Reuse that file's existing `withServer` helper (it wraps
`withEditorServer`), and the `placeAndEdit`/`editPlacedStructure` shapes from Task 3 — copy them in
rather than sharing, since the two specs set up different roots.

```kotlin
    test("undoing a restore returns the structure to its pre-restore content") {
        withServer { server, player, root ->
            EditorUndoStack.clear(player.uuid)
            val subpath = "probe.nbt"
            EditorNewStructure.create(root, "probe")
            EditorStructureHandlers.handlePlaceStructure(server, player, PlaceStructureC2S(subpath))
            val box = EditorDimRegistry.of(server).placedBoxOf(subpath).shouldNotBeNull()

            fun edit(block: Block) {
                server.overworld().setBlockAndUpdate(box.origin, block.defaultBlockState())
                StructureAutoSave.of(server).markDirty(subpath, PlacedBox(box.origin, Vec3i(1, 1, 1)))
                StructureCommit.commit(server, subpath, LocalHistoryStore.REASON_MANUAL)
            }
            edit(Blocks.REDSTONE_BLOCK)
            val redstoneRevision = LocalHistoryStore.revisions(root.resolve(subpath)).last()
            edit(Blocks.GOLD_BLOCK)

            EditorStructureHandlers.handleRestoreRevision(
                server, player, RestoreRevisionC2S(subpath, redstoneRevision.timestampMillis),
            )
            // The restore landed: redstone is back.
            val afterRestore = EditorDimRegistry.of(server).placedBoxOf(subpath).shouldNotBeNull()
            server.overworld().getBlockState(afterRestore.origin).block shouldBe Blocks.REDSTONE_BLOCK

            EditorUndoOps.undo(server, player)

            // Undo aims at the pre-restore state, which was gold.
            val afterUndo = EditorDimRegistry.of(server).placedBoxOf(subpath).shouldNotBeNull()
            server.overworld().getBlockState(afterUndo.origin).block shouldBe Blocks.GOLD_BLOCK
        }
    }

    test("redoing a restore re-applies it") {
        withServer { server, player, root ->
            EditorUndoStack.clear(player.uuid)
            // ... identical setup to the test above, through the undo ...
            // (repeat the setup verbatim; the specs run independently and share no fixtures)
            EditorUndoOps.redo(server, player)

            val box = EditorDimRegistry.of(server).placedBoxOf("probe.nbt").shouldNotBeNull()
            server.overworld().getBlockState(box.origin).block shouldBe Blocks.REDSTONE_BLOCK
        }
    }

    test("undoing a restore whose target was pruned refuses and keeps the entry") {
        withServer { server, player, root ->
            EditorUndoStack.clear(player.uuid)
            // Push a command naming a timestamp that does not exist in the index.
            EditorUndoStack.push(player.uuid, EditorUndoCommand.RestoreRevision("probe.nbt", 1L, 2L))

            EditorUndoOps.undo(server, player)

            // Refusals never pop — the player can retry after resolving the conflict.
            EditorUndoStack.peekUndo(player.uuid).shouldBeInstanceOf<EditorUndoCommand.RestoreRevision>()
            drainPayloads(player).filterIsInstance<EditorErrorS2C>().shouldNotBeEmpty()
        }
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cmd.exe /c "gradlew.bat :26.2:runGameTest"`
Expected: FAIL — `EditorUndoCommand.RestoreRevision` and `handleRestoreRevision` unresolved.
(`handleRestoreRevision` and `RestoreRevisionC2S` arrive in Tasks 5–6; if you are executing tasks
strictly in order, temporarily drive the restore through `StructureRestoreOps.restore` plus a manual
`EditorUndoStack.push`, and switch to the handler in Task 6.)

- [ ] **Step 3: Add the command**

In `EditorUndoCommand.kt`, after `Delete`:

```kotlin
    /**
     * A Local History restore. Reversible without carrying any content: the pre-restore state was
     * itself banked by the restore's own quiesce, so undo is just *the same operation aimed at
     * [fromTimestampMillis]*, and redo aims back at [toTimestampMillis].
     */
    data class RestoreRevision(
        val subpath: String,
        val fromTimestampMillis: Long,
        val toTimestampMillis: Long,
    ) : EditorUndoCommand {
        override val label get() = "restore '$subpath'"
    }
```

- [ ] **Step 4: Add the replay branches**

In `EditorUndoOps.applyInverse`'s `when (command)`:

```kotlin
        is EditorUndoCommand.RestoreRevision -> {
            // Undo = restore the OTHER revision. The pre-restore content is a real banked revision
            // (the restore's quiesce guaranteed it), so no content rides on the command.
            when (val outcome = StructureRestoreOps.restore(server, command.subpath, command.fromTimestampMillis)) {
                is RestoreOutcome.Refused -> Inverted.Refused(outcome.reason)
                is RestoreOutcome.Restored -> Inverted.Applied(
                    // Seat a command whose `from` is where THIS undo just came from, so a later
                    // redo/undo pair keeps pointing at revisions that actually exist.
                    EditorUndoCommand.RestoreRevision(
                        command.subpath,
                        fromTimestampMillis = outcome.fromTimestampMillis,
                        toTimestampMillis = command.toTimestampMillis,
                    ),
                )
            }
        }
```

And in `reapply`'s `when (command)`:

```kotlin
        is EditorUndoCommand.RestoreRevision -> {
            when (val outcome = StructureRestoreOps.restore(server, command.subpath, command.toTimestampMillis)) {
                is RestoreOutcome.Refused -> Inverted.Refused(outcome.reason)
                is RestoreOutcome.Restored -> Inverted.Applied(
                    EditorUndoCommand.RestoreRevision(
                        command.subpath,
                        fromTimestampMillis = outcome.fromTimestampMillis,
                        toTimestampMillis = command.toTimestampMillis,
                    ),
                )
            }
        }
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `cmd.exe /c "gradlew.bat :26.2:runGameTest"`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/breadmoirai/garnet/editor/undo/ \
        src/gametest/kotlin/com/breadmoirai/garnet/test/editor/EditorUndoNetworkSpec.kt
git commit -m "feat(undo): make a local-history restore undoable"
```

---

### Task 5: The three payloads

**Files:**
- Modify: `src/main/kotlin/com/breadmoirai/garnet/editor/network/EditorPackets.kt`
- Modify: `src/main/kotlin/com/breadmoirai/garnet/editor/network/EditorNetworkRegistry.kt`
- Test: `src/gametest/kotlin/com/breadmoirai/garnet/test/editor/EditorNetworkRegistrySpec.kt`

**Interfaces:**
- Consumes: nothing.
- Produces:
  ```kotlin
  data class RevisionEntry(
      val timestampMillis: Long, val sizeX: Int, val sizeY: Int, val sizeZ: Int,
      val blockCount: Int, val reason: String,
  )
  data class WatchStructureHistoryC2S(val subpath: String)           // "" == stop watching
  data class StructureHistoryS2C(val subpath: String, val revisions: List<RevisionEntry>)
  data class RestoreRevisionC2S(val subpath: String, val timestampMillis: Long)
  ```

- [ ] **Step 1: Write the failing test**

Append to `EditorNetworkRegistrySpec.kt`, matching how it asserts on the existing registrations
(open it and copy the established assertion style — it likely checks `PayloadTypeRegistry`
membership or that `register()` runs without throwing).

```kotlin
    test("the local-history payloads are registered") {
        EditorNetworkRegistry.register()

        // Same assertion shape the existing cases use for PlaceStructureC2S et al.
        WatchStructureHistoryC2S.TYPE.id().namespace shouldBe "garnet"
        StructureHistoryS2C.TYPE.id().namespace shouldBe "garnet"
        RestoreRevisionC2S.TYPE.id().namespace shouldBe "garnet"
    }

    test("a StructureHistoryS2C round-trips its revision list") {
        val payload = StructureHistoryS2C("clock.nbt", listOf(
            RevisionEntry(1_000L, 1, 2, 3, 4, "autosave"),
            RevisionEntry(2_000L, 5, 6, 7, 8, "restore"),
        ))
        val buf = io.netty.buffer.Unpooled.buffer()
        StructureHistoryS2C.STREAM_CODEC.encode(buf, payload)

        StructureHistoryS2C.STREAM_CODEC.decode(buf) shouldBe payload
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cmd.exe /c "gradlew.bat :26.2:runGameTest"`
Expected: FAIL — unresolved references.

- [ ] **Step 3: Add the payloads**

In `EditorPackets.kt`, in a new `// === Local history ===` section after the undo section:

```kotlin
// === Local history ===

/**
 * One banked revision, as the client sees it.
 *
 * Deliberately `Revision` minus its `file` field: the blob filename is a server-side filesystem
 * detail, and a client that selects a revision by timestamp rather than by name cannot ask the
 * server to read an arbitrary path.
 */
data class RevisionEntry(
    val timestampMillis: Long,
    val sizeX: Int, val sizeY: Int, val sizeZ: Int,
    val blockCount: Int,
    val reason: String,
)

val REVISION_ENTRY_STREAM_CODEC: StreamCodec<ByteBuf, RevisionEntry> = StreamCodec.composite(
    ByteBufCodecs.VAR_LONG, RevisionEntry::timestampMillis,
    ByteBufCodecs.VAR_INT, RevisionEntry::sizeX,
    ByteBufCodecs.VAR_INT, RevisionEntry::sizeY,
    ByteBufCodecs.VAR_INT, RevisionEntry::sizeZ,
    ByteBufCodecs.VAR_INT, RevisionEntry::blockCount,
    ByteBufCodecs.STRING_UTF8, RevisionEntry::reason,
    ::RevisionEntry,
)

/**
 * "I am looking at this structure's history; send it and keep me posted."
 *
 * An EMPTY [subpath] means "no longer looking". One packet rather than a watch/unwatch pair because
 * the server's state is a single entry per player, so a set-or-clear write matches it exactly and
 * there is no ordering hazard where an unwatch for the old subpath races a watch for the new one.
 */
data class WatchStructureHistoryC2S(val subpath: String) : CustomPacketPayload {
    companion object {
        val TYPE = CustomPacketPayload.Type<WatchStructureHistoryC2S>(id("watch_history"))
        val STREAM_CODEC: StreamCodec<ByteBuf, WatchStructureHistoryC2S> = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, WatchStructureHistoryC2S::subpath,
            ::WatchStructureHistoryC2S,
        )
    }
    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE
}

/**
 * A structure's revisions, oldest first — as [com.breadmoirai.garnet.history.LocalHistoryStore.revisions]
 * returns them. The panel reverses for display; keeping the store's own order on the wire means a
 * future consumer does not inherit a presentation decision.
 *
 * Sent both as the reply to [WatchStructureHistoryC2S] and as an unsolicited push after any commit
 * or restore for a watched subpath.
 */
data class StructureHistoryS2C(
    val subpath: String,
    val revisions: List<RevisionEntry>,
) : CustomPacketPayload {
    companion object {
        val TYPE = CustomPacketPayload.Type<StructureHistoryS2C>(id("structure_history"))
        val STREAM_CODEC: StreamCodec<ByteBuf, StructureHistoryS2C> = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, StructureHistoryS2C::subpath,
            REVISION_ENTRY_STREAM_CODEC.apply(ByteBufCodecs.list()), StructureHistoryS2C::revisions,
            ::StructureHistoryS2C,
        )
    }
    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE
}

/**
 * Restore one revision, identified by TIMESTAMP rather than list index.
 *
 * An index is only meaningful against the list the client happened to be holding: an autosave
 * landing between render and click would shift it silently, restoring the revision next to the one
 * that was clicked with nothing able to detect it. The server refuses an unknown timestamp rather
 * than guessing at the nearest.
 */
data class RestoreRevisionC2S(
    val subpath: String,
    val timestampMillis: Long,
) : CustomPacketPayload {
    companion object {
        val TYPE = CustomPacketPayload.Type<RestoreRevisionC2S>(id("restore_revision"))
        val STREAM_CODEC: StreamCodec<ByteBuf, RestoreRevisionC2S> = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, RestoreRevisionC2S::subpath,
            ByteBufCodecs.VAR_LONG, RestoreRevisionC2S::timestampMillis,
            ::RestoreRevisionC2S,
        )
    }
    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE
}
```

If `REVISION_ENTRY_STREAM_CODEC.apply(ByteBufCodecs.list())` does not compile against this MC
version, follow whatever list-codec pattern `EditorSaveReportS2C` or `EditorTreeSnapshotS2C` already
uses in this same file — those are the in-repo precedent.

- [ ] **Step 4: Register them**

In `EditorNetworkRegistry.register()`, beside the existing registrations:

```kotlin
        PayloadTypeRegistry.serverboundPlay().register(WatchStructureHistoryC2S.TYPE, WatchStructureHistoryC2S.STREAM_CODEC)
        PayloadTypeRegistry.serverboundPlay().register(RestoreRevisionC2S.TYPE, RestoreRevisionC2S.STREAM_CODEC)
        PayloadTypeRegistry.clientboundPlay().register(StructureHistoryS2C.TYPE, StructureHistoryS2C.STREAM_CODEC)
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `cmd.exe /c "gradlew.bat :26.2:runGameTest"`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/breadmoirai/garnet/editor/network/EditorPackets.kt \
        src/main/kotlin/com/breadmoirai/garnet/editor/network/EditorNetworkRegistry.kt \
        src/gametest/kotlin/com/breadmoirai/garnet/test/editor/EditorNetworkRegistrySpec.kt
git commit -m "feat(net): add the local-history watch, push and restore payloads"
```

---

### Task 6: Watchers, handlers, and the push

**Files:**
- Create: `src/main/kotlin/com/breadmoirai/garnet/editor/history/HistoryWatchers.kt`
- Modify: `src/main/kotlin/com/breadmoirai/garnet/editor/network/EditorStructureHandlers.kt`
- Modify: `src/main/kotlin/com/breadmoirai/garnet/editor/network/EditorNetworkRegistry.kt`
- Modify: `src/main/kotlin/com/breadmoirai/garnet/editor/structure/StructureCommit.kt` (in `broadcast`, ~line 334)
- Test: `src/gametest/kotlin/com/breadmoirai/garnet/test/editor/StructureRestoreSpec.kt`

**Interfaces:**
- Consumes: `StructureRestoreOps.restore` (Task 3), the three payloads (Task 5), `EditorHandlerSupport.fail/sendUndoState`, `EditorUndoStack.push`.
- Produces:
  ```kotlin
  object HistoryWatchers {
      fun watch(playerId: UUID, subpath: String)       // "" clears
      fun watchedBy(playerId: UUID): String?
      fun clear(playerId: UUID)
      fun pushTo(server: MinecraftServer, player: ServerPlayer, subpath: String)
      fun pushAll(server: MinecraftServer, subpath: String)
  }
  // on EditorStructureHandlers:
  fun handleWatchHistory(server: MinecraftServer, player: ServerPlayer, payload: WatchStructureHistoryC2S)
  fun handleRestoreRevision(server: MinecraftServer, player: ServerPlayer, payload: RestoreRevisionC2S)
  ```

- [ ] **Step 1: Write the failing tests**

Append to `StructureRestoreSpec.kt`:

```kotlin
    test("watching a structure replies with its revisions oldest first") {
        withEditorServer("watch-reply") { server, player, root ->
            val (subpath, first) = placeAndEdit(server, player, root)
            editPlacedStructure(server, subpath, Blocks.GOLD_BLOCK)
            StructureCommit.commit(server, subpath, LocalHistoryStore.REASON_MANUAL)
            drainPayloads(player)

            EditorStructureHandlers.handleWatchHistory(server, player, WatchStructureHistoryC2S(subpath))

            val sent = drainPayloads(player).filterIsInstance<StructureHistoryS2C>().last()
            sent.subpath shouldBe subpath
            sent.revisions.size shouldBeGreaterThan 1
            sent.revisions.first().timestampMillis shouldBe first.timestampMillis
            // Oldest first, as the store returns them.
            sent.revisions.zipWithNext().all { (a, b) -> a.timestampMillis <= b.timestampMillis }.shouldBeTrue()
        }
    }

    test("an empty subpath stops the watch") {
        withEditorServer("watch-clear") { server, player, root ->
            val (subpath, _) = placeAndEdit(server, player, root)
            EditorStructureHandlers.handleWatchHistory(server, player, WatchStructureHistoryC2S(subpath))

            EditorStructureHandlers.handleWatchHistory(server, player, WatchStructureHistoryC2S(""))

            HistoryWatchers.watchedBy(player.uuid).shouldBeNull()
        }
    }

    test("a commit pushes a refreshed list to a watcher") {
        withEditorServer("watch-push") { server, player, root ->
            val (subpath, _) = placeAndEdit(server, player, root)
            EditorStructureHandlers.handleWatchHistory(server, player, WatchStructureHistoryC2S(subpath))
            val before = drainPayloads(player).filterIsInstance<StructureHistoryS2C>().last().revisions.size

            editPlacedStructure(server, subpath, Blocks.GOLD_BLOCK)
            StructureCommit.commit(server, subpath, LocalHistoryStore.REASON_MANUAL)

            val after = drainPayloads(player).filterIsInstance<StructureHistoryS2C>().last()
            after.revisions.size shouldBe before + 1
        }
    }

    test("restoring through the handler pushes an undo entry and a refreshed list") {
        withEditorServer("restore-handler") { server, player, root ->
            EditorUndoStack.clear(player.uuid)
            val (subpath, first) = placeAndEdit(server, player, root)
            editPlacedStructure(server, subpath, Blocks.GOLD_BLOCK)
            StructureCommit.commit(server, subpath, LocalHistoryStore.REASON_MANUAL)
            EditorStructureHandlers.handleWatchHistory(server, player, WatchStructureHistoryC2S(subpath))
            drainPayloads(player)

            EditorStructureHandlers.handleRestoreRevision(
                server, player, RestoreRevisionC2S(subpath, first.timestampMillis),
            )

            EditorUndoStack.peekUndo(player.uuid)
                .shouldBeInstanceOf<EditorUndoCommand.RestoreRevision>()
            drainPayloads(player).filterIsInstance<StructureHistoryS2C>().shouldNotBeEmpty()
        }
    }

    test("a refused restore reports the reason and pushes no undo entry") {
        withEditorServer("restore-refused") { server, player, root ->
            EditorUndoStack.clear(player.uuid)
            val (subpath, _) = placeAndEdit(server, player, root)
            drainPayloads(player)

            EditorStructureHandlers.handleRestoreRevision(
                server, player, RestoreRevisionC2S(subpath, 1L),
            )

            drainPayloads(player).filterIsInstance<EditorErrorS2C>().shouldNotBeEmpty()
            EditorUndoStack.peekUndo(player.uuid).shouldBeNull()
        }
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cmd.exe /c "gradlew.bat :26.2:runGameTest"`
Expected: FAIL — `HistoryWatchers` and the two handlers are unresolved.

- [ ] **Step 3: Write HistoryWatchers**

Create `src/main/kotlin/com/breadmoirai/garnet/editor/history/HistoryWatchers.kt`:

```kotlin
package com.breadmoirai.garnet.editor.history

import com.breadmoirai.garnet.editor.network.RevisionEntry
import com.breadmoirai.garnet.editor.network.StructureHistoryS2C
import com.breadmoirai.garnet.editor.world.EditorRootResolver
import com.breadmoirai.garnet.history.LocalHistoryStore
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Which structure each player currently has open in the Local History panel, and the fan-out that
 * keeps those panels current.
 *
 * One entry per player, not a set: the panel shows exactly one structure at a time, so a
 * set-or-clear write matches the state precisely and no unwatch can race a watch for a new subpath.
 *
 * Entries are dropped on disconnect, so a rejoining player never inherits a stale watch.
 */
object HistoryWatchers {

    private val bySubpath = ConcurrentHashMap<UUID, String>()

    /** [subpath] `""` clears the watch. */
    fun watch(playerId: UUID, subpath: String) {
        if (subpath.isEmpty()) bySubpath.remove(playerId) else bySubpath[playerId] = subpath
    }

    fun watchedBy(playerId: UUID): String? = bySubpath[playerId]

    fun clear(playerId: UUID) { bySubpath.remove(playerId) }

    /**
     * Send [player] the current revision list for [subpath], oldest first.
     *
     * An unresolvable root or subpath sends an EMPTY list rather than nothing: the panel needs to
     * hear that a structure it was watching has gone (deleted, renamed, root repointed), and silence
     * would leave a stale list on screen indefinitely.
     */
    fun pushTo(server: MinecraftServer, player: ServerPlayer, subpath: String) {
        val file = EditorRootResolver.rootFor(server)?.resolveSubpath(subpath)
        val entries = if (file == null) emptyList() else LocalHistoryStore.revisions(file).map {
            RevisionEntry(it.timestampMillis, it.sizeX, it.sizeY, it.sizeZ, it.blockCount, it.reason)
        }
        // Unsolicited when called from the commit fan-out, so guard: an unknown play-phase payload
        // can disconnect a vanilla client on a dedicated server. See StructureCommit.broadcast.
        if (ServerPlayNetworking.canSend(player, StructureHistoryS2C.TYPE)) {
            ServerPlayNetworking.send(player, StructureHistoryS2C(subpath, entries))
        }
    }

    /** Push [subpath]'s list to every player watching it. */
    fun pushAll(server: MinecraftServer, subpath: String) {
        for (player in server.playerList.players) {
            if (bySubpath[player.uuid] == subpath) pushTo(server, player, subpath)
        }
    }
}
```

- [ ] **Step 4: Add the handlers**

In `EditorStructureHandlers`:

```kotlin
    fun handleWatchHistory(server: MinecraftServer, player: ServerPlayer, payload: WatchStructureHistoryC2S) {
        HistoryWatchers.watch(player.uuid, payload.subpath)
        // A reply to a C2S this player just sent, so no canSend guard is needed here — but pushTo
        // applies one anyway, harmlessly, since it is shared with the unsolicited fan-out.
        if (payload.subpath.isNotEmpty()) HistoryWatchers.pushTo(server, player, payload.subpath)
    }

    fun handleRestoreRevision(server: MinecraftServer, player: ServerPlayer, payload: RestoreRevisionC2S) {
        when (val outcome = StructureRestoreOps.restore(server, payload.subpath, payload.timestampMillis)) {
            is RestoreOutcome.Refused -> {
                fail(player, "restore failed: ${outcome.reason}")
                // Push anyway: the most likely refusal is a revision pruned between render and
                // click, and a refreshed list is what corrects the panel.
                HistoryWatchers.pushTo(server, player, payload.subpath)
            }
            is RestoreOutcome.Restored -> {
                EditorUndoStack.push(player.uuid, EditorUndoCommand.RestoreRevision(
                    outcome.subpath, outcome.fromTimestampMillis, outcome.toTimestampMillis,
                ))
                ServerPlayNetworking.send(player, StructureResultS2C(
                    payload.subpath, 0, 0, 0, "restored ${payload.subpath}",
                ))
                HistoryWatchers.pushAll(server, payload.subpath)
                sendUndoState(player)
            }
        }
    }
```

Add the imports these need (`HistoryWatchers`, `StructureRestoreOps`, `RestoreOutcome`).

- [ ] **Step 5: Register the receivers and the commit push**

In `EditorNetworkRegistry.register()`:

```kotlin
        ServerPlayNetworking.registerGlobalReceiver(WatchStructureHistoryC2S.TYPE) { payload, ctx ->
            ctx.server().execute { EditorStructureHandlers.handleWatchHistory(ctx.server(), ctx.player(), payload) }
        }
        ServerPlayNetworking.registerGlobalReceiver(RestoreRevisionC2S.TYPE) { payload, ctx ->
            ctx.server().execute { EditorStructureHandlers.handleRestoreRevision(ctx.server(), ctx.player(), payload) }
        }
```

In `StructureCommit.broadcast`, after the existing player loop, add:

```kotlin
        // Anyone with this structure's Local History panel open just gained a revision.
        HistoryWatchers.pushAll(server, payload.subpath)
```

Find where per-player editor state is torn down on disconnect (grep for `EditorSession.clear` in a
disconnect handler) and add `HistoryWatchers.clear(player.uuid)` alongside it.

- [ ] **Step 6: Run tests to verify they pass**

Run: `cmd.exe /c "gradlew.bat :26.2:runGameTest"`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add src/main/kotlin/com/breadmoirai/garnet/editor/history/HistoryWatchers.kt \
        src/main/kotlin/com/breadmoirai/garnet/editor/network/ \
        src/main/kotlin/com/breadmoirai/garnet/editor/structure/StructureCommit.kt \
        src/gametest/kotlin/com/breadmoirai/garnet/test/editor/StructureRestoreSpec.kt
git commit -m "feat(net): serve and push structure history to watching players"
```

---

### Task 7: Client state — the open structure and the revision list

**Files:**
- Create: `src/client/kotlin/com/breadmoirai/garnet/editor/ui/OpenStructureState.kt`
- Create: `src/client/kotlin/com/breadmoirai/garnet/editor/ui/LocalHistoryState.kt`
- Modify: `src/client/kotlin/com/breadmoirai/garnet/editor/network/EditorClientNetworking.kt`
- Test: `src/test/kotlin/com/breadmoirai/garnet/client/editor/ui/LocalHistoryStateTest.kt` (new)

**Interfaces:**
- Consumes: `StructureHistoryS2C`, `RevisionEntry`, `StructureResultS2C` (Task 5 / existing).
- Produces:
  ```kotlin
  object OpenStructureState {
      var subpath: String?      // private set
      fun onStructureResult(r: StructureResultS2C)
      fun reset()
  }
  object LocalHistoryState {
      var subpath: String?      // private set
      var revisions: List<RevisionEntry>   // private set; NEWEST FIRST
      var selected: Long?       // timestamp; private set
      val currentTimestamp: Long?          // the newest revision's timestamp, or null
      fun onHistory(p: StructureHistoryS2C)
      fun select(timestampMillis: Long)
      fun isRestorable(timestampMillis: Long): Boolean
      fun reset()
  }
  ```

- [ ] **Step 1: Write the failing test**

Create `src/test/kotlin/com/breadmoirai/garnet/client/editor/ui/LocalHistoryStateTest.kt`, modelled
on `ExplorerActionsTest`'s pure-JVM `FunSpec` style:

```kotlin
package com.breadmoirai.garnet.client.editor.ui

import com.breadmoirai.garnet.editor.network.RevisionEntry
import com.breadmoirai.garnet.editor.network.StructureHistoryS2C
import com.breadmoirai.garnet.editor.ui.LocalHistoryState
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe

/**
 * The Local History panel's list model. Pure: no Compose scene, no client — the panel body's
 * rendering is covered by the pixel probe in `JewelExplorerSpec`.
 */
class LocalHistoryStateTest : FunSpec({

    afterTest { LocalHistoryState.reset() }

    fun history(vararg stamps: Long) = StructureHistoryS2C(
        "clock.nbt",
        // The wire order is oldest-first, as LocalHistoryStore returns it.
        stamps.sorted().map { RevisionEntry(it, 1, 1, 1, 1, "autosave") },
    )

    test("revisions are exposed newest first regardless of wire order") {
        LocalHistoryState.onHistory(history(300L, 100L, 200L))

        LocalHistoryState.revisions.map { it.timestampMillis } shouldBe listOf(300L, 200L, 100L)
    }

    test("the newest revision is the current one and is not restorable") {
        LocalHistoryState.onHistory(history(100L, 200L))

        LocalHistoryState.currentTimestamp shouldBe 200L
        LocalHistoryState.isRestorable(200L).shouldBeFalse()
        LocalHistoryState.isRestorable(100L).shouldBeTrue()
    }

    test("an empty list has no current revision and nothing restorable") {
        LocalHistoryState.onHistory(StructureHistoryS2C("clock.nbt", emptyList()))

        LocalHistoryState.revisions.shouldBeEmpty()
        LocalHistoryState.currentTimestamp shouldBe null
        LocalHistoryState.isRestorable(1L).shouldBeFalse()
    }

    test("a selection that no longer exists is dropped when a new list arrives") {
        LocalHistoryState.onHistory(history(100L, 200L))
        LocalHistoryState.select(100L)
        LocalHistoryState.selected shouldBe 100L

        // 100 was pruned away between pushes.
        LocalHistoryState.onHistory(history(200L, 300L))

        LocalHistoryState.selected shouldBe null
    }

    test("selecting the current revision is refused") {
        LocalHistoryState.onHistory(history(100L, 200L))

        LocalHistoryState.select(200L)

        LocalHistoryState.selected shouldBe null
    }
})
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cmd.exe /c "gradlew.bat :26.2:test"`
Expected: FAIL — `LocalHistoryState` unresolved. (Remember: `--tests` will not select this spec; run
unfiltered and read `build/test-results/test/TEST-*LocalHistoryStateTest.xml`.)

- [ ] **Step 3: Write the state objects**

`OpenStructureState.kt`:

```kotlin
package com.breadmoirai.garnet.editor.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.breadmoirai.garnet.editor.network.StructureResultS2C

/**
 * Which structure is currently placed in the editor world, client-side.
 *
 * [ProjectTreeState] records only the status *message* from a `StructureResultS2C`, which is not
 * something anything can key off. The Local History panel needs the subpath itself: it only ever
 * shows a structure that is actually in the world.
 */
object OpenStructureState {
    var subpath by mutableStateOf<String?>(null)
        private set

    fun onStructureResult(r: StructureResultS2C) { subpath = r.subpath }

    /** Test/reset hook; also called on disconnect — a placed structure does not survive a world. */
    fun reset() { subpath = null }
}
```

`LocalHistoryState.kt`:

```kotlin
package com.breadmoirai.garnet.editor.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.breadmoirai.garnet.editor.network.RevisionEntry
import com.breadmoirai.garnet.editor.network.StructureHistoryS2C

/**
 * The Local History panel's list model.
 *
 * **Revisions are POST-commit snapshots**: the newest one is byte-identical to what is on disk right
 * now, so it is the "current" state and restoring it would be a no-op. It is kept in the list — a
 * timeline with a hole in it is worse — but is not selectable, and the panel renders it inert. The
 * server enforces the same rule; this is the half that stops the UI offering the action at all.
 *
 * Held newest-first, the reverse of the wire order, because that is display order.
 */
object LocalHistoryState {
    var subpath by mutableStateOf<String?>(null)
        private set
    var revisions by mutableStateOf<List<RevisionEntry>>(emptyList())
        private set
    var selected by mutableStateOf<Long?>(null)
        private set

    /** The newest revision's timestamp — what is on disk — or null when there is no history. */
    val currentTimestamp: Long? get() = revisions.firstOrNull()?.timestampMillis

    fun onHistory(p: StructureHistoryS2C) {
        subpath = p.subpath
        revisions = p.revisions.sortedByDescending { it.timestampMillis }
        // Drop a selection the new list no longer contains: a revision can be pruned between
        // pushes, and a Restore aimed at a gone timestamp would only earn a server refusal.
        if (selected != null && revisions.none { it.timestampMillis == selected }) selected = null
        if (selected == currentTimestamp) selected = null
    }

    fun isRestorable(timestampMillis: Long): Boolean =
        revisions.any { it.timestampMillis == timestampMillis } && timestampMillis != currentTimestamp

    fun select(timestampMillis: Long) {
        if (isRestorable(timestampMillis)) selected = timestampMillis
    }

    fun reset() {
        subpath = null
        revisions = emptyList()
        selected = null
    }
}
```

- [ ] **Step 4: Receive the packet**

In `EditorClientNetworking.kt`, beside the existing receivers:

```kotlin
        ClientPlayNetworking.registerGlobalReceiver(StructureHistoryS2C.TYPE) { payload, ctx ->
            ctx.client().execute { LocalHistoryState.onHistory(payload) }
        }
```

and extend the existing `StructureResultS2C` receiver to also call
`OpenStructureState.onStructureResult(payload)` alongside `ProjectTreeState.onStructureResult`.
Add `OpenStructureState.reset()` and `LocalHistoryState.reset()` wherever `ProjectTreeState.reset()`
is called on disconnect.

- [ ] **Step 5: Run tests to verify they pass**

Run: `cmd.exe /c "gradlew.bat :26.2:test"`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/client/kotlin/com/breadmoirai/garnet/editor/ui/OpenStructureState.kt \
        src/client/kotlin/com/breadmoirai/garnet/editor/ui/LocalHistoryState.kt \
        src/client/kotlin/com/breadmoirai/garnet/editor/network/EditorClientNetworking.kt \
        src/test/kotlin/com/breadmoirai/garnet/client/editor/ui/LocalHistoryStateTest.kt
git commit -m "feat(client): track the open structure and its revision list"
```

---

### Task 8: The dock tab strip

`RegionColumn` renders only `panels[active]`; the tab strip was deleted when one-panel-per-region
held. Two LEFT panels brings it back.

**Files:**
- Create: `src/client/kotlin/com/breadmoirai/garnet/ui/dock/DockTabStrip.kt`
- Modify: `src/client/kotlin/com/breadmoirai/garnet/ui/dock/DockState.kt`
- Modify: `src/client/kotlin/com/breadmoirai/garnet/ui/dock/GarnetDock.kt:61-76`
- Test: `src/test/kotlin/com/breadmoirai/garnet/client/ui/dock/DockTabStateTest.kt` (new)

**Interfaces:**
- Consumes: `DockState.panelsFor(region)`, `DockState.mountEpoch(region)`, `Panel(id, title, content)`.
- Produces:
  ```kotlin
  // on DockState:
  fun setActiveTab(region: DockRegion, index: Int)   // clamped to the region's panel indices
  fun activeTab(region: DockRegion): Int
  // new composable:
  @Composable fun DockTabStrip(region: DockRegion, panels: List<Panel>, active: Int, onSelect: (Int) -> Unit)
  ```

- [ ] **Step 1: Write the failing test**

Create `src/test/kotlin/com/breadmoirai/garnet/client/ui/dock/DockTabStateTest.kt`:

```kotlin
package com.breadmoirai.garnet.client.ui.dock

import com.breadmoirai.garnet.ui.dock.DockRegion
import com.breadmoirai.garnet.ui.dock.DockState
import com.breadmoirai.garnet.ui.dock.Panel
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class DockTabStateTest : FunSpec({

    afterTest { DockState.reset() }

    fun seedTwoLeftPanels() {
        DockState.leftPanels += Panel("a", "Explorer") { }
        DockState.leftPanels += Panel("b", "Local History") { }
    }

    test("setActiveTab selects a panel by index") {
        seedTwoLeftPanels()

        DockState.setActiveTab(DockRegion.LEFT, 1)

        DockState.activeTab(DockRegion.LEFT) shouldBe 1
    }

    test("an out-of-range index is clamped rather than accepted") {
        seedTwoLeftPanels()

        DockState.setActiveTab(DockRegion.LEFT, 7)
        DockState.activeTab(DockRegion.LEFT) shouldBe 1

        DockState.setActiveTab(DockRegion.LEFT, -3)
        DockState.activeTab(DockRegion.LEFT) shouldBe 0
    }

    test("selecting a tab in an empty region stays at zero") {
        DockState.setActiveTab(DockRegion.LEFT, 2)

        DockState.activeTab(DockRegion.LEFT) shouldBe 0
    }
})
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cmd.exe /c "gradlew.bat :26.2:test"`
Expected: FAIL — `setActiveTab` / `activeTab` unresolved.

- [ ] **Step 3: Add the DockState writers**

In `DockState`, after `panelsFor`:

```kotlin
    fun activeTab(region: DockRegion): Int = when (region) {
        DockRegion.LEFT -> leftActiveTab
        DockRegion.RIGHT -> rightActiveTab
        DockRegion.BOTTOM -> bottomActiveTab
        DockRegion.CENTER -> centerActiveTab
    }

    /**
     * Select which panel a region shows. Clamped to the region's real indices, so a stale index from
     * a caller that has not seen a panel list change can never point past the end — [GarnetDock]
     * would otherwise index out of bounds mid-composition.
     */
    fun setActiveTab(region: DockRegion, index: Int) {
        val clamped = index.coerceIn(0, (panelsFor(region).size - 1).coerceAtLeast(0))
        when (region) {
            DockRegion.LEFT -> leftActiveTab = clamped
            DockRegion.RIGHT -> rightActiveTab = clamped
            DockRegion.BOTTOM -> bottomActiveTab = clamped
            DockRegion.CENTER -> centerActiveTab = clamped
        }
    }
```

- [ ] **Step 4: Write the strip**

Create `DockTabStrip.kt`. Keep it Jewel-free and minimal — this is chrome, not content:

```kotlin
package com.breadmoirai.garnet.ui.dock

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jetbrains.compose... // see note below
import androidx.compose.foundation.text.BasicText

private val TAB_ACTIVE_BG = Color(0xFF2B2D30)
private val TAB_ACTIVE_FG = Color(0xFFDFE1E5)
private val TAB_IDLE_FG = Color(0xFF8B8F96)

/**
 * The region's tab row. Rendered only when a region holds more than one panel — a single-panel
 * region shows its body full-bleed, as it did before this strip existed.
 *
 * Deliberately hand-rolled rather than a Jewel tab component: this is the same layer that had a
 * hand-rolled strip before, and a Jewel tab row would pull focus-and-popup behaviour into a scene
 * whose layer routing is already the subtlest thing in this package.
 */
@Composable
fun DockTabStrip(
    region: DockRegion,
    panels: List<Panel>,
    active: Int,
    onSelect: (Int) -> Unit,
) {
    if (panels.size < 2) return
    Row(Modifier.padding(horizontal = 2.dp, vertical = 2.dp)) {
        panels.forEachIndexed { index, panel ->
            val isActive = index == active
            BasicText(
                text = panel.title,
                style = TextStyle(
                    color = if (isActive) TAB_ACTIVE_FG else TAB_IDLE_FG,
                    fontSize = 11.sp,
                ),
                modifier = Modifier
                    .background(if (isActive) TAB_ACTIVE_BG else Color.Transparent)
                    .padding(horizontal = 6.dp, vertical = 3.dp)
                    .pointerInput(index) { detectTapGestures { onSelect(index) } },
            )
        }
    }
}
```

Drop the bogus `org.jetbrains.compose..` import line — it is a placeholder marker; the real imports
are the `androidx.compose.*` ones listed. Match `GarnetDock.kt`'s existing import style.

- [ ] **Step 5: Render it**

In `GarnetDock.kt`'s `RegionColumn`, inside the existing `Column`, before the `Box`:

```kotlin
        DockTabStrip(region, panels, active) { DockState.setActiveTab(region, it) }
```

- [ ] **Step 6: Run tests to verify they pass**

Run: `cmd.exe /c "gradlew.bat :26.2:test"` then the full compile command from Global Constraints.
Expected: PASS and a clean compile across all five source sets.

- [ ] **Step 7: Commit**

```bash
git add src/client/kotlin/com/breadmoirai/garnet/ui/dock/ \
        src/test/kotlin/com/breadmoirai/garnet/client/ui/dock/DockTabStateTest.kt
git commit -m "feat(dock): restore a tab strip for multi-panel regions"
```

---

### Task 9: The Local History panel

**Files:**
- Create: `src/client/kotlin/com/breadmoirai/garnet/editor/ui/LocalHistoryPanel.kt`
- Modify: `src/client/kotlin/com/breadmoirai/garnet/GarnetClient.kt`
- Test: `src/clientTest/kotlin/com/breadmoirai/garnet/test/JewelExplorerSpec.kt`

**Interfaces:**
- Consumes: `LocalHistoryState`, `OpenStructureState` (Task 7), `WatchStructureHistoryC2S`, `RestoreRevisionC2S` (Task 5), `Panel` (existing).
- Produces: `fun localHistoryPanel(): Panel` with id `"garnet.localHistory"` and title `"Local History"`.

- [ ] **Step 1: Write the panel**

There is no cheap failing test for a composable body, so this task is written implementation-first and
gated by the pixel probe in Step 3. Create `LocalHistoryPanel.kt`:

```kotlin
package com.breadmoirai.garnet.editor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.breadmoirai.garnet.config.SharedSettings
import com.breadmoirai.garnet.editor.network.RestoreRevisionC2S
import com.breadmoirai.garnet.editor.network.RevisionEntry
import com.breadmoirai.garnet.editor.network.WatchStructureHistoryC2S
import com.breadmoirai.garnet.ui.dock.Panel
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import org.jetbrains.jewel.intui.standalone.theme.IntUiTheme
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.Text
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Matches the Explorer panel's background. */
private val PANEL_BG = Color(0xFF1E1F22)

/** The Local History tab for DockState.leftPanels. */
fun localHistoryPanel(): Panel = Panel("garnet.localHistory", "Local History") { LocalHistory() }

@Composable
private fun LocalHistory() {
    IntUiTheme(isDark = true) {
        Column(Modifier.fillMaxSize().background(PANEL_BG).padding(4.dp)) {
            val open = OpenStructureState.subpath
            // Tell the server what we are looking at whenever that changes — including the empty
            // "stop watching" case, so it is not still pushing lists for a structure we closed.
            LaunchedEffect(open) {
                ClientPlayNetworking.send(WatchStructureHistoryC2S(open.orEmpty()))
            }
            when {
                open == null ->
                    Text("(no structure open — place one from the Explorer)")
                !SharedSettings.localHistoryEnabled ->
                    // Distinct from an empty list, which would claim this structure has no history.
                    Text("(local history is disabled in settings)")
                LocalHistoryState.revisions.isEmpty() ->
                    Text("(no revisions yet for $open)")
                else -> RevisionList()
            }
        }
    }
}

@Composable
private fun RevisionList() {
    Text(LocalHistoryState.subpath.orEmpty(), Modifier.padding(bottom = 4.dp))
    LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
        items(LocalHistoryState.revisions) { revision ->
            RevisionRow(revision)
        }
    }
    val selected = LocalHistoryState.selected
    DefaultButton(
        onClick = { selected?.let { ClientPlayNetworking.send(RestoreRevisionC2S(LocalHistoryState.subpath!!, it)) } },
        enabled = selected != null,
        modifier = Modifier.padding(top = 4.dp),
    ) {
        Text("Restore")
    }
}

/**
 * One revision row.
 *
 * The current (newest) revision is rendered in the disabled foreground and carries NO click
 * handler — it is what is already on disk, so restoring it would do nothing. That is not
 * colour-only signalling: the row genuinely does not respond, which is the honest rendering of an
 * inert row. No glyph marks it, because Jewel's Inter has no emoji coverage and a fallback glyph
 * renders as tofu on hosts without a system emoji font.
 */
@Composable
private fun RevisionRow(revision: RevisionEntry) {
    val restorable = LocalHistoryState.isRestorable(revision.timestampMillis)
    val isSelected = LocalHistoryState.selected == revision.timestampMillis
    val rowModifier = Modifier
        .fillMaxWidth()
        .background(if (isSelected) Color(0xFF2E436E) else Color.Transparent)
        .padding(horizontal = 4.dp, vertical = 2.dp)
        .let {
            if (restorable) it.pointerInput(revision.timestampMillis) {
                detectTapGestures { LocalHistoryState.select(revision.timestampMillis) }
            } else it
        }
    Row(rowModifier) {
        Text(formatTime(revision.timestampMillis), Modifier.weight(1f), color = fg(restorable))
        Text("${revision.sizeX}×${revision.sizeY}×${revision.sizeZ}", Modifier.weight(1f), color = fg(restorable))
        Text(revision.reason, Modifier.weight(1f), color = muted(restorable))
    }
}

private fun fg(restorable: Boolean) = if (restorable) Color(0xFFDFE1E5) else Color(0xFF6F737A)
private fun muted(restorable: Boolean) = if (restorable) Color(0xFF8B8F96) else Color(0xFF6F737A)

private val TIME_FORMAT = SimpleDateFormat("HH:mm:ss", Locale.ROOT)
private fun formatTime(millis: Long): String = TIME_FORMAT.format(Date(millis))
```

If Jewel's `Text` in this version does not take a `color` parameter, wrap each in a
`CompositionLocalProvider(LocalContentColor provides ...)` or use `BasicText` with an explicit
`TextStyle`, following whatever `ProjectExplorerPanel` does for coloured text.

- [ ] **Step 2: Seed the panel**

In `GarnetClient.onInitializeClient`, beside the existing `DockState.leftPanels += explorerPanel()`:

```kotlin
        DockState.leftPanels += localHistoryPanel()
```

- [ ] **Step 3: Add the pixel probe**

In `src/clientTest/.../JewelExplorerSpec.kt`, following that file's existing probe helpers:

```kotlin
    // Switching to the Local History tab must actually swap what is painted. Asserted by pixel
    // probe, not by state flags: every flag reads clean while a stale composition is still drawing,
    // which is exactly the ghost-panel failure DockState.mountEpoch exists to prevent.
    // (Follow this file's established probe pattern for capturing and comparing framebuffer pixels.)
```

Write the probe using the same capture/compare helpers the neighbouring cases use: show LEFT, sample
a pixel inside the panel body, call `DockState.setActiveTab(DockRegion.LEFT, 1)`, render a frame, and
assert the sampled pixel changed.

- [ ] **Step 4: Verify**

Run, in order:
```
cmd.exe /c "cd /d H:\\Repo\\garnet && gradlew.bat :26.2:clientClasses :26.2:classes :26.2:gametestClasses :26.2:clientTestClasses :26.2:testClasses"
cmd.exe /c "gradlew.bat :26.2:test"
cmd.exe /c "gradlew.bat :26.2:runClientTest"
```
Expected: clean compile, PASS, PASS.

- [ ] **Step 5: Commit**

```bash
git add src/client/kotlin/com/breadmoirai/garnet/editor/ui/LocalHistoryPanel.kt \
        src/client/kotlin/com/breadmoirai/garnet/GarnetClient.kt \
        src/clientTest/kotlin/com/breadmoirai/garnet/test/JewelExplorerSpec.kt
git commit -m "feat(explorer): add the Local History panel"
```

---

### Task 10: The context-menu entry

**Files:**
- Modify: `src/client/kotlin/com/breadmoirai/garnet/editor/ui/ExplorerContextMenu.kt`
- Modify: `src/client/kotlin/com/breadmoirai/garnet/editor/ui/ProjectExplorerPanel.kt` (the `ExplorerContextMenu(...)` call, ~line 151)
- Modify: `src/client/kotlin/com/breadmoirai/garnet/editor/ui/ExplorerActions.kt`
- Test: `src/test/kotlin/com/breadmoirai/garnet/client/editor/ui/ExplorerActionsTest.kt`

**Interfaces:**
- Consumes: `PlaceStructureC2S` (existing), `OpenStructureState` (Task 7), `DockState.setActiveTab` (Task 8).
- Produces: `ExplorerActions.openLocalHistory(path: String): String?` — null on success, else the reason nothing happened.

- [ ] **Step 1: Write the failing test**

Append to `ExplorerActionsTest.kt`:

```kotlin
    test("opening local history for an unplaced structure places it first") {
        val sent = captureSends()
        ProjectTreeState.onSnapshot(snapshotWith("clock.nbt"))   // this file's existing helper

        ExplorerActions.openLocalHistory("clock.nbt") shouldBe null

        sent.filterIsInstance<PlaceStructureC2S>().single().subpath shouldBe "clock.nbt"
    }

    test("opening local history for the already-placed structure sends no place packet") {
        val sent = captureSends()
        ProjectTreeState.onSnapshot(snapshotWith("clock.nbt"))
        OpenStructureState.onStructureResult(StructureResultS2C("clock.nbt", 1, 1, 1, "placed"))

        ExplorerActions.openLocalHistory("clock.nbt") shouldBe null

        sent.filterIsInstance<PlaceStructureC2S>().shouldBeEmpty()
    }

    test("local history is refused for a non-structure path") {
        val sent = captureSends()

        ExplorerActions.openLocalHistory("notes.spec.kts").shouldNotBeNull()

        sent.shouldBeEmpty()
    }
```

Add `OpenStructureState.reset()` to the spec's `afterTest`. Reuse whatever snapshot helper the file
already has; if there is none, build an `EditorTreeSnapshotS2C` the way the neighbouring tests do.

- [ ] **Step 2: Run test to verify it fails**

Run: `cmd.exe /c "gradlew.bat :26.2:test"`
Expected: FAIL — `openLocalHistory` unresolved.

- [ ] **Step 3: Add the action**

In `ExplorerActions`:

```kotlin
    /**
     * Show [path]'s revisions in the Local History panel, placing the structure first if it is not
     * already placed.
     *
     * The panel only ever shows a PLACED structure — that invariant is what keeps the server's
     * restore path single, with a footprint to clear and a commit to write through. So this is
     * "place, then look at", never "look at without placing".
     *
     * Null on success, else the reason nothing happened.
     */
    fun openLocalHistory(path: String): String? {
        if (!path.endsWith(".nbt")) return "local history is only available for structures"
        if (OpenStructureState.subpath != path) sender(PlaceStructureC2S(path))
        DockState.setActiveTab(DockRegion.LEFT, DockState.leftPanels.indexOfFirst { it.id == "garnet.localHistory" })
        return null
    }
```

`setActiveTab` clamps, so an `indexOfFirst` of `-1` (panel not seeded, as in a unit test) becomes 0
rather than throwing.

- [ ] **Step 4: Add the menu item**

In `ExplorerContextMenu`, add an `onLocalHistory: (path: String) -> Unit` parameter and, after the
`Move to…` item:

```kotlin
        separator()
        selectableItem(
            selected = false,
            // Only a structure has revisions to show; folders, the root and .spec.kts files have
            // nothing to place.
            enabled = target != ExplorerTreeState.ROOT_PATH && target.endsWith(".nbt"),
            onClick = { state.close(); onLocalHistory(target) },
        ) {
            Text("Local History")
        }
```

Flat, not a submenu — Jewel opens a flyout as a second focusable layer and this scene stops routing
pointer events to every layer below the focused one (see the KDoc on `ExplorerContextMenu`).

In `ProjectExplorerPanel`'s call site, pass:

```kotlin
                    onLocalHistory = { path -> ExplorerActions.openLocalHistory(path) },
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `cmd.exe /c "gradlew.bat :26.2:test"` and the full compile command.
Expected: PASS and a clean compile.

- [ ] **Step 6: Commit**

```bash
git add src/client/kotlin/com/breadmoirai/garnet/editor/ui/ \
        src/test/kotlin/com/breadmoirai/garnet/client/editor/ui/ExplorerActionsTest.kt
git commit -m "feat(explorer): open Local History from the context menu"
```

---

### Task 11: Documentation

Per CLAUDE.md, docs are audited after any source change — this task is not optional.

**Files:**
- Create: `docs/ui/local-history-panel.md`
- Modify: `docs/ui/INDEX.md`, `docs/ui/dock-framework.md`, `docs/ui/explorer-toolbar-and-context-menu.md`, `docs/persistence/local-history.md`, `docs/persistence/editor-undo-stack.md`, `docs/use-cases/structure-lifecycle.md`

- [ ] **Step 1: Write the new article**

Create `docs/ui/local-history-panel.md` with the standard frontmatter:

```markdown
---
title: The Local History panel
tags: [screens, widgets, history, dock, restore]
summary: The revision list tabbed beside the Project Explorer — why it only ever shows a placed structure, why the newest revision is shown but inert, why a revision is addressed by timestamp rather than list index, and how the server pushes refreshes to watchers.
---
```

Cover, in this order: the placed-only invariant and what it buys; the POST-commit revision model and
why the newest row is inert rather than hidden; timestamp-not-index addressing; the watcher push
model; and the restore sequence's ordering rules (quiesce-or-abort, clear-old-footprint-before-place,
no teleport). Link to `persistence/local-history.md` and `persistence/editor-undo-stack.md`.

- [ ] **Step 2: Update the existing articles**

- `docs/ui/INDEX.md` — register the new article as `- [The Local History panel](local-history-panel.md) — <summary>` plus tags.
- `docs/ui/dock-framework.md` — the "Regions, panels, and tabs" section states the tab strip was
  removed because only one panel is ever registered per region. That is no longer true: describe the
  strip's return, `DockState.setActiveTab`, and that it renders only when a region holds 2+ panels.
- `docs/ui/explorer-toolbar-and-context-menu.md` — add the `Local History` item and its
  `.nbt`-only enablement.
- `docs/persistence/local-history.md` — add `restore` to the reason table and to "The writers"
  (`StructureCommit`, driven by `StructureRestoreOps`); note in the `blockCount` section that
  `restore` carries a real count because it is committed by scanning the world; and update the
  "Rollback implication" paragraph, which currently says there is no UI for this, to point at the
  panel.
- `docs/persistence/editor-undo-stack.md` — document `RestoreRevision` and why it carries no content
  (the pre-restore state is itself a banked revision).
- `docs/use-cases/structure-lifecycle.md` — add a coverage-matrix row for restore, citing
  `StructureRestoreSpec`.

- [ ] **Step 3: Verify cross-references resolve**

```bash
grep -rn "local-history-panel.md" docs/
grep -rn "no UI\|hand-copying" docs/persistence/local-history.md
```
Expected: the first returns the INDEX entry plus any "see also" links; the second returns nothing
stale claiming there is no UI.

- [ ] **Step 4: Commit**

```bash
git add docs/
git commit -m "docs: cover the Local History panel and the restore path"
```

---

## Self-Review

**Spec coverage.** Every spec section maps to a task: the invariant → Tasks 3 and 10; the three
payloads → Task 5; watchers and push → Task 6; the restore sequence's seven steps → Task 3; the
required `placeStructureCentered` refactor → Task 2; undo → Task 4; presentation (color not glyphs,
distinguished empty states) → Task 9; the failure-mode table → Tasks 3, 6 and 9; the testing section →
distributed across Tasks 1–10; the docs list → Task 11.

**Known soft spots**, flagged rather than papered over:

- Task 3's helper assumes `StructureAutoSave.markDirty(subpath, box)`. Verify the real name against
  `StructureEditWatcher` before writing the tests; the plan says so inline.
- Task 3's failed-quiesce case depends on `commitDirtyUnder` returning non-null for a deleted file.
  If `commit` clears the dirty flag instead, the test must assert the refusal that actually occurs —
  the plan explicitly forbids weakening the production abort to make it pass.
- Task 5's list codec (`REVISION_ENTRY_STREAM_CODEC.apply(ByteBufCodecs.list())`) should be checked
  against the in-file precedent (`EditorSaveReportS2C`) for this MC version.
- Task 9 is implementation-first because a composable body has no cheap unit test; its gate is the
  pixel probe plus the pure list-model tests already landed in Task 7.
- Task 8's `DockTabStrip.kt` code block contains a deliberate placeholder import line that Step 4
  tells you to delete. Do not copy it verbatim.

**Type consistency.** `RestoreOutcome.Restored(subpath, fromTimestampMillis, toTimestampMillis)` is
constructed in Task 3 and destructured in Tasks 4 and 6 with those exact names.
`StructureRestoreOps.restore(server, subpath, timestampMillis)` takes no player in all three call
sites. `LocalHistoryState.isRestorable/select/currentTimestamp` are defined in Task 7 and used in
Task 9 under those names. `DockState.setActiveTab(region, index)` is defined in Task 8 and called in
Tasks 8 and 10. The panel id `"garnet.localHistory"` is written in Task 9 and matched by string in
Task 10.
