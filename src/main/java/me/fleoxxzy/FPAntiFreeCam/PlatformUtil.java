package me.fleoxxzy.FPAntiFreeCam;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.lang.reflect.Method;
import java.util.function.Consumer;

/**
 * Thin abstraction over Bukkit / Folia scheduling and region ownership checks.
 * All methods are safe to call on any platform; they fall back to Bukkit
 * scheduler on Spigot/Paper automatically.
 */
public final class PlatformUtil {

    private static Boolean isFolia                  = null;
    private static Boolean hasGlobalRegionScheduler = null;
    private static Boolean hasRegionScheduler       = null;
    private static Boolean hasAsyncScheduler        = null;

    private static Object globalRegionScheduler;
    private static Method globalRegionRun;
    private static Method globalRegionRunDelayed;
    private static Method globalRegionRunAtFixedRate;

    private static Object regionScheduler;
    private static Method regionRun;

    private static Object asyncScheduler;
    private static Method asyncRunNow;

    private static Method isOwnedByCurrentRegionMethod;

    static {
        try {
            globalRegionScheduler = Bukkit.class.getMethod("getGlobalRegionScheduler").invoke(null);
            if (globalRegionScheduler != null) {
                Class<?> cls = globalRegionScheduler.getClass();
                globalRegionRun = cls.getMethod("run", Plugin.class, Consumer.class);
                globalRegionRunDelayed = cls.getMethod("runDelayed", Plugin.class, Consumer.class, long.class);
                globalRegionRunAtFixedRate = cls.getMethod("runAtFixedRate", Plugin.class, Consumer.class, long.class, long.class);
            }
        } catch (Exception ignored) {}

        try {
            regionScheduler = Bukkit.class.getMethod("getRegionScheduler").invoke(null);
            if (regionScheduler != null) {
                regionRun = regionScheduler.getClass().getMethod("run", Plugin.class, Location.class, Consumer.class);
            }
        } catch (Exception ignored) {}

        try {
            asyncScheduler = Bukkit.class.getMethod("getAsyncScheduler").invoke(null);
            if (asyncScheduler != null) {
                asyncRunNow = asyncScheduler.getClass().getMethod("runNow", Plugin.class, Consumer.class);
            }
        } catch (Exception ignored) {}

        try {
            isOwnedByCurrentRegionMethod = Bukkit.class.getMethod("isOwnedByCurrentRegion", Location.class);
        } catch (Exception ignored) {}
    }

    private PlatformUtil() {}

    // ── Platform detection ────────────────────────────────────────────────

    public static boolean isFolia() {
        if (isFolia == null) {
            try {
                Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
                isFolia = true;
            } catch (ClassNotFoundException e) {
                isFolia = false;
            }
        }
        return isFolia;
    }

    public static boolean hasGlobalRegionScheduler() {
        if (hasGlobalRegionScheduler == null) {
            try {
                Bukkit.class.getMethod("getGlobalRegionScheduler");
                hasGlobalRegionScheduler = true;
            } catch (NoSuchMethodException e) {
                hasGlobalRegionScheduler = false;
            }
        }
        return hasGlobalRegionScheduler;
    }

    public static boolean hasRegionScheduler() {
        if (hasRegionScheduler == null) {
            try {
                Bukkit.class.getMethod("getRegionScheduler");
                hasRegionScheduler = true;
            } catch (NoSuchMethodException e) {
                hasRegionScheduler = false;
            }
        }
        return hasRegionScheduler;
    }

    public static boolean hasAsyncScheduler() {
        if (hasAsyncScheduler == null) {
            try {
                Bukkit.class.getMethod("getAsyncScheduler");
                hasAsyncScheduler = true;
            } catch (NoSuchMethodException e) {
                hasAsyncScheduler = false;
            }
        }
        return hasAsyncScheduler;
    }

    /** Human-readable platform summary for the startup banner. */
    public static String getPlatformName() {
        if (isFolia()) return "Folia";
        // Detect Paper by checking for Paper-specific API
        try {
            Class.forName("io.papermc.paper.event.player.AsyncChatEvent");
            return "Paper";
        } catch (ClassNotFoundException e) {
            return "Spigot";
        }
    }

    // ── Task scheduling ───────────────────────────────────────────────────

    /** Run a task on the next tick (global/main thread). */
    public static void runTask(Plugin plugin, Runnable task) {
        if (isFolia() && hasGlobalRegionScheduler() && globalRegionScheduler != null && globalRegionRun != null) {
            try {
                globalRegionRun.invoke(globalRegionScheduler, plugin, (Consumer<Object>) st -> task.run());
                return;
            } catch (Exception e) {
                plugin.getLogger().warning("[FPAntiFreeCam] Folia GlobalRegionScheduler failed, falling back: " + e.getMessage());
            }
        }
        Bukkit.getScheduler().runTask(plugin, task);
    }

    /** Run a task at a specific location (Folia: on the correct region thread). */
    public static void runTask(Plugin plugin, Location location, Runnable task) {
        if (isFolia() && hasRegionScheduler() && regionScheduler != null && regionRun != null) {
            try {
                regionRun.invoke(regionScheduler, plugin, location, (Consumer<Object>) st -> task.run());
                return;
            } catch (Exception e) {
                plugin.getLogger().warning("[FPAntiFreeCam] Folia RegionScheduler failed, falling back: " + e.getMessage());
            }
        }
        Bukkit.getScheduler().runTask(plugin, task);
    }

    /** Schedule a delayed task (global/main thread). */
    public static BukkitTask runTaskLater(Plugin plugin, Runnable task, long delayTicks) {
        if (isFolia() && hasGlobalRegionScheduler() && globalRegionScheduler != null && globalRegionRunDelayed != null) {
            try {
                Object foliaTask = globalRegionRunDelayed.invoke(globalRegionScheduler, plugin,
                        (Consumer<Object>) st -> task.run(), delayTicks);
                return new FoliaTaskWrapper(foliaTask);
            } catch (Exception e) {
                plugin.getLogger().warning("[FPAntiFreeCam] Folia delayed task failed, falling back: " + e.getMessage());
            }
        }
        return Bukkit.getScheduler().runTaskLater(plugin, task, delayTicks);
    }

    /** Schedule a repeating task (global/main thread). */
    public static BukkitTask runTaskTimer(Plugin plugin, Runnable task, long delayTicks, long periodTicks) {
        if (isFolia() && hasGlobalRegionScheduler() && globalRegionScheduler != null && globalRegionRunAtFixedRate != null) {
            try {
                Object foliaTask = globalRegionRunAtFixedRate.invoke(globalRegionScheduler, plugin,
                        (Consumer<Object>) st -> task.run(), delayTicks < 1 ? 1L : delayTicks, periodTicks < 1 ? 1L : periodTicks);
                return new FoliaTaskWrapper(foliaTask);
            } catch (Exception e) {
                plugin.getLogger().warning("[FPAntiFreeCam] Folia task timer failed, falling back: " + e.getMessage());
            }
        }
        return Bukkit.getScheduler().runTaskTimer(plugin, task, delayTicks, periodTicks);
    }

    /**
     * Run a task off the main/region threads (network I/O, file I/O, etc.).
     *
     * BUGFIX (Folia): Folia's legacy BukkitScheduler rejects EVERY method,
     * including the async ones — Bukkit.getScheduler().runTaskAsynchronously()
     * throws UnsupportedOperationException just like the sync variants do.
     * Folia (and modern Paper) expose a dedicated AsyncScheduler for this,
     * which is what this method uses when available.
     */
    public static void runTaskAsync(Plugin plugin, Runnable task) {
        if (hasAsyncScheduler() && asyncScheduler != null && asyncRunNow != null) {
            try {
                asyncRunNow.invoke(asyncScheduler, plugin, (Consumer<Object>) st -> task.run());
                return;
            } catch (Exception e) {
                plugin.getLogger().warning("[FPAntiFreeCam] AsyncScheduler failed, falling back: " + e.getMessage());
            }
        }
        // Only safe as a fallback on non-Folia platforms where the legacy
        // scheduler's async methods still function normally.
        Bukkit.getScheduler().runTaskAsynchronously(plugin, task);
    }

    /**
     * Returns true if the current thread owns the region for the given location.
     * Always returns true on non-Folia servers (single-threaded).
     */
    public static boolean isOwnedByCurrentRegion(Location location) {
        if (isFolia() && isOwnedByCurrentRegionMethod != null) {
            try {
                return (Boolean) isOwnedByCurrentRegionMethod.invoke(null, location);
            } catch (Exception ignored) {}
        }
        return true;
    }

    // ── Inner: BukkitTask wrapper for Folia ScheduledTask ────────────────

    private static class FoliaTaskWrapper implements BukkitTask {
        private final Object foliaTask;

        FoliaTaskWrapper(Object foliaTask) {
            this.foliaTask = foliaTask;
        }

        @Override public int getTaskId()  { return -1; }
        @Override public Plugin getOwner() { return null; }
        @Override public boolean isSync()  { return true; }

        @Override
        public boolean isCancelled() {
            try {
                return (Boolean) foliaTask.getClass().getMethod("isCancelled").invoke(foliaTask);
            } catch (Exception e) { return false; }
        }

        @Override
        public void cancel() {
            try {
                foliaTask.getClass().getMethod("cancel").invoke(foliaTask);
            } catch (Exception ignored) {}
        }
    }
}
