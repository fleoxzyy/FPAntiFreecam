package me.fleoxxzy.FPAntiFreeCam;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * NEW: re-sends a block entity (chest, shulker box...) to one player after
 * Anti-Xray reveals the real block. Anti-Xray drops the block entity while the
 * block is faked, and without it the client draws an empty / invisible chest
 * and no custom name.
 *
 * <p>Reflection on Mojang-mapped internals (Paper 1.20.5+). If anything is
 * missing the resend is skipped: the real block still shows, and the server
 * sends the data again the next time the block entity changes.
 */
final class BlockEntityResender {

    private BlockEntityResender() {}

    private static volatile boolean ready, failed;
    private static Constructor<?> blockPos;
    private static Method getBlockEntity, getUpdatePacket, playerHandle, send;
    private static Field connection;

    private static synchronized boolean init(Object level, Player player) {
        if (ready) return true;
        if (failed) return false;
        try {
            ClassLoader cl = level.getClass().getClassLoader();
            Class<?> bp = Class.forName("net.minecraft.core.BlockPos", false, cl);
            blockPos = bp.getConstructor(int.class, int.class, int.class);
            getBlockEntity = findMethod(level.getClass(), "getBlockEntity", bp);
            Class<?> be = Class.forName("net.minecraft.world.level.block.entity.BlockEntity", false, cl);
            getUpdatePacket = be.getMethod("getUpdatePacket");
            playerHandle = player.getClass().getMethod("getHandle");
            Object sp = playerHandle.invoke(player);
            connection = findField(sp.getClass(), "connection");
            Class<?> packet = Class.forName("net.minecraft.network.protocol.Packet", false, cl);
            send = findMethod(connection.getType(), "send", packet);
            ready = true;
        } catch (Throwable t) {
            failed = true;
        }
        return ready;
    }

    private static Method findMethod(Class<?> c, String name, Class<?> arg) throws NoSuchMethodException {
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            for (Method m : k.getDeclaredMethods()) {
                if (m.getName().equals(name) && m.getParameterCount() == 1 && m.getParameterTypes()[0] == arg) {
                    m.setAccessible(true);
                    return m;
                }
            }
        }
        throw new NoSuchMethodException(name);
    }

    private static Field findField(Class<?> c, String name) throws NoSuchFieldException {
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            try {
                Field f = k.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {}
        }
        throw new NoSuchFieldException(name);
    }

    /** Must run on the thread that owns the block (skipped otherwise on Folia). */
    static void resend(Player player, World world, int x, int y, int z) {
        if (failed) return;
        if (PlatformUtil.isFolia() && !PlatformUtil.isOwnedByCurrentRegion(new Location(world, x, y, z))) return;
        Object level = FastBlockReader.level(world);
        if (level == null || !init(level, player)) return;
        try {
            Object be = getBlockEntity.invoke(level, blockPos.newInstance(x, y, z));
            if (be == null) return;
            Object pkt = getUpdatePacket.invoke(be);
            if (pkt == null) return;
            send.invoke(connection.get(playerHandle.invoke(player)), pkt);
        } catch (Throwable ignored) {}
    }
}
