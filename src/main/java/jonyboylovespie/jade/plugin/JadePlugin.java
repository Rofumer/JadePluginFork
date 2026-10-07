package jonyboylovespie.jade.plugin;

import jonyboylovespie.jade.plugin.provider.ServerDataProviderManager;
import jonyboylovespie.jade.plugin.provider.universal.ItemStorageProvider;
import jonyboylovespie.jade.plugin.provider.vanilla.VanillaProviders;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

public final class JadePlugin extends JavaPlugin implements Listener {
    private static JadePlugin instance;
    private JadeNetwork network;
    private JadeConfig jadeConfig;

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();
        jadeConfig = JadeConfig.load(getConfig());

        ServerDataProviderManager.clear();
        VanillaProviders.register();
        if (jadeConfig.itemStorageEnabled()) {
            ItemStorageProvider.register();
        }

        network = new JadeNetwork(this);
        for (String channel : JadeNetwork.INCOMING_CHANNELS) {
            getServer().getMessenger().registerIncomingPluginChannel(this, channel, network);
        }
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("Jade Plugin enabled - accepting connections via plugin messaging");
    }

    @Override
    public void onDisable() {
        getServer().getMessenger().unregisterIncomingPluginChannel(this);
        instance = null;
        getLogger().info("Jade Plugin disabled");
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        network.removePlayer(event.getPlayer());
    }

    public static JadePlugin instance() {
        return instance;
    }

    public JadeNetwork network() {
        return network;
    }

    public JadeConfig jadeConfig() {
        return jadeConfig;
    }
}
