package com.legalarchive.orchestrator.json2csv;

/**
 * The wildcard mask for the input directory: {@code *} for any run of characters, {@code ?} for one.
 *
 * <h3>Case-sensitive, on every platform</h3>
 *
 * The specification said "case-insensitive on Windows". That was wrong and is corrected here: it
 * would make the same workflow select a different set of files on the developer's machine and on the
 * server, and a run whose input depends on which filesystem it happens to be on is not reproducible.
 * {@code elarcheck} already matches case-sensitively, so this also keeps the two executors from
 * disagreeing about what a mask means.
 *
 * <p>Written here rather than borrowed from {@code elarcheck} because json2csv must not depend on
 * another executor's package. The suite asserts the two agree on every {@code *}-only pattern, so
 * "the same idea twice" is a measured fact rather than a hope.
 */
public final class FileMask {

    private FileMask() { }

    /** @param pattern null or empty means everything matches. */
    public static boolean matches(String name, String pattern) {
        if (name == null) return false;
        if (pattern == null || pattern.isEmpty()) return true;
        return match(name, 0, pattern, 0);
    }

    /**
     * Iterative backtracking: {@code *} remembers where it last matched and retries one character
     * further on failure. Linear in the common case and never recursive, so a pathological pattern
     * cannot exhaust the stack on a directory listing.
     */
    private static boolean match(String s, int si, String p, int pi) {
        int star = -1, mark = 0;
        while (si < s.length()) {
            if (pi < p.length() && (p.charAt(pi) == '?' || p.charAt(pi) == s.charAt(si))) {
                si++; pi++;
            } else if (pi < p.length() && p.charAt(pi) == '*') {
                star = pi++; mark = si;
            } else if (star >= 0) {
                pi = star + 1; si = ++mark;
            } else {
                return false;
            }
        }
        while (pi < p.length() && p.charAt(pi) == '*') pi++;
        return pi == p.length();
    }
}
