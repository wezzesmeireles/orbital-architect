package dev.orbitalarchitect;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.world.phys.AABB;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;

@EventBusSubscriber(modid = OrbitalArchitect.ID, value = Dist.CLIENT)
public final class AltarOrbRenderer implements BlockEntityRenderer<OrbAltarBlockEntity> {
    private static final float TAU = (float) (Math.PI * 2);
    private static final int HOLO_BLUE = 0x59DDF6;

    @SubscribeEvent
    public static void registerRenderer(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(OrbitalArchitect.ALTAR_ENTITY.get(), context -> new AltarOrbRenderer());
    }

    @Override
    public void render(OrbAltarBlockEntity altar, float partialTick, PoseStack pose,
                       MultiBufferSource buffers, int light, int overlay) {
        if (altar.getLevel() == null) return;
        var style = AltarTierStyle.from(altar.tier());
        int index = style.ordinal();
        double time = (altar.getLevel().getGameTime() % 7200) + partialTick;
        pose.pushPose();
        pose.translate(.5, 1.53 + index * .17 + Math.sin(time * Math.PI / 55) * .045, .5);
        var vertices = buffers.getBuffer(RenderType.debugQuads());
        reactor(pose, vertices, time, style);
        hologram(pose, vertices, time, index, style.accent);
        pose.popPose();
    }

    private static void reactor(PoseStack pose, VertexConsumer out, double time, AltarTierStyle style) {
        int tier = style.ordinal();
        int frames = Math.min(3, style.rings + 1);
        for (int layer = 0; layer < frames; layer++) {
            pose.pushPose();
            float spin = (float) time * style.speed * (layer % 2 == 0 ? .57f : -.41f);
            pose.mulPose(Axis.YP.rotationDegrees(spin + 53 * layer));
            float tilt = switch (tier) {
                case 0 -> 8;
                case 1 -> layer == 0 ? 22 : 78;
                case 2 -> layer == 0 ? 76 : layer == 1 ? -62 : 0;
                default -> layer == 0 ? 19 : layer == 1 ? 61 : -57;
            };
            pose.mulPose(Axis.XP.rotationDegrees(tilt));
            float radius = style.scale * (.78f - layer * .15f);
            float width = (tier == 0 ? .18f : .14f) - layer * .023f;
            int sectors = 2 + tier + layer;
            for (int sector = 0; sector < sectors; sector++) {
                float angle = sector * TAU / sectors;
                float arc = TAU / sectors - .17f;
                rotorSegment(out, pose.last(), radius, width, angle, arc, tier);
                ring(out, pose.last(), radius + width * .19f, .043f, angle + .08f,
                        arc - .16f, .024f, style.accent, 238);
                ring(out, pose.last(), radius + width * .19f, -.043f, angle + .08f,
                        arc - .16f, .024f, style.accent, 238);
            }
            pose.popPose();
        }
        if (tier == 3) {
            float rotation = (float) time * TAU / 155;
            ring(out, pose.last(), .29f, .29f, rotation, TAU, .035f, style.accent, 214);
            ring(out, pose.last(), .29f, -.29f, -rotation, TAU, .035f, style.accent, 214);
        }
    }

    private static void rotorSegment(VertexConsumer out, PoseStack.Pose pose, float radius, float width,
                                     float start, float arc, int tier) {
        float inner = radius - width / 2;
        float outer = radius + width / 2;
        float halfHeight = .041f;
        int steps = Math.max(4, (int) Math.ceil(arc * 8));
        int surface = switch (tier) { case 0 -> 0xA87755; case 1 -> 0xBCCDD9; case 2 -> 0x5C828B; default -> 0xD2DAE5; };
        ring(out, pose, radius, halfHeight, start, arc, width, surface, 255);
        ring(out, pose, radius, -halfHeight, start, arc, width, 0x4E6275, 255);
        for (int step = 0; step < steps; step++) {
            float a = start + arc * step / steps;
            float b = start + arc * (step + 1) / steps;
            rotorWall(out, pose, outer, a, outer, b, halfHeight, 0x2D3845);
            rotorWall(out, pose, inner, b, inner, a, halfHeight, 0x1D2732);
        }
        rotorWall(out, pose, inner, start, outer, start, halfHeight, 0x4A5C6A);
        rotorWall(out, pose, outer, start + arc, inner, start + arc, halfHeight, 0x4A5C6A);
    }

    private static void rotorWall(VertexConsumer out, PoseStack.Pose pose, float r1, float a1, float r2, float a2,
                                  float height, int color) {
        vertex(out, pose, r1, -height, a1, color, 255);
        vertex(out, pose, r2, -height, a2, color, 255);
        vertex(out, pose, r2, height, a2, color, 255);
        vertex(out, pose, r1, height, a1, color, 255);
    }

    private static void hologram(PoseStack pose, VertexConsumer out, double time, int tier, int accent) {
        float radius = .91f + tier * .25f;
        float spin = (float) time * TAU / 180;
        ring(out, pose.last(), radius, -.506f, 0, TAU, .12f, HOLO_BLUE, 27);
        ring(out, pose.last(), radius, -.50f, 0, TAU, .039f, HOLO_BLUE, 184);
        if (tier >= 2) ring(out, pose.last(), radius * .86f, .58f, 0, TAU, .018f, HOLO_BLUE, 72);
        int ticks = 8 + tier * 4;
        for (int i = 0; i < ticks; i++) {
            float length = i % 5 == 0 ? .13f : .055f;
            ring(out, pose.last(), radius + .13f, -.50f, spin * .5f + i * TAU / ticks,
                    .060f, length, HOLO_BLUE, i % 5 == 0 ? 216 : 118);
        }
        for (int i = 0; i < 4; i++) {
            ring(out, pose.last(), radius + .04f, -.49f, spin + i * TAU / 4,
                    .49f, .055f, accent, 216);
        }
        for (int i = 0; i < tier; i++) {
            pose.pushPose();
            pose.mulPose(Axis.YP.rotationDegrees((float) time * (1.8f - .6f * i) + i * 61));
            pose.mulPose(Axis.XP.rotationDegrees(62 + i * 19));
            float orbit = radius * (.78f + i * .075f);
            ring(out, pose.last(), orbit, 0, 0, TAU, .022f, HOLO_BLUE, 104);
            ring(out, pose.last(), orbit, .006f, -spin * (i + 1), .94f, .052f, HOLO_BLUE, 220);
            pose.popPose();
        }
        if (tier >= 2) {
            float scan = (float) Math.sin(time * TAU / 100) * .70f;
            float scanRadius = radius * .67f * (float) Math.sqrt(1 - .52f * scan * scan);
            ring(out, pose.last(), scanRadius, scan, 0, TAU, .018f, 0xBDFFFF, 130);
        }
    }

    private static void ring(VertexConsumer out, PoseStack.Pose pose, float radius, float y, float start,
                             float arc, float thickness, int color, int alpha) {
        int steps = Math.max(1, (int) Math.ceil(arc * 8));
        float inner = radius - thickness / 2;
        float outer = radius + thickness / 2;
        for (int i = 0; i < steps; i++) {
            float a = start + arc * i / steps;
            float b = start + arc * (i + 1) / steps;
            vertex(out, pose, inner, y, a, color, alpha);
            vertex(out, pose, outer, y, a, color, alpha);
            vertex(out, pose, outer, y, b, color, alpha);
            vertex(out, pose, inner, y, b, color, alpha);
        }
    }

    private static void vertex(VertexConsumer out, PoseStack.Pose pose, float radius, float y,
                               float angle, int color, int alpha) {
        out.addVertex(pose, radius * (float) Math.cos(angle), y, radius * (float) Math.sin(angle))
                .setColor(alpha << 24 | color);
    }

    @Override
    public AABB getRenderBoundingBox(OrbAltarBlockEntity altar) {
        return new AABB(altar.getBlockPos()).inflate(2.4, 0, 2.4).expandTowards(0, 4.2, 0);
    }

}



