package com.legalarchive.orchestrator.engine;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Builds the {@code {{columns}}} list for {@code csvsql}: the dataschema's columns, in the
 * dataschema's order, each read from the staged table and written back under the dataschema's exact
 * name.
 *
 * <p>Why it cannot simply reuse the {@code sql} executor's expansion. H2's {@code CSVREAD} does not
 * keep the CSV header as written. Measured on H2 2.1.214, the version in production:
 * <ul>
 *   <li>a header that is a valid SQL identifier is stored <b>uppercased</b> — {@code trn_CSVAXRef}
 *       becomes {@code TRN_CSVAXREF};</li>
 *   <li>a header that is not — {@code Transaction Num}, {@code weird-name} — is stored <b>as
 *       written</b>;</li>
 *   <li>headers differing only in case are <b>renamed</b> — {@code dup}, {@code Dup} become
 *       {@code DUP}, {@code DUP1}.</li>
 * </ul>
 * So a bare {@code trn_CSVAXRef} happens to resolve, a quoted {@code "trn_CSVAXRef"} does not, and
 * a bare {@code Transaction Num} is a syntax error. And the output header of {@code SELECT *} comes
 * out uppercased — which for a Transarch metadata file is a schema change the archive forbids
 * without re-onboarding.
 *
 * <p>Rather than re-derive H2's naming rule, which would break on the first case nobody thought
 * of, this asks H2: the caller passes the column names the staged table <i>actually</i> has, each
 * dataschema name is matched against them case-insensitively, and the list is emitted as
 * {@code "<stored>" AS "<dataschema name>"}. The reference always resolves, and the output header
 * carries exactly the casing the dataschema declares — which is what the team was already writing
 * by hand as {@code com_ID as "com_ID"}.
 *
 * <p>Pure JDK, no Spring, so it can be run against a real H2 outside the application.
 */
public final class ColumnsExpansion {

    private ColumnsExpansion() {
    }

    /**
     * @param schemaNames column names from the dataschema, in the order the output must have
     * @param storedNames the column names the staged table actually has, as H2 reports them
     * @param table       the staged table's name, for messages only
     * @return a SELECT list, e.g. {@code "OBJECT_ID" AS "object_id", "TRN_CSVAXREF" AS "trn_CSVAXRef"}
     * @throws IllegalArgumentException naming the column that cannot be resolved, and what exists
     */
    public static String build(List<String> schemaNames, List<String> storedNames, String table) {
        if (schemaNames == null || schemaNames.isEmpty()) {
            throw new IllegalArgumentException("the dataschema declares no columns");
        }
        Map<String, List<String>> byKey = new HashMap<String, List<String>>();
        for (String s : storedNames) {
            String k = s.toLowerCase(Locale.ROOT);
            List<String> l = byKey.get(k);
            if (l == null) {
                l = new ArrayList<String>();
                byKey.put(k, l);
            }
            l.add(s);
        }
        Map<String, String> claimedBy = new HashMap<String, String>();
        StringBuilder sb = new StringBuilder();
        for (String raw : schemaNames) {
            String name = raw == null ? "" : raw.trim();
            if (name.isEmpty()) {
                continue;
            }
            List<String> hits = byKey.get(name.toLowerCase(Locale.ROOT));
            if (hits == null || hits.isEmpty()) {
                throw new IllegalArgumentException("the dataschema column '" + name + "' is not in "
                        + table + ". Its columns, as H2 staged them, are " + storedNames);
            }
            if (hits.size() > 1) {
                throw new IllegalArgumentException("the dataschema column '" + name + "' matches "
                        + hits + " in " + table + ", which differ only in case");
            }
            String stored = hits.get(0);
            String prev = claimedBy.put(stored, name);
            if (prev != null) {
                // Two dataschema names that differ only in case would both read the same column,
                // silently duplicating one and losing the other.
                throw new IllegalArgumentException("the dataschema columns '" + prev + "' and '" + name
                        + "' both resolve to " + table + "." + stored);
            }
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(quote(stored)).append(" AS ").append(quote(name));
        }
        if (sb.length() == 0) {
            throw new IllegalArgumentException("the dataschema declares no usable column names");
        }
        return sb.toString();
    }

    /** A double-quoted SQL identifier, with embedded quotes doubled. */
    static String quote(String id) {
        return "\"" + id.replace("\"", "\"\"") + "\"";
    }
}
