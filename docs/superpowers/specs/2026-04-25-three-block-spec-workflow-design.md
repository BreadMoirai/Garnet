# Three-Block Spec Workflow

**Date:** 2026-04-25
**Status:** Approved for implementation planning

## Goal

Replace the single `RedstoneSpecBlock` with three purpose-specific blocks — **Runner**, **Editor**, **Recorder** — that transform between each other while preserving spec data on the same `BlockEntity`. This separates the three lifecycle phases of a spec (run, edit, capture) into distinct in-world artifacts with distinct affordances.

## Core data model

Each block instance owns its full `RedstoneSpec` inline (no shared canonical store). This matches the existing model and Minecraft's per-block-entity data convention. Two Runners loaded from the same saved spec are independent copies after load.

## Block & BlockEntity architecture

- **One `SpecBlockEntity` class** shared by all three blocks. Holds:
  - the `RedstoneSpec` (id, mode, bounds, lifespan, structure, inputs, outputs, breakpoints, autoSpecs, entries)
  - `lastTestResult`
  - transient recording state (only populated while a Recorder is actively recording; null/empty otherwise)
- **Three `Block` classes:**
  - `RedstoneSpecRunnerBlock`
  - `RedstoneSpecEditorBlock`
  - `RedstoneSpecRecorderBlock`
- **Transformation** = `Level.setBlock` to the target block class. The `BlockEntity` survives because all three blocks declare the same `BlockEntityType`. Implemented as a helper on `SpecBlockEntity` (e.g. `transformTo(blockClass)`).

### Allowed transitions

| From → To | Trigger | Data carried |
|---|---|---|
| Runner → Editor | "Edit" button in Runner GUI | Full spec |
| Editor → Runner | "Save" button in Editor GUI | Full spec |
| Editor → Recorder | "Discard" button in Editor GUI | Only `{ id, bounds, inputs, outputs }` — everything else cleared |
| Recorder → Editor | Redstone falling edge OR GUI "Stop" | Full spec built from recording |

No other transitions exist. In particular, Runner does not respond to redstone, and Editor does not respond to redstone.

## Marker tool scope rule

`SpecMarkerTool` clicks are rejected unless the target block lies inside *some* `SpecBlockEntity`'s bounds (looked up via `SpecBlockEntity.findFor(level, pos)`). Additionally, markers on a **Runner's** bounds are rejected — only Recorder and Editor allow tagging. This prevents accidental marker placement in the open world and protects Runner specs from in-place mutation.

## Runner block

- **Inert**: ignores redstone, has no editing affordances.
- **Loaded-state GUI** (Runner has a spec): read-only overview — entries, mode, lifespan, last test result. Single action: **Edit** → transforms to Editor.
- **Empty-state GUI** (Runner placed without spec): spec picker listing saved specs from the `SpecPersistence` save dir. Selecting one loads a copy inline.
- **Ticking**: runs `SpecRunner` against the current spec on its existing schedule (unchanged from today).

## Editor block

- Drop-in of today's `RedstoneSpecBlock` GUI behavior (overview, entry editor, file browser, bounds screen).
- Ignores redstone.
- Adds two header buttons to the overview:
  - **Save** → transforms to Runner.
  - **Discard** → transforms to Recorder. Preserves *only* `{ id, bounds, inputs (marker positions), outputs (marker positions) }`. Cleared: entries, mode (reset to default), lifespan, structure, breakpoints, autoSpecs, lastTestResult.

## Recorder block

### GUI

Lets the player configure:
- spec id
- spec mode
- bounds (via existing `SpecBoundsScreen`)
- structure load (via existing file browser — same NBT-load behavior as today)
- "Record" / "Stop" button (mirrors redstone trigger)

### Recording trigger

Recording starts on **redstone rising edge** OR **GUI "Record" button**, gated on:
- bounds defined (non-empty)
- spec id set
- ≥1 input marker inside bounds
- ≥1 output marker inside bounds

If gating fails, the trigger is ignored (no error state — the GUI surfaces what's missing).

### During recording

- Full state-recording of **all** blocks in bounds via the existing `StateRecorder` / `StateRecording` infrastructure.
- **Single session** — no multi-pulse support. Once recording starts, it ends only on falling edge or GUI Stop.

### End-of-recording pipeline

Triggered by **redstone falling edge** OR **GUI "Stop"**:

1. Stop `StateRecorder`, finalize the `StateRecording`.
2. **Auto-trim** leading and trailing ticks where no *tagged I/O block* changed state. The remaining tick span becomes the spec's `lifespan`.
3. Derive `SpecEntry` list from the trimmed recording using the configured mode's input/output property rules.
4. Build the new `RedstoneSpec` and assign to the `BlockEntity`, replacing any prior spec data.
5. Transform block to Editor.

## Persistence

Unchanged. `SpecPersistence` saves the spec on any change, keyed by spec id. Multiple blocks with the same spec id collide on save (last write wins) — this matches today's behavior and is out of scope for this redesign.

## Items & registration

- Three items, one per block, registered in `ModRegistries`.
- The existing `RedstoneSpecBlock` and its item are **removed**. No backwards-compatibility shim — any saved worlds containing the old block will lose those blocks. (If world compatibility becomes a requirement, address as a follow-up.)
- Recipes are out of scope for this design (handle via datagen in a follow-up).

## Components affected / introduced

**New:**
- `RedstoneSpecRunnerBlock`, `RedstoneSpecEditorBlock`, `RedstoneSpecRecorderBlock`
- `SpecBlockEntity` (renamed/replaces `RedstoneSpecBlockEntity`, generalized to host all three blocks)
- Runner empty-state spec picker GUI
- Editor "Save" / "Discard" header buttons
- Recorder GUI (id / mode / bounds / structure / record-stop)
- End-of-recording pipeline (trim + entry derivation + transform)

**Modified:**
- `SpecMarkerTool` — add bounds-scope rejection
- `ModRegistries` — replace one block/item with three; one shared `BlockEntityType`
- `RedstoneSpecBoundsRenderer`, `HudOverlayRenderer` — adapt to the three block types if they discriminate today
- Networking — payloads that open the Overview screen need to dispatch by block type

**Removed:**
- `RedstoneSpecBlock` (the original)

## Out of scope

- Recipes / datagen
- Backwards compatibility with worlds containing the old `RedstoneSpecBlock`
- Spec-id collision policy across multiple blocks
- Item-NBT spec transfer (carrying a spec on a pickup item)
