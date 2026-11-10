package dev.orbitalarchitect;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.BlockHitResult;

final class ServicePortBlock extends Block implements EntityBlock {
    private static final MapCodec<ServicePortBlock> CODEC = simpleCodec(ServicePortBlock::new);

    ServicePortBlock(Properties properties) { super(properties); }

    @Override protected MapCodec<ServicePortBlock> codec() { return CODEC; }

    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(BlockStateProperties.HORIZONTAL_FACING);
    }

    @Override public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, context.getHorizontalDirection().getOpposite());
    }

    @Override public ServicePortBlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new ServicePortBlockEntity(pos, state);
    }

    @Override public void setPlacedBy(Level level, BlockPos pos, BlockState state, LivingEntity placer, ItemStack item) {
        if (level instanceof ServerLevel server && placer instanceof ServerPlayer player) link(server, pos, player);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (level instanceof ServerLevel server && player instanceof ServerPlayer actor && !actor.isSpectator()) {
            var altar = link(server, pos, actor);
            if (altar != null) PlannerNetwork.openTerminal(actor, altar, pos, true);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    private static OrbAltarBlockEntity link(ServerLevel level, BlockPos pos, ServerPlayer player) {
        if (!(level.getBlockEntity(pos) instanceof ServicePortBlockEntity port)) return null;
        var altar = port.owner(level);
        if (altar == null && !port.waitingForOrbit(level) && level.mayInteract(player, pos)) {
            for (var side : Direction.Plane.HORIZONTAL) {
                var neighbor = pos.relative(side);
                if (level.hasChunkAt(neighbor) && level.getBlockEntity(neighbor) instanceof OrbAltarBlockEntity adjacent
                        && level.mayInteract(player, neighbor)) {
                    altar = adjacent;
                    break;
                }
            }
            if (altar == null) {
                altar = OrbAltarBlockEntity.findOrbit(level, pos,
                        candidate -> candidate.acceptsPort(pos) && level.mayInteract(player, candidate.getBlockPos()));
            }
            if (altar != null && !altar.attachServicePort(port)) altar = null;
        }
        if (altar == null) {
            player.displayClientMessage(Component.translatable("block.orbital_architect.service_port_unlinked"), true);
        } else {
            var face = Component.translatable("direction.orbital_architect." + port.outputSide().getSerializedName());
            player.displayClientMessage(Component.translatable("block.orbital_architect.service_port_linked", altar.tier(), face), true);
        }
        return altar;
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState next, boolean moved) {
        if (!next.is(this) && level instanceof ServerLevel server
                && server.getBlockEntity(pos) instanceof ServicePortBlockEntity port) {
            var altar = port.owner(server);
            if (altar != null) altar.detachServicePort(pos);
            for (var inventory : new net.neoforged.neoforge.items.IItemHandler[]{port.input(), port.output()}) {
                for (int slot = 0; slot < inventory.getSlots(); slot++) popResource(level, pos, inventory.getStackInSlot(slot));
            }
        }
        super.onRemove(state, level, pos, next, moved);
    }
}
