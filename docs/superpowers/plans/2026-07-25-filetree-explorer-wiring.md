# FileTree → Explorer Wiring Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the Project Explorer render the real recursive folder hierarchy (nested folders + individual files) with working expand/collapse, driven by the existing `FileTree` model over a recursive wire codec.

**Architecture:** Server scans with `scanFolder(root.path): FolderNode`, ships the whole tree in a rewritten `ProjectTreeSnapshotS2C` via a hand-written recursive `StreamCodec` (tag-byte per node). Client stores the tree in `ProjectTreeState` and `ProjectExplorerPanel` recursively renders it with expand/collapse triangles, folder-load, and file-select.

**Tech Stack:** Kotlin, Minecraft Fabric (Stonecutter multi-version, active version `:26.1:`), netty `StreamCodec`, Jetpack Compose (client), Kotest (tests).

## Global Constraints

- All gradle runs via `cmd.exe`, NO `./` prefix. Active Stonecutter version prefix is `:26.1:`.
- Unit tests (Kotest, `src/test`): `cmd.exe /c "gradlew.bat :26.1:test"` — run **unfiltered** (`--tests` gives false "No tests found"). Read pass/fail from console summary or `build/test-results/test/*.xml`. `src/test` specs autoscan.
- Client tests (`src/clientTest`): `cmd.exe /c "gradlew.bat :26.1:runClientTest"`. New clientTest specs must be registered in `ClientTestSentinel.kt` (autoscan OFF) — the Explorer spec is already registered.
- Full 5-sourceset compile: `cmd.exe /c "gradlew.bat :26.1:clientClasses :26.1:classes :26.1:gametestClasses :26.1:clientTestClasses :26.1:testClasses"`.
- Do NOT modify `src/main/kotlin/com/breadmoirai/garnet/project/FileTree.kt` (model is complete).
- Do NOT touch world/placement machinery (`ProjectDimLifecycle`, `ProjectCellSaver`, `GridLayout`, region assignment). `ProjectFolderTree` stays — still used by `ProjectDimLifecycle.placeFolder`.
- Commit directly to `main`. Conventional-commit messages. NO `Co-Authored-By` / "Generated with Claude Code" trailer.
- ARGB gotcha: use `-1` / `0xFFFFFFFF` for opaque white text; `0xFFFFFF` renders invisible (alpha=0).

**Mid-migration note:** rewriting the payload in Task 1 breaks compilation of the `client`, `gametest`, and `clientTest` sourcesets until Tasks 2–4 fix them. Each task verifies only the sourceset(s) it repairs; the full 5-sourceset compile is green again at Task 5.

## File Structure

- `src/main/kotlin/com/breadmoirai/garnet/network/project/ProjectPackets.kt` — **modify**: delete `ProjectLeafEntry`, add recursive `FILE_TREE_STREAM_CODEC`, rewrite `ProjectTreeSnapshotS2C`.
- `src/main/kotlin/com/breadmoirai/garnet/network/project/ProjectNetworkRegistry.kt` — **modify**: `sendTree` builds the new payload from `scanFolder`.
- `src/main/kotlin/com/breadmoirai/garnet/project/ProjectCommand.kt` — **modify**: second sender, same swap.
- `src/test/kotlin/com/breadmoirai/garnet/network/project/FileTreeCodecTest.kt` — **create**: codec round-trip unit test.
- `src/client/kotlin/com/breadmoirai/garnet/client/ide/ProjectTreeState.kt` — **modify**: add `selectedPath`/`select`.
- `src/client/kotlin/com/breadmoirai/garnet/client/ide/ProjectExplorerPanel.kt` — **modify**: recursive tree render.
- `src/gametest/kotlin/com/breadmoirai/garnet/test/project/ProjectNetworkRegistrySpec.kt` — **modify**: assert against `snap.root`.
- `src/gametest/kotlin/com/breadmoirai/garnet/test/project/ProjectCommandSpec.kt` — **modify**: assert against `snap.root`.
- `src/clientTest/kotlin/com/breadmoirai/garnet/test/ProjectExplorerSpec.kt` — **modify**: feed a real `FolderNode`, content-based assertions.
- `docs/architecture/redstone-project.md`, `docs/ui/dock-framework.md`, `docs/use-cases/*` — **modify**: doc sync.

---

### Task 1: Recursive wire codec + payload + server senders

**Files:**
- Modify: `src/main/kotlin/com/breadmoirai/garnet/network/project/ProjectPackets.kt`
- Modify: `src/main/kotlin/com/breadmoirai/garnet/network/project/ProjectNetworkRegistry.kt:122-134` (`sendTree`)
- Modify: `src/main/kotlin/com/breadmoirai/garnet/project/ProjectCommand.kt:1-46`
- Test: `src/test/kotlin/com/breadmoirai/garnet/network/project/FileTreeCodecTest.kt`

**Interfaces:**
- Consumes: `FolderNode(name, children)`, `FileNode(name, extension)`, `FileTreeNode` (sealed) from `com.breadmoirai.garnet.project`; `scanFolder(path): FolderNode`.
- Produces:
  - `FILE_TREE_STREAM_CODEC: StreamCodec<ByteBuf, FileTreeNode>` (top-level public in package `com.breadmoirai.garnet.network.project`).
  - `ProjectTreeSnapshotS2C(val root: FolderNode, val currentSubpath: String?)` — same `TYPE` / packet id `"tree_snapshot"`.
  - `ProjectLeafEntry` **removed**.

- [ ] **Step 1: Write the failing test**

Create `src/test/kotlin/com/breadmoirai/garnet/network/project/FileTreeCodecTest.kt`:

```kotlin
package com.breadmoirai.garnet.network.project

import com.breadmoirai.garnet.project.FileNode
import com.breadmoirai.garnet.project.FolderNode
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.netty.buffer.Unpooled

class FileTreeCodecTest : FunSpec({

    test("round-trips a nested folder tree including empty folders and no-extension files") {
        val tree = FolderNode("root", listOf(
            FolderNode("adders", listOf(
                FolderNode("full-adder", listOf(
                    FileNode("full.spec.kts", "kts"),
                    FileNode("notes", ""),
                )),
            )),
            FolderNode("empty", emptyList()),
            FileNode("loose.txt", "txt"),
        ))

        val buf = Unpooled.buffer()
        FILE_TREE_STREAM_CODEC.encode(buf, tree)
        val decoded = FILE_TREE_STREAM_CODEC.decode(buf)

        decoded shouldBe tree
    }
})
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cmd.exe /c "gradlew.bat :26.1:test"`
Expected: FAIL — compilation error, `FILE_TREE_STREAM_CODEC` unresolved.

- [ ] **Step 3: Rewrite the payload and add the codec**

In `ProjectPackets.kt`, add these imports near the top (after existing imports):

```kotlin
import com.breadmoirai.garnet.project.FileNode
import com.breadmoirai.garnet.project.FileTreeNode
import com.breadmoirai.garnet.project.FolderNode
```

Replace the entire `// === Tree listing ===` section (the `ProjectLeafEntry` data class AND the `ProjectTreeSnapshotS2C` data class, currently lines 11–47) with:

```kotlin
// === Tree listing ===

private const val TAG_FOLDER: Byte = 0
private const val TAG_FILE: Byte = 1

/** Recursive codec for a [FileTreeNode] tree. Per-node tag byte: 0 = folder, 1 = file. */
val FILE_TREE_STREAM_CODEC: StreamCodec<ByteBuf, FileTreeNode> = object : StreamCodec<ByteBuf, FileTreeNode> {
    override fun decode(buf: ByteBuf): FileTreeNode {
        val tag = buf.readByte()
        val name = ByteBufCodecs.STRING_UTF8.decode(buf)
        return when (tag) {
            TAG_FOLDER -> {
                val count = ByteBufCodecs.VAR_INT.decode(buf)
                val children = ArrayList<FileTreeNode>(count)
                repeat(count) { children.add(decode(buf)) }
                FolderNode(name, children)
            }
            TAG_FILE -> FileNode(name, ByteBufCodecs.STRING_UTF8.decode(buf))
            else -> throw IllegalStateException("Unknown FileTreeNode tag: $tag")
        }
    }

    override fun encode(buf: ByteBuf, value: FileTreeNode) {
        when (value) {
            is FolderNode -> {
                buf.writeByte(TAG_FOLDER.toInt())
                ByteBufCodecs.STRING_UTF8.encode(buf, value.name)
                ByteBufCodecs.VAR_INT.encode(buf, value.children.size)
                value.children.forEach { encode(buf, it) }
            }
            is FileNode -> {
                buf.writeByte(TAG_FILE.toInt())
                ByteBufCodecs.STRING_UTF8.encode(buf, value.name)
                ByteBufCodecs.STRING_UTF8.encode(buf, value.extension)
            }
        }
    }
}

data class ProjectTreeSnapshotS2C(
    val root: FolderNode,
    val currentSubpath: String?,
) : CustomPacketPayload {
    companion object {
        val TYPE = CustomPacketPayload.Type<ProjectTreeSnapshotS2C>(id("tree_snapshot"))
        val STREAM_CODEC: StreamCodec<ByteBuf, ProjectTreeSnapshotS2C> = object : StreamCodec<ByteBuf, ProjectTreeSnapshotS2C> {
            override fun decode(buf: ByteBuf): ProjectTreeSnapshotS2C {
                val root = FILE_TREE_STREAM_CODEC.decode(buf) as? FolderNode
                    ?: error("ProjectTreeSnapshotS2C root must be a folder")
                val hasCurrent = buf.readBoolean()
                val current = if (hasCurrent) ByteBufCodecs.STRING_UTF8.decode(buf) else null
                return ProjectTreeSnapshotS2C(root, current)
            }
            override fun encode(buf: ByteBuf, value: ProjectTreeSnapshotS2C) {
                FILE_TREE_STREAM_CODEC.encode(buf, value.root)
                buf.writeBoolean(value.currentSubpath != null)
                if (value.currentSubpath != null) ByteBufCodecs.STRING_UTF8.encode(buf, value.currentSubpath)
            }
        }
    }
    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE
}
```

- [ ] **Step 4: Update `ProjectNetworkRegistry.sendTree`**

In `ProjectNetworkRegistry.kt`, replace the body of `sendTree` (lines 122–134) — specifically the `val tree = ...` through the `ServerPlayNetworking.send(...)` block — with:

```kotlin
    private fun sendTree(server: MinecraftServer, player: ServerPlayer) {
        val root = rootFor(server) ?: run {
            ServerPlayNetworking.send(player, ProjectErrorS2C("project-root not configured"))
            return
        }
        val current = ProjectSession.get(player.uuid)?.activeSubpath
        ServerPlayNetworking.send(player, ProjectTreeSnapshotS2C(
            root = scanFolder(root.path),
            currentSubpath = current,
        ))
    }
```

(`scanFolder` and `FolderNode` resolve via the existing `import com.breadmoirai.garnet.project.*` wildcard.)

- [ ] **Step 5: Update `ProjectCommand` (the second sender)**

In `ProjectCommand.kt`, remove the now-dead import line:

```kotlin
import com.breadmoirai.garnet.network.project.ProjectLeafEntry
```

Replace lines 37–43 (the `val tree = ProjectFolderTree.scan(root)` block through the `ServerPlayNetworking.send(...)` call) with:

```kotlin
        val current = ProjectSession.get(player.uuid)?.activeSubpath
        ServerPlayNetworking.send(player, ProjectTreeSnapshotS2C(
            root = scanFolder(root.path),
            currentSubpath = current,
        ))
```

(`ProjectCommand` is in package `com.breadmoirai.garnet.project`, so `scanFolder` needs no import.)

- [ ] **Step 6: Run test to verify it passes and main compiles**

Run: `cmd.exe /c "gradlew.bat :26.1:test :26.1:classes"`
Expected: BUILD SUCCESSFUL. `FileTreeCodecTest` passes (check console summary / `build/test-results/test/*.xml`). `:26.1:classes` confirms `main` compiles.

- [ ] **Step 7: Commit**

```bash
git add src/main/kotlin/com/breadmoirai/garnet/network/project/ProjectPackets.kt src/main/kotlin/com/breadmoirai/garnet/network/project/ProjectNetworkRegistry.kt src/main/kotlin/com/breadmoirai/garnet/project/ProjectCommand.kt src/test/kotlin/com/breadmoirai/garnet/network/project/FileTreeCodecTest.kt
git commit -m "feat(project): ship recursive FileTree over the tree-snapshot payload"
```

---

### Task 2: Client state + recursive panel render

**Files:**
- Modify: `src/client/kotlin/com/breadmoirai/garnet/client/ide/ProjectTreeState.kt`
- Modify: `src/client/kotlin/com/breadmoirai/garnet/client/ide/ProjectExplorerPanel.kt`

**Interfaces:**
- Consumes: `ProjectTreeSnapshotS2C(root: FolderNode, currentSubpath: String?)` from Task 1; `FileTreeNode`/`FolderNode`/`FileNode`; existing `ProjectTreeState.expanded` + `toggleExpanded(path)`.
- Produces: `ProjectTreeState.selectedPath: String?` + `select(path: String)`.

Note: there is no isolated unit test for Compose rendering here (it needs the in-MC client harness); Task 4's `runClientTest` is this task's behavioral test. This task's own gate is `:26.1:clientClasses` compiling.

- [ ] **Step 1: Add selection state to `ProjectTreeState`**

In `ProjectTreeState.kt`, add after the `expanded` declaration (around line 22):

```kotlin
    /** The file the user has clicked (highlighted). Null when nothing is selected. */
    var selectedPath by mutableStateOf<String?>(null)
        private set
```

Add a `select` function next to `toggleExpanded`:

```kotlin
    fun select(path: String) { selectedPath = path }
```

In `reset()`, add `selectedPath = null` alongside the existing resets:

```kotlin
    fun reset() {
        snapshot = null
        status = ""
        expanded.clear()
        selectedPath = null
    }
```

- [ ] **Step 2: Rewrite the panel to render the recursive tree**

Replace the entire body of `ProjectExplorerPanel.kt` (imports + composables) with:

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
private val SELECTED_BG = Color(0x334A90E2)

/** The Explorer tab for DockState.leftPanels. */
fun explorerPanel(): Panel = Panel("garnet.explorer", "Explorer") { ProjectExplorer() }

@Composable
private fun ProjectExplorer() {
    Column(Modifier.fillMaxSize().padding(4.dp)) {
        Row2("↻ Refresh", TEXT_DIM) { ClientPlayNetworking.send(ListProjectTreeC2S.INSTANCE) }
        val snap = ProjectTreeState.snapshot
        if (snap == null) {
            Row2("(no project loaded — Refresh)", TEXT_DIM) {}
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

@Composable
private fun Row2(label: String, color: Color, onClick: () -> Unit) =
    Box(Modifier.fillMaxWidth().clickable { onClick() }.padding(vertical = 2.dp)) {
        BasicText(label, style = TextStyle(color = color))
    }
```

- [ ] **Step 3: Compile the client sourceset**

Run: `cmd.exe /c "gradlew.bat :26.1:clientClasses"`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add src/client/kotlin/com/breadmoirai/garnet/client/ide/ProjectTreeState.kt src/client/kotlin/com/breadmoirai/garnet/client/ide/ProjectExplorerPanel.kt
git commit -m "feat(ui): render the recursive project tree with expand/collapse and file select"
```

---

### Task 3: Update gametest specs to the tree payload

**Files:**
- Modify: `src/gametest/kotlin/com/breadmoirai/garnet/test/project/ProjectNetworkRegistrySpec.kt:184-206`
- Modify: `src/gametest/kotlin/com/breadmoirai/garnet/test/project/ProjectCommandSpec.kt`

**Interfaces:**
- Consumes: `ProjectTreeSnapshotS2C.root: FolderNode`; `FolderNode.walk(): Sequence<Pair<String, FileTreeNode>>` from `com.breadmoirai.garnet.project`.

Reminder: `writeStub(folder, "x")` writes `x.spec.kts` into `folder`, so `scanFolder`'s `walk()` paths are relative to the root (e.g. `"set-a/x.spec.kts"`).

- [ ] **Step 1: Update `ProjectNetworkRegistrySpec`**

In `ProjectNetworkRegistrySpec.kt`, remove the now-unused import:

```kotlin
import com.breadmoirai.garnet.project.ProjectFolderTree
```

Add these imports (alongside the other `io.kotest` / project imports):

```kotlin
import com.breadmoirai.garnet.project.walk
import io.kotest.matchers.collections.shouldContainAll
```

Replace the `test("handleListTree sends snapshot matching ProjectFolderTree.scan")` body's assertion block (currently lines 198–201) with:

```kotlin
                val snap = drainPayloads(player).filterIsInstance<ProjectTreeSnapshotS2C>().single()
                val paths = snap.root.walk().map { it.first }.toList()
                paths shouldContainAll listOf("set-a", "set-b", "set-a/x.spec.kts", "set-b/y.spec.kts", "set-b/z.spec.kts")
```

Also rename the test label to reflect the new source of truth:

```kotlin
    test("handleListTree sends a recursive snapshot matching scanFolder") {
```

- [ ] **Step 2: Update `ProjectCommandSpec`**

In `ProjectCommandSpec.kt`, add these imports:

```kotlin
import com.breadmoirai.garnet.project.walk
import io.kotest.matchers.collections.shouldContain
```

Replace the assertion in the first test (currently line 60):

```kotlin
                snap.leaves.map { it.subpath } shouldBe listOf("set")
```

with:

```kotlin
                snap.root.walk().map { it.first }.toList() shouldContain "set/a.spec.kts"
```

Replace the assertion in the fallback test (currently line 86):

```kotlin
                    snap.leaves.map { it.subpath } shouldBe listOf("cfg")
```

with:

```kotlin
                    snap.root.walk().map { it.first }.toList() shouldContain "cfg/a.spec.kts"
```

Replace the three assertions in the session test (currently lines 112–114):

```kotlin
                snap.leaves.map { it.subpath } shouldBe listOf("parent/leaf")
                snap.intermediates shouldBe listOf("parent")
                snap.currentSubpath shouldBe "parent/leaf"
```

with:

```kotlin
                val paths = snap.root.walk().map { it.first }.toList()
                paths shouldContain "parent"
                paths shouldContain "parent/leaf/a.spec.kts"
                snap.currentSubpath shouldBe "parent/leaf"
```

- [ ] **Step 3: Compile the gametest sourceset**

Run: `cmd.exe /c "gradlew.bat :26.1:gametestClasses"`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add src/gametest/kotlin/com/breadmoirai/garnet/test/project/ProjectNetworkRegistrySpec.kt src/gametest/kotlin/com/breadmoirai/garnet/test/project/ProjectCommandSpec.kt
git commit -m "test(project): assert the recursive tree payload in gametest specs"
```

---

### Task 4: Update the Explorer client test

**Files:**
- Modify: `src/clientTest/kotlin/com/breadmoirai/garnet/test/ProjectExplorerSpec.kt`

**Interfaces:**
- Consumes: `ProjectTreeSnapshotS2C(root, currentSubpath)`; `FolderNode`/`FileNode`; `FolderNode.walk()`; `ProjectTreeState.onSnapshot`/`toggleExpanded`/`reset`.

- [ ] **Step 1: Rewrite the spec to build a real tree and assert content**

Replace the whole file with:

```kotlin
package com.breadmoirai.garnet.test

import com.breadmoirai.garnet.client.ide.ProjectTreeState
import com.breadmoirai.garnet.client.ide.explorerPanel
import com.breadmoirai.garnet.client.ui.compose.ComposeOverlay
import com.breadmoirai.garnet.client.ui.compose.dock.DockRegion
import com.breadmoirai.garnet.client.ui.compose.dock.DockState
import com.breadmoirai.garnet.client.viewport.ViewportState
import com.breadmoirai.garnet.client.viewport.WindowViewportExt
import com.breadmoirai.garnet.network.project.ProjectTreeSnapshotS2C
import com.breadmoirai.garnet.project.FileNode
import com.breadmoirai.garnet.project.FolderNode
import com.breadmoirai.garnet.project.walk
import com.breadmoirai.garnet.testing.ClientSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.Path

class ProjectExplorerSpec : ClientSpec({
    fun capture(name: String): Path {
        val p = Path.of("screenshots", name).toAbsolutePath()
        Files.deleteIfExists(p)
        runOnClient { ViewportState.compositeCaptureRequest = p }
        val deadline = System.currentTimeMillis() + 6000
        while (!Files.exists(p) && System.currentTimeMillis() < deadline) Thread.sleep(50)
        Files.exists(p).shouldBeTrue(); return p
    }

    test("Explorer renders a recursive project tree snapshot") {
        closeClientScreen(); waitClientTicks(2)
        // root/
        //   adders/full-adder/full.spec.kts   (spec-folder)
        //   clocks/ring/ring.spec.kts          (spec-folder)
        val tree = FolderNode("root", listOf(
            FolderNode("adders", listOf(
                FolderNode("full-adder", listOf(FileNode("full.spec.kts", "kts"))),
            )),
            FolderNode("clocks", listOf(
                FolderNode("ring", listOf(FileNode("ring.spec.kts", "kts"))),
            )),
        ))
        runOnClient { mc ->
            DockState.reset()
            ProjectTreeState.reset()
            ProjectTreeState.onSnapshot(ProjectTreeSnapshotS2C(root = tree, currentSubpath = "adders/full-adder"))
            // Expand so the nested folders and files are visible in the screenshot.
            ProjectTreeState.toggleExpanded("adders")
            ProjectTreeState.toggleExpanded("adders/full-adder")
            DockState.leftPanels.add(explorerPanel())
            DockState.setVisible(DockRegion.LEFT, true)
            DockState.setSize(DockRegion.LEFT, 300)
            ViewportState.active = true; ComposeOverlay.enabled = true
            (mc.window as Any as WindowViewportExt).`garnet$updateScaledFramebuffer`(true)
        }
        waitClientTicks(12)
        val paths = ProjectTreeState.snapshot!!.root.walk().map { it.first }.toList()
        paths shouldContainAll listOf(
            "adders", "clocks",
            "adders/full-adder", "adders/full-adder/full.spec.kts",
            "clocks/ring/ring.spec.kts",
        )
        ProjectTreeState.snapshot!!.currentSubpath shouldBe "adders/full-adder"
        capture("explorer_tree.png")   // controller verifies: adders/ expanded to full-adder → full.spec.kts; clocks/ collapsed

        runOnClient { mc ->
            ComposeOverlay.enabled = false; ViewportState.active = false; DockState.reset()
            (mc.window as Any as WindowViewportExt).`garnet$updateScaledFramebuffer`(true)
        }
        waitClientTicks(6)
    }
})
```

- [ ] **Step 2: Compile the clientTest sourceset**

Run: `cmd.exe /c "gradlew.bat :26.1:clientTestClasses"`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Run the client test in-MC**

Run: `cmd.exe /c "gradlew.bat :26.1:runClientTest"`
Expected: BUILD SUCCESSFUL — `ProjectExplorerSpec` passes (walk-path assertions + screenshot captured).

- [ ] **Step 4: Commit**

```bash
git add src/clientTest/kotlin/com/breadmoirai/garnet/test/ProjectExplorerSpec.kt
git commit -m "test(ui): assert the Explorer renders the recursive nested tree"
```

---

### Task 5: Doc sync + full verification

**Files:**
- Modify: `docs/architecture/redstone-project.md`
- Modify: any `docs/ui/dock-framework.md` / `docs/use-cases/*` lines that reference the old flat listing.

**Interfaces:** none (documentation + final gate).

- [ ] **Step 1: Update `docs/architecture/redstone-project.md`**

Edit the `FileTree` bullet (lines ~56–60) — replace the trailing sentence "**Not yet wired** to the network payload or the Explorer, which still use the flat `ProjectFolderTree`." with:

```
Wired end-to-end: `scanFolder` feeds `ProjectTreeSnapshotS2C` (recursive tag-byte
`StreamCodec`), which the Explorer renders as the live folder tree.
```

Edit the `ProjectFolderTree` bullet (line ~54) to note it is now placement-only:

```
- `ProjectFolderTree` — leaves vs intermediates scan. **Placement-only** now: used by
  `ProjectDimLifecycle.placeFolder`; no longer the listing model (superseded by `FileTree`).
```

Edit the `ProjectExplorerPanel` / `ProjectTreeState` client bullet (lines ~88–94) so it describes recursive rendering:

```
- `client/ide/ProjectExplorerPanel` + `client/ide/ProjectTreeState` — the Compose dock panel that
  recursively renders the folder tree (LEFT region, hidden by default — Shift+1 reveals it) with
  per-folder expand/collapse triangles. `ProjectTreeState` is `mutableStateOf`-backed client state fed
  by the S2C receivers (`snapshot: ProjectTreeSnapshotS2C`, `expanded` set, `selectedPath`);
  `explorerPanel()` returns the LEFT-dock `Panel`. Clicking a spec-folder (one directly holding a
  `.spec.kts`) sends `LoadProjectFolderC2S`; clicking a container folder toggles expand; clicking a file
  selects it (highlight, no packet). The Refresh row sends `ListProjectTreeC2S`.
```

Edit the "How does the GUI show the folder tree?" entry (lines ~116–118):

```
- *"How does the GUI show the folder tree?"* → `ProjectExplorerPanel` recursively rendering
  `ProjectTreeState.snapshot.root` (a `FolderNode` from `ProjectTreeSnapshotS2C`), with
  expand/collapse driven by `ProjectTreeState.expanded`.
```

- [ ] **Step 2: Grep docs for stale references and fix them**

Run: `grep -rn "ProjectLeafEntry\|specCount\|leaves\b\|intermediates" docs/architecture docs/ui docs/use-cases`
For each hit that describes the **live** listing model (NOT a `docs/superpowers/specs` or `docs/superpowers/plans` snapshot — those are historical, leave them), update it to describe the recursive `FolderNode` tree / `ProjectTreeSnapshotS2C(root, currentSubpath)`. Expected live files to check: `docs/ui/dock-framework.md`, `docs/use-cases/redstone-project.md`, `docs/use-cases/command.md`.

- [ ] **Step 3: Verify no dangling live references remain**

Run: `grep -rn "ProjectLeafEntry\|specCount" docs/architecture docs/ui docs/use-cases`
Expected: zero hits (all live references updated; superpowers snapshots are excluded by the paths above).

- [ ] **Step 4: Full 5-sourceset compile**

Run: `cmd.exe /c "gradlew.bat :26.1:clientClasses :26.1:classes :26.1:gametestClasses :26.1:clientTestClasses :26.1:testClasses"`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Full test verification**

Run: `cmd.exe /c "gradlew.bat :26.1:test"`
Expected: BUILD SUCCESSFUL (unit suite incl. `FileTreeCodecTest` green — read console summary / XML).

Run: `cmd.exe /c "gradlew.bat :26.1:runClientTest"`
Expected: BUILD SUCCESSFUL (`ProjectExplorerSpec` green).

- [ ] **Step 6: Commit**

```bash
git add docs/
git commit -m "docs(architecture): FileTree is wired through the payload and Explorer"
```

---

## Self-Review

**Spec coverage:**
- Recursive codec (spec §1) → Task 1 (codec + `FileTreeCodecTest`). ✓
- Replace payload, delete `ProjectLeafEntry` (spec §2) → Task 1. ✓
- `currentSubpath` sibling field (spec §3) → Task 1 payload + senders. ✓
- Both server senders migrated (spec "Components changed") → Task 1 (Registry + Command). ✓
- Default-collapsed, persistent expand (spec §4) → Task 2 (empty `expanded`, gated recursion) + Task 4 (expands explicitly for screenshot). ✓
- Folder-row interaction: triangle expand / spec-folder load / container toggle (spec §5) → Task 2. ✓
- File select + highlight (spec §6) → Task 2 (`selectedPath`/`select` + background). ✓
- `ProjectFolderTree` retained for placement (spec "Components changed") → not modified; noted in docs Task 5. ✓
- Tests: unit codec / gametest / clientTest (spec "Testing strategy") → Tasks 1, 3, 4. ✓
- Doc sync (spec "Doc sync") → Task 5. ✓
- Verification commands (spec) → Task 5 Steps 4–5. ✓

**Placeholder scan:** No TBD/TODO; every code step shows full code. ✓

**Type consistency:** `FILE_TREE_STREAM_CODEC`, `ProjectTreeSnapshotS2C(root, currentSubpath)`, `selectedPath`/`select(path)`, `toggleExpanded(path)`, `walk()` used identically across Tasks 1–4. ✓
