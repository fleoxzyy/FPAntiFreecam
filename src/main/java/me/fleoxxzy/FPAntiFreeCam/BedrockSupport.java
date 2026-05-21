package me.fleoxxzy.FPAntiFreeCam;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Optional Geyser / Floodgate integration.
 * Detects bedrock players via reflection so the plugin compiles without
 * any Geyser/Floodgate API on the classpath.
 */
public final class BedrockSupport {

    private static boolean geyserAvailable    = false;
    private static boolean floodgateAvailable = false;
    private static Object  geyserApi          = null;
    private static Object  floodgateApi       = null;
    private static Method  geyserIsBedrockMethod    = null;
    private static Method  floodgateIsBedrockMethod = null;

    private final Plugin plugin;
    /** Cache: known bedrock player UUIDs for this session. */
    private final Set<UUID> bedrockCache = ConcurrentHashMap.newKeySet();

    public BedrockSupport(Plugin plugin) {
        this.plugin = plugin;
        init();
    }

    // ── Initialisation ────────────────────────────────────────────────────

    private void init() {
        // Try Geyser
        Plugin geyser = findPlugin("Geyser-Spigot", "Geyser");
        if (geyser != null && geyser.isEnabled()) {
            try {
                Class<?> api = Class.forName("org.geysermc.geyser.api.GeyserApi");
                geyserApi = api.getMethod("api").invoke(null);
                geyserIsBedrockMethod = api.getMethod("isBedrockPlayer", UUID.class);
                geyserAvailable = true;
                plugin.getLogger().info("[FPAntiFreeCam] Geyser support enabled.");
            } catch (Exception e) {
                plugin.getLogger().warning("[FPAntiFreeCam] Geyser found but API init failed: " + e.getMessage());
            }
        }

        // Try Floodgate
        Plugin floodgate = findPlugin("floodgate");
        if (floodgate != null && floodgate.isEnabled()) {
            try {
                Class<?> api = Class.forName("org.geysermc.floodgate.api.FloodgateApi");
                floodgateApi = api.getMethod("getInstance").invoke(null);
                floodgateIsBedrockMethod = api.getMethod("isFloodgatePlayer", UUID.class);
                floodgateAvailable = true;
                plugin.getLogger().info("[FPAntiFreeCam] Floodgate support enabled.");
            } catch (Exception e) {
                plugin.getLogger().warning("[FPAntiFreeCam] Floodgate found but API init failed: " + e.getMessage());
            }
        }

        if (!geyserAvailable && !floodgateAvailable) {
            plugin.getLogger().info("[FPAntiFreeCam] Geyser/Floodgate not detected – Bedrock support disabled.");
        }
    }

    private Plugin findPlugin(String... names) {
        for (String name : names) {
            Plugin p = Bukkit.getPluginManager().getPlugin(name);
            if (p != null) return p;
        }
        return null;
    }

    // ── Public API ────────────────────────────────────────────────────────

    public boolean isBedrock(Player player) {
        if (player == null) return false;
        return isBedrock(player.getUniqueId());
    }

    public boolean isBedrock(UUID id) {
        if (id == null) return false;
        if (bedrockCache.contains(id)) return true;
        boolean result = checkGeyser(id) || checkFloodgate(id);
        if (result) bedrockCache.add(id);
        return result;
    }

    /** Bedrock players get a slightly smaller chunk refresh radius to reduce lag. */
    public int optimisedRadius(Player player, int defaultRadius) {
        return isBedrock(player) ? Math.max(1, defaultRadius - 1) : defaultRadius;
    }

    public void cleanupPlayer(UUID id) {
        bedrockCache.remove(id);
    }

    public boolean isEnabled() {
        return geyserAvailable || floodgateAvailable;
    }

    public String statusLine() {
        return "Geyser: " + geyserAvailable
             + "  Floodgate: " + floodgateAvailable
             + "  Bedrock cached:" + bedrockCache.size();
    }

    /** Periodic cleanup: remove UUIDs whose players are no longer online. */
    public void periodicCleanup() {
        bedrockCache.removeIf(id -> {
            Player p = Bukkit.getPlayer(id);
            return p == null || !p.isOnline();
        });
    }

    // ── Private helpers ───────────────────────────────────────────────────

    private boolean checkGeyser(UUID id) {
        if (!geyserAvailable || geyserApi == null || geyserIsBedrockMethod == null) return false;
        try {
            return (Boolean) geyserIsBedrockMethod.invoke(geyserApi, id);
        } catch (Exception ignored) { return false; }
    }

    private boolean checkFloodgate(UUID id) {
        if (!floodgateAvailable || floodgateApi == null || floodgateIsBedrockMethod == null) return false;
        try {
            return (Boolean) floodgateIsBedrockMethod.invoke(floodgateApi, id);
        } catch (Exception ignored) { return false; }
    }
}
