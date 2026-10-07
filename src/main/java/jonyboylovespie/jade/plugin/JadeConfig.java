package jonyboylovespie.jade.plugin;

import org.bukkit.configuration.file.FileConfiguration;

public record JadeConfig(int maxPositionDeviation, long requestIntervalNanos, boolean itemStorageEnabled) {
    public static JadeConfig load(FileConfiguration config) {
        return new JadeConfig(
                Math.max(0, config.getInt("max-position-deviation", 21)),
                Math.max(0L, config.getLong("request-interval-ms", 50L)) * 1_000_000L,
                config.getBoolean("item-storage.enabled", true));
    }
}
