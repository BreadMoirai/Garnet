# Persistence

How specs are saved, loaded, and synced. On-disk format is `.spec.kts` (Kotlin
script); JSON survives as the network wire format only.

**Tags:** storage, networking, payloads, serialization, nbt, sync, scripting, dsl

## Articles

- [Spec on-disk format](spec-on-disk-format.md) — `.spec.kts` files, file naming, save dir, no-JSON-on-disk. Tags: storage, scripting
- [.spec.kts script host](kts-script-host.md) — How kotlin-scripting evaluates spec files; why a custom host (vs JSR-223); file contract; threat model. Tags: persistence, scripting, dsl
- [C2S/S2C payload contract](network-payload-contract.md) — Server-only authority, `originPos` lookup invariant, origin-relative entry coords, and the two confirmation handshakes. Tags: networking, payloads, sync, authority
- [Spec data-model invariants](spec-data-model-invariants.md) — Bounds, lifespan, flat SpecEntry list. Tags: data-model, serialization, invariants
