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

    /** Paginated search. {@code sort} is whitelisted: never interpolate a client string into SQL. */
    public Map<String, Object> search(final Filters f, final String sort, final int page, final int size) throws Exception {
        final Map<String, WorkflowDef> feeds = feedMap();
        final List<Object> args = new ArrayList<Object>();
        final String w = where(f, feeds, args);

        final int pageSafe = Math.max(0, page);
        final int sizeSafe = Math.min(Math.max(1, size), MAX_SIZE);

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
