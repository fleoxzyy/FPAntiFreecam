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
        // NEW (1.5.4): calcite and smooth basalt (geode shells), cobblestone
        // and cobbled deepslate (placed back by players) are what chunk
        // finders look for, so they are masked in hidden areas and never
        // used as fill.
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
     * NEW (follow-terrain): blocks that count as natural cover above a spot.
     * Blocks players often build with (cobblestone, packed mud, and Nether /
     * End blocks in the Overworld) don't count, nor do planks, glass, logs
     * or leaves, so builds and trees never count as "underground".
     */
    private static final Set<StateType> NATURAL_COVER_TYPES;
    static {
        Set<StateType> s = new HashSet<>(TERRAIN_TYPES);
        s.add(StateTypes.CALCITE);
        s.add(StateTypes.SMOOTH_BASALT);
        s.remove(StateTypes.PACKED_MUD);
        s.remove(StateTypes.NETHERRACK);
        s.remove(StateTypes.BLACKSTONE);
        s.remove(StateTypes.BASALT);
        s.remove(StateTypes.SOUL_SAND);
        s.remove(StateTypes.SOUL_SOIL);
        s.remove(StateTypes.END_STONE);
        s.addAll(SURFACE_ONLY_SAFE_TYPES);
        s.remove(StateTypes.FARMLAND);
        s.add(StateTypes.COAL_ORE);     s.add(StateTypes.DEEPSLATE_COAL_ORE);
        s.add(StateTypes.IRON_ORE);     s.add(StateTypes.DEEPSLATE_IRON_ORE);
        s.add(StateTypes.COPPER_ORE);   s.add(StateTypes.DEEPSLATE_COPPER_ORE);
        s.add(StateTypes.GOLD_ORE);     s.add(StateTypes.DEEPSLATE_GOLD_ORE);
        s.add(StateTypes.REDSTONE_ORE); s.add(StateTypes.DEEPSLATE_REDSTONE_ORE);
        s.add(StateTypes.LAPIS_ORE);    s.add(StateTypes.DEEPSLATE_LAPIS_ORE);
        s.add(StateTypes.DIAMOND_ORE);  s.add(StateTypes.DEEPSLATE_DIAMOND_ORE);
        s.add(StateTypes.EMERALD_ORE);  s.add(StateTypes.DEEPSLATE_EMERALD_ORE);
        s.add(StateTypes.RAW_IRON_BLOCK);
        s.add(StateTypes.RAW_COPPER_BLOCK);
        NATURAL_COVER_TYPES = Collections.unmodifiableSet(s);
    }

    /**
     * Follow-terrain edge margin: a column only counts as covered at a height
     * if every column within this many blocks is covered there too. Keeps
     * cliff faces, overhang edges, thin walls and cave mouths real.
     */
    private static final int EDGE_MARGIN = 3;

    /**
     * Blocks that must NEVER be masked, on top of TERRAIN_TYPES (which are
     * already safe since they're generic rock/dirt). BEDROCK because a
     * client noticing "diggable bedrock" is an instant giveaway and can also
     * break client-side world-border/height assumptions.
     *
     * <p>BUGFIX: WATER/LAVA used to be here too (physics worries), so every
     * aquifer, lava lake and flooded cave under a roof stayed visible in
     * freecam. The near radius and the flood fill always keep fluids real
     * around the player, the same as air, and the hard floor already masked
     * them. Open water (oceans, lakes, rivers) is still never touched: fluids
     * don't count as a roof, and neither does ice now (frozen oceans).
     */
    private static final Set<StateType> NEVER_MASK_EXTRA;
    static {
        Set<StateType> s = new HashSet<>();
        s.add(StateTypes.BEDROCK);
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
     * NEW (1.5.4): same as isSafeTerrain, but rotated deepslate (axis x or z)
     * is maskable. Worldgen only places deepslate[axis=y], so any other axis
     * was placed by a player, which chunk finders flag.
     */
    public static boolean isSafeTerrain(WrappedBlockState state) {
        if (state == null) return false;
        StateType type = state.getType();
        if (!isSafeTerrain(type)) return false;
        return type != StateTypes.DEEPSLATE || !isRotatedDeepslate(state.getGlobalId());
    }

    private static volatile int deepslateUpId = Integer.MIN_VALUE;

    /** True for a deepslate global id other than deepslate[axis=y]. */
    static boolean isRotatedDeepslate(int globalId) {
        int up = deepslateUpId;
        if (up == Integer.MIN_VALUE) {
            try {
                WrappedBlockState s = WrappedBlockState.getByString("minecraft:deepslate[axis=y]");
                up = s != null ? s.getGlobalId() : -1;
            } catch (Exception e) {
                up = -1;
            }
            deepslateUpId = up;
        }
        return up >= 0 && globalId != up;
    }

    /** NEW (1.5.4): sampler check, keeps rotated deepslate out of the fill. */
    private static boolean isSampleable(WrappedBlockState state) {
        StateType type = state != null ? state.getType() : null;
        if (type == null || !TERRAIN_TYPES.contains(type)) return false;
        return type != StateTypes.DEEPSLATE || !isRotatedDeepslate(state.getGlobalId());
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
        // Mangrove roots let water and light through; swamp water under them isn't a cave.
        s.add("mangrove_roots");
        NON_ROOF_KEYS = Collections.unmodifiableSet(s);
    }

    /**
     * Best-effort registry key for a StateType (e.g. "short_grass"), with the
     * "minecraft:" namespace stripped if present. Returns null on any failure
     * so callers can fail safe (treat as a real roof) instead of throwing.
     */
    static String registryKey(StateType type) {
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
        // BUGFIX: see-through ice over a frozen ocean isn't a roof. Now that
        // fluids can be masked, the water under it would turn to stone.
        if (type == StateTypes.ICE || type == StateTypes.FROSTED_ICE) return true;
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

        /**
         * IMPROVEMENT: fill block per 4×4×4 cell (index si * 64 + sx | sy << 2
         * | sz << 4), the most common real rock in and around that cell.
         * Value k > 0 means cellStates[k - 1]; 0 (or a null array, for
         * default palettes) falls back to the band mix.
         */
        byte[] cellIndex;
        WrappedBlockState[] cellStates;

        /**
         * NEW (follow-terrain): per column (lz * 16 + lx), the highest Y with
         * enough natural cover above it, after the edge margin. Masking in
         * that column reaches max(ceiling-y, coverTop). Null when no column
         * reaches above ceiling-y (and always when follow-terrain is off).
         */
        short[] coverTop;
        int maxCover = Integer.MIN_VALUE;

        /** Cover before the edge margin, kept for the neighbour chunks' margin. */
        short[] coverRaw;

        /**
         * Reveal side bound, at least coverTop and never lowered while this
         * column stays cached (a rebuild keeps the old values), so the reveal
         * always reaches every block a client may still have masked.
         */
        short[] revealTop;
        int maxReveal = Integer.MIN_VALUE;

        /**
         * Neighbour chunks (bit (dx + 1) * 3 + (dz + 1)) that weren't cached
         * when the edge margin was worked out. When one of them is built,
         * this palette is marked stale so the next send uses the real edge.
         */
        int guessedNeighbours;

        /**
         * Set by {@link #invalidate}. A stale palette is rebuilt on the next
         * chunk send but still answers block changes until then, so they
         * match what the client was sent.
         */
        volatile boolean stale;

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
                                            BaseChunk[] sections, int minY, int ceilingY,
                                            boolean followTerrain) {
        long key = chunkColumnKey(chunkX, chunkZ);
        ConcurrentHashMap<Long, ColumnPalette> cache = worldCaches.computeIfAbsent(
                worldName, k -> new ConcurrentHashMap<>());

        ColumnPalette existing = cache.get(key);
        if (existing != null && !existing.stale) return existing;

        ColumnPalette built = buildPalette(worldName, chunkX, chunkZ, sections, minY, ceilingY,
                plugin.getSmartFillSampleRadius(), plugin.getSmartFillMaxPaletteSize(), followTerrain);

        if (built == null) {
            built = getDefaultPalette(worldName, minY, sections.length);
        }

        if (built != null) {
            if (existing != null) keepReveal(built, existing);
            cache.put(key, built);
            if (built.coverRaw != null) {
                // Neighbours that guessed "no cover" for this chunk's side
                // get the real edge on their next send.
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dz == 0) continue;
                        ColumnPalette n = cache.get(chunkColumnKey(chunkX + dx, chunkZ + dz));
                        if (n != null && (n.guessedNeighbours & (1 << ((1 - dx) * 3 + (1 - dz)))) != 0) {
                            n.stale = true;
                        }
                    }
                }
            }
        }
        return built;
    }

    /** Other players may still have the old masking: the reveal bound only grows. */
    private static void keepReveal(ColumnPalette built, ColumnPalette old) {
        short[] o = old.revealTop;
        if (o == null) return;
        short[] n = built.revealTop;
        if (n == null) {
            built.revealTop = o; // never written after publishing, safe to share
        } else {
            short[] m = new short[256];
            for (int i = 0; i < 256; i++) m[i] = (short) Math.max(n[i], o[i]);
            built.revealTop = m;
        }
        built.maxReveal = Math.max(built.maxReveal, old.maxReveal);
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
        WrappedBlockState cell = cellState(palette, blockX, blockY, blockZ);
        if (cell != null) return cell;
        WeightedPalette band = palette.bandFor(blockY);
        if (palette.cellIndex != null) return band.states[0];
        return band.states[selectIndex(band, blockX, blockY, blockZ)];
    }

    /**
     * Deterministic block ID selection based on position hash, scoped to the
     * section band that blockY actually falls in.
     * For MULTI_BLOCK_CHANGE / BLOCK_CHANGE which use integer block IDs.
     */
    public int selectId(ColumnPalette palette, int blockX, int blockY, int blockZ) {
        WrappedBlockState cell = cellState(palette, blockX, blockY, blockZ);
        if (cell != null) return cell.getGlobalId();
        WeightedPalette band = palette.bandFor(blockY);
        if (palette.cellIndex != null) return band.globalIds[0];
        return band.globalIds[selectIndex(band, blockX, blockY, blockZ)];
    }

    private static WrappedBlockState cellState(ColumnPalette palette, int x, int y, int z) {
        byte[] cells = palette.cellIndex;
        if (cells == null) return null;
        int ry = y - palette.minY;
        if (ry < 0) return null;
        int idx = (ry >> 4) * 64 + (((x & 15) >> 2) | ((ry & 15) >> 2) << 2 | ((z & 15) >> 2) << 4);
        if (idx >= cells.length) return null;
        int k = cells[idx];
        return k > 0 ? palette.cellStates[k - 1] : null;
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

    /**
     * Invalidate a specific column's cached palette (e.g. after a real block change).
     * BUGFIX: the palette used to be dropped, so block changes until the
     * next chunk send were filled without the open-sky check or the rock
     * map (stone could appear over open water). It is now only marked stale.
     */
    public void invalidate(String worldName, int chunkX, int chunkZ) {
        ConcurrentHashMap<Long, ColumnPalette> cache = worldCaches.get(worldName);
        if (cache == null) return;
        ColumnPalette p = cache.get(chunkColumnKey(chunkX, chunkZ));
        if (p != null) p.stale = true;
    }

    // ── Follow-terrain lookups ────────────────────────────────────────────

    /** Masking top of a column: ceiling-y, or higher where it has natural cover. */
    static int maskTop(ColumnPalette p, int lx, int lz, int ceilingY) {
        if (p == null || p.coverTop == null) return ceilingY;
        return Math.max(ceilingY, p.coverTop[(lz << 4) | lx]);
    }

    /** Highest masking top of any column in the chunk. */
    static int chunkMaskTop(ColumnPalette p, int ceilingY) {
        if (p == null || p.coverTop == null) return ceilingY;
        return Math.max(ceilingY, p.maxCover);
    }

    /** Reveal-side top of a column (at least {@link #maskTop}). */
    static int revealTop(ColumnPalette p, int lx, int lz, int ceilingY) {
        if (p == null || p.revealTop == null) return ceilingY;
        return Math.max(ceilingY, p.revealTop[(lz << 4) | lx]);
    }

    /** True if a change to this block type can change a column's natural cover. */
    static boolean affectsCover(StateType type) {
        return type == null || type.isAir() || NATURAL_COVER_TYPES.contains(type);
    }

    /**
     * Follow-terrain: a block change above ceiling-y at or just above the
     * column's roof can change its cover (mining into a mountain, a new
     * roof), so the palette must be rebuilt.
     */
    static boolean coverMayChange(ColumnPalette p, int lx, int lz, int y, int ceilingY) {
        if (p == null || p.topSolidY == null || y <= ceilingY) return false;
        return y <= p.topSolidY[(lz << 4) | lx] + 1;
    }

    /**
     * Highest masking (or, with reveal, reveal) top across a range of chunk
     * columns. Never lower than ceilingY. Any thread.
     */
    public int maxTopInRange(String worldName, int cx0, int cz0, int cx1, int cz1, int ceilingY, boolean reveal) {
        ConcurrentHashMap<Long, ColumnPalette> cache = worldCaches.get(worldName);
        if (cache == null || cache.isEmpty()) return ceilingY;
        int top = ceilingY;
        for (int cx = cx0; cx <= cx1; cx++) {
            for (int cz = cz0; cz <= cz1; cz++) {
                ColumnPalette p = cache.get(chunkColumnKey(cx, cz));
                if (p == null) continue;
                int t = reveal ? (p.revealTop != null ? p.maxReveal : Integer.MIN_VALUE)
                               : (p.coverTop != null ? p.maxCover : Integer.MIN_VALUE);
                if (t > top) top = t;
            }
        }
        return top;
    }

    /** Per-block reveal-top lookups for the flood fill and sight sweep. */
    public CoverLookup coverLookup(String worldName, int ceilingY) {
        return new CoverLookup(worldCaches.get(worldName), ceilingY);
    }

    /**
     * NEW: small direct-mapped cache of each chunk's reveal tops, so a flood
     * fill or a sight sweep does one map lookup per chunk instead of one per
     * block. Single thread, short-lived.
     */
    public static final class CoverLookup {
        private static final short[] NONE = new short[0];
        private final ConcurrentHashMap<Long, ColumnPalette> cache;
        private final int ceilingY;
        // IMPROVEMENT: 16×16 chunk window, one slot per chunk. The old 64-slot
        // hash put chunks 2 apart diagonally in the same slot, so a flood fill
        // or sweep kept re-reading the palette map (~1% CPU on a weak host).
        private final long[] keys = new long[256];
        private final short[][] tops = new short[256][];
        private final int[] maxes = new int[256];

        CoverLookup(ConcurrentHashMap<Long, ColumnPalette> cache, int ceilingY) {
            this.cache = cache;
            this.ceilingY = ceilingY;
        }

        private int slot(int cx, int cz) {
            long key = chunkColumnKey(cx, cz);
            int slot = ((cx & 15) << 4) | (cz & 15);
            if (tops[slot] == null || keys[slot] != key) {
                ColumnPalette p = cache != null ? cache.get(key) : null;
                short[] t = p != null ? p.revealTop : null;
                keys[slot] = key;
                tops[slot] = t != null ? t : NONE;
                maxes[slot] = t != null ? Math.max(ceilingY, p.maxReveal) : ceilingY;
            }
            return slot;
        }

        /** Reveal tops of the chunk holding world (x, z), indexed ((z & 15) << 4) | (x & 15). Empty = none. */
        public short[] columnTops(int x, int z) {
            return tops[slot(x >> 4, z >> 4)];
        }

        /** Reveal top of the block column at world (x, z). */
        public int top(int x, int z) {
            short[] t = tops[slot(x >> 4, z >> 4)];
            if (t == NONE) return ceilingY;
            return Math.max(ceilingY, t[((z & 15) << 4) | (x & 15)]);
        }

        /** Highest reveal top in the chunk holding world (x, z). */
        public int chunkTop(int x, int z) {
            return maxes[slot(x >> 4, z >> 4)];
        }

        /** Highest reveal top in the chunks within r blocks of world (x, z) (at most 4 for r < 16). */
        public int chunkTopAround(int x, int z, int r) {
            int t = ceilingY;
            for (int cx = (x - r) >> 4; cx <= (x + r) >> 4; cx++) {
                for (int cz = (z - r) >> 4; cz <= (z + r) >> 4; cz++) {
                    int m = maxes[slot(cx, cz)];
                    if (m > t) t = m;
                }
            }
            return t;
        }
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
    private ColumnPalette buildPalette(String worldName, int chunkX, int chunkZ,
                                        BaseChunk[] sections, int minY, int ceilingY,
                                        int sampleRadiusSections, int maxPaletteSize,
                                        boolean followTerrain) {

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

        short[] coverRaw = null;
        short[] coverTop = null;
        int maxCover = Integer.MIN_VALUE;
        int[] guessed = { 0 };
        if (followTerrain) {
            try {
                coverRaw = scanCover(sections, minY, topSolidY, ceilingY, plugin.getSmartFillMinCover());
                int rawMax = Integer.MIN_VALUE;
                for (short v : coverRaw) if (v > rawMax) rawMax = v;
                if (rawMax <= ceilingY) {
                    coverRaw = null; // flat here: neighbours read null as "no cover"
                } else {
                    coverTop = applyEdgeMargin(worldName, chunkX, chunkZ, coverRaw, ceilingY, guessed);
                    for (short v : coverTop) if (v > maxCover) maxCover = v;
                    if (maxCover <= ceilingY) coverTop = null; // only thin edges above ceiling-y
                }
            } catch (Exception e) {
                coverRaw = null;
                coverTop = null; // ceiling-y only
                guessed[0] = 0;
            }
        }
        int topY = coverTop != null ? maxCover : ceilingY;
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
        int ceilingSection = Math.max(0, Math.min(sections.length - 1, (topY - minY) / 16));
        int endSection = Math.min(sections.length - 1, ceilingSection + Math.max(0, sampleRadiusSections));

        // BUGFIX: see ColumnPalette's javadoc — build one palette PER section
        // band from a small local window (sampleRadiusSections above/below
        // that band only), instead of pooling the entire sampled range into
        // one chunk-wide mix. Keeps fill material depth-appropriate.
        WeightedPalette[] bandPalettes = new WeightedPalette[sections.length];
        boolean anyBand = false;
        // NEW: never sample a block Anti-Xray hides into a fill palette.
        // TERRAIN_TYPES has no ores by default, but anti-xray hidden-ores is
        // admin-configurable (e.g. clay or gravel for custom "ore" setups); a
        // fake cave wall made of a hidden type would expose exactly what
        // Anti-Xray is masking.
        AntiXrayEngine xray = plugin.getAntiXrayEngine();
        boolean excludeOres = xray != null && xray.isEnabled();

        // IMPROVEMENT: each section is counted once (block ids, type check
        // cached per id) and the bands add up their window, instead of every
        // band reading all blocks of up to 3 sections again. Same result.
        @SuppressWarnings("unchecked")
        Map<Integer, Integer>[] sectionFreq = new Map[sections.length];
        Map<Integer, WrappedBlockState> idToState = new HashMap<>();
        Map<Integer, Boolean> sampleable = new HashMap<>();
        int countFrom = Math.max(0, startSection - sampleRadiusSections);
        int countTo = Math.min(sections.length - 1, endSection + sampleRadiusSections);
        for (int s2 = countFrom; s2 <= countTo; s2++) {
            BaseChunk section = sections[s2];
            if (section == null || section.isEmpty()) continue;
            Map<Integer, Integer> freq = new HashMap<>();
            int lastId = Integer.MIN_VALUE;
            boolean lastOk = false;
            for (int ly = 0; ly < 16; ly++) {
                for (int lx = 0; lx < 16; lx++) {
                    for (int lz = 0; lz < 16; lz++) {
                        try {
                            int id = section.getBlockId(lx, ly, lz);
                            boolean ok;
                            if (id == lastId) {
                                ok = lastOk;
                            } else {
                                Boolean known = sampleable.get(id);
                                if (known == null) {
                                    WrappedBlockState state = section.get(lx, ly, lz);
                                    known = isSampleable(state)
                                            && !(excludeOres && xray.isAnyHiddenOre(id));
                                    if (known) idToState.putIfAbsent(id, state);
                                    sampleable.put(id, known);
                                }
                                ok = known;
                                lastId = id;
                                lastOk = ok;
                            }
                            if (ok) freq.merge(id, 1, Integer::sum);
                        } catch (Exception ignored) {}
                    }
                }
            }
            if (!freq.isEmpty()) sectionFreq[s2] = freq;
        }

        for (int si = startSection; si <= endSection; si++) {
            Map<Integer, Integer> freq = new HashMap<>();

            int loSection = Math.max(0, si - sampleRadiusSections);
            int hiSection = Math.min(sections.length - 1, si + sampleRadiusSections);

            for (int s2 = loSection; s2 <= hiSection; s2++) {
                Map<Integer, Integer> f = sectionFreq[s2];
                if (f == null) continue;
                for (Map.Entry<Integer, Integer> e : f.entrySet()) freq.merge(e.getKey(), e.getValue(), Integer::sum);
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
            ColumnPalette flat = new ColumnPalette(bandPalettes, topSolidY, shallowPocketFloorY, minY);
            setCover(flat, coverRaw, coverTop, maxCover, guessed[0]);
            return flat;
        }

        // Nearest-neighbor fill for any band left null (its own local window
        // had no terrain samples — e.g. a band that's entirely pre-existing
        // cave) so every lookup always resolves to something reasonable
        // instead of falling all the way back to a flat default.
        fillNearestBand(bandPalettes);

        ColumnPalette result = new ColumnPalette(bandPalettes, topSolidY, shallowPocketFloorY, minY);
        setCover(result, coverRaw, coverTop, maxCover, guessed[0]);
        try {
            buildCellMap(result, sections, Math.min(sections.length - 1, ceilingSection + 1),
                    excludeOres ? xray : null);
        } catch (Exception ignored) {
            result.cellIndex = null; // band mix only
        }
        return result;
    }

    /**
     * IMPROVEMENT: the fill used to pick each block from the band's weighted
     * mix by position hash, so a cave inside plain stone could come out as a
     * speckle of stone, deepslate, tuff and andesite, which stood out. Now
     * every 4×4×4 cell uses the most common real rock in that cell. Cells
     * that are all air (the cave itself) take the most common rock of the
     * cells around them, growing inward up to 32 blocks. Anything left after
     * that (a huge open cave) uses the band's most common rock instead of
     * the speckled mix. Built once per palette (cached).
     */
    private static void buildCellMap(ColumnPalette palette, BaseChunk[] sections, int lastSection,
                                     AntiXrayEngine xray) {
        int cellsTotal = (lastSection + 1) * 64;
        if (cellsTotal <= 0) return;
        byte[] slotOf = new byte[cellsTotal];
        int[] countOf = new int[cellsTotal];
        List<WrappedBlockState> states = new ArrayList<>();
        Map<Integer, Integer> idSlot = new HashMap<>(); // global id -> slot (1..127), 0 = not rock

        int[] ids = new int[8], counts = new int[8];
        for (int si = 0; si <= lastSection; si++) {
            BaseChunk section = sections[si];
            if (section == null || section.isEmpty()) continue;
            for (int c = 0; c < 64; c++) {
                int bx = (c & 3) << 2, by = ((c >> 2) & 3) << 2, bz = ((c >> 4) & 3) << 2;
                int distinct = 0;
                int lastId = Integer.MIN_VALUE, lastSlot = 0;
                for (int ly = by; ly < by + 4; ly++) {
                    for (int lx = bx; lx < bx + 4; lx++) {
                        for (int lz = bz; lz < bz + 4; lz++) {
                            int id = section.getBlockId(lx, ly, lz);
                            int slot;
                            if (id == lastId) {
                                slot = lastSlot;
                            } else {
                                Integer known = idSlot.get(id);
                                if (known == null) {
                                    known = 0;
                                    WrappedBlockState st = section.get(lx, ly, lz);
                                    if (isSampleable(st)
                                            && (xray == null || !xray.isAnyHiddenOre(id))
                                            && states.size() < 127) {
                                        states.add(st);
                                        known = states.size();
                                    }
                                    idSlot.put(id, known);
                                }
                                slot = known;
                                lastId = id;
                                lastSlot = slot;
                            }
                            if (slot == 0) continue;
                            int j = 0;
                            while (j < distinct && ids[j] != slot) j++;
                            if (j == distinct) {
                                if (distinct == ids.length) continue; // rare: > 8 rock types in one cell
                                ids[distinct] = slot; counts[distinct] = 0; distinct++;
                            }
                            counts[j]++;
                        }
                    }
                }
                int best = -1;
                for (int j = 0; j < distinct; j++) {
                    if (best < 0 || counts[j] > counts[best] || (counts[j] == counts[best] && ids[j] < ids[best])) best = j;
                }
                if (best >= 0) {
                    slotOf[si * 64 + c] = (byte) ids[best];
                    countOf[si * 64 + c] = counts[best];
                }
            }
        }
        if (states.isEmpty()) return;

        // Air cells: take the rock of the cells around them, one cell further
        // per round. Each round only reads the previous one, so the result
        // doesn't depend on loop order.
        for (int round = 0; round < 8; round++) {
            byte[] nextSlot = slotOf.clone();
            int[] nextCount = countOf.clone();
            boolean changed = false;
            for (int i = 0; i < cellsTotal; i++) {
                if (slotOf[i] != 0) continue;
                int si = i >> 6, c = i & 63;
                int sx = c & 3, sy = ((c >> 2) & 3) + si * 4, sz = (c >> 4) & 3;
                int distinct = 0;
                for (int dx = -1; dx <= 1; dx++) {
                    int nx = sx + dx;
                    if (nx < 0 || nx > 3) continue;
                    for (int dy = -1; dy <= 1; dy++) {
                        int ny = sy + dy;
                        if (ny < 0 || ny >= (lastSection + 1) * 4) continue;
                        for (int dz = -1; dz <= 1; dz++) {
                            int nz = sz + dz;
                            if (nz < 0 || nz > 3 || (dx | dy | dz) == 0) continue;
                            int n = (ny >> 2) * 64 + (nx | (ny & 3) << 2 | nz << 4);
                            int slot = slotOf[n];
                            if (slot == 0) continue;
                            int j = 0;
                            while (j < distinct && ids[j] != slot) j++;
                            if (j == distinct) {
                                if (distinct == ids.length) continue;
                                ids[distinct] = slot; counts[distinct] = 0; distinct++;
                            }
                            counts[j] += countOf[n];
                        }
                    }
                }
                int best = -1;
                for (int j = 0; j < distinct; j++) {
                    if (best < 0 || counts[j] > counts[best] || (counts[j] == counts[best] && ids[j] < ids[best])) best = j;
                }
                if (best >= 0) {
                    nextSlot[i] = (byte) ids[best];
                    nextCount[i] = 1; // borrowed: weighs less than real rock
                    changed = true;
                }
            }
            slotOf = nextSlot;
            countOf = nextCount;
            if (!changed) break;
        }
        palette.cellStates = states.toArray(new WrappedBlockState[0]);
        palette.cellIndex = slotOf;
    }

    // ── Follow-terrain cover ─────────────────────────────────────────────

    private static void setCover(ColumnPalette p, short[] raw, short[] top, int maxCover, int guessed) {
        p.coverRaw = raw;
        if (top == null) return;
        p.guessedNeighbours = guessed;
        p.coverTop = top;
        p.maxCover = maxCover;
        p.revealTop = top; // merged with an older palette's in keepReveal
        p.maxReveal = maxCover;
    }

    /**
     * NEW (follow-terrain): per column, the highest Y with at least minCover
     * natural blocks above it (counted down from the roof, gaps allowed).
     * Never below ceilingY. Columns whose roof is at or below ceiling-y are
     * skipped, so flat land costs nothing. Usually stops after a few blocks.
     */
    private static short[] scanCover(BaseChunk[] sections, int minY, int[] topSolidY,
                                     int ceilingY, int minCover) {
        short[] out = new short[256];
        Map<Integer, Boolean> natural = new HashMap<>();
        for (int lz = 0; lz < 16; lz++) {
            for (int lx = 0; lx < 16; lx++) {
                int col = (lz << 4) | lx;
                int cover = ceilingY;
                int roof = topSolidY[col];
                if (roof > ceilingY) {
                    int count = 0, lastId = Integer.MIN_VALUE;
                    boolean lastNatural = false;
                    for (int y = roof; y > ceilingY; y--) {
                        int si = (y - minY) >> 4;
                        if (si < 0 || si >= sections.length) continue;
                        BaseChunk section = sections[si];
                        if (section == null || section.isEmpty()) {
                            y = minY + (si << 4); // whole section is air: skip to its bottom
                            continue;
                        }
                        int ly = (y - minY) & 15;
                        int id = section.getBlockId(lx, ly, lz);
                        boolean isNatural;
                        if (id == lastId) {
                            isNatural = lastNatural;
                        } else {
                            Boolean known = natural.get(id);
                            if (known == null) {
                                WrappedBlockState st = section.get(lx, ly, lz);
                                known = st != null && NATURAL_COVER_TYPES.contains(st.getType());
                                natural.put(id, known);
                            }
                            isNatural = known;
                            lastId = id;
                            lastNatural = isNatural;
                        }
                        if (isNatural && ++count >= minCover) {
                            cover = y - 1;
                            break;
                        }
                    }
                }
                out[col] = (short) Math.max(ceilingY, cover);
            }
        }
        return out;
    }

    /**
     * Edge margin: each column gets the lowest cover within EDGE_MARGIN
     * columns (square), so the first blocks in from a cliff face, overhang
     * edge or cave mouth stay real. Neighbour chunks use their cached raw
     * cover; one that isn't cached yet counts as uncovered (bit set in
     * guessedOut) and this chunk is rebuilt once it is.
     */
    private short[] applyEdgeMargin(String worldName, int chunkX, int chunkZ, short[] raw, int ceilingY,
                                    int[] guessedOut) {
        final int M = EDGE_MARGIN, W = 16 + 2 * M;
        ConcurrentHashMap<Long, ColumnPalette> cache = worldCaches.get(worldName);
        short[][] nb = new short[9][];
        boolean[] known = new boolean[9];
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                int i = (dx + 1) * 3 + (dz + 1);
                if (dx == 0 && dz == 0) { nb[i] = raw; known[i] = true; continue; }
                ColumnPalette p = cache != null ? cache.get(chunkColumnKey(chunkX + dx, chunkZ + dz)) : null;
                if (p != null) { nb[i] = p.coverRaw; known[i] = true; }
                else guessedOut[0] |= 1 << i;
            }
        }
        int[] grid = new int[W * W];
        for (int gx = 0; gx < W; gx++) {
            int wx = gx - M;
            int dcx = wx < 0 ? -1 : (wx > 15 ? 1 : 0);
            for (int gz = 0; gz < W; gz++) {
                int wz = gz - M;
                int dcz = wz < 0 ? -1 : (wz > 15 ? 1 : 0);
                int i = (dcx + 1) * 3 + (dcz + 1);
                int v;
                if (!known[i]) {
                    // BUGFIX: an uncached neighbour used to count as "no cover",
                    // so on join / teleport (chunks arrive in a spiral) the 3
                    // outer columns facing unloaded chunks stayed real: strips
                    // of open cave in every mountain chunk, never re-sent. Now
                    // the nearest own column is used. Too much hidden is fixed
                    // by the reveal as soon as someone can see it; too little
                    // never was.
                    v = raw[((Math.max(0, Math.min(15, wz))) << 4) | Math.max(0, Math.min(15, wx))];
                } else if (nb[i] == null) {
                    v = ceilingY; // no cover there: keep this edge real
                } else {
                    v = nb[i][((wz & 15) << 4) | (wx & 15)];
                }
                grid[gx * W + gz] = v;
            }
        }
        // Separable min filter: along x, then along z.
        int[] rowMin = new int[16 * W];
        for (int lx = 0; lx < 16; lx++) {
            for (int gz = 0; gz < W; gz++) {
                int m = Integer.MAX_VALUE;
                for (int k = 0; k <= 2 * M; k++) m = Math.min(m, grid[(lx + k) * W + gz]);
                rowMin[lx * W + gz] = m;
            }
        }
        short[] out = new short[256];
        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                int m = Integer.MAX_VALUE;
                for (int k = 0; k <= 2 * M; k++) m = Math.min(m, rowMin[lx * W + lz + k]);
                out[(lz << 4) | lx] = (short) Math.max(ceilingY, Math.min(m, raw[(lz << 4) | lx]));
            }
        }
        return out;
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
