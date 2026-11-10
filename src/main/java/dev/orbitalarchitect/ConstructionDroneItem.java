package dev.orbitalarchitect;

import java.util.List;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

final class ConstructionDroneItem extends Item {
    final DroneTier tier;

    ConstructionDroneItem(Properties properties, DroneTier tier) {
        super(properties);
        this.tier = tier;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("item.orbital_architect.drone_install", tier.level));
        tooltip.add(Component.translatable("item.orbital_architect.drone_speed",
                (int) (tier.shotSpeed * 20), tier.blocks));
    }
}
