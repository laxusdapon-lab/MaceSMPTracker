package dev.macesmp.tracker;

import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityRemoveEvent;
import org.bukkit.event.player.PlayerItemBreakEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.UUID;

/** Notices when a Mace is permanently destroyed so its slot becomes free again. */
final class LifecycleListener implements Listener {
    private static final EquipmentSlot[] MOB_SLOTS = {
            EquipmentSlot.HAND, EquipmentSlot.OFF_HAND, EquipmentSlot.HEAD,
            EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET
    };

    private final TrackerService svc;
    private final MaceItems items;
    private final Sweeper sweeper;

    LifecycleListener(TrackerService svc, Sweeper sweeper) {
        this.svc = svc;
        this.items = svc.items;
        this.sweeper = sweeper;
    }

    /** Durability ran out. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onBreak(PlayerItemBreakEvent e) {
        UUID id = items.getId(e.getBrokenItem());
        if (id != null) svc.destroyed(id, "DURABILITY_BREAK", true);
    }

    /**
     * Item entities destroyed by fire, lava, explosions, cactus, the void, or despawn timers.
     * Removals that only move the item (pickup, merge, chunk unload, ...) are deliberately ignored.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onRemove(EntityRemoveEvent e) {
        String cause = e.getCause().name();
        boolean gone = cause.equals("DEATH") || cause.equals("DESPAWN") || cause.equals("OUT_OF_WORLD");
        if (!gone) return;
        Entity en = e.getEntity();
        if (en instanceof Item it) {
            UUID id = items.getId(it.getItemStack());
            if (id != null) svc.destroyed(id, "ITEM_" + cause, true);
            return;
        }
        // A mob that despawns (or falls into the void) takes its gear with it. A mob that dies drops it.
        if (en instanceof LivingEntity le && !(en instanceof Player) && !cause.equals("DEATH")) {
            EntityEquipment eq = le.getEquipment();
            if (eq == null) return;
            for (EquipmentSlot slot : MOB_SLOTS) {
                ItemStack st = eq.getItem(slot);
                UUID id = items.getId(st);
                if (id != null) svc.destroyed(id, "MOB_" + cause, true);
            }
        }
    }

    /** Checks a joining player's inventory so duplicates and untracked Maces are caught right away. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTaskLater(svc.plugin, () -> {
            if (p.isOnline() && !sweeper.isRunning()) sweeper.start(List.of(p), false, r -> { });
        }, 40L);
    }
}
