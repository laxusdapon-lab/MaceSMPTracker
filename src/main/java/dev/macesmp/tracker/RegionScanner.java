package dev.macesmp.tracker;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

/** Reads Anvil (.mca) region files read-only and reports every Mace found in their chunk NBT. */
final class RegionScanner {
    static final class Result {
        int chunks;
        int unsupported; // LZ4 or external (.mcc) chunks that this reader cannot open
        int errors;
    }

    private RegionScanner() {
    }

    static Result scan(Path file, MaceNbt.Sink sink) {
        Result res = new Result();
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ)) {
            long size = ch.size();
            if (size < 8192) return res; // empty region
            ByteBuffer header = ByteBuffer.allocate(4096);
            readFully(ch, header, 0);
            for (int i = 0; i < 1024; i++) {
                int loc = header.getInt(i * 4);
                int offset = loc >>> 8;
                int sectors = loc & 0xFF;
                if (offset == 0 || sectors == 0) continue;
                long pos = offset * 4096L;
                if (pos + 5 > size) {
                    res.errors++;
                    continue;
                }
                try {
                    ByteBuffer lb = ByteBuffer.allocate(5);
                    readFully(ch, lb, pos);
                    int length = lb.getInt(0);
                    int comp = lb.get(4) & 0xFF;
                    if (length <= 1 || (long) (length - 1) > sectors * 4096L || pos + 4 + length > size) {
                        res.errors++;
                        continue;
                    }
                    if ((comp & 0x80) != 0) { // chunk stored in an external .mcc file
                        res.unsupported++;
                        continue;
                    }
                    byte[] data = new byte[length - 1];
                    readFully(ch, ByteBuffer.wrap(data), pos + 5);
                    InputStream in;
                    switch (comp) {
                        case 1: in = new GZIPInputStream(new ByteArrayInputStream(data)); break;
                        case 2: in = new InflaterInputStream(new ByteArrayInputStream(data)); break;
                        case 3: in = new ByteArrayInputStream(data); break;
                        default:
                            res.unsupported++; // 4 = LZ4 (or unknown)
                            continue;
                    }
                    MaceNbt.scan(in, sink);
                    res.chunks++;
                } catch (IOException | RuntimeException e) {
                    res.errors++;
                }
            }
        } catch (IOException | RuntimeException e) {
            res.errors++;
        }
        return res;
    }

    private static void readFully(FileChannel ch, ByteBuffer buf, long pos) throws IOException {
        while (buf.hasRemaining()) {
            int n = ch.read(buf, pos + buf.position());
            if (n < 0) throw new EOFException();
        }
        buf.flip();
    }
}
