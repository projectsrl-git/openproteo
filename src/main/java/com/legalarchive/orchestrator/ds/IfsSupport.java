package com.legalarchive.orchestrator.ds;

import java.io.BufferedOutputStream;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.ibm.as400.access.AS400;
import com.ibm.as400.access.IFSFile;
import com.ibm.as400.access.IFSFileInputStream;

/**
 * Native IFS file copy from AS400 (DB2 for i) to the local Windows filesystem,
 * using the IBM Toolbox for Java (JTOpen). This streams bytes directly over the
 * host servers - far faster and more reliable than a DOS copy over a mapped drive.
 *
 * The AS400 connection reuses a configured datasource (host/user/password).
 */
@Component
public class IfsSupport {

    public static class CopyResult {
        public int filesCopied;
        public long bytesCopied;
        public List<String> names = new ArrayList<String>();
        /** Listed files that are not on the IFS (or are directories). Always 0 on the pattern path. */
        public int missing;
        /** Names of the first missing files, for the log; the count above is uncapped. */
        public List<String> missingNames = new ArrayList<String>();
        /** Files already present locally and left alone because overwrite is off. */
        public int skippedExisting;
        /** Non-null when the copy stopped early; the caller fails the step with this message. */
        public String failure;
    }

    /**
     * Copy from an IFS path to a local directory.
     * If ifsPath is a directory, every entry matching the glob pattern is copied;
     * if it is a file, that single file is copied.
     */
    public CopyResult copyToLocal(DataSourceDef d, String ifsPath, String localDir,
                                  String glob, boolean overwrite, java.util.function.Consumer<String> logLine) throws Exception {
        AS400 system = new AS400(d.host, d.user, d.password);
        CopyResult res = new CopyResult();
        try {
            IFSFile root = new IFSFile(system, ifsPath);
            if (!root.exists()) throw new IllegalArgumentException("IFS path does not exist: " + ifsPath);

            java.io.File outDir = new java.io.File(localDir);
            if (!outDir.isDirectory() && !outDir.mkdirs()) {
                throw new IllegalArgumentException("Local directory cannot be created: " + localDir);
            }

            List<IFSFile> targets = new ArrayList<IFSFile>();
            if (root.isDirectory()) {
                Pattern p = globToRegex(glob);
                IFSFile[] kids = root.listFiles();
                if (kids != null) {
                    for (IFSFile f : kids) {
                        if (!f.isDirectory() && (p == null || p.matcher(f.getName()).matches())) targets.add(f);
                    }
                }
            } else {
                targets.add(root);
            }

            for (IFSFile f : targets) {
                java.io.File dest = new java.io.File(outDir, f.getName());
                if (dest.exists() && !overwrite) {
                    if (logLine != null) logLine.accept("skip (exists): " + f.getName());
                    continue;
                }
                long bytes = copyOne(system, f, dest);
                res.filesCopied++;
                res.bytesCopied += bytes;
                res.names.add(f.getName());
                if (logLine != null) logLine.accept("copied " + f.getName() + " (" + bytes + " bytes)");
            }
            return res;
        } finally {
            system.disconnectAllServices();
        }
    }

    /**
     * Copy an EXPLICIT list of IFS paths to a local directory, over a single connection.
     *
     * <p>Deliberately no existence pre-scan: unlike the ELAR delivery, where a partial set beside an
     * unmarked input is unrecoverable, the destination here is a step working directory and the run
     * stops at the first problem. A pre-scan would cost one extra round trip per listed file - for a
     * list of thousands, the whole transfer twice over - to buy nothing the failure itself does not
     * already say. With {@code failOnMissing} off, a missing file is counted and named instead, never
     * silently dropped.</p>
     *
     * <p>Returns rather than throws when a listed file is missing, so the caller can still publish
     * how many were copied before the stop - which is the first thing anyone asks about a half-done
     * transfer.</p>
     */
    public CopyResult copyListToLocal(DataSourceDef d, List<String> ifsPaths, String localDir,
                                      boolean overwrite, boolean failOnMissing,
                                      java.util.function.Consumer<String> logLine) throws Exception {
        AS400 system = new AS400(d.host, d.user, d.password);
        CopyResult res = new CopyResult();
        try {
            java.io.File outDir = new java.io.File(localDir);
            if (!outDir.isDirectory() && !outDir.mkdirs()) {
                throw new IllegalArgumentException("Local directory cannot be created: " + localDir);
            }
            for (String path : ifsPaths) {
                IFSFile f = new IFSFile(system, path);
                if (!f.exists()) {
                    res.missing++;
                    if (res.missingNames.size() < 50) res.missingNames.add(path);
                    if (logLine != null) logLine.accept("NOT FOUND on IFS: " + path);
                    if (failOnMissing) { res.failure = "file listed but not found on IFS: " + path; return res; }
                    continue;
                }
                if (f.isDirectory()) {
                    res.missing++;
                    if (res.missingNames.size() < 50) res.missingNames.add(path);
                    if (logLine != null) logLine.accept("listed path is a directory, not a file: " + path);
                    if (failOnMissing) { res.failure = "listed IFS path is a directory, not a file: " + path; return res; }
                    continue;
                }
                java.io.File dest = new java.io.File(outDir, f.getName());
                if (dest.exists() && !overwrite) {
                    res.skippedExisting++;
                    if (logLine != null) logLine.accept("skip (exists): " + f.getName());
                    continue;
                }
                long bytes = copyOne(system, f, dest);
                res.filesCopied++;
                res.bytesCopied += bytes;
                res.names.add(f.getName());
                if (logLine != null) logLine.accept("copied " + f.getName() + " (" + bytes + " bytes)");
            }
            return res;
        } finally {
            system.disconnectAllServices();
        }
    }

    private long copyOne(AS400 system, IFSFile src, java.io.File dest) throws Exception {
        byte[] buf = new byte[1 << 16];
        long total = 0;
        IFSFileInputStream in = null;
        OutputStream out = null;
        try {
            in = new IFSFileInputStream(system, src.getPath());
            out = new BufferedOutputStream(new FileOutputStream(dest), 1 << 16);
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                total += n;
            }
            out.flush();
            return total;
        } finally {
            if (in != null) try { in.close(); } catch (Exception ignored) {}
            if (out != null) try { out.close(); } catch (Exception ignored) {}
        }
    }

    private static Pattern globToRegex(String glob) {
        if (glob == null || glob.trim().isEmpty() || "*".equals(glob.trim())) return null;
        StringBuilder sb = new StringBuilder();
        for (char c : glob.trim().toCharArray()) {
            switch (c) {
                case '*': sb.append(".*"); break;
                case '?': sb.append('.'); break;
                case '.': sb.append("\\."); break;
                default: sb.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return Pattern.compile(sb.toString(), Pattern.CASE_INSENSITIVE);
    }
}
