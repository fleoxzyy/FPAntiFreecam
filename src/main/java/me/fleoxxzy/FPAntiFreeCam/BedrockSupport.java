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
        // Reset: these are static, so a /fpac reload that re-constructs this
        // class would otherwise keep stale "available" flags (and stale Method
        // handles pointing at an old plugin classloader) from the last load.
        geyserAvailable    = false;
        floodgateAvailable = false;
        geyserApi          = null;
        floodgateApi       = null;
        geyserIsBedrockMethod    = null;
        floodgateIsBedrockMethod = null;

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
            plugin.getLogger().info("[FPAntiFreeCam] Geyser/Floodgate not detected – Bedrock support disabled."
                    + " If Geyser runs on a proxy (BungeeCord/Velocity), install Floodgate on this"
                    + " server too so Bedrock players can be recognised.");
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

    /**
     * BUGFIX: this used to shrink the radius by one for Bedrock players.
     * That never reduced the real load (the per-second Smart Fill refreshes
     * were untouched) and it left the outermost ring of chunks un-refreshed,
     * so Bedrock players could keep stale masked/unmasked chunks at the edge
     * of their view. Instead, never refresh beyond what the Bedrock client
     * can actually see: chunks past its view distance are wasted re-sends
     * that Geyser still has to translate and push over the network.
     */
    public int optimisedRadius(Player player, int defaultRadius) {
        if (!isBedrock(player)) return defaultRadius;
        return Math.max(1, Math.min(defaultRadius, PlatformUtil.viewDistance(player)));
    }

    /**
     * Bedrock clients (via Geyser) rebuild every re-sent chunk from scratch
     * and silently drop chunks when they receive large bursts of them, which
     * shows up as chunks that randomly don't load. The continuous Smart Fill
     * refresh tasks are therefore throttled harder for Bedrock players.
     */
    public long smartFillRefreshCooldownMs(Player player, long defaultMs) {
        return isBedrock(player) ? Math.max(defaultMs, BEDROCK_SMART_FILL_COOLDOWN_MS) : defaultMs;
    }

    public int maxSightRefreshColumns(Player player, int defaultMax) {
        return isBedrock(player) ? Math.min(defaultMax, BEDROCK_MAX_SIGHT_REFRESH_COLUMNS) : defaultMax;
    }

    private static final long BEDROCK_SMART_FILL_COOLDOWN_MS     = 4000L;
    private static final int  BEDROCK_MAX_SIGHT_REFRESH_COLUMNS = 8;

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
