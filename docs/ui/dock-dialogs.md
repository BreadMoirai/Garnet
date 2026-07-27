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
