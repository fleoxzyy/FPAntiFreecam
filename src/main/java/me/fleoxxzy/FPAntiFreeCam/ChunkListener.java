package me.fleoxxzy.FPAntiFreeCam;

import com.github.retrooper.packetevents.event.PacketListener;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.protocol.world.chunk.BaseChunk;
import com.github.retrooper.packetevents.protocol.world.chunk.Column;
import com.github.retrooper.packetevents.protocol.world.states.WrappedBlockState;
import com.github.retrooper.packetevents.util.Vector3i;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerBlockChange;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerChunkData;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerMultiBlockChange;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.UUID;

/**
 * PacketEvents listener that intercepts outbound chunk/block-change packets
 * and replaces hidden underground blocks with air for eligible players.
 *
 * Supports:
 *  - CHUNK_DATA          (initial chunk load and full re-send)
 *  - BLOCK_CHANGE        (single block update)
 *  - MULTI_BLOCK_CHANGE  (section batch update)
 */
public final class ChunkListener implements PacketListener {

    private final FPAntiFreeCam plugin;

    public ChunkListener(FPAntiFreeCam plugin) {
        this.plugin = plugin;
    }

    // ── PacketEvents entry point ──────────────────────────────────────────

    @Override
    public void onPacketSend(PacketSendEvent event) {
        User user = event.getUser();
        if (user == null) return;

        UUID uuid = user.getUUID();
        if (uuid == null) return;

        Player player = Bukkit.getPlayer(uuid);
        if (player == null || !player.isOnline()) return;

        World world = player.getWorld();
        if (world == null || !plugin.isWorldProtected(world.getName())) return;

        if (!plugin.isProtectionActive(player)) return;

        if (event.getPacketType() == PacketType.Play.Server.CHUNK_DATA) {
            handleChunkData(event, player);
        } else if (event.getPacketType() == PacketType.Play.Server.BLOCK_CHANGE) {
            handleBlockChange(event, player);
        } else if (event.getPacketType() == PacketType.Play.Server.MULTI_BLOCK_CHANGE) {
            handleMultiBlockChange(event, player);
        }
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        // No inbound packets need modification
    }

    // ── CHUNK_DATA ────────────────────────────────────────────────────────

    private void handleChunkData(PacketSendEvent event, Player player) {
        plugin.incrementPacketsProcessed();
        WrappedBlockState replacement = plugin.getReplacementBlock();
        if (replacement == null) return;

        WrapperPlayServerChunkData wrapper;
        try {
            wrapper = new WrapperPlayServerChunkData(event);
        } catch (Exception e) {
            plugin.dbg("ChunkData wrapper error for " + player.getName() + ": " + e.getMessage());
            return;
        }

        Column column;
        BaseChunk[] sections;
        try {
            column   = wrapper.getColumn();
            sections = column != null ? column.getChunks() : null;
        } catch (Exception e) {
            plugin.dbg("Column access error: " + e.getMessage());
            return;
        }

        if (sections == null) return;

        World   world         = player.getWorld();
        int     minY          = world.getMinHeight();
        int     voidY         = plugin.getVoidY();
        int     replacementId = plugin.getReplacementBlockId();

        // Early-out: if world minY is already above voidY, no blocks in this world can be hidden.
        if (minY > voidY) return;

        boolean modified      = false;
        long    replacedCount = 0;

        for (int si = 0; si < sections.length; si++) {
            BaseChunk section = sections[si];
            if (section == null || section.isEmpty()) continue;

            int sectionBaseY = minY + si * 16;

            // Optimization: skip the whole section if its bottom is already above voidY.
            if (sectionBaseY > voidY) continue;

            for (int ly = 0; ly < 16; ly++) {
                int worldY = sectionBaseY + ly;
                if (worldY > voidY) break; // Optimization: skip remaining layers in this section

                for (int lx = 0; lx < 16; lx++) {
                    for (int lz = 0; lz < 16; lz++) {
                        try {
                            // Optimization: use integer ID comparison (much faster than .equals())
                            if (section.getFlatBlock(lx, ly, lz) != replacementId) {
                                section.set(lx, ly, lz, replacement);
                                replacedCount++;
                                modified = true;
                            }
                        } catch (Exception ignored) {}
                    }
                }
            }
        }

        if (modified) {
            try { wrapper.setIgnoreOldData(true); } catch (Exception ignored) {}
            event.markForReEncode(true);
            plugin.incrementChunksModified();
            plugin.addBlocksReplaced(replacedCount);
            plugin.dbg("CHUNK_DATA modified for " + player.getName() + " (" + replacedCount + " blocks)");
        }
    }

    // ── BLOCK_CHANGE ──────────────────────────────────────────────────────

    private void handleBlockChange(PacketSendEvent event, Player player) {
        plugin.incrementPacketsProcessed();
        WrappedBlockState replacement = plugin.getReplacementBlock();
        if (replacement == null) return;

        try {
            WrapperPlayServerBlockChange wrapper = new WrapperPlayServerBlockChange(event);
            Vector3i pos = wrapper.getBlockPosition();
            if (pos == null) return;

            if (pos.getY() > plugin.getVoidY()) return;

            int replacementId = plugin.getReplacementBlockId();
            if (wrapper.getBlockState().getGlobalId() != replacementId) {
                wrapper.setBlockState(replacement);
                event.markForReEncode(true);
                plugin.addBlocksReplaced(1);
                plugin.dbg("BLOCK_CHANGE modified at " + pos + " for " + player.getName());
            }
        } catch (Exception e) {
            plugin.dbg("BLOCK_CHANGE error: " + e.getMessage());
        }
    }

    // ── MULTI_BLOCK_CHANGE ────────────────────────────────────────────────

    private void handleMultiBlockChange(PacketSendEvent event, Player player) {
        plugin.incrementPacketsProcessed();
        WrappedBlockState replacement = plugin.getReplacementBlock();
        if (replacement == null) return;

        try {
            WrapperPlayServerMultiBlockChange wrapper = new WrapperPlayServerMultiBlockChange(event);
            WrapperPlayServerMultiBlockChange.EncodedBlock[] blocks = wrapper.getBlocks();
            if (blocks == null) return;

            boolean modified      = false;
            int     replacementId = plugin.getReplacementBlockId();
            int     voidY         = plugin.getVoidY();
            int     replacedCount = 0;

            for (WrapperPlayServerMultiBlockChange.EncodedBlock block : blocks) {
                if (block == null) continue;
                if (block.getY() > voidY) continue;
                if (block.getBlockId() != replacementId) {
                    block.setBlockId(replacementId);
                    replacedCount++;
                    modified = true;
                }
            }

            if (modified) {
                event.markForReEncode(true);
                plugin.addBlocksReplaced(replacedCount);
                plugin.dbg("MULTI_BLOCK_CHANGE modified for " + player.getName() + " (" + replacedCount + " blocks)");
            }
        } catch (Exception e) {
            plugin.dbg("MULTI_BLOCK_CHANGE error: " + e.getMessage());
        }
    }
}
