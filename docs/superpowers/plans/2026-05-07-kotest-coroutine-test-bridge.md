# Kotest + Coroutine Test Bridge Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Unify `src/test/`, `src/gametest/`, `src/clientTest/` on Kotest by introducing a `testBridge` source set that exposes coroutine-friendly primitives over Minecraft's tick system, with sentinel-only `@GameTest` / `FabricClientGameTest` entry points launching the Kotest engine on a worker thread.

**Architecture:** Two Kotlin primitives in `testBridge`: a `SharedFlow<MinecraftServer>` of tick events (producer, registered once on `SERVER_STARTED`) and a `ServerThreadDispatcher` over `MinecraftServer.execute` (consumer). Test code uses `awaitTicks(n)` (flow operators) and `withContext(McDispatchers.Server) { ... }` (thread hop). Each non-unit source set has one sentinel that runs `TestEngineLauncher.launch()` on a worker thread; Kensa auto-registers via ServiceLoader for HTML reports. Spec doc: `docs/superpowers/specs/2026-05-07-kotest-coroutine-test-bridge-design.md`.

**Tech Stack:** Kotlin 2.3.20, fabric-language-kotlin 1.13.10, kotlinx-coroutines (transitive via FLK), Kotest 5.9.x (runner-junit5 + assertions-core), Kensa (latest, ServiceLoader-registered), Fabric API 0.146.1, Minecraft 26.1.2, Stonecutter 0.9, fabric-loom 1.15-SNAPSHOT.

**Build invocation:** All Gradle commands use `cmd.exe /c "./gradlew.bat <task>"` from the repo root (project lives on a Windows drive mounted to WSL2). Stonecutter task paths are `:26.1:<task>` — never `:versions:26.1:...`.

**Verification command (run after every code change):**
```
cmd.exe /c "./gradlew.bat :26.1:clientClasses :26.1:classes :26.1:gametestClasses :26.1:clientTestClasses :26.1:testClasses"
```
This builds all five source sets — `compileKotlin` alone misses the others.

---

## Phase A — Source set scaffolding

### Task 1: Add `testBridge` source set + dependencies

**Files:**
- Modify: `build.gradle.kts` (root, lines ~37-119)
- Create: `src/testBridge/kotlin/com/breadmoirai/redstonespecs/testing/.gitkeep`

- [ ] **Step 1: Create the package directory**

```bash
mkdir -p src/testBridge/kotlin/com/breadmoirai/redstonespecs/testing/{core,server,launcher}
touch src/testBridge/kotlin/com/breadmoirai/redstonespecs/testing/.gitkeep
```

- [ ] **Step 2: Register the source set in root `build.gradle.kts`**

Find the existing `sourceSets { create("clientTest") { ... } }` block (around line 37). Add a new `testBridge` block immediately before it. The `testBridge` source set inherits main+client output (it needs MC API access) and is consumed by `gametest` + `clientTest`.

Replace the existing `sourceSets { ... }` block with:

```kotlin
sourceSets {
    create("testBridge") {
        compileClasspath += sourceSets["main"].output +
            sourceSets["client"].output +
            sourceSets["client"].compileClasspath
        runtimeClasspath += sourceSets["main"].output +
            sourceSets["client"].output +
            sourceSets["client"].runtimeClasspath
    }
    create("clientTest") {
        compileClasspath += sourceSets["main"].output +
            sourceSets["client"].output +
            sourceSets["client"].compileClasspath +
            sourceSets["testBridge"].output
        runtimeClasspath += sourceSets["main"].output +
            sourceSets["client"].output +
            sourceSets["client"].runtimeClasspath +
            sourceSets["testBridge"].output
    }
}
```

Then find the `fabricApi { configureTests { ... } }` block (around line 65). Loom auto-creates a `gametest` source set when `enableGameTests = true`. We need it to see `testBridge` output. Add immediately after `configureTests`:

```kotlin
afterEvaluate {
    sourceSets.findByName("gametest")?.let { gt ->
        gt.compileClasspath += sourceSets["testBridge"].output
        gt.runtimeClasspath += sourceSets["testBridge"].output
    }
}
```

- [ ] **Step 3: Wire `testBridge` configurations to inherit from `client`**

After the existing `configurations { named("clientTestImplementation") { ... } ... }` block (around line 53), add:

```kotlin
configurations {
    named("testBridgeImplementation") {
        extendsFrom(configurations["clientImplementation"])
    }
    named("testBridgeCompileOnly") {
        extendsFrom(configurations["clientCompileOnly"])
    }
    named("testBridgeRuntimeOnly") {
        extendsFrom(configurations["clientRuntimeOnly"])
    }
}
```

- [ ] **Step 4: Add Kotest + Kensa + coroutines dependencies**

Find the existing `dependencies { ... }` block. Add at the end (before the closing brace, after `testImplementation("net.fabricmc:fabric-loader-junit:...`) ):

```kotlin
    // Kotlin coroutines (also pulled transitively via fabric-language-kotlin, but declare explicitly)
    "testBridgeImplementation"("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.1")

    // Kotest engine + assertions (used by testBridge, gametest, clientTest, and test source sets)
    "testBridgeImplementation"("io.kotest:kotest-runner-junit5:5.9.1")
    "testBridgeImplementation"("io.kotest:kotest-assertions-core:5.9.1")

    // Kensa — auto-registers via ServiceLoader, produces HTML reports
    "testBridgeImplementation"("dev.kensa:kensa:1.4.0")
```

- [ ] **Step 5: Verify scaffolding compiles (will be empty but must not break the build)**

Run:
```
cmd.exe /c "./gradlew.bat :26.1:clientClasses :26.1:classes :26.1:gametestClasses :26.1:clientTestClasses :26.1:testClasses"
```

Expected: BUILD SUCCESSFUL, no errors. The `testBridge` source set has no Kotlin files yet (just the `.gitkeep`), so its compile is a no-op.

If Kotest/Kensa version coordinates are unavailable, `--refresh-dependencies` once. If a version doesn't exist, search Maven Central for the latest available 5.x Kotest and 1.x Kensa; record what was used.

- [ ] **Step 6: Commit**

```bash
git add build.gradle.kts src/testBridge
git commit -m "build: add testBridge source set with Kotest + Kensa + coroutines deps"
```

---

## Phase B — Core bridge primitives

### Task 2: Tick event flows (`Ticks.kt`)

**Files:**
- Create: `src/testBridge/kotlin/com/breadmoirai/redstonespecs/testing/core/Ticks.kt`

- [ ] **Step 1: Write the file**

```kotlin
package com.breadmoirai.redstonespecs.testing.core

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import net.minecraft.server.MinecraftServer

private val _serverTickStart = MutableSharedFlow<MinecraftServer>(
    replay = 0,
    extraBufferCapacity = 1,
    onBufferOverflow = BufferOverflow.DROP_OLDEST,
)
private val _serverTickEnd = MutableSharedFlow<MinecraftServer>(
    replay = 0,
    extraBufferCapacity = 1,
    onBufferOverflow = BufferOverflow.DROP_OLDEST,
)

/** Emits each `ServerTickEvents.START_SERVER_TICK`. */
val serverTickStart: SharedFlow<MinecraftServer> = _serverTickStart.asSharedFlow()

/** Emits each `ServerTickEvents.END_SERVER_TICK`. */
val serverTickEnd: SharedFlow<MinecraftServer> = _serverTickEnd.asSharedFlow()

internal fun emitServerTickStart(server: MinecraftServer) {
    _serverTickStart.tryEmit(server)
}

internal fun emitServerTickEnd(server: MinecraftServer) {
    _serverTickEnd.tryEmit(server)
}
```

The buffer config — `replay = 0`, single-slot extra buffer, `DROP_OLDEST` — is intentional: tests subscribe before the action that triggers the tick they're waiting for, so dropping when no consumer is suspended is correct. See spec section "Why `SharedFlow` with `replay = 0`...".

- [ ] **Step 2: Verify it compiles**

```
cmd.exe /c "./gradlew.bat :26.1:testBridgeClasses"
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add src/testBridge/kotlin/com/breadmoirai/redstonespecs/testing/core/Ticks.kt
git commit -m "feat(testBridge): add server tick SharedFlow producers"
```

### Task 3: Server-thread dispatcher (`Dispatchers.kt`)

**Files:**
- Create: `src/testBridge/kotlin/com/breadmoirai/redstonespecs/testing/core/Dispatchers.kt`

- [ ] **Step 1: Write the file**

```kotlin
package com.breadmoirai.redstonespecs.testing.core

import kotlinx.coroutines.CoroutineDispatcher
import net.minecraft.server.MinecraftServer
import org.slf4j.LoggerFactory
import kotlin.coroutines.CoroutineContext

private val logger = LoggerFactory.getLogger("Redstone Specs")

/** Threshold: log a warning if a server-thread Runnable runs longer than this. */
private const val WATCHDOG_THRESHOLD_MS = 100L

/**
 * Dispatcher that posts continuations to `MinecraftServer.execute`, ensuring blocks
 * run on the server thread. Short-circuits when already on the server thread so
 * nested `withContext(Server)` calls don't bounce through the executor twice.
 *
 * Wraps each dispatched Runnable in a watchdog: stalls > 100ms are logged but not
 * enforced. Test bodies are expected to keep `onServer { }` blocks short.
 */
class ServerThreadDispatcher(private val server: MinecraftServer) : CoroutineDispatcher() {

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        if (server.isSameThread) {
            runWithWatchdog(block)
        } else {
            server.execute { runWithWatchdog(block) }
        }
    }

    override fun isDispatchNeeded(context: CoroutineContext): Boolean = !server.isSameThread

    private fun runWithWatchdog(block: Runnable) {
        val start = System.nanoTime()
        try {
            block.run()
        } finally {
            val elapsedMs = (System.nanoTime() - start) / 1_000_000
            if (elapsedMs > WATCHDOG_THRESHOLD_MS) {
                logger.warn(
                    "Server-thread block ran {}ms (>{}ms threshold). Long compute on the server thread stalls the tick loop.",
                    elapsedMs, WATCHDOG_THRESHOLD_MS,
                )
            }
        }
    }
}

/** Holder for lifecycle-scoped Minecraft dispatchers. Set on SERVER_STARTED, cleared on SERVER_STOPPED. */
object McDispatchers {
    @Volatile private var _server: ServerThreadDispatcher? = null
    @Volatile private var _currentServer: MinecraftServer? = null

    val Server: CoroutineDispatcher
        get() = _server ?: error("McDispatchers.Server accessed before SERVER_STARTED or after SERVER_STOPPED")

    val currentServer: MinecraftServer
        get() = _currentServer ?: error("McDispatchers.currentServer accessed before SERVER_STARTED or after SERVER_STOPPED")

    internal fun install(server: MinecraftServer) {
        _server = ServerThreadDispatcher(server)
        _currentServer = server
    }

    internal fun uninstall() {
        _server = null
        _currentServer = null
    }
}
```

- [ ] **Step 2: Verify it compiles**

```
cmd.exe /c "./gradlew.bat :26.1:testBridgeClasses"
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add src/testBridge/kotlin/com/breadmoirai/redstonespecs/testing/core/Dispatchers.kt
git commit -m "feat(testBridge): add ServerThreadDispatcher with 100ms watchdog"
```

### Task 4: Lifecycle wiring (`Lifecycle.kt`)

**Files:**
- Create: `src/testBridge/kotlin/com/breadmoirai/redstonespecs/testing/core/Lifecycle.kt`

- [ ] **Step 1: Write the file**

```kotlin
package com.breadmoirai.redstonespecs.testing.core

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents

/**
 * Idempotent registration of the bridge's Fabric event subscriptions.
 *
 * Must be invoked from a mod-init context (the bridge does not register itself
 * automatically — it has no fabric.mod.json entrypoint). Each test source set's
 * sentinel arranges for this to be called exactly once per server lifecycle.
 */
object TestBridgeLifecycle {
    @Volatile private var registered = false

    fun register() {
        if (registered) return
        registered = true

        ServerLifecycleEvents.SERVER_STARTED.register { server ->
            McDispatchers.install(server)
        }
        ServerLifecycleEvents.SERVER_STOPPED.register { _ ->
            McDispatchers.uninstall()
        }
        ServerTickEvents.START_SERVER_TICK.register { server ->
            emitServerTickStart(server)
        }
        ServerTickEvents.END_SERVER_TICK.register { server ->
            emitServerTickEnd(server)
        }
    }
}
```

`ServerTickEvents` and `ServerLifecycleEvents` are in `net.fabricmc.fabric.api.event.lifecycle.v1`. If those imports fail to resolve in 26.1, search the Fabric API jar for the actual package — `cmd.exe /c "./gradlew.bat :26.1:dependencies --configuration runtimeClasspath"` lists the jar paths and `jar tf <path>` lists classes.

- [ ] **Step 2: Verify it compiles**

```
cmd.exe /c "./gradlew.bat :26.1:testBridgeClasses"
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add src/testBridge/kotlin/com/breadmoirai/redstonespecs/testing/core/Lifecycle.kt
git commit -m "feat(testBridge): add TestBridgeLifecycle with idempotent event registration"
```

### Task 5: Suspending primitives (`Suspending.kt`)

**Files:**
- Create: `src/testBridge/kotlin/com/breadmoirai/redstonespecs/testing/server/Suspending.kt`

- [ ] **Step 1: Write the file**

```kotlin
package com.breadmoirai.redstonespecs.testing.server

import com.breadmoirai.redstonespecs.testing.core.McDispatchers
import com.breadmoirai.redstonespecs.testing.core.serverTickEnd
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.withContext
import net.minecraft.server.MinecraftServer

/** Suspends until the next END_SERVER_TICK and returns the server. */
suspend fun awaitTickEnd(): MinecraftServer = serverTickEnd.first()

/** Suspends until [n] END_SERVER_TICK events have fired, returning the server at the last one. */
suspend fun awaitTicks(n: Int): MinecraftServer {
    require(n >= 1) { "awaitTicks requires n >= 1, got $n" }
    return serverTickEnd.take(n).last()
}

/** Suspends until an END_SERVER_TICK satisfies [predicate]. */
suspend fun awaitTickWhere(predicate: (MinecraftServer) -> Boolean): MinecraftServer =
    serverTickEnd.first(predicate)

/** Hops to the server thread, runs [block], returns the result. */
suspend fun <T> onServer(block: suspend MinecraftServer.() -> T): T =
    withContext(McDispatchers.Server) { block(McDispatchers.currentServer) }
```

- [ ] **Step 2: Verify it compiles**

```
cmd.exe /c "./gradlew.bat :26.1:testBridgeClasses"
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add src/testBridge/kotlin/com/breadmoirai/redstonespecs/testing/server/Suspending.kt
git commit -m "feat(testBridge): add awaitTicks/awaitTickWhere/onServer primitives"
```

### Task 6: Unit tests for the suspending primitives

**Files:**
- Create: `src/testBridge/test/kotlin/com/breadmoirai/redstonespecs/testing/server/SuspendingTest.kt`
- Modify: `build.gradle.kts` (add a `testBridgeTest` source set or run these via root `test` task)

Decision: rather than create a separate `testBridgeTest` source set (more Gradle plumbing), put bridge unit tests in `src/test/kotlin/` under a `testing/` package. They're plain Kotlin/coroutines tests; no MC dependency.

- [ ] **Step 1: Create the test file**

Path: `src/test/kotlin/com/breadmoirai/redstonespecs/testing/server/SuspendingTest.kt`

```kotlin
package com.breadmoirai.redstonespecs.testing.server

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import net.minecraft.server.MinecraftServer
import org.mockito.Mockito.mock

class SuspendingTest : FunSpec({

    test("take(n).last() resolves after n emissions") {
        runTest {
            val flow = MutableSharedFlow<MinecraftServer>(
                replay = 0, extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST,
            )
            val server = mock(MinecraftServer::class.java)

            val deferred = async { flow.take(3).last() }
            // Yield so the consumer attaches before we emit.
            yield()
            flow.tryEmit(server) shouldBe true
            yield()
            flow.tryEmit(server) shouldBe true
            yield()
            flow.tryEmit(server) shouldBe true

            deferred.await() shouldBe server
        }
    }

    test("first(predicate) returns the matching emission") {
        runTest {
            val flow = MutableSharedFlow<MinecraftServer>(
                replay = 0, extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST,
            )
            val a = mock(MinecraftServer::class.java)
            val b = mock(MinecraftServer::class.java)

            val deferred = async { flow.first { it === b } }
            yield()
            flow.tryEmit(a)
            yield()
            flow.tryEmit(b)
            yield()

            deferred.await() shouldBe b
        }
    }

    test("awaitTicks rejects n=0") {
        runTest {
            val ex = runCatching { awaitTicks(0) }.exceptionOrNull()
            (ex is IllegalArgumentException) shouldBe true
        }
    }
})
```

(Tests `awaitTicks(0)` validates the `require(n >= 1)` in `Suspending.kt`. The other two tests validate the underlying flow semantics by exercising the same operators `awaitTicks`/`awaitTickWhere` use, against a hand-driven flow — no MC needed.)

- [ ] **Step 2: Add test deps to root `build.gradle.kts` `dependencies { }`**

```kotlin
    testImplementation("io.kotest:kotest-runner-junit5:5.9.1")
    testImplementation("io.kotest:kotest-assertions-core:5.9.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.1")
    testImplementation("org.mockito:mockito-core:5.14.2")
    testImplementation(sourceSets["testBridge"].output)
```

The last line lets `src/test/` see `awaitTicks` etc.

- [ ] **Step 3: Run the tests; expect them to pass**

```
cmd.exe /c "./gradlew.bat :26.1:test --tests *SuspendingTest"
```

Expected: BUILD SUCCESSFUL, 3 tests passed.

If the Kotest engine isn't picked up, verify `useJUnitPlatform()` is still in `tasks.test { ... }` (it should be — already present at line 162 of root build).

- [ ] **Step 4: Commit**

```bash
git add src/test/kotlin/com/breadmoirai/redstonespecs/testing build.gradle.kts
git commit -m "test(testBridge): add SuspendingTest for awaitTicks/awaitTickWhere"
```

### Task 7: Client context holder (`ClientContextHolder.kt`)

**Files:**
- Create: `src/testBridge/kotlin/com/breadmoirai/redstonespecs/testing/core/ClientContextHolder.kt`

- [ ] **Step 1: Write the file**

```kotlin
package com.breadmoirai.redstonespecs.testing.core

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext

/**
 * Holds the active `ClientGameTestContext` for client-source-set Kotest specs to consume.
 *
 * Set by `ClientTestSentinel.runTest` before launching Kotest; cleared after.
 */
@Suppress("UnstableApiUsage")
object ClientContextHolder {
    @Volatile private var _context: ClientGameTestContext? = null

    val context: ClientGameTestContext
        get() = _context ?: error("ClientContextHolder accessed outside an active client gametest")

    fun install(ctx: ClientGameTestContext) {
        _context = ctx
    }

    fun clear() {
        _context = null
    }
}
```

- [ ] **Step 2: Verify it compiles**

```
cmd.exe /c "./gradlew.bat :26.1:testBridgeClasses"
```

Expected: BUILD SUCCESSFUL.

If `ClientGameTestContext` doesn't resolve at compile time, `testBridge` doesn't have `fabric-client-gametest-api-v1` on its classpath. Add to root `build.gradle.kts`:
```kotlin
"testBridgeImplementation"(fabricApi.module("fabric-client-gametest-api-v1", project.property("fabric_version") as String))
```

- [ ] **Step 3: Commit**

```bash
git add src/testBridge/kotlin/com/breadmoirai/redstonespecs/testing/core/ClientContextHolder.kt
# include build.gradle.kts in the commit if step 2 required the dep addition
git commit -m "feat(testBridge): add ClientContextHolder for client specs"
```

### Task 8: Structure spawning (`Structures.kt`)

**Files:**
- Create: `src/testBridge/kotlin/com/breadmoirai/redstonespecs/testing/server/Structures.kt`

The `StructureGrid` allocates fresh chunk-aligned regions and tears them down between tests. Sequential mode = always slot 0; concurrent mode allocates slot N at offset (32·N, 64, 0). Templates loaded from `data/<ns>/structures/*.nbt` via MC's `StructureManager`.

- [ ] **Step 1: Write the file**

```kotlin
package com.breadmoirai.redstonespecs.testing.server

import com.breadmoirai.redstonespecs.testing.core.McDispatchers
import kotlinx.coroutines.withContext
import net.minecraft.core.BlockPos
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.Rotation
import net.minecraft.world.level.levelgen.structure.BoundingBox
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings
import java.util.concurrent.ConcurrentHashMap

private const val GRID_Y = 64
private const val SLOT_SIZE = 32
private const val MAX_SLOTS = 16

/** Per-test handle to a spawned structure copy. */
class StructureHandle(
    val origin: BlockPos,
    val bounds: BoundingBox,
    private val server: MinecraftServer,
    private val grid: StructureGrid,
    private val slotIndex: Int,
) {
    fun absolute(relative: BlockPos): BlockPos =
        origin.offset(relative.x, relative.y, relative.z)

    fun signalAt(relative: BlockPos): Int {
        val pos = absolute(relative)
        return server.overworld().getBestNeighborSignal(pos)
    }

    suspend fun teardown() {
        withContext(McDispatchers.Server) {
            val level = server.overworld()
            BlockPos.betweenClosed(
                bounds.minX(), bounds.minY(), bounds.minZ(),
                bounds.maxX(), bounds.maxY(), bounds.maxZ(),
            ).forEach { p ->
                level.setBlock(p, Blocks.AIR.defaultBlockState(), 2)
            }
            grid.releaseSlot(slotIndex)
        }
    }
}

/** Per-server allocator for structure spawn regions. */
class StructureGrid(private val server: MinecraftServer) {
    private val freeSlots: ArrayDeque<Int> = ArrayDeque((0 until MAX_SLOTS).toList())

    @Synchronized
    private fun acquireSlot(): Int {
        check(freeSlots.isNotEmpty()) { "StructureGrid exhausted: no free slots (max $MAX_SLOTS)" }
        return freeSlots.removeFirst()
    }

    @Synchronized
    internal fun releaseSlot(index: Int) {
        if (index !in 0 until MAX_SLOTS) return
        if (freeSlots.contains(index)) return
        freeSlots.addFirst(index)
    }

    /** Must be called on the server thread. */
    fun spawn(id: ResourceLocation): StructureHandle {
        require(server.isSameThread) { "StructureGrid.spawn must be called on the server thread" }
        val level: ServerLevel = server.overworld()
        val template = level.structureManager.get(id).orElseThrow {
            IllegalArgumentException("Structure not found: $id")
        }
        val slot = acquireSlot()
        val origin = BlockPos(slot * SLOT_SIZE, GRID_Y, 0)
        val settings = StructurePlaceSettings().setRotation(Rotation.NONE)
        template.placeInWorld(level, origin, origin, settings, level.random, 2)
        val size = template.size
        val bounds = BoundingBox(
            origin.x, origin.y, origin.z,
            origin.x + size.x - 1, origin.y + size.y - 1, origin.z + size.z - 1,
        )
        return StructureHandle(origin, bounds, server, this, slot)
    }

    companion object {
        private val grids = ConcurrentHashMap<MinecraftServer, StructureGrid>()
        fun forServer(server: MinecraftServer): StructureGrid =
            grids.computeIfAbsent(server) { StructureGrid(it) }
    }
}

/** Spawns the structure named [id] into a fresh grid slot. */
suspend fun spawnStructure(id: ResourceLocation): StructureHandle =
    onServer { StructureGrid.forServer(this).spawn(id) }
```

`StructureManager.get` returns `Optional<StructureTemplate>`. `placeInWorld` signature in 26.1: `(ServerLevelAccessor, BlockPos pos, BlockPos pivot, StructurePlaceSettings, RandomSource, int flags)`. The exact signature may have shifted across MC versions — verify against the decompiled source at `.gradle/loom-cache/minecraftMaven/.../minecraft-common-*-sources.jar` if compile fails. Memory ref: see `reference_mc_sources.md`.

- [ ] **Step 2: Verify it compiles**

```
cmd.exe /c "./gradlew.bat :26.1:testBridgeClasses"
```

Expected: BUILD SUCCESSFUL.

If imports for `StructurePlaceSettings` or `BoundingBox` fail, extract the sources jar and search for the actual package — they have moved between MC versions historically.

- [ ] **Step 3: Commit**

```bash
git add src/testBridge/kotlin/com/breadmoirai/redstonespecs/testing/server/Structures.kt
git commit -m "feat(testBridge): add StructureGrid, StructureHandle, spawnStructure"
```

### Task 9: Result collector (`ResultCollector.kt`)

**Files:**
- Create: `src/testBridge/kotlin/com/breadmoirai/redstonespecs/testing/launcher/ResultCollector.kt`

- [ ] **Step 1: Write the file**

```kotlin
package com.breadmoirai.redstonespecs.testing.launcher

import io.kotest.core.listeners.TestListener
import io.kotest.core.test.TestCase
import io.kotest.core.test.TestResult

data class TestFailureRecord(val name: String, val message: String, val cause: Throwable?)

data class LauncherResult(
    val passed: Int,
    val failed: Int,
    val errors: List<TestFailureRecord>,
) {
    val total: Int get() = passed + failed
    fun summary(): String = if (failed == 0) {
        "All $total tests passed"
    } else {
        val sample = errors.take(5).joinToString("\n  ") { "${it.name}: ${it.message}" }
        "$failed/$total failed:\n  $sample" + if (errors.size > 5) "\n  ... (${errors.size - 5} more)" else ""
    }
}

internal class ResultCollector : TestListener {
    private var passed = 0
    private var failed = 0
    private val errors = mutableListOf<TestFailureRecord>()

    @Volatile var result: LauncherResult = LauncherResult(0, 0, emptyList())
        private set

    override suspend fun afterTest(testCase: TestCase, result: TestResult) {
        when (result) {
            is TestResult.Success -> passed++
            is TestResult.Failure -> {
                failed++
                errors.add(TestFailureRecord(testCase.name.testName, result.errorOrNull?.message ?: "(no message)", result.errorOrNull))
            }
            is TestResult.Error -> {
                failed++
                errors.add(TestFailureRecord(testCase.name.testName, result.errorOrNull?.message ?: "(error)", result.errorOrNull))
            }
            else -> {} // Ignored, Pending, etc.
        }
        this.result = LauncherResult(passed, failed, errors.toList())
    }
}
```

`TestResult` is a sealed class in Kotest 5.9 with `Success`, `Failure`, `Error`, `Ignored`. If the API has shifted in your specific Kotest version, adjust the `when` branches — the failing tests must end up in `errors`, the passing in the `passed` count.

- [ ] **Step 2: Verify it compiles**

```
cmd.exe /c "./gradlew.bat :26.1:testBridgeClasses"
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add src/testBridge/kotlin/com/breadmoirai/redstonespecs/testing/launcher/ResultCollector.kt
git commit -m "feat(testBridge): add ResultCollector and LauncherResult"
```

### Task 10: Kotest launcher (`KotestLauncher.kt`)

**Files:**
- Create: `src/testBridge/kotlin/com/breadmoirai/redstonespecs/testing/launcher/KotestLauncher.kt`

- [ ] **Step 1: Write the file**

```kotlin
package com.breadmoirai.redstonespecs.testing.launcher

import io.kotest.engine.TestEngineLauncher
import io.kotest.core.config.AbstractProjectConfig
import java.nio.file.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.createDirectories

/**
 * Launches the Kotest engine on the calling thread. Sets per-source-set output directories
 * for Kotest's reports and Kensa's HTML before invoking the engine.
 *
 * Specs are discovered automatically from the launching thread's classpath via Kotest's
 * built-in classpath scan.
 *
 * @param sourceSet "gametest" | "clientTest" | "test" — used to scope report directories.
 * @param reportsDir Base directory for reports (e.g., `build/reports/redstonespecs/<sourceSet>`).
 */
fun launchKotest(sourceSet: String, reportsDir: Path): LauncherResult {
    reportsDir.createDirectories()
    val kensaDir = reportsDir.resolve("kensa").also { it.createDirectories() }

    System.setProperty("kensa.report.dir", kensaDir.absolutePathString())

    val collector = ResultCollector()
    val config = object : AbstractProjectConfig() {
        override val parallelism: Int = 1
    }

    TestEngineLauncher()
        .withProjectConfig(config)
        .withExtensions(collector)
        .launch()

    return collector.result
}
```

The `parallelism = 1` enforces sequential spec/leaf execution by default. Specs that opt in via `@Isolate` or per-spec config can override.

`TestEngineLauncher.withExtensions` accepts `Extension` instances; `TestListener` extends `Extension`. If the API differs in your Kotest version, the symbol to look for is "register a `TestListener` that gets `afterTest` callbacks."

- [ ] **Step 2: Verify it compiles**

```
cmd.exe /c "./gradlew.bat :26.1:testBridgeClasses"
```

Expected: BUILD SUCCESSFUL.

If `TestEngineLauncher` isn't found, the `kotest-runner-junit5` dep may not transitively expose `kotest-framework-engine`. Add explicitly:
```kotlin
"testBridgeImplementation"("io.kotest:kotest-framework-engine:5.9.1")
```

- [ ] **Step 3: Commit**

```bash
git add src/testBridge/kotlin/com/breadmoirai/redstonespecs/testing/launcher/KotestLauncher.kt
git commit -m "feat(testBridge): add Kotest engine launcher"
```

---

## Phase C — Sentinels and smoke test

### Task 11: Empty platform structure for the gametest sentinel

**Files:**
- Create: `src/gametest/resources/data/redstonespecs/structures/empty_platform.snbt`

- [ ] **Step 1: Write the SNBT file**

```snbt
{
  size: [3, 1, 3],
  blocks: [
    {pos: [0, 0, 0], state: 0},
    {pos: [1, 0, 0], state: 0},
    {pos: [2, 0, 0], state: 0},
    {pos: [0, 0, 1], state: 0},
    {pos: [1, 0, 1], state: 0},
    {pos: [2, 0, 1], state: 0},
    {pos: [0, 0, 2], state: 0},
    {pos: [1, 0, 2], state: 0},
    {pos: [2, 0, 2], state: 0}
  ],
  palette: [
    {Name: "minecraft:stone"}
  ],
  entities: [],
  DataVersion: 4189
}
```

A 3x1x3 stone platform — a valid minimal structure for MC's gametest framework to spawn the sentinel into. `DataVersion` should match the active MC version (26.1.2 → 4189; verify if compile/run fails with a "DataVersion mismatch" warning by checking `SharedConstants.DATA_VERSION` for 26.1.2).

- [ ] **Step 2: Commit**

```bash
git add src/gametest/resources/data/redstonespecs/structures/empty_platform.snbt
git commit -m "test(gametest): add empty_platform structure for Kotest sentinel"
```

### Task 12: Gametest sentinel (`GametestSentinel.kt`)

**Files:**
- Create: `src/gametest/kotlin/com/breadmoirai/redstonespecs/test/GametestSentinel.kt`

- [ ] **Step 1: Write the file**

```kotlin
package com.breadmoirai.redstonespecs.test

import com.breadmoirai.redstonespecs.testing.core.TestBridgeLifecycle
import com.breadmoirai.redstonespecs.testing.launcher.launchKotest
import net.fabricmc.fabric.api.gametest.v1.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import org.slf4j.LoggerFactory
import java.nio.file.Path

/**
 * Single sentinel for the gametest source set. Launches the Kotest engine on a worker
 * thread and resolves the gametest via `helper.succeed()`/`fail()` posted back to the
 * server thread. We must NOT call launchKotest synchronously from this method, because
 * that would block the server tick loop and prevent any of the suspending primitives
 * from making progress.
 */
class GametestSentinel {

    private val logger = LoggerFactory.getLogger("Redstone Specs")

    @GameTest(template = "redstonespecs:empty_platform", timeoutTicks = 12000)
    fun runAll(helper: GameTestHelper) {
        TestBridgeLifecycle.register()

        val server = helper.level.server
        val worker = Thread.ofPlatform()
            .name("kotest-gametest")
            .uncaughtExceptionHandler { _, t ->
                logger.error("Kotest worker crashed", t)
                server.execute { helper.fail("Kotest worker crashed: ${t.message}") }
            }
            .unstarted {
                val result = runCatching {
                    launchKotest(
                        sourceSet = "gametest",
                        reportsDir = Path.of("build/reports/redstonespecs/gametest"),
                    )
                }
                server.execute {
                    result.fold(
                        onSuccess = { r ->
                            if (r.failed > 0) helper.fail(r.summary())
                            else helper.succeed()
                        },
                        onFailure = { e ->
                            logger.error("Kotest engine error", e)
                            helper.fail("Kotest engine error: ${e.message}")
                        },
                    )
                }
            }
        worker.start()
        // Return immediately. GameTestSequence keeps the test alive until the worker
        // resolves it via helper.succeed()/fail().
    }
}
```

`TestBridgeLifecycle.register()` is idempotent and safe to call from the gametest method body, but in practice the server-lifecycle event has already fired by the time we get here. Kept for defense in depth.

- [ ] **Step 2: Verify it compiles**

```
cmd.exe /c "./gradlew.bat :26.1:gametestClasses"
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add src/gametest/kotlin/com/breadmoirai/redstonespecs/test/GametestSentinel.kt
git commit -m "test(gametest): add Kotest sentinel"
```

### Task 13: Client sentinel (`ClientTestSentinel.kt`)

**Files:**
- Create: `src/clientTest/kotlin/com/breadmoirai/redstonespecs/test/ClientTestSentinel.kt`

- [ ] **Step 1: Write the file**

```kotlin
package com.breadmoirai.redstonespecs.test

import com.breadmoirai.redstonespecs.testing.core.ClientContextHolder
import com.breadmoirai.redstonespecs.testing.core.TestBridgeLifecycle
import com.breadmoirai.redstonespecs.testing.launcher.launchKotest
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext
import org.slf4j.LoggerFactory
import java.nio.file.Path

@Suppress("UnstableApiUsage")
class ClientTestSentinel : FabricClientGameTest {

    private val logger = LoggerFactory.getLogger("Redstone Specs")

    override fun runTest(context: ClientGameTestContext) {
        TestBridgeLifecycle.register()
        ClientContextHolder.install(context)
        try {
            val result = launchKotest(
                sourceSet = "clientTest",
                reportsDir = Path.of("build/reports/redstonespecs/clientTest"),
            )
            if (result.failed > 0) {
                logger.error(result.summary())
                error("${result.failed}/${result.total} Kotest test(s) failed")
            }
            logger.info(result.summary())
        } finally {
            ClientContextHolder.clear()
        }
    }
}
```

- [ ] **Step 2: Verify it compiles**

```
cmd.exe /c "./gradlew.bat :26.1:clientTestClasses"
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add src/clientTest/kotlin/com/breadmoirai/redstonespecs/test/ClientTestSentinel.kt
git commit -m "test(clientTest): add Kotest sentinel"
```

### Task 14: Update `fabric.mod.json` for both source sets

**Files:**
- Modify: `src/gametest/resources/fabric.mod.json`
- Modify: `src/clientTest/resources/fabric.mod.json`

- [ ] **Step 1: Read the existing gametest fabric.mod.json**

```bash
cat src/gametest/resources/fabric.mod.json
```

The file has an `"entrypoints"` block with a `"fabric-gametest"` key listing the test class. Replace any existing `RedstonespecsGameTests` entry with `com.breadmoirai.redstonespecs.test.GametestSentinel`.

- [ ] **Step 2: Edit gametest fabric.mod.json**

Replace the `RedstonespecsGameTests` entry under `entrypoints["fabric-gametest"]` with:
```
"com.breadmoirai.redstonespecs.test.GametestSentinel"
```

- [ ] **Step 3: Read the existing clientTest fabric.mod.json**

```bash
cat src/clientTest/resources/fabric.mod.json
```

Replace any existing `RedstonespecsClientTests` entry with `com.breadmoirai.redstonespecs.test.ClientTestSentinel`.

- [ ] **Step 4: Verify both still parse and compile**

```
cmd.exe /c "./gradlew.bat :26.1:gametestClasses :26.1:clientTestClasses"
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add src/gametest/resources/fabric.mod.json src/clientTest/resources/fabric.mod.json
git commit -m "test: point fabric.mod.json entrypoints to Kotest sentinels"
```

### Task 15: Smoke gametest spec

**Files:**
- Create: `src/gametest/kotlin/com/breadmoirai/redstonespecs/test/SmokeSpec.kt`

This is the first actual Kotest spec in the gametest source set. It exercises `awaitTicks`, `onServer`, and `spawnStructure` against the empty_platform structure.

- [ ] **Step 1: Write the spec**

```kotlin
package com.breadmoirai.redstonespecs.test

import com.breadmoirai.redstonespecs.testing.server.awaitTicks
import com.breadmoirai.redstonespecs.testing.server.onServer
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.ints.shouldBeGreaterThan

class SmokeSpec : FunSpec({

    test("awaitTicks advances the server tick counter") {
        val before = onServer { tickCount }
        awaitTicks(3)
        val after = onServer { tickCount }
        (after - before) shouldBeGreaterThan 2
    }

    test("onServer hops to the server thread") {
        val onServerThread = onServer { isSameThread }
        onServerThread shouldBe true
    }
})
```

(Imports: ensure `io.kotest.matchers.shouldBe` is present if the `shouldBe` matcher is used elsewhere; the `shouldBeGreaterThan` import is from `io.kotest.matchers.ints`.)

Final imports:
```kotlin
import com.breadmoirai.redstonespecs.testing.server.awaitTicks
import com.breadmoirai.redstonespecs.testing.server.onServer
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
```

- [ ] **Step 2: Verify it compiles**

```
cmd.exe /c "./gradlew.bat :26.1:gametestClasses"
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Run the gametest task end-to-end**

```
cmd.exe /c "./gradlew.bat :26.1:runGameTest"
```

Expected: BUILD SUCCESSFUL. The gametest log shows "All 2 tests passed" (or similar from `LauncherResult.summary()`). Kotest's report appears under `versions/26.1/build/reports/redstonespecs/gametest/`.

If the run fails: read `versions/26.1/build/gametest/logs/` for MC server logs; the most likely failure modes are (a) structure NBT can't be loaded (data version mismatch — see Task 11), (b) `TestBridgeLifecycle` event registration timing wrong, or (c) Kotest classpath scan fails to find `SmokeSpec` (verify `discoveryRoot` config or pass an explicit class via `withSpecs(SmokeSpec::class)`).

- [ ] **Step 4: Commit**

```bash
git add src/gametest/kotlin/com/breadmoirai/redstonespecs/test/SmokeSpec.kt
git commit -m "test(gametest): add SmokeSpec exercising awaitTicks and onServer"
```

---

## Phase D — Migrate `src/test/` to Kotest

`src/test/` doesn't use the bridge runtime — it just runs Kotest specs through the JUnit Platform engine. These migrations are independent of Phases A/B/C and could be done in parallel.

### Task 16: Switch `src/test/` Gradle wiring to Kotest

**Files:**
- Modify: `build.gradle.kts` (root)

- [ ] **Step 1: Update test deps**

In the `dependencies { }` block, find:
```kotlin
testImplementation("net.fabricmc:fabric-loader-junit:${project.property("loader_version")}")
```

Drop it (or keep it if any test still bootstraps via `Bootstrap.bootStrap()` and that requires the fabric-loader-junit shim — verify by trying without first). Also confirm the Kotest test deps from Task 6 step 2 are present:
```kotlin
testImplementation("io.kotest:kotest-runner-junit5:5.9.1")
testImplementation("io.kotest:kotest-assertions-core:5.9.1")
```

- [ ] **Step 2: Run existing JUnit tests; expect them to still pass (Kotest Platform engine doesn't break them)**

```
cmd.exe /c "./gradlew.bat :26.1:test"
```

Expected: all existing JUnit tests still pass. Kotest engine is on the classpath but only picks up `FunSpec`-derived classes; JUnit tests are unaffected.

- [ ] **Step 3: Commit**

```bash
git add build.gradle.kts
git commit -m "build: add Kotest engine to src/test/, alongside existing JUnit"
```

### Task 17: Migrate `IntEditBoxLogicTest`

**Files:**
- Modify: `src/test/kotlin/com/breadmoirai/redstonespecs/data/IntEditBoxLogicTest.kt`

- [ ] **Step 1: Replace the file content**

```kotlin
package com.breadmoirai.redstonespecs.data

import com.breadmoirai.redstonespecs.client.screen.formatIntValue
import com.breadmoirai.redstonespecs.client.screen.parseIntValue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class IntEditBoxLogicTest : FunSpec({

    test("parse normal integer") {
        parseIntValue("5", min = 1, max = 10) shouldBe 5
    }

    test("parse clamps to min") {
        parseIntValue("0", min = 1, max = 10) shouldBe 1
    }

    test("parse clamps to max") {
        parseIntValue("99", min = 1, max = 10) shouldBe 10
    }

    test("parse blank returns min") {
        parseIntValue("", min = 1, max = 10) shouldBe 1
    }

    test("parse non-numeric returns min") {
        parseIntValue("abc", min = 1, max = 10) shouldBe 1
    }

    test("parse START string when min is -1 returns -1") {
        parseIntValue("START", min = -1, max = 100) shouldBe -1
    }

    test("parse START string when min is not -1 returns min") {
        parseIntValue("START", min = 1, max = 10) shouldBe 1
    }

    test("format negative one as START when min is -1") {
        formatIntValue(-1, min = -1) shouldBe "START"
    }

    test("format negative one as string when min is not -1") {
        formatIntValue(-1, min = 0) shouldBe "-1"
    }

    test("format normal value") {
        formatIntValue(42, min = 0) shouldBe "42"
    }
})
```

- [ ] **Step 2: Run the test**

```
cmd.exe /c "./gradlew.bat :26.1:test --tests *IntEditBoxLogicTest"
```

Expected: BUILD SUCCESSFUL, 10 tests passed.

- [ ] **Step 3: Commit**

```bash
git add src/test/kotlin/com/breadmoirai/redstonespecs/data/IntEditBoxLogicTest.kt
git commit -m "test: migrate IntEditBoxLogicTest to Kotest"
```

### Task 18: Migrate `SimTimeTest`

**Files:**
- Modify: `src/test/kotlin/com/breadmoirai/redstonespecs/data/SimTimeTest.kt`

The migration template: `@BeforeAll` → `beforeSpec`, `assertEquals(a, b)` → `b shouldBe a`, `assertTrue(cond)` → `cond shouldBe true`. The `Bootstrap.bootStrap()` lives in `beforeSpec`.

- [ ] **Step 1: Replace the file content**

```kotlin
package com.breadmoirai.redstonespecs.data

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import net.minecraft.SharedConstants
import net.minecraft.nbt.NbtOps
import net.minecraft.server.Bootstrap

class SimTimeTest : FunSpec({

    beforeSpec {
        SharedConstants.tryDetectVersion()
        Bootstrap.bootStrap()
    }

    test("START sorts before tick 0") {
        val t0 = SimTime(0, Phase.START_OF_TICK)
        (SimTime.START < t0) shouldBe true
    }

    test("tick ordering") {
        val t1 = SimTime(0, Phase.END_OF_TICK)
        val t2 = SimTime(1, Phase.START_OF_TICK)
        (t1 < t2) shouldBe true
    }

    test("phase ordering within same tick") {
        Phase.entries.zipWithNext().forEach { (a, b) ->
            (SimTime(0, a) < SimTime(0, b)) shouldBe true
        }
    }

    test("order tiebreaker within same tick and phase") {
        val t1 = SimTime(0, Phase.START_OF_TICK, 0)
        val t2 = SimTime(0, Phase.START_OF_TICK, 1)
        (t1 < t2) shouldBe true
    }

    test("equal SimTimes compare to zero") {
        val t = SimTime(5, Phase.BLOCK_EVENTS, 3)
        t.compareTo(t) shouldBe 0
    }

    test("codec roundtrip via NBT") {
        val simTime = SimTime(5, Phase.SCHEDULED_TICKS, 3)
        val encoded = SimTime.CODEC.encodeStart(NbtOps.INSTANCE, simTime).getOrThrow()
        val decoded = SimTime.CODEC.parse(NbtOps.INSTANCE, encoded).getOrThrow()
        decoded shouldBe simTime
    }

    test("START codec roundtrip") {
        val encoded = SimTime.CODEC.encodeStart(NbtOps.INSTANCE, SimTime.START).getOrThrow()
        val decoded = SimTime.CODEC.parse(NbtOps.INSTANCE, encoded).getOrThrow()
        decoded shouldBe SimTime.START
    }

    test("default order omitted from NBT") {
        val withDefaultOrder = SimTime(1, Phase.START_OF_TICK, 0)
        val encoded = SimTime.CODEC.encodeStart(NbtOps.INSTANCE, withDefaultOrder).getOrThrow()
        val decoded = SimTime.CODEC.parse(NbtOps.INSTANCE, encoded).getOrThrow()
        decoded shouldBe withDefaultOrder
    }

    test("all phases roundtrip") {
        Phase.entries.forEach { phase ->
            val t = SimTime(0, phase)
            val encoded = SimTime.CODEC.encodeStart(NbtOps.INSTANCE, t).getOrThrow()
            val decoded = SimTime.CODEC.parse(NbtOps.INSTANCE, encoded).getOrThrow()
            decoded shouldBe t
        }
    }
})
```

- [ ] **Step 2: Run the test**

```
cmd.exe /c "./gradlew.bat :26.1:test --tests *SimTimeTest"
```

Expected: BUILD SUCCESSFUL, 9 tests passed.

If `Bootstrap.bootStrap()` fails with classpath issues, the dropped `fabric-loader-junit` was needed. Restore it as `testRuntimeOnly` and confirm it loads.

- [ ] **Step 3: Commit**

```bash
git add src/test/kotlin/com/breadmoirai/redstonespecs/data/SimTimeTest.kt
git commit -m "test: migrate SimTimeTest to Kotest"
```

### Task 19: Migrate the remaining 8 unit tests

The other 8 files follow the same mechanical pattern. For each: convert class to `: FunSpec({ ... })`, `@Test fun foo()` → `test("foo") { ... }`, `@BeforeAll` → `beforeSpec`, JUnit `assertX` → Kotest `shouldX`. `@TempDir` → `tempdir()` (Kotest provides this as a function inside the test scope).

For each file, do steps Read → Convert → Run → Commit. One commit per file so reviews stay digestible.

- [ ] **Step 1: Migrate `StateConditionTest.kt`**

Path: `src/test/kotlin/com/breadmoirai/redstonespecs/data/StateConditionTest.kt`. Read the file, convert assertions, run `:26.1:test --tests *StateConditionTest`, commit.

- [ ] **Step 2: Migrate `SpecPersistenceTest.kt`**

Path: `src/test/kotlin/com/breadmoirai/redstonespecs/persistence/SpecPersistenceTest.kt`. Note: uses `@TempDir Path` parameter. Replace with `val tmp = kotlin.io.path.createTempDirectory("specPersistence")` inside the test body. (Avoids depending on Kotest's `tempdir()` API surface, which has shifted between minor versions.) Run, commit. The temp dir won't auto-clean — acceptable for a unit test that produces a single small file.

- [ ] **Step 3: Migrate `StateRecordingStorageTest.kt`**

Path: `src/test/kotlin/com/breadmoirai/redstonespecs/runner/StateRecordingStorageTest.kt`. Convert, run, commit.

- [ ] **Step 4: Migrate `StateRecordingViewTest.kt`**

Path: `src/test/kotlin/com/breadmoirai/redstonespecs/runner/StateRecordingViewTest.kt`. Convert, run, commit.

- [ ] **Step 5: Migrate `SpecDslTest.kt`**

Path: `src/test/kotlin/com/breadmoirai/redstonespecs/data/dsl/SpecDslTest.kt`. Convert, run, commit.

- [ ] **Step 6: Migrate `KtsSpecEmitterTest.kt`**

Path: `src/test/kotlin/com/breadmoirai/redstonespecs/data/serial/KtsSpecEmitterTest.kt`. Convert, run, commit.

- [ ] **Step 7: Migrate `KtsSpecLoaderTest.kt`**

Path: `src/test/kotlin/com/breadmoirai/redstonespecs/data/serial/KtsSpecLoaderTest.kt`. Convert, run, commit.

- [ ] **Step 8: Migrate `SpecJsonCodecTest.kt`**

Path: `src/test/kotlin/com/breadmoirai/redstonespecs/data/serial/SpecJsonCodecTest.kt`. Convert, run, commit.

- [ ] **Step 9: Run the full unit-test suite end-to-end to catch any cross-file regression**

```
cmd.exe /c "./gradlew.bat :26.1:test"
```

Expected: BUILD SUCCESSFUL, all migrated specs pass.

---

## Phase E — Cleanup and docs

### Task 20: Delete placeholder gametest and clientTest stubs

**Files:**
- Delete: `src/gametest/kotlin/com/breadmoirai/redstonespecs/test/RedstonespecsGameTests.kt`
- Delete: `src/clientTest/kotlin/com/breadmoirai/redstonespecs/test/RedstonespecsClientTests.kt`

The pre-existing `lever_lamp.snbt` resource stays — it's the structure for the first real spec.

- [ ] **Step 1: Delete the stubs**

```bash
git rm src/gametest/kotlin/com/breadmoirai/redstonespecs/test/RedstonespecsGameTests.kt
git rm src/clientTest/kotlin/com/breadmoirai/redstonespecs/test/RedstonespecsClientTests.kt
```

- [ ] **Step 2: Verify the build still passes**

```
cmd.exe /c "./gradlew.bat :26.1:gametestClasses :26.1:clientTestClasses"
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git commit -m "test: drop placeholder stubs superseded by Kotest sentinels"
```

### Task 21: Add `docs/gametest/kotest-bridge.md`

**Files:**
- Create: `docs/gametest/kotest-bridge.md`
- Modify: `docs/gametest/INDEX.md`

- [ ] **Step 1: Write the article**

```markdown
---
title: Kotest + coroutine test bridge
tags: [testing, kotest, coroutines, gametest, client-gametest]
summary: How specs work in `src/gametest/`, `src/clientTest/`, and `src/test/` — the `awaitTicks` / `onServer` / `spawnStructure` cookbook plus invariants you must respect.
---

# Kotest + coroutine test bridge

All three test source sets run Kotest specs. The `testBridge` source set provides
coroutine-friendly primitives over MC's tick system; sentinel `@GameTest` /
`FabricClientGameTest` entry points launch the Kotest engine on a worker thread.
Design rationale lives in `docs/superpowers/specs/2026-05-07-kotest-coroutine-test-bridge-design.md`.

## Where specs live

- `src/test/` — pure JVM specs. No MC level, no server. Use Kotest matchers freely.
  Bootstrap via `beforeSpec { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap() }`
  if you need registries.
- `src/gametest/` — server-side specs. Discovered by Kotest's classpath scan via
  `GametestSentinel.runAll`. Use `awaitTicks`, `onServer`, `spawnStructure`.
- `src/clientTest/` — client + server specs. Discovered via `ClientTestSentinel`.
  Use `ClientContextHolder.context` to drive the client (input, screens, widgets).

## Cookbook

```kotlin
class ComparatorSpec : FunSpec({
    test("comparator latches after 4 ticks") {
        val s = spawnStructure(ResourceLocation.fromNamespaceAndPath("redstonespecs", "basic"))
        try {
            onServer { /* press button at s.absolute(BlockPos(2, 2, 1)) */ }
            awaitTicks(4)
            onServer { s.signalAt(BlockPos(4, 2, 1)) shouldBe 15 }
        } finally {
            s.teardown()
        }
    }
})
```

`awaitTicks(n)` suspends until `n` `END_SERVER_TICK` events have fired. `onServer { }`
hops to the server thread for any block-state mutation or read.

## Invariants

- **Never `join()` a worker on the server thread.** The gametest sentinel spawns
  a worker that runs Kotest; the sentinel returns immediately. Joining would
  freeze the tick loop and deadlock all suspending primitives.
- **Keep `onServer { }` blocks short.** A 100ms watchdog logs a warning when a
  server-thread runnable runs longer than one tick. Long compute belongs off-thread.
- **Use `spawnStructure` per test for isolation.** The default sequential model
  reuses grid slot 0 with cleanup; opt-in concurrency uses additional slots.
  Don't share spawned structures across tests.

## Reports

- `build/reports/redstonespecs/<sourceSet>/kensa/` — Kensa HTML, literate test
  documentation. Auto-registers via ServiceLoader.
- `build/test-results/<sourceSet>/` — JUnit XML for CI consumption.
- `build/reports/redstonespecs/<sourceSet>/` — Kotest's own console + HTML.
```

- [ ] **Step 2: Register in INDEX.md**

Add to `docs/gametest/INDEX.md`:
```markdown
- [Kotest + coroutine test bridge](kotest-bridge.md) — How specs work; the awaitTicks/onServer/spawnStructure cookbook; invariants. Tags: testing, kotest, coroutines.
```

- [ ] **Step 3: Update `docs/gametest/unit-vs-gametest-split.md`**

Open the file. Replace the framework references (JUnit 5, Fabric `@GameTest` methods, `FabricClientGameTest.runTest`) with: "All three source sets run Kotest specs; see `kotest-bridge.md` for the DSL." Keep the *guidance* (which logic belongs where) — that's still valid; only the framework layer changed.

- [ ] **Step 4: Commit**

```bash
git add docs/gametest/kotest-bridge.md docs/gametest/INDEX.md docs/gametest/unit-vs-gametest-split.md
git commit -m "docs(gametest): add Kotest bridge cookbook; update unit-vs-gametest split"
```

### Task 22: Add build doc

**Files:**
- Create: `docs/build/test-bridge-source-set.md`
- Modify: `docs/build/INDEX.md`

- [ ] **Step 1: Write `test-bridge-source-set.md`**

```markdown
---
title: testBridge source set wiring
tags: [gradle, source-sets, testing]
summary: Why `testBridge` is a source set rather than a Gradle subproject; how `gametest` and `clientTest` consume it.
---

# `testBridge` source set wiring

`testBridge` lives at `src/testBridge/` as a source set in the root project, not as
a separate Gradle subproject. Rationale (see spec `2026-05-07-kotest-coroutine-test-bridge-design.md`):
adding a real Gradle subproject would be the project's first non-Stonecutter
subproject and would entangle with loom + Stonecutter configuration. A source set
gives the same module boundary with no Gradle archaeology.

## Wiring shape

```
sourceSets {
    create("testBridge") {  // sees main + client output
        compileClasspath += sourceSets["main"].output + sourceSets["client"].output + ...
        runtimeClasspath += same
    }
    create("clientTest") {  // sees main + client + testBridge output
        compileClasspath += ... + sourceSets["testBridge"].output
        runtimeClasspath += ... + sourceSets["testBridge"].output
    }
}

afterEvaluate {  // gametest source set is created by loom; we patch it post-evaluation
    sourceSets.findByName("gametest")?.let { gt ->
        gt.compileClasspath += sourceSets["testBridge"].output
        gt.runtimeClasspath += sourceSets["testBridge"].output
    }
}
```

The `afterEvaluate` block is necessary because `fabricApi.configureTests { ... }`
creates the `gametest` source set during project evaluation; it doesn't exist
when the `sourceSets { ... }` block runs.

## Configurations

`testBridgeImplementation` extends `clientImplementation` so the bridge sees
client-side MC APIs (Minecraft, Screen, etc.) for the `ClientContextHolder` path.

## Dependency placement

Kotest, Kensa, and coroutines deps live on `testBridgeImplementation` in the root
build script. Both `gametest` and `clientTest` see them transitively via the
source-set classpath inheritance.
```

- [ ] **Step 2: Register in INDEX.md**

Add to `docs/build/INDEX.md`:
```markdown
- [testBridge source set wiring](test-bridge-source-set.md) — Why it's a source set, not a subproject; how gametest and clientTest pull it in (incl. the `afterEvaluate` patch for loom-created `gametest`). Tags: gradle, source-sets, testing.
```

- [ ] **Step 3: Commit**

```bash
git add docs/build/test-bridge-source-set.md docs/build/INDEX.md
git commit -m "docs(build): document testBridge source set wiring"
```

### Task 23: Final verification

- [ ] **Step 1: Build all source sets**

```
cmd.exe /c "./gradlew.bat :26.1:clientClasses :26.1:classes :26.1:gametestClasses :26.1:clientTestClasses :26.1:testClasses"
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 2: Run unit tests**

```
cmd.exe /c "./gradlew.bat :26.1:test"
```

Expected: BUILD SUCCESSFUL, all migrated tests pass.

- [ ] **Step 3: Run gametest end-to-end**

```
cmd.exe /c "./gradlew.bat :26.1:runGameTest"
```

Expected: BUILD SUCCESSFUL. Console output includes "All N tests passed" from `LauncherResult.summary()`. Reports under `versions/26.1/build/reports/redstonespecs/gametest/`.

- [ ] **Step 4: Run client gametest** (if a `ClientContext`-using spec exists; smoke spec is server-only)

```
cmd.exe /c "./gradlew.bat :26.1:runClientTest"
```

Expected: BUILD SUCCESSFUL or graceful "no specs found" if no client specs are written yet.

If all four steps pass, the bridge is functional end-to-end. Subsequent test authoring (re-authoring `RedstonespecsGameTests` and `RedstonespecsClientTests` against the flat SpecEntry model, per the data-layer redesign) is out of scope for this plan.
