# Gametest

Game-test infrastructure: test contexts, fixtures, helpers, and conventions for writing new tests against the runner and persistence layers.

**Tags:** testing, fixtures, helpers, harness, gametest

## Articles

- [SpecTestContext fixture](spec-test-context.md) — The shared client-gametest fixture wrapping ClientGameTestContext + TestSingleplayerContext with screen, button, EditBox, and YACL helpers. _Tags: testing, fixtures, helpers, harness, gametest, client-gametest_
- [Writing strict-contract gametest scenarios per SpecMode](strict-contract-scenarios.md) — How RedstonespecsGameTests structures positive and negative scenarios that cover all three SpecModes through the record→finalize→runner pipeline. _Tags: testing, gametest, spec-mode, contracts, recorder, runner_
- [Replaying player-interaction inputs through the runner extension point](player-interaction-replay.md) — Why the runner dispatches button-style inputs through ButtonBlock.press instead of raw setBlock, and how to extend tryApplyAsPlayerInteraction for new block types. _Tags: testing, runner, button, player-interaction, scheduled-ticks, gametest_
- [Unit-test vs gametest split](unit-vs-gametest-split.md) — Which logic belongs in `src/test/` (JUnit), `src/gametest/` (server `@GameTest`), or `src/clientTest/` (client gametest), and why the lines are drawn where they are. _Tags: testing, junit, gametest, client-gametest, architecture_
