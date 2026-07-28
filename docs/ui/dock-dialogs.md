---
title: Dialogs in the dock — Compose Popup renders in-scene, native pickers on a worker thread
tags: [compose, dock, dialogs, popup, jewel, tinyfd, threading, gotcha]
summary: Compose Popup/DropdownMenu DO render inside the embedded ImageComposeScene (it's a CanvasLayersComposeScene, so popup layers draw in-canvas, not a separate OS window) — Jewel's Dropdown is used directly. Native OS dialogs (tinyfd) still block, so run them off the render thread and marshal back via Minecraft.execute.
---

# Dialogs in the dock

Two rules govern any menu or dialog inside the Compose dock.

## Compose `Popup` renders in-scene — retired "hand-roll it" advice

**Retired 2026-07-28 (jewel-widget-layer spike):** this doc previously said the dock's
`ImageComposeScene` couldn't host a Compose `Popup`/`DropdownMenu` because they open a
separate desktop window, and told panels to hand-roll overlays instead
(`ProjectExplorerPanel.RootMenu` was the reference for that pattern). That premise was wrong
for Compose 1.11's `ImageComposeScene`: it is internally a `CanvasLayersComposeScene`, so
popup layers draw into the *same* canvas as the rest of the scene rather than spawning an OS
window. The spike confirmed a bare Compose `Popup` renders in-scene; the Explorer panel now
uses Jewel's `Dropdown` (built on `PopupContainer` → `androidx.compose.ui.window.Popup`)
directly for its root-folder picker, and `RootMenu` was deleted along with
`RootPickerController.menuOpen`/`toggleMenu`/`closeMenu`. New dock UI should reach for
Jewel's `Dropdown`/menu components rather than hand-rolling a scrim+card overlay. Keep the
trigger control *outside* any scrollable container so the popup isn't scroll-clipped — that
constraint survives from the old advice (this is the Compose-era analog of the legacy
"scissor baked at record time" dropdown warning).

## Native OS dialogs block — run them off the render thread

`org.lwjgl.util.tinyfd.TinyFileDialogs` (bundled with MC via `lwjgl-tinyfd`) gives a real
native folder/file picker. **It blocks the calling thread until the user dismisses it** — so
never call it on the render/client thread or the game freezes. Run it on a worker thread and
marshal the result back to the client thread via `Minecraft.getInstance().execute {}` before
touching game/network state. `RootPickerController` is the reference: the dialog, the thread,
the client-thread marshal, the network send, and disk persistence are each injectable seams so
the flow is testable without a real dialog.
