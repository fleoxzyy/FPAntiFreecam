package me.fleoxxzy.FPAntiFreeCam;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListener;
import com.github.retrooper.packetevents.event.PacketListenerCommon;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.manager.player.PlayerManager;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.protocol.nbt.NBTCompound;
import com.github.retrooper.packetevents.protocol.nbt.NBTList;
import com.github.retrooper.packetevents.protocol.nbt.NBTString;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.protocol.world.blockentity.BlockEntityTypes;
import com.github.retrooper.packetevents.util.Vector3i;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientUpdateSign;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerBlockEntityData;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerCloseWindow;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerOpenSignEditor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * FreecamDetector – translation-key based Freecam-mod detection.
 *
 * <p>Technique: the Freecam mod ships its own lang file with mod-specific
 * translation keys (e.g. "key.freecam.toggle"). A vanilla client has no such
 * key and keeps the raw key string; a client with the mod resolves it to the
 * mod's real text.
 *
 * <p>NEW: sign editor instead of the anvil. A fake sign holding the mod's
 * keys is sent at the bottom of the world under the player, its
 * editor is opened and closed in the same tick (nothing is drawn), and the
 * client answers with the sign's text as it resolved it. The answer is read
 * and cancelled on Netty, and the real block is sent back. Unlike the anvil,
 * the client always sends this text. The sign is sent through the server's
 * own API where it can be (so ViaVersion converts it for older and newer
 * clients) and through PacketEvents otherwise, so it works on Spigot too.
 *
 * <p>Probing only happens once, shortly after join. Client mods that block
 * this trick just return the raw key and are not flagged.
 */
public final class FreecamDetector implements PacketListener {

    /** Tracks an in-flight probe for a single player. */
    private static final class PendingProbe {
        final String[]   keys;
        final Player     player;
        final Location   location;
        final BlockData  realBlock;
        final Vector3i   position;

        PendingProbe(String[] keys, Player player, Location location, BlockData realBlock, Vector3i position) {
            this.keys      = keys;
            this.player    = player;
            this.location  = location;
            this.realBlock = realBlock;
            this.position  = position;
        }
    }

    private final FPAntiFreeCam plugin;

    private boolean enabled           = true;
    private boolean debug             = false;
    private int     probeTimeoutTicks = 60;
    private boolean alertsEnabled     = true;
    private String  alertMessage      = "&8[&cFreecam Alert&8] &e%player% &7was flagged for &cFreecam &7(&8%key%&7)";

    /**
     * Translation keys from the Freecam mod's lang files. One sign has 4
     * lines, one key per line. Any line that comes back as something other
     * than its raw key was translated by the client, so the mod is there.
     * That also catches clients in other languages, where the text isn't the
     * English one.
     *
     * <p>IMPROVEMENT: the keys changed in Freecam 1.4, so every version has at
     * least two keys here:
     * toggle = all versions, text.autoconfig title = 1.3.x and older,
     * config.gui.title and config.open = 1.4 and newer.
     */
    private static final String[] PROBE_KEYS = {
            "key.freecam.toggle",
            "text.autoconfig.freecam.title",
            "freecam.config.gui.title",
            "key.freecam.config.open"};
    private final List<String>     detectedCommands = new ArrayList<>();

    private final Map<UUID, PendingProbe> pendingProbes = new ConcurrentHashMap<>();

    private final PacketListenerCommon packetListener;

    public FreecamDetector(FPAntiFreeCam plugin) {
        this.plugin = plugin;
        loadSettings();
        packetListener = PacketEvents.getAPI().getEventManager().registerListener(this, PacketListenerPriority.NORMAL);
    }

    // ═════════════════════════════════════════════════════════════════════
    //  Config
    // ═════════════════════════════════════════════════════════════════════

    public void loadSettings() {
        var cfg = plugin.getConfig();
        String base = "freecam-detection.";

        enabled           = cfg.getBoolean(base + "enabled", true);
        debug             = cfg.getBoolean(base + "debug", false);
        probeTimeoutTicks = Math.max(1, cfg.getInt(base + "probe-timeout-ticks", 60));
        alertsEnabled     = cfg.getBoolean(base + "alerts-enabled", true);
        alertMessage      = cfg.getString(base + "alert-message", alertMessage);

        detectedCommands.clear();
        detectedCommands.addAll(cfg.getStringList(base + "detected-commands"));

        if (debug) {
            plugin.getLogger().info("[FPAntiFreeCam] FreecamDetector loaded: enabled=" + enabled
                    + " keys=" + PROBE_KEYS.length + " (join-only probing)");
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
        try {
            PacketEvents.getAPI().getEventManager().unregisterListener(packetListener);
        } catch (Throwable ignored) {}
        // The plugin is disabled here, so restore() runs inline.
        for (UUID id : new ArrayList<>(pendingProbes.keySet())) {
            PendingProbe p = pendingProbes.remove(id);
            if (p != null) restore(p);
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    //  Probing
    // ═════════════════════════════════════════════════════════════════════

    /**
     * Schedules a single probe shortly after a player joins. This is the ONLY
     * automatic trigger for a probe. Skips quietly if the player isn't
     * eligible (bypassed, Bedrock, etc.) by the time it fires. Not gated by
     * worlds.list.
     */
    public void scheduleJoinProbe(Player player) {
        if (!enabled) return;
        UUID id = player.getUniqueId();
        // 60 ticks (3s) gives the client time to finish loading in before we
        // probe. BUGFIX (Folia): runs on the player's own thread, since
        // hasBypass reads the player's permissions and probe() reads a block
        // in the player's own chunk.
        PlatformUtil.runForEntityLater(plugin, player, () -> {
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
            probeWhenFree(player, 0);
        }, null, 60L);
    }

    /**
     * The sign editor replaces whatever screen the client has open, which
     * would close a menu another plugin just opened (login, captcha, ...).
     * Waits for the player's own inventory to be the only one open, up to
     * 30 s, then gives up for this join.
     */
    private void probeWhenFree(Player player, int tries) {
        if (!player.isOnline() || pendingProbes.containsKey(player.getUniqueId())) return;
        if (!hasOtherMenuOpen(player)) {
            probe(player);
            return;
        }
        if (tries >= 15) {
            if (debug) plugin.getLogger().info("[FPAntiFreeCam] Skipped join-probe for "
                    + player.getName() + ": a menu stayed open.");
            return;
        }
        PlatformUtil.runForEntityLater(plugin, player, () -> probeWhenFree(player, tries + 1), null, 40L);
    }

    // InventoryView is a class up to 1.20.6 and an interface from 1.21, and
    // this jar is built against 1.19.4, so getType() is called by reflection.
    private static final java.lang.reflect.Method VIEW_TYPE;
    static {
        java.lang.reflect.Method m = null;
        try {
            m = org.bukkit.inventory.InventoryView.class.getMethod("getType");
        } catch (Throwable ignored) {}
        VIEW_TYPE = m;
    }

    private static boolean hasOtherMenuOpen(Player player) {
        if (VIEW_TYPE == null) return false;
        try {
            Object type = VIEW_TYPE.invoke(player.getOpenInventory());
            String name = String.valueOf(type);
            return !name.equals("CRAFTING") && !name.equals("CREATIVE");
        } catch (Throwable t) {
            return false;
        }
    }

    /** Runs on the player's thread. */
    private void probe(Player player) {
        UUID id = player.getUniqueId();
        PendingProbe pending = null;
        try {
            // IMPROVEMENT: the sign goes in reach under the player's feet: the
            // first solid, opaque block 2-5 below them, so it sits inside the
            // ground and can't be seen. Only if there's none (flying,
            // standing on glass) does it fall back to the bottom of the
            // world. The client still sends the text from there, it just
            // closes the editor itself because the sign is out of reach.
            // With debug on it's always 2 below the feet, so you can see it.
            World world = player.getWorld();
            Location at = player.getLocation();
            Location loc = probeSpot(world, at);
            Vector3i pos = new Vector3i(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
            pending = new PendingProbe(PROBE_KEYS, player, loc, loc.getBlock().getBlockData(), pos);
            pendingProbes.put(id, pending);

            // BUGFIX: the sign block used to be sent as a PacketEvents block
            // id. On a server newer than the client (26.2 -> 1.21.11 through
            // ViaVersion) that id could be some other block, and the client
            // ignores the sign editor unless a real sign is at that spot.
            // The block and its text now go through the server's own API,
            // so the server writes the ids and ViaVersion converts them.
            // ChunkListener lets these two packets through unmasked.
            player.sendBlockChange(loc, Material.OAK_SIGN.createBlockData());
            String via = sendSignText(player, loc, pos);

            if (debug) {
                plugin.getLogger().info("[FPAntiFreeCam] Probing " + player.getName()
                        + " at " + pos.getX() + " " + pos.getY() + " " + pos.getZ()
                        + " (server " + PacketEvents.getAPI().getServerManager().getVersion().getReleaseName()
                        + ", client " + clientVersion(player)
                        + ", sign text via " + via + ", debug: the sign editor stays open for 1 second)");
            }

            // The editor opens 2 ticks later, so the client has surely placed
            // the sign first. Closing it makes the client send the sign text.
            // Without debug it closes in the same tick, so it's never drawn.
            PendingProbe sent = pending;
            PlayerManager pm = PacketEvents.getAPI().getPlayerManager();
            PlatformUtil.runForEntityLater(plugin, player, () -> {
                if (pendingProbes.get(id) != sent) return;
                try {
                    String opener = openEditor(player, loc, pos);
                    if (debug) plugin.getLogger().info("[FPAntiFreeCam] Sign editor opened for " + player.getName()
                            + " via " + opener);
                } catch (Throwable t) {
                    if (debug) plugin.getLogger().warning("[FPAntiFreeCam] Could not open the sign editor: " + t);
                    return;
                }
                Runnable close = () -> {
                    try {
                        pm.sendPacketSilently(player, new WrapperPlayServerCloseWindow(0));
                    } catch (Throwable ignored) {}
                };
                if (debug) PlatformUtil.runForEntityLater(plugin, player, close, null, 20L);
                else close.run();
            }, null, 2L);

            // BUGFIX: the wait used to be 5 ticks + ping. Ping is often still
            // 0 right after join, so the check gave up too early. It now waits
            // at least 3 seconds.
            long wait = Math.max(60, probeTimeoutTicks) + (debug ? 20 : 0)
                    + Math.min(100, Math.max(0, player.getPing()) / 50 + 1);
            PlatformUtil.runForEntityLater(plugin, player, () -> {
                if (!pendingProbes.remove(id, sent)) return;
                restore(sent);
                if (debug) plugin.getLogger().info("[FPAntiFreeCam] No sign answer from " + player.getName()
                        + " (the client never sent the sign text)");
            }, null, wait);
        } catch (Throwable e) {
            if (debug) plugin.getLogger().warning("[FPAntiFreeCam] Probe failed for " + player.getName() + ": " + e);
            else plugin.dbg("FreecamDetector probe failed for " + player.getName() + ": " + e);
            if (pending != null && pendingProbes.remove(id, pending)) restore(pending);
        }
    }

    /** Same column as the player, so it's in the player's own chunk (Folia safe). */
    private Location probeSpot(World world, Location at) {
        int x = at.getBlockX(), z = at.getBlockZ(), feet = at.getBlockY();
        int min = world.getMinHeight();
        if (debug) return new Location(world, x, Math.max(min, feet - 2), z);
        for (int y = feet - 2; y >= feet - 5 && y >= min; y--) {
            try {
                if (world.getBlockAt(x, y, z).getType().isOccluding()) return new Location(world, x, y, z);
            } catch (Throwable ignored) {}
        }
        return new Location(world, x, min, z);
    }

    // Paper 1.21.x+ has Player#openVirtualSign(Position, Side). The jar is
    // built against 1.19.4, so it's looked up by reflection.
    private static final java.lang.reflect.Method OPEN_VIRTUAL_SIGN, POSITION_BLOCK;
    private static final Object SIDE_FRONT;
    static {
        java.lang.reflect.Method open = null, block = null;
        Object front = null;
        try {
            Class<?> position = Class.forName("io.papermc.paper.math.Position");
            Class<?> side = Class.forName("org.bukkit.block.sign.Side");
            open = Player.class.getMethod("openVirtualSign", position, side);
            block = position.getMethod("block", Location.class);
            front = side.getField("FRONT").get(null);
        } catch (Throwable ignored) {
            open = null;
        }
        OPEN_VIRTUAL_SIGN = open;
        POSITION_BLOCK = block;
        SIDE_FRONT = front;
    }

    /** Opens the editor on the fake sign. Returns which path was used, for the debug log. */
    private static String openEditor(Player player, Location loc, Vector3i pos) throws Exception {
        if (OPEN_VIRTUAL_SIGN != null) {
            try {
                OPEN_VIRTUAL_SIGN.invoke(player, POSITION_BLOCK.invoke(null, loc), SIDE_FRONT);
                return "Paper";
            } catch (Throwable ignored) {}
        }
        PacketEvents.getAPI().getPlayerManager().sendPacketSilently(player, new WrapperPlayServerOpenSignEditor(pos, true));
        return "PacketEvents";
    }

    /**
     * Sends the sign's text. Paper: through the server's own sign API, as
     * translatable components, so the server builds the data in its own
     * format. Spigot has no component API for signs, so it falls back to
     * PacketEvents. Returns which path was used, for the debug log.
     */
    private String sendSignText(Player player, Location loc, Vector3i pos) {
        if (PlatformUtil.isPaper()) {
            try {
                PaperSignText.send(player, loc, PROBE_KEYS);
                return "Paper";
            } catch (Throwable t) {
                if (debug) plugin.getLogger().warning("[FPAntiFreeCam] Paper sign text failed, using PacketEvents: " + t);
            }
        }
        PacketEvents.getAPI().getPlayerManager().sendPacketSilently(player,
                new WrapperPlayServerBlockEntityData(pos, BlockEntityTypes.SIGN, signNbt(PROBE_KEYS)));
        return "PacketEvents";
    }

    private static String clientVersion(Player player) {
        try {
            User user = PacketEvents.getAPI().getPlayerManager().getUser(player);
            return user != null ? String.valueOf(user.getClientVersion()) : "?";
        } catch (Throwable t) {
            return "?";
        }
    }

    /** Kept in its own class so Spigot never loads the Adventure classes. */
    private static final class PaperSignText {
        static void send(Player player, Location loc, String[] keys) {
            List<net.kyori.adventure.text.Component> lines = new ArrayList<>(4);
            for (int i = 0; i < 4; i++) {
                lines.add(i < keys.length ? net.kyori.adventure.text.Component.translatable(keys[i])
                                          : net.kyori.adventure.text.Component.empty());
            }
            player.sendSignChange(loc, lines);
        }
    }

    /**
     * Netty: true for the fake sign's block and text packets, so
     * ChunkListener sends them as they are instead of masking them.
     */
    boolean isProbeBlock(UUID id, Vector3i pos) {
        PendingProbe p = pendingProbes.get(id);
        return p != null && p.position.equals(pos);
    }

    boolean hasPendingProbe(UUID id) {
        return !pendingProbes.isEmpty() && pendingProbes.containsKey(id);
    }

    /**
     * Sign block entity data with one translatable key per line. The
     * format changed in 1.20 (front_text) and 1.21.5 (lines stored as NBT
     * instead of JSON); ViaVersion converts it for other client versions.
     */
    private static NBTCompound signNbt(String[] keys) {
        NBTCompound nbt = new NBTCompound();
        ServerVersion version = PacketEvents.getAPI().getServerManager().getVersion();

        if (version.isOlderThan(ServerVersion.V_1_20)) {
            for (int i = 0; i < 4; i++) {
                nbt.setTag("Text" + (i + 1), new NBTString(lineJson(keys, i)));
            }
            return nbt;
        }

        NBTCompound side = new NBTCompound();
        if (version.isNewerThanOrEquals(ServerVersion.V_1_21_5)) {
            NBTList<NBTCompound> lines = NBTList.createCompoundList();
            for (int i = 0; i < 4; i++) {
                NBTCompound line = new NBTCompound();
                if (i < keys.length) line.setTag("translate", new NBTString(keys[i]));
                else line.setTag("text", new NBTString(""));
                lines.addTag(line);
            }
            side.setTag("messages", lines);
        } else {
            NBTList<NBTString> lines = NBTList.createStringList();
            for (int i = 0; i < 4; i++) lines.addTag(new NBTString(lineJson(keys, i)));
            side.setTag("messages", lines);
        }
        nbt.setTag("front_text", side);
        return nbt;
    }

    private static String lineJson(String[] keys, int i) {
        return i < keys.length ? "{\"translate\":\"" + keys[i] + "\"}" : "\"\"";
    }

    /** Sends the real block back. Goes through ChunkListener, so it's masked like the chunk. */
    private void restore(PendingProbe p) {
        PlatformUtil.runForEntity(plugin, p.player, () -> {
            try {
                Player player = p.player;
                if (!player.isOnline() || !player.getWorld().equals(p.location.getWorld())) return;
                player.sendBlockChange(p.location, p.realBlock);
            } catch (Throwable ignored) {}
        });
    }

    // ═════════════════════════════════════════════════════════════════════
    //  Answer capture (Netty, no Bukkit calls)
    // ═════════════════════════════════════════════════════════════════════

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (event.getPacketType() != PacketType.Play.Client.UPDATE_SIGN) return;
        User user = event.getUser();
        UUID id = user != null ? user.getUUID() : null;
        PendingProbe pending = id != null ? pendingProbes.get(id) : null;
        if (pending == null) {
            if (debug) plugin.getLogger().info("[FPAntiFreeCam] Sign text from " + (user != null ? user.getName() : "?")
                    + " with no probe waiting (too late, or a real sign)");
            return;
        }

        String[] lines;
        try {
            WrapperPlayClientUpdateSign wrapper = new WrapperPlayClientUpdateSign(event);
            if (!pending.position.equals(wrapper.getBlockPosition())) {
                if (debug) plugin.getLogger().info("[FPAntiFreeCam] Sign text from " + user.getName()
                        + " for " + wrapper.getBlockPosition() + ", probe sign is at " + pending.position);
                return;
            }
            lines = wrapper.getTextLines();
        } catch (Throwable t) {
            if (debug) plugin.getLogger().warning("[FPAntiFreeCam] Could not read sign text from "
                    + user.getName() + ": " + t);
            return;
        }
        // Our fake sign: the server and other plugins never need to see this edit.
        event.setCancelled(true);
        if (!pendingProbes.remove(id, pending)) return;
        restore(pending);

        // A client without the mod sends each key back unchanged. Anything
        // else on a key's line means the client translated it.
        String matchedKey = null;
        String matchedText = null;
        for (int i = 0; i < pending.keys.length; i++) {
            String line = lines != null && i < lines.length && lines[i] != null ? lines[i].trim() : "";
            if (!line.isEmpty() && !line.equals(pending.keys[i])) {
                matchedKey = pending.keys[i];
                matchedText = line;
                break;
            }
        }
        if (debug) {
            plugin.getLogger().info("[FPAntiFreeCam] Sign answer from " + pending.player.getName()
                    + ": " + (lines != null ? String.join(" | ", lines) : "(no lines)")
                    + (matchedKey != null ? "  -> Freecam found ('" + matchedKey + "' = '" + matchedText + "')"
                                          : "  -> no Freecam (keys came back untranslated)"));
        }
        if (matchedKey != null) handleDetection(pending.player, matchedKey);
    }

    // ═════════════════════════════════════════════════════════════════════
    //  Punishment
    // ═════════════════════════════════════════════════════════════════════

    private void handleDetection(Player player, String matchedKey) {
        plugin.getLogger().warning("[FPAntiFreeCam] Freecam detected: " + player.getName()
                + " (translation key '" + matchedKey + "' resolved client-side)");

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
