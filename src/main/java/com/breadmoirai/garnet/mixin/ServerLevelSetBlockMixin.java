package com.breadmoirai.garnet.mixin;

import com.breadmoirai.garnet.editor.structure.StructureEditWatcher;
import com.breadmoirai.garnet.playback.recorder.StateRecorder;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Set;

@Mixin(Level.class)
abstract class ServerLevelSetBlockMixin {

    // Stack to handle recursive setBlock calls (neighbor updates triggering further updates).
    // We only process ServerLevel instances (guarded below), so this is always server-thread-only.
    // The stack is still needed for recursion safety: nested setBlock calls each need their own
    // stack entry to correctly pair HEAD captures with RETURN consumers.
    // ArrayDeque does not allow null elements, so we use a sentinel object instead of null.
    private static final Object SKIP_SENTINEL = new Object();
    private static final ThreadLocal<Deque<Object>> BEFORE_STATE_STACK =
        ThreadLocal.withInitial(ArrayDeque::new);

    @Inject(
        method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Z",
        at = @At("HEAD")
    )
    private void garnet$captureBeforeState(
        BlockPos pos, BlockState state, int flags,
        CallbackInfoReturnable<Boolean> cir
    ) {
        // Only record on the server level; ClientLevel calls are ignored.
        if (!(((Object) this) instanceof ServerLevel)) {
            BEFORE_STATE_STACK.get().push(SKIP_SENTINEL);
            return;
        }
        // Capture before-state if any active recorder cares about this position.
        boolean anyInterested = false;
        for (StateRecorder recorder : StateRecorder.activeRecorders()) {
            if (recorder.isInBounds(pos)) { anyInterested = true; break; }
        }
        if (!anyInterested) {
            BEFORE_STATE_STACK.get().push(SKIP_SENTINEL);
            return;
        }
        BEFORE_STATE_STACK.get().push(((Level) (Object) this).getBlockState(pos));
    }

    @Inject(
        method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Z",
        at = @At("RETURN")
    )
    private void garnet$recordChange(
        BlockPos pos, BlockState newState, int flags,
        CallbackInfoReturnable<Boolean> cir
    ) {
        Deque<Object> stack = BEFORE_STATE_STACK.get();
        Object fromObj = stack.isEmpty() ? SKIP_SENTINEL : stack.pop();

        if (fromObj == SKIP_SENTINEL) return; // client level, out of bounds, or no recorder
        if (!cir.getReturnValue()) return; // block did not actually change
        BlockState before = (BlockState) fromObj;
        for (StateRecorder recorder : StateRecorder.activeRecorders()) {
            if (recorder.isInBounds(pos)) {
                recorder.record(pos, before, newState);
            }
        }
    }

    /**
     * Feeds structure auto-save's dirty tracking, on the 4-arg overload rather than the 3-arg one
     * the recorder hooks above use.
     *
     * <p>{@code setBlock(pos, state, flags)} delegates to {@code setBlock(pos, state, flags, 512)},
     * and {@code ServerLevel} overrides neither, so hooking only the 4-arg covers BOTH overloads
     * with exactly one watcher call per write — no double-counting, nothing added on the hot path.
     * Hooking the 3-arg instead left every 4-arg write invisible to auto-save: vanilla's own
     * shape-update machinery ({@code Block.updateOrDestroy}, neighbour-shape updates for fences,
     * walls, stairs, redstone-wire reconnection) goes through the 4-arg form, as does any other mod
     * calling it directly.
     *
     * <p>Unlike the recorder hooks, auto-save cares about every successful server-side change rather
     * than only positions some {@link StateRecorder} is watching, and it needs no before-state —
     * only the position and the fact that the write actually landed. So it takes no part in the
     * {@code BEFORE_STATE_STACK} pairing and must not be folded into the RETURN hook above, which
     * returns early for the common "no recorder interested" case.
     *
     * <p>A direct {@code LevelChunk.setBlockState} write still bypasses this, which is what
     * {@code StructureAutoSaveSpec}'s bounded-capture test uses to stage a genuinely untracked edit.
     */
    @Inject(
        method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z",
        at = @At("RETURN")
    )
    private void garnet$notifyStructureWatcher(
        BlockPos pos, BlockState newState, int flags, int updateLimit,
        CallbackInfoReturnable<Boolean> cir
    ) {
        if (cir.getReturnValue() && ((Object) this) instanceof ServerLevel) {
            StructureEditWatcher.onBlockChanged((ServerLevel) (Object) this, pos);
        }
    }
}
