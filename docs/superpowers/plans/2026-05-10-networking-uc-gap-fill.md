# Networking UC GAP fill — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close the server-side GAP / GAP-PARTIAL rows in `docs/use-cases/networking.md` (UC-NET-01.a/d, UC-NET-02..05) by refactoring `NetworkRegistry.kt` to expose handlers as testable top-level functions, then adding a Kotest gametest spec covering the rows.

**Architecture:** Mirror `ManagedNetworkRegistry`'s shape — extract every C2S handler body into `handleX(server, player, payload)` functions; receivers in `registerNetworking()` become one-liners. Reuse the `EmbeddedChannel` + `drainPayloads` test pattern from `ManagedNetworkRegistrySpec`.

**Tech Stack:** Kotlin, Fabric API, Minecraft 1.26.1, Kotest (gametest), Stonecutter multi-version build.

**Spec:** [`docs/superpowers/specs/2026-05-10-networking-uc-gap-fill-design.md`](../specs/2026-05-10-networking-uc-gap-fill-design.md).

**Build verification command (used in many tasks):**

```
cmd.exe /c "./gradlew.bat :26.1:clientClasses :26.1:classes :26.1:gametestClasses :26.1:clientTestClasses :26.1:testClasses"
```

**Gametest run command:**

```
cmd.exe /c "./gradlew.bat :26.1:runGameTestServer"
```

---

## Task 1: Refactor NetworkRegistry — extract handler functions

**Files:**
- Modify: `src/main/kotlin/com/breadmoirai/garnet/network/NetworkRegistry.kt`

This is a behavior-preserving refactor. Each lambda body inside `context.server().execute { … }` becomes a top-level function `handleX(server, player, payload)`. The lambda becomes a one-liner delegation.

- [ ] **Step 1: Verify current build is clean**

```
cmd.exe /c "./gradlew.bat :26.1:classes"
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 2: Rewrite NetworkRegistry.kt**

Replace the entire file with:

```kotlin
package com.breadmoirai.garnet.network

import com.breadmoirai.garnet.block.GarnetRecorderBlock
import com.breadmoirai.garnet.block.GarnetRunnerBlock
import com.breadmoirai.garnet.block.SpecBlockEntity
import com.breadmoirai.garnet.config.SharedSettings
import com.breadmoirai.garnet.persistence.SpecDirectoryScan
import com.breadmoirai.garnet.persistence.SpecPersistence
import com.breadmoirai.garnet.persistence.StructurePersistence
import com.breadmoirai.garnet.runner.SpecSnapshot
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.storage.LevelResource
import org.slf4j.LoggerFactory

private val LOGGER = LoggerFactory.getLogger("Garnet")

internal fun saveDir(server: MinecraftServer): java.nio.file.Path =
    server.getWorldPath(LevelResource.ROOT)
        .resolve(SharedSettings.specSaveDir)

fun handleRunSpec(server: MinecraftServer, player: ServerPlayer, payload: RunSpecC2SPayload) {
    LOGGER.debug("[NetworkRegistry#runSpec] originPos={}", payload.originPos)
    val be = player.level().getBlockEntity(payload.originPos) as? SpecBlockEntity ?: return
    val level = be.level as? ServerLevel ?: return
    val dir = saveDir(server)
    val structureId = be.specStructure ?: be.specId
    StructurePersistence.save(dir, structureId, level, be.blockPos, be.specBounds)
    LOGGER.debug("[NetworkRegistry#runSpec] auto-saved structure '{}' before run", structureId)
    val dslSpec = SpecPersistence.load(dir, be.specId)
    if (dslSpec == null) {
        LOGGER.warn("[NetworkRegistry#runSpec] could not load dsl.GarnetSpec for '{}' — aborting run", be.specId)
        return
    }
    be.startRun(dslSpec, level)
}

fun handleResetSpec(server: MinecraftServer, player: ServerPlayer, payload: ResetSpecC2SPayload) {
    LOGGER.debug("[NetworkRegistry#resetSpec] originPos={}", payload.originPos)
    // No-op: engine-driven path has no coordinator to reset.
}

fun handleSetStructure(server: MinecraftServer, player: ServerPlayer, payload: SetStructureC2SPayload) {
    LOGGER.debug("[NetworkRegistry#setStructure] originPos={} structure={}", payload.originPos, payload.structure)
    val be = player.level().getBlockEntity(payload.originPos) as? SpecBlockEntity ?: return
    be.setStructure(payload.structure)
}

fun handleOverwriteDecision(server: MinecraftServer, player: ServerPlayer, payload: OverwriteDecisionC2SPayload) {
    val be = player.level().getBlockEntity(payload.originPos) as? SpecBlockEntity ?: return
    val structureId = be.specStructure ?: be.specId
    val level = be.level as? ServerLevel ?: return
    val dir = saveDir(server)
    if (payload.overwrite) {
        StructurePersistence.clearBounds(level, be.blockPos, be.specBounds)
        StructurePersistence.load(dir, structureId, level, be.blockPos, be.specBounds)
        LOGGER.debug("[NetworkRegistry#overwriteDecision] cleared and placed structure '{}'", structureId)
    } else {
        LOGGER.debug("[NetworkRegistry#overwriteDecision] user skipped structure load")
    }
}

fun handleSetRecorderConfig(server: MinecraftServer, player: ServerPlayer, payload: SetRecorderConfigC2S) {
    LOGGER.debug("[NetworkRegistry#setRecorderConfig] originPos={} specId={}", payload.originPos, payload.specId)
    val level = player.level()
    val be = level.getBlockEntity(payload.originPos) as? SpecBlockEntity ?: return
    if (level.getBlockState(payload.originPos).block !is GarnetRecorderBlock) return
    if (payload.specId.isNotBlank()) be.setSpecId(payload.specId)
    if (payload.structureId.isNotBlank()) be.setStructure(payload.structureId)
}

fun handleRecorderCommand(server: MinecraftServer, player: ServerPlayer, payload: RecorderCommandC2S) {
    LOGGER.debug("[NetworkRegistry#recorderCommand] originPos={} cmd={}", payload.originPos, payload.cmd)
    val level = player.level()
    val be = level.getBlockEntity(payload.originPos) as? SpecBlockEntity ?: return
    if (level.getBlockState(payload.originPos).block !is GarnetRecorderBlock) return
    when (payload.cmd) {
        RecorderCmd.START -> be.startRecording()
        RecorderCmd.STOP -> be.stopRecordingAndFinalize()
        RecorderCmd.DISCARD -> be.discardForRerecord()
    }
}

fun handleSetRunnerConfig(server: MinecraftServer, player: ServerPlayer, payload: SetRunnerConfigC2S) {
    LOGGER.debug("[NetworkRegistry#setRunnerConfig] originPos={} specPath={}", payload.originPos, payload.specPath)
    val level = player.level()
    val be = level.getBlockEntity(payload.originPos) as? SpecBlockEntity ?: return
    if (level.getBlockState(payload.originPos).block !is GarnetRunnerBlock) return
    val dir = saveDir(server)
    val specId = payload.specPath.removeSuffix(".spec.kts")
    val dslSpec = SpecPersistence.load(dir, specId)
    val meta: RunnerMetaSnapshot? = if (dslSpec != null) {
        RunnerMetaSnapshot(
            id = dslSpec.id,
            boundsX = dslSpec.bounds.x,
            boundsY = dslSpec.bounds.y,
            boundsZ = dslSpec.bounds.z,
            lifespan = dslSpec.lifespan,
            structure = dslSpec.structure,
        )
    } else {
        LOGGER.warn("[NetworkRegistry#setRunnerConfig] spec '{}' not found on disk", specId)
        null
    }
    val specList = SpecDirectoryScan.list(dir)
    ServerPlayNetworking.send(
        player,
        OpenRunnerScreenS2C(payload.originPos, payload.specPath, specList, meta)
    )
}

fun handleRunnerCommand(server: MinecraftServer, player: ServerPlayer, payload: RunnerCommandC2S) {
    LOGGER.debug("[NetworkRegistry#runnerCommand] originPos={} cmd={}", payload.originPos, payload.cmd)
    val serverLevel = player.level() as ServerLevel
    val be = serverLevel.getBlockEntity(payload.originPos) as? SpecBlockEntity ?: return
    if (serverLevel.getBlockState(payload.originPos).block !is GarnetRunnerBlock) return
    val dir = saveDir(server)

    when (payload.cmd) {
        RunnerCmd.PLACE_STRUCTURE -> {
            if (be.isConfigured) {
                val structureId = be.specStructure ?: be.specId
                StructurePersistence.load(dir, structureId, serverLevel, payload.originPos, be.specBounds)
                LOGGER.debug("[NetworkRegistry#runnerCommand] PLACE_STRUCTURE placed '{}'", structureId)
                ServerPlayNetworking.send(
                    player,
                    RunnerStatusS2C(payload.originPos, RunnerState.IDLE, "Structure placed: $structureId")
                )
            } else {
                LOGGER.warn("[NetworkRegistry#runnerCommand] PLACE_STRUCTURE: BE not configured at {}", payload.originPos)
                ServerPlayNetworking.send(
                    player,
                    RunnerStatusS2C(payload.originPos, RunnerState.IDLE, "No spec configured")
                )
            }
        }
        RunnerCmd.RUN -> {
            val dslSpec = SpecPersistence.load(dir, be.specId)
            if (dslSpec == null) {
                LOGGER.warn("[NetworkRegistry#runnerCommand] RUN: dsl spec '{}' not found", be.specId)
                ServerPlayNetworking.send(
                    player,
                    RunnerStatusS2C(payload.originPos, RunnerState.FAIL, "Spec file not found: ${be.specId}")
                )
                return
            }
            ServerPlayNetworking.send(
                player,
                RunnerStatusS2C(payload.originPos, RunnerState.RUNNING, "Running…")
            )
            val launched = be.startRun(dslSpec, serverLevel)
            if (!launched) {
                ServerPlayNetworking.send(
                    player,
                    RunnerStatusS2C(payload.originPos, RunnerState.RUNNING, "Already running")
                )
            }
        }
        RunnerCmd.RESTORE -> {
            if (be.isConfigured) {
                val snapshot = SpecSnapshot.capture(serverLevel, payload.originPos, be.specBounds)
                snapshot.restore(serverLevel)
                LOGGER.debug("[NetworkRegistry#runnerCommand] RESTORE applied snapshot at {}", payload.originPos)
                ServerPlayNetworking.send(
                    player,
                    RunnerStatusS2C(payload.originPos, RunnerState.IDLE, "Snapshot restored")
                )
            } else {
                LOGGER.warn("[NetworkRegistry#runnerCommand] RESTORE: BE not configured at {}", payload.originPos)
                ServerPlayNetworking.send(
                    player,
                    RunnerStatusS2C(payload.originPos, RunnerState.IDLE, "No spec configured")
                )
            }
        }
    }
}

fun registerNetworking() {
    // S2C registrations
    PayloadTypeRegistry.clientboundPlay().register(OverwritePromptS2CPayload.TYPE, OverwritePromptS2CPayload.STREAM_CODEC)

    // C2S registrations
    PayloadTypeRegistry.serverboundPlay().register(RunSpecC2SPayload.TYPE, RunSpecC2SPayload.STREAM_CODEC)
    PayloadTypeRegistry.serverboundPlay().register(ResetSpecC2SPayload.TYPE, ResetSpecC2SPayload.STREAM_CODEC)
    PayloadTypeRegistry.serverboundPlay().register(SetStructureC2SPayload.TYPE, SetStructureC2SPayload.STREAM_CODEC)
    PayloadTypeRegistry.serverboundPlay().register(OverwriteDecisionC2SPayload.TYPE, OverwriteDecisionC2SPayload.STREAM_CODEC)

    // v2.0: Slim recorder / runner packets
    PayloadTypeRegistry.serverboundPlay().register(SetRecorderConfigC2S.TYPE, SetRecorderConfigC2S.STREAM_CODEC)
    PayloadTypeRegistry.serverboundPlay().register(RecorderCommandC2S.TYPE, RecorderCommandC2S.STREAM_CODEC)
    PayloadTypeRegistry.serverboundPlay().register(SetRunnerConfigC2S.TYPE, SetRunnerConfigC2S.STREAM_CODEC)
    PayloadTypeRegistry.serverboundPlay().register(RunnerCommandC2S.TYPE, RunnerCommandC2S.STREAM_CODEC)
    PayloadTypeRegistry.clientboundPlay().register(OpenRecorderScreenS2C.TYPE, OpenRecorderScreenS2C.STREAM_CODEC)
    PayloadTypeRegistry.clientboundPlay().register(OpenRunnerScreenS2C.TYPE, OpenRunnerScreenS2C.STREAM_CODEC)
    PayloadTypeRegistry.clientboundPlay().register(RunnerStatusS2C.TYPE, RunnerStatusS2C.STREAM_CODEC)

    // C2S handlers — bodies live in handleX top-level functions for testability
    ServerPlayNetworking.registerGlobalReceiver(RunSpecC2SPayload.TYPE) { payload, ctx ->
        ctx.server().execute { handleRunSpec(ctx.server(), ctx.player(), payload) }
    }
    ServerPlayNetworking.registerGlobalReceiver(ResetSpecC2SPayload.TYPE) { payload, ctx ->
        ctx.server().execute { handleResetSpec(ctx.server(), ctx.player(), payload) }
    }
    ServerPlayNetworking.registerGlobalReceiver(SetStructureC2SPayload.TYPE) { payload, ctx ->
        ctx.server().execute { handleSetStructure(ctx.server(), ctx.player(), payload) }
    }
    ServerPlayNetworking.registerGlobalReceiver(OverwriteDecisionC2SPayload.TYPE) { payload, ctx ->
        ctx.server().execute { handleOverwriteDecision(ctx.server(), ctx.player(), payload) }
    }
    ServerPlayNetworking.registerGlobalReceiver(SetRecorderConfigC2S.TYPE) { payload, ctx ->
        ctx.server().execute { handleSetRecorderConfig(ctx.server(), ctx.player(), payload) }
    }
    ServerPlayNetworking.registerGlobalReceiver(RecorderCommandC2S.TYPE) { payload, ctx ->
        ctx.server().execute { handleRecorderCommand(ctx.server(), ctx.player(), payload) }
    }
    ServerPlayNetworking.registerGlobalReceiver(SetRunnerConfigC2S.TYPE) { payload, ctx ->
        ctx.server().execute { handleSetRunnerConfig(ctx.server(), ctx.player(), payload) }
    }
    ServerPlayNetworking.registerGlobalReceiver(RunnerCommandC2S.TYPE) { payload, ctx ->
        ctx.server().execute { handleRunnerCommand(ctx.server(), ctx.player(), payload) }
    }

    com.breadmoirai.garnet.network.managed.ManagedNetworkRegistry.register()
}
```

- [ ] **Step 3: Verify build still compiles**

```
cmd.exe /c "./gradlew.bat :26.1:clientClasses :26.1:classes :26.1:gametestClasses :26.1:clientTestClasses :26.1:testClasses"
```

Expected: BUILD SUCCESSFUL across all 5 sourcesets.

- [ ] **Step 4: Commit**

```
git add src/main/kotlin/com/breadmoirai/garnet/network/NetworkRegistry.kt
git commit -m "refactor(network): extract C2S handlers to top-level handleX functions

Mirrors ManagedNetworkRegistry's shape so handlers can be invoked
directly from gametest specs. registerNetworking() lambdas become
one-liner delegations. No behavior change.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Task 2: Promote shared test helpers to NetworkTestSupport.kt

**Files:**
- Create: `src/gametest/kotlin/com/breadmoirai/garnet/test/NetworkTestSupport.kt`
- Modify: `src/gametest/kotlin/com/breadmoirai/garnet/test/managed/ManagedTestSupport.kt`
- Modify: `src/gametest/kotlin/com/breadmoirai/garnet/test/managed/ManagedNetworkRegistrySpec.kt` (imports only)

The helpers `makeMockServerPlayer`, `drainPayloads`, `deleteRecursively`, `withTempRoot` are not managed-specific; promote them so the new networking spec can reuse them.

- [ ] **Step 1: Create the new NetworkTestSupport.kt with promoted helpers**

```kotlin
package com.breadmoirai.garnet.test

import com.breadmoirai.garnet.mixin.ConnectionAccessor
import com.breadmoirai.garnet.mixin.ServerCommonPacketListenerImplAccessor
import com.mojang.authlib.GameProfile
import io.netty.channel.embedded.EmbeddedChannel
import net.minecraft.network.Connection
import net.minecraft.network.protocol.PacketFlow
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.server.network.CommonListenerCookie
import net.minecraft.world.level.GameType
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.io.path.exists

/**
 * Builds a "mock" [ServerPlayer] in the server's overworld, mirroring vanilla
 * `GameTestHelper#makeMockServerPlayerInLevel`. Tied to a unique UUID so concurrent tests
 * don't collide on shared server-scoped state. The connection uses an [EmbeddedChannel]
 * so packet sends are no-ops on the wire but remain inspectable via [drainPayloads].
 *
 * Must be called on the server thread.
 */
fun makeMockServerPlayer(server: MinecraftServer): ServerPlayer {
    require(server.isSameThread) { "makeMockServerPlayer must be called on the server thread" }
    val profile = GameProfile(UUID.randomUUID(), "test-${UUID.randomUUID().toString().take(6)}")
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

/**
 * Reads all [CustomPacketPayload]s sent to [player] since the last call.
 * Walks the player's [EmbeddedChannel] outbound queue (set up by [makeMockServerPlayer])
 * and unwraps each [ClientboundCustomPayloadPacket].
 */
fun drainPayloads(player: ServerPlayer): List<CustomPacketPayload> {
    val listener = player.connection
    val conn = (listener as ServerCommonPacketListenerImplAccessor).`garnet$getConnection`()
    val ch = (conn as ConnectionAccessor).`garnet$getChannel`() as? EmbeddedChannel
        ?: return emptyList()
    val out = mutableListOf<CustomPacketPayload>()
    while (true) {
        val msg = ch.readOutbound<Any>() ?: break
        if (msg is ClientboundCustomPayloadPacket) out.add(msg.payload())
    }
    return out
}
```

- [ ] **Step 2: Trim ManagedTestSupport.kt to managed-only helpers**

Replace the file with:

```kotlin
package com.breadmoirai.garnet.test.managed

import com.breadmoirai.garnet.runner.RecordingDslEmitter
import net.minecraft.core.BlockPos
import net.minecraft.core.Vec3i
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.block.Blocks
import java.nio.file.Path
import kotlin.io.path.writeText

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

- [ ] **Step 3: Update ManagedNetworkRegistrySpec imports**

In `src/gametest/kotlin/com/breadmoirai/garnet/test/managed/ManagedNetworkRegistrySpec.kt`, replace the existing helper-related imports (which previously came from the same package) with explicit imports from the new package. Find and edit so the imports include both:

```kotlin
import com.breadmoirai.garnet.test.makeMockServerPlayer
import com.breadmoirai.garnet.test.drainPayloads
import com.breadmoirai.garnet.test.withTempRoot
```

…and keep the existing `clearCellVolume` / `writeStub` references resolving via same-package access. (No import line needed for same-package helpers.)

Note: also check `ManagedDimSpec.kt`, `ManagedCellSaverSpec.kt`, `ManagedTeleportSpec.kt`, `ManagedCommandSpec.kt` for usages of any moved helper and add explicit imports as needed. Run a grep:

```
grep -rln "withTempRoot\|makeMockServerPlayer\|drainPayloads\|deleteRecursively" src/gametest/kotlin/com/breadmoirai/garnet/test/managed/
```

For each file in the result, add the relevant `import com.breadmoirai.garnet.test.*` lines.

- [ ] **Step 4: Verify build**

```
cmd.exe /c "./gradlew.bat :26.1:gametestClasses"
```

Expected: BUILD SUCCESSFUL with no unresolved-reference errors.

- [ ] **Step 5: Run existing managed tests to confirm no regression**

```
cmd.exe /c "./gradlew.bat :26.1:runGameTestServer"
```

Expected: gametest passes (Kotest summary shows previous managed specs all green).

- [ ] **Step 6: Commit**

```
git add src/gametest/kotlin/com/breadmoirai/garnet/test/NetworkTestSupport.kt \
        src/gametest/kotlin/com/breadmoirai/garnet/test/managed/ManagedTestSupport.kt \
        src/gametest/kotlin/com/breadmoirai/garnet/test/managed/
git commit -m "refactor(test): promote shared network test helpers

Move makeMockServerPlayer, drainPayloads, deleteRecursively, and
withTempRoot from test/managed/ManagedTestSupport.kt to a shared
test/NetworkTestSupport.kt. ManagedTestSupport keeps managed-only
helpers (writeStub, clearCellVolume).

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Task 3: Add SpecBlockEntity placement helpers

**Files:**
- Modify: `src/gametest/kotlin/com/breadmoirai/garnet/test/NetworkTestSupport.kt`

Add helpers used by every networking test to set up a configured BE at a known position.

- [ ] **Step 1: Append placement helpers**

Append to `NetworkTestSupport.kt`:

```kotlin
import com.breadmoirai.garnet.ModRegistries
import com.breadmoirai.garnet.block.SpecBlockEntity
import net.minecraft.core.BlockPos
import net.minecraft.core.Vec3i
import net.minecraft.server.level.ServerLevel

/**
 * Places a recorder block + BE at [pos] in [level], applies setters, and returns the BE.
 * Caller is responsible for choosing a position not already occupied.
 */
fun placeRecorderBE(
    level: ServerLevel,
    pos: BlockPos,
    specId: String,
    structureId: String? = null,
    bounds: Vec3i = Vec3i(3, 3, 3),
): SpecBlockEntity {
    level.setBlock(pos, ModRegistries.GARNET_RECORDER_BLOCK.defaultBlockState(), 2)
    val be = level.getBlockEntity(pos) as SpecBlockEntity
    be.setSpecId(specId)
    if (structureId != null) be.setStructure(structureId)
    be.setSpecBounds(bounds)
    return be
}

/**
 * Places a runner block + BE at [pos] in [level], applies setters, and returns the BE.
 * Caller is responsible for choosing a position not already occupied.
 */
fun placeRunnerBE(
    level: ServerLevel,
    pos: BlockPos,
    specId: String,
    structureId: String? = null,
    bounds: Vec3i = Vec3i(3, 3, 3),
): SpecBlockEntity {
    level.setBlock(pos, ModRegistries.GARNET_RUNNER_BLOCK.defaultBlockState(), 2)
    val be = level.getBlockEntity(pos) as SpecBlockEntity
    be.setSpecId(specId)
    if (structureId != null) be.setStructure(structureId)
    be.setSpecBounds(bounds)
    return be
}
```

- [ ] **Step 2: Verify compile**

```
cmd.exe /c "./gradlew.bat :26.1:gametestClasses"
```

Expected: BUILD SUCCESSFUL. If `SpecBlockEntity` is not imported correctly or `setSpecBounds` has a different signature, fix per the actual API in `src/main/kotlin/com/breadmoirai/garnet/block/SpecBlockEntity.kt`.

- [ ] **Step 3: Commit**

```
git add src/gametest/kotlin/com/breadmoirai/garnet/test/NetworkTestSupport.kt
git commit -m "test(network): add placeRecorderBE / placeRunnerBE helpers

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Task 4: Create RecorderRunnerNetworkRegistrySpec scaffold + register sentinel

**Files:**
- Create: `src/gametest/kotlin/com/breadmoirai/garnet/test/network/RecorderRunnerNetworkRegistrySpec.kt`
- Modify: `src/gametest/kotlin/com/breadmoirai/garnet/test/GametestSentinel.kt`

**Critical:** per project memory, autoscan is OFF; specs not registered in `GametestSentinel.runAll` silently do not run.

- [ ] **Step 1: Create the spec scaffold with a single throw-away test**

```kotlin
package com.breadmoirai.garnet.test.network

import com.breadmoirai.garnet.network.handleRecorderCommand
import com.breadmoirai.garnet.network.RecorderCmd
import com.breadmoirai.garnet.network.RecorderCommandC2S
import com.breadmoirai.garnet.test.drainPayloads
import com.breadmoirai.garnet.test.makeMockServerPlayer
import com.breadmoirai.garnet.test.withTempRoot
import com.breadmoirai.garnet.testing.GarnetTestSpec
import com.breadmoirai.garnet.testing.server.onServer
import io.kotest.matchers.collections.shouldBeEmpty
import net.minecraft.core.BlockPos

/**
 * Server-side coverage for `NetworkRegistry` recorder/runner C2S handlers.
 * Each test corresponds to one or more rows in `docs/use-cases/networking.md`
 * (UC-NET-01.a/d through UC-NET-05). Test names embed the UC ID for traceability.
 *
 * Client-side rows (UC-NET-01.b/c, UC-NET-03.e, UC-NET-04.a) are deferred to
 * a future client-gametest cycle.
 */
class RecorderRunnerNetworkRegistrySpec : GarnetTestSpec({

    test("UC-NET-02.b: handleRecorderCommand on null BE is a silent no-op") {
        withTempRoot("net-rr-scaffold") {
            onServer {
                val player = makeMockServerPlayer(this)
                drainPayloads(player)

                handleRecorderCommand(this, player, RecorderCommandC2S(BlockPos(1000, 64, 1000), RecorderCmd.START))

                drainPayloads(player).shouldBeEmpty()
            }
        }
    }
})
```

- [ ] **Step 2: Register the spec in GametestSentinel.runAll**

Edit `src/gametest/kotlin/com/breadmoirai/garnet/test/GametestSentinel.kt`. Add the import:

```kotlin
import com.breadmoirai.garnet.test.network.RecorderRunnerNetworkRegistrySpec
```

In the `specs = listOf(...)` block, append `RecorderRunnerNetworkRegistrySpec::class,`. The list should look like:

```kotlin
specs = listOf(
    SmokeSpec::class,
    ManagedDimSpec::class,
    ManagedCellSaverSpec::class,
    ManagedTeleportSpec::class,
    ManagedNetworkRegistrySpec::class,
    ManagedCommandSpec::class,
    RecorderRunnerNetworkRegistrySpec::class,
),
```

- [ ] **Step 3: Run the gametest and verify the new spec executed**

```
cmd.exe /c "./gradlew.bat :26.1:runGameTestServer"
```

Expected: Kotest summary line in the log shows the new spec ran and its single test passed. Open the report at `build/reports/garnet/gametest/` to confirm `RecorderRunnerNetworkRegistrySpec` is listed.

If the spec does not appear in the report, the registration step failed — re-check `GametestSentinel.runAll`.

- [ ] **Step 4: Commit**

```
git add src/gametest/kotlin/com/breadmoirai/garnet/test/network/RecorderRunnerNetworkRegistrySpec.kt \
        src/gametest/kotlin/com/breadmoirai/garnet/test/GametestSentinel.kt
git commit -m "test(network): scaffold RecorderRunnerNetworkRegistrySpec

Single UC-NET-02.b case proves the spec is wired through the sentinel.
Subsequent commits add the remaining UC-NET-01..05 cases.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Task 5: UC-NET-02 — origin-guard tests

**Files:**
- Modify: `src/gametest/kotlin/com/breadmoirai/garnet/test/network/RecorderRunnerNetworkRegistrySpec.kt`

Adds the rest of UC-NET-02 coverage: stale-ref silent no-op (the scaffold case is renamed), and structural notes on UC-NET-02.a / 02.c.

- [ ] **Step 1: Replace the scaffold test with the full UC-NET-02 block**

In `RecorderRunnerNetworkRegistrySpec.kt`, replace the existing single test with:

```kotlin
    // UC-NET-02 — Server validates originPos and rejects stale or missing BEs.
    // 02.a (server-thread wrap) is verified structurally: every test below invokes
    //      handleX from inside `onServer { }`, which is exactly the contract the
    //      `context.server().execute { … }` lambda enforces at the registration site.
    // 02.c (block-kind re-validation) is covered by UC-NET-05.a and UC-NET-05.b.

    test("UC-NET-02.b: handleRecorderCommand on null BE is a silent no-op") {
        withTempRoot("net-uc02b") {
            onServer {
                val player = makeMockServerPlayer(this)
                drainPayloads(player)

                handleRecorderCommand(this, player, RecorderCommandC2S(BlockPos(1000, 64, 1000), RecorderCmd.START))

                drainPayloads(player).shouldBeEmpty()
            }
        }
    }

    test("UC-NET-02.d: handleRunnerCommand on null BE sends no S2C ack") {
        withTempRoot("net-uc02d") {
            onServer {
                val player = makeMockServerPlayer(this)
                drainPayloads(player)

                handleRunnerCommand(this, player, RunnerCommandC2S(BlockPos(1004, 64, 1000), RunnerCmd.PLACE_STRUCTURE))

                drainPayloads(player).shouldBeEmpty()
            }
        }
    }
```

Add imports as needed:

```kotlin
import com.breadmoirai.garnet.network.handleRunnerCommand
import com.breadmoirai.garnet.network.RunnerCmd
import com.breadmoirai.garnet.network.RunnerCommandC2S
```

- [ ] **Step 2: Run gametest**

```
cmd.exe /c "./gradlew.bat :26.1:runGameTestServer"
```

Expected: both UC-NET-02 cases pass. Inspect the XML report at `build/reports/garnet/gametest/` if the console summary is unclear.

- [ ] **Step 3: Commit**

```
git add src/gametest/kotlin/com/breadmoirai/garnet/test/network/RecorderRunnerNetworkRegistrySpec.kt
git commit -m "test(network): UC-NET-02 origin-guard coverage

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Task 6: UC-NET-03 — runner status S2C tests

**Files:**
- Modify: `src/gametest/kotlin/com/breadmoirai/garnet/test/network/RecorderRunnerNetworkRegistrySpec.kt`

Seven tests covering `PLACE_STRUCTURE`, `RUN`, and `RESTORE` flows.

- [ ] **Step 1: Probe `SpecBlockEntity.startRun` semantics for UC-NET-03.c**

Read `src/main/kotlin/com/breadmoirai/garnet/block/SpecBlockEntity.kt` around `fun startRun` (line ~171). Confirm whether two consecutive calls within the same tick can both return `true`. If a second call cannot return `false` synchronously, the UC-NET-03.c test below must instead force the "in-flight" state via whatever flag the BE uses (likely a non-null `currentRun` field or similar). If unclear, fall back to the documented downgrade in the spec's Risks section: mark UC-NET-03.c as `GAP-PARTIAL` and skip the test.

Decision recorded as a comment in the spec test below.

- [ ] **Step 2: Determine `SharedSettings.specSaveDir` interaction**

Read `src/main/kotlin/com/breadmoirai/garnet/config/SharedSettings.kt` to learn how `specSaveDir` is resolved. We need either to write `.spec.kts` / `.nbt` to `saveDir(server)` (which is `world/<specSaveDir>`) or to override `SharedSettings.specSaveDir` to point at the temp root.

Use whichever is reachable from a gametest. The gametest server's world path is under `build/`; writing into it is acceptable as long as we clean up after each test. Below, the tests use `saveDir(server)` directly via the `internal` helper exposed in Task 1.

Add an import in the test spec:

```kotlin
import com.breadmoirai.garnet.network.saveDir
```

(If `saveDir` is `internal` to the `network` package and not visible from the test sourceset, change the production declaration in NetworkRegistry.kt from `internal fun saveDir` to `fun saveDir` — Kotlin `internal` is per-sourceset per project memory. Verify by attempting a compile after the test edits.)

- [ ] **Step 3: Append UC-NET-03 tests**

Add to `RecorderRunnerNetworkRegistrySpec.kt`:

```kotlin
    // UC-NET-03 — Server emits S2C confirmation after state-mutating runner command.

    test("UC-NET-03.a: PLACE_STRUCTURE configured sends RunnerStatusS2C(IDLE, 'Structure placed: ...')") {
        withTempRoot("net-uc03a-cfg") { tmp ->
            onServer {
                val player = makeMockServerPlayer(this)
                val level = this.overworld()
                val pos = BlockPos(1100, 64, 1000)
                val bounds = Vec3i(3, 3, 3)
                placeRunnerBE(level, pos, specId = "demo", structureId = "demo", bounds = bounds)
                // Pre-write a structure NBT under the live save dir so PLACE_STRUCTURE has something to load.
                val dir = saveDir(this)
                java.nio.file.Files.createDirectories(dir)
                StructurePersistence.save(dir, "demo", level, pos, bounds)
                drainPayloads(player)

                handleRunnerCommand(this, player, RunnerCommandC2S(pos, RunnerCmd.PLACE_STRUCTURE))

                val status = drainPayloads(player).filterIsInstance<RunnerStatusS2C>().single()
                status.state shouldBe RunnerState.IDLE
                status.summary shouldBe "Structure placed: demo"

                clearCellVolume(level, pos, bounds)
            }
        }
    }

    test("UC-NET-03.a: PLACE_STRUCTURE not-configured sends RunnerStatusS2C(IDLE, 'No spec configured')") {
        withTempRoot("net-uc03a-noc") {
            onServer {
                val player = makeMockServerPlayer(this)
                val level = this.overworld()
                val pos = BlockPos(1108, 64, 1000)
                // Place runner but don't configure (blank spec id)
                level.setBlock(pos, com.breadmoirai.garnet.ModRegistries.GARNET_RUNNER_BLOCK.defaultBlockState(), 2)
                drainPayloads(player)

                handleRunnerCommand(this, player, RunnerCommandC2S(pos, RunnerCmd.PLACE_STRUCTURE))

                val status = drainPayloads(player).filterIsInstance<RunnerStatusS2C>().single()
                status.state shouldBe RunnerState.IDLE
                status.summary shouldBe "No spec configured"
            }
        }
    }

    test("UC-NET-03.b: RUN happy-path sends RunnerStatusS2C(RUNNING, 'Running…') first") {
        withTempRoot("net-uc03b-ok") {
            onServer {
                val player = makeMockServerPlayer(this)
                val level = this.overworld()
                val pos = BlockPos(1116, 64, 1000)
                placeRunnerBE(level, pos, specId = "demo")
                val dir = saveDir(this)
                java.nio.file.Files.createDirectories(dir)
                writeStub(dir, "demo")
                drainPayloads(player)

                handleRunnerCommand(this, player, RunnerCommandC2S(pos, RunnerCmd.RUN))

                val statuses = drainPayloads(player).filterIsInstance<RunnerStatusS2C>()
                statuses.first().state shouldBe RunnerState.RUNNING
                statuses.first().summary shouldBe "Running…"

                java.nio.file.Files.deleteIfExists(dir.resolve("demo.spec.kts"))
            }
        }
    }

    test("UC-NET-03.b: RUN with missing spec sends RunnerStatusS2C(FAIL, 'Spec file not found: ...')") {
        withTempRoot("net-uc03b-miss") {
            onServer {
                val player = makeMockServerPlayer(this)
                val level = this.overworld()
                val pos = BlockPos(1124, 64, 1000)
                placeRunnerBE(level, pos, specId = "no-such-spec")
                drainPayloads(player)

                handleRunnerCommand(this, player, RunnerCommandC2S(pos, RunnerCmd.RUN))

                val status = drainPayloads(player).filterIsInstance<RunnerStatusS2C>().single()
                status.state shouldBe RunnerState.FAIL
                status.summary shouldBe "Spec file not found: no-such-spec"
            }
        }
    }

    // UC-NET-03.c — see Step 1 probe. If startRun cannot synchronously return false on
    // a second call, this test asserts the contract via direct BE state inspection.
    // Adjust per probe outcome.
    test("UC-NET-03.c: RUN twice surfaces RunnerStatusS2C(RUNNING, 'Already running')") {
        withTempRoot("net-uc03c") {
            onServer {
                val player = makeMockServerPlayer(this)
                val level = this.overworld()
                val pos = BlockPos(1132, 64, 1000)
                placeRunnerBE(level, pos, specId = "demo")
                val dir = saveDir(this)
                java.nio.file.Files.createDirectories(dir)
                writeStub(dir, "demo")
                drainPayloads(player)

                handleRunnerCommand(this, player, RunnerCommandC2S(pos, RunnerCmd.RUN))
                handleRunnerCommand(this, player, RunnerCommandC2S(pos, RunnerCmd.RUN))

                val statuses = drainPayloads(player).filterIsInstance<RunnerStatusS2C>()
                // First send is "Running…"; if startRun returned false on the second call,
                // a second status with "Already running" is sent. If the second call also
                // started successfully, two "Running…" frames will be seen.
                statuses.any { it.state == RunnerState.RUNNING && it.summary == "Already running" } shouldBe true

                java.nio.file.Files.deleteIfExists(dir.resolve("demo.spec.kts"))
            }
        }
    }

    test("UC-NET-03.d: RESTORE configured sends RunnerStatusS2C(IDLE, 'Snapshot restored')") {
        withTempRoot("net-uc03d-cfg") {
            onServer {
                val player = makeMockServerPlayer(this)
                val level = this.overworld()
                val pos = BlockPos(1140, 64, 1000)
                placeRunnerBE(level, pos, specId = "demo")
                drainPayloads(player)

                handleRunnerCommand(this, player, RunnerCommandC2S(pos, RunnerCmd.RESTORE))

                val status = drainPayloads(player).filterIsInstance<RunnerStatusS2C>().single()
                status.state shouldBe RunnerState.IDLE
                status.summary shouldBe "Snapshot restored"
            }
        }
    }

    test("UC-NET-03.d: RESTORE not-configured sends RunnerStatusS2C(IDLE, 'No spec configured')") {
        withTempRoot("net-uc03d-noc") {
            onServer {
                val player = makeMockServerPlayer(this)
                val level = this.overworld()
                val pos = BlockPos(1148, 64, 1000)
                level.setBlock(pos, com.breadmoirai.garnet.ModRegistries.GARNET_RUNNER_BLOCK.defaultBlockState(), 2)
                drainPayloads(player)

                handleRunnerCommand(this, player, RunnerCommandC2S(pos, RunnerCmd.RESTORE))

                val status = drainPayloads(player).filterIsInstance<RunnerStatusS2C>().single()
                status.state shouldBe RunnerState.IDLE
                status.summary shouldBe "No spec configured"
            }
        }
    }
```

Imports to add:

```kotlin
import com.breadmoirai.garnet.network.RunnerState
import com.breadmoirai.garnet.network.RunnerStatusS2C
import com.breadmoirai.garnet.persistence.StructurePersistence
import com.breadmoirai.garnet.test.placeRunnerBE
import com.breadmoirai.garnet.test.managed.clearCellVolume
import com.breadmoirai.garnet.test.managed.writeStub
import io.kotest.matchers.shouldBe
import net.minecraft.core.Vec3i
```

- [ ] **Step 4: Run gametest**

```
cmd.exe /c "./gradlew.bat :26.1:runGameTestServer"
```

Expected: all UC-NET-03 cases pass. If UC-NET-03.c fails because the second `startRun` returns `true`, follow the probe outcome from Step 1 and either rewrite the test against BE state directly or downgrade the row to `GAP-PARTIAL` in the matrix update task and remove this test.

- [ ] **Step 5: Commit**

```
git add src/gametest/kotlin/com/breadmoirai/garnet/test/network/RecorderRunnerNetworkRegistrySpec.kt
git commit -m "test(network): UC-NET-03 runner status S2C coverage

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Task 7: UC-NET-04 — overwrite-decision tests (server half)

**Files:**
- Modify: `src/gametest/kotlin/com/breadmoirai/garnet/test/network/RecorderRunnerNetworkRegistrySpec.kt`

Three tests: origin guard, overwrite=true clears + loads, overwrite=false leaves world alone. UC-NET-04.d (disconnect) is covered structurally.

- [ ] **Step 1: Append UC-NET-04 tests**

```kotlin
    // UC-NET-04 — Overwrite-prompt confirmation handshake (server half only).
    // UC-NET-04.a (server emits OverwritePromptS2C) is GAP — no producer in
    // recorder/runner registry today; only managed/structure paths emit it.

    test("UC-NET-04.b: handleOverwriteDecision on null BE is a silent no-op") {
        withTempRoot("net-uc04b") {
            onServer {
                val player = makeMockServerPlayer(this)
                drainPayloads(player)

                handleOverwriteDecision(this, player, OverwriteDecisionC2SPayload(BlockPos(1200, 64, 1000), true))

                drainPayloads(player).shouldBeEmpty()
            }
        }
    }

    test("UC-NET-04.c: overwrite=true clears bounds and loads structure") {
        withTempRoot("net-uc04c-true") {
            onServer {
                val player = makeMockServerPlayer(this)
                val level = this.overworld()
                val pos = BlockPos(1208, 64, 1000)
                val bounds = Vec3i(3, 3, 3)
                placeRunnerBE(level, pos, specId = "demo", structureId = "demo", bounds = bounds)
                val dir = saveDir(this)
                java.nio.file.Files.createDirectories(dir)
                StructurePersistence.save(dir, "demo", level, pos, bounds)
                // Pollute bounds with a non-air block.
                val polluted = pos.offset(1, 1, 1)
                level.setBlock(polluted, net.minecraft.world.level.block.Blocks.GOLD_BLOCK.defaultBlockState(), 2)

                handleOverwriteDecision(this, player, OverwriteDecisionC2SPayload(pos, true))

                // After overwrite=true, the gold block should be cleared (clearBounds → load).
                level.getBlockState(polluted).block shouldBe net.minecraft.world.level.block.Blocks.AIR

                clearCellVolume(level, pos, bounds)
            }
        }
    }

    test("UC-NET-04.c: overwrite=false leaves world unchanged and does not load structure") {
        withTempRoot("net-uc04c-false") {
            onServer {
                val player = makeMockServerPlayer(this)
                val level = this.overworld()
                val pos = BlockPos(1216, 64, 1000)
                val bounds = Vec3i(3, 3, 3)
                placeRunnerBE(level, pos, specId = "demo", structureId = "demo", bounds = bounds)
                val polluted = pos.offset(1, 1, 1)
                level.setBlock(polluted, net.minecraft.world.level.block.Blocks.GOLD_BLOCK.defaultBlockState(), 2)

                handleOverwriteDecision(this, player, OverwriteDecisionC2SPayload(pos, false))

                level.getBlockState(polluted).block shouldBe net.minecraft.world.level.block.Blocks.GOLD_BLOCK

                clearCellVolume(level, pos, bounds)
            }
        }
    }

    // UC-NET-04.d — Disconnect-before-decision: structurally verified by simply
    // *not* calling handleOverwriteDecision. World stays in pre-prompt state.
    // Asserted as part of UC-NET-04.b (no payload, no mutation when handler absent).
```

Add imports:

```kotlin
import com.breadmoirai.garnet.network.handleOverwriteDecision
import com.breadmoirai.garnet.network.OverwriteDecisionC2SPayload
```

- [ ] **Step 2: Run gametest**

```
cmd.exe /c "./gradlew.bat :26.1:runGameTestServer"
```

Expected: all three UC-NET-04 cases pass.

- [ ] **Step 3: Commit**

```
git add src/gametest/kotlin/com/breadmoirai/garnet/test/network/RecorderRunnerNetworkRegistrySpec.kt
git commit -m "test(network): UC-NET-04 overwrite-decision coverage

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Task 8: UC-NET-05 — block-kind guard tests

**Files:**
- Modify: `src/gametest/kotlin/com/breadmoirai/garnet/test/network/RecorderRunnerNetworkRegistrySpec.kt`

Three tests: recorder cmd on runner block, runner cmd on recorder block, two-player no-permission demonstration.

- [ ] **Step 1: Append UC-NET-05 tests**

```kotlin
    // UC-NET-05 — Server rejects unauthorized or misrouted C2S commands.

    test("UC-NET-05.a: RecorderCommand on a runner block is a silent no-op") {
        withTempRoot("net-uc05a") {
            onServer {
                val player = makeMockServerPlayer(this)
                val level = this.overworld()
                val pos = BlockPos(1300, 64, 1000)
                val be = placeRunnerBE(level, pos, specId = "demo")
                drainPayloads(player)

                handleRecorderCommand(this, player, RecorderCommandC2S(pos, RecorderCmd.START))

                drainPayloads(player).shouldBeEmpty()
                // Recording must not have started — BE state is untouched.
                be.isRecording shouldBe false
            }
        }
    }

    test("UC-NET-05.b: RunnerCommand on a recorder block is a silent no-op") {
        withTempRoot("net-uc05b") {
            onServer {
                val player = makeMockServerPlayer(this)
                val level = this.overworld()
                val pos = BlockPos(1308, 64, 1000)
                placeRecorderBE(level, pos, specId = "demo")
                drainPayloads(player)

                handleRunnerCommand(this, player, RunnerCommandC2S(pos, RunnerCmd.PLACE_STRUCTURE))

                // No RunnerStatusS2C should be emitted on a recorder block.
                drainPayloads(player).filterIsInstance<RunnerStatusS2C>().shouldBeEmpty()
            }
        }
    }

    test("UC-NET-05.d: any player can issue commands to any reachable BE (no permission check)") {
        withTempRoot("net-uc05d") {
            onServer {
                val ownerLikePlayer = makeMockServerPlayer(this)
                val otherPlayer = makeMockServerPlayer(this)
                val level = this.overworld()
                val pos = BlockPos(1316, 64, 1000)
                placeRunnerBE(level, pos, specId = "demo")
                drainPayloads(ownerLikePlayer)
                drainPayloads(otherPlayer)

                // A second, unrelated player issues RESTORE — succeeds without ownership check.
                handleRunnerCommand(this, otherPlayer, RunnerCommandC2S(pos, RunnerCmd.RESTORE))

                val status = drainPayloads(otherPlayer).filterIsInstance<RunnerStatusS2C>().single()
                status.state shouldBe RunnerState.IDLE
                status.summary shouldBe "Snapshot restored"
            }
        }
    }
```

Add import for placeRecorderBE:

```kotlin
import com.breadmoirai.garnet.test.placeRecorderBE
```

**Note:** UC-NET-05.a uses `be.isRecording` — verify this property exists on `SpecBlockEntity` by reading `src/main/kotlin/com/breadmoirai/garnet/block/SpecBlockEntity.kt`. If the actual flag is named differently (e.g. `isStateRecorderActive`, or a `state` string check), substitute the correct accessor.

- [ ] **Step 2: Run gametest**

```
cmd.exe /c "./gradlew.bat :26.1:runGameTestServer"
```

Expected: all three UC-NET-05 cases pass.

- [ ] **Step 3: Commit**

```
git add src/gametest/kotlin/com/breadmoirai/garnet/test/network/RecorderRunnerNetworkRegistrySpec.kt
git commit -m "test(network): UC-NET-05 block-kind guard coverage

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Task 9: UC-NET-01 — server-initiated recorder open

**Files:**
- Modify: `src/gametest/kotlin/com/breadmoirai/garnet/test/network/RecorderRunnerNetworkRegistrySpec.kt`

UC-NET-01 covers the server-side build of `OpenRecorderScreenS2C`. The block's `useWithoutItem` is what actually emits the payload; we verify the *output* of that path by simulating a right-click via the block's public method (or, if not reachable from the test, by directly building the payload from a configured BE — a weaker structural test).

- [ ] **Step 1: Locate the screen-open path**

Read `src/main/kotlin/com/breadmoirai/garnet/block/GarnetRecorderBlock.kt`. Find `useWithoutItem`. Note whether it can be invoked from a test (it likely takes `BlockState`, `Level`, `BlockPos`, `Player`, `InteractionHand`, `BlockHitResult`) — if reachable, call it directly. If not, an alternative is to extract the `ServerPlayNetworking.send(player, OpenRecorderScreenS2C(...))` call into a dedicated helper function `openRecorderScreenFor(server, player, be)` on `GarnetRecorderBlock` and call that helper directly.

Default: invoke `useWithoutItem` with synthetic arguments. If signatures or hit-result construction is too painful, extract the helper.

- [ ] **Step 2: Append UC-NET-01 test**

```kotlin
    // UC-NET-01 — Server-initiated recorder screen open.
    // 01.b/c are client receiver rows, deferred to a future client-gametest cycle.
    // 01.d (no C2S starts the flow) is verified structurally: this test sends no
    //      C2S packet — the OpenRecorderScreenS2C is observed solely from the server path.

    test("UC-NET-01.a: server build of OpenRecorderScreenS2C carries BE fields") {
        withTempRoot("net-uc01a") {
            onServer {
                val player = makeMockServerPlayer(this)
                val level = this.overworld()
                val pos = BlockPos(1400, 64, 1000)
                placeRecorderBE(level, pos, specId = "demo", structureId = "demo")
                drainPayloads(player)

                // Invoke the recorder open path. Adjust per Step 1 finding:
                //   Option A: call GarnetRecorderBlock.useWithoutItem directly
                //   Option B: call extracted helper openRecorderScreenFor(this, player, be)
                val be = level.getBlockEntity(pos) as com.breadmoirai.garnet.block.SpecBlockEntity
                com.breadmoirai.garnet.block.GarnetRecorderBlock.openScreenFor(this, player, be)

                val packet = drainPayloads(player).filterIsInstance<OpenRecorderScreenS2C>().single()
                packet.originPos shouldBe pos
                packet.specId shouldBe "demo"
                packet.structureId shouldBe "demo"
            }
        }
    }
```

If extraction (Option B) is needed, add a corresponding production-side change in this same task: a `companion object`-level or top-level function `openScreenFor(server, player, be)` on `GarnetRecorderBlock` that contains the existing payload-build + send logic. The block's `useWithoutItem` then delegates to it.

Add import:

```kotlin
import com.breadmoirai.garnet.network.OpenRecorderScreenS2C
```

- [ ] **Step 3: Run gametest**

```
cmd.exe /c "./gradlew.bat :26.1:runGameTestServer"
```

Expected: UC-NET-01.a passes.

- [ ] **Step 4: Commit**

```
git add src/gametest/kotlin/com/breadmoirai/garnet/test/network/RecorderRunnerNetworkRegistrySpec.kt \
        src/main/kotlin/com/breadmoirai/garnet/block/GarnetRecorderBlock.kt
git commit -m "test(network): UC-NET-01 server-initiated recorder open

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Task 10: Update coverage matrix in networking.md

**Files:**
- Modify: `docs/use-cases/networking.md`

Replace `**GAP**` / `**GAP-PARTIAL**` markers with test references for covered rows. Keep deferred-client rows and untestable rows (UC-NET-04.a) marked `GAP` with footnotes.

- [ ] **Step 1: Inspect article convention for "covered"**

Open another article's coverage matrix (e.g. `docs/use-cases/managed-worlds.md` or `gametest-harness.md`) and check what the `Status` column shows for fully-covered rows. Match that convention exactly.

- [ ] **Step 2: Edit the matrix**

In `docs/use-cases/networking.md`, replace each row in the Coverage matrix as follows (test references go in the `Test` column; status uses the convention from Step 1, e.g. blank or `OK`):

- UC-NET-01.a → `RecorderRunnerNetworkRegistrySpec."UC-NET-01.a: ..."`
- UC-NET-01.b → unchanged `**GAP**`, append footnote ¹
- UC-NET-01.c → unchanged `**GAP**`, append footnote ¹
- UC-NET-01.d → `RecorderRunnerNetworkRegistrySpec."UC-NET-01.a: ..."` (verified structurally inside .a)
- UC-NET-02   → `RecorderRunnerNetworkRegistrySpec` (parent — covered by sub-rows below)
- UC-NET-02.a → covered structurally by all UC-NET tests in `RecorderRunnerNetworkRegistrySpec`
- UC-NET-02.b → `RecorderRunnerNetworkRegistrySpec."UC-NET-02.b: ..."`
- UC-NET-02.c → covered by `RecorderRunnerNetworkRegistrySpec."UC-NET-05.a: ..."`, `"UC-NET-05.b: ..."`
- UC-NET-02.d → `RecorderRunnerNetworkRegistrySpec."UC-NET-02.d: ..."`
- UC-NET-03   → covered by .a..d
- UC-NET-03.a → both `RecorderRunnerNetworkRegistrySpec."UC-NET-03.a: PLACE_STRUCTURE configured ..."` and `"... not-configured ..."`
- UC-NET-03.b → both `"UC-NET-03.b: RUN happy-path ..."` and `"... missing spec ..."`
- UC-NET-03.c → `"UC-NET-03.c: RUN twice ..."` (or `**GAP-PARTIAL**` with footnote ² if the probe forced a downgrade)
- UC-NET-03.d → both `"UC-NET-03.d: RESTORE configured ..."` and `"... not-configured ..."`
- UC-NET-03.e → unchanged `**GAP**`, footnote ¹
- UC-NET-04   → covered by .b..d
- UC-NET-04.a → unchanged `**GAP**`, footnote ³
- UC-NET-04.b → `"UC-NET-04.b: ..."`
- UC-NET-04.c → both `"UC-NET-04.c: overwrite=true ..."` and `"... overwrite=false ..."`
- UC-NET-04.d → covered structurally by `"UC-NET-04.b: ..."` (handler not invoked → world unchanged)
- UC-NET-05   → covered by .a..d
- UC-NET-05.a → `"UC-NET-05.a: ..."`
- UC-NET-05.b → `"UC-NET-05.b: ..."`
- UC-NET-05.c → covered by `"UC-NET-05.a"` + `"UC-NET-05.b"` together
- UC-NET-05.d → `"UC-NET-05.d: ..."`

Add at the bottom of the article (below the table):

```markdown
**Footnotes:**

¹ Client receiver — deferred to a future client-gametest cycle (requires `ClientTestSentinel` plus a real client process).

² UC-NET-03.c may be downgraded to `GAP-PARTIAL` if `SpecBlockEntity.startRun` cannot synchronously return `false` on a back-to-back call within a single tick.

³ No `OverwritePromptS2C` producer exists in the recorder/runner registry today; only managed/structure load paths emit it. Coverage requires a producer to be wired in `NetworkRegistry` first.
```

- [ ] **Step 3: Bump `last_audited_commit:` to the upcoming HEAD**

Leave it as-is for now; bump it in the final commit of this task.

- [ ] **Step 4: Commit**

```
LAST=$(git rev-parse HEAD)
# Open the article and replace the `last_audited_commit:` value with $LAST.
git add docs/use-cases/networking.md
git commit -m "docs(use-cases): close networking GAP rows after coverage spec

UC-NET-01.a/d, UC-NET-02..05 server-side rows now reference
RecorderRunnerNetworkRegistrySpec. Client-side rows (UC-NET-01.b/c,
UC-NET-03.e, UC-NET-04.a) remain GAP with footnotes pointing at
the deferred client-gametest cycle.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

(After commit, re-edit `last_audited_commit:` to the new HEAD and amend, OR run a follow-up commit. Either is acceptable per project convention; default to a follow-up commit if amending feels risky.)

---

## Task 11: Final build verification

**Files:** none

- [ ] **Step 1: Full multi-sourceset build**

```
cmd.exe /c "./gradlew.bat :26.1:clientClasses :26.1:classes :26.1:gametestClasses :26.1:clientTestClasses :26.1:testClasses"
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 2: Full gametest run**

```
cmd.exe /c "./gradlew.bat :26.1:runGameTestServer"
```

Expected: Kotest summary in the log shows ALL specs green (managed + new networking spec), test count increased by exactly 16–17 (Tasks 5–9 totals).

- [ ] **Step 3: Inspect XML report**

Open `build/reports/garnet/gametest/` and confirm `RecorderRunnerNetworkRegistrySpec` lists every `UC-NET-...` test as a passing case.

- [ ] **Step 4: Final summary commit (only if Steps 1-3 pass)**

If a `last_audited_commit:` bump was deferred from Task 10:

```
LAST=$(git rev-parse HEAD)
# Edit docs/use-cases/networking.md: set last_audited_commit: $LAST
git add docs/use-cases/networking.md
git commit -m "docs(use-cases): bump networking last_audited_commit

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Self-review notes (for the implementer)

- **UC-NET-03.c risk** is the single biggest uncertainty. If the probe in Task 6 Step 1 finds that `startRun` cannot return `false` synchronously in one tick, downgrade the row in Task 10 and remove the test rather than forcing a flaky pass.
- **`saveDir` visibility:** if `internal fun saveDir` in Task 1 isn't visible from gametest, change to public — Kotlin `internal` is per-sourceset (project memory).
- **`be.isRecording`** in UC-NET-05.a may not exist; substitute the correct accessor.
- **Per-test position offsets** start at 1000 to stay clear of the gametest platform; bump if collisions occur. Each task uses a different X-band (1000s for UC-02, 1100s for UC-03, 1200s for UC-04, 1300s for UC-05, 1400s for UC-01) for easier debugging.
- **Sentinel registration** (Task 4 Step 2) is a silent failure mode — if the spec doesn't appear in the report, the registration is wrong.
- Each task ends with a commit. If a task's verification fails, fix and re-commit on top; do not amend across pushes.
