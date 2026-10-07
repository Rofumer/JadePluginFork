package jonyboylovespie.jade.plugin.provider.vanilla;

import static jonyboylovespie.jade.plugin.provider.ServerDataProvider.stream;

import java.util.UUID;
import jonyboylovespie.jade.plugin.provider.BlockAccessor;
import jonyboylovespie.jade.plugin.provider.EntityAccessor;
import jonyboylovespie.jade.plugin.provider.ServerDataProviderManager;
import net.minecraft.core.Direction;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.util.Unit;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityReference;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.allay.Allay;
import net.minecraft.world.entity.animal.frog.Tadpole;
import net.minecraft.world.entity.animal.golem.CopperGolem;
import net.minecraft.world.entity.monster.zombie.ZombieVillager;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.level.block.CalibratedSculkSensorBlock;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.entity.BeehiveBlockEntity;
import net.minecraft.world.level.block.entity.BrewingStandBlockEntity;
import net.minecraft.world.level.block.entity.CalibratedSculkSensorBlockEntity;
import net.minecraft.world.level.block.entity.CommandBlockEntity;
import net.minecraft.world.level.block.entity.ComparatorBlockEntity;
import net.minecraft.world.level.block.entity.TrialSpawnerBlockEntity;
import net.minecraft.world.level.block.entity.trialspawner.TrialSpawnerStateData;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.bukkit.Bukkit;
import org.jspecify.annotations.Nullable;

/**
 * Server-side halves of Jade's vanilla providers. Payload formats follow the stream codecs of the
 * corresponding providers in Jade (snownee.jade.addon.vanilla).
 */
public final class VanillaProviders {
    private record BrewingData(int fuel, int time) {
        static final StreamCodec<RegistryFriendlyByteBuf, BrewingData> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, BrewingData::fuel,
                ByteBufCodecs.VAR_INT, BrewingData::time,
                BrewingData::new);
    }

    private static final int IN_LOVE = -1;

    public static void register() {
        ServerDataProviderManager.registerBlockProvider("minecraft:brewing_stand", stream(BrewingData.STREAM_CODEC, VanillaProviders::brewingStand));
        ServerDataProviderManager.registerBlockProvider("minecraft:beehive", stream(ByteBufCodecs.BYTE, VanillaProviders::beehive));
        ServerDataProviderManager.registerBlockProvider("minecraft:command_block", stream(ByteBufCodecs.STRING_UTF8, VanillaProviders::commandBlock));
        ServerDataProviderManager.registerBlockProvider("minecraft:hopper_lock", stream(ByteBufCodecs.BOOL, VanillaProviders::hopperLock));
        ServerDataProviderManager.registerBlockProvider("minecraft:redstone", stream(ByteBufCodecs.VAR_INT, VanillaProviders::redstone));
        ServerDataProviderManager.registerBlockProvider("minecraft:mob_spawner.cooldown", stream(ByteBufCodecs.VAR_INT, VanillaProviders::mobSpawnerCooldown));

        ServerDataProviderManager.registerEntityProvider("minecraft:animal_owner", stream(ComponentSerialization.STREAM_CODEC, VanillaProviders::animalOwner));
        ServerDataProviderManager.registerEntityProvider("minecraft:entity_health", stream(ByteBufCodecs.FLOAT, VanillaProviders::entityHealth));
        ServerDataProviderManager.registerEntityProvider("minecraft:mob_growth", stream(ByteBufCodecs.VAR_INT, VanillaProviders::mobGrowth));
        ServerDataProviderManager.registerEntityProvider("minecraft:mob_breeding", stream(ByteBufCodecs.VAR_INT, VanillaProviders::mobBreeding));
        ServerDataProviderManager.registerEntityProvider("minecraft:waxed", stream(Unit.STREAM_CODEC, VanillaProviders::waxed));
        ServerDataProviderManager.registerEntityProvider("minecraft:zombie_villager", stream(ByteBufCodecs.VAR_INT, VanillaProviders::zombieVillager));
    }

    private static @Nullable BrewingData brewingStand(BlockAccessor accessor) {
        if (!(accessor.blockEntity() instanceof BrewingStandBlockEntity brewingStand)) {
            return null;
        }
        return new BrewingData(brewingStand.fuel, brewingStand.brewTime);
    }

    private static @Nullable Byte beehive(BlockAccessor accessor) {
        if (!(accessor.blockEntity() instanceof BeehiveBlockEntity beehive)) {
            return null;
        }
        int bees = beehive.getOccupantCount();
        return (byte) (beehive.isFull() ? bees : -bees);
    }

    private static @Nullable String commandBlock(BlockAccessor accessor) {
        if (!accessor.player().canUseGameMasterBlocks()) {
            return null;
        }
        if (!(accessor.blockEntity() instanceof CommandBlockEntity commandBlock)) {
            return null;
        }
        String command = commandBlock.getCommandBlock().getCommand();
        if (command.isEmpty()) {
            return null;
        }
        if (command.length() > 40) {
            command = command.substring(0, 37) + "...";
        }
        return command;
    }

    private static @Nullable Boolean hopperLock(BlockAccessor accessor) {
        if (!(accessor.block() instanceof HopperBlock)) {
            return null;
        }
        return !accessor.state().getValue(BlockStateProperties.ENABLED);
    }

    private static @Nullable Integer redstone(BlockAccessor accessor) {
        if (accessor.blockEntity() instanceof ComparatorBlockEntity comparator) {
            return comparator.getOutputSignal();
        } else if (accessor.blockEntity() instanceof CalibratedSculkSensorBlockEntity) {
            Direction direction = accessor.state().getValue(CalibratedSculkSensorBlock.FACING).getOpposite();
            return accessor.level().getSignal(accessor.pos().relative(direction), direction);
        }
        return null;
    }

    private static @Nullable Integer mobSpawnerCooldown(BlockAccessor accessor) {
        if (!(accessor.blockEntity() instanceof TrialSpawnerBlockEntity spawner)) {
            return null;
        }
        TrialSpawnerStateData stateData = spawner.getTrialSpawner().getStateData();
        long gameTime = accessor.level().getGameTime();
        if (spawner.getTrialSpawner().canSpawnInLevel(accessor.level()) && gameTime < stateData.cooldownEndsAt) {
            return (int) (stateData.cooldownEndsAt - gameTime);
        }
        return null;
    }

    private static @Nullable Component animalOwner(EntityAccessor accessor) {
        if (!(accessor.entity() instanceof OwnableEntity ownable)) {
            return null;
        }
        EntityReference<LivingEntity> reference = ownable.getOwnerReference();
        if (reference == null) {
            return null;
        }
        UUID uuid = reference.getUUID();
        Entity owner = accessor.level().getEntity(uuid);
        if (owner != null) {
            return owner.getName();
        }
        String name = Bukkit.getOfflinePlayer(uuid).getName();
        return name == null ? null : Component.literal(name);
    }

    private static @Nullable Float entityHealth(EntityAccessor accessor) {
        if (!(accessor.entity() instanceof LivingEntity living)) {
            return null;
        }
        return Math.max(living.getAbsorptionAmount(), 0F);
    }

    private static @Nullable Integer mobGrowth(EntityAccessor accessor) {
        int time = -1;
        if (accessor.entity() instanceof AgeableMob ageable) {
            time = -ageable.getAge();
        } else if (accessor.entity() instanceof Tadpole tadpole) {
            time = Tadpole.ticksToBeFrog - ((org.bukkit.entity.Tadpole) tadpole.getBukkitEntity()).getAge();
        }
        return time > 0 ? time : null;
    }

    private static @Nullable Integer mobBreeding(EntityAccessor accessor) {
        int time = 0;
        Entity entity = accessor.entity();
        if (entity instanceof Allay allay) {
            if (allay.duplicationCooldown > 0 && allay.duplicationCooldown < Integer.MAX_VALUE) {
                time = (int) allay.duplicationCooldown;
            }
        } else if (entity instanceof Villager villager) {
            time = villager.getAge();
        } else if (entity instanceof Animal animal) {
            if (animal.isInLove()) {
                return IN_LOVE;
            }
            time = animal.getAge();
        }
        return time > 0 ? time : null;
    }

    private static @Nullable Unit waxed(EntityAccessor accessor) {
        if (!(accessor.entity() instanceof CopperGolem golem)) {
            return null;
        }
        return golem.nextWeatheringTick == CopperGolem.IGNORE_WEATHERING_TICK ? Unit.INSTANCE : null;
    }

    private static @Nullable Integer zombieVillager(EntityAccessor accessor) {
        if (!(accessor.entity() instanceof ZombieVillager zombieVillager)) {
            return null;
        }
        int time = zombieVillager.villagerConversionTime;
        return time > 0 ? time : null;
    }

    private VanillaProviders() {
    }
}
