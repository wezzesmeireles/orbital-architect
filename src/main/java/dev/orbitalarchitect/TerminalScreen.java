package dev.orbitalarchitect;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

final class TerminalScreen extends Screen {
    private static final int WIDTH = 304, HEIGHT = 224, ROWS = 6;
    private static final int ACCENT = 0x74D9C3;
    private final BlockPos altar, terminal;
    private CompoundTag reading;
    private Button reserve;
    private int offset, ticks, left, top;
    private float displayedEnergy = -1;

    TerminalScreen(BlockPos altar, BlockPos terminal, CompoundTag reading) {
        super(Component.literal("Terminal de suprimentos"));
        this.altar = altar;
        this.terminal = terminal;
        this.reading = reading;
    }

    BlockPos position() { return terminal; }

    void update(CompoundTag snapshot) {
        reading = snapshot;
        offset = Math.min(offset, Math.max(0, reading.getList("Shopping", Tag.TAG_COMPOUND).size() - ROWS));
        if (reserve != null) reserve.setMessage(reserveLabel());
    }

    private Component reserveLabel() {
        return Component.literal("Reserva do núcleo · " + reading.getInt("EnergyReserve") + " FE");
    }

    @Override protected void init() {
        left = (width - WIDTH) / 2;
        top = (height - HEIGHT) / 2;
        reserve = addRenderableWidget(new OrbitUi.Control(font, left + 12, top + 192, 202, 20, reserveLabel().getString(),
                ACCENT, OrbitUi.Role.CONTROL, null, false, () -> send(PlannerNetwork.SET_RESERVE)));
        addRenderableWidget(new OrbitUi.Control(font, left + 222, top + 192, 70, 20, "Fechar",
                ACCENT, OrbitUi.Role.CONTROL, null, false, this::onClose));
    }

    private void send(int action) {
        PacketDistributor.sendToServer(new PlannerNetwork.Request(altar, action, Long.toString(terminal.asLong()),
                0, 0, false, false, new CompoundTag()));
    }

    @Override public void tick() {
        if (++ticks % 20 == 0) send(PlannerNetwork.TERMINAL_REFRESH);
    }

    @Override public boolean isPauseScreen() { return false; }

    @Override public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float tick) { }

    @Override public boolean mouseScrolled(double x, double y, double dx, double dy) {
        var rows = reading.getList("Shopping", Tag.TAG_COMPOUND);
        if (rows.size() <= ROWS || dy == 0) return super.mouseScrolled(x, y, dx, dy);
        offset = Math.clamp(offset - (int) Math.signum(dy), 0, rows.size() - ROWS);
        return true;
    }

    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float tick) {
        graphics.fill(0, 0, width, height, 0x97091017);
        OrbitUi.panel(graphics, left, top, WIDTH, HEIGHT, 46, ACCENT);
        line(graphics, getTitle().getString(), 12, 11, OrbitUi.TEXT);
        int energy = reading.getInt("Energy"), capacity = reading.getInt("EnergyCapacity");
        line(graphics, "Núcleo  " + energy + " / " + capacity + " FE", 12, 27, OrbitUi.MUTED);
        String live = "Drones  " + reading.getInt("Consumption") + " FE/s";
        line(graphics, live, 292 - font.width(live), 27, OrbitUi.MUTED);
        float energyRatio = (float) energy / Math.max(1, capacity);
        displayedEnergy = displayedEnergy < 0 ? energyRatio : displayedEnergy + (energyRatio - displayedEnergy) * .18f;
        OrbitUi.meter(graphics, left + 12, top + 38, 280, displayedEnergy, ACCENT, reading.getInt("InputConsumption") > 0);
        var rows = reading.getList("Shopping", Tag.TAG_COMPOUND);
        String project = reading.getString("ShoppingName");
        int complete = (int) rows.stream().map(CompoundTag.class::cast).filter(row -> row.getInt("Stock") >= row.getInt("Count")).count();
        String supplies = complete + "/" + rows.size() + " materiais";
        line(graphics, project.isEmpty() ? "Fixe um projeto na revisão do Orbit." : font.plainSubstrByWidth(project, 181),
                12, 54, OrbitUi.TEXT);
        if (!rows.isEmpty()) line(graphics, supplies, 292 - font.width(supplies), 54,
                complete == rows.size() ? OrbitUi.GOOD : OrbitUi.MUTED);
        for (int row = offset; row < Math.min(rows.size(), offset + ROWS); row++) {
            var entry = rows.getCompound(row);
            var item = new ItemStack(BuiltInRegistries.BLOCK.get(ResourceLocation.parse(entry.getString("Block"))));
            int y = 67 + (row - offset) * 19;
            graphics.fill(left + 12, top + y, left + 292, top + y + 17, row % 2 == 0 ? OrbitUi.WELL : 0xFF1B2A35);
            graphics.renderItem(item, left + 15, top + y);
            line(graphics, font.plainSubstrByWidth(item.getHoverName().getString(), 168), 35, y + 4, OrbitUi.TEXT);
            int stock = entry.getInt("Stock"), need = entry.getInt("Count");
            String count = stock + " / " + need;
            line(graphics, count, 286 - font.width(count), y + 4, stock < need ? 0xFFD38C : OrbitUi.GOOD);
        }
        super.render(graphics, mouseX, mouseY, tick);
    }

    private void line(GuiGraphics graphics, String value, int x, int y, int color) {
        graphics.drawString(font, value, left + x, top + y, color, false);
    }
}
