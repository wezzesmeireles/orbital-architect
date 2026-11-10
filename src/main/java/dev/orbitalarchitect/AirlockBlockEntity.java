package dev.orbitalarchitect;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

final class AirlockBlockEntity extends BlockEntity {
    private long motionStart;
    private BlockState cladding;
    private String sign = "";
    private int signColor = 0xFFFFFF;
    private long readyUntil;

    AirlockBlockEntity(BlockPos pos, BlockState state) {
        super(OrbitalArchitect.AIRLOCK_ENTITY.get(), pos, state);
    }

    void startMotion(long tick) {
        motionStart = tick;
        setChanged();
        sendAppearance();
    }

    long motionStart() { return motionStart; }

    BlockState cladding() { return cladding; }

    String sign() { return sign; }
    int signColor() { return signColor; }
    long readyUntil() { return readyUntil; }

    void setSign(String sign, int color) {
        if (this.sign.equals(sign) && signColor == color) return;
        this.sign = sign;
        signColor = color;
        setChanged();
        sendAppearance();
    }

    void signalReady(long until) {
        readyUntil = until;
        sendAppearance();
    }

    void setCladding(BlockState cladding) {
        this.cladding = cladding;
        setChanged();
        sendAppearance();
    }

    private void sendAppearance() {
        if (level != null && !level.isClientSide) {
            var state = getBlockState();
            level.sendBlockUpdated(worldPosition, state, state, Block.UPDATE_CLIENTS);
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putLong("MotionStart", motionStart);
        if (!sign.isEmpty()) {
            tag.putString("Sign", sign);
            tag.putInt("SignColor", signColor);
        }
        if (readyUntil > 0) tag.putLong("ReadyUntil", readyUntil);
        if (cladding != null) tag.put("Cladding", NbtUtils.writeBlockState(cladding));
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        motionStart = tag.getLong("MotionStart");
        sign = tag.getString("Sign");
        signColor = tag.contains("SignColor") ? tag.getInt("SignColor") : 0xFFFFFF;
        readyUntil = tag.getLong("ReadyUntil");
        cladding = tag.contains("Cladding")
                ? NbtUtils.readBlockState(registries.lookupOrThrow(Registries.BLOCK), tag.getCompound("Cladding")) : null;
        if (cladding != null && cladding.isAir()) cladding = null;
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        return saveWithoutMetadata(registries);
    }

    @Override
    public ClientboundBlockEntityDataPacket getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }
}
