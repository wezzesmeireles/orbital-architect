package dev.orbitalarchitect;

import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

final class DroneDockBlockEntity extends BlockEntity {
    private UUID fleet;
    private BlockPos anchor;

    DroneDockBlockEntity(BlockPos pos, BlockState state) {
        super(OrbitalArchitect.DOCK_ENTITY.get(), pos, state);
    }

    BlockPos anchor() { return anchor; }

    boolean belongsTo(OrbAltarBlockEntity altar) { return altar.dockId().equals(fleet); }

    void link(ServerLevel level, OrbAltarBlockEntity altar) {
        if (belongsTo(altar) && altar.getBlockPos().equals(anchor)) return;
        fleet = altar.dockId();
        anchor = altar.getBlockPos();
        level.invalidateCapabilities(worldPosition);
        setChanged();
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        if (fleet != null && anchor != null) {
            tag.putUUID("Fleet", fleet);
            tag.putLong("Altar", anchor.asLong());
        }
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        fleet = tag.hasUUID("Fleet") ? tag.getUUID("Fleet") : null;
        anchor = fleet == null ? null : BlockPos.of(tag.getLong("Altar"));
    }
}
