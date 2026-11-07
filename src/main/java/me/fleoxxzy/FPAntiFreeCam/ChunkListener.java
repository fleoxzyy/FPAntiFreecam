package me.fleoxxzy.FPAntiFreeCam;

import com.github.retrooper.packetevents.event.PacketListener;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.protocol.world.chunk.BaseChunk;
import com.github.retrooper.packetevents.protocol.world.chunk.Column;
import com.github.retrooper.packetevents.protocol.world.chunk.LightData;
import com.github.retrooper.packetevents.protocol.world.chunk.TileEntity;
import com.github.retrooper.packetevents.protocol.world.states.WrappedBlockState;
import com.github.retrooper.packetevents.protocol.world.states.type.StateType;
import com.github.retrooper.packetevents.protocol.world.states.type.StateTypes;
import com.github.retrooper.packetevents.util.Vector3i;
import com.github.retrooper.packetevents.protocol.entity.type.EntityType;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.protocol.player.UserProfile;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityAnimation;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityEffect;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityEquipment;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityHeadLook;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityRotation;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityStatus;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityVelocity;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfo;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfoRemove;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerRemoveEntityEffect;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetPassengers;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnPlayer;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerUpdateAttributes;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerBlockAction;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerBlockChange;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerBlockEntityData;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerChunkData;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEffect;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityPositionSync;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityRelativeMove;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityRelativeMoveAndRotation;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntitySoundEffect;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityTeleport;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerMultiBlockChange;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerParticle;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSoundEffect;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerUnloadChunk;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerUpdateLight;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collections;
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
     * Per-player record of which chunk sections were actually sent with
     * Smart Fill blocks in them, keyed by {@code (cx << 32) | (cz & 0xFFFFFFFFL)}.
     *
     * BUGFIX (Bedrock): Geyser turns every chunk re-send into a full chunk
     * rebuild on the Bedrock client, which shows as a visible reload. The
     * Smart Fill refresh tasks used to re-send chunks that had nothing masked
     * in them at all (open sky, oceans, solid rock), so Bedrock players saw
     * chunks reloading every second or two. Refreshes are now limited to
     * chunks this map says the client still has fake blocks in.
     */
    private final Map<UUID, Map<Long, MaskedChunk>> maskedChunks = new ConcurrentHashMap<>();

    /**
     * Per-player chunks the client was sent with flat (non-Smart-Fill)
     * masking in them, keyed like {@link #maskedChunks}.
     *
     * BUGFIX (void gaps underground): flat masking relied on ONE full view
     * refresh when protection turned off to replace every masked chunk. If
     * that refresh was skipped (refresh cooldowns, a teleport, a quick
     * on/off flip) or a chunk was sent masked while it was still draining,
     * the client kept those chunks with everything below void-y as air
     * until a relog or /fpac reload. Players saw a "missing chunk" gap,
     * and walking into it left them stuck and rubber-banding because the
     * server still had solid blocks there. These chunks are now tracked and
     * re-sent once protection is off (see FPAntiFreeCam#healStaleFlatChunks).
     */
    private final Map<UUID, Set<Long>> flatMaskedChunks = new ConcurrentHashMap<>();

    private static final class MaskedChunk {
        /** Bit i = section (minSection + i) contains filled blocks; bit 63 also covers any higher section. */
        final long sections;
        final int  minSection;
        /**
         * IMPROVEMENT: per section (same index as the bits above), which of its
         * 64 4×4×4 sub-cells hold filled blocks. Bit = sx | sy << 2 | sz << 4.
         */
        final long[] subMasks;

        MaskedChunk(long sections, int minSection, long[] subMasks) {
            this.sections = sections;
            this.minSection = minSection;
            this.subMasks = subMasks;
        }

        boolean has(int sectionY) {
            int idx = sectionY - minSection;
            if (idx < 0) return false;
            return (sections & (1L << Math.min(idx, 63))) != 0;
        }
    }

    /**
     * NEW: packets about one entity that must be dropped while it's hidden at
     * packet level (players, cancelled spawns). Checked against the hider's
     * per-viewer blocked set; an empty set costs one map lookup.
     */
    private static final Set<PacketType.Play.Server> ENTITY_PACKETS;
    static {
        Set<PacketType.Play.Server> s = new HashSet<>();
        s.add(PacketType.Play.Server.ENTITY_RELATIVE_MOVE);
        s.add(PacketType.Play.Server.ENTITY_RELATIVE_MOVE_AND_ROTATION);
        s.add(PacketType.Play.Server.ENTITY_TELEPORT);
        s.add(PacketType.Play.Server.ENTITY_POSITION_SYNC);
        s.add(PacketType.Play.Server.ENTITY_ROTATION);
        s.add(PacketType.Play.Server.ENTITY_HEAD_LOOK);
        s.add(PacketType.Play.Server.ENTITY_VELOCITY);
        s.add(PacketType.Play.Server.ENTITY_METADATA);
        s.add(PacketType.Play.Server.ENTITY_EQUIPMENT);
        s.add(PacketType.Play.Server.ENTITY_ANIMATION);
        s.add(PacketType.Play.Server.ENTITY_STATUS);
        s.add(PacketType.Play.Server.ENTITY_EFFECT);
        s.add(PacketType.Play.Server.REMOVE_ENTITY_EFFECT);
        s.add(PacketType.Play.Server.UPDATE_ATTRIBUTES);
        s.add(PacketType.Play.Server.SET_PASSENGERS);
        s.add(PacketType.Play.Server.ENTITY_SOUND_EFFECT);
        ENTITY_PACKETS = s;
    }

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
        s.add(PacketType.Play.Server.UPDATE_LIGHT); // NEW (1.5.4): light exploit
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
        s.add(PacketType.Play.Server.UNLOAD_CHUNK);
        s.add(PacketType.Play.Server.RESPAWN);
        s.add(PacketType.Play.Server.JOIN_GAME);
        s.add(PacketType.Play.Server.ENTITY_RELATIVE_MOVE);
        s.add(PacketType.Play.Server.ENTITY_RELATIVE_MOVE_AND_ROTATION);
        s.add(PacketType.Play.Server.ENTITY_TELEPORT);
        s.add(PacketType.Play.Server.ENTITY_POSITION_SYNC);
        s.add(PacketType.Play.Server.DESTROY_ENTITIES);
        // NEW: line-of-sight EntityHider.
        s.add(PacketType.Play.Server.SPAWN_PLAYER);
        s.add(PacketType.Play.Server.PLAYER_INFO);
        s.add(PacketType.Play.Server.PLAYER_INFO_REMOVE);
        s.addAll(ENTITY_PACKETS);
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
     * Paper servers (including Folia) evict entries per-entity through
     * {@link EntityRemoveListener}. This method is the fallback for Spigot: it's
     * called periodically from FPAntiFreeCam's existing 60s cleanup task and
     * cross-checks every cached ID against the set of currently-loaded
     * entities across all worlds, dropping anything no longer present.
     * MUST be called on the main/global thread — World#getEntities() is not
     * thread-safe off it.
     *
     * BUGFIX (Folia): there is no single "main thread" on Folia — every entity
     * belongs to a region thread, and calling getEntityId() on it from the
     * global region scheduler throws "Accessing entity state off owning
     * region's thread". On Folia this scan is skipped entirely; eviction is
     * handled per-entity by {@link EntityRemoveListener} instead, which runs
     * on the entity's own region thread.
     */
    public void pruneStaleCache() {
        if (entityYCache.isEmpty()) return;
        if (PlatformUtil.isFolia()) return;
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

        // NEW: the Freecam check's fake sign (block + text) must reach the
        // client as a sign, never as void-y / Smart Fill / Anti-Xray rock.
        if (type == PacketType.Play.Server.BLOCK_CHANGE || type == PacketType.Play.Server.BLOCK_ENTITY_DATA) {
            FreecamDetector detector = plugin.getFreecamDetector();
            if (detector != null && detector.hasPendingProbe(uuid) && isFreecamProbePacket(event, uuid, type, detector)) {
                return;
            }
        }

        // NEW: line-of-sight EntityHider, Netty side. Runs before every
        // world/protection gate, because LOS hiding works in every world.
        EntityHider hider = plugin.getEntityHider();
        if (ENTITY_PACKETS.contains(type)) {
            if (hider != null && hider.hasBlocked(uuid)) cancelIfBlockedEntity(event, uuid, type, hider);
            if (type != PacketType.Play.Server.ENTITY_SOUND_EFFECT || event.isCancelled()) return;
        }
        if (type == PacketType.Play.Server.PLAYER_INFO_REMOVE || type == PacketType.Play.Server.PLAYER_INFO) {
            if (hider != null && hider.hasInfoMarks(uuid)) suppressOwnInfoRemove(event, uuid, type, hider);
            return;
        }
        // Client-side chunk lifecycle: keep the masked-chunk record in sync.
        if (type == PacketType.Play.Server.UNLOAD_CHUNK) {
            try {
                WrapperPlayServerUnloadChunk unload = new WrapperPlayServerUnloadChunk(event);
                forgetChunk(uuid, unload.getChunkX(), unload.getChunkZ());
                forgetFlatMasked(uuid, unload.getChunkX(), unload.getChunkZ());
                AntiXrayEngine xray = plugin.getAntiXrayEngine();
                if (xray != null) xray.forgetChunk(uuid, unload.getChunkX(), unload.getChunkZ());
            } catch (Exception ignored) {}
            return;
        }
        if (type == PacketType.Play.Server.RESPAWN || type == PacketType.Play.Server.JOIN_GAME) {
            maskedChunks.remove(uuid); // client drops every chunk on respawn/world change
            flatMaskedChunks.remove(uuid);
            if (hider != null) hider.onClientReset(uuid); // and every entity
            AntiXrayEngine xray = plugin.getAntiXrayEngine();
            if (xray != null) xray.cleanupPlayer(uuid); // hidden ores and reveals went with those chunks
            return;
        }
        if (type == PacketType.Play.Server.DESTROY_ENTITIES) {
            if (hider != null) {
                try {
                    hider.onDestroyPacket(uuid, new WrapperPlayServerDestroyEntities(event).getEntityIds());
                } catch (Exception ignored) {}
            }
            return;
        }

        Player player = Bukkit.getPlayer(uuid);
        if (player == null || !player.isOnline()) return;

        // NEW: spawn packets are decided here for every world, before the
        // freecam gates below: line-of-sight hiding isn't tied to worlds.list.
        if (type == PacketType.Play.Server.SPAWN_ENTITY
                || type == PacketType.Play.Server.SPAWN_LIVING_ENTITY
                || type == PacketType.Play.Server.SPAWN_EXPERIENCE_ORB
                || type == PacketType.Play.Server.SPAWN_PAINTING
                || type == PacketType.Play.Server.SPAWN_PLAYER) {
            handleEntitySpawn(event, player, type, hider);
            return;
        }

        World world = player.getWorld();
        // NEW: Anti-Xray has its own per-world config, so it has to run for
        // block packets even where the freecam gate below returns early
        // (world not in worlds.list, or protection / Smart Fill off).
        boolean blockPacket = type == PacketType.Play.Server.CHUNK_DATA
                || type == PacketType.Play.Server.BLOCK_CHANGE
                || type == PacketType.Play.Server.MULTI_BLOCK_CHANGE;
        AntiXrayEngine xray = plugin.getAntiXrayEngine();
        boolean xrayActive = blockPacket && xray != null && xray.isActiveFor(player);

        // BUGFIX: a block Anti-Xray sends as a fake block (shulker box, chest
        // in hidden-blocks) must not get its block entity data or lid
        // animation either, in any world where Anti-Xray runs.
        if ((type == PacketType.Play.Server.BLOCK_ENTITY_DATA || type == PacketType.Play.Server.BLOCK_ACTION)
                && xray != null && xray.isActiveFor(player) && cancelIfXrayMasked(event, player, type, xray)) {
            return;
        }

        if (world == null || !plugin.isWorldProtected(world.getName())) {
            if (xrayActive) handleBlockPacket(event, player, type, false);
            return;
        }

        if (!plugin.isProtectionActive(player) && !plugin.isSmartFillActiveFor(player)) {
            // This chunk goes out unmasked, so it replaces any masked copy.
            if (type == PacketType.Play.Server.CHUNK_DATA) forgetFlatMaskedFromPacket(event, uuid);
            if (xrayActive) handleBlockPacket(event, player, type, false);
            return;
        }

        if (blockPacket) {
            if (xrayActive) {
                handleBlockPacket(event, player, type, true);
            } else if (type == PacketType.Play.Server.CHUNK_DATA) {
                handleChunkData(event, player);
            } else if (type == PacketType.Play.Server.BLOCK_CHANGE) {
                handleBlockChange(event, player);
            } else {
                handleMultiBlockChange(event, player);
            }
        } else if (type == PacketType.Play.Server.BLOCK_ENTITY_DATA) {
            handleBlockEntityData(event, player);
        } else if (type == PacketType.Play.Server.UPDATE_LIGHT) {
            handleUpdateLight(event, player);
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
        }
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        // No inbound packets need modification
    }

    private static boolean isFreecamProbePacket(PacketSendEvent event, UUID uuid, PacketType.Play.Server type,
                                                FreecamDetector detector) {
        try {
            Vector3i pos = type == PacketType.Play.Server.BLOCK_CHANGE
                    ? new WrapperPlayServerBlockChange(event).getBlockPosition()
                    : new WrapperPlayServerBlockEntityData(event).getPosition();
            return pos != null && detector.isProbeBlock(uuid, pos);
        } catch (Exception e) {
            return false;
        }
    }

    // ── Anti-Xray pass (NEW) ──────────────────────────────────────────────

    /**
     * NEW: CHUNK_DATA / BLOCK_CHANGE / MULTI_BLOCK_CHANGE with Anti-Xray on.
     * Runs the existing void-y / Smart Fill handler first (if freecamPass),
     * then Anti-Xray on whatever that left. The handlers have many early
     * returns, so they're called as a whole instead of having Anti-Xray
     * bolted onto each exit.
     *
     * <p>PacketEvents hands every new wrapper of the same type the previous
     * wrapper's data (getLastUsedWrapper), so the wrappers built here see the
     * column / block ids exactly as the freecam pass left them, and
     * markForReEncode writes out the combined result.
     */
    private void handleBlockPacket(PacketSendEvent event, Player player, PacketType.Play.Server type,
                                   boolean freecamPass) {
        AntiXrayEngine xray = plugin.getAntiXrayEngine();
        if (xray == null) return;
        // BUGFIX: if Anti-Xray fails before the freecam handler ran, still run
        // it, so a broken Anti-Xray pass can never leak the void-y / Smart Fill area.
        boolean freecamDone = !freecamPass;
        try {
            if (type == PacketType.Play.Server.CHUNK_DATA) {
                freecamDone = true; // set first: the handler itself must not run twice
                if (freecamPass) handleChunkData(event, player);
                WrapperPlayServerChunkData wrapper = new WrapperPlayServerChunkData(event);
                Column column = wrapper.getColumn();
                BaseChunk[] sections = column != null ? column.getChunks() : null;
                if (sections == null) return;
                if (xray.processChunk(player, sections, column.getX(), column.getZ())) {
                    stripXrayTileEntities(column, player, xray);
                    event.markForReEncode(true);
                }
            } else if (type == PacketType.Play.Server.BLOCK_CHANGE) {
                int before = new WrapperPlayServerBlockChange(event).getBlockId();
                freecamDone = true; // set first: the handler itself must not run twice
                if (freecamPass) handleBlockChange(event, player);
                if (event.isCancelled()) return; // NEW (1.5.4): dropped as hidden
                WrapperPlayServerBlockChange wrapper = new WrapperPlayServerBlockChange(event);
                Vector3i pos = wrapper.getBlockPosition();
                if (pos == null) return;
                int id = wrapper.getBlockId();
                int out = xray.processBlockChange(player, pos.getX(), pos.getY(), pos.getZ(), id,
                        id != before);
                if (out != id) {
                    wrapper.setBlockID(out);
                    event.markForReEncode(true);
                }
            } else {
                WrapperPlayServerMultiBlockChange.EncodedBlock[] pre = new WrapperPlayServerMultiBlockChange(event).getBlocks();
                int[] before = null;
                if (pre != null) {
                    before = new int[pre.length];
                    for (int i = 0; i < pre.length; i++) before[i] = pre[i] != null ? pre[i].getBlockId() : 0;
                }
                freecamDone = true; // set first: the handler itself must not run twice
                if (freecamPass) handleMultiBlockChange(event, player);
                if (event.isCancelled()) return; // NEW (1.5.4): every entry was hidden
                WrapperPlayServerMultiBlockChange wrapper = new WrapperPlayServerMultiBlockChange(event);
                WrapperPlayServerMultiBlockChange.EncodedBlock[] post = wrapper.getBlocks();
                // NEW (1.5.4): the freecam pass may drop hidden entries, so line
                // the original ids up again by entry instead of by index.
                if (pre != null && before != null && post != null && post.length != pre.length) {
                    java.util.IdentityHashMap<Object, Integer> at = new java.util.IdentityHashMap<>(pre.length * 2);
                    for (int i = 0; i < pre.length; i++) if (pre[i] != null) at.put(pre[i], before[i]);
                    int[] kept = new int[post.length];
                    for (int i = 0; i < post.length; i++) {
                        Integer id = post[i] != null ? at.get(post[i]) : null;
                        kept[i] = id != null ? id : (post[i] != null ? post[i].getBlockId() : 0);
                    }
                    before = kept;
                }
                if (xray.processMultiBlockChange(player, post, before)) {
                    event.markForReEncode(true);
                }
            }
        } catch (Exception e) {
            plugin.dbg("Anti-Xray " + type + " error for " + player.getName() + ": " + e.getMessage());
            if (!freecamDone) {
                if (type == PacketType.Play.Server.CHUNK_DATA) handleChunkData(event, player);
                else if (type == PacketType.Play.Server.BLOCK_CHANGE) handleBlockChange(event, player);
                else handleMultiBlockChange(event, player);
            }
        }
    }

    // BUGFIX: there used to be a voidFloorFor() here that made Anti-Xray skip
    // everything at/below void-y while Smart Fill / void hiding was active,
    // "leaving it to Smart Fill". But Smart Fill shows everything within its
    // reveal radius (28 blocks) as real, ores included, regardless of line of
    // sight - so a player in a deep tunnel saw every ore around them. Anti-Xray
    // now always runs after the freecam pass: whatever that pass replaced is
    // no longer an ore id and is skipped, every ore it left real is masked.

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
        boolean smartFillEnabled = plugin.isSmartFillEnabledFor(player);

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
            if (!fullProtection || minY > hardFloorY) {
                forgetFlatMasked(player.getUniqueId(), column);
                return;
            }

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
                if (stripTileEntitiesBelow(column, hardFloorY, false, null, null, null, hardFloorY)) modified = true;
            }

            if (modified) {
                try { wrapper.setIgnoreOldData(true); } catch (Exception ignored) {}
                event.markForReEncode(true);
                plugin.incrementChunksModified();
                plugin.addBlocksReplaced(replacedCount);
                rememberFlatMasked(player.getUniqueId(), column);
                plugin.dbg("CHUNK_DATA modified for " + player.getName()
                        + " (" + replacedCount + " blocks, flat, tile entities stripped)");
            } else {
                forgetFlatMasked(player.getUniqueId(), column);
            }
            return;
        }

        // Smart Fill mode
        forgetFlatMasked(player.getUniqueId(), column); // this send replaces any flat-masked copy
        if (!smartFillActive) return; // nothing to hide for this player right now

        int ceilingY = Math.max(hardFloorY, plugin.getSmartFillCeilingY());
        // NEW (follow-terrain): columns under a hill or mountain mask up to
        // their natural cover, above ceiling-y. Worked out with the palette.
        boolean followTerrain = plugin.isFollowTerrainWorld(player.getWorld());
        if (minY > ceilingY && !followTerrain) return;

        SmartFillEngine.ColumnPalette palette = null;
        int chunkX = 0, chunkZ = 0;
        try {
            chunkX = column.getX();
            chunkZ = column.getZ();
            palette = smartFill.getOrBuildPalette(worldName, chunkX, chunkZ,
                    sections, minY, ceilingY, followTerrain);
        } catch (Exception e) {
            plugin.dbg("SmartFill palette error: " + e.getMessage());
        }
        final int chunkTop = SmartFillEngine.chunkMaskTop(palette, ceilingY);
        final short[] cover = palette != null ? palette.coverTop : null;
        if (minY > chunkTop) return;

        SmartFillReveal reveal = plugin.getSmartFillReveal();
        org.bukkit.Location pLoc = player.getLocation();
        int pBX = pLoc.getBlockX();
        int pBY = pLoc.getBlockY();
        int pBZ = pLoc.getBlockZ();
        UUID playerId = player.getUniqueId();

        boolean modified      = false;
        long    replacedCount = 0;
        long    maskedBits    = 0;
        long[]  subMasks      = new long[Math.min(sections.length, 64)];
        // NEW (1.5.4): which blocks were replaced, per section (4096 bits), for the light pass.
        long[][] replacedBits = new long[sections.length][];

        for (int si = 0; si < sections.length; si++) {
            BaseChunk section = sections[si];
            if (section == null) continue;

            int sectionBaseY = minY + si * 16;
            if (sectionBaseY > chunkTop) continue;
            long replacedBefore = replacedCount;

            for (int ly = 0; ly < 16; ly++) {
                int worldY = sectionBaseY + ly;
                if (worldY > chunkTop) break;

                for (int lx = 0; lx < 16; lx++) {
                    int worldX = (chunkX << 4) | lx;

                    for (int lz = 0; lz < 16; lz++) {
                    int worldZ = (chunkZ << 4) | lz;
                    if (worldY > ceilingY && (cover == null || worldY > cover[(lz << 4) | lx])) continue;
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
                            subMasks[Math.min(si, 63)] |= 1L << ((lx >> 2) | (ly >> 2) << 2 | (lz >> 2) << 4);
                            markReplaced(replacedBits, si, lx, ly, lz);
                        }
                continue;
                }

                                // Smart Fill camouflage band
                                StateType type = current != null ? current.getType() : StateTypes.AIR;
                                if (current != null && SmartFillEngine.isSafeTerrain(current)) continue; // already looks natural

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
                            subMasks[Math.min(si, 63)] |= 1L << ((lx >> 2) | (ly >> 2) << 2 | (lz >> 2) << 4);
                            markReplaced(replacedBits, si, lx, ly, lz);
                        } catch (Exception ignored) {}
                    }
                }
            }
            if (replacedCount != replacedBefore) maskedBits |= 1L << Math.min(si, 63);
        }

        if (plugin.isPieChartProtectionEnabled() && column != null) {
            boolean tileModified = stripTileEntitiesBelow(column, chunkTop, true, reveal, playerId,
                    cover, ceilingY);
            if (tileModified) modified = true;
        }

        recordMaskedChunk(playerId, chunkX, chunkZ, maskedBits, minY >> 4, subMasks);

        if (modified) {
            try {
                if (darkenReplaced(wrapper.getLightData(), replacedBits)) {
                    plugin.dbg("CHUNK_DATA light cleared at filled blocks for " + player.getName());
                }
            } catch (Exception e) {
                plugin.dbg("CHUNK_DATA light error: " + e.getMessage());
            }
            try { wrapper.setIgnoreOldData(true); } catch (Exception ignored) {}
            event.markForReEncode(true);
            plugin.incrementChunksModified();
            plugin.addBlocksReplaced(replacedCount);
            plugin.dbg("CHUNK_DATA modified for " + player.getName()
                    + " (" + replacedCount + " blocks, smart-fill, tile entities stripped)");
        }
    }

    // ── Light (NEW 1.5.4) ─────────────────────────────────────────────────

    private static void markReplaced(long[][] bits, int si, int lx, int ly, int lz) {
        long[] b = bits[si];
        if (b == null) b = bits[si] = new long[64];
        int i = (ly << 8) | (lz << 4) | lx;
        b[i >> 6] |= 1L << (i & 63);
    }

    /**
     * NEW (1.5.4): light exploit. Chunk Finder clients (Autism/Anubis "Dog+",
     * Krypton, Glazed) read block light the server sends with each chunk: a
     * torch-lit base or a lava pocket gives itself away under fake stone. Both
     * light values are cleared at every block Smart Fill replaced, so the fill
     * is as dark as real rock. Light arrays have one extra section below the
     * world, so section si is light index si + 1, and arrays only exist for
     * set mask bits (packed in order).
     */
    private static boolean darkenReplaced(LightData light, long[][] replacedBits) {
        if (light == null) return false;
        boolean changed = darken(light.getBlockLightMask(), light.getBlockLightArray(), replacedBits);
        if (darken(light.getSkyLightMask(), light.getSkyLightArray(), replacedBits)) changed = true;
        return changed;
    }

    private static boolean darken(BitSet mask, byte[][] arrays, long[][] replacedBits) {
        if (mask == null || arrays == null) return false;
        boolean changed = false;
        int slot = 0;
        for (int li = mask.nextSetBit(0); li >= 0 && slot < arrays.length; li = mask.nextSetBit(li + 1), slot++) {
            int si = li - 1;
            if (si < 0 || si >= replacedBits.length || replacedBits[si] == null) continue;
            byte[] nib = arrays[slot];
            if (nib == null || nib.length < 2048) continue;
            long[] b = replacedBits[si];
            byte[] out = null;
            for (int w = 0; w < 64; w++) {
                long word = b[w];
                while (word != 0) {
                    int i = (w << 6) | Long.numberOfTrailingZeros(word);
                    word &= word - 1;
                    byte cur = (out != null ? out : nib)[i >> 1];
                    byte nv = (byte) ((i & 1) == 0 ? cur & 0xF0 : cur & 0x0F);
                    if (nv == cur) continue;
                    if (out == null) out = nib.clone(); // the array may be shared with other viewers
                    out[i >> 1] = nv;
                }
            }
            if (out != null) {
                arrays[slot] = out;
                changed = true;
            }
        }
        return changed;
    }

    /**
     * NEW (1.5.4): light updates (torches, lava, glowstone placed or broken)
     * reach the client on their own, after the block change itself was dropped.
     * Block light in hidden, unrevealed 4×4×4 cells of masked chunks is
     * cleared, the same way the chunk send does it.
     */
    private void handleUpdateLight(PacketSendEvent event, Player player) {
        plugin.incrementPacketsProcessed();
        try {
            if (!plugin.isSmartFillEnabledFor(player) || !plugin.isSmartFillActiveFor(player)) return;
            WrapperPlayServerUpdateLight wrapper = new WrapperPlayServerUpdateLight(event);
            Map<Long, MaskedChunk> m = maskedChunks.get(player.getUniqueId());
            MaskedChunk mc = m != null ? m.get(columnKey(wrapper.getX(), wrapper.getZ())) : null;
            if (mc == null || mc.sections == 0) return;
            LightData light = wrapper.getLightData();
            if (light == null) return;
            BitSet mask = light.getBlockLightMask();
            byte[][] arrays = light.getBlockLightArray();
            if (mask == null || arrays == null) return;

            SmartFillReveal reveal = plugin.getSmartFillReveal();
            org.bukkit.Location pLoc = player.getLocation();
            int pBX = pLoc.getBlockX(), pBY = pLoc.getBlockY(), pBZ = pLoc.getBlockZ();
            UUID playerId = player.getUniqueId();
            int baseX = wrapper.getX() << 4, baseZ = wrapper.getZ() << 4;
            boolean changed = false;
            int slot = 0;
            for (int li = mask.nextSetBit(0); li >= 0 && slot < arrays.length; li = mask.nextSetBit(li + 1), slot++) {
                int idx = li - 1;
                if (idx < 0 || idx >= mc.subMasks.length) continue;
                long cells = mc.subMasks[idx];
                if (cells == 0) continue;
                byte[] nib = arrays[slot];
                if (nib == null || nib.length < 2048) continue;
                int baseY = (mc.minSection + idx) << 4;
                byte[] out = null;
                for (long c = cells; c != 0; c &= c - 1) {
                    int bit = Long.numberOfTrailingZeros(c);
                    int sx = bit & 3, sy = (bit >> 2) & 3, sz = (bit >> 4) & 3;
                    if (reveal != null && reveal.isSubCellRevealed(playerId,
                            (baseX >> 2) + sx, (baseY >> 2) + sy, (baseZ >> 2) + sz, pBX, pBY, pBZ)) continue;
                    for (int y = sy << 2; y < (sy << 2) + 4; y++) {
                        for (int z = sz << 2; z < (sz << 2) + 4; z++) {
                            for (int x = sx << 2; x < (sx << 2) + 4; x++) {
                                int i = (y << 8) | (z << 4) | x;
                                byte cur = (out != null ? out : nib)[i >> 1];
                                byte nv = (byte) ((i & 1) == 0 ? cur & 0xF0 : cur & 0x0F);
                                if (nv == cur) continue;
                                if (out == null) out = nib.clone();
                                out[i >> 1] = nv;
                            }
                        }
                    }
                }
                if (out != null) {
                    arrays[slot] = out;
                    changed = true;
                }
            }
            if (changed) {
                event.markForReEncode(true);
                plugin.dbg("UPDATE_LIGHT cleared hidden block light in " + wrapper.getX() + "," + wrapper.getZ()
                        + " for " + player.getName());
            }
        } catch (Exception e) {
            plugin.dbg("UPDATE_LIGHT error: " + e.getMessage());
        }
    }

    /**
     * Strips tile entity entries from the chunk column.
     * In smart-fill mode, strips tile entities in unrevealed cave areas up to ceilingY.
     * In flat mode, strips all tile entities at or below voidY.
     */
    private boolean stripTileEntitiesBelow(Column column, int maxY, boolean useSmartFill,
                                           SmartFillReveal reveal, UUID playerId,
                                           short[] cover, int ceilingY) {
        try {
            TileEntity[] tileEntities = column.getTileEntities();
            if (tileEntities == null || tileEntities.length == 0) return false;

            List<TileEntity> filtered = new ArrayList<>(tileEntities.length);
            boolean changed = false;

            for (TileEntity te : tileEntities) {
                if (te == null) continue;
                int teY = te.getY();
                // Follow-terrain: above ceiling-y only where that column is covered.
                boolean inZone = teY <= maxY && (teY <= ceilingY || cover == null
                        || teY <= cover[((te.getZ() & 15) << 4) | (te.getX() & 15)]);
                if (inZone) {
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
            boolean smartFillEnabled = plugin.isSmartFillEnabledFor(player);

            if (!smartFillEnabled) {
                // Legacy flat mode
                if (!plugin.isProtectionActive(player)) return;
                if (pos.getY() > hardFloorY) return;
                int replacementId = plugin.getReplacementBlockId();
                if (wrapper.getBlockState().getGlobalId() != replacementId) {
                    wrapper.setBlockState(replacement);
                    event.markForReEncode(true);
                    plugin.addBlocksReplaced(1);
                    rememberFlatMasked(player.getUniqueId(), pos.getX() >> 4, pos.getZ() >> 4);
                    plugin.dbg("BLOCK_CHANGE modified at " + pos + " for " + player.getName());
                }
                return;
            }

            if (!plugin.isSmartFillActiveFor(player)) return;
            int ceilingY = Math.max(hardFloorY, plugin.getSmartFillCeilingY());
            int lx = pos.getX() & 15, lz = pos.getZ() & 15;
            SmartFillEngine.ColumnPalette palette =
                    smartFill.getCachedPalette(worldName, pos.getX() >> 4, pos.getZ() >> 4);
            // NEW (follow-terrain): this column's masking / reveal tops.
            int maskTop = SmartFillEngine.maskTop(palette, lx, lz, ceilingY);
            int revealTop = SmartFillEngine.revealTop(palette, lx, lz, ceilingY);

            // NEW: a block turning into air may join this player's cave to
            // another one, whatever caused it (WorldEdit, plugins, fluids).
            SmartFillReveal airReveal = plugin.getSmartFillReveal();
            if (airReveal != null && pos.getY() <= revealTop + 2 && wrapper.getBlockState().getType().isAir()) {
                airReveal.onAirSent(player.getUniqueId(), player.getWorld().getUID(),
                        pos.getX(), pos.getY(), pos.getZ());
            }

            // Invalidate palette cache if block changes near/above the boundary,
            // so a real terrain edit near the surface eventually shows up in
            // future fills instead of the palette going stale forever.
            int sampleCeiling = SmartFillEngine.chunkMaskTop(palette, ceilingY)
                    + (plugin.getSmartFillSampleRadius() + 1) * 16;
            if (pos.getY() > hardFloorY && (pos.getY() <= sampleCeiling
                    || (plugin.isFollowTerrainWorld(player.getWorld())
                        && SmartFillEngine.coverMayChange(palette, lx, lz, pos.getY(), ceilingY)))) {
                smartFill.invalidate(worldName, pos.getX() >> 4, pos.getZ() >> 4);
            }

            if (pos.getY() > maskTop) return;
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
                dropHiddenBlockChange(event, player, pos, "hard floor");
                return;
            }

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

            dropHiddenBlockChange(event, player, pos, "smart-fill");
        } catch (Exception e) {
            plugin.dbg("BLOCK_CHANGE error: " + e.getMessage());
        }
    }

    /**
     * NEW (1.5.4): block update exploit. Chunk Finder clients (Trouser-Streak
     * NewerNewChunks, Krypton, Glazed) flag chunks that get block updates
     * underground: flowing liquid, growing amethyst, a base being built. Even
     * a faked fill block is still an update, so a hidden, unrevealed update is
     * dropped instead. The cell is marked so the real block is re-sent once
     * the player can actually see the spot.
     */
    private void dropHiddenBlockChange(PacketSendEvent event, Player player, Vector3i pos, String why) {
        event.setCancelled(true);
        markMaskedCell(player.getUniqueId(), pos.getX(), pos.getY(), pos.getZ(),
                player.getWorld().getMinHeight() >> 4);
        plugin.addBlocksReplaced(1);
        plugin.dbg("BLOCK_CHANGE dropped (" + why + ") at " + pos + " for " + player.getName());
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
            boolean smartFillEnabled = plugin.isSmartFillEnabledFor(player);

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
                        rememberFlatMasked(player.getUniqueId(), block.getX() >> 4, block.getZ() >> 4);
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
            // NEW (follow-terrain): per-column masking tops in this chunk.
            final int chunkTop = SmartFillEngine.chunkMaskTop(palette, ceilingY);
            final int revealChunkTop = Math.max(chunkTop,
                    palette != null && palette.revealTop != null ? palette.maxReveal : ceilingY);
            boolean coverEdit = false;
            // NEW (1.5.4): hidden entries are dropped, not faked - see dropHiddenBlockChange.
            int minSection = player.getWorld().getMinHeight() >> 4;
            List<WrapperPlayServerMultiBlockChange.EncodedBlock> kept = null;

            // NEW: same as BLOCK_CHANGE: air arriving from any cause re-runs
            // the fill. One air block per packet is enough.
            if (reveal != null) {
                UUID worldId = player.getWorld().getUID();
                for (WrapperPlayServerMultiBlockChange.EncodedBlock block : blocks) {
                    if (block == null || block.getY() > revealChunkTop + 2) continue;
                    int id = block.getBlockId();
                    if (id != 0 && !WrappedBlockState.getByGlobalId(id, false).getType().isAir()) continue;
                    reveal.onAirSent(playerId, worldId, block.getX(), block.getY(), block.getZ());
                    break;
                }
            }

            for (int i = 0; i < blocks.length; i++) {
                WrapperPlayServerMultiBlockChange.EncodedBlock block = blocks[i];
                if (block == null) continue;
                if (palette != null && palette.coverTop != null && !coverEdit
                        && SmartFillEngine.coverMayChange(palette, block.getX() & 15, block.getZ() & 15,
                                block.getY(), ceilingY)) {
                    coverEdit = true;
                }
                boolean keep = true;
                if (block.getY() <= SmartFillEngine.maskTop(palette, block.getX() & 15, block.getZ() & 15, ceilingY)
                        // IMPROVEMENT: shallow surface structures (PvP arenas,
                        // crystal holes, player-built rooms) are exempted entirely -
                        // see the matching comment in handleChunkData.
                        && !smartFill.isExemptSurfaceStructure(palette, block.getX() & 15, block.getZ() & 15, block.getY())
                        // BUGFIX: see the matching comment in handleChunkData - never
                        // hide a position with no real solid roof above it (the hard
                        // floor ignores this, same as before).
                        && (block.getY() <= hardFloorY
                            || !smartFill.isOpenToSky(palette, block.getX() & 15, block.getZ() & 15, block.getY()))
                        && (reveal == null || !reveal.isAirRevealed(
                            block.getX(), block.getY(), block.getZ(), playerId, pBX, pBY, pBZ))) {
                    keep = false;
                }
                if (keep) {
                    if (kept != null) kept.add(block);
                    continue;
                }
                if (kept == null) {
                    kept = new ArrayList<>(blocks.length);
                    for (int j = 0; j < i; j++) if (blocks[j] != null) kept.add(blocks[j]);
                }
                markMaskedCell(playerId, block.getX(), block.getY(), block.getZ(), minSection);
                replacedCount++;
                modified = true;
            }

            // Follow-terrain: a mountain roof dug out (or built) by this edit
            // changes the column's cover; rebuild on the next chunk send.
            if (coverEdit && plugin.isFollowTerrainWorld(player.getWorld())) {
                for (WrapperPlayServerMultiBlockChange.EncodedBlock b : blocks) {
                    if (b != null) { smartFill.invalidate(worldName, b.getX() >> 4, b.getZ() >> 4); break; }
                }
            }

            if (modified) {
                plugin.addBlocksReplaced(replacedCount);
                if (kept == null || kept.isEmpty()) {
                    event.setCancelled(true);
                } else {
                    wrapper.setBlocks(kept.toArray(new WrapperPlayServerMultiBlockChange.EncodedBlock[0]));
                    event.markForReEncode(true);
                }
                plugin.dbg("MULTI_BLOCK_CHANGE dropped " + replacedCount + " hidden blocks for "
                        + player.getName() + (event.isCancelled() ? " (whole packet)" : ""));
            }
        } catch (Exception e) {
            plugin.dbg("MULTI_BLOCK_CHANGE error: " + e.getMessage());
        }
    }

    private boolean cancelIfXrayMasked(PacketSendEvent event, Player player, PacketType.Play.Server type,
                                       AntiXrayEngine xray) {
        try {
            Vector3i pos = type == PacketType.Play.Server.BLOCK_ENTITY_DATA
                    ? new WrapperPlayServerBlockEntityData(event).getPosition()
                    : new WrapperPlayServerBlockAction(event).getBlockPosition();
            if (pos == null || !xray.isMasked(player, pos.getX(), pos.getY(), pos.getZ())) return false;
            event.setCancelled(true);
            plugin.dbg("Anti-Xray " + type + " cancelled at " + pos + " for " + player.getName());
            return true;
        } catch (Exception e) {
            plugin.dbg("Anti-Xray " + type + " error: " + e.getMessage());
            return false;
        }
    }

    /** BUGFIX: drops block entities of blocks Anti-Xray just masked in this column. */
    private boolean stripXrayTileEntities(Column column, Player player, AntiXrayEngine xray) {
        try {
            TileEntity[] tes = column.getTileEntities();
            if (tes == null || tes.length == 0 || TILE_ENTITIES_FIELD == null) return false;
            List<TileEntity> kept = new ArrayList<>(tes.length);
            for (TileEntity te : tes) {
                if (te != null && xray.isMasked(player, te.getX(), te.getY(), te.getZ())) continue;
                kept.add(te);
            }
            if (kept.size() == tes.length) return false;
            TILE_ENTITIES_FIELD.set(column, kept.toArray(new TileEntity[0]));
            return true;
        } catch (Exception e) {
            plugin.dbg("Anti-Xray tile entity strip error: " + e.getMessage());
            return false;
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
     * NEW: entity spawn packets, Netty side. Caches the position for later
     * ENTITY_SOUND_EFFECT, then lets {@link EntityHider} decide: the hidden
     * zone (void-y / Smart Fill, pie-chart-protection) plus line of sight.
     * A cancelled spawn is re-sent by the hider's next check.
     */
    private void handleEntitySpawn(PacketSendEvent event, Player player,
                                   PacketType.Play.Server type, EntityHider hider) {
        plugin.incrementPacketsProcessed();
        try {
            int entityId;
            UUID entityUuid;
            Vector3d pos;
            boolean isPlayer;
            String typeKey = null;
            if (type == PacketType.Play.Server.SPAWN_PLAYER) {
                // Only 1.19 and 1.20.1 and older; newer versions use SPAWN_ENTITY.
                WrapperPlayServerSpawnPlayer w = new WrapperPlayServerSpawnPlayer(event);
                entityId = w.getEntityId();
                entityUuid = w.getUUID();
                pos = w.getPosition();
                isPlayer = true;
            } else {
                WrapperPlayServerSpawnEntity w = new WrapperPlayServerSpawnEntity(event);
                EntityType et = w.getEntityType();
                isPlayer = et == EntityTypes.PLAYER;
                entityId = w.getEntityId();
                entityUuid = w.getUUID().orElse(null);
                pos = w.getPosition();
                if (et != null) {
                    try {
                        typeKey = et.getName().getKey();
                    } catch (Exception ignored) {}
                }
            }
            if (pos == null) return;
            int[] entityPos = blockPos(pos);
            // Players aren't in this cache: ENTITY_SOUND_EFFECT for a player
            // is their own sound and was never cancelled.
            if (!isPlayer) entityYCache.put(entityId, entityPos);
            if (hider == null) return;

            UUID viewerId = player.getUniqueId();
            // Chunk mode relies on this to hide new entities in the zone, so
            // hide-entities alone also checks it (like the old spawn hider).
            boolean zoneHidden = (plugin.isPieChartProtectionEnabled() || hider.isEnabled()) && !isPlayer
                    && isEntityPositionHidden(player, entityPos[0], entityPos[1], entityPos[2]);
            if (hider.onSpawnPacket(viewerId, entityId, entityUuid, isPlayer, typeKey,
                    pos.getX(), pos.getY(), pos.getZ(), zoneHidden)) {
                event.setCancelled(true);
                plugin.dbg("Spawn cancelled for entity #" + entityId
                        + (typeKey != null ? " (" + typeKey + ")" : isPlayer ? " (player)" : "")
                        + " at Y=" + entityPos[1] + " for " + player.getName()
                        + (zoneHidden ? " [zone]" : " [sight]"));
            }
        } catch (Exception e) {
            plugin.dbg("SPAWN packet error: " + e.getMessage());
        }
    }

    /**
     * NEW: packets about an entity the client doesn't have (or must not see)
     * are dropped, so nothing re-adds it client-side and no ESP mod picks it
     * up from a movement or metadata packet.
     */
    private void cancelIfBlockedEntity(PacketSendEvent event, UUID viewer,
                                       PacketType.Play.Server type, EntityHider hider) {
        try {
            int id;
            if (type == PacketType.Play.Server.ENTITY_RELATIVE_MOVE) {
                id = new WrapperPlayServerEntityRelativeMove(event).getEntityId();
            } else if (type == PacketType.Play.Server.ENTITY_RELATIVE_MOVE_AND_ROTATION) {
                id = new WrapperPlayServerEntityRelativeMoveAndRotation(event).getEntityId();
            } else if (type == PacketType.Play.Server.ENTITY_TELEPORT) {
                id = new WrapperPlayServerEntityTeleport(event).getEntityId();
            } else if (type == PacketType.Play.Server.ENTITY_POSITION_SYNC) {
                id = new WrapperPlayServerEntityPositionSync(event).getId();
            } else if (type == PacketType.Play.Server.ENTITY_ROTATION) {
                id = new WrapperPlayServerEntityRotation(event).getEntityId();
            } else if (type == PacketType.Play.Server.ENTITY_HEAD_LOOK) {
                id = new WrapperPlayServerEntityHeadLook(event).getEntityId();
            } else if (type == PacketType.Play.Server.ENTITY_VELOCITY) {
                id = new WrapperPlayServerEntityVelocity(event).getEntityId();
            } else if (type == PacketType.Play.Server.ENTITY_METADATA) {
                id = new WrapperPlayServerEntityMetadata(event).getEntityId();
            } else if (type == PacketType.Play.Server.ENTITY_EQUIPMENT) {
                id = new WrapperPlayServerEntityEquipment(event).getEntityId();
            } else if (type == PacketType.Play.Server.ENTITY_ANIMATION) {
                id = new WrapperPlayServerEntityAnimation(event).getEntityId();
            } else if (type == PacketType.Play.Server.ENTITY_STATUS) {
                id = new WrapperPlayServerEntityStatus(event).getEntityId();
            } else if (type == PacketType.Play.Server.ENTITY_EFFECT) {
                id = new WrapperPlayServerEntityEffect(event).getEntityId();
            } else if (type == PacketType.Play.Server.REMOVE_ENTITY_EFFECT) {
                id = new WrapperPlayServerRemoveEntityEffect(event).getEntityId();
            } else if (type == PacketType.Play.Server.UPDATE_ATTRIBUTES) {
                id = new WrapperPlayServerUpdateAttributes(event).getEntityId();
            } else if (type == PacketType.Play.Server.SET_PASSENGERS) {
                id = new WrapperPlayServerSetPassengers(event).getEntityId();
            } else if (type == PacketType.Play.Server.ENTITY_SOUND_EFFECT) {
                id = new WrapperPlayServerEntitySoundEffect(event).getEntityId();
            } else {
                return;
            }
            if (hider.isBlocked(viewer, id)) event.setCancelled(true);
        } catch (Exception ignored) {}
    }

    /**
     * NEW: our own hide+show re-sync of a hidden player makes the server send
     * a tab-list remove followed by an add. Dropping the remove keeps the tab
     * list exactly as it was (the add is a no-op for an entry that's still
     * there). PLAYER_INFO_REMOVE is 1.19.3+; older versions use PLAYER_INFO
     * with the REMOVE_PLAYER action.
     */
    private void suppressOwnInfoRemove(PacketSendEvent event, UUID viewer,
                                       PacketType.Play.Server type, EntityHider hider) {
        try {
            if (type == PacketType.Play.Server.PLAYER_INFO_REMOVE) {
                WrapperPlayServerPlayerInfoRemove w = new WrapperPlayServerPlayerInfoRemove(event);
                List<UUID> ids = w.getProfileIds();
                if (ids == null || ids.isEmpty()) return;
                List<UUID> keep = new ArrayList<>(ids.size());
                for (UUID id : ids) {
                    if (!hider.consumeInfoRemove(viewer, id)) keep.add(id);
                }
                if (keep.size() == ids.size()) return;
                if (keep.isEmpty()) {
                    event.setCancelled(true);
                } else {
                    w.setProfileIds(keep); // other removes in the same packet still apply
                    event.markForReEncode(true);
                }
                return;
            }
            WrapperPlayServerPlayerInfo w = new WrapperPlayServerPlayerInfo(event);
            if (w.getAction() != WrapperPlayServerPlayerInfo.Action.REMOVE_PLAYER) return;
            List<WrapperPlayServerPlayerInfo.PlayerData> data = w.getPlayerDataList();
            if (data == null || data.isEmpty()) return;
            List<WrapperPlayServerPlayerInfo.PlayerData> keep = new ArrayList<>(data.size());
            for (WrapperPlayServerPlayerInfo.PlayerData d : data) {
                UserProfile p = d.getUserProfile();
                UUID id = p != null ? p.getUUID() : null;
                if (id == null || !hider.consumeInfoRemove(viewer, id)) keep.add(d);
            }
            if (keep.size() == data.size()) return;
            if (keep.isEmpty()) {
                event.setCancelled(true);
            } else {
                w.setPlayerDataList(keep);
                event.markForReEncode(true);
            }
        } catch (Exception e) {
            plugin.dbg("PLAYER_INFO suppression error: " + e.getMessage());
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
        boolean smartFillEnabled = plugin.isSmartFillEnabledFor(player);

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
        if (y > ceilingY) {
            // NEW (follow-terrain): above ceiling-y only inside covered columns.
            if (x == null || z == null) return false;
            SmartFillEngine.ColumnPalette p = smartFill.getCachedPalette(player.getWorld().getName(), x >> 4, z >> 4);
            if (y > SmartFillEngine.maskTop(p, x & 15, z & 15, ceilingY)) return false;
        }

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
        if (!plugin.isSmartFillEnabledFor(player)) {
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

    // ── Masked-chunk tracking (used to skip no-op chunk refreshes) ─────────

    private void recordMaskedChunk(UUID playerId, int cx, int cz, long bits, int minSection, long[] subMasks) {
        long key = columnKey(cx, cz);
        if (bits == 0) {
            Map<Long, MaskedChunk> m = maskedChunks.get(playerId);
            if (m != null) m.remove(key);
            return;
        }
        maskedChunks.computeIfAbsent(playerId, k -> new ConcurrentHashMap<>())
                .put(key, new MaskedChunk(bits, minSection, subMasks));
    }

    private void forgetChunk(UUID playerId, int cx, int cz) {
        Map<Long, MaskedChunk> m = maskedChunks.get(playerId);
        if (m != null) m.remove(columnKey(cx, cz));
    }

    /**
     * NEW (1.5.4): a block update at a hidden spot was dropped, so the client
     * may be out of date there. Marking its 4×4×4 cell as masked lets the
     * proximity refresh re-send the chunk once the spot is revealed, so no
     * ghost blocks are left. Records are replaced, never edited in place.
     */
    private void markMaskedCell(UUID playerId, int bx, int by, int bz, int minSection) {
        int idx = Math.min(Math.max((by >> 4) - minSection, 0), 63);
        int bit = ((bx & 15) >> 2) | ((by & 15) >> 2) << 2 | ((bz & 15) >> 2) << 4;
        Map<Long, MaskedChunk> m = maskedChunks.computeIfAbsent(playerId, k -> new ConcurrentHashMap<>());
        long key = columnKey(bx >> 4, bz >> 4);
        MaskedChunk old = m.get(key);
        long[] subs;
        long sections;
        if (old != null && old.minSection == minSection) {
            if ((old.subMasks.length > idx) && (old.subMasks[idx] & (1L << bit)) != 0) return;
            subs = Arrays.copyOf(old.subMasks, Math.max(old.subMasks.length, idx + 1));
            sections = old.sections;
        } else {
            subs = new long[idx + 1];
            sections = 0;
        }
        subs[idx] |= 1L << bit;
        m.put(key, new MaskedChunk(sections | (1L << idx), minSection, subs));
    }

    private void rememberFlatMasked(UUID playerId, int cx, int cz) {
        flatMaskedChunks.computeIfAbsent(playerId, k -> ConcurrentHashMap.newKeySet()).add(columnKey(cx, cz));
    }

    private void rememberFlatMasked(UUID playerId, Column column) {
        if (column != null) rememberFlatMasked(playerId, column.getX(), column.getZ());
    }

    private void forgetFlatMasked(UUID playerId, int cx, int cz) {
        Set<Long> s = flatMaskedChunks.get(playerId);
        if (s != null) s.remove(columnKey(cx, cz));
    }

    private void forgetFlatMasked(UUID playerId, Column column) {
        if (column != null) forgetFlatMasked(playerId, column.getX(), column.getZ());
    }

    private void forgetFlatMaskedFromPacket(PacketSendEvent event, UUID playerId) {
        Set<Long> s = flatMaskedChunks.get(playerId);
        if (s == null || s.isEmpty()) return;
        try {
            forgetFlatMasked(playerId, new WrapperPlayServerChunkData(event).getColumn());
        } catch (Exception ignored) {}
    }

    /**
     * Removes and returns the chunks this player's client still has with
     * flat masking in them. Re-sending them while protection is off
     * replaces them with the real blocks; if protection is back on by
     * then, they are simply masked and recorded again.
     */
    public Set<Long> takeFlatMaskedChunks(UUID playerId) {
        Set<Long> s = flatMaskedChunks.get(playerId);
        if (s == null || s.isEmpty()) return Collections.emptySet();
        Set<Long> out = new HashSet<>();
        for (Long k : s) {
            if (s.remove(k)) out.add(k);
        }
        return out;
    }

    private static long columnKey(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
    }

    /** True if the client was sent this chunk with any Smart Fill blocks still in it. */
    public boolean hasMaskedBlocks(UUID playerId, int cx, int cz) {
        Map<Long, MaskedChunk> m = maskedChunks.get(playerId);
        return m != null && m.containsKey(columnKey(cx, cz));
    }

    /**
     * IMPROVEMENT: decides whether re-sending this chunk would actually show
     * the player something new: the client must still have fake blocks in it,
     * and one of the masked 4×4×4 sub-cells must be fully revealed now (fill,
     * line of sight, or well inside the sphere where it still applies). The
     * re-send then can't mask that sub-cell again, so this never loops; a
     * smaller reveal means fewer re-sends, not more (each one is a visible
     * reload on Bedrock). Any thread (reads snapshots only).
     */
    public boolean shouldRefreshForReveal(Player player, int cx, int cz) {
        UUID id = player.getUniqueId();
        Map<Long, MaskedChunk> m = maskedChunks.get(id);
        if (m == null) return false;
        MaskedChunk mc = m.get(columnKey(cx, cz));
        if (mc == null) return false;

        SmartFillReveal reveal = plugin.getSmartFillReveal();
        if (reveal == null) return false;

        org.bukkit.Location loc = player.getLocation();
        int px = loc.getBlockX(), py = loc.getBlockY(), pz = loc.getBlockZ();
        long farSq = (long) reveal.getFarOnlyDistance() * reveal.getFarOnlyDistance();
        for (int i = 0; i < mc.subMasks.length; i++) {
            long bits = mc.subMasks[i];
            if (bits == 0) continue;
            int secY = mc.minSection + i;
            // IMPROVEMENT: a section this far away can only be revealed by the
            // far sight sweep, which works per section. One lookup gives the
            // same answer as checking each of its masked cells.
            long ndx = axisGap(px, cx << 4), ndy = axisGap(py, secY << 4), ndz = axisGap(pz, cz << 4);
            if (ndx * ndx + ndy * ndy + ndz * ndz > farSq) {
                if (reveal.isSectionSightRevealed(id, cx, secY, cz)) return true;
                continue;
            }
            int sy0 = secY << 2;
            int sx0 = cx << 2, sz0 = cz << 2;
            while (bits != 0) {
                int b = Long.numberOfTrailingZeros(bits);
                bits &= bits - 1;
                if (reveal.isSubCellRevealed(id, sx0 + (b & 3), sy0 + ((b >> 2) & 3), sz0 + ((b >> 4) & 3),
                        px, py, pz)) return true;
            }
        }
        return false;
    }

    /** Distance from p to the 16-block span starting at lo (0 inside). */
    private static long axisGap(int p, int lo) {
        if (p < lo) return lo - p;
        if (p > lo + 15) return p - (lo + 15);
        return 0;
    }

    /** Drops all per-player tracking (call on quit). */
    public void cleanupPlayer(UUID id) {
        maskedChunks.remove(id);
        flatMaskedChunks.remove(id);
    }

    /** NEW: Smart Fill palette cache (follow-terrain lookups from the reveal side). */
    SmartFillEngine getSmartFill() { return smartFill; }

    /** Masked-chunk records only; used when Smart Fill turns off for a player. */
    public void clearMaskedChunks(UUID id) {
        maskedChunks.remove(id);
    }


    /**
     * NEW: the hidden-zone half of the EntityHider decision (void-y floor /
     * Smart Fill band). True if a block at this position is not real for the
     * player right now, so an entity there must not be shown however clear
     * the line of sight is. Netty-safe (cached state only) and safe on the
     * player's thread.
     */
    public boolean isEntityPositionHidden(Player player, int x, int y, int z) {
        World world = player.getWorld();
        if (world == null || !plugin.isWorldProtected(world.getName())) return false;
        if (!plugin.isProtectionActive(player) && !plugin.isSmartFillActiveFor(player)) return false;
        if (isAtOrBelowVoidY(player, y)) {
            if (!plugin.isSmartFillEnabledFor(player)) return plugin.isProtectionActive(player);
            return shouldCancelForPosition(player, y, x, z);
        }
        return isBandPositionHidden(player, x, y, z);
    }

    /**
     * BUGFIX: entities were only hidden at or below void-y, but Smart Fill
     * also fills caves from void-y up to ceiling-y. A bat in one of those
     * filled caves was sent anyway and flew around inside the fake stone.
     * Same rules as the block fill in handleChunkData (surface structures,
     * open sky, reveal). With no palette for the column yet, nothing there
     * was filled, so the entity stays visible.
     */
    private boolean isBandPositionHidden(Player player, int x, int y, int z) {
        if (!plugin.isSmartFillEnabledFor(player) || !plugin.isSmartFillActiveFor(player)) return false;
        String worldName = player.getWorld().getName();
        SmartFillEngine.ColumnPalette palette = smartFill.getCachedPalette(worldName, x >> 4, z >> 4);
        if (palette == null) return false;
        int top = SmartFillEngine.maskTop(palette, x & 15, z & 15,
                Math.max(plugin.getVoidY(worldName), plugin.getSmartFillCeilingY()));
        if (y > top) return false;
        if (smartFill.isExemptSurfaceStructure(palette, x & 15, z & 15, y)) return false;
        if (smartFill.isOpenToSky(palette, x & 15, z & 15, y)) return false;
        SmartFillReveal reveal = plugin.getSmartFillReveal();
        if (reveal == null) return false;
        org.bukkit.Location loc = player.getLocation();
        return !reveal.isAirRevealed(x, y, z, player.getUniqueId(),
                loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
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
