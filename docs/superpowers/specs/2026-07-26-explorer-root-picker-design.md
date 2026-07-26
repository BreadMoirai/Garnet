# Explorer root-picker header (Plan A) design

**Date:** 2026-07-26
**Status:** Approved

## Problem

The Project Explorer (`ProjectExplorerPanel`, LEFT dock) renders exactly one
folder tree, rooted at whatever `SharedSettings.projectRootPath` (or the live
`ProjectServerContext`/`ProjectWorld`) resolves to. There is **no in-game way to
choose which folder that root is** — it is baked into config at boot. The user
wants a header bar on the Explorer with an option button whose dropdown offers
**Open Folder** (replace the root) and **Attach Folder** (add a second root),
each opening a native OS folder picker.

That full capability is *multi-root* and touches the entire single-root server
model (`ProjectWorld`, region assignment, the snapshot payload, config). It is
split into two plans (user decision, 2026-07-26):

- **Plan A (this spec):** the header bar, the option-button dropdown, the native
  folder picker, and **Open Folder** working end-to-end by *swapping the single
  server root*. **Attach Folder** appears in the menu but is **disabled**
  ("coming soon"). No multi-root anywhere.
- **Plan B (separate, later):** true multi-root — `List<FolderNode>` payload,
  root-namespaced paths, per-root region/placement keying, config as a path
  list, old-grid cleanup, and enabling **Attach Folder**.

Plan A deliberately reuses the entire existing single-root snapshot/render/load
path unchanged — the server still ships **one** `FolderNode`; we only change
*which folder it is*. That is what keeps this plan small and de-risks the novel
UI + native-dialog + threading work before the deep backend refactor.

## Scope

**In scope:**
- A non-scrolling header bar at the top of `ProjectExplorer()` with an option
  button (labeled by the current root's folder name) + a Refresh control.
- A hand-rolled inline dropdown overlay (Open Folder / Attach Folder) — **not**
  Material `DropdownMenu`/`Popup`.
- A native folder picker behind an injectable `FolderPicker` seam
  (`TinyFileDialogs.tinyfd_selectFolderDialog`), run off the render thread.
- A `RootPickerController` (Compose-observable singleton) holding menu-open and
  picking-in-progress state and marshalling the picked path back to the client
  thread.
- New C2S `SetProjectRootC2S(path)` and server `handleSetRoot`: validate the
  directory, persist to `SharedSettings.projectRootPath`, swap the live root
  (re-run `placeAll`), and re-send the tree snapshot.
- Tests (client + server + codec) and the mandatory doc audit.

**Out of scope (explicit guards — all Plan B):**
- `List<FolderNode>` payload / any multi-root state. `ProjectTreeSnapshotS2C`
  keeps its single `root: FolderNode`.
- Root-namespaced paths, per-root region/placement keying, `ProjectWorld` shape
  changes. `handleSetRoot` swaps the one root; it does not add a second.
- Config as a *list*. `SharedSettings.projectRootPath` stays a single `String`.
- **Attach Folder** behavior — the menu item is rendered **disabled**.
- Cleaning up the previous root's already-placed cells after a swap (see
  "Accepted rough edges").
- `FileTree.kt` (the model) is **not** modified.

## Existing pieces (do not rebuild)

- **Payload** — `network/project/ProjectPackets.kt`:
  `ProjectTreeSnapshotS2C(root: FolderNode, currentSubpath: String?)` + the
  recursive tag-byte `FILE_TREE_STREAM_CODEC`. Reused as-is.
- **Server root resolution** — `ProjectNetworkRegistry.rootFor(server)`:
  `ProjectWorld.root` → `ProjectServerContext.root` → `SharedSettings.projectRootPath`.
  `sendTree(server, player)` scans `root.path` and sends the snapshot. Reused.
- **Placement** — `ProjectDimLifecycle.placeAll(server, root)` assigns regions and
  places cells; re-runnable (replaces `perFolder` atomically). Reused for the swap.
- **Client state** — `client/ide/ProjectTreeState.kt`: `onSnapshot`, `expanded`,
  `select`, `reset`. Reused; the new controller is a *sibling* singleton, kept
  separate (packet handlers never touch the picker's UI state).
- **Client receivers** — `client/project/ProjectClientNetworking.kt`
  (`ctx.client().execute {}`). Extended only to register the new C2S payload type.
- **Panel** — `client/ide/ProjectExplorerPanel.kt`: Compose, foundation-only,
  `Column` + `verticalScroll` (NOT LazyColumn — scissor/scroll gotcha).
- **Native dialog** — `org.lwjgl.util.tinyfd.TinyFileDialogs` is already on the
  classpath (MC bundles `lwjgl-tinyfd`). No new dependency.

## Design decisions

### 1. Header bar lives OUTSIDE the scroll area

`ProjectExplorer()`'s root becomes a `Box(fillMaxSize)` so the dropdown overlay
can z-layer above the tree. Inside it, a `Column`:
- **Header `Row`** (non-scrolling): the option button (left, shows
  `snapshot?.root?.name ?: "(no root)"` + a `▾`), a spacer, and a `↻` refresh
  button (right, sends `ListProjectTreeC2S.INSTANCE` — the existing action).
- **Tree `Column`** (`verticalScroll`): the existing recursive `TreeNode` render,
  unchanged.
- **Status line**: unchanged.

The dropdown overlay is a **later child of the outer `Box`** (higher z-order),
rendered only when `RootPickerController.menuOpen`, offset to sit just under the
option button. Because it is a sibling of the scroll `Column` (not inside it), it
is not scroll-clipped.

### 2. Dropdown is hand-rolled, NOT Material `Popup`/`DropdownMenu`

**Constraint (new learning):** Compose `Popup`/`DropdownMenu` open a *separate
desktop window*. The dock hosts Compose in an embedded `ImageComposeScene`
(CPU-raster, no platform windowing), so a `Popup` renders nowhere. The menu is a
plain `Column` of `clickable` `BasicText` rows inside a `Box` with a `background`
+ padding, drawn as a z-layered sibling. A full-size transparent scrim `Box`
behind it (also `clickable`) closes the menu on outside-click. Foundation-only,
matching the rest of the dock.

Menu items:
- **Open Folder** → `RootPickerController.pickAndSet(attach = false)`.
- **Attach Folder** → rendered with dimmed text and **no** `clickable`
  (disabled). A trailing "(soon)" or ⓘ hint. Plan B enables it.

### 3. Native picker behind an injectable seam

```kotlin
fun interface FolderPicker { fun pick(title: String, default: String?): String? }
```
- `Tinyfd FolderPicker` (default): calls
  `TinyFileDialogs.tinyfd_selectFolderDialog(title, default ?: "")`; returns the
  path or `null` on cancel.
- `RootPickerController` holds `menuOpen: Boolean` and `picking: Boolean` as
  Compose state (private setters), plus a swappable `picker: FolderPicker`
  (default = Tinyfd, overridable in tests).
- `pickAndSet(attach)`:
  - guard: if `picking`, no-op; else set `picking = true`, close the menu.
  - run `picker.pick(...)` on a **worker thread** (the native dialog blocks — must
    not run on the render/client thread or the game freezes).
  - on a non-null path → `Minecraft.getInstance().execute { ClientPlayNetworking
    .send(SetProjectRootC2S(path)) }`; on null (cancel) → nothing.
  - always clear `picking` at the end.
- Default arg for the picker's `default` path = the current root's absolute path
  is **not** known client-side (the client only has folder *names*, not absolute
  paths). So `default` is left `null` in Plan A; the OS opens at its default
  location. (Noted so a reviewer doesn't expect "open at current root".)

Rationale for the seam: the native dialog cannot run in an automated test, so the
whole send-on-pick flow is validated by injecting a fake `FolderPicker` that
returns a canned path synchronously.

### 4. `SetProjectRootC2S(path: String)` — REPLACE only

A new C2S data payload, id `project_set_root`, `STREAM_CODEC` composed of a single
`STRING_UTF8`. Registered in `ProjectNetworkRegistry.register()`
(serverbound) and on the client. **No `attach` flag** in Plan A — Attach is
disabled, so the packet only ever means "replace". Plan B introduces its own
multi-root packet(s); we do not pre-build a flag we can't exercise (YAGNI).

### 5. `handleSetRoot(server, player, payload)`

1. Resolve `Path.of(payload.path).toAbsolutePath()`. If it is **not an existing
   directory**, send `ProjectErrorS2C("not a folder: <path>")` and return.
2. Persist: `SharedSettings.projectRootPath = <abs path>` (+ save config, matching
   how other settings persist — verify the save call site).
3. Swap the live root so `rootFor` returns the new folder: set a fresh
   `ProjectServerContext(ProjectRoot(absPath))` via `ProjectServerContext.set`,
   then run `ProjectDimLifecycle.placeAll(server, newRoot)` (creates/stores a new
   `ProjectWorld` for the new root, assigns regions so subsequent spec-folder
   clicks can teleport/place).
4. `sendTree(server, player)` — the Explorer re-renders rooted at the new folder.

`ProjectSession.activeSubpath` is left as-is (it may now point at a subpath that
no longer exists under the new root; a subsequent load either resolves or returns
the existing `ProjectErrorS2C` — no new failure mode).

### 6. Accepted rough edges (documented, deferred to Plan B)

- **Old grid persists.** After a swap, the previous root's already-placed cells
  remain physically in the workspace overworld, and `ProjectDimRegistry` keeps
  accumulating region assignments across swaps (ephemeral, per-server). Functionally
  harmless for Plan A (new root gets fresh non-colliding regions); cleanup is a
  Plan B concern.
- **Single-packet full-tree ceiling** (pre-existing, see the project memory note):
  a very large picked folder still ships in one payload and can hit the ~1 MiB cap.
  Unchanged by this plan; lazy loading remains the tracked separate sub-project.

## Components changed

### `network/project/ProjectPackets.kt`
- Add `SetProjectRootC2S(path: String)` with a single-`STRING_UTF8`
  `STREAM_CODEC` and `Type` id `project_set_root`.

### `network/project/ProjectNetworkRegistry.kt`
- Register the new payload type (`serverboundPlay`) + a global receiver that
  dispatches to `handleSetRoot` on the server thread.
- Add `handleSetRoot` per decision 5. Reuse `sendTree`.

### `config/SharedSettings.kt`
- No shape change (`projectRootPath` stays `String`). Confirm the persistence/save
  path used by `handleSetRoot` writes it durably.

### `client/ide/RootPickerController.kt` (new)
- Compose-observable singleton: `menuOpen`, `picking`, swappable `picker`,
  `toggleMenu`/`closeMenu`, `pickAndSet(attach)`. Worker-thread dialog +
  `Minecraft.execute` send. Sibling of `ProjectTreeState`.

### `client/ide/FolderPicker.kt` (new)
- `fun interface FolderPicker` + the `TinyFileDialogs`-backed default impl.

### `client/ide/ProjectExplorerPanel.kt`
- Wrap body in a `Box`; add the header `Row` (option button + refresh) outside the
  scroll `Column`; render the dropdown overlay + scrim when `menuOpen`. Keep the
  existing `TreeNode` recursion and status line untouched.

### `client/project/ProjectClientNetworking.kt` (or wherever client payload types
register)
- Register `SetProjectRootC2S` as a serverbound payload type on the client so
  `ClientPlayNetworking.send` can encode it.

## Data flow

```
[Open Folder] click → RootPickerController.pickAndSet(false)   [client]
  worker thread: FolderPicker.pick(...)  → /abs/path | null
  non-null → Minecraft.execute { send SetProjectRootC2S(path) }
    → server handleSetRoot:                                     [server]
        not a dir? → ProjectErrorS2C
        SharedSettings.projectRootPath = absPath (persist)
        ProjectServerContext.set(new root); placeAll(server, root)
        sendTree → ProjectTreeSnapshotS2C(scanFolder(root.path), currentSubpath)
    → ProjectClientNetworking → ProjectTreeState.onSnapshot     [client]
    → ProjectExplorerPanel recomposes, rooted at the new folder
```

## Testing strategy

- **NEW unit** `src/test/.../ProjectPacketsSetRootTest.kt` (or extend
  `FileTreeCodecTest.kt`) — round-trip `SetProjectRootC2S` through its
  `STREAM_CODEC` (encode → decode → `shouldBe`). Autoscans (plain `:26.1:test`).
- **NEW/updated gametest** `src/gametest/.../ProjectNetworkRegistrySpec.kt`:
  - `handleSetRoot` with a real temp directory → asserts
    `SharedSettings.projectRootPath` updated and a `ProjectTreeSnapshotS2C` sent
    whose `root.name` matches the picked folder.
  - `handleSetRoot` with a non-directory path → asserts a `ProjectErrorS2C` is
    sent and the root is unchanged.
  - Already registered in `GametestSentinel` (confirm; add if the spec is new).
- **Updated clientTest** `src/clientTest/.../ProjectExplorerSpec.kt`:
  - Inject a fake `FolderPicker` returning a canned path into
    `RootPickerController`; drive `pickAndSet(false)`; assert a
    `SetProjectRootC2S` was sent (capture via a test hook / fake send) — or, if
    intercepting `ClientPlayNetworking.send` is impractical, assert the controller
    reached the "would-send" state with the canned path.
  - Assert the header renders the option button and toggling `menuOpen` reveals
    the two menu rows (Open enabled, Attach disabled). Screenshot capture as the
    existing spec does. Register in `ClientTestSentinel` if new.
- **Threading note for tests:** the worker-thread dialog is bypassed by the fake
  `FolderPicker` (returns synchronously), so tests do not block. Follow the
  `ClientSpec` threading helpers (onServer/onClient/runOnClient).

## Verification (WSL, verbatim)

- Unit: `cmd.exe /c "gradlew.bat :26.1:test"` — **unfiltered** (Kotest; `--tests`
  gives false "No tests found"). Read pass/fail from console or
  `build/test-results/test/*.xml`.
- 5-sourceset compile: `cmd.exe /c "gradlew.bat :26.1:clientClasses :26.1:classes
  :26.1:gametestClasses :26.1:clientTestClasses :26.1:testClasses"`.
- Client in-MC: `cmd.exe /c "gradlew.bat :26.1:runClientTest"`.

All three must be green before claiming done; report actual output.

## Doc sync (CLAUDE.md mandatory audit)

- `docs/ui/dock-framework.md` — extend the "First real panel: the Project
  Explorer" section: the Explorer now has a header bar with a root-picker option
  button; document the **embedded-Compose `Popup` limitation** (why the dropdown
  is hand-rolled) and the **tinyfd worker-thread rule** (native dialog must not run
  on the render thread). Add a short cross-note under the input/rendering gotchas.
- `docs/use-cases/redstone-project.md` — add a UC (e.g. **UC-MAN-09 — Re-root the
  Explorer from a native folder picker**) with `.a`–`.d` sub-IDs (client pick →
  `SetProjectRootC2S` → `handleSetRoot` validate/persist/place/snapshot → error on
  non-directory) and coverage-matrix rows pointing at the new tests. Note Attach
  is Plan B.
- `docs/persistence/` — if a payloads article enumerates the project C2S packets,
  add `SetProjectRootC2S`. Grep first; only touch if it exists.
- New learning note (own file if it generalizes): embedded-Compose `Popup`
  spawns a separate window → hand-roll overlays in the dock; and tinyfd dialogs
  block → run on a worker thread, marshal back via `Minecraft.execute`.
- Update each touched `INDEX.md` summary if a section title changes.

## Rollout

Direct commits to `main` (project workflow), conventional-commit messages
(`feat(ui):`, `feat(project):`, `docs(ui):`). No `Co-Authored-By` / "Generated
with Claude Code" trailer.
