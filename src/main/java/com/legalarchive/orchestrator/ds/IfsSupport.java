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
