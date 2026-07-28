---
title: Client-test helpers polish — split file + doc fixes
date: 2026-05-11
status: draft
---

# Client-test helpers polish

## Goal

Two small cleanups on the client-test infrastructure shipped over the prior cycles:

1. **Split `ClientNetworkTestSupport.kt`** so its name matches its contents. The file accreted non-network helpers (`onClient`, `runOnClient`, `waitForClientScreen`, `closeClientScreen`, `takeClientScreenshot`, `waitClientTicks`) during the rapid iteration of the multi-cycle networking effort. Move those into a new `ClientTestSupport.kt`. The remaining `ClientNetworkTestSupport.kt` keeps the send helpers and `drainClientPayloads`.
2. **Fix stale or misleading KDoc** in several places. No code-behavior change.

Same-package resolution handles the move: both files live in `package com.breadmoirai.garnet.test`, so call sites (`ClientNetworkSpec`, `RunGarnetSpecSmokeTest`) need no import edits.

## File split

**New `src/clientTest/kotlin/com/breadmoirai/garnet/test/ClientTestSupport.kt`** (general client-test helpers; not network-specific):

- `clientContext(): ClientGameTestContext` and `currentWorld(): TestSingleplayerContext` (the holder accessors).
- `onClient { mc -> ... }` and `runOnClient { mc -> ... }` (render-thread hop via pump + `ctx.computeOnClient`/`runOnClient`).
- `waitForClientScreen(class, timeoutMs)` — polls `mc.screen`.
- `closeClientScreen(timeoutMs)` — `mc.setScreen(null)` + poll.
- `takeClientScreenshot(name)` — captures via pump.
- `waitClientTicks(ticks)` — sleep wrapper.

Each function moves with its existing KDoc; doc fixes (next section) apply at the same time.

**Slimmed `src/clientTest/kotlin/com/breadmoirai/garnet/test/ClientNetworkTestSupport.kt`** (network only):

- `sendToLocalPlayer` (private) + its `import java.util.concurrent.CountDownLatch`.
- `sendOpenRecorderScreen` / `sendOpenRunnerScreen` / `sendRunnerStatus` / `sendOverwritePrompt`.
- `CLIENT_PAYLOAD_INTERCEPTOR` constant, `capturedClientPayloads` list, and `drainClientPayloads()`.

Imports trim down to only what's needed (network payload types, `McDispatchers`, `ServerPlayNetworking`, the mixin accessors). `FabricTestThreadPump` and `ClientContextHolder` move out (consumed by `ClientTestSupport.kt` instead — `drainClientPayloads` still uses `onClient` which is now in the other file but same package).

## Doc fixes

Five tightening edits. All in the new/moved files; no logic change.

### 1. `sendToLocalPlayer` KDoc (in `ClientNetworkTestSupport.kt`)

Current text references `GarnetTestSpec` and claims "we are usually already on the server thread". That's no longer true — consumers extend `ClientSpec`, which dispatches onto `Dispatchers.Default`, not the server thread. The same-thread fast path is effectively dead code today but stays as a safety net.

New text:

> Synchronously sends an S2C payload to the integrated server's first overworld player.
>
> Hops to the server thread via `MinecraftServer.execute` and waits on a `CountDownLatch`. The `isSameThread` fast path is a safety net for callers that already happen to be on the server thread (e.g. a future `GarnetTestSpec`-based caller); `ClientSpec` runs test bodies on `Dispatchers.Default`, so in normal use we always take the post-and-wait path.

### 2. `onClient` / `runOnClient` ordering and KDoc (in `ClientTestSupport.kt`)

Currently the file has a long block KDoc above `runOnClient` that describes `onClient`, plus a separate one-liner KDoc on `runOnClient`. Two adjacent doc blocks for one function is a refactor leftover.

Reorder so `onClient` comes first (more common in test code), each with its own correct KDoc:

```kotlin
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
fun <T> onClient(action: (net.minecraft.client.Minecraft) -> T): T { ... }

/** Runs [action] on the render thread, ignoring any return value. */
fun runOnClient(action: (net.minecraft.client.Minecraft) -> Unit) { ... }
```

### 3. `waitForClientScreen` KDoc (in `ClientTestSupport.kt`)

Current KDoc says "Returns the live screen". The function returns `Unit`.

New text:

> Polls the client's current screen until it is an instance of [screenClass], or until [timeoutMs] elapses. Throws on timeout. To inspect the screen afterward, fetch it explicitly via `onClient { mc -> mc.screen }` — direct access from the calling thread is unsafe.

### 4. `ClientSpec` KDoc (in `src/main/.../testing/ClientSpec.kt`)

Current text: "Test bodies run on the Kotest worker thread by default, not the server thread."

Misleading — we explicitly install `Dispatchers.Default` in the factory, which is a kotlinx-coroutines pool, not the Kotest worker. The Kotest worker thread launches the coroutine but the body itself runs on a Default dispatcher thread.

New text:

> Test bodies run on `Dispatchers.Default` (a kotlinx-coroutines pool), not on the server thread. This is the key difference from [GarnetTestSpec], which is server-thread-first (correct for gametest, wrong for client tests):
>
> - A worker-pool thread can sleep freely without blocking server ticks or client ticks, so polling helpers like `waitForClientScreen` work without deadlock.
> - Server-side mutations hop explicitly via `onServer { … }`.
> - Calls that already wrap themselves (e.g. `runGarnetSpec`) switch threads internally — call them directly.

### 5. `waitClientTicks` doc tightening (in `ClientTestSupport.kt`)

Current comment claims `Thread.sleep` "Acts as a memory barrier so subsequent field reads see recent writes." This overstates the JMM guarantee — we're not relying on a memory-barrier semantic, just letting the test thread tick the client a few times.

New text (replaces the inline comment):

```kotlin
fun waitClientTicks(ticks: Int) {
    // Each MC tick is ~50ms; sleep for n*50ms plus a small margin. The actual
    // tick advancement is driven by `ClientTestSentinel`'s `context.waitTick()`
    // loop on the Fabric test thread; we just yield long enough for those ticks
    // to land before proceeding.
    Thread.sleep(ticks * 50L + 50L)
}
```

## Out of scope

- `FabricTestThreadPump.runOnTestThread` timeout. Caller blocks forever if the drain never runs. Risk is low (sentinel always drains while running), so leave it.
- Unifying `WorldHolder` and `ClientContextHolder`. They have the same shape but different types. Refactor not worth the churn.
- `@Suppress("UnstableApiUsage")` placement (currently per-function on the holders). Could be file-level. Cosmetic only.

## Verification

After the split + edits:

1. Full build (`:26.1:clientClasses :26.1:classes :26.1:gametestClasses :26.1:clientTestClasses :26.1:testClasses`) BUILD SUCCESSFUL.
2. `runGameTest` reports 42/42.
3. `runClientTest` reports 6/6.

No behavior change is expected from either the split or the doc fixes; passing test counts confirm.
