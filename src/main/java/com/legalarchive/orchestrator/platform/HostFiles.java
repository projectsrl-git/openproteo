package com.legalarchive.orchestrator.platform;

import java.io.File;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The file operations whose result depends on the host, done so that the difference is either
 * removed or said out loud. Each method answers one finding of {@code .claude/LINUX_AUDIT.md}.
 *
 * <p>One rule runs through all of them: <b>nothing here changes what an existing Windows feed
 * does</b>. Where a behaviour could not be shown neutral on Windows it is applied to other hosts
 * only, and the method says so.
 *
 * <p>JDK only.
 */
public final class HostFiles {

    private HostFiles() { }

    private static final boolean WINDOWS = new PlatformProbe().windowsRules();

    /** True on the Windows family - the same test the Platform page and unarchive use. */
    public static boolean windowsHost() {
        return WINDOWS;
    }

    // ------------------------------------------------------------------ F1: rename

    /**
     * Renames a file, and REFUSES when the target already exists - on every host.
     *
     * <p>{@code File.renameTo} refuses an existing target on Windows and silently replaces it on
     * Linux (measured). Where the target is a {@code .done} from an earlier run, that replaced the
     * evidence of the earlier delivery without a word. {@code Files.move} with no option refuses
     * on both, so callers keep the path they already had for "could not rename".
     *
     * @return true when renamed; false when the target exists or the rename failed
     */
    public static boolean renameNoReplace(File from, File to) {
        try {
            Files.move(from.toPath(), to.toPath());
            return true;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    // ------------------------------------------------------------------ F2: glob case

    /**
     * Whether the host's file-name matching distinguishes case. ASKED of the host's own matcher,
     * not derived from the OS name: it is what {@code Files.newDirectoryStream(dir, glob)} uses.
     */
    public static boolean globIsCaseSensitive() {
        try {
            return !FileSystems.getDefault().getPathMatcher("glob:x").matches(Paths.get("X"));
        } catch (RuntimeException e) {
            return true;
        }
    }

    /**
     * Regular files in {@code dir} that match one of the globs ONLY if case is ignored - the
     * files a pattern written for Windows silently leaves behind on a case-sensitive host.
     * Zero on a host whose matching already ignores case, and zero on any error.
     */
    public static int matchOnlyIgnoringCase(Path dir, List<String> globs) {
        if (!globIsCaseSensitive()) return 0;
        int n = 0;
        try {
            List<PathMatcher> exact = new ArrayList<PathMatcher>();
            List<PathMatcher> folded = new ArrayList<PathMatcher>();
            for (String g : globs) {
                exact.add(FileSystems.getDefault().getPathMatcher("glob:" + g));
                folded.add(FileSystems.getDefault().getPathMatcher("glob:" + g.toLowerCase(Locale.ROOT)));
            }
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
                for (Path p : ds) {
                    if (Files.isDirectory(p)) continue;
                    Path name = p.getFileName();
                    Path lower = Paths.get(name.toString().toLowerCase(Locale.ROOT));
                    boolean e = false, f = false;
                    for (int i = 0; i < exact.size(); i++) {
                        if (exact.get(i).matches(name)) e = true;
                        if (folded.get(i).matches(lower)) f = true;
                    }
                    if (f && !e) n++;
                }
            }
        } catch (IOException | RuntimeException e) {
            return 0;
        }
        return n;
    }

    // ------------------------------------------------------------------ F3: order

    /**
     * Upper-cased name, then the name itself: the collation NTFS is documented to keep its
     * directories in. NOT {@code String.CASE_INSENSITIVE_ORDER}, which decides on the LOWER-cased
     * characters and so puts {@code a_b} before {@code ab}; NTFS, comparing upper-cased, puts
     * {@code ab} first - and file names are full of underscores.
     */
    public static final Comparator<String> NAME_ORDER = new Comparator<String>() {
        @Override public int compare(String a, String b) {
            int c = a.toUpperCase(Locale.ROOT).compareTo(b.toUpperCase(Locale.ROOT));
            return c != 0 ? c : a.compareTo(b);
        }
    };

    /**
     * The entries of a directory stream in a defined order. On Windows: the order the file system
     * gave, untouched - that is what existing feeds have always received, and the claim that NTFS
     * order equals {@link #NAME_ORDER} cannot be verified from here. Elsewhere, where enumeration
     * is in hash order and differs between file systems: sorted by {@link #NAME_ORDER}.
     */
    public static List<Path> inHostOrder(Iterable<Path> entries) {
        return inOrder(entries, WINDOWS);
    }

    static List<Path> inOrder(Iterable<Path> entries, boolean windows) {
        List<Path> out = new ArrayList<Path>();
        for (Path p : entries) out.add(p);
        if (!windows) {
            Collections.sort(out, new Comparator<Path>() {
                @Override public int compare(Path a, Path b) {
                    return NAME_ORDER.compare(a.getFileName().toString(), b.getFileName().toString());
                }
            });
        }
        return out;
    }

    /** As {@link #inHostOrder}, for files gathered from several directories: ordered by full path. */
    public static void sortFilesForHost(List<File> files) {
        sortFiles(files, WINDOWS);
    }

    static void sortFiles(List<File> files, boolean windows) {
        if (windows) return;
        Collections.sort(files, new Comparator<File>() {
            @Override public int compare(File a, File b) {
                return NAME_ORDER.compare(a.getPath(), b.getPath());
            }
        });
    }

    // ------------------------------------------------------------------ F5: backslash

    /**
     * Warning lines for values that look like a Linux path joined with a Windows separator:
     * they begin with {@code /} and contain a backslash. On a host that is not Windows the
     * backslash is part of the file NAME, so {@code /data/feed/10_s\out.csv} is one file called
     * {@code 10_s\out.csv} in {@code /data/feed}, created without any error. Nothing is changed:
     * a backslash is legitimate in a regex, and only the author knows. Empty on Windows.
     *
     * @param named label -> value; null values are skipped
     */
    public static List<String> backslashWarnings(Map<String, String> named) {
        return backslashWarnings(named, WINDOWS);
    }

    static List<String> backslashWarnings(Map<String, String> named, boolean windows) {
        List<String> out = new ArrayList<String>();
        if (windows || named == null) return out;
        for (Map.Entry<String, String> e : named.entrySet()) {
            String v = e.getValue();
            if (v == null) continue;
            String t = v.trim();
            if (t.startsWith("/") && t.indexOf('\\') >= 0) {
                out.add("WARNING: '" + e.getKey() + "' looks like a path joined with a backslash (" + t
                        + "). On this server a backslash is part of the file name, not a separator: use /");
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ F6: delete

    /**
     * Deletes a tree WITHOUT following symbolic links: a link is removed, what it points at is
     * not entered. {@code File.listFiles()} lists through a link to a directory, so a recursive
     * delete built on it empties the directory the link points at. Best effort, as the deletes it
     * replaces were: a file that cannot be removed is skipped.
     *
     * @return how many entries could not be deleted
     */
    public static int deleteTreeNoFollow(Path root) {
        final int[] failed = { 0 };
        if (root == null || !Files.exists(root, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return 0;
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
                @Override public FileVisitResult visitFile(Path f, BasicFileAttributes a) {
                    try { Files.delete(f); } catch (IOException e) { failed[0]++; }
                    return FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult visitFileFailed(Path f, IOException e) {
                    failed[0]++;
                    return FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult postVisitDirectory(Path d, IOException e) {
                    try { Files.delete(d); } catch (IOException x) { failed[0]++; }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            failed[0]++;
        }
        return failed[0];
    }
}
