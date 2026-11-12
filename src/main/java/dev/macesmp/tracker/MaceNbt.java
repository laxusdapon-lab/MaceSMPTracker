package dev.macesmp.tracker;

import java.io.*;
import java.util.*;

/**
 * Minimal NBT reader that finds every item stack with id "minecraft:mace" anywhere in a document
 * (inventories, ender chests, shulker boxes, bundles, item entities, item frames, mob equipment, ...)
 * and extracts the tracker id from its custom_data component.
 */
final class MaceNbt {
    interface Sink {
        /** @param idOrNull the tracker id, or null for a Mace without one */
        void mace(UUID idOrNull);
    }

    /** Key under which Bukkit stores our PersistentDataContainer entry inside custom_data. */
    static volatile String idKey = "macesmptracker:mace_id";

    /** Large subtrees that never contain items; skipped without allocating. */
    private static final Set<String> SKIP = Set.of(
            "sections", "Heightmaps", "structures", "PostProcessing", "block_ticks", "fluid_ticks",
            "CarvingMasks", "blending_data", "below_zero_retrogen");

    private MaceNbt() {
    }

    static void scan(InputStream raw, Sink sink) throws IOException {
        DataInputStream in = new DataInputStream(new BufferedInputStream(raw));
        int type = in.readUnsignedByte();
        if (type == 0) return;
        in.readUTF(); // root name
        readPayload(in, type, sink, 0);
    }

    private static Object readPayload(DataInputStream in, int type, Sink sink, int depth) throws IOException {
        if (depth > 512) throw new IOException("NBT nested too deeply");
        switch (type) {
            case 1: return in.readByte();
            case 2: return in.readShort();
            case 3: return in.readInt();
            case 4: return in.readLong();
            case 5: return in.readFloat();
            case 6: return in.readDouble();
            case 7: {
                int n = in.readInt();
                skipFully(in, n);
                return null;
            }
            case 8: return in.readUTF();
            case 9: {
                int elementType = in.readUnsignedByte();
                int n = in.readInt();
                if (n < 0 || (elementType == 0 && n > 0)) throw new IOException("bad NBT list");
                List<Object> list = new ArrayList<>(Math.min(n, 256));
                for (int i = 0; i < n; i++) list.add(readPayload(in, elementType, sink, depth + 1));
                return list;
            }
            case 10: {
                Map<String, Object> map = new HashMap<>();
                while (true) {
                    int t = in.readUnsignedByte();
                    if (t == 0) break;
                    String name = in.readUTF();
                    if (SKIP.contains(name)) {
                        skipPayload(in, t, depth + 1);
                        continue;
                    }
                    map.put(name, readPayload(in, t, sink, depth + 1));
                }
                inspect(map, sink);
                return map;
            }
            case 11: {
                int n = in.readInt();
                skipFully(in, 4L * n);
                return null;
            }
            case 12: {
                int n = in.readInt();
                skipFully(in, 8L * n);
                return null;
            }
            default:
                throw new IOException("unknown NBT tag " + type);
        }
    }

    private static void skipPayload(DataInputStream in, int type, int depth) throws IOException {
        if (depth > 512) throw new IOException("NBT nested too deeply");
        switch (type) {
            case 1: skipFully(in, 1); break;
            case 2: skipFully(in, 2); break;
            case 3: case 5: skipFully(in, 4); break;
            case 4: case 6: skipFully(in, 8); break;
            case 7: skipFully(in, in.readInt()); break;
            case 8: skipFully(in, in.readUnsignedShort()); break;
            case 9: {
                int et = in.readUnsignedByte();
                int n = in.readInt();
                if (n < 0 || (et == 0 && n > 0)) throw new IOException("bad NBT list");
                for (int i = 0; i < n; i++) skipPayload(in, et, depth + 1);
                break;
            }
            case 10: {
                while (true) {
                    int t = in.readUnsignedByte();
                    if (t == 0) break;
                    skipFully(in, in.readUnsignedShort());
                    skipPayload(in, t, depth + 1);
                }
                break;
            }
            case 11: skipFully(in, 4L * in.readInt()); break;
            case 12: skipFully(in, 8L * in.readInt()); break;
            default: throw new IOException("unknown NBT tag " + type);
        }
    }

    private static void skipFully(DataInputStream in, long n) throws IOException {
        if (n < 0) throw new IOException("negative length");
        while (n > 0) {
            long s = in.skip(n);
            if (s <= 0) {
                if (in.read() < 0) throw new EOFException();
                s = 1;
            }
            n -= s;
        }
    }

    @SuppressWarnings("unchecked")
    private static void inspect(Map<String, Object> map, Sink sink) {
        if (!"minecraft:mace".equals(map.get("id"))) return;
        UUID id = null;
        Object comps = map.get("components");
        if (comps instanceof Map<?, ?> cm) {
            Object cd = ((Map<String, Object>) cm).get("minecraft:custom_data");
            if (cd instanceof Map<?, ?> cdm) {
                Object pbv = ((Map<String, Object>) cdm).get("PublicBukkitValues");
                if (pbv instanceof Map<?, ?> pm) {
                    Object v = ((Map<String, Object>) pm).get(idKey);
                    if (v instanceof String s) {
                        try {
                            id = UUID.fromString(s);
                        } catch (IllegalArgumentException ignored) {
                            // malformed id: treated as untagged
                        }
                    }
                }
            }
        }
        sink.mace(id);
    }
}
