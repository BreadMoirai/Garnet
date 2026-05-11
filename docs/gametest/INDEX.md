# Gametest

Game-test infrastructure: test contexts, fixtures, helpers, and conventions for writing new tests against the runner and persistence layers.

**Tags:** testing, fixtures, helpers, harness, gametest

## Articles

- [Kotest + coroutine test bridge](kotest-bridge.md) — How specs work; the awaitTicks/onServer/spawnStructure cookbook; invariants. Tags: testing, kotest, coroutines.
- [SpecTestContext fixture](spec-test-context.md) — The shared client-gametest fixture wrapping ClientGameTestContext + TestSingleplayerContext with screen, button, EditBox, and YACL helpers. _Tags: testing, fixtures, helpers, harness, gametest, client-gametest_
- [Replaying player-interaction inputs through the runner extension point](player-interaction-replay.md) — Why the runner dispatches button-style inputs through ButtonBlock.press instead of raw setBlock, and how to extend tryApplyAsPlayerInteraction for new block types. _Tags: testing, runner, button, player-interaction, scheduled-ticks, gametest_
- [Unit-test vs gametest split](unit-vs-gametest-split.md) — Which logic belongs in `src/test/`, `src/gametest/`, or `src/clientTest/`, and why the lines are drawn where they are. _Tags: testing, kotest, gametest, client-gametest, architecture_
- [Client-test threading and the one-screen-swap limit](client-test-threading.md) — Why a `clientTest` Kotest spec can drive at most one screen-open packet per `runClientTest` invocation, plus the threading model that causes it. _Tags: testing, client-gametest, threading, harness_

> Note: the suite-author guide `strict-contract-scenarios.md` was retired with the SpecMode removal. Gametests are now authored against the DSL lambda model: `redstoneSpec("id") { input(…) { … }; output(…) { … } }` evaluated by `runRedstoneSpec`. See [runner/engine-driven-verification.md](../runner/engine-driven-verification.md) for the full pattern.
