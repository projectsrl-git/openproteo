package com.legalarchive.orchestrator.control;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

/**
 * The file protocol between the CLI and the instance (spec {@code .claude/HEADLESS.md} section 5).
 * JDK and Jackson only: the CLI uses this class without Spring.
 *
 * <pre>
 * &lt;control-dir&gt;/instance.lock   held by the serving instance
 * &lt;control-dir&gt;/instance.json   heartbeat
 * &lt;control-dir&gt;/inbox/          the CLI publishes &lt;id&gt;.json by renaming &lt;id&gt;.tmp
 * &lt;control-dir&gt;/work/           the instance claims a request by renaming it here
 * &lt;control-dir&gt;/outbox/         the instance publishes the answer &lt;id&gt;.json the same way
 * </pre>
 */
public final class ControlProtocol {

    private ControlProtocol() { }

    public static final int VERSION = 1;

    public static final String LOCK_FILE = "instance.lock";
    public static final String INSTANCE_FILE = "instance.json";
    public static final String INBOX = "inbox";
    public static final String WORK = "work";
    public static final String OUTBOX = "outbox";

    /** A request larger than this is refused unread. */
    public static final long MAX_REQUEST_BYTES = 64 * 1024;
    /** A request older than this when claimed is refused, not executed. */
    public static final long EXPIRY_MS = 120000L;
    /** The CLI treats an instance whose heartbeat is older than this as gone. */
    public static final long HEARTBEAT_STALE_MS = 60000L;
    /** Answers older than this are deleted by the instance. */
    public static final long ANSWER_TTL_MS = 600000L;

    /** Request ids are file names: this keeps them free of separators and dots. */
    public static final Pattern ID = Pattern.compile("[A-Za-z0-9-]{1,64}");
    /** Run and step ids, as the GUI accepts them (no path traversal). */
    public static final Pattern SIMPLE_ID = Pattern.compile("[A-Za-z0-9._-]+");

    public static ObjectMapper mapper() {
        return new ObjectMapper()
                .enable(SerializationFeature.INDENT_OUTPUT)
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    public static Path inbox(Path controlDir) { return controlDir.resolve(INBOX); }
    public static Path work(Path controlDir) { return controlDir.resolve(WORK); }
    public static Path outbox(Path controlDir) { return controlDir.resolve(OUTBOX); }

    /** What the CLI writes. Every field is optional except protocol, id and command. */
    public static class Request {
        public int protocol;
        public String id;
        public String created;
        public String command;
        public String feedId;
        public String runId;
        public String stepId;
        public Map<String, String> vars = new LinkedHashMap<String, String>();
        public String note;
        /** Log: lines after this one (follow). Null = the last {@link #tail} lines. */
        public Long offset;
        public Integer tail;
        public Integer limit;
        /** shutdown: stop even with runs QUEUED or RUNNING (H3, HEADLESS.md 15.6). */
        public Boolean force;
        /** Identity of the CLI process, read from its token. Evidence only, never the user. */
        public String declaredUser;
        public String cliBuild;
    }

    /** What the instance answers. {@code code} is the exit code the CLI returns (see {@link ExitCodes}). */
    public static class Response {
        public int protocol = VERSION;
        public String id;
        public boolean ok;
        public int code;
        public String error;
        public String runId;
        /** The user the instance recorded: the OS owner of the request file. */
        public String user;
        public Map<String, Object> data = new LinkedHashMap<String, Object>();

        public static Response ok(Map<String, Object> data) {
            Response r = new Response();
            r.ok = true;
            r.code = ExitCodes.OK;
            if (data != null) r.data = data;
            return r;
        }

        public static Response refused(int code, String error) {
            Response r = new Response();
            r.ok = false;
            r.code = code;
            r.error = error;
            return r;
        }
    }

    /** The heartbeat the instance rewrites every few seconds. */
    public static class Instance {
        public int protocol = VERSION;
        public long pid;
        public String host;
        public String mode;
        public String build;
        public String osUser;
        public String startedAt;
        public String lastSeen;
        public long lastSeenMillis;
        public boolean stopped;
        public String controlDir;
    }
}
