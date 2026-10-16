package me.fleoxxzy.FPAntiFreeCam;

import com.github.retrooper.packetevents.protocol.world.chunk.BaseChunk;
import com.github.retrooper.packetevents.protocol.world.chunk.impl.v_1_18.Chunk_v1_18;
import com.github.retrooper.packetevents.protocol.world.chunk.palette.DataPalette;
import com.github.retrooper.packetevents.protocol.world.chunk.palette.GlobalPalette;
import com.github.retrooper.packetevents.protocol.world.chunk.palette.Palette;
import com.github.retrooper.packetevents.protocol.world.states.WrappedBlockState;
import com.github.retrooper.packetevents.protocol.world.states.type.StateType;
import com.github.retrooper.packetevents.protocol.world.states.type.StateTypes;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerMultiBlockChange;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * NEW: per-world, per-ore Anti-Xray packet obfuscation.
 *
 * <p>Runs on the Netty thread as the last step of the CHUNK_DATA /
 * BLOCK_CHANGE / MULTI_BLOCK_CHANGE pass (after void-y hiding and Smart
 * Fill). Every configured ore inside the world's Y range is rewritten to a
 * natural block picked from the ore's own neighbours in the packet, unless
 * it's in this player's revealed set. Nothing here touches the Bukkit world
 * API: all block data comes from the packet itself, and world metadata
 * (min height, dimension) is cached up front.
 *
 * <p>While masking, every hidden ore is recorded per player and chunk with
 * its real id, the fake id it was given and whether it has an exposed face.
 * {@link AntiXrayReveal} uses the exposed ones as raycast candidates; the
 * fake id is kept so a later single-block update for the same position is
 * given the exact same fake block (no flicker between e.g. tuff and stone).
 */
public final class AntiXrayEngine {

    private final FPAntiFreeCam plugin;

    // ── Config ────────────────────────────────────────────────────────────
    private volatile boolean enabled = false;
    private volatile int     alwaysRevealRadius = 3;
    private volatile int     maxRayDistance = 64;
    private volatile int     rayBudget = 20_000;
    private volatile boolean f5Aware = true;
    private volatile int     forgetDistance = 96;
    private volatile Map<String, WorldSettings> worldSettings = Collections.emptyMap();
    private volatile WorldSettings defaultSettings = WorldSettings.DISABLED;

    /**
     * Single block updates this close to the player's eye are always sent
     * real. Covers a player placing an ore block or lighting redstone ore
     * by hand (reach is ~5 blocks), so they never see their own block turn
     * into stone while waiting for the next visibility check.
     */
    private static final int CHANGE_REVEAL_RADIUS = 6;

    /** Natural replacement candidates, in tie-break priority order. */
    private static final String[] NATURAL_KEYS = {
            "stone", "deepslate", "tuff", "granite", "diorite", "andesite", "calcite",
            "netherrack", "basalt", "blackstone", "end_stone"
    };
    private static final int K_STONE = 0, K_DEEPSLATE = 1, K_NETHERRACK = 7, K_END_STONE = 10;

    private static final List<String> DEFAULT_ORES = Arrays.asList(
            "coal_ore", "deepslate_coal_ore", "copper_ore", "deepslate_copper_ore",
            "iron_ore", "deepslate_iron_ore", "gold_ore", "deepslate_gold_ore",
            "redstone_ore", "deepslate_redstone_ore", "lapis_ore", "deepslate_lapis_ore",
            "diamond_ore", "deepslate_diamond_ore", "emerald_ore", "deepslate_emerald_ore",
            "raw_iron_block", "raw_copper_block");

    static final int ENV_OVERWORLD = 0, ENV_NETHER = 1, ENV_END = 2;

    // ── Global-id lookup tables (rebuilt on load, read-only afterwards) ──
    /** 0 = not a natural block, otherwise NATURAL_KEYS index + 1. */
    private volatile byte[]  naturalKind = new byte[0];
    /** Ids whose block stops a sight ray (same rule as SightRaycaster.blocksSight). */
    private volatile BitSet  occluding = new BitSet();
    /** Default-state id per NATURAL_KEYS entry, 0 if that block doesn't exist on this version. */
    private volatile int[]   naturalDefaultId = new int[NATURAL_KEYS.length];
    private volatile boolean tablesReady = false;

    private final Map<String, WorldMeta> worldMeta = new ConcurrentHashMap<>();
    private final Map<UUID, PlayerXray>  players   = new ConcurrentHashMap<>();

    // ── Stats ─────────────────────────────────────────────────────────────
    final AtomicLong oresHidden   = new AtomicLong();
    final AtomicLong oresRevealed = new AtomicLong();
    final AtomicLong raySteps     = new AtomicLong();
    final AtomicLong rayChecks    = new AtomicLong();
    private volatile long statsSince = System.currentTimeMillis();

    public AntiXrayEngine(FPAntiFreeCam plugin) {
        this.plugin = plugin;
    }

    // ═════════════════════════════════════════════════════════════════════
    //  Config / tables
    // ═════════════════════════════════════════════════════════════════════

    static final class WorldSettings {
        static final WorldSettings DISABLED = new WorldSettings(false, 0, -1, Collections.emptySet(), new BitSet());

        final boolean enabled;
        final int minY, maxY;
        final Set<String> oreKeys;
        final BitSet hiddenIds;

        WorldSettings(boolean enabled, int minY, int maxY, Set<String> oreKeys, BitSet hiddenIds) {
            this.enabled = enabled;
            this.minY = minY;
            this.maxY = maxY;
            this.oreKeys = oreKeys;
            this.hiddenIds = hiddenIds;
        }

        boolean inRange(int y) { return y >= minY && y <= maxY; }
    }

    static final class WorldMeta {
        final int minY, maxY, env;
        WorldMeta(int minY, int maxY, int env) { this.minY = minY; this.maxY = maxY; this.env = env; }
    }

    /** Main thread. Reads config and rebuilds id tables. Per-player state is kept (see below). */
    public void loadSettings() {
        FileConfiguration cfg = plugin.getConfig();
        boolean wantEnabled = cfg.getBoolean("anti-xray.enabled", true);
        alwaysRevealRadius = Math.max(0, Math.min(16, cfg.getInt("anti-xray.always-reveal-radius", 3)));
        maxRayDistance = Math.max(8, Math.min(256, cfg.getInt("anti-xray.max-ray-distance", 64)));
        rayBudget = Math.max(1_000, Math.min(500_000, cfg.getInt("anti-xray.ray-budget", 20_000)));
        f5Aware = cfg.getBoolean("anti-xray.f5-aware", true);
        forgetDistance = Math.max(16, Math.min(512, cfg.getInt("anti-xray.forget-distance", 96)));

        // BUGFIX: per-player state is deliberately NOT cleared here. Clients
        // still hold the fake blocks they were sent before the reload; the
        // recorded real ids are what lets AntiXrayReveal put the real blocks
        // back (on a later reveal, or all at once if the feature or this
        // player is now off) without re-sending any chunks.
        refreshWorldMeta();

        if (!wantEnabled) {
            enabled = false;
            worldSettings = Collections.emptyMap();
            defaultSettings = WorldSettings.DISABLED;
            return;
        }

        // Each id is resolved to its registry key once; ore and natural
        // lookups on Netty are then a plain BitSet / array read.
        String[] keysById;
        try {
            keysById = scanStateKeys();
        } catch (Throwable t) {
            // NEW: fail closed - if PacketEvents' mappings can't be read on
            // this version, turn the feature off instead of masking blindly.
            plugin.getLogger().warning("[FPAntiFreeCam] Anti-Xray disabled: could not read block mappings ("
                    + t.getClass().getSimpleName() + ": " + t.getMessage() + ")");
            enabled = false;
            worldSettings = Collections.emptyMap();
            defaultSettings = WorldSettings.DISABLED;
            return;
        }
        buildTables(keysById);

        ConfigurationSection worlds = cfg.getConfigurationSection("anti-xray.worlds");
        ConfigurationSection defSec = worlds != null ? worlds.getConfigurationSection("default") : null;
        WorldSettings def = parseWorld(defSec, null, keysById);
        Map<String, WorldSettings> map = new HashMap<>();
        if (worlds != null) {
            for (String name : worlds.getKeys(false)) {
                if (name.equalsIgnoreCase("default")) continue;
                map.put(name, parseWorld(worlds.getConfigurationSection(name), def, keysById));
            }
        }
        defaultSettings = def;
        worldSettings = map;
        statsSince = System.currentTimeMillis();
        enabled = true;
        plugin.dbg("Anti-Xray loaded: default ores=" + def.oreKeys.size() + " ids=" + def.hiddenIds.cardinality()
                + " worlds=" + map.keySet());
    }

    private WorldSettings parseWorld(ConfigurationSection sec, WorldSettings parent, String[] keysById) {
        boolean en = parent == null || parent.enabled;
        int minY = parent != null ? parent.minY : -64;
        int maxY = parent != null ? parent.maxY : 320;
        Set<String> keys = parent != null ? parent.oreKeys : normalizeKeys(DEFAULT_ORES);
        if (sec != null) {
            if (sec.contains("enabled")) en = sec.getBoolean("enabled");
            if (sec.contains("min-y")) minY = sec.getInt("min-y");
            if (sec.contains("max-y")) maxY = sec.getInt("max-y");
            if (sec.isList("hidden-ores")) keys = normalizeKeys(sec.getStringList("hidden-ores"));
        }
        BitSet ids = new BitSet(keysById.length);
        Set<String> found = new HashSet<>();
        for (int id = 0; id < keysById.length; id++) {
            String k = keysById[id];
            if (k != null && keys.contains(k)) {
                ids.set(id);
                found.add(k);
            }
        }
        // Registry-key matching: unknown keys (block missing on this
        // version, or a typo) are skipped instead of failing the load.
        for (String k : keys) {
            if (!found.contains(k)) plugin.dbg("Anti-Xray: unknown ore key '" + k + "' skipped");
        }
        return new WorldSettings(en, minY, maxY, Collections.unmodifiableSet(keys), ids);
    }

    private static Set<String> normalizeKeys(List<String> raw) {
        Set<String> out = new HashSet<>();
        for (String s : raw) {
            if (s == null) continue;
            String k = s.trim().toLowerCase(Locale.ROOT);
            int idx = k.indexOf(':');
            if (idx >= 0) k = k.substring(idx + 1);
            if (!k.isEmpty()) out.add(k);
        }
        return out;
    }

    /** Registry key for every global block-state id the server knows (index = id). */
    private static String[] scanStateKeys() {
        List<String> keys = new ArrayList<>(32_768);
        keys.add("air");
        int missing = 0;
        Map<StateType, String> keyCache = new HashMap<>();
        for (int id = 1; id < 1 << 20 && missing < 4096; id++) {
            WrappedBlockState st = WrappedBlockState.getByGlobalId(id, false);
            StateType type = st != null ? st.getType() : null;
            // Unknown ids come back as the shared AIR state; only id 0 is real air.
            if (type == null || type == StateTypes.AIR) {
                missing++;
                keys.add(null);
                continue;
            }
            missing = 0;
            keys.add(keyCache.computeIfAbsent(type, SmartFillEngine::registryKey));
        }
        int last = keys.size() - 1;
        while (last > 0 && keys.get(last) == null) last--;
        return keys.subList(0, last + 1).toArray(new String[0]);
    }

    private void buildTables(String[] keysById) {
        byte[] kinds = new byte[keysById.length];
        BitSet occ = new BitSet(keysById.length);
        Map<String, Integer> naturalIdx = new HashMap<>();
        for (int i = 0; i < NATURAL_KEYS.length; i++) naturalIdx.put(NATURAL_KEYS[i], i);
        Map<String, Boolean> occByKey = new HashMap<>();

        for (int id = 0; id < keysById.length; id++) {
            String k = keysById[id];
            if (k == null) continue;
            Integer n = naturalIdx.get(k);
            if (n != null) kinds[id] = (byte) (n + 1);
            if (occByKey.computeIfAbsent(k, AntiXrayEngine::isOccludingKey)) occ.set(id);
        }

        int[] defaults = new int[NATURAL_KEYS.length];
        for (int i = 0; i < NATURAL_KEYS.length; i++) {
            try {
                WrappedBlockState st = WrappedBlockState.getByString("minecraft:" + NATURAL_KEYS[i]);
                defaults[i] = st != null && st.getType() != StateTypes.AIR ? st.getGlobalId() : 0;
            } catch (Throwable t) {
                defaults[i] = 0;
            }
        }

        naturalKind = kinds;
        occluding = occ;
        naturalDefaultId = defaults;
        tablesReady = true;
    }

    /**
     * Same rule SightRaycaster uses on the region thread (Material#isOccluding),
     * so the Netty-side "exposed face" test and the raycast agree. A key with
     * no Bukkit Material counts as see-through, which errs on the side of
     * treating the ore as a candidate.
     */
    private static boolean isOccludingKey(String key) {
        try {
            Material m = Material.matchMaterial(key);
            return m != null && SightRaycaster.blocksSight(m);
        } catch (Throwable t) {
            return false;
        }
    }

    /** Main thread. Caches min height and dimension per world so Netty never asks Bukkit. */
    public void refreshWorldMeta() {
        for (World w : Bukkit.getWorlds()) cacheMeta(w);
    }

    WorldMeta cacheMeta(World w) {
        int env;
        switch (w.getEnvironment()) {
            case NETHER:  env = ENV_NETHER; break;
            case THE_END: env = ENV_END; break;
            default:      env = ENV_OVERWORLD;
        }
        WorldMeta m = new WorldMeta(w.getMinHeight(), w.getMaxHeight(), env);
        worldMeta.put(w.getName(), m);
        return m;
    }

    /** Height range and environment are fixed for a world's lifetime, so reading them once here is safe. */
    WorldMeta meta(World w) {
        WorldMeta m = worldMeta.get(w.getName());
        return m != null ? m : cacheMeta(w);
    }

    // ── Accessors ─────────────────────────────────────────────────────────

    public boolean isEnabled()        { return enabled && tablesReady; }
    int  getAlwaysRevealRadius()      { return alwaysRevealRadius; }
    int  getMaxRayDistance()          { return maxRayDistance; }
    int  getRayBudget()               { return rayBudget; }
    boolean isF5Aware()               { return f5Aware; }
    int  getForgetDistance()          { return forgetDistance; }
    BitSet getOccluding()             { return occluding; }

    WorldSettings settingsFor(String worldName) {
        if (!enabled) return WorldSettings.DISABLED;
        WorldSettings s = worldName != null ? worldSettings.get(worldName) : null;
        return s != null ? s : defaultSettings;
    }

    /** Netty-safe: config + cached bypass only. */
    public boolean isActiveFor(Player player) {
        if (!isEnabled() || player == null) return false;
        if (plugin.isXrayBypassed(player.getUniqueId())) return false;
        return settingsFor(player.getWorld().getName()).enabled;
    }

    /** Same check without touching the player entity (safe from any Folia region thread). */
    boolean isActiveFor(UUID id, String worldName) {
        if (!isEnabled() || id == null || plugin.isXrayBypassed(id)) return false;
        return settingsFor(worldName).enabled;
    }

    /** True if this id is a hidden ore in any configured world (Smart Fill palette exclusion). */
    public boolean isAnyHiddenOre(int id) {
        if (!isEnabled() || id < 0) return false;
        if (defaultSettings.hiddenIds.get(id)) return true;
        for (WorldSettings s : worldSettings.values()) if (s.hiddenIds.get(id)) return true;
        return false;
    }

    // ═════════════════════════════════════════════════════════════════════
    //  Per-player state
    // ═════════════════════════════════════════════════════════════════════

    /**
     * Hidden ores the client was sent in one chunk. The arrays come from
     * CHUNK_DATA and are never written after construction; later changes
     * (block updates, newly exposed faces) go to the small concurrent
     * overlay, which wins over the arrays.
     */
    static final class ChunkOres {
        final int cx, cz, minY;
        /** Local index ((y - minY) << 8 | lz << 4 | lx), ascending. */
        final int[] local;
        final int[] real;
        final int[] fake;
        final byte[] exposed;
        /** local -> {realId (-1 = no longer an ore), fakeId, exposed 0/1}. Values are replaced, never mutated. */
        final ConcurrentHashMap<Integer, int[]> overlay = new ConcurrentHashMap<>();

        ChunkOres(int cx, int cz, int minY, int[] local, int[] real, int[] fake, byte[] exposed) {
            this.cx = cx; this.cz = cz; this.minY = minY;
            this.local = local; this.real = real; this.fake = fake; this.exposed = exposed;
        }

        static ChunkOres empty(int cx, int cz, int minY) {
            return new ChunkOres(cx, cz, minY, new int[0], new int[0], new int[0], new byte[0]);
        }

        /** {real, fake, exposed} or null if no hidden ore was ever recorded here. */
        int[] entry(int localIdx) {
            int[] o = overlay.get(localIdx);
            if (o != null) return o;
            int i = Arrays.binarySearch(local, localIdx);
            return i >= 0 ? new int[]{ real[i], fake[i], exposed[i] } : null;
        }

        void put(int localIdx, int realId, int fakeId, boolean isExposed) {
            overlay.put(localIdx, new int[]{ realId, fakeId, isExposed ? 1 : 0 });
        }

        void markRemoved(int localIdx) {
            int[] e = entry(localIdx);
            if (e != null && e[0] >= 0) overlay.put(localIdx, new int[]{ -1, e[1], 0 });
        }

        /** Marks a recorded ore as exposed. Returns true if it wasn't before. */
        boolean markExposed(int localIdx) {
            int[] e = entry(localIdx);
            if (e == null || e[0] < 0 || e[2] == 1) return false;
            overlay.put(localIdx, new int[]{ e[0], e[1], 1 });
            return true;
        }

        int worldX(int localIdx) { return (cx << 4) | (localIdx & 15); }
        int worldY(int localIdx) { return minY + (localIdx >>> 8); }
        int worldZ(int localIdx) { return (cz << 4) | ((localIdx >>> 4) & 15); }

        interface CandidateVisitor { void visit(int localIdx, int realId); }

        /** Visits every recorded ore that is still an ore and has an exposed face. */
        void forEachExposed(CandidateVisitor v) {
            for (int i = 0; i < local.length; i++) {
                if (exposed[i] == 0 || overlay.containsKey(local[i])) continue;
                v.visit(local[i], real[i]);
            }
            for (Map.Entry<Integer, int[]> e : overlay.entrySet()) {
                int[] val = e.getValue();
                if (val[0] >= 0 && val[2] == 1) v.visit(e.getKey(), val[0]);
            }
        }

        int exposedCount() {
            int[] n = { 0 };
            forEachExposed((l, r) -> n[0]++);
            return n[0];
        }

        /** Visits every recorded ore that is still an ore, exposed or not. */
        void forEachLive(CandidateVisitor v) {
            for (int i = 0; i < local.length; i++) {
                if (overlay.containsKey(local[i])) continue;
                v.visit(local[i], real[i]);
            }
            for (Map.Entry<Integer, int[]> e : overlay.entrySet()) {
                int[] val = e.getValue();
                if (val[0] >= 0) v.visit(e.getKey(), val[0]);
            }
        }
    }

    static final class PlayerXray {
        final ConcurrentHashMap<Long, ChunkOres> chunks = new ConcurrentHashMap<>();
        /** chunk column key -> revealed local indices. Sticky until unload / forget / block change. */
        final ConcurrentHashMap<Long, Set<Integer>> revealed = new ConcurrentHashMap<>();
        /** Last known eye position, written by the visibility check, read on Netty. */
        volatile double ex, ey, ez;
        volatile boolean hasEye;
        /** World of the last visibility check, so block events can find nearby players without touching the entity. */
        volatile UUID worldId;
        /** Camera positions (x, y, z triples) from the last visibility check. */
        volatile double[] cams;
        /** Bumped whenever a new candidate appears, so the movement skip doesn't miss it. */
        final AtomicInteger gen = new AtomicInteger();

        // Visibility-check memo. Only touched by that player's own scheduled check.
        double[] lastCams;
        double lastLookX, lastLookY, lastLookZ;
        int lastGen = -1;
        boolean incomplete = true;

        boolean isRevealed(long ck, int localIdx) {
            Set<Integer> s = revealed.get(ck);
            return s != null && s.contains(localIdx);
        }

        void reveal(long ck, int localIdx) {
            revealed.computeIfAbsent(ck, k -> ConcurrentHashMap.newKeySet()).add(localIdx);
        }

        void unreveal(long ck, int localIdx) {
            Set<Integer> s = revealed.get(ck);
            if (s != null) s.remove(localIdx);
        }

        boolean isNearEye(int x, int y, int z, int radius) {
            if (!hasEye) return false;
            double dx = x + 0.5 - ex, dy = y + 0.5 - ey, dz = z + 0.5 - ez;
            return dx * dx + dy * dy + dz * dz <= (double) radius * radius;
        }
    }

    PlayerXray state(UUID id) {
        return players.computeIfAbsent(id, k -> new PlayerXray());
    }

    PlayerXray existingState(UUID id) {
        return players.get(id);
    }

    /** Removes and returns a player's state (null if none). */
    PlayerXray takeState(UUID id) {
        return players.remove(id);
    }

    public int getTrackedPlayers() {
        return players.size();
    }

    Map<UUID, PlayerXray> allStates() {
        return players;
    }

    static long columnKey(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
    }

    static int localIndex(int x, int y, int z, int minY) {
        return ((y - minY) << 8) | ((z & 15) << 4) | (x & 15);
    }

    /** Client dropped this chunk (UNLOAD_CHUNK): forget its ores and reveals. */
    public void forgetChunk(UUID id, int cx, int cz) {
        PlayerXray px = players.get(id);
        if (px == null) return;
        long ck = columnKey(cx, cz);
        px.chunks.remove(ck);
        px.revealed.remove(ck);
    }

    /** Client dropped every chunk (RESPAWN / JOIN_GAME / world change) or quit. */
    public void cleanupPlayer(UUID id) {
        players.remove(id);
    }

    public void clearAll() {
        players.clear();
    }

    // ═════════════════════════════════════════════════════════════════════
    //  Packet passes (Netty thread)
    // ═════════════════════════════════════════════════════════════════════

    /**
     * Masks hidden ores in a CHUNK_DATA column and records them for this
     * player. Returns true if any block was changed (caller re-encodes).
     */
    public boolean processChunk(Player player, BaseChunk[] sections, int cx, int cz) {
        UUID uuid = player.getUniqueId();
        long ck = columnKey(cx, cz);
        if (!isActiveFor(player)) {
            PlayerXray px = players.get(uuid);
            if (px != null) px.chunks.remove(ck);
            return false;
        }
        World world = player.getWorld();
        WorldSettings ws = settingsFor(world.getName());
        WorldMeta meta = meta(world);
        PlayerXray px = state(uuid);

        BitSet hidden = ws.hiddenIds;
        BitSet occ = occluding;
        byte[] kinds = naturalKind;
        Set<Integer> revealed = px.revealed.get(ck);
        // BUGFIX: no longer starts above void-y while Smart Fill is active
        // (see ChunkListener). Blocks the freecam pass already replaced aren't
        // ore ids any more, so they're skipped by the hiddenIds test below.
        int lowY = ws.minY;

        int n = 0;
        int[] local = new int[64], real = new int[64], fake = new int[64];
        byte[] exposed = new byte[64];
        long masked = 0;

        for (int si = 0; si < sections.length; si++) {
            BaseChunk section = sections[si];
            if (section == null) continue;
            int baseY = meta.minY + si * 16;
            if (baseY + 15 < lowY || baseY > ws.maxY) continue;
            try {
                if (section.isEmpty() || !sectionMayContain(section, hidden)) continue;
            } catch (Exception e) {
                continue;
            }

            for (int ly = 0; ly < 16; ly++) {
                int y = baseY + ly;
                if (y < lowY || y > ws.maxY) continue;
                for (int lz = 0; lz < 16; lz++) {
                    for (int lx = 0; lx < 16; lx++) {
                        int id;
                        try {
                            id = section.getBlockId(lx, ly, lz);
                        } catch (Exception e) {
                            continue;
                        }
                        if (id <= 0 || !hidden.get(id)) continue;

                        int li = ((y - meta.minY) << 8) | (lz << 4) | lx;
                        boolean isExposed = isExposed(sections, si, lx, ly, lz, occ);
                        int fakeId = chooseFake(sections, si, lx, ly, lz, y, meta.env, kinds);

                        if (n == local.length) {
                            int cap = n * 2;
                            local = Arrays.copyOf(local, cap);
                            real = Arrays.copyOf(real, cap);
                            fake = Arrays.copyOf(fake, cap);
                            exposed = Arrays.copyOf(exposed, cap);
                        }
                        local[n] = li; real[n] = id; fake[n] = fakeId; exposed[n] = (byte) (isExposed ? 1 : 0);
                        n++;

                        // Sticky reveal: a re-sent chunk (Smart Fill refresh,
                        // server resend) must not flip a revealed ore back.
                        if (revealed != null && revealed.contains(li)) continue;
                        if (fakeId <= 0) continue;
                        try {
                            section.set(lx, ly, lz, fakeId);
                            masked++;
                        } catch (Exception ignored) {}
                    }
                }
            }
        }

        if (n == 0) {
            px.chunks.remove(ck);
        } else {
            px.chunks.put(ck, new ChunkOres(cx, cz, meta.minY,
                    Arrays.copyOf(local, n), Arrays.copyOf(real, n), Arrays.copyOf(fake, n), Arrays.copyOf(exposed, n)));
            px.gen.incrementAndGet();
        }
        if (masked > 0) {
            oresHidden.addAndGet(masked);
            plugin.dbg("Anti-Xray CHUNK_DATA " + cx + "," + cz + " for " + player.getName()
                    + ": " + masked + " ores hidden, " + n + " tracked");
        }
        return masked > 0;
    }

    /**
     * BLOCK_CHANGE pass. Returns the id to send (the input id if unchanged).
     *
     * @param upstreamChanged true if void-y hiding / Smart Fill already
     *                        rewrote this block in the same pass; it's then
     *                        left alone and the tracking isn't touched.
     */
    public int processBlockChange(Player player, int x, int y, int z, int id, boolean upstreamChanged) {
        if (upstreamChanged || !isActiveFor(player)) return id;
        World world = player.getWorld();
        WorldSettings ws = settingsFor(world.getName());
        WorldMeta meta = meta(world);
        int out = processOne(player, state(player.getUniqueId()), ws, meta, x, y, z, id);
        if (out != id) oresHidden.incrementAndGet();
        return out;
    }

    /**
     * MULTI_BLOCK_CHANGE pass. originalIds holds each block's id from
     * before the void-y / Smart Fill pass (null if unknown). Returns true
     * if any block was changed.
     */
    public boolean processMultiBlockChange(Player player, WrapperPlayServerMultiBlockChange.EncodedBlock[] blocks,
                                           int[] originalIds) {
        if (blocks == null || !isActiveFor(player)) return false;
        World world = player.getWorld();
        WorldSettings ws = settingsFor(world.getName());
        WorldMeta meta = meta(world);
        PlayerXray px = state(player.getUniqueId());
        int changed = 0;
        for (int i = 0; i < blocks.length; i++) {
            WrapperPlayServerMultiBlockChange.EncodedBlock b = blocks[i];
            if (b == null) continue;
            int id = b.getBlockId();
            if (originalIds != null && i < originalIds.length && originalIds[i] != id) continue; // upstream replaced
            int out = processOne(player, px, ws, meta, b.getX(), b.getY(), b.getZ(), id);
            if (out != id) {
                b.setBlockId(out);
                changed++;
            }
        }
        if (changed > 0) oresHidden.addAndGet(changed);
        return changed > 0;
    }

    private int processOne(Player player, PlayerXray px, WorldSettings ws, WorldMeta meta,
                           int x, int y, int z, int id) {
        long ck = columnKey(x >> 4, z >> 4);
        int li = localIndex(x, y, z, meta.minY);
        ChunkOres co = px.chunks.get(ck);
        boolean ore = id > 0 && ws.inRange(y) && ws.hiddenIds.get(id);

        if (!ore) {
            // The block changed into something else: drop it from tracking
            // and from the revealed set ("or the block changes"). Mined ores
            // fall in here, so a break is never altered.
            if (co != null) co.markRemoved(li);
            px.unreveal(ck, li);
            // NEW: the client is about to see an opening here (mined, exploded,
            // pushed, washed away...), so recorded ores next to it now have an
            // exposed face. Covers every cause of a block change, including
            // fluids and physics, with nothing but packet data.
            if (id == 0 || id >= occluding.length() || !occluding.get(id)) exposeNeighbors(px, meta, x, y, z);
            return id;
        }

        int[] e = co != null ? co.entry(li) : null;
        int prevFake = e != null ? e[1] : 0;
        boolean prevExposed = e == null || e[2] == 1;

        if (px.isRevealed(ck, li)) {
            if (co != null) co.put(li, id, prevFake, prevExposed);
            return id;
        }

        if (px.isNearEye(x, y, z, Math.max(alwaysRevealRadius, CHANGE_REVEAL_RADIUS))) {
            px.reveal(ck, li);
            px.chunks.computeIfAbsent(ck, k -> ChunkOres.empty(x >> 4, z >> 4, meta.minY))
                    .put(li, id, prevFake, true);
            oresRevealed.incrementAndGet();
            return id;
        }

        int fakeId = prevFake > 0 ? prevFake : fallbackFake(meta.env, y);
        if (fakeId <= 0) return id;
        // No neighbour data in a single block update, so the face is
        // assumed exposed. The visibility check settles it within a few ticks.
        px.chunks.computeIfAbsent(ck, k -> ChunkOres.empty(x >> 4, z >> 4, meta.minY))
                .put(li, id, fakeId, true);
        px.gen.incrementAndGet();
        return fakeId;
    }

    private static void exposeNeighbors(PlayerXray px, WorldMeta meta, int x, int y, int z) {
        boolean any = false;
        for (int[] f : FACES) {
            int nx = x + f[0], ny = y + f[1], nz = z + f[2];
            if (ny < meta.minY || ny >= meta.maxY) continue;
            ChunkOres co = px.chunks.get(columnKey(nx >> 4, nz >> 4));
            if (co != null && co.markExposed(localIndex(nx, ny, nz, meta.minY))) any = true;
        }
        if (any) px.gen.incrementAndGet();
    }

    // ═════════════════════════════════════════════════════════════════════
    //  Section helpers
    // ═════════════════════════════════════════════════════════════════════

    /**
     * PERF: 1.18+ sections carry a block palette. If none of its entries is
     * a hidden ore, the 4096-block scan is skipped entirely, which is most
     * sections in a normal chunk.
     */
    private static boolean sectionMayContain(BaseChunk section, BitSet hidden) {
        try {
            if (!(section instanceof Chunk_v1_18)) return true;
            DataPalette dp = ((Chunk_v1_18) section).getChunkData();
            if (dp == null || dp.palette == null) return true;
            Palette p = dp.palette;
            if (p instanceof GlobalPalette) return true;
            int size = p.size();
            for (int i = 0; i < size; i++) {
                int state = p.idToState(i);
                if (state > 0 && hidden.get(state)) return true;
            }
            return false;
        } catch (Throwable t) {
            return true; // unknown palette layout on this PacketEvents build: just scan
        }
    }

    /** Marker for a neighbour outside this chunk column (no data in the packet). */
    private static final int OUTSIDE = -1;

    private static int neighborId(BaseChunk[] sections, int si, int lx, int ly, int lz) {
        if (lx < 0 || lx > 15 || lz < 0 || lz > 15) return OUTSIDE;
        if (ly < 0) { si--; ly += 16; }
        else if (ly > 15) { si++; ly -= 16; }
        if (si < 0 || si >= sections.length) return 0; // above / below the world: air
        BaseChunk s = sections[si];
        if (s == null) return 0;
        try {
            return s.getBlockId(lx, ly, lz);
        } catch (Exception e) {
            return OUTSIDE;
        }
    }

    private static final int[][] FACES = {
            { 1, 0, 0 }, { -1, 0, 0 }, { 0, 1, 0 }, { 0, -1, 0 }, { 0, 0, 1 }, { 0, 0, -1 }
    };

    /** At least one face touches a non-occluding block, or a neighbour chunk we can't see (conservative). */
    static boolean isExposed(BaseChunk[] sections, int si, int lx, int ly, int lz, BitSet occ) {
        for (int[] f : FACES) {
            int id = neighborId(sections, si, lx + f[0], ly + f[1], lz + f[2]);
            if (id == OUTSIDE || id < 0 || !occ.get(id)) return true;
        }
        return false;
    }

    /**
     * Most common natural block among the 6 face neighbours, then among
     * all 26 surrounding blocks, then a depth / dimension default. Only
     * depends on the packet's own data, so the same chunk always gets the
     * same fake blocks (ties are broken by NATURAL_KEYS order, then id).
     */
    private int chooseFake(BaseChunk[] sections, int si, int lx, int ly, int lz, int y, int env, byte[] kinds) {
        int[] ids = new int[26];
        int[] counts = new int[26];
        int distinct = 0;

        for (int pass = 0; pass < 2; pass++) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        int manhattan = Math.abs(dx) + Math.abs(dy) + Math.abs(dz);
                        if (manhattan == 0) continue;
                        if (pass == 0 ? manhattan != 1 : manhattan == 1) continue;
                        int id = neighborId(sections, si, lx + dx, ly + dy, lz + dz);
                        if (id <= 0 || id >= kinds.length || kinds[id] == 0) continue;
                        int j = 0;
                        while (j < distinct && ids[j] != id) j++;
                        if (j == distinct) { ids[distinct] = id; counts[distinct] = 0; distinct++; }
                        counts[j]++;
                    }
                }
            }
            if (distinct > 0) break; // face neighbours decided it
        }

        if (distinct > 0) {
            int best = 0;
            for (int j = 1; j < distinct; j++) {
                if (counts[j] > counts[best]
                        || (counts[j] == counts[best] && (kinds[ids[j]] < kinds[ids[best]]
                            || (kinds[ids[j]] == kinds[ids[best]] && ids[j] < ids[best])))) {
                    best = j;
                }
            }
            return ids[best];
        }
        return fallbackFake(env, y);
    }

    /** Fake block with no usable neighbour data: by dimension, then depth. */
    int fallbackFake(int env, int y) {
        int[] d = naturalDefaultId;
        int id;
        if (env == ENV_NETHER) id = d[K_NETHERRACK];
        else if (env == ENV_END) id = d[K_END_STONE];
        else id = y < 0 ? d[K_DEEPSLATE] : d[K_STONE];
        if (id <= 0) id = d[K_STONE];
        if (id <= 0) id = plugin.getSmartFillFallbackId();
        return id;
    }

    // ═════════════════════════════════════════════════════════════════════
    //  Paper anti-xray detection
    // ═════════════════════════════════════════════════════════════════════

    /**
     * NEW: Paper's own anti-xray (engine-mode 2/3) injects fake ores into the
     * same packets, which then show up on top of ours and make the fake rock
     * look wrong. Detected per world by looking at the class of
     * Level.chunkPacketBlockController: the base ChunkPacketBlockController is
     * Paper's no-op, anything else means anti-xray is on. Reflection only,
     * fails silently on Spigot or if internals change. Main thread.
     */
    public void checkPaperAntiXray() {
        if (!isEnabled()) return;
        for (World w : Bukkit.getWorlds()) {
            if (!settingsFor(w.getName()).enabled) continue;
            if (Boolean.TRUE.equals(paperAntiXrayEnabled(w))) {
                plugin.getLogger().warning("[FPAntiFreeCam] Paper anti-xray is enabled in world '" + w.getName()
                        + "' while FPAntiFreeCam Anti-Xray is on. Set anticheat.anti-xray.enabled: false in that"
                        + " world's paper-world config, or disable anti-xray for it in config.yml.");
            }
        }
    }

    private static Boolean paperAntiXrayEnabled(World w) {
        try {
            Object level = w.getClass().getMethod("getHandle").invoke(w);
            Object controller = level.getClass().getField("chunkPacketBlockController").get(level);
            if (controller == null) return false;
            return !controller.getClass().getSimpleName().equals("ChunkPacketBlockController");
        } catch (Throwable t) {
            return null;
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    //  Stats
    // ═════════════════════════════════════════════════════════════════════

    public long getOresHidden()   { return oresHidden.get(); }
    public long getOresRevealed() { return oresRevealed.get(); }

    public int getCandidatesTracked() {
        int total = 0;
        for (PlayerXray px : players.values()) {
            for (Map.Entry<Long, ChunkOres> e : px.chunks.entrySet()) {
                Set<Integer> rev = px.revealed.get(e.getKey());
                int[] n = { 0 };
                e.getValue().forEachExposed((l, r) -> { if (rev == null || !rev.contains(l)) n[0]++; });
                total += n[0];
            }
        }
        return total;
    }

    /** Raycast steps per server tick since the last load, across all players. */
    public double getAvgStepsPerTick() {
        long ticks = Math.max(1, (System.currentTimeMillis() - statsSince) / 50);
        return raySteps.get() / (double) ticks;
    }

    public String stats() {
        if (!isEnabled()) return "OFF";
        return "hidden=" + oresHidden.get()
                + " revealed=" + oresRevealed.get()
                + " candidates=" + getCandidatesTracked()
                + " steps/tick=" + String.format(Locale.ROOT, "%.1f", getAvgStepsPerTick())
                + " players=" + players.size();
    }

    /** Readable /fpac stats rows: counters, then config. */
    public String[] statsRows() {
        String counters = "&f" + fmt(oresHidden.get()) + " &7hidden &8· &f" + fmt(oresRevealed.get())
                + " &7revealed &8· &f" + fmt(getCandidatesTracked()) + " &7candidates";
        String load = "&f" + String.format(Locale.ROOT, "%.0f", getAvgStepsPerTick()) + " &7steps/tick &8· &f"
                + players.size() + " &7players &8· &7ray &f" + maxRayDistance + " &8· &7budget &f" + fmt(rayBudget)
                + " &8· &7F5 " + (f5Aware ? "&aon" : "&7off");
        return new String[]{ counters, load };
    }

    /** Worlds Anti-Xray is on in, among the loaded ones. */
    public List<String> activeWorlds() {
        List<String> out = new ArrayList<>();
        if (!isEnabled()) return out;
        for (World w : Bukkit.getWorlds()) if (settingsFor(w.getName()).enabled) out.add(w.getName());
        return out;
    }

    private static String fmt(long n) {
        if (n >= 10_000_000) return String.format(Locale.ROOT, "%.1fM", n / 1_000_000.0);
        if (n >= 10_000) return String.format(Locale.ROOT, "%.1fk", n / 1_000.0);
        return Long.toString(n);
    }
}
