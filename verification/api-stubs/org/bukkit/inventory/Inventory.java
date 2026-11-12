package org.bukkit.inventory; public interface Inventory { ItemStack[] getContents(); ItemStack getItem(int i); void setItem(int i, ItemStack s); int getSize(); }
