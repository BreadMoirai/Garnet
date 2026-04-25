# Three-Block Spec Workflow Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the single `RedstoneSpecBlock` with three blocks — Runner, Editor, Recorder — that transform between each other while preserving spec data on a shared `BlockEntity`.

**Spec:** `docs/superpowers/specs/2026-04-25-three-block-spec-workflow-design.md`

**Architecture:** Three `Block` classes share one `BlockEntity` class (`SpecBlockEntity`, renamed from `RedstoneSpecBlockEntity`). Transformation = `Level.setBlock` to a new block class; the BE survives because all three blocks declare the same `BlockEntityType`. Recorder uses existing `StateRecorder`/`StateRecording` for capture; a new `RecordingFinalizer` produces a `RedstoneSpec` from a finished recording.

**Tech Stack:** Kotlin, Fabric for MC 26.1, JUnit 5, gametest framework. Build via `cmd.exe /c "./gradlew.bat ..."`. Stonecutter task path is `:26.1:...`.

**Build verification command (full):**
```bash
cmd.exe /c "./gradlew.bat :26.1:clientClasses :26.1:classes :26.1:gameTestClasses :26.1:testClasses"
```

**Test run command:**
```bash
cmd.exe /c "./gradlew.bat :26.1:test"
```

---

## File Map

**New files:**
- `src/main/kotlin/com/breadmoirai/redstonespecs/block/SpecBlockEntity.kt` (renamed from `RedstoneSpecBlockEntity.kt`, generalized)
- `src/main/kotlin/com/breadmoirai/redstonespecs/block/RedstoneSpecRunnerBlock.kt`
- `src/main/kotlin/com/breadmoirai/redstonespecs/block/RedstoneSpecEditorBlock.kt`
- `src/main/kotlin/com/breadmoirai/redstonespecs/block/RedstoneSpecRecorderBlock.kt`
- `src/main/kotlin/com/breadmoirai/redstonespecs/runner/RecordingFinalizer.kt`
- `src/test/kotlin/com/breadmoirai/redstonespecs/runner/RecordingFinalizerTest.kt`
- `src/main/resources/assets/redstonespecs/models/block/redstone_spec_runner.json`
- `src/main/resources/assets/redstonespecs/models/block/redstone_spec_editor.json`
- `src/main/resources/assets/redstonespecs/models/block/redstone_spec_recorder.json`
- `src/main/resources/assets/redstonespecs/models/item/redstone_spec_runner.json`
- `src/main/resources/assets/redstonespecs/models/item/redstone_spec_editor.json`
- `src/main/resources/assets/redstonespecs/models/item/redstone_spec_recorder.json`
- `src/main/resources/assets/redstonespecs/blockstates/redstone_spec_runner.json`
- `src/main/resources/assets/redstonespecs/blockstates/redstone_spec_editor.json`
- `src/main/resources/assets/redstonespecs/blockstates/redstone_spec_recorder.json`
- `src/main/resources/assets/redstonespecs/lang/en_us.json` entries (modify existing if present)

**Removed files:**
- `src/main/kotlin/com/breadmoirai/redstonespecs/block/RedstoneSpecBlock.kt`
- `src/main/kotlin/com/breadmoirai/redstonespecs/block/RedstoneSpecBlockEntity.kt`
- `src/main/resources/assets/redstonespecs/models/block/redstone_spec.json`
- `src/main/resources/assets/redstonespecs/models/item/redstone_spec.json`
- `src/main/resources/assets/redstonespecs/blockstates/redstone_spec.json`
- Existing `redstone_spec` texture file (kept if used as fallback for editor; otherwise removed)

**Modified files:**
- `src/main/kotlin/com/breadmoirai/redstonespecs/ModRegistries.kt`
- `src/main/kotlin/com/breadmoirai/redstonespecs/item/SpecMarkerTool.kt`
- `src/main/kotlin/com/breadmoirai/redstonespecs/network/Packets.kt`
- `src/main/kotlin/com/breadmoirai/redstonespecs/network/NetworkRegistry.kt`
- `src/main/kotlin/com/breadmoirai/redstonespecs/Redstonespecs.kt` (server-side packet handlers)
- `src/client/kotlin/com/breadmoirai/redstonespecs/client/RedstonespecsClient.kt` (screen registration, S2C handlers)
- `src/client/kotlin/com/breadmoirai/redstonespecs/client/screen/SpecOverviewScreen.kt` (Save/Discard, read-only mode)
- `src/client/kotlin/com/breadmoirai/redstonespecs/client/network/ClientNetworkHandler.kt`
- `src/client/kotlin/com/breadmoirai/redstonespecs/client/render/RedstoneSpecBoundsRenderer.kt`
- Any other file importing `RedstoneSpecBlockEntity` or `RedstoneSpecBlock`

---

## Phase 1 — BE generalization & block split (no behavior change)

The goal of Phase 1 is to land a refactor that compiles and behaves identically to today, just with three blocks instead of one. The Editor is the "real" current block; Runner and Recorder are placeholder clones that will diverge in later phases.

### Task 1.1 — Rename `RedstoneSpecBlockEntity` → `SpecBlockEntity`

**Files:**
- Rename: `src/main/kotlin/com/breadmoirai/redstonespecs/block/RedstoneSpecBlockEntity.kt` → `SpecBlockEntity.kt`
- Modify call sites: any file importing `RedstoneSpecBlockEntity`

- [ ] **Step 1: Rename the file and class**

```bash
git mv src/main/kotlin/com/breadmoirai/redstonespecs/block/RedstoneSpecBlockEntity.kt \
       src/main/kotlin/com/breadmoirai/redstonespecs/block/SpecBlockEntity.kt
```

- [ ] **Step 2: Rename the class symbol**

In `SpecBlockEntity.kt`, change `class RedstoneSpecBlockEntity` to `class SpecBlockEntity`. Update the `companion object` references and `findFor`/`allFor` signatures.

Add a `transformTo` helper at the bottom of the class body:

```kotlin
fun transformTo(targetBlock: Block) {
    val lv = level ?: return
    if (lv.isClientSide) return
    val newState = targetBlock.defaultBlockState()
    // Use flag 3 (notify+sync) and skip neighbor updates flag 32? Default flag=3 OK.
    lv.setBlock(blockPos, newState, 3)
}
```

- [ ] **Step 3: Update all references**

Run a project-wide find/replace from `RedstoneSpecBlockEntity` to `SpecBlockEntity`. Files known to reference it:
- `ModRegistries.kt`
- `SpecMarkerTool.kt`
- `client/render/RedstoneSpecBoundsRenderer.kt`
- `client/render/HudOverlayRenderer.kt`
- `client/screen/SpecOverviewScreen.kt`
- `client/screen/SpecBoundsScreen.kt`
- `Redstonespecs.kt` (server packet handlers)
- `client/network/ClientNetworkHandler.kt`
- `gametest/.../RedstonespecsClientTests.kt`
- `gametest/.../SpecTestContext.kt`

Use grep to find all sites:

```bash
grep -rln "RedstoneSpecBlockEntity" src/
```

- [ ] **Step 4: Verify build**

```bash
cmd.exe /c "./gradlew.bat :26.1:clientClasses :26.1:classes :26.1:gameTestClasses :26.1:testClasses"
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "refactor: rename RedstoneSpecBlockEntity to SpecBlockEntity"
```

---

### Task 1.2 — Create three Block classes

**Files:**
- Create `block/RedstoneSpecRunnerBlock.kt`
- Create `block/RedstoneSpecEditorBlock.kt`
- Create `block/RedstoneSpecRecorderBlock.kt`
- Will delete `block/RedstoneSpecBlock.kt` in next task

Each is a near-identical clone of the current `RedstoneSpecBlock` for now. Behavior diverges in later phases.

- [ ] **Step 1: Create `RedstoneSpecEditorBlock.kt`** (the "real" current behavior — opens overview on right-click)

```kotlin
package com.breadmoirai.redstonespecs.block

import com.breadmoirai.redstonespecs.data.RedstoneSpec
import com.breadmoirai.redstonespecs.network.OpenOverviewS2CPayload
import com.mojang.serialization.MapCodec
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.BaseEntityBlock
import net.minecraft.world.level.block.RenderShape
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.BlockEntityTicker
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.BlockHitResult

class RedstoneSpecEditorBlock(properties: Properties) : BaseEntityBlock(properties) {
    override fun codec(): MapCodec<out BaseEntityBlock> = CODEC
    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity =
        SpecBlockEntity(pos, state)
    override fun getRenderShape(state: BlockState): RenderShape = RenderShape.MODEL

    override fun useWithoutItem(
        state: BlockState, level: Level, pos: BlockPos, player: Player, hit: BlockHitResult,
    ): InteractionResult {
        if (!level.isClientSide) {
            val be = level.getBlockEntity(pos) as? SpecBlockEntity ?: return InteractionResult.PASS
            val serverPlayer = player as ServerPlayer
            if (be.spec == null) {
                val defaultId = serverPlayer.gameProfile.name.lowercase().replace(" ", "_") + "_spec"
                be.setSpec(RedstoneSpec.new(defaultId))
            }
            ServerPlayNetworking.send(serverPlayer, OpenOverviewS2CPayload(be.blockPos))
        }
        return InteractionResult.SUCCESS
    }

    override fun <T : BlockEntity> getTicker(
        level: Level, state: BlockState, type: BlockEntityType<T>,
    ): BlockEntityTicker<T>? = null

    companion object {
        val CODEC: MapCodec<RedstoneSpecEditorBlock> = simpleCodec(::RedstoneSpecEditorBlock)
    }
}
```

- [ ] **Step 2: Create `RedstoneSpecRunnerBlock.kt`** (placeholder — opens overview, will become read-only in Phase 4)

```kotlin
package com.breadmoirai.redstonespecs.block

import com.breadmoirai.redstonespecs.network.OpenOverviewS2CPayload
import com.mojang.serialization.MapCodec
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.BaseEntityBlock
import net.minecraft.world.level.block.RenderShape
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.BlockEntityTicker
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.BlockHitResult

class RedstoneSpecRunnerBlock(properties: Properties) : BaseEntityBlock(properties) {
    override fun codec(): MapCodec<out BaseEntityBlock> = CODEC
    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity =
        SpecBlockEntity(pos, state)
    override fun getRenderShape(state: BlockState): RenderShape = RenderShape.MODEL

    override fun useWithoutItem(
        state: BlockState, level: Level, pos: BlockPos, player: Player, hit: BlockHitResult,
    ): InteractionResult {
        if (!level.isClientSide) {
            val be = level.getBlockEntity(pos) as? SpecBlockEntity ?: return InteractionResult.PASS
            // Phase 4 will replace this with picker-or-readonly logic.
            ServerPlayNetworking.send(player as ServerPlayer, OpenOverviewS2CPayload(be.blockPos))
        }
        return InteractionResult.SUCCESS
    }

    override fun <T : BlockEntity> getTicker(
        level: Level, state: BlockState, type: BlockEntityType<T>,
    ): BlockEntityTicker<T>? = null

    companion object {
        val CODEC: MapCodec<RedstoneSpecRunnerBlock> = simpleCodec(::RedstoneSpecRunnerBlock)
    }
}
```

- [ ] **Step 3: Create `RedstoneSpecRecorderBlock.kt`** (placeholder — opens overview, will get its own GUI + redstone in Phase 5)

```kotlin
package com.breadmoirai.redstonespecs.block

import com.breadmoirai.redstonespecs.data.RedstoneSpec
import com.breadmoirai.redstonespecs.network.OpenOverviewS2CPayload
import com.mojang.serialization.MapCodec
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.BaseEntityBlock
import net.minecraft.world.level.block.RenderShape
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.BlockEntityTicker
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.BlockHitResult

class RedstoneSpecRecorderBlock(properties: Properties) : BaseEntityBlock(properties) {
    override fun codec(): MapCodec<out BaseEntityBlock> = CODEC
    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity =
        SpecBlockEntity(pos, state)
    override fun getRenderShape(state: BlockState): RenderShape = RenderShape.MODEL

    override fun useWithoutItem(
        state: BlockState, level: Level, pos: BlockPos, player: Player, hit: BlockHitResult,
    ): InteractionResult {
        if (!level.isClientSide) {
            val be = level.getBlockEntity(pos) as? SpecBlockEntity ?: return InteractionResult.PASS
            val serverPlayer = player as ServerPlayer
            if (be.spec == null) {
                val defaultId = serverPlayer.gameProfile.name.lowercase().replace(" ", "_") + "_spec"
                be.setSpec(RedstoneSpec.new(defaultId))
            }
            ServerPlayNetworking.send(serverPlayer, OpenOverviewS2CPayload(be.blockPos))
        }
        return InteractionResult.SUCCESS
    }

    override fun <T : BlockEntity> getTicker(
        level: Level, state: BlockState, type: BlockEntityType<T>,
    ): BlockEntityTicker<T>? = null

    companion object {
        val CODEC: MapCodec<RedstoneSpecRecorderBlock> = simpleCodec(::RedstoneSpecRecorderBlock)
    }
}
```

- [ ] **Step 4: Verify compile**

```bash
cmd.exe /c "./gradlew.bat :26.1:classes"
```
Expected: BUILD SUCCESSFUL (the new blocks aren't yet registered, but they should compile).

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/breadmoirai/redstonespecs/block/RedstoneSpecRunnerBlock.kt \
        src/main/kotlin/com/breadmoirai/redstonespecs/block/RedstoneSpecEditorBlock.kt \
        src/main/kotlin/com/breadmoirai/redstonespecs/block/RedstoneSpecRecorderBlock.kt
git commit -m "feat: add Runner/Editor/Recorder block classes (placeholder behavior)"
```

---

### Task 1.3 — Update `ModRegistries`: register three blocks/items, share BE type, drop old block

**Files:**
- Modify: `src/main/kotlin/com/breadmoirai/redstonespecs/ModRegistries.kt`
- Delete: `src/main/kotlin/com/breadmoirai/redstonespecs/block/RedstoneSpecBlock.kt`

- [ ] **Step 1: Rewrite `ModRegistries.kt`**

Full contents (replace current `REDSTONE_SPEC_BLOCK` / `REDSTONE_SPEC_ITEM` with three):

```kotlin
package com.breadmoirai.redstonespecs

import com.breadmoirai.redstonespecs.block.RedstoneSpecEditorBlock
import com.breadmoirai.redstonespecs.block.RedstoneSpecRecorderBlock
import com.breadmoirai.redstonespecs.block.RedstoneSpecRunnerBlock
import com.breadmoirai.redstonespecs.block.SpecBlockEntity
import com.breadmoirai.redstonespecs.item.AutoSpecMarkerItem
import com.breadmoirai.redstonespecs.item.BreakpointSpecMarkerItem
import com.breadmoirai.redstonespecs.item.InputSpecMarkerItem
import com.breadmoirai.redstonespecs.item.OutputSpecMarkerItem
import net.fabricmc.fabric.api.`object`.builder.v1.block.entity.FabricBlockEntityTypeBuilder
import net.minecraft.core.BlockPos
import net.minecraft.core.Registry
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.registries.Registries
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.Item
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockBehaviour
import net.minecraft.world.level.block.state.BlockState
import org.slf4j.LoggerFactory

object ModRegistries {
    private val LOGGER = LoggerFactory.getLogger("Redstone Specs")

    private val sharedProps: BlockBehaviour.Properties
        get() = BlockBehaviour.Properties.of().strength(2f).noOcclusion()

    val REDSTONE_SPEC_RUNNER_BLOCK: RedstoneSpecRunnerBlock = registerBlock(
        "redstone_spec_runner", ::RedstoneSpecRunnerBlock, sharedProps
    )
    val REDSTONE_SPEC_EDITOR_BLOCK: RedstoneSpecEditorBlock = registerBlock(
        "redstone_spec_editor", ::RedstoneSpecEditorBlock, sharedProps
    )
    val REDSTONE_SPEC_RECORDER_BLOCK: RedstoneSpecRecorderBlock = registerBlock(
        "redstone_spec_recorder", ::RedstoneSpecRecorderBlock, sharedProps
    )

    val SPEC_BLOCK_ENTITY_TYPE: BlockEntityType<SpecBlockEntity> = Registry.register(
        BuiltInRegistries.BLOCK_ENTITY_TYPE,
        id("spec_block_entity"),
        FabricBlockEntityTypeBuilder.create(
            ::SpecBlockEntity,
            REDSTONE_SPEC_RUNNER_BLOCK,
            REDSTONE_SPEC_EDITOR_BLOCK,
            REDSTONE_SPEC_RECORDER_BLOCK,
        ).build(),
    )

    val REDSTONE_SPEC_RUNNER_ITEM: BlockItem = registerBlockItem("redstone_spec_runner", REDSTONE_SPEC_RUNNER_BLOCK)
    val REDSTONE_SPEC_EDITOR_ITEM: BlockItem = registerBlockItem("redstone_spec_editor", REDSTONE_SPEC_EDITOR_BLOCK)
    val REDSTONE_SPEC_RECORDER_ITEM: BlockItem = registerBlockItem("redstone_spec_recorder", REDSTONE_SPEC_RECORDER_BLOCK)

    val INPUT_SPEC_MARKER: InputSpecMarkerItem = registerItem("input_spec_marker", ::InputSpecMarkerItem)
    val OUTPUT_SPEC_MARKER: OutputSpecMarkerItem = registerItem("output_spec_marker", ::OutputSpecMarkerItem)
    val BREAKPOINT_SPEC_MARKER: BreakpointSpecMarkerItem = registerItem("breakpoint_spec_marker", ::BreakpointSpecMarkerItem)
    val AUTO_SPEC_MARKER: AutoSpecMarkerItem = registerItem("auto_spec_marker", ::AutoSpecMarkerItem)

    fun register() {
        LOGGER.debug("[ModRegistries#register] registering blocks, block entities, and items")
    }

    private fun id(path: String): Identifier = Identifier.fromNamespaceAndPath("redstonespecs", path)

    private fun <T : Block> registerBlock(
        id: String, factory: (BlockBehaviour.Properties) -> T, properties: BlockBehaviour.Properties
    ): T {
        val identifier = id(id)
        val key = ResourceKey.create(Registries.BLOCK, identifier)
        properties.setId(key)
        return Registry.register(BuiltInRegistries.BLOCK, identifier, factory(properties))
    }

    private fun registerBlockItem(id: String, block: Block): BlockItem {
        val identifier = id(id)
        return Registry.register(
            BuiltInRegistries.ITEM, identifier,
            BlockItem(block, Item.Properties().setId(ResourceKey.create(Registries.ITEM, identifier)))
        )
    }

    private fun <T : Item> registerItem(id: String, factory: (Item.Properties) -> T): T {
        val identifier = id(id)
        val props = Item.Properties().setId(ResourceKey.create(Registries.ITEM, identifier))
        return Registry.register(BuiltInRegistries.ITEM, identifier, factory(props))
    }
}
```

- [ ] **Step 2: Delete the old block file**

```bash
git rm src/main/kotlin/com/breadmoirai/redstonespecs/block/RedstoneSpecBlock.kt
```

- [ ] **Step 3: Update `SpecBlockEntity` constructor to use new BE type**

Open `SpecBlockEntity.kt`. Change the BE type reference:

```kotlin
class SpecBlockEntity(pos: BlockPos, state: BlockState) :
    BlockEntity(ModRegistries.SPEC_BLOCK_ENTITY_TYPE, pos, state) {
```

(Was `REDSTONE_SPEC_BLOCK_ENTITY_TYPE` — now renamed to `SPEC_BLOCK_ENTITY_TYPE`.)

- [ ] **Step 4: Compile**

```bash
cmd.exe /c "./gradlew.bat :26.1:classes"
```
Fix any remaining call sites that reference the old `REDSTONE_SPEC_BLOCK` or `REDSTONE_SPEC_BLOCK_ENTITY_TYPE`. Use grep:

```bash
grep -rln "REDSTONE_SPEC_BLOCK\b\|REDSTONE_SPEC_BLOCK_ENTITY_TYPE\|REDSTONE_SPEC_ITEM\b" src/
```

For game tests / fixtures that previously placed `REDSTONE_SPEC_BLOCK`, replace with `REDSTONE_SPEC_EDITOR_BLOCK` (preserves identical behavior).

- [ ] **Step 5: Full build**

```bash
cmd.exe /c "./gradlew.bat :26.1:clientClasses :26.1:classes :26.1:gameTestClasses :26.1:testClasses"
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "feat: register Runner/Editor/Recorder blocks under shared BE type"
```

---

### Task 1.4 — Resource files (models, blockstates, item models, lang)

**Files (each created):**
- `src/main/resources/assets/redstonespecs/models/block/{redstone_spec_runner,redstone_spec_editor,redstone_spec_recorder}.json`
- `src/main/resources/assets/redstonespecs/models/item/{redstone_spec_runner,redstone_spec_editor,redstone_spec_recorder}.json`
- `src/main/resources/assets/redstonespecs/blockstates/{redstone_spec_runner,redstone_spec_editor,redstone_spec_recorder}.json`
- Modify: `src/main/resources/assets/redstonespecs/lang/en_us.json`

Existing texture files already staged in git: `redstone_spec_editor.png`, `redstone_spec_recorder.png`. The Runner can reuse the existing `redstone_spec.png` texture (check `src/main/resources/assets/redstonespecs/textures/block/`).

- [ ] **Step 1: Inspect existing block model**

```bash
cat src/main/resources/assets/redstonespecs/models/block/redstone_spec.json
cat src/main/resources/assets/redstonespecs/blockstates/redstone_spec.json
cat src/main/resources/assets/redstonespecs/models/item/redstone_spec.json
```
Use these as templates; only the texture path changes per variant.

- [ ] **Step 2: Create three blockstate JSONs**

Each is a single variant pointing at its block model. Example for `redstone_spec_editor.json`:

```json
{
  "variants": {
    "": { "model": "redstonespecs:block/redstone_spec_editor" }
  }
}
```

Repeat for `redstone_spec_runner.json` and `redstone_spec_recorder.json` with matching model paths.

- [ ] **Step 3: Create three block models**

Example for `models/block/redstone_spec_editor.json`:

```json
{
  "parent": "minecraft:block/cube_all",
  "textures": {
    "all": "redstonespecs:block/redstone_spec_editor"
  }
}
```

Repeat for runner (use `redstone_spec` texture name if Runner reuses existing) and recorder.

- [ ] **Step 4: Create three item models**

Example for `models/item/redstone_spec_editor.json`:

```json
{
  "parent": "redstonespecs:block/redstone_spec_editor"
}
```

Repeat for runner and recorder.

- [ ] **Step 5: Update lang file**

Edit `src/main/resources/assets/redstonespecs/lang/en_us.json`. Remove the old `block.redstonespecs.redstone_spec` and `item.redstonespecs.redstone_spec` keys. Add:

```json
"block.redstonespecs.redstone_spec_runner": "Redstone Spec Runner",
"block.redstonespecs.redstone_spec_editor": "Redstone Spec Editor",
"block.redstonespecs.redstone_spec_recorder": "Redstone Spec Recorder",
"item.redstonespecs.redstone_spec_runner": "Redstone Spec Runner",
"item.redstonespecs.redstone_spec_editor": "Redstone Spec Editor",
"item.redstonespecs.redstone_spec_recorder": "Redstone Spec Recorder"
```

- [ ] **Step 6: Delete old resource files**

```bash
git rm src/main/resources/assets/redstonespecs/models/block/redstone_spec.json
git rm src/main/resources/assets/redstonespecs/models/item/redstone_spec.json
git rm src/main/resources/assets/redstonespecs/blockstates/redstone_spec.json
```
Keep the texture file `redstone_spec.png` if used by Runner; otherwise remove.

- [ ] **Step 7: Verify build**

```bash
cmd.exe /c "./gradlew.bat :26.1:classes"
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 8: Commit**

```bash
git add -A
git commit -m "feat: resource files for Runner/Editor/Recorder blocks"
```

---

## Phase 2 — Marker tool scope rule

### Task 2.1 — Reject markers outside any spec bounds, and on Runner blocks

**Files:**
- Modify: `src/main/kotlin/com/breadmoirai/redstonespecs/item/SpecMarkerTool.kt`

**Behavior:** today, `SpecMarkerTool.useOn` already early-returns `InteractionResult.PASS` if `SpecBlockEntity.findFor` returns null — this already enforces "must be inside some spec bounds." We add: must NOT be a Runner.

- [ ] **Step 1: Modify `SpecMarkerTool.useOn`**

Replace the body. After the existing `findFor` lookup, reject if the BE's block is the Runner:

```kotlin
override fun useOn(context: UseOnContext): InteractionResult {
    val level: Level = context.level
    val hitPos: BlockPos = context.clickedPos
    val player = context.player ?: return InteractionResult.PASS

    val be = SpecBlockEntity.findFor(level, hitPos) ?: return InteractionResult.PASS

    // Marker placement is not allowed on a Runner block — its spec is read-only.
    if (be.blockState.block is com.breadmoirai.redstonespecs.block.RedstoneSpecRunnerBlock) {
        return InteractionResult.PASS
    }

    if (!level.isClientSide) {
        val spec = be.spec ?: return InteractionResult.PASS
        val relPos = hitPos.subtract(be.blockPos)
        val hitState = level.getBlockState(hitPos)
        val initProps = captureBlockStateProps(hitState)

        if (spec.entryAt(relPos) == null) {
            be.addOrUpdateEntry(createEntry(relPos, initProps, hitState, spec))
        }
        ServerPlayNetworking.send(player as ServerPlayer, OpenEditorS2CPayload(be.blockPos, relPos))
    }
    return InteractionResult.SUCCESS
}
```

- [ ] **Step 2: Verify build**

```bash
cmd.exe /c "./gradlew.bat :26.1:classes"
```

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/com/breadmoirai/redstonespecs/item/SpecMarkerTool.kt
git commit -m "feat: marker tool rejects Runner blocks (read-only spec)"
```

The "must be inside spec bounds" rule is already enforced by the existing `findFor` early-return — no change needed there.

---

## Phase 3 — Editor: Save & Discard buttons

### Task 3.1 — Networking payloads for transformation

**Files:**
- Modify: `src/main/kotlin/com/breadmoirai/redstonespecs/network/Packets.kt`
- Modify: `src/main/kotlin/com/breadmoirai/redstonespecs/network/NetworkRegistry.kt`

- [ ] **Step 1: Add two C2S payloads**

Append to `Packets.kt`:

```kotlin
data class TransformToRunnerC2SPayload(val originPos: BlockPos) : CustomPacketPayload {
    companion object {
        val TYPE = CustomPacketPayload.Type<TransformToRunnerC2SPayload>(
            Identifier.fromNamespaceAndPath("redstonespecs", "transform_to_runner")
        )
        val STREAM_CODEC: StreamCodec<ByteBuf, TransformToRunnerC2SPayload> = StreamCodec.composite(
            BlockPos.STREAM_CODEC, TransformToRunnerC2SPayload::originPos,
            ::TransformToRunnerC2SPayload,
        )
    }
    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE
}

data class TransformToRecorderC2SPayload(val originPos: BlockPos) : CustomPacketPayload {
    companion object {
        val TYPE = CustomPacketPayload.Type<TransformToRecorderC2SPayload>(
            Identifier.fromNamespaceAndPath("redstonespecs", "transform_to_recorder")
        )
        val STREAM_CODEC: StreamCodec<ByteBuf, TransformToRecorderC2SPayload> = StreamCodec.composite(
            BlockPos.STREAM_CODEC, TransformToRecorderC2SPayload::originPos,
            ::TransformToRecorderC2SPayload,
        )
    }
    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE
}

data class TransformToEditorC2SPayload(val originPos: BlockPos) : CustomPacketPayload {
    companion object {
        val TYPE = CustomPacketPayload.Type<TransformToEditorC2SPayload>(
            Identifier.fromNamespaceAndPath("redstonespecs", "transform_to_editor")
        )
        val STREAM_CODEC: StreamCodec<ByteBuf, TransformToEditorC2SPayload> = StreamCodec.composite(
            BlockPos.STREAM_CODEC, TransformToEditorC2SPayload::originPos,
            ::TransformToEditorC2SPayload,
        )
    }
    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE
}
```

- [ ] **Step 2: Register payload types**

In `NetworkRegistry.kt`, add registration calls for the three new C2S payload types. Match the existing pattern (find an existing C2S registration as a template).

- [ ] **Step 3: Verify build**

```bash
cmd.exe /c "./gradlew.bat :26.1:classes"
```

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "feat: add transform-to-{runner,editor,recorder} C2S payloads"
```

---

### Task 3.2 — Server handlers for the three transformations

**Files:**
- Modify: `src/main/kotlin/com/breadmoirai/redstonespecs/Redstonespecs.kt`

- [ ] **Step 1: Add discard helper to `SpecBlockEntity`**

In `SpecBlockEntity.kt`, add:

```kotlin
/** Wipes everything except id, bounds, and input/output marker positions. Used by Editor → Recorder Discard. */
fun discardForRerecord() {
    val s = spec ?: return
    val cleared = RedstoneSpec.new(s.id).copy(
        bounds = s.bounds,
        // Preserve only marker positions; reset their condition data to defaults.
        inputs = s.inputs.map { InputSpec(it.pos, it.label, it.color, emptyList()) },
        outputs = s.outputs.map { OutputSpec(it.pos, it.label, it.color, emptyList()) },
    )
    setSpec(cleared)
    lastTestResult = null
    setChangedAndSync()
}
```

NOTE: verify the actual `InputSpec`/`OutputSpec` constructors take `(pos, label, color, conditions)` — read `data/SpecEntry.kt` to confirm parameter names. Adjust the call if shape differs.

- [ ] **Step 2: Add server packet handlers**

In `Redstonespecs.kt`, register handlers for the three new payloads. Use the existing handler-registration pattern (find an existing C2S handler in the same file as a template):

```kotlin
ServerPlayNetworking.registerGlobalReceiver(TransformToRunnerC2SPayload.TYPE) { payload, ctx ->
    ctx.player().server.execute {
        val level = ctx.player().serverLevel()
        val be = level.getBlockEntity(payload.originPos) as? SpecBlockEntity ?: return@execute
        if (level.getBlockState(payload.originPos).block !is RedstoneSpecEditorBlock) return@execute
        be.transformTo(ModRegistries.REDSTONE_SPEC_RUNNER_BLOCK)
    }
}

ServerPlayNetworking.registerGlobalReceiver(TransformToRecorderC2SPayload.TYPE) { payload, ctx ->
    ctx.player().server.execute {
        val level = ctx.player().serverLevel()
        val be = level.getBlockEntity(payload.originPos) as? SpecBlockEntity ?: return@execute
        if (level.getBlockState(payload.originPos).block !is RedstoneSpecEditorBlock) return@execute
        be.discardForRerecord()
        be.transformTo(ModRegistries.REDSTONE_SPEC_RECORDER_BLOCK)
    }
}

ServerPlayNetworking.registerGlobalReceiver(TransformToEditorC2SPayload.TYPE) { payload, ctx ->
    ctx.player().server.execute {
        val level = ctx.player().serverLevel()
        val be = level.getBlockEntity(payload.originPos) as? SpecBlockEntity ?: return@execute
        val block = level.getBlockState(payload.originPos).block
        if (block !is RedstoneSpecRunnerBlock) return@execute
        be.transformTo(ModRegistries.REDSTONE_SPEC_EDITOR_BLOCK)
    }
}
```

(Imports as needed: `RedstoneSpecRunnerBlock`, `RedstoneSpecEditorBlock`, `RedstoneSpecRecorderBlock`, `ModRegistries`.)

- [ ] **Step 3: Verify build**

```bash
cmd.exe /c "./gradlew.bat :26.1:classes"
```

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "feat: server handlers for Runner/Editor/Recorder transformations"
```

---

### Task 3.3 — Editor overview: Save & Discard buttons (and Edit button on Runner)

**Files:**
- Modify: `src/client/kotlin/com/breadmoirai/redstonespecs/client/screen/SpecOverviewScreen.kt`
- Modify: `src/client/kotlin/com/breadmoirai/redstonespecs/client/network/ClientNetworkHandler.kt` if the screen needs to know which block type opened it

**Approach:** the screen needs to know whether it was opened from a Runner or an Editor. Two options:
- (A) extend `OpenOverviewS2CPayload` with a `blockKind` enum
- (B) the client looks up the world block at `originPos` to determine kind

Use **(A)** — explicit is better, avoids client-side world race conditions.

- [ ] **Step 1: Add `SpecBlockKind` enum**

Create `src/main/kotlin/com/breadmoirai/redstonespecs/block/SpecBlockKind.kt`:

```kotlin
package com.breadmoirai.redstonespecs.block

import io.netty.buffer.ByteBuf
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec

enum class SpecBlockKind { RUNNER, EDITOR, RECORDER;
    companion object {
        val STREAM_CODEC: StreamCodec<ByteBuf, SpecBlockKind> =
            ByteBufCodecs.VAR_INT.map({ entries[it] }, SpecBlockKind::ordinal)
    }
}
```

- [ ] **Step 2: Extend `OpenOverviewS2CPayload`**

Modify in `Packets.kt`:

```kotlin
data class OpenOverviewS2CPayload(
    val originPos: BlockPos,
    val kind: SpecBlockKind,
) : CustomPacketPayload {
    companion object {
        val TYPE = CustomPacketPayload.Type<OpenOverviewS2CPayload>(
            Identifier.fromNamespaceAndPath("redstonespecs", "open_overview")
        )
        val STREAM_CODEC: StreamCodec<ByteBuf, OpenOverviewS2CPayload> = StreamCodec.composite(
            BlockPos.STREAM_CODEC, OpenOverviewS2CPayload::originPos,
            SpecBlockKind.STREAM_CODEC, OpenOverviewS2CPayload::kind,
            ::OpenOverviewS2CPayload,
        )
    }
    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE
}
```

- [ ] **Step 3: Update both block `useWithoutItem` to send the kind**

In `RedstoneSpecEditorBlock.useWithoutItem` change to:
```kotlin
ServerPlayNetworking.send(serverPlayer, OpenOverviewS2CPayload(be.blockPos, SpecBlockKind.EDITOR))
```

In `RedstoneSpecRunnerBlock.useWithoutItem` (only when spec is non-null):
```kotlin
ServerPlayNetworking.send(serverPlayer, OpenOverviewS2CPayload(be.blockPos, SpecBlockKind.RUNNER))
```

(Recorder will get its own GUI in Phase 5; for now leave its `OpenOverviewS2CPayload` call sending `SpecBlockKind.RECORDER` which the screen will display read-only-ish — Phase 5 replaces this entirely.)

- [ ] **Step 4: Modify `SpecOverviewScreen` to accept the kind and render conditional buttons**

Read `SpecOverviewScreen.kt` to understand current constructor signature. Add a `kind: SpecBlockKind` parameter. Add header buttons:

- If `kind == EDITOR`: show **Save** (sends `TransformToRunnerC2SPayload`) and **Discard** (sends `TransformToRecorderC2SPayload`).
- If `kind == RUNNER`: show **Edit** (sends `TransformToEditorC2SPayload`). Hide all mutation controls (the "+" entry button, mode/lifespan dropdowns, structure load, file browser load, etc. — everything that mutates the spec).
- If `kind == RECORDER`: same as EDITOR for now (Phase 5 replaces).

Implementation pattern (add inside `init` after existing widgets):

```kotlin
when (kind) {
    SpecBlockKind.EDITOR -> {
        addRenderableWidget(Button.builder(Component.literal("Save")) {
            ClientPlayNetworking.send(TransformToRunnerC2SPayload(originPos))
            onClose()
        }.bounds(saveButtonX, headerY, 50, 16).build())
        addRenderableWidget(Button.builder(Component.literal("Discard")) {
            ClientPlayNetworking.send(TransformToRecorderC2SPayload(originPos))
            onClose()
        }.bounds(discardButtonX, headerY, 60, 16).build())
    }
    SpecBlockKind.RUNNER -> {
        addRenderableWidget(Button.builder(Component.literal("Edit")) {
            ClientPlayNetworking.send(TransformToEditorC2SPayload(originPos))
            onClose()
        }.bounds(editButtonX, headerY, 50, 16).build())
    }
    SpecBlockKind.RECORDER -> { /* Phase 5 */ }
}
```

For the Runner read-only mode, gate every existing mutation widget behind `if (kind != SpecBlockKind.RUNNER) { ... addRenderableWidget(...) }`.

(Pick concrete x positions consistent with the existing header layout — read the surrounding code.)

- [ ] **Step 5: Update `ClientNetworkHandler` to pass the kind**

The handler for `OpenOverviewS2CPayload` constructs the screen — pass `payload.kind` to it.

- [ ] **Step 6: Build & verify**

```bash
cmd.exe /c "./gradlew.bat :26.1:clientClasses :26.1:classes :26.1:gameTestClasses :26.1:testClasses"
```

- [ ] **Step 7: Manual smoke test**

Place an Editor block, open it, click Save → block becomes Runner. Open Runner, click Edit → becomes Editor again. Click Discard on Editor → becomes Recorder; verify entries (other than I/O markers) are gone, id/bounds preserved.

- [ ] **Step 8: Commit**

```bash
git add -A
git commit -m "feat: Editor Save/Discard and Runner Edit transformation buttons"
```

---

## Phase 4 — Runner: empty-state spec picker

### Task 4.1 — Spec picker payload + screen

**Files:**
- Modify: `src/main/kotlin/com/breadmoirai/redstonespecs/network/Packets.kt`
- Modify: `src/main/kotlin/com/breadmoirai/redstonespecs/network/NetworkRegistry.kt`
- Modify: `src/main/kotlin/com/breadmoirai/redstonespecs/Redstonespecs.kt`
- Modify: `src/main/kotlin/com/breadmoirai/redstonespecs/block/RedstoneSpecRunnerBlock.kt`
- Create: `src/client/kotlin/com/breadmoirai/redstonespecs/client/screen/RunnerSpecPickerScreen.kt`
- Modify: `src/client/kotlin/com/breadmoirai/redstonespecs/client/RedstonespecsClient.kt` and `ClientNetworkHandler.kt`

**Reuse:** the existing `OpenFileBrowserS2CPayload` already lists saved specs (see `Packets.kt:355`). The Runner picker can use the same payload but a different screen, OR a new payload `OpenRunnerPickerS2CPayload` with the same `List<SpecFileInfo>`. Use a new payload for clarity.

- [ ] **Step 1: Add new payloads**

Append to `Packets.kt`:

```kotlin
data class OpenRunnerPickerS2CPayload(
    val originPos: BlockPos,
    val files: List<SpecFileInfo>,
) : CustomPacketPayload {
    companion object {
        val TYPE = CustomPacketPayload.Type<OpenRunnerPickerS2CPayload>(
            Identifier.fromNamespaceAndPath("redstonespecs", "open_runner_picker")
        )
        val STREAM_CODEC: StreamCodec<ByteBuf, OpenRunnerPickerS2CPayload> = StreamCodec.composite(
            BlockPos.STREAM_CODEC, OpenRunnerPickerS2CPayload::originPos,
            SpecFileInfo.STREAM_CODEC.apply(ByteBufCodecs.list()), OpenRunnerPickerS2CPayload::files,
            ::OpenRunnerPickerS2CPayload,
        )
    }
    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE
}

data class RunnerLoadSpecC2SPayload(val originPos: BlockPos, val specId: String) : CustomPacketPayload {
    companion object {
        val TYPE = CustomPacketPayload.Type<RunnerLoadSpecC2SPayload>(
            Identifier.fromNamespaceAndPath("redstonespecs", "runner_load_spec")
        )
        val STREAM_CODEC: StreamCodec<ByteBuf, RunnerLoadSpecC2SPayload> = StreamCodec.composite(
            BlockPos.STREAM_CODEC, RunnerLoadSpecC2SPayload::originPos,
            ByteBufCodecs.STRING_UTF8, RunnerLoadSpecC2SPayload::specId,
            ::RunnerLoadSpecC2SPayload,
        )
    }
    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE
}
```

- [ ] **Step 2: Register payloads in `NetworkRegistry`**

Add registrations following existing pattern.

- [ ] **Step 3: Update `RedstoneSpecRunnerBlock.useWithoutItem`**

```kotlin
override fun useWithoutItem(
    state: BlockState, level: Level, pos: BlockPos, player: Player, hit: BlockHitResult,
): InteractionResult {
    if (!level.isClientSide) {
        val be = level.getBlockEntity(pos) as? SpecBlockEntity ?: return InteractionResult.PASS
        val serverPlayer = player as ServerPlayer
        if (be.spec == null) {
            // Empty Runner: send picker.
            val files = listAvailableSpecs(serverPlayer.serverLevel())
            ServerPlayNetworking.send(serverPlayer, OpenRunnerPickerS2CPayload(be.blockPos, files))
        } else {
            ServerPlayNetworking.send(serverPlayer, OpenOverviewS2CPayload(be.blockPos, SpecBlockKind.RUNNER))
        }
    }
    return InteractionResult.SUCCESS
}

private fun listAvailableSpecs(level: ServerLevel): List<SpecFileInfo> {
    val saveDir = level.server.getWorldPath(LevelResource.ROOT)
        .resolve(SharedSettings.specSaveDir)
    return SpecPersistence.listSpecsInfo(saveDir)
}
```

If `SpecPersistence.listSpecsInfo` does not exist, look at how the existing file-browser handler in `Redstonespecs.kt` builds its `List<SpecFileInfo>` — extract that into a reusable function in `SpecPersistence` and call it from both places. Don't duplicate the logic.

- [ ] **Step 4: Server handler for `RunnerLoadSpecC2SPayload`**

In `Redstonespecs.kt`:

```kotlin
ServerPlayNetworking.registerGlobalReceiver(RunnerLoadSpecC2SPayload.TYPE) { payload, ctx ->
    ctx.player().server.execute {
        val level = ctx.player().serverLevel()
        val be = level.getBlockEntity(payload.originPos) as? SpecBlockEntity ?: return@execute
        if (level.getBlockState(payload.originPos).block !is RedstoneSpecRunnerBlock) return@execute
        val saveDir = level.server.getWorldPath(LevelResource.ROOT)
            .resolve(SharedSettings.specSaveDir)
        val loaded = SpecPersistence.load(saveDir, payload.specId) ?: return@execute
        be.setSpec(loaded)
    }
}
```

(If `SpecPersistence.load(saveDir, id)` doesn't exist with this exact signature, check how the file-browser load is implemented and follow its pattern.)

- [ ] **Step 5: Create `RunnerSpecPickerScreen`**

```kotlin
package com.breadmoirai.redstonespecs.client.screen

import com.breadmoirai.redstonespecs.network.RunnerLoadSpecC2SPayload
import com.breadmoirai.redstonespecs.network.SpecFileInfo
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.Screen
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component

class RunnerSpecPickerScreen(
    private val originPos: BlockPos,
    private val files: List<SpecFileInfo>,
) : Screen(Component.literal("Select a Spec")) {

    override fun init() {
        // Simple list: one row per available spec, click to load.
        val startY = 40
        val rowHeight = 20
        files.forEachIndexed { i, info ->
            val label = "${info.id}  (${info.mode}, ${info.lifespan}t, ${info.inputCount}→${info.outputCount})"
            addRenderableWidget(Button.builder(Component.literal(label)) {
                ClientPlayNetworking.send(RunnerLoadSpecC2SPayload(originPos, info.id))
                onClose()
            }.bounds(width / 2 - 150, startY + i * rowHeight, 300, rowHeight - 2).build())
        }
        addRenderableWidget(Button.builder(Component.literal("Cancel")) {
            onClose()
        }.bounds(width / 2 - 50, height - 30, 100, 20).build())
    }

    override fun render(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.render(graphics, mouseX, mouseY, partialTick)
        graphics.drawCenteredString(font, title, width / 2, 16, -1)
    }
}
```

(If your saved specs list grows large enough to need a scroll, follow the existing `SpecFileBrowserScreen` pattern. For now, simple list is fine.)

- [ ] **Step 6: Wire S2C handler in `ClientNetworkHandler`**

Register a handler for `OpenRunnerPickerS2CPayload` that calls `Minecraft.getInstance().setScreen(RunnerSpecPickerScreen(payload.originPos, payload.files))`.

- [ ] **Step 7: Build**

```bash
cmd.exe /c "./gradlew.bat :26.1:clientClasses :26.1:classes :26.1:gameTestClasses :26.1:testClasses"
```

- [ ] **Step 8: Commit**

```bash
git add -A
git commit -m "feat: Runner empty-state spec picker"
```

---

## Phase 5 — Recorder: GUI, redstone trigger, recording finalizer

### Task 5.1 — `RecordingFinalizer` (TDD)

This is the only piece with non-trivial logic, so it gets full TDD. The finalizer takes a `StateRecording` (full block state changes within bounds, per tick), the `SpecMode`, and the player-defined input/output marker positions, and produces a `RedstoneSpec` (with auto-trimmed lifespan and derived `SpecEntry` list).

**Files:**
- Create: `src/main/kotlin/com/breadmoirai/redstonespecs/runner/RecordingFinalizer.kt`
- Create: `src/test/kotlin/com/breadmoirai/redstonespecs/runner/RecordingFinalizerTest.kt`

Read first to understand inputs:
```bash
cat src/main/kotlin/com/breadmoirai/redstonespecs/runner/StateRecording.kt
cat src/main/kotlin/com/breadmoirai/redstonespecs/runner/StateRecorder.kt
cat src/main/kotlin/com/breadmoirai/redstonespecs/data/SpecMode.kt
cat src/main/kotlin/com/breadmoirai/redstonespecs/data/SpecEntry.kt
cat src/main/kotlin/com/breadmoirai/redstonespecs/data/StateCondition.kt
```

The actual signature of `StateRecording` and the helpers (e.g. `propsToCondition`, `captureBlockStateProps` in `runner/SpecRunner.kt` or similar) inform the implementation. Adjust the API below if the real types differ.

- [ ] **Step 1: Define the API skeleton**

Create `RecordingFinalizer.kt`:

```kotlin
package com.breadmoirai.redstonespecs.runner

import com.breadmoirai.redstonespecs.data.RedstoneSpec
import com.breadmoirai.redstonespecs.data.SpecMode
import net.minecraft.core.BlockPos

object RecordingFinalizer {

    /**
     * Produces a finalized [RedstoneSpec] from a recording.
     *
     * @param baseSpec spec on the BE before finalize: provides id, mode, bounds, structure, marker positions/labels.
     *                 Marker conditions are ignored — they are re-derived from the recording.
     * @param recording full state recording captured by [StateRecorder] over the bounds.
     * @return new spec with derived entries and trimmed lifespan; or null if the recording contains no I/O activity.
     */
    fun finalize(baseSpec: RedstoneSpec, recording: StateRecording): RedstoneSpec? {
        val ioPositions: Set<BlockPos> =
            (baseSpec.inputs.map { it.pos } + baseSpec.outputs.map { it.pos }).toSet()
        if (ioPositions.isEmpty()) return null

        val (firstTick, lastTick) = ioActivitySpan(recording, ioPositions) ?: return null
        val trimmed = recording.sliceTicks(firstTick, lastTick) // inclusive

        val lifespan = (lastTick - firstTick).coerceAtLeast(0)
        val derivedInputs = baseSpec.inputs.map { input ->
            input.copy(conditions = deriveConditionsForInput(input, trimmed, baseSpec.mode))
        }
        val derivedOutputs = baseSpec.outputs.map { output ->
            output.copy(conditions = deriveConditionsForOutput(output, trimmed, baseSpec.mode, lifespan))
        }
        return baseSpec.copy(
            lifespan = lifespan,
            inputs = derivedInputs,
            outputs = derivedOutputs,
        )
    }

    /** Returns inclusive [first, last] tick indices where any I/O block changed state, or null if none did. */
    internal fun ioActivitySpan(rec: StateRecording, io: Set<BlockPos>): Pair<Int, Int>? {
        var first = -1
        var last = -1
        for (t in 0 until rec.tickCount) {
            if (rec.tickHasAnyChangeAt(t, io)) {
                if (first < 0) first = t
                last = t
            }
        }
        return if (first < 0) null else first to last
    }

    private fun deriveConditionsForInput(
        input: com.breadmoirai.redstonespecs.data.InputSpec,
        recording: StateRecording,
        mode: SpecMode,
    ): List<Pair<com.breadmoirai.redstonespecs.data.SimTime, com.breadmoirai.redstonespecs.data.StateCondition>> {
        // For each tick where the input block's state actually changed, capture (SimTime, propsToCondition(state)).
        // Mode-specific phase: SIMPLE uses END_OF_TICK; other modes follow existing conventions in SpecMarkerTool/SpecRunner.
        TODO("see Step 4 below — implement per real APIs")
    }

    private fun deriveConditionsForOutput(
        output: com.breadmoirai.redstonespecs.data.OutputSpec,
        recording: StateRecording,
        mode: SpecMode,
        lifespan: Int,
    ): List<Pair<com.breadmoirai.redstonespecs.data.SimTime, com.breadmoirai.redstonespecs.data.StateCondition>> {
        TODO("see Step 4 below — implement per real APIs")
    }
}
```

(The `sliceTicks` / `tickHasAnyChangeAt` / `tickCount` helpers may not exist on `StateRecording` today. If not, add them as small extension functions or methods — they should be obvious one-liners over the underlying tick list.)

- [ ] **Step 2: Write failing tests for `ioActivitySpan`**

Create `RecordingFinalizerTest.kt`:

```kotlin
package com.breadmoirai.redstonespecs.runner

import net.minecraft.core.BlockPos
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class RecordingFinalizerTest {

    private fun recording(ticks: Int, changesByTick: Map<Int, Set<BlockPos>>): StateRecording {
        // Build a minimal StateRecording (or a test double if the real one is hard to construct).
        // Adjust per the real StateRecording API.
        TODO("Construct a recording with the given per-tick change set")
    }

    @Test fun `ioActivitySpan finds first and last tick with IO change`() {
        val a = BlockPos(1, 0, 0)
        val b = BlockPos(2, 0, 0)
        val rec = recording(10, mapOf(
            2 to setOf(a),
            5 to setOf(b),
            7 to setOf(a),
        ))
        val span = RecordingFinalizer.ioActivitySpan(rec, setOf(a, b))
        assertEquals(2 to 7, span)
    }

    @Test fun `ioActivitySpan returns null when no IO blocks change`() {
        val a = BlockPos(1, 0, 0)
        val other = BlockPos(9, 0, 0)
        val rec = recording(5, mapOf(1 to setOf(other), 3 to setOf(other)))
        val span = RecordingFinalizer.ioActivitySpan(rec, setOf(a))
        assertNull(span)
    }

    @Test fun `ioActivitySpan trims trailing internal-only changes from lifespan`() {
        val a = BlockPos(1, 0, 0)
        val internal = BlockPos(9, 0, 0)
        val rec = recording(20, mapOf(
            3 to setOf(a),
            5 to setOf(a),
            10 to setOf(internal), // internal-only, after I/O settled — should not extend span
            15 to setOf(internal),
        ))
        val span = RecordingFinalizer.ioActivitySpan(rec, setOf(a))
        assertEquals(3 to 5, span)
    }
}
```

- [ ] **Step 3: Run tests to confirm they fail**

```bash
cmd.exe /c "./gradlew.bat :26.1:test --tests com.breadmoirai.redstonespecs.runner.RecordingFinalizerTest"
```
Expected: FAIL (TODO in `recording` builder, plus the methods don't exist yet on `StateRecording` if they need adding).

- [ ] **Step 4: Implement `StateRecording` helpers and finalizer body**

Read `StateRecording.kt`. Identify the per-tick representation (likely a list of per-tick `Map<BlockPos, BlockState>` deltas, or similar — confirm). Add small helpers:

```kotlin
val StateRecording.tickCount: Int get() = /* size of underlying tick list */

fun StateRecording.tickHasAnyChangeAt(t: Int, positions: Set<BlockPos>): Boolean = /* ... */

fun StateRecording.sliceTicks(first: Int, last: Int): StateRecording = /* slice underlying list */

fun StateRecording.changeAt(t: Int, pos: BlockPos): BlockState? = /* per-tick lookup */
```

Implement `deriveConditionsForInput` / `deriveConditionsForOutput` per the actual `SpecMode` semantics — read existing call sites in `SpecMarkerTool.kt:84-90` and `SpecRunner.kt` to mirror how INIT vs END_OF_TICK SimTimes are constructed today, and how `propsToCondition`/`captureBlockStateProps` produce the `StateCondition`.

Pseudocode for `deriveConditionsForInput`:

```kotlin
val firstChangeOrInit: Pair<SimTime, StateCondition> = run {
    val initial = recording.changeAt(0, input.pos) ?: recording.initialStateAt(input.pos)
    SimTime.INIT to propsToCondition(captureBlockStateProps(initial), initial)
}
val laterChanges = (1 until recording.tickCount)
    .mapNotNull { t ->
        val s = recording.changeAt(t, input.pos) ?: return@mapNotNull null
        SimTime(t, Phase.END_OF_TICK) to propsToCondition(captureBlockStateProps(s), s)
    }
listOf(firstChangeOrInit) + laterChanges
```

Output derivation is similar; in `SIMPLE` mode collapse to a single `(SimTime(lifespan, END_OF_TICK), finalCondition)` entry to match how the marker tool currently bootstraps outputs (`SpecMarkerTool.kt:88-90`).

- [ ] **Step 5: Add tests for derivation**

Append to `RecordingFinalizerTest.kt` tests covering:
- `finalize` returns a spec with matching lifespan = lastTick - firstTick.
- SIMPLE mode: each output ends up with exactly one condition at `SimTime(lifespan, END_OF_TICK)`.
- Non-SIMPLE mode: an input that changes on ticks 2 and 5 produces two conditions at the corresponding `SimTime`s (relative to the trimmed start).
- `finalize` returns null when there are no I/O markers.
- `finalize` returns null when no I/O block ever changes state in the recording.

(Provide concrete test bodies — these should be straightforward variants of the existing `recording()` helper.)

- [ ] **Step 6: Run all tests**

```bash
cmd.exe /c "./gradlew.bat :26.1:test"
```
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "feat: RecordingFinalizer with auto-trim and entry derivation"
```

---

### Task 5.2 — Recorder GUI screen

**Files:**
- Create: `src/client/kotlin/com/breadmoirai/redstonespecs/client/screen/RecorderSetupScreen.kt`
- Modify: `src/main/kotlin/com/breadmoirai/redstonespecs/network/Packets.kt` (add Open + Start/Stop payloads)
- Modify: `src/main/kotlin/com/breadmoirai/redstonespecs/network/NetworkRegistry.kt`
- Modify: `src/main/kotlin/com/breadmoirai/redstonespecs/Redstonespecs.kt`
- Modify: `src/main/kotlin/com/breadmoirai/redstonespecs/block/RedstoneSpecRecorderBlock.kt`
- Modify: `src/client/kotlin/com/breadmoirai/redstonespecs/client/network/ClientNetworkHandler.kt`

The Recorder screen exposes:
- Spec ID text field (reuses existing `SetSpecIdC2SPayload`)
- Mode dropdown (reuses existing `SetSpecModeC2SPayload`)
- Bounds editor — open existing `SpecBoundsScreen` via a button (reuses existing infrastructure)
- Structure load button — opens existing `SpecFileBrowserScreen` (reuses existing `RequestFileBrowserC2SPayload`)
- Marker counts display: "Inputs: N", "Outputs: M" (read-only, from current spec)
- **Record / Stop** button (Record while idle, Stop while recording)
- Status row showing current recording state and gating reasons (e.g. "Need ≥1 input")

- [ ] **Step 1: Add payloads**

Append to `Packets.kt`:

```kotlin
data class OpenRecorderS2CPayload(
    val originPos: BlockPos,
    val isRecording: Boolean,
) : CustomPacketPayload {
    companion object {
        val TYPE = CustomPacketPayload.Type<OpenRecorderS2CPayload>(
            Identifier.fromNamespaceAndPath("redstonespecs", "open_recorder")
        )
        val STREAM_CODEC: StreamCodec<ByteBuf, OpenRecorderS2CPayload> = StreamCodec.composite(
            BlockPos.STREAM_CODEC, OpenRecorderS2CPayload::originPos,
            ByteBufCodecs.BOOL, OpenRecorderS2CPayload::isRecording,
            ::OpenRecorderS2CPayload,
        )
    }
    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE
}

data class StartRecordingC2SPayload(val originPos: BlockPos) : CustomPacketPayload {
    companion object {
        val TYPE = CustomPacketPayload.Type<StartRecordingC2SPayload>(
            Identifier.fromNamespaceAndPath("redstonespecs", "start_recording")
        )
        val STREAM_CODEC: StreamCodec<ByteBuf, StartRecordingC2SPayload> = StreamCodec.composite(
            BlockPos.STREAM_CODEC, StartRecordingC2SPayload::originPos,
            ::StartRecordingC2SPayload,
        )
    }
    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE
}

data class StopRecordingC2SPayload(val originPos: BlockPos) : CustomPacketPayload {
    companion object {
        val TYPE = CustomPacketPayload.Type<StopRecordingC2SPayload>(
            Identifier.fromNamespaceAndPath("redstonespecs", "stop_recording")
        )
        val STREAM_CODEC: StreamCodec<ByteBuf, StopRecordingC2SPayload> = StreamCodec.composite(
            BlockPos.STREAM_CODEC, StopRecordingC2SPayload::originPos,
            ::StopRecordingC2SPayload,
        )
    }
    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE
}
```

- [ ] **Step 2: Register payloads**

Add to `NetworkRegistry.kt`.

- [ ] **Step 3: Update `RedstoneSpecRecorderBlock.useWithoutItem`**

```kotlin
override fun useWithoutItem(
    state: BlockState, level: Level, pos: BlockPos, player: Player, hit: BlockHitResult,
): InteractionResult {
    if (!level.isClientSide) {
        val be = level.getBlockEntity(pos) as? SpecBlockEntity ?: return InteractionResult.PASS
        val serverPlayer = player as ServerPlayer
        if (be.spec == null) {
            val defaultId = serverPlayer.gameProfile.name.lowercase().replace(" ", "_") + "_spec"
            be.setSpec(RedstoneSpec.new(defaultId))
        }
        ServerPlayNetworking.send(serverPlayer, OpenRecorderS2CPayload(be.blockPos, be.isRecording))
    }
    return InteractionResult.SUCCESS
}
```

(`be.isRecording` is added in Task 5.3.)

- [ ] **Step 4: Create `RecorderSetupScreen`**

```kotlin
package com.breadmoirai.redstonespecs.client.screen

// imports omitted for brevity — match the pattern used in SpecOverviewScreen.kt

class RecorderSetupScreen(
    private val originPos: BlockPos,
    private val initialIsRecording: Boolean,
) : Screen(Component.literal("Spec Recorder")) {

    // Local mirror of spec; refresh from BE via the existing block-entity sync mechanism if needed.
    // For brevity here, we read the BE on the client every frame in render() — see SpecOverviewScreen for
    // its existing pattern of accessing the client's block entity.

    override fun init() {
        // Spec ID text field (reuse IntEditBox / EditBox patterns from SpecOverviewScreen)
        // Mode dropdown (reuse DropdownButton / CycleButton)
        // Bounds button → opens SpecBoundsScreen for originPos
        // Structure button → sends RequestFileBrowserC2SPayload(originPos)
        // Counts: "Inputs: N", "Outputs: M" — read be.spec on render
        // Record/Stop button:
        addRenderableWidget(Button.builder(
            if (isCurrentlyRecording()) Component.literal("Stop") else Component.literal("Record")
        ) {
            val pos = originPos
            if (isCurrentlyRecording()) ClientPlayNetworking.send(StopRecordingC2SPayload(pos))
            else ClientPlayNetworking.send(StartRecordingC2SPayload(pos))
        }.bounds(/* ... */).build())
        // Status line showing gating reason if Record is disabled.
    }

    private fun isCurrentlyRecording(): Boolean { /* read from BE if available, else use initialIsRecording */ }

    override fun render(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.render(graphics, mouseX, mouseY, partialTick)
        // Draw header, counts, status
    }
}
```

(The plan deliberately leaves widget-layout coordinates to the implementer — match the layout style of `SpecOverviewScreen.kt`.)

- [ ] **Step 5: Wire S2C handler**

In `ClientNetworkHandler`, register handler for `OpenRecorderS2CPayload` to set the screen.

- [ ] **Step 6: Build**

```bash
cmd.exe /c "./gradlew.bat :26.1:clientClasses :26.1:classes :26.1:gameTestClasses :26.1:testClasses"
```

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "feat: Recorder setup screen with Record/Stop button"
```

---

### Task 5.3 — Recording state on `SpecBlockEntity` + start/stop/finalize wiring

**Files:**
- Modify: `src/main/kotlin/com/breadmoirai/redstonespecs/block/SpecBlockEntity.kt`
- Modify: `src/main/kotlin/com/breadmoirai/redstonespecs/Redstonespecs.kt`
- Modify: `src/main/kotlin/com/breadmoirai/redstonespecs/block/RedstoneSpecRecorderBlock.kt`

- [ ] **Step 1: Add recording state to `SpecBlockEntity`**

Add fields and methods:

```kotlin
private var stateRecorder: StateRecorder? = null
val isRecording: Boolean get() = stateRecorder != null

fun startRecording(): Boolean {
    val s = spec ?: return false
    if (s.bounds == /* empty/zero check */) return false
    if (s.id.isBlank()) return false
    if (s.inputs.isEmpty() || s.outputs.isEmpty()) return false
    val lv = level as? ServerLevel ?: return false
    if (stateRecorder != null) return false
    val originAbs = blockPos
    val absBounds = /* translate s.bounds by originAbs */
    stateRecorder = StateRecorder.start(lv, absBounds, originAbs) // adjust to real API
    setChangedAndSync()
    return true
}

fun stopRecordingAndFinalize(): Boolean {
    val rec = stateRecorder ?: return false
    val recording = rec.stop()
    stateRecorder = null
    val s = spec ?: return false
    val finalized = RecordingFinalizer.finalize(s, recording)
    if (finalized != null) {
        setSpec(finalized)
    }
    setChangedAndSync()
    return true
}
```

The exact `StateRecorder.start` / `.stop` signatures come from the real class — adjust accordingly. If `StateRecorder` is not directly start-able by absolute bounds, use the same construction pattern that `SpecRunner` uses today.

- [ ] **Step 2: Server handlers for start/stop**

In `Redstonespecs.kt`:

```kotlin
ServerPlayNetworking.registerGlobalReceiver(StartRecordingC2SPayload.TYPE) { payload, ctx ->
    ctx.player().server.execute {
        val level = ctx.player().serverLevel()
        val be = level.getBlockEntity(payload.originPos) as? SpecBlockEntity ?: return@execute
        if (level.getBlockState(payload.originPos).block !is RedstoneSpecRecorderBlock) return@execute
        be.startRecording()
    }
}

ServerPlayNetworking.registerGlobalReceiver(StopRecordingC2SPayload.TYPE) { payload, ctx ->
    ctx.player().server.execute {
        val level = ctx.player().serverLevel()
        val be = level.getBlockEntity(payload.originPos) as? SpecBlockEntity ?: return@execute
        if (level.getBlockState(payload.originPos).block !is RedstoneSpecRecorderBlock) return@execute
        if (be.stopRecordingAndFinalize()) {
            be.transformTo(ModRegistries.REDSTONE_SPEC_EDITOR_BLOCK)
        }
    }
}
```

- [ ] **Step 3: Build**

```bash
cmd.exe /c "./gradlew.bat :26.1:clientClasses :26.1:classes :26.1:gameTestClasses :26.1:testClasses"
```

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "feat: Recorder start/stop wiring with finalize+transform"
```

---

### Task 5.4 — Redstone trigger on Recorder

**Files:**
- Modify: `src/main/kotlin/com/breadmoirai/redstonespecs/block/RedstoneSpecRecorderBlock.kt`

The Recorder needs to react to redstone signal changes: rising edge → `startRecording`; falling edge → `stopRecordingAndFinalize` + transform to Editor.

- [ ] **Step 1: Override `neighborChanged`**

```kotlin
override fun neighborChanged(
    state: BlockState,
    level: Level,
    pos: BlockPos,
    sourceBlock: Block,
    sourcePos: BlockPos,
    notify: Boolean,
) {
    super.neighborChanged(state, level, pos, sourceBlock, sourcePos, notify)
    if (level.isClientSide) return
    val be = level.getBlockEntity(pos) as? SpecBlockEntity ?: return
    val powered = level.hasNeighborSignal(pos)
    if (powered && !be.isRecording) {
        be.startRecording()
    } else if (!powered && be.isRecording) {
        if (be.stopRecordingAndFinalize()) {
            be.transformTo(ModRegistries.REDSTONE_SPEC_EDITOR_BLOCK)
        }
    }
}
```

(Confirm the actual MC 26.1 `neighborChanged` signature — if it has changed in this version, follow the IDE / compiler guidance. The mod targets MC 26.1.)

- [ ] **Step 2: Build**

```bash
cmd.exe /c "./gradlew.bat :26.1:clientClasses :26.1:classes :26.1:gameTestClasses :26.1:testClasses"
```

- [ ] **Step 3: Manual smoke test**

Place a Recorder, define bounds, set spec id, place input + output markers (using marker tool, which now works inside Recorder bounds), wire a redstone source, pulse it. Verify:
- On rising edge, recording starts.
- On falling edge, recording ends, block becomes Editor, and the Editor's overview shows derived entries with sensible lifespan.

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "feat: Recorder responds to redstone (rising=start, falling=stop+transform)"
```

---

## Phase 6 — Verification

### Task 6.1 — Game test for the full transformation flow

**Files:**
- Modify: `src/gametest/kotlin/com/breadmoirai/redstonespecs/test/RedstonespecsClientTests.kt` (or add new file under same dir)

Following existing test patterns in this file (use `SpecTestContext` helpers):

- [ ] **Step 1: Test Editor → Runner via Save**

```kotlin
@GameTest(template = "redstonespecs:empty_5x5", batch = "default")
fun editorTransformsToRunnerOnSave(helper: GameTestHelper) {
    val pos = BlockPos(0, 1, 0)
    helper.setBlock(pos, ModRegistries.REDSTONE_SPEC_EDITOR_BLOCK)
    val be = helper.getBlockEntity(pos) as SpecBlockEntity
    be.setSpec(RedstoneSpec.new("test"))
    be.transformTo(ModRegistries.REDSTONE_SPEC_RUNNER_BLOCK)
    helper.succeedWhen {
        val newBlock = helper.getBlockState(pos).block
        helper.assertTrue(newBlock is RedstoneSpecRunnerBlock, "block should be Runner after transform")
        val newBe = helper.getBlockEntity(pos) as SpecBlockEntity
        helper.assertTrue(newBe.spec?.id == "test", "spec data should survive transform")
    }
}
```

- [ ] **Step 2: Test Editor → Recorder Discard preserves only id/bounds/marker positions**

```kotlin
@GameTest(template = "redstonespecs:empty_5x5", batch = "default")
fun discardClearsEverythingExceptIdBoundsAndMarkers(helper: GameTestHelper) {
    val pos = BlockPos(0, 1, 0)
    helper.setBlock(pos, ModRegistries.REDSTONE_SPEC_EDITOR_BLOCK)
    val be = helper.getBlockEntity(pos) as SpecBlockEntity
    val original = RedstoneSpec.new("keep_me").copy(
        lifespan = 99,
        inputs = listOf(InputSpec(BlockPos(1, 0, 0), "in_a", 0, listOf(/* some condition */))),
        outputs = listOf(OutputSpec(BlockPos(2, 0, 0), "out_a", 0, listOf(/* some condition */))),
    )
    be.setSpec(original)
    be.discardForRerecord()
    be.transformTo(ModRegistries.REDSTONE_SPEC_RECORDER_BLOCK)
    helper.succeedWhen {
        val newBe = helper.getBlockEntity(pos) as SpecBlockEntity
        val s = newBe.spec!!
        helper.assertTrue(s.id == "keep_me", "id preserved")
        helper.assertTrue(s.bounds == original.bounds, "bounds preserved")
        helper.assertTrue(s.inputs.size == 1 && s.inputs[0].pos == BlockPos(1, 0, 0), "input position preserved")
        helper.assertTrue(s.inputs[0].conditions.isEmpty(), "input conditions cleared")
        helper.assertTrue(s.lifespan != 99, "lifespan reset")
    }
}
```

- [ ] **Step 3: Test marker tool rejects Runner and out-of-bounds**

```kotlin
@GameTest(...)
fun markerToolRejectsRunnerBlock(helper: GameTestHelper) {
    // Place Runner with a spec, attempt to use input marker tool inside its bounds, assert no entry added.
}
```

- [ ] **Step 4: Run game tests**

```bash
cmd.exe /c "./gradlew.bat :26.1:runGametest"
```
(If the project uses a different gametest task, find it via `./gradlew.bat :26.1:tasks --group verification`.)

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "test: game tests for block transformations and marker scope"
```

---

### Task 6.2 — Final full-build verification

- [ ] **Step 1: Run full build**

```bash
cmd.exe /c "./gradlew.bat :26.1:clientClasses :26.1:classes :26.1:gameTestClasses :26.1:testClasses :26.1:test"
```
Expected: BUILD SUCCESSFUL, all tests pass.

- [ ] **Step 2: Manual playthrough in dev client**

```bash
cmd.exe /c "./gradlew.bat :26.1:runClient"
```

Walk through the full intended workflow end-to-end:
1. Place a Recorder → opens Recorder GUI, set id/mode, define bounds via Bounds button.
2. Use input + output marker tools inside bounds.
3. Try the marker tool *outside* bounds — verify rejection.
4. Pulse redstone or click Record → recording runs → falling edge / Stop → block becomes Editor with derived entries.
5. Edit some entries in the Editor, click Save → block becomes Runner.
6. Open Runner → read-only Overview with Edit button. Click Edit → back to Editor.
7. Click Discard on Editor → back to Recorder, only id/bounds/markers preserved.
8. Place an empty Runner separately → spec picker GUI lists all saved specs; pick one to load.

- [ ] **Step 3: Use superpowers:requesting-code-review skill if final review desired**

---

## Self-Review Notes

- **Spec coverage:** Each spec section (architecture, transitions, marker scope, Runner, Editor, Recorder, persistence, items) has corresponding tasks.
- **Phase 1 is purely refactor** — easy to verify by running existing tests at the end of Phase 1.
- **TDD applied** to `RecordingFinalizer` only — other tasks are largely mechanical wiring or UI plumbing where unit tests have low ROI compared to the manual smoke + game tests.
- **Risk areas the executing agent should re-read code for:**
  - `StateRecording` real API shape (Task 5.1 may need to add helpers).
  - `SpecMode` semantics for input/output condition derivation (Task 5.1).
  - MC 26.1 `neighborChanged` signature (Task 5.4).
  - Existing `SpecOverviewScreen` widget layout coordinates (Task 3.3).
  - `SpecPersistence` listing / loading API (Task 4.1) — extract a shared helper if needed.
