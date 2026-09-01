package me.fleoxxzy.FPAntiFreeCam;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Hides non-player entities that are inside the hidden zone (at or below
 * void-y) from players whose FreeCam protection is currently active.
 *
 * Uses the vanilla {@link Player#hideEntity}/{@link Player#showEntity} API
 * which works on all target versions (1.19+).
 *
 * IMPROVEMENTS:
 *  - Changed pairKey() from UUID.nameUUIDFromBytes (collision-prone) to
 *    a plain "playerUUID:entityUUID" String key for correctness and speed.
 *  - getNearby() now adds a Y-range filter so we only fetch entities
 *    that are actually at or below voidY, not the entire view-distance cube.
 *  - showAllEntities() now only iterates the set of known-hidden entities
 *    rather than re-querying the world, cutting work on deactivation.
 *  - cleanupPlayer() now correctly matches the String key format.
 *  - periodicCleanup() removes stale entries for despawned/removed entities.
 */
public final class EntityHider implements Listener {

    private final Plugin        plugin;
    private final FPAntiFreeCam main;

    /**
     * Tracks which player↔entity pairs are currently hidden.
     * Key format: "<playerUUID>:<entityUUID>"
     * Using String avoids the UUID.nameUUIDFromBytes collision risk.
     */
    private final Set<String> hidden = ConcurrentHashMap.newKeySet();

    private boolean enabled = true;

    public EntityHider(FPAntiFreeCam main) {
        this.plugin = main;
        this.main   = main;
        loadSettings();
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    // ── Settings ──────────────────────────────────────────────────────────

    public void loadSettings() {
        enabled = plugin.getConfig().getBoolean("entities.hide-entities", true);
    }

    public boolean isEnabled() { return enabled; }

    // ── Core logic ────────────────────────────────────────────────────────

    /**
     * Called whenever a player's FreeCam-protection state changes.
     * Shows or hides nearby underground entities accordingly.
     */
    public void updateFor(Player player) {
        if (!enabled) return;
        if (main.isProtectionActive(player)) {
            hideUndergroundEntities(player);
        } else {
            showHiddenEntities(player);
        }
    }

    /** Returns true if the given entity is currently hidden from the player. */
    public boolean isHiddenFrom(Player player, Entity entity) {
        return hidden.contains(pairKey(player.getUniqueId(), entity.getUniqueId()));
    }

    @EventHandler
    public void onEntitySpawn(EntitySpawnEvent event) {
        if (!enabled) return;
        Entity entity = event.getEntity();
        if (entity instanceof Player) return;

        int effectiveVoidY = main.getVoidY(entity.getWorld().getName());
        if (entity.getLocation().getY() > effectiveVoidY) return;

        // BUGFIX (Folia): Player#hideEntity() mutates PLAYER-owned tracking state, not
        // entity-owned state. The previous approach scheduled a single task on the
        // region that owns the SPAWNED entity's location, then looped over every
        // online player from there — but those players can be in a completely
        // different region (or world) than the entity that just spawned. Mutating a
        // different region's player from the wrong thread is exactly what Folia's
        // docs call "entirely inappropriate" use of the region scheduler; it throws,
        // and that exception was being silently swallowed in hideFrom() below, so
        // entities were never actually hidden with no visible error. Each player's
        // check is now dispatched on THAT player's own entity scheduler, which is
        // always correct no matter where the entity is.
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!player.getWorld().equals(entity.getWorld())) continue;
            PlatformUtil.runForEntity(plugin, player, () -> {
                if (!entity.isValid() || !player.isOnline()) return;
                if (shouldHideEntityFrom(player, entity)) {
                    hideFrom(player, entity);
                }
            });
        }
    }

    // ── Cleanup ───────────────────────────────────────────────────────────

    public void cleanupPlayer(UUID id) {
        // Correct prefix match for "playerUUID:*"
        String prefix = id.toString() + ":";
        hidden.removeIf(key -> key.startsWith(prefix));
    }

    public void refreshAll() {
        for (Player p : Bukkit.getOnlinePlayers()) updateFor(p);
    }

    /**
     * Removes stale hidden-pair entries for entities that are no longer valid
     * (despawned, removed). Should be called periodically (e.g. every 30 s).
     */
    public void periodicCleanup() {
        hidden.removeIf(key -> {
            String[] parts = key.split(":", 2);
            if (parts.length < 2) return true; // malformed
            try {
                UUID entityId = UUID.fromString(parts[1]);
                Entity e = Bukkit.getEntity(entityId);
                return e == null || !e.isValid();
            } catch (IllegalArgumentException e) {
                return true; // bad UUID, drop it
            }
        });
    }

    public String stats() {
        return "hidden-pairs:" + hidden.size();
    }

    // ── Private helpers ───────────────────────────────────────────────────

    private void hideUndergroundEntities(Player player) {
        // BUGFIX (Folia): this acts on the PLAYER entity (hideEntity() plus a
        // nearby-entity query centered on the player), so it must go through the
        // player's own EntityScheduler rather than a location-based RegionScheduler
        // task. A location snapshot can go stale — the player moves or is mid-
        // teleport before the task runs — and Folia's region-ownership checks then
        // reject the call. That exception was being silently swallowed in
        // hideFrom(), so nothing visibly happened. The entity scheduler always runs
        // on whatever region currently owns the player, however they got there.
        PlatformUtil.runForEntity(plugin, player, () -> {
            if (!player.isOnline()) return;
            for (Entity e : getUndergroundNearby(player)) {
                if (shouldHideEntityFrom(player, e)) hideFrom(player, e);
            }
        });
    }

    /**
     * IMPROVEMENT: Instead of iterating the whole world's nearby entities and
     * then checking Y, we only reveal entities that we know we hid (in the
     * hidden set). This is much cheaper when deactivating protection.
     */
    private void showHiddenEntities(Player player) {
        // BUGFIX (Folia): same reasoning as hideUndergroundEntities() above — must
        // run on the player's own entity scheduler, not a stale-location region task.
        PlatformUtil.runForEntity(plugin, player, () -> {
            if (!player.isOnline()) return;
            String prefix = player.getUniqueId().toString() + ":";
            List<String> toShow = new ArrayList<>();
            for (String key : hidden) {
                if (key.startsWith(prefix)) toShow.add(key);
            }
            for (String key : toShow) {
                try {
                    UUID entityId = UUID.fromString(key.substring(prefix.length()));
                    Entity e = Bukkit.getEntity(entityId);
                    if (e != null && e.isValid()) showTo(player, e);
                    else hidden.remove(key); // entity gone, clean up
                } catch (Exception ignored) {
                    hidden.remove(key);
                }
            }
        });
    }

    private boolean shouldHideEntityFrom(Player player, Entity entity) {
        if (!enabled) return false;
        if (!main.isProtectionActive(player)) return false;
        // Use per-world voidY if available
        int effectiveVoidY = main.getVoidY(player.getWorld().getName());
        return entity.getLocation().getY() <= effectiveVoidY;
    }

    private void hideFrom(Player player, Entity entity) {
        // BUGFIX: Player#hideEntity() internally checks plugin.isEnabled() and
        // refuses to run at all once the plugin is disabled — this is a hard
        // Bukkit restriction, not something reschedulable like the scheduler
        // guard in PlatformUtil. Guard here too as defense-in-depth for any
        // future call path, on top of removing the call site in onDisable().
        if (!plugin.isEnabled()) return;
        String key = pairKey(player.getUniqueId(), entity.getUniqueId());
        if (hidden.contains(key)) return;
        try {
            player.hideEntity(plugin, entity);
            hidden.add(key);
        } catch (Exception e) {
            // BUGFIX: was logged at .fine() (invisible by default), which is how this
            // was failing completely silently. Bumped to .warning() so real failures
            // actually show up in console instead of masquerading as "nothing happened".
            plugin.getLogger().warning("[FPAntiFreeCam] hideEntity failed: " + e.getMessage());
        }
    }

    private void showTo(Player player, Entity entity) {
        if (!plugin.isEnabled()) return;
        String key = pairKey(player.getUniqueId(), entity.getUniqueId());
        if (!hidden.contains(key)) return;
        try {
            player.showEntity(plugin, entity);
            hidden.remove(key);
        } catch (Exception e) {
            plugin.getLogger().warning("[FPAntiFreeCam] showEntity failed: " + e.getMessage());
        }
    }

    /**
     * IMPROVEMENT: Only fetches entities at or below voidY using a bounded
     * bounding box. The old code fetched a full sphere (viewDist * 16 in ALL
     * directions including above) and then Y-filtered, wasting time on
     * thousands of surface/sky entities. Now we use a flat slab covering only
     * the underground zone below the player.
     */
    private List<Entity> getUndergroundNearby(Player player) {
        List<Entity> result = new ArrayList<>();
        try {
            int viewDist;
            try { viewDist = player.getClientViewDistance(); }
            catch (Exception e) { viewDist = Bukkit.getViewDistance(); }

            double  hRadius = viewDist * 16.0;
            int     effectiveVoidY = main.getVoidY(player.getWorld().getName());
            Location loc    = player.getLocation();

            // Clamp minY to the world's minimum height
            double worldMin = loc.getWorld().getMinHeight();
            double minY = worldMin;
            double maxY = effectiveVoidY + 0.5; // just above the voidY floor

            // Use the bounding-box overload to restrict to the underground slab
            result.addAll(loc.getWorld().getNearbyEntities(
                    new org.bukkit.util.BoundingBox(
                            loc.getX() - hRadius, minY, loc.getZ() - hRadius,
                            loc.getX() + hRadius, maxY, loc.getZ() + hRadius
                    )
            ));
            result.removeIf(e -> e instanceof Player);
        } catch (Exception e) {
            plugin.getLogger().warning("[FPAntiFreeCam] getUndergroundNearby failed: " + e.getMessage());
        }
        return result;
    }

    /**
     * IMPROVEMENT: Returns a plain composite String instead of the old
     * UUID.nameUUIDFromBytes approach which has a non-zero (though small)
     * collision probability. Plain String is also faster to compute.
     */
    private static String pairKey(UUID player, UUID entity) {
        return player.toString() + ":" + entity.toString();
    }
}
