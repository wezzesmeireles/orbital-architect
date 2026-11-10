package dev.orbitalarchitect;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;

final class AirlockItem extends BlockItem {
    private final AirlockBlock airlock;

    AirlockItem(AirlockBlock airlock, Properties properties) {
        super(airlock, properties);
        this.airlock = airlock;
    }

    @Override
    protected boolean placeBlock(BlockPlaceContext context, BlockState state) {
        if (!airlock.canAssemble(context, state)) {
            if (!context.getLevel().isClientSide && context.getPlayer() != null) {
                context.getPlayer().displayClientMessage(
                        Component.translatable("block.orbital_architect.airlock_space"), true);
            }
            return false;
        }
        return context.getLevel().isClientSide ? super.placeBlock(context, state)
                : airlock.assemble(context.getLevel(), context.getClickedPos(), state);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("item.orbital_architect.airlock_install"));
        tooltip.add(Component.translatable("item.orbital_architect.airlock_use"));
    }
}
