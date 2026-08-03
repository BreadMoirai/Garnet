---
title: Client-test threading and the Fabric-test-thread pump
tags: [testing, client-gametest, threading, harness]
summary: Four-thread layering of `clientTest` specs, where to do each kind of work, and how the pump bridges Kotest worker threads to the Fabric test thread.
---

# Client-test threading and the Fabric-test-thread pump

`clientTest` specs run under a four-thread layering. Knowing which thread does what is essential for writing tests that don't deadlock or throw obscure `IllegalStateException`s.

## The threads in play

- **Fabric test thread.** The thread `FabricClientGameTest.runTest` is invoked on. Methods like `ClientGameTestContext.waitTick`, `waitFor`, `waitForScreen`, `takeScreenshot`, and `TestSingleplayerContext.runOnServer` assert via `ThreadingImpl.checkOnGametestThread(...)` and throw `IllegalStateException` from any other thread. To touch `Minecraft.getInstance()`, use `ctx.runOnClient` / `computeOnClient` from this thread; those internally hop to the render thread.
- **Kotest worker thread.** `ClientTestSentinel.runKotestOnWorker` spawns a daemon (`garnet-kotest-worker`) and runs `launchKotest` on it. The Fabric test thread loops on `context.waitTick()` until the worker signals done. This is the only way to drive client ticks while a Kotest spec executes — calling `launchKotest` synchronously from the Fabric test thread would block tick advancement and deadlock any suspending primitive.
- **Server thread.** The integrated server's main loop. `onServer { … }` (`withContext(AsyncDispatchers.Server)`) hops the calling coroutine here.
- **Render thread.** Minecraft's main thread. Owns `Minecraft.getInstance()`, the screen field, and any UI mutation. `ctx.runOnClient`/`computeOnClient` runs work here.

## Where test code runs by default

Two base classes; they dispatch test bodies differently.

| Spec base | Default thread | Use for |
|---|---|---|
| `GarnetTestSpec` | Server thread (via `withContext(AsyncDispatchers.Server)`) | Gametest sourceset — `level.setBlock`, BE queries, `awaitTicks`, etc. |
| `ClientSpec` | Kotest worker thread (no special dispatcher) | ClientTest sourceset — UI assertions, packet round-trips, screenshots. |

A `RecordingHolder` is installed in both, so `runGarnetSpec` works from either base.

`clientTest` specs should extend `ClientSpec`. `GarnetTestSpec` in the clientTest sourceset puts test bodies on the server thread, which prevents the local network channel from pumping (you can't observe client-side screen state when you hold the server thread).

**`ClientSpec`/`runOnClient` are reserved for specs that genuinely drive the client.** Since
2026-08-03, `src/test/` carries `client`'s compile classpath and runs the Compose compiler plugin,
so pure-JVM client-code assertions (Compose snapshot state, `@Composable` code, payload/config
round-trips) belong there instead — see
[unit-vs-gametest-split.md](unit-vs-gametest-split.md). A new test reaching for `ClientSpec` should
first ask whether it actually needs a live `Minecraft` instance, a GL context, or GLFW; if not, it
belongs in `src/test/`, not here.

## Helpers for crossing threads

Split across two same-package files (no imports needed between them):

**`src/clientTest/.../ClientTestSupport.kt`** — general helpers:

- `onServer { server -> … }` — hop to server thread, run work, return the result. Use for `level.setBlock`, `ServerPlayNetworking.send`, etc. (Defined in the testing-core module's `Suspending.kt`; not in this file, but available in scope.)
- `onClient { mc -> … }` — hop to the render thread (via the Fabric test thread + `ctx.runOnClient`), run work with a safe `Minecraft` reference, return the result. Use for ANY `Minecraft.getInstance()` access, including `mc.gui.screen()`, `mc.gui.setScreen(…)`, and reads off screen fields. Nullable returns are routed through an `Any?` holder because Fabric's `computeOnClient` is typed `<T : Any>`.
- `runOnClient { mc -> … }` — same as `onClient` but for `Unit`-returning actions.
- `waitForClientScreen(class, timeoutMs)` — polls `mc.gui.screen()` via `onClient` until it matches, with a wall-clock deadline.
- `closeClientScreen(timeoutMs)` — `mc.gui.setScreen(null)` via `onClient`, then poll until cleared. End every screen-opening test with this — single-player pauses the integrated server when a screen is open, which tangles shutdown.
- `takeClientScreenshot(name)` — `ctx.takeScreenshot(name)` via the pump. Returns the file path (under `versions/26.2/run/screenshots/`). Useful for proving UI state in tests and for debugging. See [screenshots-for-debug-and-regression.md](screenshots-for-debug-and-regression.md).
- `waitClientTicks(ticks)` — sleeps the calling thread for ~`ticks * 50ms`; useful when waiting for render-thread tasks to drain.
- `clientContext()` / `currentWorld()` — accessors for the active `ClientGameTestContext` / `TestSingleplayerContext` (both installed by `ClientTestSentinel`).

**`src/clientTest/.../ClientNetworkTestSupport.kt`** — network-only helpers:

- `sendOpenRecorderScreen` / `sendOpenRunnerScreen` / `sendRunnerStatus` / `sendOverwritePrompt` — synthetic `ServerPlayNetworking.send` to the local player.
- `drainClientPayloads()` — reads outbound C2S `CustomPacketPayload`s captured by an idempotent `ChannelOutboundHandlerAdapter` installed into the integrated server's `LocalChannel` pipeline. Mirror of the server-side `drainPayloads(player)`.

## How the pump works

`FabricTestThreadPump` is a `LinkedBlockingQueue<WorkItem>` that:

- Kotest-worker code adds to via `runOnTestThread`. The caller blocks on the item's `CompletableFuture` until it completes.
- `ClientTestSentinel.runKotestOnWorker` drains between every `context.waitTick()` call, so queued work runs on the Fabric test thread at known tick boundaries.

The drain happens after each tick, which means any worker call to `onClient` adds ~50ms latency in the worst case (one tick wait). For tight polling loops, batch multiple operations into a single `runOnTestThread` closure when possible.

## Common pitfalls

- **`Minecraft.getInstance()` from any thread other than render throws.** Fabric injects a check. The error message tells you to use `ctx.runOnClient`/`computeOnClient`; `onClient` wraps that. Never call `Minecraft.getInstance()` from a `ClientSpec` test body directly — always through `onClient`.
- **Single-player pauses the integrated server when a screen is open.** Any non-null `mc.gui.screen()` triggers `Saving and pausing game…`. The server stops ticking; `context.waitTick()` on the Fabric test thread blocks. Always close screens at the end of each test with `closeClientScreen()`.
- **`runOnServer` / `waitForScreen` / `waitTick` are Fabric-test-thread only.** Calling from the worker or server thread throws. Use the `onClient`/`onServer`/`waitForClientScreen` helpers instead.
- **`computeOnClient` is `<T : Any>` in Java.** Kotlin's `onClient` boxes through a `Any?` holder to allow nullable returns (e.g., reading `mc.gui.screen()` which can be null).

## See also

- `docs/gametest/kotest-bridge.md` — the `GarnetTestSpec` dispatcher and `awaitTicks`/`onServer` cookbook (server-side counterpart).
- `docs/gametest/spec-test-context.md` — `SpecTestContext` helpers (note: those assert the Fabric test thread, so they're only legal inside `onClient`/`runOnClient` closures or via the pump).
- `src/testSupport/kotlin/.../harness/ClientSpec.kt` — base class.
- `src/testSupport/kotlin/.../harness/client/FabricTestThreadPump.kt` — the pump.
- `src/clientTest/.../ClientTestSupport.kt` — general helpers (`onClient`, `runOnClient`, `waitForClientScreen`, `closeClientScreen`, `takeClientScreenshot`, `waitClientTicks`, `clientContext`, `currentWorld`).
- `src/clientTest/.../ClientNetworkTestSupport.kt` — network helpers (`sendX` family + `drainClientPayloads`).
- `src/clientTest/.../ClientNetworkSpec.kt` — example consumer covering UC-NET-01.c / UC-NET-03.e / UC-NET-04.a.
