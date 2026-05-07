---
title: C2S/S2C payload contract
tags: [networking, payloads, sync, authority]
summary: The authority model behind the C2S/S2C payloads in `network/Packets.kt` — every payload carries originPos, server resolves the BE, and the client never writes spec state directly.
---

# C2S/S2C payload contract

All gameplay-state mutations flow through `network/Packets.kt`. The contract is rigid and worth internalising before adding new payloads.

## Invariant 1: server is the only writer

The client never mutates spec state and never writes to disk. Even the file browser is a request: `RequestFileBrowserC2SPayload` -> `OpenFileBrowserS2CPayload(files: List<SpecFileInfo>)`. The client has no view of the save directory.

`SpecFileInfo` is a denormalised projection (`id`, `mode`, `lifespan`, `inputCount`, `outputCount`, `structure`) computed on the server by `SpecPersistence.listSpecsInfo` — it loads each spec, extracts the summary, and sends a list. The client never reconstructs a full `RedstoneSpec` from `SpecFileInfo`; if it wants the body, it sends `LoadFromFileC2SPayload`.

## Invariant 2: every payload carries `originPos`

Every C2S payload (and most S2C payloads) carry `originPos: BlockPos`. The server-side handlers all begin with the same lookup:

```kotlin
val be = player.level().getBlockEntity(payload.originPos) as? SpecBlockEntity ?: return@execute
```

This is the *only* sanctioned way to find the target BE in a network handler. There is no session, no handle, no token. Consequences:

- **Stale references are silent no-ops.** If the spec block was broken/replaced between client send and server execute, the cast fails and the handler returns. There is no error feedback to the client.
- **Block kind is re-validated** for state-changing transforms. The `transformToRunner`/`transformToRecorder`/`transformToEditor` handlers check `level.getBlockState(originPos).block is RedstoneSpecXBlock` because the underlying BE is shared across all three blocks — without the check, a `TransformToEditorC2SPayload` could be applied to a recorder.

## Invariant 3: entry coordinates are origin-relative

`SaveSpecEntryC2SPayload` and `RemoveSpecEntryC2SPayload` carry positions relative to `originPos`, not world positions. The wire encoding uses `BlockPos.STREAM_CODEC` which is a raw long, but the *meaning* is offset-from-origin. This is consistent with how `SpecEntry.pos` is stored on disk, so an editor packet round-trips through persistence without translation.

## Authority handshakes (the exceptions)

Two flows require client confirmation before the server commits:

1. **Overwrite prompt.** `LoadFromFileC2SPayload` -> server checks `hasNonAirBlocks` -> `OverwritePromptS2CPayload` -> client UI -> `OverwriteDecisionC2SPayload(overwrite=true|false)`. Until the decision arrives, the spec data is loaded onto the BE but the structure is not placed.
2. **Recording finalize.** `StopRecordingC2SPayload` triggers `stopRecordingAndFinalize()`; on success the BE auto-transforms to the editor block. The client does not pre-decide.

Note: the `Undo` payload is unit-typed (`StreamCodec.unit`) — the undo stack lives server-side keyed by `player.uuid`; the client just signals intent.

## Stream codec idioms used here

- Fixed records: `StreamCodec.composite(...)` — most payloads.
- Optional string: hand-rolled `object : StreamCodec` writing a leading bool flag, used by `SetStructureC2SPayload` and `SpecFileInfo`. There is no `optional` combinator on `StreamCodec` in MC 26.1.
- Enum-by-ordinal: `ByteBufCodecs.VAR_INT.map({ entries[it] }, Enum::ordinal)` — used for `SpecMode`. This is fragile under reorderings; treat the enum order as wire-stable.
- Reuse a `Codec` over the wire: `ByteBufCodecs.fromCodec(SpecEntry.CODEC)` — used wherever the data type already had a JSON codec (`SpecEntry`, `TestResult`, `BoundingBox`). This re-encodes through NBT under the hood, which is more bytes than a hand-rolled stream codec but trivially correct.

All payload types are registered in `NetworkRegistry.registerNetworking()`. If you add a payload, register it in the right direction (`clientboundPlay` vs `serverboundPlay`) — Fabric will silently drop unregistered types.
