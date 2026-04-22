# Spec Origin Bounds UI Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a `SpecBoundsScreen` sub-screen for editing spec size/offset, adopt Structure Block visual style (vanilla `extractBackground` + `isInGameUi`) across all screens, and implement ctrl+scroll plane nudging with face highlight when holding a SpecOrigin block.

**Architecture:** Server-side nudge/resize handlers receive C2S packets and clamp the BoundingBox. A client-side tick handler detects the hovered face via slab-method ray-AABB intersection and writes `currentHoveredFace`. A `ScrollWheelHandlerMixin` intercepts ctrl+scroll to send the nudge packet. The block entity renderer reads the hovered face to draw a translucent face quad.

**Tech Stack:** Kotlin + Java (mixins), Fabric API 0.146+26.x, MC 26.1.2, `RenderTypes.debugQuads()` for face highlight, `CycleButton` for mode toggle, `extractBackground()` from `Screen`.

---

## File Map

| Action | Path |
|--------|------|
| Create | `src/main/kotlin/com/breadmoirai/redstonespecs/network/BoundsNudge.kt` |
| Create | `src/client/kotlin/com/breadmoirai/redstonespecs/client/SpecBoundsInteraction.kt` |
| Create | `src/client/kotlin/com/breadmoirai/redstonespecs/client/screen/SpecBoundsScreen.kt` |
| Create | `src/client/java/com/breadmoirai/redstonespecs/mixin/client/ScrollWheelHandlerMixin.java` |
| Create | `src/test/kotlin/com/breadmoirai/redstonespecs/data/BoundsNudgeTest.kt` |
| Modify | `src/main/kotlin/com/breadmoirai/redstonespecs/network/Packets.kt` |
| Modify | `src/main/kotlin/com/breadmoirai/redstonespecs/network/NetworkRegistry.kt` |
| Modify | `src/client/kotlin/com/breadmoirai/redstonespecs/client/render/SpecOriginBoundsRenderer.kt` |
| Modify | `src/client/kotlin/com/breadmoirai/redstonespecs/client/render/HudOverlayRenderer.kt` |
| Modify | `src/client/kotlin/com/breadmoirai/redstonespecs/client/RedstonespecsClient.kt` |
| Modify | `src/client/kotlin/com/breadmoirai/redstonespecs/client/screen/SpecOverviewScreen.kt` |
| Modify | `src/client/kotlin/com/breadmoirai/redstonespecs/client/screen/SpecEditorScreen.kt` |
| Modify | `src/client/resources/redstonespecs.client.mixins.json` |

---

## Task 1: BoundsNudge function + unit test

**Files:**
- Create: `src/main/kotlin/com/breadmoirai/redstonespecs/network/BoundsNudge.kt`
- Create: `src/test/kotlin/com/breadmoirai/redstonespecs/data/BoundsNudgeTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
// src/test/kotlin/com/breadmoirai/redstonespecs/data/BoundsNudgeTest.kt
package com.breadmoirai.redstonespecs.data

import com.breadmoirai.redstonespecs.network.nudgeBounds
import net.minecraft.world.level.levelgen.structure.BoundingBox
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class BoundsNudgeTest {

    private fun box() = BoundingBox(-4, -1, -4, 4, 3, 4)

    @Test fun `nudge max-X forward expands east`() {
        val result = nudgeBounds(box(), axis = 0, isMax = true, delta = 1)
        assertEquals(5, result.maxX())
        assertEquals(-4, result.minX())
    }

    @Test fun `nudge min-X forward shrinks west side`() {
        val result = nudgeBounds(box(), axis = 0, isMax = false, delta = 1)
        assertEquals(-3, result.minX())
        assertEquals(4, result.maxX())
    }

    @Test fun `nudge max-X cannot go below minX`() {
        val b = BoundingBox(0, 0, 0, 0, 0, 0)
        val result = nudgeBounds(b, axis = 0, isMax = true, delta = -5)
        assertEquals(0, result.maxX())  // clamped to minX
    }

    @Test fun `nudge min-X cannot exceed maxX`() {
        val b = BoundingBox(0, 0, 0, 0, 0, 0)
        val result = nudgeBounds(b, axis = 0, isMax = false, delta = 5)
        assertEquals(0, result.minX())  // clamped to maxX
    }

    @Test fun `nudge Y axis affects only Y coords`() {
        val result = nudgeBounds(box(), axis = 1, isMax = true, delta = 2)
        assertEquals(5, result.maxY())
        assertEquals(-1, result.minY())
        assertEquals(-4, result.minX()); assertEquals(4, result.maxX())
        assertEquals(-4, result.minZ()); assertEquals(4, result.maxZ())
    }

    @Test fun `nudge Z axis affects only Z coords`() {
        val result = nudgeBounds(box(), axis = 2, isMax = false, delta = -1)
        assertEquals(-5, result.minZ())
        assertEquals(4, result.maxZ())
    }

    @Test fun `unknown axis returns unchanged bounds`() {
        val b = box()
        val result = nudgeBounds(b, axis = 99, isMax = true, delta = 1)
        assertEquals(b, result)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
./gradlew test --tests "com.breadmoirai.redstonespecs.data.BoundsNudgeTest" 2>&1 | tail -20
```

Expected: compilation failure — `nudgeBounds` not defined yet.

- [ ] **Step 3: Create `BoundsNudge.kt`**

```kotlin
// src/main/kotlin/com/breadmoirai/redstonespecs/network/BoundsNudge.kt
package com.breadmoirai.redstonespecs.network

import net.minecraft.world.level.levelgen.structure.BoundingBox

fun nudgeBounds(b: BoundingBox, axis: Int, isMax: Boolean, delta: Int): BoundingBox = when (axis) {
    0 -> if (isMax)
            BoundingBox(b.minX(), b.minY(), b.minZ(), (b.maxX() + delta).coerceAtLeast(b.minX()), b.maxY(), b.maxZ())
         else
            BoundingBox((b.minX() + delta).coerceAtMost(b.maxX()), b.minY(), b.minZ(), b.maxX(), b.maxY(), b.maxZ())
    1 -> if (isMax)
            BoundingBox(b.minX(), b.minY(), b.minZ(), b.maxX(), (b.maxY() + delta).coerceAtLeast(b.minY()), b.maxZ())
         else
            BoundingBox(b.minX(), (b.minY() + delta).coerceAtMost(b.maxY()), b.minZ(), b.maxX(), b.maxY(), b.maxZ())
    2 -> if (isMax)
            BoundingBox(b.minX(), b.minY(), b.minZ(), b.maxX(), b.maxY(), (b.maxZ() + delta).coerceAtLeast(b.minZ()))
         else
            BoundingBox(b.minX(), b.minY(), (b.minZ() + delta).coerceAtMost(b.maxZ()), b.maxX(), b.maxY(), b.maxZ())
    else -> b
}
```

- [ ] **Step 4: Run test to verify it passes**

```bash
./gradlew test --tests "com.breadmoirai.redstonespecs.data.BoundsNudgeTest" 2>&1 | tail -10
```

Expected: `BUILD SUCCESSFUL`, all 7 tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/breadmoirai/redstonespecs/network/BoundsNudge.kt \
        src/test/kotlin/com/breadmoirai/redstonespecs/data/BoundsNudgeTest.kt
git commit -m "feat: add nudgeBounds utility with clamping + unit tests"
```

---

## Task 2: New packets + server handlers

**Files:**
- Modify: `src/main/kotlin/com/breadmoirai/redstonespecs/network/Packets.kt`
- Modify: `src/main/kotlin/com/breadmoirai/redstonespecs/network/NetworkRegistry.kt`

- [ ] **Step 1: Add two new payload types to `Packets.kt`**

Add these two data classes at the end of `Packets.kt`, after the existing `UndoC2SPayload`. Also add `import net.minecraft.world.level.levelgen.structure.BoundingBox` at the top with the other imports.

```kotlin
data class ResizeBoundsC2SPayload(
    val originPos: BlockPos,
    val bounds: BoundingBox,
) : CustomPacketPayload {
    companion object {
        val TYPE = CustomPacketPayload.Type<ResizeBoundsC2SPayload>(
            Identifier.fromNamespaceAndPath("redstonespecs", "resize_bounds")
        )
        val STREAM_CODEC: StreamCodec<ByteBuf, ResizeBoundsC2SPayload> = StreamCodec.composite(
            BlockPos.STREAM_CODEC, ResizeBoundsC2SPayload::originPos,
            ByteBufCodecs.fromCodec(BoundingBox.CODEC), ResizeBoundsC2SPayload::bounds,
            ::ResizeBoundsC2SPayload,
        )
    }
    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE
}

data class NudgeSpecBoundsC2SPayload(
    val originPos: BlockPos,
    val axis: Int,
    val isMax: Boolean,
    val delta: Int,
) : CustomPacketPayload {
    companion object {
        val TYPE = CustomPacketPayload.Type<NudgeSpecBoundsC2SPayload>(
            Identifier.fromNamespaceAndPath("redstonespecs", "nudge_spec_bounds")
        )
        val STREAM_CODEC: StreamCodec<ByteBuf, NudgeSpecBoundsC2SPayload> = StreamCodec.composite(
            BlockPos.STREAM_CODEC, NudgeSpecBoundsC2SPayload::originPos,
            ByteBufCodecs.VAR_INT, NudgeSpecBoundsC2SPayload::axis,
            ByteBufCodecs.BOOL, NudgeSpecBoundsC2SPayload::isMax,
            ByteBufCodecs.VAR_INT, NudgeSpecBoundsC2SPayload::delta,
            ::NudgeSpecBoundsC2SPayload,
        )
    }
    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE
}
```

- [ ] **Step 2: Register packets and add server handlers in `NetworkRegistry.kt`**

In `registerNetworking()`, after the existing C2S registrations block, add:

```kotlin
    PayloadTypeRegistry.serverboundPlay().register(ResizeBoundsC2SPayload.TYPE, ResizeBoundsC2SPayload.STREAM_CODEC)
    PayloadTypeRegistry.serverboundPlay().register(NudgeSpecBoundsC2SPayload.TYPE, NudgeSpecBoundsC2SPayload.STREAM_CODEC)
```

After the existing C2S handlers, add:

```kotlin
    ServerPlayNetworking.registerGlobalReceiver(ResizeBoundsC2SPayload.TYPE) { payload, context ->
        context.server().execute {
            LOGGER.debug("[NetworkRegistry#resizeBounds] originPos={} bounds={}", payload.originPos, payload.bounds)
            val be = context.player().level().getBlockEntity(payload.originPos) as? SpecOriginBlockEntity ?: return@execute
            val spec = be.spec ?: return@execute
            be.setSpec(spec.copy(bounds = payload.bounds))
        }
    }

    ServerPlayNetworking.registerGlobalReceiver(NudgeSpecBoundsC2SPayload.TYPE) { payload, context ->
        context.server().execute {
            LOGGER.debug("[NetworkRegistry#nudgeSpecBounds] originPos={} axis={} isMax={} delta={}", payload.originPos, payload.axis, payload.isMax, payload.delta)
            val be = context.player().level().getBlockEntity(payload.originPos) as? SpecOriginBlockEntity ?: return@execute
            val spec = be.spec ?: return@execute
            be.setSpec(spec.copy(bounds = nudgeBounds(spec.bounds, payload.axis, payload.isMax, payload.delta)))
        }
    }
```

Also add the import at the top of `NetworkRegistry.kt`:
```kotlin
import com.breadmoirai.redstonespecs.network.nudgeBounds
```

- [ ] **Step 3: Verify compilation**

```bash
./gradlew compileKotlin 2>&1 | tail -20
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/com/breadmoirai/redstonespecs/network/Packets.kt \
        src/main/kotlin/com/breadmoirai/redstonespecs/network/NetworkRegistry.kt
git commit -m "feat: add ResizeBounds and NudgeSpecBounds packets + server handlers"
```

---

## Task 3: `SpecBoundsInteraction.kt` — shared face detection state

**Files:**
- Create: `src/client/kotlin/com/breadmoirai/redstonespecs/client/SpecBoundsInteraction.kt`

- [ ] **Step 1: Create `SpecBoundsInteraction.kt`**

```kotlin
// src/client/kotlin/com/breadmoirai/redstonespecs/client/SpecBoundsInteraction.kt
package com.breadmoirai.redstonespecs.client

import com.breadmoirai.redstonespecs.network.NudgeSpecBoundsC2SPayload
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.minecraft.core.BlockPos

data class HoveredFace(val originPos: BlockPos, val axis: Int, val isMax: Boolean)

var currentHoveredFace: HoveredFace? = null

// Written by the tick handler; read by the renderer's extractRenderState.
// Both run on the main thread in 26.x — no synchronization needed.

data class FaceHit(val axis: Int, val isMax: Boolean, val t: Double)

/**
 * Slab-method ray-AABB intersection returning the hovered face.
 * All coordinates in the same space. Returns null if the ray misses the box
 * or the box is entirely behind the ray origin.
 */
fun findHoveredFace(
    ox: Double, oy: Double, oz: Double,   // ray origin
    dx: Double, dy: Double, dz: Double,   // ray direction (need not be normalized)
    minX: Double, minY: Double, minZ: Double,
    maxX: Double, maxY: Double, maxZ: Double,
): FaceHit? {
    val invDx = if (dx != 0.0) 1.0 / dx else if (ox < minX || ox > maxX) return null else Double.POSITIVE_INFINITY
    val invDy = if (dy != 0.0) 1.0 / dy else if (oy < minY || oy > maxY) return null else Double.POSITIVE_INFINITY
    val invDz = if (dz != 0.0) 1.0 / dz else if (oz < minZ || oz > maxZ) return null else Double.POSITIVE_INFINITY

    var txMin = (minX - ox) * invDx; var txMax = (maxX - ox) * invDx
    val xFromMax = txMin > txMax
    if (xFromMax) { val t = txMin; txMin = txMax; txMax = t }

    var tyMin = (minY - oy) * invDy; var tyMax = (maxY - oy) * invDy
    val yFromMax = tyMin > tyMax
    if (yFromMax) { val t = tyMin; tyMin = tyMax; tyMax = t }

    var tzMin = (minZ - oz) * invDz; var tzMax = (maxZ - oz) * invDz
    val zFromMax = tzMin > tzMax
    if (zFromMax) { val t = tzMin; tzMin = tzMax; tzMax = t }

    val tEnter = maxOf(txMin, tyMin, tzMin)
    val tExit = minOf(txMax, tyMax, tzMax)

    if (tExit < 0.0 || tEnter > tExit) return null

    return if (tEnter > 0.0) {
        when {
            tEnter == txMin -> FaceHit(0, xFromMax, tEnter)
            tEnter == tyMin -> FaceHit(1, yFromMax, tEnter)
            else            -> FaceHit(2, zFromMax, tEnter)
        }
    } else {
        when {
            tExit == txMax -> FaceHit(0, !xFromMax, tExit)
            tExit == tyMax -> FaceHit(1, !yFromMax, tExit)
            else           -> FaceHit(2, !zFromMax, tExit)
        }
    }
}

fun handleCtrlScroll(yOffset: Double) {
    val face = currentHoveredFace ?: return
    val delta = if (yOffset > 0) 1 else -1
    ClientPlayNetworking.send(NudgeSpecBoundsC2SPayload(face.originPos, face.axis, face.isMax, delta))
}
```

- [ ] **Step 2: Verify compilation**

```bash
./gradlew compileClientKotlin 2>&1 | tail -20
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Commit**

```bash
git add src/client/kotlin/com/breadmoirai/redstonespecs/client/SpecBoundsInteraction.kt
git commit -m "feat: add SpecBoundsInteraction — HoveredFace state and slab-method ray-AABB detection"
```

---

## Task 4: `ScrollWheelHandlerMixin` — intercept ctrl+scroll

**Files:**
- Create: `src/client/java/com/breadmoirai/redstonespecs/mixin/client/ScrollWheelHandlerMixin.java`
- Modify: `src/client/resources/redstonespecs.client.mixins.json`

- [ ] **Step 1: Create the mixin directory and Java file**

```bash
mkdir -p src/client/java/com/breadmoirai/redstonespecs/mixin/client
```

```java
// src/client/java/com/breadmoirai/redstonespecs/mixin/client/ScrollWheelHandlerMixin.java
package com.breadmoirai.redstonespecs.mixin.client;

import com.breadmoirai.redstonespecs.client.SpecBoundsInteractionKt;
import net.minecraft.client.ScrollWheelHandler;
import net.minecraft.client.gui.screens.Screen;
import org.joml.Vector2i;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ScrollWheelHandler.class)
abstract class ScrollWheelHandlerMixin {

    @Inject(
        method = "onMouseScroll(DD)Lorg/joml/Vector2i;",
        at = @At("HEAD"),
        cancellable = true
    )
    private void redstonespecs$interceptCtrlScroll(double xOffset, double yOffset, CallbackInfoReturnable<Vector2i> cir) {
        if (yOffset == 0.0) return;
        if (!Screen.hasControlDown()) return;
        if (SpecBoundsInteractionKt.getCurrentHoveredFace() == null) return;
        SpecBoundsInteractionKt.handleCtrlScroll(yOffset);
        cir.setReturnValue(new Vector2i(0, 0));
    }
}
```

- [ ] **Step 2: Register the mixin in `redstonespecs.client.mixins.json`**

Replace the empty `"client": []` array with:

```json
{
  "required": true,
  "minVersion": "0.8",
  "package": "com.breadmoirai.redstonespecs.mixin.client",
  "compatibilityLevel": "JAVA_25",
  "client": [
    "ScrollWheelHandlerMixin"
  ],
  "injectors": {
    "defaultRequire": 1
  },
  "overwrites": {
    "requireAnnotations": true
  }
}
```

- [ ] **Step 3: Verify compilation**

```bash
./gradlew compileClientJava 2>&1 | tail -20
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: Commit**

```bash
git add src/client/java/com/breadmoirai/redstonespecs/mixin/client/ScrollWheelHandlerMixin.java \
        src/client/resources/redstonespecs.client.mixins.json
git commit -m "feat: add ScrollWheelHandlerMixin to intercept ctrl+scroll for bounds nudging"
```

---

## Task 5: Face detection in `HudOverlayRenderer` + update `RedstonespecsClient`

**Files:**
- Modify: `src/client/kotlin/com/breadmoirai/redstonespecs/client/render/HudOverlayRenderer.kt`
- Modify: `src/client/kotlin/com/breadmoirai/redstonespecs/client/RedstonespecsClient.kt`

- [ ] **Step 1: Add face detection to `HudOverlayRenderer`'s tick handler**

In `HudOverlayRenderer.kt`, add these imports at the top:

```kotlin
import com.breadmoirai.redstonespecs.client.FaceHit
import com.breadmoirai.redstonespecs.client.HoveredFace
import com.breadmoirai.redstonespecs.client.currentHoveredFace
import com.breadmoirai.redstonespecs.client.findHoveredFace
import com.breadmoirai.redstonespecs.ModRegistries
import net.minecraft.world.item.BlockItem
```

Inside the existing `ClientTickEvents.END_CLIENT_TICK.register { mc ->` handler, add the face detection block **before** the existing keybinding checks:

```kotlin
    ClientTickEvents.END_CLIENT_TICK.register { mc ->
        if (mc.screen != null) {
            currentHoveredFace = null
            return@register
        }
        val level = mc.level ?: run { currentHoveredFace = null; return@register }

        // Face detection: active when holding the SpecOrigin block item
        val player = mc.player
        val holdingSpecOrigin = player != null && (
            (player.mainHandItem.item as? BlockItem)?.block == ModRegistries.SPEC_ORIGIN_BLOCK ||
            (player.offhandItem.item as? BlockItem)?.block == ModRegistries.SPEC_ORIGIN_BLOCK
        )

        if (holdingSpecOrigin && player != null) {
            val eyePos = player.getEyePosition(1.0f)
            val lookVec = player.getLookAngle()
            val maxReach = 64.0  // check all BEs up to 64 blocks away

            var bestT = Double.MAX_VALUE
            var bestFace: HoveredFace? = null

            for (be in SpecOriginBlockEntity.allFor(level)) {
                val spec = be.spec ?: continue
                val b = spec.bounds
                val bpX = be.blockPos.x.toDouble()
                val bpY = be.blockPos.y.toDouble()
                val bpZ = be.blockPos.z.toDouble()
                // World-space AABB: min corner is blockPos + bounds.min, max is blockPos + bounds.max + 1
                val hit: FaceHit = findHoveredFace(
                    eyePos.x - bpX, eyePos.y - bpY, eyePos.z - bpZ,
                    lookVec.x, lookVec.y, lookVec.z,
                    b.minX().toDouble(), b.minY().toDouble(), b.minZ().toDouble(),
                    b.maxX().toDouble() + 1.0, b.maxY().toDouble() + 1.0, b.maxZ().toDouble() + 1.0,
                ) ?: continue
                if (hit.t < bestT && hit.t < maxReach) {
                    bestT = hit.t
                    bestFace = HoveredFace(be.blockPos, hit.axis, hit.isMax)
                }
            }
            currentHoveredFace = bestFace
        } else {
            currentHoveredFace = null
        }

        // Existing keybinding checks (keep these unchanged below)
        val hitPos = (mc.hitResult as? BlockHitResult)?.blockPos ?: return@register
        val be = SpecOriginBlockEntity.findFor(level, hitPos)
            ?: (level.getBlockEntity(hitPos) as? SpecOriginBlockEntity)
            ?: return@register

        if (keyCycleForward.consumeClick()) {
            ClientPlayNetworking.send(CycleSpecCaseC2SPayload(be.blockPos, true))
        }
        if (keyCycleBackward.consumeClick()) {
            ClientPlayNetworking.send(CycleSpecCaseC2SPayload(be.blockPos, false))
        }
    }
```

- [ ] **Step 2: Verify compilation**

```bash
./gradlew compileClientKotlin 2>&1 | tail -20
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Commit**

```bash
git add src/client/kotlin/com/breadmoirai/redstonespecs/client/render/HudOverlayRenderer.kt
git commit -m "feat: detect hovered spec bounds face on client tick when holding SpecOrigin item"
```

---

## Task 6: Update renderer — face highlight quad

**Files:**
- Modify: `src/client/kotlin/com/breadmoirai/redstonespecs/client/render/SpecOriginBoundsRenderer.kt`

- [ ] **Step 1: Add `hoveredFace` to `SpecOriginRenderState` and populate it in `extractRenderState`**

In `SpecOriginRenderState`, add the field:

```kotlin
class SpecOriginRenderState : BlockEntityRenderState() {
    var bounds: BoundingBox? = null
    var activeEntries: List<SpecEntry> = emptyList()
    var hoveredFace: HoveredFace? = null  // null if no face is currently targeted
}
```

In `extractRenderState`, add after `state.bounds = ...`:

```kotlin
state.hoveredFace = if (entity.blockPos == currentHoveredFace?.originPos) currentHoveredFace else null
```

Add the required imports:

```kotlin
import com.breadmoirai.redstonespecs.client.HoveredFace
import com.breadmoirai.redstonespecs.client.currentHoveredFace
```

- [ ] **Step 2: Draw the face highlight quad in `submit()`**

At the end of the `submit()` method, after drawing the entry boxes, add:

```kotlin
        state.hoveredFace?.let { face ->
            val b = state.bounds ?: return@let
            val bufferFace = bufferSource.getBuffer(RenderTypes.debugQuads())
            drawFaceHighlight(bufferFace, matrix, b, face)
        }
```

Add the private method `drawFaceHighlight` to the class:

```kotlin
    private fun drawFaceHighlight(
        buffer: VertexConsumer,
        matrix: Matrix4f,
        bounds: BoundingBox,
        face: HoveredFace,
    ) {
        val x1 = bounds.minX().toFloat()
        val y1 = bounds.minY().toFloat()
        val z1 = bounds.minZ().toFloat()
        val x2 = bounds.maxX().toFloat() + 1f
        val y2 = bounds.maxY().toFloat() + 1f
        val z2 = bounds.maxZ().toFloat() + 1f

        val r: Float; val g: Float; val b: Float; val a = 0.3f
        when (face.axis) {
            0 -> { r = 1f; g = 0.27f; b = 0.27f }  // red — X axis
            1 -> { r = 0.27f; g = 1f; b = 0.27f }   // green — Y axis
            else -> { r = 0.27f; g = 0.27f; b = 1f } // blue — Z axis
        }

        // Emit 4 vertices (one quad) for the appropriate face.
        // Winding is counter-clockwise when viewed from outside the box.
        when {
            face.axis == 0 && face.isMax -> {  // +X face
                buffer.addVertex(matrix, x2, y1, z1).setColor(r, g, b, a)
                buffer.addVertex(matrix, x2, y1, z2).setColor(r, g, b, a)
                buffer.addVertex(matrix, x2, y2, z2).setColor(r, g, b, a)
                buffer.addVertex(matrix, x2, y2, z1).setColor(r, g, b, a)
            }
            face.axis == 0 && !face.isMax -> {  // -X face
                buffer.addVertex(matrix, x1, y1, z2).setColor(r, g, b, a)
                buffer.addVertex(matrix, x1, y1, z1).setColor(r, g, b, a)
                buffer.addVertex(matrix, x1, y2, z1).setColor(r, g, b, a)
                buffer.addVertex(matrix, x1, y2, z2).setColor(r, g, b, a)
            }
            face.axis == 1 && face.isMax -> {  // +Y face
                buffer.addVertex(matrix, x1, y2, z1).setColor(r, g, b, a)
                buffer.addVertex(matrix, x1, y2, z2).setColor(r, g, b, a)
                buffer.addVertex(matrix, x2, y2, z2).setColor(r, g, b, a)
                buffer.addVertex(matrix, x2, y2, z1).setColor(r, g, b, a)
            }
            face.axis == 1 && !face.isMax -> {  // -Y face
                buffer.addVertex(matrix, x1, y1, z2).setColor(r, g, b, a)
                buffer.addVertex(matrix, x1, y1, z1).setColor(r, g, b, a)
                buffer.addVertex(matrix, x2, y1, z1).setColor(r, g, b, a)
                buffer.addVertex(matrix, x2, y1, z2).setColor(r, g, b, a)
            }
            face.axis == 2 && face.isMax -> {  // +Z face
                buffer.addVertex(matrix, x2, y1, z2).setColor(r, g, b, a)
                buffer.addVertex(matrix, x1, y1, z2).setColor(r, g, b, a)
                buffer.addVertex(matrix, x1, y2, z2).setColor(r, g, b, a)
                buffer.addVertex(matrix, x2, y2, z2).setColor(r, g, b, a)
            }
            else -> {  // -Z face
                buffer.addVertex(matrix, x1, y1, z1).setColor(r, g, b, a)
                buffer.addVertex(matrix, x2, y1, z1).setColor(r, g, b, a)
                buffer.addVertex(matrix, x2, y2, z1).setColor(r, g, b, a)
                buffer.addVertex(matrix, x1, y2, z1).setColor(r, g, b, a)
            }
        }
    }
```

- [ ] **Step 3: Verify compilation**

```bash
./gradlew compileClientKotlin 2>&1 | tail -20
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: Commit**

```bash
git add src/client/kotlin/com/breadmoirai/redstonespecs/client/render/SpecOriginBoundsRenderer.kt
git commit -m "feat: render translucent face highlight quad on hovered spec bounds face"
```

---

## Task 7: `SpecBoundsScreen`

**Files:**
- Create: `src/client/kotlin/com/breadmoirai/redstonespecs/client/screen/SpecBoundsScreen.kt`

- [ ] **Step 1: Create the full screen**

```kotlin
// src/client/kotlin/com/breadmoirai/redstonespecs/client/screen/SpecBoundsScreen.kt
package com.breadmoirai.redstonespecs.client.screen

import com.breadmoirai.redstonespecs.block.SpecOriginBlockEntity
import com.breadmoirai.redstonespecs.network.ResizeBoundsC2SPayload
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.CycleButton
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.screens.Screen
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.CommonComponents
import net.minecraft.network.chat.Component
import net.minecraft.world.level.levelgen.structure.BoundingBox

class SpecBoundsScreen(private val originPos: BlockPos) :
    Screen(Component.translatable("screen.redstonespecs.spec_bounds")) {

    private enum class DisplayMode { OFFSET_SIZE, MIN_MAX }

    private val panelW = 252
    private val panelH = 172
    private val panelX get() = (width - panelW) / 2
    private val panelY get() = (height - panelH) / 2

    private var displayMode = DisplayMode.OFFSET_SIZE

    private var box1x: EditBox? = null
    private var box1y: EditBox? = null
    private var box1z: EditBox? = null
    private var box2x: EditBox? = null
    private var box2y: EditBox? = null
    private var box2z: EditBox? = null

    override fun isInGameUi(): Boolean = true

    override fun init() {
        super.init()
        val x = panelX
        val y = panelY
        val bounds = getBounds() ?: BoundingBox(-4, -1, -4, 4, 3, 4)

        // Mode toggle — preserves displayMode across rebuildWidgets()
        addRenderableWidget(
            CycleButton.builder<DisplayMode>(
                { mode -> Component.literal(if (mode == DisplayMode.OFFSET_SIZE) "Offset+Size" else "Min+Max") },
                displayMode,
            ).withValues(*DisplayMode.entries.toTypedArray())
                .create(x + panelW - 92, y + 14, 88, 14, Component.empty()) { _, value ->
                    displayMode = value
                    rebuildWidgets()
                }
        )

        val (v1x, v1y, v1z, v2x, v2y, v2z) = toDisplayValues(bounds)

        // First triple (Offset or Min)
        box1x = addBox(x + 8,   y + 56, v1x)
        box1y = addBox(x + 88,  y + 56, v1y)
        box1z = addBox(x + 168, y + 56, v1z)

        // Second triple (Size or Max)
        box2x = addBox(x + 8,   y + 110, v2x)
        box2y = addBox(x + 88,  y + 110, v2y)
        box2z = addBox(x + 168, y + 110, v2z)

        addRenderableWidget(
            Button.builder(Component.literal("Save")) { save() }
                .bounds(x + 8, y + panelH - 24, 60, 18).build()
        )
        addRenderableWidget(
            Button.builder(CommonComponents.GUI_CANCEL) { onClose() }
                .bounds(x + panelW - 68, y + panelH - 24, 60, 18).build()
        )
    }

    private fun addBox(bx: Int, by: Int, value: Int): EditBox =
        EditBox(font, bx, by, 72, 14, Component.empty()).also {
            it.value = value.toString()
            addRenderableWidget(it)
        }

    private data class DisplayValues(
        val v1x: Int, val v1y: Int, val v1z: Int,
        val v2x: Int, val v2y: Int, val v2z: Int,
    )

    private fun toDisplayValues(b: BoundingBox): DisplayValues = if (displayMode == DisplayMode.OFFSET_SIZE)
        DisplayValues(b.minX(), b.minY(), b.minZ(), b.maxX() - b.minX() + 1, b.maxY() - b.minY() + 1, b.maxZ() - b.minZ() + 1)
    else
        DisplayValues(b.minX(), b.minY(), b.minZ(), b.maxX(), b.maxY(), b.maxZ())

    private fun save() {
        val v1x = box1x?.value?.toIntOrNull() ?: return
        val v1y = box1y?.value?.toIntOrNull() ?: return
        val v1z = box1z?.value?.toIntOrNull() ?: return
        val v2x = box2x?.value?.toIntOrNull() ?: return
        val v2y = box2y?.value?.toIntOrNull() ?: return
        val v2z = box2z?.value?.toIntOrNull() ?: return

        val newBounds = if (displayMode == DisplayMode.OFFSET_SIZE) {
            BoundingBox(
                v1x, v1y, v1z,
                v1x + v2x.coerceAtLeast(1) - 1,
                v1y + v2y.coerceAtLeast(1) - 1,
                v1z + v2z.coerceAtLeast(1) - 1,
            )
        } else {
            BoundingBox(
                minOf(v1x, v2x), minOf(v1y, v2y), minOf(v1z, v2z),
                maxOf(v1x, v2x), maxOf(v1y, v2y), maxOf(v1z, v2z),
            )
        }

        ClientPlayNetworking.send(ResizeBoundsC2SPayload(originPos, newBounds))
        onClose()
    }

    override fun extractRenderState(
        extractor: GuiGraphicsExtractor,
        mouseX: Int,
        mouseY: Int,
        partialTick: Float,
    ) {
        super.extractBackground(extractor, mouseX, mouseY, partialTick)
        super.extractRenderState(extractor, mouseX, mouseY, partialTick)

        val x = panelX
        val y = panelY
        extractor.fill(x, y, x + panelW, y + panelH, 0xB0101010.toInt())
        extractor.centeredText(font, title, x + panelW / 2, y + 6, 0xFFFFFF)

        val (label1, label2) = if (displayMode == DisplayMode.OFFSET_SIZE) "Offset" to "Size" else "Min" to "Max"

        extractor.text(font, Component.literal(label1), x + 8, y + 38, 0x888888)
        extractor.text(font, Component.literal("X"), x + 8,   y + 44, 0x555555)
        extractor.text(font, Component.literal("Y"), x + 88,  y + 44, 0x555555)
        extractor.text(font, Component.literal("Z"), x + 168, y + 44, 0x555555)

        extractor.text(font, Component.literal(label2), x + 8, y + 92, 0x888888)
        extractor.text(font, Component.literal("X"), x + 8,   y + 98, 0x555555)
        extractor.text(font, Component.literal("Y"), x + 88,  y + 98, 0x555555)
        extractor.text(font, Component.literal("Z"), x + 168, y + 98, 0x555555)
    }

    override fun isPauseScreen(): Boolean = false

    private fun getBounds() =
        (minecraft?.level?.getBlockEntity(originPos) as? SpecOriginBlockEntity)?.spec?.bounds
}
```

- [ ] **Step 2: Verify compilation**

```bash
./gradlew compileClientKotlin 2>&1 | tail -20
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Commit**

```bash
git add src/client/kotlin/com/breadmoirai/redstonespecs/client/screen/SpecBoundsScreen.kt
git commit -m "feat: add SpecBoundsScreen with Offset+Size / Min+Max toggle"
```

---

## Task 8: Visual style + Bounds button for `SpecOverviewScreen`

**Files:**
- Modify: `src/client/kotlin/com/breadmoirai/redstonespecs/client/screen/SpecOverviewScreen.kt`

- [ ] **Step 1: Apply visual style and add "Bounds…" button**

Replace the entire `SpecOverviewScreen.kt` content with the updated version below. The key changes are: `isInGameUi()`, `extractBackground` call, section labels replacing inline text, reduced overlay opacity, and the "Bounds…" button.

```kotlin
package com.breadmoirai.redstonespecs.client.screen

import com.breadmoirai.redstonespecs.block.SpecOriginBlockEntity
import com.breadmoirai.redstonespecs.network.AddSpecCaseC2SPayload
import com.breadmoirai.redstonespecs.network.RemoveSpecCaseC2SPayload
import com.breadmoirai.redstonespecs.network.RenameSpecC2SPayload
import com.breadmoirai.redstonespecs.network.ResetSpecC2SPayload
import com.breadmoirai.redstonespecs.network.RunSpecC2SPayload
import com.breadmoirai.redstonespecs.network.SelectSpecCaseC2SPayload
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.screens.Screen
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.CommonComponents
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.common.custom.CustomPacketPayload

class SpecOverviewScreen(val originPos: BlockPos) :
    Screen(Component.translatable("screen.redstonespecs.spec_overview")) {

    private val panelW = 320
    private val panelH = 220
    private val panelX get() = (width - panelW) / 2
    private val panelY get() = (height - panelH) / 2

    private var nameEditBox: EditBox? = null

    override fun isInGameUi(): Boolean = true

    override fun init() {
        super.init()
        val x = panelX
        val y = panelY
        val spec = getSpec()

        nameEditBox = EditBox(font, x + 58, y + 14, 200, 16, Component.literal("Spec name")).also { box ->
            box.value = spec?.name ?: ""
            addRenderableWidget(box)
        }

        // Spec case buttons (up to 7 cases shown)
        spec?.specCases?.forEachIndexed { i, case ->
            if (i >= 7) return@forEachIndexed
            addRenderableWidget(
                Button.builder(Component.literal(case.name)) {
                    sendPacket(SelectSpecCaseC2SPayload(originPos, i))
                }.bounds(x + 10, y + 42 + i * 22, 180, 18).build()
            )
        }

        // Run active
        addRenderableWidget(
            Button.builder(Component.literal("▶ Run")) {
                sendPacket(RunSpecC2SPayload(originPos, false))
            }.bounds(x + 10, y + panelH - 60, 58, 20).build()
        )
        // Run all
        addRenderableWidget(
            Button.builder(Component.literal("▶▶ All")) {
                sendPacket(RunSpecC2SPayload(originPos, true))
            }.bounds(x + 74, y + panelH - 60, 58, 20).build()
        )
        // Reset
        addRenderableWidget(
            Button.builder(Component.literal("↺ Reset")) {
                sendPacket(ResetSpecC2SPayload(originPos))
            }.bounds(x + 138, y + panelH - 60, 58, 20).build()
        )

        // Add case
        addRenderableWidget(
            Button.builder(Component.literal("+ Case")) {
                val size = getSpec()?.specCases?.size ?: 0
                sendPacket(AddSpecCaseC2SPayload(originPos, "Case ${size + 1}"))
            }.bounds(x + 10, y + panelH - 35, 70, 20).build()
        )
        // Remove active case
        addRenderableWidget(
            Button.builder(Component.literal("- Case")) {
                val index = getBe()?.activeSpecCaseIndex ?: return@builder
                sendPacket(RemoveSpecCaseC2SPayload(originPos, index))
            }.bounds(x + 86, y + panelH - 35, 70, 20).build()
        )

        // Bounds sub-screen
        addRenderableWidget(
            Button.builder(Component.literal("Bounds…")) {
                minecraft?.setScreen(SpecBoundsScreen(originPos))
            }.bounds(x + 162, y + panelH - 35, 62, 20).build()
        )

        // Close
        addRenderableWidget(
            Button.builder(CommonComponents.GUI_DONE) { onClose() }
                .bounds(x + panelW - 72, y + panelH - 35, 62, 20).build()
        )
    }

    override fun extractRenderState(
        extractor: GuiGraphicsExtractor,
        mouseX: Int,
        mouseY: Int,
        partialTick: Float,
    ) {
        super.extractBackground(extractor, mouseX, mouseY, partialTick)
        super.extractRenderState(extractor, mouseX, mouseY, partialTick)

        val x = panelX
        val y = panelY

        extractor.fill(x, y, x + panelW, y + panelH, 0xB0101010.toInt())
        extractor.centeredText(font, title, x + panelW / 2, y + 4, 0xFFFFFF)
        extractor.text(font, Component.literal("Name:"), x + 10, y + 17, 0x888888)

        val be = getBe()
        val spec = be?.spec
        if (spec == null) {
            extractor.centeredText(font, Component.literal("No spec loaded"), x + panelW / 2, y + 60, 0xFF4444)
            return
        }

        extractor.text(font, Component.literal("Cases:"), x + 10, y + 36, 0x888888)

        // Highlight active case
        val activeIndex = be.activeSpecCaseIndex
        if (activeIndex < spec.specCases.size) {
            val highlightY = y + 42 + activeIndex * 22
            extractor.fill(x + 9, highlightY - 1, x + 191, highlightY + 19, 0x44FFFFFF)
        }

        // Pass/fail indicators from last test result
        val testResult = be.lastTestResult
        spec.specCases.forEachIndexed { i, case ->
            if (i >= 7) return@forEachIndexed
            val caseResult = testResult?.results?.find { it.specCaseName == case.name }
            val (statusText, statusColor) = when {
                caseResult == null -> "○" to 0x888888
                caseResult.checks.all { it.pass } -> "✓" to 0x44FF88
                else -> "✗" to 0xFF4444
            }
            extractor.text(font, Component.literal(statusText), x + 196, y + 47 + i * 22, statusColor)
        }

        // Test result summary
        if (testResult != null) {
            val pass = testResult.results.count { r -> r.checks.all { it.pass } }
            val total = testResult.results.size
            val color = if (pass == total) 0x44FF88 else 0xFF6644
            extractor.text(
                font,
                Component.literal("Last: $pass/$total passed"),
                x + 10, y + panelH - 78, color,
            )
        }
    }

    override fun onClose() {
        val newName = nameEditBox?.value?.trim() ?: ""
        val currentName = getSpec()?.name ?: ""
        if (newName.isNotBlank() && newName != currentName) {
            sendPacket(RenameSpecC2SPayload(originPos, newName))
        }
        super.onClose()
    }

    override fun isPauseScreen(): Boolean = false

    private fun getBe() = minecraft?.level?.getBlockEntity(originPos) as? SpecOriginBlockEntity
    private fun getSpec() = getBe()?.spec
    private fun sendPacket(payload: CustomPacketPayload) = ClientPlayNetworking.send(payload)
}
```

- [ ] **Step 2: Verify compilation**

```bash
./gradlew compileClientKotlin 2>&1 | tail -20
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Commit**

```bash
git add src/client/kotlin/com/breadmoirai/redstonespecs/client/screen/SpecOverviewScreen.kt
git commit -m "feat: apply Structure Block visual style to SpecOverviewScreen, add Bounds… button"
```

---

## Task 9: Visual style for `SpecEditorScreen`

**Files:**
- Modify: `src/client/kotlin/com/breadmoirai/redstonespecs/client/screen/SpecEditorScreen.kt`

- [ ] **Step 1: Add `isInGameUi` and switch to `extractBackground`**

Add this override method to `SpecEditorScreen`:

```kotlin
    override fun isInGameUi(): Boolean = true
```

In `extractRenderState`, replace the opening `extractor.fill(...)` call with:

```kotlin
    override fun extractRenderState(
        extractor: GuiGraphicsExtractor,
        mouseX: Int,
        mouseY: Int,
        partialTick: Float,
    ) {
        super.extractBackground(extractor, mouseX, mouseY, partialTick)
        super.extractRenderState(extractor, mouseX, mouseY, partialTick)

        val x = panelX
        val y = panelY
        val entry = getEntry()

        extractor.fill(x, y, x + panelW, y + panelH, 0xB0101010.toInt())
        // ... rest of the method unchanged
```

The rest of `extractRenderState` stays exactly as-is — only the opening fill opacity changes from `0xCC` to `0xB0` and the two new calls above it.

- [ ] **Step 2: Verify compilation**

```bash
./gradlew compileClientKotlin 2>&1 | tail -20
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Run all unit tests to confirm nothing regressed**

```bash
./gradlew test 2>&1 | tail -20
```

Expected: `BUILD SUCCESSFUL`, all tests pass.

- [ ] **Step 4: Commit**

```bash
git add src/client/kotlin/com/breadmoirai/redstonespecs/client/screen/SpecEditorScreen.kt
git commit -m "feat: apply Structure Block visual style to SpecEditorScreen"
```

---

## Manual verification checklist (in-game)

After all tasks compile, launch the game (`./gradlew runClient`) and verify:

1. **Visual style** — Opening the spec origin block (right-click) shows the tiled stone `INWORLD_MENU_BACKGROUND` behind the dark panel on both `SpecOverviewScreen` and `SpecEditorScreen`.
2. **Bounds button** — "Bounds…" button opens `SpecBoundsScreen` without a server round-trip.
3. **Bounds screen modes** — Toggling the `CycleButton` switches between Offset+Size and Min+Max. Values recompute correctly (e.g., offset=(-4,-1,-4) + size=(9,5,9) → min=(-4,-1,-4) max=(4,3,4)).
4. **Save bounds** — Editing values and clicking Save updates the bounding box outline in-world.
5. **Face highlight** — Holding the SpecOrigin block item and looking at the bounding box shows a colored translucent quad on the targeted face (red=X, green=Y, blue=Z).
6. **Ctrl+scroll** — While holding the item and aiming at a face, ctrl+scroll expands/contracts the spec on that axis by 1 block per scroll tick.
7. **Clamping** — Ctrl+scrolling inward cannot collapse a dimension below 1 block.
