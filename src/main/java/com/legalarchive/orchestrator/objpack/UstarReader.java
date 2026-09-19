package com.legalarchive.orchestrator.objpack;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads back the member names and sizes of a finished archive.
 *
 * <p>It exists for one reason: so the packager can assert that what it meant to put in the tar is
 * actually in it. The PowerShell script does the same with {@code tar -tf}, and it is the only check
 * that separates "the archive was written" from "the archive contains what we meant". Verifying with
 * the same code that wrote the file is weaker than verifying with an independent reader, so this
 * parses the headers from scratch rather than sharing anything with {@link UstarWriter}.
 *
 * <p>Headers only: the payload is skipped, so the cost is one seek per member and the check is
 * affordable even on a 20 GB submission.
 */
public final class UstarReader {

    /** One member, as the archive itself declares it. */
    public static final class Member {
        public final String name;
        public final long size;
        public final long offset;

        Member(String name, long size, long offset) {
            this.name = name;
            this.size = size;
            this.offset = offset;
        }
    }

    private UstarReader() {
    }

    /** Member names in archive order. */
    public static List<String> names(File tar) throws IOException {
        List<String> out = new ArrayList<String>();
        for (Member m : members(tar)) {
            out.add(m.name);
        }
        return out;
    }

    /** Member name to declared size, in archive order. */
    public static Map<String, Long> sizes(File tar) throws IOException {
        Map<String, Long> out = new LinkedHashMap<String, Long>();
        for (Member m : members(tar)) {
            out.put(m.name, Long.valueOf(m.size));
        }
        return out;
    }

    public static List<Member> members(File tar) throws IOException {
        List<Member> out = new ArrayList<Member>();
        RandomAccessFile r = new RandomAccessFile(tar, "r");
        try {
            long len = r.length();
            long off = 0;
            byte[] h = new byte[512];
            while (off + 512 <= len) {
                r.seek(off);
                r.readFully(h);
                if (isZero(h)) {
                    break;                       // the terminating blocks
                }
                verifyChecksum(h, off);
                String name = cstr(h, 0, 100);
                if (name.isEmpty()) {
                    throw new IOException("tar header at offset " + off + " has an empty member name");
                }
                long size = octal(h, 124, 12, off);
                out.add(new Member(name, size, off + 512));
                off += 512 + ((size + 511) / 512) * 512;
            }
        } finally {
            r.close();
        }
        return out;
    }

    private static boolean isZero(byte[] b) {
        for (int i = 0; i < b.length; i++) {
            if (b[i] != 0) {
                return false;
            }
        }
        return true;
    }

    /** Recomputed the way any tar does it, with the checksum field read as spaces. */
    private static void verifyChecksum(byte[] h, long off) throws IOException {
        long stored = octal(h, 148, 8, off);
        int sum = 0;
        for (int i = 0; i < 512; i++) {
            sum += (i >= 148 && i < 156) ? ' ' : (h[i] & 0xFF);
        }
        if (stored != sum) {
            throw new IOException("tar header at offset " + off + " has checksum " + stored
                    + " but its bytes sum to " + sum);
        }
    }

    private static String cstr(byte[] b, int off, int len) {
        int n = 0;
        while (n < len && b[off + n] != 0) {
            n++;
        }
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) {
            sb.append((char) (b[off + i] & 0xFF));
        }
        return sb.toString();
    }

    private static long octal(byte[] b, int off, int len, long at) throws IOException {
        String s = cstr(b, off, len).trim();
        if (s.isEmpty()) {
            return 0;
        }
        try {
            return Long.parseLong(s, 8);
        } catch (NumberFormatException e) {
            throw new IOException("tar header at offset " + at + " has a non-octal numeric field: '" + s + "'");
        }
    }
}
