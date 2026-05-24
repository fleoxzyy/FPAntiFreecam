package me.fleoxxzy.FPAntiFreeCam;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Checks the Modrinth API asynchronously on startup for a newer version.
 * Logs to console and notifies online admins (fpantifreecam.admin) on join.
 */
public final class UpdateChecker implements Listener {

    private static final String MODRINTH_API = "https://api.modrinth.com/v2/project/fp-antifreecam/version";
    private static final String MODRINTH_URL = "https://modrinth.com/plugin/fp-antifreecam";
    private static final String GITHUB_URL   = "https://github.com/Fleoxzyy/FPAntiFreeCam";

    private final FPAntiFreeCam plugin;

    private String  latestVersion = null;
    private boolean outdated      = false;

    public UpdateChecker(FPAntiFreeCam plugin) {
        this.plugin = plugin;
    }

    // ── Public API ────────────────────────────────────────────────────────

    /** Runs the version check asynchronously. Safe to call multiple times (e.g. on reload). */
    public void check() {
        outdated      = false;
        latestVersion = null;

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(MODRINTH_API).openConnection();
                conn.setRequestMethod("GET");
                // Modrinth requires a descriptive User-Agent
                conn.setRequestProperty("User-Agent",
                        "FPAntiFreeCam/" + plugin.getDescription().getVersion()
                        + " (modrinth:fp-antifreecam)");
                conn.setConnectTimeout(5_000);
                conn.setReadTimeout(5_000);

                int status = conn.getResponseCode();
                if (status != 200) {
                    plugin.getLogger().warning("[FPAntiFreeCam] Update check failed – HTTP " + status);
                    return;
                }

                StringBuilder sb = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(conn.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) sb.append(line);
                }

                // Modrinth returns a JSON array; the first element is the latest version.
                // We do a minimal parse to avoid pulling in a JSON library.
                String json = sb.toString();
                int idx = json.indexOf("\"version_number\"");
                if (idx == -1) {
                    plugin.getLogger().warning("[FPAntiFreeCam] Update check – unexpected API response.");
                    return;
                }
                int start = json.indexOf('"', idx + 17) + 1;
                int end   = json.indexOf('"', start);
                latestVersion = json.substring(start, end);

                String current = plugin.getDescription().getVersion();
                outdated = !normalise(current).equals(normalise(latestVersion));

                if (outdated) {
                    plugin.getLogger().warning("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
                    plugin.getLogger().warning("  FPAntiFreeCam — Update Available!");
                    plugin.getLogger().warning("  Current  : v" + current);
                    plugin.getLogger().warning("  Latest   : v" + latestVersion);
                    plugin.getLogger().warning("  Modrinth : " + MODRINTH_URL);
                    plugin.getLogger().warning("  GitHub   : " + GITHUB_URL);
                    plugin.getLogger().warning("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");

                    // Notify any admins already online (e.g. after /fpac reload)
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        for (Player p : Bukkit.getOnlinePlayers()) {
                            if (p.hasPermission("fpantifreecam.admin")) notifyPlayer(p);
                        }
                    });
                } else {
                    plugin.getLogger().info("[FPAntiFreeCam] Plugin is up to date (v" + current + ").");
                }

            } catch (Exception e) {
                plugin.getLogger().warning("[FPAntiFreeCam] Could not check for updates: " + e.getMessage());
            }
        });
    }

    // ── Event: notify admins on join ──────────────────────────────────────

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        if (!outdated) return;
        Player player = event.getPlayer();
        if (!player.hasPermission("fpantifreecam.admin")) return;

        // Small delay so the notification appears after the join splash
        PlatformUtil.runTaskLater(plugin, () -> {
            if (player.isOnline()) notifyPlayer(player);
        }, 40L);
    }

    // ── Private helpers ───────────────────────────────────────────────────

    private void notifyPlayer(Player player) {
        String current = plugin.getDescription().getVersion();
        player.sendMessage(ChatUtil.color("&8&m──────────────────────────────────────────────"));
        player.sendMessage(ChatUtil.color(" &e&lFPAntiFreeCam &r&e— Update Available!"));
        player.sendMessage(ChatUtil.color(" &7Current  &8» &cv" + current));
        player.sendMessage(ChatUtil.color(" &7Latest   &8» &av" + latestVersion));
        player.sendMessage(ChatUtil.color(" &7Modrinth &8» &b" + MODRINTH_URL));
        player.sendMessage(ChatUtil.color(" &7GitHub   &8» &b" + GITHUB_URL));
        player.sendMessage(ChatUtil.color("&8&m──────────────────────────────────────────────"));
    }

    /** Strips a leading 'v' so "v1.2.0" and "1.2.0" compare equal. */
    private static String normalise(String version) {
        return version == null ? "" : version.trim().replaceAll("(?i)^v", "");
    }
}
