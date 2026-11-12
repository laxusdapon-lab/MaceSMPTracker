package org.bukkit.entity; import org.bukkit.inventory.*;
public interface Player extends HumanEntity { PlayerInventory getInventory(); Inventory getEnderChest(); ItemStack getItemOnCursor(); void setItemOnCursor(ItemStack s); boolean isOnline(); }
