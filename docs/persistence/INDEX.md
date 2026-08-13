# Persistence

How specs are saved, loaded, and synced. On-disk format is `.spec.kts` (Kotlin
script); JSON is not used on disk or over the wire for spec content.

**Tags:** storage, networking, payloads, serialization, nbt, sync, scripting, dsl

## Articles

- [Spec on-disk format](spec-on-disk-format.md) — `.spec.kts` files, file naming, save dir, no-JSON-on-disk, and standalone `.nbt` structures placeable/creatable directly from the Explorer, auto-saved with local history. Tags: storage, scripting
- [.spec.kts script host](kts-script-host.md) — How `KtsSpecLoader` (in `persistence/`) evaluates spec files via kotlin-scripting; why a custom host (vs JSR-223); file contract; threat model. Tags: persistence, scripting, dsl
- [Editor C2S/S2C payload contract](network-payload-contract.md) — Server-only authority for the editor/Explorer wire protocol: path-containment via `EditorRoot.resolveSubpath`, per-player intent via `EditorSession`, replacing the deleted block-entity/`originPos` trust anchor. Tags: networking, payloads, sync, authority, editor
- [Spec DSL invariants](spec-data-model-invariants.md) — GarnetSpec / SpecRun constraints: bounds, lifespan, lambda-is-the-spec. Tags: data-model, dsl, invariants
- [Local history for standalone structures](local-history.md) — How auto-saved .nbt structures record revisions under <instance>/.garnet/local-history, why the key is the file's absolute path, how pruning works, how a delete banks a pre-delete revision of every file type, and why a deleted structure keeps its history (so a file recreated at the same path inherits it). Tags: storage, history, autosave, structures, persistence
- [Explorer undo/redo command stack](editor-undo-stack.md) — Why the undo stack stores server-authored `EditorUndoCommand` records rather than the C2S packets, how a delete is made reversible by banking every file, and why a stale entry is refused rather than discarded. Tags: editor, undo, history, networking, persistence
- [Explorer session state](explorer-session-state.md) — The Explorer's expansion and selection persist to config/garnet-explorer.json, keyed by project root, restored one-shot when the first tree snapshot lands after a join; also covers the sibling config/garnet-dock.json store for remembered dock LEFT visibility. Tags: storage, config, explorer, client, persistence, dock
