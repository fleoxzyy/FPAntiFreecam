package me.fleoxxzy.FPAntiFreeCam;

import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;

/**
 * Centralised chat/console message helper for FPAntiFreeCam.
 */
public final class ChatUtil {

    private static final String CONSOLE_PREFIX = "[FPAntiFreeCam] ";
    private static final String CHAT_PREFIX    = "&8[&bFPAntiFreeCam&8]&r ";

    private ChatUtil() {}

    // ── Core send ─────────────────────────────────────────────────────────

    public static void send(CommandSender sender, String message) {
        if (sender instanceof ConsoleCommandSender) {
            sender.sendMessage(CONSOLE_PREFIX + ChatColor.stripColor(color(message)));
        } else {
            sender.sendMessage(color(CHAT_PREFIX + message));
        }
    }

    public static void sendInfo(CommandSender sender, String message) {
        send(sender, "&7" + message);
    }

    public static void sendSuccess(CommandSender sender, String message) {
        send(sender, "&a" + message);
    }

    public static void sendWarn(CommandSender sender, String message) {
        send(sender, "&e" + message);
    }

    public static void sendError(CommandSender sender, String message) {
        send(sender, "&c" + message);
    }

    // ── Utility ───────────────────────────────────────────────────────────

    public static String color(String message) {
        return ChatColor.translateAlternateColorCodes('&', message);
    }

    public static String strip(String message) {
        return ChatColor.stripColor(color(message));
    }

    // ── Startup / shutdown banners ────────────────────────────────────────

    public static String[] startupBanner(String version, String platform) {
        return new String[]{
            "",
            "&b&lFPAntiFreeCam &fv" + version,
            "&7Server version: &b" + org.bukkit.Bukkit.getBukkitVersion() + " &7(&b" + platform + "&7)",
            "&7Metrics: &bbStats &7(ID: &b33706&7)",
            "&fFPAntiFreeCam has been enabled successfully!",
            ""
        };
    }

    public static String[] shutdownBanner() {
        return new String[]{
            "",
            "&bFPAntiFreeCam &7shutting down…",
            "&7All FreeCam protections have been removed.",
            ""
        };
    }

    /** Prints a banner array to the console, respecting Folia/Paper console senders. */
    public static void printBanner(String[] lines) {
        for (String line : lines) {
            if (line.isBlank()) {
                org.bukkit.Bukkit.getServer().getConsoleSender().sendMessage("");
            } else {
                org.bukkit.Bukkit.getServer().getConsoleSender().sendMessage(color(line));
            }
        }
    }
}
