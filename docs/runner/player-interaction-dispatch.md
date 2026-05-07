---
title: Player-Interaction Dispatch in SpecRunner
tags: [execution, replay, mc-api, scheduling]
summary: Why button input is replayed via ButtonBlock.press instead of raw setBlock, and where to extend for new input blocks.
---

# Player-Interaction Dispatch

`SpecRunner.applyCondition` has two paths for applying a recorded input condition to the world:

1. **Block-type-specific dispatch** — `tryApplyAsPlayerInteraction(state, pos, mods)`. If it returns `true`, the runner returns early.
2. **Generic fall-through** — flatten the condition into property mods, merge into the current `BlockState`, `level.setBlock(pos, state, 3)`.

The specific path exists because some blocks have side effects beyond state mutation that the generic path skips. The motivating case is `ButtonBlock`.

## ButtonBlock — why setBlock isn't enough

`ButtonBlock.press(state, level, pos, null)` does three things:

1. Sets `POWERED=true`.
2. Fires neighbor updates.
3. **Schedules the auto-depower tick** via `level.scheduleTick(pos, this, ticksToStayPressed)`.

A raw `setBlock(pos, state.setValue(POWERED, true), 3)` does (1) and (2) but **not (3)**. The schedule is the side effect.

Why this matters at replay time: recordings of player-driven circuits are produced from gametest `h.useBlock`, which goes through `ButtonBlock.useWithoutItem → press`. The recording therefore contains both the press *and* the natural depower as separate I/O events at the SimTimes the schedule fired. If the runner replays the press via `setBlock`, it will:

- Set `POWERED=true` immediately (correct).
- Skip the schedule, so the button stays pressed forever from the world's perspective.
- Eventually hit the recording's depower entry (e.g. 10 ticks later) and force `POWERED=false` via `setBlock`.

Anything driven by the button's redstone signal between those two events runs off a different cadence than the recording — neighbor updates fire at different SimTimes, and downstream comparators/repeaters fall out of sync. The verifier then explodes with `unexpected change` failures even though the spec is logically correct.

## Current dispatch (SpecRunner.kt:135)

```
if (block is ButtonBlock) {
    val targetPowered = mods.firstOrNull { it.first == "powered" }?.second?.toBoolean() ?: return false
    val currentPowered = state.getValue(BlockStateProperties.POWERED)
    if (targetPowered && !currentPowered) {
        block.press(state, level, pos, null)
        return true
    }
}
return false
```

Notes on the current logic:

- **Only the `false → true` transition is dispatched.** The `true → false` depower is left to the natural schedule. The recording's later "powered=false" entry will fire after the schedule has already run; both calls collapse into a no-op (state is already `powered=false`).
- **Returns `false` when `mods` lacks `powered`.** Other property changes on a button (e.g. `face`, `facing`) are not player-interaction events — they fall through to the generic setBlock path.
- **No fall-through after a successful press.** When `tryApplyAsPlayerInteraction` returns `true`, `applyCondition` returns immediately and never issues a setBlock. This is critical: a setBlock after `press` would re-stomp the state and could clobber the schedule depending on flags.

## Extending for new blocks

The extension point is `tryApplyAsPlayerInteraction`. When adding a block whose recorded changes were produced by player action, **prefer the same code path a player would take**. Examples:

- `LeverBlock.pull(state, level, pos, …)` — fires neighbor updates and game events.
- `BlockState.useWithoutItem(level, player, hit)` — generic dispatch for "right-click" interactions; needs a `Player` and `BlockHitResult`.

Avoid driving `useWithoutItem` with a fake player unless necessary. Lever-style blocks have direct API methods that don't need a player parameter. Buttons accept `null` for the player, which is why the current dispatch passes `null`.

## Known residual drift

Even with `ButtonBlock.press`, replay timing for the very first input drifts ~1 tick from the recording. The recording's press fires from gametest `thenExecute`, which runs in `MinecraftServer.tick` after all level phases. The runner applies `SimTime.START` inputs from `runner.start()`, called pre-tick during `SpecRunnerCoordinator.startRun`. Aligning them exactly requires running `start()` from a matching post-EOT hook; this is non-trivial and currently unresolved.

## Related

- [`output-verifier-post-run.md`](output-verifier-post-run.md) — where unexpected-change diagnostics fire, which is the symptom path for this kind of drift.
