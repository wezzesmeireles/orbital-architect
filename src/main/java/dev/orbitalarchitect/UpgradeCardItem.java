package dev.orbitalarchitect;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

import java.util.List;

final class UpgradeCardItem extends Item {
    private final int targetTier;

    UpgradeCardItem(Properties properties, int targetTier) {
        super(properties);
        this.targetTier = targetTier;
    }

    int targetTier() {
        return targetTier;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        var style = AltarTierStyle.from(targetTier);
        tooltip.add(Component.translatable("item.orbital_architect.card_step", targetTier - 1, targetTier)
                .withColor(style.accent));
        tooltip.add(Component.translatable("item.orbital_architect.card_install"));
    }
}
