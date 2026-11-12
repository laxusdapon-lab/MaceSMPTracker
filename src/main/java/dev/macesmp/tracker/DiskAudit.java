package dev.macesmp.tracker;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;

/** Reads offline player data and region/entity files from disk. Safe to run off the main thread. */
final class DiskAudit {
    static final class Result {
        final Map<UUID, String> found = new HashMap<>();
        int untagged;
        int playerFiles;
        int regionFiles;
        int chunks;
        int unsupported;
        int errors;
        final List<String> notes = new ArrayList<>();

        /** True only if every file could be read completely. Required before anything may be freed. */
        boolean complete() {
            return unsupported == 0 && errors == 0;
        }
    }

    private DiskAudit() {
    }

    static Result run(List<Path> playerDirs, List<Path> regionDirs, boolean scanPlayers, boolean scanRegions) {
        Result res = new Result();
        if (scanPlayers) {
            if (playerDirs.isEmpty()) {
                res.errors++;
                res.notes.add("No player data folder found");
            }
            for (Path dir : playerDirs) {
                for (Path f : list(dir, ".dat", res)) {
                    res.playerFiles++;
                    String where = "disk:player:" + f.getFileName();
                    try (InputStream in = new GZIPInputStream(Files.newInputStream(f))) {
                        MaceNbt.scan(in, sink(res, where));
                    } catch (IOException | RuntimeException e) {
                        res.errors++;
                        res.notes.add("Unreadable player file " + f.getFileName());
                    }
                }
            }
        }
        if (scanRegions) {
            if (regionDirs.isEmpty()) {
                res.errors++;
                res.notes.add("No region folders found");
            }
            for (Path dir : regionDirs) {
                for (Path f : list(dir, ".mca", res)) {
                    res.regionFiles++;
                    String where = "disk:" + dir.getFileName() + ":" + f.getFileName();
                    RegionScanner.Result rr = RegionScanner.scan(f, sink(res, where));
                    res.chunks += rr.chunks;
                    res.unsupported += rr.unsupported;
                    res.errors += rr.errors;
                    if (rr.unsupported > 0) res.notes.add(rr.unsupported + " unsupported chunk(s) in " + f.getFileName());
                    if (rr.errors > 0) res.notes.add(rr.errors + " read error(s) in " + f.getFileName());
                }
            }
        }
        return res;
    }

    private static MaceNbt.Sink sink(Result res, String where) {
        return id -> {
            if (id == null) res.untagged++;
            else res.found.putIfAbsent(id, where);
        };
    }

    private static List<Path> list(Path dir, String suffix, Result res) {
        List<Path> out = new ArrayList<>();
        try (Stream<Path> s = Files.list(dir)) {
            s.filter(p -> Files.isRegularFile(p) && p.getFileName().toString().endsWith(suffix)).forEach(out::add);
        } catch (IOException e) {
            res.errors++;
            res.notes.add("Cannot list " + dir);
        }
        out.sort(Comparator.comparing(Path::toString));
        return out;
    }

    /** Finds playerdata folders by walking up from a world folder (handles the pre-26.1 and 26.1+ layouts). */
    static List<Path> findPlayerDirs(Path worldFolder) {
        Set<Path> out = new LinkedHashSet<>();
        Path p = worldFolder.toAbsolutePath().normalize();
        for (int i = 0; i < 6 && p != null; i++, p = p.getParent()) {
            Path legacy = p.resolve("playerdata");
            if (Files.isDirectory(legacy)) out.add(legacy);
            Path modern = p.resolve("players").resolve("data");
            if (Files.isDirectory(modern)) out.add(modern);
        }
        return new ArrayList<>(out);
    }

    /** Finds region and entities folders below a world/dimension folder. */
    static List<Path> findRegionDirs(Path worldFolder) {
        Set<Path> out = new LinkedHashSet<>();
        Path root = worldFolder.toAbsolutePath().normalize();
        try (Stream<Path> s = Files.walk(root, 4)) {
            s.filter(Files::isDirectory).forEach(d -> {
                String n = d.getFileName() == null ? "" : d.getFileName().toString();
                if (n.equals("region") || n.equals("entities")) out.add(d);
            });
        } catch (IOException ignored) {
            // an unreadable tree simply yields fewer folders; the audit is then reported as incomplete
        }
        return new ArrayList<>(out);
    }
}
