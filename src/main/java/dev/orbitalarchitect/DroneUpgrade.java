package dev.orbitalarchitect;

import net.minecraft.world.item.ItemStack;

enum DroneUpgrade {
    EXCAVATOR("escavacao", "Escavação", "Amplia o lote de limpeza do terreno."),
    BATTERY("bateria", "Bateria", "Dobra a capacidade de energia."),
    EFFICIENCY("eficiencia", "Eficiência", "Reduz o custo por operação em 25%."),
    OVERCLOCK("overclock", "Overclock", "Aumenta a velocidade e o lote, com 50% mais consumo.");

    final String id;
    final String label;
    final String description;

    DroneUpgrade(String id, String label, String description) {
        this.id = "drone_upgrade_" + id;
        this.label = label;
        this.description = description;
    }

    ItemStack stack() {
        return new ItemStack(OrbitalArchitect.DRONE_UPGRADES.get(ordinal()).get());
    }
}
