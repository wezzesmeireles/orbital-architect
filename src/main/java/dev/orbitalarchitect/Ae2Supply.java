package dev.orbitalarchitect;

import appeng.api.AECapabilities;
import appeng.api.config.Actionable;
import appeng.api.networking.GridFlags;
import appeng.api.networking.GridHelper;
import appeng.api.networking.IManagedGridNode;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEItemKey;
import appeng.api.storage.StorageHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.items.IItemHandler;

final class Ae2Supply {
    private final OrbAltarBlockEntity altar;
    private final IManagedGridNode node;
    private final IActionSource source;

    Ae2Supply(OrbAltarBlockEntity altar, CompoundTag data) {
        this.altar = altar;
        node = GridHelper.createManagedNode(altar, (host, changed) -> host.setChanged())
                .setInWorldNode(true)
                .setFlags(GridFlags.REQUIRE_CHANNEL)
                .setIdlePowerUsage(1.0)
                .setVisualRepresentation(altar.getBlockState().getBlock());
        node.loadFromNBT(data);
        source = IActionSource.ofMachine(node::getNode);
    }

    static void register(RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(AECapabilities.IN_WORLD_GRID_NODE_HOST, OrbitalArchitect.ALTAR_ENTITY.get(),
                (altar, context) -> altar.tier() >= 3 ? direction -> {
                    var supply = altar.ae2Supply();
                    return supply == null ? null : supply.node.getNode();
                } : null);
    }

    void create(ServerLevel level) { node.create(level, altar.getBlockPos()); }
    void save(CompoundTag data) { node.saveToNBT(data); }
    void destroy() { node.destroy(); }

    int available(ItemStack material, int limit) {
        var grid = node.getGrid();
        if (grid == null || !node.isActive() || limit <= 0) return 0;
        return (int) grid.getStorageService().getInventory().extract(
                AEItemKey.of(material), limit, Actionable.SIMULATE, source);
    }

    // ME storage is key-based. Expose a virtual slot for the exact material the job needs.
    IItemHandler forMaterial(ItemStack material) {
        var grid = node.getGrid();
        if (grid == null || !node.isActive()) return null;
        var storage = grid.getStorageService().getInventory();
        var key = AEItemKey.of(material);
        return new IItemHandler() {
            @Override
            public int getSlots() { return 1; }

            @Override
            public ItemStack getStackInSlot(int slot) {
                return extractItem(slot, material.getMaxStackSize(), true);
            }

            @Override
            public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
                if (!isItemValid(slot, stack) || stack.isEmpty()) return stack;
                int accepted = (int) storage.insert(key, stack.getCount(), mode(simulate), source);
                return stack.copyWithCount(stack.getCount() - accepted);
            }

            @Override
            public ItemStack extractItem(int slot, int amount, boolean simulate) {
                if (slot != 0 || amount <= 0) return ItemStack.EMPTY;
                int extracted = (int) StorageHelper.poweredExtraction(grid.getEnergyService(), storage,
                        key, Math.min(amount, material.getMaxStackSize()), source, mode(simulate));
                return material.copyWithCount(extracted);
            }

            @Override
            public int getSlotLimit(int slot) { return slot == 0 ? material.getMaxStackSize() : 0; }

            @Override
            public boolean isItemValid(int slot, ItemStack stack) {
                return slot == 0 && ItemStack.isSameItemSameComponents(material, stack);
            }
        };
    }

    private static Actionable mode(boolean simulate) {
        return simulate ? Actionable.SIMULATE : Actionable.MODULATE;
    }
}

