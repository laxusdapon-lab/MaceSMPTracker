package dev.macesmp.tracker;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/** Plain-Java self test of the Bukkit-independent core. Run: java -cp out dev.macesmp.tracker.CoreSelfTest */
public final class CoreSelfTest {
    static int failures;

    static void check(boolean ok, String what) {
        System.out.println((ok ? "PASS  " : "FAIL  ") + what);
        if (!ok) failures++;
    }

    // ---- tiny NBT writer ----
    static final class W {
        final ByteArrayOutputStream bos = new ByteArrayOutputStream();
        final DataOutputStream o = new DataOutputStream(bos);
        W named(int type, String name) throws IOException { o.writeByte(type); o.writeUTF(name); return this; }
        W compound(String name) throws IOException { return named(10, name); }
        W end() throws IOException { o.writeByte(0); return this; }
        W str(String name, String v) throws IOException { named(8, name); o.writeUTF(v); return this; }
        W i(String name, int v) throws IOException { named(3, name); o.writeInt(v); return this; }
        W list(String name, int elemType, int n) throws IOException { named(9, name); o.writeByte(elemType); o.writeInt(n); return this; }
        W intArray(String name, int n) throws IOException { named(11, name); o.writeInt(n); for (int k = 0; k < n; k++) o.writeInt(k); return this; }
        byte[] bytes() { return bos.toByteArray(); }

        /** Writes an item compound body (no name): {id, count, components:{custom_data:{PublicBukkitValues:{key:id}}}} */
        W maceItem(UUID id) throws IOException {
            str("id", "minecraft:mace").i("count", 1);
            if (id != null) {
                compound("components").compound("minecraft:custom_data").compound("PublicBukkitValues")
                        .str(MaceNbt.idKey, id.toString()).end().end().end();
            }
            return this;
        }
    }

    static byte[] chunkNbt(UUID inChest, UUID inShulker, UUID untagged, UUID inItemEntity) throws IOException {
        W w = new W();
        w.compound("");                                   // root
        w.i("xPos", 0).i("zPos", 0);
        w.compound("Heightmaps").intArray("big", 5000).end();   // must be skipped
        w.list("sections", 10, 1).intArray("junk", 100).end();  // must be skipped
        w.list("block_entities", 10, 2);
        // block entity 1: chest with a mace
        w.str("id", "minecraft:chest").list("Items", 10, 1).i("Slot", 3).maceItem(inChest).end();
        w.end();
        // block entity 2: shulker with a mace nested inside a chest slot's container component
        w.str("id", "minecraft:chest").list("Items", 10, 2);
        w.i("Slot", 0).str("id", "minecraft:white_shulker_box").i("count", 1)
                .compound("components").list("minecraft:container", 10, 1).i("slot", 0)
                .compound("item").maceItem(inShulker).end().end().end().end();
        w.i("Slot", 1).maceItem(untagged).end();   // untagged mace
        w.end();
        w.end(); // end of list items for block_entities (second element ends)
        w.end(); // root
        return w.bytes();
    }

    static byte[] entityNbt(UUID itemEntity) throws IOException {
        W w = new W();
        w.compound("");
        w.list("Entities", 10, 1);
        w.str("id", "minecraft:item").compound("Item").maceItem(itemEntity).end();
        w.end();
        w.end();
        return w.bytes();
    }

    static void writeRegion(Path file, byte[]... chunks) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteBuffer header = ByteBuffer.allocate(8192);
        int sector = 2;
        List<byte[]> bodies = new ArrayList<>();
        for (int i = 0; i < chunks.length; i++) {
            ByteArrayOutputStream z = new ByteArrayOutputStream();
            try (DeflaterOutputStream d = new DeflaterOutputStream(z)) { d.write(chunks[i]); }
            byte[] comp = z.toByteArray();
            int total = 5 + comp.length;
            int sectors = (total + 4095) / 4096;
            ByteBuffer b = ByteBuffer.allocate(sectors * 4096);
            b.putInt(comp.length + 1).put((byte) 2).put(comp);
            bodies.add(b.array());
            header.putInt(i * 4, (sector << 8) | sectors);
            sector += sectors;
        }
        out.write(header.array());
        for (byte[] b : bodies) out.write(b);
        Files.write(file, out.toByteArray());
    }

    public static void main(String[] args) throws Exception {
        Path tmp = Files.createTempDirectory("macetest");
        UUID a = UUID.randomUUID(), b = UUID.randomUUID(), c = UUID.randomUUID(), d = UUID.randomUUID(), p1 = UUID.randomUUID();

        // ---------- registry ----------
        MaceRegistry reg = new MaceRegistry();
        check(reg.register(a, "u1", "Steve", 1000, "player:Steve"), "register new id");
        check(!reg.register(a, "u1", "Steve", 1000, "x"), "duplicate register rejected");
        reg.register(b, "u2", "Alex\tTab", 1000, "container:world");
        check(reg.activeCount() == 2, "active count 2");
        check(reg.markDestroyed(b, "DURABILITY_BREAK", 2000), "destroy active");
        check(!reg.markDestroyed(b, "again", 2001), "destroy twice is a no-op");
        check(reg.activeCount() == 1, "slot freed after destroy");
        check(reg.reactivate(b, 3000, "player:Alex"), "reactivate destroyed");
        check(reg.activeCount() == 2, "active again");
        String text = reg.serialize();
        MaceRegistry reg2 = MaceRegistry.parse(text);
        check(reg2.activeCount() == 2 && reg2.get(a).crafterName.equals("Steve"), "serialize/parse roundtrip");
        check(reg2.get(b).crafterName.equals("Alex Tab"), "tab characters sanitised");
        boolean threw = false;
        try { MaceRegistry.parse("garbage"); } catch (IllegalArgumentException e) { threw = true; }
        check(threw, "garbage rejected");

        Path rf = tmp.resolve("registry.tsv");
        MaceRegistry.writeAtomically(rf, text);
        MaceRegistry.writeAtomically(rf, reg.serialize());
        check(Files.exists(rf) && Files.exists(tmp.resolve("registry.tsv.bak")), "atomic write keeps .bak");
        Files.writeString(rf, "corrupted!!!");
        List<String> problems = new ArrayList<>();
        MaceRegistry rec = MaceRegistry.load(rf, problems);
        check(rec != null && rec.activeCount() == 2 && !problems.isEmpty(), "corrupt main file recovered from .bak");
        check(MaceRegistry.load(tmp.resolve("nope.tsv"), new ArrayList<>()) == null, "missing file returns null");
        reg.markDestroyed(b, "x", 10);
        check(reg.prune(1000, 5000) == 1 && reg.totalCount() == 1, "prune removes old destroyed");

        // ---------- NBT / region / player data ----------
        Path world = tmp.resolve("world");
        Path region = world.resolve("dimensions/minecraft/overworld/region");
        Path entities = world.resolve("dimensions/minecraft/overworld/entities");
        Path players = world.resolve("players/data");
        Files.createDirectories(region); Files.createDirectories(entities); Files.createDirectories(players);
        writeRegion(region.resolve("r.0.0.mca"), chunkNbt(a, c, null, null));
        writeRegion(entities.resolve("r.0.0.mca"), entityNbt(d));
        W pw = new W();
        pw.compound("").list("Inventory", 10, 1).i("Slot", 0).maceItem(p1).end().end();
        pw.end();
        try (OutputStream o = new GZIPOutputStream(Files.newOutputStream(players.resolve(UUID.randomUUID() + ".dat")))) { o.write(pw.bytes()); }

        List<Path> pdirs = DiskAudit.findPlayerDirs(world.resolve("dimensions/minecraft/overworld"));
        List<Path> rdirs = DiskAudit.findRegionDirs(world);
        check(pdirs.size() == 1, "player dir found by walking up from a dimension folder (26.1 layout)");
        check(rdirs.size() == 2, "region + entities dirs found (26.1 layout)");
        DiskAudit.Result r = DiskAudit.run(pdirs, rdirs, true, true);
        check(r.found.containsKey(a), "mace in chest block entity found");
        check(r.found.containsKey(c), "mace nested in shulker container component found");
        check(r.found.containsKey(d), "mace in item entity found (entities region)");
        check(r.found.containsKey(p1), "mace in offline player inventory found");
        check(r.untagged == 1, "untagged mace counted (" + r.untagged + ")");
        check(r.complete(), "audit marked complete on clean data (notes=" + r.notes + ")");
        check(r.chunks == 2, "two chunks parsed (" + r.chunks + ")");

        // legacy layout
        Path legacy = tmp.resolve("legacyworld");
        Files.createDirectories(legacy.resolve("region")); Files.createDirectories(legacy.resolve("playerdata"));
        Files.createDirectories(legacy.resolve("DIM-1/region"));
        check(DiskAudit.findRegionDirs(legacy).size() == 2, "legacy region + DIM-1/region found");
        check(DiskAudit.findPlayerDirs(legacy).size() == 1, "legacy playerdata found");

        // damaged region must make the audit incomplete (never free slots on partial data)
        Path bad = region.resolve("r.1.0.mca");
        byte[] good = Files.readAllBytes(region.resolve("r.0.0.mca"));
        Files.write(bad, Arrays.copyOf(good, good.length - 3000));
        DiskAudit.Result r2 = DiskAudit.run(pdirs, rdirs, true, true);
        check(!r2.complete(), "truncated region file => audit NOT complete");
        Files.delete(bad);

        // unsupported compression (LZ4 = 4) => incomplete
        byte[] lz = good.clone();
        int off = (ByteBuffer.wrap(lz).getInt(0) >>> 8) * 4096;
        lz[off + 4] = 4;
        Files.write(region.resolve("r.2.0.mca"), lz);
        DiskAudit.Result r3 = DiskAudit.run(pdirs, rdirs, true, true);
        check(!r3.complete() && r3.unsupported == 1, "LZ4 chunk => unsupported counted, audit incomplete");

        System.out.println(failures == 0 ? "\nALL TESTS PASSED" : "\n" + failures + " TEST(S) FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }
}
