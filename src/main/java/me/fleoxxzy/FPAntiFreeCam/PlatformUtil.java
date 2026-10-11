package me.fleoxxzy.FPAntiFreeCam;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.lang.reflect.Method;
import java.util.concurrent.CompletableFuture;
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

    private static Method entityGetScheduler;
    private static Method entitySchedulerRun;

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

        try {
            entityGetScheduler = Class.forName("org.bukkit.entity.Entity").getMethod("getScheduler");
            Class<?> entitySchedulerCls = entityGetScheduler.getReturnType();
            entitySchedulerRun = entitySchedulerCls.getMethod("run", Plugin.class, Consumer.class, Runnable.class);
        } catch (Exception ignored) {}
    }

    private PlatformUtil() {}

    // ── Platform detection ────────────────────────────────────────────────

    private static Boolean isPaper = null;

    /** True on Paper and its forks (Purpur, Folia, ...); false on plain Spigot/CraftBukkit. */
    public static boolean isPaper() {
        if (isPaper == null) {
            try {
                Class.forName("io.papermc.paper.event.player.AsyncChatEvent");
                isPaper = true;
            } catch (ClassNotFoundException e) {
                isPaper = false;
            }
        }
        return isPaper;
    }

    // ── Paper API with Spigot fallbacks ───────────────────────────────────
    // Paper-only methods are only called behind isPaper(), so the JVM never
    // links them on Spigot.

    /** The view distance actually used for this player (Paper), or the server default (Spigot). */
    public static int viewDistance(Player player) {
        if (isPaper()) return player.getViewDistance();
        return Bukkit.getViewDistance();
    }

    /** Teleports the player; async on Paper/Folia, synchronous on Spigot. */
    public static CompletableFuture<Boolean> teleport(Player player, Location dest) {
        if (isPaper()) return player.teleportAsync(dest);
        try {
            return CompletableFuture.completedFuture(player.teleport(dest));
        } catch (Throwable t) {
            return CompletableFuture.failedFuture(t);
        }
    }

    /** Sends an action bar message (already colour-translated legacy text). */
    @SuppressWarnings("deprecation")
    public static void sendActionBar(Player player, String legacyMessage) {
        if (isPaper()) {
            player.sendActionBar(legacyMessage);
        } else {
            player.spigot().sendMessage(net.md_5.bungee.api.ChatMessageType.ACTION_BAR,
                    net.md_5.bungee.api.chat.TextComponent.fromLegacyText(legacyMessage));
        }
    }

    /** Server TPS (1m, 5m, 15m), or null where the platform doesn't expose it. */
    public static double[] tps() {
        if (!isPaper()) return null;
        try {
            return Bukkit.getServer().getTPS();
        } catch (Throwable t) {
            return null;
        }
    }

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
        return isPaper() ? "Paper" : "Spigot";
    }

    // ── Task scheduling ───────────────────────────────────────────────────

    /** Run a task on the next tick (global/main thread). */
    public static void runTask(Plugin plugin, Runnable task) {
        // BUGFIX: Bukkit flips isEnabled() to false BEFORE calling onDisable(),
        // so any scheduler call made during shutdown (directly or through code
        // like EntityHider.refreshAll()) throws IllegalPluginAccessException.
        // Running inline instead of scheduling is behaviorally equivalent here
        // since onDisable() itself always runs on the main/global thread.
        if (!plugin.isEnabled()) { task.run(); return; }
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
        if (!plugin.isEnabled()) { task.run(); return; }
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

    /**
     * BUGFIX (Folia): entity-visibility operations (Player#hideEntity/showEntity,
     * getNearbyEntities centered on a player, etc.) act on a specific ENTITY, not
     * a fixed point in space, and Paper's own docs explicitly call using the
     * location-based RegionScheduler for entity operations "entirely inappropriate".
     * The EntityScheduler always runs on whatever region currently owns the entity —
     * it "follows" the entity if it moves or teleports between scheduling and
     * execution — whereas a RegionScheduler task bound to a stale location snapshot
     * can end up executing on the wrong region thread once the entity has moved,
     * which Folia rejects. That rejection was being silently swallowed by the
     * callers here, so entities were never actually hidden with no visible error.
     * Falls back to the plain Bukkit scheduler on non-Folia platforms.
     */
    public static void runForEntity(Plugin plugin, org.bukkit.entity.Entity entity, Runnable task) {
        if (!plugin.isEnabled()) { task.run(); return; }
        if (isFolia() && entityGetScheduler != null && entitySchedulerRun != null) {
            try {
                Object scheduler = entityGetScheduler.invoke(entity);
                if (scheduler != null) {
                    entitySchedulerRun.invoke(scheduler, plugin, (Consumer<Object>) st -> task.run(), (Runnable) null);
                    return;
                }
            } catch (Exception e) {
                plugin.getLogger().warning("[FPAntiFreeCam] Folia EntityScheduler failed, falling back: " + e.getMessage());
            }
        }
        Bukkit.getScheduler().runTask(plugin, task);
    }

    /**
     * Like {@link #runForEntity}, but with a {@code retired} callback that runs if
     * the entity is removed before the task executes. Returns false if the entity
     * was already retired (removed) at scheduling time, in which case neither
     * callback runs. Safe to call from any thread, including Folia's global region.
     * On non-Folia platforms the task is simply run on the main thread.
     */
    public static boolean runForEntity(Plugin plugin, org.bukkit.entity.Entity entity, Runnable task, Runnable retired) {
        if (!plugin.isEnabled()) { task.run(); return true; }
        if (isFolia() && entityGetScheduler != null && entitySchedulerRun != null) {
            try {
                Object scheduler = entityGetScheduler.invoke(entity);
                if (scheduler == null) return false;
                return entitySchedulerRun.invoke(scheduler, plugin, (Consumer<Object>) st -> task.run(), retired) != null;
            } catch (Exception e) {
                plugin.getLogger().warning("[FPAntiFreeCam] Folia EntityScheduler failed: " + e.getMessage());
                return true;
            }
        }
        runTask(plugin, task);
        return true;
    }

    /** Schedule a delayed task (global/main thread). */
    public static BukkitTask runTaskLater(Plugin plugin, Runnable task, long delayTicks) {
        // See runTask() above for why this guard exists.
        if (!plugin.isEnabled()) { task.run(); return NO_OP_TASK; }
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
        // Starting a NEW repeating task while the plugin is disabling makes no
        // sense (it would tick zero times before shutdown finishes) — just skip
        // registration entirely, unlike the run-inline guards above.
        if (!plugin.isEnabled()) return NO_OP_TASK;
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

    /** Shared no-op completed task, returned when scheduling is skipped because the plugin is disabling. */
    private static final BukkitTask NO_OP_TASK = new BukkitTask() {
        @Override public int getTaskId()   { return -1; }
        @Override public Plugin getOwner() { return null; }
        @Override public boolean isSync()  { return true; }
        @Override public boolean isCancelled() { return true; }
        @Override public void cancel() {}
    };

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
