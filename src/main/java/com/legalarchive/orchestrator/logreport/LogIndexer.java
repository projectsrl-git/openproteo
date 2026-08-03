package com.legalarchive.orchestrator.logreport;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.PreDestroy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.legalarchive.orchestrator.audit.AuditLogger;
import com.legalarchive.orchestrator.model.def.WorkflowDef;
import com.legalarchive.orchestrator.registry.WorkflowRegistry;

/**
 * Queryable, disposable cache over the audit trail.
 *
 * <p>The {@code audit_{feedId}.jsonl} files stay the only durable state and are opened strictly
 * read-only: this class never writes to them. Their content is mirrored into an in-memory H2
 * instance purely as a SQL engine, so filtering, sorting, paging and aggregation come for free
 * instead of being hand-rolled in Java. The index dies with the JVM and is rebuilt from the files.</p>
 *
 * <p>Refresh is a real tail, not a rescan: JSONL is append-only, so the byte offset reached last time
 * is remembered per feed and only the bytes appended since then are parsed. A trailing partial line
 * (a write in flight) is left unconsumed and picked up on the next pass.</p>
 *
 * <p>Batch 1 scope: full history, refreshed on demand with a short TTL. The rolling window and the
 * cold-scan fallback described in the spec come later, once real volumes are known.</p>
 */
@Component
public class LogIndexer {

    private static final Logger log = LoggerFactory.getLogger(LogIndexer.class);

    /** Same TTL already used for the dashboard feeds cache. */
    private static final long REFRESH_TTL_MS = 10_000L;

    private static final String URL = "jdbc:h2:mem:logidx;DB_CLOSE_DELAY=-1";

    private final WorkflowRegistry registry;
    private final ObjectMapper mapper = new ObjectMapper();

    /** Per feed: byte offset already consumed, and the highest seq inserted. */
    private static class FeedState {
        long offset = 0L;
        long lastSeq = 0L;
    }

    private final Map<String, FeedState> state = new HashMap<String, FeedState>();
    private Connection conn;
    private volatile long lastRefresh = 0L;
    private volatile boolean ready = false;

    public LogIndexer(WorkflowRegistry registry) {
        this.registry = registry;
        this.mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    // ------------------------------------------------------------------ lifecycle

    private synchronized Connection connection() throws Exception {
        if (conn == null || conn.isClosed()) {
            Class.forName("org.h2.Driver");
            conn = DriverManager.getConnection(URL, "sa", "");
            try (Statement st = conn.createStatement()) {
                st.execute("CREATE TABLE IF NOT EXISTS log_entry ("
                        + "feed_id VARCHAR NOT NULL, seq BIGINT NOT NULL, ts TIMESTAMP NOT NULL, "
                        + "run_id VARCHAR, node VARCHAR, event VARCHAR NOT NULL, severity VARCHAR NOT NULL, "
                        + "user_name VARCHAR, details VARCHAR, "
                        + "PRIMARY KEY (feed_id, seq))");
                st.execute("CREATE INDEX IF NOT EXISTS ix_log_ts ON log_entry(ts)");
                st.execute("CREATE INDEX IF NOT EXISTS ix_log_event ON log_entry(event)");
                st.execute("CREATE INDEX IF NOT EXISTS ix_log_feed ON log_entry(feed_id)");
            }
        }
        return conn;
    }

    @PreDestroy
    public synchronized void close() {
        try { if (conn != null && !conn.isClosed()) conn.close(); } catch (Exception ignored) { }
        conn = null;
    }

    /** Runs the query against a fresh-enough index; callers never touch the connection directly. */
    public synchronized <T> T withIndex(SqlFunction<T> fn) throws Exception {
        ensureFresh(false);
        return fn.apply(connection());
    }

    public boolean isReady() { return ready; }

    // ------------------------------------------------------------------ indexing

    /** Refreshes at most every {@link #REFRESH_TTL_MS}, unless forced. */
    public synchronized void ensureFresh(boolean force) {
        long now = System.currentTimeMillis();
        if (!force && ready && (now - lastRefresh) < REFRESH_TTL_MS) return;
        try {
            long added = indexAll();
            lastRefresh = System.currentTimeMillis();
            ready = true;
            if (added > 0) log.debug("log index: {} new event(s) in {} ms", added, lastRefresh - now);
        } catch (Exception e) {
            log.error("log index refresh failed: {}", e.getMessage());
        }
    }

    /** Drops everything and reloads from the files (admin reindex, or after a failed refresh). */
    public synchronized void reindex() throws Exception {
        try (Statement st = connection().createStatement()) {
            st.execute("TRUNCATE TABLE log_entry");
        }
        state.clear();
        ready = false;
        ensureFresh(true);
    }

    private long indexAll() throws Exception {
        long total = 0;
        for (WorkflowDef wf : registry.all()) {
            if (wf == null || wf.feedId == null) continue;
            try {
                total += indexFeed(wf.feedId);
            } catch (Exception e) {
                // one unreadable feed must not stop the others
                log.warn("log index: feed {} skipped ({})", wf.feedId, e.getMessage());
            }
        }
        return total;
    }

    private long indexFeed(String feedId) throws Exception {
        Path file;
        try {
            file = registry.layout(feedId).auditFile();
        } catch (Exception e) {
            return 0;
        }
        if (file == null || !Files.exists(file)) return 0;

        FeedState fs = state.get(feedId);
        if (fs == null) { fs = new FeedState(); state.put(feedId, fs); }

        long size = Files.size(file);
        if (size == fs.offset) return 0;                 // nothing appended
        if (size < fs.offset) {                          // truncated/replaced: start over for this feed
            log.warn("log index: {} shrank, reloading it from the beginning", file.getFileName());
            deleteFeed(feedId);
            fs.offset = 0; fs.lastSeq = 0;
        }

        byte[] chunk = readFrom(file, fs.offset, size);
        if (chunk.length == 0) return 0;

        int lastNl = -1;
        for (int i = chunk.length - 1; i >= 0; i--) { if (chunk[i] == '\n') { lastNl = i; break; } }
        if (lastNl < 0) return 0;                        // a single incomplete line: wait for the newline

        String text = new String(chunk, 0, lastNl + 1, StandardCharsets.UTF_8);
        long consumed = lastNl + 1;

        long inserted = 0;
        Connection c = connection();
        boolean auto = c.getAutoCommit();
        c.setAutoCommit(false);
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO log_entry (feed_id, seq, ts, run_id, node, event, severity, user_name, details) "
                        + "VALUES (?,?,?,?,?,?,?,?,?)")) {
            int batch = 0;
            for (String line : text.split("\n")) {
                if (line.trim().isEmpty()) continue;
                AuditLogger.Entry e;
                try {
                    e = mapper.readValue(line, AuditLogger.Entry.class);
                } catch (Exception bad) {
                    continue;                            // a malformed line is skipped, never fatal
                }
                if (e == null || e.seq <= fs.lastSeq) continue;
                java.sql.Timestamp ts = parseTs(e.ts);
                if (ts == null) continue;

                ps.setString(1, feedId);
                ps.setLong(2, e.seq);
                ps.setTimestamp(3, ts);
                ps.setString(4, e.runId);
                ps.setString(5, e.node);
                ps.setString(6, e.event == null ? "" : e.event);
                ps.setString(7, LogSeverity.of(e.event));
                ps.setString(8, e.user);
                ps.setString(9, detailsJson(e.details));
                ps.addBatch();
                fs.lastSeq = e.seq;
                inserted++;
                if (++batch >= 500) { ps.executeBatch(); batch = 0; }
            }
            if (batch > 0) ps.executeBatch();
            c.commit();
        } catch (Exception e) {
            c.rollback();
            throw e;
        } finally {
            c.setAutoCommit(auto);
        }

        fs.offset += consumed;
        return inserted;
    }

    private void deleteFeed(String feedId) throws Exception {
        try (PreparedStatement ps = connection().prepareStatement("DELETE FROM log_entry WHERE feed_id = ?")) {
            ps.setString(1, feedId);
            ps.executeUpdate();
        }
    }

    /** Read-only, positional read: the audit file is never opened for writing. */
    private static byte[] readFrom(Path file, long from, long to) throws IOException {
        long len = to - from;
        if (len <= 0) return new byte[0];
        if (len > Integer.MAX_VALUE) len = Integer.MAX_VALUE;
        java.nio.ByteBuffer buf = java.nio.ByteBuffer.allocate((int) len);
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ)) {
            ch.position(from);
            while (buf.hasRemaining() && ch.read(buf) > 0) { /* keep reading */ }
        }
        byte[] out = new byte[buf.position()];
        System.arraycopy(buf.array(), 0, out, 0, out.length);
        return out;
    }

    private String detailsJson(Map<String, String> details) {
        if (details == null || details.isEmpty()) return null;
        try {
            return mapper.writeValueAsString(details);
        } catch (Exception e) {
            return null;
        }
    }

    /** The audit ts is ISO-8601 with offset; tolerate a couple of shapes rather than lose the row. */
    static java.sql.Timestamp parseTs(String ts) {
        if (ts == null || ts.trim().isEmpty()) return null;
        String s = ts.trim();
        try {
            return new java.sql.Timestamp(java.time.OffsetDateTime.parse(s).toInstant().toEpochMilli());
        } catch (Exception ignored) { }
        try {
            java.time.LocalDateTime ldt = java.time.LocalDateTime.parse(s);
            return java.sql.Timestamp.valueOf(ldt);
        } catch (Exception ignored) { }
        try {
            return java.sql.Timestamp.valueOf(s.replace('T', ' '));
        } catch (Exception ignored) { }
        return null;
    }

    /** Diagnostics for the controller: how many events are loaded, and per feed. */
    public synchronized Map<String, Object> status() {
        Map<String, Object> out = new java.util.LinkedHashMap<String, Object>();
        out.put("ready", ready);
        out.put("lastRefresh", lastRefresh == 0 ? null : new java.util.Date(lastRefresh).toString());
        long rows = 0;
        try (Statement st = connection().createStatement(); ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM log_entry")) {
            if (rs.next()) rows = rs.getLong(1);
        } catch (Exception ignored) { }
        out.put("events", rows);
        out.put("feeds", state.size());
        List<Map<String, Object>> per = new ArrayList<Map<String, Object>>();
        for (Map.Entry<String, FeedState> e : state.entrySet()) {
            Map<String, Object> m = new java.util.LinkedHashMap<String, Object>();
            m.put("feedId", e.getKey());
            m.put("lastSeq", e.getValue().lastSeq);
            m.put("bytes", e.getValue().offset);
            per.add(m);
        }
        out.put("perFeed", per);
        return out;
    }

    /** Small functional handle so callers get a connection without ever keeping it. */
    public interface SqlFunction<T> {
        T apply(Connection c) throws Exception;
    }
}
