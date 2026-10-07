package jonyboylovespie.jade.plugin.provider.universal;

import it.unimi.dsi.fastutil.objects.Object2IntLinkedOpenHashMap;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import jonyboylovespie.jade.plugin.JadeCodecs;
import jonyboylovespie.jade.plugin.provider.BlockAccessor;
import jonyboylovespie.jade.plugin.provider.EntityAccessor;
import jonyboylovespie.jade.plugin.provider.ServerDataProviderManager;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamEncoder;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.LockCode;
import net.minecraft.world.RandomizableContainer;
import net.minecraft.world.WorldlyContainerHolder;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.equine.AbstractHorse;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.ContainerEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.EnderChestBlockEntity;
import org.jspecify.annotations.Nullable;

/**
 * Server half of Jade's {@code minecraft:item_storage} provider (snownee.jade.addon.universal.ItemStorageProvider).
 * <p>
 * Payload: {@code Map.Entry<Identifier, List<ViewGroup<ItemStack>>>} encoded with {@code ViewGroup.listCodec}, where the
 * identifier selects the client-side extension that renders the groups ({@code minecraft:item_storage.default}).
 * Without contents, the flags {@code Loot} / {@code Locked} are sent instead so the client can explain why.
 */
public final class ItemStorageProvider {
    public static final String UID = "minecraft:item_storage";
    public static final String PERMISSION = "jadeplugin.itemstorage";
    private static final Identifier DEFAULT_EXTENSION = Identifier.parse("minecraft:item_storage.default");
    /** Same as Jade's {@code ItemCollector.MAX_SIZE}. */
    private static final int MAX_SIZE = 54;
    /** Jade transfers stacks of more than 99 items as 99 and keeps the real count in custom data. */
    private static final int MAX_WIRE_COUNT = 99;
    private static final String JADE_COUNT = "__JadeCount";
    private static final CompoundTag IGNORED_TAG = new CompoundTag();

    static {
        IGNORED_TAG.putBoolean("__JadeClear", true);
    }

    private static final StreamEncoder<RegistryFriendlyByteBuf, List<ItemStack>> STREAM_CODEC = (buf, items) -> {
        Identifier.STREAM_CODEC.encode(buf, DEFAULT_EXTENSION);
        buf.writeVarInt(1); // group count
        buf.writeVarInt(items.size());
        for (ItemStack stack : items) {
            ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, toWireStack(stack));
        }
        buf.writeBoolean(false); // group id
        buf.writeBoolean(false); // group extra data
    };

    public static void register() {
        ServerDataProviderManager.registerBlockProvider(UID, (uid, data, accessor) -> appendBlock(uid, data, accessor));
        ServerDataProviderManager.registerEntityProvider(UID, (uid, data, accessor) -> appendEntity(uid, data, accessor));
    }

    private static void appendBlock(String uid, CompoundTag data, BlockAccessor accessor) {
        if (!canSee(accessor.player())) {
            return;
        }
        BlockEntity blockEntity = accessor.blockEntity();
        if (blockEntity instanceof AbstractFurnaceBlockEntity) {
            return; // furnaces have their own provider on the client
        }
        Container container = null;
        if (blockEntity == null) {
            if (accessor.block() instanceof WorldlyContainerHolder holder) {
                container = holder.getContainer(accessor.state(), accessor.level(), accessor.pos());
            }
        } else if (blockEntity instanceof RandomizableContainer randomizable && randomizable.getLootTable() != null) {
            data.putBoolean("Loot", true);
            return;
        } else if (isLocked(blockEntity, accessor.player())) {
            data.putBoolean("Locked", true);
            return;
        } else if (blockEntity instanceof EnderChestBlockEntity) {
            container = accessor.player().getEnderChestInventory();
        } else if (blockEntity instanceof ChestBlockEntity chest && accessor.block() instanceof ChestBlock chestBlock) {
            container = ChestBlock.getContainer(chestBlock, accessor.state(), accessor.level(), accessor.pos(), true);
            if (container == null) {
                container = chest;
            }
        } else if (blockEntity instanceof Container blockContainer) {
            container = blockContainer;
        }
        if (container != null) {
            put(uid, data, container);
        }
    }

    private static void appendEntity(String uid, CompoundTag data, EntityAccessor accessor) {
        if (!canSee(accessor.player())) {
            return;
        }
        Entity entity = accessor.entity();
        if (entity instanceof Player || entity instanceof ArmorStand) {
            return;
        }
        if (entity instanceof ContainerEntity containerEntity && containerEntity.getContainerLootTable() != null) {
            data.putBoolean("Loot", true);
            return;
        }
        Container container = null;
        if (entity instanceof AbstractHorse horse) {
            container = horse.inventory;
        } else if (entity instanceof Container entityContainer) {
            container = entityContainer;
        }
        if (container != null) {
            put(uid, data, container);
        }
    }

    private static boolean canSee(ServerPlayer player) {
        return player.getBukkitEntity().hasPermission(PERMISSION);
    }

    private static boolean isLocked(BlockEntity blockEntity, ServerPlayer player) {
        return !player.isCreative()
                && !player.isSpectator()
                && blockEntity instanceof BaseContainerBlockEntity container
                && container.lockKey != LockCode.NO_LOCK;
    }

    private static void put(String uid, CompoundTag data, Container container) {
        data.put(uid, JadeCodecs.encodeAsNbt(STREAM_CODEC, collect(container, data.getBooleanOr("SortItems", false))));
    }

    private record ItemDefinition(Item item, DataComponentPatch components) {
        ItemDefinition(ItemStack stack) {
            this(stack.getItem(), stack.getComponentsPatch());
        }

        ItemStack toStack(int count) {
            ItemStack stack = new ItemStack(item, count);
            stack.applyComponents(components);
            return stack;
        }
    }

    /** Merges equal stacks like Jade's {@code ItemCollector}, optionally sorted by amount (client "sort" option). */
    private static List<ItemStack> collect(Container container, boolean sort) {
        Object2IntLinkedOpenHashMap<ItemDefinition> items = new Object2IntLinkedOpenHashMap<>();
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            ItemStack stack = container.getItem(slot);
            if (isShown(stack)) {
                items.addTo(new ItemDefinition(stack), stack.getCount());
            }
        }
        var entries = new ArrayList<Map.Entry<ItemDefinition, Integer>>(items.object2IntEntrySet());
        if (sort) {
            entries.sort(Comparator.comparingInt((Map.Entry<ItemDefinition, Integer> entry) -> entry.getValue()).reversed());
        }
        List<ItemStack> result = new ArrayList<>(Math.min(entries.size(), MAX_SIZE));
        for (var entry : entries) {
            if (result.size() >= MAX_SIZE) {
                break;
            }
            result.add(entry.getKey().toStack(entry.getValue()));
        }
        return result;
    }

    private static boolean isShown(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        if (stack.getOrDefault(DataComponents.TOOLTIP_DISPLAY, TooltipDisplay.DEFAULT).hideTooltip()) {
            return false;
        }
        if (stack.hasNonDefault(DataComponents.CUSTOM_MODEL_DATA) || stack.hasNonDefault(DataComponents.ITEM_MODEL)) {
            return !stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).matchedBy(IGNORED_TAG);
        }
        return true;
    }

    private static ItemStack toWireStack(ItemStack stack) {
        int count = stack.getCount();
        if (count <= MAX_WIRE_COUNT) {
            return stack;
        }
        ItemStack wire = stack.copyWithCount(MAX_WIRE_COUNT);
        CustomData customData = wire.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).update(tag -> tag.putInt(JADE_COUNT, count));
        wire.set(DataComponents.CUSTOM_DATA, customData);
        return wire;
    }

    private ItemStorageProvider() {
    }
}
