package dev.orbitalarchitect;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.model.data.ModelData;

@EventBusSubscriber(modid = OrbitalArchitect.ID, value = Dist.CLIENT)
public final class AirlockRenderer implements BlockEntityRenderer<AirlockBlockEntity> {
    static final net.minecraft.resources.ResourceLocation LEAF_SPRITE = OrbitalArchitect.id("block/airlock_leaves_v3");
    private static final RenderType PANEL =
            RenderType.entityCutoutNoCull(OrbitalArchitect.id("textures/block/airlock_leaves_v3.png"));
    private static final RenderType ALLOY =
            RenderType.entityCutoutNoCull(OrbitalArchitect.id("textures/block/white_steel.png"));
    private static final RenderType INDICATOR =
            RenderType.entityCutoutNoCull(net.minecraft.resources.ResourceLocation.withDefaultNamespace("textures/block/white_concrete.png"));
    private static final RenderType CLADDING = RenderType.entityCutoutNoCull(InventoryMenu.BLOCK_ATLAS);
    private record Surface(VertexConsumer vertices, TextureAtlasSprite sprite) {}
    private static final float FRONT = 7f / 16;
    private static final float BACK = 9f / 16;
    private static final float[] LATCH_HEIGHTS = {.25f, .61f, .79f};

    @SubscribeEvent
    public static void registerRenderer(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(OrbitalArchitect.AIRLOCK_ENTITY.get(), context -> new AirlockRenderer());
    }

    @Override
    public void render(AirlockBlockEntity door, float partialTick, PoseStack pose,
                       MultiBufferSource buffers, int light, int overlay) {
        var level = door.getLevel();
        if (level == null) return;
        var state = door.getBlockState();
        var block = (AirlockBlock) state.getBlock();
        boolean moving = state.getValue(AirlockBlock.MOVING);
        boolean open = state.getValue(BlockStateProperties.OPEN);
        float time = (level.getGameTime() - door.motionStart() + partialTick) / block.motionTicks();
        float phase = moving ? Mth.clamp(open ? time : 1 - time, 0, 1) : open ? 1 : 0;
        float released = ease(phase, .02f, .21f);
        int size = block.size();
        float travel = size * .5f * ease(phase, .17f, .88f);
        float front = FRONT - .006f + .036f * released;
        float back = BACK + .006f - .036f * released;
        int metalLight = LightTexture.pack(Math.max(8, LightTexture.block(light)), LightTexture.sky(light));
        boolean visible = moving || !open;
        boolean xAxis = state.getValue(BlockStateProperties.HORIZONTAL_AXIS) == Direction.Axis.X;
        pose.pushPose();
        if (xAxis) {
            pose.translate(1, 0, 0);
            pose.mulPose(Axis.YP.rotationDegrees(-90));
        }
        if (visible) {
            var sprite = door.cladding() == null ? null : claddingSprite(door.cladding());
            var leaves = new Surface(buffers.getBuffer(sprite == null ? PANEL : CLADDING), sprite);
            drawLeaves(pose.last(), leaves, size, travel, front, back, metalLight, overlay);
            var alloy = new Surface(buffers.getBuffer(ALLOY), null);
            drawLatches(pose.last(), alloy, size, travel, front, back, released, metalLight, overlay);
        }

        var indicators = new Surface(buffers.getBuffer(INDICATOR), null);
        int status = moving ? 0xFFB760 : open || level.getGameTime() < door.readyUntil() ? 0x79E8AF : 0x77DFF5;
        drawSignals(pose.last(), indicators, size, status, phase, moving, visible, travel, front, back, overlay);
        if (!door.sign().isEmpty()) {
            drawSign(door, pose, buffers, size, .322f, true);
            drawSign(door, pose, buffers, size, .678f, false);
        }
        pose.popPose();
    }

    private static void drawSign(AirlockBlockEntity door, PoseStack pose, MultiBufferSource buffers,
                                 int size, float depth, boolean front) {
        var font = Minecraft.getInstance().font;
        String sign = font.plainSubstrByWidth(door.sign(), 96);
        pose.pushPose();
        pose.translate(size * .5, size - .23, depth);
        if (front) pose.mulPose(Axis.YP.rotationDegrees(180));
        pose.scale(.015f, -.015f, .015f);
        font.drawInBatch(sign, -font.width(sign) / 2f, 0, 0xFF000000 | door.signColor(), false,
                pose.last().pose(), buffers, net.minecraft.client.gui.Font.DisplayMode.NORMAL,
                0xAA152029, LightTexture.FULL_BRIGHT);
        pose.popPose();
    }

    private static float ease(float progress, float from, float to) {
        float t = Mth.clamp((progress - from) / (to - from), 0, 1);
        return t * t * (3 - 2 * t);
    }

    private static void drawLeaves(PoseStack.Pose pose, Surface surface,
                                   int size, float travel, float front, float back, int light, int overlay) {
        boolean tiled = surface.sprite() != null;
        for (int column = 0; column < size; column++) {
            float panelLeft = column + (column < size / 2 ? -travel : travel);
            float left = Math.max(0, panelLeft);
            float right = Math.min(size, panelLeft + 1);
            if (left >= right) continue;
            float u0 = tiled ? left - panelLeft : (column + left - panelLeft) / size;
            float u1 = tiled ? right - panelLeft : (column + right - panelLeft) / size;
            for (int row = 0; row < size; row++) {
                float v0 = tiled ? 0 : 1 - (row + 1f) / size;
                float v1 = tiled ? 1 : 1 - (float) row / size;
                face(surface, pose, left, right, row, front, 1 - u0, 1 - u1, v0, v1, light, overlay, -1);
                face(surface, pose, left, right, row, back, u0, u1, v0, v1, light, overlay, 1);
                if (travel > .001f && column == size / 2 - 1) edge(surface, pose, right, row, front, back, light, overlay, 1);
                if (travel > .001f && column == size / 2) edge(surface, pose, left, row, front, back, light, overlay, -1);
                if (row == 0) cap(surface, pose, left, right, row, front, back, light, overlay, -1);
                if (row == size - 1) cap(surface, pose, left, right, row + 1, front, back, light, overlay, 1);
            }
        }
    }

    private static void drawLatches(PoseStack.Pose pose, Surface alloy, int size, float travel, float front,
                                    float back, float released, int light, int overlay) {
        float half = size / 2f;
        float shift = .08f + .15f * released;
        float depth = .012f + .027f * (1 - released);
        for (float y : LATCH_HEIGHTS) {
            float left = half - travel - shift;
            float right = half + travel + shift;
            float height = size * y;
            detail(alloy, pose, left - .073f, left + .035f, height, height + .075f, size,
                    front - depth, back + depth, 0xA8B8C0, light, overlay);
            detail(alloy, pose, right - .035f, right + .073f, height, height + .075f, size,
                    front - depth, back + depth, 0xA8B8C0, light, overlay);
            latchTop(alloy, pose, left - .073f, left + .035f, height + .075f, size, front, back, depth, light, overlay);
            latchTop(alloy, pose, right - .035f, right + .073f, height + .075f, size, front, back, depth, light, overlay);
        }
    }

    private static void latchTop(Surface alloy, PoseStack.Pose pose, float x0, float x1, float y, int size,
                                 float front, float back, float depth, int light, int overlay) {
        float left = Math.max(0, x0), right = Math.min(size, x1);
        if (left >= right) return;
        cap(alloy, pose, left, right, y, front - depth, front, light, overlay, 1);
        cap(alloy, pose, left, right, y, back, back + depth, light, overlay, 1);
    }

    private static void drawSignals(PoseStack.Pose pose, Surface lights, int size, int color, float phase,
                                    boolean moving, boolean visible, float travel, float front, float back, int overlay) {
        if (visible) {
            float low = size * .402f;
            float high = low + .035f;
            detail(lights, pose, size * .215f - travel, size * .442f - travel, low, high, size,
                    front - .012f, back + .012f, color, LightTexture.FULL_BRIGHT, overlay);
            detail(lights, pose, size * .558f + travel, size * .785f + travel, low, high, size,
                    front - .012f, back + .012f, color, LightTexture.FULL_BRIGHT, overlay);
        }
        int active = Math.min(2, (int) (phase * 3));
        for (int i = 0; i < 3; i++) {
            int signal = moving && i != active ? 0x584331 : color;
            float low = size * .44f + .09f * i;
            detail(lights, pose, .053f, .101f, low, low + .047f, size, FRONT - .091f, BACK + .091f,
                    signal, LightTexture.FULL_BRIGHT, overlay);
            detail(lights, pose, size - .101f, size - .053f, low, low + .047f, size, FRONT - .091f, BACK + .091f,
                    signal, LightTexture.FULL_BRIGHT, overlay);
        }
    }

    private static void detail(Surface surface, PoseStack.Pose pose, float x0, float x1, float y0, float y1,
                               int size, float front, float back, int rgb, int light, int overlay) {
        float left = Math.max(0, x0);
        float right = Math.min(size, x1);
        if (left >= right) return;
        float u0 = (left - x0) / (x1 - x0);
        float u1 = (right - x0) / (x1 - x0);
        detailFace(surface, pose, left, right, y0, y1, front, u0, u1, light, overlay, rgb, -1);
        detailFace(surface, pose, left, right, y0, y1, back, u0, u1, light, overlay, rgb, 1);
    }

    private static void detailFace(Surface surface, PoseStack.Pose pose, float left, float right, float y0, float y1,
                                   float z, float u0, float u1, int light, int overlay, int rgb, int side) {
        vertexColor(surface, pose, left, y0, z, u0, 1, light, overlay, rgb, 0, 0, side);
        vertexColor(surface, pose, left, y1, z, u0, 0, light, overlay, rgb, 0, 0, side);
        vertexColor(surface, pose, right, y1, z, u1, 0, light, overlay, rgb, 0, 0, side);
        vertexColor(surface, pose, right, y0, z, u1, 1, light, overlay, rgb, 0, 0, side);
    }

    static TextureAtlasSprite claddingSprite(BlockState state) {
        try {
            return Minecraft.getInstance().getBlockRenderer().getBlockModel(state).getParticleIcon(ModelData.EMPTY);
        } catch (RuntimeException requiresWorld) {
            return null;
        }
    }

    private static void face(Surface out, PoseStack.Pose pose, float left, float right, int row, float depth,
                             float u0, float u1, float v0, float v1, int light, int overlay, int normal) {
        vertex(out, pose, left, row, depth, u0, v1, light, overlay, 255, 0, 0, normal);
        vertex(out, pose, left, row + 1, depth, u0, v0, light, overlay, 255, 0, 0, normal);
        vertex(out, pose, right, row + 1, depth, u1, v0, light, overlay, 255, 0, 0, normal);
        vertex(out, pose, right, row, depth, u1, v1, light, overlay, 255, 0, 0, normal);
    }

    private static void edge(Surface out, PoseStack.Pose pose, float x, int row, float front, float back,
                             int light, int overlay, int normal) {
        vertex(out, pose, x, row, front, 0, 1, light, overlay, 156, normal, 0, 0);
        vertex(out, pose, x, row + 1, front, 0, 0, light, overlay, 156, normal, 0, 0);
        vertex(out, pose, x, row + 1, back, .035f, 0, light, overlay, 156, normal, 0, 0);
        vertex(out, pose, x, row, back, .035f, 1, light, overlay, 156, normal, 0, 0);
    }

    private static void cap(Surface out, PoseStack.Pose pose, float left, float right, float y, float front, float back,
                            int light, int overlay, int normal) {
        vertex(out, pose, left, y, front, 0, 0, light, overlay, 147, 0, normal, 0);
        vertex(out, pose, left, y, back, 0, .035f, light, overlay, 147, 0, normal, 0);
        vertex(out, pose, right, y, back, 1, .035f, light, overlay, 147, 0, normal, 0);
        vertex(out, pose, right, y, front, 1, 0, light, overlay, 147, 0, normal, 0);
    }

    private static void vertex(Surface out, PoseStack.Pose pose, float x, float y, float z, float u, float v,
                               int light, int overlay, int tint, float normalX, float normalY, float normalZ) {
        vertexColor(out, pose, x, y, z, u, v, light, overlay, tint * 0x010101, normalX, normalY, normalZ);
    }

    private static void vertexColor(Surface out, PoseStack.Pose pose, float x, float y, float z, float u, float v,
                                    int light, int overlay, int rgb, float normalX, float normalY, float normalZ) {
        if (out.sprite() != null) {
            u = out.sprite().getU(u);
            v = out.sprite().getV(v);
        }
        out.vertices().addVertex(pose, x, y, z).setColor(0xFF000000 | rgb).setUv(u, v)
                .setOverlay(overlay).setLight(light).setNormal(pose, normalX, normalY, normalZ);
    }

    @Override
    public AABB getRenderBoundingBox(AirlockBlockEntity door) {
        var state = door.getBlockState();
        int extra = ((AirlockBlock) state.getBlock()).size() - 1;
        boolean xAxis = state.getValue(BlockStateProperties.HORIZONTAL_AXIS) == Direction.Axis.X;
        return new AABB(door.getBlockPos()).expandTowards(xAxis ? 0 : extra, extra, xAxis ? extra : 0);
    }
}
