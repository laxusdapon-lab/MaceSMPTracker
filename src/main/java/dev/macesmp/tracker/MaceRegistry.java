package dev.macesmp.tracker;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Persistent registry of every Mace that has ever been issued.
 * ACTIVE records count against the global limit. DESTROYED records are kept for a while so that
 * a "destroyed" Mace that turns up again can be recognised.
 *
 * All methods are synchronized so the file can be serialised from another thread.
 */
final class MaceRegistry {
    private static final String HEADER = "# MaceSMPTracker registry v1";

    private final Map<UUID, MaceRecord> records = new LinkedHashMap<>();
    private boolean dirty;

    synchronized int activeCount() {
        int n = 0;
        for (MaceRecord r : records.values()) if (r.state == MaceRecord.State.ACTIVE) n++;
        return n;
    }

    synchronized int totalCount() {
        return records.size();
    }

    synchronized MaceRecord get(UUID id) {
        return records.get(id);
    }

    synchronized List<MaceRecord> activeSnapshot() {
        List<MaceRecord> out = new ArrayList<>();
        for (MaceRecord r : records.values()) if (r.state == MaceRecord.State.ACTIVE) out.add(r);
        return out;
    }

    /** @return false if the id is already known */
    synchronized boolean register(UUID id, String crafterUuid, String crafterName, long now, String where) {
        if (records.containsKey(id)) return false;
        MaceRecord r = new MaceRecord(id);
        r.created = now;
        r.crafterUuid = clean(crafterUuid);
        r.crafterName = clean(crafterName);
        r.lastSeen = now;
        r.lastLocation = clean(where);
        records.put(id, r);
        dirty = true;
        return true;
    }

    /** @return true if the record went from ACTIVE to DESTROYED */
    synchronized boolean markDestroyed(UUID id, String reason, long now) {
        MaceRecord r = records.get(id);
        if (r == null || r.state != MaceRecord.State.ACTIVE) return false;
        r.state = MaceRecord.State.DESTROYED;
        r.destroyed = now;
        r.destroyReason = clean(reason);
        dirty = true;
        return true;
    }

    /** @return true if the record went from DESTROYED to ACTIVE */
    synchronized boolean reactivate(UUID id, long now, String where) {
        MaceRecord r = records.get(id);
        if (r == null || r.state != MaceRecord.State.DESTROYED) return false;
        r.state = MaceRecord.State.ACTIVE;
        r.destroyed = 0;
        r.destroyReason = "";
        r.missedAudits = 0;
        r.lastSeen = now;
        r.lastLocation = clean(where);
        dirty = true;
        return true;
    }

    synchronized void touch(UUID id, String where, long now) {
        MaceRecord r = records.get(id);
        if (r == null) return;
        r.lastSeen = now;
        r.lastLocation = clean(where);
        r.missedAudits = 0;
        dirty = true;
    }

    synchronized int addMissed(UUID id) {
        MaceRecord r = records.get(id);
        if (r == null) return 0;
        r.missedAudits++;
        dirty = true;
        return r.missedAudits;
    }

    synchronized void resetMissed(UUID id) {
        MaceRecord r = records.get(id);
        if (r != null && r.missedAudits != 0) {
            r.missedAudits = 0;
            dirty = true;
        }
    }

    /** Removes DESTROYED records older than the given age. */
    synchronized int prune(long maxAgeMillis, long now) {
        int removed = 0;
        Iterator<MaceRecord> it = records.values().iterator();
        while (it.hasNext()) {
            MaceRecord r = it.next();
            if (r.state == MaceRecord.State.DESTROYED && r.destroyed > 0 && now - r.destroyed > maxAgeMillis) {
                it.remove();
                removed++;
            }
        }
        if (removed > 0) dirty = true;
        return removed;
    }

    synchronized Map<String, Integer> activeByLocationType() {
        Map<String, Integer> out = new TreeMap<>();
        for (MaceRecord r : records.values()) {
            if (r.state != MaceRecord.State.ACTIVE) continue;
            String loc = r.lastLocation == null ? "unknown" : r.lastLocation;
            int c = loc.indexOf(':');
            String type = c > 0 ? loc.substring(0, c) : loc;
            out.merge(type, 1, Integer::sum);
        }
        return out;
    }

    synchronized boolean consumeDirty() {
        boolean d = dirty;
        dirty = false;
        return d;
    }

    synchronized void markDirty() {
        dirty = true;
    }

    synchronized String serialize() {
        StringBuilder sb = new StringBuilder(HEADER).append('\n');
        for (MaceRecord r : records.values()) {
            sb.append("M\t").append(r.id)
                    .append('\t').append(r.state)
                    .append('\t').append(r.created)
                    .append('\t').append(r.crafterUuid)
                    .append('\t').append(r.crafterName)
                    .append('\t').append(r.lastSeen)
                    .append('\t').append(r.lastLocation)
                    .append('\t').append(r.missedAudits)
                    .append('\t').append(r.destroyed)
                    .append('\t').append(r.destroyReason)
                    .append('\n');
        }
        return sb.toString();
    }

    /** @throws IllegalArgumentException when the text is not a valid registry */
    static MaceRegistry parse(String text) {
        MaceRegistry reg = new MaceRegistry();
        String[] lines = text.split("\n");
        if (lines.length == 0 || !lines[0].startsWith("# MaceSMPTracker registry")) {
            throw new IllegalArgumentException("missing registry header");
        }
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            if (line.isBlank() || line.startsWith("#")) continue;
            String[] f = line.split("\t", -1);
            if (f.length < 11 || !f[0].equals("M")) {
                throw new IllegalArgumentException("bad line " + (i + 1));
            }
            try {
                MaceRecord r = new MaceRecord(UUID.fromString(f[1]));
                r.state = MaceRecord.State.valueOf(f[2]);
                r.created = Long.parseLong(f[3]);
                r.crafterUuid = f[4];
                r.crafterName = f[5];
                r.lastSeen = Long.parseLong(f[6]);
                r.lastLocation = f[7];
                r.missedAudits = Integer.parseInt(f[8]);
                r.destroyed = Long.parseLong(f[9]);
                r.destroyReason = f[10];
                reg.records.put(r.id, r);
            } catch (RuntimeException e) {
                throw new IllegalArgumentException("bad line " + (i + 1) + ": " + e.getMessage());
            }
        }
        return reg;
    }

    /** Loads the registry, falling back to the .bak copy. Returns null when no file exists at all. */
    static MaceRegistry load(Path file, List<String> problems) {
        Path bak = file.resolveSibling(file.getFileName() + ".bak");
        boolean any = false;
        for (Path p : new Path[]{file, bak}) {
            if (!Files.exists(p)) continue;
            any = true;
            try {
                MaceRegistry reg = parse(Files.readString(p, StandardCharsets.UTF_8));
                if (p == bak) problems.add("Main registry unreadable, restored from " + bak.getFileName());
                return reg;
            } catch (IOException | RuntimeException e) {
                problems.add("Could not read " + p.getFileName() + ": " + e.getMessage());
            }
        }
        return any ? new MaceRegistry() : null;
    }

    /** Writes to a temp file, keeps the previous file as .bak, then atomically moves the new file into place. */
    static void writeAtomically(Path file, String content) throws IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, content, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        if (Files.exists(file)) {
            Files.copy(file, file.resolveSibling(file.getFileName() + ".bak"), StandardCopyOption.REPLACE_EXISTING);
        }
        try {
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String clean(String s) {
        if (s == null) return "";
        return s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
    }
}
