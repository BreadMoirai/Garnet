---
title: Data Layer Redesign — flatten GarnetSpec, .kts authoring, JSON network-only
tags: [data-model, dsl, scripting, persistence, refactor]
summary: Simplify GarnetSpec (drop SpecMode/breakpoints/autoSpecs), flatten SpecEntry, switch on-disk format to .kts evaluated by a custom kotlin-scripting host, keep JSON only for network payloads.
---

# Data Layer Redesign

## Goals

1. **Simplify the spec data model** — remove subsystems that have grown into the data class but are not core to the project's purpose (data-driven verification of redstone circuits).
2. **Flatten `SpecEntry`** so a single entry represents one (pos, kind, time, condition) tuple, with no per-pos lists embedded.
3. **Make Kotlin the primary authoring format.** Specs are checked-in `.spec.kts` files evaluated at runtime by a custom kotlin-scripting host. JSON survives only as the network wire format.

## Non-goals

- Migrating existing on-disk JSON specs. Old worlds may fail to load; this is acceptable.
- Redesigning the runner's evaluation logic. The runner reads from a flat list of entries, but its core "evaluate condition at time T" remains untouched.
- Changing the network payload contract. JSON-via-codec stays.

---

## Data model

### Final shape

```kotlin
data class GarnetSpec(
    val id: String,
    val bounds: Vec3i,            // size only; origin always (0,0,0)
    val lifespan: Int,
    val structure: String?,       // structure resource id; supplies initial state
    val entries: List<SpecEntry>,
)

enum class EntryKind { INPUT, OUTPUT }

data class SpecEntry(
    val pos: BlockPos,            // local, 0 ≤ pos.{x,y,z} < bounds.{x,y,z}
    val label: String,
    val color: Int,               // ARGB; use -1 for opaque white
    val kind: EntryKind,
    val time: SimTime,
    val condition: StateCondition,
)
```

`GarnetSpec.init {}` validates that every `entry.pos` lies within `bounds`.

### Removed

- `SpecMode` (entire enum + file).
- `GarnetSpec.mode`, `GarnetSpec.breakpoints`, `GarnetSpec.autoSpecs`.
- `BreakpointSpec`, `AutoSpec` sealed-class siblings.
- `InputSpec`, `OutputSpec` separate classes — collapsed into `SpecEntry` with `kind` discriminator.
- `InputSpec`'s `entries: List<Pair<SimTime, StateCondition>>` shape — every (time, condition) is now its own `SpecEntry`. Multiple entries at the same `(pos, kind)` represent multi-step sequences.
- The "exactly one `SimTime.START` per InputSpec" invariant. Initial state comes from the structure file.
- `BoundingBox` representation. Replaced by `Vec3i` size; positions are local.

### Helper extensions

```kotlin
val GarnetSpec.inputs: List<SpecEntry>  get() = entries.filter { it.kind == EntryKind.INPUT }
val GarnetSpec.outputs: List<SpecEntry> get() = entries.filter { it.kind == EntryKind.OUTPUT }
fun GarnetSpec.entriesAt(pos: BlockPos): List<SpecEntry> = entries.filter { it.pos == pos }
```

---

## DSL surface (`.spec.kts` authoring)

### Example

```kotlin
garnetSpec("door_latch") {
    bounds(5, 4, 5)
    lifespan = 40
    structure = "garnet:door_latch"

    input(2, 0, 2, label = "lever", color = 0xFFFF4444.toInt()) {
        atStart { powered() }
        at(tick = 10) { not { powered() } }
    }

    output(4, 0, 4, label = "lamp", color = -1) {
        at(tick = 11) { lit() }
    }
}
```

### Building blocks

| Element | Form | Notes |
|---|---|---|
| Top-level | `garnetSpec(id) { ... }` | Last expression of the script. Returns `GarnetSpec`. |
| Bounds | `bounds(x, y, z)` | Size vector. `x,y,z >= 1`. |
| Lifespan | `lifespan = N` | Integer ticks. |
| Structure | `structure = "ns:path"` | Optional. |
| Per-pos block | `input(x, y, z, label, color) { ... }` / `output(...)` | Opens a builder; each `at(...)` inside emits one `SpecEntry`. |
| Time anchor | `atStart { ... }` / `at(tick = N) { ... }` | Resolves to `SimTime`. |
| Conditions | `powered()`, `lit()`, `block("ns:id")`, `prop(name, value)`, `intProp(name, value)`, `range(name, lo..hi)`, `containerHas(item, min)` | Map onto `StateCondition` subclasses. |
| Combinators | `all { ... }`, `any { ... }`, `not { ... }` | Composite conditions. |

### File location

`<world>/garnet/<id>.spec.kts`

---

## Translation layer

```
   .spec.kts file  ──load──►  GarnetSpec  ──emit──►  .spec.kts text
                                  ▲   │
                           (network only)
                                  │   ▼
                                JSON (Codec)
```

### Module layout

```
data/
  GarnetSpec.kt        // simplified data class (no codec — moved out)
  SpecEntry.kt           // single class + EntryKind
  StateCondition.kt      // unchanged
  SimTime.kt             // unchanged
  dsl/
    SpecDsl.kt           // garnetSpec { } entry + GarnetBuilder
    EntryDsl.kt          // input/output blocks
    ConditionDsl.kt      // powered(), lit(), all { }, etc.
  serial/
    KtsSpecLoader.kt     // .kts → GarnetSpec via custom scripting host
    KtsSpecEmitter.kt    // GarnetSpec → .kts text via KotlinPoet
    SpecJsonCodec.kt     // GarnetSpec ↔ JSON; used ONLY by network
```

### `KtsSpecLoader` (custom kotlin-scripting host)

```kotlin
@KotlinScript(
    fileExtension = "spec.kts",
    compilationConfiguration = SpecScriptConfig::class,
)
abstract class SpecScript

object SpecScriptConfig : ScriptCompilationConfiguration({
    defaultImports("com.breadmoirai.garnet.data.dsl.*")
    jvm { dependenciesFromCurrentContext(wholeClasspath = true) }
})

object KtsSpecLoader {
    private val host = BasicJvmScriptingHost()
    fun loadFile(path: Path): GarnetSpec { /* compile, eval, return result.value */ }
    fun loadString(source: String): GarnetSpec { /* same */ }
    // Compiled scripts are cached by source hash to avoid re-compile.
}
```

The script's last expression is the `garnetSpec(...)` call, whose value is the returned `GarnetSpec`. The loader unwraps `EvaluationResult.returnValue` to read it.

### `KtsSpecEmitter` (KotlinPoet)

```kotlin
object KtsSpecEmitter {
    fun emit(spec: GarnetSpec): String  // produces a .kts text
}
```

- Groups `entries` by `(pos, kind)` and emits per-pos `input(...) { ... }` / `output(...) { ... }` blocks for source readability.
- Within a per-pos block, sorts `at(...)` calls by `time` for deterministic output.
- Round-trip identity: `loadString(emit(spec)) == spec` — covered by unit tests.

### `SpecJsonCodec`

The existing `GarnetSpec.CODEC` is moved here, regenerated for the new shape. Used **only** by C2S/S2C payloads. No on-disk JSON read/write path remains.

### Dependencies (build)

Add to common `build.gradle.kts`:
- `org.jetbrains.kotlin:kotlin-scripting-common`
- `org.jetbrains.kotlin:kotlin-scripting-jvm`
- `org.jetbrains.kotlin:kotlin-scripting-jvm-host`
- `com.squareup:kotlinpoet:<latest>`

Expected JAR size impact: ~30–50 MB (kotlin compiler embeddable). KotlinPoet is small.

---

## Caller migration

| Area | Change |
|---|---|
| Screens (`SpecEditorScreen`, `RecorderSetupScreen`, `SpecOverviewScreen`, `RunnerSpecPickerScreen`, `SpecFileBrowserScreen`) | Drop SpecMode picker. Drop breakpoint/autoSpec UI. Replace `BoundingBox` controls with size controls. On save: emit `.kts` via `KtsSpecEmitter` and write to disk. On load: read via `KtsSpecLoader`. |
| Network payloads | Re-derive `SpecJsonCodec` for new shape. Payload classes unchanged in structure; just point at the new codec. |
| Runner | Delete branches on `is BreakpointSpec` / `is AutoSpec` / `SpecMode`. Replace iteration over `spec.inputs[i].entries` with iteration over `spec.inputs` directly (entries are flat). Initial-state handling reads from the structure file rather than from `SimTime.START` entries. |
| Tests (`GarnetTest`, `SpecEntryTest`, `SpecPersistenceTest`, runner tests) | Rewrite for new shape. Many tests simplify substantially. |
| Mixin code | Unaffected — mixins target MC classes, not our data model. |

### Deleted files

- `data/SpecMode.kt`
- Old `data/SpecEntry.kt` (replaced)
- On-disk JSON load/save paths in persistence helpers
- Any breakpoint / auto-spec specific helpers in runner

### Doc updates

- `docs/persistence/spec-data-model-invariants.md` — rewrite for new shape (no mode, single SpecEntry, size-only bounds).
- `docs/persistence/spec-on-disk-format.md` — rewrite: `.spec.kts` is the on-disk format; JSON is network-only.
- `docs/persistence/network-payload-contract.md` — note JSON codec unchanged in role.
- `docs/architecture/module-map.md` — note new `data/dsl/` and `data/serial/` packages.
- New: `docs/persistence/kts-script-host.md` — how the scripting host is configured, why a custom host (vs JSR-223), what's in scope for `.kts` files (DSL builders only — no arbitrary I/O expected/sandboxed).

---

## Risks & open issues

- **JAR size growth** from kotlin-scripting-jvm-host. Mitigation: JIJ-include only, document the cost. Not blocking.
- **First-load latency** for compiling a `.kts`. Mitigation: cache compiled scripts by source hash in `KtsSpecLoader`.
- **Editor round-trip fidelity** — when the in-game editor opens a hand-authored `.kts` that uses Kotlin features beyond what the emitter produces (loops, conditionals, helper functions), saving from the editor will produce a "canonicalized" file that drops those constructs. Acceptable: editor is for graphical authoring; hand-authors of advanced files should treat their `.kts` as source of truth and not save through the editor.
- **Sandboxing** — the custom scripting host pre-imports DSL but does not currently restrict `java.io.*` / arbitrary classpath access. `.kts` files come from the user's own world directory, so the threat model is "you trust files you saved yourself". Not adding sandboxing; document the assumption.

---

## Verification plan

- Unit tests:
  - `GarnetSpec.init {}` rejects entries outside bounds.
  - `KtsSpecEmitter.emit` then `KtsSpecLoader.loadString` round-trips identity for a corpus of fixture specs.
  - `SpecJsonCodec` round-trip identity.
- Game tests: existing runner game tests, ported to construct specs via the DSL, must pass.
- Manual: open editor → save → reopen → verify on-disk `.kts` is readable and round-trips.
