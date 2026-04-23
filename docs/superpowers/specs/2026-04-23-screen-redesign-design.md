# Screen Redesign — Layout Primitives

## Goals

1. Remove the `DevLevel` config setting entirely.
2. Redesign all game screens to use MC layout primitives (`LinearLayout`, `FrameLayout`,
   `GridLayout`, `ScrollableLayout`) with YACL-styled widgets where appropriate.
3. All widget positions are resolved by layouts — no manual x/y coordinates.
4. Phase and Tick visibility in `EntryEditorScreen` is gated by `SpecMode`, not a config flag.

---

## Part 1 — Remove DevLevel

### Files changed

| File | Change |
|------|--------|
| `config/DevLevel.kt` | Delete entirely |
| `config/SharedSettings.kt` (same file) | Remove `devLevel` field from `SharedSettings` |
| `client/config/ModConfig.kt` | Remove `devLevel` field, remove from `load()`/`save()`, remove the YACL option block |
| `client/screen/EntryEditorScreen.kt` | Remove `standardMode` val; Tick/Phase rows are now shown conditionally based on `SpecMode` (see Part 3) |

`DevLevel.ADVANCED` was the only caller of the Phase row; that row and the Tick row are now shown or hidden based on the spec's `SpecMode`. No other files reference `DevLevel` or `SharedSettings.devLevel`.

---

## Part 2 — Layout conventions

### Layout rule

Every screen builds its widget tree in `init()` using this pattern:

```kotlin
val layout = FrameLayout(width, height)  // or LinearLayout etc.
// ... addChild calls ...
layout.arrangeElements()
layout.visitWidgets { addRenderableWidget(it) }
```

No pixel coordinates are set directly on any widget. `rebuildWidgets()` is called whenever state changes require the widget tree to change structure (e.g. toggling edit mode on an inline text field).

### Widget palette

| Widget | Source | Used for |
|--------|--------|----------|
| `LinearLayout.vertical()` / `.horizontal()` | MC | Row and column stacking |
| `FrameLayout` | MC | Centering a panel inside the screen |
| `GridLayout` | MC | Multi-column grids |
| `ScrollableLayout` | MC | Scrollable content areas |
| `SpacerElement` | MC | Padding / gaps |
| `StringWidget` | MC | Read-only text labels |
| `Button` | MC | Standard buttons |
| `CycleButton<T>` | MC | Cycling enum selectors |
| `EditBox` | MC | Free-text inputs |
| `LowProfileButtonWidget` | YACL | Action buttons inside content panels |
| `IntEditBox` | Custom | Integer input with scroll and ±1 step buttons |
| `ColorSwatchWidget` | Custom | Color preview swatch |

### Custom components

#### `IntEditBox` (extends MC `EditBox`)

Reusable integer field widget. Always used in the context:

```
StringWidget("<Label>:")  IntEditBox  Button("−")  Button("+")
```

all inside a `LinearLayout.horizontal(spacing = 2)`.

- Constructor: `IntEditBox(mc, x, y, width, height, min: Int, max: Int, initial: Int, onChange: (Int) -> Unit)`
- `getValue(): Int` — parses text, clamps to `[min, max]`
- `setValue(n: Int)` — sets text, fires `onChange`
- Overrides `mouseScrolled`: when mouse is inside the widget, scroll up/down adjusts value by ±1 (clamped), fires `onChange`
- The `−` and `+` buttons call `setValue(getValue() - 1)` / `setValue(getValue() + 1)` respectively

Special display: when `min == -1` and value is `-1`, the EditBox displays `"INIT"` instead of `"-1"`.

#### `ColorSwatchWidget` (extends MC `AbstractWidget`)

A 16×16 filled square rendering the current color. No interaction. Updated by calling `setColor(rgb: Int)`.

---

## Part 3 — Screen designs

### 3.1 `SpecOverviewScreen`

`isInGameUi = true`, `isPauseScreen = false`. Renders as a floating panel centered on screen.

```
FrameLayout(width, height)
  └─ LinearLayout.vertical (panelW, spacing = 2)
       ├─ StringWidget(title)
       ├─ LinearLayout.horizontal          ← ID row
       │    ├─ StringWidget("ID:")
       │    ├─ StringWidget(spec.id)       ← or EditBox when idEditMode = true
       │    └─ LowProfileButtonWidget("✎") / LowProfileButtonWidget("✔")
       ├─ LinearLayout.horizontal          ← Mode row
       │    ├─ StringWidget("Mode:")
       │    └─ CycleButton<SpecMode>
       ├─ LinearLayout.horizontal          ← Lifespan row
       │    ├─ StringWidget("Life:")
       │    ├─ IntEditBox (min = 1)
       │    ├─ Button("−")
       │    └─ Button("+")
       ├─ LinearLayout.horizontal          ← Structure row
       │    ├─ StringWidget("Struct:")
       │    ├─ StringWidget(spec.structure ?: "(none)")  ← or EditBox when structureEditMode = true
       │    └─ LowProfileButtonWidget("✎") / LowProfileButtonWidget("✔")
       ├─ ScrollableLayout (maxHeight = 5 × rowH)   ← entry list
       │    └─ LinearLayout.vertical
       │         └─ per entry: LowProfileButtonWidget("$tag  ${label.ifEmpty { "(unlabeled)" }}  ($x,$y,$z)")
       ├─ StringWidget(lastResultText)     ← colored pass/fail, e.g. "3/3 checks passed"
       └─ LinearLayout.horizontal          ← actions
            ├─ LowProfileButtonWidget("Run")
            ├─ LowProfileButtonWidget("Load")
            ├─ LowProfileButtonWidget("Save")
            └─ LowProfileButtonWidget("Done")
```

**State:** `idEditMode: Boolean` and `structureEditMode: Boolean` toggle which widgets appear in
those rows. Any toggle calls `rebuildWidgets()`.

**Background:** `renderBackground()` draws the semi-transparent dark fill rect for the panel area.
No YACL screen chrome.

---

### 3.2 `SpecBoundsScreen`

`isInGameUi = true`, `isPauseScreen = false`. Floating panel.

Row labels ("Offset"/"Min", "Size"/"Max") change with `displayMode`. All six coordinate fields are
`IntEditBox` instances with `min = Int.MIN_VALUE` and `max = Int.MAX_VALUE` (unbounded).

```
FrameLayout(width, height)
  └─ LinearLayout.vertical (spacing = 4)
       ├─ StringWidget(title)
       ├─ CycleButton<DisplayMode>          ← "Offset / Size" or "Min / Max"
       ├─ LinearLayout.vertical (spacing = 2)
       │    ├─ LinearLayout.horizontal (spacing = 8)   ← row 1 label + X Y Z
       │    │    ├─ StringWidget(row1Label)
       │    │    ├─ StringWidget("X:")  IntEditBox  Button("−")  Button("+")
       │    │    ├─ StringWidget("Y:")  IntEditBox  Button("−")  Button("+")
       │    │    └─ StringWidget("Z:")  IntEditBox  Button("−")  Button("+")
       │    └─ LinearLayout.horizontal (spacing = 8)   ← row 2 label + X Y Z
       │         ├─ StringWidget(row2Label)
       │         ├─ StringWidget("X:")  IntEditBox  Button("−")  Button("+")
       │         ├─ StringWidget("Y:")  IntEditBox  Button("−")  Button("+")
       │         └─ StringWidget("Z:")  IntEditBox  Button("−")  Button("+")
       └─ LinearLayout.horizontal
            ├─ LowProfileButtonWidget("Save")
            └─ LowProfileButtonWidget("Cancel")
```

`DisplayMode` toggle converts the current six `IntEditBox` values into the new display representation
(offset+size ↔ min+max), updates them in place, then calls `rebuildWidgets()` to swap row labels.
Values are never lost on mode switch. Save logic reads all six `IntEditBox` values and constructs
`BoundingBox` the same way as today.

---

### 3.3 `SpecEditorScreen`

`isInGameUi = true`, `isPauseScreen = false`. Previously a full YACL config screen; now a
layout-based panel screen. The existing `SpecEditorState` holder and `SpecEditorLazy` are removed;
state is held directly in the screen.

```
FrameLayout(width, height)
  └─ LinearLayout.vertical (spacing = 4)
       ├─ StringWidget("$typeLabel @ $entryRelPos")
       ├─ LinearLayout.horizontal                    ← Label row
       │    ├─ StringWidget("Label:")
       │    └─ EditBox (bound to workingLabel)
       ├─ LinearLayout.horizontal                    ← Color row
       │    ├─ StringWidget("Color:")
       │    ├─ EditBox (hex string, e.g. "FF0000")
       │    └─ ColorSwatchWidget (updates on EditBox change)
       ├─ StringWidget("Entries:")                   ← only for Input / Output
       ├─ ScrollableLayout                           ← only for Input / Output
       │    └─ LinearLayout.vertical
       │         └─ per entry (SimTime, StateCondition):
       │              LinearLayout.horizontal
       │                ├─ StringWidget(previewEntry(simTime, condition))
       │                ├─ LowProfileButtonWidget("Edit")
       │                └─ LowProfileButtonWidget("Remove")
       ├─ LowProfileButtonWidget("Add Entry")        ← only for Input / Output
       ├─ LowProfileButtonWidget("Capture State")    ← only for Input / Output
       ├─ LowProfileButtonWidget("Remove Spec")
       └─ LinearLayout.horizontal
            ├─ LowProfileButtonWidget("Save")
            └─ LowProfileButtonWidget("Cancel")
```

**State:** `workingLabel`, `workingColor`, `workingEntries` held on the screen object directly.
`Save` sends `SaveSpecEntryC2SPayload`. `Remove Spec` sends `RemoveSpecEntryC2SPayload` and closes.
`Edit` on an entry opens `EntryEditorScreen`; on return the updated entry is written back into
`workingEntries` and `rebuildWidgets()` is called.

The loading-wait pattern (currently `SpecEditorScreen` → `SpecEditorLazy` → YACL screen) simplifies:
`SpecEditorScreen.init()` attempts to read the block entity; if not ready it does nothing and
`tick()` retries until ready, then calls `rebuildWidgets()` to populate the layout.

---

### 3.4 `EntryEditorScreen`

`isInGameUi = true`, `isPauseScreen = false`. Previously a YACL config screen.

Tick and Phase rows are conditionally included based on the spec's `SpecMode`:

| SpecMode | Tick row | Phase row |
|----------|----------|-----------|
| `SIMPLE` | hidden | hidden |
| `TICK_AWARE` | shown | hidden |
| `UPDATE_AWARE` | shown | shown |

```
FrameLayout(width, height)
  └─ LinearLayout.vertical (spacing = 4)
       ├─ StringWidget("Add Entry" / "Edit Entry")
       ├─ LinearLayout.horizontal                    ← Tick (TICK_AWARE / UPDATE_AWARE only)
       │    ├─ StringWidget("Tick:")
       │    ├─ IntEditBox (min = -1)
       │    ├─ Button("−")
       │    └─ Button("+")
       ├─ LinearLayout.horizontal                    ← Phase (UPDATE_AWARE only)
       │    ├─ StringWidget("Phase:")
       │    └─ CycleButton<Phase> (excludes USER_INTERACTION)
       ├─ StringWidget("Conditions:")
       ├─ ScrollableLayout
       │    └─ LinearLayout.vertical
       │         └─ per PropState:
       │              LinearLayout.horizontal
       │                ├─ StringWidget(propName)
       │                └─ CycleButton<String> ("—" / values)
       └─ LinearLayout.horizontal
            ├─ LowProfileButtonWidget("Confirm")
            └─ LowProfileButtonWidget("Cancel")
```

`EntryEditorScreen` receives `originPos`, `entryRelPos`, `specMode: SpecMode`, optional
`initial: Pair<SimTime, StateCondition>?`, and a callback `onConfirm: (SimTime, StateCondition) -> Unit`.
The callback fires on Confirm; Cancel closes without calling it. The screen no longer opens a chain
of YACL screens — it is a self-contained layout screen that calls the callback and closes.

---

### 3.5 `ModConfig` screen

No layout changes. The YACL config builder is kept as-is. Only change: remove the
`"Redstone Developer Level"` option block from `createScreen()`, remove the `devLevel` field, and
remove it from `load()` / `save()`.

---

## Part 4 — Files affected summary

| File | Action |
|------|--------|
| `config/DevLevel.kt` | Delete |
| `config/SharedSettings.kt` | Remove `devLevel` field |
| `client/config/ModConfig.kt` | Remove `devLevel` field + YACL option |
| `client/screen/SpecOverviewScreen.kt` | Full rewrite using layouts |
| `client/screen/SpecBoundsScreen.kt` | Full rewrite using layouts |
| `client/screen/SpecEditorScreen.kt` | Full rewrite using layouts; remove `SpecEditorState`, `SpecEditorLazy`, `buildSpecEditorYacl` |
| `client/screen/EntryEditorScreen.kt` | Full rewrite using layouts; remove `buildEntryEditorYacl`, `PropState` sealed class is kept |
| `client/screen/IntEditBox.kt` | New file |
| `client/screen/ColorSwatchWidget.kt` | New file |
