---
title: Dialogs in the dock — Compose Popup renders in-scene, native pickers on a worker thread
tags: [compose, dock, dialogs, popup, jewel, nfd, threading, gotcha]
summary: Compose Popup/DropdownMenu DO render inside the embedded ImageComposeScene (it's a CanvasLayersComposeScene, so popup layers draw in-canvas, not a separate OS window) — Jewel's Dropdown is used directly. The native folder picker (NFD) still blocks, so it runs off the render thread except on macOS, where AppKit forces it back onto that thread.
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

## Native OS dialogs block — run them off the render thread (except on macOS)

The Explorer's root picker (`NfdFolderPicker`, in `editor/ui/FolderPicker.kt`) is backed by
LWJGL's bindings for [NFD](https://github.com/btzy/nativefiledialog-extended)
(`org.lwjgl.util.nfd.NativeFileDialog`) rather than tinyfd. Tinyfd's folder picker on Windows is
its legacy Win32 `SHBrowseForFolder` browser — a small, dated tree view — where NFD drives the
real `IFileOpenDialog`/`NSOpenPanel`/GTK chooser the OS ships today. MC 26.2 bundles LWJGL 3.4.1
with `lwjgl-tinyfd` but not `lwjgl-nfd`, so this mod adds `lwjgl-nfd` itself, pinned to the same
3.4.1 as MC's `lwjgl-core` (a version mismatch across LWJGL modules is a linker-error risk), with
all six natives classifiers jar-in-jar'd via `include(...)` in `build.gradle.kts` so the native
libraries ship inside the mod jar rather than requiring a separate download.

**It blocks the calling thread until the user dismisses it** — so on most platforms it must not
run on the render/client thread or the game freezes. `NfdFolderPicker.pick` also has an
NFD-specific same-thread constraint: `NFD_Init`, `NFD_PickFolder`, and `NFD_Quit` all run inside
one call on one thread. On Windows, NFD's init performs the COM `CoInitializeEx` on the
*calling* thread; splitting the three calls across threads would leave the dialog thread without
an initialized COM apartment and the picker would fail to open. So the entire pick — init,
dialog, cleanup — is one atomic unit handed to whichever thread runs it.

**macOS is the exception to "off the render thread."** `RootPickerController`'s `defaultRunner()`
runs the picker *inline* on the calling thread there instead of on a worker thread, because NFD
drives `NSOpenPanel`, which must be invoked from the AppKit main thread — and under
`-XstartOnFirstThread` (required for LWJGL/GLFW on macOS) that thread *is* Minecraft's render
thread. The worker-thread rule and the AppKit main-thread rule cannot both hold, so the game
visibly freezes behind the modal on macOS until it's dismissed; that's an accepted trade against
the alternative of an AppKit crash from calling `NSOpenPanel` off its required thread. Everywhere
else, the picker still runs on a worker thread, with the result marshaled back to the client
thread via `Minecraft.getInstance().execute {}` before touching game/network state.
`RootPickerController` is the reference for the seam layout: the dialog (`picker`), the thread
(`runner`), the client-thread marshal (`executor`), the network send (`sender`), and disk
persistence (`persist`) are each injectable seams so the flow is testable without a real dialog.
