package dev.macesmp.tracker;

import org.bukkit.Material;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.CrafterCraftEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.inventory.CraftingInventory;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Controls how Maces come into existence.
 *  - Crafting table: allowed only while active < limit. The result is stamped with a fresh unique id
 *    in PrepareItemCraftEvent and the id is registered in CraftItemEvent (MONITOR, so only if the
 *    craft really goes through). The event loop is single threaded, so two players can never both
 *    take the last slot.
 *  - Shift-click crafting is refused (it could craft several in one click).
 *  - Combining two Maces in the grid (the "repair" recipe) is refused.
 *  - The Crafter block can never craft a Mace.
 */
final class CraftListener implements Listener {
    private final TrackerService svc;
    private final MaceItems items;
    private final Map<UUID, Long> lastMessage = new HashMap<>();

    CraftListener(TrackerService svc) {
        this.svc = svc;
        this.items = svc.items;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPrepare(PrepareItemCraftEvent e) {
        CraftingInventory inv = e.getInventory();
        ItemStack result = inv.getResult();
        if (result == null || result.getType() != Material.MACE) return;
        Settings s = svc.settings;
        if (e.isRepair()) {
            inv.setResult(null);
            tell(e.getViewers(), s.msgRepair);
            return;
        }
        if (svc.isFull()) {
            inv.setResult(null);
            tell(e.getViewers(), s.msgLimitReached);
            return;
        }
        inv.setResult(items.withId(result, UUID.randomUUID()));
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onCraft(CraftItemEvent e) {
        ItemStack cur = e.getCurrentItem();
        if (cur == null || cur.getType() != Material.MACE) return;
        HumanEntity who = e.getWhoClicked();
        if (e.isShiftClick()) {
            e.setCancelled(true);
            if (who instanceof Player p) p.sendMessage(svc.settings.prefix + svc.settings.msgShiftClick);
            return;
        }
        UUID id = items.getId(cur);
        // No id (stamping failed), limit reached since the preview, or an id that already exists: refuse.
        if (id == null || svc.isFull() || svc.registry.get(id) != null) {
            e.setCancelled(true);
            if (who instanceof Player p) p.sendMessage(svc.settings.prefix + svc.format(svc.settings.msgLimitReached));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCraftDone(CraftItemEvent e) {
        ItemStack cur = e.getCurrentItem();
        if (cur == null || cur.getType() != Material.MACE) return;
        UUID id = items.getId(cur);
        if (id == null || !(e.getWhoClicked() instanceof Player p)) return;
        svc.registerCrafted(p, id);
    }

    /** Automatic Crafter blocks must never produce a Mace. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onCrafter(CrafterCraftEvent e) {
        if (!svc.settings.blockCrafter) return;
        ItemStack r = e.getResult();
        if (r != null && r.getType() == Material.MACE) e.setCancelled(true);
    }

    private void tell(java.util.List<HumanEntity> viewers, String template) {
        long now = System.currentTimeMillis();
        for (HumanEntity h : viewers) {
            if (!(h instanceof Player p)) continue;
            Long last = lastMessage.get(p.getUniqueId());
            if (last != null && now - last < 5000) continue;
            lastMessage.put(p.getUniqueId(), now);
            p.sendMessage(svc.settings.prefix + svc.format(template));
        }
    }
}
