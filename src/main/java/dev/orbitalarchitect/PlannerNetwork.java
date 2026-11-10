package dev.orbitalarchitect;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

final class PlannerNetwork {
    static final int PREVIEW = 0;
    static final int CONFIRM = 1;
    static final int CANCEL = 2;
    static final int UPGRADE = 3;
    static final int REFRESH = 4;
    static final int BROWSE = 5;
    static final int CENTER = 6;
    static final int UPDATE_JOINS = 7;
    static final int INSTALL_CARD = 8;
    static final int UPGRADE_HUB = 9;
    static final int RAISE_HUB = 10;
    static final int RECALL_DRONE = 11;
    static final int SKIP_GALLERY = 12;
    static final int DRONE_UPGRADE = 13;
    static final int TELEMETRY = 14;
    static final int REPAIR = 15;
    static final int PIN = 16;
    static final int EDIT_ROOM = 17;
    static final int SAVE_PERSONAL = 18;
    static final int GUIDE = 19;
    static final int SET_RESERVE = 20;
    static final int TERMINAL_REFRESH = 21;
    static final int RENOVATE = 22;
    static final int OPEN_TRAVEL = 23;
    static final int TELEPORT = 24;

    record Request(BlockPos altar, int action, String blueprint, int room, int face, boolean clearObstructions,
                   boolean connectGallery, CompoundTag materials, CorridorPlan.Options options) implements CustomPacketPayload {
        Request(BlockPos altar, int action, String blueprint, int room, int face, boolean clearObstructions,
                boolean connectGallery, CompoundTag materials) {
            this(altar, action, blueprint, room, face, clearObstructions, connectGallery, materials, CorridorPlan.Options.DEFAULT);
        }
        static final Type<Request> TYPE = new Type<>(OrbitalArchitect.id("planner_request"));
        static final StreamCodec<RegistryFriendlyByteBuf, Request> CODEC = StreamCodec.of(
                (buf, value) -> {
                    buf.writeBlockPos(value.altar);
                    buf.writeByte(value.action);
                    buf.writeUtf(value.blueprint, 256);
                    buf.writeVarInt(value.room);
                    buf.writeByte(value.face);
                    buf.writeBoolean(value.clearObstructions);
                    buf.writeBoolean(value.connectGallery);
                    for (var part : DomePalette.Part.values()) buf.writeUtf(value.materials.getString(part.name()), 256);
                    buf.writeByte(value.options.style().ordinal());
                    buf.writeByte(value.options.rise());
                    buf.writeByte(value.options.priority().ordinal());
                    buf.writeBoolean(value.options.pillars());
                }, buf -> new Request(buf.readBlockPos(), buf.readUnsignedByte(), buf.readUtf(256),
                        buf.readVarInt(), buf.readUnsignedByte(), buf.readBoolean(), buf.readBoolean(), readMaterials(buf), readOptions(buf)));
        @Override
        public Type<Request> type() { return TYPE; }
    }

    private static CorridorPlan.Options readOptions(RegistryFriendlyByteBuf buf) {
        var styles = CorridorPlan.Style.values();
        var priorities = CorridorPlan.Priority.values();
        return new CorridorPlan.Options(styles[Math.min(styles.length - 1, buf.readUnsignedByte())],
                Math.clamp(buf.readByte(), -1, 1), priorities[Math.min(priorities.length - 1, buf.readUnsignedByte())], buf.readBoolean());
    }

    private static CompoundTag readMaterials(RegistryFriendlyByteBuf buf) {
        var data = new CompoundTag();
        for (var part : DomePalette.Part.values()) {
            String name = buf.readUtf(256);
            if (!name.isEmpty()) data.putString(part.name(), name);
        }
        return data;
    }

    record Snapshot(BlockPos altar, CompoundTag data) implements CustomPacketPayload {
        static final Type<Snapshot> TYPE = new Type<>(OrbitalArchitect.id("planner_snapshot"));
        static final StreamCodec<RegistryFriendlyByteBuf, Snapshot> CODEC = StreamCodec.of(
                (buf, value) -> { buf.writeBlockPos(value.altar); buf.writeNbt(value.data); },
                buf -> new Snapshot(buf.readBlockPos(), buf.readNbt()));
        @Override
        public Type<Snapshot> type() { return TYPE; }
    }

    static void register(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar("11");
        registrar.playToServer(Request.TYPE, Request.CODEC, (request, context) -> {
            if (context.player() instanceof ServerPlayer player) handle(player, request);
        });
        registrar.playToClient(Snapshot.TYPE, Snapshot.CODEC, (snapshot, context) ->
                PlannerClient.receive(snapshot));
    }

    static void open(ServerPlayer player, OrbAltarBlockEntity altar) {
        send(player, altar, "", true);
    }

    static void openDrone(ServerPlayer player, OrbAltarBlockEntity altar, int slot) {
        var data = snapshot(player, altar, "", true);
        data.putInt("FocusDrone", slot);
        PacketDistributor.sendToPlayer(player, new Snapshot(altar.getBlockPos(), data));
    }

    static void openTerminal(ServerPlayer player, OrbAltarBlockEntity altar, BlockPos terminal, boolean open) {
        var data = new CompoundTag();
        data.putLong("Terminal", terminal.asLong());
        data.putBoolean("Open", open);
        data.put("Reading", altar.terminalSnapshot(player.serverLevel()));
        PacketDistributor.sendToPlayer(player, new Snapshot(altar.getBlockPos(), data));
    }

    private static void handle(ServerPlayer player, Request request) {
        var level = player.serverLevel();
        var pos = request.altar();
        if (request.action() == OPEN_TRAVEL) {
            boolean panel = request.blueprint().equals("panel") && player.canInteractWithBlock(pos, 1)
                    && level.hasChunkAt(pos);
            var orbit = panel && level.getBlockEntity(pos) instanceof OrbAltarBlockEntity local && local.canUseTravel(player)
                    ? local : OrbAltarBlockEntity.findOrbit(level, player.blockPosition(), altar -> altar.canUseTravel(player));
            if (orbit == null) {
                player.displayClientMessage(net.minecraft.network.chat.Component.literal("Entre em uma sala de um Orbit ativo para abrir o mapa."), true);
            } else if (panel && orbit.getBlockPos().equals(pos)) {
                var reading = new CompoundTag();
                reading.put("RoomNavigation", orbit.travelSnapshot(player, "", true));
                PacketDistributor.sendToPlayer(player, new Snapshot(pos, reading));
            } else {
                PacketDistributor.sendToPlayer(player, new Snapshot(orbit.getBlockPos(), orbit.travelSnapshot(player, "", true)));
            }
            return;
        }
        if (player.isSpectator() || !level.hasChunkAt(pos)
                || !(level.getBlockEntity(pos) instanceof OrbAltarBlockEntity altar)) return;
        if (request.action() == TELEPORT) {
            if (!altar.canUseTravel(player)) return;
            String result = altar.travel(player, request.room());
            if (result.startsWith("Teleporte concluído")) send(player, altar, result, false);
            else if (request.blueprint().equals("map")) {
                PacketDistributor.sendToPlayer(player, new Snapshot(pos, altar.travelSnapshot(player, result, true)));
            } else send(player, altar, result, true);
            return;
        }
        if (request.action() == TERMINAL_REFRESH || request.action() == SET_RESERVE && !request.blueprint().isEmpty()) {
            BlockPos terminal;
            try { terminal = BlockPos.of(Long.parseLong(request.blueprint())); }
            catch (NumberFormatException invalid) { return; }
            if (player.canInteractWithBlock(terminal, 1) && level.hasChunkAt(terminal)
                    && level.getBlockEntity(terminal) instanceof ServicePortBlockEntity port && port.owner(level) == altar) {
                if (request.action() == SET_RESERVE && level.mayInteract(player, terminal)
                        && level.mayInteract(player, pos)) altar.cycleReserve();
                openTerminal(player, altar, terminal, false);
            }
            return;
        }
        boolean droneAction = request.action() == REFRESH || request.action() == DRONE_UPGRADE
                || request.action() == RECALL_DRONE;
        boolean travelTelemetry = request.action() == TELEMETRY && request.blueprint().equals("map") && altar.canUseTravel(player);
        if (!player.canInteractWithBlock(pos, 1) && !travelTelemetry
                && !(droneAction && altar.isDroneInReach(player, request.room()))) return;
        var directions = Direction.values();
        if (request.face() >= directions.length) return;
        DomePalette palette = DomePalette.EMPTY;
        if (request.action() == BROWSE || request.action() == PREVIEW || request.action() == RAISE_HUB || request.action() == RENOVATE) {
            try {
                palette = DomePalette.read(request.materials(), true);
            } catch (IllegalArgumentException invalid) {
                send(player, altar, invalid.getMessage(), true);
                return;
            }
        }
        boolean close = false;
        String message;
        switch (request.action()) {
            case BROWSE -> {
                var id = ResourceLocation.tryParse(request.blueprint());
                if (id == null || !altar.isIdle()) return;
                var preview = new CompoundTag();
                String error = altar.browse(level, id, request.room(), directions[request.face()], preview, palette, request.options());
                var snapshot = snapshot(player, altar, error, true);
                snapshot.put("Browse", preview);
                PacketDistributor.sendToPlayer(player, new Snapshot(pos, snapshot));
                return;
            }
            case PREVIEW, UPGRADE, UPGRADE_HUB -> {
                var inspection = new CompoundTag();
                if (request.action() == UPGRADE_HUB) {
                    message = altar.planHubUpgrade(player, inspection);
                } else {
                    var id = ResourceLocation.tryParse(request.blueprint());
                    if (id == null) return;
                    message = altar.plan(level, id, request.room(), directions[request.face()],
                            request.action() == UPGRADE, inspection, player, palette, request.options());
                }
                sendReview(player, altar, message, inspection);
                return;
            }
            case CONFIRM -> {
                message = altar.confirm(player, request.clearObstructions(), request.connectGallery());
                close = message.isEmpty();
                if (close) message = altar.isIdle() ? "Reforma aplicada." : "Construção iniciada.";
            }
            case CANCEL -> {
                message = altar.cancelPreview(player);
            }
            case CENTER -> {
                message = altar.centerAltar(level, player);
                if (message.isEmpty()) {
                    send(player, altar, "Altar movido para o centro. Reconecte baús ou cabos quando necessário.", false);
                    return;
                }
            }
            case RAISE_HUB -> {
                var target = altar.higherHubPosition(level);
                if (target == null) {
                    message = "A elevação não está mais disponível. Revise o projeto.";
                } else {
                    message = altar.raiseAltar(level, player, target);
                    if (message.isEmpty() && level.getBlockEntity(target) instanceof OrbAltarBlockEntity raised) {
                        player.connection.send(new ClientboundBlockUpdatePacket(level, target));
                        var inspection = new CompoundTag();
                        message = raised.plan(level, OrbAltarBlockEntity.HUB, 0, Direction.NORTH, false, inspection, player, palette);
                        sendReview(player, raised, message, inspection);
                        return;
                    }
                }
            }
            case UPDATE_JOINS -> message = altar.refreshJoins(level, request.room());
            case RECALL_DRONE -> message = altar.recallDrone(player, request.room());
            case DRONE_UPGRADE -> {
                var upgrades = DroneUpgrade.values();
                if (request.face() >= upgrades.length) return;
                message = altar.installDroneUpgrade(player, request.room(), upgrades[request.face()]);
            }
            case TELEMETRY -> {
                var readings = new CompoundTag();
                readings.put("Telemetry", altar.telemetry(level));
                PacketDistributor.sendToPlayer(player, new Snapshot(pos, readings));
                return;
            }
            case RENOVATE -> {
                if (!level.mayInteract(player, pos)) return;
                message = altar.planRenovation(player, request.room(), DomePalette.Part.values()[request.face()], palette);
                if (message.isEmpty()) message = "Confira os blocos e o custo antes de iniciar a troca.";
            }
            case SKIP_GALLERY -> message = altar.skipGallery(player);
            case REPAIR, PIN, EDIT_ROOM, SAVE_PERSONAL, GUIDE, SET_RESERVE -> {
                if (!level.mayInteract(player, pos)) return;
                message = switch (request.action()) {
                    case REPAIR -> altar.planRepair(player, request.room());
                    case PIN -> altar.pinShopping(player);
                    case EDIT_ROOM -> altar.editRoom(level, request.room(), request.blueprint(), request.face());
                    case SAVE_PERSONAL -> altar.savePersonal(level, request.room());
                    case GUIDE -> altar.guideTo(level, request.room());
                    case SET_RESERVE -> altar.cycleReserve();
                    default -> throw new IllegalStateException();
                };
                if (request.action() == REPAIR && message.isEmpty()) message = "Confira os blocos que serão repostos.";
                close = request.action() == GUIDE && message.startsWith("Siga");
            }
            case INSTALL_CARD -> {
                int nextTier = altar.tier() + 1;
                if (nextTier > OrbitalArchitect.ALTARS.size()) {
                    message = "O altar já está no tier máximo.";
                } else {
                    message = altar.installUpgrade(player, altar.findUpgradeCard(player), nextTier);
                    if (message.isEmpty() && level.getBlockEntity(pos) instanceof OrbAltarBlockEntity upgraded) {
                        afterUpgrade(player, upgraded);
                        return;
                    }
                }
            }
            case REFRESH -> message = "";
            default -> { return; }
        }
        send(player, altar, message, !close);
    }

    private static void sendReview(ServerPlayer player, OrbAltarBlockEntity altar, String message, CompoundTag inspection) {
        if (!message.isEmpty() && inspection.contains("Ghost")) {
            var data = snapshot(player, altar, message, true);
            data.merge(inspection);
            data.putString("State", "INSPECT");
            PacketDistributor.sendToPlayer(player, new Snapshot(altar.getBlockPos(), data));
        } else {
            send(player, altar, message.isEmpty() ? "Prévia pronta. Confira o encaixe antes de confirmar." : message, true);
        }
    }

    static void afterUpgrade(ServerPlayer player, OrbAltarBlockEntity altar) {
        String message = "Cartão instalado · Tier " + altar.tier() + ".";
        if (altar.hasHubUpgrade()) {
            var result = altar.planHubUpgrade(player, new CompoundTag());
            message = result.isEmpty() ? message + " Revise a reforma do núcleo." : message + " " + result;
        }
        send(player, altar, message, true);
    }

    private static void send(ServerPlayer player, OrbAltarBlockEntity altar, String message, boolean open) {
        PacketDistributor.sendToPlayer(player, new Snapshot(altar.getBlockPos(),
                snapshot(player, altar, message, open)));
    }
    private static CompoundTag snapshot(ServerPlayer player, OrbAltarBlockEntity altar, String message, boolean open) {
        var data = altar.plannerSnapshot(player.serverLevel(), message, open);
        data.putBoolean("Creative", player.isCreative());
        data.putBoolean("CanConfirm", altar.isPlanner(player));
        if (open && altar.isIdle()) altar.travelInfo(player, data);
        if (altar.isIdle() && altar.tier() < OrbitalArchitect.ALTARS.size()) {
            data.putInt("NextTier", altar.tier() + 1);
            data.putBoolean("HasCard", !altar.findUpgradeCard(player).isEmpty());
        }
        return data;
    }
}



