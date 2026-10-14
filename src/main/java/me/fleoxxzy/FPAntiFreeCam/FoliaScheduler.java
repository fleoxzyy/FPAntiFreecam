package me.fleoxxzy.FPAntiFreeCam;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.Collection;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Folia-aware chunk refresh scheduler.
 *
 * <p>BUGFIX (network saturation with Smart Fill): this used to call
 * {@code World#refreshChunk}, which re-sends the chunk to EVERY player
 * watching it. Smart Fill refreshes are per player (they depend on that
 * player's position and view), so every player's once-a-second refresh was
 * also pushed to everyone around them - with N players together the chunk
 * traffic grew with N², saturating the network and raising everyone's ping.
 * Refreshes are now queued per player, sent only to that player (see
 * {@link PlayerChunkSender}), and drained at most
 * {@code performance.max-chunks-per-player-per-tick} per player per tick
 * instead of 25 per region per tick with no overall limit.
 */
public final class FoliaScheduler {

    private final Plugin            plugin;
    private final PlayerChunkSender sender;
    private final Map<UUID, Long>   lastRefreshTime = new ConcurrentHashMap<>();
    private final Map<UUID, PlayerQueue> queues     = new ConcurrentHashMap<>();
    private volatile int maxPerPlayerPerTick = 8;

    private static final class PlayerQueue {
        final Queue<ChunkTask> tasks   = new ConcurrentLinkedQueue<>();
        /** Chunks already queued for this player, so overlapping requests don't re-send the same chunk. */
        final Set<Long>        pending = ConcurrentHashMap.newKeySet();
        final AtomicBoolean    draining = new AtomicBoolean();
    }

    public FoliaScheduler(Plugin plugin) {
        this.plugin = plugin;
        this.sender = new PlayerChunkSender(plugin);
        loadSettings();
    }

    public void loadSettings() {
        maxPerPlayerPerTick = Math.max(1, Math.min(64,
                plugin.getConfig().getInt("performance.max-chunks-per-player-per-tick", 8)));
    }

    // ── Public API ────────────────────────────────────────────────────────

    public void refreshChunks(Player player, int radiusChunks) {
        // BUGFIX (void gaps): this used to drop any request within 100 ms of
        // the previous one. When protection turned on and then off again
        // that quickly (raycast zone, teleports), the "turn off" refresh was
        // silently dropped and the client kept masked chunks. Duplicate
        // chunks are already skipped by the per-player pending set.
        UUID id  = player.getUniqueId();
        long now = System.currentTimeMillis();

        Location loc   = player.getLocation();
        World    world = loc.getWorld();
        if (world == null) return;

        int px = loc.getBlockX() >> 4;
        int pz = loc.getBlockZ() >> 4;

        // Nearest chunks first, so what the player is looking at updates first.
        PlayerQueue q = queueFor(id);
        for (int r = 0; r <= radiusChunks; r++) {
            for (int cx = px - r; cx <= px + r; cx++) {
                for (int cz = pz - r; cz <= pz + r; cz++) {
                    if (Math.abs(cx - px) == r || Math.abs(cz - pz) == r) {
                        enqueue(q, world, cx, cz);
                    }
                }
            }
        }
        lastRefreshTime.put(id, now);
        startDrain(player, q);
    }

    /**
     * Refreshes specific chunk columns (keys packed as
     * {@code (cx << 32) | (cz & 0xFFFFFFFFL)}) for this player only.
     * Not subject to the refreshChunks cooldown; callers rate-limit themselves.
     */
    public void refreshColumns(Player player, Collection<Long> columnKeys) {
        World world = player.getWorld();
        if (world == null) return;
        PlayerQueue q = queueFor(player.getUniqueId());
        for (long key : columnKeys) {
            enqueue(q, world, (int) (key >> 32), (int) key);
        }
        startDrain(player, q);
    }

    public void cleanupPlayer(UUID id) {
        lastRefreshTime.remove(id);
        queues.remove(id);
    }

    public String stats() {
        int pending = 0;
        for (PlayerQueue q : queues.values()) pending += q.pending.size();
        return "tracked-players:" + lastRefreshTime.size() + "  pending:" + pending
                + "  per-player/tick:" + maxPerPlayerPerTick;
    }

    public static boolean shouldUse() {
        return PlatformUtil.isFolia()
                && PlatformUtil.hasRegionScheduler()
                && PlatformUtil.hasGlobalRegionScheduler();
    }

    // ── Internal ─────────────────────────────────────────────────────────

    private PlayerQueue queueFor(UUID id) {
        return queues.computeIfAbsent(id, k -> new PlayerQueue());
    }

    private static void enqueue(PlayerQueue q, World world, int cx, int cz) {
        long key = ((long) cx << 32) | (cz & 0xFFFFFFFFL);
        if (q.pending.add(key)) q.tasks.offer(new ChunkTask(world, cx, cz, key));
    }

    private void startDrain(Player player, PlayerQueue q) {
        if (!q.draining.compareAndSet(false, true)) return; // a drain loop is already running
        scheduleDrain(player.getUniqueId(), q, false);
    }

    private void scheduleDrain(UUID id, PlayerQueue q, boolean delayed) {
        Player player = Bukkit.getPlayer(id);
        if (player == null || !player.isOnline() || queues.get(id) != q) {
            q.draining.set(false);
            return;
        }
        Runnable run = () -> drain(id, q);
        Runnable retired = () -> q.draining.set(false);
        boolean scheduled = delayed
                ? PlatformUtil.runForEntityLater(plugin, player, run, retired, 1L)
                : PlatformUtil.runForEntity(plugin, player, run, retired);
        if (!scheduled) q.draining.set(false);
    }

    /** Runs on the player's own region thread. */
    private void drain(UUID id, PlayerQueue q) {
        Player player = Bukkit.getPlayer(id);
        if (player == null || !player.isOnline() || queues.get(id) != q) {
            q.draining.set(false);
            return;
        }

        int sent = 0;
        while (sent < maxPerPlayerPerTick) {
            ChunkTask t = q.tasks.poll();
            if (t == null) break;
            q.pending.remove(t.key);
            if (!sender.shouldSend(player, t.world, t.cx, t.cz)) continue;
            try {
                sendOne(player, t);
                sent++;
            } catch (Exception e) {
                plugin.getLogger().warning("[FPAntiFreeCam] Failed to refresh chunk ("
                        + t.cx + "," + t.cz + ") for " + player.getName() + ": " + e.getMessage());
            }
        }

        if (q.tasks.isEmpty()) {
            q.draining.set(false);
            // Re-check: something may have been queued between poll() and set(false).
            if (!q.tasks.isEmpty() && q.draining.compareAndSet(false, true)) {
                scheduleDrain(id, q, true);
            }
        } else {
            scheduleDrain(id, q, true);
        }
    }

    private void sendOne(Player player, ChunkTask t) {
        Location chunkLoc = new Location(t.world, t.cx << 4, 0, t.cz << 4);
        if (PlatformUtil.isOwnedByCurrentRegion(chunkLoc)) {
            sender.send(player, t.world, t.cx, t.cz);
        } else {
            // Rare: chunk is loaded by this player but sits in a different
            // region. Read it on its owning thread; the packet still only
            // goes to this player.
            PlatformUtil.runTask(plugin, chunkLoc, () -> sender.send(player, t.world, t.cx, t.cz));
        }
    }

    // ── Inner record ──────────────────────────────────────────────────────

    private static final class ChunkTask {
        final World world;
        final int   cx, cz;
        final long  key;

        ChunkTask(World world, int cx, int cz, long key) {
            this.world = world;
            this.cx    = cx;
            this.cz    = cz;
            this.key   = key;
        }
    }
}
