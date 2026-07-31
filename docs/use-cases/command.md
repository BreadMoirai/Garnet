---
title: Command-surface use-cases
tags: [command, dispatch, use-cases]
summary: `/garnet project` subcommand dispatcher and its observable effects.
last_audited_commit: 04907e06339cd4a545cef18246e30f515326c44d
---

# Command-surface use-cases

The mod exposes server-side subcommands under `/garnet project`. Each parent UC below is one subcommand path with its observable outcome.

---

### UC-CMD-01 — `/garnet project` opens the project-folder UI when a root is available

**Actor:** Player (server-side command source)
**Trigger:** A player executes `/garnet project` on a server where a project root is available.
**Preconditions:** Either `EditorServerContext.get(server)` returns a non-null context pin, or `SharedSettings.projectRootPath` is a non-blank string that resolves to an absolute path; the command is registered via `EditorCommand.register(dispatcher)`.
**Outcome:** The server scans the root via `scanFolder(root.path)`, builds a `EditorTreeSnapshotS2C(root: FolderNode, currentSubpath: String?)` payload carrying the full recursive folder tree, and sends it to the requesting player. The client receiver feeds the snapshot into `ProjectTreeState` (the Compose Project Explorer's observable state); it no longer auto-opens `ProjectScreen`. The command returns `Command.SINGLE_SUCCESS`.

**System interactions:**
- UC-CMD-01.a — `EditorCommand.open` calls `EditorServerContext.get(server)` first; if non-null, its `root` field is used directly without reading `SharedSettings`.
- UC-CMD-01.b — If the context pin is null but `SharedSettings.projectRootPath` is non-blank, `EditorRoot(Path.of(rootCfg).toAbsolutePath())` is constructed as the fallback root.
- UC-CMD-01.c — `scanFolder(root.path)` mirrors the whole folder recursively into a `FolderNode` tree — all files and folders, including empty ones — with folders-first ordering.
- UC-CMD-01.d — `EditorSession.get(player.uuid)?.activeSubpath` is read to embed the player's current active folder in the snapshot; this value is `null` if the player has no prior session.
- UC-CMD-01.e — `ServerPlayNetworking.send(player, EditorTreeSnapshotS2C(root, currentSubpath))` delivers the snapshot; the client-side receiver (`EditorClientNetworking`) calls `ProjectTreeState.onSnapshot(payload)`, which the Compose Explorer renders.
- UC-CMD-01.f — The return value is `Command.SINGLE_SUCCESS` (integer 1); Brigadier records a successful execution.

---

### UC-CMD-02 — `/garnet project` rejects execution when no root is configured

**Actor:** Player (server-side command source)
**Trigger:** A player executes `/garnet project` on a server where neither a `EditorServerContext` pin nor a non-blank `SharedSettings.projectRootPath` is present.
**Preconditions:** `EditorServerContext.get(server)` returns `null`; `SharedSettings.projectRootPath` is blank or empty; the command is registered via `EditorCommand.register(dispatcher)`.
**Outcome:** The server sends a red system-chat message to the player explaining how to configure the project root. No `EditorTreeSnapshotS2C` is sent; `ProjectScreen` does not open. The command returns `0`.

**System interactions:**
- UC-CMD-02.a — Both resolution paths are attempted in order: context pin first, then `SharedSettings.projectRootPath`; both return `null` / blank, so `root` is `null`.
- UC-CMD-02.b — `src.sendSystemMessage(Component.literal("§cProject root not configured…"))` delivers a red error to the executing player's chat. The message names both configuration entry points: the world-list "Project Specs…" button (singleplayer) and the `projectRootPath` config key (dedicated server).
- UC-CMD-02.c — The method returns `0` immediately after the message, short-circuiting all scan and send logic. No `EditorTreeSnapshotS2C` packet is enqueued.

---

### UC-CMD-03 — Root resolution follows a priority chain: context pin → config string

**Actor:** Server (internal, triggered by any execution of `EditorCommand.open`)
**Trigger:** `/garnet project` is dispatched; `EditorCommand.open` selects the authoritative `EditorRoot` for the current server.
**Preconditions:** The server is live; the command source holds a valid `CommandSourceStack` with `playerOrException` available.
**Outcome:** Exactly one `EditorRoot` is chosen from the highest-priority source available; the chosen root is used for all subsequent scan and snapshot operations in that invocation. No persistent state is written.

**System interactions:**
- UC-CMD-03.a — Priority 1: `EditorServerContext.get(server)?.root` — the per-server context pin. This is set in singleplayer via the world-list "Project Specs…" flow, or programmatically before the server ticks.
- UC-CMD-03.b — Priority 2: `SharedSettings.projectRootPath` non-blank → `EditorRoot(Path.of(rootCfg).toAbsolutePath())`. Used on dedicated servers where no in-process context pin exists.
- UC-CMD-03.c — If priority 1 wins, `SharedSettings.projectRootPath` is never read during this invocation; a stale or misconfigured config value has no effect.
- UC-CMD-03.d — `EditorRoot` enforces that its path is absolute at construction time (`require(path.isAbsolute)`); a relative `projectRootPath` config value would throw at the `EditorRoot` constructor, not at scan time.

---

### UC-CMD-04 — Active session subpath is embedded in every tree snapshot

**Actor:** Server
**Trigger:** Any snapshot build during `/garnet project` dispatch (UC-CMD-01) where the executing player has an existing `EditorSession`.
**Preconditions:** `EditorSession.get(player.uuid)` returns a non-null session with a non-null `activeSubpath`; the root resolves and the scan succeeds.
**Outcome:** `EditorTreeSnapshotS2C.currentSubpath` carries the player's active subpath string. The Compose Explorer uses this to mark the active leaf (a `●` marker). If no session exists the field is `null` and no leaf is marked.

**System interactions:**
- UC-CMD-04.a — `EditorSession.get(player.uuid)` is called after the tree scan; it reads from a `ConcurrentHashMap` keyed by `UUID`.
- UC-CMD-04.b — `session?.activeSubpath` is passed directly as `currentSubpath` in the `EditorTreeSnapshotS2C` constructor; a null value is encoded as a boolean `false` flag by the packet's `STREAM_CODEC`.
- UC-CMD-04.c — `EditorSession` is a per-player, in-memory only structure (`ConcurrentHashMap`); it is not persisted across server restarts. After a restart the snapshot always carries `currentSubpath = null` until the player selects a folder again.

---

## Coverage matrix

| UC ID | Description | Test | Status |
|---|---|---|---|
| UC-CMD-01 | `/garnet project` opens project-folder UI when root is available | `EditorCommandSpec."/garnet managed with context sends a EditorTreeSnapshotS2C"` | covered |
| UC-CMD-01.a | `EditorCommand.open` tries context pin first | `EditorCommandSpec."/garnet managed with context sends a EditorTreeSnapshotS2C"` | covered |
| UC-CMD-01.b | Falls back to `SharedSettings.projectRootPath` if context pin is null | — | **GAP** |
| UC-CMD-01.c | `scanFolder` produces the recursive folder tree | `EditorCommandSpec."/garnet managed with context sends a EditorTreeSnapshotS2C"` | covered |
| UC-CMD-01.d | Player's `activeSubpath` embedded in snapshot; null if no prior session | — | **GAP** |
| UC-CMD-01.e | `ServerPlayNetworking.send` delivers `EditorTreeSnapshotS2C` to player | `EditorCommandSpec."/garnet managed with context sends a EditorTreeSnapshotS2C"` | covered |
| UC-CMD-01.f | Command returns `Command.SINGLE_SUCCESS` (integer > 0) | `EditorCommandSpec."/garnet managed with context sends a EditorTreeSnapshotS2C"` | covered |
| UC-CMD-02 | `/garnet project` rejects execution when no root configured | `EditorCommandSpec."/garnet managed without root configured sends an error message"` | covered |
| UC-CMD-02.a | Both resolution paths attempted; both null / blank | `EditorCommandSpec."/garnet managed without root configured sends an error message"` | covered |
| UC-CMD-02.b | Red error message sent to player's chat | `EditorCommandSpec."/garnet managed without root configured sends an error message"` | covered |
| UC-CMD-02.c | Returns `0` immediately; no scan or send logic executed | `EditorCommandSpec."/garnet managed without root configured sends an error message"` | covered |
| UC-CMD-03 | Root resolution follows priority chain: context pin → config string | `EditorCommandSpec."/garnet managed with context sends a EditorTreeSnapshotS2C"`, `EditorCommandSpec."/garnet managed without root configured sends an error message"` | **GAP-PARTIAL** |
| UC-CMD-03.a | Priority 1: `EditorServerContext.get(server)?.root` wins when set | `EditorCommandSpec."/garnet managed with context sends a EditorTreeSnapshotS2C"` | covered |
| UC-CMD-03.b | Priority 2: `SharedSettings.projectRootPath` non-blank → constructs `EditorRoot` | — | **GAP** |
| UC-CMD-03.c | Priority 1 win means `SharedSettings.projectRootPath` never read | — | **GAP** |
| UC-CMD-03.d | `EditorRoot` enforces absolute path at construction; relative path throws | — | **GAP** |
| UC-CMD-04 | Active session subpath embedded in every tree snapshot | — | **GAP** |
| UC-CMD-04.a | `EditorSession.get(player.uuid)` called after tree scan | `EditorSessionTest."setActive upserts and overwrites prior session for same UUID"` | **GAP-PARTIAL** |
| UC-CMD-04.b | `session?.activeSubpath` passed as `currentSubpath` in snapshot | — | **GAP** |
| UC-CMD-04.c | `EditorSession` is in-memory only; restart always yields `currentSubpath = null` | — | **GAP** |
