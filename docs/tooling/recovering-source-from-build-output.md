---
title: Recovering lost Kotlin source from build output
tags: [gradle, tooling, recovery, decompiler]
summary: When uncommitted source is lost, `versions/<mcver>/build/classes/kotlin/**` still holds the last compile; decompile it with the Vineflower jar already in the Windows Gradle cache — and know what that does not bring back.
---

# Recovering lost Kotlin source from build output

Uncommitted work in this repo is not backed by anything except the build directory. If a source file
is lost — a bad `git checkout <path>`, a clobbered write, a stray revert — **the last successful
compile is usually still on disk** and is the fastest way back.

## Where the class files are

Per Stonecutter version slice, not at the repo root:

```
versions/26.2/build/classes/kotlin/main/<package path>/
versions/26.2/build/classes/kotlin/client/…    clientTest/…    test/…
```

Check the mtime first — it tells you how much of the lost work the recovery can possibly contain.
Anything edited after that compile is not in there.

## Decompiling

Vineflower already lives in the **Windows** Gradle cache (this project builds through
`cmd.exe /c gradlew.bat` — see [wsl2-gradle-invocation.md](wsl2-gradle-invocation.md)), so no
download is needed:

```sh
find /mnt/c/Users/<user>/.gradle/caches/modules-2/files-2.1/org.vineflower -name "*.jar"

java -jar .../vineflower-1.11.1.jar -dgs=1 \
  /mnt/h/Repo/Garnet/versions/26.2/build/classes/kotlin/main/com/breadmoirai/garnet/camera/data/ \
  /tmp/out/
```

Point it at the **directory**, not one class: Kotlin splits a file into `Foo.class` plus a
`FooKt.class` facade for its top-level declarations, and you need both. The output is Java-shaped
Kotlin (`copy$default` calls, `var10000` temporaries, explicit `RangesKt.coerceIn`) — readable
enough to transcribe every expression and constant back exactly.

## What decompiling does not recover

**Comments and KDoc are not in the class file.** In this codebase that is most of the value of the
file: the code is short and the reasoning around it is long. Budget for rewriting it, and say plainly
which blocks are reconstructions rather than the original text.

Two things narrow the gap:

- **The committed version.** `git show HEAD:<path>` often still carries most of the prose for the
  parts the uncommitted work did not touch; diffing intent against the decompiled code shows which
  blocks actually changed.
- **Anything already read in the session.** Tool output that quoted the file verbatim is the original
  text and should be preferred over anything rewritten.

## Verifying the reconstruction

Run the unit tests that cover the file (`cmd.exe /c "gradlew.bat :26.2:test"`). Tests written against
the *lost* version are the strongest available evidence that the reconstruction is behaviourally
identical — they were passing before the loss and cannot know they are now running against
transcribed code.

## What does not work here

- **IntelliJ Local History.** `AppData/Local/JetBrains/<product>/LocalHistory/changes.storageData`
  holds change records, but the file *contents* live in the VFS content store
  (`caches/content.dat`), which can be far staler than the history file's mtime suggests. There are
  no compressed content streams in `changes.storageData` to scavenge. Ask the user to check Local
  History in the IDE rather than trying to parse the store.
- **Unreachable git objects.** Worth one sweep
  (`git cat-file --batch-all-objects --batch-check` filtered to blobs, grepped for a distinctive
  identifier), but it only helps if the file was ever `git add`ed. Never-staged work leaves no blob.
