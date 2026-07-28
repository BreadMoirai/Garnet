# Client-test helpers polish — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Split `ClientNetworkTestSupport.kt` into `ClientTestSupport.kt` (general client-test helpers) plus a slimmed `ClientNetworkTestSupport.kt` (network-only helpers), and fix five misleading/stale KDoc blocks. No behavior change.

**Architecture:** Both files stay in `package com.breadmoirai.garnet.test`, so call sites need zero import edits — Kotlin same-package resolution covers it. The split is mechanical (move declarations and their required imports); the doc fixes are localized text edits.

**Tech Stack:** Kotlin, Fabric API, Stonecutter, Kotest.

**Spec:** [`docs/superpowers/specs/2026-05-11-client-test-helpers-polish-design.md`](../specs/2026-05-11-client-test-helpers-polish-design.md).

**Build / test commands:**

```
cmd.exe /c "gradlew.bat :26.1:clientClasses :26.1:classes :26.1:gametestClasses :26.1:clientTestClasses :26.1:testClasses"
cmd.exe /c "gradlew.bat :26.1:runGameTest"
cmd.exe /c "gradlew.bat :26.1:runClientTest"
```

---

## Task 1: Create `ClientTestSupport.kt` and remove its declarations from `ClientNetworkTestSupport.kt`

**Files:**
- Create: `src/clientTest/kotlin/com/breadmoirai/garnet/test/ClientTestSupport.kt`
- Modify: `src/clientTest/kotlin/com/breadmoirai/garnet/test/ClientNetworkTestSupport.kt`

Move the 8 non-network helpers (`clientContext`, `currentWorld`, `onClient`, `runOnClient`, `waitForClientScreen`, `closeClientScreen`, `takeClientScreenshot`, `waitClientTicks`) out of `ClientNetworkTestSupport.kt` and into a new `ClientTestSupport.kt`. Apply the documentation fixes from the spec at the same time (doc fixes #2, #3, #5 land in the new file; doc fixes #1 and #4 land in other files in later tasks).

- [ ] **Step 1: Verify clean baseline build**

```
cmd.exe /c "gradlew.bat :26.1:clientClasses :26.1:classes :26.1:gametestClasses :26.1:clientTestClasses :26.1:testClasses"
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 2: Create `ClientTestSupport.kt`**

Create `src/clientTest/kotlin/com/breadmoirai/garnet/test/ClientTestSupport.kt` with the following content:

```kotlin
package com.breadmoirai.garnet.test

import com.breadmoirai.garnet.testing.core.ClientContextHolder
import com.breadmoirai.garnet.testing.core.FabricTestThreadPump
import com.breadmoirai.garnet.testing.core.WorldHolder
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext

/** Active `ClientGameTestContext` installed by `ClientTestSentinel`. */
@Suppress("UnstableApiUsage")
fun clientContext(): ClientGameTestContext = ClientContextHolder.context

/** Active `TestSingleplayerContext` installed by `ClientTestSentinel`. */
@Suppress("UnstableApiUsage")
fun currentWorld(): TestSingleplayerContext = WorldHolder.world

/**
 * Hops to the render thread (via the Fabric test thread + `ctx.runOnClient`) to
 * evaluate [action] with a safe `Minecraft` reference, and returns the result.
 *
 * Fabric instruments `Minecraft.getInstance()` to throw if called from anywhere
 * other than the render thread. From a Kotest worker, route every `Minecraft`
 * field access through this helper.
 *
 * Nullable returns are routed through a holder because Fabric's `computeOnClient`
 * is typed `<T : Any>`; we use `runOnClient` + a captured array instead.
 */
@Suppress("UNCHECKED_CAST")
fun <T> onClient(action: (net.minecraft.client.Minecraft) -> T): T {
    val holder = arrayOf<Any?>(null)
    FabricTestThreadPump.runOnTestThread { ctx ->
        ctx.runOnClient<RuntimeException> { mc -> holder[0] = action(mc) }
    }
    return holder[0] as T
}

/** Runs [action] on the render thread, ignoring any return value. */
fun runOnClient(action: (net.minecraft.client.Minecraft) -> Unit) {
    FabricTestThreadPump.runOnTestThread { ctx ->
        ctx.runOnClient<RuntimeException> { mc -> action(mc) }
    }
}

/**
 * Polls the client's current screen until it is an instance of [screenClass], or
 * until [timeoutMs] elapses. Throws on timeout. To inspect the screen afterward,
 * fetch it explicitly via `onClient { mc -> mc.screen }` — direct access from the
 * calling thread is unsafe.
 */
fun waitForClientScreen(
    screenClass: Class<out net.minecraft.client.gui.screens.Screen>,
    timeoutMs: Long = 5000,
) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        val matched = onClient { mc -> screenClass.isInstance(mc.screen) }
        if (matched) return
        Thread.sleep(50)
    }
    val current = onClient { mc -> mc.screen?.javaClass?.simpleName }
    error("Timed out after ${timeoutMs}ms waiting for screen ${screenClass.simpleName}; current is ${current ?: "null"}")
}

/**
 * Closes the active screen by hopping to the render thread and calling
 * `mc.setScreen(null)`. Polls until the change is observed.
 */
fun closeClientScreen(timeoutMs: Long = 5000) {
    onClient { mc -> mc.setScreen(null) }
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        if (onClient { mc -> mc.screen } == null) return
        Thread.sleep(50)
    }
    val current = onClient { mc -> mc.screen?.javaClass?.simpleName }
    error("Timed out after ${timeoutMs}ms waiting for client screen to clear; current is $current")
}

/**
 * Takes a screenshot of the current client state by hopping to the Fabric test
 * thread (where `ctx.takeScreenshot` is legal). Returns the file path written.
 * Useful for diagnosing test-time UI state — store the path or open the file
 * after the test run. See `docs/gametest/screenshots-for-debug-and-regression.md`.
 */
fun takeClientScreenshot(name: String): java.nio.file.Path =
    FabricTestThreadPump.runOnTestThread { ctx -> ctx.takeScreenshot(name) }

/**
 * Sleeps the calling thread for [ticks] MC ticks' worth of wall time (~50ms each)
 * plus a small margin. Used when the test needs to let the client process pending
 * render-thread work (e.g. an `mc.execute`-posted state change) before checking.
 *
 * The actual tick advancement is driven by `ClientTestSentinel`'s `context.waitTick()`
 * loop on the Fabric test thread; this helper just yields long enough for those
 * ticks to land before the caller proceeds.
 */
fun waitClientTicks(ticks: Int) {
    Thread.sleep(ticks * 50L + 50L)
}
```

The KDoc for `onClient`, `runOnClient`, `waitForClientScreen`, and `waitClientTicks` reflects spec fixes #2, #3, and #5 in the design doc.

- [ ] **Step 3: Remove the moved declarations from `ClientNetworkTestSupport.kt`**

Edit `src/clientTest/kotlin/com/breadmoirai/garnet/test/ClientNetworkTestSupport.kt`. Delete:

- The two functions `clientContext()` and `currentWorld()` and their KDocs (currently lines 16–22).
- Both function declarations and their KDocs for `onClient` and `runOnClient` (currently lines 149–175). Also delete the orphaned KDoc block that sits above `runOnClient` (lines 149–156).
- The `waitForClientScreen` function and its KDoc (currently lines 177–194).
- The `closeClientScreen` function and its KDoc (currently lines 196–209).
- The `takeClientScreenshot` function and its KDoc (currently lines 211–218).
- The `waitClientTicks` function and its KDoc (currently lines 220–231).

Also remove these imports — they're no longer needed in this file:

- `import com.breadmoirai.garnet.testing.core.ClientContextHolder`
- `import com.breadmoirai.garnet.testing.core.WorldHolder`
- `import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext`
- `import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext`

Keep `import com.breadmoirai.garnet.testing.core.FabricTestThreadPump` — it's still used by `drainClientPayloads`.

After the edit, the file's top-level declarations are:
- `private fun sendToLocalPlayer(...)`
- `fun sendOpenRecorderScreen(payload)`
- `fun sendOpenRunnerScreen(payload)`
- `fun sendRunnerStatus(payload)`
- `fun sendOverwritePrompt(payload)`
- `private const val CLIENT_PAYLOAD_INTERCEPTOR`
- `private val capturedClientPayloads`
- `fun drainClientPayloads()`

- [ ] **Step 4: Verify build**

```
cmd.exe /c "gradlew.bat :26.1:clientTestClasses"
```

Expected: BUILD SUCCESSFUL. If the build fails with "unresolved reference" on `onClient` (or any other moved function), check that both files are in `package com.breadmoirai.garnet.test` so same-package resolution works.

- [ ] **Step 5: Run client gametest**

```
cmd.exe /c "gradlew.bat :26.1:runClientTest"
```

Expected: `Kotest: All 6 tests passed`. No behavior change from the split, just file reorganization.

- [ ] **Step 6: Commit**

```
git add src/clientTest/kotlin/com/breadmoirai/garnet/test/ClientTestSupport.kt \
        src/clientTest/kotlin/com/breadmoirai/garnet/test/ClientNetworkTestSupport.kt
git commit -m "refactor(test): split client-test helpers into ClientTestSupport

Move the 8 non-network helpers (clientContext, currentWorld, onClient,
runOnClient, waitForClientScreen, closeClientScreen, takeClientScreenshot,
waitClientTicks) out of ClientNetworkTestSupport.kt into a new
ClientTestSupport.kt. Both files stay in the same Kotlin package, so
call sites need no import edits.

Also tightens four KDoc blocks during the move (onClient/runOnClient
ordering and content, waitForClientScreen 'returns the live screen'
correction, waitClientTicks 'memory barrier' overstatement). No
behavior change — 6/6 client tests still pass.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Task 2: Fix `sendToLocalPlayer` KDoc

**Files:**
- Modify: `src/clientTest/kotlin/com/breadmoirai/garnet/test/ClientNetworkTestSupport.kt`

The KDoc on `sendToLocalPlayer` references `GarnetTestSpec` and claims callers "are usually already on the server thread". Neither is true now — consumers extend `ClientSpec`, which dispatches onto `Dispatchers.Default`. The same-thread fast path stays as a safety net.

- [ ] **Step 1: Replace the KDoc**

In `ClientNetworkTestSupport.kt`, find the existing block:

```kotlin
/**
 * Synchronously sends an S2C payload to the integrated server's first overworld
 * player.
 *
 * `GarnetTestSpec` dispatches test bodies onto the server thread via
 * `withContext(McDispatchers.Server)`, so by the time this helper runs we are
 * usually already on the server thread; in that case we call [action] inline.
 * If we somehow end up off-thread (e.g., a future spec that detaches), fall
 * back to `server.execute` + a latch.
 */
private fun sendToLocalPlayer(action: (net.minecraft.server.MinecraftServer) -> Unit) {
```

Replace with:

```kotlin
/**
 * Synchronously sends an S2C payload to the integrated server's first overworld
 * player.
 *
 * Hops to the server thread via `MinecraftServer.execute` and waits on a
 * `CountDownLatch`. The `isSameThread` fast path is a safety net for callers
 * that already happen to be on the server thread (e.g. a future
 * `GarnetTestSpec`-based caller); `ClientSpec` runs test bodies on
 * `Dispatchers.Default`, so in normal use we always take the post-and-wait path.
 */
private fun sendToLocalPlayer(action: (net.minecraft.server.MinecraftServer) -> Unit) {
```

- [ ] **Step 2: Verify build**

```
cmd.exe /c "gradlew.bat :26.1:clientTestClasses"
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```
git add src/clientTest/kotlin/com/breadmoirai/garnet/test/ClientNetworkTestSupport.kt
git commit -m "docs(test): fix stale sendToLocalPlayer KDoc

Consumers now extend ClientSpec (Dispatchers.Default), not
GarnetTestSpec (server thread), so the same-thread fast path is
effectively a safety net for hypothetical future GarnetTestSpec
callers rather than the normal path. Update the doc to say so.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Task 3: Fix `ClientSpec` KDoc

**Files:**
- Modify: `src/main/kotlin/com/breadmoirai/garnet/testing/ClientSpec.kt`

The KDoc says "Test bodies run on the Kotest worker thread by default". The factory actually installs `Dispatchers.Default`. The Kotest worker thread is what launches the coroutine, but the body runs on a Default dispatcher thread.

- [ ] **Step 1: Replace the KDoc**

In `src/main/kotlin/com/breadmoirai/garnet/testing/ClientSpec.kt`, find the current class KDoc:

```kotlin
/**
 * Base class for Kotest specs in the `clientTest` source set.
 *
 * Test bodies run on the Kotest worker thread by default, not the server thread.
 * This is the key difference from [GarnetTestSpec], which is server-thread-first
 * (correct for gametest, wrong for client tests):
 *
 * - The worker thread can sleep freely without blocking server ticks or client
 *   ticks, so polling helpers like `waitForClientScreen` work without deadlock.
 * - Server-side mutations hop explicitly via `onServer { … }`.
 * - Calls that already wrap themselves (e.g. `runGarnetSpec`) switch threads
 *   internally — call them directly.
 *
 * A `RecordingHolder` is installed in the coroutine context so `runGarnetSpec`
 * can attach the recording on completion (same as `GarnetTestSpec`).
 *
 * `ClientGameTestContext.waitForScreen` / `waitFor` / `waitTick` still assert the
 * Fabric test thread and will throw if called from a Kotest spec running on the
 * worker thread. Use the polling helpers in `ClientNetworkTestSupport.kt` instead.
 */
abstract class ClientSpec(body: ClientSpec.() -> Unit = {}) : FunSpec() {
```

Replace with:

```kotlin
/**
 * Base class for Kotest specs in the `clientTest` source set.
 *
 * Test bodies run on `Dispatchers.Default` (a kotlinx-coroutines pool), not on
 * the server thread. This is the key difference from [GarnetTestSpec], which
 * is server-thread-first (correct for gametest, wrong for client tests):
 *
 * - A worker-pool thread can sleep freely without blocking server ticks or
 *   client ticks, so polling helpers like `waitForClientScreen` work without
 *   deadlock.
 * - Server-side mutations hop explicitly via `onServer { … }`.
 * - Calls that already wrap themselves (e.g. `runGarnetSpec`) switch threads
 *   internally — call them directly.
 *
 * A `RecordingHolder` is installed in the coroutine context so `runGarnetSpec`
 * can attach the recording on completion (same as `GarnetTestSpec`).
 *
 * `ClientGameTestContext.waitForScreen` / `waitFor` / `waitTick` still assert
 * the Fabric test thread and will throw if called from a `ClientSpec` test body.
 * Use the polling helpers in `ClientTestSupport.kt` instead.
 */
abstract class ClientSpec(body: ClientSpec.() -> Unit = {}) : FunSpec() {
```

Two substantive changes:
- First paragraph: "Kotest worker thread" → "`Dispatchers.Default` (a kotlinx-coroutines pool)".
- Bottom paragraph: reference `ClientTestSupport.kt` instead of `ClientNetworkTestSupport.kt` (helpers moved in Task 1).

- [ ] **Step 2: Verify build**

```
cmd.exe /c "gradlew.bat :26.1:classes :26.1:clientTestClasses"
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```
git add src/main/kotlin/com/breadmoirai/garnet/testing/ClientSpec.kt
git commit -m "docs(test): correct ClientSpec dispatcher KDoc

Body runs on Dispatchers.Default, not on the Kotest worker thread.
Also update the cross-reference to point at ClientTestSupport.kt
(helpers moved out of ClientNetworkTestSupport.kt in the previous
commit).

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Task 4: Final verification

**Files:** none

Sanity checks after the three commits.

- [ ] **Step 1: Full multi-sourceset build**

```
cmd.exe /c "gradlew.bat :26.1:clientClasses :26.1:classes :26.1:gametestClasses :26.1:clientTestClasses :26.1:testClasses"
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 2: Server gametest (no regression)**

```
cmd.exe /c "gradlew.bat :26.1:runGameTest"
```

Expected: `Kotest: All 42 tests passed`.

- [ ] **Step 3: Client gametest (no regression)**

```
cmd.exe /c "gradlew.bat :26.1:runClientTest"
```

Expected: `Kotest: All 6 tests passed`.

- [ ] **Step 4: No additional commit needed**

If any step failed, diagnose and fix with a new commit (do not amend).

---

## Self-review notes

- **No behavior change** — both gametests must pass with the same counts as before (42 server, 6 client). If either fails, the split or doc rewrite broke something.
- **No cross-package import edits expected** — both files in `package com.breadmoirai.garnet.test`, so `ClientNetworkSpec` and `RunGarnetSpecSmokeTest` need no changes. If the compile fails with "unresolved reference", check package declarations first.
- **Out of scope per the spec**: `FabricTestThreadPump` timeout, `WorldHolder`/`ClientContextHolder` unification, `@Suppress("UnstableApiUsage")` placement. Don't include those in this pass.
