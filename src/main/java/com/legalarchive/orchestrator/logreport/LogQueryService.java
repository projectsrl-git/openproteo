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
