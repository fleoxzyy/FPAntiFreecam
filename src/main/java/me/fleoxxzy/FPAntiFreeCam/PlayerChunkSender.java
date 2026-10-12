package me.fleoxxzy.FPAntiFreeCam;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.BitSet;

/**
 * Re-sends one chunk to ONE player.
 *
 * <p>BUGFIX (network saturation): {@code World#refreshChunk} re-sends the
 * chunk to every player that can see it, not just the one whose Smart Fill
 * view changed. Smart Fill refreshes chunks per player every second, so with
 * N players near each other every refresh went out N times and total chunk
 * traffic grew with N² - enough to saturate the network and raise everyone's
 * ping. This sends the chunk packet only to the player that needs it, and
 * only if their client actually has that chunk loaded.
 *
 * <p>Uses Paper's Mojang-mapped server internals via reflection. If anything
 * can't be resolved (Spigot, very old Paper), it falls back to
 * {@code World#refreshChunk}.
 */
final class PlayerChunkSender {

    private static final Method PLAYER_IS_CHUNK_SENT;
    static {
        Method m = null;
        try { m = Player.class.getMethod("isChunkSent", long.class); } catch (Throwable ignored) {}
        PLAYER_IS_CHUNK_SENT = m;
    }

    private final Plugin plugin;

    private volatile boolean resolved;
    private volatile boolean nmsBroken;

    private Method worldGetHandle;
    private Method levelGetChunkIfLoaded;
    private Method levelGetLightEngine;
    private Field  levelController;
    private Method controllerShouldModify;
    private Method playerGetHandle;
    private Field  playerConnection;
    private Method connectionSend;
    private Constructor<?> packetCtor;
    private boolean ctorHasModifyFlag;

    PlayerChunkSender(Plugin plugin) {
        this.plugin = plugin;
    }

    /**
     * True if this player's client currently has chunk (cx, cz) loaded, so a
     * re-send would actually replace something. Must run on the thread that
     * owns the player (Folia entity scheduler / Paper main thread).
     */
    boolean shouldSend(Player player, World world, int cx, int cz) {
        if (player == null || !player.isOnline()) return false;
        if (!player.getWorld().equals(world)) return false;
        return clientHasChunk(player, world, cx, cz);
    }

    /**
     * Sends chunk (cx, cz) to this player only. Must run on the thread that
     * owns the CHUNK (Folia region thread / Paper main thread). Call
     * {@link #shouldSend} first, on the player's thread.
     */
    void send(Player player, World world, int cx, int cz) {
        if (player == null || !player.isOnline()) return;
        if (PlatformUtil.isFolia()
                && !PlatformUtil.isOwnedByCurrentRegion(new Location(world, cx << 4, 0, cz << 4))) {
            return;
        }

        if (!nmsBroken) {
            try {
                sendDirect(player, world, cx, cz);
                return;
            } catch (Throwable t) {
                nmsBroken = true;
                plugin.getLogger().warning("[FPAntiFreeCam] Per-player chunk sending is unavailable on this server ("
                        + t.getClass().getSimpleName() + ": " + t.getMessage()
                        + "). Falling back to World#refreshChunk, which re-sends chunks to every nearby player.");
            }
        }
        try { world.refreshChunk(cx, cz); } catch (Exception ignored) {}
    }

    private static boolean clientHasChunk(Player player, World world, int cx, int cz) {
        if (PLAYER_IS_CHUNK_SENT != null) {
            try {
                long key = ((long) cx & 0xFFFFFFFFL) | (((long) cz & 0xFFFFFFFFL) << 32);
                return (Boolean) PLAYER_IS_CHUNK_SENT.invoke(player, key);
            } catch (Throwable ignored) {}
        }
        if (!world.isChunkLoaded(cx, cz)) return false;
        int pcx = player.getLocation().getBlockX() >> 4;
        int pcz = player.getLocation().getBlockZ() >> 4;
        int vd = PlatformUtil.viewDistance(player) + 1;
        return Math.abs(cx - pcx) <= vd && Math.abs(cz - pcz) <= vd;
    }

    private void sendDirect(Player player, World world, int cx, int cz) throws Exception {
        if (!resolved) resolve(world, player);

        Object level = worldGetHandle.invoke(world);
        Object chunk = levelGetChunkIfLoaded.invoke(level, cx, cz);
        if (chunk == null) return;

        Object serverPlayer = playerGetHandle.invoke(player);
        Object connection = playerConnection.get(serverPlayer);
        if (connection == null) return;

        Object light = levelGetLightEngine.invoke(level);
        Object packet;
        if (ctorHasModifyFlag) {
            // Same as Paper's own refresh: let its anti-xray decide per player.
            boolean modify = true;
            if (levelController != null && controllerShouldModify != null) {
                Object controller = levelController.get(level);
                if (controller != null) modify = (Boolean) controllerShouldModify.invoke(controller, serverPlayer, chunk);
            }
            packet = packetCtor.newInstance(chunk, light, null, null, modify);
        } else {
            packet = packetCtor.newInstance(chunk, light, null, null);
        }
        connectionSend.invoke(connection, packet);
    }

    private synchronized void resolve(World world, Player player) throws Exception {
        if (resolved) return;

        worldGetHandle = world.getClass().getMethod("getHandle");
        Object level = worldGetHandle.invoke(world);
        ClassLoader cl = level.getClass().getClassLoader();

        levelGetChunkIfLoaded = level.getClass().getMethod("getChunkIfLoaded", int.class, int.class);
        levelGetLightEngine   = level.getClass().getMethod("getLightEngine");

        Class<?> chunkClass  = levelGetChunkIfLoaded.getReturnType();
        Class<?> packetClass = Class.forName(
                "net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket", false, cl);

        Constructor<?> five = null, four = null;
        for (Constructor<?> c : packetClass.getConstructors()) {
            Class<?>[] p = c.getParameterTypes();
            if (p.length < 4 || !p[0].isAssignableFrom(chunkClass)) continue;
            if (!p[1].isAssignableFrom(levelGetLightEngine.getReturnType())) continue;
            if (p[2] != BitSet.class || p[3] != BitSet.class) continue;
            if (p.length == 5 && p[4] == boolean.class) five = c;
            else if (p.length == 4) four = c;
        }
        if (five == null && four == null) throw new NoSuchMethodException("chunk packet constructor");
        packetCtor = five != null ? five : four;
        ctorHasModifyFlag = five != null;

        try {
            levelController = level.getClass().getField("chunkPacketBlockController");
            for (Method m : levelController.getType().getMethods()) {
                if (m.getName().equals("shouldModify") && m.getParameterCount() == 2
                        && m.getReturnType() == boolean.class) {
                    controllerShouldModify = m;
                    break;
                }
            }
        } catch (NoSuchFieldException ignored) {
            levelController = null;
        }

        playerGetHandle = player.getClass().getMethod("getHandle");
        Object serverPlayer = playerGetHandle.invoke(player);
        playerConnection = serverPlayer.getClass().getField("connection");
        Class<?> packetBase = Class.forName("net.minecraft.network.protocol.Packet", false, cl);
        connectionSend = playerConnection.getType().getMethod("send", packetBase);

        resolved = true;
    }
}
