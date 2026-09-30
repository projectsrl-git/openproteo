package com.legalarchive.orchestrator.rename;

/**
 * The key under which {@code StringComparer.OrdinalIgnoreCase} would consider two strings equal:
 * each UTF-16 unit upper-cased on its own, no culture. Every set the replaced script keeps -
 * directory index, targets seen, sources seen, header names - compares this way, and so does
 * PowerShell's {@code -contains} on the header, so the executor keys all of them the same.
 */
final class CaseInsensitive {

    private CaseInsensitive() { }

    static String key(String s) {
        StringBuilder b = null;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            char u = Character.isSurrogate(c) ? c : Character.toUpperCase(c);
            if (u != c && b == null) { b = new StringBuilder(s.length()); b.append(s, 0, i); }
            if (b != null) b.append(u);
        }
        return b == null ? s : b.toString();
    }
}
