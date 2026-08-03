package com.legalarchive.orchestrator.logreport;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.legalarchive.orchestrator.model.def.WorkflowDef;
import com.legalarchive.orchestrator.registry.WorkflowRegistry;

/**
 * Everything the log report asks of the index. Filters are translated into one parameterised SQL
 * statement (never string-concatenated values), and the feed-level attributes the audit line does not
 * carry - source, target, workflow name, PROD flag - are joined in Java from the registry, so a
 * rename in the workflow XML shows up immediately without reindexing.
 */
@Service
public class LogQueryService {

    /** Hard ceiling, so a caller cannot ask for the whole index in one page. */
    public static final int MAX_SIZE = 500;

    private final LogIndexer indexer;
    private final WorkflowRegistry registry;

    public LogQueryService(LogIndexer indexer, WorkflowRegistry registry) {
        this.indexer = indexer;
        this.registry = registry;
    }

    /** Filter set shared by search (and later by timeseries/metrics/export). */
    public static class Filters {
        public List<String> feedId;
        public List<String> sourceId;
        public List<String> targetId;
        public String step;                 // exact, or prefix when it ends with *
        public List<String> event;
        public List<String> severity;
        public String user;
        public String from;                 // ISO-8601 or 'yyyy-MM-dd HH:mm:ss'
        public String to;
        public String q;                    // free text over event/node/details
    }

    private static boolean has(List<String> l) { return l != null && !l.isEmpty(); }
    private static boolean has(String s) { return s != null && !s.trim().isEmpty(); }

    /** feedId -> feed attributes, rebuilt per call: 144 entries, cheaper than keeping it in sync. */
    private Map<String, WorkflowDef> feedMap() {
        Map<String, WorkflowDef> m = new LinkedHashMap<String, WorkflowDef>();
        for (WorkflowDef w : registry.all()) {
            if (w != null && w.feedId != null) m.put(w.feedId, w);
        }
        return m;
    }

    /**
     * Resolves the source/target/feed filters into the concrete set of feed ids to query.
     * Returns null when no feed-level filter is active (meaning: do not restrict).
     */
    private List<String> resolveFeeds(Filters f, Map<String, WorkflowDef> feeds) {
        if (!has(f.feedId) && !has(f.sourceId) && !has(f.targetId)) return null;
        List<String> out = new ArrayList<String>();
        for (Map.Entry<String, WorkflowDef> e : feeds.entrySet()) {
            WorkflowDef w = e.getValue();
            if (has(f.feedId) && !f.feedId.contains(e.getKey())) continue;
            if (has(f.sourceId) && (w.sourceId == null || !f.sourceId.contains(w.sourceId))) continue;
            if (has(f.targetId) && (w.targetId == null || !f.targetId.contains(w.targetId))) continue;
            out.add(e.getKey());
        }
        // an explicit feed filter naming feeds that no longer exist must yield nothing, not everything
        return out;
    }

    private static void inClause(StringBuilder sql, String column, List<String> values, List<Object> args) {
        sql.append(" AND ").append(column).append(" IN (");
        for (int i = 0; i < values.size(); i++) {
            sql.append(i == 0 ? "?" : ",?");
            args.add(values.get(i));
        }
        sql.append(')');
    }

    /** Builds the shared WHERE clause. Returns null when the filters can match nothing at all. */
    private String where(Filters f, Map<String, WorkflowDef> feeds, List<Object> args) {
        List<String> feedIds = resolveFeeds(f, feeds);
        if (feedIds != null && feedIds.isEmpty()) return null;

        StringBuilder sql = new StringBuilder(" WHERE 1=1");
        if (feedIds != null) inClause(sql, "feed_id", feedIds, args);
        if (has(f.event)) inClause(sql, "event", f.event, args);
        if (has(f.severity)) inClause(sql, "severity", f.severity, args);

        if (has(f.step)) {
            String s = f.step.trim();
            if (s.endsWith("*")) {
                sql.append(" AND node LIKE ?");
                args.add(s.substring(0, s.length() - 1) + "%");
            } else {
                sql.append(" AND node = ?");
                args.add(s);
            }
        }
        if (has(f.user)) {
            sql.append(" AND LOWER(user_name) = ?");
            args.add(f.user.trim().toLowerCase(java.util.Locale.ROOT));
        }
        java.sql.Timestamp from = LogIndexer.parseTs(f.from), to = LogIndexer.parseTs(f.to);
        if (from != null) { sql.append(" AND ts >= ?"); args.add(from); }
        if (to != null) { sql.append(" AND ts <= ?"); args.add(to); }

        if (has(f.q)) {
            String like = "%" + f.q.trim().toLowerCase(java.util.Locale.ROOT) + "%";
            sql.append(" AND (LOWER(event) LIKE ? OR LOWER(COALESCE(node,'')) LIKE ? OR LOWER(COALESCE(details,'')) LIKE ?"
                    + " OR LOWER(COALESCE(run_id,'')) LIKE ?)");
            args.add(like); args.add(like); args.add(like); args.add(like);
        }
        return sql.toString();
    }

    /** Ceiling for a cold scan, so an open-ended historical query degrades instead of hanging. */
    public static final int COLD_SCAN_MAX = 20000;

    /**
     * True when the request reaches back before the loaded window: the in-memory index cannot answer
     * it, so the files are read directly for the feeds in the filter.
     */
    private boolean needsColdScan(Filters f) {
        java.sql.Timestamp from = LogIndexer.parseTs(f.from);
        java.sql.Timestamp start = indexer.windowStart();
        return from != null && start != null && from.before(start);
    }

    /** Filters, sorts and pages cold-scanned rows in Java - the same predicates the SQL applies. */
    private Map<String, Object> coldSearch(Filters f, Map<String, WorkflowDef> feeds, String sort, int page, int size) {
        List<String> feedIds = resolveFeeds(f, feeds);
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("page", page);
        out.put("size", size);
        out.put("coldScan", true);
        if (feedIds == null || feedIds.isEmpty()) {
            // a full-history query over every feed is exactly what we refuse to do
            out.put("total", 0L);
            out.put("rows", new ArrayList<Map<String, Object>>());
            out.put("error", feedIds == null
                    ? "This range predates the in-memory window: pick a feed, source or target to search the files."
                    : null);
            return out;
        }
        java.sql.Timestamp from = LogIndexer.parseTs(f.from), to = LogIndexer.parseTs(f.to);
        List<Map<String, Object>> rows = indexer.coldScan(feedIds, from, to, COLD_SCAN_MAX);

        List<Map<String, Object>> kept = new ArrayList<Map<String, Object>>();
        String q = has(f.q) ? f.q.trim().toLowerCase(java.util.Locale.ROOT) : null;
        String step = has(f.step) ? f.step.trim() : null;
        for (Map<String, Object> r : rows) {
            if (has(f.event) && !f.event.contains(String.valueOf(r.get("event")))) continue;
            if (has(f.severity) && !f.severity.contains(String.valueOf(r.get("severity")))) continue;
            if (has(f.user) && !f.user.trim().equalsIgnoreCase(String.valueOf(r.get("user")))) continue;
            if (step != null) {
                String node = r.get("node") == null ? "" : String.valueOf(r.get("node"));
                if (step.endsWith("*")) { if (!node.startsWith(step.substring(0, step.length() - 1))) continue; }
                else if (!step.equals(node)) continue;
            }
            if (q != null) {
                String hay = (String.valueOf(r.get("event")) + " " + r.get("node") + " " + r.get("details")
                        + " " + r.get("runId")).toLowerCase(java.util.Locale.ROOT);
                if (hay.indexOf(q) < 0) continue;
            }
            WorkflowDef w = feeds.get(String.valueOf(r.get("feedId")));
            r.put("sourceId", w == null ? null : w.sourceId);
            r.put("targetId", w == null ? null : w.targetId);
            r.put("workflowName", w == null ? null : w.name);
            r.put("production", w != null && w.production);
            kept.add(r);
        }
        final boolean asc = "ts_asc".equalsIgnoreCase(sort);
        java.util.Collections.sort(kept, new java.util.Comparator<Map<String, Object>>() {
            @Override
            public int compare(Map<String, Object> a, Map<String, Object> b) {
                long x = ((Number) a.get("tsMillis")).longValue(), y = ((Number) b.get("tsMillis")).longValue();
                return asc ? Long.compare(x, y) : Long.compare(y, x);
            }
        });
        int fromIdx = Math.min(page * size, kept.size()), toIdx = Math.min(fromIdx + size, kept.size());
        out.put("total", (long) kept.size());
        out.put("truncated", rows.size() >= COLD_SCAN_MAX);
        out.put("rows", new ArrayList<Map<String, Object>>(kept.subList(fromIdx, toIdx)));
        return out;
    }

    /** Paginated search. {@code sort} is whitelisted: never interpolate a client string into SQL. */
    public Map<String, Object> search(final Filters f, final String sort, final int page, final int size) throws Exception {
        final Map<String, WorkflowDef> feeds = feedMap();
        final int pageSafe0 = Math.max(0, page);
        final int sizeSafe0 = Math.min(Math.max(1, size), MAX_SIZE);
        if (needsColdScan(f)) return coldSearch(f, feeds, sort, pageSafe0, sizeSafe0);

        final List<Object> args = new ArrayList<Object>();
        final String w = where(f, feeds, args);

        final int pageSafe = pageSafe0;
        final int sizeSafe = sizeSafe0;

        final Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("page", pageSafe);
        out.put("size", sizeSafe);

        if (w == null) {                                  // impossible filter combination
            out.put("total", 0L);
            out.put("rows", new ArrayList<Map<String, Object>>());
            return out;
        }

        final String orderBy = orderBy(sort);

        return indexer.withIndex(new LogIndexer.SqlFunction<Map<String, Object>>() {
            @Override
            public Map<String, Object> apply(Connection c) throws Exception {
                long total = 0;
                try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM log_entry" + w)) {
                    bind(ps, args);
                    try (ResultSet rs = ps.executeQuery()) { if (rs.next()) total = rs.getLong(1); }
                }
                List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
                String sql = "SELECT feed_id, seq, ts, run_id, node, event, severity, user_name, details FROM log_entry"
                        + w + " ORDER BY " + orderBy + " LIMIT ? OFFSET ?";
                try (PreparedStatement ps = c.prepareStatement(sql)) {
                    int i = bind(ps, args);
                    ps.setInt(i++, sizeSafe);
                    ps.setInt(i, pageSafe * sizeSafe);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) rows.add(row(rs, feeds));
                    }
                }
                out.put("total", total);
                out.put("rows", rows);
                return out;
            }
        });
    }

    /**
     * Runs with their declared output data - the same information Operations shows in OUTPUT DATA and
     * the run history lists. Filters reuse the feed/source/target/time selection; the run status can be
     * narrowed with {@code status}.
     */
    public Map<String, Object> runs(final Filters f, final List<String> status, final int page, final int size) throws Exception {
        final Map<String, WorkflowDef> feeds = feedMap();
        final List<Object> args = new ArrayList<Object>();
        List<String> feedIds = resolveFeeds(f, feeds);

        final int pageSafe = Math.max(0, page);
        final int sizeSafe = Math.min(Math.max(1, size), MAX_SIZE);
        final Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("page", pageSafe);
        out.put("size", sizeSafe);

        if (feedIds != null && feedIds.isEmpty()) {
            out.put("total", 0L);
            out.put("rows", new ArrayList<Map<String, Object>>());
            return out;
        }

        StringBuilder w = new StringBuilder(" WHERE 1=1");
        if (feedIds != null) inClause(w, "feed_id", feedIds, args);
        if (has(status)) inClause(w, "status", status, args);
        java.sql.Timestamp from = LogIndexer.parseTs(f.from), to = LogIndexer.parseTs(f.to);
        if (from != null) { w.append(" AND start_ts >= ?"); args.add(from); }
        if (to != null) { w.append(" AND start_ts <= ?"); args.add(to); }
        if (has(f.user)) {
            w.append(" AND LOWER(COALESCE(triggered_by,'')) = ?");
            args.add(f.user.trim().toLowerCase(java.util.Locale.ROOT));
        }
        if (has(f.q)) {                                  // free text also searches the output values
            String like = "%" + f.q.trim().toLowerCase(java.util.Locale.ROOT) + "%";
            w.append(" AND (LOWER(run_id) LIKE ? OR LOWER(COALESCE(message,'')) LIKE ? OR EXISTS ("
                    + "SELECT 1 FROM run_output o WHERE o.feed_id = run_entry.feed_id AND o.run_id = run_entry.run_id "
                    + "AND (LOWER(COALESCE(o.value,'')) LIKE ? OR LOWER(COALESCE(o.label,'')) LIKE ? OR LOWER(o.var_name) LIKE ?)))");
            args.add(like); args.add(like); args.add(like); args.add(like); args.add(like);
        }
        final String where = w.toString();

        return indexer.withIndex(new LogIndexer.SqlFunction<Map<String, Object>>() {
            @Override
            public Map<String, Object> apply(Connection c) throws Exception {
                long total = 0;
                try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM run_entry" + where)) {
                    bind(ps, args);
                    try (ResultSet rs = ps.executeQuery()) { if (rs.next()) total = rs.getLong(1); }
                }
                List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
                String sql = "SELECT feed_id, run_id, status, trigger_kind, triggered_by, start_ts, end_ts, message, "
                        + "steps_total, steps_ok, steps_failed FROM run_entry" + where
                        + " ORDER BY start_ts DESC, run_id DESC LIMIT ? OFFSET ?";
                try (PreparedStatement ps = c.prepareStatement(sql)) {
                    int i = bind(ps, args);
                    ps.setInt(i++, sizeSafe);
                    ps.setInt(i, pageSafe * sizeSafe);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) rows.add(runRow(rs, feeds));
                    }
                }
                attachOutputs(c, rows);
                out.put("total", total);
                out.put("rows", rows);
                return out;
            }
        });
    }

    /** One extra query for the whole page instead of one per run. */
    private static void attachOutputs(Connection c, List<Map<String, Object>> rows) throws Exception {
        if (rows.isEmpty()) return;
        StringBuilder sql = new StringBuilder("SELECT feed_id, run_id, var_name, label, value FROM run_output WHERE ");
        for (int i = 0; i < rows.size(); i++) sql.append(i == 0 ? "(feed_id=? AND run_id=?)" : " OR (feed_id=? AND run_id=?)");
        sql.append(" ORDER BY var_name");
        try (PreparedStatement ps = c.prepareStatement(sql.toString())) {
            int i = 1;
            for (Map<String, Object> r : rows) {
                ps.setString(i++, String.valueOf(r.get("feedId")));
                ps.setString(i++, String.valueOf(r.get("runId")));
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String key = rs.getString("feed_id") + "\u0000" + rs.getString("run_id");
                    for (Map<String, Object> r : rows) {
                        if (!key.equals(r.get("feedId") + "\u0000" + r.get("runId"))) continue;
                        @SuppressWarnings("unchecked")
                        List<Map<String, Object>> outs = (List<Map<String, Object>>) r.get("outputData");
                        Map<String, Object> o = new LinkedHashMap<String, Object>();
                        o.put("name", rs.getString("var_name"));
                        o.put("label", rs.getString("label"));
                        o.put("value", rs.getString("value"));
                        outs.add(o);
                        break;
                    }
                }
            }
        }
    }

    private static Map<String, Object> runRow(ResultSet rs, Map<String, WorkflowDef> feeds) throws Exception {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        String feedId = rs.getString("feed_id");
        java.text.SimpleDateFormat fmt = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        m.put("feedId", feedId);
        m.put("runId", rs.getString("run_id"));
        m.put("status", rs.getString("status"));
        m.put("trigger", rs.getString("trigger_kind"));
        m.put("triggeredBy", rs.getString("triggered_by"));
        java.sql.Timestamp st = rs.getTimestamp("start_ts"), en = rs.getTimestamp("end_ts");
        m.put("startTs", st == null ? null : fmt.format(st));
        m.put("endTs", en == null ? null : fmt.format(en));
        m.put("message", rs.getString("message"));
        m.put("stepsTotal", rs.getInt("steps_total"));
        m.put("stepsOk", rs.getInt("steps_ok"));
        m.put("stepsFailed", rs.getInt("steps_failed"));
        WorkflowDef w = feeds.get(feedId);
        m.put("sourceId", w == null ? null : w.sourceId);
        m.put("targetId", w == null ? null : w.targetId);
        m.put("workflowName", w == null ? null : w.name);
        m.put("production", w != null && w.production);
        m.put("outputData", new ArrayList<Map<String, Object>>());
        return m;
    }

    /** Guard: the projection feeding the chart is bounded, so a huge range degrades instead of exploding. */
    private static final int TIMESERIES_MAX_ROWS = 400000;
    private static final int TARGET_BUCKETS = 160;

    /**
     * Counts per time bucket and severity for the activity chart. Bucketing is done in Java over a
     * bounded (ts, severity) projection rather than with a database-specific date function, so the
     * behaviour does not depend on the SQL dialect and stays testable.
     */
    public Map<String, Object> timeseries(final Filters f, final String source, final List<String> status,
                                          final String bucket) throws Exception {
        final boolean runs = "runs".equalsIgnoreCase(source);
        final Map<String, WorkflowDef> feeds = feedMap();
        final List<Object> args = new ArrayList<Object>();
        final String sql;

        if (runs) {
            List<String> feedIds = resolveFeeds(f, feeds);
            if (feedIds != null && feedIds.isEmpty()) return emptySeries(bucket);
            StringBuilder w = new StringBuilder(" WHERE start_ts IS NOT NULL");
            if (feedIds != null) inClause(w, "feed_id", feedIds, args);
            if (has(status)) inClause(w, "status", status, args);
            java.sql.Timestamp from = LogIndexer.parseTs(f.from), to = LogIndexer.parseTs(f.to);
            if (from != null) { w.append(" AND start_ts >= ?"); args.add(from); }
            if (to != null) { w.append(" AND start_ts <= ?"); args.add(to); }
            sql = "SELECT start_ts AS t, status AS sev FROM run_entry" + w + " ORDER BY start_ts LIMIT " + TIMESERIES_MAX_ROWS;
        } else {
            String w = where(f, feeds, args);
            if (w == null) return emptySeries(bucket);
            sql = "SELECT ts AS t, severity AS sev FROM log_entry" + w + " ORDER BY ts LIMIT " + TIMESERIES_MAX_ROWS;
        }

        return indexer.withIndex(new LogIndexer.SqlFunction<Map<String, Object>>() {
            @Override
            public Map<String, Object> apply(Connection c) throws Exception {
                List<long[]> stampsIdx = new ArrayList<long[]>();
                List<String> sevs = new ArrayList<String>();
                long min = Long.MAX_VALUE, max = Long.MIN_VALUE;
                try (PreparedStatement ps = c.prepareStatement(sql)) {
                    bind(ps, args);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            java.sql.Timestamp t = rs.getTimestamp("t");
                            if (t == null) continue;
                            long ms = t.getTime();
                            stampsIdx.add(new long[]{ ms });
                            String sv = rs.getString("sev");
                            sevs.add(runs ? statusSeverity(sv) : (sv == null ? "INFO" : sv));
                            if (ms < min) min = ms;
                            if (ms > max) max = ms;
                        }
                    }
                }
                if (stampsIdx.isEmpty()) return emptySeries(bucket);

                long width = bucketMs(bucket, max - min);
                Map<Long, Map<String, Integer>> agg = new java.util.TreeMap<Long, Map<String, Integer>>();
                for (int i = 0; i < stampsIdx.size(); i++) {
                    long slot = (stampsIdx.get(i)[0] / width) * width;
                    Map<String, Integer> m = agg.get(slot);
                    if (m == null) { m = new LinkedHashMap<String, Integer>(); agg.put(slot, m); }
                    String sv = sevs.get(i);
                    Integer prev = m.get(sv);
                    m.put(sv, prev == null ? 1 : prev + 1);
                }

                java.text.SimpleDateFormat fmt = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
                List<Map<String, Object>> series = new ArrayList<Map<String, Object>>();
                for (Map.Entry<Long, Map<String, Integer>> e : agg.entrySet()) {
                    for (Map.Entry<String, Integer> sv : e.getValue().entrySet()) {
                        Map<String, Object> m = new LinkedHashMap<String, Object>();
                        m.put("bucketStart", fmt.format(new java.util.Date(e.getKey())));
                        m.put("bucketMs", e.getKey());
                        m.put("severity", sv.getKey());
                        m.put("count", sv.getValue());
                        series.add(m);
                    }
                }
                Map<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("bucketWidthMs", width);
                out.put("bucketLabel", labelFor(width));
                out.put("from", fmt.format(new java.util.Date(min)));
                out.put("to", fmt.format(new java.util.Date(max)));
                out.put("truncated", stampsIdx.size() >= TIMESERIES_MAX_ROWS);
                out.put("series", series);
                return out;
            }
        });
    }

    private static Map<String, Object> emptySeries(String bucket) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("bucketWidthMs", 0L);
        out.put("bucketLabel", bucket == null ? "auto" : bucket);
        out.put("series", new ArrayList<Map<String, Object>>());
        out.put("truncated", false);
        return out;
    }

    /** A run status painted with the same palette as the event severities. */
    private static String statusSeverity(String status) {
        if (status == null) return "INFO";
        String s = status.toUpperCase(java.util.Locale.ROOT);
        if (s.contains("FAIL") || s.contains("ERROR") || s.contains("ABORT") || s.contains("REJECT")) return "FAIL";
        if (s.contains("SUCCESS")) return "OK";
        if (s.contains("RUNNING") || s.contains("QUEUED")) return "RUN";
        if (s.contains("HOLD") || s.contains("WAIT")) return "WAIT";
        if (s.contains("SKIP")) return "SKIP";
        return "INFO";
    }

    private static final long MIN = 60000L, HOUR = 3600000L, DAY = 86400000L, WEEK = 7 * DAY;

    /** Explicit width when asked, otherwise the smallest step keeping the chart under ~160 bars. */
    static long bucketMs(String bucket, long spanMs) {
        String b = bucket == null ? "auto" : bucket.trim().toLowerCase(java.util.Locale.ROOT);
        if (b.equals("minute")) return MIN;
        if (b.equals("hour")) return HOUR;
        if (b.equals("day")) return DAY;
        if (b.equals("week")) return WEEK;
        long[] steps = { MIN, 5 * MIN, 15 * MIN, 30 * MIN, HOUR, 3 * HOUR, 6 * HOUR, 12 * HOUR, DAY, WEEK, 30 * DAY };
        long span = Math.max(spanMs, 1);
        for (long st : steps) {
            if (span / st <= TARGET_BUCKETS) return st;
        }
        return steps[steps.length - 1];
    }

    static String labelFor(long width) {
        if (width % WEEK == 0) return (width / WEEK) + "w";
        if (width % DAY == 0) return (width / DAY) + "d";
        if (width % HOUR == 0) return (width / HOUR) + "h";
        return Math.max(1, width / MIN) + "m";
    }

    /** Bounded, like the chart projection: duration pairing happens in Java, not in dialect SQL. */
    private static final int METRICS_MAX_PAIR_ROWS = 300000;

    /**
     * Numbers for the stat cards, over exactly the same filtered set as the grid. Counting is SQL;
     * step durations are paired in Java (STEP_STARTED -> STEP_COMPLETED|STEP_FAILED for the same
     * feed/run/node) so no database-specific date arithmetic is needed.
     */
    public Map<String, Object> metrics(final Filters f, final String source, final List<String> status) throws Exception {
        final boolean runs = "runs".equalsIgnoreCase(source);
        final Map<String, WorkflowDef> feeds = feedMap();
        final List<Object> args = new ArrayList<Object>();
        final Map<String, Object> out = new LinkedHashMap<String, Object>();

        if (runs) {
            List<String> feedIds = resolveFeeds(f, feeds);
            if (feedIds != null && feedIds.isEmpty()) return out;
            StringBuilder w = new StringBuilder(" WHERE 1=1");
            if (feedIds != null) inClause(w, "feed_id", feedIds, args);
            if (has(status)) inClause(w, "status", status, args);
            java.sql.Timestamp from = LogIndexer.parseTs(f.from), to = LogIndexer.parseTs(f.to);
            if (from != null) { w.append(" AND start_ts >= ?"); args.add(from); }
            if (to != null) { w.append(" AND start_ts <= ?"); args.add(to); }
            final String where = w.toString();
            return indexer.withIndex(new LogIndexer.SqlFunction<Map<String, Object>>() {
                @Override
                public Map<String, Object> apply(Connection c) throws Exception {
                    try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*), COUNT(DISTINCT feed_id), "
                            + "SUM(CASE WHEN status = 'SUCCESS' THEN 1 ELSE 0 END), "
                            + "SUM(CASE WHEN status LIKE '%FAIL%' THEN 1 ELSE 0 END), "
                            + "SUM(steps_failed) FROM run_entry" + where)) {
                        bind(ps, args);
                        try (ResultSet rs = ps.executeQuery()) {
                            if (rs.next()) {
                                out.put("runs", rs.getLong(1));
                                out.put("feeds", rs.getLong(2));
                                out.put("succeeded", rs.getLong(3));
                                out.put("failed", rs.getLong(4));
                                out.put("failedSteps", rs.getLong(5));
                            }
                        }
                    }
                    out.put("topFeedsByFailure", top(c, "SELECT feed_id, COUNT(*) FROM run_entry" + where
                            + " AND status LIKE '%FAIL%' GROUP BY feed_id ORDER BY 2 DESC, 1 LIMIT 5", args));
                    out.put("avgRunSeconds", avgRunSeconds(c, where, args));
                    return out;
                }
            });
        }

        final String w = where(f, feeds, args);
        if (w == null) return out;
        return indexer.withIndex(new LogIndexer.SqlFunction<Map<String, Object>>() {
            @Override
            public Map<String, Object> apply(Connection c) throws Exception {
                try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*), COUNT(DISTINCT run_id), "
                        + "COUNT(DISTINCT feed_id), "
                        + "SUM(CASE WHEN severity = 'FAIL' THEN 1 ELSE 0 END), "
                        + "SUM(CASE WHEN severity = 'OK' THEN 1 ELSE 0 END) FROM log_entry" + w)) {
                    bind(ps, args);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            out.put("events", rs.getLong(1));
                            out.put("runs", rs.getLong(2));
                            out.put("feeds", rs.getLong(3));
                            out.put("failures", rs.getLong(4));
                            out.put("successes", rs.getLong(5));
                        }
                    }
                }
                out.put("topFeedsByFailure", top(c, "SELECT feed_id, COUNT(*) FROM log_entry" + w
                        + " AND severity = 'FAIL' GROUP BY feed_id ORDER BY 2 DESC, 1 LIMIT 5", args));
                out.put("topStepsByFailure", top(c, "SELECT node, COUNT(*) FROM log_entry" + w
                        + " AND severity = 'FAIL' AND node IS NOT NULL GROUP BY node ORDER BY 2 DESC, 1 LIMIT 5", args));
                out.put("avgStepSeconds", avgStepSeconds(c, w, args));
                return out;
            }
        });
    }

    private static List<Map<String, Object>> top(Connection c, String sql, List<Object> args) throws Exception {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            bind(ps, args);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> m = new LinkedHashMap<String, Object>();
                    m.put("key", rs.getString(1));
                    m.put("count", rs.getLong(2));
                    out.add(m);
                }
            }
        }
        return out;
    }

    /** STEP_STARTED -> STEP_COMPLETED|STEP_FAILED for the same feed/run/node, paired in insertion order. */
    private static Double avgStepSeconds(Connection c, String where, List<Object> args) throws Exception {
        Map<String, Long> started = new LinkedHashMap<String, Long>();
        long sum = 0, pairs = 0, seen = 0;
        String sql = "SELECT feed_id, run_id, node, event, ts FROM log_entry" + where
                + " AND event IN ('STEP_STARTED','STEP_COMPLETED','STEP_FAILED') AND node IS NOT NULL"
                + " ORDER BY feed_id, run_id, node, ts LIMIT " + METRICS_MAX_PAIR_ROWS;
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            bind(ps, args);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    seen++;
                    String key = rs.getString(1) + "\u0000" + rs.getString(2) + "\u0000" + rs.getString(3);
                    java.sql.Timestamp t = rs.getTimestamp(5);
                    if (t == null) continue;
                    if ("STEP_STARTED".equals(rs.getString(4))) {
                        started.put(key, t.getTime());
                    } else {
                        Long st = started.remove(key);
                        if (st != null && t.getTime() >= st) { sum += (t.getTime() - st); pairs++; }
                    }
                }
            }
        }
        if (pairs == 0) return null;
        return Math.round((sum / (double) pairs) / 100.0) / 10.0;      // seconds, one decimal
    }

    private static Double avgRunSeconds(Connection c, String where, List<Object> args) throws Exception {
        long sum = 0, n = 0;
        try (PreparedStatement ps = c.prepareStatement("SELECT start_ts, end_ts FROM run_entry" + where
                + " AND start_ts IS NOT NULL AND end_ts IS NOT NULL LIMIT " + METRICS_MAX_PAIR_ROWS)) {
            bind(ps, args);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    java.sql.Timestamp a = rs.getTimestamp(1), b = rs.getTimestamp(2);
                    if (a == null || b == null || b.before(a)) continue;
                    sum += (b.getTime() - a.getTime());
                    n++;
                }
            }
        }
        if (n == 0) return null;
        return Math.round((sum / (double) n) / 100.0) / 10.0;
    }

    /** CSV escaping: quote only when needed, double the inner quotes, never emit a raw newline. */
    private static String csv(Object v) {
        if (v == null) return "";
        String s = String.valueOf(v).replace("\r\n", " ").replace("\n", " ").replace("\r", " ");
        if (s.indexOf(';') >= 0 || s.indexOf('"') >= 0) return "\"" + s.replace("\"", "\"\"") + "\"";
        return s;
    }

    /**
     * Streams the whole filtered set as CSV, paging through the index instead of materialising it, so a
     * large export costs a bounded amount of memory. Same columns as the grid.
     */
    public void exportCsv(Filters f, boolean runs, List<String> status, java.io.Writer w) throws Exception {
        final String EOL = String.valueOf((char) 13) + String.valueOf((char) 10);
        int page = 0, size = MAX_SIZE;
        if (runs) {
            w.write("feedId;workflowName;production;sourceId;targetId;runId;status;trigger;triggeredBy;"
                    + "startTs;endTs;stepsOk;stepsTotal;stepsFailed;message;outputData" + EOL);
        } else {
            w.write("ts;feedId;workflowName;production;sourceId;targetId;runId;node;event;severity;user;details" + EOL);
        }
        while (true) {
            Map<String, Object> res = runs ? runs(f, status, page, size) : search(f, "ts_desc", page, size);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> rows = (List<Map<String, Object>>) res.get("rows");
            if (rows == null || rows.isEmpty()) break;
            for (Map<String, Object> r : rows) {
                if (runs) {
                    StringBuilder od = new StringBuilder();
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> outs = (List<Map<String, Object>>) r.get("outputData");
                    if (outs != null) {
                        for (Map<String, Object> o : outs) {
                            if (od.length() > 0) od.append(" | ");
                            od.append(o.get("name")).append('=').append(o.get("value"));
                        }
                    }
                    w.write(csv(r.get("feedId")) + ";" + csv(r.get("workflowName")) + ";" + csv(r.get("production"))
                            + ";" + csv(r.get("sourceId")) + ";" + csv(r.get("targetId")) + ";" + csv(r.get("runId"))
                            + ";" + csv(r.get("status")) + ";" + csv(r.get("trigger")) + ";" + csv(r.get("triggeredBy"))
                            + ";" + csv(r.get("startTs")) + ";" + csv(r.get("endTs")) + ";" + csv(r.get("stepsOk"))
                            + ";" + csv(r.get("stepsTotal")) + ";" + csv(r.get("stepsFailed"))
                            + ";" + csv(r.get("message")) + ";" + csv(od.toString()) + EOL);
                } else {
                    w.write(csv(r.get("ts")) + ";" + csv(r.get("feedId")) + ";" + csv(r.get("workflowName"))
                            + ";" + csv(r.get("production")) + ";" + csv(r.get("sourceId")) + ";" + csv(r.get("targetId"))
                            + ";" + csv(r.get("runId")) + ";" + csv(r.get("node")) + ";" + csv(r.get("event"))
                            + ";" + csv(r.get("severity")) + ";" + csv(r.get("user")) + ";" + csv(r.get("details")) + EOL);
                }
            }
            w.flush();
            if (rows.size() < size) break;
            page++;
            if (page > 2000) break;                                   // hard stop: 1M rows
        }
    }

    /** Values for the filter dropdowns: feeds/sources/targets from the registry, the rest from the index. */
    public Map<String, Object> facets() throws Exception {
        final Map<String, WorkflowDef> feeds = feedMap();
        final Map<String, Object> out = new LinkedHashMap<String, Object>();
        java.util.TreeSet<String> sources = new java.util.TreeSet<String>(), targets = new java.util.TreeSet<String>();
        List<Map<String, Object>> feedList = new ArrayList<Map<String, Object>>();
        for (WorkflowDef w : feeds.values()) {
            if (w.sourceId != null && !w.sourceId.isEmpty()) sources.add(w.sourceId);
            if (w.targetId != null && !w.targetId.isEmpty()) targets.add(w.targetId);
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("feedId", w.feedId);
            m.put("name", w.name);
            m.put("sourceId", w.sourceId);
            m.put("targetId", w.targetId);
            m.put("production", w.production);
            feedList.add(m);
        }
        out.put("feeds", feedList);
        out.put("sources", new ArrayList<String>(sources));
        out.put("targets", new ArrayList<String>(targets));
        return indexer.withIndex(new LogIndexer.SqlFunction<Map<String, Object>>() {
            @Override
            public Map<String, Object> apply(Connection c) throws Exception {
                out.put("events", distinct(c, "SELECT DISTINCT event FROM log_entry ORDER BY event"));
                out.put("severities", distinct(c, "SELECT DISTINCT severity FROM log_entry ORDER BY severity"));
                out.put("statuses", distinct(c, "SELECT DISTINCT status FROM run_entry WHERE status IS NOT NULL ORDER BY status"));
                out.put("outputVars", distinct(c, "SELECT DISTINCT var_name FROM run_output ORDER BY var_name"));
                out.put("users", distinct(c, "SELECT DISTINCT user_name FROM log_entry WHERE user_name IS NOT NULL ORDER BY user_name"));
                return out;
            }
        });
    }

    private static List<String> distinct(Connection c, String sql) throws Exception {
        List<String> out = new ArrayList<String>();
        try (PreparedStatement ps = c.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String v = rs.getString(1);
                if (v != null && !v.isEmpty()) out.add(v);
            }
        }
        return out;
    }

    private static int bind(PreparedStatement ps, List<Object> args) throws Exception {
        int i = 1;
        for (Object a : args) {
            if (a instanceof java.sql.Timestamp) ps.setTimestamp(i++, (java.sql.Timestamp) a);
            else ps.setString(i++, String.valueOf(a));
        }
        return i;
    }

    /** Whitelist: anything unexpected falls back to the newest events first. */
    private static String orderBy(String sort) {
        String s = sort == null ? "" : sort.trim().toLowerCase(java.util.Locale.ROOT);
        if (s.equals("ts_asc")) return "ts ASC, feed_id ASC, seq ASC";
        if (s.equals("feed_asc")) return "feed_id ASC, seq ASC";
        if (s.equals("feed_desc")) return "feed_id DESC, seq DESC";
        if (s.equals("event_asc")) return "event ASC, ts DESC";
        return "ts DESC, feed_id ASC, seq DESC";
    }

    private static Map<String, Object> row(ResultSet rs, Map<String, WorkflowDef> feeds) throws Exception {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        String feedId = rs.getString("feed_id");
        m.put("feedId", feedId);
        m.put("seq", rs.getLong("seq"));
        java.sql.Timestamp ts = rs.getTimestamp("ts");
        m.put("ts", ts == null ? null : new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(ts));
        m.put("runId", rs.getString("run_id"));
        m.put("node", rs.getString("node"));
        m.put("event", rs.getString("event"));
        m.put("severity", rs.getString("severity"));
        m.put("user", rs.getString("user_name"));
        m.put("details", rs.getString("details"));

        WorkflowDef w = feeds.get(feedId);               // joined live, never denormalised into the index
        m.put("sourceId", w == null ? null : w.sourceId);
        m.put("targetId", w == null ? null : w.targetId);
        m.put("workflowName", w == null ? null : w.name);
        m.put("production", w != null && w.production);
        return m;
    }
}
