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

    /**
     * Thread-safe cache of entity ID → last-known Y coordinate.
     * Populated from SPAWN_ENTITY packets (which carry the entity's position)
     * so that ENTITY_SOUND_EFFECT can look up the Y without touching Bukkit API.
     * This avoids the Folia AsyncCatcher crash caused by calling
     * world.getEntities() on the Netty IO thread.
     */
    private final Map<Integer, Integer> entityYCache = new ConcurrentHashMap<>();

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

        if (!plugin.isProtectionActive(player)) {
            // Still cache entity Y even when protection is inactive
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
        int    voidY         = plugin.getVoidY(worldName); // per-world voidY
        int    replacementId = plugin.getReplacementBlockId();

        // Early-out: if world minY is already above voidY, no blocks can be hidden.
        if (minY > voidY) return;

        boolean modified      = false;
        long    replacedCount = 0;

        for (int si = 0; si < sections.length; si++) {
            BaseChunk section = sections[si];
            if (section == null || section.isEmpty()) continue;

            int sectionBaseY = minY + si * 16;

            // Optimization: skip the whole section if its bottom is above voidY.
            if (sectionBaseY > voidY) continue;

            for (int ly = 0; ly < 16; ly++) {
                int worldY = sectionBaseY + ly;
                if (worldY > voidY) break;

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

        // ── IMPROVEMENT: strip tile entities below voidY ──────────────────
        // The CHUNK_DATA packet includes a list of block entity (tile entity) NBT
        // compounds. Even though we replaced the block states with air, a modified
        // client could still parse these compounds and learn chest locations/contents.
        // We remove any tile entity whose Y coordinate is at or below voidY.
        // BUGFIX: this was previously unconditional, ignoring protection.pie-chart-protection
        // entirely. Tile-entity stripping now only runs when the setting is actually enabled.
        if (plugin.isPieChartProtectionEnabled() && column != null) {
            boolean tileModified = stripTileEntitiesBelow(column, voidY);
            if (tileModified) modified = true;
        }

        if (modified) {
            try { wrapper.setIgnoreOldData(true); } catch (Exception ignored) {}
            event.markForReEncode(true);
            plugin.incrementChunksModified();
            plugin.addBlocksReplaced(replacedCount);
            plugin.dbg("CHUNK_DATA modified for " + player.getName()
                    + " (" + replacedCount + " blocks, tile entities stripped)");
        }
    }

    /**
     * Strips tile entity entries at or below {@code voidY} from the chunk column.
     *
     * <p>Because {@link Column} does not expose a public setter for its
     * {@code tileEntities} field, we use reflection. The field name
     * {@code "tileEntities"} is stable across PacketEvents 2.x.
     *
     * @return true if at least one tile entity was removed and re-encoding is needed.
     */
    private boolean stripTileEntitiesBelow(Column column, int voidY) {
        try {
            TileEntity[] tileEntities = column.getTileEntities();
            if (tileEntities == null || tileEntities.length == 0) return false;

            List<TileEntity> filtered = new ArrayList<>(tileEntities.length);
            boolean changed = false;

            for (TileEntity te : tileEntities) {
                if (te == null) continue;
                int teY = te.getY();
                if (teY <= voidY) {
                    changed = true;
                    plugin.dbg("Stripped tile entity at Y=" + teY);
                } else {
                    filtered.add(te);
                }
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

            int voidY = plugin.getVoidY(player.getWorld().getName());
            if (pos.getY() > voidY) return;

            int replacementId = plugin.getReplacementBlockId();
            if (wrapper.getBlockState().getGlobalId() != replacementId) {
                wrapper.setBlockState(replacement);
                event.markForReEncode(true);
                plugin.addBlocksReplaced(1);
                plugin.dbg("BLOCK_CHANGE modified at " + pos + " for " + player.getName());
            }
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

            boolean modified      = false;
            int     replacementId = plugin.getReplacementBlockId();
            int     voidY         = plugin.getVoidY(player.getWorld().getName());
            int     replacedCount = 0;

            for (WrapperPlayServerMultiBlockChange.EncodedBlock block : blocks) {
                if (block == null) continue;
                if (block.getY() > voidY) continue;
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
                        + " (" + replacedCount + " blocks)");
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
            WrapperPlayServerBlockEntityData wrapper = new WrapperPlayServerBlockEntityData(event);
            Vector3i pos = wrapper.getPosition();
            if (pos == null) return;

            if (cancelIfAtOrBelowVoidY(event, player, pos.getY())) {
                plugin.dbg("BLOCK_ENTITY_DATA cancelled at " + pos
                        + " (Y=" + pos.getY() + " ≤ voidY=" + plugin.getVoidY(player.getWorld().getName()) + ")"
                        + " for " + player.getName());
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

            if (cancelIfAtOrBelowVoidY(event, player, pos.getY())) {
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

            if (cancelIfAtOrBelowVoidY(event, player, pos.getY())) {
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

            if (cancelIfAtOrBelowVoidY(event, player, pos.getY())) {
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

            // Look up cached Y instead of calling world.getEntities() on the Netty thread.
            Integer cachedY = entityYCache.get(entityId);
            if (cachedY == null) return; // unknown entity, let it through

            if (cancelIfAtOrBelowVoidY(event, player, cachedY)) {
                plugin.dbg("ENTITY_SOUND_EFFECT cancelled for entity #"
                        + entityId + " at Y=" + cachedY
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

            if (cancelIfAtOrBelowVoidY(event, player, (int) Math.floor(pos.getY()))) {
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
            entityYCache.put(wrapper.getEntityId(), (int) Math.floor(pos.getY()));
        } catch (Exception ignored) {}
    }

    private void handleEntitySpawn(PacketSendEvent event, Player player) {
        plugin.incrementPacketsProcessed();
        try {
            WrapperPlayServerSpawnEntity wrapper = new WrapperPlayServerSpawnEntity(event);
            if (wrapper.getEntityType() == EntityTypes.PLAYER) return;

            Vector3d pos = wrapper.getPosition();
            if (pos == null) return;

            int entityY = (int) Math.floor(pos.getY());

            // Cache the entity's Y for later use by handleEntitySoundEffect.
            // This runs on the Netty thread where we already have the position
            // from the packet, so no Bukkit API calls are needed.
            entityYCache.put(wrapper.getEntityId(), entityY);

            // BUGFIX: this cancellation was previously unconditional, ignoring
            // protection.pie-chart-protection entirely (same issue as the tile-entity
            // stripping above). Only cancel spawn packets when the setting is enabled.
            if (!plugin.isPieChartProtectionEnabled()) return;

            if (cancelIfAtOrBelowVoidY(event, player, entityY)) {
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

    private boolean cancelIfAtOrBelowVoidY(PacketSendEvent event, Player player, int y) {
        if (!isAtOrBelowVoidY(player, y)) return false;
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
    }

    /**
     * Returns the current size of the entity Y cache (for debug/stats).
     */
    public int getEntityCacheSize() {
        return entityYCache.size();
    }
}
