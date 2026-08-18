---
title: Package Move Mechanics
tags: [gradle, kotlin, refactor, package-move, sed, verification]
summary: Gotchas for moving files between packages across all six source sets without silently breaking discovery or content.
---

# Package Move Mechanics

Reference notes for the next time files move between packages (or into a new
feature/layer layout) across this mod's six source sets (`main`, `client`, `gametest`,
`clientTest`, `test`, and any version-specific set). Each item names the failure mode it
prevents.

## 1. Sentinel classes are pinned by `fabric.mod.json`

`GametestSentinel` (`src/gametest/kotlin/com/breadmoirai/garnet/test/GametestSentinel.kt`)
and `ClientTestSentinel`
(`src/clientTest/kotlin/com/breadmoirai/garnet/test/ClientTestSentinel.kt`) must stay in
`com.breadmoirai.garnet.test`. They are named as entrypoints by fully-qualified class name
in `src/gametest/resources/fabric.mod.json:9` and
`src/clientTest/resources/fabric.mod.json:9`. Moving either class to a different package
breaks gametest/clientTest *discovery* — the mod fails to register the entrypoint — and
nothing in the normal verification loop catches it: neither `:26.2:test` nor any
`*Classes` compile task reads `fabric.mod.json`. Grep both `fabric.mod.json` files for the
sentinel FQNs as a dedicated check whenever a move touches `com.breadmoirai.garnet.test`.

## 2. Gametest/clientTest specs are runtime-discovered by annotation

A green compile and a green `:26.2:test` prove nothing about the `gametest` and
`clientTest` source sets — those specs are found at runtime by annotation, not wired into
the Kotest/JUnit run that `:26.2:test` executes. Only a live `:26.2:runGameTest` or
`:26.2:runClientTest` actually exercises them. Note the capital T in `runGameTest`
(`runGameTest`, not `rungametest`) — Gradle task-name matching is case-sensitive enough
that a wrong-case invocation fails to find the task rather than silently no-op-ing.

## 3. `grep` needs `-a` in this repo

`src/test/kotlin/com/breadmoirai/garnet/editor/explorer/data/EditorRootTest.kt` embeds a
NUL byte. Plain `grep` treats the file as binary on sight and reports zero matches — which
looks identical to "the rewrite already ran and there's nothing left to find." Always pass
`-a` (or use `grep -a` / `ugrep` equivalents) when auditing a package move with grep, or a
real leftover reference in that file will pass a review silently.

## 4. Slash-path string literals are invisible to FQN sweeps

A test can hardcode a source path as a plain string literal, e.g.
`"src/client/kotlin/com/breadmoirai/garnet/.../GarnetTextField.kt"`. No dot-separated
package `sed` (matching `com\.breadmoirai\.garnet\.old\.pkg`) will ever touch a
slash-separated path like this — the delimiters don't overlap. A package move needs a
second, separate sweep: `grep -rn 'src/.*/garnet/' <sourceSet>` (or equivalent) for
hardcoded slash-paths, independent of the FQN-based sed/grep pass.

## 5. `sed -i '1s/.*/package X/'` is annotation-unsafe

Rewriting "line 1" unconditionally destroys whatever actually occupies line 1 when it
isn't the package declaration — a leading `@file:OptIn(...)`, a license header, or a
top-of-file comment. Use a pattern that targets the *first line matching `^package `*
instead:

```
sed -i '0,/^package /{s|^package .*|package com.breadmoirai.garnet.new.pkg|}' File.kt
```

Verify immediately after with `grep -Hn '^package' File.kt` — it must report exactly one
hit, at the correct new package. A count of zero means the file had no package line to
rewrite (check it wasn't skipped by mistake); a count of two-plus means something is
badly wrong with the file.

## 6. Per-class `sed` loops skip Kotlin top-level functions

A loop that walks a list of capitalized class/object names and rewrites their imports
misses Kotlin top-level `fun`s, which are imported by FQN exactly like a class is
(`import com.breadmoirai.garnet.old.pkg.someTopLevelFun`). A capitalized-names-only loop
silently leaves these unrewritten — e.g. `explorerPanel()`, `localHistoryPanel()`,
`bootWorkspace()`. Always finish a package move with a survivor sweep of the *emptied*
package prefix (`grep -rn 'com\.breadmoirai\.garnet\.old\.pkg\.' src/`) rather than trusting
a symbol-name-driven rewrite to have caught everything importable from that package.

## 7. A mis-targeted rewrite is invisible to survivor sweeps

A survivor sweep (see #6) keys on the *old* package prefix disappearing — but a rewrite
that lands the wrong *new* package (e.g. a typo, or a same-named class in two different
target packages) produces a file with no old-prefix references left, so the sweep reports
clean while the reference is simply wrong. The only ways to catch this are a content-diff
audit against the pre-move blob (confirm every changed line's payload change is exactly the
expected old-package-to-new-package substitution and nothing else) or, more reliably, the
compiler — an unresolved or wrongly-resolved reference. Don't treat "survivor sweep is
clean" as proof the move to the new package was itself performed correctly.
