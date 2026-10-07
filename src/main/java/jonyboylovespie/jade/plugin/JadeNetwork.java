package jonyboylovespie.jade.plugin;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import jonyboylovespie.jade.plugin.provider.BlockAccessor;
import jonyboylovespie.jade.plugin.provider.EntityAccessor;
import jonyboylovespie.jade.plugin.provider.ServerDataProviderManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.DiscardedPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.boss.enderdragon.EnderDragonPart;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.messaging.PluginMessageListener;

/**
 * Implements the server side of Jade's network protocol (snownee.jade.network). Payload layouts follow the
 * stream codecs of the corresponding Jade packets. Paper handles plugin messages on the main thread.
 */
public final class JadeNetwork implements PluginMessageListener {
    public static final String PROTOCOL_VERSION = "9";
    public static final String CLIENT_HANDSHAKE = "jade:client_handshake";
    public static final String REQUEST_BLOCK = "jade:request_block";
    public static final String REQUEST_ENTITY = "jade:request_entity";
    public static final List<String> INCOMING_CHANNELS = List.of(CLIENT_HANDSHAKE, REQUEST_BLOCK, REQUEST_ENTITY);
    private static final Identifier SERVER_HANDSHAKE = Identifier.parse("jade:server_handshake");
    private static final Identifier RECEIVE_DATA = Identifier.parse("jade:receive_data");

    private final JadePlugin plugin;
    private final Set<UUID> connectedPlayers = new HashSet<>();
    private final Map<UUID, Long> lastRequestNanos = new HashMap<>();

    public JadeNetwork(JadePlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        RegistryFriendlyByteBuf buf = JadeCodecs.wrap(message);
        try {
            ServerPlayer serverPlayer = ((CraftPlayer) player).getHandle();
            switch (channel) {
                case CLIENT_HANDSHAKE -> handleHandshake(serverPlayer, buf);
                case REQUEST_BLOCK -> handleBlockRequest(serverPlayer, buf);
                case REQUEST_ENTITY -> handleEntityRequest(serverPlayer, buf);
                default -> plugin.getLogger().log(Level.FINE, "Unknown Jade channel: {0}", channel);
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Error on " + channel + " from " + player.getName(), e);
        } finally {
            buf.release();
        }
    }

    private void handleHandshake(ServerPlayer player, RegistryFriendlyByteBuf buf) {
        String version = ByteBufCodecs.STRING_UTF8.decode(buf);
        if (!PROTOCOL_VERSION.equals(version)) {
            plugin.getLogger().warning("Jade protocol mismatch from " + player.getScoreboardName() + ": " + version + " (expected " + PROTOCOL_VERSION + ")");
            return;
        }
        connectedPlayers.add(player.getUUID());
        sendServerHandshake(player);
    }

    private void sendServerHandshake(ServerPlayer player) {
        send(player, SERVER_HANDSHAKE, encodeServerHandshake());
        plugin.getLogger().info("Jade handshake OK with " + player.getScoreboardName());
    }

    /** {@code ServerHandshakePacket}: server config map, shearable blocks, block provider ids, entity provider ids. */
    public static byte[] encodeServerHandshake() {
        RegistryFriendlyByteBuf buf = JadeCodecs.newBuffer();
        try {
            buf.writeVarInt(0); // server config overrides
            buf.writeVarInt(0); // shearable blocks
            writeIds(buf, ServerDataProviderManager.blockProviderIds());
            writeIds(buf, ServerDataProviderManager.entityProviderIds());
            return JadeCodecs.toByteArray(buf);
        } finally {
            buf.release();
        }
    }

    private static void writeIds(RegistryFriendlyByteBuf buf, List<String> ids) {
        buf.writeVarInt(ids.size());
        for (String id : ids) {
            Identifier.STREAM_CODEC.encode(buf, Identifier.parse(id));
        }
    }

    /** {@code RequestBlockPacket}: {@code BlockAccessorImpl.SyncData} followed by the requested provider ids. */
    public record BlockRequest(boolean showDetails, BlockHitResult hit, CompoundTag data, IntList providerIds) {
        public static BlockRequest decode(RegistryFriendlyByteBuf buf) {
            boolean showDetails = buf.readBoolean();
            BlockHitResult hit = BlockHitResult.STREAM_CODEC.decode(buf);
            ItemStack.OPTIONAL_STREAM_CODEC.decode(buf); // client-side representation of the block, unused
            CompoundTag data = ByteBufCodecs.COMPOUND_TAG.decode(buf);
            return new BlockRequest(showDetails, hit, data, readIds(buf));
        }
    }

    /** {@code RequestEntityPacket}: {@code EntityAccessorImpl.SyncData} followed by the requested provider ids. */
    public record EntityRequest(boolean showDetails, int entityId, CompoundTag data, IntList providerIds) {
        public static EntityRequest decode(RegistryFriendlyByteBuf buf) {
            boolean showDetails = buf.readBoolean();
            int entityId = buf.readVarInt();
            buf.readVarInt(); // part index
            ByteBufCodecs.VECTOR3F.decode(buf); // hit vector
            CompoundTag data = ByteBufCodecs.COMPOUND_TAG.decode(buf);
            return new EntityRequest(showDetails, entityId, data, readIds(buf));
        }
    }

    private void handleBlockRequest(ServerPlayer player, RegistryFriendlyByteBuf buf) {
        if (!acceptRequest(player)) {
            return;
        }
        BlockRequest request = BlockRequest.decode(buf);
        CompoundTag data = request.data();
        ServerLevel level = player.level();
        BlockPos pos = request.hit().getBlockPos();
        data.putInt("x", pos.getX());
        data.putInt("y", pos.getY());
        data.putInt("z", pos.getZ());
        if (isOutOfReach(player, pos, player.blockInteractionRange()) || !level.isLoaded(pos)) {
            sendReceiveData(player, data);
            return;
        }

        BlockState state = level.getBlockState(pos);
        BlockEntity blockEntity = state.hasBlockEntity() ? level.getBlockEntity(pos) : null;
        data.putString("BlockId", BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
        ServerDataProviderManager.handleBlockRequest(new BlockAccessor(player, level, pos, state, blockEntity, request.showDetails(), data), request.providerIds());
        sendReceiveData(player, data);
    }

    private void handleEntityRequest(ServerPlayer player, RegistryFriendlyByteBuf buf) {
        if (!acceptRequest(player)) {
            return;
        }
        EntityRequest request = EntityRequest.decode(buf);
        CompoundTag data = request.data();
        ServerLevel level = player.level();
        Entity entity = level.getEntity(request.entityId());
        if (entity instanceof EnderDragonPart part) {
            entity = part.parentMob;
        }
        if (entity == null) {
            return;
        }

        data.putInt("EntityId", entity.getId());
        if (isOutOfReach(player, entity.blockPosition(), player.entityInteractionRange()) || !canBeTarget(entity, player)) {
            sendReceiveData(player, data);
            return;
        }
        ServerDataProviderManager.handleEntityRequest(new EntityAccessor(player, level, entity, request.showDetails(), data), request.providerIds());
        sendReceiveData(player, data);
    }

    private static IntList readIds(RegistryFriendlyByteBuf buf) {
        int size = buf.readVarInt();
        if (size < 0 || size > 1024) {
            throw new IllegalArgumentException("Too many provider ids: " + size);
        }
        IntList ids = new IntArrayList(size);
        for (int i = 0; i < size; i++) {
            ids.add(buf.readVarInt());
        }
        return ids;
    }

    private boolean acceptRequest(ServerPlayer player) {
        if (!connectedPlayers.contains(player.getUUID())) {
            return false;
        }
        long now = System.nanoTime();
        Long last = lastRequestNanos.get(player.getUUID());
        if (last != null && now - last < plugin.jadeConfig().requestIntervalNanos()) {
            return false;
        }
        lastRequestNanos.put(player.getUUID(), now);
        return true;
    }

    private boolean isOutOfReach(ServerPlayer player, BlockPos pos, double baseReach) {
        double maxDistance = Mth.square(baseReach + plugin.jadeConfig().maxPositionDeviation());
        return pos.distSqr(player.blockPosition()) > maxDistance;
    }

    /** Mirrors {@code Jade.canBeTarget}. */
    private static boolean canBeTarget(Entity target, ServerPlayer viewer) {
        if (target.isRemoved() || target.isSpectator() || target == viewer.getVehicle()) {
            return false;
        }
        return !target.isInvisibleTo(viewer) || hasEquipment(target);
    }

    private static boolean hasEquipment(Entity entity) {
        if (entity instanceof LivingEntity living) {
            for (EquipmentSlot slot : EquipmentSlot.VALUES) {
                if (!living.getItemBySlot(slot).isEmpty()) {
                    return true;
                }
            }
        }
        return false;
    }

    public void sendReceiveData(ServerPlayer player, CompoundTag tag) {
        send(player, RECEIVE_DATA, encodeReceiveData(tag));
    }

    /** {@code ReceiveDataPacket}: a single compound tag. */
    public static byte[] encodeReceiveData(CompoundTag tag) {
        RegistryFriendlyByteBuf buf = JadeCodecs.newBuffer();
        try {
            ByteBufCodecs.COMPOUND_TAG.encode(buf, tag);
            return JadeCodecs.toByteArray(buf);
        } finally {
            buf.release();
        }
    }

    /**
     * Sends a raw custom payload. Unlike {@link Player#sendPluginMessage}, this does not depend on the client having
     * registered the channel through {@code minecraft:register}.
     */
    private static void send(ServerPlayer player, Identifier channel, byte[] payload) {
        if (player.connection != null) {
            player.connection.send(new ClientboundCustomPayloadPacket(new DiscardedPayload(channel, payload)));
        }
    }

    public void removePlayer(Player player) {
        connectedPlayers.remove(player.getUniqueId());
        lastRequestNanos.remove(player.getUniqueId());
    }
}
