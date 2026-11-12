package dev.macesmp.tracker;

import org.bukkit.Material;
import org.bukkit.block.BlockState;
import org.bukkit.block.ShulkerBox;
import org.bukkit.entity.Item;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.ItemMeta;

/** Concrete {@link Ref} implementations. */
final class Refs {
    private Refs() {
    }

    static final class InvRef implements Ref {
        private final Inventory inv;
        private final int slot;
        private final String key;

        InvRef(Inventory inv, int slot, String key) {
            this.inv = inv;
            this.slot = slot;
            this.key = key;
        }

        @Override public ItemStack get() { return inv.getItem(slot); }
        @Override public void set(ItemStack stack) { inv.setItem(slot, stack); }
        @Override public String key() { return key; }
    }

    static final class CursorRef implements Ref {
        private final Player player;

        CursorRef(Player player) { this.player = player; }

        @Override public ItemStack get() { return player.getItemOnCursor(); }
        @Override public void set(ItemStack stack) {
            player.setItemOnCursor(stack == null ? new ItemStack(Material.AIR) : stack);
        }
        @Override public String key() { return "cursor:" + player.getUniqueId(); }
    }

    static final class ItemEntityRef implements Ref {
        private final Item item;

        ItemEntityRef(Item item) { this.item = item; }

        @Override public ItemStack get() { return item.isValid() ? item.getItemStack() : null; }
        @Override public void set(ItemStack stack) {
            if (stack == null || stack.getType() == Material.AIR) item.remove();
            else item.setItemStack(stack);
        }
        @Override public String key() { return "e:" + item.getUniqueId(); }
    }

    static final class FrameRef implements Ref {
        private final ItemFrame frame;

        FrameRef(ItemFrame frame) { this.frame = frame; }

        @Override public ItemStack get() { return frame.isValid() ? frame.getItem() : null; }
        @Override public void set(ItemStack stack) { frame.setItem(stack); }
        @Override public String key() { return "frame:" + frame.getUniqueId(); }
    }

    static final class EquipRef implements Ref {
        private final LivingEntity entity;
        private final EquipmentSlot slot;

        EquipRef(LivingEntity entity, EquipmentSlot slot) {
            this.entity = entity;
            this.slot = slot;
        }

        @Override public ItemStack get() {
            if (!entity.isValid()) return null;
            EntityEquipment eq = entity.getEquipment();
            return eq == null ? null : eq.getItem(slot);
        }
        @Override public void set(ItemStack stack) {
            EntityEquipment eq = entity.getEquipment();
            if (eq != null) eq.setItem(slot, stack == null ? new ItemStack(Material.AIR) : stack);
        }
        @Override public String key() { return "eq:" + entity.getUniqueId() + ":" + slot.name(); }
    }

    /** A Mace stored inside a shulker box item. Reads and writes go through the parent stack's block state. */
    static final class ShulkerRef implements Ref {
        private final Ref parent;
        private final int index;

        ShulkerRef(Ref parent, int index) {
            this.parent = parent;
            this.index = index;
        }

        private ShulkerBox box(ItemStack p) {
            if (p == null) return null;
            ItemMeta meta = p.getItemMeta();
            if (!(meta instanceof BlockStateMeta bsm)) return null;
            BlockState st = bsm.getBlockState();
            return st instanceof ShulkerBox sb ? sb : null;
        }

        @Override public ItemStack get() {
            ShulkerBox sb = box(parent.get());
            return sb == null ? null : sb.getInventory().getItem(index);
        }

        @Override public void set(ItemStack stack) {
            ItemStack p = parent.get();
            ShulkerBox sb = box(p);
            if (sb == null) return;
            sb.getInventory().setItem(index, stack);
            BlockStateMeta bsm = (BlockStateMeta) p.getItemMeta();
            bsm.setBlockState(sb);
            p.setItemMeta(bsm);
            parent.set(p);
        }

        @Override public String key() { return parent.key() + ">" + index; }
    }
}
