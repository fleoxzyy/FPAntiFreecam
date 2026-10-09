package me.fleoxxzy.FPAntiFreeCam;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.Collection;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Paper / Spigot chunk refresh scheduler.
 * Queues chunks on the main thread and drains up to performance.max-chunks-per-tick per tick
 * to spread CPU load without holding up the server.
 */
public final class PaperScheduler {

    private static final long REFRESH_COOLDOWN_MS = 50L;

    private final Plugin              plugin;
    private       int                 maxPerTick      = 40;
    private final Map<UUID, Long>     lastRefreshTime = new ConcurrentHashMap<>();
    private final Queue<ChunkTask>    queue           = new ConcurrentLinkedQueue<>();
    /** Chunks already waiting in the queue, so overlapping refresh requests don't re-send the same chunk. */
    private final Set<String>         pending         = ConcurrentHashMap.newKeySet();
    private       BukkitTask          drainTask;

    public PaperScheduler(Plugin plugin) {
        this.plugin = plugin;
        loadSettings();
        startDrainTask();
    }

    public void loadSettings() {
        maxPerTick = Math.max(1, Math.min(500,
                plugin.getConfig().getInt("performance.max-chunks-per-tick", 40)));
    }

    // ── Public API ────────────────────────────────────────────────────────

    public void refreshChunks(Player player, int radiusChunks) {
        UUID id  = player.getUniqueId();
        long now = System.currentTimeMillis();
        Long last = lastRefreshTime.get(id);
        if (last != null && now - last < REFRESH_COOLDOWN_MS) return;

        World world = player.getWorld();
        if (world == null) return;

        int px = player.getLocation().getBlockX() >> 4;
        int pz = player.getLocation().getBlockZ() >> 4;

        // Queue rings from the centre outward so the nearest chunks refresh first
        for (int r = 0; r <= radiusChunks; r++) {
            for (int cx = px - r; cx <= px + r; cx++) {
                for (int cz = pz - r; cz <= pz + r; cz++) {
                    if (Math.abs(cx - px) == r || Math.abs(cz - pz) == r) {
                        enqueue(new ChunkTask(world, cx, cz, id, r));
                    }
                }
            }
        }
        lastRefreshTime.put(id, now);
    }

    /**
     * Queues a refresh for specific chunk columns (keys packed as
     * {@code (cx << 32) | (cz & 0xFFFFFFFFL)}). Not subject to the
     * per-player refreshChunks cooldown; callers rate-limit themselves.
     */
    public void refreshColumns(Player player, Collection<Long> columnKeys) {
        World world = player.getWorld();
        if (world == null) return;
        UUID id = player.getUniqueId();
        for (long key : columnKeys) {
            enqueue(new ChunkTask(world, (int) (key >> 32), (int) key, id, 0));
        }
    }

    public void cleanupPlayer(UUID id) {
        lastRefreshTime.remove(id);
        queue.removeIf(t -> {
            if (!t.playerId.equals(id)) return false;
            pending.remove(t.key);
            return true;
        });
    }

    public void shutdown() {
        if (drainTask != null && !drainTask.isCancelled()) drainTask.cancel();
        queue.clear();
        pending.clear();
        lastRefreshTime.clear();
    }

    private void enqueue(ChunkTask task) {
        if (pending.add(task.key)) queue.offer(task);
    }

    public String stats() {
        return "queue:" + queue.size() + "  tracked:" + lastRefreshTime.size();
    }

    public static boolean shouldUse() {
        return !PlatformUtil.isFolia();
    }

    // ── Internal ─────────────────────────────────────────────────────────

    private void startDrainTask() {
        drainTask = Bukkit.getScheduler().runTaskTimer(plugin, this::drain, 1L, 1L);
    }

    private void drain() {
        int processed = 0;
        while (!queue.isEmpty() && processed < maxPerTick) {
            ChunkTask t = queue.poll();
            if (t == null) break;
            pending.remove(t.key);
            try {
                Player p = Bukkit.getPlayer(t.playerId);
                if (p != null && p.isOnline()
                        && p.getWorld().equals(t.world)
                        && t.world.isChunkLoaded(t.cx, t.cz)) {
                    t.world.refreshChunk(t.cx, t.cz);
                    processed++;
                }
            } catch (Exception e) {
                plugin.getLogger().warning("[FPAntiFreeCam] Chunk refresh failed ("
                        + t.cx + "," + t.cz + "): " + e.getMessage());
            }
        }
    }

    // ── Inner record ──────────────────────────────────────────────────────

    private static class ChunkTask {
        final World world;
        final int   cx, cz;
        final UUID  playerId;
        /** Ring distance from player – used for priority ordering. */
        final int   ring;
        final String key;
        ChunkTask(World world, int cx, int cz, UUID playerId, int ring) {
            this.world = world; this.cx = cx; this.cz = cz;
            this.playerId = playerId; this.ring = ring;
            this.key = world.getUID() + ":" + cx + ":" + cz;
        }
    }
}
