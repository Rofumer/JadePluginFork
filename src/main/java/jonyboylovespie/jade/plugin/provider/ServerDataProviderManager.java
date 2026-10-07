package jonyboylovespie.jade.plugin.provider;

import it.unimi.dsi.fastutil.ints.IntList;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import jonyboylovespie.jade.plugin.JadePlugin;
import net.minecraft.nbt.CompoundTag;

/**
 * Provider ids are sent to the client in the server handshake; the client then requests providers by their
 * index in these lists, so registration order matters.
 */
public final class ServerDataProviderManager {
    private static final List<String> blockProviderIds = new ArrayList<>();
    private static final List<ServerDataProvider<BlockAccessor>> blockProviders = new ArrayList<>();
    private static final List<String> entityProviderIds = new ArrayList<>();
    private static final List<ServerDataProvider<EntityAccessor>> entityProviders = new ArrayList<>();

    public static void clear() {
        blockProviderIds.clear();
        blockProviders.clear();
        entityProviderIds.clear();
        entityProviders.clear();
    }

    public static void registerBlockProvider(String id, ServerDataProvider<BlockAccessor> provider) {
        blockProviderIds.add(id);
        blockProviders.add(provider);
    }

    public static void registerEntityProvider(String id, ServerDataProvider<EntityAccessor> provider) {
        entityProviderIds.add(id);
        entityProviders.add(provider);
    }

    public static List<String> blockProviderIds() {
        return blockProviderIds;
    }

    public static List<String> entityProviderIds() {
        return entityProviderIds;
    }

    public static void handleBlockRequest(BlockAccessor accessor, IntList requestedIds) {
        handle(blockProviderIds, blockProviders, accessor, accessor.serverData(), requestedIds);
    }

    public static void handleEntityRequest(EntityAccessor accessor, IntList requestedIds) {
        handle(entityProviderIds, entityProviders, accessor, accessor.serverData(), requestedIds);
    }

    private static <A> void handle(List<String> ids, List<ServerDataProvider<A>> providers, A accessor, CompoundTag data, IntList requestedIds) {
        for (int i = 0; i < requestedIds.size(); i++) {
            int requestedId = requestedIds.getInt(i);
            if (requestedId < 0 || requestedId >= providers.size()) {
                continue;
            }
            try {
                providers.get(requestedId).appendServerData(ids.get(requestedId), data, accessor);
            } catch (Exception e) {
                JadePlugin.instance().getLogger().log(Level.WARNING, "Error from provider " + ids.get(requestedId), e);
            }
        }
    }

    private ServerDataProviderManager() {
    }
}
