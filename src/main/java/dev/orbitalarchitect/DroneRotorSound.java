package dev.orbitalarchitect;

import java.util.IdentityHashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundSource;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

@EventBusSubscriber(modid = OrbitalArchitect.ID, value = Dist.CLIENT)
public final class DroneRotorSound extends AbstractTickableSoundInstance {
    private static final Map<ConstructionDrone, DroneRotorSound> ACTIVE = new IdentityHashMap<>();
    private final ConstructionDrone drone;

    private DroneRotorSound(ConstructionDrone drone) {
        super(OrbitalArchitect.DRONE_ROTOR.get(), SoundSource.BLOCKS, SoundInstance.createUnseededRandom());
        this.drone = drone;
        looping = true;
        attenuation = Attenuation.LINEAR;
        updateSound();
    }

    @SubscribeEvent
    public static void update(ClientTickEvent.Post event) {
        var client = Minecraft.getInstance();
        if (client.player == null || client.level == null) {
            ACTIVE.values().forEach(DroneRotorSound::stop);
            ACTIVE.clear();
            return;
        }
        ACTIVE.values().removeIf(sound -> {
            if (sound.drone.isRemoved() || sound.drone.isDocked() || sound.drone.level() != client.level
                    || sound.drone.distanceToSqr(client.player) > 196) sound.stop();
            return sound.isStopped();
        });
        for (var drone : client.level.getEntitiesOfClass(ConstructionDrone.class,
                client.player.getBoundingBox().inflate(12))) {
            if (ACTIVE.size() >= 6) break;
            if (!drone.isDocked() && drone.distanceToSqr(client.player) < 144 && !ACTIVE.containsKey(drone)) {
                var sound = new DroneRotorSound(drone);
                ACTIVE.put(drone, sound);
                client.getSoundManager().play(sound);
            }
        }
    }

    @Override
    public void tick() {
        var player = Minecraft.getInstance().player;
        if (drone.isRemoved() || drone.isDocked() || player == null || drone.level() != player.level()
                || drone.distanceToSqr(player) > 196) {
            stop();
            return;
        }
        updateSound();
    }

    private void updateSound() {
        x = drone.getX();
        y = drone.getY();
        z = drone.getZ();
        volume = drone.cargoPhase() == ConstructionDrone.CargoPhase.CARRY ? .24f : .17f;
        pitch = .91f + drone.tier().level * .045f;
    }
}
