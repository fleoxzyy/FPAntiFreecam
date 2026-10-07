package me.fleoxxzy.FPAntiFreeCam;

import com.github.retrooper.packetevents.protocol.world.chunk.BaseChunk;
import com.github.retrooper.packetevents.protocol.world.states.WrappedBlockState;
import com.github.retrooper.packetevents.protocol.world.states.type.StateType;
import com.github.retrooper.packetevents.protocol.world.states.type.StateTypes;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Smart Fill engine: samples the real block palette from surrounding terrain
 * and fills hidden areas with a believable weighted mix of those blocks.
 *
 * <p>Thread-safe: all public methods can be called from the Netty IO thread.
 * Palette building reads deserialized chunk sections (no Bukkit API calls).
 */
public final class SmartFillEngine {

    private final FPAntiFreeCam plugin;

    // ── Hardcoded terrain allowlist ───────────────────────────────────────
    // BUGFIX: this used to include surface-only ground-cover blocks too
    // (grass_block, sand, snow_block, ice, farmland, podzol, mycelium), and
    // the SAME set was used both to decide "leave this block alone if it's
    // real" (isSafeTerrain) AND "this is valid material to fake a cave wall
    // with" (the palette-sampling loop below). Those are different
    // questions - a snowy mountain's surface is legitimately safe to leave
    // untouched, but it should never get sampled INTO a deep cave's fill
    // palette. Since sampling reaches sampleRadiusSections above/below each
    // band, any cave running near a snowy/sandy surface could pull that
    // surface material into its camouflage mix, showing up as an
    // out-of-place white/light patch deep in an otherwise normal stone
    // cave. TERRAIN_TYPES is now cave/underground-appropriate material
    // ONLY and is what the sampling loop draws from; SURFACE_ONLY_SAFE_TYPES
    // (below) covers the surface-cover blocks and is only consulted by
    // isSafeTerrain, never by the sampler.
    private static final Set<StateType> TERRAIN_TYPES;
    static {
        Set<StateType> s = new HashSet<>();
        // Overworld
        s.add(StateTypes.STONE);
        s.add(StateTypes.DEEPSLATE);
        s.add(StateTypes.TUFF);
        s.add(StateTypes.ANDESITE);
        s.add(StateTypes.DIORITE);
        s.add(StateTypes.GRANITE);
        s.add(StateTypes.DIRT);
        s.add(StateTypes.GRAVEL);
        s.add(StateTypes.COARSE_DIRT);
        s.add(StateTypes.CALCITE);
        s.add(StateTypes.SMOOTH_BASALT);
        s.add(StateTypes.COBBLESTONE);
        s.add(StateTypes.COBBLED_DEEPSLATE);
        s.add(StateTypes.SANDSTONE);
        s.add(StateTypes.RED_SANDSTONE);
        s.add(StateTypes.TERRACOTTA);
        s.add(StateTypes.MOSS_BLOCK);
        s.add(StateTypes.MUD);
        s.add(StateTypes.CLAY);
        s.add(StateTypes.PACKED_MUD);
        // Nether
        s.add(StateTypes.NETHERRACK);
        s.add(StateTypes.BLACKSTONE);
        s.add(StateTypes.BASALT);
        s.add(StateTypes.SOUL_SAND);
        s.add(StateTypes.SOUL_SOIL);
        // End
        s.add(StateTypes.END_STONE);
        TERRAIN_TYPES = Collections.unmodifiableSet(s);
    }

    /**
     * Surface ground-cover blocks that are safe to leave alone when they're
     * genuinely real (a beach/plains/taiga/snowy column whose actual ground
     * is grass, sand, snow, ice, farmland, podzol, or mycelium), but must
     * NEVER be sampled as underground cave fill material - see
     * TERRAIN_TYPES's javadoc above for why they're kept separate.
     */
    private static final Set<StateType> SURFACE_ONLY_SAFE_TYPES;
    static {
        Set<StateType> s = new HashSet<>();
        s.add(StateTypes.GRASS_BLOCK);
        s.add(StateTypes.SAND);
        s.add(StateTypes.RED_SAND);
        s.add(StateTypes.PODZOL);
        s.add(StateTypes.MYCELIUM);
        s.add(StateTypes.SNOW_BLOCK);
        s.add(StateTypes.ICE);
        s.add(StateTypes.PACKED_ICE);
        s.add(StateTypes.BLUE_ICE);
        s.add(StateTypes.FARMLAND);
        SURFACE_ONLY_SAFE_TYPES = Collections.unmodifiableSet(s);
    }

    /**
     * Blocks that must NEVER be masked, on top of TERRAIN_TYPES (which are
     * already safe since they're generic rock/dirt). BEDROCK because a
     * client noticing "diggable bedrock" is an instant giveaway and can also
     * break client-side world-border/height assumptions; WATER/LAVA because
     * swapping a real fluid block for a solid one changes fall/swim physics
     * predictions client-side and causes rubber-banding the moment the real
     * block is revealed underneath.
     */
    private static final Set<StateType> NEVER_MASK_EXTRA;
    static {
        Set<StateType> s = new HashSet<>();
        s.add(StateTypes.BEDROCK);
        s.add(StateTypes.WATER);
        s.add(StateTypes.LAVA);
        NEVER_MASK_EXTRA = Collections.unmodifiableSet(s);
    }

    /**
     * BUGFIX: the old pipeline only ever masked blocks whose type was
     * literally AIR/CAVE_AIR/VOID_AIR. Anything else in a "hidden" cave —
     * vines, glow lichen, ladders, rails, signs, torches, redstone dust,
     * doors, item frames' backing blocks, ore veins, fence posts a player
     * built — was left completely untouched and sent as its real block,
     * sticking out against the fake stone around it (or, for ores, leaking
     * exactly what a legit anti-xray system is supposed to hide). This
     * flips the check around: anything that ISN'T recognizable, safe,
     * natural terrain is maskable, not just literal air.
     */
    public static boolean isSafeTerrain(StateType type) {
        if (type == null) return false; // null reads as air -> maskable
        return TERRAIN_TYPES.contains(type) || SURFACE_ONLY_SAFE_TYPES.contains(type)
                || NEVER_MASK_EXTRA.contains(type);
    }

    /**
     * BUGFIX: registry keys (not enum identity) for thin/passable "ground
     * cover" blocks — grass, seagrass, sugar cane, kelp, flowers, saplings,
     * crops, vines, torches, rails, coral, snow layers, etc. These do not
     * meaningfully block sky/light the way a real cave roof does, so the
     * topSolidY scan in {@link #buildPalette} must skip past them to find
     * the actual ground underneath, instead of treating the plant itself as
     * "solid roofing" whatever is directly below it.
     *
     * <p>Without this, a single flower or a multi-block-tall sugar cane
     * stalk sitting on ordinary beach sand made everything under it look
     * "enclosed" to the sky-exposure check, so the real grass/sand/dirt
     * block right underneath — and the plant itself, since it isn't on the
     * TERRAIN_TYPES allowlist either — got treated as maskable cave interior
     * and swapped for random fake stone/dirt in plain daylight.
     *
     * <p>Matched against the block's registry key (e.g. "short_grass", not
     * "minecraft:short_grass") via exact string match, deliberately NOT
     * enum identity — this project already supports MC 1.19 through 26.1+,
     * across which several of these blocks were renamed (e.g. grass →
     * short_grass in 1.20.3), and a plain key-string set degrades gracefully
     * (a renamed/missing key here just means that one plant isn't
     * recognized, not a compile failure) instead of hardcoding version-
     * specific enum constants that may not exist on every targeted version.
     */
    private static final Set<String> NON_ROOF_KEYS;
    static {
        Set<String> s = new HashSet<>();
        // Ground cover / grass variants (renamed across versions)
        s.add("grass"); s.add("short_grass"); s.add("fern"); s.add("tall_grass");
        s.add("large_fern"); s.add("dead_bush");
        // Aquatic plants
        s.add("seagrass"); s.add("tall_seagrass"); s.add("kelp"); s.add("kelp_plant");
        s.add("sea_pickle");
        s.add("tube_coral"); s.add("brain_coral"); s.add("bubble_coral");
        s.add("fire_coral"); s.add("horn_coral");
        s.add("dead_tube_coral"); s.add("dead_brain_coral"); s.add("dead_bubble_coral");
        s.add("dead_fire_coral"); s.add("dead_horn_coral");
        s.add("tube_coral_fan"); s.add("brain_coral_fan"); s.add("bubble_coral_fan");
        s.add("fire_coral_fan"); s.add("horn_coral_fan");
        s.add("dead_tube_coral_fan"); s.add("dead_brain_coral_fan"); s.add("dead_bubble_coral_fan");
        s.add("dead_fire_coral_fan"); s.add("dead_horn_coral_fan");
        // Sugar cane / bamboo plant (not the solid bamboo wood blocks)
        s.add("sugar_cane"); s.add("bamboo"); s.add("bamboo_sapling");
        // Vines / lichen
        s.add("vine"); s.add("glow_lichen");
        s.add("weeping_vines"); s.add("weeping_vines_plant");
        s.add("twisting_vines"); s.add("twisting_vines_plant");
        s.add("cave_vines"); s.add("cave_vines_plant");
        s.add("lily_pad");
        // Flowers
        s.add("dandelion"); s.add("poppy"); s.add("blue_orchid"); s.add("allium");
        s.add("azure_bluet"); s.add("red_tulip"); s.add("orange_tulip"); s.add("white_tulip");
        s.add("pink_tulip"); s.add("oxeye_daisy"); s.add("cornflower");
        s.add("lily_of_the_valley"); s.add("wither_rose"); s.add("sunflower");
        s.add("lilac"); s.add("rose_bush"); s.add("peony"); s.add("torchflower");
        s.add("pink_petals");
        // Saplings / small growths
        s.add("oak_sapling"); s.add("spruce_sapling"); s.add("birch_sapling");
        s.add("jungle_sapling"); s.add("acacia_sapling"); s.add("dark_oak_sapling");
        s.add("mangrove_propagule"); s.add("cherry_sapling");
        s.add("azalea"); s.add("flowering_azalea");
        s.add("brown_mushroom"); s.add("red_mushroom");
        // Crops
        s.add("wheat"); s.add("carrots"); s.add("potatoes"); s.add("beetroots");
        s.add("nether_wart"); s.add("pumpkin_stem"); s.add("melon_stem");
        s.add("attached_pumpkin_stem"); s.add("attached_melon_stem");
        // Thin ground layer (NOT snow_block, which is a real solid roof)
        s.add("snow");
        // Fire / light sources / redstone / rails / misc thin blocks
        s.add("fire"); s.add("soul_fire");
        s.add("torch"); s.add("wall_torch"); s.add("soul_torch"); s.add("soul_wall_torch");
        s.add("redstone_torch"); s.add("redstone_wall_torch"); s.add("redstone_wire");
        s.add("rail"); s.add("powered_rail"); s.add("detector_rail"); s.add("activator_rail");
        s.add("cobweb"); s.add("scaffolding");
        NON_ROOF_KEYS = Collections.unmodifiableSet(s);
    }

    /**
     * Best-effort registry key for a StateType (e.g. "short_grass"), with the
     * "minecraft:" namespace stripped if present. Returns null on any failure
     * so callers can fail safe (treat as a real roof) instead of throwing.
     */
    private static String registryKey(StateType type) {
        try {
            Object name = type.getName();
            if (name == null) return null;
            String s = name.toString();
            int idx = s.indexOf(':');
            return idx >= 0 ? s.substring(idx + 1) : s;
        } catch (Throwable t) {
            return null;
        }
    }

    /** True if this block type should NOT count as "roofing" for the sky-exposure scan. */
    private static boolean isNonRoofType(StateType type) {
        if (type == null) return true; // no data reads as air/open
        if (type == StateTypes.AIR || type == StateTypes.CAVE_AIR || type == StateTypes.VOID_AIR
                || type == StateTypes.WATER || type == StateTypes.LAVA) {
            return true;
        }
        String key = registryKey(type);
        if (key == null) return false;
        if (NON_ROOF_KEYS.contains(key)) return true;
        // BUGFIX: a tree's canopy is much wider than its trunk, so the grass
        // under the outer edges of the canopy has leaves - not the trunk -
        // directly above it. Leaves let skylight through and were never a
        // real "roof", but every leaf type (oak/spruce/birch/.../azalea, and
        // any future wood type) would otherwise count as one, making a wide
        // patch of ground under every tree look enclosed and get masked with
        // a stone/dirt checkerboard in broad daylight. Suffix match instead
        // of enumerating every wood type so new leaf blocks are covered too.
        return key.endsWith("_leaves") || key.equals("leaves");
    }

    // ── Palette data structures ───────────────────────────────────────────

    /**
     * A weighted mix of terrain block states for ONE 16-block Y section band.
     * Stores both WrappedBlockState (for CHUNK_DATA section.set()) and
     * globalId (for MULTI_BLOCK_CHANGE block.setBlockId()).
     */
    public static final class WeightedPalette {
        final WrappedBlockState[] states;
        final int[] globalIds;
        final int[] cumulativeWeights;
        final int totalWeight;

        WeightedPalette(WrappedBlockState[] states, int[] globalIds,
                        int[] cumulativeWeights, int totalWeight) {
            this.states = states;
            this.globalIds = globalIds;
            this.cumulativeWeights = cumulativeWeights;
            this.totalWeight = totalWeight;
        }
    }

    /**
     * BUGFIX: previously ONE WeightedPalette was built by pooling every
     * sampled block across the ENTIRE camouflage band (void-y up to
     * ceiling-y, plus sample-radius-sections) into a single frequency table,
     * then picking from that single chunk-wide mix via a position hash for
     * every masked block in the column — regardless of actual depth. That
     * meant material from anywhere in the sampled range (a deepslate vein
     * several sections down, a patch of andesite off to the side) could get
     * randomly selected at ANY masked position, producing an unnatural
     * speckled/random look instead of the real strata gradient real terrain
     * has (dirt/stone near the surface, deepslate further down).
     *
     * <p>ColumnPalette instead holds one {@link WeightedPalette} PER 16-block
     * section band, each built only from blocks actually found in that band
     * (plus a small neighboring window controlled by sample-radius-sections),
     * so the fill material at any given depth reflects what's really nearby
     * at THAT depth rather than a chunk-wide blend.
     */
    public static final class ColumnPalette {
        private final WeightedPalette[] bandPalettes; // indexed by section index
        private final int minY;

        /**
         * Per-column (index = localZ*16+localX) Y of the topmost real
         * roofing block anywhere in the full chunk column. See
         * {@link #isOpenToSky}. Null for generic/default palettes not built
         * from real column data, in which case the sky check is skipped.
         */
        final int[] topSolidY;

        /**
         * Per-column floor Y of a qualifying shallow surface pocket (see
         * {@link #isExemptSurfaceStructure}), or Integer.MIN_VALUE if this
         * column has no such pocket. Null (not just all-MIN_VALUE) for
         * generic/default palettes, same reasoning as topSolidY.
         */
        final int[] shallowPocketFloorY;

        ColumnPalette(WeightedPalette[] bandPalettes, int[] topSolidY, int[] shallowPocketFloorY, int minY) {
            this.bandPalettes = bandPalettes;
            this.topSolidY = topSolidY;
            this.shallowPocketFloorY = shallowPocketFloorY;
            this.minY = minY;
        }

        private WeightedPalette bandFor(int worldY) {
            int si = (worldY - minY) >> 4;
            if (si < 0) si = 0;
            if (si >= bandPalettes.length) si = bandPalettes.length - 1;
            WeightedPalette band = bandPalettes[si];
            return band; // callers only ever see non-null bands - resolved at build time
        }
    }

    // ── Per-world palette cache ──────────────────────────────────────────
    // worldName -> (chunkColumnKey -> palette)
    private final Map<String, ConcurrentHashMap<Long, ColumnPalette>> worldCaches
            = new ConcurrentHashMap<>();

    public SmartFillEngine(FPAntiFreeCam plugin) {
        this.plugin = plugin;
    }

    // ── Pre-cached default block states ──────────────────────────────────
    private static WrappedBlockState STONE_STATE;
    private static WrappedBlockState DEEPSLATE_STATE;
    private static WrappedBlockState NETHERRACK_STATE;
    private static WrappedBlockState END_STONE_STATE;
    static {
        try { STONE_STATE = WrappedBlockState.getByString("minecraft:stone"); } catch (Exception ignored) {}
        try { DEEPSLATE_STATE = WrappedBlockState.getByString("minecraft:deepslate"); } catch (Exception ignored) {}
        try { NETHERRACK_STATE = WrappedBlockState.getByString("minecraft:netherrack"); } catch (Exception ignored) {}
        try { END_STONE_STATE = WrappedBlockState.getByString("minecraft:end_stone"); } catch (Exception ignored) {}
    }

    public WrappedBlockState getDefaultState(String worldName, int blockY) {
        if (worldName != null) {
            String lower = worldName.toLowerCase();
            if (lower.contains("nether")) return NETHERRACK_STATE != null ? NETHERRACK_STATE : STONE_STATE;
            if (lower.contains("end")) return END_STONE_STATE != null ? END_STONE_STATE : STONE_STATE;
        }
        if (blockY < 0 && DEEPSLATE_STATE != null) return DEEPSLATE_STATE;
        return STONE_STATE != null ? STONE_STATE : plugin.getSmartFillFallback();
    }

    /**
     * Depth-appropriate fallback palette for a column with no usable sample
     * data at all (e.g. superflat/void chunk). Still varies by depth per
     * band via {@link #getDefaultState}, so even the fallback follows the
     * stone-near-the-surface / deepslate-further-down gradient rather than
     * picking one material for the whole column.
     */
    public ColumnPalette getDefaultPalette(String worldName, int minY, int sectionCount) {
        WeightedPalette[] bandPalettes = new WeightedPalette[sectionCount];
        for (int si = 0; si < sectionCount; si++) {
            int sectionBaseY = minY + si * 16;
            WrappedBlockState state = getDefaultState(worldName, sectionBaseY);
            if (state == null) return null;
            bandPalettes[si] = singleBlockPalette(state);
        }
        return new ColumnPalette(bandPalettes, null, null, minY);
    }

    private static WeightedPalette singleBlockPalette(WrappedBlockState state) {
        return new WeightedPalette(
                new WrappedBlockState[]{ state },
                new int[]{ state.getGlobalId() },
                new int[]{ 100 }, 100);
    }

    /**
     * Returns the cached palette for this chunk column, building it lazily
     * from the provided sections if not yet cached.
     *
     * @return the palette, or a depth/dimension default palette if no blocks found.
     */
    public ColumnPalette getOrBuildPalette(String worldName, int chunkX, int chunkZ,
                                            BaseChunk[] sections, int minY, int ceilingY) {
        long key = chunkColumnKey(chunkX, chunkZ);
        ConcurrentHashMap<Long, ColumnPalette> cache = worldCaches.computeIfAbsent(
                worldName, k -> new ConcurrentHashMap<>());

        ColumnPalette existing = cache.get(key);
        if (existing != null) return existing;

        ColumnPalette built = buildPalette(sections, minY, ceilingY,
                plugin.getSmartFillSampleRadius(), plugin.getSmartFillMaxPaletteSize());

        if (built == null) {
            built = getDefaultPalette(worldName, minY, sections.length);
        }

        if (built != null) {
            cache.put(key, built);
        }
        return built;
    }

    /**
     * Returns the cached palette for this column without building.
     * Used by BLOCK_CHANGE / MULTI_BLOCK_CHANGE which don't have full sections.
     */
    public ColumnPalette getCachedPalette(String worldName, int chunkX, int chunkZ) {
        ConcurrentHashMap<Long, ColumnPalette> cache = worldCaches.get(worldName);
        if (cache == null) return null;
        return cache.get(chunkColumnKey(chunkX, chunkZ));
    }

    /**
     * Deterministic block state selection based on position hash, scoped to
     * the section band that blockY actually falls in.
     * Same (x, y, z) always returns the same block — no flicker across re-sends.
     */
    public WrappedBlockState selectState(ColumnPalette palette, int blockX, int blockY, int blockZ) {
        WeightedPalette band = palette.bandFor(blockY);
        return band.states[selectIndex(band, blockX, blockY, blockZ)];
    }

    /**
     * Deterministic block ID selection based on position hash, scoped to the
     * section band that blockY actually falls in.
     * For MULTI_BLOCK_CHANGE / BLOCK_CHANGE which use integer block IDs.
     */
    public int selectId(ColumnPalette palette, int blockX, int blockY, int blockZ) {
        WeightedPalette band = palette.bandFor(blockY);
        return band.globalIds[selectIndex(band, blockX, blockY, blockZ)];
    }

    /**
     * True if (localX, localZ) has no real roofing block above worldY
     * anywhere in the real chunk column — i.e. it's open to the sky, not a
     * roofed-over underground pocket. Callers should never mask a position
     * where this returns true, even if that position falls inside the
     * void-y..ceiling-y band, because it's genuinely outdoor terrain (a
     * shallow beach, ocean surface, low valley, a flower or sugar cane
     * stalk sitting on open ground, etc.), not a cave.
     *
     * <p>Returns false (i.e. "treat as maskable") when the palette carries no
     * topSolidY data at all, preserving the old behavior for default/fallback
     * palettes that weren't built from real column data.
     */
    public boolean isOpenToSky(ColumnPalette palette, int localX, int localZ, int worldY) {
        if (palette == null || palette.topSolidY == null) return false;
        int topSolid = palette.topSolidY[localZ * 16 + localX];
        return worldY >= topSolid;
    }

    /**
     * True if (localX, localZ, worldY) falls inside a shallow, fully
     * enclosed pocket near the surface — floor, interior air, and roof all
     * included — that this column's scan identified as a likely
     * player-built room (PvP arena, crystal hole, etc.) rather than natural
     * cave/vault. See config.yml's surface-structures comment for the full
     * reasoning. Callers should treat true as "never mask this position",
     * overriding both isSafeTerrain and isOpenToSky — the whole point is to
     * leave the roof material (obsidian, etc.) alone too, not just the air
     * inside it.
     *
     * <p>Returns false when the palette carries no shallowPocketFloorY data
     * (default/fallback palettes), or when this column has no qualifying
     * pocket at all.
     */
    public boolean isExemptSurfaceStructure(ColumnPalette palette, int localX, int localZ, int worldY) {
        if (palette == null || palette.shallowPocketFloorY == null || palette.topSolidY == null) return false;
        int colIdx = localZ * 16 + localX;
        int floorY = palette.shallowPocketFloorY[colIdx];
        if (floorY == Integer.MIN_VALUE) return false; // no qualifying pocket in this column
        int roofY = palette.topSolidY[colIdx];
        return worldY >= floorY && worldY <= roofY;
    }

    /** Invalidate a specific column's cached palette (e.g. after a real block change). */
    public void invalidate(String worldName, int chunkX, int chunkZ) {
        ConcurrentHashMap<Long, ColumnPalette> cache = worldCaches.get(worldName);
        if (cache != null) cache.remove(chunkColumnKey(chunkX, chunkZ));
    }

    /** Clear all cached palettes (reload / disable). */
    public void clearAll() {
        worldCaches.clear();
    }

    /** Total cached palettes across all worlds (for /fpac stats). */
    public int getCacheSize() {
        int total = 0;
        for (ConcurrentHashMap<Long, ColumnPalette> c : worldCaches.values()) total += c.size();
        return total;
    }

    // ── Internal ─────────────────────────────────────────────────────────

    /**
     * Builds a per-section-band palette set by sampling terrain blocks
     * around each band, plus a per-column topSolidY sky-exposure map.
     */
    private ColumnPalette buildPalette(BaseChunk[] sections, int minY, int ceilingY,
                                        int sampleRadiusSections, int maxPaletteSize) {

        // BUGFIX: compute the real topmost-ROOFING-block height for every
        // column in the FULL vertical chunk (sections covers the whole
        // column here, not just the sampled/masked band). Without this, the
        // fill loop had no way to tell "this air is a real underground
        // pocket with rock above it" apart from "this air is just open sky
        // above a beach/ocean floor that happens to sit below ceiling-y" —
        // both looked identical (air, Y below ceilingY), so shallow surface
        // water/sand got a floating stone blanket filled in above it up to
        // ceilingY. Scanned top-down so each column stops at its first real
        // roofing block, skipping past thin/passable decoration (grass,
        // flowers, sugar cane, kelp, torches, snow layers, etc. — see
        // isNonRoofType) that would otherwise falsely "enclose" the ground
        // or plant right underneath them.
        //
        // IMPROVEMENT: while we're already scanning top-down per column,
        // also detect a shallow, fully-enclosed pocket right below that
        // first roof — a player-built PvP arena/crystal hole/small room is
        // typically a short (a handful of blocks) enclosed gap near the
        // surface, unlike a real cave or vault which either goes much
        // deeper or is much taller. See config.yml's surface-structures
        // comment and isExemptSurfaceStructure's javadoc for the reasoning.
        int[] topSolidY = new int[256];
        int[] shallowPocketFloorY = new int[256];
        Arrays.fill(topSolidY, minY - 1); // sentinel: "no solid found", column is fully open
        Arrays.fill(shallowPocketFloorY, Integer.MIN_VALUE); // sentinel: "no qualifying pocket"

        int surfaceStructureMinY = plugin.getSmartFillSurfaceStructureMinY();
        int maxShallowPocketHeight = plugin.getSmartFillMaxShallowPocketHeight();
        int worldTopY = minY + sections.length * 16 - 1;

        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                int colIdx = lz * 16 + lx;

                int roofY = minY - 1;
                for (int y = worldTopY; y >= minY; y--) {
                    StateType t = blockTypeAt(sections, minY, lx, y, lz);
                    if (!isNonRoofType(t)) { roofY = y; break; }
                }
                topSolidY[colIdx] = roofY;

                if (roofY != minY - 1 && roofY >= surfaceStructureMinY) {
                    int depthScanned = 0;
                    for (int y2 = roofY - 1; y2 >= minY && depthScanned < maxShallowPocketHeight; y2--, depthScanned++) {
                        StateType t2 = blockTypeAt(sections, minY, lx, y2, lz);
                        if (!isNonRoofType(t2)) {
                            shallowPocketFloorY[colIdx] = y2; // found a floor within the shallow limit
                            break;
                        }
                    }
                    // If the loop ran out of depth budget without finding a
                    // floor, this pocket is either deeper than
                    // max-pocket-height or open all the way down - not a
                    // shallow room, leave it at the "no exemption" sentinel.
                }
            }
        }

        // BUGFIX: this used to hardcode "64" as the top of the sampling range
        // no matter what protection-y/ceiling-y was actually configured, so a
        // server running a custom ceiling either sampled way past real
        // terrain (ceiling < 64, picking up surface blocks that don't belong
        // in a cave palette) or stopped sampling too early to see most of the
        // band it was about to fill (ceiling > 64). Now it samples the whole
        // band up to ceilingY, plus sampleRadiusSections extra sections above
        // that so the palette also picks up a bit of the real terrain just
        // past the camouflage boundary for a more natural blend.
        int startSection = 0;
        int ceilingSection = Math.max(0, (ceilingY - minY) / 16);
        int endSection = Math.min(sections.length - 1, ceilingSection + Math.max(0, sampleRadiusSections));

        // BUGFIX: see ColumnPalette's javadoc — build one palette PER section
        // band from a small local window (sampleRadiusSections above/below
        // that band only), instead of pooling the entire sampled range into
        // one chunk-wide mix. Keeps fill material depth-appropriate.
        WeightedPalette[] bandPalettes = new WeightedPalette[sections.length];
        boolean anyBand = false;

        for (int si = startSection; si <= endSection; si++) {
            Map<Integer, WrappedBlockState> idToState = new HashMap<>();
            Map<Integer, Integer> freq = new HashMap<>();

            int loSection = Math.max(0, si - sampleRadiusSections);
            int hiSection = Math.min(sections.length - 1, si + sampleRadiusSections);

            for (int s2 = loSection; s2 <= hiSection; s2++) {
                BaseChunk section = sections[s2];
                if (section == null || section.isEmpty()) continue;

                for (int ly = 0; ly < 16; ly++) {
                    for (int lx = 0; lx < 16; lx++) {
                        for (int lz = 0; lz < 16; lz++) {
                            try {
                                WrappedBlockState state = section.get(lx, ly, lz);
                                if (state == null) continue;
                                StateType type = state.getType();
                                if (type != null && TERRAIN_TYPES.contains(type)) {
                                    int id = state.getGlobalId();
                                    freq.merge(id, 1, Integer::sum);
                                    idToState.putIfAbsent(id, state);
                                }
                            } catch (Exception ignored) {}
                        }
                    }
                }
            }

            WeightedPalette band = toWeightedPalette(freq, idToState, maxPaletteSize);
            if (band != null) {
                bandPalettes[si] = band;
                anyBand = true;
            }
        }

        if (!anyBand) {
            // No sampled terrain anywhere in range (e.g. superflat/void chunk)
            // — still return a single fallback-block palette for every band,
            // but keep the computed topSolidY so the sky-exposure check
            // upstream still works instead of silently losing it.
            WrappedBlockState fallback = plugin.getSmartFillFallback();
            if (fallback == null) return null;
            WeightedPalette single = singleBlockPalette(fallback);
            Arrays.fill(bandPalettes, single);
            return new ColumnPalette(bandPalettes, topSolidY, shallowPocketFloorY, minY);
        }

        // Nearest-neighbor fill for any band left null (its own local window
        // had no terrain samples — e.g. a band that's entirely pre-existing
        // cave) so every lookup always resolves to something reasonable
        // instead of falling all the way back to a flat default.
        fillNearestBand(bandPalettes);

        return new ColumnPalette(bandPalettes, topSolidY, shallowPocketFloorY, minY);
    }

    private static WeightedPalette toWeightedPalette(Map<Integer, Integer> freq,
                                                       Map<Integer, WrappedBlockState> idToState,
                                                       int maxPaletteSize) {
        if (freq.isEmpty()) return null;

        List<Map.Entry<Integer, Integer>> sorted = new ArrayList<>(freq.entrySet());
        sorted.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));

        int size = Math.min(maxPaletteSize, sorted.size());
        WrappedBlockState[] states = new WrappedBlockState[size];
        int[] globalIds = new int[size];
        int[] cumulativeWeights = new int[size];
        int cumulative = 0;

        for (int i = 0; i < size; i++) {
            int id = sorted.get(i).getKey();
            globalIds[i] = id;
            states[i] = idToState.get(id);
            cumulative += sorted.get(i).getValue();
            cumulativeWeights[i] = cumulative;
        }

        return new WeightedPalette(states, globalIds, cumulativeWeights, cumulative);
    }

    /** Fills any null band with the nearest non-null band (by section distance). */
    private static void fillNearestBand(WeightedPalette[] bandPalettes) {
        int n = bandPalettes.length;
        for (int si = 0; si < n; si++) {
            if (bandPalettes[si] != null) continue;
            for (int dist = 1; dist < n; dist++) {
                int down = si - dist, up = si + dist;
                if (down >= 0 && bandPalettes[down] != null) { bandPalettes[si] = bandPalettes[down]; break; }
                if (up < n && bandPalettes[up] != null) { bandPalettes[si] = bandPalettes[up]; break; }
            }
        }
    }

    /**
     * Position-based deterministic index into a section band's palette.
     * Uses a hash of (x, y, z) mapped into the cumulative weight distribution.
     */
    private int selectIndex(WeightedPalette palette, int blockX, int blockY, int blockZ) {
        int hash = (blockX * 73856093) ^ (blockY * 19349663) ^ (blockZ * 83492791);
        int bucket = (hash & 0x7FFFFFFF) % palette.totalWeight;
        for (int i = 0; i < palette.cumulativeWeights.length; i++) {
            if (bucket < palette.cumulativeWeights[i]) return i;
        }
        return palette.states.length - 1; // safety fallback
    }

    private static long chunkColumnKey(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
    }

    /**
     * Fetches the block type at a world Y within a chunk column, mapping it
     * to the right section index/local Y. Returns null (reads as air/open)
     * for an out-of-range Y, a missing/empty section, or any read failure -
     * callers treat null the same as air via isNonRoofType.
     */
    private static StateType blockTypeAt(BaseChunk[] sections, int minY, int lx, int worldY, int lz) {
        int si = (worldY - minY) >> 4;
        if (si < 0 || si >= sections.length) return null;
        BaseChunk section = sections[si];
        if (section == null || section.isEmpty()) return null;
        int ly = (worldY - minY) & 15;
        try {
            WrappedBlockState st = section.get(lx, ly, lz);
            return st != null ? st.getType() : null;
        } catch (Exception e) {
            return null;
        }
    }
}
