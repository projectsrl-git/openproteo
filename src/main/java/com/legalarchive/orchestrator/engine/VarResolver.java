package com.legalarchive.orchestrator.engine;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Risoluzione delle variabili ${nome} e valutazione delle condizioni dei gate.
 *
 * Condizioni supportate (semplici e auditabili, niente scripting engine):
 *   clausole unite da && oppure ||  (non miste)
 *   ogni clausola:  sinistra OP destra
 *   OP: == != >= <= > < contains notcontains matches exists notexists
 * I confronti >,<,>=,<= sono numerici se entrambi i lati sono numeri, altrimenti lessicografici.
 * "exists"/"notexists" verificano l'esistenza di un file/directory (lato destro ignorato).
 */
public final class VarResolver {

    private static final Pattern VAR = Pattern.compile("\\$\\{([^}]+)\\}");

    private VarResolver() {}

    public static String resolve(String input, Map<String, String> vars) {
        if (input == null) return null;
        Matcher m = VAR.matcher(input);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String val = vars.get(m.group(1));
            m.appendReplacement(sb, Matcher.quoteReplacement(val != null ? val : ""));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** Valuta la condizione gia' risolta (senza ${}). */
    public static boolean evalCondition(String resolved) {
        if (resolved == null || resolved.trim().isEmpty()) return false;
        String s = resolved.trim();
        boolean isOr = s.contains("||");
        boolean isAnd = s.contains("&&");
        if (isOr && isAnd) {
            throw new IllegalArgumentException("Mixed && and || in a condition is not supported: " + s);
        }
        String[] clauses = isOr ? s.split("\\|\\|") : s.split("&&");
        boolean result = !isOr; // AND parte da true, OR parte da false
        for (String c : clauses) {
            boolean v = evalClause(c.trim());
            result = isOr ? (result || v) : (result && v);
        }
        return result;
    }

    private static final String[] OPS = {
        "notcontains", "contains", "matches",
        ">=", "<=", "==", "!=", ">", "<"
    };

    private static boolean evalClause(String clause) {
        // operatori postfissi: "path exists" / "path notexists"
        if (clause.endsWith(" notexists")) {
            return apply("notexists", unquote(clause.substring(0, clause.length() - " notexists".length()).trim()), "");
        }
        if (clause.endsWith(" exists")) {
            return apply("exists", unquote(clause.substring(0, clause.length() - " exists".length()).trim()), "");
        }
        for (String op : OPS) {
            // operatori testuali richiedono spazi attorno; simbolici no
            String needle = Character.isLetter(op.charAt(0)) ? (" " + op + " ") : op;
            int idx = clause.indexOf(needle);
            if (idx < 0) continue;
            String left = clause.substring(0, idx).trim();
            String right = clause.substring(idx + needle.length()).trim();
            left = unquote(left);
            right = unquote(right);
            return apply(op, left, right);
        }
        // nessun operatore: la clausola e' vera se non vuota e diversa da false/0
        String v = unquote(clause).trim().toLowerCase();
        return !(v.isEmpty() || v.equals("false") || v.equals("0"));
    }

    private static boolean apply(String op, String left, String right) {
        if ("exists".equals(op)) return new java.io.File(left).exists();
        if ("notexists".equals(op)) return !new java.io.File(left).exists();
        if ("contains".equals(op)) return left.contains(right);
        if ("notcontains".equals(op)) return !left.contains(right);
        if ("matches".equals(op)) return left.matches(right);
        if ("==".equals(op)) return cmp(left, right) == 0;
        if ("!=".equals(op)) return cmp(left, right) != 0;
        if (">".equals(op)) return cmp(left, right) > 0;
        if ("<".equals(op)) return cmp(left, right) < 0;
        if (">=".equals(op)) return cmp(left, right) >= 0;
        if ("<=".equals(op)) return cmp(left, right) <= 0;
        throw new IllegalArgumentException("Unsupported operator: " + op);
    }

    private static int cmp(String a, String b) {
        Double da = tryNum(a), db = tryNum(b);
        if (da != null && db != null) return Double.compare(da, db);
        return a.compareTo(b);
    }

    private static Double tryNum(String s) {
        try { return Double.valueOf(s.trim()); } catch (Exception e) { return null; }
    }

    private static String unquote(String s) {
        s = s.trim();
        if (s.length() >= 2 && ((s.startsWith("'") && s.endsWith("'")) || (s.startsWith("\"") && s.endsWith("\"")))) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }
}
