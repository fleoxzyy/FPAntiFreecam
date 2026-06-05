package me.fleoxxzy.FPAntiFreeCam;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.PacketEventsAPI;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.protocol.world.states.WrappedBlockState;
import io.github.retrooper.packetevents.factory.spigot.SpigotPacketEventsBuilder;
import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.generator.WorldInfo;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.StringUtil;
import org.bukkit.util.Vector;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * FPAntiFreeCam – Main plugin class.
 *
 * Anti-FreeCam: when a player is at or above the configured surface-y
 * threshold, all blocks/entities at or below void-y are replaced with air
 * in outbound packets so FreeCam mods see only void.
 *
 * Compatible with Spigot / Paper / Folia, MC 1.19 through 26.1+.
 *
 * ── IMPROVEMENTS in this version ─────────────────────────────────────────
 *
 *  BUG FIX  onPlayerMove now always bypasses the refresh cooldown when
 *           turning protection OFF (player goes underground). Previously it
 *           respected the cooldown, causing void blocks to persist for up to
 *           refreshCooldownMs after going underground.
 *
 *  BUG FIX  handleBypass now checks fpantifreecam.admin instead of the
 *           bypass-player perm (which lets anyone with bypass run the cmd).
 *
 *  BUG FIX  Removed the dead isExposedToSky() method (defined but never
 *           used anywhere). Its logic is now folded into calculateRaycast().
 *
 *  NEW      Hysteresis band (protection.hysteresis-y): protection activates
 *           at protectionY but deactivates at (protectionY – hysteresisY).
 *           Prevents rapid chunk-refresh spam when a player jumps at the
 *           surface threshold.
 *
 *  NEW      Per-world voidY (protection.per-world-void-y): different worlds
 *           can have different underground floor depths. Falls back to the
 *           global void-y if a world-specific value is not set.
 *
 *  NEW      Toggle bypass command: /fpac bypass <player> now toggles the
 *           bypass ON/OFF and stores it in a runtime manualBypass set.
 *
 *  NEW      Action bar HUD (notifications.action-bar.enabled): sends a
 *           configurable message to players while protection is active.
 *
 *  NEW      Multi-directional raycast: the sky-visibility check now casts 5
 *           rays (vertical + 4 diagonals) for better cave-opening detection.
 *
 *  NEW      Freeze detection (anti-cheat.freeze-detection.*): warns admins
 *           when a player above protectionY hasn't moved for N seconds.
 *           Useful indicator for position-spoofing FreeCam clients.
 *
 *  NEW      EntityHider.periodicCleanup() is now called every 60 s to remove
 *           stale hidden-pair entries for despawned entities.
 */
public final class FPAntiFreeCam extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {

    // ── Singleton ─────────────────────────────────────────────────────────
    private static FPAntiFreeCam instance;
    public static FPAntiFreeCam getInstance() { return instance; }

    // ── Runtime player-state maps ─────────────────────────────────────────
    /** true = protection active; false = full view shown. */
    public final Map<UUID, Boolean> playerHiddenState         = new ConcurrentHashMap<>();
    private final Map<UUID, Long>   refreshCooldowns          = new ConcurrentHashMap<>();
    private final Map<UUID, Long>   raycastDeactivationPending = new ConcurrentHashMap<>();
    private final Set<UUID>         internallyTeleporting      = ConcurrentHashMap.newKeySet();

    /**
     * NEW: runtime manual-bypass set.
     * /fpac bypass <player> toggles inclusion here. Checked in isProtectionActive().
     */
    private final Set<UUID> manualBypass = ConcurrentHashMap.newKeySet();

    /**
     * NEW: freeze detection – tracks the last time a player had a meaningful
     * position change (≥0.5 blocks) while above protectionY.
     */
    private final Map<UUID, Long>     lastSignificantMoveMs = new ConcurrentHashMap<>();
    private final Map<UUID, Location> lastSignificantMoveLoc = new ConcurrentHashMap<>();

    // ── Stats counters ────────────────────────────────────────────────────
    public final AtomicLong totalPacketsProcessed = new AtomicLong();
    public final AtomicLong totalChunksModified   = new AtomicLong();
    public final AtomicLong totalBlocksReplaced   = new AtomicLong();
    private long enabledAt = 0;

    // ── Config-driven values ──────────────────────────────────────────────
    private WrappedBlockState replacementBlockState;
    private int               replacementBlockId   = 0;
    private String            replacementBlockType = "minecraft:air";

    private boolean debugMode         = false;
    private int     refreshCooldownMs = 3_000;

    /** Y level at-or-above which the hiding effect is ARMED. */
    private double protectionY = 64.0;

    /**
     * NEW: pie-chart protection toggle.
     * When true, tile entities and entity spawns below voidY are stripped/cancelled.
     */
    private boolean pieChartProtection = false;

    /**
     * NEW: Hysteresis gap below protectionY. Protection deactivates only
     * when the player drops below (protectionY – hysteresisY). This
     * prevents rapid on/off flicker when jumping at the surface boundary.
     */
    private double protectionHysteresisY = 2.0;

    /** Blocks at-or-below this Y are hidden while protection is active. */
    private int voidY = 15;

    /**
     * NEW: Per-world overrides for voidY.
     * Falls back to the global {@link #voidY} if a world is not listed.
     */
    private final Map<String, Integer> perWorldVoidY = new ConcurrentHashMap<>();

    private Set<String> protectedWorlds  = ConcurrentHashMap.newKeySet();

    private boolean limitedAreaEnabled  = false;
    private int     limitedAreaRadius   = 4;
    private boolean instantProtection       = true;
    private int     instantRadius           = 14;
    private int     preLoadDistance         = 10;
    private boolean forceImmediateRefresh   = true;

    // ── Raycast / deep-deactivation config ───────────────────────────────
    private boolean raycastEnabled    = true;
    private double  raycastMinUpward  = 0.15;
    private long    raycastDebounceMs = 500L;
    private double  deepDeactivationY = 20.0;

    /**
     * NEW: When true, the raycast is cast in 5 directions (straight up plus
     * the four diagonal NE/NW/SE/SW directions at ~45°) instead of just
     * one vertical ray. Catches caves with angled openings.
     */
    private boolean raycastMultiDirectional = true;

    // ── Action bar HUD ────────────────────────────────────────────────────
    /** NEW: optional HUD indicator for players while protection is active. */
    private boolean actionBarEnabled    = false;
    private String  actionBarActiveMsg  = "&c☠ FreeCam Protected";

    // ── Freeze detection ──────────────────────────────────────────────────
    /** NEW: warn admins if a player above protectionY hasn't moved for N seconds. */
    private boolean freezeDetectionEnabled = false;
    private int     freezeDetectionSeconds = 30;

    // ── Language config ───────────────────────────────────────────────────
    private FileConfiguration langConfig;
    private String            currentLanguage = "en";

    // ── Sub-systems ───────────────────────────────────────────────────────
    private FoliaScheduler    foliaScheduler;
    // Background tasks tracking
    private Object raycastTask;
    private Object actionBarTask;
    private Object entityHiderCleanupTask;

    // Core utilities
    private PaperScheduler paperScheduler;
    private BedrockSupport bedrockSupport;
    private EntityHider    entityHider;
    private UpdateChecker  updateChecker;

    // ═════════════════════════════════════════════════════════════════════
    //  JavaPlugin lifecycle
    // ═════════════════════════════════════════════════════════════════════

    @Override
    public void onLoad() {
        instance = this;
        getLogger().info("onLoad() – building PacketEvents…");
        PacketEvents.setAPI(SpigotPacketEventsBuilder.build(this));
        PacketEventsAPI<?> api = PacketEvents.getAPI();
        if (api == null) {
            getLogger().severe("[FPAntiFreeCam] PacketEvents API is null after build – aborting load.");
            return;
        }
        api.getSettings().checkForUpdates(false).bStats(true);
        api.load();
        if (!api.isLoaded()) {
            getLogger().severe("[FPAntiFreeCam] PacketEvents failed to load – plugin will be disabled.");
        }
    }

    @Override
    public void onEnable() {
        ChatUtil.printBanner(ChatUtil.startupBanner(
                getDescription().getVersion(), PlatformUtil.getPlatformName()));

        PacketEventsAPI<?> api = PacketEvents.getAPI();
        if (api == null || !api.isLoaded()) {
            getLogger().severe(lang("error.packetevents-unavailable"));
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        loadConfigValues();

        bedrockSupport = new BedrockSupport(this);
        entityHider    = new EntityHider(this);
        updateChecker  = new UpdateChecker(this);
        getServer().getPluginManager().registerEvents(updateChecker, this);

        if (FoliaScheduler.shouldUse()) {
            foliaScheduler = new FoliaScheduler(this);
            getLogger().info("[FPAntiFreeCam] Folia region-aware scheduler enabled.");
        } else {
            paperScheduler = new PaperScheduler(this);
            getLogger().info("[FPAntiFreeCam] Paper/Spigot tick-batching scheduler enabled.");
        }

        if (!initReplacementBlock()) {
            getLogger().severe(lang("error.block-state-null"));
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        api.getEventManager().registerListener(new ChunkListener(this), PacketListenerPriority.NORMAL);
        api.init();

        getServer().getPluginManager().registerEvents(this, this);
        registerCommands();

        enabledAt = System.currentTimeMillis();
        // Initialize tracking tasks
        startRaycastTask();
        startActionBarTask();
        startEntityHiderCleanupTask();

        if (getConfig().getBoolean("settings.update-checker", true)) updateChecker.check();

        // Handle already-online players (e.g. after /reload).
        try {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (isWorldProtected(p.getWorld().getName())) {
                    handlePlayerInitialState(p, false);
                }
            }
        } catch (Exception e) {
            getLogger().severe("[FPAntiFreeCam] Error scanning online players on enable: " + e.getMessage());
        }

        getLogger().info("[FPAntiFreeCam] Enabled. Protected worlds: " + protectedWorlds);
    }

    @Override
    public void onDisable() {
        ChatUtil.printBanner(ChatUtil.shutdownBanner());
        if (entityHider    != null) entityHider.refreshAll();
        if (paperScheduler != null) paperScheduler.shutdown();
        PacketEventsAPI<?> api = PacketEvents.getAPI();
        if (api != null && api.isLoaded()) api.terminate();
        playerHiddenState.clear();
        refreshCooldowns.clear();
        raycastDeactivationPending.clear();
        internallyTeleporting.clear();
        manualBypass.clear();
        lastSignificantMoveMs.clear();
        lastSignificantMoveLoc.clear();
    }

    // ═════════════════════════════════════════════════════════════════════
    //  Public API
    // ═════════════════════════════════════════════════════════════════════

    public boolean isWorldProtected(String worldName) {
        return worldName != null && protectedWorlds.contains(worldName);
    }

    public boolean hasBypass(Player player) {
        if (player == null) return false;
        if (player.getGameMode() == org.bukkit.GameMode.SPECTATOR) return true;
        if (player.hasPermission("fpantifreecam.bypass")) return true;
        return manualBypass.contains(player.getUniqueId());
    }

    public boolean isProtectionActive(Player player) {
        if (player == null) return false;
        if (hasBypass(player)) return false;
        boolean active = Boolean.TRUE.equals(playerHiddenState.get(player.getUniqueId()));
        if (active) dbg("Protection active for " + player.getName());
        return active;
    }

    public boolean isPieChartProtectionEnabled() { return pieChartProtection; }

    public WrappedBlockState getReplacementBlock() { return replacementBlockState; }
    public int getReplacementBlockId()             { return replacementBlockId;    }

    /**
     * Returns the global voidY (for backward compatibility).
     * Prefer {@link #getVoidY(String)} when a world name is available.
     */
    public int getVoidY() { return voidY; }

    /**
     * NEW: Returns the voidY for a specific world.
     * Falls back to the global voidY if no per-world override is configured.
     */
    public int getVoidY(String worldName) {
        if (worldName == null || worldName.isEmpty()) return voidY;
        return perWorldVoidY.getOrDefault(worldName, voidY);
    }

    public void dbg(String message) {
        if (debugMode) getLogger().info("[FPAntiFreeCam DEBUG] " + message);
    }

    // ── Stats incrementers ────────────────────────────────────────────────
    public void incrementPacketsProcessed() { totalPacketsProcessed.incrementAndGet(); }
    public void incrementChunksModified()   { totalChunksModified.incrementAndGet();   }
    public void addBlocksReplaced(long n)   { totalBlocksReplaced.addAndGet(n);        }

    // ═════════════════════════════════════════════════════════════════════
    //  Config & language loading
    // ═════════════════════════════════════════════════════════════════════

    private void loadConfigValues() {
        saveDefaultConfig();
        checkConfigVersion();
        reloadConfig();
        FileConfiguration cfg = getConfig();

        debugMode         = cfg.getBoolean("settings.debug-mode", false);
        refreshCooldownMs = cfg.getInt("settings.refresh-cooldown-seconds", 3) * 1_000;

        // Support both new key (protection-y) and legacy key (surface-y)
        if (cfg.contains("protection.protection-y")) {
            protectionY = cfg.getDouble("protection.protection-y", 64.0);
        } else {
            protectionY = cfg.getDouble("protection.surface-y", 64.0);
        }

        // NEW: hysteresis band
        protectionHysteresisY = cfg.getDouble("protection.hysteresis-y", 2.0);
        if (protectionHysteresisY < 0) protectionHysteresisY = 0;

        voidY = cfg.getInt("protection.void-y", 15);

        pieChartProtection = cfg.getBoolean("protection.pie-chart-protection", false);

        // NEW: per-world voidY overrides
        perWorldVoidY.clear();
        var perWorldSection = cfg.getConfigurationSection("protection.per-world-void-y");
        if (perWorldSection != null) {
            for (String worldName : perWorldSection.getKeys(false)) {
                perWorldVoidY.put(worldName, perWorldSection.getInt(worldName, voidY));
            }
        }

        String rawBlock = cfg.getString("replacement.block-type", "air");
        replacementBlockType = rawBlock.startsWith("minecraft:") ? rawBlock : "minecraft:" + rawBlock;

        protectedWorlds.clear();
        List<String> worldList = cfg.getStringList("worlds.list");
        if (worldList != null) protectedWorlds.addAll(worldList);

        limitedAreaEnabled    = cfg.getBoolean("performance.limited-area.enabled", false);
        limitedAreaRadius     = cfg.getInt("performance.limited-area.chunk-radius", 4);
        instantProtection     = cfg.getBoolean("performance.instant-protection.enabled", true);
        instantRadius         = cfg.getInt("performance.instant-protection.instant-load-radius", 14);
        preLoadDistance       = cfg.getInt("performance.instant-protection.pre-load-distance", 10);
        forceImmediateRefresh = cfg.getBoolean("performance.instant-protection.force-immediate-refresh", true);

        // Raycast settings
        raycastEnabled         = cfg.getBoolean("protection.raycast.enabled", true);
        raycastMinUpward       = cfg.getDouble("protection.raycast.min-upward-angle", 0.15);
        raycastDebounceMs      = cfg.getLong("protection.raycast.deactivation-debounce-ms", 500L);
        raycastMultiDirectional= cfg.getBoolean("protection.raycast.multi-directional", true);

        deepDeactivationY = cfg.getDouble("protection.deep-deactivation-y", 20.0);

        // NEW: action bar
        actionBarEnabled   = cfg.getBoolean("notifications.action-bar.enabled", false);
        actionBarActiveMsg = cfg.getString("notifications.action-bar.message-active",
                "&c☠ FreeCam Protected");

        // NEW: freeze detection
        freezeDetectionEnabled = cfg.getBoolean("anti-cheat.freeze-detection.enabled", false);
        freezeDetectionSeconds = cfg.getInt("anti-cheat.freeze-detection.seconds", 30);

        loadLanguageConfig(cfg.getString("settings.language", "en"));
        if (entityHider != null) entityHider.loadSettings();

        // Validate Y thresholds
        if (deepDeactivationY >= protectionY) {
            double clamped = protectionY - 5.0;
            getLogger().warning("[FPAntiFreeCam] deep-deactivation-y (" + deepDeactivationY
                    + ") must be < protection-y (" + protectionY + "). Clamping to " + clamped);
            deepDeactivationY = clamped;
        }
        if (deepDeactivationY <= voidY) {
            double clamped = voidY + 2.0;
            getLogger().warning("[FPAntiFreeCam] deep-deactivation-y (" + deepDeactivationY
                    + ") must be > void-y (" + voidY + "). Clamping to " + clamped);
            deepDeactivationY = clamped;
        }

        dbg("Config loaded – worlds=" + protectedWorlds
                + " protectionY=" + protectionY
                + " hysteresisY=" + protectionHysteresisY
                + " voidY=" + voidY
                + " deepDeactivationY=" + deepDeactivationY
                + " perWorldVoidY=" + perWorldVoidY
                + " block=" + replacementBlockType
                + " raycast=" + raycastEnabled
                + " multiDir=" + raycastMultiDirectional);
    }

    private void loadLanguageConfig(String language) {
        currentLanguage = language;
        File langDir  = new File(getDataFolder(), "lang");
        File langFile = new File(langDir, language + ".yml");
        if (!langDir.exists()) langDir.mkdirs();
        if (!langFile.exists()) {
            try { saveResource("lang/" + language + ".yml", false); }
            catch (Exception ignored) {
                try { saveResource("lang/en.yml", false); } catch (Exception ignored2) {}
            }
        }
        langConfig = YamlConfiguration.loadConfiguration(langFile);
        InputStream defStream = getResource("lang/" + language + ".yml");
        if (defStream == null) defStream = getResource("lang/en.yml");
        if (defStream != null) {
            YamlConfiguration defaults = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(defStream, StandardCharsets.UTF_8));
            langConfig.setDefaults(defaults);
        }
    }

    public String lang(String key, Object... args) {
        String msg = (langConfig != null)
                ? langConfig.getString("messages." + key, key)
                : key;
        for (int i = 0; i < args.length; i++) {
            msg = msg.replace("{" + i + "}", String.valueOf(args[i]));
        }
        return ChatUtil.color(msg);
    }

    private boolean initReplacementBlock() {
        String[] candidates = { replacementBlockType, "minecraft:air", "minecraft:stone", "minecraft:dirt" };
        for (String candidate : candidates) {
            try {
                WrappedBlockState state = WrappedBlockState.getByString(candidate);
                if (state != null) {
                    replacementBlockState = state;
                    replacementBlockId    = state.getGlobalId();
                    replacementBlockType  = candidate;
                    dbg("Replacement block → " + candidate + " (globalId=" + replacementBlockId + ")");
                    return true;
                }
            } catch (Exception e) {
                getLogger().warning("[FPAntiFreeCam] Block candidate '" + candidate + "' failed: " + e.getMessage());
            }
        }
        return false;
    }

    // ═════════════════════════════════════════════════════════════════════
    //  Player state management
    // ═════════════════════════════════════════════════════════════════════

    public void handlePlayerInitialState(Player player, boolean immediateRefresh) {
        if (!isWorldProtected(player.getWorld().getName())) {
            boolean wasCached = playerHiddenState.remove(player.getUniqueId()) != null;
            if (wasCached && immediateRefresh) refreshFullView(player);
            return;
        }

        boolean bypass     = hasBypass(player);
        boolean shouldHide = !bypass && player.getLocation().getY() >= protectionY;
        playerHiddenState.put(player.getUniqueId(), shouldHide);
        dbg("InitialState " + player.getName() + " hidden=" + shouldHide
                + " Y=" + String.format("%.1f", player.getLocation().getY()));

        if (entityHider != null) entityHider.updateFor(player);
        if (shouldHide || immediateRefresh) refreshFullView(player);
    }

    public void refreshFullView(Player player) {
        refreshFullView(player, false);
    }

    public void refreshFullView(Player player, boolean bypassCooldown) {
        long now = System.currentTimeMillis();
        if (!bypassCooldown) {
            long expiration = refreshCooldowns.getOrDefault(player.getUniqueId(), 0L);
            if (now < expiration) return;
        }

        int radius = Bukkit.getViewDistance();
        if (limitedAreaEnabled)    radius = Math.min(radius, limitedAreaRadius);
        if (bedrockSupport != null) radius = bedrockSupport.optimisedRadius(player, radius);

        dbg("refreshFullView → " + player.getName() + " radius=" + radius
                + " (bypass=" + bypassCooldown + ")");
        performRefresh(player, radius);
        if (!bypassCooldown) refreshCooldowns.put(player.getUniqueId(), now + refreshCooldownMs);
    }

    private void performRefresh(Player player, int radius) {
        if (!player.isOnline()) return;
        if (!isWorldProtected(player.getWorld().getName())) return;

        if (!PlatformUtil.isOwnedByCurrentRegion(player.getLocation())) {
            final int fr = radius;
            PlatformUtil.runTask(this, player.getLocation(), () -> performRefresh(player, fr));
            return;
        }

        if (foliaScheduler != null) {
            foliaScheduler.refreshChunks(player, radius);
        } else if (paperScheduler != null) {
            paperScheduler.refreshChunks(player, radius);
        } else {
            World world = player.getWorld();
            int   px    = player.getLocation().getBlockX() >> 4;
            int   pz    = player.getLocation().getBlockZ() >> 4;
            for (int cx = px - radius; cx <= px + radius; cx++) {
                for (int cz = pz - radius; cz <= pz + radius; cz++) {
                    try { world.refreshChunk(cx, cz); } catch (Exception ignored) {}
                }
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    //  Background tasks
    // ═════════════════════════════════════════════════════════════════════

    /** Raycast monitor: runs every 5 ticks (250 ms). */
    private void startRaycastTask() {
        if (!raycastEnabled) return;
        Runnable check = () -> {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (isWorldProtected(p.getWorld().getName()) && !hasBypass(p)) {
                    checkRaycastActivation(p);
                }
            }
        };
        raycastTask = PlatformUtil.runTaskTimer(this, check, 5L, 5L);
    }

    /**
     * NEW: Sends the action bar indicator to protected players every 40 ticks (2 s).
     * Only active when notifications.action-bar.enabled is true.
     */
    private void startActionBarTask() {
        if (!actionBarEnabled) return;
        Runnable tick = () -> {
            String msg = ChatUtil.color(actionBarActiveMsg);
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (isProtectionActive(p)) {
                    try {
                        p.sendActionBar(msg);
                    } catch (Exception ignored) {}
                }
            }
        };
        actionBarTask = PlatformUtil.runTaskTimer(this, tick, 40L, 40L);
    }

    /**
     * NEW: Periodic cleanup for EntityHider hidden-pair entries (every 60 s).
     * Removes stale entries for entities that have despawned.
     */
    private void startEntityHiderCleanupTask() {
        if (entityHider == null) return;
        entityHiderCleanupTask = PlatformUtil.runTaskTimer(this, () -> {
            if (entityHider != null) entityHider.periodicCleanup();
            if (bedrockSupport != null) bedrockSupport.periodicCleanup();
        }, 1200L, 1200L); // every 60 s
    }

    private void cancelTask(Object task) {
        if (task == null) return;
        try {
            if (task instanceof org.bukkit.scheduler.BukkitTask) {
                ((org.bukkit.scheduler.BukkitTask) task).cancel();
            } else {
                task.getClass().getMethod("cancel").invoke(task);
            }
        } catch (Exception ignored) {}
    }

    private void cancelBackgroundTasks() {
        cancelTask(raycastTask);
        cancelTask(actionBarTask);
        cancelTask(entityHiderCleanupTask);
        raycastTask = null;
        actionBarTask = null;
        entityHiderCleanupTask = null;
    }

    // ═════════════════════════════════════════════════════════════════════
    //  Raycast activation
    // ═════════════════════════════════════════════════════════════════════

    private void checkRaycastActivation(Player player) {
        double feetY = player.getLocation().getY();
        UUID   id    = player.getUniqueId();

        boolean currentHidden = Boolean.TRUE.equals(playerHiddenState.getOrDefault(id, false));
        boolean targetHidden;

        if (feetY >= protectionY) {
            targetHidden = true;
        } else if (feetY < deepDeactivationY) {
            targetHidden = false;
        } else {
            targetHidden = calculateRaycast(player);
        }

        if (targetHidden == currentHidden) {
            raycastDeactivationPending.remove(id);
            return;
        }

        dbg("State mismatch " + player.getName() + " current=" + currentHidden
                + " target=" + targetHidden + " Y=" + feetY);

        long now          = System.currentTimeMillis();
        long pendingStart = raycastDeactivationPending.getOrDefault(id, 0L);
        if (pendingStart == 0L) {
            raycastDeactivationPending.put(id, now);
            return;
        }

        long debounce = targetHidden ? 100L : raycastDebounceMs;
        if (now - pendingStart < debounce) return;

        raycastDeactivationPending.remove(id);
        playerHiddenState.put(id, targetHidden);
        if (entityHider != null) entityHider.updateFor(player);
        refreshFullView(player, !targetHidden); // bypass cooldown when turning OFF

        dbg("Layer transition " + (targetHidden ? "ON" : "OFF") + " for "
                + player.getName() + " at Y=" + String.format("%.1f", feetY));
    }

    /**
     * Returns true if protection should be armed for a player in the raycast zone
     * (deepDeactivationY ≤ feetY < protectionY).
     *
     * <p>Condition 1 – Look-direction upward: FreeCam can angle the camera upward
     * through the surface even while the player's feet are in a cave.
     *
     * <p>Condition 2 – Sky visibility: if the cave has a clear vertical (or
     * diagonal, when multi-directional mode is enabled) path to protectionY,
     * FreeCam can exploit that opening.
     *
     * NEW: multi-directional ray option: in addition to straight-up, casts 4
     * diagonal rays (NE/NW/SE/SW at ~45°). Any clear path arms protection.
     */
    private boolean calculateRaycast(Player player) {
        // Condition 1: camera pointing meaningfully upward
        Vector dir = player.getEyeLocation().getDirection();
        if (dir.getY() > raycastMinUpward) return true;

        // Condition 2: unobstructed path to sky
        try {
            Location eye    = player.getEyeLocation();
            double   distUp = protectionY - eye.getY();
            if (distUp <= 0.5) return true; // already at surface, unobstructed

            // Vertical ray (always checked)
            RayTraceResult rt = player.getWorld().rayTraceBlocks(
                    eye, new Vector(0, 1, 0), distUp,
                    FluidCollisionMode.NEVER, true);
            if (rt == null) return true; // clear vertical path – arm protection

            // NEW: additional diagonal rays when multi-directional is enabled
            if (raycastMultiDirectional) {
                double diagDist = distUp * 1.41; // ~√2 scale for 45° diagonals
                Vector[] diagonals = {
                    new Vector( 0.5, 1,  0.5).normalize(),
                    new Vector(-0.5, 1,  0.5).normalize(),
                    new Vector( 0.5, 1, -0.5).normalize(),
                    new Vector(-0.5, 1, -0.5).normalize()
                };
                for (Vector diagDir : diagonals) {
                    RayTraceResult diagRt = player.getWorld().rayTraceBlocks(
                            eye, diagDir, diagDist,
                            FluidCollisionMode.NEVER, true);
                    if (diagRt == null) {
                        dbg("Multi-dir raycast: diagonal opening detected for " + player.getName());
                        return true; // diagonal path to sky – arm protection
                    }
                }
            }
        } catch (Exception ignored) {}

        return false;
    }

    // ═════════════════════════════════════════════════════════════════════
    //  Bukkit event handlers
    // ═════════════════════════════════════════════════════════════════════

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        dbg("onPlayerJoin: " + player.getName() + " world=" + player.getWorld().getName());
        if (isWorldProtected(player.getWorld().getName())) {
            handlePlayerInitialState(player, false);
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        playerHiddenState.remove(id);
        refreshCooldowns.remove(id);
        raycastDeactivationPending.remove(id);
        internallyTeleporting.remove(id);
        lastSignificantMoveMs.remove(id);
        lastSignificantMoveLoc.remove(id);
        if (foliaScheduler != null) foliaScheduler.cleanupPlayer(id);
        if (paperScheduler != null) paperScheduler.cleanupPlayer(id);
        if (bedrockSupport  != null) bedrockSupport.cleanupPlayer(id);
        if (entityHider     != null) entityHider.cleanupPlayer(id);
        dbg("Cleaned up quit player: " + event.getPlayer().getName());
    }

    @EventHandler
    public void onPlayerChangedWorld(PlayerChangedWorldEvent event) {
        Player player  = event.getPlayer();
        String toWorld = player.getWorld().getName();
        dbg("WorldChange: " + player.getName() + " → " + toWorld);
        if (isWorldProtected(toWorld)) {
            handlePlayerInitialState(player, true);
        } else {
            boolean wasHidden = playerHiddenState.remove(player.getUniqueId()) != null;
            if (wasHidden) refreshFullView(player);
        }
    }

    // Duplicate onPlayerGameModeChange removed. Replaced by onGameModeChange (MONITOR) below.
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        Player player = event.getPlayer();
        if (internallyTeleporting.contains(player.getUniqueId())) return;

        var to   = event.getTo();
        var from = event.getFrom();
        if (to == null) return;

        boolean toProtected   = isWorldProtected(to.getWorld().getName());
        boolean fromProtected = isWorldProtected(from.getWorld().getName());

        if (!toProtected) {
            if (fromProtected && playerHiddenState.remove(player.getUniqueId()) != null) {
                final var dest = to;
                PlatformUtil.runTask(this, dest, () -> {
                    if (player.isOnline()) refreshFullView(player);
                });
            }
            return;
        }

        UUID    id        = player.getUniqueId();
        boolean bypass    = hasBypass(player);
        boolean oldHidden = Boolean.TRUE.equals(playerHiddenState.getOrDefault(id, to.getY() >= protectionY));
        boolean newHidden = !bypass && to.getY() >= protectionY;

        if (oldHidden == newHidden) return;

        if (!newHidden) {
            event.setCancelled(true);
            final var dest = to;
            PlatformUtil.runTask(this, dest, () -> {
                if (!player.isOnline()) return;
                playerHiddenState.put(id, false);
                internallyTeleporting.add(id);
                try {
                    player.teleport(dest);
                    refreshFullView(player);
                } finally {
                    internallyTeleporting.remove(id);
                }
            });
        } else {
            event.setCancelled(true);
            final var dest = to;
            PlatformUtil.runTask(this, dest, () -> {
                if (!player.isOnline()) return;
                playerHiddenState.put(id, true);
                if (entityHider != null) entityHider.updateFor(player);
                internallyTeleporting.add(id);
                try {
                    player.teleport(dest);
                    refreshFullView(player);
                } finally {
                    internallyTeleporting.remove(id);
                }
            });
        }
    }

    /**
     * Detects Y-level transitions across the surface-y threshold.
     *
     * <p>IMPROVEMENT – Hysteresis: protection activates at Y ≥ protectionY but
     * deactivates only at Y < (protectionY – hysteresisY). This prevents
     * chunk-refresh spam when the player jumps at the boundary.
     *
     * <p>BUGFIX – Cooldown bypass: when turning OFF protection (player goes
     * underground) we now always bypass the refresh cooldown so the void effect
     * is removed immediately rather than after up to refreshCooldownMs ms.
     *
     * <p>NEW – Freeze detection: if the player is above protectionY and has not
     * made a significant movement in freezeDetectionSeconds, a console warning
     * is logged (useful for spotting position-spoofing FreeCam clients).
     */
    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        var to   = event.getTo();
        var from = event.getFrom();
        if (to == null) return;

        Player player    = event.getPlayer();
        String worldName = player.getWorld().getName();

        if (!isWorldProtected(worldName)) {
            if (playerHiddenState.remove(player.getUniqueId()) != null) {
                refreshFullView(player, true);
            }
            return;
        }

        // Fast-path: skip if player hasn't crossed a block boundary on Y.
        if (from.getBlockY() == to.getBlockY()) {
            // Still run freeze detection even if Y hasn't changed
            if (freezeDetectionEnabled) checkFreezeDetection(player, to);
            return;
        }

        UUID    id     = player.getUniqueId();
        boolean bypass = hasBypass(player);

        // Update freeze-detection tracking
        if (freezeDetectionEnabled) {
            Location last = lastSignificantMoveLoc.get(id);
            if (last == null || to.distanceSquared(last) >= 0.25) { // 0.5 block threshold
                lastSignificantMoveMs.put(id, System.currentTimeMillis());
                lastSignificantMoveLoc.put(id, to.clone());
            }
        }

        // Layer 3: at/above protectionY → always ON
        // Layer 1: below deepDeactivationY → always OFF
        // Layer 2: raycast zone → deferred to raycast task (do not touch here)
        boolean newHidden;
        if (bypass) {
            newHidden = false;
        } else if (to.getY() >= protectionY) {
            newHidden = true;   // Layer 3: surface
        } else if (to.getY() < deepDeactivationY) {
            newHidden = false;  // Layer 1: deep underground
        } else {
            // Layer 2: raycast zone – let the raycast task decide
            return;
        }

        // IMPROVEMENT: apply hysteresis to avoid flicker at the boundary.
        // When currently hiding and moving downward into the deactivation zone,
        // only deactivate if the player has dropped hysteresisY below protectionY.
        if (!newHidden && Boolean.TRUE.equals(playerHiddenState.getOrDefault(id, false))) {
            if (to.getY() >= (protectionY - protectionHysteresisY)) {
                dbg("Hysteresis hold for " + player.getName()
                        + " (Y=" + String.format("%.1f", to.getY()) + ")");
                return; // still within the hysteresis band – stay hidden
            }
        }

        if (!playerHiddenState.containsKey(id)) {
            handlePlayerInitialState(player, true);
            return;
        }
        boolean oldHidden = Boolean.TRUE.equals(playerHiddenState.get(id));

        if (!newHidden) raycastDeactivationPending.remove(id);
        if (newHidden == oldHidden) return;

        dbg("Move transition " + player.getName() + ": " + oldHidden
                + " → " + newHidden + " at Y=" + to.getY());
        playerHiddenState.put(id, newHidden);
        if (entityHider != null) entityHider.updateFor(player);

        if (!newHidden) {
            // BUGFIX: bypass cooldown when revealing – never leave player seeing void
            refreshFullView(player, true);
        } else {
            long now        = System.currentTimeMillis();
            long expiration = refreshCooldowns.getOrDefault(id, 0L);
            if (now < expiration) {
                dbg("Refresh cooldown active for " + player.getName()
                        + " (expires in " + (expiration - now) + " ms)");
                return;
            }

            int radius = Bukkit.getViewDistance();
            if (instantProtection && forceImmediateRefresh
                    && to.getY() <= protectionY + preLoadDistance) {
                radius = Math.max(radius, instantRadius);
                dbg("Instant-protection radius=" + radius + " for " + player.getName());
            }
            if (limitedAreaEnabled)     radius = Math.min(radius, limitedAreaRadius);
            if (bedrockSupport != null) radius = bedrockSupport.optimisedRadius(player, radius);

            final int fr = radius;
            performRefresh(player, fr);
            refreshCooldowns.put(id, now + refreshCooldownMs);
        }
    }

    /**
     * NEW: Freeze detection helper. Logs a warning if a protected player
     * hasn't moved significantly in freezeDetectionSeconds seconds.
     * Possible sign of position-spoofing FreeCam.
     */
    private void checkFreezeDetection(Player player, Location current) {
        if (!isProtectionActive(player)) return;
        UUID id = player.getUniqueId();

        long lastMove = lastSignificantMoveMs.getOrDefault(id, System.currentTimeMillis());
        long elapsed  = (System.currentTimeMillis() - lastMove) / 1_000L;

        if (elapsed >= freezeDetectionSeconds) {
            getLogger().warning("[FPAntiFreeCam] FREEZE ALERT: " + player.getName()
                    + " has not moved for " + elapsed + "s while above protectionY "
                    + "(Y=" + String.format("%.1f", current.getY()) + "). "
                    + "Possible position-spoofing FreeCam.");
            // Reset to avoid repeated alerts
            lastSignificantMoveMs.put(id, System.currentTimeMillis());
            lastSignificantMoveLoc.put(id, current.clone());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGameModeChange(org.bukkit.event.player.PlayerGameModeChangeEvent event) {
        Player player = event.getPlayer();
        if (!isWorldProtected(player.getWorld().getName())) return;
        
        boolean willBypass = (event.getNewGameMode() == org.bukkit.GameMode.SPECTATOR) 
                          || player.hasPermission("fpantifreecam.bypass") 
                          || manualBypass.contains(player.getUniqueId());
        
        UUID id = player.getUniqueId();
        boolean currentHidden = Boolean.TRUE.equals(playerHiddenState.get(id));
        
        if (willBypass && currentHidden) {
            playerHiddenState.put(id, false);
            PlatformUtil.runTask(this, player.getLocation(), () -> {
                if (player.isOnline()) refreshFullView(player, true);
            });
        } else if (!willBypass && !currentHidden && player.getLocation().getY() >= protectionY) {
            playerHiddenState.put(id, true);
            if (entityHider != null) entityHider.updateFor(player);
            PlatformUtil.runTask(this, player.getLocation(), () -> {
                if (player.isOnline()) refreshFullView(player); // refresh view to hide
            });
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onVehicleEnter(org.bukkit.event.vehicle.VehicleEnterEvent event) {
        if (!(event.getEntered() instanceof Player)) return;
        Player player = (Player) event.getEntered();
        checkVehicleStateChange(player);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onVehicleExit(org.bukkit.event.vehicle.VehicleExitEvent event) {
        if (!(event.getExited() instanceof Player)) return;
        Player player = (Player) event.getExited();
        // Delay check to let the player's position settle after dismounting
        PlatformUtil.runTaskLater(this, () -> {
            if (player.isOnline()) checkVehicleStateChange(player);
        }, 1L);
    }

    private void checkVehicleStateChange(Player player) {
        if (!isWorldProtected(player.getWorld().getName())) return;
        boolean bypass = hasBypass(player);
        UUID id = player.getUniqueId();
        boolean currentHidden = Boolean.TRUE.equals(playerHiddenState.get(id));
        boolean targetHidden = !bypass && player.getLocation().getY() >= protectionY;

        if (currentHidden != targetHidden) {
            playerHiddenState.put(id, targetHidden);
            if (targetHidden && entityHider != null) {
                entityHider.updateFor(player);
            }
            PlatformUtil.runTask(this, player.getLocation(), () -> {
                if (!player.isOnline()) return;
                if (targetHidden) {
                    refreshFullView(player);
                } else {
                    refreshFullView(player, true);
                }
            });
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    //  Command system
    // ═════════════════════════════════════════════════════════════════════

    private void registerCommands() {
        for (String cmdName : new String[]{ "fpac", "fpreload", "fpdebug" }) {
            var cmd = getCommand(cmdName);
            if (cmd != null) { cmd.setExecutor(this); cmd.setTabCompleter(this); }
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        return switch (command.getName().toLowerCase()) {
            case "fpac"     -> handleMain(sender, args);
            case "fpreload" -> handleReload(sender);
            case "fpdebug"  -> handleDebug(sender);
            default         -> false;
        };
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("fpantifreecam.admin")) return Collections.emptyList();
        if (command.getName().equalsIgnoreCase("fpac")) {
            if (args.length == 1) {
                return StringUtil.copyPartialMatches(args[0],
                        Arrays.asList("reload", "debug", "world", "stats", "bypass", "help"),
                        new ArrayList<>());
            }
            if (args.length == 2) {
                if (args[0].equalsIgnoreCase("world")) {
                    return StringUtil.copyPartialMatches(args[1],
                            Arrays.asList("list", "add", "remove"), new ArrayList<>());
                }
                if (args[0].equalsIgnoreCase("bypass")) {
                    return Bukkit.getOnlinePlayers().stream()
                            .map(Player::getName)
                            .filter(n -> StringUtil.startsWithIgnoreCase(n, args[1]))
                            .collect(Collectors.toList());
                }
            }
            if (args.length == 3 && args[0].equalsIgnoreCase("world")) {
                List<String> worldNames = Bukkit.getWorlds().stream()
                        .map(WorldInfo::getName).collect(Collectors.toList());
                if (args[1].equalsIgnoreCase("remove")) {
                    return StringUtil.copyPartialMatches(args[2],
                            new ArrayList<>(protectedWorlds), new ArrayList<>());
                }
                return StringUtil.copyPartialMatches(args[2], worldNames, new ArrayList<>());
            }
        }
        return Collections.emptyList();
    }

    // ── Sub-command handlers ──────────────────────────────────────────────

    private boolean handleMain(CommandSender sender, String[] args) {
        if (!sender.hasPermission("fpantifreecam.admin")) {
            sender.sendMessage(lang("no-permission")); return true;
        }
        if (args.length == 0) return handleHelp(sender);
        String   sub     = args[0].toLowerCase();
        String[] subArgs = Arrays.copyOfRange(args, 1, args.length);
        return switch (sub) {
            case "reload" -> handleReload(sender);
            case "debug"  -> handleDebug(sender);
            case "world"  -> handleWorld(sender, subArgs);
            case "stats"  -> handleStats(sender);
            case "bypass" -> handleBypass(sender, subArgs);
            case "help"   -> handleHelp(sender);
            default -> {
                ChatUtil.sendError(sender, "Unknown sub-command. Use /fpac help.");
                yield true;
            }
        };
    }

    private boolean handleReload(CommandSender sender) {
        if (!sender.hasPermission("fpantifreecam.reload")) {
            sender.sendMessage(lang("no-permission")); return true;
        }
        cancelBackgroundTasks();
        loadConfigValues();
        initReplacementBlock();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (isWorldProtected(p.getWorld().getName())) {
                handlePlayerInitialState(p, true);
            } else if (playerHiddenState.remove(p.getUniqueId()) != null) {
                refreshFullView(p);
            }
        }
        startRaycastTask();
        startActionBarTask();
        startEntityHiderCleanupTask();
        ChatUtil.sendSuccess(sender, lang("reload-success", String.join(", ", protectedWorlds)));
        return true;
    }

    private boolean handleDebug(CommandSender sender) {
        if (!sender.hasPermission("fpantifreecam.debug")) {
            sender.sendMessage(lang("no-permission")); return true;
        }
        debugMode = !debugMode;
        getConfig().set("settings.debug-mode", debugMode);
        saveConfig();
        ChatUtil.sendSuccess(sender, debugMode ? lang("debug-on") : lang("debug-off"));
        getLogger().info("[FPAntiFreeCam] Debug mode " + (debugMode ? "ENABLED" : "DISABLED")
                + " by " + sender.getName());
        return true;
    }

    private boolean handleWorld(CommandSender sender, String[] args) {
        if (!sender.hasPermission("fpantifreecam.world")) {
            sender.sendMessage(lang("no-permission")); return true;
        }
        String sub = args.length > 0 ? args[0].toLowerCase() : "list";
        if (sub.equals("list")) {
            if (protectedWorlds.isEmpty()) ChatUtil.sendWarn(sender, lang("world-list-empty"));
            else {
                ChatUtil.sendInfo(sender, lang("world-list-header"));
                protectedWorlds.forEach(w -> ChatUtil.sendInfo(sender, lang("world-list-entry", w)));
            }
            return true;
        }
        if (args.length < 2) {
            ChatUtil.sendError(sender, "Usage: /fpac world <list|add|remove> [name]");
            return true;
        }
        String worldName = args[1];
        if (sub.equals("add")) {
            if (Bukkit.getWorld(worldName) == null) {
                ChatUtil.sendError(sender, lang("error.world-not-found", worldName)); return true;
            }
            if (!protectedWorlds.add(worldName)) {
                ChatUtil.sendWarn(sender, lang("world-exists", worldName)); return true;
            }
            saveWorldList();
            ChatUtil.sendSuccess(sender, lang("world-added", worldName));
            Bukkit.getOnlinePlayers().stream()
                    .filter(p -> p.getWorld().getName().equals(worldName))
                    .forEach(p -> handlePlayerInitialState(p, true));
        } else if (sub.equals("remove")) {
            if (!protectedWorlds.remove(worldName)) {
                ChatUtil.sendWarn(sender, lang("world-missing", worldName)); return true;
            }
            saveWorldList();
            ChatUtil.sendSuccess(sender, lang("world-removed", worldName));
            Bukkit.getOnlinePlayers().stream()
                    .filter(p -> p.getWorld().getName().equals(worldName))
                    .forEach(p -> {
                        playerHiddenState.remove(p.getUniqueId());
                        refreshFullView(p);
                    });
        } else {
            ChatUtil.sendError(sender, "Unknown action. Use: list, add, remove.");
        }
        return true;
    }

    private boolean handleStats(CommandSender sender) {
        if (!sender.hasPermission("fpantifreecam.admin")) {
            sender.sendMessage(lang("no-permission")); return true;
        }

        long activeCount  = playerHiddenState.values().stream().filter(b -> b).count();
        long onlineCount  = Bukkit.getOnlinePlayers().size();
        long bypassCount  = manualBypass.size();

        String tpsStr;
        try {
            double[] tps = Bukkit.getServer().getTPS();
            double t1 = Math.min(tps[0], 20.0), t5 = Math.min(tps[1], 20.0), t15 = Math.min(tps[2], 20.0);
            tpsStr = String.format("%s%.2f &7/ %s%.2f &7/ %s%.2f &8(1m/5m/15m)",
                    t1  >= 18 ? "&a" : t1  >= 15 ? "&e" : "&c", t1,
                    t5  >= 18 ? "&a" : t5  >= 15 ? "&e" : "&c", t5,
                    t15 >= 18 ? "&a" : t15 >= 15 ? "&e" : "&c", t15);
        } catch (Exception e) { tpsStr = "&7N/A"; }

        Runtime rt    = Runtime.getRuntime();
        long usedMB   = (rt.totalMemory() - rt.freeMemory()) / 1_048_576L;
        long maxMB    = rt.maxMemory() / 1_048_576L;
        int  memPct   = (int)(usedMB * 100L / Math.max(maxMB, 1L));
        String memC   = memPct >= 90 ? "&c" : memPct >= 70 ? "&e" : "&a";
        String memStr = memC + usedMB + " MB &7/ &f" + maxMB + " MB &8(" + memPct + "%)";

        long upSec  = (System.currentTimeMillis() - enabledAt) / 1_000L;
        long upMin  = upSec / 60L; upSec %= 60L;
        long upHour = upMin / 60L; upMin %= 60L;
        String uptimeStr = String.format("&e%dh &e%dm &e%ds", upHour, upMin, upSec);

        String sep = "&8" + "─".repeat(46);
        ChatUtil.send(sender, sep);
        ChatUtil.send(sender, "&b&l  FPAntiFreeCam &fv" + getDescription().getVersion()
                + "  &8— &7Runtime Statistics");
        ChatUtil.send(sender, sep);
        ChatUtil.send(sender, " &7Platform    &8: &f" + PlatformUtil.getPlatformName());
        ChatUtil.send(sender, " &7Uptime      &8: " + uptimeStr);
        ChatUtil.send(sender, " &7TPS         &8: " + tpsStr);
        ChatUtil.send(sender, " &7Memory      &8: " + memStr);
        ChatUtil.send(sender, sep);
        ChatUtil.send(sender, " &7Protected   &8: &a" + activeCount
                + " &7active &8/ &f" + onlineCount + " &7online &8/ &e" + bypassCount + " &7bypassed");
        ChatUtil.send(sender, " &7Worlds      &8: &a" + protectedWorlds.size()
                + " &8— &7" + protectedWorlds);
        ChatUtil.send(sender, " &7ProtectionY &8: &e" + protectionY
                + "  &7HysteresisY &8: &e" + protectionHysteresisY
                + "  &7VoidY &8: &e" + voidY
                + "  &7DeepDeactY &8: &e" + deepDeactivationY
                + "  &7PieChartProt &8: " + (pieChartProtection ? "&aON" : "&7OFF"));
        if (!perWorldVoidY.isEmpty())
            ChatUtil.send(sender, " &7PerWorldY   &8: &f" + perWorldVoidY);
        ChatUtil.send(sender, " &7Block       &8: &f" + replacementBlockType
                + " &8(id=" + replacementBlockId + ")");
        ChatUtil.send(sender, " &7Raycast     &8: " + (raycastEnabled
                ? "&aON &8| &7zone &8= &e" + (int)deepDeactivationY + "&8-&e" + (int)protectionY
                + " &8| &7minUpY &8= &e" + raycastMinUpward
                + " &8| &7debounce &8= &e" + raycastDebounceMs + "ms"
                + " &8| &7multiDir &8= " + (raycastMultiDirectional ? "&aON" : "&cOFF")
                : "&cOFF"));
        ChatUtil.send(sender, " &7ActionBar   &8: " + (actionBarEnabled ? "&aON" : "&7OFF"));
        ChatUtil.send(sender, " &7FreezeDetect&8: " + (freezeDetectionEnabled
                ? "&aON &8(threshold=" + freezeDetectionSeconds + "s)" : "&7OFF"));
        ChatUtil.send(sender, sep);
        ChatUtil.send(sender, " &7Pkts proc   &8: &e" + totalPacketsProcessed.get());
        ChatUtil.send(sender, " &7Chunks mod  &8: &e" + totalChunksModified.get());
        ChatUtil.send(sender, " &7Blocks rep  &8: &e" + totalBlocksReplaced.get());
        ChatUtil.send(sender, " &7Cooldown    &8: &a" + (refreshCooldownMs / 1_000)
                + "s  &7Debug &8: " + (debugMode ? "&aON" : "&7OFF"));
        if (bedrockSupport  != null) ChatUtil.send(sender, " &7Bedrock     &8: &a" + bedrockSupport.statusLine());
        if (entityHider     != null) ChatUtil.send(sender, " &7EntityHider &8: &a" + entityHider.stats());
        if (foliaScheduler  != null) ChatUtil.send(sender, " &7Folia Sched &8: &a" + foliaScheduler.stats());
        if (paperScheduler  != null) ChatUtil.send(sender, " &7Paper Sched &8: &a" + paperScheduler.stats());
        ChatUtil.send(sender, sep);
        return true;
    }

    /**
     * BUGFIX + IMPROVEMENT: /fpac bypass <player> now TOGGLES the bypass and
     * uses fpantifreecam.admin permission (not fpantifreecam.bypass, which
     * would allow bypass-holders to run the admin command themselves).
     */
    private boolean handleBypass(CommandSender sender, String[] args) {
        if (!sender.hasPermission("fpantifreecam.admin")) {
            sender.sendMessage(lang("no-permission")); return true;
        }
        if (args.length == 0) {
            ChatUtil.sendError(sender, "Usage: /fpac bypass <player>"); return true;
        }
        Player target = Bukkit.getPlayer(args[0]);
        if (target == null) {
            ChatUtil.sendError(sender, lang("bypass-unknown", args[0])); return true;
        }

        UUID tid = target.getUniqueId();
        if (manualBypass.contains(tid)) {
            // Revoke bypass
            manualBypass.remove(tid);
            playerHiddenState.remove(tid);
            handlePlayerInitialState(target, true);
            ChatUtil.sendSuccess(sender, lang("bypass-revoked", target.getName()));
            dbg("Bypass REVOKED for " + target.getName() + " by " + sender.getName());
        } else {
            // Grant bypass
            manualBypass.add(tid);
            playerHiddenState.put(tid, false);
            if (entityHider != null) entityHider.updateFor(target);
            refreshFullView(target, true);
            ChatUtil.sendSuccess(sender, lang("bypass-granted", target.getName()));
            dbg("Bypass GRANTED for " + target.getName() + " by " + sender.getName());
        }
        return true;
    }

    private boolean handleHelp(CommandSender sender) {
        ChatUtil.send(sender, lang("help-header"));
        List<String> lines = langConfig != null
                ? langConfig.getStringList("messages.help-lines")
                : Collections.emptyList();
        if (lines.isEmpty()) {
            for (String l : new String[]{
                    "&e/fpac reload    &7– Reload config & language",
                    "&e/fpac debug     &7– Toggle debug logging",
                    "&e/fpac world <list|add|remove> [name]  &7– Manage worlds",
                    "&e/fpac stats     &7– Show runtime statistics",
                    "&e/fpac bypass <player>  &7– Toggle bypass for a player",
                    "&e/fpac help      &7– Show this help"
            }) { ChatUtil.send(sender, l); }
        } else {
            lines.forEach(l -> ChatUtil.send(sender, ChatUtil.color(l)));
        }
        ChatUtil.send(sender, lang("help-footer"));
        return true;
    }

    // ═════════════════════════════════════════════════════════════════════
    //  Helpers
    // ═════════════════════════════════════════════════════════════════════

    private void saveWorldList() {
        getConfig().set("worlds.list", new ArrayList<>(protectedWorlds));
        saveConfig();
    }

    private void checkConfigVersion() {
        FileConfiguration cfg = getConfig();
        Object rawVer = cfg.get("config-version", 0);
        double currentVer;
        if (rawVer instanceof Number n) currentVer = n.doubleValue();
        else {
            try { currentVer = Double.parseDouble(rawVer.toString()); }
            catch (Exception e) { currentVer = 0.0; }
        }
        double latestVer = 4.0;

        if (currentVer < latestVer) {
            getLogger().info("[FPAntiFreeCam] Updating config.yml to version " + latestVer + "…");
            InputStream defStream = getResource("config.yml");
            if (defStream != null) {
                YamlConfiguration defConfig = YamlConfiguration.loadConfiguration(
                        new InputStreamReader(defStream, StandardCharsets.UTF_8));
                for (String key : defConfig.getKeys(true)) {
                    if (!cfg.contains(key)) cfg.set(key, defConfig.get(key));
                }
            }
            cfg.set("config-version", latestVer);
            saveConfig();
            getLogger().info("[FPAntiFreeCam] Config update complete.");
        }
    }
}
