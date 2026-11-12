package org.bukkit.plugin; import java.util.logging.Logger; import org.bukkit.configuration.file.FileConfiguration;
public interface Plugin { Logger getLogger(); FileConfiguration getConfig(); void saveConfig(); String getName(); }
