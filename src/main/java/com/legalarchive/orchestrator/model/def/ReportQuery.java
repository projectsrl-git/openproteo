package com.legalarchive.orchestrator.model.def;

/**
 * sqlreport: one query rendered as a section of the Markdown evidence report.
 *
 * Stored as its own XML element under the step (&lt;reportQuery&gt;), never as a delimited string,
 * so a ';' inside the SQL cannot corrupt the workflow definition. The element name is NOT
 * &lt;query&gt; because that one is already taken: the parser reads the single-query text of the
 * 'sql' executor from a &lt;query&gt; child, and reusing the tag would make the first report query
 * silently become StepDef.query.
 */
public class ReportQuery {

    /** Heading of the section in the report. */
    public String title;

    /** The statement. Supports ${vars} like everywhere else; must be read-only (SELECT / WITH). */
    public String sql;

    /** Batch 2: key column for the ${COL@key} companion list. Parsed and round-tripped, not used yet. */
    public String keyColumn;

    /** Batch 2: comma-separated columns to publish as run variables. Parsed and round-tripped, not used yet. */
    public String collect;

    /** Rows rendered in the table (0 = use the step default). The reported row count is always the real one. */
    public int maxRows;
}
