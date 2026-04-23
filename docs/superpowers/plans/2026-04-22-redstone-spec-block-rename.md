# Redstone Spec Block Rename Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Rename the `spec_origin` block and all related code/resources to `redstone_spec` / `RedstoneSpec`, and update the default bounding box to `(1,0,1)–(5,4,5)`.

**Architecture:** All changes are pure renames — no new behaviour. Files are renamed with `git mv` to preserve history, then content is updated with targeted edits. A single compile-check commit closes each task group.

**Tech Stack:** Kotlin, Java, Minecraft Fabric mod, Gradle

---

### Task 1: git mv — Kotlin source files

**Files:**
- Rename: `src/main/kotlin/com/breadmoirai/redstonespecs/block/SpecOriginBlock.kt`
- Rename: `src/main/kotlin/com/breadmoirai/redstonespecs/block/SpecOriginBlockEntity.kt`
- Rename: `src/client/kotlin/com/breadmoirai/redstonespecs/client/render/SpecOriginBoundsRenderer.kt`

- [ ] **Step 1: Rename the three Kotlin files**

```bash
git mv src/main/kotlin/com/breadmoirai/redstonespecs/block/SpecOriginBlock.kt \
       src/main/kotlin/com/breadmoirai/redstonespecs/block/RedstoneSpecBlock.kt

git mv src/main/kotlin/com/breadmoirai/redstonespecs/block/SpecOriginBlockEntity.kt \
       src/main/kotlin/com/breadmoirai/redstonespecs/block/RedstoneSpecBlockEntity.kt

git mv src/client/kotlin/com/breadmoirai/redstonespecs/client/render/SpecOriginBoundsRenderer.kt \
       src/client/kotlin/com/breadmoirai/redstonespecs/client/render/RedstoneSpecBoundsRenderer.kt
```

Expected: `git status` shows 3 renames, no other changes.

---

### Task 2: git mv — Asset files

**Files:**
- Rename: `src/main/resources/assets/redstonespecs/blockstates/spec_origin.json`
- Rename: `src/main/resources/assets/redstonespecs/items/spec_origin.json`
- Rename: `src/main/resources/assets/redstonespecs/models/block/spec_origin.json`
- Rename: `src/main/resources/assets/redstonespecs/models/item/spec_origin.json`
- Rename: `src/main/resources/assets/redstonespecs/textures/block/spec_origin.png`

- [ ] **Step 1: Rename all asset files**

```bash
git mv src/main/resources/assets/redstonespecs/blockstates/spec_origin.json \
       src/main/resources/assets/redstonespecs/blockstates/redstone_spec.json

git mv src/main/resources/assets/redstonespecs/items/spec_origin.json \
       src/main/resources/assets/redstonespecs/items/redstone_spec.json

git mv src/main/resources/assets/redstonespecs/models/block/spec_origin.json \
       src/main/resources/assets/redstonespecs/models/block/redstone_spec.json

git mv src/main/resources/assets/redstonespecs/models/item/spec_origin.json \
       src/main/resources/assets/redstonespecs/models/item/redstone_spec.json

git mv src/main/resources/assets/redstonespecs/textures/block/spec_origin.png \
       src/main/resources/assets/redstonespecs/textures/block/redstone_spec.png
```

Expected: `git status` shows 5 additional renames (8 total).

---

### Task 3: Update content of renamed Kotlin files

**Files:**
- Modify: `src/main/kotlin/com/breadmoirai/redstonespecs/block/RedstoneSpecBlock.kt`
- Modify: `src/main/kotlin/com/breadmoirai/redstonespecs/block/RedstoneSpecBlockEntity.kt`
- Modify: `src/client/kotlin/com/breadmoirai/redstonespecs/client/render/RedstoneSpecBoundsRenderer.kt`

- [ ] **Step 1: Update `RedstoneSpecBlock.kt`**

Replace the entire file content:

```kotlin
package com.breadmoirai.redstonespecs.block

import com.breadmoirai.redstonespecs.data.RedstoneSpec
import com.breadmoirai.redstonespecs.data.SpecCase
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
import net.minecraft.world.level.levelgen.structure.BoundingBox
import net.minecraft.world.phys.BlockHitResult
import java.util.UUID

class RedstoneSpecBlock(properties: Properties) : BaseEntityBlock(properties) {

    override fun codec(): MapCodec<out BaseEntityBlock> = CODEC

    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity =
        RedstoneSpecBlockEntity(pos, state)

    override fun getRenderShape(state: BlockState): RenderShape = RenderShape.MODEL

    override fun useWithoutItem(
        state: BlockState,
        level: Level,
        pos: BlockPos,
        player: Player,
        hit: BlockHitResult,
    ): InteractionResult {
        if (!level.isClientSide) {
            val be = level.getBlockEntity(pos) as? RedstoneSpecBlockEntity ?: return InteractionResult.PASS
            if (be.spec == null) {
                be.setSpec(
                    RedstoneSpec(
                        id = UUID.randomUUID(),
                        name = "New Spec",
                        bounds = BoundingBox(1, 0, 1, 5, 4, 5),
                        oneShot = false,
                        specCases = listOf(SpecCase("Case 1", 20, emptyList(), emptyList(), emptyList(), emptyList())),
                    )
                )
            }
            ServerPlayNetworking.send(player as ServerPlayer, OpenOverviewS2CPayload(pos))
        }
        return InteractionResult.SUCCESS
    }

    override fun <T : BlockEntity> getTicker(
        level: Level,
        state: BlockState,
        type: BlockEntityType<T>,
    ): BlockEntityTicker<T>? = null

    companion object {
        val CODEC: MapCodec<RedstoneSpecBlock> = simpleCodec(::RedstoneSpecBlock)
    }
}
```

- [ ] **Step 2: Update `RedstoneSpecBlockEntity.kt` — class name and all internal references**

Replace every occurrence of `SpecOriginBlockEntity` with `RedstoneSpecBlockEntity` in the file. The class declaration line becomes:

```kotlin
class RedstoneSpecBlockEntity(pos: BlockPos, state: BlockState) :
    BlockEntity(ModRegistries.REDSTONE_SPEC_BLOCK_ENTITY_TYPE, pos, state) {
```

The companion object registry field reference:

```kotlin
private val registry = ConcurrentHashMap<Level, ConcurrentHashMap<BlockPos, RedstoneSpecBlockEntity>>()

private fun register(be: RedstoneSpecBlockEntity) {
    val level = be.level ?: return
    registry.getOrPut(level, ::ConcurrentHashMap)[be.blockPos] = be
}

fun findFor(level: Level, worldPos: BlockPos): RedstoneSpecBlockEntity? =
    registry[level]?.values?.find { be ->
        val s = be.spec ?: return@find false
        val b = s.bounds
        val o = be.blockPos
        worldPos.x in (o.x + b.minX())..(o.x + b.maxX()) &&
        worldPos.y in (o.y + b.minY())..(o.y + b.maxY()) &&
        worldPos.z in (o.z + b.minZ())..(o.z + b.maxZ())
    }

fun allFor(level: Level): Collection<RedstoneSpecBlockEntity> =
    registry[level]?.values ?: emptyList()
```

Also update the logger strings (optional but consistent):

```kotlin
LOGGER.debug("[RedstoneSpecBlockEntity#setSpec] setting spec '{}' at {}", newSpec.name, blockPos)
LOGGER.debug("[RedstoneSpecBlockEntity#addOrUpdateEntry] case={} pos={} type={}", specCaseIndex, entry.pos, entry.javaClass.simpleName)
LOGGER.debug("[RedstoneSpecBlockEntity#addSpecCase] adding case '{}' at {}", name, blockPos)
LOGGER.debug("[RedstoneSpecBlockEntity#removeEntry] case={} pos={}", specCaseIndex, pos)
LOGGER.debug("[RedstoneSpecBlockEntity#saveAdditional] saving at {}", blockPos)
LOGGER.debug("[RedstoneSpecBlockEntity#loadAdditional] loaded at {} spec='{}' activeCase={}", blockPos, spec?.name, activeSpecCaseIndex)
```

- [ ] **Step 3: Update `RedstoneSpecBoundsRenderer.kt` — class names**

Replace:
- `SpecOriginBlockEntity` → `RedstoneSpecBlockEntity`
- `SPEC_ORIGIN_BLOCK_ENTITY_TYPE` → `REDSTONE_SPEC_BLOCK_ENTITY_TYPE`
- `::SpecOriginBlockEntityRenderer` → `::RedstoneSpecBlockEntityRenderer`
- `SpecOriginRenderState` → `RedstoneSpecRenderState`
- `SpecOriginBlockEntityRenderer` → `RedstoneSpecBlockEntityRenderer`

The top of the file becomes:

```kotlin
import com.breadmoirai.redstonespecs.block.RedstoneSpecBlockEntity
```

The `registerBoundsRenderer` function becomes:

```kotlin
fun registerBoundsRenderer() {
    BlockEntityRendererRegistry.register(
        ModRegistries.REDSTONE_SPEC_BLOCK_ENTITY_TYPE,
        ::RedstoneSpecBlockEntityRenderer,
    )
}

class RedstoneSpecRenderState : BlockEntityRenderState() {
    var bounds: BoundingBox? = null
    var activeEntries: List<SpecEntry> = emptyList()
    var hoveredFace: HoveredFace? = null
}

class RedstoneSpecBlockEntityRenderer(ctx: BlockEntityRendererProvider.Context) :
    BlockEntityRenderer<RedstoneSpecBlockEntity, RedstoneSpecRenderState> {

    override fun createRenderState(): RedstoneSpecRenderState = RedstoneSpecRenderState()

    override fun extractRenderState(
        entity: RedstoneSpecBlockEntity,
        state: RedstoneSpecRenderState,
        partialTick: Float,
        cameraPos: net.minecraft.world.phys.Vec3,
        crumbling: net.minecraft.client.renderer.feature.ModelFeatureRenderer.CrumblingOverlay?,
    ) {
```

And `submit`:

```kotlin
    override fun submit(
        state: RedstoneSpecRenderState,
        poseStack: PoseStack,
        collector: net.minecraft.client.renderer.SubmitNodeCollector,
        cameraState: CameraRenderState,
    ) {
```

---

### Task 4: Update asset file content

**Files:**
- Modify: `src/main/resources/assets/redstonespecs/blockstates/redstone_spec.json`
- Modify: `src/main/resources/assets/redstonespecs/models/block/redstone_spec.json`
- Modify: `src/main/resources/assets/redstonespecs/models/item/redstone_spec.json`
- Modify: `src/main/resources/assets/redstonespecs/items/redstone_spec.json`

- [ ] **Step 1: Update `blockstates/redstone_spec.json`**

```json
{
  "variants": {
    "": { "model": "redstonespecs:block/redstone_spec" }
  }
}
```

- [ ] **Step 2: Update `models/block/redstone_spec.json`**

```json
{
  "parent": "minecraft:block/cube_all",
  "textures": {
    "all": "redstonespecs:block/redstone_spec"
  }
}
```

- [ ] **Step 3: Update `models/item/redstone_spec.json`**

```json
{
  "parent": "redstonespecs:block/redstone_spec"
}
```

- [ ] **Step 4: Update `items/redstone_spec.json`**

```json
{
  "model": {
    "type": "minecraft:model",
    "model": "redstonespecs:block/redstone_spec"
  }
}
```

---

### Task 5: Update `ModRegistries.kt`

**Files:**
- Modify: `src/main/kotlin/com/breadmoirai/redstonespecs/ModRegistries.kt`

- [ ] **Step 1: Replace imports and all references**

Replace:
- `import com.breadmoirai.redstonespecs.block.SpecOriginBlock` → `import com.breadmoirai.redstonespecs.block.RedstoneSpecBlock`
- `import com.breadmoirai.redstonespecs.block.SpecOriginBlockEntity` → `import com.breadmoirai.redstonespecs.block.RedstoneSpecBlockEntity`

Replace the three registry declarations:

```kotlin
val REDSTONE_SPEC_BLOCK: RedstoneSpecBlock = registerBlock(
    "redstone_spec",
    ::RedstoneSpecBlock,
    BlockBehaviour.Properties.of().strength(2f).noOcclusion()
)
val REDSTONE_SPEC_BLOCK_ENTITY_TYPE: BlockEntityType<RedstoneSpecBlockEntity> = registerBlockEntity(
    "redstone_spec",
    ::RedstoneSpecBlockEntity,
    REDSTONE_SPEC_BLOCK,
)
val REDSTONE_SPEC_ITEM: BlockItem = registerBlockItem("redstone_spec", REDSTONE_SPEC_BLOCK)
```

Replace the `registerBlockItem` signature:

```kotlin
private fun registerBlockItem(id: String, block: RedstoneSpecBlock): BlockItem {
```

---

### Task 6: Update `Redstonespecs.kt`

**Files:**
- Modify: `src/main/kotlin/com/breadmoirai/redstonespecs/Redstonespecs.kt`

- [ ] **Step 1: Update import**

```kotlin
import com.breadmoirai.redstonespecs.block.RedstoneSpecBlockEntity
```

- [ ] **Step 2: Update usage**

Replace:
```kotlin
val be = SpecOriginBlockEntity.findFor(world, pos) ?: return@register InteractionResult.PASS
```
with:
```kotlin
val be = RedstoneSpecBlockEntity.findFor(world, pos) ?: return@register InteractionResult.PASS
```

---

### Task 7: Update `SpecMarkerTool.kt`, `NetworkRegistry.kt`, `SpecRunnerCoordinator.kt`

**Files:**
- Modify: `src/main/kotlin/com/breadmoirai/redstonespecs/item/SpecMarkerTool.kt`
- Modify: `src/main/kotlin/com/breadmoirai/redstonespecs/network/NetworkRegistry.kt`
- Modify: `src/main/kotlin/com/breadmoirai/redstonespecs/runner/SpecRunnerCoordinator.kt`

- [ ] **Step 1: Update `SpecMarkerTool.kt`**

Replace:
```kotlin
import com.breadmoirai.redstonespecs.block.SpecOriginBlockEntity
```
with:
```kotlin
import com.breadmoirai.redstonespecs.block.RedstoneSpecBlockEntity
```

Replace:
```kotlin
val be = SpecOriginBlockEntity.findFor(level, hitPos) ?: return InteractionResult.PASS
```
with:
```kotlin
val be = RedstoneSpecBlockEntity.findFor(level, hitPos) ?: return InteractionResult.PASS
```

- [ ] **Step 2: Update `NetworkRegistry.kt`**

Replace:
```kotlin
import com.breadmoirai.redstonespecs.block.SpecOriginBlockEntity
```
with:
```kotlin
import com.breadmoirai.redstonespecs.block.RedstoneSpecBlockEntity
```

Replace all occurrences of `SpecOriginBlockEntity` with `RedstoneSpecBlockEntity` in the file (there are ~14 casts of the form `as? SpecOriginBlockEntity`).

- [ ] **Step 3: Update `SpecRunnerCoordinator.kt`**

Replace:
```kotlin
import com.breadmoirai.redstonespecs.block.SpecOriginBlockEntity
```
with:
```kotlin
import com.breadmoirai.redstonespecs.block.RedstoneSpecBlockEntity
```

Replace:
```kotlin
private val runners = HashMap<SpecOriginBlockEntity, SpecRunner>()
```
with:
```kotlin
private val runners = HashMap<RedstoneSpecBlockEntity, SpecRunner>()
```

And any other references to `SpecOriginBlockEntity` in that file.

---

### Task 8: Update client screen files

**Files:**
- Modify: `src/client/kotlin/com/breadmoirai/redstonespecs/client/screen/SpecEditorScreen.kt`
- Modify: `src/client/kotlin/com/breadmoirai/redstonespecs/client/screen/SpecCasesScreen.kt`
- Modify: `src/client/kotlin/com/breadmoirai/redstonespecs/client/screen/SpecBoundsScreen.kt`
- Modify: `src/client/kotlin/com/breadmoirai/redstonespecs/client/screen/SpecOverviewScreen.kt`

In each of the four files:

- [ ] **Step 1: Replace import**

```kotlin
import com.breadmoirai.redstonespecs.block.RedstoneSpecBlockEntity
```

- [ ] **Step 2: Replace all casts**

Replace every `as? SpecOriginBlockEntity` with `as? RedstoneSpecBlockEntity`.

---

### Task 9: Update `HudOverlayRenderer.kt`

**Files:**
- Modify: `src/client/kotlin/com/breadmoirai/redstonespecs/client/render/HudOverlayRenderer.kt`

- [ ] **Step 1: Replace import**

```kotlin
import com.breadmoirai.redstonespecs.block.RedstoneSpecBlockEntity
```

- [ ] **Step 2: Replace `SPEC_ORIGIN_BLOCK` references and local variable**

```kotlin
val holdingRedstoneSpec = player != null && (
    (player.mainHandItem.item as? BlockItem)?.block == ModRegistries.REDSTONE_SPEC_BLOCK ||
    (player.offhandItem.item as? BlockItem)?.block == ModRegistries.REDSTONE_SPEC_BLOCK
)

if (holdingRedstoneSpec && player != null) {
```

- [ ] **Step 3: Replace `SpecOriginBlockEntity` usages**

```kotlin
for (be in RedstoneSpecBlockEntity.allFor(level)) {
```

And all other casts/calls referencing `SpecOriginBlockEntity` in this file.

---

### Task 10: Update gametest files

**Files:**
- Modify: `src/gametest/kotlin/com/breadmoirai/redstonespecs/test/SpecTestContext.kt`
- Modify: `src/gametest/kotlin/com/breadmoirai/redstonespecs/test/RedstonespecsClientTests.kt`

- [ ] **Step 1: Update `SpecTestContext.kt`**

Replace import:
```kotlin
import com.breadmoirai.redstonespecs.block.RedstoneSpecBlockEntity
```

Replace all `SpecOriginBlockEntity` with `RedstoneSpecBlockEntity`.

- [ ] **Step 2: Update `RedstonespecsClientTests.kt`**

Replace import:
```kotlin
import com.breadmoirai.redstonespecs.block.RedstoneSpecBlockEntity
```

Replace all `SpecOriginBlockEntity` with `RedstoneSpecBlockEntity`.

Replace the setblock command:
```kotlin
ctx.runCommand("setblock 0 64 0 redstonespecs:redstone_spec")
```

---

### Task 11: Update lang file

**Files:**
- Modify: `src/main/resources/assets/redstonespecs/lang/en_us.json`

- [ ] **Step 1: Rename the lang key and display name**

```json
{
  "item.redstonespecs.redstone_spec": "Redstone Spec",
  "item.redstonespecs.input_spec_marker": "Input Spec Marker",
  "item.redstonespecs.output_spec_marker": "Output Spec Marker",
  "item.redstonespecs.breakpoint_spec_marker": "Breakpoint Spec Marker",
  "item.redstonespecs.auto_spec_marker": "Auto Spec Marker",

  "screen.redstonespecs.spec_overview": "Spec Overview",
  "screen.redstonespecs.spec_overview.spec_id": "Spec ID",
  "screen.redstonespecs.spec_overview.active_case": "Active Case",
  "screen.redstonespecs.spec_overview.no_spec": "No spec loaded",
  "screen.redstonespecs.spec_overview.run": "Run",
  "screen.redstonespecs.spec_overview.run_all": "Run All",
  "screen.redstonespecs.spec_overview.cases": "Cases",
  "screen.redstonespecs.spec_overview.bounds": "Bounds",
  "screen.redstonespecs.spec_overview.reset_load": "Reset & Load",
  "screen.redstonespecs.spec_overview.save": "Save",

  "screen.redstonespecs.spec_cases": "Test Cases",
  "screen.redstonespecs.spec_cases.name_hint": "Test case name…",

  "screen.redstonespecs.spec_bounds": "Spec Bounds",
  "screen.redstonespecs.spec_editor": "Spec Editor"
}
```

---

### Task 12: Verify build and commit

- [ ] **Step 1: Verify the project compiles**

```bash
./gradlew build 2>&1 | tail -20
```

Expected: `BUILD SUCCESSFUL`

If there are compilation errors, search for any remaining `SpecOriginBlock`, `SpecOriginBlockEntity`, `SPEC_ORIGIN_`, `spec_origin` references:

```bash
grep -r "SpecOriginBlock\|SPEC_ORIGIN_\|spec_origin" src --include="*.kt" --include="*.java" --include="*.json" -l
```

Fix any found occurrences and re-run.

- [ ] **Step 2: Commit all changes**

```bash
git add -A
git commit -m "refactor: rename spec_origin block to redstone_spec; update default bounds to (1,0,1)-(5,4,5)"
```
