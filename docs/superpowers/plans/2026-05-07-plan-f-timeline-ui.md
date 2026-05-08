# Plan F — Timeline UI consumes diagnostic recording

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** When a player views the result of an engine run on a `RedstoneSpecRunnerBlock`, expose the `StateRecording` attached to the `TestResult` (Plan E) through a tick-by-tick timeline scrubber UI showing each output position's state at every tick.

**Architecture:** The client receives `TestResultS2CPayload` carrying an optional `StateRecording` (Plan E). `ClientNetworkHandler` stores it next to `TestResult`. A new screen `RunnerTimelineScreen` reads from a per-runner `ClientRunnerState` and renders a horizontal tick axis with a draggable cursor; below the cursor, the state of each declared output position at the cursor's tick.

**Tech Stack:** Vanilla MC `Screen` + `AbstractWidget`, the existing client networking handler, `StateRecordingView`.

**Spec reference:** Spec §"In-game run lifecycle" step 6 ("in-game timeline scrubber reads the diagnostic recording").

**Depends on:** Plans A, B, C, D, E.

---

## File structure (after this plan)

**New:**
- `src/client/kotlin/com/breadmoirai/redstonespecs/client/state/ClientRunnerState.kt` — caches latest `TestResult` and recording per runner-block position.
- `src/client/kotlin/com/breadmoirai/redstonespecs/client/screen/RunnerTimelineScreen.kt` — the scrubber screen.
- `src/client/kotlin/com/breadmoirai/redstonespecs/client/widget/TimelineSliderWidget.kt` — horizontal scrubber widget, slot for keyboard arrows.

**Modified:**
- `src/client/kotlin/com/breadmoirai/redstonespecs/client/network/ClientNetworkHandler.kt` — write the recording to `ClientRunnerState` when receiving `TestResultS2CPayload`.
- `src/main/kotlin/com/breadmoirai/redstonespecs/block/RedstoneSpecRunnerBlock.kt` — adds an interaction (e.g. shift-right-click) that opens the timeline screen.

---

## Task 1: `ClientRunnerState`

**Files:**
- Create: `src/client/kotlin/com/breadmoirai/redstonespecs/client/state/ClientRunnerState.kt`

- [ ] **Step 1: Implement**

```kotlin
package com.breadmoirai.redstonespecs.client.state

import com.breadmoirai.redstonespecs.data.TestResult
import net.minecraft.core.BlockPos
import java.util.concurrent.ConcurrentHashMap

/**
 * Client-side cache of the latest [TestResult] and its diagnostic recording per runner block.
 * Populated by [com.breadmoirai.redstonespecs.client.network.ClientNetworkHandler] on
 * TestResultS2CPayload receipt; read by RunnerTimelineScreen.
 */
object ClientRunnerState {
    private val byRunner = ConcurrentHashMap<BlockPos, TestResult>()

    fun put(runnerPos: BlockPos, result: TestResult) {
        byRunner[runnerPos] = result
    }

    fun get(runnerPos: BlockPos): TestResult? = byRunner[runnerPos]

    fun clear(runnerPos: BlockPos) { byRunner.remove(runnerPos) }
}
```

- [ ] **Step 2: Commit**

```bash
git add src/client/kotlin/com/breadmoirai/redstonespecs/client/state/ClientRunnerState.kt
git commit -m "feat(client): ClientRunnerState caches TestResult per runner block"
```

---

## Task 2: Wire `ClientNetworkHandler` to write into the cache

**Files:**
- Modify: `src/client/kotlin/com/breadmoirai/redstonespecs/client/network/ClientNetworkHandler.kt`

- [ ] **Step 1: Read the file**

```bash
cat src/client/kotlin/com/breadmoirai/redstonespecs/client/network/ClientNetworkHandler.kt
```

Find the `TestResultS2CPayload` handler.

- [ ] **Step 2: After the existing handler logic, add**

```kotlin
// Cache for the timeline screen.
com.breadmoirai.redstonespecs.client.state.ClientRunnerState.put(payload.originPos, payload.result)
```

- [ ] **Step 3: Build**

Run: `cmd.exe /c "./gradlew.bat :26.1:clientClasses"`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add src/client/kotlin/com/breadmoirai/redstonespecs/client/network/ClientNetworkHandler.kt
git commit -m "feat(client): cache TestResult in ClientRunnerState on payload receipt"
```

---

## Task 3: `TimelineSliderWidget`

**Files:**
- Create: `src/client/kotlin/com/breadmoirai/redstonespecs/client/widget/TimelineSliderWidget.kt`

- [ ] **Step 1: Implement**

```kotlin
package com.breadmoirai.redstonespecs.client.widget

import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.AbstractSliderButton
import net.minecraft.network.chat.Component

/**
 * Horizontal slider widget for scrubbing through ticks 0..[lifespan-1].
 *
 * @param onTickChanged invoked with the current tick whenever the slider moves.
 */
class TimelineSliderWidget(
    x: Int, y: Int, w: Int, h: Int,
    private val lifespan: Int,
    private val onTickChanged: (Int) -> Unit,
) : AbstractSliderButton(x, y, w, h, Component.literal("Tick 0"), 0.0) {

    /** Current tick in [0, lifespan). */
    var tick: Int = 0
        private set

    init { updateMessage() }

    override fun updateMessage() {
        message = Component.literal("Tick $tick / ${(lifespan - 1).coerceAtLeast(0)}")
    }

    override fun applyValue() {
        if (lifespan <= 1) { tick = 0; onTickChanged(tick); return }
        tick = (value * (lifespan - 1)).toInt().coerceIn(0, lifespan - 1)
        onTickChanged(tick)
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add src/client/kotlin/com/breadmoirai/redstonespecs/client/widget/TimelineSliderWidget.kt
git commit -m "feat(client): TimelineSliderWidget for tick scrubbing"
```

---

## Task 4: `RunnerTimelineScreen`

**Files:**
- Create: `src/client/kotlin/com/breadmoirai/redstonespecs/client/screen/RunnerTimelineScreen.kt`

- [ ] **Step 1: Implement the screen**

```kotlin
package com.breadmoirai.redstonespecs.client.screen

import com.breadmoirai.redstonespecs.client.state.ClientRunnerState
import com.breadmoirai.redstonespecs.client.widget.TimelineSliderWidget
import com.breadmoirai.redstonespecs.data.Phase
import com.breadmoirai.redstonespecs.data.SimTime
import com.breadmoirai.redstonespecs.data.TestResult
import com.breadmoirai.redstonespecs.runner.StateRecording
import com.breadmoirai.redstonespecs.runner.StateRecordingView
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.screens.Screen
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component

/** Opens for a specific runner block; reads its cached TestResult and renders a tick-by-tick view. */
class RunnerTimelineScreen(
    private val runnerPos: BlockPos,
) : Screen(Component.literal("Spec Timeline")) {

    private var lifespan: Int = 0
    private var view: StateRecordingView? = null
    private var displayedTick: Int = 0
    private var snapshotLines: List<String> = emptyList()

    override fun init() {
        super.init()
        val testResult: TestResult? = ClientRunnerState.get(runnerPos)
        val recording: StateRecording? = testResult?.recording
        if (recording == null) {
            // No recording — show a hint and nothing else.
            return
        }
        view = StateRecordingView.of(recording)
        // Lifespan is implied by the largest tick index in recording.changes (+1).
        lifespan = (recording.changes.maxOfOrNull { it.simTime.tick } ?: 0) + 1

        addRenderableWidget(TimelineSliderWidget(
            x = width / 2 - 100, y = height - 40, w = 200, h = 20,
            lifespan = lifespan,
        ) { tick ->
            displayedTick = tick
            recomputeSnapshotLines()
        })
        recomputeSnapshotLines()
    }

    private fun recomputeSnapshotLines() {
        val v = view ?: return
        // For each pos in the recording's initial snapshot, look up state at displayedTick (END_OF_TICK).
        // Render up to N lines.
        val anchor = SimTime(displayedTick, Phase.END_OF_TICK, Int.MAX_VALUE)
        // The recording exposes initial positions via initialSnapshot; iterate.
        val initial = (view as? StateRecordingView)?.let {
            // StateRecordingView doesn't expose its source; if we need it, also pass the recording
            // through the screen's constructor in a follow-up. For now, read from ClientRunnerState.
            ClientRunnerState.get(runnerPos)?.recording?.initialSnapshot ?: emptyMap()
        } ?: emptyMap()
        snapshotLines = initial.keys.take(20).map { pos ->
            val s = v.stateAt(pos, anchor)
            "$pos -> $s"
        }
    }

    override fun render(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.render(graphics, mouseX, mouseY, partialTick)
        graphics.drawCenteredString(font, title, width / 2, 8, 0xFFFFFFFF.toInt())
        if (view == null) {
            graphics.drawCenteredString(font, Component.literal("(no recording available)"),
                width / 2, height / 2, 0xFFFFFFFF.toInt())
            return
        }
        var y = 30
        for (line in snapshotLines) {
            graphics.drawString(font, line, 16, y, 0xFFFFFFFF.toInt())
            y += font.lineHeight + 2
        }
    }

    override fun isPauseScreen(): Boolean = false
}
```

- [ ] **Step 2: Build**

Run: `cmd.exe /c "./gradlew.bat :26.1:clientClasses"`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add src/client/kotlin/com/breadmoirai/redstonespecs/client/screen/RunnerTimelineScreen.kt
git commit -m "feat(client): RunnerTimelineScreen renders tick scrubber over recording"
```

---

## Task 5: Trigger the screen from the Runner block

**Files:**
- Modify: `src/main/kotlin/com/breadmoirai/redstonespecs/block/RedstoneSpecRunnerBlock.kt`
- Modify: `src/main/kotlin/com/breadmoirai/redstonespecs/network/Packets.kt` (new S2C `OpenTimelineS2CPayload`)
- Modify: `src/client/kotlin/com/breadmoirai/redstonespecs/client/network/ClientNetworkHandler.kt`

- [ ] **Step 1: Add S2C payload**

In `Packets.kt`:

```kotlin
data class OpenTimelineS2CPayload(val runnerPos: BlockPos) : CustomPacketPayload {
    companion object {
        val TYPE = CustomPacketPayload.Type<OpenTimelineS2CPayload>(
            Identifier.fromNamespaceAndPath("redstonespecs", "open_timeline")
        )
        val STREAM_CODEC: StreamCodec<ByteBuf, OpenTimelineS2CPayload> = StreamCodec.composite(
            BlockPos.STREAM_CODEC, OpenTimelineS2CPayload::runnerPos,
            ::OpenTimelineS2CPayload,
        )
    }
    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE
}
```

Register it in `NetworkRegistry.kt` (`registerS2C`).

- [ ] **Step 2: Server sends it on shift-right-click of the runner block**

In `RedstoneSpecRunnerBlock.kt`'s `useWithoutItem` (or the relevant interaction handler), add a branch:

```kotlin
if (player.isShiftKeyDown && player is ServerPlayer) {
    ServerPlayNetworking.send(player, OpenTimelineS2CPayload(pos))
    return InteractionResult.SUCCESS
}
```

(Confirm exact method names against the existing code.)

- [ ] **Step 3: Client opens the screen on receipt**

In `ClientNetworkHandler.kt`, register a handler for `OpenTimelineS2CPayload`:

```kotlin
ClientPlayNetworking.registerGlobalReceiver(OpenTimelineS2CPayload.TYPE) { payload, ctx ->
    ctx.client().setScreen(RunnerTimelineScreen(payload.runnerPos))
}
```

- [ ] **Step 4: Build all source sets**

Run: `cmd.exe /c "./gradlew.bat :26.1:clientClasses classes gametestClasses clientTestClasses testClasses"`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/breadmoirai/redstonespecs/block/RedstoneSpecRunnerBlock.kt \
        src/main/kotlin/com/breadmoirai/redstonespecs/network/Packets.kt \
        src/main/kotlin/com/breadmoirai/redstonespecs/network/NetworkRegistry.kt \
        src/client/kotlin/com/breadmoirai/redstonespecs/client/network/ClientNetworkHandler.kt
git commit -m "feat(client): shift-right-click runner block opens RunnerTimelineScreen"
```

---

## Task 6: Manual verification via runClient

- [ ] **Step 1: Launch the dev client**

Run: `cmd.exe /c "./gradlew.bat :26.1:runClient"` (or whatever the dev client task is — see existing build setup).

- [ ] **Step 2: In-game**

1. Place a `RedstoneSpecRecorderBlock`, record any spec.
2. Place a `RedstoneSpecRunnerBlock` on the same id.
3. Click Run. Wait for the result toast/UI.
4. Shift-right-click the runner block.
5. Verify the timeline screen opens, the slider exists, and dragging it updates the displayed states.

- [ ] **Step 3: Document quirks**

If anything is broken (e.g. `StateRecordingView.stateAt` returns wrong state at the boundary), capture as a follow-up issue. Don't fix in this plan unless trivial.

- [ ] **Step 4: Commit any small fixes**

```bash
git status
git add -A && git commit -m "fix(client): minor timeline UI polish from manual testing" || true
```

---

## Verification checklist

- [ ] `ClientRunnerState.get(runnerPos)` returns the latest `TestResult` after a run.
- [ ] `ClientRunnerState.get(runnerPos)?.recording` is non-null after the engine run completed (Plan E delivered the recording).
- [ ] Shift-right-click on a runner block opens `RunnerTimelineScreen`.
- [ ] The slider scrubs ticks 0..lifespan-1 and the displayed state lines update.
- [ ] All five source sets compile.
- [ ] `:26.1:test` and `:26.1:runClientTest` still pass.

---

## Notes on what is intentionally NOT in this plan

- Visualization of **input** events (button presses) on the timeline — out of scope; only output state shown.
- Inline diff highlighting between expected (from `RedstoneSpec.outputs`) and actual states at each tick — would be a high-value follow-up but is beyond "scrubber MVP."
- Persisting the open screen across world reloads.
- Hover-on-slider showing per-tick TickCheck pass/fail — follow-up.

---

## Open questions to resolve during execution

- Where does the runner block's `useWithoutItem` (or interact handler) live exactly? Confirm the method signature against MC 26.1's current `Block` API before editing.
- Does `RedstoneSpecRunnerBlock` already have a non-shift interaction that opens a different screen? If so, ensure shift-right-click doesn't conflict.
- Is `StateRecording.initialSnapshot` part of the public API? If not, expose it (small refactor).
