package dev.orbitalarchitect;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

final class ConstructionDrone extends Entity {
    enum CargoPhase { EMPTY, PICKUP, CARRY, PLACE }

    private static final EntityDataAccessor<Integer> TIER =
            SynchedEntityData.defineId(ConstructionDrone.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<ItemStack> CARGO =
            SynchedEntityData.defineId(ConstructionDrone.class, EntityDataSerializers.ITEM_STACK);
    private static final EntityDataAccessor<Integer> PHASE =
            SynchedEntityData.defineId(ConstructionDrone.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Long> PHASE_START =
            SynchedEntityData.defineId(ConstructionDrone.class, EntityDataSerializers.LONG);
    private static final EntityDataAccessor<Boolean> DOCKED =
            SynchedEntityData.defineId(ConstructionDrone.class, EntityDataSerializers.BOOLEAN);
    private boolean moving;
    private double targetX, targetY, targetZ;
    private float targetYaw, targetPitch;
    private BlockPos anchor;
    private int slot;

    ConstructionDrone(EntityType<? extends ConstructionDrone> type, Level level) {
        super(type, level);
        setNoGravity(true);
        noPhysics = true;
    }

    void setTier(DroneTier tier) { entityData.set(TIER, tier.level); }

    DroneTier tier() { return DroneTier.at(entityData.get(TIER)); }

    void link(BlockPos altar, int slot) {
        anchor = altar;
        this.slot = slot;
    }

    @Override
    public boolean isPickable() { return true; }

    @Override
    public InteractionResult interact(Player player, InteractionHand hand) {
        if (player instanceof ServerPlayer server && !server.isSpectator() && anchor != null
                && level().hasChunkAt(anchor) && level().getBlockEntity(anchor) instanceof OrbAltarBlockEntity altar
                && altar.isDroneInReach(server, slot)) {
            PlannerNetwork.openDrone(server, altar, slot);
        }
        return InteractionResult.sidedSuccess(level().isClientSide);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(TIER, 1).define(CARGO, ItemStack.EMPTY)
                .define(PHASE, CargoPhase.EMPTY.ordinal()).define(PHASE_START, 0L).define(DOCKED, false);
    }

    void setDocked(boolean docked) { entityData.set(DOCKED, docked); }

    boolean isDocked() { return entityData.get(DOCKED); }

    void setCargo(ItemStack stack, CargoPhase phase, long start) {
        if (cargoPhase() == phase && phaseStart() == start) return;
        entityData.set(CARGO, stack);
        entityData.set(PHASE, phase.ordinal());
        entityData.set(PHASE_START, start);
    }

    ItemStack cargo() { return entityData.get(CARGO); }

    CargoPhase cargoPhase() { return CargoPhase.values()[entityData.get(PHASE)]; }

    long phaseStart() { return entityData.get(PHASE_START); }

    @Override
    public void lerpTo(double x, double y, double z, float yaw, float pitch, int steps) {
        targetX = x;
        targetY = y;
        targetZ = z;
        targetYaw = yaw;
        targetPitch = pitch;
        moving = true;
    }

    @Override
    public double lerpTargetX() { return moving ? targetX : getX(); }

    @Override
    public double lerpTargetY() { return moving ? targetY : getY(); }

    @Override
    public double lerpTargetZ() { return moving ? targetZ : getZ(); }

    @Override
    public float lerpTargetYRot() { return moving ? targetYaw : getYRot(); }

    @Override
    public float lerpTargetXRot() { return moving ? targetPitch : getXRot(); }

    @Override
    public void tick() {
        super.tick();
        if (level().isClientSide && moving) {
            lerpPositionAndRotationStep(1, targetX, targetY, targetZ, targetYaw, targetPitch);
            moving = false;
        }
    }

    @Override
    public boolean isAttackable() { return false; }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {}

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {}
}
