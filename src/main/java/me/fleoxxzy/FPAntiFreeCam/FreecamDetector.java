package me.fleoxxzy.FPAntiFreeCam;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.inventory.AnvilInventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * FreecamDetector – translation-key based Freecam-mod detection.
 *
 * <p>Technique: many Freecam-family client mods ship their own lang.json
 * with mod-specific translation keys (e.g. "key.freecam.freecam"). A
 * vanilla client has no such key registered and renders the raw key
 * string as a fallback; a client with the mod installed resolves it to
 * the mod's real translated text.
 *
 * <p>To turn that purely client-side rendering difference into a signal
 * the server can actually observe, this class briefly opens a hidden
 * anvil GUI whose first slot holds an item with a
 * {@link Component#translatable(String)} display name using one of the
 * configured keys. When the anvil's rename text field is populated and
 * echoed back to the server (via the vanilla "rename" packet, surfaced
 * to plugins as {@link PrepareAnvilEvent}), the plugin compares what the
 * client actually sent against the known expected translation for that
 * key. A match strongly suggests the mod (and therefore the translation
 * key) is present on the client.
 *
 * <p><b>Caveat:</b> this depends on the client actually sending the
 * rename-field content back. Some client/mod builds only send it once
 * the field is edited, not the instant the container opens. Treat this
 * as a strong heuristic to feed into an anticheat pipeline, not
 * infallible proof — verify against real Freecam-mod clients before
 * wiring it to an irreversible punishment.
 *
 * <p><b>REMOVED: periodic re-probing.</b> This used to also re-probe every
 * online player every {@code probe-interval-seconds} on top of the
 * join-time probe. That was removed entirely (not just disabled) — opening
 * the probe GUI mid-session hijacks left-click input away from attacking,
 * which was disruptive during PVP. Detection now happens ONLY once at
 * join, plus on-demand via /fpac freecamtest. If a player installs Freecam
 * mid-session after joining without it, they won't be probed again until
 * their next join — that tradeoff is intentional.
 */
public final class FreecamDetector implements Listener {

    /** One configured (translationKey -> expectedText) probe pair. */
    private record ProbeKey(String key, String expected) {}

    /** Tracks an in-flight probe for a single player. */
    private static final class PendingProbe {
        final String translationKey;
        final String expectedText;
        final long   openedAt;

        PendingProbe(String translationKey, String expectedText) {
            this.translationKey = translationKey;
            this.expectedText   = expectedText;
            this.openedAt       = System.currentTimeMillis();
        }
    }

    private final FPAntiFreeCam plugin;

    private boolean enabled           = true;
    private boolean debug             = false;
    private int     probeTimeoutTicks = 5;
    private boolean alertsEnabled     = true;
    private String  alertMessage      = "&8[&cFreecam Alert&8] &e%player% &7was flagged for &cFreecam &7(&8%key%&7)";

    private final List<ProbeKey>   probeKeys        = new ArrayList<>();
    private final List<String>     detectedCommands = new ArrayList<>();

    private final Map<UUID, PendingProbe> pendingProbes = new ConcurrentHashMap<>();

    public FreecamDetector(FPAntiFreeCam plugin) {
        this.plugin = plugin;
        loadSettings();
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    // ═════════════════════════════════════════════════════════════════════
    //  Config
    // ═════════════════════════════════════════════════════════════════════

    public void loadSettings() {
        var cfg = plugin.getConfig();
        String base = "freecam-detection.";

        enabled           = cfg.getBoolean(base + "enabled", true);
        debug             = cfg.getBoolean(base + "debug", false);
        probeTimeoutTicks = Math.max(1, cfg.getInt(base + "probe-timeout-ticks", 5));
        alertsEnabled     = cfg.getBoolean(base + "alerts-enabled", true);
        alertMessage      = cfg.getString(base + "alert-message", alertMessage);

        probeKeys.clear();
        List<Map<?, ?>> rawKeys = cfg.getMapList(base + "translation-keys");
        for (Map<?, ?> entry : rawKeys) {
            Object k = entry.get("key");
            Object e = entry.get("expected");
            if (k == null || e == null) continue;
            probeKeys.add(new ProbeKey(k.toString(), e.toString()));
        }

        detectedCommands.clear();
        detectedCommands.addAll(cfg.getStringList(base + "detected-commands"));

        if (debug) {
            plugin.getLogger().info("[FPAntiFreeCam] FreecamDetector loaded: enabled=" + enabled
                    + " keys=" + probeKeys.size() + " (join-only probing)");
        }
    }

    public boolean isEnabled() { return enabled; }

    // ═════════════════════════════════════════════════════════════════════
    //  Lifecycle
    // ═════════════════════════════════════════════════════════════════════

    public void cleanupPlayer(UUID id) {
        pendingProbes.remove(id);
    }

    public void shutdown() {
        pendingProbes.clear();
    }

    // ═════════════════════════════════════════════════════════════════════
    //  Probing
    // ═════════════════════════════════════════════════════════════════════

    /**
     * Schedules a single probe shortly after a player joins. This is now the
     * ONLY automatic trigger for a probe — there is no periodic re-check.
     * Skips quietly if the player isn't eligible (bypassed, Bedrock, etc.)
     * by the time it fires. Not gated by worlds.list — freecam detection
     * runs everywhere, independent of which worlds have void-hiding enabled.
     */
    public void scheduleJoinProbe(Player player) {
        if (!enabled || probeKeys.isEmpty()) return;
        UUID id = player.getUniqueId();
        // 60 ticks (3s) gives the client time to finish loading in before we
        // pop a GUI open on them.
        PlatformUtil.runTaskLater(plugin, () -> {
            if (!player.isOnline()) return;
            if (plugin.isBedrockPlayer(player)) {
                if (debug) plugin.getLogger().info("[FPAntiFreeCam] Skipped join-probe for "
                        + player.getName() + ": Bedrock player (freecam mods don't exist on Bedrock).");
                return;
            }
            if (plugin.hasBypass(player)) {
                if (debug) plugin.getLogger().info("[FPAntiFreeCam] Skipped join-probe for "
                        + player.getName() + ": player has bypass.");
                return;
            }
            if (pendingProbes.containsKey(id)) return;
            probe(player);
        }, 60L);
    }

    /**
     * Fires a probe immediately. Used by /fpac freecamtest for manual
     * testing against a real client. Still respects "no other GUI currently
     * open" and "no probe already pending" to avoid clobbering an
     * in-progress check.
     *
     * @return a short human-readable reason if the probe could NOT be sent,
     *         or {@code null} if a probe was successfully dispatched.
     */
    public String manualProbe(Player player) {
        if (!enabled) return "freecam-detection.enabled is false in config.yml";
        if (probeKeys.isEmpty()) return "no translation-keys configured";
        if (plugin.isBedrockPlayer(player)) return "player is on Bedrock — freecam mods don't exist on Bedrock";
        if (pendingProbes.containsKey(player.getUniqueId())) return "a probe is already pending for this player";
        if (player.getOpenInventory().getType() != InventoryType.CRAFTING) return "player already has a GUI open";

        probe(player);
        return null;
    }

    private void probe(Player player) {
        ProbeKey chosen = probeKeys.get(ThreadLocalRandom.current().nextInt(probeKeys.size()));

        PlatformUtil.runForEntity(plugin, player, () -> {
            if (!player.isOnline()) return;
            try {
                InventoryView view = player.openAnvil(null, true);
                if (view == null) return;
                if (!(view.getTopInventory() instanceof AnvilInventory anvil)) return;

                ItemStack probeItem = new ItemStack(org.bukkit.Material.PAPER);
                ItemMeta   meta      = probeItem.getItemMeta();
                if (meta != null) {
                    meta.displayName(Component.translatable(chosen.key()));
                    probeItem.setItemMeta(meta);
                }
                anvil.setItem(0, probeItem);

                pendingProbes.put(player.getUniqueId(), new PendingProbe(chosen.key(), chosen.expected()));
                if (debug) {
                    plugin.getLogger().info("[FPAntiFreeCam] Probing " + player.getName()
                            + " with key='" + chosen.key() + "'");
                }

                PlatformUtil.runTaskLater(plugin, () -> {
                    pendingProbes.remove(player.getUniqueId());
                    if (!player.isOnline()) return;
                    // Only close the probe GUI itself on the entity's region thread.
                    // Clear the slots FIRST — otherwise vanilla returns the unconsumed
                    // probe item to the player's inventory on close.
                    PlatformUtil.runForEntity(plugin, player, () -> {
                        if (!player.isOnline()) return;
                        if (player.getOpenInventory().getTopInventory() instanceof AnvilInventory openAnvil) {
                            openAnvil.clear();
                            player.closeInventory();
                        }
                    });
                }, probeTimeoutTicks);
            } catch (Exception e) {
                plugin.dbg("FreecamDetector probe failed for " + player.getName() + ": " + e.getMessage());
                pendingProbes.remove(player.getUniqueId());
            }
        });
    }

    // ═════════════════════════════════════════════════════════════════════
    //  Echo capture
    // ═════════════════════════════════════════════════════════════════════

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onPrepareAnvil(PrepareAnvilEvent event) {
        if (!enabled) return;
        if (!(event.getView().getPlayer() instanceof Player player)) return;

        PendingProbe pending = pendingProbes.get(player.getUniqueId());
        if (pending == null) return;

        String renameText = event.getInventory().getRenameText();
        if (renameText == null || renameText.isEmpty()) return;

        if (debug) {
            plugin.getLogger().info("[FPAntiFreeCam] Anvil echo from " + player.getName()
                    + " key='" + pending.translationKey + "' text='" + renameText + "'");
        }

        if (renameText.trim().equalsIgnoreCase(pending.expectedText.trim())) {
            handleDetection(player, pending.translationKey);
        }
    }

    // NOTE: pending-probe cleanup is intentionally left to the scheduled
    // timeout task in probe() rather than InventoryCloseEvent, so a fast
    // close right after a genuine echo can't race the detection check.

    // ═════════════════════════════════════════════════════════════════════
    //  Punishment
    // ═════════════════════════════════════════════════════════════════════

    private void handleDetection(Player player, String matchedKey) {
        pendingProbes.remove(player.getUniqueId());

        plugin.getLogger().warning("[FPAntiFreeCam] Freecam detected: " + player.getName()
                + " (translation key '" + matchedKey + "' resolved client-side)");

        PlatformUtil.runForEntity(plugin, player, () -> {
            if (!player.isOnline()) return;
            if (player.getOpenInventory().getTopInventory() instanceof AnvilInventory openAnvil) {
                openAnvil.clear();
                player.closeInventory();
            }
        });

        PlatformUtil.runTask(plugin, () -> {
            for (String rawCmd : detectedCommands) {
                String cmd = rawCmd
                        .replace("%player%", player.getName())
                        .replace("%uuid%", player.getUniqueId().toString())
                        .replace("%key%", matchedKey);
                try {
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), ChatUtil.color(cmd));
                } catch (Exception e) {
                    plugin.getLogger().warning("[FPAntiFreeCam] Failed to run detected-command '"
                            + cmd + "': " + e.getMessage());
                }
            }

            if (alertsEnabled) {
                String alert = ChatUtil.color(alertMessage
                        .replace("%player%", player.getName())
                        .replace("%uuid%", player.getUniqueId().toString())
                        .replace("%key%", matchedKey));
                for (Player staff : Bukkit.getOnlinePlayers()) {
                    if (staff.hasPermission("fpantifreecam.alerts")) {
                        staff.sendMessage(alert);
                    }
                }
                Bukkit.getConsoleSender().sendMessage(alert);
            }
        });
    }
}
