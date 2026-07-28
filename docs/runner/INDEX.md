# Runner

Spec execution engine: how specs are run against the world, how conditions are evaluated, how outputs are verified.

**Tags:** execution, conditions, verification, finalization, recording, simtime, phases

## Articles

- [runGarnetSpec — inline verification](engine-driven-verification.md) — How runGarnetSpec drives the tick loop from inside a Kotest test body and asserts via inline shouldBe callbacks in the spec lambda. Tags: execution, kotest, verification.
- [Player-Interaction Dispatch](player-interaction-dispatch.md) — why button replay routes through ButtonBlock.press, and how to extend for new input blocks. Tags: execution, replay, mc-api, scheduling.
