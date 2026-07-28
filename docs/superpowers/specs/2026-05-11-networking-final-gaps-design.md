---
title: Networking final GAP rows — close UC-NET-03.b/c and UC-NET-04.a click→send
date: 2026-05-11
status: draft
---

# Networking final GAP rows

## Goal

Close the two remaining networking GAP-PARTIAL rows in `docs/use-cases/networking.md`:

- **UC-NET-03.b** (RUN happy-path: `RunnerStatusS2C(RUNNING, "Running…")` sent before launch) and **UC-NET-03.c** (`"Already running"` sent when a second RUN arrives for an in-flight BE).
- **UC-NET-04.a click→send**: the `BooleanConsumer` in the receiver's `ConfirmScreen` calls `ClientPlayNetworking.send(OverwriteDecisionC2SPayload(originPos, overwrite))` when the user clicks Overwrite or Skip Structure.

After this cycle, footnotes ⁴ and ⁵ in `networking.md` are retired and the matrix shows all server-and-client-side networking rows covered (UC-NET-01.b/c, UC-NET-02..05, UC-NET-04.a screen-open + click→send).

## Out of scope

- Wiring a recorder/runner-side producer for `OverwritePromptS2CPayload` (footnote ³ row). Still a feature change, not a gap-fill.
- The UC-NET-03 `RUN happy-path → actual coroutine progress` behavior. Production `startRun` launches a fire-and-forget coroutine; we test the synchronous packet sends around it without waiting for the run to complete. The engine's own behavior is exercised by `RunGarnetSpecSmokeTest` and unit tests.

## Architecture

### UC-NET-03.b/c — `tryClaimRun` test seam

Production change in `src/main/kotlin/com/breadmoirai/garnet/block/SpecBlockEntity.kt`. Add two methods on the BE:

```kotlin
/** Atomically marks this BE as having a run in flight. Returns false if one is already in flight. */
fun tryClaimRun(): Boolean = inFlightRuns.add(blockPos)

/** Releases the in-flight claim. Test seam — production releases happen in the launched coroutine's finally block. */
fun releaseRunClaim(): Boolean = inFlightRuns.remove(blockPos)
```

Refactor `startRun`'s opening to call `tryClaimRun()`:

```kotlin
fun startRun(dslSpec: GarnetSpec, serverLevel: ServerLevel): Boolean {
    if (!tryClaimRun()) {
        LOGGER.debug("[SpecBlockEntity#startRun] '{}' already running, ignoring", dslSpec.id)
        return false
    }
    coroutineScope.launch(McDispatchers.Server) { /* ... unchanged ... */ }
    return true
}
```

No other behavior change. The two new methods are intentionally public so the gametest sourceset can call them (Kotlin `internal` doesn't cross sourcesets in this project).

**Why the test-seam approach (vs. injecting a launcher):**
- Mirrors `inFlightRuns`' real role — `tryClaimRun` is the actual atomic gate.
- Production `startRun` stays a single call site for callers.
- Tests get a way to force the "Already running" branch without launching the engine coroutine, which is what made these rows untestable before.

### UC-NET-04.a click→send — client outbound packet capture

Mirror the server-side `drainPayloads(player)` pattern for client → server packets.

**New mixin** `src/client/java/com/breadmoirai/garnet/mixin/client/ClientCommonPacketListenerImplAccessor.java`:

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

Registered in `src/client/resources/garnet.client.mixins.json` under the `client[]` list.

**New helper** in `src/clientTest/kotlin/.../ClientNetworkTestSupport.kt`:

```kotlin
/**
 * Reads all [CustomPacketPayload]s sent from the client to the server since the last call.
 * Mirrors `drainPayloads(player)` for the inverse direction — useful for asserting C2S
 * round-trips triggered by UI interactions.
 *
 * Returns an empty list if no client connection is active or the channel isn't an EmbeddedChannel.
 */
fun drainClientPayloads(): List<CustomPacketPayload> = onClient { mc ->
    val listener = mc.connection ?: return@onClient emptyList()
    val conn = (listener as ClientCommonPacketListenerImplAccessor).`garnet$getConnection`()
    val ch = (conn as ConnectionAccessor).`garnet$getChannel`() as? EmbeddedChannel
        ?: return@onClient emptyList()
    val out = mutableListOf<CustomPacketPayload>()
    while (true) {
        val msg = ch.readOutbound<Any>() ?: break
        if (msg is ServerboundCustomPayloadPacket) out.add(msg.payload())
    }
    out
}
```

**Risk and fallback:** in single-player integrated mode, the client `Connection.channel` may not be an `EmbeddedChannel` (it might be a `LocalChannel` or a memory-channel pair). If the cast fails the helper returns empty, the click→send test fails with `expected 1 payload, got 0`, and we switch the capture strategy to a `Connection.send` mixin or read from `LocalChannel.outboundMessages` directly. Decide during implementation based on the runtime type.

## Test coverage map

### Server-side (gametest)

One test in `RecorderRunnerNetworkRegistrySpec` covering both rows:

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
            val be = level.getBlockEntity(pos) as SpecBlockEntity
            be.tryClaimRun()  // force "in flight"
            drainPayloads(player)

            try {
                handleRunnerCommand(this, player, RunnerCommandC2S(pos, RunnerCmd.RUN))

                val statuses = drainPayloads(player).filterIsInstance<RunnerStatusS2C>()
                statuses shouldHaveSize 2
                statuses[0].state shouldBe RunnerState.RUNNING
                statuses[0].summary shouldBe "Running…"
                statuses[1].state shouldBe RunnerState.RUNNING
                statuses[1].summary shouldBe "Already running"
            } finally {
                be.releaseRunClaim()  // cleanup so subsequent tests start clean
                java.nio.file.Files.deleteIfExists(dir.resolve("demo.spec.kts"))
            }
        }
    }
}
```

Pre-claiming means `be.startRun` returns `false` immediately without launching — both packets are sent synchronously from `handleRunnerCommand`'s code path. The `finally` releases the claim so the next test sees a clean BE.

### Client-side (clientTest)

Two new tests in `ClientNetworkSpec`, one per ConfirmScreen button:

```kotlin
test("UC-NET-04.a: clicking Overwrite sends OverwriteDecisionC2SPayload(true)") {
    val pos = BlockPos(180, 64, 100)
    drainClientPayloads()
    sendOverwritePrompt(OverwritePromptS2CPayload(pos, "demo"))
    waitForClientScreen(ConfirmScreen::class.java)

    FabricTestThreadPump.runOnTestThread { ctx -> ctx.clickScreenButton("Overwrite") }

    // The BooleanConsumer also calls mc.setScreen(null); wait for it to clear.
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

    FabricTestThreadPump.runOnTestThread { ctx -> ctx.clickScreenButton("Skip Structure") }

    val deadline = System.currentTimeMillis() + 5000
    while (onClient { mc -> mc.screen } != null && System.currentTimeMillis() < deadline) Thread.sleep(50)

    val decisions = drainClientPayloads().filterIsInstance<OverwriteDecisionC2SPayload>()
    decisions shouldHaveSize 1
    decisions[0].originPos shouldBe pos
    decisions[0].overwrite shouldBe false
}
```

The existing `"UC-NET-04.a: OverwritePromptS2C opens ConfirmScreen"` test stays — it covers the screen-open half. Each test gets its own `pos` so any cross-test leakage in `drainClientPayloads` (e.g., a stray packet from the earlier test) is harmless to assertions.

## Coverage matrix update

In `docs/use-cases/networking.md`:

- **UC-NET-03.b** → reference both the existing missing-spec test and the new combined test. Status `covered`.
- **UC-NET-03.c** → reference the new combined test. Status `covered`.
- **UC-NET-04.a** → reference the existing screen-open test plus the two new click→send tests. Status `covered`.
- **Footnote ⁴** retired (no row references it after the update).
- **Footnote ⁵** retired (UC-NET-04.a fully covered).
- Bump `last_audited_commit` to the post-implementation HEAD.

## Risks

1. **Client `Connection.channel` is not an `EmbeddedChannel`.** Mitigation noted above. Plan a fallback at implementation time.
2. **`tryClaimRun` cleanup discipline.** `inFlightRuns` is process-static; a leaked claim from one test pollutes the next. The combined test wraps work in `try/finally` with `releaseRunClaim()` to keep the BE clean.
3. **Button label localization.** `ConfirmScreen` uses `Component.literal("Overwrite")` / `Component.literal("Skip Structure")` per `ClientNetworkHandler.kt`; `ctx.clickScreenButton(text)` matches the rendered string. As long as the production code uses `Component.literal`, label matching is locale-stable.
4. **Coroutine scope leaks.** Pre-claiming means `startRun` returns `false` and does NOT launch the coroutine — no leak from this test. (Different from the original `RUN-twice` design, which leaked the first coroutine.)

## Deliverables

1. **Production:** `SpecBlockEntity.tryClaimRun()` + `releaseRunClaim()` (`src/main/`).
2. **Production (client):** `ClientCommonPacketListenerImplAccessor.java` + registration in `garnet.client.mixins.json` (`src/client/`).
3. **Tests (gametest):** new combined UC-NET-03.b/c test in `RecorderRunnerNetworkRegistrySpec`.
4. **Tests (clientTest):** `drainClientPayloads()` helper in `ClientNetworkTestSupport`; two new UC-NET-04.a click tests in `ClientNetworkSpec`.
5. **Docs:** `docs/use-cases/networking.md` matrix updated; footnotes ⁴ and ⁵ retired; `last_audited_commit` bumped.
6. **Verification:** full build + `runGameTest` (target ~42 tests) + `runClientTest` (target 6 tests) both green.
