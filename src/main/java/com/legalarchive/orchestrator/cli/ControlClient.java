package com.legalarchive.orchestrator.cli;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.legalarchive.orchestrator.config.BuildInfo;
import com.legalarchive.orchestrator.control.ControlProtocol;
import com.legalarchive.orchestrator.control.ExitCodes;
import com.legalarchive.orchestrator.control.OsIdentity;
import com.legalarchive.orchestrator.platform.HostFiles;

/**
 * The CLI side of the control directory (spec {@code .claude/HEADLESS.md} sections 5 and 6). JDK and
 * Jackson only: no Spring, no configuration read, no feed directory touched.
 *
 * <p>A request is published by writing {@code inbox/<id>.tmp} and renaming it to {@code <id>.json};
 * the answer is read from {@code outbox/<id>.json}. A request nobody claims within
 * {@link #PICKUP_MS} is cancelled by renaming it to {@code <id>.cancelled}: that rename and the
 * instance's claim cannot both succeed, so a cancelled request is never executed.
 */
final class ControlClient {

    static final long PICKUP_MS = 30000L;
    static final long ANSWER_MS = 120000L;
    private static final long POLL_MS = 200L;
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX");

    private final Path dir;
    private final PrintStream err;
    private final ObjectMapper mapper = ControlProtocol.mapper();
    private boolean buildWarned;

    ControlClient(Path dir, PrintStream err) {
        this.dir = dir;
        this.err = err;
    }

    /** A refusal of the CLI itself, with the exit code to return. */
    static final class Failure extends Exception {
        private static final long serialVersionUID = 1L;
        final int code;
        Failure(int code, String message) { super(message); this.code = code; }
    }

    /** The heartbeat, checked: an instance that is serving this directory now, or a Failure. */
    ControlProtocol.Instance instance() throws Failure {
        if (!Files.isDirectory(dir)) {
            throw new Failure(ExitCodes.LOCAL, "control directory not found: " + dir);
        }
        ControlProtocol.Instance i;
        try {
            i = mapper.readValue(Files.readAllBytes(dir.resolve(ControlProtocol.INSTANCE_FILE)), ControlProtocol.Instance.class);
        } catch (NoSuchFileException e) {
            throw new Failure(ExitCodes.NO_INSTANCE, "no OpenProteo instance has served " + dir + " yet");
        } catch (AccessDeniedException e) {
            throw new Failure(ExitCodes.LOCAL, "your account may not read " + e.getFile());
        } catch (IOException e) {
            throw new Failure(ExitCodes.LOCAL, "the heartbeat of the instance could not be read: " + e);
        }
        if (i.protocol != ControlProtocol.VERSION) {
            throw new Failure(ExitCodes.PROTOCOL, "the instance speaks protocol " + i.protocol + ", this command line "
                    + ControlProtocol.VERSION + ": use the openproteo-headless.war of the same build");
        }
        if (i.stopped) {
            throw new Failure(ExitCodes.NO_INSTANCE, "the instance (pid " + i.pid + " on " + i.host + ") stopped at " + i.lastSeen);
        }
        long age = System.currentTimeMillis() - i.lastSeenMillis;
        if (age > ControlProtocol.HEARTBEAT_STALE_MS) {
            throw new Failure(ExitCodes.NO_INSTANCE, "no instance is serving " + dir + ": the last one (pid " + i.pid
                    + " on " + i.host + ") was seen at " + i.lastSeen + ", " + (age / 1000) + " s ago");
        }
        String mine = BuildInfo.id();
        if (!buildWarned && i.build != null && !i.build.isEmpty() && !i.build.equals(mine)) {
            err.println("warning: the instance runs build " + i.build + ", this command line is build " + mine);
            buildWarned = true;
        }
        return i;
    }

    /** Sends one request and waits for its answer. */
    ControlProtocol.Response call(ControlProtocol.Request req) throws Failure {
        instance();
        String id = UUID.randomUUID().toString();
        req.protocol = ControlProtocol.VERSION;
        req.id = id;
        req.created = OffsetDateTime.now().format(TS);
        req.declaredUser = OsIdentity.declared();
        req.cliBuild = BuildInfo.id();

        Path inbox = ControlProtocol.inbox(dir);
        Path tmp = inbox.resolve(id + ".tmp");
        Path published = inbox.resolve(id + ".json");
        try {
            Files.write(tmp, mapper.writeValueAsBytes(req), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        } catch (AccessDeniedException e) {
            throw new Failure(ExitCodes.LOCAL, "your account may not create files in " + inbox
                    + ": the inbox is open to the OpenProteo operators only");
        } catch (IOException e) {
            throw new Failure(ExitCodes.LOCAL, "the request could not be written in " + inbox + ": " + e);
        }
        if (!HostFiles.renameNoReplace(tmp.toFile(), published.toFile())) {
            try { Files.deleteIfExists(tmp); } catch (IOException ignored) { }
            throw new Failure(ExitCodes.LOCAL, "the request could not be published in " + inbox);
        }

        Path answer = ControlProtocol.outbox(dir).resolve(id + ".json");
        long start = System.currentTimeMillis();
        boolean claimed = false;
        while (true) {
            if (Files.exists(answer)) return read(answer);
            long elapsed = System.currentTimeMillis() - start;
            if (!claimed && elapsed > PICKUP_MS) {
                Path cancelled = inbox.resolve(id + ".cancelled");
                if (HostFiles.renameNoReplace(published.toFile(), cancelled.toFile())) {
                    try { Files.deleteIfExists(cancelled); } catch (IOException ignored) { }
                    throw new Failure(ExitCodes.NO_INSTANCE, "the instance did not pick the request up within "
                            + (PICKUP_MS / 1000) + " s; it was withdrawn and not executed");
                }
                claimed = true;   // the instance has it: wait for its answer
            }
            if (elapsed > ANSWER_MS) {
                throw new Failure(ExitCodes.NO_INSTANCE, "the instance took the request but gave no answer within "
                        + (ANSWER_MS / 1000) + " s; it may have been executed: check with the status command");
            }
            try { Thread.sleep(POLL_MS); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new Failure(ExitCodes.LOCAL, "interrupted while waiting for the answer");
            }
        }
    }

    private ControlProtocol.Response read(Path answer) throws Failure {
        IOException last = null;
        for (int i = 0; i < 10; i++) {
            try {
                ControlProtocol.Response r = mapper.readValue(Files.readAllBytes(answer), ControlProtocol.Response.class);
                try { Files.deleteIfExists(answer); } catch (IOException ignored) { }   // refused on a sticky outbox: the instance sweeps it
                return r;
            } catch (IOException e) {
                last = e;
                try { Thread.sleep(POLL_MS); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
            }
        }
        throw new Failure(ExitCodes.LOCAL, "the answer could not be read: " + last);
    }
}
