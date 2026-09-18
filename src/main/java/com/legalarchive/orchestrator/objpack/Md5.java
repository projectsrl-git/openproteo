package com.legalarchive.orchestrator.objpack;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * The MD5 checksum half of a Transarch submission.
 *
 * <p>The specification is short and every clause in it is a thing that can be got wrong, so each is
 * enforced here rather than left to the caller:
 * <ul>
 *   <li>the checksum is taken <b>of the tar file</b>, which is not itself a member of the tar;</li>
 *   <li>the value <b>must be lowercase</b>, which the specification states in a red box;</li>
 *   <li>the file content is the <b>bare hash</b> — no file name, no "*" marker, no second field.
 *       This is deliberately <i>not</i> the output format of md5sum, and writing md5sum's format
 *       would be the easy mistake.</li>
 * </ul>
 *
 * <p>The file is written as US-ASCII, which for 32 hex characters is byte-identical to UTF-8
 * without BOM — the encoding the rest of the package uses.
 */
public final class Md5 {

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private Md5() {
    }

    /** Streams the file and returns its MD5 as 32 lowercase hex characters. */
    public static String ofFile(File f) throws IOException {
        if (f == null || !f.isFile()) {
            throw new IOException("not a regular file: " + (f == null ? "null" : f.getPath()));
        }
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException e) {
            // Every JRE is required to provide MD5; if one does not, that is not recoverable here.
            throw new IOException("MD5 is not available in this JRE", e);
        }
        byte[] buf = new byte[64 * 1024];
        InputStream in = new FileInputStream(f);
        try {
            int n;
            while ((n = in.read(buf)) > 0) {
                md.update(buf, 0, n);
            }
        } finally {
            in.close();
        }
        return hex(md.digest());
    }

    /**
     * Writes the sidecar checksum file for {@code tarFile} and returns the hash it contains.
     *
     * @param md5File where to write, normally the tar's name with ".tar" replaced by ".md5"
     */
    public static String writeSidecar(File tarFile, File md5File) throws IOException {
        String hash = ofFile(tarFile);
        byte[] content = new byte[hash.length()];
        for (int i = 0; i < hash.length(); i++) {
            content[i] = (byte) hash.charAt(i);
        }
        OutputStream out = new FileOutputStream(md5File);
        try {
            out.write(content);
            out.flush();
        } finally {
            out.close();
        }
        return hash;
    }

    private static String hex(byte[] b) {
        char[] c = new char[b.length * 2];
        for (int i = 0; i < b.length; i++) {
            int v = b[i] & 0xFF;
            c[i * 2] = HEX[v >>> 4];
            c[i * 2 + 1] = HEX[v & 0x0F];
        }
        return new String(c);
    }
}
