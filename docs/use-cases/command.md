---
title: Command-surface use-cases
tags: [command, dispatch, use-cases]
summary: `/redstonespecs managed` subcommand dispatcher and its observable effects.
last_audited_commit: 04907e06339cd4a545cef18246e30f515326c44d
---

# Command-surface use-cases

The mod exposes server-side subcommands under `/redstonespecs managed`. Each parent UC below is one subcommand path with its observable outcome.

---

### UC-CMD-01 — `/redstonespecs managed` opens the managed-folder UI when a root is available

**Actor:** Player (server-side command source)
**Trigger:** A player executes `/redstonespecs managed` on a server where a managed root is available.
**Preconditions:** Either `ManagedServerContext.get(server)` returns a non-null context pin, or `SharedSettings.managedRootPath` is a non-blank string that resolves to an absolute path; the command is registered via `ManagedCommand.register(dispatcher)`.
**Outcome:** The server scans the root via `ManagedFolderTree.scan`, builds a `ManagedTreeSnapshotS2C` payload, and sends it to the requesting player. The client receiver opens `ManagedScreen` on receipt. The command returns `Command.SINGLE_SUCCESS`.

**System interactions:**
- UC-CMD-01.a — `ManagedCommand.open` calls `ManagedServerContext.get(server)` first; if non-null, its `root` field is used directly without reading `SharedSettings`.
- UC-CMD-01.b — If the context pin is null but `SharedSettings.managedRootPath` is non-blank, `ManagedRoot(Path.of(rootCfg).toAbsolutePath())` is constructed as the fallback root.
- UC-CMD-01.c — `ManagedFolderTree.scan(root)` walks the root directory recursively; every subdirectory containing `.spec.kts` files is emitted as a `ManagedLeaf`; subdirectories that themselves contain further subdirectories are added to the `intermediates` set.
- UC-CMD-01.d — `ManagedSession.get(player.uuid)?.activeSubpath` is read to embed the player's current active folder in the snapshot; this value is `null` if the player has no prior session.
- UC-CMD-01.e — `ServerPlayNetworking.send(player, ManagedTreeSnapshotS2C(leaves, intermediates, currentSubpath))` delivers the snapshot; the client-side receiver creates and displays `ManagedScreen`.
- UC-CMD-01.f — The return value is `Command.SINGLE_SUCCESS` (integer 1); Brigadier records a successful execution.

---

### UC-CMD-02 — `/redstonespecs managed` rejects execution when no root is configured

**Actor:** Player (server-side command source)
**Trigger:** A player executes `/redstonespecs managed` on a server where neither a `ManagedServerContext` pin nor a non-blank `SharedSettings.managedRootPath` is present.
**Preconditions:** `ManagedServerContext.get(server)` returns `null`; `SharedSettings.managedRootPath` is blank or empty; the command is registered via `ManagedCommand.register(dispatcher)`.
**Outcome:** The server sends a red system-chat message to the player explaining how to configure the managed root. No `ManagedTreeSnapshotS2C` is sent; `ManagedScreen` does not open. The command returns `0`.

**System interactions:**
- UC-CMD-02.a — Both resolution paths are attempted in order: context pin first, then `SharedSettings.managedRootPath`; both return `null` / blank, so `root` is `null`.
- UC-CMD-02.b — `src.sendSystemMessage(Component.literal("§cManaged root not configured…"))` delivers a red error to the executing player's chat. The message names both configuration entry points: the world-list "Managed Specs…" button (singleplayer) and the `managedRootPath` config key (dedicated server).
- UC-CMD-02.c — The method returns `0` immediately after the message, short-circuiting all scan and send logic. No `ManagedTreeSnapshotS2C` packet is enqueued.

---

### UC-CMD-03 — Root resolution follows a priority chain: context pin → config string

**Actor:** Server (internal, triggered by any execution of `ManagedCommand.open`)
**Trigger:** `/redstonespecs managed` is dispatched; `ManagedCommand.open` selects the authoritative `ManagedRoot` for the current server.
**Preconditions:** The server is live; the command source holds a valid `CommandSourceStack` with `playerOrException` available.
**Outcome:** Exactly one `ManagedRoot` is chosen from the highest-priority source available; the chosen root is used for all subsequent scan and snapshot operations in that invocation. No persistent state is written.

**System interactions:**
- UC-CMD-03.a — Priority 1: `ManagedServerContext.get(server)?.root` — the per-server context pin. This is set in singleplayer via the world-list "Managed Specs…" flow, or programmatically before the server ticks.
- UC-CMD-03.b — Priority 2: `SharedSettings.managedRootPath` non-blank → `ManagedRoot(Path.of(rootCfg).toAbsolutePath())`. Used on dedicated servers where no in-process context pin exists.
- UC-CMD-03.c — If priority 1 wins, `SharedSettings.managedRootPath` is never read during this invocation; a stale or misconfigured config value has no effect.
- UC-CMD-03.d — `ManagedRoot` enforces that its path is absolute at construction time (`require(path.isAbsolute)`); a relative `managedRootPath` config value would throw at the `ManagedRoot` constructor, not at scan time.

---

### UC-CMD-04 — Active session subpath is embedded in every tree snapshot

**Actor:** Server
**Trigger:** Any snapshot build during `/redstonespecs managed` dispatch (UC-CMD-01) where the executing player has an existing `ManagedSession`.
**Preconditions:** `ManagedSession.get(player.uuid)` returns a non-null session with a non-null `activeSubpath`; the root resolves and the scan succeeds.
**Outcome:** `ManagedTreeSnapshotS2C.currentSubpath` carries the player's active subpath string. The client uses this to pre-select the folder in `ManagedScreen`. If no session exists the field is `null` and no folder is pre-selected.

**System interactions:**
- UC-CMD-04.a — `ManagedSession.get(player.uuid)` is called after the tree scan; it reads from a `ConcurrentHashMap` keyed by `UUID`.
- UC-CMD-04.b — `session?.activeSubpath` is passed directly as `currentSubpath` in the `ManagedTreeSnapshotS2C` constructor; a null value is encoded as a boolean `false` flag by the packet's `STREAM_CODEC`.
- UC-CMD-04.c — `ManagedSession` is a per-player, in-memory only structure (`ConcurrentHashMap`); it is not persisted across server restarts. After a restart the snapshot always carries `currentSubpath = null` until the player selects a folder again.

---

## Coverage matrix

| UC ID | Description | Test | Status |
|---|---|---|---|
| UC-CMD-01 | `/redstonespecs managed` opens managed-folder UI when root is available | `ManagedCommandSpec."/redstonespecs managed with context sends a ManagedTreeSnapshotS2C"` | covered |
| UC-CMD-01.a | `ManagedCommand.open` tries context pin first | `ManagedCommandSpec."/redstonespecs managed with context sends a ManagedTreeSnapshotS2C"` | covered |
| UC-CMD-01.b | Falls back to `SharedSettings.managedRootPath` if context pin is null | — | **GAP** |
| UC-CMD-01.c | `ManagedFolderTree.scan` emits leaves and intermediates | `ManagedCommandSpec."/redstonespecs managed with context sends a ManagedTreeSnapshotS2C"` | covered |
| UC-CMD-01.d | Player's `activeSubpath` embedded in snapshot; null if no prior session | — | **GAP** |
| UC-CMD-01.e | `ServerPlayNetworking.send` delivers `ManagedTreeSnapshotS2C` to player | `ManagedCommandSpec."/redstonespecs managed with context sends a ManagedTreeSnapshotS2C"` | covered |
| UC-CMD-01.f | Command returns `Command.SINGLE_SUCCESS` (integer > 0) | `ManagedCommandSpec."/redstonespecs managed with context sends a ManagedTreeSnapshotS2C"` | covered |
| UC-CMD-02 | `/redstonespecs managed` rejects execution when no root configured | `ManagedCommandSpec."/redstonespecs managed without root configured sends an error message"` | covered |
| UC-CMD-02.a | Both resolution paths attempted; both null / blank | `ManagedCommandSpec."/redstonespecs managed without root configured sends an error message"` | covered |
| UC-CMD-02.b | Red error message sent to player's chat | `ManagedCommandSpec."/redstonespecs managed without root configured sends an error message"` | covered |
| UC-CMD-02.c | Returns `0` immediately; no scan or send logic executed | `ManagedCommandSpec."/redstonespecs managed without root configured sends an error message"` | covered |
| UC-CMD-03 | Root resolution follows priority chain: context pin → config string | `ManagedCommandSpec."/redstonespecs managed with context sends a ManagedTreeSnapshotS2C"`, `ManagedCommandSpec."/redstonespecs managed without root configured sends an error message"` | **GAP-PARTIAL** |
| UC-CMD-03.a | Priority 1: `ManagedServerContext.get(server)?.root` wins when set | `ManagedCommandSpec."/redstonespecs managed with context sends a ManagedTreeSnapshotS2C"` | covered |
| UC-CMD-03.b | Priority 2: `SharedSettings.managedRootPath` non-blank → constructs `ManagedRoot` | — | **GAP** |
| UC-CMD-03.c | Priority 1 win means `SharedSettings.managedRootPath` never read | — | **GAP** |
| UC-CMD-03.d | `ManagedRoot` enforces absolute path at construction; relative path throws | — | **GAP** |
| UC-CMD-04 | Active session subpath embedded in every tree snapshot | — | **GAP** |
| UC-CMD-04.a | `ManagedSession.get(player.uuid)` called after tree scan | `ManagedSessionTest."setActive upserts and overwrites prior session for same UUID"` | **GAP-PARTIAL** |
| UC-CMD-04.b | `session?.activeSubpath` passed as `currentSubpath` in snapshot | — | **GAP** |
| UC-CMD-04.c | `ManagedSession` is in-memory only; restart always yields `currentSubpath = null` | — | **GAP** |
