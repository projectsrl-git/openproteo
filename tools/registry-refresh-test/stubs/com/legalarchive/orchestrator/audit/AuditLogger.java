package com.legalarchive.orchestrator.audit;
import java.nio.file.Path;
import java.util.*;
/** Test double: records what the registry asks to be audited. */
public class AuditLogger {
    public static final List<String> EVENTS = new ArrayList<String>();
    public void log(Path auditFile, String feedId, String runId, String node, String event, String user, Map<String, String> details) {
        EVENTS.add(event + ":" + feedId);
    }
}
