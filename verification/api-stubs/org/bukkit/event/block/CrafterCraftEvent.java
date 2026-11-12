package org.bukkit.event.block; import org.bukkit.event.*; import org.bukkit.inventory.ItemStack;
public class CrafterCraftEvent extends Event implements Cancellable { public ItemStack getResult(){return null;} public boolean isCancelled(){return false;} public void setCancelled(boolean c){} }
