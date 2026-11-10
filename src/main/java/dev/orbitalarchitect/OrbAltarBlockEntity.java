package dev.orbitalarchitect;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.neoforged.neoforge.common.CommonHooks;
import net.neoforged.neoforge.common.util.BlockSnapshot;
import net.neoforged.neoforge.event.EventHooks;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.energy.EnergyStorage;
import net.neoforged.neoforge.energy.IEnergyStorage;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemHandlerHelper;

import java.io.IOException;
import java.util.*;

public final class OrbAltarBlockEntity extends net.minecraft.world.level.block.entity.BlockEntity {
    public enum State { IDLE, PREVIEW, EXCAVATING, BUILDING, UPGRADING }

    private static final TagKey<Block> EXCAVATABLE =
            TagKey.create(Registries.BLOCK, OrbitalArchitect.id("excavatable"));
    static final int ENERGY_PER_BLOCK = 20;
    private static final String BATTERY_WAIT = "Drones voltando ou recarregando. A partida exige 100% de bateria.";
    private static final int MAX_HUB_LIFT = 4;
    private static final int CABLE_PORT_BELOW_DOOR = 3;
    private static final Map<ServerLevel, Set<OrbAltarBlockEntity>> LOADED_ORBITS = new WeakHashMap<>();
    private final AltarEnergy energy = new AltarEnergy();
    private final ConstructionFleet fleet;
    private final List<BlockPos> docks = new ArrayList<>();
    private final Set<BlockPos> servicePorts = new LinkedHashSet<>();
    private final int[] outputUsage = new int[20];
    private final long[] outputUsageAt = new long[20];
    private final Flow inputFlow = new Flow();
    private final Flow chargingFlow = new Flow();
    private final Flow travelFlow = new Flow();
    private UUID dockId = UUID.randomUUID();
    private boolean docksInitialized;
    private boolean dockLightRefresh;
    private boolean transferring;
    private boolean checkedRecovery;
    private UUID placedBy;

    private final class AltarEnergy extends EnergyStorage {
        AltarEnergy() { super(10_000, 200, 0); }
        @Override
        public int receiveEnergy(int amount, boolean simulate) {
            int received = super.receiveEnergy(amount, simulate);
            if (!simulate && received > 0) {
                if (energy >= ENERGY_PER_BLOCK && energy - received < ENERGY_PER_BLOCK) dockLightRefresh = true;
                if (level != null) inputFlow.record(received, level.getGameTime());
                setChanged();
            }
            return received;
        }
        int takeCharge(int amount) {
            int taken = Math.min(energy, amount);
            if (taken == 0) return 0;
            int previous = energy;
            energy -= taken;
            if (previous >= ENERGY_PER_BLOCK && energy < ENERGY_PER_BLOCK) dockLightRefresh = true;
            setChanged();
            return taken;
        }
        void returnCharge(int amount) {
            int before = energy;
            energy = Math.min(capacity, energy + amount);
            if (before < ENERGY_PER_BLOCK && energy >= ENERGY_PER_BLOCK) dockLightRefresh = true;
        }
    }

    private static final class Flow {
        private final int[] amounts = new int[20];
        private final long[] times = new long[20];

        void record(int amount, long time) {
            if (amount == 0) return;
            int slot = (int) (time % amounts.length);
            if (times[slot] != time) amounts[slot] = 0;
            times[slot] = time;
            amounts[slot] += amount;
        }

        int rate(long time) {
            int sum = 0;
            for (int i = 0; i < amounts.length; i++) if (time - times[i] < amounts.length) sum += amounts[i];
            return sum;
        }
    }

    private record Work(BlockPos pos, BlockState target, BlockState expected, CorridorPlan.Priority stage) {
        Work(BlockPos pos, BlockState target, BlockState expected) {
            this(pos, target, expected, CorridorPlan.Priority.SHELL);
        }
    }
    private record Room(ResourceLocation blueprint, Map<BlockPos, BlockState> shell, Map<Direction, BlockPos> ports,
                        EnumSet<Direction> occupied, DomePalette palette) {}
    private record RoomLink(int from, int to, BlockPos start, BlockPos end) {}
    private record PersonalPlan(ResourceLocation blueprint, String name, DomePalette palette) {}
    private record GalleryLink(BlockPos existing, BlockPos proposed, BlockState existingPanel, BlockState proposedPanel) {
        long[] positions() { return new long[] {existing.asLong(), proposed.asLong()}; }
        BlockState expected(BlockPos pos) { return pos.equals(existing) ? existingPanel : proposedPanel; }
    }

    private final List<Room> rooms = new ArrayList<>();
    private final List<RoomLink> roomLinks = new ArrayList<>();
    private final Map<Integer, String> roomNames = new HashMap<>();
    private final Map<Integer, Integer> roomColors = new HashMap<>();
    private final List<PersonalPlan> personalPlans = new ArrayList<>();
    static final int[] ROOM_COLORS = {0x62D9BD, 0x78BFFF, 0xFFBE68, 0xC6A0F6, 0xFF8E84};
    private DomePalette favoritePalette = DomePalette.EMPTY;
    private final Map<ResourceLocation, Integer> shopping = new LinkedHashMap<>();
    private String shoppingName = "";
    private int energyReserve;
    private List<BlockPos> routeLights = List.of();
    private long routeUntil;
    private final List<Work> job = new ArrayList<>();
    private final Map<BlockPos, BlockState> built = new LinkedHashMap<>();
    private Map<Direction, BlockPos> ports = Map.of();
    private State state = State.IDLE;
    static final ResourceLocation HUB = OrbitalArchitect.id("hub");
    private static final ResourceLocation STARTER = OrbitalArchitect.id("starter");
    private static final ResourceLocation CAPSULE = OrbitalArchitect.id("capsule");
    private ResourceLocation jobBlueprint = STARTER;
    private DomePalette preferredPalette = DomePalette.standard();
    private DomePalette jobPalette = DomePalette.EMPTY;
    private int cursor;
    private final BitSet completed = new BitSet();
    private int anchor = -1;
    private Direction dock;
    private boolean upgrade;
    private boolean repair;
    private DomePalette.Part renovation;
    private CorridorPlan.Priority workPriority = CorridorPlan.Priority.SHELL;
    private CompoundTag siteSurvey = new CompoundTag();
    private UUID planner;
    private boolean creativeJob;
    private String waiting = "";
    private BlockPos blocked;
    private GalleryLink galleryLink;
    private boolean connectGallery;
    private long galleryRetryAt;
    private Ae2Supply ae2;
    private CompoundTag ae2Data = new CompoundTag();

    Ae2Supply ae2Supply() { return ae2; }

    @Override
    public void onLoad() {
        super.onLoad();
        if (level instanceof ServerLevel server) LOADED_ORBITS.computeIfAbsent(server, ignored -> new HashSet<>()).add(this);
    }

    private void unregisterOrbit() {
        if (!(level instanceof ServerLevel server)) return;
        var loaded = LOADED_ORBITS.get(server);
        if (loaded == null) return;
        loaded.remove(this);
        if (loaded.isEmpty()) LOADED_ORBITS.remove(server);
        servicePorts.forEach(server::invalidateCapabilities);
    }

    @Override
    public void setRemoved() {
        unregisterOrbit();
        invalidateDocks();
        fleet.stop();
        disconnectAe2();
        super.setRemoved();
    }

    @Override
    public void onChunkUnloaded() {
        unregisterOrbit();
        invalidateDocks();
        fleet.stop();
        disconnectAe2();
        super.onChunkUnloaded();
    }

    private void disconnectAe2() {
        if (ae2 == null) return;
        ae2.save(ae2Data);
        ae2.destroy();
        ae2 = null;
    }

    OrbAltarBlockEntity(BlockPos pos, BlockState state) {
        super(OrbitalArchitect.ALTAR_ENTITY.get(), pos, state);
        fleet = new ConstructionFleet(pos);
    }

    int tier() { return ((OrbAltarBlock) getBlockState().getBlock()).tier(); }

    EnergyStorage energyInput() { return energy; }

    int exportEnergy(int amount, boolean simulate) {
        int offered = Math.max(0, Math.min(200, Math.min(amount, energy.getEnergyStored() - energyReserve)));
        if (simulate || offered == 0) return offered;
        int used = energy.takeCharge(offered);
        if (level != null) recordOutput(used, level.getGameTime());
        return used;
    }

    void pushEnergy(IEnergyStorage receiver, long time) {
        int offered = exportEnergy(80, true);
        if (offered == 0) return;
        int sent = energy.takeCharge(Math.clamp(receiver.receiveEnergy(offered, true), 0, offered));
        if (sent == 0) return;
        int accepted = Math.clamp(receiver.receiveEnergy(sent, false), 0, sent);
        energy.returnCharge(sent - accepted);
        recordOutput(accepted, time);
    }

    private void recordOutput(int amount, long time) {
        int index = (int) (time % outputUsage.length);
        if (outputUsageAt[index] != time) outputUsage[index] = 0;
        outputUsageAt[index] = time;
        outputUsage[index] += amount;
    }

    private int outputConsumption(long time) {
        int total = 0;
        for (int i = 0; i < outputUsage.length; i++) if (time - outputUsageAt[i] < outputUsage.length) total += outputUsage[i];
        return total;
    }

    static OrbAltarBlockEntity findOrbit(ServerLevel level, BlockPos position,
                                        java.util.function.Predicate<OrbAltarBlockEntity> accepts) {
        return LOADED_ORBITS.getOrDefault(level, Set.of()).stream()
                .filter(altar -> !altar.isRemoved() && accepts.test(altar))
                .min(Comparator.comparingDouble((OrbAltarBlockEntity altar) -> position.distSqr(altar.worldPosition))
                        .thenComparing(OrbAltarBlockEntity::getBlockPos))
                .orElse(null);
    }

    boolean acceptsPort(BlockPos pos) {
        return isAltarSpace(pos) || roomAt(pos) >= 0;
    }

    private int roomAt(BlockPos pos) {
        for (int i = 0; i < rooms.size(); i++) {
            int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
            int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
            for (var block : rooms.get(i).shell().keySet()) {
                if (block.getZ() != pos.getZ()) continue;
                minX = Math.min(minX, block.getX());
                maxX = Math.max(maxX, block.getX());
                minY = Math.min(minY, block.getY());
                maxY = Math.max(maxY, block.getY());
            }
            if (pos.getX() >= minX && pos.getX() <= maxX && pos.getY() > minY && pos.getY() < maxY) return i;
        }
        return -1;
    }

    boolean canUseTravel(ServerPlayer player) {
        return player.level() == level && !player.isSpectator() && !rooms.isEmpty()
                && (player.canInteractWithBlock(worldPosition, 1) || roomAt(player.blockPosition()) >= 0);
    }

    private BlockPos roomCenter(Room room) {
        return new BlockPos((int) Math.round(room.ports().values().stream().mapToInt(BlockPos::getX).average().orElse(worldPosition.getX())),
                (int) Math.round(room.ports().entrySet().stream().filter(port -> port.getKey().getAxis().isHorizontal())
                        .mapToInt(port -> port.getValue().getY()).average().orElse(worldPosition.getY())),
                (int) Math.round(room.ports().values().stream().mapToInt(BlockPos::getZ).average().orElse(worldPosition.getZ())));
    }

    private static int travelCost(BlockPos from, BlockPos center) {
        return 120 + (int) Math.min(1080, 2 * Math.ceil(Math.sqrt(from.distSqr(center))));
    }

    String travel(ServerPlayer player, int destination) {
        if (!canUseTravel(player) || destination < 0 || destination >= rooms.size()) return "Selecione uma sala deste Orbit.";
        if (player.isPassenger() || player.isSleeping()) return "Desmonte ou levante antes de viajar.";
        var level = player.serverLevel();
        var source = player.blockPosition();
        if (destination == roomAt(source)) return "Você já está nesta sala.";
        long time = level.getGameTime();
        if (player.getPersistentData().getLong("OrbitalTravelUntil") > time) return "Aguarde o estabilizador do teleporte.";
        var room = rooms.get(destination);
        var center = roomCenter(room);
        if (!level.hasChunkAt(center)) return "A sala de destino está fora da área carregada.";
        int cost = player.isCreative() ? 0 : travelCost(source, center);
        if (cost > Math.max(0, energy.getEnergyStored() - energyReserve)) return "Teleporte requer " + cost + " FE acima da reserva.";
        for (var candidate : BlockPos.withinManhattan(center, 6, 0, 6)) {
            var floor = candidate.below();
            if (!validPosition(level, candidate.above()) || !room.shell().containsKey(floor)
                    || !level.mayInteract(player, candidate) || !level.mayInteract(player, source)
                    || !level.getBlockState(candidate).isAir() || !level.getBlockState(candidate.above()).isAir()) continue;
            var support = level.getBlockState(floor);
            if (!support.isFaceSturdy(level, floor, Direction.UP) || !support.getFluidState().isEmpty()
                    || support.is(Blocks.MAGMA_BLOCK) || support.is(Blocks.CACTUS)
                    || support.is(Blocks.CAMPFIRE) || support.is(Blocks.SOUL_CAMPFIRE)) continue;
            var arrival = Vec3.atBottomCenterOf(candidate).add(0, .02, 0);
            if (!level.noCollision(player, player.getBoundingBox().move(arrival.subtract(player.position())))) continue;
            energy.takeCharge(cost);
            travelFlow.record(cost, time);
            player.getPersistentData().putLong("OrbitalTravelUntil", time + 40);
            travelEffect(level, player.position());
            player.fallDistance = 0;
            player.setDeltaMovement(Vec3.ZERO);
            player.connection.teleport(arrival.x, arrival.y, arrival.z, player.getYRot(), player.getXRot());
            travelEffect(level, arrival);
            return "Teleporte concluído · " + roomName(level, destination) + " · " + cost + " FE.";
        }
        return "Desembarque bloqueado. Libere o piso central da sala.";
    }

    private static void travelEffect(ServerLevel level, Vec3 pos) {
        level.sendParticles(ParticleTypes.PORTAL, pos.x, pos.y + .85, pos.z, 28, .28, .6, .28, .22);
        level.playSound(null, pos.x, pos.y, pos.z, SoundEvents.CHORUS_FRUIT_TELEPORT, SoundSource.PLAYERS, .7f, 1.15f);
    }

    boolean hasServicePort(BlockPos pos) { return servicePorts.contains(pos); }

    boolean attachServicePort(ServicePortBlockEntity port) {
        if (!servicePorts.contains(port.getBlockPos()) && servicePorts.size() >= 32) return false;
        servicePorts.add(port.getBlockPos());
        port.bind(this);
        setChanged();
        return true;
    }

    void detachServicePort(BlockPos pos) {
        if (servicePorts.remove(pos)) setChanged();
    }

    boolean isDroneInReach(ServerPlayer player, int slot) { return fleet.isDroneInReach(player, slot); }

    UUID dockId() { return dockId; }

    boolean hasDock(BlockPos pos) { return docks.contains(pos); }

    String dockTask(BlockPos pos) { return fleet.dockTask(pos, tier()); }

    boolean attachDock(ServerLevel level, DroneDockBlockEntity dock) {
        var pos = dock.getBlockPos();
        if (!DroneDockBlock.inRange(worldPosition, pos)) return false;
        var owner = DroneDockBlock.owner(level, dock);
        if (owner != null && owner != this) return false;
        if (!docks.contains(pos)) {
            if (docks.size() >= fleet.capacity(tier())) return false;
            docks.add(pos);
        }
        dock.link(level, this);
        refreshDocks(level);
        setChanged();
        return true;
    }

    void detachDock(ServerLevel level, BlockPos pos) {
        if (!docks.remove(pos)) return;
        level.invalidateCapabilities(pos);
        refreshDocks(level);
        setChanged();
    }

    private void invalidateDocks() {
        docksInitialized = false;
        if (level instanceof ServerLevel server) docks.forEach(server::invalidateCapabilities);
    }

    private void refreshDocks(ServerLevel level) {
        dockLightRefresh = false;
        if (!docksInitialized) {
            docks.forEach(level::invalidateCapabilities);
            docksInitialized = true;
        }
        boolean changed = docks.removeIf(pos -> !DroneDockBlock.inRange(worldPosition, pos) || level.hasChunkAt(pos)
                && (!level.getBlockState(pos).is(OrbitalArchitect.DRONE_DOCK.get())
                || !(level.getBlockEntity(pos) instanceof DroneDockBlockEntity dock) || !dock.belongsTo(this)));
        if (changed) setChanged();
        var landingPads = new ArrayList<BlockPos>();
        for (var pos : docks) {
            if (!level.hasChunkAt(pos) || !(level.getBlockEntity(pos) instanceof DroneDockBlockEntity dock)) continue;
            dock.link(level, this);
            boolean ready = DroneDockBlock.canLand(level, pos);
            if (ready) landingPads.add(pos);
            var block = level.getBlockState(pos);
            var operation = renovation == null ? ConstructionFleet.Operation.BUILD : ConstructionFleet.Operation.SWAP;
            boolean lit = ready && (energy.getEnergyStored() - energyReserve >= fleet.minCost(tier(), operation)
                    || fleet.canOperate(tier(), operation));
            int task = ready ? fleet.dockMode(pos) : 0;
            if (block.getValue(BlockStateProperties.LIT) != lit || block.getValue(DroneDockBlock.MODE) != task) {
                level.setBlock(pos, block.setValue(BlockStateProperties.LIT, lit)
                        .setValue(DroneDockBlock.MODE, task), Block.UPDATE_CLIENTS);
            }
        }
        fleet.setLandingPads(landingPads);
    }

    boolean isIdle() { return state == State.IDLE; }

    void markPlacedBy(ServerPlayer player) { placedBy = player.getUUID(); }

    private boolean hasProject() {
        return !rooms.isEmpty() || !docks.isEmpty() || !servicePorts.isEmpty()
                || state == State.EXCAVATING || state == State.BUILDING || state == State.UPGRADING;
    }

    private void recoverOnFirstTick(ServerLevel level) {
        if (checkedRecovery) return;
        checkedRecovery = true;
        if (isIdle() && !hasProject() && AltarRecovery.restore(level, this) && placedBy != null) {
            var player = level.getServer().getPlayerList().getPlayer(placedBy);
            if (player != null) {
                player.displayClientMessage(Component.translatable("block.orbital_architect.altar_restored"), true);
            }
        }
        placedBy = null;
    }

    String installDrone(ServerPlayer player, ItemStack stack, DroneTier drone) {
        if (!isIdle()) return "Conclua ou cancele o projeto para alterar a frota.";
        if (player.isSpectator() || !player.serverLevel().mayInteract(player, worldPosition)) return "Altar protegido.";
        if (drone.level > tier()) return "Este drone precisa de um altar T" + drone.level + " ou superior.";
        if (!fleet.install(drone, tier())) return "Frota cheia para este tier de altar.";
        stack.consume(1, player);
        player.getInventory().setChanged();
        setChanged();
        return "Drone T" + drone.level + " instalado.";
    }

    String recallDrone(ServerPlayer player, int slot) {
        if (!isIdle()) return "Conclua ou cancele o projeto para alterar a frota.";
        if (!player.serverLevel().mayInteract(player, worldPosition)) return "Altar protegido.";
        var drone = fleet.withdraw(slot);
        if (drone == null) return "Selecione um drone adicional.";
        player.getInventory().placeItemBackInInventory(new ItemStack(OrbitalArchitect.DRONES.get(drone.tier().level - 1).get()));
        for (var upgrade : drone.upgrades()) player.getInventory().placeItemBackInInventory(upgrade.stack());
        energy.returnCharge(drone.charge());
        setChanged();
        return "Drone T" + drone.tier().level + " recolhido.";
    }

    String installDroneUpgrade(ServerPlayer player, int slot, DroneUpgrade upgrade) {
        if (!isIdle()) return "Encerre a operação para modificar os drones.";
        if (!player.serverLevel().mayInteract(player, worldPosition)) return "Altar protegido.";
        var item = OrbitalArchitect.DRONE_UPGRADES.get(upgrade.ordinal()).get();
        var inventory = player.getInventory();
        var stack = ItemStack.EMPTY;
        if (!player.isCreative()) {
            for (int i = 0; i < inventory.getContainerSize(); i++) {
                if (inventory.getItem(i).is(item)) {
                    stack = inventory.getItem(i);
                    break;
                }
            }
            if (stack.isEmpty()) return "Tenha o módulo de " + upgrade.label + " no inventário.";
        }
        String error = fleet.upgrade(slot, tier(), upgrade);
        if (!error.isEmpty()) return error;
        stack.consume(1, player);
        inventory.setChanged();
        setChanged();
        return "Drone " + (slot + 1) + " · módulo de " + upgrade.label + " instalado.";
    }

    void preserveOnRemoval(ServerLevel level) {
        if (transferring) return;
        for (var pos : docks) {
            if (!level.hasChunkAt(pos)) continue;
            var state = level.getBlockState(pos);
            if (state.is(OrbitalArchitect.DRONE_DOCK.get())) {
                level.setBlock(pos, state.setValue(BlockStateProperties.LIT, false)
                        .setValue(DroneDockBlock.MODE, 0), Block.UPDATE_CLIENTS);
            }
        }
        if (hasProject()) {
            AltarRecovery.remember(level, this);
            return;
        }
        for (int slot = fleet.count() - 1; slot > 0; slot--) {
            var drone = fleet.withdraw(slot);
            Block.popResource(level, worldPosition, new ItemStack(OrbitalArchitect.DRONES.get(drone.tier().level - 1).get()));
            for (var upgrade : drone.upgrades()) Block.popResource(level, worldPosition, upgrade.stack());
        }
        for (var upgrade : fleet.integratedUpgrades()) Block.popResource(level, worldPosition, upgrade.stack());
    }

    private static ResourceLocation hubBlueprint(int tier) {
        return tier == 1 ? HUB : OrbitalArchitect.id("hub_t" + tier);
    }

    private static int hubTier(ResourceLocation id) {
        for (int tier = 1; tier <= 4; tier++) if (id.equals(hubBlueprint(tier))) return tier;
        return 0;
    }

    boolean hasHubUpgrade() {
        if (!isIdle() || rooms.isEmpty()) return false;
        int builtTier = hubTier(rooms.getFirst().blueprint());
        return builtTier > 0 && builtTier < tier();
    }

    String planHubUpgrade(ServerPlayer player, CompoundTag inspection) {
        if (!hasHubUpgrade()) return "Nenhuma reforma do núcleo disponível.";
        int next = hubTier(rooms.getFirst().blueprint()) + 1;
        return plan(player.serverLevel(), hubBlueprint(next), 0, Direction.NORTH, true, inspection, player);
    }

    ItemStack findUpgradeCard(ServerPlayer player) {
        if (tier() >= OrbitalArchitect.ALTARS.size()) return ItemStack.EMPTY;
        var card = OrbitalArchitect.CARDS.get(tier() - 1).get();
        var inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            var stack = inventory.getItem(slot);
            if (stack.is(card)) return stack;
        }
        return ItemStack.EMPTY;
    }

    String installUpgrade(ServerPlayer player, ItemStack payment, int nextTier) {
        if (!isIdle()) return "Conclua a obra ou cancele a prévia para instalar um cartão.";
        if (payment.isEmpty()) return "Coloque o cartão do próximo tier no inventário.";
        if (nextTier != tier() + 1 || nextTier > OrbitalArchitect.ALTARS.size()) {
            return "Este cartão precisa do altar Tier " + (nextTier - 1) + ".";
        }
        var server = player.serverLevel();
        var current = getBlockState();
        var next = OrbitalArchitect.ALTARS.get(nextTier - 1).get().defaultBlockState()
                .setValue(OrbAltarBlock.FRONT, current.getValue(OrbAltarBlock.FRONT));
        var data = saveWithoutMetadata(server.registryAccess());
        if (!setStructureBlock(server, new Work(worldPosition, next, current), player)) {
            return "O servidor bloqueou a instalação do upgrade.";
        }
        if (!(server.getBlockEntity(worldPosition) instanceof OrbAltarBlockEntity upgraded)) {
            server.setBlockAndUpdate(worldPosition, current);
            if (server.getBlockEntity(worldPosition) instanceof OrbAltarBlockEntity restored) {
                restored.loadWithComponents(data, server.registryAccess());
                restored.setChanged();
            }
            return "Não foi possível transferir os dados do altar.";
        }
        upgraded.loadWithComponents(data, server.registryAccess());
        upgraded.setChanged();
        payment.consume(1, player);
        player.getInventory().setChanged();
        return "";
    }

    boolean isPlanner(ServerPlayer player) {
        return planner == null || planner.equals(player.getUUID());
    }

    String confirm(ServerPlayer player, boolean allowClear, boolean allowGalleryLink) {
        if (state != State.PREVIEW) return "A prévia não está mais disponível.";
        if (!isPlanner(player)) return "Apenas quem preparou a prévia pode confirmar.";
        boolean excavate = obstructionCount() > 0;
        if (excavate && !allowClear) return "Autorize a limpeza do terreno antes de confirmar.";
        if (allowGalleryLink) {
            if (galleryLink == null) return "Este encaixe não tem uma passagem técnica disponível.";
            var pos = galleryLink.existing();
            var level = player.serverLevel();
            if (!validPosition(level, pos) || !level.mayInteract(player, pos)) {
                holdAt("Passagem técnica indisponível.", pos);
                return "Passagem técnica indisponível em " + pos.toShortString() + ".";
            }
            var panel = level.getBlockState(pos);
            if (!panel.isAir() && !matchesStructure(panel, galleryLink.existingPanel())) {
                holdAt(blockage(panel, pos), pos);
                return waiting;
            }
        }
        if (upgrade && job.isEmpty()) {
            finishRoom();
            return "";
        }
        clearWait();
        job.sort(Comparator.comparing(work -> work.stage() != workPriority));
        connectGallery = allowGalleryLink;
        planner = player.getUUID();
        creativeJob = player.isCreative();
        state = upgrade ? State.UPGRADING : excavate ? State.EXCAVATING : State.BUILDING;
        setChanged();
        return "";
    }

    String skipGallery(ServerPlayer player) {
        if (!isPlanner(player) || state != State.BUILDING || cursor != job.size() || !connectGallery) {
            return "A ligação ainda não pode ser dispensada.";
        }
        connectGallery = false;
        clearWait();
        setChanged();
        return "A sala será concluída com as paredes da galeria preservadas.";
    }

    String cancelPreview(ServerPlayer player) {
        if (state != State.PREVIEW) return "A prévia não está mais disponível.";
        if (!isPlanner(player)) return "Apenas quem preparou a prévia pode cancelar.";
        clearJob();
        return "Prévia cancelada.";
    }

    String pinShopping(ServerPlayer player) {
        if (state != State.PREVIEW || !isPlanner(player)) return "Abra sua revisão para fixar a lista.";
        shopping.clear();
        requirements().forEach((key, amount) -> shopping.put(ResourceLocation.parse(key), amount));
        shoppingName = projectName(player.serverLevel(), jobBlueprint);
        setChanged();
        return "Lista fixada no Terminal de Suprimentos.";
    }

    String cycleReserve() {
        energyReserve = switch (energyReserve) {
            case 0 -> 500;
            case 500 -> 1500;
            case 1500 -> 3000;
            case 3000 -> 5000;
            default -> 0;
        };
        setChanged();
        return "Reserva do núcleo · " + energyReserve + " FE.";
    }

    CompoundTag terminalSnapshot(ServerLevel level) {
        var data = telemetry(level);
        data.putString("ShoppingName", shoppingName);
        var list = new ListTag();
        var inventories = adjacentInventories(level);
        shopping.forEach((id, needed) -> {
            var block = net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(id);
            var entry = new CompoundTag();
            entry.putString("Block", id.toString());
            entry.putInt("Count", needed);
            entry.putInt("Stock", countStock(new ItemStack(block), needed, inventories));
            list.add(entry);
        });
        list.sort(Comparator.comparing(entry -> {
            var cost = (CompoundTag) entry;
            return cost.getInt("Stock") >= cost.getInt("Count");
        }));
        data.put("Shopping", list);
        return data;
    }

    private String projectName(ServerLevel level, ResourceLocation id) {
        try { return RoomBlueprint.describe(level, id).getString("Name"); }
        catch (IOException | RuntimeException invalid) { return id.getPath(); }
    }

    private String roomName(ServerLevel level, int index) {
        return roomNames.getOrDefault(index, projectName(level, rooms.get(index).blueprint()));
    }

    String editRoom(ServerLevel level, int index, String name, int color) {
        if (!isIdle() || index < 0 || index >= rooms.size()) return "Selecione uma sala concluída.";
        name = name.strip();
        if (name.length() > 32 || name.codePoints().anyMatch(Character::isISOControl)) return "Use até 32 caracteres para o nome.";
        if (name.isEmpty()) roomNames.remove(index);
        else roomNames.put(index, name);
        roomColors.put(index, Math.clamp(color, 0, ROOM_COLORS.length - 1));
        updateDoorLabels(level);
        setChanged();
        return "Nome e sinalização salvos.";
    }

    String savePersonal(ServerLevel level, int index) {
        if (!isIdle() || index < 0 || index >= rooms.size()) return "Selecione uma sala concluída.";
        var room = rooms.get(index);
        favoritePalette = room.palette().forNewConstruction();
        if (hubTier(room.blueprint()) > 0 || room.blueprint().equals(CAPSULE)) {
            setChanged();
            return "Paleta do núcleo salva.";
        }
        var personal = new PersonalPlan(room.blueprint(), roomName(level, index), favoritePalette);
        if (personalPlans.contains(personal)) return "Esta planta já está salva.";
        if (personalPlans.size() >= 12) return "O catálogo pessoal já tem 12 plantas.";
        personalPlans.add(personal);
        setChanged();
        return "Planta e paleta salvas no catálogo.";
    }

    private PersonalPlan personalPlan(ResourceLocation id) {
        if (!id.getNamespace().equals(OrbitalArchitect.ID) || !id.getPath().startsWith("personal/")) return null;
        try {
            int index = Integer.parseInt(id.getPath().substring(9));
            return index >= 0 && index < personalPlans.size() ? personalPlans.get(index) : null;
        } catch (NumberFormatException invalid) { return null; }
    }

    String planRepair(ServerPlayer player, int index) {
        if (!isIdle() || index < 0 || index >= rooms.size()) return "Selecione uma sala concluída.";
        var level = player.serverLevel();
        var room = rooms.get(index);
        var missing = new ArrayList<Work>();
        for (var entry : room.shell().entrySet()) {
            var pos = entry.getKey();
            var target = entry.getValue();
            if (!validPosition(level, pos) || !level.getBlockState(pos).isAir()) continue;
            if (target.getBlock() instanceof AirlockBlock door) {
                if (target.getValue(AirlockBlock.COLUMN) != 0 || target.getValue(AirlockBlock.ROW) != 0) continue;
                target = target.setValue(BlockStateProperties.OPEN, false).setValue(AirlockBlock.MOVING, false);
                if (door.panels(pos, target).keySet().stream().anyMatch(part -> !validPosition(level, part)
                        || !level.getBlockState(part).isAir())) continue;
            }
            missing.add(new Work(pos, target, Blocks.AIR.defaultBlockState()));
        }
        if (missing.isEmpty()) return "A vistoria não encontrou blocos estruturais ausentes na área carregada.";
        job.addAll(missing);
        siteSurvey.putLongArray("SiteRepair", missing.stream().limit(128).mapToLong(work -> work.pos().asLong()).toArray());
        planner = player.getUUID();
        anchor = index;
        ports = room.ports();
        jobBlueprint = room.blueprint();
        jobPalette = room.palette();
        repair = true;
        upgrade = true;
        state = State.PREVIEW;
        setChanged();
        return "";
    }

    private record AlignedRoom(RoomBlueprint blueprint, BlockPos origin, int matches) {}

    private static AlignedRoom alignRoom(Room room, RoomBlueprint blueprint) {
        AlignedRoom best = null;
        for (var port : blueprint.ports().entrySet()) {
            if (!port.getKey().getAxis().isHorizontal()) continue;
            var actual = room.ports().get(port.getKey());
            if (actual == null) continue;
            var origin = actual.subtract(port.getValue());
            int matches = (int) blueprint.ports().entrySet().stream()
                    .filter(candidate -> origin.offset(candidate.getValue()).equals(room.ports().get(candidate.getKey()))).count();
            if (best == null || matches > best.matches()) best = new AlignedRoom(blueprint, origin, matches);
        }
        return best;
    }

    private static AlignedRoom alignRoom(ServerLevel level, Room room) {
        AlignedRoom best = null;
        try { best = alignRoom(room, RoomBlueprint.load(level, room.blueprint())); }
        catch (IOException | RuntimeException invalid) { /* A legacy plan may still match the recorded doors. */ }
        try {
            var legacy = alignRoom(room, RoomBlueprint.loadLegacy(level, room.blueprint()));
            if (legacy != null && (best == null || legacy.matches() > best.matches())) best = legacy;
        } catch (IOException | RuntimeException ignored) { }
        return best != null && best.matches() >= 2 ? best : null;
    }

    String planRenovation(ServerPlayer player, int index, DomePalette.Part part, DomePalette palette) {
        if (!isIdle() || index < 0 || index >= rooms.size()) return "Selecione uma sala concluída.";
        if (part != DomePalette.Part.ROOF && part != DomePalette.Part.FLOOR && part != DomePalette.Part.WALL) {
            return "Escolha teto, piso ou paredes.";
        }
        var target = palette.choices().get(part);
        if (target == null) return "Escolha o novo bloco antes de revisar.";
        var level = player.serverLevel();
        var room = rooms.get(index);
        var aligned = alignRoom(level, room);
        if (aligned == null) return "O registro desta sala não corresponde à planta disponível.";
        var blueprint = aligned.blueprint();
        var swaps = new ArrayList<Work>();
        var excluded = new ArrayList<BlockPos>();
        int skipped = 0;
        for (var cell : blueprint.shell().entrySet()) {
            if (DomePalette.partAt(blueprint, cell.getKey(), cell.getValue()) != part) continue;
            var pos = aligned.origin().offset(cell.getKey());
            var registered = room.shell().get(pos);
            if (registered == null || matchesStructure(registered, target)) continue;
            if (!validPosition(level, pos) || !level.mayInteract(player, pos)
                    || !matchesStructure(level.getBlockState(pos), registered)
                    || !excavatable(level, pos, level.getBlockState(pos))) {
                skipped++;
                if (excluded.size() < 128) excluded.add(pos);
                continue;
            }
            swaps.add(new Work(pos, target, registered));
        }
        if (swaps.isEmpty()) return "Nenhum bloco registrado elegível para trocar nesta área.";
        swaps.sort(Comparator.comparing(Work::pos));
        var choices = new EnumMap<DomePalette.Part, BlockState>(DomePalette.Part.class);
        choices.putAll(room.palette().choices());
        choices.put(part, target);
        job.addAll(swaps);
        siteSurvey.putInt("SiteProtectedCount", skipped);
        siteSurvey.putLongArray("SiteProtected", excluded.stream().mapToLong(BlockPos::asLong).toArray());
        planner = player.getUUID();
        anchor = index;
        ports = room.ports();
        jobBlueprint = room.blueprint();
        jobPalette = new DomePalette(choices);
        renovation = part;
        upgrade = true;
        state = State.PREVIEW;
        setChanged();
        return "";
    }

    private int obstructionCount() {
        if (upgrade) return 0;
        return (int) job.stream().filter(work -> !work.expected().isAir()
                && !matchesStructure(work.expected(), work.target())).count();
    }

    String plan(ServerLevel level, ResourceLocation requested, int roomIndex, Direction face, boolean replace,
                CompoundTag inspection, ServerPlayer player) {
        return plan(level, requested, roomIndex, face, replace, inspection, player, preferredPalette);
    }

    String plan(ServerLevel level, ResourceLocation requested, int roomIndex, Direction face, boolean replace,
                CompoundTag inspection, ServerPlayer player, DomePalette palette) {
        return plan(level, requested, roomIndex, face, replace, inspection, player, palette, CorridorPlan.Options.DEFAULT);
    }

    String plan(ServerLevel level, ResourceLocation requested, int roomIndex, Direction face, boolean replace,
                CompoundTag inspection, ServerPlayer player, DomePalette palette, CorridorPlan.Options options) {
        if (!isIdle()) return "Conclua o trabalho ou cancele a prévia atual.";
        try {
            String result = preview(level, requested, roomIndex, face, replace, inspection, false, player.isCreative(), palette, options);
            if (result.isEmpty()) {
                planner = player.getUUID();
                if (!replace) preferredPalette = jobPalette;
                setChanged();
            }
            return result;
        } catch (IOException | RuntimeException error) {
            clearJob();
            return "Projeto inválido: " + error.getMessage();
        }
    }

    String browse(ServerLevel level, ResourceLocation requested, int roomIndex, Direction face, CompoundTag inspection,
                  DomePalette palette, CorridorPlan.Options options) {
        if (!isIdle()) return "";
        try {
            return preview(level, requested, roomIndex, face, false, inspection, true, false, palette, options);
        } catch (IOException | RuntimeException error) {
            return "Projeto indisponível: " + error.getMessage();
        }
    }

    private String preview(ServerLevel level, ResourceLocation requested, int roomIndex, Direction face,
                           boolean replace, CompoundTag inspection, boolean browse, boolean creative,
                           DomePalette palette, CorridorPlan.Options options) throws IOException {
        boolean first = rooms.isEmpty();
        if (first && replace) return "Construa o núcleo primeiro.";
        if (!first && (roomIndex < 0 || roomIndex >= rooms.size())) return "Sala de encaixe inválida.";
        var personal = personalPlan(requested);
        if (personal != null) requested = personal.blueprint();
        int requestedHub = hubTier(requested);
        int previousHub = first ? 0 : hubTier(rooms.get(roomIndex).blueprint());
        if (!first && !replace && (requestedHub > 0 || requested.equals(CAPSULE))) return "Este altar já tem um núcleo.";
        if (replace && requestedHub > 0 && (previousHub == 0 || requestedHub != previousHub + 1 || requestedHub > tier())) {
            return "A reforma do núcleo deve avançar um tier por vez.";
        }
        if (replace && requestedHub == 0 && !requested.equals(OrbitalArchitect.id("stone_module"))) return "Upgrade inválido.";
        var id = first && !requested.equals(CAPSULE) ? hubBlueprint(tier()) : requested;
        Room previous = first ? null : rooms.get(roomIndex);
        var blueprint = RoomBlueprint.load(level, id);
        if (replace && blueprint.shapedClearance()
                && horizontalSpan(previous.ports(), Direction.NORTH, Direction.SOUTH)
                != horizontalSpan(blueprint.ports(), Direction.NORTH, Direction.SOUTH)) {
            blueprint = RoomBlueprint.loadLegacy(level, id);
        }
        if (!browse && !creative && tier() < blueprint.tier()) return "Projeto disponível a partir do Tier " + blueprint.tier() + ".";

        BlockPos alignedOrigin;
        Direction loweredPort = null;
        if (replace) {
            if (blueprint.gallery() && requestedHub == 0) loweredPort = loweredEntrance(previous);
            var alignment = loweredPort == Direction.NORTH ? Direction.SOUTH : Direction.NORTH;
            var marker = previous.ports().get(alignment);
            var newMarker = blueprint.ports().get(alignment);
            if (marker == null || newMarker == null) return "Planta incompatível com este upgrade.";
            alignedOrigin = marker.subtract(newMarker);
        } else if (previous != null) {
            var marker = previous.ports().get(face);
            var opposite = blueprint.ports().get(face.getOpposite());
            if (marker == null || opposite == null || previous.occupied().contains(face)) {
                if (!browse) return "Porta indisponível.";
                alignedOrigin = BlockPos.ZERO;
            } else {
                alignedOrigin = marker.relative(face, options.distance(face)).above(options.grade(face)).subtract(opposite);
                if (alignedOrigin.getY() == level.getMinBuildHeight() - 1 && blueprint.gallery()
                        && blueprint.airlocks().containsKey(opposite)) {
                    alignedOrigin = alignedOrigin.above();
                    loweredPort = face.getOpposite();
                }
            }
        } else {
            alignedOrigin = hubOrigin(blueprint);
        }
        var origin = alignedOrigin;

        var worldPorts = new EnumMap<Direction, BlockPos>(Direction.class);
        blueprint.ports().forEach((direction, pos) -> worldPorts.put(direction, origin.offset(pos)));
        var selectedPalette = replace ? previous.palette() : palette.forNewConstruction();
        var shell = selectedPalette.apply(blueprint);
        var airlocks = blueprint.airlocks();
        var oldPort = previous == null ? null : previous.ports().get(face);
        var corridor = !replace && oldPort != null && blueprint.ports().containsKey(face.getOpposite())
                && !previous.occupied().contains(face) ? CorridorPlan.between(level, oldPort, face, selectedPalette, options)
                : CorridorPlan.EMPTY;
        var connector = new LinkedHashMap<>(corridor.blocks());
        var supports = new LinkedHashSet<>(corridor.supports());
        if (first && id.equals(CAPSULE)) {
            for (int x : new int[]{5, blueprint.size().getX() - 6}) {
                for (int z : new int[]{5, blueprint.size().getZ() - 6}) {
                    var base = new BlockPos(x, 0, z);
                    if (shell.containsKey(base)) CorridorPlan.addSupport(level, origin.offset(base), shell.get(base),
                            options.pillars(), connector, supports);
                }
            }
        }
        if (loweredPort != null) {
            var marker = blueprint.ports().get(loweredPort);
            var door = airlocks.get(marker);
            if (door == null || !(door.getBlock() instanceof AirlockBlock airlock)) return "Planta sem porta para o desnível.";
            worldPorts.put(loweredPort, origin.offset(marker.below()));
            shell = new LinkedHashMap<>(shell);
            airlocks = new LinkedHashMap<>(airlocks);
            airlocks.remove(marker);
            airlocks.put(marker.below(), door);
            addDockSteps(shell, marker, loweredPort, airlock.size(), selectedPalette);
        }
        if (replace && blueprint.gallery() && requestedHub == 0) {
            var oldDown = previous.ports().get(Direction.DOWN);
            if (oldDown == null) worldPorts.remove(Direction.DOWN);
            else worldPorts.put(Direction.DOWN, oldDown);
        }
        var minimum = origin;
        var bounds = origin.offset(blueprint.size()).offset(-1, -1, -1);
        for (var pos : connector.keySet()) {
            minimum = new BlockPos(Math.min(minimum.getX(), pos.getX()), Math.min(minimum.getY(), pos.getY()),
                    Math.min(minimum.getZ(), pos.getZ()));
            bounds = new BlockPos(Math.max(bounds.getX(), pos.getX()), Math.max(bounds.getY(), pos.getY()),
                    Math.max(bounds.getZ(), pos.getZ()));
        }
        inspection.putLong("Min", minimum.asLong());
        inspection.putLong("Max", bounds.asLong());
        var geometry = new LinkedHashMap<BlockPos, BlockState>();
        for (var cell : shell.entrySet()) {
            var pos = origin.offset(cell.getKey());
            if (!first || !isAltarSpace(pos)) geometry.put(pos, cell.getValue());
        }
        if (!replace) airlocks.forEach((pos, door) ->
                geometry.putAll(((AirlockBlock) door.getBlock()).panels(origin.offset(pos), door)));
        geometry.putAll(connector);
        inspection.putLongArray("Ghost", ghostBoxes(new LinkedHashSet<>(geometry.keySet())));
        writeMaterialGeometry(inspection, geometry);
        writeDoorCladding(inspection, selectedPalette);
        inspection.put("Ports", writePorts(worldPorts));
        inspection.putLongArray("SiteSupports", supports.stream().mapToLong(BlockPos::asLong).toArray());
        if (!connector.isEmpty()) {
            inspection.putString("Connection", options.style().label);
            inspection.putInt("Grade", options.grade(face));
        }
        GalleryLink link = null;
        if (previous != null && !replace) {
            var available = new CompoundTag();
            for (var entry : previous.ports().entrySet()) {
                var direction = entry.getKey();
                if (!previous.occupied().contains(direction) && blueprint.ports().containsKey(direction.getOpposite())) {
                    available.putLong(direction.getSerializedName(), entry.getValue().asLong());
                }
            }
            inspection.put("AnchorPorts", available);
        }
        if (previous != null && !replace && previous.ports().containsKey(face)
                && !previous.occupied().contains(face) && blueprint.ports().containsKey(face.getOpposite())) {
            inspection.putLong("Dock", previous.ports().get(face).asLong());
            addDockContext(inspection, previous, previous.ports().get(face));
            int galleryDistance = connector.isEmpty() ? 1 : options.style() == CorridorPlan.Style.SERVICE
                    ? options.distance(face) : 0;
            if (galleryDistance > 0) link = galleryConnection(level, previous, blueprint, shell, face, origin, galleryDistance);
            if (link != null) inspection.putLongArray("GalleryLink", link.positions());
        }
        if (browse) return "";
        if (minimum.getY() < level.getMinBuildHeight()) {
            return foundationBlocked(level, blueprint, origin, inspection, first, "Base abaixo do mundo.");
        }
        if (level.isOutsideBuildHeight(bounds)) return "O teto ultrapassa o limite do mundo.";
        if (replace && !worldPorts.equals(previous.ports())) return "As portas do upgrade devem coincidir.";
        if (!level.getWorldBorder().isWithinBounds(minimum) || !level.getWorldBorder().isWithinBounds(bounds)) {
            return "O projeto ultrapassa a borda do mundo.";
        }
        if (!level.hasChunksAt(minimum, bounds)) return "A área da sala ainda não está carregada.";
        var down = worldPorts.get(Direction.DOWN);
        if (!replace && down != null && naturalFoundation(blueprint, origin, down, level.getBlockState(down))) {
            worldPorts.remove(Direction.DOWN);
            inspection.put("Ports", writePorts(worldPorts));
        }

        var occupiedPositions = new HashSet<BlockPos>();
        if (!replace) for (var room : rooms) {
            occupiedPositions.addAll(room.shell().keySet());
            occupiedPositions.addAll(room.ports().values());
        }
        var targets = new LinkedHashMap<>(shell);
        if (!replace) targets.putAll(airlocks);
        var survey = surveySite(level, blueprint, origin, geometry, corridor.clearance(), supports, replace, first);
        inspection.merge(survey);
        var existingDoorPanels = new HashSet<BlockPos>();
        if (!replace) airlocks.forEach((pos, state) -> {
            var panels = ((AirlockBlock) state.getBlock()).panels(origin.offset(pos), state);
            if (doorMatches(level, panels)) existingDoorPanels.addAll(panels.keySet());
        });
        var plan = new ArrayList<Work>();
        boolean unchangedHub = false;
        for (var entry : targets.entrySet()) {
            var pos = origin.offset(entry.getKey());
            if (first && isAltarSpace(pos)) continue;
            if (occupiedPositions.contains(pos)) return "O projeto sobrepõe outra sala.";
            var current = level.getBlockState(pos);
            if (replace) {
                if (!matchesStructure(current, previous.shell().get(pos)) || current.hasBlockEntity()) continue;
                if (requestedHub > 0 && matchesStructure(current, entry.getValue())) {
                    unchangedHub = true;
                    continue;
                }
            } else if (!current.isAir() && !existingDoorPanels.contains(pos)
                    && (entry.getValue().getBlock() instanceof AirlockBlock || !matchesStructure(current, entry.getValue()))
                    && !excavatable(level, pos, current)) {
                if (naturalFoundation(blueprint, origin, pos, current)) continue;
                inspection.putLong("Blocked", pos.asLong());
                if (current.is(Blocks.BEDROCK)) {
                    return foundationBlocked(level, blueprint, origin, inspection, first, "Rocha-mãe no projeto.");
                }
                return blockage(current, pos);
            }
            plan.add(new Work(pos, entry.getValue(), current, workStage(blueprint, entry.getKey(), entry.getValue())));
        }
        for (var entry : connector.entrySet()) {
            var pos = entry.getKey();
            var current = level.getBlockState(pos);
            if (occupiedPositions.contains(pos)) return "A ligação sobrepõe outra sala.";
            if (!current.isAir() && !matchesStructure(current, entry.getValue()) && !excavatable(level, pos, current)) {
                inspection.putLong("Blocked", pos.asLong());
                return blockage(current, pos);
            }
            var stage = oldPort != null && pos.getY() < oldPort.getY() - 1
                    ? CorridorPlan.Priority.GALLERY : CorridorPlan.Priority.SHELL;
            plan.add(new Work(pos, entry.getValue(), current, stage));
        }
        if (plan.isEmpty() && !unchangedHub) return "Nenhum bloco estrutural elegível.";
        if (!replace) {
            Iterable<BlockPos> clearance = blueprint.clearance();
            if (loweredPort != null && blueprint.shapedClearance()) {
                var adjusted = new LinkedHashSet<>(blueprint.interior());
                adjusted.addAll(blueprint.shell().keySet());
                clearance = adjusted;
            }
            for (var local : clearance) {
                var pos = origin.offset(local);
                if (occupiedPositions.contains(pos)) return "O projeto sobrepõe outra sala.";
                if (targets.containsKey(local) || existingDoorPanels.contains(pos)) continue;
                if (first && isAltarSpace(pos)) continue;
                var current = level.getBlockState(pos);
                if (current.isAir() || naturalFoundation(blueprint, origin, pos, current)) continue;
                if (current.is(Blocks.BEDROCK)) {
                    inspection.putLong("Blocked", pos.asLong());
                    return foundationBlocked(level, blueprint, origin, inspection, first, "Rocha-mãe no projeto.");
                }
                if (!excavatable(level, pos, current)) {
                    inspection.putLong("Blocked", pos.asLong());
                    return blockage(current, pos);
                }
                plan.add(new Work(pos.immutable(), Blocks.AIR.defaultBlockState(), current,
                        workStage(blueprint, local, Blocks.AIR.defaultBlockState())));
            }
            for (var pos : corridor.clearance()) {
                if (connector.containsKey(pos)) continue;
                if (occupiedPositions.contains(pos)) return "O vão do corredor sobrepõe outra sala.";
                var current = level.getBlockState(pos);
                if (current.isAir()) continue;
                if (!excavatable(level, pos, current)) {
                    inspection.putLong("Blocked", pos.asLong());
                    return blockage(current, pos);
                }
                plan.add(new Work(pos, Blocks.AIR.defaultBlockState(), current,
                        pos.getY() < oldPort.getY() - 1 ? CorridorPlan.Priority.GALLERY : CorridorPlan.Priority.INTERIOR));
            }
        }

        upgrade = replace;
        workPriority = options.priority();
        siteSurvey = survey;
        if (inspection.contains("Connection")) siteSurvey.putString("Connection", inspection.getString("Connection"));
        if (inspection.contains("Grade")) siteSurvey.putInt("Grade", inspection.getInt("Grade"));
        anchor = first ? -1 : roomIndex;
        jobBlueprint = id;
        jobPalette = selectedPalette;
        dock = previous == null || replace ? null : face;
        galleryLink = link;
        ports = worldPorts;
        if (!replace) {
            plan.sort(Comparator.comparing((Work work) -> work.target().getBlock() instanceof AirlockBlock)
                    .thenComparing(Comparator.comparingInt((Work work) -> work.pos().getY()).reversed()));
        }
        job.addAll(plan);
        state = State.PREVIEW;
        setChanged();
        return "";
    }

    private static int horizontalSpan(Map<Direction, BlockPos> ports, Direction start, Direction end) {
        var first = ports.get(start);
        var last = ports.get(end);
        return first == null || last == null ? -1 : Math.abs(last.getZ() - first.getZ());
    }

    private static CorridorPlan.Priority workStage(RoomBlueprint blueprint, BlockPos pos, BlockState state) {
        int floor = blueprint.ports().get(Direction.NORTH).getY() - 1;
        if (pos.getY() < floor || state.getBlock() instanceof StairBlock && pos.getY() == floor) {
            return CorridorPlan.Priority.GALLERY;
        }
        if (state.isAir()) return CorridorPlan.Priority.INTERIOR;
        if (pos.getY() == floor || pos.getY() >= blueprint.roofFrom() || state.getBlock() instanceof AirlockBlock) {
            return CorridorPlan.Priority.SHELL;
        }
        for (var face : Direction.Plane.HORIZONTAL) {
            var neighbor = pos.relative(face);
            if (!blueprint.shell().containsKey(neighbor) && !blueprint.interior().contains(neighbor)) {
                return CorridorPlan.Priority.SHELL;
            }
        }
        return CorridorPlan.Priority.INTERIOR;
    }

    private CompoundTag surveySite(ServerLevel level, RoomBlueprint blueprint, BlockPos origin,
                                          Map<BlockPos, BlockState> geometry, Set<BlockPos> corridor,
                                          Set<BlockPos> supports, boolean replace, boolean first) {
        var survey = new CompoundTag();
        var clearing = new ArrayList<BlockPos>();
        var protectedBlocks = new ArrayList<BlockPos>();
        int cut = 0, protectedCount = 0;
        if (!replace) {
            var examined = new LinkedHashSet<>(geometry.keySet());
            for (var pos : blueprint.clearance()) examined.add(origin.offset(pos));
            examined.addAll(corridor);
            for (var pos : examined) {
                if (!validPosition(level, pos) || first && isAltarSpace(pos)) continue;
                var current = level.getBlockState(pos);
                if (current.isAir() || matchesStructure(current, geometry.get(pos))
                        || naturalFoundation(blueprint, origin, pos, current)) continue;
                if (excavatable(level, pos, current)) {
                    cut++;
                    if (clearing.size() < 128) clearing.add(pos);
                } else {
                    protectedCount++;
                    if (protectedBlocks.size() < 128) protectedBlocks.add(pos);
                }
            }
        }
        survey.putInt("SiteCutCount", cut);
        survey.putInt("SiteProtectedCount", protectedCount);
        survey.putLongArray("SiteCut", clearing.stream().mapToLong(BlockPos::asLong).toArray());
        survey.putLongArray("SiteProtected", protectedBlocks.stream().mapToLong(BlockPos::asLong).toArray());
        survey.putLongArray("SiteSupports", supports.stream().mapToLong(BlockPos::asLong).toArray());
        return survey;
    }

    private static GalleryLink galleryConnection(ServerLevel level, Room previous, RoomBlueprint blueprint,
                                                 Map<BlockPos, BlockState> shell, Direction face, BlockPos origin, int distance) {
        if (!blueprint.gallery() || !face.getAxis().isHorizontal()) return null;
        var newPanel = blueprint.ports().get(face.getOpposite()).below(CABLE_PORT_BELOW_DOOR);
        var oldPanel = previous.ports().get(face).below(CABLE_PORT_BELOW_DOOR);
        var oldState = previous.shell().get(oldPanel);
        var newState = shell.get(newPanel);
        var remote = origin.offset(newPanel);
        int height = remote.getY() - oldPanel.getY();
        if (Math.abs(height) > (distance == 1 ? 0 : 1)
                || !remote.equals(oldPanel.relative(face, distance).above(height)) || newState == null
                || !isCablePanel(blueprint.shell().get(newPanel)) || oldState == null
                || !matchesStructure(oldState, previous.palette().material(DomePalette.Part.DETAIL, cablePanel()))
                || !validPosition(level, oldPanel)) return null;
        var actual = level.getBlockState(oldPanel);
        return actual.isAir() || matchesStructure(actual, oldState)
                ? new GalleryLink(oldPanel, origin.offset(newPanel), oldState, newState) : null;
    }

    private static BlockState cablePanel() {
        return net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(OrbitalArchitect.id("hazard_plate")).defaultBlockState();
    }

    private static boolean isCablePanel(BlockState state) {
        return state != null && net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock())
                .equals(OrbitalArchitect.id("hazard_plate"));
    }

    private void holdAt(String reason, BlockPos pos) {
        waiting = reason;
        blocked = pos.immutable();
    }

    private void clearWait() {
        waiting = "";
        blocked = null;
    }

    private static boolean naturalFoundation(RoomBlueprint blueprint, BlockPos origin, BlockPos pos, BlockState state) {
        return blueprint.gallery() && pos.getY() == origin.getY() && state.is(Blocks.BEDROCK);
    }

    private static Direction loweredEntrance(Room room) {
        int mainY = room.ports().entrySet().stream().filter(entry -> entry.getKey().getAxis().isHorizontal())
                .mapToInt(entry -> entry.getValue().getY()).max().orElse(0);
        for (var entry : room.ports().entrySet()) {
            if (entry.getKey().getAxis().isHorizontal() && room.occupied().contains(entry.getKey())
                    && entry.getValue().getY() == mainY - 1) return entry.getKey();
        }
        return null;
    }

    private static void addDockSteps(Map<BlockPos, BlockState> shell, BlockPos marker, Direction outward, int width,
                                     DomePalette palette) {
        var inward = outward.getOpposite();
        var stairs = OrbitalArchitect.TECHNICAL_STAIRS.get().defaultBlockState().setValue(StairBlock.FACING, inward);
        var trim = net.minecraft.core.registries.BuiltInRegistries.BLOCK
                .get(OrbitalArchitect.id("deck_plate")).defaultBlockState();
        for (int column = 0; column < width; column++) {
            var low = (outward.getAxis() == Direction.Axis.Z ? marker.east(column) : marker.south(column)).below();
            shell.remove(low);
            shell.put(low.above(width), palette.material(DomePalette.Part.TRIM, trim));
            var step = low.relative(inward);
            shell.put(step, palette.material(DomePalette.Part.STAIRS, stairs));
            shell.remove(step.above());
            shell.remove(step.above(2));
        }
    }

    private static String blockage(BlockState state, BlockPos pos) {
        return "Bloqueio em " + pos.toShortString() + " · " + state.getBlock().getName().getString();
    }

    private BlockPos hubOrigin(RoomBlueprint blueprint) {
        int entranceY = blueprint.ports().get(Direction.NORTH).getY();
        return worldPosition.offset(-blueprint.size().getX() / 2, -entranceY, -blueprint.size().getZ() / 2);
    }

    private String foundationBlocked(ServerLevel level, RoomBlueprint blueprint, BlockPos origin, CompoundTag inspection,
                                     boolean first, String reason) {
        if (!first) return reason + " Escolha outro encaixe.";
        int lift = hubLift(level, blueprint, origin);
        if (lift > 0) inspection.putInt("RaiseHub", lift);
        return reason + (lift > 0 ? " Eleve o altar." : " Reposicione o altar mais alto.");
    }

    private int hubLift(ServerLevel level, RoomBlueprint blueprint, BlockPos origin) {
        int minimum = Math.max(0, level.getMinBuildHeight() - origin.getY());
        for (int lift = minimum; lift <= MAX_HUB_LIFT; lift++) {
            var base = origin.above(lift);
            var roof = base.offset(blueprint.size()).offset(-1, -1, -1);
            if (level.isOutsideBuildHeight(roof) || !level.hasChunksAt(base, roof)) return 0;
            boolean bedrock = false;
            for (var pos : BlockPos.betweenClosed(base, roof)) {
                var state = level.getBlockState(pos);
                if (state.is(Blocks.BEDROCK) && !naturalFoundation(blueprint, base, pos, state)) {
                    bedrock = true;
                    break;
                }
            }
            if (!bedrock) {
                for (int step = 1; step <= lift + 1; step++) {
                    var pos = worldPosition.above(step);
                    if (!validPosition(level, pos) || !level.getBlockState(pos).isAir()) return 0;
                }
                return lift;
            }
        }
        return 0;
    }

    BlockPos higherHubPosition(ServerLevel level) {
        if (!isIdle() || !rooms.isEmpty()) return null;
        try {
            var blueprint = RoomBlueprint.load(level, hubBlueprint(tier()));
            int lift = hubLift(level, blueprint, hubOrigin(blueprint));
            return lift > 0 ? worldPosition.above(lift) : null;
        } catch (IOException | RuntimeException error) {
            return null;
        }
    }

    private BlockPos hubCenter() {
        if (rooms.isEmpty()) return null;
        var hub = rooms.getFirst();
        if (hubTier(hub.blueprint()) == 0) return null;
        var north = hub.ports().get(Direction.NORTH);
        var south = hub.ports().get(Direction.SOUTH);
        var west = hub.ports().get(Direction.WEST);
        var east = hub.ports().get(Direction.EAST);
        if (north == null || south == null || west == null || east == null) return null;
        return new BlockPos(west.getX() + (east.getX() - west.getX()) / 2, north.getY(),
                north.getZ() + (south.getZ() - north.getZ()) / 2);
    }

    boolean canCenter() {
        var center = hubCenter();
        return isIdle() && center != null
                && (center.getX() != worldPosition.getX() || center.getZ() != worldPosition.getZ());
    }

    String centerAltar(ServerLevel level, ServerPlayer player) {
        if (!canCenter()) return "O altar já está centralizado ou está ocupado.";
        var center = hubCenter();
        String result = moveAltar(level, player, center, true);
        if (result.isEmpty() && level.getBlockEntity(center) instanceof OrbAltarBlockEntity moved) {
            moved.refreshJoins(level, 0);
        }
        return result;
    }

    String raiseAltar(ServerLevel level, ServerPlayer player, BlockPos target) {
        if (!player.canInteractWithBlock(target, 1)) return "Aproxime-se do altar para elevá-lo.";
        return moveAltar(level, player, target, false);
    }

    private String moveAltar(ServerLevel level, ServerPlayer player, BlockPos center, boolean needsSupport) {
        var floor = center.below();
        if (!validPosition(level, center) || !level.getBlockState(center).isAir()
                || !validPosition(level, center.above()) || !level.getBlockState(center.above()).isAir()) {
            return "Libere o espaço do altar em " + center.toShortString() + ".";
        }
        if (AltarRecovery.tierAt(level, center) > 0) return "O local já guarda outro projeto do Orbit.";
        var originalFloor = level.getBlockState(floor);
        boolean needsFloor = needsSupport && !originalFloor.isFaceSturdy(level, floor, Direction.UP);
        if (needsFloor && (!player.isCreative() || !originalFloor.isAir())) {
            return "Coloque um bloco de piso em " + floor.toShortString() + " para apoiar o altar.";
        }
        var area = new net.minecraft.world.phys.AABB(center).expandTowards(0, 1, 0);
        if (!level.getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class, area).isEmpty()) {
            return "Libere o espaço no centro para mover o altar.";
        }
        if (!level.mayInteract(player, center) || !canBreak(level, player, worldPosition, getBlockState())) {
            return "O servidor bloqueou a mudança do altar.";
        }
        var data = saveWithoutMetadata(level.registryAccess());
        if (needsFloor) {
            var floorBlock = preferredPalette.forNewConstruction()
                    .material(DomePalette.Part.FLOOR, Blocks.POLISHED_ANDESITE.defaultBlockState());
            if (!setStructureBlock(level, new Work(floor, floorBlock, originalFloor), player)) {
                return "O servidor bloqueou o apoio do altar.";
            }
        }
        if (!setStructureBlock(level, new Work(center, getBlockState(), level.getBlockState(center)), player)) {
            if (needsFloor) level.removeBlock(floor, false);
            return "O servidor bloqueou a colocação do altar no centro.";
        }
        if (!(level.getBlockEntity(center) instanceof OrbAltarBlockEntity moved) || !removeForTransfer(level)) {
            level.removeBlock(center, false);
            if (needsFloor) level.removeBlock(floor, false);
            return "Não foi possível transferir o altar.";
        }
        moved.loadWithComponents(data, level.registryAccess());
        moved.setChanged();
        return "";
    }

    private boolean removeForTransfer(ServerLevel level) {
        transferring = true;
        try {
            return level.removeBlock(worldPosition, false);
        } finally {
            transferring = false;
        }
    }

    String refreshJoins(ServerLevel level, int roomIndex) {
        if (!isIdle() || roomIndex < 0 || roomIndex >= rooms.size()) return "Selecione uma sala concluída.";
        var room = rooms.get(roomIndex);
        int changed = 0;
        for (var entry : room.shell().entrySet()) {
            var pos = entry.getKey();
            if (!level.hasChunkAt(pos)) continue;
            var state = level.getBlockState(pos);
            if (state.getBlock() instanceof JoiningPanelBlock panel && matchesStructure(state, entry.getValue())) {
                var joined = panel.joined(state, level, pos);
                if (!joined.equals(state) && level.setBlock(pos, joined, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE)) {
                    changed++;
                }
            }
        }
        return "Junções atualizadas em " + changed + " blocos.";
    }

    private boolean isAltarSpace(BlockPos pos) {
        return pos.getY() == worldPosition.getY() && pos.distManhattan(worldPosition) <= 1;
    }

    private static boolean validPosition(ServerLevel level, BlockPos pos) {
        return !level.isOutsideBuildHeight(pos) && level.getWorldBorder().isWithinBounds(pos)
                && level.hasChunkAt(pos);
    }

    private static boolean excavatable(ServerLevel level, BlockPos pos, BlockState state) {
        String path = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
        return !state.hasBlockEntity() && !(state.getBlock() instanceof AirlockBlock) && state.getFluidState().isEmpty()
                && !(state.getBlock() instanceof net.minecraft.world.level.block.GameMasterBlock)
                && !path.contains("cable") && !path.contains("conduit") && !path.contains("pipe")
                && !state.is(Blocks.REDSTONE_WIRE)
                && state.getDestroySpeed(level, pos) >= 0;
    }

    private static boolean canBreak(ServerLevel level, ServerPlayer player, BlockPos pos, BlockState state) {
        return player != null ? level.mayInteract(player, pos)
                && !CommonHooks.fireBlockBreak(level, player.gameMode.getGameModeForPlayer(), player, pos, state).isCanceled()
                : state.is(EXCAVATABLE);
    }

    static void tick(net.minecraft.world.level.Level level, BlockPos pos, BlockState block,
                     OrbAltarBlockEntity altar) {
        if (level instanceof ServerLevel server) altar.tickServer(server);
    }

    private void tickServer(ServerLevel level) {
        // Wait until the placement and any protection hooks have committed.
        recoverOnFirstTick(level);
        if (tier() >= 3 && ae2 == null && net.neoforged.fml.ModList.get().isLoaded("ae2")) {
            ae2 = new Ae2Supply(this, ae2Data);
            ae2.create(level);
        }
        if (level.getGameTime() % 4 == 0) {
            double angle = level.getGameTime() * .12;
            level.sendParticles(ParticleTypes.ELECTRIC_SPARK,
                    worldPosition.getX() + .5 + .2 * Math.cos(angle),
                    worldPosition.getY() + 1.04 + .1 * Math.sin(angle),
                    worldPosition.getZ() + .5 + .2 * Math.sin(angle), 1, 0, 0, 0, 0);
        }
        if (!docks.isEmpty() && (!docksInitialized || dockLightRefresh || level.getGameTime() % 10 == 0)) {
            refreshDocks(level);
        }
        int charged = fleet.charge(level, tier(), amount -> energy.takeCharge(Math.min(amount,
                Math.max(0, energy.getEnergyStored() - energyReserve))));
        chargingFlow.record(charged, level.getGameTime());
        tickRoute(level);
        if (!servicePorts.isEmpty()) servicePorts(level).forEach(port -> port.transfer(level, this));
        if (state == State.IDLE || state == State.PREVIEW) {
            fleet.park(level, tier());
            return;
        }
        if (!creativeJob && energy.getEnergyStored() < energyReserve) {
            fleet.resetWork();
            fleet.park(level, tier());
            clearWait();
            waiting = "Aguardando a reserva de " + energyReserve + " FE no núcleo.";
            return;
        }

        var actor = planner == null ? null : level.getServer().getPlayerList().getPlayer(planner);
        if (planner != null && (actor == null || actor.level() != level || actor.isSpectator()
                || (creativeJob && !actor.isCreative()))) {
            fleet.resetWork();
            fleet.park(level, tier());
            clearWait();
            waiting = "Aguardando o responsável pela obra.";
            return;
        }
        if (cursor == job.size()) {
            if (connectGallery && level.getGameTime() < galleryRetryAt) return;
            var sources = creativeJob ? List.<IItemHandler>of() : returnInventories(level);
            clearWait();
            state = upgrade ? State.UPGRADING : State.BUILDING;
            if (!connectGallery || openGallery(level, actor, sources)) finishRoom();
            else galleryRetryAt = level.getGameTime() + 10;
            return;
        }

        var stocks = creativeJob ? List.<Stock>of() : itemStocks(level, false);
        var sources = stocks.stream().map(Stock::handler).toList();
        var returns = creativeJob ? List.<IItemHandler>of() : returnInventories(level);
        for (int checked = 0; checked < 128 && cursor < job.size(); checked++) {
            if (!settleExisting(level, cursor)) break;
        }
        if (cursor == job.size()) return;
        var operation = renovation == null ? ConstructionFleet.Operation.BUILD : ConstructionFleet.Operation.SWAP;
        if (!creativeJob && !fleet.inFlight() && !fleet.canOperate(tier(), operation)
                && (state != State.EXCAVATING || !fleet.canOperate(tier(), ConstructionFleet.Operation.DIG))) {
            fleet.park(level, tier());
            clearWait();
            waiting = BATTERY_WAIT;
            return;
        }
        if (waiting.equals(BATTERY_WAIT)) clearWait();
        fleet.work(level, tier(), creativeJob, renovation != null, state == State.EXCAVATING,
                (slot, digs, buildLimit, digLimit, reserved) -> {
                    var route = dispatch(level, stocks, reserved, digs, digs ? digLimit : buildLimit);
                    return route != null ? route : dispatch(level, stocks, reserved, !digs, digs ? buildLimit : digLimit);
                },
                (slot, index, digs) -> completeWork(level, actor, sources, returns, slot, index, digs));
        if (!fleet.inFlight() && !waiting.isEmpty()) fleet.park(level, tier());
    }

    private record Stock(BlockPos pos, IItemHandler handler) {}

    private ConstructionFleet.Route dispatch(ServerLevel level, List<Stock> stocks, BitSet reserved,
                                              boolean digging, int limit) {
        if (digging && state != State.EXCAVATING) return null;
        int end = Math.min(job.size(), cursor + 384);
        var orders = new ArrayList<ConstructionFleet.Order>();
        var cargo = ItemStack.EMPTY;
        BlockPos stock = worldPosition;
        for (int index = cursor; index < end && orders.size() < limit; index++) {
            if (completed.get(index) || reserved.get(index)) continue;
            if (settleExisting(level, index)) continue;
            var work = job.get(index);
            if (!validPosition(level, work.pos())) {
                if (index == cursor) holdAt("Área descarregada.", work.pos());
                continue;
            }
            var current = level.getBlockState(work.pos());
            if (digging) {
                if (!canExcavate(level, work, current)) continue;
            } else {
                if (work.target().isAir()) continue;
                if (!(upgrade ? matchesStructure(current, work.expected()) : current.isAir())) {
                    if (index == cursor && !canExcavate(level, work, current)) holdAt(blockage(current, work.pos()), work.pos());
                    continue;
                }
                if (work.target().getBlock() instanceof AirlockBlock door && door.panels(work.pos(), work.target())
                        .keySet().stream().anyMatch(pos -> !validPosition(level, pos) || !level.getBlockState(pos).isAir())) {
                    if (index == cursor) holdAt("Libere o vão da eclusa.", work.pos());
                    continue;
                }
                var material = new ItemStack(work.target().getBlock());
                if (orders.isEmpty()) {
                    var source = creativeJob ? worldPosition : pickupSource(stocks, material);
                    if (source == null) {
                        if (index == cursor) waiting = "Falta " + material.getHoverName().getString() + ".";
                        continue;
                    }
                    stock = source;
                    cargo = material;
                } else if (!ItemStack.isSameItemSameComponents(cargo, material)) continue;
            }
            orders.add(new ConstructionFleet.Order(index, work.pos()));
        }
        if (orders.isEmpty()) return null;
        return new ConstructionFleet.Route(List.copyOf(orders), stock, cargo, digging);
    }

    private BlockPos pickupSource(List<Stock> stocks, ItemStack material) {
        var network = ae2 == null ? null : ae2.forMaterial(material);
        if (network != null && hasMaterial(network, material)) return worldPosition;
        for (var stock : stocks) if (hasMaterial(stock.handler(), material)) return stock.pos();
        return null;
    }

    private static boolean hasMaterial(IItemHandler handler, ItemStack material) {
        for (int slot = 0; slot < handler.getSlots(); slot++) {
            if (ItemStack.isSameItemSameComponents(material, handler.extractItem(slot, 1, true))) return true;
        }
        return false;
    }

    private boolean settleExisting(ServerLevel level, int index) {
        var work = job.get(index);
        if (!validPosition(level, work.pos())) return false;
        var current = level.getBlockState(work.pos());
        if (work.target().getBlock() instanceof AirlockBlock door) {
            var panels = door.panels(work.pos(), work.target());
            if (!doorMatches(level, panels)) return false;
            built.putAll(panels);
        } else if (matchesStructure(current, work.target())) {
            if (!current.isAir()) built.put(work.pos(), work.target());
        } else if (!upgrade || matchesStructure(current, work.expected())) return false;
        completeIndex(index);
        return true;
    }

    private boolean canExcavate(ServerLevel level, Work work, BlockState current) {
        return state == State.EXCAVATING && !current.isAir() && !matchesStructure(current, work.target())
                && (current.is(work.expected().getBlock()) || current.is(EXCAVATABLE) && work.expected().is(EXCAVATABLE))
                && excavatable(level, work.pos(), current);
    }

    private boolean completeWork(ServerLevel level, ServerPlayer actor, List<IItemHandler> sources,
                                 List<IItemHandler> returns, int slot, int index, boolean digging) {
        if (index < cursor || completed.get(index)) return true;
        var work = job.get(index);
        if (!validPosition(level, work.pos())) {
            holdAt("Área descarregada.", work.pos());
            return false;
        }
        if (settleExisting(level, index)) return true;
        var current = level.getBlockState(work.pos());
        if (digging) {
            if (current.isAir()) return true;
            if (!canExcavate(level, work, current) || !canBreak(level, actor, work.pos(), current)) {
                holdAt("Limpeza indisponível em " + work.pos().toShortString() + ".", work.pos());
                return false;
            }
            if (!excavate(level, work, current, returns)) return false;
            if (work.target().isAir()) completeIndex(index);
        } else {
            if (!(upgrade ? matchesStructure(current, work.expected()) : current.isAir())) {
                holdAt(blockage(current, work.pos()), work.pos());
                return false;
            }
            if (upgrade && !current.isAir() && !canBreak(level, actor, work.pos(), current)) {
                holdAt("Upgrade bloqueado pela proteção em " + work.pos().toShortString(), work.pos());
                return false;
            }
            var salvage = renovation != null && !creativeJob
                    ? Block.getDrops(current, level, work.pos(), null, actor, new ItemStack(Items.DIAMOND_PICKAXE))
                    : List.<ItemStack>of();
            if (!place(level, work, sources, actor)) return false;
            if (renovation != null) {
                level.levelEvent(2001, work.pos(), Block.getId(current));
                for (var item : salvage) storeDrop(level, work.pos(), item, returns);
            }
            if (work.target().getBlock() instanceof AirlockBlock door) built.putAll(door.panels(work.pos(), work.target()));
            else built.put(work.pos(), work.target());
            completeIndex(index);
        }
        if (!creativeJob) fleet.spendForOperation(slot, tier(), digging ? ConstructionFleet.Operation.DIG
                : renovation != null ? ConstructionFleet.Operation.SWAP : ConstructionFleet.Operation.BUILD, level.getGameTime());
        clearWait();
        setChanged();
        return true;
    }

    private void completeIndex(int index) {
        completed.set(index);
        while (completed.get(cursor)) {
            completed.clear(cursor);
            cursor++;
        }
        setChanged();
    }

    private boolean openGallery(ServerLevel level, ServerPlayer actor, List<IItemHandler> sources) {
        var panels = new ArrayList<BlockSnapshot>(2);
        for (var pos : List.of(galleryLink.existing(), galleryLink.proposed())) {
            if (!validPosition(level, pos)) {
                holdAt("Área da ligação técnica descarregada.", pos);
                return false;
            }
            var current = level.getBlockState(pos);
            if (current.isAir()) continue;
            if (!matchesStructure(current, galleryLink.expected(pos))) {
                holdAt("A passagem da galeria contém " + current.getBlock().getName().getString() + ".", pos);
                return false;
            }
            if (actor == null || !canBreak(level, actor, pos, current)) {
                holdAt("A proteção do servidor bloqueou a abertura da galeria.", pos);
                return false;
            }
            panels.add(BlockSnapshot.create(level.dimension(), level, pos));
        }
        if (!creativeJob && Math.max(0, energy.getEnergyStored() - energyReserve)
                + fleet.availableCharge() < panels.size() * ENERGY_PER_BLOCK) {
            waiting = "Aguardando " + panels.size() * ENERGY_PER_BLOCK + " FE para ligar as galerias.";
            return false;
        }
        var air = Blocks.AIR.defaultBlockState();
        var removed = new ArrayList<BlockSnapshot>(2);
        for (var panel : panels) {
            var pos = panel.getPos();
            if (!level.getBlockState(pos).equals(panel.getState())
                    || !level.setBlock(pos, air, Block.UPDATE_KNOWN_SHAPE)) {
                for (var restored : removed.reversed()) restored.restore(Block.UPDATE_ALL);
                holdAt("O painel da galeria mudou em " + pos.toShortString() + ".", pos);
                return false;
            }
            removed.add(panel);
        }
        if (!creativeJob && !panels.isEmpty()) {
            int cost = panels.size() * ENERGY_PER_BLOCK;
            int core = energy.takeCharge(Math.min(cost, Math.max(0, energy.getEnergyStored() - energyReserve)));
            recordOutput(core, level.getGameTime());
            fleet.spendAuxiliary(cost - core, level.getGameTime());
        }
        for (var panel : panels) {
            var pos = panel.getPos();
            level.markAndNotifyBlock(pos, level.getChunkAt(pos), panel.getState(), air, Block.UPDATE_ALL, 512);
            if (!creativeJob) storeDrop(level, pos, new ItemStack(panel.getState().getBlock()), sources);
        }
        var previous = rooms.get(anchor);
        var shell = new LinkedHashMap<>(previous.shell());
        shell.remove(galleryLink.existing());
        rooms.set(anchor, new Room(previous.blueprint(), Map.copyOf(shell), previous.ports(), previous.occupied(), previous.palette()));
        built.remove(galleryLink.proposed());
        return true;
    }

    private boolean excavate(ServerLevel level, Work work, BlockState current, List<IItemHandler> sources) {
        if (creativeJob) return level.removeBlock(work.pos(), false);
        var drops = Block.getDrops(current, level, work.pos(), null, null, new ItemStack(Items.DIAMOND_PICKAXE));
        for (var drop : drops) {
            if (sources.stream().noneMatch(source -> ItemHandlerHelper.insertItemStacked(source, drop, true).isEmpty())) {
                waiting = "Inventário adjacente sem espaço para a escavação.";
                return false;
            }
        }
        if (!level.removeBlock(work.pos(), false)) return false;
        for (var drop : drops) storeDrop(level, work.pos(), drop, sources);
        return true;
    }

    private static void storeDrop(ServerLevel level, BlockPos pos, ItemStack remaining, List<IItemHandler> sources) {
        for (var source : sources) {
            remaining = ItemHandlerHelper.insertItemStacked(source, remaining, false);
            if (remaining.isEmpty()) return;
        }
        Block.popResource(level, pos, remaining);
    }

    private boolean place(ServerLevel level, Work work, List<IItemHandler> sources, ServerPlayer actor) {
        if (creativeJob) {
            if (!setStructureBlock(level, work, actor)) {
                if (waiting.isEmpty()) holdAt("A colocação falhou em " + work.pos().toShortString() + ".", work.pos());
                return false;
            }
            fleet.placed(level, work.pos());
            return true;
        }
        var required = new ItemStack(work.target().getBlock());
        var network = ae2 == null ? null : ae2.forMaterial(required);
        var inventories = new ArrayList<>(sources);
        if (network != null) inventories.addFirst(network);
        for (var source : inventories) {
            for (int slot = 0; slot < source.getSlots(); slot++) {
                if (!ItemStack.isSameItemSameComponents(required, source.extractItem(slot, 1, true))) continue;
                var taken = source.extractItem(slot, 1, false);
                if (ItemStack.isSameItemSameComponents(required, taken) && taken.getCount() == 1
                        && setStructureBlock(level, work, actor)) {
                    fleet.placed(level, work.pos());
                    return true;
                }
                if (!taken.isEmpty()) {
                    var leftover = ItemHandlerHelper.insertItemStacked(source, taken, false);
                    if (!leftover.isEmpty()) Block.popResource(level, worldPosition, leftover);
                }
                if (waiting.isEmpty()) holdAt("A colocação falhou em " + work.pos().toShortString() + ".", work.pos());
                return false;
            }
        }
        waiting = "Falta " + required.getHoverName().getString() + ".";
        return false;
    }

    private static boolean matchesStructure(BlockState actual, BlockState expected) {
        return expected != null && (actual.equals(expected) || (expected.getBlock() instanceof JoiningPanelBlock
                && actual.is(expected.getBlock())) || (expected.getBlock() instanceof AirlockBlock door
                && door.samePanel(actual, expected)) || (expected.getBlock() instanceof OrbitalLuminaire
                && actual.is(expected.getBlock())));
    }

    private static boolean doorMatches(ServerLevel level, Map<BlockPos, BlockState> panels) {
        return panels.entrySet().stream().allMatch(panel -> level.hasChunkAt(panel.getKey())
                && matchesStructure(level.getBlockState(panel.getKey()), panel.getValue()));
    }

    private boolean setAirlock(ServerLevel level, Work work, AirlockBlock door, ServerPlayer actor) {
        var panels = door.panels(work.pos(), work.target());
        var snapshots = new ArrayList<BlockSnapshot>(panels.size());
        for (var panel : panels.entrySet()) {
            var pos = panel.getKey();
            if (!validPosition(level, pos)) {
                holdAt("Área da porta descarregada.", pos);
                return false;
            }
            if (actor != null && !level.mayInteract(actor, pos)) {
                holdAt("Porta bloqueada pela proteção em " + pos.toShortString(), pos);
                return false;
            }
            var current = level.getBlockState(pos);
            if (!current.isAir()) {
                holdAt(blockage(current, pos), pos);
                return false;
            }
            if (!level.isUnobstructed(panel.getValue(), pos, CollisionContext.empty())) {
                holdAt("Libere a passagem da porta em " + pos.toShortString(), pos);
                return false;
            }
            snapshots.add(BlockSnapshot.create(level.dimension(), level, pos));
        }
        boolean installed = false;
        try {
            for (var panel : panels.entrySet()) {
                if (!level.setBlock(panel.getKey(), panel.getValue(), Block.UPDATE_KNOWN_SHAPE)) {
                    waiting = "Não foi possível montar a porta.";
                    return false;
                }
            }
            if (actor != null && EventHooks.onMultiBlockPlace(actor, snapshots, Direction.UP)) {
                holdAt("Porta bloqueada pela proteção em " + work.pos().toShortString(), work.pos());
                return false;
            }
            installed = true;
        } finally {
            if (!installed) for (var snapshot : snapshots.reversed()) snapshot.restore(Block.UPDATE_ALL);
        }
        for (var snapshot : snapshots) {
            var pos = snapshot.getPos();
            level.markAndNotifyBlock(pos, level.getChunkAt(pos), snapshot.getState(), panels.get(pos),
                    Block.UPDATE_ALL, 512);
        }
        if (level.getBlockEntity(work.pos()) instanceof AirlockBlockEntity controller) {
            controller.setCladding(jobPalette.choices().get(DomePalette.Part.DOOR));
        }
        return true;
    }

    private boolean setStructureBlock(ServerLevel level, Work work, ServerPlayer actor) {
        var target = work.target();
        if (target.getBlock() instanceof AirlockBlock door) return setAirlock(level, work, door, actor);
        if (target.getBlock() instanceof JoiningPanelBlock panel) target = panel.joined(target, level, work.pos());
        if (actor == null) return level.setBlock(work.pos(), target, Block.UPDATE_ALL);
        if (!level.mayInteract(actor, work.pos())) {
            holdAt("Colocação bloqueada pela proteção em " + work.pos().toShortString(), work.pos());
            return false;
        }
        var snapshot = BlockSnapshot.create(level.dimension(), level, work.pos());
        if (!level.setBlock(work.pos(), target, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE)) return false;
        if (EventHooks.onBlockPlace(actor, snapshot, Direction.UP)) {
            snapshot.restore(Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
            holdAt("Colocação bloqueada pela proteção em " + work.pos().toShortString(), work.pos());
            return false;
        }
        target.updateNeighbourShapes(level, work.pos(), Block.UPDATE_CLIENTS);
        level.updateNeighborsAt(work.pos(), target.getBlock());
        return true;
    }

    private List<IItemHandler> adjacentInventories(ServerLevel level) {
        return itemStocks(level, false).stream().map(Stock::handler).toList();
    }

    private List<ServicePortBlockEntity> servicePorts(ServerLevel level) {
        var loaded = new ArrayList<ServicePortBlockEntity>();
        if (servicePorts.removeIf(pos -> {
            if (!level.hasChunkAt(pos)) return false;
            if (!(level.getBlockEntity(pos) instanceof ServicePortBlockEntity port) || !port.belongsTo(this)) return true;
            port.bind(this);
            loaded.add(port);
            return false;
        })) setChanged();
        return loaded;
    }

    private List<IItemHandler> returnInventories(ServerLevel level) {
        return itemStocks(level, true).stream().map(Stock::handler).toList();
    }

    private List<Stock> itemStocks(ServerLevel level, boolean returning) {
        var inventories = new LinkedHashMap<BlockPos, IItemHandler>();
        var terminals = servicePorts(level);
        for (var port : terminals) {
            var pos = port.getBlockPos();
            for (var direction : Direction.values()) {
                if (returning != (direction == port.outputSide())) continue;
                var handler = ServicePortBlockEntity.neighborItems(level, pos, direction);
                if (handler != null) inventories.putIfAbsent(pos.relative(direction), handler);
            }
            inventories.putIfAbsent(pos, returning ? port.output() : port.input());
        }
        if (!returning || terminals.isEmpty()) {
            for (var direction : Direction.values()) {
                var pos = worldPosition.relative(direction);
                if (terminals.stream().anyMatch(port -> port.getBlockPos().relative(port.outputSide()).equals(pos))) continue;
                var handler = ServicePortBlockEntity.neighborItems(level, worldPosition, direction);
                if (handler != null) inventories.putIfAbsent(pos, handler);
            }
        }
        return inventories.entrySet().stream().map(entry -> new Stock(entry.getKey(), entry.getValue())).toList();
    }

    private void finishRoom() {
        var occupied = EnumSet.noneOf(Direction.class);
        if (repair) {
            clearJob();
            return;
        } else if (renovation != null) {
            var room = rooms.get(anchor);
            var tracked = new LinkedHashMap<>(room.shell());
            tracked.putAll(built);
            rooms.set(anchor, new Room(room.blueprint(), Map.copyOf(tracked), room.ports(), room.occupied(), jobPalette));
        } else if (upgrade) {
            occupied.addAll(rooms.get(anchor).occupied());
            var tracked = new LinkedHashMap<BlockPos, BlockState>();
            rooms.get(anchor).shell().forEach((pos, state) -> {
                if (!built.containsKey(pos) && level != null && level.hasChunkAt(pos)
                        && matchesStructure(level.getBlockState(pos), state)) tracked.put(pos, state);
            });
            tracked.putAll(built);
            rooms.set(anchor, new Room(jobBlueprint, Map.copyOf(tracked), Map.copyOf(ports), occupied, jobPalette));
        } else {
            int index = rooms.size();
            if (dock != null) {
                rooms.get(anchor).occupied().add(dock);
                occupied.add(dock.getOpposite());
                roomLinks.add(new RoomLink(anchor, index, rooms.get(anchor).ports().get(dock), ports.get(dock.getOpposite())));
            }
            rooms.add(new Room(jobBlueprint, Map.copyOf(built), Map.copyOf(ports), occupied, jobPalette));
            if (level instanceof ServerLevel server) {
                updateDoorLabels(server);
                commission(server, rooms.getLast(), dock == null ? worldPosition : ports.get(dock.getOpposite()));
                if (dock != null && server.getBlockEntity(rooms.get(anchor).ports().get(dock)) instanceof AirlockBlockEntity door) {
                    door.signalReady(server.getGameTime() + 90);
                }
            }
        }
        clearJob();
    }

    private List<RoomLink> connections() {
        var links = new ArrayList<>(roomLinks);
        var endpoints = new HashMap<BlockPos, Integer>();
        for (int i = 0; i < rooms.size(); i++) {
            var room = rooms.get(i);
            for (var side : room.occupied()) {
                var port = room.ports().get(side);
                if (port != null) endpoints.put(port, i);
            }
        }
        endpoints.forEach((pos, from) -> {
            for (var side : rooms.get(from).occupied()) {
                if (!pos.equals(rooms.get(from).ports().get(side))) continue;
                var end = pos.relative(side);
                var to = endpoints.get(end);
                if (to != null && from < to && end.equals(rooms.get(to).ports().get(side.getOpposite()))) {
                    var link = new RoomLink(from, to, pos, end);
                    if (!links.contains(link)) links.add(link);
                }
            }
        });
        return links;
    }

    String guideTo(ServerLevel level, int destination) {
        if (!isIdle() || destination < 0 || destination >= rooms.size()) return "Selecione uma sala no mapa.";
        var links = connections();
        var queue = new ArrayDeque<Integer>();
        var parents = new HashMap<Integer, RoomLink>();
        var seen = new HashSet<Integer>();
        queue.add(0);
        seen.add(0);
        while (!queue.isEmpty() && !seen.contains(destination)) {
            int node = queue.remove();
            for (var link : links) {
                RoomLink forward = link.from() == node ? link : link.to() == node
                        ? new RoomLink(node, link.from(), link.end(), link.start()) : null;
                if (forward != null && seen.add(forward.to())) {
                    parents.put(forward.to(), forward);
                    queue.add(forward.to());
                }
            }
        }
        if (!seen.contains(destination)) return "A sala não tem um caminho registrado desde o núcleo.";
        var path = new ArrayList<RoomLink>();
        for (int node = destination; node != 0; ) {
            var link = parents.get(node);
            path.addFirst(link);
            node = link.from();
        }
        var lights = new LinkedHashSet<BlockPos>();
        for (var link : path) {
            rooms.get(link.from()).shell().entrySet().stream().filter(entry -> isFixture(entry.getValue())
                    && entry.getKey().closerThan(link.start(), 13))
                    .sorted(Comparator.comparingDouble(entry -> -entry.getKey().distSqr(link.start())))
                    .limit(12).map(Map.Entry::getKey).forEach(lights::add);
            lights.add(link.start().above());
            var target = link.end();
            for (int step = 1; step < 7; step++) {
                double fraction = step / 7d;
                var pos = BlockPos.containing(link.start().getX() + .5 + (target.getX() - link.start().getX()) * fraction,
                        link.start().getY() + .5 + (target.getY() - link.start().getY()) * fraction,
                        link.start().getZ() + .5 + (target.getZ() - link.start().getZ()) * fraction);
                boolean eastWest = target.getX() != link.start().getX();
                var aisle = pos.relative(eastWest ? Direction.SOUTH : Direction.EAST);
                lights.add(aisle.above());
                var fixture = aisle.above(4);
                if (level.hasChunkAt(fixture) && level.getBlockState(fixture).getBlock() instanceof OrbitalLuminaire) {
                    lights.add(fixture);
                }
            }
            lights.add(target.above());
        }
        routeLights = lights.isEmpty() ? List.of(worldPosition.above()) : List.copyOf(lights);
        routeUntil = level.getGameTime() + 900;
        updateDoorLabels(level);
        return "Siga as luzes até " + roomName(level, destination) + ".";
    }

    private void tickRoute(ServerLevel level) {
        long time = level.getGameTime();
        if (routeLights.isEmpty() || time > routeUntil || time % 3 != 0) return;
        int start = (int) (time / 3 % routeLights.size());
        for (int i = 0; i < Math.min(5, routeLights.size()); i++) {
            var pos = routeLights.get((start + i) % routeLights.size());
            if (!level.hasChunkAt(pos)) continue;
            var state = level.getBlockState(pos);
            if (state.getBlock() instanceof OrbitalLuminaire) {
                if (i == 0 && state.getValue(BlockStateProperties.LIT)) {
                    level.setBlock(pos, state.setValue(BlockStateProperties.LIT, false), Block.UPDATE_CLIENTS);
                    level.scheduleTick(pos, state.getBlock(), 3);
                }
                level.sendParticles(ParticleTypes.END_ROD, pos.getX() + .5, pos.getY() + 1.08,
                        pos.getZ() + .5, 3, .10, .10, .10, .015);
            } else {
                level.sendParticles(ParticleTypes.END_ROD, pos.getX() + .5, pos.getY() + .58,
                        pos.getZ() + .5, 3, .10, .10, .10, .015);
            }
        }
    }

    private void updateDoorLabels(ServerLevel level) {
        for (var link : connections()) {
            labelDoor(level, link.start(), roomName(level, link.to()), roomColors.getOrDefault(link.to(), 0));
            labelDoor(level, link.end(), roomName(level, link.from()), roomColors.getOrDefault(link.from(), 0));
        }
    }

    private static void labelDoor(ServerLevel level, BlockPos pos, String name, int color) {
        if (level.hasChunkAt(pos) && level.getBlockEntity(pos) instanceof AirlockBlockEntity door) {
            door.setSign(name, ROOM_COLORS[color]);
        }
    }

    private static boolean isFixture(BlockState state) {
        return DomePalette.isOrbitalLight(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock()));
    }

    private static void commission(ServerLevel level, Room room, BlockPos entrance) {
        if (entrance == null) return;
        room.shell().keySet().stream().filter(level::hasChunkAt).filter(pos ->
                level.getBlockState(pos).getBlock() instanceof OrbitalLuminaire)
                .sorted(Comparator.comparingDouble(pos -> pos.distSqr(entrance))).limit(64)
                .forEachOrdered(new java.util.function.Consumer<>() {
                    private int sequence;
                    @Override public void accept(BlockPos pos) {
                        var state = level.getBlockState(pos);
                        level.setBlock(pos, state.setValue(BlockStateProperties.LIT, false), Block.UPDATE_CLIENTS);
                        level.scheduleTick(pos, state.getBlock(), 4 + Math.min(44, sequence++ * 2));
                    }
                });
        for (var port : room.ports().values()) {
            if (level.hasChunkAt(port) && level.getBlockEntity(port) instanceof AirlockBlockEntity door) {
                door.signalReady(level.getGameTime() + 90);
            }
        }
    }

    private void clearJob() {
        if (fleet.landingPadCount() == 0) fleet.stop();
        else fleet.resetWork();
        state = State.IDLE;
        cursor = 0;
        completed.clear();
        planner = null;
        creativeJob = false;
        anchor = -1;
        upgrade = false;
        repair = false;
        renovation = null;
        workPriority = CorridorPlan.Priority.SHELL;
        siteSurvey = new CompoundTag();
        dock = null;
        galleryLink = null;
        connectGallery = false;
        galleryRetryAt = 0;
        job.clear();
        built.clear();
        ports = Map.of();
        jobPalette = DomePalette.EMPTY;
        clearWait();
        setChanged();
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putString("State", state.name());
        fleet.save(tag);
        tag.putUUID("DockId", dockId);
        tag.putLongArray("Docks", docks.stream().mapToLong(BlockPos::asLong).toArray());
        tag.putLongArray("ServicePorts", servicePorts.stream().mapToLong(BlockPos::asLong).toArray());
        if (planner != null) tag.putUUID("Planner", planner);
        tag.putBoolean("CreativeJob", creativeJob);
        tag.putString("JobBlueprint", jobBlueprint.toString());
        tag.put("PreferredPalette", preferredPalette.write());
        tag.put("JobPalette", jobPalette.write());
        tag.putInt("Cursor", cursor);
        tag.putLongArray("WorkDone", completed.toLongArray());
        tag.putInt("Anchor", anchor);
        tag.putBoolean("Upgrade", upgrade);
        tag.putBoolean("Repair", repair);
        if (renovation != null) tag.putString("Renovation", renovation.name());
        tag.putInt("WorkPriority", workPriority.ordinal());
        tag.put("SiteSurvey", siteSurvey.copy());
        tag.putInt("EnergyReserve", energyReserve);
        tag.putString("ShoppingName", shoppingName);
        var shoppingTag = new CompoundTag();
        shopping.forEach((id, amount) -> shoppingTag.putInt(id.toString(), amount));
        tag.put("Shopping", shoppingTag);
        tag.put("FavoritePalette", favoritePalette.write());
        var personalTags = new ListTag();
        for (var plan : personalPlans) {
            var entry = new CompoundTag();
            entry.putString("Blueprint", plan.blueprint().toString());
            entry.putString("Name", plan.name());
            entry.put("Palette", plan.palette().write());
            personalTags.add(entry);
        }
        tag.put("PersonalPlans", personalTags);
        var linkTags = new ListTag();
        for (var link : roomLinks) {
            var entry = new CompoundTag();
            entry.putInt("From", link.from());
            entry.putInt("To", link.to());
            entry.putLong("Start", link.start().asLong());
            entry.putLong("End", link.end().asLong());
            linkTags.add(entry);
        }
        tag.put("RoomLinks", linkTags);
        if (dock != null) tag.putString("Dock", dock.getSerializedName());
        if (galleryLink != null) {
            tag.putLongArray("GalleryLink", galleryLink.positions());
            tag.put("GalleryExistingPanel", NbtUtils.writeBlockState(galleryLink.existingPanel()));
            tag.put("GalleryProposedPanel", NbtUtils.writeBlockState(galleryLink.proposedPanel()));
        }
        tag.putBoolean("ConnectGallery", connectGallery);
        tag.put("Energy", energy.serializeNBT(registries));
        if (ae2 != null) ae2.save(ae2Data);
        tag.put("Ae2", ae2Data.copy());
        tag.put("Ports", writePorts(ports));
        tag.put("Built", writeShell(built));
        var workTags = new ListTag();
        for (var work : job) {
            var entry = new CompoundTag();
            entry.putLong("Pos", work.pos().asLong());
            entry.put("Target", NbtUtils.writeBlockState(work.target()));
            entry.put("Expected", NbtUtils.writeBlockState(work.expected()));
            entry.putInt("Stage", work.stage().ordinal());
            workTags.add(entry);
        }
        tag.put("Job", workTags);
        var roomTags = new ListTag();
        for (int i = 0; i < rooms.size(); i++) {
            var room = rooms.get(i);
            var entry = new CompoundTag();
            entry.putString("Blueprint", room.blueprint().toString());
            entry.put("Shell", writeShell(room.shell()));
            entry.put("Ports", writePorts(room.ports()));
            entry.put("Palette", room.palette().write());
            if (roomNames.containsKey(i)) entry.putString("Name", roomNames.get(i));
            entry.putInt("Color", roomColors.getOrDefault(i, 0));
            int occupied = 0;
            for (var dir : room.occupied()) occupied |= 1 << dir.ordinal();
            entry.putInt("Occupied", occupied);
            roomTags.add(entry);
        }
        tag.put("Rooms", roomTags);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        if (!tag.contains("State")) return;
        fleet.load(tag, tier());
        if (tag.hasUUID("DockId")) dockId = tag.getUUID("DockId");
        docks.clear();
        for (long pos : tag.getLongArray("Docks")) {
            var pad = BlockPos.of(pos);
            if (docks.size() < fleet.capacity(tier()) && DroneDockBlock.inRange(worldPosition, pad) && !docks.contains(pad)) {
                docks.add(pad);
            }
        }
        docksInitialized = false;
        servicePorts.clear();
        for (long pos : tag.getLongArray("ServicePorts")) {
            if (servicePorts.size() == 32) break;
            servicePorts.add(BlockPos.of(pos));
        }
        ae2Data = tag.getCompound("Ae2").copy();
        state = State.valueOf(tag.getString("State"));
        planner = tag.hasUUID("Planner") ? tag.getUUID("Planner") : null;
        creativeJob = tag.getBoolean("CreativeJob") && planner != null;
        jobBlueprint = readBlueprintId(tag, "JobBlueprint");
        preferredPalette = tag.contains("PreferredPalette")
                ? DomePalette.read(tag.getCompound("PreferredPalette"), false) : DomePalette.standard();
        jobPalette = DomePalette.read(tag.getCompound("JobPalette"), false);
        cursor = state == State.EXCAVATING && !tag.contains("WorkDone") ? 0 : tag.getInt("Cursor");
        completed.clear();
        completed.or(BitSet.valueOf(tag.getLongArray("WorkDone")));
        anchor = tag.getInt("Anchor");
        upgrade = tag.getBoolean("Upgrade");
        repair = tag.getBoolean("Repair");
        renovation = switch (tag.getString("Renovation")) {
            case "ROOF" -> DomePalette.Part.ROOF;
            case "FLOOR" -> DomePalette.Part.FLOOR;
            case "WALL" -> DomePalette.Part.WALL;
            default -> null;
        };
        workPriority = CorridorPlan.Priority.values()[Math.clamp(tag.getInt("WorkPriority"), 0, 2)];
        siteSurvey = tag.getCompound("SiteSurvey").copy();
        energyReserve = Math.clamp(tag.getInt("EnergyReserve"), 0, 5000);
        shoppingName = tag.getString("ShoppingName");
        shopping.clear();
        var shoppingTag = tag.getCompound("Shopping");
        for (var key : shoppingTag.getAllKeys()) {
            var id = ResourceLocation.tryParse(key);
            if (id != null && shoppingTag.getInt(key) > 0) shopping.put(id, shoppingTag.getInt(key));
        }
        favoritePalette = DomePalette.read(tag.getCompound("FavoritePalette"), false);
        personalPlans.clear();
        for (Tag value : tag.getList("PersonalPlans", Tag.TAG_COMPOUND)) {
            var entry = (CompoundTag) value;
            if (personalPlans.size() >= 12) break;
            personalPlans.add(new PersonalPlan(readBlueprintId(entry, "Blueprint"), entry.getString("Name"),
                    DomePalette.read(entry.getCompound("Palette"), false)));
        }
        dock = Direction.byName(tag.getString("Dock"));
        var connection = tag.getLongArray("GalleryLink");
        galleryLink = connection.length == 2 ? new GalleryLink(BlockPos.of(connection[0]), BlockPos.of(connection[1]),
                tag.contains("GalleryExistingPanel") ? readState(tag, "GalleryExistingPanel", registries) : cablePanel(),
                tag.contains("GalleryProposedPanel") ? readState(tag, "GalleryProposedPanel", registries) : cablePanel()) : null;
        connectGallery = galleryLink != null && tag.getBoolean("ConnectGallery");
        if (tag.contains("Energy")) energy.deserializeNBT(registries, tag.get("Energy"));
        ports = readPorts(tag.getCompound("Ports"));
        built.clear();
        built.putAll(readShell(tag.getList("Built", Tag.TAG_COMPOUND), registries));
        job.clear();
        for (Tag value : tag.getList("Job", Tag.TAG_COMPOUND)) {
            var entry = (CompoundTag) value;
            job.add(new Work(BlockPos.of(entry.getLong("Pos")), readState(entry, "Target", registries),
                    readState(entry, "Expected", registries), CorridorPlan.Priority.values()[Math.clamp(entry.getInt("Stage"), 0, 2)]));
        }
        rooms.clear();
        roomNames.clear();
        roomColors.clear();
        for (Tag value : tag.getList("Rooms", Tag.TAG_COMPOUND)) {
            var entry = (CompoundTag) value;
            var occupied = EnumSet.noneOf(Direction.class);
            for (var dir : Direction.values()) {
                if ((entry.getInt("Occupied") & (1 << dir.ordinal())) != 0) occupied.add(dir);
            }
            if (!entry.getString("Name").isEmpty()) roomNames.put(rooms.size(), entry.getString("Name"));
            roomColors.put(rooms.size(), Math.clamp(entry.getInt("Color"), 0, ROOM_COLORS.length - 1));
            rooms.add(new Room(readBlueprintId(entry, "Blueprint"), readShell(entry.getList("Shell", Tag.TAG_COMPOUND), registries),
                    readPorts(entry.getCompound("Ports")), occupied, DomePalette.read(entry.getCompound("Palette"), false)));
        }
        roomLinks.clear();
        for (Tag value : tag.getList("RoomLinks", Tag.TAG_COMPOUND)) {
            var entry = (CompoundTag) value;
            int from = entry.getInt("From"), to = entry.getInt("To");
            if (from >= 0 && to >= 0 && from < rooms.size() && to < rooms.size() && from != to) {
                roomLinks.add(new RoomLink(from, to, BlockPos.of(entry.getLong("Start")), BlockPos.of(entry.getLong("End"))));
            }
        }
    }

    CompoundTag plannerSnapshot(ServerLevel level, String message, boolean open) {
        var data = new CompoundTag();
        data.putBoolean("Open", open);
        data.putString("Message", message);
        data.putString("State", state.name());
        data.putInt("Tier", tier());
        data.put("PreferredPalette", preferredPalette.forNewConstruction().write());
        data.merge(telemetry(level));
        data.putBoolean("FreeOperation", creativeJob);
        data.putInt("Drones", fleet.count());
        data.putIntArray("DroneRoster", fleet.roster(tier()));
        data.putInt("DroneCapacity", fleet.capacity(tier()));
        data.putInt("Docks", docks.size());
        data.putInt("DockReady", fleet.landingPadCount());
        data.put("DroneJobs", fleet.diagnostics(tier(), level.getGameTime()));
        data.putBoolean("CanCenter", canCenter());
        data.putBoolean("HubUpgrade", hasHubUpgrade());
        data.putInt("Cursor", cursor + completed.cardinality());
        data.putInt("Total", job.size());
        data.putInt("CompletedRooms", rooms.size());
        data.putBoolean("Repair", repair);
        if (renovation != null) data.putString("Renovation", renovation.label);
        data.putString("WorkPriority", workPriority.label);
        data.putString("Waiting", waiting);
        if (blocked != null) data.putLong("Blocked", blocked.asLong());
        data.putBoolean("CanSkipGallery", state == State.BUILDING && cursor == job.size() && connectGallery);
        if (state == State.IDLE && open) {
            var catalog = new ListTag();
            for (var id : RoomBlueprint.available(level)) {
                if (rooms.isEmpty() ? !id.equals(hubBlueprint(tier())) && !id.equals(CAPSULE)
                        : hubTier(id) > 0 || id.equals(CAPSULE)) continue;
                try {
                    catalog.add(RoomBlueprint.describe(level, id));
                } catch (IOException | RuntimeException ignored) {
                    // Invalid datapack entries are omitted until corrected.
                }
            }
            if (!rooms.isEmpty()) for (int i = 0; i < personalPlans.size(); i++) {
                var personal = personalPlans.get(i);
                try {
                    var entry = RoomBlueprint.describe(level, personal.blueprint());
                    entry.putString("Id", OrbitalArchitect.id("personal/" + i).toString());
                    entry.putString("Name", "Minha planta · " + personal.name());
                    entry.put("Palette", personal.palette().write());
                    catalog.add(entry);
                } catch (IOException | RuntimeException ignored) {}
            }
            data.put("Catalog", catalog);
            data.put("FavoritePalette", favoritePalette.write());
            writeRoomMap(level, data);
        }
        if (state == State.PREVIEW) {
            data.merge(siteSurvey);
            data.putInt("Obstructions", obstructionCount());
            if (galleryLink != null) data.putLongArray("GalleryLink", galleryLink.positions());
            if (upgrade && hubTier(jobBlueprint) > 0) data.putInt("HubRetrofitTier", hubTier(jobBlueprint));
            addGhost(level, data);
        }
        return data;
    }

    private void writeRoomMap(ServerLevel level, CompoundTag data) {
        var anchors = new ListTag();
        for (int i = 0; i < rooms.size(); i++) {
            var room = rooms.get(i);
            var entry = new CompoundTag();
            entry.putString("Name", roomName(level, i));
            entry.putInt("Color", roomColors.getOrDefault(i, 0));
            entry.put("Sockets", writePorts(room.ports()));
            entry.putLong("Center", roomCenter(room).asLong());
            int available = 0;
            for (var dir : room.ports().keySet()) if (!room.occupied().contains(dir)) available |= 1 << dir.ordinal();
            entry.putInt("Ports", available);
            entry.putBoolean("Upgradable", room.blueprint().equals(STARTER));
            entry.putBoolean("Hub", hubTier(room.blueprint()) > 0);
            entry.putInt("HubTier", hubTier(room.blueprint()));
            anchors.add(entry);
        }
        data.put("Rooms", anchors);
        data.putIntArray("RoomLinks", connections().stream().flatMapToInt(link -> java.util.stream.IntStream.of(link.from(), link.to())).toArray());
    }

    void travelInfo(ServerPlayer player, CompoundTag data) {
        if (!canUseTravel(player)) return;
        var source = player.blockPosition();
        data.putInt("CurrentRoom", roomAt(source));
        data.putIntArray("TravelCosts", rooms.stream().mapToInt(room -> player.isCreative() ? 0 : travelCost(source, roomCenter(room))).toArray());
    }

    CompoundTag travelSnapshot(ServerPlayer player, String message, boolean open) {
        var level = player.serverLevel();
        var data = telemetry(level);
        data.putString("State", "IDLE");
        data.putInt("Tier", tier());
        data.putBoolean("TravelOnly", true);
        data.putBoolean("Open", open);
        data.putString("Message", message);
        if (open) {
            writeRoomMap(level, data);
            travelInfo(player, data);
        }
        return data;
    }

    CompoundTag telemetry(ServerLevel level) {
        var data = new CompoundTag();
        data.putInt("Energy", energy.getEnergyStored());
        data.putInt("EnergyCapacity", energy.getMaxEnergyStored());
        data.putInt("FleetEnergy", fleet.availableCharge());
        data.putInt("Consumption", fleet.consumption(level.getGameTime()));
        data.putInt("OutputConsumption", outputConsumption(level.getGameTime()));
        data.putInt("InputConsumption", inputFlow.rate(level.getGameTime()));
        data.putInt("ChargingConsumption", chargingFlow.rate(level.getGameTime()));
        data.putInt("TravelConsumption", travelFlow.rate(level.getGameTime()));
        data.putInt("ServicePorts", servicePorts.size());
        data.putInt("EnergyReserve", energyReserve);
        data.putInt("MinWorkEnergy", fleet.minCost(tier(), renovation == null
                ? ConstructionFleet.Operation.BUILD : ConstructionFleet.Operation.SWAP));
        return data;
    }

    private Map<String, Integer> requirements() {
        var counts = new TreeMap<String, Integer>();
        for (var work : job) {
            if (work.target().isAir() || matchesStructure(work.expected(), work.target())) continue;
            var key = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(work.target().getBlock());
            counts.merge(key.toString(), 1, Integer::sum);
        }
        return counts;
    }

    private void addGhost(ServerLevel level, CompoundTag data) {
        var shell = new LinkedHashMap<BlockPos, BlockState>();
        var counts = new TreeMap<String, Integer>();
        var min = job.isEmpty() ? worldPosition : job.getFirst().pos();
        var max = min;
        if (job.isEmpty() && upgrade && anchor >= 0) {
            shell.putAll(rooms.get(anchor).shell());
            for (var pos : shell.keySet()) {
                min = new BlockPos(Math.min(pos.getX(), min.getX()), Math.min(pos.getY(), min.getY()), Math.min(pos.getZ(), min.getZ()));
                max = new BlockPos(Math.max(pos.getX(), max.getX()), Math.max(pos.getY(), max.getY()), Math.max(pos.getZ(), max.getZ()));
            }
        }
        for (int i = 0; i < job.size(); i++) {
            var work = job.get(i);
            var pos = work.pos();
            min = new BlockPos(Math.min(pos.getX(), min.getX()), Math.min(pos.getY(), min.getY()),
                    Math.min(pos.getZ(), min.getZ()));
            max = new BlockPos(Math.max(pos.getX(), max.getX()), Math.max(pos.getY(), max.getY()),
                    Math.max(pos.getZ(), max.getZ()));
            if (work.target().isAir()) continue;
            shell.put(pos, work.target());
            if (work.target().getBlock() instanceof AirlockBlock door) {
                var panels = door.panels(pos, work.target());
                shell.putAll(panels);
            }
            if (!matchesStructure(work.expected(), work.target())) {
                var key = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(work.target().getBlock());
                counts.merge(key.toString(), 1, Integer::sum);
            }
        }
        data.putLong("Min", min.asLong());
        data.putLong("Max", max.asLong());
        data.putLongArray("Ghost", ghostBoxes(new LinkedHashSet<>(shell.keySet())));
        writeMaterialGeometry(data, shell);
        if (renovation != null) {
            var unchanged = new LinkedHashSet<>(rooms.get(anchor).shell().keySet());
            unchanged.removeAll(shell.keySet());
            data.putLongArray("OriginGhost", ghostBoxes(unchanged));
        }
        writeDoorCladding(data, jobPalette);
        data.put("Ports", writePorts(ports));
        if (dock != null && anchor >= 0) {
            var connection = rooms.get(anchor).ports().get(dock);
            if (connection != null) data.putLong("Dock", connection.asLong());
            addDockContext(data, rooms.get(anchor), connection);
        }
        var costs = new ArrayList<CompoundTag>();
        var inventories = adjacentInventories(level);
        counts.forEach((id, amount) -> {
            var entry = new CompoundTag();
            entry.putString("Block", id);
            entry.putInt("Count", amount);
            var block = net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(ResourceLocation.parse(id));
            entry.putInt("Stock", countStock(new ItemStack(block), amount, inventories));
            costs.add(entry);
        });
        costs.sort(Comparator.comparing(cost -> cost.getInt("Stock") >= cost.getInt("Count")));
        var costTags = new ListTag();
        costTags.addAll(costs);
        data.put("Costs", costTags);
        int operations = counts.values().stream().mapToInt(Integer::intValue).sum();
        int cuts = obstructionCount();
        var operation = renovation == null ? ConstructionFleet.Operation.BUILD : ConstructionFleet.Operation.SWAP;
        data.putInt("EnergyLow", operations * fleet.minCost(tier(), operation)
                + cuts * fleet.minCost(tier(), ConstructionFleet.Operation.DIG));
        data.putInt("EnergyHigh", operations * fleet.maxCost(tier(), operation)
                + cuts * fleet.maxCost(tier(), ConstructionFleet.Operation.DIG));
    }

    private int countStock(ItemStack material, int needed, List<IItemHandler> inventories) {
        int found = ae2 == null ? 0 : ae2.available(material, needed);
        var counted = Collections.newSetFromMap(new IdentityHashMap<IItemHandler, Boolean>());
        for (var inventory : inventories) {
            if (!counted.add(inventory)) continue;
            for (int slot = 0; slot < inventory.getSlots() && found < needed; slot++) {
                var stack = inventory.getStackInSlot(slot);
                if (!ItemStack.isSameItemSameComponents(material, stack)) continue;
                var extracted = inventory.extractItem(slot, needed - found, true);
                if (ItemStack.isSameItemSameComponents(material, extracted)) found += extracted.getCount();
            }
        }
        return Math.min(found, needed);
    }

    private void addDockContext(CompoundTag data, Room origin, BlockPos dockPosition) {
        data.putLongArray("OriginGhost", ghostBoxes(new LinkedHashSet<>(origin.shell().keySet())));
        if (dockPosition == null) return;
        var nearby = new LinkedHashSet<BlockPos>();
        for (var room : rooms) {
            if (room == origin || room.ports().values().stream().noneMatch(port -> port.closerThan(dockPosition, 52))) {
                continue;
            }
            nearby.addAll(room.shell().keySet());
            if (nearby.size() >= 40_000) break;
        }
        data.putLongArray("BuiltGhost", ghostBoxes(nearby));
    }

    private static void writeMaterialGeometry(CompoundTag data, Map<BlockPos, BlockState> geometry) {
        if (geometry.size() > 24_000) return;
        var groups = new LinkedHashMap<BlockState, LinkedHashSet<BlockPos>>();
        geometry.forEach((pos, state) -> groups.computeIfAbsent(state, ignored -> new LinkedHashSet<>()).add(pos));
        var materials = new ListTag();
        groups.forEach((state, positions) -> {
            var material = new CompoundTag();
            material.put("State", NbtUtils.writeBlockState(state));
            material.putLongArray("Boxes", ghostBoxes(positions));
            materials.add(material);
        });
        data.put("Materials", materials);
    }

    private static void writeDoorCladding(CompoundTag data, DomePalette palette) {
        var cladding = palette.choices().get(DomePalette.Part.DOOR);
        if (cladding != null) data.put("DoorCladding", NbtUtils.writeBlockState(cladding));
    }

    // Merge adjacent shell voxels into solid boxes before sending the preview.
    private static List<Long> ghostBoxes(LinkedHashSet<BlockPos> remaining) {
        var boxes = new ArrayList<Long>();
        while (!remaining.isEmpty() && boxes.size() < 4800) {
            var start = remaining.getFirst();
            int width = 1;
            int depth = 1;
            int height = 1;
            while (remaining.contains(start.east(width))) width++;
            while (hasGhostRow(remaining, start.south(depth), width)) depth++;
            while (hasGhostLayer(remaining, start.above(height), width, depth)) height++;
            for (var pos : BlockPos.betweenClosed(start, start.offset(width - 1, height - 1, depth - 1))) {
                remaining.remove(pos);
            }
            boxes.add(start.asLong());
            boxes.add(start.offset(width, height, depth).asLong());
        }
        return boxes;
    }

    private static boolean hasGhostLayer(Set<BlockPos> remaining, BlockPos start, int width, int depth) {
        for (int z = 0; z < depth; z++) {
            if (!hasGhostRow(remaining, start.south(z), width)) return false;
        }
        return true;
    }

    private static boolean hasGhostRow(Set<BlockPos> remaining, BlockPos start, int width) {
        for (int x = 0; x < width; x++) {
            if (!remaining.contains(start.east(x))) return false;
        }
        return true;
    }

    private static ResourceLocation readBlueprintId(CompoundTag tag, String key) {
        var id = tag.contains(key) ? ResourceLocation.tryParse(tag.getString(key)) : null;
        return id == null ? STARTER : id;
    }

    private static ListTag writeShell(Map<BlockPos, BlockState> shell) {
        var list = new ListTag();
        shell.forEach((pos, state) -> {
            var entry = new CompoundTag();
            entry.putLong("Pos", pos.asLong());
            entry.put("Block", NbtUtils.writeBlockState(state));
            list.add(entry);
        });
        return list;
    }

    private static Map<BlockPos, BlockState> readShell(ListTag list, HolderLookup.Provider registries) {
        var shell = new LinkedHashMap<BlockPos, BlockState>();
        for (Tag value : list) {
            var entry = (CompoundTag) value;
            shell.put(BlockPos.of(entry.getLong("Pos")), readState(entry, "Block", registries));
        }
        return shell;
    }

    private static BlockState readState(CompoundTag entry, String key, HolderLookup.Provider registries) {
        return NbtUtils.readBlockState(registries.lookupOrThrow(net.minecraft.core.registries.Registries.BLOCK),
                entry.getCompound(key));
    }

    private static CompoundTag writePorts(Map<Direction, BlockPos> ports) {
        var tag = new CompoundTag();
        ports.forEach((dir, pos) -> tag.putLong(dir.getSerializedName(), pos.asLong()));
        return tag;
    }

    private static Map<Direction, BlockPos> readPorts(CompoundTag tag) {
        var ports = new EnumMap<Direction, BlockPos>(Direction.class);
        for (var dir : Direction.values()) {
            if (tag.contains(dir.getSerializedName())) ports.put(dir, BlockPos.of(tag.getLong(dir.getSerializedName())));
        }
        return ports;
    }
}


























