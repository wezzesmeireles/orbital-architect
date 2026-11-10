package dev.orbitalarchitect;

import com.google.gson.JsonParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

import java.io.IOException;
import java.util.*;

record RoomBlueprint(String title, int tier, Vec3i size, Map<BlockPos, BlockState> shell,
                     Map<Direction, BlockPos> ports, boolean gallery, Map<BlockPos, BlockState> airlocks,
                     boolean shapedClearance, Set<BlockPos> interior, int roofFrom) {
    private static final int MAX_VOLUME = 110_000;
    private static final Set<String> MATERIALS = Set.of("cobblestone", "oak_planks", "stone_bricks",
            "glass", "obsidian", "sea_lantern", "smooth_stone", "white_concrete", "yellow_concrete",
            "green_concrete", "cyan_concrete", "polished_deepslate", "tinted_glass", "dirt");

    private static final Set<String> PANELS = Set.of("deck_plate", "hull_panel", "hazard_plate", "orbital_light", "strip_light", "dock_light",
            "ribbed_wall", "ceramic_bulkhead", "technical_floor", "reinforced_shell", "observation_glass",
            "hub_trim_1", "hub_trim_2", "hub_trim_3", "hub_trim_4", "technical_stairs", "lift_station");

    static List<ResourceLocation> available(ServerLevel level) {
        String prefix = "orbital_rooms/";
        return level.getServer().getResourceManager().listResources("orbital_rooms",
                        id -> id.getPath().endsWith(".json")).keySet().stream()
                .map(id -> id.withPath(id.getPath().substring(prefix.length(), id.getPath().length() - 5)))
                .sorted().toList();
    }

    static CompoundTag describe(ServerLevel level, ResourceLocation id) throws IOException {
        var config = id.withPath("orbital_rooms/" + id.getPath() + ".json");
        try (var reader = level.getServer().getResourceManager().openAsReader(config)) {
            var json = JsonParser.parseReader(reader).getAsJsonObject();
            var data = new CompoundTag();
            data.putString("Id", id.toString());
            data.putString("Name", json.has("title") ? json.get("title").getAsString() : id.getPath());
            data.putInt("Tier", json.get("tier").getAsInt());
            int doors = 0;
            for (var direction : Direction.values()) {
                if (json.getAsJsonObject("ports").has(direction.getSerializedName())) doors |= 1 << direction.ordinal();
            }
            data.putInt("Doors", doors);
            if (json.has("airlocks")) {
                int width = json.getAsJsonObject("airlocks").entrySet().stream()
                        .mapToInt(entry -> entry.getValue().getAsInt()).max().orElse(0);
                data.putInt("Airlock", width);
            }
            if (json.has("size")) {
                var size = json.getAsJsonArray("size");
                data.putString("Size", size.get(0).getAsInt() + "×" + size.get(1).getAsInt()
                        + "×" + size.get(2).getAsInt());
            }
            return data;
        }
    }

    static RoomBlueprint load(ServerLevel level, ResourceLocation id) throws IOException {
        return load(level, id, "orbital_rooms");
    }

    static RoomBlueprint loadLegacy(ServerLevel level, ResourceLocation id) throws IOException {
        return load(level, id, "orbital_rooms_legacy");
    }

    private static RoomBlueprint load(ServerLevel level, ResourceLocation id, String directory) throws IOException {
        var config = id.withPath(directory + "/" + id.getPath() + ".json");
        try (var reader = level.getServer().getResourceManager().openAsReader(config)) {
            var json = JsonParser.parseReader(reader).getAsJsonObject();
            var templateId = ResourceLocation.parse(json.get("template").getAsString());
            StructureTemplate template = level.getStructureManager().get(templateId)
                    .orElseThrow(() -> new IllegalArgumentException("Estrutura ausente: " + templateId));
            var size = template.getSize();
            if (size.getX() < 1 || size.getY() < 1 || size.getZ() < 1
                    || (long) size.getX() * size.getY() * size.getZ() > MAX_VOLUME) {
                throw new IllegalArgumentException("Volume inválido ou acima de " + MAX_VOLUME + " posições");
            }
            CompoundTag data = template.save(new CompoundTag());
            if (data.contains("palettes") || !data.getList("entities", Tag.TAG_COMPOUND).isEmpty()) {
                throw new IllegalArgumentException("A estrutura deve ter uma paleta e nenhuma entidade");
            }
            var palette = data.getList("palette", Tag.TAG_COMPOUND);
            var shell = new LinkedHashMap<BlockPos, BlockState>();
            boolean shapedClearance = json.has("shapedClearance") && json.get("shapedClearance").getAsBoolean();
            var interior = new LinkedHashSet<BlockPos>();
            for (Tag entry : data.getList("blocks", Tag.TAG_COMPOUND)) {
                var block = (CompoundTag) entry;
                BlockState state = NbtUtils.readBlockState(BuiltInRegistries.BLOCK.asLookup(),
                        palette.getCompound(block.getInt("state")));
                if (state.is(Blocks.STRUCTURE_VOID)) continue;
                var pos = block.getList("pos", Tag.TAG_INT);
                var local = new BlockPos(pos.getInt(0), pos.getInt(1), pos.getInt(2));
                if (!inside(local, size)) throw new IllegalArgumentException("Bloco fora da estrutura");
                if (state.isAir()) {
                    if (shapedClearance) interior.add(local);
                    continue;
                }
                var key = BuiltInRegistries.BLOCK.getKey(state.getBlock());
                boolean structural = key.getNamespace().equals("minecraft") && MATERIALS.contains(key.getPath())
                        || key.getNamespace().equals(OrbitalArchitect.ID) && PANELS.contains(key.getPath());
                if (block.contains("nbt") || state.hasBlockEntity() || !structural) {
                    throw new IllegalArgumentException("Material estrutural proibido: " + key);
                }
                shell.put(local, state);
            }
            var ports = new EnumMap<Direction, BlockPos>(Direction.class);
            for (var entry : json.getAsJsonObject("ports").entrySet()) {
                var direction = Direction.byName(entry.getKey());
                var pos = entry.getValue().getAsJsonArray();
                if (direction == null || pos.size() != 3) throw new IllegalArgumentException("Porta inválida");
                var marker = new BlockPos(pos.get(0).getAsInt(), pos.get(1).getAsInt(), pos.get(2).getAsInt());
                if (!inside(marker, size) || inside(marker.relative(direction), size)
                        || shell.containsKey(marker)) throw new IllegalArgumentException("Porta fora de uma abertura");
                ports.put(direction, marker);
            }
            int tier = json.get("tier").getAsInt();
            if (shell.isEmpty() || shell.size() > MAX_VOLUME || tier < 1 || tier > 4) {
                throw new IllegalArgumentException("Projeto vazio, grande demais ou com tier inválido");
            }
            String title = json.has("title") ? json.get("title").getAsString() : id.getPath();
            boolean gallery = json.has("gallery") && json.get("gallery").getAsBoolean();
            var airlocks = new LinkedHashMap<BlockPos, BlockState>();
            if (json.has("airlocks")) {
                var used = new HashSet<BlockPos>();
                for (var entry : json.getAsJsonObject("airlocks").entrySet()) {
                    var direction = Direction.byName(entry.getKey());
                    int width = entry.getValue().getAsInt();
                    if (direction == null || !direction.getAxis().isHorizontal()
                            || !ports.containsKey(direction) || (width != 2 && width != 4)) {
                        throw new IllegalArgumentException("Porta de descompressão inválida");
                    }
                    var marker = ports.get(direction);
                    var door = OrbitalArchitect.AIRLOCKS.get(width == 4 ? 1 : 0).get();
                    var state = door.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_AXIS, direction.getAxis());
                    for (var panel : door.panels(marker, state).keySet()) {
                        if (!inside(panel, size) || shell.containsKey(panel) || !used.add(panel)) {
                            throw new IllegalArgumentException("Porta de descompressão sem vão livre");
                        }
                    }
                    airlocks.put(marker, state);
                }
            }
            return new RoomBlueprint(title, tier, size, Collections.unmodifiableMap(shell), Map.copyOf(ports),
                    gallery, Collections.unmodifiableMap(airlocks), shapedClearance, Set.copyOf(interior),
                    json.has("roofFrom") ? Math.clamp(json.get("roofFrom").getAsInt(), 0, size.getY() - 1) : size.getY() - 1);
        }
    }

    Iterable<BlockPos> clearance() {
        if (shapedClearance) return interior;
        return BlockPos.betweenClosed(BlockPos.ZERO, new BlockPos(size.getX() - 1, size.getY() - 1, size.getZ() - 1));
    }

    private static boolean inside(BlockPos pos, Vec3i size) {
        return pos.getX() >= 0 && pos.getX() < size.getX()
                && pos.getY() >= 0 && pos.getY() < size.getY()
                && pos.getZ() >= 0 && pos.getZ() < size.getZ();
    }
}











