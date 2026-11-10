package dev.orbitalarchitect;

enum AltarTierStyle {
    PIONEER("Pioneiro", "Baú adjacente", 0xF5AA69, 0, .80f, 2.5f),
    ENGINEER("Engenheiro", "Baú adjacente", 0x9DCBFF, 1, .88f, 3.0f),
    NETWORK("Rede ME", "Inventário ME ou baú", 0x69F0D9, 2, .95f, 4.0f),
    EXPERIMENTAL("Protótipo", "Inventário ME ou baú", 0xDCA7FF, 3, 1.03f, 3.5f);

    private static final AltarTierStyle[] STYLES = values();
    final String title;
    final String capability;
    final int accent;
    final int rings;
    final float scale;
    final float speed;

    AltarTierStyle(String title, String capability, int accent, int rings, float scale, float speed) {
        this.title = title;
        this.capability = capability;
        this.accent = accent;
        this.rings = rings;
        this.scale = scale;
        this.speed = speed;
    }

    static AltarTierStyle from(int tier) {
        return STYLES[Math.clamp(tier - 1, 0, STYLES.length - 1)];
    }
}

