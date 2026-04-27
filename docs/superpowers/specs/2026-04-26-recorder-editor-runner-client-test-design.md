# Client Test: Recorder → Editor → Runner Flow (Lever + Lamp)

## Goal

Add a new `FabricClientGameTest` method that exercises the full happy-path
spec lifecycle for a trivial lever-and-lamp circuit:

1. Drive recording via the `RecorderSetupScreen` UI (Record / Stop buttons).
2. Confirm the recorder transforms to an editor on stop.
3. Confirm `Save` on the editor's `SpecOverviewScreen` transforms it to a runner.
4. Confirm `Run` on the runner's `SpecOverviewScreen` produces a passing result.

The new test sits alongside `leverLampFullFlow` in
`src/gametest/kotlin/.../RedstonespecsClientTests.kt` and is dispatched from
`runTest`.

## World Layout

Coordinates chosen to avoid collisions with existing tests in the shared
test world.

- Recorder block at `(30, 64, 0)`.
- Stone floor at `(32, 63, 1)` (lamp rests on it for visual support; not
  required for redstone behavior).
- Redstone lamp at `(32, 64, 1)` — relative `(2, 0, 1)`, inside
  `RedstoneSpec.DEFAULT_BOUNDS = (1,0,1)→(5,4,5)`.
- Lever at `(32, 65, 1)` with `face=floor` — sitting on top of the lamp,
  attached directly to it. Relative `(2, 1, 1)`, inside `DEFAULT_BOUNDS`.
  Toggling `powered=true` directly powers the lamp via attachment.

The recorder block itself is at relative `(0, 0, 0)` (its own origin), which
is outside the bounds — the spec records blocks within bounds only.

Player is teleported away from the recorder before interactions so the
right-click hit-results target the intended blocks.

## Test Flow

Each step uses helpers already in `SpecTestContext`. New helpers are added
only where noted.

1. **World setup** — `/setblock` recorder, lamp, lever (`powered=false`),
   stone floor; `tp @a` to a viewing position.
2. **Apply input marker on lever**
   - `clear @a`, `give @a redstonespecs:input_spec_marker 1`.
   - `rightClickBlock(leverPos)`. In recorder mode, `SpecMarkerTool.useOn`
     skips the `OpenEditorS2CPayload`, so no screen opens.
3. **Apply output marker on lamp**
   - `clear @a`, `give @a redstonespecs:output_spec_marker 1`.
   - `rightClickBlock(lampPos)`. Same: no screen opens.
4. **Open recorder UI** — `clear @a`, `rightClickBlock(recorderPos)`.
   `RecorderSetupScreen` opens. Click `"Record"` → server starts recording,
   screen closes (`onClose()` is called by the button handler).
5. **Drive state changes during recording**
   - `waitTicks(2)` so the recorder captures the initial state.
   - `/setblock 32 65 1 minecraft:lever[face=floor,facing=north,powered=true]`
     (replacing the lever toggles `powered`, which through attachment lights
     the lamp on the next tick).
   - `waitTicks(4)` so the recorder captures the lit state.
6. **Stop recording**
   - `rightClickBlock(recorderPos)` → `RecorderSetupScreen` (still a
     recorder). Click `"Stop"` → server `stopRecordingAndFinalize()` +
     `transformTo(EDITOR)`. Screen closes.
   - `context.waitFor` until the block at `recorderPos` is a
     `RedstoneSpecEditorBlock`.
7. **Save on editor → Runner**
   - `rightClickBlock(recorderPos)` → `SpecOverviewScreen` (kind=EDITOR).
   - Click `"Save"` → sends `TransformToRunnerC2SPayload`, screen closes.
   - `context.waitFor` until the block is a `RedstoneSpecRunnerBlock`.
8. **Run on runner**
   - `rightClickBlock(recorderPos)` → `SpecOverviewScreen` (kind=RUNNER,
     read-only) with a `Run` button.
   - Click `"Run"` → sends `RunSpecC2SPayload`.
   - `context.waitFor { mc -> getClientBe(recorderPos)?.lastTestResult != null }`
     with a generous timeout (100 ticks, matching `leverLampFullFlow`).
9. **Assert** — `result.checks.isNotEmpty()`, no failed checks. Same
   assertion shape as `leverLampFullFlow`.

## New Helper (Optional)

If `context.waitFor { ... block class check ... }` becomes verbose at the
two transform-await points (steps 6 and 7), extract:

```kotlin
fun waitForBlockClass(pos: BlockPos, cls: Class<*>, timeoutTicks: Int = 100) =
    context.waitFor({ mc ->
        cls.isInstance(mc.level?.getBlockState(pos)?.block)
    }, timeoutTicks)
```

Otherwise inline the `waitFor`.

## Risk: Recording / Replay Determinism

The recorder snapshots blocks within bounds each tick. The replay during
`Run` re-applies the recorded initial state and checks subsequent ticks
match. Tick spacing in step 5 (the `waitTicks(2)` and `waitTicks(4)` calls)
needs to be enough that:

- The recorder sees the lever in `powered=false` for at least one captured
  tick before the toggle, so the input entry has a meaningful baseline.
- The lamp transition to `lit=true` is captured before stop.

If the test is flaky on a first run, tune the wait counts up. Do not add
retry logic.

## Out of Scope

- Verifying intermediate UI state in detail (e.g., that the gating reasons
  string is empty after markers are applied) — covered implicitly by
  `Record` being clickable.
- Recording in `TICK_AWARE` or `UPDATE_AWARE` modes — only `SIMPLE` (the
  default) is exercised.
- Discard / edit-back-to-recorder paths — separate concern.
