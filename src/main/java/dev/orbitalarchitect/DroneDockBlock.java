package dev.orbitalarchitect;

import com.mojang.serialization.MapCodec;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

final class DroneDockBlock extends Block implements EntityBlock {
    private static final MapCodec<DroneDockBlock> CODEC = simpleCodec(DroneDockBlock::new);
    private static final VoxelShape PAD = box(0, 0, 0, 16, 4, 16);
    private static final int RANGE = 16;
    static final IntegerProperty MODE = IntegerProperty.create("task", 0, 3);

    DroneDockBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(BlockStateProperties.LIT, false).setValue(MODE, 0));
    }

    @Override
    protected MapCodec<DroneDockBlock> codec() { return CODEC; }

    @Override
    public DroneDockBlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new DroneDockBlockEntity(pos, state);
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(BlockStateProperties.LIT, MODE);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return PAD;
    }

    static boolean inRange(BlockPos altar, BlockPos pad) { return altar.distSqr(pad) <= RANGE * RANGE; }

    static boolean canLand(ServerLevel level, BlockPos pos) {
        return !level.isOutsideBuildHeight(pos.above(2)) && level.getBlockState(pos.above()).isAir()
                && level.getBlockState(pos.above(2)).isAir();
    }

    static OrbAltarBlockEntity owner(ServerLevel level, BlockPos pos) {
        if (!(level.getBlockEntity(pos) instanceof DroneDockBlockEntity dock) || dock.anchor() == null) return null;
        return owner(level, dock);
    }

    static OrbAltarBlockEntity owner(ServerLevel level, DroneDockBlockEntity dock) {
        var anchor = dock.anchor();
        if (anchor == null) return null;
        var pad = dock.getBlockPos();
        if (level.hasChunkAt(anchor) && level.getBlockEntity(anchor) instanceof OrbAltarBlockEntity altar
                && !altar.isRemoved() && inRange(anchor, pad) && dock.belongsTo(altar) && altar.hasDock(pad)) return altar;
        return nearest(level, pad, altar -> dock.belongsTo(altar) && altar.hasDock(pad));
    }

    private static OrbAltarBlockEntity nearest(ServerLevel level, BlockPos pos, Predicate<OrbAltarBlockEntity> accepts) {
        OrbAltarBlockEntity nearest = null;
        double distance = RANGE * RANGE;
        for (int x = (pos.getX() - RANGE) >> 4; x <= (pos.getX() + RANGE) >> 4; x++) {
            for (int z = (pos.getZ() - RANGE) >> 4; z <= (pos.getZ() + RANGE) >> 4; z++) {
                var chunk = level.getChunkSource().getChunkNow(x, z);
                if (chunk == null) continue;
                for (var entity : chunk.getBlockEntities().values()) {
                    if (!(entity instanceof OrbAltarBlockEntity altar) || altar.isRemoved()) continue;
                    double candidate = pos.distSqr(altar.getBlockPos());
                    if (candidate > distance || !accepts.test(altar)) continue;
                    if (nearest != null && candidate == distance
                            && nearest.getBlockPos().compareTo(altar.getBlockPos()) <= 0) continue;
                    nearest = altar;
                    distance = candidate;
                }
            }
        }
        return nearest;
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack) {
        if (level instanceof ServerLevel server && placer instanceof ServerPlayer player) {
            tryLink(server, pos, player);
        }
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player,
                                               BlockHitResult hit) {
        if (level instanceof ServerLevel server && player instanceof ServerPlayer actor && !actor.isSpectator()) {
            var altar = owner(server, pos);
            if (altar == null) {
                tryLink(server, pos, actor);
            } else {
                var task = altar.dockTask(pos);
                var status = !task.isEmpty() ? Component.literal(task) : canLand(server, pos) ? Component.translatable("block.orbital_architect.drone_dock_linked",
                        altar.tier(), altar.energyInput().getEnergyStored())
                        : Component.translatable("block.orbital_architect.drone_dock_blocked");
                actor.displayClientMessage(status, true);
            }
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    private static void tryLink(ServerLevel level, BlockPos pos, ServerPlayer player) {
        if (!(level.getBlockEntity(pos) instanceof DroneDockBlockEntity dock)) return;
        if (dock.anchor() != null && !level.hasChunkAt(dock.anchor())) {
            player.displayClientMessage(Component.translatable("block.orbital_architect.drone_dock_altar_unloaded"), true);
            return;
        }
        var altar = nearest(level, pos, candidate -> true);
        if (altar == null) {
            player.displayClientMessage(Component.translatable("block.orbital_architect.drone_dock_unlinked"), true);
        } else if (!level.mayInteract(player, pos) || !level.mayInteract(player, altar.getBlockPos())) {
            player.displayClientMessage(Component.translatable("block.orbital_architect.drone_dock_protected"), true);
        } else if (altar.attachDock(level, dock)) {
            player.displayClientMessage(Component.translatable("block.orbital_architect.drone_dock_linked",
                    altar.tier(), altar.energyInput().getEnergyStored()), true);
        } else {
            player.displayClientMessage(Component.translatable("block.orbital_architect.drone_dock_full"), true);
        }
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState next, boolean moved) {
        if (!next.is(this) && level instanceof ServerLevel server) {
            var altar = owner(server, pos);
            if (altar != null) altar.detachDock(server, pos);
        }
        super.onRemove(state, level, pos, next, moved);
    }
}
