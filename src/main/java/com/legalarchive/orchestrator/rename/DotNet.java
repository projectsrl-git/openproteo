package com.legalarchive.orchestrator.rename;

/**
 * The few .NET string primitives the replaced script relies on, reproduced exactly, because their
 * Java namesakes differ at the edges that decide whether a row is usable:
 *
 * <ul>
 *   <li>{@code String.Trim()} removes what {@code char.IsWhiteSpace} says is white - including
 *       NO-BREAK SPACE and IDEOGRAPHIC SPACE - while Java's {@code trim()} removes every char up to
 *       U+0020, control characters included, and no Unicode space at all;</li>
 *   <li>{@code int.TryParse} accepts only ASCII digits with one leading sign and the Int32 range,
 *       while {@code Integer.parseInt} also accepts Arabic-Indic and other Unicode digits;</li>
 *   <li>{@code PadLeft} pads a negative number to the LEFT of its sign: -3 at width 5 is
 *       {@code 000-3}. Measured on pwsh 7.4.6, and kept, since it is what the script produced.</li>
 * </ul>
 */
final class DotNet {

    private DotNet() { }

    /** {@code char.IsWhiteSpace}. */
    static boolean isWhiteSpace(char c) {
        if (c >= '\t' && c <= '\r') return true;
        switch (c) {
            case ' ': case '\u0085': case '\u00A0': case '\u1680':
            case '\u2028': case '\u2029': case '\u202F': case '\u205F': case '\u3000':
                return true;
            default:
                return c >= '\u2000' && c <= '\u200A';
        }
    }

    /** {@code String.Trim()}; null stays null. */
    static String trim(String s) {
        if (s == null) return null;
        int a = 0, b = s.length();
        while (a < b && isWhiteSpace(s.charAt(a))) a++;
        while (b > a && isWhiteSpace(s.charAt(b - 1))) b--;
        return s.substring(a, b);
    }

    /** {@code String.IsNullOrWhiteSpace}. */
    static boolean isNullOrWhiteSpace(String s) {
        if (s == null) return true;
        for (int i = 0; i < s.length(); i++) if (!isWhiteSpace(s.charAt(i))) return false;
        return true;
    }

    /**
     * {@code int.TryParse(s, out n)} on an already trimmed value: optional + or -, ASCII digits,
     * Int32 range. Returns null where .NET returns false.
     */
    static Integer tryParseInt(String s) {
        if (s == null || s.isEmpty()) return null;
        int i = 0;
        boolean neg = false;
        char c0 = s.charAt(0);
        if (c0 == '+' || c0 == '-') { neg = c0 == '-'; i = 1; }
        if (i == s.length()) return null;
        long v = 0;
        for (; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') return null;
            v = v * 10 + (c - '0');
            if (v > 2147483648L) return null;
        }
        if (neg) v = -v;
        if (v > Integer.MAX_VALUE || v < Integer.MIN_VALUE) return null;
        return Integer.valueOf((int) v);
    }

    /** {@code s.PadLeft(width, '0')}. */
    static String padLeftZeros(String s, int width) {
        if (s.length() >= width) return s;
        StringBuilder b = new StringBuilder(width);
        for (int i = s.length(); i < width; i++) b.append('0');
        return b.append(s).toString();
    }

    /**
     * {@code [System.IO.Path]::GetInvalidFileNameChars()} as it is ON WINDOWS - chars 0-31 and
     * {@code " < > | : * ? \ /} - whatever host this runs on. On Linux .NET returns only NUL and
     * slash, so the script is more permissive there; the executor always applies the Windows set,
     * because that is the file system the renamed names have to live on.
     */
    static boolean isInvalidFileNameChar(char c) {
        if (c < 32) return true;
        switch (c) {
            case '"': case '<': case '>': case '|': case ':': case '*': case '?': case '\\': case '/':
                return true;
            default:
                return false;
        }
    }
}
