package com.legalarchive.orchestrator.unarchive;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The names already seen in one archive, keyed the way Windows compares them (section 5).
 *
 * <p>Decided cases, each an intersection of two rules:
 * <ul>
 * <li><b>Same directory twice</b> (same spelling): accepted. Creating a directory is idempotent,
 *     and GNU tar stores a directory again when it is named twice on the command line.</li>
 * <li><b>Same file twice</b> (same spelling): refused as {@code DUPLICATE}. tar's append mode
 *     ("the later one wins") is not supported: which of two same-named documents is the real one
 *     must not be answered by position.</li>
 * <li><b>Two files differing only in case</b>: refused as {@code CASE_COLLISION} - one file on
 *     NTFS, two on ext4.</li>
 * <li><b>Two directories differing only in case</b> ({@code Dir/a} and {@code dir/b}): refused as
 *     {@code DIRECTORY_CASE_MISMATCH}. On Windows they merge into one directory, on Linux they stay
 *     two; the same archive must give the same tree on both.</li>
 * <li><b>A file and a directory at the same path</b> ({@code a}, {@code a/b}): refused.</li>
 * </ul>
 * Parents are registered implicitly, so {@code a/b} followed by a file {@code a} conflicts even
 * though no directory entry {@code a/} was ever written.
 */
public final class NameIndex {

    private static final class Seen {
        final String spelling;
        final boolean directory;

        Seen(String spelling, boolean directory) {
            this.spelling = spelling;
            this.directory = directory;
        }
    }

    private final Map<String, Seen> byKey = new HashMap<String, Seen>();

    /** Registers a validated name, or refuses it against what was registered before. */
    public void add(EntryName.Name n) throws UnarchiveException {
        if (n.isRoot()) return;
        List<String> segs = n.segments;
        StringBuilder prefix = new StringBuilder();
        for (int i = 0; i < segs.size() - 1; i++) {
            if (i > 0) prefix.append('/');
            prefix.append(segs.get(i));
            registerDirectory(prefix.toString(), n.path);
        }
        if (n.directory) {
            registerDirectory(n.path, n.path);
            return;
        }
        String key = EntryName.collisionKey(n.path);
        Seen prev = byKey.get(key);
        if (prev == null) {
            byKey.put(key, new Seen(n.path, false));
            return;
        }
        if (prev.directory) {
            throw new UnarchiveException(UnarchiveException.Rule.FILE_DIRECTORY_CONFLICT,
                    "'" + n.path + "' is a file, but '" + prev.spelling + "' is already a directory");
        }
        if (prev.spelling.equals(n.path)) {
            throw new UnarchiveException(UnarchiveException.Rule.DUPLICATE,
                    "'" + n.path + "' appears twice in the archive; which one is the document cannot be decided");
        }
        throw new UnarchiveException(UnarchiveException.Rule.CASE_COLLISION,
                "'" + n.path + "' and '" + prev.spelling + "' differ only in case: one file on Windows");
    }

    private void registerDirectory(String path, String entry) throws UnarchiveException {
        String key = EntryName.collisionKey(path);
        Seen prev = byKey.get(key);
        if (prev == null) {
            byKey.put(key, new Seen(path, true));
            return;
        }
        if (!prev.directory) {
            throw new UnarchiveException(UnarchiveException.Rule.FILE_DIRECTORY_CONFLICT,
                    "'" + entry + "' needs '" + path + "' to be a directory, but '" + prev.spelling
                            + "' is a file");
        }
        if (!prev.spelling.equals(path)) {
            throw new UnarchiveException(UnarchiveException.Rule.DIRECTORY_CASE_MISMATCH,
                    "directories '" + path + "' and '" + prev.spelling
                            + "' differ only in case: merged on Windows, separate on Linux");
        }
    }

    public int size() {
        return byKey.size();
    }
}
