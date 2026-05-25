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
 * Anti-FreeCam: when a player is at or above the configured
 * surface-y threshold, all blocks/entities at or below void-y are replaced
 * with air in outbound packets so FreeCam mods see only void.
 *
 * Compatible with Spigot / Paper / Folia, MC 1.19 through 26.1+.
 */
public final class FPAntiFreeCam extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {

    // ── Singleton ─────────────────────────────────────────────────────────
    private static FPAntiFreeCam instance;
    public static FPAntiFreeCam getInstance() { return instance; }

    // ── Runtime player-state maps ─────────────────────────────────────────
    /**
     * true  = protection active, underground blocks hidden from this player.
     * false = player is underground or has bypass; full view is shown.
     */
    public final Map<UUID, Boolean> playerHiddenState  = new ConcurrentHashMap<>();
    /** Epoch ms when each player's refresh cooldown expires. */
    private final Map<UUID, Long>   refreshCooldowns   = new ConcurrentHashMap<>();
    /**
     * Epoch ms when a raycast-based deactivation was first requested for each player.
     * Deactivation is only applied after a short debounce (500 ms) to prevent
     * rapid state flipping when the player hovers near the raycast zone boundary.
     */
    private final Map<UUID, Long>   raycastDeactivationPending = new ConcurrentHashMap<>();
    /**
     * Players currently being re-teleported by us to avoid infinite loops
     * in the teleport event handler.
     */
    private final Set<UUID> internallyTeleporting      = ConcurrentHashMap.newKeySet();

    // ── Stats counters ────────────────────────────────────────────────────
    /** Total outbound chunk/block packets intercepted and modified. */
    public final AtomicLong totalPacketsProcessed = new AtomicLong();
    /** Total chunk sections that had at least one block replaced. */
    public final AtomicLong totalChunksModified   = new AtomicLong();
    /** Total individual blocks replaced with the void replacement. */
    public final AtomicLong totalBlocksReplaced   = new AtomicLong();
    /** System.currentTimeMillis() recorded at onEnable(). */
    private long enabledAt = 0;

    // ── Config-driven values ──────────────────────────────────────────────
    private WrappedBlockState replacementBlockState;
    private int               replacementBlockId    = 0;
    private String            replacementBlockType  = "minecraft:air";

    private boolean debugMode           = false;
    private int     refreshCooldownMs   = 3_000;

    /** Y level at-or-above which the hiding effect is armed. */
    private double surfaceY = 16.0;
    /** Blocks at-or-below this Y are hidden while the effect is active. */
    private int    voidY    = 15;

    private Set<String> protectedWorlds = ConcurrentHashMap.newKeySet();

    private boolean limitedAreaEnabled = false;
    private int     limitedAreaRadius  = 4;
    private boolean instantProtection        = true;
    private int     instantRadius            = 14;
    private int     preLoadDistance          = 10;
    private boolean forceImmediateRefresh    = true;

    // ── Raycast / deep-deactivation config ───────────────────────────────
    /**
     * When true, the periodic raycast task runs for players between
     * deepDeactivationY and surfaceY. If they look upward or have an
     * unobstructed line-of-sight to the surface, protection stays armed.
     */
    private boolean raycastEnabled    = true;
    /**
     * Minimum upward component of the look-vector (0–1) to trigger the
     * look-direction condition. ~0.15 ≈ ~9° above horizontal.
     */
    private double  raycastMinUpward  = 0.15;
    /**
     * Milliseconds a player must remain below deepDeactivationY before
     * protection is actually deactivated (debounce against flicker).
     */
    private long    raycastDebounceMs = 500L;
    /**
     * Absolute Y floor. If the player's feet are BELOW this value,
     * protection is ALWAYS deactivated — no raycast, no exceptions.
     * Fixes the "Y=16 void" bug where a player digging straight down
     * in stone sees nothing but void beneath them.
     * Range: should be between voidY and surfaceY.
     */
    private double  deepDeactivationY = 20.0;

    // ── Language config ───────────────────────────────────────────────────
    private FileConfiguration langConfig;
    private String            currentLanguage = "en";

    // ── Sub-systems ───────────────────────────────────────────────────────
    private FoliaScheduler  foliaScheduler;
    private PaperScheduler  paperScheduler;
    private BedrockSupport  bedrockSupport;
    private EntityHider     entityHider;
    private UpdateChecker   updateChecker;

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
        api.getSettings()
                .checkForUpdates(false)
                .bStats(true);
        api.load();
        if (!api.isLoaded()) {
            getLogger().severe("[FPAntiFreeCam] PacketEvents failed to load – plugin will be disabled.");
        }
    }

    @Override
    public void onEnable() {
        ChatUtil.printBanner(ChatUtil.startupBanner(
                getDescription().getVersion(),
                PlatformUtil.getPlatformName()));

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

        // Record startup time, launch the raycast monitor, and check for updates.
        enabledAt = System.currentTimeMillis();
        startRaycastTask();
        if (getConfig().getBoolean("settings.update-checker", true)) updateChecker.check();

        // Handle already-online players (e.g. after /reload).
        try {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (isWorldProtected(p.getWorld().getName())) {
                    handlePlayerInitialState(p, /* immediateRefresh= */ false);
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

        if (entityHider != null) {
            entityHider.refreshAll();
        }
        if (paperScheduler != null) {
            paperScheduler.shutdown();
        }

        PacketEventsAPI<?> api = PacketEvents.getAPI();
        if (api != null && api.isLoaded()) {
            api.terminate();
        }

        playerHiddenState.clear();
        refreshCooldowns.clear();
        raycastDeactivationPending.clear();
        internallyTeleporting.clear();
    }

    // ═════════════════════════════════════════════════════════════════════
    //  Public API – consumed by ChunkListener, EntityHider, schedulers, etc.
    // ═════════════════════════════════════════════════════════════════════

    public boolean isWorldProtected(String worldName) {
        return worldName != null && protectedWorlds.contains(worldName);
    }

    public boolean isProtectionActive(Player player) {
        if (player == null) return false;
        if (player.hasPermission("fpantifreecam.bypass")) return false;
        boolean active = Boolean.TRUE.equals(playerHiddenState.get(player.getUniqueId()));
        if (active) dbg("Protection active for " + player.getName());
        return active;
    }

    public WrappedBlockState getReplacementBlock() { return replacementBlockState; }
    public int getReplacementBlockId()             { return replacementBlockId;    }
    public int getVoidY()                          { return voidY;                 }

    /**
     * Checks if the player has a clear line of sight to the sky from their current location.
     * Used as an auxiliary check for activation.
     */
    public boolean isExposedToSky(Player player) {
        Location loc = player.getLocation();
        return loc.getWorld().getHighestBlockYAt(loc) <= loc.getY();
    }

    public void dbg(String message) {
        if (debugMode) {
            getLogger().info("[FPAntiFreeCam DEBUG] " + message);
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    //  Public stats incrementers (called from ChunkListener)
    // ═════════════════════════════════════════════════════════════════════

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
        surfaceY          = cfg.getDouble("protection.surface-y", 16.0);
        voidY             = cfg.getInt("protection.void-y", 15);

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
        raycastEnabled    = cfg.getBoolean("protection.raycast.enabled", true);
        raycastMinUpward  = cfg.getDouble("protection.raycast.min-upward-angle", 0.15);
        raycastDebounceMs = cfg.getLong("protection.raycast.deactivation-debounce-ms", 500L);
        deepDeactivationY = cfg.getDouble("protection.deep-deactivation-y", 20.0);

        loadLanguageConfig(cfg.getString("settings.language", "en"));

        if (entityHider != null) entityHider.loadSettings();

        dbg("Config loaded – worlds=" + protectedWorlds
                + " surfaceY=" + surfaceY
                + " voidY=" + voidY
                + " block=" + replacementBlockType
                + " raycast=" + raycastEnabled + "(deepDeactivationY=" + deepDeactivationY + ")");
    }

    private void loadLanguageConfig(String language) {
        currentLanguage = language;
        File langDir  = new File(getDataFolder(), "lang");
        File langFile = new File(langDir, language + ".yml");

        if (!langDir.exists()) langDir.mkdirs();
        if (!langFile.exists()) {
            try { saveResource("lang/" + language + ".yml", false); }
            catch (Exception ignored) {
                try { saveResource("lang/en.yml", false); }
                catch (Exception ignored2) {}
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
        dbg("Language loaded: " + language);
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

        boolean bypass     = player.hasPermission("fpantifreecam.bypass");
        // Protection is "armed" if above surfaceY. 
        // We will do more granular per-packet checks in ChunkListener.
        boolean shouldHide = !bypass && player.getLocation().getY() >= surfaceY;
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
        if (limitedAreaEnabled) radius = Math.min(radius, limitedAreaRadius);
        if (bedrockSupport  != null) radius = bedrockSupport.optimisedRadius(player, radius);
        
        dbg("refreshFullView → " + player.getName() + " radius=" + radius + " (bypass=" + bypassCooldown + ")");
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
            World  world = player.getWorld();
            int    px    = player.getLocation().getBlockX() >> 4;
            int    pz    = player.getLocation().getBlockZ() >> 4;
            for (int cx = px - radius; cx <= px + radius; cx++) {
                for (int cz = pz - radius; cz <= pz + radius; cz++) {
                    try { world.refreshChunk(cx, cz); } catch (Exception ignored) {}
                }
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    //  Raycast activation task
    // ═════════════════════════════════════════════════════════════════════

    /**
     * Starts a low-frequency task (every 5 ticks / 250 ms) that checks players
     * who are physically below surfaceY but within {@code raycastZone} blocks of
     * it.  If they are looking upward <em>or</em> have an unobstructed vertical
     * line-of-sight to the surface, protection is armed so FreeCam cannot
     * exploit a cave opening or upward camera angle to peer underground.
     */
    private void startRaycastTask() {
        if (!raycastEnabled) return;
        Runnable check = () -> {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (isWorldProtected(p.getWorld().getName())
                        && !p.hasPermission("fpantifreecam.bypass")) {
                    checkRaycastActivation(p);
                }
            }
        };
        if (FoliaScheduler.shouldUse()) {
            try {
                Object sched = Bukkit.class.getMethod("getGlobalRegionScheduler").invoke(null);
                sched.getClass()
                        .getMethod("runAtFixedRate",
                                org.bukkit.plugin.Plugin.class,
                                java.util.function.Consumer.class,
                                long.class, long.class)
                        .invoke(sched, this,
                                (java.util.function.Consumer<Object>) t -> check.run(),
                                5L, 5L);
            } catch (Exception e) {
                getLogger().warning("[FPAntiFreeCam] Folia raycast task init failed, falling back: " + e.getMessage());
                Bukkit.getScheduler().runTaskTimer(this, check, 5L, 5L);
            }
        } else {
            Bukkit.getScheduler().runTaskTimer(this, check, 5L, 5L);
        }
    }

    /**
     * Two-layer protection decision for players physically below surfaceY.
     *
     * <p><b>Layer 1 – Deep deactivation (hard floor):</b><br>
     * If the player is below {@code deepDeactivationY}, protection is ALWAYS off.
     * No raycast, no look-direction check — just off. This fixes the Y=16 void bug
     * where a player digging straight down through stone would see nothing but void
     * beneath them because voidY=15 was hidden even when fully surrounded by blocks.
     *
     * <p><b>Layer 2 – Raycast zone (deepDeactivationY ≤ feetY &lt; surfaceY):</b><br>
     * In this band the player <em>might</em> be exploitable by FreeCam (thin ceiling,
     * cave opening, or camera angled upward). Two sub-checks decide:
     * <ol>
     *   <li>Look-direction: if the camera points meaningfully upward ({@code dir.y > raycastMinUpward}),
     *       arm protection so FreeCam can’t angle up to see bases.</li>
     *   <li>Sky visibility: cast a vertical ray from the eye to surfaceY. If no solid
     *       block is hit, the cave has an open ceiling — arm protection.</li>
     * </ol>
     * Both sub-checks must be FALSE to deactivate. Deactivation uses a debounce
     * ({@code raycastDebounceMs}) to prevent flicker when hovering near the boundary.
     */
    /**
     * Decisions on whether protection should be active.
     * <p><b>Layer 1 – Deep deactivation (hard floor):</b><br>
     * If the player is below {@code deepDeactivationY}, protection is ALWAYS off.
     * This fixes the Y=16 void bug where a player digging straight down
     * through stone would see nothing but void beneath them.
     *
     * <p><b>Layer 2 – Raycast zone:</b><br>
     * Between {@code deepDeactivationY} and {@code surfaceY}, we check:
     * <ol>
     *   <li>Look-direction: if the camera points meaningfully upward ({@code dir.y > raycastMinUpward}).</li>
     *   <li>Sky visibility: cast a vertical ray from the eye to surfaceY.</li>
     * </ol>
     * Both sub-checks must be FALSE to deactivate.
     *
     * <p><b>Layer 3 – Surface:</b><br>
     * At or above {@code surfaceY}, protection is ALWAYS on.
     */
    private void checkRaycastActivation(Player player) {
        double feetY = player.getLocation().getY();
        UUID   id    = player.getUniqueId();

        boolean currentHidden = Boolean.TRUE.equals(playerHiddenState.getOrDefault(id, false));
        boolean targetHidden;

        // Determine target state based on altitude.
        if (feetY < deepDeactivationY) {
            targetHidden = false;
        } else if (feetY >= surfaceY) {
            targetHidden = true;
        } else {
            targetHidden = calculateRaycast(player);
        }

        if (targetHidden == currentHidden) {
            raycastDeactivationPending.remove(id);
            return;
        }

        dbg("DEBUG: State mismatch for " + player.getName() + " current=" + currentHidden + " target=" + targetHidden + " Y=" + feetY);
        
        long now = System.currentTimeMillis();
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
        
        // Pass bypassCooldown=true when turning OFF protection (targetHidden == false)
        refreshFullView(player, !targetHidden);

        dbg("Layer transition " + (targetHidden ? "ON" : "OFF") + " for " + player.getName() 
                + " at Y=" + String.format("%.1f", feetY));
    }

    private boolean calculateRaycast(Player player) {
        try {
            Location eye    = player.getEyeLocation();
            double   distUp = Math.min(128.0, 320.0 - eye.getY()); // 128 block limit
            if (distUp > 0.5) {
                RayTraceResult rt = player.getWorld().rayTraceBlocks(
                        eye, new Vector(0, 1, 0), distUp,
                        FluidCollisionMode.NEVER, true);
                if (rt == null) return true; // unobstructed path
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
            handlePlayerInitialState(player, /* immediateRefresh= */ false);
        }
        if (bedrockSupport != null && bedrockSupport.isBedrock(player)) {
            dbg("Bedrock player joined: " + player.getName());
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        playerHiddenState.remove(id);
        refreshCooldowns.remove(id);
        raycastDeactivationPending.remove(id);
        internallyTeleporting.remove(id);
        if (foliaScheduler != null) foliaScheduler.cleanupPlayer(id);
        if (paperScheduler != null) paperScheduler.cleanupPlayer(id);
        if (bedrockSupport != null) bedrockSupport.cleanupPlayer(id);
        if (entityHider    != null) entityHider.cleanupPlayer(id);
        dbg("Cleaned up quit player: " + event.getPlayer().getName());
    }

    @EventHandler
    public void onPlayerChangedWorld(PlayerChangedWorldEvent event) {
        Player player  = event.getPlayer();
        String toWorld = player.getWorld().getName();
        dbg("WorldChange: " + player.getName() + " → " + toWorld);

        if (isWorldProtected(toWorld)) {
            handlePlayerInitialState(player, /* immediateRefresh= */ true);
        } else {
            boolean wasHidden = playerHiddenState.remove(player.getUniqueId()) != null;
            if (wasHidden) refreshFullView(player);
        }
    }

    /**
     * Re-evaluates protection state when the player switches game modes.
     * This fixes the creative-mode bug: entering creative and flying to
     * surfaceY triggers teleport events (not always move events), and the
     * state must be current before the first chunk refresh fires.
     */
    @EventHandler
    public void onPlayerGameModeChange(PlayerGameModeChangeEvent event) {
        Player player = event.getPlayer();
        if (!isWorldProtected(player.getWorld().getName())) return;
        // Defer by 1 tick so the new game mode is fully applied.
        PlatformUtil.runTaskLater(this, () -> {
            if (player.isOnline()) handlePlayerInitialState(player, true);
        }, 1L);
    }

    /**
     * Intercepts player teleports to prevent a momentary "void glimpse" when
     * going from a hiding state to a visible one.
     */
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
        boolean bypass    = player.hasPermission("fpantifreecam.bypass");
        boolean oldHidden = Boolean.TRUE.equals(playerHiddenState.getOrDefault(id, to.getY() >= surfaceY));
        boolean newHidden = !bypass && to.getY() >= surfaceY;

        if (oldHidden == newHidden) return;

        if (!newHidden) {
            // Was hiding → now visible.  Cancel + re-teleport to avoid void flash.
            playerHiddenState.put(id, false);
            event.setCancelled(true);
            final var dest = to;
            PlatformUtil.runTask(this, dest, () -> {
                if (!player.isOnline()) return;
                internallyTeleporting.add(id);
                try {
                    player.teleport(dest);
                } finally {
                    internallyTeleporting.remove(id);
                }
            });
        } else {
            // Was visible → now hiding.
            // BUG FIX: must ALSO refresh chunks so already-sent chunks get the
            // hidden treatment immediately – not just future chunk sends.
            // This is the root cause of the creative-mode Y-level bug:
            // creative flight fires teleport events, not always move events.
            playerHiddenState.put(id, true);
            if (entityHider != null) entityHider.updateFor(player);
            final var refreshDest = to;
            PlatformUtil.runTask(this, refreshDest, () -> {
                if (player.isOnline()) refreshFullView(player);
            });
        }
    }

    /**
     * Detects Y-level transitions across the surface-y threshold and toggles
     * the protection state, triggering a chunk refresh as needed.
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
                refreshFullView(player);
            }
            return;
        }

        // Skip if the player hasn't crossed a block boundary on the Y axis –
        // this filters ~95 % of move events at essentially zero cost.
        if (from.getBlockY() == to.getBlockY()) return;

        UUID    id        = player.getUniqueId();
        boolean bypass    = player.hasPermission("fpantifreecam.bypass");
        
        // Logical state: ON if above surfaceY, OFF if below deepDeactivationY.
        // Between them, we defer to the raycast task.
        boolean newHidden = !bypass && to.getY() >= surfaceY;
        if (to.getY() < deepDeactivationY) newHidden = false;

        // BUG FIX: if no state entry exists (join/world-change race condition),
        // initialise it rather than hitting the getOrDefault(id, newHidden) trap
        // which would make oldHidden == newHidden and silently do nothing.
        if (!playerHiddenState.containsKey(id)) {
            handlePlayerInitialState(player, true);
            return;
        }
        boolean oldHidden = Boolean.TRUE.equals(playerHiddenState.get(id));

        // If the player is in the raycast zone, defer deactivation/activation to the raycast task.
        // This prevents onPlayerMove from fighting the raycast task and causing flickering.
        if (!bypass && raycastEnabled && to.getY() >= deepDeactivationY && to.getY() < surfaceY) {
            return;
        }

        // Player moved out of the raycast zone entirely; clear any pending deactivation
        // so the raycast task's debounce doesn't re-arm incorrectly.
        if (!newHidden) raycastDeactivationPending.remove(id);

        if (newHidden == oldHidden) return;

        dbg("Move transition - changing state for " + player.getName() + ": " + oldHidden + " -> " + newHidden + " at Y=" + to.getY());
        playerHiddenState.put(id, newHidden);

        if (entityHider != null) entityHider.updateFor(player);

        if (!newHidden) {
            // Player went underground – restore normal view.
            refreshFullView(player);
        } else {
            // Player came to the surface – begin hiding, respecting cooldown.
            long now        = System.currentTimeMillis();
            long expiration = refreshCooldowns.getOrDefault(id, 0L);
            if (now < expiration) {
                dbg("Refresh cooldown active for " + player.getName()
                        + " (expires in " + (expiration - now) + " ms)");
                return;
            }

            int radius = Bukkit.getViewDistance();
            if (instantProtection && forceImmediateRefresh
                    && to.getY() <= surfaceY + preLoadDistance) {
                radius = Math.max(radius, instantRadius);
                dbg("Instant-protection radius=" + radius + " for " + player.getName());
            }
            if (limitedAreaEnabled) radius = Math.min(radius, limitedAreaRadius);
            if (bedrockSupport  != null) radius = bedrockSupport.optimisedRadius(player, radius);

            final int fr = radius;
            performRefresh(player, fr);
            refreshCooldowns.put(id, now + refreshCooldownMs);
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    //  Command system
    // ═════════════════════════════════════════════════════════════════════

    private void registerCommands() {
        for (String cmdName : new String[]{ "fpac", "fpreload", "fpdebug" }) {
            var cmd = getCommand(cmdName);
            if (cmd != null) {
                cmd.setExecutor(this);
                cmd.setTabCompleter(this);
            }
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
        loadConfigValues();
        initReplacementBlock();

        for (Player p : Bukkit.getOnlinePlayers()) {
            if (isWorldProtected(p.getWorld().getName())) {
                handlePlayerInitialState(p, true);
            } else if (playerHiddenState.remove(p.getUniqueId()) != null) {
                refreshFullView(p);
            }
        }
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
            if (protectedWorlds.isEmpty()) {
                ChatUtil.sendWarn(sender, lang("world-list-empty"));
            } else {
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

    // /fpac stats  ─────────────────────────────────────────────────────────
    private boolean handleStats(CommandSender sender) {
        if (!sender.hasPermission("fpantifreecam.admin")) {
            sender.sendMessage(lang("no-permission")); return true;
        }

        // ── Player counts ─────────────────────────────────────────────────
        long activeCount = playerHiddenState.values().stream().filter(b -> b).count();
        long onlineCount = Bukkit.getOnlinePlayers().size();

        // ── TPS ───────────────────────────────────────────────────────────
        String tpsStr;
        try {
            double[] tps = Bukkit.getServer().getTPS();
            double t1 = Math.min(tps[0], 20.0);
            double t5 = Math.min(tps[1], 20.0);
            double t15= Math.min(tps[2], 20.0);
            String c1 = t1  >= 18 ? "&a" : t1  >= 15 ? "&e" : "&c";
            String c5 = t5  >= 18 ? "&a" : t5  >= 15 ? "&e" : "&c";
            String c15= t15 >= 18 ? "&a" : t15 >= 15 ? "&e" : "&c";
            tpsStr = String.format("%s%.2f &7/ %s%.2f &7/ %s%.2f &8(1m/5m/15m)",
                    c1, t1, c5, t5, c15, t15);
        } catch (Exception e) { tpsStr = "&7N/A"; }

        // ── Memory ────────────────────────────────────────────────────────
        Runtime rt    = Runtime.getRuntime();
        long usedMB   = (rt.totalMemory() - rt.freeMemory()) / 1_048_576L;
        long maxMB    = rt.maxMemory() / 1_048_576L;
        int  memPct   = (int)(usedMB * 100L / Math.max(maxMB, 1L));
        String memC   = memPct >= 90 ? "&c" : memPct >= 70 ? "&e" : "&a";
        String memStr = memC + usedMB + " MB &7/ &f" + maxMB + " MB &8(" + memPct + "%)";

        // ── Uptime ────────────────────────────────────────────────────────
        long upSec  = (System.currentTimeMillis() - enabledAt) / 1_000L;
        long upMin  = upSec / 60L; upSec %= 60L;
        long upHour = upMin / 60L; upMin %= 60L;
        String uptimeStr = String.format("&e%dh &e%dm &e%ds", upHour, upMin, upSec);

        // ── Output ────────────────────────────────────────────────────────
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
                + " &7active &8/ &f" + onlineCount + " &7online");
        ChatUtil.send(sender, " &7Worlds      &8: &a" + protectedWorlds.size()
                + " &8— &7" + protectedWorlds);
        ChatUtil.send(sender, " &7Surface Y   &8: &e" + surfaceY
                + "  &7Void Y &8: &e" + voidY);
        ChatUtil.send(sender, " &7Block       &8: &f" + replacementBlockType
                + " &8(id=" + replacementBlockId + ")");
        ChatUtil.send(sender, " &7Raycast     &8: " + (raycastEnabled
                ? "&aON &8| &7deepDeactivationY &8= &e" + (int)deepDeactivationY
                + " &8| &7minUpY &8= &e" + raycastMinUpward
                + " &8| &7debounce &8= &e" + raycastDebounceMs + "ms"
                : "&cOFF"));
        ChatUtil.send(sender, sep);
        ChatUtil.send(sender, " &7Pkts proc   &8: &e" + totalPacketsProcessed.get()
                + " &8(all chunk/block packets intercepted)");
        ChatUtil.send(sender, " &7Chunks mod  &8: &e" + totalChunksModified.get()
                + " &8(chunks with ≥1 block replaced)");
        ChatUtil.send(sender, " &7Blocks rep  &8: &e" + totalBlocksReplaced.get()
                + " &8(total underground blocks hidden)");
        ChatUtil.send(sender, " &7Cooldown    &8: &a" + (refreshCooldownMs / 1_000)
                + "s  &7Debug &8: " + (debugMode ? "&aON" : "&7OFF"));
        if (bedrockSupport != null)
            ChatUtil.send(sender, " &7Bedrock     &8: &a" + bedrockSupport.statusLine());
        if (entityHider != null)
            ChatUtil.send(sender, " &7EntityHider &8: &a" + entityHider.stats());
        if (foliaScheduler != null)
            ChatUtil.send(sender, " &7Folia Sched &8: &a" + foliaScheduler.stats());
        if (paperScheduler != null)
            ChatUtil.send(sender, " &7Paper Sched &8: &a" + paperScheduler.stats());
        ChatUtil.send(sender, sep);
        return true;
    }

    private boolean handleBypass(CommandSender sender, String[] args) {
        if (!sender.hasPermission("fpantifreecam.bypass")) {
            sender.sendMessage(lang("no-permission")); return true;
        }
        if (args.length == 0) {
            ChatUtil.sendError(sender, "Usage: /fpac bypass <player>"); return true;
        }
        Player target = Bukkit.getPlayer(args[0]);
        if (target == null) {
            ChatUtil.sendError(sender, lang("bypass-unknown", args[0])); return true;
        }
        playerHiddenState.put(target.getUniqueId(), false);
        if (entityHider != null) entityHider.updateFor(target);
        refreshFullView(target);
        ChatUtil.sendSuccess(sender, lang("bypass-granted", target.getName()));
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
                    "&e/fpac bypass <player>  &7– Force-clear protection for a player",
                    "&e/fpac help      &7– Show this help",
                    "&e/fpreload       &7– Alias: reload",
                    "&e/fpdebug        &7– Alias: debug"
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
        if (rawVer instanceof Number n) {
            currentVer = n.doubleValue();
        } else {
            try { currentVer = Double.parseDouble(rawVer.toString()); }
            catch (Exception e) { currentVer = 0.0; }
        }
        double latestVer = 3.1;

        if (currentVer < latestVer) {
            getLogger().info("[FPAntiFreeCam] Updating config.yml to version " + latestVer + "...");
            InputStream defStream = getResource("config.yml");
            if (defStream != null) {
                YamlConfiguration defConfig = YamlConfiguration.loadConfiguration(
                        new InputStreamReader(defStream, StandardCharsets.UTF_8));
                for (String key : defConfig.getKeys(true)) {
                    if (!cfg.contains(key)) {
                        cfg.set(key, defConfig.get(key));
                    }
                }
            }
            cfg.set("config-version", latestVer);
            saveConfig();
            getLogger().info("[FPAntiFreeCam] Config update complete.");
        }
    }
}