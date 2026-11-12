package dev.macesmp.tracker;

import org.bukkit.inventory.ItemStack;

/** A re-readable pointer to one item slot somewhere in the world (inventory slot, item entity, frame, ...). */
interface Ref {
    /** Current content, or null when empty or the holder no longer exists. */
    ItemStack get();

    /** Replaces the content; null empties the slot (or removes an item entity). */
    void set(ItemStack stack);

    /** Stable identity of the underlying slot, used so one slot seen twice is never mistaken for a duplicate. */
    String key();
}
