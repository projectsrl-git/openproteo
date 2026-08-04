package com.legalarchive.orchestrator.engine;

import java.util.List;

/**
 * Pure-JDK helpers for the {@code sqlreport} executor: read-only validation of a statement,
 * Markdown rendering of a result table, and redaction of anything that could leak a credential.
 *
 * Deliberately free of Spring, JDBC and project types so it can be compiled and exercised on its
 * own - this is the part of the executor whose behaviour must be provable without a database.
 *
 * On the read-only check, stated plainly because it will be asked: this is a net against mistakes,
 * NOT a guarantee. A statement that starts with SELECT can still call a function with side effects,
 * and a driver is free to ignore {@code Connection.setReadOnly(true)}. The real guarantee is the
 * rights of the database account the datasource connects with.
 */
public final class SqlReportSupport {

    private SqlReportSupport() { }

    /** Keywords that must never appear at statement level in a report query. */
    private static final String[] FORBIDDEN = {
            "INSERT", "UPDATE", "DELETE", "MERGE", "UPSERT", "CALL", "GRANT", "REVOKE",
            "DROP", "ALTER", "CREATE", "TRUNCATE", "SET", "EXEC", "EXECUTE", "COMMIT", "ROLLBACK"
    };

    // ------------------------------------------------------------------ read-only validation

    /**
     * Validates that a statement is read-only. Returns {@code null} when it is acceptable, or a
     * human-readable reason to put in the step log and fail the step.
     *
     * Three checks, all on the statement with comments removed and with the content of string
     * literals and quoted identifiers blanked out, so that a ';' or the word DELETE inside a
     * literal is not mistaken for the real thing:
     * <ol>
     *   <li>it must not be empty;</li>
     *   <li>it must not contain a second statement (a ';' followed by anything but whitespace);</li>
     *   <li>it must start with SELECT or WITH, and must not contain a statement-level DML/DDL
     *       keyword anywhere - which is what catches a data-modifying CTE
     *       ({@code WITH x AS (...) DELETE FROM ...}), a statement that does begin with WITH.</li>
     * </ol>
     */
    public static String readOnlyError(String sql) {
        if (sql == null || sql.trim().isEmpty()) return "the statement is empty";
        String noComments = stripComments(sql);
        if (noComments.trim().isEmpty()) return "the statement is empty (only comments)";
        String scan = blankQuoted(noComments);

        int semi = scan.indexOf(';');
        while (semi >= 0) {
            if (!scan.substring(semi + 1).trim().isEmpty()) {
                return "more than one statement (text after ';') - a report query must be a single SELECT";
            }
            semi = scan.indexOf(';', semi + 1);
        }

        String lead = leadingKeyword(scan);
        if (lead == null) return "no SQL keyword found at the start of the statement";
        if (!"SELECT".equals(lead) && !"WITH".equals(lead)) {
            return "statement starts with " + lead + ": a report query must start with SELECT or WITH";
        }
        String bad = forbiddenKeyword(scan);
        if (bad != null) {
            return "statement contains the keyword " + bad + ", which is not read-only";
        }
        return null;
    }

    /** Removes {@code --} line comments and block comments, replacing them with a single space. */
    public static String stripComments(String sql) {
        if (sql == null) return "";
        StringBuilder out = new StringBuilder(sql.length());
        int i = 0, n = sql.length();
        while (i < n) {
            char c = sql.charAt(i);
            char nx = (i + 1 < n) ? sql.charAt(i + 1) : '\0';
            if (c == '\'' || c == '"') {          // literals are copied verbatim: they may contain --
                char q = c;
                out.append(c); i++;
                while (i < n) {
                    char d = sql.charAt(i);
                    out.append(d); i++;
                    if (d == q) {
                        if (i < n && sql.charAt(i) == q) { out.append(q); i++; continue; }   // doubled = escaped
                        break;
                    }
                }
                continue;
            }
            if (c == '-' && nx == '-') {
                while (i < n && sql.charAt(i) != '\n' && sql.charAt(i) != '\r') i++;
                out.append(' ');
                continue;
            }
            if (c == '/' && nx == '*') {
                i += 2;
                while (i < n && !(sql.charAt(i) == '*' && i + 1 < n && sql.charAt(i + 1) == '/')) i++;
                i = Math.min(n, i + 2);
                out.append(' ');
                continue;
            }
            out.append(c); i++;
        }
        return out.toString();
    }

    /**
     * Replaces the content of every string literal and quoted identifier with spaces, keeping the
     * quotes and the overall length. Used only for scanning, never for execution.
     */
    public static String blankQuoted(String sql) {
        if (sql == null) return "";
        char[] a = sql.toCharArray();
        int i = 0, n = a.length;
        while (i < n) {
            char c = a[i];
            if (c == '\'' || c == '"') {
                char q = c;
                i++;
                while (i < n) {
                    if (a[i] == q) {
                        if (i + 1 < n && a[i + 1] == q) { a[i] = ' '; a[i + 1] = ' '; i += 2; continue; }
                        i++;
                        break;
                    }
                    a[i] = ' ';
                    i++;
                }
                continue;
            }
            i++;
        }
        return new String(a);
    }

    /** First SQL word of a statement, uppercased; leading '(' and whitespace are skipped. */
    public static String leadingKeyword(String sql) {
        if (sql == null) return null;
        int i = 0, n = sql.length();
        while (i < n && (Character.isWhitespace(sql.charAt(i)) || sql.charAt(i) == '(')) i++;
        int start = i;
        while (i < n && (Character.isLetter(sql.charAt(i)) || sql.charAt(i) == '_')) i++;
        if (i == start) return null;
        return sql.substring(start, i).toUpperCase(java.util.Locale.ROOT);
    }

    /** First forbidden keyword found as a whole word, or null. Input must already be comment- and quote-blanked. */
    public static String forbiddenKeyword(String scan) {
        if (scan == null) return null;
        String up = scan.toUpperCase(java.util.Locale.ROOT);
        for (String kw : FORBIDDEN) {
            int from = 0;
            int p;
            while ((p = up.indexOf(kw, from)) >= 0) {
                boolean leftOk = (p == 0) || !isWordChar(up.charAt(p - 1));
                int end = p + kw.length();
                boolean rightOk = (end >= up.length()) || !isWordChar(up.charAt(end));
                if (leftOk && rightOk) return kw;
                from = p + 1;
            }
        }
        return null;
    }

    private static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$' || c == '#' || c == '@';
    }

    // ------------------------------------------------------------------ Markdown rendering

    private static final String LF = String.valueOf((char) 10);

    /**
     * Renders one value for a Markdown table cell: a '|' is escaped so it cannot invent a column,
     * and CR/LF become a single space so a multi-line value cannot break the table into rows.
     * Everything else is kept verbatim - this is evidence, not prose.
     */
    public static String mdCell(String v) {
        if (v == null) return "";
        StringBuilder sb = new StringBuilder(v.length() + 8);
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (c == '|') { sb.append('\\').append('|'); }
            else if (c == '\r') { if (i + 1 < v.length() && v.charAt(i + 1) == '\n') i++; sb.append(' '); }
            else if (c == '\n') { sb.append(' '); }
            else { sb.append(c); }
        }
        return sb.toString();
    }

    /** Full Markdown table (header + separator + rows). Rows shorter than the header are padded. */
    public static String markdownTable(List<String> columns, List<List<String>> rows) {
        StringBuilder sb = new StringBuilder();
        if (columns == null || columns.isEmpty()) return "(the query returned no columns)" + LF;
        sb.append('|');
        for (String c : columns) sb.append(' ').append(mdCell(c)).append(" |");
        sb.append(LF).append('|');
        for (int i = 0; i < columns.size(); i++) sb.append("---|");
        sb.append(LF);
        if (rows != null) {
            for (List<String> r : rows) {
                sb.append('|');
                for (int i = 0; i < columns.size(); i++) {
                    String v = (r != null && i < r.size()) ? r.get(i) : "";
                    sb.append(' ').append(mdCell(v)).append(" |");
                }
                sb.append(LF);
            }
        }
        return sb.toString();
    }

    /** JDBC value -> text, mirroring the conventions of the sql executor (null becomes empty). */
    public static String cell(Object v, boolean trim) {
        if (v == null) return "";
        String s = v.toString();
        return trim ? s.trim() : s;
    }

    // ------------------------------------------------------------------ variable collection

    /**
     * Run variables a collected column must never overwrite. Collecting into one of these would
     * silently break every later step: {@code ${feedId}} names directories, {@code ${runId}} is the
     * audit key, and so on. An explicit {@code collect} naming one of them fails the step; an
     * implicit single-column collection just skips it.
     */
    public static final java.util.Set<String> RESERVED_VARS;
    static {
        java.util.Set<String> r = new java.util.LinkedHashSet<String>(java.util.Arrays.asList(
                "feedId", "parentId", "feedName", "runId", "stepId", "stepDir", "feedDir",
                "sourceId", "targetId", "runDate", "runTs", "currentDate", "currentTs",
                "landingIn", "landingOut", "sharedDir", "stepTimeoutMins",
                "reportFile", "queriesExecuted", "rowsTotal", "rowCount", "item", "loopIndex", "loopCount"));
        RESERVED_VARS = java.util.Collections.unmodifiableSet(r);
    }

    /** What to collect from one result set, resolved against its actual column labels. */
    public static class CollectPlan {
        /** Variable names to publish, in declaration order (the result's own column labels). */
        public final List<String> names = new java.util.ArrayList<String>();
        /** 0-based positions of those columns in the result. Aligned with {@link #names}. */
        public final List<Integer> indexes = new java.util.ArrayList<Integer>();
        /** 0-based position of the key column, or -1 when the collection is not keyed. */
        public int keyIndex = -1;
        public String keyName;
        /** Non-null when the step must fail: the author asked for something that cannot be done. */
        public String error;
        /** Non-fatal remarks for the step log. */
        public final List<String> notes = new java.util.ArrayList<String>();
        /** True when the author wrote a collect list, false for the implicit single-column case. */
        public boolean explicit;
        public boolean keyed() { return keyIndex >= 0; }
        public boolean active() { return error == null && !names.isEmpty(); }
    }

    /**
     * Decides what a query collects.
     *
     * With an explicit {@code collect} list every problem is fatal: a reconciliation that silently
     * loses a variable is worse than one that stops. Without it, a result with exactly ONE column
     * is collected implicitly under that column's label (the scalar case of the spec), and any
     * problem merely skips the publication - the author never asked for it, so it must not fail
     * their report.
     */
    public static CollectPlan planCollect(List<String> resultColumns, String collect, String keyColumn) {
        CollectPlan p = new CollectPlan();
        String col = trimToNull(collect), key = trimToNull(keyColumn);
        p.explicit = (col != null);
        if (col == null && key != null) {
            p.error = "key column '" + key + "' is set but 'collect' lists no column to index by it";
            return p;
        }
        List<String> wanted = new java.util.ArrayList<String>();
        if (col != null) {
            for (String t : col.split(",")) {
                String n = t.trim();
                if (!n.isEmpty() && !containsIgnoreCase(wanted, n)) wanted.add(n);
            }
            if (wanted.isEmpty()) { p.error = "'collect' is set but lists no column name"; return p; }
        } else {
            if (resultColumns == null || resultColumns.size() != 1) return p;   // nothing implicit to collect
            wanted.add(resultColumns.get(0));
        }
        if (key != null) {
            int ki = indexOfColumn(resultColumns, key);
            if (ki < 0) { p.error = "key column '" + key + "' is not among the result columns " + resultColumns; return p; }
            p.keyIndex = ki;
            p.keyName = resultColumns.get(ki);
            String bad = varNameProblem(p.keyName);
            if (bad != null) { p.error = "key column '" + p.keyName + "' " + bad; return p; }
        }
        for (String n : wanted) {
            int idx = indexOfColumn(resultColumns, n);
            if (idx < 0) {
                if (p.explicit) { p.error = "collected column '" + n + "' is not among the result columns " + resultColumns; return p; }
                continue;
            }
            String varName = resultColumns.get(idx);
            String bad = varNameProblem(varName);
            if (bad != null) {
                if (p.explicit) { p.error = "collected column '" + varName + "' " + bad; return p; }
                p.notes.add("column '" + varName + "' " + bad + " - not published");
                continue;
            }
            p.names.add(varName);
            p.indexes.add(Integer.valueOf(idx));
        }
        return p;
    }

    /** null when the label is usable as a run variable name, otherwise why it is not. */
    public static String varNameProblem(String name) {
        if (name == null || name.trim().isEmpty()) return "has no name - give the column a SQL alias (e.g. AS N)";
        String n = name.trim();
        if (!n.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            return "is not usable as a variable name - give the column a SQL alias (e.g. AS N)";
        }
        if (RESERVED_VARS.contains(n)) return "is a reserved run variable and would overwrite it - use a SQL alias";
        return null;
    }

    /** Case-insensitive lookup of a column label; -1 when absent. */
    public static int indexOfColumn(List<String> columns, String name) {
        if (columns == null || name == null) return -1;
        String want = name.trim();
        for (int i = 0; i < columns.size(); i++) {
            String c = columns.get(i);
            if (c != null && c.trim().equalsIgnoreCase(want)) return i;
        }
        return -1;
    }

    private static boolean containsIgnoreCase(List<String> list, String v) {
        for (String s : list) if (s.equalsIgnoreCase(v)) return true;
        return false;
    }

    private static String trimToNull(String v) { return (v == null || v.trim().isEmpty()) ? null : v.trim(); }

    /**
     * A collected value must not contain the list separator or a line break: run variable lists are
     * ';'-separated and BOTH {@code ${COL[N]}} and {@code ${COL@key}} split on ';' (hardcoded in
     * VarResolver), so one value containing a ';' would shift every later position and misalign the
     * companion keys list. Both are replaced by a single space and the number of touched values is
     * reported, rather than being dropped silently.
     */
    public static String sanitizeListValue(String v) {
        if (v == null) return "";
        StringBuilder sb = new StringBuilder(v.length());
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (c == ';' || c == '\r' || c == '\n') sb.append(' ');
            else sb.append(c);
        }
        return sb.toString();
    }

    /** True when {@link #sanitizeListValue} would change the value. */
    public static boolean needsSanitizing(String v) {
        if (v == null) return false;
        return v.indexOf(';') >= 0 || v.indexOf('\r') >= 0 || v.indexOf('\n') >= 0;
    }

    /** Joins already-sanitized values with ';', the separator VarResolver splits on. */
    public static String joinList(List<String> values) {
        StringBuilder sb = new StringBuilder();
        if (values == null) return "";
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) sb.append(';');
            sb.append(values.get(i) == null ? "" : values.get(i));
        }
        return sb.toString();
    }

    /** Number of duplicated keys in a key list (0 when every key is distinct). */
    public static int duplicateKeys(List<String> keys) {
        if (keys == null) return 0;
        java.util.Set<String> seen = new java.util.HashSet<String>();
        int dup = 0;
        for (String k : keys) {
            if (!seen.add(k == null ? "" : k.trim())) dup++;
        }
        return dup;
    }

    // ------------------------------------------------------------------ credential redaction

    /**
     * A JDBC URL may carry credentials, both as {@code ...?password=x} and as {@code //user:pw@host}.
     * The report names the datasource, its host, its user and its database - NEVER a password - so
     * any URL that reaches the document goes through here first.
     */
    public static String redactJdbcUrl(String url) {
        if (url == null || url.isEmpty()) return "";
        String out = url;
        String[] keys = { "password", "passwd", "pwd" };
        for (String k : keys) {
            out = replaceParam(out, k);
        }
        int at = out.indexOf('@');
        int slashes = out.indexOf("//");
        if (at > 0 && slashes >= 0 && at > slashes) {
            String userInfo = out.substring(slashes + 2, at);
            int colon = userInfo.indexOf(':');
            if (colon >= 0) {
                out = out.substring(0, slashes + 2) + userInfo.substring(0, colon) + ":***" + out.substring(at);
            }
        }
        return out;
    }

    /** Replaces the value of key=... (case-insensitive) up to the next ; & or whitespace. */
    private static String replaceParam(String url, String key) {
        String lower = url.toLowerCase(java.util.Locale.ROOT);
        String k = key.toLowerCase(java.util.Locale.ROOT);
        StringBuilder sb = new StringBuilder();
        int copyFrom = 0;      // everything before this index is already in sb
        int searchFrom = 0;    // where to look for the next occurrence
        int p;
        while ((p = lower.indexOf(k, searchFrom)) >= 0) {
            int after = p + k.length();
            boolean leftOk = (p == 0) || !isWordChar(lower.charAt(p - 1));
            int eq = after;
            while (eq < lower.length() && Character.isWhitespace(lower.charAt(eq))) eq++;
            if (!leftOk || eq >= lower.length() || lower.charAt(eq) != '=') {
                searchFrom = p + 1;   // not a key=value occurrence: keep the text, keep looking
                continue;
            }
            int vs = eq + 1;
            int ve = vs;
            while (ve < url.length() && url.charAt(ve) != ';' && url.charAt(ve) != '&'
                    && !Character.isWhitespace(url.charAt(ve))) ve++;
            sb.append(url, copyFrom, vs).append("***");
            copyFrom = ve;
            searchFrom = ve;
        }
        sb.append(url.substring(copyFrom));
        return sb.toString();
    }
}
