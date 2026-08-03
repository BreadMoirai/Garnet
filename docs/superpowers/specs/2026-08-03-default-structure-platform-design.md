# Default platform for new structures — design

**Date:** 2026-08-03
**Status:** approved, ready for planning

## Problem

`EditorNewStructure.create` writes an *empty* `StructureTemplate` NBT. Clicking a freshly created
`.nbt` in the Explorer places nothing — the player lands in an empty region with no reference plane
and has to lay down a floor by hand before building anything.

New structures should instead be initialized with a configurable platform: by default a 3×3 layer of
`minecraft:smooth_stone`.

## Settings

Three new fields on `SharedSettings` (`src/main/kotlin/com/breadmoirai/garnet/config/SharedSettings.kt`),
round-tripped in `ModConfig.load`/`ModConfig.save` (`src/client/.../config/ModConfig.kt`) exactly like
the existing keys:

| Key | Type | Default | Meaning |
|---|---|---|---|
| `newStructurePlatformBlock` | `String` | `"minecraft:smooth_stone"` | Block the platform is made of |
| `newStructurePlatformWidth` | `Int` | `3` | Platform extent along X |
| `newStructurePlatformDepth` | `Int` | `3` | Platform extent along Z |

The platform is always exactly **one block thick**. Width/depth `<= 0` disables it — `create` then
writes the empty structure it writes today.

`ModConfig.load` follows the established convention: an absent JSON key leaves the in-memory value
alone, so a hand-edited partial `garnet.json` never resets these to compiled defaults.

## Y level

No new setting. The platform occupies `y = 0` of the template, and `StructureRegionMath.anchorY`
already floors a structure shorter than `TALL_THRESHOLD` at `SharedSettings.projectGridYBase`
(64). A 1-tall structure therefore places with its platform layer at **y = 64**. Changing
`projectGridYBase` moves it, which is the correct coupling — the platform sits on the same build
plane every other placed structure is anchored to.

## Components

### `editor/ops/DefaultPlatform.kt` (new)

```kotlin
fun platformTag(width: Int, depth: Int, blockId: String): CompoundTag?
```

Pure function, no `Level`. Returns `null` when `width <= 0 || depth <= 0`. Otherwise builds the
structure NBT by hand:

- `size`: int-list `[width, 1, depth]`
- `palette`: single entry, `{ Name: <resolved block id> }`
- `blocks`: one `{ pos: [x, 0, z], state: 0 }` per cell, `width * depth` entries
- `entities`: empty list

No `Properties` are written on the palette entry: `NbtUtils.readBlockState` fills every unlisted
property from the block's default state, so a configured block that *has* properties (say
`minecraft:oak_slab`) places in its default form rather than failing to parse.

No `DataVersion` is written. `StructurePersistence.placeStructureCentered` calls
`template.load(blockGetter, nbt)` directly with no datafixer step, so the field is never read on this
path.

**Why hand-built NBT rather than `StructureTemplate`:** `StructureTemplate` exposes no public
block-adding API. The only way to populate one is `fillFromWorld`, which needs a live `Level` — and
`EditorNewStructure.create` is a pure filesystem operation that runs before anything is placed in a
world.

**Invalid block id:** resolved through `BuiltInRegistries.BLOCK`. An unparseable or unknown id logs a
`WARN` naming the offending id and falls back to `minecraft:smooth_stone`. A config typo must never
block creating a structure.

### `EditorNewStructure.create` (modified)

```kotlin
val tag = DefaultPlatform.platformTag(
    SharedSettings.newStructurePlatformWidth,
    SharedSettings.newStructurePlatformDepth,
    SharedSettings.newStructurePlatformBlock,
) ?: StructureTemplate().save(CompoundTag())
NbtIo.writeCompressed(tag, file)
```

The signature is unchanged, so the single production caller
(`EditorStructureHandlers.handleNewStructure`) and all existing gametest call sites are untouched.
Settings are read inside `create` rather than threaded through the call, matching how
`StructureCommit` and `StructureAutoSave` read `SharedSettings` directly.

### Placement

Unchanged. `handlePlaceStructure` → `placeStructureCentered` centers the 3×3 in X/Z within the
assigned structure region via `centeredStart`, and anchors it at y = 64 via `anchorY`.

## Error handling

| Case | Behavior |
|---|---|
| Unknown / malformed `newStructurePlatformBlock` | WARN, fall back to `minecraft:smooth_stone`, structure still created |
| `width <= 0` or `depth <= 0` | Platform disabled; empty structure written (today's behavior) |
| `NbtIo.writeCompressed` failure | Unchanged — propagates out of `create`, `handleNewStructure` replies `EditorErrorS2C` |

## Testing

Unit (`src/test`, which already bootstraps registries via `SharedConstants.tryDetectVersion()` +
`Bootstrap.bootStrap()`):

- `DefaultPlatformTest`
  - a 3×3 tag round-trips through `StructureTemplate.load` with `size == Vec3i(3, 1, 3)` and every
    cell holding the configured block
  - a non-default width/depth produces the right cell count
  - an unknown block id falls back to `minecraft:smooth_stone` rather than throwing
  - `width = 0` (and `depth = 0`) returns `null`
- `EditorNewStructureTest` — extend: a created file's `size` is `[3, 1, 3]` under defaults, and a
  0-width setting still yields an empty structure. Must restore `SharedSettings` after mutating it.
- `ModConfigTest` — extend: the three new keys round-trip through save/load.

Gametest:

- `EditorStructureNetworkSpec."new structure creates the file and re-sends the tree"` — extend to
  place the created structure and assert `smooth_stone` at y = 64 across the 3×3 footprint.

## Documentation

- `docs/use-cases/structure-lifecycle.md` — UC-MAN-10.d states `EditorNewStructure.create` "writes an
  empty `<name>.nbt`". Update it, and add the new-structure platform to the coverage matrix row.
- Wherever `garnet.json` keys are documented, add the three new keys with defaults.

## Known limitation (inherited)

`ModConfig` lives in the client source set, so a dedicated server never loads `garnet.json` and uses
the compiled defaults — a 3×3 smooth_stone platform. This is the same caveat already documented on
`ModConfig` for every other setting; this design does not change it.
