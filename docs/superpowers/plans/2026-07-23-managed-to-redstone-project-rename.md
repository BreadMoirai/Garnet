# `managed` → `redstone project` Rename Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Rebrand the "managed worlds / managed specs" subsystem to "redstone project" — a full rename of code symbols, packages, network/disk/config identity strings, and living docs — with a green build and green tests at the end.

**Architecture:** This is a *mechanical rename*, not a behavior change, so classic red-green TDD does not apply — the existing test suite is the safety net. Each task ends by rebuilding all five source sets and running the tests, and a rename is only "done" when they pass. The code rename is **atomic** (one commit): `Managed*` symbols are referenced across `main`, `client`, `gametest`, `clientTest`, and `test`, so a partial rename cannot compile.

**Tech Stack:** Kotlin, Fabric, Loom split-environment source sets, Stonecutter (`:26.1:` task prefix), Gradle via `cmd.exe /c "gradlew.bat …"`, WSL bash for file edits.

## Global Constraints

- **Decision D10/D11 (from the spec):** full rename now, **break compat** — rename identity strings too (`project_*` channels, `projectRootPath` config key, `project-<name>` disk prefix). No migration shim.
- **NEVER rename `managedBlock`** — `server.managedBlock { … }` in `src/main/kotlin/com/breadmoirai/garnet/testing/core/Ticks.kt` is a Minecraft `MinecraftServer` API method. The scoped replacements below are designed to miss it; a blanket `s/managed/project/g` is **forbidden**.
- **Do NOT touch `docs/superpowers/plans/` or `docs/superpowers/specs/`** — CLAUDE.md declares them historical artifacts.
- **Build command (all 5 source sets):**
  ```sh
  cmd.exe /c "cd /d H:\\Repo\\garnet && gradlew.bat :26.1:clientClasses :26.1:classes :26.1:gametestClasses :26.1:clientTestClasses :26.1:testClasses"
  ```
- **Unit-test command:** `cmd.exe /c "gradlew.bat :26.1:test"` — Kotest/JUnit `--tests` filters are unreliable in this project; run unfiltered and read `build/test-results/test/*.xml` for pass/fail.
- All file edits (git mv, sed) run in WSL bash from the repo root `/mnt/h/Repo/garnet`.

## Symbol Mapping (authoritative)

PascalCase types — a global `s/Managed/Project/g` over `.kt`/`.java` sources handles every one of these (each embeds the literal `Managed`), and is collision-safe because no Minecraft/library type used here contains `Managed`:

| Old | New | | Old | New |
|---|---|---|---|---|
| `ManagedServerContext` | `ProjectServerContext` | | `ManagedCell` | `ProjectCell` |
| `ManagedLeaf` | `ProjectLeaf` | | `ManagedCellSaver` | `ProjectCellSaver` |
| `ManagedSession` | `ProjectSession` | | `ManagedSaveNaming` | `ProjectSaveNaming` |
| `ManagedNewSpec` | `ProjectNewSpec` | | `ManagedTeleport` | `ProjectTeleport` |
| `ManagedFolderTree` | `ProjectFolderTree` | | `ManagedDimRegistry` | `ProjectDimRegistry` |
| `ManagedRoot` | `ProjectRoot` | | `ManagedDimLifecycle` | `ProjectDimLifecycle` |
| `ManagedCommand` | `ProjectCommand` | | `ManagedWorld` | `ProjectWorld` |
| `ManagedRootsConfig` | `ProjectRootsConfig` | | | |
| `ManagedNetworkRegistry` | `ProjectNetworkRegistry` | | `ManagedTreeSnapshotS2C` | `ProjectTreeSnapshotS2C` |
| `ManagedLeafEntry` | `ProjectLeafEntry` | | `ManagedFolderLoadedS2C` | `ProjectFolderLoadedS2C` |
| `ManagedSaveReportS2C` | `ProjectSaveReportS2C` | | `ManagedErrorS2C` | `ProjectErrorS2C` |
| `ListManagedTreeC2S` | `ListProjectTreeC2S` | | `LoadManagedFolderC2S` | `LoadProjectFolderC2S` |
| `UnloadManagedFolderC2S` | `UnloadProjectFolderC2S` | | `NewManagedSpecC2S` | `NewProjectSpecC2S` |
| `ManagedClientNetworking` | `ProjectClientNetworking` | | `ManagedIntegratedBoot` | `ProjectIntegratedBoot` |
| `ManagedRootListScreen` | `ProjectRootListScreen` | | `ManagedScreen` | `ProjectScreen` |
| `ManagedCellSaverSpec` | `ProjectCellSaverSpec` | | `ManagedCommandSpec` | `ProjectCommandSpec` |
| `ManagedDimSpec` | `ProjectDimSpec` | | `ManagedNetworkRegistrySpec` | `ProjectNetworkRegistrySpec` |
| `ManagedTeleportSpec` | `ProjectTeleportSpec` | | `ManagedTestSupport` | `ProjectTestSupport` |
| `ManagedCellTest` | `ProjectCellTest` | | `ManagedDimRegistryTest` | `ProjectDimRegistryTest` |
| `ManagedFolderTreeTest` | `ProjectFolderTreeTest` | | `ManagedLifecycleReleaseTest` | `ProjectLifecycleReleaseTest` |
| `ManagedNewSpecTest` | `ProjectNewSpecTest` | | `ManagedRootTest` | `ProjectRootTest` |
| `ManagedRootsConfigTest` | `ProjectRootsConfigTest` | | `ManagedSaveNamingTest` | `ProjectSaveNamingTest` |
| `ManagedSessionTest` | `ProjectSessionTest` | | `ManagedEntryFlowSpec` | `ProjectEntryFlowSpec` |

Unchanged types (no `Managed` in the name — do not rename, just moved by package): `GridLayout`, `LoadedSpec`, `CellSaveResult`, `ParseError`, `LoadFolderReport`, `LayoutInput`, `LayoutError`, `LayoutResult`, `SaveNowC2S`, `GridLayoutTest`, `LoadedSpecTest`.

Lowercase identity strings — replaced with **scoped** patterns (never bare `managed`):

| Pattern (regex) | Replacement | Where |
|---|---|---|
| `\.managed\b` | `.project` | package/import path segments (misses `managedBlock`) |
| `managedRootPath` | `projectRootPath` | `SharedSettings` field + all refs |
| `"managed_` | `"project_` | payload channel ids (`ProjectPackets.kt`) |
| `"managed-` | `"project-` | disk folder prefix (`ProjectSaveNaming.kt`) + `"managed-root not configured"` |
| `literal("managed")` | `literal("project")` | command node (`ProjectCommand.kt`) |
| `\[managed/` | `[project/` | log tags in `ProjectNetworkRegistry.kt` |

---

### Task 1: Atomic code rename (all five source sets)

**Files:**
- Rename (git mv) the four `managed` package directories and every `Managed*.kt` file within them (server, network, client, test, gametest), plus the standalone `ManagedEntryFlowSpec.kt`.
- Modify: every `.kt`/`.java` under `src/` that references a renamed symbol or the `.managed` package path (import fix-ups happen via the sed pass).
- Modify: `src/main/kotlin/com/breadmoirai/garnet/config/SharedSettings.kt`, `src/main/kotlin/com/breadmoirai/garnet/garnet.kt`, `src/main/kotlin/com/breadmoirai/garnet/network/NetworkRegistry.kt`, `src/main/kotlin/com/breadmoirai/garnet/block/SpecBlockEntity.kt`, `src/main/kotlin/com/breadmoirai/garnet/runner/RecordingDslEmitter.kt` (KDoc ref).
- Modify: `src/gametest/kotlin/com/breadmoirai/garnet/test/GametestSentinel.kt`, `src/clientTest/kotlin/com/breadmoirai/garnet/test/ClientTestSentinel.kt` (import + `::class` registrations — handled by the sed pass).
- **Must not touch:** `src/main/kotlin/com/breadmoirai/garnet/testing/core/Ticks.kt` (`managedBlock`).

**Interfaces:**
- Consumes: nothing (first task).
- Produces: renamed public symbols per the mapping table. Later tasks and Phase 1 rely on `ProjectTreeSnapshotS2C`, `ProjectClientNetworking`, `ProjectCommand`, `ProjectSaveNaming`, `projectRootPath`, and package `com.breadmoirai.garnet.project` / `…network.project` / `…client.project`.

- [ ] **Step 1: Move the package directories (git mv)**

```bash
cd /mnt/h/Repo/garnet
base=src
for root in \
  main/kotlin/com/breadmoirai/garnet/managed \
  main/kotlin/com/breadmoirai/garnet/network/managed \
  client/kotlin/com/breadmoirai/garnet/client/managed \
  test/kotlin/com/breadmoirai/garnet/managed \
  gametest/kotlin/com/breadmoirai/garnet/test/managed ; do
    git mv "$base/$root" "$base/${root%managed}project"
done
```

- [ ] **Step 2: Rename the `Managed*` files (git mv)**

```bash
cd /mnt/h/Repo/garnet
# ManagedEntryFlowSpec lives directly under the clientTest `test` package, not a subdir
git mv src/clientTest/kotlin/com/breadmoirai/garnet/test/ManagedEntryFlowSpec.kt \
       src/clientTest/kotlin/com/breadmoirai/garnet/test/ProjectEntryFlowSpec.kt
# All Managed*.kt now living under a renamed `project/` dir
find src -type f -name 'Managed*.kt' | while read -r f; do
  git mv "$f" "$(dirname "$f")/$(basename "$f" | sed 's/^Managed/Project/')"
done
```

- [ ] **Step 3: Verify no `Managed*.kt` files and no `/managed/` dirs remain**

Run:
```bash
cd /mnt/h/Repo/garnet
find src -name 'Managed*.kt' -o -path '*/managed/*' | grep . && echo "LEFTOVERS" || echo "clean"
```
Expected: `clean`

- [ ] **Step 4: Apply the PascalCase symbol rename across all sources**

```bash
cd /mnt/h/Repo/garnet
grep -rlZ 'Managed' src --include='*.kt' --include='*.java' \
  | xargs -0 sed -i 's/Managed/Project/g'
```

- [ ] **Step 5: Apply the scoped lowercase identity-string renames**

```bash
cd /mnt/h/Repo/garnet
files=$(grep -rlE '\.managed\b|managedRootPath|"managed_|"managed-|literal\("managed"\)|\[managed/' src --include='*.kt' --include='*.java')
for f in $files; do
  sed -i -E \
    -e 's/\.managed\b/.project/g' \
    -e 's/managedRootPath/projectRootPath/g' \
    -e 's/"managed_/"project_/g' \
    -e 's/"managed-/"project-/g' \
    -e 's/literal\("managed"\)/literal("project")/g' \
    -e 's/\[managed\//[project\//g' \
    "$f"
done
```

- [ ] **Step 6: Curate user-facing copy (rebrand to "Redstone Project")**

The blunt rename leaves player-visible strings reading "Project Specs"/"Managed". Fix them by hand to the rebrand wording. Edit each occurrence:
- `ProjectCommand.kt` — the `§cManaged root not configured…` message → `§cRedstone Project root not configured. Use the world-list 'Redstone Projects…' button (singleplayer) or set 'projectRootPath' in config (dedicated server).`
- `TitleScreenMixin.java` — the title-screen button label "Managed Specs..." → "Garnet Projects...".
- `ProjectScreen.kt` / `ProjectRootListScreen.kt` — screen titles and headers "Managed Specs" / "Managed Spec Roots" → "Redstone Projects" / "Redstone Project Roots"; the "📁" leaf rows and status text keep their format.
- `SpecBlockEntity.kt` — log `"[finalize] managed: wrote…"` → `"[finalize] project: wrote…"`.

Find remaining player-facing "Managed"/"managed" mentions to curate:
```bash
cd /mnt/h/Repo/garnet
grep -rniE '\bmanaged\b' src --include='*.kt' --include='*.java' | grep -v 'managedBlock'
```
Curate any player-facing hit; leave `managedBlock`.

- [ ] **Step 7: Guard — confirm `managedBlock` survived and no stray symbols remain**

Run:
```bash
cd /mnt/h/Repo/garnet
grep -rn 'managedBlock' src/main/kotlin/com/breadmoirai/garnet/testing/core/Ticks.kt   # must still print 2 hits
grep -rnE 'Managed|garnet\.managed' src --include='*.kt' --include='*.java'             # must print nothing
```
Expected: `Ticks.kt` still shows `server.managedBlock`; the second grep prints nothing.

- [ ] **Step 8: Build all five source sets**

Run:
```sh
cmd.exe /c "cd /d H:\\Repo\\garnet && gradlew.bat :26.1:clientClasses :26.1:classes :26.1:gametestClasses :26.1:clientTestClasses :26.1:testClasses"
```
Expected: `BUILD SUCCESSFUL`. If a source set fails, it is almost always a missed reference — re-run Step 7's second grep and fix.

- [ ] **Step 9: Run unit tests**

Run:
```sh
cmd.exe /c "gradlew.bat :26.1:test"
```
Expected: `BUILD SUCCESSFUL`. If Gradle reports failures, read `build/test-results/test/TEST-*.xml` — a rename should not change any assertion, so any failure is a missed/incorrect reference.

- [ ] **Step 10: Commit**

```bash
cd /mnt/h/Repo/garnet
git add -A
git commit -m "refactor(project): rename managed worlds subsystem to redstone project

Full symbol + package + identity-string rename (managed -> project),
breaking on-disk/config/wire compat by decision. MinecraftServer.managedBlock
left untouched. Behavior unchanged; existing tests are the safety net."
```

---

### Task 2: Rename the living docs

**Files:**
- Rename: `docs/architecture/managed-redstone-worlds.md` → `docs/architecture/redstone-project.md`.
- Rename: `docs/use-cases/managed-worlds.md` → `docs/use-cases/redstone-project.md`.
- Modify: `docs/architecture/INDEX.md`, `docs/use-cases/INDEX.md`, `docs/use-cases/command.md`, `docs/use-cases/cross-cutting.md`, `docs/use-cases/networking.md`, `docs/gametest/kotest-bridge.md`, and any other living doc still mentioning `managed` / `Managed*` symbols.
- **Must not touch:** anything under `docs/superpowers/`.

**Interfaces:**
- Consumes: the renamed symbols from Task 1 (docs cite `Project*` class names and `garnet.project` paths).
- Produces: nothing code depends on.

- [ ] **Step 1: Move the two renamed articles**

```bash
cd /mnt/h/Repo/garnet
git mv docs/architecture/managed-redstone-worlds.md docs/architecture/redstone-project.md
git mv docs/use-cases/managed-worlds.md docs/use-cases/redstone-project.md
```

- [ ] **Step 2: Update living-doc bodies, frontmatter, and cross-links**

Apply the code-symbol map and update prose/titles/tags in living docs only (exclude `docs/superpowers`):
```bash
cd /mnt/h/Repo/garnet
files=$(grep -rlE 'Managed|managed' docs --include='*.md' | grep -v '^docs/superpowers/')
for f in $files; do
  sed -i -E \
    -e 's/Managed/Project/g' \
    -e 's/garnet\.managed/garnet.project/g' \
    -e 's/managed-redstone-worlds\.md/redstone-project.md/g' \
    -e 's/managed-worlds\.md/redstone-project.md/g' \
    "$f"
done
```
Then hand-edit for readable rebrand wording (headings, `summary:` frontmatter, INDEX one-liners): the concept is now **"redstone project"** / **"Redstone Projects"**, not "Project worlds"/"Project Specs". Check the two moved articles' `title:`/`summary:` and both INDEX entries read naturally.

- [ ] **Step 3: Verify no dangling references or stale filenames**

Run:
```bash
cd /mnt/h/Repo/garnet
grep -rnE 'managed-redstone-worlds|managed-worlds\.md|garnet\.managed|\bManaged[A-Z]' docs --include='*.md' | grep -v '^docs/superpowers/'
```
Expected: prints nothing.

- [ ] **Step 4: Commit**

```bash
cd /mnt/h/Repo/garnet
git add -A
git commit -m "docs: rebrand managed-worlds articles to redstone-project"
```

---

### Task 3: Final verification sweep

**Files:** none created; this task is a gate that fails loudly if anything was missed.

**Interfaces:**
- Consumes: the completed rename from Tasks 1–2.
- Produces: confidence that Phase 1 can start.

- [ ] **Step 1: Full-repo residual scan (code + living docs)**

Run:
```bash
cd /mnt/h/Repo/garnet
echo "--- code ---"
grep -rnE 'Managed|garnet\.managed' src --include='*.kt' --include='*.java'
echo "--- living docs ---"
grep -rnE '\bManaged[A-Z]|garnet\.managed|managed-worlds\.md|managed-redstone-worlds' docs --include='*.md' | grep -v '^docs/superpowers/'
echo "--- managedBlock intact ---"
grep -rn 'managedBlock' src/main/kotlin/com/breadmoirai/garnet/testing/core/Ticks.kt
```
Expected: the first two sections print nothing; the third prints the two `server.managedBlock` lines.

- [ ] **Step 2: Full build + unit tests (final gate)**

Run:
```sh
cmd.exe /c "cd /d H:\\Repo\\garnet && gradlew.bat :26.1:clientClasses :26.1:classes :26.1:gametestClasses :26.1:clientTestClasses :26.1:testClasses"
cmd.exe /c "gradlew.bat :26.1:test"
```
Expected: both `BUILD SUCCESSFUL`.

- [ ] **Step 3: (Optional, heavier) runtime harnesses**

If time allows, confirm the runtime harnesses still pass — these boot a server/client and are slow:
```sh
cmd.exe /c "gradlew.bat :26.1:runGameTest"
cmd.exe /c "gradlew.bat :26.1:runClientTest"
```
Expected: `BUILD SUCCESSFUL`. (The `Project*Spec` gametests and `ProjectEntryFlowSpec` exercise the renamed command, networking, and title-screen flow.)

- [ ] **Step 4: No-op commit guard**

If Steps 1–2 surfaced fixes, commit them:
```bash
cd /mnt/h/Repo/garnet
git add -A && git commit -m "refactor(project): mop up residual managed references" || echo "nothing to fix"
```

---

## Self-Review

**Spec coverage** (Phase 0 of the design spec §5): package rename ✓ (Task 1 Steps 1–2); `Managed*` class rename ✓ (mapping + Step 4); network payload package/ids ✓ (Steps 1, 4, 5); command literal ✓ (Step 5); ~15 tests + sentinels ✓ (Steps 1–2, 4); docs incl. INDEX/tags/cross-refs ✓ (Task 2); identity strings incl. break-compat config key / disk prefix / channel ✓ (Step 5, Global Constraints); 5-source-set green-build gate ✓ (Task 1 Step 8, Task 3 Step 2). No spec requirement left unmapped.

**Placeholder scan:** every step has exact commands/paths; no TBD/TODO/"handle errors"/"similar to". Curation steps name the exact strings to change.

**Type consistency:** the mapping table is the single source; Steps 4–5 mechanically apply it; Step 7/Task 3 grep-guards prove no old symbol survives and `managedBlock` is preserved. Package targets (`…project`, `…network.project`, `…client.project`, `…test.project`) match across move (Step 1) and path-segment sed (Step 5).

**Collision guard:** the `managedBlock` MC-API hazard is called out in Global Constraints and asserted in Task 1 Step 7 and Task 3 Step 1.
