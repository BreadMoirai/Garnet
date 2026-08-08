# Dock Auto-Open Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** On joining a Garnet-capable world, reveal the LEFT dock region (Project Explorer) without taking input focus, remembering whether the player last chose to have it open.

**Architecture:** A new client-only JSON store (`config/garnet-dock.json`) holds one boolean, written on the keypresses that change LEFT visibility and read by a new `ClientPlayConnectionEvents.JOIN` handler in the dock's world-lifecycle registration. The decision logic lives in a `Minecraft`-free function so it is unit-testable; the event handler owns the two Minecraft-side follow-ups (`syncDockViewport()`, `garnet$updateScaledFramebuffer(true)`).

**Tech Stack:** Kotlin, Fabric API (`fabric-networking-api-v1`, `fabric-lifecycle-events-v1`), Gson, Kotest (`FunSpec`/`StringSpec`) on `fabric-loader-junit`, Stonecutter (single active version `26.2`).

**Spec:** `docs/superpowers/specs/2026-08-08-dock-auto-open-design.md`

## Global Constraints

- **Gradle is invoked through the Windows batch wrapper**, never `./gradlew`. Form: `cmd.exe /c "cd /d H:\\Repo\\Garnet && gradlew.bat <tasks...>"`. Do not mix invocation forms within a run — see `docs/tooling/wsl2-gradle-invocation.md`.
- **Stonecutter active version is `26.2`.** All task paths are prefixed `:26.2:`.
- **Compile verification spans five source sets.** The full check is `:26.2:clientClasses :26.2:classes :26.2:gametestClasses :26.2:clientTestClasses :26.2:testClasses` — see `docs/tooling/local-verification-commands.md`.
- **Unit tests live in `src/test/kotlin`** and run via `:26.2:test`. New production code for this feature is client-only and lives under `src/client/kotlin`.
- **Scope is the LEFT region only.** Do not touch RIGHT, BOTTOM, CENTER, or splitter-size persistence.
- **Auto-open never focuses.** `DockInputRouter.focus(...)` must not be called on the join path; `DockState.focusedRegion` stays `null`.
- **Stores degrade, never propagate.** Load failures log at `warn` and return the default; save failures log at `error` and are dropped. Match `ExplorerStateStore`'s `runCatching { }.onFailure { }` shape.
- **Docs sync is mandatory** (project `CLAUDE.md`) and is Task 4 — the feature is not complete without it.
- **Commit messages:** no `Co-Authored-By` trailer, no Claude attribution.

---

### Task 1: `DockLayoutStore` — the `config/garnet-dock.json` round-trip

**Files:**
- Create: `src/client/kotlin/com/breadmoirai/garnet/config/DockLayoutStore.kt`
- Test: `src/test/kotlin/com/breadmoirai/garnet/client/config/DockLayoutStoreTest.kt`
- Read for pattern: `src/client/kotlin/com/breadmoirai/garnet/config/ExplorerStateStore.kt`, `src/test/kotlin/com/breadmoirai/garnet/client/config/ExplorerStateStoreTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces:
  - `object DockLayoutStore`
  - `fun DockLayoutStore.load(): Boolean` — stored `leftVisible`, or `true` when the file is absent, unreadable, malformed, or the key is missing/not a boolean.
  - `fun DockLayoutStore.save(leftVisible: Boolean)`
  - `fun DockLayoutStore.configFileForTest(file: java.io.File)` / `fun DockLayoutStore.resetConfigFileForTest()`

- [ ] **Step 1: Write the failing test**

Create `src/test/kotlin/com/breadmoirai/garnet/client/config/DockLayoutStoreTest.kt`:

```kotlin
package com.breadmoirai.garnet.client.config

import com.breadmoirai.garnet.config.DockLayoutStore
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.io.path.createTempDirectory

class DockLayoutStoreTest : FunSpec({

    /** Runs [body] with the store redirected at a fresh temp `garnet-dock.json`. */
    fun withStore(name: String, body: (java.io.File) -> Unit) {
        val dir = createTempDirectory(name)
        val file = dir.resolve("garnet-dock.json").toFile()
        DockLayoutStore.configFileForTest(file)
        try {
            body(file)
        } finally {
            DockLayoutStore.resetConfigFileForTest()
            dir.toFile().deleteRecursively()
        }
    }

    test("a visible dock round-trips through garnet-dock.json") {
        withStore("garnet-dock-visible") {
            DockLayoutStore.save(true)
            DockLayoutStore.load() shouldBe true
        }
    }

    test("a hidden dock round-trips as false rather than defaulting back to open") {
        withStore("garnet-dock-hidden") {
            DockLayoutStore.save(false)
            DockLayoutStore.load() shouldBe false
        }
    }

    test("a missing file loads as true so a fresh install still auto-opens") {
        val dir = createTempDirectory("garnet-dock-missing")
        DockLayoutStore.configFileForTest(dir.resolve("absent.json").toFile())
        try {
            DockLayoutStore.load() shouldBe true
        } finally {
            DockLayoutStore.resetConfigFileForTest()
            dir.toFile().deleteRecursively()
        }
    }

    test("a malformed file loads as true instead of throwing") {
        withStore("garnet-dock-malformed") { file ->
            file.writeText("{ this is not json")
            DockLayoutStore.load() shouldBe true
        }
    }

    test("a record with no leftVisible key loads as true") {
        withStore("garnet-dock-nokey") { file ->
            file.writeText("""{"somethingElse":1}""")
            DockLayoutStore.load() shouldBe true
        }
    }

    test("a non-boolean leftVisible loads as true instead of throwing") {
        withStore("garnet-dock-badtype") { file ->
            file.writeText("""{"leftVisible":{"nested":true}}""")
            DockLayoutStore.load() shouldBe true
        }
    }

    test("save overwrites a previous record rather than appending") {
        withStore("garnet-dock-overwrite") {
            DockLayoutStore.save(true)
            DockLayoutStore.save(false)
            DockLayoutStore.load() shouldBe false
        }
    }
})
```

- [ ] **Step 2: Run the test to verify it fails**

```sh
cmd.exe /c "cd /d H:\\Repo\\Garnet && gradlew.bat :26.2:test --tests *DockLayoutStoreTest*"
```

Expected: FAIL at compilation — `Unresolved reference: DockLayoutStore`.

- [ ] **Step 3: Write the implementation**

Create `src/client/kotlin/com/breadmoirai/garnet/config/DockLayoutStore.kt`:

```kotlin
package com.breadmoirai.garnet.config

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import net.fabricmc.loader.api.FabricLoader
import org.slf4j.LoggerFactory
import java.io.File

private val LOGGER = LoggerFactory.getLogger("Garnet")

/**
 * The `config/garnet-dock.json` round-trip for the dock's remembered LEFT-region visibility.
 *
 * Deliberately NOT part of [ModConfig]: that object's contract is a pure [SharedSettings] round-trip,
 * and `SharedSettings` is read by the dedicated server — dock visibility is client UI state a server
 * must never see. Deliberately NOT part of [ExplorerStateStore] either: that record is keyed by
 * project root and written only in singleplayer, and dock visibility is neither. Reusing that file
 * would silently inherit both restrictions and break auto-open on a remote Garnet server.
 *
 * A single boolean, not the full layout. Splitter sizes are process-lifetime state that
 * `DockState.closeAll()` already preserves, and nothing needs them to survive a restart yet.
 */
object DockLayoutStore {
    private val defaultFile: File
        get() = FabricLoader.getInstance().configDir.resolve("garnet-dock.json").toFile()

    private var overrideFile: File? = null
    private val configFile: File get() = overrideFile ?: defaultFile

    /** Test seam: redirect reads/writes at [file] instead of the real config directory. */
    fun configFileForTest(file: File) { overrideFile = file }
    fun resetConfigFileForTest() { overrideFile = null }

    /**
     * The remembered LEFT visibility, defaulting to `true`.
     *
     * Every failure path — absent file, unreadable file, malformed JSON, missing or non-boolean key
     * — yields the default rather than propagating. Restoring the layout is a convenience, and
     * "open the dock" is the wanted behaviour for a fresh install, so it is also the right thing to
     * fall back to when the record cannot be trusted.
     */
    fun load(): Boolean {
        val file = configFile
        if (!file.exists()) return true
        return runCatching {
            file.reader().use { reader ->
                val json = JsonParser.parseReader(reader) as? JsonObject ?: return@use true
                val value = json.get("leftVisible") ?: return@use true
                if (!value.isJsonPrimitive || !value.asJsonPrimitive.isBoolean) return@use true
                value.asBoolean
            }
        }.onFailure { e ->
            LOGGER.warn("Failed to load dock layout from {}", file.absolutePath, e)
        }.getOrDefault(true)
    }

    /** Overwrite the stored record with [leftVisible]. */
    fun save(leftVisible: Boolean) {
        val file = configFile
        file.parentFile?.mkdirs()
        val json = JsonObject()
        json.addProperty("leftVisible", leftVisible)
        runCatching {
            file.writeText(json.toString())
        }.onFailure { e ->
            LOGGER.error("Failed to save dock layout to {}", file.absolutePath, e)
        }
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

```sh
cmd.exe /c "cd /d H:\\Repo\\Garnet && gradlew.bat :26.2:test --tests *DockLayoutStoreTest*"
```

Expected: PASS, 7 tests.

- [ ] **Step 5: Commit**

```bash
git add src/client/kotlin/com/breadmoirai/garnet/config/DockLayoutStore.kt \
        src/test/kotlin/com/breadmoirai/garnet/client/config/DockLayoutStoreTest.kt
git commit -m "feat(dock): persist the LEFT region's visibility in config/garnet-dock.json"
```

---

### Task 2: `DockAutoOpenGate` and the `Minecraft`-free `applyDockAutoOpen()`

**Files:**
- Create: `src/client/kotlin/com/breadmoirai/garnet/ui/dock/DockAutoOpen.kt`
- Test: `src/test/kotlin/com/breadmoirai/garnet/client/ui/dock/DockAutoOpenTest.kt`
- Read for pattern: `src/client/kotlin/com/breadmoirai/garnet/editor/ui/ExplorerLifecycle.kt` (the `ExplorerSessionGate` seam), `src/test/kotlin/com/breadmoirai/garnet/client/ui/dock/DockLifecycleTest.kt`

**Note on placement:** the spec said "`DockLifecycleTest` additions". Use a separate `DockAutoOpenTest.kt` instead — it needs a temp-file fixture and a gate override that `DockLifecycleTest`'s existing tests do not, and the project favours one file per responsibility. Behaviour covered is identical.

**Interfaces:**
- Consumes: `DockLayoutStore.load()`, `DockLayoutStore.configFileForTest(File)`, `DockLayoutStore.resetConfigFileForTest()` (Task 1); `DockState.setVisible(DockRegion, Boolean)`, `DockState.isVisible(DockRegion)`, `DockState.reset()`, `DockState.focusedRegion` (existing).
- Produces:
  - `object DockAutoOpenGate` with `var isGarnetServer: () -> Boolean` and `fun resetForTest()`
  - `fun applyDockAutoOpen(): Boolean` — `true` exactly when it just made LEFT visible.

- [ ] **Step 1: Write the failing test**

Create `src/test/kotlin/com/breadmoirai/garnet/client/ui/dock/DockAutoOpenTest.kt`:

```kotlin
package com.breadmoirai.garnet.client.ui.dock

import com.breadmoirai.garnet.config.DockLayoutStore
import com.breadmoirai.garnet.ui.dock.DockAutoOpenGate
import com.breadmoirai.garnet.ui.dock.DockRegion
import com.breadmoirai.garnet.ui.dock.DockState
import com.breadmoirai.garnet.ui.dock.applyDockAutoOpen
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.io.path.createTempDirectory

/**
 * The join-time auto-open decision. `applyDockAutoOpen()` touches only snapshot state and the JSON
 * store, never `Minecraft` — that is the point of the split, and this test is what holds it.
 */
class DockAutoOpenTest : FunSpec({

    /**
     * Runs [body] with a fresh temp store (seeded to [stored] when non-null, left absent otherwise),
     * a stubbed server probe, and a clean [DockState].
     */
    fun withDock(name: String, garnetServer: Boolean, stored: Boolean?, body: () -> Unit) {
        val dir = createTempDirectory(name)
        DockLayoutStore.configFileForTest(dir.resolve("garnet-dock.json").toFile())
        DockState.reset()
        DockAutoOpenGate.isGarnetServer = { garnetServer }
        try {
            if (stored != null) DockLayoutStore.save(stored)
            body()
        } finally {
            DockAutoOpenGate.resetForTest()
            DockLayoutStore.resetConfigFileForTest()
            DockState.reset()
            dir.toFile().deleteRecursively()
        }
    }

    test("a vanilla server leaves the dock hidden") {
        withDock("dock-vanilla", garnetServer = false, stored = true) {
            applyDockAutoOpen() shouldBe false
            DockState.isVisible(DockRegion.LEFT) shouldBe false
        }
    }

    test("a Garnet server honours a stored hidden dock") {
        withDock("dock-stored-hidden", garnetServer = true, stored = false) {
            applyDockAutoOpen() shouldBe false
            DockState.isVisible(DockRegion.LEFT) shouldBe false
        }
    }

    test("a Garnet server opens the dock when the record says visible") {
        withDock("dock-stored-visible", garnetServer = true, stored = true) {
            applyDockAutoOpen() shouldBe true
            DockState.isVisible(DockRegion.LEFT) shouldBe true
        }
    }

    test("a Garnet server with no record opens the dock") {
        withDock("dock-no-record", garnetServer = true, stored = null) {
            applyDockAutoOpen() shouldBe true
            DockState.isVisible(DockRegion.LEFT) shouldBe true
        }
    }

    test("auto-open never takes input focus") {
        withDock("dock-no-focus", garnetServer = true, stored = true) {
            applyDockAutoOpen() shouldBe true
            DockState.focusedRegion shouldBe null
        }
    }

    test("an already-visible dock reports no change so the caller skips the framebuffer churn") {
        withDock("dock-already-open", garnetServer = true, stored = true) {
            DockState.setVisible(DockRegion.LEFT, true)
            applyDockAutoOpen() shouldBe false
            DockState.isVisible(DockRegion.LEFT) shouldBe true
        }
    }

    test("the vanilla-server check short-circuits before the store is consulted") {
        withDock("dock-gate-first", garnetServer = false, stored = null) {
            applyDockAutoOpen() shouldBe false
            DockState.isVisible(DockRegion.LEFT) shouldBe false
        }
    }
})
```

- [ ] **Step 2: Run the test to verify it fails**

```sh
cmd.exe /c "cd /d H:\\Repo\\Garnet && gradlew.bat :26.2:test --tests *DockAutoOpenTest*"
```

Expected: FAIL at compilation — `Unresolved reference: DockAutoOpenGate` / `applyDockAutoOpen`.

- [ ] **Step 3: Write the implementation**

Create `src/client/kotlin/com/breadmoirai/garnet/ui/dock/DockAutoOpen.kt`:

```kotlin
package com.breadmoirai.garnet.ui.dock

import com.breadmoirai.garnet.config.DockLayoutStore
import com.breadmoirai.garnet.editor.network.ListEditorTreeC2S
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking

/**
 * Seam for the "is the peer a Garnet server" probe that gates join-time auto-open.
 *
 * Extracted so unit tests can drive both branches without a server, exactly as
 * `ExplorerSessionGate` does for the singleplayer check.
 *
 * The default asks whether the editor-tree payload can be sent, which is Fabric's standard way of
 * asking whether the other side registered a receiver — i.e. whether it runs Garnet. The payload
 * type is an implementation detail of this probe; the dock shell has no other notion of the Explorer.
 */
object DockAutoOpenGate {
    var isGarnetServer: () -> Boolean = { ClientPlayNetworking.canSend(ListEditorTreeC2S.TYPE) }

    fun resetForTest() {
        isGarnetServer = { ClientPlayNetworking.canSend(ListEditorTreeC2S.TYPE) }
    }
}

/**
 * Applies the remembered LEFT visibility on world join. Returns `true` when the dock actually
 * opened, so the caller can skip [com.breadmoirai.garnet.ui.viewport.syncDockViewport] and the
 * framebuffer resize when nothing changed.
 *
 * Kept free of `Minecraft` for the same reason [DockState.closeAll] is: the decision is plain state
 * plus one JSON read, and that is worth being able to unit-test without a render context. The two
 * Minecraft-side follow-ups belong to the event handler in `viewport/DockKeybinds.kt`.
 *
 * Focus is deliberately NOT taken. The dock appears, the game keeps keyboard/pointer input and the
 * cursor stays grabbed; Alt+1 remains the only way to hand focus to the panel.
 */
fun applyDockAutoOpen(): Boolean {
    // Ask the gate before the store: on a vanilla server the answer is "no" regardless of what was
    // remembered, and there is no reason to touch the filesystem to find that out.
    if (!DockAutoOpenGate.isGarnetServer()) return false
    if (!DockLayoutStore.load()) return false
    if (DockState.isVisible(DockRegion.LEFT)) return false
    DockState.setVisible(DockRegion.LEFT, true)
    return true
}
```

- [ ] **Step 4: Run the test to verify it passes**

```sh
cmd.exe /c "cd /d H:\\Repo\\Garnet && gradlew.bat :26.2:test --tests *DockAutoOpenTest*"
```

Expected: PASS, 7 tests.

- [ ] **Step 5: Commit**

```bash
git add src/client/kotlin/com/breadmoirai/garnet/ui/dock/DockAutoOpen.kt \
        src/test/kotlin/com/breadmoirai/garnet/client/ui/dock/DockAutoOpenTest.kt
git commit -m "feat(dock): decide join-time auto-open behind a testable Garnet-server gate"
```

---

### Task 3: Wire it into the client — JOIN handler and the persist-on-keypress calls

**Files:**
- Modify: `src/client/kotlin/com/breadmoirai/garnet/ui/viewport/DockKeybinds.kt` (the `registerDockKeybinds` body, and `registerDockWorldLifecycle` plus its KDoc)
- Modify: `src/client/kotlin/com/breadmoirai/garnet/GarnetClient.kt:26` (the seeding comment only)

**Interfaces:**
- Consumes: `applyDockAutoOpen()` (Task 2), `DockLayoutStore.save(Boolean)` (Task 1), existing `syncDockViewport()`, `WindowViewportExt.garnet$updateScaledFramebuffer(Boolean)`, `DockState`, `DockInputRouter`.
- Produces: no new symbols. `registerDockWorldLifecycle()` gains a JOIN handler.

This task has no unit test of its own: every line added touches `Minecraft`, `ClientPlayConnectionEvents`, or the window mixin, which is exactly the code the Task 2 split pushed *out* of the testable core. Verification is a full five-source-set compile plus the existing suite.

- [ ] **Step 1: Add the two persist calls in `registerDockKeybinds`**

In `src/client/kotlin/com/breadmoirai/garnet/ui/viewport/DockKeybinds.kt`, add the import:

```kotlin
import com.breadmoirai.garnet.config.DockLayoutStore
```

Replace the `shift ->` branch body (currently lines 36-41) with:

```kotlin
                shift -> {
                    DockState.toggleVisible(DockRegion.LEFT)
                    if (!DockState.isVisible(DockRegion.LEFT) && DockState.focusedRegion == DockRegion.LEFT) {
                        DockInputRouter.clearFocus()
                    }
                    // Persist here, on the user's keypress, rather than reading DockState at
                    // DISCONNECT: closeAll() hides LEFT on that same event, so a disconnect-time
                    // save would race Fabric's handler ordering and persist the programmatic close
                    // instead of what the player chose. Writing on the keypress also means no
                    // CLIENT_STOPPING backstop is needed for the close-the-window-in-world path.
                    DockLayoutStore.save(DockState.isVisible(DockRegion.LEFT))
                    syncDockViewport()
                    (mc.window as Any as WindowViewportExt).`garnet$updateScaledFramebuffer`(true)
                }
```

Replace the `alt ->` branch body (currently lines 44-47) with:

```kotlin
                alt -> {
                    if (DockState.focusedRegion == DockRegion.LEFT) {
                        // Focus-only change: visibility is untouched, so nothing to persist.
                        DockInputRouter.clearFocus()
                    } else {
                        DockState.setVisible(DockRegion.LEFT, true)
                        DockLayoutStore.save(true)
                        DockInputRouter.focus(DockRegion.LEFT)
                    }
                    syncDockViewport()
                    (mc.window as Any as WindowViewportExt).`garnet$updateScaledFramebuffer`(true)
                }
```

- [ ] **Step 2: Add the JOIN handler in `registerDockWorldLifecycle`**

Add the import:

```kotlin
import com.breadmoirai.garnet.ui.dock.applyDockAutoOpen
```

Insert this handler at the top of the `registerDockWorldLifecycle()` body, before the existing `ClientPlayConnectionEvents.DISCONNECT.register { ... }` block:

```kotlin
    ClientPlayConnectionEvents.JOIN.register { _, _, mc ->
        mc.execute {
            // Only follow up when the dock actually opened: applyDockAutoOpen returns false on a
            // vanilla server, on a remembered-hidden dock, and when LEFT is somehow already up, and
            // a framebuffer resize for a no-op change is pure churn.
            if (applyDockAutoOpen()) {
                syncDockViewport()
                (mc.window as Any as WindowViewportExt).`garnet$updateScaledFramebuffer`(true)
            }
        }
    }
```

- [ ] **Step 3: Update `registerDockWorldLifecycle`'s KDoc**

The existing KDoc opens with "Closes the dock when the client leaves a world" and closes by asserting "this dock shell has no notion of the Explorer feature". Both are now wrong. Replace the entire KDoc block above `fun registerDockWorldLifecycle()` with:

```kotlin
/**
 * Opens the dock on world join and closes it when the client leaves.
 *
 * JOIN restores the remembered LEFT visibility through [applyDockAutoOpen] — gated on the peer being
 * a Garnet server, and deliberately visibility-only, so the game keeps input and the cursor stays
 * grabbed. See `ui/dock/DockAutoOpen.kt` for both decisions.
 *
 * `DISCONNECT` covers every exit path that matters: quit-to-title from singleplayer, a multiplayer
 * disconnect, and a server kick. Without this the dock keeps painting over the title screen and the
 * viewport stays shrunk, because [DockState] is a client-lifetime singleton with no notion of a world.
 *
 * The `garnet$updateScaledFramebuffer(true)` follow-up mirrors both keybind branches in
 * [registerDockKeybinds]: without it the shrink survives until something else resizes the framebuffer.
 * On the JOIN side it is run only when the dock actually opened, since a resize for an unchanged
 * layout is pure churn.
 *
 * Both bodies run inside `mc.execute { ... }` because `fabric-networking-api-v1` fires these events
 * from two sites in `ClientConnectionMixin` — `handleDisconnection` on the main thread, or
 * `channelInactive` on a **Netty event-loop thread**, whichever wins the CAS — and
 * `garnet$updateScaledFramebuffer` reaches `eventHandler.resizeGui()`, which is unsafe to call
 * concurrently with rendering off the render thread.
 *
 * Explorer-specific state (the tree snapshot, its expansion/selection, and the join-time tree
 * request) lives separately in `editor/ui/ExplorerLifecycle.kt`'s `registerExplorerLifecycle()`.
 * This dock shell knows only whether the peer is a Garnet server, asked through [DockAutoOpenGate].
 */
```

This needs one more import:

```kotlin
import com.breadmoirai.garnet.ui.dock.DockAutoOpenGate
```

- [ ] **Step 4: Update the stale comment in `GarnetClient.kt`**

Line 26 currently reads:

```kotlin
        // Seed the Project Explorer into the LEFT dock (region stays hidden until Shift+1 reveals it).
```

Replace with:

```kotlin
        // Seed the Project Explorer into the LEFT dock. The region starts hidden; joining a Garnet
        // world reveals it (see applyDockAutoOpen), and Shift+1 toggles it by hand.
```

- [ ] **Step 5: Verify the whole project compiles across all five source sets**

```sh
cmd.exe /c "cd /d H:\\Repo\\Garnet && gradlew.bat :26.2:clientClasses :26.2:classes :26.2:gametestClasses :26.2:clientTestClasses :26.2:testClasses"
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Run the full unit-test suite**

```sh
cmd.exe /c "cd /d H:\\Repo\\Garnet && gradlew.bat :26.2:test"
```

Expected: BUILD SUCCESSFUL, no regressions in `DockLifecycleTest`, `DockInsetsTest`, `DockViewportSyncTest`, `ExplorerLifecycleTest`.

- [ ] **Step 7: Commit**

```bash
git add src/client/kotlin/com/breadmoirai/garnet/ui/viewport/DockKeybinds.kt \
        src/client/kotlin/com/breadmoirai/garnet/GarnetClient.kt
git commit -m "feat(dock): auto-open the dock on joining a Garnet world"
```

---

### Task 4: Docs sync

**Files:**
- Modify: `docs/ui/dock-framework.md`
- Modify: `docs/ui/dock-input-routing.md`
- Modify: `docs/persistence/explorer-session-state.md`
- Modify: `docs/ui/INDEX.md`
- Modify: `docs/persistence/INDEX.md`

**Interfaces:**
- Consumes: the behaviour shipped in Tasks 1-3.
- Produces: nothing consumed by code.

Project `CLAUDE.md` makes this mandatory after any source change, and the doc conventions are: one article per topic, frontmatter with `title`/`tags`/`summary`, every article registered in its category `INDEX.md` as `- [Title](filename.md) — summary`.

- [ ] **Step 1: Find every stale claim**

```sh
grep -rn "Shift+1\|hidden until\|OFF-by-default\|off by default\|garnet-explorer.json" docs/ --include=*.md
```

Read each hit. Any sentence claiming the dock is hidden until the player presses a key is now only true for non-Garnet servers and for a remembered-hidden dock.

- [ ] **Step 2: Update `docs/ui/dock-framework.md`**

Amend the visibility description to state: edge regions start hidden at client init; on joining a Garnet-capable world (`ClientPlayNetworking.canSend(ListEditorTreeC2S.TYPE)`) the LEFT region is revealed by `applyDockAutoOpen()` in `ui/dock/DockAutoOpen.kt`, subject to the boolean remembered in `config/garnet-dock.json`; a vanilla server never gets a dock, because an empty Explorer that shrinks the viewport is worse than none. Note that `DockAutoOpenGate.isGarnetServer` is the test seam.

- [ ] **Step 3: Update `docs/ui/dock-input-routing.md`**

Add a sentence to the keybind/focus section: auto-open changes visibility only. `DockState.focusedRegion` stays `null`, the input mixins keep routing to the game, and the cursor stays grabbed — Alt+1 remains the only path to focusing the panel.

- [ ] **Step 4: Update `docs/persistence/explorer-session-state.md`**

Add a short "Sibling store" section: `config/garnet-dock.json` (`DockLayoutStore`) holds the dock's remembered LEFT visibility. It is a separate file from `garnet-explorer.json` because that record is keyed by project root and written only in singleplayer, whereas dock visibility is neither and must also restore on a remote Garnet server. It is written on the Shift+1 / Alt+1 keypresses rather than at DISCONNECT, because `DockState.closeAll()` hides LEFT on that same event and a disconnect-time read would race handler ordering.

- [ ] **Step 5: Refresh both INDEX.md summaries**

Update the `dock-framework.md`, `dock-input-routing.md`, and `explorer-session-state.md` entry summaries so they mention auto-open / `garnet-dock.json`. Add `lifecycle` to `dock-framework.md`'s tags and `dock` to `explorer-session-state.md`'s tags if not already present. Keep the `- [Title](filename.md) — summary _[tags]_` shape used by the surrounding lines.

- [ ] **Step 6: Verify every cross-reference resolves**

```sh
grep -rn "garnet-dock.json\|applyDockAutoOpen\|DockAutoOpenGate\|DockLayoutStore" docs/ --include=*.md
```

Expected: hits in the four articles above and the two INDEX files, and every file path they name exists on disk.

- [ ] **Step 7: Commit**

```bash
git add docs/
git commit -m "docs: cover join-time dock auto-open and the garnet-dock.json store"
```

---

## Manual verification (after Task 4)

Not automated — the join path needs a real client. Run once:

```sh
cmd.exe /c "cd /d H:\\Repo\\Garnet && gradlew.bat :26.2:runClient"
```

1. Load a singleplayer world → the Explorer appears on the left, the world viewport shrinks, and the cursor is still grabbed (WASD moves, mouse looks).
2. Press Shift+1 to hide it, quit to title, rejoin → the dock stays hidden.
3. Press Shift+1 to show it, quit to title, rejoin → the dock is back.
4. Check `<gameDir>/config/garnet-dock.json` tracks each toggle.
