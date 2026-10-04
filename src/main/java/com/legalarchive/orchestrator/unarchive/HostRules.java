package com.legalarchive.orchestrator.unarchive;

import java.util.Locale;

/**
 * Which name rules apply, decided by the OS the server runs on (spec section 21, Gate 0 L1:
 * detection is automatic, Fabiano's decision).
 *
 * <p>{@link #WINDOWS} is everything batches 1-4 built, unchanged. {@link #LINUX} drops only the
 * rules that exist because of Windows - drive letters, {@code :}, characters Windows forbids,
 * reserved device names, trailing dots and spaces, case-insensitive collisions - so an archive
 * extracts as GNU tar and unzip extract it on Linux (measured). The security rules stay on both:
 * {@code ..}, absolute paths, empty and {@code .} segments, duplicates, the limits.
 *
 * <p>{@code LINUX} is the POSIX rule set, and <b>AIX</b> gets it too (the author's decision,
 * 2026-10-04, after {@code objunpack} was refused on an AIX server): its file systems are
 * case-sensitive and forbid only {@code /} and NUL in a name, as Linux's do. What differs is the
 * path limit - see {@link #posixPathMax}.
 *
 * <p>Any other OS is refused rather than guessed: macOS's default file system is case-insensitive
 * like Windows but allows what Windows refuses, so neither rule set describes it.
 */
public enum HostRules {
    WINDOWS, LINUX;

    /** From {@code os.name}; null when the OS is not Windows, Linux or AIX. */
    public static HostRules detect(String osName) {
        if (osName == null) return null;
        String s = osName.trim().toLowerCase(Locale.ROOT);
        if (s.startsWith("windows")) return WINDOWS;
        if (s.equals("linux") || s.equals("aix")) return LINUX;
        return null;
    }

    /** Absolute path limit when {@code maxPathLength} is auto: MAX_PATH-1 UTF-16 units, PATH_MAX UTF-8 bytes. */
    public int defaultMaxPath() {
        return this == WINDOWS ? 259 : 4096;
    }

    /**
     * The absolute path limit of a POSIX host, in bytes: Linux's PATH_MAX is 4096 and AIX's is
     * 1023 (its {@code limits.h}; from the documentation, not measured - no AIX host was available).
     * A path beyond it is refused by the OS at creation; knowing the figure lets a step refuse the
     * package before writing anything instead.
     */
    public static int posixPathMax(String osName) {
        return osName != null && osName.trim().equalsIgnoreCase("aix") ? 1023 : 4096;
    }

    public String label() {
        return name().toLowerCase(Locale.ROOT);
    }
}
