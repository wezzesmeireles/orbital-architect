package dev.orbitalarchitect;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

public final class OrbAltarBlock extends BaseEntityBlock {
    private static final MapCodec<OrbAltarBlock> CODEC = RecordCodec.create();
    private static final VoxelShape SHAPE = Shapes.or(
            box(0, 0, 0, 16, 3, 16), box(4, 3, 4, 12, 8, 12), box(2, 8, 2, 14, 10, 14));
    private static final VoxelShape INTERACTION_SHAPE = Shapes.or(
            SHAPE, box(3, 10, 3, 13, 16, 13));
    static final DirectionProperty FRONT = BlockStateProperties.HORIZONTAL_FACING;
    private final int tier;

    OrbAltarBlock(Properties properties, int tier) {
        super(properties);
        this.tier = tier;
        registerDefaultState(stateDefinition.any().setValue(FRONT, Direction.NORTH));
    }

    private static final class RecordCodec {
        static MapCodec<OrbAltarBlock> create() {
            return com.mojang.serialization.codecs.RecordCodecBuilder.mapCodec(instance -> instance.group(
                    propertiesCodec(), com.mojang.serialization.Codec.intRange(1, 4)
                            .fieldOf("tier").forGetter(altar -> altar.tier)
            ).apply(instance, OrbAltarBlock::new));
        }
    }

    int tier() { return tier; }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FRONT);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        if (context.getLevel() instanceof ServerLevel server) {
            int required = AltarRecovery.tierAt(server, context.getClickedPos());
            if (required > tier) {
                if (context.getPlayer() instanceof ServerPlayer player) {
                    player.displayClientMessage(Component.translatable("block.orbital_architect.altar_restore_tier", required), true);
                }
                return null;
            }
        }
        return defaultBlockState().setValue(FRONT, context.getHorizontalDirection().getOpposite());
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack) {
        if (placer instanceof ServerPlayer player && level.getBlockEntity(pos) instanceof OrbAltarBlockEntity altar) {
            altar.markPlacedBy(player);
        }
    }

    @Override
    protected MapCodec<OrbAltarBlock> codec() { return CODEC; }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return INTERACTION_SHAPE;
    }

    @Override
    protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos,
                                           CollisionContext context) {
        return SHAPE;
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) { return RenderShape.MODEL; }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new OrbAltarBlockEntity(pos, state);
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
                                                                  BlockEntityType<T> type) {
        return level.isClientSide ? null : createTickerHelper(type, OrbitalArchitect.ALTAR_ENTITY.get(),
                OrbAltarBlockEntity::tick);
    }

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
                                             Player player, InteractionHand hand, BlockHitResult hit) {
        if (stack.getItem() instanceof ConstructionDroneItem drone) {
            if (player instanceof ServerPlayer server && level.getBlockEntity(pos) instanceof OrbAltarBlockEntity altar) {
                player.displayClientMessage(Component.literal(altar.installDrone(server, stack, drone.tier)), true);
            }
            return ItemInteractionResult.sidedSuccess(level.isClientSide);
        }
        int nextTier;
        if (stack.getItem() instanceof UpgradeCardItem card) {
            nextTier = card.targetTier();
        } else if (stack.getItem() instanceof BlockItem item && item.getBlock() instanceof OrbAltarBlock next
                && next.tier() == tier + 1) {
            nextTier = next.tier();
        } else {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        if (player instanceof ServerPlayer server && level.getBlockEntity(pos) instanceof OrbAltarBlockEntity altar) {
            String result = altar.installUpgrade(server, stack, nextTier);
            if (result.isEmpty() && level.getBlockEntity(pos) instanceof OrbAltarBlockEntity upgraded) {
                PlannerNetwork.afterUpgrade(server, upgraded);
            } else if (!result.isEmpty()) {
                player.displayClientMessage(Component.literal(result), true);
            }
        }
        return ItemInteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState next, boolean moved) {
        if (!(next.getBlock() instanceof OrbAltarBlock) && level instanceof ServerLevel server
                && level.getBlockEntity(pos) instanceof OrbAltarBlockEntity altar) {
            altar.preserveOnRemoval(server);
        }
        super.onRemove(state, level, pos, next, moved);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                               Player player, BlockHitResult hit) {
        if (player instanceof ServerPlayer server && !server.isSpectator()
                && level.getBlockEntity(pos) instanceof OrbAltarBlockEntity altar) {
            PlannerNetwork.open(server, altar);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }
}









