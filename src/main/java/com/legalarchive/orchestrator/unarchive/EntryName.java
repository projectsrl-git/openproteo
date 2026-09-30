package com.legalarchive.orchestrator.unarchive;

import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The one validator every entry name goes through, from every format (spec section 5).
 *
 * <p><b>Refusal is the rule.</b> Exactly three transformations are accepted, each because real
 * tools produce the shape and refusing it would refuse ordinary archives:
 * <ol>
 * <li>ONE leading {@code ./} is dropped, and {@code ./} or {@code .} alone is the root (GNU tar
 *     with {@code -C dir .}, measured). <b>Intersection</b> with the dot-segment rule: only the
 *     FIRST segment, only once - {@code ././a} and {@code a/./b} are refused.</li>
 * <li>{@code \} is a separator, like {@code /}, on every host. <b>Intersection</b> with the
 *     absolute rule: a LEADING {@code \} is absolute and refused; one inside a name separates.
 *     On Linux a tar member {@code a\b} therefore becomes {@code a/b}, deliberately: the same
 *     workflow must produce the same tree on a Linux test box and on the Windows server.</li>
 * <li>A trailing separator marks a directory. <b>Intersection</b> with the empty-segment rule:
 *     exactly one trailing separator; {@code a//} is an empty segment and refused.</li>
 * </ol>
 *
 * <p>Nothing here touches the file system: the checks are the same on every host.
 */
public final class EntryName {

    /** A validated name. */
    public static final class Name {
        /** Segments joined with {@code /}; empty for the root. */
        public final String path;
        public final List<String> segments;
        public final boolean directory;
        /** The name used at least one backslash as a separator: counted in the log. */
        public final boolean usedBackslash;
        /** The name is not Unicode NFC: reported, never renamed (section 5). */
        public final boolean notNfc;

        Name(List<String> segments, boolean directory, boolean usedBackslash, boolean notNfc) {
            this.segments = Collections.unmodifiableList(segments);
            this.path = join(segments);
            this.directory = directory;
            this.usedBackslash = usedBackslash;
            this.notNfc = notNfc;
        }

        public boolean isRoot() {
            return segments.isEmpty();
        }
    }

    /** NTFS and ext4 both stop at 255 per component; both units are checked so every host agrees. */
    public static final int MAX_SEGMENT = 255;

    private static final Set<String> RESERVED = new HashSet<String>(Arrays.asList(
            "CON", "PRN", "AUX", "NUL", "CONIN$", "CONOUT$",
            "COM0", "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
            "COM\u00B9", "COM\u00B2", "COM\u00B3",
            "LPT0", "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9",
            "LPT\u00B9", "LPT\u00B2", "LPT\u00B3"));

    private EntryName() {
    }

    /**
     * Validates {@code raw}. {@code directoryByType} is true when the format says the entry is a
     * directory whatever its spelling (tar typeflag 5); a trailing separator says so too.
     */
    public static Name validate(String raw, boolean directoryByType) throws UnarchiveException {
        if (raw == null || raw.isEmpty()) {
            throw refuse(UnarchiveException.Rule.EMPTY_NAME, "(empty)", "an entry has no name");
        }
        String s = raw;
        if (s.equals(".") || s.equals("./") || s.equals(".\\")) {
            return new Name(new ArrayList<String>(), true, s.indexOf('\\') >= 0, false);
        }
        char c0 = s.charAt(0);
        if (c0 == '/' || c0 == '\\') {
            throw refuse(UnarchiveException.Rule.ABSOLUTE, raw,
                    "starts with a separator (absolute, rooted, UNC or device path)");
        }
        if (s.length() >= 2 && s.charAt(1) == ':' && isAsciiLetter(c0)) {
            throw refuse(UnarchiveException.Rule.DRIVE_LETTER, raw,
                    "names a drive; C:x is relative to that drive's current directory, never to ours");
        }
        if (s.startsWith("./") || s.startsWith(".\\")) {
            s = s.substring(2);
        }
        boolean usedBackslash = s.indexOf('\\') >= 0;
        boolean dir = directoryByType;
        char last = s.charAt(s.length() - 1);
        if (last == '/' || last == '\\') {
            dir = true;
            s = s.substring(0, s.length() - 1);
        }
        if (s.isEmpty()) {
            throw refuse(UnarchiveException.Rule.EMPTY_SEGMENT, raw, "has an empty path segment");
        }
        List<String> segs = new ArrayList<String>();
        int start = 0;
        for (int i = 0; i <= s.length(); i++) {
            if (i == s.length() || s.charAt(i) == '/' || s.charAt(i) == '\\') {
                segs.add(s.substring(start, i));
                start = i + 1;
            }
        }
        for (String seg : segs) {
            checkSegment(raw, seg);
        }
        boolean notNfc = !Normalizer.isNormalized(raw, Normalizer.Form.NFC);
        return new Name(segs, dir, usedBackslash, notNfc);
    }

    private static void checkSegment(String raw, String seg) throws UnarchiveException {
        if (seg.isEmpty()) {
            throw refuse(UnarchiveException.Rule.EMPTY_SEGMENT, raw, "has an empty path segment");
        }
        if (seg.equals("..")) {
            throw refuse(UnarchiveException.Rule.TRAVERSAL, raw, "has a '..' segment");
        }
        if (seg.equals(".")) {
            throw refuse(UnarchiveException.Rule.DOT_SEGMENT, raw,
                    "has a '.' segment other than one leading './'");
        }
        for (int i = 0; i < seg.length(); i++) {
            char c = seg.charAt(i);
            if (c == ':') {
                throw refuse(UnarchiveException.Rule.COLON, raw,
                        "contains ':' (on NTFS it writes a hidden alternate data stream)");
            }
            if (c < 0x20 || c == '<' || c == '>' || c == '"' || c == '|' || c == '?' || c == '*') {
                throw refuse(UnarchiveException.Rule.INVALID_CHAR, raw,
                        "contains a character Windows does not allow in a name (code " + (int) c + ")");
            }
        }
        char end = seg.charAt(seg.length() - 1);
        if (end == '.' || end == ' ') {
            throw refuse(UnarchiveException.Rule.TRAILING_DOT_OR_SPACE, raw,
                    "has a segment ending in '.' or space, which Windows strips (two names, one file)");
        }
        if (isReserved(seg)) {
            throw refuse(UnarchiveException.Rule.RESERVED_NAME, raw,
                    "has the Windows device name '" + seg + "' as a segment (reserved with any extension)");
        }
        if (seg.length() > MAX_SEGMENT || seg.getBytes(StandardCharsets.UTF_8).length > MAX_SEGMENT) {
            throw refuse(UnarchiveException.Rule.SEGMENT_TOO_LONG, raw,
                    "has a segment over " + MAX_SEGMENT + " characters or UTF-8 bytes");
        }
    }

    /** Reserved if the part before the first dot, trailing spaces removed, is a device name. */
    static boolean isReserved(String seg) {
        int dot = seg.indexOf('.');
        String stem = dot < 0 ? seg : seg.substring(0, dot);
        int e = stem.length();
        while (e > 0 && stem.charAt(e - 1) == ' ') e--;
        return RESERVED.contains(stem.substring(0, e).toUpperCase(Locale.ROOT));
    }

    /**
     * The key under which two names are one file on Windows: each UTF-16 unit upper-cased on its
     * own, no culture - {@code StringComparer.OrdinalIgnoreCase}, and the same rule as
     * {@code rename.CaseInsensitive}, which this package may not depend on (the suite compares the
     * two). NOT {@code toUpperCase(Locale.ROOT)} on the string, which maps {@code ß} to {@code SS},
     * nor {@code toLowerCase}, which turns {@code İ} into two characters: measured, and NTFS does
     * neither.
     */
    public static String collisionKey(String s) {
        StringBuilder b = null;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            char u = Character.isSurrogate(c) ? c : Character.toUpperCase(c);
            if (u != c && b == null) {
                b = new StringBuilder(s.length());
                b.append(s, 0, i);
            }
            if (b != null) b.append(u);
        }
        return b == null ? s : b.toString();
    }

    /**
     * Length of the absolute path the entry will have, in UTF-16 units as Windows counts
     * {@code MAX_PATH}. {@code base} is the directory the entry lands in, already resolved.
     */
    public static int absoluteLength(String base, Name n) {
        if (n.isRoot()) return base.length();
        boolean sep = base.endsWith("/") || base.endsWith("\\");
        return base.length() + (sep ? 0 : 1) + n.path.length();
    }

    /** Refuses a path whose absolute length exceeds {@code max}. */
    public static void checkLength(String base, Name n, int max) throws UnarchiveException {
        int len = absoluteLength(base, n);
        if (len > max) {
            throw refuse(UnarchiveException.Rule.PATH_TOO_LONG, n.path,
                    "would be " + len + " characters as an absolute path, over the limit of " + max
                            + " (Explorer, PowerShell 5.1 and later steps could not open it)");
        }
    }

    static String join(List<String> segs) {
        StringBuilder b = new StringBuilder();
        for (String s : segs) {
            if (b.length() > 0) b.append('/');
            b.append(s);
        }
        return b.toString();
    }

    private static boolean isAsciiLetter(char c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z');
    }

    private static UnarchiveException refuse(UnarchiveException.Rule r, String raw, String why) {
        return new UnarchiveException(r, "entry '" + printable(raw) + "' " + why);
    }

    /** Control characters shown as escapes so a hostile name cannot rewrite the log line. */
    static String printable(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x20 || c == 0x7F) b.append(String.format("\\x%02x", (int) c));
            else b.append(c);
        }
        return b.toString();
    }
}
