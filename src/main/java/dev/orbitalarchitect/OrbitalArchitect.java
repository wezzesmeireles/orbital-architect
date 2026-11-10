package dev.orbitalarchitect;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

@Mod(OrbitalArchitect.ID)
public final class OrbitalArchitect {
    public static final String ID = "orbital_architect";
    private static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(ID);
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(ID);
    private static final DeferredRegister<BlockEntityType<?>> ENTITIES =
            DeferredRegister.create(net.minecraft.core.registries.Registries.BLOCK_ENTITY_TYPE, ID);
    private static final DeferredRegister<SoundEvent> SOUNDS = DeferredRegister.create(Registries.SOUND_EVENT, ID);
    private static final DeferredRegister<AttachmentType<?>> ATTACHMENTS =
            DeferredRegister.create(NeoForgeRegistries.ATTACHMENT_TYPES, ID);
    static final java.util.function.Supplier<AttachmentType<CompoundTag>> ALTAR_RECOVERY =
            ATTACHMENTS.register("altar_recovery", () -> AttachmentType.builder(() -> new CompoundTag())
                    .serialize(CompoundTag.CODEC).build());
    private static final DeferredRegister<EntityType<?>> MOBILE = DeferredRegister.create(Registries.ENTITY_TYPE, ID);
    static final java.util.function.Supplier<EntityType<ConstructionDrone>> CONSTRUCTION_DRONE =
            MOBILE.register("construction_drone", () -> EntityType.Builder.of(ConstructionDrone::new, MobCategory.MISC)
                    .sized(.8f, .45f).clientTrackingRange(8).updateInterval(1).noSave()
                    .build(ID + ":construction_drone"));
    static final java.util.List<net.neoforged.neoforge.registries.DeferredItem<ConstructionDroneItem>> DRONES =
            java.util.Arrays.stream(DroneTier.values()).map(tier ->
                    ITEMS.register("construction_drone_" + tier.level,
                            () -> new ConstructionDroneItem(new Item.Properties().stacksTo(16), tier))).toList();
    static final java.util.List<net.neoforged.neoforge.registries.DeferredItem<Item>> DRONE_UPGRADES =
            java.util.Arrays.stream(DroneUpgrade.values())
                    .map(upgrade -> ITEMS.registerSimpleItem(upgrade.id, new Item.Properties())).toList();
    static final java.util.function.Supplier<SoundEvent> AIRLOCK_OPEN =
            SOUNDS.register("airlock_open", () -> SoundEvent.createVariableRangeEvent(id("airlock_open")));
    static final java.util.function.Supplier<SoundEvent> AIRLOCK_CLOSE =
            SOUNDS.register("airlock_close", () -> SoundEvent.createVariableRangeEvent(id("airlock_close")));
    static final java.util.function.Supplier<SoundEvent> DRONE_PICKUP =
            SOUNDS.register("drone_pickup", () -> SoundEvent.createFixedRangeEvent(id("drone_pickup"), 12));
    static final java.util.function.Supplier<SoundEvent> DRONE_PLACE =
            SOUNDS.register("drone_place", () -> SoundEvent.createFixedRangeEvent(id("drone_place"), 14));
    static final java.util.function.Supplier<SoundEvent> DRONE_ROTOR =
            SOUNDS.register("drone_rotor", () -> SoundEvent.createFixedRangeEvent(id("drone_rotor"), 12));

    static final java.util.List<net.neoforged.neoforge.registries.DeferredBlock<OrbAltarBlock>> ALTARS =
            java.util.stream.IntStream.rangeClosed(1, 4).mapToObj(tier -> {
                var altar = BLOCKS.register("orb_altar_" + tier,
                        () -> new OrbAltarBlock(BlockBehaviour.Properties.of().strength(2).noOcclusion().lightLevel(state -> 11), tier));
                ITEMS.registerSimpleBlockItem(altar, new Item.Properties());
                return altar;
            }).toList();

    static final net.neoforged.neoforge.registries.DeferredBlock<DroneDockBlock> DRONE_DOCK =
            BLOCKS.register("drone_dock", () -> new DroneDockBlock(BlockBehaviour.Properties.of().strength(3)
                    .sound(net.minecraft.world.level.block.SoundType.METAL).noOcclusion()
                    .pushReaction(net.minecraft.world.level.material.PushReaction.BLOCK)
                    .lightLevel(state -> state.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.LIT)
                            ? 6 : 0)));
    static final net.neoforged.neoforge.registries.DeferredBlock<ServicePortBlock> SERVICE_PORT =
            BLOCKS.register("service_port", () -> new ServicePortBlock(BlockBehaviour.Properties.of()
                    .strength(3).sound(net.minecraft.world.level.block.SoundType.METAL)));
    static final net.neoforged.neoforge.registries.DeferredBlock<OrbitLiftBlock> LIFT_STATION =
            BLOCKS.register("lift_station", () -> new OrbitLiftBlock(BlockBehaviour.Properties.of().strength(3)
                    .sound(net.minecraft.world.level.block.SoundType.METAL).lightLevel(state -> 6)));
    static {
        ITEMS.registerSimpleBlockItem(DRONE_DOCK, new Item.Properties());
        ITEMS.registerSimpleBlockItem(SERVICE_PORT, new Item.Properties());
        ITEMS.registerSimpleBlockItem(LIFT_STATION, new Item.Properties());
    }

    private static final java.util.List<net.neoforged.neoforge.registries.DeferredBlock<net.minecraft.world.level.block.Block>> PANELS =
            java.util.stream.Stream.of("deck_plate", "hull_panel", "hazard_plate", "orbital_light", "strip_light", "dock_light",
                    "ribbed_wall", "ceramic_bulkhead", "technical_floor", "reinforced_shell",
                    "hub_trim_1", "hub_trim_2", "hub_trim_3", "hub_trim_4").map(name -> {
                var properties = BlockBehaviour.Properties.of().strength(2)
                        .isValidSpawn((state, level, pos, entity) -> false);
                if (isHubTrim(name)) properties.lightLevel(state -> 12);
                if (name.equals("reinforced_shell")) properties.strength(50, 1200).requiresCorrectToolForDrops();
                var panel = BLOCKS.register(name, () -> {
                    if (name.endsWith("_light")) return new OrbitalLuminaire(properties);
                    if (name.equals("technical_floor") || name.equals("ceramic_bulkhead")) {
                        return new JoiningPanelBlock(properties, false, name.equals("technical_floor"));
                    }
                    return new net.minecraft.world.level.block.Block(properties);
                });
                ITEMS.registerSimpleBlockItem(panel, new Item.Properties());
                return panel;
            }).toList();

    static final java.util.List<net.neoforged.neoforge.registries.DeferredItem<UpgradeCardItem>> CARDS =
            java.util.stream.IntStream.rangeClosed(2, 4).mapToObj(tier ->
                    ITEMS.register("upgrade_card_" + tier, () -> new UpgradeCardItem(new Item.Properties(), tier)))
                    .toList();

    private static final net.neoforged.neoforge.registries.DeferredBlock<JoiningPanelBlock> OBSERVATION_GLASS =
            BLOCKS.register("observation_glass", () -> new JoiningPanelBlock(BlockBehaviour.Properties.ofFullCopy(Blocks.GLASS), true, false));

    static {
        ITEMS.registerSimpleBlockItem(OBSERVATION_GLASS, new Item.Properties());
    }

    static final net.neoforged.neoforge.registries.DeferredBlock<net.minecraft.world.level.block.StairBlock> TECHNICAL_STAIRS =
            BLOCKS.register("technical_stairs", () -> new net.minecraft.world.level.block.StairBlock(
                    PANELS.stream().filter(panel -> panel.getId().getPath().equals("technical_floor"))
                            .findFirst().orElseThrow().get().defaultBlockState(),
                    BlockBehaviour.Properties.of().strength(2).isValidSpawn((state, level, pos, entity) -> false)));

    static final java.util.List<net.neoforged.neoforge.registries.DeferredBlock<AirlockBlock>> AIRLOCKS =
            java.util.stream.IntStream.of(2, 4).mapToObj(size -> {
                var name = "airlock_" + size;
                var block = BLOCKS.register(name, () -> new AirlockBlock(BlockBehaviour.Properties.of()
                        .strength(4).sound(net.minecraft.world.level.block.SoundType.METAL).noOcclusion()
                        .pushReaction(net.minecraft.world.level.material.PushReaction.BLOCK), size));
                ITEMS.register(name, () -> new AirlockItem(block.get(), new Item.Properties()));
                return block;
            }).toList();

    static {
        ITEMS.registerSimpleBlockItem(TECHNICAL_STAIRS, new Item.Properties());
    }

    static final java.util.function.Supplier<BlockEntityType<OrbAltarBlockEntity>> ALTAR_ENTITY =
            ENTITIES.register("orb_altar", () -> BlockEntityType.Builder.of(OrbAltarBlockEntity::new,
                    ALTARS.stream().map(java.util.function.Supplier::get)
                            .toArray(net.minecraft.world.level.block.Block[]::new)).build(null));
    static final java.util.function.Supplier<BlockEntityType<AirlockBlockEntity>> AIRLOCK_ENTITY =
            ENTITIES.register("airlock", () -> BlockEntityType.Builder.of(AirlockBlockEntity::new,
                    AIRLOCKS.get(0).get(), AIRLOCKS.get(1).get()).build(null));
    static final java.util.function.Supplier<BlockEntityType<DroneDockBlockEntity>> DOCK_ENTITY =
            ENTITIES.register("drone_dock", () -> BlockEntityType.Builder.of(DroneDockBlockEntity::new,
                    DRONE_DOCK.get()).build(null));
    static final java.util.function.Supplier<BlockEntityType<ServicePortBlockEntity>> SERVICE_ENTITY =
            ENTITIES.register("service_port", () -> BlockEntityType.Builder.of(ServicePortBlockEntity::new,
                    SERVICE_PORT.get()).build(null));

    private static final DeferredRegister<CreativeModeTab> TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, ID);

    static {
        TABS.register("main", () -> CreativeModeTab.builder()
                .title(Component.literal("Orbital Architect"))
                .icon(() -> new ItemStack(ALTARS.getFirst().get()))
                .displayItems((parameters, output) -> {
                    ALTARS.forEach(altar -> output.accept(altar.get()));
                    CARDS.forEach(card -> output.accept(card.get()));
                    DRONES.forEach(drone -> output.accept(drone.get()));
                    DRONE_UPGRADES.forEach(upgrade -> output.accept(upgrade.get()));
                    output.accept(DRONE_DOCK.get());
                    output.accept(SERVICE_PORT.get());
                    output.accept(LIFT_STATION.get());
                    PANELS.stream().filter(panel -> DomePalette.isOrbitalLight(panel.getId()))
                            .forEach(panel -> output.accept(panel.get()));
                    output.accept(AIRLOCKS.getLast().get());
                })
                .build());
    }

    public OrbitalArchitect(IEventBus bus) {
        BLOCKS.register(bus);
        ITEMS.register(bus);
        TABS.register(bus);
        ENTITIES.register(bus);
        ATTACHMENTS.register(bus);
        SOUNDS.register(bus);
        MOBILE.register(bus);
        NeoForge.EVENT_BUS.addListener(OrbitalArchitect::refreshExistingLights);
        bus.addListener(this::registerCapabilities);
        bus.addListener(PlannerNetwork::register);
        if (net.neoforged.fml.ModList.get().isLoaded("ae2")) bus.addListener(Ae2Supply::register);
    }

    private static boolean isHubTrim(String name) {
        return name.startsWith("hub_trim_");
    }

    private static void refreshExistingLights(ChunkEvent.Load event) {
        if (event.isNewChunk() || !(event.getLevel() instanceof ServerLevel level)) return;
        var engine = level.getChunkSource().getLightEngine();
        // Queue light checks without loading neighboring chunks during the load event.
        event.getChunk().findBlocks(state -> {
            if (state.getBlock() instanceof OrbAltarBlock) return true;
            var key = BuiltInRegistries.BLOCK.getKey(state.getBlock());
            return key.getNamespace().equals(ID) && isHubTrim(key.getPath());
        }, (pos, state) -> engine.checkBlock(pos));
    }

    private void registerCapabilities(RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(Capabilities.EnergyStorage.BLOCK, ALTAR_ENTITY.get(),
                (altar, side) -> altar.energyInput());
        event.registerBlockEntity(Capabilities.EnergyStorage.BLOCK, DOCK_ENTITY.get(), (dock, side) -> {
            if (!(dock.getLevel() instanceof ServerLevel server)) return null;
            var altar = DroneDockBlock.owner(server, dock);
            return altar == null ? null : altar.energyInput();
        });
        event.registerBlockEntity(Capabilities.EnergyStorage.BLOCK, SERVICE_ENTITY.get(), ServicePortBlockEntity::energy);
        event.registerBlockEntity(Capabilities.ItemHandler.BLOCK, SERVICE_ENTITY.get(), ServicePortBlockEntity::automation);
    }

    static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(ID, path);
    }
}




































