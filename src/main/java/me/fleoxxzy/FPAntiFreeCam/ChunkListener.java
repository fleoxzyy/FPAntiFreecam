package me.fleoxxzy.FPAntiFreeCam;

import com.github.retrooper.packetevents.event.PacketListener;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.protocol.world.chunk.BaseChunk;
import com.github.retrooper.packetevents.protocol.world.chunk.Column;
import com.github.retrooper.packetevents.protocol.world.chunk.TileEntity;
import com.github.retrooper.packetevents.protocol.world.states.WrappedBlockState;
import com.github.retrooper.packetevents.protocol.world.states.type.StateType;
import com.github.retrooper.packetevents.protocol.world.states.type.StateTypes;
import com.github.retrooper.packetevents.util.Vector3i;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerBlockAction;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerBlockChange;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerBlockEntityData;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerChunkData;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEffect;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntitySoundEffect;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerMultiBlockChange;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerParticle;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSoundEffect;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * PacketEvents listener that intercepts outbound chunk/block-change packets
 * and replaces hidden underground blocks with air for eligible players.
 *
 * Supports:
 *  - CHUNK_DATA          (initial chunk load and full re-send)
 *  - BLOCK_CHANGE        (single block update)
 *  - MULTI_BLOCK_CHANGE  (section batch update)
 *  - BLOCK_ENTITY_DATA   tile entity updates sent after the chunk
 *  - BLOCK_ACTION        chest/door/furnace animations at hidden positions
 *  - EFFECT              block break/smoke particles at hidden positions
 *  - SOUND_EFFECT        positional sounds (doors, chests, redstone)
 *  - ENTITY_SOUND_EFFECT sounds attached to hidden underground entities
 *  - PARTICLE              particles at hidden positions (redstone dust, smoke, etc.)
 *  - SPAWN_ENTITY          entity spawns below voidY (pie-chart / ESP leak fix)
 *
 * IMPROVEMENTS in this version:
 *  1. BLOCK_ENTITY_DATA packets are now cancelled when the block entity's
 *     position is at or below voidY. Without this, a packet-sniffing FreeCam
 *     client could still receive chest/furnace/hopper NBT data even though
 *     the block was visually replaced with air — leaking base contents.
 *
 *  2. Tile entities embedded in CHUNK_DATA are now stripped from the column's
 *     tileEntities array for positions at or below voidY. Same data-leak fix
 *     as above but for the initial chunk send.
 *
 *  3. Per-world voidY: all packet handlers now call plugin.getVoidY(worldName)
 *     instead of the global plugin.getVoidY(), so different worlds can have
 *     different underground floor depths.
 */
public final class ChunkListener implements PacketListener {

    private final FPAntiFreeCam plugin;
    private final SmartFillEngine smartFill;

    /**
     * Thread-safe cache of entity ID → last-known Y coordinate.
     * Populated from SPAWN_ENTITY packets (which carry the entity's position)
     * so that ENTITY_SOUND_EFFECT can look up the Y without touching Bukkit API.
     * This avoids the Folia AsyncCatcher crash caused by calling
     * world.getEntities() on the Netty IO thread.
     */
    private final Map<Integer, int[]> entityYCache = new ConcurrentHashMap<>();

    /**
     * PERF: Set of packet types that this listener actually handles.
     * Checked BEFORE any Bukkit API calls (getPlayer, getWorld, hasPermission)
     * so that the ~90% of outbound packets we don't care about (chat, tab list,
     * keep-alive, entity metadata, etc.) skip all processing with zero overhead.
     */
    private static final Set<PacketType.Play.Server> HANDLED_TYPES;
    static {
        Set<PacketType.Play.Server> s = new HashSet<>();
        s.add(PacketType.Play.Server.CHUNK_DATA);
        s.add(PacketType.Play.Server.BLOCK_CHANGE);
        s.add(PacketType.Play.Server.MULTI_BLOCK_CHANGE);
        s.add(PacketType.Play.Server.BLOCK_ENTITY_DATA);
        s.add(PacketType.Play.Server.BLOCK_ACTION);
        s.add(PacketType.Play.Server.EFFECT);
        s.add(PacketType.Play.Server.SOUND_EFFECT);
        s.add(PacketType.Play.Server.NAMED_SOUND_EFFECT);
        s.add(PacketType.Play.Server.ENTITY_SOUND_EFFECT);
        s.add(PacketType.Play.Server.PARTICLE);
        s.add(PacketType.Play.Server.SPAWN_ENTITY);
        s.add(PacketType.Play.Server.SPAWN_LIVING_ENTITY);
        s.add(PacketType.Play.Server.SPAWN_EXPERIENCE_ORB);
        s.add(PacketType.Play.Server.SPAWN_PAINTING);
        HANDLED_TYPES = s;
    }

    /**
     * PERF: Cached reflection Field for Column.tileEntities.
     * Resolved once instead of on every CHUNK_DATA packet.
     */
    private static final Field TILE_ENTITIES_FIELD;
    static {
        Field f = null;
        try {
            f = Column.class.getDeclaredField("tileEntities");
            f.setAccessible(true);
        } catch (Exception ignored) {}
        TILE_ENTITIES_FIELD = f;
    }

    public ChunkListener(FPAntiFreeCam plugin) {
        this.plugin = plugin;
        this.smartFill = new SmartFillEngine(plugin);
    }

    /**
     * BUGFIX: entityYCache previously had no eviction path at all —
     * removeEntityFromCache()/clearEntityCache() existed but were never
     * called anywhere, so every spawned mob/item/orb/painting stayed in this
     * map for the lifetime of the server even after despawning. On a server
     * with active farms or heavy natural spawning this grew unbounded.
     *
     * A direct EntityRemoveFromWorldEvent hook would be ideal, but that
     * class isn't available in the paper-api version this project compiles
     * against (added in a later Paper API than 1.19.4), so instead this is
     * called periodically from FPAntiFreeCam's existing 60s cleanup task and
     * cross-checks every cached ID against the set of currently-loaded
     * entities across all worlds, dropping anything no longer present.
     * MUST be called on the main/global thread — World#getEntities() is not
     * thread-safe off it.
     */
    public void pruneStaleCache() {
        if (entityYCache.isEmpty()) return;
        Set<Integer> liveIds = new HashSet<>();
        for (World world : Bukkit.getWorlds()) {
            for (Entity e : world.getEntities()) {
                liveIds.add(e.getEntityId());
            }
        }
        entityYCache.keySet().removeIf(id -> !liveIds.contains(id));
    }

    // ── PacketEvents entry point ──────────────────────────────────────────

    @Override
    public void onPacketSend(PacketSendEvent event) {
        // PERF: Check packet type FIRST — this is a cheap enum identity check.
        // Avoids calling Bukkit.getPlayer(), player.getWorld(), player.hasPermission(),
        // and player.getGameMode() for the vast majority of outbound packets
        // (chat, tab list, keep-alive, entity metadata, etc.).
        if (!(event.getPacketType() instanceof PacketType.Play.Server type)) return;
        if (!HANDLED_TYPES.contains(type)) return;

        User user = event.getUser();
        if (user == null) return;

        UUID uuid = user.getUUID();
        if (uuid == null) return;

        Player player = Bukkit.getPlayer(uuid);
        if (player == null || !player.isOnline()) return;

        // SPAWN_ENTITY must cache entity Y even for unprotected players/worlds,
        // because ENTITY_SOUND_EFFECT for the same entity may arrive later
        // when the player IS protected.
        boolean isSpawnPacket = type == PacketType.Play.Server.SPAWN_ENTITY
                || type == PacketType.Play.Server.SPAWN_LIVING_ENTITY
                || type == PacketType.Play.Server.SPAWN_EXPERIENCE_ORB
                || type == PacketType.Play.Server.SPAWN_PAINTING;

        World world = player.getWorld();
        if (world == null || !plugin.isWorldProtected(world.getName())) {
            // Still cache entity Y for spawn packets in protected worlds
            if (isSpawnPacket) cacheEntitySpawnY(event);
            return;
        }

        if (!plugin.isProtectionActive(player) && !(plugin.isSmartFillEnabled() && plugin.isSmartFillActiveFor(player))) {
            // Still cache entity Y even when nothing needs hiding right now
            if (isSpawnPacket) cacheEntitySpawnY(event);
            return;
        }

        if (type == PacketType.Play.Server.CHUNK_DATA) {
            handleChunkData(event, player);
        } else if (type == PacketType.Play.Server.BLOCK_CHANGE) {
            handleBlockChange(event, player);
        } else if (type == PacketType.Play.Server.MULTI_BLOCK_CHANGE) {
            handleMultiBlockChange(event, player);
        } else if (type == PacketType.Play.Server.BLOCK_ENTITY_DATA) {
            handleBlockEntityData(event, player);
        } else if (type == PacketType.Play.Server.BLOCK_ACTION) {
            handleBlockAction(event, player);
        } else if (type == PacketType.Play.Server.EFFECT) {
            handleEffect(event, player);
        } else if (type == PacketType.Play.Server.SOUND_EFFECT
                || type == PacketType.Play.Server.NAMED_SOUND_EFFECT) {
            handleSoundEffect(event, player);
        } else if (type == PacketType.Play.Server.ENTITY_SOUND_EFFECT) {
            handleEntitySoundEffect(event, player);
        } else if (type == PacketType.Play.Server.PARTICLE) {
            handleParticle(event, player);
        } else if (isSpawnPacket) {
            handleEntitySpawn(event, player);
        }
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        // No inbound packets need modification
    }

    // ── CHUNK_DATA ────────────────────────────────────────────────────────

    private void handleChunkData(PacketSendEvent event, Player player) {
        plugin.incrementPacketsProcessed();
        WrappedBlockState replacement = plugin.getReplacementBlock();
        if (replacement == null) return;

        WrapperPlayServerChunkData wrapper;
        try {
            wrapper = new WrapperPlayServerChunkData(event);
        } catch (Exception e) {
            plugin.dbg("ChunkData wrapper error for " + player.getName() + ": " + e.getMessage());
            return;
        }

        Column    column;
        BaseChunk[] sections;
        try {
            column   = wrapper.getColumn();
            sections = column != null ? column.getChunks() : null;
        } catch (Exception e) {
            plugin.dbg("Column access error: " + e.getMessage());
            return;
        }

        if (sections == null) return;

        World  world         = player.getWorld();
        int    minY          = world.getMinHeight();
        String worldName     = world.getName();
        int    hardFloorY    = plugin.getVoidY(worldName); // absolute, always-hidden vault floor
        int    replacementId = plugin.getReplacementBlockId();
        boolean smartFillEnabled = plugin.isSmartFillEnabled();

        // BUGFIX: "useSmartFill" and the surface-triggered playerHiddenState
        // used to be the SAME on/off switch, and the masked range never went
        // above hardFloorY. That meant (a) Smart Fill only ever covered the
        // exact same range flat mode did, leaving every cave between void-y
        // and protection-y completely unmasked, and (b) the moment a player
        // dropped below deep-deactivation-y (normal underground play),
        // protection went fully off and NOTHING was masked anymore, not
        // even the void-y floor. Smart Fill's whole point is to keep hiding
        // distant caves/bases while the player is freely mining nearby, so
        // its activation must not be tied to the player's own surface
        // exposure state at all. See FPAntiFreeCam.isSmartFillActiveFor() -
        // by default (always-on: true) it only depends on world/bypass, not
        // playerHiddenState.
        boolean smartFillActive = smartFillEnabled && plugin.isSmartFillActiveFor(player);
        boolean fullProtection  = plugin.isProtectionActive(player);

        if (!smartFillEnabled) {
            // Legacy flat mode: unchanged from the original behavior. Masks
            // everything at/below hardFloorY, but ONLY while the player is
            // in the surface-triggered "hidden" state, exactly like before
            // Smart Fill existed.
            if (!fullProtection) return;
            if (minY > hardFloorY) return;

            boolean modified      = false;
            long    replacedCount = 0;

            for (int si = 0; si < sections.length; si++) {
                BaseChunk section = sections[si];
                if (section == null) continue;
                if (section.isEmpty() && replacementId == 0) continue;

                int sectionBaseY = minY + si * 16;
                if (sectionBaseY > hardFloorY) continue;

                for (int ly = 0; ly < 16; ly++) {
                    int worldY = sectionBaseY + ly;
                    if (worldY > hardFloorY) break;

                    for (int lx = 0; lx < 16; lx++) {
                        for (int lz = 0; lz < 16; lz++) {
                            try {
                                WrappedBlockState current = section.get(lx, ly, lz);
                                if (current != null && current.getGlobalId() != replacementId) {
                                    section.set(lx, ly, lz, replacement);
                                    replacedCount++;
                                    modified = true;
                                }
                            } catch (Exception ignored) {}
                        }
                    }
                }
            }

            if (plugin.isPieChartProtectionEnabled() && column != null) {
                if (stripTileEntitiesBelow(column, hardFloorY, false, null, null)) modified = true;
            }

            if (modified) {
                try { wrapper.setIgnoreOldData(true); } catch (Exception ignored) {}
                event.markForReEncode(true);
                plugin.incrementChunksModified();
                plugin.addBlocksReplaced(replacedCount);
                plugin.dbg("CHUNK_DATA modified for " + player.getName()
                        + " (" + replacedCount + " blocks, flat, tile entities stripped)");
            }
            return;
        }

        // Smart Fill mode
        if (!smartFillActive) return; // nothing to hide for this player right now

        int ceilingY = Math.max(hardFloorY, plugin.getSmartFillCeilingY());
        if (minY > ceilingY) return;

        SmartFillEngine.ColumnPalette palette = null;
        int chunkX = 0, chunkZ = 0;
        try {
            chunkX = column.getX();
            chunkZ = column.getZ();
            palette = smartFill.getOrBuildPalette(worldName, chunkX, chunkZ,
                    sections, minY, ceilingY);
        } catch (Exception e) {
            plugin.dbg("SmartFill palette error: " + e.getMessage());
        }

        SmartFillReveal reveal = plugin.getSmartFillReveal();
        org.bukkit.Location pLoc = player.getLocation();
        int pBX = pLoc.getBlockX();
        int pBY = pLoc.getBlockY();
        int pBZ = pLoc.getBlockZ();
        UUID playerId = player.getUniqueId();

        boolean modified      = false;
        long    replacedCount = 0;

        for (int si = 0; si < sections.length; si++) {
            BaseChunk section = sections[si];
            if (section == null) continue;

            int sectionBaseY = minY + si * 16;
            if (sectionBaseY > ceilingY) continue;

            for (int ly = 0; ly < 16; ly++) {
                int worldY = sectionBaseY + ly;
                if (worldY > ceilingY) break;

                for (int lx = 0; lx < 16; lx++) {
                    int worldX = (chunkX << 4) | lx;

                    for (int lz = 0; lz < 16; lz++) {
                    int worldZ = (chunkZ << 4) | lz;
                try {
                                WrappedBlockState current = section.get(lx, ly, lz);

                                // IMPROVEMENT: shallow surface structures
                                // (PvP arenas, crystal holes, player-built
                                // rooms) are exempted entirely - roof,
                                // floor, and interior - before either the
                                // hard-floor or camouflage-band logic below
                                // gets a say. See isExemptSurfaceStructure's
                                // javadoc and config.yml's surface-structures
                                // comment for why this can't just be a flat
                                // Y cutoff.
                                if (smartFill.isExemptSurfaceStructure(palette, lx, lz, worldY)) continue;

                if (worldY <= hardFloorY) {
                    // BUGFIX: this used to be "always masked,
                    // no reveal exception" on the theory that
                    // the vault floor should never be visible
                    // to anyone. But Smart Fill's activation
                    // is no longer coupled to the player's
                    // own playerHiddenState, so a player
                // genuinely playing/mining at or below
                // void-y had their OWN surroundings
                // replaced with flat air with no way to
                    // see them - showing up as pure black
                    // void or, wherever real terrain dipped
                        // below void-y near the surface (beaches,
                                    // ravines, ocean floors), as floating
                        // pillars of terrain sticking up above
                        // the artificial void line. Apply the
                        // same proximity/look reveal used by the
                                    // camouflage band above it, so the vault
                        // stays invisible to anyone far away or
                        // above (freecam) but looks completely
                        // normal to whoever is actually standing
                        // there.
                        if (reveal != null && reveal.isAirRevealed(
                                worldX, worldY, worldZ, playerId, pBX, pBY, pBZ)) {
                            continue; // player is physically here - show the real block
                        }
                        // IMPROVEMENT: fill with the depth-adaptive palette
                        // (same one the camouflage band above uses) instead
                        // of flat air. Flat air this deep, with no nearby
                        // light source, rendered as a solid black mass from
                        // a distance - just as obvious a "something is being
                        // hidden here" signal as showing the real terrain
                        // would be. An endless stretch of deepslate blends
                        // in and gives nothing away.
                        WrappedBlockState hardFloorFill = palette != null
                                ? smartFill.selectState(palette, worldX, worldY, worldZ)
                                : smartFill.getDefaultState(worldName, worldY);
                        if (current == null || current.getGlobalId() != hardFloorFill.getGlobalId()) {
                            section.set(lx, ly, lz, hardFloorFill);
                                        replacedCount++;
                            modified = true;
                        }
                continue;
                }

                                // Smart Fill camouflage band
                                StateType type = current != null ? current.getType() : StateTypes.AIR;
                                if (SmartFillEngine.isSafeTerrain(type)) continue; // already looks natural

                                // BUGFIX: previously anything between hardFloorY and
                                // ceilingY that wasn't "safe terrain" got filled
                                // unconditionally, including open sky above shallow
                                // beaches/oceans that just happen to sit below
                                // ceilingY (protection-y). That produced a floating
                                // stone blanket hovering over water/sand at the
                                // real surface. Skip filling anywhere with no solid
                                // roof above it in the real column - only genuine
                                // roofed-over cave pockets get camouflaged.
                                if (smartFill.isOpenToSky(palette, lx, lz, worldY)) continue;

                                if (reveal != null && reveal.isAirRevealed(
                                        worldX, worldY, worldZ, playerId, pBX, pBY, pBZ)) {
                                    continue; // near/looked-at -> show the real block
                                }

                            WrappedBlockState fill = palette != null
                                    ? smartFill.selectState(palette, worldX, worldY, worldZ)
                                    : smartFill.getDefaultState(worldName, worldY);
                            section.set(lx, ly, lz, fill);
                            replacedCount++;
                            modified = true;
                        } catch (Exception ignored) {}
                    }
                }
            }
        }

        if (plugin.isPieChartProtectionEnabled() && column != null) {
            boolean tileModified = stripTileEntitiesBelow(column, ceilingY, true, reveal, playerId);
            if (tileModified) modified = true;
        }

        if (modified) {
            try { wrapper.setIgnoreOldData(true); } catch (Exception ignored) {}
            event.markForReEncode(true);
            plugin.incrementChunksModified();
            plugin.addBlocksReplaced(replacedCount);
            plugin.dbg("CHUNK_DATA modified for " + player.getName()
                    + " (" + replacedCount + " blocks, smart-fill, tile entities stripped)");
        }
    }

    /**
     * Strips tile entity entries from the chunk column.
     * In smart-fill mode, strips tile entities in unrevealed cave areas up to ceilingY.
     * In flat mode, strips all tile entities at or below voidY.
     */
    private boolean stripTileEntitiesBelow(Column column, int maxY, boolean useSmartFill,
                                           SmartFillReveal reveal, UUID playerId) {
        try {
            TileEntity[] tileEntities = column.getTileEntities();
            if (tileEntities == null || tileEntities.length == 0) return false;

            List<TileEntity> filtered = new ArrayList<>(tileEntities.length);
            boolean changed = false;

            for (TileEntity te : tileEntities) {
                if (te == null) continue;
                int teY = te.getY();
                if (teY <= maxY) {
                    if (useSmartFill && reveal != null) {
                        if (!reveal.isPositionRevealed(playerId, te.getX(), teY, te.getZ())) {
                            changed = true;
                            plugin.dbg("Stripped smart-fill tile entity at " + te.getX() + "," + teY + "," + te.getZ());
                            continue;
                        }
                    } else if (!useSmartFill) {
                        changed = true;
                        plugin.dbg("Stripped tile entity at Y=" + teY);
                        continue;
                    }
                }
                filtered.add(te);
            }

            if (!changed) return false;

            try {
                if (TILE_ENTITIES_FIELD == null) {
                    plugin.dbg("tileEntities Field not resolved at class load — skipping");
                    return false;
                }
                TILE_ENTITIES_FIELD.set(column, filtered.toArray(new TileEntity[0]));
                return true;
            } catch (ReflectiveOperationException | SecurityException ex) {
                plugin.dbg("Could not update tileEntities via reflection: " + ex.getMessage());
                return false;
            }

        } catch (Exception e) {
            plugin.dbg("stripTileEntitiesBelow error: " + e.getMessage());
            return false;
        }
    }

    // ── BLOCK_CHANGE ──────────────────────────────────────────────────────

    private void handleBlockChange(PacketSendEvent event, Player player) {
        plugin.incrementPacketsProcessed();
        WrappedBlockState replacement = plugin.getReplacementBlock();
        if (replacement == null) return;

        try {
            WrapperPlayServerBlockChange wrapper = new WrapperPlayServerBlockChange(event);
            Vector3i pos = wrapper.getBlockPosition();
            if (pos == null) return;

            String worldName = player.getWorld().getName();
            int hardFloorY = plugin.getVoidY(worldName);
            boolean smartFillEnabled = plugin.isSmartFillEnabled();

            if (!smartFillEnabled) {
                // Legacy flat mode
                if (!plugin.isProtectionActive(player)) return;
                if (pos.getY() > hardFloorY) return;
                int replacementId = plugin.getReplacementBlockId();
                if (wrapper.getBlockState().getGlobalId() != replacementId) {
                    wrapper.setBlockState(replacement);
                    event.markForReEncode(true);
                    plugin.addBlocksReplaced(1);
                    plugin.dbg("BLOCK_CHANGE modified at " + pos + " for " + player.getName());
                }
                return;
            }

            if (!plugin.isSmartFillActiveFor(player)) return;
            int ceilingY = Math.max(hardFloorY, plugin.getSmartFillCeilingY());

            // Invalidate palette cache if block changes near/above the boundary,
            // so a real terrain edit near the surface eventually shows up in
            // future fills instead of the palette going stale forever.
            int sampleCeiling = ceilingY + (plugin.getSmartFillSampleRadius() + 1) * 16;
            if (pos.getY() > hardFloorY && pos.getY() <= sampleCeiling) {
                smartFill.invalidate(worldName, pos.getX() >> 4, pos.getZ() >> 4);
            }

            if (pos.getY() > ceilingY) return;

            SmartFillEngine.ColumnPalette palette =
                    smartFill.getCachedPalette(worldName, pos.getX() >> 4, pos.getZ() >> 4);

            // IMPROVEMENT: shallow surface structures (PvP arenas, crystal
            // holes, player-built rooms) are exempted entirely - see the
            // matching comment in handleChunkData.
            if (smartFill.isExemptSurfaceStructure(palette, pos.getX() & 15, pos.getZ() & 15, pos.getY())) return;

            if (pos.getY() <= hardFloorY) {
                // BUGFIX: see the matching comment in handleChunkData - apply
                // the same proximity/look reveal to the hard floor so a
                // player genuinely playing at/below void-y sees their own
                // real surroundings instead of forced flat air.
                SmartFillReveal hardFloorReveal = plugin.getSmartFillReveal();
                if (hardFloorReveal != null) {
                    org.bukkit.Location pLoc = player.getLocation();
                    if (hardFloorReveal.isAirRevealed(pos.getX(), pos.getY(), pos.getZ(),
                            player.getUniqueId(), pLoc.getBlockX(), pLoc.getBlockY(), pLoc.getBlockZ())) {
                        return; // player is physically here - keep the real block
                    }
                }
                // IMPROVEMENT: adaptive fill instead of flat air - see the
                // matching comment in handleChunkData.
                WrappedBlockState hardFloorFill = palette != null
                        ? smartFill.selectState(palette, pos.getX(), pos.getY(), pos.getZ())
                        : smartFill.getDefaultState(worldName, pos.getY());
                if (wrapper.getBlockState().getGlobalId() != hardFloorFill.getGlobalId()) {
                    wrapper.setBlockState(hardFloorFill);
                    event.markForReEncode(true);
                    plugin.addBlocksReplaced(1);
                    plugin.dbg("BLOCK_CHANGE (hard floor) at " + pos + " for " + player.getName());
                }
                return;
            }

            StateType type = wrapper.getBlockState().getType();
            if (SmartFillEngine.isSafeTerrain(type)) return; // already looks natural

            // BUGFIX: see the matching comment in handleChunkData - never fill
            // a position with no real solid roof above it, even if it's below
            // ceilingY (open sky above a beach/ocean is not a cave).
            if (smartFill.isOpenToSky(palette, pos.getX() & 15, pos.getZ() & 15, pos.getY())) return;

            SmartFillReveal reveal = plugin.getSmartFillReveal();
            if (reveal != null) {
                org.bukkit.Location pLoc = player.getLocation();
                if (reveal.isAirRevealed(pos.getX(), pos.getY(), pos.getZ(),
                        player.getUniqueId(), pLoc.getBlockX(), pLoc.getBlockY(), pLoc.getBlockZ())) {
                    return; // near/looked-at -> keep the real block
                }
            }

            WrappedBlockState fill = palette != null
                    ? smartFill.selectState(palette, pos.getX(), pos.getY(), pos.getZ())
                    : smartFill.getDefaultState(worldName, pos.getY());
            wrapper.setBlockState(fill);
            event.markForReEncode(true);
            plugin.addBlocksReplaced(1);
            plugin.dbg("BLOCK_CHANGE smart-fill at " + pos + " for " + player.getName());
        } catch (Exception e) {
            plugin.dbg("BLOCK_CHANGE error: " + e.getMessage());
        }
    }

    // ── MULTI_BLOCK_CHANGE ────────────────────────────────────────────────

    private void handleMultiBlockChange(PacketSendEvent event, Player player) {
        plugin.incrementPacketsProcessed();
        WrappedBlockState replacement = plugin.getReplacementBlock();
        if (replacement == null) return;

        try {
            WrapperPlayServerMultiBlockChange wrapper = new WrapperPlayServerMultiBlockChange(event);
            WrapperPlayServerMultiBlockChange.EncodedBlock[] blocks = wrapper.getBlocks();
            if (blocks == null) return;

            String  worldName     = player.getWorld().getName();
            int     hardFloorY    = plugin.getVoidY(worldName);
            int     replacementId = plugin.getReplacementBlockId();
            boolean smartFillEnabled = plugin.isSmartFillEnabled();

            boolean modified      = false;
            int     replacedCount = 0;

            if (!smartFillEnabled) {
                // Legacy flat mode
                if (!plugin.isProtectionActive(player)) return;
                for (WrapperPlayServerMultiBlockChange.EncodedBlock block : blocks) {
                    if (block == null || block.getY() > hardFloorY) continue;
                    if (block.getBlockId() != replacementId) {
                        block.setBlockId(replacementId);
                        replacedCount++;
                        modified = true;
                    }
                }
                if (modified) {
                    event.markForReEncode(true);
                    plugin.addBlocksReplaced(replacedCount);
                    plugin.dbg("MULTI_BLOCK_CHANGE modified for " + player.getName()
                            + " (" + replacedCount + " blocks, flat)");
                }
                return;
            }

            if (!plugin.isSmartFillActiveFor(player)) return;
            int ceilingY = Math.max(hardFloorY, plugin.getSmartFillCeilingY());

            SmartFillEngine.ColumnPalette palette = null;
            for (WrapperPlayServerMultiBlockChange.EncodedBlock b : blocks) {
                if (b != null) {
                    palette = smartFill.getCachedPalette(worldName, b.getX() >> 4, b.getZ() >> 4);
                    break;
                }
            }
            SmartFillReveal reveal = plugin.getSmartFillReveal();
            org.bukkit.Location pLoc = player.getLocation();
            int pBX = pLoc.getBlockX();
            int pBY = pLoc.getBlockY();
            int pBZ = pLoc.getBlockZ();
            UUID playerId = player.getUniqueId();

            for (WrapperPlayServerMultiBlockChange.EncodedBlock block : blocks) {
                if (block == null || block.getY() > ceilingY) continue;

                // IMPROVEMENT: shallow surface structures (PvP arenas,
                // crystal holes, player-built rooms) are exempted entirely -
                // see the matching comment in handleChunkData.
                if (smartFill.isExemptSurfaceStructure(palette, block.getX() & 15, block.getZ() & 15, block.getY())) continue;

                if (block.getY() <= hardFloorY) {
                    // BUGFIX: see the matching comment in handleChunkData -
                    // apply the same proximity/look reveal to the hard floor
                    // so a player genuinely playing at/below void-y sees
                    // their own real surroundings instead of forced flat air.
                    if (reveal != null && reveal.isAirRevealed(
                            block.getX(), block.getY(), block.getZ(), playerId, pBX, pBY, pBZ)) {
                        continue; // player is physically here - keep the real block
                    }
                    // IMPROVEMENT: adaptive fill instead of flat air - see the
                    // matching comment in handleChunkData.
                    int hardFloorFillId = palette != null
                            ? smartFill.selectId(palette, block.getX(), block.getY(), block.getZ())
                            : smartFill.getDefaultState(worldName, block.getY()).getGlobalId();
                    if (block.getBlockId() != hardFloorFillId) {
                        block.setBlockId(hardFloorFillId);
                        replacedCount++;
                        modified = true;
                    }
                    continue;
                }

                WrappedBlockState state = WrappedBlockState.getByGlobalId(block.getBlockId());
                StateType t = state != null ? state.getType() : StateTypes.AIR;
                if (SmartFillEngine.isSafeTerrain(t)) continue; // already looks natural

                // BUGFIX: see the matching comment in handleChunkData - never
                // fill a position with no real solid roof above it.
                if (smartFill.isOpenToSky(palette, block.getX() & 15, block.getZ() & 15, block.getY())) continue;

                if (reveal != null && reveal.isAirRevealed(
                        block.getX(), block.getY(), block.getZ(), playerId, pBX, pBY, pBZ)) {
                    continue; // near/looked-at -> send the real block
                }
                int fillId = palette != null
                        ? smartFill.selectId(palette, block.getX(), block.getY(), block.getZ())
                        : smartFill.getDefaultState(worldName, block.getY()).getGlobalId();
                block.setBlockId(fillId);
                replacedCount++;
                modified = true;
            }

            if (modified) {
                event.markForReEncode(true);
                plugin.addBlocksReplaced(replacedCount);
                plugin.dbg("MULTI_BLOCK_CHANGE modified for " + player.getName()
                        + " (" + replacedCount + " blocks, smart-fill)");
            }
        } catch (Exception e) {
            plugin.dbg("MULTI_BLOCK_CHANGE error: " + e.getMessage());
        }
    }

    // ── BLOCK_ENTITY_DATA (NEW) ───────────────────────────────────────────

    /**
     * BYPASS FIX: Cancel tile entity update packets sent for positions at or
     * below voidY. These are sent by the server when a block entity's data
     * changes (e.g. a chest is opened/closed, a furnace starts smelting).
     *
     * <p>Without this interception, a FreeCam client positioned above protectionY
     * would still receive NBT data for underground chests/furnaces — leaking
     * base contents even though the block itself appears as air.
     */
    private void handleBlockEntityData(PacketSendEvent event, Player player) {
        plugin.incrementPacketsProcessed();
        try {
            if (!plugin.isPieChartProtectionEnabled()) return;
            WrapperPlayServerBlockEntityData wrapper = new WrapperPlayServerBlockEntityData(event);
            Vector3i pos = wrapper.getPosition();
            if (pos == null) return;

            if (cancelIfShouldHide(event, player, pos.getY(), pos.getX(), pos.getZ())) {
                plugin.dbg("BLOCK_ENTITY_DATA cancelled at " + pos + " for " + player.getName());
            }
        } catch (Exception e) {
            plugin.dbg("BLOCK_ENTITY_DATA error: " + e.getMessage());
        }
    }

    /**
     * Cancel block action packets (chest lid, furnace, note block, etc.) for
     * hidden positions so clients cannot infer underground block types.
     */
    private void handleBlockAction(PacketSendEvent event, Player player) {
        plugin.incrementPacketsProcessed();
        try {
            WrapperPlayServerBlockAction wrapper = new WrapperPlayServerBlockAction(event);
            Vector3i pos = wrapper.getBlockPosition();
            if (pos == null) return;

            if (cancelIfShouldHide(event, player, pos.getY(), pos.getX(), pos.getZ())) {
                plugin.dbg("BLOCK_ACTION cancelled at " + pos + " for " + player.getName());
            }
        } catch (Exception e) {
            plugin.dbg("BLOCK_ACTION error: " + e.getMessage());
        }
    }

    /** Block break particles, chest smoke, piston effects, etc. */
    private void handleEffect(PacketSendEvent event, Player player) {
        plugin.incrementPacketsProcessed();
        try {
            WrapperPlayServerEffect wrapper = new WrapperPlayServerEffect(event);
            Vector3i pos = wrapper.getPosition();
            if (pos == null) return;

            if (cancelIfHiddenHardFloor(event, player, pos.getX(), pos.getY(), pos.getZ())) {
                plugin.dbg("EFFECT cancelled at " + pos + " for " + player.getName());
            }
        } catch (Exception e) {
            plugin.dbg("EFFECT error: " + e.getMessage());
        }
    }

    /** Positional sounds such as doors, chests, note blocks, and redstone. */
    private void handleSoundEffect(PacketSendEvent event, Player player) {
        plugin.incrementPacketsProcessed();
        try {
            WrapperPlayServerSoundEffect wrapper = new WrapperPlayServerSoundEffect(event);
            Vector3i pos = wrapper.getEffectPosition();
            if (pos == null) return;

            // The sound packet stores coordinates as fixed-point (block * 8).
            int bx = Math.floorDiv(pos.getX(), 8);
            int by = Math.floorDiv(pos.getY(), 8);
            int bz = Math.floorDiv(pos.getZ(), 8);
            if (cancelIfHiddenHardFloor(event, player, bx, by, bz)) {
                plugin.dbg("SOUND_EFFECT cancelled at " + pos + " for " + player.getName());
            }
        } catch (Exception e) {
            plugin.dbg("SOUND_EFFECT error: " + e.getMessage());
        }
    }

    /** Sounds bound to entities (e.g. mobs/items in hidden underground farms). */
    private void handleEntitySoundEffect(PacketSendEvent event, Player player) {
        plugin.incrementPacketsProcessed();
        try {
            WrapperPlayServerEntitySoundEffect wrapper = new WrapperPlayServerEntitySoundEffect(event);
            int entityId = wrapper.getEntityId();

            // Look up the cached spawn position instead of calling world.getEntities() on the Netty thread.
            int[] cached = entityYCache.get(entityId);
            if (cached == null) return; // unknown entity, let it through

            if (cancelIfHiddenHardFloor(event, player, cached[0], cached[1], cached[2])) {
                plugin.dbg("ENTITY_SOUND_EFFECT cancelled for entity #"
                        + entityId + " at Y=" + cached[1]
                        + " for " + player.getName());
            }
        } catch (Exception e) {
            plugin.dbg("ENTITY_SOUND_EFFECT error: " + e.getMessage());
        }
    }

    /** Particles such as redstone dust, portal effects, or block hit particles. */
    private void handleParticle(PacketSendEvent event, Player player) {
        plugin.incrementPacketsProcessed();
        try {
            WrapperPlayServerParticle wrapper = new WrapperPlayServerParticle(event);
            Vector3d pos = wrapper.getPosition();
            if (pos == null) return;

            if (cancelIfHiddenHardFloor(event, player, (int) Math.floor(pos.getX()),
                    (int) Math.floor(pos.getY()), (int) Math.floor(pos.getZ()))) {
                plugin.dbg("PARTICLE cancelled at " + pos + " for " + player.getName());
            }
        } catch (Exception e) {
            plugin.dbg("PARTICLE error: " + e.getMessage());
        }
    }

    /**
     * Cancel entity spawn packets for underground entities before the client
     * registers them. Prevents F3 pie-chart spikes and ESP from one-tick leaks
     * before {@link EntityHider} can call hideEntity.
     */
    /**
     * Caches the entity's Y from a spawn packet without doing any protection logic.
     * Called even for unprotected players/worlds so the cache is populated
     * before a future ENTITY_SOUND_EFFECT arrives.
     */
    private void cacheEntitySpawnY(PacketSendEvent event) {
        try {
            WrapperPlayServerSpawnEntity wrapper = new WrapperPlayServerSpawnEntity(event);
            if (wrapper.getEntityType() == EntityTypes.PLAYER) return;
            Vector3d pos = wrapper.getPosition();
            if (pos == null) return;
            entityYCache.put(wrapper.getEntityId(), blockPos(pos));
        } catch (Exception ignored) {}
    }

    private void handleEntitySpawn(PacketSendEvent event, Player player) {
        plugin.incrementPacketsProcessed();
        try {
            WrapperPlayServerSpawnEntity wrapper = new WrapperPlayServerSpawnEntity(event);
            if (wrapper.getEntityType() == EntityTypes.PLAYER) return;

            Vector3d pos = wrapper.getPosition();
            if (pos == null) return;

            int[] entityPos = blockPos(pos);
            int entityY = entityPos[1];

            // Cache the entity's position for later use by handleEntitySoundEffect.
            // This runs on the Netty thread where we already have the position
            // from the packet, so no Bukkit API calls are needed.
            entityYCache.put(wrapper.getEntityId(), entityPos);

            // BUGFIX: this cancellation was previously unconditional, ignoring
            // protection.pie-chart-protection entirely (same issue as the tile-entity
            // stripping above). Only cancel spawn packets when the setting is enabled.
            if (!plugin.isPieChartProtectionEnabled()) return;

            if (cancelIfHiddenHardFloor(event, player, entityPos[0], entityY, entityPos[2])) {
                plugin.dbg("SPAWN_ENTITY cancelled " + wrapper.getEntityType().getName()
                        + " at Y=" + entityY + " for " + player.getName());
            }
        } catch (Exception e) {
            plugin.dbg("SPAWN_ENTITY error: " + e.getMessage());
        }
    }

    private boolean isAtOrBelowVoidY(Player player, int y) {
        return y <= plugin.getVoidY(player.getWorld().getName());
    }

    /**
     * BUGFIX: this used to only ever check against the hard void-y floor, so
     * chest/door/particle/sound packets for a distant, still-hidden cave or
     * base inside the Smart Fill camouflage band (void-y..ceiling-y) leaked
     * straight through uncancelled — a player couldn't SEE the base through
     * the fake stone, but could still hear a chest open or a redstone
     * particle pop at its exact coordinates. This now checks the same
     * hard-floor-or-camouflage-band logic as block masking, including the
     * proximity/look reveal so a player's OWN nearby chests/doors still
     * sound normal. When no X/Z is available for the packet (e.g. an entity
     * sound that only carries a cached Y), it deliberately does NOT cancel
     * inside the band — guessing wrong there would silence normal sounds for
     * the player's own nearby entities, which is worse than the minor leak
     * of an un-positioned sound.
     */
    private boolean shouldCancelForPosition(Player player, int y, Integer x, Integer z) {
        int hardFloorY = plugin.getVoidY(player.getWorld().getName());
        boolean smartFillEnabled = plugin.isSmartFillEnabled();

        // IMPROVEMENT: shallow surface structures (PvP arenas, crystal
        // holes, player-built rooms) are exempted entirely - see the
        // matching comment in handleChunkData. Only meaningful in smart-fill
        // mode with a known X/Z; legacy mode and un-positioned packets fall
        // through to the existing logic below unchanged.
        if (smartFillEnabled && x != null && z != null) {
            String worldName = player.getWorld().getName();
            SmartFillEngine.ColumnPalette palette = smartFill.getCachedPalette(worldName, x >> 4, z >> 4);
            if (smartFill.isExemptSurfaceStructure(palette, x & 15, z & 15, y)) return false;
        }

        if (y <= hardFloorY) {
            // BUGFIX: this was "no exceptions, ever" - that used to be safe
            // because in legacy mode the hard floor was only ever masked
            // while the player's OWN surface-triggered hidden state
            // (fullProtection) was true, so a player actually down at/below
            // the floor never had it applied to themselves. Smart Fill
            // decoupled masking from that state entirely, so without this
            // exception a player legitimately playing near/below void-y
            // would have their own chest/door/redstone sounds silenced with
            // no way to reveal them. Only smart-fill mode needs the
            // proximity/look exception here - legacy mode is unaffected
            // since it never reaches this method while the player is
            // genuinely below the floor.
            if (!smartFillEnabled || !plugin.isSmartFillActiveFor(player)) return true;
            if (x == null || z == null) return true; // no position to localize, err hidden

            SmartFillReveal hardFloorReveal = plugin.getSmartFillReveal();
            if (hardFloorReveal == null) return true;

            org.bukkit.Location hardFloorLoc = player.getLocation();
            return !hardFloorReveal.isAirRevealed(x, y, z, player.getUniqueId(),
                    hardFloorLoc.getBlockX(), hardFloorLoc.getBlockY(), hardFloorLoc.getBlockZ());
        }

        if (!smartFillEnabled) return false; // legacy mode never touches above void-y
        if (!plugin.isSmartFillActiveFor(player)) return false;

        int ceilingY = plugin.getSmartFillCeilingY();
        if (y > ceilingY) return false;

        if (x == null || z == null) return false; // no position to localize, don't guess

        SmartFillReveal reveal = plugin.getSmartFillReveal();
        if (reveal == null) return true; // reveal system unavailable, err hidden

        org.bukkit.Location loc = player.getLocation();
        return !reveal.isAirRevealed(x, y, z, player.getUniqueId(),
                loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
    }

    private boolean cancelIfAtOrBelowVoidY(PacketSendEvent event, Player player, int y) {
        if (!isAtOrBelowVoidY(player, y)) return false;
        event.setCancelled(true);
        return true;
    }

    /**
     * Cancels effects/sounds/particles/entity spawns at or below void-y. In
     * flat mode that only happens while the player's surface protection is
     * on. In Smart Fill mode the player's own revealed surroundings are left
     * alone, matching how hard-floor blocks are handled.
     */
    private boolean cancelIfHiddenHardFloor(PacketSendEvent event, Player player, int x, int y, int z) {
        if (!isAtOrBelowVoidY(player, y)) return false;
        if (!plugin.isSmartFillEnabled()) {
            if (!plugin.isProtectionActive(player)) return false;
            event.setCancelled(true);
            return true;
        }
        return cancelIfShouldHide(event, player, y, x, z);
    }

    private static int[] blockPos(Vector3d pos) {
        return new int[]{ (int) Math.floor(pos.getX()), (int) Math.floor(pos.getY()), (int) Math.floor(pos.getZ()) };
    }

    private boolean cancelIfShouldHide(PacketSendEvent event, Player player, int y, Integer x, Integer z) {
        if (!shouldCancelForPosition(player, y, x, z)) return false;
        event.setCancelled(true);
        return true;
    }

    /**
     * Removes a single entity from the Y cache (e.g. on despawn).
     * Can be called from any thread.
     */
    public void removeEntityFromCache(int entityId) {
        entityYCache.remove(entityId);
    }

    /**
     * Clears the entire entity Y cache. Call on plugin disable or reload.
     */
    public void clearEntityCache() {
        entityYCache.clear();
        smartFill.clearAll();
    }

    /**
     * Returns the current size of the entity Y cache (for debug/stats).
     */
    public int getEntityCacheSize() {
        return entityYCache.size();
    }

    /**
     * Returns the total number of cached Smart Fill palettes across all worlds.
     */
    public int getSmartFillCacheSize() {
        return smartFill.getCacheSize();
    }
}
