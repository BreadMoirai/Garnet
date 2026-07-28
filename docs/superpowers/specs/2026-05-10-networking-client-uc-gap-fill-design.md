---
title: Networking client-side UC GAP fill
date: 2026-05-10
status: draft
---

# Networking client-side UC GAP fill

## Goal

Close the 4 client-side rows in `docs/use-cases/networking.md` that were deferred during the server-side cycle (`docs/superpowers/specs/2026-05-10-networking-uc-gap-fill-design.md`): UC-NET-01.b, UC-NET-01.c, UC-NET-03.e, and UC-NET-04.a. Use the existing `clientTest` sourceset and `FabricClientGameTest` harness — no new test infrastructure beyond a small send-helper file.

## Scope

**In scope:**
- UC-NET-01.b/c — full coverage via the real flow: place a configured recorder BE, right-click it, observe `RecorderScreen` opening on the client with the correct `originPos`.
- UC-NET-03.e — full coverage: open `RunnerScreen`, synthetically send `RunnerStatusS2C` with matching and mismatched `originPos`, assert `pushStatus` is called only on match.
- UC-NET-04.a — partial coverage: synthetically send `OverwritePromptS2CPayload`, assert `ConfirmScreen` opens with the expected labels. The click→send half (`BooleanConsumer` sending `OverwriteDecisionC2SPayload`) is left untested at the client wire layer; the server-side `handleOverwriteDecision` is already covered by UC-NET-04.b/c.

**Out of scope:**
- Wiring an actual `OverwritePromptS2C` producer into recorder/runner code. This is a feature change, not gap-fill.
- Adding client-side outbound packet capture infrastructure (would require new mixin accessors mirroring `ServerCommonPacketListenerImplAccessor` / `ConnectionAccessor` for the client connection). Out of scope; would unlock the click→send half of UC-NET-04.a.

## Architecture

### New test-support file

`src/clientTest/kotlin/com/breadmoirai/garnet/test/ClientNetworkTestSupport.kt` provides four payload-send helpers plus two accessors:

```
fun clientContext(): ClientGameTestContext
fun currentWorld(): TestSingleplayerContext
fun sendOpenRecorderScreen(world, payload: OpenRecorderScreenS2C)
fun sendOpenRunnerScreen(world, payload: OpenRunnerScreenS2C)
fun sendRunnerStatus(world, payload: RunnerStatusS2C)
fun sendOverwritePrompt(world, payload: OverwritePromptS2CPayload)
```

Each send helper invokes `world.getServer().runOnServer { server -> server.overworld().players().firstOrNull()?.let { ServerPlayNetworking.send(it, payload) } }`. The shape mirrors `SpecTestContext.rightClickBlock`'s `runOnServer` usage.

`clientContext()` and `currentWorld()` read from `ClientContextHolder`, which `ClientTestSentinel` already installs at the start of the test run.

### New Kotest spec

`src/clientTest/kotlin/com/breadmoirai/garnet/test/ClientNetworkSpec.kt` is a `GarnetTestSpec` with 4 tests (described below). Each test resets `RunnerScreen.active = null` and closes any open screen at the start to avoid bleed from earlier tests.

**Critical:** the spec must be added to `ClientTestSentinel.runKotestOnWorker`'s `specs = listOf(...)` block. The list currently contains only `RunGarnetSpecSmokeTest::class` — autoscan is off (same model as the gametest sentinel), so unregistered specs silently don't run.

### Existing production code under test

`src/client/kotlin/com/breadmoirai/garnet/client/network/ClientNetworkHandler.kt` registers four `ClientPlayNetworking.registerGlobalReceiver` callbacks for `OverwritePromptS2CPayload`, `OpenRecorderScreenS2C`, `OpenRunnerScreenS2C`, and `RunnerStatusS2C`. All callbacks call `mc.execute { ... }` to hop to the client main thread before mutating screen state. The tests observe the screen state via `Minecraft.getInstance().screen` and `RunnerScreen.active` after a `waitForScreen(...)` / `waitTicks(...)` synchronization.

No production-side changes required.

## Test coverage map

Each test name follows `"UC-NET-XX.y: ..."` for matrix traceability.

### UC-NET-01.b/c — recorder open via real flow

One test exercises both rows:

```kotlin
test("UC-NET-01.c: rightClick recorder opens RecorderScreen with originPos") {
    val ctx = clientContext()
    val world = currentWorld()
    val pos = BlockPos(100, 64, 100)
    world.getServer().runOnServer { server ->
        placeRecorderBE(server.overworld(), pos, specId = "demo", structureId = "demo")
    }
    SpecTestContext(ctx, world).rightClickBlock(pos)
    ctx.waitForScreen(RecorderScreen::class.java)
    val screen = Minecraft.getInstance().screen as RecorderScreen
    screen.originPos shouldBe pos
}
```

Verifies: the server-side `useWithoutItem` → `openScreenFor` → `ServerPlayNetworking.send` flow reaches the client, the `OpenRecorderScreenS2C` receiver runs (UC-NET-01.b implicit), and the handler instantiates `RecorderScreen` with the right origin (UC-NET-01.c).

Note: `placeRecorderBE` is the gametest helper from `NetworkTestSupport.kt`. It is in the gametest sourceset; the clientTest sourceset cannot depend on it directly. Two options during implementation:
- Move `placeRecorderBE` / `placeRunnerBE` to a sourceset shared between gametest and clientTest, or
- Inline the equivalent setup (`level.setBlock` + setSpecId + setStructure + setSpecBounds) directly in the test body.

Decision left to implementation; default to inlining if no shared sourceset exists.

### UC-NET-03.e — `RunnerStatusS2C` originPos guard

Two tests sharing setup.

```kotlin
test("UC-NET-03.e: matching originPos updates RunnerScreen.statusText") {
    val ctx = clientContext()
    val world = currentWorld()
    val pos = BlockPos(110, 64, 100)
    sendOpenRunnerScreen(world, OpenRunnerScreenS2C(pos, "", emptyList(), null))
    ctx.waitForScreen(RunnerScreen::class.java)

    sendRunnerStatus(world, RunnerStatusS2C(pos, RunnerState.PASS, "All good"))
    ctx.waitFor({ RunnerScreen.active?.statusText == "All good" }, 40)
    RunnerScreen.active?.statusState shouldBe RunnerState.PASS
}

test("UC-NET-03.e: mismatched originPos does not update status") {
    val ctx = clientContext()
    val world = currentWorld()
    val activePos = BlockPos(120, 64, 100)
    val otherPos  = BlockPos(120, 64, 200)
    sendOpenRunnerScreen(world, OpenRunnerScreenS2C(activePos, "", emptyList(), null))
    ctx.waitForScreen(RunnerScreen::class.java)
    val initialStatus = RunnerScreen.active!!.statusText

    sendRunnerStatus(world, RunnerStatusS2C(otherPos, RunnerState.FAIL, "Should be ignored"))
    ctx.waitTicks(5)

    RunnerScreen.active?.statusText shouldBe initialStatus
    RunnerScreen.active?.statusState shouldNotBe RunnerState.FAIL
}
```

### UC-NET-04.a — `OverwritePromptS2C` opens `ConfirmScreen`

```kotlin
test("UC-NET-04.a: OverwritePromptS2C opens ConfirmScreen") {
    val ctx = clientContext()
    val world = currentWorld()
    val pos = BlockPos(130, 64, 100)
    sendOverwritePrompt(world, OverwritePromptS2CPayload(pos, "demo"))
    ctx.waitForScreen(ConfirmScreen::class.java)
    val screen = Minecraft.getInstance().screen as ConfirmScreen
    screen.title.string shouldContain "Blocks found"
    screen.message.string shouldContain "demo"
}
```

Partial: the receiver-opens-screen half is covered. The `BooleanConsumer` half (clicking Overwrite → sending `OverwriteDecisionC2SPayload`) is exercised indirectly by UC-NET-04.b/c on the server side, which test what happens when the decision payload arrives.

### Setup hygiene per test

- Each test runs in a `GarnetTestSpec` block on the kotest worker thread; UI work is hopped to the client main thread by Minecraft itself (`mc.execute`).
- Reset state at start: `Minecraft.getInstance().execute { Minecraft.getInstance().setScreen(null) }`; clear `RunnerScreen.active = null`.
- Per-test position offsets stay above y=64 and within the test world's chunk loaded by the integrated server (positions starting at x=100 stay clear of the spawn area).
- After the spec finishes, the world is closed by `ClientTestSentinel.runTest`'s `use { }` block; no per-test cleanup required for the world itself.

## Coverage matrix update

After tests pass, edit `docs/use-cases/networking.md`:

- **UC-NET-01.b** — replace `**GAP** ¹` with `ClientNetworkSpec."UC-NET-01.c: rightClick recorder opens RecorderScreen with originPos"`, Status `covered`.
- **UC-NET-01.c** — same test reference, Status `covered`.
- **UC-NET-03.e** — both `ClientNetworkSpec."UC-NET-03.e: matching originPos updates..."` and `"... mismatched originPos does not update..."`, Status `covered`.
- **UC-NET-04.a** — `ClientNetworkSpec."UC-NET-04.a: OverwritePromptS2C opens ConfirmScreen"`, Status `**GAP-PARTIAL** ⁵`.

Footnote changes:
- **Drop footnote ¹** (client receiver — deferred). No row references it after the update.
- **Drop footnote ³** (no producer wired). Superseded by ⁵.
- **Add footnote ⁵:**
  > `OverwritePromptS2C` is not currently emitted by recorder/runner code paths in `NetworkRegistry`; the test sends the payload synthetically to exercise the client receiver. The `BooleanConsumer` half — clicking Overwrite/Skip and sending `OverwriteDecisionC2SPayload` — is exercised indirectly by `handleOverwriteDecision` tests (UC-NET-04.b/c) which verify the C2S side. Full coverage would require a real producer in recorder/runner code plus client-side outbound packet capture infrastructure.

Final footnote set in the article: ⁴ (be.startRun hang) and ⁵ (UC-NET-04.a partial). Bump `last_audited_commit:` to the new HEAD.

## Risks

1. **`SpecTestContext.rightClickBlock` prefers an item from the hotbar.** Its current logic does `(0 until 9).map { player.inventory.getItem(it) }.firstOrNull { !it.isEmpty }` — if any hotbar slot has an item, it dispatches through `stack.useOn` instead of `useWithoutItem`. The real player joining the test world likely has an empty hotbar, but verify during implementation by checking the test world's defaults. If non-empty, either clear the hotbar before `rightClickBlock`, or modify the helper.

2. **`RunnerScreen.active` is global mutable state.** Cross-test bleed is possible if a prior test leaves a screen open. Mitigation: reset at start of each test.

3. **`ctx.waitFor` and `ctx.waitForScreen` advance ticks via the test thread.** They block until the predicate is true or a timeout (default 100 ticks). If a tested receiver never fires (e.g., the payload TYPE wasn't registered on the client), the test fails with a clear timeout rather than hanging.

4. **`placeRecorderBE` is in the gametest sourceset.** Inline the placement in the test body if the gametest sourceset isn't on the clientTest classpath. The setBlock + setters sequence is 4–5 lines; not worth a shared-sourceset refactor for one caller.

5. **No outbound C2S capture.** Documented gap for UC-NET-04.a's click→send half. Accepted scope cut.

## Deliverables

1. New `src/clientTest/kotlin/com/breadmoirai/garnet/test/ClientNetworkTestSupport.kt` (4 send helpers + 2 accessors).
2. New `src/clientTest/kotlin/com/breadmoirai/garnet/test/ClientNetworkSpec.kt` (4 tests).
3. `ClientNetworkSpec::class` added to `ClientTestSentinel.runKotestOnWorker`'s `specs` list.
4. Updated `docs/use-cases/networking.md` coverage matrix and footnotes; `last_audited_commit:` bumped.
5. Full build verification: `cmd.exe /c "gradlew.bat :26.1:clientClasses :26.1:classes :26.1:gametestClasses :26.1:clientTestClasses :26.1:testClasses"` clean.
6. Server-side gametest still green: `cmd.exe /c "gradlew.bat :26.1:runGameTest"`.
7. Client-test green with new spec: `cmd.exe /c "gradlew.bat :26.1:runClientTest"`. Test count rises from 1 to 5.
