package dev.macesmp.tracker;

import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;

public final class MaceSMPTracker extends JavaPlugin {
    private TrackerService service;
    private Sweeper sweeper;
    private BukkitTask saveTask;
    private BukkitTask sweepTask;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        Settings settings = Settings.load(getConfig());
        for (String w : settings.warnings) getLogger().warning("config.yml: " + w);

        MaceItems items = new MaceItems(this);
        Path file = getDataFolder().toPath().resolve("registry.tsv");
        List<String> problems = new ArrayList<>();
        MaceRegistry registry = MaceRegistry.load(file, problems);
        for (String p : problems) getLogger().severe(p);
        if (registry == null) {
            registry = new MaceRegistry();
            getLogger().info("No registry found, starting a new one.");
        } else if (!problems.isEmpty() && registry.totalCount() == 0) {
            getLogger().severe("The registry could not be read and was replaced by an empty one. "
                    + "Run /macesmp recount: Maces that still carry their id are re-adopted automatically.");
        } else {
            getLogger().info("Loaded " + registry.activeCount() + " active Mace(s) from the registry.");
        }

        DatapackBridge bridge = new DatapackBridge(getLogger());
        service = new TrackerService(this, registry, items, bridge, file, settings);
        sweeper = new Sweeper(service);
        AuditRunner audit = new AuditRunner(service, sweeper);

        Bukkit.getPluginManager().registerEvents(new CraftListener(service), this);
        Bukkit.getPluginManager().registerEvents(new LifecycleListener(service, sweeper), this);

        PluginCommand cmd = getCommand("macesmp");
        if (cmd != null) {
            MaceCommand handler = new MaceCommand(this, service, audit);
            cmd.setExecutor(handler);
            cmd.setTabCompleter(handler);
        }

        scheduleTasks();
        // Let the datapack finish loading before the first sync so it can tell whether the datapack is present.
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (!bridge.datapackDetected()) {
                getLogger().warning("The Mace datapack was not detected. The plugin enforces the limit on its own, "
                        + "but the recipe book will not hide the Mace when the limit is reached. Install mace_datapack_26.3.zip.");
            }
            service.changed();
        }, 100L);
        service.changed();
        getLogger().info("MaceSMPTracker enabled. Limit " + settings.limit + ", active " + registry.activeCount() + ".");
    }

    /** (Re)creates the repeating tasks from the current settings. */
    void scheduleTasks() {
        if (saveTask != null) saveTask.cancel();
        if (sweepTask != null) sweepTask.cancel();
        Settings s = service.settings;
        if (s.saveAutomatically) {
            long ticks = s.saveIntervalSeconds * 20L;
            saveTask = Bukkit.getScheduler().runTaskTimer(this, () -> {
                service.registry.prune(s.pruneDays * 86_400_000L, System.currentTimeMillis());
                service.saveAsyncIfDirty();
            }, ticks, ticks);
        } else {
            saveTask = null;
        }
        if (s.sweepIntervalSeconds > 0) {
            long ticks = s.sweepIntervalSeconds * 20L;
            sweepTask = Bukkit.getScheduler().runTaskTimer(this, () -> {
                if (!sweeper.isRunning()) sweeper.start(new ArrayList<>(Bukkit.getOnlinePlayers()), true, r -> { });
            }, ticks, ticks);
        } else {
            sweepTask = null;
        }
    }

    @Override
    public void onDisable() {
        if (service == null) return;
        try {
            service.saveNow();
            service.bridge.pluginOff();
        } catch (RuntimeException e) {
            getLogger().log(Level.SEVERE, "Could not save the Mace registry on shutdown: " + e, e);
        }
    }
}
