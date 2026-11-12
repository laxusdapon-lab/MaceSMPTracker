package org.bukkit.inventory; import org.bukkit.Material; import org.bukkit.inventory.meta.ItemMeta;
public class ItemStack { public ItemStack(Material m){} public Material getType(){return null;} public ItemMeta getItemMeta(){return null;} public boolean setItemMeta(ItemMeta m){return true;} public ItemStack clone(){return this;} }
