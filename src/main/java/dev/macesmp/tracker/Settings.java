package dev.macesmp.tracker;

import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Immutable-ish snapshot of config.yml. Re-created on /macesmp reload. */
final class Settings {
    enum UntrackedPolicy { REGISTER, REMOVE, IGNORE }
    enum ZombiePolicy { REACTIVATE, REMOVE }

    int limit;
    boolean blockCrafter;
    boolean trackPlayerInventories;
    boolean trackEnderChests;
    boolean trackContainers;
    boolean trackDroppedItems;
    boolean trackEntities;
    boolean saveAutomatically;
    int saveIntervalSeconds;
    int sweepIntervalSeconds;
    int chunksPerTick;
    UntrackedPolicy untracked;
    ZombiePolicy zombie;
    boolean auditSaveFirst;
    boolean auditScanPlayers;
    boolean auditScanRegions;
    boolean auditFreeMissing;
    int auditMissedNeeded;
    int pruneDays;
    boolean announceCrafted;
    boolean announceDestroyed;
    boolean notifyAdmins;
    String prefix;
    String msgLimitReached;
    String msgShiftClick;
    String msgRepair;
    String msgCrafted;
    String msgDestroyed;

    static final int DEFAULT_LIMIT = 3;

    /** Problems found while reading the config (out-of-range numbers, unknown policy names). */
    final List<String> warnings = new ArrayList<>();

    static Settings load(FileConfiguration c) {
        Settings s = new Settings();
        int rawLimit = c.getInt("global-mace-limit", DEFAULT_LIMIT);
        s.limit = clamp(rawLimit, 0, 1000);
        if (rawLimit != s.limit) s.warnings.add("global-mace-limit must be 0-1000, using " + s.limit);
        s.blockCrafter = c.getBoolean("block-crafter", true);
        s.trackPlayerInventories = c.getBoolean("track-player-inventories", true);
        s.trackEnderChests = c.getBoolean("track-ender-chests", true);
        s.trackContainers = c.getBoolean("track-containers", true);
        s.trackDroppedItems = c.getBoolean("track-dropped-items", true);
        s.trackEntities = c.getBoolean("track-entities", true);
        s.saveAutomatically = c.getBoolean("save-data-automatically", true);
        s.saveIntervalSeconds = clamp(c.getInt("save-interval-seconds", 30), 5, 3600);
        s.sweepIntervalSeconds = clamp(c.getInt("sweep.interval-seconds", 120), 0, 86400);
        s.chunksPerTick = clamp(c.getInt("sweep.chunks-per-tick", 8), 1, 200);
        s.untracked = parseEnum(UntrackedPolicy.class, c.getString("untracked-mace-policy", "REGISTER"), UntrackedPolicy.REGISTER, s.warnings, "untracked-mace-policy");
        s.zombie = parseEnum(ZombiePolicy.class, c.getString("destroyed-mace-reappears", "REACTIVATE"), ZombiePolicy.REACTIVATE, s.warnings, "destroyed-mace-reappears");
        s.auditSaveFirst = c.getBoolean("audit.save-worlds-first", true);
        s.auditScanPlayers = c.getBoolean("audit.scan-offline-players", true);
        s.auditScanRegions = c.getBoolean("audit.scan-region-files", true);
        s.auditFreeMissing = c.getBoolean("audit.free-missing-slots", false);
        s.auditMissedNeeded = clamp(c.getInt("audit.missed-audits-before-free", 2), 1, 100);
        s.pruneDays = clamp(c.getInt("registry.prune-destroyed-after-days", 30), 1, 3650);
        s.announceCrafted = c.getBoolean("announce.mace-crafted", true);
        s.announceDestroyed = c.getBoolean("announce.mace-destroyed", true);
        s.notifyAdmins = c.getBoolean("announce.notify-admins-of-problems", true);
        s.prefix = color(c.getString("messages.prefix", "&8[&6MaceSMP&8] &7"));
        s.msgLimitReached = color(c.getString("messages.limit-reached", "&cThe Mace limit has been reached (%active%/%limit%). A Mace must be destroyed before another can be forged."));
        s.msgShiftClick = color(c.getString("messages.shift-click", "&cMaces cannot be shift-click crafted. Click the result normally."));
        s.msgRepair = color(c.getString("messages.repair", "&cMaces cannot be combined in the crafting grid. Use an anvil to repair them."));
        s.msgCrafted = color(c.getString("messages.crafted", "&6A Mace has been forged! &7(%active%/%limit% in the world)"));
        s.msgDestroyed = color(c.getString("messages.destroyed", "&6A Mace has been destroyed. &7(%active%/%limit% in the world)"));
        return s;
    }

    static String color(String s) {
        return s == null ? "" : s.replace('&', '\u00a7');
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> type, String raw, E fallback, List<String> warnings, String path) {
        if (raw == null) return fallback;
        try {
            return Enum.valueOf(type, raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            warnings.add(path + ": unknown value '" + raw + "', using " + fallback);
            return fallback;
        }
    }
}
