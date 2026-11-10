package dev.orbitalarchitect;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;

final class AltarRecovery {
    static int tierAt(ServerLevel level, BlockPos pos) {
        var chunk = level.getChunkAt(pos);
        var type = OrbitalArchitect.ALTAR_RECOVERY.get();
        if (!chunk.hasData(type)) return 0;
        return chunk.getData(type).getCompound(key(pos)).getInt("Tier");
    }

    static void remember(ServerLevel level, OrbAltarBlockEntity altar) {
        var pos = altar.getBlockPos();
        var chunk = level.getChunkAt(pos);
        var type = OrbitalArchitect.ALTAR_RECOVERY.get();
        var record = new CompoundTag();
        record.putInt("Tier", altar.tier());
        record.put("Project", altar.saveWithoutMetadata(level.registryAccess()));
        chunk.getData(type).put(key(pos), record);
        chunk.setUnsaved(true);
    }

    static boolean restore(ServerLevel level, OrbAltarBlockEntity altar) {
        var pos = altar.getBlockPos();
        var chunk = level.getChunkAt(pos);
        var type = OrbitalArchitect.ALTAR_RECOVERY.get();
        if (!chunk.hasData(type)) return false;
        var records = chunk.getData(type);
        var record = records.getCompound(key(pos));
        int tier = record.getInt("Tier");
        if (tier == 0 || tier > altar.tier()) return false;

        altar.loadWithComponents(record.getCompound("Project").copy(), level.registryAccess());
        altar.setChanged();
        records.remove(key(pos));
        if (records.isEmpty()) chunk.removeData(type);
        chunk.setUnsaved(true);
        return true;
    }

    private static String key(BlockPos pos) {
        return Long.toString(pos.asLong());
    }
}
