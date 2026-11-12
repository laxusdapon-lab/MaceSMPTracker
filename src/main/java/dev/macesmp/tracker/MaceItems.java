package dev.macesmp.tracker;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.UUID;

/** Reads and writes the persistent Mace id stored on the item itself. */
final class MaceItems {
    private final NamespacedKey key;

    MaceItems(Plugin plugin) {
        this.key = new NamespacedKey(plugin, "mace_id");
        // The NBT reader must look for exactly the key Bukkit writes into custom_data.
        MaceNbt.idKey = key.toString();
    }

    boolean isMace(ItemStack s) {
        return s != null && s.getType() == Material.MACE;
    }

    /** @return the tracker id, or null when the item is not a Mace or carries no valid id */
    UUID getId(ItemStack s) {
        if (!isMace(s)) return null;
        ItemMeta meta = s.getItemMeta();
        if (meta == null) return null;
        String v = meta.getPersistentDataContainer().get(key, PersistentDataType.STRING);
        if (v == null) return null;
        try {
            return UUID.fromString(v);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    ItemStack withId(ItemStack s, UUID id) {
        ItemStack copy = s.clone();
        ItemMeta meta = copy.getItemMeta();
        meta.getPersistentDataContainer().set(key, PersistentDataType.STRING, id.toString());
        copy.setItemMeta(meta);
        return copy;
    }
}
