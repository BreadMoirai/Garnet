# Managed Worlds Test Coverage Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add unit + gametest coverage for every `managed/*` class that lacks it (one test file per class), per the spec at `docs/superpowers/specs/2026-05-09-managed-worlds-test-coverage-design.md`.

**Architecture:** New unit tests under `src/test/kotlin/com/breadmoirai/redstonespecs/managed/` (Kotest `FunSpec`). New gametests under `src/gametest/kotlin/com/breadmoirai/redstonespecs/test/managed/` (extends `RedstoneTestSpec` from `src/main/.../testing/`). One shared fixtures file (`ManagedTestSupport.kt`) deduplicates harness boilerplate. `ManagedNetworkRegistry`'s handler bodies are extracted into `internal` functions so tests can drive them directly.

**Tech Stack:** Kotlin, Kotest 5.x (`io.kotest.core.spec.style.FunSpec`, `io.kotest.matchers.*`), Fabric/Minecraft 26.1, Gradle via Stonecutter.

---

## Build commands (reference)

- Unit-test compile only: `cmd.exe /c "./gradlew.bat :26.1:compileTestKotlin"`
- Run unit tests: `cmd.exe /c "./gradlew.bat :26.1:test"`
- Run a single unit test class: `cmd.exe /c "./gradlew.bat :26.1:test --tests 'com.breadmoirai.redstonespecs.managed.ManagedSaveNamingTest'"`
- Gametest compile only: `cmd.exe /c "./gradlew.bat :26.1:compileGametestKotlin"`
- Run gametests: `cmd.exe /c "./gradlew.bat :26.1:runGametest"` (slow; see `docs/gametest/INDEX.md`)
- Full verification before any final commit: `cmd.exe /c "./gradlew.bat :26.1:clientClasses :26.1:classes :26.1:gametestClasses :26.1:clientTestClasses :26.1:testClasses"`

Each task ends with the same commit pattern:
```
git add <files>
git commit -m "test(managed): <task summary>"
```

---

## Task 1: Extract shared gametest fixtures into `ManagedTestSupport.kt`

**Files:**
- Create: `src/gametest/kotlin/com/breadmoirai/redstonespecs/test/managed/ManagedTestSupport.kt`
- Modify: `src/gametest/kotlin/com/breadmoirai/redstonespecs/test/managed/ManagedDimSpec.kt` (replace private helpers with imports)

- [ ] **Step 1.1: Create `ManagedTestSupport.kt`**

```kotlin
package com.breadmoirai.redstonespecs.test.managed

import com.breadmoirai.redstonespecs.runner.RecordingDslEmitter
import com.mojang.authlib.GameProfile
import io.netty.channel.embedded.EmbeddedChannel
import net.minecraft.core.BlockPos
import net.minecraft.core.Vec3i
import net.minecraft.network.Connection
import net.minecraft.network.protocol.PacketFlow
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.server.network.CommonListenerCookie
import net.minecraft.world.level.GameType
import net.minecraft.world.level.block.Blocks
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.io.path.exists
import kotlin.io.path.writeText

/**
 * Builds a "mock" [ServerPlayer] in the server's overworld, mirroring vanilla
 * `GameTestHelper#makeMockServerPlayerInLevel`. Tied to a unique UUID so concurrent tests
 * don't collide on `ManagedSession`. The connection uses an [EmbeddedChannel] so packet
 * sends are no-ops.
 *
 * Must be called on the server thread.
 */
fun makeMockServerPlayer(server: MinecraftServer): ServerPlayer {
    require(server.isSameThread) { "makeMockServerPlayer must be called on the server thread" }
    val profile = GameProfile(UUID.randomUUID(), "test-managed-${UUID.randomUUID().toString().take(6)}")
    val cookie = CommonListenerCookie.createInitial(profile, false)
    val player = object : ServerPlayer(server, server.overworld(), cookie.gameProfile(), cookie.clientInformation()) {
        override fun gameMode(): GameType = GameType.CREATIVE
    }
    val connection = Connection(PacketFlow.SERVERBOUND)
    EmbeddedChannel(connection)
    server.playerList.placeNewPlayer(connection, player, cookie)
    return player
}

fun deleteRecursively(path: Path) {
    if (!path.exists()) return
    Files.walk(path).use { stream ->
        stream.sorted(Comparator.reverseOrder()).forEach { p -> runCatching { Files.delete(p) } }
    }
}

inline fun withTempRoot(prefix: String, block: (Path) -> Unit) {
    val tmp = Files.createTempDirectory(prefix)
    try {
        block(tmp)
    } finally {
        deleteRecursively(tmp)
    }
}

fun writeStub(folder: Path, name: String) {
    folder.resolve("$name.spec.kts").writeText(RecordingDslEmitter.emitStub(name))
}

/** Sets every block in `[origin, origin+size)` to AIR. */
fun clearCellVolume(level: ServerLevel, origin: BlockPos, size: Vec3i) {
    val air = Blocks.AIR.defaultBlockState()
    val end = origin.offset(size.x - 1, size.y - 1, size.z - 1)
    for (pos in BlockPos.betweenClosed(origin, end)) {
        level.setBlock(pos, air, 2)
    }
}
```

- [ ] **Step 1.2: Update `ManagedDimSpec.kt` to use the shared fixtures**

Remove the private `makeMockServerPlayer` and `deleteRecursively` at the bottom of the file. Replace the file's three `try { ... } finally { deleteRecursively(tmp) }` blocks with `withTempRoot("managed-...") { tmp -> ... }` (the existing tests). Keep test bodies otherwise unchanged.

The top of the file's import block should now also import:
```kotlin
import com.breadmoirai.redstonespecs.test.managed.makeMockServerPlayer
import com.breadmoirai.redstonespecs.test.managed.deleteRecursively
import com.breadmoirai.redstonespecs.test.managed.withTempRoot
```

- [ ] **Step 1.3: Verify compile**

Run: `cmd.exe /c "./gradlew.bat :26.1:compileGametestKotlin"`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 1.4: Commit**

```
git add src/gametest/kotlin/com/breadmoirai/redstonespecs/test/managed/ManagedTestSupport.kt \
        src/gametest/kotlin/com/breadmoirai/redstonespecs/test/managed/ManagedDimSpec.kt
git commit -m "test(managed): extract shared gametest fixtures into ManagedTestSupport"
```

---

## Task 2: Refactor `ManagedNetworkRegistry` handler bodies

Each `registerGlobalReceiver` lambda body becomes an `internal` `handleX` function. Lambdas are reduced to `ctx.server().execute { handleX(...) }`.

**Files:**
- Modify: `src/main/kotlin/com/breadmoirai/redstonespecs/network/managed/ManagedNetworkRegistry.kt`

- [ ] **Step 2.1: Extract handlers**

Replace the body of `register()` so that the receiver lambdas only schedule a call to handler functions. Add the handler functions as `internal` members of `ManagedNetworkRegistry`. Keep `rootFor`, `sendTree`, and `formatSaveResult` as-is (they're already private helpers; mark `formatSaveResult` and `sendTree` `internal` if tests need them — `sendTree` is now wrapped by `handleListTree` which is `internal`).

Final shape:
```kotlin
fun register() {
    PayloadTypeRegistry.serverboundPlay().register(ListManagedTreeC2S.TYPE, ListManagedTreeC2S.STREAM_CODEC)
    // ... (all 8 PayloadTypeRegistry registrations unchanged)

    ServerPlayNetworking.registerGlobalReceiver(ListManagedTreeC2S.TYPE) { _, ctx ->
        ctx.server().execute { handleListTree(ctx.server(), ctx.player()) }
    }
    ServerPlayNetworking.registerGlobalReceiver(LoadManagedFolderC2S.TYPE) { payload, ctx ->
        ctx.server().execute { handleLoadFolder(ctx.server(), ctx.player(), payload) }
    }
    ServerPlayNetworking.registerGlobalReceiver(UnloadManagedFolderC2S.TYPE) { _, ctx ->
        ctx.server().execute { handleUnload(ctx.server(), ctx.player()) }
    }
    ServerPlayNetworking.registerGlobalReceiver(SaveNowC2S.TYPE) { _, ctx ->
        ctx.server().execute { handleSaveNow(ctx.server(), ctx.player()) }
    }
    ServerPlayNetworking.registerGlobalReceiver(NewManagedSpecC2S.TYPE) { payload, ctx ->
        ctx.server().execute { handleNewSpec(ctx.server(), ctx.player(), payload) }
    }
}

internal fun handleListTree(server: MinecraftServer, player: ServerPlayer) {
    sendTree(server, player)
}

internal fun handleLoadFolder(server: MinecraftServer, player: ServerPlayer, payload: LoadManagedFolderC2S) {
    val root = rootFor(server) ?: run {
        ServerPlayNetworking.send(player, ManagedErrorS2C("managed-root not configured")); return
    }
    if (root.resolveSubpath(payload.subpath) == null) {
        ServerPlayNetworking.send(player, ManagedErrorS2C("subpath not found or escapes root: ${payload.subpath}")); return
    }
    val ok = ManagedTeleport.toFolder(server, player, payload.subpath)
    if (!ok) {
        ServerPlayNetworking.send(player, ManagedErrorS2C("folder not placed: ${payload.subpath}")); return
    }
    val world = ManagedWorld.get(server)
    val loadedIds = world?.perFolder?.get(payload.subpath)?.keys?.toList().orEmpty()
    ServerPlayNetworking.send(player, ManagedFolderLoadedS2C(
        subpath = payload.subpath,
        loadedSpecIds = loadedIds,
        parseErrors = emptyList(),
        layoutErrors = emptyList(),
    ))
}

internal fun handleUnload(server: MinecraftServer, player: ServerPlayer) {
    ManagedSession.clear(player.uuid)
    ServerPlayNetworking.send(player, ManagedSaveReportS2C(emptyList()))
}

internal fun handleSaveNow(server: MinecraftServer, player: ServerPlayer) {
    val results = ManagedDimLifecycle.saveAll(server)
    ServerPlayNetworking.send(player, ManagedSaveReportS2C(results.map(::formatSaveResult)))
}

internal fun handleNewSpec(server: MinecraftServer, player: ServerPlayer, payload: NewManagedSpecC2S) {
    val activeSubpath = ManagedSession.get(player.uuid)?.activeSubpath ?: run {
        ServerPlayNetworking.send(player, ManagedErrorS2C("no folder selected")); return
    }
    val root = rootFor(server) ?: run {
        ServerPlayNetworking.send(player, ManagedErrorS2C("managed-root not configured")); return
    }
    val world = ManagedWorld.get(server)
    val folderAbsolute = world?.folderAbsoluteByPath?.get(activeSubpath)
        ?: root.resolveSubpath(activeSubpath)
        ?: run {
            ServerPlayNetworking.send(player, ManagedErrorS2C("active folder not resolvable: $activeSubpath")); return
        }
    try {
        ManagedNewSpec.create(folderAbsolute, payload.name)
    } catch (e: Exception) {
        LOGGER.error("[managed/new-spec] create {}/{}: {}", activeSubpath, payload.name, e.message, e)
        ServerPlayNetworking.send(player, ManagedErrorS2C("new-spec failed: ${e.message}")); return
    }
    val report = try {
        ManagedDimLifecycle.placeFolder(server, root, activeSubpath)
    } catch (e: Exception) {
        LOGGER.error("[managed/new-spec] re-place {}: {}", activeSubpath, e.message, e)
        ServerPlayNetworking.send(player, ManagedErrorS2C("re-place failed: ${e.message}")); return
    }
    ServerPlayNetworking.send(player, ManagedFolderLoadedS2C(
        subpath = report.subpath,
        loadedSpecIds = report.loaded,
        parseErrors = report.parseErrors.map { "${it.filename}: ${it.message}" },
        layoutErrors = report.errors.map { "${it.specId} (${it.filename}): ${it.reason}" },
    ))
}
```

- [ ] **Step 2.2: Verify compile**

Run: `cmd.exe /c "./gradlew.bat :26.1:compileKotlin"`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 2.3: Commit**

```
git add src/main/kotlin/com/breadmoirai/redstonespecs/network/managed/ManagedNetworkRegistry.kt
git commit -m "refactor(managed): extract network handlers into internal handleX functions"
```

---

## Task 3: `ManagedSaveNamingTest.kt` (unit)

**Files:**
- Create: `src/test/kotlin/com/breadmoirai/redstonespecs/managed/ManagedSaveNamingTest.kt`

- [ ] **Step 3.1: Write the test**

```kotlin
package com.breadmoirai.redstonespecs.managed

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldMatch
import io.kotest.matchers.string.shouldStartWith
import java.nio.file.Path

class ManagedSaveNamingTest : FunSpec({

    test("preserves alphanumeric tail and appends 8-hex hash") {
        val name = ManagedSaveNaming.saveName(Path.of("/foo/specs"))
        name shouldStartWith "managed-specs-"
        name shouldMatch Regex("managed-specs-[0-9a-f]{8}")
    }

    test("non-alphanumeric tail characters become underscores") {
        val name = ManagedSaveNaming.saveName(Path.of("/foo/my specs!"))
        name shouldStartWith "managed-my_specs_-"
    }

    test("preserves hyphens and underscores in tail") {
        val name = ManagedSaveNaming.saveName(Path.of("/foo/my-cool_specs"))
        name shouldStartWith "managed-my-cool_specs-"
    }

    test("blank tail falls back to 'root'") {
        // The root path "/" has fileName == null.
        val name = ManagedSaveNaming.saveName(Path.of("/"))
        name shouldStartWith "managed-root-"
    }

    test("same tail at different absolute paths produce different hashes") {
        val n1 = ManagedSaveNaming.saveName(Path.of("/a/specs"))
        val n2 = ManagedSaveNaming.saveName(Path.of("/b/specs"))
        n1 shouldStartWith "managed-specs-"
        n2 shouldStartWith "managed-specs-"
        (n1 == n2) shouldBe false
    }

    test("same absolute path produces same name across calls") {
        val p = Path.of("/foo/specs")
        ManagedSaveNaming.saveName(p) shouldBe ManagedSaveNaming.saveName(p)
    }

    test("hash suffix is exactly 8 hex chars") {
        val name = ManagedSaveNaming.saveName(Path.of("/x/y/z"))
        val hash = name.substringAfterLast('-')
        hash shouldMatch Regex("[0-9a-f]{8}")
    }
})
```

- [ ] **Step 3.2: Run**

Run: `cmd.exe /c "./gradlew.bat :26.1:test --tests 'com.breadmoirai.redstonespecs.managed.ManagedSaveNamingTest'"`
Expected: all 7 tests pass.

- [ ] **Step 3.3: Commit**

```
git add src/test/kotlin/com/breadmoirai/redstonespecs/managed/ManagedSaveNamingTest.kt
git commit -m "test(managed): cover ManagedSaveNaming sanitization and hash suffix"
```

---

## Task 4: `ManagedCellTest.kt` (unit)

**Files:**
- Create: `src/test/kotlin/com/breadmoirai/redstonespecs/managed/ManagedCellTest.kt`

- [ ] **Step 4.1: Write the test**

```kotlin
package com.breadmoirai.redstonespecs.managed

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import net.minecraft.core.BlockPos
import net.minecraft.core.Vec3i

class ManagedCellTest : FunSpec({

    test("data class equality by component") {
        val a = ManagedCell("id", BlockPos(1, 2, 3), Vec3i(5, 5, 5), "id.spec.kts")
        val b = ManagedCell("id", BlockPos(1, 2, 3), Vec3i(5, 5, 5), "id.spec.kts")
        a shouldBe b
    }

    test("copy preserves untouched fields") {
        val a = ManagedCell("id", BlockPos(1, 2, 3), Vec3i(5, 5, 5), "id.spec.kts")
        val b = a.copy(origin = BlockPos(10, 2, 3))
        b.specId shouldBe "id"
        b.cellSize shouldBe Vec3i(5, 5, 5)
        b.sourceFile shouldBe "id.spec.kts"
        b.origin shouldBe BlockPos(10, 2, 3)
    }
})
```

- [ ] **Step 4.2: Run + commit**

Run: `cmd.exe /c "./gradlew.bat :26.1:test --tests 'com.breadmoirai.redstonespecs.managed.ManagedCellTest'"`
Expected: 2 tests pass.

```
git add src/test/kotlin/com/breadmoirai/redstonespecs/managed/ManagedCellTest.kt
git commit -m "test(managed): cover ManagedCell equality and copy"
```

---

## Task 5: `LoadedSpecTest.kt` (unit)

**Files:**
- Create: `src/test/kotlin/com/breadmoirai/redstonespecs/managed/LoadedSpecTest.kt`

- [ ] **Step 5.1: Write the test**

```kotlin
package com.breadmoirai.redstonespecs.managed

import com.breadmoirai.redstonespecs.dsl.RedstoneSpec
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import net.minecraft.core.BlockPos
import net.minecraft.core.Vec3i
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate
import java.nio.file.Path

class LoadedSpecTest : FunSpec({

    test("copy with refreshed snapshot preserves other fields") {
        val cell = ManagedCell("id", BlockPos(0, 0, 0), Vec3i(5, 5, 5), "id.spec.kts")
        val spec = RedstoneSpec(id = "id", bounds = Vec3i(5, 5, 5), lifespan = 20, structure = null, strict = false, block = {})
        val source = Path.of("/tmp/id.spec.kts")
        val snap1 = StructureTemplate()
        val snap2 = StructureTemplate()
        val a = LoadedSpec(cell, spec, source, snap1)
        val b = a.copy(loadedSnapshot = snap2)
        b.cell shouldBe cell
        b.spec shouldBe spec
        b.sourceFile shouldBe source
        (b.loadedSnapshot === snap2) shouldBe true
    }
})
```

- [ ] **Step 5.2: Run + commit**

Run: `cmd.exe /c "./gradlew.bat :26.1:test --tests 'com.breadmoirai.redstonespecs.managed.LoadedSpecTest'"`
Expected: 1 test passes.

```
git add src/test/kotlin/com/breadmoirai/redstonespecs/managed/LoadedSpecTest.kt
git commit -m "test(managed): cover LoadedSpec.copy field preservation"
```

---

## Task 6: `ManagedSessionTest.kt` (unit)

**Files:**
- Create: `src/test/kotlin/com/breadmoirai/redstonespecs/managed/ManagedSessionTest.kt`

> Note: `ManagedSession.sessions` is a process-global `ConcurrentHashMap`. Each test must use fresh UUIDs and clean up the UUIDs it creates.

- [ ] **Step 6.1: Write the test**

```kotlin
package com.breadmoirai.redstonespecs.managed

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import java.util.UUID

class ManagedSessionTest : FunSpec({

    test("setActive upserts and overwrites prior session for same UUID") {
        val u = UUID.randomUUID()
        try {
            ManagedSession.setActive(u, "folder/a")
            ManagedSession.get(u)?.activeSubpath shouldBe "folder/a"
            ManagedSession.setActive(u, "folder/b")
            ManagedSession.get(u)?.activeSubpath shouldBe "folder/b"
        } finally {
            ManagedSession.clear(u)
        }
    }

    test("setActive(null) records a session with null subpath") {
        val u = UUID.randomUUID()
        try {
            ManagedSession.setActive(u, null)
            val s = ManagedSession.get(u)
            s.shouldBe(ManagedSession(u, null))
        } finally {
            ManagedSession.clear(u)
        }
    }

    test("get returns null after clear") {
        val u = UUID.randomUUID()
        ManagedSession.setActive(u, "x")
        ManagedSession.clear(u)
        ManagedSession.get(u).shouldBeNull()
    }

    test("set stores a constructed session") {
        val u = UUID.randomUUID()
        try {
            ManagedSession.set(ManagedSession(u, "x"))
            ManagedSession.get(u)?.activeSubpath shouldBe "x"
        } finally {
            ManagedSession.clear(u)
        }
    }

    test("all() reflects every active session") {
        val u1 = UUID.randomUUID()
        val u2 = UUID.randomUUID()
        try {
            ManagedSession.setActive(u1, "a")
            ManagedSession.setActive(u2, "b")
            val ids = ManagedSession.all().map { it.playerId }.toSet()
            ids shouldContain u1
            ids shouldContain u2
        } finally {
            ManagedSession.clear(u1)
            ManagedSession.clear(u2)
        }
        ManagedSession.all().map { it.playerId }.toSet().also {
            it shouldNotContain u1
            it shouldNotContain u2
        }
    }

    test("UUIDs are isolated") {
        val u1 = UUID.randomUUID()
        val u2 = UUID.randomUUID()
        try {
            ManagedSession.setActive(u1, "a")
            ManagedSession.get(u2).shouldBeNull()
            ManagedSession.get(u1)?.activeSubpath shouldBe "a"
        } finally {
            ManagedSession.clear(u1)
            ManagedSession.clear(u2)
        }
    }
})
```

- [ ] **Step 6.2: Run + commit**

Run: `cmd.exe /c "./gradlew.bat :26.1:test --tests 'com.breadmoirai.redstonespecs.managed.ManagedSessionTest'"`
Expected: 6 tests pass.

```
git add src/test/kotlin/com/breadmoirai/redstonespecs/managed/ManagedSessionTest.kt
git commit -m "test(managed): cover ManagedSession set/get/clear/all"
```

---

## Task 7: `ManagedNewSpecTest.kt` (unit)

**Files:**
- Create: `src/test/kotlin/com/breadmoirai/redstonespecs/managed/ManagedNewSpecTest.kt`

- [ ] **Step 7.1: Write the test**

```kotlin
package com.breadmoirai.redstonespecs.managed

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Files
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

class ManagedNewSpecTest : FunSpec({

    test("blank name throws IllegalArgumentException") {
        val tmp = Files.createTempDirectory("managed-new-blank")
        try {
            shouldThrow<IllegalArgumentException> { ManagedNewSpec.create(tmp, "") }
            shouldThrow<IllegalArgumentException> { ManagedNewSpec.create(tmp, "   ") }
        } finally {
            Files.walk(tmp).use { it.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        }
    }

    test("illegal characters in name throw") {
        val tmp = Files.createTempDirectory("managed-new-illegal")
        try {
            shouldThrow<IllegalArgumentException> { ManagedNewSpec.create(tmp, "with space") }
            shouldThrow<IllegalArgumentException> { ManagedNewSpec.create(tmp, "a/b") }
            shouldThrow<IllegalArgumentException> { ManagedNewSpec.create(tmp, "dot.name") }
        } finally {
            Files.walk(tmp).use { it.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        }
    }

    test("file already exists throws") {
        val tmp = Files.createTempDirectory("managed-new-exists")
        try {
            tmp.resolve("dup.spec.kts").writeText("// stub")
            val ex = shouldThrow<IllegalArgumentException> { ManagedNewSpec.create(tmp, "dup") }
            ex.message?.shouldContain("already exists")
        } finally {
            Files.walk(tmp).use { it.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        }
    }

    test("create writes <name>.spec.kts with stub content") {
        val tmp = Files.createTempDirectory("managed-new-ok")
        try {
            val out = ManagedNewSpec.create(tmp, "fresh")
            out shouldBe tmp.resolve("fresh.spec.kts")
            out.exists() shouldBe true
            // Stub from RecordingDslEmitter.emitStub mentions the spec id.
            out.readText().shouldContain("fresh")
        } finally {
            Files.walk(tmp).use { it.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        }
    }
})
```

- [ ] **Step 7.2: Run + commit**

Run: `cmd.exe /c "./gradlew.bat :26.1:test --tests 'com.breadmoirai.redstonespecs.managed.ManagedNewSpecTest'"`
Expected: 4 tests pass.

```
git add src/test/kotlin/com/breadmoirai/redstonespecs/managed/ManagedNewSpecTest.kt
git commit -m "test(managed): cover ManagedNewSpec validation and stub write"
```

---

## Task 8: `ManagedServerContextTest.kt` (unit)

> The internal storage is a `WeakHashMap<MinecraftServer, ManagedServerContext>`. Tests use raw `Any` instances unsafe-cast — but Kotlin's type system prevents that without a real `MinecraftServer`. **Resolution:** use a `Mockito` stub, OR drop this test file since the storage logic is too thin to justify it. **Decision for the plan:** drop the file. `ManagedServerContext` is 4 lines (set/get/clear over a WeakHashMap) — same shape as `ManagedWorld`'s static map, which is also untested at the unit level. The cost of mocking a `MinecraftServer` to test this triviality outweighs the benefit. Coverage for context behavior is implicitly exercised through `ManagedNetworkRegistrySpec`'s `handleLoadFolder` test (root resolves via `ManagedServerContext`).

- [ ] **Step 8.1: No-op task**

This task is intentionally empty after analysis. Skip to Task 9.

---

## Task 9: `ManagedDimRegistryTest.kt` — region math (unit, optional)

`ManagedDimRegistry`'s constructor takes a `MinecraftServer`. The region-math methods (`getOrAssignRegion`, `regionOriginOf`, `computeRegionOrigin`) don't actually touch the server — but `companion of(server)` does. **Implementation choice:** instantiate via the public constructor, passing a Mockito-mocked `MinecraftServer`. If Mockito isn't already a dependency, fall back to gametest coverage (Task 14 covers region uniqueness & idempotency).

- [ ] **Step 9.1: Check existing test dependencies**

Run: `grep -r "mockito\|mockk" build.gradle*` (use Bash). If neither is present in the test classpath, **skip this task**; region math is exercised in Task 14's gametest. Mark this task complete and move on.

- [ ] **Step 9.2 (only if a mocking lib is present): write the test**

```kotlin
package com.breadmoirai.redstonespecs.managed

import com.breadmoirai.redstonespecs.config.SharedSettings
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import net.minecraft.server.MinecraftServer
import org.mockito.kotlin.mock // adapt to whichever is on the classpath

class ManagedDimRegistryTest : FunSpec({

    test("getOrAssignRegion is idempotent for same subpath") {
        val server = mock<MinecraftServer>()
        val r = ManagedDimRegistry(server)
        val a1 = r.getOrAssignRegion("set/a")
        val a2 = r.getOrAssignRegion("set/a")
        a1 shouldBe a2
    }

    test("distinct subpaths get distinct origins") {
        val server = mock<MinecraftServer>()
        val r = ManagedDimRegistry(server)
        val a = r.getOrAssignRegion("set/a")
        val b = r.getOrAssignRegion("set/b")
        (a == b) shouldBe false
    }

    test("regionOriginOf returns null for unassigned subpath") {
        val server = mock<MinecraftServer>()
        ManagedDimRegistry(server).regionOriginOf("never").shouldBeNull()
    }

    test("region width matches cellSize.x * rowMax + gap*(rowMax+1) + REGION_PAD") {
        val server = mock<MinecraftServer>()
        val r = ManagedDimRegistry(server)
        val a = r.getOrAssignRegion("a")  // index 0
        val b = r.getOrAssignRegion("b")  // index 1
        val cs = SharedSettings.managedCellSize
        val gap = SharedSettings.managedCellGap
        val rowMax = SharedSettings.managedRowMax
        val expectedWidth = cs.x * rowMax + gap * (rowMax + 1) + ManagedDimRegistry.REGION_PAD
        (b.x - a.x) shouldBe expectedWidth
        b.z shouldBe a.z
    }
})
```

- [ ] **Step 9.3: Run + commit (only if Step 9.2 was executed)**

Run: `cmd.exe /c "./gradlew.bat :26.1:test --tests 'com.breadmoirai.redstonespecs.managed.ManagedDimRegistryTest'"`
Expected: 4 tests pass.

```
git add src/test/kotlin/com/breadmoirai/redstonespecs/managed/ManagedDimRegistryTest.kt
git commit -m "test(managed): cover ManagedDimRegistry region math"
```

---

## Task 10: Capture-channel helper for network tests

Network gametests need to read `ManagedErrorS2C` / `ManagedFolderLoadedS2C` / etc. that handlers send via `ServerPlayNetworking.send(player, payload)`. These go through the player's `Connection`, which is backed by `EmbeddedChannel` in our mock player. The channel records outbound messages.

**Files:**
- Modify: `src/gametest/kotlin/com/breadmoirai/redstonespecs/test/managed/ManagedTestSupport.kt`

- [ ] **Step 10.1: Add a capture helper**

Add to `ManagedTestSupport.kt`:

```kotlin
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.server.level.ServerPlayer

/**
 * Reads all `CustomPacketPayload`s sent to `player` since the last call.
 * Walks the EmbeddedChannel outbound queue and unwraps `ClientboundCustomPayloadPacket`.
 */
fun drainPayloads(player: ServerPlayer): List<CustomPacketPayload> {
    val ch = (player.connection?.connection?.channel as? io.netty.channel.embedded.EmbeddedChannel)
        ?: return emptyList()
    val out = mutableListOf<CustomPacketPayload>()
    while (true) {
        val msg = ch.readOutbound<Any>() ?: break
        if (msg is ClientboundCustomPayloadPacket) out.add(msg.payload())
    }
    return out
}
```

> **Implementation note:** the exact accessor for the player's channel may differ (`player.connection.connection.channel`). If the property names don't match in MC 26.1, locate them with `grep -rn "EmbeddedChannel" src/gametest src/main` and adjust before continuing. The `ServerGamePacketListenerImpl` typically exposes the underlying `Connection` via `connection`.

- [ ] **Step 10.2: Verify compile**

Run: `cmd.exe /c "./gradlew.bat :26.1:compileGametestKotlin"`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 10.3: Commit**

```
git add src/gametest/kotlin/com/breadmoirai/redstonespecs/test/managed/ManagedTestSupport.kt
git commit -m "test(managed): add drainPayloads helper for capturing S2C in tests"
```

---

## Task 11: `ManagedCellSaverSpec.kt` (gametest)

**Files:**
- Create: `src/gametest/kotlin/com/breadmoirai/redstonespecs/test/managed/ManagedCellSaverSpec.kt`

- [ ] **Step 11.1: Write the test**

```kotlin
package com.breadmoirai.redstonespecs.test.managed

import com.breadmoirai.redstonespecs.managed.ManagedDimLifecycle
import com.breadmoirai.redstonespecs.managed.ManagedDimRegistry
import com.breadmoirai.redstonespecs.managed.ManagedRoot
import com.breadmoirai.redstonespecs.managed.ManagedWorld
import com.breadmoirai.redstonespecs.testing.RedstoneTestSpec
import com.breadmoirai.redstonespecs.testing.server.onServer
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import net.minecraft.core.Vec3i
import net.minecraft.world.level.block.Blocks
import kotlin.io.path.createDirectories
import kotlin.io.path.exists

class ManagedCellSaverSpec : RedstoneTestSpec({

    test("no mutation -> not saved") {
        withTempRoot("managed-saver-clean") { tmp ->
            val folder = tmp.resolve("set").also { it.createDirectories() }
            writeStub(folder, "a")

            val results = onServer {
                makeMockServerPlayer(this)
                val root = ManagedRoot(tmp)
                ManagedDimLifecycle.placeAll(this, root)
                // Re-place to baseline the snapshot deterministically.
                val world = ManagedWorld.get(this).shouldNotBeNull()
                val abs = world.absoluteCellOrigin(this, "set", "a").shouldNotBeNull()
                val level = ManagedDimRegistry.of(this).managedLevel().shouldNotBeNull()
                val bounds = world.perFolder["set"]!!["a"]!!.spec.bounds
                clearCellVolume(level, abs, bounds)
                ManagedDimLifecycle.placeFolder(this, root, "set")

                val r = ManagedDimLifecycle.saveFolder(this, "set")
                ManagedWorld.clear(this)
                r
            }

            results.single { it.specId == "a" }.saved shouldBe false
        }
    }

    test("mutation inside cell -> saved and .nbt written") {
        withTempRoot("managed-saver-dirty") { tmp ->
            val folder = tmp.resolve("set").also { it.createDirectories() }
            writeStub(folder, "a")

            val results = onServer {
                makeMockServerPlayer(this)
                val root = ManagedRoot(tmp)
                ManagedDimLifecycle.placeAll(this, root)
                val world = ManagedWorld.get(this).shouldNotBeNull()
                val abs = world.absoluteCellOrigin(this, "set", "a").shouldNotBeNull()
                val level = ManagedDimRegistry.of(this).managedLevel().shouldNotBeNull()
                val bounds = world.perFolder["set"]!!["a"]!!.spec.bounds
                clearCellVolume(level, abs, bounds)
                ManagedDimLifecycle.placeFolder(this, root, "set")

                level.setBlock(abs.offset(1, 1, 1), Blocks.GOLD_BLOCK.defaultBlockState(), 2)
                val r = ManagedDimLifecycle.saveFolder(this, "set")
                ManagedWorld.clear(this)
                r
            }

            val r = results.single { it.specId == "a" }
            r.saved shouldBe true
            // Stub spec id == structure id "a" → folder/a.nbt should exist.
            tmp.resolve("set/a.nbt").exists() shouldBe true
        }
    }

    test("mutation outside cell bounds -> not saved") {
        withTempRoot("managed-saver-outside") { tmp ->
            val folder = tmp.resolve("set").also { it.createDirectories() }
            writeStub(folder, "a")

            val results = onServer {
                makeMockServerPlayer(this)
                val root = ManagedRoot(tmp)
                ManagedDimLifecycle.placeAll(this, root)
                val world = ManagedWorld.get(this).shouldNotBeNull()
                val abs = world.absoluteCellOrigin(this, "set", "a").shouldNotBeNull()
                val level = ManagedDimRegistry.of(this).managedLevel().shouldNotBeNull()
                val bounds = world.perFolder["set"]!!["a"]!!.spec.bounds
                clearCellVolume(level, abs, bounds)
                ManagedDimLifecycle.placeFolder(this, root, "set")

                // 5 blocks beyond the +X face of the AABB — outside the cell volume.
                level.setBlock(abs.offset(bounds.x + 5, 0, 0), Blocks.GOLD_BLOCK.defaultBlockState(), 2)
                val r = ManagedDimLifecycle.saveFolder(this, "set")
                ManagedWorld.clear(this)
                r
            }

            results.single { it.specId == "a" }.saved shouldBe false
        }
    }

    test("snapshot refresh: second saveFolder after a save returns saved=false") {
        withTempRoot("managed-saver-refresh") { tmp ->
            val folder = tmp.resolve("set").also { it.createDirectories() }
            writeStub(folder, "a")

            val (first, second) = onServer {
                makeMockServerPlayer(this)
                val root = ManagedRoot(tmp)
                ManagedDimLifecycle.placeAll(this, root)
                val world = ManagedWorld.get(this).shouldNotBeNull()
                val abs = world.absoluteCellOrigin(this, "set", "a").shouldNotBeNull()
                val level = ManagedDimRegistry.of(this).managedLevel().shouldNotBeNull()
                val bounds = world.perFolder["set"]!!["a"]!!.spec.bounds
                clearCellVolume(level, abs, bounds)
                ManagedDimLifecycle.placeFolder(this, root, "set")

                level.setBlock(abs.offset(1, 1, 1), Blocks.GOLD_BLOCK.defaultBlockState(), 2)
                val r1 = ManagedDimLifecycle.saveFolder(this, "set")
                val r2 = ManagedDimLifecycle.saveFolder(this, "set")
                ManagedWorld.clear(this)
                r1 to r2
            }

            first.single { it.specId == "a" }.saved shouldBe true
            second.single { it.specId == "a" }.saved shouldBe false
        }
    }
})
```

- [ ] **Step 11.2: Compile**

Run: `cmd.exe /c "./gradlew.bat :26.1:compileGametestKotlin"`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 11.3: Commit**

```
git add src/gametest/kotlin/com/breadmoirai/redstonespecs/test/managed/ManagedCellSaverSpec.kt
git commit -m "test(managed): cover ManagedCellSaver dirty diff scenarios"
```

---

## Task 12: `ManagedTeleportSpec.kt` (gametest)

**Files:**
- Create: `src/gametest/kotlin/com/breadmoirai/redstonespecs/test/managed/ManagedTeleportSpec.kt`

- [ ] **Step 12.1: Write the test**

```kotlin
package com.breadmoirai.redstonespecs.test.managed

import com.breadmoirai.redstonespecs.config.SharedSettings
import com.breadmoirai.redstonespecs.managed.ManagedDimLifecycle
import com.breadmoirai.redstonespecs.managed.ManagedDimRegistry
import com.breadmoirai.redstonespecs.managed.ManagedRoot
import com.breadmoirai.redstonespecs.managed.ManagedSession
import com.breadmoirai.redstonespecs.managed.ManagedTeleport
import com.breadmoirai.redstonespecs.managed.ManagedWorld
import com.breadmoirai.redstonespecs.testing.RedstoneTestSpec
import com.breadmoirai.redstonespecs.testing.server.onServer
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlin.io.path.createDirectories
import kotlin.math.abs

class ManagedTeleportSpec : RedstoneTestSpec({

    test("toFolder returns false for unknown subpath and does not change session") {
        withTempRoot("managed-tp-unknown") { _ ->
            onServer {
                val player = makeMockServerPlayer(this)
                ManagedSession.clear(player.uuid)
                val ok = ManagedTeleport.toFolder(this, player, "does/not/exist")
                ok shouldBe false
                ManagedSession.get(player.uuid).shouldBeNull()
            }
        }
    }

    test("toFolder teleports player to region and sets active subpath") {
        withTempRoot("managed-tp-known") { tmp ->
            val folder = tmp.resolve("set").also { it.createDirectories() }
            writeStub(folder, "a")
            onServer {
                val player = makeMockServerPlayer(this)
                ManagedSession.clear(player.uuid)
                ManagedDimLifecycle.placeAll(this, ManagedRoot(tmp))

                val region = ManagedDimRegistry.of(this).regionOriginOf("set").shouldNotBeNull()
                val ok = ManagedTeleport.toFolder(this, player, "set")
                ok shouldBe true

                (abs(player.x - (region.x + 0.5)) < 1e-6) shouldBe true
                (abs(player.z - (region.z + 0.5)) < 1e-6) shouldBe true
                player.y shouldBe (SharedSettings.managedGridYBase + 2).toDouble()
                ManagedSession.get(player.uuid)?.activeSubpath shouldBe "set"

                ManagedSession.clear(player.uuid)
                ManagedWorld.clear(this)
            }
        }
    }
})
```

- [ ] **Step 12.2: Compile + commit**

Run: `cmd.exe /c "./gradlew.bat :26.1:compileGametestKotlin"`
Expected: BUILD SUCCESSFUL.

```
git add src/gametest/kotlin/com/breadmoirai/redstonespecs/test/managed/ManagedTeleportSpec.kt
git commit -m "test(managed): cover ManagedTeleport.toFolder success and unknown-subpath"
```

---

## Task 13: `ManagedNetworkRegistrySpec.kt` (gametest)

Drives the `internal` `handleX` functions added in Task 2 directly. Asserts on `drainPayloads(player)`.

**Files:**
- Create: `src/gametest/kotlin/com/breadmoirai/redstonespecs/test/managed/ManagedNetworkRegistrySpec.kt`

- [ ] **Step 13.1: Write the test**

```kotlin
package com.breadmoirai.redstonespecs.test.managed

import com.breadmoirai.redstonespecs.managed.ManagedDimLifecycle
import com.breadmoirai.redstonespecs.managed.ManagedFolderTree
import com.breadmoirai.redstonespecs.managed.ManagedRoot
import com.breadmoirai.redstonespecs.managed.ManagedServerContext
import com.breadmoirai.redstonespecs.managed.ManagedSession
import com.breadmoirai.redstonespecs.managed.ManagedWorld
import com.breadmoirai.redstonespecs.network.managed.ListManagedTreeC2S
import com.breadmoirai.redstonespecs.network.managed.LoadManagedFolderC2S
import com.breadmoirai.redstonespecs.network.managed.ManagedErrorS2C
import com.breadmoirai.redstonespecs.network.managed.ManagedFolderLoadedS2C
import com.breadmoirai.redstonespecs.network.managed.ManagedNetworkRegistry
import com.breadmoirai.redstonespecs.network.managed.ManagedSaveReportS2C
import com.breadmoirai.redstonespecs.network.managed.ManagedTreeSnapshotS2C
import com.breadmoirai.redstonespecs.network.managed.NewManagedSpecC2S
import com.breadmoirai.redstonespecs.testing.RedstoneTestSpec
import com.breadmoirai.redstonespecs.testing.server.onServer
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlin.io.path.createDirectories
import kotlin.io.path.exists

class ManagedNetworkRegistrySpec : RedstoneTestSpec({

    test("handleLoadFolder rejects path traversal with ManagedErrorS2C") {
        withTempRoot("managed-net-traversal") { tmp ->
            val folder = tmp.resolve("set").also { it.createDirectories() }
            writeStub(folder, "a")
            onServer {
                val player = makeMockServerPlayer(this)
                ManagedServerContext.set(this, ManagedServerContext(ManagedRoot(tmp)))
                ManagedDimLifecycle.placeAll(this, ManagedRoot(tmp))
                drainPayloads(player) // discard any pre-existing payloads

                ManagedNetworkRegistry.handleLoadFolder(this, player, LoadManagedFolderC2S("../escape"))

                val payloads = drainPayloads(player)
                val err = payloads.filterIsInstance<ManagedErrorS2C>().single()
                err.reason.shouldContain("escapes root")

                ManagedSession.clear(player.uuid)
                ManagedWorld.clear(this)
                ManagedServerContext.clear(this)
            }
        }
    }

    test("handleLoadFolder happy path sends ManagedFolderLoadedS2C and sets session") {
        withTempRoot("managed-net-load-ok") { tmp ->
            val folder = tmp.resolve("set").also { it.createDirectories() }
            writeStub(folder, "a")
            writeStub(folder, "b")
            onServer {
                val player = makeMockServerPlayer(this)
                ManagedServerContext.set(this, ManagedServerContext(ManagedRoot(tmp)))
                ManagedDimLifecycle.placeAll(this, ManagedRoot(tmp))
                drainPayloads(player)

                ManagedNetworkRegistry.handleLoadFolder(this, player, LoadManagedFolderC2S("set"))

                val loaded = drainPayloads(player).filterIsInstance<ManagedFolderLoadedS2C>().single()
                loaded.subpath shouldBe "set"
                loaded.loadedSpecIds.toSet() shouldBe setOf("a", "b")
                ManagedSession.get(player.uuid)?.activeSubpath shouldBe "set"

                ManagedSession.clear(player.uuid)
                ManagedWorld.clear(this)
                ManagedServerContext.clear(this)
            }
        }
    }

    test("handleSaveNow returns formatted ManagedSaveReportS2C") {
        withTempRoot("managed-net-save") { tmp ->
            val folder = tmp.resolve("set").also { it.createDirectories() }
            writeStub(folder, "a")
            onServer {
                val player = makeMockServerPlayer(this)
                ManagedServerContext.set(this, ManagedServerContext(ManagedRoot(tmp)))
                val root = ManagedRoot(tmp)
                ManagedDimLifecycle.placeAll(this, root)
                val world = ManagedWorld.get(this).shouldNotBeNull()
                val abs = world.absoluteCellOrigin(this, "set", "a").shouldNotBeNull()
                val level = com.breadmoirai.redstonespecs.managed.ManagedDimRegistry.of(this).managedLevel()
                val bounds = world.perFolder["set"]!!["a"]!!.spec.bounds
                clearCellVolume(level, abs, bounds)
                ManagedDimLifecycle.placeFolder(this, root, "set")
                level.setBlock(abs.offset(1, 1, 1), net.minecraft.world.level.block.Blocks.GOLD_BLOCK.defaultBlockState(), 2)
                drainPayloads(player)

                ManagedNetworkRegistry.handleSaveNow(this, player)

                val report = drainPayloads(player).filterIsInstance<ManagedSaveReportS2C>().single()
                report.perSpec.any { it.startsWith("a|saved=true") } shouldBe true

                ManagedWorld.clear(this)
                ManagedServerContext.clear(this)
            }
        }
    }

    test("handleNewSpec without active session returns 'no folder selected'") {
        withTempRoot("managed-net-new-no-session") { tmp ->
            tmp.resolve("set").createDirectories()
            onServer {
                val player = makeMockServerPlayer(this)
                ManagedServerContext.set(this, ManagedServerContext(ManagedRoot(tmp)))
                ManagedSession.clear(player.uuid)
                drainPayloads(player)

                ManagedNetworkRegistry.handleNewSpec(this, player, NewManagedSpecC2S("fresh"))

                val err = drainPayloads(player).filterIsInstance<ManagedErrorS2C>().single()
                err.reason shouldBe "no folder selected"

                ManagedServerContext.clear(this)
            }
        }
    }

    test("handleNewSpec with active session creates file and sends ManagedFolderLoadedS2C") {
        withTempRoot("managed-net-new-ok") { tmp ->
            val folder = tmp.resolve("set").also { it.createDirectories() }
            onServer {
                val player = makeMockServerPlayer(this)
                ManagedServerContext.set(this, ManagedServerContext(ManagedRoot(tmp)))
                ManagedDimLifecycle.placeAll(this, ManagedRoot(tmp))
                ManagedSession.setActive(player.uuid, "set")
                drainPayloads(player)

                ManagedNetworkRegistry.handleNewSpec(this, player, NewManagedSpecC2S("fresh"))

                folder.resolve("fresh.spec.kts").exists() shouldBe true
                val loaded = drainPayloads(player).filterIsInstance<ManagedFolderLoadedS2C>().single()
                loaded.loadedSpecIds shouldContain "fresh"

                ManagedSession.clear(player.uuid)
                ManagedWorld.clear(this)
                ManagedServerContext.clear(this)
            }
        }
    }

    test("handleUnload clears session and sends empty save report") {
        withTempRoot("managed-net-unload") { tmp ->
            tmp.resolve("set").createDirectories()
            onServer {
                val player = makeMockServerPlayer(this)
                ManagedSession.setActive(player.uuid, "set")
                drainPayloads(player)

                ManagedNetworkRegistry.handleUnload(this, player)

                ManagedSession.get(player.uuid).let { it == null } shouldBe true
                val report = drainPayloads(player).filterIsInstance<ManagedSaveReportS2C>().single()
                report.perSpec shouldBe emptyList()
            }
        }
    }

    test("handleListTree sends snapshot matching ManagedFolderTree.scan") {
        withTempRoot("managed-net-list") { tmp ->
            tmp.resolve("set-a").createDirectories().also { writeStub(it, "x") }
            tmp.resolve("set-b").createDirectories().also { writeStub(it, "y"); writeStub(it, "z") }
            onServer {
                val player = makeMockServerPlayer(this)
                ManagedServerContext.set(this, ManagedServerContext(ManagedRoot(tmp)))
                drainPayloads(player)

                ManagedNetworkRegistry.handleListTree(this, player)

                val snap = drainPayloads(player).filterIsInstance<ManagedTreeSnapshotS2C>().single()
                val expected = ManagedFolderTree.scan(ManagedRoot(tmp))
                snap.leaves.map { it.subpath }.toSet() shouldBe expected.leaves.map { it.subpath }.toSet()
                snap.leaves.single { it.subpath == "set-b" }.specCount shouldBe 2

                ManagedServerContext.clear(this)
            }
        }
    }
})
```

> Note: `kotlin.io.path.createDirectories()` returns `Path`. The chained `.also { writeStub(it, "x") }` works.

- [ ] **Step 13.2: Compile + commit**

Run: `cmd.exe /c "./gradlew.bat :26.1:compileGametestKotlin"`
Expected: BUILD SUCCESSFUL.

```
git add src/gametest/kotlin/com/breadmoirai/redstonespecs/test/managed/ManagedNetworkRegistrySpec.kt
git commit -m "test(managed): cover ManagedNetworkRegistry handleX server-authority paths"
```

---

## Task 14: `ManagedCommandSpec.kt` (gametest)

**Files:**
- Create: `src/gametest/kotlin/com/breadmoirai/redstonespecs/test/managed/ManagedCommandSpec.kt`

- [ ] **Step 14.1: Write the test**

```kotlin
package com.breadmoirai.redstonespecs.test.managed

import com.breadmoirai.redstonespecs.config.SharedSettings
import com.breadmoirai.redstonespecs.managed.ManagedCommand
import com.breadmoirai.redstonespecs.managed.ManagedRoot
import com.breadmoirai.redstonespecs.managed.ManagedServerContext
import com.breadmoirai.redstonespecs.network.managed.ManagedTreeSnapshotS2C
import com.breadmoirai.redstonespecs.testing.RedstoneTestSpec
import com.breadmoirai.redstonespecs.testing.server.onServer
import com.mojang.brigadier.CommandDispatcher
import io.kotest.matchers.shouldBe
import net.minecraft.commands.CommandSourceStack
import kotlin.io.path.createDirectories

class ManagedCommandSpec : RedstoneTestSpec({

    test("/redstonespecs managed without root configured sends an error message") {
        // Save and restore the global SharedSettings.managedRootPath since the gametest server is shared.
        val prior = SharedSettings.managedRootPath
        SharedSettings.managedRootPath = ""
        try {
            onServer {
                val player = makeMockServerPlayer(this)
                ManagedServerContext.clear(this)
                val dispatcher = CommandDispatcher<CommandSourceStack>()
                ManagedCommand.register(dispatcher)

                val source = player.createCommandSourceStack()
                val rc = dispatcher.execute("redstonespecs managed", source)
                rc shouldBe 0
                // No tree snapshot should have been sent.
                val payloads = drainPayloads(player)
                payloads.filterIsInstance<ManagedTreeSnapshotS2C>().isEmpty() shouldBe true
            }
        } finally {
            SharedSettings.managedRootPath = prior
        }
    }

    test("/redstonespecs managed with context sends a ManagedTreeSnapshotS2C") {
        withTempRoot("managed-cmd-ok") { tmp ->
            val folder = tmp.resolve("set").also { it.createDirectories() }
            writeStub(folder, "a")
            onServer {
                val player = makeMockServerPlayer(this)
                ManagedServerContext.set(this, ManagedServerContext(ManagedRoot(tmp)))
                drainPayloads(player)
                val dispatcher = CommandDispatcher<CommandSourceStack>()
                ManagedCommand.register(dispatcher)

                val source = player.createCommandSourceStack()
                val rc = dispatcher.execute("redstonespecs managed", source)
                (rc > 0) shouldBe true

                val snap = drainPayloads(player).filterIsInstance<ManagedTreeSnapshotS2C>().single()
                snap.leaves.map { it.subpath } shouldBe listOf("set")

                ManagedServerContext.clear(this)
            }
        }
    }
})
```

> If `SharedSettings.managedRootPath` is a `val`, change the assignment lines to whatever the actual config setter is, or skip the no-root test variant. Confirm via `grep -n "managedRootPath" src/main/kotlin/com/breadmoirai/redstonespecs/config/SharedSettings.kt` before writing.

- [ ] **Step 14.2: Compile + commit**

Run: `cmd.exe /c "./gradlew.bat :26.1:compileGametestKotlin"`
Expected: BUILD SUCCESSFUL.

```
git add src/gametest/kotlin/com/breadmoirai/redstonespecs/test/managed/ManagedCommandSpec.kt
git commit -m "test(managed): cover ManagedCommand /redstonespecs managed dispatch"
```

---

## Task 15: Extend `ManagedDimSpec.kt` with lifecycle edge cases

**Files:**
- Modify: `src/gametest/kotlin/com/breadmoirai/redstonespecs/test/managed/ManagedDimSpec.kt`

Add these tests inside the existing `RedstoneTestSpec({ ... })` body:

- [ ] **Step 15.1: Add `placeFolder` for unknown subpath throws**

```kotlin
test("placeFolder for unknown subpath throws") {
    withTempRoot("managed-place-unknown") { tmp ->
        onServer {
            makeMockServerPlayer(this)
            try {
                ManagedDimLifecycle.placeFolder(this, ManagedRoot(tmp), "does/not/exist")
                error("expected throw")
            } catch (e: IllegalStateException) {
                e.message?.let { it.contains("subpath outside root") } shouldBe true
            }
            ManagedWorld.clear(this)
        }
    }
}
```

- [ ] **Step 15.2: Add `placeFolder` on empty leaf folder**

```kotlin
test("placeFolder on empty leaf folder reports zero loaded specs and no errors") {
    withTempRoot("managed-place-empty") { tmp ->
        val folder = tmp.resolve("empty").also { it.createDirectories() }
        val report = onServer {
            makeMockServerPlayer(this)
            ManagedDimLifecycle.placeAll(this, ManagedRoot(tmp))
            val r = ManagedDimLifecycle.placeFolder(this, ManagedRoot(tmp), "empty")
            ManagedWorld.clear(this)
            r
        }
        report.loaded.isEmpty() shouldBe true
        report.errors.isEmpty() shouldBe true
        report.parseErrors.isEmpty() shouldBe true
    }
}
```

- [ ] **Step 15.3: Add nested-folders test**

```kotlin
test("placeAll handles nested leaf folders with distinct region origins") {
    withTempRoot("managed-place-nested") { tmp ->
        tmp.resolve("alpha").createDirectories().also { writeStub(it, "x") }
        tmp.resolve("beta").createDirectories().also { writeStub(it, "y") }

        val (origins, reports) = onServer {
            makeMockServerPlayer(this)
            val rs = ManagedDimLifecycle.placeAll(this, ManagedRoot(tmp))
            val reg = ManagedDimRegistry.of(this)
            val map = mapOf(
                "alpha" to reg.regionOriginOf("alpha"),
                "beta" to reg.regionOriginOf("beta"),
            )
            ManagedWorld.clear(this)
            map to rs
        }
        reports.map { it.subpath }.toSet() shouldBe setOf("alpha", "beta")
        origins["alpha"].shouldNotBeNull()
        origins["beta"].shouldNotBeNull()
        (origins["alpha"] == origins["beta"]) shouldBe false
    }
}
```

- [ ] **Step 15.4: Add re-place after adding a spec**

```kotlin
test("re-place after adding a new spec keeps region origin and includes new spec") {
    withTempRoot("managed-replace") { tmp ->
        val folder = tmp.resolve("set").also { it.createDirectories() }
        writeStub(folder, "a")

        val (firstOrigin, secondOrigin, secondReport) = onServer {
            makeMockServerPlayer(this)
            val root = ManagedRoot(tmp)
            ManagedDimLifecycle.placeFolder(this, root, "set")
            val reg = ManagedDimRegistry.of(this)
            val o1 = reg.regionOriginOf("set")

            writeStub(folder, "b")
            val r2 = ManagedDimLifecycle.placeFolder(this, root, "set")
            val o2 = reg.regionOriginOf("set")
            ManagedWorld.clear(this)
            Triple(o1, o2, r2)
        }
        firstOrigin shouldBe secondOrigin
        secondReport.loaded.toSet() shouldBe setOf("a", "b")
    }
}
```

- [ ] **Step 15.5: Add parse-error test**

```kotlin
test("parse-error file is reported but does not block other specs in the folder") {
    withTempRoot("managed-place-parse-err") { tmp ->
        val folder = tmp.resolve("set").also { it.createDirectories() }
        writeStub(folder, "a")
        folder.resolve("bad.spec.kts").writeText("this is not valid kotlin <<<<")

        val report = onServer {
            makeMockServerPlayer(this)
            val r = ManagedDimLifecycle.placeFolder(this, ManagedRoot(tmp), "set")
            ManagedWorld.clear(this)
            r
        }
        report.loaded shouldContain "a"
        report.parseErrors.map { it.filename } shouldContain "bad.spec.kts"
    }
}
```

- [ ] **Step 15.6: Add `saveAll` aggregation test**

```kotlin
test("saveAll aggregates dirty cells across folders") {
    withTempRoot("managed-saveall") { tmp ->
        tmp.resolve("set-a").createDirectories().also { writeStub(it, "a") }
        tmp.resolve("set-b").createDirectories().also { writeStub(it, "b") }

        val results = onServer {
            makeMockServerPlayer(this)
            val root = ManagedRoot(tmp)
            ManagedDimLifecycle.placeAll(this, root)
            val world = ManagedWorld.get(this).shouldNotBeNull()
            val level = ManagedDimRegistry.of(this).managedLevel()
            for (sub in listOf("set-a", "set-b")) {
                val id = world.perFolder[sub]!!.keys.single()
                val abs = world.absoluteCellOrigin(this, sub, id).shouldNotBeNull()
                val bounds = world.perFolder[sub]!![id]!!.spec.bounds
                clearCellVolume(level, abs, bounds)
            }
            ManagedDimLifecycle.placeFolder(this, root, "set-a")
            ManagedDimLifecycle.placeFolder(this, root, "set-b")
            // Mutate one block in each cell.
            for (sub in listOf("set-a", "set-b")) {
                val id = world.perFolder[sub]!!.keys.single()
                val abs = world.absoluteCellOrigin(this, sub, id).shouldNotBeNull()
                level.setBlock(abs.offset(1, 1, 1), net.minecraft.world.level.block.Blocks.GOLD_BLOCK.defaultBlockState(), 2)
            }
            val r = ManagedDimLifecycle.saveAll(this)
            ManagedWorld.clear(this)
            r
        }
        results.size shouldBe 2
        results.all { it.saved } shouldBe true
    }
}
```

- [ ] **Step 15.7: Add region-stability test**

```kotlin
test("region origins are stable for prior subpaths when a new region is added") {
    withTempRoot("managed-region-stable") { tmp ->
        tmp.resolve("alpha").createDirectories().also { writeStub(it, "x") }
        tmp.resolve("beta").createDirectories().also { writeStub(it, "y") }

        val (firstAlpha, firstBeta, secondAlpha, secondBeta) = onServer {
            makeMockServerPlayer(this)
            val root = ManagedRoot(tmp)
            ManagedDimLifecycle.placeFolder(this, root, "alpha")
            ManagedDimLifecycle.placeFolder(this, root, "beta")
            val reg = ManagedDimRegistry.of(this)
            val a1 = reg.regionOriginOf("alpha")
            val b1 = reg.regionOriginOf("beta")
            // Add a third folder.
            tmp.resolve("gamma").createDirectories().also { writeStub(it, "z") }
            ManagedDimLifecycle.placeFolder(this, root, "gamma")
            val a2 = reg.regionOriginOf("alpha")
            val b2 = reg.regionOriginOf("beta")
            ManagedWorld.clear(this)
            arrayOf(a1, b1, a2, b2)
        }
        firstAlpha shouldBe secondAlpha
        firstBeta shouldBe secondBeta
    }
}
```

> Imports needed at the top of `ManagedDimSpec.kt` (add only the missing ones): `kotlin.io.path.writeText` for parse-error test. The other imports are already present.

- [ ] **Step 15.8: Compile + commit**

Run: `cmd.exe /c "./gradlew.bat :26.1:compileGametestKotlin"`
Expected: BUILD SUCCESSFUL.

```
git add src/gametest/kotlin/com/breadmoirai/redstonespecs/test/managed/ManagedDimSpec.kt
git commit -m "test(managed): extend ManagedDimSpec with 7 lifecycle edge cases"
```

---

## Task 16: Final verification

- [ ] **Step 16.1: Full multi-sourceset compile**

Run:
```
cmd.exe /c "./gradlew.bat :26.1:clientClasses :26.1:classes :26.1:gametestClasses :26.1:clientTestClasses :26.1:testClasses"
```
Expected: BUILD SUCCESSFUL across all 5 sourcesets.

- [ ] **Step 16.2: Run all unit tests**

Run: `cmd.exe /c "./gradlew.bat :26.1:test"`
Expected: BUILD SUCCESSFUL; the new tests in `managed/` show as passing.

- [ ] **Step 16.3: Run gametests (if convenient — slow)**

Run: `cmd.exe /c "./gradlew.bat :26.1:runGametest"`
Expected: BUILD SUCCESSFUL; new managed gametest classes pass.

- [ ] **Step 16.4: No commit needed if everything passes — work is done.**

If verification surfaces failures, file follow-up issues per failure rather than amending earlier commits. Each test file is self-contained and can be fixed independently.
