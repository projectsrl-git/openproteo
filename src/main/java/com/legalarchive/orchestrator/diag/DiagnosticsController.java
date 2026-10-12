package com.legalarchive.orchestrator.diag;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The diagnostic snapshots on the Platform page. Spec: {@code .claude/STALL_DIAGNOSTICS.md}.
 *
 * <p>Like the rest of the Platform page it is public until authentication exists. A snapshot
 * holds thread stacks, engine state and timings - see {@link StallSnapshot} for what it never
 * holds.
 */
@RestController
public class DiagnosticsController {

    private final DiagnosticsService service;

    public DiagnosticsController(DiagnosticsService service) {
        this.service = service;
    }

    /** The snapshots kept in memory, newest first, without their text. */
    @GetMapping("/api/diagnostics")
    public ResponseEntity<Map<String, Object>> list() {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        for (DiagnosticsService.Entry e : service.entries()) rows.add(row(e));
        out.put("ok", Boolean.TRUE);
        out.put("snapshots", rows);
        out.put("stallSeconds", StallWatchdog.STALL_MS / 1000);
        out.put("checkEverySeconds", DiagnosticsService.CHECK_EVERY_SEC);
        out.put("keptInMemory", DiagnosticsService.KEEP_IN_MEMORY);
        out.put("keptOnDisk", DiagnosticsService.KEEP_ON_DISK);
        return ResponseEntity.ok(out);
    }

    /** A snapshot now. About ten seconds; refused within thirty seconds of the previous one. */
    @PostMapping("/api/diagnostics/snapshot")
    public ResponseEntity<Map<String, Object>> take(HttpServletRequest req) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        DiagnosticsService.Entry e = service.takeNow(req == null ? null : req.getRemoteAddr());
        if (e == null) {
            out.put("ok", Boolean.FALSE);
            out.put("error", "A snapshot was taken less than " + (DiagnosticsService.MANUAL_FLOOR_MS / 1000)
                    + " seconds ago: download that one, or wait.");
            return ResponseEntity.ok(out);
        }
        out.put("ok", Boolean.TRUE);
        out.put("snapshot", row(e));
        return ResponseEntity.ok(out);
    }

    /** The text of one snapshot, as a file to save. */
    @GetMapping("/api/diagnostics/{id}")
    public ResponseEntity<byte[]> download(@PathVariable("id") String id) {
        DiagnosticsService.Entry e = service.entry(id);
        if (e == null) {
            return ResponseEntity.status(404).contentType(MediaType.TEXT_PLAIN)
                    .body(("No snapshot " + id + " in memory: only the last " + DiagnosticsService.KEEP_IN_MEMORY
                            + " are kept, and none survives a restart (look in the log directory, diag/).").getBytes(StandardCharsets.UTF_8));
        }
        HttpHeaders h = new HttpHeaders();
        h.set(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"openproteo-diagnostic-" + e.id + ".txt\"");
        return ResponseEntity.ok().headers(h).contentType(new MediaType("text", "plain", StandardCharsets.UTF_8))
                .body(e.text.getBytes(StandardCharsets.UTF_8));
    }

    private static Map<String, Object> row(DiagnosticsService.Entry e) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("id", e.id);
        m.put("takenAt", e.takenAt);
        m.put("automatic", e.automatic);
        m.put("reason", e.reason);
        m.put("feedId", e.feedId);
        m.put("runId", e.runId);
        m.put("sizeKb", (e.text.length() + 1023) / 1024);
        m.put("savedAs", e.savedAs);
        m.put("saveError", e.saveError);
        return m;
    }
}
