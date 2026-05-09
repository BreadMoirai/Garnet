---
title: Managed worlds — test coverage expansion
tags: [testing, managed-worlds, gametest, unit-tests]
summary: Broad sweep adding unit + gametest coverage for every managed/* class that lacks it, organized as one test file per class.
---

# Managed worlds — test coverage expansion

## Goal

Bring the `managed/*` subsystem (server) and `network/managed/*` (server-authority handlers) up to broad test coverage. Today only pure-data classes (`ManagedRoot`, `ManagedFolderTree`, `GridLayout`, `ManagedRootsConfig`) and three lifecycle paths (`placeAll`, `saveFolder`, `ManagedNewSpec.create`) are tested. This spec adds tests for the rest.

Non-goals: rendering/UI tests for `ManagedScreen` / `ManagedRootListScreen` (out of scope; pure-data client helpers like `ManagedRootsConfig` are already covered).

## Conventions

- **Unit tests** live in `src/test/kotlin/com/breadmoirai/redstonespecs/managed/`. Use `io.kotest.core.spec.style.FunSpec` and `io.kotest.matchers.*`. No `MinecraftServer` allowed.
- **Gametests** live in `src/gametest/kotlin/com/breadmoirai/redstonespecs/test/managed/`. Use `RedstoneTestSpec({ ... })` with `onServer { ... }` for server-thread work.
- **One test file per class** (`<ClassName>Test.kt` for unit, `<ClassName>Spec.kt` for gametest), mirroring the existing convention. The single exception is `ManagedDimSpec.kt`, which is *extended* with new lifecycle edge-case tests rather than split.
- Every gametest ends its `onServer` block with `ManagedWorld.clear(this)`; tests touching `ManagedSession` also `ManagedSession.clear(playerId)`.
- Tests must not assume specific region indices from `ManagedDimRegistry` — only relative invariants (distinct subpaths → distinct origins; same subpath → same origin).
- Temp roots are always wrapped in `try { ... } finally { deleteRecursively(tmp) }` (or `withTempRoot { ... }`).

## Shared fixtures

### `ManagedTestSupport.kt` (`src/gametest/.../test/managed/`)

Top-level helpers, lifted out of the current private helpers in `ManagedDimSpec.kt`:

- `makeMockServerPlayer(server: MinecraftServer): ServerPlayer` — moved verbatim from `ManagedDimSpec.kt` (object expression that overrides `gameMode()` to `CREATIVE`, `EmbeddedChannel`-backed `Connection`, fresh UUID per call).
- `deleteRecursively(path: Path)` — moved verbatim.
- `withTempRoot(prefix: String, block: (Path) -> Unit)` — `createTempDirectory` + try/finally cleanup. Reduces boilerplate.
- `writeStub(folder: Path, name: String)` — `folder.resolve("$name.spec.kts").writeText(RecordingDslEmitter.emitStub(name))`.
- `clearCellVolume(level: ServerLevel, origin: BlockPos, size: Vec3i)` — sets every block in `[origin, origin+size)` to AIR. Used by cell-saver tests to get a deterministic baseline since the gametest world persists across runs.

`ManagedDimSpec.kt` is updated to call into these helpers instead of its private duplicates.

### Network refactor: `ManagedNetworkRegistry`

Each `registerGlobalReceiver` lambda body is extracted into a private function on `ManagedNetworkRegistry`:

- `handleListTree(server, player)` *(replaces the body of `sendTree`-via-receiver)*
- `handleLoadFolder(server, player, payload: LoadManagedFolderC2S)`
- `handleUnload(server, player)`
- `handleSaveNow(server, player)`
- `handleNewSpec(server, player, payload: NewManagedSpecC2S)`

The `registerGlobalReceiver` lambdas become one-liners: `ctx.server().execute { handleX(ctx.server(), ctx.player(), payload) }`. Visibility: `internal` so gametests can call them directly. Tests assert on the S2C payload(s) sent via `ServerPlayNetworking.send` — captured by reading the player's `EmbeddedChannel` outbound queue, since `makeMockServerPlayer` already attaches one.

This refactor is necessary because Fabric's networking pipeline isn't easily simulated in-process; testing the handler bodies directly is sufficient to verify server-authority validation behavior.

## Test inventory

### Unit tests

#### `ManagedSaveNamingTest.kt`
- Tail-only paths produce sanitized names (`/foo/specs` → `managed-specs-<8-hex>`).
- Non-alphanumeric characters in the tail become `_` (e.g. `my specs!`).
- Blank tail (`/`) falls back to `managed-root-<hash>`.
- Same tail at different absolute paths produce different hashes.
- Same absolute path produces the same name across calls (deterministic).
- Hash suffix is exactly 8 hex chars and depends on the absolute path string.

#### `ManagedDimRegistryTest.kt`
*Pure-math subset only.* Tests that don't need `MinecraftServer` use a stub or skip; the bulk lives in this test where region math can be verified without a server by *not* testing `managedLevel()`. Where a server is unavoidable, defer to the lifecycle gametests.

- `getOrAssignRegion` is idempotent for the same subpath.
- Distinct subpaths get distinct origins.
- Region width respects `cellSize.x * rowMax + gap * (rowMax+1) + REGION_PAD`.
- `regionOriginOf` returns null for unassigned subpaths.

To keep this unit-testable, the test constructs a `ManagedDimRegistry` instance only via reflection or by *moving* the region-math computation to a pure top-level function `computeRegionOrigin(idx, settings)`. **Implementation note:** if direct instantiation isn't viable without a server, this file is dropped and the tests move into `ManagedDimSpec.kt` as gametests. The plan resolves this during implementation.

#### `ManagedCellTest.kt`
- Equality and `copy` semantics on the `data class`. Tiny but present for completeness.

#### `LoadedSpecTest.kt`
- `copy(loadedSnapshot = newSnap)` preserves `cell`, `spec`, `sourceFile`. Tiny.

#### `ManagedSessionTest.kt`
- `setActive` upserts and overwrites prior session for the same UUID.
- `get` returns null after `clear`.
- `all` reflects every active session.
- Multiple UUIDs are isolated.

#### `ManagedNewSpecTest.kt`
- Blank name → `IllegalArgumentException`.
- Name with illegal chars (`a/b`, `with space`, `dot.name`) → `IllegalArgumentException`.
- File already exists → `IllegalArgumentException`.
- Successful create writes `<name>.spec.kts`, content is a valid `RecordingDslEmitter.emitStub(name)`.

#### `ManagedServerContextTest.kt`
- Set/get/clear basics; distinct `MinecraftServer` instances are isolated (verified with two stub objects in the WeakHashMap — no real server needed).

### Gametests

#### `ManagedDimSpec.kt` (extended)
Existing 3 tests stay. New tests:

- **`placeFolder` for unknown subpath throws.** `error("subpath outside root: ...")` from the lifecycle.
- **`placeFolder` on empty leaf folder.** Report has empty `loaded`, no errors.
- **Nested folders.** Tree with `set/a/` and `set/b/` (or `outer/` plus `outer/inner/`), `placeAll` returns one report per leaf, each with a distinct region origin via `regionOriginOf`.
- **Re-place after adding a spec.** `placeFolder` once, write a new `.spec.kts`, `placeFolder` again — second report includes the new spec; region origin unchanged.
- **Parse-error file.** Writes a `bad.spec.kts` with invalid Kotlin. Report's `parseErrors` lists `bad.spec.kts`; `loaded` still contains valid specs from the same folder.
- **`saveAll` aggregates across folders.** Two folders each with one mutated cell → `saveAll` returns 2 results, both `saved=true`.
- **Region assignment is stable for subpath-sorted order within a server lifetime.** Place two folders, capture origins, place a third — first two origins unchanged.

#### `ManagedCellSaverSpec.kt`
- **No mutation → not saved.** Place folder, immediately `saveFolder`, all results `saved=false`.
- **Mutation inside cell → saved.** Set a block at `absOrigin + (1,1,1)`, `saveFolder` → that spec `saved=true`, `<id>.nbt` exists with new content.
- **Mutation outside cell bounds → not saved.** Set a block at `absOrigin + spec.bounds + (5,0,0)` (outside AABB). `saveFolder` → that spec `saved=false`.
- **Snapshot refresh.** After `saved=true`, second `saveFolder` with no further changes → `saved=false` (snapshot was refreshed in `perFolder`).

#### `ManagedTeleportSpec.kt`
- **Unknown subpath → false.** No teleport, session not changed.
- **Known subpath → true.** Player position is at region center + (0.5, yBase+2, 0.5); `ManagedSession.get(playerId)?.activeSubpath == subpath`.

#### `ManagedNetworkRegistrySpec.kt`
Drives the extracted `handleX` functions directly. Each test creates a fresh root, a mock player, and asserts on the S2C payload(s) read from the player's `EmbeddedChannel` outbound queue.

- **`handleLoadFolder` traversal rejection.** `subpath = "../escape"` → single `ManagedErrorS2C` "escapes root", no teleport.
- **`handleLoadFolder` happy path.** Valid subpath → `ManagedFolderLoadedS2C` with correct `loadedSpecIds`; player is at the region; `ManagedSession.activeSubpath == subpath`.
- **`handleSaveNow`.** Mutate a cell, then call `handleSaveNow` → `ManagedSaveReportS2C` with one entry formatted as `<id>|saved=true`.
- **`handleNewSpec` without active session.** No prior `setActive` → `ManagedErrorS2C` "no folder selected".
- **`handleNewSpec` with active session.** After `setActive(playerId, "set")`, `payload.name = "fresh"` → `set/fresh.spec.kts` exists, then `ManagedFolderLoadedS2C` is sent with `fresh` in `loadedSpecIds`.
- **`handleUnload`.** Sets active session, then `handleUnload` → session cleared, `ManagedSaveReportS2C(emptyList())` sent.
- **`handleListTree`.** `ManagedTreeSnapshotS2C.leaves` matches `ManagedFolderTree.scan(root).leaves` (same subpaths and counts).

#### `ManagedCommandSpec.kt`
- **No root configured.** `SharedSettings.managedRootPath` is blank and no `ManagedServerContext` set → `/redstonespecs managed` writes a system message starting with `Managed root not configured` and returns 0.
- **Root configured via context.** With a `ManagedServerContext` set → returns `Command.SINGLE_SUCCESS` and a `ManagedTreeSnapshotS2C` is sent (read from the player channel).

## Out of scope

- Client `ManagedScreen` / `ManagedRootListScreen` rendering or input.
- `ManagedClientNetworking` receivers (the screen-open side effect).
- Cross-server-restart persistence (reopening the same managed save).
- Concurrency stress on `ManagedSession` / `ManagedDimRegistry` (they're `ConcurrentHashMap`-backed; correctness under contention is implicit).

## Open questions resolved during implementation

- **`ManagedDimRegistryTest.kt` instantiation.** If a `ManagedDimRegistry(server)` can't be constructed in a unit test without a real `MinecraftServer`, extract the region-math into a pure helper and unit-test that instead; otherwise the test file is dropped and its assertions move into `ManagedDimSpec.kt`.
- **EmbeddedChannel outbound capture.** `Connection.send` may buffer differently than expected. The implementation plan verifies one capture works end-to-end before the rest of `ManagedNetworkRegistrySpec.kt` is written.

## File summary

New unit files (`src/test/kotlin/com/breadmoirai/redstonespecs/managed/`):
- `ManagedSaveNamingTest.kt`
- `ManagedDimRegistryTest.kt` *(may collapse into `ManagedDimSpec.kt`)*
- `ManagedCellTest.kt`
- `LoadedSpecTest.kt`
- `ManagedSessionTest.kt`
- `ManagedNewSpecTest.kt`
- `ManagedServerContextTest.kt`

New gametest files (`src/gametest/kotlin/com/breadmoirai/redstonespecs/test/managed/`):
- `ManagedTestSupport.kt` *(shared fixtures, no tests)*
- `ManagedCellSaverSpec.kt`
- `ManagedTeleportSpec.kt`
- `ManagedNetworkRegistrySpec.kt`
- `ManagedCommandSpec.kt`

Modified files:
- `ManagedDimSpec.kt` — uses shared fixtures; adds 7 new lifecycle tests.
- `ManagedNetworkRegistry.kt` — extracts handler bodies into 5 `internal` `handleX` functions.
