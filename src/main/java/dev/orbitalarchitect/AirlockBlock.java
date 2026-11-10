package dev.orbitalarchitect;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

final class AirlockBlock extends Block implements EntityBlock {
    private static final MapCodec<AirlockBlock> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            propertiesCodec(), Codec.intRange(2, 4).fieldOf("size").forGetter(block -> block.size)
    ).apply(instance, AirlockBlock::new));
    static final IntegerProperty COLUMN = IntegerProperty.create("column", 0, 3);
    static final IntegerProperty ROW = IntegerProperty.create("row", 0, 3);
    static final BooleanProperty MOVING = BooleanProperty.create("moving");
    private static final VoxelShape PANEL_X = box(7, 0, 0, 9, 16, 16);
    private static final VoxelShape PANEL_Z = box(0, 0, 7, 16, 16, 9);

    private final int size;

    AirlockBlock(Properties properties, int size) {
        super(properties);
        this.size = size;
        registerDefaultState(stateDefinition.any().setValue(BlockStateProperties.HORIZONTAL_AXIS, Direction.Axis.Z)
                .setValue(BlockStateProperties.OPEN, false).setValue(MOVING, false)
                .setValue(COLUMN, 0).setValue(ROW, 0));
    }

    @Override
    protected MapCodec<AirlockBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(BlockStateProperties.HORIZONTAL_AXIS, BlockStateProperties.OPEN, MOVING, COLUMN, ROW);
    }

    @Override
    public AirlockBlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return state.getValue(COLUMN) == 0 && state.getValue(ROW) == 0 ? new AirlockBlockEntity(pos, state) : null;
    }

    int size() { return size; }

    int motionTicks() { return size == 4 ? 30 : 22; }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_AXIS, context.getHorizontalDirection().getAxis());
    }

    private static BlockPos at(BlockPos origin, Direction.Axis axis, int column, int row) {
        return axis == Direction.Axis.Z ? origin.offset(column, row, 0) : origin.offset(0, row, column);
    }

    private static BlockPos origin(BlockPos pos, BlockState state) {
        return at(pos, state.getValue(BlockStateProperties.HORIZONTAL_AXIS),
                -state.getValue(COLUMN), -state.getValue(ROW));
    }

    private boolean member(BlockState state, Direction.Axis axis, int column, int row) {
        return state.is(this) && state.getValue(BlockStateProperties.HORIZONTAL_AXIS) == axis
                && state.getValue(COLUMN) == column && state.getValue(ROW) == row;
    }

    boolean samePanel(BlockState state, BlockState expected) {
        return member(state, expected.getValue(BlockStateProperties.HORIZONTAL_AXIS),
                expected.getValue(COLUMN), expected.getValue(ROW));
    }

    Map<BlockPos, BlockState> panels(BlockPos origin, BlockState state) {
        var parts = new LinkedHashMap<BlockPos, BlockState>();
        var axis = state.getValue(BlockStateProperties.HORIZONTAL_AXIS);
        for (int row = 0; row < size; row++) for (int column = 0; column < size; column++) {
            parts.put(at(origin, axis, column, row), state.setValue(COLUMN, column).setValue(ROW, row));
        }
        return parts;
    }

    boolean canAssemble(BlockPlaceContext context, BlockState state) {
        var level = context.getLevel();
        var origin = context.getClickedPos();
        var axis = state.getValue(BlockStateProperties.HORIZONTAL_AXIS);
        var player = context.getPlayer();
        var collisions = player == null ? CollisionContext.empty() : CollisionContext.of(player);
        for (int row = 0; row < size; row++) for (int column = 0; column < size; column++) {
            var pos = at(origin, axis, column, row);
            if (!level.hasChunkAt(pos) || level.isOutsideBuildHeight(pos) || !level.getWorldBorder().isWithinBounds(pos)
                    || !level.getBlockState(pos).isAir() || !level.isUnobstructed(state, pos, collisions)
                    || (player != null && !level.mayInteract(player, pos))) return false;
        }
        return true;
    }

    boolean assemble(Level level, BlockPos origin, BlockState state) {
        for (var panel : panels(origin, state).entrySet()) {
            if (!level.setBlock(panel.getKey(), panel.getValue(), UPDATE_ALL | UPDATE_KNOWN_SHAPE)) {
                if (level.getBlockState(origin).is(this)) level.removeBlock(origin, false);
                return false;
            }
        }
        return true;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player,
                                               BlockHitResult hit) {
        if (level.isClientSide) return InteractionResult.SUCCESS;
        if (player.isSpectator()) return InteractionResult.PASS;
        if (state.getValue(MOVING)) return InteractionResult.CONSUME;
        var axis = state.getValue(BlockStateProperties.HORIZONTAL_AXIS);
        var origin = origin(pos, state);
        boolean open = !state.getValue(BlockStateProperties.OPEN);
        for (int row = 0; row < size; row++) for (int column = 0; column < size; column++) {
            var cell = at(origin, axis, column, row);
            if (!level.hasChunkAt(cell) || !member(level.getBlockState(cell), axis, column, row)
                    || !level.mayInteract(player, cell)) {
                player.displayClientMessage(Component.translatable("block.orbital_architect.airlock_incomplete"), true);
                return InteractionResult.CONSUME;
            }
        }
        if (!open && obstructed(level, origin, axis)) {
            player.displayClientMessage(Component.translatable("block.orbital_architect.airlock_obstructed"), true);
            return InteractionResult.CONSUME;
        }
        startMotion(level, origin, axis, open);
        return InteractionResult.CONSUME;
    }

    private boolean obstructed(Level level, BlockPos origin, Direction.Axis axis) {
        for (int row = 0; row < size; row++) for (int column = 0; column < size; column++) {
            var cell = at(origin, axis, column, row);
            var closed = level.getBlockState(cell).setValue(BlockStateProperties.OPEN, false).setValue(MOVING, false);
            if (!level.isUnobstructed(closed, cell, CollisionContext.empty())) return true;
        }
        return false;
    }

    private void setPanels(Level level, BlockPos origin, Direction.Axis axis, boolean open, boolean moving) {
        for (int row = 0; row < size; row++) for (int column = 0; column < size; column++) {
            var cell = at(origin, axis, column, row);
            var panel = level.getBlockState(cell);
            level.setBlock(cell, panel.setValue(BlockStateProperties.OPEN, open).setValue(MOVING, moving),
                    UPDATE_CLIENTS);
        }
    }

    private void startMotion(Level level, BlockPos origin, Direction.Axis axis, boolean open) {
        setPanels(level, origin, axis, open, true);
        if (level.getBlockEntity(origin) instanceof AirlockBlockEntity door) {
            door.startMotion(level.getGameTime());
        }
        level.scheduleTick(origin, this, motionTicks());
        playMechanism(level, origin, axis, (open ? OrbitalArchitect.AIRLOCK_OPEN : OrbitalArchitect.AIRLOCK_CLOSE).get(),
                .80f, size == 4 ? .86f : 1f);
    }

    @Override
    protected void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        var axis = state.getValue(BlockStateProperties.HORIZONTAL_AXIS);
        if (!state.getValue(MOVING) || !member(state, axis, 0, 0)) return;
        for (int row = 0; row < size; row++) for (int column = 0; column < size; column++) {
            var cell = at(pos, axis, column, row);
            if (!level.hasChunkAt(cell)) {
                level.scheduleTick(pos, this, 1);
                return;
            }
            if (!member(level.getBlockState(cell), axis, column, row)) {
                level.removeBlock(pos, false);
                return;
            }
        }
        boolean open = state.getValue(BlockStateProperties.OPEN);
        if (!open && obstructed(level, pos, axis)) {
            startMotion(level, pos, axis, true);
            return;
        }
        setPanels(level, pos, axis, open, false);
        if (!open) playMechanism(level, pos, axis, net.minecraft.sounds.SoundEvents.IRON_TRAPDOOR_CLOSE, .27f, .86f);
    }

    private void playMechanism(Level level, BlockPos origin, Direction.Axis axis, net.minecraft.sounds.SoundEvent sound,
                               float volume, float pitch) {
        double center = size * .5;
        level.playSound(null, origin.getX() + (axis == Direction.Axis.Z ? center : .5), origin.getY() + center,
                origin.getZ() + (axis == Direction.Axis.X ? center : .5), sound, SoundSource.BLOCKS, volume, pitch);
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState next, boolean moved) {
        super.onRemove(state, level, pos, next, moved);
        if (level.isClientSide || next.is(this)) return;
        var axis = state.getValue(BlockStateProperties.HORIZONTAL_AXIS);
        var origin = origin(pos, state);
        for (int row = 0; row < size; row++) for (int column = 0; column < size; column++) {
            var cell = at(origin, axis, column, row);
            if (!cell.equals(pos) && member(level.getBlockState(cell), axis, column, row)) {
                level.removeBlock(cell, false);
            }
        }
    }

    @Override
    protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos,
                                           CollisionContext context) {
        if (state.getValue(MOVING) || state.getValue(BlockStateProperties.OPEN)) return Shapes.empty();
        return state.getValue(BlockStateProperties.HORIZONTAL_AXIS) == Direction.Axis.X ? PANEL_X : PANEL_Z;
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        var axis = state.getValue(BlockStateProperties.HORIZONTAL_AXIS);
        if (state.getValue(MOVING) || !state.getValue(BlockStateProperties.OPEN)) {
            return axis == Direction.Axis.X ? PANEL_X : PANEL_Z;
        }
        int column = state.getValue(COLUMN), row = state.getValue(ROW);
        var frame = Shapes.empty();
        if (column == 0) frame = Shapes.or(frame, rail(axis, 0, 0, 2, 16));
        if (column == size - 1) frame = Shapes.or(frame, rail(axis, 14, 0, 16, 16));
        if (row == 0) frame = Shapes.or(frame, rail(axis, 0, 0, 16, 2));
        if (row == size - 1) frame = Shapes.or(frame, rail(axis, 0, 14, 16, 16));
        return frame;
    }

    private static VoxelShape rail(Direction.Axis axis, int start, int bottom, int end, int top) {
        return axis == Direction.Axis.Z ? box(start, bottom, 6, end, top, 10)
                : box(6, bottom, start, 10, top, end);
    }
}
