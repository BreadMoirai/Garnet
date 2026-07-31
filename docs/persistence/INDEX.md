# Persistence

How specs are saved, loaded, and synced. On-disk format is `.spec.kts` (Kotlin
script); JSON is not used on disk or over the wire for spec content.

**Tags:** storage, networking, payloads, serialization, nbt, sync, scripting, dsl

## Articles

- [Spec on-disk format](spec-on-disk-format.md) — `.spec.kts` files, file naming, save dir, no-JSON-on-disk, and standalone `.nbt` structures placeable/creatable directly from the Explorer, with `.nbt.unsaved` dirty buffers on world-save. Tags: storage, scripting
- [.spec.kts script host](kts-script-host.md) — How `KtsSpecLoader` (in `persistence/`) evaluates spec files via kotlin-scripting; why a custom host (vs JSR-223); file contract; threat model. Tags: persistence, scripting, dsl
- [Editor C2S/S2C payload contract](network-payload-contract.md) — Server-only authority for the editor/Explorer wire protocol: path-containment via `EditorRoot.resolveSubpath`, per-player intent via `EditorSession`, replacing the deleted block-entity/`originPos` trust anchor. Tags: networking, payloads, sync, authority, editor
- [Spec DSL invariants](spec-data-model-invariants.md) — GarnetSpec / SpecRun constraints: bounds, lifespan, lambda-is-the-spec. Tags: data-model, dsl, invariants
