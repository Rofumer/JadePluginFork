package jonyboylovespie.jade.selftest;

import com.mojang.authlib.GameProfile;
import it.unimi.dsi.fastutil.ints.IntList;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import jonyboylovespie.jade.plugin.JadeCodecs;
import jonyboylovespie.jade.plugin.JadeNetwork;
import jonyboylovespie.jade.plugin.provider.BlockAccessor;
import jonyboylovespie.jade.plugin.provider.EntityAccessor;
import jonyboylovespie.jade.plugin.provider.ServerDataProviderManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.ByteArrayTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.LockCode;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.entity.vehicle.minecart.MinecartChest;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.advancements.predicates.ItemPredicate;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BrewingStandBlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.storage.loot.BuiltInLootTables;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import snownee.jade.api.view.ViewGroup;
import snownee.jade.impl.BlockAccessorImpl;
import snownee.jade.impl.EntityAccessorImpl;
import snownee.jade.network.ServerHandshakePacket;

/**
 * Feeds JadePlugin's payloads through the codecs of the real Jade 26.3 jar inside a running Paper 26.3 server.
 */
public final class SelfTestPlugin extends JavaPlugin {
    private int passed;
    private int failed;
    private ServerLevel level;
    private ServerPlayer player;

    @Override
    public void onEnable() {
        Bukkit.getScheduler().runTask(this, () -> {
            try {
                runAll();
            } catch (Throwable t) {
                failed++;
                getLogger().log(Level.SEVERE, "Self test crashed", t);
            }
            getLogger().info("RESULT: " + passed + " passed, " + failed + " failed");
            if (Boolean.getBoolean("jade.selftest.shutdown")) {
                Bukkit.shutdown();
            }
        });
    }

    private void runAll() throws Exception {
        MinecraftServer server = MinecraftServer.getServer();
        level = server.overworld();
        player = new ServerPlayer(server, level, new GameProfile(UUID.randomUUID(), "JadeSelfTest"), ClientInformation.createDefault());
        player.setPos(0.5, 200, 0.5);

        run("handshake", this::testHandshake);
        run("block request decode", this::testBlockRequest);
        run("entity request decode", this::testEntityRequest);
        run("double chest contents", this::testDoubleChest);
        run("loot chest", this::testLootChest);
        run("locked barrel", this::testLockedBarrel);
        run("ender chest", this::testEnderChest);
        run("chest minecart", this::testChestMinecart);
        run("brewing stand", this::testBrewingStand);
        run("entity health", this::testEntityHealth);
    }

    private interface TestBody {
        void run() throws Exception;
    }

    private void run(String name, TestBody body) {
        int failedBefore = failed;
        try {
            body.run();
        } catch (Throwable t) {
            failed++;
            getLogger().log(Level.SEVERE, "[" + name + "] threw", t);
        }
        getLogger().info((failed == failedBefore ? "PASS " : "FAIL ") + name);
    }

    private void check(String what, boolean condition, Object actual) {
        if (condition) {
            passed++;
        } else {
            failed++;
            getLogger().severe("  check failed: " + what + " (actual: " + actual + ")");
        }
    }

    // ---- protocol ----

    private void testHandshake() {
        RegistryFriendlyByteBuf buf = JadeCodecs.wrap(JadeNetwork.encodeServerHandshake());
        ServerHandshakePacket packet = ServerHandshakePacket.CODEC.decode(buf);
        check("handshake fully consumed", buf.readableBytes() == 0, buf.readableBytes());
        List<String> blockIds = packet.blockProviderIds().stream().map(Identifier::toString).toList();
        List<String> entityIds = packet.entityProviderIds().stream().map(Identifier::toString).toList();
        check("block ids", blockIds.equals(ServerDataProviderManager.blockProviderIds()), blockIds);
        check("entity ids", entityIds.equals(ServerDataProviderManager.entityProviderIds()), entityIds);
        check("item_storage announced for blocks", blockIds.contains("minecraft:item_storage"), blockIds);
        check("item_storage announced for entities", entityIds.contains("minecraft:item_storage"), entityIds);
    }

    private void testBlockRequest() {
        BlockPos pos = new BlockPos(-123, 64, 4567);
        CompoundTag data = new CompoundTag();
        data.putBoolean("SortItems", true);
        // A non-empty "serverside rep" stack is what broke the original heuristic parser.
        ItemStack rep = new ItemStack(Items.CHEST, 3);
        rep.set(DataComponents.CUSTOM_NAME, Component.literal("rep"));
        var syncData = new BlockAccessorImpl.SyncData(true, new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false), rep, data);
        RegistryFriendlyByteBuf buf = JadeCodecs.newBuffer();
        BlockAccessorImpl.SyncData.STREAM_CODEC.encode(buf, syncData);
        writeIds(buf, 3, 0, 9);

        JadeNetwork.BlockRequest request = JadeNetwork.BlockRequest.decode(buf);
        check("request fully consumed", buf.readableBytes() == 0, buf.readableBytes());
        check("showDetails", request.showDetails(), request.showDetails());
        check("pos", request.hit().getBlockPos().equals(pos), request.hit().getBlockPos());
        check("data", request.data().getBooleanOr("SortItems", false), request.data());
        check("ids", request.providerIds().equals(IntList.of(3, 0, 9)), request.providerIds());
    }

    private void testEntityRequest() {
        CompoundTag data = new CompoundTag();
        data.putString("foo", "bar");
        var syncData = new EntityAccessorImpl.SyncData(false, 4242, 1, new Vec3(1.5, 2.5, 3.5), data);
        RegistryFriendlyByteBuf buf = JadeCodecs.newBuffer();
        EntityAccessorImpl.SyncData.STREAM_CODEC.encode(buf, syncData);
        writeIds(buf, 1, 6);

        JadeNetwork.EntityRequest request = JadeNetwork.EntityRequest.decode(buf);
        check("request fully consumed", buf.readableBytes() == 0, buf.readableBytes());
        check("entity id", request.entityId() == 4242, request.entityId());
        check("showDetails", !request.showDetails(), request.showDetails());
        check("data", "bar".equals(request.data().getStringOr("foo", "")), request.data());
        check("ids", request.providerIds().equals(IntList.of(1, 6)), request.providerIds());
    }

    private static void writeIds(RegistryFriendlyByteBuf buf, int... ids) {
        buf.writeVarInt(ids.length);
        for (int id : ids) {
            buf.writeVarInt(id);
        }
    }

    // ---- item storage ----

    private void testDoubleChest() throws Exception {
        BlockPos left = new BlockPos(0, 200, 0);
        BlockPos right = left.east();
        BlockState chest = Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.NORTH);
        level.setBlock(left, chest.setValue(ChestBlock.TYPE, ChestType.LEFT), 3);
        level.setBlock(right, chest.setValue(ChestBlock.TYPE, ChestType.RIGHT), 3);
        ChestBlockEntity leftChest = (ChestBlockEntity) level.getBlockEntity(left);
        ChestBlockEntity rightChest = (ChestBlockEntity) level.getBlockEntity(right);
        leftChest.setItem(0, new ItemStack(Items.STONE, 64));
        leftChest.setItem(1, new ItemStack(Items.STONE, 64));
        leftChest.setItem(2, new ItemStack(Items.APPLE, 5));
        rightChest.setItem(5, new ItemStack(Items.STONE, 10));
        ItemStack sword = new ItemStack(Items.DIAMOND_SWORD);
        sword.set(DataComponents.CUSTOM_NAME, Component.literal("Excalibur"));
        var sharpness = level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.SHARPNESS);
        ItemEnchantments.Mutable enchantments = new ItemEnchantments.Mutable(ItemEnchantments.EMPTY);
        enchantments.set(sharpness, 5);
        sword.set(DataComponents.ENCHANTMENTS, enchantments.toImmutable());
        rightChest.setItem(7, sword);

        // Look at the right half: contents of both halves must be reported.
        CompoundTag response = requestBlock(right, true);
        List<ItemStack> items = decodeItemStorage(response);
        check("3 distinct stacks", items.size() == 3, items);
        check("stone first (sorted) with 138 = 64+64+10 via __JadeCount", items.getFirst().is(Items.STONE) && items.getFirst().getCount() == 138, items.getFirst());
        ItemStack decodedSword = items.stream().filter(s -> s.is(Items.DIAMOND_SWORD)).findFirst().orElse(ItemStack.EMPTY);
        check("sword name kept", Component.literal("Excalibur").equals(decodedSword.get(DataComponents.CUSTOM_NAME)), decodedSword);
        check("sword enchantment kept", decodedSword.getEnchantments().getLevel(sharpness) == 5, decodedSword.getEnchantments());
        check("apples", items.stream().anyMatch(s -> s.is(Items.APPLE) && s.getCount() == 5), items);
    }

    private void testLootChest() {
        BlockPos pos = new BlockPos(4, 200, 0);
        level.setBlock(pos, Blocks.CHEST.defaultBlockState(), 3);
        ((ChestBlockEntity) level.getBlockEntity(pos)).setLootTable(BuiltInLootTables.SIMPLE_DUNGEON);
        CompoundTag response = requestBlock(pos, false);
        check("Loot flag", response.getBooleanOr("Loot", false), response);
        check("no contents sent", !response.contains("minecraft:item_storage"), response);
    }

    private void testLockedBarrel() {
        BlockPos pos = new BlockPos(6, 200, 0);
        level.setBlock(pos, Blocks.BARREL.defaultBlockState(), 3);
        BarrelBlockEntity barrel = (BarrelBlockEntity) level.getBlockEntity(pos);
        barrel.setItem(0, new ItemStack(Items.DIAMOND, 3));
        barrel.lockKey = new LockCode(ItemPredicate.Builder.item().build());
        CompoundTag response = requestBlock(pos, false);
        check("Locked flag", response.getBooleanOr("Locked", false), response);
        check("no contents sent", !response.contains("minecraft:item_storage"), response);
    }

    private void testEnderChest() throws Exception {
        BlockPos pos = new BlockPos(8, 200, 0);
        level.setBlock(pos, Blocks.ENDER_CHEST.defaultBlockState(), 3);
        player.getEnderChestInventory().setItem(3, new ItemStack(Items.EMERALD, 42));
        List<ItemStack> items = decodeItemStorage(requestBlock(pos, false));
        check("viewer's ender chest", items.size() == 1 && items.getFirst().is(Items.EMERALD) && items.getFirst().getCount() == 42, items);
    }

    private void testChestMinecart() throws Exception {
        MinecartChest minecart = EntityTypes.CHEST_MINECART.create(level, EntitySpawnReason.COMMAND);
        minecart.setPos(10.5, 200, 0.5);
        level.addFreshEntity(minecart);
        minecart.setItem(0, new ItemStack(Items.GOLD_INGOT, 7));
        CompoundTag response = requestEntity(new EntityAccessor(player, level, minecart, false, new CompoundTag()), "minecraft:item_storage");
        List<ItemStack> items = decodeItemStorage(response);
        check("minecart contents", items.size() == 1 && items.getFirst().is(Items.GOLD_INGOT) && items.getFirst().getCount() == 7, items);
        minecart.discard();
    }

    // ---- vanilla providers ----

    private void testBrewingStand() {
        BlockPos pos = new BlockPos(12, 200, 0);
        level.setBlock(pos, Blocks.BREWING_STAND.defaultBlockState(), 3);
        BrewingStandBlockEntity stand = (BrewingStandBlockEntity) level.getBlockEntity(pos);
        stand.fuel = 7;
        stand.brewTime = 123;
        CompoundTag response = requestBlock(pos, false, "minecraft:brewing_stand");
        RegistryFriendlyByteBuf buf = JadeCodecs.wrap(((ByteArrayTag) response.get("minecraft:brewing_stand")).getAsByteArray());
        int fuel = ByteBufCodecs.VAR_INT.decode(buf);
        int time = ByteBufCodecs.VAR_INT.decode(buf);
        check("fuel/time", fuel == 7 && time == 123, fuel + "/" + time);
    }

    private void testEntityHealth() {
        Zombie zombie = EntityTypes.ZOMBIE.create(level, EntitySpawnReason.COMMAND);
        zombie.setPos(14.5, 200, 0.5);
        level.addFreshEntity(zombie);
        zombie.getAttribute(Attributes.MAX_ABSORPTION).setBaseValue(20);
        zombie.setAbsorptionAmount(4F);
        CompoundTag response = requestEntity(new EntityAccessor(player, level, zombie, false, new CompoundTag()), "minecraft:entity_health");
        float absorption = ByteBufCodecs.FLOAT.decode(JadeCodecs.wrap(((ByteArrayTag) response.get("minecraft:entity_health")).getAsByteArray()));
        check("absorption", absorption == 4F, absorption);
        zombie.discard();
    }

    // ---- helpers ----

    private CompoundTag requestBlock(BlockPos pos, boolean sort) {
        return requestBlock(pos, sort, "minecraft:item_storage");
    }

    private CompoundTag requestBlock(BlockPos pos, boolean sort, String providerId) {
        CompoundTag data = new CompoundTag();
        if (sort) {
            data.putBoolean("SortItems", true);
        }
        BlockState state = level.getBlockState(pos);
        BlockEntity blockEntity = level.getBlockEntity(pos);
        int id = ServerDataProviderManager.blockProviderIds().indexOf(providerId);
        ServerDataProviderManager.handleBlockRequest(new BlockAccessor(player, level, pos, state, blockEntity, false, data), IntList.of(id));
        return throughWire(data);
    }

    private CompoundTag requestEntity(EntityAccessor accessor, String providerId) {
        int id = ServerDataProviderManager.entityProviderIds().indexOf(providerId);
        ServerDataProviderManager.handleEntityRequest(accessor, IntList.of(id));
        return throughWire(accessor.serverData());
    }

    /** Encodes the response like {@code jade:receive_data} and decodes it like the client does. */
    private static CompoundTag throughWire(CompoundTag data) {
        return ByteBufCodecs.COMPOUND_TAG.decode(JadeCodecs.wrap(JadeNetwork.encodeReceiveData(data)));
    }

    /** Decodes {@code minecraft:item_storage} with the codec of Jade's client ({@code ItemStorageProvider.STREAM_CODEC}). */
    @SuppressWarnings("unchecked")
    private List<ItemStack> decodeItemStorage(CompoundTag response) throws Exception {
        Tag tag = response.get("minecraft:item_storage");
        if (!(tag instanceof ByteArrayTag bytes)) {
            throw new AssertionError("no minecraft:item_storage in " + response);
        }
        // Jade's own client codec. Its >99 count mapping reads CustomData.tag, which Jade opens with an access widener
        // on Fabric; on Paper the field is private, so fall back to an identical copy of that mapping.
        StreamCodec<RegistryFriendlyByteBuf, Map.Entry<Identifier, List<ViewGroup<ItemStack>>>> replica = ViewGroup.listCodec(
                ItemStack.OPTIONAL_STREAM_CODEC.map(stack -> {
                    if (stack.count() < 99 || !stack.has(DataComponents.CUSTOM_DATA)) {
                        return stack;
                    }
                    stack.setCount(stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag().getIntOr("__JadeCount", 99));
                    return stack;
                }, stack -> stack));
        Field field = Class.forName("snownee.jade.addon.universal.ItemStorageProvider").getDeclaredField("STREAM_CODEC");
        field.setAccessible(true);
        var jadeCodec = (StreamCodec<RegistryFriendlyByteBuf, Map.Entry<Identifier, List<ViewGroup<ItemStack>>>>) field.get(null);
        RegistryFriendlyByteBuf buf = JadeCodecs.wrap(bytes.getAsByteArray());
        Map.Entry<Identifier, List<ViewGroup<ItemStack>>> entry;
        try {
            entry = jadeCodec.decode(buf);
        } catch (IllegalAccessError e) {
            getLogger().info("  (Jade codec needs its access widener for >99 counts, decoding with the replica)");
            buf = JadeCodecs.wrap(bytes.getAsByteArray());
            entry = replica.decode(buf);
        }
        check("payload fully consumed", buf.readableBytes() == 0, buf.readableBytes());
        check("default extension id", entry.getKey().toString().equals("minecraft:item_storage.default"), entry.getKey());
        check("one group", entry.getValue().size() == 1, entry.getValue().size());
        return entry.getValue().getFirst().views;
    }
}
