---
title: Replaying player-interaction inputs through the runner extension point
tags: [testing, runner, button, player-interaction, scheduled-ticks, gametest]
summary: Why the runner dispatches button-style inputs through ButtonBlock.press instead of raw setBlock, and how to extend tryApplyAsPlayerInteraction for new block types.
---

# Replaying player-interaction inputs through the runner

The runner replay (`tryApplyAsPlayerInteraction` in
`src/main/kotlin/com/breadmoirai/garnet/spec/PlayerInteractionDispatch.kt`)
has two paths for applying an input block-state change:

1. The **generic path** — `level.setBlock(pos, target, 3)`.
2. The **player-interaction path** — dispatch to a block-type-specific
   method that mirrors what a player's click would do (e.g.
   `ButtonBlock.press`).

`InputScope` callbacks (inside `input(x,y,z) { at(tick) { … } }`) call this
function. Gametests need to know which path their inputs take, because the
two paths produce different downstream timing.

## The button gotcha

`ButtonBlock.press(state, level, pos, null)` does three things:

- sets `POWERED=true`,
- fires neighbor updates,
- **schedules the auto-depower tick** via
  `level.scheduleTick(pos, this, ticksToStayPressed)`.

The third step is the load-bearing one. Recording captures both the
press AND the natural depower as separate I/O events derived from
`helper.useBlock` (which routes through `ButtonBlock.useWithoutItem` →
`press`). If replay applied the press via raw `setBlock`, the
schedule never gets queued, the button stays pressed forever, and the
spec's later "powered=false" input entry depowers it on the wrong tick
— breaking downstream timing for everything that depends on the rising
or falling edge.

Hence: when an input is a button-style press, the runner takes the
specific path. The condition's "powered=false" transition then lands on
top of an already-natural depower (the schedule beat it), and the two
collapse into a no-op `setBlock`.

## How to write a gametest input that exercises this

In a `src/gametest/` spec, scenarios drive the world with
`helper.useBlock(buttonPos)`. That call goes through the same code path
a player would, so the recording captures both edges. The replay then
takes the player-interaction path on the rising edge and the no-op
collapse on the falling edge, and timing matches the recording.

If you write a scenario that drives a block via raw `setBlock` in
`drive`, you will *record* a single state change (no depower schedule)
and the contract will pass — but you'll have tested nothing about the
runner's specific path. Always drive button-style inputs through
`useBlock`.

## Extending `tryApplyAsPlayerInteraction`

Today the only handled block is `ButtonBlock`. Lever, doors, comparators
in subtract mode, etc. all currently fall through to the generic path.
When adding a new block type:

- Prefer methods on the same code path a player takes:
  `LeverBlock.pull`, `BlockState.useWithoutItem(level, player, hit)`,
  etc. This guarantees identical neighbor updates, scheduled ticks, and
  game events.
- Detect only the *meaningful transition* (e.g. button: `false→true`
  triggers press; `true→false` is left to the natural schedule).
- The function falls through to `setBlock` when the block-specific path
  is not taken — no explicit return value needed.

Then add a gametest scenario whose recording uses `helper.useBlock` (or
the player-driven equivalent) so recording and replay traverse the
same path.

## Known residual drift

Even with `ButtonBlock.press`, replay timing drifts ~1 tick from the
original recording for player-driven circuits. The recording's press
fires from gametest's `thenExecute` — which runs in
`MinecraftServer.tick` *after* all level phases of that tick — while
the `runGarnetSpec` tick loop fires `START_OF_TICK` inputs before
`awaitTickEnd()`. Aligning the press phase exactly is non-trivial;
relevant code paths are `MinecraftServer.tick` (gametest tick is last),
`SubTickPhaseEvents`, and the `runGarnetSpec` loop. Until that's
solved, scenarios should tolerate a 1-tick offset on player-driven
edges if you write exact-time assertions.
