package dev.orbitalarchitect;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

record DomePalette(Map<Part, BlockState> choices) {
    enum Part {
        WALL("Paredes", "Revestimento interno e externo"),
        FLOOR("Piso", "Piso principal e base da galeria"),
        ROOF("Cúpula", "Cobertura acima das paredes"),
        GLASS("Vidros", "Janelas e claraboias"),
        TRIM("Molduras", "Acabamentos em volta da estrutura"),
        DETAIL("Detalhes", "Runas, marcações e tampas da galeria"),
        STAIRS("Escadas", "Acesso à galeria de cabos"),
        LIGHT("Luz principal", "Luminárias do piso e da cobertura"),
        SIGNAL("Luz da porta", "Luminárias junto às entradas"),
        DOOR("Portas", "Revestimento das folhas deslizantes"),
        PLANTER("Canteiros", "Solo dos canteiros");

        final String label;
        final String description;

        Part(String label, String description) {
            this.label = label;
            this.description = description;
        }
    }

    static final DomePalette EMPTY = new DomePalette(Map.of());
    private static final Set<String> TRIMS = Set.of("hull_panel", "deck_plate", "ribbed_wall");
    private static final Set<String> FIXTURES = Set.of("orbital_light", "strip_light", "dock_light");
    private static final Set<String> MARKINGS = Set.of("cyan_concrete", "green_concrete", "yellow_concrete");

    DomePalette {
        choices = Map.copyOf(choices);
    }

    static DomePalette standard() {
        return new DomePalette(Map.of(
                Part.WALL, Blocks.POLISHED_DEEPSLATE.defaultBlockState(),
                Part.FLOOR, Blocks.POLISHED_ANDESITE.defaultBlockState(),
                Part.ROOF, Blocks.DEEPSLATE_TILES.defaultBlockState(),
                Part.GLASS, Blocks.GRAY_STAINED_GLASS.defaultBlockState(),
                Part.TRIM, Blocks.SMOOTH_BASALT.defaultBlockState(),
                Part.DETAIL, Blocks.CHISELED_DEEPSLATE.defaultBlockState(),
                Part.STAIRS, Blocks.POLISHED_DEEPSLATE_STAIRS.defaultBlockState(),
                Part.LIGHT, BuiltInRegistries.BLOCK.get(OrbitalArchitect.id("orbital_light")).defaultBlockState(),
                Part.PLANTER, Blocks.DIRT.defaultBlockState()
        ));
    }

    static boolean isOrbitalLight(ResourceLocation id) {
        return id.getNamespace().equals(OrbitalArchitect.ID) && FIXTURES.contains(id.getPath());
    }

    private static boolean isOffered(Part part, Block block) {
        var id = BuiltInRegistries.BLOCK.getKey(block);
        return !id.getNamespace().equals(OrbitalArchitect.ID)
                || isOrbitalLight(id) && (part == Part.LIGHT || part == Part.SIGNAL);
    }

    static boolean isSelectable(Part part, Block block) {
        return isOffered(part, block) && isUsable(part, block);
    }

    private static boolean isUsable(Part part, Block block) {
        if (!(block.asItem() instanceof BlockItem item) || item.getBlock() != block || block instanceof FallingBlock) {
            return false;
        }
        var state = block.defaultBlockState();
        try {
            if (state.isAir() || state.hasBlockEntity() || !state.getFluidState().isEmpty()
                    || state.getRenderShape() != RenderShape.MODEL) return false;
            if (part == Part.STAIRS) {
                return block instanceof StairBlock && state.hasProperty(StairBlock.FACING)
                        && state.hasProperty(StairBlock.HALF) && state.hasProperty(StairBlock.SHAPE)
                        && state.hasProperty(BlockStateProperties.WATERLOGGED);
            }
            if (!state.isCollisionShapeFullBlock(EmptyBlockGetter.INSTANCE, BlockPos.ZERO)) return false;
            return switch (part) {
                case LIGHT, SIGNAL -> state.getLightEmission(EmptyBlockGetter.INSTANCE, BlockPos.ZERO) > 0;
                case DOOR -> state.isSolidRender(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
                default -> true;
            };
        } catch (RuntimeException unavailableWithoutWorld) {
            return false;
        }
    }

    static DomePalette read(CompoundTag tag, boolean strict) {
        var blocks = new EnumMap<Part, BlockState>(Part.class);
        for (var part : Part.values()) {
            var name = tag.getString(part.name());
            if (name.isEmpty()) continue;
            var id = ResourceLocation.tryParse(name);
            var block = id == null ? null : BuiltInRegistries.BLOCK.getOptional(id).orElse(null);
            if (block != null && (strict ? isSelectable(part, block) : isUsable(part, block))) {
                blocks.put(part, block.defaultBlockState());
            } else if (strict) {
                throw new IllegalArgumentException("Material estrutural indisponível: " + name);
            }
        }
        return new DomePalette(blocks);
    }

    DomePalette forNewConstruction() {
        var blocks = new EnumMap<Part, BlockState>(Part.class);
        blocks.putAll(standard().choices);
        choices.forEach((part, state) -> {
            if (isOffered(part, state.getBlock())) blocks.put(part, state);
        });
        return new DomePalette(blocks);
    }

    CompoundTag write() {
        var tag = new CompoundTag();
        choices.forEach((part, state) -> tag.putString(part.name(),
                BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString()));
        return tag;
    }

    BlockState material(Part part, BlockState original) {
        var replacement = choices.get(part);
        if (replacement == null) return original;
        if (part == Part.STAIRS) {
            return replacement.setValue(StairBlock.FACING, original.getValue(StairBlock.FACING))
                    .setValue(StairBlock.HALF, original.getValue(StairBlock.HALF))
                    .setValue(StairBlock.SHAPE, original.getValue(StairBlock.SHAPE))
                    .setValue(BlockStateProperties.WATERLOGGED, original.getValue(BlockStateProperties.WATERLOGGED));
        }
        return replacement;
    }

    Map<BlockPos, BlockState> apply(RoomBlueprint blueprint) {
        if (choices.isEmpty()) return blueprint.shell();
        var result = new LinkedHashMap<BlockPos, BlockState>();
        blueprint.shell().forEach((pos, state) -> {
            var part = partAt(blueprint, pos, state);
            result.put(pos, part == null ? state : material(part, state));
        });
        return result;
    }

    static Part partAt(RoomBlueprint blueprint, BlockPos pos, BlockState state) {
        if (state.getBlock() instanceof OrbitLiftBlock) return null;
        var id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        String name = id.getPath();
        boolean orbital = id.getNamespace().equals(OrbitalArchitect.ID);
        int floor = blueprint.ports().get(Direction.NORTH).getY() - 1;
        if (state.getBlock() instanceof StairBlock) return Part.STAIRS;
        if (orbital && FIXTURES.contains(name)) {
            return pos.getY() == floor + 2 || name.equals("dock_light") ? Part.SIGNAL : Part.LIGHT;
        }
        if (orbital && (name.equals("hazard_plate") || name.startsWith("hub_trim_"))
                || id.getNamespace().equals("minecraft") && MARKINGS.contains(name)) return Part.DETAIL;
        if (state.is(Blocks.DIRT)) return Part.PLANTER;
        if (state.is(Blocks.TINTED_GLASS) || orbital && name.equals("observation_glass")) return Part.GLASS;
        if (pos.getY() == 0 || pos.getY() == floor) return Part.FLOOR;
        if (pos.getY() >= blueprint.roofFrom()) return Part.ROOF;
        return orbital && TRIMS.contains(name) ? Part.TRIM : Part.WALL;
    }
}
