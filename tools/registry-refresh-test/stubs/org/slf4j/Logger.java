package org.slf4j;
public class Logger {
    public static final java.util.List<String> LINES = new java.util.ArrayList<String>();
    private static void add(String lvl, String m, Object[] a) {
        StringBuilder sb = new StringBuilder(lvl).append(' ');
        int ai = 0, i = 0;
        while (i < m.length()) {
            int j = m.indexOf("{}", i);
            if (j < 0 || a == null || ai >= a.length) { sb.append(m.substring(i)); break; }
            sb.append(m, i, j).append(a[ai++]); i = j + 2;
        }
        LINES.add(sb.toString());
    }
    public void info(String m, Object... a) { add("INFO", m, a); }
    public void error(String m, Object... a) { add("ERROR", m, a); }
    public void warn(String m, Object... a) { add("WARN", m, a); }
    public void debug(String m, Object... a) { }
}
