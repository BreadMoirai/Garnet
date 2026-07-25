# Project File-Tree Data Model Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a recursive file-tree data model (`FolderNode`/`FileNode`) that mirrors a project folder from disk, with computed (not stored) paths so any folder can serve as the root.

**Architecture:** One new pure-Kotlin file `project/FileTree.kt` holding a sealed `FileTreeNode` hierarchy, a `scanFolder(Path)` scanner, and two extension helpers (`walk`, `resolve`). No Minecraft dependencies, so it lives in `src/main` but is fully unit-testable from `src/test`. The existing flat `ProjectFolderTree` and all network/UI consumers are left untouched — migrating them is a later task.

**Tech Stack:** Kotlin 2.3.20, `kotlin.io.path` extensions (stable — no opt-in), Kotest `FunSpec` unit tests (autoscanned in `src/test`, no sentinel registration needed).

## Global Constraints

- **Build/test runner:** always `cmd.exe /c "gradlew.bat <task>"` — no `./` prefix (cmd.exe can't parse it).
- **Stonecutter task path:** the active version is `:26.1:`, e.g. `:26.1:test`, `:26.1:testClasses`.
- **Kotest + Gradle `--tests` does not work** — it reports a false "No tests found". Run the unfiltered `:26.1:test` task and read results from the console summary or `build/test-results/test/*.xml`.
- **Use `kotlin.io.path` idioms**, not raw `java.nio.file`: `path.name`, `path.extension`, `path.isDirectory()`, `path.listDirectoryEntries()`, and `kotlin.io.path.div` (`base / "sub"`) for joins.
- **No baked paths on nodes** — nodes store only `name`; paths are computed relative to whichever `FolderNode` is treated as root.
- Full compile sanity (all sourcesets) uses: `cmd.exe /c "gradlew.bat :26.1:clientClasses :26.1:classes :26.1:gametestClasses :26.1:clientTestClasses :26.1:testClasses"`.

---

### Task 1: Node types + `scanFolder`

**Files:**
- Create: `src/main/kotlin/com/breadmoirai/redstonespecs/project/FileTree.kt`
- Test: `src/test/kotlin/com/breadmoirai/redstonespecs/project/FileTreeTest.kt`

**Interfaces:**
- Consumes: nothing (leaf task).
- Produces:
  - `sealed interface FileTreeNode { val name: String }`
  - `data class FolderNode(val name: String, val children: List<FileTreeNode>) : FileTreeNode`
  - `data class FileNode(val name: String, val extension: String) : FileTreeNode`
  - `fun scanFolder(path: java.nio.file.Path): FolderNode`

- [ ] **Step 1: Write the failing tests**

Create `src/test/kotlin/com/breadmoirai/redstonespecs/project/FileTreeTest.kt`:

```kotlin
package com.breadmoirai.redstonespecs.project

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.div
import kotlin.io.path.name
import kotlin.io.path.writeText

class FileTreeTest : FunSpec({

    test("scans nested folders and files into a recursive tree, including empty folders") {
        val tmp = Files.createTempDirectory("ft-structure")
        (tmp / "sub").createDirectories()
        (tmp / "a.spec.kts").writeText("")
        (tmp / "sub" / "b.spec.kts").writeText("")
        (tmp / "sub" / "empty").createDirectories()

        val root = scanFolder(tmp)

        root.name shouldBe tmp.name
        root.children.map { it.name } shouldContainExactly listOf("sub", "a.spec.kts")

        val sub = root.children.first { it.name == "sub" } as FolderNode
        sub.children.map { it.name } shouldContainExactly listOf("empty", "b.spec.kts")
        (sub.children.first { it.name == "empty" } as FolderNode).children shouldBe emptyList()
    }

    test("children are folders-first, then files, alphabetical case-insensitive") {
        val tmp = Files.createTempDirectory("ft-order")
        (tmp / "Zeta").createDirectories()
        (tmp / "alpha").createDirectories()
        (tmp / "b.txt").writeText("")
        (tmp / "A.txt").writeText("")

        val root = scanFolder(tmp)

        root.children.map { it.name } shouldContainExactly listOf("alpha", "Zeta", "A.txt", "b.txt")
    }

    test("file nodes carry the lowercased last-dot extension; empty when none") {
        val tmp = Files.createTempDirectory("ft-ext")
        (tmp / "foo.spec.kts").writeText("")
        (tmp / "data.NBT").writeText("")
        (tmp / "README").writeText("")

        val root = scanFolder(tmp)
        val byName = root.children.filterIsInstance<FileNode>().associateBy { it.name }

        byName.getValue("foo.spec.kts").extension shouldBe "kts"
        byName.getValue("data.NBT").extension shouldBe "nbt"
        byName.getValue("README").extension shouldBe ""
    }

    test("non-existent or non-directory path yields an empty root folder named after the path") {
        val tmp = Files.createTempDirectory("ft-missing")
        scanFolder(tmp / "nope") shouldBe FolderNode("nope", emptyList())

        val file = (tmp / "file.txt").also { it.writeText("") }
        scanFolder(file) shouldBe FolderNode("file.txt", emptyList())
    }
})
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `cmd.exe /c "gradlew.bat :26.1:test"`
Expected: FAIL — compilation error, `scanFolder` / `FolderNode` / `FileNode` unresolved.

- [ ] **Step 3: Write the minimal implementation**

Create `src/main/kotlin/com/breadmoirai/redstonespecs/project/FileTree.kt`:

```kotlin
package com.breadmoirai.redstonespecs.project

import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

/** A node in a project's file tree. Stores only its own [name]; paths are computed on demand. */
sealed interface FileTreeNode {
    val name: String
}

/** A folder. [children] are sorted folders-first, then files, alphabetical case-insensitive. */
data class FolderNode(
    override val name: String,
    val children: List<FileTreeNode>,
) : FileTreeNode

/** A file. [extension] is the lowercased last-dot extension, "" when the name has no dot. */
data class FileNode(
    override val name: String,
    val extension: String,
) : FileTreeNode

// Folders before files (false < true), then case-insensitive name order.
private val CHILD_ORDER: Comparator<FileTreeNode> =
    compareBy<FileTreeNode> { it !is FolderNode }
        .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }

/**
 * Recursively scan [path] into a [FolderNode] mirroring the filesystem: all files (any
 * extension) and all folders, including empty ones. A non-existent or non-directory path
 * yields an empty root folder named after the path's last segment.
 */
fun scanFolder(path: Path): FolderNode {
    if (!path.isDirectory()) return FolderNode(path.name, emptyList())
    val children = path.listDirectoryEntries()
        .map { entry ->
            if (entry.isDirectory()) scanFolder(entry)
            else FileNode(entry.name, entry.extension.lowercase())
        }
        .sortedWith(CHILD_ORDER)
    return FolderNode(path.name, children)
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `cmd.exe /c "gradlew.bat :26.1:test"`
Expected: PASS — the 4 `FileTreeTest` cases green in the console summary (and in `build/test-results/test/*.xml`). Other existing unit tests remain green.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/breadmoirai/redstonespecs/project/FileTree.kt \
        src/test/kotlin/com/breadmoirai/redstonespecs/project/FileTreeTest.kt
git commit -m "feat(project): recursive file-tree model (FolderNode/FileNode) + scanFolder"
```

---

### Task 2: `walk` and `resolve` path helpers

**Files:**
- Modify: `src/main/kotlin/com/breadmoirai/redstonespecs/project/FileTree.kt` (append helpers)
- Test: `src/test/kotlin/com/breadmoirai/redstonespecs/project/FileTreeTest.kt` (append cases)

**Interfaces:**
- Consumes: `FolderNode`, `FileNode`, `FileTreeNode`, `scanFolder` from Task 1.
- Produces:
  - `fun FolderNode.walk(): Sequence<Pair<String, FileTreeNode>>` — depth-first pre-order; every node paired with its `/`-joined path relative to the receiver; the receiver itself is emitted as `"" to this`.
  - `fun FolderNode.resolve(path: String): FileTreeNode?` — inverse of `walk`; `""` returns the receiver; null if any segment is missing.

- [ ] **Step 1: Write the failing tests**

Append these cases inside the existing `FileTreeTest` `FunSpec({ ... })` block in
`src/test/kotlin/com/breadmoirai/redstonespecs/project/FileTreeTest.kt` (before the closing `})`):

```kotlin
    test("walk emits every node with its '/'-path, depth-first; root maps to empty string") {
        val tmp = Files.createTempDirectory("ft-walk")
        (tmp / "sub").createDirectories()
        (tmp / "a.spec.kts").writeText("")
        (tmp / "sub" / "b.spec.kts").writeText("")

        val root = scanFolder(tmp)

        root.walk().map { it.first }.toList() shouldContainExactly
            listOf("", "sub", "sub/b.spec.kts", "a.spec.kts")
    }

    test("resolve round-trips every path from walk; empty path returns the receiver; missing returns null") {
        val tmp = Files.createTempDirectory("ft-resolve")
        (tmp / "sub").createDirectories()
        (tmp / "sub" / "b.spec.kts").writeText("")

        val root = scanFolder(tmp)

        root.walk().forEach { (path, node) -> root.resolve(path) shouldBe node }
        root.resolve("") shouldBe root
        root.resolve("sub/missing.kts") shouldBe null
        root.resolve("nope") shouldBe null
    }

    test("walk/resolve on a subfolder produce paths relative to that subfolder") {
        val tmp = Files.createTempDirectory("ft-reroot")
        (tmp / "sub" / "deep").createDirectories()
        (tmp / "sub" / "deep" / "c.spec.kts").writeText("")

        val root = scanFolder(tmp)
        val sub = root.resolve("sub") as FolderNode

        sub.walk().map { it.first }.toList() shouldContainExactly
            listOf("", "deep", "deep/c.spec.kts")
        sub.resolve("deep/c.spec.kts") shouldBe root.resolve("sub/deep/c.spec.kts")
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `cmd.exe /c "gradlew.bat :26.1:test"`
Expected: FAIL — compilation error, `walk` / `resolve` unresolved on `FolderNode`.

- [ ] **Step 3: Write the minimal implementation**

Append to `src/main/kotlin/com/breadmoirai/redstonespecs/project/FileTree.kt`:

```kotlin
/**
 * Every node under this folder paired with its `/`-joined path relative to this folder, in
 * depth-first pre-order. The receiver itself is emitted first as `"" to this`. Child order
 * follows [FolderNode.children] (folders-first, then files, alphabetical).
 */
fun FolderNode.walk(): Sequence<Pair<String, FileTreeNode>> = sequence {
    suspend fun SequenceScope<Pair<String, FileTreeNode>>.visit(prefix: String, node: FileTreeNode) {
        yield(prefix to node)
        if (node is FolderNode) {
            for (child in node.children) {
                val childPath = if (prefix.isEmpty()) child.name else "$prefix/${child.name}"
                visit(childPath, child)
            }
        }
    }
    visit("", this@walk)
}

/**
 * Resolve a `/`-joined [path] (relative to this folder; `""` = the folder itself) to a node,
 * or null if any segment is missing or a non-final segment is a file. Matches children by
 * [name]; does not depend on holding a specific node instance.
 */
fun FolderNode.resolve(path: String): FileTreeNode? {
    if (path.isEmpty()) return this
    var current: FileTreeNode = this
    for (segment in path.split('/')) {
        val folder = current as? FolderNode ?: return null
        current = folder.children.firstOrNull { it.name == segment } ?: return null
    }
    return current
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `cmd.exe /c "gradlew.bat :26.1:test"`
Expected: PASS — all 7 `FileTreeTest` cases green; other unit tests remain green.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/breadmoirai/redstonespecs/project/FileTree.kt \
        src/test/kotlin/com/breadmoirai/redstonespecs/project/FileTreeTest.kt
git commit -m "feat(project): walk/resolve path helpers for the file-tree model"
```

---

### Task 3: Document the new model

**Files:**
- Modify: `docs/architecture/redstone-project.md` (the "Components → Pure data" list)

**Interfaces:**
- Consumes: nothing (docs only).
- Produces: nothing (docs only).

- [ ] **Step 1: Add the `FileTree` entry to the Pure-data component list**

In `docs/architecture/redstone-project.md`, under `## Components` → the `Pure data:` bullet list
(the one that starts with `- `ProjectRoot` — ...`), add a new bullet immediately after the
`ProjectFolderTree` bullet:

```markdown
- `FileTree` — recursive tree model (`FolderNode`/`FileNode` under sealed `FileTreeNode`) built by
  `scanFolder(path)`; mirrors the whole folder (all files/folders, incl. empty), folders-first
  ordering. Paths are **computed, not stored** — `FolderNode.walk()` (node→path) and
  `FolderNode.resolve(path)` (path→node), both relative to whichever folder is the root, so
  re-rooting is free. **Not yet wired** to the network payload or the Explorer, which still use
  the flat `ProjectFolderTree`.
```

- [ ] **Step 2: Verify the reference is accurate**

Run: `cmd.exe /c "gradlew.bat :26.1:testClasses"` (cheap compile check that the cited names exist)
Expected: BUILD SUCCESSFUL. Manually confirm `FolderNode`, `FileNode`, `FileTreeNode`,
`scanFolder`, `walk`, `resolve` all appear in `FileTree.kt` exactly as named in the doc entry.

- [ ] **Step 3: Commit**

```bash
git add docs/architecture/redstone-project.md
git commit -m "docs(architecture): document the FileTree model in redstone-project components"
```

---

## Self-Review

**Spec coverage:**
- Types (`FileTreeNode`/`FolderNode`/`FileNode`, name-only, no baked path) → Task 1. ✓
- `scanFolder` (all files/folders, empty folders, folders-first ordering, missing→empty root, `kotlin.io.path` idioms) → Task 1. ✓
- Extension = simple last-dot lowercased → Task 1 (`entry.extension.lowercase()`, test asserts `kts`/`nbt`/`""`). ✓
- `resolve` (path→node) + `walk` (node→path), root=`""`, re-rooting relative to receiver → Task 2. ✓
- Test matrix (structure, ordering, empty folders, all extensions, walk paths, resolve round-trip, re-rooting, missing path) → Tasks 1 & 2 cover every bullet in the spec's Testing section. ✓
- Docs update to `redstone-project.md` Pure-data list → Task 3. ✓
- Scope boundary (do not touch `ProjectFolderTree`/network/panel) → honored; no task modifies them. ✓

**Placeholder scan:** No TBD/TODO; every code and test step contains complete content. ✓

**Type consistency:** `FolderNode(name, children)`, `FileNode(name, extension)`, `scanFolder(path)`,
`FolderNode.walk()`, `FolderNode.resolve(path)` are used identically in Task 1 interfaces, Task 2
interfaces, the implementations, and the Task 3 doc entry. ✓
