---
title: Client-test threading and the one-screen-swap limit
tags: [testing, client-gametest, threading, harness]
summary: Why a `clientTest` Kotest spec can drive at most one screen-open packet per `runClientTest` invocation.
---

# Client-test threading and the one-screen-swap limit

`clientTest` specs run under a layered threading model whose constraints aren't obvious until you write a test that hits them. This article documents what is true and what doesn't work, so the next person doesn't spend two hours bisecting.

## The threads in play

- **Fabric test thread.** The thread `FabricClientGameTest.runTest` is invoked on. Methods like `ClientGameTestContext.waitTick`, `waitFor`, `waitForScreen`, and `TestSingleplayerContext.runOnServer` assert via `ThreadingImpl.checkOnGametestThread(...)` and throw `IllegalStateException` from any other thread.
- **Kotest worker thread.** `ClientTestSentinel.runKotestOnWorker` spawns a daemon (`redstonespecs-kotest-worker`) and runs `launchKotest` on it. The Fabric test thread loops on `context.waitTick()` until the worker signals done. This is the only way to drive client ticks while a Kotest spec executes — calling `launchKotest` synchronously from the Fabric test thread would block tick advancement and deadlock any suspending primitive.
- **Server thread.** `RedstoneTestSpec` installs a `coroutineDispatcherFactory` that wraps every test body in `withContext(McDispatchers.Server + RecordingHolder())`. By the time test code runs, the coroutine is dispatched onto the integrated server thread (not the Kotest worker).
- **Render thread.** Minecraft's main thread. Updates `Minecraft.getInstance().screen`; drains `mc.execute(...)` between render frames.

## What this means for test code

Inside a `clientTest` `RedstoneTestSpec`:

- You are on the **server thread**, not the worker, and not the Fabric test thread.
- Server-side things work directly: `level.setBlock(...)`, `level.getBlockEntity(...)`, `ServerPlayNetworking.send(player, payload)` — call them without an `onServer { }` wrapper.
- `ClientGameTestContext.waitForScreen` / `waitFor` / `waitTick` and `TestSingleplayerContext.runOnServer` **throw** — they assert the Fabric test thread.
- `SpecTestContext.rightClickBlock` and any helper that internally calls `runOnServer` therefore also throws. Bypass with direct server-thread work.
- To observe client-side state (e.g., `mc.screen`), poll the field directly with `Thread.sleep(50)` between checks. The Fabric test thread keeps ticking the client in the sentinel's loop, so polled fields eventually see updates from `mc.execute`.

The polling pattern lives in `ClientNetworkTestSupport.kt` as `waitForClientScreen(class, timeoutMs)`.

## The one-screen-swap limit

When a test body sends an S2C packet via `ServerPlayNetworking.send(player, OpenXScreenS2C(...))`, the local network channel hands the packet to the client. The client-side receiver does `mc.execute { mc.setScreen(NewScreen(...)) }`. The render thread eventually drains the queue and the new screen appears.

This works the **first** time. After that, it doesn't.

Specifically: when a previous test left a non-null `mc.screen`, a subsequent test that sends an open-screen packet and polls for the new screen class will time out. `mc.screen` stays as the previous screen for the entire poll window (5+ seconds, far more than the ~50ms a successful swap takes).

The mechanism we believe is responsible: the local in-memory network channel needs both server- and client-side pumping to deliver. While the test body holds the server thread to poll, the server can't drain its half of the channel, so the open-screen packet never crosses to the client receiver. `mc.execute` therefore never gets enqueued, and the render thread has nothing to drain. Switching the poll to `withContext(Dispatchers.IO)` was tried and did not unblock it within a reasonable timeout — the underlying server-pump dependency persists past a single dispatcher hop, or it's something else entirely.

## What works today

- One screen-opening test per `ClientTestSentinel` invocation. `mc.screen` starts null; the first packet's `setScreen` lands; assertions pass.
- Tests that don't open client screens — pure server-side flows, or assertions on packet outcomes that don't involve UI — are not affected.

`ClientNetworkSpec` ships exactly one screen-opening test (UC-NET-01.c). Attempts to add UC-NET-03.e and UC-NET-04.a in the same spec stall on the swap; both rows stay GAP with a footnote in `docs/use-cases/networking.md`.

## What would unblock multi-screen client coverage

Two known options, neither implemented:

1. **One `FabricClientGameTest` entrypoint per case.** Each case runs in its own world, with its own Kotest run, starting from a null `mc.screen`. Heavyweight: full client boot per case (~30s each).
2. **Drive UI assertions from the Fabric test thread.** Keep server-side setup in the Kotest worker (or in a `withContext(McDispatchers.Server)`), but hand UI polling work back to the test thread via some coordination channel. Avoids re-booting the client, but requires either a custom `RedstoneTestSpec` variant or a different sentinel design.

If you need multi-screen coverage soon, option 1 is the path of least surprise. If a third client-test feature lands, building option 2 is probably worth it.

## See also

- `docs/gametest/kotest-bridge.md` — the `RedstoneTestSpec` dispatcher and `awaitTicks`/`onServer` cookbook (server-side).
- `docs/gametest/spec-test-context.md` — fixture helpers and the quirks they paper over.
- `src/clientTest/.../ClientTestSentinel.kt` — the worker-spawning loop.
- `src/clientTest/.../ClientNetworkTestSupport.kt` — `waitForClientScreen` polling helper.
