package dev.orbitalarchitect;

import net.minecraft.client.Minecraft;
import net.minecraft.client.KeyMapping;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.minecraft.nbt.CompoundTag;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@EventBusSubscriber(modid = OrbitalArchitect.ID, value = Dist.CLIENT)
final class PlannerClient {
    private static final KeyMapping TRAVEL = new KeyMapping("key.orbital_architect.travel",
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_U, "key.categories.orbital_architect");

    @SubscribeEvent
    static void register(RegisterKeyMappingsEvent event) { event.register(TRAVEL); }

    @SubscribeEvent
    static void tick(ClientTickEvent.Post event) {
        var client = Minecraft.getInstance();
        if (TRAVEL.consumeClick() && client.player != null && client.screen == null) {
            PacketDistributor.sendToServer(new PlannerNetwork.Request(BlockPos.ZERO, PlannerNetwork.OPEN_TRAVEL,
                    "", 0, 0, false, false, new CompoundTag()));
        }
    }
    private record Ghost(ClientLevel level, BlockPos altar, AABB bounds, List<AABB> blocks,
                         List<AABB> ports, AABB dock, AABB gallery, List<SurveyMark> survey) {}
    private record SurveyMark(AABB bounds, float red, float green, float blue) {}
    private record Problem(ClientLevel level, BlockPos altar, BlockPos pos, BlockState initial, long expires) {}

    private static Ghost ghost;
    private static Problem problem;

    static void receive(PlannerNetwork.Snapshot snapshot) {
        var minecraft = Minecraft.getInstance();
        var data = snapshot.data();
        if (data == null || minecraft.level == null) return;
        if (data.contains("Terminal")) {
            var pos = BlockPos.of(data.getLong("Terminal"));
            if (minecraft.screen instanceof TerminalScreen terminal && terminal.position().equals(pos)) {
                terminal.update(data.getCompound("Reading"));
            } else if (data.getBoolean("Open")) {
                minecraft.setScreen(new TerminalScreen(snapshot.altar(), pos, data.getCompound("Reading")));
            }
            return;
        }
        if (data.contains("Telemetry")) {
            if (minecraft.screen instanceof PlannerScreen screen && screen.altar().equals(snapshot.altar())) {
                screen.updateTelemetry(data.getCompound("Telemetry"));
            }
            return;
        }
        if (data.contains("RoomNavigation")) {
            if (minecraft.screen instanceof PlannerScreen screen && screen.altar().equals(snapshot.altar())) {
                screen.updateRoomNavigation(data.getCompound("RoomNavigation"));
            }
            return;
        }
        var level = minecraft.level;
        if (data.contains("Blocked")) {
            var pos = BlockPos.of(data.getLong("Blocked"));
            var state = level.hasChunkAt(pos) ? level.getBlockState(pos) : null;
            problem = new Problem(level, snapshot.altar(), pos, state, level.getGameTime() + 600);
        } else if (problem != null && problem.altar().equals(snapshot.altar())) {
            problem = null;
        }
        if (data.contains("Min") && (data.getString("State").equals("PREVIEW") || data.getString("State").equals("INSPECT"))) {
            var min = BlockPos.of(data.getLong("Min"));
            var max = BlockPos.of(data.getLong("Max")).offset(1, 1, 1);
            var ports = new ArrayList<AABB>();
            var markers = data.getCompound("Ports");
            for (var dir : Direction.values()) {
                var key = dir.getSerializedName();
                if (markers.contains(key)) ports.add(port(BlockPos.of(markers.getLong(key)), dir));
            }
            var dock = data.contains("Dock") ? new AABB(BlockPos.of(data.getLong("Dock"))).inflate(.25) : null;
            var boxes = new ArrayList<AABB>();
            long[] positions = data.getLongArray("Ghost");
            for (int i = 1; i < positions.length; i += 2) {
                boxes.add(bounds(BlockPos.of(positions[i - 1]), BlockPos.of(positions[i])));
            }
            long[] link = data.getLongArray("GalleryLink");
            var gallery = link.length == 2 ? new AABB(BlockPos.of(link[0])).minmax(new AABB(BlockPos.of(link[1]))).inflate(.05) : null;
            var survey = new ArrayList<SurveyMark>();
            addMarks(survey, data.getLongArray("SiteCut"), 1f, .73f, .4f);
            addMarks(survey, data.getLongArray("SiteProtected"), 1f, .25f, .25f);
            addMarks(survey, data.getLongArray("SiteSupports"), .25f, .9f, .98f);
            addMarks(survey, data.getLongArray("SiteRepair"), .5f, 1f, .55f);
            ghost = new Ghost(level, snapshot.altar(), bounds(min, max), boxes, ports, dock, gallery, survey);
        } else if (ghost != null && ghost.altar().equals(snapshot.altar())) {
            ghost = null;
        }
        if (data.getBoolean("Open")) {
            if (minecraft.screen instanceof PlannerScreen screen && screen.altar().equals(snapshot.altar())
                    && screen.travelOnly() == data.getBoolean("TravelOnly")) {
                screen.update(data);
            } else {
                minecraft.setScreen(new PlannerScreen(snapshot.altar(), data));
            }
        } else {
            minecraft.setScreen(null);
            if (minecraft.player != null) {
                String message = data.getString("Message");
                if (message.isEmpty()) message = "Prévia 3D · amarelo = encaixe · reabra o altar para confirmar.";
                minecraft.player.displayClientMessage(Component.literal(message), true);
            }
        }
    }

    private static AABB bounds(BlockPos min, BlockPos max) {
        return new AABB(min.getX(), min.getY(), min.getZ(), max.getX(), max.getY(), max.getZ());
    }

    private static void addMarks(List<SurveyMark> marks, long[] positions, float red, float green, float blue) {
        for (long pos : positions) marks.add(new SurveyMark(new AABB(BlockPos.of(pos)).inflate(.025), red, green, blue));
    }

    private static AABB port(BlockPos pos, Direction direction) {
        var box = new AABB(pos);
        return direction.getAxis().isHorizontal() ? box.expandTowards(0, 1, 0).inflate(.08) : box.inflate(.08);
    }

    @SubscribeEvent
    public static void render(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) return;
        var minecraft = Minecraft.getInstance();
        renderProblem(minecraft, event);
        if (ghost == null) return;
        if (minecraft.level != ghost.level()) {
            ghost = null;
            return;
        }
        if (minecraft.level.hasChunkAt(ghost.altar())
                && !(minecraft.level.getBlockState(ghost.altar()).getBlock() instanceof OrbAltarBlock)) {
            ghost = null;
            return;
        }
        var camera = event.getCamera().getPosition();
        if (ghost.bounds().getCenter().distanceToSqr(camera) > 128 * 128) return;
        var buffers = minecraft.renderBuffers().bufferSource();
        var filled = RenderType.debugFilledBox();
        var fill = buffers.getBuffer(filled);
        var pose = event.getPoseStack();
        var offset = camera.scale(-1);
        for (var block : ghost.blocks()) {
            var box = block.move(offset).deflate(.015);
            LevelRenderer.addChainedFilledBoxVertices(pose, fill, box.minX, box.minY, box.minZ,
                    box.maxX, box.maxY, box.maxZ, .12f, .74f, .92f, .21f);
        }
        buffers.endBatch(filled);
        var lines = RenderType.lines();
        var line = buffers.getBuffer(lines);
        LevelRenderer.renderLineBox(pose, line, ghost.bounds().move(offset), .16f, .83f, .98f, .9f);
        for (var port : ghost.ports()) {
            LevelRenderer.renderLineBox(pose, line, port.move(offset), .25f, 1f, .48f, 1f);
        }
        if (ghost.dock() != null) {
            LevelRenderer.renderLineBox(pose, line, ghost.dock().move(offset), 1f, .74f, .16f, 1f);
        }
        if (ghost.gallery() != null) {
            LevelRenderer.renderLineBox(pose, line, ghost.gallery().move(offset), .76f, .36f, 1f, 1f);
        }
        for (var mark : ghost.survey()) {
            LevelRenderer.renderLineBox(pose, line, mark.bounds().move(offset), mark.red(), mark.green(), mark.blue(), .88f);
        }
        buffers.endBatch(lines);
    }

    private static void renderProblem(Minecraft minecraft, RenderLevelStageEvent event) {
        if (problem == null) return;
        var level = minecraft.level;
        if (level != problem.level() || level.getGameTime() > problem.expires()
                || problem.initial() != null && level.hasChunkAt(problem.pos())
                && !level.getBlockState(problem.pos()).equals(problem.initial())) {
            problem = null;
            return;
        }
        var camera = event.getCamera().getPosition();
        if (problem.pos().distToCenterSqr(camera) > 128 * 128) return;
        var buffers = minecraft.renderBuffers().bufferSource();
        var lines = RenderType.lines();
        var box = new AABB(problem.pos()).inflate(.035).move(camera.scale(-1));
        LevelRenderer.renderLineBox(event.getPoseStack(), buffers.getBuffer(lines), box, 1f, .19f, .16f, 1f);
        buffers.endBatch(lines);
    }
}



