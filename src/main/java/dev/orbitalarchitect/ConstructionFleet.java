package dev.orbitalarchitect;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.EnumSet;
import java.util.List;
import java.util.function.IntUnaryOperator;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

final class ConstructionFleet {
    private static final int SHOT_TICKS = 5;
    private static final int DISMANTLE_TICKS = 7;
    static final int RETURN_PERCENT = 20;
    static final int PICKUP_TICKS = 6;
    static final int PLACE_TICKS = 4;
    private final BlockPos anchor;
    private final List<Unit> units = new ArrayList<>(List.of(new Unit(0)));
    private final long[] readyAt = new long[DroneTier.values().length + 1];
    private final Flight[] flights = new Flight[readyAt.length];
    private final BitSet reserved = new BitSet();
    private final Landing[] landings = new Landing[readyAt.length];
    private final List<ConstructionDrone> drones = new ArrayList<>();
    private List<BlockPos> landingPads = List.of();
    private int chargeStart;
    enum Operation { BUILD, DIG, SWAP }

    ConstructionFleet(BlockPos anchor) { this.anchor = anchor.immutable(); }

    record Order(int index, BlockPos target) {}
    record Route(List<Order> orders, BlockPos stock, ItemStack cargo, boolean excavating) {}
    interface Dispatcher {
        Route select(int slot, boolean prefersDigging, int buildLimit, int digLimit, BitSet reserved);
    }
    interface WorkAction {
        boolean complete(int slot, int index, boolean excavating);
    }

    private static final class Flight {
        private final Route route;
        private final ConstructionDrone drone;
        private final double speed;
        private final Vec3 pickup;
        private final boolean refit;
        private int stop;
        private long began;
        private Vec3 start;
        private Vec3 destination;
        private int approach;
        private int carry;
        private boolean collected;

        Flight(Route route, ConstructionDrone drone, double speed, Vec3 pickup, boolean refit) {
            this.route = route;
            this.drone = drone;
            this.speed = speed;
            this.pickup = pickup;
            this.refit = refit;
        }

        Order order() { return route.orders().get(stop); }
    }
    private record Landing(Vec3 start, Vec3 pad, long began, int duration) {}
    record Recovered(DroneTier tier, List<DroneUpgrade> upgrades, int charge) {}

    private static final class Unit {
        private final int tier;
        private final EnumSet<DroneUpgrade> upgrades = EnumSet.noneOf(DroneUpgrade.class);
        private int charge;
        private boolean recharging = true;
        private final int[] usage = new int[20];
        private final long[] usageAt = new long[20];

        Unit(int tier) { this.tier = tier; }

        DroneTier tier(int altarTier) { return DroneTier.at(tier == 0 ? altarTier : tier); }

        int capacity(int altarTier) {
            return tier(altarTier).battery * (upgrades.contains(DroneUpgrade.BATTERY) ? 2 : 1);
        }

        int workEnergy(int altarTier) {
            return Math.max(0, charge - capacity(altarTier) * RETURN_PERCENT / 100);
        }

        int cost(int altarTier, Operation operation) {
            if (operation == Operation.SWAP) return cost(altarTier, Operation.BUILD) + cost(altarTier, Operation.DIG);
            var spec = tier(altarTier);
            int base = operation == Operation.DIG ? spec.digCost : spec.buildCost;
            if (upgrades.contains(DroneUpgrade.OVERCLOCK)) base = (base * 3 + 1) / 2;
            return upgrades.contains(DroneUpgrade.EFFICIENCY) ? (base * 3 + 2) / 4 : base;
        }

        int interval(int altarTier) {
            int interval = tier(altarTier).interval;
            return upgrades.contains(DroneUpgrade.OVERCLOCK) ? interval * 3 / 4 : interval;
        }

        int batch(int altarTier, boolean creative, boolean excavating) {
            var tier = tier(altarTier);
            int batch = creative ? tier.creativeBlocks : tier.blocks;
            if (excavating && upgrades.contains(DroneUpgrade.EXCAVATOR)) batch += Math.max(2, batch / 2);
            if (upgrades.contains(DroneUpgrade.OVERCLOCK)) batch += Math.max(1, batch / 3);
            return batch;
        }

        double speed(int altarTier) {
            return tier(altarTier).shotSpeed * (upgrades.contains(DroneUpgrade.OVERCLOCK) ? 1.30 : 1);
        }

        int consumed(long time) {
            int sum = 0;
            for (int i = 0; i < usage.length; i++) if (time - usageAt[i] < usage.length) sum += usage[i];
            return sum;
        }

        void spend(int amount, long time) {
            charge -= amount;
            int index = (int) (time % usage.length);
            if (usageAt[index] != time) usage[index] = 0;
            usageAt[index] = time;
            usage[index] += amount;
        }
    }

    int count() { return units.size(); }

    int capacity(int altarTier) { return altarTier + 1; }

    boolean inFlight() { return !reserved.isEmpty(); }

    void setLandingPads(List<BlockPos> pads) { landingPads = List.copyOf(pads); }

    int landingPadCount() { return landingPads.size(); }

    private BlockPos padFor(int slot) {
        if (slot == 0) return landingPads.size() >= count() ? landingPads.get(count() - 1) : null;
        return slot <= landingPads.size() ? landingPads.get(slot - 1) : null;
    }

    int dockMode(BlockPos pad) {
        int slot = slotFor(pad);
        var flight = slot < 0 ? null : flights[slot];
        if (flight == null) return 0;
        if (flight.route.excavating()) return 1;
        return flight.drone.cargoPhase() == ConstructionDrone.CargoPhase.PLACE ? 2 : 3;
    }

    String dockTask(BlockPos pad, int altarTier) {
        int slot = slotFor(pad);
        if (slot < 0) return "";
        var flight = flights[slot];
        if (flight == null) {
            if (landings[slot] != null) return "Drone voltando para a doca.";
            var unit = units.get(slot);
            if (unit.recharging) return "Recarga · " + unit.charge + "/" + unit.capacity(altarTier) + " FE";
            return "";
        }
        if (flight.route.excavating()) return "Escavando em " + flight.order().target().toShortString();
        if (flight.drone.cargoPhase() == ConstructionDrone.CargoPhase.PLACE) {
            return "Montando em " + flight.order().target().toShortString();
        }
        return "Transporte · " + (flight.route.orders().size() - flight.stop) + " peças de "
                + flight.route.stock().toShortString() + " para " + flight.order().target().toShortString();
    }

    private int slotFor(BlockPos pad) {
        for (int slot = 0; slot < count(); slot++) {
            if (pad.equals(padFor(slot))) return slot;
        }
        return -1;
    }

    boolean isDroneInReach(ServerPlayer player, int slot) {
        if (slot < 0 || slot >= drones.size()) return false;
        var drone = drones.get(slot);
        return drone != null && !drone.isRemoved() && player.distanceToSqr(drone) <= 36;
    }

    void park(ServerLevel level, int altarTier) {
        for (int slot = 0; slot < count(); slot++) {
            if (flights[slot] == null) park(level, altarTier, slot);
        }
    }

    private Vec3 home(ServerLevel level, int slot) {
        var pad = padFor(slot);
        if (pad != null && level.hasChunkAt(pad) && level.getBlockState(pad).is(OrbitalArchitect.DRONE_DOCK.get())
                && DroneDockBlock.canLand(level, pad)) return Vec3.atBottomCenterOf(pad).add(0, .28, 0);
        return slot == 0 && level.hasChunkAt(anchor) ? Vec3.atCenterOf(anchor).add(0, .85, 0) : null;
    }

    private void park(ServerLevel level, int altarTier, int slot) {
        var unit = units.get(slot);
        var position = home(level, slot);
        if (position == null) {
            if (slot < drones.size() && drones.get(slot) != null) {
                drones.get(slot).discard();
                drones.set(slot, null);
            }
            landings[slot] = null;
            return;
        }
        unit.recharging = unit.charge < unit.capacity(altarTier);
        var tier = unit.tier(altarTier);
        var drone = droneAt(level, slot, tier, position);
        if (drone == null) return;
        drone.setTier(tier);
        if (drone.cargoPhase() != ConstructionDrone.CargoPhase.EMPTY) {
            drone.setCargo(ItemStack.EMPTY, ConstructionDrone.CargoPhase.EMPTY, 0);
        }
        if (drone.position().distanceToSqr(position) < .0025) {
            drone.setDocked(true);
            landings[slot] = null;
            return;
        }
        drone.setDocked(false);
        long time = level.getGameTime();
        var landing = landings[slot];
        if (landing == null || !landing.pad().equals(position)) {
            int duration = travelTime(drone.position(), position, unit.speed(altarTier), 6, 65);
            landing = new Landing(drone.position(), position, time, duration);
            landings[slot] = landing;
        }
        double progress = Math.min(1, (double) (time - landing.began()) / landing.duration());
        moveDrone(drone, arc(landing.start(), position, progress), position);
        if (progress == 1) {
            drone.setDocked(true);
            landings[slot] = null;
        }
    }

    ListTag diagnostics(int altarTier, long time) {
        var result = new ListTag();
        for (int i = 0; i < count(); i++) {
            var data = new CompoundTag();
            var unit = units.get(i);
            data.putInt("Energy", unit.charge);
            data.putInt("Capacity", unit.capacity(altarTier));
            data.putInt("Cost", unit.cost(altarTier, Operation.BUILD));
            data.putInt("DigCost", unit.cost(altarTier, Operation.DIG));
            data.putInt("SwapCost", unit.cost(altarTier, Operation.SWAP));
            data.putInt("Consumption", unit.consumed(time));
            data.putInt("Speed", (int) Math.round(unit.speed(altarTier) * 20));
            data.putInt("Batch", unit.batch(altarTier, false, false));
            data.putInt("DigBatch", unit.batch(altarTier, false, true));
            data.putInt("CreativeBatch", unit.batch(altarTier, true, false));
            data.putInt("CreativeDigBatch", unit.batch(altarTier, true, true));
            data.putIntArray("Upgrades", unit.upgrades.stream().mapToInt(Enum::ordinal).toArray());
            var flight = flights[i];
            if (flight != null) {
                data.putLong("Target", flight.order().target().asLong());
                data.putInt("Remaining", flight.route.orders().size() - flight.stop);
                if (!flight.route.excavating()) data.putLong("StockPos", flight.route.stock().asLong());
                if (!flight.route.cargo().isEmpty()) {
                    data.putString("Cargo", net.minecraft.core.registries.BuiltInRegistries.ITEM
                            .getKey(flight.route.cargo().getItem()).toString());
                }
                String phase = flight.route.excavating() ? "Escavação" : switch (flight.drone.cargoPhase()) {
                    case EMPTY -> "A caminho";
                    case PICKUP -> "Coleta";
                    case CARRY -> "Em voo";
                    case PLACE -> flight.refit ? "Troca" : "Montagem";
                };
                data.putString("Phase", phase);
            } else if (landings[i] != null) {
                data.putString("Phase", "Voltando");
                data.putLong("Target", BlockPos.containing(landings[i].pad()).asLong());
            } else if (i < drones.size() && drones.get(i) != null && drones.get(i).isDocked()) {
                data.putString("Phase", unit.recharging ? "Recarregando" : "Pronto");
            } else if (i > 0 && padFor(i) == null) {
                data.putString("Phase", "Sem doca");
            }
            result.add(data);
        }
        return result;
    }

    int[] roster(int altarTier) {
        return units.stream().mapToInt(unit -> unit.tier(altarTier).level).toArray();
    }

    boolean install(DroneTier tier, int altarTier) {
        if (tier.level > altarTier || count() >= capacity(altarTier)) return false;
        units.add(new Unit(tier.level));
        return true;
    }

    String upgrade(int slot, int altarTier, DroneUpgrade upgrade) {
        if (slot < 0 || slot >= count()) return "Drone indisponível.";
        var unit = units.get(slot);
        int tier = unit.tier(altarTier).level;
        if (tier < 2) return "Os módulos exigem um drone T2 ou superior.";
        if (unit.upgrades.contains(upgrade)) return "Este módulo já está instalado.";
        if (unit.upgrades.size() >= tier - 1) return "Todos os encaixes deste drone estão ocupados.";
        unit.upgrades.add(upgrade);
        return "";
    }

    Recovered withdraw(int slot) {
        if (slot <= 0 || slot >= count()) return null;
        var unit = units.remove(slot);
        stop();
        return new Recovered(DroneTier.at(unit.tier), List.copyOf(unit.upgrades), unit.charge);
    }

    List<DroneUpgrade> integratedUpgrades() { return List.copyOf(units.getFirst().upgrades); }

    int charge(ServerLevel level, int altarTier, IntUnaryOperator source) {
        final int budget = 40;
        int used = 0;
        for (int offset = 0; offset < count() && used < budget; offset++) {
            int slot = (chargeStart + offset) % count();
            if (flights[slot] != null || slot >= drones.size()) continue;
            var drone = drones.get(slot);
            if (drone == null || drone.isRemoved() || !drone.isDocked()) continue;
            var home = home(level, slot);
            if (home == null || drone.position().distanceToSqr(home) >= .0025) continue;
            var unit = units.get(slot);
            int rate = 4 + 4 * unit.tier(altarTier).level;
            int transfer = source.applyAsInt(Math.min(budget - used, Math.min(rate, Math.max(0, unit.capacity(altarTier) - unit.charge))));
            unit.charge += transfer;
            unit.recharging = unit.charge < unit.capacity(altarTier);
            used += transfer;
        }
        chargeStart = (chargeStart + 1) % count();
        return used;
    }

    int availableCharge() { return units.stream().mapToInt(unit -> unit.charge).sum(); }

    boolean canOperate(int altarTier, Operation operation) {
        for (int slot = 0; slot < count(); slot++) {
            var unit = units.get(slot);
            if ((slot == 0 || padFor(slot) != null) && !unit.recharging && landings[slot] == null
                    && unit.workEnergy(altarTier) >= unit.cost(altarTier, operation)) return true;
        }
        return false;
    }

    int minCost(int altarTier, Operation operation) {
        return units.stream().mapToInt(unit -> unit.cost(altarTier, operation)).min().orElseThrow();
    }

    int maxCost(int altarTier, Operation operation) {
        return units.stream().mapToInt(unit -> unit.cost(altarTier, operation)).max().orElseThrow();
    }

    int consumption(long time) { return units.stream().mapToInt(unit -> unit.consumed(time)).sum(); }

    void spendForOperation(int slot, int altarTier, Operation operation, long time) {
        var unit = units.get(slot);
        unit.spend(unit.cost(altarTier, operation), time);
    }

    void spendAuxiliary(int energy, long time) {
        for (var unit : units) {
            int used = Math.min(energy, unit.charge);
            unit.spend(used, time);
            energy -= used;
            if (energy == 0) return;
        }
    }

    void save(CompoundTag tag) {
        var stored = new ListTag();
        for (var unit : units) {
            var data = new CompoundTag();
            data.putInt("Tier", unit.tier);
            data.putInt("Energy", unit.charge);
            data.putIntArray("Upgrades", unit.upgrades.stream().mapToInt(Enum::ordinal).toArray());
            stored.add(data);
        }
        tag.put("Fleet", stored);
    }

    void load(CompoundTag tag, int altarTier) {
        units.clear();
        units.add(new Unit(0));
        if (tag.contains("Fleet", Tag.TAG_LIST)) {
            var stored = tag.getList("Fleet", Tag.TAG_COMPOUND);
            for (int i = 0; i < stored.size() && count() <= capacity(altarTier); i++) {
                var data = stored.getCompound(i);
                int tier = data.getInt("Tier");
                if (i > 0 && (tier < 1 || tier > altarTier || count() == capacity(altarTier))) continue;
                var unit = i == 0 ? units.getFirst() : new Unit(tier);
                if (i > 0) units.add(unit);
                var upgrades = DroneUpgrade.values();
                int limit = unit.tier(altarTier).level - 1;
                for (int upgrade : data.getIntArray("Upgrades")) {
                    if (unit.upgrades.size() >= limit) break;
                    if (upgrade >= 0 && upgrade < upgrades.length) unit.upgrades.add(upgrades[upgrade]);
                }
                unit.charge = Math.clamp(data.getInt("Energy"), 0, unit.capacity(altarTier));
            }
            return;
        }
        var stored = tag.getIntArray("Drones");
        for (int i = 0; i < stored.length && i < altarTier; i++) {
            int amount = Math.clamp(stored[i], 0, capacity(altarTier) - count());
            for (int n = 0; n < amount; n++) units.add(new Unit(i + 1));
        }
    }

    void work(ServerLevel level, int altarTier, boolean creative, boolean renovating, boolean excavating,
              Dispatcher dispatch, WorkAction action) {
        long time = level.getGameTime();
        for (int slot = 0; slot < count(); slot++) {
            var flight = flights[slot];
            if (flight != null) {
                if (!animate(level, flight, time)) continue;
                var order = flight.order();
                boolean done = action.complete(slot, order.index(), flight.route.excavating());
                reserved.clear(order.index());
                if (done && ++flight.stop < flight.route.orders().size()) {
                    startLeg(flight, time);
                    continue;
                }
                if (!done) {
                    for (int i = flight.stop; i < flight.route.orders().size(); i++) {
                        reserved.clear(flight.route.orders().get(i).index());
                    }
                    readyAt[slot] = time + 10;
                }
                flight.drone.setCargo(ItemStack.EMPTY, ConstructionDrone.CargoPhase.EMPTY, 0);
                flights[slot] = null;
                if (done) readyAt[slot] = Math.max(readyAt[slot], time + units.get(slot).interval(altarTier) / 4);
                continue;
            }
            var unit = units.get(slot);
            var origin = home(level, slot);
            if (origin == null || landings[slot] != null) {
                park(level, altarTier, slot);
                continue;
            }
            int buildPower = creative ? Integer.MAX_VALUE
                    : unit.workEnergy(altarTier) / unit.cost(altarTier, renovating ? Operation.SWAP : Operation.BUILD);
            int digPower = !excavating ? 0 : creative ? Integer.MAX_VALUE : unit.workEnergy(altarTier) / unit.cost(altarTier, Operation.DIG);
            if (!creative && (unit.recharging || buildPower == 0 && digPower == 0)) {
                park(level, altarTier, slot);
                continue;
            }
            if (time < readyAt[slot]) continue;
            int buildLimit = Math.min(buildPower, unit.batch(altarTier, creative, false));
            int digLimit = Math.min(digPower, unit.batch(altarTier, creative, true));
            boolean digs = count() == 1 || slot % 2 == 1 || unit.upgrades.contains(DroneUpgrade.EXCAVATOR);
            var route = dispatch.select(slot, digs, buildLimit, digLimit, reserved);
            if (route == null) {
                readyAt[slot] = time + 5;
                continue;
            }
            var tier = unit.tier(altarTier);
            double angle = slot * Math.PI * 2 / count();
            Vec3 pickup = Vec3.atCenterOf(route.stock()).add(Math.cos(angle) * .44, 1.2, Math.sin(angle) * .44);
            var drone = droneAt(level, slot, tier, origin);
            if (drone == null) continue;
            takeOff(drone, slot);
            readyAt[slot] = time + unit.interval(altarTier);
            route.orders().forEach(order -> reserved.set(order.index()));
            flight = new Flight(route, drone, unit.speed(altarTier), pickup, renovating);
            flights[slot] = flight;
            startLeg(flight, time);
        }
    }

    private static void startLeg(Flight flight, long time) {
        flight.began = time;
        flight.start = flight.drone.position();
        Vec3 target = Vec3.atCenterOf(flight.order().target());
        if (flight.route.excavating()) {
            flight.destination = target.add(-.34, .86, .24).add(0, flight.speed * 2.5, 0);
            flight.approach = travelTime(flight.start, flight.destination, flight.speed, 4, 65);
            flight.carry = SHOT_TICKS;
        } else {
            flight.destination = target.add(0, .92, 0);
            flight.approach = flight.collected ? 0 : travelTime(flight.start, flight.pickup, flight.speed, 3, 65);
            flight.carry = travelTime(flight.collected ? flight.start : flight.pickup,
                    flight.destination, flight.speed, 3, 70);
        }
    }

    private void takeOff(ConstructionDrone drone, int index) {
        landings[index] = null;
        drone.setDocked(false);
    }

    private static int travelTime(Vec3 from, Vec3 to, double speed, int minimum, int maximum) {
        return Math.clamp((int) Math.ceil(from.distanceTo(to) / speed), minimum, maximum);
    }

    private static boolean animate(ServerLevel level, Flight flight, long time) {
        long elapsed = time - flight.began;
        var drone = flight.drone;
        var target = flight.destination;
        if (flight.route.excavating()) {
            var block = Vec3.atCenterOf(flight.order().target());
            if (elapsed < flight.approach) {
                moveDrone(drone, arc(flight.start, target, (double) elapsed / flight.approach), block);
                return false;
            }
            int shot = (int) (elapsed - flight.approach);
            if (shot >= SHOT_TICKS) {
                level.sendParticles(ParticleTypes.ELECTRIC_SPARK, block.x, block.y, block.z, 3, .14, .14, .14, .05);
                return true;
            }
            var point = target.lerp(block, (shot + .5) / SHOT_TICKS);
            level.sendParticles(new DustParticleOptions(drone.tier().color, .75f),
                    point.x, point.y, point.z, 1, 0, 0, 0, 0);
            return false;
        }
        int pickup = flight.approach == 0 ? 0 : PICKUP_TICKS;
        int departure = flight.approach + pickup;
        int arrival = departure + flight.carry;
        int placement = arrival + (flight.refit ? DISMANTLE_TICKS : 0);
        if (elapsed < flight.approach) {
            moveDrone(drone, arc(flight.start, flight.pickup, (double) elapsed / flight.approach), flight.pickup);
        } else if (elapsed < departure) {
            moveDrone(drone, flight.pickup, target);
            if (drone.cargoPhase() != ConstructionDrone.CargoPhase.PICKUP) {
                var pos = flight.pickup;
                level.playSound(null, pos.x, pos.y, pos.z, OrbitalArchitect.DRONE_PICKUP.get(),
                        SoundSource.BLOCKS, .65f, 1f);
            }
            drone.setCargo(flight.route.cargo(), ConstructionDrone.CargoPhase.PICKUP, flight.began + flight.approach);
        } else if (elapsed < arrival) {
            var from = departure == 0 ? flight.start : flight.pickup;
            moveDrone(drone, arc(from, target, (double) (elapsed - departure) / flight.carry), target);
            flight.collected = true;
            drone.setCargo(flight.route.cargo(), ConstructionDrone.CargoPhase.CARRY, flight.began + departure);
        } else if (elapsed < placement) {
            moveDrone(drone, target, target);
            flight.collected = true;
            var block = Vec3.atCenterOf(flight.order().target());
            level.sendParticles(ParticleTypes.ELECTRIC_SPARK, block.x, block.y + .55, block.z, 3, .13, .06, .13, .015);
        } else {
            moveDrone(drone, target, target);
            flight.collected = true;
            drone.setCargo(flight.route.cargo(), ConstructionDrone.CargoPhase.PLACE, flight.began + placement);
        }
        return elapsed >= placement + PLACE_TICKS;
    }

    private static Vec3 arc(Vec3 from, Vec3 to, double progress) {
        double lift = Math.min(4, from.distanceTo(to) * .27);
        return from.lerp(to, progress).add(0, Math.sin(Math.PI * progress) * lift, 0);
    }

    private ConstructionDrone droneAt(ServerLevel level, int index, DroneTier tier, Vec3 initial) {
        if (!level.hasChunkAt(BlockPos.containing(initial))) return null;
        while (drones.size() <= index) drones.add(null);
        var drone = drones.get(index);
        if (drone == null || drone.isRemoved()) {
            drone = new ConstructionDrone(OrbitalArchitect.CONSTRUCTION_DRONE.get(), level);
            drone.setTier(tier);
            drone.link(anchor, index);
            drone.moveTo(initial.x, initial.y, initial.z, 0, 0);
            if (!level.addFreshEntity(drone)) return null;
            drones.set(index, drone);
        }
        return drone;
    }

    private static void moveDrone(ConstructionDrone drone, Vec3 position, Vec3 facing) {
        drone.setPos(position.x, position.y, position.z);
        double x = facing.x - position.x;
        double z = facing.z - position.z;
        if (x * x + z * z > .01) drone.setYRot((float) Math.toDegrees(Math.atan2(-x, z)));
    }

    void placed(ServerLevel level, BlockPos pos) {
        level.playSound(null, pos, OrbitalArchitect.DRONE_PLACE.get(), SoundSource.BLOCKS, .46f, 1f);
        var center = Vec3.atCenterOf(pos);
        level.sendParticles(ParticleTypes.ELECTRIC_SPARK, center.x, center.y + .5, center.z, 3, .16, .12, .16, .04);
    }

    void stop() {
        resetWork();
        for (var drone : drones) if (drone != null) drone.discard();
        drones.clear();
        Arrays.fill(landings, null);
    }

    void resetWork() {
        Arrays.fill(flights, null);
        Arrays.fill(readyAt, 0);
        reserved.clear();
    }
}
