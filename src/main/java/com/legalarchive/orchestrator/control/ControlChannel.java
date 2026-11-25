package com.legalarchive.orchestrator.control;

import java.io.File;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.legalarchive.orchestrator.platform.HostFiles;

/**
 * The instance side of the control directory (spec {@code .claude/HEADLESS.md} section 5).
 *
 * <p>No Spring and no engine in here: requests are executed by a {@link ControlBackend}. What this
 * class owns is everything that makes the channel trustworthy:
 * <ul>
 *   <li>one instance per directory ({@code instance.lock}, held for the life of the channel);</li>
 *   <li>a request is claimed by renaming it out of the inbox, so it is executed at most once, and
 *       a CLI that cancels by renaming it the other way wins or loses cleanly;</li>
 *   <li>the user is the OS owner of the request file, never something the request says;</li>
 *   <li>an inbox open to everyone switches the channel off (Q8);</li>
 *   <li>a request older than 120 s is refused, not executed;</li>
 *   <li>answers are published by rename, so the CLI never reads half of one.</li>
 * </ul>
 * Files are read through NIO, which on Windows opens them with delete sharing: a reader then never
 * makes the other side's rename fail.
 */
public class ControlChannel {

    private static final Logger log = LoggerFactory.getLogger(ControlChannel.class);
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX");

    private final Path dir;
    private final ControlBackend backend;
    private final String mode;
    private final String build;
    private final ObjectMapper mapper = ControlProtocol.mapper();
    private final long pid;
    private final String host;
    private final String osUser;
    private final String startedAt = now();

    private volatile boolean enabled;
    private volatile String reason = "not started";
    private FileChannel lockChannel;
    private FileLock lock;
    private ScheduledExecutorService exec;

    public ControlChannel(Path dir, ControlBackend backend, String mode, String build) {
        this.dir = dir;
        this.backend = backend;
        this.mode = mode;
        this.build = build == null ? "" : build;
        this.pid = pid();
        this.host = host();
        this.osUser = OsIdentity.declared();
    }

    public Path dir() { return dir; }
    public boolean enabled() { return enabled; }
    public String reason() { return reason; }

    /**
     * The checks {@link #start()} makes before it touches anything, without taking the lock: null
     * when the directory can serve, otherwise the reason. The headless artifact runs them before
     * its engine exists, so a channel that cannot open stops the start instead of leaving an
     * instance nobody can reach (HEADLESS.md 4.4, Q8).
     */
    public static String preflight(Path dir) {
        try {
            if (!dir.isAbsolute()) return "the control directory must be an absolute path: " + dir;
            if (!Files.isDirectory(dir)) return "the control directory does not exist or is not a directory: " + dir;
            Path inbox = ControlProtocol.inbox(dir), outbox = ControlProtocol.outbox(dir);
            if (!Files.isDirectory(inbox) || !Files.isDirectory(outbox)) {
                return "the control directory has no inbox/ or outbox/ folder. Create both, with the "
                        + "permissions described in USAGE.md (section Command-line control)";
            }
            String open = InboxAcl.openToEveryone(inbox);
            if (open != null) {
                return "the inbox is open to everyone: " + open + ". The channel stays off until the inbox "
                        + "is restricted to the operators";
            }
            return null;
        } catch (Exception e) {
            return "the control directory cannot be used: " + e;
        }
    }

    /** Opens the channel. Returns false, with {@link #reason()} saying why, when it stays off. */
    public synchronized boolean start() {
        if (enabled) return true;
        try {
            String why = preflight(dir);
            if (why != null) return off(why);
            Files.createDirectories(ControlProtocol.work(dir));
            if (!takeLock()) {
                return off("another instance serves this control directory" + holder());
            }
            writeHeartbeat(false);
            exec = Executors.newScheduledThreadPool(2, r -> {
                Thread t = new Thread(r, "op-control");
                t.setDaemon(true);
                return t;
            });
            // 250 ms (H3, HEADLESS.md 15.4): a command typed in the console answers in well under a second
            exec.scheduleWithFixedDelay(this::pollSafely, 250, 250, TimeUnit.MILLISECONDS);
            exec.scheduleWithFixedDelay(this::heartbeatSafely, 10, 10, TimeUnit.SECONDS);
            exec.scheduleWithFixedDelay(this::cleanupSafely, 5, 60, TimeUnit.SECONDS);
            enabled = true;
            reason = "serving " + dir;
            log.info("control: serving {} (mode {}, pid {})", dir, mode, pid);
            return true;
        } catch (Exception e) {
            releaseLock();
            return off("the control directory cannot be used: " + e);
        }
    }

    /** Closes the channel and tells waiting CLIs at once that the instance stopped. */
    public synchronized void stop() {
        if (exec != null) {
            exec.shutdownNow();
            try { exec.awaitTermination(5, TimeUnit.SECONDS); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            exec = null;
        }
        if (enabled) {
            try { writeHeartbeat(true); } catch (Exception e) { log.warn("control: last heartbeat not written: {}", e.toString()); }
        }
        releaseLock();
        enabled = false;
        reason = "stopped";
    }

    private boolean off(String why) {
        enabled = false;
        reason = why;
        log.error("control: channel OFF - {}", why);
        return false;
    }

    // ------------------------------------------------------------------ lock

    private boolean takeLock() throws IOException {
        lockChannel = FileChannel.open(dir.resolve(ControlProtocol.LOCK_FILE),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        try {
            lock = lockChannel.tryLock();
        } catch (OverlappingFileLockException e) {
            lock = null;   // this JVM already holds it: a second channel in the same process
        }
        if (lock == null) {
            lockChannel.close();
            lockChannel = null;
            return false;
        }
        return true;
    }

    private void releaseLock() {
        try { if (lock != null) lock.release(); } catch (Exception ignored) { }
        try { if (lockChannel != null) lockChannel.close(); } catch (Exception ignored) { }
        lock = null;
        lockChannel = null;
    }

    /** ": pid N on host H, mode M" from the other instance's heartbeat, or "" when unreadable. */
    private String holder() {
        try {
            ControlProtocol.Instance i = mapper.readValue(
                    Files.readAllBytes(dir.resolve(ControlProtocol.INSTANCE_FILE)), ControlProtocol.Instance.class);
            return ": pid " + i.pid + " on " + i.host + ", mode " + i.mode + ", last seen " + i.lastSeen;
        } catch (Exception e) {
            return "";
        }
    }

    // ------------------------------------------------------------------ heartbeat

    private void heartbeatSafely() {
        try {
            writeHeartbeat(false);
        } catch (Exception e) {
            log.debug("control: heartbeat not written this time: {}", e.toString());
        }
    }

    private void writeHeartbeat(boolean stopped) throws IOException {
        ControlProtocol.Instance i = new ControlProtocol.Instance();
        i.pid = pid;
        i.host = host;
        i.mode = mode;
        i.build = build;
        i.osUser = osUser;
        i.startedAt = startedAt;
        i.lastSeenMillis = System.currentTimeMillis();
        i.lastSeen = now();
        i.stopped = stopped;
        i.controlDir = dir.toString();
        publish(dir, ControlProtocol.INSTANCE_FILE, mapper.writeValueAsBytes(i), true);
    }

    // ------------------------------------------------------------------ requests

    private void pollSafely() {
        try {
            poll();
        } catch (Throwable t) {
            log.error("control: polling the inbox failed: {}", t.toString());
        }
    }

    /** One pass over the inbox: every published request, oldest first. Package-private for tests. */
    void poll() throws IOException {
        Path inbox = ControlProtocol.inbox(dir);
        List<Path> found = new ArrayList<Path>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(inbox)) {
            for (Path p : HostFiles.inHostOrder(ds)) {
                if (p.getFileName().toString().endsWith(".json")) found.add(p);
            }
        }
        if (found.isEmpty()) return;
        final Map<Path, Long> mtime = new LinkedHashMap<Path, Long>();
        for (Path p : found) {
            try { mtime.put(p, Files.getLastModifiedTime(p).toMillis()); } catch (IOException e) { mtime.put(p, Long.MAX_VALUE); }
        }
        Collections.sort(found, new Comparator<Path>() {
            @Override public int compare(Path a, Path b) {
                int c = Long.compare(mtime.get(a), mtime.get(b));
                return c != 0 ? c : HostFiles.NAME_ORDER.compare(a.getFileName().toString(), b.getFileName().toString());
            }
        });
        for (Path p : found) {
            String name = p.getFileName().toString();
            Path claimed = ControlProtocol.work(dir).resolve(name);
            if (!HostFiles.renameNoReplace(p.toFile(), claimed.toFile())) continue;   // cancelled by its CLI, or gone
            handle(claimed, name.substring(0, name.length() - ".json".length()), mtime.get(p));
        }
    }

    private void handle(Path claimed, String id, long publishedAt) {
        ControlProtocol.Response resp;
        ControlProtocol.Request req = null;
        String user = "";
        boolean answerable = ControlProtocol.ID.matcher(id).matches();
        try {
            user = OsIdentity.owner(claimed);
            if (!answerable) {
                log.warn("control: ignored {} from {}: not a request name", claimed.getFileName(), user);
                Files.deleteIfExists(claimed);
                return;
            }
            if (Files.size(claimed) > ControlProtocol.MAX_REQUEST_BYTES) {
                resp = ControlProtocol.Response.refused(ExitCodes.USAGE, "request larger than 64 KB, not read");
            } else {
                try {
                    req = mapper.readValue(Files.readAllBytes(claimed), ControlProtocol.Request.class);
                } catch (Exception e) {
                    req = null;
                }
                if (req == null) {
                    resp = ControlProtocol.Response.refused(ExitCodes.USAGE, "malformed request");
                } else if (req.protocol != ControlProtocol.VERSION) {
                    resp = ControlProtocol.Response.refused(ExitCodes.PROTOCOL, "protocol " + req.protocol
                            + " is not understood by this instance, which speaks " + ControlProtocol.VERSION);
                } else if (!id.equals(req.id)) {
                    resp = ControlProtocol.Response.refused(ExitCodes.USAGE, "the request id does not match its file name");
                } else {
                    Map<String, String> extra = extra(id, user, req.declaredUser);
                    long age = System.currentTimeMillis() - publishedAt;
                    if (age > ControlProtocol.EXPIRY_MS) {
                        backend.auditExpired(req, user, extra);
                        resp = ControlProtocol.Response.refused(ExitCodes.EXPIRED, "the request waited " + (age / 1000)
                                + " s for the instance, more than " + (ControlProtocol.EXPIRY_MS / 1000) + " s: not executed");
                    } else {
                        try {
                            resp = backend.execute(req, user, extra);
                        } catch (Throwable t) {
                            log.error("control: request {} failed: {}", id, t.toString(), t);
                            resp = ControlProtocol.Response.refused(ExitCodes.INSTANCE_ERROR, "the instance failed: " + t);
                        }
                    }
                }
            }
        } catch (Exception e) {
            resp = ControlProtocol.Response.refused(ExitCodes.INSTANCE_ERROR, "the instance could not read the request: " + e);
        }
        if (resp == null) resp = ControlProtocol.Response.refused(ExitCodes.INSTANCE_ERROR, "no answer");
        resp.id = id;
        resp.user = user;
        resp.protocol = ControlProtocol.VERSION;
        // Command metadata only: never variable values, never data (CLAUDE.md, PII).
        log.info("control: request {} command={} feed={} run={} user={} -> code {}", id,
                req == null ? "?" : req.command, req == null ? "" : n(req.feedId),
                req == null ? "" : n(req.runId), user, resp.code);
        try {
            publish(ControlProtocol.outbox(dir), id + ".json", mapper.writeValueAsBytes(resp), true);
        } catch (Exception e) {
            log.error("control: answer to {} not written: {}", id, e.toString());
        }
        try { Files.deleteIfExists(claimed); } catch (IOException e) { log.warn("control: {} not removed: {}", claimed, e.toString()); }
    }

    /**
     * The details every audit line of one request carries. The declared identity is written only
     * when it names another account than the owner: then it is evidence worth keeping.
     */
    static Map<String, String> extra(String id, String owner, String declared) {
        Map<String, String> m = new LinkedHashMap<String, String>();
        m.put("channel", "cli");
        m.put("request", id);
        if (declared != null && !declared.trim().isEmpty() && !OsIdentity.sameAccount(owner, declared.trim())) {
            m.put("declaredUser", declared.trim());
        }
        return m;
    }

    // ------------------------------------------------------------------ cleanup

    private void cleanupSafely() {
        try {
            long limit = System.currentTimeMillis() - ControlProtocol.ANSWER_TTL_MS;
            sweep(ControlProtocol.outbox(dir), limit, ".json", ".tmp");
            sweep(ControlProtocol.inbox(dir), limit, ".tmp", ".cancelled");
            sweep(ControlProtocol.work(dir), limit, ".json");
        } catch (Throwable t) {
            log.warn("control: cleanup failed: {}", t.toString());
        }
    }

    private void sweep(Path folder, long olderThan, String... suffixes) throws IOException {
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(folder)) {
            for (Path p : ds) {
                String n = p.getFileName().toString();
                boolean match = false;
                for (String s : suffixes) if (n.endsWith(s)) match = true;
                if (!match || !Files.isRegularFile(p)) continue;
                if (Files.getLastModifiedTime(p).toMillis() < olderThan) {
                    try { Files.deleteIfExists(p); } catch (IOException ignored) { }
                }
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    /** Writes {@code <name>.tmp} and renames it to {@code name}: a reader sees the old file or the new one. */
    static void publish(Path folder, String name, byte[] content, boolean replace) throws IOException {
        Path tmp = folder.resolve(name + ".tmp");
        Files.write(tmp, content);
        if (replace) {
            Files.move(tmp, folder.resolve(name), StandardCopyOption.REPLACE_EXISTING);
        } else if (!HostFiles.renameNoReplace(tmp.toFile(), folder.resolve(name).toFile())) {
            Files.deleteIfExists(tmp);
            throw new IOException(folder.resolve(name) + " already exists");
        }
    }

    private static String n(String s) { return s == null ? "" : s; }

    private static String now() { return OffsetDateTime.now().format(TS); }

    public static long pid() {
        try {
            String n = ManagementFactory.getRuntimeMXBean().getName();
            int at = n.indexOf('@');
            return Long.parseLong(at > 0 ? n.substring(0, at) : n);
        } catch (Exception e) {
            return -1;
        }
    }

    public static String host() {
        try { return InetAddress.getLocalHost().getHostName(); } catch (Exception e) { return ""; }
    }

    /** For a test: the file the CLI would read. */
    File instanceFile() { return dir.resolve(ControlProtocol.INSTANCE_FILE).toFile(); }
}
