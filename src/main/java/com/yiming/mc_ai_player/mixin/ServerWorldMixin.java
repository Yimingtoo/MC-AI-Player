package com.yiming.mc_ai_player.mixin;

import com.yiming.mc_ai_player.monitor.MonitoringSession;
import com.yiming.mc_ai_player.monitor.MonitoringState;
import net.minecraft.block.BlockState;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(World.class)
public class ServerWorldMixin {

    @Inject(method = "setBlockState", at = @At("HEAD"))
    private void onSetBlockState(BlockPos pos, BlockState state, int flags,
                                  CallbackInfoReturnable<Boolean> cir) {
        MonitoringSession session = MonitoringState.getActiveSession();
        if (session == null || !session.isActive()) return;

        World world = (World) (Object) this;
        if (!session.dimension.equals(world.getRegistryKey().getValue().toString())) return;

        // Quick bounds check
        int x = pos.getX(), y = pos.getY(), z = pos.getZ();
        if (x < session.minX || x > session.maxX ||
            y < session.minY || y > session.maxY ||
            z < session.minZ || z > session.maxZ) return;

        // Capture OLD block state (before change)
        Identifier oldId = Registries.BLOCK.getId(world.getBlockState(pos).getBlock());
        session.recordChange(pos, oldId.toString());
    }
}
