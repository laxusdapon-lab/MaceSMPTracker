package org.bukkit; import java.util.*; import org.bukkit.command.*; import org.bukkit.entity.Player; import org.bukkit.scheduler.BukkitScheduler; import org.bukkit.scoreboard.ScoreboardManager; import org.bukkit.plugin.PluginManager;
public final class Bukkit {
 public static BukkitScheduler getScheduler(){return null;} public static List<World> getWorlds(){return null;}
 public static Collection<? extends Player> getOnlinePlayers(){return null;} public static ScoreboardManager getScoreboardManager(){return null;}
 public static boolean dispatchCommand(CommandSender s,String c){return true;} public static ConsoleCommandSender getConsoleSender(){return null;}
 public static int broadcastMessage(String m){return 0;} public static void savePlayers(){} public static PluginManager getPluginManager(){return null;} }
