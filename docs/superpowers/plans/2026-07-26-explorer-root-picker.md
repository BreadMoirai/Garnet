# Explorer Root-Picker Header (Plan A) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a header bar to the Project Explorer with an option-button dropdown ("Open Folder" / "Attach Folder"); "Open Folder" opens a native OS folder picker and swaps the single project root end-to-end. "Attach Folder" is shown disabled (Plan B).

**Architecture:** The client picks a folder with `TinyFileDialogs` (already on the classpath) behind an injectable seam, runs it off the render thread, persists the choice via the client `ModConfig`, and sends a new `SetProjectRootC2S(path)`. The server validates the directory, pins a new `ProjectServerContext`, re-runs `ProjectDimLifecycle.placeAll`, and re-sends the existing single-root `ProjectTreeSnapshotS2C`. The whole existing snapshot/render/load path is reused unchanged — only *which* folder is root changes.

**Tech Stack:** Kotlin, Fabric (MC 26.1), Compose Multiplatform (foundation-only, embedded `ImageComposeScene`), Kotest, `org.lwjgl.util.tinyfd.TinyFileDialogs`.

## Global Constraints

- Single-root only. `ProjectTreeSnapshotS2C` keeps its `root: FolderNode` (no list). `SharedSettings.projectRootPath` stays a single `String`. No root-namespaced paths, no per-root region/placement keying. All multi-root work is Plan B.
- **Attach Folder** is rendered **disabled** (dimmed, non-clickable). No attach behavior, no `attach` flag on the packet.
- `src/main/kotlin/.../project/FileTree.kt` (the model) is **not** modified.
- Compose in the dock is **foundation-only**; **no** Material `DropdownMenu`/`Popup` (a `Popup` spawns a separate desktop window the embedded scene cannot host). Dropdown is a hand-rolled z-layered overlay.
- The native folder dialog **blocks** — it must run on a worker thread, never the render/client thread. Result marshals back to the client thread via `Minecraft.getInstance().execute {}`.
- Persistence is **client-side** via `ModConfig` (writes `<configDir>/garnet.json`); the main-sourceset server handler cannot reach it.
- WSL build/test invocation is `cmd.exe /c "gradlew.bat ..."` (no `./`). Gradle task paths are `:26.1:...`. Kotest runs **unfiltered** (`--tests` gives false "No tests found").
- Git: direct commits to `main`, conventional-commit messages, **no** `Co-Authored-By` / "Generated with Claude Code" trailer.

---

### Task 1: `SetProjectRootC2S` payload + codec round-trip

**Files:**
- Modify: `src/main/kotlin/com/breadmoirai/garnet/network/project/ProjectPackets.kt`
- Test: `src/test/kotlin/com/breadmoirai/garnet/network/project/FileTreeCodecTest.kt`

**Interfaces:**
- Produces: `data class SetProjectRootC2S(val path: String) : CustomPacketPayload` with `companion object { val TYPE; val STREAM_CODEC }` in package `com.breadmoirai.garnet.network.project`.

- [ ] **Step 1: Write the failing round-trip test**

Append this test inside the existing `FileTreeCodecTest` `FunSpec({ ... })` block in `FileTreeCodecTest.kt`:

```kotlin
    test("SetProjectRootC2S round-trips its path through STREAM_CODEC") {
        val payload = SetProjectRootC2S("/abs/some/workspace")
        val buf = io.netty.buffer.Unpooled.buffer()
        SetProjectRootC2S.STREAM_CODEC.encode(buf, payload)
        val decoded = SetProjectRootC2S.STREAM_CODEC.decode(buf)
        decoded shouldBe payload
    }
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cmd.exe /c "gradlew.bat :26.1:test"`
Expected: compile failure / FAIL — `SetProjectRootC2S` is unresolved.

- [ ] **Step 3: Add the payload**

In `ProjectPackets.kt`, in the `// === C2S ===` section (next to `LoadProjectFolderC2S`), add:

```kotlin
data class SetProjectRootC2S(val path: String) : CustomPacketPayload {
    companion object {
        val TYPE = CustomPacketPayload.Type<SetProjectRootC2S>(id("set_root"))
        val STREAM_CODEC: StreamCodec<ByteBuf, SetProjectRootC2S> = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, SetProjectRootC2S::path,
            ::SetProjectRootC2S,
        )
    }
    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE
}
```

(`id`, `ByteBufCodecs`, `StreamCodec`, `ByteBuf`, `CustomPacketPayload` are already imported in this file.)

- [ ] **Step 4: Run the test to verify it passes**

Run: `cmd.exe /c "gradlew.bat :26.1:test"`
Expected: PASS (check console summary or `build/26.1/test-results/test/*.xml`).

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/breadmoirai/garnet/network/project/ProjectPackets.kt \
        src/test/kotlin/com/breadmoirai/garnet/network/project/FileTreeCodecTest.kt
git commit -m "feat(project): add SetProjectRootC2S payload with round-trip codec"
```

---

### Task 2: Server `handleSetRoot` + registration + gametest

**Files:**
- Modify: `src/main/kotlin/com/breadmoirai/garnet/network/project/ProjectNetworkRegistry.kt`
- Test: `src/gametest/kotlin/com/breadmoirai/garnet/test/project/ProjectNetworkRegistrySpec.kt`

**Interfaces:**
- Consumes: `SetProjectRootC2S` (Task 1); existing `ProjectRoot`, `ProjectServerContext`, `ProjectDimLifecycle.placeAll(server, root)`, `SharedSettings.projectRootPath`, `sendTree(server, player)`.
- Produces: `fun handleSetRoot(server: MinecraftServer, player: ServerPlayer, payload: SetProjectRootC2S)`.

- [ ] **Step 1: Write the two failing gametests**

Add these imports at the top of `ProjectNetworkRegistrySpec.kt`:

```kotlin
import com.breadmoirai.garnet.config.SharedSettings
import com.breadmoirai.garnet.network.project.SetProjectRootC2S
import kotlin.io.path.writeText
```

Append these two tests inside the `GarnetTestSpec({ ... })` block:

```kotlin
    test("handleSetRoot switches root, persists it, and sends a snapshot of the new folder") {
        withTempRoot("project-net-setroot") { tmp ->
            val newRoot = tmp.resolve("workspace").also { it.createDirectories() }
            val folder = newRoot.resolve("set").also { it.createDirectories() }
            writeStub(folder, "a")
            val originalRootPath = SharedSettings.projectRootPath
            onServer {
                val player = makeMockServerPlayer(this)
                drainPayloads(player)

                ProjectNetworkRegistry.handleSetRoot(this, player, SetProjectRootC2S(newRoot.toString()))

                SharedSettings.projectRootPath shouldBe newRoot.toAbsolutePath().toString()
                val snap = drainPayloads(player).filterIsInstance<ProjectTreeSnapshotS2C>().single()
                snap.root.name shouldBe "workspace"
                snap.root.walk().map { it.first }.toList() shouldContain "set/a.spec.kts"

                SharedSettings.projectRootPath = originalRootPath
                ProjectWorld.clear(this)
                ProjectServerContext.clear(this)
            }
        }
    }

    test("handleSetRoot rejects a non-directory path with ProjectErrorS2C") {
        withTempRoot("project-net-setroot-bad") { tmp ->
            val notAFolder = tmp.resolve("notafolder.txt").also { it.writeText("x") }
            val originalRootPath = SharedSettings.projectRootPath
            onServer {
                val player = makeMockServerPlayer(this)
                drainPayloads(player)

                ProjectNetworkRegistry.handleSetRoot(this, player, SetProjectRootC2S(notAFolder.toString()))

                val err = drainPayloads(player).filterIsInstance<ProjectErrorS2C>().single()
                err.reason shouldContain "not a folder"
                SharedSettings.projectRootPath shouldBe originalRootPath
            }
        }
    }
```

(`shouldContain` for both the collection and string forms is already imported in this file; `withTempRoot`, `writeStub`, `makeMockServerPlayer`, `drainPayloads`, `walk` are already imported.)

- [ ] **Step 2: Run the tests to verify they fail**

Run: `cmd.exe /c "gradlew.bat :26.1:gametestClasses"`
Expected: compile failure — `handleSetRoot` and `SetProjectRootC2S` unresolved.

- [ ] **Step 3: Register the payload + receiver and implement `handleSetRoot`**

In `ProjectNetworkRegistry.kt`, add the serverbound registration inside `register()` next to the other `serverboundPlay().register(...)` calls:

```kotlin
        PayloadTypeRegistry.serverboundPlay().register(SetProjectRootC2S.TYPE, SetProjectRootC2S.STREAM_CODEC)
```

Add the receiver next to the other `registerGlobalReceiver(...)` calls in `register()`:

```kotlin
        ServerPlayNetworking.registerGlobalReceiver(SetProjectRootC2S.TYPE) { payload, ctx ->
            ctx.server().execute { handleSetRoot(ctx.server(), ctx.player(), payload) }
        }
```

Add the handler (e.g. after `handleListTree`):

```kotlin
    fun handleSetRoot(server: MinecraftServer, player: ServerPlayer, payload: SetProjectRootC2S) {
        val abs = try {
            Path.of(payload.path).toAbsolutePath()
        } catch (e: java.nio.file.InvalidPathException) {
            ServerPlayNetworking.send(player, ProjectErrorS2C("invalid path: ${payload.path}")); return
        }
        if (!abs.isDirectory()) {
            ServerPlayNetworking.send(player, ProjectErrorS2C("not a folder: $abs")); return
        }
        val root = ProjectRoot(abs)
        SharedSettings.projectRootPath = abs.toString()
        ProjectServerContext.set(server, ProjectServerContext(root))
        ProjectDimLifecycle.placeAll(server, root)
        sendTree(server, player)
    }
```

Add this import near the top of `ProjectNetworkRegistry.kt` (it already imports `java.nio.file.Path` and wildcard `project.*`):

```kotlin
import kotlin.io.path.isDirectory
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `cmd.exe /c "gradlew.bat :26.1:test"` (Kotest gametest specs run under the same `test` task; `ProjectNetworkRegistrySpec` is already registered in `GametestSentinel`).
Expected: PASS — both new tests green. Confirm in `build/26.1/test-results/test/*.xml`.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/breadmoirai/garnet/network/project/ProjectNetworkRegistry.kt \
        src/gametest/kotlin/com/breadmoirai/garnet/test/project/ProjectNetworkRegistrySpec.kt
git commit -m "feat(project): handleSetRoot swaps the project root and re-snapshots"
```

---

### Task 3: `FolderPicker` seam, `RootPickerController`, and client persistence

**Files:**
- Create: `src/client/kotlin/com/breadmoirai/garnet/client/ide/FolderPicker.kt`
- Create: `src/client/kotlin/com/breadmoirai/garnet/client/ide/RootPickerController.kt`
- Modify: `src/client/kotlin/com/breadmoirai/garnet/client/config/ModConfig.kt`
- Create + Register: `src/clientTest/kotlin/com/breadmoirai/garnet/test/RootPickerSpec.kt`, `src/clientTest/kotlin/com/breadmoirai/garnet/test/ClientTestSentinel.kt`

**Interfaces:**
- Consumes: `SetProjectRootC2S` (Task 1); `ClientPlayNetworking.send`; `Minecraft.getInstance()`; `ModConfig`.
- Produces:
  - `fun interface FolderPicker { fun pick(title: String, default: String?): String? }`
  - `object RootPickerController` with observable `var menuOpen: Boolean`, `var picking: Boolean` (private setters); swappable `var picker`, `var sender: (CustomPacketPayload) -> Unit`, `var runner: (Runnable) -> Unit`, `var executor: (Runnable) -> Unit`, `var persist: (String) -> Unit`; functions `toggleMenu()`, `closeMenu()`, `openFolder()`, `resetForTest()`.

- [ ] **Step 1: Write the failing controller tests**

Create `src/clientTest/kotlin/com/breadmoirai/garnet/test/RootPickerSpec.kt`:

```kotlin
package com.breadmoirai.garnet.test

import com.breadmoirai.garnet.client.ide.FolderPicker
import com.breadmoirai.garnet.client.ide.RootPickerController
import com.breadmoirai.garnet.network.project.SetProjectRootC2S
import com.breadmoirai.garnet.testing.ClientSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import net.minecraft.network.protocol.common.custom.CustomPacketPayload

class RootPickerSpec : ClientSpec({

    test("openFolder sends SetProjectRootC2S and persists the picked path") {
        val sent = mutableListOf<CustomPacketPayload>()
        val persisted = mutableListOf<String>()
        RootPickerController.picker = FolderPicker { _, _ -> "/abs/picked" }
        RootPickerController.runner = Runnable::run
        RootPickerController.executor = Runnable::run
        RootPickerController.sender = { sent.add(it) }
        RootPickerController.persist = { persisted.add(it) }
        RootPickerController.toggleMenu() // open, then openFolder should close it

        RootPickerController.openFolder()

        sent.filterIsInstance<SetProjectRootC2S>().single().path shouldBe "/abs/picked"
        persisted.single() shouldBe "/abs/picked"
        RootPickerController.picking shouldBe false
        RootPickerController.menuOpen shouldBe false

        RootPickerController.resetForTest()
    }

    test("openFolder sends nothing when the picker is cancelled") {
        val sent = mutableListOf<CustomPacketPayload>()
        RootPickerController.picker = FolderPicker { _, _ -> null }
        RootPickerController.runner = Runnable::run
        RootPickerController.executor = Runnable::run
        RootPickerController.sender = { sent.add(it) }
        RootPickerController.persist = { }

        RootPickerController.openFolder()

        sent.shouldBeEmpty()
        RootPickerController.picking shouldBe false

        RootPickerController.resetForTest()
    }
})
```

Register it in `ClientTestSentinel.kt` — add `RootPickerSpec::class,` to the `listOf(...)` of spec classes (right after `ProjectExplorerSpec::class,`).

- [ ] **Step 2: Run the tests to verify they fail**

Run: `cmd.exe /c "gradlew.bat :26.1:clientTestClasses"`
Expected: compile failure — `FolderPicker` / `RootPickerController` unresolved.

- [ ] **Step 3: Create `FolderPicker.kt`**

```kotlin
package com.breadmoirai.garnet.client.ide

import org.lwjgl.util.tinyfd.TinyFileDialogs

/** Selects a folder from the OS. [pick] blocks — never call it on the render thread. */
fun interface FolderPicker {
    fun pick(title: String, default: String?): String?
}

/** Default impl backed by LWJGL tinyfd (bundled with MC). Returns null on cancel. */
object TinyfdFolderPicker : FolderPicker {
    override fun pick(title: String, default: String?): String? =
        TinyFileDialogs.tinyfd_selectFolderDialog(title, default ?: "")
}
```

- [ ] **Step 4: Create `RootPickerController.kt`**

```kotlin
package com.breadmoirai.garnet.client.ide

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.breadmoirai.garnet.client.config.ModConfig
import com.breadmoirai.garnet.network.project.SetProjectRootC2S
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.minecraft.client.Minecraft
import net.minecraft.network.protocol.common.custom.CustomPacketPayload

/**
 * Compose-observable state + action controller for the Explorer's root-picker header.
 * Sibling of [ProjectTreeState]; packet handlers never touch this UI state.
 *
 * External effects are seams (swapped in tests): [picker] (native dialog), [runner]
 * (background thread — the dialog blocks), [executor] (marshal back to the client thread),
 * [sender] (network), [persist] (disk).
 */
object RootPickerController {
    var picker: FolderPicker = TinyfdFolderPicker
    var runner: (Runnable) -> Unit = { Thread(it, "garnet-folder-picker").start() }
    var executor: (Runnable) -> Unit = { Minecraft.getInstance().execute(it) }
    var sender: (CustomPacketPayload) -> Unit = { ClientPlayNetworking.send(it) }
    var persist: (String) -> Unit = { path -> ModConfig.projectRootPath = path; ModConfig.save() }

    var menuOpen by mutableStateOf(false)
        private set
    var picking by mutableStateOf(false)
        private set

    fun toggleMenu() { menuOpen = !menuOpen }
    fun closeMenu() { menuOpen = false }

    /** Open the native folder picker; on a non-null result, persist it and send [SetProjectRootC2S]. */
    fun openFolder() {
        closeMenu()
        if (picking) return
        picking = true
        runner {
            try {
                val path = picker.pick("Open Project Folder", null)
                if (path != null) {
                    persist(path)
                    executor { sender(SetProjectRootC2S(path)) }
                }
            } finally {
                executor { picking = false }
            }
        }
    }

    /** Restore default seams + flags between tests. */
    fun resetForTest() {
        picker = TinyfdFolderPicker
        runner = { Thread(it, "garnet-folder-picker").start() }
        executor = { Minecraft.getInstance().execute(it) }
        sender = { ClientPlayNetworking.send(it) }
        persist = { path -> ModConfig.projectRootPath = path; ModConfig.save() }
        menuOpen = false
        picking = false
    }
}
```

- [ ] **Step 5: Add `projectRootPath` persistence to `ModConfig.kt`**

In `ModConfig.kt`, add the field beside `specSaveDir`:

```kotlin
    var projectRootPath: String = ""
```

In `load()`, inside the `runCatching { ... configFile.reader().use { ... } }` block, after the `specSaveDir` read line, add:

```kotlin
                projectRootPath = json.get("projectRootPath")?.asString ?: ""
```

Still in `load()`, after the existing `SharedSettings.specSaveDir = specSaveDir` line, add:

```kotlin
        SharedSettings.projectRootPath = projectRootPath
```

In `save()`, after `json.addProperty("specSaveDir", specSaveDir)`, add:

```kotlin
        json.addProperty("projectRootPath", projectRootPath)
```

Still in `save()`, after the existing `SharedSettings.specSaveDir = specSaveDir` line, add:

```kotlin
        SharedSettings.projectRootPath = projectRootPath
```

- [ ] **Step 6: Run the tests to verify they pass**

Run: `cmd.exe /c "gradlew.bat :26.1:runClientTest"`
Expected: PASS — `RootPickerSpec`'s two tests green (they run fully synchronously via the injected seams; no live connection needed).

- [ ] **Step 7: Commit**

```bash
git add src/client/kotlin/com/breadmoirai/garnet/client/ide/FolderPicker.kt \
        src/client/kotlin/com/breadmoirai/garnet/client/ide/RootPickerController.kt \
        src/client/kotlin/com/breadmoirai/garnet/client/config/ModConfig.kt \
        src/clientTest/kotlin/com/breadmoirai/garnet/test/RootPickerSpec.kt \
        src/clientTest/kotlin/com/breadmoirai/garnet/test/ClientTestSentinel.kt
git commit -m "feat(ui): RootPickerController + native FolderPicker + client-side root persistence"
```

---

### Task 4: Explorer header bar + dropdown overlay

**Files:**
- Modify: `src/client/kotlin/com/breadmoirai/garnet/client/ide/ProjectExplorerPanel.kt`
- Test: `src/clientTest/kotlin/com/breadmoirai/garnet/test/ProjectExplorerSpec.kt`

**Interfaces:**
- Consumes: `RootPickerController` (Task 3), `ProjectTreeState`, `ListProjectTreeC2S.INSTANCE`.
- Produces: no new public API — the `explorerPanel()` content now renders the header + overlay.

- [ ] **Step 1: Write the failing header test**

Append this test inside the existing `ProjectExplorerSpec` `ClientSpec({ ... })` block (the `capture` helper and imports for `DockState`/`ViewportState`/`ComposeOverlay`/`WindowViewportExt` are already in the file):

```kotlin
    test("Explorer header renders the root option button and opens the dropdown") {
        closeClientScreen(); waitClientTicks(2)
        val tree = FolderNode("myroot", listOf(
            FolderNode("set", listOf(FileNode("a.spec.kts", "kts"))),
        ))
        runOnClient { mc ->
            DockState.reset(); ProjectTreeState.reset()
            com.breadmoirai.garnet.client.ide.RootPickerController.resetForTest()
            ProjectTreeState.onSnapshot(ProjectTreeSnapshotS2C(root = tree, currentSubpath = null))
            com.breadmoirai.garnet.client.ide.RootPickerController.toggleMenu()
            DockState.leftPanels.add(explorerPanel())
            DockState.setVisible(DockRegion.LEFT, true); DockState.setSize(DockRegion.LEFT, 300)
            ViewportState.active = true; ComposeOverlay.enabled = true
            (mc.window as Any as WindowViewportExt).`garnet$updateScaledFramebuffer`(true)
        }
        waitClientTicks(12)
        com.breadmoirai.garnet.client.ide.RootPickerController.menuOpen shouldBe true
        capture("explorer_root_menu.png")
        runOnClient { mc ->
            com.breadmoirai.garnet.client.ide.RootPickerController.resetForTest()
            ComposeOverlay.enabled = false; ViewportState.active = false; DockState.reset()
            (mc.window as Any as WindowViewportExt).`garnet$updateScaledFramebuffer`(true)
        }
        waitClientTicks(6)
    }
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cmd.exe /c "gradlew.bat :26.1:runClientTest"`
Expected: FAIL — `RootPickerController` is not yet wired into the panel, so nothing toggles/renders the menu (the assertion on `menuOpen` passes only after the header is present to make the screenshot meaningful; if it compiles and the screenshot is blank, treat the run as the RED baseline and proceed).

- [ ] **Step 3: Rewrite `ProjectExplorerPanel.kt` with a header + overlay**

Replace the whole file with:

```kotlin
package com.breadmoirai.garnet.client.ide

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import com.breadmoirai.garnet.client.ui.compose.dock.Panel
import com.breadmoirai.garnet.network.project.ListProjectTreeC2S
import com.breadmoirai.garnet.network.project.LoadProjectFolderC2S
import com.breadmoirai.garnet.project.FileNode
import com.breadmoirai.garnet.project.FileTreeNode
import com.breadmoirai.garnet.project.FolderNode
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking

private val TEXT = Color(0xFFDDE3EC)
private val TEXT_DIM = Color(0xFF8FA0B5)
private val TEXT_DISABLED = Color(0xFF5A6678)
private val SELECTED_BG = Color(0x334A90E2)
private val MENU_BG = Color(0xF01A2130)

/** The Explorer tab for DockState.leftPanels. */
fun explorerPanel(): Panel = Panel("garnet.explorer", "Explorer") { ProjectExplorer() }

@Composable
private fun ProjectExplorer() {
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(4.dp)) {
            Header()
            val snap = ProjectTreeState.snapshot
            if (snap == null) {
                BasicText("(no project loaded — Refresh)", Modifier.padding(vertical = 2.dp), style = TextStyle(color = TEXT_DIM))
            } else {
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                    snap.root.children.forEach { child ->
                        TreeNode(child, path = child.name, depth = 0, currentSubpath = snap.currentSubpath)
                    }
                }
            }
            val status = ProjectTreeState.status
            if (status.isNotEmpty()) BasicText(status, Modifier.padding(top = 4.dp), style = TextStyle(color = TEXT_DIM))
        }
        if (RootPickerController.menuOpen) RootMenu()
    }
}

@Composable
private fun Header() {
    val rootName = ProjectTreeState.snapshot?.root?.name ?: "(no root)"
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Box(Modifier.clickable { RootPickerController.toggleMenu() }.padding(end = 8.dp)) {
            BasicText("$rootName  ▾", style = TextStyle(color = TEXT))
        }
        Spacer(Modifier.weight(1f))
        Box(Modifier.clickable { ClientPlayNetworking.send(ListProjectTreeC2S.INSTANCE) }) {
            BasicText("↻", style = TextStyle(color = TEXT_DIM))
        }
    }
}

@Composable
private fun RootMenu() {
    // Scrim (lower z): click outside closes the menu.
    Box(Modifier.fillMaxSize().clickable { RootPickerController.closeMenu() })
    // Menu card (higher z): offset to sit just under the option button.
    Column(Modifier.offset(x = 4.dp, y = 22.dp).background(MENU_BG).padding(4.dp)) {
        Box(Modifier.fillMaxWidth().clickable { RootPickerController.openFolder() }
            .padding(vertical = 3.dp, horizontal = 6.dp)) {
            BasicText("Open Folder", style = TextStyle(color = TEXT))
        }
        Box(Modifier.fillMaxWidth().padding(vertical = 3.dp, horizontal = 6.dp)) {
            BasicText("Attach Folder  (soon)", style = TextStyle(color = TEXT_DISABLED))
        }
    }
}

@Composable
private fun TreeNode(node: FileTreeNode, path: String, depth: Int, currentSubpath: String?) {
    val indent = (depth * 12).dp
    when (node) {
        is FolderNode -> {
            val isExpanded = path in ProjectTreeState.expanded
            val hasChildren = node.children.isNotEmpty()
            val isSpecFolder = node.children.any { it is FileNode && it.name.endsWith(".spec.kts") }
            val triangle = if (!hasChildren) "  " else if (isExpanded) "▾" else "▸"
            val marker = if (path == currentSubpath) "● " else ""
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                Spacer(Modifier.width(indent))
                Box(Modifier.clickable(enabled = hasChildren) { ProjectTreeState.toggleExpanded(path) }) {
                    BasicText("$triangle ", style = TextStyle(color = TEXT_DIM))
                }
                Box(Modifier.fillMaxWidth().clickable {
                    if (isSpecFolder) ClientPlayNetworking.send(LoadProjectFolderC2S(path))
                    else ProjectTreeState.toggleExpanded(path)
                }) {
                    BasicText("$marker${node.name}", style = TextStyle(color = TEXT))
                }
            }
            if (isExpanded) {
                node.children.forEach { child ->
                    TreeNode(child, path = "$path/${child.name}", depth = depth + 1, currentSubpath = currentSubpath)
                }
            }
        }
        is FileNode -> {
            val isSelected = path == ProjectTreeState.selectedPath
            val base = Modifier.fillMaxWidth().clickable { ProjectTreeState.select(path) }
            val rowMod = if (isSelected) base.background(SELECTED_BG) else base
            Row(rowMod.padding(vertical = 2.dp)) {
                Spacer(Modifier.width(indent))
                BasicText(node.name, style = TextStyle(color = if (isSelected) TEXT else TEXT_DIM))
            }
        }
    }
}
```

(The `Row2` helper is gone — the Refresh action now lives in `Header`. `TreeNode` is unchanged from the current file.)

- [ ] **Step 4: Run the test to verify it passes**

Run: `cmd.exe /c "gradlew.bat :26.1:runClientTest"`
Expected: PASS — `menuOpen` is `true` and `screenshots/explorer_root_menu.png` is written. Open the screenshot and confirm the header shows `myroot  ▾` with the `Open Folder` / `Attach Folder (soon)` dropdown over the tree.

- [ ] **Step 5: Commit**

```bash
git add src/client/kotlin/com/breadmoirai/garnet/client/ide/ProjectExplorerPanel.kt \
        src/clientTest/kotlin/com/breadmoirai/garnet/test/ProjectExplorerSpec.kt
git commit -m "feat(ui): Explorer header bar with root-picker option-button dropdown"
```

---

### Task 5: Documentation audit

**Files:**
- Modify: `docs/ui/dock-framework.md`
- Create: `docs/ui/dock-dialogs.md`
- Modify: `docs/ui/INDEX.md`
- Modify: `docs/use-cases/redstone-project.md`

- [ ] **Step 1: Extend `dock-framework.md`**

In the "First real panel: the Project Explorer" section, append a bullet:

```markdown
- **The panel has a header bar** (`Header` in `ProjectExplorerPanel.kt`, rendered *outside* the
  tree's `verticalScroll`): an option button labeled with the current root's folder name +
  `▾`, and a `↻` refresh button. Clicking the option button toggles
  `RootPickerController.menuOpen`, which renders `RootMenu` — a hand-rolled dropdown overlay
  (scrim + card) as a z-layered sibling `Box`. **Open Folder** runs a native folder picker and
  swaps the single server root (`SetProjectRootC2S` → `handleSetRoot`); **Attach Folder** is
  disabled pending multi-root (Plan B). See [dock-dialogs.md](dock-dialogs.md) for why the menu
  is hand-rolled and how the native picker is threaded.
```

- [ ] **Step 2: Create `docs/ui/dock-dialogs.md`**

```markdown
---
title: Dialogs in the dock — no Compose Popup, native pickers on a worker thread
tags: [compose, dock, dialogs, popup, tinyfd, threading, gotcha]
summary: The embedded ImageComposeScene can't host Compose Popup/DropdownMenu (they spawn a separate desktop window); hand-roll overlays. Native OS dialogs (tinyfd) block, so run them off the render thread and marshal back via Minecraft.execute.
---

# Dialogs in the dock

Two rules govern any menu or dialog inside the Compose dock.

## No Compose `Popup` / `DropdownMenu`

The dock hosts Compose in an embedded `ImageComposeScene` (CPU raster, no platform
windowing — see [dock-framework.md](dock-framework.md)). Material `DropdownMenu` and the
underlying `Popup` open a **separate desktop window**, which that scene cannot host — the
content renders nowhere. Hand-roll dropdowns/menus instead: a z-layered sibling `Box`
(optionally with a full-size transparent scrim `Box` behind it to close on outside-click),
rendered only when an observable "open" flag is set. `ProjectExplorerPanel.RootMenu` is the
reference. Keep the trigger control *outside* any `verticalScroll` so the overlay isn't
scroll-clipped. (This is the Compose-era analog of the legacy "scissor baked at record time"
dropdown warning.)

## Native OS dialogs block — run them off the render thread

`org.lwjgl.util.tinyfd.TinyFileDialogs` (bundled with MC via `lwjgl-tinyfd`) gives a real
native folder/file picker. **It blocks the calling thread until the user dismisses it** — so
never call it on the render/client thread or the game freezes. Run it on a worker thread and
marshal the result back to the client thread via `Minecraft.getInstance().execute {}` before
touching game/network state. `RootPickerController` is the reference: the dialog, the thread,
the client-thread marshal, the network send, and disk persistence are each injectable seams so
the flow is testable without a real dialog.
```

- [ ] **Step 3: Register the new article in `docs/ui/INDEX.md`**

Add to the `## Articles` list:

```markdown
- [Dialogs in the dock — no Compose Popup, native pickers on a worker thread](dock-dialogs.md) — Why the dock's dropdowns are hand-rolled (embedded `ImageComposeScene` can't host a Compose `Popup`) and how `TinyFileDialogs` pickers are threaded off the render thread. _[compose, dock, dialogs, popup, tinyfd, threading, gotcha]_
```

- [ ] **Step 4: Add UC-MAN-09 to `docs/use-cases/redstone-project.md`**

After the UC-MAN-08 section (before the `## Coverage matrix` heading), add:

```markdown
### UC-MAN-09 — Re-root the Explorer from a native folder picker

A player opens the Explorer header's option button, chooses **Open Folder**, and picks a folder
in the OS dialog; the workspace root switches to it. **Attach Folder** is present but disabled
(multi-root is Plan B).

- **UC-MAN-09.a** Clicking the option button toggles `RootPickerController.menuOpen`, rendering
  the hand-rolled `RootMenu` overlay. **Open Folder** calls `RootPickerController.openFolder`,
  which runs the injectable `FolderPicker` (default `TinyfdFolderPicker` →
  `TinyFileDialogs.tinyfd_selectFolderDialog`) on a worker thread.
- **UC-MAN-09.b** On a non-null pick, the controller persists the path client-side
  (`ModConfig.projectRootPath` → `garnet.json`, also mirrored to
  `SharedSettings.projectRootPath`) and sends `SetProjectRootC2S(path)` on the client thread via
  `Minecraft.execute`. A cancel (null) sends nothing.
- **UC-MAN-09.c** `ProjectNetworkRegistry.handleSetRoot` rejects a non-directory / invalid path
  with `ProjectErrorS2C`; otherwise it sets `SharedSettings.projectRootPath`, pins a new
  `ProjectServerContext`, re-runs `ProjectDimLifecycle.placeAll`, and re-sends the single-root
  `ProjectTreeSnapshotS2C`. The Explorer re-renders rooted at the new folder.
- **UC-MAN-09.d** *(Plan-A rough edges, deferred to Plan B)* The previous root's already-placed
  cells remain in the workspace overworld after a swap and `ProjectDimRegistry` keeps
  accumulating region assignments; **Attach Folder** (a second root) is not implemented.
```

Then add these rows to the coverage matrix table:

```markdown
| UC-MAN-09 | Re-root the Explorer from a native folder picker | `RootPickerSpec`, `ProjectNetworkRegistrySpec` | **GAP-PARTIAL** |
| UC-MAN-09.a | Option button toggles the menu; Open Folder runs the `FolderPicker` on a worker thread | `RootPickerSpec."openFolder sends SetProjectRootC2S and persists the picked path"`, `ProjectExplorerSpec."Explorer header renders the root option button and opens the dropdown"` | covered |
| UC-MAN-09.b | Non-null pick persists + sends `SetProjectRootC2S`; cancel sends nothing | `RootPickerSpec."openFolder sends SetProjectRootC2S and persists the picked path"`, `RootPickerSpec."openFolder sends nothing when the picker is cancelled"` | covered |
| UC-MAN-09.c | `handleSetRoot` validates dir, swaps root, re-places, re-snapshots; non-dir → `ProjectErrorS2C` | `ProjectNetworkRegistrySpec."handleSetRoot switches root, persists it, and sends a snapshot of the new folder"`, `ProjectNetworkRegistrySpec."handleSetRoot rejects a non-directory path with ProjectErrorS2C"` | covered |
| UC-MAN-09.d | *(Plan B)* old grid persists; region assignments accumulate; Attach not implemented | — | n/a |
```

- [ ] **Step 5: Grep for a persistence payloads article and update if present**

Run: `grep -rln "LoadProjectFolderC2S\|ProjectTreeSnapshotS2C" docs/persistence docs/architecture`
If a payloads/packets list enumerates the project C2S packets, add a one-line `SetProjectRootC2S` entry (client → server; sets the project root, replies with a tree snapshot or `ProjectErrorS2C`). If no such enumeration exists, skip — do not invent one.

- [ ] **Step 6: Verify cross-references resolve**

Run: `grep -rn "dock-dialogs.md" docs/ui/`
Expected: the link appears in both `dock-framework.md` and `INDEX.md` and resolves to the new file.

- [ ] **Step 7: Commit**

```bash
git add docs/ui/dock-framework.md docs/ui/dock-dialogs.md docs/ui/INDEX.md docs/use-cases/redstone-project.md
git commit -m "docs(ui): document the Explorer root picker, dock dialog rules, and UC-MAN-09"
```

---

## Final verification (run after Task 5)

- [ ] Full 5-sourceset compile:

Run: `cmd.exe /c "gradlew.bat :26.1:clientClasses :26.1:classes :26.1:gametestClasses :26.1:clientTestClasses :26.1:testClasses"`
Expected: BUILD SUCCESSFUL.

- [ ] Unit + gametest (unfiltered):

Run: `cmd.exe /c "gradlew.bat :26.1:test"`
Expected: BUILD SUCCESSFUL; `FileTreeCodecTest` and `ProjectNetworkRegistrySpec` green in `build/26.1/test-results/test/*.xml`.

- [ ] Client-in-MC:

Run: `cmd.exe /c "gradlew.bat :26.1:runClientTest"`
Expected: BUILD SUCCESSFUL; `RootPickerSpec` + `ProjectExplorerSpec` green; `screenshots/explorer_root_menu.png` shows the header + dropdown.

Report actual console output for each; do not claim done until all three are green.
```
