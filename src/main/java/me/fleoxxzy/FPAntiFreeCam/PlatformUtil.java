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
        if (isFolia() && hasGlobalRegionScheduler()) {
            try {
                Object scheduler = Bukkit.class.getMethod("getGlobalRegionScheduler").invoke(null);
                Method run = scheduler.getClass().getMethod("run", Plugin.class, Consumer.class);
                run.invoke(scheduler, plugin, (Consumer<Object>) st -> task.run());
                return;
            } catch (Exception e) {
                plugin.getLogger().warning("[FPAntiFreeCam] Folia GlobalRegionScheduler failed, falling back: " + e.getMessage());
            }
        }
        Bukkit.getScheduler().runTask(plugin, task);
    }

    /** Run a task at a specific location (Folia: on the correct region thread). */
    public static void runTask(Plugin plugin, Location location, Runnable task) {
        if (isFolia() && hasRegionScheduler()) {
            try {
                Object scheduler = Bukkit.class.getMethod("getRegionScheduler").invoke(null);
                Method run = scheduler.getClass().getMethod("run", Plugin.class, Location.class, Consumer.class);
                run.invoke(scheduler, plugin, location, (Consumer<Object>) st -> task.run());
                return;
            } catch (Exception e) {
                plugin.getLogger().warning("[FPAntiFreeCam] Folia RegionScheduler failed, falling back: " + e.getMessage());
            }
        }
        Bukkit.getScheduler().runTask(plugin, task);
    }

    /** Schedule a delayed task (global/main thread). */
    public static BukkitTask runTaskLater(Plugin plugin, Runnable task, long delayTicks) {
        if (isFolia() && hasGlobalRegionScheduler()) {
            try {
                Object scheduler = Bukkit.class.getMethod("getGlobalRegionScheduler").invoke(null);
                Method runDelayed = scheduler.getClass()
                        .getMethod("runDelayed", Plugin.class, Consumer.class, long.class);
                Object foliaTask = runDelayed.invoke(scheduler, plugin,
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
        if (isFolia() && hasGlobalRegionScheduler()) {
            try {
                Object scheduler = Bukkit.class.getMethod("getGlobalRegionScheduler").invoke(null);
                Method runAtFixedRate = scheduler.getClass()
                        .getMethod("runAtFixedRate", Plugin.class, Consumer.class, long.class, long.class);
                Object foliaTask = runAtFixedRate.invoke(scheduler, plugin,
                        (Consumer<Object>) st -> task.run(), delayTicks < 1 ? 1L : delayTicks, periodTicks < 1 ? 1L : periodTicks);
                return new FoliaTaskWrapper(foliaTask);
            } catch (Exception e) {
                plugin.getLogger().warning("[FPAntiFreeCam] Folia task timer failed, falling back: " + e.getMessage());
            }
        }
        return Bukkit.getScheduler().runTaskTimer(plugin, task, delayTicks, periodTicks);
    }

    /**
     * Returns true if the current thread owns the region for the given location.
     * Always returns true on non-Folia servers (single-threaded).
     */
    public static boolean isOwnedByCurrentRegion(Location location) {
        if (isFolia()) {
            try {
                Method m = Bukkit.class.getMethod("isOwnedByCurrentRegion", Location.class);
                return (Boolean) m.invoke(null, location);
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
