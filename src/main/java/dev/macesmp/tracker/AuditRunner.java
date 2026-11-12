package dev.macesmp.tracker;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.CommandSender;

import java.nio.file.Path;
import java.util.*;

/**
 * /macesmp recount: a live sweep of everything reachable through the API PLUS a read-only scan of the
 * files on disk (offline player inventories and ender chests, and every region/entities file).
 * Anything found anywhere counts as "present". A registry slot is only ever freed because it is
 * missing if EVERY file could be read, the admin enabled audit.free-missing-slots, and the Mace was
 * missing in several audits in a row.
 */
final class AuditRunner {
    private final TrackerService svc;
    private final Sweeper sweeper;
    private boolean running;

    AuditRunner(TrackerService svc, Sweeper sweeper) {
        this.svc = svc;
        this.sweeper = sweeper;
    }

    boolean isRunning() {
        return running;
    }

    void start(CommandSender requester) {
        if (running || sweeper.isRunning()) {
            svc.reply(requester, "\u00a7cA sweep or audit is already running. Try again in a moment.");
            return;
        }
        Settings s = svc.settings;
        running = true;
        svc.reply(requester, "Audit started. This can take a while on big worlds; the server keeps running.");
        if (s.auditSaveFirst) {
            Bukkit.savePlayers();
            for (World w : Bukkit.getWorlds()) w.save();
        }
        List<Path> worldFolders = new ArrayList<>();
        for (World w : Bukkit.getWorlds()) worldFolders.add(w.getWorldFolder().toPath());

        boolean started = sweeper.start(new ArrayList<>(Bukkit.getOnlinePlayers()), true, live -> {
            Bukkit.getScheduler().runTaskAsynchronously(svc.plugin, () -> {
                DiskAudit.Result disk;
                try {
                    List<Path> playerDirs = worldFolders.isEmpty() ? List.of() : DiskAudit.findPlayerDirs(worldFolders.get(0));
                    Set<Path> regionDirs = new LinkedHashSet<>();
                    for (Path wf : worldFolders) regionDirs.addAll(DiskAudit.findRegionDirs(wf));
                    disk = DiskAudit.run(playerDirs, new ArrayList<>(regionDirs), s.auditScanPlayers, s.auditScanRegions);
                } catch (Throwable t) {
                    disk = new DiskAudit.Result();
                    disk.errors++;
                    disk.notes.add("Disk scan crashed: " + t);
                }
                final DiskAudit.Result result = disk;
                Bukkit.getScheduler().runTask(svc.plugin, () -> finish(requester, live, result));
            });
        });
        if (!started) {
            running = false;
            svc.reply(requester, "\u00a7cCould not start the audit.");
        }
    }

    private void finish(CommandSender requester, Sweeper.Result live, DiskAudit.Result disk) {
        try {
            Settings s = svc.settings;
            MaceRegistry reg = svc.registry;
            long now = System.currentTimeMillis();
            int adoptedFromDisk = 0;
            int reactivatedFromDisk = 0;
            int unknownOnDisk = 0;

            // Maces on disk that the registry does not know about, or that were marked destroyed.
            for (Map.Entry<UUID, String> en : disk.found.entrySet()) {
                UUID id = en.getKey();
                MaceRecord rec = reg.get(id);
                if (rec == null) {
                    if (s.untracked == Settings.UntrackedPolicy.REGISTER) {
                        if (reg.register(id, "adopted", "adopted", now, en.getValue())) adoptedFromDisk++;
                    } else {
                        unknownOnDisk++;
                    }
                } else if (rec.state == MaceRecord.State.DESTROYED && s.zombie == Settings.ZombiePolicy.REACTIVATE) {
                    if (reg.reactivate(id, now, en.getValue())) reactivatedFromDisk++;
                }
            }

            boolean mayFree = s.auditFreeMissing && s.auditScanPlayers && s.auditScanRegions && disk.complete();
            int present = 0;
            int missing = 0;
            int freed = 0;
            List<String> missingList = new ArrayList<>();
            for (MaceRecord rec : reg.activeSnapshot()) {
                UUID id = rec.id;
                if (live.seenIds.contains(id)) {
                    present++;
                } else if (disk.found.containsKey(id)) {
                    reg.touch(id, disk.found.get(id), now);
                    present++;
                } else {
                    missing++;
                    missingList.add(TrackerService.shortId(id) + " (last: " + rec.lastLocation + ")");
                    if (mayFree && now - rec.created > 10L * 60_000L) {
                        int m = reg.addMissed(id);
                        if (m >= s.auditMissedNeeded && reg.markDestroyed(id, "AUDIT_MISSING", now)) freed++;
                    }
                }
            }
            reg.prune(s.pruneDays * 86_400_000L, now);
            svc.changed();
            svc.saveNow();

            List<String> out = new ArrayList<>();
            out.add("\u00a76Audit finished.");
            out.add("Active: \u00a7f" + reg.activeCount() + "/" + s.limit + "\u00a77, present: " + present + ", not found: " + missing);
            out.add("Live: " + live.players + " online player(s), " + live.chunks + " loaded chunk(s), "
                    + live.duplicatesRemoved + " duplicate(s) removed, " + live.adopted + " adopted, "
                    + live.untaggedRemoved + " untracked removed, " + live.untaggedIgnored + " untracked ignored");
            out.add("Disk: " + disk.playerFiles + " player file(s), " + disk.regionFiles + " region file(s), "
                    + disk.chunks + " chunk(s), " + disk.found.size() + " tracked Mace(s) found"
                    + (disk.untagged > 0 ? ", " + disk.untagged + " untracked on disk (cannot be removed offline)" : ""));
            if (adoptedFromDisk + reactivatedFromDisk + unknownOnDisk > 0) {
                out.add("Disk registry fixes: " + adoptedFromDisk + " adopted, " + reactivatedFromDisk + " counted active again, "
                        + unknownOnDisk + " unknown (ignored by policy)");
            }
            if (!disk.complete()) {
                out.add("\u00a7eThe disk scan was NOT complete (" + disk.errors + " error(s), " + disk.unsupported
                        + " unsupported chunk(s)). No slot will be freed from this audit.");
                for (String n : disk.notes.subList(0, Math.min(5, disk.notes.size()))) out.add("\u00a78 - " + n);
            }
            if (missing > 0) {
                out.add("\u00a7eNot found anywhere: " + String.join(", ", missingList.subList(0, Math.min(8, missingList.size())))
                        + (missingList.size() > 8 ? " ..." : ""));
                if (!s.auditFreeMissing) {
                    out.add("\u00a77They stay counted because audit.free-missing-slots is false (they may sit in another plugin's storage).");
                } else if (freed > 0) {
                    out.add("\u00a7aFreed " + freed + " slot(s) after repeated misses.");
                } else if (mayFree) {
                    out.add("\u00a77They will be freed if they are missing in " + s.auditMissedNeeded + " audits in a row.");
                }
            }
            for (String line : out) svc.reply(requester, line);
            svc.lastAudit = new Date() + " - " + present + " present, " + missing + " not found, " + freed + " freed";
        } catch (Throwable t) {
            svc.log.log(java.util.logging.Level.SEVERE, "Audit failed: " + t, t);
            svc.reply(requester, "\u00a7cThe audit failed: " + t + " (see console)");
        } finally {
            running = false;
        }
    }
}
