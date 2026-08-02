package com.breadmoirai.garnet.mixin;

import com.breadmoirai.garnet.spec.Phase;
import com.breadmoirai.garnet.core.events.SubTickPhaseEvents;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerLevel.class)
abstract class ServerLevelPhaseMixin {

    @Inject(method = "tick(Ljava/util/function/BooleanSupplier;)V", at = @At("HEAD"))
    private void garnet$startOfTick(java.util.function.BooleanSupplier hasTimeLeft, CallbackInfo ci) {
        SubTickPhaseEvents.PHASE.invoker().onPhase((ServerLevel) (Object) this, Phase.START_OF_TICK);
    }

    // Fires after all block entities have ticked.
    @Inject(
        method = "tick(Ljava/util/function/BooleanSupplier;)V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerLevel;tickBlockEntities()V",
            shift = At.Shift.AFTER
        )
    )
    private void garnet$afterTickBlockEntities(java.util.function.BooleanSupplier hasTimeLeft, CallbackInfo ci) {
        SubTickPhaseEvents.PHASE.invoker().onPhase((ServerLevel) (Object) this, Phase.TILE_ENTITY_TICK);
    }

    // Fires after the block-events queue is fully drained each tick.
    @Inject(method = "runBlockEvents()V", at = @At("TAIL"))
    private void garnet$afterBlockEvents(CallbackInfo ci) {
        SubTickPhaseEvents.PHASE.invoker().onPhase((ServerLevel) (Object) this, Phase.BLOCK_EVENTS);
    }

    // Fires after all scheduled block and fluid ticks have been processed.
    @Inject(
        method = "tick(Ljava/util/function/BooleanSupplier;)V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/ticks/LevelTicks;tick(JILjava/util/function/BiConsumer;)V",
            ordinal = 1,
            shift = At.Shift.AFTER
        )
    )
    private void garnet$afterScheduledTicks(java.util.function.BooleanSupplier hasTimeLeft, CallbackInfo ci) {
        SubTickPhaseEvents.PHASE.invoker().onPhase((ServerLevel) (Object) this, Phase.SCHEDULED_TICKS);
    }

    // Fires after random ticks have been distributed to chunks.
    @Inject(
        method = "tick(Ljava/util/function/BooleanSupplier;)V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerChunkCache;tick(Ljava/util/function/BooleanSupplier;Z)V",
            shift = At.Shift.AFTER
        )
    )
    private void garnet$afterChunkSourceTick(java.util.function.BooleanSupplier hasTimeLeft, CallbackInfo ci) {
        SubTickPhaseEvents.PHASE.invoker().onPhase((ServerLevel) (Object) this, Phase.RANDOM_TICKS);
    }

    @Inject(method = "tick(Ljava/util/function/BooleanSupplier;)V", at = @At("TAIL"))
    private void garnet$endOfTick(java.util.function.BooleanSupplier hasTimeLeft, CallbackInfo ci) {
        SubTickPhaseEvents.PHASE.invoker().onPhase((ServerLevel) (Object) this, Phase.END_OF_TICK);
    }
}
