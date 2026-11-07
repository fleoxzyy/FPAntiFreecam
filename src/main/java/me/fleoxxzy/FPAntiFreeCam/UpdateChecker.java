package me.fleoxxzy.FPAntiFreeCam;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Checks the Modrinth API asynchronously on startup for a newer version.
 * Logs to console and notifies online admins (fpantifreecam.admin) on join.
 */
public final class UpdateChecker implements Listener {

    private static final String MODRINTH_API = "https://api.modrinth.com/v2/project/fpantifreecam/version";
    private static final String MODRINTH_URL = "https://modrinth.com/plugin/fpantifreecam";
    private static final String GITHUB_URL   = "https://github.com/fleoxzyy/FPAntiFreeCam";

    private final FPAntiFreeCam plugin;

    private String  latestVersion = null;
    private String  latestType    = null;
    private boolean outdated      = false;

    public UpdateChecker(FPAntiFreeCam plugin) {
        this.plugin = plugin;
    }

    // ── Public API ────────────────────────────────────────────────────────

    /** Runs the version check asynchronously. Safe to call multiple times (e.g. on reload). */
    public void check() {
        outdated      = false;
        latestVersion = null;
        latestType    = null;

        // BUGFIX (Folia): was Bukkit.getScheduler().runTaskAsynchronously(), which Folia's
        // legacy scheduler rejects with UnsupportedOperationException just like the sync
        // methods do (confirmed crash on Folia 26.1.2 at plugin enable). Routed through
        // PlatformUtil.runTaskAsync(), which uses Folia's dedicated AsyncScheduler.
        PlatformUtil.runTaskAsync(plugin, () -> {
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(MODRINTH_API).openConnection();
                conn.setRequestMethod("GET");
                // Modrinth requires a descriptive User-Agent
                conn.setRequestProperty("User-Agent",
                        "FPAntiFreeCam/" + plugin.getDescription().getVersion()
                        + " (modrinth:fpantifreecam)");
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

                // NEW (1.5.4): alpha builds count as updates too. Every version
                // (release and alpha) is sorted newest first by publish date.
                JsonArray arr = JsonParser.parseString(sb.toString()).getAsJsonArray();
                List<String[]> entries = new ArrayList<>();
                for (JsonElement el : arr) {
                    if (!el.isJsonObject()) continue;
                    JsonObject o = el.getAsJsonObject();
                    if (!o.has("version_number")) continue;
                    entries.add(new String[] {
                            o.get("version_number").getAsString(),
                            o.has("version_type") ? o.get("version_type").getAsString() : "release",
                            o.has("date_published") ? o.get("date_published").getAsString() : ""
                    });
                }
                if (entries.isEmpty()) {
                    plugin.getLogger().warning("[FPAntiFreeCam] Update check – unexpected API response.");
                    return;
                }
                // ISO-8601 UTC timestamps sort correctly as text; the sort is stable.
                entries.sort((a, b) -> b[2].compareTo(a[2]));
                List<String> versions = new ArrayList<>(entries.size());
                for (String[] e : entries) versions.add(e[0]);
                latestVersion = entries.get(0)[0];
                latestType = entries.get(0)[1];

                String current = plugin.getDescription().getVersion();
                outdated = isOutdated(current, versions);

                if (outdated) {
                    plugin.getLogger().warning("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
                    plugin.getLogger().warning("  FPAntiFreeCam — Update Available!");
                    plugin.getLogger().warning("  Current  : v" + current);
                    plugin.getLogger().warning("  Latest   : v" + latestVersion + typeLabel(latestType));
                    plugin.getLogger().warning("  Modrinth : " + MODRINTH_URL);
                    plugin.getLogger().warning("  GitHub   : " + GITHUB_URL);
                    plugin.getLogger().warning("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");

                    // Notify any admins already online (e.g. after /fpac reload)
                    // BUGFIX (Folia): was calling the raw Bukkit.getScheduler().runTask(),
                    // which Folia's legacy synchronous scheduler rejects with
                    // UnsupportedOperationException (same failure class as the teleport
                    // and entity-hider fixes). Routed through PlatformUtil instead.
                    PlatformUtil.runTask(plugin, () -> {
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
        player.sendMessage(ChatUtil.color(" &7Latest   &8» &av" + latestVersion + typeLabel(latestType)));
        player.sendMessage(ChatUtil.color(" &7Modrinth &8» &b" + MODRINTH_URL));
        player.sendMessage(ChatUtil.color(" &7GitHub   &8» &b" + GITHUB_URL));
        player.sendMessage(ChatUtil.color("&8&m──────────────────────────────────────────────"));
    }

    private static String typeLabel(String type) {
        return type == null || type.equalsIgnoreCase("release") ? "" : " (" + type.toLowerCase(Locale.ROOT) + ")";
    }

    /**
     * NEW (1.5.4): this build is outdated if anything (release or alpha) was
     * published after it. A build Modrinth doesn't list (local / unpublished)
     * is only outdated by a higher version, or by the release of its own
     * version (that release replaces its alphas).
     */
    static boolean isOutdated(String current, List<String> newestFirst) {
        String cur = normalise(current);
        for (int i = 0; i < newestFirst.size(); i++) {
            if (normalise(newestFirst.get(i)).equalsIgnoreCase(cur)) return i > 0;
        }
        String latest = normalise(newestFirst.get(0));
        int cmp = compareBase(base(latest), base(cur));
        if (cmp != 0) return cmp > 0;
        return latest.equals(base(latest)) && !cur.equals(base(cur));
    }

    /** "1.5.4-d33ade3" -> "1.5.4". */
    private static String base(String version) {
        int dash = version.indexOf('-');
        return dash < 0 ? version : version.substring(0, dash);
    }

    /**
     * Versions here read like decimals: 1.5.31 sits between 1.5.3 and 1.5.4.
     * So the first part is compared as a number, the rest digit by digit.
     */
    private static int compareBase(String a, String b) {
        String[] pa = a.split("\\."), pb = b.split("\\.");
        for (int i = 0; i < Math.max(pa.length, pb.length); i++) {
            String x = i < pa.length ? digits(pa[i]) : "0", y = i < pb.length ? digits(pb[i]) : "0";
            int c;
            if (i == 0) {
                c = Integer.compare(num(x), num(y));
            } else {
                int len = Math.max(x.length(), y.length());
                c = pad(x, len).compareTo(pad(y, len));
            }
            if (c != 0) return c;
        }
        return 0;
    }

    private static String digits(String s) {
        String d = s.replaceAll("\\D.*$", "");
        return d.isEmpty() ? "0" : d;
    }

    private static String pad(String s, int len) {
        StringBuilder sb = new StringBuilder(s);
        while (sb.length() < len) sb.append('0');
        return sb.toString();
    }

    private static int num(String s) {
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return 0; }
    }

    /** Strips a leading 'v' so "v1.2.0" and "1.2.0" compare equal. */
    private static String normalise(String version) {
        return version == null ? "" : version.trim().replaceAll("(?i)^v", "");
    }
}
