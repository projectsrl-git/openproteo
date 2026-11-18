package com.legalarchive.orchestrator.control;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The engine lock of the headless artifact (spec {@code .claude/HEADLESS.md} section 4.3).
 *
 * <p>{@code <workflowsDir>/.openproteo-engine.lock}, held with {@code FileChannel.tryLock} for the
 * life of the JVM; who holds it goes to {@code .openproteo-engine.json} beside it (on Windows a
 * locked region cannot be read by another process, so the details cannot live in the lock file).
 * The lock dies with the process, so a crash leaves nothing to clean up.
 *
 * <p><b>Only the headless artifact takes it</b> (Q10, answered by the author 2026-11-18): two headless
 * instances on the same data are impossible, and the GUI artifacts are left exactly as they were.
 */
public final class EngineLock {

    public static final String LOCK_FILE = ".openproteo-engine.lock";
    public static final String INFO_FILE = ".openproteo-engine.json";

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX");

    /** Held for the life of the JVM: never released, never garbage-collected. */
    private static FileChannel channel;
    private static FileLock lock;

    private EngineLock() { }

    /**
     * Takes the lock, or throws with the reason: held by another process (named from its info
     * file), or impossible to create. Calling it twice in one JVM is harmless.
     */
    public static synchronized Path acquire(String workflowsDir, String artifact) {
        String w = (workflowsDir == null || workflowsDir.trim().isEmpty()) ? "./workflows" : workflowsDir.trim();
        Path dir = Paths.get(w).toAbsolutePath().normalize();
        Path file = dir.resolve(LOCK_FILE);
        if (lock != null) return file;
        if (!Files.isDirectory(dir)) {
            throw new IllegalStateException("the workflows directory does not exist: " + dir);
        }
        FileChannel ch = null;
        FileLock l;
        try {
            ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            try {
                l = ch.tryLock();
            } catch (OverlappingFileLockException e) {
                l = null;
            }
        } catch (IOException e) {
            close(ch);
            throw new IllegalStateException("the engine lock " + file + " cannot be created: " + e.getMessage());
        }
        if (l == null) {
            close(ch);
            throw new IllegalStateException("another headless instance runs on these workflows" + holder(dir));
        }
        channel = ch;
        lock = l;
        Map<String, Object> info = new LinkedHashMap<String, Object>();
        info.put("pid", ControlChannel.pid());
        info.put("host", ControlChannel.host());
        info.put("artifact", artifact);
        info.put("osUser", OsIdentity.declared());
        info.put("startedAt", OffsetDateTime.now().format(TS));
        try {
            ControlChannel.publish(dir, INFO_FILE, ControlProtocol.mapper().writeValueAsBytes(info), true);
        } catch (IOException e) {
            // the lock is what protects; the details only explain it
        }
        return file;
    }

    private static String holder(Path dir) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> m = ControlProtocol.mapper().readValue(Files.readAllBytes(dir.resolve(INFO_FILE)), Map.class);
            return ": pid " + m.get("pid") + " on " + m.get("host") + ", started " + m.get("startedAt");
        } catch (Exception e) {
            return "";
        }
    }

    private static void close(FileChannel ch) {
        try { if (ch != null) ch.close(); } catch (IOException ignored) { }
    }
}
