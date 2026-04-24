# Persistence Model v1 Design

**Date:** 2026-04-24
**Scope:** Version 1 (auto-save, Run button, UI cleanup) and Version 1.2 (file browser load)

---

## Overview

Replace the manual Save/Load button workflow with automatic persistence. All in-world spec changes are immediately written to disk via a reactive `@AutoEmit` emitter. The structure file is auto-saved before each test run. A new file browser (v1.2) allows loading saved specs from disk.

---

## 1. Build & Dependencies

KSP and `auto-emit` are already configured in the project.

---

## 2. Data Model

### RedstoneSpec

Annotate with `@AutoEmit`. No field changes.

```kotlin
@AutoEmit
data class RedstoneSpec(
    val id: String,
    val mode: SpecMode,
    val bounds: BoundingBox,
    val lifespan: Int,
    val structure: String?,
    val inputs: List<InputSpec>,
    val outputs: List<OutputSpec>,
    val breakpoints: List<BreakpointSpec>,
    val autoSpecs: List<AutoSpec>,
)
```

### Structure field defaulting

- `structure` defaults to the spec's `id` when null.
- `SetSpecIdC2SPayload` handler: if `spec.structure == spec.id` (not yet diverged), update both `id` and `structure` together.
- The field is not user-editable. Future versions will add flows to reference other structs.

---

## 3. Block Entity

`RedstoneSpecBlockEntity` replaces `var spec: RedstoneSpec` with a `RedstoneSpecEmitter`.

### Initialization

```kotlin
private lateinit var specEmitter: RedstoneSpecEmitter
val spec get() = specEmitter.value
```

On `loadAdditional`: create a fresh `specEmitter = loadedSpec.emitter()`. The collector uses `drop(1)` so the initial StateFlow emission (the value set during load) is skipped and does not trigger a disk write.

### Auto-save coroutine

On block entity attach to world, launch a coroutine using `fabric-language-kotlin`'s server scope:

```kotlin
specEmitter.stateFlow
    .drop(1)
    .collect { spec ->
        setChanged()
        withContext(Dispatchers.IO) {
            SpecPersistence.save(saveDir, spec)
        }
    }
```

`saveDir` is resolved via `level.server.getWorldPath(LevelResource.ROOT).resolve(SharedSettings.specSaveDir)`.

### Packet handler migration

All handlers that currently do:
```kotlin
blockEntity.spec = blockEntity.spec.copy(field = value)
```
switch to:
```kotlin
blockEntity.specEmitter.field = value
```

---

## 4. Network / Packets

### Removed

| Packet | Reason |
|--------|--------|
| `SaveSpecC2SPayload` | Replaced by auto-save |
| `LoadSpecC2SPayload` | Replaced by file browser (v1.2) |
| `SaveStructureC2SPayload` | Replaced by auto-save before run |
| `LoadStructureC2SPayload` | Replaced by file browser (v1.2) |
| `StructureDecisionC2SPayload` | No longer needed (auto-named structure) |
| `StructurePromptS2CPayload` | No longer needed |

`OverwriteDecisionC2SPayload` and `OverwritePromptS2CPayload` are retained for the v1.2 load-from-file flow.

### New: RunSpecC2SPayload

```kotlin
data class RunSpecC2SPayload(val originPos: BlockPos) : CustomPacketPayload
```

Server handler:
1. Resolve block entity at `originPos`.
2. Auto-save structure: `StructurePersistence.save(saveDir, spec.structure ?: spec.id, level, originPos, spec.bounds)`.
3. Execute the test run (existing logic).

### Version 1.2: File Browser Packets

**`RequestFileBrowserC2SPayload(originPos: BlockPos)`**
Client requests the spec file list.

**`OpenFileBrowserS2CPayload(originPos: BlockPos, files: List<SpecFileInfo>)`**
Server responds with metadata for all valid `.json` spec files.

```kotlin
data class SpecFileInfo(
    val id: String,
    val mode: SpecMode,
    val lifespan: Int,
    val inputCount: Int,
    val outputCount: Int,
    val structure: String?,
)
```

**`LoadFromFileC2SPayload(originPos: BlockPos, specId: String)`**
Client confirms selection. Server:
1. Loads spec JSON via `SpecPersistence.load(saveDir, specId)`.
2. Loads structure NBT via `StructurePersistence.load(...)` if structure file exists.
3. If blocks exist at origin, sends `OverwritePromptS2CPayload` before applying structure.
4. Applies spec to block entity (triggers auto-save via emitter).

---

## 5. UI — Version 1

### SpecOverviewScreen

**Removed:**
- `Load` button
- `Save` button
- `Load Structure` button
- `Save Structure` button
- Struct id row (text field + pencil button)

**Added:**
- `Run` button → sends `RunSpecC2SPayload`

**Remaining action buttons:** `Run`, `Bounds`, `Done`

**Dynamic height:** Layout uses `this.height` as the available vertical space. The entry list scroll area expands to fill all remaining space after fixed-height rows (id row, mode row, lifespan row, test result row, button row).

### SpecEditorScreen

**Dynamic height:** The entries table scroll area expands to fill all remaining vertical space after fixed-height header and button rows.

### SpecBoundsScreen

**Dynamic height:** Content is vertically centered within `this.height`. No scroll area needed.

---

## 6. UI — Version 1.2

### SpecOverviewScreen

Re-adds a `Load` button → sends `RequestFileBrowserC2SPayload` → server responds with `OpenFileBrowserS2CPayload` → opens `SpecFileBrowserScreen`.

**Action buttons (v1.2):** `Load`, `Run`, `Bounds`, `Done`

### SpecFileBrowserScreen (new)

A split-panel screen:

**Left panel:** Scrollable list of spec files. Each entry shows the spec id. Clicking selects and populates the right panel preview.

**Right panel:** Preview of the selected spec:
- ID
- Mode
- Lifespan
- Input count / Output count
- Structure ID

**Bottom buttons:**
- `Load` — sends `LoadFromFileC2SPayload(originPos, selectedId)`, closes screen
- `Cancel` — closes screen without action

If blocks exist at the origin when loading, the server sends `OverwritePromptS2CPayload` before applying the structure (existing confirm dialog).
