package org.bukkit.plugin.java; import java.io.File; import java.util.logging.Logger; import org.bukkit.command.PluginCommand; import org.bukkit.configuration.file.FileConfiguration; import org.bukkit.plugin.Plugin;
public abstract class JavaPlugin implements Plugin {
 public void onEnable(){} public void onDisable(){} public Logger getLogger(){return null;} public FileConfiguration getConfig(){return null;}
 public void saveConfig(){} public void saveDefaultConfig(){} public void reloadConfig(){} public File getDataFolder(){return null;}
 public PluginCommand getCommand(String n){return null;} public String getName(){return "";} }
