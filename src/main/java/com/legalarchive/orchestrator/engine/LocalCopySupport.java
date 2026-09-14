package com.legalarchive.orchestrator.engine;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Copies the files named in a list to one flat destination directory.
 *
 * <p>The counterpart of {@code IfsSupport.copyListToLocal} for the local filesystem, and the transfer
 * half of the option {@link CopyListSupport} reads. No Spring and no orchestrator types, so the whole
 * thing runs against real files in a test rather than only on deploy - which is the point, because
 * everything that can go wrong here (a missing file, a temp-suffixed name, a half-done delivery) is
 * exactly what a suite can build and a deploy cannot be asked to demonstrate.</p>
 *
 * <p><b>The list IS pre-scanned, and {@code ifscopy} deliberately is not.</b> Over IFS an existence
 * check costs one round trip per file - for a list of thousands, the whole transfer twice over - to buy
 * nothing the failure message does not already say. Here it is a {@code Files.isRegularFile} on the
 * very filesystem the copy is about to read, so the argument reverses and the step can refuse
 * <i>before copying anything</i> instead of stopping half way through. That matters most under
 * {@code safecopy}, whose whole purpose is that a process watching the landing zone never sees
 * something incomplete: a half-done run leaves real, correctly renamed files in that zone with nothing
 * saying the delivery was short.</p>
 */
public final class LocalCopySupport {

    private LocalCopySupport() { }

    public static class Options {
        /** Source paths, already resolved against the base and de-duplicated by the reader. */
        public List<String> paths = new ArrayList<String>();
        /** The one flat destination directory. */
        public File dest;
        /**
         * When set, each file is written as {@code <name><tmpSuffix>} and renamed to its final name
         * only once the copy is complete. Null or empty writes straight to the final name.
         */
        public String tmpSuffix;
        /** {@code false} = skip a listed file that is not there and count it. */
        public boolean failOnMissing = true;
        /** {@code filecopy} preserves file attributes; {@code safecopy} never has. */
        public boolean copyAttributes;
        /** Prefix for every message, so a log line says which executor wrote it. */
        public String label = "copy";
    }

    public static class Result {
        public int filesCopied;
        /** Listed files that were not there, or were there but were not a file. */
        public int missing;
        public long bytesCopied;
        /** Destination names, in list order. */
        public final List<String> names = new ArrayList<String>();
        public final List<String> missingNames = new ArrayList<String>();
        /** Non-null means the step must fail with this message. */
        public String failure;
    }

    public static Result run(Options o, Consumer<String> line) {
        Result r = new Result();
        String label = o.label == null ? "copy" : o.label;
        String tmp = (o.tmpSuffix == null || o.tmpSuffix.trim().isEmpty()) ? null : o.tmpSuffix;

        if (o.dest == null) { r.failure = "no destination directory"; return r; }

        // ---- a listed name that already ends in the temp suffix is REFUSED, not skipped.
        // Under a pattern, skipping it is right: it is somebody else's in-flight file and nobody asked
        // for it. In a list it was asked for by name, and copying it would put a file into the landing
        // zone under a name every watcher is built to ignore - a delivery that silently never arrives.
        if (tmp != null) {
            List<String> offend = new ArrayList<String>();
            for (String p : o.paths) {
                if (CopyListSupport.localName(CopyListSupport.Flavour.LOCAL, p).endsWith(tmp)) {
                    if (offend.size() < CopyListSupport.MAX_REPORTED) offend.add(p);
                }
            }
            if (!offend.isEmpty()) {
                r.failure = offend.size() + " listed file(s) already end in the temp suffix '" + tmp
                        + "', so the delivered name would be one every watcher ignores: "
                        + join(offend) + "; rename them or change the temp suffix";
                return r;
            }
        }

        // ---- pre-scan: every question answered before the first byte moves
        List<Path> sources = new ArrayList<Path>();
        List<String> notThere = new ArrayList<String>();
        List<String> notAFile = new ArrayList<String>();
        for (String p : o.paths) {
            Path src;
            try {
                src = Paths.get(p);
            } catch (Exception notAPath) {
                notThere.add(p);
                continue;
            }
            if (!Files.exists(src)) notThere.add(p);
            else if (!Files.isRegularFile(src)) notAFile.add(p);
            else sources.add(src);
        }
        r.missing = notThere.size() + notAFile.size();
        if (!notThere.isEmpty()) {
            line.accept(label + ": " + notThere.size() + " listed file(s) are not there: "
                    + join(notThere) + (notThere.size() > CopyListSupport.MAX_REPORTED ? " ..." : ""));
        }
        // stated apart because the remedy is different: the path is right and the thing at the end of
        // it is not a file, which a "not found" message would send somebody looking in the wrong place
        if (!notAFile.isEmpty()) {
            line.accept(label + ": " + notAFile.size() + " listed path(s) exist but are not a file"
                    + " (a directory, most likely): " + join(notAFile)
                    + (notAFile.size() > CopyListSupport.MAX_REPORTED ? " ..." : ""));
        }
        for (String p : notThere) if (r.missingNames.size() < CopyListSupport.MAX_REPORTED) r.missingNames.add(p);
        for (String p : notAFile) if (r.missingNames.size() < CopyListSupport.MAX_REPORTED) r.missingNames.add(p);

        if (r.missing > 0 && o.failOnMissing) {
            r.failure = r.missing + " of " + o.paths.size() + " listed file(s) could not be copied and"
                    + " onMissingFile=fail, so NOTHING was copied; fix the list or set onMissingFile=skip";
            return r;
        }

        // ---- transfer
        try {
            Files.createDirectories(o.dest.toPath());
        } catch (Exception e) {
            r.failure = "cannot create the destination directory " + o.dest.getPath() + ": " + e;
            return r;
        }
        Path outDir = o.dest.toPath();
        for (Path src : sources) {
            String name = src.getFileName().toString();
            Path target = outDir.resolve(name);
            try {
                if (tmp == null) {
                    if (o.copyAttributes) {
                        Files.copy(src, target, StandardCopyOption.REPLACE_EXISTING,
                                StandardCopyOption.COPY_ATTRIBUTES);
                    } else {
                        Files.copy(src, target, StandardCopyOption.REPLACE_EXISTING);
                    }
                } else {
                    Path stage = outDir.resolve(name + tmp);
                    Files.copy(src, stage, StandardCopyOption.REPLACE_EXISTING);
                    try {
                        Files.move(stage, target, StandardCopyOption.ATOMIC_MOVE);
                    } catch (Exception atomicUnsupported) {
                        Files.move(stage, target, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
                r.bytesCopied += Files.size(target);
                r.filesCopied++;
                r.names.add(name);
                line.accept(label + " " + name);
            } catch (Exception e) {
                // the files already delivered are named, because "how far did it get" is the first
                // question anyone asks about a transfer that stopped
                r.failure = "failed copying " + src + " to " + target + ": " + e
                        + " (" + r.filesCopied + " file(s) already copied)";
                return r;
            }
        }
        return r;
    }

    private static String join(List<String> l) {
        StringBuilder sb = new StringBuilder();
        int n = Math.min(l.size(), CopyListSupport.MAX_REPORTED);
        for (int i = 0; i < n; i++) {
            if (i > 0) sb.append(", ");
            sb.append(l.get(i));
        }
        return sb.toString();
    }
}
