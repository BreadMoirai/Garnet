# Structure Info Block List Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a scrollable, sortable per-block materials list with real inventory icons to the Structure Info panel.

**Architecture:** The server counts blocks per type during the scan it already performs (auto-save commit) or a new minimal scan of the placed box (place), and broadcasts them on a new `StructureBlockTallyS2C`. The client stores the tally in `StructureInfoState`, sorts it by count or creative order (persisted preference), and renders it in a `LazyColumn`. Icons come from a new subsystem that renders each block's item model into an offscreen `TextureTarget` and reads it back to a Compose `ImageBitmap`, cached per session.

**Tech Stack:** Kotlin, Fabric (MC 26.2), Stonecutter, Compose Multiplatform + Jewel, Blaze3D GPU API, Kotest.

**Spec:** `docs/superpowers/specs/2026-08-16-structure-block-list-design.md`

## Global Constraints

- **MC version:** 26.2 only (`stonecutter active "26.2"`). All Gradle tasks are `:26.2:`-prefixed.
- **Gradle from WSL:** always `cmd.exe /c "cd /d H:\\Repo\\Garnet && gradlew.bat <tasks>"`. Never bare `./gradlew` — see `docs/tooling/wsl2-gradle-invocation.md`.
- **Compile verification covers five source sets:** `:26.2:clientClasses :26.2:classes :26.2:gametestClasses :26.2:clientTestClasses :26.2:testClasses`.
- **`:26.2:test --tests '<filter>'` does not select Kotest specs** — it reports a false "No tests found". Always run `:26.2:test` unfiltered and read the per-class JUnit XML under `versions/26.2/build/test-results/test/`.
- **No emoji or non-ASCII glyphs in any panel text.** Jewel's default family is Inter, which has no emoji coverage; anything outside it renders as tofu. Use `x` not `×`. Icons must be SVG `IconKey`s from the artwork artifact, never glyphs.
- **No fastutil.** It is used nowhere in this codebase; use plain Kotlin/Java collections.
- **Unsolicited S2C sends must be `canSend`-guarded.** A broadcast is not a reply to a C2S, so the receiver is not provably running the mod; sending an unknown play-phase payload to a vanilla client on a dedicated server can disconnect it.
- **Panel-local state may not live in a bare top-level object.** The dock composes into a long-lived singleton scene, so global panel state survives a re-mount and paints over the next one (`DockState.mountEpoch`). Either `remember` it in the composable or persist it through a config store.
- **Commit after every task.** Do not batch commits across tasks.

---

## File Structure

**Server / common (`src/main/kotlin/`)**
- `com/breadmoirai/garnet/structure/StructurePersistence.kt` — modify: tally during `captureAutoFitIn`, new `tallyBlocksIn`.
- `com/breadmoirai/garnet/editor/network/EditorPackets.kt` — modify: add `BlockTallyEntry` + `StructureBlockTallyS2C`.
- `com/breadmoirai/garnet/editor/network/EditorNetworkRegistry.kt` — modify: register the new payload.
- `com/breadmoirai/garnet/editor/structure/StructureCommit.kt` — modify: carry the tally out of `commit`, broadcast it.
- `com/breadmoirai/garnet/editor/network/EditorStructureHandlers.kt` — modify: tally + send on the place path.

**Client (`src/client/kotlin/`)**
- `com/breadmoirai/garnet/editor/ui/StructureInfoState.kt` — modify: observable `blockTally`.
- `com/breadmoirai/garnet/editor/network/EditorClientNetworking.kt` — modify: receiver for the new payload.
- `com/breadmoirai/garnet/editor/ui/BlockSortMode.kt` — create: the enum, the creative-order index, the comparator.
- `com/breadmoirai/garnet/config/PanelPrefsStore.kt` — create: sort-mode persistence.
- `com/breadmoirai/garnet/editor/ui/StructureInfoPanel.kt` — modify: header, sort toggle, list.
- `com/breadmoirai/garnet/ui/icon/BlockIconCache.kt` — create: observable `Block → ImageBitmap` cache + request queue.
- `com/breadmoirai/garnet/ui/icon/BlockIconRenderer.kt` — create: offscreen render + readback.
- `src/client/java/com/breadmoirai/garnet/mixin/client/MinecraftPresentMixin.java` — modify: drain the icon queue.

**Tests**
- `src/gametest/kotlin/com/breadmoirai/garnet/test/structure/StructureRegionPersistenceSpec.kt` — modify: tally assertions.
- `src/test/kotlin/com/breadmoirai/garnet/client/editor/ui/StructureInfoStateTest.kt` — modify: tally reset semantics.
- `src/test/kotlin/com/breadmoirai/garnet/client/editor/ui/BlockSortModeTest.kt` — create: comparator.
- `src/test/kotlin/com/breadmoirai/garnet/client/config/PanelPrefsStoreTest.kt` — create: round-trip.
- `src/clientTest/kotlin/com/breadmoirai/garnet/test/BlockListPanelSpec.kt` — create: panel + icon behavior.

---

### Task 1: Server-side block tally

**Files:**
- Modify: `src/main/kotlin/com/breadmoirai/garnet/structure/StructurePersistence.kt`
- Test: `src/gametest/kotlin/com/breadmoirai/garnet/test/structure/StructureRegionPersistenceSpec.kt`

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces:
  - `CapturedStructure(tag: CompoundTag, box: PlacedBox?, blockCount: Int, tally: Map<Block, Int>)`
  - `StructurePersistence.tallyBlocksIn(level: ServerLevel, box: PlacedBox): Map<Block, Int>`

- [ ] **Step 1: Write the failing tests**

Add to `StructureRegionPersistenceSpec.kt`:

```kotlin
    test("captureAutoFitIn tallies blocks by type, collapsing block states") {
        onServer {
            val level = overworld()
            val origin = BlockPos(300_200, 64, EditorDimRegistry.STRUCTURE_LANE_Z)
            val scan = PlacedBox(origin, Vec3i(8, 4, 8))
            StructurePersistence.clearBounds(level, scan.origin, scan.size)

            // Two oak stairs in DIFFERENT facings must collapse to one tally entry of 2.
            val stairs = Blocks.OAK_STAIRS.defaultBlockState()
            level.setBlock(origin.offset(1, 0, 1), stairs.setValue(StairBlock.FACING, Direction.NORTH), 2)
            level.setBlock(origin.offset(2, 0, 1), stairs.setValue(StairBlock.FACING, Direction.SOUTH), 2)
            level.setBlock(origin.offset(3, 0, 1), Blocks.GOLD_BLOCK.defaultBlockState(), 2)

            val captured = StructurePersistence.captureAutoFitIn(level, scan)

            captured.blockCount shouldBe 3
            captured.tally[Blocks.OAK_STAIRS] shouldBe 2
            captured.tally[Blocks.GOLD_BLOCK] shouldBe 1
            captured.tally.containsKey(Blocks.AIR) shouldBe false

            StructurePersistence.clearBounds(level, scan.origin, scan.size)
        }
    }

    test("captureAutoFitIn on an empty volume tallies nothing") {
        onServer {
            val level = overworld()
            val origin = BlockPos(300_300, 64, EditorDimRegistry.STRUCTURE_LANE_Z)
            val scan = PlacedBox(origin, Vec3i(4, 4, 4))
            StructurePersistence.clearBounds(level, scan.origin, scan.size)

            val captured = StructurePersistence.captureAutoFitIn(level, scan)

            captured.blockCount shouldBe 0
            captured.tally.isEmpty() shouldBe true
        }
    }

    test("tallyBlocksIn counts exactly the given box") {
        onServer {
            val level = overworld()
            val origin = BlockPos(300_400, 64, EditorDimRegistry.STRUCTURE_LANE_Z)
            val scan = PlacedBox(origin, Vec3i(8, 4, 8))
            StructurePersistence.clearBounds(level, scan.origin, scan.size)

            level.setBlock(origin.offset(0, 0, 0), Blocks.IRON_BLOCK.defaultBlockState(), 2)
            level.setBlock(origin.offset(1, 0, 0), Blocks.IRON_BLOCK.defaultBlockState(), 2)
            // Outside the 2x1x1 box below -- must NOT be counted.
            level.setBlock(origin.offset(5, 0, 5), Blocks.GOLD_BLOCK.defaultBlockState(), 2)

            val tally = StructurePersistence.tallyBlocksIn(level, PlacedBox(origin, Vec3i(2, 1, 1)))

            tally[Blocks.IRON_BLOCK] shouldBe 2
            tally.containsKey(Blocks.GOLD_BLOCK) shouldBe false

            StructurePersistence.clearBounds(level, scan.origin, scan.size)
        }
    }
```

Add these imports to the spec file:

```kotlin
import net.minecraft.core.Direction
import net.minecraft.world.level.block.StairBlock
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cmd.exe /c "cd /d H:\\Repo\\Garnet && gradlew.bat :26.2:runGameTest"`
Expected: FAIL — `captured.tally` unresolved, `tallyBlocksIn` unresolved.

- [ ] **Step 3: Add the tally to `CapturedStructure`**

In `StructurePersistence.kt`, extend the data class and its KDoc:

```kotlin
/**
 * The result of scanning a volume: the saved [StructureTemplate] tag, the tight [box] enclosing all
 * non-air (null when the volume was empty), the non-air [blockCount], and the per-type [tally].
 *
 * [blockCount] is counted during the scan rather than derived from the tag: `fillFromWorld` records
 * every cell in its bounds, air included, so `tag.blocks.size` is the box volume, not the build size.
 *
 * [tally] is keyed by [Block], not `BlockState`: oak stairs in eight rotations are one entry of 24,
 * not eight entries of 3. That is the granularity a materials list is read at, and the only one a
 * creative ordering is defined over (creative tabs contain items, not states). Air is excluded, so
 * the tally's values sum to exactly [blockCount].
 */
data class CapturedStructure(
    val tag: CompoundTag,
    val box: PlacedBox?,
    val blockCount: Int,
    val tally: Map<Block, Int>,
)
```

- [ ] **Step 4: Tally inside `captureAutoFitIn`**

Replace the body of `captureAutoFitIn` between the empty-scan guard and the `fit == null` guard:

```kotlin
        var blockCount = 0
        val tally = HashMap<Block, Int>()
        val fit = autoFit(scan.size.x, scan.size.y, scan.size.z) { lx, ly, lz ->
            val state = level.getBlockState(
                BlockPos(scan.origin.x + lx, scan.origin.y + ly, scan.origin.z + lz),
            )
            val nonAir = !state.`is`(Blocks.AIR)
            if (nonAir) {
                blockCount++
                tally.merge(state.block, 1, Int::plus)
            }
            nonAir
        }
```

Update the three `CapturedStructure(...)` constructions in this function:
- the two early returns become `CapturedStructure(template.save(CompoundTag()), null, 0, emptyMap())`
- the final return becomes `CapturedStructure(template.save(CompoundTag()), PlacedBox(tightOrigin, size), blockCount, tally)`

Add to the `captureAutoFitIn` KDoc, after the existing paragraphs:

```
     * The per-type tally rides the same walk that computes the auto-fit box, so it costs no extra
     * traversal. It is exact despite being counted over the whole *scan* volume rather than the
     * tight box: the tight box is by definition the bounding box of all non-air, so every counted
     * block lies inside it. This is the same reasoning that makes [CapturedStructure.blockCount]
     * correct.
```

- [ ] **Step 5: Add `tallyBlocksIn`**

Add to `StructurePersistence`, next to `hasNonAirBlocks`:

```kotlin
    /**
     * Tally non-air blocks by type across exactly [box].
     *
     * The place path's counterpart to the tally [captureAutoFitIn] produces for free: placing a
     * structure runs no scan at all, so without this a freshly placed structure would show an empty
     * materials list until its first edit committed. [box] is the `PlacedBox` returned by
     * [placeStructureCentered] — already the tight box — so this is the minimal possible scan,
     * thousands of positions rather than the ~8M of a region-wide walk.
     */
    fun tallyBlocksIn(level: ServerLevel, box: PlacedBox): Map<Block, Int> {
        val tally = HashMap<Block, Int>()
        for (x in 0 until box.size.x)
            for (y in 0 until box.size.y)
                for (z in 0 until box.size.z) {
                    val state = level.getBlockState(
                        BlockPos(box.origin.x + x, box.origin.y + y, box.origin.z + z),
                    )
                    if (!state.`is`(Blocks.AIR)) tally.merge(state.block, 1, Int::plus)
                }
        return tally
    }
```

- [ ] **Step 6: Fix the other `CapturedStructure` construction sites**

Run: `cmd.exe /c "cd /d H:\\Repo\\Garnet && gradlew.bat :26.2:classes :26.2:gametestClasses"`
Any other site constructing `CapturedStructure` positionally now fails to compile. Add `emptyMap()` as the fourth argument at each.

- [ ] **Step 7: Run tests to verify they pass**

Run: `cmd.exe /c "cd /d H:\\Repo\\Garnet && gradlew.bat :26.2:runGameTest"`
Expected: PASS, including the pre-existing `blockCount` tests (unchanged behavior).

- [ ] **Step 8: Commit**

```bash
git add src/main/kotlin/com/breadmoirai/garnet/structure/StructurePersistence.kt \
        src/gametest/kotlin/com/breadmoirai/garnet/test/structure/StructureRegionPersistenceSpec.kt
git commit -m "feat(structure): tally captured blocks by type"
```

---

### Task 2: The `StructureBlockTallyS2C` payload

**Files:**
- Modify: `src/main/kotlin/com/breadmoirai/garnet/editor/network/EditorPackets.kt`
- Modify: `src/main/kotlin/com/breadmoirai/garnet/editor/network/EditorNetworkRegistry.kt`

**Interfaces:**
- Consumes: `CapturedStructure.tally` and `StructurePersistence.tallyBlocksIn` (Task 1).
- Produces:
  - `BlockTallyEntry(val block: Holder<Block>, val count: Int)`
  - `StructureBlockTallyS2C(val subpath: String, val entries: List<BlockTallyEntry>)` with `TYPE` and `STREAM_CODEC`
  - `MAX_TALLY_ENTRIES = 4096`
  - `fun blockTallyPayload(subpath: String, tally: Map<Block, Int>): StructureBlockTallyS2C`

- [ ] **Step 1: Add the payload**

Append to the `// === Structure S2C ===` section of `EditorPackets.kt`, after `StructureAutoSavedS2C`:

```kotlin
/** One row of a structure's materials list: a block type and how many of it the structure contains. */
data class BlockTallyEntry(val block: Holder<Block>, val count: Int)

/**
 * Hard cap on tally entries, enforced by the codec on decode.
 *
 * There are fewer than this many block types in the game, so a well-formed payload can never reach
 * it; the cap exists so a malformed or hostile payload cannot make the client allocate without
 * bound before the list is validated.
 */
const val MAX_TALLY_ENTRIES = 4096

/**
 * The open structure's per-type block tally, for the Structure Info panel's materials list.
 *
 * Broadcast on both structure paths: alongside [StructureAutoSavedS2C] on every commit, and
 * alongside [StructureResultS2C] on a place. The place path matters because a freshly placed
 * structure has never been auto-saved — without it the list would stay empty until the first edit
 * committed.
 *
 * A payload of its own rather than two extra fields on those two: a variable-length list on both
 * would duplicate this codec and force churn on [StructureResultS2C], which has uses that want
 * nothing to do with a materials list.
 *
 * Blocks travel as numeric registry ids ([ByteBufCodecs.registry]) — compact, and they resolve
 * straight back to a [Block] client-side without a string lookup.
 */
data class StructureBlockTallyS2C(
    val subpath: String,
    val entries: List<BlockTallyEntry>,
) : CustomPacketPayload {
    companion object {
        val TYPE = CustomPacketPayload.Type<StructureBlockTallyS2C>(id("structure_block_tally"))

        private val ENTRY_CODEC: StreamCodec<RegistryFriendlyByteBuf, BlockTallyEntry> =
            StreamCodec.composite(
                ByteBufCodecs.holderRegistry(Registries.BLOCK), BlockTallyEntry::block,
                ByteBufCodecs.VAR_INT, BlockTallyEntry::count,
                ::BlockTallyEntry,
            )

        val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, StructureBlockTallyS2C> =
            StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, StructureBlockTallyS2C::subpath,
                ENTRY_CODEC.apply(ByteBufCodecs.list(MAX_TALLY_ENTRIES)), StructureBlockTallyS2C::entries,
                ::StructureBlockTallyS2C,
            )
    }
    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE
}

/**
 * Build the payload from a server-side tally. Entries are emitted in whatever order the map
 * iterates — the client sorts, so wire order carries no meaning and is deliberately not specified.
 */
fun blockTallyPayload(subpath: String, tally: Map<Block, Int>): StructureBlockTallyS2C =
    StructureBlockTallyS2C(
        subpath,
        tally.entries.take(MAX_TALLY_ENTRIES).map { (block, count) ->
            BlockTallyEntry(block.builtInRegistryHolder(), count)
        },
    )
```

Add these imports at the top of `EditorPackets.kt`:

```kotlin
import net.minecraft.core.Holder
import net.minecraft.core.registries.Registries
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.world.level.block.Block
```

- [ ] **Step 2: Register the payload**

In `EditorNetworkRegistry.kt`, after the `StructureAutoSavedS2C` registration:

```kotlin
        PayloadTypeRegistry.clientboundPlay().register(StructureBlockTallyS2C.TYPE, StructureBlockTallyS2C.STREAM_CODEC)
```

- [ ] **Step 3: Verify it compiles**

Run: `cmd.exe /c "cd /d H:\\Repo\\Garnet && gradlew.bat :26.2:classes"`
Expected: BUILD SUCCESSFUL.

If `ByteBufCodecs.holderRegistry` does not resolve, check the actual name in the sources jar and use whichever exists:

```bash
python3 -c "
import zipfile
z=zipfile.ZipFile('.gradle/loom-cache/minecraftMaven/net/minecraft/minecraft-common-a89b853bf9/26.2/minecraft-common-a89b853bf9-26.2-sources.jar')
d=z.read('net/minecraft/network/codec/ByteBufCodecs.java').decode()
print('\n'.join(l for l in d.split(chr(10)) if 'Registry' in l and 'static' in l))"
```

The other codecs in this file all use `StreamCodec<ByteBuf, ...>`; this one needs
`RegistryFriendlyByteBuf` because registry-id codecs require registry access on the buffer. That is
fine — `PayloadTypeRegistry.clientboundPlay()` accepts both.

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/com/breadmoirai/garnet/editor/network/EditorPackets.kt \
        src/main/kotlin/com/breadmoirai/garnet/editor/network/EditorNetworkRegistry.kt
git commit -m "feat(net): add StructureBlockTallyS2C"
```

---

### Task 3: Broadcast the tally from both server paths

**Files:**
- Modify: `src/main/kotlin/com/breadmoirai/garnet/editor/structure/StructureCommit.kt`
- Modify: `src/main/kotlin/com/breadmoirai/garnet/editor/network/EditorStructureHandlers.kt`
- Test: `src/gametest/kotlin/com/breadmoirai/garnet/test/editor/StructureAutoSaveSpec.kt`

**Interfaces:**
- Consumes: `blockTallyPayload`, `StructureBlockTallyS2C` (Task 2); `CapturedStructure.tally`, `tallyBlocksIn` (Task 1).
- Produces: `CommitOutcome.Committed` gains a `tally: StructureBlockTallyS2C` property; `StructureCommit.broadcast` sends it.

- [ ] **Step 1: Write the failing test**

Add to `StructureAutoSaveSpec.kt`, modelled on the existing `outcome.payload.blockCount` assertions:

```kotlin
    test("a commit carries a block tally alongside the auto-save payload") {
        // Reuse whichever fixture the neighbouring `outcome.payload.blockCount shouldBe 1` test in
        // this file uses to reach a CommitOutcome.Committed with exactly one tracked block, then:
        val outcome = /* the same commit call that test makes */
        outcome.shouldBeInstanceOf<CommitOutcome.Committed>()
        outcome.payload.blockCount shouldBe 1
        outcome.tally.subpath shouldBe outcome.payload.subpath
        outcome.tally.entries.sumOf { it.count } shouldBe 1
    }
```

Read the existing test around `outcome.payload.blockCount shouldBe 1` in this file and mirror its
setup exactly — it already builds a known-empty structure with one tracked block.

- [ ] **Step 2: Run to verify it fails**

Run: `cmd.exe /c "cd /d H:\\Repo\\Garnet && gradlew.bat :26.2:runGameTest"`
Expected: FAIL — `outcome.tally` unresolved.

- [ ] **Step 3: Carry the tally out of `commit`**

In `StructureCommit.kt`, find the `CommitOutcome` declaration (it is in this file or a sibling in the
same package) and add the property to `Committed`:

```kotlin
    data class Committed(
        val payload: StructureAutoSavedS2C,
        val tally: StructureBlockTallyS2C,
    ) : CommitOutcome()
```

Then update the final return of `commit`:

```kotlin
        return CommitOutcome.Committed(
            StructureAutoSavedS2C(
                subpath, size.x, size.y, size.z, captured.blockCount, System.currentTimeMillis(),
            ),
            blockTallyPayload(subpath, captured.tally),
        )
```

- [ ] **Step 4: Broadcast it**

Change `StructureCommit.broadcast`'s signature and body:

```kotlin
    fun broadcast(
        server: MinecraftServer,
        payload: StructureAutoSavedS2C,
        tally: StructureBlockTallyS2C,
        exclude: ServerPlayer? = null,
    ) {
        for (player in server.playerList.players) {
            if (player === exclude) continue
            // Unlike every other S2C here, this one is unsolicited -- it isn't a reply to a C2S, so
            // the receiver isn't provably running the mod. On a dedicated server, sending an unknown
            // play-phase payload to a vanilla/unmodded client can get it disconnected (F6).
            if (ServerPlayNetworking.canSend(player, StructureAutoSavedS2C.TYPE)) {
                ServerPlayNetworking.send(player, payload)
            }
            // Separately guarded: the two payloads are independent registrations, and a client that
            // can take one is not thereby proven to take the other.
            if (ServerPlayNetworking.canSend(player, StructureBlockTallyS2C.TYPE)) {
                ServerPlayNetworking.send(player, tally)
            }
        }
        HistoryWatchers.pushAll(server, payload.subpath)
    }
```

Update every `broadcast(...)` call site to pass the tally — compile errors will point at each. In
`handleSaveStructure`, the direct reply to the requesting player must also send the tally:

```kotlin
            is CommitOutcome.Committed -> {
                // This is a REPLY to the SaveStructureC2S `player` just sent -- they provably have
                // the mod, so these two sends need no canSend guard.
                ServerPlayNetworking.send(player, outcome.payload)
                ServerPlayNetworking.send(player, outcome.tally)
                StructureCommit.broadcast(server, outcome.payload, outcome.tally, exclude = player)
                // ... keep whatever else this branch already does
            }
```

- [ ] **Step 5: Send a tally on the place path**

In `EditorStructureHandlers.placeStructureFrom`, replace the existing `StructureResultS2C` send:

```kotlin
        ServerPlayNetworking.send(player, StructureResultS2C(
            subpath, placed.size.x, placed.size.y, placed.size.z, message,
        ))
        // A placed structure has never been auto-saved, so no commit tally exists for it yet.
        // Without this, the panel's materials list would stay empty until the first edit committed.
        // `placed` is already the tight box, so this is the minimal scan. Both sends are replies to
        // the C2S this player sent, so neither needs a canSend guard.
        ServerPlayNetworking.send(
            player,
            blockTallyPayload(subpath, StructurePersistence.tallyBlocksIn(level, placed)),
        )
```

Add the import:

```kotlin
import com.breadmoirai.garnet.editor.network.blockTallyPayload
```

(`StructurePersistence` is already imported in this file.)

- [ ] **Step 6: Run tests to verify they pass**

Run: `cmd.exe /c "cd /d H:\\Repo\\Garnet && gradlew.bat :26.2:runGameTest"`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add src/main/kotlin/com/breadmoirai/garnet/editor/
git add src/gametest/kotlin/com/breadmoirai/garnet/test/editor/StructureAutoSaveSpec.kt
git commit -m "feat(structure): broadcast the block tally on commit and place"
```

---

### Task 4: Client tally state

**Files:**
- Modify: `src/client/kotlin/com/breadmoirai/garnet/editor/ui/StructureInfoState.kt`
- Modify: `src/client/kotlin/com/breadmoirai/garnet/editor/network/EditorClientNetworking.kt`
- Test: `src/test/kotlin/com/breadmoirai/garnet/client/editor/ui/StructureInfoStateTest.kt`

**Interfaces:**
- Consumes: `StructureBlockTallyS2C`, `BlockTallyEntry` (Task 2).
- Produces: `StructureInfoState.blockTally: List<BlockTallyEntry>`, `StructureInfoState.onBlockTally(p: StructureBlockTallyS2C)`.

- [ ] **Step 1: Write the failing tests**

Add to `StructureInfoStateTest.kt`:

```kotlin
    test("a tally payload lands in blockTally") {
        StructureInfoState.reset()
        StructureInfoState.onBlockTally(
            StructureBlockTallyS2C("a/box.nbt", listOf(BlockTallyEntry(Blocks.GOLD_BLOCK.builtInRegistryHolder(), 7))),
        )
        StructureInfoState.blockTally.size shouldBe 1
        StructureInfoState.blockTally.first().count shouldBe 7
    }

    test("placing a structure clears the previous structure's tally") {
        StructureInfoState.reset()
        StructureInfoState.onBlockTally(
            StructureBlockTallyS2C("a/box.nbt", listOf(BlockTallyEntry(Blocks.GOLD_BLOCK.builtInRegistryHolder(), 7))),
        )
        StructureInfoState.onStructureResult(
            StructureResultS2C("b/other.nbt", 1, 1, 1, message = "placed b/other.nbt"),
        )
        StructureInfoState.blockTally.isEmpty() shouldBe true
    }

    test("reset clears the tally") {
        StructureInfoState.onBlockTally(
            StructureBlockTallyS2C("a/box.nbt", listOf(BlockTallyEntry(Blocks.GOLD_BLOCK.builtInRegistryHolder(), 7))),
        )
        StructureInfoState.reset()
        StructureInfoState.blockTally.isEmpty() shouldBe true
    }
```

Add imports:

```kotlin
import com.breadmoirai.garnet.editor.network.BlockTallyEntry
import com.breadmoirai.garnet.editor.network.StructureBlockTallyS2C
import net.minecraft.world.level.block.Blocks
```

- [ ] **Step 2: Run to verify it fails**

Run: `cmd.exe /c "cd /d H:\\Repo\\Garnet && gradlew.bat :26.2:test"`
Then read `versions/26.2/build/test-results/test/TEST-com.breadmoirai.garnet.client.editor.ui.StructureInfoStateTest.xml`.
Expected: compile failure — `onBlockTally` unresolved.

- [ ] **Step 3: Add the state**

In `StructureInfoState.kt`, add the field after `lastSavedMillis`:

```kotlin
    /**
     * The open structure's materials list, one entry per block type. Empty when unknown — a
     * structure whose tally has not arrived yet, or none open.
     */
    var blockTally by mutableStateOf<List<BlockTallyEntry>>(emptyList())
        private set
```

Add the receiver method next to `onAutoSaved`:

```kotlin
    fun onBlockTally(p: StructureBlockTallyS2C) { blockTally = p.entries }
```

Add `blockTally = emptyList()` to **both** `onStructureResult` (beside the existing
`blockCount = -1` reset, under the same comment about not carrying the previous structure's numbers
under the new one's name) and `reset()`.

Add imports for `BlockTallyEntry` and `StructureBlockTallyS2C`.

- [ ] **Step 4: Register the receiver**

In `EditorClientNetworking.kt`, after the `StructureAutoSavedS2C` receiver:

```kotlin
        ClientPlayNetworking.registerGlobalReceiver(StructureBlockTallyS2C.TYPE) { payload, ctx ->
            ctx.client().execute { StructureInfoState.onBlockTally(payload) }
        }
```

Add the import for `StructureBlockTallyS2C`.

- [ ] **Step 5: Run tests to verify they pass**

Run: `cmd.exe /c "cd /d H:\\Repo\\Garnet && gradlew.bat :26.2:test"`
Read the XML report for `StructureInfoStateTest`. Expected: all tests pass.

- [ ] **Step 6: Commit**

```bash
git add src/client/kotlin/com/breadmoirai/garnet/editor/ui/StructureInfoState.kt \
        src/client/kotlin/com/breadmoirai/garnet/editor/network/EditorClientNetworking.kt \
        src/test/kotlin/com/breadmoirai/garnet/client/editor/ui/StructureInfoStateTest.kt
git commit -m "feat(client): hold the structure block tally in StructureInfoState"
```

---

### Task 5: Sort modes and the comparator

**Files:**
- Create: `src/client/kotlin/com/breadmoirai/garnet/editor/ui/BlockSortMode.kt`
- Test: `src/test/kotlin/com/breadmoirai/garnet/client/editor/ui/BlockSortModeTest.kt`

**Interfaces:**
- Consumes: `BlockTallyEntry` (Task 2).
- Produces:
  - `enum class BlockSortMode { COUNT, CREATIVE }`
  - `BlockSortMode.next(): BlockSortMode`
  - `BlockSortMode.label: String`
  - `fun sortTally(entries: List<BlockTallyEntry>, mode: BlockSortMode, creativeIndex: (Block) -> Int): List<BlockTallyEntry>`
  - `object CreativeOrder { fun indexOf(block: Block): Int; fun invalidate() }`

- [ ] **Step 1: Write the failing test**

Create `src/test/kotlin/com/breadmoirai/garnet/client/editor/ui/BlockSortModeTest.kt`:

```kotlin
package com.breadmoirai.garnet.client.editor.ui

import com.breadmoirai.garnet.editor.network.BlockTallyEntry
import com.breadmoirai.garnet.editor.ui.BlockSortMode
import com.breadmoirai.garnet.editor.ui.sortTally
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks

/**
 * The comparator only. `CreativeOrder` itself needs a live client (creative tabs are built on
 * registry sync), so the index is injected here as a plain lambda.
 */
class BlockSortModeTest : FunSpec({

    val order = mapOf<Block, Int>(
        Blocks.STONE to 0,
        Blocks.GOLD_BLOCK to 1,
        Blocks.IRON_BLOCK to 2,
    )
    val index: (Block) -> Int = { order[it] ?: Int.MAX_VALUE }

    fun entry(block: Block, count: Int) = BlockTallyEntry(block.builtInRegistryHolder(), count)

    test("count sort is descending by count") {
        val sorted = sortTally(
            listOf(entry(Blocks.STONE, 1), entry(Blocks.GOLD_BLOCK, 9), entry(Blocks.IRON_BLOCK, 5)),
            BlockSortMode.COUNT, index,
        )
        sorted.map { it.count } shouldBe listOf(9, 5, 1)
    }

    test("count sort breaks ties on creative order, not input order") {
        val sorted = sortTally(
            listOf(entry(Blocks.IRON_BLOCK, 4), entry(Blocks.STONE, 4), entry(Blocks.GOLD_BLOCK, 4)),
            BlockSortMode.COUNT, index,
        )
        sorted.map { it.block.value() } shouldBe listOf(Blocks.STONE, Blocks.GOLD_BLOCK, Blocks.IRON_BLOCK)
    }

    test("creative sort ignores count entirely") {
        val sorted = sortTally(
            listOf(entry(Blocks.IRON_BLOCK, 99), entry(Blocks.STONE, 1)),
            BlockSortMode.CREATIVE, index,
        )
        sorted.map { it.block.value() } shouldBe listOf(Blocks.STONE, Blocks.IRON_BLOCK)
    }

    test("blocks outside the creative index sort last") {
        val sorted = sortTally(
            listOf(entry(Blocks.WATER, 3), entry(Blocks.STONE, 3)),
            BlockSortMode.COUNT, index,
        )
        sorted.map { it.block.value() } shouldBe listOf(Blocks.STONE, Blocks.WATER)
    }

    test("sorting is stable across two identical tallies") {
        val input = listOf(entry(Blocks.IRON_BLOCK, 4), entry(Blocks.STONE, 4), entry(Blocks.GOLD_BLOCK, 4))
        sortTally(input, BlockSortMode.COUNT, index) shouldBe sortTally(input.reversed(), BlockSortMode.COUNT, index)
    }

    test("next toggles between the two modes") {
        BlockSortMode.COUNT.next() shouldBe BlockSortMode.CREATIVE
        BlockSortMode.CREATIVE.next() shouldBe BlockSortMode.COUNT
    }
})
```

- [ ] **Step 2: Run to verify it fails**

Run: `cmd.exe /c "cd /d H:\\Repo\\Garnet && gradlew.bat :26.2:test"`
Expected: compile failure — `BlockSortMode` unresolved.

- [ ] **Step 3: Write the implementation**

Create `src/client/kotlin/com/breadmoirai/garnet/editor/ui/BlockSortMode.kt`:

```kotlin
package com.breadmoirai.garnet.editor.ui

import com.breadmoirai.garnet.editor.network.BlockTallyEntry
import net.minecraft.client.Minecraft
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.CreativeModeTabs
import net.minecraft.world.level.block.Block

/** How the Structure Info panel's materials list is ordered. Toggled by the panel's sort button. */
enum class BlockSortMode {
    COUNT,
    CREATIVE;

    /** The other mode. The sort control is a two-state toggle, so "next" is "the other one". */
    fun next(): BlockSortMode = if (this == COUNT) CREATIVE else COUNT

    /** Tooltip text. ASCII only -- see the panel's no-glyph rule. */
    val label: String get() = when (this) {
        COUNT -> "Sort: by count"
        CREATIVE -> "Sort: creative order"
    }
}

/**
 * Creative-inventory position per block, built once and memoized.
 *
 * The order comes from the creative search tab's display items, which is the true creative
 * sequence. Two things it cannot cover, both handled by falling back rather than failing:
 *
 * - **The tabs may not be built yet.** They are populated on registry sync, so a structure open
 *   before that would otherwise sort arbitrarily. An empty search tab falls back to registry order
 *   and is NOT memoized, so the real order is picked up once the tabs exist.
 * - **Some blocks have no item at all** (water, fire, wall torches), so they appear in no tab.
 *   They sort after everything that does, ordered among themselves by registry id.
 */
object CreativeOrder {
    private var cached: Map<Block, Int>? = null

    /** Drop the memo. Called on disconnect: a different server may sync a different registry. */
    fun invalidate() { cached = null }

    fun indexOf(block: Block): Int = index()[block] ?: (registryFallback(block) + REGISTRY_BASE)

    private fun index(): Map<Block, Int> {
        cached?.let { return it }
        val built = buildIndex()
        // Only memoize a real answer. An empty map means the tabs were not ready; caching that
        // would freeze the list into registry order for the rest of the session.
        if (built.isNotEmpty()) cached = built
        return built
    }

    private fun buildIndex(): Map<Block, Int> {
        // Guard the whole lookup: CreativeModeTabs touches the client's registry access and throws
        // if called too early. A throw here must degrade to registry order, not break the panel.
        val stacks = runCatching { CreativeModeTabs.searchTab().displayItems }.getOrNull()
            ?: return emptyMap()
        val out = LinkedHashMap<Block, Int>()
        for (stack in stacks) {
            val block = (stack.item as? BlockItem)?.block ?: continue
            // putIfAbsent: a block can appear in several stacks (different components); the FIRST
            // position is the one the creative inventory shows it at.
            out.putIfAbsent(block, out.size)
        }
        return out
    }

    private fun registryFallback(block: Block): Int = BuiltInRegistries.BLOCK.getId(block)

    /**
     * Offset that parks every unindexed block after every indexed one. Larger than any possible
     * creative index, so the two ranges cannot interleave.
     */
    private const val REGISTRY_BASE = 1 shl 20
}

/**
 * Order [entries] for display.
 *
 * [creativeIndex] is injected rather than read from [CreativeOrder] directly so the comparator is
 * testable without a live client -- creative tabs need a synced registry.
 *
 * Count order breaks ties on creative order. That tiebreak is not cosmetic: without it, equal-count
 * rows would reorder arbitrarily between refreshes and the list would visibly jitter on every
 * auto-save.
 */
fun sortTally(
    entries: List<BlockTallyEntry>,
    mode: BlockSortMode,
    creativeIndex: (Block) -> Int,
): List<BlockTallyEntry> = when (mode) {
    BlockSortMode.COUNT ->
        entries.sortedWith(
            compareByDescending<BlockTallyEntry> { it.count }
                .thenBy { creativeIndex(it.block.value()) },
        )
    BlockSortMode.CREATIVE ->
        entries.sortedBy { creativeIndex(it.block.value()) }
}

/** The panel's binding: the real creative index. */
fun sortTallyForDisplay(entries: List<BlockTallyEntry>, mode: BlockSortMode): List<BlockTallyEntry> =
    sortTally(entries, mode, CreativeOrder::indexOf)
```

Note: `Minecraft` is imported for symmetry with other files in this package but is not used by the
final code — remove the import if the compiler warns.

- [ ] **Step 4: Run tests to verify they pass**

Run: `cmd.exe /c "cd /d H:\\Repo\\Garnet && gradlew.bat :26.2:test"`
Read `versions/26.2/build/test-results/test/TEST-com.breadmoirai.garnet.client.editor.ui.BlockSortModeTest.xml`.
Expected: all 6 tests pass.

If `CreativeModeTabs.searchTab()` does not resolve, find the real accessor:

```bash
python3 -c "
import zipfile
z=zipfile.ZipFile('.gradle/loom-cache/minecraftMaven/net/minecraft/minecraft-common-a89b853bf9/26.2/minecraft-common-a89b853bf9-26.2-sources.jar')
d=z.read('net/minecraft/world/item/CreativeModeTabs.java').decode()
print('\n'.join(l for l in d.split(chr(10)) if 'static' in l and 'Tab' in l))"
```

- [ ] **Step 5: Wire `CreativeOrder.invalidate()` into disconnect**

Find where `StructureInfoState.reset()` is called on disconnect (grep for `reset()` in
`src/client/kotlin/`) and add `CreativeOrder.invalidate()` beside it.

- [ ] **Step 6: Commit**

```bash
git add src/client/kotlin/com/breadmoirai/garnet/editor/ui/BlockSortMode.kt \
        src/test/kotlin/com/breadmoirai/garnet/client/editor/ui/BlockSortModeTest.kt
git add src/client/kotlin/
git commit -m "feat(client): block-tally sort modes and creative ordering"
```

---

### Task 6: Sort-mode persistence

**Files:**
- Create: `src/client/kotlin/com/breadmoirai/garnet/config/PanelPrefsStore.kt`
- Test: `src/test/kotlin/com/breadmoirai/garnet/client/config/PanelPrefsStoreTest.kt`

**Interfaces:**
- Consumes: `BlockSortMode` (Task 5).
- Produces: `PanelPrefsStore.loadBlockSortMode(): BlockSortMode`, `PanelPrefsStore.saveBlockSortMode(mode: BlockSortMode)`, `PanelPrefsStore.configFileForTest(file: File)`, `PanelPrefsStore.resetConfigFileForTest()`.

- [ ] **Step 1: Write the failing test**

Create `src/test/kotlin/com/breadmoirai/garnet/client/config/PanelPrefsStoreTest.kt`:

```kotlin
package com.breadmoirai.garnet.client.config

import com.breadmoirai.garnet.config.PanelPrefsStore
import com.breadmoirai.garnet.editor.ui.BlockSortMode
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files

class PanelPrefsStoreTest : FunSpec({

    afterTest { PanelPrefsStore.resetConfigFileForTest() }

    test("a saved sort mode round-trips") {
        val file = Files.createTempFile("garnet-panel-prefs", ".json").toFile()
        PanelPrefsStore.configFileForTest(file)
        PanelPrefsStore.saveBlockSortMode(BlockSortMode.CREATIVE)
        PanelPrefsStore.loadBlockSortMode() shouldBe BlockSortMode.CREATIVE
    }

    test("an absent file loads the COUNT default") {
        val file = Files.createTempFile("garnet-panel-prefs", ".json").toFile()
        file.delete()
        PanelPrefsStore.configFileForTest(file)
        PanelPrefsStore.loadBlockSortMode() shouldBe BlockSortMode.COUNT
    }

    test("malformed JSON loads the COUNT default rather than throwing") {
        val file = Files.createTempFile("garnet-panel-prefs", ".json").toFile()
        file.writeText("{ not json at all")
        PanelPrefsStore.configFileForTest(file)
        PanelPrefsStore.loadBlockSortMode() shouldBe BlockSortMode.COUNT
    }

    test("an unknown mode name loads the COUNT default") {
        val file = Files.createTempFile("garnet-panel-prefs", ".json").toFile()
        file.writeText("""{"blockSortMode":"BY_VIBES"}""")
        PanelPrefsStore.configFileForTest(file)
        PanelPrefsStore.loadBlockSortMode() shouldBe BlockSortMode.COUNT
    }
})
```

- [ ] **Step 2: Run to verify it fails**

Run: `cmd.exe /c "cd /d H:\\Repo\\Garnet && gradlew.bat :26.2:test"`
Expected: compile failure — `PanelPrefsStore` unresolved.

- [ ] **Step 3: Write the implementation**

Create `src/client/kotlin/com/breadmoirai/garnet/config/PanelPrefsStore.kt`:

```kotlin
package com.breadmoirai.garnet.config

import com.breadmoirai.garnet.editor.ui.BlockSortMode
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import net.fabricmc.loader.api.FabricLoader
import org.slf4j.LoggerFactory
import java.io.File

private val LOGGER = LoggerFactory.getLogger("Garnet")

/**
 * The `config/garnet-panel-prefs.json` round-trip for dock panel display preferences.
 *
 * Today that is one field: the Structure Info panel's materials-list sort mode.
 *
 * **Why not `remember`.** A `remember` in the composable would respect the dock's mount rules but
 * reset the toggle every time the user switched panels on the stripe. For a control the user flips
 * deliberately, that is worse than useless.
 *
 * **Why not a top-level object.** `StructureInfoPanel`'s own doc records the hazard: the dock
 * composes into a long-lived singleton scene, so global panel state survives a re-mount and paints
 * over the next one (`DockState.mountEpoch`).
 *
 * **Why not `ModConfig`/`SharedSettings`.** That object's contract is a pure [SharedSettings]
 * round-trip with no shadow state, and it is read by the dedicated server — which must never see
 * client UI preferences. A separate file keeps that boundary intact, exactly as
 * [ExplorerStateStore] does.
 */
object PanelPrefsStore {
    private const val KEY_BLOCK_SORT_MODE = "blockSortMode"

    private val defaultFile: File
        get() = FabricLoader.getInstance().configDir.resolve("garnet-panel-prefs.json").toFile()

    private var overrideFile: File? = null
    private val configFile: File get() = overrideFile ?: defaultFile

    /** Test seam: redirect reads/writes at [file] instead of the real config directory. */
    fun configFileForTest(file: File) { overrideFile = file }
    fun resetConfigFileForTest() { overrideFile = null }

    /**
     * The persisted sort mode, or [BlockSortMode.COUNT] when there is none to restore — absent
     * file, malformed JSON, or an unrecognized mode name. A preference is a convenience, so every
     * failure degrades to the default rather than propagating.
     */
    fun loadBlockSortMode(): BlockSortMode {
        val file = configFile
        if (!file.exists()) return BlockSortMode.COUNT
        return runCatching {
            file.reader().use { reader ->
                val json = JsonParser.parseReader(reader) as? JsonObject ?: return@use null
                val name = json.get(KEY_BLOCK_SORT_MODE)?.asString ?: return@use null
                BlockSortMode.entries.firstOrNull { it.name == name }
            }
        }.onFailure { e ->
            LOGGER.warn("Failed to load panel prefs from {}", file.absolutePath, e)
        }.getOrNull() ?: BlockSortMode.COUNT
    }

    /** Overwrite the stored sort mode, preserving any other keys already in the file. */
    fun saveBlockSortMode(mode: BlockSortMode) {
        val file = configFile
        file.parentFile?.mkdirs()
        // Read-modify-write rather than clobber: this file is shared by every panel preference, so
        // writing a fresh object would silently drop keys other panels own.
        val json = runCatching {
            file.takeIf { it.exists() }?.reader()?.use { JsonParser.parseReader(it) as? JsonObject }
        }.getOrNull() ?: JsonObject()
        json.addProperty(KEY_BLOCK_SORT_MODE, mode.name)
        runCatching {
            file.writeText(json.toString())
        }.onFailure { e ->
            LOGGER.error("Failed to save panel prefs to {}", file.absolutePath, e)
        }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cmd.exe /c "cd /d H:\\Repo\\Garnet && gradlew.bat :26.2:test"`
Read `versions/26.2/build/test-results/test/TEST-com.breadmoirai.garnet.client.config.PanelPrefsStoreTest.xml`.
Expected: all 4 tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/client/kotlin/com/breadmoirai/garnet/config/PanelPrefsStore.kt \
        src/test/kotlin/com/breadmoirai/garnet/client/config/PanelPrefsStoreTest.kt
git commit -m "feat(client): persist the block-list sort mode"
```

---

### Task 7: The panel UI (text rows, no icons yet)

**Files:**
- Modify: `src/client/kotlin/com/breadmoirai/garnet/editor/ui/StructureInfoPanel.kt`

**Interfaces:**
- Consumes: `StructureInfoState.blockTally` (Task 4), `sortTallyForDisplay`, `BlockSortMode` (Task 5), `PanelPrefsStore` (Task 6).
- Produces: a `BlockRow` composable taking `(entry: BlockTallyEntry)`, extended in Task 9 to draw an icon.

This task delivers the whole feature minus icons — a working, scrollable, sortable materials list.

- [ ] **Step 1: Restructure the panel body**

In `StructureInfoPanel.kt`, replace the `StructureInfo()` composable body. The `Column` must give the
list `weight(1f)` or it will not scroll — same shape as `LocalHistoryPanel.RevisionList`:

```kotlin
@Composable
private fun StructureInfo() {
    IntUiTheme(isDark = true) {
        Column(Modifier.fillMaxSize().background(PANEL_BG).padding(4.dp)) {
            val subpath = StructureInfoState.subpath
            if (subpath == null) {
                Text("no structure open")
            } else {
                Text(subpath)
                Spacer(Modifier.height(6.dp))
                InfoRow("Size", "${StructureInfoState.sizeX} x ${StructureInfoState.sizeY} x ${StructureInfoState.sizeZ}")
                if (StructureInfoState.blockCount >= 0) {
                    InfoRow("Blocks", StructureInfoState.blockCount.toString())
                }
                if (StructureInfoState.lastSavedMillis > 0L) {
                    InfoRow("Saved", formatClock(StructureInfoState.lastSavedMillis))
                }
                val tally = StructureInfoState.blockTally
                if (tally.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    BlockList(tally)
                }
            }
            val status = StructureInfoState.status
            if (status.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(status)
            }
        }
    }
}
```

- [ ] **Step 2: Add the list and its header**

Append to `StructureInfoPanel.kt`:

```kotlin
/**
 * The materials list: a sort header over a scrolling one-row-per-block-type list.
 *
 * `ColumnScope` extension so the list can take [Modifier.weight] -- without it the LazyColumn has
 * unbounded height inside the panel's Column and never scrolls.
 *
 * The sort mode is `remember`-ed here rather than parked in a top-level object (the dock's
 * singleton-scene hazard -- see this file's panel doc), and seeded from / written through to
 * [PanelPrefsStore] so it survives both a panel switch and a restart.
 */
@Composable
private fun ColumnScope.BlockList(tally: List<BlockTallyEntry>) {
    var mode by remember { mutableStateOf(PanelPrefsStore.loadBlockSortMode()) }

    Row(Modifier.fillMaxWidth().padding(bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Blocks", Modifier.weight(1f))
        Text(tally.size.toString(), Modifier.padding(end = 4.dp))
        Tooltip(tooltip = { Text(mode.label) }) {
            IconButton(onClick = {
                val next = mode.next()
                mode = next
                PanelPrefsStore.saveBlockSortMode(next)
            }) {
                Icon(
                    key = when (mode) {
                        BlockSortMode.COUNT -> AllIconsKeys.ObjectBrowser.SortByUsage
                        BlockSortMode.CREATIVE -> AllIconsKeys.ObjectBrowser.SortByType
                    },
                    contentDescription = mode.label,
                )
            }
        }
    }

    // Keyed on both inputs: re-sorting a few hundred entries on every recomposition would run on
    // every unrelated panel repaint, including the status line ticking.
    val sorted = remember(tally, mode) { sortTallyForDisplay(tally, mode) }

    LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
        items(sorted, key = { it.block.value().descriptionId }) { entry ->
            BlockRow(entry)
        }
    }
}

/** One `<name>   <count>` materials row. */
@Composable
private fun BlockRow(entry: BlockTallyEntry) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 2.dp, vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(entry.block.value().name.string, Modifier.weight(1f))
        Text(entry.count.toString())
    }
}
```

Add these imports:

```kotlin
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import com.breadmoirai.garnet.config.PanelPrefsStore
import com.breadmoirai.garnet.editor.network.BlockTallyEntry
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.IconButton
import org.jetbrains.jewel.ui.component.Tooltip
```

- [ ] **Step 3: Update the panel KDoc**

Add to the `StructureInfo` KDoc, after the existing paragraphs:

```
 * The materials list below the fact rows is fed by `StructureBlockTallyS2C`, which the server sends
 * on both the commit and the place path -- so it is populated the moment a structure is open, not
 * only after the first auto-save. Its sort mode is the one piece of genuinely panel-local state
 * here: `remember`-ed in the composable per the rule above, and mirrored to `PanelPrefsStore` so a
 * panel switch or a restart does not silently reset a toggle the user set on purpose.
```

- [ ] **Step 4: Verify it compiles**

Run: `cmd.exe /c "cd /d H:\\Repo\\Garnet && gradlew.bat :26.2:clientClasses"`
Expected: BUILD SUCCESSFUL.

If `AllIconsKeys.ObjectBrowser.SortByUsage` does not resolve, list the real key names:

```bash
python3 -c "
import zipfile
p='/home/local/.gradle/caches/modules-2/files-2.1/org.jetbrains.jewel/jewel-ui/0.39.1-262.9437.29/e8112993db43239cb4912a5565a17069a3c7a30d/jewel-ui-0.39.1-262.9437.29.jar'
z=zipfile.ZipFile(p)
print([x for x in z.namelist() if 'ObjectBrowser' in x])"
```

The artwork artifact ships `expui/objectBrowser/sortByUsage.svg` and `sortByType.svg`, so keys for
both exist; only the Kotlin path may differ.

- [ ] **Step 5: Run the client test suite for regressions**

Run: `cmd.exe /c "cd /d H:\\Repo\\Garnet && gradlew.bat :26.2:runClientTest"`
Expected: existing dock/explorer specs still pass — the panel now renders more, but nothing that
existed changed shape.

- [ ] **Step 6: Commit**

```bash
git add src/client/kotlin/com/breadmoirai/garnet/editor/ui/StructureInfoPanel.kt
git commit -m "feat(ui): scrollable sortable block list in the Structure Info panel"
```

---

### Task 8: The block icon bridge

**Files:**
- Create: `src/client/kotlin/com/breadmoirai/garnet/ui/icon/BlockIconCache.kt`
- Create: `src/client/kotlin/com/breadmoirai/garnet/ui/icon/BlockIconRenderer.kt`
- Modify: `src/client/java/com/breadmoirai/garnet/mixin/client/MinecraftPresentMixin.java`

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces:
  - `BlockIconCache.iconFor(block: Block): ImageBitmap?` — returns the cached icon, enqueueing a render on first miss.
  - `BlockIconCache.disabled: Boolean`
  - `BlockIconCache.drain()` — render-thread pump, renders at most `MAX_PER_FRAME` queued icons.
  - `BlockIconCache.reset()` — test hook.
  - `BlockIconRenderer.render(block: Block, onDone: (ImageBitmap) -> Unit)`

**Reference implementation:** `net/minecraft/client/gui/render/GuiItemAtlas.java#drawToSlot` in the
26.2 clientOnly sources jar. Extract it before starting:

```bash
python3 -c "
import zipfile,os
z=zipfile.ZipFile('.gradle/loom-cache/minecraftMaven/net/minecraft/minecraft-clientOnly-a89b853bf9/26.2/minecraft-clientOnly-a89b853bf9-26.2-sources.jar')
z.extract('net/minecraft/client/gui/render/GuiItemAtlas.java', '/tmp/mcsrc')"
```

- [ ] **Step 1: Write the cache**

Create `src/client/kotlin/com/breadmoirai/garnet/ui/icon/BlockIconCache.kt`:

```kotlin
package com.breadmoirai.garnet.ui.icon

import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.graphics.ImageBitmap
import net.minecraft.world.level.block.Block
import org.slf4j.LoggerFactory

private val LOGGER = LoggerFactory.getLogger("Garnet")

/**
 * Session cache of rendered block icons, plus the bounded queue that feeds it.
 *
 * The map is Compose-observable, so a row that asked for a missing icon recomposes by itself once
 * the icon lands. Rows must render fine without one: the readback is asynchronous, so an icon is
 * never available on the frame it was first requested.
 *
 * **Why a queue and a budget.** Each icon costs an offscreen render plus a GPU->CPU readback. A
 * structure with 200 distinct materials rendered in one frame would visibly hitch; [MAX_PER_FRAME]
 * spreads it over the next second instead, which nobody notices.
 */
object BlockIconCache {
    /** Icons rendered per frame. Small enough that a full panel never costs a visible hitch. */
    private const val MAX_PER_FRAME = 4

    private val icons = mutableStateMapOf<Block, ImageBitmap>()
    private val queued = LinkedHashSet<Block>()
    private val inFlight = HashSet<Block>()

    /**
     * Set once the renderer or the readback throws. From then on [iconFor] always returns null and
     * the panel falls back to text-only rows permanently.
     *
     * Same contract as `ComposeSurface.disabled`: a missing icon is a cosmetic loss, and taking the
     * dock down over one would not be.
     */
    @Volatile
    var disabled: Boolean = false
        private set

    /** The icon for [block], or null if it is not rendered yet. Enqueues a render on first miss. */
    fun iconFor(block: Block): ImageBitmap? {
        if (disabled) return null
        icons[block]?.let { return it }
        if (block !in inFlight) queued.add(block)
        return null
    }

    /**
     * Render up to [MAX_PER_FRAME] queued icons. MUST be called on the render thread with a live GL
     * context -- it issues Blaze3D draws.
     */
    fun drain() {
        if (disabled || queued.isEmpty()) return
        var budget = MAX_PER_FRAME
        val iterator = queued.iterator()
        while (iterator.hasNext() && budget > 0) {
            val block = iterator.next()
            iterator.remove()
            budget--
            inFlight.add(block)
            try {
                BlockIconRenderer.render(block) { bitmap ->
                    icons[block] = bitmap
                    inFlight.remove(block)
                }
            } catch (t: Throwable) {
                kill(t)
                return
            }
        }
    }

    private fun kill(cause: Throwable) {
        disabled = true
        queued.clear()
        inFlight.clear()
        LOGGER.error("[block-icons] disabled after a render failure; falling back to text-only rows", cause)
    }

    /** Test/reset hook; also called on disconnect, since a resource reload invalidates every model. */
    fun reset() {
        icons.clear()
        queued.clear()
        inFlight.clear()
        disabled = false
    }
}
```

- [ ] **Step 2: Write the renderer**

Create `src/client/kotlin/com/breadmoirai/garnet/ui/icon/BlockIconRenderer.kt`. This mirrors
`GuiItemAtlas.drawToSlot` — read the extracted source alongside it.

```kotlin
package com.breadmoirai.garnet.ui.icon

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import com.mojang.blaze3d.GpuFormat
import com.mojang.blaze3d.ProjectionType
import com.mojang.blaze3d.pipeline.TextureTarget
import com.mojang.blaze3d.platform.Lighting
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.PoseStack
import net.minecraft.client.Minecraft
import net.minecraft.client.Screenshot
import net.minecraft.client.renderer.Projection
import net.minecraft.client.renderer.ProjectionMatrixBuffer
import net.minecraft.client.renderer.SubmitNodeStorage
import net.minecraft.client.renderer.item.ItemStackRenderState
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.world.item.ItemDisplayContext
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.Block
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ImageInfo
import org.joml.Vector4f

/**
 * Renders one block's inventory icon offscreen and reads it back to a Compose [ImageBitmap].
 *
 * **Why this exists at all.** MC 26.2 already rasterizes inventory icons, into `GuiItemAtlas` -- but
 * that atlas is frame-scoped (its `DynamicAtlasAllocator` reclaims slots and marks them `STALE`),
 * private to `GuiRenderer`, and GPU-only, while Compose needs CPU pixels. Its `drawToSlot` is
 * nonetheless the reference implementation for the render below, and the two should be diffed
 * whenever MC updates.
 *
 * Both MC entry points it needs are public, so no mixin is involved:
 * `Minecraft.getItemModelResolver()` and `GameRenderer.featureRenderDispatcher()`.
 */
object BlockIconRenderer {
    /** Icon edge in pixels. 32 rather than 16 so the panel's 16dp row icon stays sharp when the dock scales. */
    private const val SIZE = 32

    private var target: TextureTarget? = null
    private val poseStack = PoseStack()
    private val projection = Projection()
    private val submitNodeStorage = SubmitNodeStorage()
    private val projectionMatrixBuffer = ProjectionMatrixBuffer("garnet block icons")
    private val renderState = ItemStackRenderState()

    /**
     * Render [block]'s icon and hand the result to [onDone].
     *
     * [onDone] fires from the readback callback, which is a GPU download and therefore
     * **asynchronous** -- it does not run before this function returns. Callers must not treat the
     * icon as available on the frame they requested it.
     *
     * Throws on any Blaze3D/model failure; `BlockIconCache.drain` catches and disables.
     */
    fun render(block: Block, onDone: (ImageBitmap) -> Unit) {
        val mc = Minecraft.getInstance()
        val fbo = target ?: TextureTarget("garnet block icon", SIZE, SIZE, true, GpuFormat.RGBA8_UNORM)
            .also { target = it }

        mc.itemModelResolver.updateForTopItem(
            renderState, ItemStack(block), ItemDisplayContext.GUI, null, null, 0,
        )

        val colorView = requireNotNull(fbo.colorTextureView) { "icon target has no color view" }
        val depthView = requireNotNull(fbo.depthTextureView) { "icon target has no depth view" }
        val colorTexture = requireNotNull(fbo.colorTexture) { "icon target has no color texture" }
        val depthTexture = requireNotNull(fbo.depthTexture) { "icon target has no depth texture" }

        // Transparent clear: the panel background must show through the icon's empty corners.
        RenderSystem.getDevice().createCommandEncoder()
            .clearColorAndDepthTextures(colorTexture, Vector4f(0f, 0f, 0f, 0f), depthTexture, 0.0)

        poseStack.pushPose()
        try {
            // Centre the model in the target and flip Y, exactly as GuiItemAtlas.drawToSlot does.
            poseStack.translate(SIZE / 2.0f, SIZE / 2.0f, 0.0f)
            poseStack.scale(SIZE.toFloat(), -SIZE.toFloat(), SIZE.toFloat())

            RenderSystem.outputColorTextureOverride = colorView
            RenderSystem.outputDepthTextureOverride = depthView
            projection.setupOrtho(-1000.0f, 1000.0f, SIZE.toFloat(), SIZE.toFloat(), true)
            RenderSystem.setProjectionMatrix(
                projectionMatrixBuffer.getBuffer(projection), ProjectionType.ORTHOGRAPHIC,
            )
            // 3D block models and flat item sprites need different lighting; usesBlockLight() is how
            // GuiItemAtlas picks, and getting it wrong makes every block icon look flat-shaded.
            val lighting = if (renderState.usesBlockLight()) Lighting.Entry.ITEMS_3D else Lighting.Entry.ITEMS_FLAT
            mc.gameRenderer.lighting().setupFor(lighting)
            renderState.submit(poseStack, submitNodeStorage, 15728880, OverlayTexture.NO_OVERLAY, 0)
            mc.gameRenderer.featureRenderDispatcher().renderAllFeatures(submitNodeStorage)
        } finally {
            // Always restore, even on a throw: leaving these set redirects the NEXT frame's whole
            // render into our 32x32 icon target.
            RenderSystem.outputColorTextureOverride = null
            RenderSystem.outputDepthTextureOverride = null
            poseStack.popPose()
        }

        // Reuses the readback idiom already proven by CompositeTarget.captureToPng.
        Screenshot.takeScreenshot(fbo) { image ->
            try {
                onDone(image.toComposeImageBitmap())
            } finally {
                image.close()
            }
        }
    }
}

/**
 * `NativeImage` -> Compose [ImageBitmap].
 *
 * Two conversions, both mandatory and both easy to miss:
 * - **Row order.** A Blaze3D render-target readback arrives bottom-up (see
 *   `docs/minecraft/blaze3d-custom-blit-pipeline-26.md`), so rows are copied in reverse.
 * - **Channel order.** `NativeImage.getPixel` returns ABGR; Skia's `BGRA_8888` wants ARGB, so R and
 *   B swap. Skipping this renders every icon with its colours inverted, which reads as "wrong
 *   texture" rather than "wrong byte order" and is a genuinely confusing bug to chase.
 */
private fun com.mojang.blaze3d.platform.NativeImage.toComposeImageBitmap(): ImageBitmap {
    val w = width
    val h = height
    val pixels = IntArray(w * h)
    for (y in 0 until h) {
        for (x in 0 until w) {
            val abgr = getPixel(x, y)
            val a = (abgr ushr 24) and 0xFF
            val b = (abgr ushr 16) and 0xFF
            val g = (abgr ushr 8) and 0xFF
            val r = abgr and 0xFF
            pixels[(h - 1 - y) * w + x] = (a shl 24) or (r shl 16) or (g shl 8) or b
        }
    }
    val bytes = ByteArray(w * h * 4)
    for (i in pixels.indices) {
        val p = pixels[i]
        bytes[i * 4] = (p and 0xFF).toByte()
        bytes[i * 4 + 1] = ((p ushr 8) and 0xFF).toByte()
        bytes[i * 4 + 2] = ((p ushr 16) and 0xFF).toByte()
        bytes[i * 4 + 3] = ((p ushr 24) and 0xFF).toByte()
    }
    val bitmap = Bitmap()
    bitmap.allocPixels(ImageInfo.makeN32(w, h, ColorAlphaType.UNPREMUL))
    bitmap.installPixels(bytes)
    return bitmap.asComposeImageBitmap()
}
```

- [ ] **Step 3: Pump the queue from the render thread**

In `MinecraftPresentMixin.java`, add the drain **before** the composite is built — item rendering
sets and clears `RenderSystem.outputColorTextureOverride`, so it must not interleave with the
composite's own target work. Insert immediately after the `realWidth`/`realHeight` guard block, just
before `ViewportState.ContentRect rect = ...`:

```java
        // Render-thread pump for the Structure Info panel's block icons. Deliberately BEFORE the
        // composite is built: the icon renderer sets RenderSystem output overrides, which must not
        // interleave with the composite's own target work below. Fully guarded -- BlockIconCache
        // disables itself on failure, and this backstop keeps a throw out of present regardless.
        try {
            BlockIconCache.INSTANCE.drain();
        } catch (Throwable iconFailure) {
            // BlockIconCache logs and disables internally; nothing to do here.
        }
```

Add the import:

```java
import com.breadmoirai.garnet.ui.icon.BlockIconCache;
```

- [ ] **Step 4: Reset the cache on disconnect**

Beside the existing `StructureInfoState.reset()` disconnect call (Task 5, Step 5), add
`BlockIconCache.reset()`. A resource reload rebuilds every model, so a cached icon can outlive the
model it was rendered from.

- [ ] **Step 5: Verify it compiles**

Run: `cmd.exe /c "cd /d H:\\Repo\\Garnet && gradlew.bat :26.2:clientClasses"`
Expected: BUILD SUCCESSFUL.

Likely resolution failures and how to check the real names:

```bash
# TextureTarget getters, Projection.setupOrtho, ItemStackRenderState.submit/usesBlockLight
python3 -c "
import zipfile
z=zipfile.ZipFile('.gradle/loom-cache/minecraftMaven/net/minecraft/minecraft-clientOnly-a89b853bf9/26.2/minecraft-clientOnly-a89b853bf9-26.2-sources.jar')
for f in ['net/minecraft/client/renderer/item/ItemStackRenderState.java','net/minecraft/client/renderer/Projection.java']:
    d=z.read(f).decode()
    print('==',f)
    print('\n'.join(l for l in d.split(chr(10)) if 'public' in l))"
```

`fbo.colorTextureView` / `depthTextureView`: `CompositeTarget` and `MinecraftPresentMixin` already use
`getColorTextureView()`; check `RenderTarget` for the depth equivalent's real name.

- [ ] **Step 6: Commit**

```bash
git add src/client/kotlin/com/breadmoirai/garnet/ui/icon/ \
        src/client/java/com/breadmoirai/garnet/mixin/client/MinecraftPresentMixin.java
git add src/client/kotlin/
git commit -m "feat(ui): offscreen block icon rendering and readback"
```

---

### Task 9: Wire icons into the rows

**Files:**
- Modify: `src/client/kotlin/com/breadmoirai/garnet/editor/ui/StructureInfoPanel.kt`
- Test: `src/clientTest/kotlin/com/breadmoirai/garnet/test/BlockListPanelSpec.kt`

**Interfaces:**
- Consumes: `BlockIconCache.iconFor` (Task 8), `BlockRow` (Task 7).
- Produces: nothing further.

- [ ] **Step 1: Draw the icon in `BlockRow`**

Replace `BlockRow` in `StructureInfoPanel.kt`:

```kotlin
/**
 * One `<icon> <name>   <count>` materials row.
 *
 * The icon slot is a fixed-size `Box` that is reserved whether or not an icon exists: the readback
 * is asynchronous, so no icon is available on the frame a row first asks for one, and a row that
 * grew when its icon landed would make the whole list jump. Text never waits on the GPU -- if icons
 * are disabled outright, every row simply keeps an empty slot.
 */
@Composable
private fun BlockRow(entry: BlockTallyEntry) {
    val block = entry.block.value()
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 2.dp, vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(16.dp)) {
            BlockIconCache.iconFor(block)?.let { icon ->
                Image(
                    bitmap = icon,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    filterQuality = FilterQuality.None,
                )
            }
        }
        Spacer(Modifier.width(4.dp))
        Text(block.name.string, Modifier.weight(1f))
        Text(entry.count.toString())
    }
}
```

Add these imports:

```kotlin
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.graphics.FilterQuality
import com.breadmoirai.garnet.ui.icon.BlockIconCache
```

`filterQuality = FilterQuality.None` keeps MC's pixel art crisp — the default bilinear filter blurs a
32px icon scaled to 16dp into mush.

- [ ] **Step 2: Write the client test**

Create `src/clientTest/kotlin/com/breadmoirai/garnet/test/BlockListPanelSpec.kt`. Read
`JewelExplorerSpec.kt` first and mirror its fixture setup, panel-opening helpers, and
`PanelPixelProbe` usage — the harness details are not repeated here.

```kotlin
package com.breadmoirai.garnet.test

import com.breadmoirai.garnet.editor.network.BlockTallyEntry
import com.breadmoirai.garnet.editor.network.StructureBlockTallyS2C
import com.breadmoirai.garnet.editor.network.StructureResultS2C
import com.breadmoirai.garnet.editor.ui.BlockSortMode
import com.breadmoirai.garnet.editor.ui.StructureInfoState
import com.breadmoirai.garnet.config.PanelPrefsStore
import com.breadmoirai.garnet.ui.icon.BlockIconCache
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import net.minecraft.world.level.block.Blocks

/**
 * The Structure Info panel's materials list, end to end in a live client: a tally lands, the list
 * renders, the sort toggle persists, and an icon actually arrives from the GPU.
 */
class BlockListPanelSpec : /* the base class JewelExplorerSpec uses */ {

    // Open the Structure Info panel using the same stripe helper JewelExplorerSpec uses for the
    // Explorer, then:

    // 1. A tally renders as rows.
    onClient {
        StructureInfoState.onStructureResult(
            StructureResultS2C("a/box.nbt", 2, 1, 3, message = "placed a/box.nbt"),
        )
        StructureInfoState.onBlockTally(StructureBlockTallyS2C("a/box.nbt", listOf(
            BlockTallyEntry(Blocks.GOLD_BLOCK.builtInRegistryHolder(), 7),
            BlockTallyEntry(Blocks.STONE.builtInRegistryHolder(), 3),
        )))
    }
    awaitTicks(2)
    onClient { StructureInfoState.blockTally.size shouldBe 2 }

    // 2. An icon arrives. The readback is asynchronous and the pump does at most 4 per frame, so
    //    this must wait several frames -- asserting on the same tick always fails.
    awaitTicks(20)
    onClient {
        BlockIconCache.disabled shouldBe false
        BlockIconCache.iconFor(Blocks.GOLD_BLOCK) shouldNotBe null
    }

    // 3. The sort mode persists across a panel switch. Toggle it, close and reopen the panel via
    //    the stripe, and read it back from the store.
    onClient {
        PanelPrefsStore.saveBlockSortMode(BlockSortMode.CREATIVE)
        PanelPrefsStore.loadBlockSortMode() shouldBe BlockSortMode.CREATIVE
    }

    // 4. Disabled icons still render rows. There is no public setter for `disabled` by design, so
    //    cover this by asserting the panel renders non-blank BEFORE any icon has arrived --
    //    the same state a permanently-disabled cache leaves the panel in.
}
```

Convert the sketch above into the spec style `JewelExplorerSpec` actually uses (its `test("...") { }`
blocks and its client-thread helpers). Keep every assertion and every comment.

- [ ] **Step 3: Run the client tests**

Run: `cmd.exe /c "cd /d H:\\Repo\\Garnet && gradlew.bat :26.2:runClientTest"`
Expected: PASS.

If the icon assertion fails with `disabled == true`, read the logged stack trace — that is the
renderer failing, and the API-name checks in Task 8 Step 5 are where to look.

- [ ] **Step 4: Full verification**

```bash
cmd.exe /c "cd /d H:\\Repo\\Garnet && gradlew.bat :26.2:clientClasses :26.2:classes :26.2:gametestClasses :26.2:clientTestClasses :26.2:testClasses"
cmd.exe /c "cd /d H:\\Repo\\Garnet && gradlew.bat :26.2:test"
cmd.exe /c "cd /d H:\\Repo\\Garnet && gradlew.bat :26.2:runGameTest"
cmd.exe /c "cd /d H:\\Repo\\Garnet && gradlew.bat :26.2:runClientTest"
```

All four must pass. Read the JUnit XML for `:26.2:test` rather than trusting console output.

- [ ] **Step 5: Commit**

```bash
git add src/client/kotlin/com/breadmoirai/garnet/editor/ui/StructureInfoPanel.kt \
        src/clientTest/kotlin/com/breadmoirai/garnet/test/BlockListPanelSpec.kt
git commit -m "feat(ui): draw block icons in the materials list"
```

---

### Task 10: Documentation

**Files:**
- Modify: `docs/ui/structure-info-panel.md`
- Create: `docs/ui/block-icon-bridge.md`
- Modify: `docs/ui/INDEX.md`
- Modify: `docs/persistence/INDEX.md` and the relevant payload article

Per `CLAUDE.md`, this step is mandatory, not optional.

- [ ] **Step 1: Extend the Structure Info panel article**

Add sections to `docs/ui/structure-info-panel.md` covering: the materials list and why it is fed on
both the commit and place paths; `Block`-not-`BlockState` counting and the waterlogging rule; the two
sort modes and why count ties break on creative order; and the persistence boundary (why the sort
mode is neither `remember`-only nor in `SharedSettings`). Update its frontmatter `summary`.

- [ ] **Step 2: Write the icon-bridge article**

Create `docs/ui/block-icon-bridge.md` with the standard frontmatter:

```
---
title: Block icons in Compose — offscreen render and readback
tags: [compose, blaze3d, icons, readback, gpu, jewel, dock]
summary: How the Structure Info materials list gets real inventory icons into Compose — the GuiItemAtlas reference implementation, the two public MC entry points, the async readback, the ABGR/bottom-up conversion, the per-frame budget, and the disabled fallback.
---
```

Cover: why `GuiItemAtlas` cannot be used directly (frame-scoped, private, GPU-only); why the
zero-copy Skia route was rejected (`GlStateStash`'s GL-state hazard); the two public entry points;
the render sequence and the mandatory output-override restore; the asynchronous readback; **both**
pixel conversions and the confusing bug each prevents; the `MAX_PER_FRAME` budget and why it exists;
and the `disabled` fallback contract.

- [ ] **Step 3: Register in the indexes**

Add to `docs/ui/INDEX.md`:

```
- [Block icons in Compose — offscreen render and readback](block-icon-bridge.md) — Real inventory icons in the dock's Compose panels: why `GuiItemAtlas` can't be reused, the offscreen render mirroring its `drawToSlot`, the async `Screenshot.takeScreenshot` readback, the ABGR + bottom-up conversion, the 4-icons-per-frame budget, and the disabled-falls-back-to-text contract. _[compose, blaze3d, icons, readback, gpu, jewel, dock]_
```

Update the existing `structure-info-panel.md` line's summary to mention the materials list.

- [ ] **Step 4: Document the payload**

Find the persistence article covering the S2C payloads (`grep -rn "StructureAutoSavedS2C" docs/`) and
add `StructureBlockTallyS2C`: what it carries, that it is broadcast on both paths, why it is a
separate payload rather than fields on the two existing ones, and the registry-id encoding with its
`MAX_TALLY_ENTRIES` cap. Update `docs/persistence/INDEX.md` if the summary changes.

- [ ] **Step 5: Verify no stale references**

```bash
grep -rn "CapturedStructure" docs/ | grep -v superpowers
```

Any article describing `CapturedStructure`'s shape now needs the fourth field. Fix each hit.

- [ ] **Step 6: Commit**

```bash
git add docs/
git commit -m "docs(ui): the block list and the block icon bridge"
```

---

## Self-Review

**Spec coverage.** Spec §1 → Task 1. §2 → Tasks 2 and 3. §3 → Tasks 4, 5, 6. §4 → Task 8. §5 → Tasks 7 and 9. §6 → tests in Tasks 1, 3, 4, 5, 6, 9. §7 → Task 10. The rejected alternatives in the spec's Scope section are carried into the Task 8 and Task 10 prose so the reasoning survives.

**Ordering.** The feature is fully usable at the end of Task 7 (text-only rows). Task 8, the riskiest and largest piece, layers on top without any earlier task depending on it — so it can be deferred or reverted independently.

**Type consistency.** `tally: Map<Block, Int>` is used identically in Tasks 1, 2, 3. `BlockTallyEntry(block: Holder<Block>, count: Int)` is constructed in Task 2 and read as `entry.block.value()` in Tasks 5, 7, 9. `sortTally` takes its creative index as a lambda in Task 5 and is called through `sortTallyForDisplay` in Task 7. `BlockIconCache.iconFor` returns `ImageBitmap?` in Task 8 and is consumed as nullable in Task 9. `CommitOutcome.Committed` gains `tally` in Task 3 and is read as `outcome.tally` in the same task's test.

**Known-uncertain API names**, each with an inline verification command at the point of use: `ByteBufCodecs.holderRegistry` (Task 2 Step 3), `AllIconsKeys.ObjectBrowser.*` (Task 7 Step 4), `CreativeModeTabs.searchTab()` (Task 5 Step 4), and the `TextureTarget`/`ItemStackRenderState`/`Projection` members (Task 8 Step 5). Every one degrades to a compile error, not a silent runtime bug.
