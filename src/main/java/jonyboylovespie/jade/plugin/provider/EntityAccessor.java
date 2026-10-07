package jonyboylovespie.jade.plugin.provider;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

public record EntityAccessor(
        ServerPlayer player,
        ServerLevel level,
        Entity entity,
        boolean showDetails,
        CompoundTag serverData) {
}
