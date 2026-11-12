package org.bukkit; import org.bukkit.block.BlockState; import org.bukkit.entity.Entity;
public interface Chunk { boolean isLoaded(); BlockState[] getTileEntities(); Entity[] getEntities(); }
