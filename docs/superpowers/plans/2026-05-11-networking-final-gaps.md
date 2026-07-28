# Networking final GAP rows — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close UC-NET-03.b/c and UC-NET-04.a click→send in `docs/use-cases/networking.md`, retiring footnotes ⁴ and ⁵.

**Architecture:** Add a `tryClaimRun()` / `releaseRunClaim()` test seam to `SpecBlockEntity` so gametest can force the "Already running" branch without launching the engine coroutine. For client-side, add a `ClientCommonPacketListenerImplAccessor` mixin mirroring the existing server-side `ServerCommonPacketListenerImplAccessor` and a `drainClientPayloads()` helper to assert outbound C2S packets.

**Tech Stack:** Kotlin, Fabric API, Minecraft 1.26.1, Stonecutter, Kotest, Mixin.

**Spec:** [`docs/superpowers/specs/2026-05-11-networking-final-gaps-design.md`](../specs/2026-05-11-networking-final-gaps-design.md).

**Build commands:**

```
cmd.exe /c "gradlew.bat :26.1:clientClasses :26.1:classes :26.1:gametestClasses :26.1:clientTestClasses :26.1:testClasses"
cmd.exe /c "gradlew.bat :26.1:runGameTest"
cmd.exe /c "gradlew.bat :26.1:runClientTest"
```

---

## Task 1: `tryClaimRun` / `releaseRunClaim` on `SpecBlockEntity`

**Files:**
- Modify: `src/main/kotlin/com/breadmoirai/garnet/block/SpecBlockEntity.kt`

Add two public methods that expose the `inFlightRuns` slot lifecycle. `startRun` is rewritten to call `tryClaimRun()` instead of the inline `inFlightRuns.add(blockPos)`. No production behavior change.

- [ ] **Step 1: Verify clean build**

```
cmd.exe /c "gradlew.bat :26.1:clientClasses :26.1:classes :26.1:gametestClasses :26.1:clientTestClasses :26.1:testClasses"
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 2: Read the current `startRun` body**

Read `src/main/kotlin/com/breadmoirai/garnet/block/SpecBlockEntity.kt` lines 165–190. Confirm the body matches the spec's quoted snippet.

- [ ] **Step 3: Replace `startRun` and add the new methods**

Find this block (lines ~167–190):

```kotlin
/**
 * Launches [runGarnetSpec] on the server thread using the BE's own coroutine scope.
 * Returns false if a run is already in flight for this BE (debounce).
 */
fun startRun(dslSpec: com.breadmoirai.garnet.dsl.GarnetSpec, serverLevel: ServerLevel): Boolean {
    if (!inFlightRuns.add(blockPos)) {
        LOGGER.debug("[SpecBlockEntity#startRun] '{}' already running, ignoring", dslSpec.id)
        return false
    }
    coroutineScope.launch(McDispatchers.Server) {
        try {
            LOGGER.info("[SpecBlockEntity#startRun] launching '{}' at {}", dslSpec.id, blockPos)
            runGarnetSpec(serverLevel, blockPos, dslSpec)
            LOGGER.info("[SpecBlockEntity#startRun] '{}' PASSED", dslSpec.id)
        } catch (e: AssertionError) {
            LOGGER.warn("[SpecBlockEntity#startRun] '{}' FAILED: {}", dslSpec.id, e.message)
        } catch (t: Throwable) {
            LOGGER.error("[SpecBlockEntity#startRun] '{}' crashed unexpectedly", dslSpec.id, t)
        } finally {
            inFlightRuns.remove(blockPos)
        }
    }
    return true
}
```

Replace with:

```kotlin
/**
 * Atomically marks this BE as having a run in flight. Returns false if a run is
 * already in flight for this BE. Public so gametest specs can force the
 * "already running" branch of [startRun] without actually launching the engine.
 */
fun tryClaimRun(): Boolean = inFlightRuns.add(blockPos)

/**
 * Releases the in-flight claim. Test seam for gametest cleanup — production
 * releases happen in the launched coroutine's finally block.
 */
fun releaseRunClaim(): Boolean = inFlightRuns.remove(blockPos)

/**
 * Launches [runGarnetSpec] on the server thread using the BE's own coroutine scope.
 * Returns false if a run is already in flight for this BE (debounce).
 */
fun startRun(dslSpec: com.breadmoirai.garnet.dsl.GarnetSpec, serverLevel: ServerLevel): Boolean {
    if (!tryClaimRun()) {
        LOGGER.debug("[SpecBlockEntity#startRun] '{}' already running, ignoring", dslSpec.id)
        return false
    }
    coroutineScope.launch(McDispatchers.Server) {
        try {
            LOGGER.info("[SpecBlockEntity#startRun] launching '{}' at {}", dslSpec.id, blockPos)
            runGarnetSpec(serverLevel, blockPos, dslSpec)
            LOGGER.info("[SpecBlockEntity#startRun] '{}' PASSED", dslSpec.id)
        } catch (e: AssertionError) {
            LOGGER.warn("[SpecBlockEntity#startRun] '{}' FAILED: {}", dslSpec.id, e.message)
        } catch (t: Throwable) {
            LOGGER.error("[SpecBlockEntity#startRun] '{}' crashed unexpectedly", dslSpec.id, t)
        } finally {
            releaseRunClaim()
        }
    }
    return true
}
```

Note: `inFlightRuns` stays a companion-object private set; only the two new methods plus `startRun` access it.

- [ ] **Step 4: Verify build**

```
cmd.exe /c "gradlew.bat :26.1:clientClasses :26.1:classes :26.1:gametestClasses :26.1:clientTestClasses :26.1:testClasses"
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```
git add src/main/kotlin/com/breadmoirai/garnet/block/SpecBlockEntity.kt
git commit -m "refactor(block): extract tryClaimRun/releaseRunClaim from startRun

Makes the inFlightRuns lifecycle explicitly callable so gametest specs
can force the 'already running' branch without launching the engine
coroutine. No production behavior change — startRun still calls
tryClaimRun and the launched coroutine still calls releaseRunClaim in
its finally block.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Task 2: UC-NET-03.b/c gametest

**Files:**
- Modify: `src/gametest/kotlin/com/breadmoirai/garnet/test/network/RecorderRunnerNetworkRegistrySpec.kt`

Add one combined test asserting both packets that `handleRunnerCommand(RUN)` emits when the slot is pre-claimed.

- [ ] **Step 1: Append the new test before the closing `})` of the spec body**

Insert this test inside the `RecorderRunnerNetworkRegistrySpec({ ... })` block, after the existing `UC-NET-03.b: RUN with missing spec ...` test and before the `UC-NET-03.d` tests:

```kotlin
    test("UC-NET-03.b/c: RUN sends 'Running…' then 'Already running' when slot is in-flight") {
        withTempRoot("net-uc03bc") {
            onServer {
                val player = makeMockServerPlayer(this)
                val level = this.overworld()
                val pos = BlockPos(1156, 64, 1000)
                placeRunnerBE(level, pos, specId = "demo")
                val dir = saveDir(this)
                java.nio.file.Files.createDirectories(dir)
                writeStub(dir, "demo")
                val be = level.getBlockEntity(pos) as com.breadmoirai.garnet.block.SpecBlockEntity
                be.tryClaimRun()
                drainPayloads(player)

                try {
                    handleRunnerCommand(this, player, RunnerCommandC2S(pos, RunnerCmd.RUN))

                    val statuses = drainPayloads(player).filterIsInstance<RunnerStatusS2C>()
                    statuses.size shouldBe 2
                    statuses[0].state shouldBe RunnerState.RUNNING
                    statuses[0].summary shouldBe "Running…"
                    statuses[1].state shouldBe RunnerState.RUNNING
                    statuses[1].summary shouldBe "Already running"
                } finally {
                    be.releaseRunClaim()
                    java.nio.file.Files.deleteIfExists(dir.resolve("demo.spec.kts"))
                }
            }
        }
    }
```

If `writeStub` is not already imported in this file, add the import to the existing import block (do not duplicate):

```kotlin
import com.breadmoirai.garnet.test.managed.writeStub
```

Same for `saveDir` and `placeRunnerBE` if not present — verify by grepping the file. (Per memory the prior cycle dropped `writeStub` after Task 6 of the earlier plan, so it's likely absent again now.)

- [ ] **Step 2: Run gametest**

```
cmd.exe /c "gradlew.bat :26.1:runGameTest"
```

Expected: `Kotest: All 42 tests passed` (was 41; +1 new test).

If the test fails with "expected 2, got 1", check whether `placeRunnerBE` configured the BE such that `isConfigured` returned true and `SpecPersistence.load` for `"demo"` actually loads the stub — both are required for `handleRunnerCommand` to reach the second send.

- [ ] **Step 3: Commit**

```
git add src/gametest/kotlin/com/breadmoirai/garnet/test/network/RecorderRunnerNetworkRegistrySpec.kt
git commit -m "test(network): UC-NET-03.b/c covered via tryClaimRun pre-claim

Pre-claiming the run slot forces handleRunnerCommand to send 'Running…'
then 'Already running' synchronously, with no engine coroutine launched.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Task 3: `ClientCommonPacketListenerImplAccessor` mixin

**Files:**
- Create: `src/client/java/com/breadmoirai/garnet/mixin/client/ClientCommonPacketListenerImplAccessor.java`
- Modify: `src/client/resources/garnet.client.mixins.json`

Mirrors the existing `src/main/java/com/breadmoirai/garnet/mixin/ServerCommonPacketListenerImplAccessor.java`.

- [ ] **Step 1: Create the mixin file**

Create `src/client/java/com/breadmoirai/garnet/mixin/client/ClientCommonPacketListenerImplAccessor.java` with:

```java
package com.breadmoirai.garnet.mixin.client;

import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.network.Connection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ClientCommonPacketListenerImpl.class)
public interface ClientCommonPacketListenerImplAccessor {
    @Accessor("connection")
    Connection garnet$getConnection();
}
```

- [ ] **Step 2: Register the mixin in the client config**

Read `src/client/resources/garnet.client.mixins.json`. The current `client[]` block is:

```json
  "client": [
    "ScrollWheelHandlerMixin",
    "SelectWorldScreenMixin"
  ],
```

Replace with:

```json
  "client": [
    "ClientCommonPacketListenerImplAccessor",
    "ScrollWheelHandlerMixin",
    "SelectWorldScreenMixin"
  ],
```

(Alphabetical ordering; matches the style of `garnet.mixins.json`.)

- [ ] **Step 3: Verify build**

```
cmd.exe /c "gradlew.bat :26.1:clientClasses :26.1:clientTestClasses"
```

Expected: BUILD SUCCESSFUL. The mixin must compile and pass Mixin's annotation validation.

- [ ] **Step 4: Commit**

```
git add src/client/java/com/breadmoirai/garnet/mixin/client/ClientCommonPacketListenerImplAccessor.java \
        src/client/resources/garnet.client.mixins.json
git commit -m "mixin(client): add ClientCommonPacketListenerImplAccessor

Exposes ClientCommonPacketListenerImpl#connection so clientTest specs
can reach the client-side Connection. Mirrors the existing server-side
ServerCommonPacketListenerImplAccessor.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Task 4: `drainClientPayloads` helper

**Files:**
- Modify: `src/clientTest/kotlin/com/breadmoirai/garnet/test/ClientNetworkTestSupport.kt`

Adds the C2S equivalent of `drainPayloads`. Uses the new mixin accessor.

- [ ] **Step 1: Add the helper**

Read `src/clientTest/kotlin/com/breadmoirai/garnet/test/ClientNetworkTestSupport.kt` and append after the existing `sendOverwritePrompt`:

```kotlin
/**
 * Reads all [CustomPacketPayload]s sent FROM the client TO the server since the
 * last call. Mirrors [drainPayloads] for the inverse direction — useful for
 * asserting C2S round-trips triggered by UI interactions (e.g. a ConfirmScreen
 * button click that sends `OverwriteDecisionC2SPayload`).
 *
 * Returns an empty list if no client connection is active or if the channel
 * isn't an [EmbeddedChannel] (single-player integrated mode may use a
 * different channel type — see the spec's risk note).
 */
fun drainClientPayloads(): List<net.minecraft.network.protocol.common.custom.CustomPacketPayload> = onClient { mc ->
    val listener = mc.connection ?: return@onClient emptyList()
    val conn = (listener as com.breadmoirai.garnet.mixin.client.ClientCommonPacketListenerImplAccessor)
        .`garnet$getConnection`()
    val ch = (conn as com.breadmoirai.garnet.mixin.ConnectionAccessor).`garnet$getChannel`()
        as? io.netty.channel.embedded.EmbeddedChannel
        ?: return@onClient emptyList<net.minecraft.network.protocol.common.custom.CustomPacketPayload>()
    val out = mutableListOf<net.minecraft.network.protocol.common.custom.CustomPacketPayload>()
    while (true) {
        val msg = ch.readOutbound<Any>() ?: break
        if (msg is net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket) out.add(msg.payload())
    }
    out
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
git commit -m "test(client): add drainClientPayloads helper

Reads outbound C2S CustomPacketPayloads from Minecraft.getInstance().connection
via ClientCommonPacketListenerImplAccessor + ConnectionAccessor. Returns an
empty list if the channel isn't an EmbeddedChannel — single-player integrated
mode may use a different channel type; the test in the next commit will reveal
the actual runtime type if the cast fails.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Task 5: UC-NET-04.a click → send (Overwrite + Skip)

**Files:**
- Modify: `src/clientTest/kotlin/com/breadmoirai/garnet/test/ClientNetworkSpec.kt`

Two tests, one per ConfirmScreen button.

- [ ] **Step 1: Add imports**

In `ClientNetworkSpec.kt`, add the following to the existing import block (merge, do not duplicate):

```kotlin
import com.breadmoirai.garnet.network.OverwriteDecisionC2SPayload
import io.kotest.matchers.collections.shouldHaveSize
```

(`OverwritePromptS2CPayload` and `ConfirmScreen` should already be imported from the existing screen-open test.)

- [ ] **Step 2: Append the two click tests**

Inside the spec body, after the existing `"UC-NET-04.a: OverwritePromptS2C opens ConfirmScreen"` test and before the closing `})`, add:

```kotlin
    test("UC-NET-04.a: clicking Overwrite sends OverwriteDecisionC2SPayload(true)") {
        val pos = BlockPos(180, 64, 100)
        drainClientPayloads()
        sendOverwritePrompt(OverwritePromptS2CPayload(pos, "demo"))
        waitForClientScreen(ConfirmScreen::class.java)

        com.breadmoirai.garnet.testing.core.FabricTestThreadPump.runOnTestThread { ctx ->
            ctx.clickScreenButton("Overwrite")
        }

        val deadline = System.currentTimeMillis() + 5000
        while (onClient { mc -> mc.screen } != null && System.currentTimeMillis() < deadline) Thread.sleep(50)

        val decisions = drainClientPayloads().filterIsInstance<OverwriteDecisionC2SPayload>()
        decisions shouldHaveSize 1
        decisions[0].originPos shouldBe pos
        decisions[0].overwrite shouldBe true
    }

    test("UC-NET-04.a: clicking Skip Structure sends OverwriteDecisionC2SPayload(false)") {
        val pos = BlockPos(200, 64, 100)
        drainClientPayloads()
        sendOverwritePrompt(OverwritePromptS2CPayload(pos, "demo"))
        waitForClientScreen(ConfirmScreen::class.java)

        com.breadmoirai.garnet.testing.core.FabricTestThreadPump.runOnTestThread { ctx ->
            ctx.clickScreenButton("Skip Structure")
        }

        val deadline = System.currentTimeMillis() + 5000
        while (onClient { mc -> mc.screen } != null && System.currentTimeMillis() < deadline) Thread.sleep(50)

        val decisions = drainClientPayloads().filterIsInstance<OverwriteDecisionC2SPayload>()
        decisions shouldHaveSize 1
        decisions[0].originPos shouldBe pos
        decisions[0].overwrite shouldBe false
    }
```

- [ ] **Step 3: Run client gametest**

```
cmd.exe /c "gradlew.bat :26.1:runClientTest"
```

Expected: `Kotest: All 6 tests passed` (was 4; +2 new tests).

**Fallback if the `EmbeddedChannel` cast fails:** the test will report `expected 1 payload, got 0`. In that case:

1. Add a one-shot diagnostic inside `drainClientPayloads` (above the cast):
   ```kotlin
   println("CLIENT_CHANNEL_TYPE: ${conn::class.qualifiedName} -> ${(conn as com.breadmoirai.garnet.mixin.ConnectionAccessor).`garnet\$getChannel`()::class.qualifiedName}")
   ```
2. Re-run; observe the actual channel class.
3. Adapt `drainClientPayloads` to read outbound from that class (likely `io.netty.channel.local.LocalChannel`: use a `ChannelOutboundHandler` mixin OR poll `LocalChannel`'s `unsafe().outboundBuffer()`).
4. Remove the diagnostic, recommit with a single message explaining the channel-type change.

- [ ] **Step 4: Commit (only after Step 3 green)**

```
git add src/clientTest/kotlin/com/breadmoirai/garnet/test/ClientNetworkSpec.kt
git commit -m "test(client): UC-NET-04.a click->send half via drainClientPayloads

Two tests: clicking Overwrite sends OverwriteDecisionC2SPayload(originPos, true);
clicking Skip Structure sends ...(originPos, false). Together with the existing
screen-open test, these fully cover UC-NET-04.a.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Task 6: Update coverage matrix

**Files:**
- Modify: `docs/use-cases/networking.md`

Update rows for UC-NET-03.b, UC-NET-03.c, and UC-NET-04.a; retire footnotes ⁴ and ⁵; bump `last_audited_commit`.

- [ ] **Step 1: Update UC-NET-03.b row**

Read `docs/use-cases/networking.md`. Find:

```
| UC-NET-03.b | `RUN` pre-launch: immediately sends `RUNNING` then starts run | `RecorderRunnerNetworkRegistrySpec."UC-NET-03.b: RUN with missing spec sends RunnerStatusS2C(FAIL, 'Spec file not found: ...'"` | **GAP-PARTIAL** ⁴ |
```

Replace with:

```
| UC-NET-03.b | `RUN` pre-launch: immediately sends `RUNNING` then starts run | `RecorderRunnerNetworkRegistrySpec."UC-NET-03.b: RUN with missing spec sends RunnerStatusS2C(FAIL, 'Spec file not found: ...'"`, `"UC-NET-03.b/c: RUN sends 'Running…' then 'Already running' when slot is in-flight"` | covered |
```

- [ ] **Step 2: Update UC-NET-03.c row**

Find:

```
| UC-NET-03.c | `RUN` already in flight: sends `RUNNING, "Already running"` | — | **GAP-PARTIAL** ⁴ |
```

Replace with:

```
| UC-NET-03.c | `RUN` already in flight: sends `RUNNING, "Already running"` | `RecorderRunnerNetworkRegistrySpec."UC-NET-03.b/c: RUN sends 'Running…' then 'Already running' when slot is in-flight"` | covered |
```

- [ ] **Step 3: Update UC-NET-04.a row**

Find:

```
| UC-NET-04.a | `OverwritePromptS2CPayload` handler opens `ConfirmScreen` whose `BooleanConsumer` sends `OverwriteDecisionC2SPayload` | `ClientNetworkSpec."UC-NET-04.a: OverwritePromptS2C opens ConfirmScreen"` (screen-open half; screenshot in `versions/26.1/run/screenshots/`) | **GAP-PARTIAL** ⁵ |
```

Replace with:

```
| UC-NET-04.a | `OverwritePromptS2CPayload` handler opens `ConfirmScreen` whose `BooleanConsumer` sends `OverwriteDecisionC2SPayload` | `ClientNetworkSpec."UC-NET-04.a: OverwritePromptS2C opens ConfirmScreen"`, `"UC-NET-04.a: clicking Overwrite sends OverwriteDecisionC2SPayload(true)"`, `"UC-NET-04.a: clicking Skip Structure sends OverwriteDecisionC2SPayload(false)"` | covered |
```

- [ ] **Step 4: Retire footnotes ⁴ and ⁵**

Find the Footnotes block (after the matrix). Current text is:

```
**Footnotes:**

³ No `OverwritePromptS2C` producer exists in the recorder/runner registry today; only managed/structure load paths emit it. Coverage requires a producer to be wired in `NetworkRegistry` first.

⁴ `RUN` happy-path and "Already running" are deferred: ...

⁵ `OverwritePromptS2C` is not currently emitted by recorder/runner code paths in `NetworkRegistry`; the test sends the payload synthetically to exercise the client receiver. The click→send half ...
```

(Plus the HTML comment for retired footnote ⁶.)

Replace the entire Footnotes block with:

```
**Footnotes:**

³ No `OverwritePromptS2C` producer exists in the recorder/runner registry today; only managed/structure load paths emit it. UC-NET-04.a's tests send the payload synthetically.

<!-- footnote ⁴ retired 2026-05-11: tryClaimRun test seam on SpecBlockEntity unblocks UC-NET-03.b/c coverage. See docs/superpowers/specs/2026-05-11-networking-final-gaps-design.md -->

<!-- footnote ⁵ retired 2026-05-11: drainClientPayloads via ClientCommonPacketListenerImplAccessor unblocks UC-NET-04.a click→send coverage. See docs/superpowers/specs/2026-05-11-networking-final-gaps-design.md -->

<!-- footnote ⁶ retired 2026-05-10: ClientSpec + FabricTestThreadPump fix the multi-screen client test architecture. See docs/gametest/client-test-threading.md -->
```

Note: footnote ³ stays because the `OverwritePromptS2C` producer is still missing (a feature gap, not a test gap). Move its current second sentence about "test sends synthetically" out of the original ⁵ footnote into ³ since they're now overlapping.

- [ ] **Step 5: First commit (matrix + footnotes)**

```
git add docs/use-cases/networking.md
git commit -m "docs(use-cases): close UC-NET-03.b/c and UC-NET-04.a

UC-NET-03.b and 03.c now reference RecorderRunnerNetworkRegistrySpec's
new combined test (forced 'Already running' via tryClaimRun). UC-NET-04.a
references all three ClientNetworkSpec tests (screen open + two click→
send tests). Footnotes 4 and 5 retired; footnote 3 stays (no producer).

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

- [ ] **Step 6: Bump `last_audited_commit`**

Run:

```
git rev-parse HEAD
```

Take the SHA from output. In `docs/use-cases/networking.md`, find the frontmatter line `last_audited_commit: <old-sha>` and replace `<old-sha>` with the SHA you just captured.

- [ ] **Step 7: Second commit (audit bump)**

```
git add docs/use-cases/networking.md
git commit -m "docs(use-cases): bump networking last_audited_commit

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Task 7: Final verification

**Files:** none

- [ ] **Step 1: Full multi-sourceset build**

```
cmd.exe /c "gradlew.bat :26.1:clientClasses :26.1:classes :26.1:gametestClasses :26.1:clientTestClasses :26.1:testClasses"
```

Expected: BUILD SUCCESSFUL across all 5 sourcesets.

- [ ] **Step 2: Server gametest**

```
cmd.exe /c "gradlew.bat :26.1:runGameTest"
```

Expected: `Kotest: All 42 tests passed`.

- [ ] **Step 3: Client gametest**

```
cmd.exe /c "gradlew.bat :26.1:runClientTest"
```

Expected: `Kotest: All 6 tests passed`.

- [ ] **Step 4: No additional commit needed**

If any step failed, fix the underlying issue with a new commit (not amending Task 6's commits).

---

## Self-review notes

- **`tryClaimRun` cleanup discipline (Task 2):** `inFlightRuns` is process-static. The test wraps work in `try/finally` and calls `releaseRunClaim` to keep the BE clean for subsequent tests.
- **Channel type fallback (Task 5):** if the cast in `drainClientPayloads` returns empty, follow the fallback procedure in Task 5 Step 3.
- **Footnote bookkeeping (Task 6):** keep footnote ³ because the producer is genuinely missing in production code. ⁴ and ⁵ become retired HTML comments alongside ⁶ for git-history continuity.
- **No new test base classes or infrastructure.** This cycle reuses what the previous cycles built (`ClientSpec`, `FabricTestThreadPump`, `GarnetTestSpec`, `NetworkTestSupport`).
