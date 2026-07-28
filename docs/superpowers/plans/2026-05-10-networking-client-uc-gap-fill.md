# Networking client-side UC GAP fill — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close the 4 deferred client-side rows in `docs/use-cases/networking.md` (UC-NET-01.b/c, UC-NET-03.e, UC-NET-04.a) by adding a `ClientNetworkSpec` Kotest spec to the clientTest sourceset, backed by a small file of `ServerPlayNetworking.send` helpers.

**Architecture:** New `ClientNetworkTestSupport.kt` provides synthetic payload-send helpers + accessors for the running `ClientGameTestContext` and `TestSingleplayerContext`. New `ClientNetworkSpec.kt` has 4 tests. `ClientTestSentinel` registers the new spec and installs the world into a holder so the worker-thread spec can reach it. No production code changes.

**Tech Stack:** Kotlin, Fabric Client Gametest API (`FabricClientGameTest`), Minecraft 1.26.1, Kotest, Stonecutter.

**Spec:** [`docs/superpowers/specs/2026-05-10-networking-client-uc-gap-fill-design.md`](../specs/2026-05-10-networking-client-uc-gap-fill-design.md).

**Build commands used in this plan:**

```
cmd.exe /c "gradlew.bat :26.1:clientTestClasses"
cmd.exe /c "gradlew.bat :26.1:runClientTest"
```

`runClientTest` boots a real Minecraft client + integrated server; expect 1–3 minutes per run. `runGameTest` is unchanged from the server-side cycle and should remain green throughout.

---

## Task 1: Add `WorldHolder` for the active singleplayer test world

**Files:**
- Create: `src/main/kotlin/com/breadmoirai/garnet/testing/core/WorldHolder.kt`
- Modify: `src/clientTest/kotlin/com/breadmoirai/garnet/test/ClientTestSentinel.kt`

The kotest worker runs on a separate thread from `ClientTestSentinel.runTest`. The worker can't see the local `world` variable inside the `use { }` block. Mirror the existing `ClientContextHolder` pattern.

- [ ] **Step 1: Verify build clean**

```
cmd.exe /c "gradlew.bat :26.1:clientClasses :26.1:classes :26.1:gametestClasses :26.1:clientTestClasses :26.1:testClasses"
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 2: Create `WorldHolder.kt`**

```kotlin
package com.breadmoirai.garnet.testing.core

import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext

/**
 * Holds the active `TestSingleplayerContext` (the integrated server's world) for
 * client-source-set Kotest specs to consume.
 *
 * Set by `ClientTestSentinel.runTest` after `SpecTestContext.createWorld`; cleared
 * when the surrounding `use { }` block exits.
 */
@Suppress("UnstableApiUsage")
object WorldHolder {
    @Volatile private var _world: TestSingleplayerContext? = null

    val world: TestSingleplayerContext
        get() = _world ?: error("WorldHolder accessed outside an active client gametest")

    fun install(world: TestSingleplayerContext) {
        _world = world
    }

    fun clear() {
        _world = null
    }
}
```

- [ ] **Step 3: Wire the holder into `ClientTestSentinel.runTest`**

In `src/clientTest/kotlin/com/breadmoirai/garnet/test/ClientTestSentinel.kt`, update the `runTest` method. Find the `SpecTestContext.createWorld(context).use { }` block and change it to install + clear the world:

```kotlin
override fun runTest(context: ClientGameTestContext) {
    GarnetTestLifecycle.register()
    ClientContextHolder.install(context)
    SpecTestContext.createWorld(context).use { world ->
        WorldHolder.install(world)
        try {
            val result = runKotestOnWorker(context)
            if (result.failed > 0) {
                logger.error(result.summary())
                error("${result.failed}/${result.total} Kotest test(s) failed")
            }
            logger.info(result.summary())
        } finally {
            WorldHolder.clear()
            ClientContextHolder.clear()
        }
    }
}
```

Note: the `use { it ->` parameter was previously unnamed. Rename to `use { world ->` and add the `WorldHolder.install(world)` call. The existing `ClientContextHolder.clear()` moves into the same `finally` block.

Add the import:

```kotlin
import com.breadmoirai.garnet.testing.core.WorldHolder
```

- [ ] **Step 4: Verify build**

```
cmd.exe /c "gradlew.bat :26.1:clientTestClasses"
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```
git add src/main/kotlin/com/breadmoirai/garnet/testing/core/WorldHolder.kt \
        src/clientTest/kotlin/com/breadmoirai/garnet/test/ClientTestSentinel.kt
git commit -m "test(client): add WorldHolder for kotest-worker world access

Mirrors ClientContextHolder. Lets client-sourceset Kotest specs
reach the active TestSingleplayerContext (and its MinecraftServer)
from the worker thread that ClientTestSentinel spawns.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Task 2: Create `ClientNetworkTestSupport.kt`

**Files:**
- Create: `src/clientTest/kotlin/com/breadmoirai/garnet/test/ClientNetworkTestSupport.kt`

Four send helpers + two accessors. Each send helper schedules onto the server thread via `ServerPlayNetworking.send`. The integrated server's overworld player is the one to address — there's exactly one in this harness.

- [ ] **Step 1: Create the file**

```kotlin
package com.breadmoirai.garnet.test

import com.breadmoirai.garnet.network.OpenRecorderScreenS2C
import com.breadmoirai.garnet.network.OpenRunnerScreenS2C
import com.breadmoirai.garnet.network.OverwritePromptS2CPayload
import com.breadmoirai.garnet.network.RunnerStatusS2C
import com.breadmoirai.garnet.testing.core.ClientContextHolder
import com.breadmoirai.garnet.testing.core.WorldHolder
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import org.apache.commons.lang3.function.FailableConsumer

/** Active `ClientGameTestContext` installed by `ClientTestSentinel`. */
@Suppress("UnstableApiUsage")
fun clientContext(): ClientGameTestContext = ClientContextHolder.context

/** Active `TestSingleplayerContext` installed by `ClientTestSentinel`. */
@Suppress("UnstableApiUsage")
fun currentWorld(): TestSingleplayerContext = WorldHolder.world

/**
 * Synchronously sends an S2C payload to the integrated server's first overworld
 * player. Runs on the server thread via `TestSingleplayerContext.runOnServer`,
 * which blocks the caller until the block completes — convenient for tests that
 * need to know the send happened before they assert.
 */
@Suppress("UnstableApiUsage")
private fun sendToLocalPlayer(action: (net.minecraft.server.MinecraftServer) -> Unit) {
    currentWorld().getServer().runOnServer(
        object : FailableConsumer<net.minecraft.server.MinecraftServer, RuntimeException> {
            override fun accept(server: net.minecraft.server.MinecraftServer) {
                action(server)
            }
        }
    )
}

fun sendOpenRecorderScreen(payload: OpenRecorderScreenS2C) {
    sendToLocalPlayer { server ->
        val player = server.overworld().players().firstOrNull() ?: return@sendToLocalPlayer
        ServerPlayNetworking.send(player, payload)
    }
}

fun sendOpenRunnerScreen(payload: OpenRunnerScreenS2C) {
    sendToLocalPlayer { server ->
        val player = server.overworld().players().firstOrNull() ?: return@sendToLocalPlayer
        ServerPlayNetworking.send(player, payload)
    }
}

fun sendRunnerStatus(payload: RunnerStatusS2C) {
    sendToLocalPlayer { server ->
        val player = server.overworld().players().firstOrNull() ?: return@sendToLocalPlayer
        ServerPlayNetworking.send(player, payload)
    }
}

fun sendOverwritePrompt(payload: OverwritePromptS2CPayload) {
    sendToLocalPlayer { server ->
        val player = server.overworld().players().firstOrNull() ?: return@sendToLocalPlayer
        ServerPlayNetworking.send(player, payload)
    }
}
```

- [ ] **Step 2: Verify build**

```
cmd.exe /c "gradlew.bat :26.1:clientTestClasses"
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```
git add src/clientTest/kotlin/com/breadmoirai/garnet/test/ClientNetworkTestSupport.kt
git commit -m "test(client): add ClientNetworkTestSupport with send helpers

Four ServerPlayNetworking.send helpers (open recorder/runner screen,
runner status, overwrite prompt) plus clientContext/currentWorld
accessors over the existing ClientContextHolder/WorldHolder.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Task 3: Create `ClientNetworkSpec` scaffold and register in sentinel

**Files:**
- Create: `src/clientTest/kotlin/com/breadmoirai/garnet/test/ClientNetworkSpec.kt`
- Modify: `src/clientTest/kotlin/com/breadmoirai/garnet/test/ClientTestSentinel.kt`

A single throwaway test that proves the spec is wired through the sentinel and the world holder works. Subsequent tasks fill in the actual UC-NET tests.

- [ ] **Step 1: Create the spec scaffold**

```kotlin
package com.breadmoirai.garnet.test

import com.breadmoirai.garnet.client.screen.RunnerScreen
import com.breadmoirai.garnet.network.OpenRunnerScreenS2C
import com.breadmoirai.garnet.testing.GarnetTestSpec
import io.kotest.matchers.shouldNotBe
import net.minecraft.core.BlockPos

/**
 * Client-side coverage for `ClientNetworkHandler` receivers.
 * Covers UC-NET-01.b/c, UC-NET-03.e, and UC-NET-04.a (partial) from
 * `docs/use-cases/networking.md`. Server-side rows live in
 * `RecorderRunnerNetworkRegistrySpec` (gametest sourceset).
 */
class ClientNetworkSpec : GarnetTestSpec({

    test("scaffold: sendOpenRunnerScreen opens RunnerScreen") {
        val ctx = clientContext()
        val pos = BlockPos(100, 64, 100)
        sendOpenRunnerScreen(OpenRunnerScreenS2C(pos, "", emptyList(), null))
        ctx.waitForScreen(RunnerScreen::class.java)
        RunnerScreen.active shouldNotBe null

        // Cleanup so later tests start from a known state.
        ctx.getInput().pressKey(org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE)
        ctx.waitFor { mc -> mc.screen == null }
        RunnerScreen.active = null
    }
})
```

- [ ] **Step 2: Register the spec in `ClientTestSentinel.runKotestOnWorker`**

In `src/clientTest/kotlin/com/breadmoirai/garnet/test/ClientTestSentinel.kt`, find the `specs = listOf(...)` block inside `launchKotest(...)`. Change it from:

```kotlin
specs = listOf(RunGarnetSpecSmokeTest::class),
```

to:

```kotlin
specs = listOf(
    RunGarnetSpecSmokeTest::class,
    ClientNetworkSpec::class,
),
```

- [ ] **Step 3: Run client gametest and verify the new spec executed**

```
cmd.exe /c "gradlew.bat :26.1:runClientTest"
```

Expected: `Kotest: All N tests passed` where `N = previous + 1`. If the new spec doesn't appear in the log, the sentinel registration is wrong.

This run also validates that:
- `WorldHolder.install` from Task 1 works (the test reads it via `currentWorld()` inside the send helper).
- `clientContext()` resolves to the active context.
- `sendOpenRunnerScreen` actually delivers a payload that opens `RunnerScreen`.

- [ ] **Step 4: Commit**

```
git add src/clientTest/kotlin/com/breadmoirai/garnet/test/ClientNetworkSpec.kt \
        src/clientTest/kotlin/com/breadmoirai/garnet/test/ClientTestSentinel.kt
git commit -m "test(client): scaffold ClientNetworkSpec and register in sentinel

Single scaffold test proves the spec is wired and WorldHolder
resolves on the worker thread. Subsequent commits add the UC-NET
client-side cases.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Task 4: UC-NET-01.c — recorder open via real flow

**Files:**
- Modify: `src/clientTest/kotlin/com/breadmoirai/garnet/test/ClientNetworkSpec.kt`

Place a configured recorder block on the server, right-click it from the client, observe `RecorderScreen` opens with the correct origin.

Inline the BE setup rather than depending on the gametest sourceset's `placeRecorderBE`. The setup is straightforward; cross-sourceset reuse for one call site isn't worth the refactor.

- [ ] **Step 1: Replace the scaffold test with the UC-NET-01.c case**

In `ClientNetworkSpec.kt`, replace the existing `test("scaffold: ...")` block with:

```kotlin
    // Helper: places a recorder block + BE at `pos` with the given fields.
    // Inlined here because `placeRecorderBE` lives in the gametest sourceset.
    fun placeRecorderInWorld(
        pos: BlockPos,
        specId: String,
        structureId: String,
    ) {
        currentWorld().getServer().runOnServer(
            object : org.apache.commons.lang3.function.FailableConsumer<net.minecraft.server.MinecraftServer, RuntimeException> {
                override fun accept(server: net.minecraft.server.MinecraftServer) {
                    val level = server.overworld()
                    level.setBlock(pos, com.breadmoirai.garnet.ModRegistries.GARNET_RECORDER_BLOCK.defaultBlockState(), 2)
                    val be = level.getBlockEntity(pos) as com.breadmoirai.garnet.block.SpecBlockEntity
                    be.setSpecId(specId)
                    be.setStructure(structureId)
                    be.setSpecBounds(net.minecraft.core.Vec3i(3, 3, 3))
                }
            }
        )
    }

    test("UC-NET-01.c: rightClick recorder opens RecorderScreen with originPos") {
        val ctx = clientContext()
        val world = currentWorld()
        val pos = BlockPos(100, 64, 100)
        placeRecorderInWorld(pos, specId = "demo", structureId = "demo")

        SpecTestContext(ctx, world).rightClickBlock(pos)
        ctx.waitForScreen(RecorderScreen::class.java)

        val screen = net.minecraft.client.Minecraft.getInstance().screen as RecorderScreen
        screen.originPos shouldBe pos

        // Cleanup so later tests start from a known state.
        ctx.getInput().pressKey(org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE)
        ctx.waitFor { mc -> mc.screen == null }
    }
```

Add the imports at the top of the file (merge with existing imports — do not duplicate):

```kotlin
import com.breadmoirai.garnet.client.screen.RecorderScreen
import io.kotest.matchers.shouldBe
```

Remove the now-unused `shouldNotBe` import and the `OpenRunnerScreenS2C` / `RunnerScreen` imports if the scaffold was the only consumer.

- [ ] **Step 2: Run client gametest**

```
cmd.exe /c "gradlew.bat :26.1:runClientTest"
```

Expected: test count = previous + 0 (we replaced the scaffold, didn't add). All tests pass. The new test name appears in the Kotest summary.

**Risk:** `SpecTestContext.rightClickBlock` chooses an item from the hotbar if any slot is non-empty. The integrated test world's default player starts with an empty inventory, so this should hit the `useWithoutItem` path. If the test fails because some other block-state action runs first (e.g., the wrong screen opens), inspect `rightClickBlock` and consider clearing the hotbar before the call.

- [ ] **Step 3: Commit**

```
git add src/clientTest/kotlin/com/breadmoirai/garnet/test/ClientNetworkSpec.kt
git commit -m "test(client): UC-NET-01.c recorder open via real flow

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Task 5: UC-NET-03.e — `RunnerStatusS2C` origin guard

**Files:**
- Modify: `src/clientTest/kotlin/com/breadmoirai/garnet/test/ClientNetworkSpec.kt`

Two tests covering positive and negative origin match. Both open a `RunnerScreen` first via `sendOpenRunnerScreen`, then assert `pushStatus` is/isn't applied.

- [ ] **Step 1: Append UC-NET-03.e tests**

After the UC-NET-01.c test inside the spec body, append:

```kotlin
    test("UC-NET-03.e: matching originPos updates RunnerScreen.statusText") {
        val ctx = clientContext()
        val pos = BlockPos(110, 64, 100)
        RunnerScreen.active = null
        sendOpenRunnerScreen(OpenRunnerScreenS2C(pos, "", emptyList(), null))
        ctx.waitForScreen(RunnerScreen::class.java)

        sendRunnerStatus(RunnerStatusS2C(pos, RunnerState.PASS, "All good"))
        ctx.waitFor({ mc -> RunnerScreen.active?.statusText == "All good" }, 40)

        RunnerScreen.active?.statusState shouldBe RunnerState.PASS

        ctx.getInput().pressKey(org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE)
        ctx.waitFor { mc -> mc.screen == null }
        RunnerScreen.active = null
    }

    test("UC-NET-03.e: mismatched originPos does not update RunnerScreen status") {
        val ctx = clientContext()
        val activePos = BlockPos(120, 64, 100)
        val otherPos = BlockPos(120, 64, 200)
        RunnerScreen.active = null
        sendOpenRunnerScreen(OpenRunnerScreenS2C(activePos, "", emptyList(), null))
        ctx.waitForScreen(RunnerScreen::class.java)
        val initialStatus = RunnerScreen.active!!.statusText
        val initialState = RunnerScreen.active!!.statusState

        sendRunnerStatus(RunnerStatusS2C(otherPos, RunnerState.FAIL, "Should be ignored"))
        ctx.waitTicks(5)

        RunnerScreen.active?.statusText shouldBe initialStatus
        RunnerScreen.active?.statusState shouldBe initialState

        ctx.getInput().pressKey(org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE)
        ctx.waitFor { mc -> mc.screen == null }
        RunnerScreen.active = null
    }
```

Add imports (merge with existing):

```kotlin
import com.breadmoirai.garnet.client.screen.RunnerScreen
import com.breadmoirai.garnet.network.OpenRunnerScreenS2C
import com.breadmoirai.garnet.network.RunnerState
import com.breadmoirai.garnet.network.RunnerStatusS2C
```

- [ ] **Step 2: Run client gametest**

```
cmd.exe /c "gradlew.bat :26.1:runClientTest"
```

Expected: both new tests pass. The negative test waits 5 ticks after the mismatched send and asserts no field changed.

- [ ] **Step 3: Commit**

```
git add src/clientTest/kotlin/com/breadmoirai/garnet/test/ClientNetworkSpec.kt
git commit -m "test(client): UC-NET-03.e RunnerStatusS2C origin guard

Two cases: matching origin updates statusText/statusState;
mismatched origin leaves both fields unchanged.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Task 6: UC-NET-04.a — `OverwritePromptS2C` opens `ConfirmScreen`

**Files:**
- Modify: `src/clientTest/kotlin/com/breadmoirai/garnet/test/ClientNetworkSpec.kt`

Synthetic send (no producer in recorder/runner code paths) → assert `ConfirmScreen` opens with the right labels. Click-handler half stays out of scope (documented).

- [ ] **Step 1: Append UC-NET-04.a test**

After the UC-NET-03.e tests in the spec body, append:

```kotlin
    test("UC-NET-04.a: OverwritePromptS2C opens ConfirmScreen with specId in message") {
        val ctx = clientContext()
        val pos = BlockPos(130, 64, 100)
        sendOverwritePrompt(OverwritePromptS2CPayload(pos, "demo"))
        ctx.waitForScreen(net.minecraft.client.gui.screens.ConfirmScreen::class.java)

        val screen = net.minecraft.client.Minecraft.getInstance().screen as net.minecraft.client.gui.screens.ConfirmScreen
        screen.title.string shouldContain "Blocks found"
        screen.message.string shouldContain "demo"

        ctx.getInput().pressKey(org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE)
        ctx.waitFor { mc -> mc.screen == null }
    }
```

Add imports (merge with existing):

```kotlin
import com.breadmoirai.garnet.network.OverwritePromptS2CPayload
import io.kotest.matchers.string.shouldContain
```

- [ ] **Step 2: Run client gametest**

```
cmd.exe /c "gradlew.bat :26.1:runClientTest"
```

Expected: all 4 UC-NET client tests pass. Final ClientNetworkSpec test count = 4.

- [ ] **Step 3: Commit**

```
git add src/clientTest/kotlin/com/breadmoirai/garnet/test/ClientNetworkSpec.kt
git commit -m "test(client): UC-NET-04.a OverwritePromptS2C opens ConfirmScreen

Receiver-opens-screen half covered. Click->send half is exercised
indirectly by handleOverwriteDecision tests on the server side
(UC-NET-04.b/c) and remains documented as GAP-PARTIAL.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Task 7: Update coverage matrix in `networking.md`

**Files:**
- Modify: `docs/use-cases/networking.md`

Update the 4 client-side rows and footnote section.

- [ ] **Step 1: Read the current matrix and footnotes**

Open `docs/use-cases/networking.md` and locate:
- The coverage matrix at the bottom.
- The footnotes block (currently contains ¹ "Client receiver — deferred", ³ "No producer", ⁴ "be.startRun hang").

- [ ] **Step 2: Update the 4 covered/changed rows**

Replace these specific rows (matching the existing table structure exactly; do not change column widths or other rows):

| UC ID | Old | New |
|---|---|---|
| UC-NET-01.b | `Test: —`, Status: `**GAP** ¹` | `Test: ClientNetworkSpec."UC-NET-01.c: rightClick recorder opens RecorderScreen with originPos"`, Status: `covered` |
| UC-NET-01.c | `Test: —`, Status: `**GAP** ¹` | Same as 01.b above, Status: `covered` |
| UC-NET-03.e | `Test: —`, Status: `**GAP** ¹` | `Test: ClientNetworkSpec."UC-NET-03.e: matching originPos updates RunnerScreen.statusText"`, `ClientNetworkSpec."UC-NET-03.e: mismatched originPos does not update RunnerScreen status"`, Status: `covered` |
| UC-NET-04.a | `Test: —`, Status: `**GAP** ³` | `Test: ClientNetworkSpec."UC-NET-04.a: OverwritePromptS2C opens ConfirmScreen with specId in message"`, Status: `**GAP-PARTIAL** ⁵` |

- [ ] **Step 3: Update the footnotes block**

Replace the existing footnotes block with this exact text:

```markdown
**Footnotes:**

⁴ `RUN` happy-path and "Already running" are deferred: `SpecBlockEntity.startRun` launches an unbounded BE-scoped coroutine without a `RecordingHolder`, which hangs the Kotest gametest harness. Will be revisited when `handleRunnerCommand` is split or the spec engine gains a synchronous test entry point.

⁵ `OverwritePromptS2C` is not currently emitted by recorder/runner code paths in `NetworkRegistry`; the test sends the payload synthetically to exercise the client receiver. The click→send half — clicking Overwrite/Skip and dispatching `OverwriteDecisionC2SPayload` — is exercised indirectly by `handleOverwriteDecision` tests (UC-NET-04.b/c) which verify the C2S side. Full coverage would require a real producer in recorder/runner code plus client-side outbound packet capture infrastructure.
```

Drop footnotes ¹ (no longer referenced) and ³ (superseded by ⁵).

- [ ] **Step 4: First commit (matrix + footnotes; `last_audited_commit` bump comes next)**

```
git add docs/use-cases/networking.md
git commit -m "docs(use-cases): close client-side networking rows

UC-NET-01.b/c and UC-NET-03.e now reference ClientNetworkSpec
tests. UC-NET-04.a moves from GAP to GAP-PARTIAL with footnote
explaining the partial scope. Footnotes 1 and 3 dropped (no
remaining references); new footnote 5 added.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

- [ ] **Step 5: Bump `last_audited_commit:`**

Capture the new HEAD and update the frontmatter:

```
LAST=$(git rev-parse HEAD)
```

Then in `docs/use-cases/networking.md`, change the `last_audited_commit:` value in the frontmatter to that SHA.

- [ ] **Step 6: Second commit (`last_audited_commit` bump)**

```
git add docs/use-cases/networking.md
git commit -m "docs(use-cases): bump networking last_audited_commit

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Task 8: Final verification

**Files:** none

- [ ] **Step 1: Full multi-sourceset build**

```
cmd.exe /c "gradlew.bat :26.1:clientClasses :26.1:classes :26.1:gametestClasses :26.1:clientTestClasses :26.1:testClasses"
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 2: Server-side gametest still green**

```
cmd.exe /c "gradlew.bat :26.1:runGameTest"
```

Expected: `Kotest: All 41 tests passed`. No regression from the previous cycle.

- [ ] **Step 3: Client gametest green with new spec**

```
cmd.exe /c "gradlew.bat :26.1:runClientTest"
```

Expected: Kotest summary shows 5 passing tests total (1 pre-existing `RunGarnetSpecSmokeTest` + 4 new `ClientNetworkSpec` cases).

- [ ] **Step 4: Verify XML reports list the new tests**

```
ls versions/26.1/build/reports/garnet/clientTest/
```

Then inspect the most recent report to confirm `ClientNetworkSpec` is listed with 4 passing tests.

- [ ] **Step 5: No additional commit needed**

If any of Steps 1-3 failed, fix the underlying issue and re-commit; do not amend the prior task's commits.

---

## Self-review notes (for the implementer)

- **`rightClickBlock` hotbar quirk (Task 4):** the helper chooses an inventory item if one exists. The test relies on an empty default inventory. If a future change pre-populates the test player, this test breaks; the fix is to clear the hotbar before the rightClick call.
- **`RunnerScreen.active` is global mutable state (Tasks 3, 5):** each test resets it to `null` at start. The scaffold (Task 3) and Task 5's positive case both rely on this.
- **`WorldHolder` lives in `main`, not `clientTest`:** put it in the `testing/core` package alongside `ClientContextHolder` so it ships with the main artifact (same pattern). Per project memory, `internal` doesn't cross sourcesets — the `object` is public so the clientTest sourceset can read it.
- **`runClientTest` is slow:** budget 1–3 minutes per run. The plan runs it 4 times (Tasks 3, 4, 5, 6) plus once in final verification (Task 8). Consider batching Tasks 4–6 into a single run if cycle time matters; the plan as written runs after each task for tighter feedback.
- **No new mixin accessors:** the spec explicitly punts client-side outbound packet capture. Don't introduce it. The UC-NET-04.a click→send half remains GAP-PARTIAL ⁵.
