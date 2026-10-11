package me.fleoxxzy.FPAntiFreeCam;

import com.destroystokyo.paper.event.entity.EntityRemoveFromWorldEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

/**
 * Evicts entities from ChunkListener's entity Y cache as they leave the world.
 *
 * Paper-only (the event doesn't exist on Spigot), so this lives in its own
 * class and is only registered after checking the event class is present.
 * On Folia the event fires on the entity's owning region thread, which makes
 * getEntityId() safe here — unlike the old global-thread world scan, which
 * Folia rejects with "Accessing entity state off owning region's thread".
 */
final class EntityRemoveListener implements Listener {

    private final ChunkListener chunkListener;

    EntityRemoveListener(ChunkListener chunkListener) {
        this.chunkListener = chunkListener;
    }

    static boolean isSupported() {
        try {
            Class.forName("com.destroystokyo.paper.event.entity.EntityRemoveFromWorldEvent");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntityRemove(EntityRemoveFromWorldEvent event) {
        chunkListener.removeEntityFromCache(event.getEntity().getEntityId());
    }
}
