# The Structure Info block list

**Date:** 2026-08-16
**Status:** design approved, not yet implemented

## Context

The Structure Info panel (`editor/ui/StructureInfoPanel.kt`) shows the open structure's subpath,
size, block count and last-saved time. `blockCount` is a single integer: the non-air total, counted
during the auto-save scan and carried on `StructureAutoSavedS2C`.

That total answers "how big is this build" but not "what is it made of". A builder looking at a
structure wants the materials list — every block type in it and how many of each — which is
information the server already walks past on every commit and throws away.

This spec adds that list to the panel: a scrollable, sortable per-block tally with real inventory
icons.

Related: `docs/superpowers/specs/2026-08-15-dock-stripe-and-structure-info-design.md` created the
panel this extends.

## Scope

Touches the server (tally computation), the protocol (one new S2C payload), and the client (state,
sorting, persistence, panel UI, and a new offscreen icon-rendering subsystem).

The icon subsystem in section 4 is roughly two-thirds of the work and is the part most exposed to
MC version churn. It was chosen deliberately over two cheaper alternatives, both rejected:

- **Particle-texture swatches.** `BlockStateModelSet.getParticleMaterial(state).sprite()` →
  `SpriteContents.originalImage` (private; would need a one-field accessor mixin) → `NativeImage`.
  Pure CPU, no GL, no readback — but yields a flat 16×16 texture, so stairs and fences show their
  material rather than their shape. Rejected: the icons should match the creative inventory.
- **Zero-copy Skia wrapping of MC's GPU atlas.** Skia's `DirectContext` already lives on MC's GL
  context, so a `BackendTexture` around `GuiItemAtlas`'s texture would avoid any copy. Rejected:
  that atlas is frame-scoped (its `DynamicAtlasAllocator` reclaims slots and marks them `STALE`),
  and letting Skia sample a Blaze3D-owned texture reintroduces exactly the GL-state hazard
  `ui/compose/GlStateStash.kt` exists to contain.

## 1. Server: computing the tally

`StructurePersistence.captureAutoFitIn` already walks every position in the scan volume and tests
non-air to compute the auto-fit box. The same callback gains a per-block tally beside the existing
`blockCount++`:

```kotlin
val tally = Object2IntOpenHashMap<Block>()
val fit = autoFit(scan.size.x, scan.size.y, scan.size.z) { lx, ly, lz ->
    val state = level.getBlockState(BlockPos(scan.origin.x + lx, scan.origin.y + ly, scan.origin.z + lz))
    val nonAir = !state.`is`(Blocks.AIR)
    if (nonAir) { blockCount++; tally.addTo(state.block, 1) }
    nonAir
}
```

`CapturedStructure` gains a `tally: Object2IntMap<Block>` field.

**Why the tally is exact.** The walk covers the whole *scan* volume, which is a superset of the
tight auto-fit box — but the tight box is by definition the bounding box of all non-air, so every
counted block lies inside it. This is the same reasoning that already makes `blockCount` correct,
and it costs no extra traversal.

**Counting granularity is `Block`, not `BlockState`.** Oak stairs in eight rotations are one row of
24, not eight rows of 3. This matches how a materials list is read, and it is the only granularity a
creative ordering is even defined over — creative tabs contain items, not states. Waterlogging is
ignored (a waterlogged fence counts as one fence); water and lava that exist as their own blocks
still get their own rows. Air is excluded, as it already is from `blockCount`.

**The place path.** `StructureResultS2C` carries sizes but no count, so a freshly placed structure
has `blockCount == -1` and would show an empty list until the first edit committed. A new
`StructurePersistence.tallyBlocksIn(level, box): Object2IntMap<Block>` walks a `PlacedBox` directly;
`EditorStructureHandlers` calls it on the `PlacedBox` returned by `placeStructureCentered`. That box
is already the tight box, so this is the minimal possible scan — thousands of positions, the same
order as the auto-fit scan, not the ~8M of a region-wide walk.

## 2. Protocol: `StructureBlockTallyS2C`

A new payload rather than widening the two existing ones:

```kotlin
data class BlockTallyEntry(val block: Holder<Block>, val count: Int)

data class StructureBlockTallyS2C(
    val subpath: String,
    val entries: List<BlockTallyEntry>,
) : CustomPacketPayload
```

Broadcast alongside `StructureAutoSavedS2C` on the commit path and alongside `StructureResultS2C`
on the place path. Broadcast rather than addressed, for the same reason the auto-save payload is:
structure regions are server-global.

**Why a separate payload.** Bolting a variable-length list onto both existing packets duplicates the
tally codec and forces churn on `StructureResultS2C`, which has uses that want nothing to do with a
materials list. One new payload keeps the codec in one place and leaves both existing codecs alone.

Blocks encode through `ByteBufCodecs.registry(Registries.BLOCK)` — the numeric registry id, which is
compact and resolves directly to a `Block` client-side. Entries use `ByteBufCodecs.collection` with
an explicit cap (4096) so a malformed or hostile payload cannot allocate without bound.

## 3. Client: state, sorting, and persistence

`StructureInfoState` gains an observable tally:

```kotlin
var blockTally by mutableStateOf<List<BlockTallyEntry>>(emptyList())
    private set

fun onBlockTally(p: StructureBlockTallyS2C) { blockTally = p.entries }
```

`onStructureResult` clears it, and `reset()` clears it. This is the same rule the existing
`blockCount`/`lastSavedMillis` reset follows: showing the previous structure's materials under the
new structure's name would be a lie, and the place path's own tally follows within the same tick.

### Sort mode

```kotlin
enum class BlockSortMode { COUNT, CREATIVE }
```

**Creative ordering** is a lazily-built `Map<Block, Int>` from `CreativeModeTabs.searchTab()`'s
display items, mapping each `ItemStack` through `(item as? BlockItem)?.block`. If the tabs are not
built yet — they are built on registry sync, so a structure open before that would otherwise sort
arbitrarily — it falls back to `BuiltInRegistries.BLOCK` iteration order. Blocks with no item at all
(water, fire, wall torches) never appear in a creative tab; they sort last, ordered among themselves
by registry id.

**Count ordering** is descending by count, breaking ties on creative order. The tiebreak is not
cosmetic: without it, equal-count rows would reorder arbitrarily between refreshes and the list
would jitter on every auto-save.

### Persistence

The sort mode is persisted, not `remember`-ed. `remember` in the composable would respect the dock's
mount rules but reset the toggle every time the user switched panels on the stripe, which for a
deliberately-flipped control is worse than useless.

It cannot live in a bare top-level object either — `StructureInfoPanel`'s own doc records why: the
dock composes into a long-lived singleton scene, so global panel state survives a re-mount and
paints over the next one (`DockState.mountEpoch`).

So it follows `config/ExplorerStateStore.kt`: a small store round-tripping
`config/garnet-panel-prefs.json`, with the same `configFileForTest` / `resetConfigFileForTest` seam.
Deliberately **not** part of `ModConfig`/`SharedSettings` — that object's contract is a pure
`SharedSettings` round-trip, and it is read by the dedicated server, which must never see client UI
preferences.

## 4. The icon bridge — `ui/icon/`

MC 26.2 renders inventory item icons through `GuiItemAtlas`, which rasterizes each item's model into
a slot of a GPU atlas. That atlas is unusable here — it is frame-scoped, private to `GuiRenderer`,
and GPU-only, while Compose needs CPU pixels. But its `drawToSlot` is the reference implementation
for rendering one item icon offscreen, and this subsystem mirrors it.

Both entry points it needs are **public**, so no mixin is required:
`Minecraft.getItemModelResolver()` and `GameRenderer.featureRenderDispatcher()`.

### `BlockIconRenderer`

Owns a small `TextureTarget` (built through the existing `CompositeTarget.resizeOrCreate` shape).
For one block:

1. `ItemStack(block)`, resolved by `Minecraft.getItemModelResolver().updateForTopItem(...)` into an
   `ItemStackRenderState`.
2. Set `RenderSystem.outputColorTextureOverride` / `outputDepthTextureOverride` to the target's
   views, `Projection.setupOrtho`, and `Lighting.Entry.ITEMS_3D` or `ITEMS_FLAT` according to
   `renderState.usesBlockLight()`.
3. `renderState.submit(poseStack, submitNodeStorage, 15728880, OverlayTexture.NO_OVERLAY, 0)`, then
   `GameRenderer.featureRenderDispatcher().renderAllFeatures(submitNodeStorage)`.
4. Clear both output overrides back to `null`.

### Readback

Reuses the idiom already proven in `CompositeTarget.captureToPng`:
`Screenshot.takeScreenshot(target) { image -> ... }`, a GPU download whose callback is
**asynchronous** — the cache is populated from the callback, never inline.

The resulting `NativeImage` needs both the ABGR swizzle and the bottom-up V-flip that
`docs/minecraft/blaze3d-custom-blit-pipeline-26.md` documents for render-target readback, before
becoming a Compose `ImageBitmap`.

### `BlockIconCache`

A Compose-observable `Block → ImageBitmap` map plus pending and failed sets. Rows recompose as icons
land. Each block is rendered exactly once per session.

### Pump and budget

A render-phase hook drains a bounded queue at **at most 4 icons per frame**. A structure with 200
distinct materials must not stall a frame to fill the panel; the list simply fills in over the next
second. Rendering must happen on the render thread with a live GL context, so the pump hangs off the
existing render-phase hook rather than a client tick.

### Failure isolation

Copies `ComposeSurface.disabled`: any throw out of the renderer or the readback flips a `disabled`
flag, logs once, and the panel falls back to text-only rows permanently. A missing icon is a cosmetic
loss; taking the dock down with it would not be. Rows are laid out so text never waits on the GPU —
an un-arrived icon renders as empty space of the right size, not a stalled row.

## 5. Panel UI

`StructureInfoPanel` gains a header row and a list below its existing info rows:

- **Header:** `Blocks  <total>` with a right-aligned Jewel `IconButton` toggling the sort, wrapped in
  Jewel's `Tooltip` reading `Sort: by count` or `Sort: creative order`. Icons are
  `AllIconsKeys.ObjectBrowser.SortByUsage` (count) and `.SortByType` (creative) — both present in the
  `com.jetbrains.intellij.platform:icons` artwork artifact. The icon shows the *current* mode.
- **List:** a `LazyColumn` taking `Modifier.weight(1f)`, exactly as `LocalHistoryPanel.RevisionList`
  does — the panel's `Column` must give the list the remaining height for it to scroll at all.
- **Row:** a 16dp icon, the block name from `Block.getName()` (already localized), and a
  right-aligned count.

The panel's existing no-glyph rule still applies: Jewel's default family is Inter, which has no emoji
coverage, so anything outside it renders as tofu. The sort control is an SVG icon from the artwork
artifact, not a glyph, so it is unaffected.

## 6. Testing

- **Gametest** — build a known structure and assert tally contents on both paths (commit and place),
  reusing the payload-capture idioms in `StructureRegionPersistenceSpec` and `StructureAutoSaveSpec`.
  Covers the `Block`-not-`BlockState` collapse explicitly, with a multi-rotation stair build.
- **Unit** (`src/test`) — the comparator: count-descending, creative tiebreak, itemless blocks last,
  and stability across two identical tallies.
- **Client state** (`StructureInfoStateTest`) — tally reset on `onStructureResult` and on `reset()`.
- **Client UI** (`JewelExplorerSpec` shape) — the sort toggle flips and persists across a panel
  re-mount; an icon arrives and renders non-blank; the `disabled` flag falls back to text-only rows
  without throwing.

## 7. Documentation

- Extend `docs/ui/structure-info-panel.md` with the block list, the sort control, and the persistence
  boundary.
- New `docs/ui/block-icon-bridge.md` for the offscreen render + readback subsystem: the
  `GuiItemAtlas` reference implementation, the two public entry points, the async readback, the
  swizzle/flip, the per-frame budget, and the disabled fallback.
- Register both in `docs/ui/INDEX.md`.
- Note `StructureBlockTallyS2C` in `docs/persistence/`, including why it is a separate payload.
