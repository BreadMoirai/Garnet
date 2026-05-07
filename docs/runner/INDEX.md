# Runner

Spec execution engine: how specs are run against the world, how conditions are evaluated, how outputs are verified.

**Tags:** execution, conditions, verification, finalization, recording, simtime, phases

## Articles

- [Post-Run Output Verification](output-verifier-post-run.md) — why OutputVerifier runs after the recording closes and the invariants it relies on. Tags: verification, recording, simtime, lifecycle.
- [Player-Interaction Dispatch in SpecRunner](player-interaction-dispatch.md) — why button replay routes through ButtonBlock.press, and how to extend for new input blocks. Tags: execution, replay, mc-api, scheduling.
