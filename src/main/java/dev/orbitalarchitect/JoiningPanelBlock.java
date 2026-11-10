package dev.orbitalarchitect;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;

final class JoiningPanelBlock extends Block {
    private static final BooleanProperty[] CONNECTIONS = java.util.Arrays.stream(Direction.values())
            .map(direction -> BooleanProperty.create(direction.getSerializedName()))
            .toArray(BooleanProperty[]::new);
    private final boolean transparent;
    private final boolean floor;
    private static final java.util.Set<String> FLOOR_NEIGHBORS = java.util.Set.of(
            "deck_plate", "hull_panel", "hazard_plate", "strip_light", "dock_light", "orbital_light");

    JoiningPanelBlock(Properties properties, boolean transparent, boolean floor) {
        super(properties);
        this.transparent = transparent;
        this.floor = floor;
        BlockState state = stateDefinition.any();
        for (var property : CONNECTIONS) state = state.setValue(property, false);
        registerDefaultState(state);
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(CONNECTIONS);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return joined(defaultBlockState(), context.getLevel(), context.getClickedPos());
    }

    BlockState joined(BlockState state, BlockGetter level, BlockPos pos) {
        for (var direction : Direction.values()) {
            state = state.setValue(CONNECTIONS[direction.ordinal()], connects(direction, level.getBlockState(pos.relative(direction))));
        }
        return state;
    }

    @Override
    protected BlockState updateShape(BlockState state, Direction side, BlockState neighbor, LevelAccessor level,
                                     BlockPos pos, BlockPos neighborPos) {
        return state.setValue(CONNECTIONS[side.ordinal()], connects(side, neighbor));
    }

    private boolean connects(Direction direction, BlockState neighbor) {
        if (neighbor.is(this)) return true;
        if (!floor || !direction.getAxis().isHorizontal()) return false;
        if (neighbor.is(net.minecraft.world.level.block.Blocks.CYAN_CONCRETE)) return true;
        var id = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(neighbor.getBlock());
        return id.getNamespace().equals(OrbitalArchitect.ID) && FLOOR_NEIGHBORS.contains(id.getPath());
    }

    @Override
    protected boolean skipRendering(BlockState state, BlockState neighbor, Direction side) {
        return transparent && neighbor.is(this) || super.skipRendering(state, neighbor, side);
    }

    @Override
    protected boolean propagatesSkylightDown(BlockState state, BlockGetter level, BlockPos pos) {
        return transparent || super.propagatesSkylightDown(state, level, pos);
    }

    @Override
    protected float getShadeBrightness(BlockState state, BlockGetter level, BlockPos pos) {
        return transparent ? 1 : super.getShadeBrightness(state, level, pos);
    }
}

