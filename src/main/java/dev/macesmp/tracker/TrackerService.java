package dev.macesmp.tracker;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.io.IOException;
import java.nio.file.Path;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Shared state and the few operations every listener needs. Everything here runs on the main thread. */
final class TrackerService {
    final Plugin plugin;
    final Logger log;
    final MaceRegistry registry;
    final MaceItems items;
    final DatapackBridge bridge;
    final Path registryFile;
    volatile Settings settings;

    String lastSweep = "never";
    String lastAudit = "never";

    private final Object fileLock = new Object();

    TrackerService(Plugin plugin, MaceRegistry registry, MaceItems items, DatapackBridge bridge, Path registryFile, Settings settings) {
        this.plugin = plugin;
        this.log = plugin.getLogger();
        this.registry = registry;
        this.items = items;
        this.bridge = bridge;
        this.registryFile = registryFile;
        this.settings = settings;
    }

    boolean isFull() {
        return registry.activeCount() >= settings.limit;
    }

    String format(String template) {
        return template.replace("%active%", Integer.toString(registry.activeCount()))
                .replace("%limit%", Integer.toString(settings.limit));
    }

    /** Call after any change to the number of active Maces or to the limit. */
    void changed() {
        bridge.sync(registry.activeCount(), settings.limit);
    }

    void reloadSettings(Settings fresh) {
        this.settings = fresh;
        for (String w : fresh.warnings) log.warning("config.yml: " + w);
        changed();
    }

    void setLimitAndSave(int limit) {
        plugin.getConfig().set("global-mace-limit", limit);
        plugin.saveConfig();
        reloadSettings(Settings.load(plugin.getConfig()));
    }

    // ---------------------------------------------------------------- crafting

    void registerCrafted(Player p, UUID id) {
        long now = System.currentTimeMillis();
        if (!registry.register(id, p.getUniqueId().toString(), p.getName(), now, "player:" + p.getName())) return;
        changed();
        log.info(p.getName() + " forged Mace " + shortId(id) + " (" + registry.activeCount() + "/" + settings.limit + ")");
        if (settings.announceCrafted) broadcast(settings.msgCrafted);
        // If the craft was cancelled by another plugin after our MONITOR handler, release the slot again.
        Bukkit.getScheduler().runTaskLater(plugin, () -> verifyCrafted(p, id), 3L);
    }

    private void verifyCrafted(Player p, UUID id) {
        MaceRecord r = registry.get(id);
        if (r == null || r.state != MaceRecord.State.ACTIVE) return;
        if (!p.isOnline()) return; // cannot tell; the audit will sort it out
        if (holds(p, id)) return;
        for (Entity e : p.getNearbyEntities(8, 8, 8)) {
            if (e instanceof Item it && id.equals(items.getId(it.getItemStack()))) return;
        }
        destroyed(id, "CRAFT_NOT_COMPLETED", false);
    }

    private boolean holds(Player p, UUID id) {
        for (ItemStack s : p.getInventory().getContents()) {
            if (s != null && id.equals(items.getId(s))) return true;
        }
        return id.equals(items.getId(p.getItemOnCursor()));
    }

    // ---------------------------------------------------------------- destruction

    void destroyed(UUID id, String reason, boolean announce) {
        if (!registry.markDestroyed(id, reason, System.currentTimeMillis())) return;
        changed();
        log.info("Mace " + shortId(id) + " destroyed (" + reason + "). Active: " + registry.activeCount() + "/" + settings.limit);
        if (announce && settings.announceDestroyed) broadcast(settings.msgDestroyed);
    }

    // ---------------------------------------------------------------- messaging

    void broadcast(String template) {
        Bukkit.broadcastMessage(settings.prefix + format(template));
    }

    /** Logs a problem and tells online admins. */
    void warn(String message) {
        log.warning(message);
        if (!settings.notifyAdmins) return;
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.hasPermission("macesmp.admin")) p.sendMessage(settings.prefix + "\u00a7e" + message);
        }
    }

    void reply(CommandSender to, String message) {
        to.sendMessage(settings.prefix + message);
    }

    static String shortId(UUID id) {
        return id.toString().substring(0, 8);
    }

    // ---------------------------------------------------------------- persistence

    /** Writes the registry on a worker thread if anything changed. */
    void saveAsyncIfDirty() {
        if (!registry.consumeDirty()) return;
        final String data = registry.serialize();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> write(data));
    }

    /** Synchronous save, used on shutdown and after audits. */
    void saveNow() {
        registry.consumeDirty();
        write(registry.serialize());
    }

    private void write(String data) {
        synchronized (fileLock) {
            try {
                MaceRegistry.writeAtomically(registryFile, data);
            } catch (IOException e) {
                registry.markDirty();
                log.log(Level.SEVERE, "Could not save the Mace registry (" + registryFile + "): " + e);
            }
        }
    }
}
