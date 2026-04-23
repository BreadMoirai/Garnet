package com.breadmoirai.redstonespecs.mixin;

import com.breadmoirai.redstonespecs.runner.StateRecorder;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayDeque;
import java.util.Deque;

@Mixin(Level.class)
abstract class LevelSetBlockMixin {

    // Stack to handle recursive setBlock calls (neighbor updates triggering further updates).
    private static final ThreadLocal<Deque<BlockState>> BEFORE_STATE_STACK =
        ThreadLocal.withInitial(ArrayDeque::new);

    @Inject(
        method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Z",
        at = @At("HEAD")
    )
    private void redstonespecs$captureBeforeState(
        BlockPos pos, BlockState state, int flags,
        CallbackInfoReturnable<Boolean> cir
    ) {
        StateRecorder recorder = StateRecorder.getActive();
        if (recorder == null || !recorder.isInBounds(pos)) {
            BEFORE_STATE_STACK.get().push(null); // null sentinel — skip at RETURN
            return;
        }
        BEFORE_STATE_STACK.get().push(((Level) (Object) this).getBlockState(pos));
    }

    @Inject(
        method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Z",
        at = @At("RETURN")
    )
    private void redstonespecs$recordChange(
        BlockPos pos, BlockState newState, int flags,
        CallbackInfoReturnable<Boolean> cir
    ) {
        Deque<BlockState> stack = BEFORE_STATE_STACK.get();
        BlockState from = stack.isEmpty() ? null : stack.pop();
        if (from == null) return; // out of bounds or no recorder — sentinel consumed
        if (!cir.getReturnValue()) return; // block did not actually change
        StateRecorder recorder = StateRecorder.getActive();
        if (recorder == null) return; // recorder deactivated between HEAD and RETURN (edge case)
        recorder.record(pos, from, newState);
    }
}
