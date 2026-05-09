---
title: Player-Interaction Dispatch
tags: [execution, replay, mc-api, scheduling]
summary: Why button input is replayed via ButtonBlock.press instead of raw setBlock, and where to extend for new input blocks.
---

# Player-Interaction Dispatch

`tryApplyAsPlayerInteraction` in `runner/PlayerInteractionDispatch.kt` applies
a target `BlockState` to a position in two possible ways:

1. **Block-type-specific dispatch** — for `ButtonBlock`, routes through
   `ButtonBlock.press` so the scheduled-tick side-effect fires correctly.
2. **Generic fall-through** — `level.setBlock(pos, target, 3)` for all other
   blocks.

The specific path exists because some blocks have side effects beyond state mutation that the generic path skips. The motivating case is `ButtonBlock`.

`InputScope` callbacks (inside `redstoneSpec { input(…) { … } }`) call
`tryApplyAsPlayerInteraction` when applying a state change at a tick boundary.

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

## Current dispatch (`runner/PlayerInteractionDispatch.kt`)

```kotlin
if (block is ButtonBlock) {
    val targetPowered = if (target.hasProperty(BlockStateProperties.POWERED))
        target.getValue(BlockStateProperties.POWERED) else return
    val currentPowered = current.getValue(BlockStateProperties.POWERED)
    if (targetPowered && !currentPowered) {
        block.press(current, level, pos, null)
        return
    }
}
if (target != current) level.setBlock(pos, target, 3)
```

Notes on the current logic:

- **Only the `false → true` transition is dispatched.** The `true → false` depower is left to the natural schedule. The recording's later "powered=false" entry will fire after the schedule has already run; both calls collapse into a no-op (state is already `powered=false`).
- **Falls through when `target` lacks the `POWERED` property.** Other property changes on a button (e.g. `face`, `facing`) are not player-interaction events — they fall through to the generic `setBlock`.
- **No double-setBlock after a successful press.** The function returns immediately after `press`; the final `setBlock` guard only fires when the button path was not taken.

## Extending for new blocks

The extension point is `tryApplyAsPlayerInteraction`. When adding a block whose recorded changes were produced by player action, **prefer the same code path a player would take**. Examples:

- `LeverBlock.pull(state, level, pos, …)` — fires neighbor updates and game events.
- `BlockState.useWithoutItem(level, player, hit)` — generic dispatch for "right-click" interactions; needs a `Player` and `BlockHitResult`.

Avoid driving `useWithoutItem` with a fake player unless necessary. Lever-style blocks have direct API methods that don't need a player parameter. Buttons accept `null` for the player, which is why the current dispatch passes `null`.

## Known residual drift

Even with `ButtonBlock.press`, replay timing for the very first input can drift ~1 tick from the original recording depending on how the recording was produced. The `runRedstoneSpec` tick loop applies `START_OF_TICK` inputs at the beginning of each tick iteration, which may not align exactly with when `gametest thenExecute` fired during recording. Aligning them exactly requires carefully matching the `SimTime` anchor in the emitted DSL; this is a known limitation of the current emitter.

## Related

- [runRedstoneSpec — inline verification](engine-driven-verification.md) — how the tick loop fires the InputScope callbacks that call this function.
