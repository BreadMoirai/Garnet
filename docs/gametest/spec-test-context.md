---
title: SpecTestContext fixture
tags: [testing, fixtures, helpers, harness, gametest, client-gametest]
summary: The shared client-gametest fixture wrapping ClientGameTestContext + TestSingleplayerContext with screen, button, EditBox, and YACL helpers.
---

# SpecTestContext fixture

`SpecTestContext` (at `src/clientTest/kotlin/com/breadmoirai/redstonespecs/test/SpecTestContext.kt`)
is the single fixture all `FabricClientGameTest` flows in this repo build on.
It wraps the two contexts the Fabric client-gametest API hands you
(`ClientGameTestContext` and `TestSingleplayerContext`) and adds the
project-specific helpers we kept needing.

## What it sets up

`SpecTestContext.createWorld(context)` is the entry point. It:

- builds a single-player world with `setUseConsistentSettings(true)`
  (deterministic seed/settings — required for tick-precise specs),
- waits for chunk download,
- runs `time set day`, `gamemode creative @a`, and gives infinite
  saturation so the player never dies of hunger during long runs.

Tests should always go through `createWorld`, then wrap in
`SpecTestContext(context, world)`. The `world` is `Closeable` — use
`.use { ... }`.

## Why the helpers exist

Every helper here is a workaround for a specific MC / Fabric quirk:

- **`rightClickBlock(pos, dir)`** — calls `stack.useOn(...)` directly
  (with whatever non-empty stack is in the hotbar) instead of going
  through `ServerPlayerGameMode`. The gamemode's interaction phase
  toggles levers/buttons *before* item-use runs, so a raw "use" with a
  spec marker tool in hand would flip the lever first and never reach
  the item. Items and bare-hand uses are dispatched on different paths
  for the same reason.
- **`clickButton(label)` / `waitForButton(label, timeoutTicks)`** —
  Screen rebuilds asynchronously when server data arrives (e.g.
  payload-driven editor opens). Click immediately and you'll race the
  rebuild. Always `waitForButton` first.
- **`clickNthButton` / `clickNthCycleButtonByValue`** — Several editor
  rows render identical labels (e.g. multiple `" "` checkboxes,
  multiple `false` cycle buttons). Index by occurrence. CycleButton
  messages are either bare (`"false"`) or `"<label>: <value>"` when
  `displayState=NAME_AND_VALUE`; the helper accepts both.
- **`fillEditBoxByWidth(widthPx, value)`** — EditBoxes have no stable
  ID; pixel width is the most reliable identifier in our screens.
  Sets `box.value` directly (the responder fires synchronously — do
  not also call `onChange`).
- **`clickYaclButton` / `setYaclOption`** — YACL options live on the
  config tree, not the rendered widget tree, so generic
  `clickScreenButton` cannot find them.

## `onClient` / `fromClient` wrappers

Kotlin can't infer `FailableConsumer<T, X>`'s exception type parameter
when passed a lambda, so direct `context.runOnClient { ... }` does not
compile. `onClient(block)` and `fromClient(block)` are the typed
wrappers — use them instead of constructing `FailableConsumer` objects
inline.

## When to extend it

If a new helper would be reused by 2+ tests, add it here. If it's
single-use, inline it in the test. The bar is "would another test
hit the same MC quirk?" — every existing helper passes that bar.
