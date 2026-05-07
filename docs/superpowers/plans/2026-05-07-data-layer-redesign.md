# Data Layer Redesign Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Simplify `RedstoneSpec` (drop `SpecMode`/breakpoints/`autoSpecs`), flatten `SpecEntry` into a single class, and switch the on-disk format to `.spec.kts` evaluated by a custom kotlin-scripting host. JSON survives only as the network wire format.

**Architecture:** New data model in-place (no parallel namespace). New `data/dsl/` package for the builder DSL. New `data/serial/` package for the kotlin-scripting loader, KotlinPoet-based emitter, and the JSON network codec. Migration tasks ripple through runner, persistence, network, screens, and tests using the compiler as a checklist.

**Tech Stack:** Kotlin / Fabric 1.26.1 / Stonecutter (root project + per-version subprojects under `:26.1:`). New deps: `org.jetbrains.kotlin:kotlin-scripting-{common,jvm,jvm-host}`, `com.squareup:kotlinpoet`. Build verification: `cmd.exe /c "./gradlew.bat :26.1:clientClasses :26.1:classes :26.1:gametestClasses :26.1:clientTestClasses :26.1:testClasses"`.

**Spec:** [`docs/superpowers/specs/2026-05-07-data-layer-redesign-design.md`](../specs/2026-05-07-data-layer-redesign-design.md)

---

## File Structure

### New files

| Path | Responsibility |
|---|---|
| `src/main/kotlin/com/breadmoirai/redstonespecs/data/EntryKind.kt` | `enum class EntryKind { INPUT, OUTPUT }`. |
| `src/main/kotlin/com/breadmoirai/redstonespecs/data/dsl/SpecDsl.kt` | Top-level `redstoneSpec(id) { ... }` builder. Returns `RedstoneSpec`. |
| `src/main/kotlin/com/breadmoirai/redstonespecs/data/dsl/EntryDsl.kt` | `input(...) { }` / `output(...) { }` builders, `at(tick) { }`/`atStart { }`. |
| `src/main/kotlin/com/breadmoirai/redstonespecs/data/dsl/ConditionDsl.kt` | Condition builders: `powered`, `lit`, `block`, `prop`, `intProp`, `range`, `containerHas`, `all`/`any`/`not`. |
| `src/main/kotlin/com/breadmoirai/redstonespecs/data/serial/SpecJsonCodec.kt` | `RedstoneSpec.JSON_CODEC` and `SpecEntry.JSON_CODEC`. Used **only** by network. |
| `src/main/kotlin/com/breadmoirai/redstonespecs/data/serial/KtsSpecLoader.kt` | Loads `.spec.kts` → `RedstoneSpec` via custom kotlin-scripting host. |
| `src/main/kotlin/com/breadmoirai/redstonespecs/data/serial/KtsSpecEmitter.kt` | `RedstoneSpec` → `.spec.kts` text via KotlinPoet. |
| `src/main/kotlin/com/breadmoirai/redstonespecs/data/serial/SpecScript.kt` | `@KotlinScript` annotated abstract class + `ScriptCompilationConfiguration`. |
| `src/test/kotlin/com/breadmoirai/redstonespecs/data/dsl/SpecDslTest.kt` | DSL builder tests. |
| `src/test/kotlin/com/breadmoirai/redstonespecs/data/serial/SpecJsonCodecTest.kt` | JSON round-trip. |
| `src/test/kotlin/com/breadmoirai/redstonespecs/data/serial/KtsSpecLoaderTest.kt` | `.kts` loading tests. |
| `src/test/kotlin/com/breadmoirai/redstonespecs/data/serial/KtsSpecEmitterTest.kt` | Emitter + round-trip tests. |
| `docs/persistence/kts-script-host.md` | New article on the scripting host. |

### Modified files

| Path | Change |
|---|---|
| `build.gradle.kts` (root) | Add kotlin-scripting + KotlinPoet deps. |
| `src/main/kotlin/com/breadmoirai/redstonespecs/data/RedstoneSpec.kt` | Rewrite: drop mode/breakpoints/autoSpecs, change bounds to `Vec3i`, replace `inputs`/`outputs`/`breakpoints`/`autoSpecs` with single `entries: List<SpecEntry>`. Move codec out. |
| `src/main/kotlin/com/breadmoirai/redstonespecs/data/SpecEntry.kt` | Rewrite: single `data class SpecEntry(pos, label, color, kind, time, condition)`. Delete sealed hierarchy. |
| `src/main/kotlin/com/breadmoirai/redstonespecs/runner/SpecRunner.kt` | Drop `checkBreakpointsAt`, `BreakpointHit`. Replace `input.entries.find` with flat-list lookup. |
| `src/main/kotlin/com/breadmoirai/redstonespecs/runner/SpecRunnerCoordinator.kt` | Drop breakpoint UI hooks. |
| `src/main/kotlin/com/breadmoirai/redstonespecs/runner/OutputVerifier.kt` | Replace `output.entries` iteration with flat-list iteration. |
| `src/main/kotlin/com/breadmoirai/redstonespecs/runner/RecordingFinalizer.kt` | Replace per-pos `entries` build with flat `SpecEntry` emission. |
| `src/main/kotlin/com/breadmoirai/redstonespecs/persistence/SpecPersistence.kt` | Replace JSON-on-disk with `.spec.kts` via `KtsSpecLoader`/`KtsSpecEmitter`. |
| `src/main/kotlin/com/breadmoirai/redstonespecs/network/Packets.kt` | Re-point payload codecs at `SpecJsonCodec.RedstoneSpec.JSON_CODEC`. Drop `mode` from `SpecFileInfo`. |
| `src/main/kotlin/com/breadmoirai/redstonespecs/network/NetworkRegistry.kt` | Same as Packets.kt if it owns codecs. |
| `src/client/kotlin/com/breadmoirai/redstonespecs/client/screen/SpecEditorScreen.kt` | Drop SpecMode picker / breakpoint / auto-spec UI. Bounds = size. Save via `KtsSpecEmitter`. |
| `src/client/kotlin/com/breadmoirai/redstonespecs/client/screen/RecorderSetupScreen.kt` | Drop SpecMode picker. Bounds = size. |
| `src/client/kotlin/com/breadmoirai/redstonespecs/client/screen/SpecOverviewScreen.kt` | Drop breakpoint/auto display. Update for new entries. |
| `src/client/kotlin/com/breadmoirai/redstonespecs/client/screen/RunnerSpecPickerScreen.kt` | Drop mode column. |
| `src/client/kotlin/com/breadmoirai/redstonespecs/client/screen/SpecFileBrowserScreen.kt` | List `.spec.kts` files instead of `.json`. |
| `src/client/kotlin/com/breadmoirai/redstonespecs/client/render/RedstoneSpecBoundsRenderer.kt` | Bounds renderer reads new size-based bounds. |
| `src/client/kotlin/com/breadmoirai/redstonespecs/client/render/HudOverlayRenderer.kt` | Drop breakpoint hit overlay. |
| `src/main/kotlin/com/breadmoirai/redstonespecs/block/SpecBlockEntity.kt` | Update any field reads from old shape. |
| `src/main/kotlin/com/breadmoirai/redstonespecs/item/SpecMarkerTool.kt` | Update for new `SpecEntry` shape — `withEntryAddedOrUpdated` semantics. |
| `src/main/kotlin/com/breadmoirai/redstonespecs/item/UndoStack.kt` | Track `RedstoneSpec` snapshots — likely unchanged but verify. |
| `src/test/kotlin/com/breadmoirai/redstonespecs/data/RedstoneSpecTest.kt` | Rewrite for new shape. |
| `src/test/kotlin/com/breadmoirai/redstonespecs/data/SpecEntryTest.kt` | Rewrite (or delete; subsumed by SpecDslTest). |
| `src/test/kotlin/com/breadmoirai/redstonespecs/data/StateConditionTest.kt` | Update if it touches `RedstoneSpec`. |
| `src/test/kotlin/com/breadmoirai/redstonespecs/persistence/SpecPersistenceTest.kt` | Rewrite for `.kts` round-trip on disk. |
| `src/test/kotlin/com/breadmoirai/redstonespecs/runner/OutputVerifierTest.kt` | Update fixtures to flat entries. |
| `src/test/kotlin/com/breadmoirai/redstonespecs/runner/RecordingFinalizerTest.kt` | Update fixtures. |
| `src/gametest/kotlin/com/breadmoirai/redstonespecs/test/RedstonespecsGameTests.kt` | Update spec fixtures to use DSL. |
| `src/clientTest/kotlin/com/breadmoirai/redstonespecs/test/RedstonespecsClientTests.kt` | Same. |
| `docs/persistence/spec-data-model-invariants.md` | Rewrite for new shape. |
| `docs/persistence/spec-on-disk-format.md` | Rewrite: `.spec.kts` is on-disk format. |
| `docs/persistence/network-payload-contract.md` | Note JSON codec unchanged. |
| `docs/persistence/INDEX.md` | Add `kts-script-host.md` entry. |
| `docs/architecture/module-map.md` | Add `data/dsl/` and `data/serial/` packages. |

### Deleted files

| Path | Reason |
|---|---|
| `src/main/kotlin/com/breadmoirai/redstonespecs/data/SpecMode.kt` | Removed entirely. |
| `src/test/kotlin/com/breadmoirai/redstonespecs/data/SpecMarkerToolTest.kt` (only if it tests breakpoint/auto modes) | Verify whether it survives or needs rewrite. |

---

## Build Verification Command

After tasks that should compile, run:
```
cmd.exe /c "./gradlew.bat :26.1:clientClasses :26.1:classes :26.1:gametestClasses :26.1:clientTestClasses :26.1:testClasses"
```

After tasks that add/change tests, also run:
```
cmd.exe /c "./gradlew.bat :26.1:test"
```

**Note:** Tasks 2–6 will leave the build broken. The build only returns to green at Task 13. This is expected for a refactor of this shape; the alternative (parallel namespace) is more work and more error-prone.

---

## Task 1: Add build dependencies

**Files:**
- Modify: `build.gradle.kts:96-112` (root project `dependencies` block)

- [ ] **Step 1: Add scripting + KotlinPoet deps**

Add these lines inside the `dependencies { ... }` block (after the existing `implementation` lines, before `testImplementation`):

```kotlin
    // Kotlin scripting host for .spec.kts authoring (data/serial/KtsSpecLoader.kt)
    implementation(kotlin("scripting-common"))
    implementation(kotlin("scripting-jvm"))
    implementation(kotlin("scripting-jvm-host"))

    // KotlinPoet for emitting .spec.kts source from RedstoneSpec (data/serial/KtsSpecEmitter.kt)
    implementation("com.squareup:kotlinpoet:1.18.1")
```

Use `kotlin("scripting-...")` (Gradle Kotlin DSL helper) so the version tracks the Kotlin Gradle plugin. Pin KotlinPoet to `1.18.1`.

- [ ] **Step 2: Verify Gradle picks up deps**

Run: `cmd.exe /c "./gradlew.bat :26.1:dependencies --configuration runtimeClasspath" | grep -E "kotlin-scripting|kotlinpoet"`
Expected: lines listing `kotlin-scripting-common`, `kotlin-scripting-jvm`, `kotlin-scripting-jvm-host`, and `kotlinpoet`.

- [ ] **Step 3: Commit**

```bash
git add build.gradle.kts
git commit -m "build: add kotlin-scripting + KotlinPoet for .spec.kts authoring"
```

---

## Task 2: Replace data model — `EntryKind`, new `SpecEntry`, simplified `RedstoneSpec`

This task replaces the data classes in-place. After this commit, the build will be broken until the migration tasks complete. Do not run a full build between this task and Task 3 — instead, verify the new types compile in isolation by running `cmd.exe /c "./gradlew.bat :26.1:compileKotlin"` only after Task 3 (which fixes the codec).

**Files:**
- Create: `src/main/kotlin/com/breadmoirai/redstonespecs/data/EntryKind.kt`
- Modify (rewrite): `src/main/kotlin/com/breadmoirai/redstonespecs/data/SpecEntry.kt`
- Modify (rewrite): `src/main/kotlin/com/breadmoirai/redstonespecs/data/RedstoneSpec.kt`
- Delete: `src/main/kotlin/com/breadmoirai/redstonespecs/data/SpecMode.kt`

- [ ] **Step 1: Create `EntryKind.kt`**

```kotlin
package com.breadmoirai.redstonespecs.data

enum class EntryKind { INPUT, OUTPUT }
```

- [ ] **Step 2: Rewrite `SpecEntry.kt`**

Replace the entire file with:

```kotlin
package com.breadmoirai.redstonespecs.data

import net.minecraft.core.BlockPos

data class SpecEntry(
    val pos: BlockPos,
    val label: String,
    val color: Int,
    val kind: EntryKind,
    val time: SimTime,
    val condition: StateCondition,
)
```

The codec moves to `data/serial/SpecJsonCodec.kt` in Task 3.

- [ ] **Step 3: Rewrite `RedstoneSpec.kt`**

Replace the entire file with:

```kotlin
package com.breadmoirai.redstonespecs.data

import net.minecraft.core.BlockPos
import net.minecraft.core.Vec3i

data class RedstoneSpec(
    val id: String,
    val bounds: Vec3i,
    val lifespan: Int,
    val structure: String?,
    val entries: List<SpecEntry>,
) {
    init {
        require(bounds.x >= 1 && bounds.y >= 1 && bounds.z >= 1) {
            "bounds must be >= 1 on all axes, got: $bounds"
        }
        for (e in entries) {
            require(e.pos.x in 0 until bounds.x &&
                    e.pos.y in 0 until bounds.y &&
                    e.pos.z in 0 until bounds.z) {
                "entry pos ${e.pos} (kind=${e.kind}, label='${e.label}') is outside bounds $bounds"
            }
        }
    }

    fun entriesAt(pos: BlockPos): List<SpecEntry> = entries.filter { it.pos == pos }

    fun withEntryAddedOrUpdated(entry: SpecEntry): RedstoneSpec {
        // "Same entry" = same (pos, kind, time). Replaces existing matching entry, or appends.
        val others = entries.filter {
            !(it.pos == entry.pos && it.kind == entry.kind && it.time == entry.time)
        }
        return copy(entries = others + entry)
    }

    fun withEntriesRemoved(pos: BlockPos): RedstoneSpec =
        copy(entries = entries.filter { it.pos != pos })

    companion object {
        val DEFAULT_BOUNDS: Vec3i = Vec3i(5, 5, 5)

        fun new(id: String) = RedstoneSpec(
            id = id,
            bounds = DEFAULT_BOUNDS,
            lifespan = 20,
            structure = null,
            entries = emptyList(),
        )
    }
}

val RedstoneSpec.inputs: List<SpecEntry>
    get() = entries.filter { it.kind == EntryKind.INPUT }

val RedstoneSpec.outputs: List<SpecEntry>
    get() = entries.filter { it.kind == EntryKind.OUTPUT }

val RedstoneSpec.allEntries: List<SpecEntry> get() = entries
```

- [ ] **Step 4: Delete `SpecMode.kt`**

```bash
rm src/main/kotlin/com/breadmoirai/redstonespecs/data/SpecMode.kt
```

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/breadmoirai/redstonespecs/data/
git commit -m "refactor(data): flatten SpecEntry, simplify RedstoneSpec, drop SpecMode

Drops SpecMode, breakpoints, autoSpecs from RedstoneSpec. Collapses
InputSpec/OutputSpec into a single SpecEntry with EntryKind discriminator.
Bounds becomes Vec3i (size); positions are local to (0,0,0) origin.
Removes the SimTime.START requirement; initial state will come from the
structure file at runtime.

Build is intentionally broken at this commit — callers and codec migrate
in subsequent commits."
```

---

## Task 3: Move JSON codec to `data/serial/SpecJsonCodec.kt`

**Files:**
- Create: `src/main/kotlin/com/breadmoirai/redstonespecs/data/serial/SpecJsonCodec.kt`
- Create: `src/test/kotlin/com/breadmoirai/redstonespecs/data/serial/SpecJsonCodecTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
// src/test/kotlin/com/breadmoirai/redstonespecs/data/serial/SpecJsonCodecTest.kt
package com.breadmoirai.redstonespecs.data.serial

import com.breadmoirai.redstonespecs.data.*
import com.mojang.serialization.JsonOps
import net.minecraft.core.BlockPos
import net.minecraft.core.Vec3i
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SpecJsonCodecTest {
    @Test
    fun `RedstoneSpec round-trips through JSON_CODEC`() {
        val spec = RedstoneSpec(
            id = "test",
            bounds = Vec3i(5, 4, 5),
            lifespan = 40,
            structure = "redstonespecs:test",
            entries = listOf(
                SpecEntry(
                    pos = BlockPos(2, 0, 2),
                    label = "lever",
                    color = 0xFFFF4444.toInt(),
                    kind = EntryKind.INPUT,
                    time = SimTime(0, Phase.START_OF_TICK),
                    condition = StateCondition.BoolProperty("powered", true),
                ),
                SpecEntry(
                    pos = BlockPos(4, 0, 4),
                    label = "lamp",
                    color = -1,
                    kind = EntryKind.OUTPUT,
                    time = SimTime(11, Phase.END_OF_TICK),
                    condition = StateCondition.BoolProperty("lit", true),
                ),
            ),
        )

        val json = SpecJsonCodec.SPEC.encodeStart(JsonOps.INSTANCE, spec).getOrThrow()
        val decoded = SpecJsonCodec.SPEC.parse(JsonOps.INSTANCE, json).getOrThrow()

        assertEquals(spec, decoded)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cmd.exe /c "./gradlew.bat :26.1:test --tests com.breadmoirai.redstonespecs.data.serial.SpecJsonCodecTest"`
Expected: COMPILATION FAILURE — `SpecJsonCodec` unresolved.

- [ ] **Step 3: Implement `SpecJsonCodec`**

```kotlin
// src/main/kotlin/com/breadmoirai/redstonespecs/data/serial/SpecJsonCodec.kt
package com.breadmoirai.redstonespecs.data.serial

import com.breadmoirai.redstonespecs.data.EntryKind
import com.breadmoirai.redstonespecs.data.RedstoneSpec
import com.breadmoirai.redstonespecs.data.SimTime
import com.breadmoirai.redstonespecs.data.SpecEntry
import com.breadmoirai.redstonespecs.data.StateCondition
import com.mojang.serialization.Codec
import com.mojang.serialization.DataResult
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.core.BlockPos
import net.minecraft.core.Vec3i
import java.util.Optional

object SpecJsonCodec {

    val ENTRY_KIND: Codec<EntryKind> = Codec.STRING.comapFlatMap(
        { s -> EntryKind.entries.find { it.name.equals(s, ignoreCase = true) }
            ?.let { DataResult.success(it) }
            ?: DataResult.error { "Unknown EntryKind: $s" } },
        { it.name.lowercase() },
    )

    val VEC3I: Codec<Vec3i> = RecordCodecBuilder.create { instance ->
        instance.group(
            Codec.INT.fieldOf("x").forGetter(Vec3i::getX),
            Codec.INT.fieldOf("y").forGetter(Vec3i::getY),
            Codec.INT.fieldOf("z").forGetter(Vec3i::getZ),
        ).apply(instance, ::Vec3i)
    }

    val ENTRY: Codec<SpecEntry> = RecordCodecBuilder.create { instance ->
        instance.group(
            BlockPos.CODEC.fieldOf("pos").forGetter(SpecEntry::pos),
            Codec.STRING.fieldOf("label").forGetter(SpecEntry::label),
            Codec.INT.fieldOf("color").forGetter(SpecEntry::color),
            ENTRY_KIND.fieldOf("kind").forGetter(SpecEntry::kind),
            SimTime.CODEC.fieldOf("time").forGetter(SpecEntry::time),
            StateCondition.CODEC.fieldOf("condition").forGetter(SpecEntry::condition),
        ).apply(instance, ::SpecEntry)
    }

    val SPEC: Codec<RedstoneSpec> = RecordCodecBuilder.create { instance ->
        instance.group(
            Codec.STRING.fieldOf("id").forGetter(RedstoneSpec::id),
            VEC3I.fieldOf("bounds").forGetter(RedstoneSpec::bounds),
            Codec.INT.optionalFieldOf("lifespan", 20).forGetter(RedstoneSpec::lifespan),
            Codec.STRING.optionalFieldOf("structure")
                .forGetter { Optional.ofNullable(it.structure) },
            ENTRY.listOf().optionalFieldOf("entries", emptyList())
                .forGetter(RedstoneSpec::entries),
        ).apply(instance) { id, bounds, lifespan, structure, entries ->
            RedstoneSpec(id, bounds, lifespan, structure.orElse(null), entries)
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cmd.exe /c "./gradlew.bat :26.1:test --tests com.breadmoirai.redstonespecs.data.serial.SpecJsonCodecTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/breadmoirai/redstonespecs/data/serial/SpecJsonCodec.kt \
        src/test/kotlin/com/breadmoirai/redstonespecs/data/serial/SpecJsonCodecTest.kt
git commit -m "feat(data/serial): add SpecJsonCodec for network payloads

Holds RedstoneSpec/SpecEntry codecs separately from the data classes.
Used only by network payloads — on-disk format is .spec.kts (Task 6+)."
```

---

## Task 4: Build the DSL — conditions

**Files:**
- Create: `src/main/kotlin/com/breadmoirai/redstonespecs/data/dsl/ConditionDsl.kt`

- [ ] **Step 1: Implement condition builders**

```kotlin
// src/main/kotlin/com/breadmoirai/redstonespecs/data/dsl/ConditionDsl.kt
package com.breadmoirai.redstonespecs.data.dsl

import com.breadmoirai.redstonespecs.data.StateCondition
import net.minecraft.resources.Identifier

@DslMarker
annotation class SpecDslMarker

@SpecDslMarker
class ConditionScope {
    private val conditions = mutableListOf<StateCondition>()

    fun powered(value: Boolean = true) { conditions += StateCondition.BoolProperty("powered", value) }
    fun lit(value: Boolean = true)     { conditions += StateCondition.BoolProperty("lit", value) }
    fun prop(name: String, value: Boolean) { conditions += StateCondition.BoolProperty(name, value) }
    fun prop(name: String, value: String)  { conditions += StateCondition.EnumProperty(name, value) }
    fun intProp(name: String, value: Int)  { conditions += StateCondition.IntProperty(name, value) }
    fun range(name: String, range: IntRange) {
        conditions += StateCondition.IntRange(name, range.first, range.last)
    }
    fun block(id: String) { conditions += StateCondition.BlockType(Identifier.parse(id)) }
    fun containerHas(item: String? = null, slot: Int? = null, min: Int = 1) {
        conditions += StateCondition.ContainerContents(
            slot = slot,
            item = item?.let { Identifier.parse(it) },
            minCount = min,
        )
    }
    fun all(block: ConditionScope.() -> Unit) {
        conditions += StateCondition.All(ConditionScope().apply(block).build())
    }
    fun any(block: ConditionScope.() -> Unit) {
        conditions += StateCondition.Any(ConditionScope().apply(block).build())
    }
    fun not(block: ConditionScope.() -> Unit) {
        val inner = ConditionScope().apply(block).build()
        require(inner.size == 1) { "not { } must contain exactly one condition, got ${inner.size}" }
        conditions += StateCondition.Not(inner.single())
    }

    internal fun build(): List<StateCondition> = conditions.toList()

    /** Returns the single condition for `at { ... }` blocks. Wraps multiple conditions in `All`. */
    internal fun buildSingle(): StateCondition = when (conditions.size) {
        0 -> error("at { } block produced no conditions")
        1 -> conditions.single()
        else -> StateCondition.All(conditions.toList())
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add src/main/kotlin/com/breadmoirai/redstonespecs/data/dsl/ConditionDsl.kt
git commit -m "feat(data/dsl): add ConditionScope DSL builder"
```

---

## Task 5: Build the DSL — entries and top-level spec

**Files:**
- Create: `src/main/kotlin/com/breadmoirai/redstonespecs/data/dsl/EntryDsl.kt`
- Create: `src/main/kotlin/com/breadmoirai/redstonespecs/data/dsl/SpecDsl.kt`
- Create: `src/test/kotlin/com/breadmoirai/redstonespecs/data/dsl/SpecDslTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
// src/test/kotlin/com/breadmoirai/redstonespecs/data/dsl/SpecDslTest.kt
package com.breadmoirai.redstonespecs.data.dsl

import com.breadmoirai.redstonespecs.data.EntryKind
import com.breadmoirai.redstonespecs.data.Phase
import com.breadmoirai.redstonespecs.data.SimTime
import com.breadmoirai.redstonespecs.data.StateCondition
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SpecDslTest {
    @Test
    fun `redstoneSpec builds a flat entry list`() {
        val spec = redstoneSpec("door_latch") {
            bounds(5, 4, 5)
            lifespan = 40
            structure = "redstonespecs:door_latch"

            input(2, 0, 2, label = "lever", color = 0xFFFF4444.toInt()) {
                atStart { powered() }
                at(tick = 10) { not { powered() } }
            }
            output(4, 0, 4, label = "lamp", color = -1) {
                at(tick = 11) { lit() }
            }
        }

        assertEquals("door_latch", spec.id)
        assertEquals(40, spec.lifespan)
        assertEquals(3, spec.entries.size)

        val (e0, e1, e2) = spec.entries.sortedBy { it.time }
        assertEquals(EntryKind.INPUT, e0.kind)
        assertEquals(SimTime.START, e0.time)
        assertEquals(StateCondition.BoolProperty("powered", true), e0.condition)

        assertEquals(EntryKind.INPUT, e1.kind)
        assertEquals(10, e1.time.tick)
        assertEquals(StateCondition.Not(StateCondition.BoolProperty("powered", true)), e1.condition)

        assertEquals(EntryKind.OUTPUT, e2.kind)
        assertEquals(11, e2.time.tick)
        assertEquals(Phase.END_OF_TICK, e2.time.phase)
        assertEquals(StateCondition.BoolProperty("lit", true), e2.condition)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cmd.exe /c "./gradlew.bat :26.1:test --tests com.breadmoirai.redstonespecs.data.dsl.SpecDslTest"`
Expected: COMPILATION FAILURE — `redstoneSpec` unresolved.

- [ ] **Step 3: Implement `EntryDsl.kt`**

```kotlin
// src/main/kotlin/com/breadmoirai/redstonespecs/data/dsl/EntryDsl.kt
package com.breadmoirai.redstonespecs.data.dsl

import com.breadmoirai.redstonespecs.data.EntryKind
import com.breadmoirai.redstonespecs.data.Phase
import com.breadmoirai.redstonespecs.data.SimTime
import com.breadmoirai.redstonespecs.data.SpecEntry
import net.minecraft.core.BlockPos

@SpecDslMarker
class EntryScope internal constructor(
    private val pos: BlockPos,
    private val label: String,
    private val color: Int,
    private val kind: EntryKind,
) {
    private val entries = mutableListOf<SpecEntry>()

    /** Initial-condition slot. Resolves to SimTime.START. Useful for outputs that should match before tick 0. */
    fun atStart(block: ConditionScope.() -> Unit) {
        addEntry(SimTime.START, ConditionScope().apply(block).buildSingle())
    }

    /** Anchor at `tick` with default phase per kind: inputs fire at START_OF_TICK, outputs check at END_OF_TICK. */
    fun at(tick: Int, block: ConditionScope.() -> Unit) {
        val phase = if (kind == EntryKind.INPUT) Phase.START_OF_TICK else Phase.END_OF_TICK
        addEntry(SimTime(tick, phase), ConditionScope().apply(block).buildSingle())
    }

    /** Explicit phase override for advanced cases. */
    fun at(tick: Int, phase: Phase, order: Int = 0, block: ConditionScope.() -> Unit) {
        addEntry(SimTime(tick, phase, order), ConditionScope().apply(block).buildSingle())
    }

    private fun addEntry(time: SimTime, condition: com.breadmoirai.redstonespecs.data.StateCondition) {
        entries += SpecEntry(pos, label, color, kind, time, condition)
    }

    internal fun build(): List<SpecEntry> = entries.toList()
}
```

- [ ] **Step 4: Implement `SpecDsl.kt`**

```kotlin
// src/main/kotlin/com/breadmoirai/redstonespecs/data/dsl/SpecDsl.kt
package com.breadmoirai.redstonespecs.data.dsl

import com.breadmoirai.redstonespecs.data.EntryKind
import com.breadmoirai.redstonespecs.data.RedstoneSpec
import com.breadmoirai.redstonespecs.data.SpecEntry
import net.minecraft.core.BlockPos
import net.minecraft.core.Vec3i

@SpecDslMarker
class RedstoneSpecBuilder internal constructor(private val id: String) {
    private var bounds: Vec3i = RedstoneSpec.DEFAULT_BOUNDS
    var lifespan: Int = 20
    var structure: String? = null
    private val entries = mutableListOf<SpecEntry>()

    fun bounds(x: Int, y: Int, z: Int) { bounds = Vec3i(x, y, z) }
    fun bounds(size: Vec3i) { bounds = size }

    fun input(x: Int, y: Int, z: Int, label: String = "", color: Int = -1, block: EntryScope.() -> Unit) {
        entries += EntryScope(BlockPos(x, y, z), label, color, EntryKind.INPUT).apply(block).build()
    }

    fun output(x: Int, y: Int, z: Int, label: String = "", color: Int = -1, block: EntryScope.() -> Unit) {
        entries += EntryScope(BlockPos(x, y, z), label, color, EntryKind.OUTPUT).apply(block).build()
    }

    internal fun build(): RedstoneSpec = RedstoneSpec(
        id = id, bounds = bounds, lifespan = lifespan, structure = structure, entries = entries.toList(),
    )
}

fun redstoneSpec(id: String, block: RedstoneSpecBuilder.() -> Unit): RedstoneSpec =
    RedstoneSpecBuilder(id).apply(block).build()
```

- [ ] **Step 5: Run test to verify it passes**

Run: `cmd.exe /c "./gradlew.bat :26.1:test --tests com.breadmoirai.redstonespecs.data.dsl.SpecDslTest"`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/breadmoirai/redstonespecs/data/dsl/ \
        src/test/kotlin/com/breadmoirai/redstonespecs/data/dsl/
git commit -m "feat(data/dsl): redstoneSpec { } DSL builds RedstoneSpec from Kotlin"
```

---

## Task 6: Define the script type and compilation config

**Files:**
- Create: `src/main/kotlin/com/breadmoirai/redstonespecs/data/serial/SpecScript.kt`

- [ ] **Step 1: Implement the script class + config**

```kotlin
// src/main/kotlin/com/breadmoirai/redstonespecs/data/serial/SpecScript.kt
package com.breadmoirai.redstonespecs.data.serial

import kotlin.script.experimental.annotations.KotlinScript
import kotlin.script.experimental.api.ScriptCompilationConfiguration
import kotlin.script.experimental.api.defaultImports
import kotlin.script.experimental.jvm.dependenciesFromCurrentContext
import kotlin.script.experimental.jvm.jvm

object SpecScriptCompilationConfig : ScriptCompilationConfiguration({
    defaultImports(
        "com.breadmoirai.redstonespecs.data.dsl.*",
        "com.breadmoirai.redstonespecs.data.Phase",
        "com.breadmoirai.redstonespecs.data.SimTime",
    )
    jvm {
        dependenciesFromCurrentContext(wholeClasspath = true)
    }
})

@KotlinScript(
    fileExtension = "spec.kts",
    compilationConfiguration = SpecScriptCompilationConfig::class,
)
abstract class SpecScript
```

- [ ] **Step 2: Commit**

```bash
git add src/main/kotlin/com/breadmoirai/redstonespecs/data/serial/SpecScript.kt
git commit -m "feat(data/serial): add SpecScript type for kotlin-scripting host"
```

---

## Task 7: Implement `KtsSpecLoader`

**Files:**
- Create: `src/main/kotlin/com/breadmoirai/redstonespecs/data/serial/KtsSpecLoader.kt`
- Create: `src/test/kotlin/com/breadmoirai/redstonespecs/data/serial/KtsSpecLoaderTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
// src/test/kotlin/com/breadmoirai/redstonespecs/data/serial/KtsSpecLoaderTest.kt
package com.breadmoirai.redstonespecs.data.serial

import com.breadmoirai.redstonespecs.data.EntryKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class KtsSpecLoaderTest {
    @Test
    fun `loadString parses a minimal spec`() {
        val source = """
            redstoneSpec("simple") {
                bounds(3, 3, 3)
                lifespan = 5
                input(1, 0, 1, label = "in") { atStart { powered() } }
                output(2, 0, 2, label = "out") { at(tick = 4) { lit() } }
            }
        """.trimIndent()

        val spec = KtsSpecLoader.loadString(source)

        assertEquals("simple", spec.id)
        assertEquals(5, spec.lifespan)
        assertEquals(2, spec.entries.size)
        assertEquals(setOf(EntryKind.INPUT, EntryKind.OUTPUT), spec.entries.map { it.kind }.toSet())
    }

    @Test
    fun `loadString surfaces compilation errors`() {
        val source = """redstoneSpec("bad") { not_a_function() }"""
        val ex = runCatching { KtsSpecLoader.loadString(source) }.exceptionOrNull()
        require(ex != null) { "expected exception for invalid script" }
        assert(ex.message!!.contains("not_a_function") || ex.message!!.contains("unresolved")) {
            "expected compile error mentioning 'not_a_function', got: ${ex.message}"
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cmd.exe /c "./gradlew.bat :26.1:test --tests com.breadmoirai.redstonespecs.data.serial.KtsSpecLoaderTest"`
Expected: COMPILATION FAILURE — `KtsSpecLoader` unresolved.

- [ ] **Step 3: Implement `KtsSpecLoader.kt`**

```kotlin
// src/main/kotlin/com/breadmoirai/redstonespecs/data/serial/KtsSpecLoader.kt
package com.breadmoirai.redstonespecs.data.serial

import com.breadmoirai.redstonespecs.data.RedstoneSpec
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.script.experimental.api.EvaluationResult
import kotlin.script.experimental.api.ResultValue
import kotlin.script.experimental.api.ResultWithDiagnostics
import kotlin.script.experimental.api.ScriptEvaluationConfiguration
import kotlin.script.experimental.host.toScriptSource
import kotlin.script.experimental.jvmhost.BasicJvmScriptingHost

object KtsSpecLoader {
    private val host = BasicJvmScriptingHost()
    private val evalConfig = ScriptEvaluationConfiguration { /* defaults */ }

    fun loadFile(path: Path): RedstoneSpec = loadString(path.readText(), name = path.fileName.toString())

    fun loadString(source: String, name: String = "spec.kts"): RedstoneSpec {
        val scriptSource = source.toScriptSource(name)
        val result = host.eval(scriptSource, SpecScriptCompilationConfig, evalConfig)
        return when (result) {
            is ResultWithDiagnostics.Success -> extractSpec(result.value, name)
            is ResultWithDiagnostics.Failure -> {
                val msg = result.reports.joinToString("\n") { "  ${it.severity}: ${it.message}" }
                error("Failed to load $name:\n$msg")
            }
        }
    }

    private fun extractSpec(eval: EvaluationResult, name: String): RedstoneSpec {
        val rv = eval.returnValue
        return when (rv) {
            is ResultValue.Value -> rv.value as? RedstoneSpec
                ?: error("$name: last expression must be RedstoneSpec, got ${rv.type}")
            is ResultValue.Unit -> error("$name: script must end with redstoneSpec(...) expression")
            is ResultValue.Error -> throw rv.error
            ResultValue.NotEvaluated -> error("$name: script not evaluated")
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cmd.exe /c "./gradlew.bat :26.1:test --tests com.breadmoirai.redstonespecs.data.serial.KtsSpecLoaderTest"`
Expected: PASS. Note: first run may take 5-15s as the scripting compiler warms up.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/breadmoirai/redstonespecs/data/serial/KtsSpecLoader.kt \
        src/test/kotlin/com/breadmoirai/redstonespecs/data/serial/KtsSpecLoaderTest.kt
git commit -m "feat(data/serial): KtsSpecLoader evaluates .spec.kts via custom host

Uses BasicJvmScriptingHost with SpecScriptCompilationConfig (Task 6) which
pre-imports the DSL. Extracts the script's last expression as RedstoneSpec.
Surfaces compile errors as exception messages."
```

---

## Task 8: Implement `KtsSpecEmitter` with KotlinPoet, plus round-trip test

**Files:**
- Create: `src/main/kotlin/com/breadmoirai/redstonespecs/data/serial/KtsSpecEmitter.kt`
- Create: `src/test/kotlin/com/breadmoirai/redstonespecs/data/serial/KtsSpecEmitterTest.kt`

- [ ] **Step 1: Write the failing round-trip test**

```kotlin
// src/test/kotlin/com/breadmoirai/redstonespecs/data/serial/KtsSpecEmitterTest.kt
package com.breadmoirai.redstonespecs.data.serial

import com.breadmoirai.redstonespecs.data.*
import net.minecraft.core.BlockPos
import net.minecraft.core.Vec3i
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class KtsSpecEmitterTest {
    @Test
    fun `emit then loadString round-trips identity`() {
        val spec = RedstoneSpec(
            id = "round_trip",
            bounds = Vec3i(5, 4, 5),
            lifespan = 40,
            structure = "redstonespecs:rt",
            entries = listOf(
                SpecEntry(BlockPos(2, 0, 2), "lever", 0xFFFF4444.toInt(),
                    EntryKind.INPUT, SimTime.START,
                    StateCondition.BoolProperty("powered", true)),
                SpecEntry(BlockPos(2, 0, 2), "lever", 0xFFFF4444.toInt(),
                    EntryKind.INPUT, SimTime(10, Phase.START_OF_TICK),
                    StateCondition.Not(StateCondition.BoolProperty("powered", true))),
                SpecEntry(BlockPos(4, 0, 4), "lamp", -1,
                    EntryKind.OUTPUT, SimTime(11, Phase.END_OF_TICK),
                    StateCondition.BoolProperty("lit", true)),
            ),
        )
        val source = KtsSpecEmitter.emit(spec)
        val reloaded = KtsSpecLoader.loadString(source, name = "round_trip.spec.kts")
        assertEquals(spec, reloaded)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cmd.exe /c "./gradlew.bat :26.1:test --tests com.breadmoirai.redstonespecs.data.serial.KtsSpecEmitterTest"`
Expected: COMPILATION FAILURE — `KtsSpecEmitter` unresolved.

- [ ] **Step 3: Implement `KtsSpecEmitter.kt`**

```kotlin
// src/main/kotlin/com/breadmoirai/redstonespecs/data/serial/KtsSpecEmitter.kt
package com.breadmoirai.redstonespecs.data.serial

import com.breadmoirai.redstonespecs.data.EntryKind
import com.breadmoirai.redstonespecs.data.Phase
import com.breadmoirai.redstonespecs.data.RedstoneSpec
import com.breadmoirai.redstonespecs.data.SimTime
import com.breadmoirai.redstonespecs.data.SpecEntry
import com.breadmoirai.redstonespecs.data.StateCondition
import com.squareup.kotlinpoet.CodeBlock

object KtsSpecEmitter {

    fun emit(spec: RedstoneSpec): String {
        val out = CodeBlock.builder()
        out.beginControlFlow("redstoneSpec(%S)", spec.id)
        out.addStatement("bounds(%L, %L, %L)", spec.bounds.x, spec.bounds.y, spec.bounds.z)
        out.addStatement("lifespan = %L", spec.lifespan)
        spec.structure?.let { out.addStatement("structure = %S", it) }

        val grouped = spec.entries.groupBy { Triple(it.pos, it.kind, EntryHeader(it.label, it.color)) }
        for ((key, entries) in grouped) {
            val (pos, kind, header) = key
            val fn = if (kind == EntryKind.INPUT) "input" else "output"
            out.beginControlFlow(
                "$fn(%L, %L, %L, label = %S, color = %L)",
                pos.x, pos.y, pos.z, header.label, formatColor(header.color),
            )
            for (entry in entries.sortedBy { it.time }) {
                emitTimeBlock(out, entry.time, kind, entry.condition)
            }
            out.endControlFlow()
        }
        out.endControlFlow()
        return out.build().toString()
    }

    private data class EntryHeader(val label: String, val color: Int)

    private fun formatColor(c: Int): String = when (c) {
        -1 -> "-1"
        else -> "0x%08X.toInt()".format(c)
    }

    private fun emitTimeBlock(out: CodeBlock.Builder, time: SimTime, kind: EntryKind, cond: StateCondition) {
        val isStart = time == SimTime.START
        val defaultPhase = if (kind == EntryKind.INPUT) Phase.START_OF_TICK else Phase.END_OF_TICK
        when {
            isStart -> out.beginControlFlow("atStart")
            time.phase == defaultPhase && time.order == 0 ->
                out.beginControlFlow("at(tick = %L)", time.tick)
            else -> out.beginControlFlow(
                "at(tick = %L, phase = %T.%L, order = %L)",
                time.tick, Phase::class, time.phase.name, time.order,
            )
        }
        emitCondition(out, cond)
        out.endControlFlow()
    }

    private fun emitCondition(out: CodeBlock.Builder, c: StateCondition) {
        when (c) {
            is StateCondition.BoolProperty -> when (c.name) {
                "powered" -> out.addStatement("powered(%L)", c.value)
                "lit"     -> out.addStatement("lit(%L)", c.value)
                else      -> out.addStatement("prop(%S, %L)", c.name, c.value)
            }
            is StateCondition.IntProperty  -> out.addStatement("intProp(%S, %L)", c.name, c.value)
            is StateCondition.EnumProperty -> out.addStatement("prop(%S, %S)", c.name, c.value)
            is StateCondition.IntRange     -> out.addStatement("range(%S, %L..%L)", c.name, c.min, c.max)
            is StateCondition.BlockType    -> out.addStatement("block(%S)", c.blockId.toString())
            is StateCondition.ContainerContents -> {
                val args = buildList {
                    c.item?.let { add("""item = "$it"""") }
                    c.slot?.let { add("slot = $it") }
                    if (c.minCount != 1) add("min = ${c.minCount}")
                }
                out.addStatement("containerHas(${args.joinToString(", ")})")
            }
            is StateCondition.All -> {
                out.beginControlFlow("all")
                c.conditions.forEach { emitCondition(out, it) }
                out.endControlFlow()
            }
            is StateCondition.Any -> {
                out.beginControlFlow("any")
                c.conditions.forEach { emitCondition(out, it) }
                out.endControlFlow()
            }
            is StateCondition.Not -> {
                out.beginControlFlow("not")
                emitCondition(out, c.condition)
                out.endControlFlow()
            }
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cmd.exe /c "./gradlew.bat :26.1:test --tests com.breadmoirai.redstonespecs.data.serial.KtsSpecEmitterTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/breadmoirai/redstonespecs/data/serial/KtsSpecEmitter.kt \
        src/test/kotlin/com/breadmoirai/redstonespecs/data/serial/KtsSpecEmitterTest.kt
git commit -m "feat(data/serial): KtsSpecEmitter generates .spec.kts via KotlinPoet

Groups entries by (pos, kind, label, color) to emit per-pos blocks.
Sorts by time within a block for deterministic output. Round-trip
identity verified by KtsSpecEmitterTest."
```

---

## Task 9: Migrate the runner

**Files:**
- Modify: `src/main/kotlin/com/breadmoirai/redstonespecs/runner/SpecRunner.kt`
- Modify: `src/main/kotlin/com/breadmoirai/redstonespecs/runner/SpecRunnerCoordinator.kt`
- Modify: `src/main/kotlin/com/breadmoirai/redstonespecs/runner/OutputVerifier.kt`
- Modify: `src/main/kotlin/com/breadmoirai/redstonespecs/runner/RecordingFinalizer.kt`

- [ ] **Step 1: Rewrite `SpecRunner.applyInputsAt`**

In `SpecRunner.kt`, replace the `applyInputsAt` method (lines 65–76) with:

```kotlin
private fun applyInputsAt(simTime: SimTime) {
    val userInteractionTime = if (simTime.phase == Phase.START_OF_TICK)
        simTime.copy(phase = Phase.USER_INTERACTION) else null
    for (input in spec.inputs) {
        if (input.time != simTime && input.time != userInteractionTime) continue
        val pos = worldPos(input.pos)
        LOGGER.debug("[SpecRunner#applyInputsAt] {} applying condition to {}", simTime, pos)
        applyCondition(input.condition, pos)
    }
}
```

- [ ] **Step 2: Delete breakpoint code from `SpecRunner.kt`**

Remove:
- The top-level `data class BreakpointHit(...)` (lines 17–21).
- The `frozenAt` and `pendingBreakpointHit` properties.
- The `clearPendingBreakpointHit()` method.
- The call to `checkBreakpointsAt(simTime)` in `onPhase`.
- The `private fun checkBreakpointsAt(simTime: SimTime)` method (lines 164–174).
- The `if (frozenAt != null) return false` guard in `onPhase`.
- The `frozenAt = null` reset in `resume()` (or delete `resume()` entirely if only used for breakpoints).

If `resume()` becomes empty, leave the method but make it a no-op with a comment, OR remove it and update callers in Task 9 Step 3.

- [ ] **Step 3: Update `SpecRunnerCoordinator.kt`**

Search for references to `BreakpointHit`, `pendingBreakpointHit`, `frozenAt`, `clearPendingBreakpointHit`, `resume`. Delete each branch / call. If the coordinator exposed a "breakpoint hit" event to clients, drop the event entirely.

- [ ] **Step 4: Update `OutputVerifier.kt`**

`OutputVerifier` previously iterated `output.entries` (list of `(SimTime, StateCondition)` pairs). Now iterate `spec.outputs` directly — each `SpecEntry` is one (time, condition) pair.

Pattern to replace:
```kotlin
// OLD:
for (output in spec.outputs) {
    for ((time, cond) in output.entries) { ... use output.pos, output.label, time, cond ... }
}

// NEW:
for (output in spec.outputs) {
    // output is now a SpecEntry directly
    ... use output.pos, output.label, output.time, output.condition ...
}
```

Apply the same pattern wherever inputs/outputs are iterated.

- [ ] **Step 5: Update `RecordingFinalizer.kt`**

`RecordingFinalizer` constructed `OutputSpec(pos, label, color, entries = listOf(time to cond, ...))`. Now construct multiple `SpecEntry` rows:

```kotlin
// OLD:
OutputSpec(pos = ..., label = ..., color = ..., entries = listOf(t1 to c1, t2 to c2))

// NEW:
listOf(
    SpecEntry(pos, label, color, EntryKind.OUTPUT, t1, c1),
    SpecEntry(pos, label, color, EntryKind.OUTPUT, t2, c2),
)
```

Collect the list across all output positions and pass to `RedstoneSpec.copy(entries = ...)`.

- [ ] **Step 6: Run runner tests**

Run: `cmd.exe /c "./gradlew.bat :26.1:test --tests com.breadmoirai.redstonespecs.runner.*"`
Expected: tests still fail because they reference old types — Task 14 fixes them. But the **main** sourceset should now compile.

Verify main sourceset compiles:
Run: `cmd.exe /c "./gradlew.bat :26.1:compileKotlin"`
Expected: BUILD SUCCESSFUL (compilation of `src/main/`).

- [ ] **Step 7: Commit**

```bash
git add src/main/kotlin/com/breadmoirai/redstonespecs/runner/
git commit -m "refactor(runner): adapt to flat SpecEntry; drop breakpoint logic

SpecRunner.applyInputsAt now matches against SpecEntry.time directly.
BreakpointHit, frozenAt, pendingBreakpointHit, checkBreakpointsAt all
removed. OutputVerifier and RecordingFinalizer updated for flat entries."
```

---

## Task 10: Migrate persistence to `.spec.kts`

**Files:**
- Modify (rewrite): `src/main/kotlin/com/breadmoirai/redstonespecs/persistence/SpecPersistence.kt`

- [ ] **Step 1: Rewrite `SpecPersistence.kt`**

Replace the entire file with:

```kotlin
package com.breadmoirai.redstonespecs.persistence

import com.breadmoirai.redstonespecs.data.RedstoneSpec
import com.breadmoirai.redstonespecs.data.serial.KtsSpecEmitter
import com.breadmoirai.redstonespecs.data.serial.KtsSpecLoader
import com.breadmoirai.redstonespecs.network.SpecFileInfo
import org.slf4j.LoggerFactory
import java.nio.file.Path
import kotlin.io.path.*

private val LOGGER = LoggerFactory.getLogger("Redstone Specs")

private const val EXT = ".spec.kts"

object SpecPersistence {

    fun save(saveDir: Path, spec: RedstoneSpec) {
        saveDir.createDirectories()
        val file = saveDir.resolve("${spec.id}$EXT")
        file.writeText(KtsSpecEmitter.emit(spec))
        LOGGER.debug("[SpecPersistence#save] saved spec '{}' to {}", spec.id, file)
    }

    fun load(saveDir: Path, id: String): RedstoneSpec? {
        val file = saveDir.resolve("$id$EXT")
        if (!file.exists()) return null
        return runCatching { KtsSpecLoader.loadFile(file) }
            .onFailure { e -> LOGGER.warn("[SpecPersistence#load] failed to load '{}': {}", id, e.message) }
            .getOrNull()
    }

    fun listIds(saveDir: Path): List<String> {
        if (!saveDir.exists()) return emptyList()
        return saveDir.listDirectoryEntries("*$EXT").map {
            // strip the .spec.kts extension (two dots)
            it.fileName.toString().removeSuffix(EXT)
        }
    }

    fun listSpecsInfo(saveDir: Path): List<SpecFileInfo> {
        return listIds(saveDir).mapNotNull { id ->
            val spec = load(saveDir, id) ?: return@mapNotNull null
            SpecFileInfo(
                id = spec.id,
                lifespan = spec.lifespan,
                inputCount = spec.entries.count { it.kind == com.breadmoirai.redstonespecs.data.EntryKind.INPUT },
                outputCount = spec.entries.count { it.kind == com.breadmoirai.redstonespecs.data.EntryKind.OUTPUT },
                structure = spec.structure,
            )
        }
    }
}
```

(Note: `SpecFileInfo`'s `mode` field is dropped — Task 11 updates the data class.)

- [ ] **Step 2: Commit**

```bash
git add src/main/kotlin/com/breadmoirai/redstonespecs/persistence/SpecPersistence.kt
git commit -m "refactor(persistence): switch on-disk format to .spec.kts

Save uses KtsSpecEmitter; load uses KtsSpecLoader. JSON-on-disk path
removed. Old .json files in existing worlds will not be loaded."
```

---

## Task 11: Update network payloads

**Files:**
- Modify: `src/main/kotlin/com/breadmoirai/redstonespecs/network/Packets.kt`
- Modify: `src/main/kotlin/com/breadmoirai/redstonespecs/network/NetworkRegistry.kt`

- [ ] **Step 1: Find current codec references**

Run:
```bash
grep -n "RedstoneSpec.CODEC\|InputSpec\|OutputSpec\|BreakpointSpec\|AutoSpec\|SpecMode" \
    src/main/kotlin/com/breadmoirai/redstonespecs/network/*.kt
```

Inspect each hit.

- [ ] **Step 2: Replace codec references**

- Anywhere a payload referenced `RedstoneSpec.CODEC`, change to `com.breadmoirai.redstonespecs.data.serial.SpecJsonCodec.SPEC`.
- Anywhere a `SpecEntry.CODEC` was referenced, change to `SpecJsonCodec.ENTRY`.
- Drop `SpecFileInfo.mode: SpecMode` field — both from the data class and from any `StreamCodec.composite(...)` definition.

- [ ] **Step 3: Update `SpecFileInfo` data class**

Find `SpecFileInfo` (likely in `Packets.kt`). Remove the `mode: SpecMode` field. Remove the corresponding line from any `StreamCodec.composite(...)` construction.

- [ ] **Step 4: Verify compilation**

Run: `cmd.exe /c "./gradlew.bat :26.1:compileKotlin"`
Expected: BUILD SUCCESSFUL.

If errors remain pointing at non-network files: those are addressed in subsequent tasks.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/breadmoirai/redstonespecs/network/
git commit -m "refactor(network): point payloads at SpecJsonCodec; drop mode field"
```

---

## Task 12: Migrate blocks and items

**Files:**
- Modify: `src/main/kotlin/com/breadmoirai/redstonespecs/block/SpecBlockEntity.kt`
- Modify: `src/main/kotlin/com/breadmoirai/redstonespecs/item/SpecMarkerTool.kt`
- Modify: `src/main/kotlin/com/breadmoirai/redstonespecs/item/UndoStack.kt`

- [ ] **Step 1: Find all references to old shape**

Run:
```bash
grep -rn "InputSpec\|OutputSpec\|BreakpointSpec\|AutoSpec\|SpecMode\|spec.breakpoints\|spec.autoSpecs\|spec.mode\|\.entries " \
    src/main/kotlin/com/breadmoirai/redstonespecs/block \
    src/main/kotlin/com/breadmoirai/redstonespecs/item
```

- [ ] **Step 2: Apply mechanical translations**

Use this translation table:

| Old | New |
|---|---|
| `InputSpec(pos, label, color, entries = listOf(t to c))` | `SpecEntry(pos, label, color, EntryKind.INPUT, t, c)` |
| `OutputSpec(pos, label, color, entries = listOf(t to c))` | `SpecEntry(pos, label, color, EntryKind.OUTPUT, t, c)` |
| `BreakpointSpec(...)` / `AutoSpec(...)` constructions | Delete (feature removed). |
| `spec.inputs` / `spec.outputs` | Same name, but each element is now a `SpecEntry` (no inner `entries` list). |
| `spec.breakpoints` / `spec.autoSpecs` | Delete reads; remove related UI. |
| `spec.mode` / `SpecMode.SIMPLE` etc. | Delete. |
| `spec.allEntries` | Same name; returns `List<SpecEntry>`. |
| `spec.withEntryAddedOrUpdated(entry)` | Same; signature now takes `SpecEntry` directly. |
| `spec.withEntryRemoved(pos)` | Renamed to `withEntriesRemoved(pos)` — removes all entries at `pos`. |

If `SpecMarkerTool` had separate code paths for placing breakpoint/auto markers, delete them.

- [ ] **Step 3: Verify compilation**

Run: `cmd.exe /c "./gradlew.bat :26.1:compileKotlin"`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/com/breadmoirai/redstonespecs/block/ \
        src/main/kotlin/com/breadmoirai/redstonespecs/item/
git commit -m "refactor(blocks/items): adapt to flat SpecEntry; drop breakpoint/auto markers"
```

---

## Task 13: Migrate client screens and renderers

**Files:**
- Modify: `src/client/kotlin/com/breadmoirai/redstonespecs/client/screen/SpecEditorScreen.kt`
- Modify: `src/client/kotlin/com/breadmoirai/redstonespecs/client/screen/RecorderSetupScreen.kt`
- Modify: `src/client/kotlin/com/breadmoirai/redstonespecs/client/screen/SpecOverviewScreen.kt`
- Modify: `src/client/kotlin/com/breadmoirai/redstonespecs/client/screen/RunnerSpecPickerScreen.kt`
- Modify: `src/client/kotlin/com/breadmoirai/redstonespecs/client/screen/SpecFileBrowserScreen.kt`
- Modify: `src/client/kotlin/com/breadmoirai/redstonespecs/client/render/RedstoneSpecBoundsRenderer.kt`
- Modify: `src/client/kotlin/com/breadmoirai/redstonespecs/client/render/HudOverlayRenderer.kt`
- Modify: `src/client/kotlin/com/breadmoirai/redstonespecs/client/network/ClientNetworkHandler.kt`

- [ ] **Step 1: Find all client-side references**

Run:
```bash
grep -rn "InputSpec\|OutputSpec\|BreakpointSpec\|AutoSpec\|SpecMode\|spec.breakpoints\|spec.autoSpecs\|spec.mode\|BoundingBox" \
    src/client/kotlin/com/breadmoirai/redstonespecs/client
```

- [ ] **Step 2: Apply translations**

Same translation table as Task 12, plus:

- **Bounds:** wherever a screen exposed a `BoundingBox` editor (six int spinners for min/max), reduce to a `Vec3i` size editor (three int spinners for x/y/z size). Drop the min coords entirely.
- **SpecMode picker:** delete the dropdown. Delete any state field tracking the selected mode.
- **Breakpoint UI / Auto-spec UI:** delete (entry kind buttons, configuration panels, render highlighting).
- **Breakpoint hit overlay** in `HudOverlayRenderer`: delete.

- [ ] **Step 3: Update `SpecFileBrowserScreen`**

Change file listing predicate from `*.json` to `*.spec.kts`. Strip the `.spec.kts` (double extension) suffix when displaying ids.

- [ ] **Step 4: Update `SpecEditorScreen` save flow**

Saving an in-memory `RedstoneSpec` now goes through `SpecPersistence.save` (already updated to `KtsSpecEmitter`); no change needed in the screen if it was already calling `SpecPersistence.save`. If it ever wrote JSON directly, replace with `SpecPersistence.save`.

- [ ] **Step 5: Verify compilation**

Run: `cmd.exe /c "./gradlew.bat :26.1:compileClientKotlin"`
Expected: BUILD SUCCESSFUL.

Run full check:
Run: `cmd.exe /c "./gradlew.bat :26.1:clientClasses :26.1:classes"`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add src/client/kotlin/com/breadmoirai/redstonespecs/client/
git commit -m "refactor(client): drop SpecMode/breakpoint/auto UI; size-only bounds; .spec.kts files

Editor and recorder screens lose the SpecMode picker. Bounds editors
become 3-axis size spinners. Breakpoint/auto-spec markers and HUD overlay
removed. File browser lists .spec.kts files."
```

---

## Task 14: Migrate tests

**Files:**
- Modify: `src/test/kotlin/com/breadmoirai/redstonespecs/data/RedstoneSpecTest.kt`
- Modify: `src/test/kotlin/com/breadmoirai/redstonespecs/data/SpecEntryTest.kt`
- Modify: `src/test/kotlin/com/breadmoirai/redstonespecs/data/StateConditionTest.kt`
- Modify: `src/test/kotlin/com/breadmoirai/redstonespecs/persistence/SpecPersistenceTest.kt`
- Modify: `src/test/kotlin/com/breadmoirai/redstonespecs/runner/OutputVerifierTest.kt`
- Modify: `src/test/kotlin/com/breadmoirai/redstonespecs/runner/RecordingFinalizerTest.kt`
- Possibly delete: `src/test/kotlin/com/breadmoirai/redstonespecs/data/SpecMarkerToolTest.kt`

- [ ] **Step 1: Audit `SpecMarkerToolTest`**

Read the file. If its only purpose is testing breakpoint/auto-marker placement, delete it:
```bash
git rm src/test/kotlin/com/breadmoirai/redstonespecs/data/SpecMarkerToolTest.kt
```
Otherwise, port its remaining input/output coverage to the new model.

- [ ] **Step 2: Rewrite `RedstoneSpecTest.kt`**

Replace tests of mode-defaulting, breakpoint/auto add/remove, and the START-entry invariant with tests for:
- `RedstoneSpec.init {}` rejects entries outside bounds
- `RedstoneSpec.init {}` rejects bounds with axis < 1
- `withEntryAddedOrUpdated` replaces an existing entry with same `(pos, kind, time)` and appends otherwise
- `withEntriesRemoved(pos)` removes all entries at that pos
- `inputs` / `outputs` extension props filter by kind correctly

Use the DSL (`redstoneSpec("test") { ... }`) to construct fixtures where convenient.

- [ ] **Step 3: Rewrite `SpecEntryTest.kt`**

Most of this file likely tested the old sealed-class dispatch and the `InputSpec` START-entry invariant — both gone. Reduce to a couple of equality / `data class` sanity checks, or delete and rely on `SpecDslTest` and `SpecJsonCodecTest`.

- [ ] **Step 4: Rewrite `SpecPersistenceTest.kt`**

```kotlin
@Test
fun `save then load round-trips a spec via .spec.kts`(@TempDir tmp: Path) {
    val spec = redstoneSpec("rt") {
        bounds(3, 3, 3)
        lifespan = 10
        input(1, 0, 1, label = "in") { atStart { powered() } }
        output(2, 0, 2, label = "out") { at(tick = 5) { lit() } }
    }
    SpecPersistence.save(tmp, spec)
    val loaded = SpecPersistence.load(tmp, "rt")
    assertEquals(spec, loaded)
    assertTrue(tmp.resolve("rt.spec.kts").exists())
}
```

- [ ] **Step 5: Update `OutputVerifierTest.kt` and `RecordingFinalizerTest.kt`**

Translation pattern in fixtures:

```kotlin
// OLD:
OutputSpec(pos = ..., label = ..., color = ..., entries = listOf(t1 to c1, t2 to c2))

// NEW: build the spec with redstoneSpec { } DSL, listing each (time, condition) under one output { }:
redstoneSpec("test") {
    bounds(5, 5, 5)
    lifespan = 20
    output(x, y, z, label = "...", color = -1) {
        at(tick = t1.tick) { /* mirror c1 */ }
        at(tick = t2.tick) { /* mirror c2 */ }
    }
}
```

For tests that need fine-grained `SimTime` control (specific phase or order), use the explicit `at(tick, phase, order) { }` form.

- [ ] **Step 6: Update `StateConditionTest.kt`**

Likely only needs updates if it constructs `RedstoneSpec` or `SpecEntry`. Pure StateCondition tests should still pass unchanged.

- [ ] **Step 7: Run all tests**

Run: `cmd.exe /c "./gradlew.bat :26.1:test"`
Expected: BUILD SUCCESSFUL, all tests passing.

- [ ] **Step 8: Commit**

```bash
git add src/test/kotlin/com/breadmoirai/redstonespecs/
git commit -m "test: migrate tests to flat SpecEntry + .spec.kts persistence"
```

---

## Task 15: Migrate game tests

**Files:**
- Modify: `src/gametest/kotlin/com/breadmoirai/redstonespecs/test/RedstonespecsGameTests.kt`
- Modify: `src/clientTest/kotlin/com/breadmoirai/redstonespecs/test/RedstonespecsClientTests.kt`
- Modify: `src/clientTest/kotlin/com/breadmoirai/redstonespecs/test/SpecTestContext.kt`

- [ ] **Step 1: Find references**

Run:
```bash
grep -rn "InputSpec\|OutputSpec\|BreakpointSpec\|AutoSpec\|SpecMode" \
    src/gametest src/clientTest
```

- [ ] **Step 2: Replace fixture construction with the DSL**

Anywhere a game test built a `RedstoneSpec` by calling constructors directly, switch to `redstoneSpec(...) { ... }`. This makes fixtures readable and exercises the DSL in integration.

- [ ] **Step 3: Verify gametest + clientTest compile**

Run: `cmd.exe /c "./gradlew.bat :26.1:gametestClasses :26.1:clientTestClasses"`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Run gametests**

Run: `cmd.exe /c "./gradlew.bat :26.1:runGameTest"`
Expected: BUILD SUCCESSFUL, all gametests passing. (May take 1–3 minutes.)

If specific gametests are unrelated to the data layer and pass on `main`, an unexpected failure here is a bug introduced by the refactor — investigate before continuing.

- [ ] **Step 5: Commit**

```bash
git add src/gametest/ src/clientTest/
git commit -m "test: port game/client tests to redstoneSpec { } DSL fixtures"
```

---

## Task 16: Update documentation

**Files:**
- Create: `docs/persistence/kts-script-host.md`
- Modify (rewrite): `docs/persistence/spec-data-model-invariants.md`
- Modify (rewrite): `docs/persistence/spec-on-disk-format.md`
- Modify: `docs/persistence/network-payload-contract.md`
- Modify: `docs/persistence/INDEX.md`
- Modify: `docs/architecture/module-map.md`

- [ ] **Step 1: Write `kts-script-host.md`**

Front-matter + sections:

```markdown
---
title: .spec.kts script host
tags: [persistence, scripting, dsl]
summary: How the kotlin-scripting host loads .spec.kts files; why a custom host (vs JSR-223); what the threat model is.
---

# .spec.kts Script Host

`RedstoneSpec` is authored as `.spec.kts` files in the world directory and
loaded at runtime by `KtsSpecLoader` (in `data/serial/`). Files are evaluated
by a custom `BasicJvmScriptingHost` configured via `SpecScriptCompilationConfig`.

## Why a custom host (vs JSR-223)

- Pre-imports the DSL (`com.breadmoirai.redstonespecs.data.dsl.*`) so script
  authors don't need import lines.
- Better error reporting: diagnostics flow through `ResultWithDiagnostics`,
  not buried in `ScriptException`.
- Tighter control over the classpath surface (currently
  `dependenciesFromCurrentContext(wholeClasspath = true)`; can be narrowed
  later if sandboxing is needed).

## File contract

Every `.spec.kts` file MUST evaluate, as its last expression, to a `RedstoneSpec`.
The standard form is:

```kotlin
redstoneSpec("my_id") {
    bounds(5, 4, 5)
    lifespan = 20
    structure = "redstonespecs:my_id"
    input(...) { ... }
    output(...) { ... }
}
```

Errors:
- If the last expression is `Unit` (e.g., the script forgot `redstoneSpec(...)`),
  loading fails with "script must end with redstoneSpec(...) expression".
- If compilation fails, all diagnostics are joined into the exception message.

## Cost / size

The kotlin-scripting JVM host adds ~30–50 MB to the final jar (JIJ).
First-load latency for compiling a single `.spec.kts` is 1–3s on warmup,
then sub-100ms once cached.

## Threat model

`.spec.kts` files come from the user's own world directory — same trust
boundary as any other file the user saves. We do NOT sandbox arbitrary
JVM access. If a future change wants to load specs from untrusted sources
(e.g., shared maps), `dependenciesFromCurrentContext` should be replaced
with an explicit narrow classpath listing only the DSL package.
```

- [ ] **Step 2: Rewrite `spec-data-model-invariants.md`**

Replace contents with the post-refactor invariants:

```markdown
---
title: Spec data model invariants
tags: [data-model, design]
summary: What RedstoneSpec / SpecEntry guarantee at construction time and what callers can rely on.
---

# Spec data model invariants

## RedstoneSpec
- `bounds: Vec3i` — every axis ≥ 1.
- `lifespan: Int` — ticks; runner stops the run once `ticksElapsed >= lifespan`.
- `structure: String?` — optional structure resource id; supplies initial block state at run start.
- `entries: List<SpecEntry>` — flat list. May be empty.
- For every `entry` in `entries`: `entry.pos` lies inside `bounds`
  (`0 <= pos.{x,y,z} < bounds.{x,y,z}`).

## SpecEntry
- `pos`, `label`, `color`, `kind`, `time`, `condition` — all required.
- No required relationship between entries — duplicate `(pos, kind, time)`
  is allowed at construction time, but `RedstoneSpec.withEntryAddedOrUpdated`
  treats `(pos, kind, time)` as the entry's identity for replace-vs-append.

## What's gone

- `SpecMode` (mode field on RedstoneSpec).
- `BreakpointSpec` and `AutoSpec` sealed-class siblings.
- The "exactly one `SimTime.START` entry per InputSpec" invariant.
- `BoundingBox` — replaced by `Vec3i` size; positions are local.
- The nested `entries: List<Pair<SimTime, StateCondition>>` per InputSpec/OutputSpec
  — every (time, condition) is now its own SpecEntry row.

Initial state for the circuit-under-test now comes from the structure file
referenced by `RedstoneSpec.structure`, NOT from `SimTime.START` entries.
```

- [ ] **Step 3: Rewrite `spec-on-disk-format.md`**

Replace with a description of `.spec.kts` (one example, file naming
`<id>.spec.kts`, location `<world>/redstonespecs/`, that JSON is no longer
used on disk).

- [ ] **Step 4: Update `network-payload-contract.md`**

Add a paragraph noting that JSON-via-codec (`SpecJsonCodec.SPEC` /
`SpecJsonCodec.ENTRY`) is the network format only. No on-disk JSON.

- [ ] **Step 5: Update `docs/persistence/INDEX.md`**

Add the entry:
```
- [.spec.kts script host](kts-script-host.md) — how kotlin-scripting loads spec files; why a custom host; file contract; threat model
```
Tags: `persistence, scripting, dsl`.

- [ ] **Step 6: Update `docs/architecture/module-map.md`**

Add notes for the new packages:
- `data.dsl` — the `redstoneSpec { }` Kotlin DSL.
- `data.serial` — `.spec.kts` loader/emitter and the JSON network codec.

- [ ] **Step 7: Commit**

```bash
git add docs/
git commit -m "docs: data layer redesign — invariants, on-disk format, script host"
```

---

## Task 17: Final verification

- [ ] **Step 1: Full build**

Run: `cmd.exe /c "./gradlew.bat :26.1:clientClasses :26.1:classes :26.1:gametestClasses :26.1:clientTestClasses :26.1:testClasses"`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 2: Full unit tests**

Run: `cmd.exe /c "./gradlew.bat :26.1:test"`
Expected: BUILD SUCCESSFUL, all tests pass.

- [ ] **Step 3: Game tests**

Run: `cmd.exe /c "./gradlew.bat :26.1:runGameTest"`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Manual smoke test (UI)**

Per CLAUDE.md, UI changes need browser/in-game verification. Either:
- Launch the client manually and exercise: open editor → place input/output → save → reopen → verify spec round-trips through `<world>/redstonespecs/<id>.spec.kts`.
- OR explicitly note in the PR description that UI smoke testing was not performed (matches CLAUDE.md guidance: say so if you can't test the UI rather than claiming success).

- [ ] **Step 5: Verify no stale references**

Run:
```bash
grep -rn "SpecMode\|BreakpointSpec\|AutoSpec\|InputSpec\|OutputSpec" src/ docs/
```
Expected: empty (or only matches inside design/plan documents under `docs/superpowers/`).

If any matches remain in `src/` or other doc folders, fix them.

- [ ] **Step 6: Final commit if any cleanups happened**

```bash
git status
# if anything modified:
git add .
git commit -m "chore: clean up stale references to removed types"
```
