package me.fleoxxzy.FPAntiFreeCam;

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

/**
 * Folia-aware chunk refresh scheduler.
 * Batches chunk refreshes by region so they always run on the correct
 * region thread, avoiding cross-region access exceptions.
 */
public final class FoliaScheduler {

    private static final long REGION_SWITCH_COOLDOWN_MS = 100L;

    private final Plugin          plugin;
    private final Map<UUID, Long> lastRefreshTime = new ConcurrentHashMap<>();
    /** Chunks already scheduled for refresh, so overlapping requests don't re-send the same chunk. */
    private final Set<String>     pending         = ConcurrentHashMap.newKeySet();

    public FoliaScheduler(Plugin plugin) {
        this.plugin = plugin;
    }

    // ── Public API ────────────────────────────────────────────────────────

    public void refreshChunks(Player player, int radiusChunks) {
        UUID id  = player.getUniqueId();
        long now = System.currentTimeMillis();
        Long last = lastRefreshTime.get(id);
        if (last != null && now - last < REGION_SWITCH_COOLDOWN_MS) return;

        Location loc   = player.getLocation();
        World    world = loc.getWorld();
        if (world == null) return;

        int px = loc.getBlockX() >> 4;
        int pz = loc.getBlockZ() >> 4;

        Map<String, Queue<ChunkTask>> byRegion = new ConcurrentHashMap<>();
        for (int cx = px - radiusChunks; cx <= px + radiusChunks; cx++) {
            for (int cz = pz - radiusChunks; cz <= pz + radiusChunks; cz++) {
                addTask(byRegion, world, cx, cz, loc.getY());
            }
        }

        scheduleAll(byRegion);
        lastRefreshTime.put(id, now);
    }

    /**
     * Refreshes specific chunk columns (keys packed as
     * {@code (cx << 32) | (cz & 0xFFFFFFFFL)}), grouped by region so each
     * batch runs on its owning thread. Not subject to the refreshChunks
     * cooldown; callers rate-limit themselves.
     */
    public void refreshColumns(Player player, Collection<Long> columnKeys) {
        Location loc   = player.getLocation();
        World    world = loc.getWorld();
        if (world == null) return;

        Map<String, Queue<ChunkTask>> byRegion = new ConcurrentHashMap<>();
        for (long key : columnKeys) {
            addTask(byRegion, world, (int) (key >> 32), (int) key, loc.getY());
        }
        scheduleAll(byRegion);
    }

    public void cleanupPlayer(UUID id) {
        lastRefreshTime.remove(id);
    }

    public String stats() {
        return "tracked-players:" + lastRefreshTime.size() + "  pending:" + pending.size();
    }

    public static boolean shouldUse() {
        return PlatformUtil.isFolia()
                && PlatformUtil.hasRegionScheduler()
                && PlatformUtil.hasGlobalRegionScheduler();
    }

    // ── Internal ─────────────────────────────────────────────────────────

    private void addTask(Map<String, Queue<ChunkTask>> byRegion, World world, int cx, int cz, double y) {
        ChunkTask task = new ChunkTask(world, cx, cz, new Location(world, cx * 16.0, y, cz * 16.0));
        if (!pending.add(task.key)) return;
        byRegion.computeIfAbsent(regionKey(task.loc), k -> new ConcurrentLinkedQueue<>()).offer(task);
    }

    private void scheduleAll(Map<String, Queue<ChunkTask>> byRegion) {
        for (Queue<ChunkTask> tasks : byRegion.values()) {
            ChunkTask first = tasks.peek();
            if (first != null) scheduleRegion(first.loc, tasks);
        }
    }

    private void scheduleRegion(Location regionLoc, Queue<ChunkTask> tasks) {
        PlatformUtil.runTask(plugin, regionLoc, () -> {
            int processed = 0;
            while (!tasks.isEmpty() && processed < 25) {
                ChunkTask t = tasks.poll();
                if (t == null) break;
                if (!PlatformUtil.isOwnedByCurrentRegion(t.loc)) {
                    tasks.offer(t);
                    break;
                }
                pending.remove(t.key);
                try {
                    t.world.refreshChunk(t.cx, t.cz);
                    processed++;
                } catch (Exception e) {
                    plugin.getLogger().warning("[FPAntiFreeCam] Failed to refresh chunk ("
                            + t.cx + "," + t.cz + "): " + e.getMessage());
                }
            }
            if (!tasks.isEmpty()) {
                PlatformUtil.runTaskLater(plugin, () -> scheduleRegion(regionLoc, tasks), 1L);
            }
        });
    }

    private static String regionKey(Location loc) {
        return loc.getWorld().getName()
                + ":" + (loc.getBlockX() >> 9)
                + ":" + (loc.getBlockZ() >> 9);
    }

    // ── Inner record ──────────────────────────────────────────────────────

    private static class ChunkTask {
        final World    world;
        final int      cx, cz;
        final Location loc;
        final String   key;

        ChunkTask(World world, int cx, int cz, Location loc) {
            this.world = world;
            this.cx    = cx;
            this.cz    = cz;
            this.loc   = loc;
            this.key   = world.getUID() + ":" + cx + ":" + cz;
        }
    }
}
