# Use Cases

End-to-end and per-subsystem use-case catalog with test-coverage audit. Use this folder to find: "what scenarios does this mod support, which are tested, where are the gaps."

Each article lists parent UCs (`UC-<PREFIX>-NN`) describing user-visible journeys, with sub-IDs (`.a`–`.f`) decomposing them into system interactions. A coverage matrix at the foot of each article maps every UC to its existing test (or marks it `GAP` / `GAP-PARTIAL`).

The prefix list is closed: `PER` (persistence), `NET` (networking), `MAN` (redstone project), `CMD` (command surface), `GT` (gametest harness). Cross-cutting UCs reuse a parent prefix or, if no single prefix dominates, use `X2X`.

`REC` (recording) and `RUN` (running) were retired with this INDEX entry when `recording.md`/`running.md` were deleted: those articles documented the `RecorderScreen`/`RunnerScreen` client UI. **That whole subsystem is now gone, not just its UI:** `GarnetRecorderBlock`, `GarnetRunnerBlock`, `SpecBlockEntity`, the C2S/S2C payloads, and `ClientNetworkHandler` were all deleted — there is currently no in-game way to record or run a spec (see [networking.md](networking.md), now marked UNREACHABLE and kept only as a future-panel spec, and [architecture/recording-pipeline.md](../architecture/recording-pipeline.md)). The engine underneath (`StateRecorder`, `runGarnetSpec`, `RecordingDslEmitter`) is intact and still test-covered, but has no caller outside tests. Any remaining bare `UC-REC-*`/`UC-RUN-*` references elsewhere in this folder are historical journey labels, not resolvable parent UCs.

**Tags:** use-cases, scenarios, coverage, audit, test-matrix

## Articles

- [Persistence](persistence.md) — `.spec.kts` save/load, directory scan, script-host errors. (Structure `.nbt` sidecar UCs moved to structure-lifecycle.md.) Tags: storage, kts, scripting.
- [Structure lifecycle](structure-lifecycle.md) — `.nbt` save/load on both paths, `.nbt.unsaved` dirty tracking, save-vs-discard (UC-MAN-10, UC-PER-06/07). Tags: structure, dirty-state, save, revert, sidecar.
- [Networking](networking.md) — UNREACHABLE: documents the deleted recorder/runner C2S/S2C protocol, kept as a design spec for a future dock panel. Tags: payloads, sync, authority, unreachable.
- [Redstone project](redstone-project.md) — Void-dim workspace, grid placement, folder-tree, per-spec save-back. (Standalone `.nbt` structures moved to structure-lifecycle.md.) Tags: redstone-project, dimensions, grid.
- [Command surface](command.md) — `/garnet project` dispatcher. Tags: command, dispatch.
- [Gametest harness](gametest-harness.md) — Test infrastructure use-cases: fixtures, sentinels, replay. Tags: gametest, harness, fixtures.
- [Cross-cutting](cross-cutting.md) — End-to-end journeys spanning ≥3 subsystems. Tags: e2e, integration, regression.
