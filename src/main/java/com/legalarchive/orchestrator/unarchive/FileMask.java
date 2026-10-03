package com.legalarchive.orchestrator.unarchive;

import java.util.ArrayList;
import java.util.List;

/**
 * The {@code pattern} parameter: a {@code ;}/{@code ,} separated list of wildcard masks,
 * {@code *} any run of characters, {@code ?} one. <b>Case-sensitive on every host</b> - the
 * {@code json2csv}/{@code elarcheck} rule: the same workflow must select the same files on a
 * developer's machine and on the server.
 *
 * <p>A copy of {@code json2csv.FileMask}'s matcher, because an executor must not depend on another
 * executor's package; the suite compiles the original from the repository and compares the two.
 */
public final class FileMask {

    private final List<String> masks = new ArrayList<String>();

    public FileMask(String pattern) {
        if (pattern != null) {
            for (String t : pattern.split("[;,]")) {
                String m = t.trim();
                if (!m.isEmpty()) masks.add(m);
            }
        }
    }

    public boolean isEmpty() {
        return masks.isEmpty();
    }

    public List<String> masks() {
        return masks;
    }

    public boolean matchesAny(String name) {
        for (String m : masks) {
            if (matches(name, m)) return true;
        }
        return false;
    }

    /** @param pattern null or empty means everything matches (same contract as json2csv's). */
    public static boolean matches(String name, String pattern) {
        if (name == null) return false;
        if (pattern == null || pattern.isEmpty()) return true;
        int si = 0, pi = 0, star = -1, mark = 0;
        String s = name, p = pattern;
        while (si < s.length()) {
            if (pi < p.length() && (p.charAt(pi) == '?' || p.charAt(pi) == s.charAt(si))) {
                si++;
                pi++;
            } else if (pi < p.length() && p.charAt(pi) == '*') {
                star = pi++;
                mark = si;
            } else if (star >= 0) {
                pi = star + 1;
                si = ++mark;
            } else {
                return false;
            }
        }
        while (pi < p.length() && p.charAt(pi) == '*') pi++;
        return pi == p.length();
    }
}
