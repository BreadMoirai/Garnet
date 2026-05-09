# Persistence

How specs are saved, loaded, and synced. On-disk format is `.spec.kts` (Kotlin
script); JSON is not used on disk or over the wire for spec content.

**Tags:** storage, networking, payloads, serialization, nbt, sync, scripting, dsl

## Articles

- [Spec on-disk format](spec-on-disk-format.md) — `.spec.kts` files, file naming, save dir, no-JSON-on-disk. Tags: storage, scripting
- [.spec.kts script host](kts-script-host.md) — How `KtsSpecLoader` (in `persistence/`) evaluates spec files via kotlin-scripting; why a custom host (vs JSR-223); file contract; threat model. Tags: persistence, scripting, dsl
- [C2S/S2C payload contract](network-payload-contract.md) — Server-only authority, `originPos` lookup invariant, origin-relative entry coords, and the two confirmation handshakes. Tags: networking, payloads, sync, authority
- [Spec DSL invariants](spec-data-model-invariants.md) — RedstoneSpec / SpecRun constraints: bounds, lifespan, lambda-is-the-spec. Tags: data-model, dsl, invariants
