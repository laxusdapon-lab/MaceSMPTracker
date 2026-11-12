package org.bukkit.event.inventory; import java.util.List; import org.bukkit.event.Event; import org.bukkit.entity.HumanEntity; import org.bukkit.inventory.CraftingInventory;
public class PrepareItemCraftEvent extends Event { public CraftingInventory getInventory(){return null;} public boolean isRepair(){return false;} public List<HumanEntity> getViewers(){return null;} }
