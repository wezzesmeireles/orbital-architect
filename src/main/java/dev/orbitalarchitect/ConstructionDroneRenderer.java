package dev.orbitalarchitect;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.ModelEvent;

@EventBusSubscriber(modid = OrbitalArchitect.ID, value = Dist.CLIENT)
public final class ConstructionDroneRenderer extends EntityRenderer<ConstructionDrone> {
    private static final ModelResourceLocation ROTOR =
            ModelResourceLocation.standalone(OrbitalArchitect.id("item/construction_drone_rotor"));
    private static final ModelResourceLocation GRIPPER =
            ModelResourceLocation.standalone(OrbitalArchitect.id("item/construction_drone_gripper"));
    private static final int[] MOTOR_POSITIONS = {3, 13};
    private static final int[] JAW_SIDES = {-1, 1};
    private final ItemRenderer items;
    private final java.util.List<ItemStack> models = OrbitalArchitect.DRONES.stream()
            .map(item -> new ItemStack(item.get())).toList();

    ConstructionDroneRenderer(EntityRendererProvider.Context context) {
        super(context);
        items = context.getItemRenderer();
    }

    @SubscribeEvent
    public static void register(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(OrbitalArchitect.CONSTRUCTION_DRONE.get(), ConstructionDroneRenderer::new);
    }

    @SubscribeEvent
    public static void registerModels(ModelEvent.RegisterAdditional event) {
        event.register(ROTOR);
        event.register(GRIPPER);
    }

    @Override
    public void render(ConstructionDrone drone, float yaw, float partial, PoseStack pose, MultiBufferSource buffers,
                       int light) {
        float time = drone.tickCount + partial;
        boolean parked = drone.isDocked();
        var phase = drone.cargoPhase();
        float progress = switch (phase) {
            case PICKUP -> progress(drone, partial, ConstructionFleet.PICKUP_TICKS);
            case PLACE -> progress(drone, partial, ConstructionFleet.PLACE_TICKS);
            default -> 0;
        };
        pose.pushPose();
        pose.translate(0, parked ? .18 : .23 + Math.sin(time * .45) * .06, 0);
        pose.mulPose(Axis.YP.rotationDegrees(180 - yaw));
        if (!parked) pose.mulPose(Axis.ZP.rotationDegrees((float) Math.sin(time * .31) * 6));
        float scale = .82f + drone.tier().level * .08f;
        pose.scale(scale, scale, scale);
        var model = models.get(drone.tier().level - 1);
        items.renderStatic(model, ItemDisplayContext.NONE, light,
                OverlayTexture.NO_OVERLAY, pose, buffers, drone.level(), drone.getId());
        var rotor = Minecraft.getInstance().getModelManager().getModel(ROTOR);
        for (int x : MOTOR_POSITIONS) for (int z : MOTOR_POSITIONS) {
            float spin = parked ? 35 : (x == z ? 1 : -1) * time * (30 + drone.tier().level * 9);
            pose.pushPose();
            pose.translate((x - 8) / 16f, (10.05f - 8) / 16f, (z - 8) / 16f);
            pose.mulPose(Axis.YP.rotationDegrees(spin));
            items.render(model, ItemDisplayContext.NONE, false, pose, buffers, light, OverlayTexture.NO_OVERLAY, rotor);
            pose.popPose();
        }
        float grip = switch (phase) {
            case EMPTY -> 26;
            case PICKUP -> 26 * (1 - progress);
            case CARRY -> 0;
            case PLACE -> 26 * progress;
        };
        var gripper = Minecraft.getInstance().getModelManager().getModel(GRIPPER);
        for (int side : JAW_SIDES) {
            pose.pushPose();
            pose.translate(side * .145f, -.13, 0);
            pose.mulPose(Axis.ZP.rotationDegrees(side * grip));
            if (side < 0) pose.mulPose(Axis.YP.rotationDegrees(180));
            items.render(model, ItemDisplayContext.NONE, false, pose, buffers, light, OverlayTexture.NO_OVERLAY, gripper);
            pose.popPose();
        }
        pose.popPose();
        if (!drone.cargo().isEmpty()) {
            float lower = switch (phase) {
                case PICKUP -> Mth.lerp(progress, -.64f, -.25f);
                case PLACE -> Mth.lerp(progress, -.25f, -.92f);
                default -> -.25f;
            };
            pose.pushPose();
            pose.translate(0, lower, 0);
            pose.mulPose(Axis.YP.rotationDegrees(180 - yaw));
            pose.scale(.43f, .43f, .43f);
            items.renderStatic(drone.cargo(), ItemDisplayContext.NONE, light, OverlayTexture.NO_OVERLAY,
                    pose, buffers, drone.level(), drone.getId());
            pose.popPose();
        }
    }

    private static float progress(ConstructionDrone drone, float partial, int duration) {
        float t = Mth.clamp((drone.level().getGameTime() - drone.phaseStart() + partial) / duration, 0, 1);
        return t * t * (3 - 2 * t);
    }

    @Override
    public ResourceLocation getTextureLocation(ConstructionDrone drone) { return TextureAtlas.LOCATION_BLOCKS; }
}
