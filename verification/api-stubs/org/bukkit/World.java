package org.bukkit; import java.io.File;
public interface World { Chunk[] getLoadedChunks(); File getWorldFolder(); String getName(); void save(); }
