package dev.macesmp.tracker;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.*;

/** /macesmp limit|status|recount|reload|list */
final class MaceCommand implements CommandExecutor, TabCompleter {
    static final String PERMISSION = "macesmp.admin";

    private final MaceSMPTracker plugin;
    private final TrackerService svc;
    private final AuditRunner audit;

    MaceCommand(MaceSMPTracker plugin, TrackerService svc, AuditRunner audit) {
        this.plugin = plugin;
        this.svc = svc;
        this.audit = audit;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission(PERMISSION)) {
            sender.sendMessage("\u00a7cYou do not have permission to use this command.");
            return true;
        }
        if (args.length == 0) {
            help(sender, label);
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "limit":
                limit(sender, args);
                return true;
            case "status":
                status(sender);
                return true;
            case "recount":
                audit.start(sender);
                return true;
            case "reload":
                reload(sender);
                return true;
            case "list":
                list(sender);
                return true;
            default:
                help(sender, label);
                return true;
        }
    }

    private void help(CommandSender s, String label) {
        svc.reply(s, "\u00a76MaceSMPTracker commands:");
        s.sendMessage("\u00a7e/" + label + " status \u00a77- active Maces and the limit");
        s.sendMessage("\u00a7e/" + label + " limit <number|reset> \u00a77- change the global limit (default " + Settings.DEFAULT_LIMIT + ")");
        s.sendMessage("\u00a7e/" + label + " recount \u00a77- full audit of players, chunks and world files");
        s.sendMessage("\u00a7e/" + label + " list \u00a77- every active Mace and where it was last seen");
        s.sendMessage("\u00a7e/" + label + " reload \u00a77- reload config.yml");
    }

    private void limit(CommandSender sender, String[] args) {
        if (args.length < 2) {
            svc.reply(sender, "Current limit: \u00a7f" + svc.settings.limit + "\u00a77 (active: " + svc.registry.activeCount() + "). Usage: /macesmp limit <number|reset>");
            return;
        }
        int value;
        if (args[1].equalsIgnoreCase("reset") || args[1].equalsIgnoreCase("default")) {
            value = Settings.DEFAULT_LIMIT;
        } else {
            try {
                value = Integer.parseInt(args[1]);
            } catch (NumberFormatException e) {
                svc.reply(sender, "\u00a7c'" + args[1] + "' is not a whole number.");
                return;
            }
            if (value < 0 || value > 1000) {
                svc.reply(sender, "\u00a7cThe limit must be between 0 and 1000.");
                return;
            }
        }
        svc.setLimitAndSave(value);
        int active = svc.registry.activeCount();
        svc.reply(sender, "Global Mace limit set to \u00a7f" + value + "\u00a77 and saved to config.yml. Active: " + active + "/" + value + ".");
        if (active > value) {
            svc.reply(sender, "\u00a7eThere are already more Maces than the new limit. None are removed, but nobody can craft another until the count drops below " + value + ".");
        }
        svc.log.info(sender.getName() + " set the Mace limit to " + value);
    }

    private void status(CommandSender sender) {
        Settings s = svc.settings;
        int active = svc.registry.activeCount();
        svc.reply(sender, "\u00a76Mace status");
        sender.sendMessage("\u00a77Active Maces: \u00a7f" + active + "/" + s.limit + (active >= s.limit ? " \u00a7c(limit reached)" : " \u00a7a(" + (s.limit - active) + " free)"));
        Map<String, Integer> byType = svc.registry.activeByLocationType();
        if (!byType.isEmpty()) sender.sendMessage("\u00a77Last known places: \u00a7f" + byType);
        sender.sendMessage("\u00a77Block crafter: \u00a7f" + s.blockCrafter + "\u00a77, untracked policy: \u00a7f" + s.untracked
                + "\u00a77, free missing slots: \u00a7f" + s.auditFreeMissing);
        sender.sendMessage("\u00a77Datapack: " + (svc.bridge.datapackDetected() ? "\u00a7adetected" : "\u00a7cnot detected"));
        sender.sendMessage("\u00a77Last sweep: \u00a78" + svc.lastSweep);
        sender.sendMessage("\u00a77Last audit: \u00a78" + svc.lastAudit);
    }

    private void reload(CommandSender sender) {
        try {
            plugin.reloadConfig();
            Settings fresh = Settings.load(plugin.getConfig());
            svc.reloadSettings(fresh);
            plugin.scheduleTasks();
            svc.reply(sender, "Configuration reloaded. Limit: \u00a7f" + fresh.limit + "\u00a77, active: " + svc.registry.activeCount() + ".");
            for (String w : fresh.warnings) sender.sendMessage("\u00a7e" + w);
        } catch (RuntimeException e) {
            svc.reply(sender, "\u00a7cReload failed, the previous settings stay active: " + e);
            svc.log.warning("Reload failed: " + e);
        }
    }

    private void list(CommandSender sender) {
        List<MaceRecord> active = svc.registry.activeSnapshot();
        svc.reply(sender, "\u00a76Active Maces (" + active.size() + "/" + svc.settings.limit + ")");
        long now = System.currentTimeMillis();
        for (MaceRecord r : active) {
            long hours = Math.max(0, (now - r.created) / 3_600_000L);
            sender.sendMessage("\u00a7e" + TrackerService.shortId(r.id) + " \u00a77forged by " + r.crafterName + " " + hours + "h ago, last seen: " + r.lastLocation);
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission(PERMISSION)) return List.of();
        if (args.length == 1) return filter(List.of("limit", "status", "recount", "reload", "list"), args[0]);
        if (args.length == 2 && args[0].equalsIgnoreCase("limit")) return filter(List.of("reset", "3", "5"), args[1]);
        return List.of();
    }

    private static List<String> filter(List<String> all, String prefix) {
        List<String> out = new ArrayList<>();
        for (String a : all) if (a.startsWith(prefix.toLowerCase(Locale.ROOT))) out.add(a);
        return out;
    }
}
