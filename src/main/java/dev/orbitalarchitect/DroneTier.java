package dev.orbitalarchitect;

import net.minecraft.util.FastColor;
import org.joml.Vector3f;

enum DroneTier {
    COPPER(1, .28, 72, 1, 3, 400, 12, 18, 0xFFAA59),
    ALLOY(2, .47, 48, 3, 6, 760, 16, 23, 0x6FE6FF),
    QUANTUM(3, .73, 32, 6, 11, 1280, 20, 28, 0xC88AFF),
    PROTOTYPE(4, .95, 24, 9, 16, 2000, 24, 34, 0xFF7C6E);

    final int level;
    final double shotSpeed;
    final int interval;
    final int blocks;
    final int creativeBlocks;
    final int battery;
    final int buildCost;
    final int digCost;
    final int rgb;
    final Vector3f color;

    DroneTier(int level, double shotSpeed, int interval, int blocks, int creativeBlocks, int battery,
              int buildCost, int digCost, int rgb) {
        this.level = level;
        this.shotSpeed = shotSpeed;
        this.interval = interval;
        this.blocks = blocks;
        this.creativeBlocks = creativeBlocks;
        this.battery = battery;
        this.buildCost = buildCost;
        this.digCost = digCost;
        this.rgb = rgb;
        color = new Vector3f(FastColor.ARGB32.red(rgb) / 255f, FastColor.ARGB32.green(rgb) / 255f,
                FastColor.ARGB32.blue(rgb) / 255f);
    }

    static DroneTier at(int level) {
        return switch (level) { case 2 -> ALLOY; case 3 -> QUANTUM; case 4 -> PROTOTYPE; default -> COPPER; };
    }
}
