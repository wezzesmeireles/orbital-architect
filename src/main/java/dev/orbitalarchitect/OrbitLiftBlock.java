package dev.orbitalarchitect;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;

final class OrbitLiftBlock extends Block {
    OrbitLiftBlock(Properties properties) { super(properties); }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player,
                                               BlockHitResult hit) {
        if (level instanceof ServerLevel server && player instanceof ServerPlayer rider && !rider.isSpectator()) {
            ride(server, pos, rider, rider.isShiftKeyDown() ? -1 : 1);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    private void ride(ServerLevel level, BlockPos start, ServerPlayer rider, int direction) {
        for (int step = 1; step <= 96; step++) {
            var destination = start.above(step * direction);
            if (level.isOutsideBuildHeight(destination.above(2))) break;
            if (!level.getBlockState(destination).is(this)) continue;
            var landing = destination.above();
            if (!level.getBlockState(landing).isAir() || !level.getBlockState(landing.above()).isAir()
                    || !level.getWorldBorder().isWithinBounds(landing) || !level.mayInteract(rider, destination)) {
                rider.displayClientMessage(Component.translatable("block.orbital_architect.lift_blocked"), true);
                return;
            }
            var cargo = level.getEntitiesOfClass(ItemEntity.class, new AABB(start.above()).inflate(.12));
            for (var item : cargo) {
                item.teleportTo(destination.getX() + .5, landing.getY() + .12, destination.getZ() + .5);
            }
            rider.fallDistance = 0;
            rider.connection.teleport(destination.getX() + .5, landing.getY() + .02, destination.getZ() + .5,
                    rider.getYRot(), rider.getXRot());
            level.playSound(null, destination, SoundEvents.PISTON_EXTEND, SoundSource.BLOCKS, .55f, 1.25f);
            return;
        }
        rider.displayClientMessage(Component.translatable("block.orbital_architect.lift_no_stop"), true);
    }
}
