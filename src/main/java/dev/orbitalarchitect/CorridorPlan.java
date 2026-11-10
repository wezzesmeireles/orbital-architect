package dev.orbitalarchitect;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;

record CorridorPlan(Map<BlockPos, BlockState> blocks, Set<BlockPos> clearance, Set<BlockPos> supports) {
    static final int LENGTH = 6;
    static final CorridorPlan EMPTY = new CorridorPlan(Map.of(), Set.of(), Set.of());

    enum Style {
        DIRECT("Direto"), GLASS("Passarela de vidro"), ARMORED("Túnel blindado"), SERVICE("Galeria técnica");

        final String label;

        Style(String label) { this.label = label; }

        Style next() { return values()[(ordinal() + 1) % values().length]; }
    }

    enum Priority {
        SHELL("Fechar o casco"), GALLERY("Liberar a galeria"), INTERIOR("Montar o interior");

        final String label;

        Priority(String label) { this.label = label; }

        Priority next() { return values()[(ordinal() + 1) % values().length]; }
    }

    record Options(Style style, int rise, Priority priority, boolean pillars) {
        static final Options DEFAULT = new Options(Style.DIRECT, 0, Priority.SHELL, false);

        int distance(Direction direction) {
            return style == Style.DIRECT || !direction.getAxis().isHorizontal() ? 1 : LENGTH + 1;
        }

        int grade(Direction direction) { return distance(direction) == 1 ? 0 : rise; }
    }

    static CorridorPlan between(ServerLevel level, BlockPos door, Direction direction, DomePalette palette, Options options) {
        if (options.distance(direction) == 1) return EMPTY;
        var blocks = new LinkedHashMap<BlockPos, BlockState>();
        var clearance = new LinkedHashSet<BlockPos>();
        var supports = new LinkedHashSet<BlockPos>();
        var transverse = direction.getAxis() == Direction.Axis.X ? Direction.SOUTH : Direction.EAST;
        var floor = palette.material(DomePalette.Part.FLOOR, Blocks.POLISHED_ANDESITE.defaultBlockState());
        var wall = palette.material(DomePalette.Part.WALL, Blocks.POLISHED_DEEPSLATE.defaultBlockState());
        var trim = palette.material(DomePalette.Part.TRIM, Blocks.SMOOTH_BASALT.defaultBlockState());
        var glass = palette.material(DomePalette.Part.GLASS, Blocks.GRAY_STAINED_GLASS.defaultBlockState());
        var lamp = palette.material(DomePalette.Part.LIGHT, Blocks.SEA_LANTERN.defaultBlockState());
        int rise = options.rise();
        int ramp = LENGTH / 2;
        for (int section = 1; section <= LENGTH; section++) {
            int y = section > ramp ? rise : 0;
            if (section == ramp && rise > 0) y = 1;
            boolean stair = section == ramp && rise != 0;
            var aisle = door.relative(direction, section).above(y);
            var step = palette.material(DomePalette.Part.STAIRS, Blocks.POLISHED_DEEPSLATE_STAIRS.defaultBlockState()
                    .setValue(StairBlock.FACING, rise > 0 ? direction : direction.getOpposite()));
            for (int col = -1; col <= 4; col++) {
                var pos = aisle.relative(transverse, col);
                boolean edge = col == -1 || col == 4;
                blocks.put(pos.below(), stair && !edge ? step : floor);
                blocks.put(pos.above(4), col == 1 && (section == 2 || section == 5) ? lamp : trim);
                for (int height = 0; height < 4; height++) {
                    var cell = pos.above(height);
                    if (!edge) clearance.add(cell);
                    else {
                        boolean window = options.style() == Style.GLASS && height >= 1 && height <= 2;
                        blocks.put(cell, window ? glass : height == 3 ? trim : wall);
                    }
                }
                int base = options.style() == Style.SERVICE ? 5 : 2;
                for (int depth = 2; depth <= base; depth++) {
                    var cell = pos.below(depth);
                    if (depth == base || edge) {
                        blocks.put(cell, depth == base && stair && !edge && base == 5 ? step : trim);
                    } else clearance.add(cell);
                }
                if (edge && (section == 2 || section == 5)) {
                    addSupport(level, pos.below(base), trim, options.pillars(), blocks, supports);
                }
            }
        }
        return new CorridorPlan(blocks, clearance, supports);
    }

    static void addSupport(ServerLevel level, BlockPos base, BlockState material, boolean build,
                           Map<BlockPos, BlockState> blocks, Set<BlockPos> suggestions) {
        var start = base.below();
        if (!level.hasChunkAt(start) || level.isOutsideBuildHeight(start) || !level.getBlockState(start).isAir()) return;
        suggestions.add(start);
        if (!build) return;
        var pos = start;
        while (start.getY() - pos.getY() < 12 && !level.isOutsideBuildHeight(pos) && level.getBlockState(pos).isAir()) {
            pos = pos.below();
        }
        if (!level.getBlockState(pos).isFaceSturdy(level, pos, Direction.UP)) return;
        for (var fill = pos.above(); fill.getY() <= start.getY(); fill = fill.above()) blocks.putIfAbsent(fill, material);
    }
}
