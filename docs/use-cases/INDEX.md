# Use Cases

End-to-end and per-subsystem use-case catalog with test-coverage audit. Use this folder to find: "what scenarios does this mod support, which are tested, where are the gaps."

Each article lists parent UCs (`UC-<PREFIX>-NN`) describing user-visible journeys, with sub-IDs (`.a`–`.f`) decomposing them into system interactions. A coverage matrix at the foot of each article maps every UC to its existing test (or marks it `GAP` / `GAP-PARTIAL`).

The prefix list is closed: `REC` (recording), `RUN` (running), `PER` (persistence), `NET` (networking), `MAN` (redstone project), `CMD` (command surface), `GT` (gametest harness). Cross-cutting UCs reuse a parent prefix or, if no single prefix dominates, use `X2X`.

**Tags:** use-cases, scenarios, coverage, audit, test-matrix

## Articles

- [Recording](recording.md) — Author opens recorder block, captures redstone behavior into a spec. Tags: recorder, capture, ui, dsl-emit.
- [Running](running.md) — Player runs a saved spec via runner block; verification surfaces. Tags: runner, replay, verification, ui.
- [Persistence](persistence.md) — `.spec.kts` + `.nbt` save/load, directory scan, sidecar handling. Tags: storage, kts, sidecar.
- [Networking](networking.md) — C2S/S2C payloads, server-authority, origin-pos lookup. Tags: payloads, sync, authority.
- [Redstone project](redstone-project.md) — Datapack-driven void dim, grid placement, folder-tree, save-back. Tags: redstone-project, dimensions, grid.
- [Command surface](command.md) — `/redstonespecs project` dispatcher. Tags: command, dispatch.
- [Gametest harness](gametest-harness.md) — Test infrastructure use-cases: fixtures, sentinels, replay. Tags: gametest, harness, fixtures.
- [Cross-cutting](cross-cutting.md) — End-to-end journeys spanning ≥3 subsystems. Tags: e2e, integration, regression.
