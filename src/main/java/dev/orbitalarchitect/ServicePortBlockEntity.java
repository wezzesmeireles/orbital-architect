package dev.orbitalarchitect;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.energy.IEnergyStorage;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemStackHandler;
import net.neoforged.neoforge.items.ItemHandlerHelper;
import net.neoforged.neoforge.items.wrapper.RangedWrapper;

import java.util.UUID;

final class ServicePortBlockEntity extends BlockEntity {
    private final ItemStackHandler input = new Buffer();
    private final ItemStackHandler output = new Buffer();
    private final IItemHandler supply = new RangedWrapper(input, 0, input.getSlots()) {
        @Override public ItemStack extractItem(int slot, int count, boolean simulate) { return ItemStack.EMPTY; }
    };
    private final IItemHandler returns = new RangedWrapper(output, 0, output.getSlots()) {
        @Override public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) { return stack; }
    };
    private UUID orbit;
    private BlockPos anchor;

    private final class Buffer extends ItemStackHandler {
        Buffer() { super(9); }
        @Override protected void onContentsChanged(int slot) { setChanged(); }
    }

    ServicePortBlockEntity(BlockPos pos, BlockState state) {
        super(OrbitalArchitect.SERVICE_ENTITY.get(), pos, state);
    }

    Direction outputSide() { return getBlockState().getValue(BlockStateProperties.HORIZONTAL_FACING); }

    IItemHandler automation(Direction side) { return side == outputSide() ? returns : supply; }

    IItemHandler input() { return input; }

    IItemHandler output() { return output; }

    static IItemHandler neighborItems(ServerLevel level, BlockPos pos, Direction side) {
        var neighbor = pos.relative(side);
        if (!level.hasChunkAt(neighbor) || level.getBlockState(neighbor).getBlock() instanceof ServicePortBlock) return null;
        return level.getCapability(Capabilities.ItemHandler.BLOCK, neighbor, side.getOpposite());
    }

    void transfer(ServerLevel level, OrbAltarBlockEntity altar) {
        var side = outputSide();
        var neighbor = worldPosition.relative(side);
        if (!level.hasChunkAt(neighbor)) return;
        var block = level.getBlockState(neighbor).getBlock();
        if (block instanceof ServicePortBlock || block instanceof DroneDockBlock || block instanceof OrbAltarBlock) return;
        var receiver = level.getCapability(Capabilities.EnergyStorage.BLOCK, neighbor, side.getOpposite());
        if (receiver != null && receiver.canReceive()) altar.pushEnergy(receiver, level.getGameTime());
        if (level.getGameTime() % 5 != 0) return;
        var container = neighborItems(level, worldPosition, side);
        if (container == null) return;
        for (int slot = 0; slot < output.getSlots(); slot++) {
            var stored = output.getStackInSlot(slot);
            if (stored.isEmpty()) continue;
            var remaining = ItemHandlerHelper.insertItemStacked(container, stored.copy(), false);
            if (remaining.getCount() != stored.getCount()) output.setStackInSlot(slot, remaining);
            break;
        }
    }

    boolean belongsTo(OrbAltarBlockEntity altar) { return altar.dockId().equals(orbit); }

    boolean waitingForOrbit(ServerLevel level) { return anchor != null && !level.hasChunkAt(anchor); }

    void bind(OrbAltarBlockEntity altar) {
        if (belongsTo(altar) && altar.getBlockPos().equals(anchor)) return;
        orbit = altar.dockId();
        anchor = altar.getBlockPos();
        if (level != null) level.invalidateCapabilities(worldPosition);
        setChanged();
    }

    OrbAltarBlockEntity owner(ServerLevel level) {
        if (anchor == null) return null;
        if (level.hasChunkAt(anchor) && level.getBlockEntity(anchor) instanceof OrbAltarBlockEntity altar
                && !altar.isRemoved() && belongsTo(altar) && altar.hasServicePort(worldPosition)) return altar;
        return OrbAltarBlockEntity.findOrbit(level, worldPosition,
                altar -> belongsTo(altar) && altar.hasServicePort(worldPosition));
    }

    IEnergyStorage energy(Direction side) {
        return new IEnergyStorage() {
            private OrbAltarBlockEntity orbit() { return level instanceof ServerLevel server ? owner(server) : null; }

            @Override public int receiveEnergy(int amount, boolean simulate) {
                var altar = orbit();
                return altar != null && canReceive() ? altar.energyInput().receiveEnergy(amount, simulate) : 0;
            }
            @Override public int extractEnergy(int amount, boolean simulate) {
                var altar = orbit();
                return altar != null && canExtract() ? altar.exportEnergy(amount, simulate) : 0;
            }
            @Override public int getEnergyStored() {
                var altar = orbit();
                return altar == null ? 0 : altar.energyInput().getEnergyStored();
            }
            @Override public int getMaxEnergyStored() {
                var altar = orbit();
                return altar == null ? 0 : altar.energyInput().getMaxEnergyStored();
            }
            @Override public boolean canExtract() { return side == null || side == outputSide(); }
            @Override public boolean canReceive() { return side == null || side != outputSide(); }
        };
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        if (orbit != null && anchor != null) {
            tag.putUUID("Orbit", orbit);
            tag.putLong("Anchor", anchor.asLong());
        }
        tag.put("Input", input.serializeNBT(registries));
        tag.put("Output", output.serializeNBT(registries));
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        orbit = tag.hasUUID("Orbit") ? tag.getUUID("Orbit") : null;
        anchor = orbit == null ? null : BlockPos.of(tag.getLong("Anchor"));
        input.deserializeNBT(registries, tag.getCompound("Input"));
        output.deserializeNBT(registries, tag.getCompound("Output"));
    }
}
