package dev.orbitalarchitect;

import net.minecraft.Util;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

final class OrbitUi {
    static final int SURFACE = 0xFF17212A;
    static final int WELL = 0xFF101B25;
    static final int BORDER = 0xFF344550;
    static final int TEXT = 0xEDF4F7;
    static final int MUTED = 0xABC0CE;
    static final int GOOD = 0x85E3BC;
    private static final int HEADER = 0xFF1B2935;
    private static final int ATLAS_SIZE = 256;

    private OrbitUi() {}

    static void panel(GuiGraphics g, int x, int y, int w, int h, int header, int accent) {
        g.fill(x - 3, y + 4, x + w + 4, y + h + 6, 0x5205080C);
        g.fill(x, y, x + w, y + h, SURFACE);
        g.fill(x + 1, y + 1, x + w - 1, y + header, HEADER);
        g.blit(OrbitalArchitect.id("textures/gui/orbit_chassis.png"), x + 1, y + 1, w - 2, header - 1,
                0f, 0f, ATLAS_SIZE, ATLAS_SIZE, ATLAS_SIZE, ATLAS_SIZE);
        g.fill(x + 1, y + 1, x + w - 1, y + header, 0x3D10202C);
        g.fill(x + 1, y + header, x + w - 1, y + header + 1, BORDER);
        g.renderOutline(x, y, w, h, BORDER);
        g.fill(x + 1, y + 1, x + 29, y + 2, opaque(accent));
        g.fill(x + w - 8, y + h - 2, x + w - 2, y + h - 1, opaque(accent));
    }

    static void well(GuiGraphics g, int x, int y, int w, int h, boolean grid) {
        g.fillGradient(x, y, x + w, y + h, 0xFF162530, WELL);
        if (grid) {
            for (int column = x + w / 2 % 12; column < x + w; column += 12) g.vLine(column, y, y + h - 1, 0x3846667A);
            for (int row = y + h / 2 % 12; row < y + h; row += 12) g.hLine(x, x + w - 1, row, 0x3846667A);
        }
        g.renderOutline(x, y, w, h, BORDER);
    }

    static void meter(GuiGraphics g, int x, int y, int w, float amount, int accent, boolean running) {
        g.fill(x, y, x + w, y + 5, 0xFF0C151C);
        int end = x + (int) (w * Math.clamp(amount, 0, 1));
        if (end == x) return;
        g.fillGradient(x, y, end, y + 4, opaque(accent), mix(opaque(accent), WELL, .36f));
        g.fill(x, y, end, y + 1, mix(opaque(accent), 0xFFFFFFFF, .20f));
        if (running) {
            int marker = (int) ((Util.getMillis() / 10) % (w + 14)) + x - 14;
            int from = Math.max(x, marker), to = Math.min(end, marker + 14);
            if (to > from) g.fill(from, y + 1, to, y + 3, 0x68FFFFFF);
        }
    }

    static int opaque(int color) { return 0xFF000000 | color; }

    private static int mix(int a, int b, float amount) {
        int r = (int) ((a >>> 16 & 255) * (1 - amount) + (b >>> 16 & 255) * amount);
        int g = (int) ((a >>> 8 & 255) * (1 - amount) + (b >>> 8 & 255) * amount);
        int blue = (int) ((a & 255) * (1 - amount) + (b & 255) * amount);
        return 0xFF000000 | r << 16 | g << 8 | blue;
    }

    enum Role { CONTROL, PRIMARY, ROW, TAB }
    enum Icon {
        PROJECT("01110", "10001", "11111", "10101", "11111"),
        MAP("11000", "11111", "01011", "01000", "11000"),
        DRONE("10001", "11111", "01110", "00100", "10001"),
        ROTATE("01110", "10001", "00001", "00111", "00010"),
        CUT("01111", "11010", "10110", "11100", "10000"),
        LIST("10111", "00000", "10111", "00000", "10111"),
        TARGET("00100", "01110", "11011", "01110", "00100");

        private final String[] pixels;
        Icon(String... pixels) { this.pixels = pixels; }

        void draw(GuiGraphics g, int x, int y, int color) {
            for (int row = 0; row < pixels.length; row++) for (int col = 0; col < 5; col++) {
                if (pixels[row].charAt(col) == '1') g.fill(x + col, y + row, x + col + 1, y + row + 1, color);
            }
        }
    }

    static final class Control extends Button {
        private final Font font;
        private final int accent;
        private final Role role;
        private final Icon icon;
        private final boolean chosen;
        private float hover;
        private long lastFrame = Util.getMillis();

        Control(Font font, int x, int y, int w, int h, String text, int accent, Role role, Icon icon,
                boolean chosen, Runnable press) {
            super(x, y, w, h, Component.literal(text), b -> press.run(), DEFAULT_NARRATION);
            this.font = font;
            this.accent = opaque(accent);
            this.role = role;
            this.icon = icon;
            this.chosen = chosen;
        }

        @Override protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float tick) {
            long now = Util.getMillis();
            float target = active && isHoveredOrFocused() ? 1 : 0;
            hover += (target - hover) * Math.clamp((now - lastFrame) / 85f, 0, 1);
            lastFrame = now;
            String text = getMessage().getString();
            boolean selected = chosen || text.startsWith("» ") || text.startsWith("• ") || text.startsWith("[x]");
            if (text.startsWith("» ") || text.startsWith("• ")) text = text.substring(2);
            int x = getX(), y = getY(), w = getWidth(), h = getHeight();
            boolean primary = active && role == Role.PRIMARY;
            if (role != Role.ROW || selected || hover > .02f) {
                int base = primary ? accent : selected ? mix(0xFF243844, accent, .18f) : active ? 0xFF23333F : 0xFF1A2630;
                int fill = mix(base, primary ? 0xFFFFFFFF : 0xFF435E70, hover * .36f);
                g.fill(x + 1, y, x + w - 1, y + h, fill);
                g.fill(x, y + 1, x + w, y + h - 1, fill);
                if (role != Role.ROW) {
                    g.hLine(x + 2, x + w - 3, y, mix(fill, 0xFFFFFFFF, primary ? .26f : .12f + hover * .09f));
                    g.hLine(x + 2, x + w - 3, y + h - 1, selected && role == Role.TAB ? accent : mix(fill, WELL, .55f));
                }
            }
            if (selected && role != Role.TAB) g.fill(x + 1, y + 2, x + 3, y + h - 2, accent);
            int available = Math.max(1, w - (role == Role.TAB ? 6 : 10) - (icon == null ? 0 : 9));
            if (font.width(text) > available) text = font.plainSubstrByWidth(text, Math.max(0, available - font.width("…"))) + "…";
            int left = role == Role.ROW ? x + 6 : x + (w - font.width(text) - (icon == null ? 0 : 9)) / 2;
            if (icon != null) {
                icon.draw(g, left, y + (h - 5) / 2, active ? primary ? WELL : accent : 0xFF617581);
                left += 9;
            }
            g.drawString(font, text, left, y + (h - 8) / 2, !active ? 0x798B97 : primary ? 0x101922 : TEXT, false);
        }
    }
}
