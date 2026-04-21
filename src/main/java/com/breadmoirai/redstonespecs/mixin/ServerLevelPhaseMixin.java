package com.breadmoirai.redstonespecs.mixin;

import com.breadmoirai.redstonespecs.data.Phase;
import com.breadmoirai.redstonespecs.runner.SpecRunnerCoordinator;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerLevel.class)
abstract class ServerLevelPhaseMixin {

    // Fires after the block-events queue is fully drained each tick.
    @Inject(method = "runBlockEvents()V", at = @At("TAIL"))
    private void redstonespecs$afterBlockEvents(CallbackInfo ci) {
        ServerLevel self = (ServerLevel) (Object) this;
        SpecRunnerCoordinator.INSTANCE.onPhase(self, self.getServer().getTickCount(), Phase.BLOCK_EVENTS);
    }

    // Fires after all block entities have ticked.
    @Inject(method = "tickBlockEntities()V", at = @At("TAIL"))
    private void redstonespecs$afterTickBlockEntities(CallbackInfo ci) {
        ServerLevel self = (ServerLevel) (Object) this;
        SpecRunnerCoordinator.INSTANCE.onPhase(self, self.getServer().getTickCount(), Phase.TILE_ENTITY_TICK);
    }

    // Fires after all scheduled block and fluid ticks have been processed.
    // Injection point: AFTER the second LevelTicks.tick() call (fluid ticks follow block ticks).
    // Verify against decompiled source with ./gradlew genSources if this needs adjustment.
    @Inject(
        method = "tick(Ljava/util/function/BooleanSupplier;)V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/ticks/LevelTicks;tick(JILnet/minecraft/world/ticks/LevelTicks$TickCallback;)V",
            ordinal = 1,
            shift = At.Shift.AFTER
        )
    )
    private void redstonespecs$afterScheduledTicks(java.util.function.BooleanSupplier hasTimeLeft, CallbackInfo ci) {
        ServerLevel self = (ServerLevel) (Object) this;
        SpecRunnerCoordinator.INSTANCE.onPhase(self, self.getServer().getTickCount(), Phase.SCHEDULED_TICKS);
    }

    // Fires after random ticks have been distributed to chunks.
    // Injection point: AFTER the chunk-source tick call that drives random ticking.
    // Verify against decompiled source with ./gradlew genSources if this needs adjustment.
    @Inject(
        method = "tick(Ljava/util/function/BooleanSupplier;)V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerChunkCache;tick(Ljava/util/function/BooleanSupplier;Z)V",
            shift = At.Shift.AFTER
        )
    )
    private void redstonespecs$afterChunkSourceTick(java.util.function.BooleanSupplier hasTimeLeft, CallbackInfo ci) {
        ServerLevel self = (ServerLevel) (Object) this;
        SpecRunnerCoordinator.INSTANCE.onPhase(self, self.getServer().getTickCount(), Phase.RANDOM_TICKS);
    }
}
