# Redstone Spec Block Rename Design

## Summary

Rename the "spec origin" block and all related resources to "Redstone Spec". Also update the default bounding box applied when placing a new Redstone Spec block.

## Approach

Use `git mv` for all file renames to preserve file-level git history, then apply targeted text replacements in each affected file.

## Files to Rename (git mv)

| Old | New |
|---|---|
| `src/main/kotlin/.../block/SpecOriginBlock.kt` | `RedstoneSpecBlock.kt` |
| `src/main/kotlin/.../block/SpecOriginBlockEntity.kt` | `RedstoneSpecBlockEntity.kt` |
| `src/client/kotlin/.../render/SpecOriginBoundsRenderer.kt` | `RedstoneSpecBoundsRenderer.kt` |
| `assets/redstonespecs/blockstates/spec_origin.json` | `redstone_spec.json` |
| `assets/redstonespecs/items/spec_origin.json` | `redstone_spec.json` |
| `assets/redstonespecs/models/block/spec_origin.json` | `redstone_spec.json` |
| `assets/redstonespecs/models/item/spec_origin.json` | `redstone_spec.json` |
| `assets/redstonespecs/textures/block/spec_origin.png` | `redstone_spec.png` |

## Symbol Renames

| Old | New |
|---|---|
| `SpecOriginBlock` | `RedstoneSpecBlock` |
| `SpecOriginBlockEntity` | `RedstoneSpecBlockEntity` |
| `SpecOriginRenderState` | `RedstoneSpecRenderState` |
| `SpecOriginBlockEntityRenderer` | `RedstoneSpecBlockEntityRenderer` |
| `SPEC_ORIGIN_BLOCK` | `REDSTONE_SPEC_BLOCK` |
| `SPEC_ORIGIN_BLOCK_ENTITY_TYPE` | `REDSTONE_SPEC_BLOCK_ENTITY_TYPE` |
| `SPEC_ORIGIN_ITEM` | `REDSTONE_SPEC_ITEM` |
| `holdingSpecOrigin` | `holdingRedstoneSpec` |
| `"spec_origin"` (registry string IDs) | `"redstone_spec"` |
| `redstonespecs:spec_origin` (test command) | `redstonespecs:redstone_spec` |
| lang key `item.redstonespecs.spec_origin` / display `"Spec Origin"` | `item.redstonespecs.redstone_spec` / `"Redstone Spec"` |

## Files Requiring Text Updates

After renaming, update imports and symbol references in:

- `src/main/kotlin/.../ModRegistries.kt`
- `src/main/kotlin/.../Redstonespecs.kt`
- `src/main/kotlin/.../item/SpecMarkerTool.kt`
- `src/main/kotlin/.../network/NetworkRegistry.kt`
- `src/main/kotlin/.../runner/SpecRunnerCoordinator.kt`
- `src/client/kotlin/.../render/HudOverlayRenderer.kt`
- `src/client/kotlin/.../screen/SpecEditorScreen.kt`
- `src/client/kotlin/.../screen/SpecCasesScreen.kt`
- `src/client/kotlin/.../screen/SpecBoundsScreen.kt`
- `src/client/kotlin/.../screen/SpecOverviewScreen.kt`
- `src/gametest/kotlin/.../test/SpecTestContext.kt`
- `src/gametest/kotlin/.../test/RedstonespecsClientTests.kt`
- `src/main/resources/assets/redstonespecs/lang/en_us.json`
- Renamed asset JSON files (model references to `spec_origin` texture/model paths)

## Default Bounds Change

In `RedstoneSpecBlock.kt`, change the `BoundingBox` created on first right-click:

```kotlin
// Before
bounds = BoundingBox(-4, -1, -4, 4, 3, 4)

// After
bounds = BoundingBox(1, 0, 1, 5, 4, 5)
```

Offsets `(+1, 0, +1)` to `(+5, +4, +5)` relative to the block, matching `~1 0 ~1` to `~5 ~4 ~5`.
