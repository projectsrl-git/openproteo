package com.legalarchive.orchestrator.ftps;

/**
 * A DOS/UNIX file mask: '*' matches any run of characters including none, '?' matches exactly one.
 * Everything else is a literal.
 *
 * <p>The match is performed directly, character by character, and the pattern is deliberately NOT
 * translated into a regular expression. A file mask contains '.' on essentially every use, and the
 * masks seen in the field also contain '+' and '$' (S211048_TRANSARCH_XF_TAR+$). Translating by
 * string substitution and forgetting to escape one metacharacter turns "*.tar" into a pattern that
 * also accepts "Xtar", which is the kind of defect that produces a delivery nobody notices is
 * wrong.
 *
 * <p>Matching is case-insensitive by default: the packaging directories live on NTFS, where an
 * operator writing "*.TAR" means the file called ".tar". Stated as a decision rather than inherited
 * from whichever comparison the implementation happened to use.
 *
 * <p>A mask names a file, never a path, so a pattern containing a separator is rejected at compile
 * time rather than silently never matching.
 */
public final class Glob {

    private final String pattern;
    private final boolean caseSensitive;

    private Glob(String pattern, boolean caseSensitive) {
        this.pattern = pattern;
        this.caseSensitive = caseSensitive;
    }

    /** Compiles a case-insensitive mask. */
    public static Glob compile(String pattern) {
        return compile(pattern, false);
    }

    public static Glob compile(String pattern, boolean caseSensitive) {
        if (pattern == null) {
            throw new IllegalArgumentException("file mask is null");
        }
        String p = pattern.trim();
        if (p.isEmpty()) {
            throw new IllegalArgumentException("file mask is empty");
        }
        if (p.indexOf('/') >= 0 || p.indexOf('\\') >= 0) {
            throw new IllegalArgumentException(
                    "file mask must name a file, not a path: " + pattern);
        }
        return new Glob(p, caseSensitive);
    }

    public String pattern() {
        return pattern;
    }

    public boolean caseSensitive() {
        return caseSensitive;
    }

    /**
     * Iterative match with backtracking on the last '*' seen. Linear in the common case and free of
     * the stack depth a recursive matcher would spend on a name full of wildcards.
     */
    public boolean matches(String name) {
        if (name == null) {
            return false;
        }
        final int pn = pattern.length();
        final int nn = name.length();
        int p = 0;
        int n = 0;
        int starP = -1;
        int starN = -1;

        while (n < nn) {
            if (p < pn && (pattern.charAt(p) == '?' || same(pattern.charAt(p), name.charAt(n)))) {
                p++;
                n++;
            } else if (p < pn && pattern.charAt(p) == '*') {
                starP = p;
                p++;
                starN = n;
            } else if (starP >= 0) {
                starN++;
                p = starP + 1;
                n = starN;
            } else {
                return false;
            }
        }
        while (p < pn && pattern.charAt(p) == '*') {
            p++;
        }
        return p == pn;
    }

    private boolean same(char a, char b) {
        if (a == b) {
            return true;
        }
        if (caseSensitive) {
            return false;
        }
        return Character.toLowerCase(a) == Character.toLowerCase(b)
                || Character.toUpperCase(a) == Character.toUpperCase(b);
    }

    @Override
    public String toString() {
        return pattern;
    }
}
