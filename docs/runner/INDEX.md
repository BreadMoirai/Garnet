# Runner

Spec execution engine: how specs are run against the world, how conditions are evaluated, how outputs are verified.

**Tags:** execution, conditions, verification, finalization, recording, simtime, phases

## Articles

- [Engine-driven verification](engine-driven-verification.md) — How runRedstoneSpec drives SpecRunner from inside a Kotest test body and asserts via assertOutputsMatch. Tags: execution, kotest, verification.
- [Player-Interaction Dispatch in SpecRunner](player-interaction-dispatch.md) — why button replay routes through ButtonBlock.press, and how to extend for new input blocks. Tags: execution, replay, mc-api, scheduling.
