package me.fleoxxzy.FPAntiFreeCam;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.projectiles.ProjectileSource;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * NEW (1.5.3): line-of-sight entity hiding, the entity side of the raycast
 * Anti-Xray.
 *
 * <p>The old EntityHider only hid non-player entities at/below void-y while
 * surface protection was on, and Smart Fill showed every entity in a
 * revealed 16x16x16 section, even behind solid rock. Now every entity the
 * server sends to a player is tested every 5 ticks, on the player's own
 * thread, with rays from the same cameras Anti-Xray uses. Entities nobody
 * could see are hidden; they show the moment any camera can see any part of
 * them, and hide again after {@code hide-delay-ms} out of sight.
 *
 * <p>The hidden zone (void-y / Smart Fill band) still applies on top: there,
 * an entity is shown only if its block would be real for this player AND it
 * is in sight. This is the one decision function for both the periodic
 * check and the Netty spawn path, so they can't disagree.
 *
 * <p>How an entity is hidden:
 * <ul>
 *   <li>Non-players: {@link Player#hideEntity}, so the server tracker stays the
 *       source of truth and re-sends metadata/equipment/passengers on show.</li>
 *   <li>Players: packet level (a silent DESTROY_ENTITIES plus cancelling that
 *       player's packets on Netty). hideEntity on a player makes canSee false,
 *       which breaks name tab completion and vanish-aware plugins while
 *       someone is behind a wall. To show them again a quick hide+show makes
 *       the tracker re-send the spawn, and the PLAYER_INFO remove it causes is
 *       suppressed, so the tab list never changes.</li>
 *   <li>Spawn packets for entities that aren't known to be visible are
 *       cancelled on Netty (no 250 ms ESP leak), and re-synced with hide+show
 *       once a check finds them visible.</li>
 * </ul>
 *
 * <p>"When unsure, show": rays into unloaded chunks or another Folia region
 * count as visible, and entities a check had no budget or region access for
 * keep their current state.
 */
public final class EntityHider implements Listener {

    private final FPAntiFreeCam plugin;

    // ── Settings ──────────────────────────────────────────────────────────
    private volatile boolean enabled = true;
    private volatile boolean losEnabled = true;
    private volatile boolean includePlayers = false;
    private volatile double  alwaysShowRadius = 8;
    private volatile long    hideDelayMs = 1500;
    private volatile int     rayBudget = 15000;
    private volatile boolean f5Aware = true;
    private volatile Set<String> disabledWorlds = Collections.emptySet();
    private volatile Set<String> neverHide = Collections.emptySet();

    static final List<String> DEFAULT_NEVER_HIDE = List.of(
            "ender_dragon", "wither", "lightning_bolt", "arrow", "spectral_arrow", "trident",
            "fireball", "small_fireball", "dragon_fireball", "wither_skull", "firework_rocket",
            "fishing_bobber", "ender_pearl", "snowball", "egg", "potion", "experience_bottle",
            "llama_spit", "shulker_bullet", "wind_charge");

    /** Show for this long after either side damaged the other. */
    private static final long COMBAT_MS = 3000;
    /** Re-test a pair at least this often even when nothing moved. */
    private static final long RETEST_MS = 1000;
    /**
     * IMPROVEMENT: pairs where nothing moved back off (1, 2, 4, 5 s)
     * instead of re-running every ray each second. Broken / placed / moved
     * blocks near the viewer reset this straight away (onWallsOpened).
     */
    private static final long STILL_RETEST_MAX_MS = 5000;
    /** A spawn packet passes if the entity was decided visible this recently. */
    private static final long SHOWN_TTL_MS = 2000;
    /** PLAYER_INFO remove suppression marks expire after this long. */
    private static final long INFO_MARK_MS = 2000;
    private static final double SKIP_ENTITY_MOVE_SQ = 0.25 * 0.25;
    /** Moving boxes grow by velocity * this many seconds (covers the 10-tick check)... */
    private static final double LEAD_SECONDS = 0.5;
    /** ...capped at this many blocks. */
    private static final double LEAD_MAX = 2.0;
    /** Corner sample points are pulled this far toward the box center. */
    private static final double CORNER_INSET = 0.1;
    /** A hidden-zone result is reused this long while the entity stays in its block. */
    private static final long ZONE_CACHE_MS = 500;
    /** Past this distance the eye tests 5 points of an entity instead of 11. */
    private static final double FAR_POINTS_DIST = 24;
    /** How long glowing / leash results are reused. */
    private static final long FLAG_CACHE_MS = 1_000;
    private static final long OUT_OF_RANGE_SWEEP_MS = 2_000;
    /** Chunk mode: how often all nearby entities are scanned (hidden ones are re-checked every pass). */
    private static final long CHUNK_FULL_SCAN_MS = 5_000;
    /** Chunk mode: a hidden entity where nothing changed is fully re-checked this often. */
    private static final long STILL_RECHECK_MS = 2_000;
    /** Leashes break well before this distance, so a mob further away can't be leashed to the viewer. */
    private static final double LEASH_MAX = 24;
    /** Chunk mode: how often a kept reference is checked with isValid while hidden. */
    private static final long VALID_RECHECK_MS = 1_000;

    static final int VISIBLE = 0, HIDDEN = 1, NO_BUDGET = 2;
    /** Hide modes of a tracked entity. */
    static final int SHOWN = 0, PACKET = 1, BUKKIT = 2;

    /** One entity the server sent (or tried to send) to one player. */
    static final class Tracked {
        final int id;
        final UUID uuid;
        final boolean isPlayer;
        /** SHOWN / PACKET / BUKKIT. Written on the player's thread and on Netty (spawn). */
        volatile int mode;
        // Player thread only from here on.
        long lastSeen;
        long lastTest;
        boolean lastVisible = true;
        boolean tested;
        /** Same result in a row while nothing moved (re-test backoff). */
        int stillStreak;
        double tx, ty, tz;        // entity position at the last test
        long tAt;                 // when that position was taken
        double vx, vy, vz;        // estimated velocity, blocks/second
        // Hidden-zone cache (zoneHidden).
        int zx, zy, zz;
        long zoneAt;
        boolean zoneHidden;
        /** IMPROVEMENT: glowing / leashed-to-viewer result, reused for FLAG_CACHE_MS. */
        long flagsAt;
        boolean glowing, leashed;
        /** Time of the last check() that found this entity nearby. */
        long checkedAt;
        /** IMPROVEMENT: saves a UUID lookup when re-checking hidden entities. */
        java.lang.ref.WeakReference<Entity> ref;
        /** Chunk mode: last full re-check that kept this entity hidden, and the viewer's eye then. */
        long rcAt;
        double rcEx, rcEy, rcEz;
        /** Chunk mode: last time the kept reference was checked with isValid. */
        long validAt;

        Tracked(int id, UUID uuid, boolean isPlayer, int mode) {
            this.id = id; this.uuid = uuid; this.isPlayer = isPlayer; this.mode = mode;
        }
    }

    /** Per-viewer state. Netty reads the concurrent parts, the rest is player-thread only. */
    static final class View {
        final Map<Integer, Tracked> tracked = new ConcurrentHashMap<>();
        /** Entity ids whose packets are cancelled for this viewer (PACKET mode). */
        final Set<Integer> blocked = ConcurrentHashMap.newKeySet();
        /** Entity UUID -> last time it was decided visible (spawn packets pass). */
        final Map<UUID, Long> shownAt = new ConcurrentHashMap<>();
        /** Player UUID -> expiry of a pending "suppress the PLAYER_INFO remove we caused". */
        final Map<UUID, Long> infoRemoveMarks = new ConcurrentHashMap<>();
        /** Entity UUID -> last combat time with this viewer. */
        final Map<UUID, Long> combat = new ConcurrentHashMap<>();
        /** Entity UUIDs this viewer has hidden through Player#hideEntity (by us). */
        final Set<UUID> bukkitHidden = ConcurrentHashMap.newKeySet();
        volatile double ex, ey, ez;
        volatile UUID worldId;
        volatile boolean losActive;
        /** NEW: players can be hidden from this viewer (include-players on, locator bar off). */
        volatile boolean hidePlayers;
        /** NEW: a block near this viewer was broken / moved after this time; cached hidden results are stale. */
        volatile long wallsOpenedAt;
        double[] lastCams;
        double lastLookX, lastLookY, lastLookZ;
        long lastSweep;
        /** Chunk mode: last full nearby scan, and the Smart Fill reveal version seen. */
        long lastFullScan;
        int revealVersion;
    }

    private final Map<UUID, View> views = new ConcurrentHashMap<>();

    // ── Stats ─────────────────────────────────────────────────────────────
    final AtomicLong checks = new AtomicLong();
    final AtomicLong raySteps = new AtomicLong();
    final AtomicLong spawnsCancelled = new AtomicLong();
    final AtomicLong budgetCuts = new AtomicLong();
    private final long statsSince = System.currentTimeMillis();

    public EntityHider(FPAntiFreeCam plugin) {
        this.plugin = plugin;
        loadSettings();
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    public void loadSettings() {
        org.bukkit.configuration.file.FileConfiguration c = plugin.getConfig();
        enabled          = c.getBoolean("entities.hide-entities", true);
        // NEW: one switch between the two hiding systems. "chunk" (default)
        // hides only what is in Smart Fill's hidden zone, like before the
        // rework. "raycast" also hides entities behind walls.
        String mode = String.valueOf(c.get("entities.hide-mode", "chunk")).trim().toLowerCase(Locale.ROOT);
        if (!mode.equals("chunk") && !mode.equals("raycast")) {
            plugin.getLogger().warning("entities.hide-mode '" + mode + "' is not chunk or raycast, using chunk.");
            mode = "chunk";
        }
        losEnabled       = mode.equals("raycast");
        includePlayers   = c.getBoolean("entities.line-of-sight.include-players", false);
        alwaysShowRadius = clamp(c.getDouble("entities.line-of-sight.always-show-radius", 8), 0, 64);
        hideDelayMs      = (long) clamp(c.getLong("entities.line-of-sight.hide-delay-ms", 1500), 0, 10000);
        rayBudget        = (int) clamp(c.getInt("entities.line-of-sight.ray-budget", 15000), 500, 200000);
        f5Aware          = c.getBoolean("entities.line-of-sight.f5-aware", true);
        disabledWorlds   = Collections.unmodifiableSet(new HashSet<>(c.getStringList("entities.line-of-sight.disabled-worlds")));
        List<String> nh = c.isList("entities.line-of-sight.never-hide")
                ? c.getStringList("entities.line-of-sight.never-hide") : DEFAULT_NEVER_HIDE;
        Set<String> keys = new HashSet<>();
        for (String s : nh) {
            if (s == null) continue;
            String k = s.trim().toLowerCase(Locale.ROOT);
            if (k.startsWith("minecraft:")) k = k.substring(10);
            if (!k.isEmpty()) keys.add(k); // unknown keys simply never match
        }
        neverHide = Collections.unmodifiableSet(keys);
        loadTrackingRanges();
    }

    // ── Entity tracking ranges (spigot.yml) ───────────────────────────────
    // IMPROVEMENT: entities past their tracking range are never sent to the
    // client, so there is nothing to hide. Testing them anyway (out to 128
    // blocks) was most of the cost, and it grew as mobs piled up.
    private static final int TR_PLAYER = 0, TR_ANIMAL = 1, TR_MONSTER = 2, TR_MISC = 3, TR_OTHER = 4, TR_DISPLAY = 5;
    private static final String[] TR_KEYS = { "players", "animals", "monsters", "misc", "other", "display" };
    private static final int[] TR_DEFAULTS = { 48, 48, 48, 32, 64, 128 };
    private volatile Map<String, int[]> trackingRanges = Collections.emptyMap();
    private volatile int[] defaultTracking = TR_DEFAULTS.clone();

    private void loadTrackingRanges() {
        org.bukkit.configuration.ConfigurationSection ws = null;
        try {
            ws = Bukkit.spigot().getConfig().getConfigurationSection("world-settings");
        } catch (Throwable ignored) {} // not Spigot-based: vanilla-like defaults
        int[] def = readRanges(ws != null ? ws.getConfigurationSection("default") : null, TR_DEFAULTS);
        Map<String, int[]> map = new java.util.HashMap<>();
        if (ws != null) {
            for (String name : ws.getKeys(false)) {
                if (!name.equals("default")) map.put(name, readRanges(ws.getConfigurationSection(name), def));
            }
        }
        defaultTracking = def;
        trackingRanges = map;
    }

    private static int[] readRanges(org.bukkit.configuration.ConfigurationSection sec, int[] parent) {
        int[] out = parent.clone();
        if (sec == null) return out;
        for (int i = 0; i < TR_KEYS.length; i++) {
            out[i] = Math.max(1, Math.min(512, sec.getInt("entity-tracking-range." + TR_KEYS[i], parent[i])));
        }
        return out;
    }

    private int[] rangesFor(World w) {
        int[] r = trackingRanges.get(w.getName());
        return r != null ? r : defaultTracking;
    }

    /** Same groups as Spigot's tracking-range activation. */
    private static int trackingGroup(Entity e) {
        if (e instanceof Player) return TR_PLAYER;
        if (e instanceof org.bukkit.entity.Monster || e instanceof org.bukkit.entity.Slime
                || e instanceof org.bukkit.entity.Ghast || e instanceof org.bukkit.entity.Phantom) return TR_MONSTER;
        if (e instanceof org.bukkit.entity.Ageable || e instanceof org.bukkit.entity.Ambient
                || e instanceof org.bukkit.entity.WaterMob) return TR_ANIMAL;
        if (e instanceof org.bukkit.entity.Item || e instanceof org.bukkit.entity.ExperienceOrb
                || e instanceof org.bukkit.entity.ItemFrame || e instanceof org.bukkit.entity.Painting) return TR_MISC;
        if (DISPLAY_CLASS != null && DISPLAY_CLASS.isInstance(e)) return TR_DISPLAY;
        return TR_OTHER;
    }

    /** org.bukkit.entity.Display only exists on 1.19.4+. */
    private static final Class<?> DISPLAY_CLASS;
    static {
        Class<?> c = null;
        try { c = Class.forName("org.bukkit.entity.Display"); } catch (Throwable ignored) {}
        DISPLAY_CLASS = c;
    }

    // ── Locator bar ───────────────────────────────────────────────────────
    /** NEW: gamerule locatorBar (1.21.6+, renamed locator_bar later). Null on older servers. */
    @SuppressWarnings("unchecked")
    private static final org.bukkit.GameRule<Boolean> LOCATOR_BAR;
    static {
        org.bukkit.GameRule<Boolean> g = null;
        for (String name : new String[]{ "locatorBar", "locator_bar", "minecraft:locator_bar" }) {
            try {
                org.bukkit.GameRule<?> r = org.bukkit.GameRule.getByName(name);
                if (r != null && r.getType() == Boolean.class) { g = (org.bukkit.GameRule<Boolean>) r; break; }
            } catch (Throwable ignored) {}
        }
        LOCATOR_BAR = g;
    }

    /** The locator bar shows every player's direction anyway, so hiding them would only look broken. */
    private static boolean locatorBarOn(World w) {
        if (LOCATOR_BAR == null) return false;
        try {
            return Boolean.TRUE.equals(w.getGameRuleValue(LOCATOR_BAR));
        } catch (Throwable t) {
            return false;
        }
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    public boolean isEnabled() { return enabled; }

    public boolean isRaycastMode() { return losEnabled; }

    private View view(UUID id) {
        return views.computeIfAbsent(id, k -> new View());
    }

    // ═════════════════════════════════════════════════════════════════════
    //  Netty side (no Bukkit calls)
    // ═════════════════════════════════════════════════════════════════════

    /**
     * Netty. A spawn packet for {@code entityId} is about to go to the viewer.
     * Returns true if it must be cancelled. {@code zoneHidden} is the old
     * hidden-zone decision for the packet's position (computed by
     * ChunkListener). Cancelled spawns are re-synced by the next check once
     * the entity is visible.
     */
    public boolean onSpawnPacket(UUID viewer, int entityId, UUID entityUuid, boolean isPlayer,
                                 String typeKey, double x, double y, double z, boolean zoneHidden) {
        if (entityUuid == null || entityUuid.equals(viewer)) return false;
        if (!zoneHidden && !enabled) return false;
        View v = view(viewer);
        // Players were never hidden in the zone before; they now are only
        // with include-players on and the locator bar off (same rule as check()).
        boolean hidePlayer = includePlayers && v.hidePlayers;
        boolean hide = zoneHidden && (!isPlayer || hidePlayer);
        if (!hide && v.losActive && (!isPlayer || hidePlayer)
                && (typeKey == null || !neverHide.contains(typeKey))) {
            Long shown = v.shownAt.get(entityUuid);
            boolean recentlyShown = shown != null && System.currentTimeMillis() - shown <= SHOWN_TTL_MS;
            double dx = x - v.ex, dy = y - v.ey, dz = z - v.ez;
            boolean near = dx * dx + dy * dy + dz * dz <= alwaysShowRadius * alwaysShowRadius;
            hide = !recentlyShown && !near;
        }
        Tracked t = v.tracked.get(entityId);
        if (t == null || !t.uuid.equals(entityUuid)) {
            t = new Tracked(entityId, entityUuid, isPlayer, SHOWN);
            t.lastSeen = System.currentTimeMillis();
            v.tracked.put(entityId, t);
        }
        if (hide) {
            t.mode = PACKET;
            v.blocked.add(entityId);
            spawnsCancelled.incrementAndGet();
            return true;
        }
        t.mode = SHOWN;
        v.blocked.remove(entityId);
        return false;
    }

    /** Netty. Cheap pre-check before parsing an entity packet. */
    public boolean hasBlocked(UUID viewer) {
        View v = views.get(viewer);
        return v != null && !v.blocked.isEmpty();
    }

    /** Netty. True if packets about this entity must not reach the viewer. */
    public boolean isBlocked(UUID viewer, int entityId) {
        View v = views.get(viewer);
        return v != null && !v.blocked.isEmpty() && v.blocked.contains(entityId);
    }

    /** Netty. The server told the client to forget these entities. */
    public void onDestroyPacket(UUID viewer, int[] ids) {
        View v = views.get(viewer);
        if (v == null || ids == null) return;
        for (int id : ids) {
            Tracked t = v.tracked.get(id);
            // BUKKIT-hidden entities are destroyed by our own hideEntity; keep
            // them so they can be shown again.
            if (t != null && t.mode != BUKKIT) {
                v.tracked.remove(id, t);
                v.blocked.remove(id);
            }
        }
    }

    /** Netty. Cheap pre-check before parsing a player-info packet. */
    public boolean hasInfoMarks(UUID viewer) {
        View v = views.get(viewer);
        return v != null && !v.infoRemoveMarks.isEmpty();
    }

    /** Netty. True if a PLAYER_INFO remove for {@code target} was caused by our own re-sync and must be dropped. */
    public boolean consumeInfoRemove(UUID viewer, UUID target) {
        View v = views.get(viewer);
        if (v == null || v.infoRemoveMarks.isEmpty()) return false;
        Long exp = v.infoRemoveMarks.remove(target);
        return exp != null && exp >= System.currentTimeMillis();
    }

    /** Netty. The client dropped every entity (respawn, world change, join). */
    public void onClientReset(UUID viewer) {
        View v = views.get(viewer);
        if (v == null) return;
        // BUKKIT-hidden entities stay hidden on the server side across a
        // respawn, so keep them; everything else is gone from the client.
        v.tracked.values().removeIf(t -> t.mode != BUKKIT);
        v.blocked.clear();
    }

    // ═════════════════════════════════════════════════════════════════════
    //  Periodic check (player's own thread, every 5 ticks)
    // ═════════════════════════════════════════════════════════════════════

    /** Entities farther than this are never tested (beyond any tracking range). */
    private static final double MAX_RANGE = 128;

    /** True if line-of-sight hiding applies to this player right now. Player's thread. */
    public boolean isLosActiveFor(Player player) {
        if (!enabled || !losEnabled || player == null) return false;
        if (disabledWorlds.contains(player.getWorld().getName())) return false;
        if (player.getGameMode() == GameMode.SPECTATOR) return false;
        return !plugin.isEntitiesBypassed(player.getUniqueId());
    }

    private boolean isZoneActiveFor(Player player) {
        // The hidden zone used to be covered by two settings: hide-entities
        // (EntityHider) and pie-chart-protection (spawn cancel). Either keeps it on.
        return (enabled || plugin.isPieChartProtectionEnabled())
                && plugin.isWorldProtected(player.getWorld().getName())
                && (plugin.isProtectionActive(player) || plugin.isSmartFillActiveFor(player));
    }

    /** One candidate entity of a check, sorted nearest first. */
    private static final class Cand {
        final Entity e; final Tracked t; final Location l; final double d2;
        boolean visible;
        Cand(Entity e, Tracked t, Location l, double d2) { this.e = e; this.t = t; this.l = l; this.d2 = d2; }
    }

    /**
     * The one decision for every entity this player could be sent. Must run
     * on the thread that owns the player (Folia: the player's region).
     */
    public void check(Player player) {
        if (player == null || !player.isOnline()) return;
        // Folia: the 5-tick task is scheduled at the player's location, which
        // can be stale by the time it runs (teleport, region border).
        if (PlatformUtil.isFolia() && !PlatformUtil.isOwnedByCurrentRegion(player)) return;
        UUID pid = player.getUniqueId();
        View v = view(pid);
        World world = player.getWorld();
        Location eye = player.getEyeLocation();
        v.ex = eye.getX(); v.ey = eye.getY(); v.ez = eye.getZ();
        v.worldId = world.getUID();

        boolean los = isLosActiveFor(player);
        boolean zone = isZoneActiveFor(player);
        v.losActive = los;
        if (!los && !zone) {
            // Feature off, bypass gained, or nothing to hide here: give back
            // everything we hid (like AntiXrayReveal#release).
            release(player, v);
            return;
        }
        checks.incrementAndGet();
        long now = System.currentTimeMillis();
        if (!los) {
            // IMPROVEMENT (chunk mode, like before the rework): new entities in
            // the hidden zone are already stopped at spawn (onSpawnPacket), so
            // most passes only re-check the ones we hid and show them once
            // Smart Fill reveals their spot. The full scan below only catches
            // visible mobs that walk into a hidden area, so it runs every 5 s.
            if (now - v.lastFullScan < CHUNK_FULL_SCAN_MS) {
                recheckHidden(player, v, world, now);
                return;
            }
            v.lastFullScan = now;
        }
        boolean folia = PlatformUtil.isFolia();
        int minY = world.getMinHeight(), maxY = world.getMaxHeight();
        Vector look = eye.getDirection();
        // Chunk mode casts no rays, so the F5 camera positions aren't needed.
        double[] cams = los ? SightRaycaster.cameras(world, minY, maxY, folia, f5Aware,
                v.ex, v.ey, v.ez, look.getX(), look.getY(), look.getZ()) : null;
        boolean camsStill = cams != null && v.lastCams != null && SightRaycaster.camerasClose(v.lastCams, cams)
                && look.getX() * v.lastLookX + look.getY() * v.lastLookY + look.getZ() * v.lastLookZ
                   >= SightRaycaster.SKIP_TURN_COS;
        if (!camsStill) {
            v.lastCams = cams;
            v.lastLookX = look.getX(); v.lastLookY = look.getY(); v.lastLookZ = look.getZ();
        }

        double range = Math.min(MAX_RANGE, Math.max(2, PlatformUtil.viewDistance(player)) * 16.0);
        int[] tracking = rangesFor(world);
        int maxTrack = 0;
        // IMPROVEMENT: display entities track out to 128 by default, which made
        // every search box twice as wide as any other entity needs. They are
        // left out of the search range; ones further out simply stay visible.
        for (int i = 0; i < tracking.length; i++) {
            if (i != TR_DISPLAY) maxTrack = Math.max(maxTrack, tracking[i]);
        }
        // +8: Paper also counts the range of passengers, and entities right at
        // the edge flicker in and out of tracking.
        range = Math.min(range, maxTrack + 8.0);
        v.hidePlayers = includePlayers && !locatorBarOn(world);
        java.util.Collection<Entity> nearby;
        try {
            if (los) {
                nearby = world.getNearbyEntities(player.getLocation(), range, range, range);
            } else {
                // IMPROVEMENT: in chunk mode only entities in the hidden zone
                // can be hidden (isEntityPositionHidden), so the search box stops
                // at its top (void-y, or ceiling-y with Smart Fill) instead of
                // reaching up into the sky. Entities above it that we hid are
                // shown again by showOutOfRange below.
                int zoneTop = plugin.getVoidY(world.getName());
                if (plugin.isSmartFillActiveFor(player)) {
                    // NEW (follow-terrain): inside nearby mountains the zone reaches higher.
                    Location pl0 = player.getLocation();
                    zoneTop = Math.max(zoneTop, plugin.maxRevealTopNear(world,
                            pl0.getBlockX(), pl0.getBlockZ(), (int) range));
                }
                double top = Math.min(maxY, zoneTop + 2.0);
                if (top <= minY) {
                    release(player, v);
                    return;
                }
                Location pl = player.getLocation();
                nearby = world.getNearbyEntities(new BoundingBox(
                        pl.getX() - range, minY, pl.getZ() - range,
                        pl.getX() + range, top, pl.getZ() + range));
            }
        } catch (Exception ex) {
            plugin.dbg("EntityHider nearby lookup failed for " + player.getName() + ": " + ex.getMessage());
            return;
        }

        Entity ownRoot = root(player);
        List<Cand> cands = new ArrayList<>(nearby.size());
        for (Entity e : nearby) {
            if (e == player) continue;
            // IMPROVEMENT: players that can't be hidden and aren't hidden now
            // need nothing at all, so they skip the rest (most of the cost
            // with many players close together).
            if (!v.hidePlayers && e instanceof Player) {
                Tracked pt = v.tracked.get(e.getEntityId());
                if (pt == null || pt.mode == SHOWN) continue;
            }
            Location l = e.getLocation();
            double dx = l.getX() - v.ex, dy = l.getY() - v.ey, dz = l.getZ() - v.ez;
            // Tracking is horizontal distance only (Paper ChunkMap).
            double tr = tracking[trackingGroup(e)] + 8.0;
            if (dx * dx + dz * dz > tr * tr && e.getPassengers().isEmpty() && e.getVehicle() == null) continue;
            if (folia && !PlatformUtil.isOwnedByCurrentRegion(e)) continue; // keep state, test later
            Tracked t = v.tracked.get(e.getEntityId());
            if (t == null || !t.uuid.equals(e.getUniqueId())) {
                int mode;
                if (v.bukkitHidden.contains(e.getUniqueId())) mode = BUKKIT;
                else if (canSee(player, e)) mode = SHOWN;
                else continue; // hidden by another plugin: not ours to touch
                t = new Tracked(e.getEntityId(), e.getUniqueId(), e instanceof Player, mode);
                t.lastSeen = now;
                v.tracked.put(t.id, t);
            } else if (t.mode == SHOWN && !canSee(player, e)) {
                continue; // another plugin hid it
            }
            t.checkedAt = now;
            if (t.ref == null || t.ref.get() != e) t.ref = new java.lang.ref.WeakReference<>(e);
            cands.add(new Cand(e, t, l, dx * dx + dy * dy + dz * dz));
        }
        // Order only matters for the ray budget.
        if (los) cands.sort((a, b) -> Double.compare(a.d2, b.d2));
        showOutOfRange(player, v, now, los ? OUT_OF_RANGE_SWEEP_MS : 0);

        int[] budget = { rayBudget };
        boolean cut = false;
        long wallsOpenedAt = v.wallsOpenedAt;
        Map<Integer, Boolean> stackVisible = new java.util.HashMap<>();
        boolean anyHidden = false;
        for (Cand c : cands) {
            Entity e = c.e;
            Tracked t = c.t;
            Location l = c.l;
            boolean hideable = !(e instanceof Player) || v.hidePlayers;
            boolean always, zoneHidden;
            if (los) {
                // IMPROVEMENT: players that can't be hidden (include-players off,
                // locator bar on) skip the always-show and hidden-zone checks.
                always = !hideable || alwaysShow(player, v, t, e, ownRoot, now);
                zoneHidden = !always && zone && zoneHidden(player, t, l, now);
            } else {
                // IMPROVEMENT: chunk mode only needs always-show for entities
                // that are in the hidden zone, so the cheap cached zone check
                // runs first.
                zoneHidden = hideable && zone && zoneHidden(player, t, l, now);
                always = !zoneHidden || alwaysShow(player, v, t, e, ownRoot, now);
                if (always) zoneHidden = false;
            }
            boolean visible;
            if (always) {
                visible = true;
            } else if (zoneHidden) {
                visible = false;
            } else if (!los || !hideable || neverHide(e) || isGlowing(player, t, e, now)) {
                visible = true;
            } else if (cut) {
                visible = t.mode == SHOWN; // no budget left: keep the current state
            } else {
                double mx = l.getX() - t.tx, my = l.getY() - t.ty, mz = l.getZ() - t.tz;
                boolean entityStill = mx * mx + my * my + mz * mz < SKIP_ENTITY_MOVE_SQ;
                boolean still = t.tested && camsStill && entityStill && t.lastTest >= wallsOpenedAt;
                if (!still) t.stillStreak = 0;
                // IMPROVEMENT: pairs where nothing moved back off (1, 2, 4,
                // 5 s), visible or hidden. Broken / placed / moved blocks near
                // the viewer reset this (wallsOpenedAt).
                long every = Math.min(STILL_RETEST_MAX_MS, RETEST_MS << Math.min(3, t.stillStreak));
                if (still && now - t.lastTest < every) {
                    visible = t.lastVisible;
                } else {
                    updateVelocity(t, l, now);
                    int before = budget[0];
                    int r = visibility(world, minY, maxY, folia, cams, leadBox(e, t), budget,
                            c.d2 > FAR_POINTS_DIST * FAR_POINTS_DIST);
                    raySteps.addAndGet(before - Math.max(0, budget[0]));
                    if (r == NO_BUDGET) {
                        cut = true;
                        budgetCuts.incrementAndGet();
                        visible = t.mode == SHOWN;
                    } else {
                        visible = r == VISIBLE;
                        if (still && visible == t.lastVisible) t.stillStreak++;
                        else t.stillStreak = 0;
                        t.tested = true;
                        t.lastTest = now;
                        t.lastVisible = visible;
                    }
                }
            }
            c.visible = visible;
            if (!visible) anyHidden = true;
            // Hidden-zone entities hide straight away (old rule); out-of-sight
            // ones only after the grace delay.
            if (zoneHidden) t.lastSeen = 0;
        }

        // Riding: if any part of a vehicle stack is visible, the whole stack is.
        // IMPROVEMENT: only worked out when something is hidden, so the
        // vehicle / passenger lookups are skipped when all is visible.
        if (anyHidden) {
            for (Cand c : cands) {
                if (!c.visible) continue;
                Entity r = root(c.e);
                if (r != c.e || !c.e.getPassengers().isEmpty()) stackVisible.put(r.getEntityId(), Boolean.TRUE);
            }
        }

        for (Cand c : cands) {
            boolean visible = c.visible;
            if (!visible && !stackVisible.isEmpty()
                    && Boolean.TRUE.equals(stackVisible.get(root(c.e).getEntityId()))) {
                visible = true;
            }
            Tracked t = c.t;
            if (visible) {
                t.lastSeen = now;
                show(player, v, t, c.e, now);
            } else if (now - t.lastSeen >= hideDelayMs) {
                hide(player, v, t, c.e);
            }
        }
    }

    /**
     * Chunk mode: shows hidden entities whose spot Smart Fill reveals now (or
     * that left the hidden zone, or came close). Cost grows with the number
     * of hidden entities only, not with everything nearby.
     */
    private void recheckHidden(Player player, View v, World world, long now) {
        if (v.blocked.isEmpty() && v.bukkitHidden.isEmpty()) return;
        // A new fill or sight sweep: the cached zone results are stale.
        SmartFillReveal reveal = plugin.getSmartFillReveal();
        int version = reveal != null ? reveal.getRevealVersion(player.getUniqueId()) : 0;
        boolean revealChanged = version != v.revealVersion;
        v.revealVersion = version;
        Entity ownRoot = root(player);
        for (Tracked t : v.tracked.values()) {
            if (t.mode == SHOWN) continue;
            // IMPROVEMENT: a hidden entity that stayed in its block, with no new
            // fill, no wall opened and the viewer within a block of where it
            // was, can't have become visible. Skips the validity, distance and
            // flag checks for it (most of the cost with many hidden mobs).
            if (!revealChanged && t.rcAt != 0 && now - t.rcAt < STILL_RECHECK_MS
                    && v.wallsOpenedAt < t.rcAt && !v.combat.containsKey(t.uuid)) {
                double mx = v.ex - t.rcEx, my = v.ey - t.rcEy, mz = v.ez - t.rcEz;
                Entity q = t.ref != null ? t.ref.get() : null;
                if (q != null && mx * mx + my * my + mz * mz < 1.0
                        && PlatformUtil.isOwnedByCurrentRegion(q)) {
                    Location ql = q.getLocation();
                    if (floor(ql.getX()) == t.zx && floor(ql.getY()) == t.zy && floor(ql.getZ()) == t.zz) continue;
                }
            }
            Entity e = t.ref != null ? t.ref.get() : null;
            if (e == null) e = Bukkit.getEntity(t.uuid);
            if (e == null) { forget(v, t); continue; }
            if (!PlatformUtil.isOwnedByCurrentRegion(e)) continue; // another region: next pass
            // IMPROVEMENT: isValid was the biggest cost here with thousands of
            // hidden mobs. While an entity stays hidden it's checked once a
            // second; anything about to be shown is always checked first, so
            // a stale reference is never shown.
            boolean validated = now - t.validAt >= VALID_RECHECK_MS;
            if (validated) {
                e = validEntity(v, t, e);
                if (e == null) continue;
                t.validAt = now;
            }
            if (revealChanged) t.zoneAt = 0;
            boolean keep = keepHidden(player, v, t, e, world, ownRoot, now);
            if (!keep && !validated) {
                Entity fresh = validEntity(v, t, e);
                if (fresh == null) continue;
                t.validAt = now;
                if (fresh != e) {
                    e = fresh;
                    keep = keepHidden(player, v, t, e, world, ownRoot, now);
                }
            }
            if (!keep) {
                t.rcAt = 0;
                t.lastSeen = now;
                show(player, v, t, e, now);
            } else {
                t.rcAt = now;
                t.rcEx = v.ex; t.rcEy = v.ey; t.rcEz = v.ez;
            }
        }
    }

    /**
     * The entity to use for t, or null to skip it this pass (gone: forgotten;
     * stale reference whose replacement is in another region: next pass).
     * The kept reference goes stale when a world change re-creates the entity.
     */
    private static Entity validEntity(View v, Tracked t, Entity e) {
        if (!e.isValid()) {
            Entity fresh = Bukkit.getEntity(t.uuid);
            if (fresh == null || fresh == e || !PlatformUtil.isOwnedByCurrentRegion(fresh) || !fresh.isValid()) {
                if (fresh == null || fresh == e) forget(v, t);
                return null;
            }
            e = fresh;
        }
        if (t.ref == null || t.ref.get() != e) t.ref = new java.lang.ref.WeakReference<>(e);
        return e;
    }

    /** Chunk mode: still in the hidden zone and nothing makes it always shown. */
    private boolean keepHidden(Player player, View v, Tracked t, Entity e, World world, Entity ownRoot, long now) {
        if (!e.getWorld().equals(world) || (t.isPlayer && !v.hidePlayers)) return false;
        if (!zoneHidden(player, t, e.getLocation(), now)) return false;
        return !alwaysShow(player, v, t, e, ownRoot, now);
    }

    /**
     * Entities we hid that check() no longer finds (past the search range,
     * e.g. far display entities) are shown again, so nothing stays hidden
     * without being re-tested. Raycast mode runs this at most every
     * OUT_OF_RANGE_SWEEP_MS; chunk mode every check (gap 0), so a mob that
     * climbs above void-y shows straight away.
     */
    private void showOutOfRange(Player player, View v, long now, long gap) {
        if (gap > 0 && now - v.lastSweep < gap) return;
        v.lastSweep = now;
        for (Tracked t : v.tracked.values()) {
            if (t.mode == SHOWN || t.checkedAt >= now - gap) continue;
            Entity e = Bukkit.getEntity(t.uuid);
            if (e == null) { forget(v, t); continue; }
            if (!PlatformUtil.isOwnedByCurrentRegion(e)) continue;
            if (!e.isValid()) { forget(v, t); continue; }
            t.lastSeen = now;
            show(player, v, t, e, now);
        }
    }

    /** Netty-safe. A block near these coordinates was broken or moved: hidden results there may be stale. */
    public void onWallsOpened(UUID worldId, double x, double y, double z) {
        long now = System.currentTimeMillis();
        for (View v : views.values()) {
            if (!worldId.equals(v.worldId)) continue;
            double dx = x - v.ex, dy = y - v.ey, dz = z - v.ez;
            if (dx * dx + dy * dy + dz * dz <= MAX_RANGE * MAX_RANGE) v.wallsOpenedAt = now;
        }
    }

    /**
     * IMPROVEMENT: hidden-zone result per entity, reused while it stays in
     * the same block (at most ZONE_CACHE_MS), instead of the full palette and
     * reveal lookup on every check.
     */
    private boolean zoneHidden(Player player, Tracked t, Location l, long now) {
        int bx = floor(l.getX()), by = floor(l.getY()), bz = floor(l.getZ());
        if (t.zoneAt != 0 && now - t.zoneAt < ZONE_CACHE_MS
                && bx == t.zx && by == t.zy && bz == t.zz) return t.zoneHidden;
        boolean hidden = plugin.isEntityInHiddenZone(player, bx, by, bz);
        t.zx = bx; t.zy = by; t.zz = bz;
        t.zoneAt = now;
        t.zoneHidden = hidden;
        return hidden;
    }

    /** Always shown: close by, same vehicle stack, leash, own projectile, recent combat. */
    private boolean alwaysShow(Player player, View v, Tracked t, Entity e, Entity ownRoot, long now) {
        if (root(e) == ownRoot) return true;
        double d2 = 0;
        try {
            BoundingBox box = e.getBoundingBox();
            double cx = Math.max(box.getMinX(), Math.min(v.ex, box.getMaxX()));
            double cy = Math.max(box.getMinY(), Math.min(v.ey, box.getMaxY()));
            double cz = Math.max(box.getMinZ(), Math.min(v.ez, box.getMaxZ()));
            double dx = cx - v.ex, dy = cy - v.ey, dz = cz - v.ez;
            d2 = dx * dx + dy * dy + dz * dz;
            if (d2 <= alwaysShowRadius * alwaysShowRadius) return true;
        } catch (Exception ignored) {}
        // IMPROVEMENT: only mobs close enough to be on the viewer's leash
        // need the leash lookup (most of the cost with thousands of hidden
        // mobs). Glowing is checked on its own in raycast mode.
        if (e instanceof LivingEntity && d2 <= LEASH_MAX * LEASH_MAX) {
            refreshFlags(player, t, e, now);
            if (t.leashed) return true;
        }
        if (e instanceof Projectile) {
            ProjectileSource s = ((Projectile) e).getShooter();
            if (s == player) return true;
        }
        Long hit = v.combat.get(e.getUniqueId());
        return hit != null && now - hit <= COMBAT_MS;
    }

    private boolean neverHide(Entity e) {
        try {
            return neverHide.contains(e.getType().getKey().getKey());
        } catch (Exception ex) {
            return false; // EntityType.UNKNOWN has no key
        }
    }

    /** Glowing entities are visible through walls in vanilla, hiding them would be a giveaway. */
    private static boolean isGlowing(Player player, Tracked t, Entity e, long now) {
        refreshFlags(player, t, e, now);
        return t.glowing;
    }

    /**
     * IMPROVEMENT: glowing and leash checks ran for every entity on every
     * check (Paper looks up potion effects inside isGlowing). The result is
     * now kept for FLAG_CACHE_MS, so a mob that starts glowing or gets
     * leashed shows up to 1 s later.
     */
    private static void refreshFlags(Player player, Tracked t, Entity e, long now) {
        if (t.flagsAt != 0 && now - t.flagsAt < FLAG_CACHE_MS) return;
        t.flagsAt = now;
        // IMPROVEMENT: the glowing effect sets the same shared flag, so the
        // extra hasPotionEffect lookup (a slow-tick hotspot) isn't needed.
        try {
            t.glowing = e.isGlowing();
        } catch (Exception ex) {
            t.glowing = false;
        }
        boolean leashed = false;
        if (e instanceof LivingEntity) {
            LivingEntity le = (LivingEntity) e;
            try {
                leashed = le.isLeashed() && le.getLeashHolder() == player;
            } catch (Exception ignored) {}
        }
        t.leashed = leashed;
    }

    private static boolean canSee(Player player, Entity e) {
        try {
            return player.canSee(e);
        } catch (Throwable t) {
            return true;
        }
    }

    private static Entity root(Entity e) {
        Entity r = e;
        for (int i = 0; i < 16; i++) {
            Entity up = r.getVehicle();
            if (up == null) break;
            r = up;
        }
        return r;
    }

    private static void updateVelocity(Tracked t, Location l, long now) {
        long dt = now - t.tAt;
        if (t.tAt != 0 && dt > 0 && dt <= 2000) {
            double s = 1000.0 / dt;
            t.vx = (l.getX() - t.tx) * s; t.vy = (l.getY() - t.ty) * s; t.vz = (l.getZ() - t.tz) * s;
        } else {
            t.vx = t.vy = t.vz = 0;
        }
        t.tx = l.getX(); t.ty = l.getY(); t.tz = l.getZ();
        t.tAt = now;
    }

    /** Bounding box grown toward where the entity will be in LEAD_SECONDS, so it shows before it rounds a corner. */
    private static BoundingBox leadBox(Entity e, Tracked t) {
        BoundingBox box = e.getBoundingBox().clone();
        double lx = cap(t.vx * LEAD_SECONDS), ly = cap(t.vy * LEAD_SECONDS), lz = cap(t.vz * LEAD_SECONDS);
        if (lx != 0 || ly != 0 || lz != 0) box.expandDirectional(lx, ly, lz);
        return box;
    }

    private static double cap(double d) {
        return Math.max(-LEAD_MAX, Math.min(LEAD_MAX, d));
    }

    // ── Ray tests ─────────────────────────────────────────────────────────

    /**
     * VISIBLE if any camera reaches any sample point of the box (center, top
     * and bottom center, 8 inset corners). Small boxes (items, item frames,
     * paintings) still get the full set, the points just sit close together.
     */
    private static int visibility(World world, int minY, int maxY, boolean folia, double[] cams,
                                  BoundingBox box, int[] budget, boolean far) {
        double x0 = box.getMinX(), y0 = box.getMinY(), z0 = box.getMinZ();
        double x1 = box.getMaxX(), y1 = box.getMaxY(), z1 = box.getMaxZ();
        double ix = (x1 - x0) * CORNER_INSET, iy = (y1 - y0) * CORNER_INSET, iz = (z1 - z0) * CORNER_INSET;
        double cx = (x0 + x1) * 0.5, cy = (y0 + y1) * 0.5, cz = (z0 + z1) * 0.5;
        for (int c = 0; c < cams.length; c += 3) {
            double ox = cams[c], oy = cams[c + 1], oz = cams[c + 2];
            if (ox >= x0 && ox <= x1 && oy >= y0 && oy <= y1 && oz >= z0 && oz <= z1) return VISIBLE;
            // IMPROVEMENT: the eye tests all 11 points. The F5 cameras are an
            // approximation anyway, so they test centre, top and bottom only:
            // a hidden pair costs 17 rays instead of 33. Past FAR_POINTS_DIST
            // the eye tests 5 (centre, top, bottom, two opposite corners):
            // the box is small on screen there, so the extra corners rarely matter.
            int points = c == 0 ? (far ? 5 : 11) : 3;
            for (int s = 0; s < points; s++) {
                double px, py, pz;
                if (s == 0) { px = cx; py = cy; pz = cz; }
                else if (s == 1) { px = cx; py = y1 - iy; pz = cz; }
                else if (s == 2) { px = cx; py = y0 + iy; pz = cz; }
                else {
                    int k = far ? (s == 3 ? 0 : 7) : s - 3;
                    px = (k & 1) != 0 ? x1 - ix : x0 + ix;
                    py = (k & 2) != 0 ? y1 - iy : y0 + iy;
                    pz = (k & 4) != 0 ? z1 - iz : z0 + iz;
                }
                int r = ray(world, minY, maxY, folia, ox, oy, oz, px, py, pz, budget);
                if (r != HIDDEN) return r;
            }
        }
        return HIDDEN;
    }

    private static int ray(World world, int minY, int maxY, boolean folia,
                           double ox, double oy, double oz, double tx, double ty, double tz, int[] budget) {
        double dx = tx - ox, dy = ty - oy, dz = tz - oz;
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 1e-6) return VISIBLE;
        dx /= len; dy /= len; dz /= len;

        // Same as AntiXrayReveal#ray: a camera above/below the world looks
        // through open air until the ray enters it.
        double top = maxY - 1e-4, bottom = minY + 1e-4;
        if (oy > top || oy < bottom) {
            if (ty > top || ty < bottom) return VISIBLE; // both outside the world: open air
            double edge = oy > top ? top : bottom;
            if (dy == 0 || (oy > top) == (dy > 0)) return HIDDEN;
            double t = (edge - oy) / dy;
            if (t >= len) return VISIBLE;
            ox += dx * t; oy = edge; oz += dz * t;
            len -= t;
        }
        final int bx = floor(tx), by = floor(ty), bz = floor(tz);
        final double end = len;
        boolean[] reached = { false };
        int r = SightRaycaster.walk(world, minY, maxY, folia, ox, oy, oz, dx, dy, dz,
                len + 0.01, budget, (x, y, z, type, blocks, t) -> {
                    // The target's own cell counts as reached: an entity half
                    // inside a wall is still visible from its open side.
                    if ((x == bx && y == by && z == bz) || t >= end) { reached[0] = true; return false; }
                    return !blocks || t <= 0; // the camera's own cell never blocks
                });
        if (reached[0] || r == SightRaycaster.REACHED_END) return VISIBLE;
        if (r == SightRaycaster.OUT_OF_BUDGET) return NO_BUDGET;
        // Unloaded chunk / other Folia region / world edge: we can't tell, show it.
        if (r == SightRaycaster.UNREADABLE) return VISIBLE;
        return HIDDEN;
    }

    private static int floor(double v) {
        int i = (int) v;
        return v < i ? i - 1 : i;
    }

    // ── Hide / show (player's thread) ─────────────────────────────────────

    private void hide(Player player, View v, Tracked t, Entity e) {
        if (t.mode != SHOWN || !plugin.isEnabled()) return;
        v.shownAt.remove(t.uuid);
        if (t.isPlayer) {
            // Packet level: canSee stays true for every other plugin.
            t.mode = PACKET;
            v.blocked.add(t.id);
            try {
                PacketEvents.getAPI().getPlayerManager().sendPacketSilently(player,
                        new WrapperPlayServerDestroyEntities(t.id));
            } catch (Exception ex) {
                t.mode = SHOWN;
                v.blocked.remove(t.id);
                plugin.dbg("EntityHider destroy packet failed for " + player.getName() + ": " + ex.getMessage());
            }
            return;
        }
        // Mark first: hideEntity sends DESTROY_ENTITIES straight away, and
        // onDestroyPacket must know this one is ours.
        t.mode = BUKKIT;
        v.bukkitHidden.add(t.uuid);
        try {
            player.hideEntity(plugin, e);
        } catch (Exception ex) {
            t.mode = SHOWN;
            v.bukkitHidden.remove(t.uuid);
            plugin.getLogger().warning("[FPAntiFreeCam] hideEntity failed: " + ex.getMessage());
        }
    }

    private void show(Player player, View v, Tracked t, Entity e, long now) {
        // IMPROVEMENT: shownAt is only read with raycast on (spawn check), so
        // chunk mode skips the map write for every visible entity.
        if (v.losActive || t.mode != SHOWN) v.shownAt.put(t.uuid, now);
        if (t.mode == SHOWN || !plugin.isEnabled()) return;
        int was = t.mode;
        // SHOWN before the calls: the spawn packet they cause must pass the
        // Netty spawn check (shownAt above), and the destroy must not be kept.
        t.mode = SHOWN;
        v.blocked.remove(t.id);
        try {
            if (was == BUKKIT) {
                v.bukkitHidden.remove(t.uuid);
                player.showEntity(plugin, e);
            } else {
                // PACKET: the client doesn't have the entity but the server
                // thinks it does. Hide+show makes the tracker re-send the
                // spawn with metadata, equipment and passengers.
                if (t.isPlayer) v.infoRemoveMarks.put(t.uuid, now + INFO_MARK_MS);
                player.hideEntity(plugin, e);
                player.showEntity(plugin, e);
            }
        } catch (Exception ex) {
            plugin.getLogger().warning("[FPAntiFreeCam] showEntity failed: " + ex.getMessage());
        }
    }

    /** Shows everything we hid from this player. Entities in another Folia region are retried on the next check. */
    private void release(Player player, View v) {
        if (v.tracked.isEmpty() && v.bukkitHidden.isEmpty()) return;
        long now = System.currentTimeMillis();
        for (Tracked t : new ArrayList<>(v.tracked.values())) {
            if (t.mode == SHOWN) continue;
            Entity e = Bukkit.getEntity(t.uuid);
            if (e == null) { forget(v, t); continue; }
            if (!PlatformUtil.isOwnedByCurrentRegion(e)) continue;
            if (!e.isValid()) { forget(v, t); continue; }
            show(player, v, t, e, now);
        }
        // Hidden through Bukkit but no longer tracked by id (e.g. after a world change).
        for (UUID id : new ArrayList<>(v.bukkitHidden)) {
            Entity e = Bukkit.getEntity(id);
            if (e == null) { v.bukkitHidden.remove(id); continue; }
            if (!PlatformUtil.isOwnedByCurrentRegion(e)) continue;
            v.bukkitHidden.remove(id);
            try {
                if (plugin.isEnabled()) player.showEntity(plugin, e);
            } catch (Exception ignored) {}
        }
    }

    private static void forget(View v, Tracked t) {
        v.tracked.remove(t.id, t);
        v.blocked.remove(t.id);
        v.bukkitHidden.remove(t.uuid);
    }

    // ── Events ────────────────────────────────────────────────────────────

    // Broken / placed / exploded / pushed blocks can change a line of sight.
    // Pairs nearby are re-tested on the next check instead of after backoff.
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(org.bukkit.event.block.BlockBreakEvent event) {
        wallsOpened(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplode(org.bukkit.event.block.BlockExplodeEvent event) {
        wallsOpened(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityExplode(org.bukkit.event.entity.EntityExplodeEvent event) {
        Location l = event.getLocation();
        if (l.getWorld() != null) onWallsOpened(l.getWorld().getUID(), l.getX(), l.getY(), l.getZ());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonExtend(org.bukkit.event.block.BlockPistonExtendEvent event) {
        wallsOpened(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonRetract(org.bukkit.event.block.BlockPistonRetractEvent event) {
        wallsOpened(event.getBlock());
    }

    // NEW: visible pairs back off too, so a placed block (new wall) re-tests them.
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlace(org.bukkit.event.block.BlockPlaceEvent event) {
        wallsOpened(event.getBlock());
    }

    private void wallsOpened(org.bukkit.block.Block b) {
        if (!enabled || views.isEmpty()) return;
        onWallsOpened(b.getWorld().getUID(), b.getX() + 0.5, b.getY() + 0.5, b.getZ() + 0.5);
    }

    /** Anything that hits the player, or that the player hits, stays shown for COMBAT_MS. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!enabled) return;
        Entity damager = event.getDamager();
        Entity attacker = damager;
        if (damager instanceof Projectile) {
            ProjectileSource s = ((Projectile) damager).getShooter();
            if (s instanceof Entity) attacker = (Entity) s;
        }
        Entity victim = event.getEntity();
        long now = System.currentTimeMillis();
        if (victim instanceof Player) {
            View v = view(victim.getUniqueId());
            v.combat.put(attacker.getUniqueId(), now);
            if (damager != attacker) v.combat.put(damager.getUniqueId(), now);
        }
        if (attacker instanceof Player && attacker != victim) {
            view(attacker.getUniqueId()).combat.put(victim.getUniqueId(), now);
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────

    /** Runs a check right away on the player's own thread (join, teleport, respawn, world change, state change). */
    public void updateFor(Player player) {
        if (player == null) return;
        PlatformUtil.runForEntity(plugin, player, () -> {
            if (player.isOnline()) check(player);
        });
    }

    public void refreshAll() {
        for (Player p : Bukkit.getOnlinePlayers()) updateFor(p);
    }

    /** Returns true if we currently hide this entity from the player (either way). */
    public boolean isHiddenFrom(Player player, Entity entity) {
        View v = views.get(player.getUniqueId());
        if (v == null) return false;
        if (v.bukkitHidden.contains(entity.getUniqueId())) return true;
        Tracked t = v.tracked.get(entity.getEntityId());
        return t != null && t.mode != SHOWN && t.uuid.equals(entity.getUniqueId());
    }

    public void cleanupPlayer(UUID id) {
        views.remove(id);
        // BUGFIX: a pending "drop the tab-list remove we caused" mark must not
        // swallow the real remove sent when this player quits. PlayerQuitEvent
        // fires before the server sends that packet.
        for (View v : views.values()) {
            v.infoRemoveMarks.remove(id);
            v.shownAt.remove(id);
            v.combat.remove(id);
        }
    }

    /** An entity left the world (Paper EntityRemoveFromWorldEvent). Any thread. */
    public void onEntityRemoved(int entityId, UUID uuid) {
        for (View v : views.values()) {
            Tracked t = v.tracked.get(entityId);
            if (t != null && t.uuid.equals(uuid)) forget(v, t);
            v.bukkitHidden.remove(uuid);
            v.shownAt.remove(uuid);
            v.combat.remove(uuid);
        }
    }

    /** Every 60 s: drops expired timers and entries for entities that are gone. */
    public void periodicCleanup() {
        long now = System.currentTimeMillis();
        for (View v : views.values()) {
            v.shownAt.values().removeIf(at -> now - at > SHOWN_TTL_MS * 4);
            v.combat.values().removeIf(at -> now - at > COMBAT_MS);
            v.infoRemoveMarks.values().removeIf(exp -> exp < now);
            // SHOWN entries the client lost without a destroy we saw.
            v.tracked.values().removeIf(t -> t.mode == SHOWN && now - Math.max(t.lastSeen, t.lastTest) > 60_000);
            for (Iterator<UUID> it = v.bukkitHidden.iterator(); it.hasNext(); ) {
                // Bukkit#getEntity only looks the UUID up, it doesn't touch entity state.
                if (Bukkit.getEntity(it.next()) == null) it.remove();
            }
        }
    }

    // ── Stats ─────────────────────────────────────────────────────────────

    /** {players hidden, other entities hidden}, summed over all viewers. */
    public int[] hiddenPairs() {
        int players = 0, other = 0;
        for (View v : views.values()) {
            for (Tracked t : v.tracked.values()) {
                if (t.mode == SHOWN) continue;
                if (t.isPlayer) players++; else other++;
            }
        }
        return new int[]{ players, other };
    }

    public String stats() {
        int[] h = hiddenPairs();
        return "hidden-pairs:" + (h[0] + h[1]);
    }

    /** NEW: readable /fpac stats rows: what's hidden now, then load since enable. */
    public String[] statsRows() {
        int[] h = hiddenPairs();
        String hidden = "&f" + h[0] + " &7players &8· &f" + h[1] + " &7other &8· &f"
                + spawnsCancelled.get() + " &7spawns cancelled"
                + (enabled && losEnabled ? " &8· &7mode &fraycast" : " &8· &7mode &fchunk");
        long ticks = Math.max(1, (System.currentTimeMillis() - statsSince) / 50);
        String load = "&f" + String.format(Locale.ROOT, "%.0f", raySteps.get() / (double) ticks)
                + " &7steps/tick &8· &f" + String.format(Locale.ROOT, "%.1f", checks.get() / (double) ticks)
                + " &7checks/tick &8· &f" + budgetCuts.get() + " &7budget cuts &8· &7budget &f" + rayBudget
                + " &8· &7fast reads " + (FastBlockReader.AVAILABLE ? "&aon" : "&coff");
        return new String[]{ hidden, load };
    }
}
