---
title: Networking UC GAP fill (recorder/runner C2S handlers)
date: 2026-05-10
status: draft
---

# Networking UC GAP fill — recorder/runner C2S handlers

## Goal

Close the server-side `GAP` and `GAP-PARTIAL` rows in `docs/use-cases/networking.md` (parents UC-NET-02 through UC-NET-05, plus UC-NET-01.a/d). Refactor `NetworkRegistry.kt` to expose handler bodies as top-level functions, mirroring `ManagedNetworkRegistry`'s testable shape. Add one Kotest gametest spec covering ~17 cases across ~20 UC sub-rows.

Behavior must not change. This is a test-coverage and refactor cycle.

## Scope

**In scope (server-side):**
- UC-NET-01.a (server builds `OpenRecorderScreenS2C`), UC-NET-01.d (server-initiated screen open).
- UC-NET-02.a/b/c/d (origin guard, server-thread wrap, silent no-op on stale BE).
- UC-NET-03.a/b/c/d (runner status confirmations after `PLACE_STRUCTURE`, `RUN`, `RESTORE`).
- UC-NET-04.b/c/d (overwrite-decision handler — server half).
- UC-NET-05.a/b/c/d (block-kind guards, absence of permission check).

**Deferred to a future client-gametest cycle:**
- UC-NET-01.b, UC-NET-01.c — `ClientNetworkHandler` registration and screen instantiation.
- UC-NET-03.e — client-side `RunnerStatusS2C` delivery and `pushStatus` originPos check.
- UC-NET-04.a — client-side `ConfirmScreen` wiring.

**Not testable until producer exists:**
- UC-NET-04.a (server emit of `OverwritePromptS2CPayload`) — no recorder/runner code path emits this payload today; only managed/structure load paths do. Row stays `GAP` with a footnote.

**Out of scope:**
- Other articles' GAP rows.
- Wiring a new `OverwritePromptS2C` producer.
- Adding permission/ownership enforcement (UC-NET-05.d documents the *absence* of such checks; the test asserts the absence).

## Architecture

### Production refactor (no behavior change)

`src/main/kotlin/com/breadmoirai/redstonespecs/network/NetworkRegistry.kt` is restructured so each C2S handler body becomes a top-level function in the file:

```
fun handleRunSpec(server: MinecraftServer, player: ServerPlayer, payload: RunSpecC2SPayload)
fun handleSetStructure(server: MinecraftServer, player: ServerPlayer, payload: SetStructureC2SPayload)
fun handleOverwriteDecision(server: MinecraftServer, player: ServerPlayer, payload: OverwriteDecisionC2SPayload)
fun handleSetRecorderConfig(server: MinecraftServer, player: ServerPlayer, payload: SetRecorderConfigC2S)
fun handleRecorderCommand(server: MinecraftServer, player: ServerPlayer, payload: RecorderCommandC2S)
fun handleSetRunnerConfig(server: MinecraftServer, player: ServerPlayer, payload: SetRunnerConfigC2S)
fun handleRunnerCommand(server: MinecraftServer, player: ServerPlayer, payload: RunnerCommandC2S)
```

Each function contains exactly the body that today lives inside the `context.server().execute { … }` lambda. The receiver registrations in `registerNetworking()` become one-liners:

```kotlin
ServerPlayNetworking.registerGlobalReceiver(RunnerCommandC2S.TYPE) { payload, context ->
    context.server().execute { handleRunnerCommand(context.server(), context.player(), payload) }
}
```

Why `(server, player, payload)` parameters instead of `Context`: lets tests call handlers directly without constructing a `ServerPlayNetworking.Context`, exactly mirroring `ManagedNetworkRegistry.handleX(server, player, payload)`. The `context.server().execute { … }` wrapping (UC-NET-02.a) stays at the registration site; tests verify it implicitly because they invoke handlers from inside `onServer { }` (already on the server thread).

### Test support

**Helper promotion:**
- New file `src/gametest/kotlin/com/breadmoirai/redstonespecs/test/NetworkTestSupport.kt`. Move from `test/managed/ManagedTestSupport.kt`:
  - `makeMockServerPlayer(server)`
  - `drainPayloads(player)`
  - `deleteRecursively(path)`
  - `withTempRoot(prefix, block)`
- `ManagedTestSupport.kt` keeps managed-only helpers (`writeStub`, `clearCellVolume`).
- Update existing imports in `ManagedNetworkRegistrySpec.kt` and any other callers.

**New BE placement helpers (in `NetworkTestSupport.kt`):**

```
fun placeRecorderBE(level: ServerLevel, pos: BlockPos, specId: String, structureId: String? = null, bounds: Vec3i = Vec3i(3, 3, 3)): SpecBlockEntity
fun placeRunnerBE(level: ServerLevel, pos: BlockPos, specId: String, structureId: String? = null, bounds: Vec3i = Vec3i(3, 3, 3)): SpecBlockEntity
```

Each places the appropriate block, fetches the BE, applies setters (`setSpecId`, `setStructure` if non-null, `setSpecBounds`), and returns the BE. Tests use distinct positions per case to avoid collisions, e.g. `BlockPos(index * 16, 64, 0)`.

### New Kotest spec

`src/gametest/kotlin/com/breadmoirai/redstonespecs/test/network/RecorderRunnerNetworkRegistrySpec.kt` extends `RedstoneTestSpec` and contains the cases below. **Must be registered in `GametestSentinel.runAll`** — autoscan is off; an unregistered spec silently does not run.

## Test coverage map

Each test name uses the convention `"UC-NET-XX.y: <one-line>"` so coverage-matrix backreferences are unambiguous and grep-able.

### UC-NET-01 (server-initiated recorder open)
- **01.a** "server resolves BE and builds OpenRecorderScreenS2C payload" — invoke the server-side recorder open path (`RedstoneSpecRecorderBlock.useWithoutItem` or its extracted helper, depending on what's reachable from a test); drain `OpenRecorderScreenS2C` and assert origin/specId/state match BE.
- **01.d** structurally inside 01.a — the test sends no C2S; the S2C alone proves server-initiated. Add a single comment in the test pointing this out; no separate test method.

### UC-NET-02 (origin guard)
- **02.a** "context.server().execute wrap is registered" — assert by structural inspection of `registerNetworking()` (a tiny test that checks each lambda's body is the one-liner `handleX(...)` form via reflection or grep is overkill; instead, this row is verified by the *fact* that all other UC-NET-02..05 tests pass — they invoke `handleX` from `onServer { }` and observe correct behavior). Mark this row as `GAP-PARTIAL` covered structurally.
- **02.b** "null BE → silent return" — call `handleRecorderCommand` with `originPos` at AIR; assert `drainPayloads` is empty and no exception thrown.
- **02.c** verified by UC-NET-05.a/b.
- **02.d** "stale ref → no S2C ack" — same as 02.b, explicit `drainPayloads(player).shouldBeEmpty()`.

### UC-NET-03 (runner status S2C)
- **03.a-configured** `PLACE_STRUCTURE`: place runner BE configured, write structure NBT to disk first; invoke handler; assert `RunnerStatusS2C(IDLE, "Structure placed: <id>")`.
- **03.a-not-configured** `PLACE_STRUCTURE`: runner BE with blank specId / zero bounds; assert `RunnerStatusS2C(IDLE, "No spec configured")`.
- **03.b-happy** `RUN`: write `.spec.kts` stub; invoke; assert first payload is `RunnerStatusS2C(RUNNING, "Running…")`. Don't assert run completion.
- **03.b-missing** `RUN` with no `.spec.kts` on disk; assert `RunnerStatusS2C(FAIL, "Spec file not found: <id>")`.
- **03.c-already-running** `RUN` twice; assert second payload is `RunnerStatusS2C(RUNNING, "Already running")`. **Risk:** depends on `SpecBlockEntity.startRun` returning `false` for the second call within a single `onServer { }`. If the first call completes synchronously, the second won't see "in flight" state — see Risks below.
- **03.d-configured** `RESTORE`: place runner BE configured; place a non-air block in bounds; invoke; assert `RunnerStatusS2C(IDLE, "Snapshot restored")` and the dirty block is gone.
- **03.d-not-configured** `RESTORE` with unconfigured BE; assert `RunnerStatusS2C(IDLE, "No spec configured")`.

### UC-NET-04 (overwrite handshake — server half)
- **04.b** "decision handler runs originPos guard" — `handleOverwriteDecision` with `originPos` at AIR; assert no payloads, no world mutation.
- **04.c-true** place runner BE configured; pre-write a `.nbt` to `specSaveDir` (use `StructurePersistence.save`); place a non-air block in bounds; invoke `handleOverwriteDecision(overwrite=true)`; assert bounds are cleared and structure was placed (block at known offset matches saved structure).
- **04.c-false** same setup; `overwrite=false`; assert non-air block still present, no structure load.
- **04.d-disconnect** structural: do *not* invoke `handleOverwriteDecision`; assert world unchanged. Comment in test explains this models the disconnect case.
- **04.a** untested — see Scope.

### UC-NET-05 (block-kind guard)
- **05.a** "RecorderCommand on a runner block is a silent no-op" — place runner block at pos; invoke `handleRecorderCommand(START)` against that pos; assert no recording started, no payload, BE state unchanged.
- **05.b** "RunnerCommand on a recorder block is a silent no-op" — symmetric.
- **05.c** covered by 05.a + 05.b together; no separate test (the pair demonstrates the shared-BE-type guard).
- **05.d** "no permission/ownership check" — create two mock players; player B issues `RunnerCommandC2S(PLACE_STRUCTURE)` against a BE placed by/for player A; assert it succeeds (proves absence of ownership enforcement).

**Total:** 17 test cases.

### Setup hygiene per test
- Each test runs inside `withTempRoot { … onServer { … } }`.
- `SharedSettings.specSaveDir` either is overridden to point inside the temp root, or — preferable — tests use `saveDir(server)` as-is and clean up `.spec.kts` / `.nbt` artifacts in finally blocks. Implementation step: confirm which approach is reachable.
- Per-test position offset in the overworld (e.g. `BlockPos(index * 16, 64, 0)`).
- After each test: clear bounds via `clearCellVolume`, remove placed blocks, drain remaining payloads.

## Coverage matrix update

After tests pass, edit `docs/use-cases/networking.md`:

- Covered rows: replace `—` with `RecorderRunnerNetworkRegistrySpec."UC-NET-XX.y: …"`; replace `**GAP**` / `**GAP-PARTIAL**` with the article's existing convention for covered rows (verify by inspecting other coverage matrices; if no positive convention exists, leave Status blank).
- Deferred client rows (UC-NET-01.b, .c, UC-NET-03.e, UC-NET-04.a-client): keep `GAP`, append footnote *"Client receiver — deferred to client-gametest cycle."*
- UC-NET-04.a (server-side prompt emit): keep `GAP`, append footnote *"No producer in recorder/runner registry; only managed/structure paths emit `OverwritePromptS2C`."*
- Bump `last_audited_commit:` to the new HEAD after merge.

## Risks

1. **`be.startRun` "already running" semantics (UC-NET-03.c).** If the engine-driven path completes synchronously inside the same tick, a back-to-back `RUN` won't observe `false`. Mitigation: during implementation, read `SpecBlockEntity.startRun` and confirm the contract. If true async re-entrancy can't be reproduced in a single test scope, downgrade this row to `GAP-PARTIAL` and document.

2. **Silent sentinel registration.** Forgetting to add `RecorderRunnerNetworkRegistrySpec::class` to `GametestSentinel.runAll` makes the spec a no-op with no warning. Plan must include this step explicitly and verify by tail of test output.

3. **Block-kind guard tests (05.a/b) require both block types registered.** Confirm `RedstoneSpecRecorderBlock` and `RedstoneSpecRunnerBlock` are both available in the gametest classpath at the spec position.

4. **`saveDir` / `SharedSettings.specSaveDir`.** Tests must not pollute the real save directory; verify whether `saveDir(server)` for a gametest server resolves under the gametest temp world (likely yes) before relying on cleanup.

5. **EmbeddedChannel + `ServerPlayNetworking.send` path.** Already proven by `ManagedNetworkRegistrySpec`; reuse means low risk.

## Deliverables

1. Refactored `NetworkRegistry.kt` with seven `handleX` top-level functions and one-liner registrations.
2. New `NetworkTestSupport.kt`; helpers moved out of `ManagedTestSupport.kt`; managed spec imports updated.
3. New `RecorderRunnerNetworkRegistrySpec.kt` with 17 test cases.
4. `RecorderRunnerNetworkRegistrySpec::class` registered in `GametestSentinel.runAll`.
5. Updated `docs/use-cases/networking.md` coverage matrix.
6. Full build verification (`clientClasses classes gametestClasses clientTestClasses testClasses`) and gametest run green.
