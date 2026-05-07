---
title: DiodeBlock.FACING points to the BACK (input) side
tags: [redstone, mc-api, quirks, blocks]
summary: For repeaters and comparators, FACING is the input direction; output emits to FACING.getOpposite(). Inverting this silently breaks redstone analysis.
---

# DiodeBlock.FACING points to the BACK (input) side

`net.minecraft.world.level.block.DiodeBlock.FACING` (inherited by
`RepeaterBlock` and `ComparatorBlock`) names the direction the diode reads its
**input** from — i.e. the *back* of the block, opposite the visible arrow.

This is unintuitive: in-world the arrow points the way the signal flows, but
in code that direction is `FACING.getOpposite()`.

## Verified from MC 26.1 source

`net/minecraft/world/level/block/DiodeBlock.java`:

```java
// getInputSignal — reads from FACING (the back)
Direction direction = state.getValue(FACING);
// ... level.getSignal(pos.relative(direction), direction) ...

// updateNeighborsInFront — emits to FACING.getOpposite() (the front)
Direction direction = ((Direction) state.getValue(FACING)).getOpposite();
// updates pos.relative(direction)

// setPlacedBy / getStateForPlacement — player faces the output direction
return this.defaultBlockState()
    .setValue(FACING, context.getHorizontalDirection().getOpposite());
```

So when a player places a comparator while looking east, the comparator's
*output* goes east, but `FACING = WEST` is stored.

## Practical encoding

| Layout description | FACING value |
|---|---|
| "comparator with output east" | `Direction.WEST` |
| "repeater feeding north" | `Direction.SOUTH` |
| "comparator reading from north" | `Direction.NORTH` |

Always set FACING to the **opposite** of the desired signal-flow direction.

## Symptoms of an inverted FACING

- Wires connect visually correctly (`east=side`, etc.) — connection logic is
  agnostic to which end is input.
- The diode's `POWERED` value never changes even though the upstream wire is
  clearly powered.
- Recording a circuit shows zero state changes on the downstream wire while
  upstream wires update normally.

If a comparator/repeater scenario has these symptoms, suspect FACING inversion
before chasing wire connection or signal-strength bugs.

## Related code in this repo

`SpecMarkerTool.kt` and the runner's condition application both consume diode
states; any code that reasons about "which neighbor is the input" should use
`pos.relative(state.getValue(DiodeBlock.FACING))`.
