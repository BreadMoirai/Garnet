# Persistence

How specs are saved, loaded, and synced. Covers on-disk formats, block-entity NBT, and C2S/S2C network payloads.

**Tags:** storage, networking, payloads, serialization, nbt, sync

## Articles

- [Spec on-disk format](spec-on-disk-format.md) — JSON + compressed-NBT split, structure-vs-spec id resolution, and the emitter-flow auto-save pipeline. Tags: storage, serialization, codec, json, nbt
- [C2S/S2C payload contract](network-payload-contract.md) — Server-only authority, `originPos` lookup invariant, origin-relative entry coords, and the two confirmation handshakes. Tags: networking, payloads, sync, authority
- [Spec data-model invariants](spec-data-model-invariants.md) — InputSpec START-entry requirement, SimTime sentinel ordering, lazy StateCondition codec, and the no-version migration story. Tags: data-model, serialization, sealed-types, invariants
