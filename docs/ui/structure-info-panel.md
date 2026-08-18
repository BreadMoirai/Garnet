---
title: The Structure Info panel
tags: [screens, widgets, dock, structure, status, explorer]
summary: The LEFT panel showing the open structure's subpath, size, block count and last-saved time — why it holds fields rather than a formatted status string, why placing a structure resets the block count and save time, and why the Explorer's inline edit error did not move with the rest of the status line.
---

# The Structure Info panel

`StructureInfoPanel.kt` (`src/client/kotlin/.../editor/structure/ui/`) is the third LEFT-region dock panel,
`structureInfoPanel()` registering `Panel("garnet.structureInfo", "Structure Info", DockRegion.LEFT,
AllIconsKeys.General.Information)`. It shows facts about whichever structure is currently placed in
the world: subpath, size, block count, and last-saved time. See [dock-stripe.md](dock-stripe.md) for
how the stripe icon that opens it works.

## What the panel shows

- The open structure's **subpath**, or `"no structure open"` when none is placed.
- **Size** (`sizeX x sizeY x sizeZ`, ASCII `x` — see "The no-glyph rule" below).
- **Blocks** — omitted while unknown (see "The two sentinels" below).
- **Saved** — the wall-clock time of the last auto-save (`formatClock`, `HH:mm:ss`), omitted while
  never saved this session.
- The editor's transient **status line** — load reports, save reports, place messages, and server
  errors — appended below the facts when non-empty.

All of it comes from `StructureInfoState`, a top-level object fed entirely by network receivers; the
panel composable itself holds no state of its own (see [dock-framework.md](dock-framework.md#panel-composition-must-not-outlive-its-mount)
for why anything panel-local has to be `remember`-ed inside the composable rather than parked in a
global — the dock composes into a long-lived singleton scene, so global state survives a re-mount and
paints over the next one).

## The `ExplorerTreeSnapshot.status` split, and why one string could not serve both purposes

`ExplorerTreeSnapshot` used to hold a single `status: String`, written by **five** different S2C
receivers: `onFolderLoaded`, `onSaveReport`, `onError` (three *transient* feedback messages) and
`onStructureResult`, `onAutoSaved` (two carrying *structural facts* — subpath, size, block count, save
time). Because all five wrote the same field, whichever packet arrived last won, unconditionally. A
transient error arriving after a structure was placed didn't just cover the fact line temporarily —
`onError`'s single-string write **destroyed** the size and block-count information the structure
packets had put there, with no way to recover it until the next structural packet happened to arrive.

`StructureInfoState` splits this into fields: `subpath`, `sizeX`/`sizeY`/`sizeZ`, `blockCount`,
`lastSavedMillis` are written only by `onStructureResult`/`onAutoSaved`; `status` is still a single
transient string, but it no longer shares a field with the facts it used to overwrite. An error can
now update `status` alone, leaving the structure's size and block count exactly as they were.
`ExplorerTreeSnapshot` itself shrank to just `snapshot` — the server's tree — plus `onSnapshot`/`reset`.

## Why a `StructureResultS2C` resets `blockCount` and `lastSavedMillis` while keeping the sizes

`onStructureResult` (fired by a place, among other things) writes the new subpath and sizes, but
**always** resets `blockCount` to `-1` and `lastSavedMillis` to `0L`:

```kotlin
fun onStructureResult(r: StructureResultS2C) {
    subpath = r.subpath
    sizeX = r.sizeX; sizeY = r.sizeY; sizeZ = r.sizeZ
    blockCount = -1
    lastSavedMillis = 0L
    status = r.message
}
```

This is not an oversight — it follows directly from what the two payloads carry.
`StructureResultS2C` (`subpath`, `sizeX/Y/Z`, `message`) has no `blockCount` field at all, and a
structure that was just placed has, by definition, not yet been auto-saved this session — there is no
honest `lastSavedMillis` to report. Carrying the **previous** structure's block count and save time
forward under the *new* structure's name and sizes would be a lie: two different structures could
easily have different block counts, and a stale save timestamp would claim a save that never happened
for what's now on screen. Resetting both to their sentinels is the only value that is actually true at
that moment. `onAutoSaved` (`StructureAutoSavedS2C`: subpath, sizes, `blockCount`, `savedAtMillis`)
fills all five fields at once, because that payload actually carries a real count and a real save time.

## The two sentinels, and why unknown rows are omitted rather than rendered

`blockCount = -1` means "placed, but no auto-save report has arrived for it yet".
`lastSavedMillis = 0L` means "never saved this session". `StructureInfoPanel`'s body checks both
before rendering their row:

```kotlin
if (StructureInfoState.blockCount >= 0) { InfoRow("Blocks", ...) }
if (StructureInfoState.lastSavedMillis > 0L) { InfoRow("Saved", formatClock(...)) }
```

Rendering the sentinel instead — a bare `-1`, or a save time formatted from epoch `0L` (which would
print as a plausible-looking, entirely wrong wall-clock time) — would look like real data and would be
worse than showing nothing: a freshly-placed structure genuinely has no known block count and has
genuinely never been saved this session, and the panel says exactly that by omitting the row rather
than asserting a fact it doesn't have.

## Why `savedAtMillis` needed no protocol change

`lastSavedMillis` reads straight from `StructureAutoSavedS2C.savedAtMillis` — a field the payload
already carried before this panel existed. It is **the server's own write time**, recorded at the
moment `StructureCommit` wrote the `.nbt`, not a client-side receipt timestamp taken when the packet
happens to arrive. That distinction matters under any latency: a client stamp would drift from the
server's actual save time by however long the packet took to arrive, and would report a different
"saved at" for two players watching the same structure. No protocol change was needed to add the
"Saved" row — only a new place to read a field that was already being sent and previously ignored by
the UI.

## The no-glyph rule

No glyphs anywhere in this panel, and `x` between the three size dimensions rather than U+00D7 (`×`).
Jewel's default font family is Inter, which has no emoji/symbol coverage outside its own glyph set;
anything outside that coverage falls through to whatever Skia finds on the host, which renders as tofu
on a system with no matching font installed. Same rule, same reasoning, as
[the Local History panel](local-history-panel.md#no-glyphs-anywhere-in-this-panel) — the two panels
sit side by side in the same stripe and should look consistent.

## Why `editError` stayed in the Explorer, rendered at the field

The Explorer's rename/create inline field used to report a failed commit on a panel-wide status line
at the bottom of the Explorer panel. That line is gone — status now lives in `StructureInfoState` and
renders in the Structure Info panel instead. `editError`, the inline field's own failure message
(`"a name with that extension already exists"` and similar), did **not** move with it. It stayed in
`ExplorerPanel.kt`, `remember`-ed alongside the field it belongs to, and now renders directly
underneath the name field itself.

The reason is that Structure Info and the Explorer are two *different* stripe panels, and only one of
them can be open in LEFT at a time. If `editError` had moved to Structure Info, a user with Structure
Info closed (which is the common case — most of the time there's no reason to have it open) who typed
an illegal rename would see the field's red `Outline.Error` border and get no explanation anywhere on
screen for why the commit refused: the message reporting *why* would be sitting in a panel that isn't
visible. Rendering it inline, in the same panel as the field the error belongs to, guarantees the
explanation is on screen exactly when the border that signals the failure is.

## See also

- [dock-stripe.md](dock-stripe.md) — the per-panel visibility model and the stripe icon that opens
  this panel.
- [dock-framework.md](dock-framework.md) — the dock's overall region/panel model and mount lifecycle.
- [explorer-toolbar-and-context-menu.md](explorer-toolbar-and-context-menu.md) — the inline name field
  `editError` belongs to.
- [local-history-panel.md](local-history-panel.md) — the sibling LEFT panel with the same no-glyph
  rule.
