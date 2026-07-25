---
title: Networking use-cases
tags: [payloads, sync, authority, use-cases]
summary: C2S/S2C payloads, server-authority enforcement, origin-pos lookup, confirmation handshakes.
last_audited_commit: f97d72daa35ad4551c2591efc6ab88d657b17083
---

# Networking use-cases

Spec content never crosses the wire as JSON. Payloads carry origin-relative coordinates and rely on the server's `originPos` lookup as the single source of authority (see [persistence/network-payload-contract.md](../persistence/network-payload-contract.md)).

These UCs cover the recorder/runner C2S/S2C flow only. Project-dim payloads (`network/project/*`) belong in [redstone-project.md](redstone-project.md).

**UI status:** `RecorderScreen`/`RunnerScreen` were deleted in the Compose-dock hard-cut. The server
still builds and sends `OpenRecorderScreenS2C`/`OpenRunnerScreenS2C`/`RunnerStatusS2C` exactly as
before (UC-NET-01.a still holds server-side); `ClientNetworkHandler`'s receivers for those three
payload types are now **no-ops that only log** (`"recorder UI removed (returns as a panel in
sub-project A/B); ignoring open"`) — no screen opens and no client-side UI state exists to update.
UC-NET-01.b–d and UC-NET-03.e below describe that no-op reality, not a screen.

---

### UC-NET-01 — Client requests recorder screen state from the server (server side is live; client is a no-op)

**Actor:** Player
**Trigger:** Player right-clicks a placed `RedstoneSpecRecorderBlock`; the block's `useWithoutItem` calls `ServerPlayNetworking.send` with `OpenRecorderScreenS2C`.
**Preconditions:** The block entity at the clicked position is a `SpecBlockEntity`; the player is in a `ServerLevel`.
**Outcome:** The server sends `OpenRecorderScreenS2C` exactly as before; the client's registered receiver executes on the main thread and logs that the recorder UI is removed. No screen opens; there is currently no live UI to reach a recorder block's state from the client (see `ui/dock-framework.md` — a future Explorer-adjacent panel is the intended replacement).

**System interactions:**
- UC-NET-01.a — On the server side the block resolves the `SpecBlockEntity` at `blockPos` and builds an `OpenRecorderScreenS2C` payload carrying `originPos`, `specId`, `outPath`, `structureId`, and the current recorder state string. Unchanged by the hard-cut.
- UC-NET-01.b — `ClientNetworkHandler.registerClientNetworking` still registers the `OpenRecorderScreenS2C.TYPE` receiver; on arrival the handler executes on the client main thread via `mc.execute` and only logs — it does not instantiate any screen.
- UC-NET-01.c — No screen is instantiated. `mc.setScreen` is never called for this payload; the payload's fields (`specId`, `outPath`, `structureId`, `state`) are read from the log line only, not surfaced in any widget.
- UC-NET-01.d — No C2S packet is sent to open the screen; the server is still the sole initiator of the `OpenRecorderScreenS2C` send, even though the client now discards it.

**Invariants:** [persistence/network-payload-contract.md](../persistence/network-payload-contract.md) — server is the only writer; client carries no cached spec state.

**Edge cases referenced elsewhere:** UC-NET-04 (disconnect before payload arrives).

---

### UC-NET-02 — Server validates `originPos` and rejects stale or missing block entities

**Actor:** Server
**Trigger:** Any C2S payload arrives (`RunSpecC2SPayload`, `SetRecorderConfigC2S`, `RecorderCommandC2S`, `SetRunnerConfigC2S`, `RunnerCommandC2S`, `SetStructureC2SPayload`, `OverwriteDecisionC2SPayload`).
**Preconditions:** The player's connection is live; the payload has been decoded from the wire without error; the server main-thread executor is available.
**Outcome:** If a `SpecBlockEntity` is found at `payload.originPos` the handler proceeds; if the lookup returns `null` or the BE is the wrong type, the entire handler body is skipped — no error is sent back to the client.

**System interactions:**
- UC-NET-02.a — Every C2S handler inside `NetworkRegistry.registerNetworking` wraps its body in `context.server().execute { … }` to run on the server main thread; lookup races with world unload are impossible inside that executor.
- UC-NET-02.b — The lookup pattern `context.player().level().getBlockEntity(payload.originPos) as? SpecBlockEntity ?: return@execute` is the canonical guard; a broken/replaced block causes the cast to return `null` and the handler exits silently.
- UC-NET-02.c — Block-kind re-validation is applied for state-changing handlers: `SetRecorderConfigC2S` and `RecorderCommandC2S` check `level.getBlockState(payload.originPos).block !is RedstoneSpecRecorderBlock`; `SetRunnerConfigC2S` and `RunnerCommandC2S` check `!is RedstoneSpecRunnerBlock`. This prevents a misrouted packet from mutating a BE that has been transformed to a different block type.
- UC-NET-02.d — The stale-reference outcome is a silent no-op on the server. The client receives no acknowledgment that its payload was discarded; the UI may show a stale state until the player re-opens the block.

**Invariants:** [persistence/network-payload-contract.md](../persistence/network-payload-contract.md) — Invariant 2: every payload carries `originPos`; stale references are silent no-ops.

---

### UC-NET-03 — Server emits S2C confirmation after a state-mutating runner command (client receipt is a no-op)

**Actor:** Server
**Trigger:** A `RunnerCommandC2S` payload arrives with `cmd` equal to `PLACE_STRUCTURE`, `RUN`, or `RESTORE`.
**Preconditions:** The `SpecBlockEntity` at `payload.originPos` passes the block-kind guard (`RedstoneSpecRunnerBlock`); for `RUN`, `SpecPersistence.load` must resolve a `RedstoneSpec` from disk.
**Outcome:** The client receives a `RunnerStatusS2C` payload with an updated `RunnerState` (`IDLE`, `RUNNING`, `PASS`, or `FAIL`) and a human-readable `summary` string; `ClientNetworkHandler` logs it at debug level ("runner status (no UI)"). There is currently no client widget that displays it — `RunnerScreen` (which used to read `active.originPos` and call `pushStatus`) was deleted.

**System interactions:**
- UC-NET-03.a — `PLACE_STRUCTURE`: if `be.isConfigured`, `StructurePersistence.load` places the structure NBT into the world; the server then sends `RunnerStatusS2C(originPos, RunnerState.IDLE, "Structure placed: $structureId")`; if `be.isConfigured` is false, it sends `RunnerState.IDLE` with `"No spec configured"`. Unchanged by the hard-cut — this is entirely server-side.
- UC-NET-03.b — `RUN` (pre-launch): the server immediately sends `RunnerStatusS2C(originPos, RunnerState.RUNNING, "Running…")` before calling `be.startRun`, providing instant feedback while the spec is still loading. If `SpecPersistence.load` returns null, `RunnerState.FAIL` is sent instead.
- UC-NET-03.c — `RUN` (already in flight): if `be.startRun` returns `false`, an additional `RunnerStatusS2C(RunnerState.RUNNING, "Already running")` is sent.
- UC-NET-03.d — `RESTORE`: if `be.isConfigured`, `SpecSnapshot.capture` snapshots the bounds region and `snapshot.restore` resets it; the server sends `RunnerState.IDLE, "Snapshot restored"`.
- UC-NET-03.e — `ClientNetworkHandler` delivers `RunnerStatusS2C` on the client main thread but no longer reads `RunnerScreen.active` (deleted) or calls `pushStatus`; it only logs `state`/`summary` at debug level. The `originPos`-matching guard that used to prevent cross-block status corruption no longer applies because there is no concurrently-open screen state to corrupt.

**Invariants:** [persistence/network-payload-contract.md](../persistence/network-payload-contract.md) — server is the only writer; the client applies no local `RunnerState` mutation at all (no screen to mutate).

---

### UC-NET-04 — Overwrite-prompt confirmation handshake

**Actor:** Player
**Trigger:** A C2S action (loading a structure from file) causes the server to find non-air blocks inside the spec bounds; the server halts the load and issues an `OverwritePromptS2CPayload` to the player.
**Preconditions:** The `SpecBlockEntity` is at a valid `originPos`; the bounds region contains at least one non-air block; the player's connection is live when the prompt payload is dispatched.
**Outcome:** The player sees a `ConfirmScreen` ("Overwrite existing blocks with structure?"). Their yes/no choice is returned to the server as `OverwriteDecisionC2SPayload`; on "yes" the server clears the bounds region with `StructurePersistence.clearBounds` then calls `StructurePersistence.load` to place the structure; on "no" the load is skipped and the world is unchanged.

**System interactions:**
- UC-NET-04.a — `ClientNetworkHandler` registers the `OverwritePromptS2CPayload.TYPE` receiver; on arrival it calls `mc.setScreen` with a `ConfirmScreen` whose `BooleanConsumer` closes the screen and sends `OverwriteDecisionC2SPayload(payload.originPos, overwrite)`.
- UC-NET-04.b — The `OverwriteDecisionC2SPayload` handler on the server performs the `SpecBlockEntity` lookup via the standard `originPos` guard (UC-NET-02); if the BE is gone when the decision arrives, the decision is silently dropped.
- UC-NET-04.c — On `overwrite = true` the server calls `StructurePersistence.clearBounds(level, be.blockPos, be.specBounds)` followed by `StructurePersistence.load`; both calls happen on the server main thread in the same `execute` block.
- UC-NET-04.d — If the player disconnects after `OverwritePromptS2CPayload` is sent but before `OverwriteDecisionC2SPayload` arrives, the server never receives the decision; the structure is neither cleared nor placed, leaving the world in its pre-prompt state.

**Invariants:** [persistence/network-payload-contract.md](../persistence/network-payload-contract.md) — Authority handshakes section; the two-step overwrite is one of two flows that require client confirmation.

---

### UC-NET-05 — Server rejects unauthorized or misrouted C2S commands

**Actor:** Server
**Trigger:** A C2S payload arrives for a `RecorderCommandC2S` or `RunnerCommandC2S` whose `originPos` resolves to a block that is not of the expected type (e.g., a `RunnerCommandC2S` for a position holding a `RedstoneSpecRecorderBlock`).
**Preconditions:** A `SpecBlockEntity` exists at `payload.originPos`; the block-kind check fails because the block type does not match the expected handler.
**Outcome:** The handler returns early without executing any mutation; no S2C feedback is sent; the world state is unchanged.

**System interactions:**
- UC-NET-05.a — `RecorderCommandC2S` handler checks `if (level.getBlockState(payload.originPos).block !is RedstoneSpecRecorderBlock) return@execute` before dispatching `RecorderCmd.START`, `STOP`, or `DISCARD`.
- UC-NET-05.b — `RunnerCommandC2S` handler checks `if (serverLevel.getBlockState(payload.originPos).block !is RedstoneSpecRunnerBlock) return@execute` before dispatching `RunnerCmd.PLACE_STRUCTURE`, `RUN`, or `RESTORE`.
- UC-NET-05.c — Because the `SpecBlockEntity` is shared across recorder and runner block types, omitting the block-kind check would allow a recorder command to fire on a runner BE (and vice versa); the check is the sole enforcement boundary.
- UC-NET-05.d — No permission or ownership check is performed beyond the block-kind guard and the `originPos` lookup; any connected player can issue a command to any reachable BE. Out-of-range command filtering is not yet implemented.

**Invariants:** [persistence/network-payload-contract.md](../persistence/network-payload-contract.md) — Invariant 2: block kind is re-validated for state-changing transforms.

---

## Coverage matrix

| UC ID | Description | Test | Status |
|---|---|---|---|
| UC-NET-01 | Client requests recorder screen state from server (client receipt is now a no-op) | `ClientNetworkSpec."UC-NET-01.c: OpenRecorderScreenS2C is a no-op (recorder UI removed)"` | covered |
| UC-NET-01.a | Server resolves BE and builds `OpenRecorderScreenS2C` payload | `RecorderRunnerNetworkRegistrySpec."UC-NET-01.a: server build of OpenRecorderScreenS2C carries BE fields"` | covered |
| UC-NET-01.b | `ClientNetworkHandler` registers `OpenRecorderScreenS2C.TYPE` receiver | `ClientNetworkSpec."UC-NET-01.c: OpenRecorderScreenS2C is a no-op (recorder UI removed)"` (registration implicit in the no-op receipt) | covered |
| UC-NET-01.c | Handler receives the payload and only logs; no screen is opened | `ClientNetworkSpec."UC-NET-01.c: OpenRecorderScreenS2C is a no-op (recorder UI removed)"` | covered |
| UC-NET-01.d | Server is initiator of the send; no C2S packet starts the flow | `RecorderRunnerNetworkRegistrySpec."UC-NET-01.a: server build of OpenRecorderScreenS2C carries BE fields"` (verified structurally inside .a) | covered |
| UC-NET-02 | Server validates `originPos` and rejects stale or missing block entities | covered by .a–.d | covered |
| UC-NET-02.a | Every C2S handler wraps body in `context.server().execute { … }` | covered structurally by all UC-NET tests in `RecorderRunnerNetworkRegistrySpec` | covered |
| UC-NET-02.b | `as? SpecBlockEntity ?: return@execute` canonical guard on null BE | `RecorderRunnerNetworkRegistrySpec."UC-NET-02.b: handleRecorderCommand on null BE is a silent no-op"` | covered |
| UC-NET-02.c | Block-kind re-validation for recorder vs runner commands | covered by `RecorderRunnerNetworkRegistrySpec."UC-NET-05.a: ..."`, `"UC-NET-05.b: ..."` | covered |
| UC-NET-02.d | Stale reference is silent no-op; client receives no acknowledgment | `RecorderRunnerNetworkRegistrySpec."UC-NET-02.d: handleRunnerCommand on null BE sends no S2C ack"` | covered |
| UC-NET-03 | Server emits S2C confirmation after state-mutating runner command | covered by .a–.d | covered |
| UC-NET-03.a | `PLACE_STRUCTURE`: places structure NBT and sends `RunnerStatusS2C(IDLE, …)` | `RecorderRunnerNetworkRegistrySpec."UC-NET-03.a: PLACE_STRUCTURE configured sends RunnerStatusS2C(IDLE, 'Structure placed: ...'"` and `"UC-NET-03.a: PLACE_STRUCTURE not-configured sends RunnerStatusS2C(IDLE, 'No spec configured')"` | covered |
| UC-NET-03.b | `RUN` pre-launch: immediately sends `RUNNING` then starts run | `RecorderRunnerNetworkRegistrySpec."UC-NET-03.b: RUN with missing spec sends RunnerStatusS2C(FAIL, 'Spec file not found: ...'"`, `"UC-NET-03.b/c: RUN sends 'Running…' then 'Already running' when slot is in-flight"` | covered |
| UC-NET-03.c | `RUN` already in flight: sends `RUNNING, "Already running"` | `RecorderRunnerNetworkRegistrySpec."UC-NET-03.b/c: RUN sends 'Running…' then 'Already running' when slot is in-flight"` | covered |
| UC-NET-03.d | `RESTORE`: snapshots region, restores, sends `IDLE, "Snapshot restored"` | `RecorderRunnerNetworkRegistrySpec."UC-NET-03.d: RESTORE configured sends RunnerStatusS2C(IDLE, 'Snapshot restored')"` and `"UC-NET-03.d: RESTORE not-configured sends RunnerStatusS2C(IDLE, 'No spec configured')"` | covered |
| UC-NET-03.e | `ClientNetworkHandler` delivers `RunnerStatusS2C` and `OpenRunnerScreenS2C`; both are no-ops (runner UI removed) | `ClientNetworkSpec."UC-NET-03.e: OpenRunnerScreenS2C and RunnerStatusS2C are no-ops (runner UI removed)"` | covered |
| UC-NET-04 | Overwrite-prompt confirmation handshake | covered by .b–.d | covered |
| UC-NET-04.a | `OverwritePromptS2CPayload` handler opens `ConfirmScreen` whose `BooleanConsumer` sends `OverwriteDecisionC2SPayload` | `ClientNetworkSpec."UC-NET-04.a: OverwritePromptS2C opens ConfirmScreen"`, `"UC-NET-04.a: clicking Overwrite sends OverwriteDecisionC2SPayload(true)"`, `"UC-NET-04.a: clicking Skip Structure sends OverwriteDecisionC2SPayload(false)"` | covered |
| UC-NET-04.b | `OverwriteDecisionC2SPayload` handler performs `originPos` guard before acting | `RecorderRunnerNetworkRegistrySpec."UC-NET-04.b: handleOverwriteDecision on null BE is a silent no-op"` | covered |
| UC-NET-04.c | `overwrite = true`: `clearBounds` then `StructurePersistence.load` on server thread | `RecorderRunnerNetworkRegistrySpec."UC-NET-04.c: overwrite=true clears bounds and loads structure"` and `"UC-NET-04.c: overwrite=false leaves world unchanged and does not load structure"` | covered |
| UC-NET-04.d | Player disconnect before decision: structure neither cleared nor placed | covered structurally by `"UC-NET-04.b: ..."` (handler not invoked → world unchanged) | covered |
| UC-NET-05 | Server rejects unauthorized or misrouted C2S commands | covered by .a–.d | covered |
| UC-NET-05.a | `RecorderCommandC2S` handler checks block-kind and returns early on mismatch | `RecorderRunnerNetworkRegistrySpec."UC-NET-05.a: RecorderCommand on a runner block is a silent no-op"` | covered |
| UC-NET-05.b | `RunnerCommandC2S` handler checks block-kind and returns early on mismatch | `RecorderRunnerNetworkRegistrySpec."UC-NET-05.b: RunnerCommand on a recorder block is a silent no-op"` | covered |
| UC-NET-05.c | Shared BE type makes block-kind check the sole enforcement boundary | covered by `"UC-NET-05.a: ..."` + `"UC-NET-05.b: ..."` together | covered |
| UC-NET-05.d | No permission or ownership check beyond block-kind guard | `RecorderRunnerNetworkRegistrySpec."UC-NET-05.d: any player can issue commands to any reachable BE (no permission check)"` | covered |

**Footnotes:**

³ No `OverwritePromptS2C` producer exists in the recorder/runner registry today; only project/structure load paths emit it. UC-NET-04.a's tests send the payload synthetically.

<!-- footnote ⁴ retired 2026-05-11: tryClaimRun test seam on SpecBlockEntity unblocks UC-NET-03.b/c coverage. See docs/superpowers/specs/2026-05-11-networking-final-gaps-design.md -->

<!-- footnote ⁵ retired 2026-05-11: drainClientPayloads via ClientCommonPacketListenerImplAccessor unblocks UC-NET-04.a click→send coverage. See docs/superpowers/specs/2026-05-11-networking-final-gaps-design.md -->

<!-- footnote ⁶ retired 2026-05-10: ClientSpec + FabricTestThreadPump fix the multi-screen client test architecture. See docs/gametest/client-test-threading.md -->
