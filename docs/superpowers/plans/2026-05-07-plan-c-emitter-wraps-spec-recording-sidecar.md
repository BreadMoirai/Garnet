# Plan C — Emitter produces a `RedstoneTestSpec` subclass; recording sidecar

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Reshape `KtsSpecEmitter` so the `.spec.kts` it produces declares a `class : RedstoneTestSpec({ test("...") { runRedstoneSpec(redstoneSpec(...){ ... }) } })` rather than evaluating to a bare `RedstoneSpec`. Update `KtsSpecLoader` to return a Kotest `Spec` class. Persist the authorship `StateRecording` as `<id>.recording.nbt` for visualization-only reference.

**Architecture:** The declarative DSL inside `redstoneSpec(...) { ... }` is preserved verbatim — that's the human-readable, hand-editable surface. The new wrapper turns the file into a runnable Kotest spec without forcing users to learn `awaitTicks` / `signalAt`. Loader and emitter both deal in this new shape; `SpecPersistence` handles both files atomically.

**Tech Stack:** KotlinPoet (already in use), kotlin-scripting-jvm-host, Fabric NBT codecs, the helpers added in Plan B.

**Spec reference:** Spec §"Authored DSL — example", §"Persistence", §"R1 becomes a code-generator, then steps out".

**Depends on:** Plan A, Plan B.

---

## File structure (after this plan)

**Modified:**
- `src/main/kotlin/com/breadmoirai/redstonespecs/data/serial/KtsSpecEmitter.kt` — wraps `redstoneSpec(...)` block in a class declaration.
- `src/main/kotlin/com/breadmoirai/redstonespecs/data/serial/KtsSpecLoader.kt` — returns `KClass<out Spec>` instead of `RedstoneSpec`. A second function `loadRedstoneSpec` preserves the old signature for editor/UI consumers.
- `src/main/kotlin/com/breadmoirai/redstonespecs/data/serial/SpecScript.kt` — script `defaultImports` adds `com.breadmoirai.redstonespecs.testing.*`, `com.breadmoirai.redstonespecs.testing.runner.*`, `io.kotest.matchers.shouldBe`.
- `src/main/kotlin/com/breadmoirai/redstonespecs/persistence/SpecPersistence.kt` — saves `<id>.spec.kts` and `<id>.recording.nbt` together.
- `src/test/kotlin/com/breadmoirai/redstonespecs/data/serial/KtsSpecEmitterTest.kt` — updated assertions on the wrapped form.
- `src/test/kotlin/com/breadmoirai/redstonespecs/data/serial/KtsSpecLoaderTest.kt` — updated; the loader returns a `Spec` now.

**New:**
- `src/main/kotlin/com/breadmoirai/redstonespecs/persistence/RecordingSidecar.kt` — read/write `<id>.recording.nbt` via the existing `StateRecordingStorage`.
- `src/test/kotlin/com/breadmoirai/redstonespecs/persistence/RecordingSidecarTest.kt`.

---

## Task 1: Update emitter to wrap `redstoneSpec(...)` in a class declaration (TDD)

**Files:**
- Modify: `src/main/kotlin/com/breadmoirai/redstonespecs/data/serial/KtsSpecEmitter.kt`
- Modify: `src/test/kotlin/com/breadmoirai/redstonespecs/data/serial/KtsSpecEmitterTest.kt`

- [ ] **Step 1: Write the failing test**

In `KtsSpecEmitterTest.kt`, replace any existing emit test with:

```kotlin
test("emit wraps the spec DSL in a RedstoneTestSpec subclass with a single named test") {
    val spec = redstoneSpec("comparator-latch") {
        bounds(5, 3, 5)
        lifespan = 8
    }
    val source = KtsSpecEmitter.emit(spec)

    source shouldContain "class ComparatorLatchSpec : RedstoneTestSpec({"
    source shouldContain "test(\"comparator-latch\")"
    source shouldContain "runRedstoneSpec("
    source shouldContain "redstoneSpec(\"comparator-latch\")"
    source shouldContain "bounds(5, 3, 5)"
}
```

- [ ] **Step 2: Run to verify failure**

Run: `cmd.exe /c "./gradlew.bat :26.1:test --tests com.breadmoirai.redstonespecs.data.serial.KtsSpecEmitterTest"`
Expected: FAIL — current emitter produces only `redstoneSpec(...) { ... }` text, not a class wrapper.

- [ ] **Step 3: Update `KtsSpecEmitter.emit(...)`**

Edit `KtsSpecEmitter.kt`. Wrap the existing `redstoneSpec(...)` body in a class declaration:

```kotlin
fun emit(spec: RedstoneSpec): String {
    val className = classNameFor(spec.id)
    val out = CodeBlock.builder()
    out.beginControlFlow("class $className : RedstoneTestSpec(")
    out.beginControlFlow("{") // Kotest spec body lambda

    out.beginControlFlow("test(%S)", spec.id)
    out.beginControlFlow("runRedstoneSpec(")
    emitSpecLiteral(out, spec)
    // origin + level supplied by the test runtime via Plan D's coordinator wiring
    out.add(", originPos, level)\n")
    out.endControlFlow() // test
    out.endControlFlow() // spec body lambda

    out.add(")")
    out.endControlFlow() // class
    return out.build().toString()
}

private fun emitSpecLiteral(out: CodeBlock.Builder, spec: RedstoneSpec) {
    // (Existing redstoneSpec(...) emission moves here verbatim — refactor of current emit body.)
    out.beginControlFlow("redstoneSpec(%S)", spec.id)
    out.addStatement("bounds(%L, %L, %L)", spec.bounds.x, spec.bounds.y, spec.bounds.z)
    out.addStatement("lifespan = %L", spec.lifespan)
    spec.structure?.let { out.addStatement("structure = %S", it) }
    // ... grouping + emission identical to current implementation ...
    out.endControlFlow()
}

private fun classNameFor(id: String): String =
    id.split('-', '_', ' ', '.', '/')
        .filter { it.isNotEmpty() }
        .joinToString("") { it.replaceFirstChar(Char::uppercase) } + "Spec"
```

> The `originPos` / `level` identifiers above are unbound until Plan D introduces a `RedstoneTestSpec` extension that defines them. **Acknowledge this:** scripts emitted by this task will not run successfully until Plan D lands. Compilation by `KtsSpecLoader` will succeed (defaultImports + script type), but invocation by an engine will fail with "unresolved reference" in `runRedstoneSpec(spec, originPos, level)` — exactly the surface Plan D wires up.

- [ ] **Step 4: Run the emitter tests**

Run: `cmd.exe /c "./gradlew.bat :26.1:test --tests com.breadmoirai.redstonespecs.data.serial.KtsSpecEmitterTest"`
Expected: PASS.

- [ ] **Step 5: Update the emit-roundtrip test from Plan A**

Plan A's `KtsSpecLoaderRoundtripTest` cast the loaded value to `RedstoneSpec`. After this plan, the loader returns a `Spec`. Update the test (or move it to assert via `loadRedstoneSpec`, added in Task 3 below).

For now, **temporarily delete** the failing test or mark it ignored:

```kotlin
xtest("emit then load yields equivalent RedstoneSpec") { /* updated in Task 3 */ }
```

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/breadmoirai/redstonespecs/data/serial/KtsSpecEmitter.kt \
        src/test/kotlin/com/breadmoirai/redstonespecs/data/serial/KtsSpecEmitterTest.kt \
        src/test/kotlin/com/breadmoirai/redstonespecs/data/serial/KtsSpecLoaderRoundtripTest.kt
git commit -m "feat(serial): emitter wraps redstoneSpec DSL in a RedstoneTestSpec subclass"
```

---

## Task 2: Update `SpecScript` defaultImports

**Files:**
- Modify: `src/main/kotlin/com/breadmoirai/redstonespecs/data/serial/SpecScript.kt`

- [ ] **Step 1: Add testing imports**

```kotlin
object SpecScriptCompilationConfig : ScriptCompilationConfiguration({
    defaultImports(
        "com.breadmoirai.redstonespecs.data.dsl.*",
        "com.breadmoirai.redstonespecs.data.Phase",
        "com.breadmoirai.redstonespecs.data.SimTime",
        // New: testing surface so .spec.kts can name RedstoneTestSpec / runRedstoneSpec.
        "com.breadmoirai.redstonespecs.testing.RedstoneTestSpec",
        "com.breadmoirai.redstonespecs.testing.runner.runRedstoneSpec",
        "com.breadmoirai.redstonespecs.testing.core.awaitTicks",
        "com.breadmoirai.redstonespecs.testing.core.awaitTickEnd",
        "com.breadmoirai.redstonespecs.testing.server.spawnStructure",
        "io.kotest.matchers.shouldBe",
    )
    jvm {
        dependenciesFromClassContext(RedstoneSpec::class, wholeClasspath = true)
    }
})
```

- [ ] **Step 2: Commit**

```bash
git add src/main/kotlin/com/breadmoirai/redstonespecs/data/serial/SpecScript.kt
git commit -m "feat(serial): expose testing helpers as default imports in .spec.kts"
```

---

## Task 3: Loader returns a Spec class; keep a RedstoneSpec extractor for editor

**Files:**
- Modify: `src/main/kotlin/com/breadmoirai/redstonespecs/data/serial/KtsSpecLoader.kt`
- Modify: `src/test/kotlin/com/breadmoirai/redstonespecs/data/serial/KtsSpecLoaderTest.kt`
- Modify: `src/test/kotlin/com/breadmoirai/redstonespecs/data/serial/KtsSpecLoaderRoundtripTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.breadmoirai.redstonespecs.data.serial

import com.breadmoirai.redstonespecs.data.RedstoneSpec
import com.breadmoirai.redstonespecs.data.dsl.redstoneSpec
import com.breadmoirai.redstonespecs.testing.RedstoneTestSpec
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

class KtsSpecLoaderTest : FunSpec({
    test("loadSpec returns a Spec class extending RedstoneTestSpec") {
        val source = KtsSpecEmitter.emit(redstoneSpec("loader-1") { bounds(2, 2, 2); lifespan = 4 })
        val klass = KtsSpecLoader.loadSpec(source, name = "loader-1.spec.kts")
        // Instantiate to confirm the class is well-formed:
        val instance = klass.java.getDeclaredConstructor().newInstance()
        instance.shouldBeInstanceOf<RedstoneTestSpec>()
    }

    test("loadRedstoneSpec extracts the inner RedstoneSpec value for editor consumers") {
        val original = redstoneSpec("loader-2") { bounds(3, 3, 3); lifespan = 6 }
        val source = KtsSpecEmitter.emit(original)
        val extracted = KtsSpecLoader.loadRedstoneSpec(source, name = "loader-2.spec.kts")
        extracted.id shouldBe "loader-2"
        extracted.lifespan shouldBe 6
    }
})
```

- [ ] **Step 2: Run to verify failure**

Run: `cmd.exe /c "./gradlew.bat :26.1:test --tests com.breadmoirai.redstonespecs.data.serial.KtsSpecLoaderTest"`
Expected: FAIL — `KtsSpecLoader.loadSpec` and `KtsSpecLoader.loadRedstoneSpec` don't exist.

- [ ] **Step 3: Implement both loader entry points**

Replace `KtsSpecLoader.kt` with:

```kotlin
package com.breadmoirai.redstonespecs.data.serial

import com.breadmoirai.redstonespecs.data.RedstoneSpec
import com.breadmoirai.redstonespecs.data.dsl.redstoneSpec
import io.kotest.core.spec.Spec
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.reflect.KClass
import kotlin.script.experimental.api.EvaluationResult
import kotlin.script.experimental.api.ResultValue
import kotlin.script.experimental.api.ResultWithDiagnostics
import kotlin.script.experimental.api.ScriptEvaluationConfiguration
import kotlin.script.experimental.host.toScriptSource
import kotlin.script.experimental.jvm.baseClassLoader
import kotlin.script.experimental.jvm.jvm
import kotlin.script.experimental.jvmhost.BasicJvmScriptingHost

object KtsSpecLoader {
    private val host = BasicJvmScriptingHost()
    private val evalConfig = ScriptEvaluationConfiguration {
        jvm { baseClassLoader(RedstoneSpec::class.java.classLoader) }
    }

    /** Loads `.spec.kts` and returns the declared Kotest Spec class. */
    fun loadSpec(source: String, name: String = "spec.kts"): KClass<out Spec> {
        val eval = evalOrThrow(source, name)
        // The script declares `class XSpec : RedstoneTestSpec(...)`. The class is in scope
        // of the script object's classloader; locate it by reflection on the script object.
        @Suppress("UNCHECKED_CAST")
        return findFirstSpecClass(eval, name) as KClass<out Spec>
    }

    /** Loads `.spec.kts` and returns its inner RedstoneSpec literal. Used by the in-game editor. */
    fun loadRedstoneSpec(source: String, name: String = "spec.kts"): RedstoneSpec {
        val klass = loadSpec(source, name)
        // Instantiate, drive its first test body partially to capture the redstoneSpec literal,
        // OR — simpler — re-evaluate a stripped projection of the script that returns the literal.
        // For now: instantiate and inspect the captured literal via a known interface.
        return extractSpecFromInstance(klass, name)
    }

    fun loadFile(path: Path): KClass<out Spec> = loadSpec(path.readText(), path.fileName.toString())
    fun loadFileAsRedstoneSpec(path: Path): RedstoneSpec =
        loadRedstoneSpec(path.readText(), path.fileName.toString())

    private fun evalOrThrow(source: String, name: String): EvaluationResult {
        val result = host.eval(source.toScriptSource(name), SpecScriptCompilationConfig, evalConfig)
        return when (result) {
            is ResultWithDiagnostics.Success -> result.value
            is ResultWithDiagnostics.Failure -> {
                val msg = result.reports.joinToString("\n") { "  ${it.severity}: ${it.message}" }
                error("Failed to load $name:\n$msg")
            }
        }
    }

    private fun findFirstSpecClass(eval: EvaluationResult, name: String): KClass<*> {
        // After eval, the top-level class declaration is a member of the script's compiled classloader.
        // Iterate the script object's class's nested classes for a Spec subclass.
        val scriptObj = (eval.returnValue as? ResultValue.Value)?.scriptInstance
            ?: (eval.returnValue as? ResultValue.Unit)?.scriptInstance
            ?: error("$name: script produced no instance")
        val nested = scriptObj.javaClass.declaredClasses
        val specClass = nested.firstOrNull { Spec::class.java.isAssignableFrom(it) }
            ?: error("$name: no Spec class declared in script (expected `class XSpec : RedstoneTestSpec(...)`)")
        return specClass.kotlin
    }

    private fun extractSpecFromInstance(klass: KClass<out Spec>, name: String): RedstoneSpec {
        // The emitted class shape always wraps a single test body that calls runRedstoneSpec(literalSpec, ...).
        // For the editor's read-only need, we capture the literal by interposing a thread-local marker
        // before instantiation. Implementation detail:
        return SpecLiteralCapture.captureFrom(klass)
            ?: error("$name: could not extract RedstoneSpec literal from script")
    }
}
```

Add a tiny capture helper:

```kotlin
package com.breadmoirai.redstonespecs.data.serial

import com.breadmoirai.redstonespecs.data.RedstoneSpec
import io.kotest.core.spec.Spec
import kotlin.reflect.KClass

internal object SpecLiteralCapture {
    private val capture = ThreadLocal<RedstoneSpec?>()

    fun captureFrom(klass: KClass<out Spec>): RedstoneSpec? {
        capture.set(null)
        return try {
            // Instantiating the spec class triggers emitter-generated init code; we'll inject a hook
            // in Task 4 below that calls record() with the literal before runRedstoneSpec executes.
            klass.java.getDeclaredConstructor().newInstance()
            capture.get()
        } finally {
            capture.remove()
        }
    }

    fun record(spec: RedstoneSpec) {
        if (capture.get() == null) capture.set(spec)
    }
}
```

- [ ] **Step 4: Update emitter to call `SpecLiteralCapture.record(...)` before `runRedstoneSpec(...)`**

In `KtsSpecEmitter.emit`, change the test body to:

```kotlin
out.beginControlFlow("test(%S)", spec.id)
out.addStatement("%T.record(", SpecLiteralCapture::class)
emitSpecLiteral(out, spec)
out.add(")\n")
out.addStatement("runRedstoneSpec(")
emitSpecLiteral(out, spec)  // emit twice; the duplication is unavoidable without a let{} binding
out.add(", originPos, level)\n")
out.endControlFlow()
```

> **Why emit the literal twice:** the editor's `loadRedstoneSpec` instantiates the class without ever invoking the test body; only init-time and class-body code runs. We need the literal captured at instantiation, not at test execution. Putting it in a class-body init block (outside the lambda) would also work — refactor to that if cleaner. The emitter author chooses; the test in Step 1 only requires the literal be reachable.

Cleaner alternative — move capture to class-init:

```kotlin
out.beginControlFlow("class $className : RedstoneTestSpec(")
out.beginControlFlow("{")
out.addStatement("%T.record(", SpecLiteralCapture::class)
emitSpecLiteral(out, spec)
out.add(")\n")
out.beginControlFlow("test(%S)", spec.id)
out.addStatement("runRedstoneSpec(")
emitSpecLiteral(out, spec)
out.add(", originPos, level)\n")
out.endControlFlow()
out.endControlFlow()
out.add(")")
out.endControlFlow()
```

Pick the cleaner option (class-init); it runs once on instantiation and avoids duplicate evaluation.

- [ ] **Step 5: Run loader tests**

Run: `cmd.exe /c "./gradlew.bat :26.1:test --tests com.breadmoirai.redstonespecs.data.serial.KtsSpecLoaderTest"`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/breadmoirai/redstonespecs/data/serial/KtsSpecLoader.kt \
        src/main/kotlin/com/breadmoirai/redstonespecs/data/serial/SpecLiteralCapture.kt \
        src/main/kotlin/com/breadmoirai/redstonespecs/data/serial/KtsSpecEmitter.kt \
        src/test/kotlin/com/breadmoirai/redstonespecs/data/serial/KtsSpecLoaderTest.kt
git commit -m "feat(serial): loader returns Spec class; loadRedstoneSpec extracts literal for editor"
```

---

## Task 4: Update `KtsSpecLoaderRoundtripTest` from Plan A

**Files:**
- Modify: `src/test/kotlin/com/breadmoirai/redstonespecs/data/serial/KtsSpecLoaderRoundtripTest.kt`

- [ ] **Step 1: Replace the test**

```kotlin
package com.breadmoirai.redstonespecs.data.serial

import com.breadmoirai.redstonespecs.data.dsl.redstoneSpec
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class KtsSpecLoaderRoundtripTest : FunSpec({
    test("emit then loadRedstoneSpec yields equivalent RedstoneSpec") {
        val original = redstoneSpec("roundtrip-1") { bounds(4, 3, 2); lifespan = 8 }
        val source = KtsSpecEmitter.emit(original)
        val loaded = KtsSpecLoader.loadRedstoneSpec(source, name = "roundtrip-1.spec.kts")
        loaded.id shouldBe "roundtrip-1"
        loaded.lifespan shouldBe 8
        loaded.bounds shouldBe original.bounds
    }
})
```

- [ ] **Step 2: Run**

Run: `cmd.exe /c "./gradlew.bat :26.1:test --tests com.breadmoirai.redstonespecs.data.serial.KtsSpecLoaderRoundtripTest"`
Expected: PASS.

- [ ] **Step 3: Commit**

```bash
git add src/test/kotlin/com/breadmoirai/redstonespecs/data/serial/KtsSpecLoaderRoundtripTest.kt
git commit -m "test(serial): roundtrip via loadRedstoneSpec extractor"
```

---

## Task 5: Recording sidecar — `<id>.recording.nbt`

**Files:**
- Create: `src/main/kotlin/com/breadmoirai/redstonespecs/persistence/RecordingSidecar.kt`
- Create: `src/test/kotlin/com/breadmoirai/redstonespecs/persistence/RecordingSidecarTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.breadmoirai.redstonespecs.persistence

import com.breadmoirai.redstonespecs.runner.StateRecording
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.nio.file.Files

class RecordingSidecarTest : FunSpec({
    test("save then load yields an equivalent recording") {
        val tmp = Files.createTempDirectory("recording-sidecar")
        val recording = StateRecording.empty()  // construct via real fixture if needed

        RecordingSidecar.save(tmp, "spec-1", recording)
        val loaded = RecordingSidecar.load(tmp, "spec-1")

        loaded shouldNotBe null
        loaded!!.changes.size shouldBe recording.changes.size
    }

    test("load returns null when sidecar absent") {
        val tmp = Files.createTempDirectory("recording-sidecar-empty")
        RecordingSidecar.load(tmp, "missing") shouldBe null
    }
})
```

- [ ] **Step 2: Run to verify failure**

Run: `cmd.exe /c "./gradlew.bat :26.1:test --tests com.breadmoirai.redstonespecs.persistence.RecordingSidecarTest"`
Expected: FAIL.

- [ ] **Step 3: Implement using the existing `StateRecordingStorage`**

```kotlin
package com.breadmoirai.redstonespecs.persistence

import com.breadmoirai.redstonespecs.runner.StateRecording
import net.minecraft.nbt.NbtIo
import org.slf4j.LoggerFactory
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists

private val LOGGER = LoggerFactory.getLogger("Redstone Specs")
private const val EXT = ".recording.nbt"

object RecordingSidecar {
    fun save(saveDir: Path, specId: String, recording: StateRecording) {
        saveDir.createDirectories()
        val file = saveDir.resolve("$specId$EXT")
        // StateRecordingStorage already knows how to convert StateRecording <-> CompoundTag.
        // Reuse its codec; do NOT introduce a parallel codec here.
        val tag = com.breadmoirai.redstonespecs.runner.StateRecordingStorage.toTag(recording)
        NbtIo.writeCompressed(tag, file)
        LOGGER.debug("[RecordingSidecar#save] saved recording '{}' to {}", specId, file)
    }

    fun load(saveDir: Path, specId: String): StateRecording? {
        val file = saveDir.resolve("$specId$EXT")
        if (!file.exists()) return null
        val tag = NbtIo.readCompressed(file, net.minecraft.nbt.NbtAccounter.unlimitedHeap())
        return com.breadmoirai.redstonespecs.runner.StateRecordingStorage.fromTag(tag)
    }
}
```

> If `StateRecordingStorage` does not currently expose `toTag(...)` / `fromTag(...)` as a public API, refactor: add those public functions wrapping its existing internal serialization. Don't duplicate the codec.

- [ ] **Step 4: Run**

Run: `cmd.exe /c "./gradlew.bat :26.1:test --tests com.breadmoirai.redstonespecs.persistence.RecordingSidecarTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/breadmoirai/redstonespecs/persistence/RecordingSidecar.kt \
        src/main/kotlin/com/breadmoirai/redstonespecs/runner/StateRecordingStorage.kt \
        src/test/kotlin/com/breadmoirai/redstonespecs/persistence/RecordingSidecarTest.kt
git commit -m "feat(persistence): RecordingSidecar persists authorship StateRecording as <id>.recording.nbt"
```

---

## Task 6: `SpecPersistence.save` writes both files atomically

**Files:**
- Modify: `src/main/kotlin/com/breadmoirai/redstonespecs/persistence/SpecPersistence.kt`
- Modify: `src/test/kotlin/com/breadmoirai/redstonespecs/persistence/SpecPersistenceTest.kt`

- [ ] **Step 1: Add overload with optional recording**

```kotlin
fun save(saveDir: Path, spec: RedstoneSpec, recording: StateRecording? = null) {
    saveDir.createDirectories()
    val file = saveDir.resolve("${spec.id}$EXT")
    file.writeText(KtsSpecEmitter.emit(spec))
    if (recording != null) {
        RecordingSidecar.save(saveDir, spec.id, recording)
    }
    LOGGER.debug("[SpecPersistence#save] saved spec '{}' to {} (recording={})",
        spec.id, file, recording != null)
}

fun loadRecording(saveDir: Path, id: String): StateRecording? =
    RecordingSidecar.load(saveDir, id)
```

The existing `load(...)` should keep returning `RedstoneSpec` (use `KtsSpecLoader.loadFileAsRedstoneSpec`).

- [ ] **Step 2: Update `SpecPersistenceTest.kt`**

Add a test for the recording-roundtrip path; ensure existing tests still pass with the new optional parameter (no signature break).

- [ ] **Step 3: Run**

Run: `cmd.exe /c "./gradlew.bat :26.1:test --tests com.breadmoirai.redstonespecs.persistence.SpecPersistenceTest"`
Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/com/breadmoirai/redstonespecs/persistence/SpecPersistence.kt \
        src/test/kotlin/com/breadmoirai/redstonespecs/persistence/SpecPersistenceTest.kt
git commit -m "feat(persistence): SpecPersistence.save accepts optional StateRecording sidecar"
```

---

## Task 7: Build all source sets

- [ ] **Step 1: Full build**

Run: `cmd.exe /c "./gradlew.bat :26.1:clientClasses classes gametestClasses clientTestClasses testClasses"`
Expected: BUILD SUCCESSFUL.

> Note: emitted `.spec.kts` will not yet *run* (the `originPos` and `level` symbols are unbound at this point). Plan D resolves that by extending `RedstoneTestSpec` with bound members.

- [ ] **Step 2: Run unit tests**

Run: `cmd.exe /c "./gradlew.bat :26.1:test"`
Expected: PASS.

- [ ] **Step 3: Commit (if anything still pending)**

```bash
git status
git add -A && git commit -m "build: confirm all sourcesets compile after emitter/loader reshape" || true
```

---

## Verification checklist

- [ ] `KtsSpecEmitter.emit(spec)` returns text containing `class <ClassName>Spec : RedstoneTestSpec({` and `runRedstoneSpec(...)`.
- [ ] `KtsSpecLoader.loadSpec(source)` returns `KClass<out Spec>`.
- [ ] `KtsSpecLoader.loadRedstoneSpec(source)` returns the inner `RedstoneSpec` literal.
- [ ] `RecordingSidecar.save` / `load` round-trip a `StateRecording` via `<id>.recording.nbt`.
- [ ] `SpecPersistence.save(dir, spec, recording)` writes both files; `loadRecording` reads the sidecar.
- [ ] All tests in `:26.1:test` pass.

---

## Notes on what is intentionally NOT in this plan

- The emitted scripts use `originPos` and `level` identifiers that don't exist on `RedstoneTestSpec` yet. Plan D adds them as receiver-bound members, completing the runnable shape.
- `SpecRunnerCoordinator.startRun(...)` still uses the data-only path. Plan D replaces it.
- Editor-side UI changes to surface the recording sidecar are out of scope (Plan F).
