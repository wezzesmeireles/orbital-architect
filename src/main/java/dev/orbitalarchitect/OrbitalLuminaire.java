package dev.orbitalarchitect;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

final class OrbitalLuminaire extends Block {
    OrbitalLuminaire(Properties properties) {
        super(properties.lightLevel(state -> state.getValue(BlockStateProperties.LIT) ? 15 : 0));
        registerDefaultState(stateDefinition.any().setValue(BlockStateProperties.LIT, true));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(BlockStateProperties.LIT);
    }

    @Override
    protected void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        if (!state.getValue(BlockStateProperties.LIT)) {
            level.setBlock(pos, state.setValue(BlockStateProperties.LIT, true), Block.UPDATE_CLIENTS);
        }
    }
}
